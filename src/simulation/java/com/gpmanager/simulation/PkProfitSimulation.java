package com.gpmanager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import net.runelite.client.util.Filepath;

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
        suite.run("PK simulation exports encounter CSV files", () -> exportPkExample(output));

        suite.finish();
    }

    private static void pkWin()
    {
        Session session = new Session("PK win", 0L, SessionMode.PK);
        Transaction supplies = transaction(
            1_000L,
            TransactionType.CONSUMPTION,
            Context.GENERIC,
            "PK supplies",
            Arrays.asList(
                SimulationSupport.cost(379, 40L),
                SimulationSupport.cost(385, 20L),
                SimulationSupport.cost(561, 50L),
                SimulationSupport.cost(555, 1_000L)));
        session.addTransaction(supplies, 100);

        PkEncounter kill = session.addPkEncounter(
            EncounterType.KILL,
            10_000L,
            "Player kill",
            ClassificationConfidence.CONFIRMED,
            "Offline simulation of RuneLite player-loot accounting.");
        session.attachRecentCostsToEncounter(kill.getId(), 10_000L, 90_000L);

        Transaction loot = transaction(
            11_000L,
            TransactionType.PK_LOOT,
            Context.PK_LOOT,
            "PK loot",
            Collections.singletonList(SimulationSupport.gain(10001, 1L)));
        session.addTransaction(loot, 100);
        session.attachTransactionToEncounter(loot.getId(), kill.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        SimulationSupport.equal(1, metrics.kills, "kill count");
        SimulationSupport.equal(42_000L, metrics.costs, "PK supply cost");
        SimulationSupport.equal(780_000L, metrics.revenue, "PK loot revenue");
        SimulationSupport.equal(738_000L, metrics.net, "PK win net");
        SimulationSupport.equal(1, metrics.getEncounterCount(), "PK win encounter count");
        SimulationSupport.equal(738_000L, metrics.totalKillNet / metrics.kills, "profit per kill");
        SimulationSupport.equal(738_000L, metrics.net / metrics.getEncounterCount(), "net per encounter");
        SimulationSupport.equal(TransactionType.PK_SUPPLY_COST, supplies.getAutomaticType(), "retrospective supply type");
    }

    private static void pkDeath()
    {
        Session session = new Session("PK death", 0L, SessionMode.PK);
        PkEncounter death = session.addPkEncounter(
            EncounterType.DEATH,
            20_000L,
            "Player death",
            ClassificationConfidence.CONFIRMED,
            "Offline simulation of a confirmed PK death.");

        Transaction supplies = transaction(
            18_000L,
            TransactionType.PK_SUPPLY_COST,
            Context.PK_DEATH,
            "Supplies consumed before death",
            Collections.singletonList(SimulationSupport.valuedFlow(11001, "PK supplies", -1L, 38_000)));
        Transaction risk = transaction(
            20_000L,
            TransactionType.PK_DEATH_LOSS,
            Context.PK_DEATH,
            "Unprotected items lost",
            Collections.singletonList(SimulationSupport.cost(10002, 1L)));
        Transaction fee = transaction(
            20_500L,
            TransactionType.PK_FEE,
            Context.PK_DEATH,
            "PK fee",
            Collections.singletonList(SimulationSupport.cost(10003, 1L)));

        session.addTransaction(supplies, 100);
        session.addTransaction(risk, 100);
        session.addTransaction(fee, 100);
        session.attachTransactionToEncounter(supplies.getId(), death.getId(), false);
        session.attachTransactionToEncounter(risk.getId(), death.getId(), false);
        session.attachTransactionToEncounter(fee.getId(), death.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        SimulationSupport.equal(1, metrics.deaths, "death count");
        SimulationSupport.equal(663_000L, metrics.costs, "death costs");
        SimulationSupport.equal(-663_000L, metrics.net, "death net");
        SimulationSupport.equal(663_000L, metrics.largestDeathLoss, "largest death loss");
        SimulationSupport.equal(663_000L, metrics.totalDeathLoss / metrics.deaths, "loss per death");
        SimulationSupport.equal(-663_000L, metrics.net / metrics.getEncounterCount(), "death net per encounter");
    }

    private static void lootKeyLifecycle()
    {
        Session session = new Session("Loot key", 0L, SessionMode.PK);
        PkEncounter kill = session.addPkEncounter(
            EncounterType.KILL,
            10_000L,
            "Player kill - loot key",
            ClassificationConfidence.CONFIRMED,
            "Loot-key receipt is retained as a non-counted audit row until opened.");

        Transaction keyReceipt = Tx.of(
            10_000L,
            10_000L,
            TransactionType.TRANSFER,
            Context.PK_LOOT,
            "Loot key received - value deferred",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.gain(11941, 1L)));
        session.addTransaction(keyReceipt, 100);
        session.attachTransactionToEncounter(keyReceipt.getId(), kill.getId(), false);

        Transaction contents = transaction(
            30_000L,
            TransactionType.PK_LOOT,
            Context.PK_LOOT,
            "Loot key opened",
            Collections.singletonList(SimulationSupport.gain(10001, 1L)));
        session.addTransaction(contents, 100);
        session.attachTransactionToEncounter(contents.getId(), kill.getId(), false);

        Transaction keyRemoval = Tx.of(
            30_000L,
            30_000L,
            TransactionType.TRANSFER,
            Context.PK_LOOT,
            "Loot key consumed - value already deferred",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.cost(11941, 1L)));
        session.addTransaction(keyRemoval, 100);
        session.attachTransactionToEncounter(keyRemoval.getId(), kill.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        SimulationSupport.equal(780_000L, metrics.revenue, "loot-key contents revenue");
        SimulationSupport.equal(0L, metrics.costs, "loot-key deferred audit costs");
        SimulationSupport.equal(780_000L, metrics.net, "loot-key net counted once");
    }

    private static void partialLootPickup()
    {
        Session session = new Session("Partial pickup", 0L, SessionMode.PK);
        PkEncounter kill = session.addPkEncounter(
            EncounterType.KILL,
            10_000L,
            "Player kill",
            ClassificationConfidence.CONFIRMED,
            "One player-loot encounter with items collected in separate inventory changes.");

        Transaction firstPickup = transaction(
            11_000L,
            TransactionType.PK_LOOT,
            Context.PK_LOOT,
            "Partial PK loot 1",
            Collections.singletonList(SimulationSupport.valuedFlow(12001, "First pickup", 1L, 300_000)));
        Transaction secondPickup = transaction(
            14_000L,
            TransactionType.PK_LOOT,
            Context.PK_LOOT,
            "Partial PK loot 2",
            Collections.singletonList(SimulationSupport.valuedFlow(12002, "Second pickup", 1L, 480_000)));
        session.addTransaction(firstPickup, 100);
        session.addTransaction(secondPickup, 100);
        session.attachTransactionToEncounter(firstPickup.getId(), kill.getId(), false);
        session.attachTransactionToEncounter(secondPickup.getId(), kill.getId(), false);

        SimulationSupport.equal(1, session.pkMetrics().kills, "partial-pickup kill count");
        SimulationSupport.equal(780_000L, session.pkMetrics().net, "partial-pickup total");
        SimulationSupport.equal(2, kill.getTransactionIds().size(), "partial-pickup transaction count");
    }

    private static void multipleKillsBeforeBanking()
    {
        Session session = new Session("Multi-kill", 0L, SessionMode.PK);
        PkEncounter first = session.addPkEncounter(
            EncounterType.KILL, 10_000L, "Player kill 1",
            ClassificationConfidence.CONFIRMED, "First simulated kill.");
        PkEncounter second = session.addPkEncounter(
            EncounterType.KILL, 20_000L, "Player kill 2",
            ClassificationConfidence.CONFIRMED, "Second simulated kill.");
        Transaction firstLoot = transaction(
            11_000L, TransactionType.PK_LOOT, Context.PK_LOOT,
            "First kill loot", Collections.singletonList(SimulationSupport.gain(10004, 1L)));
        Transaction secondLoot = transaction(
            21_000L, TransactionType.PK_LOOT, Context.PK_LOOT,
            "Second kill loot", Collections.singletonList(SimulationSupport.gain(10005, 1L)));
        Transaction bank = Tx.of(
            30_000L, TransactionType.TRANSFER, Context.TRANSFER,
            "Banked PK loot", false,
            Arrays.asList(SimulationSupport.cost(10004, 1L), SimulationSupport.cost(10005, 1L)));
        session.addTransaction(firstLoot, 100);
        session.addTransaction(secondLoot, 100);
        session.addTransaction(bank, 100);
        session.attachTransactionToEncounter(firstLoot.getId(), first.getId(), false);
        session.attachTransactionToEncounter(secondLoot.getId(), second.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        SimulationSupport.equal(2, metrics.kills, "multi-kill count");
        SimulationSupport.equal(820_000L, metrics.net, "multi-kill net");
        SimulationSupport.equal(820_000L, session.metrics(60_000L).net, "banking must not change session net");
    }

    private static void deathWithPriorLoot()
    {
        Session session = new Session("Carry then die", 0L, SessionMode.PK);
        PkEncounter kill = session.addPkEncounter(
            EncounterType.KILL, 10_000L, "Player kill",
            ClassificationConfidence.CONFIRMED, "Prior loot gained.");
        Transaction loot = transaction(
            11_000L, TransactionType.PK_LOOT, Context.PK_LOOT,
            "Carried PK loot", Collections.singletonList(SimulationSupport.gain(10004, 1L)));
        session.addTransaction(loot, 100);
        session.attachTransactionToEncounter(loot.getId(), kill.getId(), false);

        PkEncounter death = session.addPkEncounter(
            EncounterType.DEATH, 20_000L, "Player death",
            ClassificationConfidence.CONFIRMED, "Previously gained loot was lost.");
        Transaction loss = transaction(
            20_000L, TransactionType.PK_DEATH_LOSS, Context.PK_DEATH,
            "Carried PK loot lost", Collections.singletonList(SimulationSupport.cost(10004, 1L)));
        session.addTransaction(loss, 100);
        session.attachTransactionToEncounter(loss.getId(), death.getId(), false);

        PkMetrics metrics = session.pkMetrics();
        SimulationSupport.equal(1, metrics.kills, "carry-death kills");
        SimulationSupport.equal(1, metrics.deaths, "carry-death deaths");
        SimulationSupport.equal(-1, metrics.currentStreak, "one death forms a loss streak");
        SimulationSupport.equal(0L, metrics.net, "carried loot gain and loss cancel");
        SimulationSupport.equal(500_000L, metrics.largestDeathLoss, "carried loot death loss");
    }

    private static void uncertainCorrectionAndUndo()
    {
        Session session = new Session("Uncertain PK", 0L, SessionMode.PK);
        PkEncounter kill = session.addPkEncounter(
            EncounterType.KILL, 10_000L, "Uncertain player loot",
            ClassificationConfidence.UNCERTAIN, "Synthetic ambiguous PK inventory change.");
        Transaction uncertain = new Transaction(
            11_000L,
            11_000L,
            TransactionType.UNCERTAIN,
            Context.GENERIC,
            "Ambiguous PK change",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.valuedFlow(13001, "Ambiguous loot", 1L, 200_000)),
            ClassificationConfidence.UNCERTAIN,
            "No confirmed player-loot correlation was available.",
            kill.getId());
        session.addTransaction(uncertain, 100);
        session.attachTransactionToEncounter(uncertain.getId(), kill.getId(), false);
        SimulationSupport.equal(0L, session.pkMetrics().net, "uncertain PK net before correction");

        SimulationSupport.check(
            session.correctTransaction(uncertain.getId(), Correction.REVENUE, 12_000L, "Manual correction"),
            "PK correction should apply");
        SimulationSupport.equal(200_000L, session.pkMetrics().net, "corrected PK revenue");

        Transaction removed = session.undoLastTransaction(System.currentTimeMillis());
        SimulationSupport.equal(uncertain.getId(), removed.getId(), "PK undo transaction id");
        SimulationSupport.equal(0L, session.pkMetrics().net, "PK net after undo");
        SimulationSupport.equal(0, kill.getTransactionIds().size(), "encounter references after undo");
    }

    private static void exportPkExample(Path output) throws Exception
    {
        Session session = new Session("PK-Simulation", 1_000L, SessionMode.PK);
        PkEncounter kill = session.addPkEncounter(
            EncounterType.KILL, 10_000L, "Player kill",
            ClassificationConfidence.CONFIRMED, "Offline example kill.");
        Transaction loot = transaction(
            11_000L, TransactionType.PK_LOOT, Context.PK_LOOT,
            "PK loot", Collections.singletonList(SimulationSupport.gain(10004, 1L)));
        session.addTransaction(loot, 100);
        session.attachTransactionToEncounter(loot.getId(), kill.getId(), false);

        PkEncounter death = session.addPkEncounter(
            EncounterType.DEATH, 20_000L, "Player death",
            ClassificationConfidence.CONFIRMED, "Offline example death.");
        Transaction loss = transaction(
            20_000L, TransactionType.PK_DEATH_LOSS, Context.PK_DEATH,
            "Risk lost", Collections.singletonList(SimulationSupport.cost(10002, 1L)));
        session.addTransaction(loss, 100);
        session.attachTransactionToEncounter(loss.getId(), death.getId(), false);
        session.close(61_000L);

        // One file per export (owner 2026-09-28): the Grind CSV carries the PvP loot and death rows.
        Filepath export = SimulationSupport.export(session, output.resolve("pk-example"));
        String pkCsv = new String(Files.readAllBytes(Filepath.Unchecked.getPath(export)),
            java.nio.charset.StandardCharsets.UTF_8);
        SimulationSupport.check(pkCsv.contains("PK_LOOT"), "PvP loot rows");
        SimulationSupport.check(pkCsv.contains("PK_DEATH_LOSS"), "PvP death rows");


    }

    private static Transaction transaction(
        long timestamp,
        TransactionType type,
        Context context,
        String note,
        java.util.List<Flow> flows)
    {
        return new Transaction(
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
