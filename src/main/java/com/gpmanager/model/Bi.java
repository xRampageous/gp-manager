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
 final String presentationId;
 final long timestampEpochMillis;
 final int slot;
 final int itemId;
 final String itemName;
 final Aa.Side side;
 final long offeredQty;
 final long filledQty;
 /** Exact frozen unit basis used as the comparison value; 0 when unavailable. */
 final long basisUnitPrice;
 final long settledQty;
 final long observedSettlementGp;
 final long settlementAdjustmentGp;
 /**
 * Observed gross-to-cash gap matching the per-item GE rule. It is the shared accepted-tax
 * predicate: when the booked settlement is a counted unknown-basis sale, this exact tax is
 * the booked Net contribution; otherwise it stays presentation evidence only.
 */
 final long inferredGeTaxGp;
 /** Actual after-tax Received per unit when exactly divisible; -1 when not provable. */
 final long receivedEachGp;
 /** Realized execution/gross GP attributable to the collected quantity; 0 until collected. */
 /** Settlement/collection observation time; 0 when no exact settlement receipt resolves. */
 final long settlementAtEpochMillis;
 final long realizedResultGp;
 final boolean realizedResultCorrectionAware;
 /** An explicit counted correction can supply a Result even when automatic basis is unknown. */
 final boolean manualFinancialResult;
 /** Any applied correction (AUTO=false) on the booked settlement transaction. */
 final boolean correctionApplied;
 /**
 * The lifecycle failed closed and was quarantined: its booked allocation was never
 * observed as collected cash, so presentation must never show it as Received.
 */
 final boolean collectionAmbiguous;
 /**
 * Booked contribution state: a fully-unknown sale whose counted AUTO settlement
 * contributes exactly {@code -inferredGeTaxGp}. Derivation is from the booked transaction
 * (counted + AUTO + effective net equals the shared accepted tax), never from coverage
 * plus an inferred tax alone; a pre-change uncounted sale stays UNCOUNTED.
 */
 final boolean knownCostOnly;
 /** SELL: known-coverage quantity consumed by realized settlements; BUY: realized quantity. */
 /** SELL: realized quantity with no provable prior tracked value (never basis zero). */
 /** SELL: known basis consumed; BUY: created known basis (the exact spend). */
 final long trackedBasisConsumedGp;
 /** Settlement share attributable to known coverage (the part that may carry Result). */
 /**
 * Settlement share with unknown prior basis; liquidation cash that never carries an
 * automatic Result. Only a proven GE tax may contribute Net for such a sale.
 */
 /** Frozen GE/listing gross reference value for the settled quantity; -1 when unavailable. */
 /** Exact per-item GE tax that would apply to the frozen reference; 0 when none applies. */
 final long expectedReferenceTaxGp;
 /** Frozen reference proceeds AFTER expected per-item GE tax; -1 when unavailable. */
 final long geReferenceGp;
 /** Actual after-tax Received minus the tax-adjusted reference; execution comparison only. */
 final long geDifferenceGp;
 final boolean geDifferenceAvailable;
 final Coverage coverage;
 final Lifecycle lifecycle;
 final String settlementId;
 /**
 * The session whose scope shows this row: the session holding its booked settlement,
 * otherwise the listing (origin) session.
 */
 final String scopeSessionId;
 /** Display name of the listing session when the sale settled in another one; else empty. */
 final String listedDuring;
 Row(Aa record, java.util.function.Function<String, Ac> lookup, SessionLookup sessions) {
  this.presentationId = record.getOfferId();
  String originSessionId = record.getOriginSessionId();
  Ad holder = sessions == null || record.getSettlementId().isEmpty()
  ? null : sessions.holding(record.getSettlementId());
  this.scopeSessionId = holder == null ? originSessionId : holder.getId();
  Ad origin = holder == null || originSessionId.isEmpty()
  || originSessionId.equals(holder.getId()) ? null : sessions.byId(originSessionId);
  this.listedDuring = origin == null ? "" : origin.getName();
  this.timestampEpochMillis = record.getPlacedAtEpochMillis();
  this.slot = record.slot;
  this.itemId = record.itemId;
  this.itemName = record.getItemName();
  this.side = record.getSide();
  this.offeredQty = record.getOfferedQty();
  this.filledQty = record.getFilledQty();
  boolean sell = record.getSide() == Aa.Side.SELL;
  boolean basisKnown = record.vf() && !record.wx();
  this.basisUnitPrice = basisKnown ? record.getBasisUnitPrice() : 0L;
  this.settledQty = record.getSettledQty();
  this.observedSettlementGp = record.settledCashGp;
  this.settlementAdjustmentGp = sell && record.getSettledQty() > 0L
  ? record.settledCashGp - record.getSettledExecutionGp() : 0L;
  // Actual after-tax settled unit value, only when the exact division is proven; -1
  // means "not exactly derivable" and is never silently rounded for display.
  long receivedCash = Ae.abs(record.settledCashGp);
  // One shared accepted-tax predicate for booking and presentation: the per-fill tax
  // when every fill was observed at its own price, else the uniform rule.
  this.inferredGeTaxGp = sell && record.fillTaxExact && record.getSettledTaxGp() > 0L
  && record.getSettledExecutionGp() - receivedCash == record.getSettledTaxGp() ? record.getSettledTaxGp()
  : sell ? GeTaxRule.jv(record.itemId, record.getSettledExecutionGp(),
  record.getSettledQty(), receivedCash) : 0L;
  this.receivedEachGp = sell && record.getSettledQty() > 0L && receivedCash > 0L
  && receivedCash % record.getSettledQty() == 0L ? receivedCash / record.getSettledQty() : -1L;
  Ac settlement = lookup == null || record.getSettlementId().isEmpty()
  ? null : lookup.apply(record.getSettlementId());
  this.settlementAtEpochMillis = settlement == null ? 0L : settlement.timestampEpochMillis;
  if (settlement != null) {
   // Ah-aware: consume the effective canonical truth of the booked settlement.
   this.realizedResultGp = Bp.transaction(settlement).getNet();
   this.realizedResultCorrectionAware = true;
   this.manualFinancialResult = settlement.getCorrection() == REVENUE || settlement.getCorrection() == COST;
   this.correctionApplied = settlement.getCorrection() != AUTO;
  } else {
   // The canonical receipt is no longer inspectable; the booked accumulator is
   // exposed but explicitly marked as not correction-recomputed.
   this.realizedResultGp = record.realizedResultGp;
   this.realizedResultCorrectionAware = false;
   this.manualFinancialResult = false;
   this.correctionApplied = false;
  }
  long settled = record.getSettledQty();
  long knownConsumed = sell ? Math.min(record.getConsumedTrackedQty(), settled) : settled;
  this.trackedBasisConsumedGp = settled <= 0L ? 0L
  : sell ? record.getConsumedTrackedBasisGp() : Ae.abs(record.settledCashGp);
  this.coverage = settled <= 0L ? Coverage.FULLY_UNKNOWN : settled - knownConsumed <= 0L ? Coverage.FULLY_KNOWN
  : knownConsumed <= 0L ? Coverage.FULLY_UNKNOWN : Coverage.PARTIALLY_KNOWN;
  // KNOWN_COST_ONLY is a booked state, not merely coverage plus an inferred tax: a
  // pre-change uncounted sale keeps UNCOUNTED even when its custody evidence still
  // proves the historical tax, and a corrected sale is no longer automatic.
  this.knownCostOnly = sell && settled > 0L && this.coverage == Coverage.FULLY_UNKNOWN && settlement != null
  && settlement.vn() && settlement.getCorrection() == AUTO && this.inferredGeTaxGp > 0L
  && this.realizedResultGp == -this.inferredGeTaxGp;
  long amv = settled <= 0L || !basisKnown ? -1L : record.lo(settled);
  long apw = amv >= 0L && sell
  ? GeTaxRule.axc(record.itemId, record.getBasisUnitPrice(), settled) : 0L;
  long referenceNet = amv < 0L ? -1L : Ae.nonNeg(amv - apw);
  this.expectedReferenceTaxGp = amv < 0L ? 0L : apw;
  this.geReferenceGp = referenceNet;
  this.geDifferenceAvailable = referenceNet >= 0L && settled > 0L;
  this.geDifferenceGp = !this.geDifferenceAvailable ? 0L : sell ? record.settledCashGp - referenceNet
  : referenceNet - Ae.abs(record.settledCashGp);
  this.lifecycle = yw(record);
  this.collectionAmbiguous = record.collectionAmbiguous;
  this.settlementId = record.getSettlementId();
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
 Ad holding(String transactionId);
 Ad byId(String sessionId);
}

static List<Row> rows(List<Aa> records,
java.util.function.Function<String, Ac> settlementLookup, SessionLookup sessions) {
 if (Ag.empty(records)) return Collections.emptyList();
 var rows = new ArrayList<Row>(records.size());
 for (Aa record : records) {
  if (record != null && !record.getOfferId().isEmpty()) rows.add(new Row(record, settlementLookup, sessions));
 }
 return Collections.unmodifiableList(rows);
}

/** Behavioural lifecycle for one durable record. Pending edge is never realized here. */
static Lifecycle yw(Aa record) {
 if (record == null) return Lifecycle.UNAVAILABLE;
 if (record.wx()) return Lifecycle.UNAVAILABLE;
 if (record.getStage() == Aa.Stage.CLOSED_UNOBSERVED) return Lifecycle.CLOSED_UNOBSERVED;
 if (record.getConfidence() == Aa.Confidence.AMBIGUOUS || record.quantityModified || record.collectionAmbiguous) {
  return Lifecycle.AMBIGUOUS;
 }
 if (record.getFilledQty() <= 0L) {
  if (record.vq() || record.cleared) return Lifecycle.CANCELLED_RETURNED;
  if (record.getConfidence() == Aa.Confidence.RESUMED) return Lifecycle.RESUMED;
  return Lifecycle.PENDING;
 }
 if (record.getSettledQty() > 0L && record.getSettledQty() < record.getFilledQty()) {
  return Lifecycle.PARTIALLY_REALIZED;
 }
 if (record.getSettledQty() >= record.getFilledQty() && record.getSettledQty() > 0L) return Lifecycle.REALIZED;
 if (record.getFilledQty() >= record.getOfferedQty()) return Lifecycle.EXECUTED_UNSETTLED;
 return Lifecycle.PARTIALLY_EXECUTED;
}
}
