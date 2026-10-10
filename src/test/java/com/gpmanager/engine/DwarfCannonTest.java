package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Owner 1.3.3: setting up and picking up a dwarf multicannon is neutral; only the cannonballs it
 * fired count. Balls it did not fire come back on pick-up and shrink the load.
 */
public class DwarfCannonTest
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

    private static final Map<Integer, String> NAMES = Map.of(
        ItemID.TWPART1, "Cannon base", ItemID.TWPART2, "Cannon stand",
        ItemID.TWPART3, "Cannon barrels", ItemID.TWPART4, "Cannon furnace",
        ItemID.MCANNONBALL, "Cannonball");

    private final FlowValuator valuator = deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) -> {
            int unit = id == ItemID.MCANNONBALL ? 200 : 150_000;
            flows.add(new Flow(id, NAMES.get(id), quantity, unit, quantity * unit));
        });
        return flows;
    };

    @Test
    public void onlyTheFiredBallsCount()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(snapshot(true, 100L));

        engine.moveCannon();
        settle(engine, snapshot(false, 100L), 1_600L);
        assertEquals("setting it up is neutral", 0L, engine.getMetrics(5_000L).net);

        engine.loadCannon(6);
        Transaction load = settle(engine, snapshot(false, 70L), 6_000L);
        assertEquals("Loaded into the cannon", load.getNote());
        assertEquals(-6_000L, engine.getMetrics(10_000L).net);

        engine.moveCannon();
        settle(engine, snapshot(true, 80L), 12_000L);
        assertEquals("20 balls were fired; parts and 10 balls came back", -4_000L, engine.getMetrics(20_000L).net);
        assertEquals(0, engine.getReviewRows(20_000L).size());
    }

    private static ContainerSnapshot snapshot(boolean parts, long balls)
    {
        Map<Integer, Long> held = new HashMap<>();
        if (parts)
        {
            held.put(ItemID.TWPART1, 1L);
            held.put(ItemID.TWPART2, 1L);
            held.put(ItemID.TWPART3, 1L);
            held.put(ItemID.TWPART4, 1L);
        }
        held.put(ItemID.MCANNONBALL, balls);
        return new ContainerSnapshot(held);
    }

    private static Transaction settle(Engine engine, ContainerSnapshot snapshot, long firstTick)
    {
        Transaction row = null;
        for (long t = firstTick; row == null && t < firstTick + 6_000L; t += 600L) row = engine.processIfDirty(snapshot, t);
        assertNotNull(row);
        return row;
    }
}
