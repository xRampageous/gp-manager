package com.gpmanager.engine;

import com.gpmanager.model.KeyChestCatalogue;
import java.util.Locale;

/**
 * Reward-interface / chest adapters → Pending Rewards → Claimed once.
 * Name matching for Loot Tracker EVENT + live reward UIs.
 */
public final class RewardChestCatalogue
{
    private RewardChestCatalogue()
    {
    }

    /** True when this LT/event name is a reward chest / raid / clue pool — not floor loot. */
    public static boolean isPendingRewardName(String name)
    {
        if (name == null || name.trim().isEmpty())
        {
            return false;
        }
        if (KeyChestCatalogue.entryForChestMention(name) != null)
        {
            return true;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return containsAny(lower,
            "chambers of xeric",
            "theatre of blood",
            "tombs of amascut",
            "barrows",
            "gauntlet",
            "tempoross",
            "wintertodt",
            "guardians of the rift",
            "fishing trawler",
            "drift net",
            "colosseum",
            "lunar chest",
            "loot chest",
            "wilderness loot",
            "clue",
            "casket",
            "reward",
            "bounty hunter",
            "crates",
            "bird house",
            "birdhouse",
            "herbiboar",
            "giants' foundry",
            "giants foundry");
    }

    /**
     * GOTR cell/catalytic/elemental deposits are ownership-neutral — not RC spends
     * and not Pending Rewards claims.
     */
    public static boolean isOwnershipNeutralGotrDeposit(String note)
    {
        String lower = norm(note);
        if (lower == null)
        {
            return false;
        }
        return lower.contains("guardians of the rift")
            && (lower.contains("deposit")
                || lower.contains("cell")
                || lower.contains("catalytic")
                || lower.contains("elemental"));
    }

    public static boolean isWildernessLootChest(String name)
    {
        String lower = norm(name);
        return lower != null
            && (lower.contains("loot chest") || lower.contains("wilderness loot"));
    }

    private static boolean containsAny(String haystack, String... needles)
    {
        for (String needle : needles)
        {
            if (haystack.contains(needle))
            {
                return true;
            }
        }
        return false;
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
