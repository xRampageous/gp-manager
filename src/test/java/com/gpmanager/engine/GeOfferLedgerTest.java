package com.gpmanager;

import java.util.Map;
import java.util.Optional;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The offer ledger only screens and pairs snapshots; fill arithmetic is custody's (GeOfferBaselineTest). */
public class GeOfferLedgerTest
{
    @Test
    public void loginReplaySeedsSlotsWithoutEmittingTransitions()
    {
        OfferSlots ledger = new OfferSlots();
        ledger.beginSeed();

        assertFalse(ledger.observe(snapshot(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0)).isPresent());
        assertFalse(ledger.observe(snapshot(1, GrandExchangeOfferState.BUYING, 4151, 10, 0, 2_000_000, 0)).isPresent());
        OfferSlots.Snapshot seeded = snapshot(1, GrandExchangeOfferState.BUYING, 4151, 10, 2, 2_000_000, 3_900_000);
        assertFalse(ledger.observe(seeded).isPresent());

        ledger.finishSeed();
        Optional<OfferSlots.Transition> transition = ledger.observe(
            snapshot(1, GrandExchangeOfferState.BUYING, 4151, 10, 3, 2_000_000, 5_850_000));
        assertTrue(transition.isPresent());
        assertSame("the seeded snapshot is the baseline", seeded, transition.get().previous);
    }

    @Test
    public void repeatedSnapshotsAreIdempotentAndFirstEmptySnapshotIsOnlyABaseline()
    {
        OfferSlots ledger = new OfferSlots();
        OfferSlots.Snapshot empty = snapshot(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        assertFalse(ledger.observe(empty).isPresent());
        assertFalse(ledger.observe(empty).isPresent());
        assertFalse(ledger.observe(snapshot(0, GrandExchangeOfferState.EMPTY, 995, 1, 1, 1, 1)).isPresent());

        OfferSlots.Snapshot offer = snapshot(0, GrandExchangeOfferState.SELLING, 995, 100, 0, 100, 0);
        OfferSlots.Transition placed = ledger.observe(offer).get();
        assertEquals(GrandExchangeOfferState.EMPTY, placed.previous.state);
        assertSame(offer, placed.current);
        assertFalse(ledger.observe(offer).isPresent());

        OfferSlots.Transition cleared = ledger.observe(empty).get();
        assertSame("a cleared slot keeps the offer it cleared", offer, cleared.previous);
    }

    @Test
    public void firstObservationHasNoPrevious()
    {
        OfferSlots ledger = new OfferSlots();
        OfferSlots.Transition first = ledger.observe(
            snapshot(2, GrandExchangeOfferState.BUYING, 4151, 10, 3, 2_000_000, 5_970_000)).get();
        assertNull(first.previous);
    }

    @Test
    public void relogSeedReplacesOldBaselinesAndSuppressesTheOfferReplay()
    {
        OfferSlots ledger = new OfferSlots();
        ledger.observe(snapshot(3, GrandExchangeOfferState.BUYING, 4151, 10, 2, 2_000_000, 3_980_000));

        ledger.beginSeed();
        assertTrue(ledger.snapshots().isEmpty());
        assertFalse(ledger.observe(snapshot(3, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0)).isPresent());
        assertFalse(ledger.observe(snapshot(3, GrandExchangeOfferState.BUYING, 4151, 10, 2, 2_000_000, 3_980_000)).isPresent());
        ledger.finishSeed();

        OfferSlots.Transition progress = ledger.observe(
            snapshot(3, GrandExchangeOfferState.BUYING, 4151, 10, 5, 2_000_000, 9_950_000)).get();
        assertEquals(2, progress.previous.quantityTraded);
    }

    @Test
    public void snapshotsExposeAnImmutableSlotMapAndImmutableValues()
    {
        OfferSlots ledger = new OfferSlots();
        ledger.observe(snapshot(4, GrandExchangeOfferState.SELLING, 995, 100, 0, 120, 0));

        Map<Integer, OfferSlots.Snapshot> snapshots = ledger.snapshots();
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

    private static OfferSlots.Snapshot snapshot(
        int slot,
        GrandExchangeOfferState state,
        int itemId,
        int totalQuantity,
        int quantityTraded,
        int price,
        int spent)
    {
        return new OfferSlots.Snapshot(
            slot, state, itemId, totalQuantity, quantityTraded, price, spent);
    }
}
