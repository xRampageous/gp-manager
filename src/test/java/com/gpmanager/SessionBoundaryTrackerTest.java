package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Automatic boundaries fire on the right moments only: a slayer stretch opens on the first
 * Slayer XP with a task active (not at assignment) and closes when the count reaches zero;
 * raids open on entry and close on exit; a bank visit is the close, not the open.
 */
public class SessionBoundaryTrackerTest
{
    private final Map<Integer, Integer> varps = new HashMap<>();
    private final Map<Integer, Integer> varbits = new HashMap<>();
    private final List<String> events = new ArrayList<>();
    private final SessionBoundaryTracker tracker = new SessionBoundaryTracker(
        (kind, event, label) -> events.add(kind + ":" + event + ":" + label));

    private Client client()
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class}, (proxy, method, args) ->
        {
            switch (method.getName())
            {
                case "getVarpValue":
                    return varps.getOrDefault((Integer) args[0], 0);
                case "getVarbitValue":
                    return varbits.getOrDefault((Integer) args[0], 0);
                case "getLocalPlayer":
                    return null;
                case "getDBTableField":
                    return new Object[] {"Abyssal demons"};
                default:
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class)
                    {
                        return false;
                    }
                    if (r == int.class)
                    {
                        return 0;
                    }
                    if (r == long.class)
                    {
                        return 0L;
                    }
                    return null;
            }
        });
    }

    @Test
    public void slayerStretchOpensOnFirstXpAndClosesOnCompletion()
    {
        Client client = client();
        tracker.onTick(client, false);            // baseline, no task
        varps.put(VarPlayerID.SLAYER_COUNT, 120);
        varps.put(VarPlayerID.SLAYER_TARGET, 42);
        tracker.onTick(client, false);
        assertEquals(1, events.size());
        assertEquals("SLAYER:POINT:Task assigned · Abyssal demons", events.get(0));
        assertFalse(tracker.isSlayerStretchOpen());

        tracker.onSlayerXp();                     // first kill
        assertTrue(tracker.isSlayerStretchOpen());
        assertEquals("SLAYER:START:Slayer · Abyssal demons", events.get(1));
        tracker.onSlayerXp();                     // more kills: nothing new
        assertEquals(2, events.size());

        varps.put(VarPlayerID.SLAYER_COUNT, 0);
        tracker.onTick(client, false);
        assertEquals("SLAYER:END:Slayer · Abyssal demons", events.get(2));
        assertFalse(tracker.isSlayerStretchOpen());
    }

    @Test
    public void slayerXpWithoutATaskDoesNothing()
    {
        tracker.onTick(client(), false);
        tracker.onSlayerXp();
        assertTrue(events.isEmpty());
    }

    @Test
    public void raidsOpenOnEntryAndCloseOnExit()
    {
        Client client = client();
        tracker.onTick(client, false);
        varbits.put(VarbitID.RAIDS_CLIENT_INDUNGEON, 1);
        tracker.onTick(client, false);
        tracker.onTick(client, false);
        assertEquals(1, events.size());
        assertEquals("RAID:START:Chambers of Xeric", events.get(0));
        assertEquals("Chambers of Xeric", tracker.openRaid());
        varbits.put(VarbitID.RAIDS_CLIENT_INDUNGEON, 0);
        tracker.onTick(client, false);
        assertEquals("RAID:END:Chambers of Xeric", events.get(1));

        varbits.put(VarbitID.TOB_CLIENT_PARTYSTATUS, 2);
        tracker.onTick(client, false);
        assertEquals("RAID:START:Theatre of Blood", events.get(2));
    }

    @Test
    public void bankVisitIsTheClose()
    {
        Client client = client();
        tracker.onTick(client, false);
        tracker.onTick(client, true);
        tracker.onTick(client, true);
        assertTrue(events.isEmpty());
        tracker.onTick(client, false);
        assertEquals(1, events.size());
        assertEquals("BANK:POINT:Bank visit", events.get(0));
    }
}
