package com.gpmanager.model;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ItemSplitAccountingTest
{
    @Test
    public void applyItemSplitKeepsPersonalShareAndRecordsUndoableCorrection()
    {
        ProfitSession session = new ProfitSession("Split", 0L);
        long priceCapturedAt = 1_725_000_000_123L;
        ProfitTransaction loot = new ProfitTransaction(
            1_000L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot from Corp",
            true,
            Collections.singletonList(new ItemFlow(995, "Coins", 10, 1, 10,
                ItemPriceSource.FACE_VALUE, priceCapturedAt)));
        session.addTransaction(loot, 50);

        assertEquals(10L, session.metrics(2_000L, 60_000L).getNet());
        assertTrue(session.applyItemSplit(loot.getId(), 995, 4L, 3_000L, "team"));
        assertEquals(4L, session.metrics(4_000L, 60_000L).getNet());
        assertEquals(priceCapturedAt, loot.getFlows().get(0).getPriceCapturedAtEpochMillis());
        assertTrue(ItemSplitAccounting.isSplitReason(loot.getExplanation()));
        assertTrue(ItemSplitAccounting.isSplitReason(loot.getCorrectionReason()));
        assertEquals(1, session.getCorrectionHistory().size());
        assertTrue(session.getCorrectionHistory().get(0).hasFlowSnapshot());
        assertEquals(priceCapturedAt,
            session.getCorrectionHistory().get(0).getFlowSnapshot().get(0).getPriceCapturedAtEpochMillis());

        assertTrue(session.undoLastCorrection(5_000L));
        assertEquals(10L, session.metrics(6_000L, 60_000L).getNet());
        assertEquals(priceCapturedAt, loot.getFlows().get(0).getPriceCapturedAtEpochMillis());
        assertFalse(ItemSplitAccounting.isSplitReason(loot.getExplanation()));
    }

    @Test
    public void fullGiveawayIgnoresSingleItemGain()
    {
        ProfitSession session = new ProfitSession("Split", 0L);
        ProfitTransaction loot = new ProfitTransaction(
            1_000L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot",
            true,
            Collections.singletonList(new ItemFlow(526, "Bones", 3, 50, 150)));
        session.addTransaction(loot, 50);

        assertTrue(session.applyItemSplit(loot.getId(), 526, 0L, 2_000L, null));
        assertEquals(TransactionCorrection.IGNORE, loot.getCorrection());
        assertEquals(0L, session.metrics(3_000L, 60_000L).getNet());
        assertTrue(session.undoLastCorrection(4_000L));
        assertEquals(TransactionCorrection.AUTO, loot.getCorrection());
        assertEquals(150L, session.metrics(5_000L, 60_000L).getNet());
    }

    @Test
    public void reasonFormatIsStableForLedgerFilter()
    {
        String reason = ItemSplitAccounting.reason(2, 5, "Alice");
        assertTrue(reason, reason.startsWith("Split keep 2/5"));
        assertTrue(reason, reason.contains("Split share"));
        assertTrue(ItemSplitAccounting.isSplitReason(reason));
    }
}
