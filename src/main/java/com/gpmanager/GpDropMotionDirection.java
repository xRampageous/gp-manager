package com.gpmanager;

/** Direction used by movement-based live GP-drop animations. */
public enum GpDropMotionDirection
{
    UP("Up"),
    DOWN("Down"),
    LEFT("Left"),
    RIGHT("Right");

    private final String displayName;

    GpDropMotionDirection(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
