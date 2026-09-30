package com.gpmanager;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollectedLootVisibilityTest
{
    @Test public void hiddenLootCountsOnlyWhenCollectedAndBankingDoesNotCountAgain()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public int stabilizationTicks() { return 0; }
            public Dl lootPresentationFilter() { return Dl.HIGHLIGHTED_LIST_ONLY; }
        };
        Am engine = new Am(deltas -> {
            java.util.List<Ab> flows = new java.util.ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new Ab(id, "Bones", qty, 500, qty * 500)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.setContributionEligibility(new LootPresentationFilterService(new Bc(
            true, "Feather", "Bones", false)));
        engine.rm(1000);
        engine.setBaseline(Cc.empty());
        engine.zk(Collections.singletonMap(526, 2L), 20, "Loot from Chicken", "Chicken");
        assertEquals(0, engine.getMetrics(1600).net);
        Cc picked = new Cc(Collections.singletonMap(526, 1L));
        engine.yz();
        engine.adj(picked, 2200);
        engine.adj(picked, 2800);
        assertEquals(500, engine.getMetrics(2800).net);
        engine.ze(6);
        engine.yz();
        engine.adj(Cc.empty(), 3400);
        engine.adj(Cc.empty(), 4000);
        assertEquals(500, engine.getMetrics(4000).net);
    }
}
