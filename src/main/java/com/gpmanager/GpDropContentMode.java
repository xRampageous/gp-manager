package com.gpmanager;

/** Controls how a committed transaction becomes one or more visible GP-drop rows. */
public enum GpDropContentMode
{
    SMART("Smart"),
    TRANSACTION_TOTAL("Transaction total"),
    DIRECTION_TOTALS("Gain and cost totals"),
    EACH_ITEM("Each changed item");

    private final String displayName;

    GpDropContentMode(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
