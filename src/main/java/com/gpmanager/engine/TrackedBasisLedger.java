package com.gpmanager;
import java.util.Map.Entry;
import java.util.*;
import net.runelite.api.gameval.ItemID;
import lombok.*;
import static java.lang.Math.*;
import static com.gpmanager.Ae.*;
import static com.gpmanager.Ah.*;
import static com.gpmanager.Av.*;
/**
* Schema-106 pooled known-basis coverage memory.
*
* <p>Tracked basis is a double-count-prevention memory, never a second financial ledger: canonical
* counted transactions stay the only financial authority and this ledger never books, reprices or
* exports GP. It records only <em>known coverage</em>: quantity whose prior GP Manager accounting
* is proven, with the exact GP value GP Manager counted (which may legitimately be zero). Absence
* of coverage means UNKNOWN basis, never zero basis; unknown quantity is derived per realization
* and never persisted as a global unknown-owned ledger.</p>
*
* <p>Allocation is tracked-first pooled weighted average (never FIFO/LIFO). Acquisitions add known
* coverage. Proven SELL placements reserve their share; cancellations release the unused
* reservation exactly; settlements consume the realized share; permanent ownership sinks deplete
* with the same rule. A per-item realization fence blocks retroactive acquisition corrections once
* known coverage has been realized or depleted, so canonical Net and tracked basis can never
* disagree about what was previously counted.</p>
*/
class TrackedBasisLedger implements Ad.Dj {
/** Exact known-coverage share a proven SELL placement captured from the available pool. */
@AllArgsConstructor
static class Reservation {
 final long trackedQty;
 final long basisGp;
 boolean isEmpty() {
  return trackedQty <= 0L && basisGp <= 0L;
 }
}

final Map<Integer, Az> pools = new LinkedHashMap<>();
long basisEpochMillis;
java.util.function.IntFunction<Long> openReservationLookup = id -> 0L;
// ---------------------------------------------------------------------------------------
// Acquisition / removal / change
// ---------------------------------------------------------------------------------------
/**
* Book a newly added canonical transaction: add its exact known coverage, and deplete known
* coverage for proven permanent non-custody ownership sinks. Custody-owned settlements already
* consumed their reservation, so their item legs never deplete again.
*/
public synchronized void onAdded(Ac transaction, boolean custodyOwned) {
 if (transaction == null || transaction.timestampEpochMillis < basisEpochMillis) {
  // Pre-epoch stock is UNKNOWN coverage by migration law; nothing is inferred here.
  return;
 }
 if (basisEpochMillis <= 0L && transaction.timestampEpochMillis > 0L) basisEpochMillis = transaction.timestampEpochMillis;
 kc(contribution(transaction), transaction.timestampEpochMillis);
 if (!custodyOwned) qx(transaction);
}

/** Validate restoring a previously removed transaction; false blocks the restore. */
public synchronized boolean canAdd(Ac transaction) {
 if (transaction == null) return false;
 if (transaction.timestampEpochMillis >= basisEpochMillis
 && !sinkQuantities(transaction.tm(), transaction.isCounted(), transaction.getCorrection(),
 transaction.getFlows()).isEmpty()) {
  return false;
 }
 return nj(contribution(transaction), 1L, transaction.timestampEpochMillis);
}

/** Validate removing a retained transaction (undo); false blocks the canonical removal. */
public synchronized boolean canRemove(Ac transaction) {
 if (transaction == null || transaction.timestampEpochMillis < basisEpochMillis) return true;
 if (!sinkQuantities(transaction.tm(), transaction.isCounted(),
 transaction.getCorrection(), transaction.getFlows()).isEmpty()) {
  return false;
 }
 return nj(contribution(transaction), -1L, transaction.timestampEpochMillis);
}

boolean nj(Map<Integer, long[]> deltas, long sign, long timestamp) {
 for (Entry<Integer, long[]> entry : deltas.entrySet()) {
  if (!awa(entry.getKey(), sign * entry.getValue()[0], sign * entry.getValue()[1], timestamp)) {
   return false;
  }
 }
 return true;
}

/** Reverse a previously removed transaction's exact known coverage. */
public synchronized void onRemoved(Ac transaction) {
 if (transaction == null || transaction.timestampEpochMillis < basisEpochMillis) return;
 contribution(transaction).forEach((itemId, delta) -> kq(itemId, -delta[0], -delta[1]));
}

/**
* Validate a pending correction/split/undo before any canonical mutation. False means the
* mutation must be rejected as-is: the item's known coverage is reserved or already realized.
*/
public synchronized boolean canChange(Ac transaction, Ah previousCorrection, List<Ab> previousFlows,
Ah nextCorrection, List<Ab> nextFlows) {
 if (transaction == null) return false;
 Map<Integer, long[]> before = contribution(transaction, previousCorrection, previousFlows);
 // A null next-flow set means the caller can only promise the positive contribution
 // shrinks; validating against the empty worst case is the strictest safe check.
 Map<Integer, long[]> after = contribution(transaction, nextCorrection, nextFlows);
 Map<Integer, Long> beforeSinks = sinkQuantities(transaction, previousCorrection, previousFlows);
 Map<Integer, Long> afterSinks = sinkQuantities(transaction, nextCorrection, nextFlows);
 if (transaction.timestampEpochMillis >= basisEpochMillis && !beforeSinks.equals(afterSinks)
 && !np(transaction, beforeSinks, afterSinks) && !nm(beforeSinks, afterSinks,
 transaction.timestampEpochMillis)) {
  return false;
 }
 return nj(deltas(before, after), 1L, transaction.timestampEpochMillis);
}

/** Apply the exact effective delta after a permitted correction/split/undo. */
public synchronized void onChanged(Ac transaction, Ah previousCorrection, List<Ab> previousFlows) {
 if (transaction == null) return;
 Map<Integer, Long> beforeSinks = sinkQuantities(transaction, previousCorrection, previousFlows);
 Map<Integer, Long> afterSinks = sinkQuantities(transaction.tm(),
 transaction.isCounted(), transaction.getCorrection(), transaction.getFlows());
 kc(deltas(contribution(transaction, previousCorrection, previousFlows),
 contribution(transaction)), transaction.timestampEpochMillis);
 if (!beforeSinks.equals(afterSinks)) {
  if (beforeSinks.isEmpty()) qx(transaction);
  else ahc(transaction, beforeSinks, afterSinks);
 }
}

// ---------------------------------------------------------------------------------------
// Reservation / realization / depletion
// ---------------------------------------------------------------------------------------
/** Reserve the tracked-first known-coverage share for a quantity entering proven SELL custody. */
synchronized Reservation reserve(int itemId, long quantity, long now) {
 int id = itemId;
 long requested = nonNeg(quantity);
 Az pool = pools.get(id);
 long availableQty = pool == null ? 0L : pool.getAvailableQty();
 long availableBasis = pool == null ? 0L : pool.getAvailableBasisGp();
 long consumeQty = min(requested, availableQty);
 long basis = Df.ayb(availableQty, availableBasis, consumeQty);
 if (consumeQty <= 0L && basis <= 0L) return new Reservation(0L, 0L);
 pool = pool(id, true);
 pool.setAvailableQty(availableQty - consumeQty);
 pool.setAvailableBasisGp(availableBasis - basis);
 pool.setReservedQty(pool.getReservedQty() + consumeQty);
 pool.setReservedBasisGp(pool.getReservedBasisGp() + basis);
 return new Reservation(consumeQty, basis);
}

/** Move an exactly realized reservation share out of the reserved totals and fence the item. */
synchronized void qd(int itemId, long quantity, long basisGp, long now) {
 int id = itemId;
 Az pool = pool(id, true);
 pool.setReservedQty(pool.getReservedQty() - nonNeg(quantity));
 pool.setReservedBasisGp(pool.getReservedBasisGp() - nonNeg(basisGp));
 pool.setRealizationFenceEpochMillis(max(pool.getRealizationFenceEpochMillis(), now));
}

/** Return an unconsumed reservation share to the available pool (cancel/return). */
synchronized void release(int itemId, long quantity, long basisGp) {
 int id = itemId;
 long releaseQty = nonNeg(quantity);
 long releaseBasis = nonNeg(basisGp);
 if (releaseQty <= 0L && releaseBasis <= 0L) return;
 Az pool = pool(id, true);
 pool.setReservedQty(pool.getReservedQty() - releaseQty);
 pool.setReservedBasisGp(pool.getReservedBasisGp() - releaseBasis);
 pool.setAvailableQty(pool.getAvailableQty() + releaseQty);
 pool.setAvailableBasisGp(pool.getAvailableBasisGp() + releaseBasis);
}

/** Deplete known coverage for a proven permanent ownership sink; returns basis consumed. */
synchronized long deplete(int itemId, long quantity, long now) {
 int id = itemId;
 long requested = nonNeg(quantity);
 Az pool = pools.get(id);
 if (requested <= 0L) return 0L;
 if (pool == null) {
  // Even a sink with no known coverage leaves a realization fence: units left ownership,
  // so later retroactive coverage for them can never be re-added.
  pool(id, true).setRealizationFenceEpochMillis(now);
  return 0L;
 }
 long consumeQty = min(requested, pool.getAvailableQty());
 long basis = Df.ayb(pool.getAvailableQty(), pool.getAvailableBasisGp(), consumeQty);
 pool.setAvailableQty(pool.getAvailableQty() - consumeQty);
 pool.setAvailableBasisGp(pool.getAvailableBasisGp() - basis);
 pool.setRealizationFenceEpochMillis(max(pool.getRealizationFenceEpochMillis(), now));
 return basis;
}

/** Currency is money, never tracked owned-item value. */
static boolean wk(int itemId) {
 return itemId == ItemID.COINS || itemId == ItemID.PLATINUM;
}

// ---------------------------------------------------------------------------------------
// Persistence / lifecycle
// ---------------------------------------------------------------------------------------
synchronized void ayc(SavedState state) {
 if (state == null) return;
 var out = new TrackedBasisState();
 // Epoch 0 only happens for an engine that never restored or tracked anything; a positive
 // sentinel keeps a saved pool loadable as "tracked since the beginning".
 out.basisEpochMillis = nonNeg(basisEpochMillis > 0L ? basisEpochMillis : 1L);
 var list = new ArrayList<Az>();
 for (Az pool : pools.values()) {
  if (pool == null) continue;
  if (pool.getAvailableQty() <= 0L && pool.getAvailableBasisGp() <= 0L
  && pool.getReservedQty() <= 0L && pool.getReservedBasisGp() <= 0L && pool.getRealizationFenceEpochMillis() <= 0L) {
   continue;
  }
  var copy = new Az(pool.itemId);
  copy.setAvailableQty(pool.getAvailableQty());
  copy.setAvailableBasisGp(pool.getAvailableBasisGp());
  copy.setReservedQty(pool.getReservedQty());
  copy.setReservedBasisGp(pool.getReservedBasisGp());
  copy.setRealizationFenceEpochMillis(pool.getRealizationFenceEpochMillis());
  copy.setLatestAcquisitionEpochMillis(pool.getLatestAcquisitionEpochMillis());
  list.add(copy);
 }
 list.removeIf(value -> value == null || value.itemId <= 0);
 out.pools = new ArrayList<>(list);
 state.setTrackedBasis(out);
}

/**
* Restore persisted continuity. A pre-106 (or absent) state starts with no known coverage at
* the migration boundary: existing holdings are UNKNOWN coverage, no bank value seeds basis and
* no history is reconstructed. Reserved totals are rebound from the durable custody records.
*/
synchronized void restore(SavedState state, List<Aa> custodyRecords, long now) {
 pools.clear();
 TrackedBasisState saved = state == null ? null : state.getTrackedBasis();
 if (saved != null && saved.getBasisEpochMillis() > 0L) {
  basisEpochMillis = saved.getBasisEpochMillis();
  for (Az pool : saved.awg()) {
   if (pool == null || pool.itemId <= 0) continue;
   pools.put(pool.itemId, pool);
  }
 } else {
  basisEpochMillis = now;
 }
 aek(custodyRecords);
}

/** Recompute reserved totals from the durable custody records (they are the authority). */
synchronized void aek(List<Aa> custodyRecords) {
 for (Az pool : pools.values()) {
  pool.setReservedQty(0L);
  pool.setReservedBasisGp(0L);
 }
 if (custodyRecords == null) return;
 for (Aa record : custodyRecords) {
  if (record == null || record.getSide() != Aa.Side.SELL) continue;
  long remainingQty = record.afs();
  long remainingBasis = record.afr();
  if (remainingQty <= 0L && remainingBasis <= 0L) continue;
  Az pool = pool(record.itemId, true);
  pool.setReservedQty(pool.getReservedQty() + remainingQty);
  pool.setReservedBasisGp(pool.getReservedBasisGp() + remainingBasis);
 }
}

/** Destructive reset: tracked basis is continuity state and clears with the rest. */
synchronized void reset() {
 pools.clear();
 basisEpochMillis = 0L;
}

/** Adopt another ledger's durable state during a staged profile swap. */
synchronized void auc(TrackedBasisLedger other) {
 reset();
 if (other != null) {
  basisEpochMillis = other.basisEpochMillis;
  other.pools.forEach((itemId, pool) -> {
   if (pool != null) pools.put(itemId, pool);
  });
 }
}

// ---------------------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------------------
Az pool(int itemId, boolean create) {
 Az pool = pools.get(itemId);
 if (pool == null && create) {
  pool = new Az(itemId);
  pools.put(itemId, pool);
 }
 return pool;
}

void kc(Map<Integer, long[]> contribution, long timestamp) {
 for (Entry<Integer, long[]> entry : contribution.entrySet()) {
  kq(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
  if (entry.getValue()[0] > 0L) {
   Az pool = pool(entry.getKey(), true);
   pool.setLatestAcquisitionEpochMillis(max(pool.getLatestAcquisitionEpochMillis(), timestamp));
  }
 }
}

/** Deplete known coverage for each proven permanent non-custody ownership sink in the row. */
void qx(Ac transaction) {
 Map<Integer, Long> quantities = sinkQuantities(transaction.tm(),
 transaction.isCounted(), transaction.getCorrection(), transaction.getFlows());
 if (quantities.isEmpty()) return;
 List<Cd> shares = transaction.xe() ? new ArrayList<>() : null;
 for (Entry<Integer, Long> entry : quantities.entrySet()) {
  Az pool = pools.get(entry.getKey());
  long knownQty = min(entry.getValue(), pool == null ? 0L : pool.getAvailableQty());
  long basis = deplete(entry.getKey(), entry.getValue(), transaction.timestampEpochMillis);
  if (shares != null) shares.add(new Cd(entry.getKey(), entry.getValue(), knownQty, basis));
 }
 if (shares != null) transaction.aie(shares);
}

/** A confirmed own ground-drop pickup may undo its recorded sink share before later use. */
boolean np(Ac transaction, Map<Integer, Long> before, Map<Integer, Long> after) {
 if (!transaction.xe() || !transaction.yi() || before.isEmpty()) return false;
 if (!after.equals(sinkQuantities(transaction.tm(), true, AUTO, transaction.getFlows()))) {
  // A manual Ignore/Transfer keeps the physical loss intact. Only a proven pickup
  // shrinks the booked negative flows before this observer runs.
  return false;
 }
 for (Entry<Integer, Long> entry : before.entrySet()) {
  int itemId = entry.getKey();
  long remaining = after.getOrDefault(itemId, 0L);
  if (remaining > entry.getValue()) return false;
  Cd share = avi(transaction, itemId);
  if (share == null || share.uh() != entry.getValue()) return false;
 }
 for (Integer itemId : after.keySet()) if (!before.containsKey(itemId)) return false;
 return true;
}

Cd avi(Ac transaction, int itemId) {
 for (Cd share : transaction.va()) {
  if (share != null && share.itemId == itemId) return share;
 }
 return null;
}

/** An owner may first count a Review removal while no later use has frozen its pool. */
boolean nm(Map<Integer, Long> before, Map<Integer, Long> after, long transactionTimestamp) {
 if (!before.isEmpty() || after.isEmpty()) return false;
 for (Integer itemId : after.keySet()) {
  if (!awa(itemId, 0L, 0L, transactionTimestamp)) return false;
  Az pool = pools.get(itemId);
  if (pool != null && pool.getAvailableQty() > 0L && (pool.getLatestAcquisitionEpochMillis() <= 0L
  || pool.getLatestAcquisitionEpochMillis() > transactionTimestamp)) {
   return false;
  }
 }
 return true;
}

void ahc(Ac transaction, Map<Integer, Long> before, Map<Integer, Long> after) {
 for (Entry<Integer, Long> entry : before.entrySet()) {
  long amu = entry.getValue() - after.getOrDefault(entry.getKey(), 0L);
  if (amu <= 0L) continue;
  Cd share = avi(transaction, entry.getKey());
  if (share == null) continue;
  long knownQty = Df.ayb(share.uh(), share.ug(), amu);
  long basis = Df.ayb(share.ug(), nonNeg(share.remainingBasisGp), knownQty);
  kq(entry.getKey(), knownQty, basis);
  share.restore(amu, knownQty, basis);
 }
}

/** Physical counted removals that would require exact reversal of pooled sink depletion. */
Map<Integer, Long> sinkQuantities(Ai type, boolean counted, Ah correction, List<Ab> flows) {
 var quantities = new LinkedHashMap<Integer, Long>();
 if (!counted || type == Ai.TRANSFER || type == Ai.TRADE
 || correction == TRANSFER || flows == null) {
  // Custody-owned trade settlements consume their reservation separately. Their financial
  // Result corrections must remain available without touching physical basis.
  return quantities;
 }
 for (Ab flow : flows) {
  if (flow == null || flow.quantityDelta >= 0L || wk(flow.itemId)) continue;
  long quantity = flow.quantityDelta == Long.MIN_VALUE ? Long.MAX_VALUE : -flow.quantityDelta;
  int itemId = flow.itemId;
  quantities.put(itemId, safeAdd(quantities.getOrDefault(itemId, 0L), quantity));
 }
 return quantities;
}

void kq(int itemId, long deltaQty, long deltaBasisGp) {
 if (deltaQty == 0L && deltaBasisGp == 0L) return;
 Az pool = pool(itemId, true);
 pool.setAvailableQty(pool.getAvailableQty() + deltaQty);
 pool.setAvailableBasisGp(pool.getAvailableBasisGp() + deltaBasisGp);
}

boolean awa(int itemId, long deltaQty, long deltaBasisGp, long transactionTimestamp) {
 if (transactionTimestamp < basisEpochMillis) return false;
 int id = itemId;
 Long amw = openReservationLookup.apply(id);
 if (amw != null && amw > 0L && amw >= transactionTimestamp) return false;
 Az pool = pools.get(id);
 long fence = pool == null ? 0L : pool.getRealizationFenceEpochMillis();
 if (fence > 0L && fence >= transactionTimestamp) return false;
 long availableQty = pool == null ? 0L : pool.getAvailableQty();
 long availableBasis = pool == null ? 0L : pool.getAvailableBasisGp();
 if (availableQty + deltaQty < 0L || availableBasis + deltaBasisGp < 0L) return false;
 return true;
}

Map<Integer, long[]> deltas(Map<Integer, long[]> before, Map<Integer, long[]> after) {
 var out = new LinkedHashMap<Integer, long[]>();
 for (Entry<Integer, long[]> entry : before.entrySet()) {
  long[] target = after.get(entry.getKey());
  long dq = (target == null ? 0L : target[0]) - entry.getValue()[0];
  long dv = (target == null ? 0L : target[1]) - entry.getValue()[1];
  if (dq != 0L || dv != 0L) {
   out.put(entry.getKey(), new long[] { dq, dv });
  }
 }
 for (Entry<Integer, long[]> entry : after.entrySet()) {
  if (!before.containsKey(entry.getKey())) {
   out.put(entry.getKey(), new long[] { entry.getValue()[0], entry.getValue()[1] });
  }
 }
 return out;
}

Map<Integer, long[]> contribution(Ac transaction) {
 if (transaction == null) return new LinkedHashMap<>();
 return contribution(transaction.tm(), transaction.isCounted(),
 transaction.getCorrection(), transaction.getFlows());
}

/** The contribution the transaction would have under {@code correction} with {@code flows}. */
Map<Integer, long[]> contribution(Ac transaction, Ah correction, List<Ab> flows) {
 return flows == null ? new LinkedHashMap<>() : contribution(transaction.tm(),
 rd(transaction.vn(), correction), correction, flows);
}

Map<Integer, Long> sinkQuantities(Ac transaction, Ah correction, List<Ab> flows) {
 return sinkQuantities(transaction.tm(),
 rd(transaction.vn(), correction), correction, flows);
}

/** Effective counted state for an explicit correction (REVENUE/COST always count). */
static boolean rd(boolean automaticallyCounted, Ah correction) {
 Ah effective = correction == null ? AUTO : correction;
 return effective == REVENUE || effective == COST || (effective == AUTO && automaticallyCounted);
}

/**
* Exact known coverage a counted transaction introduces, mirroring
* {@code Bp.flow} for positive quantities: AUTO counts the observed positive
* value, REVENUE counts the gross magnitude, COST/IGNORE/TRANSFER count nothing. Quantity is
* tracked even when the booked value is legitimately zero; unpriced/unknown/deferred sources
* create no coverage at all.
*/
static Map<Integer, long[]> contribution(Ai type, boolean counted,
Ah correction, List<Ab> flows) {
 var out = new LinkedHashMap<Integer, long[]>();
 Ah effective = correction == null ? AUTO : correction;
 if (type == Ai.TRANSFER || effective == TRANSFER || !counted || effective == IGNORE || flows == null) {
  return out;
 }
 for (Ab flow : flows) {
  if (flow == null || flow.quantityDelta <= 0L || wk(flow.itemId)) continue;
  Av source = flow.getPriceSource();
  if (source == null || source == UNPRICED || source == UNKNOWN || source == DEFERRED_CLAIM) continue;
  long revenue = effective == REVENUE ? Ae.abs(flow.valueDelta) : effective == COST ? 0L : nonNeg(flow.valueDelta);
  long[] total = out.computeIfAbsent(flow.itemId, itemId -> new long[2]);
  total[0] = safeAdd(total[0], flow.quantityDelta);
  total[1] = safeAdd(total[1], revenue);
 }
 return out;
}
}
