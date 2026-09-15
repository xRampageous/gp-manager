package com.gpmanager.ui;

import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.reward.CollectionStatus;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardObservation;
import com.gpmanager.reward.RewardSourceKind;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class HudTrayStateTest
{
    @Test
    public void resolvesLifecycleStates()
    {
        assertEquals(HudTrayState.GROUND_LOOT, HudTrayState.resolve(observation(
            RewardSourceKind.NPC_LOOT, "Goblin",
            List.of(item(4151, "Abyssal whip", 1L, 1_000L, true)), false)));
        assertEquals(HudTrayState.RECEIVED, HudTrayState.resolve(observation(
            RewardSourceKind.INVENTORY_CONFIRMED, "Loot",
            List.of(item(4151, "Abyssal whip", 1L, 1_000L, true)), true)));
        assertEquals(HudTrayState.GATHERED, HudTrayState.resolve(observation(
            RewardSourceKind.SKILLING, "Woodcutting",
            List.of(item(1519, "Willow logs", 4L, 80L, true)), true)));
        assertEquals(HudTrayState.BANKED, HudTrayState.resolve(observation(
            RewardSourceKind.BANKED, "Banked",
            List.of(item(1519, "Willow logs", 4L, 80L, true)), true)));
        assertEquals("Deposit", HudTrayState.tagLine(observation(
            RewardSourceKind.BANKED, "Deposit",
            List.of(item(1519, "Willow logs", 4L, 80L, true)), true)));
        assertEquals("Withdrew", HudTrayState.tagLine(observation(
            RewardSourceKind.BANKED, "Withdrew",
            List.of(item(385, "Shark", 5L, 2_500L, true)), true)));
        assertEquals("Deposit", HudTrayState.tagLine(observation(
            RewardSourceKind.BANKED, "Banked",
            List.of(item(1519, "Willow logs", 4L, 80L, true)), true)));
        assertEquals(HudTrayState.USED, HudTrayState.resolve(observation(
            RewardSourceKind.USED, "Used",
            List.of(item(385, "Shark", -1L, -500L, true)), true)));
        assertEquals(HudTrayState.LOST, HudTrayState.resolve(observation(
            RewardSourceKind.LOST, "Lost",
            List.of(item(11802, "Armadyl godsword", -1L, -50_000_000L, true)), true)));
        assertEquals(HudTrayState.CHEST_LOOT, HudTrayState.resolve(observation(
            RewardSourceKind.CLAIMED, "Brimstone chest",
            List.of(item(995, "Coins", 1_000L, 1_000L, true)), true)));
        assertEquals("Chest loot", HudTrayState.tagLine(observation(
            RewardSourceKind.INVENTORY_CONFIRMED, "Larran's big chest",
            List.of(item(995, "Coins", 1_000L, 1_000L, true)), true)));
    }

    @Test
    public void resolvesMixedAndFullVerbMap()
    {
        assertEquals(HudTrayState.MIXED, HudTrayState.resolve(observation(
            RewardSourceKind.SKILLING, "Mixed",
            List.of(
                item(1519, "Willow logs", 4L, 80L, true),
                item(385, "Shark", -1L, -500L, true)), true)));
        assertEquals("Mined", HudTrayState.skillingVerb("Mining"));
        assertEquals("Harvested", HudTrayState.skillingVerb("Farming"));
        assertEquals("Caught", HudTrayState.skillingVerb("Hunter"));
        assertEquals("Cooked", HudTrayState.skillingVerb("Cooking"));
        assertEquals("Smithed", HudTrayState.skillingVerb("Smithing"));
        assertEquals("Crafted", HudTrayState.skillingVerb("Crafting"));
        assertEquals("Crafted", HudTrayState.skillingVerb("Runecrafting"));
        assertEquals("Fletched", HudTrayState.skillingVerb("Fletching"));
        assertEquals("Built", HudTrayState.skillingVerb("Construction"));
        assertEquals("Burned", HudTrayState.skillingVerb("Firemaking"));
        assertEquals("Offered", HudTrayState.skillingVerb("Prayer"));
        assertEquals("Offered", HudTrayState.skillingVerb("Offered"));
        assertEquals("Buried", HudTrayState.skillingVerb("Buried"));
        assertEquals("Scattered", HudTrayState.skillingVerb("Scattered"));
        assertTrue(HudTrayState.isProcessSpendActivity("Buried"));
        assertTrue(HudTrayState.isProcessSpendActivity("Scattered"));
        assertFalse(HudTrayState.isImmediateProcessSpendActivity("Buried"));
        assertEquals("Smelted", HudTrayState.skillingVerb("Smelting"));
        assertEquals("Spun", HudTrayState.skillingVerb("Spinning"));
        assertEquals("Strung", HudTrayState.skillingVerb("Stringing"));
        assertEquals("Chiseled", HudTrayState.skillingVerb("Chiselling"));
        assertEquals("Cut", HudTrayState.skillingVerb("Cutting"));
        assertEquals("Cleaned", HudTrayState.skillingVerb("Cleaning"));
        assertEquals("Enchanted", HudTrayState.skillingVerb("Enchanting"));
        assertEquals("Blown", HudTrayState.skillingVerb("Glassblowing"));
        assertTrue(HudTrayState.isProcessSpendActivity("Firemaking"));
        assertFalse(HudTrayState.isProcessSpendActivity("Woodcutting"));
    }

    @Test
    public void firemakingLossStacksPaintBurnedNotUsed()
    {
        assertEquals(HudTrayState.GATHERED, HudTrayState.resolve(observation(
            RewardSourceKind.SKILLING, "Firemaking",
            List.of(item(1511, "Logs", -1L, -100L, true)), true)));
        assertEquals("Burned", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Firemaking",
            List.of(item(1511, "Logs", -1L, -100L, true)), true)));
    }

    @Test
    public void pairedProcessStacksPaintTheSkillVerbAndMixedOnlyForHerblore()
    {
        // State stays MIXED (paired colour); the tag is the skill's own past tense.
        assertEquals(HudTrayState.MIXED, HudTrayState.resolve(observation(
            RewardSourceKind.SKILLING, "Crafting",
            List.of(
                item(1745, "Green dragonhide", -1L, -1500L, true),
                item(1099, "Green d'hide chaps", 1L, 2000L, true)), true)));
        assertEquals("Crafted", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Crafting",
            List.of(
                item(1745, "Green dragonhide", -1L, -1500L, true),
                item(1099, "Green d'hide chaps", 1L, 2000L, true)), true)));
        // Cooking reports the actual outcome.
        assertEquals("Cooked", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Cooking",
            List.of(item(383, "Raw shark", -1L, -700L, true), item(385, "Shark", 1L, 800L, true)), true)));
        assertEquals("Burnt", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Cooking",
            List.of(item(383, "Raw shark", -1L, -700L, true), item(387, "Burnt fish", 1L, 0L, true)), true)));
        assertEquals("Cooked", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Cooking",
            List.of(
                item(383, "Raw shark", -2L, -1400L, true),
                item(385, "Shark", 1L, 800L, true),
                item(387, "Burnt fish", 1L, 0L, true)), true)));
        // Herblore production is genuinely "Mixed"; unknown processes stay Mixed too.
        assertEquals("Mixed", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Herblore",
            List.of(item(257, "Ranarr weed", -1L, -6000L, true), item(139, "Prayer potion(3)", 1L, 150L, true)), true)));
        assertEquals("Mixed", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Processing",
            List.of(item(1511, "Logs", -1L, -100L, true), item(52, "Arrow shaft", 15L, 75L, true)), true)));
    }

    @Test
    public void prayerLossStacksPaintOfferedNotUsed()
    {
        assertEquals("Offered", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Prayer",
            List.of(item(526, "Bones", -1L, -35L, true)), true)));
    }

    @Test
    public void claimedBeatsGainStacksPriority()
    {
        // Explicit Claimed kind wins even with ordinary gain stacks (never Received).
        assertEquals(HudTrayState.CLAIMED, HudTrayState.resolve(observation(
            RewardSourceKind.CLAIMED, "Tombs of Amascut",
            List.of(item(22477, "Osmumten's fang", 1L, 50_000_000L, true)), true)));
        assertEquals(HudTrayState.CHARGED, HudTrayState.resolve(observation(
            RewardSourceKind.CHARGED, "Banker charges",
            List.of(item(11907, "Trident of the seas", 1L, 1L, true)), true)));
    }

    @Test
    public void resolvesPeerPresentationKinds()
    {
        assertEquals(HudTrayState.CHARGED, HudTrayState.resolve(observation(
            RewardSourceKind.CHARGED, "Charged",
            List.of(item(12926, "Toxic blowpipe", -1L, -100L, true)), true)));
        assertEquals(HudTrayState.STORED, HudTrayState.resolve(observation(
            RewardSourceKind.STORED, "Stored",
            List.of(item(11941, "Looting bag", 1L, 0L, false)), true)));
        assertEquals(HudTrayState.CLAIMED, HudTrayState.resolve(observation(
            RewardSourceKind.CLAIMED, "Claimed",
            List.of(item(20997, "Twisted bow", 1L, 1_000_000_000L, true)), true)));
        assertEquals(HudTrayState.TRADED, HudTrayState.resolve(observation(
            RewardSourceKind.TRADED, "Traded",
            List.of(item(995, "Coins", 1000L, 1000L, true)), true)));
        assertEquals(HudTrayState.RECOVERED, HudTrayState.resolve(observation(
            RewardSourceKind.RECOVERED, "Recovered",
            List.of(item(11802, "Armadyl godsword", 1L, 50_000_000L, true)), true)));
        assertEquals(HudTrayState.PENDING_REWARDS, HudTrayState.resolve(observation(
            RewardSourceKind.PENDING_REWARDS, "Tombs of Amascut",
            List.of(item(22477, "Osmumten's fang", 1L, 50_000_000L, true)), true)));
    }

    @Test
    public void peerKindsBeatLossHeuristics()
    {
        // Claimed must not look like Received even with gain stacks.
        assertEquals(HudTrayState.CLAIMED, HudTrayState.resolve(observation(
            RewardSourceKind.CLAIMED, "Chambers of Xeric",
            List.of(item(20997, "Twisted bow", 1L, 1L, true)), true)));
        // Stored must not resolve as Lost when items are loss-shaped.
        assertEquals(HudTrayState.STORED, HudTrayState.resolve(observation(
            RewardSourceKind.STORED, "Herb sack",
            List.of(item(199, "Grimy guam", -5L, -100L, true)), true)));
    }

    @Test
    public void skillingVerbsOnTagLine()
    {
        assertEquals("Chopped", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Woodcutting",
            List.of(item(1519, "Willow logs", 1L, 20L, true)), true)));
        assertEquals("Fished", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Fishing",
            List.of(item(317, "Raw shrimp", 1L, 10L, true)), true)));
        assertEquals("Brewed", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Herblore",
            List.of(item(2434, "Prayer potion(4)", 1L, 8_000L, true)), true)));
        assertEquals("Alched", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Magic",
            List.of(item(995, "Coins", 100L, 100L, true)), true)));
        assertEquals("Made", HudTrayState.skillingVerb("Processing"));
        assertEquals("Stolen", HudTrayState.skillingVerb("Thieving"));
        assertEquals("Offered", HudTrayState.skillingVerb("Prayer"));
        assertEquals("Gathered", HudTrayState.skillingVerb("Agility"));
    }

    @Test
    public void drinkSipEatNeverInventPastTenseTrayVerbs()
    {
        assertEquals("Used", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Drink",
            List.of(item(2434, "Prayer potion(4)", -1L, -200L, true)), true)));
        assertEquals("Used", HudTrayState.tagLine(observation(
            RewardSourceKind.USED, "Sip",
            List.of(item(2434, "Prayer potion(4)", -1L, -200L, true)), true)));
        assertEquals("Mixed", HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Drink",
            List.of(
                item(2434, "Prayer potion(4)", -1L, -200L, true),
                item(139, "Prayer potion(3)", 1L, 100L, true)), true)));
        assertFalse(HudTrayState.tagLine(observation(
            RewardSourceKind.SKILLING, "Drink",
            List.of(item(2434, "Prayer potion(4)", -1L, -200L, true)), true)).contains("Drinked"));
        assertFalse(HudTrayState.isInventoryConsumeActivity("Herblore"));
        assertTrue(HudTrayState.isInventoryConsumeActivity("Sipped"));
    }

    @Test
    public void npcLootDoesNotFalseFirePeerStates()
    {
        HudTrayState state = HudTrayState.resolve(observation(
            RewardSourceKind.NPC_LOOT, "Goblin",
            List.of(item(526, "Bones", 1L, 50L, true)), false));
        assertEquals(HudTrayState.GROUND_LOOT, state);
        assertFalse(state == HudTrayState.CHARGED
            || state == HudTrayState.STORED
            || state == HudTrayState.CLAIMED
            || state == HudTrayState.TRADED
            || state == HudTrayState.RECOVERED);
    }

    @Test
    public void groundUnconfirmedAndPreserveHelpers()
    {
        RewardObservation ground = observation(
            RewardSourceKind.NPC_LOOT, "Goblin",
            List.of(item(4151, "Abyssal whip", 1L, 1_000L, true)), false);
        assertTrue(HudTrayState.isGroundUnconfirmed(ground));
        assertEquals(CollectionStatus.UNCONFIRMED, ground.collectionStatus());
        assertFalse(HudTrayState.isPreservedOnAlwaysExpandedBank(ground));

        RewardObservation skilling = observation(
            RewardSourceKind.SKILLING, "Woodcutting",
            List.of(item(1519, "Willow logs", 1L, 20L, true)), true);
        assertFalse(HudTrayState.isGroundUnconfirmed(skilling));
        assertTrue(HudTrayState.isPreservedOnAlwaysExpandedBank(skilling));

        RewardObservation charged = observation(
            RewardSourceKind.CHARGED, "Charged",
            List.of(item(12926, "Toxic blowpipe", -1L, -50L, true)), true);
        assertTrue(HudTrayState.isPreservedOnAlwaysExpandedBank(charged));
    }

    @Test
    public void itemDetailLineIncludesUnitOrUnpriced()
    {
        assertTrue(HudTrayState.itemDetailLine(item(995, "Coins", 100L, 100L, true))
            .contains("ea"));
        assertTrue(HudTrayState.itemDetailLine(item(1, "Mystery", 1L, 0L, false))
            .contains("unpriced"));
    }

    @Test
    public void waitingBodyCopyStaysEmptyUnlessJunkHonesty()
    {
        TrackingDisplaySnapshot idle = new TrackingDisplaySnapshot(
            0L, 0L, 0L, true, false, "General", "AFK", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null);
        assertEquals("", DedicatedHudRenderer.waitingBodyCopy(idle));
        TrackingDisplaySnapshot live = new TrackingDisplaySnapshot(
            0L, 0L, 0L, false, false, "General", "Live", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null);
        assertEquals("", DedicatedHudRenderer.waitingBodyCopy(live));
        TrackingDisplaySnapshot liveMin = live.withMinimumDisplayedLootValue(200L);
        assertEquals("", DedicatedHudRenderer.waitingBodyCopy(liveMin));
    }

    @Test
    public void allFilteredLiveShowsJunkItemsHidden()
    {
        RewardObservation willow = observation(
            RewardSourceKind.SKILLING, "Woodcutting",
            List.of(item(1519, "Willow logs", 20L, 380L, true)), true);
        com.gpmanager.grounditems.FilteredRewardView filtered =
            com.gpmanager.grounditems.FilteredRewardView.empty(
                willow, com.gpmanager.LootPresentationFilter.ALL_ITEMS);
        assertTrue(filtered.isAllFiltered());
        assertEquals(1, filtered.getHiddenStackCount());
        TrackingDisplaySnapshot snap = new TrackingDisplaySnapshot(
            0L, 0L, 0L, false, false, "Woodcutting", "Live", null,
            null, willow, filtered,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null)
            .withMinimumDisplayedLootValue(200L);
        assertEquals("Hidden items (1)", DedicatedHudRenderer.waitingBodyCopy(snap));
        assertEquals(1, snap.getFilteredReward().getHiddenItems().size());
        assertEquals("Willow logs", snap.getFilteredReward().getHiddenItems().get(0).getItemName());
        assertEquals(1, DedicatedHudRenderer.junkExpandRowCount(snap));

        java.util.List<DedicatedHudRenderer.HoverLine> hover =
            DedicatedHudRenderer.planHoverLines(snap, null, false, 10L, 5L);
        assertTrue(hover.size() <= 6);
        assertEquals("Status", hover.get(0).left);
        assertEquals("State", hover.get(1).left);
        assertEquals("Chopped", hover.get(1).right);
        assertTrue(hover.stream().anyMatch(h -> "Hidden".equals(h.left)));
        assertTrue(hover.stream().anyMatch(h -> "Filter".equals(h.left)));
        assertFalse(hover.stream().anyMatch(h -> "Rate".equals(h.left)));
        assertFalse(hover.stream().anyMatch(h -> "Active time".equals(h.left)));
    }

    @Test
    public void lossCompactLabelUsesAsciiHyphen()
    {
        RewardItem bones = new RewardItem(
            526, "Bones", -1L, -35L, true, ItemPriceSource.GRAND_EXCHANGE);
        assertEquals("Bones -1", bones.compactLabel());
    }

    @Test
    public void hudPlusHoverTurnsLegacyTrackingStatusIntoLive()
    {
        TrackingDisplaySnapshot legacyTracking = new TrackingDisplaySnapshot(
            0L, 0L, 0L, false, true, "General", "Tracking", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null);
        java.util.List<DedicatedHudRenderer.HoverLine> hover =
            DedicatedHudRenderer.planHoverLines(legacyTracking, null, false, 0L, 0L);
        assertEquals("Live", hover.get(0).right);
    }

    @Test
    public void partialFilterFooterShowsJunkCount()
    {
        RewardObservation complete = observation(
            RewardSourceKind.NPC_LOOT, "Goblin",
            List.of(
                item(1519, "Willow logs", 20L, 380L, true),
                item(4151, "Abyssal whip", 1L, 2_000_000L, true)), false);
        RewardObservation visible = observation(
            RewardSourceKind.NPC_LOOT, "Goblin",
            List.of(item(4151, "Abyssal whip", 1L, 2_000_000L, true)), false);
        com.gpmanager.grounditems.FilteredRewardView filtered =
            com.gpmanager.grounditems.FilteredRewardView.of(
                complete, visible.getItems(), java.util.Collections.emptyMap(),
                com.gpmanager.LootPresentationFilter.ALL_ITEMS);
        assertTrue(filtered.isPartiallyFiltered());
        TrackingDisplaySnapshot snap = new TrackingDisplaySnapshot(
            0L, 0L, 0L, false, false, "Goblin", "Live", null,
            visible, complete, filtered,
            com.gpmanager.reward.RewardPresentationPhase.SETTLED, false, 0f, 0L, "", null);
        assertEquals("+1 hidden item", DedicatedHudRenderer.junkHiddenFooter(snap, true));
        assertNull(DedicatedHudRenderer.junkHiddenFooter(snap, false));
    }

    @Test
    public void partialCollectionShowsReceivedWithConfirmedStacks()
    {
        RewardItem bones = item(526, "Bones", 1L, 35L, true);
        RewardItem mail = item(288, "Goblin mail", 1L, 231L, true);
        RewardObservation partial = new RewardObservation(
            "id", "dedupe", RewardSourceKind.NPC_LOOT, "Goblin", "enc", 1_000L,
            List.of(bones, mail),
            java.util.Map.of(288, 1L),
            1,
            "scope",
            new com.gpmanager.reward.RewardBatchState(1, true, false));
        assertEquals(CollectionStatus.PARTIAL, partial.collectionStatus());
        assertEquals(HudTrayState.RECEIVED, HudTrayState.resolve(partial));
        assertEquals(1, partial.presentationStacks().size());
        assertEquals("Goblin mail", partial.presentationStacks().get(0).getItemName());

        RewardObservation collected = new RewardObservation(
            "id", "dedupe", RewardSourceKind.NPC_LOOT, "Goblin", "enc", 1_000L,
            List.of(bones, mail), true, 1, "scope");
        assertEquals(CollectionStatus.COLLECTED, collected.collectionStatus());
        assertEquals(HudTrayState.RECEIVED, HudTrayState.resolve(collected));
        assertEquals(2, collected.presentationStacks().size());
    }

    @Test
    public void trayTagOmitsKillCountAlreadyShownInHeader()
    {
        RewardObservation goblin = new RewardObservation(
            "id", "dedupe", RewardSourceKind.NPC_LOOT, "Goblin", "enc", 1_000L,
            List.of(item(526, "Bones", 1L, 35L, true)),
            java.util.Collections.emptyMap(),
            1,
            "scope",
            new com.gpmanager.reward.RewardBatchState(17, true, false));
        assertEquals("Goblin \u00d717", goblin.displaySourceLabel());
        assertEquals("Ground Loot", HudTrayState.tagLine(goblin));
        assertFalse(HudTrayState.tagLine(goblin).contains("kills"));
    }

    private static RewardObservation observation(
        RewardSourceKind kind, String source, List<RewardItem> items, boolean confirmed)
    {
        return new RewardObservation(
            "id", "dedupe", kind, source, "", 1_000L, items, confirmed, 1, "scope");
    }

    private static RewardItem item(int id, String name, long qty, long value, boolean known)
    {
        return new RewardItem(id, name, qty, value, known,
            known ? ItemPriceSource.GRAND_EXCHANGE : ItemPriceSource.UNPRICED);
    }
}
