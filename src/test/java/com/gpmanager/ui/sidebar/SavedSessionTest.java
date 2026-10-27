package com.gpmanager;

import com.google.gson.Gson;
import java.util.function.LongSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.gpmanager.MilestoneTest.onEdt;
import static com.gpmanager.TargetFreshStartTest.*;
import static org.junit.Assert.*;

/** Optional saved Live sessions never become another owner of counted money. */
public class SavedSessionTest {
    LongSupplier clock;
    @Before public void setUp() { clock = SafeMath.clock; JsonCodec.bind(new Gson()); }
    @After public void tearDown() { SafeMath.clock = clock; }

    @Test public void freePlayLinkOffersAnOptionalSave() throws Exception { prompt(false); }
    @Test public void grindLinkOffersAnOptionalSave() throws Exception { prompt(true); }

    void prompt(boolean grind) throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            if (grind) engine.startCustom("Vorkath", 0L);
            coins(engine, 5L, 1_000L);
            engine.newLive(MINUTE);
            coins(engine, MINUTE + 5L, 500L);
            SafeMath.clock = () -> 2L * MINUTE;
            SidebarPanel panel = RateRestartTest.panel(engine);
            panel.active = true;
            panel.refreshNow();
            DirectHeaderControlsTest.click(panel.live.total);
            assertEquals("Save session?", panel.shell.heading);
            assertTrue(ShellProbe.sheetButtonTexts(panel.shell).contains("Save session"));
            assertTrue(ShellProbe.sheetButtonTexts(panel.shell).contains("Continue without saving"));
            ShellProbe.pressSheet(panel.shell, "Cancel");
            assertEquals(Long.valueOf(MINUTE), engine.session().sessionStartAt);
            assertTrue(engine.saved.isEmpty());
            DirectHeaderControlsTest.click(panel.live.total);
            ShellProbe.pressSheet(panel.shell, "Continue without saving");
            assertNull(engine.session().sessionStartAt);
            assertEquals(1_500L, live(engine, 2L * MINUTE).net);
            assertTrue(engine.history().isEmpty());
            assertTrue(engine.saved.isEmpty());
            return null;
        });
    }

    @Test public void saveShowsTheLiveWindowInGrindsAndReadOnlyLedgerWithoutCountingItAgain() throws Exception {
        onEdt(() -> {
            Engine engine = window();
            SidebarPanel panel = panel(engine);
            DirectHeaderControlsTest.click(panel.live.total);
            ShellProbe.pressSheet(panel.shell, "Save session");
            assertEquals(1, engine.saved.size());
            assertTrue(panel.shell.message.getText().contains("Session saved"));
            String id = engine.saved.get(0).id;
            assertNull(engine.session().sessionStartAt);
            assertEquals(1_500L, live(engine, 2L * MINUTE).net);
            assertEquals(1_500L, GrindsData.allTime(engine, 2L * MINUTE).net);
            GrindsData data = GrindsData.capture(engine, 2L * MINUTE, false, id);
            assertEquals(500L, data.detail.net);
            assertEquals(MINUTE, data.detail.activeMillis);
            assertEquals(30_000L, data.detail.rate);
            assertEquals(id, data.detail.session);
            new GrindsController(panel).openLedger(id);
            LedgerData ledger = LedgerData.capture(engine, 2L * MINUTE, panel.scope);
            assertTrue(ledger.readonly);
            assertEquals(500L, ledger.net);
            assertTrue(ledger.balanced);
            assertTrue(engine.history().isEmpty());
            return null;
        });
    }

    @Test public void laterBookingsCorrectionsAndWholeRunViewsCannotChangeTheSavedCopy() throws Exception {
        onEdt(() -> {
            Engine engine = window();
            coins(engine, MINUTE + 10L, -100L);
            Session source = engine.session();
            String cost = source.transactions().get(2).id();
            String id = EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 2L * MINUTE);
            Session saved = engine.historySession(id);
            assertEquals(400L, saved.metrics(0L).net);
            assertNotSame(source.transactions().get(1), saved.transactions().get(0));
            assertTrue(engine.correctTransaction(cost, Correction.IGNORE, "owner correction"));
            coins(engine, 2L * MINUTE + 5L, 600L);
            engine.showWholeRun();
            assertEquals(2_100L, engine.metrics(3L * MINUTE).net);
            assertEquals(400L, saved.metrics(3L * MINUTE).net);
            assertEquals(MINUTE, saved.elapsed(3L * MINUTE));
            LedgerData ledger = LedgerData.capture(engine, 3L * MINUTE,
                    new LedgerData.Entry(LedgerData.Scope.HISTORY, id, saved.name()));
            assertEquals(400L, ledger.net);
            assertTrue(ledger.balanced);
            assertEquals(2_100L, GrindsData.allTime(engine, 3L * MINUTE).net);
            return null;
        });
    }

    @Test public void savedEntriesRoundTripAndStayWithTheirProfile() throws Exception {
        onEdt(() -> {
            Engine engine = window();
            Session source = engine.session();
            String id = EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 2L * MINUTE);
            Gson gson = new Gson();
            SavedState disk = gson.fromJson(gson.toJson(engine.exportState()), SavedState.class);
            Engine loaded = engine();
            loaded.restoreProfile("saved-owner", disk, 3L * MINUTE);
            assertEquals(500L, loaded.historySession(id).metrics(0L).net);
            assertEquals(1_500L, GrindsData.allTime(loaded, 3L * MINUTE).net);
            assertTrue(LedgerData.capture(loaded, 3L * MINUTE,
                    new LedgerData.Entry(LedgerData.Scope.HISTORY, id, "Saved")).balanced);
            loaded.restoreProfile("another-owner", new SavedState(), 4L * MINUTE);
            assertTrue(loaded.saved.isEmpty());
            assertNull(loaded.historySession(id));
            loaded.restoreProfile("saved-owner", disk, 5L * MINUTE);
            assertEquals(500L, loaded.historySession(id).metrics(0L).net);
            return null;
        });
    }

    @Test public void restoredCopiesCleanNullRowsAndFlowsWithoutChangingValidReceipts() throws Exception {
        onEdt(() -> {
            Engine original = window();
            Session source = original.session();
            String id = EngineProbe.saveLiveSession(original, source, source.sessionStartAt, 2L * MINUTE);
            Gson gson = JsonCodec.gson();
            String canonical = gson.toJson(source.transactions());
            String receipt = gson.toJson(original.historySession(id).transactions().get(0));
            var json = gson.toJsonTree(original.exportState()).getAsJsonObject();
            var saved = json.getAsJsonArray("savedSessions").get(0).getAsJsonObject()
                    .getAsJsonObject("session");
            var rows = saved.getAsJsonArray("transactions");
            rows.add(com.google.gson.JsonNull.INSTANCE);
            rows.get(0).getAsJsonObject().getAsJsonArray("flows").add(com.google.gson.JsonNull.INSTANCE);
            Engine loaded = engine();
            loaded.restoreProfile("copy-cleanup", gson.fromJson(json, SavedState.class), 3L * MINUTE);
            Session copy = loaded.historySession(id);
            LedgerData ledger = LedgerData.capture(loaded, 3L * MINUTE,
                    new LedgerData.Entry(LedgerData.Scope.HISTORY, id, copy.name()));
            assertTrue(ledger.readonly);
            assertTrue(ledger.balanced);
            assertEquals(500L, ledger.net);
            assertEquals(1, copy.transactions().size());
            assertEquals(receipt, gson.toJson(copy.transactions().get(0)));
            assertEquals(canonical, gson.toJson(loaded.session().transactions()));
            assertEquals(1_500L, GrindsData.allTime(loaded, 3L * MINUTE).net);
            Engine reloaded = engine();
            reloaded.restoreProfile("copy-cleanup", loaded.exportState(), 4L * MINUTE);
            assertEquals(receipt, gson.toJson(reloaded.historySession(id).transactions().get(0)));
            assertEquals(500L, LedgerData.capture(reloaded, 4L * MINUTE,
                    new LedgerData.Entry(LedgerData.Scope.HISTORY, id, copy.name())).net);
            reloaded.restoreProfile("other-owner", new SavedState(), 5L * MINUTE);
            assertNull(reloaded.historySession(id));
            assertEquals(0L, GrindsData.allTime(reloaded, 5L * MINUTE).net);
            return null;
        });
    }

    @Test public void restoredCopiesWithMissingFlowsKeepTheirBookedAmounts() throws Exception {
        onEdt(() -> {
            Engine original = window();
            Session source = original.session();
            String id = EngineProbe.saveLiveSession(original, source, source.sessionStartAt, 2L * MINUTE);
            Gson gson = JsonCodec.gson();
            String canonical = gson.toJson(source.transactions());
            String receiptId = original.historySession(id).transactions().get(0).id();
            for (boolean explicitNull : new boolean[]{false, true}) {
                var json = gson.toJsonTree(original.exportState()).getAsJsonObject();
                var receipt = json.getAsJsonArray("savedSessions").get(0).getAsJsonObject()
                        .getAsJsonObject("session").getAsJsonArray("transactions").get(0).getAsJsonObject();
                if (explicitNull) receipt.add("flows", com.google.gson.JsonNull.INSTANCE);
                else receipt.remove("flows");
                Engine loaded = engine();
                loaded.restoreProfile("missing-copy-flows", gson.fromJson(json, SavedState.class), 3L * MINUTE);
                Session copy = loaded.historySession(id);
                assertEquals(500L, GrindsData.capture(loaded, 3L * MINUTE, false, id).detail.net);
                Transaction row = copy.transactions().get(0);
                assertEquals(receiptId, row.id());
                assertTrue(row.flows().isEmpty());
                assertEquals(500L, row.revenue());
                assertEquals(0L, row.costs());
                LedgerData ledger = LedgerData.capture(loaded, 3L * MINUTE,
                        new LedgerData.Entry(LedgerData.Scope.HISTORY, id, copy.name()));
                assertTrue(ledger.readonly);
                assertTrue(ledger.balanced);
                assertEquals(500L, ledger.net);
                assertEquals(canonical, gson.toJson(loaded.session().transactions()));
                assertEquals(1_500L, GrindsData.allTime(loaded, 3L * MINUTE).net);
            }
            return null;
        });
    }

    @Test public void deletingACopyKeepsCanonicalBookingsAndProfileResetClearsCopies() throws Exception {
        onEdt(() -> {
            Engine engine = window();
            Session source = engine.session();
            String id = EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 2L * MINUTE);
            assertTrue(engine.deleteHistory(id));
            assertNull(engine.historySession(id));
            assertEquals(1_500L, engine.metrics(2L * MINUTE).net);
            assertEquals(2, source.transactions().size());
            assertFalse(engine.deleteHistory(id));
            EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 2L * MINUTE);
            engine.resetData(3L * MINUTE);
            assertTrue(engine.saved.isEmpty());
            return null;
        });
    }

    @Test public void targetPauseAndRateRestartAreFrozenWithoutMutatingTheRunningTarget() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            engine.setTargets(10_000L, HOUR, MINUTE);
            coins(engine, MINUTE + 5L, 500L);
            engine.togglePause(2L * MINUTE);
            Session source = engine.session();
            LiveSnapshot before = live(engine, 3L * MINUTE);
            String id = EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 3L * MINUTE);
            assertEquals(MINUTE, engine.historySession(id).elapsed(10L * MINUTE));
            assertEquals(before.goal.current, live(engine, 3L * MINUTE).goal.current);
            assertEquals(before.goal.remaining, live(engine, 3L * MINUTE).goal.remaining);
            assertTrue(source.paused);
            engine.togglePause(3L * MINUTE);
            engine.restartRate(4L * MINUTE, true);
            String rateId = EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 5L * MINUTE);
            assertEquals(MINUTE, engine.historySession(rateId).elapsed(10L * MINUTE));
            assertEquals(30_000L, engine.historySession(rateId).metrics(0L).rate);
            assertEquals(500L, GrindsData.allTime(engine, 5L * MINUTE).net);
            return null;
        });
    }

    @Test public void stalePromptCannotSaveAnotherOwnerOrAnotherLiveWindow() throws Exception {
        onEdt(() -> {
            Engine engine = window();
            SidebarPanel panel = panel(engine);
            DirectHeaderControlsTest.click(panel.live.total);
            javax.swing.JButton old = ShellProbe.sheetButtons(panel.shell).stream()
                    .filter(button -> button.getText().equals("Save session")).findFirst().get();
            javax.swing.JButton continueOld = ShellProbe.sheetButtons(panel.shell).stream()
                    .filter(button -> button.getText().equals("Continue without saving")).findFirst().get();
            engine.newLive(3L * MINUTE);
            old.doClick();
            continueOld.doClick();
            assertTrue(engine.saved.isEmpty());
            assertEquals(Long.valueOf(3L * MINUTE), engine.session().sessionStartAt);
            panel.refreshNow();
            DirectHeaderControlsTest.click(panel.live.total);
            javax.swing.JButton another = ShellProbe.sheetButtons(panel.shell).stream()
                    .filter(button -> button.getText().equals("Save session")).findFirst().get();
            engine.restoreProfile("another-owner", new SavedState(), 4L * MINUTE);
            engine.ensureSession(4L * MINUTE);
            another.doClick();
            assertTrue(engine.saved.isEmpty());
            assertEquals(0L, engine.metrics(4L * MINUTE).net);
            return null;
        });
    }

    @Test public void repeatedCopiesStayBoundedAndDoNotAffectGrindBestsOrAllTime() throws Exception {
        onEdt(() -> {
            Engine engine = new Engine(deltas -> java.util.Collections.emptyList(), new TransactionClassifier(),
                    new GpManagerConfig() { public int maxHistorySessions() { return 1; } });
            engine.startCustom("Vorkath", 0L);
            coins(engine, 5L, 500L);
            Session source = engine.session();
            String first = EngineProbe.saveLiveSession(engine, source, null, MINUTE);
            coins(engine, MINUTE + 5L, 200L);
            String second = EngineProbe.saveLiveSession(engine, source, null, 2L * MINUTE);
            assertNull(engine.historySession(first));
            assertEquals(700L, engine.historySession(second).metrics(0L).net);
            assertEquals(700L, GrindsData.allTime(engine, 2L * MINUTE).net);
            assertTrue(engine.history().isEmpty());
            assertNull(GrindsData.allTime(engine, 2L * MINUTE).rateName);
            return null;
        });
    }

    @Test public void legacyAndMalformedOptionalListsCannotBecomeCanonicalHistory() throws Exception {
        onEdt(() -> {
            Engine engine = engine();
            SavedState state = engine.exportState();
            assertNull(state.savedSessions);
            state.savedSessions = java.util.Arrays.asList(null,
                    new SavedState.SavedSession("not-a-snapshot", engine.session(), java.util.List.of()));
            engine.restoreProfile("bad-optional-copy", state, MINUTE);
            assertTrue(engine.saved.isEmpty());
            assertTrue(engine.history().isEmpty());
            return null;
        });
    }

    @Test public void marketSnapshotsKeepOnlyTheirOwnSettlementsAndFreezeTheRecordedContext() throws Exception {
        onEdt(() -> {
            var markets = new java.util.ArrayList<MarketRows.Row>();
            Engine engine = new Engine(deltas -> java.util.List.of(), new TransactionClassifier(),
                    new GpManagerConfig() {}) {
                @Override synchronized java.util.List<MarketRows.Row> marketRows() { return markets; }
            };
            engine.ensureSession(0L);
            Session source = engine.session();
            Transaction earlier = trade(5L);
            source.addTransaction(earlier, 2000, false);
            engine.newLive(MINUTE);
            Transaction selected = trade(MINUTE + 5L);
            source.addTransaction(selected, 2000, false);
            MarketRows.Row row = new Gson().fromJson("{}", MarketRows.Row.class);
            row.scopeSessionId = source.id();
            row.presentationId = "offer";
            row.itemId = 536;
            row.itemName = "Dragon bones";
            row.side = GeOffer.Side.SELL;
            row.coverage = MarketRows.Coverage.FULLY_KNOWN;
            row.lifecycle = MarketRows.Lifecycle.REALIZED;
            row.realizedResultCorrectionAware = true;
            row.realizedResultGp = 200L;
            row.observedSettlementGp = 400L;
            row.trackedBasisConsumedGp = 200L;
            row.receivedEachGp = 200L;
            row.listedDuring = "";
            row.settledQty = 2L;
            row.settlementId = selected.id();
            row.earlierSettlements = " " + earlier.id();
            markets.add(row);
            String id = EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 2L * MINUTE);
            SavedState.SavedSession saved = engine.savedSession(id);
            assertNotSame(row, saved.market.get(0));
            assertEquals("offer context retains its captured lifetime result", 200L,
                    saved.market.get(0).realizedResultGp);
            assertEquals(2L, saved.market.get(0).settledQty);
            assertEquals(Long.valueOf(100L), saved.market.get(0).savedNet);
            assertEquals(1L, saved.market.get(0).savedQuantity);
            assertEquals(400L, saved.market.get(0).observedSettlementGp);
            assertTrue(MarketText.humanLines(saved.market.get(0), false).contains("Saved session|+100 gp"));
            assertTrue(MarketText.humanLines(saved.market.get(0), false).contains("Offer context|At save time"));
            assertTrue(MarketText.humanLines(saved.market.get(0), false).contains("Received|400 gp"));
            assertEquals(2L, row.settledQty);
            assertEquals(200L, row.realizedResultGp);
            assertEquals("", saved.market.get(0).earlierSettlements);
            row.realizedResultGp = 999L;
            row.itemName = "later live context";
            LedgerData ledger = LedgerData.capture(engine, 3L * MINUTE,
                    new LedgerData.Entry(LedgerData.Scope.HISTORY, id, "Saved"));
            assertEquals(100L, ledger.net);
            assertTrue(ledger.balanced);
            assertEquals("Dragon bones", saved.market.get(0).itemName);
            return null;
        });
    }

    @Test public void saveChoicesStayReadableOnNarrowSidebars() throws Exception {
        onEdt(() -> {
            SidebarPanel panel = panel(window());
            DirectHeaderControlsTest.click(panel.live.total);
            for (int width : new int[]{213, 225, 300}) {
                DirectHeaderControlsTest.size(panel.shell.sheet, width);
                for (javax.swing.JButton button : ShellProbe.sheetButtons(panel.shell)) {
                    java.awt.Rectangle bounds = DirectHeaderControlsTest.bounds(button, panel.shell.sheet);
                    assertTrue(bounds.x >= 0 && bounds.getMaxX() <= width);
                    assertTrue(bounds.y >= 0 && bounds.getMaxY() <= panel.shell.sheet.getHeight());
                    assertTrue(button.getFontMetrics(button.getFont()).stringWidth(button.getText())
                            <= button.getWidth() - button.getInsets().left - button.getInsets().right);
                }
            }
            return null;
        });
    }

    @Test public void normalAndQuickPersistenceSnapshotsKeepSavedBookingsAlongsideCanonicalHistory() throws Exception {
        onEdt(() -> {
            Engine engine = window();
            Session source = engine.session();
            String id = EngineProbe.saveLiveSession(engine, source, source.sessionStartAt, 2L * MINUTE);
            engine.startFreePlayOver(3L * MINUTE);
            LedgerData.capture(engine, 3L * MINUTE, new LedgerData.Entry(LedgerData.Scope.HISTORY, id, "Saved"));
            Gson gson = new Gson();
            SessionRepository repository = new SessionRepository(gson);
            Persistence persistence = new Persistence(null, null, repository, null, engine);
            for (boolean quick : new boolean[]{false, true, true, false}) {
                SavedState saved = gson.fromJson(repository.gson.toJson(persistence.snapshotState(null)), SavedState.class);
                assertEquals(1, saved.savedSessions.size());
                assertEquals(id, saved.savedSessions.get(0).id);
                assertEquals(500L, saved.savedSessions.get(0).session.metrics(0L).net);
                Engine loaded = engine();
                loaded.restoreProfile("writer-owner", saved, 4L * MINUTE);
                assertEquals(500L, loaded.historySession(id).metrics(0L).net);
                assertEquals(1_500L, GrindsData.allTime(loaded, 4L * MINUTE).net);
            }
            return null;
        });
    }

    static Transaction trade(long at) {
        return new Transaction(at, TransactionType.TRADE, Context.MARKET, "Sell", "General", true,
                java.util.List.of(new Flow(536, "Dragon bones", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE),
                        new Flow(995, "Coins", 200L, 1, 200L, PriceSource.FACE_VALUE)),
                Confidence.CONFIRMED, "", null);
    }

    static Engine window() {
        Engine engine = engine();
        coins(engine, 5L, 1_000L);
        engine.newLive(MINUTE);
        coins(engine, MINUTE + 5L, 500L);
        return engine;
    }

    static SidebarPanel panel(Engine engine) {
        SafeMath.clock = () -> 2L * MINUTE;
        SidebarPanel panel = RateRestartTest.panel(engine);
        panel.active = true;
        panel.refreshNow();
        return panel;
    }
}
