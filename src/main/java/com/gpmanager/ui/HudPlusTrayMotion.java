package com.gpmanager.ui;

/**
 * Deterministic cassette entry/exit motion for HUD+ Trip Beacon.
 * Entry is short and eased; exit fades in the last slice of a finite dwell.
 * Activity-hold (busy / Pending Rewards) skips exit fade; Idle release starts
 * a normal dwell with exit only near expiry. AFK pause does not drive this.
 */
public final class HudPlusTrayMotion
{
    /** Fade + slide-in window after a reveal opens. */
    public static final long ENTRY_MILLIS = 280L;
    /** Soft fade before Auto-collapse / peer-flash clear. */
    public static final long EXIT_MILLIS = 320L;
    /** Max slide distance in px at entry start. */
    public static final int ENTRY_SLIDE_PX = 10;

    private HudPlusTrayMotion()
    {
    }

    /**
     * @param revealStartedAtEpochMillis when the current visual reveal opened
     * @param revealExpiresAtEpochMillis dwell deadline; frozen ({@code >= MAX/8}) = held
     * @param activityHold true while busy skilling/process or Pending Rewards
     * @param reducedMotion skip slide/fade — snap to full opacity
     */
    public static float cassetteAlpha(
        long now,
        long revealStartedAtEpochMillis,
        long revealExpiresAtEpochMillis,
        boolean activityHold,
        boolean reducedMotion)
    {
        if (reducedMotion)
        {
            return 1f;
        }
        float entry = entryProgress(now, revealStartedAtEpochMillis);
        float exit = exitProgress(now, revealExpiresAtEpochMillis, activityHold);
        float alpha = easeOutCubic(entry) * (1f - 0.55f * easeInCubic(exit));
        return clamp01(alpha);
    }

    public static int cassetteSlidePx(
        long now,
        long revealStartedAtEpochMillis,
        boolean reducedMotion)
    {
        if (reducedMotion)
        {
            return 0;
        }
        float entry = entryProgress(now, revealStartedAtEpochMillis);
        return Math.round((1f - easeOutCubic(entry)) * ENTRY_SLIDE_PX);
    }

    static float entryProgress(long now, long revealStartedAtEpochMillis)
    {
        if (revealStartedAtEpochMillis <= 0L)
        {
            return 1f;
        }
        long age = Math.max(0L, now - revealStartedAtEpochMillis);
        if (age >= ENTRY_MILLIS)
        {
            return 1f;
        }
        return age / (float) ENTRY_MILLIS;
    }

    /**
     * 0 while held or mid-dwell; rises to 1 in the last {@link #EXIT_MILLIS}.
     */
    static float exitProgress(long now, long revealExpiresAtEpochMillis, boolean activityHold)
    {
        if (activityHold
            || revealExpiresAtEpochMillis <= 0L
            || revealExpiresAtEpochMillis >= Long.MAX_VALUE / 8)
        {
            return 0f;
        }
        long remaining = revealExpiresAtEpochMillis - now;
        if (remaining >= EXIT_MILLIS)
        {
            return 0f;
        }
        if (remaining <= 0L)
        {
            return 1f;
        }
        return 1f - (remaining / (float) EXIT_MILLIS);
    }

    static float easeOutCubic(float t)
    {
        float p = clamp01(t);
        float inv = 1f - p;
        return 1f - inv * inv * inv;
    }

    static float easeInCubic(float t)
    {
        float p = clamp01(t);
        return p * p * p;
    }

    static float clamp01(float v)
    {
        return Math.max(0f, Math.min(1f, v));
    }
}
