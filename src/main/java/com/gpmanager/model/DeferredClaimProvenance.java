package com.gpmanager.model;

import java.util.Locale;

/**
 * Presentation-only provenance for an untradeable key whose contents are not yet
 * owned. This is deliberately separate from Wilderness loot-key manifests.
 */
public final class DeferredClaimProvenance
{
    public enum Status
    {
        PENDING,
        CLAIMED,
        LOST_ON_DEATH
    }

    private int keyItemId;
    private String keyItemName;
    private String chestName;
    private long receivedQuantity;
    private long remainingQuantity;
    private long claimedQuantity;
    private long lostQuantity;
    private long receivedAtEpochMillis;
    private long updatedAtEpochMillis;
    private Status status;

    public DeferredClaimProvenance()
    {
        // Gson
    }

    public DeferredClaimProvenance(int keyItemId, String keyItemName, String chestName,
        long quantity, long receivedAtEpochMillis)
    {
        this.keyItemId = keyItemId;
        this.keyItemName = safe(keyItemName, "Key");
        this.chestName = safe(chestName, "Chest");
        this.receivedQuantity = Math.max(0L, quantity);
        this.remainingQuantity = Math.max(0L, quantity);
        this.receivedAtEpochMillis = Math.max(0L, receivedAtEpochMillis);
        this.updatedAtEpochMillis = this.receivedAtEpochMillis;
        this.status = Status.PENDING;
    }

    public int getKeyItemId() { return keyItemId; }
    public String getKeyItemName() { return keyItemName == null ? "Key" : keyItemName; }
    public String getChestName() { return chestName == null ? "Chest" : chestName; }
    public long getReceivedQuantity() { return Math.max(0L, receivedQuantity); }
    public long getRemainingQuantity() { return Math.max(0L, remainingQuantity); }
    public long getClaimedQuantity() { return Math.max(0L, claimedQuantity); }
    public long getLostQuantity() { return Math.max(0L, lostQuantity); }
    public long getReceivedAtEpochMillis() { return Math.max(0L, receivedAtEpochMillis); }
    public long getUpdatedAtEpochMillis() { return Math.max(0L, updatedAtEpochMillis); }
    public Status getStatus() { return status == null ? Status.PENDING : status; }

    public boolean isPending()
    {
        return getStatus() == Status.PENDING && getRemainingQuantity() > 0L;
    }

    public DeferredClaimProvenance claim(long quantity, long now)
    {
        DeferredClaimProvenance copy = copy();
        long applied = Math.min(copy.remainingQuantity, Math.max(0L, quantity));
        copy.remainingQuantity -= applied;
        copy.claimedQuantity = safeAdd(copy.claimedQuantity, applied);
        copy.updatedAtEpochMillis = Math.max(copy.updatedAtEpochMillis, now);
        if (copy.remainingQuantity == 0L)
        {
            copy.status = Status.CLAIMED;
        }
        return copy;
    }

    public DeferredClaimProvenance lostOnDeath(long quantity, long now)
    {
        DeferredClaimProvenance copy = copy();
        long applied = Math.min(copy.remainingQuantity, Math.max(0L, quantity));
        copy.remainingQuantity -= applied;
        copy.lostQuantity = safeAdd(copy.lostQuantity, applied);
        copy.updatedAtEpochMillis = Math.max(copy.updatedAtEpochMillis, now);
        if (copy.remainingQuantity == 0L)
        {
            copy.status = Status.LOST_ON_DEATH;
        }
        return copy;
    }

    public String summary()
    {
        switch (getStatus())
        {
            case CLAIMED:
                return "Key claimed · " + getChestName();
            case LOST_ON_DEATH:
                return "Key lost on death · " + getChestName();
            case PENDING:
            default:
                return "Key held · " + getChestName();
        }
    }

    public DeferredClaimProvenance copy()
    {
        DeferredClaimProvenance copy = new DeferredClaimProvenance();
        copy.keyItemId = keyItemId;
        copy.keyItemName = getKeyItemName();
        copy.chestName = getChestName();
        copy.receivedQuantity = getReceivedQuantity();
        copy.remainingQuantity = getRemainingQuantity();
        copy.claimedQuantity = getClaimedQuantity();
        copy.lostQuantity = getLostQuantity();
        copy.receivedAtEpochMillis = getReceivedAtEpochMillis();
        copy.updatedAtEpochMillis = getUpdatedAtEpochMillis();
        copy.status = getStatus();
        return copy;
    }

    private static String safe(String value, String fallback)
    {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static long safeAdd(long left, long right)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException ignored)
        {
            return Long.MAX_VALUE;
        }
    }
}
