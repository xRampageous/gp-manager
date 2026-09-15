package com.gpmanager.model;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * Shared item-eligibility projection for accounting read models and exports.
 * A filtered consumer must use these projected amounts instead of a parent
 * transaction's raw totals, because manual classifications apply only to the
 * flows that remain eligible.
 */
public final class AccountingProjection
{
    private AccountingProjection()
    {
    }

    public static TransactionAmounts transaction(
        ProfitTransaction transaction,
        BiPredicate<ProfitTransaction, ItemFlow> eligibility)
    {
        if (transaction == null)
        {
            return TransactionAmounts.unavailable();
        }
        if (transaction.getType() == TransactionType.TRANSFER
            || transaction.getCorrection() == TransactionCorrection.TRANSFER)
        {
            return TransactionAmounts.transfer();
        }
        if (!transaction.isCounted())
        {
            return TransactionAmounts.excluded();
        }

        List<ItemFlow> flows = transaction.getFlows();
        if (flows == null || flows.isEmpty())
        {
            if (eligibility != null)
            {
                return TransactionAmounts.unavailable();
            }
            return TransactionAmounts.included(
                transaction.getRevenue(), transaction.getCosts(), 0L, 0L);
        }

        long autoRevenue = 0L;
        long autoCosts = 0L;
        long gross = 0L;
        boolean included = false;
        for (ItemFlow flow : flows)
        {
            if (flow == null || (eligibility != null && !eligibility.test(transaction, flow)))
            {
                continue;
            }
            included = true;
            long value = flow.getValueDelta();
            if (value > 0L)
            {
                autoRevenue = safeAdd(autoRevenue, value);
            }
            else if (value < 0L)
            {
                autoCosts = safeAdd(autoCosts, absolute(value));
            }
            gross = safeAdd(gross, absolute(value));
        }
        if (!included)
        {
            return TransactionAmounts.excluded();
        }

        switch (transaction.getCorrection())
        {
            case REVENUE:
                return TransactionAmounts.included(gross, 0L, autoRevenue, autoCosts);
            case COST:
                return TransactionAmounts.included(0L, gross, autoRevenue, autoCosts);
            case IGNORE:
                return TransactionAmounts.excluded();
            case TRANSFER:
                return TransactionAmounts.transfer();
            case AUTO:
            default:
                if (eligibility == null)
                {
                    return TransactionAmounts.included(
                        transaction.getRevenue(), transaction.getCosts(), autoRevenue, autoCosts);
                }
                return TransactionAmounts.included(autoRevenue, autoCosts, autoRevenue, autoCosts);
        }
    }

    /** Returns the additive accounting contribution of a single item-flow row. */
    public static TransactionAmounts flow(
        ProfitTransaction transaction,
        ItemFlow flow,
        BiPredicate<ProfitTransaction, ItemFlow> eligibility)
    {
        if (transaction == null || flow == null)
        {
            return TransactionAmounts.unavailable();
        }
        if (transaction.getType() == TransactionType.TRANSFER
            || transaction.getCorrection() == TransactionCorrection.TRANSFER)
        {
            return TransactionAmounts.transfer();
        }
        if (!transaction.isCounted() || transaction.getCorrection() == TransactionCorrection.IGNORE
            || (eligibility != null && !eligibility.test(transaction, flow)))
        {
            return TransactionAmounts.excluded();
        }

        long value = flow.getValueDelta();
        long autoRevenue = value > 0L ? value : 0L;
        long autoCosts = value < 0L ? absolute(value) : 0L;
        switch (transaction.getCorrection())
        {
            case REVENUE:
                return TransactionAmounts.included(absolute(value), 0L, autoRevenue, autoCosts);
            case COST:
                return TransactionAmounts.included(0L, absolute(value), autoRevenue, autoCosts);
            case IGNORE:
                return TransactionAmounts.excluded();
            case TRANSFER:
                return TransactionAmounts.transfer();
            case AUTO:
            default:
                return TransactionAmounts.included(autoRevenue, autoCosts, autoRevenue, autoCosts);
        }
    }

    private static long absolute(long value)
    {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private static long safeAdd(long left, long right)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException ex)
        {
            return Long.MAX_VALUE;
        }
    }

    public static final class TransactionAmounts
    {
        private final boolean available;
        private final boolean included;
        private final boolean transfer;
        private final long revenue;
        private final long costs;
        private final long automaticRevenue;
        private final long automaticCosts;

        private TransactionAmounts(boolean available, boolean included, boolean transfer,
            long revenue, long costs, long automaticRevenue, long automaticCosts)
        {
            this.available = available;
            this.included = included;
            this.transfer = transfer;
            this.revenue = revenue;
            this.costs = costs;
            this.automaticRevenue = automaticRevenue;
            this.automaticCosts = automaticCosts;
        }

        private static TransactionAmounts included(long revenue, long costs,
            long automaticRevenue, long automaticCosts)
        {
            return new TransactionAmounts(true, true, false, revenue, costs,
                automaticRevenue, automaticCosts);
        }

        private static TransactionAmounts excluded()
        {
            return new TransactionAmounts(true, false, false, 0L, 0L, 0L, 0L);
        }

        private static TransactionAmounts transfer()
        {
            return new TransactionAmounts(true, false, true, 0L, 0L, 0L, 0L);
        }

        private static TransactionAmounts unavailable()
        {
            return new TransactionAmounts(false, false, false, 0L, 0L, 0L, 0L);
        }

        public boolean isAvailable() { return available; }
        public boolean isIncluded() { return included; }
        public boolean isTransfer() { return transfer; }
        public long getRevenue() { return revenue; }
        public long getCosts() { return costs; }
        public long getNet() { return safeSubtract(revenue, costs); }
        public long getAutomaticRevenue() { return automaticRevenue; }
        public long getAutomaticCosts() { return automaticCosts; }
        public long getAutomaticNet() { return safeSubtract(automaticRevenue, automaticCosts); }

        private static long safeSubtract(long left, long right)
        {
            try
            {
                return Math.subtractExact(left, right);
            }
            catch (ArithmeticException ex)
            {
                return right < 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
            }
        }
    }
}
