package com.gpmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static org.junit.Assert.*;

/** Missing-price recovery must publish financial facts and physical coverage atomically. */
public class RepricedBasisTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final int ITEM = 1203;

    private static Engine engine(long price, PriceSource source) {
        JsonCodec.bind(new com.google.gson.Gson());
        var engine = new Engine(changes -> {
            var result = new ArrayList<Flow>();
            changes.forEach((id, quantity) -> result.add(new Flow(id, id == 995 ? "Coins" : "Iron dagger",
                    quantity, id == 995 ? 1 : price, quantity * (id == 995 ? 1 : price),
                    id == 995 ? PriceSource.FACE_VALUE : source)));
            return result;
        }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
        engine.startCustom("Recovered prices", NOW);
        return engine;
    }

    private static Transaction gain(Engine engine, long at) {
        var row = Tx.of(at, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(ITEM, "Iron dagger", 1, 0, 0, PriceSource.UNPRICED)));
        engine.session().addTransaction(row, 2000, false);
        return row;
    }

    private static void coverage(Engine engine, long quantity, long value) {
        assertEquals(quantity, engine.basis.known(ITEM).availableQty);
        assertEquals(value, engine.basis.known(ITEM).availableBasisGp);
    }

    @Test public void aNewlyKnownAcquisitionSuppliesItsBasisOnce() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        var row = gain(engine, NOW + 1);
        var old = row.flows().get(0);
        engine.recheck();
        coverage(engine, 1, 100);
        assertEquals(100, engine.metrics(NOW + 100).net);
        assertEquals(PriceSource.GRAND_EXCHANGE, row.flows().get(0).source());
        assertEquals("Iron dagger", row.flows().get(0).name);
        assertEquals("a detached old flow cannot acquire the new quote", PriceSource.UNPRICED, old.source());
        long revision = engine.revision();
        engine.recheck();
        coverage(engine, 1, 100);
        assertEquals(revision, engine.revision());
        assertEquals(1, engine.session().transactions().size());
    }

    @Test public void theLaterSaleBooksOnlyItsDifferenceFromRecoveredBasis() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        gain(engine, NOW + 1);
        engine.recheck();
        engine.setBaseline(new ContainerSnapshot(Map.of(ITEM, 1L, 995, 10_000L)));
        var slots = new OfferSlots();
        slots.observe(new OfferSlots.Snapshot(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0));
        engine.noteGeOffer(slots.observe(new OfferSlots.Snapshot(0, GrandExchangeOfferState.SELLING,
                ITEM, 1, 0, 150, 0)).get(), "Iron dagger", NOW + 600);
        settle(engine, Map.of(995, 10_000L), NOW + 1200);
        engine.noteGeOffer(slots.observe(new OfferSlots.Snapshot(0, GrandExchangeOfferState.SOLD,
                ITEM, 1, 1, 150, 147)).get(), "Iron dagger", NOW + 3600);
        settle(engine, Map.of(995, 10_147L), NOW + 4200);
        assertEquals(147, engine.metrics(NOW + 6000).net);
        assertEquals(100, engine.marketRows().get(0).trackedBasisConsumedGp);
        assertEquals(47, engine.marketRows().get(0).realizedResultGp);
        coverage(engine, 0, 0);
    }

    private static void settle(Engine engine, Map<Integer, Long> inventory, long at) {
        engine.markDirty();
        for (int tick = 0; tick < 3; tick++)
            engine.processIfDirty(new ContainerSnapshot(inventory), at + tick * 600, null);
    }

    @Test public void knownZeroIsCoverageRatherThanAnUnknownBasis() {
        var engine = engine(0, PriceSource.NO_VALUE);
        gain(engine, NOW + 1);
        engine.recheck();
        coverage(engine, 1, 0);
        assertEquals(1, engine.basis.reserve(ITEM, 1).trackedQty);
    }

    @Test public void anUnloadedQuoteIsAReadOnlyRetry() {
        var engine = engine(0, PriceSource.UNPRICED);
        var row = gain(engine, NOW + 1);
        long revision = engine.revision();
        engine.recheck();
        assertEquals(revision, engine.revision());
        assertEquals(PriceSource.UNPRICED, row.flows().get(0).source());
        coverage(engine, 0, 0);
    }

    @Test public void aLaterPhysicalUseRefusesTheWholePriceUpdate() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        var row = gain(engine, NOW + 1);
        var use = Tx.of(NOW + 2, TransactionType.CONSUMPTION, Context.GENERIC, "", true,
                List.of(new Flow(ITEM, "Iron dagger", -1, 0, 0, PriceSource.UNPRICED)));
        engine.session().addTransaction(use, 2000, false);
        engine.recheck();
        assertEquals(PriceSource.UNPRICED, row.flows().get(0).source());
        assertEquals("pricing a sink does not deplete it twice", NOW + 2, engine.basis.known(ITEM).fencedAt);
        coverage(engine, 0, 0);
    }

    @Test public void aFullyUnknownOpenSaleBlocksRetroactiveCoverage() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        var row = gain(engine, NOW + 1);
        custody(engine);
        long revision = engine.revision();
        engine.recheck();
        assertEquals(PriceSource.UNPRICED, row.flows().get(0).source());
        assertEquals(revision, engine.revision());
        coverage(engine, 0, 0);
    }

    @Test public void aKnownReservationAlsoBlocksRetroactiveCoverage() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        var row = gain(engine, NOW + 1);
        var record = custody(engine);
        record.reservedTrackedQty = 1;
        record.reservedTrackedBasisGp = 80;
        engine.recheck();
        assertEquals(PriceSource.UNPRICED, row.flows().get(0).source());
        assertEquals(80, record.reservedBasis());
        coverage(engine, 0, 0);
    }

    @Test public void anOutsideCollectionLeavesARealizationFenceEvenForUnknownStock() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        var row = gain(engine, NOW + 1);
        var record = custody(engine);
        engine.exchange.away(record);
        engine.recheck();
        assertEquals(PriceSource.UNPRICED, row.flows().get(0).source());
        coverage(engine, 0, 0);
    }

    @Test public void anAmbiguousCustodyHandoffCannotResurrectUnknownStock() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        var row = gain(engine, NOW + 1);
        engine.exchange.handoff(custody(engine), "Unknown disposition");
        engine.recheck();
        assertEquals(PriceSource.UNPRICED, row.flows().get(0).source());
        coverage(engine, 0, 0);
    }

    @Test public void aConfirmedFullReturnAllowsTheStillOwnedItemToBePriced() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        gain(engine, NOW + 1);
        var record = custody(engine);
        record.returnedQty = 1;
        engine.recheck();
        coverage(engine, 1, 100);
    }

    @Test public void aPartialReturnDoesNotInventAnUnreservedShare() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        var row = gain(engine, NOW + 1);
        var record = custody(engine);
        record.capturedQty = record.offeredQty = 2;
        record.returnedQty = 1;
        engine.recheck();
        assertEquals(PriceSource.UNPRICED, row.flows().get(0).source());
        coverage(engine, 0, 0);
    }

    @Test public void recordedDecisionsAndExistingQuotesAreNotRewritten() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        var ignored = gain(engine, NOW + 1);
        ignored.applyCorrection(Correction.IGNORE, "Owner decision");
        var known = Tx.of(NOW + 2, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(526, "Bones", 1, 7, 7, PriceSource.MANUAL_OVERRIDE)));
        engine.session().addTransaction(known, 2000, false);
        long revision = engine.revision();
        engine.recheck();
        assertEquals(revision, engine.revision());
        assertEquals(PriceSource.UNPRICED, ignored.flows().get(0).source());
        assertEquals(Correction.IGNORE, ignored.correction());
        assertEquals(7, known.flows().get(0).value);
        assertEquals(PriceSource.MANUAL_OVERRIDE, known.flows().get(0).source());
    }

    @Test public void aQuoteFailureCannotPublishHalfOfAReceipt() {
        var engine = new Engine(changes -> {
            if (changes.containsKey(526)) throw new IllegalStateException("Unavailable quote");
            return List.of(new Flow(ITEM, "Iron dagger", 1, 100, 100, PriceSource.GRAND_EXCHANGE));
        }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
        engine.startCustom("Atomic quote", NOW);
        var row = Tx.of(NOW + 1, TransactionType.GAIN, Context.GENERIC, "", true, List.of(
                new Flow(ITEM, "Iron dagger", 1, 0, 0, PriceSource.UNPRICED),
                new Flow(526, "Bones", 1, 0, 0, PriceSource.UNPRICED)));
        engine.session().addTransaction(row, 2000, false);
        long revision = engine.revision();
        assertThrows(IllegalStateException.class, engine::recheck);
        assertEquals(revision, engine.revision());
        assertTrue(row.flows().stream().allMatch(flow -> flow.source() == PriceSource.UNPRICED));
        assertEquals(0, engine.metrics(NOW + 100).net);
        coverage(engine, 0, 0);
    }

    private static GeOffer custody(Engine engine) {
        var record = new GeOffer();
        record.offerId = "synthetic-custody";
        record.itemId = ITEM;
        record.side = GeOffer.Side.SELL;
        record.offeredQty = record.capturedQty = 1;
        record.placedAtEpochMillis = record.basisCapturedAtEpochMillis = NOW + 10;
        record.basisSource = PriceSource.UNPRICED;
        engine.exchange.records.add(record);
        engine.exchange.now = NOW + 20;
        return record;
    }

    @Test public void preEpochPricesMayResolveWithoutBackfillingOwnership() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        var row = gain(engine, NOW + 1);
        engine.basis.epoch = NOW + 10;
        engine.recheck();
        assertEquals(100, row.flows().get(0).value);
        coverage(engine, 0, 0);
    }

    @Test public void rejectedMixedRowsKeepAllOriginalQuotesAndCachedMoney() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        var row = Tx.of(NOW + 1, TransactionType.GAIN, Context.GENERIC, "", true, List.of(
                new Flow(ITEM, "Iron dagger", 1, 0, 0, PriceSource.UNPRICED),
                new Flow(526, "Bones", 1, 7, 7, PriceSource.MANUAL_OVERRIDE)));
        engine.session().addTransaction(row, 2000, false);
        engine.basis.fenceRealized(ITEM, NOW + 2);
        var snapshot = MoneySnapshot.of(engine);
        long revision = engine.revision();
        engine.recheck();
        assertSame(snapshot, MoneySnapshot.of(engine));
        assertEquals(revision, engine.revision());
        assertEquals(PriceSource.UNPRICED, row.flows().get(0).source());
        assertEquals(7, row.flows().get(1).value);
        coverage(engine, 0, 0);
    }

    @Test public void recoveryAndItsCoverageSurviveReloadWithoutDuplicating() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        gain(engine, NOW + 1);
        engine.recheck();
        var restored = engine(100, PriceSource.GRAND_EXCHANGE);
        restored.restore(SaveCopies.copy(engine.savedState()), NOW + 100);
        restored.recheck();
        coverage(restored, 1, 100);
        assertEquals(100, restored.metrics(NOW + 200).net);
        restored.resetData(NOW + 300);
        restored.recheck();
        coverage(restored, 0, 0);
    }

    @Test public void aDifferentLoadedOwnerNeverReceivesThePreviousOwnersCoverage() {
        var engine = engine(100, PriceSource.GRAND_EXCHANGE);
        gain(engine, NOW + 1);
        engine.recheck();
        var originalOwner = SaveCopies.copy(engine.savedState());
        engine.restore(new SavedState(null, null, List.of()), NOW + 100);
        engine.recheck();
        coverage(engine, 0, 0);
        engine.restore(originalOwner, NOW + 200);
        engine.recheck();
        coverage(engine, 1, 100);
    }
}
