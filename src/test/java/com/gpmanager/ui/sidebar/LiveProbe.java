package com.gpmanager;

import java.util.Collections;
import java.util.List;

/** Test-side Live rows straight from receipts, without an engine. */
public final class LiveProbe
{
    private LiveProbe()
    {
    }

    /** The bounded Live preview for these receipts. */
    public static List<Ca.Recent> recent(List<Ac> transactions, Dz ctx)
    {
        Dz context = ctx == null ? Dz.NONE : ctx;
        return Ca.afa(Br.capture(transactions, Collections.emptyList(),
            "", context::td), Collections.emptyMap(), context.filter);
    }

    /** The first Live row one receipt produces; null when it produces none. */
    public static Ca.Recent toRecent(Ac transaction)
    {
        List<Ca.Recent> rows = recent(Collections.singletonList(transaction), Dz.NONE);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
