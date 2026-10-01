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
        Ad session = new Ad("PK win", 0L, Cx.PK);
        Ac supplies = transaction(
            1_000L,
            Ai.CONSUMPTION,
            Aj.GENERIC,
            "PK supplies",
            Arrays.asList(
                SimulationSupport.cost(379, 40L),
                SimulationSupport.cost(385, 20L),
                SimulationSupport.cost(561, 50L),
                SimulationSupport.cost(555, 1_000L)));
        session.kf(supplies, 100);

        Bx kill = session.ke(
            Be.KILL,
            10_000L,
            "Player kill",
            Bd.CONFIRMED,
            "Offline simulation of RuneLite player-loot accounting.");
        session.lj(kill.getId(), 10_000L, 90_000L);

        Ac loot = transaction(
            11_000L,
            Ai.PK_LOOT,
            Aj.PK_LOOT,
            "PK loot",
            Collections.singletonList(SimulationSupport.gain(10001, 1L)));
        session.kf(loot, 100);
        session.ll(loot.getId(), kill.getId(), false);

        Dt metrics = session.ava();
        SimulationSupport.equal(1, metrics.kills, "kill count");
        SimulationSupport.equal(42_000L, metrics.costs, "PK supply cost");
        SimulationSupport.equal(780_000L, metrics.revenue, "PK loot revenue");
        SimulationSupport.equal(738_000L, metrics.net, "PK win net");
        SimulationSupport.equal(1, metrics.ue(), "PK win encounter count");
        SimulationSupport.equal(738_000L, metrics.totalKillNet / metrics.kills, "profit per kill");
        SimulationSupport.equal(738_000L, metrics.net / metrics.ue(), "net per encounter");
        SimulationSupport.equal(Ai.PK_SUPPLY_COST, supplies.tm(), "retrospective supply type");
    }

    private static void pkDeath()
    {
        Ad session = new Ad("PK death", 0L, Cx.PK);
        Bx death = session.ke(
            Be.DEATH,
            20_000L,
            "Player death",
            Bd.CONFIRMED,
            "Offline simulation of a confirmed PK death.");

        Ac supplies = transaction(
            18_000L,
            Ai.PK_SUPPLY_COST,
            Aj.PK_DEATH,
            "Supplies consumed before death",
            Collections.singletonList(SimulationSupport.valuedFlow(11001, "PK supplies", -1L, 38_000)));
        Ac risk = transaction(
            20_000L,
            Ai.PK_DEATH_LOSS,
            Aj.PK_DEATH,
            "Unprotected items lost",
            Collections.singletonList(SimulationSupport.cost(10002, 1L)));
        Ac fee = transaction(
            20_500L,
            Ai.PK_FEE,
            Aj.PK_DEATH,
            "PK fee",
            Collections.singletonList(SimulationSupport.cost(10003, 1L)));

        session.kf(supplies, 100);
        session.kf(risk, 100);
        session.kf(fee, 100);
        session.ll(supplies.getId(), death.getId(), false);
        session.ll(risk.getId(), death.getId(), false);
        session.ll(fee.getId(), death.getId(), false);

        Dt metrics = session.ava();
        SimulationSupport.equal(1, metrics.deaths, "death count");
        SimulationSupport.equal(663_000L, metrics.costs, "death costs");
        SimulationSupport.equal(-663_000L, metrics.net, "death net");
        SimulationSupport.equal(663_000L, metrics.largestDeathLoss, "largest death loss");
        SimulationSupport.equal(663_000L, metrics.totalDeathLoss / metrics.deaths, "loss per death");
        SimulationSupport.equal(-663_000L, metrics.net / metrics.ue(), "death net per encounter");
    }

    private static void lootKeyLifecycle()
    {
        Ad session = new Ad("Loot key", 0L, Cx.PK);
        Bx kill = session.ke(
            Be.KILL,
            10_000L,
            "Player kill - loot key",
            Bd.CONFIRMED,
            "Loot-key receipt is retained as a non-counted audit row until opened.");

        Ac keyReceipt = Tx.of(
            10_000L,
            10_000L,
            Ai.TRANSFER,
            Aj.PK_LOOT,
            "Loot key received - value deferred",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.gain(11941, 1L)));
        session.kf(keyReceipt, 100);
        session.ll(keyReceipt.getId(), kill.getId(), false);

        Ac contents = transaction(
            30_000L,
            Ai.PK_LOOT,
            Aj.PK_LOOT,
            "Loot key opened",
            Collections.singletonList(SimulationSupport.gain(10001, 1L)));
        session.kf(contents, 100);
        session.ll(contents.getId(), kill.getId(), false);

        Ac keyRemoval = Tx.of(
            30_000L,
            30_000L,
            Ai.TRANSFER,
            Aj.PK_LOOT,
            "Loot key consumed - value already deferred",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.cost(11941, 1L)));
        session.kf(keyRemoval, 100);
        session.ll(keyRemoval.getId(), kill.getId(), false);

        Dt metrics = session.ava();
        SimulationSupport.equal(780_000L, metrics.revenue, "loot-key contents revenue");
        SimulationSupport.equal(0L, metrics.costs, "loot-key deferred audit costs");
        SimulationSupport.equal(780_000L, metrics.net, "loot-key net counted once");
    }

    private static void partialLootPickup()
    {
        Ad session = new Ad("Partial pickup", 0L, Cx.PK);
        Bx kill = session.ke(
            Be.KILL,
            10_000L,
            "Player kill",
            Bd.CONFIRMED,
            "One player-loot encounter with items collected in separate inventory changes.");

        Ac firstPickup = transaction(
            11_000L,
            Ai.PK_LOOT,
            Aj.PK_LOOT,
            "Partial PK loot 1",
            Collections.singletonList(SimulationSupport.valuedFlow(12001, "First pickup", 1L, 300_000)));
        Ac secondPickup = transaction(
            14_000L,
            Ai.PK_LOOT,
            Aj.PK_LOOT,
            "Partial PK loot 2",
            Collections.singletonList(SimulationSupport.valuedFlow(12002, "Second pickup", 1L, 480_000)));
        session.kf(firstPickup, 100);
        session.kf(secondPickup, 100);
        session.ll(firstPickup.getId(), kill.getId(), false);
        session.ll(secondPickup.getId(), kill.getId(), false);

        SimulationSupport.equal(1, session.ava().kills, "partial-pickup kill count");
        SimulationSupport.equal(780_000L, session.ava().net, "partial-pickup total");
        SimulationSupport.equal(2, kill.getTransactionIds().size(), "partial-pickup transaction count");
    }

    private static void multipleKillsBeforeBanking()
    {
        Ad session = new Ad("Multi-kill", 0L, Cx.PK);
        Bx first = session.ke(
            Be.KILL, 10_000L, "Player kill 1",
            Bd.CONFIRMED, "First simulated kill.");
        Bx second = session.ke(
            Be.KILL, 20_000L, "Player kill 2",
            Bd.CONFIRMED, "Second simulated kill.");
        Ac firstLoot = transaction(
            11_000L, Ai.PK_LOOT, Aj.PK_LOOT,
            "First kill loot", Collections.singletonList(SimulationSupport.gain(10004, 1L)));
        Ac secondLoot = transaction(
            21_000L, Ai.PK_LOOT, Aj.PK_LOOT,
            "Second kill loot", Collections.singletonList(SimulationSupport.gain(10005, 1L)));
        Ac bank = Tx.of(
            30_000L, Ai.TRANSFER, Aj.TRANSFER,
            "Banked PK loot", false,
            Arrays.asList(SimulationSupport.cost(10004, 1L), SimulationSupport.cost(10005, 1L)));
        session.kf(firstLoot, 100);
        session.kf(secondLoot, 100);
        session.kf(bank, 100);
        session.ll(firstLoot.getId(), first.getId(), false);
        session.ll(secondLoot.getId(), second.getId(), false);

        Dt metrics = session.ava();
        SimulationSupport.equal(2, metrics.kills, "multi-kill count");
        SimulationSupport.equal(820_000L, metrics.net, "multi-kill net");
        SimulationSupport.equal(820_000L, session.metrics(60_000L).net, "banking must not change session net");
    }

    private static void deathWithPriorLoot()
    {
        Ad session = new Ad("Carry then die", 0L, Cx.PK);
        Bx kill = session.ke(
            Be.KILL, 10_000L, "Player kill",
            Bd.CONFIRMED, "Prior loot gained.");
        Ac loot = transaction(
            11_000L, Ai.PK_LOOT, Aj.PK_LOOT,
            "Carried PK loot", Collections.singletonList(SimulationSupport.gain(10004, 1L)));
        session.kf(loot, 100);
        session.ll(loot.getId(), kill.getId(), false);

        Bx death = session.ke(
            Be.DEATH, 20_000L, "Player death",
            Bd.CONFIRMED, "Previously gained loot was lost.");
        Ac loss = transaction(
            20_000L, Ai.PK_DEATH_LOSS, Aj.PK_DEATH,
            "Carried PK loot lost", Collections.singletonList(SimulationSupport.cost(10004, 1L)));
        session.kf(loss, 100);
        session.ll(loss.getId(), death.getId(), false);

        Dt metrics = session.ava();
        SimulationSupport.equal(1, metrics.kills, "carry-death kills");
        SimulationSupport.equal(1, metrics.deaths, "carry-death deaths");
        SimulationSupport.equal(-1, metrics.currentStreak, "one death forms a loss streak");
        SimulationSupport.equal(0L, metrics.net, "carried loot gain and loss cancel");
        SimulationSupport.equal(500_000L, metrics.largestDeathLoss, "carried loot death loss");
    }

    private static void uncertainCorrectionAndUndo()
    {
        Ad session = new Ad("Uncertain PK", 0L, Cx.PK);
        Bx kill = session.ke(
            Be.KILL, 10_000L, "Uncertain player loot",
            Bd.UNCERTAIN, "Synthetic ambiguous PK inventory change.");
        Ac uncertain = new Ac(
            11_000L,
            11_000L,
            Ai.UNCERTAIN,
            Aj.GENERIC,
            "Ambiguous PK change",
            "PKing",
            false,
            Collections.singletonList(SimulationSupport.valuedFlow(13001, "Ambiguous loot", 1L, 200_000)),
            Bd.UNCERTAIN,
            "No confirmed player-loot correlation was available.",
            kill.getId());
        session.kf(uncertain, 100);
        session.ll(uncertain.getId(), kill.getId(), false);
        SimulationSupport.equal(0L, session.ava().net, "uncertain PK net before correction");

        SimulationSupport.check(
            session.qi(uncertain.getId(), Ah.REVENUE, 12_000L, "Manual correction"),
            "PK correction should apply");
        SimulationSupport.equal(200_000L, session.ava().net, "corrected PK revenue");

        Ac removed = session.akc(System.currentTimeMillis());
        SimulationSupport.equal(uncertain.getId(), removed.getId(), "PK undo transaction id");
        SimulationSupport.equal(0L, session.ava().net, "PK net after undo");
        SimulationSupport.equal(0, kill.getTransactionIds().size(), "encounter references after undo");
    }

    private static void exportPkExample(Path output) throws Exception
    {
        Ad session = new Ad("PK-Simulation", 1_000L, Cx.PK);
        Bx kill = session.ke(
            Be.KILL, 10_000L, "Player kill",
            Bd.CONFIRMED, "Offline example kill.");
        Ac loot = transaction(
            11_000L, Ai.PK_LOOT, Aj.PK_LOOT,
            "PK loot", Collections.singletonList(SimulationSupport.gain(10004, 1L)));
        session.kf(loot, 100);
        session.ll(loot.getId(), kill.getId(), false);

        Bx death = session.ke(
            Be.DEATH, 20_000L, "Player death",
            Bd.CONFIRMED, "Offline example death.");
        Ac loss = transaction(
            20_000L, Ai.PK_DEATH_LOSS, Aj.PK_DEATH,
            "Risk lost", Collections.singletonList(SimulationSupport.cost(10002, 1L)));
        session.kf(loss, 100);
        session.ll(loss.getId(), death.getId(), false);
        session.close(61_000L);

        // One file per export (owner 2026-09-28): the Grind CSV carries the PvP loot and death rows.
        Filepath export = SimulationSupport.export(session, output.resolve("pk-example"));
        String pkCsv = new String(Files.readAllBytes(Filepath.Unchecked.getPath(export)),
            java.nio.charset.StandardCharsets.UTF_8);
        SimulationSupport.check(pkCsv.contains("PK_LOOT"), "PvP loot rows");
        SimulationSupport.check(pkCsv.contains("PK_DEATH_LOSS"), "PvP death rows");


    }

    private static Ac transaction(
        long timestamp,
        Ai type,
        Aj context,
        String note,
        java.util.List<Ab> flows)
    {
        return new Ac(
            timestamp,
            timestamp,
            type,
            context,
            note,
            "PKing",
            true,
            flows,
            Bd.CONFIRMED,
            "Offline simulation input; no RuneLite client or game event was used.",
            null);
    }
}
