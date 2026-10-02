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
        List<Ab> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int price = entry.getKey() == 526 ? 35 : 100;
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
    public void dropThenRecoverReversesLossWithoutRevenue()
    {
        Am engine = startedWithBones(1L);
        engine.aki(100, 200, 0);
        engine.abz(526, 6, 100, 200, 0, true);

        Ac drop = settle(engine, Cc.empty(), 1_600L);
        assertEquals(Ai.CONSUMPTION, drop.getType());
        assertEquals("Dropped", drop.getNote());
        assertEquals(35L, engine.getMetrics(2_800L).costs);
        assertEquals(-35L, engine.getMetrics(2_800L).net);

        engine.yz();
        Ac recovery = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(Ai.TRANSFER, recovery.getType());
        assertFalse(recovery.isCounted());

        Bu metrics = engine.getMetrics(5_000L);
        assertEquals(0L, metrics.revenue);
        assertEquals(0L, metrics.costs);
        assertEquals(0L, metrics.net);
    }

    @Test
    public void partialRecoveryReversesOnlyRecoveredQuantity()
    {
        Am engine = startedWithBones(5L);
        engine.aki(10, 10, 0);
        engine.abz(526, 6, 10, 10, 0, true);

        settle(engine, Cc.empty(), 1_600L);
        assertEquals(175L, engine.getMetrics(2_800L).costs);

        engine.yz();
        settle(engine, snapshot(526, 2L), 3_400L);

        Bu metrics = engine.getMetrics(5_000L);
        assertEquals(0L, metrics.revenue);
        assertEquals(105L, metrics.costs);
        assertEquals(-105L, metrics.net);
    }

    @Test
    public void buryDoesNotCreateRecoverableOwnDrop()
    {
        Am engine = startedWithBones(1L);
        engine.aki(1, 1, 0);
        engine.noteConsumptionIntent(526, 6);

        Ac burial = settle(engine, Cc.empty(), 1_600L);
        assertEquals(Ai.CONSUMPTION, burial.getType());
        assertEquals("", burial.getNote());

        engine.yz();
        Ac pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(Ai.GAIN, pickup.getType());
        assertTrue(pickup.isCounted());
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
        assertEquals(35L, engine.getMetrics(5_000L).costs);
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void destroyStampsDestroyedNoteAndIsNotRecoverable()
    {
        Am engine = startedWithBones(1L);
        engine.aki(1, 1, 0);
        engine.noteConsumptionIntent(526, 6, true, null);

        Ac destroyed = settle(engine, Cc.empty(), 1_600L);
        assertEquals(Ai.CONSUMPTION, destroyed.getType());
        assertEquals("Destroyed", destroyed.getNote());

        engine.yz();
        Ac pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(Ai.GAIN, pickup.getType());
        assertTrue(pickup.isCounted());
    }

    @Test
    public void dropThenPickupStillRecoversAfterDroppedStamp()
    {
        Am engine = startedWithBones(1L);
        engine.aki(50, 50, 0);
        engine.abz(526, 6, 50, 50, 0, true);

        Ac drop = settle(engine, Cc.empty(), 1_600L);
        assertEquals("Dropped", drop.getNote());
        assertEquals(Ai.CONSUMPTION, drop.getType());

        engine.yz();
        Ac recovery = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(Ai.TRANSFER, recovery.getType());
        assertEquals("Own-drop recovery", recovery.getNote());
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void distantPickupDoesNotMatchOwnDrop()
    {
        Am engine = startedWithBones(1L);
        engine.aki(100, 100, 0);
        engine.abz(526, 6, 100, 100, 0, true);
        settle(engine, Cc.empty(), 1_600L);

        // Outside OWN_DROP_MATCH_RADIUS (8).
        engine.aki(120, 120, 0);
        engine.yz();
        Ac pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(Ai.GAIN, pickup.getType());
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
        assertEquals(35L, engine.getMetrics(5_000L).costs);
    }

    @Test
    public void willowDumpThenPickupRecoversLoss()
    {
        Am engine = startedWithBones(0L);
        engine.setBaseline(snapshot(1519, 20L));
        engine.aki(40, 40, 0);
        engine.abz(1519, 20, 40, 40, 0, true);

        Ac drop = settle(engine, Cc.empty(), 1_600L);
        assertEquals(Ai.CONSUMPTION, drop.getType());
        assertEquals("Dropped", drop.getNote());
        assertEquals(100L * 20L, engine.getMetrics(2_800L).costs);

        engine.aki(42, 41, 0);
        engine.yz();
        Ac recovery = settle(engine, snapshot(1519, 20L), 3_400L);
        assertEquals(Ai.TRANSFER, recovery.getType());
        assertEquals("Own-drop recovery", recovery.getNote());
        assertEquals(0L, engine.getMetrics(5_000L).costs);
        assertEquals(0L, engine.getMetrics(5_000L).revenue);
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void unlocatedDropStillRecoversWhenPlayerLocationKnown()
    {
        Am engine = startedWithBones(1L);
        engine.aki(10, 10, 0);
        engine.abz(526, 6, 0, 0, 0, false);

        settle(engine, Cc.empty(), 1_600L);
        assertEquals(35L, engine.getMetrics(2_800L).costs);

        engine.yz();
        Ac recovery = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(Ai.TRANSFER, recovery.getType());
        assertEquals(0L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void npcLootContextIsNotTreatedAsOwnDropRecovery()
    {
        Am engine = startedWithBones(1L);
        engine.aki(5, 5, 0);
        engine.abz(526, 6, 5, 5, 0, true);
        settle(engine, Cc.empty(), 1_600L);

        engine.zk(Collections.singletonMap(526, 1L), 6, "Loot from Chicken", "Chicken");
        engine.yz();
        Ac loot = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(Ai.LOOT, loot.getType());
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
        assertEquals(35L, engine.getMetrics(5_000L).costs);
    }

    @Test
    public void skillingDumpBooksConsumptionWhileBankDepositDoesNot()
    {
        // Drop willow logs → counted Used/lost.
        Am dump = startedWithBones(0L);
        dump.setBaseline(snapshot(1519, 20L));
        dump.aki(50, 50, 0);
        dump.abz(1519, 20, 50, 50, 0, true);
        Ac dropped = settle(dump, Cc.empty(), 1_600L);
        assertEquals(Ai.CONSUMPTION, dropped.getType());
        assertTrue(dropped.isCounted());
        assertEquals(100L * 20L, dump.getMetrics(2_800L).costs);

        // Soft bank-open leave-inv → Transfer, not Used (WC guild / deposit-box peer pain).
        Am bank = startedWithBones(0L);
        bank.setBaseline(snapshot(1519, 20L));
        bank.ze(6);
        bank.yz();
        Ac deposit = settle(bank, Cc.empty(), 1_600L);
        assertEquals(Ai.TRANSFER, deposit.getType());
        assertFalse(deposit.isCounted());
        assertEquals(0L, bank.getMetrics(2_800L).costs);
    }

    @Test
    public void surplusPickupCountsOnlyTheSurplus()
    {
        Am engine = startedWithBones(1L);
        engine.aki(10, 10, 0);
        engine.abz(526, 6, 10, 10, 0, true);
        settle(engine, Cc.empty(), 1_600L);
        engine.yz();
        settle(engine, snapshot(526, 2L), 3_400L);
        assertEquals(0L, engine.getMetrics(5_000L).costs);
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
        assertEquals(35L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void coalescedPickupPreservesTheOtherGain()
    {
        Am engine = startedWithBones(1L);
        engine.aki(10, 10, 0);
        engine.abz(526, 6, 10, 10, 0, true);
        settle(engine, Cc.empty(), 1_600L);
        engine.yz();
        settle(engine, new Cc(Map.of(526, 1L, 1519, 2L)), 3_400L);
        assertEquals(0L, engine.getMetrics(5_000L).costs);
        assertEquals(200L, engine.getMetrics(5_000L).revenue);
    }

    @Test
    public void previousSessionDropCannotSwallowPickup()
    {
        Am engine = startedWithBones(1L);
        engine.aki(10, 10, 0);
        engine.abz(526, 6, 10, 10, 0, true);
        settle(engine, Cc.empty(), 1_600L);
        engine.ajl("Next", Cx.AUTO, 3_000L);
        engine.setBaseline(Cc.empty());
        engine.yz();
        Ac pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(Ai.GAIN, pickup.getType());
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
    }

    @Test
    public void correctedDropCannotSwallowPickup()
    {
        Am engine = startedWithBones(1L);
        engine.aki(10, 10, 0);
        engine.abz(526, 6, 10, 10, 0, true);
        Ac drop = settle(engine, Cc.empty(), 1_600L);
        // A retained corrected receipt cannot become fresh recovery authority.
        drop.ko(Ah.IGNORE, 3_000L, "Not a loss");
        engine.yz();
        Ac pickup = settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(Ai.GAIN, pickup.getType());
        assertEquals(35L, engine.getMetrics(5_000L).revenue);
    }

    @Test
    public void deathContextCannotRecoverAnEarlierDrop()
    {
        Am engine = startedWithBones(1L);
        engine.aki(10, 10, 0);
        engine.abz(526, 6, 10, 10, 0, true);
        Ac drop = settle(engine, Cc.empty(), 1_600L);
        engine.zo("Death", 3_000L);
        engine.yz();
        settle(engine, snapshot(526, 1L), 3_400L);
        assertEquals(35L, drop.getCosts());
        assertTrue(engine.getActiveSession().getTransactions().stream().noneMatch(tx -> "Own-drop recovery".equals(tx.getNote())));
    }

    private Am startedWithBones(long quantity)
    {
        Am engine = new Am(valuator, new TransactionClassifier(), CONFIG);
        engine.rm(1_000L);
        engine.setBaseline(snapshot(526, quantity));
        return engine;
    }

    private static Ac settle(Am engine, Cc snapshot, long firstTick)
    {
        assertNull(engine.adj(snapshot, firstTick));
        assertNull(engine.adj(snapshot, firstTick + 600L));
        Ac tx = engine.adj(snapshot, firstTick + 1_200L);
        assertNotNull(tx);
        return tx;
    }

    private static Cc snapshot(int itemId, long quantity)
    {
        return new Cc(Collections.singletonMap(itemId, quantity));
    }
}
