package com.gpmanager.engine;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.HistoryDateRange;
import com.gpmanager.model.HistoryQuery;
import com.gpmanager.model.HistorySort;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionCategory;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.SessionSummary;
import com.gpmanager.model.WealthLocationSnapshot;
import com.gpmanager.model.WealthLocationsSnapshot;
import com.gpmanager.persistence.SavedState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Synthetic engine analytics and save workload. This measures the Java model and
 * engine only; it is not a live RuneLite client soak or a timing-based release gate.
 */
@Category(PerformanceBudget.class)
public class LongHistoryEnginePerfTest
{
    private static final int SESSION_COUNT = 2_000;
    private static final int DAY_COUNT = 400;
    private static final int SESSIONS_PER_DAY = SESSION_COUNT / DAY_COUNT;
    private static final long DAY_MILLIS = 86_400_000L;
    private static final long BASE = Instant.parse("2025-08-12T00:00:00Z").toEpochMilli();
    private static final long AS_OF = BASE + DAY_COUNT * DAY_MILLIS;
    private static final int ITEM_ID = 1511;
    private static final int UNIT_PRICE = 39;
    private static final int HISTORY_SUMMARIES = 50;
    private static final long SAVED_STATE_LIMIT_BYTES = 64L * 1024L * 1024L;
    private static final String PROFILE_ZONE = "UTC";

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int maxHistorySessions()
        {
            return SESSION_COUNT + 10;
        }

        @Override
        public int stabilizationTicks()
        {
            return 1;
        }

        @Override
        public boolean wealthHistoryEnabled()
        {
            return true;
        }
    };

    @Test
    public void measuresLongHistoryReadsAndSavedState() throws IOException
    {
        GpManagerEngine engine = newEngine();
        Set<LocalDate> fixtureDays = new HashSet<>();
        int settlements = 0;
        int pkEncounters = 0;
        int wealthSnapshots = 0;

        for (int index = 0; index < SESSION_COUNT; index++)
        {
            int dayIndex = index / SESSIONS_PER_DAY;
            int slot = index % SESSIONS_PER_DAY;
            long startedAt = BASE + dayIndex * DAY_MILLIS
                + slot * (DAY_MILLIS / SESSIONS_PER_DAY) + 600_000L;
            long settledAt = startedAt + 1_600L;
            long endedAt = startedAt + 60_000L;
            fixtureDays.add(Instant.ofEpochMilli(startedAt).atZone(ZoneOffset.UTC).toLocalDate());

            boolean pkSession = index % 100 == 0;
            engine.startCustomSession("Synthetic session " + index,
                pkSession ? SessionMode.PK : SessionMode.AUTO, startedAt);
            ProfitSession active = engine.getActiveSession();
            assertNotNull("session should be active at fixture index " + index, active);
            engine.setBaseline(ContainerSnapshot.empty());
            engine.markLootContext(Collections.singletonMap(ITEM_ID, 1L), 5,
                "Synthetic settled loot", "Long history fixture");

            ContainerSnapshot withLoot = inventory(ITEM_ID, 1L);
            // One dirty sample plus one stable sample satisfies the configured
            // one-tick stabilization window (two-tick windows need three samples).
            assertNull(engine.processIfDirty(withLoot, startedAt + 1_000L));
            ProfitTransaction settled = engine.processIfDirty(withLoot, settledAt);
            assertNotNull("inventory delta should settle at fixture index " + index, settled);
            assertTrue("fixture receipt should count at index " + index, settled.isCounted());
            settlements++;

            if (pkSession)
            {
                engine.markPkLootContext(Collections.emptyMap(), 5,
                    "Synthetic player kill", startedAt + 3_000L);
                pkEncounters++;
            }

            assertTrue("custom session should close at fixture index " + index,
                engine.finishCustomSession(endedAt));

            if (index % 50 == 0)
            {
                recordWealthObservation(engine, endedAt + 1_000L, wealthSnapshots);
                wealthSnapshots++;
            }
        }

        assertEquals("fixture spans 400 distinct UTC days", DAY_COUNT, fixtureDays.size());
        assertEquals(SESSION_COUNT, engine.getHistory().size());
        assertEquals(SESSION_COUNT, settlements);
        assertEquals(20, pkEncounters);
        assertEquals(40, wealthSnapshots);

        int retainedPkEncounters = 0;
        for (ProfitSession session : engine.getHistory())
        {
            retainedPkEncounters += session.getPkEncounters().size();
        }
        assertEquals("all public PK producer calls should be retained", 20, retainedPkEncounters);

        int compactedReceipts = engine.compactOlderThan(90, AS_OF);
        assertTrue("public receipt-retention API should compact older receipts", compactedReceipts > 0);

        HistoryQuery query = new HistoryQuery(SessionCategory.ALL, "",
            HistoryDateRange.ALL_TIME, HistorySort.NEWEST, false);
        List<ProfitSession> queriedHistory = engine.getHistory(query, AS_OF);
        assertEquals(SESSION_COUNT, queriedHistory.size());
        int summaryCount = Math.min(HISTORY_SUMMARIES, queriedHistory.size());
        assertEquals(HISTORY_SUMMARIES, summaryCount);

        List<Measurement> measurements = new ArrayList<>();
        measurements.add(measure("overall_totals", () -> engine.getOverallTotals(AS_OF)));
        measurements.add(measure("overall_today", () -> engine.getOverallToday(AS_OF)));
        measurements.add(measure("insights_30d", () -> engine.getInsightsWindow(30, AS_OF)));
        measurements.add(measure("pk_30d", () -> engine.getPkWindow(30, AS_OF)));
        measurements.add(measure("history_query_plus_50_summaries", () ->
            historyQueryAndSummaries(engine, query, AS_OF, summaryCount)));
        measurements.add(measure("wealth_trend_30d", () -> engine.getWealthTrend(30, AS_OF)));
        measurements.add(measure("records", () -> engine.getRecords(AS_OF)));
        if (queriedHistory.size() >= 2)
        {
            String left = queriedHistory.get(0).getId(), right = queriedHistory.get(1).getId();
            measurements.add(measure("compare_sessions", () -> engine.compareSessions(left, right, AS_OF)));
        }
        Measurement savedState = measure("create_saved_state",
            engine::createSavedState);
        measurements.add(savedState);

        assertNotNull(measurements.get(0).result);
        assertNotNull(measurements.get(1).result);
        assertNotNull(measurements.get(2).result);
        assertNotNull(measurements.get(3).result);
        assertNotNull(measurements.get(4).result);
        assertNotNull(measurements.get(5).result);
        assertTrue("wealth trend includes the 30-day calendar window",
            ((List<?>) measurements.get(5).result).size() == 30);

        SavedState state = (SavedState) savedState.result;
        long savedStateBytes = new Gson().toJson(state).getBytes(StandardCharsets.UTF_8).length;
        assertTrue("serialized state exceeds the 64 MiB synthetic fixture cap: " + savedStateBytes,
            savedStateBytes <= SAVED_STATE_LIMIT_BYTES);

        double multiplier = Double.parseDouble(System.getProperty("gp.perf.multiplier", "1"));
        for (Measurement measurement : measurements)
        {
            boolean broadAnalytics = measurement.label.equals("insights_30d")
                || measurement.label.equals("pk_30d");
            long firstLimit = measurement.label.equals("create_saved_state")
                ? 1_000L : 400L;
            long repeatLimit = measurement.label.equals("create_saved_state")
                ? 1_000L : broadAnalytics ? 200L : 100L;
            assertTrue(measurement.label + " first budget exceeded: " + measurement.firstNanos,
                measurement.firstNanos <= firstLimit * multiplier * 1_000_000L);
            assertTrue(measurement.label + " repeat budget exceeded: " + measurement.repeatNanos,
                measurement.repeatNanos <= repeatLimit * multiplier * 1_000_000L);
        }

        String report = buildReport(measurements, settlements, compactedReceipts,
            retainedPkEncounters, wealthSnapshots, fixtureDays.size(), savedStateBytes);
        Path output = Path.of("build", "reports", "perf");
        Files.createDirectories(output);
        Path reportPath = output.resolve("long-history-engine.txt");
        Files.write(reportPath, report.getBytes(StandardCharsets.UTF_8));
        assertTrue("performance report should be written", Files.exists(reportPath));

        // Tick storm (pass 10 step 44): an hour of idle ticks on a live session must not rebuild
        // the profile rollups per tick, and a repeat read afterwards comes from the cache.
        engine.startCustomSession("Storm", SessionMode.AUTO, AS_OF);
        ContainerSnapshot idle = inventory(ITEM_ID, 3L);
        engine.setBaseline(idle);
        engine.getInsightsWindow(30, AS_OF);
        int rebuildsBefore = engine.rollupRebuildsForTest();
        long stormStarted = System.nanoTime();
        long tick = AS_OF;
        for (int i = 0; i < 6_000; i++)
        {
            tick += 600L;
            engine.processIfDirty(idle, tick);
        }
        long stormMillis = (System.nanoTime() - stormStarted) / 1_000_000L;
        int rebuildsDuringStorm = engine.rollupRebuildsForTest() - rebuildsBefore;
        assertEquals("ticks must not rebuild the rollups (" + rebuildsDuringStorm + " rebuilds)", 0, rebuildsDuringStorm);
        assertTrue("6,000 idle ticks took " + stormMillis + " ms", stormMillis <= 3_000L * multiplier);
        engine.getInsightsWindow(30, tick);
        int afterFirstRead = engine.rollupRebuildsForTest();
        engine.getInsightsWindow(30, tick);
        engine.getRecords(tick);
        engine.getOverallTotals(tick);
        assertEquals("repeat reads must come from the rollup cache", afterFirstRead, engine.rollupRebuildsForTest());
    }

    private static GpManagerEngine newEngine()
    {
        FlowValuator valuator = deltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                long quantity = delta.getValue();
                long value = quantity * UNIT_PRICE;
                flows.add(new ItemFlow(delta.getKey(), "Logs", quantity, UNIT_PRICE, value,
                    ItemPriceSource.GRAND_EXCHANGE, AS_OF));
            }
            return flows;
        };
        GpManagerEngine engine = new GpManagerEngine(valuator,
            new TransactionClassifier(), CONFIG);
        SavedState profile = new SavedState();
        profile.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        profile.setProfileTimeZoneId(PROFILE_ZONE);
        profile.setSavedAtEpochMillis(BASE);
        engine.restore(profile, BASE);
        return engine;
    }

    private static ContainerSnapshot inventory(int itemId, long quantity)
    {
        Map<Integer, Long> contents = new HashMap<>();
        if (quantity > 0L) contents.put(itemId, quantity);
        return new ContainerSnapshot(contents);
    }

    private static void recordWealthObservation(GpManagerEngine engine, long at, int observation)
    {
        long value = 100_000L + observation * 1_000L;
        long bank = value / 4L;
        long worn = value / 8L;
        long offers = value / 8L;
        long collection = value / 8L;
        long inventory = value / 8L;
        long pouch = value / 8L;
        long coffers = value - bank - worn - offers - collection - inventory - pouch;
        engine.recordWealthSnapshot(new WealthLocationsSnapshot(at,
            java.util.Arrays.asList(
                wealthLocation("bank", "Bank", bank, at),
                wealthLocation("worn", "Equipment", worn, at),
                wealthLocation("ge_offers", "GE offers", offers, at),
                wealthLocation("ge_collection", "GE collection", collection, at),
                wealthLocation("inventory", "Inventory", inventory, at),
                wealthLocation("rune_pouch", "Rune pouch", pouch, at),
                wealthLocation("coffers", "Coffers", coffers, at))), at);
    }

    private static WealthLocationSnapshot wealthLocation(String id, String name,
        long value, long at)
    {
        return new WealthLocationSnapshot(id, name,
            WealthLocationSnapshot.Status.AVAILABLE, value, at,
            Collections.emptyList(), "Synthetic bank visit");
    }

    private static Object historyQueryAndSummaries(GpManagerEngine engine,
        HistoryQuery query, long now, int summaries)
    {
        List<ProfitSession> result = engine.getHistory(query, now);
        int count = Math.min(summaries, result.size());
        long summaryNet = 0L;
        for (int index = 0; index < count; index++)
        {
            SessionSummary summary = engine.getHistorySummary(result.get(index).getId(), now);
            if (summary != null) summaryNet += summary.getMetrics().getNet();
        }
        return Long.valueOf(summaryNet + result.size());
    }

    private static Measurement measure(String label, Supplier<?> action)
    {
        long firstStart = System.nanoTime();
        Object firstResult = action.get();
        long firstNanos = System.nanoTime() - firstStart;
        long repeatStart = System.nanoTime();
        Object repeatResult = action.get();
        long repeatNanos = System.nanoTime() - repeatStart;
        return new Measurement(label, firstNanos, repeatNanos,
            repeatResult == null ? firstResult : repeatResult);
    }

    private static String buildReport(List<Measurement> measurements,
        int settlements, int compactedReceipts, int pkEncounters, int wealthSnapshots,
        int distinctDays, long savedStateBytes)
    {
        StringBuilder report = new StringBuilder();
        report.append("long_history_engine_perf\n")
            .append("label=SYNTHETIC_NOT_LIVE_CLIENT\n")
            .append("sessions=").append(SESSION_COUNT).append('\n')
            .append("distinct_utc_days=").append(distinctDays).append('\n')
            .append("settlements=").append(settlements).append('\n')
            .append("retention_days=90\n")
            .append("compacted_receipts=").append(compactedReceipts).append('\n')
            .append("pk_encounters=").append(pkEncounters).append('\n')
            .append("wealth_snapshots=").append(wealthSnapshots).append('\n')
            .append("saved_state_bytes=").append(savedStateBytes).append('\n')
            .append("saved_state_limit_bytes=").append(SAVED_STATE_LIMIT_BYTES).append('\n')
            .append("timings_unit=nanoseconds; first=first call, repeat=immediate repeated call; no JVM/cache reset\n")
            .append("create_saved_state_times=in_memory_model_snapshot_only; JSON serialization and disk I/O are not timed\n");
        for (Measurement measurement : measurements)
        {
            report.append(String.format(Locale.ROOT, "%s_first_ns=%d%n%s_repeat_ns=%d%n",
                measurement.label, measurement.firstNanos,
                measurement.label, measurement.repeatNanos));
        }
        report.append("note=Timings are environment-dependent; the CI-multiplied budgets are asserted above.\n");
        return report.toString();
    }

    private static final class Measurement
    {
        private final String label;
        private final long firstNanos;
        private final long repeatNanos;
        private final Object result;

        private Measurement(String label, long firstNanos, long repeatNanos, Object result)
        {
            this.label = label;
            this.firstNanos = firstNanos;
            this.repeatNanos = repeatNanos;
            this.result = result;
        }
    }
}
