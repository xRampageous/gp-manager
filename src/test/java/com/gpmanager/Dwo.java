package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.util.*;
import net.runelite.api.MenuAction;
import static java.util.Locale.*;
import static com.gpmanager.Ag.has;
/**
* Menu / chat vocabulary that turns a click into an accounting intent: explicit spend actions
* (eat, drink, bury, offer, light), transform/production options that arm PRODUCTION context,
* gather verbs that only name the activity, and the chat lines that confirm a spend after the
* click. Anything not listed here stays ambiguous and settles through Review, never a guess.
*/
/** Frozen pre-table oracle; production never loads this class. */
class Dwo {
static final Map<String, String> TRANSFORM = Ak.byName("d7");
static final Map<String, String> GATHER = Ak.byName("d10");
/**
* Widget-target menu actions: the player selected a widget (for example a spellbook spell)
* and is now clicking its target. The target click carries no widget of its own, so exact
* identity must come from the still-selected widget.
*/
static boolean yj(MenuAction /* action */a) {
 return a == MenuAction.WIDGET_TARGET || a == MenuAction.WIDGET_TARGET_ON_NPC
 || a == MenuAction.WIDGET_TARGET_ON_PLAYER || a == MenuAction.WIDGET_TARGET_ON_WIDGET
 || a == MenuAction.WIDGET_TARGET_ON_GROUND_ITEM || a == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT;
}

/** Inventory actions that destroy or spend a stack when confirmed by removal. */
static boolean wy(String /* option */a) {
 if (Ag.empty(a)) return false;
 return has(a, "bury", "scatter") || a.startsWith("eat")
 || a.startsWith("drink") || a.equals("empty") || a.equals("break")
 || a.startsWith("release") || a.startsWith("cast") || a.equals("activate") || xi(a);
}

/** Light / Tend-to / Offer / Pray / bury / scatter: the item leaves, nothing comes back. */
static boolean xi(String /* option */a) {
 String /* lower */b = Ag.lower(a);
 return "light".equals(b) || "tend-to".equals(b) || "tend to".equals(b) || "offer".equals(b)
 || "pray".equals(b) || "pray-at".equals(b) || has(b, "bury", "scatter");
}

/** Transform/production options (station or inventory) mapped to the skill whose XP confirms them. */
static String ajx(String /* option */a) {
 return TRANSFORM.getOrDefault(Ag.lower(a), "");
}

/** Use Tinderbox ↔ fuel, or fuel on a fire: never a knife on logs (owner 2026-10-08). */
static boolean ww(String /* option */a, String /* target */b) {
 if (!"use".equals(Ag.lower(a))) return false;
 String t = b == null ? "" : b.toLowerCase(ROOT);
 boolean /* fuel */c = has(t, "logs", "juniper", "redwood", "blisterwood", "pyre", "kindling");
 return c && (has(t, "tinderbox", "fire", "brazier", "funeral pyre"));
}

/** Use bones/ashes ↔ altar (PoH gilded / Wilderness chaos). */
static boolean xt(String /* option */a, String /* target */b) {
 if (!"use".equals(Ag.lower(a))) return false;
 String t = b == null ? "" : b.toLowerCase(ROOT);
 return t.contains("altar") && has(t, "bones", "ashes", "ensouled", "blessed bone");
}

/** Cast Sinister Offering (Arceuus): bones spent after Prayer XP. */
static boolean xp(String /* option */a, String /* target */b) {
 return Ag.lower(a).startsWith("cast")
 && b != null && b.toLowerCase(ROOT).contains("sinister offering");
}

/** Activity hint for object gather verbs; "General" when the verb is not a gather. */
static String ka(String /* option */a) {
 return GATHER.getOrDefault(Ag.axw(a), "General");
}

static boolean xb(String /* option */a) {
 return a != null && !"General".equals(ka(a))
 && !a.equals("rake") && !a.equals("plant");
}

/** Chaos Altar 50% bone-save: the bone may stay, so the chat must not reinforce a spend. */
static boolean vt(String /* message */a) {
 return a != null && a.toLowerCase(ROOT).contains("dark lord spares");
}

/** Game chat that confirms an inventory spend after Eat/Bury/Drink/Scatter/altar offer. */
static boolean vy(String /* message */a) {
 if (Ag.empty(a) || vt(a)) return false;
 String m = a.toLowerCase(ROOT);
 if (m.contains("bury") && (m.contains("dig") || m.startsWith("you bury"))) return true;
 return has(m, msg("ao"), msg("aa"), "you offer ", "you scatter ", "you drink ", "you eat ", "dose of potion left");
}
}
