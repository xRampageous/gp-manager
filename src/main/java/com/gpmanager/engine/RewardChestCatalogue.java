package com.gpmanager;

import java.util.*;

/**
 * Reward-interface / chest adapters → Pending Rewards → Claimed once. Name matching for Loot
 * Tracker EVENT + live reward UIs.
 */
class RewardChestCatalogue {
    /** True when this LT/event name is a reward chest / raid / clue pool — not floor loot. */

    /** Reward fragments share the same once-compiled matcher as other evidence tables. */
    static final Map<String, Phrases.Clause[][]> RULES = GameData.rules("reward_chests");

    static boolean isRewardChest(String raw) {
        if (Phrases.blank(raw)) return false;
        if (KeyChestCatalogue.entryForChest(raw) != null) return true;
        String name = Phrases.lower(raw);
        return RULES.values().stream().anyMatch(c -> Phrases.has(name, c));
    }
}
