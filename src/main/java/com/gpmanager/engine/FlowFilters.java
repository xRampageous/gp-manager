package com.gpmanager;
import java.util.Map.Entry;
import java.util.*;
import java.util.function.*;
import static com.gpmanager.SafeMath.nonNeg;
import static com.gpmanager.ModelText.*;
import static java.lang.Math.*;
/** Null-tolerant, allocation-light predicates and projections over settled item flows. */
class FlowFilters {
static final IntPredicate ANY = itemId -> true;
/** Positive flows whose item matches. */
static List<Flow> gains(List<Flow> flows, IntPredicate item) {
var out = new ArrayList<Flow>();
if (flows != null) {
for (Flow flow : flows) {
if (flow != null && flow.quantityDelta > 0L && item.test(flow.itemId)) out.add(flow);
}
}
return out;
}

/** Flows minus those whose item matches (positive-only when {@code gainsOnly}). */
static List<Flow> without(List<Flow> flows, IntPredicate item, boolean gainsOnly) {
if (flows == null) return Collections.emptyList();
var out = new ArrayList<Flow>(flows.size());
for (Flow flow : flows) {
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
out.merge(e.getKey(), e.getValue(), SafeMath::safeAdd);
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
static List<Flow> claim(List<Flow> flows, Predicate<Flow> which, ToLongBiFunction<Flow, Long> claimer) {
var parts = new ArrayList<Flow>();
for (int index = 0; index < flows.size(); index++) {
Flow flow = flows.get(index);
if (flow == null || flow.quantityDelta == Long.MIN_VALUE || !which.test(flow)) continue;
long taken = claimer.applyAsLong(flow, SafeMath.abs(flow.quantityDelta));
if (taken > 0L) parts.add(flow.part(taken));
Flow rest = flow.rest(taken);
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
static Map<Integer, Long> gainQuantities(List<Flow> flows, IntPredicate item) {
return quantities(flows, item, true);
}

/** Summed magnitudes of negative deltas per matching item. */
static Map<Integer, Long> lossQuantities(List<Flow> flows, IntPredicate item) {
return quantities(flows, item, false);
}

static Map<Integer, Long> quantities(List<Flow> flows, IntPredicate item, boolean gains) {
var out = new HashMap<Integer, Long>();
if (flows != null) {
for (Flow flow : flows) {
if (flow == null || !item.test(flow.itemId)) continue;
long delta = flow.quantityDelta;
if (gains ? delta > 0L : delta < 0L) {
long magnitude = SafeMath.abs(delta);
out.merge(flow.itemId, magnitude, SafeMath::safeAdd);
}
}
}
return out;
}

/**
* Removes same-variant charge-state swaps (full -> partial -> uncharged): one physical weapon
* changing item id is neither a gain nor a loss. Leftover flows keep their own rows.
*/
static List<Flow> withoutChargeStateSwaps(List<Flow> flows) {
boolean[] dropped = new boolean[flows.size()];
for (int i = 0; i < flows.size(); i++) {
Flow cost = flows.get(i);
ChargeRead.Variant variant = cost == null || cost.quantityDelta >= 0L
? null : ChargeRead.supportedVariantForItemId(cost.itemId);
if (variant == null) continue;
for (int j = 0; j < flows.size(); j++) {
Flow gain = flows.get(j);
if (dropped[j] || gain == null || gain.quantityDelta <= 0L || gain.itemId == cost.itemId
|| abs(gain.quantityDelta) != abs(cost.quantityDelta) || ChargeRead.supportedVariantForItemId(gain.itemId) != variant) {
continue;
}
dropped[i] = true;
dropped[j] = true;
break;
}
}
var result = new ArrayList<Flow>();
for (int i = 0; i < flows.size(); i++) {
if (!dropped[i]) result.add(flows.get(i));
}
return result;
}

static boolean hasLoss(List<Flow> flows) {
return hasLoss(flows, ANY);
}

static boolean hasLoss(List<Flow> flows, IntPredicate item) {
if (flows != null) {
for (Flow flow : flows) {
if (flow != null && flow.quantityDelta < 0L && item.test(flow.itemId)) return true;
}
}
return false;
}

/** True when every non-null flow is on one side and at least one is non-zero. */
static boolean onlyGains(List<Flow> flows) {
return oneSided(flows, true);
}

static boolean onlyCosts(List<Flow> flows) {
return oneSided(flows, false);
}

static boolean oneSided(List<Flow> flows, boolean gains) {
if (empty(flows)) return false;
boolean saw = false;
for (Flow flow : flows) {
long delta = flow == null ? (gains ? -1L : 1L) : flow.quantityDelta;
if (gains ? delta < 0L : delta > 0L) return false;
saw |= delta != 0L;
}
return saw;
}
}
