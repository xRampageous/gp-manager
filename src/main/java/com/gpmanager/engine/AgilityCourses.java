package com.gpmanager;

import java.util.*;

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
