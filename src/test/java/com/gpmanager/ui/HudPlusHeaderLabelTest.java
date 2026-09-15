package com.gpmanager.ui;

import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardObservation;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.reward.RewardSourceKind;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HudPlusHeaderLabelTest
{
    @Test
    public void rewardObservationsDoNotPromoteLiveHeader()
    {
        RewardObservation dropped = new RewardObservation(
            "r1", "d", RewardSourceKind.USED, "Dropped", "",
            1L,
            Collections.singletonList(
                new RewardItem(1511, "Oak logs", -5L, -195L, true, ItemPriceSource.GRAND_EXCHANGE)),
            true, 1, "");
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Woodcutting", dropped));

        RewardObservation traded = new RewardObservation(
            "r2", "d", RewardSourceKind.TRADED, "Traded", "",
            1L,
            Collections.singletonList(
                new RewardItem(995, "Coins", 100L, 100L, true, ItemPriceSource.GRAND_EXCHANGE)),
            true, 1, "");
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Trading", traded));

        RewardObservation pending = new RewardObservation(
            "r3", "d", RewardSourceKind.PENDING_REWARDS, "Pending Rewards", "",
            1L,
            Collections.singletonList(
                new RewardItem(22477, "Osmumten's fang", 1L, 50_000_000L, true, ItemPriceSource.GRAND_EXCHANGE)),
            true, 1, "");
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Raid", pending));

        RewardObservation toa = new RewardObservation(
            "r4", "d", RewardSourceKind.PENDING_REWARDS, "Tombs of Amascut", "enc",
            1L,
            Collections.singletonList(
                new RewardItem(22477, "Osmumten's fang", 1L, 50_000_000L, true, ItemPriceSource.GRAND_EXCHANGE)),
            true, 1, "");
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Raid", toa));
    }

    @Test
    public void observedNpcRewardNeedsInteractionContextForHeader()
    {
        RewardObservation reward = new RewardObservation(
            "r1", "d", RewardSourceKind.NPC_LOOT, "Chicken", "enc",
            1L,
            Collections.singletonList(
                new RewardItem(526, "Bones", 1L, 35L, true, ItemPriceSource.GRAND_EXCHANGE)),
            false, 1, "");
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "PvM", reward));
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Chicken", reward));
    }

    @Test
    public void pausedAndRecoveryRemainStatusOnlyWithRewardPresent()
    {
        RewardObservation reward = new RewardObservation(
            "r1", "d", RewardSourceKind.NPC_LOOT, "Goblin", "enc",
            1L,
            Collections.singletonList(
                new RewardItem(526, "Bones", 1L, 35L, true, ItemPriceSource.GRAND_EXCHANGE)),
            false, 1, "");
        assertEquals("PAUSED", HudPlusHeaderLabel.resolve("Paused", "PvM", reward));
        assertEquals("REC", HudPlusHeaderLabel.resolve("Recovery", "PvM", reward));
    }

    @Test
    public void statusAloneWhenNotLiveWithoutSource()
    {
        assertEquals("PAUSED", HudPlusHeaderLabel.resolve("Paused", "General", null));
        assertEquals("AFK", HudPlusHeaderLabel.resolve("Idle", "", null));
        assertEquals("AFK", HudPlusHeaderLabel.resolve("AFK", "", null));
        assertEquals("AFK", TrackingStatus.compactHudPlus("AFK", true));
        assertEquals("Idle", TrackingStatus.compactHudPlus("Live", true));
        assertEquals("", TrackingStatus.compactHudPlus("Live", false));
        assertEquals("", TrackingStatus.compactHudPlus("Tracking"));
        assertEquals("Idle", TrackingStatus.compactHudPlus("Tracking", true));
        assertEquals("", HudPlusHeaderLabel.resolve("Tracking", "General", null));
        TrackingDisplaySnapshot legacyTracking = new TrackingDisplaySnapshot(
            0L, 0L, 0L, false, true, "General", "Tracking", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null);
        assertEquals("", HudPlusHeaderLabel.resolve(legacyTracking));
    }

    @Test
    public void characterIdleUsesGemOnlyAndNoIdleTitle()
    {
        TrackingDisplaySnapshot idle = new TrackingDisplaySnapshot(
            0L, 0L, 0L, false, true, "General", "Live", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null)
            .withCharacterIdle(true);
        assertEquals("", HudPlusHeaderLabel.resolve(idle));
    }

    @Test
    public void afkStaysBareAfterInteractionContextExpires()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.selectObject("u1", "oak", "Oak tree", 1_000L);
        TrackingDisplaySnapshot snap = new TrackingDisplaySnapshot(
            0L, 0L, 0L, true, false, "Woodcutting", "AFK", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null)
            .withInteraction(ctx.snapshot("u1", 6_000L))
            .withCharacterIdle(true);
        assertEquals("AFK", HudPlusHeaderLabel.resolve(snap));
    }

    @Test
    public void freshTargetOutranksPausedAndAfkStatusLabels()
    {
        InteractionContextModel npc = new InteractionContextModel();
        npc.npc("u1", 1, 41, "Goblin");
        TrackingDisplaySnapshot paused = new TrackingDisplaySnapshot(
            0L, 0L, 0L, true, false, "General", "Paused", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null)
            .withInteraction(npc.snapshot("u1", 2_000L));
        assertEquals("PAUSED · Goblin", HudPlusHeaderLabel.resolve(paused));

        InteractionContextModel object = new InteractionContextModel();
        object.selectObject("u1", "oak", "Oak tree", 1_000L);
        TrackingDisplaySnapshot afk = new TrackingDisplaySnapshot(
            0L, 0L, 0L, true, false, "Woodcutting", "AFK", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null)
            .withInteraction(object.snapshot("u1", 2_000L))
            .withCharacterIdle(true);
        assertEquals("AFK · Oak tree", HudPlusHeaderLabel.resolve(afk));
    }

    @Test
    public void characterIdleIgnoresStaleTargetAndGenericFallback()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.selectObject("u1", "oak", "Oak tree", 1_000L);
        ctx.clearAndBlockNpcTickRearm();
        TrackingDisplaySnapshot snap = new TrackingDisplaySnapshot(
            0L, 0L, 0L, false, true, "Woodcutting", "Live", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null)
            .withInteraction(ctx.snapshot("u1", 6_000L))
            .withCharacterIdle(true);
        assertEquals("", HudPlusHeaderLabel.resolve(snap));
    }

    @Test
    public void genericLiveStateUsesEmptyTitleAndFirstUseKeepsWaiting()
    {
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "General", null));
        assertEquals("Waiting", HudPlusHeaderLabel.resolve("Live", "Waiting", null));
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Tracking", null));
        // Generalized Insights buckets and gather skills leave the header slot empty.
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Skilling", null));
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "PvM", null));
        // Gather skills are not HUD titles — prefer a specific interaction when known.
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Woodcutting", null));
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Mining", null));
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Crafting", null));
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Smelting", null));
    }

    @Test
    public void skillingRewardWithoutInteractionLeavesGenericHeaderEmpty()
    {
        RewardObservation skill = new RewardObservation(
            "r2", "d", RewardSourceKind.SKILLING, "Woodcutting", "",
            1L,
            Collections.singletonList(
                new RewardItem(1511, "Oak logs", 1L, 39L, true, ItemPriceSource.GRAND_EXCHANGE)),
            true, 1, "");
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Woodcutting", skill));
    }

    @Test
    public void interactionSceneryBeatsSkillingItemName()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.selectObject("u1", "willow", "Willow tree", 1_000L);
        ctx.confirmObject("u1", 1_100L);
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.offerObservation(
            RewardSourceKind.SKILLING,
            "Woodcutting",
            "skill|1519",
            Collections.singletonList(
                new RewardItem(1519, "Willow logs", 2L, 44L, true, ItemPriceSource.GRAND_EXCHANGE)),
            1_200L,
            false);
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Woodcutting", "Live", rewards, 1_200L, 5_000L, 44L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_200L));
        assertEquals("Willow tree", HudPlusHeaderLabel.resolve(snap));
    }

    @Test
    public void chestNameWinsHeaderAndBareChestUsesOpeningFallback()
    {
        InteractionContextModel namedChest = new InteractionContextModel();
        namedChest.selectObject("u1", "brimstone", "Brimstone chest", 1_000L);
        TrackingDisplaySnapshot namedSnapshot = new TrackingDisplaySnapshot(
            0L, 0L, 0L, true, false, "General", "Live", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null)
            .withInteraction(namedChest.snapshot("u1", 1_200L));
        assertEquals("Brimstone chest", HudPlusHeaderLabel.resolve(namedSnapshot));

        InteractionContextModel genericChest = new InteractionContextModel();
        genericChest.selectObject("u1", "chest", "Chest", 1_000L);
        TrackingDisplaySnapshot genericSnapshot = new TrackingDisplaySnapshot(
            0L, 0L, 0L, true, false, "General", "Live", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null)
            .withInteraction(genericChest.snapshot("u1", 1_200L));
        assertEquals("Opening chest", HudPlusHeaderLabel.resolve(genericSnapshot));
    }

    @Test
    public void clearingNpcInteractionDropsHeaderToEmpty()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.npc("u1", 1, 172, "Dark wizard");
        TrackingDisplaySnapshot withNpc = TrackingDisplaySnapshot.from(
            null, "Dark wizard", "Live", null, 1_000L, 5_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_000L));
        assertEquals("Dark wizard", HudPlusHeaderLabel.resolve(withNpc));

        ctx.clear();
        TrackingDisplaySnapshot afterDeath = TrackingDisplaySnapshot.from(
            null, "General", "Live", null, 1_100L, 5_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_100L));
        assertEquals("", HudPlusHeaderLabel.resolve(afterDeath));
    }

    @Test
    public void sameNpcKillCountAdvancesWhileNpcRemainsTheLiveTarget()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.npc("u1", 1, 2, "Goblin");
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setLootCoalesceTicks(1);
        java.util.List<RewardItem> bones = Collections.singletonList(
            new RewardItem(526, "Bones", 1L, 35L, true, ItemPriceSource.GRAND_EXCHANGE));

        rewards.offerObservation(RewardSourceKind.NPC_LOOT, "Goblin", "npc:Goblin:1", bones, 1_000L, true);
        assertEquals("Goblin", liveNpcHeader(ctx, rewards, 1_000L));

        rewards.offerObservation(RewardSourceKind.NPC_LOOT, "Goblin", "npc:Goblin:2", bones, 1_500L, true);
        assertEquals("Goblin ×2", liveNpcHeader(ctx, rewards, 1_500L));

        // The visible loot tray locks after the quiet window; its third kill is
        // queued for later card presentation while the player keeps fighting.
        rewards.tick(2_100L);
        assertTrue(rewards.isLootBatchLocked());
        rewards.offerObservation(RewardSourceKind.NPC_LOOT, "Goblin", "npc:Goblin:3", bones, 3_000L, true);
        assertEquals("Goblin ×3", liveNpcHeader(ctx, rewards, 3_000L));

        // Opening the queued tray later must not count that third kill twice.
        rewards.tick(3_000L + 1_400L + 1L);
        assertEquals("Goblin ×3", rewards.currentForHud(4_401L).displaySourceLabel());
    }

    @Test
    public void longNpcNameCompactsAfterRewardBatchSuffixAndBeforeSessionPrefix()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.npc("u1", 1, 2, "Grand Exchange Clerk");
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setLootCoalesceTicks(1);
        java.util.List<RewardItem> bones = Collections.singletonList(
            new RewardItem(526, "Bones", 1L, 35L, true, ItemPriceSource.GRAND_EXCHANGE));
        rewards.offerObservation(RewardSourceKind.NPC_LOOT, "Grand Exchange Clerk", "ge-clerk:1",
            bones, 1_000L, true);
        rewards.offerObservation(RewardSourceKind.NPC_LOOT, "Grand Exchange Clerk", "ge-clerk:2",
            bones, 1_500L, true);

        RewardObservation reward = rewards.currentForHud(1_500L);
        TrackingDisplaySnapshot snapshot = TrackingDisplaySnapshot.from(
            null, "PvM", "Live", rewards, 1_500L, 5_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_500L))
            .withSessionChrome(true, "Boss trip", true, 12_000L, 3_600L, true);

        assertEquals("Grand Exchange Clerk ×2", reward.displaySourceLabel());
        assertEquals("Session · GE Clerk ×2", HudPlusHeaderLabel.resolve(snapshot));
        assertEquals("Session · Boss trip · Grand Exchange Clerk ×2",
            HudPlusHeaderLabel.resolveHover(snapshot));
    }

    @Test
    public void transferNotesDoNotBecomeHudHeaderTitles()
    {
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.offerObservation(RewardSourceKind.BANKED, "Tool Leprechaun store", "transfer:leprechaun",
            Collections.singletonList(
                new RewardItem(5295, "Ranarr seed", 1L, 100L, true, ItemPriceSource.GRAND_EXCHANGE)),
            1_000L, true);
        TrackingDisplaySnapshot snapshot = TrackingDisplaySnapshot.from(
            null, "Tracking", "Live", rewards, 1_000L, 5_000L, 0L, null, null);

        assertEquals("", HudPlusHeaderLabel.resolve(snapshot));
    }

    private static String liveNpcHeader(
        InteractionContextModel ctx, RewardPresentationModel rewards, long now)
    {
        TrackingDisplaySnapshot snapshot = TrackingDisplaySnapshot.from(
            null, "PvM", "Live", rewards, now, 5_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", now));
        return HudPlusHeaderLabel.resolve(snapshot);
    }

    @Test
    public void staleNpcActivityWithoutInteractionLeavesHeaderEmpty()
    {
        // After Idle/bank clear interaction, loot may leave detectedActivity as the NPC.
        // Header must not soft-stick Dark wizard without a live engage.
        TrackingDisplaySnapshot stale = TrackingDisplaySnapshot.from(
            null, "Dark wizard", "Live", null, 1_000L, 5_000L, 0L, null, null);
        assertEquals("", HudPlusHeaderLabel.resolve(stale));
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Chicken", null));
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Crafting", null));
    }

    @Test
    public void leaveIdleWithStaleNpcActivityDoesNotResurrectTitle()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.npc("u1", 1, 172, "Dark wizard");
        // The plugin clears transient interaction when Character Idle fires.
        ctx.clearAndBlockNpcTickRearm();
        TrackingDisplaySnapshot idle = TrackingDisplaySnapshot.from(
            null, "Dark wizard", "Live", null, 1_000L, 5_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_000L))
            .withCharacterIdle(true);
        assertEquals("", HudPlusHeaderLabel.resolve(idle));

        ctx.clearAndBlockNpcTickRearm();
        TrackingDisplaySnapshot afterIdle = TrackingDisplaySnapshot.from(
            null, "Dark wizard", "Live", null, 6_000L, 5_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", 6_000L))
            .withCharacterIdle(false);
        assertEquals("", HudPlusHeaderLabel.resolve(afterIdle));
    }

    @Test
    public void freshCookingStationTargetBeatsPrayerOrFiremakingProcessTitle()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.selectObject("u1", "fire", "Fire", 1_000L,
            com.gpmanager.model.InsightsActivityCategory.SKILLING, "Cooking");
        ctx.confirmObject("u1", 1_100L);
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Firemaking", "Live", null, 1_200L, 5_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_200L))
            .withConfirmedProcessTitle("Firemaking");
        assertEquals("Cooking", HudPlusHeaderLabel.resolve(snap));
    }

    @Test
    public void receiptNeverPromotesTitleOverFreshStationTarget()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.selectObject("u1", "fire", "Fire", 1_000L,
            com.gpmanager.model.InsightsActivityCategory.SKILLING, "Cooking");
        ctx.confirmObject("u1", 1_100L);
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setActivityHold(true);
        rewards.offerSkillingOrConfirmed(
            new com.gpmanager.model.ProfitTransaction(
                1_200L, null,
                com.gpmanager.model.TransactionType.PROCESSING,
                com.gpmanager.model.TrackingContext.GENERIC,
                "Light", "Firemaking", true,
                Collections.singletonList(
                    new com.gpmanager.model.ItemFlow(1511, "Logs", -1L, 25, -25L))),
            1_200L, true);
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Tracking", "Live", rewards, 1_200L, 5_000L, -25L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_200L));
        assertEquals("Cooking", HudPlusHeaderLabel.resolve(snap));
    }

    @Test
    public void mismatchedProcessXpDoesNotEraseFreshCookingStationTarget()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.selectObject("u1", "fire", "Fire", 1_000L,
            com.gpmanager.model.InsightsActivityCategory.SKILLING, "Cooking");
        ctx.confirmObject("u1", 1_100L);
        assertTrue(ctx.hasStickyObject(1_200L));
        assertFalse(ctx.refreshObjectGraceForSkill("Firemaking", 1_200L));
        assertTrue(ctx.hasStickyObject(1_200L));
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Firemaking", "Live", null, 1_200L, 5_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_200L))
            .withConfirmedProcessTitle("Firemaking");
        assertEquals("Cooking", HudPlusHeaderLabel.resolve(snap));
    }

    @Test
    public void freshGatherTargetBeatsFiremakingProcessTitle()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.selectObject("u1", "tree", "Willow tree", 1_000L);
        ctx.confirmObject("u1", 1_100L);
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Firemaking", "Live", null, 1_200L, 5_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_200L))
            .withConfirmedProcessTitle("Firemaking");
        assertEquals("Willow tree", HudPlusHeaderLabel.resolve(snap));
    }

    @Test
    public void freshNpcTargetBeatsFiremakingProcessTitle()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.npc("u1", 1, 41, "Chicken");
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Firemaking", "Live", null, 1_200L, 5_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_200L))
            .withConfirmedProcessTitle("Firemaking");
        assertEquals("Chicken", HudPlusHeaderLabel.resolve(snap));
    }

    @Test
    public void freshPlayerTargetBeatsProcessTitle()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.player("u1", 4, "Rival");
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Prayer", "Live", null, 1_200L, 5_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_200L))
            .withConfirmedProcessTitle("Prayer");
        assertEquals("Rival", HudPlusHeaderLabel.resolve(snap));
    }

    @Test
    public void unrelatedActivityCannotStealConfirmedTitleButFreshTargetStillWins()
    {
        TrackingDisplaySnapshot lootActivity = liveWithProcessTitle("Chicken", "Prayer");
        assertEquals("Prayer", HudPlusHeaderLabel.resolve(lootActivity));

        InteractionContextModel target = new InteractionContextModel();
        target.npc("u1", 7, 172, "Goblin");
        assertEquals("Goblin", HudPlusHeaderLabel.resolve(
            lootActivity.withInteraction(target.snapshot("u1", 1_100L))));
    }

    @Test
    public void recentXpProcessTitleOutranksIdleGemAndPlaceState()
    {
        TrackingDisplaySnapshot idle = liveWithProcessTitle("Chicken", "Prayer")
            .withCharacterIdle(true);
        assertEquals("Prayer", HudPlusHeaderLabel.resolve(idle));
        assertEquals("Prayer", HudPlusHeaderLabel.resolve(idle.withBankingUiOpen(true)));
    }

    @Test
    public void processXpDoesNotEraseFreshNpcOrGatherTarget()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.npc("u1", 1, 41, "Chicken");
        assertEquals("Chicken", ctx.snapshot("u1", 1_000L).getHudPlusName());

        ctx.selectObject("u1", "tree", "Willow tree", 1_000L);
        ctx.confirmObject("u1", 1_100L);
        assertFalse("Gather scenery must stay until Idle/retarget",
            ctx.refreshObjectGraceForSkill("Firemaking", 1_200L));
        assertTrue(ctx.hasStickyObject(1_200L));
        assertEquals("Willow tree", ctx.snapshot("u1", 1_200L).getHudPlusName());
    }

    @Test
    public void mismatchedFiremakingXpDoesNotEraseCookingStation()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.selectObject("u1", "fire", "Fire", 1_000L,
            com.gpmanager.model.InsightsActivityCategory.SKILLING, "Cooking");
        ctx.confirmObject("u1", 1_100L);
        assertFalse(ctx.refreshObjectGraceForSkill("Firemaking", 1_200L));
        assertTrue(ctx.hasStickyObject(1_200L));
    }

    @Test
    public void bankingUiOpenPaintsBankingNotIdle()
    {
        TrackingDisplaySnapshot idle = TrackingDisplaySnapshot.from(
            null, "Tracking", "Live", null, 1_000L, 5_000L, 0L, null, null)
            .withCharacterIdle(true);
        assertEquals("", HudPlusHeaderLabel.resolve(idle));
        assertEquals("Banking", HudPlusHeaderLabel.resolve(idle.withBankingUiOpen(true)));
    }

    @Test
    public void pausedWithoutSpecificSourceStaysHonestStatusOnly()
    {
        assertEquals("PAUSED", HudPlusHeaderLabel.resolve("Paused", "Skilling", null));
        assertEquals("PAUSED", HudPlusHeaderLabel.resolve("Paused", "PvM", null));
    }

    @Test
    public void collectionFooterUsesCollectionStatusLabels()
    {
        RewardObservation loot = new RewardObservation(
            "r1", "d", RewardSourceKind.NPC_LOOT, "Goblin", "enc",
            1L,
            Collections.singletonList(
                new RewardItem(526, "Bones", 1L, 35L, true, ItemPriceSource.GRAND_EXCHANGE)),
            false, 1, "");
        // Unpicked auto-collapse preview must not claim "Collection unconfirmed".
        assertNull(DedicatedHudRenderer.collectionFooter(loot));

        RewardObservation collected = loot.withCollectionConfirmed(2);
        assertEquals("Collected", DedicatedHudRenderer.collectionFooter(collected));

        RewardObservation skill = new RewardObservation(
            "r2", "d", RewardSourceKind.SKILLING, "Woodcutting", "",
            1L,
            Collections.singletonList(
                new RewardItem(1511, "Oak logs", 1L, 39L, true, ItemPriceSource.GRAND_EXCHANGE)),
            true, 1, "");
        assertNull(DedicatedHudRenderer.collectionFooter(skill));
    }

    @Test
    public void customSessionPrefixesHeaderAndHoverIncludesName()
    {
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Smelting", "Live", null, 1_000L, 5_000L, 0L, null, null)
            .withConfirmedProcessTitle("Smelting")
            .withSessionChrome(true, "Boss trip", true, 12_000L, 3_600L, true);
        assertEquals("Session · Smelting", HudPlusHeaderLabel.resolve(snap));
        assertEquals("Session · Boss trip · Smelting", HudPlusHeaderLabel.resolveHover(snap));
        assertEquals("Session · Smelting",
            HudPlusHeaderLabel.resolve(snap.withBankingUiOpen(true)));
    }

    @Test
    public void processTitleStaysPreferredOverGatherSkillFallback()
    {
        // Confirmed titles remain independent from non-process activity updates.
        assertEquals("Smelting", HudPlusHeaderLabel.resolve(
            liveWithProcessTitle("Woodcutting", "Smelting")));
        assertEquals("Fletching", HudPlusHeaderLabel.resolve(
            liveWithProcessTitle("General", "Fletching")));
        assertEquals("", HudPlusHeaderLabel.resolve(
            liveWithProcessTitle("Woodcutting", "")));
    }

    @Test
    public void actionReceiptCardsNeverRepeatTheTrayVerbInTheHeader()
    {
        // Tray says "Buried" — header keeps the skill so the same word is not painted twice.
        RewardObservation buried = new RewardObservation(
            "r1", "d", RewardSourceKind.SKILLING, "Buried", "",
            1L,
            Collections.singletonList(
                new RewardItem(526, "Bones", -1L, 35L, true, ItemPriceSource.GRAND_EXCHANGE)),
            false, 1, "");
        assertEquals("", buried.displaySourceLabel());
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "Prayer", buried));

        // Tray says "Drank" — header falls back to the activity / Tracking, never "Drank".
        RewardObservation drank = new RewardObservation(
            "r2", "d", RewardSourceKind.SKILLING, "Drank", "",
            1L,
            Collections.singletonList(
                new RewardItem(2434, "Prayer potion(4)", -1L, 200L, true, ItemPriceSource.GRAND_EXCHANGE)),
            false, 1, "");
        assertEquals("", drank.displaySourceLabel());
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "General", drank));
        assertEquals("Offered", new RewardObservation(
            "r3", "d", RewardSourceKind.SKILLING, "Offered", "", 1L,
            Collections.<RewardItem>emptyList(), false, 1, "").getSourceName());

        // A skill receipt still belongs only to the tray until positive XP arrives.
        RewardObservation cooking = new RewardObservation(
            "r4", "d", RewardSourceKind.SKILLING, "Cooking", "",
            1L,
            Collections.singletonList(
                new RewardItem(385, "Shark", 1L, 800L, true, ItemPriceSource.GRAND_EXCHANGE)),
            false, 1, "");
        assertEquals("", HudPlusHeaderLabel.resolve("Live", "General", cooking));
    }

    @Test
    public void freshNpcTargetOutranksPreviouslyXpConfirmedPrayerTitle()
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.npc("u1", 7, 172, "Dark wizard");
        // A fresh interaction is rank 1 and remains intact while rank-2 XP titles age.
        TrackingDisplaySnapshot snapshot = new TrackingDisplaySnapshot(
            0L, 0L, 0L, false, true, "Prayer", "Live", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 0f, 0L, "", null)
            .withInteraction(ctx.snapshot("u1", 2_000L))
            .withConfirmedProcessTitle("Prayer");
        assertEquals("Dark wizard", HudPlusHeaderLabel.resolve(snapshot));
    }

    @Test
    public void neutralZoneIsAPlaceTitleBelowProcessAndAboveWaiting()
    {
        TrackingDisplaySnapshot neutral = TrackingDisplaySnapshot.from(
            null, "Waiting", "Live", null, 1_000L, 5_000L, 0L, null, null)
            .withNeutralZoneActive(true);
        assertEquals("Neutral zone", HudPlusHeaderLabel.resolve(neutral));
        TrackingDisplaySnapshot prayer = TrackingDisplaySnapshot.from(
            null, "Prayer", "Live", null, 1_000L, 5_000L, 0L, null, null)
            .withNeutralZoneActive(true)
            .withConfirmedProcessTitle("Prayer");
        assertEquals("Prayer", HudPlusHeaderLabel.resolve(prayer));
        assertEquals("Prayer", HudPlusHeaderLabel.resolve(prayer.withBankingUiOpen(true)));
        assertEquals("Banking", HudPlusHeaderLabel.resolve(neutral.withBankingUiOpen(true)));
    }

    @Test
    public void buryMenuIntentAndEatingSharkKeepHeaderAndReceiptSeparate()
    {
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.noteProcessSpendIntent("Prayer", -1, 1_000L, "bury");
        ProfitTransaction eat = new ProfitTransaction(
            1_100L, null, TransactionType.CONSUMPTION, TrackingContext.GENERIC,
            "", "General", true,
            Collections.singletonList(new ItemFlow(383, "Shark", -1L, 900, -900L)));
        eat.setActionKind(ActionKind.EAT);
        rewards.offerSkillingOrConfirmed(eat, 1_100L, true);
        assertEquals("Ate", HudTrayState.tagLine(rewards.current()));
        assertEquals("", HudPlusHeaderLabel.resolve(TrackingDisplaySnapshot.from(
            null, "General", "Live", rewards, 1_100L, 5_000L, -900L, null, null)));

        InteractionContextModel target = new InteractionContextModel();
        target.npc("u1", 7, 172, "Goblin");
        TrackingDisplaySnapshot targeted = TrackingDisplaySnapshot.from(
            null, "General", "Live", rewards, 1_100L, 5_000L, -900L, null, null)
            .withInteraction(target.snapshot("u1", 1_100L));
        assertEquals("Goblin", HudPlusHeaderLabel.resolve(targeted));
        assertEquals("Ate", HudTrayState.tagLine(rewards.current()));
    }

    @Test
    public void plainBuryWithPrayerXpKeepsBuriedReceiptWithoutPromotingPrayer()
    {
        RewardPresentationModel rewards = buriedBonesAfterPrayerXp();
        TrackingDisplaySnapshot snapshot = TrackingDisplaySnapshot.from(
            null, "General", "Live", rewards, 2_500L, 5_000L, -35L, null, null);
        assertEquals("", HudPlusHeaderLabel.resolve(snapshot));
        assertEquals("Buried", HudTrayState.tagLine(rewards.current()));
    }

    @Test
    public void confirmedAltarUseTitleRendersAlongsideOfferedReceipt()
    {
        RewardPresentationModel rewards = offeredBonesAfterPrayerXp();
        TrackingDisplaySnapshot snapshot = TrackingDisplaySnapshot.from(
            null, "Prayer", "Live", rewards, 2_500L, 5_000L, -35L, null, null)
            .withConfirmedProcessTitle("Prayer");
        assertEquals("Prayer", HudPlusHeaderLabel.resolve(snapshot));
        assertEquals("Offered", HudTrayState.tagLine(rewards.current()));
    }

    @Test
    public void buryDuringNpcCombatKeepsNpcHeaderAndBuriedReceipt()
    {
        RewardPresentationModel rewards = buriedBonesAfterPrayerXp();
        InteractionContextModel target = new InteractionContextModel();
        target.npc("u1", 7, 172, "Goblin");
        TrackingDisplaySnapshot snapshot = TrackingDisplaySnapshot.from(
            null, "Prayer", "Live", rewards, 2_500L, 5_000L, -35L, null, null)
            .withInteraction(target.snapshot("u1", 2_500L))
            .withConfirmedProcessTitle("Prayer");
        assertEquals("Goblin", HudPlusHeaderLabel.resolve(snapshot));
        assertEquals("Buried", HudTrayState.tagLine(rewards.current()));
    }

    private static RewardPresentationModel buriedBonesAfterPrayerXp()
    {
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.noteProcessSpendIntent("Prayer", 526, 1_000L, "bury");
        ProfitTransaction bury = new ProfitTransaction(
            2_000L, null, TransactionType.CONSUMPTION, TrackingContext.GENERIC,
            "", "General", true,
            Collections.singletonList(new ItemFlow(526, "Bones", -1L, 35, -35L)));
        rewards.offerSkillingOrConfirmed(bury, 2_000L, true);
        rewards.confirmProcessSpendXp("Prayer", 2_500L, true);
        return rewards;
    }

    private static RewardPresentationModel offeredBonesAfterPrayerXp()
    {
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.noteProcessSpendIntent("Prayer", 526, 1_000L, "offer");
        ProfitTransaction offer = new ProfitTransaction(
            2_000L, null, TransactionType.CONSUMPTION, TrackingContext.GENERIC,
            "", "General", true,
            Collections.singletonList(new ItemFlow(526, "Bones", -1L, 35, -35L)));
        rewards.offerSkillingOrConfirmed(offer, 2_000L, true);
        rewards.confirmProcessSpendXp("Prayer", 2_500L, true);
        return rewards;
    }

    private static TrackingDisplaySnapshot liveWithProcessTitle(String activity, String title)
    {
        return TrackingDisplaySnapshot.from(
            null, activity, "Live", null, 1_000L, 5_000L, 0L, null, null)
            .withConfirmedProcessTitle(title);
    }
}
