package com.gpmanager;

import com.google.gson.Gson;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static com.gpmanager.SwingTestSupport.onEdt;
import static org.junit.Assert.*;

/** Refusal must explain why the new account is fenced, without changing writer truth. */
public class AccountSwitchNoticeTest {
    private static final TrackingIdentity ALICE = new TrackingIdentity("rsprofile.notice-a", 11);
    private static final TrackingIdentity BOB = new TrackingIdentity("rsprofile.notice-b", 22);
    private static final long NOW = 1_700_000_000_000L;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void refusedSwitchExplainsThePauseAndPreservesTheOldAccount() throws Exception {
        try (Harness h = new Harness(temporary.newFolder().toPath())) {
            h.refuse();
            SaveStatus status = h.disk.saveStatus();
            assertTrue(status.isFailure());
            assertFalse("save Retry cannot repeat an owner switch", status.retry);
            assertTrue(status.detail, status.detail.contains("previous account"));
            assertFalse(h.disk.isTrackingReady());
            assertEquals(ALICE, h.disk.activeIdentity);
            assertEquals(500, h.engine.metrics(NOW).net);
        }
    }

    @Test public void successfulSwitchClearsTheTransientNotice() throws Exception {
        try (Harness h = new Harness(temporary.newFolder().toPath())) {
            h.refuse();
            assertTrue(h.disk.saveStatus().isFailure());
            h.disk.allow = true;
            assertTrue(h.disk.switchIdentity(BOB, true));
            assertTrue(h.disk.isTrackingReady());
            assertFalse(h.disk.saveStatus().isFailure());
            assertEquals(BOB, h.disk.activeIdentity);
            assertEquals(0, h.engine.metrics(NOW).net);
        }
    }

    @Test public void returningToTheOriginalAccountClearsOnlyTheSwitchNotice() throws Exception {
        try (Harness h = new Harness(temporary.newFolder().toPath())) {
            h.refuse();
            assertTrue(h.disk.saveStatus().isFailure());
            assertTrue(h.writer.flush(Duration.ofSeconds(5)));
            h.current.set(ALICE);
            h.disk.fence();
            assertTrue(h.disk.switchIdentity(ALICE, true));
            assertTrue(h.disk.isTrackingReady());
            assertFalse(h.disk.saveStatus().isFailure());
            assertEquals(500, h.engine.metrics(NOW).net);
        }
    }

    @Test public void warningStaysVisibleWhileThePanelIsSuspended() throws Exception {
        try (Harness h = new Harness(temporary.newFolder().toPath())) {
            SidebarPanel panel = onEdt(() -> new SidebarPanel(h.engine, new GpManagerConfig() {}, null, h.repository, h.disk));
            h.refuse();
            onEdt(() -> {
                panel.active = true;
                panel.suspend();
                panel.refreshNow();
                assertTrue(panel.suspended);
                assertNull("old owner's receipts stay hidden", panel.snapshot);
                assertTrue(panel.shell.notice.isVisible());
                assertTrue(ShellProbe.noticeText(panel.shell), ShellProbe.noticeText(panel.shell).contains("previous account"));
                assertEquals(0, panel.shell.noticeAction.getComponentCount());
                assertFalse(panel.shell.timer.isRunning());
                return null;
            });
            assertEquals(500, h.engine.metrics(NOW).net);
        }
    }

    @Test public void theSwitchNoticeNeverReplacesTheWritersConflictGuard() throws Exception {
        try (Harness h = new Harness(temporary.newFolder().toPath())) {
            SaveStatus conflict = new SaveStatus(SaveStatus.State.CONFLICT, "writer-conflict", false, "");
            h.writer.status.set(conflict);
            h.writer.conflict.set(h.repository.generation);
            h.refuse();
            assertTrue(h.disk.saveStatus().detail.contains("previous account"));
            assertSame(conflict, h.writer.status());
            h.current.set(ALICE);
            assertTrue(h.disk.switchIdentity(ALICE, true));
            assertSame(conflict, h.disk.saveStatus());
            assertFalse("returning cannot acknowledge the failed write", h.disk.isTrackingReady());
        }
    }

    @Test public void ownerRequestFencesIngestionBeforeTheWorkerCanRun() throws Exception {
        try (Harness h = new Harness(temporary.newFolder().toPath())) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            h.disk.worker = worker;
            worker.execute(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                h.engine.events.put(Inbox.CONSUME, 385, 1, 10, null);
                h.current.set(BOB);
                h.disk.syncIdentityAsync();
                assertTrue(h.disk.loading);
                assertFalse(h.disk.isTrackingReady());
                assertNull(h.engine.events.peek(Inbox.CONSUME));
                assertEquals(ALICE, h.disk.activeIdentity);
                release.countDown();
                worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertEquals(BOB, h.disk.activeIdentity);
                assertTrue(h.disk.consumeReady());
                assertFalse(h.disk.consumeReady());
            } finally { release.countDown(); worker.shutdown(); }
        }
    }

    private static class Harness implements AutoCloseable {
        final Engine engine = TargetFreshStartTest.engine();
        final AtomicReference<TrackingIdentity> current = new AtomicReference<>(ALICE);
        final SessionRepository repository;
        final OrderedPersistenceWriter writer;
        final SwitchPersistence disk;
        Harness(Path root) {
            Gson gson = new Gson(); JsonCodec.bind(gson);
            repository = new SessionRepository(gson, FilepathTestSupport.root(root), true);
            writer = new OrderedPersistenceWriter(repository);
            disk = new SwitchPersistence();
            assertTrue(disk.switchIdentity(ALICE, true));
            engine.ensureSession(NOW);
            engine.session().rename("Alice");
            TargetFreshStartTest.coins(engine, NOW, 500);
            assertTrue(disk.saveNow());
        }
        void refuse() {
            current.set(BOB); disk.allow = false;
            assertFalse(disk.switchIdentity(BOB, true));
        }
        class SwitchPersistence extends Persistence {
            boolean allow = true;
            SwitchPersistence() { super(null, null, Harness.this.repository, Harness.this.writer, Harness.this.engine); }
            @Override TrackingIdentity resolveCurrentIdentity() { return current.get(); }
            @Override synchronized boolean flush(Duration timeout) { return allow && super.flush(timeout); }
        }
        public void close() {
            if (disk.worker != null) disk.worker.shutdown();
            writer.shutdown(Duration.ofSeconds(5));
        }
    }
}
