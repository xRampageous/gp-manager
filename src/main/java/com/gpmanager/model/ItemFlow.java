package com.gpmanager.model;

import java.util.Objects;

public class ItemFlow
{
    private int itemId;
    private String itemName;
    private long quantityDelta;
    private int unitPrice;
    private long valueDelta;
    private ItemPriceSource priceSource;
    /** Epoch-millis when the selected non-zero unit price was observed; zero when unavailable. */
    private long priceCapturedAtEpochMillis;

    public ItemFlow()
    {
        // Gson
    }

    public ItemFlow(int itemId, String itemName, long quantityDelta, int unitPrice, long valueDelta)
    {
        this(itemId, itemName, quantityDelta, unitPrice, valueDelta, ItemPriceSource.UNKNOWN);
    }

    public ItemFlow(
        int itemId,
        String itemName,
        long quantityDelta,
        int unitPrice,
        long valueDelta,
        ItemPriceSource priceSource)
    {
        this(itemId, itemName, quantityDelta, unitPrice, valueDelta, priceSource, 0L);
    }

    public ItemFlow(
        int itemId,
        String itemName,
        long quantityDelta,
        int unitPrice,
        long valueDelta,
        ItemPriceSource priceSource,
        long priceCapturedAtEpochMillis)
    {
        this.itemId = itemId;
        this.itemName = itemName;
        this.quantityDelta = quantityDelta;
        this.unitPrice = unitPrice;
        this.valueDelta = valueDelta;
        this.priceSource = priceSource == null ? ItemPriceSource.UNKNOWN : priceSource;
        this.priceCapturedAtEpochMillis = priceCapturedAtEpochMillis;
    }

    public int getItemId()
    {
        return itemId;
    }

    public String getItemName()
    {
        return itemName;
    }

    public long getQuantityDelta()
    {
        return quantityDelta;
    }

    public int getUnitPrice()
    {
        return unitPrice;
    }

    public long getValueDelta()
    {
        return valueDelta;
    }

    public ItemPriceSource getPriceSource()
    {
        return priceSource == null ? ItemPriceSource.UNKNOWN : priceSource;
    }

    public long getPriceCapturedAtEpochMillis()
    {
        return Math.max(0L, priceCapturedAtEpochMillis);
    }

    public boolean isGain()
    {
        return quantityDelta > 0;
    }

    public boolean isCost()
    {
        return quantityDelta < 0;
    }

    @Override
    public boolean equals(Object object)
    {
        if (this == object)
        {
            return true;
        }
        if (!(object instanceof ItemFlow))
        {
            return false;
        }
        ItemFlow itemFlow = (ItemFlow) object;
        return itemId == itemFlow.itemId
            && quantityDelta == itemFlow.quantityDelta
            && unitPrice == itemFlow.unitPrice
            && valueDelta == itemFlow.valueDelta
            && getPriceSource() == itemFlow.getPriceSource()
            && Objects.equals(itemName, itemFlow.itemName);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(itemId, itemName, quantityDelta, unitPrice, valueDelta, getPriceSource());
    }
}
