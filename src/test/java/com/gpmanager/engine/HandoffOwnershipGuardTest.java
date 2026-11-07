package com.gpmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static org.junit.Assert.*;

public class HandoffOwnershipGuardTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int ITEM = 1203;
    private static final long FOUR = 4_000_000_000_000_000_000L;

    private static Engine engine() {
        JsonCodec.bind(new com.google.gson.Gson());
        var engine = new Engine(changes -> {
            var flows = new ArrayList<Flow>();
            changes.forEach((id, qty) -> flows.add(new Flow(id, id == 995 ? "Coins" : "Iron dagger", qty,
                    id == 995 ? 1 : 100, qty * (id == 995 ? 1 : 100),
                    id == 995 ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE)));
            return flows;
        }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
        engine.startCustom("Further accounting review", NOW);
        return engine;
    }

    private static Transaction gain(Engine engine, long at, long qty, long value) {
        var row = Tx.of(at, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(ITEM, "Iron dagger", qty, value / qty, value, PriceSource.MANUAL_OVERRIDE)));
        engine.session().addTransaction(row, 2000, false);
        return row;
    }

    private static GeOffer handoff(Engine engine, long filled) {
        return handoff(engine, filled, 0);
    }

    private static GeOffer handoff(Engine engine, long filled, long returned) {
        gain(engine, NOW + 1, 2, 200);
        var held = engine.basis.reserve(ITEM, 2);
        var record = new GeOffer("lost-lineage", 0, GeOffer.Side.SELL, ITEM, "Iron dagger", 2, 150,
                NOW + 2, engine.session().id());
        record.capturedQty = 2;
        record.filledQty = filled;
        record.spentGp = filled * 150;
        record.basisUnitPrice = 100;
        record.basisSource = PriceSource.GRAND_EXCHANGE;
        record.reservedTrackedQty = held.trackedQty;
        record.reservedTrackedBasisGp = held.basisGp;
        record.basisCapturedAtEpochMillis = NOW + 3;
        record.confidence = GeOffer.CustodyConfidence.AMBIGUOUS;
        engine.exchange.records.add(record);
        engine.exchange.releaseShare(record, returned);
        record.returnedQty = returned;
        engine.exchange.now = NOW + 10;
        var previous = new OfferSlots.Snapshot(0, GrandExchangeOfferState.SELLING, ITEM, 2, (int) filled,
                150, (int) (filled * 150));
        engine.exchange.baseline.put(0, previous);
        engine.exchange.observe(previous, new OfferSlots.Snapshot(0, GrandExchangeOfferState.SELLING,
                882, 1, 0, 10, 0), "Bronze arrow");
        for (var review : engine.exchange.rows) engine.session().addTransaction(review, 2000, false);
        assertEquals("only the explicitly observed return is retained", returned, record.returnedQty);
        return record;
    }

    private static void sellUntrackedSpare(Engine engine) {
        engine.setBaseline(new ContainerSnapshot(Map.of(ITEM, 1L, 995, 10_000L)));
        var slots = new OfferSlots();
        slots.observe(new OfferSlots.Snapshot(1, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0));
        engine.noteGeOffer(slots.observe(new OfferSlots.Snapshot(1, GrandExchangeOfferState.SELLING,
                ITEM, 1, 0, 150, 0)).get(), "Iron dagger", NOW + 600);
        settle(engine, Map.of(995, 10_000L), NOW + 1200);
        engine.noteGeOffer(slots.observe(new OfferSlots.Snapshot(1, GrandExchangeOfferState.SOLD,
                ITEM, 1, 1, 150, 147)).get(), "Iron dagger", NOW + 3600);
        settle(engine, Map.of(995, 10_147L), NOW + 4200);
        var sale = engine.marketRows().stream().filter(row -> row.itemId == ITEM).findFirst().get();
        System.out.println("spare basis=" + sale.trackedBasisConsumedGp + " result=" + sale.realizedResultGp
                + " net=" + engine.metrics(NOW + 6000).net);
        assertEquals("unreturned custody basis is unavailable to the spare", 0, sale.trackedBasisConsumedGp);
        assertEquals("the fixture has no independently proven tax", 200, engine.metrics(NOW + 6000).net);
    }

    private static void settle(Engine engine, Map<Integer, Long> inventory, long at) {
        engine.markDirty();
        for (int tick = 0; tick < 3; tick++) engine.processIfDirty(new ContainerSnapshot(inventory), at + tick * 600, null);
    }

    @Test public void unfilledCapturedStockIsNotAProvenReturn() {
        var engine = engine();
        handoff(engine, 0);
        sellUntrackedSpare(engine);
    }

    @Test public void aPartialHandoffCannotReleaseUnreturnedUnfilledStock() {
        var engine = engine();
        handoff(engine, 1);
        sellUntrackedSpare(engine);
    }

    @Test public void keepingUnreturnedStockUnavailableLeavesTheSpareUnknown() {
        var engine = engine();
        handoff(engine, 1);
        engine.basis.reserve(ITEM, 1);
        sellUntrackedSpare(engine);
    }

    @Test public void saturationCannotLoseRemainingBasisWhenAGainBecomesACost() {
        var engine = engine();
        var first = gain(engine, NOW + 1, 1, FOUR);
        gain(engine, NOW + 2, 1, FOUR);
        gain(engine, NOW + 3, 1, FOUR);
        assertEquals(Long.MAX_VALUE, engine.basis.known(ITEM).availableBasisGp);
        assertFalse("lost saturation must refuse before changing the receipt", engine.correctTransaction(
                first.id(), Correction.COST, "Owner decision"));
        assertEquals(Correction.AUTO, first.correction());
        assertEquals(Long.MAX_VALUE, engine.metrics(NOW + 20).net);
        assertEquals(Long.MAX_VALUE, engine.basis.known(ITEM).availableBasisGp);
        assertTrue(engine.session().correctionHistory.isEmpty());
    }

    @Test public void aBatchCannotSubtractLostSaturationFromTheRemainingAcquisition() {
        var engine = engine();
        var first = gain(engine, NOW + 1, 1, FOUR);
        var second = gain(engine, NOW + 2, 1, FOUR);
        gain(engine, NOW + 3, 1, FOUR);
        for (var ids : List.of(List.of(second.id(), first.id()), List.of(first.id(), second.id()))) {
            assertFalse("saturation must refuse the complete batch", engine.correctEntries(ids, Correction.COST));
            assertEquals(Correction.AUTO, first.correction());
            assertEquals(Correction.AUTO, second.correction());
            assertEquals(Long.MAX_VALUE, engine.metrics(NOW + 20).net);
            assertEquals(Long.MAX_VALUE, engine.basis.known(ITEM).availableBasisGp);
            assertTrue(engine.session().correctionHistory.isEmpty());
        }
    }

    @Test public void ordinaryCorrectionControlRetainsOtherCountedAcquisitions() {
        var engine = engine();
        var first = gain(engine, NOW + 1, 1, 100);
        gain(engine, NOW + 2, 1, 100);
        gain(engine, NOW + 3, 1, 100);
        assertTrue(engine.correctTransaction(first.id(), Correction.COST, "Owner decision"));
        assertEquals(200, engine.basis.known(ITEM).availableBasisGp);
    }

    private static Transaction uncertain(Engine engine, String id, long quantity) {
        var row = new Transaction(NOW + 10, TransactionType.UNCERTAIN, Context.GENERIC, "", "Review", false,
                List.of(new Flow(ITEM, "Iron dagger", quantity, 100, quantity * 100, PriceSource.GRAND_EXCHANGE)),
                Confidence.UNCERTAIN, "Review", null);
        row.id = id;
        engine.session().addTransaction(row, 2000, false);
        return row;
    }

    @Test public void aBatchCannotIntroduceCoverageAfterItsOwnSameTimeSinkFence() {
        var engine = engine();
        var sink = uncertain(engine, "a-sink", -1);
        var gain = uncertain(engine, "b-gain", 1);
        assertTrue(engine.session().canCorrect(sink, Correction.REVENUE));
        assertTrue(engine.session().canCorrect(gain, Correction.REVENUE));
        long net = engine.metrics(NOW + 20).net;
        int history = engine.session().correctionHistory.size();
        var preview = engine.previewDecision(Decision.GAIN, row -> true, NOW + 20);
        boolean applied = engine.correctEntries(List.of(gain.id(), sink.id()), Correction.REVENUE);
        System.out.println("same-time batch applied=" + applied + " preview rows=" + preview.rowCount()
                + " net=" + engine.metrics(NOW + 20).net + " basis=" + engine.basis.known(ITEM).availableBasisGp);
        assertFalse("the projected sink invalidates the acquisition before any mutation", applied);
        assertEquals(0, preview.rowCount());
        assertEquals(net, engine.metrics(NOW + 20).net);
        assertEquals(history, engine.session().correctionHistory.size());
    }

    @Test public void sequentialSameTimeControlRejectsTheAcquisitionAfterTheSink() {
        var engine = engine();
        var sink = uncertain(engine, "a-sink", -1);
        var gain = uncertain(engine, "b-gain", 1);
        assertTrue(engine.correctTransaction(sink.id(), Correction.REVENUE, "First owner decision"));
        assertFalse(engine.session().canCorrect(gain, Correction.REVENUE));
        assertFalse(engine.correctTransaction(gain.id(), Correction.REVENUE, "Second owner decision"));
        assertEquals(0, engine.basis.known(ITEM).availableBasisGp);
    }

    @Test public void provenPartialReturnRemainsAvailableAfterTheHandoffAndDecision() {
        var engine = engine();
        handoff(engine, 1, 1);
        assertEquals(1, engine.basis.known(ITEM).availableQty);
        assertEquals(100, engine.basis.known(ITEM).availableBasisGp);
        var review = engine.session().transactions().stream().filter(ReviewRules::needsReview).findFirst().get();
        assertTrue(engine.correctTransaction(review.id(), Correction.COST, "Owner decision"));
        assertEquals(1, engine.basis.known(ITEM).availableQty);
        assertEquals(100, engine.basis.known(ITEM).availableBasisGp);
    }

    @Test public void aProvenFullReturnReleasesTheWholeReservationWithoutReview() {
        var engine = engine();
        handoff(engine, 0, 2);
        assertTrue(engine.session().transactions().stream().noneMatch(ReviewRules::needsReview));
        var returned = engine.basis.reserve(ITEM, 2);
        assertEquals(2, returned.trackedQty);
        assertEquals(200, returned.basisGp);
    }

    @Test public void batchProjectionDoesNotSpendLiveOrDetachedHandoffIdentities() {
        var engine = engine();
        handoff(engine, 1);
        var review = engine.session().transactions().stream().filter(ReviewRules::needsReview).findFirst().get();
        var gson = new com.google.gson.Gson();
        var before = gson.toJson(engine.basis.stock);
        var saved = SaveCopies.copy(engine.savedState());
        long revision = engine.revision();
        Engine.DecisionPreview preview = null;
        for (int count = 0; count < 3; count++) {
            preview = engine.previewDecision(Decision.GAIN, row -> row.transaction.equals(review.id()), NOW + 20);
            assertEquals(1, preview.rowCount());
            assertEquals(before, gson.toJson(engine.basis.stock));
            assertTrue(review.handoffHeld);
            assertEquals(Correction.AUTO, review.correction());
            assertEquals(revision, engine.revision());
        }
        assertEquals(1, engine.applyDecision(preview));
        assertFalse(review.handoffHeld);
        var restored = engine();
        restored.restore(saved, NOW + 100);
        var detached = restored.session().findTransaction(review.id());
        assertTrue(detached.handoffHeld);
        assertTrue(restored.correctTransaction(detached.id(), Correction.COST, "Detached owner"));
        assertEquals(0, restored.basis.known(ITEM).availableQty);
    }

    @Test public void saturationRefusalSurvivesReloadAndDoesNotBlockFinancialRemoval() {
        var engine = engine();
        var first = gain(engine, NOW + 1, 1, FOUR);
        gain(engine, NOW + 2, 1, FOUR);
        gain(engine, NOW + 3, 1, FOUR);
        var restored = engine();
        restored.restore(SaveCopies.copy(engine.savedState()), NOW + 100);
        assertFalse(restored.correctTransaction(first.id(), Correction.COST, "Unsafe inverse"));
        assertEquals(Long.MAX_VALUE, restored.basis.known(ITEM).availableBasisGp);
        assertTrue("financial removal retains its physical booking", restored.correctEntries(
                List.of(first.id()), Correction.IGNORE));
        assertEquals(Long.MAX_VALUE, restored.basis.known(ITEM).availableBasisGp);
        assertTrue(restored.undoCorrection());
        assertEquals(Long.MAX_VALUE, restored.metrics(NOW + 101).net);
    }

    @Test public void undoCannotSubtractTheLostExcessOfASaturatingDecision() {
        var engine = engine();
        gain(engine, NOW + 1, 1, FOUR);
        gain(engine, NOW + 2, 1, FOUR);
        var last = new Transaction(NOW + 3, TransactionType.UNCERTAIN, Context.GENERIC, "", "Review", false,
                List.of(new Flow(ITEM, "Iron dagger", 1, FOUR, FOUR, PriceSource.MANUAL_OVERRIDE)),
                Confidence.UNCERTAIN, "Review", null);
        engine.session().addTransaction(last, 2000, false);
        assertTrue(engine.correctTransaction(last.id(), Correction.REVENUE, "Count gain"));
        assertFalse(engine.undoCorrection());
        assertEquals(Correction.REVENUE, last.correction());
        assertEquals(Long.MAX_VALUE, engine.basis.known(ITEM).availableBasisGp);
        var restored = engine();
        restored.restore(SaveCopies.copy(engine.savedState()), NOW + 100);
        assertFalse(restored.undoCorrection());
        assertEquals(Long.MAX_VALUE, restored.basis.known(ITEM).availableBasisGp);
    }

    @Test public void saturatedQuantityCannotBeShrunkByAnUnprovableInverse() {
        var engine = engine();
        gain(engine, NOW + 1, Long.MAX_VALUE, 0);
        gain(engine, NOW + 2, 3, 0);
        assertFalse(engine.basis.canApply(ITEM, -1, 0, NOW + 10));
        assertEquals(Long.MAX_VALUE, engine.basis.known(ITEM).availableQty);
        assertTrue(engine.basis.canApply(ITEM, 1, 0, NOW + 10));
    }

    @Test public void fullyUnfilledHandoffRemainsUnavailableAfterReload() {
        var engine = engine();
        handoff(engine, 0);
        var restored = engine();
        restored.restore(SaveCopies.copy(engine.savedState()), NOW + 100);
        assertEquals(0, restored.basis.reserve(ITEM, 2).trackedQty);
        assertEquals(200, restored.metrics(NOW + 200).net);
    }

    @Test public void aNeutralDecisionKeepsTheHeldShareForItsLaterFinancialDecision() {
        var engine = engine();
        handoff(engine, 1);
        var review = engine.session().transactions().stream().filter(ReviewRules::needsReview).findFirst().get();
        assertTrue(engine.correctTransaction(review.id(), Correction.TRANSFER, "Neutral decision"));
        assertTrue(review.handoffHeld);
        engine.basis.release(ITEM, 1, 50);
        assertTrue(engine.correctTransaction(review.id(), Correction.REVENUE, "Financial decision"));
        assertFalse(review.handoffHeld);
        assertEquals(1, engine.basis.known(ITEM).availableQty);
        assertEquals(50, engine.basis.known(ITEM).availableBasisGp);
        long fence = engine.basis.known(ITEM).fencedAt;
        assertEquals(0, engine.basis.deplete(ITEM, 0, NOW + 100));
        assertEquals(fence, engine.basis.known(ITEM).fencedAt);
    }
}
