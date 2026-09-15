package com.gpmanager.grounditems;

import com.gpmanager.LootPresentationFilter;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.reward.RewardSourceKind;
import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Filtering must never rewrite revenue / costs / net on the active session.
 */
public class LootFilterAccountingIsolationTest
{
    @Test
    public void filteringPresentationLeavesSessionTotalsUnchanged()
    {
        GroundItemsConfigSnapshot snap = new GroundItemsConfigSnapshot(
            true,
            "Armadyl hilt",
            "Bones",
            false,
            true,
            0,
            GroundItemsConfigSnapshot.ValueMode.HIGHEST,
            Color.MAGENTA, Color.WHITE, Color.GRAY,
            Collections.emptyList());
        LootPresentationFilterService filter = new LootPresentationFilterService(snap);

        long revenue = 12_000_050L;
        long costs = 800L;
        long net = revenue - costs;

        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Kree'arra",
            "enc-boss",
            Arrays.asList(
                new RewardItem(526, "Bones", 1L, 50L, true, ItemPriceSource.GRAND_EXCHANGE),
                new RewardItem(2, "Armadyl hilt", 1L, 12_000_000L, true, ItemPriceSource.GRAND_EXCHANGE)),
            1_000L,
            true);

        FilteredRewardView view = filter.filterReward(
            rewards.current(), LootPresentationFilter.HIGHLIGHTED_LIST_ONLY, false);
        assertTrue(view.isPartiallyFiltered());
        assertEquals(12_000_000L, view.getVisibleLootTotal());

        ItemFlow bones = new ItemFlow(526, "Bones", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE);
        assertFalse(filter.isFlowVisible(bones, LootPresentationFilter.HIGHLIGHTED_LIST_ONLY, false));

        // Presentation filter never mutates accounting inputs or complete observation.
        assertEquals(2, rewards.current().getItems().size());
        assertEquals(12_000_050L, rewards.current().getLootValueTotal());
        assertEquals(net, revenue - costs);
        assertEquals(revenue, 12_000_050L);
        assertEquals(costs, 800L);
    }
}
