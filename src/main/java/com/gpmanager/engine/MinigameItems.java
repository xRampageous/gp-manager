package com.gpmanager;

import java.util.Set;

/**
 * Minigame-only items: ids that cannot legally exist outside their minigame (a loaned loadout,
 * table supplies), so their grant and exit strip are both non-economic. Persistent rewards stay
 * tracked. Coinflow MinigameQuarantine evidence, Muse review, owner 2026-10-12.
 */
class MinigameItems {
    static final Set<Integer> IDS = GameData.byId("minigame_items").keySet();

    /** True for an item id that only exists inside its minigame. */
    static boolean isMinigameOnly(int itemId) {
        return IDS.contains(itemId);
    }
}
