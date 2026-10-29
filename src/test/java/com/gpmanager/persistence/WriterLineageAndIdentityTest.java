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
        SavedState state = new SavedState(new Session(name, 1_000L, SessionMode.GENERAL), null, Collections.emptyList());
        state.setRevision(revision);
        return state;
    }

    @Test
    public void laterSnapshotDuringOwnWriteMustPersist() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
        {
            @Override
            public boolean save(WriteIntent intent)
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
            writer.submit(new WriteIntent(null,
                repository.generation,
                repository.revision,
                first, null));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            SavedState latest = named("Latest", 2L);
            writer.submit(new WriteIntent(null,
                repository.generation,
                repository.revision,
                latest, null));
            release.countDown();
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals("Latest", FixtureReads.currentSession(repository.load()).name());
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
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        try
        {
            long generation = repository.generation;
            writer.submit(new WriteIntent(null, generation, 0L, named("A", 1L), null));
            writer.submit(new WriteIntent(null, generation, 0L, named("B", 2L), null));
            writer.submit(new WriteIntent(null, generation, 0L, named("C", 3L), null));
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals("C", FixtureReads.currentSession(repository.load()).name());
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
        SessionRepository shared = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        assertTrue(PersistenceProbe.save(shared, named("Seed", 1L)));

        SessionRepository external = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false);
        external.load();
        assertTrue(external.save(new WriteIntent(null,
            external.generation,
            1L,
            named("External", 2L), null)));

        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(shared);
        try
        {
            writer.resetRevision(1L);
            // Stale base against external tip must conflict, not overwrite.
            writer.submit(new WriteIntent(null,
                shared.generation,
                1L,
                named("Local stale", 2L), null));
            assertFalse("1.7.0: a refused write is never acknowledged", writer.flush(Duration.ofSeconds(5)));
            assertTrue(writer.status().isFailure()
                || writer.status().state == SaveStatus.State.CONFLICT);
            assertEquals("External", FixtureReads.currentSession(new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false).load()).name());
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
        Engine engine = new Engine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            new GpManagerConfig() {});
        Persistence coordinator = new Persistence(
            null, null, repository, writer, engine);

        TrackingIdentity alice = new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID);
        TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
        assertTrue(coordinator.switchIdentity(alice, true));
        engine.ensureSession(1_000L);
        engine.session().rename("Alice live");
        assertEquals("Alice live", engine.session().name());

        assertFalse(coordinator.switchIdentity(bob, true));
        assertEquals(alice, coordinator.getActiveIdentity());
        assertEquals("Alice live", engine.session().name());
        assertNotNull(PersistenceProbe.identityBlockReason(coordinator));
    }

    /**
     * A save copies under the engine lock, then serializes once on the writer without that lock.
     */
    @Test
    public void saveSnapshotIsCopiedUnderTheEngineLock() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        java.util.List<Boolean> copiedLocked = new java.util.concurrent.CopyOnWriteArrayList<>();
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {})
        {
            @Override SavedState savedState()
            {
                copiedLocked.add(Thread.holdsLock(this));
                return super.savedState();
            }
        };
        java.util.List<Boolean> locked = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<Thread> encodingThreads = new java.util.concurrent.CopyOnWriteArrayList<>();
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
                        encodingThreads.add(Thread.currentThread());
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
        Persistence coordinator = new Persistence(
            null, null, repository, writer, engine);
        try
        {
            assertTrue(coordinator.switchIdentity(new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID), true));
            engine.ensureSession(1_000L);
            locked.clear();
            copiedLocked.clear();
            encodingThreads.clear();
            Thread caller = Thread.currentThread();
            assertTrue(coordinator.saveNow());
            assertEquals("copy under the engine lock", Collections.singletonList(true), copiedLocked);
            assertEquals("one serialization per save, without the engine lock",
                Collections.singletonList(false), locked);
            assertEquals(1, encodingThreads.size());
            assertFalse("encoding is off the capture thread", caller == encodingThreads.get(0));
            String disk = SessionRepository.readText(repository.bound.state);
            SavedState reread = new Gson().fromJson(disk, SavedState.class);
            assertEquals("the file retains the captured schema and values", new Gson().toJson(reread), disk);
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
        Engine engine = new Engine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            new GpManagerConfig() {});
        Persistence coordinator = new Persistence(
            null, null, repository, writer, engine);
        try
        {
            TrackingIdentity alice = new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID);
            TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
            assertTrue(coordinator.switchIdentity(alice, true));
            engine.ensureSession(1_000L);
            assertTrue(coordinator.switchIdentity(bob, true));
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
        Engine engine = new Engine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            new GpManagerConfig() {});
        Persistence coordinator = new Persistence(
            null, null, repository, writer, engine);
        try
        {
            TrackingIdentity alice = new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID);
            TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
            assertTrue(coordinator.switchIdentity(alice, true));
            engine.ensureSession(1_000L);
            engine.session().rename("Alice ephemeral");
            assertTrue(coordinator.switchIdentity(bob, true));
            assertEquals(bob, coordinator.getActiveIdentity());
            // New account gets a fresh empty restore, not Alice's live session.
            assertTrue(engine.session() == null
                || !"Alice ephemeral".equals(engine.session().name()));
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }
}
