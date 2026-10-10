package com.gpmanager;

import static com.gpmanager.SafeMath.*;

import com.gpmanager.ActionReader.PartialUse;

import lombok.*;

import java.util.List;

/** Shared correction-aware projection for accounting read models and exports. */
class Accounting {
    static Amounts transaction(Transaction transaction) {
        if (transaction == null) return Amounts.unavailable();
        return transaction.memo(8, () -> amounts(transaction));
    }

    static Amounts amounts(Transaction transaction) {
        if (transaction.getType() == TransactionType.TRANSFER
                || transaction.getCorrection() == Correction.TRANSFER) {
            return Amounts.transfer();
        }
        if (!transaction.isCounted()) return Amounts.excluded();
        List<Flow> flows = transaction.getFlows();
        if (Phrases.empty(flows)) {
            return Amounts.included(transaction.getRevenue(), transaction.getCosts());
        }
        long gross = 0L;
        boolean included = false;
        for (Flow flow : flows) {
            if (flow == null) continue;
            included = true;
            gross = safeAdd(gross, abs(flow.valueDelta));
        }
        if (!included) return Amounts.excluded();
        switch (transaction.getCorrection()) {
            case REVENUE:
                return Amounts.included(gross, 0L);
            case COST:
                return Amounts.included(0L, gross);
            case IGNORE:
                return Amounts.excluded();
            case TRANSFER:
                return Amounts.transfer();
            case AUTO:
            default:
                long[] steps = cachedSteps(transaction);
                long step = 0L;
                for (int i = 0; i < flows.size(); i++) {
                    if (flows.get(i) != null && flows.get(i).valueDelta > 0L)
                        step = safeAdd(step, steps[i]);
                }
                step = Math.min(step, Math.min(transaction.getRevenue(), transaction.getCosts()));
                return Amounts.included(
                        safeSubtract(transaction.getRevenue(), step),
                        safeSubtract(transaction.getCosts(), step));
        }
    }

    /**
     * Owner 2026-09-28: a sip or bite steps an item down ((4) to (3), pie to half pie). The part
     * left is no gain and the whole potion no cost; only the step is, as the Ledger pairs it. Per
     * flow, the value both legs of each such pair give up (0 elsewhere, and always 0 under a
     * correction).
     */
    static long[] cachedSteps(Transaction transaction) {
        return transaction.memo(6, () -> steps(transaction));
    }

    static long[] steps(Transaction transaction) {
        List<Flow> flows = transaction.getFlows();
        long[] steps = new long[flows.size()];
        for (PartialUse pair : consumePairs(transaction)) {
            long left = flows.get(pair.remainderIndex).valueDelta;
            if (left > 0L && safeAdd(flows.get(pair.lossIndex).valueDelta, left) <= 0L) {
                steps[pair.lossIndex] = left;
                steps[pair.remainderIndex] = left;
            }
        }
        return steps;
    }

    /** One consume gate for accounting and item presentation; transfers/decants keep raw quotes. */
    static List<PartialUse> consumePairs(Transaction transaction) {
        List<Flow> flows = transaction.getFlows();
        ActionKind action = transaction.getActionKind();
        // Cheapest facts first: this runs for every flow of every receipt on each refresh (release
        // pass).
        if (!transaction.isCounted()
                || transaction.getCorrection() != Correction.AUTO
                || transaction.getType() != TransactionType.CONSUMPTION
                        && transaction.getType() != TransactionType.PK_SUPPLY_COST
                || action != null
                        && action != ActionKind.DRINK
                        && action != ActionKind.EAT
                        && action != ActionKind.SUPPLIES
                || ActionReader.isDecant(flows)) return java.util.Collections.emptyList();
        var pairs = new java.util.ArrayList<PartialUse>();
        for (PartialUse pair : ActionReader.partialUses(flows)) {
            if (flows.get(pair.lossIndex).getPriceSource()
                    == flows.get(pair.remainderIndex).getPriceSource()) pairs.add(pair);
        }
        return pairs;
    }

    /** Returns the additive accounting contribution of a single item-flow row. */
    static Amounts flow(Transaction transaction, Flow flow) {
        if (transaction == null || flow == null) return Amounts.unavailable();
        if (transaction.getType() == TransactionType.TRANSFER
                || transaction.getCorrection() == Correction.TRANSFER) {
            return Amounts.transfer();
        }
        if (!transaction.isCounted() || transaction.getCorrection() == Correction.IGNORE) {
            return Amounts.excluded();
        }
        long value = flow.valueDelta;
        long autoRevenue = value > 0L ? value : 0L;
        long autoCosts = value < 0L ? abs(value) : 0L;
        switch (transaction.getCorrection()) {
            case REVENUE:
                return Amounts.included(abs(value), 0L);
            case COST:
                return Amounts.included(0L, abs(value));
            case IGNORE:
                return Amounts.excluded();
            case TRANSFER:
                return Amounts.transfer();
            case AUTO:
            default:
                List<Flow> flows = transaction.getFlows();
                long step = 0L;
                for (int i = 0; i < flows.size(); i++) {
                    if (flows.get(i) == flow) step = cachedSteps(transaction)[i];
                }
                return Amounts.included(
                        safeSubtract(autoRevenue, value > 0L ? step : 0L),
                        safeSubtract(autoCosts, value < 0L ? step : 0L));
        }
    }

    /** Correction-aware supplies/other split shared by every retained money fold. */
    static CostSplit costSplit(Transaction transaction) {
        return transaction.memo(9, () -> split(transaction));
    }

    static CostSplit split(Transaction transaction) {
        Amounts total = transaction(transaction);
        if (!total.available) return new CostSplit(0L, 0L, false);
        if (!total.included || total.costs <= 0L) return new CostSplit(0L, 0L, true);
        if (transaction.getFlows().isEmpty()) return new CostSplit(0L, 0L, false);
        long supplies = 0L;
        long other = 0L;
        for (Flow flow : transaction.getFlows()) {
            if (flow == null) continue;
            Amounts amounts = flow(transaction, flow);
            if (!amounts.available) return new CostSplit(0L, 0L, false);
            if (!amounts.included || amounts.costs <= 0L) continue;
            switch (CostKind.of(transaction, flow)) {
                case SUPPLIES:
                    supplies = safeAdd(supplies, amounts.costs);
                    break;
                case LOSS:
                case MARKET:
                    other = safeAdd(other, amounts.costs);
                    break;
                default:
                    return new CostSplit(0L, 0L, false);
            }
        }
        return new CostSplit(supplies, other, safeAdd(supplies, other) == total.costs);
    }

    /**
     * The correction-aware fold of the counted Market-context population. Market membership is the
     * authoritative typed predicate {@code automaticType == TRADE}; corrections change effective
     * amounts but never membership. Whole transactions are folded here so callers can remove their
     * gross legs from ordinary categories without re-deriving economics.
     */
    static MarketFold marketFold(List<Transaction> transactions) {
        long revenue = 0L;
        long costs = 0L;
        long supplies = 0L;
        long other = 0L;
        boolean splitAvailable = true;
        int counted = 0;
        if (transactions == null) return new MarketFold(0L, 0L, 0L, 0L, true, 0);
        for (Transaction transaction : transactions) {
            if (transaction == null || transaction.autoType() != TransactionType.TRADE) continue;
            Amounts amounts = transaction(transaction);
            if (!amounts.included) continue;
            counted++;
            revenue = safeAdd(revenue, amounts.revenue);
            costs = safeAdd(costs, amounts.costs);
            CostSplit split = costSplit(transaction);
            if (!split.available) {
                splitAvailable = false;
                continue;
            }
            supplies = safeAdd(supplies, split.supplies);
            other = safeAdd(other, split.other);
        }
        return new MarketFold(
                revenue,
                costs,
                splitAvailable ? supplies : 0L,
                splitAvailable ? other : 0L,
                splitAvailable,
                counted);
    }

    @AllArgsConstructor
    static class MarketFold {
        long revenue;
        long costs;
        long supplies;
        long other;
        boolean splitAvailable;

        /** Counted Market-context transactions behind this fold. */
        int transactions;

        long getNet() {
            return safeSubtract(revenue, costs);
        }
    }

    @AllArgsConstructor
    static class Amounts {
        boolean available;
        boolean included;
        boolean transfer;
        long revenue;
        long costs;

        static Amounts included(long revenue, long costs) {
            return new Amounts(true, true, false, revenue, costs);
        }

        static Amounts excluded() {
            return new Amounts(true, false, false, 0L, 0L);
        }

        static Amounts transfer() {
            return new Amounts(true, false, true, 0L, 0L);
        }

        static Amounts unavailable() {
            return new Amounts(false, false, false, 0L, 0L);
        }

        long getNet() {
            return safeSubtract(revenue, costs);
        }
    }

    @AllArgsConstructor
    static class CostSplit {
        long supplies;
        long other;
        boolean available;
    }
}
