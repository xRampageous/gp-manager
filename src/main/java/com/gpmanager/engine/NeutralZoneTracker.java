package com.gpmanager.engine;

/**
 * Region-based ownership-neutral zones. Some instances store the whole inventory and
 * equipment on entry and restore it on exit without any menu click at the exit — The
 * Gauntlet is the canonical case (GP Per Hour issues #7/#8: all gear booked as a loss on
 * entry and a gain on exit). While the player is inside such a region every settled
 * delta is an ownership-neutral transfer; leaving opens one more window so the restore
 * settles as a transfer too. Reward chests are claimed in the lobby, outside the zone,
 * so real loot still counts.
 *
 * <p>Pure state machine over region ids; the plugin feeds it one region per tick.
 */
public final class NeutralZoneTracker
{
    public enum Signal
    {
        /** Not in a neutral zone and nothing to close. */
        NONE,
        /** First tick inside a neutral zone. */
        ENTERED,
        /** Inside a neutral zone: keep the transfer window open this tick. */
        INSIDE,
        /** Just left a neutral zone: open one closing window for the gear restore. */
        LEFT
    }

    private boolean inside;

    /** Feed the current region id (0 or negative when unknown). */
    public Signal onRegion(int regionId)
    {
        boolean now = MinigameRegionHints.isNeutralZoneRegion(regionId);
        if (now)
        {
            boolean entered = !inside;
            inside = true;
            return entered ? Signal.ENTERED : Signal.INSIDE;
        }
        if (inside && regionId > 0)
        {
            inside = false;
            return Signal.LEFT;
        }
        // Unknown region (loading screens) keeps the previous state without signalling.
        return Signal.NONE;
    }

    public boolean isInside()
    {
        return inside;
    }

    public void reset()
    {
        inside = false;
    }
}
