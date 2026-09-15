package com.gpmanager.persistence;

import com.google.gson.Gson;
import com.gpmanager.model.ProfitSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
        SavedState state = new SavedState(new ProfitSession(name, 1_000L), Collections.emptyList());
        state.setRevision(revision);
        return state;
    }

    @Test
    public void accountScopesDoNotMixAndLegacyStaysUnassigned() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        Files.writeString(root.resolve("sessions.json"),
            "{\"activeSession\":{\"name\":\"Legacy Shared\",\"transactions\":[]},\"history\":[]}");

        SessionRepository store = new SessionRepository(new Gson(), root, null, true);
        assertTrue(store.hasUnassignedLegacyData());
        assertFalse(store.isBound());
        assertFalse(store.save(named("Should not write", 1L)));

        TrackingIdentity alice = TrackingIdentity.ofRsProfileKey("rsprofile.alice");
        TrackingIdentity bob = TrackingIdentity.ofRsProfileKey("rsprofile.bob");

        store.bindIdentity(alice);
        assertTrue(store.save(named("Alice General", 1L)));
        store.bindIdentity(bob);
        assertTrue(store.save(named("Bob General", 1L)));

        store.bindIdentity(alice);
        assertEquals("Alice General", store.load().getActiveSession().getName());
        store.bindIdentity(bob);
        assertEquals("Bob General", store.load().getActiveSession().getName());

        // Legacy remains at root until explicit claim.
        assertTrue(Files.exists(root.resolve("sessions.json")));
        assertTrue(store.loadUnassignedLegacy().isPresent());
        assertEquals("Legacy Shared", store.loadUnassignedLegacy().get().getActiveSession().getName());

        assertTrue(store.claimUnassignedLegacy(alice));
        store.bindIdentity(alice);
        assertEquals("Legacy Shared", store.load().getActiveSession().getName());
        assertFalse(Files.exists(root.resolve("sessions.json")));
        store.bindIdentity(bob);
        assertEquals("Bob General", store.load().getActiveSession().getName());
    }

    @Test
    public void resetFailureAfterPrimaryStillCommitsAndInvalidatesObsoleteBackup() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository baseline = new SessionRepository(new Gson(), directory);
        baseline.save(named("Old history", 1L));
        baseline.save(named("Older backup seed", 2L));

        AtomicInteger backupReplacements = new AtomicInteger();
        SessionRepository failing = new SessionRepository(new Gson(), directory)
        {
            @Override
            void replaceFile(Path source, Path target) throws IOException
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
                super.replaceFile(source, target);
            }
        };

        SavedState reset = named("Fresh reset", 3L);
        SessionRepository.ReplaceOutcome outcome = failing.replaceStateDetailed(reset);
        assertTrue(outcome.isCommitted());
        assertEquals("Fresh reset", failing.load().getActiveSession().getName());
        // Cleared data must not reappear via backup fallback.
        Files.deleteIfExists(directory.resolve("sessions.json"));
        // If backup still exists it must also be the reset payload, or be gone.
        if (Files.exists(directory.resolve("sessions.backup.json")))
        {
            assertEquals("Fresh reset",
                new SessionRepository(new Gson(), directory).load().getActiveSession().getName());
        }
        else
        {
            assertEquals(null,
                new SessionRepository(new Gson(), directory).load().getActiveSession());
        }
    }

    @Test
    public void staleRevisionCannotOverwriteNewerDiskState() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), directory);
        assertTrue(repository.save(named("First", 1L)));
        assertTrue(repository.save(named("Second", 2L)));
        assertFalse(repository.save(named("Stale", 1L)));
        assertEquals("Second", repository.load().getActiveSession().getName());
    }

    @Test
    public void twoWritersFromSameBaseCannotBothCommit() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository first = new SessionRepository(new Gson(), directory);
        assertTrue(first.save(named("Shared", 10L)));
        long base = first.load().getRevision();
        assertEquals(10L, base);

        SessionRepository writerA = new SessionRepository(new Gson(), directory);
        writerA.load();
        SessionRepository writerB = new SessionRepository(new Gson(), directory);
        writerB.load();

        SavedState a = named("From A", 11L);
        SavedState b = named("From B", 11L);
        WriteIntent intentA = WriteIntent.unbound(writerA.getScopeGeneration(), base, a);
        WriteIntent intentB = WriteIntent.unbound(writerB.getScopeGeneration(), base, b);

        assertTrue(writerA.save(intentA));
        assertFalse("Second writer must lose the CAS race", writerB.save(intentB));
        assertEquals("From A", new SessionRepository(new Gson(), directory).load().getActiveSession().getName());
    }

    @Test
    public void queuedWriteKeepsImmutableAccountDestinationAfterSwitch() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository store = new SessionRepository(new Gson(), root, null, true);
        TrackingIdentity alice = TrackingIdentity.ofRsProfileKey("rsprofile.alice");
        TrackingIdentity bob = TrackingIdentity.ofRsProfileKey("rsprofile.bob");

        store.bindIdentity(alice);
        assertTrue(store.save(named("Alice-1", 1L)));
        long aliceBase = store.getLastKnownDiskRevision();
        long aliceGeneration = store.getScopeGeneration();

        SavedState pending = named("Alice-pending", aliceBase + 1L);
        WriteIntent aliceIntent = new WriteIntent(alice, aliceGeneration, aliceBase, pending);

        store.bindIdentity(bob);
        assertTrue(store.save(named("Bob-1", 1L)));

        // Delayed Alice write must still land in Alice's folder, not Bob's.
        assertTrue(store.save(aliceIntent));
        store.bindIdentity(alice);
        assertEquals("Alice-pending", store.load().getActiveSession().getName());
        store.bindIdentity(bob);
        assertEquals("Bob-1", store.load().getActiveSession().getName());
    }

    @Test
    public void writerSurvivesShutdownThenRestart() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), directory);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);

        writer.submit(WriteIntent.unbound(
            repository.getScopeGeneration(),
            repository.getLastKnownDiskRevision(),
            named("Before disable", 1L)));
        assertTrue(writer.flush(Duration.ofSeconds(5)));
        writer.shutdown(Duration.ofSeconds(5));

        writer.start();
        long base = repository.load().getRevision();
        writer.resetAppliedRevision(base);
        writer.submit(WriteIntent.unbound(
            repository.getScopeGeneration(),
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
        SessionRepository repository = new SessionRepository(new Gson(), directory);
        ExecutorService sameThread = Executors.newSingleThreadExecutor();
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository, sameThread);

        long generation = repository.getScopeGeneration();
        writer.submit(WriteIntent.unbound(generation, 0L, named("Old", 1L)));
        writer.submit(WriteIntent.unbound(generation, 0L, named("New", 2L)));
        assertTrue(writer.flush(Duration.ofSeconds(5)));
        assertEquals("New", repository.load().getActiveSession().getName());
        assertEquals(2L, writer.getAppliedRevision());
        assertEquals(SaveStatus.State.OK, writer.getStatus().getState());

        long base = repository.getLastKnownDiskRevision();
        writer.submit(WriteIntent.unbound(generation, base, named("Stale again", 1L)));
        assertTrue(writer.flush(Duration.ofSeconds(5)));
        assertEquals("New", repository.load().getActiveSession().getName());
        sameThread.shutdownNow();
    }

    @Test
    public void shutdownFlushPersistsDetachedSnapshot() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), directory);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(
            repository,
            Executors.newSingleThreadExecutor());
        writer.submit(WriteIntent.unbound(
            repository.getScopeGeneration(),
            0L,
            named("Shutdown save", 1L)));
        writer.shutdown(Duration.ofSeconds(5));
        assertEquals("Shutdown save", repository.load().getActiveSession().getName());
        assertNotEquals(SaveStatus.State.NEVER_SAVED, writer.getStatus().getState());
    }
}
