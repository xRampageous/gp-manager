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
        engine.bankOpened(6);
        engine.processIfDirty(bones, 1000L, null);
        engine.bankClosed();
        engine.markDirty();
        engine.processIfDirty(FixtureReads.empty(), 1600L, null);
        engine.processIfDirty(FixtureReads.empty(), 2200L, null);
        Transaction transaction = engine.processIfDirty(FixtureReads.empty(), 2800L, null);
        assertNotNull(transaction);
        assertEquals("burial after bank close must be a cost", TransactionType.CONSUMPTION, transaction.type());
        assertEquals(35L, engine.metrics(2800L).cost);
    }


    @Test
    public void pendingDepositStartedWhileBankOpenStillSettlesAsTransferAfterClose()
    {
        Engine engine = engine();
        engine.ensureSession(1000L);
        ContainerSnapshot bones = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.bankOpened(6);
        engine.markDirty();
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.processIfDirty(FixtureReads.empty(), 1600L, null);
        engine.bankClosed();
        engine.processIfDirty(FixtureReads.empty(), 2200L, null);
        Transaction transaction = engine.processIfDirty(FixtureReads.empty(), 2800L, null);
        assertNotNull(transaction);
        assertEquals(TransactionType.TRANSFER, transaction.type());
        assertFalse(transaction.counts());
    }


    @Test
    public void hardBankEvidenceThenIdleTickThenInventoryStillSettlesAsTransfer()
    {
        Engine engine = engine();
        engine.ensureSession(1000L);
        ContainerSnapshot bones = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.markContext(Context.TRANSFER, 6, "Bank container transfer");
        engine.processIfDirty(bones, 1000L, null);
        engine.bankClosed();
        engine.processIfDirty(bones, 1600L, null);
        engine.markDirty();
        engine.processIfDirty(FixtureReads.empty(), 2200L, null);
        engine.processIfDirty(FixtureReads.empty(), 2800L, null);
        Transaction transaction = engine.processIfDirty(FixtureReads.empty(), 3400L, null);
        assertNotNull(transaction);
        assertEquals(TransactionType.TRANSFER, transaction.type());
        assertFalse(transaction.counts());
        assertEquals(0L, engine.metrics(3400L).cost);
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
