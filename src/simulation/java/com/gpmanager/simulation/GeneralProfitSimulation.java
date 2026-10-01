package com.gpmanager;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.client.util.Filepath;

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
        suite.run("Session history summaries preserve financial totals", GeneralProfitSimulation::sessionHistorySummaries);
        suite.run("History tags, exclusions, corrections, and deletion", GeneralProfitSimulation::historyManagement);
        suite.run("History search, favorites, notes, dates, and sorting", GeneralProfitSimulation::sessionIntelligenceComplete);
        suite.run("General simulation exports readable CSV files", () -> exportGeneralExample(output));

        suite.finish();
    }

    private static void openingInventoryIsNotProfit()
    {
        Engine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        long start = 100_000L;
        engine.ensureSession(start);
        engine.beginBaselinePriming();

        SimulationSupport.equal(null, engine.processIfDirty(ContainerSnapshot.empty(), start + 600L), "warm-up tick 1");
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 1_200L), "warm-up tick 2");
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 1_800L), "warm-up tick 3");
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 2_400L), "warm-up tick 4");

        SimulationSupport.equal(0L, engine.getMetrics(start + 2_400L).net, "opening inventory net");
        SimulationSupport.equal(0, engine.getActiveSession().getTransactions().size(), "opening inventory transactions");

        engine.markInventoryDirty();
        Transaction consumed = SimulationSupport.settle(engine, ContainerSnapshot.empty(), start + 3_000L);
        SimulationSupport.equal(TransactionType.CONSUMPTION, consumed.getType(), "post-baseline removal type");
        SimulationSupport.equal(-100L, consumed.getNet(), "post-baseline removal value");
    }

    private static void bankAndEquipmentTransfersAreNeutral()
    {
        Engine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        long start = 200_000L;
        engine.ensureSession(start);
        engine.setBaseline(SimulationSupport.snapshot(1265, 1L));

        engine.markContext(Context.TRANSFER, 8, "Bank transfer");
        engine.markInventoryDirty();
        SimulationSupport.equal(null, engine.processIfDirty(ContainerSnapshot.empty(), start + 600L), "temporary removal tick 1");
        SimulationSupport.equal(null, engine.processIfDirty(ContainerSnapshot.empty(), start + 1_200L), "temporary removal tick 2");

        engine.markInventoryDirty();
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 1_800L), "restore tick 1");
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 2_400L), "restore tick 2");
        SimulationSupport.equal(null, engine.processIfDirty(SimulationSupport.snapshot(1265, 1L), start + 3_000L), "restore settle");

        SimulationSupport.equal(0, engine.getActiveSession().getTransactions().size(), "round-trip transaction count");
        SimulationSupport.equal(0L, engine.getMetrics(start + 3_000L).net, "round-trip net");
    }

    private static void npcLootAndSupplies()
    {
        Engine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        long start = 300_000L;
        engine.ensureSession(start);
        engine.setBaseline(SimulationSupport.snapshot(379, 4L, 892, 100L, 555, 50L));

        engine.markInventoryDirty();
        Transaction supplies = SimulationSupport.settle(
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
        Transaction drop = SimulationSupport.settle(
            engine,
            SimulationSupport.snapshot(379, 3L, 892, 90L, 555, 51L, 526, 1L),
            start + 3_000L);

        SimulationSupport.equal(TransactionType.LOOT, drop.getType(), "loot transaction type");
        SimulationSupport.equal(ClassificationConfidence.CONFIRMED, drop.getConfidence(), "loot confidence");
        SimulationSupport.equal(61L, drop.getRevenue(), "loot revenue");
        SessionMetrics metrics = engine.getMetrics(start + 6_000L);
        SimulationSupport.equal(61L, metrics.revenue, "session revenue");
        SimulationSupport.equal(875L, metrics.costs, "session costs");
        SimulationSupport.equal(-814L, metrics.net, "session net");
    }

    private static void processingMargin()
    {
        Engine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        long start = 400_000L;
        engine.ensureSession(start);
        engine.setBaseline(SimulationSupport.snapshot(1511, 10L));

        engine.markContext(Context.PRODUCTION, 8, "Fletching logs");
        engine.markInventoryDirty();
        Transaction processing = SimulationSupport.settle(
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
        Engine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        long start = 500_000L;
        engine.ensureSession(start);
        engine.setBaseline(SimulationSupport.snapshot(995, 100L));

        engine.markInventoryDirty();
        Transaction uncertain = SimulationSupport.settle(
            engine,
            SimulationSupport.snapshot(995, 50L, 526, 2L),
            start + 600L);

        SimulationSupport.equal(TransactionType.UNCERTAIN, uncertain.getType(), "automatic uncertain type");
        SimulationSupport.check(!uncertain.isCounted(), "uncertain transaction should be excluded");
        SimulationSupport.equal(0L, engine.getMetrics(start + 3_000L).net, "uncertain net before correction");

        SimulationSupport.check(
            engine.correctTransaction(uncertain.getId(), Correction.REVENUE, start + 4_000L,
                "Manual revenue correction"),
            "manual revenue correction should apply");
        SimulationSupport.equal(112L, engine.getMetrics(start + 4_000L).net, "corrected gross revenue");

        Transaction removed = engine.undoLastTransaction(System.currentTimeMillis());
        SimulationSupport.equal(uncertain.getId(), removed.getId(), "undo transaction id");
        SimulationSupport.equal(0, engine.getActiveSession().getTransactions().size(), "transactions after undo");
        SimulationSupport.equal(0L, engine.getMetrics(start + 5_000L).net, "net after undo");
    }

    private static void pauseFreezesRates()
    {
        Session session = new Session("Pause simulation", 0L, SessionMode.GENERAL);
        session.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                Context.GENERIC,
                "Synthetic gain",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20001, "Test gain", 1L, 1_000))),
            100);

        session.pause(2_000L, PauseReason.MANUAL);
        SessionMetrics atPause = session.metrics(2_000L);
        SessionMetrics oneMinuteLater = session.metrics(62_000L);
        SimulationSupport.equal(atPause.elapsedMillis, oneMinuteLater.elapsedMillis, "paused elapsed time");
        SimulationSupport.equal(atPause.profitPerHour, oneMinuteLater.profitPerHour, "paused session rate");

        session.resume(62_000L);
        SimulationSupport.equal(3_000L, session.metrics(63_000L).elapsedMillis, "resumed active time");
    }

    private static void idlePauseAndAutoActivity()
    {
        Engine engine = SimulationSupport.newEngine(SessionMode.AUTO);
        engine.ensureSession(0L);
        engine.setDetectedActivity("PvM", 500L);
        engine.setDetectedActivity("Skilling", 1_000L);

        SimulationSupport.equal("Skilling", engine.getMetrics(1_000L).activityHint, "auto activity hint");

        engine.pauseForIdle(2_000L, 2_000L);
        SimulationSupport.check(engine.isIdlePaused(), "engine must record idle pause reason");
        long frozenElapsed = engine.getMetrics(8_000L).elapsedMillis;
        SimulationSupport.equal(2_000L, frozenElapsed, "idle pause frozen elapsed");

        engine.resume(8_000L, PauseReason.IDLE);
        SimulationSupport.check(!engine.isIdlePaused(), "idle pause must clear after activity");
        SimulationSupport.equal(3_000L, engine.getMetrics(9_000L).elapsedMillis, "active time after idle resume");
    }

    private static void crashSafeRestore(Path output) throws Exception
    {
        Path stateDirectory = output.resolve("state-recovery");
        Files.createDirectories(stateDirectory);
        SessionRepository repository = new SessionRepository(new Gson(), Filepath.Unchecked.getRooted(stateDirectory));
        Session session = new Session("Recovered session", 1_000L, SessionMode.GENERAL);
        session.addTransaction(
            Tx.of(
                2_000L,
                TransactionType.LOOT,
                Context.LOOT,
                "Recovered loot",
                true,
                Collections.singletonList(SimulationSupport.gain(526, 2L))),
            100);
        repository.save(new WriteIntent(null, repository.scopeGeneration,
            repository.lastKnownDiskRevision, new SavedState(session, null, false, Collections.emptyList())));

        SavedState restored = repository.load();
        SimulationSupport.check(restored.getActiveSession() != null, "active session should restore");
        SimulationSupport.equal("Recovered session", restored.getActiveSession().getName(), "restored session name");
        SimulationSupport.equal(62L, restored.getActiveSession().metrics(3_000L).net, "restored session net");

        Engine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        engine.restore(restored);
        SimulationSupport.check(
            engine.getActiveSession().recoveredFromCrash,
            "interrupted active session should be labelled recovered");
    }

    private static void sessionHistorySummaries()
    {
        Session profitable = new Session("Profitable", 0L, SessionMode.GENERAL);
        profitable.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                Context.GENERIC,
                "Historical profit",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20010, "Profit", 1L, 1_000))),
            100);
        profitable.close(3_600_000L);

        Session loss = new Session("Loss", 0L, SessionMode.GENERAL);
        loss.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.CONSUMPTION,
                Context.GENERIC,
                "Historical loss",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20011, "Cost", -1L, 500))),
            100);
        loss.close(3_600_000L);

        Session current = new Session("Current", 0L, SessionMode.GENERAL);
        current.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.GAIN,
                Context.GENERIC,
                "Current profit",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20012, "Current", 1L, 600))),
            100);

        Engine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        engine.restore(new SavedState(current, null, false, Arrays.asList(profitable, loss)), 3_600_000L);

        SimulationSupport.equal(2, engine.getHistory().size(), "compared session count");
        SimulationSupport.equal(600L, engine.getMetrics(3_600_000L).net, "current net");
        SimulationSupport.equal(1_000L, engine.getHistoryMetrics(profitable.getId(), 3_600_000L).net, "best historical net");
        SimulationSupport.equal(-500L, engine.getHistoryMetrics(loss.getId(), 3_600_000L).net, "worst historical net");
    }

    private static void historyManagement()
    {
        Session pvm = new Session("Bossing", 0L, SessionMode.GENERAL);
        pvm.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.LOOT,
                Context.LOOT,
                "Boss loot",
                "Bossing",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(21000, "Boss drop", 1L, 1_000))),
            100);
        pvm.close(3_600_000L);

        Session skilling = new Session("Fletching", 0L, SessionMode.GENERAL);
        skilling.addTransaction(
            Tx.of(
                1_000L,
                1_000L,
                TransactionType.PROCESSING,
                Context.PRODUCTION,
                "Fletching",
                "Fletching",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(21001, "Bow", 1L, 500))),
            100);
        skilling.close(3_600_000L);

        Session trading = new Session("Flipping", 0L, SessionMode.GENERAL);
        Transaction tradeLoss = Tx.of(
            1_000L,
            1_000L,
            TransactionType.TRADE,
            Context.MARKET,
            "Trade loss",
            "Market",
            true,
            Collections.singletonList(SimulationSupport.valuedFlow(21002, "Trade", -1L, 200)));
        trading.addTransaction(tradeLoss, 100);
        trading.close(3_600_000L);

        Engine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        engine.restore(new SavedState(null, null, false, Arrays.asList(pvm, skilling, trading)), 3_600_000L);

        engine.getHistorySession(pvm.getId()).rename("Vorkath");

        engine.getHistorySession(trading.getId()).setExcludedFromAverages(true);
        SimulationSupport.check(engine.getHistorySession(trading.getId()).excludedFromAverages, "excluded from averages");

        SessionMetrics before = engine.getHistoryMetrics(trading.getId(), 3_600_000L);
        SimulationSupport.equal(-200L, before.net, "historical net before correction");
        SimulationSupport.check(
            engine.getHistorySession(trading.getId()).correctTransaction(
                tradeLoss.getId(), Correction.REVENUE, 4_000_000L, "Manual correction"),
            "historical correction");
        SessionMetrics after = engine.getHistoryMetrics(trading.getId(), 4_000_000L);
        SimulationSupport.equal(200L, after.net, "historical net after correction");

        SimulationSupport.check(engine.deleteHistorySession(skilling.getId()), "history deletion");
        SimulationSupport.equal(2, engine.getHistory().size(), "history size after deletion");
    }

    private static void sessionIntelligenceComplete()
    {
        long day = 24L * 60L * 60L * 1000L;
        long now = 100L * day;
        Session pvm = completedSession("Vorkath", now - day, Context.LOOT, TransactionType.LOOT, 1_000L);
        java.util.Collections.addAll(pvm.tags, "boss", "blue dragon");
        pvm.notes = "pet hunt";
        pvm.setFavorite(true);
        Session skilling = completedSession("Fletching", now - 10L * day, Context.PRODUCTION, TransactionType.PROCESSING, 500L);
        skilling.notes = "afk bows";
        Session trading = completedSession("Flipping", now - 40L * day, Context.MARKET, TransactionType.TRADE, -200L);

        Engine engine = SimulationSupport.newEngine(SessionMode.GENERAL);
        engine.restore(new SavedState(null, null, false, Arrays.asList(pvm, skilling, trading)), now);

        SimulationSupport.equal("pet hunt", engine.getHistorySession(pvm.getId()).notes, "history notes");
        SimulationSupport.check(engine.getHistorySession(pvm.getId()).favorite, "favorite retained");
        SimulationSupport.equal(3, engine.getHistory().size(), "history retained");
        SimulationSupport.equal("Vorkath", engine.getHistory().get(0).getName(), "newest first");
    }

    private static Session completedSession(
        String name, long start, Context context, TransactionType type, long value)
    {
        Session session = new Session(name, start, SessionMode.GENERAL);
        long quantity = value < 0L ? -1L : 1L;
        session.addTransaction(Tx.of(
            start + 1_000L, 1_000L, type, context, name, name, true,
            Collections.singletonList(new Flow(31_000, "Item", quantity, (int) Math.abs(value), value))), 100);
        session.close(start + 3_600_000L);
        return session;
    }

    private static void exportGeneralExample(Path output) throws Exception
    {
        Session session = new Session("General-Simulation", 1_000L, SessionMode.GENERAL);
        session.recordAction("Goblin", System.currentTimeMillis());
        session.addTransaction(
            new Transaction(
                2_000L,
                1_000L,
                TransactionType.LOOT,
                Context.LOOT,
                "Loot from Goblin",
                "Goblin",
                true,
                Arrays.asList(SimulationSupport.gain(526, 1L), SimulationSupport.gain(555, 6L)),
                ClassificationConfidence.CONFIRMED,
                "Offline simulation of confirmed NPC loot.",
                null),
            100);
        session.addTransaction(
            Tx.of(
                3_000L,
                2_000L,
                TransactionType.CONSUMPTION,
                Context.GENERIC,
                "Supplies consumed",
                "Goblin",
                true,
                Collections.singletonList(SimulationSupport.cost(379, 1L))),
            100);
        session.close(61_000L);

        Filepath export = SimulationSupport.export(session, output.resolve("general-example"));
        SimulationSupport.check(export.size() > 100L, "the Grind CSV should contain data");
    }
}
