package com.gpmanager.grounditems;

import com.gpmanager.LootPresentationFilter;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardObservation;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.reward.RewardSourceKind;
import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GroundItemsLootFilterTest
{
    private GroundItemsConfigSnapshot baseSnapshot;

    @Before
    public void setUp()
    {
        baseSnapshot = new GroundItemsConfigSnapshot(
            true,
            "Armadyl hilt, rune*>5, dragon*",
            "Bones, Vial, Coins",
            false,
            true,
            1_000,
            GroundItemsConfigSnapshot.ValueMode.HIGHEST,
            Color.decode("#AA00FF"),
            Color.WHITE,
            Color.GRAY,
            Arrays.asList(
                new GroundItemsConfigSnapshot.PriceTier(10_000_000, Color.MAGENTA),
                new GroundItemsConfigSnapshot.PriceTier(1_000_000, Color.ORANGE),
                new GroundItemsConfigSnapshot.PriceTier(100_000, Color.GREEN),
                new GroundItemsConfigSnapshot.PriceTier(20_000, Color.CYAN)));
    }

    @Test
    public void exactHighlightBeatsExactHide()
    {
        GroundItemsConfigSnapshot snap = withLists("Bones", "Bones");
        GroundItemsRuleEngine engine = new GroundItemsRuleEngine(snap);
        GroundItemsItemDecision decision = engine.evaluate("Bones", 1, 526, 50, 30, true);
        assertTrue(decision.isOnHighlightedList());
        assertTrue(decision.isVisibleUnderFollow());
        assertFalse(decision.isHiddenByRules());
    }

    @Test
    public void exactHideBeatsWildcardHighlight()
    {
        GroundItemsConfigSnapshot snap = withLists("Bone*", "Bones");
        GroundItemsRuleEngine engine = new GroundItemsRuleEngine(snap);
        GroundItemsItemDecision decision = engine.evaluate("Bones", 1, 526, 50, 30, true);
        assertFalse(decision.isOnHighlightedList());
        assertTrue(decision.isHiddenByRules());
        assertFalse(decision.isVisibleUnderFollow());
    }

    @Test
    public void wildcardHighlightBeatsWildcardHide()
    {
        GroundItemsConfigSnapshot snap = withLists("rune*", "*rune*");
        GroundItemsRuleEngine engine = new GroundItemsRuleEngine(snap);
        GroundItemsItemDecision decision = engine.evaluate("Rune platebody", 1, 1127, 40_000, 20_000, true);
        assertTrue(decision.isOnHighlightedList());
        assertTrue(decision.isVisibleUnderFollow());
    }

    @Test
    public void quantityThresholdsGateMatches()
    {
        GroundItemsRuleEngine engine = new GroundItemsRuleEngine(baseSnapshot);
        assertFalse(engine.evaluate("Rune essence", 3, 1436, 30, 10, true).isOnHighlightedList());
        assertTrue(engine.evaluate("Rune essence", 6, 1436, 60, 20, true).isOnHighlightedList());
        assertTrue(engine.evaluate("Dragon bones", 1, 536, 5_000, 100, true).isOnHighlightedList());
    }

    @Test
    public void hideUnderValueRequiresBothGeAndHa()
    {
        GroundItemsConfigSnapshot snap = new GroundItemsConfigSnapshot(
            true, "", "", false, true, 1_000,
            GroundItemsConfigSnapshot.ValueMode.HIGHEST,
            Color.MAGENTA, Color.WHITE, Color.GRAY, Collections.emptyList());
        GroundItemsRuleEngine engine = new GroundItemsRuleEngine(snap);
        // GE under, HA over → not hidden by value
        assertFalse(engine.evaluate("Widget", 1, 1, 100, 2_000, true).isHiddenByRules());
        // both under → hidden
        assertTrue(engine.evaluate("Widget", 1, 1, 100, 100, true).isHiddenByRules());
    }

    @Test
    public void highlightedListOnlyIgnoresValueTiers()
    {
        LootPresentationFilterService service = new LootPresentationFilterService(baseSnapshot);
        RewardObservation reward = observation(
            item(526, "Bones", 1, 50L),
            item(2, "Armadyl hilt", 1, 12_000_000L),
            item(1513, "Magic logs", 1, 1_100L));
        FilteredRewardView highlighted = service.filterReward(
            reward, LootPresentationFilter.HIGHLIGHTED_LIST_ONLY, false);
        assertEquals(1, highlighted.getVisibleItems().size());
        assertEquals("Armadyl hilt", highlighted.getSelectedVisibleItem().getItemName());
        assertTrue(highlighted.isPartiallyFiltered());
        assertFalse(highlighted.isAllFiltered());
    }

    @Test
    public void nameOnlyInsightFallbackHonorsUnconditionalListsWithoutHidingUnknownPrices()
    {
        LootPresentationFilterService service = new LootPresentationFilterService(
            withLists("Armadyl hilt, rune*>5", "Bones"));

        assertFalse(service.isItemNameIncluded("Bones", LootPresentationFilter.FOLLOW_GROUND_ITEMS));
        assertTrue(service.isItemNameIncluded("Magic logs", LootPresentationFilter.FOLLOW_GROUND_ITEMS));
        assertTrue("A quantity-qualified rule cannot be decided from a name-only legacy row",
            service.isItemNameIncluded("Rune essence", LootPresentationFilter.FOLLOW_GROUND_ITEMS));
        assertFalse(service.isItemNameIncluded("Bones", LootPresentationFilter.HIGHLIGHTED_LIST_ONLY));
        assertTrue(service.isItemNameIncluded("Armadyl hilt", LootPresentationFilter.HIGHLIGHTED_LIST_ONLY));
    }

    @Test
    public void followModeHidesDefaultHiddenAndHonoursShowHighlightedOnly()
    {
        GroundItemsConfigSnapshot showHl = new GroundItemsConfigSnapshot(
            true,
            "Armadyl hilt",
            "Bones",
            true,
            true,
            0,
            GroundItemsConfigSnapshot.ValueMode.HIGHEST,
            Color.MAGENTA, Color.WHITE, Color.GRAY,
            Collections.emptyList());
        LootPresentationFilterService service = new LootPresentationFilterService(showHl);
        RewardObservation reward = observation(
            item(526, "Bones", 1, 50L),
            item(2, "Armadyl hilt", 1, 12_000_000L),
            item(1513, "Magic logs", 1, 1_100L));
        FilteredRewardView follow = service.filterReward(
            reward, LootPresentationFilter.FOLLOW_GROUND_ITEMS, true);
        assertEquals(1, follow.getVisibleItems().size());
        assertEquals("Armadyl hilt", follow.getSelectedVisibleItem().getItemName());
        assertNotNull(follow.colorFor(2));
    }

    @Test
    public void groundItemsDisabledPassesThrough()
    {
        GroundItemsConfigSnapshot disabled = new GroundItemsConfigSnapshot(
            false,
            "",
            "Bones,Coins",
            true,
            true,
            1_000_000,
            GroundItemsConfigSnapshot.ValueMode.HIGHEST,
            Color.MAGENTA, Color.WHITE, Color.GRAY,
            Collections.emptyList());
        LootPresentationFilterService service = new LootPresentationFilterService(disabled);
        RewardObservation reward = observation(item(526, "Bones", 1, 50L));
        FilteredRewardView follow = service.filterReward(
            reward, LootPresentationFilter.FOLLOW_GROUND_ITEMS, false);
        assertFalse(follow.isAllFiltered());
        assertEquals(1, follow.getVisibleItems().size());
        FilteredRewardView highlighted = service.filterReward(
            reward, LootPresentationFilter.HIGHLIGHTED_LIST_ONLY, false);
        assertEquals(1, highlighted.getVisibleItems().size());
    }

    @Test
    public void allFilteredKeepsCompleteObservationWithoutReplay()
    {
        LootPresentationFilterService service = new LootPresentationFilterService(baseSnapshot);
        RewardPresentationModel model = new RewardPresentationModel();
        List<RewardItem> stacks = Collections.singletonList(item(526, "Bones", 1, 50L));
        model.offerObservation(RewardSourceKind.NPC_LOOT, "Goblin", "enc-1", stacks, 1_000L, true);
        assertNotNull(model.current());

        FilteredRewardView filtered = service.filterReward(
            model.current(), LootPresentationFilter.HIGHLIGHTED_LIST_ONLY, false);
        assertTrue(filtered.isAllFiltered());
        assertNull(filtered.asDisplayObservation());
        assertEquals("Goblin", filtered.getSourceName());
        assertNotNull(filtered.getObservation());
        assertEquals(1, filtered.getObservation().getItems().size());

        // Config flip to All items re-projects without offerObservation again.
        FilteredRewardView all = service.filterReward(
            model.current(), LootPresentationFilter.ALL_ITEMS, false);
        assertFalse(all.isAllFiltered());
        assertEquals(1, all.getVisibleItems().size());
        assertSameObservation(model.current(), all.getObservation());
    }

    @Test
    public void profileStyleSnapshotReplaceRefreshesProjection()
    {
        LootPresentationFilterService service = new LootPresentationFilterService(baseSnapshot);
        RewardObservation reward = observation(
            item(526, "Bones", 1, 50L),
            item(2, "Armadyl hilt", 1, 12_000_000L));
        FilteredRewardView before = service.filterReward(
            reward, LootPresentationFilter.FOLLOW_GROUND_ITEMS, false);
        assertEquals(1, before.getVisibleItems().size());

        GroundItemsConfigSnapshot open = withLists("", "");
        service.replaceSnapshotForTests(open);
        FilteredRewardView after = service.filterReward(
            reward, LootPresentationFilter.FOLLOW_GROUND_ITEMS, false);
        assertEquals(2, after.getVisibleItems().size());
        assertEquals(2, reward.getItems().size());
    }

    @Test
    public void visibleTotalsUseSelectedVisibleItemOnly()
    {
        LootPresentationFilterService service = new LootPresentationFilterService(baseSnapshot);
        RewardObservation reward = observation(
            item(526, "Bones", 1, 50L),
            item(2, "Armadyl hilt", 1, 12_000_000L),
            item(536, "Dragon bones", 2, 8_000L));
        FilteredRewardView view = service.filterReward(
            reward, LootPresentationFilter.HIGHLIGHTED_LIST_ONLY, false);
        assertEquals(2, view.getVisibleItems().size());
        assertEquals(12_008_000L, view.getVisibleLootTotal());
        assertEquals("Armadyl hilt", view.getSelectedVisibleItem().getItemName());
        // Complete observation totals unchanged.
        assertEquals(12_008_050L, reward.getLootValueTotal());
    }

    @Test
    public void lootVisibilityKeepsCostsWhileExplicitAccountingExclusionsRemainAvailable()
    {
        LootPresentationFilterService service = new LootPresentationFilterService(baseSnapshot);
        ItemFlow bonesGain = new ItemFlow(526, "Bones", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE);
        ItemFlow foodCost = new ItemFlow(385, "Shark", -1L, 800, -800L, ItemPriceSource.GRAND_EXCHANGE);
        assertFalse(service.isFlowVisible(
            bonesGain, LootPresentationFilter.HIGHLIGHTED_LIST_ONLY, false));
        assertTrue("loot visibility must not conceal supply costs", service.isFlowVisible(
            foodCost, LootPresentationFilter.HIGHLIGHTED_LIST_ONLY, false));
        assertFalse(service.isFlowIncluded(foodCost, LootPresentationFilter.HIGHLIGHTED_LIST_ONLY));
    }

    @Test
    public void displayFilterDoesNotHideRewardCostRows()
    {
        LootPresentationFilterService service = new LootPresentationFilterService(baseSnapshot);
        RewardItem bonesCost = item(526, "Bones", -1L, -50L);
        RewardObservation observation = new RewardObservation(
            "r-cost", "r-cost", RewardSourceKind.USED, "Used", "", 1_000L,
            Collections.singletonList(bonesCost), false, 1, "scope");

        FilteredRewardView filtered = service.filterReward(
            observation, LootPresentationFilter.HIGHLIGHTED_LIST_ONLY, false);

        assertEquals(1, filtered.getVisibleItems().size());
        assertTrue(filtered.getVisibleItems().get(0).isLoss());
        assertTrue(service.isRewardItemIncluded(bonesCost, LootPresentationFilter.HIGHLIGHTED_LIST_ONLY));
    }

    @Test
    public void confirmedPickupShowsGroundItemsHiddenStackAsReceived()
    {
        LootPresentationFilterService service = new LootPresentationFilterService(baseSnapshot);
        RewardItem bones = item(526, "Bones", 1, 50L);
        RewardItem hilt = item(2, "Armadyl hilt", 1, 12_000_000L);
        RewardObservation onGround = observation(bones, hilt);
        FilteredRewardView ground = service.filterReward(
            onGround, LootPresentationFilter.FOLLOW_GROUND_ITEMS, false);
        assertEquals(1, ground.getVisibleItems().size());
        assertEquals("Armadyl hilt", ground.getVisibleItems().get(0).getItemName());
        assertTrue(ground.isPartiallyFiltered());

        RewardObservation pickedUp = new RewardObservation(
            "r1", "d1", RewardSourceKind.NPC_LOOT, "Goblin", "enc-1", 1_000L,
            Arrays.asList(bones, hilt), true, 1, "scope");
        assertEquals(com.gpmanager.reward.CollectionStatus.COLLECTED, pickedUp.collectionStatus());
        FilteredRewardView received = service.filterReward(
            pickedUp, LootPresentationFilter.FOLLOW_GROUND_ITEMS, false);
        assertEquals(2, received.getVisibleItems().size());
        assertFalse(received.isPartiallyFiltered());
        assertEquals("Received", com.gpmanager.ui.HudTrayState.tagLine(received.asDisplayObservation()));
    }

    @Test
    public void intentionalPickupBypassesMinimumDisplayedValueOnTray()
    {
        LootPresentationFilterService service = new LootPresentationFilterService(baseSnapshot);
        RewardItem water = item(555, "Water rune", 12, 60L);
        RewardObservation picked = new RewardObservation(
            "r1", "d1", RewardSourceKind.RECENT_PICKUPS, "Recent pickups", "", 1_000L,
            Collections.singletonList(water), true, 1, "scope");
        FilteredRewardView filtered = service.filterReward(
            picked, LootPresentationFilter.FOLLOW_GROUND_ITEMS, false, 1_000L);
        assertEquals(1, filtered.getVisibleItems().size());
        assertEquals("Water rune", filtered.getVisibleItems().get(0).getItemName());
    }

    @Test
    public void partialPickupShowsReceivedConfirmedStacksNotGroundLoot()
    {
        LootPresentationFilterService service = new LootPresentationFilterService(baseSnapshot);
        RewardItem bones = item(526, "Bones", 1, 50L);
        RewardItem hilt = item(2, "Armadyl hilt", 1, 12_000_000L);
        java.util.Map<Integer, Long> confirmed = new java.util.LinkedHashMap<>();
        confirmed.put(2, 1L);
        RewardObservation partial = new RewardObservation(
            "r1", "d1", RewardSourceKind.NPC_LOOT, "Goblin", "enc-1", 1_000L,
            Arrays.asList(bones, hilt), confirmed, 1, "scope");
        assertEquals(com.gpmanager.reward.CollectionStatus.PARTIAL, partial.collectionStatus());
        assertEquals("Received", com.gpmanager.ui.HudTrayState.tagLine(partial));
        FilteredRewardView view = service.filterReward(
            partial, LootPresentationFilter.FOLLOW_GROUND_ITEMS, false);
        // Floor pickup of hilt → Received row; bones still unpicked are not listed.
        assertEquals(1, view.getVisibleItems().size());
        assertEquals("Armadyl hilt", view.getVisibleItems().get(0).getItemName());
        assertFalse(view.isPartiallyFiltered());
    }

    @Test
    public void recentPickupsBypassGroundItemsHide()
    {
        LootPresentationFilterService service = new LootPresentationFilterService(baseSnapshot);
        RewardObservation pickup = new RewardObservation(
            "r1", "d1", RewardSourceKind.RECENT_PICKUPS, "Inventory", "enc-1", 1_000L,
            Collections.singletonList(item(526, "Bones", 1, 50L)), false, 1, "scope");
        FilteredRewardView view = service.filterReward(
            pickup, LootPresentationFilter.FOLLOW_GROUND_ITEMS, false);
        assertEquals(1, view.getVisibleItems().size());
        assertEquals("Bones", view.getVisibleItems().get(0).getItemName());
    }

    private static GroundItemsConfigSnapshot withLists(String highlighted, String hidden)
    {
        return new GroundItemsConfigSnapshot(
            true,
            highlighted,
            hidden,
            false,
            true,
            0,
            GroundItemsConfigSnapshot.ValueMode.HIGHEST,
            Color.MAGENTA, Color.WHITE, Color.GRAY,
            Collections.emptyList());
    }

    private static RewardObservation observation(RewardItem... items)
    {
        return new RewardObservation(
            "r1",
            "d1",
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "enc-1",
            1_000L,
            Arrays.asList(items),
            false,
            1,
            "scope");
    }

    private static RewardItem item(int id, String name, long qty, long value)
    {
        return new RewardItem(id, name, qty, value, true, ItemPriceSource.GRAND_EXCHANGE);
    }

    private static void assertSameObservation(RewardObservation left, RewardObservation right)
    {
        assertNotNull(left);
        assertNotNull(right);
        assertEquals(left.getRewardId(), right.getRewardId());
        assertEquals(left.getGeneration(), right.getGeneration());
    }

    @Test
    public void allItemsStillHonorsIndependentColorReuseOption()
    {
        GroundItemsConfigSnapshot rules = new GroundItemsConfigSnapshot(true, "Bones", "",
            false, true, 0, GroundItemsConfigSnapshot.ValueMode.HIGHEST,
            Color.MAGENTA, Color.WHITE, Color.GRAY, Collections.emptyList());
        RewardObservation reward = new RewardObservation("r", "r", RewardSourceKind.NPC_LOOT,
            "Chicken", "enc-1", 1000L,
            Collections.singletonList(new RewardItem(526, "Bones", 1L, 35L, true,
                ItemPriceSource.GRAND_EXCHANGE)), false, 1, "scope");
        FilteredRewardView view = new LootPresentationFilterService(rules)
            .filterReward(reward, LootPresentationFilter.ALL_ITEMS, true);
        assertEquals(1, view.getVisibleItems().size());
        assertEquals(Color.MAGENTA, view.colorFor(526));
        assertEquals(35L, reward.getLootValueTotal());
    }
}
