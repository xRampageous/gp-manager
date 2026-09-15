package com.gpmanager;

/** Determines which nearby GP-drop rows are allowed to merge. */
public enum GpDropCombineMode
{
    SMART("Smart"),
    SAME_ITEM("Same item only"),
    SAME_DIRECTION("Same gain/loss direction"),
    OFF("Never combine");

    private final String displayName;

    GpDropCombineMode(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
