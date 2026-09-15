package com.gpmanager.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PkMetrics
{
    private final int kills;
    private final int deaths;
    private final int currentStreak;
    private final long revenue;
    private final long costs;
    private final long net;
    private final long bestKill;
    private final long largestDeathLoss;
    private final long totalKillNet;
    private final long totalDeathLoss;
    private final boolean projectionAvailable;
    private final long suppliesCosts;
    private final long otherCosts;
    private final boolean costSplitAvailable;
    private final Double medianKillNetGp;
    private final Double medianDeathLossGp;

    public PkMetrics(
        int kills,
        int deaths,
        int currentStreak,
        long revenue,
        long costs,
        long net,
        long bestKill,
        long largestDeathLoss)
    {
        this(
            kills, deaths, currentStreak, revenue, costs, net, bestKill,
            largestDeathLoss, kills == 0 ? 0L : net, largestDeathLoss);
    }

    public PkMetrics(
        int kills,
        int deaths,
        int currentStreak,
        long revenue,
        long costs,
        long net,
        long bestKill,
        long largestDeathLoss,
        long totalKillNet,
        long totalDeathLoss)
    {
        this(kills, deaths, currentStreak, revenue, costs, net, bestKill,
            largestDeathLoss, totalKillNet, totalDeathLoss, true);
    }

    public PkMetrics(
        int kills,
        int deaths,
        int currentStreak,
        long revenue,
        long costs,
        long net,
        long bestKill,
        long largestDeathLoss,
        long totalKillNet,
        long totalDeathLoss,
        boolean projectionAvailable)
    {
        this(kills, deaths, currentStreak, revenue, costs, net, bestKill,
            largestDeathLoss, totalKillNet, totalDeathLoss, projectionAvailable,
            0L, 0L, false, null, null);
    }

    public PkMetrics(
        int kills,
        int deaths,
        int currentStreak,
        long revenue,
        long costs,
        long net,
        long bestKill,
        long largestDeathLoss,
        long totalKillNet,
        long totalDeathLoss,
        boolean projectionAvailable,
        long suppliesCosts,
        long otherCosts,
        boolean costSplitAvailable)
    {
        this(kills, deaths, currentStreak, revenue, costs, net, bestKill,
            largestDeathLoss, totalKillNet, totalDeathLoss, projectionAvailable,
            suppliesCosts, otherCosts, costSplitAvailable, null, null);
    }

    public PkMetrics(
        int kills,
        int deaths,
        int currentStreak,
        long revenue,
        long costs,
        long net,
        long bestKill,
        long largestDeathLoss,
        long totalKillNet,
        long totalDeathLoss,
        boolean projectionAvailable,
        long suppliesCosts,
        long otherCosts,
        boolean costSplitAvailable,
        Double medianKillNetGp,
        Double medianDeathLossGp)
    {
        this.kills = Math.max(0, kills);
        this.deaths = Math.max(0, deaths);
        this.currentStreak = currentStreak;
        this.revenue = revenue;
        this.costs = costs;
        this.net = net;
        this.bestKill = bestKill;
        this.largestDeathLoss = Math.max(0L, largestDeathLoss);
        this.totalKillNet = totalKillNet;
        this.totalDeathLoss = Math.max(0L, totalDeathLoss);
        this.projectionAvailable = projectionAvailable;
        this.suppliesCosts = suppliesCosts;
        this.otherCosts = otherCosts;
        this.costSplitAvailable = projectionAvailable && costSplitAvailable
            && safeAdd(suppliesCosts, otherCosts) == costs;
        this.medianKillNetGp = projectionAvailable && this.kills > 0 ? medianKillNetGp : null;
        this.medianDeathLossGp = projectionAvailable && this.deaths > 0
            ? medianDeathLossGp : null;
    }

    public int getKills() { return kills; }
    public int getDeaths() { return deaths; }
    public int getCurrentStreak() { return currentStreak; }
    public long getRevenue() { return revenue; }
    public long getCosts() { return costs; }
    public long getNet() { return net; }
    public long getBestKill() { return bestKill; }
    public long getLargestDeathLoss() { return largestDeathLoss; }
    public long getTotalKillNet() { return totalKillNet; }
    public long getTotalDeathLoss() { return totalDeathLoss; }
    public boolean isProjectionAvailable() { return projectionAvailable; }
    public long getSuppliesCosts() { return suppliesCosts; }
    public long getOtherCosts() { return otherCosts; }
    public boolean isCostSplitAvailable() { return costSplitAvailable; }
    public Double getMedianKillNetGp() { return medianKillNetGp; }
    public boolean isMedianKillNetAvailable() { return medianKillNetGp != null; }
    public Double getMedianDeathLossGp() { return medianDeathLossGp; }
    public boolean isMedianDeathLossAvailable() { return medianDeathLossGp != null; }
    public int getEncounterCount() { return kills + deaths; }
    public long getProfitPerKill() { return kills == 0 ? 0L : totalKillNet / kills; }
    public long getLossPerDeath() { return deaths == 0 ? 0L : totalDeathLoss / deaths; }
    /** Average Supplies cost per recorded kill/death encounter; zero with no fights. */
    public long getSuppliesPerFight()
    {
        int encounters = getEncounterCount();
        return encounters == 0 ? 0L : suppliesCosts / encounters;
    }
    public long getNetPerEncounter()
    {
        int encounters = getEncounterCount();
        return encounters == 0 ? 0L : net / encounters;
    }
    public double getKillDeathRatio()
    {
        return deaths == 0 ? kills : (double) kills / deaths;
    }

    /** Exact middle-value median, averaging the two centre values for even counts. */
    public static Double median(List<Long> values)
    {
        if (values == null || values.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) return sorted.get(middle).doubleValue();
        return BigDecimal.valueOf(sorted.get(middle - 1))
            .add(BigDecimal.valueOf(sorted.get(middle)))
            .divide(BigDecimal.valueOf(2L)).doubleValue();
    }

    private static long safeAdd(long left, long right)
    {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ex) { return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE; }
    }
}
