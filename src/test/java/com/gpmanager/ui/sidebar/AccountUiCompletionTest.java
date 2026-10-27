package com.gpmanager;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.*;

/** Worker results belong to the account and lifecycle on which the work completed. */
public class AccountUiCompletionTest
{
    @Test
    public void unchangedOwnerReceivesSuccessOnSwingThread() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            AtomicInteger delivered = new AtomicInteger();
            fixture.completeBeforeDelivery(() -> 7, result ->
            {
                assertTrue(SwingUtilities.isEventDispatchThread());
                assertEquals(7, result.intValue());
                delivered.incrementAndGet();
            }, () -> { });
            assertEquals(1, delivered.get());
        }
    }

    @Test
    public void unchangedOwnerReceivesFailure() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.completeBeforeDelivery(() -> { throw new IOException("disk refused"); }, ignored -> fail(), () -> { });
            fixture.assertNotice("Work failed · disk refused");
        }
    }

    @Test
    public void accountChangeBeforeWorkerStartsPreventsWork() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.beforeWork(fixture::changeAccount);
        }
    }

    @Test
    public void sameAccountWithNewLifecycleBeforeWorkerStartsPreventsWork() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.beforeWork(() -> fixture.disk.request.incrementAndGet());
        }
    }

    @Test
    public void lateResetDoesNotClearTheNextAccountsHud() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.completeBeforeDelivery(() -> new Persistence.ResetOutcome(true, "reset"),
                fixture.controller::reset, fixture::changeAccount);
            assertEquals(0, fixture.panel.resets.get());
            fixture.assertNotice("Current account · ready");
        }
    }

    @Test
    public void lateFailureDoesNotReplaceTheNextAccountsNotice() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.completeBeforeDelivery(() -> { throw new IOException("old failure"); }, ignored -> fail(),
                fixture::changeAccount);
            fixture.assertNotice("Current account · ready");
        }
    }

    @Test
    public void returningToTheSameAccountStillRejectsItsOldResult() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            AtomicInteger delivered = new AtomicInteger();
            fixture.completeBeforeDelivery(() -> 1, ignored -> delivered.incrementAndGet(), () ->
            {
                fixture.changeAccount();
                fixture.disk.activeIdentity = fixture.original;
                fixture.disk.request.incrementAndGet();
            });
            assertEquals(0, delivered.get());
        }
    }

    @Test
    public void resetThatAdvancesItsOwnGenerationStillUpdatesItsHud() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.completeBeforeDelivery(() ->
            {
                fixture.disk.fence();
                return new Persistence.ResetOutcome(true, "reset");
            }, fixture.controller::reset, () -> { });
            assertEquals(1, fixture.panel.resets.get());
            assertTrue(fixture.disk.request.get() > 0L);
        }
    }

    @Test
    public void resetFailureAfterItsOwnFenceStillReportsTheFailure() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.completeBeforeDelivery(() ->
            {
                fixture.disk.fence();
                throw new IOException("reset refused");
            }, ignored -> fail(), () -> { });
            fixture.assertNotice("Work failed · reset refused");
        }
    }

    @Test
    public void shutdownBeforeSwingDeliveryDiscardsTheResult() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            AtomicInteger delivered = new AtomicInteger();
            fixture.completeBeforeDelivery(() -> 1, ignored -> delivered.incrementAndGet(),
                () -> fixture.disk.closing = true);
            assertEquals(0, delivered.get());
        }
    }

    static final class RecordingPanel extends SidebarPanel
    {
        final AtomicInteger resets = new AtomicInteger();

        RecordingPanel(Engine engine, Persistence disk)
        {
            super(engine, PresentationLifecycleTest.config(), null, null, disk);
        }

        @Override void resetProfile()
        {
            resets.incrementAndGet();
        }
    }

    static final class Fixture implements AutoCloseable
    {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final TrackingIdentity original = new TrackingIdentity("first", 1L);
        final Persistence disk;
        RecordingPanel panel;
        GrindsController controller;

        Fixture() throws Exception
        {
            Engine engine = PresentationLifecycleTest.engine();
            disk = new Persistence(null, null, null, null, engine);
            disk.activeIdentity = original;
            disk.worker = worker;
            SwingUtilities.invokeAndWait(() ->
            {
                panel = new RecordingPanel(engine, disk);
                controller = new GrindsController(panel);
            });
        }

        void changeAccount()
        {
            disk.request.incrementAndGet();
            disk.activeIdentity = new TrackingIdentity("second", 2L);
            panel.shell.tell("Current account", "ready", false);
        }

        <T> void completeBeforeDelivery(Callable<T> work, Consumer<T> done, Runnable beforeDelivery) throws Exception
        {
            // Deliberately hold the test EDT until the worker has queued its presentation.
            SwingUtilities.invokeAndWait(() ->
            {
                controller.off(work, done, "Work failed");
                drainWorker();
                beforeDelivery.run();
            });
            SwingUtilities.invokeAndWait(() -> { });
            assertFalse("the worker always releases busy", disk.busy);
        }

        void beforeWork(Runnable change) throws Exception
        {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            worker.execute(() ->
            {
                entered.countDown();
                await(release);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try
            {
                SwingUtilities.invokeAndWait(() ->
                {
                    controller.off(() -> calls.incrementAndGet(), ignored -> fail(), "Work failed");
                    change.run();
                });
            }
            finally
            {
                release.countDown();
            }
            drainWorker();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals("stale queued work never begins", 0, calls.get());
            assertFalse(disk.busy);
        }

        void assertNotice(String expected) throws Exception
        {
            SwingUtilities.invokeAndWait(() -> assertEquals(expected, panel.shell.message.getToolTipText()));
        }

        void drainWorker()
        {
            CountDownLatch idle = new CountDownLatch(1);
            worker.execute(idle::countDown);
            await(idle);
        }

        static void await(CountDownLatch latch)
        {
            try
            {
                assertTrue("worker barrier completed", latch.await(5, TimeUnit.SECONDS));
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }

        @Override public void close() throws Exception
        {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() ->
            {
                panel.shell.timer.stop();
                panel.clockPulse.stop();
            });
        }
    }
}
