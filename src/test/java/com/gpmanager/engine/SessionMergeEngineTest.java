package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionComparison;
import com.gpmanager.model.SessionMode;
import java.time.LocalDate;
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

/** Pass 10 step 41: merging consecutive sessions with an exact undo, and the enriched comparison. */
public class SessionMergeEngineTest
{
    private static final int LOGS = 1519;
    private static final int SHARK = 385;
    private static final long DAY = 86_400_000L;
    private static final long NOW = System.currentTimeMillis() - 3_600_000L;

    @Test
    public void mergeSumsEverythingAndUndoRestoresTheOriginals()
    {
        GpManagerEngine engine = engine();
        String a = history(engine, "Vorkath", NOW - 5 * 3_600_000L, 30 * 60_000L, 40L, 0L);
        String b = history(engine, "Vorkath", NOW - 4 * 3_600_000L, 45 * 60_000L, 20L, 2L);
        String c = history(engine, "Zulrah", NOW - 2 * 3_600_000L, 10 * 60_000L, 5L, 0L);
        long netA = engine.getHistorySummary(a, NOW).getMetrics().getNet();
        long netB = engine.getHistorySummary(b, NOW).getMetrics().getNet();
        long overallBefore = engine.getOverallTotals(NOW).getNetGp();
        long activeBefore = engine.getOverallTotals(NOW).getActiveMillis();
        long dayBefore = dayNet(engine);
        List<String> idsBefore = ids(engine);

        GpManagerEngine.MergeOutcome outcome = engine.mergeHistorySessions(Arrays.asList(a, b), NOW);
        assertTrue(outcome.getReason(), outcome.isMerged());
        assertEquals(Arrays.asList(b, a), outcome.getOriginalIds()); // history order, newest first
        assertEquals(idsBefore.size() - 1, engine.getHistory().size());
        ProfitSession merged = engine.getHistorySession(outcome.getMergedSessionId());
        assertNotNull(merged);
        assertEquals("Vorkath", merged.getName());
        assertTrue(merged.isClosed());
        assertEquals(NOW - 5 * 3_600_000L, merged.getStartedAtEpochMillis());
        assertEquals(NOW - 4 * 3_600_000L + 45 * 60_000L, merged.getEndedAtEpochMillis());
        // Elapsed is the sum of the parts: the gap between them is paused time.
        assertEquals(75 * 60_000L, merged.getElapsedMillis(NOW));
        assertEquals(netA + netB, engine.getHistorySummary(merged.getId(), NOW).getMetrics().getNet());
        assertEquals(3, merged.getTransactions().size());
        Map<Integer, ProfitSession.ItemNet> items = merged.getItemNets();
        assertNotNull(items);
        assertEquals(60L * 48L, items.get(LOGS).getNet());
        assertEquals(-2L * 800L, items.get(SHARK).getNet());
        // Overall and the day rollups are unchanged by a merge.
        assertEquals(overallBefore, engine.getOverallTotals(NOW).getNetGp());
        assertEquals(activeBefore, engine.getOverallTotals(NOW).getActiveMillis());
        assertEquals(dayBefore, dayNet(engine));
        assertEquals(merged.getId(), engine.getUndoableMergeId());
        // Zulrah is untouched and still in front.
        assertEquals(c, engine.getHistory().get(0).getId());

        assertTrue(engine.undoLastMerge());
        assertEquals(idsBefore, ids(engine));
        assertEquals(netA, engine.getHistorySummary(a, NOW).getMetrics().getNet());
        assertEquals(netB, engine.getHistorySummary(b, NOW).getMetrics().getNet());
        assertEquals(overallBefore, engine.getOverallTotals(NOW).getNetGp());
        assertNull(engine.getUndoableMergeId());
        assertFalse(engine.undoLastMerge());
    }

    @Test
    public void mergeRefusesWhatItCannotStandFor()
    {
        GpManagerEngine engine = engine();
        String a = history(engine, "Vorkath", NOW - 5 * 3_600_000L, 30 * 60_000L, 40L, 0L);
        String c = history(engine, "Zulrah", NOW - 4 * 3_600_000L, 10 * 60_000L, 5L, 0L);
        String b = history(engine, "Vorkath", NOW - 3 * 3_600_000L, 45 * 60_000L, 20L, 0L);
        assertEquals("NOT_CONSECUTIVE", engine.mergeHistorySessions(Arrays.asList(a, b), NOW).getReason());
        assertEquals("TOO_FEW", engine.mergeHistorySessions(Collections.singletonList(a), NOW).getReason());
        assertEquals("NOT_FOUND", engine.mergeHistorySessions(Arrays.asList(a, "nope"), NOW).getReason());
        engine.startCustomSession("Live", SessionMode.AUTO, NOW - 60_000L);
        assertEquals("ACTIVE", engine.mergeHistorySessions(Arrays.asList(engine.getActiveSession().getId(), b), NOW).getReason());
        assertEquals(3, engine.getHistory().size() - (engine.getHistory().get(0).getId().equals(engine.getActiveSession().getId()) ? 1 : 0));
        assertNotNull(c);
    }

    @Test
    public void comparisonCarriesItemDeltasAndTheSameNameAverage()
    {
        GpManagerEngine engine = engine();
        String older = history(engine, "Vorkath", NOW - 3 * DAY, 60 * 60_000L, 100L, 0L);
        String a = history(engine, "Vorkath", NOW - 5 * 3_600_000L, 30 * 60_000L, 40L, 0L);
        String b = history(engine, "Vorkath", NOW - 3 * 3_600_000L, 45 * 60_000L, 20L, 2L);
        SessionComparison cmp = engine.compareSessions(a, b, NOW);
        assertTrue(cmp.isAvailable());
        assertTrue(cmp.isTopItemDeltasAvailable());
        assertEquals(2, cmp.getTopItemDeltas().size());
        SessionComparison.ItemDelta shark = null;
        SessionComparison.ItemDelta logs = null;
        for (SessionComparison.ItemDelta d : cmp.getTopItemDeltas())
        {
            if (d.getItemId() == SHARK) shark = d;
            if (d.getItemId() == LOGS) logs = d;
        }
        assertNotNull(shark);
        assertNotNull(logs);
        assertEquals(0L, shark.getLeftNet());
        assertEquals(-1_600L, shark.getRightNet());
        assertEquals(40L * 48L, logs.getLeftNet());
        assertEquals(20L * 48L, logs.getRightNet());
        // Sorted by the size of the difference: sharks moved 1,600, logs 960.
        assertEquals(SHARK, cmp.getTopItemDeltas().get(0).getItemId());
        assertNotNull(cmp.getSameNameAverage());
        assertEquals("Vorkath", cmp.getSameNameAverage().getActivityName());
        assertEquals(2, cmp.getSameNameAverage().getSessionsCounted()); // b and older; a itself excluded
        assertNotNull(older);
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private static long dayNet(GpManagerEngine engine)
    {
        long net = 0L;
        for (com.gpmanager.model.DailyRollup day : engine.getDailyRollups(LocalDate.now().minusDays(400), LocalDate.now()))
        {
            net += day.getNetGp();
        }
        return net;
    }

    private static List<String> ids(GpManagerEngine engine)
    {
        List<String> out = new ArrayList<>();
        for (ProfitSession s : engine.getHistory()) out.add(s.getId());
        return out;
    }

    private static String history(GpManagerEngine engine, String name, long startedAt, long length, long logs, long sharksEaten)
    {
        engine.startCustomSession(name, SessionMode.AUTO, startedAt);
        String id = engine.getActiveSession().getId();
        engine.getActiveSession().setActivityHint(name, startedAt);
        engine.setBaseline(new ContainerSnapshot(map(SHARK, sharksEaten)));
        if (sharksEaten > 0L)
        {
            // Eat the sharks first (a consumption), then gather the logs (a gain): two plain receipts.
            settle(engine, new ContainerSnapshot(map()), startedAt + 30_000L);
        }
        settle(engine, new ContainerSnapshot(map(LOGS, logs)), startedAt + 60_000L);
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

    private static Map<Integer, Long> map(Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            long value = (Long) pairs[i + 1];
            if (value != 0L) map.put((Integer) pairs[i], value);
        }
        return map;
    }
}
