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
        Session session = new Session("PK win", 0L, SessionMode.AUTO);
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
        session.addTransaction(supplies, 100, false);

        String kill = session.addEncounter(true, 10_000L);
        session.attachRecentCosts(kill, 10_000L, 90_000L);

        Transaction loot = transaction(
            11_000L,
            TransactionType.PK_LOOT,
            Context.PK_LOOT,
            "PK loot",
            Collections.singletonList(SimulationSupport.gain(10001, 1L)));
        session.addTransaction(loot, 100, false);
        session.attachToEncounter(loot.id(), kill, false);

        long[] money = money(session, kill);
        SimulationSupport.equal(1, session.pk().kills, "kill count");
        SimulationSupport.equal(42_000L, money[1], "PK supply cost");
        SimulationSupport.equal(780_000L, money[0], "PK loot revenue");
        SimulationSupport.equal(738_000L, money[2], "PK win net");
        SimulationSupport.equal(738_000L, session.bestKill(), "best kill");
        SimulationSupport.equal(TransactionType.PK_SUPPLY_COST, supplies.original(), "retrospective supply type");
    }

    private static void pkDeath()
    {
        Session session = new Session("PK death", 0L, SessionMode.AUTO);
        String death = session.addEncounter(false, 20_000L);

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
            TransactionType.CONSUMPTION,
            Context.PK_DEATH,
            "Reclaim fee",
            Collections.singletonList(SimulationSupport.cost(10003, 1L)));

        session.addTransaction(supplies, 100, false);
        session.addTransaction(risk, 100, false);
        session.addTransaction(fee, 100, false);
        session.attachToEncounter(supplies.id(), death, false);
        session.attachToEncounter(risk.id(), death, false);
        session.attachToEncounter(fee.id(), death, false);

        long[] money = money(session, death);
        SimulationSupport.equal(1, session.pk().deaths, "death count");
        SimulationSupport.equal(663_000L, money[1], "death costs");
        SimulationSupport.equal(-663_000L, money[2], "death net");
        SimulationSupport.equal(0L, session.bestKill(), "a death is never a best kill");
    }

    private static void lootKeyLifecycle()
    {
        Session session = new Session("Loot key", 0L, SessionMode.AUTO);
        String kill = session.addEncounter(true, 10_000L);

        Transaction keyReceipt = Tx.of(
            10_000L,
            10_000L,
            TransactionType.TRANSFER,
            Context.PK_LOOT,
            "Loot key received - value deferred",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.gain(11941, 1L)));
        session.addTransaction(keyReceipt, 100, false);
        session.attachToEncounter(keyReceipt.id(), kill, false);

        Transaction contents = transaction(
            30_000L,
            TransactionType.PK_LOOT,
            Context.PK_LOOT,
            "Loot key opened",
            Collections.singletonList(SimulationSupport.gain(10001, 1L)));
        session.addTransaction(contents, 100, false);
        session.attachToEncounter(contents.id(), kill, false);

        Transaction keyRemoval = Tx.of(
            30_000L,
            30_000L,
            TransactionType.TRANSFER,
            Context.PK_LOOT,
            "Loot key consumed - value already deferred",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.cost(11941, 1L)));
        session.addTransaction(keyRemoval, 100, false);
        session.attachToEncounter(keyRemoval.id(), kill, false);

        long[] money = money(session, kill);
        SimulationSupport.equal(780_000L, money[0], "loot-key contents revenue");
        SimulationSupport.equal(0L, money[1], "loot-key deferred audit costs");
        SimulationSupport.equal(780_000L, session.bestKill(), "loot-key net counted once");
    }

    private static void partialLootPickup()
    {
        Session session = new Session("Partial pickup", 0L, SessionMode.AUTO);
        String kill = session.addEncounter(true, 10_000L);

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
        session.addTransaction(firstPickup, 100, false);
        session.addTransaction(secondPickup, 100, false);
        session.attachToEncounter(firstPickup.id(), kill, false);
        session.attachToEncounter(secondPickup.id(), kill, false);

        SimulationSupport.equal(1, session.pk().kills, "partial-pickup kill count");
        SimulationSupport.equal(780_000L, session.bestKill(), "partial-pickup total");
    }

    private static void multipleKillsBeforeBanking()
    {
        Session session = new Session("Multi-kill", 0L, SessionMode.AUTO);
        String first = session.addEncounter(true, 10_000L);
        String second = session.addEncounter(true, 20_000L);
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
        session.addTransaction(firstLoot, 100, false);
        session.addTransaction(secondLoot, 100, false);
        session.addTransaction(bank, 100, false);
        session.attachToEncounter(firstLoot.id(), first, false);
        session.attachToEncounter(secondLoot.id(), second, false);

        SimulationSupport.equal(2, session.pk().kills, "multi-kill count");
        SimulationSupport.equal(820_000L, money(session, first)[2] + money(session, second)[2], "multi-kill net");
        SimulationSupport.equal(820_000L, session.metrics(60_000L).net, "banking must not change session net");
    }

    private static void deathWithPriorLoot()
    {
        Session session = new Session("Carry then die", 0L, SessionMode.AUTO);
        String kill = session.addEncounter(true, 10_000L);
        Transaction loot = transaction(
            11_000L, TransactionType.PK_LOOT, Context.PK_LOOT,
            "Carried PK loot", Collections.singletonList(SimulationSupport.gain(10004, 1L)));
        session.addTransaction(loot, 100, false);
        session.attachToEncounter(loot.id(), kill, false);

        String death = session.addEncounter(false, 20_000L);
        Transaction loss = transaction(
            20_000L, TransactionType.PK_DEATH_LOSS, Context.PK_DEATH,
            "Carried PK loot lost", Collections.singletonList(SimulationSupport.cost(10004, 1L)));
        session.addTransaction(loss, 100, false);
        session.attachToEncounter(loss.id(), death, false);

        SimulationSupport.equal(1, session.pk().kills, "carry-death kills");
        SimulationSupport.equal(1, session.pk().deaths, "carry-death deaths");
        SimulationSupport.equal(-1, session.pk().streak, "one death forms a loss streak");
        SimulationSupport.equal(0L, money(session, kill)[2] + money(session, death)[2], "carried loot gain and loss cancel");
        SimulationSupport.equal(-500_000L, money(session, death)[2], "carried loot death loss");
    }

    private static void uncertainCorrectionAndUndo()
    {
        Session session = new Session("Uncertain PK", 0L, SessionMode.AUTO);
        String kill = session.addEncounter(true, 10_000L);
        Transaction uncertain = new Transaction(
            11_000L,
            TransactionType.UNCERTAIN,
            Context.GENERIC,
            "Ambiguous PK change",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.valuedFlow(13001, "Ambiguous loot", 1L, 200_000)),
            Confidence.UNCERTAIN,
            "No confirmed player-loot correlation was available.",
            kill);
        session.addTransaction(uncertain, 100, false);
        session.attachToEncounter(uncertain.id(), kill, false);
        SimulationSupport.equal(0L, session.bestKill(), "uncertain PK net before correction");

        SimulationSupport.check(
            session.correctTransaction(uncertain.id(), Correction.REVENUE, "Manual correction"),
            "PK correction should apply");
        SimulationSupport.equal(200_000L, session.bestKill(), "corrected PK revenue");

        Transaction removed = session.undoTransaction();
        SimulationSupport.equal(uncertain.id(), removed.id(), "PK undo transaction id");
        SimulationSupport.equal(0L, session.bestKill(), "PK net after undo");
    }

    private static void exportPkExample(Path output) throws Exception
    {
        Session session = new Session("PK-Simulation", 1_000L, SessionMode.AUTO);
        String kill = session.addEncounter(true, 10_000L);
        Transaction loot = transaction(
            11_000L, TransactionType.PK_LOOT, Context.PK_LOOT,
            "PK loot", Collections.singletonList(SimulationSupport.gain(10004, 1L)));
        session.addTransaction(loot, 100, false);
        session.attachToEncounter(loot.id(), kill, false);

        String death = session.addEncounter(false, 20_000L);
        Transaction loss = transaction(
            20_000L, TransactionType.PK_DEATH_LOSS, Context.PK_DEATH,
            "Risk lost", Collections.singletonList(SimulationSupport.cost(10002, 1L)));
        session.addTransaction(loss, 100, false);
        session.attachToEncounter(loss.id(), death, false);
        session.close(61_000L);

        SimulationSupport.check(session.transactions().stream().anyMatch(t -> t.type() == TransactionType.PK_LOOT),
            "PvP loot rows");
        SimulationSupport.check(session.transactions().stream().anyMatch(t -> t.type() == TransactionType.PK_DEATH_LOSS),
            "PvP death rows");


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
            type,
            context,
            note,
            "PKing",
            true,
            flows,
            Confidence.CONFIRMED,
            "Offline simulation input; no RuneLite client or game event was used.",
            null);
    }

    /** Revenue, costs and net of the receipts carrying one encounter's id. */
    private static long[] money(Session session, String encounterId)
    {
        long[] money = new long[3];
        for (Transaction transaction : session.transactions())
        {
            if (!encounterId.equals(transaction.encounterId())) continue;
            Accounting.Amounts amounts = Accounting.transaction(transaction);
            if (!amounts.available || !amounts.included) continue;
            money[0] += amounts.income;
            money[1] += amounts.cost;
            money[2] += amounts.net();
        }
        return money;
    }
}
