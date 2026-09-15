package com.gpmanager;

public enum InfoBoxView
{
    /** Activity, profit, and rate only. */
    MINIMAL("Minimal"),
    /** Slim dedicated always-on overlay (approved Compact HUD). */
    COMPACT("Compact"),
    DETAILED("Detailed"),
    CUSTOM("Custom");

    private final String label;

    InfoBoxView(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
