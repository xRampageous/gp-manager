package com.gpmanager;

import java.util.Collections;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.*;

/** Window closure, leftover removal, and the bounded journal's overlap memory. */
public class ChargeReconciliationWindowTest
{
    private static final long T0 = 10_000L;

    @Test
    public void clearingAFamilyKeepsTheReceiptBoundHonest()
    {
        ChargeEstimateJournal journal = new ChargeEstimateJournal();
        for (int i = 0; i < ChargeEstimateJournal.MAX_RECEIPTS; i++)
        {
            journal.record("s1", "slot", "seas", "tx" + i, 1, 1L);
        }
        journal.clearFamily("s1", "seas");
        for (int i = 0; i < ChargeEstimateJournal.MAX_RECEIPTS; i++)
        {
            journal.record("s1", "slot", "seas", "ty" + i, 1, 1L);
        }
        assertEquals("a cleared family must not evict its replacements early",
            ChargeEstimateJournal.MAX_RECEIPTS, journal.receipts("s1", "slot", 1).size());
    }

    @Test
    public void evictedReceiptsKeepTheirWindowOverlapUntilCleared()
    {
        ChargeEstimateJournal journal = new ChargeEstimateJournal();
        for (int i = 0; i < ChargeEstimateJournal.MAX_RECEIPTS + 3; i++)
        {
            journal.record("s1", "slot", "seas", "tx" + i, 1, 2L);
        }
        assertEquals("evicted quantities stay visible to reconciliation", 6L,
            journal.evictedOverlap("s1", "slot", 1));
        journal.clearFamily("s1", "seas");
        assertEquals(0L, journal.evictedOverlap("s1", "slot", 1));
    }

    @Test
    public void leftoverEstimatesAreRemovedWhenACheckClosesTheWindow()
    {
        Am engine = engine();
        engine.rm(T0);
        Ac first = estimate(engine, T0 + 1L);
        Ac second = estimate(engine, T0 + 2L);
        Ac third = estimate(engine, T0 + 3L);

        Cm delta = new Cm(Ar.V.TRIDENT_SEAS,
            Collections.singletonList(new Cm.ComponentDelta(ItemID.DEATHRUNE, -1L)));
        Ac measured = engine.mj(delta, "Trident of the seas",
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -1L, 100)), T0 + 4L, "slot-a");

        assertNull("the surviving estimate already booked the measured unit", measured);
        assertEquals(1L, first.quantity(ItemID.DEATHRUNE, false));
        assertEquals(0L, second.quantity(ItemID.DEATHRUNE, false));
        assertEquals(0L, third.quantity(ItemID.DEATHRUNE, false));
        assertEquals(Ah.IGNORE, second.getCorrection());
        assertEquals(Ah.IGNORE, third.getCorrection());
        assertEquals(0L, engine.chargeEstimateJournal.adp(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.DEATHRUNE));
    }

    @Test
    public void aCompatibleCheckThatMovedNothingClosesTheWindow()
    {
        Am engine = engine();
        engine.rm(T0);
        Ac first = estimate(engine, T0 + 1L);
        Ac second = estimate(engine, T0 + 2L);

        engine.closeChargeWindow("slot-a");

        assertEquals(0L, first.quantity(ItemID.DEATHRUNE, false));
        assertEquals(0L, second.quantity(ItemID.DEATHRUNE, false));
        assertEquals(Ah.IGNORE, first.getCorrection());
        assertEquals(Ah.IGNORE, second.getCorrection());
        assertEquals(0L, engine.chargeEstimateJournal.adp(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.DEATHRUNE));
    }

    private static Ac estimate(Am engine, long at)
    {
        return engine.mn("slot-a", "seas", Au.CAST,
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -1L, 100)), at);
    }

    private static Ab flow(int id, String name, long quantity, int unit)
    {
        return new Ab(id, name, quantity, unit, quantity * unit, Av.GRAND_EXCHANGE);
    }

    private static Am engine()
    {
        return new Am(deltas -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig() {});
    }
}
