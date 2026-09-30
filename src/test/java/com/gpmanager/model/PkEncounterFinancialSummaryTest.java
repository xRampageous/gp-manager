package com.gpmanager;

import com.google.gson.Gson;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PkEncounterFinancialSummaryTest
{
    @Test
    public void newEncounterTracksCorrectionAwareSnapshotsAndReplacesSameTransaction()
    {
        Bx encounter = encounter();
        Ac receipt = transaction(true, Ai.LOOT, 500L, -100L);

        encounter.ahr(receipt);
        assertEquals(400L, encounter.ur());
        assertEquals(0L, encounter.uk());

        receipt.ko(Ah.COST, 20L, "Manual cost");
        encounter.ahr(receipt);
        assertEquals(-600L, encounter.ur());
        assertEquals(600L, encounter.total().tr());
    }

    @Test
    public void excludesUncountedIgnoredAndTransferTransactions()
    {
        Bx encounter = encounter();
        Ac uncounted = transaction(false, Ai.LOOT, 900L, -50L);
        Ac ignored = transaction(true, Ai.LOOT, 300L, -100L);
        ignored.ko(Ah.IGNORE, 30L, "Ignore");
        Ac transfer = transaction(true, Ai.TRANSFER, 400L, -200L);

        encounter.ahr(uncounted);
        encounter.ahr(ignored);
        encounter.ahr(transfer);

        assertEquals(0L, encounter.ur());
        assertEquals(0L, encounter.total().tr());
    }

    @Test
    public void undoRemovesDetailedContributionAndCompactionRetainsTotals()
    {
        Bx encounter = encounter();
        Ac first = transaction(true, Ai.LOOT, 250L, -75L);
        Ac undone = transaction(true, Ai.LOOT, 100L, -20L);
        encounter.ahr(first);
        encounter.ahr(undone);

        assertTrue(encounter.afd(undone.getId()));
        assertEquals(175L, encounter.ur());
        assertTrue(encounter.pf(first.getId()));
        assertFalse(encounter.pf(first.getId()));
        assertEquals(175L, encounter.ur());
        assertEquals(0L, encounter.uk());
        assertEquals(75L, encounter.total().tr());
        assertFalse(encounter.afd(first.getId()));
    }

    @Test
    public void gsonRoundTripPreservesCompactedAndDetailedSummary()
    {
        Gson gson = new Gson();
        Bx encounter = encounter();
        Ac compacted = transaction(true, Ai.LOOT, 500L, -100L);
        Ac detailed = transaction(true, Ai.LOOT, 200L, -50L);
        encounter.ahr(compacted);
        encounter.ahr(detailed);
        encounter.pf(compacted.getId());

        Bx restored = gson.fromJson(gson.toJson(encounter), Bx.class);
        assertEquals(550L, restored.ur());
        assertEquals(0L, restored.uk());
        assertTrue(restored.afd(detailed.getId()));
        assertEquals(400L, restored.ur());
    }

    private static Bx encounter()
    {
        return new Bx(Be.KILL, 1L, "Victim",
            Bd.CONFIRMED, "test");
    }

    private static Ac transaction(boolean counted, Ai type,
        long gainGp, long costGp)
    {
        return Tx.of(2L, type, Aj.GENERIC, "PK receipt", counted,
            Arrays.asList(
                new Ab(1, "Loot", 1L, (int) gainGp, gainGp),
                new Ab(2, "Cost", -1L, (int) -costGp, costGp)));
    }
}
