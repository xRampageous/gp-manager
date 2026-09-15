package com.gpmanager.model;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable profile-level daily analytics. A rollup is scoped to a local
 * calendar day and its zone; use {@link Builder#merge(DailyRollup)} to combine
 * non-overlapping session slices without retaining their transactions.
 *
 * <p>Each data dimension carries its own coverage. {@link Coverage#PARTIAL}
 * means that the available values are useful but do not represent the whole
 * local day. A missing dimension is never silently presented as a complete
 * zero.</p>
 */
public final class DailyRollup
{
    public static final int FOUR_HOUR_BUCKET_COUNT = 6;
    public static final int HOURLY_BUCKET_COUNT = 24;
    public static final int TOP_ITEM_LIMIT = 20;

    public enum Dimension
    {
        ACCOUNTING,
        COST_SPLIT,
        ACTIVE_TIME,
        EVENT_COUNTS,
        ACTIVITIES,
        GAINED_ITEMS,
        COST_ITEMS,
        FOUR_HOUR_BUCKETS,
        PVP,
        CATEGORIES,
        PVM_ENCOUNTERS,
        PVM_ENCOUNTER_SOURCES,
        HOURLY_BUCKETS,
        ACTIVITY_SESSION_COUNTS,
        /** Exact Free play exclusion / named session-start attribution introduced in schema 23. */
        NAMED_SESSION_STARTS
    }

    public enum Coverage
    {
        UNAVAILABLE,
        PARTIAL,
        COMPLETE;

        static Coverage weakest(Coverage left, Coverage right)
        {
            if (left == null) return right == null ? UNAVAILABLE : right;
            if (right == null) return left;
            return left.ordinal() < right.ordinal() ? left : right;
        }
    }

    /** Immutable gained-item or cost-item summary. Quantities are magnitudes. */
    public static final class ItemTotal
    {
        private int itemId;
        private String itemName;
        private long quantity;
        private long valueGp;

        @SuppressWarnings("unused")
        private ItemTotal()
        {
            this(0, "Unknown item", 0L, 0L);
        }

        public ItemTotal(int itemId, String itemName, long quantity, long valueGp)
        {
            this.itemId = Math.max(0, itemId);
            this.itemName = normalizeItem(itemName);
            this.quantity = Math.max(0L, quantity);
            this.valueGp = Math.max(0L, valueGp);
        }

        public int getItemId() { return itemId; }
        public String getItemName() { return itemName; }
        public long getQuantity() { return quantity; }
        public long getValueGp() { return valueGp; }
    }

    /** Immutable net and active-time contribution for one activity. */
    public static final class ActivityTotal
    {
        private String activityName;
        private long netGp;
        private long activeMillis;
        private List<String> sourceSessionIds = Collections.emptyList();
        private boolean sessionCountAvailable;

        @SuppressWarnings("unused")
        private ActivityTotal()
        {
            this("General", 0L, 0L);
        }

        public ActivityTotal(String activityName, long netGp, long activeMillis)
        {
            this(activityName, netGp, activeMillis, Collections.emptyList(), false);
        }

        ActivityTotal(String activityName, long netGp, long activeMillis,
            Iterable<String> sourceSessionIds, boolean sessionCountAvailable)
        {
            this.activityName = normalizeActivity(activityName);
            this.netGp = netGp;
            this.activeMillis = Math.max(0L, activeMillis);
            List<String> ids = new ArrayList<>();
            if (sourceSessionIds != null)
            {
                for (String id : sourceSessionIds)
                {
                    if (id != null && !id.trim().isEmpty() && !ids.contains(id)) ids.add(id);
                }
            }
            this.sourceSessionIds = Collections.unmodifiableList(ids);
            this.sessionCountAvailable = sessionCountAvailable;
        }

        public String getActivityName() { return activityName; }
        public long getNetGp() { return netGp; }
        public long getActiveMillis() { return activeMillis; }
        public int getSessionCount() { return sourceSessionIds == null ? 0 : sourceSessionIds.size(); }
        public boolean isSessionCountAvailable() { return sessionCountAvailable; }
        public List<String> getSourceSessionIds()
        {
            return Collections.unmodifiableList(new ArrayList<>(
                sourceSessionIds == null ? Collections.emptyList() : sourceSessionIds));
        }
    }

    /** Immutable category net and active-time contribution for one day. */
    public static final class CategoryTotal
    {
        private SessionCategory category;
        private long netGp;
        private long activeMillis;

        @SuppressWarnings("unused")
        private CategoryTotal()
        {
            this(SessionCategory.OTHER, 0L, 0L);
        }

        public CategoryTotal(SessionCategory category, long netGp, long activeMillis)
        {
            this.category = category == null ? SessionCategory.OTHER : category;
            this.netGp = netGp;
            this.activeMillis = Math.max(0L, activeMillis);
        }

        public SessionCategory getCategory() { return category; }
        public long getNetGp() { return netGp; }
        public long getActiveMillis() { return activeMillis; }
    }

    // Strings keep the persisted form independent of Gson support for java.time internals.
    private String date;
    private String zoneId;
    private long revenueGp;
    private long costsGp;
    private long suppliesCostsGp;
    /** Non-supplies costs, including CostKind.LOSS and CostKind.MARKET. */
    private long lossCostsGp;
    private long activeMillis;
    private int kills;
    private int deaths;
    private int sessionStarts;
    /** Exact named-session starts; missing old fields remain unavailable by coverage. */
    private int namedSessionStarts;
    private int runStarts;
    private Map<String, ActivityTotal> activities = Collections.emptyMap();
    private Map<SessionCategory, CategoryTotal> categoryTotals = Collections.emptyMap();
    /** Bounded, source-ranked NPC encounters retained independently of receipt summaries. */
    private EncounterTotals pvmEncounterTotals = new EncounterTotals();
    private Map<String, ItemTotal> gainedItems = Collections.emptyMap();
    private Map<String, ItemTotal> costItems = Collections.emptyMap();
    private long[] fourHourNetGp = new long[FOUR_HOUR_BUCKET_COUNT];
    private long[] fourHourActiveMillis = new long[FOUR_HOUR_BUCKET_COUNT];
    /** New local-hour buckets; missing arrays remain unavailable on old schemas. */
    private long[] hourlyNetGp = new long[HOURLY_BUCKET_COUNT];
    private long[] hourlyActiveMillis = new long[HOURLY_BUCKET_COUNT];
    private boolean gainedItemsTruncated;
    private boolean costItemsTruncated;
    private List<String> sourceSessionIds = Collections.emptyList();
    private int pvpKills;
    private int pvpDeaths;
    private long pvpKillNetGp;
    private long pvpLossGp;
    private long pvpBestKillGp;
    private long pvpLargestLossGp;
    private Map<Dimension, Coverage> coverage = emptyCoverage();

    private DailyRollup(Builder builder)
    {
        date = builder.date.toString();
        zoneId = builder.zone.getId();
        revenueGp = builder.revenueGp;
        costsGp = builder.costsGp;
        suppliesCostsGp = builder.suppliesCostsGp;
        lossCostsGp = builder.lossCostsGp;
        activeMillis = builder.activeMillis;
        kills = builder.kills;
        deaths = builder.deaths;
        sessionStarts = builder.sessionStarts;
        namedSessionStarts = builder.namedSessionStarts;
        runStarts = builder.runStarts;
        activities = immutableActivities(builder.activities);
        categoryTotals = immutableCategories(builder.categoryTotals);
        pvmEncounterTotals = builder.pvmEncounterTotals.copy();
        gainedItems = immutableItems(builder.gainedItems);
        costItems = immutableItems(builder.costItems);
        fourHourNetGp = builder.fourHourNetGp.clone();
        fourHourActiveMillis = builder.fourHourActiveMillis.clone();
        hourlyNetGp = builder.hourlyNetGp.clone();
        hourlyActiveMillis = builder.hourlyActiveMillis.clone();
        gainedItemsTruncated = builder.gainedItemsTruncated;
        costItemsTruncated = builder.costItemsTruncated;
        sourceSessionIds = Collections.unmodifiableList(new ArrayList<>(builder.sourceSessionIds));
        pvpKills = builder.pvpKills;
        pvpDeaths = builder.pvpDeaths;
        pvpKillNetGp = builder.pvpKillNetGp;
        pvpLossGp = builder.pvpLossGp;
        pvpBestKillGp = builder.pvpBestKillGp;
        pvpLargestLossGp = builder.pvpLargestLossGp;
        EnumMap<Dimension, Coverage> copiedCoverage = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values())
        {
            copiedCoverage.put(dimension, builder.coverage.getOrDefault(dimension, Coverage.UNAVAILABLE));
        }
        coverage = Collections.unmodifiableMap(copiedCoverage);
    }

    /** Gson constructor. Read models are otherwise created through {@link #builder}. */
    private DailyRollup()
    {
        date = "1970-01-01";
        zoneId = "UTC";
    }

    public static Builder builder(LocalDate date, ZoneId zone)
    {
        return new Builder(date, zone);
    }

    public LocalDate getDate() { return LocalDate.parse(date); }
    public String getDay() { return date == null ? "" : date; }
    public ZoneId getZone() { return ZoneId.of(zoneId == null ? "UTC" : zoneId); }
    public String getZoneId() { return zoneId == null ? "UTC" : zoneId; }
    public long getRevenueGp() { return revenueGp; }
    public long getCostsGp() { return costsGp; }
    public long getSuppliesCostsGp() { return suppliesCostsGp; }
    public long getLossCostsGp() { return lossCostsGp; }
    /** Alias matching the existing Supplies/Other split read models. */
    public long getOtherCostsGp() { return lossCostsGp; }
    public long getNetGp() { return safeSubtract(revenueGp, costsGp); }
    public long getActiveMillis() { return activeMillis; }
    public int getKills() { return kills; }
    public int getDeaths() { return deaths; }
    public int getSessionStarts() { return sessionStarts; }
    public int getNamedSessionStarts() { return namedSessionStarts; }
    public int getRunStarts() { return runStarts; }
    public int getPvpKills() { return pvpKills; }
    public int getPvpDeaths() { return pvpDeaths; }
    public long getPvpKillNetGp() { return pvpKillNetGp; }
    public long getPvpLossGp() { return pvpLossGp; }
    public long getPvpBestKillGp() { return pvpBestKillGp; }
    public long getPvpLargestLossGp() { return pvpLargestLossGp; }

    public Coverage getCoverage(Dimension dimension)
    {
        return dimension == null || coverage == null ? Coverage.UNAVAILABLE
            : coverage.getOrDefault(dimension, Coverage.UNAVAILABLE);
    }

    public boolean isAvailable(Dimension dimension)
    {
        return getCoverage(dimension) != Coverage.UNAVAILABLE;
    }

    public boolean isComplete(Dimension dimension)
    {
        return getCoverage(dimension) == Coverage.COMPLETE;
    }

    public Map<Dimension, Coverage> getCoverageByDimension()
    {
        EnumMap<Dimension, Coverage> result = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) result.put(dimension, getCoverage(dimension));
        return Collections.unmodifiableMap(result);
    }
    public Map<String, ActivityTotal> getActivities()
    {
        return immutableActivities(activities == null ? Collections.emptyMap() : activities);
    }
    public Map<SessionCategory, CategoryTotal> getCategoryTotals()
    {
        return immutableCategories(categoryTotals == null
            ? Collections.emptyMap() : categoryTotals);
    }
    public EncounterTotals getPvmEncounterTotals()
    {
        return pvmEncounterTotals == null ? new EncounterTotals() : pvmEncounterTotals.copy();
    }
    public long getPvmEncounterCount() { return getPvmEncounterTotals().getTotalEncounterCount(); }
    public String getTopPvmSource()
    {
        if (!isComplete(Dimension.PVM_ENCOUNTER_SOURCES)) return "";
        List<EncounterTotals.SourceSnapshot> sources = getPvmEncounterTotals().getNamedSources();
        return sources.isEmpty() ? "" : sources.get(0).getSourceName();
    }
    public long getTopPvmSourceCount()
    {
        if (!isComplete(Dimension.PVM_ENCOUNTER_SOURCES)) return 0L;
        List<EncounterTotals.SourceSnapshot> sources = getPvmEncounterTotals().getNamedSources();
        return sources.isEmpty() ? 0L : sources.get(0).getEncounterCount();
    }
    public Map<String, ItemTotal> getGainedItemTotals()
    {
        return immutableItems(gainedItems == null ? Collections.emptyMap() : gainedItems);
    }
    public Map<String, ItemTotal> getCostItemTotals()
    {
        return immutableItems(costItems == null ? Collections.emptyMap() : costItems);
    }
    public List<ItemTotal> getTopGainedItems()
    {
        return topItems(gainedItems == null ? Collections.emptyMap() : gainedItems);
    }
    public List<ItemTotal> getTopCostItems()
    {
        return topItems(costItems == null ? Collections.emptyMap() : costItems);
    }
    public long[] getFourHourNetGp()
    {
        return getCoverage(Dimension.HOURLY_BUCKETS) == Coverage.COMPLETE
            ? collapseHourly(getHourlyNetGp()) : safeBuckets(fourHourNetGp);
    }
    public long[] getFourHourActiveMillis()
    {
        return getCoverage(Dimension.HOURLY_BUCKETS) == Coverage.COMPLETE
            ? collapseHourly(getHourlyActiveMillis()) : safeBuckets(fourHourActiveMillis);
    }
    public long[] getHourlyNetGp() { return safeHourlyBuckets(hourlyNetGp); }
    public long[] getHourlyActiveMillis() { return safeHourlyBuckets(hourlyActiveMillis); }
    public boolean isGainedItemsTruncated() { return gainedItemsTruncated; }
    public boolean isCostItemsTruncated() { return costItemsTruncated; }
    public List<String> getSourceSessionIds()
    {
        return Collections.unmodifiableList(new ArrayList<>(
            sourceSessionIds == null ? Collections.emptyList() : sourceSessionIds));
    }

    /** Compares persisted content so a rebuilt slice can fail closed if it diverges. */
    public boolean hasSameRecordedValues(DailyRollup other)
    {
        return hasSameRecordedValuesExceptCategories(other)
            && getSourceSessionIds().equals(other.getSourceSessionIds())
            && getCoverage(Dimension.CATEGORIES) == other.getCoverage(Dimension.CATEGORIES)
            && sameCategories(getCategoryTotals(), other.getCategoryTotals())
            && getCoverage(Dimension.PVM_ENCOUNTERS) == other.getCoverage(Dimension.PVM_ENCOUNTERS)
            && getCoverage(Dimension.PVM_ENCOUNTER_SOURCES)
                == other.getCoverage(Dimension.PVM_ENCOUNTER_SOURCES)
            && sameEncounterTotals(getPvmEncounterTotals(), other.getPvmEncounterTotals())
            && getCoverage(Dimension.HOURLY_BUCKETS) == other.getCoverage(Dimension.HOURLY_BUCKETS)
            && Arrays.equals(getHourlyNetGp(), other.getHourlyNetGp())
            && Arrays.equals(getHourlyActiveMillis(), other.getHourlyActiveMillis())
            && getCoverage(Dimension.ACTIVITY_SESSION_COUNTS)
                == other.getCoverage(Dimension.ACTIVITY_SESSION_COUNTS)
            && sameActivitySessionCounts(getActivities(), other.getActivities())
            && getCoverage(Dimension.NAMED_SESSION_STARTS)
                == other.getCoverage(Dimension.NAMED_SESSION_STARTS)
            && namedSessionStarts == other.namedSessionStarts;
    }

    /**
     * Compares the original financial/event/activity/item dimensions. Missing
     * newer dimensions must not downgrade otherwise matching accounting/time
     * data; callers reconcile those dimensions independently.
     */
    public boolean hasSameRecordedValuesExceptCategories(DailyRollup other)
    {
        if (other == null || !Objects.equals(date, other.date) || !Objects.equals(zoneId, other.zoneId)
            || revenueGp != other.revenueGp || costsGp != other.costsGp
            || suppliesCostsGp != other.suppliesCostsGp || lossCostsGp != other.lossCostsGp
            || activeMillis != other.activeMillis || kills != other.kills || deaths != other.deaths
            || sessionStarts != other.sessionStarts || runStarts != other.runStarts
            || pvpKills != other.pvpKills || pvpDeaths != other.pvpDeaths
            || pvpKillNetGp != other.pvpKillNetGp || pvpLossGp != other.pvpLossGp
            || pvpBestKillGp != other.pvpBestKillGp || pvpLargestLossGp != other.pvpLargestLossGp
            || !Arrays.equals(getFourHourNetGp(), other.getFourHourNetGp())
            || !Arrays.equals(getFourHourActiveMillis(), other.getFourHourActiveMillis())
            || !coverageExceptNewDimensions().equals(other.coverageExceptNewDimensions()))
        {
            return false;
        }
        return sameActivities(getActivities(), other.getActivities())
            && sameItems(getGainedItemTotals(), other.getGainedItemTotals())
            && sameItems(getCostItemTotals(), other.getCostItemTotals());
    }

    /**
     * Compares activity-to-session attribution when both rollups have exact
     * source-session coverage. Reconciliation callers can downgrade only this
     * dimension when the financial/activity totals still match.
     */
    public boolean hasSameActivitySessionCounts(DailyRollup other)
    {
        return other != null
            && getCoverage(Dimension.ACTIVITY_SESSION_COUNTS)
                == other.getCoverage(Dimension.ACTIVITY_SESSION_COUNTS)
            && (getCoverage(Dimension.ACTIVITY_SESSION_COUNTS) != Coverage.COMPLETE
                || sameActivitySessionCounts(getActivities(), other.getActivities()));
    }

    private Map<Dimension, Coverage> coverageExceptNewDimensions()
    {
        EnumMap<Dimension, Coverage> result = new EnumMap<>(getCoverageByDimension());
        result.remove(Dimension.CATEGORIES);
        result.remove(Dimension.PVM_ENCOUNTERS);
        result.remove(Dimension.PVM_ENCOUNTER_SOURCES);
        result.remove(Dimension.HOURLY_BUCKETS);
        result.remove(Dimension.ACTIVITY_SESSION_COUNTS);
        result.remove(Dimension.NAMED_SESSION_STARTS);
        return result;
    }

    private static boolean sameActivities(Map<String, ActivityTotal> left,
        Map<String, ActivityTotal> right)
    {
        if (left.size() != right.size()) return false;
        for (Map.Entry<String, ActivityTotal> entry : left.entrySet())
        {
            ActivityTotal other = right.get(entry.getKey());
            ActivityTotal value = entry.getValue();
            if (other == null || !value.activityName.equals(other.activityName)
                || value.netGp != other.netGp || value.activeMillis != other.activeMillis) return false;
        }
        return true;
    }

    private static boolean sameActivitySessionCounts(Map<String, ActivityTotal> left,
        Map<String, ActivityTotal> right)
    {
        if (left.size() != right.size()) return false;
        for (Map.Entry<String, ActivityTotal> entry : left.entrySet())
        {
            ActivityTotal other = right.get(entry.getKey());
            ActivityTotal value = entry.getValue();
            if (other == null || value.sessionCountAvailable != other.sessionCountAvailable
                || !value.getSourceSessionIds().equals(other.getSourceSessionIds())) return false;
        }
        return true;
    }

    private static boolean sameItems(Map<String, ItemTotal> left, Map<String, ItemTotal> right)
    {
        if (left.size() != right.size()) return false;
        for (Map.Entry<String, ItemTotal> entry : left.entrySet())
        {
            ItemTotal other = right.get(entry.getKey());
            ItemTotal value = entry.getValue();
            if (other == null || value.itemId != other.itemId
                || !value.itemName.equals(other.itemName) || value.quantity != other.quantity
                || value.valueGp != other.valueGp) return false;
        }
        return true;
    }

    private static boolean sameCategories(Map<SessionCategory, CategoryTotal> left,
        Map<SessionCategory, CategoryTotal> right)
    {
        if (left.size() != right.size()) return false;
        for (Map.Entry<SessionCategory, CategoryTotal> entry : left.entrySet())
        {
            CategoryTotal other = right.get(entry.getKey());
            CategoryTotal value = entry.getValue();
            if (other == null || value.category != other.category
                || value.netGp != other.netGp || value.activeMillis != other.activeMillis) return false;
        }
        return true;
    }

    private static boolean sameEncounterTotals(EncounterTotals left, EncounterTotals right)
    {
        if (left.getTotalEncounterCount() != right.getTotalEncounterCount()
            || left.getTotalLootValue() != right.getTotalLootValue()
            || left.isTotalLootValueKnown() != right.isTotalLootValueKnown()
            || left.getRemainder().getEncounterCount() != right.getRemainder().getEncounterCount()
            || left.getRemainder().getLootValue() != right.getRemainder().getLootValue()) return false;
        List<EncounterTotals.SourceSnapshot> leftSources = left.getNamedSources();
        List<EncounterTotals.SourceSnapshot> rightSources = right.getNamedSources();
        if (leftSources.size() != rightSources.size()) return false;
        for (int i = 0; i < leftSources.size(); i++)
        {
            EncounterTotals.SourceSnapshot a = leftSources.get(i);
            EncounterTotals.SourceSnapshot b = rightSources.get(i);
            if (!a.getSourceName().equalsIgnoreCase(b.getSourceName())
                || a.getEncounterCount() != b.getEncounterCount()
                || a.getLootValue() != b.getLootValue()
                || a.isLootValueKnown() != b.isLootValueKnown()
                || a.getBestStreak() != b.getBestStreak()
                || a.getLastSeenAt() != b.getLastSeenAt()) return false;
        }
        return true;
    }

    /**
     * Returns an accounting-filter-safe view when the current filter cannot be
     * applied to this already-compacted daily aggregate. Flow-derived values
     * are redacted instead of exposing raw totals that may include excluded
     * items. Independent time and event counts remain available with their
     * original coverage.
     */
    public DailyRollup withoutUnfilteredContributions()
    {
        Builder safe = builder(getDate(), getZone());
        for (String sessionId : getSourceSessionIds()) safe.addSourceSessionId(sessionId);
        safe.addActiveMillis(activeMillis);
        safe.addEventCounts(kills, deaths, sessionStarts, runStarts);
        safe.addNamedSessionStarts(namedSessionStarts);
        for (ActivityTotal activity : getActivities().values())
        {
            safe.addActivity(activity.getActivityName(), 0L, activity.getActiveMillis());
            if (activity.isSessionCountAvailable())
            {
                for (String sessionId : activity.getSourceSessionIds())
                    safe.addActivitySession(activity.getActivityName(), sessionId);
            }
        }
        for (CategoryTotal category : getCategoryTotals().values())
        {
            safe.addCategory(category.getCategory(), 0L, category.getActiveMillis());
        }
        safe.addPvmEncounters(getPvmEncounterTotals(),
            getCoverage(Dimension.PVM_ENCOUNTERS) != Coverage.UNAVAILABLE);
        safe.addFourHourBuckets(new long[FOUR_HOUR_BUCKET_COUNT], getFourHourActiveMillis());
        if (getCoverage(Dimension.HOURLY_BUCKETS) != Coverage.UNAVAILABLE)
            safe.addHourlyBuckets(new long[HOURLY_BUCKET_COUNT], getHourlyActiveMillis());
        safe.addPvp(pvpKills, pvpDeaths, 0L, 0L, 0L, 0L);
        for (Dimension dimension : Dimension.values())
        {
            safe.coverage(dimension, getCoverage(dimension));
        }
        safe.coverage(Dimension.ACCOUNTING, Coverage.UNAVAILABLE)
            .coverage(Dimension.COST_SPLIT, Coverage.UNAVAILABLE)
            .coverage(Dimension.ACTIVITIES, Coverage.UNAVAILABLE)
            .coverage(Dimension.GAINED_ITEMS, Coverage.UNAVAILABLE)
            .coverage(Dimension.COST_ITEMS, Coverage.UNAVAILABLE)
            .coverage(Dimension.FOUR_HOUR_BUCKETS, Coverage.UNAVAILABLE)
            .coverage(Dimension.HOURLY_BUCKETS, Coverage.UNAVAILABLE)
            .coverage(Dimension.PVP, Coverage.UNAVAILABLE)
            .coverage(Dimension.CATEGORIES, Coverage.UNAVAILABLE);
        return safe.build();
    }

    private static long[] safeBuckets(long[] values)
    {
        if (values == null || values.length != FOUR_HOUR_BUCKET_COUNT)
        {
            return new long[FOUR_HOUR_BUCKET_COUNT];
        }
        return values.clone();
    }

    private static long[] safeHourlyBuckets(long[] values)
    {
        if (values == null || values.length != HOURLY_BUCKET_COUNT)
            return new long[HOURLY_BUCKET_COUNT];
        return values.clone();
    }

    private static long[] collapseHourly(long[] values)
    {
        long[] result = new long[FOUR_HOUR_BUCKET_COUNT];
        if (values == null || values.length != HOURLY_BUCKET_COUNT) return result;
        for (int hour = 0; hour < HOURLY_BUCKET_COUNT; hour++)
            result[hour / 4] = safeAdd(result[hour / 4], values[hour]);
        return result;
    }

    private static Map<String, ActivityTotal> immutableActivities(Map<String, ActivityTotal> input)
    {
        Map<String, ActivityTotal> result = new LinkedHashMap<>();
        for (Map.Entry<String, ActivityTotal> entry : input.entrySet())
        {
            ActivityTotal value = entry.getValue();
            if (value == null) continue;
            result.put(entry.getKey(), new ActivityTotal(value.activityName, value.netGp,
                value.activeMillis, value.sourceSessionIds, value.sessionCountAvailable));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<SessionCategory, CategoryTotal> immutableCategories(
        Map<SessionCategory, CategoryTotal> input)
    {
        EnumMap<SessionCategory, CategoryTotal> result = new EnumMap<>(SessionCategory.class);
        for (Map.Entry<SessionCategory, CategoryTotal> entry : input.entrySet())
        {
            SessionCategory category = entry.getKey();
            CategoryTotal value = entry.getValue();
            if (category == null || category == SessionCategory.ALL || value == null) continue;
            result.put(category, new CategoryTotal(category, value.netGp, value.activeMillis));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, ItemTotal> immutableItems(Map<String, ItemTotal> input)
    {
        Map<String, ItemTotal> result = new LinkedHashMap<>();
        for (Map.Entry<String, ItemTotal> entry : input.entrySet())
        {
            ItemTotal value = entry.getValue();
            if (value == null) continue;
            result.put(entry.getKey(), new ItemTotal(value.itemId, value.itemName,
                value.quantity, value.valueGp));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<ItemTotal> topItems(Map<String, ItemTotal> source)
    {
        List<ItemTotal> sorted = new ArrayList<>(source.values());
        sorted.sort(Comparator.comparingLong(ItemTotal::getValueGp).reversed()
            .thenComparing(ItemTotal::getItemName, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(ItemTotal::getItemId));
        if (sorted.size() > TOP_ITEM_LIMIT)
        {
            sorted = new ArrayList<>(sorted.subList(0, TOP_ITEM_LIMIT));
        }
        return Collections.unmodifiableList(sorted);
    }

    private static String normalizeActivity(String value)
    {
        return value == null || value.trim().isEmpty() ? "General" : value.trim();
    }

    private static String normalizeItem(String value)
    {
        return value == null || value.trim().isEmpty() ? "Unknown item" : value.trim();
    }

    private static long safeAdd(long left, long right)
    {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ex) { return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE; }
    }

    private static long safeSubtract(long left, long right)
    {
        try { return Math.subtractExact(left, right); }
        catch (ArithmeticException ex) { return right < 0L ? Long.MAX_VALUE : Long.MIN_VALUE; }
    }

    private static long nonNegativeAdd(long left, long right)
    {
        return safeAdd(left, Math.max(0L, right));
    }

    private static int safeAddCount(int left, int right)
    {
        long result = (long) Math.max(0, left) + Math.max(0, right);
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static String itemKey(int itemId, String name)
    {
        return itemId > 0 ? "id:" + itemId : "name:" + normalizeItem(name).toLowerCase(Locale.ROOT);
    }

    private static List<String> unionActivityNames(Map<String, Long> netByActivity,
        Map<String, Long> activeByActivity)
    {
        List<String> result = new ArrayList<>();
        if (netByActivity != null)
        {
            for (String name : netByActivity.keySet())
                if (name != null && !containsActivityIgnoreCase(result, name)) result.add(name);
        }
        if (activeByActivity != null)
        {
            for (String name : activeByActivity.keySet())
                if (name != null && !containsActivityIgnoreCase(result, name)) result.add(name);
        }
        return result;
    }

    private static boolean containsActivityIgnoreCase(Iterable<String> names, String requested)
    {
        if (requested == null || names == null) return false;
        for (String name : names)
            if (name != null && name.equalsIgnoreCase(requested)) return true;
        return false;
    }

    private static Map<Dimension, Coverage> emptyCoverage()
    {
        EnumMap<Dimension, Coverage> result = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) result.put(dimension, Coverage.UNAVAILABLE);
        return result;
    }

    public static final class Builder
    {
        private final LocalDate date;
        private final ZoneId zone;
        private long revenueGp;
        private long costsGp;
        private long suppliesCostsGp;
        private long lossCostsGp;
        private long activeMillis;
        private int kills;
        private int deaths;
        private int sessionStarts;
        private int namedSessionStarts;
        private int runStarts;
        private final Map<String, ActivityTotal> activities = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final EnumMap<SessionCategory, CategoryTotal> categoryTotals =
            new EnumMap<>(SessionCategory.class);
        private final EncounterTotals pvmEncounterTotals = new EncounterTotals();
        private final Map<String, ItemTotal> gainedItems = new LinkedHashMap<>();
        private final Map<String, ItemTotal> costItems = new LinkedHashMap<>();
        private final Set<String> sourceSessionIds = new TreeSet<>();
        private final long[] fourHourNetGp = new long[FOUR_HOUR_BUCKET_COUNT];
        private final long[] fourHourActiveMillis = new long[FOUR_HOUR_BUCKET_COUNT];
        private final long[] hourlyNetGp = new long[HOURLY_BUCKET_COUNT];
        private final long[] hourlyActiveMillis = new long[HOURLY_BUCKET_COUNT];
        private boolean gainedItemsTruncated;
        private boolean costItemsTruncated;
        private int pvpKills;
        private int pvpDeaths;
        private long pvpKillNetGp;
        private long pvpLossGp;
        private long pvpBestKillGp;
        private long pvpLargestLossGp;
        private final EnumMap<Dimension, Coverage> coverage = new EnumMap<>(Dimension.class);

        private Builder(LocalDate date, ZoneId zone)
        {
            this.date = Objects.requireNonNull(date, "date");
            this.zone = Objects.requireNonNull(zone, "zone");
        }

        /** Add a session slice's non-negative counted revenue and costs. */
        public Builder addAccounting(long revenueGp, long costsGp)
        {
            this.revenueGp = nonNegativeAdd(this.revenueGp, revenueGp);
            this.costsGp = nonNegativeAdd(this.costsGp, costsGp);
            recordCoverage(Dimension.ACCOUNTING, Coverage.COMPLETE);
            return this;
        }

        /** Add the cost split: lossCosts is the non-supplies (other) bucket. */
        public Builder addCostSplit(long suppliesCostsGp, long lossCostsGp)
        {
            this.suppliesCostsGp = nonNegativeAdd(this.suppliesCostsGp, suppliesCostsGp);
            this.lossCostsGp = nonNegativeAdd(this.lossCostsGp, lossCostsGp);
            recordCoverage(Dimension.COST_SPLIT, Coverage.COMPLETE);
            return this;
        }

        public Builder addActiveMillis(long value)
        {
            activeMillis = nonNegativeAdd(activeMillis, value);
            recordCoverage(Dimension.ACTIVE_TIME, Coverage.COMPLETE);
            return this;
        }

        /** Records a durable source so persisted day snapshots can reconcile safely. */
        public Builder addSourceSessionId(String sessionId)
        {
            if (sessionId != null && !sessionId.trim().isEmpty())
            {
                sourceSessionIds.add(sessionId.trim());
            }
            return this;
        }

        public Builder addEventCounts(int kills, int deaths, int sessionStarts, int runStarts)
        {
            this.kills = safeAddCount(this.kills, kills);
            this.deaths = safeAddCount(this.deaths, deaths);
            this.sessionStarts = safeAddCount(this.sessionStarts, sessionStarts);
            this.runStarts = safeAddCount(this.runStarts, runStarts);
            recordCoverage(Dimension.EVENT_COUNTS, Coverage.COMPLETE);
            return this;
        }

        /** Count a session start only when its event timestamp falls on this local day. */
        public Builder addSessionStart(long timestampEpochMillis)
        {
            if (isOnDay(timestampEpochMillis)) addEventCounts(0, 0, 1, 0);
            return this;
        }

        /**
         * Records a typed named-session start. Free play contributes an exact
         * zero; unknown ownership contributes no guessed count and marks the
         * dimension unavailable for the day.
         */
        public Builder addNamedSessionStart(long timestampEpochMillis, boolean named, boolean ownerKnown)
        {
            if (!isOnDay(timestampEpochMillis)) return this;
            if (!ownerKnown)
            {
                recordCoverage(Dimension.NAMED_SESSION_STARTS, Coverage.UNAVAILABLE);
                return this;
            }
            if (named) namedSessionStarts = safeAddCount(namedSessionStarts, 1);
            recordCoverage(Dimension.NAMED_SESSION_STARTS, Coverage.COMPLETE);
            return this;
        }

        /** Merges an already-attributed named-session start dimension. */
        public Builder addNamedSessionStarts(int value)
        {
            namedSessionStarts = safeAddCount(namedSessionStarts, value);
            recordCoverage(Dimension.NAMED_SESSION_STARTS, Coverage.COMPLETE);
            return this;
        }

        /** Count a run start only when its event timestamp falls on this local day. */
        public Builder addRunStart(long timestampEpochMillis)
        {
            if (isOnDay(timestampEpochMillis)) addEventCounts(0, 0, 0, 1);
            return this;
        }

        public Builder addActivity(String name, long netGp, long activeMillis)
        {
            String key = normalizeActivity(name);
            ActivityTotal current = activities.get(key);
            long combinedNet = safeAdd(current == null ? 0L : current.netGp, netGp);
            long combinedMillis = nonNegativeAdd(current == null ? 0L : current.activeMillis, activeMillis);
            activities.put(key, new ActivityTotal(key, combinedNet, combinedMillis,
                current == null ? Collections.emptyList() : current.getSourceSessionIds(),
                current != null && current.sessionCountAvailable));
            recordCoverage(Dimension.ACTIVITIES, Coverage.COMPLETE);
            return this;
        }

        /** Records this session's unique contribution to one activity bucket. */
        public Builder addActivitySession(String name, String sessionId)
        {
            if (sessionId == null || sessionId.trim().isEmpty()) return this;
            ActivityTotal current = activities.get(normalizeActivity(name));
            if (current == null) current = new ActivityTotal(name, 0L, 0L);
            List<String> ids = new ArrayList<>(current.getSourceSessionIds());
            if (!ids.contains(sessionId)) ids.add(sessionId);
            activities.put(current.activityName, new ActivityTotal(current.activityName,
                current.netGp, current.activeMillis, ids, true));
            recordCoverage(Dimension.ACTIVITY_SESSION_COUNTS, Coverage.COMPLETE);
            return this;
        }

        /** Accept the aggregate activity map shape used by the persisted daily summaries. */
        public Builder addActivityNet(Map<String, Long> netByActivity,
            Map<String, Long> activeMillisByActivity)
        {
            if (netByActivity == null && activeMillisByActivity == null) return this;
            Map<String, Long> nets = netByActivity == null ? Collections.emptyMap() : netByActivity;
            Map<String, Long> times = activeMillisByActivity == null
                ? Collections.emptyMap() : activeMillisByActivity;
            for (Map.Entry<String, Long> entry : nets.entrySet())
            {
                Long millis = getIgnoreCase(times, entry.getKey());
                addActivity(entry.getKey(), entry.getValue() == null ? 0L : entry.getValue(),
                    millis == null ? 0L : millis);
            }
            for (Map.Entry<String, Long> entry : times.entrySet())
            {
                if (!containsActivityIgnoreCase(nets, entry.getKey()))
                {
                    addActivity(entry.getKey(), 0L, entry.getValue() == null ? 0L : entry.getValue());
                }
            }
            if (activeMillisByActivity == null)
            {
                recordCoverage(Dimension.ACTIVITIES, Coverage.PARTIAL);
            }
            return this;
        }

        /** Records activity totals and the retained session identity for exact window deduplication. */
        public Builder addActivityNet(Map<String, Long> netByActivity,
            Map<String, Long> activeMillisByActivity, String sourceSessionId)
        {
            addActivityNet(netByActivity, activeMillisByActivity);
            if (sourceSessionId == null || sourceSessionId.trim().isEmpty())
            {
                recordCoverage(Dimension.ACTIVITY_SESSION_COUNTS, Coverage.UNAVAILABLE);
                return this;
            }
            for (String name : unionActivityNames(netByActivity, activeMillisByActivity))
                addActivitySession(name, sourceSessionId);
            recordCoverage(Dimension.ACTIVITY_SESSION_COUNTS, Coverage.COMPLETE);
            return this;
        }

        /** Add activity net only; per-activity time remains unavailable. */
        public Builder addActivityNet(Map<String, Long> netByActivity)
        {
            return addActivityNet(netByActivity, null);
        }

        public Builder addActivities(Map<String, ActivityTotal> aggregate)
        {
            if (aggregate == null) return this;
            for (ActivityTotal activity : aggregate.values())
            {
                if (activity != null)
                {
                    addActivity(activity.activityName, activity.netGp, activity.activeMillis);
                    if (activity.sessionCountAvailable)
                    {
                        for (String sessionId : activity.getSourceSessionIds())
                            addActivitySession(activity.activityName, sessionId);
                    }
                }
            }
            return this;
        }

        public Builder addCategory(SessionCategory category, long netGp, long activeMillis)
        {
            if (category == null || category == SessionCategory.ALL) return this;
            CategoryTotal current = categoryTotals.get(category);
            categoryTotals.put(category, new CategoryTotal(category,
                safeAdd(current == null ? 0L : current.netGp, netGp),
                nonNegativeAdd(current == null ? 0L : current.activeMillis, activeMillis)));
            recordCoverage(Dimension.CATEGORIES, Coverage.COMPLETE);
            return this;
        }

        /** Adds exact session-day NPC encounter totals; absent legacy data stays unavailable. */
        public Builder addPvmEncounters(EncounterTotals totals, boolean available)
        {
            if (available && totals != null)
            {
                pvmEncounterTotals.merge(totals);
                recordCoverage(Dimension.PVM_ENCOUNTERS, Coverage.COMPLETE);
                recordCoverage(Dimension.PVM_ENCOUNTER_SOURCES, totals.hasRemainder()
                    ? Coverage.PARTIAL : Coverage.COMPLETE);
            }
            else
            {
                recordCoverage(Dimension.PVM_ENCOUNTERS, Coverage.UNAVAILABLE);
                recordCoverage(Dimension.PVM_ENCOUNTER_SOURCES, Coverage.UNAVAILABLE);
            }
            return this;
        }

        public Builder addGainedItem(int itemId, String name, long quantity, long valueGp)
        {
            addItem(gainedItems, itemId, name, quantity, valueGp);
            recordCoverage(Dimension.GAINED_ITEMS, Coverage.COMPLETE);
            return this;
        }

        public Builder addGainedItems(Iterable<ItemTotal> items)
        {
            if (items == null) return this;
            for (ItemTotal item : items)
            {
                if (item != null) addGainedItem(item.itemId, item.itemName, item.quantity, item.valueGp);
            }
            return this;
        }

        public Builder addGainedItemDetails(Iterable<TrackingGainedItemDetail> items)
        {
            if (items == null) return this;
            for (TrackingGainedItemDetail item : items)
            {
                if (item != null)
                {
                    addGainedItem(item.getItemId(), item.getItemName(),
                        item.getQuantity(), item.getValue());
                }
            }
            return this;
        }

        /** Accept a name-to-value legacy aggregate; detail fields remain zero/unknown. */
        public Builder addGainedItems(Map<String, Long> valueByName)
        {
            if (valueByName == null) return this;
            for (Map.Entry<String, Long> item : valueByName.entrySet())
            {
                addGainedItem(0, item.getKey(), 0L, item.getValue() == null ? 0L : item.getValue());
            }
            return this;
        }

        public Builder addCostItem(int itemId, String name, long quantity, long valueGp)
        {
            addItem(costItems, itemId, name, quantity, valueGp);
            recordCoverage(Dimension.COST_ITEMS, Coverage.COMPLETE);
            return this;
        }

        public Builder addCostItems(Iterable<ItemTotal> items)
        {
            if (items == null) return this;
            for (ItemTotal item : items)
            {
                if (item != null) addCostItem(item.itemId, item.itemName, item.quantity, item.valueGp);
            }
            return this;
        }

        /** Accept a name-to-value legacy aggregate; detail fields remain zero/unknown. */
        public Builder addCostItems(Map<String, Long> valueByName)
        {
            if (valueByName == null) return this;
            for (Map.Entry<String, Long> item : valueByName.entrySet())
            {
                addCostItem(0, item.getKey(), 0L, item.getValue() == null ? 0L : item.getValue());
            }
            return this;
        }

        /** Set all six local-time buckets. Input arrays are copied immediately. */
        public Builder addFourHourBuckets(long[] netGp, long[] activeMillis)
        {
            if (netGp == null || activeMillis == null
                || netGp.length != FOUR_HOUR_BUCKET_COUNT
                || activeMillis.length != FOUR_HOUR_BUCKET_COUNT)
            {
                throw new IllegalArgumentException("Expected exactly six net and active-time buckets");
            }
            for (int i = 0; i < FOUR_HOUR_BUCKET_COUNT; i++)
            {
                fourHourNetGp[i] = safeAdd(fourHourNetGp[i], netGp[i]);
                fourHourActiveMillis[i] = nonNegativeAdd(fourHourActiveMillis[i], activeMillis[i]);
            }
            recordCoverage(Dimension.FOUR_HOUR_BUCKETS, Coverage.COMPLETE);
            return this;
        }

        /** Adds 24 profile-local hour buckets; old six-bucket data is never split into guessed hours. */
        public Builder addHourlyBuckets(long[] netGp, long[] activeMillis)
        {
            if (netGp == null || activeMillis == null
                || netGp.length != HOURLY_BUCKET_COUNT
                || activeMillis.length != HOURLY_BUCKET_COUNT)
                throw new IllegalArgumentException("Expected exactly 24 net and active-time buckets");
            for (int i = 0; i < HOURLY_BUCKET_COUNT; i++)
            {
                hourlyNetGp[i] = safeAdd(hourlyNetGp[i], netGp[i]);
                hourlyActiveMillis[i] = nonNegativeAdd(hourlyActiveMillis[i], activeMillis[i]);
            }
            recordCoverage(Dimension.HOURLY_BUCKETS, Coverage.COMPLETE);
            return this;
        }

        public Builder addPvp(int kills, int deaths, long killNetGp, long lossGp,
            long bestKillGp, long largestLossGp)
        {
            pvpKills = safeAddCount(pvpKills, kills);
            pvpDeaths = safeAddCount(pvpDeaths, deaths);
            pvpKillNetGp = safeAdd(pvpKillNetGp, killNetGp);
            pvpLossGp = nonNegativeAdd(pvpLossGp, lossGp);
            pvpBestKillGp = Math.max(pvpBestKillGp, Math.max(0L, bestKillGp));
            pvpLargestLossGp = Math.max(pvpLargestLossGp, Math.max(0L, largestLossGp));
            recordCoverage(Dimension.PVP, Coverage.COMPLETE);
            return this;
        }

        /** Add one durable PK encounter's precomputed accounting attribution. */
        public Builder addPkEncounter(PkEncounter encounter, long encounterNetGp,
            long encounterLossGp)
        {
            if (encounter == null || !isOnDay(encounter.getTimestampEpochMillis())) return this;
            boolean kill = encounter.getType() == PkEncounterType.KILL;
            long loss = kill ? 0L : Math.max(0L, encounterLossGp);
            addPvp(kill ? 1 : 0, kill ? 0 : 1, kill ? encounterNetGp : 0L, loss,
                kill ? Math.max(0L, encounterNetGp) : 0L,
                kill ? 0L : loss);
            return this;
        }

        /** Add an existing encounter-level summary without retaining its transactions. */
        public Builder addPkMetrics(PkMetrics metrics)
        {
            if (metrics == null) return this;
            addPvp(metrics.getKills(), metrics.getDeaths(), metrics.getTotalKillNet(),
                metrics.getTotalDeathLoss(), metrics.getBestKill(), metrics.getLargestDeathLoss());
            if (!metrics.isProjectionAvailable())
            {
                recordCoverage(Dimension.PVP, Coverage.UNAVAILABLE);
            }
            return this;
        }

        /** Downgrade a dimension when this slice is partial or unavailable. */
        public Builder coverage(Dimension dimension, Coverage value)
        {
            if (dimension == null) throw new IllegalArgumentException("dimension is required");
            recordCoverage(dimension, value == null ? Coverage.UNAVAILABLE : value);
            return this;
        }

        /** Merge one non-overlapping session/day slice, preserving worst coverage. */
        public Builder merge(DailyRollup slice)
        {
            Objects.requireNonNull(slice, "slice");
            if (!date.toString().equals(slice.getDay()) || !zone.getId().equals(slice.getZoneId()))
            {
                throw new IllegalArgumentException("Daily rollups must share date and zone");
            }
            revenueGp = nonNegativeAdd(revenueGp, slice.revenueGp);
            costsGp = nonNegativeAdd(costsGp, slice.costsGp);
            suppliesCostsGp = nonNegativeAdd(suppliesCostsGp, slice.suppliesCostsGp);
            lossCostsGp = nonNegativeAdd(lossCostsGp, slice.lossCostsGp);
            activeMillis = nonNegativeAdd(activeMillis, slice.activeMillis);
            kills = safeAddCount(kills, slice.kills);
            deaths = safeAddCount(deaths, slice.deaths);
            sessionStarts = safeAddCount(sessionStarts, slice.sessionStarts);
            namedSessionStarts = safeAddCount(namedSessionStarts, slice.namedSessionStarts);
            runStarts = safeAddCount(runStarts, slice.runStarts);
            for (ActivityTotal activity : slice.getActivities().values())
            {
                addActivity(activity.activityName, activity.netGp, activity.activeMillis);
                if (activity.sessionCountAvailable)
                {
                    for (String sessionId : activity.getSourceSessionIds())
                        addActivitySession(activity.activityName, sessionId);
                }
            }
            for (CategoryTotal category : slice.getCategoryTotals().values())
            {
                addCategory(category.category, category.netGp, category.activeMillis);
            }
            pvmEncounterTotals.merge(slice.getPvmEncounterTotals());
            recordCoverage(Dimension.PVM_ENCOUNTERS, slice.getCoverage(Dimension.PVM_ENCOUNTERS));
            recordCoverage(Dimension.PVM_ENCOUNTER_SOURCES,
                slice.getCoverage(Dimension.PVM_ENCOUNTER_SOURCES));
            // addActivity marks coverage; merge the original status below to preserve partiality.
            for (Map.Entry<String, ItemTotal> item : slice.getGainedItemTotals().entrySet())
            {
                ItemTotal value = item.getValue();
                addItem(gainedItems, value.itemId, value.itemName, value.quantity, value.valueGp);
            }
            for (Map.Entry<String, ItemTotal> item : slice.getCostItemTotals().entrySet())
            {
                ItemTotal value = item.getValue();
                addItem(costItems, value.itemId, value.itemName, value.quantity, value.valueGp);
            }
            long[] sliceNetBuckets = slice.getFourHourNetGp();
            long[] sliceActiveBuckets = slice.getFourHourActiveMillis();
            for (int i = 0; i < FOUR_HOUR_BUCKET_COUNT; i++)
            {
                fourHourNetGp[i] = safeAdd(fourHourNetGp[i], sliceNetBuckets[i]);
                fourHourActiveMillis[i] = nonNegativeAdd(fourHourActiveMillis[i], sliceActiveBuckets[i]);
            }
            long[] sliceHourlyNet = slice.getHourlyNetGp();
            long[] sliceHourlyActive = slice.getHourlyActiveMillis();
            for (int i = 0; i < HOURLY_BUCKET_COUNT; i++)
            {
                hourlyNetGp[i] = safeAdd(hourlyNetGp[i], sliceHourlyNet[i]);
                hourlyActiveMillis[i] = nonNegativeAdd(hourlyActiveMillis[i], sliceHourlyActive[i]);
            }
            gainedItemsTruncated |= slice.isGainedItemsTruncated();
            costItemsTruncated |= slice.isCostItemsTruncated();
            pvpKills = safeAddCount(pvpKills, slice.pvpKills);
            pvpDeaths = safeAddCount(pvpDeaths, slice.pvpDeaths);
            pvpKillNetGp = safeAdd(pvpKillNetGp, slice.pvpKillNetGp);
            pvpLossGp = nonNegativeAdd(pvpLossGp, slice.pvpLossGp);
            pvpBestKillGp = Math.max(pvpBestKillGp, slice.pvpBestKillGp);
            pvpLargestLossGp = Math.max(pvpLargestLossGp, slice.pvpLargestLossGp);
            sourceSessionIds.addAll(slice.getSourceSessionIds());
            for (Dimension dimension : Dimension.values())
            {
                recordCoverage(dimension, slice.getCoverage(dimension));
            }
            return this;
        }

        private boolean isOnDay(long epochMillis)
        {
            return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().equals(date);
        }

        private static boolean containsActivityIgnoreCase(Map<String, Long> map, String name)
        {
            if (name == null) return false;
            for (String existing : map.keySet())
            {
                if (existing != null && existing.equalsIgnoreCase(name)) return true;
            }
            return false;
        }

        private static Long getIgnoreCase(Map<String, Long> map, String name)
        {
            if (name == null) return null;
            for (Map.Entry<String, Long> entry : map.entrySet())
            {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
            }
            return null;
        }

        public DailyRollup build()
        {
            Builder snapshot = copyForBuild();
            if (snapshot.pvmEncounterTotals.hasRemainder())
            {
                snapshot.recordCoverage(Dimension.PVM_ENCOUNTER_SOURCES, Coverage.PARTIAL);
            }
            snapshot.gainedItemsTruncated |= trimItems(snapshot.gainedItems,
                Dimension.GAINED_ITEMS, snapshot);
            snapshot.costItemsTruncated |= trimItems(snapshot.costItems,
                Dimension.COST_ITEMS, snapshot);
            if (snapshot.coverage.get(Dimension.COST_SPLIT) != null
                && snapshot.coverage.get(Dimension.COST_SPLIT) != Coverage.UNAVAILABLE
                && safeAdd(snapshot.suppliesCostsGp, snapshot.lossCostsGp) != snapshot.costsGp)
            {
                // An inconsistent split must never be exposed as a trustworthy total.
                snapshot.coverage.put(Dimension.COST_SPLIT, Coverage.UNAVAILABLE);
            }
            return new DailyRollup(snapshot);
        }

        private static boolean trimItems(Map<String, ItemTotal> items, Dimension dimension,
            Builder snapshot)
        {
            if (items.size() <= TOP_ITEM_LIMIT) return false;
            List<ItemTotal> sorted = new ArrayList<>(items.values());
            sorted.sort(Comparator.comparingLong(ItemTotal::getValueGp).reversed()
                .thenComparing(ItemTotal::getItemName, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(ItemTotal::getItemId));
            items.clear();
            for (ItemTotal item : sorted.subList(0, TOP_ITEM_LIMIT))
            {
                items.put(itemKey(item.getItemId(), item.getItemName()), item);
            }
            snapshot.recordCoverage(dimension, Coverage.PARTIAL);
            return true;
        }

        private Builder copyForBuild()
        {
            Builder copy = new Builder(date, zone);
            copy.revenueGp = revenueGp;
            copy.costsGp = costsGp;
            copy.suppliesCostsGp = suppliesCostsGp;
            copy.lossCostsGp = lossCostsGp;
            copy.activeMillis = activeMillis;
            copy.kills = kills;
            copy.deaths = deaths;
            copy.sessionStarts = sessionStarts;
            copy.namedSessionStarts = namedSessionStarts;
            copy.runStarts = runStarts;
            copy.activities.putAll(activities);
            copy.categoryTotals.putAll(categoryTotals);
            copy.pvmEncounterTotals.merge(pvmEncounterTotals);
            copy.gainedItems.putAll(gainedItems);
            copy.costItems.putAll(costItems);
            copy.sourceSessionIds.addAll(sourceSessionIds);
            System.arraycopy(fourHourNetGp, 0, copy.fourHourNetGp, 0, FOUR_HOUR_BUCKET_COUNT);
            System.arraycopy(fourHourActiveMillis, 0, copy.fourHourActiveMillis, 0, FOUR_HOUR_BUCKET_COUNT);
            System.arraycopy(hourlyNetGp, 0, copy.hourlyNetGp, 0, HOURLY_BUCKET_COUNT);
            System.arraycopy(hourlyActiveMillis, 0, copy.hourlyActiveMillis, 0, HOURLY_BUCKET_COUNT);
            copy.gainedItemsTruncated = gainedItemsTruncated;
            copy.costItemsTruncated = costItemsTruncated;
            copy.pvpKills = pvpKills;
            copy.pvpDeaths = pvpDeaths;
            copy.pvpKillNetGp = pvpKillNetGp;
            copy.pvpLossGp = pvpLossGp;
            copy.pvpBestKillGp = pvpBestKillGp;
            copy.pvpLargestLossGp = pvpLargestLossGp;
            copy.coverage.putAll(coverage);
            return copy;
        }

        private void addItem(Map<String, ItemTotal> target, int itemId, String name,
            long quantity, long valueGp)
        {
            int safeId = Math.max(0, itemId);
            String safeName = normalizeItem(name);
            String key = itemKey(safeId, safeName);
            ItemTotal current = target.get(key);
            target.put(key, new ItemTotal(safeId, safeName,
                nonNegativeAdd(current == null ? 0L : current.quantity, quantity),
                nonNegativeAdd(current == null ? 0L : current.valueGp, valueGp)));
        }

        private void recordCoverage(Dimension dimension, Coverage value)
        {
            coverage.put(dimension, Coverage.weakest(coverage.get(dimension), value));
        }
    }
}
