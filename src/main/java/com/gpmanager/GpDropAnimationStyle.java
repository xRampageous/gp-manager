package com.gpmanager;

/** Presentation-only animation applied to live GP-drop rows. */
public enum GpDropAnimationStyle
{
    STATIC("Static"),
    FADE("Fade"),
    CLASSIC_RISE("Classic rise"),
    RISE_AND_FADE("Rise + fade"),
    SLIDE_IN("Slide in"),
    POP_IN("Pop in"),
    GENTLE_FLOAT("Gentle float");

    private final String displayName;

    GpDropAnimationStyle(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
