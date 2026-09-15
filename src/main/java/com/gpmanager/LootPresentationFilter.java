package com.gpmanager;

/**
 * Optional item eligibility filter shared by reward presentation and all
 * local accounting projections. Stored source history is never deleted.
 */
public enum LootPresentationFilter
{
    ALL_ITEMS("All items"),
    FOLLOW_GROUND_ITEMS("Follow Ground Items"),
    HIGHLIGHTED_LIST_ONLY("Highlighted list only");

    private final String label;

    LootPresentationFilter(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
