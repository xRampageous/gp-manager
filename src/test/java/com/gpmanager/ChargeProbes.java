package com.gpmanager;

import java.util.List;

/** Read-only charge lookups the tests assert on; the plugin itself never needs them. */
final class ChargeProbes
{
    private ChargeProbes()
    {
    }

    /** The family's first pinned local cast graphic (d20); 0 means no automatic estimate. */
    static int nx(ChargeCheck.ChargedItem variant)
    {
        return variant.graphics.length == 0 ? 0 : variant.graphics[0];
    }

    /** The journal's still-open estimated quantity of one component for one weapon. */
    static long adp(ChargeEstimateJournal journal, String sessionId, String targetIdentity, String family, int itemId)
    {
        long total = 0L;
        for (ChargeEstimateJournal.Receipt receipt : journal.receipts(sessionId, targetIdentity, itemId))
        {
            total = SafeMath.sum(total, Math.max(0L, receipt.remaining));
        }
        return total;
    }
}
