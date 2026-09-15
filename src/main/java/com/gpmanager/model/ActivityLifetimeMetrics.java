package com.gpmanager.model;

public final class ActivityLifetimeMetrics
{
    private final SessionCategory category;
    private final int sessionCount;
    private final int profitableSessionCount;
    private final long totalRevenue;
    private final long totalCosts;
    private final long totalNet;
    private final long totalDurationMillis;
    private final long averageNet;
    private final long averageProfitPerHour;
    private final String bestSessionName;
    private final long bestSessionNet;
    private final String worstSessionName;
    private final long worstSessionNet;

    public ActivityLifetimeMetrics(
        SessionCategory category,
        int sessionCount,
        int profitableSessionCount,
        long totalRevenue,
        long totalCosts,
        long totalNet,
        long totalDurationMillis,
        long averageNet,
        long averageProfitPerHour,
        String bestSessionName,
        long bestSessionNet,
        String worstSessionName,
        long worstSessionNet)
    {
        this.category = category == null ? SessionCategory.GENERAL : category;
        this.sessionCount = Math.max(0, sessionCount);
        this.profitableSessionCount = Math.max(0, profitableSessionCount);
        this.totalRevenue = totalRevenue;
        this.totalCosts = totalCosts;
        this.totalNet = totalNet;
        this.totalDurationMillis = Math.max(0L, totalDurationMillis);
        this.averageNet = averageNet;
        this.averageProfitPerHour = averageProfitPerHour;
        this.bestSessionName = bestSessionName == null ? "" : bestSessionName;
        this.bestSessionNet = bestSessionNet;
        this.worstSessionName = worstSessionName == null ? "" : worstSessionName;
        this.worstSessionNet = worstSessionNet;
    }

    public SessionCategory getCategory() { return category; }
    public int getSessionCount() { return sessionCount; }
    public int getProfitableSessionCount() { return profitableSessionCount; }
    public long getTotalRevenue() { return totalRevenue; }
    public long getTotalCosts() { return totalCosts; }
    public long getTotalNet() { return totalNet; }
    public long getTotalDurationMillis() { return totalDurationMillis; }
    public long getAverageNet() { return averageNet; }
    public long getAverageProfitPerHour() { return averageProfitPerHour; }
    public String getBestSessionName() { return bestSessionName; }
    public long getBestSessionNet() { return bestSessionNet; }
    public String getWorstSessionName() { return worstSessionName; }
    public long getWorstSessionNet() { return worstSessionNet; }

    public int getProfitablePercent()
    {
        return sessionCount == 0
            ? 0
            : (int) Math.round((double) profitableSessionCount * 100.0 / sessionCount);
    }
}
