package com.gpmanager;

/** Easing curve used for live GP-drop entry motion. */
public enum GpDropEasing
{
    LINEAR("Linear"),
    EASE_OUT("Ease out"),
    SMOOTH("Smooth");

    private final String displayName;

    GpDropEasing(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
