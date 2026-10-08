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
static final List<String> CURATED = l("curated");
static final List<String> CURATED_SHORT = l("short");
/** XP in these is fighting, not an activity of its own. */
static final List<String> COMBAT = l("combat");

/** The status-line label: the named run, else a named activity, else Combat, else empty. */


/** A raid, minigame, skill, agility course or one of the named pursuits; never an NPC. */


/** True when the session activity is a raid or minigame that keeps its name over a target. */


/** Trim, and drop generic placeholders that are not activities. */


/** Label predicates share the compiled rule matcher; skill case matching remains unchanged. */
static final Map<String, Ag.P[][]> R = Ak.r("d28");
static boolean b1(String a) {
 String b = Ag.lower(a);
 return !b.isEmpty() && (Ag.has(b, R.get("short")) || Ag.has(b, R.get("curated")));
}
static String c1(String a) {
 String b = a == null ? "" : a.trim();
 return b.isEmpty() || Ag.has(b, R.get("generic")) ? "" : b;
}
static boolean a1(String /* hint */a) {
 if (b1(a) || AgilityCourses.BY_REGION.containsValue(a)
 || Ag.has(a, R.get("pursuit"))) return true;
 for (net.runelite.api.Skill /* skill */b : net.runelite.api.Skill.values()) {
  if (b.getName().equalsIgnoreCase(a)) return !COMBAT.contains(b.getName());
 }
 return false;
}
static String d1(boolean /* fighting */a, String /* activity */b, String /* ownerLabel */c, boolean /* hasSession */d, boolean /* freePlay */e) {
 if (d && !e) return Fmt.activity(c);
 String /* hint */f = c1(b);
 if (a1(f)) return Fmt.activity(f);
 // What is left came from fighting: an NPC's loot names it, or combat XP.
 return a || !f.isEmpty() ? "Combat" : "";
}

/** Original list fields retain their exact values; the table is their only source. */
static List<String> l(String a) {
 for (String[] b : Ak.x("d28")) if (b[0].equals(a))
 return Arrays.stream(b[2].split("\\|")).map(Ak::t).collect(java.util.stream.Collectors.toList());
 throw new IllegalArgumentException(a);
}
}
