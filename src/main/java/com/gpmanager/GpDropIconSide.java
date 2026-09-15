package com.gpmanager;

/** Places optional RuneLite item sprites before or after GP-drop text. */
public enum GpDropIconSide
{
    LEFT("Before text"),
    RIGHT("After text");

    private final String displayName;

    GpDropIconSide(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
