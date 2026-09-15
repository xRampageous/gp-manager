package com.gpmanager.simulation;

import com.google.gson.Gson;
import com.gpmanager.engine.ContainerSnapshot;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.HistoryDateRange;
import com.gpmanager.model.HistoryQuery;
import com.gpmanager.model.HistorySort;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionCategory;
import com.gpmanager.model.SessionComparisonMetrics;
import com.gpmanager.model.SessionIntelligenceSnapshot;
import com.gpmanager.model.SessionSummary;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import com.gpmanager.persistence.CsvExportResult;
import com.gpmanager.persistence.SavedState;
import com.gpmanager.persistence.SessionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GeneralProfitSimulation
{
    private GeneralProfitSimulation()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Path output = args.length == 0
            ? Paths.get("build", "simulation", "general")
            : Paths.get(args[0]);
        SimulationSupport.Suite suite = new SimulationSupport.Suite(
            "General Profit Simulation",
            output);

        suite.run("Opening inventory is baseline-only", GeneralProfitSimulation::openingInventoryIsNotProfit);
        suite.run("Bank and equipment round trips are neutral", GeneralProfitSimulation::bankAndEquipmentTransfersAreNeutral);
        suite.run("NPC loot and consumed supplies calculate exact net", GeneralProfitSimulation::npcLootAndSupplies);
        suite.run("Production inputs and outputs calculate processing margin", GeneralProfitSimulation::processingMargin);
        suite.run("Uncertain mixed changes require correction and support undo", GeneralProfitSimulation::uncertainCorrectionAndUndo);
        suite.run("Pause freezes elapsed time and both rates", GeneralProfitSimulation::pauseFreezesRates);
        suite.run("Idle pause and Auto activity segments preserve active time", GeneralProfitSimulation::idlePauseAndAutoActivity);
        suite.run("Crash-safe state save and restore preserves accounting", () -> crashSafeRestore(output));
        suite.run("Session comparison summarizes recent completed sessions", GeneralProfitSimulation::sessionComparison);
        suite.run("History filters, tags, exclusions, corrections, and deletion", GeneralProfitSimulation::historyManagement);
        suite.run("Lifetime intelligence, search, favorites, notes, dates, and sorting", GeneralProfitSimulation::sessionIntelligenceComplete);
        suite.run("General simulation exports readable CSV diagnostics", () -> exportGeneralExample(output));

        suite.finish();
    }

    private static void openingInventoryIsNotProfit()
    {
        GpManagerEngine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        long start = 100_000L;
        engine.ensureSession(start);
        engine.beginBaselinePriming();

        SimulationSupport.equal(null, engine.processIfDirty(ContainerSnapshot.empty(), start + 600L), "warm-up tick 1");
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 1_200L), "warm-up tick 2");
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 1_800L), "warm-up tick 3");
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 2_400L), "warm-up tick 4");

        SimulationSupport.equal(0L, engine.getMetrics(start + 2_400L).getNet(), "opening inventory net");
        SimulationSupport.equal(0, engine.getActiveSession().getTransactions().size(), "opening inventory transactions");

        engine.markInventoryDirty();
        ProfitTransaction consumed = SimulationSupport.settle(engine, ContainerSnapshot.empty(), start + 3_000L);
        SimulationSupport.equal(TransactionType.CONSUMPTION, consumed.getType(), "post-baseline removal type");
        SimulationSupport.equal(-100L, consumed.getNet(), "post-baseline removal value");
    }

    private static void bankAndEquipmentTransfersAreNeutral()
    {
        GpManagerEngine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        long start = 200_000L;
        engine.ensureSession(start);
        engine.setBaseline(SimulationSupport.snapshot(1265, 1L));

        engine.markContext(TrackingContext.TRANSFER, 8, "Bank transfer");
        engine.markInventoryDirty();
        SimulationSupport.equal(null, engine.processIfDirty(ContainerSnapshot.empty(), start + 600L), "temporary removal tick 1");
        SimulationSupport.equal(null, engine.processIfDirty(ContainerSnapshot.empty(), start + 1_200L), "temporary removal tick 2");

        engine.markInventoryDirty();
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 1_800L), "restore tick 1");
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 2_400L), "restore tick 2");
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 3_000L), "restore settle");

        SimulationSupport.equal(0, engine.getActiveSession().getTransactions().size(), "round-trip transaction count");
        SimulationSupport.equal(0L, engine.getMetrics(start + 3_000L).getNet(), "round-trip net");
    }

    private static void npcLootAndSupplies()
    {
        GpManagerEngine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        long start = 300_000L;
        engine.ensureSession(start);
        engine.setBaseline(SimulationSupport.snapshot(379, 4L, 892, 100L, 555, 50L));

        engine.markInventoryDirty();
        ProfitTransaction supplies = SimulationSupport.settle(
            engine,
            SimulationSupport.snapshot(379, 3L, 892, 90L, 555, 45L),
            start + 600L);
        SimulationSupport.equal(TransactionType.CONSUMPTION, supplies.getType(), "supply transaction type");
        SimulationSupport.equal(875L, supplies.getCosts(), "supply costs");

        Map<Integer, Long> loot = new LinkedHashMap<>();
        loot.put(526, 1L);
        loot.put(555, 6L);
        engine.recordAction("Goblin");
        engine.markLootContext(loot, 50, "Loot from Goblin", "Goblin");
        engine.markInventoryDirty();
        ProfitTransaction drop = SimulationSupport.settle(
            engine,
            SimulationSupport.snapshot(379, 3L, 892, 90L, 555, 51L, 526, 1L),
            start + 3_000L);

        SimulationSupport.equal(TransactionType.LOOT, drop.getType(), "loot transaction type");
        SimulationSupport.equal(ClassificationConfidence.CONFIRMED, drop.getConfidence(), "loot confidence");
        SimulationSupport.equal(61L, drop.getRevenue(), "loot revenue");
        SessionMetrics metrics = engine.getMetrics(start + 6_000L);
        SimulationSupport.equal(61L, metrics.getRevenue(), "session revenue");
        SimulationSupport.equal(875L, metrics.getCosts(), "session costs");
        SimulationSupport.equal(-814L, metrics.getNet(), "session net");
        SimulationSupport.equal(1, engine.getActivityBreakdown().get(0).getActionCount(), "goblin action count");
    }

    private static void processingMargin()
    {
        GpManagerEngine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        long start = 400_000L;
        engine.ensureSession(start);
        engine.setBaseline(SimulationSupport.snapshot(1511, 10L));

        engine.markContext(TrackingContext.PRODUCTION, 8, "Fletching logs");
        engine.markInventoryDirty();
        ProfitTransaction processing = SimulationSupport.settle(
            engine,
            SimulationSupport.snapshot(1511, 5L, 50, 5L),
            start + 600L);

        SimulationSupport.equal(TransactionType.PROCESSING, processing.getType(), "processing type");
        SimulationSupport.equal(600L, processing.getRevenue(), "output value");
        SimulationSupport.equal(250L, processing.getCosts(), "input value");
        SimulationSupport.equal(350L, processing.getNet(), "processing margin");
    }

    private static void uncertainCorrectionAndUndo()
    {
        GpManagerEngine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        long start = 500_000L;
        engine.ensureSession(start);
        engine.setBaseline(SimulationSupport.snapshot(995, 100L));

        engine.markInventoryDirty();
        ProfitTransaction uncertain = SimulationSupport.settle(
            engine,
            SimulationSupport.snapshot(995, 50L, 526, 2L),
            start + 600L);

        SimulationSupport.equal(TransactionType.UNCERTAIN, uncertain.getType(), "automatic uncertain type");
        SimulationSupport.check(!uncertain.isCounted(), "uncertain transaction should be excluded");
        SimulationSupport.equal(0L, engine.getMetrics(start + 3_000L).getNet(), "uncertain net before correction");

        SimulationSupport.check(
            engine.correctTransaction(uncertain.getId(), TransactionCorrection.REVENUE, start + 4_000L),
            "manual revenue correction should apply");
        SimulationSupport.equal(112L, engine.getMetrics(start + 4_000L).getNet(), "corrected gross revenue");

        ProfitTransaction removed = engine.undoLastTransaction();
        SimulationSupport.equal(uncertain.getId(), removed.getId(), "undo transaction id");
        SimulationSupport.equal(0, engine.getActiveSession().getTransactions().size(), "transactions after undo");
        SimulationSupport.equal(0L, engine.getMetrics(start + 5_000L).getNet(), "net after undo");
    }

    private static void pauseFreezesRates()
    {
        ProfitSession session = new ProfitSession("Pause simulation", 0L, SessionMode.GENERAL);
        session.addTransaction(
            new ProfitTransaction(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                TrackingContext.GENERIC,
                "Synthetic gain",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20001, "Test gain", 1L, 1_000))),
            100);

        session.pause(2_000L);
        SessionMetrics atPause = session.metrics(2_000L, 60_000L);
        SessionMetrics oneMinuteLater = session.metrics(62_000L, 60_000L);
        SimulationSupport.equal(atPause.getElapsedMillis(), oneMinuteLater.getElapsedMillis(), "paused elapsed time");
        SimulationSupport.equal(atPause.getProfitPerHour(), oneMinuteLater.getProfitPerHour(), "paused session rate");
        SimulationSupport.equal(atPause.getRollingProfitPerHour(), oneMinuteLater.getRollingProfitPerHour(), "paused rolling rate");

        session.resume(62_000L);
        SimulationSupport.equal(3_000L, session.metrics(63_000L, 60_000L).getElapsedMillis(), "resumed active time");
    }

    private static void idlePauseAndAutoActivity()
    {
        GpManagerEngine engine = SimulationSupport.newEngine(SessionMode.AUTO);
        engine.ensureSession(0L);
        engine.setDetectedActivity("PvM", 500L);
        engine.setDetectedActivity("Skilling", 1_000L);

        SimulationSupport.equal("Skilling", engine.getMetrics(1_000L).getActivityHint(), "auto activity hint");
        SimulationSupport.equal(3, engine.getActiveSession().getActivitySegments().size(), "activity segment count");

        engine.pauseForIdle(2_000L);
        SimulationSupport.check(engine.isIdlePaused(), "engine must record idle pause reason");
        long frozenElapsed = engine.getMetrics(8_000L).getElapsedMillis();
        SimulationSupport.equal(2_000L, frozenElapsed, "idle pause frozen elapsed");

        engine.resumeAfterIdle(8_000L);
        SimulationSupport.check(!engine.isIdlePaused(), "idle pause must clear after activity");
        SimulationSupport.equal(3_000L, engine.getMetrics(9_000L).getElapsedMillis(), "active time after idle resume");
    }

    private static void crashSafeRestore(Path output) throws Exception
    {
        Path stateDirectory = output.resolve("state-recovery");
        Files.createDirectories(stateDirectory);
        SessionRepository repository = new SessionRepository(new Gson(), stateDirectory);
        ProfitSession session = new ProfitSession("Recovered session", 1_000L, SessionMode.GENERAL);
        session.addTransaction(
            new ProfitTransaction(
                2_000L,
                TransactionType.LOOT,
                TrackingContext.LOOT,
                "Recovered loot",
                true,
                Collections.singletonList(SimulationSupport.gain(526, 2L))),
            100);
        repository.save(new SavedState(session, Collections.emptyList()));

        SavedState restored = repository.load();
        SimulationSupport.check(restored.getActiveSession() != null, "active session should restore");
        SimulationSupport.equal("Recovered session", restored.getActiveSession().getName(), "restored session name");
        SimulationSupport.equal(62L, restored.getActiveSession().metrics(3_000L, 60_000L).getNet(), "restored session net");

        GpManagerEngine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        engine.restore(restored);
        SimulationSupport.check(
            engine.getActiveSession().isRecoveredFromCrash(),
            "interrupted active session should be labelled recovered");
    }

    private static void sessionComparison()
    {
        ProfitSession profitable = new ProfitSession("Profitable", 0L, SessionMode.GENERAL);
        profitable.addTransaction(
            new ProfitTransaction(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                TrackingContext.GENERIC,
                "Historical profit",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20010, "Profit", 1L, 1_000))),
            100);
        profitable.close(3_600_000L);

        ProfitSession loss = new ProfitSession("Loss", 0L, SessionMode.GENERAL);
        loss.addTransaction(
            new ProfitTransaction(
                1_000L,
                1_000L,
                TransactionType.CONSUMPTION,
                TrackingContext.GENERIC,
                "Historical loss",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20011, "Cost", -1L, 500))),
            100);
        loss.close(3_600_000L);

        ProfitSession current = new ProfitSession("Current", 0L, SessionMode.GENERAL);
        current.addTransaction(
            new ProfitTransaction(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                TrackingContext.GENERIC,
                "Current profit",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20012, "Current", 1L, 600))),
            100);

        GpManagerEngine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        engine.restore(new SavedState(current, Arrays.asList(profitable, loss)), 3_600_000L);
        SessionComparisonMetrics comparison = engine.getSessionComparison(3_600_000L, 10);

        SimulationSupport.equal(2, comparison.getSessionCount(), "compared session count");
        SimulationSupport.equal(1, comparison.getProfitableSessionCount(), "profitable session count");
        SimulationSupport.equal(250L, comparison.getAverageNet(), "average historical net");
        SimulationSupport.equal(350L, comparison.getCurrentVsAverage(), "current versus average");
        SimulationSupport.equal(1_000L, comparison.getBestNet(), "best historical net");
        SimulationSupport.equal(-500L, comparison.getWorstNet(), "worst historical net");
        SimulationSupport.equal(50, comparison.getProfitablePercent(), "profitable session percent");
    }

    private static void historyManagement()
    {
        ProfitSession pvm = new ProfitSession("Bossing", 0L, SessionMode.GENERAL);
        pvm.addTransaction(
            new ProfitTransaction(
                1_000L,
                1_000L,
                TransactionType.LOOT,
                TrackingContext.LOOT,
                "Boss loot",
                "Bossing",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(21000, "Boss drop", 1L, 1_000))),
            100);
        pvm.close(3_600_000L);

        ProfitSession skilling = new ProfitSession("Fletching", 0L, SessionMode.GENERAL);
        skilling.addTransaction(
            new ProfitTransaction(
                1_000L,
                1_000L,
                TransactionType.PROCESSING,
                TrackingContext.PRODUCTION,
                "Fletching",
                "Fletching",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(21001, "Bow", 1L, 500))),
            100);
        skilling.close(3_600_000L);

        ProfitSession trading = new ProfitSession("Flipping", 0L, SessionMode.GENERAL);
        ProfitTransaction tradeLoss = new ProfitTransaction(
            1_000L,
            1_000L,
            TransactionType.TRADE,
            TrackingContext.MARKET,
            "Trade loss",
            "Market",
            true,
            Collections.singletonList(SimulationSupport.valuedFlow(21002, "Trade", -1L, 200)));
        trading.addTransaction(tradeLoss, 100);
        trading.close(3_600_000L);

        GpManagerEngine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        engine.restore(new SavedState(null, Arrays.asList(pvm, skilling, trading)), 3_600_000L);

        SimulationSupport.equal(1, engine.getFilteredHistory(SessionCategory.PVM).size(), "PvM filter count");
        SimulationSupport.equal(1, engine.getFilteredHistory(SessionCategory.SKILLING).size(), "skilling filter count");
        SimulationSupport.equal(1, engine.getFilteredHistory(SessionCategory.TRADING).size(), "trading filter count");

        SimulationSupport.check(engine.renameHistorySession(pvm.getId(), "Vorkath"), "history rename");
        SimulationSupport.check(engine.setHistorySessionTags(pvm.getId(), "boss, pet hunt, boss"), "history tags");
        SimulationSupport.equal("boss, pet hunt", pvm.getTagsDisplay(), "deduplicated tags");

        SimulationSupport.check(engine.setHistorySessionExcluded(trading.getId(), true), "exclude history");
        SessionComparisonMetrics comparison = engine.getSessionComparison(3_600_000L, 10);
        SimulationSupport.equal(2, comparison.getSessionCount(), "excluded comparison count");
        SimulationSupport.equal(750L, comparison.getAverageNet(), "excluded comparison average");

        SessionSummary before = engine.getHistorySummary(trading.getId(), 3_600_000L);
        SimulationSupport.equal(-200L, before.getMetrics().getNet(), "historical net before correction");
        SimulationSupport.check(
            engine.correctHistoryTransaction(
                trading.getId(),
                tradeLoss.getId(),
                TransactionCorrection.REVENUE,
                4_000_000L),
            "historical correction");
        SessionSummary after = engine.getHistorySummary(trading.getId(), 4_000_000L);
        SimulationSupport.equal(200L, after.getMetrics().getNet(), "historical net after correction");

        SimulationSupport.check(engine.deleteHistorySession(skilling.getId()), "history deletion");
        SimulationSupport.equal(2, engine.getHistory().size(), "history size after deletion");
    }

    private static void sessionIntelligenceComplete()
    {
        long day = 24L * 60L * 60L * 1000L;
        long now = 100L * day;
        ProfitSession pvm = completedSession("Vorkath", now - day, TrackingContext.LOOT, TransactionType.LOOT, 1_000L);
        pvm.setTags("boss, blue dragon");
        pvm.setNotes("pet hunt");
        pvm.setFavorite(true);
        ProfitSession skilling = completedSession("Fletching", now - 10L * day, TrackingContext.PRODUCTION, TransactionType.PROCESSING, 500L);
        skilling.setNotes("afk bows");
        ProfitSession trading = completedSession("Flipping", now - 40L * day, TrackingContext.MARKET, TransactionType.TRADE, -200L);

        GpManagerEngine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        engine.restore(new SavedState(null, Arrays.asList(pvm, skilling, trading)), now);

        SimulationSupport.equal(1, engine.getHistory(new HistoryQuery(
            SessionCategory.ALL, "pet", HistoryDateRange.ALL_TIME, HistorySort.NEWEST, false), now).size(), "history note search");
        SimulationSupport.equal(1, engine.getHistory(new HistoryQuery(
            SessionCategory.ALL, "", HistoryDateRange.ALL_TIME, HistorySort.NEWEST, true), now).size(), "favorite-only history");
        SimulationSupport.equal(2, engine.getHistory(new HistoryQuery(
            SessionCategory.ALL, "", HistoryDateRange.LAST_30_DAYS, HistorySort.NEWEST, false), now).size(), "date range history");
        SimulationSupport.equal("Vorkath", engine.getHistory(new HistoryQuery(
            SessionCategory.ALL, "", HistoryDateRange.ALL_TIME, HistorySort.PROFIT_HIGH, false), now).get(0).getName(), "profit sort");

        SessionIntelligenceSnapshot intelligence = engine.getSessionIntelligence(now, 2);
        SimulationSupport.equal(2, intelligence.getRecent().getSessionCount(), "recent intelligence count");
        SimulationSupport.equal(3, intelligence.getLifetime().getSessionCount(), "lifetime intelligence count");
        SimulationSupport.equal(750L, intelligence.getRecent().getAverageNet(), "recent average net");
        SimulationSupport.equal(433L, intelligence.getLifetime().getAverageNet(), "lifetime average net");
        SimulationSupport.check(!intelligence.getActivityMetrics().isEmpty(), "lifetime activity metrics");
        SimulationSupport.equal(2, intelligence.getRecentNetTrend().size(), "trend point count");
    }

    private static ProfitSession completedSession(
        String name, long start, TrackingContext context, TransactionType type, long value)
    {
        ProfitSession session = new ProfitSession(name, start, SessionMode.GENERAL);
        long quantity = value < 0L ? -1L : 1L;
        session.addTransaction(new ProfitTransaction(
            start + 1_000L, 1_000L, type, context, name, name, true,
            Collections.singletonList(new ItemFlow(31_000, "Item", quantity, (int) Math.abs(value), value))), 100);
        session.close(start + 3_600_000L);
        return session;
    }

    private static void exportGeneralExample(Path output) throws Exception
    {
        ProfitSession session = new ProfitSession("General-Simulation", 1_000L, SessionMode.GENERAL);
        session.recordAction("Goblin");
        session.addTransaction(
            new ProfitTransaction(
                2_000L,
                1_000L,
                TransactionType.LOOT,
                TrackingContext.LOOT,
                "Loot from Goblin",
                "Goblin",
                true,
                Arrays.asList(SimulationSupport.gain(526, 1L), SimulationSupport.gain(555, 6L)),
                ClassificationConfidence.CONFIRMED,
                "Offline simulation of confirmed NPC loot.",
                null),
            100);
        session.addTransaction(
            new ProfitTransaction(
                3_000L,
                2_000L,
                TransactionType.CONSUMPTION,
                TrackingContext.GENERIC,
                "Supplies consumed",
                "Goblin",
                true,
                Collections.singletonList(SimulationSupport.cost(379, 1L))),
            100);
        session.close(61_000L);

        CsvExportResult export = SimulationSupport.export(session, output.resolve("general-example"));
        SimulationSupport.check(Files.size(export.getDetailPath()) > 100L, "detail CSV should contain data");
        SimulationSupport.check(Files.size(export.getSummaryPath()) > 100L, "transaction CSV should contain data");
        SimulationSupport.check(Files.size(export.getDiagnosticsPath()) > 100L, "diagnostics CSV should contain data");
        SimulationSupport.check(Files.size(export.getActivitiesPath()) > 50L, "activity CSV should contain data");
    }
}
