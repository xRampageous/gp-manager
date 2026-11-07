package com.gpmanager;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.runelite.api.gameval.ItemID.LOGS;

/** Only decisions accepted by the existing basis guard may have inline forecasts. */
public class ReviewHintEligibilityTest {
    private static final long NOW = 1_000_000_000_000L;

    private static Engine engine() {
        JsonCodec.bind(new com.google.gson.Gson());
        var engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        engine.startCustom("Review eligibility", NOW);
        engine.setBaseline(new ContainerSnapshot(Map.of(995, 1_000_000L)));
        return engine;
    }

    private static Transaction book(Engine engine, long at, long quantity, int unit, boolean counted, boolean review) {
        var row = new Transaction(at, review ? TransactionType.UNCERTAIN
                        : quantity > 0 ? TransactionType.GAIN : TransactionType.CONSUMPTION,
                Context.GENERIC, "", "Review eligibility", counted,
                List.of(new Flow(LOGS, "Logs", quantity, unit, quantity * unit, PriceSource.GRAND_EXCHANGE)),
                review ? Confidence.UNCERTAIN : Confidence.CONFIRMED, "", null);
        engine.session().addTransaction(row, 500, false);
        return row;
    }

    private static Map<Decision, Long> readOnly(Engine engine, Transaction review) {
        String saved = JsonCodec.gson().toJson(engine.session());
        long revision = engine.revision();
        long quantity = EngineProbe.knownCoverageQty(engine, LOGS);
        long basis = EngineProbe.knownCoverageBasisGp(engine, LOGS);
        var nets = engine.reviewNets(review.id());
        assertEquals("eligibility reads must preserve receipts, history and Undo", saved,
                JsonCodec.gson().toJson(engine.session()));
        assertEquals(revision, engine.revision());
        assertEquals(quantity, EngineProbe.knownCoverageQty(engine, LOGS));
        assertEquals(basis, EngineProbe.knownCoverageBasisGp(engine, LOGS));
        return nets;
    }

    private static Map<Decision, Long> refused(Engine engine, Transaction review, Decision decision) {
        String saved = JsonCodec.gson().toJson(engine.session());
        long revision = engine.revision();
        var nets = readOnly(engine, review);
        assertNull(LedgerData.preview(engine, review.id(), decision.correction, NOW));
        assertFalse("the existing guard must continue refusing this correction",
                engine.correctEntries(List.of(review.id()), decision.correction));
        assertEquals(saved, JsonCodec.gson().toJson(engine.session()));
        assertEquals(revision, engine.revision());
        assertFalse("a refused " + decision + " must have no forecast: " + nets, nets.containsKey(decision));
        return nets;
    }

    private static void delayed(Decision decision) {
        var engine = engine();
        book(engine, NOW - 60_000, 10, 100, true, false);
        var review = book(engine, NOW - 30_000, -4, 100, false, true);
        book(engine, NOW - 10_000, 5, 200, true, false);
        assertEquals(15, EngineProbe.knownCoverageQty(engine, LOGS));
        var nets = refused(engine, review, decision);
        assertEquals(2000, engine.metrics(NOW).net);
        assertEquals("neither financial choice is eligible in this state", "",
                LedgerPage.netPreview(Engine.reviewRow(engine.session(), review, NOW), nets));
    }

    @Test public void delayedReviewOmitsRefusedCost() { delayed(Decision.COST); }
    @Test public void delayedReviewOmitsRefusedGain() { delayed(Decision.GAIN); }

    @Test public void spentAcquisitionOmitsCostButKeepsTheEligibleGain() {
        var engine = engine();
        var review = book(engine, NOW - 60_000, 10, 100, true, true);
        book(engine, NOW - 30_000, -4, 100, true, false);
        assertEquals(6, EngineProbe.knownCoverageQty(engine, LOGS));
        var nets = refused(engine, review, Decision.COST);
        assertEquals(Long.valueOf(600), nets.get(Decision.GAIN));
        assertEquals("If counted as a gain: Net " + Fmt.signed(600),
                LedgerPage.netPreview(Engine.reviewRow(engine.session(), review, NOW), nets));
        assertTrue(engine.correctEntries(List.of(review.id()), Correction.REVENUE));
        assertEquals(nets.get(Decision.GAIN).longValue(), engine.metrics(NOW).net);
        assertEquals(6, EngineProbe.knownCoverageQty(engine, LOGS));
    }

    @Test public void eligibleCostKeepsItsCanonicalAfterTotal() {
        var engine = engine();
        book(engine, NOW - 60_000, 10, 100, true, false);
        var review = book(engine, NOW - 30_000, -4, 100, false, true);
        var nets = readOnly(engine, review);
        assertEquals(Long.valueOf(600), nets.get(Decision.COST));
        assertTrue(engine.correctEntries(List.of(review.id()), Correction.COST));
        assertEquals(nets.get(Decision.COST).longValue(), engine.metrics(NOW).net);
        assertEquals(6, EngineProbe.knownCoverageQty(engine, LOGS));
    }

    @Test public void eligibleGainKeepsItsCanonicalAfterTotal() {
        var engine = engine();
        var review = book(engine, NOW - 30_000, 5, 100, false, true);
        var nets = readOnly(engine, review);
        assertEquals(Long.valueOf(500), nets.get(Decision.GAIN));
        assertTrue(engine.correctEntries(List.of(review.id()), Correction.REVENUE));
        assertEquals(nets.get(Decision.GAIN).longValue(), engine.metrics(NOW).net);
        assertEquals(5, EngineProbe.knownCoverageQty(engine, LOGS));
    }
}
