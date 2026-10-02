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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WriterLineageAndIdentityTest
{
    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private SavedState named(String name, long revision)
    {
        SavedState state = new SavedState(new Ad(name, 1_000L), null, false, Collections.emptyList());
        state.setRevision(revision);
        return state;
    }

    @Test
    public void laterSnapshotDuringOwnWriteMustPersist() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory))
        {
            @Override
            public boolean save(Cs intent)
            {
                if (intent.state.revision == 1L)
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
            writer.submit(new Cs(null,
                repository.scopeGeneration,
                repository.lastKnownDiskRevision,
                first));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            SavedState latest = named("Latest", 2L);
            writer.submit(new Cs(null,
                repository.scopeGeneration,
                repository.lastKnownDiskRevision,
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
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        try
        {
            long generation = repository.scopeGeneration;
            writer.submit(new Cs(null, generation, 0L, named("A", 1L)));
            writer.submit(new Cs(null, generation, 0L, named("B", 2L)));
            writer.submit(new Cs(null, generation, 0L, named("C", 3L)));
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals("C", repository.load().getActiveSession().getName());
            assertEquals(3L, repository.load().revision);
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
        SessionRepository shared = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        assertTrue(PersistenceProbe.save(shared, named("Seed", 1L)));

        SessionRepository external = new SessionRepository(new Gson(), FilepathTestSupport.root(directory));
        external.load();
        assertTrue(external.save(new Cs(null,
            external.scopeGeneration,
            1L,
            named("External", 2L))));

        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(shared);
        try
        {
            writer.aft(1L);
            // Stale base against external tip must conflict, not overwrite.
            writer.submit(new Cs(null,
                shared.scopeGeneration,
                1L,
                named("Local stale", 2L)));
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertTrue(writer.getStatus().auq()
                || writer.getStatus().state == Ci.State.CONFLICT);
            assertEquals("External", new SessionRepository(new Gson(), FilepathTestSupport.root(directory)).load()
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
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository)
        {
            @Override
            public boolean flush(Duration timeout)
            {
                return false;
            }
        };
        Am engine = new Am(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            new GpManagerConfig() {});
        Ei coordinator = new Ei(
            null, null, repository, writer, engine);

        TrackingIdentity alice = new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID);
        TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
        assertTrue(coordinator.ajy(alice, true));
        engine.rm(1_000L);
        engine.getActiveSession().rename("Alice live");
        assertEquals("Alice live", engine.getActiveSession().getName());

        assertFalse(coordinator.ajy(bob, true));
        assertEquals(alice, coordinator.getActiveIdentity());
        assertEquals("Alice live", engine.getActiveSession().getName());
        assertNotNull(PersistenceProbe.identityBlockReason(coordinator));
    }

    /**
     * A save serializes the engine's state while holding the engine's lock, so no live record is
     * read after it is released; the writer then commits that JSON without serializing again.
     */
    @Test
    public void saveSnapshotIsCopiedUnderTheEngineLock() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        Am engine = new Am(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            new GpManagerConfig() {});
        java.util.List<Boolean> locked = new java.util.ArrayList<>();
        Gson recording = new com.google.gson.GsonBuilder().registerTypeAdapterFactory(new com.google.gson.TypeAdapterFactory()
        {
            @Override
            @SuppressWarnings("unchecked")
            public <T> com.google.gson.TypeAdapter<T> create(Gson gson, com.google.gson.reflect.TypeToken<T> type)
            {
                if (type.getRawType() != SavedState.class)
                {
                    return null;
                }
                com.google.gson.TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
                return new com.google.gson.TypeAdapter<T>()
                {
                    @Override
                    public void write(com.google.gson.stream.JsonWriter out, T value) throws java.io.IOException
                    {
                        locked.add(Thread.holdsLock(engine));
                        delegate.write(out, value);
                    }

                    @Override
                    public T read(com.google.gson.stream.JsonReader in) throws java.io.IOException
                    {
                        return delegate.read(in);
                    }
                };
            }
        }).create();
        SessionRepository repository = new SessionRepository(recording, FilepathTestSupport.root(root), true);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Ei coordinator = new Ei(
            null, null, repository, writer, engine);
        try
        {
            assertTrue(coordinator.ajy(new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID), true));
            engine.rm(1_000L);
            locked.clear();
            assertTrue(coordinator.aya());
            assertEquals("one serialization per save, under the engine lock",
                Collections.singletonList(true), locked);
            String disk = SessionRepository.awy(repository.bound.state);
            SavedState reread = new Gson().fromJson(disk, SavedState.class);
            reread.aar();
            assertEquals("the file is exactly what the old detached copy wrote", new Gson().toJson(reread), disk);
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void successfulIdentitySwitchPrimesFreshBaseline() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Am engine = new Am(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            new GpManagerConfig() {});
        Ei coordinator = new Ei(
            null, null, repository, writer, engine);
        try
        {
            TrackingIdentity alice = new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID);
            TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
            assertTrue(coordinator.ajy(alice, true));
            engine.rm(1_000L);
            assertTrue(coordinator.ajy(bob, true));
            assertEquals(bob, coordinator.getActiveIdentity());
            assertTrue(EngineProbe.isBaselinePriming(engine));
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
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Am engine = new Am(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            new GpManagerConfig() {});
        Ei coordinator = new Ei(
            null, null, repository, writer, engine);
        try
        {
            TrackingIdentity alice = new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID);
            TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
            assertTrue(coordinator.ajy(alice, false));
            engine.rm(1_000L);
            engine.getActiveSession().rename("Alice ephemeral");
            assertTrue(coordinator.ajy(bob, false));
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
