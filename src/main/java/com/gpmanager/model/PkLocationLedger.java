package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compaction-safe, bounded location-time evidence for one session. Closed
 * segments are retained for date-window queries; per-label and unlabelled
 * totals survive segment eviction and session compaction.
 */
public final class PkLocationLedger
{
    public static final int MAX_SEGMENTS = 200;
    private static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private long coverageStartEpochMillis;
    private long truncatedThroughEpochMillis;
    private long unlabelledMillis;
    private Map<String, Long> totalMillisByLabel = new LinkedHashMap<>();
    private List<Segment> segments = new ArrayList<>();
    private boolean open;
    private String currentLabel;
    private long currentEnteredAtEpochMillis;

    /** Gson constructor; legacy sessions have no ledger field at all. */
    private PkLocationLedger()
    {
    }

    public PkLocationLedger(long startedAtEpochMillis)
    {
        version = CURRENT_VERSION;
        coverageStartEpochMillis = Math.max(0L, startedAtEpochMillis);
        open = true;
        currentEnteredAtEpochMillis = coverageStartEpochMillis;
    }

    public synchronized boolean isAvailable()
    {
        return version == CURRENT_VERSION;
    }

    public synchronized long getCoverageStartEpochMillis()
    {
        return coverageStartEpochMillis;
    }

    public synchronized long getTruncatedThroughEpochMillis()
    {
        return truncatedThroughEpochMillis;
    }

    /** True when this ledger can fully cover all in-session time from {@code from}. */
    public synchronized boolean hasCompleteCoverageFrom(long fromEpochMillis,
        long sessionStartedAtEpochMillis)
    {
        if (!isAvailable()) return false;
        long relevantStart = Math.max(fromEpochMillis, Math.max(0L, sessionStartedAtEpochMillis));
        return relevantStart >= coverageStartEpochMillis
            && (truncatedThroughEpochMillis == 0L || relevantStart >= truncatedThroughEpochMillis);
    }

    /** Observe a new label at a client location sample; null remains unlabelled. */
    public synchronized void observe(String locationLabel, long now)
    {
        if (!isAvailable()) return;
        String normalized = normalize(locationLabel);
        if (open && equalsLabel(currentLabel, normalized)) return;
        closeOpen(now);
        open = true;
        currentLabel = normalized;
        currentEnteredAtEpochMillis = Math.max(coverageStartEpochMillis, now);
    }

    /** Closes the current interval at pause, stop, shutdown, or session switch. */
    public synchronized void pause(long now)
    {
        if (isAvailable()) closeOpen(now);
    }

    /** Starts a fresh unknown interval so resume never reuses a stale location. */
    public synchronized void resume(long now)
    {
        if (!isAvailable()) return;
        closeOpen(now);
        open = true;
        currentLabel = null;
        currentEnteredAtEpochMillis = Math.max(coverageStartEpochMillis, now);
    }

    /**
     * Returns retained intervals clipped to the requested instant range,
     * including the live interval when it overlaps.
     */
    public synchronized List<Segment> getSegments(long fromEpochMillis,
        long toEpochMillis, long now)
    {
        if (!isAvailable() || toEpochMillis <= fromEpochMillis) return Collections.emptyList();
        ensureState();
        List<Segment> result = new ArrayList<>();
        for (Segment segment : segments)
        {
            Segment clipped = segment.clip(fromEpochMillis, toEpochMillis);
            if (clipped != null) result.add(clipped);
        }
        if (open)
        {
            Segment live = new Segment(currentLabel, currentEnteredAtEpochMillis,
                Math.max(currentEnteredAtEpochMillis, now));
            Segment clipped = live.clip(fromEpochMillis, toEpochMillis);
            if (clipped != null) result.add(clipped);
        }
        return Collections.unmodifiableList(result);
    }

    /** Completed all-time per-label totals, retained when old segments are evicted. */
    public synchronized Map<String, Long> getTotalMillisByLabel()
    {
        ensureState();
        return Collections.unmodifiableMap(new LinkedHashMap<>(totalMillisByLabel));
    }

    /** Completed all-time unknown-label remainder. */
    public synchronized long getUnlabelledMillis()
    {
        return Math.max(0L, unlabelledMillis);
    }

    public synchronized List<Segment> getRetainedSegments()
    {
        ensureState();
        return Collections.unmodifiableList(new ArrayList<>(segments));
    }

    /** Appends the closed intervals and totals of a later ledger; the open interval of {@code other} is closed at {@code now}. */
    public synchronized void absorb(PkLocationLedger other, long now)
    {
        if (other == null || other == this) return;
        ensureState();
        closeOpen(now);
        PkLocationLedger theirs = other.copy();
        theirs.closeOpen(now);
        if (!theirs.isAvailable())
        {
            // Unknown coverage on either side makes the whole merged ledger unknown.
            version = 0;
            return;
        }
        if (!isAvailable()) return;
        coverageStartEpochMillis = coverageStartEpochMillis == 0L ? theirs.coverageStartEpochMillis
            : Math.min(coverageStartEpochMillis, theirs.coverageStartEpochMillis);
        truncatedThroughEpochMillis = Math.max(truncatedThroughEpochMillis, theirs.truncatedThroughEpochMillis);
        unlabelledMillis = safeAdd(unlabelledMillis, theirs.unlabelledMillis);
        for (Map.Entry<String, Long> e : theirs.totalMillisByLabel.entrySet())
        {
            totalMillisByLabel.put(e.getKey(), safeAdd(totalMillisByLabel.getOrDefault(e.getKey(), 0L), e.getValue()));
        }
        for (Segment segment : theirs.segments) appendSegment(segment);
    }

    public synchronized PkLocationLedger copy()
    {
        PkLocationLedger copy = new PkLocationLedger();
        copy.version = version;
        copy.coverageStartEpochMillis = coverageStartEpochMillis;
        copy.truncatedThroughEpochMillis = truncatedThroughEpochMillis;
        copy.unlabelledMillis = unlabelledMillis;
        copy.totalMillisByLabel = new LinkedHashMap<>(getTotalMillisByLabel());
        copy.segments = new ArrayList<>(getRetainedSegments());
        copy.open = open;
        copy.currentLabel = currentLabel;
        copy.currentEnteredAtEpochMillis = currentEnteredAtEpochMillis;
        return copy;
    }

    private void closeOpen(long now)
    {
        if (!open) return;
        long ended = Math.max(currentEnteredAtEpochMillis, now);
        long duration = ended - currentEnteredAtEpochMillis;
        if (duration > 0L)
        {
            ensureState();
            if (currentLabel == null)
            {
                unlabelledMillis = safeAdd(unlabelledMillis, duration);
            }
            else
            {
                totalMillisByLabel.put(currentLabel,
                    safeAdd(totalMillisByLabel.getOrDefault(currentLabel, 0L), duration));
            }
            appendSegment(new Segment(currentLabel, currentEnteredAtEpochMillis, ended));
        }
        open = false;
        currentLabel = null;
        currentEnteredAtEpochMillis = ended;
    }

    private void appendSegment(Segment next)
    {
        int size = segments.size();
        if (size > 0)
        {
            Segment previous = segments.get(size - 1);
            if (previous.leftAtEpochMillis == next.enteredAtEpochMillis
                && equalsLabel(previous.locationLabel, next.locationLabel))
            {
                previous.leftAtEpochMillis = next.leftAtEpochMillis;
                return;
            }
        }
        segments.add(next);
        while (segments.size() > MAX_SEGMENTS)
        {
            Segment removed = segments.remove(0);
            truncatedThroughEpochMillis = Math.max(truncatedThroughEpochMillis,
                removed.leftAtEpochMillis);
        }
    }

    private void ensureState()
    {
        if (totalMillisByLabel == null) totalMillisByLabel = new LinkedHashMap<>();
        if (segments == null) segments = new ArrayList<>();
    }

    private static String normalize(String value)
    {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static boolean equalsLabel(String left, String right)
    {
        return left == null ? right == null : left.equals(right);
    }

    private static long safeAdd(long left, long right)
    {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ex) { return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE; }
    }

    /** Persisted half-open interval; null labels contribute only to the remainder. */
    public static final class Segment
    {
        private String locationLabel;
        private long enteredAtEpochMillis;
        private long leftAtEpochMillis;

        private Segment()
        {
            // Gson
        }

        private Segment(String locationLabel, long enteredAtEpochMillis, long leftAtEpochMillis)
        {
            this.locationLabel = locationLabel;
            this.enteredAtEpochMillis = enteredAtEpochMillis;
            this.leftAtEpochMillis = Math.max(enteredAtEpochMillis, leftAtEpochMillis);
        }

        public String getLocationLabel() { return locationLabel; }
        public long getEnteredAtEpochMillis() { return enteredAtEpochMillis; }
        public long getLeftAtEpochMillis() { return leftAtEpochMillis; }
        public long getActiveMillis() { return Math.max(0L, leftAtEpochMillis - enteredAtEpochMillis); }

        private Segment clip(long from, long to)
        {
            long start = Math.max(enteredAtEpochMillis, from);
            long end = Math.min(leftAtEpochMillis, to);
            return end <= start ? null : new Segment(locationLabel, start, end);
        }
    }
}
