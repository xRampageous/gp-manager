package com.gpmanager.model;

public enum ProfitTrendDirection
{
    INSUFFICIENT_DATA("Not enough data"),
    IMPROVING("Improving"),
    DECLINING("Declining"),
    FLAT("Stable");

    private final String displayName;

    ProfitTrendDirection(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
