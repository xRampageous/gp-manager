package com.gpmanager;

/** Supported detailed-receipt windows; zero days means retain detail forever. */
public enum ReceiptRetentionPeriod
{
    DAYS_30("30 days", 30),
    DAYS_90("90 days", 90),
    DAYS_180("180 days", 180),
    DAYS_365("365 days", 365),
    FOREVER("Forever", 0);

    private final String label;
    private final int days;

    ReceiptRetentionPeriod(String label, int days)
    {
        this.label = label;
        this.days = days;
    }

    public int getDays()
    {
        return days;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
