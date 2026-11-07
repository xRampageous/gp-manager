package com.gpmanager;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class CustodyHandoffDecisionTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int ITEM = 1203;

    private static final class Fixture {
        final Engine engine = knownEngine();
        final Transaction acquisition;
        final Transaction review;

        Fixture(long units, long filled, long price, PriceSource source) {
            this(units, filled, price, source, 0);
        }

        Fixture(long units, long filled, long price, PriceSource source, long spare) {
            engine.startCustom("Handoff decisions", NOW);
            acquisition = Tx.of(NOW + 1, TransactionType.GAIN, Context.GENERIC, "", true,
                    List.of(new Flow(ITEM, "Iron dagger", units + spare, price, (units + spare) * price, source)));
            engine.session().addTransaction(acquisition, 2000, false);
            var reserved = engine.basis.reserve(ITEM, units);
            var record = new GeOffer("handoff-sale", 0, GeOffer.Side.SELL, ITEM, "Iron dagger",
                    units, 150, NOW + 2, engine.session().id());
            record.capturedQty = units;
            record.filledQty = filled;
            record.spentGp = filled * 150;
            record.basisUnitPrice = price;
            record.basisSource = source;
            record.basisCapturedAtEpochMillis = NOW + 3;
            record.reservedTrackedQty = reserved.trackedQty;
            record.reservedTrackedBasisGp = reserved.basisGp;
            engine.exchange.records.add(record);
            engine.exchange.now = NOW + 10;
            engine.exchange.handoff(record, "Ambiguous terminal");
            assertEquals(1, engine.exchange.rows.size());
            review = engine.exchange.rows.get(0);
            engine.session().addTransaction(review, 2000, false);
        }
    }

    private static Engine knownEngine() {
        JsonCodec.bind(new Gson());
        return new Engine(changes -> {
            var flows = new ArrayList<Flow>();
            changes.forEach((id, quantity) -> flows.add(new Flow(id, "Iron dagger", quantity,
                    100, quantity * 100, PriceSource.GRAND_EXCHANGE)));
            return flows;
        }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
    }

    @Test public void knownHandoffsPermitBothDecisionsAndDepleteTheirStockOnce() {
        for (var decision : new Correction[]{Correction.REVENUE, Correction.COST}) {
            var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE);
            assertTrue(f.engine.session().canCorrect(f.review, decision));
            assertEquals("the captured share stays with its Review", 0,
                    f.engine.basis.known(ITEM).availableQty);
            assertTrue(f.review.handoffHeld);
            long before = f.engine.metrics(NOW + 20).net;
            assertTrue(f.engine.correctTransaction(f.review.id(), decision, "Owner decision"));
            assertEquals(before + (decision == Correction.REVENUE ? 250 : -250),
                    f.engine.metrics(NOW + 20).net);
            assertEquals(0, f.engine.basis.known(ITEM).availableQty);
            assertEquals(0, f.engine.basis.known(ITEM).availableBasisGp);
            assertFalse(f.review.handoffHeld);
            assertFalse("a physical sink cannot be silently reversed", f.engine.undoCorrection());
        }
    }

    @Test public void partialHandoffsDoNotReturnUnobservedStock() {
        var f = new Fixture(2, 1, 100, PriceSource.GRAND_EXCHANGE);
        assertTrue(f.engine.correctTransaction(f.review.id(), Correction.COST, "Owner decision"));
        assertEquals(0, f.engine.basis.known(ITEM).availableQty);
        assertEquals(0, f.engine.basis.known(ITEM).availableBasisGp);
    }

    @Test public void unknownAndKnownZeroHandoffsNeverInventCoverage() {
        for (var source : new PriceSource[]{PriceSource.UNPRICED, PriceSource.NO_VALUE}) {
            var f = new Fixture(1, 1, 0, source);
            assertTrue(f.engine.correctTransaction(f.review.id(), Correction.REVENUE, "Owner decision"));
            assertEquals(0, f.engine.basis.known(ITEM).availableQty);
            assertEquals(0, f.engine.basis.known(ITEM).availableBasisGp);
        }
    }

    @Test public void olderAcquisitionRecoveryStaysFencedWhileItsReviewIsEligible() {
        var f = new Fixture(1, 1, 0, PriceSource.UNPRICED);
        f.engine.recheck();
        assertEquals(PriceSource.UNPRICED, f.acquisition.flows().get(0).source());
        assertTrue(f.engine.session().canCorrect(f.review, Correction.REVENUE));
        assertEquals(0, f.engine.basis.known(ITEM).availableQty);
    }

    @Test public void anUnrelatedReviewAtTheSameTimeCannotBorrowTheException() {
        var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE);
        var other = Tx.of(f.review.timestamp, TransactionType.UNCERTAIN, Context.MARKET, "", false,
                List.of(new Flow(ITEM, "Iron dagger", -1, 100, -100, PriceSource.GRAND_EXCHANGE)));
        f.engine.session().addTransaction(other, 2000, false);
        assertFalse(f.engine.session().canCorrect(other, Correction.COST));
        assertTrue(f.engine.session().canCorrect(f.review, Correction.COST));
    }

    @Test public void aLaterPhysicalUseAtTheSameTimeInvalidatesTheException() {
        var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE);
        f.engine.session().addTransaction(Tx.of(f.review.timestamp, TransactionType.CONSUMPTION,
                Context.GENERIC, "", true, List.of(new Flow(ITEM, "Iron dagger", -1, 100, -100,
                        PriceSource.GRAND_EXCHANGE))), 2000, false);
        assertFalse(f.engine.session().canCorrect(f.review, Correction.REVENUE));
        assertFalse(f.engine.correctTransaction(f.review.id(), Correction.COST, "Must refuse"));
    }

    @Test public void aLaterAcquisitionAtTheSameTimeCannotChangeTheOriginalPool() {
        var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE);
        f.engine.session().addTransaction(Tx.of(f.review.timestamp, TransactionType.GAIN,
                Context.GENERIC, "", true, List.of(new Flow(ITEM, "Iron dagger", 1, 200, 200,
                        PriceSource.GRAND_EXCHANGE))), 2000, false);
        assertFalse(f.engine.session().canCorrect(f.review, Correction.REVENUE));
    }

    @Test public void anotherCapturedSaleAtTheSameTimeStillBlocksTheDecision() {
        var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE);
        var next = new GeOffer("later-sale", 1, GeOffer.Side.SELL, ITEM, "Iron dagger", 1, 150,
                f.review.timestamp, f.engine.session().id());
        next.capturedQty = 1;
        next.basisCapturedAtEpochMillis = f.review.timestamp;
        f.engine.exchange.records.add(next);
        assertFalse(f.engine.session().canCorrect(f.review, Correction.REVENUE));
    }

    @Test public void aHandoffCannotReplaceAnAlreadyExistingFenceAtTheSameTime() {
        var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE);
        f.engine.basis.fenceRealized(ITEM, f.review.timestamp);
        assertFalse(f.engine.session().canCorrect(f.review, Correction.REVENUE));
    }

    @Test public void decisionIdentitySurvivesTheActualSaveShape() {
        var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE);
        var gson = new Gson();
        var state = gson.fromJson(gson.toJson(f.engine.savedState()), SavedState.class);
        var restored = ReviewFixesTest.engine();
        restored.restore(state, NOW + 100);
        var review = restored.session().findTransaction(f.review.id());
        assertTrue(review.handoffHeld);
        assertTrue(restored.session().canCorrect(review, Correction.COST));
        assertTrue(restored.correctTransaction(review.id(), Correction.COST, "Owner decision"));
        assertEquals(0, restored.basis.known(ITEM).availableQty);
        assertFalse(review.handoffHeld);
    }

    @Test public void olderSavesWithoutTheHandoffMarkerStayTolerant() {
        var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE);
        var gson = new Gson();
        var json = gson.toJsonTree(f.engine.savedState()).getAsJsonObject();
        var session = json.getAsJsonObject("customSession");
        for (var row : session.getAsJsonArray("transactions"))
            if (f.review.id().equals(row.getAsJsonObject().get("id").getAsString()))
                row.getAsJsonObject().remove("handoffHeld");
        var restored = ReviewFixesTest.engine();
        restored.restore(gson.fromJson(json, SavedState.class), NOW + 100);
        var review = restored.session().findTransaction(f.review.id());
        assertFalse(review.handoffHeld);
        assertTrue(restored.session().canCorrect(review, Correction.COST));
        assertTrue(restored.correctTransaction(review.id(), Correction.COST, "Owner decision"));
        assertEquals(0, restored.basis.known(ITEM).availableQty);
        assertEquals(0, restored.basis.known(ITEM).availableBasisGp);
    }

    @Test public void oldSavesWithoutDecisionIdentityKeepTheirOriginalFence() {
        var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE);
        var gson = new Gson();
        var json = gson.toJsonTree(f.engine.savedState()).getAsJsonObject();
        for (var pool : json.getAsJsonObject("trackedBasis").getAsJsonArray("pools"))
            pool.getAsJsonObject().remove("fenceOwners");
        var restored = ReviewFixesTest.engine();
        restored.restore(gson.fromJson(json, SavedState.class), NOW + 100);
        assertFalse(restored.session().canCorrect(restored.session().findTransaction(f.review.id()), Correction.COST));
    }

    @Test public void forecastsStayReadOnlyAndMatchTheAppliedDecision() {
        var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE);
        long revision = f.engine.revision();
        var forecasts = f.engine.reviewNets(f.review.id());
        assertEquals(revision, f.engine.revision());
        assertEquals("the captured share is not free stock", 0, f.engine.basis.known(ITEM).availableQty);
        assertTrue(forecasts.containsKey(Decision.GAIN));
        assertTrue(forecasts.containsKey(Decision.COST));
        assertTrue(f.engine.correctTransaction(f.review.id(), Correction.REVENUE, "Owner decision"));
        assertEquals((long) forecasts.get(Decision.GAIN), f.engine.metrics(NOW + 20).net);
    }

    @Test public void independentHandoffsCanResolveInEitherOrderWithoutDoubleDepletion() {
        for (boolean reverse : new boolean[]{false, true}) {
            var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE, 1);
            var reserved = f.engine.basis.reserve(ITEM, 1);
            assertEquals("the second independently captured share is known", 100, reserved.basisGp);
            var record = new GeOffer("second-handoff", 1, GeOffer.Side.SELL, ITEM, "Iron dagger",
                    1, 150, NOW + 11, f.engine.session().id());
            record.capturedQty = record.filledQty = 1;
            record.spentGp = 150;
            record.basisUnitPrice = 100;
            record.basisSource = PriceSource.GRAND_EXCHANGE;
            record.basisCapturedAtEpochMillis = NOW + 11;
            record.reservedTrackedQty = reserved.trackedQty;
            record.reservedTrackedBasisGp = reserved.basisGp;
            f.engine.exchange.records.add(record);
            f.engine.exchange.now = NOW + 12;
            f.engine.exchange.handoff(record, "Second ambiguous terminal");
            var second = f.engine.exchange.rows.get(1);
            f.engine.session().addTransaction(second, 2000, false);
            assertEquals("both captured shares stay with their own Reviews", 0,
                    f.engine.basis.known(ITEM).availableQty);
            assertTrue(f.review.handoffHeld);
            assertTrue(second.handoffHeld);
            f.engine.basis.release(ITEM, 1, 50);
            for (var row : reverse ? List.of(second, f.review) : List.of(f.review, second))
                assertTrue(f.engine.correctTransaction(row.id(), Correction.COST, "Owner decision"));
            assertEquals("unrelated free stock survives both decisions", 1,
                    f.engine.basis.known(ITEM).availableQty);
            assertEquals("unrelated free stock survives both decisions", 50,
                    f.engine.basis.known(ITEM).availableBasisGp);
        }
    }

    @Test public void detachedSavedCoverageKeepsItsDecisionRightsAfterLiveMutation() {
        var f = new Fixture(1, 1, 100, PriceSource.GRAND_EXCHANGE);
        var saved = SaveCopies.copy(f.engine.savedState());
        assertTrue(f.engine.correctTransaction(f.review.id(), Correction.COST, "Live decision"));
        assertEquals(0, f.engine.basis.known(ITEM).availableQty);
        var restored = knownEngine();
        restored.restore(saved, NOW + 100);
        assertTrue(restored.session().canCorrect(restored.session().findTransaction(f.review.id()), Correction.COST));
    }
}
