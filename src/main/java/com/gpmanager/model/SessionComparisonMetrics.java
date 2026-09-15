package com.gpmanager.model;

public class SessionComparisonMetrics
{
    private final int sessionCount;
    private final int profitableSessionCount;
    private final long averageNet;
    private final long averageProfitPerHour;
    private final long bestNet;
    private final long worstNet;
    private final long currentNet;
    private final long currentVsAverage;
    private final String projectionStatus;

    public SessionComparisonMetrics(
        int sessionCount,
        int profitableSessionCount,
        long averageNet,
        long averageProfitPerHour,
        long bestNet,
        long worstNet,
        long currentNet,
        long currentVsAverage)
    {
        this(sessionCount, profitableSessionCount, averageNet, averageProfitPerHour,
            bestNet, worstNet, currentNet, currentVsAverage, "AVAILABLE");
    }

    public SessionComparisonMetrics(
        int sessionCount,
        int profitableSessionCount,
        long averageNet,
        long averageProfitPerHour,
        long bestNet,
        long worstNet,
        long currentNet,
        long currentVsAverage,
        String projectionStatus)
    {
        this.sessionCount = Math.max(0, sessionCount);
        this.profitableSessionCount = Math.max(0, profitableSessionCount);
        this.averageNet = averageNet;
        this.averageProfitPerHour = averageProfitPerHour;
        this.bestNet = bestNet;
        this.worstNet = worstNet;
        this.currentNet = currentNet;
        this.currentVsAverage = currentVsAverage;
        this.projectionStatus = projectionStatus == null ? "UNAVAILABLE" : projectionStatus;
    }

    public static SessionComparisonMetrics empty(long currentNet)
    {
        return new SessionComparisonMetrics(
            0,
            0,
            0L,
            0L,
            0L,
            0L,
            currentNet,
            0L);
    }

    public static SessionComparisonMetrics unavailable(long currentNet, String status)
    {
        return new SessionComparisonMetrics(0, 0, 0L, 0L, 0L, 0L,
            currentNet, 0L, status == null ? "UNAVAILABLE" : status);
    }

    public int getSessionCount() { return sessionCount; }
    public int getProfitableSessionCount() { return profitableSessionCount; }
    public long getAverageNet() { return averageNet; }
    public long getAverageProfitPerHour() { return averageProfitPerHour; }
    public long getBestNet() { return bestNet; }
    public long getWorstNet() { return worstNet; }
    public long getCurrentNet() { return currentNet; }
    public long getCurrentVsAverage() { return currentVsAverage; }
    public String getProjectionStatus() { return projectionStatus; }
    public boolean isProjectionAvailable() { return "AVAILABLE".equals(projectionStatus); }

    public int getProfitablePercent()
    {
        return sessionCount == 0
            ? 0
            : (int) Math.round((double) profitableSessionCount * 100.0 / sessionCount);
    }
}
