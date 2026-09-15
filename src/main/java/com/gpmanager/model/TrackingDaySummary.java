package com.gpmanager.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Bounded local-day analytics retained with a tracker. Values are recorded at
 * observation time, not recomputed from current prices when ledger rows age
 * out. Gson keeps the simple fields and maps compatible with older saves.
 */
public final class TrackingDaySummary
{
    public static final int FOUR_HOUR_BUCKET_COUNT = 6;
    public static final int HOURLY_BUCKET_COUNT = 24;
    public static final int TOP_ITEM_LIMIT = 20;
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final String[] FOUR_HOUR_LABELS = {
        "00:00-04:00", "04:00-08:00", "08:00-12:00",
        "12:00-16:00", "16:00-20:00", "20:00-24:00"
    };

    private String day;
    /** Persist strings rather than java.time objects for older Gson runtimes. */
    private String zoneId = "UTC";
    private long revenue;
    private long costs;
    private long suppliesCosts;
    private long otherCosts;
    /** Zero for persisted day aggregates predating Supplies/Loss accounting. */
    private int costSplitVersion;
    private boolean costSplitComplete;
    private long activeMillis;
    /** True once a restore clamped this day's active time to what its session could have played (pass 10 step 44). */
    private boolean activeTimeRebuilt;
    /** Independent analytics dimensions; absent legacy JSON fields stay unavailable. */
    private boolean activeTimeAvailable;
    private boolean fourHourBucketsAvailable;
    /** Absent from pre-Step-30 JSON, so legacy hourly buckets stay unavailable. */
    private boolean hourlyBucketsAvailable;
    private boolean activityActiveMillisAvailable;
    private boolean gainedItemTotalsAvailable;
    private boolean costItemTotalsAvailable;
    /** Caller-confirmed NPC encounter evidence; absent on old day summaries. */
    private EncounterTotals encounterTotals = new EncounterTotals();
    private boolean encounterTotalsAvailable;
    private long[] fourHourNetGp = new long[FOUR_HOUR_BUCKET_COUNT];
    private long[] fourHourActiveMillis = new long[FOUR_HOUR_BUCKET_COUNT];
    private long[] hourlyNetGp = new long[HOURLY_BUCKET_COUNT];
    private long[] hourlyActiveMillis = new long[HOURLY_BUCKET_COUNT];
    private Map<String, Long> activityNet = new LinkedHashMap<>();
    private Map<String, Long> activityActiveMillis = new LinkedHashMap<>();
    private Map<String, Long> gainedItems = new LinkedHashMap<>();
    private Map<String, TrackingGainedItemDetail> gainedItemDetails = new LinkedHashMap<>();
    private Map<String, DailyRollup.ItemTotal> gainedItemTotals = new LinkedHashMap<>();
    private Map<String, DailyRollup.ItemTotal> costItemTotals = new LinkedHashMap<>();

    public TrackingDaySummary()
    {
        // Gson
    }

    public TrackingDaySummary(String day)
    {
        this(day, UTC);
    }

    public TrackingDaySummary(String day, ZoneId zone)
    {
        this.day = day == null ? "" : day;
        this.zoneId = safeZone(zone).getId();
        this.costSplitVersion = 1;
        this.costSplitComplete = true;
        this.activeTimeAvailable = true;
        this.fourHourBucketsAvailable = true;
        this.hourlyBucketsAvailable = true;
        this.activityActiveMillisAvailable = true;
        this.gainedItemTotalsAvailable = true;
        this.costItemTotalsAvailable = true;
        this.encounterTotals = new EncounterTotals();
        this.encounterTotalsAvailable = true;
    }

    public TrackingDaySummary(long timestampEpochMillis, ZoneId zone)
    {
        this(dayDate(timestampEpochMillis, zone), zone);
    }

    public String getDay() { return day == null ? "" : day; }
    public String getDayKey() { return dayKey(getDay(), getZoneId()); }
    public String getZoneId()
    {
        return zoneFromId(zoneId == null || zoneId.trim().isEmpty() ? "UTC" : zoneId).getId();
    }
    public ZoneId getZone() { return zoneFromId(getZoneId()); }
    public long getRevenue() { return revenue; }
    public long getCosts() { return costs; }
    public long getSuppliesCosts() { return suppliesCosts; }
    public long getOtherCosts() { return otherCosts; }
    public boolean isCostSplitAvailable() { return costSplitVersion >= 1 && costSplitComplete; }
    public boolean isActiveTimeAvailable() { return activeTimeAvailable; }
    public boolean isActiveTimeRebuilt() { return activeTimeRebuilt; }

    /**
     * Scales this day's active time (and its buckets and per-activity shares) down so it totals
     * {@code maxMillis}, marking the day rebuilt. A no-op when it already fits.
     */
    public boolean clampActiveMillis(long maxMillis)
    {
        long bound = Math.max(0L, maxMillis);
        if (activeMillis <= bound) return false;
        double factor = activeMillis == 0L ? 0d : bound / (double) activeMillis;
        activeMillis = bound;
        for (int i = 0; fourHourActiveMillis != null && i < fourHourActiveMillis.length; i++)
            fourHourActiveMillis[i] = Math.round(fourHourActiveMillis[i] * factor);
        for (int i = 0; hourlyActiveMillis != null && i < hourlyActiveMillis.length; i++)
            hourlyActiveMillis[i] = Math.round(hourlyActiveMillis[i] * factor);
        ensureMaps();
        for (Map.Entry<String, Long> e : activityActiveMillis.entrySet())
            e.setValue(Math.round(e.getValue() * factor));
        activeTimeRebuilt = true;
        return true;
    }
    public boolean isFourHourBucketsAvailable() { return fourHourBucketsAvailable; }
    public boolean isHourlyBucketsAvailable() { return hourlyBucketsAvailable; }
    public boolean isActivityActiveMillisAvailable() { return activityActiveMillisAvailable; }
    public boolean isGainedItemTotalsAvailable() { return gainedItemTotalsAvailable; }
    public boolean isCostItemTotalsAvailable() { return costItemTotalsAvailable; }
    public boolean isEncounterTotalsAvailable() { return encounterTotalsAvailable; }
    public EncounterTotals getEncounterTotals()
    {
        return encounterTotals == null ? new EncounterTotals() : encounterTotals.copy();
    }
    public long getNet() { return safeAdd(revenue, -costs); }
    public long getActiveMillis() { return activeMillis; }
    /** Normalizes and persists the zone used by this day's bucket calculations. */
    public void setZone(ZoneId zone) { zoneId = safeZone(zone).getId(); }

    /** Marks migrated active-time evidence as unavailable rather than a complete zero. */
    public void markActiveTimeUnavailable()
    {
        activeTimeAvailable = false;
        fourHourBucketsAvailable = false;
        hourlyBucketsAvailable = false;
        activityActiveMillisAvailable = false;
    }

    public void markFourHourBucketsUnavailable()
    {
        fourHourBucketsAvailable = false;
        hourlyBucketsAvailable = false;
    }
    public void markHourlyBucketsUnavailable() { hourlyBucketsAvailable = false; }
    public void markActivityActiveMillisUnavailable() { activityActiveMillisAvailable = false; }

    /** Marks both new item-summary dimensions partial for a profile with legacy history. */
    public void markItemSummariesPartial()
    {
        gainedItemTotalsAvailable = false;
        costItemTotalsAvailable = false;
    }

    /** Records one already-deduplicated observed NPC encounter contribution. */
    public void recordEncounter(String sourceName, long multiplicity, long lootValue,
        boolean lootValueKnown, long streak, long timestampEpochMillis)
    {
        if (encounterTotals == null) encounterTotals = new EncounterTotals();
        encounterTotals.record(sourceName, multiplicity, lootValue, lootValueKnown,
            streak, timestampEpochMillis);
    }

    /** Marks encounter history missing rather than interpreting absent fields as zero. */
    public void markEncounterTotalsUnavailable()
    {
        encounterTotalsAvailable = false;
    }
    public Map<String, Long> getActivityNet()
    {
        return activityNet == null ? new LinkedHashMap<>() : new LinkedHashMap<>(activityNet);
    }
    public Map<String, Long> getActivityActiveMillis()
    {
        return activityActiveMillis == null ? new LinkedHashMap<>() : new LinkedHashMap<>(activityActiveMillis);
    }
    public long[] getFourHourNetGp()
    {
        if (hourlyBucketsAvailable) return collapseHourly(hourlyNetGp);
        return fourHourNetGp == null || fourHourNetGp.length != FOUR_HOUR_BUCKET_COUNT
            ? new long[FOUR_HOUR_BUCKET_COUNT] : fourHourNetGp.clone();
    }
    public long[] getFourHourActiveMillis()
    {
        if (hourlyBucketsAvailable) return collapseHourly(hourlyActiveMillis);
        return fourHourActiveMillis == null || fourHourActiveMillis.length != FOUR_HOUR_BUCKET_COUNT
            ? new long[FOUR_HOUR_BUCKET_COUNT] : fourHourActiveMillis.clone();
    }
    public long[] getHourlyNetGp()
    {
        return hourlyNetGp == null || hourlyNetGp.length != HOURLY_BUCKET_COUNT
            ? new long[HOURLY_BUCKET_COUNT] : hourlyNetGp.clone();
    }
    public long[] getHourlyActiveMillis()
    {
        return hourlyActiveMillis == null || hourlyActiveMillis.length != HOURLY_BUCKET_COUNT
            ? new long[HOURLY_BUCKET_COUNT] : hourlyActiveMillis.clone();
    }
    public Map<String, Long> getFourHourNetByBucket()
    {
        return bucketMap(getFourHourNetGp());
    }
    public Map<String, Long> getFourHourActiveMillisByBucket()
    {
        return bucketMap(getFourHourActiveMillis());
    }
    public Map<String, DailyRollup.ItemTotal> getGainedItemTotals()
    {
        return copyItemTotals(gainedItemTotals);
    }
    public Map<String, DailyRollup.ItemTotal> getCostItemTotals()
    {
        return copyItemTotals(costItemTotals);
    }
    public List<DailyRollup.ItemTotal> getTopGainedItems() { return topItems(gainedItemTotals); }
    public List<DailyRollup.ItemTotal> getTopCostItems() { return topItems(costItemTotals); }

    /** Stable date-plus-zone key for callers maintaining several local-day views. */
    public static String dayKey(long timestampEpochMillis, ZoneId zone)
    {
        return dayKey(dayDate(timestampEpochMillis, zone), safeZone(zone).getId());
    }

    /** Date component for this summary's requested zone. */
    public static String dayDate(long timestampEpochMillis, ZoneId zone)
    {
        return Instant.ofEpochMilli(timestampEpochMillis).atZone(safeZone(zone)).toLocalDate().toString();
    }
    public Map<String, Long> getGainedItems()
    {
        return gainedItems == null ? new LinkedHashMap<>() : new LinkedHashMap<>(gainedItems);
    }

    /** Name-only gains from older saves or flows without a usable item id. */
    public Map<String, Long> getUndetailedGainedItems()
    {
        Map<String, Long> undetailed = getGainedItems();
        if (gainedItemDetails == null || gainedItemDetails.isEmpty())
        {
            return undetailed;
        }
        for (TrackingGainedItemDetail detail : gainedItemDetails.values())
        {
            if (detail == null || detail.getValue() <= 0L) continue;
            String name = normalize(detail.getItemName());
            long remaining = safeAdd(undetailed.getOrDefault(name, 0L), -detail.getValue());
            if (remaining == 0L)
            {
                undetailed.remove(name);
            }
            else
            {
                undetailed.put(name, remaining);
            }
        }
        return undetailed;
    }

    public List<TrackingGainedItemDetail> getGainedItemDetails()
    {
        if (gainedItemDetails == null || gainedItemDetails.isEmpty())
        {
            return Collections.emptyList();
        }
        List<TrackingGainedItemDetail> details = new ArrayList<>();
        for (TrackingGainedItemDetail detail : gainedItemDetails.values())
        {
            if (detail != null && !detail.isEmpty()) details.add(detail.copy());
        }
        return details;
    }

    public void addTransaction(ProfitTransaction transaction)
    {
        addTransaction(transaction, UTC);
    }

    public void addTransaction(ProfitTransaction transaction, ZoneId zone)
    {
        AccountingProjection.TransactionAmounts amounts =
            AccountingProjection.transaction(transaction, null);
        if (!amounts.isAvailable() || !amounts.isIncluded()) return;
        ZoneId safeZone = safeZone(zone);
        zoneId = safeZone.getId();
        revenue = safeAdd(revenue, amounts.getRevenue());
        costs = safeAdd(costs, amounts.getCosts());
        updateCostSplit(transaction, 1L);
        ensureMaps();
        updateMapValue(activityNet, normalize(transaction.getActivityName()), amounts.getNet());
        addTimeBucketNet(transaction.getTimestampEpochMillis(), amounts.getNet(), safeZone, 1L);
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow != null && flow.getValueDelta() > 0L)
            {
                gainedItems.merge(normalize(flow.getItemName()), flow.getValueDelta(), TrackingDaySummary::safeAdd);
                if (flow.getItemId() > 0)
                {
                    String key = TrackingGainedItemDetail.key(flow);
                    TrackingGainedItemDetail detail = gainedItemDetails.get(key);
                    if (detail == null)
                    {
                        gainedItemDetails.put(key, new TrackingGainedItemDetail(flow));
                    }
                    else
                    {
                        detail.add(flow);
                    }
                }
            }

            updateItemTotals(transaction, flow, 1L);
        }
    }

    public void removeTransaction(ProfitTransaction transaction)
    {
        removeTransaction(transaction, UTC);
    }

    public void removeTransaction(ProfitTransaction transaction, ZoneId zone)
    {
        AccountingProjection.TransactionAmounts amounts =
            AccountingProjection.transaction(transaction, null);
        if (!amounts.isAvailable() || !amounts.isIncluded()) return;
        ZoneId safeZone = safeZone(zone);
        zoneId = safeZone.getId();
        revenue = safeAdd(revenue, -amounts.getRevenue());
        costs = safeAdd(costs, -amounts.getCosts());
        updateCostSplit(transaction, -1L);
        ensureMaps();
        updateMapValue(activityNet, normalize(transaction.getActivityName()), -amounts.getNet());
        addTimeBucketNet(transaction.getTimestampEpochMillis(), amounts.getNet(), safeZone, -1L);
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow != null && flow.getValueDelta() > 0L)
            {
                String name = normalize(flow.getItemName());
                long remaining = safeAdd(gainedItems.getOrDefault(name, 0L), -flow.getValueDelta());
                if (remaining == 0L)
                {
                    gainedItems.remove(name);
                }
                else
                {
                    gainedItems.put(name, remaining);
                }
                if (flow.getItemId() > 0 && gainedItemDetails != null)
                {
                    String key = TrackingGainedItemDetail.key(flow);
                    TrackingGainedItemDetail detail = gainedItemDetails.get(key);
                    if (detail != null)
                    {
                        detail.remove(flow);
                        if (detail.isEmpty()) gainedItemDetails.remove(key);
                    }
                }
            }

            updateItemTotals(transaction, flow, -1L);
        }
    }

    /**
     * Adds every figure of {@code other} (same day key) into this summary: accounting, cost split,
     * active time and its buckets, per-activity figures, item totals and encounter totals. Each
     * availability flag survives only when both sides had it. Used when two sessions merge.
     */
    public void absorb(TrackingDaySummary other)
    {
        if (other == null || other == this) return;
        ensureMaps();
        other.ensureMaps();
        revenue = safeAdd(revenue, other.revenue);
        costs = safeAdd(costs, other.costs);
        suppliesCosts = safeAdd(suppliesCosts, other.suppliesCosts);
        otherCosts = safeAdd(otherCosts, other.otherCosts);
        boolean split = isCostSplitAvailable() && other.isCostSplitAvailable();
        costSplitVersion = split ? Math.min(costSplitVersion, other.costSplitVersion) : 0;
        costSplitComplete = split;
        activeMillis = safeAdd(activeMillis, other.activeMillis);
        activeTimeAvailable = activeTimeAvailable && other.activeTimeAvailable;
        fourHourBucketsAvailable = fourHourBucketsAvailable && other.fourHourBucketsAvailable;
        hourlyBucketsAvailable = hourlyBucketsAvailable && other.hourlyBucketsAvailable;
        activityActiveMillisAvailable = activityActiveMillisAvailable && other.activityActiveMillisAvailable;
        gainedItemTotalsAvailable = gainedItemTotalsAvailable && other.gainedItemTotalsAvailable;
        costItemTotalsAvailable = costItemTotalsAvailable && other.costItemTotalsAvailable;
        for (int i = 0; i < FOUR_HOUR_BUCKET_COUNT; i++)
        {
            fourHourNetGp[i] = safeAdd(fourHourNetGp[i], other.fourHourNetGp == null || i >= other.fourHourNetGp.length ? 0L : other.fourHourNetGp[i]);
            fourHourActiveMillis[i] = safeAdd(fourHourActiveMillis[i], other.fourHourActiveMillis == null || i >= other.fourHourActiveMillis.length ? 0L : other.fourHourActiveMillis[i]);
        }
        for (int i = 0; i < HOURLY_BUCKET_COUNT; i++)
        {
            hourlyNetGp[i] = safeAdd(hourlyNetGp[i], other.hourlyNetGp == null || i >= other.hourlyNetGp.length ? 0L : other.hourlyNetGp[i]);
            hourlyActiveMillis[i] = safeAdd(hourlyActiveMillis[i], other.hourlyActiveMillis == null || i >= other.hourlyActiveMillis.length ? 0L : other.hourlyActiveMillis[i]);
        }
        for (Map.Entry<String, Long> e : other.activityNet.entrySet()) activityNet.merge(e.getKey(), e.getValue(), TrackingDaySummary::safeAdd);
        for (Map.Entry<String, Long> e : other.activityActiveMillis.entrySet()) activityActiveMillis.merge(e.getKey(), e.getValue(), TrackingDaySummary::safeAdd);
        for (Map.Entry<String, Long> e : other.gainedItems.entrySet()) gainedItems.merge(e.getKey(), e.getValue(), TrackingDaySummary::safeAdd);
        for (Map.Entry<String, TrackingGainedItemDetail> e : other.gainedItemDetails.entrySet())
        {
            TrackingGainedItemDetail mine = gainedItemDetails.get(e.getKey());
            if (mine == null) gainedItemDetails.put(e.getKey(), e.getValue());
            else mine.absorb(e.getValue());
        }
        absorbItemTotals(gainedItemTotals, other.gainedItemTotals);
        absorbItemTotals(costItemTotals, other.costItemTotals);
        if (encounterTotalsAvailable && other.encounterTotalsAvailable)
        {
            if (encounterTotals == null) encounterTotals = new EncounterTotals();
            encounterTotals.absorb(other.encounterTotals);
        }
        else
        {
            markEncounterTotalsUnavailable();
        }
    }

    private static void absorbItemTotals(Map<String, DailyRollup.ItemTotal> into, Map<String, DailyRollup.ItemTotal> from)
    {
        if (from == null) return;
        for (Map.Entry<String, DailyRollup.ItemTotal> e : from.entrySet())
        {
            DailyRollup.ItemTotal theirs = e.getValue();
            if (theirs == null) continue;
            DailyRollup.ItemTotal mine = into.get(e.getKey());
            into.put(e.getKey(), mine == null ? theirs : new DailyRollup.ItemTotal(theirs.getItemId(), theirs.getItemName(),
                safeAdd(mine.getQuantity(), theirs.getQuantity()), safeAdd(mine.getValueGp(), theirs.getValueGp())));
        }
    }

    public void addActiveMillis(long value)
    {
        activeMillis = safeAdd(activeMillis, Math.max(0L, value));
        // The legacy signature has neither a timestamp nor an activity label.
        // Keep its total but do not pretend the new dimensions are complete.
        fourHourBucketsAvailable = false;
        hourlyBucketsAvailable = false;
        activityActiveMillisAvailable = false;
        hourlyBucketsAvailable = false;
    }

    /** Adds a measured active interval, splitting it at local four-hour boundaries. */
    public void addActiveMillis(long value, long startTimestampEpochMillis, ZoneId zone,
        String activityName)
    {
        updateActiveMillis(value, startTimestampEpochMillis, zone, activityName, 1L);
    }

    /** Symmetric inverse for callers undoing a previously recorded active interval. */
    public void removeActiveMillis(long value, long startTimestampEpochMillis, ZoneId zone,
        String activityName)
    {
        updateActiveMillis(value, startTimestampEpochMillis, zone, activityName, -1L);
    }

    public void addActivityActiveMillis(String activityName, long value)
    {
        ensureMaps();
        long millis = Math.max(0L, value);
        if (millis > 0L)
        {
            activityActiveMillis.merge(normalize(activityName), millis, TrackingDaySummary::safeAdd);
        }
    }

    public void removeActivityActiveMillis(String activityName, long value)
    {
        ensureMaps();
        long millis = Math.max(0L, value);
        subtractMapValue(activityActiveMillis, normalize(activityName), millis);
    }

    private void ensureMaps()
    {
        if (activityNet == null) activityNet = new LinkedHashMap<>();
        if (activityActiveMillis == null) activityActiveMillis = new LinkedHashMap<>();
        if (gainedItems == null) gainedItems = new LinkedHashMap<>();
        if (gainedItemDetails == null) gainedItemDetails = new LinkedHashMap<>();
        if (gainedItemTotals == null) gainedItemTotals = new LinkedHashMap<>();
        if (costItemTotals == null) costItemTotals = new LinkedHashMap<>();
        if (fourHourNetGp == null || fourHourNetGp.length != FOUR_HOUR_BUCKET_COUNT)
            fourHourNetGp = new long[FOUR_HOUR_BUCKET_COUNT];
        if (fourHourActiveMillis == null || fourHourActiveMillis.length != FOUR_HOUR_BUCKET_COUNT)
            fourHourActiveMillis = new long[FOUR_HOUR_BUCKET_COUNT];
        if (hourlyNetGp == null || hourlyNetGp.length != HOURLY_BUCKET_COUNT)
            hourlyNetGp = new long[HOURLY_BUCKET_COUNT];
        if (hourlyActiveMillis == null || hourlyActiveMillis.length != HOURLY_BUCKET_COUNT)
            hourlyActiveMillis = new long[HOURLY_BUCKET_COUNT];
    }

    private void updateActiveMillis(long value, long startTimestampEpochMillis, ZoneId requestedZone,
        String activityName, long direction)
    {
        if (value <= 0L) return;
        ZoneId zone = safeZone(requestedZone);
        setZone(zone);
        LocalDate localDay;
        try { localDay = LocalDate.parse(getDay()); }
        catch (RuntimeException ex) { return; }
        long end;
        try { end = Math.addExact(startTimestampEpochMillis, value); }
        catch (ArithmeticException ex) { end = Long.MAX_VALUE; }
        long dayStart = localDay.atStartOfDay(zone).toInstant().toEpochMilli();
        long nextDay = localDay.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli();
        long cursor = Math.max(startTimestampEpochMillis, dayStart);
        long intervalEnd = Math.min(end, nextDay);
        if (intervalEnd <= cursor) return;
        long recorded = intervalEnd - cursor;
        activeMillis = safeAdd(activeMillis, direction * recorded);
        ensureMaps();
        updateActivityValue(normalize(activityName), direction * recorded);
        long intervalStart = cursor;
        while (cursor < intervalEnd)
        {
            ZonedDateTime local = Instant.ofEpochMilli(cursor).atZone(zone);
            int bucket = Math.min(FOUR_HOUR_BUCKET_COUNT - 1, local.getHour() / 4);
            int nextBoundaryHour = (bucket + 1) * 4;
            ZonedDateTime boundary = nextBoundaryHour >= 24
                ? local.toLocalDate().plusDays(1L).atStartOfDay(zone)
                : local.toLocalDate().atTime(LocalTime.of(nextBoundaryHour, 0)).atZone(zone);
            long segmentEnd = Math.min(intervalEnd, boundary.toInstant().toEpochMilli());
            if (segmentEnd <= cursor)
            {
                // Handles unusual zone transitions at a nominal boundary.
                segmentEnd = Math.min(intervalEnd, cursor + 1L);
            }
            fourHourActiveMillis[bucket] = safeAdd(fourHourActiveMillis[bucket],
                direction * (segmentEnd - cursor));
            cursor = segmentEnd;
        }
        cursor = intervalStart;
        while (cursor < intervalEnd)
        {
            ZonedDateTime local = Instant.ofEpochMilli(cursor).atZone(zone);
            int nextHour = local.getHour() + 1;
            ZonedDateTime boundary = nextHour >= 24
                ? local.toLocalDate().plusDays(1L).atStartOfDay(zone)
                : local.toLocalDate().atTime(LocalTime.of(nextHour, 0)).atZone(zone);
            long segmentEnd = Math.min(intervalEnd, boundary.toInstant().toEpochMilli());
            if (segmentEnd <= cursor)
            {
                // A skipped local hour can move the nominal boundary backwards.
                segmentEnd = Math.min(intervalEnd, cursor + 1L);
            }
            int hour = local.getHour();
            hourlyActiveMillis[hour] = safeAdd(hourlyActiveMillis[hour],
                direction * (segmentEnd - cursor));
            cursor = segmentEnd;
        }
    }

    private void addTimeBucketNet(long timestampEpochMillis, long net, ZoneId zone, long direction)
    {
        if (fourHourNetGp == null || fourHourNetGp.length != FOUR_HOUR_BUCKET_COUNT)
            fourHourNetGp = new long[FOUR_HOUR_BUCKET_COUNT];
        if (!dayDate(timestampEpochMillis, zone).equals(getDay())) return;
        int bucket = Instant.ofEpochMilli(timestampEpochMillis).atZone(zone).getHour() / 4;
        fourHourNetGp[bucket] = safeAdd(fourHourNetGp[bucket], direction * net);
        if (hourlyNetGp == null || hourlyNetGp.length != HOURLY_BUCKET_COUNT)
            hourlyNetGp = new long[HOURLY_BUCKET_COUNT];
        int hour = Instant.ofEpochMilli(timestampEpochMillis).atZone(zone).getHour();
        hourlyNetGp[hour] = safeAdd(hourlyNetGp[hour], direction * net);
    }

    private void updateActivityValue(String name, long delta)
    {
        if (delta >= 0L)
        {
            activityActiveMillis.merge(name, delta, TrackingDaySummary::safeAdd);
        }
        else
        {
            subtractMapValue(activityActiveMillis, name, -delta);
        }
    }

    private static void updateMapValue(Map<String, Long> values, String name, long delta)
    {
        long updated = safeAdd(values.getOrDefault(name, 0L), delta);
        if (updated == 0L) values.remove(name);
        else values.put(name, updated);
    }

    private void updateItemTotals(ProfitTransaction transaction, ItemFlow flow, long direction)
    {
        if (flow == null) return;
        AccountingProjection.TransactionAmounts amounts = AccountingProjection.flow(transaction, flow, null);
        if (!amounts.isAvailable() || !amounts.isIncluded()) return;
        if (amounts.getRevenue() > 0L)
        {
            updateItemTotal(gainedItemTotals, flow, amounts.getRevenue(), direction);
        }
        if (amounts.getCosts() > 0L)
        {
            updateItemTotal(costItemTotals, flow, amounts.getCosts(), direction);
        }
    }

    private static void updateItemTotal(Map<String, DailyRollup.ItemTotal> totals,
        ItemFlow flow, long value, long direction)
    {
        int itemId = Math.max(0, flow.getItemId());
        String name = normalizeItem(flow.getItemName());
        String key = itemKey(itemId, name);
        DailyRollup.ItemTotal current = totals.get(key);
        long quantity = safeAbs(flow.getQuantityDelta());
        long oldQuantity = current == null ? 0L : current.getQuantity();
        long oldValue = current == null ? 0L : current.getValueGp();
        long nextQuantity = nonNegativeAdd(oldQuantity, direction * quantity);
        long nextValue = nonNegativeAdd(oldValue, direction * value);
        if (nextQuantity == 0L && nextValue == 0L)
        {
            totals.remove(key);
        }
        else
        {
            totals.put(key, new DailyRollup.ItemTotal(itemId, name, nextQuantity, nextValue));
        }
    }

    private static long nonNegativeAdd(long left, long right)
    {
        long result = safeAdd(left, right);
        return Math.max(0L, result);
    }

    private static long safeAbs(long value)
    {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private static void subtractMapValue(Map<String, Long> values, String key, long value)
    {
        if (values == null || value <= 0L) return;
        long remaining = Math.max(0L, safeAdd(values.getOrDefault(key, 0L), -value));
        if (remaining == 0L) values.remove(key);
        else values.put(key, remaining);
    }

    private static String itemKey(int itemId, String name)
    {
        return Math.max(0, itemId) + ":" + normalizeItem(name);
    }

    private static Map<String, DailyRollup.ItemTotal> copyItemTotals(
        Map<String, DailyRollup.ItemTotal> values)
    {
        Map<String, DailyRollup.ItemTotal> copy = new LinkedHashMap<>();
        if (values != null)
        {
            for (Map.Entry<String, DailyRollup.ItemTotal> entry : values.entrySet())
            {
                DailyRollup.ItemTotal value = entry.getValue();
                if (value != null)
                {
                    copy.put(entry.getKey(), new DailyRollup.ItemTotal(value.getItemId(),
                        value.getItemName(), value.getQuantity(), value.getValueGp()));
                }
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<DailyRollup.ItemTotal> topItems(Map<String, DailyRollup.ItemTotal> values)
    {
        List<DailyRollup.ItemTotal> sorted = new ArrayList<>(copyItemTotals(values).values());
        sorted.sort(Comparator.comparingLong(DailyRollup.ItemTotal::getValueGp).reversed()
            .thenComparing(DailyRollup.ItemTotal::getItemName, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(DailyRollup.ItemTotal::getItemId));
        if (sorted.size() > TOP_ITEM_LIMIT)
        {
            sorted = new ArrayList<>(sorted.subList(0, TOP_ITEM_LIMIT));
        }
        return Collections.unmodifiableList(sorted);
    }

    private static Map<String, Long> bucketMap(long[] values)
    {
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < FOUR_HOUR_BUCKET_COUNT; i++)
        {
            result.put(FOUR_HOUR_LABELS[i], values[i]);
        }
        return Collections.unmodifiableMap(result);
    }

    private static long[] collapseHourly(long[] hourly)
    {
        long[] result = new long[FOUR_HOUR_BUCKET_COUNT];
        if (hourly == null || hourly.length != HOURLY_BUCKET_COUNT) return result;
        for (int hour = 0; hour < HOURLY_BUCKET_COUNT; hour++)
        {
            result[hour / 4] = safeAdd(result[hour / 4], hourly[hour]);
        }
        return result;
    }

    private static String dayKey(String date, String zoneId)
    {
        return (date == null ? "" : date) + "@" + (zoneId == null ? "UTC" : zoneId);
    }

    private static ZoneId safeZone(ZoneId zone)
    {
        return zone == null ? UTC : zone;
    }

    private static ZoneId zoneFromId(String value)
    {
        try { return ZoneId.of(value); }
        catch (RuntimeException ex) { return UTC; }
    }

    private static String normalizeItem(String value)
    {
        return value == null || value.trim().isEmpty() ? "Unknown item" : value.trim();
    }

    private void updateCostSplit(ProfitTransaction transaction, long direction)
    {
        CostSplit split = costSplit(transaction);
        if (!split.available)
        {
            costSplitComplete = false;
            return;
        }
        suppliesCosts = safeAdd(suppliesCosts, direction * split.supplies);
        otherCosts = safeAdd(otherCosts, direction * split.other);
        if (costSplitVersion < 1)
        {
            costSplitComplete = false;
        }
    }

    private static CostSplit costSplit(ProfitTransaction transaction)
    {
        AccountingProjection.TransactionAmounts total = AccountingProjection.transaction(transaction, null);
        if (!total.isAvailable()) return new CostSplit(0L, 0L, false);
        if (!total.isIncluded() || total.getCosts() <= 0L)
        {
            return new CostSplit(0L, 0L, true);
        }
        if (transaction.getFlows().isEmpty())
        {
            return new CostSplit(0L, 0L, false);
        }
        long supplies = 0L;
        long other = 0L;
        for (ItemFlow flow : transaction.getFlows())
        {
            if (flow == null) continue;
            AccountingProjection.TransactionAmounts amounts =
                AccountingProjection.flow(transaction, flow, null);
            if (!amounts.isAvailable()) return new CostSplit(0L, 0L, false);
            if (amounts.getCosts() <= 0L) continue;
            CostKind kind = CostKind.of(transaction, flow);
            if (kind == CostKind.SUPPLIES)
            {
                supplies = safeAdd(supplies, amounts.getCosts());
            }
            else if (kind == CostKind.LOSS || kind == CostKind.MARKET)
            {
                other = safeAdd(other, amounts.getCosts());
            }
            else
            {
                return new CostSplit(0L, 0L, false);
            }
        }
        return new CostSplit(supplies, other,
            safeAdd(supplies, other) == total.getCosts());
    }

    private static final class CostSplit
    {
        private final long supplies;
        private final long other;
        private final boolean available;

        private CostSplit(long supplies, long other, boolean available)
        {
            this.supplies = supplies;
            this.other = other;
            this.available = available;
        }
    }

    private static String normalize(String value)
    {
        return value == null || value.trim().isEmpty() ? "General" : value.trim();
    }

    private static long safeAdd(long left, long right)
    {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ex) { return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE; }
    }
}
