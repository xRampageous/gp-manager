package com.gpmanager.persistence;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.diagnostics.DebugTrace;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ProfitSession;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WriterLineageAndIdentityTest
{
    static
    {
        com.gpmanager.persistence.JsonCodec.bind(new com.google.gson.Gson());
    }

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private SavedState named(String name, long revision)
    {
        SavedState state = new SavedState(new ProfitSession(name, 1_000L), Collections.emptyList());
        state.setRevision(revision);
        return state;
    }

    @Test
    public void laterSnapshotDuringOwnWriteMustPersist() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SessionRepository repository = new SessionRepository(new Gson(), directory)
        {
            @Override
            public boolean save(WriteIntent intent)
            {
                if (intent.getState().getRevision() == 1L)
                {
                    entered.countDown();
                    try
                    {
                        if (!release.await(5, TimeUnit.SECONDS))
                        {
                            return false;
                        }
                    }
                    catch (InterruptedException ex)
                    {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return super.save(intent);
            }
        };
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        try
        {
            SavedState first = named("First", 1L);
            writer.submit(WriteIntent.unbound(
                repository.getScopeGeneration(),
                repository.getLastKnownDiskRevision(),
                first));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            SavedState latest = named("Latest", 2L);
            writer.submit(WriteIntent.unbound(
                repository.getScopeGeneration(),
                repository.getLastKnownDiskRevision(),
                latest));
            release.countDown();
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals("Latest", repository.load().getActiveSession().getName());
        }
        finally
        {
            release.countDown();
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void intermediateSnapshotsCoalesceToNewest() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), directory);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        try
        {
            long generation = repository.getScopeGeneration();
            writer.submit(WriteIntent.unbound(generation, 0L, named("A", 1L)));
            writer.submit(WriteIntent.unbound(generation, 0L, named("B", 2L)));
            writer.submit(WriteIntent.unbound(generation, 0L, named("C", 3L)));
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals("C", repository.load().getActiveSession().getName());
            assertEquals(3L, repository.load().getRevision());
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void externalConflictIsNotSilentlyRebased() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository shared = new SessionRepository(new Gson(), directory);
        assertTrue(shared.save(named("Seed", 1L)));

        SessionRepository external = new SessionRepository(new Gson(), directory);
        external.load();
        assertTrue(external.save(WriteIntent.unbound(
            external.getScopeGeneration(),
            1L,
            named("External", 2L))));

        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(shared);
        try
        {
            writer.resetAppliedRevision(1L);
            // Stale base against external tip must conflict, not overwrite.
            writer.submit(WriteIntent.unbound(
                shared.getScopeGeneration(),
                1L,
                named("Local stale", 2L)));
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertTrue(writer.getStatus().isFailure()
                || writer.getStatus().getState() == SaveStatus.State.CONFLICT);
            assertEquals("External", new SessionRepository(new Gson(), directory).load()
                .getActiveSession().getName());
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void refusedIdentitySwitchPreservesPreviousState() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), root, null, true);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository)
        {
            @Override
            public boolean flush(Duration timeout)
            {
                return false;
            }
        };
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            new GpManagerConfig() {});
        PersistenceCoordinator coordinator = new PersistenceCoordinator(
            null, null, repository, writer, engine, new DebugTrace());

        TrackingIdentity alice = TrackingIdentity.ofRsProfileKey("rsprofile.alice");
        TrackingIdentity bob = TrackingIdentity.ofRsProfileKey("rsprofile.bob");
        assertTrue(coordinator.trySwitchIdentity(alice, true));
        engine.ensureSession(1_000L);
        engine.getActiveSession().rename("Alice live");
        assertEquals("Alice live", engine.getActiveSession().getName());

        assertFalse(coordinator.trySwitchIdentity(bob, true));
        assertEquals(alice, coordinator.getActiveIdentity());
        assertEquals("Alice live", engine.getActiveSession().getName());
        assertNotNull(coordinator.identityBlockReason());
    }

    @Test
    public void successfulIdentitySwitchPrimesFreshBaseline() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), root, null, true);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            new GpManagerConfig() {});
        PersistenceCoordinator coordinator = new PersistenceCoordinator(
            null, null, repository, writer, engine, new DebugTrace());
        try
        {
            TrackingIdentity alice = TrackingIdentity.ofRsProfileKey("rsprofile.alice");
            TrackingIdentity bob = TrackingIdentity.ofRsProfileKey("rsprofile.bob");
            assertTrue(coordinator.trySwitchIdentity(alice, true));
            engine.ensureSession(1_000L);
            assertTrue(coordinator.trySwitchIdentity(bob, true));
            assertEquals(bob, coordinator.getActiveIdentity());
            assertTrue(engine.isBaselinePriming());
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void identityIsolationWorksWithoutPersistenceEnabled() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), root, null, true);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            new GpManagerConfig() {});
        PersistenceCoordinator coordinator = new PersistenceCoordinator(
            null, null, repository, writer, engine, new DebugTrace());
        try
        {
            TrackingIdentity alice = TrackingIdentity.ofRsProfileKey("rsprofile.alice");
            TrackingIdentity bob = TrackingIdentity.ofRsProfileKey("rsprofile.bob");
            assertTrue(coordinator.trySwitchIdentity(alice, false));
            engine.ensureSession(1_000L);
            engine.getActiveSession().rename("Alice ephemeral");
            assertTrue(coordinator.trySwitchIdentity(bob, false));
            assertEquals(bob, coordinator.getActiveIdentity());
            // New account gets a fresh empty restore, not Alice's live session.
            assertTrue(engine.getActiveSession() == null
                || !"Alice ephemeral".equals(engine.getActiveSession().getName()));
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }
}
