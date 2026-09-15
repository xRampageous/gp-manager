package com.gpmanager.model;

public class SessionMetrics
{
    private final String sessionName;
    private final String activityHint;
    private final boolean paused;
    private final long elapsedMillis;
    private final long revenue;
    private final long costs;
    private final long net;
    private final long profitPerHour;
    private final long rollingProfitPerHour;
    private final int transactionCount;
    private final int transferCount;
    private final int actionCount;
    private final long profitPerAction;
    private final long costPerAction;
    private final boolean accountingProjectionAvailable;
    private final boolean transactionCountAvailable;
    private final boolean rollingRateAvailable;
    private final boolean actionCountAvailable;
    private final String accountingProjectionStatus;
    private final long suppliesCosts;
    private final long otherCosts;
    private final boolean costSplitAvailable;

    public SessionMetrics(
        String sessionName,
        String activityHint,
        boolean paused,
        long elapsedMillis,
        long revenue,
        long costs,
        long net,
        long profitPerHour,
        long rollingProfitPerHour,
        int transactionCount,
        int transferCount,
        int actionCount)
    {
        this(sessionName, activityHint, paused, elapsedMillis, revenue, costs, net,
            profitPerHour, rollingProfitPerHour, transactionCount, transferCount,
            actionCount, true, true, true, true, "AVAILABLE", 0L, 0L, false);
    }

    public SessionMetrics(
        String sessionName,
        String activityHint,
        boolean paused,
        long elapsedMillis,
        long revenue,
        long costs,
        long net,
        long profitPerHour,
        long rollingProfitPerHour,
        int transactionCount,
        int transferCount,
        int actionCount,
        boolean accountingProjectionAvailable,
        boolean transactionCountAvailable,
        boolean rollingRateAvailable,
        boolean actionCountAvailable,
        String accountingProjectionStatus)
    {
        this(sessionName, activityHint, paused, elapsedMillis, revenue, costs, net,
            profitPerHour, rollingProfitPerHour, transactionCount, transferCount,
            actionCount, accountingProjectionAvailable, transactionCountAvailable,
            rollingRateAvailable, actionCountAvailable, accountingProjectionStatus,
            0L, 0L, false);
    }

    public SessionMetrics(
        String sessionName,
        String activityHint,
        boolean paused,
        long elapsedMillis,
        long revenue,
        long costs,
        long net,
        long profitPerHour,
        long rollingProfitPerHour,
        int transactionCount,
        int transferCount,
        int actionCount,
        boolean accountingProjectionAvailable,
        boolean transactionCountAvailable,
        boolean rollingRateAvailable,
        boolean actionCountAvailable,
        String accountingProjectionStatus,
        long suppliesCosts,
        long otherCosts,
        boolean costSplitAvailable)
    {
        this.sessionName = sessionName;
        this.activityHint = activityHint;
        this.paused = paused;
        this.elapsedMillis = elapsedMillis;
        this.revenue = revenue;
        this.costs = costs;
        this.net = net;
        this.profitPerHour = profitPerHour;
        this.rollingProfitPerHour = rollingProfitPerHour;
        this.transactionCount = transactionCount;
        this.transferCount = transferCount;
        this.actionCount = actionCount;
        this.profitPerAction = actionCount <= 0 ? 0L : Math.round(net / (double) actionCount);
        this.costPerAction = actionCount <= 0 ? 0L : Math.round(costs / (double) actionCount);
        this.accountingProjectionAvailable = accountingProjectionAvailable;
        this.transactionCountAvailable = transactionCountAvailable;
        this.rollingRateAvailable = rollingRateAvailable;
        this.actionCountAvailable = actionCountAvailable;
        this.accountingProjectionStatus = accountingProjectionStatus == null
            ? "UNAVAILABLE" : accountingProjectionStatus;
        this.suppliesCosts = suppliesCosts;
        this.otherCosts = otherCosts;
        this.costSplitAvailable = costSplitAvailable
            && safeAdd(suppliesCosts, otherCosts) == costs;
    }

    public String getSessionName() { return sessionName; }
    public String getActivityHint() { return activityHint; }
    public boolean isPaused() { return paused; }
    public long getElapsedMillis() { return elapsedMillis; }
    public long getRevenue() { return revenue; }
    public long getCosts() { return costs; }
    public long getNet() { return net; }
    public long getProfitPerHour() { return profitPerHour; }
    public long getRollingProfitPerHour() { return rollingProfitPerHour; }
    public int getTransactionCount() { return transactionCount; }
    public int getTransferCount() { return transferCount; }
    public int getActionCount() { return actionCount; }
    public long getProfitPerAction() { return profitPerAction; }
    public long getCostPerAction() { return costPerAction; }
    public boolean isAccountingProjectionAvailable() { return accountingProjectionAvailable; }
    public boolean isTransactionCountAvailable() { return transactionCountAvailable; }
    public boolean isRollingRateAvailable() { return rollingRateAvailable; }
    public boolean isActionCountAvailable() { return actionCountAvailable; }
    public String getAccountingProjectionStatus() { return accountingProjectionStatus; }
    public long getSuppliesCosts() { return suppliesCosts; }
    public long getOtherCosts() { return otherCosts; }
    public boolean isCostSplitAvailable() { return costSplitAvailable; }

    private static long safeAdd(long left, long right)
    {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ex) { return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE; }
    }
}
