package com.gpmanager.model;

/**
 * Generalized Insights buckets only. HUD / Live use specific skill, NPC, or
 * object labels — never these category names as the current-activity title.
 */
public enum InsightsActivityCategory
{
    SKILLING("Skilling"),
    PVM("PvM"),
    RAIDS("Raid"),
    PK("PvP"),
    TRADING("Trading"),
    OTHER("Misc");

    private final String displayName;

    InsightsActivityCategory(String displayName)
    {
        this.displayName = displayName;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
