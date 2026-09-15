package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.TransactionType;
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
        List<ItemFlow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int price = entry.getKey() == 526 ? 35 : 100;
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
    public void dropThenRecoverReversesLossWithoutRevenue()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(100, 200, 0);
        engine.noteDropIntent(526, 6, 100, 200, 0, true);

        ProfitTransaction drop = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, drop.getType());
        assertEquals("Dropped", drop.getNote());
        assertEquals(35L, engine.getMetrics(2_800L).getCosts());
        assertEquals(-35L, engine.getMetrics(2_800L).getNet());

        engine.markInventoryDirty();
        ProfitTransaction recovery = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.TRANSFER, recovery.getType());
        assertFalse(recovery.isCounted());

        SessionMetrics metrics = engine.getMetrics(5_000L);
        assertEquals(0L, metrics.getRevenue());
        assertEquals(0L, metrics.getCosts());
        assertEquals(0L, metrics.getNet());
    }

    @Test
    public void partialRecoveryReversesOnlyRecoveredQuantity()
    {
        GpManagerEngine engine = startedWithBones(5L);
        engine.updatePlayerWorldLocation(10, 10, 0);
        engine.noteDropIntent(526, 6, 10, 10, 0, true);

        settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(175L, engine.getMetrics(2_800L).getCosts());

        engine.markInventoryDirty();
        settle(engine, snapshot(526, 2L), 3_400L);

        SessionMetrics metrics = engine.getMetrics(5_000L);
        assertEquals(0L, metrics.getRevenue());
        assertEquals(105L, metrics.getCosts());
        assertEquals(-105L, metrics.getNet());
    }

    @Test
    public void buryDoesNotCreateRecoverableOwnDrop()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(1, 1, 0);
        engine.noteConsumptionIntent(526, 6);

        ProfitTransaction burial = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, burial.getType());
        assertEquals("", burial.getNote());

        engine.markInventoryDirty();
        ProfitTransaction pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.GAIN, pickup.getType());
        assertTrue(pickup.isCounted());
        assertEquals(35L, engine.getMetrics(5_000L).getRevenue());
        assertEquals(35L, engine.getMetrics(5_000L).getCosts());
        assertEquals(0L, engine.getMetrics(5_000L).getNet());
    }

    @Test
    public void destroyStampsDestroyedNoteAndIsNotRecoverable()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(1, 1, 0);
        engine.noteConsumptionIntent(526, 6, true);

        ProfitTransaction destroyed = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, destroyed.getType());
        assertEquals("Destroyed", destroyed.getNote());

        engine.markInventoryDirty();
        ProfitTransaction pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.GAIN, pickup.getType());
        assertTrue(pickup.isCounted());
    }

    @Test
    public void dropThenPickupStillRecoversAfterDroppedStamp()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(50, 50, 0);
        engine.noteDropIntent(526, 6, 50, 50, 0, true);

        ProfitTransaction drop = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals("Dropped", drop.getNote());
        assertEquals(TransactionType.CONSUMPTION, drop.getType());

        engine.markInventoryDirty();
        ProfitTransaction recovery = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.TRANSFER, recovery.getType());
        assertEquals("Own-drop recovery", recovery.getNote());
        assertEquals(0L, engine.getMetrics(5_000L).getNet());
    }

    @Test
    public void distantPickupDoesNotMatchOwnDrop()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(100, 100, 0);
        engine.noteDropIntent(526, 6, 100, 100, 0, true);
        settle(engine, ContainerSnapshot.empty(), 1_600L);

        // Outside OWN_DROP_MATCH_RADIUS (8).
        engine.updatePlayerWorldLocation(120, 120, 0);
        engine.markInventoryDirty();
        ProfitTransaction pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.GAIN, pickup.getType());
        assertEquals(35L, engine.getMetrics(5_000L).getRevenue());
        assertEquals(35L, engine.getMetrics(5_000L).getCosts());
    }

    @Test
    public void willowDumpThenPickupRecoversLoss()
    {
        GpManagerEngine engine = startedWithBones(0L);
        engine.setBaseline(snapshot(1519, 20L));
        engine.updatePlayerWorldLocation(40, 40, 0);
        engine.noteDropIntent(1519, 20, 40, 40, 0, true);

        ProfitTransaction drop = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, drop.getType());
        assertEquals("Dropped", drop.getNote());
        assertEquals(100L * 20L, engine.getMetrics(2_800L).getCosts());

        engine.updatePlayerWorldLocation(42, 41, 0);
        engine.markInventoryDirty();
        ProfitTransaction recovery = settle(engine, snapshot(1519, 20L), 3_400L);
        assertEquals(TransactionType.TRANSFER, recovery.getType());
        assertEquals("Own-drop recovery", recovery.getNote());
        assertEquals(0L, engine.getMetrics(5_000L).getCosts());
        assertEquals(0L, engine.getMetrics(5_000L).getRevenue());
        assertEquals(0L, engine.getMetrics(5_000L).getNet());
    }

    @Test
    public void unlocatedDropStillRecoversWhenPlayerLocationKnown()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(10, 10, 0);
        engine.noteDropIntent(526, 6, 0, 0, 0, false);

        settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(35L, engine.getMetrics(2_800L).getCosts());

        engine.markInventoryDirty();
        ProfitTransaction recovery = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.TRANSFER, recovery.getType());
        assertEquals(0L, engine.getMetrics(5_000L).getNet());
    }

    @Test
    public void npcLootContextIsNotTreatedAsOwnDropRecovery()
    {
        GpManagerEngine engine = startedWithBones(1L);
        engine.updatePlayerWorldLocation(5, 5, 0);
        engine.noteDropIntent(526, 6, 5, 5, 0, true);
        settle(engine, ContainerSnapshot.empty(), 1_600L);

        engine.markLootContext(Collections.singletonMap(526, 1L), 6, "Loot from Chicken");
        engine.markInventoryDirty();
        ProfitTransaction loot = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(TransactionType.LOOT, loot.getType());
        assertEquals(35L, engine.getMetrics(5_000L).getRevenue());
        assertEquals(35L, engine.getMetrics(5_000L).getCosts());
    }

    @Test
    public void skillingDumpBooksConsumptionWhileBankDepositDoesNot()
    {
        // Drop willow logs → counted Used/lost.
        GpManagerEngine dump = startedWithBones(0L);
        dump.setBaseline(snapshot(1519, 20L));
        dump.updatePlayerWorldLocation(50, 50, 0);
        dump.noteDropIntent(1519, 20, 50, 50, 0, true);
        ProfitTransaction dropped = settle(dump, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.CONSUMPTION, dropped.getType());
        assertTrue(dropped.isCounted());
        assertEquals(100L * 20L, dump.getMetrics(2_800L).getCosts());

        // Soft bank-open leave-inv → Transfer, not Used (WC guild / deposit-box peer pain).
        GpManagerEngine bank = startedWithBones(0L);
        bank.setBaseline(snapshot(1519, 20L));
        bank.markBankInterfaceOpen(6);
        bank.markInventoryDirty();
        ProfitTransaction deposit = settle(bank, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, bank.getMetrics(2_800L).getCosts());
    }

    private GpManagerEngine startedWithBones(long quantity)
    {
        GpManagerEngine engine = new GpManagerEngine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(526, quantity));
        return engine;
    }

    private static ProfitTransaction settle(GpManagerEngine engine, ContainerSnapshot snapshot, long firstTick)
    {
        assertNull(engine.processIfDirty(snapshot, firstTick));
        assertNull(engine.processIfDirty(snapshot, firstTick + 600L));
        ProfitTransaction tx = engine.processIfDirty(snapshot, firstTick + 1_200L);
        assertNotNull(tx);
        return tx;
    }

    private static ContainerSnapshot snapshot(int itemId, long quantity)
    {
        return new ContainerSnapshot(Collections.singletonMap(itemId, quantity));
    }
}
