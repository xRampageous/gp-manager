package com.gpmanager.grounditems;

import com.gpmanager.LootPresentationFilter;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTargetParser;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardObservation;
import com.gpmanager.reward.RewardSourceKind;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class UniversalLootMinimumTest
{
    @Test public void minimumWorksWithoutGroundItemsAndKeepsBoundaryAndUnpricedRows()
    {
        LootPresentationFilterService filter = new LootPresentationFilterService(GroundItemsConfigSnapshot.disabled());
        // Unit-price filter: qty does not inflate past the threshold.
        RewardObservation observed = new RewardObservation("r", "r", RewardSourceKind.NPC_LOOT,
            "Goblin", "kill", 1000, Arrays.asList(
                new RewardItem(1, "Small stack", 1, 999, true, ItemPriceSource.GRAND_EXCHANGE),
                new RewardItem(2, "Boundary", 1, 1000, true, ItemPriceSource.GRAND_EXCHANGE),
                new RewardItem(3, "Unknown", 1, 0, false, ItemPriceSource.UNPRICED)), false, 1, "owner");
        FilteredRewardView visible = filter.filterReward(observed, LootPresentationFilter.ALL_ITEMS, false, 1000);
        assertEquals(2, visible.getVisibleItems().size());
        assertEquals(3, observed.getItems().size());
        assertTrue(visible.isPartiallyFiltered());
        assertTrue(filter.isFlowVisible(new ItemFlow(1, "Food", -1, 300, -300),
            LootPresentationFilter.HIGHLIGHTED_LIST_ONLY, false, 1000));
        assertFalse(filter.isFlowVisible(new ItemFlow(1, "Small stack", 1, 300, 300),
            LootPresentationFilter.ALL_ITEMS, false, 1000));
    }

    @Test public void stackedCheapJunkStaysHiddenByUnitPrice()
    {
        LootPresentationFilterService filter = new LootPresentationFilterService(GroundItemsConfigSnapshot.disabled());
        // 10 shrimp @ 20 ea = stack 200; min 200 must still hide (unit 20 < 200).
        RewardObservation shrimp = new RewardObservation("s", "s", RewardSourceKind.NPC_LOOT,
            "Fishing spot", "fish", 1000, Arrays.asList(
                new RewardItem(315, "Shrimps", 10, 200, true, ItemPriceSource.GRAND_EXCHANGE)),
            false, 1, "owner");
        FilteredRewardView visible = filter.filterReward(shrimp, LootPresentationFilter.ALL_ITEMS, false, 200);
        assertTrue(visible.getVisibleItems().isEmpty());
        assertTrue(visible.isAllFiltered());
        assertFalse(filter.isFlowVisible(new ItemFlow(315, "Shrimps", 10, 20, 200),
            LootPresentationFilter.ALL_ITEMS, false, 200));
    }

    @Test public void unitAtOrAboveMinimumShowsFromQuantityOne()
    {
        LootPresentationFilterService filter = new LootPresentationFilterService(GroundItemsConfigSnapshot.disabled());
        RewardObservation atMin = new RewardObservation("b", "b", RewardSourceKind.NPC_LOOT,
            "Goblin", "kill", 1000, Arrays.asList(
                new RewardItem(1, "Boundary", 1, 200, true, ItemPriceSource.GRAND_EXCHANGE)),
            false, 1, "owner");
        FilteredRewardView shown = filter.filterReward(atMin, LootPresentationFilter.ALL_ITEMS, false, 200);
        assertEquals(1, shown.getVisibleItems().size());

        // 2× @ 150 ea (stack 300) still hidden when min is 200.
        RewardObservation under = new RewardObservation("u", "u", RewardSourceKind.NPC_LOOT,
            "Goblin", "kill", 1000, Arrays.asList(
                new RewardItem(2, "Cheap stack", 2, 300, true, ItemPriceSource.GRAND_EXCHANGE)),
            false, 1, "owner");
        FilteredRewardView hidden = filter.filterReward(under, LootPresentationFilter.ALL_ITEMS, false, 200);
        assertTrue(hidden.getVisibleItems().isEmpty());
    }

    @Test public void minimumDoesNotHideUsedOrLostStacks()
    {
        LootPresentationFilterService filter = new LootPresentationFilterService(GroundItemsConfigSnapshot.disabled());
        RewardObservation used = new RewardObservation("u", "u", RewardSourceKind.USED,
            "Used", "", 1000, Arrays.asList(
                new RewardItem(385, "Shark", -1, -500, true, ItemPriceSource.GRAND_EXCHANGE)), true, 1, "owner");
        FilteredRewardView visible = filter.filterReward(used, LootPresentationFilter.ALL_ITEMS, false, 1000);
        assertEquals(1, visible.getVisibleItems().size());
        assertFalse(visible.isAllFiltered());
    }

    @Test public void readableMinimumAcceptsKAndInvalidInputFailsOpen()
    {
        assertEquals(1000, ProfitTargetParser.parseOrZero("1k"));
        assertEquals(1000, ProfitTargetParser.parseOrZero("1,000"));
        assertEquals(1500, ProfitTargetParser.parseOrZero("1.5k"));
        assertEquals(0, ProfitTargetParser.parseOrZero("-1"));
        assertEquals(0, ProfitTargetParser.parseOrZero("invalid"));
        assertEquals(0, ProfitTargetParser.parseOrZero("0"));
    }
}
