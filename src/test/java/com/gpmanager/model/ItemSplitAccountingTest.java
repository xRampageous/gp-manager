package com.gpmanager;

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
        Session session = new Session("Split", 0L);
        long priceCapturedAt = 1_725_000_000_123L;
        Transaction loot = Tx.of(
            1_000L,
            TransactionType.LOOT,
            Context.LOOT,
            "Loot from Corp",
            true,
            Collections.singletonList(new Flow(995, "Coins", 10, 1, 10,
                PriceSource.FACE_VALUE, priceCapturedAt)));
        session.addTransaction(loot, 50);

        assertEquals(10L, session.metrics(2_000L).net);
        assertTrue(session.applyItemSplit(loot.getId(), 995, 4L, 3_000L, "team"));
        assertEquals(4L, session.metrics(4_000L).net);
        assertEquals(priceCapturedAt, loot.getFlows().get(0).getPriceCapturedAtEpochMillis());
        assertTrue(ItemSplitAccounting.isSplitReason(loot.getExplanation()));
        assertTrue(ItemSplitAccounting.isSplitReason(loot.getCorrectionReason()));
        assertEquals(1, ModelProbe.activeCorrections(session).size());
        assertTrue(ModelProbe.activeCorrections(session).get(0).getChanges().get(0).hasFlowSnapshot());
        assertEquals(priceCapturedAt,
            ModelProbe.activeCorrections(session).get(0).getChanges().get(0).getFlowSnapshot().get(0).getPriceCapturedAtEpochMillis());

        assertTrue(session.undoLastCorrection(5_000L));
        assertEquals(10L, session.metrics(6_000L).net);
        assertEquals(priceCapturedAt, loot.getFlows().get(0).getPriceCapturedAtEpochMillis());
        assertFalse(ItemSplitAccounting.isSplitReason(loot.getExplanation()));
    }

    @Test
    public void fullGiveawayIgnoresSingleItemGain()
    {
        Session session = new Session("Split", 0L);
        Transaction loot = Tx.of(
            1_000L,
            TransactionType.LOOT,
            Context.LOOT,
            "Loot",
            true,
            Collections.singletonList(new Flow(526, "Bones", 3, 50, 150)));
        session.addTransaction(loot, 50);

        assertTrue(session.applyItemSplit(loot.getId(), 526, 0L, 2_000L, null));
        assertEquals(Correction.IGNORE, loot.getCorrection());
        assertEquals(0L, session.metrics(3_000L).net);
        assertTrue(session.undoLastCorrection(4_000L));
        assertEquals(Correction.AUTO, loot.getCorrection());
        assertEquals(150L, session.metrics(5_000L).net);
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
