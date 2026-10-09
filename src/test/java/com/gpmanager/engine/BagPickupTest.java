package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** A pickup that lands straight in an open bag counts like any pickup (owner 1.2). */
public class BagPickupTest
{
    private static final int BONES = 526;
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
        deltas.forEach((id, quantity) -> flows.add(new Ab(id, "Item " + id, quantity, 35, quantity * 35)));
        return flows;
    };

    private Am started(long bones)
    {
        Am engine = new Am(valuator, new TransactionClassifier(), CONFIG);
        engine.rm(1_000L);
        engine.setBaseline(new Cc(Collections.singletonMap(BONES, bones)));
        return engine;
    }

    @Test
    public void aBaggedPickupIsAGainThoughTheInventoryNeverChanges()
    {
        Am engine = started(0L);
        engine.bagged(BONES, 3L);
        Ac pickup = settle(engine, Cc.empty(), 1_600L);
        assertEquals(Ai.GAIN, pickup.getType());
        assertTrue(pickup.isCounted());
        assertEquals(105L, engine.getMetrics(5_000L).net);
    }

    @Test
    public void baggingYourOwnDropCancelsTheDropNotNewLoot()
    {
        Am engine = started(1L);
        engine.aki(100, 200, 0);
        engine.abz(BONES, 6, 100, 200, 0, true);
        Ac drop = settle(engine, Cc.empty(), 1_600L);
        assertEquals("Dropped", drop.getNote());
        assertEquals(-35L, engine.getMetrics(2_800L).net);

        engine.noteTake(BONES);
        engine.bagged(BONES, 1L);
        Ac recovery = settle(engine, Cc.empty(), 3_400L);
        assertEquals(Ai.TRANSFER, recovery.getType());
        assertFalse(recovery.isCounted());
        assertEquals("your own item, back in the bag", 0L, engine.getMetrics(5_000L).net);
        assertEquals(0L, engine.getMetrics(5_000L).revenue);
    }

    /** Loot is private to you for a minute; a bag pickup inside it is still yours (research 1.2). */
    @Test
    public void yourLootStaysYoursForTheMinuteItIsPrivate()
    {
        Am engine = started(0L);
        engine.zk(Collections.singletonMap(BONES, 3L), 50, "Loot from Guard", "Guard");
        for (int tick = 0; tick < 60; tick++)
        {
            engine.eb.tick(engine.eb.now + 1);
        }
        assertTrue("past the 30 s attribution window", (engine.eb.has(Eb.LT, BONES) || engine.eb.has(Eb.OD, BONES)));
        for (int tick = 60; tick < 100; tick++)
        {
            engine.eb.tick(engine.eb.now + 1);
        }
        assertFalse("forgotten once the minute is over", (engine.eb.has(Eb.LT, BONES) || engine.eb.has(Eb.OD, BONES)));
    }

    @Test
    public void nothingIsBookedBeforeTrackingHasABaseline()
    {
        Am engine = new Am(valuator, new TransactionClassifier(), CONFIG);
        engine.bagged(BONES, 1L);
        assertNull(engine.baseline);
    }

    /** The catch and cut messages name the item an open barrel or basket takes (owner 1.2). */
    @Test
    public void catchAndCutMessagesNameTheItem()
    {
        String[][] cases = {{"You catch a shark.", null, "shark"}, {"You catch an anglerfish.", null, "anglerfish"},
            {"You catch some shrimps.", null, "shrimps"}, {"You catch 2 karambwanji.", "2", "karambwanji"},
            {"You get some yew logs.", null, "yew logs"}, {"You get some logs.", null, "logs"}};
        for (String[] c : cases)
        {
            java.util.regex.Matcher m = Ak.pattern("bag").matcher(c[0].toLowerCase());
            assertTrue(c[0], m.matches());
            assertEquals(c[0], c[1], m.group(1));
            assertEquals(c[0], c[2], m.group(2));
        }
        assertFalse(Ak.pattern("bag").matcher("you light the logs.").matches());
    }

    private static Ac settle(Am engine, Cc snapshot, long firstTick)
    {
        assertNull(engine.adj(snapshot, firstTick));
        assertNull(engine.adj(snapshot, firstTick + 600L));
        Ac tx = engine.adj(snapshot, firstTick + 1_200L);
        assertNotNull(tx);
        return tx;
    }
}
