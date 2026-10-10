package com.gpmanager;

/** A transaction removed through the undo action, kept so the latest one can be restored. */
class UndoRecord {
    Transaction transaction;
    boolean restored;

    UndoRecord() {
        // Gson
    }

    UndoRecord(Transaction transaction) {
        this.transaction = transaction;
    }
}
