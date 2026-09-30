package com.gpmanager;

import java.lang.reflect.Field;

/** Test fingerprint support for persisted counters that presentation no longer reads. */
final class SessionCounts {
    static int counted(Ad session) { return count(session, false); }
    static int transfers(Ad session) { return count(session, true); }

    private static int count(Ad session, boolean transfers) {
        int count;
        try {
            Field field = Ad.class.getDeclaredField(transfers ? "retainedTransfers" : "retainedCountedTransactions");
            field.setAccessible(true);
            count = field.getInt(session);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        for (Ac transaction : session.getTransactions()) {
            if (transaction == null) { continue; }
            Bp.Ax amounts = Bp.transaction(transaction);
            if (amounts.transfer ? transfers : !transfers && amounts.available && amounts.included) {
                count++;
            }
        }
        return count;
    }
}
