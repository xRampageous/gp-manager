package com.gpmanager;

import java.util.*;

/**
 * Activity naming facts the engine still reads: combat XP never names an activity (the NPC's loot
 * does), and the rules table stays the single source for that skill list. The activity display was
 * retired (owner live 2026-10-13): the Live hero shows the run's own name and nothing else, so the
 * presentation ladder that used to live here is gone. Presentation only: it never books or values.
 */
class ActivityLabel {
    /** XP in these is fighting, not an activity of its own. */
    static final List<String> COMBAT = list("combat");

    /** The compiled activity rules; the table remains the only source for these rows. */
    static final Map<String, Phrases.Clause[][]> RULES = GameData.rules("activity_rules");

    /** Original list fields retain their exact values; the table is their only source. */
    static List<String> list(String name) {
        for (String[] row : GameData.cachedRows("activity_rules"))
            if (row[0].equals(name))
                return Arrays.stream(row[2].split("\\|"))
                        .map(GameData::text)
                        .collect(java.util.stream.Collectors.toList());
        throw new IllegalArgumentException(name);
    }
}

/**
 * Agility courses by map region, so Agility XP earns a named activity ("Wilderness Agility Course")
 * instead of the bare skill. Presentation / activity naming only — accounting never reads this.
 * Region ids follow the wiki course maps (and RuneLite's agility plugin).
 */
class AgilityCourses {
    static final Map<Integer, String> BY_REGION = GameData.byId("agility_courses");

    /** Course name for a region, or null when the region is not a known course. */
    static String courseName(int regionId) {
        return BY_REGION.get(regionId);
    }

    /** The Wilderness course's reward dispenser and the Brimhaven ticket dispenser. */

    /** Name-only dispenser evidence, independent of the region, as before. */
    static final java.util.regex.Pattern DISPENSER = GameData.pattern("dispenser");

    static boolean isDispenser(String name) {
        return name != null && DISPENSER.matcher(Phrases.lower(name)).matches();
    }
}
