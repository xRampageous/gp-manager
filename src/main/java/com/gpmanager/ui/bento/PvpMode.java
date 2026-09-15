package com.gpmanager.ui.bento;

/** When Live switches to its PvP layout (SIDEBAR_BENTO.md §3). */
public enum PvpMode
{
    AUTO("Auto"),
    ALWAYS("Always"),
    NEVER("Never");

    private final String label;

    PvpMode(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
