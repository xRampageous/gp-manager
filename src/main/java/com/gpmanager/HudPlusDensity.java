package com.gpmanager;

/** HUD+ content density presets — one enum instead of many toggles. */
public enum HudPlusDensity
{
    GLANCE("Glance"),
    STANDARD("Standard"),
    TRIP("Trip");

    private final String displayName;

    HudPlusDensity(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }

    public boolean showTarget()
    {
        return this != GLANCE;
    }

    public boolean showTray()
    {
        return this == TRIP;
    }
}
