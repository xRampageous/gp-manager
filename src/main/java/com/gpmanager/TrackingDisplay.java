package com.gpmanager;

/**
 * Primary on-screen tracking presentation. Also selects change-feedback shape:
 * HUD+ = tray (+ optional floating), HUD/Infobox = floating drops, Off = none.
 */
public enum TrackingDisplay
{
    HUD_PLUS("HUD+"),
    HUD("HUD"),
    INFOBOX("Infobox"),
    OFF("Off");

    private final String label;

    TrackingDisplay(String label)
    {
        this.label = label;
    }

    /** Dedicated custom Graphics2D overlay. */
    public boolean isHudPlus()
    {
        return this == HUD_PLUS;
    }

    /** Legacy lightweight overlay (Compact / Minimal / Detailed / Custom). */
    public boolean isLegacyHud()
    {
        return this == HUD;
    }

    public boolean isOverlay()
    {
        return this == HUD_PLUS || this == HUD;
    }

    public boolean isInfobox()
    {
        return this == INFOBOX;
    }

    public boolean showsIntegratedSurface()
    {
        return this == HUD_PLUS || this == HUD || this == INFOBOX;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
