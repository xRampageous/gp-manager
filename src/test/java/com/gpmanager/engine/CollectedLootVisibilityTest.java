package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.LootPresentationFilter;
import com.gpmanager.grounditems.GroundItemsConfigSnapshot;
import com.gpmanager.grounditems.LootPresentationFilterService;
import com.gpmanager.model.ItemFlow;
import java.awt.Color;
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
        GpManagerEngine engine = new GpManagerEngine(deltas -> {
            java.util.List<ItemFlow> flows = new java.util.ArrayList<>();
            deltas.forEach((id, qty) -> flows.add(new ItemFlow(id, "Bones", qty, 500, qty * 500)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.setContributionEligibility(new LootPresentationFilterService(new GroundItemsConfigSnapshot(
            true, "Feather", "Bones", false, true, 0, GroundItemsConfigSnapshot.ValueMode.HIGHEST,
            Color.MAGENTA, Color.WHITE, Color.GRAY, Collections.emptyList())));
        engine.ensureSession(1000);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markLootContext(Collections.singletonMap(526, 2L), 20, "Loot from Chicken", "Chicken");
        assertEquals(0, engine.getMetrics(1600).getNet());
        ContainerSnapshot picked = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.markInventoryDirty();
        engine.processIfDirty(picked, 2200);
        engine.processIfDirty(picked, 2800);
        assertEquals(500, engine.getMetrics(2800).getNet());
        assertEquals(500, engine.getTrackingInsights(0, 2800).getNet());
        engine.markBankInterfaceOpen(6);
        engine.markInventoryDirty();
        engine.processIfDirty(ContainerSnapshot.empty(), 3400);
        engine.processIfDirty(ContainerSnapshot.empty(), 4000);
        assertEquals(500, engine.getMetrics(4000).getNet());
    }
}
