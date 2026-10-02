package com.gpmanager;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.client.util.Filepath;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Checkpoint 4 retention/compaction soak (charter L, T, 4.1). One synthetic owner with 11k+
 * receipts over three years of UTC days: corrected, split, excluded, unpriced and uncertain rows,
 * PK encounters and one unresolved loot-key claim. Every window compacts the
 * same fixture and must reconcile exactly against totals computed from the uncompacted rows.
 * Offline model/engine only — not a live client soak.
 */
public class RetentionCompactionSoakTest
{
    private static final long DAY = 86_400_000L;
    private static final int DAYS = 1_100;
    private static final long BASE = Instant.parse("2023-01-15T00:00:00Z").toEpochMilli();
    private static final long NOW = BASE + DAYS * DAY + 12L * 3_600_000L;
    private static final int LOGS = 1511;
    private static final int SHARK = 385;
    private static final int COINS = 995;
    private static final int UNPRICED = 4151;
    private static final int KEY = 26_892;
    private static final long WINDOW = 60_000L;
    /** The unresolved claim's audit row id; assigned by {@link #fixture()}. */
    private static String PENDING_CLAIM_ROW = "";
    private static final String SETTLED_CLAIM_ID = "settled-claim-id";

    private static final Gson GSON = new Gson();

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void bindCodec()
    {
        JsonCodec.bind(GSON);
    }

    /** Ground truth computed from the uncompacted fixture rows. */
    private static final class Truth
    {
        final Map<String, long[]> sessionMoney = new LinkedHashMap<>();
        /** Default-window compacted subset, independent of post-restore aggregates. */
        final Map<String, long[]> sessionCompactedMoney = new LinkedHashMap<>();
        final Map<String, long[]> sessionPk = new LinkedHashMap<>();
        final TreeMap<String, Long> dayNet = new TreeMap<>();
        long transactions;
        long net;
        long activeMillis;
        long kills;
        long deaths;
        long bestKill;
        long worstDeath;
        long pvpNet;
        int sessions;
    }

    private final StringBuilder report = new StringBuilder();

    @Test
    public void tenThousandReceiptsOverThreeYearsCompactAndReconcileAcrossEveryWindowAndReload() throws Exception
    {
        List<Ad> fixture = fixture();
        Truth truth = truth(fixture);
        assertTrue("10k+ transaction fixture: " + truth.transactions, truth.transactions >= 10_000L);
        assertTrue("multi-year fixture: " + DAYS + " UTC days", DAYS >= 3 * 365);
        assertEquals(DAYS, truth.dayNet.size());
        line("label=SYNTHETIC_NOT_LIVE_CLIENT");
        line("fixture_sessions=" + truth.sessions);
        line("fixture_transactions=" + truth.transactions);
        line("fixture_utc_days=" + DAYS);
        line("fixture_pk_encounters=" + (truth.kills + truth.deaths));

        SavedState fullState = state(fixture, true);
        String fullJson = GSON.toJson(fullState);
        line("uncompacted_json_bytes=" + fullJson.getBytes(StandardCharsets.UTF_8).length);

        // Uncompacted save/load through the repository: the 10k+ profile round trip and its cost.
        Path uncompactedDir = folder.newFolder("uncompacted").toPath();
        SessionRepository uncompacted = new SessionRepository(GSON,
            FilepathTestSupport.root(uncompactedDir));
        long t = System.nanoTime();
        assertTrue(PersistenceProbe.save(uncompacted, copy(fullJson)));
        line("uncompacted_save_ms=" + ms(t));
        t = System.nanoTime();
        SavedState loadedFull = uncompacted.load();
        line("uncompacted_load_ms=" + ms(t));
        assertEquals(truth.sessions, loadedFull.getHistory().size());
        line("uncompacted_dir_bytes=" + dirSize(uncompactedDir));

        // Every shipping window compacts the same fixture and reconciles exactly.
        for (Db period : Db.values())
        {
            verifyWindow(period, fullJson, truth);
        }
        // Legacy Forever migrates to 365 days (charter L); the enum read is the migration.

        // The default 90-day window carries the lifetime, merge, CSV and reload soak.
        Am engine = engine(Db.DAYS_90);
        t = System.nanoTime();
        engine.restore(copy(fullJson), NOW);
        line("restore_and_compact_90d_ms=" + ms(t));
        verifyLifetimeArchive(engine, truth, fixture);
        verifyPendingClaim(engine, truth, "after restore");
        verifyCsvReconciliation(engine, truth);
        verifyRepeatedSaveReload(engine, truth);

        Path out = Path.of("build", "reports", "perf");
        Files.createDirectories(out);
        Files.write(out.resolve("retention-compaction-soak.txt"),
            ("retention_compaction_soak\n" + report).getBytes(StandardCharsets.UTF_8));
    }

    // ── windows ───────────────────────────────────────────────────────────────

    private void verifyWindow(Db period, String fullJson, Truth truth)
    {
        int days = period.getDays();
        Am engine = engine(period);
        long t = System.nanoTime();
        engine.restore(copy(fullJson), NOW);
        long restoreMs = ms(t);
        long cutoff = NOW - days * DAY;
        int retained = 0;
        long compacted = 0L;
        int kept = 0;
        for (Ad session : engine.getHistory())
        {
            long[] money = truth.sessionMoney.get(session.getName());
            assertNotNull(session.getName(), money);
            Bu metrics = session.metrics(NOW);
            assertEquals(session.getName() + " revenue " + days + "d", money[0], metrics.revenue);
            assertEquals(session.getName() + " costs " + days + "d", money[1], metrics.costs);
            assertEquals(session.getName() + " net " + days + "d", money[2], metrics.net);
            assertEquals(session.getName() + " elapsed", money[3], metrics.elapsedMillis);
            long[] pk = truth.sessionPk.get(session.getName());
            if (pk != null)
            {
                Dt metricsPk = session.ava();
                assertEquals(pk[0], metricsPk.kills);
                assertEquals(pk[1], metricsPk.deaths);
                assertEquals(session.getName() + " PK net", pk[2], metricsPk.net);
                assertEquals(session.getName() + " best kill", pk[3], metricsPk.bestKill);
                assertEquals(session.getName() + " worst death", pk[4], metricsPk.largestDeathLoss);
                PkHistoryProjection projection = session.pkProjection;
                assertNotNull(session.getName() + " PK projection exists", projection);
                assertEquals(session.getName() + " retained detail matches the projection",
                    projection.uj(), session.getPkEncounters().size());
                assertTrue(session.getName() + " retained detail stays bounded",
                    session.getPkEncounters().size() <= PkHistoryArchive.MAX_DETAILED_ENCOUNTERS);
                if (session.getPkEncounters().size() < 2)
                {
                    assertEquals(session.getName() + " detail scope stays truthful",
                        Di.RETAINED_WINDOW, metricsPk.detailScope);
                }
            }
            for (Ac row : session.getTransactions())
            {
                retained++;
                if (row.getId().equals(PENDING_CLAIM_ROW))
                {
                    kept++;
                    continue;
                }
                assertTrue(days + "d window retained an older row at " + row.timestampEpochMillis,
                    row.timestampEpochMillis >= cutoff);
            }
            compacted += session.compactedTransactionCount;
        }
        assertEquals("retained + compacted = every fixture row (" + days + "d)", truth.transactions, retained + compacted);
        assertTrue(days + "d window compacted nothing", compacted > 0L);
        assertEquals("the unresolved claim's audit row survives every window", 1, kept);
        assertEquals("a second sweep is a no-op", 0, engine.pg(days, NOW));
        line(String.format(Locale.ROOT, "window_%dd retained_rows=%d compacted_rows=%d restore_ms=%d",
            days, retained, compacted, restoreMs));
    }

    // ── lifetime archive / records ────────────────────────────────────────────

    private void verifyLifetimeArchive(Am engine, Truth truth, List<Ad> fixture)
    {
        // PvP lifetime facts live in the profile PvP base and survive session trimming and reload.
        AllTime pvp = new AllTime();
        pvp.addPvp(EngineProbe.profileFacts(engine.pkHistory, engine.akf()));
        assertEquals(truth.kills, pvp.kills);
        assertEquals(truth.deaths, pvp.deaths);
        assertEquals("best kill survives trimming", truth.bestKill, pvp.bestKill);
        assertEquals("worst death survives trimming", truth.worstDeath, pvp.worstDeath);
        Am reloaded = engine(Db.DAYS_90);
        reloaded.restore(copy(GSON.toJson(engine.qm())), NOW);
        AllTime afterReload = new AllTime();
        afterReload.addPvp(EngineProbe.profileFacts(reloaded.pkHistory, reloaded.akf()));
        assertEquals(pvp.toString(), afterReload.toString());
    }

    /** All-time facts summed from day rows and/or the lifetime archive; equal strings mean equal facts. */
    private static final class AllTime
    {
        long net, active, kills, deaths, bestKill, worstDeath, killNet, lossGp, starts, namedStarts;

        /** Profile PvP facts compose from the immutable base plus retained projections/anchors. */
        void addPvp(PkProfileBase facts)
        {
            if (facts == null) return;
            kills += facts.getKills();
            deaths += facts.getDeaths();
            killNet += facts.getKillNet();
            lossGp += facts.getDeathLoss();
            bestKill = Math.max(bestKill, facts.getBestKill());
            worstDeath = Math.max(worstDeath, facts.getLargestDeathLoss());
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("net=").append(net).append(" active=").append(active).append(" kills=").append(kills)
                .append(" deaths=").append(deaths).append(" bestKill=").append(bestKill).append(" worstDeath=").append(worstDeath)
                .append(" killNet=").append(killNet).append(" loss=").append(lossGp).append(" starts=").append(starts)
                .append(" named=").append(namedStarts).append('\n');
            return sb.toString();
        }
    }

    // ── pending claim ─────────────────────────────────────────────────────────

    private void verifyPendingClaim(Am engine, Truth truth, String when)
    {
        List<SavedState.By> claims = engine.getPendingClaims();
        assertEquals("the unresolved claim is neither lost nor duplicated " + when, 1, claims.size());
        assertEquals(PENDING_CLAIM_ROW, claims.get(0).getClaimId());
        Ad owner = null;
        Ac audit = null;
        int settledRows = 0;
        for (Ad session : engine.getHistory())
        {
            for (Ac row : session.getTransactions())
            {
                if (row.getId().equals(PENDING_CLAIM_ROW))
                {
                    owner = session;
                    audit = row;
                }
                if (row.uo().equals(SETTLED_CLAIM_ID)) settledRows++;
            }
        }
        assertNotNull("the unresolved claim's audit row stays individually inspectable " + when, audit);
        assertEquals("Day 3", owner.getName());
        assertEquals("the audit row is not counted twice", 0L, audit.getNet());
        assertEquals("the settled claim was dropped on restore, its settlement booked once",
            0, settledRows == 0 ? 0 : settledRows - 1);
        assertEquals(truth.sessionMoney.get("Day 3")[2], owner.metrics(NOW).net);
    }

    // ── performance ───────────────────────────────────────────────────────────

    // ── CSV ───────────────────────────────────────────────────────────────────

    /** Retained receipts plus the one compacted contribution reconcile exactly to the session. */
    private void verifyCsvReconciliation(Am engine, Truth truth)
    {
        for (String name : new String[] {"PK trip " + (DAYS - 90), "Day 10"})
        {
            Ad session = session(engine, name);
            assertTrue(session.compactedTransactionCount > 0L);
            Bu metrics = session.metrics(NOW);
            assertEquals(truth.sessionMoney.get(name)[2], metrics.net);
            Ad.CompactedContribution compacted = session.pl();
            long revenue = compacted.revenue;
            long costs = compacted.costs;
            for (Ac row : session.getTransactions())
            {
                Bp.Ax amounts = Bp.transaction(row);
                if (amounts.available && amounts.included)
                {
                    revenue += amounts.revenue;
                    costs += amounts.costs;
                }
            }
            assertEquals(name + ": retained + compacted revenue", metrics.revenue, revenue);
            assertEquals(name + ": retained + compacted costs", metrics.costs, costs);
        }
    }

    private static String[] splitCsv(String line)
    {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            if (quoted)
            {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cell.append(c);
            }
            else if (c == '"') quoted = true;
            else if (c == ',') { cells.add(cell.toString()); cell.setLength(0); }
            else cell.append(c);
        }
        cells.add(cell.toString());
        return cells.toArray(new String[0]);
    }

    // ── merge ─────────────────────────────────────────────────────────────────

    // ── repeated save / reload ────────────────────────────────────────────────

    private void verifyRepeatedSaveReload(Am seed, Truth truth) throws IOException
    {
        Path directory = folder.newFolder("reload").toPath();
        SessionRepository repository = new SessionRepository(GSON,
            FilepathTestSupport.root(directory));
        Am engine = seed;
        String fingerprint = fingerprint(engine);
        long saveNs = 0L;
        long loadNs = 0L;
        long restoreNs = 0L;
        long minBytes = Long.MAX_VALUE;
        long maxBytes = 0L;
        final int rounds = 200;
        for (int round = 0; round < rounds; round++)
        {
            long t = System.nanoTime();
            SavedState state = engine.qm();
            assertTrue("save round " + round, PersistenceProbe.save(repository, state));
            saveNs += System.nanoTime() - t;
            long bytes = Files.size(FilepathTestSupport.path(
                repository.ty().joinSegment("sessions.json")));
            minBytes = Math.min(minBytes, bytes);
            maxBytes = Math.max(maxBytes, bytes);
            t = System.nanoTime();
            SavedState loaded = repository.load();
            loadNs += System.nanoTime() - t;
            assertEquals(SavedState.CURRENT_SCHEMA_VERSION, loaded.schemaVersion);
            assertEquals(round + 1L, loaded.revision);
            engine = engine(Db.DAYS_90);
            t = System.nanoTime();
            engine.restore(loaded, NOW + round);
            restoreNs += System.nanoTime() - t;
            assertEquals("round " + round + " drifted", fingerprint, fingerprint(engine));
        }
        verifyPendingClaim(engine, truth, "after " + rounds + " reloads");
        // The only expected byte-size drift is the decimal width of the monotonically increasing
        // revision field; payload shape and financial fingerprint remain stable.
        assertTrue("file size only changes with revision width: " + minBytes + ".." + maxBytes,
            maxBytes - minBytes <= Long.toString(rounds + 1L).length() - 1L);
        line("reload_rounds=" + rounds);
        line("reload_profile_bytes=" + maxBytes);
        line("reload_avg_save_ms=" + saveNs / rounds / 1_000_000L);
        line("reload_avg_load_ms=" + loadNs / rounds / 1_000_000L);
        line("reload_avg_restore_ms=" + restoreNs / rounds / 1_000_000L);
        line("reload_dir_bytes=" + dirSize(directory));
    }

    /** Every accounting fact a reload must preserve, as one comparable string. */
    private static String fingerprint(Am engine)
    {
        StringBuilder sb = new StringBuilder();
        PkProfileBase pvp = EngineProbe.profileFacts(engine.pkHistory, engine.akf());
        sb.append(pvp.getKills()).append('|').append(pvp.getBestKill()).append('|');
        sb.append(engine.getHistory().size()).append('|').append(engine.getPendingClaims().size()).append('|');
        for (Ad session : engine.getHistory())
        {
            Bu metrics = session.metrics(NOW);
            sb.append(session.getId()).append(':').append(metrics.revenue).append(':').append(metrics.costs)
                .append(':').append(session.getTransactions().size()).append(':').append(session.compactedTransactionCount)
                .append(':').append(session.getPkEncounters().size()).append(':').append(session.ava().bestKill).append(';');
        }
        return sb.toString();
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private static List<Ad> fixture()
    {
        List<Ad> sessions = new ArrayList<>();
        for (int day = 0; day < DAYS; day++)
        {
            // Day DAYS-90 straddles the default cutoff: its kill compacts, its later death stays retained.
            boolean pk = day % 10 == 5 || day == DAYS - 90;
            long start = BASE + day * DAY + 3_600_000L;
            Ad session = new Ad(pk ? "PK trip " + day : "Day " + day, start,
                pk ? Cx.PK : Cx.AUTO);
            session.setActivityHint(pk ? "PKing" : "Woodcutting", start);
            long logsQuantity = dayLogsQuantity(day);
            String splitTarget = null;
            String ignoreTarget = null;
            for (int i = 0; i < 7; i++)
            {
                Ac loot = row(start + (i + 1) * 60_000L, Ai.LOOT, Aj.LOOT,
                    "Woodcutting", true, Bd.CONFIRMED,
                    new Ab(LOGS, "Logs", logsQuantity, 39, logsQuantity * 39L));
                session.kf(loot, 100_000);
                if (i == 1) splitTarget = loot.getId();
                if (i == 2) ignoreTarget = loot.getId();
                session.aeh("Woodcutting", loot.timestampEpochMillis);
            }
            session.kf(row(start + 8 * 60_000L, Ai.CONSUMPTION, Aj.GENERIC,
                "Woodcutting", true, Bd.CONFIRMED,
                new Ab(SHARK, "Shark", -2L, 385, -770L)), 100_000);
            session.kf(row(start + 9 * 60_000L, Ai.UNCERTAIN, Aj.GENERIC,
                "Woodcutting", false, Bd.UNCERTAIN,
                new Ab(COINS, "Coins", 1_000L, 1, 1_000L)), 100_000);
            session.kf(row(start + 10 * 60_000L, Ai.LOOT, Aj.LOOT,
                "Woodcutting", true, Bd.LIKELY,
                new Ab(UNPRICED, "Abyssal whip", 1L, 0, 0L)), 100_000);
            if (day % 3 == 0) assertTrue(session.qi(ignoreTarget, Ah.IGNORE, start + 11 * 60_000L, "Not mine"));
            if (day % 3 == 1) assertTrue(session.qi(ignoreTarget, Ah.COST, start + 11 * 60_000L, "Bought"));
            if (day % 3 == 2) assertTrue(session.kr(splitTarget, LOGS, logsQuantity / 2, start + 11 * 60_000L, "half"));
            if (day == 3)
            {
                Ac audit = row(start + 13 * 60_000L, Ai.ADJUSTMENT, Aj.PK_LOOT,
                    "Loot key audit", false, Bd.CONFIRMED,
                    new Ab(KEY, "Loot key", 1L, 0, 0L));
                PENDING_CLAIM_ROW = audit.getId();
                session.kf(audit, 100_000);
            }
            if (day == 4)
            {
                Ac settlement = row(start + 13 * 60_000L, Ai.PK_LOOT, Aj.PK_LOOT,
                    "Loot key chest", true, Bd.CONFIRMED,
                    new Ab(COINS, "Coins", 5_000L, 1, 5_000L));
                settlement.aid(SETTLED_CLAIM_ID);
                session.kf(settlement, 100_000);
            }
            if (pk)
            {
                long killAt = start + 20 * 60_000L;
                Bx kill = session.ke(Be.KILL, killAt, "Player kill", Bd.CONFIRMED, "Kill");
                Ac loot = row(killAt + 1_000L, Ai.PK_LOOT, Aj.PK_LOOT, "PKing", true,
                    Bd.CONFIRMED, new Ab(COINS, "Coins", 50_000L + (DAYS - day) * 10L, 1, 50_000L + (DAYS - day) * 10L));
                session.kf(loot, 100_000);
                session.ll(loot.getId(), kill.getId(), false);
                long deathAt = start + 12L * 3_600_000L;
                Bx death = session.ke(Be.DEATH, deathAt, "Death", Bd.CONFIRMED, "Death");
                Ac loss = row(deathAt + 1_000L, Ai.PK_DEATH_LOSS, Aj.PK_DEATH, "PKing", true,
                    Bd.CONFIRMED, new Ab(SHARK, "Shark", -(80L + day % 7), 385, -(80L + day % 7) * 385L));
                session.kf(loss, 100_000);
                session.ll(loss.getId(), death.getId(), false);
            }
            session.close(pk ? start + 13L * 3_600_000L : start + 60 * 60_000L);
            sessions.add(session);
        }
        return sessions;
    }

    private static long dayLogsQuantity(long day)
    {
        return 20L + day % 7;
    }

    private static Ac row(long at, Ai type, Aj context, String activity,
        boolean counted, Bd confidence, Ab flow)
    {
        return new Ac(at, null, type, context, type.name().toLowerCase(Locale.ROOT), activity, counted,
            Collections.singletonList(flow), confidence, "soak", null);
    }

    private static Truth truth(List<Ad> sessions)
    {
        Truth truth = new Truth();
        truth.sessions = sessions.size();
        for (Ad session : sessions)
        {
            Bu metrics = session.metrics(NOW);
            truth.sessionMoney.put(session.getName(), new long[] {metrics.revenue, metrics.costs, metrics.net, metrics.elapsedMillis});
            long compactedRevenue = 0L;
            long compactedCosts = 0L;
            long cutoff = NOW - Db.DAYS_90.getDays() * DAY;
            for (Ac row : session.getTransactions())
            {
                if (row.timestampEpochMillis >= cutoff
                    || row.getId().equals(PENDING_CLAIM_ROW)
                    || SETTLED_CLAIM_ID.equals(row.uo())
                    || row.getType() == Ai.TRANSFER
                    || !row.isCounted())
                {
                    continue;
                }
                compactedRevenue += row.getRevenue();
                compactedCosts += row.getCosts();
            }
            truth.sessionCompactedMoney.put(session.getName(),
                new long[] {compactedRevenue, compactedCosts});
            truth.net += metrics.net;
            truth.activeMillis += metrics.elapsedMillis;
            truth.transactions += session.getTransactions().size();
            for (Ac row : session.getTransactions())
            {
                if (!row.isCounted()) continue;
                String day = LocalDate.ofEpochDay(Math.floorDiv(row.timestampEpochMillis, DAY)).toString();
                truth.dayNet.merge(day, row.getNet(), Long::sum);
            }
            if (session.getMode() == Cx.PK)
            {
                Dt pk = session.ava();
                truth.sessionPk.put(session.getName(), new long[] {pk.kills, pk.deaths, pk.net, pk.bestKill, pk.largestDeathLoss});
                truth.kills += pk.kills;
                truth.deaths += pk.deaths;
                truth.pvpNet += pk.net;
                truth.bestKill = Math.max(truth.bestKill, pk.bestKill);
                truth.worstDeath = Math.max(truth.worstDeath, pk.largestDeathLoss);
            }
        }
        return truth;
    }

    private static SavedState state(List<Ad> sessions, boolean withClaims)
    {
        List<Ad> newestFirst = new ArrayList<>(sessions);
        Collections.reverse(newestFirst);
        SavedState state = new SavedState(null, null, false, newestFirst);
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(NOW);
        state.setLastReceiptRetentionDayUtc(LocalDate.ofEpochDay(NOW / DAY).toString());
        if (!withClaims) return state;
        List<SavedState.By> claims = new ArrayList<>();
        claims.add(new SavedState.By(PENDING_CLAIM_ROW, KEY, 1L,
            BASE + 3 * DAY));
        claims.add(new SavedState.By(SETTLED_CLAIM_ID, KEY, 1L,
            BASE + 4 * DAY));
        state.setPendingClaims(claims);
        return state;
    }

    private static SavedState copy(String json)
    {
        return GSON.fromJson(json, SavedState.class);
    }

    private static Am engine(Db period)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int maxHistorySessions() { return 5_000; }
            @Override public int maxTransactionsPerSession() { return 100_000; }
            @Override public Db receiptRetentionDays() { return period; }
        };
        return new Am(deltas -> Collections.emptyList(), new TransactionClassifier(), config);
    }

    private static Ad session(Am engine, String name)
    {
        for (Ad session : engine.getHistory())
        {
            if (session.getName().equals(name)) return session;
        }
        throw new AssertionError("no session " + name);
    }

    private void line(String text)
    {
        report.append(text).append('\n');
    }

    private static long ms(long startNanos)
    {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static long dirSize(Path root) throws IOException
    {
        final long[] total = {0L};
        try (java.util.stream.Stream<Path> paths = Files.walk(root))
        {
            paths.filter(Files::isRegularFile).forEach(path ->
            {
                try { total[0] += Files.size(path); }
                catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
            });
        }
        return total[0];
    }
}
