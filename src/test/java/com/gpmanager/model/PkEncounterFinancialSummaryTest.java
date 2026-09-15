package com.gpmanager.model;

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
        ProfitTransaction receipt = transaction(true, TransactionType.LOOT, 500L, -100L);

        assertTrue(encounter.isFinancialSummaryAvailable());
        encounter.setFinancialContribution(receipt);
        assertEquals(400L, encounter.getFinancialNetGp());
        assertEquals(0L, encounter.getFinancialLossGp());

        receipt.applyCorrection(TransactionCorrection.COST, 20L, "Manual cost");
        encounter.setFinancialContribution(receipt);
        assertEquals(-600L, encounter.getFinancialNetGp());
        assertEquals(600L, encounter.getFinancialCostGp());
        assertEquals(1, encounter.getFinancialContributionSnapshots().size());
    }

    @Test
    public void excludesUncountedIgnoredAndTransferTransactions()
    {
        PkEncounter encounter = encounter();
        ProfitTransaction uncounted = transaction(false, TransactionType.LOOT, 900L, -50L);
        ProfitTransaction ignored = transaction(true, TransactionType.LOOT, 300L, -100L);
        ignored.applyCorrection(TransactionCorrection.IGNORE, 30L, "Ignore");
        ProfitTransaction transfer = transaction(true, TransactionType.TRANSFER, 400L, -200L);

        encounter.setFinancialContribution(uncounted);
        encounter.setFinancialContribution(ignored);
        encounter.setFinancialContribution(transfer);

        assertEquals(0L, encounter.getFinancialNetGp());
        assertEquals(0L, encounter.getFinancialCostGp());
        assertEquals(3, encounter.getFinancialContributionSnapshots().size());
    }

    @Test
    public void undoRemovesDetailedContributionAndCompactionRetainsTotals()
    {
        PkEncounter encounter = encounter();
        ProfitTransaction first = transaction(true, TransactionType.LOOT, 250L, -75L);
        ProfitTransaction undone = transaction(true, TransactionType.LOOT, 100L, -20L);
        encounter.setFinancialContribution(first);
        encounter.setFinancialContribution(undone);

        assertTrue(encounter.removeFinancialContribution(undone.getId()));
        assertEquals(175L, encounter.getFinancialNetGp());
        assertTrue(encounter.compactFinancialContribution(first.getId()));
        assertFalse(encounter.compactFinancialContribution(first.getId()));
        assertTrue(encounter.getFinancialContributionSnapshots().isEmpty());
        assertEquals(175L, encounter.getFinancialNetGp());
        assertEquals(0L, encounter.getFinancialLossGp());
        assertEquals(175L, encounter.getRetainedFinancialNetGp());
        assertEquals(75L, encounter.getRetainedFinancialCostsGp());
        assertFalse(encounter.removeFinancialContribution(first.getId()));
    }

    @Test
    public void gsonRoundTripPreservesCompactedAndDetailedSummaryWhileLegacyIsUnavailableUntilRebuilt()
    {
        Gson gson = new Gson();
        PkEncounter encounter = encounter();
        ProfitTransaction compacted = transaction(true, TransactionType.LOOT, 500L, -100L);
        ProfitTransaction detailed = transaction(true, TransactionType.LOOT, 200L, -50L);
        encounter.setFinancialContribution(compacted);
        encounter.setFinancialContribution(detailed);
        encounter.compactFinancialContribution(compacted.getId());

        PkEncounter restored = gson.fromJson(gson.toJson(encounter), PkEncounter.class);
        assertTrue(restored.isFinancialSummaryAvailable());
        assertEquals(550L, restored.getFinancialNetGp());
        assertEquals(0L, restored.getFinancialLossGp());
        assertEquals(1, restored.getFinancialContributionSnapshots().size());

        PkEncounter legacy = gson.fromJson("{\"id\":\"legacy\",\"transactionIds\":[]}",
            PkEncounter.class);
        assertFalse(legacy.isFinancialSummaryAvailable());
        assertEquals(0L, legacy.getFinancialNetGp());
        legacy.setFinancialContribution(compacted);
        assertFalse(legacy.isFinancialSummaryAvailable());

        legacy.rebuildFinancialContributions(Arrays.asList(compacted, detailed));
        assertTrue(legacy.isFinancialSummaryAvailable());
        assertEquals(550L, legacy.getFinancialNetGp());
        assertEquals(0L, legacy.getFinancialLossGp());
    }

    @Test
    public void locationLabelRoundTripsAndLegacyEncounterDefaultsToUnknown()
    {
        Gson gson = new Gson();
        PkEncounter encounter = encounter();
        encounter.setLocationLabel("Wilderness");
        PkEncounter restored = gson.fromJson(gson.toJson(encounter), PkEncounter.class);
        assertEquals("Wilderness", restored.getLocationLabel());

        PkEncounter legacy = gson.fromJson(
            "{\"id\":\"old\",\"type\":\"DEATH\",\"timestampEpochMillis\":10,\"transactionIds\":[]}",
            PkEncounter.class);
        assertEquals(null, legacy.getLocationLabel());
        legacy.setLocationLabel("  ");
        assertEquals(null, legacy.getLocationLabel());
    }

    private static PkEncounter encounter()
    {
        return new PkEncounter(PkEncounterType.KILL, 1L, "Victim",
            ClassificationConfidence.CONFIRMED, "test");
    }

    private static ProfitTransaction transaction(boolean counted, TransactionType type,
        long gainGp, long costGp)
    {
        return new ProfitTransaction(2L, type, TrackingContext.GENERIC, "PK receipt", counted,
            Arrays.asList(
                new ItemFlow(1, "Loot", 1L, (int) gainGp, gainGp),
                new ItemFlow(2, "Cost", -1L, (int) -costGp, costGp)));
    }
}
