package com.gpmanager.ui;

import javax.inject.Singleton;

/**
 * Presentation-only character idle (Idle Notifier–shaped). Never pauses accounting
 * or changes GP/hr — session AFK pause stays separate.
 *
 * <p>Busy when animating, interacting, object context is sticky, or recent skilling XP
 * (Make-X / RC gaps). Idle badge after hard-busy clears for {@link #delayMillis}, or
 * {@link #delayMillis} after the last skilling XP when XP was the only soft-busy
 * (no double-wait).
 */
@Singleton
public final class CharacterIdleModel
{
    public static final long DEFAULT_DELAY_MILLIS = 5_000L;
    public static final long MIN_DELAY_MILLIS = 2_000L;
    public static final long MAX_DELAY_MILLIS = 30_000L;

    private long delayMillis = DEFAULT_DELAY_MILLIS;
    private long idleCandidateSinceEpochMillis;
    private long lastSkillingXpEpochMillis;
    private boolean characterIdle;
    private boolean firedThisIdle;
    /** Bank / deposit UI open — presentation busy; header paints Banking not Idle. */
    private boolean bankUiOpen;

    public synchronized void setDelayMillis(long delayMillis)
    {
        this.delayMillis = clampDelay(delayMillis);
    }

    public synchronized long getDelayMillis()
    {
        return delayMillis;
    }

    public static long clampDelay(long delayMillis)
    {
        return Math.max(MIN_DELAY_MILLIS, Math.min(MAX_DELAY_MILLIS, delayMillis));
    }

    /** Soft-busy for Make-X / process gaps — presentation only. */
    public synchronized void noteSkillingXp(long now)
    {
        lastSkillingXpEpochMillis = Math.max(0L, now);
        characterIdle = false;
        firedThisIdle = false;
        idleCandidateSinceEpochMillis = 0L;
    }

    /**
     * Bank interface visibility. While open the character is not Idle — HUD+
     * shows Banking. Does not pause accounting or change GP/hr.
     */
    public synchronized void setBankUiOpen(boolean bankUiOpen)
    {
        this.bankUiOpen = bankUiOpen;
        if (bankUiOpen)
        {
            characterIdle = false;
            firedThisIdle = false;
            idleCandidateSinceEpochMillis = 0L;
        }
    }

    public synchronized boolean isBankUiOpen()
    {
        return bankUiOpen;
    }

    /**
     * @param animating true when local animation != -1
     * @param interacting true when local player has an interact target
     * @param objectSticky true when gather/process object context still in grace
     *                     or Ground Loot coalesce/lock is holding the tray
     * @return true once when character idle newly becomes true
     */
    public synchronized boolean tick(
        boolean animating,
        boolean interacting,
        boolean objectSticky,
        long now)
    {
        boolean recentXp = lastSkillingXpEpochMillis > 0L
            && now - lastSkillingXpEpochMillis < delayMillis;
        // Loot tray busy is passed as objectSticky by CharacterIdleTracker.
        boolean hardBusy = animating || interacting || objectSticky || bankUiOpen;
        if (hardBusy || recentXp)
        {
            if (hardBusy)
            {
                // Animation / interact / sticky / loot tray: full Idle delay after clear.
                idleCandidateSinceEpochMillis = 0L;
            }
            else
            {
                // XP soft-busy alone: Idle fires delayMillis after the XP drop —
                // do not stack a second full delay when the soft window ends.
                idleCandidateSinceEpochMillis = lastSkillingXpEpochMillis;
            }
            characterIdle = false;
            firedThisIdle = false;
            return false;
        }
        if (idleCandidateSinceEpochMillis <= 0L)
        {
            idleCandidateSinceEpochMillis = now;
        }
        if (now - idleCandidateSinceEpochMillis < delayMillis)
        {
            characterIdle = false;
            return false;
        }
        if (!characterIdle)
        {
            characterIdle = true;
            firedThisIdle = true;
            return true;
        }
        return false;
    }

    /** @deprecated use {@link #tick(boolean, boolean, boolean, long)} */
    @Deprecated
    public synchronized boolean tick(boolean animating, boolean interacting, long now)
    {
        return tick(animating, interacting, false, now);
    }

    public synchronized boolean isCharacterIdle()
    {
        return characterIdle;
    }

    /** Logout / hop — reset like Idle Notifier timers. */
    public synchronized void clear()
    {
        idleCandidateSinceEpochMillis = 0L;
        lastSkillingXpEpochMillis = 0L;
        characterIdle = false;
        firedThisIdle = false;
        bankUiOpen = false;
    }

    public synchronized boolean consumeIdleFire()
    {
        if (firedThisIdle)
        {
            firedThisIdle = false;
            return true;
        }
        return false;
    }
}
