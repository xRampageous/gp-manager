package com.gpmanager;
import java.util.*;
/**
* Combat spells by exact rune cost (spells.tsv). Autocasting never clicks a spell, so a Cast with no
* captured name reads as the one spell its runes pay for (owner 2026-09-28). A staff, tome or
* combination rune may cover elementals; when two spells could fit, it stays a plain Cast.
*/
class Spells {
/** Icons and recipes retain their original joined row shape for existing readers. */
static final Map<String, String> I = Ak.byName("d19");
static final List<String[]> TABLE = Ak.x("d27").stream()
.map(a -> new String[]{a[0], I.get(a[0]), a[1]}).collect(java.util.stream.Collectors.toList());
static final Map<String, String> COMBINATIONS = new HashMap<>();
static final String ELEMENTS = " air water earth fire ";
static {
 for (String[] /* row */b : Ak.rows("d12")) COMBINATIONS.put(b[0], b[1]);
}

/**
* The spell these rune costs pay for (a whole number of casts), or null. When staff-covered
* elementals let several fit, the one paid in full wins; if none or several are, it stays a Cast.
*/


/** The spell's spellbook icon as a negated sprite id (the sidebar's sprite lookup reads it), else -1. */
static int icon(String /* name */a) {
 for (String[] /* spell */b : TABLE) {
  if (b[0].equals(a)) return -Integer.parseInt(b[1]);
 }
 return -1;
}

/** 0 when these costs cannot be this spell, 1 when they fit it, 2 when they pay it in full. */


/** Rune recipes are parsed once; fitting arithmetic and HashMap iteration stay unchanged. */
static final Map<String, Map<String, Long>> R = new LinkedHashMap<>();
static {
 for (String[] a : Ak.x("d27")) {
  var b = new HashMap<String, Long>();
  for (String c : a[1].split(" ")) {
   int d = c.indexOf(':');
   b.put(c.substring(d + 1), Long.parseLong(c.substring(0, d)));
  }
  R.put(a[0], Collections.unmodifiableMap(b));
 }
}
static String a1(List<Ab> /* flows */a) {
 String /* fitting */b = null;
 String /* full */c = null;
 int /* fits */d = 0;
 int /* axi */e = 0;
 for (String[] /* spell */f : TABLE) {
  int /* fit */g = f1(R.get(f[0]), a);
  if (g > 0) {
   d++;
   b = f[0];
  }
  if (g > 1) {
   e++;
   c = f[0];
  }
 }
 return d == 1 ? b : e == 1 ? c : null;
}
static int f1(Map<String, Long> c, List<Ab> b) {
 var /* paid */f = new HashMap<String, Long>();
 for (Ab /* flow */g : b) {
  String /* name */h = g == null ? "" : g.itemName.toLowerCase(Locale.ROOT);
  if (!h.endsWith(" rune") || g.quantityDelta >= 0L) return 0;
  String /* rune */i = h.substring(0, h.length() - 5);
  boolean /* used */j = false;
  for (String /* element */k : COMBINATIONS.getOrDefault(i, i).split(" ")) {
   // A combination rune pays only the elements this spell uses (steam is water for Ice Barrage).
   if (c.containsKey(k) || !COMBINATIONS.containsKey(i)) {
    f.merge(k, -g.quantityDelta, Long::sum);
    j = true;
   }
  }
  if (!j) return 0;
 }
 long /* casts */l = 0L;
 for (Map.Entry<String, Long> /* rune */m : c.entrySet()) {
  if (!ELEMENTS.contains(" " + m.getKey() + " ")) {
   long /* got */n = f.getOrDefault(m.getKey(), 0L);
   if (n == 0L || n % m.getValue() != 0L || l != 0L && n / m.getValue() != l) return 0;
   l = n / m.getValue();
  }
 }
 boolean /* full */o = true;
 for (Map.Entry<String, Long> /* rune */p : c.entrySet()) {
  long /* got */q = f.getOrDefault(p.getKey(), 0L);
  // A rune the spell never uses, or more of an elemental than its casts need, is another spell.
  if (q > l * p.getValue()) return 0;
  o &= q == l * p.getValue();
 }
 return f.keySet().stream().allMatch(c::containsKey) ? o ? 2 : 1 : 0;
}
}
