package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Facts-only grouping of one durable wealth snapshot. A missing, unreadable,
 * unpriced, or otherwise incomplete source is reported as unavailable rather
 * than as a zero balance. Remainder holdings are reported separately because
 * the source location may still have an exact full-location value.
 */
public final class WealthBreakdown
{
    public enum Group
    {
        BANK,
        EQUIPPED,
        GRAND_EXCHANGE,
        OTHER
    }

    private static final Map<Group, List<String>> REQUIRED_LOCATION_IDS = requiredLocationIds();

    private final long capturedAtEpochMillis;
    private final Map<Group, GroupValue> groups;
    private final Long totalGp;
    private final Map<Group, Double> shares;

    private WealthBreakdown(long capturedAtEpochMillis, Map<Group, GroupValue> groups,
        Long totalGp, Map<Group, Double> shares)
    {
        this.capturedAtEpochMillis = Math.max(0L, capturedAtEpochMillis);
        EnumMap<Group, GroupValue> groupCopy = new EnumMap<>(Group.class);
        EnumMap<Group, Double> shareCopy = new EnumMap<>(Group.class);
        for (Group group : Group.values())
        {
            groupCopy.put(group, groups.getOrDefault(group, GroupValue.unavailable(group,
                Collections.emptyList(), REQUIRED_LOCATION_IDS.get(group),
                Collections.emptyList(), false)));
            if (shares.containsKey(group)) shareCopy.put(group, shares.get(group));
        }
        this.groups = Collections.unmodifiableMap(groupCopy);
        this.totalGp = totalGp;
        this.shares = Collections.unmodifiableMap(shareCopy);
    }

    /** Builds the grouping directly from the retained full location values and coverage. */
    public static WealthBreakdown fromSnapshot(WealthSnapshotHistory.Snapshot snapshot)
    {
        if (snapshot == null)
        {
            return unavailable(0L);
        }

        EnumMap<Group, List<WealthSnapshotHistory.Location>> locations =
            new EnumMap<>(Group.class);
        EnumMap<Group, Set<String>> ids = new EnumMap<>(Group.class);
        EnumMap<Group, Boolean> duplicateIds = new EnumMap<>(Group.class);
        for (Group group : Group.values())
        {
            locations.put(group, new ArrayList<>());
            ids.put(group, new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
            duplicateIds.put(group, false);
        }

        for (WealthSnapshotHistory.Location location : snapshot.getLocations())
        {
            if (location == null) continue;
            Group group = groupFor(location.getId());
            String id = location.getId();
            if (id.isEmpty() || !ids.get(group).add(id))
            {
                duplicateIds.put(group, true);
            }
            locations.get(group).add(location);
        }

        EnumMap<Group, GroupValue> values = new EnumMap<>(Group.class);
        boolean allValuesAvailable = true;
        long total = 0L;
        try
        {
            for (Group group : Group.values())
            {
                GroupValue value = summarize(group, locations.get(group), ids.get(group),
                    duplicateIds.get(group));
                values.put(group, value);
                if (!value.isValueAvailable())
                {
                    allValuesAvailable = false;
                }
                else
                {
                    total = Math.addExact(total, value.getValueGp());
                }
            }
        }
        catch (ArithmeticException overflow)
        {
            return unavailable(snapshot.getCapturedAtEpochMillis());
        }

        Long availableTotal = allValuesAvailable ? total : null;
        EnumMap<Group, Double> shares = new EnumMap<>(Group.class);
        if (availableTotal != null && availableTotal > 0L)
        {
            for (Group group : Group.values())
            {
                shares.put(group, values.get(group).getValueGp() * 100.0d / availableTotal);
            }
        }
        return new WealthBreakdown(snapshot.getCapturedAtEpochMillis(), values,
            availableTotal, shares);
    }

    /** An uncaptured read has every group unavailable and no fabricated zero total. */
    public static WealthBreakdown unavailable(long capturedAtEpochMillis)
    {
        EnumMap<Group, GroupValue> values = new EnumMap<>(Group.class);
        for (Group group : Group.values())
        {
            values.put(group, GroupValue.unavailable(group, Collections.emptyList(),
                REQUIRED_LOCATION_IDS.get(group), Collections.emptyList(), false));
        }
        return new WealthBreakdown(capturedAtEpochMillis, values, null,
            Collections.emptyMap());
    }

    public long getCapturedAtEpochMillis() { return capturedAtEpochMillis; }
    public Long getTotalGp() { return totalGp; }
    public boolean isTotalAvailable() { return totalGp != null; }
    public GroupValue getGroup(Group group)
    {
        return group == null ? null : groups.get(group);
    }
    public Map<Group, GroupValue> getGroups() { return groups; }
    public Double getSharePercent(Group group) { return shares.get(group); }
    public boolean isShareAvailable(Group group) { return shares.containsKey(group); }

    private static GroupValue summarize(Group group,
        List<WealthSnapshotHistory.Location> locations, Set<String> ids,
        boolean duplicateIds)
    {
        List<String> sourceIds = new ArrayList<>(ids);
        List<String> missingIds = new ArrayList<>();
        List<String> unavailableIds = new ArrayList<>();
        for (String required : REQUIRED_LOCATION_IDS.get(group))
        {
            if (!ids.contains(required)) missingIds.add(required);
        }

        boolean available = !duplicateIds && !locations.isEmpty() && missingIds.isEmpty();
        boolean remainderPresent = false;
        long value = 0L;
        try
        {
            for (WealthSnapshotHistory.Location location : locations)
            {
                boolean locationAvailable = location.getStatus() == WealthLocationSnapshot.Status.AVAILABLE;
                if (!locationAvailable)
                {
                    available = false;
                    unavailableIds.add(location.getId());
                }
                for (WealthSnapshotHistory.Holding holding : location.getHoldings())
                {
                    if (holding.isRemainder()) remainderPresent = true;
                    else if (!holding.isValueAvailable())
                    {
                        available = false;
                        if (!unavailableIds.contains(location.getId()))
                            unavailableIds.add(location.getId());
                    }
                }
                if (locationAvailable) value = Math.addExact(value, location.getValueGp());
            }
        }
        catch (ArithmeticException overflow)
        {
            available = false;
        }
        return new GroupValue(group, available ? value : null, sourceIds, missingIds,
            unavailableIds, remainderPresent, duplicateIds);
    }

    private static Group groupFor(String id)
    {
        String key = id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
        if ("bank".equals(key)) return Group.BANK;
        if ("worn".equals(key) || "equipped".equals(key)) return Group.EQUIPPED;
        if ("ge_offers".equals(key) || "ge_collection".equals(key)) return Group.GRAND_EXCHANGE;
        // Inventory, pouches, coffers and any future non-dedicated location belong here.
        return Group.OTHER;
    }

    private static Map<Group, List<String>> requiredLocationIds()
    {
        EnumMap<Group, List<String>> result = new EnumMap<>(Group.class);
        result.put(Group.BANK, Collections.singletonList("bank"));
        result.put(Group.EQUIPPED, Collections.singletonList("worn"));
        result.put(Group.GRAND_EXCHANGE, java.util.Arrays.asList("ge_collection", "ge_offers"));
        // These are the known sources in the read-model contract. If a source is not
        // captured, Other remains unavailable instead of presenting a partial sum as total.
        // "coffers" is derived by the engine from the coin stores once every store is fresh or
        // marked unused; GpManagerEngine.getCoinStoreGaps names what is still missing.
        result.put(Group.OTHER, java.util.Arrays.asList("coffers", "inventory", "rune_pouch"));
        return Collections.unmodifiableMap(result);
    }

    /** One group's total and source-completeness evidence. */
    public static final class GroupValue
    {
        private final Group group;
        private final Long valueGp;
        private final List<String> sourceLocationIds;
        private final List<String> missingLocationIds;
        private final List<String> unavailableLocationIds;
        private final boolean remainderPresent;
        private final boolean duplicateLocationIds;

        private GroupValue(Group group, Long valueGp, List<String> sourceLocationIds,
            List<String> missingLocationIds, List<String> unavailableLocationIds,
            boolean remainderPresent, boolean duplicateLocationIds)
        {
            this.group = group;
            this.valueGp = valueGp;
            this.sourceLocationIds = immutableStrings(sourceLocationIds);
            this.missingLocationIds = immutableStrings(missingLocationIds);
            this.unavailableLocationIds = immutableStrings(unavailableLocationIds);
            this.remainderPresent = remainderPresent;
            this.duplicateLocationIds = duplicateLocationIds;
        }

        private static GroupValue unavailable(Group group, List<String> sourceIds,
            List<String> requiredIds, List<String> unavailableIds, boolean remainderPresent)
        {
            List<String> missing = new ArrayList<>();
            Set<String> present = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (sourceIds != null) present.addAll(sourceIds);
            if (requiredIds != null)
            {
                for (String required : requiredIds)
                {
                    if (!present.contains(required)) missing.add(required);
                }
            }
            return new GroupValue(group, null, sourceIds, missing, unavailableIds,
                remainderPresent, false);
        }

        public Group getGroup() { return group; }
        /** Null when any required source is missing or unreadable. */
        public Long getValueGp() { return valueGp; }
        public boolean isValueAvailable() { return valueGp != null; }
        /** Observed locations in this group, including unreadable locations. */
        public List<String> getSourceLocationIds() { return sourceLocationIds; }
        public List<String> getMissingLocationIds() { return missingLocationIds; }
        /** Observed locations whose status or retained item evidence is unusable. */
        public List<String> getUnavailableLocationIds() { return unavailableLocationIds; }
        /** True when source history retained a top-100 remainder for any location. */
        public boolean hasRemainder() { return remainderPresent; }
        public boolean hasDuplicateLocationIds() { return duplicateLocationIds; }

        private static List<String> immutableStrings(List<String> source)
        {
            if (source == null || source.isEmpty()) return Collections.emptyList();
            return Collections.unmodifiableList(new ArrayList<>(source));
        }
    }
}
