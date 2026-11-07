package com.gpmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static org.junit.Assert.*;

public class HandoffAccountingReviewTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int ITEM = 1203;

    private static Engine engine() {
        JsonCodec.bind(new com.google.gson.Gson());
        var engine = new Engine(changes -> {
            var result = new ArrayList<Flow>();
            changes.forEach((id, quantity) -> result.add(new Flow(id, id == 995 ? "Coins" : "Iron dagger",
                    quantity, id == 995 ? 1 : 100, quantity * (id == 995 ? 1 : 100),
                    id == 995 ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE)));
            return result;
        }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
        engine.startCustom("Handoff accounting review", NOW);
        return engine;
    }

    private static Transaction handoff(Engine engine, boolean known) {
        return handoff(engine, known, 1, 1);
    }

    private static Transaction handoff(Engine engine, boolean known, long units, long filled) {
        engine.session().addTransaction(Tx.of(NOW + 1, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(ITEM, "Iron dagger", units, known ? 100 : 0, known ? units * 100 : 0,
                        known ? PriceSource.GRAND_EXCHANGE : PriceSource.UNPRICED))), 2000, false);
        var held = engine.basis.reserve(ITEM, units);
        var record = new GeOffer("first-sale", 0, GeOffer.Side.SELL, ITEM, "Iron dagger", units, 150,
                NOW + 2, engine.session().id());
        record.capturedQty = units;
        record.filledQty = filled;
        record.spentGp = filled * 150;
        record.basisUnitPrice = known ? 100 : 0;
        record.basisSource = known ? PriceSource.GRAND_EXCHANGE : PriceSource.UNPRICED;
        record.reservedTrackedQty = held.trackedQty;
        record.reservedTrackedBasisGp = held.basisGp;
        record.basisCapturedAtEpochMillis = NOW + 3;
        engine.exchange.records.add(record);
        engine.exchange.now = NOW + 10;
        engine.exchange.handoff(record, "Ambiguous terminal");
        var review = engine.exchange.rows.get(0);
        engine.session().addTransaction(review, 2000, false);
        return review;
    }

    @Test public void anUnresolvedCapturedSaleCannotFundTheBasisOfAnUntrackedSpare() {
        var engine = engine();
        var unresolved = handoff(engine, true);
        assertTrue(ReviewRules.needsReview(unresolved));
        assertFalse("the captured share stays with its Review", engine.basis.known(ITEM).availableQty > 0);
        // Only the pre-existing, untracked spare remains in the carried inventory.
        engine.setBaseline(new ContainerSnapshot(Map.of(ITEM, 1L, 995, 10_000L)));
        var slots = new OfferSlots();
        slots.observe(new OfferSlots.Snapshot(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0));
        engine.noteGeOffer(slots.observe(new OfferSlots.Snapshot(1, GrandExchangeOfferState.SELLING,
                ITEM, 1, 0, 150, 0)).get(), "Iron dagger", NOW + 600);
        settle(engine, Map.of(995, 10_000L), NOW + 1200);
        engine.noteGeOffer(slots.observe(new OfferSlots.Snapshot(1, GrandExchangeOfferState.SOLD,
                ITEM, 1, 1, 150, 147)).get(), "Iron dagger", NOW + 3600);
        settle(engine, Map.of(995, 10_147L), NOW + 4200);
        var sale = engine.marketRows().get(0);
        assertTrue(ReviewRules.needsReview(unresolved));
        assertEquals("the first captured sale's share is not available to this spare", 0, sale.trackedBasisConsumedGp);
        assertEquals("this fixture has no independently proven tax", 0, sale.realizedResultGp);
        assertEquals("this fixture has no independently proven tax", 100, engine.metrics(NOW + 6000).net);
    }

    @Test public void resolvingAHandoffConsumesOnlyItsOwnShare() {
        var engine = engine();
        var unresolved = handoff(engine, true);
        assertEquals(0, engine.basis.known(ITEM).availableQty);
        engine.basis.release(ITEM, 1, 0);
        assertTrue(engine.correctTransaction(unresolved.id(), Correction.COST, "Owner decision"));
        assertEquals("unrelated free stock is not consumed", 1, engine.basis.known(ITEM).availableQty);
        assertEquals("unrelated free stock is not consumed", 0, engine.basis.known(ITEM).availableBasisGp);
    }

    @Test public void partialFillsKeepUnreturnedStockUnavailable() {
        var engine = engine();
        var unresolved = handoff(engine, true, 2, 1);
        assertEquals("an unfilled counter is not a proven return", 0, engine.basis.known(ITEM).availableQty);
        assertEquals(0, engine.basis.known(ITEM).availableBasisGp);
        assertTrue(engine.correctTransaction(unresolved.id(), Correction.REVENUE, "Owner decision"));
        assertEquals("the decision cannot return unobserved stock", 0,
                engine.basis.known(ITEM).availableQty);
        assertEquals(0, engine.basis.known(ITEM).availableBasisGp);
    }

    @Test public void knownZeroAndUnknownSharesNeverConsumeFreeStock() {
        var zero = engine();
        zero.basis.release(ITEM, 1, 0);
        var zeroReview = zeroHandoff(zero);
        zero.basis.release(ITEM, 1, 100);
        zero.basis.known(ITEM).acquiredAt = NOW;
        assertTrue(zero.correctTransaction(zeroReview.id(), Correction.REVENUE, "Owner decision"));
        assertEquals("known-zero coverage consumes nothing", 1, zero.basis.known(ITEM).availableQty);
        assertEquals(100, zero.basis.known(ITEM).availableBasisGp);

        var unknown = engine();
        var unknownReview = unknownHandoff(unknown);
        unknown.basis.release(ITEM, 1, 100);
        unknown.basis.known(ITEM).acquiredAt = NOW;
        assertTrue(unknown.correctTransaction(unknownReview.id(), Correction.REVENUE, "Owner decision"));
        assertEquals("unknown coverage consumes nothing", 1, unknown.basis.known(ITEM).availableQty);
        assertEquals(100, unknown.basis.known(ITEM).availableBasisGp);
    }

    private static Transaction zeroHandoff(Engine engine) {
        var held = engine.basis.reserve(ITEM, 1);
        return review(engine, "zero-sale", 1, held.trackedQty, held.basisGp, PriceSource.NO_VALUE);
    }

    private static Transaction unknownHandoff(Engine engine) {
        return review(engine, "unknown-sale", 1, 0, 0, PriceSource.UNPRICED);
    }

    private static Transaction review(Engine engine, String offerId, long units, long trackedQty, long trackedBasis,
            PriceSource source) {
        var record = new GeOffer(offerId, 0, GeOffer.Side.SELL, ITEM, "Iron dagger", units, 150,
                NOW + 2, engine.session().id());
        record.capturedQty = record.filledQty = units;
        record.spentGp = units * 150;
        record.basisUnitPrice = source == PriceSource.NO_VALUE ? 0 : 100;
        record.basisSource = source;
        record.reservedTrackedQty = trackedQty;
        record.reservedTrackedBasisGp = trackedBasis;
        record.basisCapturedAtEpochMillis = NOW + 3;
        engine.exchange.records.add(record);
        engine.exchange.now = NOW + 10;
        engine.exchange.handoff(record, "Ambiguous terminal");
        var review = engine.exchange.rows.get(0);
        engine.session().addTransaction(review, 2000, false);
        return review;
    }

    @Test public void aBatchMustNotSpendCoverageAcquiredAfterTheHandoff() {
        batch(true);
    }

    @Test public void chronologicalBatchControlRetainsTheLaterAcquisition() {
        batch(false);
    }

    private static void batch(boolean newestFirst) {
        var engine = engine();
        var earlier = handoff(engine, false);
        var later = Tx.of(NOW + 20, TransactionType.UNCERTAIN, Context.GENERIC, "", false,
                List.of(new Flow(ITEM, "Iron dagger", 1, 100, 100, PriceSource.GRAND_EXCHANGE)));
        engine.session().addTransaction(later, 2000, false);
        assertTrue(engine.session().canCorrect(earlier, Correction.REVENUE));
        assertTrue(engine.session().canCorrect(later, Correction.REVENUE));
        long before = engine.metrics(NOW + 30).net;
        boolean applied = engine.correctEntries(newestFirst ? List.of(later.id(), earlier.id())
                : List.of(earlier.id(), later.id()), Correction.REVENUE);
        var pool = engine.basis.known(ITEM);
        if (applied) {
            assertEquals("the later acquisition is still held after both financial choices", 1, pool.availableQty);
            assertEquals(100, pool.availableBasisGp);
        } else {
            assertEquals("an unsafe batch must refuse without changing money", before, engine.metrics(NOW + 30).net);
            assertEquals(Correction.AUTO, earlier.correction());
            assertEquals(Correction.AUTO, later.correction());
        }
    }

    @Test public void safeBatchAppliesCanonicalOrderWithOneGroupedUndo() {
        var engine = engine();
        var first = uncertain(NOW + 10, 1, 100, 100);
        var second = uncertain(NOW + 20, 1, 200, 200);
        engine.session().addTransaction(first, 2000, false);
        engine.session().addTransaction(second, 2000, false);
        var preview = engine.previewDecision(Decision.GAIN, row -> true, NOW + 30);
        assertEquals(2, preview.rowCount());
        assertEquals(300, preview.netDelta);
        int history = engine.session().correctionHistory.size();
        assertEquals(2, engine.applyDecision(preview));
        assertEquals("application matches the previewed Net", 300, engine.metrics(NOW + 30).net);
        assertEquals(history + 1, engine.session().correctionHistory.size());
        assertEquals("one grouped undo unit", 2, engine.session().latestCorrection().changes().size());
        assertTrue(engine.undoCorrection());
        assertEquals(0, engine.metrics(NOW + 31).net);
        assertEquals(Correction.AUTO, first.correction());
        assertEquals(Correction.AUTO, second.correction());
    }

    @Test public void anUnsafeBatchRefusesAtomicallyAndLogsNoUndo() {
        var engine = engine();
        engine.session().addTransaction(Tx.of(NOW + 1, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(ITEM, "Iron dagger", 1, 100, 100, PriceSource.GRAND_EXCHANGE))), 2000, false);
        var blocked = uncertain(NOW + 10, -1, 100, -100);
        var eligible = uncertain(NOW + 15, 1, 50, 50);
        engine.session().addTransaction(blocked, 2000, false);
        engine.session().addTransaction(eligible, 2000, false);
        engine.session().addTransaction(Tx.of(NOW + 20, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(ITEM, "Iron dagger", 1, 100, 100, PriceSource.GRAND_EXCHANGE))), 2000, false);
        assertFalse("the later acquisition blocks this Review", engine.session().canCorrect(blocked, Correction.COST));
        assertTrue(engine.session().canCorrect(eligible, Correction.COST));
        var preview = engine.previewDecision(Decision.COST, row -> true, NOW + 30);
        assertEquals("preview refuses the same unsafe batch", 0, preview.rowCount());
        assertEquals(0, preview.netDelta);
        long net = engine.metrics(NOW + 30).net;
        int undo = engine.session().correctionHistory.size();
        for (var ids : List.of(List.of(blocked.id(), eligible.id()), List.of(eligible.id(), blocked.id()))) {
            assertFalse(engine.correctEntries(ids, Correction.COST));
            assertEquals(net, engine.metrics(NOW + 30).net);
            assertEquals(undo, engine.session().correctionHistory.size());
            assertEquals(Correction.AUTO, blocked.correction());
            assertEquals(Correction.AUTO, eligible.correction());
            assertEquals(2, engine.basis.known(ITEM).availableQty);
            assertEquals(200, engine.basis.known(ITEM).availableBasisGp);
        }
    }

    @Test public void savedAndDetachedCoverageKeepHeldShareOwnership() {
        var engine = engine();
        var review = handoff(engine, true);
        var saved = SaveCopies.copy(engine.savedState());
        assertTrue(engine.correctTransaction(review.id(), Correction.COST, "Live decision"));
        var json = new com.google.gson.Gson().toJson(saved);
        var restored = engine();
        restored.restore(new com.google.gson.Gson().fromJson(json, SavedState.class), NOW + 100);
        var restoredReview = restored.session().findTransaction(review.id());
        assertTrue(restored.session().canCorrect(restoredReview, Correction.COST));
        assertEquals(0, restored.basis.known(ITEM).availableQty);
        assertTrue(restored.correctTransaction(review.id(), Correction.COST, "Detached decision"));
        assertEquals(0, restored.basis.known(ITEM).availableQty);
        assertEquals(0, restored.basis.known(ITEM).availableBasisGp);
    }

    @Test public void forecastMatchesTheAppliedHandoffDecision() {
        var engine = engine();
        var review = handoff(engine, true);
        var forecasts = engine.reviewNets(review.id());
        assertTrue(forecasts.containsKey(Decision.GAIN));
        assertTrue(engine.correctTransaction(review.id(), Correction.REVENUE, "Owner decision"));
        assertEquals((long) forecasts.get(Decision.GAIN), engine.metrics(NOW + 20).net);
    }

    private static Transaction uncertain(long at, long quantity, long unit, long value) {
        return new Transaction(at, TransactionType.UNCERTAIN, Context.GENERIC, "", "Review", false,
                List.of(new Flow(ITEM, "Iron dagger", quantity, unit, value, PriceSource.GRAND_EXCHANGE)),
                Confidence.UNCERTAIN, "Review", null);
    }

    private static void settle(Engine engine, Map<Integer, Long> inventory, long at) {
        engine.markDirty();
        for (int tick = 0; tick < 3; tick++)
            engine.processIfDirty(new ContainerSnapshot(inventory), at + tick * 600, null);
    }
}
