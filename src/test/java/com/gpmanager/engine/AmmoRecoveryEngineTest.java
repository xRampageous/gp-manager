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

/**
 * Owner 1.1: fired ammo books Supplies; picking it back up (a ground Take) shrinks those Supplies
 * instead of booking loot, capped at what was fired and not yet recovered.
 */
public class AmmoRecoveryEngineTest
{
    private static final int RUNE_ARROW = 892;
    private static final int ADAMANT_ARROW = 890;
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
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
            String name = entry.getKey() == RUNE_ARROW ? "Rune arrow" : "Adamant arrow";
            flows.add(new Ab(entry.getKey(), name, entry.getValue(), 100, entry.getValue() * 100));
        }
        return flows;
    };

    @Test
    public void pickingUpFiredArrowsReducesSupplies()
    {
        Am engine = firedAll(100L);
        assertEquals(10_000L, engine.getMetrics(3_000L).costs);

        engine.noteTake(RUNE_ARROW);
        Ac recovery = settle(engine, arrows(RUNE_ARROW, 40L), 3_400L);
        assertEquals(Ai.TRANSFER, recovery.getType());
        assertFalse(recovery.isCounted());
        Bu metrics = engine.getMetrics(6_000L);
        assertEquals("never income", 0L, metrics.revenue);
        assertEquals(6_000L, metrics.costs);
    }

    @Test
    public void withoutATakeAPickupStaysLoot()
    {
        Am engine = firedAll(100L);
        Ac pickup = settle(engine, arrows(RUNE_ARROW, 40L), 3_400L);
        assertTrue(pickup.isCounted());
        assertEquals(4_000L, engine.getMetrics(6_000L).revenue);
        assertEquals(10_000L, engine.getMetrics(6_000L).costs);
    }

    @Test
    public void anotherAmmoStaysLoot()
    {
        Am engine = firedAll(100L);
        engine.noteTake(ADAMANT_ARROW);
        Ac pickup = settle(engine, arrows(ADAMANT_ARROW, 40L), 3_400L);
        assertTrue(pickup.isCounted());
        assertEquals(4_000L, engine.getMetrics(6_000L).revenue);
        assertEquals(10_000L, engine.getMetrics(6_000L).costs);
    }

    @Test
    public void morePickedUpThanFiredCountsTheRestAsLoot()
    {
        Am engine = firedAll(10L);
        engine.noteTake(RUNE_ARROW);
        Ac pickup = settle(engine, arrows(RUNE_ARROW, 15L), 3_400L);
        assertTrue(pickup.isCounted());
        Bu metrics = engine.getMetrics(6_000L);
        assertEquals("the ten fired come back", 0L, metrics.costs);
        assertEquals("the extra five are loot", 500L, metrics.revenue);
    }

    @Test
    public void aTakeExpires()
    {
        Am engine = firedAll(100L);
        engine.noteTake(RUNE_ARROW);
        for (int tick = 0; tick < 60; tick++)
        {
            engine.adj(Cc.empty(), 3_400L + tick * 600L);
        }
        Ac pickup = settle(engine, arrows(RUNE_ARROW, 40L), 40_000L);
        assertTrue(pickup.isCounted());
        assertEquals(4_000L, engine.getMetrics(45_000L).revenue);
    }

    /** Starts with {@code quantity} rune arrows and fires them all. */
    private Am firedAll(long quantity)
    {
        Am engine = new Am(valuator, new TransactionClassifier(), CONFIG);
        engine.rm(1_000L);
        engine.setBaseline(arrows(RUNE_ARROW, quantity));
        Ac fired = settle(engine, Cc.empty(), 1_600L);
        assertEquals(Ai.CONSUMPTION, fired.getType());
        assertEquals(Au.FIRE, fired.getActionKind());
        engine.yz();
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

    private static Cc arrows(int itemId, long quantity)
    {
        return new Cc(Collections.singletonMap(itemId, quantity));
    }
}
