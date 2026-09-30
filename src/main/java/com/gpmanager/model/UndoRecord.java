package com.gpmanager;
/** Immutable audit snapshot for a transaction removed through the undo action. */
class UndoRecord {
String transactionId;
long timestampEpochMillis;
String activityName;
Ai type;
long net;
Ac transaction;
boolean restored;
long restoredAtEpochMillis;
UndoRecord() {
// Gson
}
UndoRecord(Ac transaction, long timestampEpochMillis) {
this.transactionId = transaction == null ? "" : transaction.getId();
this.timestampEpochMillis = timestampEpochMillis;
this.activityName = transaction == null ? "Transaction" : transaction.getActivityName();
this.type = transaction == null ? Ai.ADJUSTMENT : transaction.getType();
this.net = transaction == null ? 0L : transaction.getNet();
this.transaction = transaction;
}
String getTransactionId() { return Ag.axw(transactionId); }
String getActivityName() { return Ag.blank(activityName) ? "Transaction" : activityName; }
Ai getType() { return type == null ? Ai.ADJUSTMENT : type; }
void zp(long now) {
restored = true;
restoredAtEpochMillis = now;
}
}
