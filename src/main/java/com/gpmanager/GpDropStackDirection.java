package com.gpmanager;

/** Chooses where newly committed GP drops enter the visible stack. */
public enum GpDropStackDirection
{
    NEWEST_BOTTOM("Newest at bottom"),
    NEWEST_TOP("Newest at top");

    private final String displayName;

    GpDropStackDirection(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
