package com.gpmanager;

import static com.gpmanager.Phrases.*;
import static com.gpmanager.SafeMath.nonNeg;
import static java.lang.Math.*;

import java.util.Set;
import lombok.*;

/**
 * One bounded schema-104 Grand Exchange custody lifecycle: the durable minimum needed to keep exact
 * economics across restart for an offer whose placement was observed under schema 104.
 *
 * <p>Physical custody movements (placement principal, cancellation return/refund) are
 * ownership-neutral. A safely realized settlement books one counted canonical Market result,
 * exactly once, using the persisted cumulative-execution counters as the idempotency guard.
 *
 * <p>Fields are side-specific on purpose: SELL freezes item basis at placement; BUY captures item
 * basis only when the purchased quantity enters tracked ownership. No field is a second financial
 * journal.
 */
@EqualsAndHashCode(of = "offerId")
@Getter
@Setter
class GeOffer {
    /** Durable offer side. */
    enum Side {
        BUY,
        SELL
    }

    /** Durable lifecycle stage. Behavioural projection states are derived from these facts. */
    enum Stage {
        /** Placement observed; principal may still be arriving; execution may advance. */
        OPEN,
        /** Terminal and reconciled; eligible for safe retirement. */
        CLOSED
    }

    /** Confidence in the placement/basis lineage. */
    enum CustodyConfidence {
        /** Placement and any basis were observed live under schema 104. */
        CONFIRMED,
        /**
         * Offer was already open when schema 104 first observed it; never receives invented basis.
         */
        LEGACY_UNBASED,
        /** Identity or quantity desync; no exact basis lineage. */
        AMBIGUOUS
    }

    String offerId = "";
    int slot;
    Side side = Side.SELL;
    int itemId;
    String itemName = "";
    long offeredQty;
    long listedPrice;
    long placedAtEpochMillis;
    long lastSeenAtEpochMillis;
    String originSessionId = "";
    String offerState = "";
    Stage stage = Stage.OPEN;
    CustodyConfidence confidence = CustodyConfidence.CONFIRMED;

    /** Latest cumulative filled quantity observed from the offer counters. */
    long filledQty;

    /** Latest cumulative spent observed from the offer counters (exact execution evidence). */
    long spentGp;

    /** Filled quantity already booked into a canonical settlement. */
    long settledQty;

    /** Gross execution value already booked into a canonical settlement. */
    long settledExecutionGp;

    /** Observed settlement value already applied to this offer's settlements (0 when batch). */
    long settledCashGp;

    /**
     * Cumulative realized Market result booked for this lifecycle (derived, not a second truth).
     */
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

    /** SELL frozen basis price source; null until a basis is captured. */
    PriceSource basisSource;

    /** SELL frozen basis capture time; 0 when unbased. */
    long basisCapturedAtEpochMillis;

    /** BUY: quantity collected into tracked ownership (basis captured at collection). */
    long collectedQty;

    /** BUY: exact value of the collected quantity (sum of observed collection valuations). */
    long collectedValueGp;

    /** Last canonical settlement transaction id linked to this offer lifecycle. */
    String settlementId = "";

    /**
     * The earlier settlement ids, space-separated: an offer collected in parts books one settlement
     * per collect, and Live and the Ledger fold all of them into its one market row (owner
     * 2026-10-09).
     */
    String earlierSettlements = "";

    /** Schema-106 pooled tracked-basis reservation captured with the SELL placement principal. */
    long reservedTrackedQty;

    long reservedTrackedBasisGp;

    /** Schema-106 realized share of the reservation consumed by collected settlements. */
    long consumedTrackedQty;

    long consumedTrackedBasisGp;

    /** True when quantity was modified without an exact per-slice basis allocation. */
    boolean quantityModified;

    /** True once the offer slot was observed EMPTY; the terminal/cancelled state is retained. */
    boolean cleared;

    /** True when a collection could not be attributed exactly to the filled quantity. */
    boolean collectionAmbiguous;

    /**
     * Schema-107 SELL: exact GE tax of every observed fill, each at its own proven unit price.
     * Meaningful only while {@link #fillTaxExact}; absent (false) on pre-107 records.
     */
    long fillTaxGp;

    /** Schema-107 SELL: true while every fill was observed at a provable per-item price. */
    boolean fillTaxExact;

    /** Schema-107 SELL: the part of {@link #fillTaxGp} already booked into settlements. */
    long settledTaxGp;

    GeOffer() {
        // Gson
    }

    GeOffer(
            String offerId,
            int slot,
            Side side,
            int itemId,
            String itemName,
            long offeredQty,
            long listedPrice,
            long placedAtEpochMillis,
            String originSessionId) {
        this.offerId = offerId;
        this.slot = slot;
        this.side = side;
        this.itemId = itemId;
        this.itemName = orEmpty(itemName);
        this.offeredQty = nonNeg(offeredQty);
        this.listedPrice = nonNeg(listedPrice);
        this.placedAtEpochMillis = nonNeg(placedAtEpochMillis);
        this.originSessionId = orEmpty(originSessionId);
    }

    /** The frozen basis price source; UNKNOWN until one is captured. */
    PriceSource getBasisSource() {
        return basisSource == null ? PriceSource.UNKNOWN : basisSource;
    }

    /** SELL: tracked reservation quantity still held by this lifecycle. */
    long reservedQty() {
        return nonNeg(reservedTrackedQty - consumedTrackedQty);
    }

    /** SELL: tracked reservation basis still held by this lifecycle. */
    long reservedBasis() {
        return nonNeg(reservedTrackedBasisGp - consumedTrackedBasisGp);
    }

    void setSettlementId(String value) {
        earlierSettlements = earlierSettlements + " " + settlementId;
        settlementId = orEmpty(value);
    }

    /**
     * Clamps a record read from a save once, so the accessors can read fields directly: blank text
     * and missing enums take their defaults, and no counter is negative.
     */
    void sanitize() {
        offerId = orEmpty(offerId);
        itemName = orEmpty(itemName);
        originSessionId = orEmpty(originSessionId);
        offerState = orEmpty(offerState);
        settlementId = orEmpty(settlementId);
        earlierSettlements = orEmpty(earlierSettlements);
        if (side == null) side = Side.BUY;
        if (stage == null) stage = Stage.OPEN;
        if (confidence == null) confidence = CustodyConfidence.CONFIRMED;
        offeredQty = nonNeg(offeredQty);
        listedPrice = nonNeg(listedPrice);
        placedAtEpochMillis = nonNeg(placedAtEpochMillis);
        lastSeenAtEpochMillis = nonNeg(lastSeenAtEpochMillis);
        filledQty = nonNeg(filledQty);
        spentGp = nonNeg(spentGp);
        settledQty = nonNeg(settledQty);
        settledExecutionGp = nonNeg(settledExecutionGp);
        capturedQty = nonNeg(capturedQty);
        returnedQty = nonNeg(returnedQty);
        reserveGp = nonNeg(reserveGp);
        refundedGp = nonNeg(refundedGp);
        basisUnitPrice = nonNeg(basisUnitPrice);
        basisCapturedAtEpochMillis = nonNeg(basisCapturedAtEpochMillis);
        collectedQty = nonNeg(collectedQty);
        reservedTrackedQty = nonNeg(reservedTrackedQty);
        reservedTrackedBasisGp = nonNeg(reservedTrackedBasisGp);
        consumedTrackedQty = nonNeg(consumedTrackedQty);
        consumedTrackedBasisGp = nonNeg(consumedTrackedBasisGp);
        fillTaxGp = nonNeg(fillTaxGp);
        settledTaxGp = nonNeg(settledTaxGp);
    }

    /** A fresh basis can still be frozen at the first matched owned movement. */
    boolean hasFrozenBasis() {
        return basisSource != null
                && basisSource != PriceSource.UNPRICED
                && basisSource != PriceSource.UNKNOWN
                && basisUnitPrice > 0L;
    }

    /** Filled quantity not yet booked into a canonical settlement. */
    long pendingQty() {
        return nonNeg(filledQty - settledQty);
    }

    /** Exact gross execution value of the quantity not yet booked. */
    long pendingExecution() {
        return nonNeg(spentGp - settledExecutionGp);
    }

    /** SELL: quantity still held by the exchange (captured, not returned, not filled). */
    long heldQty() {
        return nonNeg(capturedQty - returnedQty - filledQty);
    }

    /** SELL: basis value for a quantity at the frozen unit price; 0 when unbased. */
    long basisFor(long quantity) {
        long left = basisUnitPrice;
        long right = nonNeg(quantity);
        try {
            return multiplyExact(left, right);
        } catch (ArithmeticException ex) {
            return left >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /** SELL: quantity that can be settled with an exact frozen basis. */
    long settleableQty() {
        return min(pendingQty(), nonNeg(capturedQty - settledQty));
    }

    /** BUY: reserve not yet spent on fills or returned as change; zero once the offer is done. */
    long reserveOutstanding() {
        return nonNeg(reserveGp - spentGp - refundedGp);
    }

    /** True when the offer's last observed state is a terminal fill/cancellation. */
    boolean isTerminal() {
        return "SOLD".equals(offerState) || "BOUGHT".equals(offerState) || isCancelled();
    }

    /** True when the last observed state is an explicit cancellation. */
    boolean isCancelled() {
        return "CANCELLED_SELL".equals(offerState) || "CANCELLED_BUY".equals(offerState);
    }

    /** True when custody is fully reconciled and the record may be retired after a durable save. */
    boolean isReconciled() {
        if (side == Side.SELL) {
            return pendingQty() == 0L && heldQty() == 0L && (isTerminal() || cleared);
        }
        return pendingQty() == 0L
                && nonNeg(filledQty - collectedQty) == 0L
                && reserveOutstanding() == 0L
                && (isTerminal() || cleared);
    }

    /** True when no durable basis can ever be attached (legacy-open or desync). */
    boolean isUnbased() {
        return confidence == CustodyConfidence.LEGACY_UNBASED;
    }
}

/**
 * The one authoritative GE sell-tax rule shared by custody settlement and the read model.
 *
 * <p>Ordinary taxed items pay 2% of the sale price rounded DOWN per item (so sub-50 gp items pay
 * nothing), capped at 5,000,000 gp per item. The wiki's Grand Exchange tax exemption list (checked
 * 2026-09-13, ids resolved against the pinned RuneLite gameval {@code ItemID} constants) pays
 * nothing regardless of price; a real client Collect All of the owner's seven-sale batch proved the
 * exempt low-level food case (cooked chicken sold at 69 gp paid no tax). Tax is never approximated
 * as a stack-wide 2%: it rounds per ITEM. Everything is integer-only and long-safe; there is no
 * floating point here and no second exemption list anywhere else in the codebase.
 */
class GeTaxRule {
    /**
     * Items exempt from the GE convenience fee, per the wiki's Grand Exchange tax exemption list.
     * Items under 50 gp pay nothing through rounding anyway, so most tools here matter only when
     * their price spikes.
     */
    static final Set<Integer> EXEMPT_IDS = GameData.byId("tax_exempt").keySet();

    /** Sentinel: no proven per-item price exists, so no tax can be asserted for an execution. */
    static final long UNPROVABLE_TAX = -1L;

    /**
     * The uniform per-item tax of one execution: {@link #UNPROVABLE_TAX} when the per-item price
     * cannot be proven (a non-exempt gross that is not divisible by the quantity), otherwise the
     * exact tax, which may legitimately be 0 (an exempt item, or a unit price below 50 gp). Each
     * item pays its price / 50 rounded down, at most 5,000,000 gp. The tax never exceeds gross /
     * 50, so it always fits a long.
     */
    static long provableTax(int itemId, long grossGp, long quantity) {
        if (grossGp <= 0L || quantity <= 0L) return UNPROVABLE_TAX;
        if (EXEMPT_IDS.contains(itemId)) return 0L;
        if (grossGp % quantity != 0L) return UNPROVABLE_TAX;
        return Math.min(5_000_000L, grossGp / quantity / 50L) * quantity;
    }

    /**
     * The proven sell tax of one settled execution: the exact positive gap between the observed
     * gross execution and the observed Received cash, accepted only when it equals the uniform
     * per-item rule for that execution. Zero means nothing is proven — a genuinely tax-free stack,
     * an exempt item, a non-uniform execution, or a gap that does not match the rule.
     *
     * <p>Booking and the read model share this one predicate, so a booked tax can never disagree
     * with the receipt that explains it. It is a tax proof, not a result: it never implies the
     * prior tracked value of the sold units.
     */
    static long acceptedTax(int itemId, long grossGp, long quantity, long receivedGp) {
        long tax = provableTax(itemId, grossGp, quantity);
        return tax > 0L && grossGp - receivedGp == tax ? tax : 0L;
    }
}
