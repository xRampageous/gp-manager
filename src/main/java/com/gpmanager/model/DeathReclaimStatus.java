package com.gpmanager.model;

/**
 * Read-only state for the local PvM death-reclaim indicator. The item count is the sum of
 * measured outstanding stack quantities, and age is measured in active engine game ticks.
 */
public final class DeathReclaimStatus
{
    private final boolean awaiting;
    private final boolean armed;
    private final long outstandingItemCount;
    private final long ageTicks;

    public DeathReclaimStatus(boolean awaiting, boolean armed, long outstandingItemCount, long ageTicks)
    {
        this.awaiting = awaiting;
        this.armed = awaiting && armed;
        this.outstandingItemCount = Math.max(0L, outstandingItemCount);
        this.ageTicks = Math.max(0L, ageTicks);
    }

    public boolean isAwaiting()
    {
        return awaiting;
    }

    public boolean isArmed()
    {
        return armed;
    }

    public long getOutstandingItemCount()
    {
        return outstandingItemCount;
    }

    public long getAgeTicks()
    {
        return ageTicks;
    }
}
