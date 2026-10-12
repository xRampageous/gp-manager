package com.gpmanager;

import java.util.*;

/**
 * The one activity label HUD+ and Live both show. It never names an NPC (owner 1.1: other plugins
 * do): a named Grind, else a raid, minigame or skilling activity, else "Combat" for fighting and
 * NPC loot. Generic placeholders never surface. Presentation only: it never books or values.
 */
class ActivityLabel {
    /** Raids and minigames whose session name outranks a fresh target. */
    static final List<String> CURATED = list("curated");

    static final List<String> CURATED_SHORT = list("short");

    /** XP in these is fighting, not an activity of its own. */
    static final List<String> COMBAT = list("combat");

    /** The status-line label: the named run, else a named activity, else Combat, else empty. */

    /** A raid, minigame, skill, agility course or one of the named pursuits; never an NPC. */

    /** True when the session activity is a raid or minigame that keeps its name over a target. */

    /** Trim, and drop generic placeholders that are not activities. */

    /** Label predicates share the compiled rule matcher; skill case matching remains unchanged. */
    static final Map<String, Phrases.Clause[][]> RULES = GameData.rules("activity_rules");

    static boolean isCurated(String raw) {
        String name = Phrases.lower(raw);
        return !name.isEmpty()
                && (Phrases.has(name, RULES.get("short"))
                        || Phrases.has(name, RULES.get("curated")));
    }

    static String specific(String raw) {
        String name = raw == null ? "" : raw.trim();
        return name.isEmpty() || Phrases.has(name, RULES.get("generic")) ? "" : name;
    }

    static boolean isActivity(String hint) {
        if (isCurated(hint)
                || AgilityCourses.BY_REGION.containsValue(hint)
                || Phrases.has(hint, RULES.get("pursuit"))) return true;
        for (net.runelite.api.Skill skill : net.runelite.api.Skill.values()) {
            if (skill.getName().equalsIgnoreCase(hint)) return !COMBAT.contains(skill.getName());
        }
        return false;
    }

    static String label(
            boolean fighting,
            String activity,
            String ownerLabel,
            boolean hasSession,
            boolean freePlay) {
        if (hasSession && !freePlay) return Fmt.activity(ownerLabel);
        String hint = specific(activity);
        if (isActivity(hint)) return Fmt.activity(hint);
        // What is left came from fighting: an NPC's loot names it, or combat XP.
        return fighting || !hint.isEmpty() ? "Combat" : "";
    }

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
