package com.gpmanager;

import com.google.gson.Gson;
import java.util.*;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Dropped items and fired ammo stay on the paid-price record while they can still be picked up,
 * and leave it when the pickup window closes: only what nobody picked up is used up.
 */
public class GroundBasisTest
{
    private static final int BOLTS = 9140;
    private static final int DART = 807;
    private static final long WINDOW_TICKS = 300;

    static
    {
        JsonCodec.bind(new Gson());
    }

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
        deltas.forEach((id, quantity) -> flows.add(new Flow(id, id == DART ? "Iron dart" : "Iron bolts",
            quantity, 10, quantity * 10, PriceSource.GRAND_EXCHANGE)));
        return flows;
    };

    private long now = 1_000L;

    private static ContainerSnapshot pack(int itemId, long quantity)
    {
        return new ContainerSnapshot(Collections.singletonMap(itemId, quantity));
    }

    private void settle(Engine engine, ContainerSnapshot snapshot)
    {
        engine.markDirty();
        for (int tick = 0; tick < 4; tick++) engine.processIfDirty(snapshot, now += 600L);
    }

    private void wait(Engine engine, ContainerSnapshot snapshot, long ticks)
    {
        for (long tick = 0; tick < ticks; tick++) engine.processIfDirty(snapshot, now += 600L);
    }

    /** Ten counted bolts (known coverage 10), then three dropped. */
    private Engine droppedThree()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());
        settle(engine, pack(BOLTS, 10L));
        assertEquals(10L, EngineProbe.knownCoverageQty(engine, BOLTS));
        engine.noteDrop(BOLTS, 6);
        settle(engine, pack(BOLTS, 7L));
        Transaction drop = engine.getActiveSession().getTransactions().get(1);
        assertEquals("Dropped", drop.getNote());
        assertTrue(drop.isOnGround());
        return engine;
    }

    @Test
    public void aDropLeavesTheRecordOnlyWhenItsPickupWindowCloses()
    {
        Engine engine = droppedThree();
        assertEquals("still on the ground", 10L, EngineProbe.knownCoverageQty(engine, BOLTS));
        wait(engine, pack(BOLTS, 7L), WINDOW_TICKS);
        assertEquals("never picked up: used up", 7L, EngineProbe.knownCoverageQty(engine, BOLTS));
        assertFalse(engine.getActiveSession().getTransactions().get(1).isOnGround());
    }

    @Test
    public void aDropPickedBackUpNeverLeavesTheRecord()
    {
        Engine engine = droppedThree();
        settle(engine, pack(BOLTS, 10L));
        wait(engine, pack(BOLTS, 10L), WINDOW_TICKS);
        assertEquals(10L, EngineProbe.knownCoverageQty(engine, BOLTS));
    }

    @Test
    public void aPartPickedUpLeavesOnlyTheRest()
    {
        Engine engine = droppedThree();
        settle(engine, pack(BOLTS, 9L));
        wait(engine, pack(BOLTS, 9L), WINDOW_TICKS);
        assertEquals("one bolt stayed on the ground", 9L, EngineProbe.knownCoverageQty(engine, BOLTS));
    }

    @Test
    public void aRestartSettlesWhatWasStillOnTheGround()
    {
        Engine engine = droppedThree();
        Gson gson = JsonCodec.gson();
        SavedState saved = gson.fromJson(gson.toJson(engine.savedState()), SavedState.class);
        Engine restarted = new Engine(valuator, new TransactionClassifier(), CONFIG);
        restarted.restore(saved, now);
        assertEquals(10L, EngineProbe.knownCoverageQty(restarted, BOLTS));
        restarted.processIfDirty(pack(BOLTS, 7L), now += 600L);
        assertEquals("its pickup memory did not survive", 7L, EngineProbe.knownCoverageQty(restarted, BOLTS));
    }

    @Test
    public void aDropCompactedWhileOnTheGroundStillLeavesTheRecord()
    {
        Engine engine = droppedThree();
        engine.getActiveSession().compactBefore(now + 1L, null);
        assertTrue(engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(7L, EngineProbe.knownCoverageQty(engine, BOLTS));
        wait(engine, pack(BOLTS, 7L), WINDOW_TICKS);
        assertEquals("settled once", 7L, EngineProbe.knownCoverageQty(engine, BOLTS));
    }

    @Test
    public void anUndoneDropNeverLeavesTheRecord()
    {
        Engine engine = droppedThree();
        assertNotNull(engine.getActiveSession().undoTransaction());
        wait(engine, pack(BOLTS, 7L), WINDOW_TICKS);
        assertEquals(10L, EngineProbe.knownCoverageQty(engine, BOLTS));
    }

    @Test
    public void dartsACheckMeasuredLeaveTheRecordAtOnce()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(now);
        engine.setBaseline(ContainerSnapshot.empty());
        settle(engine, pack(DART, 10L));
        assertEquals(10L, EngineProbe.knownCoverageQty(engine, DART));
        ChargeDelta delta = new ChargeDelta(ChargeCheck.ChargedItem.BLOWPIPE,
            Collections.singletonList(new ChargeDelta.ComponentDelta(DART, -4L)));
        Transaction spent = engine.bookChargeSpend(delta, "Toxic blowpipe",
            Collections.singletonList(new Flow(DART, "Iron dart", -4L, 10, -40L, PriceSource.GRAND_EXCHANGE)),
            now, null);
        assertNotNull(spent);
        assertFalse("a measured spend is not on the ground", spent.isOnGround());
        assertEquals(6L, EngineProbe.knownCoverageQty(engine, DART));
    }

    @Test
    public void onlyRowsOnTheGroundCarryTheFlagInASave()
    {
        Engine engine = droppedThree();
        String json = JsonCodec.gson().toJson(engine.getActiveSession());
        assertEquals("one row on the ground", 1, json.split("\"onGround\"", -1).length - 1);
    }
}
