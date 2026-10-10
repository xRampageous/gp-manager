package com.gpmanager;

/**
 * Ownership-neutral moves, treated like a bank TRANSFER: world and coin storages (seed vault, tool
 * leprechaun, coffers, STASH units...) and minigame moves (LMS loadouts, raid bags, GIM shared
 * storage). Never invent profit or loss on deposit, withdraw, enter or exit, and never book a full
 * inventory as Lost or loot on an LMS enter or exit.
 *
 * <p>The rows live in neutral_transfers.tsv and are checked in file order, so the first match wins:
 * minigame rows have "m" keys, and the ".lms" rows apply only inside an LMS region.
 */
class NeutralTransfers {
    static final java.util.Map<String, Phrases.Clause[][]> RULES =
            GameData.rules("neutral_transfers");

    /** The transfer note of the first storage row matching this menu text, or null. */
    static String storage(String raw) {
        return match(Phrases.norm(raw), false, -1);
    }

    static String minigame(String raw) {
        return minigame(raw, -1);
    }

    /** The transfer note of the first minigame row matching this menu text, or null. */
    static String minigame(String raw, int regionId) {
        return match(Phrases.lower(raw), true, regionId);
    }

    private static String match(String name, boolean minigame, int regionId) {
        boolean lms = regionId >= 0 && MinigameRegionHints.isLmsRegion(regionId);
        for (String[] row : GameData.cachedRows("neutral_transfers")) {
            if (row[0].startsWith("m") != minigame || row[0].endsWith(".lms") && !lms) continue;
            if (Phrases.has(name, RULES.get(row[0]))) return GameData.text(row[3]);
        }
        return null;
    }
}
