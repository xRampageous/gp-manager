package com.gpmanager.ui;

/**
 * Presentation-only latest-change notification. Accounting stays untouched.
 */
public final class LatestChangeNotice
{
    private final int itemId;
    private final String itemName;
    private final long quantity;
    private final long valueDelta;
    private final int itemCount;
    private final boolean correction;
    private final long createdAtEpochMillis;
    private final long expiresAtEpochMillis;

    public LatestChangeNotice(
        int itemId,
        String itemName,
        long quantity,
        long valueDelta,
        int itemCount,
        boolean correction,
        long createdAtEpochMillis,
        long expiresAtEpochMillis)
    {
        this.itemId = itemId;
        this.itemName = itemName == null ? "" : itemName;
        this.quantity = quantity;
        this.valueDelta = valueDelta;
        this.itemCount = Math.max(1, itemCount);
        this.correction = correction;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    public int getItemId()
    {
        return itemId;
    }

    public String getItemName()
    {
        return itemName;
    }

    public long getQuantity()
    {
        return quantity;
    }

    public long getValueDelta()
    {
        return valueDelta;
    }

    public int getItemCount()
    {
        return itemCount;
    }

    public boolean isCorrection()
    {
        return correction;
    }

    public long getCreatedAtEpochMillis()
    {
        return createdAtEpochMillis;
    }

    public long getExpiresAtEpochMillis()
    {
        return expiresAtEpochMillis;
    }

    public boolean isExpired(long now)
    {
        return now >= expiresAtEpochMillis;
    }

    public boolean isMuted(long now)
    {
        long life = Math.max(1L, expiresAtEpochMillis - createdAtEpochMillis);
        return now - createdAtEpochMillis > life * 2L / 3L;
    }

    public boolean canCoalesce(LatestChangeNotice other)
    {
        return other != null
            && !correction
            && !other.correction
            && itemId == other.itemId
            && itemCount == 1
            && other.itemCount == 1
            && Long.signum(valueDelta) == Long.signum(other.valueDelta);
    }

    public LatestChangeNotice coalesce(LatestChangeNotice next, long now, long durationMillis)
    {
        return new LatestChangeNotice(
            itemId,
            itemName,
            quantity + next.quantity,
            valueDelta + next.valueDelta,
            1,
            false,
            createdAtEpochMillis,
            now + Math.max(500L, durationMillis));
    }

    public String compactLabel()
    {
        if (itemCount > 1)
        {
            return itemCount + " items";
        }
        if (quantity == 0L)
        {
            return itemName;
        }
        return itemName + " ×" + Math.abs(quantity);
    }
}
