package com.gpmanager;

/** Optional item information rendered beside item-specific GP drops. */
public enum GpDropItemTextMode
{
    VALUE_ONLY("Value only"),
    QUANTITY("Quantity + value"),
    ITEM_NAME("Item name + value"),
    NAME_AND_QUANTITY("Name + quantity + value");

    private final String displayName;

    GpDropItemTextMode(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
