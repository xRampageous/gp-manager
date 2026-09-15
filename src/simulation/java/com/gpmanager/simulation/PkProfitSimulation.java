package com.gpmanager.simulation;

import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.PkEncounter;
import com.gpmanager.model.PkEncounterType;
import com.gpmanager.model.PkMetrics;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import com.gpmanager.persistence.CsvExportResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

public final class PkProfitSimulation
{
    private PkProfitSimulation()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Path output = args.length == 0
            ? Paths.get("build", "simulation", "pk")
            : Paths.get(args[0]);
        SimulationSupport.Suite suite = new SimulationSupport.Suite(
            "PK Profit Simulation",
            output);

        suite.run("PK win subtracts supplies from player loot", PkProfitSimulation::pkWin);
        suite.run("PK death includes lost risk, supplies, and fees", PkProfitSimulation::pkDeath);
        suite.run("Loot key lifecycle counts contents exactly once", PkProfitSimulation::lootKeyLifecycle);
        suite.run("Partial loot pickup remains one kill encounter", PkProfitSimulation::partialLootPickup);
        suite.run("Multiple kills remain separate before banking", PkProfitSimulation::multipleKillsBeforeBanking);
        suite.run("Death while carrying prior loot offsets earlier profit", PkProfitSimulation::deathWithPriorLoot);
        suite.run("Uncertain PK transaction supports correction and undo", PkProfitSimulation::uncertainCorrectionAndUndo);
        suite.run("PK simulation exports encounter and diagnostic CSV files", () -> exportPkExample(output));

        suite.finish();
    }

    private static void pkWin()
    {
        ProfitSession session = new ProfitSession("PK win", 0L, SessionMode.PK);
        ProfitTransaction supplies = transaction(
            1_000L,
            TransactionType.CONSUMPTION,
            TrackingContext.GENERIC,
            "PK supplies",
            Arrays.asList(
                SimulationSupport.cost(379, 40L),
                SimulationSupport.cost(385, 20L),
                SimulationSupport.cost(561, 50L),
                SimulationSupport.cost(555, 1_000L)));
        session.addTransaction(supplies, 100);

        PkEncounter kill = session.addPkEncounter(
            PkEncounterType.KILL,
            10_000L,
            "Player kill",
            ClassificationConfidence.CONFIRMED,
            "Offline simulation of RuneLite player-loot accounting.");
        session.attachRecentCostsToEncounter(kill.getId(), 10_000L, 90_000L);

        ProfitTransaction loot = transaction(
            11_000L,
            TransactionType.PK_LOOT,
            TrackingContext.PK_LOOT,
            "PK loot",
            Collections.singletonList(SimulationSupport.gain(10001, 1L)));
        session.addTransaction(loot, 100);
        session.attachTransactionToEncounter(loot.getId(), kill.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        SimulationSupport.equal(1, metrics.getKills(), "kill count");
        SimulationSupport.equal(42_000L, metrics.getCosts(), "PK supply cost");
        SimulationSupport.equal(780_000L, metrics.getRevenue(), "PK loot revenue");
        SimulationSupport.equal(738_000L, metrics.getNet(), "PK win net");
        SimulationSupport.equal(1, metrics.getEncounterCount(), "PK win encounter count");
        SimulationSupport.equal(738_000L, metrics.getProfitPerKill(), "profit per kill");
        SimulationSupport.equal(738_000L, metrics.getNetPerEncounter(), "net per encounter");
        SimulationSupport.equal(TransactionType.PK_SUPPLY_COST, supplies.getAutomaticType(), "retrospective supply type");
    }

    private static void pkDeath()
    {
        ProfitSession session = new ProfitSession("PK death", 0L, SessionMode.PK);
        PkEncounter death = session.addPkEncounter(
            PkEncounterType.DEATH,
            20_000L,
            "Player death",
            ClassificationConfidence.CONFIRMED,
            "Offline simulation of a confirmed PK death.");

        ProfitTransaction supplies = transaction(
            18_000L,
            TransactionType.PK_SUPPLY_COST,
            TrackingContext.PK_DEATH,
            "Supplies consumed before death",
            Collections.singletonList(SimulationSupport.valuedFlow(11001, "PK supplies", -1L, 38_000)));
        ProfitTransaction risk = transaction(
            20_000L,
            TransactionType.PK_DEATH_LOSS,
            TrackingContext.PK_DEATH,
            "Unprotected items lost",
            Collections.singletonList(SimulationSupport.cost(10002, 1L)));
        ProfitTransaction fee = transaction(
            20_500L,
            TransactionType.PK_FEE,
            TrackingContext.PK_DEATH,
            "PK fee",
            Collections.singletonList(SimulationSupport.cost(10003, 1L)));

        session.addTransaction(supplies, 100);
        session.addTransaction(risk, 100);
        session.addTransaction(fee, 100);
        session.attachTransactionToEncounter(supplies.getId(), death.getId(), false);
        session.attachTransactionToEncounter(risk.getId(), death.getId(), false);
        session.attachTransactionToEncounter(fee.getId(), death.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        SimulationSupport.equal(1, metrics.getDeaths(), "death count");
        SimulationSupport.equal(663_000L, metrics.getCosts(), "death costs");
        SimulationSupport.equal(-663_000L, metrics.getNet(), "death net");
        SimulationSupport.equal(663_000L, metrics.getLargestDeathLoss(), "largest death loss");
        SimulationSupport.equal(663_000L, metrics.getLossPerDeath(), "loss per death");
        SimulationSupport.equal(-663_000L, metrics.getNetPerEncounter(), "death net per encounter");
    }

    private static void lootKeyLifecycle()
    {
        ProfitSession session = new ProfitSession("Loot key", 0L, SessionMode.PK);
        PkEncounter kill = session.addPkEncounter(
            PkEncounterType.KILL,
            10_000L,
            "Player kill - loot key",
            ClassificationConfidence.CONFIRMED,
            "Loot-key receipt is retained as a non-counted audit row until opened.");

        ProfitTransaction keyReceipt = new ProfitTransaction(
            10_000L,
            10_000L,
            TransactionType.TRANSFER,
            TrackingContext.PK_LOOT,
            "Loot key received - value deferred",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.gain(11941, 1L)));
        session.addTransaction(keyReceipt, 100);
        session.attachTransactionToEncounter(keyReceipt.getId(), kill.getId(), false);

        ProfitTransaction contents = transaction(
            30_000L,
            TransactionType.PK_LOOT,
            TrackingContext.PK_LOOT,
            "Loot key opened",
            Collections.singletonList(SimulationSupport.gain(10001, 1L)));
        session.addTransaction(contents, 100);
        session.attachTransactionToEncounter(contents.getId(), kill.getId(), false);

        ProfitTransaction keyRemoval = new ProfitTransaction(
            30_000L,
            30_000L,
            TransactionType.TRANSFER,
            TrackingContext.PK_LOOT,
            "Loot key consumed - value already deferred",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.cost(11941, 1L)));
        session.addTransaction(keyRemoval, 100);
        session.attachTransactionToEncounter(keyRemoval.getId(), kill.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        SimulationSupport.equal(780_000L, metrics.getRevenue(), "loot-key contents revenue");
        SimulationSupport.equal(0L, metrics.getCosts(), "loot-key deferred audit costs");
        SimulationSupport.equal(780_000L, metrics.getNet(), "loot-key net counted once");
    }

    private static void partialLootPickup()
    {
        ProfitSession session = new ProfitSession("Partial pickup", 0L, SessionMode.PK);
        PkEncounter kill = session.addPkEncounter(
            PkEncounterType.KILL,
            10_000L,
            "Player kill",
            ClassificationConfidence.CONFIRMED,
            "One player-loot encounter with items collected in separate inventory changes.");

        ProfitTransaction firstPickup = transaction(
            11_000L,
            TransactionType.PK_LOOT,
            TrackingContext.PK_LOOT,
            "Partial PK loot 1",
            Collections.singletonList(SimulationSupport.valuedFlow(12001, "First pickup", 1L, 300_000)));
        ProfitTransaction secondPickup = transaction(
            14_000L,
            TransactionType.PK_LOOT,
            TrackingContext.PK_LOOT,
            "Partial PK loot 2",
            Collections.singletonList(SimulationSupport.valuedFlow(12002, "Second pickup", 1L, 480_000)));
        session.addTransaction(firstPickup, 100);
        session.addTransaction(secondPickup, 100);
        session.attachTransactionToEncounter(firstPickup.getId(), kill.getId(), false);
        session.attachTransactionToEncounter(secondPickup.getId(), kill.getId(), false);

        SimulationSupport.equal(1, session.pkMetrics().getKills(), "partial-pickup kill count");
        SimulationSupport.equal(780_000L, session.pkMetrics().getNet(), "partial-pickup total");
        SimulationSupport.equal(2, kill.getTransactionIds().size(), "partial-pickup transaction count");
    }

    private static void multipleKillsBeforeBanking()
    {
        ProfitSession session = new ProfitSession("Multi-kill", 0L, SessionMode.PK);
        PkEncounter first = session.addPkEncounter(
            PkEncounterType.KILL, 10_000L, "Player kill 1",
            ClassificationConfidence.CONFIRMED, "First simulated kill.");
        PkEncounter second = session.addPkEncounter(
            PkEncounterType.KILL, 20_000L, "Player kill 2",
            ClassificationConfidence.CONFIRMED, "Second simulated kill.");
        ProfitTransaction firstLoot = transaction(
            11_000L, TransactionType.PK_LOOT, TrackingContext.PK_LOOT,
            "First kill loot", Collections.singletonList(SimulationSupport.gain(10004, 1L)));
        ProfitTransaction secondLoot = transaction(
            21_000L, TransactionType.PK_LOOT, TrackingContext.PK_LOOT,
            "Second kill loot", Collections.singletonList(SimulationSupport.gain(10005, 1L)));
        ProfitTransaction bank = new ProfitTransaction(
            30_000L, TransactionType.TRANSFER, TrackingContext.TRANSFER,
            "Banked PK loot", false,
            Arrays.asList(SimulationSupport.cost(10004, 1L), SimulationSupport.cost(10005, 1L)));
        session.addTransaction(firstLoot, 100);
        session.addTransaction(secondLoot, 100);
        session.addTransaction(bank, 100);
        session.attachTransactionToEncounter(firstLoot.getId(), first.getId(), false);
        session.attachTransactionToEncounter(secondLoot.getId(), second.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        SimulationSupport.equal(2, metrics.getKills(), "multi-kill count");
        SimulationSupport.equal(820_000L, metrics.getNet(), "multi-kill net");
        SimulationSupport.equal(820_000L, session.metrics(60_000L, 60_000L).getNet(), "banking must not change session net");
    }

    private static void deathWithPriorLoot()
    {
        ProfitSession session = new ProfitSession("Carry then die", 0L, SessionMode.PK);
        PkEncounter kill = session.addPkEncounter(
            PkEncounterType.KILL, 10_000L, "Player kill",
            ClassificationConfidence.CONFIRMED, "Prior loot gained.");
        ProfitTransaction loot = transaction(
            11_000L, TransactionType.PK_LOOT, TrackingContext.PK_LOOT,
            "Carried PK loot", Collections.singletonList(SimulationSupport.gain(10004, 1L)));
        session.addTransaction(loot, 100);
        session.attachTransactionToEncounter(loot.getId(), kill.getId(), false);

        PkEncounter death = session.addPkEncounter(
            PkEncounterType.DEATH, 20_000L, "Player death",
            ClassificationConfidence.CONFIRMED, "Previously gained loot was lost.");
        ProfitTransaction loss = transaction(
            20_000L, TransactionType.PK_DEATH_LOSS, TrackingContext.PK_DEATH,
            "Carried PK loot lost", Collections.singletonList(SimulationSupport.cost(10004, 1L)));
        session.addTransaction(loss, 100);
        session.attachTransactionToEncounter(loss.getId(), death.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        SimulationSupport.equal(1, metrics.getKills(), "carry-death kills");
        SimulationSupport.equal(1, metrics.getDeaths(), "carry-death deaths");
        SimulationSupport.equal(-1, metrics.getCurrentStreak(), "one death forms a loss streak");
        SimulationSupport.equal(0L, metrics.getNet(), "carried loot gain and loss cancel");
        SimulationSupport.equal(500_000L, metrics.getLargestDeathLoss(), "carried loot death loss");
    }

    private static void uncertainCorrectionAndUndo()
    {
        ProfitSession session = new ProfitSession("Uncertain PK", 0L, SessionMode.PK);
        PkEncounter kill = session.addPkEncounter(
            PkEncounterType.KILL, 10_000L, "Uncertain player loot",
            ClassificationConfidence.UNCERTAIN, "Synthetic ambiguous PK inventory change.");
        ProfitTransaction uncertain = new ProfitTransaction(
            11_000L,
            11_000L,
            TransactionType.UNCERTAIN,
            TrackingContext.GENERIC,
            "Ambiguous PK change",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.valuedFlow(13001, "Ambiguous loot", 1L, 200_000)),
            ClassificationConfidence.UNCERTAIN,
            "No confirmed player-loot correlation was available.",
            kill.getId());
        session.addTransaction(uncertain, 100);
        session.attachTransactionToEncounter(uncertain.getId(), kill.getId(), false);
        SimulationSupport.equal(0L, session.pkMetrics().getNet(), "uncertain PK net before correction");

        SimulationSupport.check(
            session.correctTransaction(uncertain.getId(), TransactionCorrection.REVENUE, 12_000L),
            "PK correction should apply");
        SimulationSupport.equal(200_000L, session.pkMetrics().getNet(), "corrected PK revenue");

        ProfitTransaction removed = session.undoLastTransaction();
        SimulationSupport.equal(uncertain.getId(), removed.getId(), "PK undo transaction id");
        SimulationSupport.equal(0L, session.pkMetrics().getNet(), "PK net after undo");
        SimulationSupport.equal(0, kill.getTransactionIds().size(), "encounter references after undo");
    }

    private static void exportPkExample(Path output) throws Exception
    {
        ProfitSession session = new ProfitSession("PK-Simulation", 1_000L, SessionMode.PK);
        PkEncounter kill = session.addPkEncounter(
            PkEncounterType.KILL, 10_000L, "Player kill",
            ClassificationConfidence.CONFIRMED, "Offline example kill.");
        ProfitTransaction loot = transaction(
            11_000L, TransactionType.PK_LOOT, TrackingContext.PK_LOOT,
            "PK loot", Collections.singletonList(SimulationSupport.gain(10004, 1L)));
        session.addTransaction(loot, 100);
        session.attachTransactionToEncounter(loot.getId(), kill.getId(), false);

        PkEncounter death = session.addPkEncounter(
            PkEncounterType.DEATH, 20_000L, "Player death",
            ClassificationConfidence.CONFIRMED, "Offline example death.");
        ProfitTransaction loss = transaction(
            20_000L, TransactionType.PK_DEATH_LOSS, TrackingContext.PK_DEATH,
            "Risk lost", Collections.singletonList(SimulationSupport.cost(10002, 1L)));
        session.addTransaction(loss, 100);
        session.attachTransactionToEncounter(loss.getId(), death.getId(), false);
        session.close(61_000L);

        CsvExportResult export = SimulationSupport.export(session, output.resolve("pk-example"));
        SimulationSupport.check(export.getPkEncountersPath() != null, "PK encounter export path");
        SimulationSupport.check(Files.size(export.getPkEncountersPath()) > 100L, "PK encounter CSV should contain data");
        SimulationSupport.check(Files.size(export.getDiagnosticsPath()) > 100L, "PK diagnostics CSV should contain data");
        long lines;
        try (java.util.stream.Stream<String> stream = Files.lines(export.getPkEncountersPath()))
        {
            lines = stream.count();
        }
        SimulationSupport.equal(3L, lines, "PK encounter CSV rows including header");

        String diagnostics = new String(Files.readAllBytes(export.getDiagnosticsPath()), java.nio.charset.StandardCharsets.UTF_8);
        SimulationSupport.check(diagnostics.contains(",PKing,"), "PK diagnostics activity hint");
        SimulationSupport.check(diagnostics.contains("pk_profit_per_kill"), "PK diagnostics profit-per-kill column");
        SimulationSupport.check(diagnostics.contains("pk_loss_per_death"), "PK diagnostics loss-per-death column");
        SimulationSupport.check(diagnostics.contains("pk_net_per_encounter"), "PK diagnostics net-per-encounter column");

        String activities = new String(Files.readAllBytes(export.getActivitiesPath()), java.nio.charset.StandardCharsets.UTF_8);
        SimulationSupport.check(activities.contains(",PKing,2,"), "PK activity action count should equal encounters");
    }

    private static ProfitTransaction transaction(
        long timestamp,
        TransactionType type,
        TrackingContext context,
        String note,
        java.util.List<com.gpmanager.model.ItemFlow> flows)
    {
        return new ProfitTransaction(
            timestamp,
            timestamp,
            type,
            context,
            note,
            "PKing",
            true,
            flows,
            ClassificationConfidence.CONFIRMED,
            "Offline simulation input; no RuneLite client or game event was used.",
            null);
    }
}
