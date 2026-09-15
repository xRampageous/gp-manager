package com.gpmanager.ui.bento;

/**
 * What an automatic boundary (slayer task, raid, bank visit) does. Off leaves free play
 * alone; Mark paints a dot on the Live ribbon; New session starts and ends sessions for you.
 */
public enum BoundaryMode
{
    OFF("Off"),
    MARK("Mark"),
    NEW_SESSION("New session");

    private final String label;

    BoundaryMode(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
