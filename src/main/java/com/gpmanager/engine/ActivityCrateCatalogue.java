package com.gpmanager.engine;

import java.util.Locale;

/**
 * Birdhouse / herbiboar / Giants' Foundry / Bounty Hunter crate paths.
 */
public final class ActivityCrateCatalogue
{
    public enum Kind
    {
        BIRDHOUSE,
        HERBIBOAR,
        GIANTS_FOUNDRY,
        BOUNTY_HUNTER_CRATE
    }

    private ActivityCrateCatalogue()
    {
    }

    public static Kind classify(String nameOrNote)
    {
        String lower = norm(nameOrNote);
        if (lower == null)
        {
            return null;
        }
        if (lower.contains("bird house") || lower.contains("birdhouse")
            || lower.contains("bird's nest") || lower.contains("birds nest"))
        {
            return Kind.BIRDHOUSE;
        }
        if (lower.contains("herbiboar"))
        {
            return Kind.HERBIBOAR;
        }
        if (lower.contains("giants' foundry") || lower.contains("giants foundry")
            || lower.contains("foundry"))
        {
            return Kind.GIANTS_FOUNDRY;
        }
        if (lower.contains("bounty hunter")
            || (lower.contains("crate") && lower.contains("bounty")))
        {
            return Kind.BOUNTY_HUNTER_CRATE;
        }
        return null;
    }

    /** BH crates and similar reward UIs → Pending Rewards; birdhouse nests are Hunter gains. */
    public static boolean usesPendingRewards(Kind kind)
    {
        return kind == Kind.BOUNTY_HUNTER_CRATE;
    }

    public static boolean isHunterCaught(Kind kind)
    {
        return kind == Kind.BIRDHOUSE || kind == Kind.HERBIBOAR;
    }

    public static boolean isSmithingProcess(Kind kind)
    {
        return kind == Kind.GIANTS_FOUNDRY;
    }

    public static String activityLabel(Kind kind)
    {
        if (kind == null)
        {
            return "Activity";
        }
        switch (kind)
        {
            case BIRDHOUSE:
                return "Hunter";
            case HERBIBOAR:
                return "Hunter";
            case GIANTS_FOUNDRY:
                return "Smithing";
            case BOUNTY_HUNTER_CRATE:
                return "Bounty Hunter";
            default:
                return "Activity";
        }
    }

    private static String norm(String value)
    {
        if (value == null)
        {
            return null;
        }
        String key = value.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }
}
