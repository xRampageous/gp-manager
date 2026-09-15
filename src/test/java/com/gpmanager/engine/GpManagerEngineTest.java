package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ActionKind;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.engine.evidence.MeasuredChargeRead;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
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
        List<ItemFlow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int price = entry.getKey() == 995 ? 1 : entry.getKey() == 526 ? 31 : entry.getKey() == 555 ? 5 : 100;
            flows.add(new ItemFlow(
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
        GpManagerEngine engine = engine();

        engine.ensureSession(now);
        engine.setBaseline(snapshot(995, 1_000L));

        engine.markContext(TrackingContext.TRANSFER, 2, "Bank transfer");
        engine.markInventoryDirty();
        ProfitTransaction transfer = settle(engine, snapshot(995, 2_000L), now + 600L);

        assertNotNull(transfer);
        assertEquals(TransactionType.TRANSFER, transfer.getType());
        assertFalse(transfer.isCounted());
        assertEquals(0L, engine.getMetrics(now + 2_400L).getNet());

        engine.markInventoryDirty();
        ProfitTransaction consumption = settle(
            engine,
            snapshot(995, 1_900L),
            now + 3_000L);
        assertEquals(TransactionType.CONSUMPTION, consumption.getType());
        assertEquals(-100L, consumption.getNet());

        engine.markLootContext(
            Collections.singletonMap(100, 2L),
            2,
            "Loot from Goblin");
        engine.recordAction("Goblin");
        engine.markInventoryDirty();
        Map<Integer, Long> lootValues = new HashMap<>();
        lootValues.put(995, 1_900L);
        lootValues.put(100, 2L);
        ProfitTransaction loot = settle(
            engine,
            new ContainerSnapshot(lootValues),
            now + 5_400L);

        assertEquals(TransactionType.LOOT, loot.getType());
        assertEquals(200L, loot.getNet());
        assertEquals("Loot from Goblin", loot.getNote());

        SessionMetrics metrics = engine.getMetrics(now + 8_000L);
        assertEquals(200L, metrics.getRevenue());
        assertEquals(100L, metrics.getCosts());
        assertEquals(100L, metrics.getNet());
        assertEquals(1, metrics.getActionCount());
        assertEquals("Goblin", metrics.getActivityHint());
    }

    @Test
    public void openingInventoryIsPrimedAndNeverCountedAsProfit()
    {
        GpManagerEngine engine = engine();
        long now = 10_000L;
        engine.ensureSession(now);
        engine.beginBaselinePriming();

        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 600L));
        assertNull(engine.processIfDirty(snapshot(1205, 1L), now + 1_200L));
        assertNull(engine.processIfDirty(snapshot(1205, 1L), now + 1_800L));
        assertNull(engine.processIfDirty(snapshot(1205, 1L), now + 2_400L));

        assertEquals(0L, engine.getMetrics(now + 2_400L).getRevenue());
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());

        engine.markInventoryDirty();
        ProfitTransaction cost = settle(engine, ContainerSnapshot.empty(), now + 3_000L);
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
        GpManagerEngine engine = new GpManagerEngine(service, new TransactionClassifier(), config);
        long settleAt = 20_600L;
        engine.ensureSession(20_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markInventoryDirty();

        assertNull(engine.processIfDirty(snapshot(1942, 2L), 20_000L));
        ProfitTransaction transaction = engine.processIfDirty(snapshot(1942, 2L), settleAt);

        assertNotNull(transaction);
        assertEquals(150L, transaction.getFlows().get(0).getValueDelta());
        assertEquals(ItemPriceSource.GRAND_EXCHANGE, transaction.getFlows().get(0).getPriceSource());
        assertEquals(settleAt, transaction.getFlows().get(0).getPriceCapturedAtEpochMillis());
    }

    @Test
    public void stabilizationCancelsTemporaryEquipmentRemoval()
    {
        GpManagerEngine engine = engine();
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
        engine.markLootContext(expected, 6, "Loot from Goblin");
        engine.markInventoryDirty();
        Map<Integer, Long> finalValues = new HashMap<>();
        finalValues.put(1265, 1L);
        finalValues.put(526, 1L);

        ProfitTransaction transaction = settle(
            engine,
            new ContainerSnapshot(finalValues),
            now + 1_800L);

        assertNotNull(transaction);
        assertEquals(TransactionType.LOOT, transaction.getType());
        assertEquals(31L, transaction.getNet());
        assertEquals(1, transaction.getFlows().size());
        assertEquals(526, transaction.getFlows().get(0).getItemId());
    }

    @Test
    public void bankRemoveAndRestorePairProducesNoTransaction()
    {
        GpManagerEngine engine = engine();
        long now = 30_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(2309, 1L));

        engine.markContext(TrackingContext.TRANSFER, 6, "Bank transfer");
        engine.markInventoryDirty();
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 600L));
        assertNull(engine.processIfDirty(ContainerSnapshot.empty(), now + 1_200L));

        engine.markInventoryDirty();
        assertNull(engine.processIfDirty(snapshot(2309, 1L), now + 1_800L));
        assertNull(engine.processIfDirty(snapshot(2309, 1L), now + 2_400L));
        assertNull(engine.processIfDirty(snapshot(2309, 1L), now + 3_000L));

        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0L, engine.getMetrics(now + 3_000L).getNet());
    }

    @Test
    public void bankTransferWinsOverAnArmedChargeLoadIntent()
    {
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> {
                List<ItemFlow> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> entry : deltas.entrySet())
                {
                    String name = entry.getKey() == 12934 ? "Zulrah's scales" : "Item " + entry.getKey();
                    int price = entry.getKey() == 12934 ? 100 : 1;
                    flows.add(new ItemFlow(
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
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE, 12934, "Zulrah's scales", 8));

        // The bank's hard evidence is stronger than the narrow Use-on-item intent.
        engine.markContext(TrackingContext.TRANSFER, 8, "Bank transfer");
        engine.markInventoryDirty();
        ProfitTransaction transfer = settle(engine, snapshot(12934, 50L), now + 600L);

        assertNotNull(transfer);
        assertEquals(TransactionType.TRANSFER, transfer.getType());
        assertEquals("Bank transfer", transfer.getNote());
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertEquals(-5_000L, engine.getActiveSession().getTransactions().get(0).getNet());
    }

    @Test
    public void chargeLoadWithoutExactQuantityMovesLossIntoUncountedReviewRow()
    {
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> {
                List<ItemFlow> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> entry : deltas.entrySet())
                {
                    boolean scales = entry.getKey() == 12934;
                    flows.add(new ItemFlow(
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
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE, 12934, "Zulrah's scales",
            "149:0:9764864:12934:TOXIC_BLOWPIPE", 8));

        engine.markInventoryDirty();
        ProfitTransaction review = settle(engine, snapshot(12934, 50L), now + 600L);

        assertEquals(TransactionType.UNCERTAIN, review.getType());
        assertFalse(review.isCounted());
        assertEquals(-5_000L, review.getAutomaticNet());
        assertEquals(-5_000L, review.getNet());
        assertEquals(0L, engine.getMetrics(now + 3_000L).getNet());
        assertEquals(1, review.getFlows().size());
        assertNotNull(review.getChargeLoadReviewProvenance());
        assertEquals(ActionKind.CHARGE_LOAD_AMBIGUOUS, review.getActionKind());
        assertTrue(com.gpmanager.reward.ActionPresentation.showsOn(
            review, com.gpmanager.reward.ActionPresentation.Surface.LEDGER));
        assertFalse(com.gpmanager.reward.ActionPresentation.showsOn(
            review, com.gpmanager.reward.ActionPresentation.Surface.HUD_PLUS_TRAY));
        assertFalse(com.gpmanager.reward.ActionPresentation.showsOn(
            review, com.gpmanager.reward.ActionPresentation.Surface.LIVE_TIMELINE));
    }

    @Test
    public void expectedLootMustMatchAPositiveFlow()
    {
        GpManagerEngine engine = engine();
        long now = 40_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(995, 100L));

        engine.markLootContext(Collections.singletonMap(526, 1L), 6, "Loot from Goblin");
        engine.markInventoryDirty();
        ProfitTransaction transaction = settle(engine, snapshot(995, 200L), now + 600L);

        assertEquals(TransactionType.GAIN, transaction.getType());
        assertEquals(TrackingContext.GENERIC, transaction.getContext());
        assertEquals("", transaction.getNote());
    }

    @Test
    public void expectedLootQuantityMustMatchThePositiveFlow()
    {
        GpManagerEngine engine = engine();
        long now = 45_000L;
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());

        engine.markLootContext(Collections.singletonMap(526, 1L), 6, "Loot from Goblin");
        engine.markInventoryDirty();
        ProfitTransaction transaction = settle(engine, snapshot(526, 5L), now + 600L);

        assertEquals(TransactionType.GAIN, transaction.getType());
        assertEquals(TrackingContext.GENERIC, transaction.getContext());
        assertEquals("", transaction.getNote());
    }

    @Test
    public void unpricedInventoryChangeBypassesValueThresholdAndReachesReview()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 2; }
            @Override public int minimumTransactionValue() { return 1_000; }
        };
        FlowValuator unpricedValuator = deltas -> Collections.singletonList(
            new ItemFlow(1942, "Potato", deltas.get(1942), 0, 0, ItemPriceSource.UNPRICED));
        GpManagerEngine engine = new GpManagerEngine(
            unpricedValuator, new TransactionClassifier(), config);
        long now = 48_000L;
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markInventoryDirty();

        ProfitTransaction transaction = settle(engine, snapshot(1942, 1L), now + 600L);

        assertNotNull(transaction);
        assertEquals(TransactionType.GAIN, transaction.getType());
        assertEquals(1L, transaction.getFlows().get(0).getQuantityDelta());
        assertEquals(ItemPriceSource.UNPRICED, transaction.getFlows().get(0).getPriceSource());
    }

    @Test
    public void excludesLoggedOutTimeWithoutOverridingManualPause()
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1_000L);

        engine.pauseForLifecycle(2_000L);
        engine.resumeAfterLifecycle(12_000L);
        assertEquals(2_000L, engine.getMetrics(13_000L).getElapsedMillis());

        engine.togglePause(14_000L);
        engine.pauseForLifecycle(15_000L);
        engine.resumeAfterLifecycle(25_000L);
        assertTrue(engine.getMetrics(26_000L).isPaused());
    }

    @Test
    public void lifecycleResumeCheckDoesNotEraseRunningBaseline()
    {
        GpManagerEngine engine = engine();
        long now = 12_000L;
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());

        // Normal gameplay calls this check even when there was no lifecycle pause.
        engine.resumeAfterLifecycle(now + 100L);
        engine.markInventoryDirty();
        ProfitTransaction gain = settle(engine, snapshot(1942, 1L), now + 600L);

        assertNotNull(gain);
        assertEquals(TransactionType.GAIN, gain.getType());
    }

    @Test
    public void idlePauseSurvivesLifecycleUntilMeaningfulActivity()
    {
        GpManagerEngine engine = engine();
        engine.ensureSession(1_000L);

        engine.pauseForIdle(2_000L);
        assertTrue(engine.isIdlePaused());
        assertTrue(engine.getMetrics(3_000L).isPaused());

        engine.pauseForLifecycle(4_000L);
        engine.resumeAfterLifecycle(14_000L);

        assertTrue(engine.isIdlePaused());
        assertTrue(engine.getMetrics(15_000L).isPaused());

        engine.resumeAfterIdle(16_000L);
        assertFalse(engine.isIdlePaused());
        assertFalse(engine.getMetrics(17_000L).isPaused());
    }

    @Test
    public void delayedPartialNpcLootRemainsClassifiedAndBuildsActivityInsights()
    {
        GpManagerEngine engine = engine();
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
        ProfitTransaction bones = settle(engine, snapshot(526, 1L), now + 7_000L);
        assertEquals(TransactionType.LOOT, bones.getType());
        assertEquals("Goblin", bones.getActivityName());

        Map<Integer, Long> allLoot = new HashMap<>();
        allLoot.put(526, 1L);
        allLoot.put(555, 6L);
        engine.markInventoryDirty();
        ProfitTransaction runes = settle(engine, new ContainerSnapshot(allLoot), now + 10_000L);
        assertEquals(TransactionType.LOOT, runes.getType());
        assertEquals("Goblin", runes.getActivityName());

        assertEquals(1, engine.getActivityBreakdown().size());
        assertEquals(1, engine.getActivityBreakdown().get(0).getActionCount());
        assertEquals(61L, engine.getActivityBreakdown().get(0).getNet());
        assertEquals(61L, engine.getActivityBreakdown().get(0).getProfitPerAction());
    }

    private GpManagerEngine engine()
    {
        return new GpManagerEngine(
            valuator,
            new TransactionClassifier(),
            CONFIG);
    }

    private static ProfitTransaction settle(
        GpManagerEngine engine,
        ContainerSnapshot snapshot,
        long firstTick)
    {
        ProfitTransaction transaction = engine.processIfDirty(snapshot, firstTick);
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
        GpManagerEngine engine = engine();
        long now = 70_000L;
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());

        engine.markPkLootContext(
            Collections.singletonMap(526, 1L),
            50,
            "Player kill",
            now + 600L);
        engine.markInventoryDirty();
        ProfitTransaction loot = settle(engine, snapshot(526, 1L), now + 1_200L);

        assertEquals(TransactionType.PK_LOOT, loot.getType());
        assertEquals(ClassificationConfidence.CONFIRMED, loot.getConfidence());
        assertFalse(loot.getEncounterId().isEmpty());
        assertEquals(1, engine.getPkMetrics().getKills());
        assertEquals(31L, engine.getPkMetrics().getNet());
    }

    @Test
    public void pkKillAndDeathEncountersCopyPresentationOnlyLocation()
    {
        GpManagerEngine engine = engine();
        long now = 72_000L;
        engine.ensureSession(now);
        engine.updatePlayerWorldLocation(100, 200, 0, "Wilderness");
        engine.markPkLootContext(Collections.emptyMap(), 10, "Player kill", now + 1L);
        assertEquals("Wilderness", engine.getActiveSession().getPkEncounters()
            .get(engine.getActiveSession().getPkEncounters().size() - 1).getLocationLabel());

        engine.updatePlayerWorldLocation(300, 400, 0, "Corrupted Gauntlet");
        engine.markPkDeath("Player death", now + 2L);
        assertEquals("Corrupted Gauntlet", engine.getActiveSession().getPkEncounters()
            .get(engine.getActiveSession().getPkEncounters().size() - 1).getLocationLabel());
    }

    @Test
    public void genericMixedChangeIsUncertainAndExcludedByDefault()
    {
        GpManagerEngine engine = engine();
        long now = 80_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(995, 100L));

        Map<Integer, Long> after = new HashMap<>();
        after.put(995, 50L);
        after.put(526, 2L);
        engine.markInventoryDirty();
        ProfitTransaction transaction = settle(engine, new ContainerSnapshot(after), now + 600L);

        assertEquals(TransactionType.UNCERTAIN, transaction.getType());
        assertEquals(ClassificationConfidence.UNCERTAIN, transaction.getConfidence());
        assertFalse(transaction.isCounted());
        assertEquals(0L, engine.getMetrics(now + 3_000L).getNet());
    }


    @Test
    public void confirmedPkDeathCreatesLossEncounter()
    {
        GpManagerEngine engine = engine();
        long now = 90_000L;
        engine.ensureSession(now);
        engine.setBaseline(snapshot(1265, 1L));

        engine.markPkDeath("Player death", now + 600L,
            LocalDeathEvidence.capture(null, false, SkullIcon.SKULL, null, null));
        engine.markInventoryDirty();
        ProfitTransaction loss = settle(engine, ContainerSnapshot.empty(), now + 1_200L);

        assertEquals(TransactionType.PK_DEATH_LOSS, loss.getType());
        assertEquals(ClassificationConfidence.CONFIRMED, loss.getConfidence());
        assertEquals(-100L, loss.getNet());
        assertTrue(loss.getExplanation().contains("Death evidence"));
        assertTrue(loss.getExplanation(), loss.getExplanation().contains("lost: Item 1265"));
        assertEquals(1, engine.getPkMetrics().getDeaths());
        assertEquals(100L, engine.getPkMetrics().getLargestDeathLoss());
    }

}
