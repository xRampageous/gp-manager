package com.gpmanager;

/**
 * Legacy Integrated / Floating / Off picker. Runtime feedback now follows
 * {@link TrackingDisplay} plus {@code hudPlusFloatingDrops}. Kept for migration
 * and hidden config compatibility only.
 */
public enum FeedbackStyle
{
    INTEGRATED("Integrated"),
    FLOATING("Floating"),
    OFF("Off");

    private final String label;

    FeedbackStyle(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
