package com.gpmanager;
import java.util.Map.Entry;
import java.util.*;
import java.util.function.*;
import static com.gpmanager.Ae.nonNeg;
import static com.gpmanager.Ag.*;
import static java.lang.Math.*;
/** Null-tolerant, allocation-light predicates and projections over settled item flows. */
class Dv {
static final IntPredicate ANY = /* itemId */c -> true;
/** Positive flows whose item matches. */
static List<Ab> gains(List<Ab> /* flows */a, IntPredicate /* item */b) {
 var /* out */c = new ArrayList<Ab>();
 if (a != null) {
  for (Ab /* flow */d : a) {
   if (d != null && d.quantityDelta > 0L && b.test(d.itemId)) c.add(d);
  }
 }
 return c;
}

/** Flows minus those whose item matches (positive-only when {@code gainsOnly}). */
static List<Ab> without(List<Ab> /* flows */a, IntPredicate /* item */b, boolean /* gainsOnly */c) {
 if (a == null) return Collections.emptyList();
 var /* out */d = new ArrayList<Ab>(a.size());
 for (Ab /* flow */e : a) {
  if (e == null || !b.test(e.itemId) || (c && e.quantityDelta <= 0L)) d.add(e);
 }
 return d;
}

/** Positive entries of {@code quantities} whose item does not match. */
static Map<Integer, Long> withoutItems(Map<Integer, Long> /* quantities */a, IntPredicate /* item */b) {
 var /* out */c = new HashMap<Integer, Long>();
 if (a != null) {
  for (Entry<Integer, Long> e : a.entrySet()) {
   if (e.getKey() != null && e.getValue() != null && e.getValue() > 0L && !b.test(e.getKey())) {
    c.put(e.getKey(), e.getValue());
   }
  }
 }
 return c.isEmpty() ? Collections.emptyMap() : c;
}

/** Positive entries only (fresh mutable map; merges duplicates). */
static Map<Integer, Long> positiveEntries(Map<Integer, Long> /* quantities */a) {
 var /* out */b = new HashMap<Integer, Long>();
 if (a != null) {
  for (Entry<Integer, Long> e : a.entrySet()) {
   if (e.getKey() != null && e.getValue() != null && e.getValue() > 0L) {
    b.merge(e.getKey(), e.getValue(), Ae::safeAdd);
   }
  }
 }
 return b;
}

/**
* The shared settle-later claim: offers each matching flow's magnitude to {@code claimer},
* which returns how much its lifecycle takes, leaves only the unclaimed rest in {@code flows}
* and returns the claimed parts.
*/
static List<Ab> claim(List<Ab> /* flows */a, Predicate<Ab> /* which */b, ToLongBiFunction<Ab, Long> /* claimer */c) {
 var /* parts */d = new ArrayList<Ab>();
 for (int /* index */e = 0; e < a.size(); e++) {
  Ab /* flow */f = a.get(e);
  if (f == null || f.quantityDelta == Long.MIN_VALUE || !b.test(f)) continue;
  long /* taken */g = c.applyAsLong(f, Ae.abs(f.quantityDelta));
  if (g > 0L) d.add(f.part(g));
  Ab /* rest */h = f.rest(g);
  if (h == null) a.remove(e--);
  else a.set(e, h);
 }
 return d;
}

/** Positive remainders of {@code a} minus {@code b}: what left between two observations. */
static Map<Integer, Long> minus(Map<Integer, Long> a, Map<Integer, Long> b) {
 var /* out */c = new LinkedHashMap<Integer, Long>();
 if (a != null) {
  for (Entry<Integer, Long> e : a.entrySet()) {
   Long /* less */d = b == null ? null : b.get(e.getKey());
   long /* left */f = (e.getValue() == null ? 0L : e.getValue()) - (d == null ? 0L : nonNeg(d));
   if (e.getKey() != null && f > 0L) c.put(e.getKey(), f);
  }
 }
 return c;
}

/** True when every wanted quantity fits inside {@code available}. */
static boolean covers(Map<Integer, Long> /* available */a, Map<Integer, Long> /* wanted */b) {
 if (empty(b) || empty(a)) return false;
 for (Entry<Integer, Long> e : b.entrySet()) {
  if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0L
  || a.getOrDefault(e.getKey(), 0L) < e.getValue()) {
   return false;
  }
 }
 return true;
}

/** Removes up to {@code max} of one item and returns the quantity taken. */
static long take(Map<Integer, Long> /* quantities */a, int /* itemId */b, long /* max */c) {
 if (a == null || c <= 0L) return 0L;
 long /* held */d = a.getOrDefault(b, 0L);
 long /* taken */e = min(nonNeg(d), c);
 if (d - e <= 0L) a.remove(b);
 else a.put(b, d - e);
 return e;
}

/** Summed positive quantities per matching item. */
static Map<Integer, Long> gainQuantities(List<Ab> /* flows */a, IntPredicate /* item */b) {
 return quantities(a, b, true);
}

/** Summed magnitudes of negative deltas per matching item. */
static Map<Integer, Long> lossQuantities(List<Ab> /* flows */a, IntPredicate /* item */b) {
 return quantities(a, b, false);
}

static Map<Integer, Long> quantities(List<Ab> /* flows */a, IntPredicate /* item */b, boolean /* gains */c) {
 var /* out */d = new HashMap<Integer, Long>();
 if (a != null) {
  for (Ab /* flow */e : a) {
   if (e == null || !b.test(e.itemId)) continue;
   long /* delta */f = e.quantityDelta;
   if (c ? f > 0L : f < 0L) {
    long /* magnitude */g = Ae.abs(f);
    d.merge(e.itemId, g, Ae::safeAdd);
   }
  }
 }
 return d;
}

/**
* Removes same-variant charge-state swaps (full -> partial -> uncharged): one physical weapon
* changing item id is neither a gain nor a loss. Leftover flows keep their own rows.
*/
static List<Ab> akq(List<Ab> /* flows */a) {
 boolean[] /* dropped */b = new boolean[a.size()];
 for (int i = 0; i < a.size(); i++) {
  Ab /* cost */c = a.get(i);
  Ar.V /* variant */d = c == null || c.quantityDelta >= 0L
  ? null : Ar.aja(c.itemId);
  if (d == null) continue;
  for (int j = 0; j < a.size(); j++) {
   Ab /* gain */e = a.get(j);
   if (b[j] || e == null || e.quantityDelta <= 0L || e.itemId == c.itemId
   || abs(e.quantityDelta) != abs(c.quantityDelta) || Ar.aja(e.itemId) != d) {
    continue;
   }
   b[i] = true;
   b[j] = true;
   break;
  }
 }
 var /* result */f = new ArrayList<Ab>();
 for (int i = 0; i < a.size(); i++) {
  if (!b[i]) f.add(a.get(i));
 }
 return f;
}

static boolean axs(List<Ab> /* flows */a) {
 return axs(a, ANY);
}

static boolean axs(List<Ab> /* flows */a, IntPredicate /* item */b) {
 if (a != null) {
  for (Ab /* flow */c : a) {
   if (c != null && c.quantityDelta < 0L && b.test(c.itemId)) return true;
  }
 }
 return false;
}

/** True when every non-null flow is on one side and at least one is non-zero. */
static boolean aux(List<Ab> /* flows */a) {
 return aws(a, true);
}

static boolean auw(List<Ab> /* flows */a) {
 return aws(a, false);
}

static boolean aws(List<Ab> /* flows */a, boolean /* gains */b) {
 if (empty(a)) return false;
 boolean /* saw */c = false;
 for (Ab /* flow */d : a) {
  long /* delta */e = d == null ? (b ? -1L : 1L) : d.quantityDelta;
  if (b ? e < 0L : e > 0L) return false;
  c |= e != 0L;
 }
 return c;
}
}
