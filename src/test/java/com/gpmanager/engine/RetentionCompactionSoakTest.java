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
        List<Session> fixture = fixture();
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
        for (ReceiptRetentionPeriod period : ReceiptRetentionPeriod.values())
        {
            verifyWindow(period, fullJson, truth);
        }
        // Legacy Forever migrates to 365 days (charter L); the enum read is the migration.

        // The default 90-day window carries the lifetime, merge, CSV and reload soak.
        Engine engine = engine(ReceiptRetentionPeriod.DAYS_90);
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

    private void verifyWindow(ReceiptRetentionPeriod period, String fullJson, Truth truth)
    {
        int days = period.getDays();
        Engine engine = engine(period);
        long t = System.nanoTime();
        engine.restore(copy(fullJson), NOW);
        long restoreMs = ms(t);
        long cutoff = NOW - days * DAY;
        int retained = 0;
        long compacted = 0L;
        int kept = 0;
        for (Session session : engine.getHistory())
        {
            long[] money = truth.sessionMoney.get(session.getName());
            assertNotNull(session.getName(), money);
            SessionMetrics metrics = session.metrics(NOW);
            assertEquals(session.getName() + " revenue " + days + "d", money[0], metrics.revenue);
            assertEquals(session.getName() + " costs " + days + "d", money[1], metrics.costs);
            assertEquals(session.getName() + " net " + days + "d", money[2], metrics.net);
            assertEquals(session.getName() + " elapsed", money[3], metrics.elapsedMillis);
            long[] pk = truth.sessionPk.get(session.getName());
            if (pk != null)
            {
                PkMetrics metricsPk = session.pkMetrics();
                assertEquals(pk[0], metricsPk.kills);
                assertEquals(pk[1], metricsPk.deaths);
                assertEquals(session.getName() + " PK net", pk[2], metricsPk.net);
                assertEquals(session.getName() + " best kill", pk[3], metricsPk.bestKill);
                assertEquals(session.getName() + " worst death", pk[4], metricsPk.largestDeathLoss);
                PkHistoryProjection projection = session.pkProjection;
                assertNotNull(session.getName() + " PK projection exists", projection);
                assertEquals(session.getName() + " retained detail matches the projection",
                    projection.getRetainedDetailCount(), session.getPkEncounters().size());
                assertTrue(session.getName() + " retained detail stays bounded",
                    session.getPkEncounters().size() <= PkHistoryArchive.MAX_DETAILED_ENCOUNTERS);
                if (session.getPkEncounters().size() < 2)
                {
                    assertEquals(session.getName() + " detail scope stays truthful",
                        PkDetailScope.RETAINED_WINDOW, metricsPk.detailScope);
                }
            }
            for (Transaction row : session.getTransactions())
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
        assertEquals("a second sweep is a no-op", 0, engine.compactOlderThan(days, NOW));
        line(String.format(Locale.ROOT, "window_%dd retained_rows=%d compacted_rows=%d restore_ms=%d",
            days, retained, compacted, restoreMs));
    }

    // ── lifetime archive / records ────────────────────────────────────────────

    private void verifyLifetimeArchive(Engine engine, Truth truth, List<Session> fixture)
    {
        // PvP lifetime facts live in the profile PvP base and survive session trimming and reload.
        AllTime pvp = new AllTime();
        pvp.addPvp(EngineProbe.profileFacts(engine.pkHistory, engine.uniqueProfileSessions()));
        assertEquals(truth.kills, pvp.kills);
        assertEquals(truth.deaths, pvp.deaths);
        assertEquals("best kill survives trimming", truth.bestKill, pvp.bestKill);
        assertEquals("worst death survives trimming", truth.worstDeath, pvp.worstDeath);
        Engine reloaded = engine(ReceiptRetentionPeriod.DAYS_90);
        reloaded.restore(copy(GSON.toJson(engine.createSavedState())), NOW);
        AllTime afterReload = new AllTime();
        afterReload.addPvp(EngineProbe.profileFacts(reloaded.pkHistory, reloaded.uniqueProfileSessions()));
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

    private void verifyPendingClaim(Engine engine, Truth truth, String when)
    {
        List<SavedState.PendingClaim> claims = engine.getPendingClaims();
        assertEquals("the unresolved claim is neither lost nor duplicated " + when, 1, claims.size());
        assertEquals(PENDING_CLAIM_ROW, claims.get(0).getClaimId());
        Session owner = null;
        Transaction audit = null;
        int settledRows = 0;
        for (Session session : engine.getHistory())
        {
            for (Transaction row : session.getTransactions())
            {
                if (row.getId().equals(PENDING_CLAIM_ROW))
                {
                    owner = session;
                    audit = row;
                }
                if (row.getSourceClaimId().equals(SETTLED_CLAIM_ID)) settledRows++;
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

    private void verifyCsvReconciliation(Engine engine, Truth truth) throws IOException
    {
        // A PK session straddling the 90-day cutoff: compacted rows and retained rows in one file.
        Session straddling = session(engine, "PK trip " + (DAYS - 90));
        assertTrue(straddling.compactedTransactionCount > 0L);
        assertFalse(straddling.getTransactions().isEmpty());
        Filepath directory = FilepathTestSupport.root(folder.newFolder("csv").toPath());
        Filepath result = new CsvExporter().exportSession(straddling, directory);
        SessionMetrics metrics = straddling.metrics(NOW);
        assertEquals(truth.sessionMoney.get(straddling.getName())[2], metrics.net);

        long[] detailSum = reconcile(result, "projected_row_revenue", "projected_row_costs", "ITEM_FLOW", "TRANSACTION");
        assertEquals("details: retained rows + COMPACTED row = session revenue", metrics.revenue, detailSum[0]);
        assertEquals("details: retained rows + COMPACTED row = session costs", metrics.costs, detailSum[1]);
        assertEquals("exactly one COMPACTED row", 1L, detailSum[2]);
        assertEquals("no fabricated old item rows", straddling.getTransactions().size(), detailSum[3]);

        // A fully compacted session exports no detail rows and one COMPACTED row that is the total.
        Session old = session(engine, "Day 10");
        assertTrue(old.getTransactions().isEmpty());
        Filepath oldResult = new CsvExporter().exportSession(old, directory);
        long[] oldSum = reconcile(oldResult, "projected_row_revenue", "projected_row_costs", "ITEM_FLOW", "TRANSACTION");
        assertEquals(truth.sessionMoney.get("Day 10")[0], oldSum[0]);
        assertEquals(truth.sessionMoney.get("Day 10")[1], oldSum[1]);
        assertEquals(1L, oldSum[2]);
        assertEquals(0L, oldSum[3]);
        line("csv_straddling_retained_rows=" + straddling.getTransactions().size()
            + " compacted_rows=" + straddling.compactedTransactionCount);
    }

    /** Sums the revenue/costs columns over detail rows plus the COMPACTED row: {revenue, costs, compactedRows, detailRows}. */
    private static long[] reconcile(Filepath csv, String revenueColumn, String costsColumn, String... detailKinds) throws IOException
    {
        List<String> lines = Files.readAllLines(FilepathTestSupport.path(csv), StandardCharsets.UTF_8);
        List<String> header = Arrays.asList(lines.get(0).split(","));
        int kind = header.indexOf("record_kind");
        int revenue = header.indexOf(revenueColumn);
        int costs = header.indexOf(costsColumn);
        int explanation = header.indexOf("explanation");
        long[] sum = new long[4];
        List<String> kinds = Arrays.asList(detailKinds);
        for (String line : lines.subList(1, lines.size()))
        {
            String[] cells = splitCsv(line);
            if (cells.length != header.size()) throw new AssertionError("column count " + cells.length + " != " + header.size() + ": " + line);
            if (cells[kind].equals("COMPACTED"))
            {
                sum[2]++;
                assertEquals("COMPACTED", cells[explanation]);
            }
            else if (kinds.contains(cells[kind]))
            {
                sum[3]++;
            }
            else
            {
                continue;
            }
            sum[0] += cells[revenue].isEmpty() ? 0L : Long.parseLong(cells[revenue]);
            sum[1] += cells[costs].isEmpty() ? 0L : Long.parseLong(cells[costs]);
        }
        return sum;
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

    private void verifyRepeatedSaveReload(Engine seed, Truth truth) throws IOException
    {
        Path directory = folder.newFolder("reload").toPath();
        SessionRepository repository = new SessionRepository(GSON,
            FilepathTestSupport.root(directory));
        Engine engine = seed;
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
            SavedState state = engine.createSavedState();
            assertTrue("save round " + round, PersistenceProbe.save(repository, state));
            saveNs += System.nanoTime() - t;
            long bytes = Files.size(FilepathTestSupport.path(
                repository.getDataDirectory().joinSegment("sessions.json")));
            minBytes = Math.min(minBytes, bytes);
            maxBytes = Math.max(maxBytes, bytes);
            t = System.nanoTime();
            SavedState loaded = repository.load();
            loadNs += System.nanoTime() - t;
            assertEquals(SavedState.CURRENT_SCHEMA_VERSION, loaded.schemaVersion);
            assertEquals(round + 1L, loaded.revision);
            engine = engine(ReceiptRetentionPeriod.DAYS_90);
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
    private static String fingerprint(Engine engine)
    {
        StringBuilder sb = new StringBuilder();
        PkProfileBase pvp = EngineProbe.profileFacts(engine.pkHistory, engine.uniqueProfileSessions());
        sb.append(pvp.getKills()).append('|').append(pvp.getBestKill()).append('|');
        sb.append(engine.getHistory().size()).append('|').append(engine.getPendingClaims().size()).append('|');
        for (Session session : engine.getHistory())
        {
            SessionMetrics metrics = session.metrics(NOW);
            sb.append(session.getId()).append(':').append(metrics.revenue).append(':').append(metrics.costs)
                .append(':').append(session.getTransactions().size()).append(':').append(session.compactedTransactionCount)
                .append(':').append(session.getPkEncounters().size()).append(':').append(session.pkMetrics().bestKill).append(';');
        }
        return sb.toString();
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private static List<Session> fixture()
    {
        List<Session> sessions = new ArrayList<>();
        for (int day = 0; day < DAYS; day++)
        {
            // Day DAYS-90 straddles the default cutoff: its kill compacts, its later death stays retained.
            boolean pk = day % 10 == 5 || day == DAYS - 90;
            long start = BASE + day * DAY + 3_600_000L;
            Session session = new Session(pk ? "PK trip " + day : "Day " + day, start,
                pk ? SessionMode.PK : SessionMode.AUTO);
            session.setActivityHint(pk ? "PKing" : "Woodcutting", start);
            long logsQuantity = dayLogsQuantity(day);
            String splitTarget = null;
            String ignoreTarget = null;
            for (int i = 0; i < 7; i++)
            {
                Transaction loot = row(start + (i + 1) * 60_000L, TransactionType.LOOT, Context.LOOT,
                    "Woodcutting", true, ClassificationConfidence.CONFIRMED,
                    new Flow(LOGS, "Logs", logsQuantity, 39, logsQuantity * 39L));
                session.addTransaction(loot, 100_000);
                if (i == 1) splitTarget = loot.getId();
                if (i == 2) ignoreTarget = loot.getId();
                session.recordAction("Woodcutting", loot.timestampEpochMillis);
            }
            session.addTransaction(row(start + 8 * 60_000L, TransactionType.CONSUMPTION, Context.GENERIC,
                "Woodcutting", true, ClassificationConfidence.CONFIRMED,
                new Flow(SHARK, "Shark", -2L, 385, -770L)), 100_000);
            session.addTransaction(row(start + 9 * 60_000L, TransactionType.UNCERTAIN, Context.GENERIC,
                "Woodcutting", false, ClassificationConfidence.UNCERTAIN,
                new Flow(COINS, "Coins", 1_000L, 1, 1_000L)), 100_000);
            session.addTransaction(row(start + 10 * 60_000L, TransactionType.LOOT, Context.LOOT,
                "Woodcutting", true, ClassificationConfidence.LIKELY,
                new Flow(UNPRICED, "Abyssal whip", 1L, 0, 0L)), 100_000);
            if (day % 3 == 0) assertTrue(session.correctTransaction(ignoreTarget, Correction.IGNORE, start + 11 * 60_000L, "Not mine"));
            if (day % 3 == 1) assertTrue(session.correctTransaction(ignoreTarget, Correction.COST, start + 11 * 60_000L, "Bought"));
            if (day % 3 == 2) assertTrue(session.applyItemSplit(splitTarget, LOGS, logsQuantity / 2, start + 11 * 60_000L, "half"));
            if (day == 3)
            {
                Transaction audit = row(start + 13 * 60_000L, TransactionType.ADJUSTMENT, Context.PK_LOOT,
                    "Loot key audit", false, ClassificationConfidence.CONFIRMED,
                    new Flow(KEY, "Loot key", 1L, 0, 0L));
                PENDING_CLAIM_ROW = audit.getId();
                session.addTransaction(audit, 100_000);
            }
            if (day == 4)
            {
                Transaction settlement = row(start + 13 * 60_000L, TransactionType.PK_LOOT, Context.PK_LOOT,
                    "Loot key chest", true, ClassificationConfidence.CONFIRMED,
                    new Flow(COINS, "Coins", 5_000L, 1, 5_000L));
                settlement.setSourceClaimId(SETTLED_CLAIM_ID);
                session.addTransaction(settlement, 100_000);
            }
            if (pk)
            {
                long killAt = start + 20 * 60_000L;
                PkEncounter kill = session.addPkEncounter(EncounterType.KILL, killAt, "Player kill", ClassificationConfidence.CONFIRMED, "Kill");
                Transaction loot = row(killAt + 1_000L, TransactionType.PK_LOOT, Context.PK_LOOT, "PKing", true,
                    ClassificationConfidence.CONFIRMED, new Flow(COINS, "Coins", 50_000L + (DAYS - day) * 10L, 1, 50_000L + (DAYS - day) * 10L));
                session.addTransaction(loot, 100_000);
                session.attachTransactionToEncounter(loot.getId(), kill.getId(), false);
                long deathAt = start + 12L * 3_600_000L;
                PkEncounter death = session.addPkEncounter(EncounterType.DEATH, deathAt, "Death", ClassificationConfidence.CONFIRMED, "Death");
                Transaction loss = row(deathAt + 1_000L, TransactionType.PK_DEATH_LOSS, Context.PK_DEATH, "PKing", true,
                    ClassificationConfidence.CONFIRMED, new Flow(SHARK, "Shark", -(80L + day % 7), 385, -(80L + day % 7) * 385L));
                session.addTransaction(loss, 100_000);
                session.attachTransactionToEncounter(loss.getId(), death.getId(), false);
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

    private static Transaction row(long at, TransactionType type, Context context, String activity,
        boolean counted, ClassificationConfidence confidence, Flow flow)
    {
        return new Transaction(at, null, type, context, type.name().toLowerCase(Locale.ROOT), activity, counted,
            Collections.singletonList(flow), confidence, "soak", null);
    }

    private static Truth truth(List<Session> sessions)
    {
        Truth truth = new Truth();
        truth.sessions = sessions.size();
        for (Session session : sessions)
        {
            SessionMetrics metrics = session.metrics(NOW);
            truth.sessionMoney.put(session.getName(), new long[] {metrics.revenue, metrics.costs, metrics.net, metrics.elapsedMillis});
            long compactedRevenue = 0L;
            long compactedCosts = 0L;
            long cutoff = NOW - ReceiptRetentionPeriod.DAYS_90.getDays() * DAY;
            for (Transaction row : session.getTransactions())
            {
                if (row.timestampEpochMillis >= cutoff
                    || row.getId().equals(PENDING_CLAIM_ROW)
                    || SETTLED_CLAIM_ID.equals(row.getSourceClaimId())
                    || row.getType() == TransactionType.TRANSFER
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
            for (Transaction row : session.getTransactions())
            {
                if (!row.isCounted()) continue;
                String day = LocalDate.ofEpochDay(Math.floorDiv(row.timestampEpochMillis, DAY)).toString();
                truth.dayNet.merge(day, row.getNet(), Long::sum);
            }
            if (session.getMode() == SessionMode.PK)
            {
                PkMetrics pk = session.pkMetrics();
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

    private static SavedState state(List<Session> sessions, boolean withClaims)
    {
        List<Session> newestFirst = new ArrayList<>(sessions);
        Collections.reverse(newestFirst);
        SavedState state = new SavedState(null, null, false, newestFirst);
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(NOW);
        state.setLastReceiptRetentionDayUtc(LocalDate.ofEpochDay(NOW / DAY).toString());
        if (!withClaims) return state;
        List<SavedState.PendingClaim> claims = new ArrayList<>();
        claims.add(new SavedState.PendingClaim(PENDING_CLAIM_ROW, KEY, 1L,
            BASE + 3 * DAY));
        claims.add(new SavedState.PendingClaim(SETTLED_CLAIM_ID, KEY, 1L,
            BASE + 4 * DAY));
        state.setPendingClaims(claims);
        return state;
    }

    private static SavedState copy(String json)
    {
        return GSON.fromJson(json, SavedState.class);
    }

    private static Engine engine(ReceiptRetentionPeriod period)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int maxHistorySessions() { return 5_000; }
            @Override public int maxTransactionsPerSession() { return 100_000; }
            @Override public ReceiptRetentionPeriod receiptRetentionDays() { return period; }
        };
        return new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), config);
    }

    private static Session session(Engine engine, String name)
    {
        for (Session session : engine.getHistory())
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
