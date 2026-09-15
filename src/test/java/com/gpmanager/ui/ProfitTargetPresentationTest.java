package com.gpmanager.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotEquals;

public class ProfitTargetPresentationTest
{
    @Test
    public void unsetAndNonPositiveTargetsHaveNoPresentation()
    {
        assertFalse(ProfitTargetPresentation.unset().isPresent());
        assertFalse(ProfitTargetPresentation.of(null, 100L, false).isPresent());
        assertFalse(ProfitTargetPresentation.of(0L, 100L, false).isPresent());
        assertFalse(ProfitTargetPresentation.of(-1L, 100L, false).isPresent());
    }

    @Test
    public void negativeNetHasZeroFillAndFullRemainingTarget()
    {
        ProfitTargetPresentation target = ProfitTargetPresentation.of(1_000L, -50L, false);

        assertTrue(target.isPresent());
        assertEquals(1_050L, target.getRemainingGp());
        assertEquals(0L, target.getSurplusGp());
        assertEquals(0f, target.getFillRatio(), 0.0001f);
        assertEquals("0%", target.percentLabel());
        assertFalse(target.isReached());
    }

    @Test
    public void tinyPositiveProgressUsesReadableLabel()
    {
        ProfitTargetPresentation target = ProfitTargetPresentation.of(1_000_000L, 1L, false);

        assertEquals(0.000001f, target.getFillRatio(), 0.0000001f);
        assertEquals("<0.1%", target.percentLabel());
        assertEquals(999_999L, target.getRemainingGp());
    }

    @Test
    public void partialProgressFloorsFillAndPreservesRawPercent()
    {
        ProfitTargetPresentation target = ProfitTargetPresentation.of(1_000L, 456L, false);

        assertEquals(45.6d, target.getRawPercent(), 0.0001d);
        assertEquals(0.456f, target.getFillRatio(), 0.0001f);
        assertEquals(544L, target.getRemainingGp());
        assertEquals("45.6%", target.percentLabel());
        assertFalse(target.isReached());
    }

    @Test
    public void exactTargetIsReachedWithNoSurplus()
    {
        ProfitTargetPresentation target = ProfitTargetPresentation.of(1_000L, 1_000L, false);

        assertTrue(target.isReached());
        assertEquals(0L, target.getRemainingGp());
        assertEquals(0L, target.getSurplusGp());
        assertEquals(1f, target.getFillRatio(), 0.0001f);
        assertEquals("reached", target.remainingOrSurplusLabel());
    }

    @Test
    public void exceededTargetShowsSurplusAndClampedFill()
    {
        ProfitTargetPresentation target = ProfitTargetPresentation.of(1_000L, 1_250L, false);

        assertTrue(target.isReached());
        assertEquals(250L, target.getSurplusGp());
        assertEquals(1f, target.getFillRatio(), 0.0001f);
        assertEquals("+250 over", target.remainingOrSurplusLabel());
        assertEquals("100%+", target.percentLabel());
    }

    @Test
    public void largeValuesDoNotOverflowRemainingOrSurplus()
    {
        ProfitTargetPresentation below = ProfitTargetPresentation.of(Long.MAX_VALUE, Long.MIN_VALUE, false);
        assertEquals(Long.MAX_VALUE, below.getRemainingGp());
        assertEquals(0f, below.getFillRatio(), 0.0001f);

        ProfitTargetPresentation above = ProfitTargetPresentation.of(Long.MAX_VALUE / 2L, Long.MAX_VALUE, false);
        assertTrue(above.isReached());
        assertEquals(Long.MAX_VALUE / 2L + 1L, above.getSurplusGp());
        assertEquals(1f, above.getFillRatio(), 0.0001f);
    }

    @Test
    public void nearTargetDoesNotDisplayOrFillAsComplete()
    {
        ProfitTargetPresentation target = ProfitTargetPresentation.of(100_000L, 99_999L, false);

        assertFalse(target.isReached());
        assertEquals(0.999f, target.getFillRatio(), 0.0001f);
        assertEquals(99, target.getFillPercent());
        assertFalse("100.0%".equals(target.percentLabel()));
    }

    @Test
    public void crossedThisUpdateOnlyFiresOnFirstReach()
    {
        assertTrue(ProfitTargetPresentation.of(100L, 100L, false).isCrossedThisUpdate());
        assertFalse(ProfitTargetPresentation.of(100L, 150L, true).isCrossedThisUpdate());
        assertFalse(ProfitTargetPresentation.of(100L, 99L, false).isCrossedThisUpdate());
    }

    @Test
    public void captionLabelUsesExplicitCurrentOverTargetFormat()
    {
        ProfitTargetPresentation target = ProfitTargetPresentation.of(100_000L, 5_100L, false);

        assertEquals("Target 5.1k / 100k", target.captionLabel());
    }

    @Test
    public void compactHudLineJoinsPercentAndRemainingOrSurplus()
    {
        ProfitTargetPresentation unreached = ProfitTargetPresentation.of(1_000L, 456L, false);
        assertEquals("45.6% · 544 gp left", unreached.compactHudLine());
        assertEquals("45.6% · 544 gp left", unreached.centeredProgressLine());
        assertEquals("/1k", unreached.netOverTargetSuffix());
        assertEquals("Total: +456/1k", unreached.totalLine(false, true));
        assertEquals("+456/1k", unreached.totalLine(false, false));

        ProfitTargetPresentation reachedWithSurplus = ProfitTargetPresentation.of(1_000L, 1_250L, false);
        assertEquals("100%+ · +250 over", reachedWithSurplus.compactHudLine());

        ProfitTargetPresentation exact = ProfitTargetPresentation.of(1_000L, 1_000L, false);
        assertEquals("100% · reached", exact.compactHudLine());
    }

    @Test
    public void veryLargeTargetsNeverRoundFillOrPercentToCompleteBeforeReached()
    {
        // 999,999,999 / 1,000,000,000 is far closer to 100% than the smaller
        // fixtures above (basis-point-scale precision) and must still clamp.
        ProfitTargetPresentation target = ProfitTargetPresentation.of(1_000_000_000L, 999_999_999L, false);

        assertFalse(target.isReached());
        assertTrue(target.getFillRatio() < 1f);
        assertEquals(99, target.getFillPercent());
        assertFalse("100.0%".equals(target.percentLabel()));
        assertFalse("100%".equals(target.percentLabel()));
    }

    @Test
    public void fillRatioIsMonotonicAndNeverNegative()
    {
        long target = 1_000L;
        float previous = -1f;
        for (long net = -500L; net <= 1_500L; net += 100L)
        {
            ProfitTargetPresentation presentation = ProfitTargetPresentation.of(target, net, false);
            assertTrue("fill ratio must be within [0,1]",
                presentation.getFillRatio() >= 0f && presentation.getFillRatio() <= 1f);
            assertTrue("fill ratio must never decrease as net rises",
                presentation.getFillRatio() >= previous);
            previous = presentation.getFillRatio();
        }
    }

    @Test
    public void nearTargetMustNotDisplayOneHundredPercentBeforeReached()
    {
        ProfitTargetPresentation target = ProfitTargetPresentation.of(100000L, 99999L, false);
        assertFalse(target.isReached());
        assertNotEquals("100.0%", target.percentLabel());
    }
}
