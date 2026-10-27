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
    public void clearingATargetKeepsTheReceiptBoundHonest()
    {
        ChargeEstimateJournal journal = new ChargeEstimateJournal();
        for (int i = 0; i < ChargeEstimateJournal.MAX_RECEIPTS; i++)
        {
            journal.record("s1", "slot", "tx" + i, 1, 1L);
        }
        journal.clearTarget("s1", "slot");
        for (int i = 0; i < ChargeEstimateJournal.MAX_RECEIPTS; i++)
        {
            journal.record("s1", "slot", "ty" + i, 1, 1L);
        }
        assertEquals("a cleared target must not evict its replacements early",
            ChargeEstimateJournal.MAX_RECEIPTS, journal.receipts("s1", "slot", 1).size());
    }

    @Test
    public void evictedReceiptsKeepTheirWindowOverlapUntilCleared()
    {
        ChargeEstimateJournal journal = new ChargeEstimateJournal();
        for (int i = 0; i < ChargeEstimateJournal.MAX_RECEIPTS + 3; i++)
        {
            journal.record("s1", "slot", "tx" + i, 1, 2L);
        }
        assertEquals("evicted quantities stay visible to reconciliation", 6L,
            journal.evictedOverlap("s1", "slot", 1));
        journal.clearTarget("s1", "slot");
        assertEquals(0L, journal.evictedOverlap("s1", "slot", 1));
    }

    @Test
    public void leftoverEstimatesAreRemovedWhenACheckClosesTheWindow()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        Transaction first = estimate(engine, T0 + 1L);
        Transaction second = estimate(engine, T0 + 2L);
        Transaction third = estimate(engine, T0 + 3L);

        ChargeDelta delta = new ChargeDelta(ChargeCheck.ChargedItem.TRIDENT_SEAS,
            Collections.singletonList(new ChargeDelta.ComponentDelta(ItemID.DEATHRUNE, -1L)), ChargeDelta.CheckResult.SPEND);
        Transaction measured = engine.bookChargeSpend(delta, "Trident of the seas",
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -1L, 100)), T0 + 4L, "slot-a");

        assertNull("the surviving estimate already booked the measured unit", measured);
        assertEquals(1L, first.quantity(ItemID.DEATHRUNE, false));
        assertEquals(0L, second.quantity(ItemID.DEATHRUNE, false));
        assertEquals(0L, third.quantity(ItemID.DEATHRUNE, false));
        assertEquals(Correction.IGNORE, second.correction());
        assertEquals(Correction.IGNORE, third.correction());
        assertEquals(0L, ChargeProbes.adp(engine.estimate,
            engine.session().id(), "slot-a", "seas", ItemID.DEATHRUNE));
    }

    @Test
    public void aCompatibleCheckThatMovedNothingClosesTheWindow()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        Transaction first = estimate(engine, T0 + 1L);
        Transaction second = estimate(engine, T0 + 2L);

        engine.closeChargeWindow("slot-a");

        assertEquals(0L, first.quantity(ItemID.DEATHRUNE, false));
        assertEquals(0L, second.quantity(ItemID.DEATHRUNE, false));
        assertEquals(Correction.IGNORE, first.correction());
        assertEquals(Correction.IGNORE, second.correction());
        assertEquals(0L, ChargeProbes.adp(engine.estimate,
            engine.session().id(), "slot-a", "seas", ItemID.DEATHRUNE));
    }

    private static Transaction estimate(Engine engine, long at)
    {
        return engine.bookEstimatedCharge("slot-a", "seas", ActionKind.CAST,
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -1L, 100)), at);
    }

    private static Flow flow(int id, String name, long quantity, int unit)
    {
        return new Flow(id, name, quantity, unit, quantity * unit, PriceSource.GRAND_EXCHANGE);
    }

    private static Engine engine()
    {
        return new Engine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig() {});
    }
}
