package com.gpmanager;

/** HUD+ overlay text size. Independent of legacy HUD number format. */
public enum HudPlusTextSize
{
    SMALL("Small"),
    NORMAL("Normal"),
    LARGE("Large");

    private final String label;

    HudPlusTextSize(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
