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
        Ab consumedDose = new Ab(2434, "Prayer potion(4)", -1L, 100, -100L);
        Ab remainingDose = new Ab(139, "Prayer potion(3)", 1L, 60, 60L);
        Ab companionGain = new Ab(1942, "Potato", 1L, 25, 25L);
        Ac transaction = new Ac(10_000L, null,
            Ai.CONSUMPTION, Aj.GENERIC, "", "Vorkath", true,
            Arrays.asList(consumedDose, remainingDose, companionGain),
            Bd.CONFIRMED, "Drink evidence", null);
        transaction.setActionKind(Au.DRINK);

        List<Af> contributions = Af.project(transaction);
        assertEquals("one normalized potion plus the unrelated gain", 2, contributions.size());
        Af supply = contributions.get(0);
        assertTrue(supply.normalizedConsume);
        assertEquals("Prayer potion", supply.itemName);
        assertEquals(-40L, supply.effectiveValue);
        assertEquals(2, supply.rawFlows.size());
        assertEquals(Af.Category.SUPPLY, supply.category);
        Af companion = contributions.get(1);
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
        Ac pizza = transaction(new Ab(1, "Whole pizza", -1L, 500, -500L),
            new Ab(1, "Half pizza", 1L, 250, 250L), Au.EAT);
        List<Af> food = Af.project(pizza);
        assertEquals(1, food.size());
        assertTrue(food.get(0).normalizedConsume);
        assertEquals("pizza", food.get(0).itemName.toLowerCase(java.util.Locale.ROOT));
        assertEquals(-250L, food.get(0).effectiveValue);

        List<Ab> decantFlows = Arrays.asList(
            new Ab(2434, "Prayer potion(4)", -1L, 400, -400L),
            new Ab(139, "Prayer potion(2)", 1L, 100, 100L),
            new Ab(139, "Prayer potion(2)", 1L, 100, 100L));
        assertFalse("dose conserving changes do not form consume pairs",
            Bn.wi(decantFlows));
        Ac decant = transaction(decantFlows, Au.DECANT);
        assertTrue(Af.project(decant).stream()
            .noneMatch((itemData -> itemData.normalizedConsume)));

        List<Ab> ambiguous = Arrays.asList(
            new Ab(2434, "Prayer potion(4)", -1L, 400, -400L),
            new Ab(139, "Prayer potion(3)", 1L, 300, 300L),
            new Ab(139, "Prayer potion(3)", 1L, 300, 300L));
        assertFalse("ambiguous remainders fail closed", Bn.wi(ambiguous));
        Ac ambiguousDrink = transaction(ambiguous, Au.DRINK);
        assertTrue(Af.project(ambiguousDrink).stream()
            .noneMatch((itemData -> itemData.normalizedConsume)));
    }

    @Test
    public void adjacentDecantKeepsGrossQuotesAndRawRows()
    {
        Ac decant = transaction(Arrays.asList(
            new Ab(2434, "Prayer potion(4)", -1L, 3600, -3600L),
            new Ab(139, "Prayer potion(3)", 1L, 2600, 2600L),
            new Ab(143, "Prayer potion(1)", 1L, 1000, 1000L)), Au.DECANT);
        assertEquals(3600L, Bp.transaction(decant).revenue);
        assertEquals(3600L, Bp.transaction(decant).costs);
        assertTrue(Af.project(decant).stream()
            .noneMatch((itemData -> itemData.normalizedConsume)));
    }

    @Test
    public void conflictingQuoteSourcesDoNotNormalizeAPair()
    {
        Ac mixed = transaction(
            new Ab(2434, "Prayer potion(4)", -1L, 3600, -3600L, Av.GRAND_EXCHANGE),
            new Ab(139, "Prayer potion(3)", 1L, 2600, 2600L, Av.MANUAL_OVERRIDE), Au.DRINK);
        assertEquals(2600L, Bp.transaction(mixed).revenue);
        assertEquals(3600L, Bp.transaction(mixed).costs);
        assertTrue(Af.project(mixed).stream()
            .noneMatch((itemData -> itemData.normalizedConsume)));
    }

    @Test
    public void processingRowWithDrinkMetadataDoesNotNormalizeLastDose()
    {
        Ac processing = new Ac(10_000L, null, Ai.PROCESSING,
            Aj.PRODUCTION, "", "Herblore", true,
            List.of(new Ab(143, "Prayer potion(1)", -1L, 1000, -1000L)),
            Bd.CONFIRMED, "", null);
        processing.setActionKind(Au.DRINK);
        assertFalse(Af.project(processing).get(0).normalizedConsume);
    }

    private static Ac transaction(Ab first, Ab second, Au kind)
    {
        return transaction(Arrays.asList(first, second), kind);
    }

    private static Ac transaction(List<Ab> flows, Au kind)
    {
        Ac transaction = new Ac(10_000L, null,
            Ai.CONSUMPTION, Aj.GENERIC, "", "Vorkath", true, flows,
            Bd.CONFIRMED, "partial consume test", null);
        transaction.setActionKind(kind);
        return transaction;
    }
}
