package com.gpmanager.engine;

import java.util.Map;
import java.util.Optional;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GeOfferLedgerTest
{
    @Test
    public void loginReplaySeedsSlotsWithoutEmittingTransitions()
    {
        GeOfferLedger ledger = new GeOfferLedger();
        ledger.beginLoginSeed();

        assertFalse(ledger.observe(snapshot(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0)).isPresent());
        assertFalse(ledger.observe(snapshot(1, GrandExchangeOfferState.BUYING, 4151, 10, 0, 2_000_000, 0)).isPresent());
        assertFalse(ledger.observe(snapshot(1, GrandExchangeOfferState.BUYING, 4151, 10, 2, 2_000_000, 3_900_000)).isPresent());
        assertTrue(ledger.isLoginSeedInProgress());

        ledger.finishLoginSeed();
        Optional<GeOfferLedger.Transition> transition = ledger.observe(
            snapshot(1, GrandExchangeOfferState.BUYING, 4151, 10, 3, 2_000_000, 5_850_000));
        assertTrue(transition.isPresent());
        assertEquals(1, transition.get().getQuantityTradedDelta());
        assertEquals(1_950_000, transition.get().getSpentDelta());
    }

    @Test
    public void repeatedSnapshotsAreIdempotentAndFirstEmptySnapshotIsOnlyABaseline()
    {
        GeOfferLedger ledger = new GeOfferLedger();
        GeOfferLedger.Snapshot empty = snapshot(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        assertFalse(ledger.observe(empty).isPresent());
        assertFalse(ledger.observe(empty).isPresent());
        assertFalse(ledger.observe(snapshot(0, GrandExchangeOfferState.EMPTY, 995, 1, 1, 1, 1)).isPresent());

        GeOfferLedger.Snapshot offer = snapshot(0, GrandExchangeOfferState.SELLING, 995, 100, 0, 100, 0);
        GeOfferLedger.Transition placed = ledger.observe(offer).get();
        assertEquals(GeOfferLedger.TransitionKind.PLACED, placed.getKind());
        assertFalse(placed.hasComparableProgress());
        assertFalse(ledger.observe(offer).isPresent());
    }

    @Test
    public void buyAndSellProgressExposeRawQuantityAndSpentDifferences()
    {
        GeOfferLedger ledger = new GeOfferLedger();
        ledger.observe(snapshot(0, GrandExchangeOfferState.BUYING, 4151, 10, 0, 2_000_000, 0));
        GeOfferLedger.Transition buy = ledger.observe(
            snapshot(0, GrandExchangeOfferState.BUYING, 4151, 10, 3, 2_000_000, 5_970_000)).get();
        assertEquals(GeOfferLedger.TransitionKind.UPDATED, buy.getKind());
        assertTrue(buy.hasComparableProgress());
        assertEquals(3, buy.getQuantityTradedDelta());
        assertEquals(5_970_000, buy.getSpentDelta());

        ledger.observe(snapshot(1, GrandExchangeOfferState.SELLING, 995, 100, 0, 120, 0));
        GeOfferLedger.Transition sell = ledger.observe(
            snapshot(1, GrandExchangeOfferState.SELLING, 995, 100, 4, 120, 470)).get();
        assertEquals(GeOfferLedger.TransitionKind.UPDATED, sell.getKind());
        assertTrue(sell.hasComparableProgress());
        assertEquals(4, sell.getQuantityTradedDelta());
        assertEquals(470, sell.getSpentDelta());
        // These are raw API deltas only; this model makes no sell proceeds/tax claim.
    }

    @Test
    public void cancellationAndSlotReplacementAreDistinctAndDoNotShareDeltas()
    {
        GeOfferLedger ledger = new GeOfferLedger();
        ledger.observe(snapshot(2, GrandExchangeOfferState.BUYING, 4151, 10, 3, 2_000_000, 5_970_000));
        GeOfferLedger.Transition cancelled = ledger.observe(
            snapshot(2, GrandExchangeOfferState.CANCELLED_BUY, 4151, 10, 3, 2_000_000, 5_970_000)).get();
        assertEquals(GeOfferLedger.TransitionKind.CANCELLED, cancelled.getKind());
        assertTrue(cancelled.hasComparableProgress());

        GeOfferLedger.Transition replacement = ledger.observe(
            snapshot(2, GrandExchangeOfferState.SELLING, 995, 100, 0, 120, 0)).get();
        assertEquals(GeOfferLedger.TransitionKind.PLACED, replacement.getKind());
        assertFalse(replacement.hasComparableProgress());

        GeOfferLedger.Transition liveReplacement = ledger.observe(
            snapshot(2, GrandExchangeOfferState.BUYING, 4151, 10, 0, 2_000_000, 0)).get();
        assertEquals(GeOfferLedger.TransitionKind.REPLACED, liveReplacement.getKind());
        assertFalse(liveReplacement.hasComparableProgress());

        GeOfferLedger.Transition cleared = ledger.observe(
            snapshot(2, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0)).get();
        assertEquals(GeOfferLedger.TransitionKind.CLEARED, cleared.getKind());
    }

    @Test
    public void backwardsProgressIsPreservedForAuditButNotComparableEvidence()
    {
        GeOfferLedger ledger = new GeOfferLedger();
        ledger.observe(snapshot(5, GrandExchangeOfferState.SELLING, 995, 100, 8, 120, 940));

        GeOfferLedger.Transition reordered = ledger.observe(
            snapshot(5, GrandExchangeOfferState.SELLING, 995, 100, 3, 120, 350)).get();

        assertFalse(reordered.hasComparableProgress());
        assertEquals(-5, reordered.getQuantityTradedDelta());
        assertEquals(-590, reordered.getSpentDelta());
    }

    @Test
    public void relogSeedReplacesOldBaselinesAndSuppressesTheOfferReplay()
    {
        GeOfferLedger ledger = new GeOfferLedger();
        ledger.observe(snapshot(3, GrandExchangeOfferState.BUYING, 4151, 10, 2, 2_000_000, 3_980_000));

        ledger.beginLoginSeed();
        assertTrue(ledger.snapshots().isEmpty());
        assertFalse(ledger.observe(snapshot(3, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0)).isPresent());
        assertFalse(ledger.observe(snapshot(3, GrandExchangeOfferState.BUYING, 4151, 10, 2, 2_000_000, 3_980_000)).isPresent());
        ledger.finishLoginSeed();

        GeOfferLedger.Transition progress = ledger.observe(
            snapshot(3, GrandExchangeOfferState.BUYING, 4151, 10, 5, 2_000_000, 9_950_000)).get();
        assertEquals(3, progress.getQuantityTradedDelta());
        assertEquals(5_970_000, progress.getSpentDelta());
    }

    @Test
    public void snapshotsExposeAnImmutableSlotMapAndImmutableValues()
    {
        GeOfferLedger ledger = new GeOfferLedger();
        ledger.observe(snapshot(4, GrandExchangeOfferState.SELLING, 995, 100, 0, 120, 0));

        Map<Integer, GeOfferLedger.Snapshot> snapshots = ledger.snapshots();
        assertNotNull(snapshots.get(4));
        try
        {
            snapshots.clear();
            throw new AssertionError("snapshot map should be immutable");
        }
        catch (UnsupportedOperationException expected)
        {
            // Expected: callers cannot mutate ledger state through the returned map.
        }
        assertEquals(1, ledger.snapshots().size());
    }

    private static GeOfferLedger.Snapshot snapshot(
        int slot,
        GrandExchangeOfferState state,
        int itemId,
        int totalQuantity,
        int quantityTraded,
        int price,
        int spent)
    {
        return new GeOfferLedger.Snapshot(
            slot, state, itemId, totalQuantity, quantityTraded, price, spent);
    }
}
