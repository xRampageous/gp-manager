package com.gpmanager.model;

import com.google.gson.Gson;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DailyRollupTest
{
    private static final LocalDate DAY = LocalDate.of(2026, 9, 13);
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    public void mergesSessionSlicesAndPreservesTheWeakestCoverage()
    {
        DailyRollup first = DailyRollup.builder(DAY, UTC)
            .addAccounting(1_000L, 300L)
            .addCostSplit(100L, 200L)
            .addActiveMillis(3_600_000L)
            .addEventCounts(10, 1, 1, 2)
            .addActivity("Goblin", 700L, 3_000_000L)
            .coverage(DailyRollup.Dimension.PVP, DailyRollup.Coverage.PARTIAL)
            .build();
        DailyRollup second = DailyRollup.builder(DAY, UTC)
            .addAccounting(500L, 50L)
            .addCostSplit(20L, 30L)
            .addActiveMillis(1_800_000L)
            .addEventCounts(5, 0, 1, 1)
            .addActivity("Goblin", 450L, 1_200_000L)
            .addPvp(2, 0, 900L, 0L, 600L, 0L)
            .build();

        DailyRollup result = DailyRollup.builder(DAY, UTC).merge(first).merge(second).build();

        assertEquals(DAY, result.getDate());
        assertEquals(UTC, result.getZone());
        assertEquals(1_500L, result.getRevenueGp());
        assertEquals(350L, result.getCostsGp());
        assertEquals(120L, result.getSuppliesCostsGp());
        assertEquals(230L, result.getLossCostsGp());
        assertEquals(1_150L, result.getNetGp());
        assertEquals(5_400_000L, result.getActiveMillis());
        assertEquals(15, result.getKills());
        assertEquals(1, result.getDeaths());
        assertEquals(2, result.getSessionStarts());
        assertEquals(3, result.getRunStarts());
        assertEquals(4_200_000L, result.getActivities().get("Goblin").getActiveMillis());
        assertEquals(1_150L, result.getActivities().get("Goblin").getNetGp());
        assertEquals(DailyRollup.Coverage.PARTIAL,
            result.getCoverage(DailyRollup.Dimension.PVP));
        assertTrue(result.isComplete(DailyRollup.Dimension.ACCOUNTING));
        assertFalse(result.isComplete(DailyRollup.Dimension.PVP));
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            result.getCoverage(DailyRollup.Dimension.GAINED_ITEMS));
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            result.getCoverage(DailyRollup.Dimension.COST_ITEMS));
    }

    @Test
    public void aggregatesActivityAndItemMapsThenReturnsDefensiveSummaries()
    {
        DailyRollup.Builder builder = DailyRollup.builder(DAY, UTC)
            .addActivityNet(Collections.singletonMap("Woodcutting", 200L),
                Collections.singletonMap("Woodcutting", 60_000L))
            .addActivities(Collections.singletonMap("Woodcutting",
                new DailyRollup.ActivityTotal("Woodcutting", 100L, 30_000L)))
            .addGainedItems(Collections.singletonMap("Logs", 500L))
            .addGainedItems(Arrays.asList(
                new DailyRollup.ItemTotal(1, "Bronze axe", 1L, 10L),
                new DailyRollup.ItemTotal(2, "Iron axe", 1L, 100L),
                new DailyRollup.ItemTotal(3, "Steel axe", 1L, 200L),
                new DailyRollup.ItemTotal(4, "Mithril axe", 1L, 300L),
                new DailyRollup.ItemTotal(5, "Adamant axe", 1L, 400L),
                new DailyRollup.ItemTotal(6, "Rune axe", 1L, 600L)))
            .addCostItems(Collections.singletonMap("Coins", 20L))
            .addCostItem(1, "Bronze axe", 2L, 30L);

        DailyRollup result = builder.build();
        assertEquals(300L, result.getActivities().get("Woodcutting").getNetGp());
        assertEquals(90_000L, result.getActivities().get("Woodcutting").getActiveMillis());
        assertEquals(7, result.getGainedItemTotals().size());
        assertEquals("Rune axe", result.getTopGainedItems().get(0).getItemName());
        assertEquals(7, result.getTopGainedItems().size());
        assertEquals("Bronze axe", result.getTopCostItems().get(0).getItemName());
        assertEquals(30L, result.getTopCostItems().get(0).getValueGp());
        assertEquals(2L, result.getTopCostItems().get(0).getQuantity());

        try
        {
            result.getGainedItemTotals().clear();
            fail("gained item map must be immutable");
        }
        catch (UnsupportedOperationException expected) { }
        try
        {
            result.getTopGainedItems().clear();
            fail("top-item list must be immutable");
        }
        catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void aggregatesCategoryNetAndActiveTimeAcrossSlices()
    {
        DailyRollup left = DailyRollup.builder(DAY, UTC)
            .addCategory(SessionCategory.BOSSING, 250L, 1_800_000L)
            .build();
        DailyRollup right = DailyRollup.builder(DAY, UTC)
            .addCategory(SessionCategory.BOSSING, -50L, 900_000L)
            .addCategory(SessionCategory.SKILLING, 100L, 600_000L)
            .build();

        DailyRollup merged = DailyRollup.builder(DAY, UTC).merge(left).merge(right).build();

        assertEquals(200L, merged.getCategoryTotals().get(SessionCategory.BOSSING).getNetGp());
        assertEquals(2_700_000L,
            merged.getCategoryTotals().get(SessionCategory.BOSSING).getActiveMillis());
        assertEquals(100L, merged.getCategoryTotals().get(SessionCategory.SKILLING).getNetGp());
        assertEquals(DailyRollup.Coverage.COMPLETE,
            merged.getCoverage(DailyRollup.Dimension.CATEGORIES));
    }

    @Test
    public void legacyRollupWithoutCategoriesKeepsThatDimensionUnavailable()
    {
        DailyRollup current = DailyRollup.builder(DAY, UTC)
            .addAccounting(100L, 20L)
            .addActiveMillis(60_000L)
            .build();
        com.google.gson.JsonObject json = new Gson().toJsonTree(current).getAsJsonObject();
        json.remove("categoryTotals");
        json.getAsJsonObject("coverage").remove("CATEGORIES");

        DailyRollup restored = new Gson().fromJson(json, DailyRollup.class);

        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            restored.getCoverage(DailyRollup.Dimension.CATEGORIES));
        assertTrue(restored.getCategoryTotals().isEmpty());
        assertEquals(DailyRollup.Coverage.COMPLETE,
            restored.getCoverage(DailyRollup.Dimension.ACCOUNTING));
        assertEquals(80L, restored.getNetGp());
    }

    @Test
    public void schema22NamedStartCoverageStaysUnavailableThroughProjectionAndMerge()
    {
        DailyRollup schema22 = DailyRollup.builder(DAY, UTC)
            .addAccounting(100L, 0L)
            .addNamedSessionStarts(1)
            .coverage(DailyRollup.Dimension.NAMED_SESSION_STARTS, DailyRollup.Coverage.COMPLETE)
            .build();
        com.google.gson.JsonObject json = new Gson().toJsonTree(schema22).getAsJsonObject();
        json.remove("namedSessionStarts");
        json.getAsJsonObject("coverage").remove("NAMED_SESSION_STARTS");

        DailyRollup restored = new Gson().fromJson(json, DailyRollup.class);
        DailyRollup filtered = restored.withoutUnfilteredContributions();
        DailyRollup current = DailyRollup.builder(DAY, UTC)
            .addNamedSessionStarts(2)
            .coverage(DailyRollup.Dimension.NAMED_SESSION_STARTS, DailyRollup.Coverage.COMPLETE)
            .build();
        DailyRollup merged = DailyRollup.builder(DAY, UTC).merge(filtered).merge(current).build();
        DailyRollup partialThenAdded = DailyRollup.builder(DAY, UTC)
            .coverage(DailyRollup.Dimension.NAMED_SESSION_STARTS, DailyRollup.Coverage.PARTIAL)
            .addNamedSessionStarts(3)
            .build();

        assertEquals(0, restored.getNamedSessionStarts());
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            restored.getCoverage(DailyRollup.Dimension.NAMED_SESSION_STARTS));
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            filtered.getCoverage(DailyRollup.Dimension.NAMED_SESSION_STARTS));
        assertEquals(2, merged.getNamedSessionStarts());
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            merged.getCoverage(DailyRollup.Dimension.NAMED_SESSION_STARTS));
        assertEquals(3, partialThenAdded.getNamedSessionStarts());
        assertEquals(DailyRollup.Coverage.PARTIAL,
            partialThenAdded.getCoverage(DailyRollup.Dimension.NAMED_SESSION_STARTS));
    }

    @Test
    public void capsTopItemSummariesAtTwenty()
    {
        ArrayList<DailyRollup.ItemTotal> items = new ArrayList<>();
        for (int i = 1; i <= 25; i++)
        {
            items.add(new DailyRollup.ItemTotal(i, "Item " + i, 1L, i * 100L));
        }
        DailyRollup result = DailyRollup.builder(DAY, UTC).addGainedItems(items).build();
        assertEquals(DailyRollup.TOP_ITEM_LIMIT, result.getGainedItemTotals().size());
        assertEquals(DailyRollup.TOP_ITEM_LIMIT, result.getTopGainedItems().size());
        assertEquals("Item 25", result.getTopGainedItems().get(0).getItemName());
        assertEquals(DailyRollup.Coverage.PARTIAL,
            result.getCoverage(DailyRollup.Dimension.GAINED_ITEMS));
    }

    @Test
    public void copiesBucketArraysAndRequiresAllSixBuckets()
    {
        long[] net = { 1L, -2L, 3L, -4L, 5L, -6L };
        long[] active = { 10L, 20L, 30L, 40L, 50L, 60L };
        DailyRollup.Builder builder = DailyRollup.builder(DAY, UTC).addFourHourBuckets(net, active);
        net[0] = 99L;
        active[0] = 99L;
        DailyRollup result = builder.build();

        long[] receivedNet = result.getFourHourNetGp();
        long[] receivedActive = result.getFourHourActiveMillis();
        assertArrayEquals(new long[] { 1L, -2L, 3L, -4L, 5L, -6L }, receivedNet);
        assertArrayEquals(new long[] { 10L, 20L, 30L, 40L, 50L, 60L }, receivedActive);
        assertNotSame(receivedNet, result.getFourHourNetGp());
        receivedNet[0] = 77L;
        assertEquals(1L, result.getFourHourNetGp()[0]);
        assertTrue(result.isComplete(DailyRollup.Dimension.FOUR_HOUR_BUCKETS));

        try
        {
            DailyRollup.builder(DAY, UTC).addFourHourBuckets(new long[5], new long[6]);
            fail("bucket arrays must contain six entries");
        }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void appliesLocalZoneToStartsAndPkEncounterSummaries()
    {
        ZoneId zone = ZoneId.of("Pacific/Auckland");
        long atLocalMidnight = DAY.atStartOfDay(zone).toInstant().toEpochMilli();
        PkEncounter kill = new PkEncounter(PkEncounterType.KILL,
            atLocalMidnight + 1_000L, "Victim", ClassificationConfidence.CONFIRMED, "test");
        PkEncounter nextDayDeath = new PkEncounter(PkEncounterType.DEATH,
            DAY.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            "Player death", ClassificationConfidence.CONFIRMED, "test");

        DailyRollup result = DailyRollup.builder(DAY, zone)
            .addSessionStart(atLocalMidnight)
            .addSessionStart(DAY.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            .addRunStart(atLocalMidnight + 2_000L)
            .addPkEncounter(kill, 250L, 0L)
            .addPkEncounter(nextDayDeath, -400L, 400L)
            .build();

        assertEquals(1, result.getSessionStarts());
        assertEquals(1, result.getRunStarts());
        assertEquals(1, result.getPvpKills());
        assertEquals(0, result.getPvpDeaths());
        assertEquals(250L, result.getPvpKillNetGp());
        assertEquals(250L, result.getPvpBestKillGp());
        assertEquals(0L, result.getPvpLossGp());
    }

    @Test
    public void pkMetricsCanBeConsumedAsAnAggregateAndMarkedUnavailable()
    {
        PkMetrics metrics = new PkMetrics(3, 2, 0, 1_000L, 500L, 500L,
            700L, 400L, 900L, 600L, false);
        DailyRollup result = DailyRollup.builder(DAY, UTC).addPkMetrics(metrics).build();

        assertEquals(3, result.getPvpKills());
        assertEquals(2, result.getPvpDeaths());
        assertEquals(900L, result.getPvpKillNetGp());
        assertEquals(600L, result.getPvpLossGp());
        assertEquals(700L, result.getPvpBestKillGp());
        assertEquals(400L, result.getPvpLargestLossGp());
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            result.getCoverage(DailyRollup.Dimension.PVP));
    }

    @Test
    public void saturatesTotalsAndCountersAndRejectsMismatchedDayOrZone()
    {
        DailyRollup saturated = DailyRollup.builder(DAY, UTC)
            .addAccounting(Long.MAX_VALUE, Long.MAX_VALUE)
            .addAccounting(1L, 1L)
            .addEventCounts(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0)
            .addEventCounts(1, 0, 1, 0)
            .addActivity("General", Long.MAX_VALUE, Long.MAX_VALUE)
            .addActivity("General", 1L, 1L)
            .build();
        assertEquals(Long.MAX_VALUE, saturated.getRevenueGp());
        assertEquals(Long.MAX_VALUE, saturated.getCostsGp());
        assertEquals(0L, saturated.getNetGp());
        assertEquals(Integer.MAX_VALUE, saturated.getKills());
        assertEquals(Integer.MAX_VALUE, saturated.getSessionStarts());
        assertEquals(Long.MAX_VALUE, saturated.getActivities().get("General").getNetGp());
        assertEquals(Long.MAX_VALUE, saturated.getActivities().get("General").getActiveMillis());

        try
        {
            DailyRollup.builder(DAY, UTC).merge(DailyRollup.builder(DAY.plusDays(1), UTC).build());
            fail("must reject a different local date");
        }
        catch (IllegalArgumentException expected) { }
        try
        {
            DailyRollup.builder(DAY, UTC).merge(DailyRollup.builder(DAY, ZoneId.of("Australia/Sydney")).build());
            fail("must reject a different local zone");
        }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void inconsistentCostSplitIsUnavailableAndCoverageMapIsImmutable()
    {
        DailyRollup result = DailyRollup.builder(DAY, UTC)
            .addAccounting(100L, 40L)
            .addCostSplit(10L, 20L)
            .build();

        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            result.getCoverage(DailyRollup.Dimension.COST_SPLIT));
        assertTrue(result.isComplete(DailyRollup.Dimension.ACCOUNTING));
        try
        {
            Map<DailyRollup.Dimension, DailyRollup.Coverage> coverage = result.getCoverageByDimension();
            coverage.clear();
            fail("coverage must be immutable");
        }
        catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void immutableRollupRoundTripsThroughGson()
    {
        DailyRollup original = DailyRollup.builder(DAY, ZoneId.of("Australia/Sydney"))
            .addAccounting(500L, 125L)
            .addCostSplit(25L, 100L)
            .addActivity("Fishing", 375L, 60_000L)
            .addFourHourBuckets(new long[] { 1, 2, 3, 4, 5, 6 },
                new long[] { 6, 5, 4, 3, 2, 1 })
            .coverage(DailyRollup.Dimension.PVP, DailyRollup.Coverage.PARTIAL)
            .build();

        DailyRollup restored = new Gson().fromJson(new Gson().toJson(original), DailyRollup.class);

        assertEquals(original.getDate(), restored.getDate());
        assertEquals(original.getZone(), restored.getZone());
        assertEquals(375L, restored.getNetGp());
        assertEquals(60_000L, restored.getActivities().get("Fishing").getActiveMillis());
        assertEquals(DailyRollup.Coverage.PARTIAL,
            restored.getCoverage(DailyRollup.Dimension.PVP));
        assertArrayEquals(original.getFourHourNetGp(), restored.getFourHourNetGp());
    }
}
