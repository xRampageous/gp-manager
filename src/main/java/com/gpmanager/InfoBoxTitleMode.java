package com.gpmanager;

public enum InfoBoxTitleMode
{
    SESSION("Session name"),
    ACTIVITY("Activity"),
    SESSION_AND_ACTIVITY("Session + activity"),
    HIDDEN("Hidden");

    private final String label;

    InfoBoxTitleMode(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
