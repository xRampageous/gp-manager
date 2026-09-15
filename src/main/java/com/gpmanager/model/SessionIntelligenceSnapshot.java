package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SessionIntelligenceSnapshot
{
    private final SessionComparisonMetrics recent;
    private final SessionComparisonMetrics lifetime;
    private final List<ActivityLifetimeMetrics> activityMetrics;
    private final String bestNetSessionName;
    private final long bestNet;
    private final String bestRateSessionName;
    private final long bestRate;
    private final String longestSessionName;
    private final long longestDurationMillis;
    private final String bestPkKillSessionName;
    private final long bestPkKill;
    private final String largestPkDeathSessionName;
    private final long largestPkDeath;
    private final List<Long> recentNetTrend;
    private final ProfitTrendDirection trendDirection;
    private final String projectionStatus;

    public SessionIntelligenceSnapshot(
        SessionComparisonMetrics recent,
        SessionComparisonMetrics lifetime,
        List<ActivityLifetimeMetrics> activityMetrics,
        String bestNetSessionName,
        long bestNet,
        String bestRateSessionName,
        long bestRate,
        String longestSessionName,
        long longestDurationMillis,
        String bestPkKillSessionName,
        long bestPkKill,
        String largestPkDeathSessionName,
        long largestPkDeath,
        List<Long> recentNetTrend,
        ProfitTrendDirection trendDirection)
    {
        this(recent, lifetime, activityMetrics, bestNetSessionName, bestNet,
            bestRateSessionName, bestRate, longestSessionName, longestDurationMillis,
            bestPkKillSessionName, bestPkKill, largestPkDeathSessionName, largestPkDeath,
            recentNetTrend, trendDirection, "AVAILABLE");
    }

    public SessionIntelligenceSnapshot(
        SessionComparisonMetrics recent,
        SessionComparisonMetrics lifetime,
        List<ActivityLifetimeMetrics> activityMetrics,
        String bestNetSessionName,
        long bestNet,
        String bestRateSessionName,
        long bestRate,
        String longestSessionName,
        long longestDurationMillis,
        String bestPkKillSessionName,
        long bestPkKill,
        String largestPkDeathSessionName,
        long largestPkDeath,
        List<Long> recentNetTrend,
        ProfitTrendDirection trendDirection,
        String projectionStatus)
    {
        this.recent = recent == null ? SessionComparisonMetrics.empty(0L) : recent;
        this.lifetime = lifetime == null ? SessionComparisonMetrics.empty(0L) : lifetime;
        this.activityMetrics = Collections.unmodifiableList(new ArrayList<>(
            activityMetrics == null ? Collections.emptyList() : activityMetrics));
        this.bestNetSessionName = safe(bestNetSessionName);
        this.bestNet = bestNet;
        this.bestRateSessionName = safe(bestRateSessionName);
        this.bestRate = bestRate;
        this.longestSessionName = safe(longestSessionName);
        this.longestDurationMillis = Math.max(0L, longestDurationMillis);
        this.bestPkKillSessionName = safe(bestPkKillSessionName);
        this.bestPkKill = bestPkKill;
        this.largestPkDeathSessionName = safe(largestPkDeathSessionName);
        this.largestPkDeath = largestPkDeath;
        this.recentNetTrend = Collections.unmodifiableList(new ArrayList<>(
            recentNetTrend == null ? Collections.emptyList() : recentNetTrend));
        this.trendDirection = trendDirection == null
            ? ProfitTrendDirection.INSUFFICIENT_DATA
            : trendDirection;
        this.projectionStatus = projectionStatus == null ? "UNAVAILABLE" : projectionStatus;
    }

    private static String safe(String value)
    {
        return value == null ? "" : value;
    }

    public SessionComparisonMetrics getRecent() { return recent; }
    public SessionComparisonMetrics getLifetime() { return lifetime; }
    public List<ActivityLifetimeMetrics> getActivityMetrics() { return activityMetrics; }
    public String getBestNetSessionName() { return bestNetSessionName; }
    public long getBestNet() { return bestNet; }
    public String getBestRateSessionName() { return bestRateSessionName; }
    public long getBestRate() { return bestRate; }
    public String getLongestSessionName() { return longestSessionName; }
    public long getLongestDurationMillis() { return longestDurationMillis; }
    public String getBestPkKillSessionName() { return bestPkKillSessionName; }
    public long getBestPkKill() { return bestPkKill; }
    public String getLargestPkDeathSessionName() { return largestPkDeathSessionName; }
    public long getLargestPkDeath() { return largestPkDeath; }
    public List<Long> getRecentNetTrend() { return recentNetTrend; }
    public ProfitTrendDirection getTrendDirection() { return trendDirection; }
    public String getProjectionStatus() { return projectionStatus; }
    public boolean isProjectionAvailable() { return "AVAILABLE".equals(projectionStatus); }

    public long getRecentVsLifetimeAverageNet()
    {
        return recent.getAverageNet() - lifetime.getAverageNet();
    }
}
