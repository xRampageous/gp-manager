package com.gpmanager;

import java.util.Collections;
import java.util.List;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.BOUGHT;
import static net.runelite.api.GrandExchangeOfferState.BUYING;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL;
import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Per-slot offer baseline contract of the custody ledger: exact cumulative deltas, duplicate and
 * no-progress screening, desync rejection, Modify Offer, Repeat Offer identity and login seeding.
 */
public class GeOfferBaselineTest
{
    private final Bj offers = new Bj();
    private final GeCustodyLedger custody = new GeCustodyLedger();
    private long now = 1_000L;

    private static Bj.Snapshot snap(int slot, GrandExchangeOfferState state, int item,
        int total, int traded, int price, int spent)
    {
        return new Bj.Snapshot(slot, state, item, total, traded, price, spent);
    }

    /** Feed one snapshot through the offer ledger into custody; returns any Review handoffs. */
    private List<Ac> step(Bj.Snapshot snapshot)
    {
        now += 600L;
        Bj.Transition transition = offers.observe(snapshot).orElse(null);
        return transition == null ? Collections.emptyList()
            : custody.ack(transition, "Item", now, "s1");
    }

    /** The slot's newest lifecycle. */
    private Aa record(int slot)
    {
        Aa newest = null;
        for (Aa record : custody.aji())
        {
            if (record.slot == slot && (newest == null
                || record.getPlacedAtEpochMillis() >= newest.getPlacedAtEpochMillis()))
            {
                newest = record;
            }
        }
        return newest;
    }

    @Test
    public void exactPartialFillDeltasAreDerivedFromCumulativeSnapshots()
    {
        step(snap(0, BUYING, 10, 100, 0, 7, 0));

        step(snap(0, BUYING, 10, 100, 4, 7, 27));
        assertEquals(4L, record(0).getFilledQty());
        assertEquals(27L, record(0).getSpentGp());

        step(snap(0, BOUGHT, 10, 100, 10, 7, 69));
        assertEquals(10L, record(0).getFilledQty());
        assertEquals(69L, record(0).getSpentGp());
        assertEquals("BOUGHT", record(0).getOfferState());
    }

    @Test
    public void duplicateAndNoProgressSnapshotsAreIdempotent()
    {
        step(snap(0, SELLING, 10, 10, 0, 7, 0));
        step(snap(0, SELLING, 10, 10, 3, 7, 21));

        // The identical replay is suppressed and changes nothing.
        step(snap(0, SELLING, 10, 10, 3, 7, 21));
        assertEquals(3L, record(0).getFilledQty());
        assertEquals(21L, record(0).getSpentGp());
        assertEquals(1, custody.aji().size());
    }

    @Test
    public void backwardsCountersAreDesyncNotFill()
    {
        step(snap(0, BUYING, 10, 10, 0, 7, 0));
        step(snap(0, BUYING, 10, 10, 6, 7, 42));

        step(snap(0, BUYING, 10, 10, 2, 7, 14));
        assertEquals("backwards counters are never a fill", 6L, record(0).getFilledQty());
        assertEquals(Aa.Confidence.AMBIGUOUS, record(0).getConfidence());

        // Later progress counts from the desynced baseline, not the old one.
        step(snap(0, BUYING, 10, 10, 5, 7, 35));
        assertEquals(9L, record(0).getFilledQty());
    }

    @Test
    public void valueWithoutQuantityIsDesyncNotFill()
    {
        step(snap(0, SELLING, 10, 10, 0, 7, 0));
        step(snap(0, SELLING, 10, 10, 2, 7, 14));

        step(snap(0, SELLING, 10, 10, 2, 7, 20));
        assertEquals(14L, record(0).getSpentGp());
        assertEquals(Aa.Confidence.AMBIGUOUS, record(0).getConfidence());
        assertFalse(record(0).fillTaxExact);
    }

    @Test
    public void terminalTransitionProcessesFinalFillBeforeCancellation()
    {
        step(snap(0, SELLING, 10, 100, 0, 7, 0));
        step(snap(0, SELLING, 10, 100, 20, 7, 140));

        step(snap(0, CANCELLED_SELL, 10, 100, 30, 7, 210));
        assertEquals(30L, record(0).getFilledQty());
        assertEquals(210L, record(0).getSpentGp());
        assertEquals("CANCELLED_SELL", record(0).getOfferState());
    }

    @Test
    public void priceOnlyModifyKeepsTheLifecycleAndQuantityModifyFlags()
    {
        step(snap(0, SELLING, 10, 100, 0, 7, 0));
        String placed = record(0).getOfferId();

        step(snap(0, SELLING, 10, 100, 0, 8, 0));
        assertEquals("a price Modify keeps the lifecycle", placed, record(0).getOfferId());
        assertEquals(8L, record(0).getListedPrice());
        assertFalse(record(0).quantityModified);

        step(snap(0, SELLING, 10, 150, 0, 8, 0));
        assertEquals(placed, record(0).getOfferId());
        assertEquals(150L, record(0).getOfferedQty());
        assertTrue(record(0).quantityModified);

        // Fills after a Modify still count from the unchanged cumulative counters.
        step(snap(0, SELLING, 10, 150, 5, 8, 40));
        assertEquals(5L, record(0).getFilledQty());
    }

    @Test
    public void repeatOfferIsANewLifecycleAfterTerminalClear()
    {
        step(snap(0, SELLING, 10, 5, 0, 7, 0));
        step(snap(0, SOLD, 10, 5, 5, 7, 35));
        Aa first = record(0);
        step(snap(0, EMPTY, 0, 0, 0, 0, 0));
        assertTrue(first.cleared);

        step(snap(0, SELLING, 10, 5, 0, 7, 0));
        assertNotEquals(first.getOfferId(), record(0).getOfferId());
        assertEquals("a repeat offer starts from zero progress", 0L, record(0).getFilledQty());
    }

    @Test
    public void replacedIdentityNeverInheritsProgress()
    {
        step(snap(0, SELLING, 10, 5, 0, 7, 0));
        step(snap(0, SELLING, 10, 5, 3, 7, 21));
        Aa old = record(0);

        step(snap(0, SELLING, 11, 5, 3, 7, 21));
        assertFalse("the replaced lifecycle is handed off", custody.aji().contains(old));
        Aa replacement = record(0);
        assertEquals(11, replacement.itemId);
        assertEquals("the replacement is quarantined", Aa.Confidence.AMBIGUOUS,
            replacement.getConfidence());
        assertEquals(3L, replacement.getFilledQty());
    }

    @Test
    public void seedBaselineNeverReplaysExistingProgress()
    {
        offers.lu();
        offers.observe(snap(0, SELLING, 10, 100, 40, 7, 280));
        offers.tg();
        custody.avh(offers.snapshots(), now, "s1");
        assertEquals(40L, record(0).getFilledQty());

        step(snap(0, SELLING, 10, 100, 45, 7, 315));
        assertEquals(45L, record(0).getFilledQty());
        assertEquals(315L, record(0).getSpentGp());
    }

    @Test
    public void missedPlacementIsQuarantinedWithoutAFill()
    {
        // The offer ledger saw the slot, but custody never saw its placement.
        offers.observe(snap(0, SELLING, 10, 100, 40, 7, 280));

        step(snap(0, SELLING, 10, 100, 45, 7, 315));
        assertEquals(Aa.Confidence.LEGACY_UNBASED, record(0).getConfidence());
        assertEquals(0L, record(0).getFilledQty());
    }
}
