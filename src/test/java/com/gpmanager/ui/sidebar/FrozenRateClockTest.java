package com.gpmanager;

import com.google.gson.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;
import javax.swing.JCheckBox;
import org.junit.*;

import static com.gpmanager.MilestoneTest.onEdt;
import static com.gpmanager.TargetFreshStartTest.*;
import static org.junit.Assert.*;

/** A frozen clock is presentation metadata, independent of receipts and the canonical run clock. */
public class FrozenRateClockTest {
    LongSupplier previous;
    @Before public void setUp() { previous = SafeMath.clock; JsonCodec.bind(new Gson()); }
    @After public void tearDown() { SafeMath.clock = previous; JsonCodec.bind(new Gson()); }

    static JCheckBox keep(SidebarPanel panel) {
        var pending = new ArrayDeque<Component>();
        pending.add(panel.shell.sheet);
        while (!pending.isEmpty()) {
            var component = pending.removeFirst();
            if (component instanceof JCheckBox && ((JCheckBox) component).getText().equals("Keep previous clock"))
                return (JCheckBox) component;
            if (component instanceof Container)
                java.util.Collections.addAll(pending, ((Container) component).getComponents());
        }
        throw new AssertionError("Session controls must offer Keep previous clock");
    }

    static void restart(Engine engine, long at, boolean save) {
        SafeMath.clock = () -> at;
        var panel = RateRestartTest.panel(engine);
        new GrindsController(panel).newSession();
        assertTrue("the safe default retains the previous clock", keep(panel).isSelected());
        keep(panel).setSelected(save);
        ShellProbe.pressSheet(panel.shell, "Restart GP/h");
    }

    @Test public void restoreResumesTheFrozenTimeAndUsesCurrentNet() throws Exception {
        onEdt(() -> {
            var engine = engine(); coins(engine, 5, 566_329);
            restart(engine, 19 * HOUR, true);
            assertEquals(0, live(engine, 19 * HOUR).elapsed);
            assertEquals(33_979_740, live(engine, 19 * HOUR + MINUTE).rate);
            coins(engine, 20 * HOUR, 1000);
            RateClockRestoreTest.choose(engine, 21 * HOUR, "Restore time");
            assertEquals(19 * HOUR, live(engine, 21 * HOUR).elapsed);
            assertEquals(Session.hourly(567_329, 19 * HOUR), live(engine, 21 * HOUR).rate);
            assertEquals(19 * HOUR + MINUTE, live(engine, 21 * HOUR + MINUTE).elapsed);
            assertEquals(21 * HOUR, engine.metrics(21 * HOUR).elapsed);
            assertEquals(567_329, engine.metrics(21 * HOUR).net);
            return null;
        });
    }

    @Test public void uncheckedRestartKeepsAnOlderBackupAndCheckedRestartReplacesIt() throws Exception {
        onEdt(() -> {
            var engine = engine();
            restart(engine, 19 * HOUR, true);
            restart(engine, 20 * HOUR, false);
            RateClockRestoreTest.choose(engine, 21 * HOUR, "Restore time");
            assertEquals(19 * HOUR, live(engine, 21 * HOUR).elapsed);
            restart(engine, 22 * HOUR, true);
            RateClockRestoreTest.choose(engine, 24 * HOUR, "Restore time");
            assertEquals(20 * HOUR, live(engine, 24 * HOUR).elapsed);
            return null;
        });
    }

    @Test public void noBackupRetainsTheOriginalTimeFallback() throws Exception {
        onEdt(() -> {
            var engine = engine(); restart(engine, 19 * HOUR, false);
            RateClockRestoreTest.choose(engine, 20 * HOUR, "Restore time");
            assertEquals(20 * HOUR, live(engine, 20 * HOUR).elapsed);
            return null;
        });
    }

    @Test public void freePlayTargetSaveRestoresOriginalTime() throws Exception { targetChoice(false, true); }
    @Test public void freePlayTargetContinueRestoresOriginalTime() throws Exception { targetChoice(false, false); }
    @Test public void grindTargetSaveRestoresOriginalTime() throws Exception { targetChoice(true, true); }
    @Test public void grindTargetContinueRestoresOriginalTime() throws Exception { targetChoice(true, false); }

    void targetChoice(boolean grind, boolean save) throws Exception {
        onEdt(() -> {
            var engine = engine(); if (grind) engine.startCustom("Vorkath", 0);
            coins(engine, 5, 1000); engine.setTargets(100_000L, 30 * HOUR, 18 * HOUR);
            coins(engine, 18 * HOUR + 5, 200); restart(engine, 19 * HOUR, true);
            var before = live(engine, 20 * HOUR); SafeMath.clock = () -> 20 * HOUR;
            var panel = RateRestartTest.panel(engine); new GrindsController(panel).saveSessionPrompt();
            ShellProbe.pressSheet(panel.shell, save ? "Save session" : "Continue without saving");
            var after = live(engine, 20 * HOUR);
            assertEquals(20 * HOUR, after.elapsed); assertEquals(200, after.net); assertTrue(after.fresh);
            assertEquals(before.goal.current, after.goal.current);
            assertEquals(before.goal.remaining, after.goal.remaining);
            assertEquals(1200, engine.metrics(20 * HOUR).net);
            assertEquals(save ? 1 : 0, engine.saved.size());
            if (save) assertEquals(HOUR, engine.saved.get(0).session.elapsed(20 * HOUR));
            return null;
        });
    }

    @Test public void aFrozenBackupSurvivesRelogAndRestoringDoesNotCountPausesOrOfflineTime() throws Exception {
        onEdt(() -> {
            var engine = engine(); restart(engine, 19 * HOUR, true);
            engine.togglePause(20 * HOUR);
            var loaded = RateRestartTest.load(new Gson().toJsonTree(engine.exportState()).getAsJsonObject(), 24 * HOUR);
            RateClockRestoreTest.choose(loaded, 24 * HOUR, "Restore time");
            assertEquals(19 * HOUR, live(loaded, 24 * HOUR).elapsed);
            assertEquals(20 * HOUR, live(loaded, 25 * HOUR).elapsed);
            SafeMath.clock = () -> 25 * HOUR;
            var state = new Gson().fromJson(new Gson().toJson(loaded.exportState()), SavedState.class);
            state.savedAtEpochMillis = 25 * HOUR;
            var again = engine(); again.restoreProfile("rate-test", state, 30 * HOUR); again.togglePause(30 * HOUR);
            assertEquals(20 * HOUR, live(again, 30 * HOUR).elapsed);
            again.restoreProfile("other", new SavedState(), 30 * HOUR);
            again.ensureSession(30 * HOUR);
            assertEquals(0, live(again, 30 * HOUR).elapsed);
            return null;
        });
    }

    @Test public void aSavedReceiptWindowRetainsARestoredClockLongerThanItsWallTime() throws Exception {
        onEdt(() -> {
            var engine = engine(); coins(engine, 5, 1000);
            restart(engine, 19 * HOUR, true);
            engine.newLive(20 * HOUR); coins(engine, 20 * HOUR + 5, 200);
            RateClockRestoreTest.choose(engine, 21 * HOUR, "Restore time");
            var source = engine.session();
            var id = EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 21 * HOUR);
            var saved = engine.historySession(id);
            assertEquals(19 * HOUR, saved.elapsed(22 * HOUR));
            assertEquals("the saved booking window keeps its actual start", 20 * HOUR, saved.startedAt);
            assertEquals(200, saved.metrics(22 * HOUR).net);
            assertEquals(1, saved.transactions().size());
            assertEquals(Long.valueOf(20 * HOUR), source.sessionStartAt);
            assertEquals(1200, GrindsData.allTime(engine, 21 * HOUR).net);
            var loaded = RateRestartTest.load(new Gson().toJsonTree(engine.exportState()).getAsJsonObject(), 22 * HOUR);
            assertEquals(19 * HOUR, loaded.historySession(id).elapsed(22 * HOUR));
            assertEquals(20 * HOUR, loaded.historySession(id).startedAt);
            return null;
        });
    }

    @Test public void savedDurationIsOptionalAndCannotReplaceAnActiveRunClock() {
        var gson = new Gson();
        var json = new JsonObject();
        json.addProperty("startedAtEpochMillis", 1000); json.addProperty("endedAtEpochMillis", 10000);
        json.addProperty("totalPausedMillis", 1000);
        assertEquals(8000, gson.fromJson(json, Session.class).elapsed(20000));
        json.add("savedActiveMillis", JsonNull.INSTANCE);
        assertEquals(8000, gson.fromJson(json, Session.class).elapsed(20000));
        json.addProperty("savedActiveMillis", -1);
        assertEquals(8000, gson.fromJson(json, Session.class).elapsed(20000));
        for (long saved : new long[]{0, 19 * HOUR, Long.MAX_VALUE}) {
            json.addProperty("savedActiveMillis", saved);
            assertEquals(saved, gson.fromJson(json, Session.class).elapsed(20000));
        }
        json.addProperty("endedAtEpochMillis", 0);
        assertEquals(8000, gson.fromJson(json, Session.class).elapsed(10000));
    }

    @Test public void malformedFrozenMarksFailClosedAndMissingFieldsKeepLegacyBehavior() throws Exception {
        onEdt(() -> {
            var engine = engine(); engine.restartRate(HOUR, true);
            var json = new Gson().toJsonTree(engine.exportState()).getAsJsonObject();
            var run = json.getAsJsonObject("generalSession");
            for (long invalid : new long[]{-1, 3 * HOUR}) {
                run.addProperty("savedRateMillis", invalid);
                var loaded = RateRestartTest.load(json, 2 * HOUR);
                RateClockRestoreTest.choose(loaded, 2 * HOUR, "Restore time");
                assertEquals(HOUR, live(loaded, 2 * HOUR).elapsed);
                run.addProperty("restoredRateMillis", invalid);
                assertEquals(2 * HOUR, live(RateRestartTest.load(json, 2 * HOUR), 2 * HOUR).elapsed);
                run.remove("restoredRateMillis");
            }
            run.remove("savedRateMillis"); run.remove("restoredRateMillis");
            assertEquals(HOUR, live(RateRestartTest.load(json, 2 * HOUR), 2 * HOUR).elapsed);
            run.add("savedRateMillis", JsonNull.INSTANCE); run.add("restoredRateMillis", JsonNull.INSTANCE);
            assertEquals(HOUR, live(RateRestartTest.load(json, 2 * HOUR), 2 * HOUR).elapsed);
            return null;
        });
    }

    @Test public void savingDoesNotSerializeOrBuildAFullLiveProjection() throws Exception {
        onEdt(() -> {
            var engine = SavedSessionTest.window(); var source = engine.session();
            JsonCodec.bind(new GsonBuilder().registerTypeAdapter(Session.class, (JsonSerializer<Session>) (s, t, c) -> {
                throw new AssertionError("Save session must not serialize on the caller thread");
            }).create());
            var copy = engine.historySession(EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 2 * MINUTE));
            assertEquals(500, copy.metrics(0).net);
            assertNotSame(source.transactions.get(1), copy.transactions.get(0));
            assertNotSame(source.transactions.get(1).flows.get(0), copy.transactions.get(0).flows.get(0));
            return null;
        });
    }

    @Test public void splitPreviewCopiesWithoutSerializationOrChangingTheOriginal() throws Exception {
        onEdt(() -> {
            var engine = engine();
            var receipt = new Transaction(5, TransactionType.GAIN, Context.GENERIC, "", "Test", true,
                    java.util.List.of(new Flow(536, "Dragon bones", 3, 100, 300, PriceSource.GRAND_EXCHANGE)),
                    Confidence.CONFIRMED, "", null);
            engine.session().addTransaction(receipt, 2000, false);
            JsonCodec.bind(new GsonBuilder().registerTypeAdapter(Transaction.class, (JsonSerializer<Transaction>) (s, t, c) -> {
                throw new AssertionError("Split preview must not serialize on the caller thread");
            }).create());
            var preview = LedgerData.splitPreview(engine, receipt.id(), 536, 1, MINUTE);
            assertEquals(100, preview.afterNet); assertEquals(300, receipt.net());
            assertEquals(3, receipt.quantity(536, true)); assertEquals(300, engine.metrics(MINUTE).net);
            return null;
        });
    }

    @Test public void recentUsesItsPreviousDarkBackgroundIncludingAfterHover() throws Exception {
        onEdt(() -> {
            var panel = SavedSessionTest.panel(SavedSessionTest.window()); var recent = panel.live.recent;
            assertEquals(Kit.CARD, recent.getBackground());
            assertEquals(Kit.CARD, recent.header.getBackground());
            assertEquals(Kit.CARD, recent.body.getBackground());
            var row = recent.body.getComponent(0);
            row.dispatchEvent(new MouseEvent(row, MouseEvent.MOUSE_ENTERED, 0, 0, 0, 0, 0, false));
            row.dispatchEvent(new MouseEvent(row, MouseEvent.MOUSE_EXITED, 0, 0, 0, 0, 0, false));
            assertEquals(Kit.CARD, row.getBackground());
            assertEquals(Fmt.signed(500), recent.total.getText());
            return null;
        });
    }

    @Test public void deleteUsesTheShortLabelAndCancelPreservesTheSavedCopy() throws Exception {
        onEdt(() -> {
            var engine = SavedSessionTest.window(); var source = engine.session();
            var id = EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 2 * MINUTE);
            var panel = SavedSessionTest.panel(engine);
            new GrindsController(panel).deleteHistorical(id);
            assertTrue(ShellProbe.sheetButtonTexts(panel.shell).contains("Delete"));
            assertFalse(ShellProbe.sheetButtonTexts(panel.shell).contains("Delete for good"));
            ShellProbe.pressSheet(panel.shell, "Cancel"); assertNotNull(engine.historySession(id));
            new GrindsController(panel).deleteHistorical(id); ShellProbe.pressSheet(panel.shell, "Delete");
            assertNull(engine.historySession(id)); assertEquals(1500, engine.metrics(2 * MINUTE).net);
            return null;
        });
    }

    @Test public void frozenClockControlsFitNarrowSheetsAndScaledRenders() throws Exception {
        onEdt(() -> {
            var panel = SavedSessionTest.panel(engine()); new GrindsController(panel).newSession();
            var checkbox = keep(panel);
            var output = java.nio.file.Path.of("build/clock-polish-research/previews");
            java.nio.file.Files.createDirectories(output);
            for (int width : new int[]{173, 185, 260}) {
                DirectHeaderControlsTest.size(panel.shell.sheet, width);
                assertTrue(checkbox.getFontMetrics(checkbox.getFont()).stringWidth(checkbox.getText()) + 26 <= checkbox.getWidth());
                assertTrue(checkbox.getY() + checkbox.getHeight() <= checkbox.getParent().getHeight());
                for (double scale : new double[]{1, 1.25, 1.5}) {
                    var image = new java.awt.image.BufferedImage((int) Math.ceil(width * scale),
                            (int) Math.ceil(panel.shell.sheet.getHeight() * scale), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    var graphics = image.createGraphics(); graphics.scale(scale, scale); panel.shell.sheet.paint(graphics); graphics.dispose();
                    javax.imageio.ImageIO.write(image, "png", output.resolve("clock-" + width + "-" + scale + ".png").toFile());
                }
            }
            return null;
        });
    }
}
