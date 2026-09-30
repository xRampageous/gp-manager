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
        Ad session = new Ad("Split", 0L);
        long priceCapturedAt = 1_725_000_000_123L;
        Ac loot = Tx.of(
            1_000L,
            Ai.LOOT,
            Aj.LOOT,
            "Loot from Corp",
            true,
            Collections.singletonList(new Ab(995, "Coins", 10, 1, 10,
                Av.FACE_VALUE, priceCapturedAt)));
        session.kf(loot, 50);

        assertEquals(10L, session.metrics(2_000L, 60_000L).net);
        assertTrue(session.kr(loot.getId(), 995, 4L, 3_000L, "team"));
        assertEquals(4L, session.metrics(4_000L, 60_000L).net);
        assertEquals(priceCapturedAt, loot.getFlows().get(0).getPriceCapturedAtEpochMillis());
        assertTrue(Dm.xr(loot.getExplanation()));
        assertTrue(Dm.xr(loot.tq()));
        assertEquals(1, ModelProbe.activeCorrections(session).size());
        assertTrue(ModelProbe.activeCorrections(session).get(0).getChanges().get(0).hasFlowSnapshot());
        assertEquals(priceCapturedAt,
            ModelProbe.activeCorrections(session).get(0).getChanges().get(0).getFlowSnapshot().get(0).getPriceCapturedAtEpochMillis());

        assertTrue(session.akb(5_000L));
        assertEquals(10L, session.metrics(6_000L, 60_000L).net);
        assertEquals(priceCapturedAt, loot.getFlows().get(0).getPriceCapturedAtEpochMillis());
        assertFalse(Dm.xr(loot.getExplanation()));
    }

    @Test
    public void fullGiveawayIgnoresSingleItemGain()
    {
        Ad session = new Ad("Split", 0L);
        Ac loot = Tx.of(
            1_000L,
            Ai.LOOT,
            Aj.LOOT,
            "Loot",
            true,
            Collections.singletonList(new Ab(526, "Bones", 3, 50, 150)));
        session.kf(loot, 50);

        assertTrue(session.kr(loot.getId(), 526, 0L, 2_000L, null));
        assertEquals(Ah.IGNORE, loot.getCorrection());
        assertEquals(0L, session.metrics(3_000L, 60_000L).net);
        assertTrue(session.akb(4_000L));
        assertEquals(Ah.AUTO, loot.getCorrection());
        assertEquals(150L, session.metrics(5_000L, 60_000L).net);
    }

    @Test
    public void reasonFormatIsStableForLedgerFilter()
    {
        String reason = Dm.reason(2, 5, "Alice");
        assertTrue(reason, reason.startsWith("Split keep 2/5"));
        assertTrue(reason, reason.contains("Split share"));
        assertTrue(Dm.xr(reason));
    }
}
