package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Bank-evidence boundaries around burials: closing the bank must not hide a burial as
 * a transfer, while a deposit that started with the bank open still settles as one.
 * Companion to {@link ConsumptionBurialEngineTest}; these are the independent review
 * probes (September 2026) with their own minimal fixture.
 */
public class BankBoundaryBurialTest
{
    @Test
    public void recentBankEvidenceMustNotHideBurialAfterClosingBank()
    {
        Am engine = engine();
        engine.rm(1000L);
        Cc bones = new Cc(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.ze(6);
        engine.adj(bones, 1000L);
        engine.yu();
        engine.yz();
        engine.adj(Cc.empty(), 1600L);
        engine.adj(Cc.empty(), 2200L);
        Ac transaction = engine.adj(Cc.empty(), 2800L);
        assertNotNull(transaction);
        assertEquals("burial after bank close must be a cost", Ai.CONSUMPTION, transaction.getType());
        assertEquals(35L, engine.getMetrics(2800L).costs);
    }


    @Test
    public void pendingDepositStartedWhileBankOpenStillSettlesAsTransferAfterClose()
    {
        Am engine = engine();
        engine.rm(1000L);
        Cc bones = new Cc(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.ze(6);
        engine.yz();
        engine.markContext(Aj.TRANSFER, 6, "Bank container transfer");
        engine.adj(Cc.empty(), 1600L);
        engine.yu();
        engine.adj(Cc.empty(), 2200L);
        Ac transaction = engine.adj(Cc.empty(), 2800L);
        assertNotNull(transaction);
        assertEquals(Ai.TRANSFER, transaction.getType());
        assertFalse(transaction.isCounted());
    }


    @Test
    public void hardBankEvidenceThenIdleTickThenInventoryStillSettlesAsTransfer()
    {
        Am engine = engine();
        engine.rm(1000L);
        Cc bones = new Cc(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.markContext(Aj.TRANSFER, 6, "Bank container transfer");
        engine.adj(bones, 1000L);
        engine.yu();
        engine.adj(bones, 1600L);
        engine.yz();
        engine.adj(Cc.empty(), 2200L);
        engine.adj(Cc.empty(), 2800L);
        Ac transaction = engine.adj(Cc.empty(), 3400L);
        assertNotNull(transaction);
        assertEquals(Ai.TRANSFER, transaction.getType());
        assertFalse(transaction.isCounted());
        assertEquals(0L, engine.getMetrics(3400L).costs);
    }

    private static Am engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 2;
            }

            @Override
            public boolean keepTransferAuditRows()
            {
                return true;
            }
        };
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            deltas.forEach((id, quantity) ->
                flows.add(new Ab(id, "Bones", quantity, 35, quantity * 35L)));
            return flows;
        }, new TransactionClassifier(), config);
    }
}
