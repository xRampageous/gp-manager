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

/** Restarting the clock recalculates displayed Net over the new Active Time, keeping accounting. */
public class RateRestartTest {
    private LongSupplier clock;
    @Before public void setUp() { clock = SafeMath.clock; JsonCodec.bind(new Gson()); }
    @After public void restoreClock() { SafeMath.clock = clock; }

    @Test public void retainedNetSetsTheRateAtOneMinuteAndItFallsWithActiveTime() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 566_329L);
            restart(engine, HOUR);
            LiveSnapshot waiting = live(engine, HOUR + MINUTE - 1L);
            assertFalse(waiting.ready);
            assertEquals("Calculating…", LivePage.rateText(waiting.ready, waiting.rate));
            long[] activeMillis = {MINUTE, 72_000L, 2L * MINUTE, HOUR};
            long[] expectedRates = {33_979_740L, 28_316_450L, 16_989_870L, 566_329L};
            for (int i = 0; i < activeMillis.length; i++) {
                LiveSnapshot next = live(engine, HOUR + activeMillis[i]);
                assertTrue(next.ready);
                assertEquals(activeMillis[i], next.elapsed);
                assertEquals(expectedRates[i], next.rate);
                assertEquals(566_329L, next.net);
                assertEquals(566_329L, recentTotal(next));
                HudFrame hud = new HudBuilder(HudBuilderTest.config(false, 4), null)
                        .update(next, f -> true, HOUR + activeMillis[i]);
                assertEquals(LivePage.rateText(true, expectedRates[i]), hud.rate);
            }
            return null;
        });
    }

    @Test public void restartUsesTheDisplayedSessionNetInsteadOfOlderRunReceipts() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 500_000L);
            engine.newLive(HOUR);
            coins(engine, HOUR + 5L, 566_329L);
            restart(engine, 2L * HOUR);
            LiveSnapshot next = live(engine, 2L * HOUR + MINUTE);
            assertEquals(566_329L, next.net);
            assertEquals(1_066_329L, next.run);
            assertEquals(566_329L, recentTotal(next));
            assertEquals(33_979_740L, next.rate);
            assertEquals(MINUTE, next.elapsed);
            return null;
        });
    }

    @Test public void freePlayKeepsNetRecentAndLedgerWhileRateUsesTheRetainedNet() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 500_000L);
            Session run = engine.session();
            ContainerSnapshot baseline = ContainerSnapshot.empty();
            engine.baseline = baseline;
            restart(engine, HOUR);
            assertSame("a rate reset cannot re-prime inventory or lose pending movement", baseline, engine.baseline);
            LiveSnapshot just = live(engine, HOUR);
            assertEquals(500_000L, just.net);
            assertEquals(500_000L, recentTotal(just));
            assertEquals(0L, just.elapsed);
            assertEquals(0L, just.rate);
            assertFalse(just.ready);
            coins(engine, HOUR + 5L, 50_000L);
            LiveSnapshot next = live(engine, HOUR + HOUR / 2L);
            assertEquals(550_000L, next.net);
            assertEquals(550_000L, next.run);
            assertEquals(550_000L, recentTotal(next));
            assertEquals(HOUR / 2L, next.elapsed);
            assertEquals(1_100_000L, next.rate);
            assertSame(run, engine.session());
            assertEquals(2, run.transactions().size());
            assertTrue(engine.history().isEmpty());
            assertEquals(550_000L, engine.summary().metrics(run, HOUR + HOUR / 2L).net);
            HudFrame hud = new HudBuilder(HudBuilderTest.config(false, 4), null)
                    .update(next, f -> true, HOUR + HOUR / 2L);
            assertEquals(Fmt.signed(next.net), hud.net);
            assertEquals(LivePage.rateText(true, next.rate), hud.rate);
            assertEquals(new HudBuilder(HudBuilderTest.config(false, 4), null).timer(next.elapsed), hud.timer);
            return null;
        });
    }

    @Test public void lossesAndRepeatedRestartsUseTheDisplayedSignedNet() throws Exception {
        onEdt(() -> {
            for (long base : new long[]{0L, -1_000L}) {
                Engine engine = engine();
                if (base != 0L) coins(engine, 5L, base);
                restart(engine, HOUR);
                coins(engine, HOUR + 5L, -200L);
                assertEquals(base - 200L, live(engine, 2L * HOUR).net);
                assertEquals(base - 200L, live(engine, 2L * HOUR).rate);
                restart(engine, 2L * HOUR);
                coins(engine, 2L * HOUR + 5L, 50L);
                assertEquals(base - 150L, live(engine, 3L * HOUR).net);
                assertEquals(base - 150L, live(engine, 3L * HOUR).rate);
            }
            Engine extreme = engine();
            coins(extreme, 5L, Long.MIN_VALUE + 1L);
            restart(extreme, HOUR);
            coins(extreme, HOUR + 5L, Long.MAX_VALUE);
            coins(extreme, HOUR + 6L, Long.MAX_VALUE);
            assertEquals("saturated revenue and costs leave zero displayed Net", 0L,
                    live(extreme, 2L * HOUR).net);
            assertEquals(0L, live(extreme, 2L * HOUR).rate);
            for (long net : new long[]{Long.MAX_VALUE, -Long.MAX_VALUE}) {
                Engine signed = engine();
                coins(signed, 5L, net);
                restart(signed, HOUR);
                LiveSnapshot next = live(signed, HOUR + MINUTE);
                assertEquals(net, next.net);
                assertEquals("a signed rate saturates rather than wrapping",
                        net > 0L ? Long.MAX_VALUE : Long.MIN_VALUE, next.rate);
            }
            return null;
        });
    }

    @Test public void netTimeTargetsAndMilestonesKeepTheirOwnProgress() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            engine.setTargets(5_000L, 4L * HOUR, 0L);
            engine.setMilestone(400_000L);
            coins(engine, 5L, 1_000L);
            LiveSnapshot before = live(engine, HOUR);
            restart(engine, HOUR);
            LiveSnapshot after = live(engine, HOUR);
            assertEquals(before.goal.current, after.goal.current);
            assertEquals(before.goal.remaining, after.goal.remaining);
            assertEquals(before.milestone, after.milestone);
            assertEquals(before.run, after.run);
            coins(engine, HOUR + 5L, 500L);
            LiveSnapshot next = live(engine, HOUR + HOUR / 2L);
            assertEquals(1_500L, next.goal.current);
            assertEquals(5L * HOUR / 2L, next.goal.remaining);
            assertEquals(3_000L, next.rate);
            return null;
        });
    }

    @Test public void pausedRestartsStayPausedAndExcludeThePausedTime() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 1_000L);
            engine.togglePause(HOUR);
            restart(engine, 2L * HOUR);
            assertTrue(engine.session().paused);
            assertEquals(0L, live(engine, 3L * HOUR).elapsed);
            engine.togglePause(3L * HOUR);
            coins(engine, 3L * HOUR + 5L, 500L);
            LiveSnapshot next = live(engine, 4L * HOUR);
            assertEquals(HOUR, next.elapsed);
            assertEquals(1_500L, next.rate);
            assertEquals(1_500L, next.net);
            return null;
        });
    }

    @Test public void undoAndRestoreKeepTheRateAlignedWithDisplayedNet() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 1_000L);
            restart(engine, HOUR);
            engine.undoTransaction();
            assertEquals(0L, live(engine, 2L * HOUR).net);
            assertEquals("zero displayed Net means a zero rate", 0L,
                    live(engine, 2L * HOUR).rate);
            engine.restoreUndo();
            assertEquals(1_000L, live(engine, 2L * HOUR).net);
            assertEquals(1_000L, live(engine, 2L * HOUR).rate);
            coins(engine, HOUR + 5L, 500L);
            assertEquals(1_500L, live(engine, 2L * HOUR).rate);
            return null;
        });
    }

    @Test public void savedRateWindowsRoundTripAndOlderOrInvalidMarksUseTheNormalRate() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 1_000L);
            restart(engine, HOUR);
            coins(engine, HOUR + 5L, 500L);
            Gson gson = new Gson();
            JsonObject json = gson.toJsonTree(engine.exportState()).getAsJsonObject();
            Engine loaded = load(json, 2L * HOUR);
            assertEquals(1_500L, live(loaded, 2L * HOUR).net);
            assertEquals(1_500L, live(loaded, 2L * HOUR).rate);
            assertEquals(HOUR, live(loaded, 2L * HOUR).elapsed);
            JsonObject savedRun = json.getAsJsonObject("generalSession");
            savedRun.remove("rateStartAt");
            savedRun.remove("rateSetMillis");
            savedRun.addProperty("rateSetNet", 1_000L);
            assertEquals(750L, live(load(json, 2L * HOUR), 2L * HOUR).rate);
            for (long invalid : new long[]{-1L, 3L * HOUR}) {
                savedRun.addProperty("rateStartAt", HOUR);
                savedRun.addProperty("rateSetMillis", invalid);
                assertEquals(750L, live(load(json, 2L * HOUR), 2L * HOUR).rate);
                savedRun.addProperty("rateSetMillis", HOUR);
                savedRun.addProperty("rateStartAt", invalid);
                assertEquals(750L, live(load(json, 2L * HOUR), 2L * HOUR).rate);
            }
            return null;
        });
    }

    @Test public void wholeRunBookingsRestoreTheOriginalClock() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 1_000L);
            restart(engine, HOUR);
            engine.newLive(2L * HOUR);
            coins(engine, 2L * HOUR + 5L, 500L);
            assertEquals(500L, live(engine, 3L * HOUR).net);
            assertEquals(500L, live(engine, 3L * HOUR).rate);
            restart(engine, 3L * HOUR);
            engine.showWholeRun();
            LiveSnapshot whole = live(engine, 3L * HOUR);
            assertEquals(1_500L, whole.net);
            assertEquals(3L * HOUR, whole.elapsed);
            assertEquals(500L, whole.rate);
            return null;
        });
    }

    @Test public void grindHistoryKeepsTheWholeRunAndAnotherGrindGetsNoOldBaseline() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            engine.startCustom("Vorkath", 0L);
            coins(engine, 5L, 1_000L);
            restart(engine, HOUR);
            coins(engine, HOUR + 5L, 500L);
            assertEquals(1_500L, live(engine, 2L * HOUR).rate);
            assertEquals(750L, GrindsData.capture(engine, 2L * HOUR, false, null).rate);
            engine.finishCustom(2L * HOUR);
            assertEquals(750L, engine.history().get(0).metrics(2L * HOUR).rate);
            engine.startCustom("Zulrah", 2L * HOUR);
            coins(engine, 2L * HOUR + 5L, 50L);
            assertEquals(50L, live(engine, 3L * HOUR).rate);
            assertEquals(HOUR, live(engine, 3L * HOUR).elapsed);
            engine.restoreProfile("next-owner", new SavedState(), 3L * HOUR);
            engine.ensureSession(3L * HOUR);
            coins(engine, 3L * HOUR + 5L, 50L);
            assertEquals(50L, live(engine, 4L * HOUR).net);
            assertEquals(50L, live(engine, 4L * HOUR).rate);
            return null;
        });
    }

    @Test public void sessionChoicesStayReadableAtSupportedSidebarWidths() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel(engine());
            new GrindsController(panel).newSession();
            for (int width : new int[]{213, 225, 300}) {
                DirectHeaderControlsTest.size(panel.shell.sheet, width);
                for (javax.swing.JButton button : ShellProbe.sheetButtons(panel.shell)) {
                    java.awt.Rectangle box = DirectHeaderControlsTest.bounds(button, panel.shell.sheet);
                    assertTrue(button.getText() + " stays inside at " + width,
                            box.x >= 0 && box.getMaxX() <= width && box.y >= 0
                            && box.getMaxY() <= panel.shell.sheet.getHeight());
                    assertTrue(button.getText() + " fits its row", button.getY() + button.getHeight()
                            <= button.getParent().getHeight());
                    assertTrue(button.getText() + " fits without clipping at " + width,
                            button.getFontMetrics(button.getFont()).stringWidth(button.getText())
                                    + button.getInsets().left + button.getInsets().right <= button.getWidth());
                }
            }
            return null;
        });
    }

    @Test public void deleteProfileChoicesAlsoStayInsideTheNarrowSheet() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel(engine());
            new GrindsController(panel).factoryReset();
            for (int width : new int[]{213, 225, 300}) {
                DirectHeaderControlsTest.size(panel.shell.sheet, width);
                for (javax.swing.JButton button : ShellProbe.sheetButtons(panel.shell)) {
                    java.awt.Rectangle box = DirectHeaderControlsTest.bounds(button, panel.shell.sheet);
                    assertTrue(button.getText() + " stays inside at " + width,
                            box.x >= 0 && box.getMaxX() <= width && box.y >= 0
                            && box.getMaxY() <= panel.shell.sheet.getHeight());
                }
            }
            return null;
        });
    }

    @Test public void cancelAndNoActiveRunNeverStartOrResetAnything() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            coins(engine, 5L, 1_000L);
            SafeMath.clock = () -> HOUR;
            SidebarPanel panel = panel(engine);
            new GrindsController(panel).newSession();
            assertTrue(ShellProbe.sheetButtonTexts(panel.shell).contains("Restart GP/h"));
            assertTrue(ShellProbe.sheetButtonTexts(panel.shell).contains("New session"));
            ShellProbe.pressSheet(panel.shell, "Cancel");
            assertEquals(HOUR, live(engine, HOUR).elapsed);
            assertEquals(1_000L, live(engine, HOUR).rate);
            Engine idle = PresentationLifecycleTest.engine();
            restart(idle, HOUR);
            assertNull(idle.session());
            return null;
        });
    }

    static SidebarPanel panel(Engine engine) {
        return new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null);
    }

    static void restart(Engine engine, long at) {
        SafeMath.clock = () -> at;
        SidebarPanel panel = panel(engine);
        DirectHeaderControlsTest.click(panel.live.hero.clock);
        assertTrue("clock offers a rate-only restart", ShellProbe.sheetButtonTexts(panel.shell).contains("Restart GP/h"));
        ShellProbe.pressSheet(panel.shell, "Restart GP/h");
        assertEquals("", panel.shell.heading);
    }

    static Engine load(JsonObject json, long at) {
        SavedState state = new Gson().fromJson(json, SavedState.class);
        state.savedAtEpochMillis = at;
        Engine engine = engine();
        engine.restoreProfile("rate-test", state, at);
        engine.togglePause(at);
        return engine;
    }
}
