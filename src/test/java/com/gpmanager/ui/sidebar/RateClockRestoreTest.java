package com.gpmanager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.function.LongSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.gpmanager.MilestoneTest.onEdt;
import static com.gpmanager.TargetFreshStartTest.*;
import static org.junit.Assert.*;

/** Returning to whole-run bookings restores its clock; an optional previous clock stays frozen. */
public class RateClockRestoreTest {
    private LongSupplier clock;
    @Before public void setUp() { clock = SafeMath.clock; JsonCodec.bind(new Gson()); }
    @After public void tearDown() { SafeMath.clock = clock; }

    @Test public void freePlaySaveReturnsToTheOriginalClock() throws Exception { saveChoice(false, true); }
    @Test public void freePlayContinueReturnsToTheOriginalClock() throws Exception { saveChoice(false, false); }
    @Test public void grindSaveReturnsToTheOriginalClock() throws Exception { saveChoice(true, true); }
    @Test public void grindContinueReturnsToTheOriginalClock() throws Exception { saveChoice(true, false); }

    private void saveChoice(boolean grind, boolean save) throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            if (grind) engine.startCustom("Vorkath", 0L);
            coins(engine, 5L, 566_329L);
            long resetAt = 16L * HOUR;
            choose(engine, resetAt, "Restart GP/h");
            assertClock(engine, resetAt, 0L, 0L);
            choose(engine, resetAt, "New session");
            assertEquals(0L, live(engine, resetAt).net);
            assertClock(engine, resetAt, 0L, 0L);
            coins(engine, resetAt + 5L, 500L);
            SafeMath.clock = () -> resetAt + 30_000L;
            SidebarPanel panel = RateRestartTest.panel(engine);
            panel.active = true;
            panel.refreshNow();
            DirectHeaderControlsTest.click(panel.live.total);
            assertEquals("Save session?", panel.shell.heading);
            ShellProbe.pressSheet(panel.shell, save ? "Save session" : "Continue without saving");
            assertClock(engine, resetAt + 30_000L, resetAt + 30_000L, Session.hourly(566_829L, resetAt + 30_000L));
            assertClock(engine, resetAt + MINUTE, resetAt + MINUTE, Session.hourly(566_829L, resetAt + MINUTE));
            assertEquals(566_829L, live(engine, resetAt + MINUTE).net);
            assertEquals(566_829L, recentTotal(live(engine, resetAt + MINUTE)));
            assertEquals(566_829L, GrindsData.allTime(engine, resetAt + MINUTE).net);
            assertEquals(save ? 1 : 0, engine.saved.size());
            assertTrue(engine.history().isEmpty());
            Gson gson = new Gson();
            String savedBefore = gson.toJson(engine.saved);
            String receipts = gson.toJson(engine.session().transactions());
            if (save) {
                Session copy = engine.saved.get(0).session;
                assertEquals(500L, copy.metrics(resetAt + MINUTE).net);
                assertEquals(30_000L, copy.elapsed(resetAt + MINUTE));
            }
            choose(engine, 17L * HOUR, "Restore time");
            assertClock(engine, 17L * HOUR, 16L * HOUR, Session.hourly(566_829L, 16L * HOUR));
            assertEquals(savedBefore, gson.toJson(engine.saved));
            assertEquals(receipts, gson.toJson(engine.session().transactions()));
            assertEquals(566_829L, GrindsData.allTime(engine, 17L * HOUR).net);
            return null;
        });
    }

    @Test public void restoreOriginalTimeKeepsAFreshReceiptWindowAndItsNet() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 566_329L);
            engine.newLive(16L * HOUR);
            coins(engine, 16L * HOUR + 5L, 34_000L);
            LiveSnapshot before = live(engine, 17L * HOUR);
            assertEquals(34_000L, before.net);
            assertClock(engine, 17L * HOUR, HOUR, 34_000L);
            choose(engine, 17L * HOUR, "Restore time");
            LiveSnapshot after = live(engine, 17L * HOUR);
            assertTrue(after.fresh);
            assertEquals(before.net, after.net);
            assertEquals(before.run, after.run);
            assertEquals(recentTotal(before), recentTotal(after));
            assertClock(engine, 17L * HOUR, 17L * HOUR, 2_000L);
            assertEquals(600_329L, engine.metrics(17L * HOUR).net);
            assertEquals(17L * HOUR, engine.metrics(17L * HOUR).elapsed);
            return null;
        });
    }

    @Test public void anotherRestartOrNewSessionStartsAtZeroAfterRestoring() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 34_000L);
            choose(engine, 16L * HOUR, "Restart GP/h");
            choose(engine, 17L * HOUR, "Restore time");
            assertClock(engine, 17L * HOUR, 16L * HOUR, 2_125L);
            choose(engine, 17L * HOUR, "Restart GP/h");
            assertClock(engine, 17L * HOUR, 0L, 0L);
            assertClock(engine, 17L * HOUR + MINUTE, MINUTE, 2_040_000L);
            choose(engine, 18L * HOUR, "Restore time");
            choose(engine, 18L * HOUR, "New session");
            assertClock(engine, 18L * HOUR, 0L, 0L);
            assertEquals(0L, live(engine, 18L * HOUR).net);
            return null;
        });
    }

    @Test public void restoringWhilePausedKeepsTargetsAndExcludesPausedTime() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            engine.setTargets(100_000L, 20L * HOUR, 0L);
            engine.setMilestone(400_000L);
            coins(engine, 5L, 34_000L);
            engine.togglePause(16L * HOUR);
            LiveSnapshot before = live(engine, 17L * HOUR);
            choose(engine, 17L * HOUR, "Restart GP/h");
            assertClock(engine, 17L * HOUR, 0L, 0L);
            choose(engine, 17L * HOUR, "Restore time");
            LiveSnapshot restored = live(engine, 18L * HOUR);
            assertTrue(engine.session().paused);
            assertClock(engine, 18L * HOUR, 16L * HOUR, 2_125L);
            assertEquals(before.goal.current, restored.goal.current);
            assertEquals(before.goal.remaining, restored.goal.remaining);
            assertEquals(before.milestone, restored.milestone);
            engine.togglePause(18L * HOUR);
            assertClock(engine, 19L * HOUR, 17L * HOUR, 2_000L);
            assertEquals(34_000L, engine.metrics(19L * HOUR).net);
            return null;
        });
    }

    @Test public void resetAndRestoredClocksSurviveRelogAndStayWithTheirProfile() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 34_000L);
            engine.newLive(16L * HOUR);
            coins(engine, 16L * HOUR + 5L, 34_000L);
            engine.showWholeRun();
            engine.togglePause(17L * HOUR);
            SafeMath.clock = () -> 17L * HOUR;
            Gson gson = new Gson();
            Engine loaded = RateRestartTest.load(gson.toJsonTree(engine.exportState()).getAsJsonObject(), 20L * HOUR);
            assertClock(loaded, 20L * HOUR, 17L * HOUR, 4_000L);
            choose(loaded, 20L * HOUR, "Restore time");
            assertClock(loaded, 20L * HOUR, 17L * HOUR, 4_000L);
            loaded.togglePause(20L * HOUR);
            Engine again = RateRestartTest.load(gson.toJsonTree(loaded.exportState()).getAsJsonObject(), 24L * HOUR);
            assertClock(again, 24L * HOUR, 17L * HOUR, 4_000L);
            again.restoreProfile("another-owner", new SavedState(), 24L * HOUR);
            again.ensureSession(24L * HOUR);
            assertEquals(0L, live(again, 24L * HOUR).net);
            assertClock(again, 24L * HOUR, 0L, 0L);
            return null;
        });
    }

    @Test public void onlyTheExactOriginalStartPairMayReachBeforeTheReceiptWindow() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            engine.newLive(16L * HOUR);
            coins(engine, 16L * HOUR + 5L, 34_000L);
            JsonObject json = new Gson().toJsonTree(engine.exportState()).getAsJsonObject();
            JsonObject run = json.getAsJsonObject("generalSession");
            long[][] invalid = {{-1L, 0L}, {1L, 0L}, {0L, -1L}, {0L, HOUR}, {18L * HOUR, 0L}};
            for (long[] marks : invalid) {
                run.addProperty("rateStartAt", marks[0]);
                run.addProperty("rateSetMillis", marks[1]);
                Engine loaded = RateRestartTest.load(json, 17L * HOUR);
                assertClock(loaded, 17L * HOUR, HOUR, 34_000L);
            }
            run.addProperty("rateStartAt", 0L);
            run.addProperty("rateSetMillis", 0L);
            Engine restored = RateRestartTest.load(json, 17L * HOUR);
            assertClock(restored, 17L * HOUR, 17L * HOUR, 2_000L);
            return null;
        });
    }

    @Test public void cancelAndRestoreWithNoActiveRunLeaveStateAlone() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 34_000L);
            choose(engine, 16L * HOUR, "Restart GP/h");
            SafeMath.clock = () -> 17L * HOUR;
            SidebarPanel panel = RateRestartTest.panel(engine);
            new GrindsController(panel).newSession();
            assertTrue(ShellProbe.sheetButtonTexts(panel.shell).contains("Restore time"));
            ShellProbe.pressSheet(panel.shell, "Cancel");
            assertClock(engine, 17L * HOUR, HOUR, 34_000L);
            Engine idle = PresentationLifecycleTest.engine();
            choose(idle, 17L * HOUR, "Restore time");
            assertNull(idle.session());
            assertTrue(idle.history().isEmpty());
            return null;
        });
    }

    @Test public void sessionHelpFitsTheNarrowSheet() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = RateRestartTest.panel(engine());
            new GrindsController(panel).newSession();
            for (int width : new int[]{173, 185, 260}) {
                DirectHeaderControlsTest.size(panel.shell.sheet, width);
                boolean found = false;
                java.util.ArrayDeque<java.awt.Component> pending = new java.util.ArrayDeque<>();
                pending.add(panel.shell.sheet);
                while (!pending.isEmpty()) {
                    java.awt.Component component = pending.removeFirst();
                    if (component instanceof java.awt.Container)
                        java.util.Collections.addAll(pending, ((java.awt.Container) component).getComponents());
                    if (component instanceof javax.swing.JLabel
                            && ((javax.swing.JLabel) component).getText().startsWith("<html>")) {
                        found = true;
                        assertTrue("help fits the inner sheet at " + width,
                                component.getPreferredSize().width <= component.getWidth());
                    }
                }
                assertTrue("Session controls has readable help", found);
            }
            return null;
        });
    }

    @Test public void compactControlsKeepTwoButtonRowsAndDetailedHelp() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = RateRestartTest.panel(engine());
            new GrindsController(panel).newSession();
            java.util.List<javax.swing.JButton> buttons = ShellProbe.sheetButtons(panel.shell);
            javax.swing.JButton fresh = buttons.stream().filter(b -> b.getText().equals("New session")).findFirst().get();
            javax.swing.JButton restore = buttons.stream().filter(b -> b.getText().equals("Restore time")).findFirst().get();
            javax.swing.JButton restart = buttons.stream().filter(b -> b.getText().equals("Restart GP/h")).findFirst().get();
            javax.swing.JButton cancel = buttons.stream().filter(b -> b.getText().equals("Cancel")).findFirst().get();
            assertEquals(4, buttons.size());
            assertSame(fresh.getParent(), restore.getParent());
            assertSame(restart.getParent(), cancel.getParent());
            assertNotSame(fresh.getParent(), restart.getParent());
            assertFalse(fresh.getToolTipText().isEmpty());
            assertTrue(restore.getToolTipText().contains("original"));
            for (int width : new int[]{173, 185, 260}) {
                DirectHeaderControlsTest.size(panel.shell.sheet, width);
                assertTrue("compact sheet at " + width, panel.shell.sheet.getHeight() <= 160);
                for (javax.swing.JButton button : buttons) {
                    java.awt.Rectangle box = DirectHeaderControlsTest.bounds(button, panel.shell.sheet);
                    assertTrue(button.getText() + " stays inside at " + width,
                            box.x >= 0 && box.getMaxX() <= width && box.y >= 0
                                    && box.getMaxY() <= panel.shell.sheet.getHeight());
                    assertTrue(button.getText() + " stays readable at " + width,
                            button.getFontMetrics(button.getFont()).stringWidth(button.getText())
                                    + button.getInsets().left + button.getInsets().right <= button.getWidth());
                }
            }
            return null;
        });
    }

    static void choose(Engine engine, long at, String choice) {
        SafeMath.clock = () -> at;
        SidebarPanel panel = RateRestartTest.panel(engine);
        new GrindsController(panel).newSession();
        assertTrue("Session controls offers " + choice, ShellProbe.sheetButtonTexts(panel.shell).contains(choice));
        ShellProbe.pressSheet(panel.shell, choice);
        assertEquals("", panel.shell.heading);
    }

    private static void assertClock(Engine engine, long at, long elapsed, long rate) {
        LiveSnapshot live = live(engine, at);
        assertEquals("Live Active Time", elapsed, live.elapsed);
        if (elapsed >= MINUTE || elapsed == 0L) assertEquals(rate, live.rate);
        assertEquals(elapsed >= MINUTE, live.ready);
        HudBuilder builder = new HudBuilder(HudBuilderTest.config(false, 4), null);
        HudFrame hud = builder.update(live, f -> true, at);
        assertEquals(builder.timer(elapsed), hud.timer);
        assertEquals(LivePage.rateText(live.ready, rate), hud.rate);
    }
}
