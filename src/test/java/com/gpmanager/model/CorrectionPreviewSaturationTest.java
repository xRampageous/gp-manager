package com.gpmanager;

import java.util.List;
import java.util.function.Supplier;
import org.junit.Test;
import static org.junit.Assert.*;

/** Confirmation totals must equal the canonical totals after applying the same change. */
public class CorrectionPreviewSaturationTest {
    private static final long NOW = ReviewFixesTest.T0 + 1000;
    private static final long FOUR_E18 = 4_000_000_000_000_000_000L;

    private static Transaction book(Engine engine, int item, long quantity, long value, boolean review) {
        var row = Tx.of(NOW, review ? TransactionType.UNCERTAIN
                        : quantity < 0 ? TransactionType.CONSUMPTION : TransactionType.GAIN,
                Context.GENERIC, "", !review, List.of(new Flow(item, "Item " + item, quantity, 100, value)));
        row.confidence = review ? Confidence.UNCERTAIN : Confidence.CONFIRMED;
        engine.session().addTransaction(row, 2000, false);
        return row;
    }

    private static <T> T readOnly(Engine engine, Supplier<T> request) {
        String before = JsonCodec.gson().toJson(engine.session());
        long revision = engine.revision();
        T preview = request.get();
        assertEquals("preview must not change saved receipts, history or Undo", before,
                JsonCodec.gson().toJson(engine.session()));
        assertEquals("preview must not advance the revision", revision, engine.revision());
        return preview;
    }

    private static void remove(Engine engine, Transaction target, long after, long change) {
        var preview = readOnly(engine, () -> LedgerData.preview(engine, target.id(), Correction.IGNORE, NOW));
        assertNotNull(preview);
        assertEquals(after, preview.afterNet);
        assertEquals(change, preview.change);
        long before = engine.metrics(NOW).net;
        assertTrue(engine.correctEntries(List.of(target.id()), Correction.IGNORE));
        assertEquals(after, engine.metrics(NOW).net);
        assertTrue(engine.undoCorrection());
        assertEquals("Undo restores the original saturated total", before, engine.metrics(NOW).net);
    }

    @Test public void singleRemovalUsesTheRealAfterTotalWithSaturatedGains() {
        var engine = ReviewFixesTest.engine();
        book(engine, 536, 2_000_000_000L, FOUR_E18, false);
        book(engine, 537, 2_000_000_000L, FOUR_E18, false);
        var target = book(engine, 538, 2_000_000_000L, FOUR_E18, false);
        remove(engine, target, 8_000_000_000_000_000_000L, -1_223_372_036_854_775_807L);
    }

    @Test public void singleRemovalUsesTheRealAfterTotalWithSaturatedCosts() {
        var engine = ReviewFixesTest.engine();
        book(engine, 536, -2_000_000_000L, -FOUR_E18, false);
        book(engine, 537, -2_000_000_000L, -FOUR_E18, false);
        var target = book(engine, 538, -2_000_000_000L, -FOUR_E18, false);
        remove(engine, target, -8_000_000_000_000_000_000L, 1_223_372_036_854_775_807L);
    }

    @Test public void singleRemovalFoldsIncomeAndCostsSeparatelyBeforeTakingNet() {
        var engine = ReviewFixesTest.engine();
        book(engine, 536, 1, Long.MAX_VALUE - 100, false);
        var target = book(engine, 537, 3, 300, false);
        book(engine, 995, -1, -50, false);
        remove(engine, target, Long.MAX_VALUE - 150, -100);
    }

    @Test public void removalIncludesCompactedContributions() {
        var engine = ReviewFixesTest.engine();
        book(engine, 536, 1, Long.MAX_VALUE - 100, false);
        engine.session().evict(1);
        var target = book(engine, 537, 3, 300, false);
        remove(engine, target, Long.MAX_VALUE - 100, -100);
    }

    private static void split(boolean compacted) {
        var engine = ReviewFixesTest.engine();
        book(engine, 536, 1, Long.MAX_VALUE - 100, false);
        if (compacted) engine.session().evict(1);
        var target = book(engine, 537, 3, 300, false);
        var preview = readOnly(engine, () -> LedgerData.splitPreview(engine, target.id(), 537, 1, NOW));
        assertEquals(Long.MAX_VALUE, preview.afterNet);
        assertEquals("reducing one receipt need not reduce saturated Net", 0, preview.change);
        assertTrue(engine.splitItem(target.id(), 537, 1, "Saturation regression"));
        assertEquals(preview.afterNet, engine.metrics(NOW).net);
        assertEquals(1, target.quantity(537, true));
        assertTrue(engine.undoCorrection());
        assertEquals(3, target.quantity(537, true));
        assertEquals(Long.MAX_VALUE, engine.metrics(NOW).net);
    }

    @Test public void splitUsesTheRealAfterTotal() { split(false); }
    @Test public void splitIncludesCompactedContributions() { split(true); }

    private static void decide(boolean compacted, boolean cost) {
        var engine = ReviewFixesTest.engine();
        book(engine, 536, cost ? -1 : 1, cost ? -Long.MAX_VALUE + 100 : Long.MAX_VALUE - 100, false);
        if (compacted) engine.session().evict(1);
        var target = book(engine, 537, 1, 200, true);
        var unselected = book(engine, 538, 1, 300, true);
        var preview = readOnly(engine, () -> engine.previewDecision(cost ? Decision.COST : Decision.GAIN,
                row -> row.transaction.equals(target.id()), NOW));
        assertEquals(List.of(target.id()), preview.transactionIds);
        assertEquals(cost ? -100 : 100, preview.netDelta);
        int undoUnits = engine.session().correctionHistory.size();
        assertEquals(1, engine.applyDecision(preview));
        assertEquals(cost ? -Long.MAX_VALUE : Long.MAX_VALUE, engine.metrics(NOW).net);
        assertEquals(Correction.AUTO, unselected.correction());
        assertEquals(undoUnits + 1, engine.session().correctionHistory.size());
        assertTrue(engine.undoCorrection());
        assertEquals(Correction.AUTO, target.correction());
        assertEquals(cost ? -Long.MAX_VALUE + 100 : Long.MAX_VALUE - 100, engine.metrics(NOW).net);
    }

    @Test public void decideAllUsesTheRealGainChangeAndKeepsScopeAndUndo() { decide(false, false); }
    @Test public void decideAllUsesTheRealCostChange() { decide(false, true); }
    @Test public void decideAllIncludesCompactedContributions() { decide(true, false); }

    @Test public void aPreviewChangeAcrossTheSignedRangeSaturatesInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, new LedgerData.CorrectionPreview(-Long.MAX_VALUE, Long.MAX_VALUE, 0).change);
        assertEquals(Long.MIN_VALUE, new LedgerData.CorrectionPreview(Long.MAX_VALUE, -Long.MAX_VALUE, 0).change);
    }

    @Test public void missingRowsAndEmptyDecisionScopesKeepNetAndRevision() {
        var engine = ReviewFixesTest.engine();
        book(engine, 536, 1, 300, false);
        var missing = readOnly(engine, () -> LedgerData.preview(engine, "missing", Correction.IGNORE, NOW));
        assertEquals(300, missing.afterNet);
        assertEquals(0, missing.change);
        var split = readOnly(engine, () -> LedgerData.splitPreview(engine, "missing", 536, 1, NOW));
        assertEquals(300, split.afterNet);
        var empty = readOnly(engine, () -> engine.previewDecision(Decision.GAIN, row -> false, NOW));
        assertEquals(0, empty.rowCount());
        assertEquals(0, empty.netDelta);
    }
}
