package com.gpmanager.ui;

import com.gpmanager.model.ItemPriceSource;

/**
 * Persistent highlight of the highest-value received item from the latest
 * eligible drop. Display-only — never mutates accounting.
 */
public final class LatestDropHighlight
{
    private final int itemId;
    private final String itemName;
    private final long quantity;
    private final long recordedValue;
    private final boolean valueKnown;
    private final ItemPriceSource priceSource;
    private final String encounterId;
    private final String sourceTransactionId;
    private final long createdAtEpochMillis;
    private final int generation;

    public LatestDropHighlight(
        int itemId,
        String itemName,
        long quantity,
        long recordedValue,
        boolean valueKnown,
        ItemPriceSource priceSource,
        String encounterId,
        String sourceTransactionId,
        long createdAtEpochMillis,
        int generation)
    {
        this.itemId = itemId;
        this.itemName = itemName == null ? "" : itemName;
        this.quantity = quantity;
        this.recordedValue = recordedValue;
        this.valueKnown = valueKnown;
        this.priceSource = priceSource == null ? ItemPriceSource.UNKNOWN : priceSource;
        this.encounterId = encounterId == null ? "" : encounterId;
        this.sourceTransactionId = sourceTransactionId == null ? "" : sourceTransactionId;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.generation = generation;
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

    public String getEncounterId()
    {
        return encounterId;
    }

    public String getSourceTransactionId()
    {
        return sourceTransactionId;
    }

    public long getCreatedAtEpochMillis()
    {
        return createdAtEpochMillis;
    }

    public int getGeneration()
    {
        return generation;
    }

    public LatestDropHighlight combineSameItem(LatestDropHighlight other, long now)
    {
        if (other == null || other.itemId != itemId)
        {
            return this;
        }
        boolean known = valueKnown && other.valueKnown;
        return new LatestDropHighlight(
            itemId,
            itemName,
            quantity + other.quantity,
            known ? recordedValue + other.recordedValue : Math.max(recordedValue, other.recordedValue),
            known,
            priceSource,
            encounterId.isEmpty() ? other.encounterId : encounterId,
            sourceTransactionId,
            createdAtEpochMillis,
            generation);
    }

    public String compactLabel()
    {
        if (quantity <= 0L)
        {
            return itemName;
        }
        return itemName + " ×" + quantity;
    }

    public String valueLabel()
    {
        if (!valueKnown)
        {
            return "unpriced";
        }
        return recordedValue + " gp";
    }
}
