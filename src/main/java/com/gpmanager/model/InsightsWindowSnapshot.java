package com.gpmanager.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Current and immediately preceding equal-length local-date windows for Insights. */
public final class InsightsWindowSnapshot
{
    private final String zoneId;
    private final LocalDate currentFrom;
    private final LocalDate currentTo;
    private final LocalDate previousFrom;
    private final LocalDate previousTo;
    private final Window current;
    private final Window previous;

    public InsightsWindowSnapshot(String zoneId, LocalDate currentFrom, LocalDate currentTo,
        LocalDate previousFrom, LocalDate previousTo, Window current, Window previous)
    {
        this.zoneId = zoneId == null ? "UTC" : zoneId;
        this.currentFrom = currentFrom;
        this.currentTo = currentTo;
        this.previousFrom = previousFrom;
        this.previousTo = previousTo;
        this.current = current == null ? Window.empty() : current;
        this.previous = previous == null ? Window.empty() : previous;
    }

    public String getZoneId() { return zoneId; }
    public LocalDate getCurrentFrom() { return currentFrom; }
    public LocalDate getCurrentTo() { return currentTo; }
    public LocalDate getPreviousFrom() { return previousFrom; }
    public LocalDate getPreviousTo() { return previousTo; }
    public Window getCurrent() { return current; }
    public Window getPrevious() { return previous; }

    /** Session-sized performance row. Net/time are window-scoped; identity fields describe its owner. */
    public static final class SessionHighlight
    {
        private final boolean available;
        private final boolean rollupBacked;
        private final String sessionId;
        private final String name;
        private final SessionCategory category;
        private final long netGp;
        private final long activeMillis;
        private final long startedAtEpochMillis;

        private SessionHighlight(boolean available, boolean rollupBacked, String sessionId,
            String name, SessionCategory category, long netGp, long activeMillis, long startedAtEpochMillis)
        {
            this.available = available;
            this.rollupBacked = rollupBacked;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.name = name == null ? "" : name;
            this.category = category == null ? SessionCategory.GENERAL : category;
            this.netGp = netGp;
            this.activeMillis = Math.max(0L, activeMillis);
            this.startedAtEpochMillis = Math.max(0L, startedAtEpochMillis);
        }

        public static SessionHighlight unavailable(boolean rollupBacked)
        {
            return new SessionHighlight(false, rollupBacked, "", "", SessionCategory.GENERAL,
                0L, 0L, 0L);
        }

        public static SessionHighlight of(String sessionId, String name, SessionCategory category,
            long netGp, long activeMillis, long startedAtEpochMillis)
        {
            return new SessionHighlight(true, false, sessionId, name, category,
                netGp, activeMillis, startedAtEpochMillis);
        }

        public boolean isAvailable() { return available; }
        public boolean isRollupBacked() { return rollupBacked; }
        public String getSessionId() { return sessionId; }
        public String getName() { return name; }
        public SessionCategory getCategory() { return category; }
        public long getNetGp() { return netGp; }
        public long getActiveMillis() { return activeMillis; }
        public long getStartedAtEpochMillis() { return startedAtEpochMillis; }
    }

    /** Largest individually evidenced counted item gain; receipt-free rollups cannot identify this. */
    public static final class BiggestDrop
    {
        private final boolean evidenceComplete;
        private final boolean present;
        private final boolean rollupBacked;
        private final int itemId;
        private final String itemName;
        private final long valueGp;
        private final long quantity;
        private final String sessionId;
        private final long timestampEpochMillis;

        private BiggestDrop(boolean evidenceComplete, boolean present, boolean rollupBacked,
            int itemId, String itemName, long valueGp, long quantity, String sessionId,
            long timestampEpochMillis)
        {
            this.evidenceComplete = evidenceComplete;
            this.present = present;
            this.rollupBacked = rollupBacked;
            this.itemId = Math.max(0, itemId);
            this.itemName = itemName == null ? "" : itemName;
            this.valueGp = Math.max(0L, valueGp);
            this.quantity = Math.max(0L, quantity);
            this.sessionId = sessionId == null ? "" : sessionId;
            this.timestampEpochMillis = Math.max(0L, timestampEpochMillis);
        }

        public static BiggestDrop unavailable(boolean rollupBacked)
        {
            return new BiggestDrop(false, false, rollupBacked, 0, "", 0L, 0L, "", 0L);
        }

        public static BiggestDrop none()
        {
            return new BiggestDrop(true, false, false, 0, "", 0L, 0L, "", 0L);
        }

        public static BiggestDrop of(int itemId, String itemName, long valueGp,
            long quantity, String sessionId, long timestampEpochMillis)
        {
            return new BiggestDrop(true, true, false, itemId, itemName, valueGp,
                quantity, sessionId, timestampEpochMillis);
        }

        public boolean isAvailable() { return evidenceComplete; }
        public boolean isPresent() { return present; }
        public boolean isRollupBacked() { return rollupBacked; }
        public int getItemId() { return itemId; }
        public String getItemName() { return itemName; }
        public long getValueGp() { return valueGp; }
        public long getQuantity() { return quantity; }
        public String getSessionId() { return sessionId; }
        public long getTimestampEpochMillis() { return timestampEpochMillis; }
    }

    /** Highest-net active local clock hour in a profile date window. */
    public static final class BestHour
    {
        private final boolean available;
        private final boolean rollupBacked;
        private final LocalDate date;
        private final int hour;
        private final String zoneId;
        private final long netGp;
        private final long activeMillis;

        private BestHour(boolean available, boolean rollupBacked, LocalDate date, int hour,
            String zoneId, long netGp, long activeMillis)
        {
            this.available = available;
            this.rollupBacked = rollupBacked;
            this.date = date;
            this.hour = hour;
            this.zoneId = zoneId == null ? "UTC" : zoneId;
            this.netGp = netGp;
            this.activeMillis = Math.max(0L, activeMillis);
        }

        public static BestHour unavailable(boolean rollupBacked)
        {
            return new BestHour(false, rollupBacked, null, -1, "UTC", 0L, 0L);
        }

        public static BestHour of(LocalDate date, int hour, String zoneId, long netGp, long activeMillis)
        {
            return new BestHour(true, true, date, hour, zoneId, netGp, activeMillis);
        }

        public boolean isAvailable() { return available; }
        public boolean isRollupBacked() { return rollupBacked; }
        public LocalDate getDate() { return date; }
        public int getHour() { return hour; }
        public String getZoneId() { return zoneId; }
        public long getNetGp() { return netGp; }
        public long getActiveMillis() { return activeMillis; }
    }

    /**
     * Per-activity values used by Insights; counts are unique sessions, not
     * session-days. Positive-net share is positive activity net divided by the
     * window's positive total net, and is null when that denominator/coverage
     * is unavailable.
     */
    public static final class ActivityInsight
    {
        private final String name;
        private final long netGp;
        private final long activeMillis;
        private final int sessionCount;
        private final boolean sessionCountAvailable;
        private final Double positiveNetShare;
        private final boolean rollupBacked;

        private ActivityInsight(String name, long netGp, long activeMillis, int sessionCount,
            boolean sessionCountAvailable, Double positiveNetShare, boolean rollupBacked)
        {
            this.name = name == null ? "General" : name;
            this.netGp = netGp;
            this.activeMillis = Math.max(0L, activeMillis);
            this.sessionCount = Math.max(0, sessionCount);
            this.sessionCountAvailable = sessionCountAvailable;
            this.positiveNetShare = positiveNetShare;
            this.rollupBacked = rollupBacked;
        }

        public String getName() { return name; }
        public long getNetGp() { return netGp; }
        public long getActiveMillis() { return activeMillis; }
        public int getSessionCount() { return sessionCount; }
        public boolean isSessionCountAvailable() { return sessionCountAvailable; }
        public Double getPositiveNetShare() { return positiveNetShare; }
        public boolean isPositiveNetShareAvailable() { return positiveNetShare != null; }
        public boolean isRollupBacked() { return rollupBacked; }
    }

    /** Aggregated fields shared by either date window. */
    public static final class Window
    {
        private final long revenueGp;
        private final long costsGp;
        private final long suppliesCostsGp;
        private final long lossCostsGp;
        private final long activeMillis;
        private final int kills;
        private final int deaths;
        private final int sessionStarts;
        private final int runStarts;
        private final int pvpKills;
        private final int pvpDeaths;
        private final long pvpKillNetGp;
        private final long pvpLossGp;
        private final long pvpBestKillGp;
        private final long pvpLargestLossGp;
        private final Map<String, DailyRollup.ActivityTotal> activities;
        private final Map<String, DailyRollup.ItemTotal> gainedItems;
        private final Map<String, DailyRollup.ItemTotal> costItems;
        private final long[] fourHourNetGp;
        private final long[] fourHourActiveMillis;
        private final long[] hourlyNetGp;
        private final long[] hourlyActiveMillis;
        private final boolean gainedItemsTruncated;
        private final boolean costItemsTruncated;
        private final SessionHighlight bestSession;
        private final SessionHighlight longestSession;
        private final BiggestDrop biggestDrop;
        private final BestHour bestHour;
        private final List<ActivityInsight> topActivities;
        private final Long averageGpPerHour;
        private final boolean averageGpPerHourRollupBacked;
        private final Map<DailyRollup.Dimension, DailyRollup.Coverage> coverage;
        private final PkWindow pkWindow;

        private Window(long revenueGp, long costsGp, long suppliesCostsGp, long lossCostsGp,
            long activeMillis, int kills, int deaths, int sessionStarts, int runStarts,
            int pvpKills, int pvpDeaths, long pvpKillNetGp, long pvpLossGp,
            long pvpBestKillGp, long pvpLargestLossGp,
            Map<String, DailyRollup.ActivityTotal> activities,
            Map<String, DailyRollup.ItemTotal> gainedItems,
            Map<String, DailyRollup.ItemTotal> costItems,
            long[] fourHourNetGp, long[] fourHourActiveMillis,
            long[] hourlyNetGp, long[] hourlyActiveMillis,
            boolean gainedItemsTruncated, boolean costItemsTruncated,
            SessionHighlight bestSession, SessionHighlight longestSession,
            BiggestDrop biggestDrop, BestHour bestHour, List<ActivityInsight> topActivities,
            Long averageGpPerHour, boolean averageGpPerHourRollupBacked,
            Map<DailyRollup.Dimension, DailyRollup.Coverage> coverage, PkWindow pkWindow)
        {
            this.revenueGp = revenueGp;
            this.costsGp = costsGp;
            this.suppliesCostsGp = suppliesCostsGp;
            this.lossCostsGp = lossCostsGp;
            this.activeMillis = activeMillis;
            this.kills = kills;
            this.deaths = deaths;
            this.sessionStarts = sessionStarts;
            this.runStarts = runStarts;
            this.pvpKills = pvpKills;
            this.pvpDeaths = pvpDeaths;
            this.pvpKillNetGp = pvpKillNetGp;
            this.pvpLossGp = pvpLossGp;
            this.pvpBestKillGp = pvpBestKillGp;
            this.pvpLargestLossGp = pvpLargestLossGp;
            this.activities = Collections.unmodifiableMap(new LinkedHashMap<>(activities));
            this.gainedItems = Collections.unmodifiableMap(new LinkedHashMap<>(gainedItems));
            this.costItems = Collections.unmodifiableMap(new LinkedHashMap<>(costItems));
            this.fourHourNetGp = fourHourNetGp.clone();
            this.fourHourActiveMillis = fourHourActiveMillis.clone();
            this.hourlyNetGp = hourlyNetGp.clone();
            this.hourlyActiveMillis = hourlyActiveMillis.clone();
            this.gainedItemsTruncated = gainedItemsTruncated;
            this.costItemsTruncated = costItemsTruncated;
            this.bestSession = bestSession == null ? SessionHighlight.unavailable(false) : bestSession;
            this.longestSession = longestSession == null ? SessionHighlight.unavailable(false) : longestSession;
            this.biggestDrop = biggestDrop == null ? BiggestDrop.unavailable(false) : biggestDrop;
            this.bestHour = bestHour == null ? BestHour.unavailable(true) : bestHour;
            this.topActivities = Collections.unmodifiableList(new ArrayList<>(
                topActivities == null ? Collections.emptyList() : topActivities));
            this.averageGpPerHour = averageGpPerHour;
            this.averageGpPerHourRollupBacked = averageGpPerHourRollupBacked;
            this.coverage = Collections.unmodifiableMap(new EnumMap<>(coverage));
            this.pkWindow = pkWindow == null
                ? PkWindow.aggregate(pvpKills, pvpDeaths, pvpKillNetGp, pvpLossGp,
                    coverage.getOrDefault(DailyRollup.Dimension.PVP, DailyRollup.Coverage.UNAVAILABLE))
                : pkWindow;
        }

        public long getRevenueGp() { return revenueGp; }
        public long getCostsGp() { return costsGp; }
        public long getSuppliesCostsGp() { return suppliesCostsGp; }
        public long getLossCostsGp() { return lossCostsGp; }
        public long getNetGp() { return safeSubtract(revenueGp, costsGp); }
        public long getActiveMillis() { return activeMillis; }
        /** Null when excluded-session or duration coverage cannot support an honest rate. */
        public Long getAverageGpPerHour() { return averageGpPerHour; }
        public boolean isAverageGpPerHourAvailable() { return averageGpPerHour != null; }
        public boolean isAverageGpPerHourRollupBacked() { return averageGpPerHourRollupBacked; }
        public int getKills() { return kills; }
        public int getDeaths() { return deaths; }
        public int getSessionStarts() { return sessionStarts; }
        public int getRunStarts() { return runStarts; }
        public int getPvpKills() { return pvpKills; }
        public int getPvpDeaths() { return pvpDeaths; }
        public long getPvpKillNetGp() { return pvpKillNetGp; }
        public long getPvpLossGp() { return pvpLossGp; }
        public long getPvpBestKillGp() { return pvpBestKillGp; }
        public long getPvpLargestLossGp() { return pvpLargestLossGp; }
        public PkWindow getPkWindow() { return pkWindow; }
        public Map<String, DailyRollup.ActivityTotal> getActivities() { return activities; }
        public Map<String, DailyRollup.ItemTotal> getGainedItemTotals() { return gainedItems; }
        public Map<String, DailyRollup.ItemTotal> getCostItemTotals() { return costItems; }
        public long[] getFourHourNetGp() { return fourHourNetGp.clone(); }
        public long[] getFourHourActiveMillis() { return fourHourActiveMillis.clone(); }
        public long[] getHourlyNetGp() { return hourlyNetGp.clone(); }
        public long[] getHourlyActiveMillis() { return hourlyActiveMillis.clone(); }
        public SessionHighlight getBestSession() { return bestSession; }
        public SessionHighlight getLongestSession() { return longestSession; }
        public BiggestDrop getBiggestDrop() { return biggestDrop; }
        public BestHour getBestHour() { return bestHour; }
        public List<ActivityInsight> getTopActivities() { return topActivities; }
        public int getTopItemLimit() { return DailyRollup.TOP_ITEM_LIMIT; }
        public boolean isGainedItemsTruncated() { return gainedItemsTruncated; }
        public boolean isCostItemsTruncated() { return costItemsTruncated; }
        public Map<DailyRollup.Dimension, DailyRollup.Coverage> getCoverage() { return coverage; }
        public DailyRollup.Coverage getCoverage(DailyRollup.Dimension dimension)
        {
            return coverage.getOrDefault(dimension, DailyRollup.Coverage.UNAVAILABLE);
        }

        /** Returns this aggregate with one dimension's coverage safely downgraded. */
        public Window withCoverage(DailyRollup.Dimension dimension, DailyRollup.Coverage value)
        {
            EnumMap<DailyRollup.Dimension, DailyRollup.Coverage> copy =
                new EnumMap<>(DailyRollup.Dimension.class);
            copy.putAll(coverage);
            copy.put(dimension, DailyRollup.Coverage.weakest(copy.get(dimension), value));
            boolean accountingComplete = copy.get(DailyRollup.Dimension.ACCOUNTING)
                == DailyRollup.Coverage.COMPLETE;
            boolean activeTimeComplete = copy.get(DailyRollup.Dimension.ACTIVE_TIME)
                == DailyRollup.Coverage.COMPLETE;
            boolean hourlyComplete = copy.get(DailyRollup.Dimension.HOURLY_BUCKETS)
                == DailyRollup.Coverage.COMPLETE;
            boolean activityValuesComplete = accountingComplete
                && copy.get(DailyRollup.Dimension.ACTIVITIES) == DailyRollup.Coverage.COMPLETE;
            boolean activityCountsComplete = copy.get(DailyRollup.Dimension.ACTIVITY_SESSION_COUNTS)
                == DailyRollup.Coverage.COMPLETE;
            List<ActivityInsight> safeActivities = new ArrayList<>();
            for (ActivityInsight activity : topActivities)
            {
                safeActivities.add(new ActivityInsight(activity.getName(), activity.getNetGp(),
                    activity.getActiveMillis(), activity.getSessionCount(),
                    activity.isSessionCountAvailable() && activityCountsComplete,
                    activityValuesComplete ? activity.getPositiveNetShare() : null,
                    activity.isRollupBacked()));
            }
            return new Window(revenueGp, costsGp, suppliesCostsGp, lossCostsGp, activeMillis,
                kills, deaths, sessionStarts, runStarts, pvpKills, pvpDeaths, pvpKillNetGp, pvpLossGp,
                pvpBestKillGp, pvpLargestLossGp, activities, gainedItems, costItems,
                fourHourNetGp, fourHourActiveMillis, hourlyNetGp, hourlyActiveMillis,
                gainedItemsTruncated, costItemsTruncated,
                accountingComplete && activeTimeComplete ? bestSession
                    : SessionHighlight.unavailable(bestSession.isRollupBacked()),
                accountingComplete && activeTimeComplete ? longestSession
                    : SessionHighlight.unavailable(longestSession.isRollupBacked()),
                accountingComplete ? biggestDrop : BiggestDrop.unavailable(biggestDrop.isRollupBacked()),
                hourlyComplete ? bestHour : BestHour.unavailable(bestHour.isRollupBacked()),
                safeActivities,
                accountingComplete && activeTimeComplete ? averageGpPerHour : null,
                averageGpPerHourRollupBacked, copy,
                pkWindow.withCoverage(copy.get(DailyRollup.Dimension.PVP)));
        }

        /** Adds session/receipt evidence without changing the rollup aggregation contract. */
        public Window withPerformance(SessionHighlight best, SessionHighlight longest,
            BiggestDrop drop, Long averageGpPerHour, boolean averageRollupBacked,
            List<ActivityInsight> activities)
        {
            return new Window(revenueGp, costsGp, suppliesCostsGp, lossCostsGp, activeMillis,
                kills, deaths, sessionStarts, runStarts, pvpKills, pvpDeaths, pvpKillNetGp, pvpLossGp,
                pvpBestKillGp, pvpLargestLossGp, this.activities, gainedItems, costItems,
                fourHourNetGp, fourHourActiveMillis, hourlyNetGp, hourlyActiveMillis,
                gainedItemsTruncated, costItemsTruncated, best, longest, drop, bestHour,
                activities, averageGpPerHour, averageRollupBacked, coverage, pkWindow);
        }

        /** Attach exact event-distribution evidence without replacing daily aggregate values. */
        public Window withPkWindow(PkWindow value)
        {
            return new Window(revenueGp, costsGp, suppliesCostsGp, lossCostsGp, activeMillis,
                kills, deaths, sessionStarts, runStarts, pvpKills, pvpDeaths, pvpKillNetGp, pvpLossGp,
                pvpBestKillGp, pvpLargestLossGp, activities, gainedItems, costItems,
                fourHourNetGp, fourHourActiveMillis, hourlyNetGp, hourlyActiveMillis,
                gainedItemsTruncated, costItemsTruncated, bestSession, longestSession,
                biggestDrop, bestHour, topActivities, averageGpPerHour,
                averageGpPerHourRollupBacked, coverage, value);
        }

        /** Aggregates bounded daily summaries without opening session receipts. */
        public static Window aggregate(Iterable<DailyRollup> source)
        {
            if (source == null) return empty();
            long revenue = 0L, costs = 0L, supplies = 0L, loss = 0L, active = 0L;
            long pvpKillNet = 0L, pvpLoss = 0L, bestKill = 0L, largestLoss = 0L;
            int kills = 0, deaths = 0, starts = 0, runs = 0, pvpKills = 0, pvpDeaths = 0;
            Map<String, MutableActivity> activityMap = new LinkedHashMap<>();
            Map<String, MutableItem> gainedMap = new LinkedHashMap<>();
            Map<String, MutableItem> costMap = new LinkedHashMap<>();
            long[] netBuckets = new long[DailyRollup.FOUR_HOUR_BUCKET_COUNT];
            long[] activeBuckets = new long[DailyRollup.FOUR_HOUR_BUCKET_COUNT];
            long[] hourlyNetBuckets = new long[DailyRollup.HOURLY_BUCKET_COUNT];
            long[] hourlyActiveBuckets = new long[DailyRollup.HOURLY_BUCKET_COUNT];
            boolean gainedTruncated = false;
            boolean costTruncated = false;
            BestHour bestHour = null;
            EnumMap<DailyRollup.Dimension, DailyRollup.Coverage> coverage =
                new EnumMap<>(DailyRollup.Dimension.class);
            boolean any = false;
            for (DailyRollup rollup : source)
            {
                if (rollup == null) continue;
                if (!any)
                {
                    coverage.putAll(rollup.getCoverageByDimension());
                    any = true;
                }
                else
                {
                    for (DailyRollup.Dimension dimension : DailyRollup.Dimension.values())
                    {
                        coverage.put(dimension, DailyRollup.Coverage.weakest(
                            coverage.get(dimension), rollup.getCoverage(dimension)));
                    }
                }
                revenue = safeAdd(revenue, rollup.getRevenueGp());
                costs = safeAdd(costs, rollup.getCostsGp());
                supplies = safeAdd(supplies, rollup.getSuppliesCostsGp());
                loss = safeAdd(loss, rollup.getLossCostsGp());
                active = nonNegativeAdd(active, rollup.getActiveMillis());
                kills = safeAddCount(kills, rollup.getKills());
                deaths = safeAddCount(deaths, rollup.getDeaths());
                starts = safeAddCount(starts, rollup.getSessionStarts());
                runs = safeAddCount(runs, rollup.getRunStarts());
                pvpKills = safeAddCount(pvpKills, rollup.getPvpKills());
                pvpDeaths = safeAddCount(pvpDeaths, rollup.getPvpDeaths());
                pvpKillNet = safeAdd(pvpKillNet, rollup.getPvpKillNetGp());
                pvpLoss = nonNegativeAdd(pvpLoss, rollup.getPvpLossGp());
                bestKill = Math.max(bestKill, rollup.getPvpBestKillGp());
                largestLoss = Math.max(largestLoss, rollup.getPvpLargestLossGp());
                gainedTruncated |= rollup.isGainedItemsTruncated();
                costTruncated |= rollup.isCostItemsTruncated();
                for (DailyRollup.ActivityTotal activity : rollup.getActivities().values())
                {
                    String key = activity.getActivityName().toLowerCase(Locale.ROOT);
                    MutableActivity value = activityMap.computeIfAbsent(key,
                        ignored -> new MutableActivity(activity.getActivityName()));
                    value.netGp = safeAdd(value.netGp, activity.getNetGp());
                    value.activeMillis = nonNegativeAdd(value.activeMillis, activity.getActiveMillis());
                    value.sessionCountAvailable &= activity.isSessionCountAvailable();
                    value.sessionIds.addAll(activity.getSourceSessionIds());
                }
                addItems(gainedMap, rollup.getGainedItemTotals());
                addItems(costMap, rollup.getCostItemTotals());
                if (rollup.getCoverage(DailyRollup.Dimension.HOURLY_BUCKETS)
                    != DailyRollup.Coverage.UNAVAILABLE)
                {
                    long[] hourlyNet = rollup.getHourlyNetGp();
                    long[] hourlyActive = rollup.getHourlyActiveMillis();
                    for (int hour = 0; hour < DailyRollup.HOURLY_BUCKET_COUNT; hour++)
                    {
                        hourlyNetBuckets[hour] = safeAdd(hourlyNetBuckets[hour], hourlyNet[hour]);
                        hourlyActiveBuckets[hour] = nonNegativeAdd(hourlyActiveBuckets[hour], hourlyActive[hour]);
                        if (hourlyActive[hour] > 0L && (bestHour == null
                            || hourlyNet[hour] > bestHour.getNetGp()
                            || (hourlyNet[hour] == bestHour.getNetGp()
                                && (rollup.getDate().isBefore(bestHour.getDate())
                                    || (rollup.getDate().equals(bestHour.getDate())
                                        && hour < bestHour.getHour())))))
                        {
                            bestHour = BestHour.of(rollup.getDate(), hour, rollup.getZoneId(),
                                hourlyNet[hour], hourlyActive[hour]);
                        }
                    }
                }
                long[] dayNet = rollup.getFourHourNetGp();
                long[] dayActive = rollup.getFourHourActiveMillis();
                for (int i = 0; i < DailyRollup.FOUR_HOUR_BUCKET_COUNT; i++)
                {
                    netBuckets[i] = safeAdd(netBuckets[i], dayNet[i]);
                    activeBuckets[i] = nonNegativeAdd(activeBuckets[i], dayActive[i]);
                }
            }
            if (!any) return empty();
            Map<String, DailyRollup.ActivityTotal> activities = new LinkedHashMap<>();
            for (Map.Entry<String, MutableActivity> entry : activityMap.entrySet())
            {
                MutableActivity value = entry.getValue();
                activities.put(value.name, new DailyRollup.ActivityTotal(value.name, value.netGp,
                    value.activeMillis, value.sessionIds, value.sessionCountAvailable));
            }
            List<ActivityInsight> topActivities = new ArrayList<>();
            long positiveNet = Math.max(0L, safeSubtract(revenue, costs));
            boolean positiveSharesAvailable = coverage.get(DailyRollup.Dimension.ACTIVITIES)
                == DailyRollup.Coverage.COMPLETE
                && coverage.get(DailyRollup.Dimension.ACCOUNTING) == DailyRollup.Coverage.COMPLETE
                && positiveNet > 0L;
            boolean sessionCountsAvailable = coverage.get(DailyRollup.Dimension.ACTIVITY_SESSION_COUNTS)
                == DailyRollup.Coverage.COMPLETE;
            for (MutableActivity value : activityMap.values())
            {
                Double share = positiveSharesAvailable
                    ? Math.max(0L, value.netGp) / (double) positiveNet : null;
                topActivities.add(new ActivityInsight(value.name, value.netGp,
                    value.activeMillis, value.sessionIds.size(),
                    sessionCountsAvailable && value.sessionCountAvailable, share, true));
            }
            topActivities.sort(Comparator.comparingLong(ActivityInsight::getNetGp).reversed()
                .thenComparing(ActivityInsight::getName, String.CASE_INSENSITIVE_ORDER));
            Map<String, DailyRollup.ItemTotal> gained = boundedItems(gainedMap,
                DailyRollup.Dimension.GAINED_ITEMS, coverage);
            Map<String, DailyRollup.ItemTotal> spent = boundedItems(costMap,
                DailyRollup.Dimension.COST_ITEMS, coverage);
            gainedTruncated |= gainedMap.size() > DailyRollup.TOP_ITEM_LIMIT;
            costTruncated |= costMap.size() > DailyRollup.TOP_ITEM_LIMIT;
            if (!any || coverage.get(DailyRollup.Dimension.HOURLY_BUCKETS)
                != DailyRollup.Coverage.COMPLETE || bestHour == null)
                bestHour = BestHour.unavailable(any);
            return new Window(revenue, costs, supplies, loss, active, kills, deaths, starts, runs,
                pvpKills, pvpDeaths, pvpKillNet, pvpLoss, bestKill, largestLoss, activities,
                gained, spent, netBuckets, activeBuckets, hourlyNetBuckets, hourlyActiveBuckets,
                gainedTruncated, costTruncated, SessionHighlight.unavailable(false),
                SessionHighlight.unavailable(false), BiggestDrop.unavailable(false), bestHour,
                topActivities, null, any, coverage, null);
        }

        private static void addItems(Map<String, MutableItem> target,
            Map<String, DailyRollup.ItemTotal> source)
        {
            for (DailyRollup.ItemTotal item : source.values())
            {
                String key = item.getItemId() > 0 ? "id:" + item.getItemId()
                    : "name:" + item.getItemName().toLowerCase(Locale.ROOT);
                MutableItem value = target.computeIfAbsent(key,
                    ignored -> new MutableItem(item.getItemId(), item.getItemName()));
                value.quantity = nonNegativeAdd(value.quantity, item.getQuantity());
                value.valueGp = nonNegativeAdd(value.valueGp, item.getValueGp());
            }
        }

        private static Map<String, DailyRollup.ItemTotal> boundedItems(
            Map<String, MutableItem> source, DailyRollup.Dimension dimension,
            EnumMap<DailyRollup.Dimension, DailyRollup.Coverage> coverage)
        {
            List<MutableItem> sorted = new ArrayList<>(source.values());
            sorted.sort(Comparator.comparingLong((MutableItem item) -> item.valueGp).reversed()
                .thenComparing(item -> item.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(item -> item.id));
            if (sorted.size() > DailyRollup.TOP_ITEM_LIMIT)
            {
                sorted = new ArrayList<>(sorted.subList(0, DailyRollup.TOP_ITEM_LIMIT));
                coverage.put(dimension, DailyRollup.Coverage.weakest(
                    coverage.get(dimension), DailyRollup.Coverage.PARTIAL));
            }
            Map<String, DailyRollup.ItemTotal> result = new LinkedHashMap<>();
            for (MutableItem item : sorted)
            {
                String key = item.id > 0 ? "id:" + item.id
                    : "name:" + item.name.toLowerCase(Locale.ROOT);
                result.put(key, new DailyRollup.ItemTotal(item.id, item.name,
                    item.quantity, item.valueGp));
            }
            return result;
        }

        private static final class MutableActivity
        {
            private final String name;
            private long netGp;
            private long activeMillis;
            private final java.util.Set<String> sessionIds = new java.util.LinkedHashSet<>();
            private boolean sessionCountAvailable = true;
            private MutableActivity(String name) { this.name = name; }
        }

        private static final class MutableItem
        {
            private final int id;
            private final String name;
            private long quantity;
            private long valueGp;
            private MutableItem(int id, String name) { this.id = id; this.name = name; }
        }

        private static Window empty()
        {
            EnumMap<DailyRollup.Dimension, DailyRollup.Coverage> coverage =
                new EnumMap<>(DailyRollup.Dimension.class);
            for (DailyRollup.Dimension dimension : DailyRollup.Dimension.values())
                coverage.put(dimension, DailyRollup.Coverage.UNAVAILABLE);
            return new Window(0L, 0L, 0L, 0L, 0L, 0, 0, 0, 0, 0, 0,
                0L, 0L, 0L, 0L, Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), new long[DailyRollup.FOUR_HOUR_BUCKET_COUNT],
                new long[DailyRollup.FOUR_HOUR_BUCKET_COUNT],
                new long[DailyRollup.HOURLY_BUCKET_COUNT],
                new long[DailyRollup.HOURLY_BUCKET_COUNT], false, false,
                SessionHighlight.unavailable(false), SessionHighlight.unavailable(false),
                BiggestDrop.unavailable(false), BestHour.unavailable(false),
                Collections.emptyList(), null, false, coverage, null);
        }
    }

    private static long safeSubtract(long left, long right)
    {
        try { return Math.subtractExact(left, right); }
        catch (ArithmeticException ex) { return right >= 0L ? Long.MIN_VALUE : Long.MAX_VALUE; }
    }

    private static long safeAdd(long left, long right)
    {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ex) { return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE; }
    }

    private static long nonNegativeAdd(long left, long right)
    {
        return Math.max(0L, safeAdd(left, Math.max(0L, right)));
    }

    private static int safeAddCount(int left, int right)
    {
        long sum = (long) Math.max(0, left) + Math.max(0, right);
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
