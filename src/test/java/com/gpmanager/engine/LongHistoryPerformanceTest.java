package com.gpmanager;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Opt-in synthetic long histories, including growth beyond the retired session cap. */
@Category(PerformanceBudget.class)
public class LongHistoryPerformanceTest {
    private static final long DAY = 86_400_000L;
    private static final long NOW = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli();
    private static final int SESSIONS = 2_000, ROWS = 100, ACTIVE_ROWS = 2_000;
    private static final Gson GSON = new Gson();

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void compactedHistoriesPreserveMoneyAcrossYearsAndBeyondTheFormerCap() throws Exception {
        Assume.assumeTrue("set GP_PERF to run", System.getenv("GP_PERF") != null);
        JsonCodec.bind(GSON);
        var report = new StringBuilder("long_history_performance\nlabel=SYNTHETIC_NOT_LIVE_CLIENT\n");
        int growth = Integer.getInteger("gp.historyGrowth", 0);
        if (growth > 0) {
            verifyGrowth(growth, report, folder.newFolder("growth-" + growth).toPath());
            return;
        }
        for (var period : new GpManagerConfig.Retention[] {
                GpManagerConfig.Retention.DAYS_90, GpManagerConfig.Retention.DAYS_365}) {
            verify(period, report, folder.newFolder("history-" + period.days).toPath());
        }
        Path output = Path.of("build", "reports", "perf", "long-history.txt");
        Files.createDirectories(output.getParent());
        Files.write(output, report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.print(report);
    }

    /** Same 100-receipt sessions, compacted as they age instead of allocating years of raw rows at once. */
    private static void verifyGrowth(int count, StringBuilder report, Path directory) throws Exception {
        Path output = Path.of("build", "reports", "perf", "history-growth-" + count + ".txt");
        Files.createDirectories(output.getParent());
        GpManagerConfig config = new GpManagerConfig() {};
        Engine engine = new Engine(delta -> Collections.emptyList(), new TransactionClassifier(), config);
        long expectedNet = 0;
        for (int index = 0; index < count; index++) {
            long at = NOW - (366L + index * 1095L / Math.max(1, count - 1)) * DAY;
            var session = new Session("History " + index, at, SessionMode.GENERAL);
            var run = engine.raids.start(index % 4, "Observed", at, false);
            run.ended = at + 300_000;
            run.complete = run.claimed = true;
            run.owners.add(session.id());
            for (int item = 0; item < ROWS; item++) {
                var row = receipt(at + item * 1000L, item);
                if (item == 1) row.flows.get(0).raid = run.id;
                expectedNet += row.net();
                session.addTransaction(row, config.maxTransactionsPerSession(), false);
            }
            session.close(at + 300_000);
            assertEquals(ROWS, session.compactBefore(NOW - 90 * DAY, ReviewRules::needsReview));
            engine.history.add(0, session);
        }
        engine.startCustom("Current", NOW);
        for (int item = 0; item < ACTIVE_ROWS; item++) {
            var row = receipt(NOW + item, item);
            expectedNet += row.net();
            engine.session().addTransaction(row, config.maxTransactionsPerSession(), false);
        }
        engine.raids.prune();
        assertEquals(count, engine.history.size());
        assertEquals(0, engine.history.stream().mapToLong(session -> session.transactions().size()).sum());
        assertEquals(expectedNet, GrindsData.allTime(engine, NOW).net);
        assertEquals(count * 2000L, engine.savedSession("raid:all").session.metrics(NOW).net);
        report.append("closed_compacted_sessions=").append(count).append(" active_rows=").append(ACTIVE_ROWS)
                .append(" raid_attempt_metadata=").append(engine.raids.state.runs.size()).append('\n');
        report.append("heap_max_bytes=").append(Runtime.getRuntime().maxMemory()).append('\n');
        Files.writeString(output, report);
        System.gc();
        var memory = ManagementFactory.getMemoryMXBean();
        var peak = new AtomicLong(memory.getHeapMemoryUsage().getUsed());
        var sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max),
                0, 5, TimeUnit.MILLISECONDS);
        var repository = new SessionRepository(GSON, FilepathTestSupport.root(directory), false);
        var writer = new OrderedPersistenceWriter(repository);
        var persistence = new Persistence(null, null, repository, writer, engine);
        var previousClock = SafeMath.clock;
        SafeMath.clock = () -> NOW;
        try {
            long start = System.nanoTime();
            boolean saved = persistence.saveNow();
            report.append("first_save_ms=").append((System.nanoTime() - start) / 1e6)
                    .append(" successful=").append(saved).append('\n');
            Files.writeString(output, report);
            assertTrue("save exceeded/refused the existing five-second persistence boundary", saved);
            measure(report, "warm_save", 3, () -> {
                if (!persistence.saveNow()) throw new AssertionError("warm save exceeded/refused the persistence boundary");
                return repository.revision;
            });
            report.append("save_bytes=").append(Files.size(FilepathTestSupport.path(repository.bound.state))).append('\n');
            Files.writeString(output, report);
            start = System.nanoTime();
            engine.restoreProfile(null, repository.load(), NOW);
            report.append("load_and_restore_ms=").append((System.nanoTime() - start) / 1e6).append('\n');
            assertEquals(count, engine.history.size());
            assertEquals(expectedNet, GrindsData.allTime(engine, NOW).net);
            assertEquals(count * 2000L, engine.savedSession("raid:all").session.metrics(NOW).net);
            engine.snapshot = null;
            measure(report, "Live_cold", 1, () -> LiveSnapshot.capture(engine, NOW, LiveContext.NONE).net);
            measure(report, "Live_warm", 100, () -> LiveSnapshot.capture(engine, NOW, LiveContext.NONE).net);
            measure(report, "Grinds_cold", 1, () -> GrindsData.capture(engine, NOW, false, null).plans.size());
            measure(report, "Grinds_warm", 100, () -> GrindsData.capture(engine, NOW, false, null).plans.size());
            System.gc();
            report.append("heap_after_gc_bytes=").append(memory.getHeapMemoryUsage().getUsed()).append('\n');
        } finally {
            SafeMath.clock = previousClock;
            writer.shutdown(Duration.ofSeconds(10));
            sampler.shutdown();
            sampler.awaitTermination(5, TimeUnit.SECONDS);
            report.append("heap_peak_sampled_bytes=").append(peak.get()).append('\n');
            Files.writeString(output, report);
            System.out.print(report);
        }
    }

    private static void verify(GpManagerConfig.Retention period, StringBuilder report, Path temporary) throws Exception {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public Retention receiptRetentionDays() { return period; }
        };
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        long expectedNet = 0;
        for (int index = 0; index < SESSIONS; index++) {
            long at = NOW - (1L + (SESSIONS - 1L - index) * 1_095L / (SESSIONS - 1L)) * DAY;
            var session = new Session("History " + index, at, SessionMode.GENERAL);
            var run = engine.raids.start(index % 4, "Observed", at, false);
            run.ended = at + 300_000L;
            run.complete = run.claimed = true;
            run.owners.add(session.id());
            for (int item = 0; item < ROWS; item++) {
                var row = receipt(at + item * 1_000L, item);
                if (item == 1) row.flows.get(0).raid = run.id;
                expectedNet += row.net();
                session.addTransaction(row, config.maxTransactionsPerSession(), false);
            }
            session.close(at + 300_000L);
            engine.history.add(0, session);
        }
        engine.startCustom("Current", NOW);
        for (int item = 0; item < ACTIVE_ROWS; item++) {
            var row = receipt(NOW + item, item);
            expectedNet += row.net();
            engine.session().addTransaction(row, config.maxTransactionsPerSession(), false);
        }
        assertEquals(SESSIONS, engine.history.size());
        assertEquals(config.maxTransactionsPerSession(), engine.session().transactions().size());
        int folded = engine.compactOlderThan(period.days, NOW);
        assertTrue("old details must actually compact", folded > 0);
        assertEquals("a second sweep does no repeated work", 0, engine.compactOlderThan(period.days, NOW));
        engine.raids.prune();
        assertEquals(SESSIONS, engine.raids.state.runs.size());
        assertEquals(expectedNet, GrindsData.allTime(engine, NOW).net);
        long expectedRaidNet = SESSIONS * 2_000L;
        assertEquals(expectedRaidNet, engine.savedSession("raid:all").session.metrics(NOW).net);

        String prefix = "window_" + period.days + "d ";
        long retained = engine.sessions().stream().mapToLong(session -> session.transactions().size()).sum();
        report.append(prefix).append("history_sessions=").append(engine.history.size())
                .append(" raid_attempts=").append(engine.raids.state.runs.size())
                .append(" original_rows=").append(SESSIONS * ROWS + ACTIVE_ROWS)
                .append(" retained_rows=").append(retained).append(" compacted_rows=").append(folded).append('\n');
        engine.snapshot = null;
        measure(report, prefix + "Live initial", 1,
                () -> LiveSnapshot.capture(engine, NOW, LiveContext.NONE).net);
        var live = LiveSnapshot.capture(engine, NOW, LiveContext.NONE);
        var snapshot = engine.snapshot;
        var raidView = engine.savedSession("raid:all");
        for (int tick = 0; tick < 1_000; tick++) {
            assertSame(live.recent, LiveSnapshot.capture(engine, NOW + tick * 600L, LiveContext.NONE).recent);
            assertSame("only one money snapshot for unchanged state", snapshot, engine.snapshot);
            assertSame("raid projection does not accumulate per refresh", raidView, engine.savedSession("raid:all"));
        }
        assertEquals(engine.sessions().size(), snapshot.facts.size());
        measure(report, prefix + "Live warm", 100,
                () -> LiveSnapshot.capture(engine, NOW, LiveContext.NONE).net);
        measure(report, prefix + "Ledger", 100,
                () -> LedgerData.capture(engine, NOW, LedgerData.Entry.current()).net);
        measure(report, prefix + "Grinds", 100,
                () -> GrindsData.capture(engine, NOW, false, null).plans.size());
        measure(report, prefix + "All raids warm", 100,
                () -> engine.savedSession("raid:all").session.metrics(NOW).net);

        for (int index = 0; index < 72; index++) {
            var offer = new GeOffer("history-perf-" + index, index < 64 ? -1 : index - 64,
                    index % 2 == 0 ? GeOffer.Side.SELL : GeOffer.Side.BUY,
                    1_319, "Rune axe", 10, 7_202, NOW - index * 60_000L, engine.session().id());
            offer.stage = index < 64 ? GeOffer.Stage.CLOSED : GeOffer.Stage.OPEN;
            offer.settlementId = engine.history.get(index).transactions().get(0).id();
            engine.exchange.records.add(offer);
        }
        measure(report, prefix + "Live warm with 72 GE", 100,
                () -> LiveSnapshot.capture(engine, NOW, LiveContext.NONE).net);
        assertNull("temporary GE index must be released", engine.holders);
        {
            var repository = new SessionRepository(GSON, FilepathTestSupport.root(temporary), false);
            var writer = new OrderedPersistenceWriter(repository, null);
            var persistence = new Persistence(null, null, repository, writer, engine);
            measure(report, prefix + "save intent", 100, () -> persistence.currentIntent(false).base);
            String json = GSON.toJson(persistence.currentIntent(false).state);
            report.append(prefix).append("json_bytes=").append(json.getBytes(StandardCharsets.UTF_8).length).append('\n');
            for (int cycle = 0; cycle < 3; cycle++) {
                engine.restore(GSON.fromJson(json, SavedState.class), NOW);
                assertEquals(expectedNet, GrindsData.allTime(engine, NOW).net);
                assertEquals(expectedRaidNet, engine.savedSession("raid:all").session.metrics(NOW).net);
                assertEquals(retained, engine.sessions().stream().mapToLong(session -> session.transactions().size()).sum());
                assertEquals(SESSIONS, engine.history.size());
                assertEquals(SESSIONS, engine.raids.state.runs.size());
                LiveSnapshot.capture(engine, NOW, LiveContext.NONE);
                assertEquals(engine.sessions().size(), engine.snapshot.facts.size());
                System.gc();
                Runtime runtime = Runtime.getRuntime();
                report.append(prefix).append("reload_cycle=").append(cycle)
                        .append(" heap_used_after_gc_request_bytes=")
                        .append(runtime.totalMemory() - runtime.freeMemory()).append('\n');
            }
            // An account replacement must evict the previous owner's cached facts on refresh.
            engine.restore(null, NOW);
            LiveSnapshot.capture(engine, NOW, LiveContext.NONE);
            assertTrue(engine.history.isEmpty());
            assertTrue(engine.raids.state.runs.isEmpty());
            assertTrue(engine.snapshot.facts.size() <= 1);
            assertNull(engine.holders);
        }
    }

    private static Transaction receipt(long at, int index) {
        boolean cost = index % 5 == 0;
        return Tx.of(at, cost ? TransactionType.CONSUMPTION : TransactionType.LOOT,
                cost ? Context.GENERIC : Context.LOOT, "History probe", true,
                Collections.singletonList(new Flow(cost ? 385 : 536, cost ? "Shark" : "Dragon bones",
                        cost ? -1 : 1, cost ? 1_000 : 2_000, cost ? -1_000L : 2_000L)));
    }

    private static void measure(StringBuilder report, String name, int samples, LongSupplier work) {
        for (int warm = 0; samples > 1 && warm < 10; warm++) work.getAsLong();
        long[] times = new long[samples];
        for (int sample = 0; sample < samples; sample++) {
            long before = System.nanoTime();
            work.getAsLong();
            times[sample] = System.nanoTime() - before;
        }
        Arrays.sort(times);
        report.append(String.format(Locale.ROOT, "%s median_ms=%.3f p99_ms=%.3f%n", name,
                times[samples / 2] / 1e6, times[Math.max(0, (int) Math.ceil(samples * .99) - 1)] / 1e6));
    }
}
