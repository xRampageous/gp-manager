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

    private Ei coordinator(SessionRepository repository, OrderedPersistenceWriter writer)
    {
        Am engine = new Am(d -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig() {});
        return new Ei(null, null, repository, writer, engine)
        {
            @Override TrackingIdentity resolveCurrentIdentity() { return ALICE; }
        };
    }

    private SessionRepository repository(Path root)
    {
        return new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
    }

    private void seed(Ei coordinator)
    {
        JsonCodec.bind(new Gson());
        assertTrue(coordinator.ajy(ALICE, true));
        coordinator.engine.rm(1_000L);
        coordinator.engine.getActiveSession().rename("Seed");
        assertTrue(coordinator.aya());
        assertTrue(coordinator.isTrackingReady());
    }

    private void externalWrite(Path root)
    {
        SessionRepository external = repository(root);
        external.mc(ALICE);
        SavedState state = external.load();
        state.getActiveSession().rename("External");
        long base = state.revision;
        state.setRevision(base + 1L);
        assertTrue(external.save(new Cs(ALICE, external.scopeGeneration, base, state)));
    }

    private SavedState disk(Path root)
    {
        SessionRepository reader = repository(root);
        reader.mc(ALICE);
        return reader.load();
    }

    @Test
    public void aSecondAutosaveAndDirectRetryCannotOverwriteTheExternalProfile() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository repository = repository(root);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Ei coordinator = coordinator(repository, writer);
        try
        {
            seed(coordinator);
            externalWrite(root);
            coordinator.engine.getActiveSession().rename("Local stale");
            assertFalse(coordinator.aya());
            assertEquals(Ci.State.CONFLICT, writer.getStatus().state);
            coordinator.ahe();
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals("External", disk(root).getActiveSession().getName());
            assertFalse("new gameplay must wait for disk truth to be reloaded", coordinator.isTrackingReady());
            assertFalse(coordinator.aya());
            assertNotNull(coordinator.ahx());

            SavedState stale = coordinator.snapshot();
            stale.setRevision(50L);
            Cs retry = new Cs(ALICE, repository.scopeGeneration,
                repository.lastKnownDiskRevision, stale);
            writer.submit(retry);
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertFalse("reset cannot bypass the same conflict fence", writer.aic(retry).isCommitted());
            assertEquals("External", disk(root).getActiveSession().getName());
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
        Ei coordinator = coordinator(repository, writer);
        try
        {
            seed(coordinator);
            SavedState reset = coordinator.snapshot();
            long base = repository.lastKnownDiskRevision;
            externalWrite(root);
            assertFalse(writer.aic(new Cs(ALICE, repository.scopeGeneration, base, reset)).isCommitted());
            assertEquals(Ci.State.CONFLICT, writer.getStatus().state);
            assertTrue(writer.getStatus().detail.contains("back up current data, then restart RuneLite"));
            assertFalse(coordinator.isTrackingReady());
            assertEquals("External", disk(root).getActiveSession().getName());
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
            @Override boolean save(Cs intent)
            {
                boolean ok = super.save(intent);
                if (!ok && lastKnownDiskRevision != intent.expectedBaseRevision)
                {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
                }
                return ok;
            }
        };
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Ei coordinator = coordinator(repository, writer);
        try
        {
            seed(coordinator);
            externalWrite(root);
            coordinator.engine.getActiveSession().rename("Local stale");
            coordinator.ahe();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Disk revision is observed, but the writer has not published CONFLICT yet.
            coordinator.ahe();
            release.countDown();
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals("External", disk(root).getActiveSession().getName());
            assertEquals(Ci.State.CONFLICT, writer.getStatus().state);
        }
        finally { release.countDown(); writer.shutdown(Duration.ofSeconds(5)); }
    }

    @Test
    public void aRestartLoadsTheExternalProfileBeforeSavingAgain() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository repository = repository(root);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Ei stale = coordinator(repository, writer);
        try
        {
            seed(stale);
            externalWrite(root);
            assertFalse(stale.aya());
            assertFalse(stale.isTrackingReady());
        }
        finally { writer.shutdown(Duration.ofSeconds(5)); }

        SessionRepository reopened = repository(root);
        OrderedPersistenceWriter newWriter = new OrderedPersistenceWriter(reopened);
        Ei fresh = coordinator(reopened, newWriter);
        try
        {
            assertTrue(fresh.ajy(ALICE, true));
            assertEquals("External", fresh.engine.getActiveSession().getName());
            assertTrue(fresh.isTrackingReady());
            fresh.engine.getActiveSession().rename("Reloaded local");
            assertTrue(fresh.aya());
            assertEquals("Reloaded local", disk(root).getActiveSession().getName());
        }
        finally { newWriter.shutdown(Duration.ofSeconds(5)); }
    }
}
