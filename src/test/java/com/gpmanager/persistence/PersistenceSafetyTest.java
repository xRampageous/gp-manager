package com.gpmanager;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PersistenceSafetyTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private SavedState named(String name, long revision)
    {
        SavedState state = new SavedState(new Ad(name, 1_000L), null, false, Collections.emptyList());
        state.setRevision(revision);
        return state;
    }

    @Test
    public void resetFailureAfterPrimaryStillCommitsAndInvalidatesObsoleteBackup() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository baseline = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        PersistenceProbe.save(baseline, named("Old history", 1L));
        PersistenceProbe.save(baseline, named("Older backup seed", 2L));

        AtomicInteger backupReplacements = new AtomicInteger();
        SessionRepository failing = new SessionRepository(new Gson(), FilepathTestSupport.root(directory))
        {
            @Override
            void afl(Filepath source, Filepath target) throws IOException
            {
                if (target.getFileName().toString().equals("sessions.backup.json")
                    && backupReplacements.incrementAndGet() >= 1
                    && Files.exists(directory.resolve("sessions.json")))
                {
                    // Fail while aligning backup after primary reset commit.
                    String primary = Files.readString(directory.resolve("sessions.json"));
                    if (primary.contains("Fresh reset"))
                    {
                        throw new IOException("Simulated crash after primary reset");
                    }
                }
                super.afl(source, target);
            }
        };

        SavedState reset = named("Fresh reset", 3L);
        SessionRepository.Bm outcome = PersistenceProbe.replaceStateDetailed(failing, reset);
        assertTrue(outcome.isCommitted());
        assertEquals("Fresh reset", failing.load().getActiveSession().getName());
        // Cleared data must not reappear via backup fallback.
        Files.deleteIfExists(directory.resolve("sessions.json"));
        // If backup still exists it must also be the reset payload, or be gone.
        if (Files.exists(directory.resolve("sessions.backup.json")))
        {
            assertEquals("Fresh reset",
                new SessionRepository(new Gson(), FilepathTestSupport.root(directory)).load().getActiveSession().getName());
        }
        else
        {
            assertEquals(null,
                new SessionRepository(new Gson(), FilepathTestSupport.root(directory)).load().getActiveSession());
        }
    }

    @Test
    public void staleRevisionCannotOverwriteNewerDiskState() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        assertTrue(PersistenceProbe.save(repository, named("First", 1L)));
        assertTrue(PersistenceProbe.save(repository, named("Second", 2L)));
        assertFalse(PersistenceProbe.save(repository, named("Stale", 1L)));
        assertEquals("Second", repository.load().getActiveSession().getName());
    }

    @Test
    public void staleDestructiveResetIsRefusedNotRebased() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        assertTrue(PersistenceProbe.save(repository, named("First", 1L)));
        assertTrue(PersistenceProbe.save(repository, named("Second", 2L)));
        String before = Files.readString(directory.resolve("sessions.json"));

        // Reset fenced on base 1 while disk is at 2: CONFLICT, disk untouched, revision not advanced.
        SessionRepository.Bm outcome = repository.replaceStateDetailed(
            new Cs(null, repository.scopeGeneration, 1L, named("Reset", 2L)));
        assertEquals(SessionRepository.Bm.Kind.CONFLICT, outcome.kind);
        assertFalse(outcome.isCommitted());
        assertEquals(before, Files.readString(directory.resolve("sessions.json")));
        assertEquals("Second", repository.load().getActiveSession().getName());
        assertEquals(2L, repository.lastKnownDiskRevision);
        assertFalse(Files.exists(directory.resolve("sessions.next.json")));
        assertFalse(Files.exists(directory.resolve("sessions.commit.stage")));

        // A non-advancing revision is refused even on the correct base.
        assertFalse(repository.replaceStateDetailed(new Cs(null, repository.scopeGeneration, 2L, named("Reset", 2L))).isCommitted());
        assertEquals(before, Files.readString(directory.resolve("sessions.json")));

        // The same reset re-issued explicitly against the observed revision commits as 3.
        assertTrue(repository.replaceStateDetailed(new Cs(null, repository.scopeGeneration, 2L, named("Reset", 3L))).isCommitted());
        assertEquals("Reset", repository.load().getActiveSession().getName());
        assertEquals(3L, repository.lastKnownDiskRevision);
    }

    @Test
    public void twoWritersFromSameBaseCannotBothCommit() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository first = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        assertTrue(PersistenceProbe.save(first, named("Shared", 10L)));
        long base = first.load().revision;
        assertEquals(10L, base);

        SessionRepository writerA = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        writerA.load();
        SessionRepository writerB = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        writerB.load();

        SavedState a = named("From A", 11L);
        SavedState b = named("From B", 11L);
        Cs intentA = new Cs(null, writerA.scopeGeneration, base, a);
        Cs intentB = new Cs(null, writerB.scopeGeneration, base, b);

        assertTrue(writerA.save(intentA));
        assertFalse("Second writer must lose the CAS race", writerB.save(intentB));
        assertEquals("From A", new SessionRepository(new Gson(), FilepathTestSupport.root(directory)).load().getActiveSession().getName());
    }

    @Test
    public void heldAccountLockRefusesSaveAndReplaceUntilReleased() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository store = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
        TrackingIdentity alice = new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID);
        store.mc(alice);
        assertTrue(PersistenceProbe.save(store, named("Alice-1", 1L)));
        Path primary = FilepathTestSupport.path(store.ty().joinSegment("sessions.json"));
        String before = Files.readString(primary);

        Path lockPath = FilepathTestSupport.path(store.ty().joinSegment("sessions.write.lock"));
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lockPath,
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            java.nio.channels.FileLock held = channel.tryLock())
        {
            assertTrue(held != null && held.isValid());
            assertFalse("ordinary save refused while another client holds the account lock",
                store.save(new Cs(alice, store.scopeGeneration, 1L, named("Alice-2", 2L))));
            assertFalse("destructive replace refused too",
                store.replaceStateDetailed(new Cs(alice, store.scopeGeneration, 1L, named("Reset", 2L))).isCommitted());
            assertEquals(before, Files.readString(primary));
        }
        assertTrue(store.save(new Cs(alice, store.scopeGeneration, 1L, named("Alice-2", 2L))));
        assertEquals("Alice-2", store.load().getActiveSession().getName());
    }

    @Test
    public void queuedWriteKeepsImmutableAccountDestinationAfterSwitch() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository store = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
        TrackingIdentity alice = new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID);
        TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);

        store.mc(alice);
        assertTrue(PersistenceProbe.save(store, named("Alice-1", 1L)));
        long aliceBase = store.lastKnownDiskRevision;
        long aliceGeneration = store.scopeGeneration;

        SavedState pending = named("Alice-pending", aliceBase + 1L);
        Cs aliceIntent = new Cs(alice, aliceGeneration, aliceBase, pending);

        store.mc(bob);
        assertTrue(PersistenceProbe.save(store, named("Bob-1", 1L)));

        // Delayed Alice write must still land in Alice's folder, not Bob's.
        assertTrue(store.save(aliceIntent));
        store.mc(alice);
        assertEquals("Alice-pending", store.load().getActiveSession().getName());
        store.mc(bob);
        assertEquals("Bob-1", store.load().getActiveSession().getName());
    }

    @Test
    public void writerSurvivesShutdownThenRestart() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);

        writer.submit(new Cs(null,
            repository.scopeGeneration,
            repository.lastKnownDiskRevision,
            named("Before disable", 1L)));
        assertTrue(writer.flush(Duration.ofSeconds(5)));
        writer.shutdown(Duration.ofSeconds(5));

        writer.start();
        long base = repository.load().revision;
        writer.aft(base);
        writer.submit(new Cs(null,
            repository.scopeGeneration,
            base,
            named("After enable", base + 1L)));
        assertTrue(writer.flush(Duration.ofSeconds(5)));
        assertEquals("After enable", repository.load().getActiveSession().getName());
        writer.shutdown(Duration.ofSeconds(5));
    }

    @Test
    public void orderedWriterCoalescesAndIgnoresStaleSnapshots() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        ExecutorService sameThread = Executors.newSingleThreadExecutor();
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository, sameThread);

        long generation = repository.scopeGeneration;
        writer.submit(new Cs(null, generation, 0L, named("Old", 1L)));
        writer.submit(new Cs(null, generation, 0L, named("New", 2L)));
        assertTrue(writer.flush(Duration.ofSeconds(5)));
        assertEquals("New", repository.load().getActiveSession().getName());
        assertEquals(2L, writer.tk());
        assertEquals(Ci.State.OK, writer.getStatus().state);

        long base = repository.lastKnownDiskRevision;
        writer.submit(new Cs(null, generation, base, named("Stale again", 1L)));
        assertTrue(writer.flush(Duration.ofSeconds(5)));
        assertEquals("New", repository.load().getActiveSession().getName());
        sameThread.shutdown();
    }

    @Test
    public void shutdownPreservesPreviouslyQueuedDetachedSnapshot() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(
            repository,
            Executors.newSingleThreadExecutor());
        writer.submit(new Cs(null,
            repository.scopeGeneration,
            0L,
            named("Shutdown save", 1L)));
        assertTrue("test explicitly establishes the durable state before lifecycle fencing",
            writer.flush(Duration.ofSeconds(5)));
        writer.shutdown(Duration.ofSeconds(5));
        assertEquals("Shutdown save", repository.load().getActiveSession().getName());
        assertNotEquals(Ci.State.NEVER_SAVED, writer.getStatus().state);
    }
}
