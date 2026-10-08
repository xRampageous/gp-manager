package com.gpmanager;
import java.util.*;
import net.runelite.api.MenuAction;
/**
* Menu / chat vocabulary that turns a click into an accounting intent: explicit spend actions
* (eat, drink, bury, offer, light), transform/production options that arm PRODUCTION context,
* gather verbs that only name the activity, and the chat lines that confirm a spend after the
* click. Anything not listed here stays ambiguous and settles through Review, never a guess.
*/
class Dw {
static final Map<String, String> TRANSFORM = Ak.byName("d7");
static final Map<String, String> GATHER = Ak.byName("d10");
/** Compiled menu/chat predicates; d7/d10 already contain transform and gather data. */
static final Map<String, Ag.P[][]> R = Ak.r("d24");
/** Apply one compiled predicate group. */
static boolean r(String a, String b) { return Ag.has(b, R.get(a)); }
/** Widget actions eligible for selected spell evidence. */
static boolean a1(MenuAction a) { return r("widget", a == null ? null : a.name()); }
/** Raw consume verbs plus normalized explicit spends. */
static boolean b1(String a) { return r("spend.raw", a) || c1(a); }
/** Normalized light/offer/pray and burial/scatter verbs. */
static boolean c1(String a) { return r("spend.norm", Ag.lower(a)); }
/** Use tinderbox with fuel; target normalization preserves whitespace. */
static boolean d1(String a, String b) {
 return r("use", Ag.lower(a)) && r("fuel", Ag.lower(b, false)) && r("fire", Ag.lower(b, false));
}
/** Use bones/ashes on an altar. */
static boolean e1(String a, String b) {
 return r("use", Ag.lower(a)) && r("altar", Ag.lower(b, false)) && r("bones", Ag.lower(b, false));
}
/** Positive Sinister Offering cast evidence. */
static boolean f1(String a, String b) { return r("cast", Ag.lower(a)) && r("offering", Ag.lower(b, false)); }
/** Chaos Altar saved a bone; this cannot confirm spend. */
static boolean g1(String a) { return r("spared", Ag.lower(a, false)); }
/** Inventory-spend chat, excluding a saved bone. */
static boolean h1(String a) { return !g1(a) && r("spend.chat", Ag.lower(a, false)); }






/** Transform/production options (station or inventory) mapped to the skill whose XP confirms them. */
static String ajx(String /* option */a) {
 return TRANSFORM.getOrDefault(Ag.lower(a), "");
}







/** Activity hint for object gather verbs; "General" when the verb is not a gather. */
static String ka(String /* option */a) {
 return GATHER.getOrDefault(Ag.axw(a), "General");
}







/** Rake and plant still name the activity, but do not arm gather ownership evidence. */
static boolean i1(String a) {
 return a != null && !"General".equals(ka(a)) && !r("gather.exclude", a);
}
}
