package com.gpmanager.model;

public class ActivityMetrics
{
    private final String activityName;
    private final int actionCount;
    private final int transactionCount;
    private final long revenue;
    private final long costs;
    private final long net;
    private final long profitPerAction;
    private final long costPerAction;
    private final boolean actionCountAvailable;

    public ActivityMetrics(
        String activityName,
        int actionCount,
        int transactionCount,
        long revenue,
        long costs,
        long net)
    {
        this(activityName, actionCount, transactionCount, revenue, costs, net, true);
    }

    public ActivityMetrics(
        String activityName,
        int actionCount,
        int transactionCount,
        long revenue,
        long costs,
        long net,
        boolean actionCountAvailable)
    {
        this.activityName = activityName == null || activityName.trim().isEmpty()
            ? "General"
            : activityName.trim();
        this.actionCount = Math.max(0, actionCount);
        this.transactionCount = Math.max(0, transactionCount);
        this.revenue = revenue;
        this.costs = costs;
        this.net = net;
        this.actionCountAvailable = actionCountAvailable;
        this.profitPerAction = actionCountAvailable ? divide(net, this.actionCount) : 0L;
        this.costPerAction = actionCountAvailable ? divide(costs, this.actionCount) : 0L;
    }

    private static long divide(long value, int divisor)
    {
        return divisor <= 0 ? 0L : Math.round(value / (double) divisor);
    }

    public String getActivityName()
    {
        return activityName;
    }

    public int getActionCount()
    {
        return actionCount;
    }

    public int getTransactionCount()
    {
        return transactionCount;
    }

    public long getRevenue()
    {
        return revenue;
    }

    public long getCosts()
    {
        return costs;
    }

    public long getNet()
    {
        return net;
    }

    public long getProfitPerAction()
    {
        return profitPerAction;
    }

    public long getCostPerAction()
    {
        return costPerAction;
    }

    public boolean isActionCountAvailable()
    {
        return actionCountAvailable;
    }
}
