package com.gpmanager.reward;

import com.gpmanager.model.ItemPriceSource;

/** One item stack inside an observed or confirmed reward. */
public final class RewardItem
{
    private final int itemId;
    private final String itemName;
    private final long quantity;
    private final long recordedValue;
    private final boolean valueKnown;
    private final ItemPriceSource priceSource;

    public RewardItem(
        int itemId,
        String itemName,
        long quantity,
        long recordedValue,
        boolean valueKnown,
        ItemPriceSource priceSource)
    {
        this.itemId = itemId;
        this.itemName = itemName == null ? "" : itemName;
        this.quantity = quantity;
        this.recordedValue = recordedValue;
        this.valueKnown = valueKnown;
        this.priceSource = priceSource == null ? ItemPriceSource.UNKNOWN : priceSource;
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

    public long getRecordedValue()
    {
        return recordedValue;
    }

    public boolean isValueKnown()
    {
        return valueKnown;
    }

    public ItemPriceSource getPriceSource()
    {
        return priceSource;
    }

    public String compactLabel()
    {
        long absolute = quantity == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(quantity);
        if (absolute <= 1L)
        {
            if (quantity < 0L)
            {
                // ASCII hyphen — RS fonts lack U+2212 minus.
                return itemName + " -1";
            }
            return quantity <= 0L ? itemName : itemName + " ×1";
        }
        return quantity < 0L ? itemName + " -" + absolute : itemName + " ×" + absolute;
    }

    /** True when this stack represents a used/lost quantity (HUD+ loss rows). */
    public boolean isLoss()
    {
        return quantity < 0L || recordedValue < 0L;
    }

    public RewardItem merge(RewardItem other)
    {
        if (other == null || other.itemId != itemId)
        {
            return this;
        }
        long mergedQty = quantity + other.quantity;
        long mergedValue;
        boolean known;
        if (valueKnown && other.valueKnown)
        {
            mergedValue = recordedValue + other.recordedValue;
            known = true;
        }
        else if (valueKnown)
        {
            // Keep stack GP honest when the other side lacks a price: extend by unit.
            mergedValue = extendByUnit(recordedValue, quantity, other.quantity);
            known = true;
        }
        else if (other.valueKnown)
        {
            mergedValue = extendByUnit(other.recordedValue, other.quantity, quantity);
            known = true;
        }
        else
        {
            // Both unknown — still sum deltas when present; never Math.max unit leftovers.
            mergedValue = recordedValue + other.recordedValue;
            known = false;
        }
        return new RewardItem(
            itemId,
            itemName.isEmpty() ? other.itemName : itemName,
            mergedQty,
            mergedValue,
            known,
            priceSource == ItemPriceSource.UNKNOWN ? other.priceSource : priceSource);
    }

    /** Extend a known stack value by {@code extraQty} at the same unit rate. */
    private static long extendByUnit(long knownValue, long knownQty, long extraQty)
    {
        if (extraQty == 0L)
        {
            return knownValue;
        }
        long absQty = knownQty == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(knownQty);
        if (absQty == 0L)
        {
            return knownValue;
        }
        long unit = knownValue / knownQty;
        return knownValue + unit * extraQty;
    }

    public boolean beats(RewardItem other)
    {
        if (other == null)
        {
            return true;
        }
        if (valueKnown != other.valueKnown)
        {
            return valueKnown;
        }
        long absValue = Math.abs(recordedValue);
        long otherAbsValue = Math.abs(other.recordedValue);
        if (absValue != otherAbsValue)
        {
            return absValue > otherAbsValue;
        }
        long absQty = Math.abs(quantity);
        long otherAbsQty = Math.abs(other.quantity);
        if (absQty != otherAbsQty)
        {
            return absQty > otherAbsQty;
        }
        return itemId > other.itemId;
    }
}
