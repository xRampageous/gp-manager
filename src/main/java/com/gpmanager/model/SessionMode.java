package com.gpmanager.model;

public enum SessionMode
{
    AUTO("Auto"),
    GENERAL("General"),
    PK("PK"),
    MIXED("Mixed");

    private final String displayName;

    SessionMode(String displayName)
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
