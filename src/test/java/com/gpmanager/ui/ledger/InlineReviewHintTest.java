package com.gpmanager;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Inline forecasts must match the canonical total after the exact selected decision. */
public class InlineReviewHintTest {
    private static final long NOW = ReviewFixesTest.T0 + 1000;

    private static Transaction book(Engine engine, TransactionType type, boolean counted, Flow... flows) {
        var row = Tx.of(NOW, type, Context.GENERIC, "", counted, List.of(flows));
        row.confidence = type == TransactionType.UNCERTAIN ? Confidence.UNCERTAIN : Confidence.CONFIRMED;
        engine.session().addTransaction(row, 2000, false);
        return row;
    }

    private static void matchesApplied(Engine engine, Transaction target, Correction correction, long expected) {
        var untouched = book(engine, TransactionType.UNCERTAIN, false, new Flow(4, "Other review", 1, 400, 400));
        String saved = JsonCodec.gson().toJson(engine.session());
        long revision = engine.revision();
        var row = Engine.reviewRow(engine.session(), target, NOW);
        var nets = engine.reviewNets(target.id());
        String hint = LedgerPage.netPreview(row, nets);
        assertEquals("reading a hint must not edit receipts, quotes, history or Undo", saved,
                JsonCodec.gson().toJson(engine.session()));
        assertEquals("reading a hint must not advance the revision", revision, engine.revision());
        assertTrue("the supported selected decision applies", engine.correctEntries(List.of(target.id()), correction));
        assertEquals("canonical applied Net", expected, engine.metrics(NOW).net);
        assertEquals("the hint keeps the exact total before compact formatting", expected,
                nets.get(correction == Correction.REVENUE ? Decision.GAIN : Decision.COST).longValue());
        assertEquals("the other Review remains undecided", Correction.AUTO, untouched.correction());
        String prefix = correction == Correction.REVENUE ? "If counted as a gain: Net " : " · as a cost: Net ";
        assertTrue("hint must show applied " + correction + " Net " + expected + ": " + hint,
                hint.contains(prefix + Fmt.signed(expected)));
    }

    private static void mixed(boolean counted, Correction decision, boolean balanced) {
        var engine = ReviewFixesTest.engine();
        book(engine, TransactionType.GAIN, true, new Flow(1, "Prior gain", 1, 500, 500));
        var target = book(engine, TransactionType.UNCERTAIN, counted,
                new Flow(2, "Gain", 1, balanced ? 100 : 300, balanced ? 100 : 300),
                new Flow(3, "Cost", -1, 100, -100));
        long gross = balanced ? 200 : 400;
        matchesApplied(engine, target, decision, decision == Correction.REVENUE ? 500 + gross : 500 - gross);
    }

    @Test public void mixedUncountedGainUsesGross() { mixed(false, Correction.REVENUE, false); }
    @Test public void mixedUncountedCostUsesGross() { mixed(false, Correction.COST, false); }
    @Test public void mixedCountedGainReplacesItsContribution() { mixed(true, Correction.REVENUE, false); }
    @Test public void mixedCountedCostReplacesItsContribution() { mixed(true, Correction.COST, false); }
    @Test public void netZeroMixedGainStillHasAPreview() { mixed(false, Correction.REVENUE, true); }
    @Test public void netZeroMixedCostStillHasAPreview() { mixed(false, Correction.COST, true); }

    @Test public void gainSaturatesInsteadOfWrapping() {
        var engine = ReviewFixesTest.engine();
        book(engine, TransactionType.GAIN, true, new Flow(1, "Prior gain", 1, Long.MAX_VALUE - 100, Long.MAX_VALUE - 100));
        var target = book(engine, TransactionType.UNCERTAIN, false, new Flow(2, "Gain", 1, 200, 200));
        matchesApplied(engine, target, Correction.REVENUE, Long.MAX_VALUE);
    }

    @Test public void costSaturatesInsteadOfWrapping() {
        var engine = ReviewFixesTest.engine();
        book(engine, TransactionType.CONSUMPTION, true,
                new Flow(1, "Prior cost", -1, Long.MAX_VALUE - 100, -Long.MAX_VALUE + 100));
        var target = book(engine, TransactionType.UNCERTAIN, false, new Flow(2, "Gain", 1, 200, 200));
        matchesApplied(engine, target, Correction.COST, -Long.MAX_VALUE);
    }

    @Test public void saturatedIncomeBesideACostUsesSeparateCanonicalTotals() {
        var engine = ReviewFixesTest.engine();
        book(engine, TransactionType.GAIN, true, new Flow(1, "Prior gain", 1, Long.MAX_VALUE - 100, Long.MAX_VALUE - 100));
        book(engine, TransactionType.CONSUMPTION, true, new Flow(3, "Prior cost", -1, 50, -50));
        var target = book(engine, TransactionType.UNCERTAIN, false, new Flow(2, "Gain", 1, 200, 200));
        matchesApplied(engine, target, Correction.REVENUE, Long.MAX_VALUE - 50);
    }

    @Test public void compactedMoneyRemainsInTheHint() {
        var engine = ReviewFixesTest.engine();
        book(engine, TransactionType.GAIN, true, new Flow(1, "Prior gain", 1, Long.MAX_VALUE - 100, Long.MAX_VALUE - 100));
        engine.session().evict(1);
        var target = book(engine, TransactionType.UNCERTAIN, false, new Flow(2, "Gain", 1, 200, 200));
        matchesApplied(engine, target, Correction.REVENUE, Long.MAX_VALUE);
    }

    @Test public void missingAndAlreadyDecidedRowsHaveNoHint() {
        var engine = ReviewFixesTest.engine();
        assertTrue(engine.reviewNets("missing").isEmpty());
        var target = book(engine, TransactionType.UNCERTAIN, false, new Flow(2, "Gain", 1, 200, 200));
        assertTrue(engine.correctEntries(List.of(target.id()), Correction.IGNORE));
        assertTrue(engine.reviewNets(target.id()).isEmpty());
    }

    @Test public void unpricedRowsHaveNoForecast() {
        var engine = ReviewFixesTest.engine();
        var target = book(engine, TransactionType.UNCERTAIN, false, new Flow(2, "Unknown price", 1, 0, 0));
        var row = Engine.reviewRow(engine.session(), target, NOW);
        assertTrue(engine.reviewNets(target.id()).isEmpty());
        assertEquals("", LedgerPage.netPreview(row, engine.reviewNets(target.id())));
    }

    @Test public void anOldRunCannotForecastAgainstTheNewRun() {
        var engine = ReviewFixesTest.engine();
        var target = book(engine, TransactionType.UNCERTAIN, false, new Flow(2, "Gain", 1, 200, 200));
        assertFalse(engine.reviewNets(target.id()).isEmpty());
        engine.startCustom("New run", NOW + 1);
        assertTrue(engine.reviewNets(target.id()).isEmpty());
    }

    @Test public void onlyAllowedChoicesRenderAndAnExactZeroStillRenders() {
        var row = new ReviewRow("id", "run", NOW, 0, List.of(), 200, "why",
                java.util.EnumSet.of(Decision.COST));
        assertEquals("If counted as a cost: Net " + Fmt.signed(0), LedgerPage.netPreview(row,
                java.util.Map.of(Decision.GAIN, 400L, Decision.COST, 0L)));
    }

    @Test public void theSelectedPanelUsesTheControllersCanonicalQuery() throws Exception {
        var engine = ReviewFixesTest.engine();
        book(engine, TransactionType.GAIN, true, new Flow(1, "Prior gain", 1, 500, 500));
        var target = book(engine, TransactionType.UNCERTAIN, false,
                new Flow(2, "Gain", 1, 300, 300), new Flow(3, "Cost", -1, 100, -100));
        var storage = new Persistence(null, null, null, null, engine) {
            @Override SaveStatus saveStatus() { return SaveStatus.neverSaved(); }
        };
        var panel = SwingTestSupport.onEdt(() -> new SidebarPanel(engine, new GpManagerConfig() {},
                null, null, storage));
        var row = Engine.reviewRow(engine.session(), target, NOW);
        String saved = JsonCodec.gson().toJson(engine.session());
        long revision = engine.revision();
        String hint = SwingTestSupport.onEdt(() -> {
            var page = new LedgerPage(new LedgerController(panel), id -> null);
            var detail = page.decisionPanel(row);
            return ((javax.swing.JLabel) detail.getComponent(1)).getText();
        });
        assertTrue(hint, hint.contains("Net +900"));
        assertTrue(hint, hint.contains("Net +100"));
        assertEquals(saved, JsonCodec.gson().toJson(engine.session()));
        assertEquals(revision, engine.revision());
    }
}
