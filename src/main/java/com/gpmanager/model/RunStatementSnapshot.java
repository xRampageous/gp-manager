package com.gpmanager.model;

/**
 * Immutable, presentation-independent accounting statement for one run.
 * Left-on-ground data is deliberately unavailable until ground-item lifecycle
 * evidence can be retained durably.
 */
public final class RunStatementSnapshot
{
    private final String runId;
    private final String name;
    private final boolean legacy;
    private final String status;
    private final long activeDurationMillis;
    private final long lootGp;
    private final long suppliesGp;
    private final long consumableGp;
    private final long lossGp;
    private final boolean costSplitAvailable;
    private final long netGp;
    private final long gpPerHour;
    private final boolean accountingAvailable;
    private final String accountingStatus;
    private final int receiptCount;
    private final boolean receiptCountAvailable;
    private final boolean leftOnGroundAvailable;
    private final Long leftOnGroundGp;

    public RunStatementSnapshot(
        String runId,
        String name,
        boolean legacy,
        String status,
        long activeDurationMillis,
        long lootGp,
        long suppliesGp,
        long netGp,
        long gpPerHour,
        boolean accountingAvailable,
        String accountingStatus,
        int receiptCount,
        boolean receiptCountAvailable)
    {
        this(runId, name, legacy, status, activeDurationMillis, lootGp, suppliesGp,
            netGp, gpPerHour, accountingAvailable, accountingStatus, receiptCount,
            receiptCountAvailable, 0L, 0L, false);
    }

    public RunStatementSnapshot(
        String runId,
        String name,
        boolean legacy,
        String status,
        long activeDurationMillis,
        long lootGp,
        long suppliesGp,
        long netGp,
        long gpPerHour,
        boolean accountingAvailable,
        String accountingStatus,
        int receiptCount,
        boolean receiptCountAvailable,
        long consumableGp,
        long lossGp,
        boolean costSplitAvailable)
    {
        this.runId = safeText(runId);
        this.name = safeText(name);
        this.legacy = legacy;
        this.status = status == null ? "UNKNOWN" : status;
        this.activeDurationMillis = Math.max(0L, activeDurationMillis);
        this.lootGp = lootGp;
        this.suppliesGp = suppliesGp;
        this.consumableGp = consumableGp;
        this.lossGp = lossGp;
        this.costSplitAvailable = accountingAvailable && costSplitAvailable
            && safeAdd(consumableGp, lossGp) == suppliesGp;
        this.netGp = netGp;
        this.gpPerHour = gpPerHour;
        this.accountingAvailable = accountingAvailable;
        this.accountingStatus = accountingStatus == null
            ? (accountingAvailable ? "AVAILABLE" : "UNAVAILABLE")
            : accountingStatus;
        this.receiptCount = Math.max(0, receiptCount);
        this.receiptCountAvailable = receiptCountAvailable;
        this.leftOnGroundAvailable = false;
        this.leftOnGroundGp = null;
    }

    public static RunStatementSnapshot unavailable(String runId, String name)
    {
        return new RunStatementSnapshot(runId, name, false, "UNKNOWN", 0L,
            0L, 0L, 0L, 0L, false, "UNAVAILABLE", 0, false);
    }

    public String getRunId() { return runId; }
    public String getName() { return name; }
    public boolean isLegacy() { return legacy; }
    public String getStatus() { return status; }
    public long getActiveDurationMillis() { return activeDurationMillis; }
    public long getLootGp() { return lootGp; }
    public long getSuppliesGp() { return suppliesGp; }
    /** Cost total for the new split; kept separate from legacy getSuppliesGp(). */
    public long getConsumableGp() { return consumableGp; }
    /** Loss plus market costs; this complements consumables to total costs. */
    public long getLossGp() { return lossGp; }
    public boolean isCostSplitAvailable() { return costSplitAvailable; }
    public long getNetGp() { return netGp; }
    public long getGpPerHour() { return gpPerHour; }
    public boolean isAccountingAvailable() { return accountingAvailable; }
    public String getAccountingStatus() { return accountingStatus; }
    public int getReceiptCount() { return receiptCount; }
    public boolean isReceiptCountAvailable() { return receiptCountAvailable; }
    public boolean isRateAvailable() { return accountingAvailable && activeDurationMillis > 0L; }
    public boolean isLeftOnGroundAvailable() { return leftOnGroundAvailable; }
    public Long getLeftOnGroundGp() { return leftOnGroundGp; }

    private static String safeText(String value)
    {
        return value == null ? "" : value;
    }

    private static long safeAdd(long left, long right)
    {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ex) { return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE; }
    }
}
