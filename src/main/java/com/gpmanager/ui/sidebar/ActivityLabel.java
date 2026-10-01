package com.gpmanager;
import static com.gpmanager.GameData.msg;
import java.util.*;
/**
* The one activity label HUD+ and Live both show: the freshest financially meaningful evidence.
* A fresh NPC target outranks the session activity; raids and minigames keep their curated name
* over a target; generic placeholders never surface. Presentation only: it never books or values.
*/
class ActivityLabel {
/** Raids and minigames whose session name outranks a fresh target. */
static final List<String> CURATED = Arrays.asList("theatre of blood", "chambers of xeric",
"tombs of amascut", msg("fe"), "barbarian assault", "tempoross", "wintertodt", "inferno",
"fight caves", "colosseum", "nightmare", "gauntlet");
static final List<String> CURATED_SHORT = Arrays.asList("tob", "cox", "toa", "gotr");
/** The status-line label: fresh target, else the activity, else the named run, else empty. */
static String resolve(String target, String activity, String ownerLabel, boolean hasSession, boolean freePlay) {
 String hint = clean(activity);
 if (curated(hint)) return Fmt.activity(hint);
 String fresh = clean(target);
 if (!fresh.isEmpty()) return Fmt.activity(fresh);
 if (!hint.isEmpty()) return Fmt.activity(hint);
 return hasSession && !freePlay ? Fmt.activity(ownerLabel) : "";
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
