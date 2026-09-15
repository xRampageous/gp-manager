package com.gpmanager.ui;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WildernessRiskCalculatorTest
{
    @Test
    public void subtractsTheMostValuableMeasuredStacksUpToSuppliedKeepCount()
    {
        WildernessRiskCalculator.Result result = WildernessRiskCalculator.calculate(
            Arrays.asList(
                new WildernessRiskCalculator.ItemStack(1, 1L, 1_000L),
                new WildernessRiskCalculator.ItemStack(2, 100L, 10L)),
            Collections.singletonList(new WildernessRiskCalculator.ItemStack(3, 1L, 500L)),
            2);

        assertTrue(result.isComplete());
        assertEquals(2_500L, result.getCarriedValue());
        assertEquals(2_000L, result.getKeptValue());
        assertEquals(500L, result.getRiskValue());
    }

    @Test
    public void zeroKeepsAllMeasuredValueAtRiskAndExcessKeepCountKeepsAll()
    {
        WildernessRiskCalculator.Result noKeeps = WildernessRiskCalculator.calculate(
            Collections.singletonList(new WildernessRiskCalculator.ItemStack(1, 5L, 20L)),
            Collections.emptyList(), 0);
        WildernessRiskCalculator.Result moreKeepsThanStacks = WildernessRiskCalculator.calculate(
            Collections.singletonList(new WildernessRiskCalculator.ItemStack(1, 5L, 20L)),
            Collections.emptyList(), 3);

        assertEquals(100L, noKeeps.getRiskValue());
        assertEquals(0L, moreKeepsThanStacks.getRiskValue());
    }

    @Test
    public void unknownPricesAndOverflowAreIncompleteInsteadOfUnderstatingRisk()
    {
        WildernessRiskCalculator.Result missingPrice = WildernessRiskCalculator.calculate(
            Collections.singletonList(new WildernessRiskCalculator.ItemStack(1, 1L, null)),
            Collections.emptyList(), 1);
        WildernessRiskCalculator.Result overflow = WildernessRiskCalculator.calculate(
            Collections.singletonList(new WildernessRiskCalculator.ItemStack(1, Long.MAX_VALUE, 2L)),
            Collections.emptyList(), 0);

        assertFalse(missingPrice.isComplete());
        assertFalse(overflow.isComplete());
    }

    @Test
    public void negativeKeepCountAndMissingContainerAreIncomplete()
    {
        assertFalse(WildernessRiskCalculator.calculate(
            Collections.emptyList(), Collections.emptyList(), -1).isComplete());
        assertFalse(WildernessRiskCalculator.calculate(null, Collections.emptyList(), 0).isComplete());
    }

    @Test(expected = IllegalStateException.class)
    public void incompleteResultCannotBeReadAsAZeroValue()
    {
        WildernessRiskCalculator.calculate(
            Collections.singletonList(new WildernessRiskCalculator.ItemStack(1, 1L, null)),
            Collections.emptyList(), 0).getRiskValue();
    }
}
