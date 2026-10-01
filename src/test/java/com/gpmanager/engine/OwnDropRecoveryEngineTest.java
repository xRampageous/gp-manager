package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Own ground-drop recovery reverses prior Used/lost instead of booking revenue. */
public class OwnDropRecoveryEngineTest
{
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 2;
        }

        @Override
        public boolean keepTransferAuditRows()
        {
            return true;
        }
    };

    private final FlowValuator valuator = deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int price = entry.getKey() == 526 ? 35 : 100;
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
    public void dropThenRecoverReversesLossWithoutRevenue()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(100, 200, 0);
        engine.noteDropIntent(526, 6, 100, 200, 0, true);

        Transaction drop = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, drop.getType());
        assertEquals("Dropped", drop.getNote());
        assertEquals(35L, engine.getMetrics(2_800L).costs);
        assertEquals(-35L, engine.getMetrics(2_800L).net);

        engine.markInventoryDirty();
        Transaction recovery = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.TRANSFER, recovery.getType());
        assertFalse(recovery.isCounted());

        SessionMetrics metrics = engine.getMetrics(5_000L);
        assertEquals(0L, metrics.revenue);
        assertEquals(0L, metrics.costs);
        assertEquals(0L, metrics.net);
    }

    @Test
    public void partialRecoveryReversesOnlyRecoveredQuantity()
    {
        Engine engine = startedWithBones(5L);
        engine.updatePlayerWorldLocation(10, 10, 0);
        engine.noteDropIntent(526, 6, 10, 10, 0, true);

        settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(175L, engine.getMetrics(2_800L).costs);

        engine.markInventoryDirty();
        settle(engine, snapshot(526, 2L), 3_400L);

        SessionMetrics metrics = engine.getMetrics(5_000L);
        assertEquals(0L, metrics.revenue);
        assertEquals(105L, metrics.costs);
        assertEquals(-105L, metrics.net);
    }

    @Test
    public void buryDoesNotCreateRecoverableOwnDrop()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(1, 1, 0);
        engine.noteConsumptionIntent(526, 6);

        Transaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertEquals("", burial.getNote());

        engine.markInventoryDirty();
        Transaction pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.GAIN, pickup.getType());
        assertTrue(pickup.isCounted());
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
        assertEquals(35L, engine.getMetrics(5_000L).costs);
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void destroyStampsDestroyedNoteAndIsNotRecoverable()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(1, 1, 0);
        engine.noteConsumptionIntent(526, 6, true, null);

        Transaction destroyed = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, destroyed.getType());
        assertEquals("Destroyed", destroyed.getNote());

        engine.markInventoryDirty();
        Transaction pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.GAIN, pickup.getType());
        assertTrue(pickup.isCounted());
    }

    @Test
    public void dropThenPickupStillRecoversAfterDroppedStamp()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(50, 50, 0);
        engine.noteDropIntent(526, 6, 50, 50, 0, true);

        Transaction drop = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals("Dropped", drop.getNote());
        assertEquals(TransactionType.CONSUMPTION, drop.getType());

        engine.markInventoryDirty();
        Transaction recovery = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.TRANSFER, recovery.getType());
        assertEquals("Own-drop recovery", recovery.getNote());
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void distantPickupDoesNotMatchOwnDrop()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(100, 100, 0);
        engine.noteDropIntent(526, 6, 100, 100, 0, true);
        settle(engine, ContainerSnapshot.empty(), 1_600L);

        // Outside OWN_DROP_MATCH_RADIUS (8).
        engine.updatePlayerWorldLocation(120, 120, 0);
        engine.markInventoryDirty();
        Transaction pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.GAIN, pickup.getType());
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
        assertEquals(35L, engine.getMetrics(5_000L).costs);
    }

    @Test
    public void willowDumpThenPickupRecoversLoss()
    {
        Engine engine = startedWithBones(0L);
        engine.setBaseline(snapshot(1519, 20L));
        engine.updatePlayerWorldLocation(40, 40, 0);
        engine.noteDropIntent(1519, 20, 40, 40, 0, true);

        Transaction drop = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, drop.getType());
        assertEquals("Dropped", drop.getNote());
        assertEquals(100L * 20L, engine.getMetrics(2_800L).costs);

        engine.updatePlayerWorldLocation(42, 41, 0);
        engine.markInventoryDirty();
        Transaction recovery = settle(engine, snapshot(1519, 20L), 3_400L);
        assertEquals(TransactionType.TRANSFER, recovery.getType());
        assertEquals("Own-drop recovery", recovery.getNote());
        assertEquals(0L, engine.getMetrics(5_000L).costs);
        assertEquals(0L, engine.getMetrics(5_000L).revenue);
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void unlocatedDropStillRecoversWhenPlayerLocationKnown()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(10, 10, 0);
        engine.noteDropIntent(526, 6, 0, 0, 0, false);

        settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(35L, engine.getMetrics(2_800L).costs);

        engine.markInventoryDirty();
        Transaction recovery = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.TRANSFER, recovery.getType());
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void npcLootContextIsNotTreatedAsOwnDropRecovery()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(5, 5, 0);
        engine.noteDropIntent(526, 6, 5, 5, 0, true);
        settle(engine, ContainerSnapshot.empty(), 1_600L);

        engine.markLootContext(Collections.singletonMap(526, 1L), 6, "Loot from Chicken", "Chicken");
        engine.markInventoryDirty();
        Transaction loot = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.LOOT, loot.getType());
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
        assertEquals(35L, engine.getMetrics(5_000L).costs);
    }

    @Test
    public void skillingDumpBooksConsumptionWhileBankDepositDoesNot()
    {
        // Drop willow logs → counted Used/lost.
        Engine dump = startedWithBones(0L);
        dump.setBaseline(snapshot(1519, 20L));
        dump.updatePlayerWorldLocation(50, 50, 0);
        dump.noteDropIntent(1519, 20, 50, 50, 0, true);
        Transaction dropped = settle(dump, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, dropped.getType());
        assertTrue(dropped.isCounted());
        assertEquals(100L * 20L, dump.getMetrics(2_800L).costs);

        // Soft bank-open leave-inv → Transfer, not Used (WC guild / deposit-box peer pain).
        Engine bank = startedWithBones(0L);
        bank.setBaseline(snapshot(1519, 20L));
        bank.markBankInterfaceOpen(6);
        bank.markInventoryDirty();
        Transaction deposit = settle(bank, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, bank.getMetrics(2_800L).costs);
    }

    @Test
    public void surplusPickupCountsOnlyTheSurplus()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(10, 10, 0);
        engine.noteDropIntent(526, 6, 10, 10, 0, true);
        settle(engine, ContainerSnapshot.empty(), 1_600L);
        engine.markInventoryDirty();
        settle(engine, snapshot(526, 2L), 3_400L);
        assertEquals(0L, engine.getMetrics(5_000L).costs);
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
        assertEquals(35L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void coalescedPickupPreservesTheOtherGain()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(10, 10, 0);
        engine.noteDropIntent(526, 6, 10, 10, 0, true);
        settle(engine, ContainerSnapshot.empty(), 1_600L);
        engine.markInventoryDirty();
        settle(engine, new ContainerSnapshot(Map.of(526, 1L, 1519, 2L)), 3_400L);
        assertEquals(0L, engine.getMetrics(5_000L).costs);
        assertEquals(200L, engine.getMetrics(5_000L).revenue);
    }

    @Test
    public void previousSessionDropCannotSwallowPickup()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(10, 10, 0);
        engine.noteDropIntent(526, 6, 10, 10, 0, true);
        settle(engine, ContainerSnapshot.empty(), 1_600L);
        engine.startCustomSession("Next", SessionMode.AUTO, 3_000L);
        engine.setBaseline(ContainerSnapshot.empty());
        engine.markInventoryDirty();
        Transaction pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.GAIN, pickup.getType());
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
    }

    @Test
    public void correctedDropCannotSwallowPickup()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(10, 10, 0);
        engine.noteDropIntent(526, 6, 10, 10, 0, true);
        Transaction drop = settle(engine, ContainerSnapshot.empty(), 1_600L);
        // A retained corrected receipt cannot become fresh recovery authority.
        drop.applyCorrection(Correction.IGNORE, 3_000L, "Not a loss");
        engine.markInventoryDirty();
        Transaction pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.GAIN, pickup.getType());
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
    }

    @Test
    public void deathContextCannotRecoverAnEarlierDrop()
    {
        Engine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(10, 10, 0);
        engine.noteDropIntent(526, 6, 10, 10, 0, true);
        Transaction drop = settle(engine, ContainerSnapshot.empty(), 1_600L);
        engine.markPkDeath("Death", 3_000L, null);
        engine.markInventoryDirty();
        settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(35L, drop.getCosts());
        assertTrue(engine.getActiveSession().getTransactions().stream().noneMatch(tx -> "Own-drop recovery".equals(tx.getNote())));
    }

    private Engine startedWithBones(long quantity)
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(526, quantity));
        return engine;
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        assertNull(engine.processIfDirty(snapshot, firstTick));
        assertNull(engine.processIfDirty(snapshot, firstTick + 600L));
        Transaction tx = engine.processIfDirty(snapshot, firstTick + 1_200L);
        assertNotNull(tx);
        return tx;
    }

    private static ContainerSnapshot snapshot(int itemId, long quantity)
    {
        return new ContainerSnapshot(Collections.singletonMap(itemId, quantity));
    }
}
