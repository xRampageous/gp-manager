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
            public LootPresentationFilter lootPresentationFilter() { return LootPresentationFilter.HIGHLIGHTED_LIST_ONLY; }
        };
        Engine engine = new Engine(deltas -> {
            java.util.List<Flow> flows = new java.util.ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new Flow(id, "Bones", qty, 500, qty * 500)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.setContributionEligibility(new LootPresentationFilterService(new GroundItemsConfigSnapshot(
            true, "Feather", "Bones", false)));
        engine.ensureSession(1000);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markLootContext(Collections.singletonMap(526, 2L), 20, "Loot from Chicken", "Chicken");
        assertEquals(0, engine.getMetrics(1600).net);
        ContainerSnapshot picked = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.markInventoryDirty();
        engine.processIfDirty(picked, 2200);
        engine.processIfDirty(picked, 2800);
        assertEquals(500, engine.getMetrics(2800).net);
        engine.markBankInterfaceOpen(6);
        engine.markInventoryDirty();
        engine.processIfDirty(ContainerSnapshot.empty(), 3400);
        engine.processIfDirty(ContainerSnapshot.empty(), 4000);
        assertEquals(500, engine.getMetrics(4000).net);
    }
}
