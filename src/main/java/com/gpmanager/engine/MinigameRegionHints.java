package com.gpmanager.engine;

/**
 * Conservative region hints for LMS / raid instances when menu text is sparse.
 * Live producers pass {@link net.runelite.api.coords.WorldPoint#getRegionID()}.
 */
public final class MinigameRegionHints
{
    /** Common LMS instance regions (not exhaustive — menu/chat still primary). */
    private static final int[] LMS_REGIONS =
    {
        13658, 13659, 13660, 13661, 13662, 13663, 13664, 13665
    };

    /** CoX / ToB / ToA reward and storage instances (partial). */
    private static final int[] RAID_REGIONS =
    {
        12889, 13122, 13123, 13125, 12613, 12614, 12615, 12616
    };

    /**
     * Instances that store the player's gear on entry and restore it on exit with no
     * exit click: The Gauntlet (7512) and the Corrupted Gauntlet (7768). The lobby
     * (12127) is deliberately excluded — the reward chest is claimed there. Ids match
     * the maintained Hunllef helper plugin (Loze-Put/hunllef-helper, PluginConstants).
     */
    private static final int[] NEUTRAL_ZONE_REGIONS =
    {
        7512, 7768
    };

    private MinigameRegionHints()
    {
    }

    /** True inside an instance whose inventory changes are never profit or loss. */
    public static boolean isNeutralZoneRegion(int regionId)
    {
        return contains(NEUTRAL_ZONE_REGIONS, regionId);
    }

    public static boolean isLmsRegion(int regionId)
    {
        return contains(LMS_REGIONS, regionId);
    }

    public static boolean isRaidRegion(int regionId)
    {
        return contains(RAID_REGIONS, regionId);
    }

    private static boolean contains(int[] regions, int regionId)
    {
        if (regionId <= 0)
        {
            return false;
        }
        for (int region : regions)
        {
            if (region == regionId)
            {
                return true;
            }
        }
        return false;
    }
}
