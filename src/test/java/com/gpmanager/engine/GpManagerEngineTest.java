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
        engine.markInventoryDirty();
        Transaction transfer = settle(engine, snapshot(995, 2_000L), now + 600L);

        assertNotNull(transfer);
        assertEquals(TransactionType.TRANSFER, transfer.getType());
        assertFalse(transfer.isCounted());
        assertEquals(0L, engine.getMetrics(now + 2_400L).net);

        engine.markInventoryDirty();
        Transaction consumption = settle(
            engine,
            snapshot(995, 1_900L),
            now + 3_000L);
        assertEquals(TransactionType.CONSUMPTION, consumption.getType());
        assertEquals(-100L, consumption.getNet());

        engine.markLootContext(
            Collections.singletonMap(100, 2L),
            2,
            "Loot from Goblin",
            "Goblin");
        engine.recordAction("Goblin");
        engine.markInventoryDirty();
        Map<Integer, Long> lootValues = new HashMap<>();
        lootValues.put(995, 1_900L);
        lootValues.put(100, 2L);
        Transaction loot = settle(
            engine,
            new ContainerSnapshot(lootValues),
            now + 5_400L);

        assertEquals(TransactionType.LOOT, loot.getType());
        assertEquals(200L, loot.getNet());
        assertEquals("Loot from Goblin", loot.getNote());

        SessionMetrics metrics = engine.getMetrics(now + 8_000L);
        assertEquals(200L, metrics.revenue);
        assertEquals(100L, metrics.costs);
        assertEquals(100L, metrics.net);
        assertEquals(1, engine.getActiveSession().actionCount);
        assertEquals("Goblin", metrics.activityHint);
    }

    @Test
    public void openingInventoryIsPrimedAndNeverCountedAsProfit()
    {
        Engine engine = engine();
        long now = 10_000L;
        engine.ensureSession(now);
        engine.beginBaselinePriming();

        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 600L));
        assertNull(engine.processIfDirty(snapshot(1205, 1L), now + 1_200L));
        assertNull(engine.processIfDirty(snapshot(1205, 1L), now + 1_800L));
        assertNull(engine.processIfDirty(snapshot(1205, 1L), now + 2_400L));

        assertEquals(0L, engine.getMetrics(now + 2_400L).revenue);
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());

        engine.markInventoryDirty();
        Transaction cost = settle(engine, ContainerSnapshot.empty(), now + 3_000L);
        assertEquals(TransactionType.CONSUMPTION, cost.getType());
        assertEquals(-100L, cost.getNet());
    }

    @Test
    public void settledItemFlowUsesThePriceObservationTimestamp()
    {
        GpManagerConfig config = new GpManagerConfig() {
            @Override public int stabilizationTicks() { return 1; }
        };
        ItemValuationService service = new ItemValuationService(config, id -> null, id -> 75);
        Engine engine = new Engine(service, new TransactionClassifier(), config);
        long settleAt = 20_600L;
        engine.ensureSession(20_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markInventoryDirty();

        assertNull(engine.processIfDirty(snapshot(1942, 2L), 20_000L));
        Transaction transaction = engine.processIfDirty(snapshot(1942, 2L), settleAt);

        assertNotNull(transaction);
        assertEquals(150L, transaction.getFlows().get(0).valueDelta);
        assertEquals(PriceSource.GRAND_EXCHANGE, transaction.getFlows().get(0).getPriceSource());
        assertEquals(settleAt, transaction.getFlows().get(0).getPriceCapturedAtEpochMillis());
    }

    @Test
    public void stabilizationCancelsTemporaryEquipmentRemoval()
    {
        Engine engine = engine();
        long now = 20_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(1265, 1L));

        // Equipment disappears first.
        engine.markInventoryDirty();
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 600L));
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 1_200L));

        // Loot arrives while the equipment is restored. The pending transaction
        // is recalculated from the original baseline, so the pickaxe nets to zero.
        Map<Integer, Long> expected = Collections.singletonMap(526, 1L);
        engine.markLootContext(expected, 6, "Loot from Goblin", "Goblin");
        engine.markInventoryDirty();
        Map<Integer, Long> finalValues = new HashMap<>();
        finalValues.put(1265, 1L);
        finalValues.put(526, 1L);

        Transaction transaction = settle(
            engine,
            new ContainerSnapshot(finalValues),
            now + 1_800L);

        assertNotNull(transaction);
        assertEquals(TransactionType.LOOT, transaction.getType());
        assertEquals(31L, transaction.getNet());
        assertEquals(1, transaction.getFlows().size());
        assertEquals(526, transaction.getFlows().get(0).itemId);
    }

    @Test
    public void bankRemoveAndRestorePairProducesNoTransaction()
    {
        Engine engine = engine();
        long now = 30_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(2309, 1L));

        engine.markContext(Context.TRANSFER, 6, "Bank transfer");
        engine.markInventoryDirty();
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 600L));
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 1_200L));

        engine.markInventoryDirty();
        assertNull(engine.processIfDirty(snapshot(2309, 1L), now + 1_800L));
        assertNull(engine.processIfDirty(snapshot(2309, 1L), now + 2_400L));
        assertNull(engine.processIfDirty(snapshot(2309, 1L), now + 3_000L));

        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0L, engine.getMetrics(now + 3_000L).net);
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
        assertTrue(engine.markChargeLoadTransfer(
            ChargeRead.Variant.V1b, 12934, "Zulrah's scales", null, 8));

        // The bank's hard evidence is stronger than the narrow Use-on-item intent.
        engine.markContext(Context.TRANSFER, 8, "Bank transfer");
        engine.markInventoryDirty();
        Transaction transfer = settle(engine, snapshot(12934, 50L), now + 600L);

        assertNotNull(transfer);
        assertEquals(TransactionType.TRANSFER, transfer.getType());
        assertEquals("Bank transfer", transfer.getNote());
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertEquals(-5_000L, engine.getActiveSession().getTransactions().get(0).getNet());
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
        assertTrue(engine.markChargeLoadTransfer(
            ChargeRead.Variant.V1b, 12934, "Zulrah's scales",
            "149:0:9764864:12934:V1b", 8));

        engine.markInventoryDirty();
        Transaction load = settle(engine, snapshot(12934, 50L), now + 600L);

        assertEquals("a validated load moves value, never a cost", TransactionType.TRANSFER, load.getType());
        assertFalse(load.isCounted());
        assertEquals("Charge load transfer", load.getNote());
        assertEquals(-5_000L, load.getAutomaticNet());
        assertEquals(-5_000L, load.getNet());
        assertEquals(0L, engine.getMetrics(now + 3_000L).net);
        assertEquals(1, load.getFlows().size());
        assertFalse(new com.google.gson.Gson().toJson(load).contains("chargeLoadReviewProvenance"));
        assertEquals(ActionKind.CHARGE_LOAD_AMBIGUOUS, load.getActionKind());
    }

    @Test
    public void expectedLootMustMatchAPositiveFlow()
    {
        Engine engine = engine();
        long now = 40_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(995, 100L));

        engine.markLootContext(Collections.singletonMap(526, 1L), 6, "Loot from Goblin", "Goblin");
        engine.markInventoryDirty();
        Transaction transaction = settle(engine, snapshot(995, 200L), now + 600L);

        assertEquals(TransactionType.GAIN, transaction.getType());
        assertEquals(Context.GENERIC, transaction.getContext());
        assertEquals("", transaction.getNote());
    }

    @Test
    public void expectedLootQuantityMustMatchThePositiveFlow()
    {
        Engine engine = engine();
        long now = 45_000L;
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());

        engine.markLootContext(Collections.singletonMap(526, 1L), 6, "Loot from Goblin", "Goblin");
        engine.markInventoryDirty();
        Transaction transaction = settle(engine, snapshot(526, 5L), now + 600L);

        assertEquals(TransactionType.GAIN, transaction.getType());
        assertEquals(Context.GENERIC, transaction.getContext());
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
            new Flow(1942, "Potato", deltas.get(1942), 0, 0, PriceSource.UNPRICED));
        Engine engine = new Engine(
            unpricedValuator, new TransactionClassifier(), config);
        long now = 48_000L;
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markInventoryDirty();

        Transaction transaction = settle(engine, snapshot(1942, 1L), now + 600L);

        assertNotNull(transaction);
        assertEquals(TransactionType.GAIN, transaction.getType());
        assertEquals(1L, transaction.getFlows().get(0).quantityDelta);
        assertEquals(PriceSource.UNPRICED, transaction.getFlows().get(0).getPriceSource());
    }

    @Test
    public void excludesLoggedOutTimeWithoutOverridingManualPause()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);

        engine.pauseForLifecycle(2_000L);
        engine.resume(12_000L, PauseReason.LIFECYCLE);
        assertEquals(2_000L, engine.getMetrics(13_000L).elapsedMillis);

        engine.togglePause(14_000L);
        engine.pauseForLifecycle(15_000L);
        engine.resume(25_000L, PauseReason.LIFECYCLE);
        assertTrue(engine.getMetrics(26_000L).paused);
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
        engine.markInventoryDirty();
        Transaction gain = settle(engine, snapshot(1942, 1L), now + 600L);

        assertNotNull(gain);
        assertEquals(TransactionType.GAIN, gain.getType());
    }

    @Test
    public void idlePauseSurvivesLifecycleUntilMeaningfulActivity()
    {
        Engine engine = engine();
        engine.ensureSession(1_000L);

        engine.pauseForIdle(2_000L, 2_000L);
        assertTrue(engine.isIdlePaused());
        assertTrue(engine.getMetrics(3_000L).paused);

        engine.pauseForLifecycle(4_000L);
        engine.resume(14_000L, PauseReason.LIFECYCLE);

        assertTrue(engine.isIdlePaused());
        assertTrue(engine.getMetrics(15_000L).paused);

        engine.resume(16_000L, PauseReason.IDLE);
        assertFalse(engine.isIdlePaused());
        assertFalse(engine.getMetrics(17_000L).paused);
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
        engine.markLootContext(expected, 50, "Loot from Goblin", "Goblin");

        for (int tick = 0; tick < 10; tick++)
        {
            assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 600L * (tick + 1)));
        }

        engine.markInventoryDirty();
        Transaction bones = settle(engine, snapshot(526, 1L), now + 7_000L);
        assertEquals(TransactionType.LOOT, bones.getType());
        assertEquals("Goblin", bones.getActivityName());

        Map<Integer, Long> allLoot = new HashMap<>();
        allLoot.put(526, 1L);
        allLoot.put(555, 6L);
        engine.markInventoryDirty();
        Transaction runes = settle(engine, new ContainerSnapshot(allLoot), now + 10_000L);
        assertEquals(TransactionType.LOOT, runes.getType());
        assertEquals("Goblin", runes.getActivityName());

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
        Transaction transaction = engine.processIfDirty(snapshot, firstTick);
        assertNull(transaction);
        transaction = engine.processIfDirty(snapshot, firstTick + 600L);
        assertNull(transaction);
        return engine.processIfDirty(snapshot, firstTick + 1_200L);
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

        engine.markPkLootContext(
            Collections.singletonMap(526, 1L),
            50,
            "Player kill",
            now + 600L);
        engine.markInventoryDirty();
        Transaction loot = settle(engine, snapshot(526, 1L), now + 1_200L);

        assertEquals(TransactionType.PK_LOOT, loot.getType());
        assertEquals(ClassificationConfidence.CONFIRMED, loot.getConfidence());
        assertFalse(loot.getEncounterId().isEmpty());
        assertEquals(1, engine.getPkMetrics().kills);
        assertEquals(31L, engine.getPkMetrics().net);
    }

    @Test
    public void pkKillAndDeathEncountersRecordFactsButNeverALocation()
    {
        Engine engine = engine();
        long now = 72_000L;
        engine.ensureSession(now);
        engine.updatePlayerWorldLocation(100, 200, 0);
        engine.markPkLootContext(Collections.emptyMap(), 10, "Player kill", now + 1L);
        engine.updatePlayerWorldLocation(300, 400, 0);
        engine.markPkDeath("Player death", now + 2L, null);

        java.util.List<PkEncounter> encounters = engine.getActiveSession().getPkEncounters();
        assertEquals(2, encounters.size());
        assertEquals(EncounterType.KILL, encounters.get(0).getType());
        assertEquals(EncounterType.DEATH, encounters.get(1).getType());
        assertEquals(1, engine.getActiveSession().pkMetrics().kills);
        assertEquals(1, engine.getActiveSession().pkMetrics().deaths);
        String json = new com.google.gson.Gson().toJson(encounters);
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
        engine.markInventoryDirty();
        Transaction transaction = settle(engine, new ContainerSnapshot(after), now + 600L);

        assertEquals(TransactionType.UNCERTAIN, transaction.getType());
        assertEquals(ClassificationConfidence.UNCERTAIN, transaction.getConfidence());
        assertFalse(transaction.isCounted());
        assertEquals(0L, engine.getMetrics(now + 3_000L).net);
    }


    @Test
    public void confirmedPkDeathCreatesLossEncounter()
    {
        Engine engine = engine();
        long now = 90_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(1265, 1L));

        engine.markPkDeath("Player death", now + 600L,
            LocalDeathEvidence.capture(null, false, SkullIcon.SKULL, null, null));
        engine.markInventoryDirty();
        Transaction loss = settle(engine, ContainerSnapshot.empty(), now + 1_200L);

        assertEquals(TransactionType.PK_DEATH_LOSS, loss.getType());
        assertEquals(ClassificationConfidence.CONFIRMED, loss.getConfidence());
        assertEquals(-100L, loss.getNet());
        assertTrue(loss.getExplanation().contains("Death evidence"));
        assertTrue(loss.getExplanation(), loss.getExplanation().contains("lost: Item 1265"));
        assertEquals(1, engine.getPkMetrics().deaths);
        assertEquals(100L, engine.getPkMetrics().largestDeathLoss);
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
