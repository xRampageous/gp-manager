package com.gpmanager.model;

import com.google.gson.Gson;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrackingDaySummaryTest
{
    @Test
    public void removesDetailedItemValuesFromLegacyFallback()
    {
        TrackingDaySummary day = new TrackingDaySummary("2026-09-13");
        ProfitTransaction transaction = new ProfitTransaction(1L, TransactionType.LOOT,
            TrackingContext.LOOT, "loot", true,
            Collections.singletonList(new ItemFlow(526, "Bones", 2L, 35, 70L)));

        day.addTransaction(transaction);

        assertEquals(Long.valueOf(70L), day.getGainedItems().get("Bones"));
        assertTrue(day.getUndetailedGainedItems().isEmpty());
        assertEquals(2L, day.getGainedItemDetails().get(0).getQuantity());
        assertEquals(70L, day.getGainedItemDetails().get(0).getValue());

        day.removeTransaction(transaction);

        assertTrue(day.getGainedItemDetails().isEmpty());
        assertTrue(day.getUndetailedGainedItems().isEmpty());
    }

    @Test
    public void oldNameOnlySaveRemainsAvailableAsLegacyInsightData()
    {
        TrackingDaySummary day = new Gson().fromJson(
            "{\"day\":\"2026-09-13\",\"gainedItems\":{\"Bones\":70}}",
            TrackingDaySummary.class);

        assertEquals(Long.valueOf(70L), day.getUndetailedGainedItems().get("Bones"));
        assertTrue(day.getGainedItemDetails().isEmpty());
    }

    @Test
    public void detailedInsightsRoundTripThroughGson()
    {
        TrackingDaySummary original = new TrackingDaySummary("2026-09-13");
        original.addTransaction(new ProfitTransaction(1L, TransactionType.LOOT,
            TrackingContext.LOOT, "loot", true,
            Collections.singletonList(new ItemFlow(526, "Bones", 2L, 35, 70L))));
        Gson gson = new Gson();

        TrackingDaySummary restored = gson.fromJson(gson.toJson(original), TrackingDaySummary.class);

        assertEquals(2L, restored.getGainedItemDetails().get(0).getQuantity());
        assertEquals(70L, restored.getGainedItemDetails().get(0).getValue());
        assertEquals(0L, restored.getGainedItemDetails().get(0).getPriceCapturedAtEpochMillis());
        assertTrue(restored.getUndetailedGainedItems().isEmpty());
    }

    @Test
    public void dailyInsightRetainsPriceSourceAndCaptureTimeWithoutMergingDifferentReads()
    {
        TrackingDaySummary day = new TrackingDaySummary("2026-09-13");
        ProfitTransaction first = valuedAt(1_000L);
        ProfitTransaction second = valuedAt(2_000L);
        day.addTransaction(first);
        day.addTransaction(second);

        assertEquals(2, day.getGainedItemDetails().size());
        TrackingGainedItemDetail detail = day.getGainedItemDetails().get(0);
        assertEquals(ItemPriceSource.GRAND_EXCHANGE, detail.getPriceSource());
        assertEquals(1_000L, detail.getPriceCapturedAtEpochMillis());
        assertEquals(1_000L, detail.toItemFlow().getPriceCapturedAtEpochMillis());
        assertEquals(ItemPriceSource.GRAND_EXCHANGE, detail.toItemFlow().getPriceSource());

        day.removeTransaction(first);
        assertEquals(1, day.getGainedItemDetails().size());
        assertEquals(2_000L, day.getGainedItemDetails().get(0).getPriceCapturedAtEpochMillis());
    }

    @Test
    public void oldSerializedFlowWithoutCaptureTimeRemainsUnavailable()
    {
        ItemFlow flow = new Gson().fromJson(
            "{\"itemId\":526,\"itemName\":\"Bones\",\"quantityDelta\":1,"
                + "\"unitPrice\":35,\"valueDelta\":35,\"priceSource\":\"GRAND_EXCHANGE\"}",
            ItemFlow.class);

        assertEquals(0L, flow.getPriceCapturedAtEpochMillis());
        assertEquals(ItemPriceSource.GRAND_EXCHANGE, flow.getPriceSource());
    }

    @Test
    public void dailyAccountingUsesEffectiveManualCorrectionForTotalsAndCostSplit()
    {
        TrackingDaySummary day = new TrackingDaySummary("2026-09-13");
        ProfitTransaction correctedGain = new ProfitTransaction(1L, TransactionType.LOOT,
            TrackingContext.LOOT, "loot", true,
            Collections.singletonList(new ItemFlow(526, "Bones", 1L, 50, 50L)));
        correctedGain.applyCorrection(TransactionCorrection.COST, 2L, "Manual review");

        day.addTransaction(correctedGain);

        assertEquals(0L, day.getRevenue());
        assertEquals(50L, day.getCosts());
        assertEquals(0L, day.getSuppliesCosts());
        assertEquals(50L, day.getOtherCosts());
        assertTrue(day.isCostSplitAvailable());

        day.removeTransaction(correctedGain);
        assertEquals(0L, day.getRevenue());
        assertEquals(0L, day.getCosts());
        assertEquals(0L, day.getOtherCosts());
    }

    @Test
    public void mixedFlowsPopulateTimeAndItemDimensionsAndRemoveSymmetrically()
    {
        ZoneId utc = ZoneOffset.UTC;
        TrackingDaySummary day = new TrackingDaySummary("2026-09-13", utc);
        ProfitTransaction gain = transaction("2026-09-13T01:15:00Z", TransactionType.LOOT,
            "Goblin", new ItemFlow(526, "Bones", 2L, 35, 70L));
        ProfitTransaction supply = transaction("2026-09-13T05:15:00Z", TransactionType.CONSUMPTION,
            "Goblin", new ItemFlow(385, "Shark", -1L, 100, -100L));
        supply.setActionKind(com.gpmanager.model.ActionKind.EAT);
        ProfitTransaction loss = transaction("2026-09-13T09:15:00Z", TransactionType.LOOT,
            "Goblin", new ItemFlow(560, "Death rune", -2L, 50, -100L));

        day.addTransaction(gain, utc);
        day.addTransaction(supply, utc);
        day.addTransaction(loss, utc);
        long start = Instant.parse("2026-09-13T02:00:00Z").toEpochMilli();
        day.addActiveMillis(5L * 60L * 60L * 1000L, start, utc, "Goblin");

        assertEquals(70L, day.getRevenue());
        assertEquals(200L, day.getCosts());
        assertEquals(100L, day.getSuppliesCosts());
        assertEquals(100L, day.getOtherCosts());
        assertEquals(-130L, day.getActivityNet().get("Goblin").longValue());
        assertEquals(5L * 60L * 60L * 1000L, day.getActivityActiveMillis().get("Goblin").longValue());
        assertTrue(day.isFourHourBucketsAvailable());
        assertTrue(day.isActivityActiveMillisAvailable());
        assertTrue(day.isGainedItemTotalsAvailable());
        assertTrue(day.isCostItemTotalsAvailable());
        assertEquals(70L, day.getFourHourNetGp()[0]);
        assertEquals(-100L, day.getFourHourNetGp()[1]);
        assertEquals(-100L, day.getFourHourNetGp()[2]);
        assertEquals(2L * 60L * 60L * 1000L, day.getFourHourActiveMillis()[0]);
        assertEquals(3L * 60L * 60L * 1000L, day.getFourHourActiveMillis()[1]);
        assertEquals(Long.valueOf(-100L), day.getFourHourNetByBucket().get("04:00-08:00"));
        assertEquals(Long.valueOf(70L), day.getFourHourNetByBucket().get("00:00-04:00"));

        DailyRollup.ItemTotal gained = day.getGainedItemTotals().get("526:Bones");
        assertEquals(2L, gained.getQuantity());
        assertEquals(70L, gained.getValueGp());
        assertEquals(1L, day.getCostItemTotals().get("385:Shark").getQuantity());
        assertEquals(2L, day.getCostItemTotals().get("560:Death rune").getQuantity());
        assertEquals(2, day.getTopCostItems().size());

        day.removeTransaction(loss, utc);
        day.removeTransaction(supply, utc);
        day.removeTransaction(gain, utc);
        day.removeActiveMillis(5L * 60L * 60L * 1000L, start, utc, "Goblin");

        assertEquals(0L, day.getRevenue());
        assertEquals(0L, day.getCosts());
        assertTrue(day.getGainedItemTotals().isEmpty());
        assertTrue(day.getCostItemTotals().isEmpty());
        assertTrue(Arrays.equals(new long[6], day.getFourHourNetGp()));
        assertTrue(Arrays.equals(new long[6], day.getFourHourActiveMillis()));
        assertTrue(day.getActivityActiveMillis().isEmpty());
        assertEquals(0L, day.getActiveMillis());
    }

    @Test
    public void manualCorrectionAndUndoMoveItemBetweenEffectiveDimensions()
    {
        TrackingDaySummary day = new TrackingDaySummary("2026-09-13");
        ProfitTransaction gain = transaction("2026-09-13T01:00:00Z", TransactionType.LOOT,
            "Goblins", new ItemFlow(526, "Bones", 2L, 40, 80L));

        day.addTransaction(gain);
        assertEquals(80L, day.getGainedItemTotals().get("526:Bones").getValueGp());
        day.removeTransaction(gain);

        gain.applyCorrection(TransactionCorrection.COST, 2L, "Manual review");
        day.addTransaction(gain);
        assertTrue(day.getGainedItemTotals().isEmpty());
        assertEquals(2L, day.getCostItemTotals().get("526:Bones").getQuantity());
        assertEquals(80L, day.getCostItemTotals().get("526:Bones").getValueGp());
        day.removeTransaction(gain);

        gain.applyCorrection(TransactionCorrection.AUTO, 3L, "Undo review");
        day.addTransaction(gain);
        assertEquals(80L, day.getRevenue());
        assertEquals(0L, day.getCosts());
        assertEquals(80L, day.getGainedItemTotals().get("526:Bones").getValueGp());
        assertTrue(day.getCostItemTotals().isEmpty());
        day.removeTransaction(gain);
        assertTrue(day.getGainedItemTotals().isEmpty());
    }

    @Test
    public void localDayKeysBucketsAndZoneSurviveSerialization()
    {
        ZoneId sydney = ZoneId.of("Australia/Sydney");
        long timestamp = Instant.parse("2026-09-13T23:30:00Z").toEpochMilli();
        TrackingDaySummary day = new TrackingDaySummary(timestamp, sydney);
        ProfitTransaction gain = transaction(timestamp, TransactionType.LOOT,
            "Goblins", new ItemFlow(526, "Bones", 1L, 35, 35L));
        day.addTransaction(gain, sydney);

        assertEquals("2026-09-14", day.getDay());
        assertEquals("Australia/Sydney", day.getZoneId());
        assertEquals("2026-09-14@Australia/Sydney", day.getDayKey());
        assertEquals("2026-09-14@Australia/Sydney", TrackingDaySummary.dayKey(timestamp, sydney));
        assertEquals(35L, day.getFourHourNetGp()[2]);

        TrackingDaySummary restored = new Gson().fromJson(new Gson().toJson(day), TrackingDaySummary.class);
        assertEquals("Australia/Sydney", restored.getZoneId());
        assertEquals(day.getDayKey(), restored.getDayKey());
        assertEquals(35L, restored.getFourHourNetGp()[2]);
        assertEquals(35L, restored.getGainedItemTotals().get("526:Bones").getValueGp());
    }

    @Test
    public void legacyJsonMarksNewDimensionsUnavailableAndNewSummariesStartComplete()
    {
        TrackingDaySummary legacy = new Gson().fromJson(
            "{\"day\":\"2026-09-13\",\"activeMillis\":123,\"activityNet\":{\"Goblin\":5}}",
            TrackingDaySummary.class);

        assertEquals("UTC", legacy.getZoneId());
        assertEquals(123L, legacy.getActiveMillis());
        assertFalse(legacy.isActiveTimeAvailable());
        assertFalse(legacy.isFourHourBucketsAvailable());
        assertFalse(legacy.isActivityActiveMillisAvailable());
        assertFalse(legacy.isGainedItemTotalsAvailable());
        assertFalse(legacy.isCostItemTotalsAvailable());

        TrackingDaySummary fresh = new TrackingDaySummary("2026-09-13");
        assertTrue(fresh.isActiveTimeAvailable());
        assertTrue(fresh.isFourHourBucketsAvailable());
        assertTrue(fresh.isActivityActiveMillisAvailable());
        assertTrue(fresh.isGainedItemTotalsAvailable());
        assertTrue(fresh.isCostItemTotalsAvailable());
    }

    private static ProfitTransaction transaction(String timestamp, TransactionType type,
        String activity, ItemFlow flow)
    {
        return transaction(Instant.parse(timestamp).toEpochMilli(), type, activity, flow);
    }

    private static ProfitTransaction transaction(long timestamp, TransactionType type,
        String activity, ItemFlow flow)
    {
        return new ProfitTransaction(timestamp, null, type, TrackingContext.GENERIC,
            activity, activity, true, Collections.singletonList(flow));
    }

    private static ProfitTransaction valuedAt(long capturedAt)
    {
        return new ProfitTransaction(capturedAt, TransactionType.LOOT, TrackingContext.LOOT,
            "Bones", true, Collections.singletonList(new ItemFlow(526, "Bones", 1L, 35, 35L,
                ItemPriceSource.GRAND_EXCHANGE, capturedAt)));
    }
}
