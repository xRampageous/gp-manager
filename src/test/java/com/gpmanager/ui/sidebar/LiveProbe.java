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
    public static List<LiveSnapshot.Recent> recent(List<Transaction> transactions, LiveContext ctx)
    {
        LiveContext context = ctx == null ? LiveContext.NONE : ctx;
        return LiveSnapshot.recentRows(SemanticFinancialProjection.capture(transactions, Collections.emptyList(),
            "", context::flowVisible), Collections.emptyMap(), context.filter);
    }

    /** The first Live row one receipt produces; null when it produces none. */
    public static LiveSnapshot.Recent toRecent(Transaction transaction)
    {
        List<LiveSnapshot.Recent> rows = recent(Collections.singletonList(transaction), LiveContext.NONE);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
