package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.reward.RewardSourceKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Factory / data reset must not leave tracking dead for everything except
 * ground pickups. Covers auto-start re-arm, skilling inventory GAIN, and
 * NPC-loot-style settlement after a fresh slate.
 */
public class FactoryResetActivityCoverageTest
{
    private static final int OAK_LOGS = 1521;
    private static final int COINS = 995;

    private GpManagerEngine engine(AtomicBoolean autoStart)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public boolean autoStartSession()
            {
                return autoStart.get();
            }

            @Override
            public int stabilizationTicks()
            {
                return 0;
            }

            @Override
            public int minimumTransactionValue()
            {
                return 1;
            }
        };
        return new GpManagerEngine(
            deltas ->
            {
                List<ItemFlow> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> entry : deltas.entrySet())
                {
                    int id = entry.getKey();
                    long qty = entry.getValue();
                    int price = id == COINS ? 1 : id == OAK_LOGS ? 40 : 100;
                    flows.add(new ItemFlow(id, "Item " + id, qty, price, qty * price));
                }
                return flows;
            },
            new TransactionClassifier(),
            config);
    }

    private GpManagerEngine engine(boolean autoStart)
    {
        return engine(new AtomicBoolean(autoStart));
    }

    private ProfitTransaction settle(GpManagerEngine engine, ContainerSnapshot snapshot, long now)
    {
        assertNull(engine.processIfDirty(snapshot, now));
        return engine.processIfDirty(snapshot, now + 600L);
    }

    @Test
    public void resetWithAutoStartLeavesRunningPrimedGeneral()
    {
        GpManagerEngine engine = engine(true);
        engine.ensureSession(1_000L);
        engine.resetTrackingData(2_000L);
        assertNotNull(engine.getActiveSession());
        assertFalse(engine.isStopped());
        assertFalse(engine.getActiveSession().isPaused());
        assertTrue(engine.isBaselinePriming());
        assertFalse(engine.isAutoStartEligibleAfterReset());
    }

    @Test
    public void resetWithoutAutoStartStopsButAllowsWakeAfterEnablingAutoStart()
    {
        AtomicBoolean autoStart = new AtomicBoolean(false);
        GpManagerEngine engine = engine(autoStart);
        engine.ensureSession(1_000L);
        engine.resetTrackingData(2_000L);
        assertTrue(engine.isStopped());
        assertTrue(engine.isAutoStartEligibleAfterReset());
        assertFalse(engine.startGeneralFromActivityIfNeeded(2_500L));

        autoStart.set(true);
        assertTrue(engine.startGeneralFromActivityIfNeeded(3_000L));
        assertFalse(engine.isStopped());
        assertTrue(engine.isBaselinePriming());
        assertFalse(engine.isAutoStartEligibleAfterReset());
    }

    @Test
    public void ordinaryStopDoesNotWakeOnGameplayEvenWithAutoStart()
    {
        GpManagerEngine engine = engine(true);
        engine.ensureSession(1_000L);
        engine.stop(2_000L);
        assertTrue(engine.isStopped());
        assertFalse(engine.isAutoStartEligibleAfterReset());
        assertFalse(engine.startGeneralFromActivityIfNeeded(3_000L));
        assertTrue(engine.isStopped());
    }

    @Test
    public void restartGeneralStillRequiresExplicitResume()
    {
        GpManagerEngine engine = engine(true);
        engine.ensureSession(1_000L);
        assertTrue(engine.restartGeneral(2_000L, false, false));
        assertTrue(engine.isStopped());
        assertFalse(engine.isAutoStartEligibleAfterReset());
        assertFalse(engine.startGeneralFromActivityIfNeeded(3_000L));
        String emptyRunId = engine.getActiveSession().getRuns(3_000L).get(0).getId();
        engine.togglePause(3_000L);
        assertFalse(engine.isStopped());
        assertEquals(1, engine.getActiveSession().getRuns(3_000L).size());
        assertEquals(emptyRunId, engine.getActiveSession().getRuns(3_000L).get(0).getId());
    }

    @Test
    public void afterResetSkillingInventoryGainAndNpcLootRegister()
    {
        GpManagerEngine engine = engine(true);
        engine.ensureSession(500L);
        engine.resetTrackingData(1_000L);
        engine.ensureArmedAfterConfigReseed(1_100L);

        Map<Integer, Long> empty = new HashMap<>();
        engine.setBaseline(new ContainerSnapshot(empty));

        // Woodcutting-style pure inventory gain (no ground-pickup / NPC adapter).
        engine.setDetectedActivity("Woodcutting", 1_200L);
        engine.markInventoryDirty();
        Map<Integer, Long> withLogs = new HashMap<>();
        withLogs.put(OAK_LOGS, 1L);
        ProfitTransaction skilling = settle(engine, new ContainerSnapshot(withLogs), 1_300L);
        assertNotNull(skilling);
        assertEquals(TransactionType.GAIN, skilling.getType());
        assertTrue(skilling.isCounted());
        assertEquals(40L, skilling.getNet());

        // NPC loot expectation + inventory settlement (combat path).
        engine.markLootContext(
            Collections.singletonMap(COINS, 25L),
            4,
            "Loot from Goblin",
            "Goblin");
        engine.markInventoryDirty();
        Map<Integer, Long> withLoot = new HashMap<>(withLogs);
        withLoot.put(COINS, 25L);
        ProfitTransaction loot = settle(engine, new ContainerSnapshot(withLoot), 1_500L);
        assertNotNull(loot);
        assertEquals(TransactionType.LOOT, loot.getType());
        assertTrue(loot.isCounted());
        assertEquals(25L, loot.getNet());

        assertEquals(65L, engine.getMetrics(1_600L).getNet());
    }

    @Test
    public void rewardPresentationAcceptsNpcLootAndSkillingAfterResetSuppressionCleared()
    {
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setSuppressReveals(true);
        assertFalse(rewards.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "npc:1",
            Collections.emptyList(),
            1_000L,
            false));

        rewards.setSuppressReveals(false);
        assertFalse(RewardSourceKind.NPC_LOOT.isSkilling());
        assertFalse(RewardSourceKind.RECENT_PICKUPS.isSkilling());
    }

    @Test
    public void hardTransferStillSettlesAsTransferAfterResetArm()
    {
        GpManagerEngine engine = engine(true);
        engine.resetTrackingData(1_000L);
        engine.setBaseline(new ContainerSnapshot(Collections.singletonMap(COINS, 100L)));

        engine.markContext(TrackingContext.TRANSFER, 4, "Bank transfer");
        engine.markInventoryDirty();
        ProfitTransaction transfer = settle(
            engine,
            new ContainerSnapshot(Collections.singletonMap(COINS, 50L)),
            1_200L);
        assertNotNull(transfer);
        assertEquals(TransactionType.TRANSFER, transfer.getType());
        assertFalse(transfer.isCounted());
        assertEquals(0L, engine.getMetrics(1_300L).getNet());
    }
}
