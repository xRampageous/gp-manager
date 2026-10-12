package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner 1.3.4: a confirmed drop owns its item when a harvest lands in the same change (the live
 * calquat shape: Empty plant pot x-11 with a Calquat fruit gain). The drop books as its own
 * Dropped row and the sibling gain separately, with no Review; guard shapes cancel the split.
 */
public class DropMixedSettleTest
{
    private static final int CALQUAT_FRUIT = 5980;
    private static final int EMPTY_PLANT_POT = 5350;
    private static final int SHARK = 385;

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
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) ->
            flows.add(new Flow(id, name(id), quantity, unit(id), quantity * unit(id))));
        return flows;
    };

    private static String name(int id)
    {
        switch (id)
        {
            case CALQUAT_FRUIT: return "Calquat fruit";
            case EMPTY_PLANT_POT: return "Empty plant pot";
            case SHARK: return "Shark";
            default: return "Item " + id;
        }
    }

    private static int unit(int id)
    {
        switch (id)
        {
            case CALQUAT_FRUIT: return 600;
            case SHARK: return 1_000;
            default: return 1;
        }
    }

    @Test
    public void aDropKeepsItsOwnItemWhenAHarvestLands()
    {
        Engine engine = engine(Map.of(EMPTY_PLANT_POT, 11L));
        engine.noteDrop(EMPTY_PLANT_POT, 6);
        settle(engine, held(Map.of(CALQUAT_FRUIT, 1L)), 1_600L);

        List<Transaction> rows = engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 2, rows.size());
        Transaction drop = rows.get(0);
        assertEquals(TransactionType.CONSUMPTION, drop.getType());
        assertEquals("Dropped", drop.getNote());
        assertTrue(drop.isCounted());
        assertEquals(11L, drop.quantity(EMPTY_PLANT_POT, false));
        Transaction harvest = rows.get(1);
        assertEquals(TransactionType.GAIN, harvest.getType());
        assertEquals(1L, harvest.quantity(CALQUAT_FRUIT, true));
        assertEquals("no Review", 0, engine.getReviewRows(10_000L).size());
        Metrics metrics = engine.getMetrics(10_000L);
        assertEquals(11L, metrics.costs);
        assertEquals(600L, metrics.revenue);
    }

    @Test
    public void pickingPartOfTheSplitDropBackUpShrinksIt()
    {
        Engine engine = engine(Map.of(EMPTY_PLANT_POT, 11L));
        engine.noteDrop(EMPTY_PLANT_POT, 6);
        settle(engine, held(Map.of(CALQUAT_FRUIT, 1L)), 1_600L);

        engine.markDirty();
        settle(engine, held(Map.of(CALQUAT_FRUIT, 1L, EMPTY_PLANT_POT, 5L)), 4_000L);

        assertEquals("the pickup books no new row", 2, engine.getActiveSession().getTransactions().size());
        Metrics metrics = engine.getMetrics(10_000L);
        assertEquals("6 pots stay on the ground", 6L, metrics.costs);
        assertEquals(600L, metrics.revenue);
    }

    @Test
    public void aSecondLossCancelsTheSplit()
    {
        Engine engine = engine(Map.of(EMPTY_PLANT_POT, 11L, SHARK, 2L));
        engine.noteDrop(EMPTY_PLANT_POT, 6);
        settle(engine, held(Map.of(CALQUAT_FRUIT, 1L, SHARK, 1L)), 1_600L);

        List<Transaction> rows = engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertFalse("Dropped", "Dropped".equals(rows.get(0).getNote()));
        assertEquals(TransactionType.UNCERTAIN, rows.get(0).getType());
        assertEquals(1, engine.getReviewRows(10_000L).size());
    }

    @Test
    public void aConsumeIntentCancelsTheSplit()
    {
        Engine engine = engine(Map.of(EMPTY_PLANT_POT, 11L, SHARK, 2L));
        engine.noteDrop(EMPTY_PLANT_POT, 6);
        engine.noteConsume(SHARK, 6, false);
        settle(engine, held(Map.of(CALQUAT_FRUIT, 1L, SHARK, 1L)), 1_600L);

        List<Transaction> rows = engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertFalse("Dropped", "Dropped".equals(rows.get(0).getNote()));
        assertEquals(TransactionType.CONSUMPTION, rows.get(0).getType());
    }

    @Test
    public void aPkLootContextCancelsTheSplit()
    {
        Engine engine = engine(Map.of(EMPTY_PLANT_POT, 11L));
        engine.noteDrop(EMPTY_PLANT_POT, 6);
        engine.markContext(Context.PK_LOOT, 6, "PKing");
        settle(engine, held(Map.of(CALQUAT_FRUIT, 1L)), 1_600L);

        List<Transaction> rows = engine.getActiveSession().getTransactions();
        assertEquals(rows.toString(), 1, rows.size());
        assertFalse("Dropped", "Dropped".equals(rows.get(0).getNote()));
    }

    private Engine engine(Map<Integer, Long> baseline)
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(baseline)));
        return engine;
    }

    private static ContainerSnapshot held(Map<Integer, Long> quantities)
    {
        return new ContainerSnapshot(new HashMap<>(quantities));
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        assertNull(engine.processIfDirty(snapshot, firstTick));
        assertNull(engine.processIfDirty(snapshot, firstTick + 600L));
        Transaction tx = engine.processIfDirty(snapshot, firstTick + 1_200L);
        assertNotNull(tx);
        return tx;
    }
}
