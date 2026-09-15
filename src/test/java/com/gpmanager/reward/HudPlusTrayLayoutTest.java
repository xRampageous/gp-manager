package com.gpmanager.reward;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Layout (Always expanded / Auto-collapse) must not own the reveal clock. */
public class HudPlusTrayLayoutTest
{
    @Test
    public void alwaysExpandedUsesFiniteRevealAndKeepsLayoutFlag()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(1_000L);
        model.setLootCoalesceTicks(1);
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:1",
            Collections.singletonList(item(526, "Bones", 1)),
            1_000L,
            true);
        model.setPinnedExpanded(true, 1_100L);

        assertTrue(model.isPinnedExpanded());
        assertTrue(model.isRevealing(1_200L));
        // Lock coalesce at quiet end, then let Tray dwell finish under Always Expanded.
        model.tick(1_000L + 600L + 1L);
        model.tick(1_000L + 600L + 1_000L + 1L);
        assertEquals(RewardPresentationPhase.SETTLED, model.phase());
        assertTrue("layout flag survives reveal expiry", model.isPinnedExpanded());
        assertEquals(1, model.currentForHud(2_700L).getBatch().getEncounterCount());
    }

    @Test
    public void leavingAlwaysExpandedRestoresFiniteCountdown()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(800L);
        model.setLootCoalesceTicks(1);
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:1",
            Collections.singletonList(item(526, "Bones", 1)),
            1_000L,
            true);
        model.setPinnedExpanded(true, 1_050L);
        int reveals = model.getRevealStartCount();

        // Past coalesce lock so unpin starts a fresh finite Auto-collapse dwell.
        model.tick(1_000L + 600L + 1L);
        model.setPinnedExpanded(false, 2_000L);
        assertFalse(model.isPinnedExpanded());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        assertTrue(model.isRevealing(2_100L));
        model.tick(2_000L + 800L + 1L);
        assertEquals(RewardPresentationPhase.NONE, model.phase());
        assertTrue(model.getRevealStartCount() > reveals);
    }

    @Test
    public void repeatedLayoutSyncDoesNotRestartReveal()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(5_000L);
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:1",
            Collections.singletonList(item(526, "Bones", 1)),
            1_000L,
            true);
        model.setPinnedExpanded(true, 1_100L);
        int reveals = model.getRevealStartCount();
        model.setPinnedExpanded(true, 1_200L);
        model.setPinnedExpanded(true, 1_300L);
        assertEquals(reveals, model.getRevealStartCount());
    }

    @Test
    public void collectionStatusDoesNotReopenSettledTray()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(400L);
        model.setLootCoalesceTicks(1);
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "stable-encounter",
            Collections.singletonList(item(526, "Bones", 1)),
            1_000L,
            true);
        ProfitTransaction pickup = new ProfitTransaction(
            1_200L,
            TransactionType.GAIN,
            TrackingContext.LOOT,
            "Loot from Chicken",
            true,
            Collections.singletonList(new ItemFlow(526, "Bones", 1L, 35, 35L)));
        pickup.assignEncounter("stable-encounter", false);
        model.offerSkillingOrConfirmed(pickup, 1_200L, true);
        int reveals = model.getRevealStartCount();
        // Pickup refreshed coalesce quiet → lock then dwell before SETTLED.
        model.tick(1_200L + 600L + 1L);
        model.tick(1_200L + 600L + 400L + 1L);
        // Confirmed loot settles instead of clearing.
        assertEquals(RewardPresentationPhase.SETTLED, model.phase());
        assertNotNull(model.current());

        model.offerSkillingOrConfirmed(pickup, 2_500L, true);
        assertEquals(RewardPresentationPhase.SETTLED, model.phase());
        assertEquals(reveals, model.getRevealStartCount());
    }

    @Test
    public void skillingReplacesSettledGroundLootAfterCombatDwell()
    {
        // Combat flee left SETTLED collected loot with a leftover lock — skilling must resume.
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(400L);
        model.setLootCoalesceTicks(1);
        model.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "stable-goblin",
            Collections.singletonList(item(526, "Bones", 1)),
            1_000L,
            true);
        ProfitTransaction pickup = new ProfitTransaction(
            1_200L,
            TransactionType.GAIN,
            TrackingContext.LOOT,
            "Loot from Goblin",
            true,
            Collections.singletonList(new ItemFlow(526, "Bones", 1L, 35, 35L)));
        pickup.assignEncounter("stable-goblin", false);
        model.offerSkillingOrConfirmed(pickup, 1_200L, true);
        model.tick(1_200L + 600L + 1L);
        model.tick(1_200L + 600L + 400L + 1L);
        assertEquals(RewardPresentationPhase.SETTLED, model.phase());
        assertEquals(CollectionStatus.COLLECTED, model.current().collectionStatus());

        ProfitTransaction chop = new ProfitTransaction(
            3_000L,
            null,
            TransactionType.GAIN,
            TrackingContext.GENERIC,
            "Chopped",
            "Woodcutting",
            true,
            Collections.singletonList(new ItemFlow(1511, "Oak logs", 1L, 40, 40L)));
        model.offerSkillingOrConfirmed(chop, 3_000L, true);
        assertNotNull(model.current());
        assertEquals(RewardSourceKind.SKILLING, model.current().getSourceKind());
        assertEquals("Woodcutting", model.current().getSourceName());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
    }

    private static RewardItem item(int id, String name, long quantity)
    {
        return new RewardItem(id, name, quantity, quantity * 35L, true, ItemPriceSource.GRAND_EXCHANGE);
    }
}
