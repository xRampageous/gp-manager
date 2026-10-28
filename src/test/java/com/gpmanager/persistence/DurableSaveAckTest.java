package com.gpmanager;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 1.7.0 durability: a save is acknowledged only once its snapshot (or a newer one of the same
 * scope generation) committed. Up to three attempts per accepted snapshot, 50 and 200 ms apart on
 * the writer's own worker; an exhausted snapshot stays queued for a newer snapshot, a retry or a
 * restart, and lifecycle steps that would replace the owner wait for that acknowledgement.
 * Retry waits go through the writer's retry seam, so no test sleeps through them.
 */
public class DurableSaveAckTest
{
    private static final TrackingIdentity ALICE = new TrackingIdentity("rsprofile.alice", -1L);
    private static final TrackingIdentity BOB = new TrackingIdentity("rsprofile.bob", -1L);

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** Fails the next {@code failures} ordinary saves without touching disk; counts every attempt. */
    private static class FlakyRepository extends SessionRepository
    {
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();

        FlakyRepository(Path root)
        {
            super(new Gson(), FilepathTestSupport.root(root), true);
        }

        @Override
        synchronized boolean save(WriteIntent intent)
        {
            calls.incrementAndGet();
            if (failures.get() > 0)
            {
                failures.decrementAndGet();
                return false;
            }
            return super.save(intent);
        }
    }

    private static final class Rig
    {
        final Path root;
        final FlakyRepository repository;
        final List<Long> pauses = Collections.synchronizedList(new ArrayList<>());
        final OrderedPersistenceWriter writer;
        final Engine engine = new Engine(d -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
        volatile TrackingIdentity who = ALICE;
        final Persistence coordinator;

        Rig(Path root)
        {
            this(root, null, false);
        }

        Rig(Path root, boolean production)
        {
            this(root, null, production);
        }

        Rig(Path root, java.util.concurrent.ExecutorService executor)
        {
            this(root, executor, false);
        }

        Rig(Path root, java.util.concurrent.ExecutorService executor, boolean production)
        {
            this.root = root;
            repository = new FlakyRepository(root);
            writer = production ? new OrderedPersistenceWriter(repository)
                : executor == null ? new PausingWriter(repository, pauses)
                : new PausingWriter(repository, executor, pauses);
            coordinator = new Persistence(null, null, repository, writer, engine)
            {
                @Override
                TrackingIdentity resolveCurrentIdentity()
                {
                    return who;
                }
            };
            JsonCodec.bind(new Gson());
        }

        void seed(String name)
        {
            assertTrue(coordinator.switchIdentity(ALICE, true));
            engine.ensureSession(1_000L);
            engine.session().rename(name);
            assertTrue(coordinator.saveNow());
        }

        String disk(TrackingIdentity owner)
        {
            return DurableSaveAckTest.disk(root, owner);
        }
    }

    static String disk(Path root, TrackingIdentity owner)
    {
        SessionRepository reader = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
        reader.bindIdentity(owner);
        SavedState state = reader.load();
        return state.currentSession() == null ? null : state.currentSession().name();
    }

    /** Records each retry delay and lets the drain reschedule immediately. */
    private static final class PausingWriter extends OrderedPersistenceWriter
    {
        final List<Long> pauses;

        PausingWriter(SessionRepository repository, List<Long> pauses)
        {
            super(repository);
            this.pauses = pauses;
        }

        PausingWriter(SessionRepository repository, java.util.concurrent.ExecutorService executor, List<Long> pauses)
        {
            super(repository, executor);
            this.pauses = pauses;
        }

        @Override // RETRY-SEAM
        boolean retryLater(long millis) // RETRY-SEAM
        { // RETRY-SEAM
            pauses.add(millis); // RETRY-SEAM
            drain(); // RETRY-SEAM
            return true; // RETRY-SEAM
        } // RETRY-SEAM
    }

    /** Runs queued writer tasks only when the test says so. */
    private static final class ManualExecutor extends AbstractExecutorService
    {
        final List<Runnable> queued = new ArrayList<>();

        @Override public synchronized void execute(Runnable command) { queued.add(command); }

        synchronized void runAll()
        {
            while (!queued.isEmpty()) queued.remove(0).run();
        }

        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return new ArrayList<>(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    @Test
    public void aFailedSaveIsNeverAcknowledgedWhileTheWarningStaysQuiet() throws Exception
    {
        Rig rig = new Rig(temporary.newFolder().toPath());
        try
        {
            rig.seed("Seed");
            rig.engine.session().rename("Unsaved");
            rig.repository.failures.set(Integer.MAX_VALUE);
            int before = rig.repository.calls.get();

            assertFalse("a failed write is not durable", rig.coordinator.saveNow());
            assertNotEquals("one failed request stays quiet", SaveStatus.State.FAILED, rig.writer.status().state);
            assertEquals("disk keeps the previous save", "Seed", rig.disk(ALICE));
            assertEquals("three attempts for the accepted snapshot", 3, rig.repository.calls.get() - before);
            assertEquals(java.util.Arrays.asList(50L, 200L), rig.pauses);
        }
        finally
        {
            rig.writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void aTransientFailureCommitsWithinTheBoundedAttemptsAndStaysQuiet() throws Exception
    {
        Rig rig = new Rig(temporary.newFolder().toPath());
        try
        {
            rig.seed("Seed");
            rig.engine.session().addTransaction(new Transaction(2_000L, TransactionType.GAIN, Context.GENERIC,
                "Coins", "", true, Collections.singletonList(new Flow(995, "Coins", 10L, 1, 10L)),
                Confidence.CONFIRMED, "fixture", null), 2_000, false);
            rig.engine.session().rename("Recovered");
            rig.repository.failures.set(2);

            assertTrue(rig.coordinator.saveNow());
            assertEquals("Recovered", rig.disk(ALICE));
            assertEquals(SaveStatus.State.OK, rig.writer.status().state);
            assertEquals(0, rig.writer.failed);
            SessionRepository reader = new SessionRepository(new Gson(), FilepathTestSupport.root(rig.root), true);
            reader.bindIdentity(ALICE);
            assertEquals("the receipt is saved once", 1, reader.load().currentSession().transactions().size());
        }
        finally
        {
            rig.writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void anExhaustedSnapshotStaysRecoverableWithoutSpinning() throws Exception
    {
        Rig rig = new Rig(temporary.newFolder().toPath());
        try
        {
            rig.seed("Seed");
            rig.engine.session().rename("Kept");
            rig.repository.failures.set(Integer.MAX_VALUE);
            int before = rig.repository.calls.get();
            assertFalse(rig.coordinator.saveNow());
            assertFalse("a second flush does not invent an acknowledgement", rig.writer.flush(Duration.ofSeconds(1)));
            assertEquals("no background spin after the bounded attempts", 3, rig.repository.calls.get() - before);

            rig.repository.failures.set(0);
            rig.writer.start();
            assertTrue("a restart retries the retained snapshot", rig.writer.flush(Duration.ofSeconds(5)));
            assertEquals("Kept", rig.disk(ALICE));
        }
        finally
        {
            rig.writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void productionSchedulerCommitsDelayedRetriesOnTheWriterWorker() throws Exception
    {
        Rig rig = new Rig(temporary.newFolder().toPath(), true);
        try
        {
            rig.seed("Seed");
            rig.engine.session().rename("Scheduled");
            int before = rig.repository.calls.get();
            rig.repository.failures.set(2);

            assertTrue(rig.coordinator.saveNow());
            assertEquals("two delayed retries then the commit", 3, rig.repository.calls.get() - before);
            assertEquals("Scheduled", rig.disk(ALICE));
            assertEquals(SaveStatus.State.OK, rig.writer.status().state);
        }
        finally
        {
            rig.writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void productionShutdownFinalizesScheduledRetriesOnItsOwnWorker() throws Exception
    {
        Rig rig = new Rig(temporary.newFolder().toPath(), true);
        try
        {
            rig.seed("Seed");
            rig.engine.session().rename("Final");
            int before = rig.repository.calls.get();
            rig.repository.failures.set(2);

            rig.coordinator.shutdown();
            assertTrue("the writer worker owns the retry window",
                rig.writer.executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("the final snapshot still uses both retries", 3, rig.repository.calls.get() - before);
            assertEquals("Final", rig.disk(ALICE));
            assertTrue(rig.writer.flush(Duration.ofSeconds(1)));
        }
        finally
        {
            rig.writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void aNewerSnapshotDuringAFailedWriteCoversTheOlderRequest() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean gate = new AtomicBoolean();
        Engine engine = new Engine(d -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        JsonCodec.bind(new Gson());
        FlakyRepository gated = new FlakyRepository(root)
        {
            @Override
            synchronized boolean save(WriteIntent intent)
            {
                if (gate.compareAndSet(true, false))
                {
                    calls.incrementAndGet();
                    entered.countDown();
                    try
                    {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    }
                    catch (InterruptedException ex)
                    {
                        throw new AssertionError(ex);
                    }
                    return false;
                }
                return super.save(intent);
            }
        };
        List<Long> pauses = Collections.synchronizedList(new ArrayList<>());
        OrderedPersistenceWriter writer = new PausingWriter(gated, pauses);
        Persistence coordinator = new Persistence(null, null, gated, writer, engine)
        {
            @Override
            TrackingIdentity resolveCurrentIdentity()
            {
                return ALICE;
            }
        };
        try
        {
            assertTrue(coordinator.switchIdentity(ALICE, true));
            engine.ensureSession(1_000L);
            engine.session().rename("Seed");
            assertTrue(coordinator.saveNow());

            engine.session().rename("Older");
            gate.set(true);
            coordinator.scheduleSave();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            boolean[] olderAcknowledged = {false};
            Thread waiter = new Thread(() -> olderAcknowledged[0] = writer.flush(Duration.ofSeconds(5)));
            waiter.start();
            engine.session().rename("Newer");
            coordinator.scheduleSave();
            release.countDown();
            waiter.join(10_000L);
            assertFalse(waiter.isAlive());

            assertTrue("the newer same-owner snapshot covers the older watermark", olderAcknowledged[0]);
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals("only the latest state is committed", "Newer", disk(root, ALICE));
            assertEquals("a superseded failure is not retried", Collections.emptyList(), pauses);
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void anAccountSwitchWaitsUntilThePreviousOwnerIsDurable() throws Exception
    {
        Rig rig = new Rig(temporary.newFolder().toPath());
        try
        {
            rig.seed("Seed");
            rig.engine.session().rename("Alice latest");
            rig.repository.failures.set(Integer.MAX_VALUE);
            rig.who = BOB;

            assertFalse("the failed final save refuses the switch", rig.coordinator.switchIdentity(BOB, true));
            assertSame(ALICE, rig.coordinator.activeIdentity);
            assertEquals("Alice's unsaved state is kept", "Alice latest", rig.engine.session().name());
            assertEquals("Seed", rig.disk(ALICE));
            assertEquals("nothing reached Bob's folder", null, rig.disk(BOB));
            assertFalse("the new physical account cannot feed Alice", rig.coordinator.isTrackingReady());

            rig.repository.failures.set(0);
            assertTrue(rig.coordinator.switchIdentity(BOB, true));
            assertEquals("Alice latest", rig.disk(ALICE));
            assertEquals(null, rig.disk(BOB));

            rig.who = ALICE;
            assertTrue("A -> B -> A reloads Alice's own latest save", rig.coordinator.switchIdentity(ALICE, true));
            assertEquals("Alice latest", rig.engine.session().name());
        }
        finally
        {
            rig.writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void anInvalidatedAccountHashKeepsAnUnsavedOwner() throws Exception
    {
        Rig rig = new Rig(temporary.newFolder().toPath());
        try
        {
            rig.seed("Seed");
            rig.engine.session().rename("Alice latest");
            rig.repository.failures.set(Integer.MAX_VALUE);
            rig.who = null;

            rig.coordinator.onHashInvalidated();
            assertSame("the owner is not cleared over an unsaved snapshot", ALICE, rig.coordinator.activeIdentity);
            assertEquals("Alice latest", rig.engine.session().name());
            assertFalse(rig.coordinator.isTrackingReady());

            rig.repository.failures.set(0);
            rig.coordinator.onHashInvalidated();
            assertEquals("Alice latest", rig.disk(ALICE));
            assertEquals(null, rig.coordinator.activeIdentity);
        }
        finally
        {
            rig.writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void aResetWaitsForADurablePreSave() throws Exception
    {
        Rig rig = new Rig(temporary.newFolder().toPath());
        try
        {
            rig.seed("Seed");
            rig.engine.session().rename("Before reset");
            rig.repository.failures.set(Integer.MAX_VALUE);

            assertFalse(rig.coordinator.factoryReset(5_000L, false).isApplied());
            assertEquals("the profile is untouched", "Before reset", rig.engine.session().name());
            assertEquals("Seed", rig.disk(ALICE));
        }
        finally
        {
            rig.writer.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    public void aTransientFinalShutdownFailureRecoversOnItsOwnWorker() throws Exception
    {
        Rig rig = new Rig(temporary.newFolder().toPath());
        rig.seed("Seed");
        rig.engine.session().rename("Final");
        rig.repository.failures.set(1);

        rig.coordinator.shutdown();
        assertTrue(rig.writer.executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals("no later autosave was needed", "Final", rig.disk(ALICE));
        assertTrue(rig.writer.flush(Duration.ofSeconds(1)));
    }

    @Test
    public void aFailedFinalShutdownWriteIsNeverAcknowledgedAndReEnableRetriesIt() throws Exception
    {
        Rig rig = new Rig(temporary.newFolder().toPath());
        rig.seed("Seed");
        rig.engine.session().rename("Final");
        rig.repository.failures.set(Integer.MAX_VALUE);
        int before = rig.repository.calls.get();

        rig.coordinator.shutdown();
        assertTrue(rig.writer.executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals("Seed", rig.disk(ALICE));
        assertEquals(3, rig.repository.calls.get() - before);
        assertFalse("never acknowledged as saved", rig.writer.flush(Duration.ofSeconds(1)));

        rig.repository.failures.set(0);
        rig.writer.start();
        assertTrue(rig.writer.flush(Duration.ofSeconds(5)));
        assertEquals("re-enable retries the kept snapshot", "Final", rig.disk(ALICE));
        rig.writer.shutdown(Duration.ofSeconds(5));
    }

    @Test
    public void noRetryStartsAfterTheShutdownWindow() throws Exception
    {
        ManualExecutor executor = new ManualExecutor();
        Rig rig = new Rig(temporary.newFolder().toPath(), executor);
        assertTrue(rig.coordinator.switchIdentity(ALICE, true));
        rig.engine.ensureSession(1_000L);
        rig.repository.failures.set(Integer.MAX_VALUE);
        rig.coordinator.scheduleSave();

        rig.writer.shutdown(Duration.ZERO);
        executor.runAll();
        assertEquals("the expired window starts no retry", 1, rig.repository.calls.get());
        assertEquals(Collections.emptyList(), rig.pauses);
        assertFalse(rig.writer.flush(Duration.ZERO));
    }

    @Test
    public void expiryDuringBackoffDoesNotStartAnotherSave() throws Exception
    {
        checkBackoffShutdown(true);
    }

    @Test
    public void shutdownArrivingDuringBackoffDoesNotStartAnotherSave() throws Exception
    {
        checkBackoffShutdown(false);
    }

    private void checkBackoffShutdown(boolean alreadyClosing) throws Exception
    {
        ManualExecutor executor = new ManualExecutor();
        FlakyRepository repository = new FlakyRepository(temporary.newFolder().toPath());
        repository.bindIdentity(ALICE);
        repository.load();
        repository.failures.set(Integer.MAX_VALUE);
        OrderedPersistenceWriter writer = new OrderedPersistenceWriter(repository, executor)
        {
            @Override boolean retryLater(long millis)
            {
                if (alreadyClosing) until = System.nanoTime() - 1;
                else shutdown(Duration.ZERO);
                return false;
            }
        };
        SavedState state = new SavedState();
        state.setRevision(1);
        writer.submit(new WriteIntent(ALICE, repository.generation, repository.revision, state, null));
        if (alreadyClosing) writer.shutdown(Duration.ofSeconds(5));
        executor.runAll();
        assertEquals("backoff ended outside the window: no new attempt", 1, repository.calls.get());
        assertNotEquals(null, writer.merged.get());
        assertFalse("the retained failed snapshot is not acknowledged", writer.flush(Duration.ZERO));
        repository.failures.set(0);
        writer.start();
        executor.runAll();
        assertEquals("re-enable writes the retained snapshot once", 2, repository.calls.get());
        assertTrue(writer.flush(Duration.ZERO));
        assertEquals(1L, repository.revision);
    }

    @Test
    public void aFaultAfterThePrimaryCommitIsAcknowledgedOnce() throws Exception
    {
        Path root = temporary.newFolder().toPath();
        AtomicBoolean fault = new AtomicBoolean();
        AtomicInteger saves = new AtomicInteger();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true)
        {
            @Override
            synchronized boolean save(WriteIntent intent)
            {
                saves.incrementAndGet();
                return super.save(intent);
            }

            @Override
            void replaceFile(Filepath source, Filepath target) throws IOException
            {
                super.replaceFile(source, target);
                if (target.getFileName().toString().equals("sessions.json") && fault.compareAndSet(true, false))
                {
                    throw new IOException("fault after the primary was replaced");
                }
            }
        };
        List<Long> pauses = Collections.synchronizedList(new ArrayList<>());
        OrderedPersistenceWriter writer = new PausingWriter(repository, pauses);
        Engine engine = new Engine(d -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        Persistence coordinator = new Persistence(null, null, repository, writer, engine)
        {
            @Override
            TrackingIdentity resolveCurrentIdentity()
            {
                return ALICE;
            }
        };
        JsonCodec.bind(new Gson());
        try
        {
            assertTrue(coordinator.switchIdentity(ALICE, true));
            engine.ensureSession(1_000L);
            engine.session().rename("Committed");
            fault.set(true);
            assertTrue("the primary holds this revision: acknowledged", coordinator.saveNow());
            assertEquals("never written twice", 1, saves.get());
            assertTrue(Files.exists(root.resolve("profiles").resolve(ALICE.folderName()).resolve("sessions.json")));
            SessionRepository reader = new SessionRepository(new Gson(), FilepathTestSupport.root(root), true);
            reader.bindIdentity(ALICE);
            assertEquals("Committed", reader.load().currentSession().name());
            assertEquals(Collections.emptyList(), pauses);
        }
        finally
        {
            writer.shutdown(Duration.ofSeconds(5));
        }
    }
}
