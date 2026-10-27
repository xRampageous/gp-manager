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
        List<Flow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int price = entry.getKey() == 995 ? 1 : entry.getKey() == 526 ? 31 : entry.getKey() == 555 ? 5 : 100;
            flows.add(new Flow(
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
        Engine engine = engine();

        engine.ensureSession(now);
        engine.setBaseline(snapshot(995, 1_000L));

        engine.markContext(Context.TRANSFER, 2, "Bank transfer");
        engine.markDirty();
        Transaction transfer = settle(engine, snapshot(995, 2_000L), now + 600L);

        assertNotNull(transfer);
        assertEquals(TransactionType.TRANSFER, transfer.type());
        assertFalse(transfer.counts());
        assertEquals(0L, engine.metrics(now + 2_400L).net);

        engine.markDirty();
        Transaction consumption = settle(
            engine,
            snapshot(995, 1_900L),
            now + 3_000L);
        assertEquals(TransactionType.CONSUMPTION, consumption.type());
        assertEquals(-100L, consumption.net());

        engine.markLoot(
            Collections.singletonMap(100, 2L),
            2,
            "Loot from Goblin",
            "Goblin");
        engine.recordAction("Goblin");
        engine.markDirty();
        Map<Integer, Long> lootValues = new HashMap<>();
        lootValues.put(995, 1_900L);
        lootValues.put(100, 2L);
        Transaction loot = settle(
            engine,
            new ContainerSnapshot(lootValues),
            now + 5_400L);

        assertEquals(TransactionType.LOOT, loot.type());
        assertEquals(200L, loot.net());
        assertEquals("Loot from Goblin", loot.note());

        Metrics metrics = engine.metrics(now + 8_000L);
        assertEquals(200L, metrics.income);
        assertEquals(100L, metrics.cost);
        assertEquals(100L, metrics.net);
        assertEquals("Goblin", metrics.activity);
    }

    @Test
    public void openingInventoryIsPrimedAndNeverCountedAsProfit()
    {
        Engine engine = engine();
        long now = 10_000L;
        engine.ensureSession(now);
        engine.beginPriming();

        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 600L, null));
        assertNull(engine.processIfDirty(snapshot(1205, 1L), now + 1_200L, null));
        assertNull(engine.processIfDirty(snapshot(1205, 1L), now + 1_800L, null));
        assertNull(engine.processIfDirty(snapshot(1205, 1L), now + 2_400L, null));

        assertEquals(0L, engine.metrics(now + 2_400L).income);
        assertTrue(engine.session().transactions().isEmpty());

        engine.markDirty();
        Transaction cost = settle(engine, ContainerSnapshot.empty(), now + 3_000L);
        assertEquals(TransactionType.CONSUMPTION, cost.type());
        assertEquals(-100L, cost.net());
    }

    @Test
    public void settledItemFlowUsesThePriceObservationTimestamp()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public int stabilizationTicks() { return 1; }
        };
        Valuation service = ModelProbe.valuation(config, id -> null, id -> 75);
        Engine engine = new Engine(service, new TransactionClassifier(), config);
        long settleAt = 20_600L;
        engine.ensureSession(20_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markDirty();

        assertNull(engine.processIfDirty(snapshot(1942, 2L), 20_000L, null));
        Transaction transaction = engine.processIfDirty(snapshot(1942, 2L), settleAt, null);

        assertNotNull(transaction);
        assertEquals(150L, transaction.flows().get(0).value);
        assertEquals(PriceSource.GRAND_EXCHANGE, transaction.flows().get(0).source());
        assertEquals(settleAt, transaction.flows().get(0).pricedAt());
    }

    @Test
    public void stabilizationCancelsTemporaryEquipmentRemoval()
    {
        Engine engine = engine();
        long now = 20_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(1265, 1L));

        // Equipment disappears first.
        engine.markDirty();
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 600L, null));
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 1_200L, null));

        // Loot arrives while the equipment is restored. The pending transaction
        // is recalculated from the original baseline, so the pickaxe nets to zero.
        Map<Integer, Long> expected = Collections.singletonMap(526, 1L);
        engine.markLoot(expected, 6, "Loot from Goblin", "Goblin");
        engine.markDirty();
        Map<Integer, Long> finalValues = new HashMap<>();
        finalValues.put(1265, 1L);
        finalValues.put(526, 1L);

        Transaction transaction = settle(
            engine,
            new ContainerSnapshot(finalValues),
            now + 1_800L);

        assertNotNull(transaction);
        assertEquals(TransactionType.LOOT, transaction.type());
        assertEquals(31L, transaction.net());
        assertEquals(1, transaction.flows().size());
        assertEquals(526, transaction.flows().get(0).item);
    }

    @Test
    public void bankRemoveAndRestorePairProducesNoTransaction()
    {
        Engine engine = engine();
        long now = 30_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(2309, 1L));

        engine.markContext(Context.TRANSFER, 6, "Bank transfer");
        engine.markDirty();
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 600L, null));
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 1_200L, null));

        engine.markDirty();
        assertNull(engine.processIfDirty(snapshot(2309, 1L), now + 1_800L, null));
        assertNull(engine.processIfDirty(snapshot(2309, 1L), now + 2_400L, null));
        assertNull(engine.processIfDirty(snapshot(2309, 1L), now + 3_000L, null));

        assertTrue(engine.session().transactions().isEmpty());
        assertEquals(0L, engine.metrics(now + 3_000L).net);
    }

    @Test
    public void bankTransferWinsOverAnArmedChargeLoadIntent()
    {
        Engine engine = new Engine(
            deltas -> {
                List<Flow> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> entry : deltas.entrySet())
                {
                    String name = entry.getKey() == 12934 ? "Zulrah's scales" : "Item " + entry.getKey();
                    int price = entry.getKey() == 12934 ? 100 : 1;
                    flows.add(new Flow(
                        entry.getKey(), name, entry.getValue(), price, entry.getValue() * price));
                }
                return flows;
            },
            new TransactionClassifier(),
            CONFIG);
        long now = 33_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(12934, 100L));
        assertTrue(engine.markChargeLoad(
            ChargeCheck.ChargedItem.BLOWPIPE, 12934, "Zulrah's scales", null, 8));

        // The bank's hard evidence is stronger than the narrow Use-on-item intent.
        engine.markContext(Context.TRANSFER, 8, "Bank transfer");
        engine.markDirty();
        Transaction transfer = settle(engine, snapshot(12934, 50L), now + 600L);

        assertNotNull(transfer);
        assertEquals(TransactionType.TRANSFER, transfer.type());
        assertEquals("Bank transfer", transfer.note());
        assertEquals(1, engine.session().transactions().size());
        assertEquals(-5_000L, engine.session().transactions().get(0).net());
    }

    @Test
    public void chargeLoadWithoutExactQuantityMovesLossIntoUncountedReviewRow()
    {
        Engine engine = new Engine(
            deltas -> {
                List<Flow> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> entry : deltas.entrySet())
                {
                    boolean scales = entry.getKey() == 12934;
                    flows.add(new Flow(
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
        engine.ensureSession(now);
        engine.setBaseline(snapshot(12934, 100L));
        assertTrue(engine.markChargeLoad(
            ChargeCheck.ChargedItem.BLOWPIPE, 12934, "Zulrah's scales",
            "149:0:9764864:12934:BLOWPIPE", 8));

        engine.markDirty();
        Transaction load = settle(engine, snapshot(12934, 50L), now + 600L);

        assertEquals("a validated load moves value, never a cost", TransactionType.TRANSFER, load.type());
        assertFalse(load.counts());
        assertEquals("Charge load transfer", load.note());
        assertEquals(-5_000L, load.autoNet());
        assertEquals(-5_000L, load.net());
        assertEquals(0L, engine.metrics(now + 3_000L).net);
        assertEquals(1, load.flows().size());
        assertFalse(new com.google.gson.Gson().toJson(load).contains("chargeLoadReviewProvenance"));
        assertEquals(ActionKind.CHARGE_LOAD_AMBIGUOUS, load.action());
    }

    @Test
    public void expectedLootMustMatchAPositiveFlow()
    {
        Engine engine = engine();
        long now = 40_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(995, 100L));

        engine.markLoot(Collections.singletonMap(526, 1L), 6, "Loot from Goblin", "Goblin");
        engine.markDirty();
        Transaction transaction = settle(engine, snapshot(995, 200L), now + 600L);

        assertEquals(TransactionType.GAIN, transaction.type());
        assertEquals(Context.GENERIC, transaction.context());
        assertEquals("", transaction.note());
    }

    @Test
    public void expectedLootQuantityMustMatchThePositiveFlow()
    {
        Engine engine = engine();
        long now = 45_000L;
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());

        engine.markLoot(Collections.singletonMap(526, 1L), 6, "Loot from Goblin", "Goblin");
        engine.markDirty();
        Transaction transaction = settle(engine, snapshot(526, 5L), now + 600L);

        assertEquals(TransactionType.GAIN, transaction.type());
        assertEquals(Context.GENERIC, transaction.context());
        assertEquals("", transaction.note());
    }

    @Test
    public void unpricedInventoryChangeReachesReviewInsteadOfBecomingZeroGp()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 2; }
        };
        FlowValuator unpricedValuator = deltas -> Collections.singletonList(
            new Flow(1942, "Potato", deltas.get(1942), 0, 0, PriceSource.UNPRICED));
        Engine engine = new Engine(
            unpricedValuator, new TransactionClassifier(), config);
        long now = 48_000L;
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markDirty();

        Transaction transaction = settle(engine, snapshot(1942, 1L), now + 600L);

        assertNotNull(transaction);
        assertEquals(TransactionType.GAIN, transaction.type());
        assertEquals(1L, transaction.flows().get(0).quantity);
        assertEquals(PriceSource.UNPRICED, transaction.flows().get(0).source());
    }

    @Test
    public void excludesLoggedOutTimeWithoutOverridingManualPause()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);

        engine.pauseForLifecycle(2_000L);
        engine.resume(12_000L, PauseReason.LIFECYCLE);
        assertEquals(2_000L, engine.metrics(13_000L).elapsed);

        engine.togglePause(14_000L);
        engine.pauseForLifecycle(15_000L);
        engine.resume(25_000L, PauseReason.LIFECYCLE);
        assertTrue(engine.metrics(26_000L).paused);
    }

    @Test
    public void lifecycleResumeCheckDoesNotEraseRunningBaseline()
    {
        Engine engine = engine();
        long now = 12_000L;
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());

        // Normal gameplay calls this check even when there was no lifecycle pause.
        engine.resume(now + 100L, PauseReason.LIFECYCLE);
        engine.markDirty();
        Transaction gain = settle(engine, snapshot(1942, 1L), now + 600L);

        assertNotNull(gain);
        assertEquals(TransactionType.GAIN, gain.type());
    }

    @Test
    public void idlePauseSurvivesLifecycleUntilMeaningfulActivity()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);

        engine.session().pause(2_000L, PauseReason.IDLE);
        assertTrue(engine.isIdlePaused());
        assertTrue(engine.metrics(3_000L).paused);

        engine.pauseForLifecycle(4_000L);
        engine.resume(14_000L, PauseReason.LIFECYCLE);

        assertTrue(engine.isIdlePaused());
        assertTrue(engine.metrics(15_000L).paused);

        engine.resume(16_000L, PauseReason.IDLE);
        assertFalse(engine.isIdlePaused());
        assertFalse(engine.metrics(17_000L).paused);
    }

    @Test
    public void delayedPartialNpcLootRemainsClassified()
    {
        Engine engine = engine();
        long now = 50_000L;
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());

        Map<Integer, Long> expected = new HashMap<>();
        expected.put(526, 1L);
        expected.put(555, 6L);
        engine.recordAction("Goblin");
        engine.markLoot(expected, 50, "Loot from Goblin", "Goblin");

        for (int tick = 0; tick < 10; tick++)
        {
            assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 600L * (tick + 1), null));
        }

        engine.markDirty();
        Transaction bones = settle(engine, snapshot(526, 1L), now + 7_000L);
        assertEquals(TransactionType.LOOT, bones.type());
        assertEquals("Goblin", bones.activityName());

        Map<Integer, Long> allLoot = new HashMap<>();
        allLoot.put(526, 1L);
        allLoot.put(555, 6L);
        engine.markDirty();
        Transaction runes = settle(engine, new ContainerSnapshot(allLoot), now + 10_000L);
        assertEquals(TransactionType.LOOT, runes.type());
        assertEquals("Goblin", runes.activityName());

    }

    private Engine engine()
    {
        return new Engine(
            valuator,
            new TransactionClassifier(),
            CONFIG);
    }

    private static Transaction settle(
        Engine engine,
        ContainerSnapshot snapshot,
        long firstTick)
    {
        Transaction transaction = engine.processIfDirty(snapshot, firstTick, null);
        assertNull(transaction);
        transaction = engine.processIfDirty(snapshot, firstTick + 600L, null);
        assertNull(transaction);
        return engine.processIfDirty(snapshot, firstTick + 1_200L, null);
    }

    private static ContainerSnapshot snapshot(int itemId, long quantity)
    {
        Map<Integer, Long> values = new HashMap<>();
        if (quantity > 0L)
        {
            values.put(itemId, quantity);
        }
        return new ContainerSnapshot(values);
    }

    @Test
    public void playerLootCreatesConfirmedPkEncounter()
    {
        Engine engine = engine();
        long now = 70_000L;
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());

        engine.markPkLoot(
            Collections.singletonMap(526, 1L),
            50,
            now + 600L);
        engine.markDirty();
        Transaction loot = settle(engine, snapshot(526, 1L), now + 1_200L);

        assertEquals(TransactionType.PK_LOOT, loot.type());
        assertEquals(Confidence.CONFIRMED, loot.confidence());
        assertFalse(loot.encounterId().isEmpty());
        assertEquals(1, engine.session().pk().kills);
        assertEquals(31L, engine.bestKill());
    }

    @Test
    public void pkKillAndDeathEncountersRecordFactsButNeverALocation()
    {
        Engine engine = engine();
        long now = 72_000L;
        engine.ensureSession(now);
        engine.markPkLoot(Collections.emptyMap(), 10, now + 1L);
        engine.markPkDeath(now + 2L, java.util.Collections.emptyMap(), null, engine.events.owner);

        assertEquals(1, engine.session().pk().kills);
        assertEquals(1, engine.session().pk().deaths);
        String json = new com.google.gson.Gson().toJson(engine.session());
        assertFalse("location is a detection signal only, never a persisted PvP fact", json.contains("Wilderness"));
        assertFalse(json.contains("Gauntlet"));
        assertFalse(json.contains("locationLabel"));
    }

    @Test
    public void genericMixedChangeIsUncertainAndExcludedByDefault()
    {
        Engine engine = engine();
        long now = 80_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(995, 100L));

        Map<Integer, Long> after = new HashMap<>();
        after.put(995, 50L);
        after.put(526, 2L);
        engine.markDirty();
        Transaction transaction = settle(engine, new ContainerSnapshot(after), now + 600L);

        assertEquals(TransactionType.UNCERTAIN, transaction.type());
        assertEquals(Confidence.UNCERTAIN, transaction.confidence());
        assertFalse(transaction.counts());
        assertEquals(0L, engine.metrics(now + 3_000L).net);
    }


    @Test
    public void confirmedPkDeathCreatesLossEncounter()
    {
        Engine engine = engine();
        long now = 90_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(1265, 1L));

        engine.markPkDeath(now + 600L, java.util.Collections.emptyMap(), null, engine.events.owner);
        engine.markDirty();
        Transaction loss = settle(engine, ContainerSnapshot.empty(), now + 1_200L);

        assertEquals(TransactionType.PK_DEATH_LOSS, loss.type());
        assertEquals(Confidence.CONFIRMED, loss.confidence());
        assertEquals(-100L, loss.net());
        assertEquals(1, engine.session().pk().deaths);
        assertFalse(loss.encounterId().isEmpty());
    }

    /** Guice calls every @Inject method when it builds the engine; only setters may carry it. */
    @Test
    public void onlySettersAreInjected()
    {
        for (java.lang.reflect.Method method : Engine.class.getDeclaredMethods())
        {
            if (method.isAnnotationPresent(javax.inject.Inject.class))
            {
                assertTrue(method.getName(), method.getName().startsWith("set"));
            }
        }
    }

}
