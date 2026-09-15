package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.DailyRollup;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.TrackingDaySummary;
import com.gpmanager.persistence.SavedState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pass 10 step 44: day summaries inflated by the old read-accrual defect are clamped on restore and marked. */
public class ActiveTimeRepairEngineTest
{
    static
    {
        com.gpmanager.persistence.JsonCodec.bind(new com.google.gson.Gson());
    }

    private static final int LOGS = 1519;
    private static final long NOW = System.currentTimeMillis() - 3_600_000L;

    @Test
    public void inflatedDaysAreClampedToWhatTheSessionCouldHavePlayed()
    {
        GpManagerEngine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.AUTO, NOW - 2 * 3_600_000L);
        String id = engine.getActiveSession().getId();
        engine.setBaseline(new ContainerSnapshot(Collections.emptyMap()));
        settle(engine, new ContainerSnapshot(map(LOGS, 40L)), NOW - 2 * 3_600_000L + 60_000L);
        assertTrue(engine.finishCustomSession(NOW - 2 * 3_600_000L + 30 * 60_000L));
        ProfitSession closed = engine.getHistorySession(id);
        long elapsed = closed.getElapsedMillis(NOW);
        assertEquals(30 * 60_000L, elapsed);

        // The defect: a day summary claiming a full day of play inside a 30-minute session.
        TrackingDaySummary day = closed.getAnalyticsDays().get(0);
        day.addActiveMillis(23L * 3_600_000L, closed.getStartedAtEpochMillis(), day.getZone(), "Vorkath");
        assertTrue(day.getActiveMillis() > elapsed);
        SavedState state = engine.createSavedState();

        GpManagerEngine restored = engine();
        restored.restore(state, NOW);
        ProfitSession fixed = restored.getHistorySession(id);
        long claimed = 0L;
        for (TrackingDaySummary d : fixed.getAnalyticsDays()) claimed += d.getActiveMillis();
        assertTrue("clamped to the session bound: " + claimed, claimed <= elapsed);
        assertTrue(fixed.getAnalyticsDays().get(0).isActiveTimeRebuilt());
        assertEquals(1, restored.getDataHealth(NOW).getRebuiltDays());
        long overallActive = restored.getOverallTotals(NOW).getActiveMillis();
        long freePlay = restored.getGeneralSession() == null ? 0L : restored.getGeneralSession().getElapsedMillis(NOW);
        assertTrue("overall active " + overallActive + " vs session " + elapsed + " + free play " + freePlay,
            overallActive <= elapsed + freePlay + 60_000L);
        boolean partial = false;
        for (DailyRollup rollup : restored.getDailyRollups(LocalDate.now().minusDays(3), LocalDate.now()))
        {
            if (rollup.getSourceSessionIds().contains(id))
            {
                partial |= rollup.getCoverage(DailyRollup.Dimension.ACTIVE_TIME) == DailyRollup.Coverage.PARTIAL;
            }
        }
        assertTrue("a rebuilt day reports partial active-time coverage", partial);

        // Idempotent: a second restore clamps nothing more.
        GpManagerEngine again = engine();
        again.restore(restored.createSavedState(), NOW);
        assertEquals(0, again.getDataHealth(NOW).getRebuiltDays());
        assertTrue(again.getHistorySession(id).getAnalyticsDays().get(0).isActiveTimeRebuilt());
    }

    @Test
    public void honestDaysAreLeftAlone()
    {
        GpManagerEngine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.AUTO, NOW - 3_600_000L);
        String id = engine.getActiveSession().getId();
        engine.setBaseline(new ContainerSnapshot(Collections.emptyMap()));
        settle(engine, new ContainerSnapshot(map(LOGS, 40L)), NOW - 3_600_000L + 60_000L);
        assertTrue(engine.finishCustomSession(NOW - 3_600_000L + 30 * 60_000L));
        long before = 0L;
        for (TrackingDaySummary d : engine.getHistorySession(id).getAnalyticsDays()) before += d.getActiveMillis();
        GpManagerEngine restored = engine();
        restored.restore(engine.createSavedState(), NOW);
        long after = 0L;
        for (TrackingDaySummary d : restored.getHistorySession(id).getAnalyticsDays())
        {
            after += d.getActiveMillis();
            assertTrue(!d.isActiveTimeRebuilt());
        }
        assertEquals(before, after);
        assertEquals(0, restored.getDataHealth(NOW).getRebuiltDays());
    }

    private static GpManagerEngine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public int minimumTransactionValue() { return 1; }
        };
        return new GpManagerEngine(deltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                flows.add(new ItemFlow(delta.getKey(), "Willow logs", delta.getValue(), 48, delta.getValue() * 48, ItemPriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }

    private static ProfitTransaction settle(GpManagerEngine engine, ContainerSnapshot snapshot, long now)
    {
        engine.markInventoryDirty();
        ProfitTransaction first = engine.processIfDirty(snapshot, now);
        ProfitTransaction settled = engine.processIfDirty(snapshot, now + 600L);
        return settled == null ? first : settled;
    }

    private static Map<Integer, Long> map(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        return map;
    }
}
