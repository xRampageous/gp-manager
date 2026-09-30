package com.gpmanager;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** C0a lifecycle proofs: lifecycle callbacks enqueue and never wait for disk. */
public class PersistenceLifecycleC0aTest
{
    private static final TrackingIdentity ALICE = new TrackingIdentity("rsprofile.alice", TrackingIdentity.ACCOUNT_HASH_INVALID);

    private static final class FailingRepository extends SessionRepository
    {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private volatile long failingGeneration = -1L;

        FailingRepository(Path root, CountDownLatch entered, CountDownLatch release)
        {
            super(new Gson(), FilepathTestSupport.root(root), true);
            this.entered = entered;
            this.release = release;
        }

        void failNextSaveForGeneration(long generation)
        {
            failingGeneration = generation;
        }

        @Override
        public boolean save(Cs intent)
        {
            if (intent.scopeGeneration == failingGeneration)
            {
                failingGeneration = -1L;
                entered.countDown();
                try
                {
                    if (!release.await(10, TimeUnit.SECONDS))
                    {
                        throw new AssertionError("save gate was not released");
                    }
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return false;
            }
            return super.save(intent);
        }
    }

    static
    {
        JsonCodec.bind(new Gson());
    }

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void shutdownReturnsBeforeAnInFlightWriteCompletes() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory))
        {
            @Override
            public boolean save(Cs intent)
            {
                entered.countDown();
                try
                {
                    if (!release.await(10, TimeUnit.SECONDS))
                    {
                        return false;
                    }
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return super.save(intent);
            }
        };
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch shutdownReturned = new CountDownLatch(1);
        try
        {
            writer.submit(new Cs(null,
                repository.scopeGeneration,
                0L,
                state("pending", 1L)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            caller.execute(() ->
            {
                writer.shutdown(Duration.ofSeconds(5));
                shutdownReturned.countDown();
            });
            assertTrue("shutdown must not wait for the writer gate",
                shutdownReturned.await(1, TimeUnit.SECONDS));

            release.countDown();
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals("pending", repository.load().getActiveSession().getName());
        }
        finally
        {
            release.countDown();
            writer.shutdown(Duration.ofSeconds(5));
            caller.shutdownNow();
        }
    }

    @Test
    public void asyncIdentitySwitchReturnsBeforeRestoreCompletes() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), true)
        {
            @Override
            public synchronized SavedState load()
            {
                entered.countDown();
                try
                {
                    if (!release.await(10, TimeUnit.SECONDS))
                    {
                        throw new AssertionError("restore gate was not released");
                    }
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
                return super.load();
            }
        };
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Am engine = new Am(
            deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        Ei coordinator = new Ei(
            null, null, repository, writer, engine);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch requestReturned = new CountDownLatch(1);
        try
        {
            coordinator.start();
            caller.execute(() ->
            {
                coordinator.ajz(ALICE, true);
                requestReturned.countDown();
            });
            assertTrue(requestReturned.await(1, TimeUnit.SECONDS));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            release.countDown();
            assertTrue(String.valueOf(PersistenceProbe.identityBlockReason(coordinator)),
                coordinator.ajy(ALICE, true));
            assertEquals(ALICE, coordinator.getActiveIdentity());
        }
        finally
        {
            release.countDown();
            coordinator.shutdown(false);
            caller.shutdownNow();
        }
    }

    @Test
    public void gameplaySaveRequestDoesNotWaitBehindLifecycleDiskWork() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean gateLoad = new AtomicBoolean(false);
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), true)
        {
            @Override
            public synchronized SavedState load()
            {
                if (gateLoad.get())
                {
                    entered.countDown();
                    try
                    {
                        if (!release.await(10, TimeUnit.SECONDS))
                        {
                            throw new AssertionError("restore gate was not released");
                        }
                    }
                    catch (InterruptedException ex)
                    {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                }
                return super.load();
            }
        };
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Am engine = new Am(
            deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        Ei coordinator = new Ei(
            null, null, repository, writer, engine);
        TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try
        {
            coordinator.start();
            assertTrue(coordinator.ajy(ALICE, true));

            gateLoad.set(true);
            coordinator.ajz(bob, true);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            CountDownLatch requestReturned = new CountDownLatch(1);
            caller.execute(() ->
            {
                coordinator.ahe();
                requestReturned.countDown();
            });
            assertTrue("a gameplay save request must not wait behind lifecycle disk work",
                requestReturned.await(1, TimeUnit.SECONDS));

            release.countDown();
            assertTrue(String.valueOf(PersistenceProbe.identityBlockReason(coordinator)),
                coordinator.ajy(bob, true));
        }
        finally
        {
            release.countDown();
            coordinator.shutdown(false);
            caller.shutdownNow();
        }
    }

    @Test
    public void shutdownDuringIdentitySwitchCapturesThePausedOldOwner() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean gateLoad = new AtomicBoolean(false);
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), true)
        {
            @Override
            public synchronized SavedState load()
            {
                if (gateLoad.get())
                {
                    entered.countDown();
                    try
                    {
                        if (!release.await(10, TimeUnit.SECONDS))
                        {
                            throw new AssertionError("restore gate was not released");
                        }
                    }
                    catch (InterruptedException ex)
                    {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                }
                return super.load();
            }
        };
        CountDownLatch writerShutdown = new CountDownLatch(1);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository)
        {
            @Override
            public void shutdown(Duration timeout)
            {
                super.shutdown(timeout);
                writerShutdown.countDown();
            }
        };
        Am engine = new Am(
            deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        Ei coordinator = new Ei(
            null, null, repository, writer, engine);
        TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch shutdownReturned = new CountDownLatch(1);
        try
        {
            assertTrue(coordinator.ajy(ALICE, true));
            engine.rm(1_000L);
            engine.getGeneralSession().rename("Alice");
            assertTrue(coordinator.aya());

            gateLoad.set(true);
            coordinator.ajz(bob, true);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            engine.acu(2_000L);
            caller.execute(() ->
            {
                coordinator.shutdown(true);
                shutdownReturned.countDown();
            });
            assertTrue("shutdown must not wait for the identity restore",
                shutdownReturned.await(1, TimeUnit.SECONDS));

            release.countDown();
            assertTrue("deferred shutdown must fence the writer after its snapshot",
                writerShutdown.await(10, TimeUnit.SECONDS));
            assertTrue(writer.flush(Duration.ofSeconds(10)));
            assertEquals(ALICE, coordinator.getActiveIdentity());
            assertTrue(repository.isBound());
            assertTrue(repository.load().getActiveSession().paused);
            assertEquals("Alice", repository.load().getActiveSession().getName());
        }
        finally
        {
            release.countDown();
            coordinator.shutdown(false);
            caller.shutdownNow();
        }
    }

    @Test
    public void ownerRoundTripKeepsEachProfileLineageIsolated() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), true);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Am engine = new Am(
            deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        Ei coordinator = new Ei(
            null, null, repository, writer, engine);
        TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
        try
        {
            assertTrue(coordinator.ajy(ALICE, true));
            engine.rm(1_000L);
            engine.getGeneralSession().rename("Alice");
            assertTrue(coordinator.aya());

            assertTrue(coordinator.ajy(bob, true));
            engine.rm(2_000L);
            engine.getGeneralSession().rename("Bob");
            assertTrue(coordinator.aya());

            assertTrue(coordinator.ajy(ALICE, true));
            assertEquals(ALICE, coordinator.getActiveIdentity());
            assertEquals("Alice", engine.getGeneralSession().getName());
            assertTrue(coordinator.ajy(bob, true));
            assertEquals("Bob", engine.getGeneralSession().getName());
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void oldOwnerWriteFailureStaysFencedAfterSwitch() throws Exception
    {
        Path directory = temporary.newFolder().toPath();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FailingRepository repository = new FailingRepository(directory, entered, release);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository);
        Am engine = new Am(
            deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        Ei coordinator = new Ei(
            null, null, repository, writer, engine);
        TrackingIdentity bob = new TrackingIdentity("rsprofile.bob", TrackingIdentity.ACCOUNT_HASH_INVALID);
        try
        {
            assertTrue(coordinator.ajy(ALICE, true));
            engine.rm(1_000L);
            engine.getGeneralSession().rename("Alice");
            assertTrue(coordinator.aya());
            long aliceGeneration = repository.scopeGeneration;
            engine.getGeneralSession().notes = "failing old save";
            repository.failNextSaveForGeneration(aliceGeneration);
            coordinator.ahe();
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            coordinator.acd(false);
            assertTrue(coordinator.ajy(bob, true));
            engine.rm(2_000L);
            engine.getGeneralSession().rename("Bob");
            Ci bobStatus = writer.getStatus();
            Path bobPrimary = FilepathTestSupport.path(repository.ty().joinSegment("sessions.json"));

            release.countDown();
            assertTrue(writer.flush(Duration.ofSeconds(10)));
            assertEquals(bobStatus.state, writer.getStatus().state);
            assertSame("the stale completion leaves Bob's status alone", bobStatus, writer.getStatus());
            assertEquals(bob, coordinator.getActiveIdentity());
            assertFalse("old-owner failure must not create Bob's file", Files.exists(bobPrimary));
            assertEquals("Bob", engine.getGeneralSession().getName());
        }
        finally
        {
            release.countDown();
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    private static SavedState state(String name, long revision)
    {
        SavedState state = new SavedState(
            new Ad(name, 1_000L), null, false, Collections.emptyList());
        state.setRevision(revision);
        return state;
    }
}
