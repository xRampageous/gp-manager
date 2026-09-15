package com.gpmanager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Watches the client for the moments a session naturally starts or ends: a slayer task's
 * first kill and its completion, entering and leaving a raid, and closing the bank. Emits
 * events only; what happens (mark, new session, nothing) is the owner's setting, applied
 * by the plugin. Presentation / session naming only — accounting never reads this.
 */
final class SessionBoundaryTracker
{
    enum Kind
    {
        SLAYER, RAID, BANK
    }

    enum Event
    {
        /** A stretch begins: first slayer kill of a task, raid entered. */
        START,
        /** A stretch ends: task complete, raid left. */
        END,
        /** A point in time: task assigned, bank closed. */
        POINT
    }

    interface Sink
    {
        void onBoundary(Kind kind, Event event, String label);
    }

    /** Tombs of Amascut has no client varbit for "inside"; its rooms are region-bound. */
    private static final Set<Integer> TOA_REGIONS = new HashSet<>(Arrays.asList(
        14160, 14162, 14164, 14674, 14676, 14678, 15184, 15186, 15188, 15696, 15698, 15700));

    private final Sink sink;
    private int lastSlayerCount = Integer.MIN_VALUE;
    private int lastSlayerTarget = Integer.MIN_VALUE;
    private boolean slayerStretchOpen;
    private String slayerLabel = "Slayer";
    @Nullable
    private String raidOpen;
    private boolean bankWasOpen;

    SessionBoundaryTracker(Sink sink)
    {
        this.sink = sink;
    }

    /** Once per game tick while logged in. */
    void onTick(Client client, boolean bankOpen)
    {
        // Slayer: task assigned / completed from the varps; the stretch itself opens on XP.
        int count = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
        int target = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
        if (lastSlayerCount != Integer.MIN_VALUE)
        {
            boolean assigned = count > 0 && (lastSlayerCount == 0 || target != lastSlayerTarget);
            boolean completed = count == 0 && lastSlayerCount > 0;
            if (assigned)
            {
                slayerLabel = "Slayer · " + taskName(client, target);
                sink.onBoundary(Kind.SLAYER, Event.POINT, "Task assigned · " + taskName(client, target));
            }
            if (completed)
            {
                if (slayerStretchOpen)
                {
                    slayerStretchOpen = false;
                    sink.onBoundary(Kind.SLAYER, Event.END, slayerLabel);
                }
                else
                {
                    sink.onBoundary(Kind.SLAYER, Event.POINT, "Task complete");
                }
            }
        }
        else if (count > 0)
        {
            slayerLabel = "Slayer · " + taskName(client, target);
        }
        lastSlayerCount = count;
        lastSlayerTarget = target;

        // Raids: CoX and ToB from varbits, ToA from the region.
        String raid = null;
        if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 1)
        {
            raid = "Chambers of Xeric";
        }
        else if (client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS) == 2)
        {
            raid = "Theatre of Blood";
        }
        else
        {
            Player local = client.getLocalPlayer();
            if (local != null && local.getWorldLocation() != null
                && TOA_REGIONS.contains(local.getWorldLocation().getRegionID()))
            {
                raid = "Tombs of Amascut";
            }
        }
        if (raid != null && raidOpen == null)
        {
            raidOpen = raid;
            sink.onBoundary(Kind.RAID, Event.START, raid);
        }
        else if (raid == null && raidOpen != null)
        {
            String ended = raidOpen;
            raidOpen = null;
            sink.onBoundary(Kind.RAID, Event.END, ended);
        }

        // Bank: a visit is the moment the bank closes.
        if (bankWasOpen && !bankOpen)
        {
            sink.onBoundary(Kind.BANK, Event.POINT, "Bank visit");
        }
        bankWasOpen = bankOpen;
    }

    /** Slayer XP with a task active is the first kill: that is when the stretch begins. */
    void onSlayerXp()
    {
        if (lastSlayerCount > 0 && !slayerStretchOpen)
        {
            slayerStretchOpen = true;
            sink.onBoundary(Kind.SLAYER, Event.START, slayerLabel);
        }
    }

    boolean isSlayerStretchOpen()
    {
        return slayerStretchOpen;
    }

    @Nullable
    String openRaid()
    {
        return raidOpen;
    }

    /** Task creature name from the cache's slayer table; falls back to a plain label. */
    static String taskName(Client client, int taskId)
    {
        if (taskId <= 0)
        {
            return "task";
        }
        try
        {
            Object[] name = client.getDBTableField(taskId, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0);
            if (name != null && name.length > 0 && name[0] != null && !name[0].toString().trim().isEmpty())
            {
                return name[0].toString().trim();
            }
        }
        catch (RuntimeException ignored)
        {
            // cache table unavailable off the client thread or in tests
        }
        return "task";
    }

    /** Test seam. */
    void reset()
    {
        lastSlayerCount = Integer.MIN_VALUE;
        lastSlayerTarget = Integer.MIN_VALUE;
        slayerStretchOpen = false;
        raidOpen = null;
        bankWasOpen = false;
    }
}
