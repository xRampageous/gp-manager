package com.gpmanager;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Ground Items filtering is presentation only. A live presentation filter can never change
 * canonical money, rollups or corrections. Bank-close burial remains an observed supply cost.
 */
public class PresentationFilterAccountingIsolationEngineTest
{
    private Engine engine()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public int stabilizationTicks() { return 2; }
            public boolean keepTransferAuditRows() { return true; }
            public LootPresentationFilter lootPresentationFilter() { return LootPresentationFilter.HIGHLIGHTED_LIST_ONLY; }
        };
        Engine engine = new Engine(deltas -> {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Bones", quantity, 35, quantity * 35L)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.ensureSession(1000L);
        return engine;
    }

    /** A Ground Items rule set that would show only feathers; bones are hidden from presentation. */
    private void onlyFeathers(Engine engine)
    {
        engine.setContributionEligibility(new LootPresentationFilterService(new GroundItemsConfigSnapshot(
            true, "Feather", "Bones", false)));
    }

    private Transaction gain(Flow... flows)
    {
        return Tx.of(1600L, TransactionType.GAIN, Context.GENERIC,
            "Pickup", true, Arrays.asList(flows));
    }

    @Test public void burialCallbackBeforeGameTickBankCloseMustNotLatchOldTransfer()
    {
        Engine engine = engine();
        ContainerSnapshot bones = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.markBankInterfaceOpen(6);
        engine.processIfDirty(bones, 1000L);
        engine.markInventoryDirty();
        engine.markBankInterfaceClosed();
        engine.processIfDirty(ContainerSnapshot.empty(), 1600L);
        engine.processIfDirty(ContainerSnapshot.empty(), 2200L);
        Transaction tx = engine.processIfDirty(ContainerSnapshot.empty(), 2800L);
        assertEquals(TransactionType.CONSUMPTION, tx.getType());
    }

    @Test public void hiddenItemsStillCountInNetAndInsights()
    {
        Engine engine = engine();
        onlyFeathers(engine);
        engine.getActiveSession().addTransaction(gain(new Flow(526, "Bones", 1L, 35, 35L)), 100);
        assertEquals(35L, engine.getMetrics(2800L).net);
    }

    @Test public void correctionsOperateOnTheWholeCanonicalRow()
    {
        Engine engine = engine();
        onlyFeathers(engine);
        Transaction tx = gain(new Flow(526, "Bones", 1L, 35, 35L),
            new Flow(314, "Feather", 1L, 10, 10L));
        engine.getActiveSession().addTransaction(tx, 100);
        assertEquals(45L, engine.getMetrics(2800L).revenue);
        engine.getActiveSession().correctTransaction(tx.getId(), Correction.REVENUE, 3000L, "Manual correction");
        assertEquals(45L, engine.getMetrics(3100L).revenue);
    }

    @Test public void compactionFoldsExactRawValues()
    {
        Engine engine = engine();
        onlyFeathers(engine);
        Session session = engine.getActiveSession();
        session.addTransaction(gain(new Flow(526, "Bones", 1L, 35, 35L)), 1);
        session.addTransaction(gain(new Flow(526, "Bones", 1L, 35, 35L)), 1);
        assertTrue(session.compactedTransactionCount > 0L);
        assertEquals(70L, engine.getMetrics(2800L).net);
    }
}
