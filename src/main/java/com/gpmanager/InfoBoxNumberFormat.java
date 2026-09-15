package com.gpmanager;

public enum InfoBoxNumberFormat
{
    COMPACT("Compact"),
    EXACT("Exact");

    private final String label;

    InfoBoxNumberFormat(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
