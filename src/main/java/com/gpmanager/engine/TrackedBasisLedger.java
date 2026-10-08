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
 long trackedQty;
 long basisGp;
 boolean isEmpty() {
  return trackedQty <= 0L && basisGp <= 0L;
 }
}

Map<Integer, Az> pools = new LinkedHashMap<>();
long basisEpochMillis;
java.util.function.IntFunction<Long> openReservationLookup = /* id */b -> 0L;
// ---------------------------------------------------------------------------------------
// Acquisition / removal / change
// ---------------------------------------------------------------------------------------
/**
* Book a newly added canonical transaction: add its exact known coverage, and deplete known
* coverage for proven permanent non-custody ownership sinks. Custody-owned settlements already
* consumed their reservation, so their item legs never deplete again.
*/
public synchronized void onAdded(Ac /* transaction */a, boolean /* custodyOwned */b) {
 if (a == null || a.timestampEpochMillis < basisEpochMillis) {
  // Pre-epoch stock is UNKNOWN coverage by migration law; nothing is inferred here.
  return;
 }
 if (basisEpochMillis <= 0L && a.timestampEpochMillis > 0L) basisEpochMillis = a.timestampEpochMillis;
 kc(contribution(a), a.timestampEpochMillis);
 if (!b) qx(a);
}

/** Validate restoring a previously removed transaction; false blocks the restore. */
public synchronized boolean canAdd(Ac /* transaction */a) {
 if (a == null) return false;
 if (a.timestampEpochMillis >= basisEpochMillis
 && !sinkQuantities(a.tm(), a.isCounted(), a.getCorrection(),
 a.getFlows()).isEmpty()) {
  return false;
 }
 return nj(contribution(a), 1L, a.timestampEpochMillis);
}

/** Validate removing a retained transaction (undo); false blocks the canonical removal. */
public synchronized boolean canRemove(Ac /* transaction */a) {
 if (a == null || a.timestampEpochMillis < basisEpochMillis) return true;
 if (!sinkQuantities(a.tm(), a.isCounted(),
 a.getCorrection(), a.getFlows()).isEmpty()) {
  return false;
 }
 return nj(contribution(a), -1L, a.timestampEpochMillis);
}

boolean nj(Map<Integer, long[]> /* deltas */a, long /* sign */b, long /* timestamp */c) {
 for (Entry<Integer, long[]> /* entry */d : a.entrySet()) {
  if (!awa(d.getKey(), b * d.getValue()[0], b * d.getValue()[1], c)) {
   return false;
  }
 }
 return true;
}

/** Reverse a previously removed transaction's exact known coverage. */
public synchronized void onRemoved(Ac /* transaction */a) {
 if (a == null || a.timestampEpochMillis < basisEpochMillis) return;
 contribution(a).forEach((/* itemId */b, /* delta */c) -> kq(b, -c[0], -c[1]));
}

/**
* Validate a pending correction/split/undo before any canonical mutation. False means the
* mutation must be rejected as-is: the item's known coverage is reserved or already realized.
*/
public synchronized boolean canChange(Ac /* transaction */a, Ah /* previousCorrection */b, List<Ab> /* previousFlows */c,
Ah /* nextCorrection */d, List<Ab> /* nextFlows */e) {
 if (a == null) return false;
 Map<Integer, long[]> /* before */f = contribution(a, b, c);
 // A null next-flow set means the caller can only promise the positive contribution
 // shrinks; validating against the empty worst case is the strictest safe check.
 Map<Integer, long[]> /* after */g = contribution(a, d, e);
 Map<Integer, Long> /* beforeSinks */h = sinkQuantities(a, b, c);
 Map<Integer, Long> /* afterSinks */i = sinkQuantities(a, d, e);
 if (a.timestampEpochMillis >= basisEpochMillis && !h.equals(i)
 && !np(a, h, i) && !nm(h, i,
 a.timestampEpochMillis)) {
  return false;
 }
 return nj(deltas(f, g), 1L, a.timestampEpochMillis);
}

/** Apply the exact effective delta after a permitted correction/split/undo. */
public synchronized void onChanged(Ac /* transaction */a, Ah /* previousCorrection */b, List<Ab> /* previousFlows */c) {
 if (a == null) return;
 Map<Integer, Long> /* beforeSinks */d = sinkQuantities(a, b, c);
 Map<Integer, Long> /* afterSinks */e = sinkQuantities(a.tm(),
 a.isCounted(), a.getCorrection(), a.getFlows());
 kc(deltas(contribution(a, b, c),
 contribution(a)), a.timestampEpochMillis);
 if (!d.equals(e)) {
  if (d.isEmpty()) qx(a);
  else ahc(a, d, e);
 }
}

// ---------------------------------------------------------------------------------------
// Reservation / realization / depletion
// ---------------------------------------------------------------------------------------
/** Reserve the tracked-first known-coverage share for a quantity entering proven SELL custody. */
synchronized Reservation reserve(int /* itemId */a, long /* quantity */b, long /* now */c) {
 int /* id */d = a;
 long /* requested */e = nonNeg(b);
 Az /* pool */f = pools.get(d);
 long /* availableQty */g = f == null ? 0L : f.getAvailableQty();
 long /* availableBasis */h = f == null ? 0L : f.getAvailableBasisGp();
 long /* consumeQty */i = min(e, g);
 long /* basis */j = Df.ayb(g, h, i);
 if (i <= 0L && j <= 0L) return new Reservation(0L, 0L);
 f = pool(d, true);
 f.setAvailableQty(g - i);
 f.setAvailableBasisGp(h - j);
 f.setReservedQty(f.getReservedQty() + i);
 f.setReservedBasisGp(f.getReservedBasisGp() + j);
 return new Reservation(i, j);
}

/** Move an exactly realized reservation share out of the reserved totals and fence the item. */
synchronized void qd(int /* itemId */a, long /* quantity */b, long /* basisGp */c, long /* now */d) {
 int /* id */e = a;
 Az /* pool */f = pool(e, true);
 f.setReservedQty(f.getReservedQty() - nonNeg(b));
 f.setReservedBasisGp(f.getReservedBasisGp() - nonNeg(c));
 f.setRealizationFenceEpochMillis(max(f.getRealizationFenceEpochMillis(), d));
}

/** Return an unconsumed reservation share to the available pool (cancel/return). */
synchronized void release(int /* itemId */a, long /* quantity */b, long /* basisGp */c) {
 int /* id */d = a;
 long /* releaseQty */e = nonNeg(b);
 long /* releaseBasis */f = nonNeg(c);
 if (e <= 0L && f <= 0L) return;
 Az /* pool */g = pool(d, true);
 g.setReservedQty(g.getReservedQty() - e);
 g.setReservedBasisGp(g.getReservedBasisGp() - f);
 g.setAvailableQty(g.getAvailableQty() + e);
 g.setAvailableBasisGp(g.getAvailableBasisGp() + f);
}

/** Deplete known coverage for a proven permanent ownership sink; returns basis consumed. */
synchronized long deplete(int /* itemId */b, long /* quantity */c, long /* now */d) {
 int /* id */e = b;
 long /* requested */f = nonNeg(c);
 Az /* pool */g = pools.get(e);
 if (f <= 0L) return 0L;
 if (g == null) {
  // Even a sink with no known coverage leaves a realization fence: units left ownership,
  // so later retroactive coverage for them can never be re-added.
  pool(e, true).setRealizationFenceEpochMillis(d);
  return 0L;
 }
 long /* consumeQty */h = min(f, g.getAvailableQty());
 long /* basis */i = Df.ayb(g.getAvailableQty(), g.getAvailableBasisGp(), h);
 g.setAvailableQty(g.getAvailableQty() - h);
 g.setAvailableBasisGp(g.getAvailableBasisGp() - i);
 g.setRealizationFenceEpochMillis(max(g.getRealizationFenceEpochMillis(), d));
 return i;
}

/** Currency is money, never tracked owned-item value. */
static boolean wk(int /* itemId */a) {
 return a == ItemID.COINS || a == ItemID.PLATINUM;
}

// ---------------------------------------------------------------------------------------
// Persistence / lifecycle
// ---------------------------------------------------------------------------------------
synchronized void ayc(SavedState /* state */b) {
 if (b == null) return;
 var /* out */c = new TrackedBasisState();
 // Epoch 0 only happens for an engine that never restored or tracked anything; a positive
 // sentinel keeps a saved pool loadable as "tracked since the beginning".
 c.basisEpochMillis = nonNeg(basisEpochMillis > 0L ? basisEpochMillis : 1L);
 var /* list */d = new ArrayList<Az>();
 for (Az /* pool */e : pools.values()) {
  if (e == null) continue;
  if (e.getAvailableQty() <= 0L && e.getAvailableBasisGp() <= 0L
  && e.getReservedQty() <= 0L && e.getReservedBasisGp() <= 0L && e.getRealizationFenceEpochMillis() <= 0L) {
   continue;
  }
  var /* copy */f = new Az(e.itemId);
  f.setAvailableQty(e.getAvailableQty());
  f.setAvailableBasisGp(e.getAvailableBasisGp());
  f.setReservedQty(e.getReservedQty());
  f.setReservedBasisGp(e.getReservedBasisGp());
  f.setRealizationFenceEpochMillis(e.getRealizationFenceEpochMillis());
  f.setLatestAcquisitionEpochMillis(e.getLatestAcquisitionEpochMillis());
  d.add(f);
 }
 d.removeIf(/* value */g -> g == null || g.itemId <= 0);
 c.pools = new ArrayList<>(d);
 b.setTrackedBasis(c);
}

/**
* Restore persisted continuity. A pre-106 (or absent) state starts with no known coverage at
* the migration boundary: existing holdings are UNKNOWN coverage, no bank value seeds basis and
* no history is reconstructed. Reserved totals are rebound from the durable custody records.
*/
synchronized void restore(SavedState /* state */a, List<Aa> /* custodyRecords */b, long /* now */c) {
 pools.clear();
 TrackedBasisState /* saved */d = a == null ? null : a.getTrackedBasis();
 if (d != null && d.getBasisEpochMillis() > 0L) {
  basisEpochMillis = d.getBasisEpochMillis();
  for (Az /* pool */e : d.awg()) {
   if (e == null || e.itemId <= 0) continue;
   pools.put(e.itemId, e);
  }
 } else {
  basisEpochMillis = c;
 }
 aek(b);
}

/** Recompute reserved totals from the durable custody records (they are the authority). */
synchronized void aek(List<Aa> /* custodyRecords */a) {
 for (Az /* pool */b : pools.values()) {
  b.setReservedQty(0L);
  b.setReservedBasisGp(0L);
 }
 if (a == null) return;
 for (Aa /* record */c : a) {
  if (c == null || c.getSide() != Aa.Side.SELL) continue;
  long /* remainingQty */d = c.afs();
  long /* remainingBasis */e = c.afr();
  if (d <= 0L && e <= 0L) continue;
  Az /* pool */f = pool(c.itemId, true);
  f.setReservedQty(f.getReservedQty() + d);
  f.setReservedBasisGp(f.getReservedBasisGp() + e);
 }
}

/** Destructive reset: tracked basis is continuity state and clears with the rest. */
synchronized void reset() {
 pools.clear();
 basisEpochMillis = 0L;
}

/** Adopt another ledger's durable state during a staged profile swap. */
synchronized void auc(TrackedBasisLedger /* other */a) {
 reset();
 if (a != null) {
  basisEpochMillis = a.basisEpochMillis;
  a.pools.forEach((/* itemId */b, /* pool */c) -> {
   if (c != null) pools.put(b, c);
  });
 }
}

// ---------------------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------------------
Az pool(int /* itemId */a, boolean /* create */b) {
 Az /* pool */c = pools.get(a);
 if (c == null && b) {
  c = new Az(a);
  pools.put(a, c);
 }
 return c;
}

void kc(Map<Integer, long[]> /* contribution */a, long /* timestamp */b) {
 for (Entry<Integer, long[]> /* entry */c : a.entrySet()) {
  kq(c.getKey(), c.getValue()[0], c.getValue()[1]);
  if (c.getValue()[0] > 0L) {
   Az /* pool */d = pool(c.getKey(), true);
   d.setLatestAcquisitionEpochMillis(max(d.getLatestAcquisitionEpochMillis(), b));
  }
 }
}

/** Deplete known coverage for each proven permanent non-custody ownership sink in the row. */
void qx(Ac /* transaction */a) {
 Map<Integer, Long> /* quantities */b = sinkQuantities(a.tm(),
 a.isCounted(), a.getCorrection(), a.getFlows());
 if (b.isEmpty()) return;
 List<Cd> /* shares */c = a.xe() ? new ArrayList<>() : null;
 for (Entry<Integer, Long> /* entry */d : b.entrySet()) {
  Az /* pool */e = pools.get(d.getKey());
  long /* knownQty */f = min(d.getValue(), e == null ? 0L : e.getAvailableQty());
  long /* basis */g = deplete(d.getKey(), d.getValue(), a.timestampEpochMillis);
  if (c != null) c.add(new Cd(d.getKey(), d.getValue(), f, g));
 }
 if (c != null) a.aie(c);
}

/** A confirmed own ground-drop pickup may undo its recorded sink share before later use. */
boolean np(Ac /* transaction */b, Map<Integer, Long> /* before */c, Map<Integer, Long> /* after */d) {
 if (!b.xe() || !b.yi() || c.isEmpty()) return false;
 if (!d.equals(sinkQuantities(b.tm(), true, AUTO, b.getFlows()))) {
  // A manual Ignore/Transfer keeps the physical loss intact. Only a proven pickup
  // shrinks the booked negative flows before this observer runs.
  return false;
 }
 for (Entry<Integer, Long> /* entry */e : c.entrySet()) {
  int /* itemId */f = e.getKey();
  long /* remaining */g = d.getOrDefault(f, 0L);
  if (g > e.getValue()) return false;
  Cd /* share */h = avi(b, f);
  if (h == null || h.uh() != e.getValue()) return false;
 }
 for (Integer /* itemId */i : d.keySet()) if (!c.containsKey(i)) return false;
 return true;
}

Cd avi(Ac /* transaction */a, int /* itemId */b) {
 for (Cd /* share */c : a.va()) {
  if (c != null && c.itemId == b) return c;
 }
 return null;
}

/** An owner may first count a Review removal while no later use has frozen its pool. */
boolean nm(Map<Integer, Long> /* before */a, Map<Integer, Long> /* after */b, long /* transactionTimestamp */c) {
 if (!a.isEmpty() || b.isEmpty()) return false;
 for (Integer /* itemId */d : b.keySet()) {
  if (!awa(d, 0L, 0L, c)) return false;
  Az /* pool */e = pools.get(d);
  if (e != null && e.getAvailableQty() > 0L && (e.getLatestAcquisitionEpochMillis() <= 0L
  || e.getLatestAcquisitionEpochMillis() > c)) {
   return false;
  }
 }
 return true;
}

void ahc(Ac /* transaction */a, Map<Integer, Long> /* before */b, Map<Integer, Long> /* after */c) {
 for (Entry<Integer, Long> /* entry */d : b.entrySet()) {
  long /* amu */e = d.getValue() - c.getOrDefault(d.getKey(), 0L);
  if (e <= 0L) continue;
  Cd /* share */f = avi(a, d.getKey());
  if (f == null) continue;
  long /* knownQty */g = Df.ayb(f.uh(), f.ug(), e);
  long /* basis */h = Df.ayb(f.ug(), nonNeg(f.remainingBasisGp), g);
  kq(d.getKey(), g, h);
  f.restore(e, g, h);
 }
}

/** Physical counted removals that would require exact reversal of pooled sink depletion. */
Map<Integer, Long> sinkQuantities(Ai /* type */a, boolean /* counted */b, Ah /* correction */c, List<Ab> /* flows */d) {
 var /* quantities */e = new LinkedHashMap<Integer, Long>();
 if (!b || a == Ai.TRANSFER || a == Ai.TRADE
 || c == TRANSFER || d == null) {
  // Custody-owned trade settlements consume their reservation separately. Their financial
  // Result corrections must remain available without touching physical basis.
  return e;
 }
 for (Ab /* flow */f : d) {
  if (f == null || f.quantityDelta >= 0L || wk(f.itemId)) continue;
  long /* quantity */g = f.quantityDelta == Long.MIN_VALUE ? Long.MAX_VALUE : -f.quantityDelta;
  int /* itemId */h = f.itemId;
  e.put(h, safeAdd(e.getOrDefault(h, 0L), g));
 }
 return e;
}

void kq(int /* itemId */a, long /* deltaQty */b, long /* deltaBasisGp */c) {
 if (b == 0L && c == 0L) return;
 Az /* pool */d = pool(a, true);
 d.setAvailableQty(d.getAvailableQty() + b);
 d.setAvailableBasisGp(d.getAvailableBasisGp() + c);
}

boolean awa(int /* itemId */a, long /* deltaQty */b, long /* deltaBasisGp */c, long /* transactionTimestamp */d) {
 if (d < basisEpochMillis) return false;
 int /* id */e = a;
 Long /* amw */f = openReservationLookup.apply(e);
 if (f != null && f > 0L && f >= d) return false;
 Az /* pool */g = pools.get(e);
 long /* fence */h = g == null ? 0L : g.getRealizationFenceEpochMillis();
 if (h > 0L && h >= d) return false;
 long /* availableQty */i = g == null ? 0L : g.getAvailableQty();
 long /* availableBasis */j = g == null ? 0L : g.getAvailableBasisGp();
 if (i + b < 0L || j + c < 0L) return false;
 return true;
}

Map<Integer, long[]> deltas(Map<Integer, long[]> /* before */a, Map<Integer, long[]> /* after */b) {
 var /* out */c = new LinkedHashMap<Integer, long[]>();
 for (Entry<Integer, long[]> /* entry */d : a.entrySet()) {
  long[] /* target */e = b.get(d.getKey());
  long /* dq */f = (e == null ? 0L : e[0]) - d.getValue()[0];
  long /* dv */g = (e == null ? 0L : e[1]) - d.getValue()[1];
  if (f != 0L || g != 0L) {
   c.put(d.getKey(), new long[] { f, g });
  }
 }
 for (Entry<Integer, long[]> /* entry */h : b.entrySet()) {
  if (!a.containsKey(h.getKey())) {
   c.put(h.getKey(), new long[] { h.getValue()[0], h.getValue()[1] });
  }
 }
 return c;
}

Map<Integer, long[]> contribution(Ac /* transaction */a) {
 if (a == null) return new LinkedHashMap<>();
 return contribution(a.tm(), a.isCounted(),
 a.getCorrection(), a.getFlows());
}

/** The contribution the transaction would have under {@code correction} with {@code flows}. */
Map<Integer, long[]> contribution(Ac /* transaction */a, Ah /* correction */b, List<Ab> /* flows */c) {
 return c == null ? new LinkedHashMap<>() : contribution(a.tm(),
 rd(a.vn(), b), b, c);
}

Map<Integer, Long> sinkQuantities(Ac /* transaction */a, Ah /* correction */b, List<Ab> /* flows */c) {
 return sinkQuantities(a.tm(),
 rd(a.vn(), b), b, c);
}

/** Effective counted state for an explicit correction (REVENUE/COST always count). */
static boolean rd(boolean /* automaticallyCounted */a, Ah /* correction */b) {
 Ah /* effective */c = b == null ? AUTO : b;
 return c == REVENUE || c == COST || (c == AUTO && a);
}

/**
* Exact known coverage a counted transaction introduces, mirroring
* {@code Bp.flow} for positive quantities: AUTO counts the observed positive
* value, REVENUE counts the gross magnitude, COST/IGNORE/TRANSFER count nothing. Quantity is
* tracked even when the booked value is legitimately zero; unpriced/unknown/deferred sources
* create no coverage at all.
*/
static Map<Integer, long[]> contribution(Ai /* type */a, boolean /* counted */b,
Ah /* correction */c, List<Ab> /* flows */d) {
 var /* out */e = new LinkedHashMap<Integer, long[]>();
 Ah /* effective */f = c == null ? AUTO : c;
 if (a == Ai.TRANSFER || f == TRANSFER || !b || f == IGNORE || d == null) {
  return e;
 }
 for (Ab /* flow */g : d) {
  if (g == null || g.quantityDelta <= 0L || wk(g.itemId)) continue;
  Av /* source */h = g.getPriceSource();
  if (h == null || h == UNPRICED || h == UNKNOWN || h == DEFERRED_CLAIM) continue;
  long /* revenue */i = f == REVENUE ? Ae.abs(g.valueDelta) : f == COST ? 0L : nonNeg(g.valueDelta);
  long[] /* total */j = e.computeIfAbsent(g.itemId, /* itemId */k -> new long[2]);
  j[0] = safeAdd(j[0], g.quantityDelta);
  j[1] = safeAdd(j[1], i);
 }
 return e;
}
}
