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
        Session session = new Session("Split", 0L, SessionMode.GENERAL);
        long priceCapturedAt = 1_725_000_000_123L;
        Transaction loot = Tx.of(
            1_000L,
            TransactionType.LOOT,
            Context.LOOT,
            "Loot from Corp",
            true,
            Collections.singletonList(new Flow(995, "Coins", 10, 1, 10,
                PriceSource.FACE_VALUE, priceCapturedAt)));
        session.addTransaction(loot, 50, false);

        assertEquals(10L, session.metrics(2_000L).net);
        assertTrue(session.splitItem(loot.id(), 995, 4L, "team"));
        assertEquals(4L, session.metrics(4_000L).net);
        assertEquals(priceCapturedAt, loot.flows().get(0).pricedAt());
        assertTrue(ItemSplits.splitReason(loot.explanation()));
        assertTrue(ItemSplits.splitReason(loot.correctionReason()));
        assertEquals(1, ModelProbe.activeCorrections(session).size());
        assertTrue(ModelProbe.activeCorrections(session).get(0).changes().get(0).hasFlowSnapshot());
        assertEquals(priceCapturedAt,
            ModelProbe.activeCorrections(session).get(0).changes().get(0).flowSnapshot().get(0).pricedAt());

        assertTrue(session.undoCorrection());
        assertEquals(10L, session.metrics(6_000L).net);
        assertEquals(priceCapturedAt, loot.flows().get(0).pricedAt());
        assertFalse(ItemSplits.splitReason(loot.explanation()));
    }

    @Test
    public void fullGiveawayIgnoresSingleItemGain()
    {
        Session session = new Session("Split", 0L, SessionMode.GENERAL);
        Transaction loot = Tx.of(
            1_000L,
            TransactionType.LOOT,
            Context.LOOT,
            "Loot",
            true,
            Collections.singletonList(new Flow(526, "Bones", 3, 50, 150)));
        session.addTransaction(loot, 50, false);

        assertTrue(session.splitItem(loot.id(), 526, 0L, null));
        assertEquals(Correction.IGNORE, loot.correction());
        assertEquals(0L, session.metrics(3_000L).net);
        assertTrue(session.undoCorrection());
        assertEquals(Correction.AUTO, loot.correction());
        assertEquals(150L, session.metrics(5_000L).net);
    }

    @Test
    public void reasonFormatIsStableForLedgerFilter()
    {
        String reason = ItemSplits.reason(2, 5, "Alice");
        assertTrue(reason, reason.startsWith("Split keep 2/5"));
        assertTrue(reason, reason.contains("Split share"));
        assertTrue(ItemSplits.splitReason(reason));
    }
}
