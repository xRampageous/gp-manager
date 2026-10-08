package com.gpmanager;

import java.util.List;

/** Read-only charge lookups the tests assert on; the plugin itself never needs them. */
final class ChargeProbes
{
    private ChargeProbes()
    {
    }

    /** The family's first pinned local cast graphic (d20); 0 means no automatic estimate. */
    static int nx(Ar.V variant)
    {
        return variant.graphics.length == 0 ? 0 : variant.graphics[0];
    }

    /** The journal's still-open estimated quantity of one component for one weapon and family. */
    static long adp(ChargeEstimateJournal journal, String sessionId, String targetIdentity, String family, int itemId)
    {
        List<ChargeEstimateJournal.Receipt> receipts = journal.entries.get(Ag.lower(sessionId) + "\n"
            + Ag.lower(targetIdentity) + "\n" + Ag.lower(family));
        long total = 0L;
        if (receipts != null)
        {
            for (ChargeEstimateJournal.Receipt receipt : receipts)
            {
                if (receipt.itemId == itemId)
                {
                    total = Ae.safeAdd(total, Math.max(0L, receipt.remainingQuantity));
                }
            }
        }
        return total;
    }
}
