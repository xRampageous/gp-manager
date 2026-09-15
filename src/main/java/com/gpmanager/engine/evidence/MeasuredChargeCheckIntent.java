package com.gpmanager.engine.evidence;

import javax.annotation.Nullable;

/**
 * One-shot identity evidence linking a Check menu action to its numeric chat read.
 * The click provides no quantity and can never authorize a cost on its own.
 */
public final class MeasuredChargeCheckIntent
{
    private MeasuredChargeRead.Variant variant;
    private String targetIdentity;
    private int expiresAfterTick;

    public synchronized boolean arm(
        MeasuredChargeRead.Variant variant,
        @Nullable String targetIdentity,
        int currentTick,
        int validTicks)
    {
        clear();
        if (variant == null || !variant.isImplemented()
            || targetIdentity == null || targetIdentity.trim().isEmpty() || validTicks <= 0)
        {
            return false;
        }
        this.variant = variant;
        this.targetIdentity = targetIdentity;
        this.expiresAfterTick = currentTick + validTicks;
        return true;
    }

    /** Consume only a matching exact Check message before the short click window expires. */
    @Nullable
    public synchronized String consume(@Nullable MeasuredChargeRead read, int currentTick)
    {
        if (variant == null || targetIdentity == null
            || currentTick > expiresAfterTick
            || read == null || !read.isBookable() || read.getVariant() != variant)
        {
            clear();
            return null;
        }
        String identity = targetIdentity;
        clear();
        return identity;
    }

    public synchronized void clear()
    {
        variant = null;
        targetIdentity = null;
        expiresAfterTick = 0;
    }
}
