package com.gpmanager;

import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.ItemDespawned;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The game's ammo-picking setting puts picked-up darts straight into the blowpipe (OSRS Wiki): the
 * pack never gains them, yet the shot they came from is recovered, once.
 */
public class PipePickupTest
{
    private static final int DART = 807;
    private static final String PIPE = "pipe";

    private static Flow dartsSpent(long quantity)
    {
        return new Flow(DART, "Iron dart", -quantity, 10, -quantity * 10, PriceSource.GRAND_EXCHANGE);
    }

    /** A Checked pipe, twelve shots on the ground, and a Take on eight of them. */
    private static ReviewFixesTest.Plugin firedThenTake(long packAfterPickup, int[] tick)
    {
        Engine engine = ReviewFixesTest.engine();
        engine.observeCheck(ChargeCheck.parse("Darts: Iron dart x 100. Scales: 100 (1.0%)."), PIPE,
            ReviewFixesTest.T0);
        for (int shot = 0; shot < 12; shot++)
        {
            engine.bookEstimatedCharge(PIPE, "Toxic blowpipe", ActionKind.CAST,
                Collections.singletonList(dartsSpent(1)), ReviewFixesTest.T0 + 100L + shot);
        }
        ReviewFixesTest.Plugin p = ReviewFixesTest.plugin(engine, tick);
        p.snapshotFactory = new ContainerSnapshotFactory(p.client, null)
        {
            @Override
            ContainerSnapshot capture(boolean equipment, boolean pouch)
            {
                return new ContainerSnapshot(Collections.singletonMap(DART, packAfterPickup));
            }

            @Override
            boolean bagOpen()
            {
                return false;
            }
        };
        p.charges.blowpipeDart = DART;
        Observation take = ReviewFixesTest.take(p);
        take.item = DART;
        take.at = 10;
        take.x = 10;
        take.y = 10;
        take.held = 0L;
        Map<String, Object> pile = new HashMap<>();
        pile.put("getId", DART);
        pile.put("getQuantity", 8);
        pile.put("getOwnership", TileItem.OWNERSHIP_SELF);
        p.onItemDespawned(new ItemDespawned(ReviewFollowupTest.tile(10, 10),
            ReviewFixesTest.proxy(TileItem.class, pile)));
        return p;
    }

    @Test
    public void dartsTakenStraightIntoThePipeRecoverTheShot()
    {
        int[] tick = {11};
        ReviewFixesTest.Plugin p = firedThenTake(0L, tick);
        p.pipePickups();
        assertEquals("the decision waits for the pack", 120L, p.engine.getMetrics(ReviewFixesTest.T0 + 1_000L).costs);
        tick[0] = 13;
        p.pipePickups();
        assertEquals("8 of the 12 darts went back into the pipe", 40L,
            p.engine.getMetrics(ReviewFixesTest.T0 + 1_000L).costs);
        p.pipePickups();
        assertEquals("one pickup recovers once", 40L, p.engine.getMetrics(ReviewFixesTest.T0 + 1_000L).costs);
    }

    @Test
    public void dartsThePackGainedAreLeftToTheOrdinaryPickup()
    {
        int[] tick = {11};
        ReviewFixesTest.Plugin p = firedThenTake(8L, tick);
        tick[0] = 13;
        p.pipePickups();
        assertEquals("the pack's own settle books this pickup, not the pipe path", 120L,
            p.engine.getMetrics(ReviewFixesTest.T0 + 1_000L).costs);
    }
}
