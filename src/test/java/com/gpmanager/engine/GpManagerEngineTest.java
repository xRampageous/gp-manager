package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.SkullIcon;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GpManagerEngineTest
{
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public boolean keepTransferAuditRows()
        {
            return true;
        }

        @Override
        public int stabilizationTicks()
        {
            return 2;
        }
    };

    private final FlowValuator valuator = deltas ->
    {
        List<Ab> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int price = entry.getKey() == 995 ? 1 : entry.getKey() == 526 ? 31 : entry.getKey() == 555 ? 5 : 100;
            flows.add(new Ab(
                entry.getKey(),
                "Item " + entry.getKey(),
                entry.getValue(),
                price,
                entry.getValue() * price));
        }
        return flows;
    };

    @Test
    public void excludesTransfersThenTracksConsumptionAndLoot()
    {
        long now = 1_000_000L;
        Am engine = engine();

        engine.rm(now);
        engine.setBaseline(snapshot(995, 1_000L));

        engine.markContext(Aj.TRANSFER, 2, "Bank transfer");
        engine.yz();
        Ac transfer = settle(engine, snapshot(995, 2_000L), now + 600L);

        assertNotNull(transfer);
        assertEquals(Ai.TRANSFER, transfer.getType());
        assertFalse(transfer.isCounted());
        assertEquals(0L, engine.getMetrics(now + 2_400L).net);

        engine.yz();
        Ac consumption = settle(
            engine,
            snapshot(995, 1_900L),
            now + 3_000L);
        assertEquals(Ai.CONSUMPTION, consumption.getType());
        assertEquals(-100L, consumption.getNet());

        engine.zk(
            Collections.singletonMap(100, 2L),
            2,
            "Loot from Goblin",
            "Goblin");
        engine.aeh("Goblin");
        engine.yz();
        Map<Integer, Long> lootValues = new HashMap<>();
        lootValues.put(995, 1_900L);
        lootValues.put(100, 2L);
        Ac loot = settle(
            engine,
            new Cc(lootValues),
            now + 5_400L);

        assertEquals(Ai.LOOT, loot.getType());
        assertEquals(200L, loot.getNet());
        assertEquals("Loot from Goblin", loot.getNote());

        Bu metrics = engine.getMetrics(now + 8_000L);
        assertEquals(200L, metrics.revenue);
        assertEquals(100L, metrics.costs);
        assertEquals(100L, metrics.net);
        assertEquals(1, engine.getActiveSession().actionCount);
        assertEquals("Goblin", metrics.activityHint);
    }

    @Test
    public void openingInventoryIsPrimedAndNeverCountedAsProfit()
    {
        Am engine = engine();
        long now = 10_000L;
        engine.rm(now);
        engine.lp();

        assertNull(engine.adj(Cc.empty(), now + 600L));
        assertNull(engine.adj(snapshot(1205, 1L), now + 1_200L));
        assertNull(engine.adj(snapshot(1205, 1L), now + 1_800L));
        assertNull(engine.adj(snapshot(1205, 1L), now + 2_400L));

        assertEquals(0L, engine.getMetrics(now + 2_400L).revenue);
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());

        engine.yz();
        Ac cost = settle(engine, Cc.empty(), now + 3_000L);
        assertEquals(Ai.CONSUMPTION, cost.getType());
        assertEquals(-100L, cost.getNet());
    }

    @Test
    public void settledItemFlowUsesThePriceObservationTimestamp()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public int stabilizationTicks() { return 1; }
        };
        Bl service = new Bl(config, id -> null, id -> 75);
        Am engine = new Am(service, new TransactionClassifier(), config);
        long settleAt = 20_600L;
        engine.rm(20_000L);
        engine.setBaseline(Cc.empty());
        engine.yz();

        assertNull(engine.adj(snapshot(1942, 2L), 20_000L));
        Ac transaction = engine.adj(snapshot(1942, 2L), settleAt);

        assertNotNull(transaction);
        assertEquals(150L, transaction.getFlows().get(0).valueDelta);
        assertEquals(Av.GRAND_EXCHANGE, transaction.getFlows().get(0).getPriceSource());
        assertEquals(settleAt, transaction.getFlows().get(0).getPriceCapturedAtEpochMillis());
    }

    @Test
    public void stabilizationCancelsTemporaryEquipmentRemoval()
    {
        Am engine = engine();
        long now = 20_000L;
        engine.rm(now);
        engine.setBaseline(snapshot(1265, 1L));

        // Equipment disappears first.
        engine.yz();
        assertNull(engine.adj(Cc.empty(), now + 600L));
        assertNull(engine.adj(Cc.empty(), now + 1_200L));

        // Loot arrives while the equipment is restored. The pending transaction
        // is recalculated from the original baseline, so the pickaxe nets to zero.
        Map<Integer, Long> expected = Collections.singletonMap(526, 1L);
        engine.zk(expected, 6, "Loot from Goblin", "Goblin");
        engine.yz();
        Map<Integer, Long> finalValues = new HashMap<>();
        finalValues.put(1265, 1L);
        finalValues.put(526, 1L);

        Ac transaction = settle(
            engine,
            new Cc(finalValues),
            now + 1_800L);

        assertNotNull(transaction);
        assertEquals(Ai.LOOT, transaction.getType());
        assertEquals(31L, transaction.getNet());
        assertEquals(1, transaction.getFlows().size());
        assertEquals(526, transaction.getFlows().get(0).itemId);
    }

    @Test
    public void bankRemoveAndRestorePairProducesNoTransaction()
    {
        Am engine = engine();
        long now = 30_000L;
        engine.rm(now);
        engine.setBaseline(snapshot(2309, 1L));

        engine.markContext(Aj.TRANSFER, 6, "Bank transfer");
        engine.yz();
        assertNull(engine.adj(Cc.empty(), now + 600L));
        assertNull(engine.adj(Cc.empty(), now + 1_200L));

        engine.yz();
        assertNull(engine.adj(snapshot(2309, 1L), now + 1_800L));
        assertNull(engine.adj(snapshot(2309, 1L), now + 2_400L));
        assertNull(engine.adj(snapshot(2309, 1L), now + 3_000L));

        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0L, engine.getMetrics(now + 3_000L).net);
    }

    @Test
    public void bankTransferWinsOverAnArmedChargeLoadIntent()
    {
        Am engine = new Am(
            deltas -> {
                List<Ab> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> entry : deltas.entrySet())
                {
                    String name = entry.getKey() == 12934 ? "Zulrah's scales" : "Item " + entry.getKey();
                    int price = entry.getKey() == 12934 ? 100 : 1;
                    flows.add(new Ab(
                        entry.getKey(), name, entry.getValue(), price, entry.getValue() * price));
                }
                return flows;
            },
            new TransactionClassifier(),
            CONFIG);
        long now = 33_000L;
        engine.rm(now);
        engine.setBaseline(snapshot(12934, 100L));
        assertTrue(engine.yx(
            Ar.V.V1b, 12934, "Zulrah's scales", null, 8));

        // The bank's hard evidence is stronger than the narrow Use-on-item intent.
        engine.markContext(Aj.TRANSFER, 8, "Bank transfer");
        engine.yz();
        Ac transfer = settle(engine, snapshot(12934, 50L), now + 600L);

        assertNotNull(transfer);
        assertEquals(Ai.TRANSFER, transfer.getType());
        assertEquals("Bank transfer", transfer.getNote());
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertEquals(-5_000L, engine.getActiveSession().getTransactions().get(0).getNet());
    }

    @Test
    public void chargeLoadWithoutExactQuantityMovesLossIntoUncountedReviewRow()
    {
        Am engine = new Am(
            deltas -> {
                List<Ab> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> entry : deltas.entrySet())
                {
                    boolean scales = entry.getKey() == 12934;
                    flows.add(new Ab(
                        entry.getKey(),
                        scales ? "Zulrah's scales" : "Item " + entry.getKey(),
                        entry.getValue(),
                        scales ? 100 : 1,
                        entry.getValue() * (scales ? 100 : 1)));
                }
                return flows;
            },
            new TransactionClassifier(),
            CONFIG);
        long now = 36_000L;
        engine.rm(now);
        engine.setBaseline(snapshot(12934, 100L));
        assertTrue(engine.yx(
            Ar.V.V1b, 12934, "Zulrah's scales",
            "149:0:9764864:12934:V1b", 8));

        engine.yz();
        Ac load = settle(engine, snapshot(12934, 50L), now + 600L);

        assertEquals("a validated load moves value, never a cost", Ai.TRANSFER, load.getType());
        assertFalse(load.isCounted());
        assertEquals("Charge load transfer", load.getNote());
        assertEquals(-5_000L, load.tl());
        assertEquals(-5_000L, load.getNet());
        assertEquals(0L, engine.getMetrics(now + 3_000L).net);
        assertEquals(1, load.getFlows().size());
        assertFalse(new com.google.gson.Gson().toJson(load).contains("chargeLoadReviewProvenance"));
        assertEquals(Au.CHARGE_LOAD_AMBIGUOUS, load.getActionKind());
    }

    @Test
    public void expectedLootMustMatchAPositiveFlow()
    {
        Am engine = engine();
        long now = 40_000L;
        engine.rm(now);
        engine.setBaseline(snapshot(995, 100L));

        engine.zk(Collections.singletonMap(526, 1L), 6, "Loot from Goblin", "Goblin");
        engine.yz();
        Ac transaction = settle(engine, snapshot(995, 200L), now + 600L);

        assertEquals(Ai.GAIN, transaction.getType());
        assertEquals(Aj.GENERIC, transaction.getContext());
        assertEquals("", transaction.getNote());
    }

    @Test
    public void expectedLootQuantityMustMatchThePositiveFlow()
    {
        Am engine = engine();
        long now = 45_000L;
        engine.rm(now);
        engine.setBaseline(Cc.empty());

        engine.zk(Collections.singletonMap(526, 1L), 6, "Loot from Goblin", "Goblin");
        engine.yz();
        Ac transaction = settle(engine, snapshot(526, 5L), now + 600L);

        assertEquals(Ai.GAIN, transaction.getType());
        assertEquals(Aj.GENERIC, transaction.getContext());
        assertEquals("", transaction.getNote());
    }

    @Test
    public void unpricedInventoryChangeReachesReviewInsteadOfBecomingZeroGp()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 2; }
        };
        FlowValuator unpricedValuator = deltas -> Collections.singletonList(
            new Ab(1942, "Potato", deltas.get(1942), 0, 0, Av.UNPRICED));
        Am engine = new Am(
            unpricedValuator, new TransactionClassifier(), config);
        long now = 48_000L;
        engine.rm(now);
        engine.setBaseline(Cc.empty());
        engine.yz();

        Ac transaction = settle(engine, snapshot(1942, 1L), now + 600L);

        assertNotNull(transaction);
        assertEquals(Ai.GAIN, transaction.getType());
        assertEquals(1L, transaction.getFlows().get(0).quantityDelta);
        assertEquals(Av.UNPRICED, transaction.getFlows().get(0).getPriceSource());
    }

    @Test
    public void excludesLoggedOutTimeWithoutOverridingManualPause()
    {
        Am engine = engine();
        engine.rm(1_000L);

        engine.acu(2_000L);
        engine.resume(12_000L, Ed.LIFECYCLE);
        assertEquals(2_000L, engine.getMetrics(13_000L).elapsedMillis);

        engine.togglePause(14_000L);
        engine.acu(15_000L);
        engine.resume(25_000L, Ed.LIFECYCLE);
        assertTrue(engine.getMetrics(26_000L).paused);
    }

    @Test
    public void lifecycleResumeCheckDoesNotEraseRunningBaseline()
    {
        Am engine = engine();
        long now = 12_000L;
        engine.rm(now);
        engine.setBaseline(Cc.empty());

        // Normal gameplay calls this check even when there was no lifecycle pause.
        engine.resume(now + 100L, Ed.LIFECYCLE);
        engine.yz();
        Ac gain = settle(engine, snapshot(1942, 1L), now + 600L);

        assertNotNull(gain);
        assertEquals(Ai.GAIN, gain.getType());
    }

    @Test
    public void idlePauseSurvivesLifecycleUntilMeaningfulActivity()
    {
        Am engine = engine();
        engine.rm(1_000L);

        engine.adh(2_000L, 2_000L);
        assertTrue(engine.yp());
        assertTrue(engine.getMetrics(3_000L).paused);

        engine.acu(4_000L);
        engine.resume(14_000L, Ed.LIFECYCLE);

        assertTrue(engine.yp());
        assertTrue(engine.getMetrics(15_000L).paused);

        engine.resume(16_000L, Ed.IDLE);
        assertFalse(engine.yp());
        assertFalse(engine.getMetrics(17_000L).paused);
    }

    @Test
    public void delayedPartialNpcLootRemainsClassified()
    {
        Am engine = engine();
        long now = 50_000L;
        engine.rm(now);
        engine.setBaseline(Cc.empty());

        Map<Integer, Long> expected = new HashMap<>();
        expected.put(526, 1L);
        expected.put(555, 6L);
        engine.aeh("Goblin");
        engine.zk(expected, 50, "Loot from Goblin", "Goblin");

        for (int tick = 0; tick < 10; tick++)
        {
            assertNull(engine.adj(Cc.empty(), now + 600L * (tick + 1)));
        }

        engine.yz();
        Ac bones = settle(engine, snapshot(526, 1L), now + 7_000L);
        assertEquals(Ai.LOOT, bones.getType());
        assertEquals("Goblin", bones.getActivityName());

        Map<Integer, Long> allLoot = new HashMap<>();
        allLoot.put(526, 1L);
        allLoot.put(555, 6L);
        engine.yz();
        Ac runes = settle(engine, new Cc(allLoot), now + 10_000L);
        assertEquals(Ai.LOOT, runes.getType());
        assertEquals("Goblin", runes.getActivityName());

    }

    private Am engine()
    {
        return new Am(
            valuator,
            new TransactionClassifier(),
            CONFIG);
    }

    private static Ac settle(
        Am engine,
        Cc snapshot,
        long firstTick)
    {
        Ac transaction = engine.adj(snapshot, firstTick);
        assertNull(transaction);
        transaction = engine.adj(snapshot, firstTick + 600L);
        assertNull(transaction);
        return engine.adj(snapshot, firstTick + 1_200L);
    }

    private static Cc snapshot(int itemId, long quantity)
    {
        Map<Integer, Long> values = new HashMap<>();
        if (quantity > 0L)
        {
            values.put(itemId, quantity);
        }
        return new Cc(values);
    }

    @Test
    public void playerLootCreatesConfirmedPkEncounter()
    {
        Am engine = engine();
        long now = 70_000L;
        engine.rm(now);
        engine.setBaseline(Cc.empty());

        engine.zn(
            Collections.singletonMap(526, 1L),
            50,
            "Player kill",
            now + 600L);
        engine.yz();
        Ac loot = settle(engine, snapshot(526, 1L), now + 1_200L);

        assertEquals(Ai.PK_LOOT, loot.getType());
        assertEquals(Bd.CONFIRMED, loot.getConfidence());
        assertFalse(loot.getEncounterId().isEmpty());
        assertEquals(1, engine.vl().kills);
        assertEquals(31L, engine.vl().net);
    }

    @Test
    public void pkKillAndDeathEncountersRecordFactsButNeverALocation()
    {
        Am engine = engine();
        long now = 72_000L;
        engine.rm(now);
        engine.aki(100, 200, 0);
        engine.zn(Collections.emptyMap(), 10, "Player kill", now + 1L);
        engine.aki(300, 400, 0);
        engine.zo("Player death", now + 2L, null);

        java.util.List<Bx> encounters = engine.getActiveSession().getPkEncounters();
        assertEquals(2, encounters.size());
        assertEquals(Be.KILL, encounters.get(0).getType());
        assertEquals(Be.DEATH, encounters.get(1).getType());
        assertEquals(1, engine.getActiveSession().ava().kills);
        assertEquals(1, engine.getActiveSession().ava().deaths);
        String json = new com.google.gson.Gson().toJson(encounters);
        assertFalse("location is a detection signal only, never a persisted PvP fact", json.contains("Wilderness"));
        assertFalse(json.contains("Gauntlet"));
        assertFalse(json.contains("locationLabel"));
    }

    @Test
    public void genericMixedChangeIsUncertainAndExcludedByDefault()
    {
        Am engine = engine();
        long now = 80_000L;
        engine.rm(now);
        engine.setBaseline(snapshot(995, 100L));

        Map<Integer, Long> after = new HashMap<>();
        after.put(995, 50L);
        after.put(526, 2L);
        engine.yz();
        Ac transaction = settle(engine, new Cc(after), now + 600L);

        assertEquals(Ai.UNCERTAIN, transaction.getType());
        assertEquals(Bd.UNCERTAIN, transaction.getConfidence());
        assertFalse(transaction.isCounted());
        assertEquals(0L, engine.getMetrics(now + 3_000L).net);
    }


    @Test
    public void confirmedPkDeathCreatesLossEncounter()
    {
        Am engine = engine();
        long now = 90_000L;
        engine.rm(now);
        engine.setBaseline(snapshot(1265, 1L));

        engine.zo("Player death", now + 600L,
            Ch.capture(null, false, SkullIcon.SKULL, null, null));
        engine.yz();
        Ac loss = settle(engine, Cc.empty(), now + 1_200L);

        assertEquals(Ai.PK_DEATH_LOSS, loss.getType());
        assertEquals(Bd.CONFIRMED, loss.getConfidence());
        assertEquals(-100L, loss.getNet());
        assertTrue(loss.getExplanation().contains("Death evidence"));
        assertTrue(loss.getExplanation(), loss.getExplanation().contains("lost: Item 1265"));
        assertEquals(1, engine.vl().deaths);
        assertEquals(100L, engine.vl().largestDeathLoss);
    }

    /** Guice calls every @Inject method when it builds the engine; only setters may carry it. */
    @Test
    public void onlySettersAreInjected()
    {
        for (java.lang.reflect.Method method : Am.class.getDeclaredMethods())
        {
            if (method.isAnnotationPresent(javax.inject.Inject.class))
            {
                assertTrue(method.getName(), method.getName().startsWith("set"));
            }
        }
    }

}
