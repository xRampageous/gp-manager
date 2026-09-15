package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded, durable encounter aggregates by source name.
 *
 * <p>At most {@value #MAX_NAMED_SOURCES} sources retain individual detail. When a new source
 * displaces a less frequent retained source, or cannot enter the retained set, its encounter
 * count and loot value are folded into the explicit remainder. Remainder values are never
 * discarded. Since the remainder intentionally omits source identities, an aggregated source
 * cannot later be separated from it; admission compares each new source contribution with the
 * least frequent currently named source.</p>
 *
 * <p>{@code lootValue} is the total value attributable to the supplied observation, not a
 * per-encounter unit price. Names are matched case-insensitively while the first observed
 * trimmed spelling is retained for display.</p>
 */
public final class EncounterTotals
{
    public static final int MAX_NAMED_SOURCES = 30;

    // Mutable persisted state. Public no-arg construction and non-final fields support Gson.
    private Map<String, SourceTotals> namedSources = new LinkedHashMap<>();
    private long remainderEncounterCount;
    private long remainderLootValue;
    private Boolean remainderLootValueKnown = Boolean.TRUE;

    /** Gson constructor and empty aggregate constructor. */
    public EncounterTotals()
    {
    }

    /** Adds every source and the remainder of {@code other}; used when two sessions merge. */
    public synchronized void absorb(EncounterTotals other)
    {
        if (other == null || other == this) return;
        normalizeState();
        Map<String, SourceTotals> theirs;
        long theirRemainderCount;
        long theirRemainderValue;
        boolean theirRemainderKnown;
        synchronized (other)
        {
            other.normalizeState();
            theirs = new LinkedHashMap<>(other.namedSources);
            theirRemainderCount = other.remainderEncounterCount;
            theirRemainderValue = other.remainderLootValue;
            theirRemainderKnown = other.remainderLootValueKnown == null || other.remainderLootValueKnown;
        }
        for (SourceTotals source : theirs.values())
        {
            boolean known = source.lootValueKnown == null || source.lootValueKnown;
            record(source.sourceName, source.encounterCount, source.lootValue, known, source.bestStreak, source.lastSeenAt);
        }
        if (theirRemainderCount > 0L)
        {
            addToRemainder(theirRemainderCount, theirRemainderValue, theirRemainderKnown);
        }
    }

    /**
     * Records an observed source contribution.
     *
     * @param sourceName source label; null and blank names are ignored
     * @param multiplicity nonnegative number of encounters represented by this observation
     * @param lootValue nonnegative total loot value represented by this observation; this overload
     *                  marks the value as complete
     * @param streak observed streak, retained as the maximum for the source
     * @param timestamp observation time in epoch milliseconds, retained as the latest time
     * @return true when a valid named observation was accepted
     */
    public synchronized boolean record(String sourceName, long multiplicity, long lootValue,
        long streak, long timestamp)
    {
        return record(sourceName, multiplicity, lootValue, true, streak, timestamp);
    }

    /**
     * Records a source contribution whose loot valuation may be incomplete.
     * When {@code lootValueKnown} is false, the numeric value is not added and the affected
     * source's availability is permanently false; this prevents a partial value from appearing
     * complete. The same availability rule applies to the aggregate remainder when the source
     * is overflowed or later evicted.
     */
    public synchronized boolean record(String sourceName, long multiplicity, long lootValue,
        boolean lootValueKnown, long streak, long timestamp)
    {
        String displayName = cleanDisplayName(sourceName);
        if (displayName.isEmpty() || multiplicity <= 0)
        {
            return false;
        }

        normalizeState();
        long value = lootValueKnown ? Math.max(0L, lootValue) : 0L;
        long safeStreak = Math.max(0L, streak);
        long safeTimestamp = Math.max(0L, timestamp);
        String key = normalizeName(displayName);

        SourceTotals existing = namedSources.get(key);
        if (existing != null)
        {
            existing.encounterCount = saturatedAdd(existing.encounterCount, multiplicity);
            existing.lootValue = saturatedAdd(existing.lootValue, value);
            existing.lootValueKnown = existing.lootValueKnown && lootValueKnown;
            existing.bestStreak = Math.max(existing.bestStreak, safeStreak);
            existing.lastSeenAt = Math.max(existing.lastSeenAt, safeTimestamp);
            return true;
        }

        if (namedSources.size() < MAX_NAMED_SOURCES)
        {
            namedSources.put(key, new SourceTotals(displayName, multiplicity, value,
                lootValueKnown, safeStreak, safeTimestamp));
            return true;
        }

        Map.Entry<String, SourceTotals> least = findLeastFrequent();
        if (least != null && multiplicity > least.getValue().encounterCount)
        {
            SourceTotals displaced = least.getValue();
            namedSources.remove(least.getKey());
            addToRemainder(displaced.encounterCount, displaced.lootValue,
                displaced.lootValueKnown);
            namedSources.put(key, new SourceTotals(displayName, multiplicity, value,
                lootValueKnown, safeStreak, safeTimestamp));
        }
        else
        {
            addToRemainder(multiplicity, value, lootValueKnown);
        }
        return true;
    }

    /** Returns an immutable, frequency-ordered snapshot of the retained named sources. */
    public synchronized List<SourceSnapshot> getNamedSources()
    {
        normalizeState();
        List<SourceSnapshot> result = new ArrayList<>();
        for (SourceTotals source : namedSources.values())
        {
            result.add(source.snapshot());
        }
        result.sort(Comparator.comparingLong(SourceSnapshot::getEncounterCount).reversed()
            .thenComparing(SourceSnapshot::getSourceName, String.CASE_INSENSITIVE_ORDER));
        return Collections.unmodifiableList(result);
    }

    /** Returns the aggregate for sources whose individual identities are not retained. */
    public synchronized RemainderSnapshot getRemainder()
    {
        normalizeState();
        return new RemainderSnapshot(remainderEncounterCount, remainderLootValue,
            isRemainderLootValueKnown());
    }

    /** Returns the saturated total encounter count across named sources and the remainder. */
    public synchronized long getTotalEncounterCount()
    {
        normalizeState();
        long total = remainderEncounterCount;
        for (SourceTotals source : namedSources.values())
        {
            total = saturatedAdd(total, source.encounterCount);
        }
        return total;
    }

    /** Returns the saturated total loot value across named sources and the remainder. */
    public synchronized long getTotalLootValue()
    {
        normalizeState();
        long total = remainderLootValue;
        for (SourceTotals source : namedSources.values())
        {
            total = saturatedAdd(total, source.lootValue);
        }
        return total;
    }

    /** True only when every named and remainder contribution has a complete loot valuation. */
    public synchronized boolean isTotalLootValueKnown()
    {
        normalizeState();
        if (!isRemainderLootValueKnown())
        {
            return false;
        }
        for (SourceTotals source : namedSources.values())
        {
            if (!source.lootValueKnown)
            {
                return false;
            }
        }
        return true;
    }

    public synchronized long getNamedEncounterCount()
    {
        normalizeState();
        long total = 0L;
        for (SourceTotals source : namedSources.values())
        {
            total = saturatedAdd(total, source.encounterCount);
        }
        return total;
    }

    public synchronized long getNamedLootValue()
    {
        normalizeState();
        long total = 0L;
        for (SourceTotals source : namedSources.values())
        {
            total = saturatedAdd(total, source.lootValue);
        }
        return total;
    }

    public synchronized int getNamedSourceCount()
    {
        normalizeState();
        return namedSources.size();
    }

    /** Returns a deep copy that may be safely changed independently. */
    public synchronized EncounterTotals copy()
    {
        normalizeState();
        EncounterTotals copy = new EncounterTotals();
        for (Map.Entry<String, SourceTotals> entry : namedSources.entrySet())
        {
            copy.namedSources.put(entry.getKey(), entry.getValue().copy());
        }
        copy.remainderEncounterCount = remainderEncounterCount;
        copy.remainderLootValue = remainderLootValue;
        copy.remainderLootValueKnown = isRemainderLootValueKnown();
        return copy;
    }

    /**
     * Adds an independent aggregate without expanding its overflow remainder into named rows.
     * This is used when combining session-day slices into profile rollups; source identities
     * omitted by an input remain explicitly omitted in the result.
     */
    public synchronized void merge(EncounterTotals other)
    {
        if (other == null || other == this)
        {
            return;
        }
        EncounterTotals snapshot = other.copy();
        for (SourceSnapshot source : snapshot.getNamedSources())
        {
            record(source.getSourceName(), source.getEncounterCount(), source.getLootValue(),
                source.isLootValueKnown(), source.getBestStreak(), source.getLastSeenAt());
        }
        RemainderSnapshot remainder = snapshot.getRemainder();
        addToRemainder(remainder.getEncounterCount(), remainder.getLootValue(),
            remainder.isLootValueKnown());
    }

    /** True when one or more source identities were folded into the bounded remainder. */
    public synchronized boolean hasRemainder()
    {
        normalizeState();
        return remainderEncounterCount > 0L;
    }

    private Map.Entry<String, SourceTotals> findLeastFrequent()
    {
        Map.Entry<String, SourceTotals> least = null;
        for (Map.Entry<String, SourceTotals> entry : namedSources.entrySet())
        {
            if (least == null
                || entry.getValue().encounterCount < least.getValue().encounterCount
                || (entry.getValue().encounterCount == least.getValue().encounterCount
                    && entry.getKey().compareTo(least.getKey()) > 0))
            {
                least = entry;
            }
        }
        return least;
    }

    private void addToRemainder(long encounterCount, long lootValue, boolean lootValueKnown)
    {
        remainderEncounterCount = saturatedAdd(remainderEncounterCount, Math.max(0L, encounterCount));
        remainderLootValue = saturatedAdd(remainderLootValue, Math.max(0L, lootValue));
        remainderLootValueKnown = isRemainderLootValueKnown() && lootValueKnown;
    }

    private boolean isRemainderLootValueKnown()
    {
        return remainderLootValueKnown == null || remainderLootValueKnown;
    }

    /** Repairs absent or malformed persisted state and merges case-insensitive duplicates. */
    private void normalizeState()
    {
        remainderEncounterCount = Math.max(0L, remainderEncounterCount);
        remainderLootValue = Math.max(0L, remainderLootValue);
        if (namedSources == null)
        {
            namedSources = new LinkedHashMap<>();
            return;
        }

        Map<String, SourceTotals> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, SourceTotals> entry : namedSources.entrySet())
        {
            SourceTotals source = entry.getValue();
            String displayName = cleanDisplayName(source == null ? null : source.sourceName);
            if (displayName.isEmpty())
            {
                displayName = cleanDisplayName(entry.getKey());
            }
            if (displayName.isEmpty() || source == null || source.encounterCount <= 0)
            {
                if (source != null)
                {
                    addToRemainder(source.encounterCount, source.lootValue,
                        source.lootValueKnown == null || source.lootValueKnown);
                }
                continue;
            }

            String key = normalizeName(displayName);
            SourceTotals safe = new SourceTotals(displayName, source.encounterCount,
                source.lootValue, source.lootValueKnown == null || source.lootValueKnown,
                source.bestStreak, source.lastSeenAt);
            SourceTotals previous = normalized.get(key);
            if (previous == null)
            {
                normalized.put(key, safe);
            }
            else
            {
                previous.encounterCount = saturatedAdd(previous.encounterCount, safe.encounterCount);
                previous.lootValue = saturatedAdd(previous.lootValue, safe.lootValue);
                previous.lootValueKnown = previous.lootValueKnown && safe.lootValueKnown;
                previous.bestStreak = Math.max(previous.bestStreak, safe.bestStreak);
                previous.lastSeenAt = Math.max(previous.lastSeenAt, safe.lastSeenAt);
            }
        }

        while (normalized.size() > MAX_NAMED_SOURCES)
        {
            Map.Entry<String, SourceTotals> least = findLeastFrequent(normalized);
            if (least == null)
            {
                break;
            }
            normalized.remove(least.getKey());
            addToRemainder(least.getValue().encounterCount, least.getValue().lootValue,
                least.getValue().lootValueKnown == null || least.getValue().lootValueKnown);
        }
        namedSources = normalized;
    }

    private static Map.Entry<String, SourceTotals> findLeastFrequent(Map<String, SourceTotals> sources)
    {
        Map.Entry<String, SourceTotals> least = null;
        for (Map.Entry<String, SourceTotals> entry : sources.entrySet())
        {
            if (least == null
                || entry.getValue().encounterCount < least.getValue().encounterCount
                || (entry.getValue().encounterCount == least.getValue().encounterCount
                    && entry.getKey().compareTo(least.getKey()) > 0))
            {
                least = entry;
            }
        }
        return least;
    }

    private static String cleanDisplayName(String sourceName)
    {
        return sourceName == null ? "" : sourceName.trim();
    }

    private static String normalizeName(String sourceName)
    {
        return cleanDisplayName(sourceName).toLowerCase(Locale.ROOT);
    }

    private static long saturatedAdd(long left, long right)
    {
        long a = Math.max(0L, left);
        long b = Math.max(0L, right);
        if (Long.MAX_VALUE - a < b)
        {
            return Long.MAX_VALUE;
        }
        return a + b;
    }

    private static final class SourceTotals
    {
        private String sourceName;
        private long encounterCount;
        private long lootValue;
        private Boolean lootValueKnown = Boolean.TRUE;
        private long bestStreak;
        private long lastSeenAt;

        /** Gson constructor. */
        private SourceTotals()
        {
        }

        private SourceTotals(String sourceName, long encounterCount, long lootValue,
            boolean lootValueKnown, long bestStreak, long lastSeenAt)
        {
            this.sourceName = cleanDisplayName(sourceName);
            this.encounterCount = Math.max(0L, encounterCount);
            this.lootValue = Math.max(0L, lootValue);
            this.lootValueKnown = lootValueKnown;
            this.bestStreak = Math.max(0L, bestStreak);
            this.lastSeenAt = Math.max(0L, lastSeenAt);
        }

        private SourceTotals copy()
        {
            return new SourceTotals(sourceName, encounterCount, lootValue,
                lootValueKnown == null || lootValueKnown, bestStreak, lastSeenAt);
        }

        private SourceSnapshot snapshot()
        {
            return new SourceSnapshot(sourceName, encounterCount, lootValue,
                lootValueKnown == null || lootValueKnown, bestStreak, lastSeenAt);
        }
    }

    /** Immutable snapshot of a named source aggregate. */
    public static final class SourceSnapshot
    {
        private final String sourceName;
        private final long encounterCount;
        private final long lootValue;
        private final boolean lootValueKnown;
        private final long bestStreak;
        private final long lastSeenAt;

        private SourceSnapshot(String sourceName, long encounterCount, long lootValue,
            boolean lootValueKnown, long bestStreak, long lastSeenAt)
        {
            this.sourceName = sourceName;
            this.encounterCount = encounterCount;
            this.lootValue = lootValue;
            this.lootValueKnown = lootValueKnown;
            this.bestStreak = bestStreak;
            this.lastSeenAt = lastSeenAt;
        }

        public String getSourceName() { return sourceName; }
        public long getEncounterCount() { return encounterCount; }
        public long getLootValue() { return lootValue; }
        public boolean isLootValueKnown() { return lootValueKnown; }
        public long getBestStreak() { return bestStreak; }
        public long getLastSeenAt() { return lastSeenAt; }
    }

    /** Immutable count/value aggregate for unnamed or overflow sources. */
    public static final class RemainderSnapshot
    {
        private final long encounterCount;
        private final long lootValue;
        private final boolean lootValueKnown;

        private RemainderSnapshot(long encounterCount, long lootValue, boolean lootValueKnown)
        {
            this.encounterCount = encounterCount;
            this.lootValue = lootValue;
            this.lootValueKnown = lootValueKnown;
        }

        public long getEncounterCount() { return encounterCount; }
        public long getLootValue() { return lootValue; }
        public boolean isLootValueKnown() { return lootValueKnown; }
    }
}
