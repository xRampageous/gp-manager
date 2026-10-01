package com.gpmanager;

import java.util.List;

/** Shorthand for Transaction's full constructor with the automatic-classification defaults. */
public final class Tx
{
    private Tx()
    {
    }

    public static Transaction of(long at, TransactionType type, Context context, String note,
        boolean counted, List<Flow> flows)
    {
        return of(at, null, type, context, note, "General", counted, flows);
    }

    public static Transaction of(long at, Long activeElapsedMillis, TransactionType type,
        Context context, String note, boolean counted, List<Flow> flows)
    {
        return of(at, activeElapsedMillis, type, context, note, "General", counted, flows);
    }

    public static Transaction of(long at, Long activeElapsedMillis, TransactionType type,
        Context context, String note, String activityName, boolean counted, List<Flow> flows)
    {
        return new Transaction(at, activeElapsedMillis, type, context, note, activityName, counted, flows,
            ClassificationConfidence.LIKELY, "Automatically classified from a stable inventory/equipment change.",
            null);
    }
}
