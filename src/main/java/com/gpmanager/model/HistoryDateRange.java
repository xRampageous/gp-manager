package com.gpmanager.model;

public enum HistoryDateRange
{
    ALL_TIME("All time", 0),
    TODAY("Today", 1),
    LAST_7_DAYS("Last 7 days", 7),
    LAST_30_DAYS("Last 30 days", 30),
    LAST_90_DAYS("Last 90 days", 90),
    LAST_365_DAYS("Last 12 months", 365);

    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private final String displayName;
    private final int days;

    HistoryDateRange(String displayName, int days)
    {
        this.displayName = displayName;
        this.days = days;
    }

    public boolean includes(long startedAtEpochMillis, long now)
    {
        if (days <= 0)
        {
            return true;
        }
        long cutoff;
        try
        {
            cutoff = Math.subtractExact(now, Math.multiplyExact((long) days, DAY_MILLIS));
        }
        catch (ArithmeticException ex)
        {
            cutoff = Long.MIN_VALUE;
        }
        return startedAtEpochMillis >= cutoff;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}
