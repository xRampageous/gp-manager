package com.gpmanager.engine.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Compares explicit charge reads. One read establishes a baseline; only measured
 * component decreases can produce a booking-ready delta.
 */
public final class MeasuredChargeReadTracker
{
    /** Bounded number of same-variant weapons tracked side by side (two blowpipes, a spare trident). */
    private static final int MAX_TARGETS = 8;

    @Nullable
    private MeasuredChargeRead baseline;
    private String baselineIdentity;
    private long baselineAtEpochMillis;
    /** One baseline per stable target identity, most recently read last (pass 10 step 46). */
    private final java.util.LinkedHashMap<String, MeasuredChargeRead> baselines = new java.util.LinkedHashMap<>();

    /**
     * Observe a Check read. A {@code null} argument represents unrelated chat and
     * has no effect. Unsupported reads clear the baseline. A supported variant or
     * dart-type change seeds a fresh baseline without producing a delta.
     */
    @Nullable
    public synchronized MeasuredChargeDelta observe(@Nullable MeasuredChargeRead read)
    {
        return observe(read, "single-target", System.currentTimeMillis());
    }

    /**
     * Observe one exact Check read tied to the same stable menu target as the
     * previous read. A target change seeds a new baseline rather than comparing
     * two different same-variant weapons.
     */
    @Nullable
    public synchronized MeasuredChargeDelta observe(
        @Nullable MeasuredChargeRead read,
        @Nullable String targetIdentity)
    {
        return observe(read, targetIdentity, System.currentTimeMillis());
    }

    /** Observe one exact Check read and retain its local observation time. */
    @Nullable
    public synchronized MeasuredChargeDelta observe(
        @Nullable MeasuredChargeRead read,
        @Nullable String targetIdentity,
        long now)
    {
        if (read == null)
        {
            return null;
        }
        if (!read.isBookable() || targetIdentity == null || targetIdentity.trim().isEmpty())
        {
            reset();
            return null;
        }
        String safeIdentity = targetIdentity.trim();
        // Each weapon keeps its own baseline: a Check of the other blowpipe is neither a
        // decrease on this one nor a reason to forget what this one last read.
        MeasuredChargeRead previous = baselines.remove(safeIdentity);
        baselines.put(safeIdentity, read);
        while (baselines.size() > MAX_TARGETS)
        {
            baselines.remove(baselines.keySet().iterator().next());
        }
        baseline = read;
        baselineIdentity = safeIdentity;
        baselineAtEpochMillis = Math.max(0L, now);
        if (previous == null || previous.getVariant() != read.getVariant())
        {
            return null;
        }

        boolean blowpipeDartTypeSwitch = read.getVariant() == MeasuredChargeRead.Variant.TOXIC_BLOWPIPE
            && hasBlowpipeDartTypeSwitch(previous, read);

        Map<Integer, Long> previousCounts = previous.getComponentCounts();
        Map<Integer, Long> nextCounts = effectiveCurrentCounts(previous, read);

        Set<Integer> itemIds = new LinkedHashSet<>(previousCounts.keySet());
        itemIds.addAll(nextCounts.keySet());
        List<MeasuredChargeDelta.ComponentDelta> decreases = new ArrayList<>();
        for (int itemId : itemIds)
        {
            // Dart type is an independent component identity. A type switch can
            // return old darts and load new ones, so do not infer dart spend from
            // the change; scale measurements remain valid across that switch.
            if (blowpipeDartTypeSwitch && itemId != 12934)
            {
                continue;
            }
            long before = previousCounts.getOrDefault(itemId, 0L);
            long after = nextCounts.getOrDefault(itemId, 0L);
            if (after < before)
            {
                decreases.add(new MeasuredChargeDelta.ComponentDelta(itemId, after - before));
            }
        }
        return decreases.isEmpty()
            ? null
            : new MeasuredChargeDelta(read.getVariant(), decreases);
    }

    public synchronized void reset()
    {
        baseline = null;
        baselineIdentity = null;
        baselineAtEpochMillis = 0L;
        baselines.clear();
    }

    @Nullable
    public synchronized MeasuredChargeRead getBaseline()
    {
        return baseline;
    }

    /** Stable target identity paired with the current baseline, if present. */
    @Nullable
    public synchronized String getBaselineIdentity()
    {
        return baselineIdentity;
    }

    /** Local observation time for the current baseline. */
    public synchronized long getBaselineAtEpochMillis()
    {
        return Math.max(0L, baselineAtEpochMillis);
    }

    private static boolean hasBlowpipeDartTypeSwitch(
        MeasuredChargeRead previous,
        MeasuredChargeRead current)
    {
        boolean previouslyTyped = previous.hasDarts();
        boolean currentlyTyped = current.hasDarts();
        if (!previouslyTyped && currentlyTyped)
        {
            return true;
        }
        return previouslyTyped && currentlyTyped
            && previous.getDartItemId() != current.getDartItemId();
    }

    private static Map<Integer, Long> effectiveCurrentCounts(
        MeasuredChargeRead previous,
        MeasuredChargeRead current)
    {
        Map<Integer, Long> effective = new LinkedHashMap<>(current.getComponentCounts());
        if (current.getVariant() == MeasuredChargeRead.Variant.TOXIC_BLOWPIPE
            && previous.hasDarts()
            && !current.hasDarts())
        {
            effective.put(previous.getDartItemId(), 0L);
        }
        return effective;
    }
}
