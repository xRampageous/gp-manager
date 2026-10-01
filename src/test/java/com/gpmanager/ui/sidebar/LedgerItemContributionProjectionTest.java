package com.gpmanager;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LedgerItemContributionProjectionTest
{
    @Test
    public void doseRemainderNormalizesIntoOneSupplyAndPreservesCompanionGain()
    {
        Flow consumedDose = new Flow(2434, "Prayer potion(4)", -1L, 100, -100L);
        Flow remainingDose = new Flow(139, "Prayer potion(3)", 1L, 60, 60L);
        Flow companionGain = new Flow(1942, "Potato", 1L, 25, 25L);
        Transaction transaction = new Transaction(10_000L, null,
            TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath", true,
            Arrays.asList(consumedDose, remainingDose, companionGain),
            ClassificationConfidence.CONFIRMED, "Drink evidence", null);
        transaction.setActionKind(ActionKind.DRINK);

        List<Contribution> contributions = Contribution.project(transaction);
        assertEquals("one normalized potion plus the unrelated gain", 2, contributions.size());
        Contribution supply = contributions.get(0);
        assertTrue(supply.normalizedConsume);
        assertEquals("Prayer potion", supply.itemName);
        assertEquals(-40L, supply.effectiveValue);
        assertEquals(2, supply.rawFlows.size());
        assertEquals(Contribution.Category.SUPPLY, supply.category);
        Contribution companion = contributions.get(1);
        assertFalse(companion.normalizedConsume);
        assertEquals("Potato", companion.itemName);
        assertEquals(25L, companion.effectiveValue);

        long projected = contributions.stream().mapToLong((itemData -> itemData.effectiveValue)).sum();
        assertEquals("normalized rows reconcile to the canonical transaction", transaction.getNet(), projected);
        assertEquals("projection does not mutate canonical raw flows", 3, transaction.getFlows().size());
    }

    @Test
    public void partialFoodNormalizesButDecantAndAmbiguousPairsStayRaw()
    {
        Transaction pizza = transaction(new Flow(1, "Whole pizza", -1L, 500, -500L),
            new Flow(1, "Half pizza", 1L, 250, 250L), ActionKind.EAT);
        List<Contribution> food = Contribution.project(pizza);
        assertEquals(1, food.size());
        assertTrue(food.get(0).normalizedConsume);
        assertEquals("pizza", food.get(0).itemName.toLowerCase(java.util.Locale.ROOT));
        assertEquals(-250L, food.get(0).effectiveValue);

        List<Flow> decantFlows = Arrays.asList(
            new Flow(2434, "Prayer potion(4)", -1L, 400, -400L),
            new Flow(139, "Prayer potion(2)", 1L, 100, 100L),
            new Flow(139, "Prayer potion(2)", 1L, 100, 100L));
        assertFalse("dose conserving changes do not form consume pairs",
            ActionEvidence.isDoseOrPartialConsumeDelta(decantFlows));
        Transaction decant = transaction(decantFlows, ActionKind.DECANT);
        assertTrue(Contribution.project(decant).stream()
            .noneMatch((itemData -> itemData.normalizedConsume)));

        List<Flow> ambiguous = Arrays.asList(
            new Flow(2434, "Prayer potion(4)", -1L, 400, -400L),
            new Flow(139, "Prayer potion(3)", 1L, 300, 300L),
            new Flow(139, "Prayer potion(3)", 1L, 300, 300L));
        assertFalse("ambiguous remainders fail closed", ActionEvidence.isDoseOrPartialConsumeDelta(ambiguous));
        Transaction ambiguousDrink = transaction(ambiguous, ActionKind.DRINK);
        assertTrue(Contribution.project(ambiguousDrink).stream()
            .noneMatch((itemData -> itemData.normalizedConsume)));
    }

    @Test
    public void adjacentDecantKeepsGrossQuotesAndRawRows()
    {
        Transaction decant = transaction(Arrays.asList(
            new Flow(2434, "Prayer potion(4)", -1L, 3600, -3600L),
            new Flow(139, "Prayer potion(3)", 1L, 2600, 2600L),
            new Flow(143, "Prayer potion(1)", 1L, 1000, 1000L)), ActionKind.DECANT);
        assertEquals(3600L, AccountingProjection.transaction(decant).revenue);
        assertEquals(3600L, AccountingProjection.transaction(decant).costs);
        assertTrue(Contribution.project(decant).stream()
            .noneMatch((itemData -> itemData.normalizedConsume)));
    }

    @Test
    public void conflictingQuoteSourcesDoNotNormalizeAPair()
    {
        Transaction mixed = transaction(
            new Flow(2434, "Prayer potion(4)", -1L, 3600, -3600L, PriceSource.GRAND_EXCHANGE),
            new Flow(139, "Prayer potion(3)", 1L, 2600, 2600L, PriceSource.MANUAL_OVERRIDE), ActionKind.DRINK);
        assertEquals(2600L, AccountingProjection.transaction(mixed).revenue);
        assertEquals(3600L, AccountingProjection.transaction(mixed).costs);
        assertTrue(Contribution.project(mixed).stream()
            .noneMatch((itemData -> itemData.normalizedConsume)));
    }

    @Test
    public void processingRowWithDrinkMetadataDoesNotNormalizeLastDose()
    {
        Transaction processing = new Transaction(10_000L, null, TransactionType.PROCESSING,
            Context.PRODUCTION, "", "Herblore", true,
            List.of(new Flow(143, "Prayer potion(1)", -1L, 1000, -1000L)),
            ClassificationConfidence.CONFIRMED, "", null);
        processing.setActionKind(ActionKind.DRINK);
        assertFalse(Contribution.project(processing).get(0).normalizedConsume);
    }

    private static Transaction transaction(Flow first, Flow second, ActionKind kind)
    {
        return transaction(Arrays.asList(first, second), kind);
    }

    private static Transaction transaction(List<Flow> flows, ActionKind kind)
    {
        Transaction transaction = new Transaction(10_000L, null,
            TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath", true, flows,
            ClassificationConfidence.CONFIRMED, "partial consume test", null);
        transaction.setActionKind(kind);
        return transaction;
    }
}
