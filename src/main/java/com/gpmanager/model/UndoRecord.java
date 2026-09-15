package com.gpmanager.model;

/** Immutable audit snapshot for a transaction removed through the undo action. */
public class UndoRecord
{
    private String transactionId;
    private long timestampEpochMillis;
    private String activityName;
    private TransactionType type;
    private long net;
    private ProfitTransaction transaction;
    private boolean restored;
    private long restoredAtEpochMillis;

    public UndoRecord()
    {
        // Gson
    }

    public UndoRecord(ProfitTransaction transaction, long timestampEpochMillis)
    {
        this.transactionId = transaction == null ? "" : transaction.getId();
        this.timestampEpochMillis = timestampEpochMillis;
        this.activityName = transaction == null ? "Transaction" : transaction.getActivityName();
        this.type = transaction == null ? TransactionType.ADJUSTMENT : transaction.getType();
        this.net = transaction == null ? 0L : transaction.getNet();
        this.transaction = transaction;
    }

    public String getTransactionId() { return transactionId == null ? "" : transactionId; }
    public long getTimestampEpochMillis() { return timestampEpochMillis; }
    public String getActivityName() { return activityName == null || activityName.trim().isEmpty() ? "Transaction" : activityName; }
    public TransactionType getType() { return type == null ? TransactionType.ADJUSTMENT : type; }
    public long getNet() { return net; }
    public boolean isRestored() { return restored; }
    public long getRestoredAtEpochMillis() { return restoredAtEpochMillis; }
    public ProfitTransaction getTransaction() { return transaction; }

    public void markRestored(long now)
    {
        restored = true;
        restoredAtEpochMillis = now;
    }
}
