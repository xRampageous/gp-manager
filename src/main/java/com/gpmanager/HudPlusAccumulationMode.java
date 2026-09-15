package com.gpmanager;

/** Lifetime of the HUD+ reward rows; accounting and history are unaffected. */
public enum HudPlusAccumulationMode
{
    SESSION("Session"),
    STREAK("Streak");

    private final String label;

    HudPlusAccumulationMode(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
