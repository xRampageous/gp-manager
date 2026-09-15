package com.gpmanager.model;

public enum TrackingContext
{
    GENERIC(0),
    PRODUCTION(1),
    LOOT(2),
    PK_LOOT(3),
    MARKET(4),
    PK_DEATH(5),
    TRANSFER(6);

    private final int priority;

    TrackingContext(int priority)
    {
        this.priority = priority;
    }

    public int getPriority()
    {
        return priority;
    }
}
