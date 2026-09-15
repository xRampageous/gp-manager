package com.gpmanager;

/** Text backdrop for floating GP drops (Customizable XP Drops parity). */
public enum GpDropTextBackground
{
    NONE("None"),
    SHADOW("Shadow"),
    OUTLINE("Outline");

    private final String displayName;

    GpDropTextBackground(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
