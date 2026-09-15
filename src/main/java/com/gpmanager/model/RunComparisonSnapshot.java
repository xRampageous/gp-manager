package com.gpmanager.model;

/**
 * Immutable signed comparison of two run statements. Deltas are right minus
 * left and are all unavailable together if accounting, a time rate, or safe
 * subtraction is unavailable.
 */
public final class RunComparisonSnapshot
{
    private final RunStatementSnapshot left;
    private final RunStatementSnapshot right;
    private final boolean available;
    private final String status;
    private final Long lootDeltaGp;
    private final Long suppliesDeltaGp;
    private final Long netDeltaGp;
    private final Long gpPerHourDelta;
    private final boolean costSplitAvailable;
    private final Long consumableDeltaGp;
    private final Long lossDeltaGp;

    public RunComparisonSnapshot(RunStatementSnapshot left, RunStatementSnapshot right)
    {
        this.left = left == null ? RunStatementSnapshot.unavailable("", "") : left;
        this.right = right == null ? RunStatementSnapshot.unavailable("", "") : right;

        Long consumable = null;
        Long loss = null;
        boolean splitAvailable = this.left.isAccountingAvailable()
            && this.right.isAccountingAvailable()
            && this.left.isCostSplitAvailable() && this.right.isCostSplitAvailable();
        if (splitAvailable)
        {
            consumable = subtractSafely(this.right.getConsumableGp(), this.left.getConsumableGp());
            loss = subtractSafely(this.right.getLossGp(), this.left.getLossGp());
            splitAvailable = consumable != null && loss != null;
        }
        this.costSplitAvailable = splitAvailable;
        this.consumableDeltaGp = splitAvailable ? consumable : null;
        this.lossDeltaGp = splitAvailable ? loss : null;

        if (!this.left.isAccountingAvailable() || !this.right.isAccountingAvailable())
        {
            this.available = false;
            this.status = "ACCOUNTING_UNAVAILABLE";
            this.lootDeltaGp = null;
            this.suppliesDeltaGp = null;
            this.netDeltaGp = null;
            this.gpPerHourDelta = null;
            return;
        }

        if (!this.left.isRateAvailable() || !this.right.isRateAvailable())
        {
            this.available = false;
            this.status = "RATE_UNAVAILABLE";
            this.lootDeltaGp = null;
            this.suppliesDeltaGp = null;
            this.netDeltaGp = null;
            this.gpPerHourDelta = null;
            return;
        }

        Long loot = subtractSafely(this.right.getLootGp(), this.left.getLootGp());
        Long supplies = subtractSafely(this.right.getSuppliesGp(), this.left.getSuppliesGp());
        Long net = subtractSafely(this.right.getNetGp(), this.left.getNetGp());
        Long rate = subtractSafely(this.right.getGpPerHour(), this.left.getGpPerHour());
        if (loot == null || supplies == null || net == null || rate == null)
        {
            this.available = false;
            this.status = "DELTA_OVERFLOW";
            this.lootDeltaGp = null;
            this.suppliesDeltaGp = null;
            this.netDeltaGp = null;
            this.gpPerHourDelta = null;
            return;
        }

        this.available = true;
        this.status = "AVAILABLE";
        this.lootDeltaGp = loot;
        this.suppliesDeltaGp = supplies;
        this.netDeltaGp = net;
        this.gpPerHourDelta = rate;
    }

    public RunStatementSnapshot getLeft() { return left; }
    public RunStatementSnapshot getRight() { return right; }
    public boolean isAvailable() { return available; }
    public String getStatus() { return status; }
    public Long getLootDeltaGp() { return lootDeltaGp; }
    public Long getSuppliesDeltaGp() { return suppliesDeltaGp; }
    public Long getNetDeltaGp() { return netDeltaGp; }
    public Long getGpPerHourDelta() { return gpPerHourDelta; }
    public boolean isCostSplitAvailable() { return costSplitAvailable; }
    public Long getConsumableDeltaGp() { return consumableDeltaGp; }
    public Long getLossDeltaGp() { return lossDeltaGp; }

    private static Long subtractSafely(long right, long left)
    {
        try
        {
            return Math.subtractExact(right, left);
        }
        catch (ArithmeticException overflow)
        {
            return null;
        }
    }
}
