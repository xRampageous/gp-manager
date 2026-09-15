package com.gpmanager.ui;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LatestDropModelTest
{
    @Test
    public void selectsHighestStackValueFromMixedDrop()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(1, "Bones", 1L, 50, 50L),
            new ItemFlow(2, "Armadyl hilt", 1L, 8_000_000, 8_000_000L),
            new ItemFlow(3, "Coins", 1000L, 1, 1000L)), 1_000L, 2_000L);

        LatestDropHighlight drop = model.highlight();
        assertNotNull(drop);
        assertEquals("Armadyl hilt", drop.getItemName());
        assertEquals(8_000_000L, drop.getRecordedValue());
        assertTrue(model.isAnimating(1_100L));
        assertFalse(model.isAnimating(4_000L));
        assertNotNull(model.highlight());
    }

    @Test
    public void combinesDuplicateItemsInSameEncounter()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(1511, "Oak logs", 2L, 39, 78L)), 1_000L, 2_000L);
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(1511, "Oak logs", 1L, 39, 39L)), 1_200L, 2_000L);

        LatestDropHighlight drop = model.highlight();
        assertNotNull(drop);
        assertEquals(3L, drop.getQuantity());
        assertEquals(117L, drop.getRecordedValue());
    }

    @Test
    public void splitDeliveriesWithinOneEncounterReselectBest()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(1, "Bones", 1L, 50, 50L)), 1_000L, 2_000L);
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(2, "Armadyl hilt", 1L, 8_000_000, 8_000_000L)), 1_100L, 2_000L);

        assertEquals("Armadyl hilt", model.highlight().getItemName());
        assertEquals(2, model.getContributionCount());
    }

    @Test
    public void accumulatedItemsCanOvertakePreviousWinner()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(2, "Armadyl hilt", 1L, 1_000, 1_000L)), 1_000L, 2_000L);
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(1511, "Oak logs", 10L, 200, 2_000L)), 1_200L, 2_000L);

        LatestDropHighlight drop = model.highlight();
        assertNotNull(drop);
        assertEquals("Oak logs", drop.getItemName());
        assertEquals(10L, drop.getQuantity());
        assertEquals(2_000L, drop.getRecordedValue());
    }

    @Test
    public void skillingSameItemStacksQuantityWithoutEncounter()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("", TransactionType.GAIN,
            new ItemFlow(1511, "Oak logs", 1L, 39, 39L)), 1_000L, 2_000L);
        assertEquals(1L, model.highlight().getQuantity());
        model.offer(transaction("", TransactionType.GAIN,
            new ItemFlow(1511, "Oak logs", 1L, 39, 39L)), 1_600L, 2_000L);
        model.offer(transaction("", TransactionType.GAIN,
            new ItemFlow(1511, "Oak logs", 1L, 39, 39L)), 2_200L, 2_000L);

        LatestDropHighlight drop = model.highlight();
        assertEquals("Oak logs ×3", drop.compactLabel());
        assertEquals(3L, drop.getQuantity());
        assertEquals(117L, drop.getRecordedValue());
    }

    @Test
    public void sameMillisecondReplacementsAdvanceGeneration()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(2, "Armadyl hilt", 1L, 8_000_000, 8_000_000L)), 5_000L, 2_000L);
        int firstGen = model.highlight().getGeneration();
        model.offer(transaction("enc-2", TransactionType.LOOT,
            new ItemFlow(1511, "Oak logs", 1L, 500, 500L)), 5_000L, 2_000L);
        int secondGen = model.highlight().getGeneration();
        assertNotEquals(firstGen, secondGen);
    }

    @Test
    public void correctionRemovalRebuildsOrClearsHighlight()
    {
        LatestDropModel model = new LatestDropModel();
        ProfitTransaction hilt = transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(2, "Armadyl hilt", 1L, 8_000_000, 8_000_000L));
        ProfitTransaction logs = transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(1511, "Oak logs", 2L, 39, 78L));
        model.offer(hilt, 1_000L, 2_000L);
        model.offer(logs, 1_100L, 2_000L);
        assertEquals("Armadyl hilt", model.highlight().getItemName());

        model.invalidateSource(hilt.getId());
        assertEquals("Oak logs", model.highlight().getItemName());
        assertEquals(2L, model.highlight().getQuantity());

        model.invalidateSource(logs.getId());
        assertNull(model.highlight());
    }

    @Test
    public void lowerValueNextDropReplacesHighlight()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(2, "Armadyl hilt", 1L, 8_000_000, 8_000_000L)), 1_000L, 2_000L);
        model.offer(transaction("enc-2", TransactionType.LOOT,
            new ItemFlow(1511, "Oak logs", 1L, 500, 500L)), 2_000L, 2_000L);

        LatestDropHighlight drop = model.highlight();
        assertNotNull(drop);
        assertEquals("Oak logs", drop.getItemName());
        assertEquals(500L, drop.getRecordedValue());
        assertEquals(1, model.getReplacementCount());
    }

    @Test
    public void separateEncountersDoNotCoalesce()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("kill-a", TransactionType.PK_LOOT,
            new ItemFlow(1511, "Oak logs", 2L, 39, 78L)), 1_000L, 2_000L);
        model.offer(transaction("kill-b", TransactionType.PK_LOOT,
            new ItemFlow(1511, "Oak logs", 2L, 39, 78L)), 1_100L, 2_000L);
        assertEquals(1, model.getReplacementCount());
        assertEquals(2, model.getAnimationStartCount());
    }

    @Test
    public void transfersAndCostsDoNotReplaceHighlight()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(2, "Armadyl hilt", 1L, 8_000_000, 8_000_000L)), 1_000L, 2_000L);
        model.offer(transaction("", TransactionType.TRANSFER,
            new ItemFlow(995, "Coins", 100L, 1, 100L)), 1_500L, 2_000L);
        model.offer(transaction("", TransactionType.CONSUMPTION,
            new ItemFlow(385, "Shark", -1L, 800, -800L)), 1_600L, 2_000L);

        assertEquals("Armadyl hilt", model.highlight().getItemName());
        assertEquals(0, model.getReplacementCount());
    }

    @Test
    public void unknownPricingIsExplicit()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(99, "Mystery", 1L, 0, 0L, ItemPriceSource.UNKNOWN)), 1_000L, 2_000L);
        LatestDropHighlight drop = model.highlight();
        assertNotNull(drop);
        assertFalse(drop.isValueKnown());
        assertEquals("unpriced", drop.valueLabel());
    }

    @Test
    public void modeSwitchWithoutAnimationDoesNotReplayArrival()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(2, "Armadyl hilt", 1L, 8_000_000, 8_000_000L)),
            1_000L, 2_000L, false);
        assertFalse(model.isAnimating(1_100L));
        assertNotNull(model.highlight());
        assertEquals(0, model.getAnimationStartCount());
    }

    @Test
    public void clearDropsHighlightOnInvalidation()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(2, "Armadyl hilt", 1L, 8_000_000, 8_000_000L)), 1_000L, 2_000L);
        model.clear();
        assertNull(model.highlight());
    }

    @Test
    public void animationExpiryKeepsPersistentHighlight()
    {
        LatestDropModel model = new LatestDropModel();
        model.offer(transaction("enc-1", TransactionType.LOOT,
            new ItemFlow(2, "Armadyl hilt", 1L, 8_000_000, 8_000_000L)), 1_000L, 500L);
        assertFalse(model.isAnimating(2_000L));
        assertEquals("Armadyl hilt", model.highlight().getItemName());
    }

    private static ProfitTransaction transaction(
        String encounterId,
        TransactionType type,
        ItemFlow... flows)
    {
        ProfitTransaction tx = new ProfitTransaction(
            1_000L,
            0L,
            type,
            TrackingContext.LOOT,
            "",
            "Boss",
            true,
            Arrays.asList(flows));
        return withEncounter(tx, encounterId);
    }

    private static ProfitTransaction withEncounter(ProfitTransaction base, String encounterId)
    {
        return new ProfitTransaction(
            base.getTimestampEpochMillis(),
            base.getActiveElapsedMillis(),
            base.getType(),
            base.getContext(),
            base.getNote(),
            base.getActivityName(),
            base.isCounted(),
            base.getFlows(),
            base.getConfidence(),
            base.getExplanation(),
            encounterId);
    }
}
