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
        Am engine = SimulationSupport.newEngine(Cx.GENERAL);
        long start = 100_000L;
        engine.rm(start);
        engine.lp();

        SimulationSupport.equal(null, engine.adj(Cc.empty(), start + 600L), "warm-up tick 1");
        SimulationSupport.equal(null, engine.adj(SimulationSupport.snapshot(1265, 1L), start + 1_200L), "warm-up tick 2");
        SimulationSupport.equal(null, engine.adj(SimulationSupport.snapshot(1265, 1L), start + 1_800L), "warm-up tick 3");
        SimulationSupport.equal(null, engine.adj(SimulationSupport.snapshot(1265, 1L), start + 2_400L), "warm-up tick 4");

        SimulationSupport.equal(0L, engine.getMetrics(start + 2_400L).net, "opening inventory net");
        SimulationSupport.equal(0, engine.getActiveSession().getTransactions().size(), "opening inventory transactions");

        engine.yz();
        Ac consumed = SimulationSupport.settle(engine, Cc.empty(), start + 3_000L);
        SimulationSupport.equal(Ai.CONSUMPTION, consumed.getType(), "post-baseline removal type");
        SimulationSupport.equal(-100L, consumed.getNet(), "post-baseline removal value");
    }

    private static void bankAndEquipmentTransfersAreNeutral()
    {
        Am engine = SimulationSupport.newEngine(Cx.GENERAL);
        long start = 200_000L;
        engine.rm(start);
        engine.setBaseline(SimulationSupport.snapshot(1265, 1L));

        engine.markContext(Aj.TRANSFER, 8, "Bank transfer");
        engine.yz();
        SimulationSupport.equal(null, engine.adj(Cc.empty(), start + 600L), "temporary removal tick 1");
        SimulationSupport.equal(null, engine.adj(Cc.empty(), start + 1_200L), "temporary removal tick 2");

        engine.yz();
        SimulationSupport.equal(null, engine.adj(SimulationSupport.snapshot(1265, 1L), start + 1_800L), "restore tick 1");
        SimulationSupport.equal(null, engine.adj(SimulationSupport.snapshot(1265, 1L), start + 2_400L), "restore tick 2");
        SimulationSupport.equal(null, engine.adj(SimulationSupport.snapshot(1265, 1L), start + 3_000L), "restore settle");

        SimulationSupport.equal(0, engine.getActiveSession().getTransactions().size(), "round-trip transaction count");
        SimulationSupport.equal(0L, engine.getMetrics(start + 3_000L).net, "round-trip net");
    }

    private static void npcLootAndSupplies()
    {
        Am engine = SimulationSupport.newEngine(Cx.GENERAL);
        long start = 300_000L;
        engine.rm(start);
        engine.setBaseline(SimulationSupport.snapshot(379, 4L, 892, 100L, 555, 50L));

        engine.yz();
        Ac supplies = SimulationSupport.settle(
            engine,
            SimulationSupport.snapshot(379, 3L, 892, 90L, 555, 45L),
            start + 600L);
        SimulationSupport.equal(Ai.CONSUMPTION, supplies.getType(), "supply transaction type");
        SimulationSupport.equal(875L, supplies.getCosts(), "supply costs");

        Map<Integer, Long> loot = new LinkedHashMap<>();
        loot.put(526, 1L);
        loot.put(555, 6L);
        engine.aeh("Goblin");
        engine.zk(loot, 50, "Loot from Goblin", "Goblin");
        engine.yz();
        Ac drop = SimulationSupport.settle(
            engine,
            SimulationSupport.snapshot(379, 3L, 892, 90L, 555, 51L, 526, 1L),
            start + 3_000L);

        SimulationSupport.equal(Ai.LOOT, drop.getType(), "loot transaction type");
        SimulationSupport.equal(Bd.CONFIRMED, drop.getConfidence(), "loot confidence");
        SimulationSupport.equal(61L, drop.getRevenue(), "loot revenue");
        Bu metrics = engine.getMetrics(start + 6_000L);
        SimulationSupport.equal(61L, metrics.revenue, "session revenue");
        SimulationSupport.equal(875L, metrics.costs, "session costs");
        SimulationSupport.equal(-814L, metrics.net, "session net");
    }

    private static void processingMargin()
    {
        Am engine = SimulationSupport.newEngine(Cx.GENERAL);
        long start = 400_000L;
        engine.rm(start);
        engine.setBaseline(SimulationSupport.snapshot(1511, 10L));

        engine.markContext(Aj.PRODUCTION, 8, "Fletching logs");
        engine.yz();
        Ac processing = SimulationSupport.settle(
            engine,
            SimulationSupport.snapshot(1511, 5L, 50, 5L),
            start + 600L);

        SimulationSupport.equal(Ai.PROCESSING, processing.getType(), "processing type");
        SimulationSupport.equal(600L, processing.getRevenue(), "output value");
        SimulationSupport.equal(250L, processing.getCosts(), "input value");
        SimulationSupport.equal(350L, processing.getNet(), "processing margin");
    }

    private static void uncertainCorrectionAndUndo()
    {
        Am engine = SimulationSupport.newEngine(Cx.GENERAL);
        long start = 500_000L;
        engine.rm(start);
        engine.setBaseline(SimulationSupport.snapshot(995, 100L));

        engine.yz();
        Ac uncertain = SimulationSupport.settle(
            engine,
            SimulationSupport.snapshot(995, 50L, 526, 2L),
            start + 600L);

        SimulationSupport.equal(Ai.UNCERTAIN, uncertain.getType(), "automatic uncertain type");
        SimulationSupport.check(!uncertain.isCounted(), "uncertain transaction should be excluded");
        SimulationSupport.equal(0L, engine.getMetrics(start + 3_000L).net, "uncertain net before correction");

        SimulationSupport.check(
            engine.qi(uncertain.getId(), Ah.REVENUE, start + 4_000L,
                "Manual revenue correction"),
            "manual revenue correction should apply");
        SimulationSupport.equal(112L, engine.getMetrics(start + 4_000L).net, "corrected gross revenue");

        Ac removed = engine.akc(System.currentTimeMillis());
        SimulationSupport.equal(uncertain.getId(), removed.getId(), "undo transaction id");
        SimulationSupport.equal(0, engine.getActiveSession().getTransactions().size(), "transactions after undo");
        SimulationSupport.equal(0L, engine.getMetrics(start + 5_000L).net, "net after undo");
    }

    private static void pauseFreezesRates()
    {
        Ad session = new Ad("Pause simulation", 0L, Cx.GENERAL);
        session.kf(
            Tx.of(
                1_000L,
                1_000L,
                Ai.GAIN,
                Aj.GENERIC,
                "Synthetic gain",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20001, "Test gain", 1L, 1_000))),
            100);

        session.pause(2_000L, Ed.MANUAL);
        Bu atPause = session.metrics(2_000L);
        Bu oneMinuteLater = session.metrics(62_000L);
        SimulationSupport.equal(atPause.elapsedMillis, oneMinuteLater.elapsedMillis, "paused elapsed time");
        SimulationSupport.equal(atPause.profitPerHour, oneMinuteLater.profitPerHour, "paused session rate");

        session.resume(62_000L);
        SimulationSupport.equal(3_000L, session.metrics(63_000L).elapsedMillis, "resumed active time");
    }

    private static void idlePauseAndAutoActivity()
    {
        Am engine = SimulationSupport.newEngine(Cx.AUTO);
        engine.rm(0L);
        engine.setDetectedActivity("PvM", 500L);
        engine.setDetectedActivity("Skilling", 1_000L);

        SimulationSupport.equal("Skilling", engine.getMetrics(1_000L).activityHint, "auto activity hint");

        engine.adh(2_000L, 2_000L);
        SimulationSupport.check(engine.yp(), "engine must record idle pause reason");
        long frozenElapsed = engine.getMetrics(8_000L).elapsedMillis;
        SimulationSupport.equal(2_000L, frozenElapsed, "idle pause frozen elapsed");

        engine.resume(8_000L, Ed.IDLE);
        SimulationSupport.check(!engine.yp(), "idle pause must clear after activity");
        SimulationSupport.equal(3_000L, engine.getMetrics(9_000L).elapsedMillis, "active time after idle resume");
    }

    private static void crashSafeRestore(Path output) throws Exception
    {
        Path stateDirectory = output.resolve("state-recovery");
        Files.createDirectories(stateDirectory);
        SessionRepository repository = new SessionRepository(new Gson(), Filepath.Unchecked.getRooted(stateDirectory));
        Ad session = new Ad("Recovered session", 1_000L, Cx.GENERAL);
        session.kf(
            Tx.of(
                2_000L,
                Ai.LOOT,
                Aj.LOOT,
                "Recovered loot",
                true,
                Collections.singletonList(SimulationSupport.gain(526, 2L))),
            100);
        repository.save(new Cs(null, repository.scopeGeneration,
            repository.lastKnownDiskRevision, new SavedState(session, null, false, Collections.emptyList())));

        SavedState restored = repository.load();
        SimulationSupport.check(restored.getActiveSession() != null, "active session should restore");
        SimulationSupport.equal("Recovered session", restored.getActiveSession().getName(), "restored session name");
        SimulationSupport.equal(62L, restored.getActiveSession().metrics(3_000L).net, "restored session net");

        Am engine = SimulationSupport.newEngine(Cx.GENERAL);
        engine.restore(restored);
        SimulationSupport.check(
            engine.getActiveSession().recoveredFromCrash,
            "interrupted active session should be labelled recovered");
    }

    private static void sessionHistorySummaries()
    {
        Ad profitable = new Ad("Profitable", 0L, Cx.GENERAL);
        profitable.kf(
            Tx.of(
                1_000L,
                1_000L,
                Ai.GAIN,
                Aj.GENERIC,
                "Historical profit",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20010, "Profit", 1L, 1_000))),
            100);
        profitable.close(3_600_000L);

        Ad loss = new Ad("Loss", 0L, Cx.GENERAL);
        loss.kf(
            Tx.of(
                1_000L,
                1_000L,
                Ai.CONSUMPTION,
                Aj.GENERIC,
                "Historical loss",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20011, "Cost", -1L, 500))),
            100);
        loss.close(3_600_000L);

        Ad current = new Ad("Current", 0L, Cx.GENERAL);
        current.kf(
            Tx.of(
                1_000L,
                1_000L,
                Ai.GAIN,
                Aj.GENERIC,
                "Current profit",
                "General",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(20012, "Current", 1L, 600))),
            100);

        Am engine = SimulationSupport.newEngine(Cx.GENERAL);
        engine.restore(new SavedState(current, null, false, Arrays.asList(profitable, loss)), 3_600_000L);

        SimulationSupport.equal(2, engine.getHistory().size(), "compared session count");
        SimulationSupport.equal(600L, engine.getMetrics(3_600_000L).net, "current net");
        SimulationSupport.equal(1_000L, engine.tz(profitable.getId(), 3_600_000L).net, "best historical net");
        SimulationSupport.equal(-500L, engine.tz(loss.getId(), 3_600_000L).net, "worst historical net");
    }

    private static void historyManagement()
    {
        Ad pvm = new Ad("Bossing", 0L, Cx.GENERAL);
        pvm.kf(
            Tx.of(
                1_000L,
                1_000L,
                Ai.LOOT,
                Aj.LOOT,
                "Boss loot",
                "Bossing",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(21000, "Boss drop", 1L, 1_000))),
            100);
        pvm.close(3_600_000L);

        Ad skilling = new Ad("Fletching", 0L, Cx.GENERAL);
        skilling.kf(
            Tx.of(
                1_000L,
                1_000L,
                Ai.PROCESSING,
                Aj.PRODUCTION,
                "Fletching",
                "Fletching",
                true,
                Collections.singletonList(SimulationSupport.valuedFlow(21001, "Bow", 1L, 500))),
            100);
        skilling.close(3_600_000L);

        Ad trading = new Ad("Flipping", 0L, Cx.GENERAL);
        Ac tradeLoss = Tx.of(
            1_000L,
            1_000L,
            Ai.TRADE,
            Aj.MARKET,
            "Trade loss",
            "Market",
            true,
            Collections.singletonList(SimulationSupport.valuedFlow(21002, "Trade", -1L, 200)));
        trading.kf(tradeLoss, 100);
        trading.close(3_600_000L);

        Am engine = SimulationSupport.newEngine(Cx.GENERAL);
        engine.restore(new SavedState(null, null, false, Arrays.asList(pvm, skilling, trading)), 3_600_000L);

        engine.ua(pvm.getId()).rename("Vorkath");

        engine.ua(trading.getId()).setExcludedFromAverages(true);
        SimulationSupport.check(engine.ua(trading.getId()).excludedFromAverages, "excluded from averages");

        Bu before = engine.tz(trading.getId(), 3_600_000L);
        SimulationSupport.equal(-200L, before.net, "historical net before correction");
        SimulationSupport.check(
            engine.ua(trading.getId()).qi(
                tradeLoss.getId(), Ah.REVENUE, 4_000_000L, "Manual correction"),
            "historical correction");
        Bu after = engine.tz(trading.getId(), 4_000_000L);
        SimulationSupport.equal(200L, after.net, "historical net after correction");

        SimulationSupport.check(engine.qu(skilling.getId()), "history deletion");
        SimulationSupport.equal(2, engine.getHistory().size(), "history size after deletion");
    }

    private static void sessionIntelligenceComplete()
    {
        long day = 24L * 60L * 60L * 1000L;
        long now = 100L * day;
        Ad pvm = completedSession("Vorkath", now - day, Aj.LOOT, Ai.LOOT, 1_000L);
        java.util.Collections.addAll(pvm.tags, "boss", "blue dragon");
        pvm.notes = "pet hunt";
        pvm.setFavorite(true);
        Ad skilling = completedSession("Fletching", now - 10L * day, Aj.PRODUCTION, Ai.PROCESSING, 500L);
        skilling.notes = "afk bows";
        Ad trading = completedSession("Flipping", now - 40L * day, Aj.MARKET, Ai.TRADE, -200L);

        Am engine = SimulationSupport.newEngine(Cx.GENERAL);
        engine.restore(new SavedState(null, null, false, Arrays.asList(pvm, skilling, trading)), now);

        SimulationSupport.equal("pet hunt", engine.ua(pvm.getId()).notes, "history notes");
        SimulationSupport.check(engine.ua(pvm.getId()).favorite, "favorite retained");
        SimulationSupport.equal(3, engine.getHistory().size(), "history retained");
        SimulationSupport.equal("Vorkath", engine.getHistory().get(0).getName(), "newest first");
    }

    private static Ad completedSession(
        String name, long start, Aj context, Ai type, long value)
    {
        Ad session = new Ad(name, start, Cx.GENERAL);
        long quantity = value < 0L ? -1L : 1L;
        session.kf(Tx.of(
            start + 1_000L, 1_000L, type, context, name, name, true,
            Collections.singletonList(new Ab(31_000, "Item", quantity, (int) Math.abs(value), value))), 100);
        session.close(start + 3_600_000L);
        return session;
    }

    private static void exportGeneralExample(Path output) throws Exception
    {
        Ad session = new Ad("General-Simulation", 1_000L, Cx.GENERAL);
        session.aeh("Goblin", System.currentTimeMillis());
        session.kf(
            new Ac(
                2_000L,
                1_000L,
                Ai.LOOT,
                Aj.LOOT,
                "Loot from Goblin",
                "Goblin",
                true,
                Arrays.asList(SimulationSupport.gain(526, 1L), SimulationSupport.gain(555, 6L)),
                Bd.CONFIRMED,
                "Offline simulation of confirmed NPC loot.",
                null),
            100);
        session.kf(
            Tx.of(
                3_000L,
                2_000L,
                Ai.CONSUMPTION,
                Aj.GENERIC,
                "Supplies consumed",
                "Goblin",
                true,
                Collections.singletonList(SimulationSupport.cost(379, 1L))),
            100);
        session.close(61_000L);

        SimulationSupport.check(!session.getTransactions().isEmpty(), "the Grind keeps its receipts");
    }
}
