package com.gpmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TrackingInsightsSnapshot;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import com.gpmanager.persistence.SavedState;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class TrackingInsightsTest
{
    @Test
    public void generalOnlyInsightsPersistAcrossCompactionAndCorrections()
    {
        long now = System.currentTimeMillis();
        ProfitSession general = new ProfitSession("General", now - 10_000L);
        general.addTransaction(transaction(now - 5_000L, "Woodcutting", 250L), 1);
        general.addTransaction(transaction(now - 4_000L, "Woodcutting", -75L), 1); // compacts the first row
        assertEquals(1, general.getAnalyticsDays().size());
        assertEquals(250L, general.getAnalyticsDays().get(0).getRevenue());
        GpManagerEngine engine = engine();
        SavedState saved = new SavedState(general, Collections.emptyList());
        // This test is about compaction/correction, not cross-zone migration.
        saved.setProfileTimeZoneId("UTC");
        engine.restore(saved);
        long migratedRevenue = 0L;
        for (com.gpmanager.model.TrackingDaySummary day : engine.getActiveSession().getAnalyticsDays())
        {
            migratedRevenue += day.getRevenue();
        }
        assertEquals(250L, migratedRevenue);

        TrackingInsightsSnapshot before = engine.getTrackingInsights(0, now);
        assertEquals(1, before.getDayCount());
        assertEquals(250L, before.getRevenue());
        assertEquals(75L, before.getCosts());
        assertEquals(0L, before.getSuppliesCosts());
        assertEquals(75L, before.getOtherCosts());
        assertTrue(before.isCostSplitAvailable());
        assertEquals(175L, before.getNet());
        assertTrue(before.getActivityNet().containsKey("Woodcutting"));

        String retained = general.getTransactions().get(0).getId();
        assertTrue(engine.correctTransaction(retained, TransactionCorrection.IGNORE, now, "test"));
        TrackingInsightsSnapshot corrected = engine.getTrackingInsights(0, now);
        assertEquals(250L, corrected.getNet());
        assertTrue(engine.undoLastTransaction(now + 1L) != null);
        assertEquals(250L, engine.getTrackingInsights(0, now + 2L).getNet());
    }

    @Test
    public void generalAndCustomAreCountedOnceAndPausedTimeDoesNotIncreaseRateDuration()
    {
        long now = System.currentTimeMillis();
        ProfitSession general = new ProfitSession("General", now - 20_000L);
        general.addTransaction(transaction(now - 18_000L, "General", 100L), 20);
        general.pause(now - 15_000L);
        ProfitSession custom = new ProfitSession("Vorkath", now - 10_000L);
        custom.addTransaction(transaction(now - 9_000L, "Vorkath", 300L), 20);
        GpManagerEngine engine = engine();
        engine.restore(new SavedState(general, custom, true, Collections.emptyList()));

        TrackingInsightsSnapshot summary = engine.getTrackingInsights(0, now);
        assertEquals(400L, summary.getNet());
        assertTrue("only active custom time is accumulated after General pauses", summary.getActiveMillis() < 20_000L);
    }

    @Test
    public void insightsRetainTheSuppliesAndOtherCostSplit()
    {
        long now = 1_790_000_000_000L;
        ProfitSession session = new ProfitSession("Costs", now - 60_000L);
        ProfitTransaction food = new ProfitTransaction(now - 30_000L, null, TransactionType.CONSUMPTION,
            TrackingContext.GENERIC, "Food", "Food", true,
            Collections.singletonList(new ItemFlow(1, "Food", -1L, 100, -100L)));
        food.setActionKind(com.gpmanager.model.ActionKind.EAT);
        session.addTransaction(food, 20);
        session.addTransaction(new ProfitTransaction(now - 20_000L, null, TransactionType.TRADE,
            TrackingContext.MARKET, "Market purchase", "Trading", true,
            Collections.singletonList(new ItemFlow(2, "Item", -1L, 40, -40L))), 20);
        session.addTransaction(new ProfitTransaction(now - 10_000L, null, TransactionType.LOOT,
            TrackingContext.GENERIC, "Death loss", "Death", true,
            Collections.singletonList(new ItemFlow(3, "Gear", -1L, 60, -60L))), 20);

        session = new com.google.gson.Gson().fromJson(
            new com.google.gson.Gson().toJson(session), ProfitSession.class);
        GpManagerEngine engine = engine();
        engine.restore(new SavedState(session, Collections.emptyList()));
        TrackingInsightsSnapshot snapshot = engine.getTrackingInsights(0, now);

        assertEquals(200L, snapshot.getCosts());
        assertEquals(100L, snapshot.getSuppliesCosts());
        assertEquals(100L, snapshot.getOtherCosts());
        assertTrue(snapshot.isCostSplitAvailable());
    }

    @Test
    public void insightsRetainFilterableItemDetailsAndLegacyRowsSeparately()
    {
        long now = System.currentTimeMillis();
        ProfitSession general = new ProfitSession("General", now - 10_000L);
        long capturedAt = now - 6_000L;
        general.addTransaction(new ProfitTransaction(now - 5_000L, TransactionType.LOOT,
            TrackingContext.LOOT, "loot", true, Arrays.asList(
                new ItemFlow(526, "Bones", 2L, 35, 70L, com.gpmanager.model.ItemPriceSource.GRAND_EXCHANGE,
                    capturedAt),
                new ItemFlow(2, "Armadyl hilt", 1L, 12_000_000, 12_000_000L))), 1);
        GpManagerEngine engine = engine();
        engine.restore(new SavedState(general, Collections.emptyList()));

        TrackingInsightsSnapshot snapshot = engine.getTrackingInsights(0, now);

        assertEquals(12_000_070L, snapshot.getGainedItems().get("Bones")
            + snapshot.getGainedItems().get("Armadyl hilt"));
        assertTrue(snapshot.getLegacyGainedItems().isEmpty());
        assertEquals(2, snapshot.getGainedItemDetails().size());
        assertFalse(snapshot.getGainedItemDetails().get(0).toItemFlow() == null);
        com.gpmanager.model.TrackingGainedItemDetail bones = null;
        for (com.gpmanager.model.TrackingGainedItemDetail detail : snapshot.getGainedItemDetails())
        {
            if (detail.getItemId() == 526) bones = detail;
        }
        assertNotNull(bones);
        assertEquals(capturedAt, bones.getPriceCapturedAtEpochMillis());
        assertEquals(com.gpmanager.model.ItemPriceSource.GRAND_EXCHANGE, bones.getPriceSource());
    }

    @Test
    public void milestonesFailClosedUntilDurableEncounterEventTimeEvidenceExists()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public int notableDropThresholdGp() { return 25_000_000; }
        };
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);

        TrackingInsightsSnapshot snapshot = engine.getTrackingInsights(0, 10_000L);

        assertEquals(25_000_000, snapshot.getMilestoneThresholdGp());
        assertEquals(TrackingInsightsSnapshot.MILESTONES_UNAVAILABLE_NO_DURABLE_ENCOUNTER_EVIDENCE,
            snapshot.getMilestoneStatus());
        assertFalse(snapshot.areMilestonesAvailable());
        assertTrue("No kill count, elapsed time, or event-time rate is fabricated", snapshot.getMilestones().isEmpty());
    }

    private static GpManagerEngine engine()
    {
        GpManagerConfig config = new GpManagerConfig() {};
        return new GpManagerEngine(deltas -> Collections.emptyList(), new TransactionClassifier(), config);
    }

    private static ProfitTransaction transaction(long timestamp, String activity, long value)
    {
        return new ProfitTransaction(timestamp, null, TransactionType.LOOT, TrackingContext.LOOT, activity, activity, true,
            Arrays.asList(new ItemFlow(1, activity + " item", value >= 0 ? 1 : -1, (int) Math.abs(value), value)));
    }
}
