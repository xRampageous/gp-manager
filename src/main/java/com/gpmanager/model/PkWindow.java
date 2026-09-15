package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** PvP projection for one current or previous Insights date window. */
public final class PkWindow
{
    private final boolean aggregateAvailable;
    private final boolean aggregateComplete;
    private final boolean rollupBacked;
    private final int kills;
    private final int deaths;
    private final int bestStreak;
    private final long killNetGp;
    private final long deathLossGp;
    private final long netGp;
    private final Long lootValueGp;
    private final Long attachedSuppliesGp;
    private final Long averageFightCostGp;
    private final Double medianKillNetGp;
    private final Double medianDeathLossGp;
    private final boolean detailsAvailable;
    private final List<Event> bestKills;
    private final List<Event> costliestDeaths;

    public PkWindow(boolean aggregateAvailable, boolean aggregateComplete,
        boolean rollupBacked, int kills, int deaths, int bestStreak,
        long killNetGp, long deathLossGp, long netGp, Long lootValueGp,
        Long attachedSuppliesGp, Long averageFightCostGp, Double medianKillNetGp,
        Double medianDeathLossGp, boolean detailsAvailable, List<Event> bestKills,
        List<Event> costliestDeaths)
    {
        this.aggregateAvailable = aggregateAvailable;
        this.aggregateComplete = aggregateComplete;
        this.rollupBacked = rollupBacked;
        this.kills = Math.max(0, kills);
        this.deaths = Math.max(0, deaths);
        this.bestStreak = Math.max(0, bestStreak);
        this.killNetGp = killNetGp;
        this.deathLossGp = Math.max(0L, deathLossGp);
        this.netGp = netGp;
        this.lootValueGp = detailsAvailable ? lootValueGp : null;
        this.attachedSuppliesGp = detailsAvailable ? attachedSuppliesGp : null;
        this.averageFightCostGp = detailsAvailable ? averageFightCostGp : null;
        this.medianKillNetGp = detailsAvailable ? medianKillNetGp : null;
        this.medianDeathLossGp = detailsAvailable ? medianDeathLossGp : null;
        this.detailsAvailable = detailsAvailable;
        this.bestKills = detailsAvailable ? immutable(bestKills) : Collections.emptyList();
        this.costliestDeaths = detailsAvailable ? immutable(costliestDeaths) : Collections.emptyList();
    }

    public static PkWindow unavailable()
    {
        return new PkWindow(false, false, false, 0, 0, 0, 0L, 0L, 0L,
            null, null, null, null, null, false, Collections.emptyList(), Collections.emptyList());
    }

    /** Reuses the existing daily-rollup aggregate; event details are attached by the engine. */
    public static PkWindow aggregate(int kills, int deaths, long killNetGp,
        long deathLossGp, DailyRollup.Coverage coverage)
    {
        boolean available = coverage != null && coverage != DailyRollup.Coverage.UNAVAILABLE;
        boolean complete = coverage == DailyRollup.Coverage.COMPLETE;
        return new PkWindow(available, complete, true, kills, deaths, 0,
            killNetGp, deathLossGp, safeSubtract(killNetGp, deathLossGp),
            null, null, null, null, null, false,
            Collections.emptyList(), Collections.emptyList());
    }

    public boolean isAggregateAvailable() { return aggregateAvailable; }
    public boolean isAggregateComplete() { return aggregateComplete; }
    public boolean isRollupBacked() { return rollupBacked; }
    public int getKills() { return kills; }
    public int getDeaths() { return deaths; }
    public double getKillDeathRatio() { return deaths == 0 ? kills : (double) kills / deaths; }
    public int getBestStreak() { return bestStreak; }
    public long getKillNetGp() { return killNetGp; }
    public long getDeathLossGp() { return deathLossGp; }
    public long getNetGp() { return netGp; }
    public Long getLootValueGp() { return lootValueGp; }
    public boolean isLootValueAvailable() { return lootValueGp != null; }
    public Long getAttachedSuppliesGp() { return attachedSuppliesGp; }
    public boolean isAttachedSuppliesAvailable() { return attachedSuppliesGp != null; }
    public Long getAverageFightCostGp() { return averageFightCostGp; }
    public boolean isAverageFightCostAvailable() { return averageFightCostGp != null; }
    public Double getMedianKillNetGp() { return medianKillNetGp; }
    public boolean isMedianKillNetAvailable() { return medianKillNetGp != null; }
    public Double getMedianDeathLossGp() { return medianDeathLossGp; }
    public boolean isMedianDeathLossAvailable() { return medianDeathLossGp != null; }
    public boolean isDetailsAvailable() { return detailsAvailable; }
    public List<Event> getBestKills() { return bestKills; }
    public List<Event> getCostliestDeaths() { return costliestDeaths; }

    /** Returns an enriched copy while keeping aggregate values sourced by the rollups. */
    public PkWindow withDetails(int bestStreak, Long lootValueGp,
        long attachedSuppliesGp, Long averageFightCostGp, Double medianKillNetGp,
        Double medianDeathLossGp, List<Event> bestKills, List<Event> costliestDeaths)
    {
        return new PkWindow(aggregateAvailable, aggregateComplete, rollupBacked,
            kills, deaths, bestStreak, killNetGp, deathLossGp, netGp, lootValueGp,
            attachedSuppliesGp, averageFightCostGp, medianKillNetGp, medianDeathLossGp,
            true, bestKills, costliestDeaths);
    }

    /** Fails closed when the PVP rollup itself is partial or unavailable. */
    public PkWindow withCoverage(DailyRollup.Coverage coverage)
    {
        if (coverage == null || coverage == DailyRollup.Coverage.UNAVAILABLE)
        {
            return new PkWindow(false, false, rollupBacked, kills, deaths,
                detailsAvailable ? bestStreak : 0, killNetGp, deathLossGp, netGp,
                lootValueGp, attachedSuppliesGp, averageFightCostGp,
                medianKillNetGp, medianDeathLossGp, detailsAvailable,
                bestKills, costliestDeaths);
        }
        boolean complete = coverage == DailyRollup.Coverage.COMPLETE;
        return new PkWindow(true, complete, rollupBacked, kills, deaths,
            detailsAvailable ? bestStreak : 0, killNetGp, deathLossGp, netGp,
            lootValueGp, attachedSuppliesGp, averageFightCostGp, medianKillNetGp,
            medianDeathLossGp, detailsAvailable, bestKills, costliestDeaths);
    }

    private static List<Event> immutable(List<Event> values)
    {
        return Collections.unmodifiableList(new ArrayList<>(
            values == null ? Collections.emptyList() : values));
    }

    private static long safeSubtract(long left, long right)
    {
        try { return Math.subtractExact(left, right); }
        catch (ArithmeticException ex) { return right >= 0L ? Long.MIN_VALUE : Long.MAX_VALUE; }
    }

    /**
     * Best-kill or costliest-death evidence. In {@code bestKills}, valueGp is
     * signed correction-aware encounter net; in {@code costliestDeaths}, it is
     * the positive encounter loss. Generic labels do not invent an opponent.
     */
    public static final class Event
    {
        private final String opponentName;
        private final String locationLabel;
        private final long valueGp;
        private final long timestampEpochMillis;

        public Event(String opponentName, String locationLabel, long valueGp,
            long timestampEpochMillis)
        {
            this.opponentName = opponentName == null ? "" : opponentName;
            this.locationLabel = locationLabel == null ? "" : locationLabel;
        this.valueGp = valueGp;
        this.timestampEpochMillis = Math.max(0L, timestampEpochMillis);
        }

        public String getOpponentName() { return opponentName; }
        public String getLocationLabel() { return locationLabel; }
        public long getValueGp() { return valueGp; }
        public long getTimestampEpochMillis() { return timestampEpochMillis; }
    }
}
