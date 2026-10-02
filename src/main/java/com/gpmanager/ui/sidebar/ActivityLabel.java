package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.util.*;
/**
* The one activity label HUD+ and Live both show. It never names an NPC (owner 1.1: other plugins
* do): a named Grind, else a raid, minigame or skilling activity, else "Combat" for fighting and
* NPC loot. Generic placeholders never surface. Presentation only: it never books or values.
*/
class ActivityLabel {
/** Raids and minigames whose session name outranks a fresh target. */
static final List<String> CURATED = Arrays.asList("theatre of blood", "chambers of xeric",
"tombs of amascut", msg("fe"), "barbarian assault", "tempoross", "wintertodt", "inferno",
"fight caves", "colosseum", "nightmare", "gauntlet");
static final List<String> CURATED_SHORT = Arrays.asList("tob", "cox", "toa", "gotr");
/** XP in these is fighting, not an activity of its own. */
static final List<String> COMBAT = Arrays.asList("Attack", "Strength", "Defence", "Ranged", "Hitpoints");

/** The status-line label: the named run, else a named activity, else Combat, else empty. */
static String resolve(boolean fighting, String activity, String ownerLabel, boolean hasSession, boolean freePlay) {
 if (hasSession && !freePlay) return Fmt.activity(ownerLabel);
 String hint = clean(activity);
 if (named(hint)) return Fmt.activity(hint);
 // What is left came from fighting: an NPC's loot names it, or combat XP.
 return fighting || !hint.isEmpty() ? "Combat" : "";
}

/** A raid, minigame, skill, agility course or one of the named pursuits; never an NPC. */
static boolean named(String hint) {
 if (curated(hint) || AgilityCourses.BY_REGION.containsValue(hint)
 || Arrays.asList("PKing", "Trading", "Death reclaim").contains(hint)) return true;
 for (net.runelite.api.Skill skill : net.runelite.api.Skill.values()) {
  if (skill.getName().equalsIgnoreCase(hint)) return !COMBAT.contains(skill.getName());
 }
 return false;
}

/** True when the session activity is a raid or minigame that keeps its name over a target. */
static boolean curated(String activity) {
 String lower = activity == null ? "" : activity.trim().toLowerCase(Locale.ROOT);
 if (lower.isEmpty()) return false;
 if (CURATED_SHORT.contains(lower)) return true;
 for (String name : CURATED) {
  if (lower.contains(name)) return true;
 }
 return false;
}

/** Trim, and drop generic placeholders that are not activities. */
static String clean(String value) {
 String trimmed = value == null ? "" : value.trim();
 return trimmed.isEmpty() || "General".equalsIgnoreCase(trimmed) || "NPC loot".equalsIgnoreCase(trimmed) ? "" : trimmed;
}
}
