package com.gpmanager;
import static com.gpmanager.Ak.msg;
import java.util.*;
import java.util.regex.*;
import lombok.*;
import net.runelite.api.gameval.ItemID;
import static java.util.Locale.*;
import static java.util.Collections.*;
/**
* One explicit weapon Check response, expressed as its measured component stock.
* It does not infer depletion from combat events, menu intent, or probabilities.
*/
@AllArgsConstructor
class Ar {
static final int SCALES = ItemID.SNAKEBOSS_SCALE;
static final Pattern BLOWPIPE_CHECK = Ak.pattern("ays");
static final Pattern TYPED_DARTS = Ak.pattern("aza");
static final Pattern EYE_OF_AYAK_CHECK = Ak.pattern("ayc");
static final Pattern EYE_RUNE_CHECK = Ak.pattern("ayd");
static final Map<String, Integer> DARTS = new LinkedHashMap<>();
static {
 for (String[] /* row */b : Ak.rows("d15"))
 DARTS.put(b[0], Integer.parseInt(b[1]));
}

/** Charged-item families; each one's data (names, ids, triggers, recipe, Check shape) is a d20 row. */
@Getter
enum V {
 TRIDENT_SEAS, TRIDENT_SWAMP, TRIDENT_SWAMP_ENHANCED, TRIDENT_SEAS_ENHANCED, V1b, SHADOW, SANGUINESTI, SANGUINESTI_HOLY,
 WARPED, VENATOR, VENATOR_ECHO, EYE_OF_AYAK, SCYTHE, ATES, V3, CRYSTAL_BOW, CRYSTAL_HALBERD, BOWFA, SAELDOR, BLOOD_FURY,
 CRAW, WEBWEAVER, VIGGORA, URSINE, THAMMARON, ACCURSED, MEMOIRS;
 String displayName;
 boolean implemented;
 String family;
 Au action;
 int units;
 /** The Check line's pattern with its count group and its single-charge group (0: none). */
 Pattern check;
 int countGroup, oneGroup;
 int[] graphics, animations, itemIds;
 /** Components one use spends; a Check's count of uses times this is its measured stock. */
 Map<Integer, Long> recipe;
 /** A fractional per-use part, [itemId, numerator, denominator], or null. */
 long[] fraction;
 List<String> aliases;
 static {
  for (String[] r : Ak.rows("d20")) {
   V v = valueOf(r[0]);
   v.displayName = r[1];
   v.implemented = Boolean.parseBoolean(r[2]);
   v.family = r[3];
   v.action = Au.valueOf(r[4]);
   v.units = Integer.parseInt(r[5]);
   if (!"-".equals(r[6])) {
    String[] c = r[6].split(" ");
    v.check = Ak.pattern(c[0]);
    v.countGroup = Integer.parseInt(c[1]);
    v.oneGroup = Integer.parseInt(c[2]);
   }
   v.graphics = ids(r[7]);
   v.animations = ids(r[8]);
   var /* recipe */d = new LinkedHashMap<Integer, Long>();
   for (String /* part */e : parts(r[9], " ")) d.put(id(e), Long.parseLong(e.substring(0, e.indexOf('*'))));
   v.recipe = unmodifiableMap(d);
   if (!"-".equals(r[10])) {
    String[] f = r[10].split("[/*]");
    v.fraction = new long[]{id(r[10]), Long.parseLong(f[0]), Long.parseLong(f[1])};
   }
   v.aliases = parts(r[11], ";");
   v.itemIds = ids(r[12]);
  }
 }
 static List<String> parts(String /* cell */g, String /* separator */h) {
  return "-".equals(g) ? emptyList() : List.of(g.split(h));
 }
 /** The id in "qty*id=NAME" or "id=NAME". */
 static int id(String /* part */g) {
  return Integer.parseInt(g.substring(g.indexOf('*') + 1, g.indexOf('=')));
 }
 static int[] ids(String /* cell */g) {
  return parts(g, ",").stream().mapToInt(V::id).toArray();
 }
 boolean trident() {
  return "TRIDENT".equals(family);
 }
}

V variant;
Map<Integer, Long> componentCounts;
/** Returns -1 when a Check explicitly reported no darts or had an unknown type. */
int dartItemId;
boolean bookable;
/**
* Parse a pinned Check-message shape. Returns {@code null} for unrelated chat.
* Recognized but unsupported/unknown variants return a non-bookable read so a
* tracker can clear an older baseline safely.
*/
static Ar acs(String /* rawMessage */b) {
 String /* message */c = avl(b);
 V /* surface */d = c == null ? null : surface(c.toLowerCase(ROOT));
 if (d == null) return null;
 // A recognizably malformed Check surface resets the caller's old baseline
 // rather than carrying stale evidence forward.
 var /* components */e = new LinkedHashMap<Integer, Long>();
 int /* avr */f = -1;
 try {
  Matcher /* blowpipe */g = BLOWPIPE_CHECK.matcher(c);
  if (g.matches()) {
   e.put(SCALES, acm(g.group(2)));
   String /* anu */h = g.group(1).trim();
   Matcher /* darts */i = TYPED_DARTS.matcher(h);
   if (!"none".equalsIgnoreCase(h)) {
    f = i.matches() ? DARTS.getOrDefault(aau(i.group(1)), -1) : -1;
    if (f < 0) return unsupported(d);
    e.put(f, acm(i.group(2)));
   }
  } else if (d == V.EYE_OF_AYAK) {
   Matcher /* eye */j = EYE_OF_AYAK_CHECK.matcher(c);
   Matcher /* runes */k = EYE_RUNE_CHECK.matcher(c);
   if (j.matches()) {
    e.put(ItemID.DEMON_TEAR, acm(j.group(1)));
   } else if (k.matches()) {
    long /* charges */l = acm(k.group(1));
    e.put(ItemID.DEATHRUNE, Math.multiplyExact(l, 2L));
    e.put(ItemID.CHAOSRUNE, l);
   }
  } else if (d.check != null) {
   // Every other family's Check counts uses, so its stock is that count times one use's recipe.
   Matcher /* read */m = d.check.matcher(c);
   if (m.matches()) {
    String /* counted */n = m.group(d.countGroup);
    long /* count */o = n != null ? acm(n) : d.oneGroup > 0 && m.group(d.oneGroup) != null ? 1L : 0L;
    for (var /* part */p : d.recipe.entrySet()) e.put(p.getKey(), Math.multiplyExact(o, p.getValue()));
   }
  }
  if (!e.isEmpty()) return new Ar(d, unmodifiableMap(e), f, true);
 } catch (NumberFormatException | ArithmeticException /* malformed */q) {
  // The count is syntax-checked; this is a value outside the supported range.
 }
 return unsupported(d);
}

/** The Check surface a lowercase chat line belongs to, whether or not it parses. */
static V surface(String /* lower */a) {
 if (a.startsWith("darts:") && a.contains("scales:")) return V.V1b;
 if (a.startsWith("the pendant has ")) return V.ATES;
 if (a.startsWith("your tome has been charged with ")) return V.V3;
 if (a.startsWith(msg("br"))) return V.BLOOD_FURY;
 if (a.startsWith(msg("bx"))
 || a.startsWith(msg("cj"))) {
  return V.EYE_OF_AYAK;
 }
 for (V /* variant */b : V.values()) {
  if (b == V.V1b) continue;
  String /* name */c = b.displayName.toLowerCase(ROOT);
  if (a.startsWith("your " + c + " has ") || a.startsWith("your " + c + " only has ")
  || a.startsWith(c + " has ") || a.startsWith(c + " only has ")) {
   return b;
  }
 }
 // A Check that names no weapon ("Your bow has…"): the first row sharing that wording.
 for (V /* variant */d : V.values()) {
  if (d.check != null && d.check.matcher(a).matches()) return d;
 }
 return null;
}

/** Resolve only exact supported menu-target names; menu evidence is identity only. */
static V ajb(String /* itemName */a) {
 String /* name */b = aau(a);
 if (b == null) return null;
 for (V /* variant */c : V.values()) {
  if (c.implemented && (c.displayName.toLowerCase(ROOT).equals(b) || c.aliases.contains(b))) {
   return c;
  }
 }
 return null;
}

/** Any catalogue variant by exact display name for presentation; estimate-only variants included. */
static V ajt(String /* itemName */a) {
 String /* name */b = aau(a);
 for (V /* variant */c : V.values()) {
  if (c.displayName.toLowerCase(ROOT).equals(b)) return c;
 }
 return null;
}

/** Resolve charged and uncharged item ids so a Check receipt can be tied to its menu target. */
static V aja(int /* itemId */a) {
 for (V /* variant */b : V.values()) {
  for (int /* id */c : b.itemIds) {
   if (c == a) return b;
  }
 }
 return null;
}

/**
* Validate a candidate item-on-weapon load component. Both the canonical id
* and displayed name must agree, so unknown dart types fail closed.
*/
static boolean xq(V /* variant */a, int /* itemId */b, String /* itemName */c) {
 String /* expectedName */d = a == null || !a.implemented ? null : rz(a, b);
 String /* rt */e = aau(c);
 // Owner 1.2: what an item's own charge is made of is loaded, never spent, for every item.
 if (e != null && a != null && (a.recipe.containsKey(b)
 || a.fraction != null && a.fraction[0] == b)) return true;
 return d != null && e != null
 && (d.equals(e) || e.equals(d + "s") && !d.endsWith("s"));
}

boolean awi() {
 return dartItemId >= 0;
}

/** Component units per priced item: a page feeds 20 charges, a shard 100. */
static int akr(V /* variant */a, int /* componentItemId */b) {
 return a.units;
}

/** Returns only a positive same-variant load increase; decreases are charge spend evidence. */
static Map<Integer, Long> exactLoadQuantities(Ar /* previous */a, Ar /* current */b) {
 if (a == null || b == null || !a.bookable || !b.bookable
 || a.variant != b.variant || b.variant != V.V1b && !b.variant.trident()) {
  return emptyMap();
 }
 var /* result */c = new LinkedHashMap<Integer, Long>();
 for (Map.Entry<Integer, Long> /* entry */d : b.componentCounts.entrySet()) {
  if (b.variant == V.V1b && d.getKey() != SCALES) continue;
  Long /* before */e = a.componentCounts.get(d.getKey());
  if (e == null || d.getValue() == null) return emptyMap();
  if (d.getValue() > e) c.put(d.getKey(), d.getValue() - e);
 }
 return unmodifiableMap(c);
}

static Ar unsupported(V /* variant */a) {
 return new Ar(a, emptyMap(), -1, false);
}

static String rz(V /* variant */b, int /* itemId */c) {
 if (b == V.V1b) {
  for (Map.Entry<String, Integer> /* dart */d : DARTS.entrySet()) {
   if (d.getValue() == c) return d.getKey();
  }
  return c == SCALES ? "zulrah's scale" : null;
 }
 if (b == V.BLOOD_FURY) return c == ItemID.BLOOD_SHARD ? "blood shard" : null;
 if (b == V.EYE_OF_AYAK) {
  // Both charging modes validate, so a recharge books as a neutral transfer (owner 2026-09-30).
  return c == ItemID.DEMON_TEAR ? "demon tear" : c == ItemID.DEATHRUNE ? "death rune"
  : c == ItemID.CHAOSRUNE ? "chaos rune" : null;
 }
 boolean /* seas */e = b == V.TRIDENT_SEAS || b == V.TRIDENT_SEAS_ENHANCED;
 return !b.trident() ? null : c == ItemID.DEATHRUNE ? "death rune" : c == ItemID.CHAOSRUNE ? "chaos rune"
 : c == ItemID.FIRERUNE ? "fire rune" : e && c == ItemID.COINS ? "coin"
 : !e && c == SCALES ? "zulrah's scale" : null;
}

static long acm(String /* raw */a) {
 return Long.parseLong(a.replace(",", ""));
}

static String avl(String /* value */a) {
 return a == null ? null : a.replaceAll("<[^>]*>", "").trim();
}

static String aau(String /* value */a) {
 String /* ars */b = avl(a);
 return Ag.empty(b) ? null : b.toLowerCase(ROOT).replaceAll("\\s+", " ");
}
}
