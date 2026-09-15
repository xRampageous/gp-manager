package com.gpmanager.model;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, bounded history of read-only wealth snapshots.
 *
 * <p>All visits from the latest 30 days are retained. From day 30 through day
 * 365 the latest snapshot per UTC calendar day is retained; older history is
 * reduced to the latest snapshot per UTC Monday-based week. The hard cap is
 * 2,048 snapshots; if it is reached, the oldest entries are discarded and
 * {@link #isCapped()} reports that loss. Each location retains its 100 highest
 * value identifiable holdings plus one aggregate remainder bucket when more
 * items exist. This bounds serialized profile size even in long sessions.
 * Wealth history is informational and never participates in accounting Net.
 */
public final class WealthSnapshotHistory
{
    public static final long EVERY_VISIT_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L;
    public static final long DAILY_RETENTION_MILLIS = 365L * 24L * 60L * 60L * 1000L;
    public static final int MAX_SNAPSHOTS = 2_048;
    public static final int MAX_ITEMS_PER_LOCATION = 100;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    // Kept non-final for Gson's conservative Java 11 reflection support; callers
    // only receive immutable copies through the public API.
    private List<Snapshot> snapshots = new ArrayList<>();
    private boolean capped;

    /** Creates an empty history. */
    public WealthSnapshotHistory()
    {
    }

    private WealthSnapshotHistory(List<Snapshot> snapshots, boolean capped)
    {
        this.snapshots = new ArrayList<>();
        if (snapshots != null)
        {
            for (Snapshot snapshot : snapshots)
            {
                if (snapshot != null) this.snapshots.add(snapshot.copy());
            }
        }
        this.capped = capped;
    }

    public static WealthSnapshotHistory empty()
    {
        return new WealthSnapshotHistory();
    }

    /**
     * Returns a new history with {@code snapshot} appended and retention applied
     * relative to {@code nowEpochMillis}. The receiver is unchanged.
     */
    public WealthSnapshotHistory append(WealthLocationsSnapshot snapshot, long nowEpochMillis)
    {
        if (snapshot == null) return compact(nowEpochMillis);
        List<Snapshot> all = safeSnapshots();
        all.add(new Snapshot(snapshot));
        return normalized(all, isCapped(), nowEpochMillis);
    }

    /** Applies age-based compaction and the hard cap without adding a snapshot. */
    public WealthSnapshotHistory compact(long nowEpochMillis)
    {
        return normalized(safeSnapshots(), isCapped(), nowEpochMillis);
    }

    /** Immutable chronological history, including a copy of every nested row. */
    public List<Snapshot> getSnapshots()
    {
        return Collections.unmodifiableList(safeSnapshots());
    }

    /** Returns the newest retained snapshot, or {@code null} when empty. */
    public Snapshot getLatest()
    {
        List<Snapshot> values = safeSnapshots();
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    /** Returns retained snapshots within the requested trailing window. */
    public List<Snapshot> getTimeline(long days, long nowEpochMillis)
    {
        long window = Math.max(0L, days) > Long.MAX_VALUE / DAY_MILLIS
            ? Long.MAX_VALUE : Math.max(0L, days) * DAY_MILLIS;
        long start = nowEpochMillis < Long.MIN_VALUE + window ? Long.MIN_VALUE : nowEpochMillis - window;
        List<Snapshot> result = new ArrayList<>();
        for (Snapshot snapshot : safeSnapshots())
        {
            if (snapshot.getCapturedAtEpochMillis() >= start
                && snapshot.getCapturedAtEpochMillis() <= nowEpochMillis)
            {
                result.add(snapshot);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public int getSnapshotCap()
    {
        return MAX_SNAPSHOTS;
    }

    public int getItemCapPerLocation()
    {
        return MAX_ITEMS_PER_LOCATION;
    }

    /** True when at least one historical snapshot was dropped by the hard cap. */
    public boolean isCapped()
    {
        return capped;
    }

    /** Returns an independently copied instance suitable for persistence boundaries. */
    public WealthSnapshotHistory copyForPersistence()
    {
        return new WealthSnapshotHistory(safeSnapshots(), isCapped());
    }

    private static WealthSnapshotHistory normalized(List<Snapshot> input, boolean wasCapped, long now)
    {
        List<Snapshot> ordered = new ArrayList<>();
        if (input != null)
        {
            for (Snapshot snapshot : input)
            {
                if (snapshot != null) ordered.add(snapshot.copy());
            }
        }
        ordered.sort(Comparator.comparingLong(Snapshot::getCapturedAtEpochMillis));

        Map<String, Snapshot> compacted = new HashMap<>();
        List<Snapshot> recent = new ArrayList<>();
        for (Snapshot snapshot : ordered)
        {
            long captured = snapshot.getCapturedAtEpochMillis();
            long age = now >= captured ? now - captured : 0L;
            if (age <= EVERY_VISIT_RETENTION_MILLIS)
            {
                recent.add(snapshot);
                continue;
            }

            LocalDate day = Instant.ofEpochMilli(captured).atZone(ZoneOffset.UTC).toLocalDate();
            String bucket;
            if (age <= DAILY_RETENTION_MILLIS)
            {
                bucket = "D:" + day;
            }
            else
            {
                LocalDate week = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                bucket = "W:" + week;
            }
            // ordered is oldest-to-newest, so replacement leaves the latest row.
            compacted.put(bucket, snapshot);
        }

        List<Snapshot> retained = new ArrayList<>(recent);
        retained.addAll(compacted.values());
        retained.sort(Comparator.comparingLong(Snapshot::getCapturedAtEpochMillis));
        boolean capped = wasCapped;
        if (retained.size() > MAX_SNAPSHOTS)
        {
            capped = true;
            retained = new ArrayList<>(retained.subList(retained.size() - MAX_SNAPSHOTS, retained.size()));
        }
        return new WealthSnapshotHistory(retained, capped);
    }

    private List<Snapshot> safeSnapshots()
    {
        List<Snapshot> result = new ArrayList<>();
        if (snapshots != null)
        {
            for (Snapshot snapshot : snapshots)
            {
                if (snapshot != null) result.add(snapshot.copy());
            }
        }
        result.sort(Comparator.comparingLong(Snapshot::getCapturedAtEpochMillis));
        return result;
    }

    /** One timestamped set of locations. */
    public static final class Snapshot
    {
        private long capturedAtEpochMillis;
        private List<Location> locations = new ArrayList<>();

        public Snapshot()
        {
        }

        private Snapshot(WealthLocationsSnapshot source)
        {
            capturedAtEpochMillis = Math.max(0L, source.getCapturedAtEpochMillis());
            locations = new ArrayList<>();
            for (WealthLocationSnapshot location : source.getLocations())
            {
                if (location != null) locations.add(new Location(location));
            }
        }

        private Snapshot(long capturedAtEpochMillis, List<Location> locations)
        {
            this.capturedAtEpochMillis = Math.max(0L, capturedAtEpochMillis);
            this.locations = copyLocations(locations);
        }

        public long getCapturedAtEpochMillis() { return Math.max(0L, capturedAtEpochMillis); }
        public List<Location> getLocations() { return Collections.unmodifiableList(copyLocations(locations)); }

        public Location getLocation(String id)
        {
            if (id == null) return null;
            if (locations != null)
            {
                for (Location location : locations)
                {
                    if (location != null && id.equals(location.getId())) return location.copy();
                }
            }
            return null;
        }

        /** Converts retained identifiable items for existing wealth comparison code. */
        public WealthLocationsSnapshot toWealthLocationsSnapshot()
        {
            List<WealthLocationSnapshot> converted = new ArrayList<>();
            for (Location location : getLocations()) converted.add(location.toWealthLocationSnapshot());
            return new WealthLocationsSnapshot(getCapturedAtEpochMillis(), converted);
        }

        private Snapshot copy()
        {
            return new Snapshot(getCapturedAtEpochMillis(), locations);
        }

        private static List<Location> copyLocations(List<Location> source)
        {
            List<Location> copy = new ArrayList<>();
            if (source != null)
            {
                for (Location location : source)
                {
                    if (location != null) copy.add(location.copy());
                }
            }
            return copy;
        }
    }

    /** A location summary with up to 100 identified holdings plus an optional remainder. */
    public static final class Location
    {
        private String id = "";
        private String title = "";
        private WealthLocationSnapshot.Status status = WealthLocationSnapshot.Status.INCOMPLETE;
        private long valueGp;
        private long capturedAtEpochMillis;
        private String detail = "";
        private List<Holding> holdings = new ArrayList<>();

        public Location()
        {
        }

        private Location(WealthLocationSnapshot source)
        {
            id = source.getId();
            title = source.getTitle();
            status = source.getStatus();
            valueGp = source.getValueGp();
            capturedAtEpochMillis = source.getCapturedAtEpochMillis();
            detail = source.getDetail();
            holdings = new ArrayList<>();

            List<WealthLocationSnapshot.Item> sorted = new ArrayList<>(source.getItems());
            sorted.sort(Comparator
                .comparing(WealthLocationSnapshot.Item::isValueAvailable).reversed()
                .thenComparing(Comparator.comparingLong(WealthLocationSnapshot.Item::getValueGp).reversed())
                .thenComparingInt(WealthLocationSnapshot.Item::getItemId)
                .thenComparingInt(WealthLocationSnapshot.Item::getSlot));
            long remainderValue = 0L;
            long remainderCount = 0L;
            for (int i = 0; i < sorted.size(); i++)
            {
                WealthLocationSnapshot.Item item = sorted.get(i);
                if (i < MAX_ITEMS_PER_LOCATION)
                {
                    holdings.add(new Holding(item));
                }
                else
                {
                    remainderCount++;
                    if (item.isValueAvailable()) remainderValue = saturatedAdd(remainderValue, item.getValueGp());
                }
            }
            if (remainderCount > 0) holdings.add(Holding.remainder(remainderCount, remainderValue));
        }

        public String getId() { return id == null ? "" : id; }
        public String getTitle() { return title == null ? "" : title; }
        public WealthLocationSnapshot.Status getStatus()
        {
            return status == null ? WealthLocationSnapshot.Status.INCOMPLETE : status;
        }
        public long getValueGp() { return Math.max(0L, valueGp); }
        public long getCapturedAtEpochMillis() { return Math.max(0L, capturedAtEpochMillis); }
        public String getDetail() { return detail == null ? "" : detail; }
        public List<Holding> getHoldings() { return Collections.unmodifiableList(copyHoldings(holdings)); }

        public WealthLocationSnapshot toWealthLocationSnapshot()
        {
            List<WealthLocationSnapshot.Item> items = new ArrayList<>();
            for (Holding holding : getHoldings())
            {
                if (!holding.isRemainder())
                {
                    items.add(holding.toWealthLocationItem());
                }
            }
            return new WealthLocationSnapshot(getId(), getTitle(), getStatus(), getValueGp(),
                getCapturedAtEpochMillis(), items, getDetail());
        }

        private Location copy()
        {
            Location copy = new Location();
            copy.id = getId();
            copy.title = getTitle();
            copy.status = getStatus();
            copy.valueGp = getValueGp();
            copy.capturedAtEpochMillis = getCapturedAtEpochMillis();
            copy.detail = getDetail();
            copy.holdings = copyHoldings(holdings);
            return copy;
        }

        private static List<Holding> copyHoldings(List<Holding> source)
        {
            List<Holding> copy = new ArrayList<>();
            if (source != null)
            {
                for (Holding holding : source)
                {
                    if (holding != null) copy.add(holding.copy());
                }
            }
            return copy;
        }
    }

    /** A priced item observation, or an aggregate remainder bucket. */
    public static final class Holding
    {
        private int slot;
        private int itemId;
        private String itemName = "";
        private long quantity;
        private int unitPrice;
        private long valueGp;
        private ItemPriceSource priceSource = ItemPriceSource.UNKNOWN;
        private long priceCapturedAtEpochMillis;
        private boolean valueAvailable;
        private boolean remainder;
        private long remainderItemCount;

        public Holding()
        {
        }

        private Holding(WealthLocationSnapshot.Item source)
        {
            slot = source.getSlot();
            itemId = source.getItemId();
            itemName = source.getItemName();
            quantity = source.getQuantity();
            unitPrice = source.getUnitPrice();
            valueGp = source.getValueGp();
            priceSource = source.getPriceSource();
            priceCapturedAtEpochMillis = source.getPriceCapturedAtEpochMillis();
            valueAvailable = source.isValueAvailable();
        }

        private static Holding remainder(long count, long knownValue)
        {
            Holding item = new Holding();
            item.itemName = "Other holdings";
            item.valueGp = Math.max(0L, knownValue);
            item.valueAvailable = false;
            item.remainder = true;
            item.remainderItemCount = Math.max(0L, count);
            return item;
        }

        public int getSlot() { return slot; }
        public int getItemId() { return itemId; }
        public String getItemName() { return itemName == null ? "" : itemName; }
        public long getQuantity() { return Math.max(0L, quantity); }
        public int getUnitPrice() { return Math.max(0, unitPrice); }
        public long getValueGp() { return Math.max(0L, valueGp); }
        public ItemPriceSource getPriceSource()
        {
            return priceSource == null ? ItemPriceSource.UNKNOWN : priceSource;
        }
        public long getPriceCapturedAtEpochMillis() { return Math.max(0L, priceCapturedAtEpochMillis); }
        public boolean isValueAvailable() { return valueAvailable && !remainder; }
        public boolean isRemainder() { return remainder; }
        public long getRemainderItemCount() { return Math.max(0L, remainderItemCount); }

        private WealthLocationSnapshot.Item toWealthLocationItem()
        {
            return new WealthLocationSnapshot.Item(slot, itemId, getItemName(), getQuantity(),
                getUnitPrice(), getPriceSource(), getPriceCapturedAtEpochMillis());
        }

        private Holding copy()
        {
            Holding copy = new Holding();
            copy.slot = slot;
            copy.itemId = itemId;
            copy.itemName = getItemName();
            copy.quantity = getQuantity();
            copy.unitPrice = getUnitPrice();
            copy.valueGp = getValueGp();
            copy.priceSource = getPriceSource();
            copy.priceCapturedAtEpochMillis = getPriceCapturedAtEpochMillis();
            copy.valueAvailable = valueAvailable;
            copy.remainder = remainder;
            copy.remainderItemCount = getRemainderItemCount();
            return copy;
        }
    }

    private static long saturatedAdd(long left, long right)
    {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
