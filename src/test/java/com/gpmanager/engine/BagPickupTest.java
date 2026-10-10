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
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Item " + id, quantity, 35, quantity * 35)));
        return flows;
    };

    private Engine started(long bones)
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
        engine.ensureSession(1_000L);
        engine.setBaseline(new ContainerSnapshot(Collections.singletonMap(BONES, bones)));
        return engine;
    }

    @Test
    public void aBaggedPickupIsAGainThoughTheInventoryNeverChanges()
    {
        Engine engine = started(0L);
        engine.bagged(BONES, 3L);
        Transaction pickup = settle(engine, ContainerSnapshot.empty(), 1_600L);
        assertEquals(TransactionType.GAIN, pickup.getType());
        assertTrue(pickup.isCounted());
        assertEquals(105L, engine.getMetrics(5_000L).net);
    }


    /** Loot is private to you for a minute; a bag pickup inside it is still yours (research 1.2). */
    @Test
    public void yourLootStaysYoursForTheMinuteItIsPrivate()
    {
        Engine engine = started(0L);
        engine.markLoot(Collections.singletonMap(BONES, 3L), 50, "Loot from Guard", "Guard");
        for (int tick = 0; tick < 60; tick++)
        {
            engine.inbox.tick(engine.inbox.now + 1);
        }
        assertTrue("past the 30 s attribution window", (engine.inbox.has(Inbox.PRIVATE_LOOT, BONES)));
        for (int tick = 60; tick < 100; tick++)
        {
            engine.inbox.tick(engine.inbox.now + 1);
        }
        assertFalse("forgotten once the minute is over", (engine.inbox.has(Inbox.PRIVATE_LOOT, BONES)));
    }

    @Test
    public void nothingIsBookedBeforeTrackingHasABaseline()
    {
        Engine engine = new Engine(valuator, new TransactionClassifier(), CONFIG);
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
            java.util.regex.Matcher m = GameData.pattern("bag").matcher(c[0].toLowerCase());
            assertTrue(c[0], m.matches());
            assertEquals(c[0], c[1], m.group(1));
            assertEquals(c[0], c[2], m.group(2));
        }
        assertFalse(GameData.pattern("bag").matcher("you light the logs.").matches());
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
