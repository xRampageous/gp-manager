package com.gpmanager.model;

/** Approximate saved-profile footprint and retained receipt counts. */
public final class ProfileSizeEstimate
{
    private final long sessionCount;
    private final long receiptCount;
    private final long compactedReceiptCount;
    private final long approximateJsonBytes;

    public ProfileSizeEstimate(long sessionCount, long receiptCount,
        long compactedReceiptCount, long approximateJsonBytes)
    {
        this.sessionCount = Math.max(0L, sessionCount);
        this.receiptCount = Math.max(0L, receiptCount);
        this.compactedReceiptCount = Math.max(0L, compactedReceiptCount);
        this.approximateJsonBytes = Math.max(0L, approximateJsonBytes);
    }

    public long getSessionCount() { return sessionCount; }
    public long getReceiptCount() { return receiptCount; }
    public long getCompactedReceiptCount() { return compactedReceiptCount; }
    public long getApproximateJsonBytes() { return approximateJsonBytes; }
}
