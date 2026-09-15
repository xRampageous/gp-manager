package com.gpmanager.model;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable result of calculating progress against a {@link GoalDefinition}. */
public final class GoalProgress
{
    private static final BigInteger MILLIS_PER_HOUR = BigInteger.valueOf(3_600_000L);

    private final boolean available;
    private final long currentValue;
    private final long targetValue;
    private final double percentComplete;
    private final boolean reached;
    private final long remainingValue;
    private final List<Integer> reachedMilestones;
    private final String unavailableReason;

    private GoalProgress(
        boolean available,
        long currentValue,
        long targetValue,
        double percentComplete,
        boolean reached,
        long remainingValue,
        List<Integer> reachedMilestones,
        String unavailableReason)
    {
        this.available = available;
        this.currentValue = currentValue;
        this.targetValue = targetValue;
        this.percentComplete = percentComplete;
        this.reached = reached;
        this.remainingValue = remainingValue;
        this.reachedMilestones = Collections.unmodifiableList(new ArrayList<>(reachedMilestones));
        this.unavailableReason = unavailableReason;
    }

    /**
     * Calculate progress from values already aggregated for the goal's scope.
     * GP/hour is derived from net GP and active elapsed milliseconds; the
     * caller owns scope selection and must exclude paused time.
     */
    public static GoalProgress calculate(
        GoalDefinition definition,
        long netGp,
        long elapsedMillis,
        long kills,
        long itemCount)
    {
        if (definition == null || !definition.isConfigured())
        {
            return unavailable(definition == null ? 0L : definition.getTargetValue(), "GOAL_NOT_CONFIGURED");
        }

        long current;
        switch (definition.getKind())
        {
            case NET:
                current = netGp;
                break;
            case GP_PER_HOUR:
                if (elapsedMillis <= 0L)
                {
                    return unavailable(definition.getTargetValue(), "ELAPSED_TIME_UNAVAILABLE");
                }
                BigInteger rate = BigInteger.valueOf(netGp).multiply(MILLIS_PER_HOUR)
                    .divide(BigInteger.valueOf(elapsedMillis));
                if (rate.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                    || rate.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0)
                {
                    return unavailable(definition.getTargetValue(), "RATE_OVERFLOW");
                }
                current = rate.longValue();
                break;
            case KILLS:
                current = Math.max(0L, kills);
                break;
            case ITEM_COUNT:
                current = Math.max(0L, itemCount);
                break;
            default:
                return unavailable(definition.getTargetValue(), "GOAL_KIND_UNAVAILABLE");
        }
        return available(definition, current);
    }

    /** Creates an explicit unavailable result for a configured goal whose evidence is incomplete. */
    public static GoalProgress unavailable(GoalDefinition definition, String reason)
    {
        return unavailable(definition == null ? 0L : definition.getTargetValue(),
            reason == null || reason.trim().isEmpty() ? "EVIDENCE_UNAVAILABLE" : reason.trim());
    }

    private static GoalProgress available(GoalDefinition definition, long current)
    {
        long target = definition.getTargetValue();
        double rawPercent = ((double) current / (double) target) * 100.0d;
        double percent = Math.max(0.0d, Math.min(100.0d, rawPercent));
        boolean reached = current >= target;
        List<Integer> reachedMilestones = new ArrayList<>();
        for (Integer milestone : definition.getMilestonePercentages())
        {
            if (rawPercent >= milestone)
            {
                reachedMilestones.add(milestone);
            }
        }
        return new GoalProgress(true, current, target, percent, reached,
            remaining(target, current), reachedMilestones, "");
    }

    private static GoalProgress unavailable(long target, String reason)
    {
        return new GoalProgress(false, 0L, target, 0.0d, false, target,
            Collections.emptyList(), reason);
    }

    private static long remaining(long target, long current)
    {
        BigInteger difference = BigInteger.valueOf(target).subtract(BigInteger.valueOf(current));
        if (difference.signum() <= 0)
        {
            return 0L;
        }
        return difference.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
            ? Long.MAX_VALUE
            : difference.longValue();
    }

    public boolean isAvailable() { return available; }
    public long getCurrentValue() { return currentValue; }
    public long getTargetValue() { return targetValue; }
    /** Clamped to 0..100; over-target values remain available through {@link #isReached()}. */
    public double getPercentComplete() { return percentComplete; }
    public boolean isReached() { return reached; }
    public long getRemainingValue() { return remainingValue; }
    public List<Integer> getReachedMilestones() { return reachedMilestones; }
    public String getUnavailableReason() { return unavailableReason; }
}
