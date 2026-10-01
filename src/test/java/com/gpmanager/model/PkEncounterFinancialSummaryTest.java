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
        PkEncounter encounter = encounter();
        Transaction receipt = transaction(true, TransactionType.LOOT, 500L, -100L);

        encounter.setFinancialContribution(receipt);
        assertEquals(400L, encounter.getFinancialNetGp());
        assertEquals(0L, encounter.getFinancialLossGp());

        receipt.applyCorrection(Correction.COST, 20L, "Manual cost");
        encounter.setFinancialContribution(receipt);
        assertEquals(-600L, encounter.getFinancialNetGp());
        assertEquals(600L, encounter.total().getCostsGp());
    }

    @Test
    public void excludesUncountedIgnoredAndTransferTransactions()
    {
        PkEncounter encounter = encounter();
        Transaction uncounted = transaction(false, TransactionType.LOOT, 900L, -50L);
        Transaction ignored = transaction(true, TransactionType.LOOT, 300L, -100L);
        ignored.applyCorrection(Correction.IGNORE, 30L, "Ignore");
        Transaction transfer = transaction(true, TransactionType.TRANSFER, 400L, -200L);

        encounter.setFinancialContribution(uncounted);
        encounter.setFinancialContribution(ignored);
        encounter.setFinancialContribution(transfer);

        assertEquals(0L, encounter.getFinancialNetGp());
        assertEquals(0L, encounter.total().getCostsGp());
    }

    @Test
    public void undoRemovesDetailedContributionAndCompactionRetainsTotals()
    {
        PkEncounter encounter = encounter();
        Transaction first = transaction(true, TransactionType.LOOT, 250L, -75L);
        Transaction undone = transaction(true, TransactionType.LOOT, 100L, -20L);
        encounter.setFinancialContribution(first);
        encounter.setFinancialContribution(undone);

        assertTrue(encounter.removeFinancialContribution(undone.getId()));
        assertEquals(175L, encounter.getFinancialNetGp());
        assertTrue(encounter.compactFinancialContribution(first.getId()));
        assertFalse(encounter.compactFinancialContribution(first.getId()));
        assertEquals(175L, encounter.getFinancialNetGp());
        assertEquals(0L, encounter.getFinancialLossGp());
        assertEquals(75L, encounter.total().getCostsGp());
        assertFalse(encounter.removeFinancialContribution(first.getId()));
    }

    @Test
    public void gsonRoundTripPreservesCompactedAndDetailedSummary()
    {
        Gson gson = new Gson();
        PkEncounter encounter = encounter();
        Transaction compacted = transaction(true, TransactionType.LOOT, 500L, -100L);
        Transaction detailed = transaction(true, TransactionType.LOOT, 200L, -50L);
        encounter.setFinancialContribution(compacted);
        encounter.setFinancialContribution(detailed);
        encounter.compactFinancialContribution(compacted.getId());

        PkEncounter restored = gson.fromJson(gson.toJson(encounter), PkEncounter.class);
        assertEquals(550L, restored.getFinancialNetGp());
        assertEquals(0L, restored.getFinancialLossGp());
        assertTrue(restored.removeFinancialContribution(detailed.getId()));
        assertEquals(400L, restored.getFinancialNetGp());
    }

    private static PkEncounter encounter()
    {
        return new PkEncounter(EncounterType.KILL, 1L, "Victim",
            ClassificationConfidence.CONFIRMED, "test");
    }

    private static Transaction transaction(boolean counted, TransactionType type,
        long gainGp, long costGp)
    {
        return Tx.of(2L, type, Context.GENERIC, "PK receipt", counted,
            Arrays.asList(
                new Flow(1, "Loot", 1L, (int) gainGp, gainGp),
                new Flow(2, "Cost", -1L, (int) -costGp, costGp)));
    }
}
