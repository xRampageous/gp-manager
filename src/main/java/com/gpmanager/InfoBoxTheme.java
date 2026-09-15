package com.gpmanager;

public enum InfoBoxTheme
{
    PROFIT_LOSS("Profit / loss"),
    RUNELITE("Classic RuneLite"),
    DARK("Dark neutral"),
    CUSTOM("Custom");

    private final String label;

    InfoBoxTheme(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
