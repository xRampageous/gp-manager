package com.gpmanager;
import java.util.*;
import lombok.*;
import static com.gpmanager.Ah.*;
/**
* Derived, read-only Market settlement context over durable schema-104 custody lifecycles.
*
* <p>It never books GP, never owns corrections and never becomes a second financial authority.
* For a REALIZED lifecycle it explains the canonical settlement already booked; pending
* Execution Edge is exposed separately and is never folded into a realized total.</p>
*/
class Bi {
/** Behavioural lifecycle state; several durable stages may map to one state. */
enum Lifecycle {
 PENDING, PARTIALLY_EXECUTED, EXECUTED_UNSETTLED, PARTIALLY_REALIZED, REALIZED, CANCELLED_RETURNED, RESUMED, AMBIGUOUS,
 UNAVAILABLE, CLOSED_UNOBSERVED
}

/** Coverage of the realized quantity: proven prior tracked value, partial, or unknown. */
enum Coverage {
 FULLY_KNOWN, PARTIALLY_KNOWN, FULLY_UNKNOWN
}

/** One immutable Market presentation row; no booking behaviour. */
@EqualsAndHashCode
static class Row {
 String presentationId;
 long timestampEpochMillis;
 int slot;
 int itemId;
 String itemName;
 Aa.Side side;
 long offeredQty;
 long filledQty;
 /** Exact frozen unit basis used as the comparison value; 0 when unavailable. */
 long basisUnitPrice;
 long settledQty;
 long observedSettlementGp;
 long settlementAdjustmentGp;
 /**
 * Observed gross-to-cash gap matching the per-item GE rule. It is the shared accepted-tax
 * predicate: when the booked settlement is a counted unknown-basis sale, this exact tax is
 * the booked Net contribution; otherwise it stays presentation evidence only.
 */
 long inferredGeTaxGp;
 /** Actual after-tax Received per unit when exactly divisible; -1 when not provable. */
 long receivedEachGp;
 /** Realized execution/gross GP attributable to the collected quantity; 0 until collected. */
 /** Settlement/collection observation time; 0 when no exact settlement receipt resolves. */
 long settlementAtEpochMillis;
 long realizedResultGp;
 boolean realizedResultCorrectionAware;
 /** An explicit counted correction can supply a Result even when automatic basis is unknown. */
 boolean manualFinancialResult;
 /** Any applied correction (AUTO=false) on the booked settlement transaction. */
 boolean correctionApplied;
 /**
 * The lifecycle failed closed and was quarantined: its booked allocation was never
 * observed as collected cash, so presentation must never show it as Received.
 */
 boolean collectionAmbiguous;
 /**
 * Booked contribution state: a fully-unknown sale whose counted AUTO settlement
 * contributes exactly {@code -inferredGeTaxGp}. Derivation is from the booked transaction
 * (counted + AUTO + effective net equals the shared accepted tax), never from coverage
 * plus an inferred tax alone; a pre-change uncounted sale stays UNCOUNTED.
 */
 boolean knownCostOnly;
 /** SELL: known-coverage quantity consumed by realized settlements; BUY: realized quantity. */
 /** SELL: realized quantity with no provable prior tracked value (never basis zero). */
 /** SELL: known basis consumed; BUY: created known basis (the exact spend). */
 long trackedBasisConsumedGp;
 /** Settlement share attributable to known coverage (the part that may carry Result). */
 /**
 * Settlement share with unknown prior basis; liquidation cash that never carries an
 * automatic Result. Only a proven GE tax may contribute Net for such a sale.
 */
 /** Frozen GE/listing gross reference value for the settled quantity; -1 when unavailable. */
 /** Exact per-item GE tax that would apply to the frozen reference; 0 when none applies. */
 long expectedReferenceTaxGp;
 /** Frozen reference proceeds AFTER expected per-item GE tax; -1 when unavailable. */
 long geReferenceGp;
 /** Actual after-tax Received minus the tax-adjusted reference; execution comparison only. */
 long geDifferenceGp;
 boolean geDifferenceAvailable;
 Coverage coverage;
 Lifecycle lifecycle;
 String settlementId;
 /**
 * The session whose scope shows this row: the session holding its booked settlement,
 * otherwise the listing (origin) session.
 */
 String scopeSessionId;
 /** Display name of the listing session when the sale settled in another one; else empty. */
 String listedDuring;
 Row(Aa /* record */b, java.util.function.Function<String, Ac> /* lookup */c, SessionLookup /* sessions */d) {
  this.presentationId = b.getOfferId();
  String /* originSessionId */e = b.getOriginSessionId();
  Ad /* holder */f = d == null || b.getSettlementId().isEmpty()
  ? null : d.holding(b.getSettlementId());
  this.scopeSessionId = f == null ? e : f.getId();
  Ad /* origin */g = f == null || e.isEmpty()
  || e.equals(f.getId()) ? null : d.byId(e);
  this.listedDuring = g == null ? "" : g.getName();
  this.timestampEpochMillis = b.getPlacedAtEpochMillis();
  this.slot = b.slot;
  this.itemId = b.itemId;
  this.itemName = b.getItemName();
  this.side = b.getSide();
  this.offeredQty = b.getOfferedQty();
  this.filledQty = b.getFilledQty();
  boolean /* sell */h = b.getSide() == Aa.Side.SELL;
  boolean /* basisKnown */i = b.vf() && !b.wx();
  this.basisUnitPrice = i ? b.getBasisUnitPrice() : 0L;
  this.settledQty = b.getSettledQty();
  this.observedSettlementGp = b.settledCashGp;
  this.settlementAdjustmentGp = h && b.getSettledQty() > 0L
  ? b.settledCashGp - b.getSettledExecutionGp() : 0L;
  // Actual after-tax settled unit value, only when the exact division is proven; -1
  // means "not exactly derivable" and is never silently rounded for display.
  long /* receivedCash */j = Ae.abs(b.settledCashGp);
  // One shared accepted-tax predicate for booking and presentation: the per-fill tax
  // when every fill was observed at its own price, else the uniform rule.
  this.inferredGeTaxGp = h && b.fillTaxExact && b.getSettledTaxGp() > 0L
  && b.getSettledExecutionGp() - j == b.getSettledTaxGp() ? b.getSettledTaxGp()
  : h ? GeTaxRule.jv(b.itemId, b.getSettledExecutionGp(),
  b.getSettledQty(), j) : 0L;
  this.receivedEachGp = h && b.getSettledQty() > 0L && j > 0L
  && j % b.getSettledQty() == 0L ? j / b.getSettledQty() : -1L;
  Ac /* settlement */k = c == null || b.getSettlementId().isEmpty()
  ? null : c.apply(b.getSettlementId());
  this.settlementAtEpochMillis = k == null ? 0L : k.timestampEpochMillis;
  if (k != null) {
   // Ah-aware: consume the effective canonical truth of the booked settlement.
   this.realizedResultGp = Bp.transaction(k).getNet();
   this.realizedResultCorrectionAware = true;
   this.manualFinancialResult = k.getCorrection() == REVENUE || k.getCorrection() == COST;
   this.correctionApplied = k.getCorrection() != AUTO;
  } else {
   // The canonical receipt is no longer inspectable; the booked accumulator is
   // exposed but explicitly marked as not correction-recomputed.
   this.realizedResultGp = b.realizedResultGp;
   this.realizedResultCorrectionAware = false;
   this.manualFinancialResult = false;
   this.correctionApplied = false;
  }
  long /* settled */l = b.getSettledQty();
  long /* knownConsumed */m = h ? Math.min(b.getConsumedTrackedQty(), l) : l;
  this.trackedBasisConsumedGp = l <= 0L ? 0L
  : h ? b.getConsumedTrackedBasisGp() : Ae.abs(b.settledCashGp);
  this.coverage = l <= 0L ? Coverage.FULLY_UNKNOWN : l - m <= 0L ? Coverage.FULLY_KNOWN
  : m <= 0L ? Coverage.FULLY_UNKNOWN : Coverage.PARTIALLY_KNOWN;
  // KNOWN_COST_ONLY is a booked state, not merely coverage plus an inferred tax: a
  // pre-change uncounted sale keeps UNCOUNTED even when its custody evidence still
  // proves the historical tax, and a corrected sale is no longer automatic.
  this.knownCostOnly = h && l > 0L && this.coverage == Coverage.FULLY_UNKNOWN && k != null
  && k.vn() && k.getCorrection() == AUTO && this.inferredGeTaxGp > 0L
  && this.realizedResultGp == -this.inferredGeTaxGp;
  long /* amv */n = l <= 0L || !i ? -1L : b.lo(l);
  long /* apw */o = n >= 0L && h
  ? GeTaxRule.axc(b.itemId, b.getBasisUnitPrice(), l) : 0L;
  long /* referenceNet */q = n < 0L ? -1L : Ae.nonNeg(n - o);
  this.expectedReferenceTaxGp = n < 0L ? 0L : o;
  this.geReferenceGp = q;
  this.geDifferenceAvailable = q >= 0L && l > 0L;
  this.geDifferenceGp = !this.geDifferenceAvailable ? 0L : h ? b.settledCashGp - q
  : q - Ae.abs(b.settledCashGp);
  this.lifecycle = yw(b);
  this.collectionAmbiguous = b.collectionAmbiguous;
  this.settlementId = b.getSettlementId();
 }
 boolean isRealizedIncluded() {
  return lifecycle == Lifecycle.REALIZED || lifecycle == Lifecycle.PARTIALLY_REALIZED;
 }
}

/**
* Project the durable custody lifecycles. {@code settlementLookup} resolves a settlement id to
* its effective canonical transaction so corrections recompute the realized result; when it
* cannot resolve one, the row fails closed and flags the value as not correction-recomputed.
*/
/** Ad resolution for scope and the cross-Grind "Listed during" label. */
interface SessionLookup {
 /** The session holding this booked transaction, or null when none is retained. */
 Ad holding(String /* transactionId */b);
 Ad byId(String /* sessionId */b);
}

static List<Row> rows(List<Aa> /* records */a,
java.util.function.Function<String, Ac> /* settlementLookup */b, SessionLookup /* sessions */c) {
 if (Ag.empty(a)) return Collections.emptyList();
 var /* rows */d = new ArrayList<Row>(a.size());
 for (Aa /* record */e : a) {
  if (e != null && !e.getOfferId().isEmpty()) d.add(new Row(e, b, c));
 }
 return Collections.unmodifiableList(d);
}

/** Behavioural lifecycle for one durable record. Pending edge is never realized here. */
static Lifecycle yw(Aa /* record */a) {
 if (a == null) return Lifecycle.UNAVAILABLE;
 if (a.wx()) return Lifecycle.UNAVAILABLE;
 if (a.getStage() == Aa.Stage.CLOSED_UNOBSERVED) return Lifecycle.CLOSED_UNOBSERVED;
 if (a.getConfidence() == Aa.Confidence.AMBIGUOUS || a.quantityModified || a.collectionAmbiguous) {
  return Lifecycle.AMBIGUOUS;
 }
 if (a.getFilledQty() <= 0L) {
  if (a.vq() || a.cleared) return Lifecycle.CANCELLED_RETURNED;
  if (a.getConfidence() == Aa.Confidence.RESUMED) return Lifecycle.RESUMED;
  return Lifecycle.PENDING;
 }
 if (a.getSettledQty() > 0L && a.getSettledQty() < a.getFilledQty()) {
  return Lifecycle.PARTIALLY_REALIZED;
 }
 if (a.getSettledQty() >= a.getFilledQty() && a.getSettledQty() > 0L) return Lifecycle.REALIZED;
 if (a.getFilledQty() >= a.getOfferedQty()) return Lifecycle.EXECUTED_UNSETTLED;
 return Lifecycle.PARTIALLY_EXECUTED;
}
}
