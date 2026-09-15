package com.gpmanager.model;

/** Filterable item totals preserved when detailed transaction rows are compacted. */
public class RetainedItemContribution
{
    private int itemId;
    private String itemName;
    private long revenue;
    private long costs;
    /** Supply portion of costs, retained independently of the raw receipt. */
    private long suppliesCosts;
    /** Non-supply portion (Loss + Market) of costs. */
    private long otherCosts;
    /** Absent on item aggregates written before cost-kind retention. */
    private int costSplitVersion;
    private boolean costSplitComplete;

    public RetainedItemContribution()
    {
        // Gson
    }

    public RetainedItemContribution(int itemId, String itemName)
    {
        this.itemId = itemId;
        this.itemName = itemName == null ? "" : itemName;
        this.costSplitVersion = 1;
        this.costSplitComplete = true;
    }

    /** Sums another contribution for the same item; the split stays available only when both were. */
    public void absorb(RetainedItemContribution other)
    {
        if (other == null || other == this) return;
        revenue += other.revenue;
        costs += other.costs;
        suppliesCosts += other.suppliesCosts;
        otherCosts += other.otherCosts;
        boolean split = costSplitVersion >= 1 && costSplitComplete && other.costSplitVersion >= 1 && other.costSplitComplete;
        costSplitVersion = split ? Math.min(costSplitVersion, other.costSplitVersion) : 0;
        costSplitComplete = split;
    }

    public void addValueDelta(long valueDelta)
    {
        if (valueDelta > 0L)
        {
            revenue = safeAdd(revenue, valueDelta);
        }
        else if (valueDelta < 0L)
        {
            costs = safeAdd(costs, valueDelta == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(valueDelta));
        }
    }

    /** Retains item-attributed amounts after a transaction-level correction. */
    public void addProjectedValueDelta(long valueDelta, TransactionCorrection correction)
    {
        TransactionCorrection safe = correction == null ? TransactionCorrection.AUTO : correction;
        if (safe == TransactionCorrection.REVENUE)
        {
            revenue = safeAdd(revenue, absolute(valueDelta));
        }
        else if (safe == TransactionCorrection.COST)
        {
            costs = safeAdd(costs, absolute(valueDelta));
        }
        else if (safe == TransactionCorrection.AUTO)
        {
            addValueDelta(valueDelta);
        }
        if (valueDelta < 0L && safe != TransactionCorrection.IGNORE
            && safe != TransactionCorrection.TRANSFER)
        {
            costSplitComplete = false;
        }
    }

    /** Retains the correction-aware item and cost-kind projection for one flow. */
    public void addProjectedValueDelta(ProfitTransaction transaction, ItemFlow flow)
    {
        if (transaction == null || flow == null)
        {
            return;
        }
        AccountingProjection.TransactionAmounts amounts =
            AccountingProjection.flow(transaction, flow, null);
        if (!amounts.isAvailable())
        {
            costSplitComplete = false;
            return;
        }
        if (!amounts.isIncluded())
        {
            return;
        }

        revenue = safeAdd(revenue, amounts.getRevenue());
        costs = safeAdd(costs, amounts.getCosts());
        if (amounts.getCosts() <= 0L)
        {
            return;
        }

        CostKind kind = CostKind.of(transaction, flow);
        if (kind == CostKind.SUPPLIES)
        {
            suppliesCosts = safeAdd(suppliesCosts, amounts.getCosts());
        }
        else if (kind == CostKind.LOSS || kind == CostKind.MARKET)
        {
            otherCosts = safeAdd(otherCosts, amounts.getCosts());
        }
        else
        {
            costSplitComplete = false;
        }
    }

    public int getItemId() { return itemId; }
    public String getItemName() { return itemName == null ? "" : itemName; }
    public long getRevenue() { return revenue; }
    public long getCosts() { return costs; }
    public long getSuppliesCosts() { return suppliesCosts; }
    public long getOtherCosts() { return otherCosts; }
    public boolean isCostSplitComplete() { return costSplitVersion >= 1 && costSplitComplete; }

    public RetainedItemContribution copy()
    {
        RetainedItemContribution copy = new RetainedItemContribution(itemId, getItemName());
        copy.revenue = revenue;
        copy.costs = costs;
        copy.suppliesCosts = suppliesCosts;
        copy.otherCosts = otherCosts;
        copy.costSplitVersion = costSplitVersion;
        copy.costSplitComplete = costSplitComplete;
        return copy;
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

    private static long absolute(long value)
    {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }
}
