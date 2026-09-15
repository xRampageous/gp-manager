package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Point-in-time, read-only contents/value for one non-accounting wealth location. */
public final class WealthLocationSnapshot
{
    public enum Status
    {
        AVAILABLE,
        INITIALIZING,
        CLOSED,
        INCOMPLETE,
        UNPRICED
    }

    /** One observed stack or offer position within the location. */
    public static final class Item
    {
        private final int slot;
        private final int itemId;
        private final String itemName;
        private final long quantity;
        private final int unitPrice;
        private final long valueGp;
        private final ItemPriceSource priceSource;
        private final long priceCapturedAtEpochMillis;
        private final boolean valueAvailable;

        public Item(int slot, int itemId, String itemName, long quantity, int unitPrice,
            ItemPriceSource priceSource, long priceCapturedAtEpochMillis)
        {
            this.slot = slot;
            this.itemId = itemId;
            this.itemName = itemName == null || itemName.trim().isEmpty()
                ? "Item " + itemId : itemName.trim();
            this.quantity = Math.max(0L, quantity);
            this.unitPrice = Math.max(0, unitPrice);
            this.priceSource = priceSource == null ? ItemPriceSource.UNKNOWN : priceSource;
            this.priceCapturedAtEpochMillis = Math.max(0L, priceCapturedAtEpochMillis);
            boolean deferredZeroValue = this.priceSource == ItemPriceSource.DEFERRED_CLAIM
                && this.unitPrice == 0;
            boolean priced = deferredZeroValue || (this.unitPrice > 0
                && this.priceSource != ItemPriceSource.UNPRICED
                && this.priceSource != ItemPriceSource.UNKNOWN);
            long value = 0L;
            if (priced)
            {
                try
                {
                    value = Math.multiplyExact(this.quantity, (long) this.unitPrice);
                }
                catch (ArithmeticException ex)
                {
                    priced = false;
                }
            }
            this.valueGp = priced ? value : 0L;
            this.valueAvailable = priced;
        }

        public int getSlot() { return slot; }
        public int getItemId() { return itemId; }
        public String getItemName() { return itemName; }
        public long getQuantity() { return quantity; }
        public int getUnitPrice() { return unitPrice; }
        public long getValueGp() { return valueGp; }
        public ItemPriceSource getPriceSource() { return priceSource; }
        public long getPriceCapturedAtEpochMillis() { return priceCapturedAtEpochMillis; }
        public boolean isValueAvailable() { return valueAvailable; }
    }

    private final String id;
    private final String title;
    private final Status status;
    private final long valueGp;
    private final long capturedAtEpochMillis;
    private final List<Item> items;
    private final String detail;

    public WealthLocationSnapshot(String id, String title, Status status, long valueGp,
        long capturedAtEpochMillis, List<Item> items, String detail)
    {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.status = status == null ? Status.INCOMPLETE : status;
        this.valueGp = Math.max(0L, valueGp);
        this.capturedAtEpochMillis = Math.max(0L, capturedAtEpochMillis);
        this.items = immutableItems(items);
        this.detail = detail == null ? "" : detail;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public Status getStatus() { return status; }
    public long getValueGp() { return valueGp; }
    public long getCapturedAtEpochMillis() { return capturedAtEpochMillis; }
    public List<Item> getItems() { return items; }
    public String getDetail() { return detail; }
    public boolean isAvailable() { return status == Status.AVAILABLE; }
    public boolean isValueAvailable() { return isAvailable(); }
    /** Wealth-location estimates never participate in accounting Net. */
    public boolean isExcludedFromNet() { return true; }

    private static List<Item> immutableItems(List<Item> input)
    {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        List<Item> copy = new ArrayList<>();
        for (Item item : input)
        {
            if (item != null) copy.add(item);
        }
        return Collections.unmodifiableList(copy);
    }
}
