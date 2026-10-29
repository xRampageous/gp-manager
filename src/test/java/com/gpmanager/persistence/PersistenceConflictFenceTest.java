package com.gpmanager;

import com.google.gson.Gson;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** An external disk tip never becomes authority for a stale in-memory profile. */
public class PersistenceConflictFenceTest
{
    private static final TrackingIdentity ALICE = new TrackingIdentity("rsprofile.alice", -1L);
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Persistence coordinator(SessionRepository repository, OrderedPersistenceWriter writer)
    {
        Engine engine = new Engine(d -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig() {});
        return new Persistence(null, null, repository, writer, engine)
        {
            @Override TrackingIdentity resolveCurrentIdentity() { return ALICE; }
        };
    }

    private SessionRepository repository(Path root)
    {
        return new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
    }

    private void seed(Persistence coordinator)
    {
        JsonCodec.bind(new Gson());
        assertTrue(coordinator.switchIdentity(ALICE, true));
        coordinator.engine.ensureSession(1_000L);
        coordinator.engine.session().rename("Seed");
        assertTrue(coordinator.saveNow());
        assertTrue(coordinator.isTrackingReady());
    }

    private void externalWrite(Path root)
    {
        SessionRepository external = repository(root);
        external.bindIdentity(ALICE);
        SavedState state = external.load();
        FixtureReads.currentSession(state).rename("External");
        long base = state.revision;
        state.setRevision(base + 1L);
        assertTrue(external.save(new WriteIntent(ALICE, external.generation, base, state, null)));
    }

    private SavedState disk(Path root)
    {
        SessionRepository reader = repository(root);
        reader.bindIdentity(ALICE);
        return reader.load();
    }

    @Test
    public void aSecondAutosaveAndDirectRetryCannotOverwriteTheExternalProfile() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository repository = repository(root);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Persistence coordinator = coordinator(repository, writer);
        try
        {
            seed(coordinator);
            externalWrite(root);
            coordinator.engine.session().rename("Local stale");
            assertFalse(coordinator.saveNow());
            assertEquals(SaveStatus.State.CONFLICT, writer.status().state);
            coordinator.scheduleSave();
            assertFalse("1.7.0: a refused write is never acknowledged", writer.flush(Duration.ofSeconds(5)));
            assertEquals("External", FixtureReads.currentSession(disk(root)).name());
            assertFalse("new gameplay must wait for disk truth to be reloaded", coordinator.isTrackingReady());
            assertFalse(coordinator.saveNow());
            assertNotNull(coordinator.scopeRefusal());

            SavedState stale = coordinator.snapshot();
            stale.setRevision(50L);
            WriteIntent retry = new WriteIntent(ALICE, repository.generation,
                repository.revision, stale, null);
            writer.submit(retry);
            assertFalse("1.7.0: a refused write is never acknowledged", writer.flush(Duration.ofSeconds(5)));
            assertFalse("reset cannot bypass the same conflict fence", writer.replaceNow(retry).isCommitted());
            assertEquals("External", FixtureReads.currentSession(disk(root)).name());
            assertEquals(2L, disk(root).revision);
        }
        finally { writer.shutdown(Duration.ofSeconds(5)); }
    }

    @Test
    public void aDirectResetConflictExplainsRecoveryAndPreservesDisk() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository repository = repository(root);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Persistence coordinator = coordinator(repository, writer);
        try
        {
            seed(coordinator);
            SavedState reset = coordinator.snapshot();
            long base = repository.revision;
            externalWrite(root);
            assertFalse(writer.replaceNow(new WriteIntent(ALICE, repository.generation, base, reset, null)).isCommitted());
            assertEquals(SaveStatus.State.CONFLICT, writer.status().state);
            assertTrue(writer.status().detail.contains("Back up your current history, then restart RuneLite"));
            assertFalse(coordinator.isTrackingReady());
            assertEquals("External", FixtureReads.currentSession(disk(root)).name());
            assertEquals(2L, disk(root).revision);
        }
        finally { writer.shutdown(Duration.ofSeconds(5)); }
    }

    @Test
    public void anAlreadyQueuedSnapshotCannotRebaseDuringTheConflictCompletion() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true)
        {
            @Override boolean save(WriteIntent intent)
            {
                boolean ok = super.save(intent);
                if (!ok && revision != intent.base)
                {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { throw new AssertionError(ex); }
                }
                return ok;
            }
        };
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Persistence coordinator = coordinator(repository, writer);
        try
        {
            seed(coordinator);
            externalWrite(root);
            coordinator.engine.session().rename("Local stale");
            coordinator.scheduleSave();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Disk revision is observed, but the writer has not published CONFLICT yet.
            coordinator.scheduleSave();
            release.countDown();
            assertFalse("1.7.0: a refused write is never acknowledged", writer.flush(Duration.ofSeconds(5)));
            assertEquals("External", FixtureReads.currentSession(disk(root)).name());
            assertEquals(SaveStatus.State.CONFLICT, writer.status().state);
        }
        finally { release.countDown(); writer.shutdown(Duration.ofSeconds(5)); }
    }

    @Test
    public void aRestartLoadsTheExternalProfileBeforeSavingAgain() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository repository = repository(root);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Persistence stale = coordinator(repository, writer);
        try
        {
            seed(stale);
            externalWrite(root);
            assertFalse(stale.saveNow());
            assertFalse(stale.isTrackingReady());
        }
        finally { writer.shutdown(Duration.ofSeconds(5)); }

        SessionRepository reopened = repository(root);
        OrderedPersistenceWriter newWriter = new OrderedPersistenceWriter(reopened);
        Persistence fresh = coordinator(reopened, newWriter);
        try
        {
            assertTrue(fresh.switchIdentity(ALICE, true));
            assertEquals("External", fresh.engine.session().name());
            assertTrue(fresh.isTrackingReady());
            fresh.engine.session().rename("Reloaded local");
            assertTrue(fresh.saveNow());
            assertEquals("Reloaded local", FixtureReads.currentSession(disk(root)).name());
        }
        finally { newWriter.shutdown(Duration.ofSeconds(5)); }
    }
}
