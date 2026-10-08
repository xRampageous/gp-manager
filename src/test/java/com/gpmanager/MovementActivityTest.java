package com.gpmanager;

import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Moving is not AFK (owner 1.2): a changed tile counts as activity, standing still does not. */
public class MovementActivityTest
{
    @Test
    public void aChangedTileIsMovementAndTheFirstSightingIsNot()
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        assertFalse("nothing to compare on the first tick", plugin.moved(new WorldPoint(3200, 3200, 0)));
        assertFalse("standing still", plugin.moved(new WorldPoint(3200, 3200, 0)));
        assertTrue("one step", plugin.moved(new WorldPoint(3201, 3200, 0)));
        assertTrue("a plane change", plugin.moved(new WorldPoint(3201, 3200, 1)));
        assertFalse("no player this tick", plugin.moved(null));
        assertFalse("after a gap, the next sighting is a fresh start", plugin.moved(new WorldPoint(3300, 3300, 0)));
    }
}
