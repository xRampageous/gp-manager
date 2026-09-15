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

/**
 * Ground Loot tray batching boundaries: pinned/expanded trays keep distinct pickups,
 * nearby kills with different loot still batch, and explicitly different encounters
 * never dedupe. Originally independent review probes (September 2026), kept as
 * permanent coverage under the subject they test.
 */
public class TrayBatchingBoundaryTest
{
    private static RewardItem item(int id, String name, long quantity)
    {
        return new RewardItem(id, name, quantity, quantity * 35L, true, ItemPriceSource.GRAND_EXCHANGE);
    }

    private static void observed(RewardPresentationModel model, RewardSourceKind kind,
        String encounter, long now, RewardItem... items)
    {
        model.offerObservation(kind, "Chicken", encounter, Arrays.asList(items), now, true);
    }

    private static ProfitTransaction pickup(long time, int id, String name)
    {
        return new ProfitTransaction(time, TransactionType.GAIN, TrackingContext.GENERIC,
            "Pickup", true, Collections.singletonList(new ItemFlow(id, name, 1L, 35, 35L)));
    }

    @Test
    public void differentPickupsMustRemainTogetherWhenExpanded()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true);
        model.offerSkillingOrConfirmed(pickup(1000L, 526, "Bones"), 1000L, true);
        model.offerSkillingOrConfirmed(pickup(1600L, 2138, "Raw chicken"), 1600L, true);
        assertEquals("both received item types must remain visible", 2, model.current().getItems().size());
    }


    @Test
    public void multipleInventoryItemsCanOpenPinnedTray()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setPinnedExpanded(true);
        ProfitTransaction transaction = new ProfitTransaction(1000L, TransactionType.GAIN,
            TrackingContext.GENERIC, "Pickup", true, Arrays.asList(
                new ItemFlow(526, "Bones", 1L, 35, 35L),
                new ItemFlow(2138, "Raw chicken", 1L, 35, 35L)));
        model.offerSkillingOrConfirmed(transaction, 1000L, true);
        assertEquals("Keep expanded must not force multi-item receipts into settled-only state",
            RewardPresentationPhase.REVEALING, model.phase());
    }


    @Test
    public void nearbyKillsWithDifferentLootMustStillBatch()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        observed(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:1", 1000L, item(526, "Bones", 1));
        observed(model, RewardSourceKind.NPC_LOOT, "npc:Chicken:2", 1600L,
            item(526, "Bones", 1), item(314, "Feather", 5));
        assertEquals("random loot variance must not discard the previous kill", 2,
            model.current().getBatch().getEncounterCount());
    }


    @Test
    public void explicitlyDifferentEncountersMustNotBeDedupeMatches()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        observed(model, RewardSourceKind.NPC_LOOT, "encounter-A", 1000L, item(526, "Bones", 1));
        observed(model, RewardSourceKind.SERVER_NPC_LOOT, "encounter-B", 1100L, item(526, "Bones", 1));
        assertTrue("a distinct identified encounter must replace or be added, not be discarded",
            "encounter-B".equals(model.current().getEncounterId())
                || model.current().getBatch().getEncounterCount() == 2);
    }
}
