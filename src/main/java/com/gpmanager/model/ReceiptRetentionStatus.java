package com.gpmanager.model;

/** Read-only status for UTC receipt compaction. A null next time means forever. */
public final class ReceiptRetentionStatus
{
    private final int windowDays;
    private final Long nextCompactionAtEpochMillis;
    private final int pendingCount;

    public ReceiptRetentionStatus(int windowDays, Long nextCompactionAtEpochMillis,
        int pendingCount)
    {
        this.windowDays = Math.max(0, windowDays);
        this.nextCompactionAtEpochMillis = this.windowDays == 0
            ? null : nextCompactionAtEpochMillis;
        this.pendingCount = Math.max(0, pendingCount);
    }

    public int getWindowDays() { return windowDays; }
    public Long getNextCompactionAtEpochMillis() { return nextCompactionAtEpochMillis; }
    public int getPendingCount() { return pendingCount; }
}
