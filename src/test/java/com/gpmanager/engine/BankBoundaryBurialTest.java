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
        Engine engine = engine();
        engine.ensureSession(1000L);
        ContainerSnapshot bones = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.markBankInterfaceOpen(6);
        engine.processIfDirty(bones, 1000L);
        engine.markBankInterfaceClosed();
        engine.markInventoryDirty();
        engine.processIfDirty(ContainerSnapshot.empty(), 1600L);
        engine.processIfDirty(ContainerSnapshot.empty(), 2200L);
        Transaction transaction = engine.processIfDirty(ContainerSnapshot.empty(), 2800L);
        assertNotNull(transaction);
        assertEquals("burial after bank close must be a cost", TransactionType.CONSUMPTION, transaction.getType());
        assertEquals(35L, engine.getMetrics(2800L).costs);
    }


    @Test
    public void pendingDepositStartedWhileBankOpenStillSettlesAsTransferAfterClose()
    {
        Engine engine = engine();
        engine.ensureSession(1000L);
        ContainerSnapshot bones = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.markBankInterfaceOpen(6);
        engine.markInventoryDirty();
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.processIfDirty(ContainerSnapshot.empty(), 1600L);
        engine.markBankInterfaceClosed();
        engine.processIfDirty(ContainerSnapshot.empty(), 2200L);
        Transaction transaction = engine.processIfDirty(ContainerSnapshot.empty(), 2800L);
        assertNotNull(transaction);
        assertEquals(TransactionType.TRANSFER, transaction.getType());
        assertFalse(transaction.isCounted());
    }


    @Test
    public void hardBankEvidenceThenIdleTickThenInventoryStillSettlesAsTransfer()
    {
        Engine engine = engine();
        engine.ensureSession(1000L);
        ContainerSnapshot bones = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.processIfDirty(bones, 1000L);
        engine.markBankInterfaceClosed();
        engine.processIfDirty(bones, 1600L);
        engine.markInventoryDirty();
        engine.processIfDirty(ContainerSnapshot.empty(), 2200L);
        engine.processIfDirty(ContainerSnapshot.empty(), 2800L);
        Transaction transaction = engine.processIfDirty(ContainerSnapshot.empty(), 3400L);
        assertNotNull(transaction);
        assertEquals(TransactionType.TRANSFER, transaction.getType());
        assertFalse(transaction.isCounted());
        assertEquals(0L, engine.getMetrics(3400L).costs);
    }

    private static Engine engine()
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
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) ->
                flows.add(new Flow(id, "Bones", quantity, 35, quantity * 35L)));
            return flows;
        }, new TransactionClassifier(), config);
    }
}
