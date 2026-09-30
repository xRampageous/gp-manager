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
    private Am engine()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public int stabilizationTicks() { return 2; }
            public boolean keepTransferAuditRows() { return true; }
            public Dl lootPresentationFilter() { return Dl.HIGHLIGHTED_LIST_ONLY; }
        };
        Am engine = new Am(deltas -> {
            List<Ab> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Ab(id, "Bones", quantity, 35, quantity * 35L)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.rm(1000L);
        return engine;
    }

    /** A Ground Items rule set that would show only feathers; bones are hidden from presentation. */
    private void onlyFeathers(Am engine)
    {
        engine.setContributionEligibility(new LootPresentationFilterService(new Bc(
            true, "Feather", "Bones", false)));
    }

    private Ac gain(Ab... flows)
    {
        return Tx.of(1600L, Ai.GAIN, Aj.GENERIC,
            "Pickup", true, Arrays.asList(flows));
    }

    @Test public void burialCallbackBeforeGameTickBankCloseMustNotLatchOldTransfer()
    {
        Am engine = engine();
        Cc bones = new Cc(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.ze(6);
        engine.adj(bones, 1000L);
        engine.yz();
        engine.yu();
        engine.adj(Cc.empty(), 1600L);
        engine.adj(Cc.empty(), 2200L);
        Ac tx = engine.adj(Cc.empty(), 2800L);
        assertEquals(Ai.CONSUMPTION, tx.getType());
    }

    @Test public void hiddenItemsStillCountInNetAndInsights()
    {
        Am engine = engine();
        onlyFeathers(engine);
        engine.getActiveSession().kf(gain(new Ab(526, "Bones", 1L, 35, 35L)), 100);
        assertEquals(35L, engine.getMetrics(2800L).net);
    }

    @Test public void correctionsOperateOnTheWholeCanonicalRow()
    {
        Am engine = engine();
        onlyFeathers(engine);
        Ac tx = gain(new Ab(526, "Bones", 1L, 35, 35L),
            new Ab(314, "Feather", 1L, 10, 10L));
        engine.getActiveSession().kf(tx, 100);
        assertEquals(45L, engine.getMetrics(2800L).revenue);
        engine.getActiveSession().qi(tx.getId(), Ah.REVENUE, 3000L, "Manual correction");
        assertEquals(45L, engine.getMetrics(3100L).revenue);
    }

    @Test public void compactionFoldsExactRawValues()
    {
        Am engine = engine();
        onlyFeathers(engine);
        Ad session = engine.getActiveSession();
        session.kf(gain(new Ab(526, "Bones", 1L, 35, 35L)), 1);
        session.kf(gain(new Ab(526, "Bones", 1L, 35, 35L)), 1);
        assertTrue(session.compactedTransactionCount > 0L);
        assertEquals(70L, engine.getMetrics(2800L).net);
    }
}
