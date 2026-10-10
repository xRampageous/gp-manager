package com.gpmanager;

import com.google.gson.Gson;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.stream.Stream;
import net.runelite.api.Client;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Charter section 14/15 interruption classes for the save/replace/restore commit protocol: every
 * crash point resolves to exactly one of "old valid state untouched" or "new valid state committed
 * and finished idempotently on the next load". Never a mix.
 */
public class CommitInterruptionTest
{
    private static final String PROFILE = "rsprofile.interrupt";
    private static final TrackingIdentity IDENTITY = new TrackingIdentity(PROFILE, TrackingIdentity.ACCOUNT_HASH_INVALID);
    private static final long NOW = 1_800_000_000_000L;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static SavedState named(String name, long revision)
    {
        SavedState state = new SavedState(new Session(name, 1_000L), null, Collections.emptyList());
        state.setRevision(revision);
        return state;
    }

    private static SessionRepository bound(Path root)
    {
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
        repository.bindIdentity(IDENTITY);
        return repository;
    }

    /** A repository that fails the first replace whose target file has {@code failingTarget}'s name. */
    private static SessionRepository failingAt(Path root, String failingTarget)
    {
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true)
        {
            @Override
            void replaceFile(Filepath source, Filepath target) throws IOException
            {
                if (target.getFileName().toString().equals(failingTarget))
                {
                    throw new IOException("Simulated crash replacing " + failingTarget);
                }
                super.replaceFile(source, target);
            }
        };
        repository.bindIdentity(IDENTITY);
        return repository;
    }

    private static String primary(SessionRepository repository) throws IOException
    {
        return Files.readString(FilepathTestSupport.path(repository.dataDir().joinSegment("sessions.json")), StandardCharsets.UTF_8);
    }

    private static String activeName(SessionRepository repository)
    {
        return repository.load().getActiveSession().getName();
    }

    @Test
    public void crashBeforeThePrimaryIsReplacedLeavesTheOldStateAndRecoversCleanly() throws Exception
    {
        for (String crashPoint : new String[] {"sessions.backup.json", "sessions.json"})
        {
            Path root = temporary.newFolder().toPath();
            SessionRepository good = bound(root);
            assertTrue(PersistenceProbe.save(good, named("Old", 1L)));
            String before = primary(good);

            SessionRepository failing = failingAt(root, crashPoint);
            failing.load();
            assertFalse(crashPoint, failing.save(new WriteIntent(IDENTITY, failing.scopeGeneration, 1L, named("New", 2L))));

            // Old valid state, byte-identical primary; the next load sees exactly that.
            SessionRepository reload = bound(root);
            assertEquals(crashPoint, "Old", activeName(reload));
            assertEquals(crashPoint, before, primary(reload));
            assertEquals(1L, reload.lastKnownDiskRevision);
            assertEquals("", reload.lastRecoveredFrom);

            // Recovery finishes idempotently: the next save advances from the old lineage and
            // leaves no staged leftovers behind.
            assertTrue(reload.save(new WriteIntent(IDENTITY, reload.scopeGeneration, 1L, named("After", 2L))));
            assertEquals("After", activeName(bound(root)));
            assertFalse(reload.dataDir().joinSegment("sessions.commit.stage").exists());
            assertFalse(reload.dataDir().joinSegment("sessions.next.json").exists());
        }
    }

    @Test
    public void renameFallbackStillCommitsOnlyValidatedFilesAndLeavesNoTemporaries() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository noAtomic = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true)
        {
            @Override
            void atomicMove(Filepath source, Filepath target) throws IOException
            {
                throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), target.toString(), "simulated volume");
            }
        };
        noAtomic.bindIdentity(IDENTITY);
        assertTrue(PersistenceProbe.save(noAtomic, named("First", 1L)));
        assertTrue(PersistenceProbe.save(noAtomic, named("Second", 2L)));
        assertTrue(noAtomic.replaceStateDetailed(new WriteIntent(IDENTITY, noAtomic.scopeGeneration, 2L, named("Reset", 3L))).isCommitted());
        assertEquals("Reset", activeName(bound(root)));
        try (Stream<Path> files = Files.list(FilepathTestSupport.path(noAtomic.dataDir())))
        {
            assertTrue(files.map(path -> path.getFileName().toString()).noneMatch(name -> name.endsWith(".tmp")));
        }
    }

    @Test
    public void aCorruptPrimaryFallsBackToTheCrashCopyAndReportsWhereFrom() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository repository = bound(root);
        assertTrue(PersistenceProbe.save(repository, named("One", 1L)));
        assertTrue(PersistenceProbe.save(repository, named("Two", 2L)));
        assertTrue(PersistenceProbe.save(repository, named("Three", 3L)));
        Path dir = FilepathTestSupport.path(repository.dataDir());
        assertEquals("one crash copy, no rotation (owner 2026-09-28)", 0, PersistenceProbe.rotatedBackupCount(repository));
        Files.writeString(dir.resolve("sessions.json"), "{not json", StandardCharsets.UTF_8);

        SessionRepository reload = bound(root);
        assertEquals("Two", activeName(reload));
        assertEquals("sessions.backup.json", reload.lastRecoveredFrom);
        assertEquals(2L, reload.lastKnownDiskRevision);
        try (Stream<Path> files = Files.list(dir))
        {
            assertTrue("the corrupt primary is preserved as evidence",
                files.map(path -> path.getFileName().toString()).anyMatch(name -> name.startsWith("sessions.corrupt-")));
        }
        // The next save continues from the recovered lineage.
        assertTrue(reload.save(new WriteIntent(IDENTITY, reload.scopeGeneration, 2L, named("Four", 3L))));
        assertEquals("Four", activeName(bound(root)));
        assertEquals("", bound(root).lastRecoveredFrom);
    }

    private static Client client()
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getAccountHash")) return 77L;
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == long.class) return 0L;
                return null;
            });
    }
}
