package com.gpmanager.ui;

/** One-shot, short-lived evidence that can authorize a Prayer XP title. */
public final class PrayerAltarTitleEvidence
{
    private long expiresAtEpochMillis;

    /** Arm only after an explicit Use-on-altar menu pair. */
    public void arm(long now, long lifetimeMillis)
    {
        clear();
        if (lifetimeMillis <= 0L)
        {
            return;
        }
        expiresAtEpochMillis = now > Long.MAX_VALUE - lifetimeMillis
            ? Long.MAX_VALUE
            : now + lifetimeMillis;
    }

    /** Positive Prayer XP may consume the candidate once, and only before expiry. */
    public boolean consumeForPrayerXp(long now)
    {
        boolean supported = expiresAtEpochMillis > now;
        clear();
        return supported;
    }

    public boolean isArmed(long now)
    {
        return expiresAtEpochMillis > now;
    }

    public void clear()
    {
        expiresAtEpochMillis = 0L;
    }
}
