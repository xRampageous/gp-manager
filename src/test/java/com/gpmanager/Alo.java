package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.util.*;
/**
* The one activity label HUD+ and Live both show. It never names an NPC (owner 1.1: other plugins
* do): a named Grind, else a raid, minigame or skilling activity, else "Combat" for fighting and
* NPC loot. Generic placeholders never surface. Presentation only: it never books or values.
*/
class Alo {
/** Raids and minigames whose session name outranks a fresh target. */
static final List<String> CURATED = Arrays.asList("theatre of blood", "chambers of xeric",
"tombs of amascut", msg("fe"), "barbarian assault", "tempoross", "wintertodt", "inferno",
"fight caves", "colosseum", "nightmare", "gauntlet");
static final List<String> CURATED_SHORT = Arrays.asList("tob", "cox", "toa", "gotr");
/** XP in these is fighting, not an activity of its own. */
static final List<String> COMBAT = Arrays.asList("Attack", "Strength", "Defence", "Ranged", "Hitpoints");

/** The status-line label: the named run, else a named activity, else Combat, else empty. */
static String resolve(boolean /* fighting */a, String /* activity */b, String /* ownerLabel */c, boolean /* hasSession */d, boolean /* freePlay */e) {
 if (d && !e) return Fmt.activity(c);
 String /* hint */f = clean(b);
 if (named(f)) return Fmt.activity(f);
 // What is left came from fighting: an NPC's loot names it, or combat XP.
 return a || !f.isEmpty() ? "Combat" : "";
}

/** A raid, minigame, skill, agility course or one of the named pursuits; never an NPC. */
static boolean named(String /* hint */a) {
 if (curated(a) || AgilityCourses.BY_REGION.containsValue(a)
 || Arrays.asList("PKing", "Trading", "Death reclaim").contains(a)) return true;
 for (net.runelite.api.Skill /* skill */b : net.runelite.api.Skill.values()) {
  if (b.getName().equalsIgnoreCase(a)) return !COMBAT.contains(b.getName());
 }
 return false;
}

/** True when the session activity is a raid or minigame that keeps its name over a target. */
static boolean curated(String /* activity */a) {
 String /* lower */b = Ag.lower(a);
 if (b.isEmpty()) return false;
 if (CURATED_SHORT.contains(b)) return true;
 for (String /* name */c : CURATED) {
  if (b.contains(c)) return true;
 }
 return false;
}

/** Trim, and drop generic placeholders that are not activities. */
static String clean(String /* value */a) {
 String /* trimmed */b = a == null ? "" : a.trim();
 return b.isEmpty() || "General".equalsIgnoreCase(b) || "NPC loot".equalsIgnoreCase(b) ? "" : b;
}
}
