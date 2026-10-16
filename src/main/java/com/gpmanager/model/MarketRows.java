package com.gpmanager;

import static com.gpmanager.Correction.*;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.GeOffer.Side.*;

import com.gpmanager.MarketRows.Row;
import com.gpmanager.Semantics.*;
import java.util.*;
import lombok.*;

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
        int itemId;
        String itemName;
        GeOffer.Side side;
        long offeredQty;
        long filledQty;

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
                Engine sessions) {
            presentationId = record.offerId;
            String originSessionId = record.originSessionId;
            Session holder =
                    sessions == null || record.settlementId.isEmpty()
                            ? null
                            : sessions.sessionHolding(record.settlementId);
            scopeSessionId = holder == null ? originSessionId : holder.getId();
            Session origin =
                    holder == null
                                    || originSessionId.isEmpty()
                                    || originSessionId.equals(holder.getId())
                            ? null
                            : sessions.sessionById(originSessionId);
            listedDuring = origin == null ? "" : origin.getName();
            timestampEpochMillis = record.placedAtEpochMillis;
            itemId = record.itemId;
            itemName = record.itemName;
            side = record.side;
            offeredQty = record.offeredQty;
            filledQty = record.filledQty;
            boolean sell = record.side == GeOffer.Side.SELL;
            long settled = record.settledQty;
            settledQty = settled;
            observedSettlementGp = record.settledCashGp;
            settlementAdjustmentGp =
                    sell && settled > 0L ? record.settledCashGp - record.settledExecutionGp : 0L;
            // Actual after-tax settled unit value, only when the exact division is proven; -1
            // means "not exactly derivable" and is never silently rounded for display.
            long receivedCash = SafeMath.abs(record.settledCashGp);
            // One shared accepted-tax predicate for booking and presentation: the per-fill tax
            // when every fill was observed at its own price, else the uniform rule.
            inferredGeTaxGp =
                    sell
                                    && record.fillTaxExact
                                    && record.settledTaxGp > 0L
                                    && record.settledExecutionGp - receivedCash
                                            == record.settledTaxGp
                            ? record.settledTaxGp
                            : sell
                                    ? GeTaxRule.acceptedTax(
                                            record.itemId,
                                            record.settledExecutionGp,
                                            settled,
                                            receivedCash)
                                    : 0L;
            receivedEachGp =
                    sell && settled > 0L && receivedCash > 0L && receivedCash % settled == 0L
                            ? receivedCash / settled
                            : -1L;
            Transaction settlement =
                    lookup == null || record.settlementId.isEmpty()
                            ? null
                            : lookup.apply(record.settlementId);
            settlementAtEpochMillis = settlement == null ? 0L : settlement.timestampEpochMillis;
            if (settlement != null) {
                // Correction-aware: consume the effective canonical truth of every booked
                // settlement; an offer
                // collected in parts has one per collect (owner 2026-10-09).
                boolean missing = false;
                for (String id :
                        (record.settlementId + Phrases.orEmpty(record.earlierSettlements))
                                .split(" ")) {
                    Transaction part = id.isEmpty() ? null : lookup.apply(id);
                    // An earlier receipt compacted away: fall back to the booked accumulator.
                    missing |= part == null && !id.isEmpty();
                    if (part == null) continue;
                    realizedResultGp += Accounting.transaction(part).getNet();
                    manualFinancialResult |=
                            part.getCorrection() == REVENUE || part.getCorrection() == COST;
                }
                if (missing) realizedResultGp = record.realizedResultGp;
                realizedResultCorrectionAware = !missing;
            } else {
                // The canonical receipt is no longer inspectable; the booked accumulator is
                // exposed but explicitly marked as not correction-recomputed.
                realizedResultGp = record.realizedResultGp;
            }
            long knownConsumed = sell ? Math.min(record.consumedTrackedQty, settled) : settled;
            trackedBasisConsumedGp =
                    settled <= 0L
                            ? 0L
                            : sell
                                    ? record.consumedTrackedBasisGp
                                    : SafeMath.abs(record.settledCashGp);
            coverage =
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
            knownCostOnly =
                    sell
                            && settled > 0L
                            && coverage == Coverage.FULLY_UNKNOWN
                            && settlement != null
                            && settlement.autoCounted()
                            && settlement.getCorrection() == AUTO
                            && inferredGeTaxGp > 0L
                            && realizedResultGp == -inferredGeTaxGp;
            lifecycle = lifecycleOf(record);
            collectionAmbiguous = record.collectionAmbiguous;
            settlementId = record.settlementId;
            earlierSettlements = Phrases.orEmpty(record.earlierSettlements);
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
    static List<Row> rows(
            List<GeOffer> records,
            java.util.function.Function<String, Transaction> settlementLookup,
            Engine sessions) {
        if (Phrases.empty(records)) return Collections.emptyList();
        var rows = new ArrayList<Row>(records.size());
        for (GeOffer record : records) {
            if (record != null && !record.offerId.isEmpty())
                rows.add(new Row(record, settlementLookup, sessions));
        }
        return Collections.unmodifiableList(rows);
    }

    /** Behavioural lifecycle for one durable record. Pending edge is never realized here. */
    static Lifecycle lifecycleOf(GeOffer record) {
        if (record == null) return Lifecycle.UNAVAILABLE;
        if (record.isUnbased()) return Lifecycle.UNAVAILABLE;
        if (record.confidence == GeOffer.CustodyConfidence.AMBIGUOUS
                || record.quantityModified
                || record.collectionAmbiguous) {
            return Lifecycle.AMBIGUOUS;
        }
        if (record.filledQty <= 0L) {
            if (record.isCancelled() || record.cleared) return Lifecycle.CANCELLED_RETURNED;
            return Lifecycle.PENDING;
        }
        if (record.settledQty > 0L && record.settledQty < record.filledQty) {
            return Lifecycle.PARTIALLY_REALIZED;
        }
        if (record.settledQty >= record.filledQty && record.settledQty > 0L)
            return Lifecycle.REALIZED;
        if (record.filledQty >= record.offeredQty) return Lifecycle.EXECUTED_UNSETTLED;
        return Lifecycle.PARTIALLY_EXECUTED;
    }
}

/**
 * The Ledger's Market wording: one source for rows, receipts and the short GE facts, so the page
 * and the read-model tests never drift apart.
 */
class MarketText {
    /** True when a Market group has proven settled quantity and an observed settlement amount. */
    static boolean marketSettled(Group group, Row market) {
        return group.market
                && market != null
                && market.isRealizedIncluded()
                && market.settledQty > 0L
                && market.observedSettlementGp != 0L;
    }

    /** The Market row's primary signed value: observed settlement, else the canonical result. */
    static long marketPrimary(LedgerData data, Group group) {
        Row market = data.marketRow(group.marketPresentationId);
        if (!marketSettled(group, market)) return group.value;
        boolean sell = "SELL".equals(market.side.name());
        return sell
                ? Math.abs(market.observedSettlementGp)
                : -Math.abs(market.observedSettlementGp);
    }

    /** The Market row's secondary line: explicit side + Result, or the lifecycle context. */
    static String marketLead(LedgerData data, Group group) {
        if (!group.market) return groupLead(group);
        Row market = data.marketRow(group.marketPresentationId);
        if (marketSettled(group, market)) {
            return ("SELL".equals(market.side.name()) ? "Sold" : "Bought")
                    + " \u00b7 Result "
                    + (group.coverage == Semantics.Coverage.COMPLETE
                            ? signed(group.value)
                            : "\u2014");
        }
        if (group.coverage != Semantics.Coverage.COMPLETE) return group.contextLine;
        return "Result " + signed(group.value);
    }

    static boolean receiptRealized(Receipt receipt) {
        Row market = receipt.marketSettlement;
        return market != null && market.isRealizedIncluded() && market.settledQty > 0L;
    }

    /** Signed observed settlement cash for the collected quantity (SELL positive, BUY negative). */
    static long receiptPrimary(Receipt receipt) {
        Row market = receipt.marketSettlement;
        if (!receiptRealized(receipt)) return 0L;
        return market.side == SELL
                ? Math.abs(market.observedSettlementGp)
                : -Math.abs(market.observedSettlementGp);
    }

    static String receiptTitle(Receipt receipt) {
        Row market = receipt.marketSettlement;
        if (receiptRealized(receipt) && market.settledQty > 1L) {
            return receipt.itemName + "  " + times(market.settledQty);
        }
        return receipt.itemName;
    }

    /**
     * Sold/Bought with the canonical Result as secondary information and a settlement-relative age.
     */
    static String receiptSub(Receipt receipt, long capturedAt) {
        Row market = receipt.marketSettlement;
        String sub;
        if (receiptRealized(receipt)) {
            boolean resultAvailable =
                    market.realizedResultCorrectionAware
                            && (market.side != SELL
                                    || market.coverage != MarketRows.Coverage.FULLY_UNKNOWN
                                    || market.manualFinancialResult);
            String result =
                    resultAvailable
                            ? "Result " + exactSigned(market.realizedResultGp) + " gp"
                            : "Result \u2014";
            sub = (market.side == SELL ? "Sold" : "Bought") + " \u00b7 " + result;
        } else {
            sub = market == null ? receipt.verb : orderStateText(market);
        }
        return sub + " \u00b7 " + age(SafeMath.nonNeg(capturedAt - receipt.at));
    }

    /**
     * One word for where an offer stands: Offering / Buying while it is up with nothing filled,
     * Pending while part-filled, Sold / Bought once the exchange completes it, Canceled when
     * returned.
     */
    static String stateWord(Row market) {
        if (market == null) return "Pending";
        boolean sell = market.side == SELL;
        switch (market.lifecycle) {
            case CANCELLED_RETURNED:
                return "Canceled";
            case AMBIGUOUS:
                return "Review";
            case EXECUTED_UNSETTLED:
            case REALIZED:
                return sell ? "Sold" : "Bought";
            case PENDING:
                return sell ? "Selling" : "Buying";
            default:
                return "Pending";
        }
    }

    /**
     * The exact human Market lines ("label|value") for one lifecycle row; the renderer and the
     * read-model tests consume this one source. Coverage-aware: a sale whose prior tracked value is
     * unknown shows liquidation cash with an unavailable Result, never a fake zero basis. Only a
     * booked, uncorrected PROVEN tax earns a main GE tax row; exempt, unproven and pre-change sales
     * and fully known results never show one.
     */
    static List<String> humanLines(Row market, boolean corrected) {
        var lines = new ArrayList<String>();
        String fillState = fillStateText(market);
        if (!fillState.isEmpty()) lines.add(fillState);
        if (!market.listedDuring.isEmpty()) {
            // Cross-Grind: booked where it was collected; the listing session is named, never
            // rewritten.
            lines.add("Listed during|" + market.listedDuring);
        }
        boolean sell = market.side == SELL;
        if (!market.isRealizedIncluded() || market.settledQty <= 0L) {
            if (sell && market.collectionAmbiguous && market.settledQty > 0L) {
                // D1: a quarantined settlement's cash was never observed; Received is never shown
                // as if the player had actually collected it.
                lines.add(msg("received-proven"));
            }
            return lines;
        }
        lines.add(
                (sell ? "Received" : "Spent")
                        + "|"
                        + exact(Math.abs(market.observedSettlementGp))
                        + " gp");
        boolean unknown = sell && market.coverage == MarketRows.Coverage.FULLY_UNKNOWN;
        if (sell) {
            if (market.receivedEachGp > 0L) {
                // The actual after-tax settled unit value; shown only when exactly divisible.
                lines.add("Received each|" + exact(market.receivedEachGp) + " gp");
            }
            lines.add(
                    msg("previously-counted")
                            + (unknown ? "Unknown" : exact(market.trackedBasisConsumedGp) + " gp"));
            lines.add(
                    "Result|"
                            + (unknown && !market.manualFinancialResult
                                    ? "\u2014"
                                    : market.realizedResultCorrectionAware
                                            ? exactSigned(market.realizedResultGp)
                                                    + " gp"
                                                    + (market.coverage
                                                                    == MarketRows.Coverage
                                                                            .PARTIALLY_KNOWN
                                                            ? " \u00b7 Partial"
                                                            : "")
                                                    + (corrected ? msg("corrected") : "")
                                            : "\u2014"));
            if (market.knownCostOnly) {
                // Only the BOOKED state with an AUTO correction earns this row: the tax is a real
                // market cost here. Uncounted/pre-change, corrected and fully known sales never
                // show it (a full Result already has the tax inside Received).
                lines.add("GE tax|" + exactSigned(-market.inferredGeTaxGp) + " gp");
            }
        } else {
            lines.add("Tracked value|" + exact(market.trackedBasisConsumedGp) + " gp");
            lines.add(
                    "Result|"
                            + (market.realizedResultCorrectionAware
                                    ? exactSigned(market.realizedResultGp)
                                            + " gp"
                                            + (corrected ? msg("corrected") : "")
                                    : "0 gp"));
        }
        if (market.settlementAdjustmentGp != 0L && market.inferredGeTaxGp <= 0L) {
            // An unexplained gap is the receipt's own answer and must stay visible.
            lines.add("Adjustment|" + exactSigned(market.settlementAdjustmentGp) + " gp");
        }
        return lines;
    }

    /** Compact fill/collected state; omitted when offered, filled and collected agree. */
    static String fillStateText(Row market) {
        long offered = market.offeredQty;
        long filled = market.filledQty;
        long collected = market.settledQty;
        if (offered <= 0L || (filled == collected && collected == offered)) return "";
        if (filled == collected) return "Filled " + exact(collected) + " / " + exact(offered);
        return "Filled "
                + exact(filled)
                + " / "
                + exact(offered)
                + " \u00b7 collected "
                + exact(collected);
    }

    /** Order-state wording that never mislabels placement time as settlement time. */
    static String orderStateText(Row market) {
        boolean sell = market.side == SELL;
        String side = sell ? "Sell order" : "Buy order";
        switch (market.lifecycle) {
            case CANCELLED_RETURNED:
                return msg("returned-canceled");
            case AMBIGUOUS:
                return "Needs review";
            case UNAVAILABLE:
                return "Unavailable";
            case EXECUTED_UNSETTLED:
                return (sell ? "Sold " : "Bought ")
                        + exact(market.filledQty)
                        + "/"
                        + exact(market.offeredQty)
                        + msg("awaiting-collection");
            case PARTIALLY_EXECUTED:
                return (sell ? "Part sold " : "Part bought ")
                        + exact(market.filledQty)
                        + "/"
                        + exact(market.offeredQty)
                        + " \u00b7 Pending";
            default:
                return stateWord(market);
        }
    }

    static String orderWhy(Row market) {
        switch (market.lifecycle) {
            case CANCELLED_RETURNED:
                return msg("canceled-returned");
            case AMBIGUOUS:
                return msg("lifecycle-could-settled");
            case EXECUTED_UNSETTLED:
                return msg("filled-quantity");
            default:
                return msg("collected-settlement");
        }
    }

    static String groupLead(Group group) {
        if (group.market) return group.contextLine;
        if (!group.usedBy.isEmpty()) return "Used by " + group.usedBy;
        return group.actionLabel;
    }
}
