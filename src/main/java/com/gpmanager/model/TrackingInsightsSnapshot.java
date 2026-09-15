package com.gpmanager.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Aggregated, recorded analytics for a requested UTC-day period. */
public final class TrackingInsightsSnapshot
{
    /** No reliable kill count, encounter identity, or event-time rate is stored yet. */
    public static final String MILESTONES_UNAVAILABLE_NO_DURABLE_ENCOUNTER_EVIDENCE =
        "UNAVAILABLE_NO_DURABLE_ENCOUNTER_EVIDENCE";

    /** Receipt-shaped milestone contract for future durable encounter-backed rows. */
    public static final class Milestone
    {
        private final int itemId;
        private final String itemName;
        private final long valueGp;
        private final long observedAtEpochMillis;
        private final int encounterKillCount;
        private final long sessionElapsedMillis;
        private final long gpPerHourAtObservation;

        public Milestone(int itemId, String itemName, long valueGp, long observedAtEpochMillis,
            int encounterKillCount, long sessionElapsedMillis, long gpPerHourAtObservation)
        {
            this.itemId = itemId;
            this.itemName = itemName == null ? "" : itemName;
            this.valueGp = Math.max(0L, valueGp);
            this.observedAtEpochMillis = Math.max(0L, observedAtEpochMillis);
            this.encounterKillCount = Math.max(0, encounterKillCount);
            this.sessionElapsedMillis = Math.max(0L, sessionElapsedMillis);
            this.gpPerHourAtObservation = gpPerHourAtObservation;
        }

        public int getItemId() { return itemId; }
        public String getItemName() { return itemName; }
        public long getValueGp() { return valueGp; }
        public long getObservedAtEpochMillis() { return observedAtEpochMillis; }
        public int getEncounterKillCount() { return encounterKillCount; }
        public long getSessionElapsedMillis() { return sessionElapsedMillis; }
        public long getGpPerHourAtObservation() { return gpPerHourAtObservation; }
    }

    private final int dayCount;
    private final long revenue;
    private final long costs;
    private final long suppliesCosts;
    private final long otherCosts;
    private final boolean costSplitAvailable;
    private final long activeMillis;
    private final long availableSinceEpochMillis;
    private final Map<String, Long> activityNet;
    private final Map<String, Long> gainedItems;
    private final Map<String, Long> legacyGainedItems;
    private final List<TrackingGainedItemDetail> gainedItemDetails;
    private final String projectionStatus;
    private final List<Milestone> milestones;
    private final String milestoneStatus;
    private final int milestoneThresholdGp;

    public TrackingInsightsSnapshot(int dayCount, long revenue, long costs, long activeMillis,
        long availableSinceEpochMillis, Map<String, Long> activityNet, Map<String, Long> gainedItems)
    {
        this(dayCount, revenue, costs, activeMillis, availableSinceEpochMillis,
            activityNet, gainedItems, gainedItems, Collections.emptyList(), "AVAILABLE");
    }

    public TrackingInsightsSnapshot(int dayCount, long revenue, long costs, long activeMillis,
        long availableSinceEpochMillis, Map<String, Long> activityNet, Map<String, Long> gainedItems,
        Map<String, Long> legacyGainedItems, List<TrackingGainedItemDetail> gainedItemDetails)
    {
        this(dayCount, revenue, costs, activeMillis, availableSinceEpochMillis,
            activityNet, gainedItems, legacyGainedItems, gainedItemDetails, "AVAILABLE");
    }

    public TrackingInsightsSnapshot(int dayCount, long revenue, long costs, long activeMillis,
        long availableSinceEpochMillis, Map<String, Long> activityNet, Map<String, Long> gainedItems,
        Map<String, Long> legacyGainedItems, List<TrackingGainedItemDetail> gainedItemDetails,
        String projectionStatus)
    {
        this(dayCount, revenue, costs, activeMillis, availableSinceEpochMillis, activityNet,
            gainedItems, legacyGainedItems, gainedItemDetails, projectionStatus,
            10_000_000, Collections.emptyList(), MILESTONES_UNAVAILABLE_NO_DURABLE_ENCOUNTER_EVIDENCE);
    }

    public TrackingInsightsSnapshot(int dayCount, long revenue, long costs, long activeMillis,
        long availableSinceEpochMillis, Map<String, Long> activityNet, Map<String, Long> gainedItems,
        Map<String, Long> legacyGainedItems, List<TrackingGainedItemDetail> gainedItemDetails,
        String projectionStatus, int milestoneThresholdGp)
    {
        this(dayCount, revenue, costs, activeMillis, availableSinceEpochMillis, activityNet,
            gainedItems, legacyGainedItems, gainedItemDetails, projectionStatus,
            milestoneThresholdGp, Collections.emptyList(),
            MILESTONES_UNAVAILABLE_NO_DURABLE_ENCOUNTER_EVIDENCE);
    }

    public TrackingInsightsSnapshot(int dayCount, long revenue, long costs, long activeMillis,
        long availableSinceEpochMillis, Map<String, Long> activityNet, Map<String, Long> gainedItems,
        Map<String, Long> legacyGainedItems, List<TrackingGainedItemDetail> gainedItemDetails,
        String projectionStatus, int milestoneThresholdGp, List<Milestone> milestones,
        String milestoneStatus)
    {
        this(dayCount, revenue, costs, activeMillis, availableSinceEpochMillis, activityNet,
            gainedItems, legacyGainedItems, gainedItemDetails, projectionStatus,
            milestoneThresholdGp, milestones, milestoneStatus, 0L, 0L, false);
    }

    public TrackingInsightsSnapshot(int dayCount, long revenue, long costs, long activeMillis,
        long availableSinceEpochMillis, Map<String, Long> activityNet, Map<String, Long> gainedItems,
        Map<String, Long> legacyGainedItems, List<TrackingGainedItemDetail> gainedItemDetails,
        String projectionStatus, int milestoneThresholdGp, List<Milestone> milestones,
        String milestoneStatus, long suppliesCosts, long otherCosts, boolean costSplitAvailable)
    {
        this.dayCount = Math.max(0, dayCount);
        this.revenue = revenue;
        this.costs = costs;
        this.suppliesCosts = suppliesCosts;
        this.otherCosts = otherCosts;
        this.costSplitAvailable = costSplitAvailable
            && safeAdd(suppliesCosts, otherCosts) == costs;
        this.activeMillis = Math.max(0L, activeMillis);
        this.availableSinceEpochMillis = Math.max(0L, availableSinceEpochMillis);
        this.activityNet = immutable(activityNet);
        this.gainedItems = immutable(gainedItems);
        this.legacyGainedItems = immutable(legacyGainedItems);
        this.gainedItemDetails = immutableDetails(gainedItemDetails);
        this.projectionStatus = projectionStatus == null ? "UNAVAILABLE" : projectionStatus;
        this.milestones = immutableMilestones(milestones);
        this.milestoneThresholdGp = Math.max(1, milestoneThresholdGp);
        this.milestoneStatus = milestoneStatus == null
            ? MILESTONES_UNAVAILABLE_NO_DURABLE_ENCOUNTER_EVIDENCE : milestoneStatus;
    }

    public int getDayCount() { return dayCount; }
    public long getRevenue() { return revenue; }
    public long getCosts() { return costs; }
    public long getSuppliesCosts() { return suppliesCosts; }
    public long getOtherCosts() { return otherCosts; }
    public boolean isCostSplitAvailable() { return costSplitAvailable; }
    public long getNet() { return safeAdd(revenue, -costs); }
    public long getActiveMillis() { return activeMillis; }
    public long getAverageRate() { return activeMillis <= 0L ? 0L : Math.round(getNet() * 3_600_000d / activeMillis); }
    public long getAvailableSinceEpochMillis() { return availableSinceEpochMillis; }
    public boolean hasRecordedData() { return availableSinceEpochMillis > 0L; }
    public Map<String, Long> getActivityNet() { return activityNet; }
    public Map<String, Long> getGainedItems() { return gainedItems; }
    public Map<String, Long> getLegacyGainedItems() { return legacyGainedItems; }
    public List<TrackingGainedItemDetail> getGainedItemDetails() { return gainedItemDetails; }
    public String getProjectionStatus() { return projectionStatus; }
    public boolean isProjectionAvailable() { return "AVAILABLE".equals(projectionStatus); }
    public List<Milestone> getMilestones() { return milestones; }
    public String getMilestoneStatus() { return milestoneStatus; }
    public int getMilestoneThresholdGp() { return milestoneThresholdGp; }
    public boolean areMilestonesAvailable() { return "AVAILABLE".equals(milestoneStatus); }

    private static Map<String, Long> immutable(Map<String, Long> input)
    {
        return Collections.unmodifiableMap(new LinkedHashMap<>(input == null ? Collections.emptyMap() : input));
    }

    private static List<TrackingGainedItemDetail> immutableDetails(List<TrackingGainedItemDetail> input)
    {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        List<TrackingGainedItemDetail> result = new ArrayList<>();
        for (TrackingGainedItemDetail detail : input)
        {
            if (detail != null) result.add(detail.copy());
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Milestone> immutableMilestones(List<Milestone> input)
    {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(input));
    }

    private static long safeAdd(long left, long right)
    {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ex) { return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE; }
    }
}
