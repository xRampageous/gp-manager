package com.gpmanager.model;

public enum HistorySort
{
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    PROFIT_HIGH("Highest profit"),
    PROFIT_LOW("Largest loss"),
    RATE_HIGH("Highest GP/hour"),
    DURATION_LONG("Longest duration"),
    NAME_A_Z("Name A–Z");

    private final String displayName;

    HistorySort(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
