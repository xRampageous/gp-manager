package com.gpmanager;

/**
 * HUD+ reward tray layout over the same session/streak accumulation.
 * Separate from reveal-animation timing and from streak inactivity reset.
 * Replaces the legacy {@code keepRewardTrayExpanded} boolean.
 */
public enum HudPlusRewardPresentation
{
    AUTO_COLLAPSE("Auto-collapse"),
    KEEP_EXPANDED("Always expanded");

    private final String label;

    HudPlusRewardPresentation(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
