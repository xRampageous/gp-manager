package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2C.6A correction fence: a basis-affecting acquisition correction is allowed only while
 * it can be reflected exactly before downstream realization. Once known coverage is reserved or
 * realized the correction is rejected BEFORE any canonical mutation. No clamp, no history rewrite.
 */
public class TrackedBasisCorrectionFenceTest
{
    private static final int LOGS = ItemID.LOGS;
    private static final int OAK = ItemID.OAK_LOGS;
    private static final int COINS = ItemID.COINS;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void acquisitionCorrectionBeforeRealizationIsAllowedAndExact()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        assertEquals(5L, EngineProbe.knownCoverageQty(h.engine, LOGS));

        assertTrue(h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));

        assertEquals("the exact delta leaves the pool empty", 0L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(0L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        assertEquals("canonical exclusion still applies", 0L, h.net());
    }

    @Test
    public void undoPreRealizationCorrectionRestoresCoverage()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.engine.correctTransaction(gainId, Correction.IGNORE, "test");

        assertTrue(h.engine.undoCorrection());

        assertEquals(5L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(775L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        assertEquals(775L, h.net());
    }

    @Test
    public void correctionWhileReservationOpenIsBlocked()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.inventory = with(h.inventory, LOGS, 5L);
        h.engine.setBaseline(new ContainerSnapshot(h.inventory));
        h.offer(0, SELLING, LOGS, 5, 0, 159, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        assertEquals(5L, EngineProbe.reservedQty(h.engine, LOGS));

        assertFalse("an open reservation freezes basis-affecting corrections",
            h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));

        assertEquals("the canonical acquisition is untouched", Correction.AUTO,
            h.transaction(gainId).correction());
        assertEquals("the reservation is untouched", 5L, EngineProbe.reservedQty(h.engine, LOGS));
        assertEquals(775L, EngineProbe.reservedBasisGp(h.engine, LOGS));
        assertEquals(775L, h.net());
    }

    @Test
    public void zeroRealizationCancellationClearsTheTemporaryBlock()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.inventory = with(h.inventory, LOGS, 5L);
        h.engine.setBaseline(new ContainerSnapshot(h.inventory));
        h.offer(0, SELLING, LOGS, 5, 0, 159, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        assertFalse(h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));

        h.offer(0, CANCELLED_SELL, LOGS, 5, 0, 159, 0);
        h.settle(with(h.inventory, LOGS, 5L));

        assertEquals("the exact reservation is restored", 5L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertTrue("the temporary freeze clears after a zero-realization cancel",
            h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));
        assertEquals(0L, EngineProbe.knownCoverageQty(h.engine, LOGS));
    }

    @Test
    public void correctionAfterCollectedSellIsBlockedBeforeCanonicalMutation()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.sell(0, LOGS, 5L, 159, 790L);
        assertEquals(790L, h.net());

        assertFalse("realized known coverage cannot be changed retroactively",
            h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));

        assertEquals(Correction.AUTO, h.transaction(gainId).correction());
        assertEquals("the realized sale is untouched", 790L, h.net());
        assertEquals(0L, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(0L, EngineProbe.reservedQty(h.engine, LOGS));
    }

    @Test
    public void splitAfterPermanentSinkIsBlocked()
    {
        Harness h = new Harness(100);
        String gainId = h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);
        h.sink(LOGS, 4L, h.now - 30_000L);
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        assertFalse("a shrink split after a permanent sink cannot be reflected exactly",
            h.engine.splitItem(gainId, LOGS, 5L, "test"));

        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        assertEquals(1_000L, h.transaction(gainId).revenue());
    }

    @Test
    public void spentKnownCoverageCannotBeReclassifiedOrUndoneWithoutExactReversal()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);
        h.sink(LOGS, 4L, h.now - 30_000L);
        Transaction sink = h.engine.session().transactions().get(1);

        assertFalse(h.engine.correctTransaction(sink.id(), Correction.TRANSFER, "bank deposit"));
        assertFalse(h.engine.correctTransaction(sink.id(), Correction.IGNORE, "not spent"));
        assertNull(h.engine.undoTransaction());
        assertEquals(Correction.AUTO, sink.correction());
        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        assertTrue("Result attribution may change without reversing physical depletion",
            h.engine.correctTransaction(sink.id(), Correction.COST, "cost"));
        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
    }

    @Test
    public void ownerCountedReviewRemovalDepletesBasisBeforeLaterRealization()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);
        Transaction uncounted = new Transaction(h.now - 30_000L,
            TransactionType.UNCERTAIN, Context.GENERIC, "", "Review", false,
            Collections.singletonList(new Flow(LOGS, "Logs", -4L, 100, -400L,
                PriceSource.GRAND_EXCHANGE)), Confidence.UNCERTAIN, "", null);
        h.engine.session().addTransaction(uncounted, 500, false);

        assertTrue(h.engine.correctTransaction(uncounted.id(), Correction.COST, "spent"));
        assertEquals(Correction.COST, uncounted.correction());
        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        assertFalse("the counted sink cannot later be undone without exact reversal",
            h.engine.correctTransaction(uncounted.id(), Correction.IGNORE, "actually transfer"));
    }

    @Test
    public void delayedReviewDecisionCannotConsumeLaterAcquiredBasis()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);
        Transaction review = new Transaction(h.now - 30_000L,
            TransactionType.UNCERTAIN, Context.GENERIC, "", "Review", false,
            Collections.singletonList(new Flow(LOGS, "Logs", -4L, 100, -400L,
                PriceSource.GRAND_EXCHANGE)), Confidence.UNCERTAIN, "", null);
        h.engine.session().addTransaction(review, 500, false);
        h.gain(LOGS, 5L, 1_000L, h.now - 10_000L);

        assertFalse(h.engine.correctTransaction(review.id(), Correction.COST, "late decision"));
        assertEquals(Correction.AUTO, review.correction());
        assertEquals(15L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(2_000L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
    }


    @Test
    public void unsafeUndoThatWouldReAddBasisAfterLaterSaleIsBlocked()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        assertTrue(h.engine.correctTransaction(gainId, Correction.IGNORE, "test"));
        assertEquals(0L, EngineProbe.knownCoverageQty(h.engine, LOGS));

        h.sell(0, LOGS, 5L, 159, 790L);
        assertEquals("unknown liquidation stays Net-neutral", 0L, h.net());

        assertFalse("undoing would re-add basis after a later sale",
            h.engine.undoCorrection());
        assertEquals(0L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(0L, h.net());
    }

    @Test
    public void sellResultCorrectionStaysAllowedAndKeepsBasisAndGeDifference()
    {
        Harness h = new Harness(159);
        h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        h.sell(0, LOGS, 5L, 159, 790L);
        MarketRows.Row before = h.rows().get(0);
        assertEquals(15L, before.realizedResultGp);
        assertEquals("after-tax cash", 790L, before.observedSettlementGp);
        GeOffer record = h.engine.exchange.records().get(0);

        Transaction settlement = h.transaction(before.settlementId);
        assertNotNull(settlement);
        assertTrue("financial attribution corrections stay available",
            h.engine.correctTransaction(settlement.id(), Correction.COST, "test"));

        assertEquals("physical basis consumption is unchanged", 775L,
            record.getConsumedTrackedBasisGp());
        MarketRows.Row after = h.rows().get(0);
        assertEquals("GE execution evidence is never rewritten", 790L, after.observedSettlementGp);
        assertTrue(after.realizedResultCorrectionAware);
        assertEquals("the corrected financial result is visible", -1_565L, after.realizedResultGp);
    }

    @Test
    public void preEpochAcquisitionCorrectionIsBlocked()
    {
        Harness h = new Harness(159);
        String gainId = h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        SavedState legacy = h.engine.savedState();
        legacy.setSchemaVersion(105);
        legacy.setTrackedBasis(null);

        Engine migrated = engine(new int[] {159});
        migrated.restore(legacy, h.now + 1_000L);
        Transaction gain = migrated.session().findTransaction(gainId);
        assertNotNull(gain);

        assertFalse("pre-106 coverage is unknown and cannot be changed retroactively",
            migrated.correctTransaction(gainId, Correction.IGNORE, "test"));
        assertEquals(Correction.AUTO, gain.correction());
    }

    @Test public void manualRemovalOfAUsedPickaxeChangesOnlyItsBookingAndUndo() throws Exception {
        Harness h = new Harness(18_635);
        String gain = h.gain(1275, 1, 18_635, h.now - 60_000);
        h.sink(1275, 1, h.now);
        long before = h.net();
        String stock = stock(h.engine);
        removeInLedger(h.engine, gain);
        assertEquals(Correction.IGNORE, h.transaction(gain).correction());
        assertEquals(before - 18_635, h.net());
        assertEquals(stock, stock(h.engine));
        assertTrue(h.engine.undoCorrection());
        assertEquals(before, h.net());
        assertEquals(stock, stock(h.engine));
        assertEquals(18_635, h.transaction(gain).flows().get(0).unitPrice);
    }

    @Test public void manualRemovalBeforeSaleKeepsOwnershipAndLaterUndoCannotDoubleIt() throws Exception {
        Harness h = new Harness(159);
        String gain = h.gain(LOGS, 5, 775, h.now - 60_000);
        String stock = stock(h.engine);
        removeInLedger(h.engine, gain);
        assertEquals(0, h.net());
        assertEquals(stock, stock(h.engine));
        h.sell(0, LOGS, 5, 159, 790);
        assertEquals(15, h.net());
        assertEquals(0, EngineProbe.availableQty(h.engine, LOGS));
        assertTrue(h.engine.undoCorrection());
        assertEquals(790, h.net());
        assertEquals(0, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(0, EngineProbe.availableBasisGp(h.engine, LOGS));
    }

    @Test public void manualRemovalAfterSaleNeverRewritesTheTradeOrRecreatesStock() throws Exception {
        Harness h = new Harness(159);
        String gain = h.gain(LOGS, 5, 775, h.now - 60_000);
        h.sell(0, LOGS, 5, 159, 790);
        long before = h.net(), result = h.rows().get(0).realizedResultGp;
        String trades = new com.google.gson.Gson().toJson(h.engine.exchange.records());
        String stock = stock(h.engine);
        removeInLedger(h.engine, gain);
        assertEquals(Correction.IGNORE, h.transaction(gain).correction());
        assertEquals(before - 775, h.net());
        assertEquals(result, h.rows().get(0).realizedResultGp);
        assertEquals(trades, new com.google.gson.Gson().toJson(h.engine.exchange.records()));
        assertEquals(stock, stock(h.engine));
        assertTrue(h.engine.undoCorrection());
        assertEquals(before, h.net());
        assertEquals(stock, stock(h.engine));
    }

    @Test public void manualRemovalSurvivesReloadAndCancelledReservationWithoutDuplicatingBasis() throws Exception {
        Harness h = new Harness(159);
        String gain = h.gain(LOGS, 5, 775, h.now - 60_000);
        h.inventory = with(h.inventory, LOGS, 5L);
        h.engine.setBaseline(new ContainerSnapshot(h.inventory));
        h.offer(0, SELLING, LOGS, 5, 0, 159, 0);
        h.inventory = with(h.inventory, LOGS, 0L);
        h.settle(h.inventory);
        long before = h.net();
        String stock = stock(h.engine);
        removeInLedger(h.engine, gain);
        assertEquals(before - 775, h.net());
        assertEquals(stock, stock(h.engine));
        var gson = new com.google.gson.Gson();
        var state = gson.fromJson(gson.toJson(h.engine.exportState()), SavedState.class);
        Engine restored = engine(new int[] {159});
        restored.restore(state, h.now + 1);
        assertEquals(stock, stock(restored));
        h.engine = restored;
        h.engine.setBaseline(new ContainerSnapshot(h.inventory));
        h.engine.session().resume(h.now);
        h.engine.seedGeSlots(Collections.singletonMap(0,
                new OfferSlots.Snapshot(0, SELLING, LOGS, 5, 0, 159, 0)), h.now + 500);
        h.offer(0, CANCELLED_SELL, LOGS, 5, 0, 159, 0);
        h.inventory = with(h.inventory, LOGS, 5L);
        h.settle(h.inventory);
        assertEquals(5, EngineProbe.availableQty(restored, LOGS));
        assertTrue(restored.undoCorrection());
        assertEquals(5, EngineProbe.availableQty(restored, LOGS));
        assertEquals(775, EngineProbe.availableBasisGp(restored, LOGS));
        assertEquals(before, restored.metrics(h.now + 2).net);
    }

    @Test public void manualRemovalOfPreTrackingHistoryNeverBackfillsOwnedValue() throws Exception {
        Harness h = new Harness(159);
        String gain = h.gain(LOGS, 5, 775, h.now - 60_000);
        var legacy = h.engine.exportState();
        legacy.setSchemaVersion(105);
        legacy.setTrackedBasis(null);
        Engine restored = engine(new int[] {159});
        restored.restore(legacy, h.now + 1_000);
        String stock = stock(restored);
        long before = restored.metrics(h.now + 1_000).net;
        removeInLedger(restored, gain);
        assertEquals(before - 775, restored.metrics(h.now + 2_000).net);
        assertEquals(stock, stock(restored));
        assertTrue(restored.undoCorrection());
        assertEquals(before, restored.metrics(h.now + 2_000).net);
        assertEquals(stock, stock(restored));
    }

    @Test public void financialRemovalDoesNotBypassOtherOwnershipCorrections() throws Exception {
        Harness h = new Harness(159);
        String gain = h.gain(LOGS, 5, 775, h.now - 60_000);
        h.sink(LOGS, 2, h.now);
        String stock = stock(h.engine);
        removeInLedger(h.engine, gain);
        assertFalse(h.engine.correctTransaction(gain, Correction.COST, "owner"));
        assertFalse(h.engine.correctTransaction(gain, Correction.TRANSFER, "owner"));
        assertEquals(Correction.IGNORE, h.transaction(gain).correction());
        assertEquals(stock, stock(h.engine));
        assertTrue(h.engine.undoCorrection());
        assertEquals(Correction.AUTO, h.transaction(gain).correction());
        assertEquals(stock, stock(h.engine));
        assertFalse(h.engine.correctTransaction(gain, Correction.COST, "owner"));
        assertFalse(h.engine.correctTransaction(gain, Correction.TRANSFER, "owner"));
        assertEquals(stock, stock(h.engine));
    }

    @Test public void financialRemovalDoesNotUnlockQuantityEdits() throws Exception {
        Harness h = new Harness(159);
        String gain = h.gain(LOGS, 5, 775, h.now - 60_000);
        h.sink(LOGS, 2, h.now);
        removeInLedger(h.engine, gain);
        assertTrue(h.engine.undoCorrection());
        assertFalse(h.engine.splitItem(gain, LOGS, 2, "owner"));
        assertEquals(5, h.transaction(gain).flows().get(0).quantity);
        assertEquals(3, EngineProbe.availableQty(h.engine, LOGS));
    }

    @Test public void removedDropStillDepletesOwnershipOnceWhenItsPickupWindowEnds() throws Exception {
        Harness h = new Harness(159);
        h.gain(LOGS, 5, 775, h.now - 60_000);
        var drop = Tx.of(h.now, TransactionType.CONSUMPTION, Context.GENERIC, "", true,
                List.of(new Flow(LOGS, "Logs", -2, 155, -310, PriceSource.GRAND_EXCHANGE)));
        drop.setOnGround(true);
        h.engine.session().addTransaction(drop, 500, false);
        assertEquals(5, EngineProbe.availableQty(h.engine, LOGS));
        removeInLedger(h.engine, drop.id());
        assertEquals(775, h.net());
        assertEquals(5, EngineProbe.availableQty(h.engine, LOGS));
        h.engine.session().usedUp(drop);
        assertEquals(3, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(465, EngineProbe.availableBasisGp(h.engine, LOGS));
        assertTrue(h.engine.undoCorrection());
        assertEquals(465, h.net());
        assertEquals(3, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(465, EngineProbe.availableBasisGp(h.engine, LOGS));
    }

    @Test public void removalMarkerIsOptionalAndRoundTripsWithoutChangingSavedNames() throws Exception {
        Harness h = new Harness(159);
        String gain = h.gain(LOGS, 5, 775, h.now - 60_000);
        var gson = new com.google.gson.Gson();
        var oldJson = gson.toJson(h.engine.exportState());
        assertFalse(oldJson.contains("ownershipCorrection"));
        Engine old = engine(new int[] {159});
        old.restore(gson.fromJson(oldJson, SavedState.class), h.now);
        assertEquals(h.net(), old.metrics(h.now).net);
        assertEquals(stock(h.engine), stock(old));
        removeInLedger(h.engine, gain);
        var json = gson.toJson(h.engine.exportState());
        assertTrue(json.contains("\"ownershipCorrection\":\"AUTO\""));
        var saved = gson.fromJson(json, SavedState.class);
        assertEquals(110, saved.schemaVersion);
        Engine restored = engine(new int[] {159});
        restored.restore(saved, h.now);
        assertEquals(0, restored.metrics(h.now).net);
        assertEquals(stock(h.engine), stock(restored));
        assertTrue(restored.undoCorrection());
        assertEquals(775, restored.metrics(h.now).net);
        assertEquals(stock(h.engine), stock(restored));
        var unknown = gson.fromJson(json.replace("\"ownershipCorrection\":\"AUTO\"",
                "\"ownershipCorrection\":\"FUTURE_VALUE\""), SavedState.class);
        Engine tolerant = engine(new int[] {159});
        tolerant.restore(unknown, h.now);
        assertEquals(0, tolerant.metrics(h.now).net);
        assertEquals(stock(h.engine), stock(tolerant));
    }

    @Test public void refusedOrRepeatedRemovalLeavesAllStateUnchanged() throws Exception {
        Harness h = new Harness(159);
        String gain = h.gain(LOGS, 5, 775, h.now - 60_000);
        String before = stableState(h.engine);
        assertFalse(h.engine.removeEntry("missing", "owner"));
        assertEquals(before, stableState(h.engine));
        removeInLedger(h.engine, gain);
        before = stableState(h.engine);
        assertFalse(h.engine.removeEntry(gain, "owner"));
        assertEquals(before, stableState(h.engine));
    }

    @Test public void undoneRemovalRetainsNoOwnershipForALaterGiveAwayOrSale() throws Exception {
        Harness h = new Harness(159);
        String gain = h.gain(LOGS, 5, 775, h.now - 60_000);
        removeInLedger(h.engine, gain);
        assertTrue(h.engine.undoCorrection());
        assertTrue(h.engine.splitItem(gain, LOGS, 0, "owner"));
        assertEquals(0, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(0, EngineProbe.availableBasisGp(h.engine, LOGS));
        h.sell(0, LOGS, 5, 159, 790);
        assertEquals(0, h.net());
        assertNull(h.transaction(gain).ownershipCorrection);
    }

    // ---- removed own drops (G2/G3): quote 159 each; known logs basis 155 each, oak 100 each ----

    @Test public void removedOwnDropRecoveryIsNeutralAndKeepsKnownBasisForTheTaxedSale() throws Exception {
        Harness h = new Harness(159);
        String gain = h.gain(LOGS, 5, 775, h.now - 60_000);
        h.hold(LOGS, 5L);
        Transaction drop = h.drop(LOGS, 5);
        assertEquals("Dropped", drop.note());
        assertEquals(159, drop.flows().get(0).unitPrice);
        assertEquals(775 - 795, h.net());
        removeInLedger(h.engine, drop.id());
        assertEquals(775, h.net());
        Transaction back = h.pickUp(LOGS, 5L);
        assertEquals(TransactionType.TRANSFER, back.type());
        assertEquals("Picked back up", back.note());
        assertEquals("recovery adds no income", 775, h.net());
        assertEquals(0, drop.quantity(LOGS, false));
        assertEquals(Correction.IGNORE, drop.correction());
        assertEquals(1, h.engine.session().correctionHistory.size());
        h.expire();
        assertEquals(5, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(775, EngineProbe.availableBasisGp(h.engine, LOGS));
        assertEquals(155, h.transaction(gain).flows().get(0).unitPrice);
        // 5 x 159 = 795 gross, 5 x (159 / 50 = 3) = 15 tax, 780 received; 780 - 775 basis = +5.
        h.sellTaxed(0, LOGS, 5, 159, 780);
        assertEquals(780, h.net());
        assertEquals(0, EngineProbe.availableQty(h.engine, LOGS));
    }

    @Test public void removedOwnDropOfUnknownStockInventsNoIncomeOrBasis() throws Exception {
        Harness h = new Harness(159);
        h.hold(LOGS, 5L);
        assertEquals(0, EngineProbe.availableQty(h.engine, LOGS));
        Transaction drop = h.drop(LOGS, 5);
        assertEquals(-795, h.net());
        removeInLedger(h.engine, drop.id());
        assertEquals(0, h.net());
        assertEquals(TransactionType.TRANSFER, h.pickUp(LOGS, 5L).type());
        assertEquals(0, h.net());
        h.expire();
        assertEquals(0, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(0, EngineProbe.availableBasisGp(h.engine, LOGS));
        // Unknown basis with a proven 15 GP tax books exactly the tax as the trade cost.
        h.sellTaxed(0, LOGS, 5, 159, 780);
        assertEquals(-15, h.net());
    }

    @Test public void partlyRecoveredRemovedDropDepletesOnlyTheUnrecoveredItems() throws Exception {
        Harness h = new Harness(159);
        h.gain(LOGS, 5, 775, h.now - 60_000);
        h.hold(LOGS, 5L);
        Transaction drop = h.drop(LOGS, 5);
        removeInLedger(h.engine, drop.id());
        assertEquals(TransactionType.TRANSFER, h.pickUp(LOGS, 2L).type());
        assertEquals(775, h.net());
        assertEquals(3, drop.quantity(LOGS, false));
        h.expire();
        assertEquals(2, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(310, EngineProbe.availableBasisGp(h.engine, LOGS));
        assertEquals(775, h.net());
        // 2 x 159 = 318 gross, 6 tax, 312 received; 312 - 310 basis = +2.
        h.sellTaxed(0, LOGS, 2, 159, 312);
        assertEquals(777, h.net());
    }

    @Test public void mixedRecoveryKeepsGenuineExcessAndShrinksEachOwnDrop() throws Exception {
        Harness h = new Harness(159);
        h.gain(LOGS, 5, 775, h.now - 60_000);
        h.gain(OAK, 3, 300, h.now - 60_000);
        h.hold(LOGS, 5L, OAK, 3L);
        Transaction logs = h.drop(LOGS, 5), oak = h.drop(OAK, 3);
        assertEquals(1_075 - 795 - 477, h.net());
        removeInLedger(h.engine, logs.id());
        assertEquals(1_075 - 477, h.net());
        h.pickUp(LOGS, 7L, OAK, 1L);
        assertEquals(0, logs.quantity(LOGS, false));
        assertEquals(2, oak.quantity(OAK, false));
        assertEquals(Correction.AUTO, oak.correction());
        // Two genuine extra logs count once (+318); the unrecovered oak stays a -318 loss.
        assertEquals(1_075, h.net());
        h.expire();
        assertEquals(1_075, h.net());
        assertEquals(7, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(1_093, EngineProbe.availableBasisGp(h.engine, LOGS));
        assertEquals(1, EngineProbe.availableQty(h.engine, OAK));
        assertEquals(100, EngineProbe.availableBasisGp(h.engine, OAK));
    }

    @Test public void ordinaryOwnDropStillShrinksAndDepletesItsRemainder() throws Exception {
        Harness h = new Harness(159);
        h.gain(LOGS, 5, 775, h.now - 60_000);
        h.hold(LOGS, 5L);
        Transaction drop = h.drop(LOGS, 5);
        assertEquals(TransactionType.TRANSFER, h.pickUp(LOGS, 2L).type());
        assertEquals(Correction.AUTO, drop.correction());
        assertEquals(775 - 477, h.net());
        h.expire();
        assertEquals(298, h.net());
        assertEquals(2, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(310, EngineProbe.availableBasisGp(h.engine, LOGS));
    }

    @Test public void legacyExcludedDropWithoutMarkerReconcilesItsPickup() throws Exception {
        Harness h = new Harness(159);
        h.gain(LOGS, 5, 775, h.now - 60_000);
        h.hold(LOGS, 5L);
        Transaction drop = h.drop(LOGS, 5);
        assertTrue(h.engine.correctTransaction(drop.id(), Correction.IGNORE, "older exclusion"));
        assertNull(drop.ownershipCorrection);
        assertEquals(TransactionType.TRANSFER, h.pickUp(LOGS, 5L).type());
        assertEquals(775, h.net());
        h.expire();
        assertEquals(5, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(775, EngineProbe.availableBasisGp(h.engine, LOGS));
    }

    @Test public void otherDropCorrectionsKeepTheirExistingPickupRule() throws Exception {
        Harness h = new Harness(159);
        h.hold(LOGS, 5L);
        Transaction drop = h.drop(LOGS, 5);
        assertTrue(h.engine.correctTransaction(drop.id(), Correction.TRANSFER, "owner transfer"));
        assertEquals(TransactionType.GAIN, h.pickUp(LOGS, 5L).type());
        assertEquals(Correction.TRANSFER, drop.correction());
        assertEquals(5, drop.quantity(LOGS, false));
    }

    @Test public void undoAfterRecoveryRecreatesNoIncomeStockOrBasis() throws Exception {
        Harness full = new Harness(159);
        full.gain(LOGS, 5, 775, full.now - 60_000);
        full.hold(LOGS, 5L);
        Transaction all = full.drop(LOGS, 5);
        removeInLedger(full.engine, all.id());
        full.pickUp(LOGS, 5L);
        assertTrue(full.engine.undoCorrection());
        assertEquals(Correction.AUTO, all.correction());
        assertNull(all.ownershipCorrection);
        assertEquals(775, full.net());
        full.expire();
        assertEquals(775, full.net());
        assertEquals(5, EngineProbe.availableQty(full.engine, LOGS));
        assertEquals(775, EngineProbe.availableBasisGp(full.engine, LOGS));

        Harness part = new Harness(159);
        part.gain(LOGS, 5, 775, part.now - 60_000);
        part.hold(LOGS, 5L);
        Transaction some = part.drop(LOGS, 5);
        removeInLedger(part.engine, some.id());
        part.pickUp(LOGS, 2L);
        assertTrue(part.engine.undoCorrection());
        assertEquals("the 3 still on the ground count as lost again", 775 - 477, part.net());
        part.expire();
        assertEquals(298, part.net());
        assertEquals(2, EngineProbe.availableQty(part.engine, LOGS));
        assertEquals(310, EngineProbe.availableBasisGp(part.engine, LOGS));
    }

    @Test public void completedRecoverySurvivesJsonButPendingDropMemoryDoesNot() throws Exception {
        Harness done = new Harness(159);
        done.gain(LOGS, 5, 775, done.now - 60_000);
        done.hold(LOGS, 5L);
        Transaction drop = done.drop(LOGS, 5);
        removeInLedger(done.engine, drop.id());
        done.pickUp(LOGS, 5L);
        done.reload();
        var restored = done.transaction(drop.id());
        assertEquals(Correction.IGNORE, restored.correction());
        assertEquals(Correction.AUTO, restored.ownershipCorrection);
        assertEquals(0, restored.quantity(LOGS, false));
        done.expire();
        assertEquals(775, done.net());
        assertEquals(5, EngineProbe.availableQty(done.engine, LOGS));
        assertEquals(775, EngineProbe.availableBasisGp(done.engine, LOGS));

        Harness pending = new Harness(159);
        pending.gain(LOGS, 5, 775, pending.now - 60_000);
        pending.hold(LOGS, 5L);
        Transaction left = pending.drop(LOGS, 5);
        removeInLedger(pending.engine, left.id());
        pending.reload();
        pending.expire();
        assertFalse(pending.transaction(left.id()).onGround());
        assertEquals(0, EngineProbe.availableQty(pending.engine, LOGS));
        assertEquals("no drop memory is invented after a reload", TransactionType.GAIN,
                pending.pickUp(LOGS, 5L).type());
        assertEquals(5, pending.transaction(left.id()).quantity(LOGS, false));
    }

    private static String stableState(Engine engine) {
        var state = engine.exportState();
        state.savedAtEpochMillis = 0; // Compare durable facts, not the time a copy was requested.
        return new com.google.gson.Gson().toJson(state);
    }

    private static String stock(Engine engine) {
        return new com.google.gson.Gson().toJson(engine.exportState().trackedBasis());
    }

    private static void removeInLedger(Engine engine, String id) throws Exception {
        MilestoneTest.onEdt(() -> {
            var panel = new SidebarPanel(engine, new GpManagerConfig() {}, null, null, null);
            var controller = new LedgerController(panel, false);
            var preview = controller.preview(id, Correction.IGNORE);
            assertNotNull("explicit removal must have a usable preview", preview);
            controller.correct(id, Correction.IGNORE, preview.revision);
            assertEquals(Correction.IGNORE, engine.session().findTransaction(id).correction());
            return null;
        });
    }

    // ---- harness ----------------------------------------------------------------------------

    private static final class Harness
    {
        final int[] quote;
        Engine engine;
        final OfferSlots ledger = new OfferSlots();
        long now = T0;
        Map<Integer, Long> inventory = new HashMap<>();

        Harness(int initialQuote)
        {
            quote = new int[] { initialQuote };
            engine = engine(quote);
            engine.startCustom("Trading", now);
            inventory.put(COINS, 1_000_000L);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }

        String gain(int item, long qty, long value, long at)
        {
            Transaction transaction = new Transaction(at, TransactionType.GAIN,
                Context.GENERIC, "", "Loot", true,
                Collections.singletonList(new Flow(item, name(item), qty,
                    (int) (qty > 0L ? value / qty : 0L), value, PriceSource.GRAND_EXCHANGE)),
                Confidence.CONFIRMED, "", null);
            engine.session().addTransaction(transaction, 500, false);
            return transaction.id();
        }

        void sink(int item, long qty, long at)
        {
            engine.session().addTransaction(new Transaction(at,
                TransactionType.CONSUMPTION, Context.GENERIC, "", "Used", true,
                Collections.singletonList(new Flow(item, name(item), -qty, 100, -qty * 100L,
                    PriceSource.GRAND_EXCHANGE)),
                Confidence.CONFIRMED, "", null), 500, false);
        }

        void sell(int slot, int item, long qty, int limit, long cash)
        {
            inventory = with(inventory, item, qty);
            engine.setBaseline(new ContainerSnapshot(inventory));
            offer(slot, SELLING, item, (int) qty, 0, limit, 0);
            inventory = with(inventory, item, 0L);
            settle(inventory);
            offer(slot, SOLD, item, (int) qty, (int) qty, limit, (int) cash);
            inventory = with(inventory, COINS, inventory.getOrDefault(COINS, 0L) + cash);
            settle(inventory);
        }

        void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent)
        {
            now += 600L;
            OfferSlots.Transition transition = ledger.observe(
                new OfferSlots.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.noteGeOffer(transition, name(transition.current.item), now);
            }
        }

        Transaction settle(Map<Integer, Long> next)
        {
            now += 600L;
            inventory = new HashMap<>(next);
            engine.markDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            Transaction result = null;
            for (int i = 0; i < 3; i++)
            {
                Transaction settled = engine.processIfDirty(snapshot, now, null);
                if (settled != null)
                {
                    result = settled;
                }
                now += 600L;
            }
            return result;
        }

        void hold(Object... pairs)
        {
            inventory = with(inventory, pairs);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }

        Transaction drop(int item, long qty)
        {
            engine.noteDrop(item, 6);
            return settle(with(inventory, item, inventory.getOrDefault(item, 0L) - qty));
        }

        Transaction pickUp(Object... pairs)
        {
            Map<Integer, Long> next = new HashMap<>(inventory);
            for (int i = 0; i < pairs.length; i += 2)
            {
                next.merge((Integer) pairs[i], (Long) pairs[i + 1], Long::sum);
            }
            return settle(next);
        }

        /** Ages every remembered drop past its 300-tick pickup window. */
        void expire()
        {
            for (int i = 0; i < 300; i++)
            {
                now += 600L;
                engine.processIfDirty(new ContainerSnapshot(inventory), now, null);
            }
        }

        /** A sale whose G.E. gross is qty x price and whose collected cash is the taxed amount. */
        void sellTaxed(int slot, int item, long qty, int price, long received)
        {
            inventory = with(inventory, item, qty);
            engine.setBaseline(new ContainerSnapshot(inventory));
            offer(slot, SELLING, item, (int) qty, 0, price, 0);
            inventory = with(inventory, item, 0L);
            settle(inventory);
            offer(slot, SOLD, item, (int) qty, (int) qty, price, (int) (qty * price));
            inventory = with(inventory, COINS, inventory.getOrDefault(COINS, 0L) + received);
            settle(inventory);
        }

        /** Saved JSON into a fresh engine: durable rows survive, transient drop memory does not. */
        void reload()
        {
            var gson = new com.google.gson.Gson();
            var state = gson.fromJson(gson.toJson(engine.exportState()), SavedState.class);
            Engine restored = engine(quote);
            restored.restore(state, now + 1);
            engine = restored;
            engine.setBaseline(new ContainerSnapshot(inventory));
            engine.session().resume(now);
        }

        Transaction transaction(String id)
        {
            return engine.session().findTransaction(id);
        }

        long net()
        {
            return engine.metrics(now).net;
        }

        List<MarketRows.Row> rows()
        {
            return engine.marketRows();
        }
    }

    private static String name(int id)
    {
        return id == COINS ? "Coins" : id == LOGS ? "Logs" : "Item " + id;
    }

    private static Map<Integer, Long> with(Map<Integer, Long> base, Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>(base);
        for (int i = 0; i < pairs.length; i += 2)
        {
            long quantity = (Long) pairs[i + 1];
            if (quantity <= 0L)
            {
                map.remove((Integer) pairs[i]);
            }
            else
            {
                map.put((Integer) pairs[i], quantity);
            }
        }
        return map;
    }

    private static Engine engine(int[] quote)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                int unit = id == COINS ? 1 : quote[0];
                PriceSource source = id == COINS ? PriceSource.FACE_VALUE
                    : unit > 0 ? PriceSource.GRAND_EXCHANGE : PriceSource.UNPRICED;
                flows.add(new Flow(id, name(id), delta.getValue(), unit, delta.getValue() * unit,
                    source));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
