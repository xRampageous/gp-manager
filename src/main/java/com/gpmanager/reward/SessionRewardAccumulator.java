package com.gpmanager.reward;

import com.gpmanager.HudPlusAccumulationMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory HUD+ reward lifetime. This deliberately records only caller-proven
 * encounters; item quantities never imply an encounter or kill count.
 */
public final class SessionRewardAccumulator
{
    private final Map<String, SourceTotals> sources = new LinkedHashMap<>();

    public void clear()
    {
        sources.clear();
    }

    public void recordEncounter(
        String sourceName,
        RewardSourceKind kind,
        List<RewardItem> items,
        long now,
        int encounterMultiplicity,
        long streakTimeoutMillis)
    {
        int count = Math.max(1, encounterMultiplicity);
        String name = sourceName == null || sourceName.trim().isEmpty() ? "Reward" : sourceName.trim();
        RewardSourceKind sourceKind = kind == null ? RewardSourceKind.UNKNOWN : kind;
        SourceTotals totals = sources.computeIfAbsent(key(name, sourceKind),
            ignored -> new SourceTotals(name, sourceKind));
        if (streakTimeoutMillis > 0L && totals.lastEncounterEpochMillis > 0L
            && now - totals.lastEncounterEpochMillis > streakTimeoutMillis)
        {
            totals.clearStreak();
        }
        totals.addSession(items, count);
        totals.addStreak(items, count);
        totals.lastEncounterEpochMillis = now;
    }

    public Snapshot current(
        String sourceName,
        RewardSourceKind kind,
        HudPlusAccumulationMode mode,
        long now,
        long streakTimeoutMillis)
    {
        if (sourceName == null || kind == null)
        {
            return null;
        }
        SourceTotals totals = sources.get(key(sourceName.trim(), kind));
        if (totals == null)
        {
            return null;
        }
        if (mode == HudPlusAccumulationMode.STREAK && streakTimeoutMillis > 0L
            && now - totals.lastEncounterEpochMillis > streakTimeoutMillis)
        {
            totals.clearStreak();
        }
        return mode == HudPlusAccumulationMode.STREAK
            ? totals.streakSnapshot()
            : totals.sessionSnapshot();
    }

    private static String key(String name, RewardSourceKind kind)
    {
        return kind.name() + '|' + name;
    }

    public static final class Snapshot
    {
        private final String sourceName;
        private final RewardSourceKind sourceKind;
        private final int encounterCount;
        private final List<RewardItem> items;

        Snapshot(String sourceName, RewardSourceKind sourceKind, int encounterCount, List<RewardItem> items)
        {
            this.sourceName = sourceName;
            this.sourceKind = sourceKind;
            this.encounterCount = encounterCount;
            this.items = items;
        }

        public String getSourceName() { return sourceName; }
        public RewardSourceKind getSourceKind() { return sourceKind; }
        public int getEncounterCount() { return encounterCount; }
        public List<RewardItem> getItems() { return items; }
    }

    private static final class SourceTotals
    {
        private final String sourceName;
        private final RewardSourceKind sourceKind;
        private int sessionCount;
        private int streakCount;
        private long lastEncounterEpochMillis;
        private final Map<Integer, RewardItem> sessionItems = new LinkedHashMap<>();
        private final Map<Integer, RewardItem> streakItems = new LinkedHashMap<>();

        SourceTotals(String sourceName, RewardSourceKind sourceKind)
        {
            this.sourceName = sourceName;
            this.sourceKind = sourceKind;
        }

        void addSession(List<RewardItem> items, int count)
        {
            sessionCount += count;
            merge(sessionItems, items);
        }

        void addStreak(List<RewardItem> items, int count)
        {
            streakCount += count;
            merge(streakItems, items);
        }

        void clearStreak()
        {
            streakCount = 0;
            streakItems.clear();
        }

        Snapshot sessionSnapshot()
        {
            return new Snapshot(sourceName, sourceKind, sessionCount,
                RewardObservation.mergeStacks(new java.util.ArrayList<>(sessionItems.values())));
        }

        Snapshot streakSnapshot()
        {
            return new Snapshot(sourceName, sourceKind, streakCount,
                RewardObservation.mergeStacks(new java.util.ArrayList<>(streakItems.values())));
        }

        private static void merge(Map<Integer, RewardItem> target, List<RewardItem> items)
        {
            if (items == null)
            {
                return;
            }
            for (RewardItem item : items)
            {
                if (item != null && item.getItemId() > 0 && item.getQuantity() > 0L)
                {
                    RewardItem prior = target.get(item.getItemId());
                    target.put(item.getItemId(), prior == null ? item : prior.merge(item));
                }
            }
        }
    }
}
