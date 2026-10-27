package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static com.gpmanager.SwingTestSupport.onEdt;
import static org.junit.Assert.*;

/** Client-side owner fencing must not wait on a busy EDT or revive queued old-owner work. */
public class PanelOwnerQueueTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test public void suspendFencesImmediatelyWithoutWaitingForEdt() throws Exception {
        CountingPanel panel = panel();
        Object previous = panel.ownerToken;
        panel.engine.events.put(Inbox.CONSUME, 385, 1, 10, null);
        try (BlockedEdt edt = new BlockedEdt()) {
            edt.client(panel::suspend);
            assertTrue(panel.suspended);
            assertNotSame(previous, panel.ownerToken);
            assertNull("action evidence clears before EDT work", panel.engine.events.peek(Inbox.CONSUME));
        }
        onEdt(() -> { assertNull(panel.snapshot); return null; });
    }

    @Test public void anObsoleteQueuedResumeCannotLiftANewerFence() throws Exception {
        CountingPanel panel = panel();
        AtomicReference<Boolean> fenceBetweenTasks = new AtomicReference<>();
        try (BlockedEdt edt = new BlockedEdt()) {
            edt.client(() -> { panel.suspend(); panel.resume(); });
            SwingUtilities.invokeLater(() -> fenceBetweenTasks.set(panel.suspended));
            edt.client(panel::suspend);
        }
        assertEquals("old resume must remain fenced even before the later clear runs", Boolean.TRUE, fenceBetweenTasks.get());
        assertTrue(panel.suspended);
        assertTrue(panel.captures.isEmpty());
    }

    @Test public void queuedClearCannotEraseAnOwnerResumedDirectlyOnEdt() throws Exception {
        CountingPanel panel = panel();
        try (BlockedEdt edt = new BlockedEdt()) {
            edt.client(panel::suspend);
            edt.beforeQueued = () -> {
                load(panel.engine, "b", "Owner B", 200);
                panel.suspend();
                panel.resume();
                panel.ledgerSearch = "Owner B search";
            };
        }
        onEdt(() -> {
            assertFalse(panel.suspended);
            assertEquals("Owner B search", panel.ledgerSearch);
            assertNotNull(panel.snapshot);
            assertEquals(200, panel.snapshot.net);
            assertFalse(panel.captures.isEmpty());
            assertTrue(panel.captures.stream().allMatch("Owner B"::equals));
            return null;
        });
    }

    @Test public void rapidAToBToAKeepsOnlyTheFinalGeneration() throws Exception {
        CountingPanel panel = panel();
        AtomicReference<Object> firstA = new AtomicReference<>();
        try (BlockedEdt edt = new BlockedEdt()) {
            edt.client(() -> {
                panel.suspend(); firstA.set(panel.ownerToken); panel.resume();
                load(panel.engine, "b", "Owner B", 200);
                panel.suspend(); panel.resume();
                load(panel.engine, "a", "Returned A", 300);
                panel.suspend(); panel.resume();
            });
            assertTrue(panel.suspended);
            assertNotSame("same account still needs a new generation", firstA.get(), panel.ownerToken);
        }
        onEdt(() -> {
            assertFalse(panel.suspended);
            assertFalse(panel.captures.isEmpty());
            assertTrue(panel.captures.stream().allMatch("Returned A"::equals));
            assertEquals(300, panel.snapshot.net);
            return null;
        });
    }

    @Test public void directResumeCompletesAPendingClearForTheSameGeneration() throws Exception {
        CountingPanel panel = panel();
        panel.ledgerSearch = "old search";
        try (BlockedEdt edt = new BlockedEdt()) {
            edt.client(panel::suspend);
            edt.beforeQueued = panel::resume;
        }
        onEdt(() -> {
            assertFalse(panel.suspended);
            assertEquals("", panel.ledgerSearch);
            assertNotNull("the late clear must not erase the resumed snapshot", panel.snapshot);
            assertFalse(panel.captures.isEmpty());
            assertTrue(panel.captures.stream().allMatch("Owner A"::equals));
            return null;
        });
    }

    @Test public void queuedOldOwnerEditCannotMutateTheReturnedAccount() throws Exception {
        CountingPanel panel = panel();
        AtomicReference<Runnable> pending = new AtomicReference<>();
        panel.mutationOwner = pending::set;
        onEdt(() -> { panel.mutate(() -> panel.engine.session().rename("stale edit")); return null; });
        try (BlockedEdt edt = new BlockedEdt()) {
            edt.client(() -> {
                panel.suspend(); load(panel.engine, "b", "Owner B", 200); panel.resume();
                panel.suspend(); load(panel.engine, "a", "Returned A", 300); panel.resume();
                pending.get().run();
            });
        }
        assertEquals("Returned A", panel.engine.session().name());
    }

    @Test public void aNewFenceDuringResumeCannotBeLiftedByThatResume() throws Exception {
        CountingPanel panel = panel();
        panel.suspend();
        onEdt(() -> null);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        panel.clearHook = () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("resume gate timed out");
            } catch (InterruptedException ex) { throw new AssertionError(ex); }
        };
        try {
            panel.resume();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            panel.suspend();
            assertTrue(panel.suspended);
        } finally {
            release.countDown();
            onEdt(() -> null);
        }
        assertTrue("a stale in-progress resume cannot lift the new synchronous fence", panel.suspended);
        assertTrue(panel.captures.isEmpty());
    }

    private static CountingPanel panel() throws Exception {
        JsonCodec.bind(new com.google.gson.Gson());
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        load(engine, "a", "Owner A", 100);
        return onEdt(() -> {
            CountingPanel panel = new CountingPanel(engine);
            panel.active = true;
            panel.refreshNow();
            panel.captures.clear();
            return panel;
        });
    }

    private static void load(Engine engine, String owner, String name, long gp) {
        var session = new Session(name, NOW, SessionMode.GENERAL);
        session.addTransaction(Tx.of(NOW, TransactionType.GAIN, Context.GENERIC, "Owner", true,
                Collections.singletonList(new Flow(995, "Coins", gp, 1, gp))), 2000, false);
        engine.restoreProfile("rsprofile." + owner, new SavedState(session, null, Collections.emptyList()), NOW);
    }

    private static class CountingPanel extends SidebarPanel {
        final List<String> captures = new ArrayList<>();
        volatile Runnable clearHook;
        CountingPanel(Engine engine) { super(engine, new GpManagerConfig() {}, null, null, null); }
        @Override void clearOwner(Object owner) {
            super.clearOwner(owner);
            if (clearHook != null) clearHook.run();
        }
        @Override void refreshNow() {
            if (captures != null && !suspended) captures.add(engine.session().name());
            super.refreshNow();
        }
    }

    private static class BlockedEdt implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final ExecutorService caller = Executors.newSingleThreadExecutor();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile Runnable beforeQueued = () -> {};
        BlockedEdt() throws Exception {
            SwingUtilities.invokeLater(() -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("EDT gate timed out");
                    beforeQueued.run();
                } catch (Throwable ex) { failure.set(ex); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
        }
        void client(Runnable work) throws Exception { caller.submit(work).get(1, TimeUnit.SECONDS); }
        public void close() throws Exception {
            release.countDown();
            caller.shutdown();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
            onEdt(() -> null);
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
