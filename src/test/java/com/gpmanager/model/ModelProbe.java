package com.gpmanager;

import java.util.ArrayList;
import java.util.List;

/** Test-side reads of model state; moved out of production to keep the plugin small. */
public final class ModelProbe
{
    private ModelProbe()
    {
    }

    /** The session's corrections that have not been undone, oldest first. */
    public static List<Dx> activeCorrections(Ad session)
    {
        List<Dx> active = new ArrayList<>();
        for (Dx record : session.correctionHistory)
        {
            if (record != null && !record.isUndone())
            {
                active.add(record);
            }
        }
        return active;
    }
}
