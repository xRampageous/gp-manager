package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
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
        GpManagerEngine engine = engine();
        engine.ensureSession(1000L);
        ContainerSnapshot bones = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.markBankInterfaceOpen(6);
        engine.processIfDirty(bones, 1000L);
        engine.markBankInterfaceClosed();
        engine.markInventoryDirty();
        engine.processIfDirty(ContainerSnapshot.empty(), 1600L);
        engine.processIfDirty(ContainerSnapshot.empty(), 2200L);
        ProfitTransaction transaction = engine.processIfDirty(ContainerSnapshot.empty(), 2800L);
        assertNotNull(transaction);
        assertEquals("burial after bank close must be a cost", TransactionType.CONSUMPTION, transaction.getType());
        assertEquals(35L, engine.getMetrics(2800L).getCosts());
    }


    @Test
    public void pendingDepositStartedWhileBankOpenStillSettlesAsTransferAfterClose()
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1000L);
        ContainerSnapshot bones = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.markBankInterfaceOpen(6);
        engine.markInventoryDirty();
        engine.markContext(TrackingContext.TRANSFER, 6, "Bank container transfer");
        engine.processIfDirty(ContainerSnapshot.empty(), 1600L);
        engine.markBankInterfaceClosed();
        engine.processIfDirty(ContainerSnapshot.empty(), 2200L);
        ProfitTransaction transaction = engine.processIfDirty(ContainerSnapshot.empty(), 2800L);
        assertNotNull(transaction);
        assertEquals(TransactionType.TRANSFER, transaction.getType());
        assertFalse(transaction.isCounted());
    }


    @Test
    public void hardBankEvidenceThenIdleTickThenInventoryStillSettlesAsTransfer()
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1000L);
        ContainerSnapshot bones = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.markContext(TrackingContext.TRANSFER, 6, "Bank container transfer");
        engine.processIfDirty(bones, 1000L);
        engine.markBankInterfaceClosed();
        engine.processIfDirty(bones, 1600L);
        engine.markInventoryDirty();
        engine.processIfDirty(ContainerSnapshot.empty(), 2200L);
        engine.processIfDirty(ContainerSnapshot.empty(), 2800L);
        ProfitTransaction transaction = engine.processIfDirty(ContainerSnapshot.empty(), 3400L);
        assertNotNull(transaction);
        assertEquals(TransactionType.TRANSFER, transaction.getType());
        assertFalse(transaction.isCounted());
        assertEquals(0L, engine.getMetrics(3400L).getCosts());
    }

    private static GpManagerEngine engine()
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
        return new GpManagerEngine(deltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) ->
                flows.add(new ItemFlow(id, "Bones", quantity, 35, quantity * 35L)));
            return flows;
        }, new TransactionClassifier(), config);
    }
}
