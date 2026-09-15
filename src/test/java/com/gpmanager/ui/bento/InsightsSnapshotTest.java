package com.gpmanager.ui.bento;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.ContainerSnapshot;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.PkEncounter;
import com.gpmanager.model.PkEncounterType;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.SessionSummary;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.model.WealthLocationSnapshot;
import com.gpmanager.model.WealthLocationsSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Insights read model: window membership, previous-window comparison, averages that honour
 * exclusions, activities and items, the heatmap, PvP figures with medians, and wealth totals.
 */
public class InsightsSnapshotTest
{
    private static final int LOGS = 1519;
    private static final int SHARK = 385;
    private static final long DAY = 24L * 3_600_000L;

    @Test
    public void emptyEngineIsEmptyEverywhere()
    {
        InsightsSnapshot s = InsightsSnapshot.capture(engine(), InsightsSnapshot.Range.D30, 1_000L, false, null);
        assertEquals(0, s.sessionCount);
        assertTrue(s.trend.isEmpty());
        assertNull(s.previousNet);
        assertNull(s.vsPrevious());
        assertEquals(0, s.pvp.sessions);
        assertFalse(s.wealth.available);
        assertFalse(s.heatAvailable);
    }

    @Test
    public void windowPreviousWindowAndExclusions()
    {
        GpManagerEngine engine = engine();
        long now = System.currentTimeMillis();
        String yesterday = history(engine, "Vorkath", now - DAY, 30 * 60_000L, 40L);
        history(engine, "Zulrah", now - 3 * DAY, 60 * 60_000L, 20L);
        history(engine, "Vorkath", now - 10 * DAY, 30 * 60_000L, 10L);   // previous 7d window (days 7-14)
        history(engine, "Old", now - 100 * DAY, 30 * 60_000L, 200L);     // outside 30d

        // Free play is always live: it feeds the total but is never a session point.
        InsightsSnapshot week = InsightsSnapshot.capture(engine, InsightsSnapshot.Range.D7, now, false, null);
        assertEquals(2, week.sessionCount);
        assertEquals(2, week.trend.size());
        long expectedNet = 0L;
        long expectedMillis = 0L;
        for (InsightsSnapshot.SessionPoint p : week.trend)
        {
            assertFalse(p.active);
            SessionSummary summary = engine.getHistorySummary(p.id, now);
            assertEquals(summary.getMetrics().getNet(), p.net);
            expectedNet += p.net;
            expectedMillis += p.activeMillis;
        }
        if (week.rollupBacked)
        {
            // Profile rollups vouched for the window: the figure is the engine's, not the session sum.
            assertEquals(engine.getInsightsWindow(7, now).getCurrent().getNetGp(), week.net);
        }
        else
        {
            assertEquals(expectedNet + week.freePlayNet, week.net);
        }
        assertEquals(engine.getMetrics(now).getNet(), week.freePlayNet);
        assertEquals(Math.round(expectedNet * 3_600_000d / expectedMillis), week.averageRate);
        // Oldest first for the trend line.
        assertTrue(week.trend.get(0).startedAt <= week.trend.get(1).startedAt);
        assertNotNull(week.previousNet);
        assertTrue(week.previousNet > 0L);
        assertNotNull(week.vsPrevious());

        InsightsSnapshot month = InsightsSnapshot.capture(engine, InsightsSnapshot.Range.D30, now, false, null);
        assertEquals(3, month.sessionCount);
        InsightsSnapshot all = InsightsSnapshot.capture(engine, InsightsSnapshot.Range.ALL, now, false, null);
        assertEquals(4, all.sessionCount);
        assertNull(all.previousNet);

        // Records look over everything retained, never Free play, and name the session to open.
        InsightsSnapshot.Records records = all.records;
        assertNotNull(records.bestSession);
        assertEquals("Old", records.bestSession.detail.substring(0, 3));
        assertEquals(engine.getHistorySummary(records.bestSession.sessionId, now).getMetrics().getNet(), records.bestSession.value);
        assertNotNull(records.longestSession);
        assertEquals(60 * 60_000L, records.longestSession.value);
        assertNotNull(records.bestRate);
        assertTrue(records.bestRate.value > 0L);
        assertFalse(records.isEmpty());

        // Activities are grouped by session name for custom sessions, with counts.
        assertFalse(month.activities.isEmpty());
        assertEquals("Vorkath", month.activities.get(0).name);
        assertEquals(2, month.activities.get(0).sessions);
        assertEquals(1, month.topItems.size());
        assertEquals("Willow logs", month.topItems.get(0).name);
        assertEquals(70L, month.topItems.get(0).quantity);
        assertTrue(month.heatAvailable);

        // Excluding yesterday keeps it in the trend, flagged, but out of the average and best.
        engine.setHistorySessionExcluded(yesterday, true);
        InsightsSnapshot excluded = InsightsSnapshot.capture(engine, InsightsSnapshot.Range.D7, now, false, null);
        assertEquals(2, excluded.sessionCount);
        assertEquals(1, excluded.excludedCount);
        boolean flagged = false;
        for (InsightsSnapshot.SessionPoint p : excluded.trend)
        {
            flagged |= p.excluded && p.id.equals(yesterday);
        }
        assertTrue(flagged);
        assertNotNull(excluded.bestSession);
        assertFalse(excluded.bestSession.id.equals(yesterday));
        assertTrue(excluded.averageRate != week.averageRate);
        // An excluded session never holds a record either.
        InsightsSnapshot.Records after = InsightsSnapshot.capture(engine, InsightsSnapshot.Range.ALL, now, false, null).records;
        for (InsightsSnapshot.Record r : java.util.Arrays.asList(after.bestSession, after.bestRate, after.longestSession))
        {
            assertTrue(r == null || !yesterday.equals(r.sessionId));
        }
    }

    @Test
    public void suppliesAndDeathsLandInCosts()
    {
        GpManagerEngine engine = engine();
        long now = System.currentTimeMillis();
        long start = now - 20 * 60_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(map(SHARK, 2L)));
        ProfitTransaction ate = settle(engine, snapshot(map(SHARK, 0L)), start + 60_000L);
        assertNotNull(ate);
        ate.setActionKind(ActionKind.EAT);
        ProfitSession session = engine.getActiveSession();
        ProfitTransaction death = new ProfitTransaction(start + 120_000L, TransactionType.PK_DEATH_LOSS,
            TrackingContext.GENERIC, "Death: items lost", true,
            Collections.singletonList(new ItemFlow(LOGS, "Willow logs", -10, 48, -480, ItemPriceSource.GRAND_EXCHANGE)));
        session.addTransaction(death, 500);

        InsightsSnapshot s = InsightsSnapshot.capture(engine, InsightsSnapshot.Range.D7, now, false, null);
        assertEquals(1, s.deaths);
        assertEquals(480L, s.deathLoss);
        assertEquals(2, s.topCosts.size());
        boolean shark = false;
        boolean deaths = false;
        for (InsightsSnapshot.Item c : s.topCosts)
        {
            shark |= "Shark".equals(c.name) && c.value == 1_600L;
            deaths |= c.itemId < 0 && "Deaths".equals(c.name) && c.value == 480L;
        }
        assertTrue(shark);
        assertTrue(deaths);
    }

    @Test
    public void pvpFiguresUseEncountersAndMedians()
    {
        GpManagerEngine engine = engine();
        long now = System.currentTimeMillis();
        long start = now - 2 * DAY;
        engine.startCustomSession("Edge", SessionMode.PK, start);
        ProfitSession session = engine.getActiveSession();
        long[] kills = {1_000_000L, 300_000L, 80_000L};
        long t = start;
        for (long value : kills)
        {
            t += 60_000L;
            PkEncounter kill = session.addPkEncounter(PkEncounterType.KILL, t, "Kill: Rival",
                ClassificationConfidence.CONFIRMED, "loot");
            ProfitTransaction loot = new ProfitTransaction(t + 1_000L, t + 1_000L - start, TransactionType.PK_LOOT,
                TrackingContext.PK_LOOT, "PK loot", "PKing", true,
                Collections.singletonList(new ItemFlow(LOGS, "Loot", 1, (int) value, value, ItemPriceSource.GRAND_EXCHANGE)));
            session.addTransaction(loot, 500);
            session.attachTransactionToEncounter(loot.getId(), kill.getId(), false);
        }
        t += 60_000L;
        PkEncounter death = session.addPkEncounter(PkEncounterType.DEATH, t, "Death", ClassificationConfidence.CONFIRMED, "died");
        ProfitTransaction loss = new ProfitTransaction(t + 500L, t + 500L - start, TransactionType.PK_DEATH_LOSS,
            TrackingContext.GENERIC, "Death: items lost", "PKing", true,
            Collections.singletonList(new ItemFlow(SHARK, "Gear", -1, 400_000, -400_000L, ItemPriceSource.GRAND_EXCHANGE)));
        session.addTransaction(loss, 500);
        session.attachTransactionToEncounter(loss.getId(), death.getId(), false);
        engine.finishCustomSession(t + 60_000L);

        InsightsSnapshot s = InsightsSnapshot.capture(engine, InsightsSnapshot.Range.D7, now, false, null);
        InsightsSnapshot.Pvp p = s.pvp;
        assertEquals(1, p.sessions);
        assertEquals(3, p.kills);
        assertEquals(1, p.deaths);
        assertEquals(3.0d, p.kd(), 0.0001d);
        assertEquals(300_000L, p.medianKill);
        assertEquals(400_000L, p.medianDeath);
        assertEquals(3, p.bestKills.size());
        assertEquals(1_000_000L, p.bestKills.get(0).value);
        assertEquals(1, p.worstDeaths.size());
        assertEquals(-400_000L, p.worstDeaths.get(0).value);
        assertEquals(1, s.deaths);
        assertEquals(400_000L, s.deathLoss);
    }

    @Test
    public void wealthChangesAndMoversAreUnavailableWithoutBankVisits()
    {
        InsightsSnapshot s = InsightsSnapshot.capture(engine(), InsightsSnapshot.Range.D30, System.currentTimeMillis(), false, null);
        assertFalse(s.wealth.fromHistory);
        assertTrue(s.wealth.timeline.isEmpty());
        assertEquals(4, s.wealth.changes.size()); // last bank visit · today · 7 days · 30 days
        for (InsightsSnapshot.WealthChange c : s.wealth.changes)
        {
            assertFalse(c.available);
        }
        assertTrue(s.wealth.movers.isEmpty());
    }

    @Test
    public void wealthTotalsOnlyAvailableLocations()
    {
        WealthLocationsSnapshot snapshot = new WealthLocationsSnapshot(5_000L, Arrays.asList(
            new WealthLocationSnapshot("ge", "GE offers", WealthLocationSnapshot.Status.AVAILABLE, 1_000L, 5_000L, Collections.emptyList(), ""),
            new WealthLocationSnapshot("box", "Collection box", WealthLocationSnapshot.Status.CLOSED, 999L, 5_000L, Collections.emptyList(), "")));
        InsightsSnapshot s = InsightsSnapshot.capture(engine(), InsightsSnapshot.Range.ALL, 10_000L, false, snapshot);
        assertTrue(s.wealth.available);
        assertEquals(1_000L, s.wealth.total);
        assertEquals(5_000L, s.wealth.capturedAt);
        assertEquals(2, s.wealth.locations.size());
        assertFalse(s.wealth.locations.get(1).valueAvailable);
        assertEquals("closed", s.wealth.locations.get(1).status);
    }

    @Test
    public void medianOfEvenAndOddLists()
    {
        assertEquals(0L, InsightsSnapshot.median(Collections.emptyList()));
        assertEquals(5L, InsightsSnapshot.median(Arrays.asList(9L, 1L, 5L)));
        assertEquals(3L, InsightsSnapshot.median(Arrays.asList(4L, 1L, 2L, 9L)));
    }

    private static String history(GpManagerEngine engine, String name, long startedAt, long length, long logs)
    {
        engine.startCustomSession(name, SessionMode.AUTO, startedAt);
        String id = engine.getActiveSession().getId();
        engine.setBaseline(snapshot(Collections.emptyMap()));
        settle(engine, snapshot(map(LOGS, logs)), startedAt + 60_000L);
        assertTrue(engine.finishCustomSession(startedAt + length));
        return id;
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
                int id = delta.getKey();
                int price = id == LOGS ? 48 : 800;
                flows.add(new ItemFlow(id, id == LOGS ? "Willow logs" : "Shark", delta.getValue(), price,
                    delta.getValue() * price, ItemPriceSource.GRAND_EXCHANGE));
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

    private static ContainerSnapshot snapshot(Map<Integer, Long> items)
    {
        return new ContainerSnapshot(items);
    }

    private static Map<Integer, Long> map(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            map.put((Integer) pairs[i], (Long) pairs[i + 1]);
        }
        return map;
    }
}
