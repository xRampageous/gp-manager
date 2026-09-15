package com.gpmanager;

/** Deterministic animation helpers kept separate from RuneLite rendering. */
final class GpDropAnimationMath
{
    private GpDropAnimationMath()
    {
    }

    static double progress(long ageMillis, long durationMillis)
    {
        if (durationMillis <= 0L)
        {
            return 1.0d;
        }
        return clamp01(Math.max(0L, ageMillis) / (double) durationMillis);
    }

    static double ease(double progress, GpDropEasing easing)
    {
        double p = clamp01(progress);
        if (easing == GpDropEasing.LINEAR)
        {
            return p;
        }
        if (easing == GpDropEasing.SMOOTH)
        {
            return p * p * (3.0d - (2.0d * p));
        }
        // Cubic ease-out gives a quick, familiar XP-drop arrival without a bounce.
        double inverse = 1.0d - p;
        return 1.0d - (inverse * inverse * inverse);
    }

    static double popScale(double progress)
    {
        double p = clamp01(progress);
        if (p < 0.65d)
        {
            return lerp(0.78d, 1.08d, ease(p / 0.65d, GpDropEasing.EASE_OUT));
        }
        return lerp(1.08d, 1.0d, ease((p - 0.65d) / 0.35d, GpDropEasing.SMOOTH));
    }

    static double fadeIn(double progress)
    {
        return ease(clamp01(progress), GpDropEasing.EASE_OUT);
    }

    private static double lerp(double start, double end, double progress)
    {
        return start + ((end - start) * clamp01(progress));
    }

    private static double clamp01(double value)
    {
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
