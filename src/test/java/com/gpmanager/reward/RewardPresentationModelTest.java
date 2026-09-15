package com.gpmanager.reward;

import com.gpmanager.HudPlusAccumulationMode;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.ui.HudTrayState;
import com.gpmanager.ui.TrackingDisplaySnapshot;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RewardPresentationModelTest
{
    @Test
    public void encounterObserverReceivesDeduplicatedNpcEvidenceNotPlayerLoot()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        List<String> received = new ArrayList<>();
        model.setEncounterObserver((source, multiplicity, value, known, streak, at) ->
            received.add(source + ":" + multiplicity + ":" + value + ":" + known));

        List<RewardItem> loot = Collections.singletonList(
            item(526, "Bones", 2L, 70L));
        assertTrue(model.offerObservation(RewardSourceKind.NPC_LOOT, "Goblin",
            "stable-encounter-1", loot, 1_000L, true));
        assertFalse(model.offerObservation(RewardSourceKind.NPC_LOOT, "Goblin",
            "stable-encounter-1", loot, 1_050L, true));
        model.offerObservation(RewardSourceKind.PLAYER_LOOT, "Opponent",
            "player:1", loot, 1_100L, true);

        assertEquals(Collections.singletonList("Goblin:1:70:true"), received);
    }

    @Test
    public void multiItemObservationKeepsBestAndLootTotalSeparateFromLedger()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Kree'arra",
            "npc:Kree'arra:1",
            Arrays.asList(
                item(11818, "Armadyl hilt", 1, 8_000_000L),
                item(995, "Coins", 20_000, 20_000L),
                item(536, "Big bones", 1, 500L)),
            1_000L,
            true);

        RewardObservation reward = model.current();
        assertNotNull(reward);
        assertEquals("Armadyl hilt", reward.bestItem().getItemName());
        assertEquals(8_020_500L, reward.getLootValueTotal());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        assertTrue(model.isRevealing(1_100L));
        // Coalesce (~2 ticks) then Tray dwell, then clear.
        model.tick(1_000L + 2 * RewardPresentationModel.MILLIS_PER_GAME_TICK);
        model.tick(1_000L + 2 * RewardPresentationModel.MILLIS_PER_GAME_TICK
            + RewardPresentationModel.REVEAL_DWELL_MILLIS + 1L);
        // Unconfirmed Auto-collapse clears after coalesce lock + dwell — no settled best-item card.
        assertEquals(RewardPresentationPhase.NONE, model.phase());
        assertNull(model.current());
    }

    @Test
    public void crossAdapterSameKillDedupesAndNearbyIdenticalKillsBatch()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setLootCoalesceTicks(2);
        model.setRevealDwellMillis(1_400L);
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Kree'arra",
            "npc:Kree'arra:1",
            Collections.singletonList(item(11818, "Armadyl hilt", 1, 8_000_000L)),
            1_000L,
            true);
        int firstGen = model.getGeneration();
        int reveals = model.getRevealStartCount();
        assertTrue(model.isLootCoalescing());

        assertFalse(model.offerObservation(
            RewardSourceKind.LOOT_TRACKER,
            "Kree'arra",
            "loottracker:Kree'arra:2",
            Collections.singletonList(item(11818, "Armadyl hilt", 1, 8_000_000L)),
            1_200L,
            true));
        assertEquals(firstGen + 1, model.getGeneration());
        assertEquals(reveals, model.getRevealStartCount());

        assertFalse(model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Kree'arra",
            "npc:Kree'arra:3",
            Collections.singletonList(item(11818, "Armadyl hilt", 1, 8_000_000L)),
            1_500L,
            true));
        assertEquals(2L, model.current().bestItem().getQuantity());
        assertEquals(16_000_000L, model.current().getLootValueTotal());
        assertEquals(reveals, model.getRevealStartCount());
        assertTrue(model.isLootCoalescing());

        // Past coalesce quiet from last merge (1500 + 1200): lock, then queue later kill.
        assertFalse(model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Kree'arra",
            "npc:Kree'arra:4",
            Collections.singletonList(item(11818, "Armadyl hilt", 1, 8_000_000L)),
            3_000L,
            true));
        assertTrue(model.isLootBatchLocked());
        assertEquals(2L, model.current().bestItem().getQuantity());
        assertEquals(reveals, model.getRevealStartCount());

        // After dwell from lock (3000 + 1400), pending opens as a fresh coalesce.
        model.tick(3_000L + 1_400L + 1L);
        assertNotNull(model.current());
        assertEquals(1L, model.current().bestItem().getQuantity());
        assertEquals(reveals + 1, model.getRevealStartCount());
        assertTrue(model.isLootCoalescing());
    }

    @Test
    public void sessionHudAccumulatesDifferentLootAfterRevealExpires()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setLootCoalesceTicks(1);
        model.setRevealDwellMillis(400L);
        model.offerObservation(RewardSourceKind.NPC_LOOT, "Chicken", "npc:Chicken:1",
            Collections.singletonList(item(526, "Bones", 1, 35L)), 1_000L, true);
        model.tick(1_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK);
        model.tick(1_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK + 400L + 1L);
        model.offerObservation(RewardSourceKind.NPC_LOOT, "Chicken", "npc:Chicken:2",
            Collections.singletonList(item(2138, "Raw chicken", 1, 31L)), 2_000L, true);
        model.tick(2_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK);
        model.tick(2_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK + 400L + 1L);
        model.offerObservation(RewardSourceKind.NPC_LOOT, "Chicken", "npc:Chicken:3",
            Collections.singletonList(item(314, "Feather", 5, 10L)), 3_000L, true);
        model.tick(3_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK);
        model.tick(3_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK + 400L + 1L);
        model.offerObservation(RewardSourceKind.NPC_LOOT, "Chicken", "npc:Chicken:4",
            Collections.singletonList(item(526, "Bones", 1, 35L)), 4_000L, true);

        RewardObservation hud = model.currentForHud(4_401L);
        assertEquals(4, hud.getBatch().getEncounterCount());
        assertEquals("Chicken ×4", hud.displaySourceLabel());
        // Live Ground Loot stacks only — do not resurrect prior kills' items on the tray.
        assertEquals(1, hud.getItems().size());
        assertEquals(526, hud.getItems().get(0).getItemId());
    }

    @Test
    public void duplicateDoesNotIncrementSessionHudEncounters()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        offer(model, RewardSourceKind.LOOT_TRACKER, "loottracker:Chicken:1", 1_010L);

        assertEquals(1, model.currentForHud(1_020L).getBatch().getEncounterCount());
    }

    @Test
    public void streakTimeoutUsesGenuineEncounterClockOnly()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setHudPlusAccumulation(HudPlusAccumulationMode.STREAK, 10);
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:2", 600_999L);
        assertEquals(2, model.currentForHud(1_200_998L).getBatch().getEncounterCount());

        // Rendering and a cross-adapter duplicate do not extend the inactivity timer.
        model.currentForHud(1_200_999L);
        offer(model, RewardSourceKind.LOOT_TRACKER, "loottracker:Chicken:2", 601_000L);
        assertNull(model.currentForHud(1_201_000L));
    }

    @Test
    public void switchingSourcesKeepsTheirSessionHudTotals()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        model.offerObservation(RewardSourceKind.NPC_LOOT, "Goblin", "npc:Goblin:1",
            Collections.singletonList(item(526, "Bones", 1, 35L)), 5_000L, true);
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:2", 9_000L);

        assertEquals(2, model.currentForHud(9_001L).getBatch().getEncounterCount());
    }

    @Test
    public void twoAdaptersForOneChickenBoneDoNotDoubleQuantityOrValue()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        offer(model, RewardSourceKind.LOOT_TRACKER, "loottracker:Chicken:1", 1_010L);

        assertEquals(1L, model.current().bestItem().getQuantity());
        assertEquals(35L, model.current().getLootValueTotal());
    }

    @Test
    public void threeAdaptersForOneKillStartOnlyOneReveal()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        offer(model, RewardSourceKind.SERVER_NPC_LOOT, "server-npc:Chicken:1", 1_010L);
        offer(model, RewardSourceKind.LOOT_TRACKER, "loottracker:Chicken:1", 1_020L);

        assertEquals(1, model.getRevealStartCount());
    }

    @Test
    public void sameTickIdenticalKillsBatchTwoAndThreeWithoutExtraReveal()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:2", 1_000L);
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:3", 1_000L);

        assertEquals(3L, model.current().bestItem().getQuantity());
        assertEquals(105L, model.current().getLootValueTotal());
        assertEquals(1, model.getRevealStartCount());
    }

    @Test
    public void interleavedAdapterDuplicateDoesNotPreventSecondKillBatching()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        offer(model, RewardSourceKind.LOOT_TRACKER, "loottracker:Chicken:1", 1_010L);
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:2", 1_020L);

        assertEquals(2L, model.current().bestItem().getQuantity());
        assertEquals(70L, model.current().getLootValueTotal());
        assertEquals(1, model.getRevealStartCount());
    }

    @Test
    public void duplicateAfterRevealClearStillAcceptsDistinctAdapterOffer()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setLootCoalesceTicks(1);
        model.setRevealDwellMillis(400L);
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        model.tick(1_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK);
        model.tick(1_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK + 400L + 1L);
        assertEquals(RewardPresentationPhase.NONE, model.phase());
        assertNull(model.current());

        // After Auto-collapse clear, a fresh adapter event may open a new card.
        offer(model, RewardSourceKind.LOOT_TRACKER, "loottracker:Chicken:2", 1_500L);
        assertNotNull(model.current());
        assertEquals(1L, model.current().bestItem().getQuantity());
    }

    @Test
    public void stableEncounterMergesSplitDeliveryAndCanOvertakeWinner()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        String encounter = "enc-stable-1";
        model.offerObservation(
            RewardSourceKind.INVENTORY_CONFIRMED,
            "Boss",
            encounter,
            Collections.singletonList(item(536, "Big bones", 1, 500L)),
            1_000L,
            true);
        assertEquals("Big bones", model.current().bestItem().getItemName());

        model.offerObservation(
            RewardSourceKind.INVENTORY_CONFIRMED,
            "Boss",
            encounter,
            Collections.singletonList(item(11818, "Armadyl hilt", 1, 8_000_000L)),
            1_100L,
            true);
        assertEquals("Armadyl hilt", model.current().bestItem().getItemName());
        assertEquals(2, model.current().getItems().size());
    }

    @Test
    public void stableEncounterMergesSplitStacks()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(RewardSourceKind.INVENTORY_CONFIRMED, "Boss", "enc-stable-stacks",
            Collections.singletonList(item(526, "Bones", 1, 35L)), 1_000L, true);
        model.offerObservation(RewardSourceKind.INVENTORY_CONFIRMED, "Boss", "enc-stable-stacks",
            Collections.singletonList(item(526, "Bones", 2, 70L)), 1_100L, true);

        assertEquals(3L, model.current().bestItem().getQuantity());
        assertEquals(105L, model.current().getLootValueTotal());
    }

    @Test
    public void skillingStacksWithoutRevealTray()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction first = gain(1511, "Oak logs", 1, 39L);
        ProfitTransaction second = gain(1511, "Oak logs", 1, 39L);
        model.offerSkillingOrConfirmed(first, 1_000L, true);
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        model.offerSkillingOrConfirmed(second, 1_100L, true);
        assertEquals(2L, model.current().bestItem().getQuantity());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        assertTrue(model.current().getSourceKind().isSkilling());
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void alwaysExpandedSkillingAccumulatesAcrossResources()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 900L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 4, 39L), 1_000L, true);
        model.offerSkillingOrConfirmed(woodcutting(1519, "Logs", 1, 40L), 1_200L, true);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 2, 39L), 1_400L, true);
        assertNotNull(model.current());
        assertEquals(2, model.current().getItems().size());
        long oakQty = 0L;
        long logQty = 0L;
        for (RewardItem item : model.current().getItems())
        {
            if (item.getItemId() == 1511)
            {
                oakQty = item.getQuantity();
            }
            if (item.getItemId() == 1519)
            {
                logQty = item.getQuantity();
            }
        }
        assertEquals(6L, oakQty);
        assertEquals(1L, logQty);
        assertEquals("Woodcutting", model.current().getSourceName());
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));
        assertTrue(model.current().getSourceKind().supportsExpandedTray());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());

        model.setOwnerScope("other-account");
        assertNull(model.current());
    }

    @Test
    public void activityHoldFiremakingThenCookingReplacesTray()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setActivityHold(true);
        model.offerSkillingOrConfirmed(skillGain(1511, "Logs", 1, 25L, "Firemaking"), 1_000L, true);
        assertEquals("Firemaking", model.current().getSourceName());

        // Process path (Cooking Mixed) must replace, not merge/suppress Firemaking.
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_200L, null, TransactionType.PROCESSING, TrackingContext.GENERIC,
            "Cook", "Cooking", true,
            Arrays.asList(
                new ItemFlow(2140, "Cooked chicken", 2L, 70, 140L),
                new ItemFlow(2144, "Burnt chicken", 2L, 0, 0L),
                new ItemFlow(2138, "Raw chicken", -4L, 32, -128L))), 1_200L, true);

        assertNotNull(model.current());
        assertEquals("Cooking", model.current().getSourceName());
        for (RewardItem item : model.current().getItems())
        {
            assertFalse("Firemaking logs must not bleed into Cooking tray",
                item.getItemId() == 1511);
        }
        assertEquals(HudTrayState.MIXED, HudTrayState.resolve(model.current()));
    }

    @Test
    public void activityHoldCookingThenFiremakingReplacesTray()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setActivityHold(true);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_000L, null, TransactionType.PROCESSING, TrackingContext.GENERIC,
            "Cook", "Cooking", true,
            Arrays.asList(
                new ItemFlow(2140, "Cooked chicken", 2L, 70, 140L),
                new ItemFlow(2138, "Raw chicken", -2L, 32, -64L))), 1_000L, true);
        assertEquals("Cooking", model.current().getSourceName());
        assertEquals("Cooked", HudTrayState.tagLine(model.current()));

        model.offerSkillingOrConfirmed(skillGain(1511, "Logs", 1, 25L, "Firemaking"), 1_200L, true);
        assertEquals("Firemaking", model.current().getSourceName());
        assertEquals("Burned", HudTrayState.tagLine(model.current()));
        for (RewardItem item : model.current().getItems())
        {
            assertFalse("Cooking stacks must not bleed into Firemaking tray",
                item.getItemId() == 2140 || item.getItemId() == 2138);
        }
    }

    @Test
    public void activityHoldCookingThenLightLogsProcessSpendReplacesTray()
    {
        // Live bug: Cooking Mixed open under hold → light logs still book
        // activityName "Cooking" → must not merge Logs into Cooking.
        RewardPresentationModel model = new RewardPresentationModel();
        model.setActivityHold(true);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_000L, null, TransactionType.PROCESSING, TrackingContext.GENERIC,
            "Cook", "Cooking", true,
            Arrays.asList(
                new ItemFlow(2140, "Cooked chicken", 2L, 70, 140L),
                new ItemFlow(2138, "Raw chicken", -2L, 32, -64L))), 1_000L, true);
        assertEquals("Cooking", model.current().getSourceName());

        model.noteProcessSpendIntent("Firemaking", 1511, 1_200L);
        ProfitTransaction light = new ProfitTransaction(
            1_300L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "Cooking",
            true,
            Collections.singletonList(new ItemFlow(1511, "Logs", -1L, 100, -100L)));
        model.offerSkillingOrConfirmed(light, 1_300L, true);

        assertNotNull(model.current());
        assertEquals("Firemaking", model.current().getSourceName());
        assertEquals("Burned", HudTrayState.tagLine(model.current()));
        assertEquals("Logs -1", model.current().bestItem().compactLabel());
        for (RewardItem item : model.current().getItems())
        {
            assertFalse("Cooking stacks must not absorb Firemaking log spends",
                item.getItemId() == 2140 || item.getItemId() == 2138);
        }
    }

    @Test
    public void alwaysExpandedCookingThenLightLogsKeepsSessionReceipts()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 900L);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_000L, null, TransactionType.PROCESSING, TrackingContext.GENERIC,
            "Cook", "Cooking", true,
            Arrays.asList(
                new ItemFlow(2140, "Cooked chicken", 2L, 70, 140L),
                new ItemFlow(2138, "Raw chicken", -2L, 32, -64L))), 1_000L, true);
        assertEquals("Cooking", model.current().getSourceName());

        model.noteProcessSpendIntent("Firemaking", 1511, 1_200L);
        ProfitTransaction light = new ProfitTransaction(
            1_300L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "Cooking",
            true,
            Collections.singletonList(new ItemFlow(1511, "Logs", -1L, 100, -100L)));
        model.offerSkillingOrConfirmed(light, 1_300L, true);

        assertEquals("Session", model.current().getSourceName());
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 2140));
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 1511));
    }

    @Test
    public void openFiremakingIntentIgnoresUnrelatedFoodLoss()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setActivityHold(true);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_000L, null, TransactionType.PROCESSING, TrackingContext.GENERIC,
            "Cook", "Cooking", true,
            Arrays.asList(
                new ItemFlow(2140, "Cooked chicken", 2L, 70, 140L),
                new ItemFlow(2138, "Raw chicken", -2L, 32, -64L))), 1_000L, true);
        model.noteProcessSpendIntent("Firemaking", -1, 1_200L);
        ProfitTransaction eat = new ProfitTransaction(
            1_300L, null, TransactionType.CONSUMPTION, TrackingContext.GENERIC,
            "Eat", "Cooking", true,
            Collections.singletonList(new ItemFlow(2140, "Cooked chicken", -1L, 70, -70L)));
        model.offerSkillingOrConfirmed(eat, 1_300L, true);
        assertEquals("Cooking", model.current().getSourceName());
        assertNotEquals("Burned", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void openFiremakingIntentMatchesLogsUnderStaleCookingActivity()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setActivityHold(true);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_000L, null, TransactionType.PROCESSING, TrackingContext.GENERIC,
            "Cook", "Cooking", true,
            Arrays.asList(
                new ItemFlow(2140, "Cooked chicken", 1L, 70, 70L),
                new ItemFlow(2138, "Raw chicken", -1L, 32, -32L))), 1_000L, true);
        model.noteProcessSpendIntent("Firemaking", -1, 1_200L);
        ProfitTransaction light = new ProfitTransaction(
            1_300L, null, TransactionType.CONSUMPTION, TrackingContext.GENERIC,
            "", "Cooking", true,
            Collections.singletonList(new ItemFlow(1511, "Logs", -1L, 100, -100L)));
        model.offerSkillingOrConfirmed(light, 1_300L, true);
        assertEquals("Firemaking", model.current().getSourceName());
        assertEquals("Burned", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void activityHoldFletchingThenSmithingReplaces()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setActivityHold(true);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_000L, null, TransactionType.PROCESSING, TrackingContext.PRODUCTION,
            "Fletch", "Fletching", true,
            Arrays.asList(
                new ItemFlow(52, "Arrow shaft", 15L, 5, 75L),
                new ItemFlow(1511, "Oak logs", -1L, 39, -39L))), 1_000L, true);
        assertEquals("Fletching", model.current().getSourceName());
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_200L, null, TransactionType.PROCESSING, TrackingContext.PRODUCTION,
            "Smith", "Smithing", true,
            Arrays.asList(
                new ItemFlow(2351, "Iron bar", -1L, 50, -50L),
                new ItemFlow(1203, "Iron dagger", 1L, 80, 80L))), 1_200L, true);
        assertEquals("Smithing", model.current().getSourceName());
        assertFalse(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 52));
    }

    @Test
    public void activityHoldHerbloreThenCookingReplaces()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setActivityHold(true);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_000L, null, TransactionType.PROCESSING, TrackingContext.PRODUCTION,
            "Mix", "Herblore", true,
            Arrays.asList(
                new ItemFlow(91, "Guam potion (unf)", 1L, 80, 80L),
                new ItemFlow(249, "Guam leaf", -1L, 50, -50L))), 1_000L, true);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_200L, null, TransactionType.PROCESSING, TrackingContext.GENERIC,
            "Cook", "Cooking", true,
            Arrays.asList(
                new ItemFlow(2140, "Cooked chicken", 1L, 70, 70L),
                new ItemFlow(2138, "Raw chicken", -1L, 32, -32L))), 1_200L, true);
        assertEquals("Cooking", model.current().getSourceName());
        assertFalse(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 91));
    }

    @Test
    public void alwaysExpandedSmeltingThenCraftingAccumulatesSession()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 900L);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_000L, null, TransactionType.PROCESSING, TrackingContext.PRODUCTION,
            "Smelt", "Smelting", true,
            Arrays.asList(
                new ItemFlow(2351, "Iron bar", 1L, 50, 50L),
                new ItemFlow(440, "Iron ore", -1L, 40, -40L))), 1_000L, true);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_200L, null, TransactionType.PROCESSING, TrackingContext.PRODUCTION,
            "Craft", "Crafting", true,
            Arrays.asList(
                new ItemFlow(1059, "Leather gloves", 1L, 30, 30L),
                new ItemFlow(1741, "Leather", -1L, 20, -20L))), 1_200L, true);
        assertEquals("Session", model.current().getSourceName());
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 2351));
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 1059));
    }

    @Test
    public void choppedThenFloorPickupBecomesReceivedNotMixed()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setActivityHold(true);
        model.offerSkillingOrConfirmed(woodcutting(1519, "Willow logs", 3, 23L), 1_000L, true);
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));

        ProfitTransaction pickup = new ProfitTransaction(
            1_200L, null, TransactionType.LOOT, TrackingContext.LOOT,
            "Floor pickup", "General", true,
            Collections.singletonList(new ItemFlow(555, "Water rune", 5L, 5, 25L)));
        model.offerSkillingOrConfirmed(pickup, 1_200L, true);

        assertEquals(RewardSourceKind.RECENT_PICKUPS, model.current().getSourceKind());
        assertEquals("Received", HudTrayState.tagLine(model.current()));
        assertNotEquals("Mixed", HudTrayState.tagLine(model.current()));
        for (RewardItem item : model.current().getItems())
        {
            assertFalse("Willow logs must not bleed into Received pickup tray",
                item.getItemId() == 1519);
        }
    }

    @Test
    public void claimedAndPendingNeverResolveAsGroundLootOrMixed()
    {
        RewardObservation claimed = new RewardObservation(
            "c1", "d", RewardSourceKind.CLAIMED, "Tombs of Amascut", "enc",
            1L, Collections.singletonList(item(22477, "Osmumten's fang", 1, 50_000_000L)),
            true, 1, "");
        assertEquals(HudTrayState.CLAIMED, HudTrayState.resolve(claimed));
        assertEquals("Claimed", HudTrayState.tagLine(claimed));
        assertNotEquals(HudTrayState.GROUND_LOOT, HudTrayState.resolve(claimed));
        assertNotEquals(HudTrayState.MIXED, HudTrayState.resolve(claimed));

        RewardObservation pending = new RewardObservation(
            "p1", "d", RewardSourceKind.PENDING_REWARDS, "Tombs of Amascut", "enc",
            1L, Collections.singletonList(item(22477, "Osmumten's fang", 1, 50_000_000L)),
            true, 1, "");
        assertEquals(HudTrayState.PENDING_REWARDS, HudTrayState.resolve(pending));
        assertEquals("Pending Rewards", HudTrayState.tagLine(pending));
        assertNotEquals(HudTrayState.GROUND_LOOT, HudTrayState.resolve(pending));
    }

    @Test
    public void groundLootCoalesceStaysNpcLootOnlyAcrossSkillOffer()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setLootCoalesceTicks(2);
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        assertEquals(HudTrayState.GROUND_LOOT, HudTrayState.resolve(model.current()));
        assertTrue(model.isLootCoalescing());

        // Skilling must not merge into or retag protected Ground Loot.
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 1, 39L), 1_100L, true);
        assertEquals(RewardSourceKind.NPC_LOOT, model.current().getSourceKind());
        assertEquals("Ground Loot", HudTrayState.tagLine(model.current()));
        assertEquals("Chicken", model.current().getSourceName());
    }

    @Test
    public void alwaysExpandedWoodcuttingThenCookingAccumulatesSession()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 900L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 4, 39L), 1_000L, true);
        model.offerSkillingOrConfirmed(skillGain(2140, "Cooked chicken", 1, 70L, "Cooking"), 1_200L, true);
        assertEquals("Session", model.current().getSourceName());
        assertEquals("Session", HudTrayState.tagLine(model.current()));
        assertEquals(2, model.current().getItems().size());
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 1511));
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 2140));
    }

    @Test
    public void alwaysExpandedWcCookFiremakingKeepsAllGains()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 900L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 4, 39L), 1_000L, true);
        model.offerSkillingOrConfirmed(skillGain(2140, "Cooked chicken", 1, 70L, "Cooking"), 1_200L, true);
        model.offerSkillingOrConfirmed(skillGain(592, "Ashes", 1, 1L, "Firemaking"), 1_400L, true);
        assertEquals("Session", model.current().getSourceName());
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 1511));
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 2140));
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 592));
    }

    @Test
    public void bankingFlashesCanBeSuppressed()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setShowBankingFlashes(false);
        ProfitTransaction deposit = new ProfitTransaction(
            1_000L, null, TransactionType.TRANSFER, TrackingContext.TRANSFER,
            "Bank deposit", "Bank", false,
            Collections.singletonList(new ItemFlow(1511, "Oak logs", -4L, 39, -156L)));
        model.offerSkillingOrConfirmed(deposit, 1_000L, true);
        assertNull(model.current());
    }

    @Test
    public void autoCollapseSkillingStillReplacesOnItemChange()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 4, 39L), 1_000L, true);
        model.offerSkillingOrConfirmed(woodcutting(1519, "Logs", 1, 40L), 1_200L, true);
        assertEquals(1, model.current().getItems().size());
        assertEquals(1519, model.current().bestItem().getItemId());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
    }

    @Test
    public void pauseSuppressesNewRevealsAndOwnershipClearDropsState()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Kree'arra",
            "npc:Kree'arra:1",
            Collections.singletonList(item(11818, "Armadyl hilt", 1, 8_000_000L)),
            1_000L,
            true);
        assertNotNull(model.current());

        model.setSuppressReveals(true);
        assertFalse(model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Kree'arra",
            "npc:Kree'arra:2",
            Collections.singletonList(item(995, "Coins", 1, 1L)),
            2_000L,
            true));
        assertEquals("Armadyl hilt", model.current().bestItem().getItemName());

        model.setOwnerScope("other");
        assertNull(model.current());
    }

    @Test
    public void inventoryConfirmDoesNotReplayReveal()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Kree'arra",
            "enc-1",
            Collections.singletonList(item(11818, "Armadyl hilt", 1, 8_000_000L)),
            1_000L,
            true);
        int reveals = model.getRevealStartCount();
        ProfitTransaction pickup = new ProfitTransaction(
            1_200L,
            0L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot from Kree'arra",
            "Kree'arra",
            true,
            Collections.singletonList(new ItemFlow(11818, "Armadyl hilt", 1L, 8_000_000, 8_000_000L)),
            ClassificationConfidence.LIKELY,
            "test",
            "enc-1");
        model.offerSkillingOrConfirmed(pickup, 1_200L, true);
        assertEquals(reveals, model.getRevealStartCount());
        assertTrue(model.current().isCollectionConfirmed());
    }

    @Test
    public void partialPickupDoesNotConfirmWholeReward()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "enc-chicken-1",
            java.util.Arrays.asList(
                item(526, "Bones", 1, 35L),
                item(2138, "Raw chicken", 1, 31L),
                item(314, "Feather", 5, 10L)),
            1_000L,
            true);
        ProfitTransaction bonesOnly = new ProfitTransaction(
            1_100L,
            0L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot from Chicken",
            "Chicken",
            true,
            Collections.singletonList(new ItemFlow(526, "Bones", 1L, 35, 35L)),
            ClassificationConfidence.LIKELY,
            "test",
            "enc-chicken-1");
        model.offerSkillingOrConfirmed(bonesOnly, 1_100L, true);
        assertEquals(CollectionStatus.PARTIAL, model.current().collectionStatus());
        assertFalse(model.current().isCollectionConfirmed());
        assertEquals(1L, model.current().confirmedQuantity(526));
        assertEquals(0L, model.current().confirmedQuantity(2138));
    }

    @Test
    public void overlappingItemIdDifferentEncounterStaysUnconfirmed()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "enc-a",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            1_000L,
            true);
        ProfitTransaction otherKill = new ProfitTransaction(
            1_200L,
            0L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot from Goblin",
            "Goblin",
            true,
            Collections.singletonList(new ItemFlow(526, "Bones", 1L, 35, 35L)),
            ClassificationConfidence.LIKELY,
            "test",
            "enc-b");
        model.offerSkillingOrConfirmed(otherKill, 1_200L, true);
        // Different stable encounter while Ground Loot is protected must not wipe the
        // open tray or falsely confirm Chicken via item-id overlap alone.
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.NPC_LOOT, model.current().getSourceKind());
        assertEquals("Chicken", model.current().getSourceName());
        assertEquals(CollectionStatus.UNCONFIRMED, model.current().collectionStatus());
    }

    @Test
    public void volatileNpcLootPickupConfirmsRemainingStacks()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "npc:Goblin:1",
            java.util.Arrays.asList(
                item(2347, "Hammer", 1, 50L),
                item(526, "Bones", 1, 35L)),
            1_000L,
            true);
        ProfitTransaction pickup = new ProfitTransaction(
            1_100L,
            0L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot from Goblin",
            "Goblin",
            true,
            Collections.singletonList(new ItemFlow(2347, "Hammer", 1L, 50, 50L)),
            ClassificationConfidence.LIKELY,
            "test",
            "npc:Goblin:2");
        model.offerSkillingOrConfirmed(pickup, 1_100L, true);
        assertNotNull(model.current());
        assertTrue(model.current().getSourceKind().isObservedLoot());
        assertEquals(CollectionStatus.PARTIAL, model.current().collectionStatus());
        assertEquals(1L, model.current().confirmedQuantity(2347));
        assertEquals(0L, model.current().confirmedQuantity(526));
        assertEquals(1, model.current().presentationStacks().size());
        assertEquals("Hammer", model.current().presentationStacks().get(0).getItemName());
        assertEquals(HudTrayState.RECEIVED, HudTrayState.resolve(model.current()));
    }

    @Test
    public void volatileEncounterOverlapDoesNotConfirm()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:1",
            java.util.Arrays.asList(
                item(526, "Bones", 1, 35L),
                item(2138, "Raw chicken", 1, 31L)),
            1_000L,
            true);
        ProfitTransaction pickup = new ProfitTransaction(
            1_100L,
            0L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot from Chicken",
            "Chicken",
            true,
            Collections.singletonList(new ItemFlow(526, "Bones", 1L, 35, 35L)),
            ClassificationConfidence.LIKELY,
            "test",
            "npc:Chicken:2");
        model.offerSkillingOrConfirmed(pickup, 1_100L, true);
        // Same-source volatile pickup confirms remaining overlap (Bones only).
        assertEquals(CollectionStatus.PARTIAL, model.current().collectionStatus());
        assertEquals(1L, model.current().confirmedQuantity(526));
        assertEquals(0L, model.current().confirmedQuantity(2138));
    }

    @Test
    public void emptyEncounterPickupConfirmsVolatileGroundLoot()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "npc:Goblin:" + System.nanoTime(),
            java.util.Arrays.asList(
                item(2347, "Hammer", 1, 50L),
                item(288, "Goblin mail", 1, 231L)),
            1_000L,
            true);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_100L, 0L, TransactionType.LOOT, TrackingContext.LOOT,
            "Loot from Goblin", "Goblin", true,
            Collections.singletonList(new ItemFlow(2347, "Hammer", 1L, 50, 50L)),
            ClassificationConfidence.LIKELY, "test", ""), 1_100L, true);
        assertEquals(RewardSourceKind.NPC_LOOT, model.current().getSourceKind());
        assertEquals(1L, model.current().confirmedQuantity(2347));
        assertEquals("Hammer", model.current().presentationStacks().get(0).getItemName());
        assertEquals(HudTrayState.RECEIVED, HudTrayState.resolve(model.current()));
    }

    @Test
    public void completePickupAcrossTransactionsCollects()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "enc-full",
            java.util.Arrays.asList(
                item(526, "Bones", 1, 35L),
                item(2138, "Raw chicken", 1, 31L)),
            1_000L,
            true);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_100L, 0L, TransactionType.LOOT, TrackingContext.LOOT,
            "Loot", "Chicken", true,
            Collections.singletonList(new ItemFlow(526, "Bones", 1L, 35, 35L)),
            ClassificationConfidence.LIKELY, "test", "enc-full"), 1_100L, true);
        assertEquals(CollectionStatus.PARTIAL, model.current().collectionStatus());
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_200L, 0L, TransactionType.LOOT, TrackingContext.LOOT,
            "Loot", "Chicken", true,
            Collections.singletonList(new ItemFlow(2138, "Raw chicken", 1L, 31, 31L)),
            ClassificationConfidence.LIKELY, "test", "enc-full"), 1_200L, true);
        assertEquals(CollectionStatus.COLLECTED, model.current().collectionStatus());
    }

    @Test
    public void partialPickupSettledHighlightPrefersConfirmedNotTopDrop()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "enc-partial-top",
            java.util.Arrays.asList(
                item(526, "Bones", 1, 35L),
                item(995, "Coins", 1, 50_000L)),
            1_000L,
            true);
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_100L, 0L, TransactionType.LOOT, TrackingContext.LOOT,
            "Loot", "Goblin", true,
            Collections.singletonList(new ItemFlow(526, "Bones", 1L, 35, 35L)),
            ClassificationConfidence.LIKELY, "test", "enc-partial-top"), 1_100L, true);
        assertEquals(CollectionStatus.PARTIAL, model.current().collectionStatus());
        assertEquals(995, model.current().bestItem().getItemId());
        assertEquals(526, model.current().bestConfirmedItem().getItemId());
        assertEquals(526, model.current().bestSettledDisplayItem().getItemId());
        assertEquals(CollectionStatus.PARTIAL, model.current().collectionStatus());
        assertEquals("Partially collected", model.current().collectionStatus().getLabel());
    }

    @Test
    public void inventoryConfirmedDoesNotMergeIntoNpcLootAsFullyCollected()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "enc-keep-open",
            java.util.Arrays.asList(
                item(526, "Bones", 1, 35L),
                item(995, "Coins", 1, 50_000L)),
            1_000L,
            true);
        // Same encounter + inventory-confirmed must not merge into NPC loot and
        // blanket-confirm unpicked stacks (Auto-collapse "top drop counted" bug).
        model.offerObservation(
            RewardSourceKind.INVENTORY_CONFIRMED,
            "Loot",
            "enc-keep-open",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            1_100L,
            true);
        if (model.current().getSourceKind().isObservedLoot())
        {
            assertEquals(CollectionStatus.UNCONFIRMED, model.current().collectionStatus());
            assertEquals(0L, model.current().confirmedQuantity(995));
        }
        else
        {
            assertEquals(RewardSourceKind.INVENTORY_CONFIRMED, model.current().getSourceKind());
            assertEquals(1, model.current().getItems().size());
            assertEquals(526, model.current().getItems().get(0).getItemId());
        }
    }

    @Test
    public void revokeAcquisitionEvidenceClearsPartial()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "enc-rev",
            java.util.Arrays.asList(
                item(526, "Bones", 1, 35L),
                item(2138, "Raw chicken", 1, 31L)),
            1_000L,
            true);
        ProfitTransaction bones = new ProfitTransaction(
            1_100L, 0L, TransactionType.LOOT, TrackingContext.LOOT,
            "Loot", "Chicken", true,
            Collections.singletonList(new ItemFlow(526, "Bones", 1L, 35, 35L)),
            ClassificationConfidence.LIKELY, "test", "enc-rev");
        model.offerSkillingOrConfirmed(bones, 1_100L, true);
        assertEquals(CollectionStatus.PARTIAL, model.current().collectionStatus());
        model.revokeAcquisitionEvidence(bones);
        assertEquals(CollectionStatus.UNCONFIRMED, model.current().collectionStatus());
    }

    @Test
    public void multiKillBurstRefreshesIdleAndClearsOnlyAfterQuiet()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setLootCoalesceTicks(2);
        model.setRevealDwellMillis(1_000L);
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        assertTrue(model.isLootCoalescing());
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:2", 1_500L);
        assertNotNull(model.current());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        assertEquals(2, model.current().getBatch().getEncounterCount());
        // Still within coalesce quiet of the second kill (1500 + 1200).
        model.tick(2_400L);
        assertNotNull(model.current());
        assertTrue(model.isLootCoalescing());
        // Quiet ends → lock + dwell (2700 + 1000).
        model.tick(2_700L);
        assertTrue(model.isLootBatchLocked());
        assertNotNull(model.current());
        model.tick(3_701L);
        assertNull(model.current());
        assertEquals(RewardPresentationPhase.NONE, model.phase());
    }

    @Test
    public void unconfirmedAutoCollapseClearsAfterRevealDwellIfUnlooted()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setLootCoalesceTicks(1);
        model.setRevealDwellMillis(400L);
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:1",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            1_000L,
            true);
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        model.tick(1_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK);
        model.tick(1_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK + 400L + 1L);
        assertNull(model.current());
        assertEquals(RewardPresentationPhase.NONE, model.phase());
    }

    @Test
    public void skillingAutoCollapseClearsAfterDwell()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(400L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 1L, 39L), 1_000L, true);
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));
        assertNotNull(model.current());
        model.tick(1_401L);
        assertNull(model.current());
        assertEquals(RewardPresentationPhase.NONE, model.phase());
    }

    @Test
    public void retargetClearsAutoCollapseCardButNotAlwaysExpanded()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        // Ground Loot coalesce/countdown is non-interrupt — header retarget must not wipe tray.
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:1",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            1_000L,
            true);
        model.clearLiveCardOnRetarget("Goblin");
        assertNotNull(model.current());

        model.clearLiveCardOnRetarget("Chicken");
        assertNotNull(model.current());

        model.setPinnedExpanded(true, 2_100L);
        model.clearLiveCardOnRetarget("Goblin");
        assertNotNull(model.current());

        // Skilling Auto-collapse still clears on retarget when not protected loot.
        model.clear();
        model.setPinnedExpanded(false, 3_000L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 1L, 39L), 3_000L, true);
        model.clearLiveCardOnRetarget("Goblin");
        assertNull(model.current());
    }

    @Test
    public void consumptionLossAppearsOnHudPlusSettledPath()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction used = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Drank prayer potion",
            "Prayer",
            true,
            Collections.singletonList(
                new ItemFlow(2434, "Prayer potion(4)", -1L, 8_000, -8_000L)));
        model.offerSkillingOrConfirmed(used, 1_000L, true);
        assertNotNull(model.current());
        assertTrue(model.current().bestItem().isLoss());
        assertEquals(-8_000L, model.current().bestItem().getRecordedValue());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        assertEquals(RewardSourceKind.USED, model.current().getSourceKind());
        assertEquals("Used", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void doseStepConsumptionShowsSpentAndLeftoverWithNetHonestValues()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction drink = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Drink",
            "Prayer",
            true,
            Arrays.asList(
                new ItemFlow(2434, "Prayer potion(4)", -1L, 200, -200L),
                new ItemFlow(139, "Prayer potion(3)", 1L, 100, 100L)));
        model.offerSkillingOrConfirmed(drink, 1_000L, true);
        assertNotNull(model.current());
        assertEquals(2, model.current().getItems().size());
        long trayNet = 0L;
        for (RewardItem item : model.current().getItems())
        {
            trayNet += item.getRecordedValue();
        }
        assertEquals(-100L, trayNet);
        // B10: a dose step without action evidence is "Supplies used", never Mixed.
        assertEquals("Supplies used", HudTrayState.tagLine(model.current()));
        drink.setActionKind(com.gpmanager.model.ActionKind.DRINK);
        RewardPresentationModel evidenced = new RewardPresentationModel();
        evidenced.offerSkillingOrConfirmed(drink, 1_000L, true);
        assertEquals("Drank", HudTrayState.tagLine(evidenced.current()));
    }

    @Test
    public void hardBankDepositQueuesBehindProtectedGroundLoot()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:bank-1",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            1_000L,
            true);
        assertNotNull(model.current());
        assertTrue(model.isLootCoalescing() || model.isLootBatchLocked()
            || HudTrayState.isGroundUnconfirmed(model.current()));
        ProfitTransaction deposit = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank deposit",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(526, "Bones", -1L, 35, -35L)));
        model.offerSkillingOrConfirmed(deposit, 2_000L, true);
        assertNotNull(model.current());
        assertTrue(model.current().getSourceKind().isObservedLoot());
        assertEquals("Chicken", model.current().getSourceName());
    }

    @Test
    public void alwaysExpandedPreservesSkillingThroughBankDeposit()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 4, 39L), 1_000L, true);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        ProfitTransaction deposit = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank deposit",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(1519, "Logs", -1L, 40, -40L)));
        model.offerSkillingOrConfirmed(deposit, 2_000L, true);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals("Oak logs", model.current().bestItem().getItemName());
    }

    @Test
    public void alwaysExpandedFullOakDepositFlashesDepositThenClears()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.setRevealDwellMillis(1_400L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 20L, 39L), 1_000L, true);
        ProfitTransaction deposit = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank deposit",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(1511, "Oak logs", -20L, 39, -780L)));
        model.offerSkillingOrConfirmed(deposit, 2_000L, true);
        assertEquals(RewardSourceKind.BANKED, model.current().getSourceKind());
        assertEquals(HudTrayState.BANKED, HudTrayState.resolve(model.current()));
        assertEquals("Deposit", HudTrayState.tagLine(model.current()));
        model.tick(2_000L + 1_400L + 1L);
        assertNull(model.current());
        assertEquals(RewardPresentationPhase.NONE, model.phase());
    }

    @Test
    public void alwaysExpandedPartialOakDepositLeavesChoppedRemainder()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 20L, 39L), 1_000L, true);
        ProfitTransaction deposit = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank deposit",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(1511, "Oak logs", -10L, 39, -390L)));
        model.offerSkillingOrConfirmed(deposit, 2_000L, true);
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));
        assertEquals(10L, model.current().bestItem().getQuantity());
        assertEquals(390L, model.current().bestItem().getRecordedValue());
    }

    @Test
    public void alwaysExpandedDepositStripsOnlyOverlappingResource()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 10L, 39L), 1_000L, true);
        model.offerSkillingOrConfirmed(woodcutting(1519, "Willow logs", 8L, 19L), 1_200L, true);
        ProfitTransaction deposit = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank deposit",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", -8L, 19, -152L)));
        model.offerSkillingOrConfirmed(deposit, 2_000L, true);
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals(1, model.current().getItems().size());
        assertEquals(1511, model.current().bestItem().getItemId());
        assertEquals(10L, model.current().bestItem().getQuantity());
    }

    @Test
    public void alwaysExpandedPartialDropFlashesDroppedThenRestoresChopped()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.setRevealDwellMillis(1_400L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 20L, 39L), 1_000L, true);
        ProfitTransaction dump = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Dropped",
            "Woodcutting",
            true,
            Collections.singletonList(new ItemFlow(1511, "Oak logs", -5L, 39, -195L)));
        model.offerSkillingOrConfirmed(dump, 2_000L, true);
        assertEquals(RewardSourceKind.USED, model.current().getSourceKind());
        assertEquals("Dropped", HudTrayState.tagLine(model.current()));
        model.tick(2_000L + 1_400L + 1L);
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));
        assertEquals(15L, model.current().bestItem().getQuantity());
    }

    @Test
    public void alwaysExpandedFullDropFlashesDroppedThenClears()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.setRevealDwellMillis(1_400L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 20L, 39L), 1_000L, true);
        ProfitTransaction dump = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Dropped",
            "Woodcutting",
            true,
            Collections.singletonList(new ItemFlow(1511, "Oak logs", -20L, 39, -780L)));
        model.offerSkillingOrConfirmed(dump, 2_000L, true);
        assertEquals(RewardSourceKind.USED, model.current().getSourceKind());
        assertEquals("Dropped", HudTrayState.tagLine(model.current()));
        model.tick(2_000L + 1_400L + 1L);
        assertNull(model.current());
    }

    @Test
    public void alwaysExpandedEatDoesNotWipeChoppedTray()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 20L, 39L), 1_000L, true);
        ProfitTransaction eat = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Ate food",
            "Combat",
            true,
            Collections.singletonList(new ItemFlow(385, "Shark", -1L, 500, -500L)));
        model.offerSkillingOrConfirmed(eat, 2_000L, true);
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));
        assertEquals(1511, model.current().bestItem().getItemId());
        assertEquals(20L, model.current().bestItem().getQuantity());
    }

    @Test
    public void alwaysExpandedUnrelatedProcessKeepsChoppedReceipts()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 20L, 39L), 1_000L, true);
        ProfitTransaction brew = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.PROCESSING,
            TrackingContext.PRODUCTION,
            "Brewed",
            "Herblore",
            true,
            java.util.Arrays.asList(
                new ItemFlow(249, "Guam leaf", -1L, 50, -50L),
                new ItemFlow(91, "Guam potion (unf)", 1L, 80, 80L)));
        model.offerSkillingOrConfirmed(brew, 2_000L, true);
        assertEquals("Session", model.current().getSourceName());
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 1511));
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 91));
    }

    @Test
    public void alwaysExpandedOverlappingFletchMergesIntoSessionBag()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 20L, 39L), 1_000L, true);
        ProfitTransaction shafts = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.PROCESSING,
            TrackingContext.PRODUCTION,
            "Fletched",
            "Fletching",
            true,
            java.util.Arrays.asList(
                new ItemFlow(1511, "Oak logs", -1L, 39, -39L),
                new ItemFlow(52, "Arrow shaft", 15L, 5, 75L)));
        model.offerSkillingOrConfirmed(shafts, 2_000L, true);
        assertEquals("Session", model.current().getSourceName());
        long oakQty = model.current().getItems().stream()
            .filter(i -> i.getItemId() == 1511)
            .mapToLong(RewardItem::getQuantity)
            .sum();
        assertEquals(19L, oakQty);
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getItemId() == 52));
    }

    @Test
    public void alwaysExpandedTradeFlashesThenRestoresUnrelatedChopped()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.setRevealDwellMillis(1_400L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 20L, 39L), 1_000L, true);
        ProfitTransaction trade = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRADE,
            TrackingContext.MARKET,
            "Player trade",
            "Trading",
            false,
            Collections.singletonList(new ItemFlow(995, "Coins", 500L, 1, 500L)));
        model.offerSkillingOrConfirmed(trade, 2_000L, true);
        assertEquals(RewardSourceKind.TRADED, model.current().getSourceKind());
        assertEquals("Traded", HudTrayState.tagLine(model.current()));
        model.tick(2_000L + 1_400L + 1L);
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));
        assertEquals(20L, model.current().bestItem().getQuantity());
    }

    @Test
    public void bankWithdrawDoesNotWipeUnrelatedGroundLoot()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:withdraw-1",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            1_000L,
            true);
        assertTrue(HudTrayState.isGroundUnconfirmed(model.current()));
        ProfitTransaction withdraw = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank withdraw",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(385, "Shark", 5L, 500, 2_500L)));
        model.offerSkillingOrConfirmed(withdraw, 2_000L, true);
        assertTrue(HudTrayState.isGroundUnconfirmed(model.current()));
        assertEquals("Chicken", model.current().getSourceName());
    }

    @Test
    public void pendingRewardsInventCollectBecomesReceived()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.offerPendingRewards(
            "Tombs of Amascut",
            "toa:chest:1",
            Collections.singletonList(item(22477, "Osmumten's fang", 1, 50_000_000L)),
            1_000L,
            true);
        assertEquals(HudTrayState.PENDING_REWARDS, HudTrayState.resolve(model.current()));
        ProfitTransaction collect = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot from Tombs of Amascut",
            "Tombs of Amascut",
            false,
            Collections.singletonList(new ItemFlow(22477, "Osmumten's fang", 1L, 50_000_000, 50_000_000L)));
        model.offerSkillingOrConfirmed(collect, 2_000L, true);
        assertEquals(RewardSourceKind.INVENTORY_CONFIRMED, model.current().getSourceKind());
        assertEquals(HudTrayState.RECEIVED, HudTrayState.resolve(model.current()));
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
    }

    @Test
    public void pendingRewardsBankDepositBecomesClaimed()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerPendingRewards(
            "Barrows",
            "barrows:chest:1",
            Collections.singletonList(item(4708, "Ahrim's hood", 1, 1_000_000L)),
            1_000L,
            true);
        ProfitTransaction deposit = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank deposit",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(4708, "Ahrim's hood", -1L, 1_000_000, -1_000_000L)));
        model.offerSkillingOrConfirmed(deposit, 2_000L, true);
        assertEquals(RewardSourceKind.CLAIMED, model.current().getSourceKind());
        assertEquals(HudTrayState.CLAIMED, HudTrayState.resolve(model.current()));
    }

    @Test
    public void alwaysExpandedRecoveredFlashesThenRestoresChopped()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.setRevealDwellMillis(1_400L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 20L, 39L), 1_000L, true);
        ProfitTransaction recovery = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.GENERIC,
            "Own-drop recovery",
            "Drop recovery",
            false,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", 5L, 19, 95L)));
        model.offerSkillingOrConfirmed(recovery, 2_000L, true);
        assertEquals(RewardSourceKind.RECOVERED, model.current().getSourceKind());
        assertEquals("Recovered", HudTrayState.tagLine(model.current()));
        model.tick(2_000L + 1_400L + 1L);
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));
        assertEquals(1511, model.current().bestItem().getItemId());
        assertEquals(20L, model.current().bestItem().getQuantity());
    }

    @Test
    public void alwaysExpandedRecoveredFlashesOverDroppedPeerThenClears()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.setRevealDwellMillis(1_400L);
        model.offerSkillingOrConfirmed(woodcutting(1519, "Willow logs", 20L, 19L), 1_000L, true);
        ProfitTransaction dump = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Dropped",
            "Woodcutting",
            true,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", -20L, 19, -380L)));
        model.offerSkillingOrConfirmed(dump, 2_000L, true);
        assertEquals("Dropped", HudTrayState.tagLine(model.current()));

        ProfitTransaction recovery = new ProfitTransaction(
            3_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.GENERIC,
            "Own-drop recovery",
            "Drop recovery",
            false,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", 20L, 19, 380L)));
        model.offerSkillingOrConfirmed(recovery, 3_000L, true);
        assertEquals(RewardSourceKind.RECOVERED, model.current().getSourceKind());
        assertEquals("Recovered", HudTrayState.tagLine(model.current()));
        model.tick(3_000L + 1_400L + 1L);
        assertNull(model.current());
    }

    @Test
    public void autoCollapseRecoveredClearsAfterIdleDwell()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(1_400L);
        ProfitTransaction recovery = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.GENERIC,
            "Own-drop recovery",
            "Drop recovery",
            false,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", 5L, 19, 95L)));
        model.offerSkillingOrConfirmed(recovery, 1_000L, true);
        assertEquals(RewardSourceKind.RECOVERED, model.current().getSourceKind());
        model.tick(1_000L + 1_400L + 1L);
        assertNull(model.current());
        assertEquals(RewardPresentationPhase.NONE, model.phase());
    }

    @Test
    public void alwaysExpandedPreservesProtectedGroundLootOnBankDeposit()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:ae-bank",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            1_000L,
            true);
        assertTrue(HudTrayState.isGroundUnconfirmed(model.current()));
        ProfitTransaction deposit = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank deposit",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(526, "Bones", -1L, 35, -35L)));
        model.offerSkillingOrConfirmed(deposit, 2_000L, true);
        assertNotNull(model.current());
        assertTrue(model.current().getSourceKind().isObservedLoot());
        assertEquals("Chicken", model.current().getSourceName());
    }

    @Test
    public void alwaysExpandedSettledUnpickedQueuesNextKill()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.setRevealDwellMillis(1_000L);
        model.setLootCoalesceTicks(1);
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:ae-q1",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            1_000L,
            true);
        // Lock coalesce at quiet end, then finish Tray dwell under Always Expanded.
        model.tick(1_000L + 600L + 1L);
        model.tick(1_000L + 600L + 1_000L + 1L);
        assertEquals(RewardPresentationPhase.SETTLED, model.phase());
        assertTrue(model.isLootBatchLocked());
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "npc:Goblin:ae-q2",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            3_000L,
            true);
        assertEquals("Chicken", model.current().getSourceName());
        assertTrue(model.isLootBatchLocked());
    }

    @Test
    public void consumptionUsesUsedKindAndDeathUsesLost()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction used = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Ate food",
            "Combat",
            true,
            Collections.singletonList(new ItemFlow(385, "Shark", -1L, 500, -500L)));
        model.offerSkillingOrConfirmed(used, 1_000L, true);
        assertEquals(RewardSourceKind.USED, model.current().getSourceKind());
        assertEquals(HudTrayState.USED, HudTrayState.resolve(model.current()));
        assertEquals("Used", HudTrayState.tagLine(model.current()));

        ProfitTransaction death = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.PK_DEATH_LOSS,
            TrackingContext.GENERIC,
            "Died",
            "Wilderness",
            true,
            Collections.singletonList(new ItemFlow(11802, "Armadyl godsword", -1L, 50_000_000, -50_000_000L)));
        model.offerSkillingOrConfirmed(death, 2_000L, true);
        assertEquals(RewardSourceKind.LOST, model.current().getSourceKind());
        assertEquals(HudTrayState.LOST, HudTrayState.resolve(model.current()));
    }

    @Test
    public void woodcuttingTagLineIsChopped()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 4, 39L), 1_000L, true);
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void tradeFlashesTradedOnAutoCollapse()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setLootCoalesceTicks(1);
        model.setRevealDwellMillis(400L);
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:trade-1",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            1_000L,
            true);
        assertTrue(HudTrayState.isGroundUnconfirmed(model.current()));
        ProfitTransaction trade = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRADE,
            TrackingContext.GENERIC,
            "Grand Exchange",
            "GE",
            false,
            Collections.singletonList(new ItemFlow(995, "Coins", 1000L, 1, 1000L)));
        model.offerSkillingOrConfirmed(trade, 2_000L, true);
        // Locked/coalescing Ground Loot is non-interrupt — Traded waits in the peer queue.
        assertEquals(RewardSourceKind.NPC_LOOT, model.current().getSourceKind());
        model.tick(1_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK);
        model.tick(1_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK + 400L + 1L);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.TRADED, model.current().getSourceKind());
        assertEquals(HudTrayState.TRADED, HudTrayState.resolve(model.current()));
    }

    @Test
    public void pkSupplyCostResolvesUsed()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction supply = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.PK_SUPPLY_COST,
            TrackingContext.GENERIC,
            "Supply",
            "Wilderness",
            true,
            Collections.singletonList(new ItemFlow(385, "Shark", -1L, 500, -500L)));
        model.offerSkillingOrConfirmed(supply, 1_000L, true);
        assertEquals(RewardSourceKind.USED, model.current().getSourceKind());
        assertEquals(HudTrayState.USED, HudTrayState.resolve(model.current()));
    }

    @Test
    public void alwaysExpandedPreservesChargedThroughBankDeposit()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true, 1_000L);
        model.offerObservation(
            RewardSourceKind.CHARGED,
            "Charged",
            "",
            Collections.singletonList(item(12926, "Toxic blowpipe", -1, -100L)),
            1_000L,
            true);
        assertEquals(RewardSourceKind.CHARGED, model.current().getSourceKind());
        ProfitTransaction deposit = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank deposit",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(526, "Bones", -1L, 35, -35L)));
        model.offerSkillingOrConfirmed(deposit, 2_000L, true);
        assertEquals(RewardSourceKind.CHARGED, model.current().getSourceKind());
        assertEquals(HudTrayState.CHARGED, HudTrayState.resolve(model.current()));
    }

    @Test
    public void processingWithoutActivityUsesMadeVerb()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction processing = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.PROCESSING,
            TrackingContext.GENERIC,
            "Processed",
            "",
            true,
            Collections.singletonList(new ItemFlow(1751, "Green dragon leather", 1L, 1000, 1000L)));
        model.offerSkillingOrConfirmed(processing, 1_000L, true);
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals("Made", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void dropReplacesSkillingCardWithDroppedFlash()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerSkillingOrConfirmed(woodcutting(1519, "Willow logs", 20L, 19L), 1_000L, true);
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));

        ProfitTransaction dump = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Dropped",
            "Woodcutting",
            true,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", -20L, 19, -380L)));
        model.offerSkillingOrConfirmed(dump, 2_000L, true);
        assertEquals(RewardSourceKind.USED, model.current().getSourceKind());
        assertEquals("Dropped", HudTrayState.tagLine(model.current()));
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
    }

    @Test
    public void destroyFlashShowsDestroyedTag()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction destroy = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Destroyed",
            "General",
            true,
            Collections.singletonList(new ItemFlow(995, "Coins", -1L, 1, -1L)));
        model.offerSkillingOrConfirmed(destroy, 2_000L, true);
        assertEquals(RewardSourceKind.USED, model.current().getSourceKind());
        assertEquals("Destroyed", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void buryWithIntentWaitsForXpThenOffered()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        // Legacy call without mode — Prayer default tray verb remains Offered:
        model.noteProcessSpendIntent("Prayer", 526, 1_000L);
        ProfitTransaction bury = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "Prayer",
            true,
            Collections.singletonList(new ItemFlow(526, "Bones", -1L, 35, -35L)));
        model.offerSkillingOrConfirmed(bury, 2_000L, true);
        assertNull(model.current());
        model.confirmProcessSpendXp("Prayer", 2_500L, true);
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals("Offered", HudTrayState.tagLine(model.current()));
        assertEquals("Bones -1", model.current().bestItem().compactLabel());
    }

    @Test
    public void groundBuryModePaintsBuriedAfterPrayerXp()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.noteProcessSpendIntent("Prayer", 526, 1_000L, "bury");
        ProfitTransaction bury = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "Prayer",
            true,
            Collections.singletonList(new ItemFlow(526, "Bones", -1L, 35, -35L)));
        model.offerSkillingOrConfirmed(bury, 2_000L, true);
        assertNull(model.current());
        model.confirmProcessSpendXp("Prayer", 2_500L, true);
        assertEquals("Buried", HudTrayState.tagLine(model.current()));
        assertEquals("Buried", model.current().getSourceName());
    }

    @Test
    public void prayerXpBeforeInventSettlePaintsBuriedOnce()
    {
        // Live bury: Prayer XP often lands before invent stabilization.
        RewardPresentationModel model = new RewardPresentationModel();
        model.noteProcessSpendIntent("Prayer", 526, 1_000L, "bury");
        model.confirmProcessSpendXp("Prayer", 1_200L, true);
        assertNull(model.current());

        ProfitTransaction bury = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "General",
            true,
            Collections.singletonList(new ItemFlow(526, "Bones", -1L, 35, -35L)));
        model.offerSkillingOrConfirmed(bury, 2_000L, true);

        assertNotNull(model.current());
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals("Buried", HudTrayState.tagLine(model.current()));
        assertEquals("Buried", model.current().getSourceName());
        // Must not be holding a pending Used timeout after XP-before-invent paint.
        model.tick(2_000L + 500L);
        assertEquals("Buried", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void usedFallbackLateXpUpgradesToBuriedNotOffered()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.noteProcessSpendIntent("Prayer", 526, 1_000L, "bury");
        ProfitTransaction bury = new ProfitTransaction(
            1_500L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "General",
            true,
            Collections.singletonList(new ItemFlow(526, "Bones", -1L, 35, -35L)));
        model.offerSkillingOrConfirmed(bury, 1_500L, true);
        assertNull(model.current());
        model.tick(1_500L + RewardPresentationModel.PROCESS_SPEND_WAIT_MILLIS);
        assertEquals("Used", HudTrayState.tagLine(model.current()));

        model.confirmProcessSpendXp("Prayer", 1_500L + RewardPresentationModel.PROCESS_SPEND_WAIT_MILLIS + 100L, true);
        assertEquals("Buried", HudTrayState.tagLine(model.current()));
        assertEquals("Buried", model.current().getSourceName());
    }

    @Test
    public void ashScatterModePaintsScatteredAfterPrayerXp()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.noteProcessSpendIntent("Prayer", 25769, 1_000L, "scatter");
        ProfitTransaction scatter = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "Prayer",
            true,
            Collections.singletonList(
                new ItemFlow(25769, "Fiendish ashes", -1L, 100, -100L)));
        model.offerSkillingOrConfirmed(scatter, 2_000L, true);
        model.confirmProcessSpendXp("Prayer", 2_500L, true);
        assertEquals("Scattered", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void altarOfferModePaintsOfferedAfterPrayerXp()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.noteProcessSpendIntent("Prayer", 526, 1_000L, "offer");
        ProfitTransaction offer = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "Prayer",
            true,
            Collections.singletonList(new ItemFlow(526, "Bones", -1L, 35, -35L)));
        model.offerSkillingOrConfirmed(offer, 2_000L, true);
        model.confirmProcessSpendXp("Prayer", 2_500L, true);
        assertEquals("Offered", HudTrayState.tagLine(model.current()));
        assertEquals("Offered", model.current().getSourceName());
    }

    @Test
    public void firemakingWaitsForXpBeforeBurnedTag()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.noteProcessSpendIntent("Firemaking", 1511, 1_000L);
        ProfitTransaction light = new ProfitTransaction(
            1_500L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "Woodcutting",
            true,
            Collections.singletonList(new ItemFlow(1511, "Logs", -1L, 100, -100L)));
        model.offerSkillingOrConfirmed(light, 1_500L, true);
        assertNull(model.current());

        model.confirmProcessSpendXp("Firemaking", 2_000L, true);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals("Burned", HudTrayState.tagLine(model.current()));
        assertEquals("Logs -1", model.current().bestItem().compactLabel());
    }

    @Test
    public void firemakingWithActivityNameBurnsImmediately()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction light = new ProfitTransaction(
            1_500L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "Firemaking",
            true,
            Collections.singletonList(new ItemFlow(1511, "Logs", -1L, 100, -100L)));
        model.offerSkillingOrConfirmed(light, 1_500L, true);
        assertEquals("Burned", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void eatingShrimpDuringFiremakingKeepsItsAteLabel()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.noteProcessSpendIntent("Firemaking", 1511, 1_000L);
        ProfitTransaction eat = new ProfitTransaction(
            1_500L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "Firemaking",
            true,
            Collections.singletonList(new ItemFlow(315, "Shrimps", -1L, 10, -10L)));
        eat.setActionKind(com.gpmanager.model.ActionKind.EAT);

        model.offerSkillingOrConfirmed(eat, 1_500L, true);
        assertEquals("Ate", HudTrayState.tagLine(model.current()));

        // A delayed Firemaking XP event must not relabel the food loss as Burned.
        model.confirmProcessSpendXp("Firemaking", 1_600L, true);
        assertEquals("Ate", HudTrayState.tagLine(model.current()));
        assertEquals("Shrimps -1", model.current().bestItem().compactLabel());
    }

    @Test
    public void processSpendFallsBackToUsedWithoutXp()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.noteProcessSpendIntent("Firemaking", 1511, 1_000L);
        ProfitTransaction light = new ProfitTransaction(
            1_500L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "General",
            true,
            Collections.singletonList(new ItemFlow(1511, "Logs", -1L, 100, -100L)));
        model.offerSkillingOrConfirmed(light, 1_500L, true);
        assertNull(model.current());
        model.tick(1_500L + RewardPresentationModel.PROCESS_SPEND_WAIT_MILLIS);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.USED, model.current().getSourceKind());
        assertEquals("Used", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void openCookingIntentClaimsRawFoodLossThenWaitsForCookingXp()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.noteProcessSpendIntent("Cooking", -1, 1_000L, "cook");
        ProfitTransaction cook = new ProfitTransaction(
            1_200L, null, TransactionType.CONSUMPTION, TrackingContext.GENERIC,
            "", "General", true,
            Collections.singletonList(new ItemFlow(383, "Raw shark", -1L, 900, -900L)));
        model.offerSkillingOrConfirmed(cook, 1_200L, true);
        assertNull("open intent does not claim a spend until matching XP arrives", model.current());

        model.confirmProcessSpendXp("Cooking", 1_300L, true);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals("Cooking", model.current().getSourceName());
        assertEquals("Raw shark -1", model.current().bestItem().compactLabel());
    }

    @Test
    public void openPrayerIntentRejectsMixedBoneAndSharkLosses()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.noteProcessSpendIntent("Prayer", -1, 1_000L, "bury");
        ProfitTransaction mixed = new ProfitTransaction(
            1_200L, null, TransactionType.CONSUMPTION, TrackingContext.GENERIC,
            "", "General", true,
            Arrays.asList(
                new ItemFlow(526, "Bones", -1L, 35, -35L),
                new ItemFlow(383, "Shark", -1L, 900, -900L)));
        model.offerSkillingOrConfirmed(mixed, 1_200L, true);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.USED, model.current().getSourceKind());
        assertEquals("Used", HudTrayState.tagLine(model.current()));

        // Prayer XP cannot retroactively relabel a mixed, incompatible loss as Buried.
        model.confirmProcessSpendXp("Prayer", 1_300L, true);
        assertEquals(RewardSourceKind.USED, model.current().getSourceKind());
        assertEquals("Used", HudTrayState.tagLine(model.current()));
        assertEquals(2, model.current().getItems().size());
    }

    @Test
    public void pairedProcessingShowsMixedBothStacksAndCountdown()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(2_000L);
        long now = 10_000L;
        model.beginProductionCountdown(now, 6_000L);
        ProfitTransaction hideToChaps = new ProfitTransaction(
            now,
            null,
            TransactionType.PROCESSING,
            TrackingContext.PRODUCTION,
            "Crafted",
            "Crafting",
            true,
            java.util.Arrays.asList(
                new ItemFlow(1745, "Green dragonhide", -1L, 1500, -1500L),
                new ItemFlow(1099, "Green d'hide chaps", 1L, 2000, 2000L)));
        model.offerSkillingOrConfirmed(hideToChaps, now, true);
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals(HudTrayState.MIXED, HudTrayState.resolve(model.current()));
        assertEquals("Crafted", HudTrayState.tagLine(model.current()));
        assertEquals(2, model.current().getItems().size());
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getQuantity() < 0));
        assertTrue(model.current().getItems().stream().anyMatch(i -> i.getQuantity() > 0));
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        // Countdown prefers PRODUCTION window over short default dwell.
        assertEquals(now + 6_000L, model.getRevealExpiresAtEpochMillis());
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Crafting", "Tracking", model, now, 2_000L, 0L, null, null);
        assertEquals(6, snap.dwellSecondsRemaining(now));
    }

    @Test
    public void logToShaftsProcessingIsMixed()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction shafts = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.PROCESSING,
            TrackingContext.PRODUCTION,
            "Fletched",
            "Fletching",
            true,
            java.util.Arrays.asList(
                new ItemFlow(1511, "Logs", -1L, 100, -100L),
                new ItemFlow(52, "Arrow shaft", 15L, 5, 75L)));
        model.offerSkillingOrConfirmed(shafts, 1_000L, true);
        assertEquals("Fletched", HudTrayState.tagLine(model.current()));
        assertEquals(2, model.current().getItems().size());
        assertTrue(model.current().getItems().stream().anyMatch(i ->
            i.getItemId() == 1511 && i.getQuantity() < 0L && i.getRecordedValue() < 0L));
        assertTrue(model.current().getItems().stream().anyMatch(i ->
            i.getItemId() == 52 && i.getQuantity() > 0L && i.getRecordedValue() > 0L));
    }

    @Test
    public void drinkUnderPrayerActivityStaysUsedNotOffered()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction drink = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "",
            "Prayer",
            true,
            Collections.singletonList(new ItemFlow(2434, "Prayer potion(4)", -1L, 8000, -8000L)));
        model.offerSkillingOrConfirmed(drink, 1_000L, true);
        assertEquals(RewardSourceKind.USED, model.current().getSourceKind());
        assertEquals("Used", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void ownDropRecoveryFlashesRecoveredNotBanked()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerSkillingOrConfirmed(woodcutting(1519, "Willow logs", 20L, 19L), 1_000L, true);
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));

        ProfitTransaction dump = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "Dropped",
            "Woodcutting",
            true,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", -20L, 19, -380L)));
        model.offerSkillingOrConfirmed(dump, 2_000L, true);
        assertEquals("Dropped", HudTrayState.tagLine(model.current()));

        ProfitTransaction recovery = new ProfitTransaction(
            3_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.GENERIC,
            "Own-drop recovery",
            "Drop recovery",
            false,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", 20L, 19, 380L)));
        model.offerSkillingOrConfirmed(recovery, 3_000L, true);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.RECOVERED, model.current().getSourceKind());
        assertEquals("Recovered", HudTrayState.tagLine(model.current()));
        assertEquals(HudTrayState.RECOVERED, HudTrayState.resolve(model.current()));
    }

    @Test
    public void hardBankDepositFlashesDepositNotUsed()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerSkillingOrConfirmed(woodcutting(1519, "Willow logs", 20L, 19L), 1_000L, true);
        ProfitTransaction deposit = new ProfitTransaction(
            2_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank deposit",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", -20L, 19, -380L)));
        model.offerSkillingOrConfirmed(deposit, 2_000L, true);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.BANKED, model.current().getSourceKind());
        assertEquals(HudTrayState.BANKED, HudTrayState.resolve(model.current()));
        assertEquals("Deposit", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void idleBankWithdrawFlashesWithdrew()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        ProfitTransaction withdraw = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank withdraw",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(385, "Shark", 5L, 500, 2_500L)));
        model.offerSkillingOrConfirmed(withdraw, 1_000L, true);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.BANKED, model.current().getSourceKind());
        assertEquals("Withdrew", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void withdrawWhileBankOpenFlashesOnlyAfterClose()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(500L);
        model.setBankUiOpen(true);
        ProfitTransaction withdraw = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank withdraw",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(385, "Shark", 5L, 500, 2_500L)));
        model.offerSkillingOrConfirmed(withdraw, 1_000L, true);
        assertEquals(null, model.current());
        model.offerSkillingOrConfirmed(new ProfitTransaction(
            1_100L, null, TransactionType.TRANSFER, TrackingContext.TRANSFER,
            "Bank withdraw", "Bank", false,
            Collections.singletonList(new ItemFlow(379, "Lobster", 3L, 100, 300L))), 1_100L, true);
        assertEquals(null, model.current());
        model.tick(1_200L);
        model.setBankUiOpen(false);
        assertNotNull(model.current());
        assertEquals("Withdrew", HudTrayState.tagLine(model.current()));
        assertEquals(2, model.current().getItems().size());
    }

    @Test
    public void differentNpcDuringCoalesceReplacesBurst()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setLootCoalesceTicks(2);
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        assertTrue(model.isLootCoalescing());
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "npc:Goblin:1",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            1_200L,
            true);
        assertEquals("Goblin", model.current().getSourceName());
        assertEquals(1, model.current().getBatch().getEncounterCount());
        assertTrue(model.isLootCoalescing());
    }

    @Test
    public void lockedLootQueuesPendingAndSkillingIsDeferred()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setLootCoalesceTicks(1);
        model.setRevealDwellMillis(2_000L);
        offer(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1_000L);
        model.tick(1_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK);
        assertTrue(model.isLootBatchLocked());
        assertFalse(model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:2",
            Collections.singletonList(item(526, "Bones", 1, 35L)),
            1_800L,
            true));
        assertEquals(1L, model.current().bestItem().getQuantity());

        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 1L, 39L), 1_900L, true);
        assertEquals(RewardSourceKind.NPC_LOOT, model.current().getSourceKind());

        model.tick(1_000L + RewardPresentationModel.MILLIS_PER_GAME_TICK + 2_000L + 1L);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.NPC_LOOT, model.current().getSourceKind());
        assertEquals(1L, model.current().bestItem().getQuantity());
        assertTrue(model.isLootCoalescing());
    }


    private static RewardItem item(int id, String name, long qty, long value)
    {
        return new RewardItem(id, name, qty, value, true, ItemPriceSource.GRAND_EXCHANGE);
    }

    private static void offer(
        RewardPresentationModel model, RewardSourceKind kind, String encounterId, long now)
    {
        model.offerObservation(
            kind,
            "Chicken",
            encounterId,
            Collections.singletonList(item(526, "Chicken bones", 1L, 35L)),
            now,
            true);
    }

    private static ProfitTransaction woodcutting(int id, String name, long qty, long unit)
    {
        return skillGain(id, name, qty, unit, "Woodcutting");
    }

    private static ProfitTransaction skillGain(int id, String name, long qty, long unit, String skill)
    {
        return new ProfitTransaction(
            System.currentTimeMillis(),
            null,
            TransactionType.GAIN,
            TrackingContext.GENERIC,
            "Gathered while " + skill,
            skill,
            true,
            Collections.singletonList(new ItemFlow(id, name, qty, (int) unit, unit * qty)));
    }

    private static ProfitTransaction gain(int id, String name, long qty, long unit)
    {
        return skillGain(id, name, qty, unit, "Woodcutting");
    }

    @Test
    public void singleFloorPickupAlwaysRevealsReceivedTrayUnderAutoCollapse()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        // Empty encounter + LOOT → Recent pickups path (world floor Water rune).
        ProfitTransaction pickup = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Floor pickup",
            "General",
            true,
            Collections.singletonList(new ItemFlow(555, "Water rune", 12L, 5, 60L)));
        model.offerSkillingOrConfirmed(pickup, 1_000L, true);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.RECENT_PICKUPS, model.current().getSourceKind());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        assertEquals("Received", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void softConfirmGroundLootOpensReceivedUnderAutoCollapse()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "npc:Goblin:1",
            Arrays.asList(
                item(555, "Water rune", 5, 25L),
                item(526, "Bones", 1, 35L)),
            1_000L,
            true);
        model.tick(1_050L);
        ProfitTransaction pickup = new ProfitTransaction(
            1_100L,
            null,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Pickup",
            "Goblin",
            true,
            Collections.singletonList(new ItemFlow(555, "Water rune", 5L, 5, 25L)));
        model.offerSkillingOrConfirmed(pickup, 1_100L, true);
        assertEquals(CollectionStatus.PARTIAL, model.current().collectionStatus());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        assertEquals("Received", HudTrayState.tagLine(model.current()));
    }
}
