package com.gpmanager;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static com.gpmanager.ReviewFixesTest.T0;
import static com.gpmanager.ReviewFixesTest.engine;
import static com.gpmanager.ReviewFixesTest.plugin;
import static com.gpmanager.ReviewFixesTest.proxy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The 1.5.0 guards: separate-economy worlds pause tracking, and windows hold the activity label. */
public class WorldPauseAndWindowHoldTest
{
    @Test
    public void aSeparateEconomyWorldPausesAndLeavingResumes()
    {
        Engine engine = engine();
        engine.pauseForWorld(T0 + 1_000L);
        assertTrue("a separate-economy world pauses the session", engine.getActiveSession().paused);
        assertEquals(PauseReason.WORLD, engine.getActiveSession().getPauseReason());
        assertFalse("nothing resumes while still there",
                engine.resume(T0 + 1_500L, PauseReason.WORLD, PauseReason.IDLE));
        engine.separateWorld = false;
        assertTrue(engine.resume(T0 + 2_000L, PauseReason.WORLD));
        assertFalse("leaving resumes like a relog", engine.getActiveSession().paused);
    }

    @Test
    public void anIdlePauseCarriedOntoTheWorldStaysPaused()
    {
        Engine engine = engine();
        engine.getActiveSession().pause(T0 + 1_000L, PauseReason.IDLE);
        engine.pauseForWorld(T0 + 2_000L);
        assertFalse("gameplay there never lifts the idle pause",
                engine.resume(T0 + 3_000L, PauseReason.LIFECYCLE, PauseReason.IDLE, PauseReason.RECOVERY));
        assertTrue(engine.getActiveSession().paused);
    }

    @Test
    public void aWorldPauseNeverTouchesAManualOne()
    {
        Engine engine = engine();
        engine.getActiveSession().pause(T0 + 1_000L, PauseReason.MANUAL);
        engine.pauseForWorld(T0 + 2_000L);
        assertEquals("the manual pause stands", PauseReason.MANUAL, engine.getActiveSession().getPauseReason());
        assertFalse("a world resume cannot lift a manual pause", engine.resume(T0 + 3_000L, PauseReason.WORLD));
    }

    @Test
    public void theTickPausesOnLeaguesBeforeAnythingIsRead()
    {
        Engine engine = engine();
        ReviewFixesTest.Plugin p = plugin(engine, new int[] {11});
        p.client = clientOn(WorldType.SEASONAL);
        assertTrue("a Leagues world is a separate economy", p.onUnsupportedWorld());
        // The fixture wires no valuation, pvp or activity: reaching them would throw.
        p.onGameTick(null);
        assertTrue(engine.separateWorld);
        assertEquals(PauseReason.WORLD, engine.getActiveSession().getPauseReason());
        p.client = clientOn(WorldType.MEMBERS);
        assertFalse("a members world tracks", p.onUnsupportedWorld());
    }

    @Test
    public void anExchangeOrTradeWindowHoldsTheActivityLabel()
    {
        ReviewFixesTest.Plugin p = plugin(engine(), new int[] {11});
        p.client = proxy(Client.class, Map.of("getWidget", proxy(Widget.class, new HashMap<>())));
        assertTrue("an open exchange window holds the label", p.windowHoldsActivity());
        p.client = proxy(Client.class, new HashMap<>());
        assertFalse("no window, no hold", p.windowHoldsActivity());
    }

    static Client clientOn(WorldType type)
    {
        Map<String, Object> values = new HashMap<>();
        values.put("getGameState", GameState.LOGGED_IN);
        values.put("getWorldType", EnumSet.of(type));
        return proxy(Client.class, values);
    }
}
