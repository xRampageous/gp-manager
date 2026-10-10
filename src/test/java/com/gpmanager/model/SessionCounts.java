package com.gpmanager;

/** Test fingerprint support: counted and transfer rows still held by a session. */
final class SessionCounts {
    static int counted(Session session) { return count(session, false); }
    static int transfers(Session session) { return count(session, true); }

    private static int count(Session session, boolean transfers) {
        int count = 0;
        for (Transaction transaction : session.getTransactions()) {
            if (transaction == null) { continue; }
            Accounting.Amounts amounts = Accounting.transaction(transaction);
            if (amounts.transfer ? transfers : !transfers && amounts.available && amounts.included) {
                count++;
            }
        }
        return count;
    }
}
