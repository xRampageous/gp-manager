package com.gpmanager;

/** Horizontal alignment inside the movable transparent GP-drop overlay. */
public enum GpDropAlignment
{
    LEFT("Left"),
    CENTER("Centre"),
    RIGHT("Right");

    private final String displayName;

    GpDropAlignment(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
