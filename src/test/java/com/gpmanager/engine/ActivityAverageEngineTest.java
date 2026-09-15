package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.LootPresentationFilter;
import com.gpmanager.grounditems.GroundItemsConfigSnapshot;
import com.gpmanager.grounditems.LootPresentationFilterService;
import com.gpmanager.model.ActivityAverageSnapshot;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TrackingDaySummary;
import com.gpmanager.model.TransactionType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ActivityAverageEngineTest
{
    private GpManagerEngine engine()
    {
        return engine(new AtomicBoolean(false));
    }

    private GpManagerEngine engine(AtomicBoolean filterActive)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public boolean autoStartSession() { return false; }
            @Override public LootPresentationFilter accountingItemFilter()
            {
                return filterActive.get() ? LootPresentationFilter.FOLLOW_GROUND_ITEMS
                    : LootPresentationFilter.ALL_ITEMS;
            }
        };
        return new GpManagerEngine(deltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : deltas.entrySet())
            {
                flows.add(new ItemFlow(entry.getKey(), "Item " + entry.getKey(),
                    entry.getValue(), 100, entry.getValue() * 100L));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private ProfitSession addSession(GpManagerEngine engine, String name, String activity,
        long start, long duration, long net, boolean excluded)
    {
        engine.startCustomSession(name, SessionMode.AUTO, start);
        ProfitSession session = engine.getActiveSession();
        session.setActivityHint(activity, start);
        if (net != 0L)
        {
            long itemValue = Math.abs(net);
            ItemFlow flow = new ItemFlow(2, "Activity item", net < 0L ? -1L : 1L,
                (int) itemValue, net);
            session.addTransaction(new ProfitTransaction(start + Math.max(1L, duration / 2L),
                Math.max(0L, duration / 2L), net < 0L ? TransactionType.CONSUMPTION
                    : TransactionType.LOOT, net < 0L ? TrackingContext.GENERIC
                    : TrackingContext.LOOT, "Activity receipt", activity, true,
                Collections.singletonList(flow), ClassificationConfidence.CONFIRMED,
                "Activity average fixture", null), 2_000);
        }
        session.setExcludedFromAverages(excluded);
        engine.finishCustomSession(start + duration);
        return session;
    }

    @Test
    public void aggregatesCaseInsensitivePerSessionNetAndTimeWeightedRate()
    {
        GpManagerEngine engine = engine();
        addSession(engine, "One", "Woodcutting", 1_000L, 3_600_000L, 100L, false);
        addSession(engine, "Two", "woodcutting", 5_000_000L, 7_200_000L, 600L, false);
        addSession(engine, "Excluded", "WOODCUTTING", 13_000_000L, 3_600_000L, 900L, true);
        ProfitSession explicitExcluded = addSession(engine, "Other", "Woodcutting",
            18_000_000L, 3_600_000L, 1_000L, false);

        ActivityAverageSnapshot average = engine.getActivityAverage("WOODCUTTING",
            explicitExcluded.getId());

        assertTrue(average.isNetAvailable());
        assertTrue(average.isRateAvailable());
        assertEquals(2, average.getSessionsCounted());
        assertEquals(Long.valueOf(700L), average.getTotalNetGp());
        assertEquals(Double.valueOf(350.0d), average.getMedianSessionNetGp());
        assertEquals(10_800_000L, average.getKnownActiveMillis());
        assertEquals(Long.valueOf(233L), average.getTimeWeightedGpPerHour());
        assertEquals("AVAILABLE", average.getStatus());
    }

    @Test
    public void durationOnlyActivityCountsAsAZeroNetSession()
    {
        GpManagerEngine engine = engine();
        addSession(engine, "Time only", "Fishing", 1_000L, 1_800_000L, 0L, false);

        ActivityAverageSnapshot average = engine.getActivityAverage("fishing", null);

        assertEquals(1, average.getSessionsCounted());
        assertEquals(Long.valueOf(0L), average.getTotalNetGp());
        assertEquals(Double.valueOf(0.0d), average.getMedianSessionNetGp());
        assertEquals(Long.valueOf(0L), average.getTimeWeightedGpPerHour());
    }

    @Test
    public void missingLegacyActivityDurationKeepsNetButHidesRate()
    {
        GpManagerEngine engine = engine();
        ProfitSession session = addSession(engine, "Legacy time", "Mining", 1_000L,
            1_800_000L, 80L, false);
        for (TrackingDaySummary day : session.getAnalyticsDays())
            day.markActivityActiveMillisUnavailable();

        ActivityAverageSnapshot average = engine.getActivityAverage("Mining", null);

        assertTrue(average.isNetAvailable());
        assertEquals(Long.valueOf(80L), average.getTotalNetGp());
        assertFalse(average.isRateAvailable());
        assertNull(average.getTimeWeightedGpPerHour());
        assertEquals("UNAVAILABLE_ACTIVITY_TIME", average.getStatus());
    }

    @Test
    public void netWithoutMatchingActivityDurationDoesNotBorrowTimeFromAnotherLabel()
    {
        GpManagerEngine engine = engine();
        engine.startCustomSession("Mismatched evidence", SessionMode.AUTO, 1_000L);
        ProfitSession session = engine.getActiveSession();
        session.setActivityHint("Fishing", 1_000L);
        session.addTransaction(new ProfitTransaction(901_000L, 900_000L,
            TransactionType.LOOT, TrackingContext.LOOT, "Loot", "Chopping", true,
            Collections.singletonList(new ItemFlow(2, "Logs", 1L, 80, 80L)),
            ClassificationConfidence.CONFIRMED, "Activity label fixture", null), 2_000);
        engine.finishCustomSession(1_801_000L);

        ActivityAverageSnapshot average = engine.getActivityAverage("Chopping", null);

        assertTrue(average.isNetAvailable());
        assertEquals(Long.valueOf(80L), average.getTotalNetGp());
        assertFalse(average.isRateAvailable());
        assertNull(average.getTimeWeightedGpPerHour());
    }

    @Test
    public void filteredDailyAggregatesFailClosed()
    {
        AtomicBoolean filterActive = new AtomicBoolean(false);
        GpManagerEngine engine = engine(filterActive);
        addSession(engine, "Mining", "Mining", 1_000L, 1_800_000L, 80L, false);
        engine.setContributionEligibility(new LootPresentationFilterService(
            GroundItemsConfigSnapshot.disabled()));
        filterActive.set(true);

        ActivityAverageSnapshot average = engine.getActivityAverage("Mining", null);

        assertFalse(average.isNetAvailable());
        assertFalse(average.isRateAvailable());
        assertEquals("UNAVAILABLE_ACTIVE_ACCOUNTING_FILTER", average.getStatus());
    }
}
