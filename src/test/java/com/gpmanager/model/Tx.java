package com.gpmanager;

import java.util.List;

/** Shorthand for Ac's full constructor with the automatic-classification defaults. */
public final class Tx
{
    private Tx()
    {
    }

    public static Ac of(long at, Ai type, Aj context, String note,
        boolean counted, List<Ab> flows)
    {
        return of(at, null, type, context, note, "General", counted, flows);
    }

    public static Ac of(long at, Long activeElapsedMillis, Ai type,
        Aj context, String note, boolean counted, List<Ab> flows)
    {
        return of(at, activeElapsedMillis, type, context, note, "General", counted, flows);
    }

    public static Ac of(long at, Long activeElapsedMillis, Ai type,
        Aj context, String note, String activityName, boolean counted, List<Ab> flows)
    {
        return new Ac(at, activeElapsedMillis, type, context, note, activityName, counted, flows,
            Bd.LIKELY, "Automatically classified from a stable inventory/equipment change.",
            null);
    }
}
