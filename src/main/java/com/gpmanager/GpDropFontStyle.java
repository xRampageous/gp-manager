package com.gpmanager;

/** Text rendering presets for the transparent live GP-drop overlay. */
public enum GpDropFontStyle
{
    EXPANDED_XP("Expanded XP Drops"),
    RUNESCAPE("RuneScape"),
    RUNESCAPE_BOLD("RuneScape bold"),
    RUNELITE("RuneLite");

    private final String displayName;

    GpDropFontStyle(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
