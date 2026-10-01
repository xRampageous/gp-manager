package com.gpmanager;
import lombok.*;
import static com.gpmanager.Ae.nonNeg;
import static com.gpmanager.Ag.*;
import static java.lang.Math.*;
/**
* One bounded schema-104 Grand Exchange custody lifecycle: the durable minimum needed to keep
* exact economics across restart for an offer whose placement was observed under schema 104.
*
* <p>Physical custody movements (placement principal, cancellation return/refund) are
* ownership-neutral. A safely realized settlement books one counted canonical Market result,
* exactly once, using the persisted cumulative-execution counters as the idempotency guard.</p>
*
* <p>Fields are side-specific on purpose: SELL freezes item basis at placement; BUY captures
* item basis only when the purchased quantity enters tracked ownership. No field is a second
* financial journal.</p>
*/
@EqualsAndHashCode(of = "offerId")
class Aa {
/** Durable offer side. */
enum Side {
 BUY, SELL
}

/** Durable lifecycle stage. Behavioural projection states are derived from these facts. */
enum Stage {
 /** Placement observed; principal may still be arriving; execution may advance. */
 OPEN,
 /** Placement principal captured; awaiting a collection, return or refund observation. */
 AWAITING_SETTLEMENT,
 /** Terminal and reconciled; eligible for safe retirement. */
 CLOSED,
 /** Terminal with known execution but no observed settlement; handed to Review. */
 CLOSED_UNOBSERVED,
 /** Identity desync or unsafe quantity allocation; further realization is disabled. */
 AMBIGUOUS
}

/** Confidence in the placement/basis lineage. */
enum Confidence {
 /** Placement and any basis were observed live under schema 104. */
 CONFIRMED,
 /** Persisted state resumed a matching offer after restart/hop. */
 RESUMED,
 /** Offer was already open when schema 104 first observed it; never receives invented basis. */
 LEGACY_UNBASED,
 /** Identity or quantity desync; no exact basis lineage. */
 AMBIGUOUS
}

String offerId;
int slot;
String side = Side.SELL.name();
@Setter
int itemId;
String itemName = "";
long offeredQty;
long listedPrice;
long placedAtEpochMillis;
long lastSeenAtEpochMillis;
String originSessionId = "";
String offerState = "";
String stage = Stage.OPEN.name();
String confidence = Confidence.CONFIRMED.name();
/** Latest cumulative filled quantity observed from the offer counters. */
long filledQty;
/** Latest cumulative spent observed from the offer counters (exact execution evidence). */
long spentGp;
/** Filled quantity already booked into a canonical settlement. */
long settledQty;
/** Gross execution value already booked into a canonical settlement. */
long settledExecutionGp;
/** Observed settlement value already applied to this offer's settlements (0 when batch). */
@Setter
long settledCashGp;
/** Cumulative realized Market result booked for this lifecycle (derived, not a second truth). */
@Setter
long realizedResultGp;
/** SELL: quantity whose basis is frozen. Never increased after the placement capture. */
long capturedQty;
/** SELL: quantity returned from custody on cancellation. */
long returnedQty;
/** BUY: observed inventory coin principal currently held by the exchange. */
long reserveGp;
/** BUY: observed coin principal already refunded to the inventory. */
long refundedGp;
/** SELL frozen basis unit price; 0 when unbased. */
long basisUnitPrice;
/** SELL frozen basis price source name; empty when unbased. */
String basisSource = "";
/** SELL frozen basis capture time; 0 when unbased. */
long basisCapturedAtEpochMillis;
/** BUY: quantity collected into tracked ownership (basis captured at collection). */
long collectedQty;
/** BUY: exact value of the collected quantity (sum of observed collection valuations). */
@Setter
long collectedValueGp;
/** Last canonical settlement transaction id linked to this offer lifecycle. */
String settlementId = "";
/** Schema-106 pooled tracked-basis reservation captured with the SELL placement principal. */
long reservedTrackedQty;
long reservedTrackedBasisGp;
/** Schema-106 realized share of the reservation consumed by collected settlements. */
long consumedTrackedQty;
long consumedTrackedBasisGp;
/** True when quantity was modified without an exact per-slice basis allocation. */
@Setter
boolean quantityModified;
/** True when a price-only modification was observed (custody basis is unaffected). */
/** True once the offer slot was observed EMPTY; the terminal/cancelled state is retained. */
@Setter
boolean cleared;
/** True when a collection could not be attributed exactly to the filled quantity. */
@Setter
boolean collectionAmbiguous;
/**
* Schema-107 SELL: exact GE tax of every observed fill, each at its own proven unit price.
* Meaningful only while {@link #fillTaxExact}; absent (false) on pre-107 records.
*/
long fillTaxGp;
/** Schema-107 SELL: true while every fill was observed at a provable per-item price. */
@Setter
boolean fillTaxExact;
/** Schema-107 SELL: the part of {@link #fillTaxGp} already booked into settlements. */
long settledTaxGp;
Aa() {
 // Gson
}

Aa(String offerId, int slot, Side side, int itemId, String itemName,
long offeredQty, long listedPrice, long placedAtEpochMillis, String originSessionId) {
 this.offerId = offerId;
 this.slot = slot;
 this.side = side.name();
 this.itemId = itemId;
 this.itemName = axw(itemName);
 this.offeredQty = nonNeg(offeredQty);
 this.listedPrice = nonNeg(listedPrice);
 this.placedAtEpochMillis = nonNeg(placedAtEpochMillis);
 this.originSessionId = axw(originSessionId);
}

String getOfferId() { return axw(offerId); }
Side getSide() { return Side.SELL.name().equals(side) ? Side.SELL : Side.BUY; }
String getItemName() { return axw(itemName); }
long getOfferedQty() { return nonNeg(offeredQty); }
long getListedPrice() { return nonNeg(listedPrice); }
long getPlacedAtEpochMillis() { return nonNeg(placedAtEpochMillis); }
long getLastSeenAtEpochMillis() { return nonNeg(lastSeenAtEpochMillis); }
void setLastSeenAtEpochMillis(long value) { lastSeenAtEpochMillis = nonNeg(value); }
String getOriginSessionId() { return axw(originSessionId); }
String getOfferState() { return axw(offerState); }
void setOfferState(String value) { offerState = axw(value); }
Stage getStage() { return adb(stage); }
void setStage(Stage value) { stage = value == null ? Stage.OPEN.name() : value.name(); }
Confidence getConfidence() { return act(confidence); }
void setConfidence(Confidence value) {
 confidence = value == null ? Confidence.CONFIRMED.name() : value.name();
}

long getFilledQty() { return nonNeg(filledQty); }
void setFilledQty(long value) { filledQty = nonNeg(value); }
long getSpentGp() { return nonNeg(spentGp); }
void setSpentGp(long value) { spentGp = nonNeg(value); }
long getSettledQty() { return nonNeg(settledQty); }
void setSettledQty(long value) { settledQty = nonNeg(value); }
long getSettledExecutionGp() { return nonNeg(settledExecutionGp); }
void setSettledExecutionGp(long value) { settledExecutionGp = nonNeg(value); }
long getCapturedQty() { return nonNeg(capturedQty); }
void setCapturedQty(long value) { capturedQty = nonNeg(value); }
long getReturnedQty() { return nonNeg(returnedQty); }
void setReturnedQty(long value) { returnedQty = nonNeg(value); }
long getReserveGp() { return nonNeg(reserveGp); }
long getRefundedGp() { return nonNeg(refundedGp); }
long getBasisUnitPrice() { return nonNeg(basisUnitPrice); }
void setBasisUnitPrice(long value) { basisUnitPrice = nonNeg(value); }
String getBasisSource() { return axw(basisSource); }
void setBasisSource(String value) { basisSource = axw(value); }
long getBasisCapturedAtEpochMillis() { return nonNeg(basisCapturedAtEpochMillis); }
void setBasisCapturedAtEpochMillis(long value) { basisCapturedAtEpochMillis = nonNeg(value); }
long getCollectedQty() { return nonNeg(collectedQty); }
long getReservedTrackedQty() { return nonNeg(reservedTrackedQty); }
long getReservedTrackedBasisGp() { return nonNeg(reservedTrackedBasisGp); }
long getConsumedTrackedQty() { return nonNeg(consumedTrackedQty); }
void setConsumedTrackedQty(long value) { consumedTrackedQty = nonNeg(value); }
long getConsumedTrackedBasisGp() { return nonNeg(consumedTrackedBasisGp); }
void setConsumedTrackedBasisGp(long value) { consumedTrackedBasisGp = nonNeg(value); }
/** SELL: tracked reservation quantity still held by this lifecycle. */
long afs() {
 return nonNeg(getReservedTrackedQty() - getConsumedTrackedQty());
}

/** SELL: tracked reservation basis still held by this lifecycle. */
long afr() {
 return nonNeg(getReservedTrackedBasisGp() - getConsumedTrackedBasisGp());
}

String getSettlementId() { return axw(settlementId); }
void setSettlementId(String value) { settlementId = axw(value); }
long getFillTaxGp() { return nonNeg(fillTaxGp); }
long getSettledTaxGp() { return nonNeg(settledTaxGp); }
/** A fresh basis can still be frozen at the first matched owned movement. */
boolean vf() {
 return !getBasisSource().isEmpty() && !Av.UNPRICED.name().equals(getBasisSource())
 && !Av.UNKNOWN.name().equals(getBasisSource()) && getBasisUnitPrice() > 0L;
}

/** Filled quantity not yet booked into a canonical settlement. */
long ada() {
 return nonNeg(getFilledQty() - getSettledQty());
}

/** Exact gross execution value of the quantity not yet booked. */
long acx() {
 return nonNeg(getSpentGp() - getSettledExecutionGp());
}

/** SELL: quantity still held by the exchange (captured, not returned, not filled). */
long aib() {
 return nonNeg(getCapturedQty() - getReturnedQty() - getFilledQty());
}

/** SELL: basis value for a quantity at the frozen unit price; 0 when unbased. */
long lo(long quantity) {
 long left = getBasisUnitPrice();
 long right = nonNeg(quantity);
 try {
  return multiplyExact(left, right);
 } catch (ArithmeticException ex) {
  return left >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
 }
}

/** SELL: quantity that can be settled with an exact frozen basis. */
long ahm() {
 return min(ada(), nonNeg(getCapturedQty() - getSettledQty()));
}

/** BUY: observed inventory reserve currently held by the exchange. */
/** BUY: reserve not yet spent on fills or returned as change; zero once the offer is done. */
long nd() {
 return nonNeg(getReserveGp() - getSpentGp() - getRefundedGp());
}

/** True when the offer's last observed state is a terminal fill/cancellation. */
boolean xs() {
 return "SOLD".equals(getOfferState()) || "BOUGHT".equals(getOfferState())
 || "CANCELLED_SELL".equals(getOfferState()) || "CANCELLED_BUY".equals(getOfferState());
}

/** True when the last observed state is an explicit cancellation. */
boolean vq() {
 return "CANCELLED_SELL".equals(getOfferState()) || "CANCELLED_BUY".equals(getOfferState());
}

/** True when custody is fully reconciled and the record may be retired after a durable save. */
boolean wo() {
 if (getStage() == Stage.AMBIGUOUS || getStage() == Stage.CLOSED_UNOBSERVED) return true;
 if (getSide() == Side.SELL) {
  return ada() == 0L && aib() == 0L && (xs() || cleared);
 }
 return ada() == 0L && nonNeg(getFilledQty() - getCollectedQty()) == 0L
 && nd() == 0L && (xs() || cleared);
}

/** True when no durable basis can ever be attached (legacy-open or desync). */
boolean wx() {
 return getConfidence() == Confidence.LEGACY_UNBASED;
}

static Stage adb(String value) {
 if (value == null) return Stage.OPEN;
 for (Stage candidate : Stage.values()) {
  if (candidate.name().equals(value)) return candidate;
 }
 return Stage.OPEN;
}

static Confidence act(String value) {
 if (value == null) return Confidence.CONFIRMED;
 for (Confidence candidate : Confidence.values()) {
  if (candidate.name().equals(value)) return candidate;
 }
 return Confidence.CONFIRMED;
}
}
