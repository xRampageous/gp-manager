package com.gpmanager;

import java.lang.reflect.Field;

/** Test fingerprint support for persisted counters that presentation no longer reads. */
final class SessionCounts {
    static int counted(Session session) { return count(session, false); }
    static int transfers(Session session) { return count(session, true); }

    private static int count(Session session, boolean transfers) {
        int count;
        try {
            Field field = Session.class.getDeclaredField(transfers ? "retainedTransfers" : "retainedCountedTransactions");
            field.setAccessible(true);
            count = field.getInt(session);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        for (Transaction transaction : session.getTransactions()) {
            if (transaction == null) { continue; }
            AccountingProjection.TransactionAmounts amounts = AccountingProjection.transaction(transaction);
            if (amounts.transfer ? transfers : !transfers && amounts.available && amounts.included) {
                count++;
            }
        }
        return count;
    }
}
