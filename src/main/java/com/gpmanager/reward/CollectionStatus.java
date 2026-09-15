package com.gpmanager.reward;

/** Presentation-only reward collection progress. Never affects Net profit. */
public enum CollectionStatus
{
    UNCONFIRMED("Collection unconfirmed"),
    PARTIAL("Partially collected"),
    COLLECTED("Collected");

    private final String label;

    CollectionStatus(String label)
    {
        this.label = label;
    }

    public String getLabel()
    {
        return label;
    }
}
