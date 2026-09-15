package com.gpmanager;

/** Optional RuneLite item sprites shown with transparent live GP drops. */
public enum GpDropIconMode
{
    NONE("None"),
    COINS("Coins"),
    ITEM("Primary item"),
    ITEM_OR_COINS("Primary item, otherwise coins"),
    ITEM_STRIP("Contributing item strip"),
    SMART("Smart matching icons");

    private final String displayName;

    GpDropIconMode(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
