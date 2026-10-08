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

UndoRecord(Ac /* transaction */a, long /* timestampEpochMillis */b) {
 this.transactionId = a == null ? "" : a.getId();
 this.timestampEpochMillis = b;
 this.activityName = a == null ? "Transaction" : a.getActivityName();
 this.type = a == null ? Ai.ADJUSTMENT : a.getType();
 this.net = a == null ? 0L : a.getNet();
 this.transaction = a;
}

String getTransactionId() { return Ag.axw(transactionId); }
String getActivityName() { return Ag.blank(activityName) ? "Transaction" : activityName; }
Ai getType() { return type == null ? Ai.ADJUSTMENT : type; }
void zp(long /* now */a) {
 restored = true;
 restoredAtEpochMillis = a;
}
}
