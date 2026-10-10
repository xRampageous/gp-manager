package com.gpmanager;

import static com.gpmanager.Correction.*;

import lombok.*;

import java.util.*;

/**
 * Derived, read-only Market settlement context over durable schema-104 custody lifecycles.
 *
 * <p>It never books GP, never owns corrections and never becomes a second financial authority. For
 * a REALIZED lifecycle it explains the canonical settlement already booked; pending Execution Edge
 * is exposed separately and is never folded into a realized total.
 */
class MarketRows {
    /** Behavioural lifecycle state; several durable stages may map to one state. */
    enum Lifecycle {
        PENDING,
        PARTIALLY_EXECUTED,
        EXECUTED_UNSETTLED,
        PARTIALLY_REALIZED,
        REALIZED,
        CANCELLED_RETURNED,
        AMBIGUOUS,
        UNAVAILABLE
    }

    /** Coverage of the realized quantity: proven prior tracked value, partial, or unknown. */
    enum Coverage {
        FULLY_KNOWN,
        PARTIALLY_KNOWN,
        FULLY_UNKNOWN
    }

    /** One immutable Market presentation row; no booking behaviour. */
    @EqualsAndHashCode
    static class Row {
        String presentationId;
        long timestampEpochMillis;
        int slot;
        int itemId;
        String itemName;
        GeOffer.Side side;
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

        /**
         * Realized execution/gross GP attributable to the collected quantity; 0 until collected.
         */
        /** Settlement/collection observation time; 0 when no exact settlement receipt resolves. */
        long settlementAtEpochMillis;

        long realizedResultGp;
        boolean realizedResultCorrectionAware;

        /**
         * An explicit counted correction can supply a Result even when automatic basis is unknown.
         */
        boolean manualFinancialResult;

        /** Any applied correction (AUTO=false) on the booked settlement transaction. */
        boolean correctionApplied;

        /**
         * The lifecycle failed closed and was quarantined: its booked allocation was never observed
         * as collected cash, so presentation must never show it as Received.
         */
        boolean collectionAmbiguous;

        /**
         * Booked contribution state: a fully-unknown sale whose counted AUTO settlement contributes
         * exactly {@code -inferredGeTaxGp}. Derivation is from the booked transaction (counted +
         * AUTO + effective net equals the shared accepted tax), never from coverage plus an
         * inferred tax alone; a pre-change uncounted sale stays UNCOUNTED.
         */
        boolean knownCostOnly;

        /**
         * SELL: known-coverage quantity consumed by realized settlements; BUY: realized quantity.
         */
        /** SELL: known basis consumed; BUY: created known basis (the exact spend). */
        long trackedBasisConsumedGp;

        Coverage coverage;
        Lifecycle lifecycle;
        String settlementId;

        /** The offer's earlier settlement ids, space-separated (a sale collected in parts). */
        String earlierSettlements;

        /**
         * The session whose scope shows this row: the session holding its booked settlement,
         * otherwise the listing (origin) session.
         */
        String scopeSessionId;

        /** Display name of the listing session when the sale settled in another one; else empty. */
        String listedDuring;

        Row(
                GeOffer record,
                java.util.function.Function<String, Transaction> lookup,
                SessionLookup sessions) {
            this.presentationId = record.getOfferId();
            String originSessionId = record.getOriginSessionId();
            Session holder =
                    sessions == null || record.getSettlementId().isEmpty()
                            ? null
                            : sessions.holding(record.getSettlementId());
            this.scopeSessionId = holder == null ? originSessionId : holder.getId();
            Session origin =
                    holder == null
                                    || originSessionId.isEmpty()
                                    || originSessionId.equals(holder.getId())
                            ? null
                            : sessions.byId(originSessionId);
            this.listedDuring = origin == null ? "" : origin.getName();
            this.timestampEpochMillis = record.getPlacedAtEpochMillis();
            this.slot = record.slot;
            this.itemId = record.itemId;
            this.itemName = record.getItemName();
            this.side = record.getSide();
            this.offeredQty = record.getOfferedQty();
            this.filledQty = record.getFilledQty();
            boolean sell = record.getSide() == GeOffer.Side.SELL;
            boolean basisKnown = record.hasFrozenBasis() && !record.isUnbased();
            this.basisUnitPrice = basisKnown ? record.getBasisUnitPrice() : 0L;
            this.settledQty = record.getSettledQty();
            this.observedSettlementGp = record.settledCashGp;
            this.settlementAdjustmentGp =
                    sell && record.getSettledQty() > 0L
                            ? record.settledCashGp - record.getSettledExecutionGp()
                            : 0L;
            // Actual after-tax settled unit value, only when the exact division is proven; -1
            // means "not exactly derivable" and is never silently rounded for display.
            long receivedCash = SafeMath.abs(record.settledCashGp);
            // One shared accepted-tax predicate for booking and presentation: the per-fill tax
            // when every fill was observed at its own price, else the uniform rule.
            this.inferredGeTaxGp =
                    sell
                                    && record.fillTaxExact
                                    && record.getSettledTaxGp() > 0L
                                    && record.getSettledExecutionGp() - receivedCash
                                            == record.getSettledTaxGp()
                            ? record.getSettledTaxGp()
                            : sell
                                    ? GeTaxRule.acceptedTax(
                                            record.itemId,
                                            record.getSettledExecutionGp(),
                                            record.getSettledQty(),
                                            receivedCash)
                                    : 0L;
            this.receivedEachGp =
                    sell
                                    && record.getSettledQty() > 0L
                                    && receivedCash > 0L
                                    && receivedCash % record.getSettledQty() == 0L
                            ? receivedCash / record.getSettledQty()
                            : -1L;
            Transaction settlement =
                    lookup == null || record.getSettlementId().isEmpty()
                            ? null
                            : lookup.apply(record.getSettlementId());
            this.settlementAtEpochMillis =
                    settlement == null ? 0L : settlement.timestampEpochMillis;
            if (settlement != null) {
                // Correction-aware: consume the effective canonical truth of every booked
                // settlement; an offer
                // collected in parts has one per collect (owner 2026-10-09).
                boolean missing = false;
                for (String id :
                        (record.getSettlementId() + Phrases.orEmpty(record.earlierSettlements))
                                .split(" ")) {
                    Transaction part = id.isEmpty() ? null : lookup.apply(id);
                    // An earlier receipt compacted away: fall back to the booked accumulator.
                    missing |= part == null && !id.isEmpty();
                    if (part == null) continue;
                    this.realizedResultGp += Accounting.transaction(part).getNet();
                    this.manualFinancialResult |=
                            part.getCorrection() == REVENUE || part.getCorrection() == COST;
                    this.correctionApplied |= part.getCorrection() != AUTO;
                }
                if (missing) this.realizedResultGp = record.realizedResultGp;
                this.realizedResultCorrectionAware = !missing;
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
            this.trackedBasisConsumedGp =
                    settled <= 0L
                            ? 0L
                            : sell
                                    ? record.getConsumedTrackedBasisGp()
                                    : SafeMath.abs(record.settledCashGp);
            this.coverage =
                    settled <= 0L
                            ? Coverage.FULLY_UNKNOWN
                            : settled - knownConsumed <= 0L
                                    ? Coverage.FULLY_KNOWN
                                    : knownConsumed <= 0L
                                            ? Coverage.FULLY_UNKNOWN
                                            : Coverage.PARTIALLY_KNOWN;
            // KNOWN_COST_ONLY is a booked state, not merely coverage plus an inferred tax: a
            // pre-change uncounted sale keeps UNCOUNTED even when its custody evidence still
            // proves the historical tax, and a corrected sale is no longer automatic.
            this.knownCostOnly =
                    sell
                            && settled > 0L
                            && this.coverage == Coverage.FULLY_UNKNOWN
                            && settlement != null
                            && settlement.autoCounted()
                            && settlement.getCorrection() == AUTO
                            && this.inferredGeTaxGp > 0L
                            && this.realizedResultGp == -this.inferredGeTaxGp;
            this.lifecycle = lifecycleOf(record);
            this.collectionAmbiguous = record.collectionAmbiguous;
            this.settlementId = record.getSettlementId();
            this.earlierSettlements = Phrases.orEmpty(record.earlierSettlements);
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
    /** Session resolution for scope and the cross-Grind "Listed during" label. */
    interface SessionLookup {
        /** The session holding this booked transaction, or null when none is retained. */
        Session holding(String transactionId);

        Session byId(String sessionId);
    }

    static List<Row> rows(
            List<GeOffer> records,
            java.util.function.Function<String, Transaction> settlementLookup,
            SessionLookup sessions) {
        if (Phrases.empty(records)) return Collections.emptyList();
        var rows = new ArrayList<Row>(records.size());
        for (GeOffer record : records) {
            if (record != null && !record.getOfferId().isEmpty())
                rows.add(new Row(record, settlementLookup, sessions));
        }
        return Collections.unmodifiableList(rows);
    }

    /** Behavioural lifecycle for one durable record. Pending edge is never realized here. */
    static Lifecycle lifecycleOf(GeOffer record) {
        if (record == null) return Lifecycle.UNAVAILABLE;
        if (record.isUnbased()) return Lifecycle.UNAVAILABLE;
        if (record.getConfidence() == GeOffer.CustodyConfidence.AMBIGUOUS
                || record.quantityModified
                || record.collectionAmbiguous) {
            return Lifecycle.AMBIGUOUS;
        }
        if (record.getFilledQty() <= 0L) {
            if (record.isCancelled() || record.cleared) return Lifecycle.CANCELLED_RETURNED;
            return Lifecycle.PENDING;
        }
        if (record.getSettledQty() > 0L && record.getSettledQty() < record.getFilledQty()) {
            return Lifecycle.PARTIALLY_REALIZED;
        }
        if (record.getSettledQty() >= record.getFilledQty() && record.getSettledQty() > 0L)
            return Lifecycle.REALIZED;
        if (record.getFilledQty() >= record.getOfferedQty()) return Lifecycle.EXECUTED_UNSETTLED;
        return Lifecycle.PARTIALLY_EXECUTED;
    }
}
