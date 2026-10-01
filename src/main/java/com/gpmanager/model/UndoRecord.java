package com.gpmanager;
/** Immutable audit snapshot for a transaction removed through the undo action. */
class UndoRecord {
String transactionId;
long timestampEpochMillis;
String activityName;
TransactionType type;
long net;
Transaction transaction;
boolean restored;
long restoredAtEpochMillis;
UndoRecord() {
 // Gson
}

UndoRecord(Transaction transaction, long timestampEpochMillis) {
 this.transactionId = transaction == null ? "" : transaction.getId();
 this.timestampEpochMillis = timestampEpochMillis;
 this.activityName = transaction == null ? "Transaction" : transaction.getActivityName();
 this.type = transaction == null ? TransactionType.ADJUSTMENT : transaction.getType();
 this.net = transaction == null ? 0L : transaction.getNet();
 this.transaction = transaction;
}

String getTransactionId() { return ModelText.orEmpty(transactionId); }
String getActivityName() { return ModelText.blank(activityName) ? "Transaction" : activityName; }
TransactionType getType() { return type == null ? TransactionType.ADJUSTMENT : type; }
void markRestored(long now) {
 restored = true;
 restoredAtEpochMillis = now;
}
}
