package com.gpmanager;
import java.util.Map.Entry;
import java.util.*;
import java.util.function.*;
import static com.gpmanager.Ae.nonNeg;
import static com.gpmanager.Ag.*;
import static java.lang.Math.*;
/** Null-tolerant, allocation-light predicates and projections over settled item flows. */
class Dv {
static final IntPredicate ANY = itemId -> true;
/** Positive flows whose item matches. */
static List<Ab> gains(List<Ab> flows, IntPredicate item) {
 var out = new ArrayList<Ab>();
 if (flows != null) {
  for (Ab flow : flows) {
   if (flow != null && flow.quantityDelta > 0L && item.test(flow.itemId)) out.add(flow);
  }
 }
 return out;
}

/** Flows minus those whose item matches (positive-only when {@code gainsOnly}). */
static List<Ab> without(List<Ab> flows, IntPredicate item, boolean gainsOnly) {
 if (flows == null) return Collections.emptyList();
 var out = new ArrayList<Ab>(flows.size());
 for (Ab flow : flows) {
  if (flow == null || !item.test(flow.itemId) || (gainsOnly && flow.quantityDelta <= 0L)) out.add(flow);
 }
 return out;
}

/** Positive entries of {@code quantities} whose item does not match. */
static Map<Integer, Long> withoutItems(Map<Integer, Long> quantities, IntPredicate item) {
 var out = new HashMap<Integer, Long>();
 if (quantities != null) {
  for (Entry<Integer, Long> e : quantities.entrySet()) {
   if (e.getKey() != null && e.getValue() != null && e.getValue() > 0L && !item.test(e.getKey())) {
    out.put(e.getKey(), e.getValue());
   }
  }
 }
 return out.isEmpty() ? Collections.emptyMap() : out;
}

/** Positive entries only (fresh mutable map; merges duplicates). */
static Map<Integer, Long> positiveEntries(Map<Integer, Long> quantities) {
 var out = new HashMap<Integer, Long>();
 if (quantities != null) {
  for (Entry<Integer, Long> e : quantities.entrySet()) {
   if (e.getKey() != null && e.getValue() != null && e.getValue() > 0L) {
    out.merge(e.getKey(), e.getValue(), Ae::safeAdd);
   }
  }
 }
 return out;
}

/**
* The shared settle-later claim: offers each matching flow's magnitude to {@code claimer},
* which returns how much its lifecycle takes, leaves only the unclaimed rest in {@code flows}
* and returns the claimed parts.
*/
static List<Ab> claim(List<Ab> flows, Predicate<Ab> which, ToLongBiFunction<Ab, Long> claimer) {
 var parts = new ArrayList<Ab>();
 for (int index = 0; index < flows.size(); index++) {
  Ab flow = flows.get(index);
  if (flow == null || flow.quantityDelta == Long.MIN_VALUE || !which.test(flow)) continue;
  long taken = claimer.applyAsLong(flow, Ae.abs(flow.quantityDelta));
  if (taken > 0L) parts.add(flow.part(taken));
  Ab rest = flow.rest(taken);
  if (rest == null) flows.remove(index--);
  else flows.set(index, rest);
 }
 return parts;
}

/** Positive remainders of {@code a} minus {@code b}: what left between two observations. */
static Map<Integer, Long> minus(Map<Integer, Long> a, Map<Integer, Long> b) {
 var out = new LinkedHashMap<Integer, Long>();
 if (a != null) {
  for (Entry<Integer, Long> e : a.entrySet()) {
   Long less = b == null ? null : b.get(e.getKey());
   long left = (e.getValue() == null ? 0L : e.getValue()) - (less == null ? 0L : nonNeg(less));
   if (e.getKey() != null && left > 0L) out.put(e.getKey(), left);
  }
 }
 return out;
}

/** True when every wanted quantity fits inside {@code available}. */
static boolean covers(Map<Integer, Long> available, Map<Integer, Long> wanted) {
 if (empty(wanted) || empty(available)) return false;
 for (Entry<Integer, Long> e : wanted.entrySet()) {
  if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0L
  || available.getOrDefault(e.getKey(), 0L) < e.getValue()) {
   return false;
  }
 }
 return true;
}

/** Removes up to {@code max} of one item and returns the quantity taken. */
static long take(Map<Integer, Long> quantities, int itemId, long max) {
 if (quantities == null || max <= 0L) return 0L;
 long held = quantities.getOrDefault(itemId, 0L);
 long taken = min(nonNeg(held), max);
 if (held - taken <= 0L) quantities.remove(itemId);
 else quantities.put(itemId, held - taken);
 return taken;
}

/** Summed positive quantities per matching item. */
static Map<Integer, Long> gainQuantities(List<Ab> flows, IntPredicate item) {
 return quantities(flows, item, true);
}

/** Summed magnitudes of negative deltas per matching item. */
static Map<Integer, Long> lossQuantities(List<Ab> flows, IntPredicate item) {
 return quantities(flows, item, false);
}

static Map<Integer, Long> quantities(List<Ab> flows, IntPredicate item, boolean gains) {
 var out = new HashMap<Integer, Long>();
 if (flows != null) {
  for (Ab flow : flows) {
   if (flow == null || !item.test(flow.itemId)) continue;
   long delta = flow.quantityDelta;
   if (gains ? delta > 0L : delta < 0L) {
    long magnitude = Ae.abs(delta);
    out.merge(flow.itemId, magnitude, Ae::safeAdd);
   }
  }
 }
 return out;
}

/**
* Removes same-variant charge-state swaps (full -> partial -> uncharged): one physical weapon
* changing item id is neither a gain nor a loss. Leftover flows keep their own rows.
*/
static List<Ab> akq(List<Ab> flows) {
 boolean[] dropped = new boolean[flows.size()];
 for (int i = 0; i < flows.size(); i++) {
  Ab cost = flows.get(i);
  Ar.V variant = cost == null || cost.quantityDelta >= 0L
  ? null : Ar.aja(cost.itemId);
  if (variant == null) continue;
  for (int j = 0; j < flows.size(); j++) {
   Ab gain = flows.get(j);
   if (dropped[j] || gain == null || gain.quantityDelta <= 0L || gain.itemId == cost.itemId
   || abs(gain.quantityDelta) != abs(cost.quantityDelta) || Ar.aja(gain.itemId) != variant) {
    continue;
   }
   dropped[i] = true;
   dropped[j] = true;
   break;
  }
 }
 var result = new ArrayList<Ab>();
 for (int i = 0; i < flows.size(); i++) {
  if (!dropped[i]) result.add(flows.get(i));
 }
 return result;
}

static boolean axs(List<Ab> flows) {
 return axs(flows, ANY);
}

static boolean axs(List<Ab> flows, IntPredicate item) {
 if (flows != null) {
  for (Ab flow : flows) {
   if (flow != null && flow.quantityDelta < 0L && item.test(flow.itemId)) return true;
  }
 }
 return false;
}

/** True when every non-null flow is on one side and at least one is non-zero. */
static boolean aux(List<Ab> flows) {
 return aws(flows, true);
}

static boolean auw(List<Ab> flows) {
 return aws(flows, false);
}

static boolean aws(List<Ab> flows, boolean gains) {
 if (empty(flows)) return false;
 boolean saw = false;
 for (Ab flow : flows) {
  long delta = flow == null ? (gains ? -1L : 1L) : flow.quantityDelta;
  if (gains ? delta < 0L : delta > 0L) return false;
  saw |= delta != 0L;
 }
 return saw;
}
}
