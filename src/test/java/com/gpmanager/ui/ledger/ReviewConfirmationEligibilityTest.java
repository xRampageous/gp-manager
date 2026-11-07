package com.gpmanager;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.runelite.api.gameval.ItemID.LOGS;
import static net.runelite.api.gameval.ItemID.OAK_LOGS;

/** Review confirmations and application must share eligibility, atomicity and exact Net. */
public class ReviewConfirmationEligibilityTest {
    private static final long NOW = 1_000_000_000_000L;

    private static Engine engine() {
        JsonCodec.bind(new com.google.gson.Gson());
        var engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        engine.startCustom("Review confirmation", NOW);
        engine.setBaseline(new ContainerSnapshot(Map.of(995, 1_000_000L)));
        return engine;
    }

    private static Transaction book(Engine engine, long at, int item, long quantity, boolean counted, boolean review) {
        return book(engine, at, item, quantity, 100, counted, review);
    }

    private static Transaction book(Engine engine, long at, int item, long quantity, int unit, boolean counted, boolean review) {
        var row = new Transaction(at, review ? TransactionType.UNCERTAIN
                        : quantity > 0 ? TransactionType.GAIN : TransactionType.CONSUMPTION,
                Context.GENERIC, "", "Review confirmation", counted,
                List.of(new Flow(item, "Item " + item, quantity, unit, quantity * unit, PriceSource.GRAND_EXCHANGE)),
                review ? Confidence.UNCERTAIN : Confidence.CONFIRMED, "", null);
        engine.session().addTransaction(row, 500, false);
        return row;
    }

    private static Transaction delayed(Engine engine) {
        book(engine, NOW - 60_000, LOGS, 10, true, false);
        var review = book(engine, NOW - 30_000, LOGS, -4, false, true);
        book(engine, NOW - 10_000, LOGS, 5, 200, true, false);
        return review;
    }

    private static void refused(Engine engine, Decision decision) {
        String saved = JsonCodec.gson().toJson(engine.session());
        long revision = engine.revision(), net = engine.metrics(NOW).net;
        long quantity = EngineProbe.knownCoverageQty(engine, LOGS);
        long basis = EngineProbe.knownCoverageBasisGp(engine, LOGS);
        var preview = engine.previewDecision(decision, row -> true, NOW);
        assertEquals("one blocked row must refuse the complete selection", 0, preview.rowCount());
        assertEquals("a refused selection cannot promise a Net change", 0, preview.netDelta);
        assertEquals(0, engine.applyDecision(preview));
        assertEquals(saved, JsonCodec.gson().toJson(engine.session()));
        assertEquals(revision, engine.revision());
        assertEquals(net, engine.metrics(NOW).net);
        assertEquals(quantity, EngineProbe.knownCoverageQty(engine, LOGS));
        assertEquals(basis, EngineProbe.knownCoverageBasisGp(engine, LOGS));
    }

    @Test public void delayedCostHasNoApplicableConfirmation() {
        var engine = engine();
        delayed(engine);
        refused(engine, Decision.COST);
    }

    @Test public void delayedGainHasNoApplicableConfirmation() {
        var engine = engine();
        delayed(engine);
        refused(engine, Decision.GAIN);
    }

    private static void mixed(boolean eligibleFirst) {
        var engine = engine();
        Transaction eligible = eligibleFirst ? book(engine, NOW - 90_000, OAK_LOGS, 5, false, true) : null;
        var blocked = book(engine, NOW - 60_000, LOGS, 10, true, true);
        book(engine, NOW - 30_000, LOGS, -4, true, false);
        if (!eligibleFirst) eligible = book(engine, NOW - 10_000, OAK_LOGS, 5, false, true);
        assertTrue(engine.reviewNets(eligible.id()).containsKey(Decision.COST));
        assertFalse(engine.reviewNets(blocked.id()).containsKey(Decision.COST));
        refused(engine, Decision.COST);
        assertEquals(Correction.AUTO, eligible.correction());
        assertEquals(Correction.AUTO, blocked.correction());
    }

    @Test public void blockedFirstRowRefusesTheWholeBatch() { mixed(false); }
    @Test public void blockedLastRowRefusesTheWholeBatch() { mixed(true); }

    @Test public void eligibleBatchMatchesCanonicalNetAndKeepsOneUndo() {
        var engine = engine();
        var first = book(engine, NOW - 60_000, LOGS, 5, false, true);
        var second = book(engine, NOW - 30_000, OAK_LOGS, 3, false, true);
        String saved = JsonCodec.gson().toJson(engine.session());
        long revision = engine.revision();
        var preview = engine.previewDecision(Decision.GAIN, row -> true, NOW);
        assertEquals(saved, JsonCodec.gson().toJson(engine.session()));
        assertEquals(revision, engine.revision());
        assertEquals(List.of(first.id(), second.id()), preview.transactionIds);
        assertEquals(800, preview.netDelta);
        int history = engine.session().correctionHistory.size();
        assertEquals(2, engine.applyDecision(preview));
        assertEquals(800, engine.metrics(NOW).net);
        assertEquals(history + 1, engine.session().correctionHistory.size());
        assertTrue(engine.undoCorrection());
        assertEquals(0, engine.metrics(NOW).net);
        assertEquals(Correction.AUTO, first.correction());
        assertEquals(Correction.AUTO, second.correction());
    }

    private static SidebarPanel panel(Engine engine, AtomicInteger saves) throws Exception {
        var storage = new Persistence(null, null, null, null, engine) {
            @Override boolean scheduleSave() { saves.incrementAndGet(); return true; }
            @Override SaveStatus saveStatus() { return SaveStatus.neverSaved(); }
        };
        return SwingTestSupport.onEdt(() -> {
            var panel = new SidebarPanel(engine, new GpManagerConfig() {}, null, null, storage);
            panel.mutationOwner = Runnable::run;
            return panel;
        });
    }

    private static void open(SidebarPanel panel, Transaction row, Decision decision) throws Exception {
        SwingTestSupport.onEdt(() -> {
            new LedgerController(panel).decide(row.id(), decision);
            return null;
        });
    }

    private static void press(SidebarPanel panel, String button) throws Exception {
        SwingTestSupport.onEdt(() -> { ShellProbe.pressSheet(panel.shell, button); return null; });
    }

    private static void notApplied(SidebarPanel panel) throws Exception {
        assertTrue(SwingTestSupport.onEdt(() -> ShellProbe.noticeText(panel.shell)).contains("Not applied"));
    }

    @Test public void blockedDecisionReportsRefusalWithoutOfferingApply() throws Exception {
        var engine = engine();
        var review = delayed(engine);
        var saves = new AtomicInteger();
        var panel = panel(engine, saves);
        String saved = JsonCodec.gson().toJson(engine.session());
        open(panel, review, Decision.COST);
        assertFalse(SwingTestSupport.onEdt(() -> ShellProbe.sheetButtonTexts(panel.shell)).contains("Apply"));
        notApplied(panel);
        assertEquals(saved, JsonCodec.gson().toJson(engine.session()));
        assertEquals(0, saves.get());
    }

    @Test public void aBasisRefusalAfterConfirmationReportsZeroApplied() throws Exception {
        var engine = engine();
        var review = book(engine, NOW - 30_000, LOGS, 10, true, true);
        var panel = panel(engine, new AtomicInteger());
        open(panel, review, Decision.COST);
        assertTrue(SwingTestSupport.onEdt(() -> ShellProbe.sheetButtonTexts(panel.shell)).contains("Apply"));
        long revision = engine.revision();
        engine.session().ownedValueObserver.reserve(LOGS, 1);
        String saved = JsonCodec.gson().toJson(engine.session());
        assertEquals("the basis guard, rather than the revision fence, refuses this edit", revision, engine.revision());
        press(panel, "Apply");
        notApplied(panel);
        assertEquals(saved, JsonCodec.gson().toJson(engine.session()));
        assertEquals(Correction.AUTO, review.correction());
        assertEquals(9, EngineProbe.knownCoverageQty(engine, LOGS));
    }

    @Test public void anEligibleConfirmationAppliesAndSavesOnce() throws Exception {
        var engine = engine();
        var review = book(engine, NOW - 30_000, LOGS, 5, false, true);
        var saves = new AtomicInteger();
        var panel = panel(engine, saves);
        open(panel, review, Decision.COST);
        press(panel, "Apply");
        assertEquals(-500, engine.metrics(NOW).net);
        assertEquals(Correction.COST, review.correction());
        assertEquals(1, saves.get());
        assertTrue(engine.undoCorrection());
        assertEquals(0, engine.metrics(NOW).net);
    }

    @Test public void cancelKeepsTheLedgerAndDoesNotSave() throws Exception {
        var engine = engine();
        var review = book(engine, NOW - 30_000, LOGS, 5, false, true);
        var saves = new AtomicInteger();
        var panel = panel(engine, saves);
        String saved = JsonCodec.gson().toJson(engine.session());
        open(panel, review, Decision.COST);
        press(panel, "Cancel");
        assertEquals(saved, JsonCodec.gson().toJson(engine.session()));
        assertEquals(0, saves.get());
    }

    @Test public void aStaleConfirmationReportsRefusalAndKeepsTheNewLedger() throws Exception {
        var engine = engine();
        var review = book(engine, NOW - 30_000, LOGS, 5, false, true);
        var panel = panel(engine, new AtomicInteger());
        open(panel, review, Decision.COST);
        book(engine, NOW - 10_000, OAK_LOGS, 2, true, false);
        String saved = JsonCodec.gson().toJson(engine.session());
        press(panel, "Apply");
        notApplied(panel);
        assertEquals(saved, JsonCodec.gson().toJson(engine.session()));
        assertEquals(Correction.AUTO, review.correction());
    }
}
