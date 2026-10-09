package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import lombok.*;
import static com.gpmanager.Dv.*;
import static com.gpmanager.Au.*;
import static com.gpmanager.Ai.*;
/**
* Derives the observed {@link Au} for a settled inventory change from positive
* evidence only: a matched menu verb plus the settled flow shape. Never upgrades weak
* evidence into a confident verb — a dose leftover without a matched Drink click is
* {@link Au#SUPPLIES}, not {@link Au#DRINK}.
*
* <p>Presentation evidence only. Accounting type, valuation and counted state are decided
* elsewhere and never read this class.
*/
class Bn {
static final Set<String> HERBLORE_INGREDIENTS = new HashSet<>(Ak.names("d9"));
static final Pattern HERBLORE_INGREDIENT_SUFFIX = Ak.pattern("ayq");
static final Pattern DOSE_SUFFIX = Ak.pattern("ayo");
/** Ammunition names; tips, heads and shafts are fletching supplies, not ammunition. */
static final Pattern AMMUNITION = Ak.pattern("ayn");
static final Pattern FLETCHING_PART = Ak.pattern("ayp");
/** Menu verb prefixes (a leading "~" means "contains") and the action each evidences. */
static final Object[] VERBS = Ak.rows("d25").stream().filter(a -> !a[2].isEmpty())
.flatMap(a -> java.util.stream.Stream.of(a[0], Au.valueOf(a[2]))).toArray();


/**
* Final stamp for a booked change.
*
* @param matchedIntent the menu verb whose intent matched these flows, or null when the
*                      intent was absent, stale or matched nothing
* @param flows         settled flows of the transaction
* @param type          booked accounting type (unchanged by this call)
* @param activityName  booked activity name, used only to recognise Herblore production
*/
static Au resolve(Au /* matchedIntent */b, List<Ab> /* flows */c, Ai /* type */d, String /* activityName */e) {
 if (Ag.empty(c)) return null;
 // A decant shape can also appear in a bank transfer or trade. Only the
 // otherwise-unclassified mixed inventory row is evidence of a player action.
 if (d == UNCERTAIN && isDecant(c)) return DECANT;
 String /* activity */f = Ag.lower(e);
 if (d == PROCESSING && f.equals("herblore")
 && all(c, -1, Bn::f1) && all(c, 1, /* name */g -> ajr(g) > 0)) {
  return MIX;
 }
 if (d == PROCESSING && f.equals("cooking") && wj(c)) {
  // Use the actual outcome: every output burnt → Burnt, otherwise Cooked.
  return all(c, 1, /* name */h -> h.startsWith("burnt")) ? BURN : COOK;
 }
 // Rune/ammo spends are only recognised on cost-shaped rows: a bank deposit or
 // death loss of runes is a transfer/death, not casting.
 boolean /* aqp */i = d == CONSUMPTION || d == PK_SUPPLY_COST;
 boolean /* axs */j = axs(c);
 boolean /* hasGain */k = !gains(c, ANY).isEmpty();
 boolean /* aoz */l = j && !k;
 if (i && l && all(c, 0, Bn::d1)) return Au.CAST;
 if (i && l && all(c, 0, Bn::vm)) return FIRE;
 boolean /* aoa */m = wi(c);
 // Every loss must be one potion's doses (Drink) or one item (Eat/Bury/...): a coalesced
 // shark or bones under the same click is not "Drank", so it falls back to Supplies.
 boolean /* asy */n = acn(c, /* name */o -> ajr(o) > 0 ? qz(o) : null);
 boolean /* asx */p = acn(c, /* name */q -> q.isEmpty() ? null : q)
 && all(c, -1, /* name */r -> ajr(r) <= 0);
 if (b != null && j) {
  switch (b) {
   case DRINK:
   return n ? Au.DRINK : Au.SUPPLIES;
   case EAT:
   case BURY:
   case SCATTER:
   case OFFER:
   return p ? b : Au.SUPPLIES;
   case CAST:
   // A Cast click without a rune-only loss is not evidence runes were used.
   return m ? Au.SUPPLIES : null;
   case SUPPLIES:
   // This explicit intent currently comes from Light/Tend-to and the
   // Tinderbox→fuel pair. Require the matching loss to be actual fuel.
   return l && all(c, 0, /* name */t -> t.matches(msg("s"))) ? Au.SUPPLIES : null;
   default:
   return b;
  }
 }
 // Consumption is known from the leftover shape; the verb is not.
 return d == CONSUMPTION && m ? Au.SUPPLIES : null;
}

/**
* Doses of one potion redistributed with no dose lost: e.g. −(3) −(1) +(4), or
* −(4) +(2) +(2). Requires every flow to be a dosed form of the same base name.
*/
static boolean isDecant(List<Ab> /* flows */b) {
 if (b == null || b.size() < 2) return false;
 String /* base */c = null;
 long /* anx */d = 0L;
 boolean /* arl */e = false;
 boolean /* ark */f = false;
 for (Ab /* flow */g : b) {
  if (g == null || g.quantityDelta == 0L) continue;
  String /* name */h = Ag.lower(g.itemName);
  int /* dose */i = ajr(h);
  // Combining doses frees a vessel; an emptied vial/jug gain is part of the shape.
  if (i <= 0 && g.quantityDelta > 0L && b1(h)) continue;
  String /* ayk */j = qz(h);
  if (i <= 0 || j.isEmpty() || c != null && !c.equals(j)) return false;
  c = j;
  d += (long) i * g.quantityDelta;
  e |= g.quantityDelta < 0L;
  f |= g.quantityDelta > 0L;
 }
 return e && f && d == 0L;
}

/** A dose/food step confirms a consume even when its menu intent has expired. */
static boolean wi(List<Ab> /* flows */a) {
 return !acr(a).isEmpty();
}

/** Exact matched loss/remainder pairs for a recognized dose or partial-food consume. */
static List<Cg> acr(List<Ab> /* flows */a) {
 if (a == null || a.size() < 2) return Collections.emptyList();
 var /* pairs */b = new ArrayList<Cg>();
 for (int /* lossIndex */c = 0; c < a.size(); c++) {
  // A loss pairs only with the one remainder it could have become, and that remainder
  // only with this loss.
  List<Integer> /* gains */d = aiy(a, a.get(c), true);
  if (d.size() == 1 && aiy(a, a.get(d.get(0)), false).size() == 1) {
   b.add(new Cg(c, d.get(0)));
  }
 }
 return Collections.unmodifiableList(b);
}

/** Indexes of the flows a loss could step to ({@code toGains}), or the losses a gain could come from. */
static List<Integer> aiy(List<Ab> /* flows */a, Ab /* flow */b, boolean /* toGains */c) {
 var /* partners */d = new ArrayList<Integer>();
 Ab /* loss */e = c ? b : null;
 Ab /* gain */f = c ? null : b;
 if (b == null || (c ? b.quantityDelta >= 0L || b.quantityDelta == Long.MIN_VALUE
 : b.quantityDelta <= 0L)) {
  return d;
 }
 for (int /* index */h = 0; h < a.size(); h++) {
  Ab /* other */i = a.get(h);
  Ab l = c ? e : i;
  Ab g = c ? i : f;
  if (l != null && g != null && l.quantityDelta < 0L && l.quantityDelta != Long.MIN_VALUE
  && g.quantityDelta > 0L && -l.quantityDelta == g.quantityDelta && c1(l.itemName, g.itemName)) {
   d.add(h);
  }
 }
 return d;
}

@AllArgsConstructor
static class Cg {
 int lossIndex;
 int remainderIndex;
}

static boolean kd(Map<Integer, Long> /* target */a, List<Ab> /* flows */b) {
 boolean /* matched */c = false;
 for (Ab /* loss */d : b) {
  if (!aiy(b, d, true).isEmpty()) {
   a.put(d.itemId, -d.quantityDelta);
   c = true;
  }
 }
 return c;
}





/** Potion base name without its dose suffix, for receipts: "Prayer potion(4)" → "Prayer potion". */
static String qz(String /* itemName */a) {
 return a == null ? "" : DOSE_SUFFIX.matcher(a.trim()).replaceFirst("").trim();
}

/** Dose count parsed from a trailing "(N)" suffix, or −1 when absent. */
static int ajr(String /* itemName */a) {
 Matcher /* matcher */b = DOSE_SUFFIX.matcher(a == null ? "" : a.trim());
 return b.find() ? Integer.parseInt(b.group(1)) : -1;
}



static boolean vm(String /* itemName */a) {
 String /* lower */b = Ag.lower(a);
 return AMMUNITION.matcher(b).matches() && !FLETCHING_PART.matcher(b).matches();
}



/**
* True when every loss has the same non-null family key (a matched click cannot name mixed
* costs); false when there is no loss.
*/
static boolean acn(List<Ab> /* flows */a, Function<String, String> /* family */b) {
 String /* first */c = null;
 for (Ab /* flow */d : a) {
  if (d == null || d.quantityDelta >= 0L) continue;
  String /* key */e = b.apply(Ag.lower(d.itemName));
  if (e == null || c != null && !c.equals(e)) return false;
  c = e;
 }
 return c != null;
}



/** A cooking row must pair raw food inputs with only their cooked/burnt products. */
static boolean wj(List<Ab> /* flows */a) {
 var /* apr */b = new ArrayList<String>();
 for (Ab /* flow */c : a) {
  if (c != null && c.quantityDelta < 0L) {
   String /* name */d = Ag.lower(c.itemName);
   b.add(d.startsWith("raw ") && d.length() > 4 ? d.substring(4).trim()
   : d.startsWith("uncooked ") && d.length() > 9 ? d.substring(9).trim() : null);
  }
 }
 return !b.isEmpty() && !b.contains(null) && all(a, 1, /* output */e -> b.stream()
 .anyMatch(/* raw */f -> e.equals(f) || e.equals("cooked " + f) || e.equals("burnt " + f)));
}

/** True when every gained item is a burnt product ("Burnt shark", "Burnt pie"). */
/**
* True when at least one flow on the chosen side (1 gains, -1 losses, 0 both) exists and every
* such flow's lower-case name satisfies {@code predicate}.
*/
static boolean all(List<Ab> /* flows */a, int /* side */b, Predicate<String> /* predicate */c) {
 boolean /* any */d = false;
 for (Ab /* flow */e : a) {
  long /* delta */f = e == null ? 0L : e.quantityDelta;
  if (f == 0L || b != 0 && Long.signum(f) != b) continue;
  if (!c.test(Ag.lower(e.itemName))) return false;
  d = true;
 }
 return d;
}
/** Ordered intent and item-name rules, compiled beside the old implementation for parity. */
static final Map<String, Ag.P[][]> R = Ak.r("d25");
static boolean r(String a, String b) { return Ag.has(b, R.get(a)); }
/** First matching verb wins, before the explicit fuel fallback. */
static Au a1(String a) {
 String b = Ag.lower(a);
 for (int c = 0; c < VERBS.length; c += 2) {
  if (r((String) VERBS[c], b)) return (Au) VERBS[c + 1];
 }
 return r("supplies", b) ? Au.SUPPLIES : null;
}
/** Empty vessels use the already-normalized name, exactly as before. */
static boolean b1(String a) { return r("vessel", a); }
/** Rune and unopened-claim names use the shared trimmed normalization. */
static boolean d1(String a) { return r("rune", Ag.lower(a)); }
static boolean e1(String a) { return r("claim", Ag.lower(a)); }
/** Ingredient names retain their existing non-null precondition and named ingredient set. */
static boolean f1(String a) {
 return !a.isEmpty() && r("ingredient", a)
 && (HERBLORE_INGREDIENTS.contains(a) || HERBLORE_INGREDIENT_SUFFIX.matcher(a).matches());
}

/** Dose arithmetic is unchanged; only food/vessel vocabulary is table-driven. */
static boolean c1(String /* lostName */a, String /* gainedName */b) {
 String /* lost */c = Ag.lower(a);
 String /* gained */d = Ag.lower(b);
 if (c.isEmpty() || d.isEmpty()) return false;
 if (b1(d)) return true;
 int /* lostDose */e = ajr(c);
 int /* gainedDose */f = ajr(d);
 if (e > 0 && f >= 0 && e == f + 1) {
  String /* apb */g = qz(c);
  return !g.isEmpty() && g.equals(qz(d));
 }
 return r("food", c) && r("partial", d);
}

}
