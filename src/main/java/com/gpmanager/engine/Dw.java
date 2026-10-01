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
class Dw {
static final Map<String, String> TRANSFORM = Ak.byName("d7");
static final Map<String, String> GATHER = Ak.byName("d10");
/**
* Widget-target menu actions: the player selected a widget (for example a spellbook spell)
* and is now clicking its target. The target click carries no widget of its own, so exact
* identity must come from the still-selected widget.
*/
static boolean yj(MenuAction action) {
 return action == MenuAction.WIDGET_TARGET || action == MenuAction.WIDGET_TARGET_ON_NPC
 || action == MenuAction.WIDGET_TARGET_ON_PLAYER || action == MenuAction.WIDGET_TARGET_ON_WIDGET
 || action == MenuAction.WIDGET_TARGET_ON_GROUND_ITEM || action == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT;
}

/** Inventory actions that destroy or spend a stack when confirmed by removal. */
static boolean wy(String option) {
 if (Ag.empty(option)) return false;
 return has(option, "bury", "scatter") || option.startsWith("eat")
 || option.startsWith("drink") || option.equals("empty") || option.equals("break")
 || option.startsWith("release") || option.startsWith("cast") || xi(option);
}

/** Light / Tend-to / Offer / Pray / bury / scatter: the item leaves, nothing comes back. */
static boolean xi(String option) {
 String lower = option == null ? "" : option.trim().toLowerCase(ROOT);
 return "light".equals(lower) || "tend-to".equals(lower) || "tend to".equals(lower) || "offer".equals(lower)
 || "pray".equals(lower) || "pray-at".equals(lower) || has(lower, "bury", "scatter");
}

/** Transform/production options (station or inventory) mapped to the skill whose XP confirms them. */
static String ajx(String option) {
 return TRANSFORM.getOrDefault(option == null ? "" : option.trim().toLowerCase(ROOT), "");
}

/** Use Tinderbox ↔ fuel: fuel in the target plus a tinderbox or a Use-on arrow. */
static boolean ww(String option, String target) {
 if (option == null || !"use".equals(option.trim().toLowerCase(ROOT))) return false;
 String t = target == null ? "" : target.toLowerCase(ROOT);
 boolean fuel = has(t, "logs", "juniper", "redwood", "blisterwood", "pyre", "kindling");
 return fuel && (has(t, "tinderbox", "->", "→"));
}

/** Use bones/ashes ↔ altar (PoH gilded / Wilderness chaos). */
static boolean xt(String option, String target) {
 if (option == null || !"use".equals(option.trim().toLowerCase(ROOT))) return false;
 String t = target == null ? "" : target.toLowerCase(ROOT);
 return t.contains("altar") && has(t, "bones", "ashes", "ensouled", "blessed bone");
}

/** Cast Sinister Offering (Arceuus): bones spent after Prayer XP. */
static boolean xp(String option, String target) {
 return option != null && option.trim().toLowerCase(ROOT).startsWith("cast")
 && target != null && target.toLowerCase(ROOT).contains("sinister offering");
}

/** Activity hint for object gather verbs; "General" when the verb is not a gather. */
static String ka(String option) {
 return GATHER.getOrDefault(Ag.axw(option), "General");
}

static boolean xb(String option) {
 return option != null && !"General".equals(ka(option))
 && !option.equals("rake") && !option.equals("plant");
}

/** Chaos Altar 50% bone-save: the bone may stay, so the chat must not reinforce a spend. */
static boolean vt(String message) {
 return message != null && message.toLowerCase(ROOT).contains("dark lord spares");
}

/** Game chat that confirms an inventory spend after Eat/Bury/Drink/Scatter/altar offer. */
static boolean vy(String message) {
 if (Ag.empty(message) || vt(message)) return false;
 String m = message.toLowerCase(ROOT);
 if (m.contains("bury") && (m.contains("dig") || m.startsWith("you bury"))) return true;
 return has(m, msg("ao"), msg("aa"), "you offer ", "you scatter ", "you drink ", "you eat ", "dose of potion left");
}
}
