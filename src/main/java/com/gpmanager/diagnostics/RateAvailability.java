package com.gpmanager.diagnostics;

/**
 * One shared rule for Live, HUD, and Party personal-rate availability.
 * Insufficient active time is unavailable — never shown as 0 gp/h.
 */
public final class RateAvailability
{
    public static final long MIN_ACTIVE_MILLIS = 60_000L;

    private RateAvailability()
    {
    }

    public static boolean isEstablished(long activeMillis)
    {
        return activeMillis >= MIN_ACTIVE_MILLIS;
    }

    public static String unavailableLabel()
    {
        return "—";
    }

    public static String unavailableTooltip()
    {
        return "Warming up — need ~1m of active tracking time before GP/hr is shown";
    }
}
