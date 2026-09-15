package com.gpmanager.ui;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HudMetricPairFormatterTest
{
    private final FontMetrics metrics = metrics();

    @Test
    public void formatRateLinePreservesNegativeAndUnavailable()
    {
        assertEquals("GP/hr: -2.5k",
            HudMetricPairFormatter.formatRateLine(-2_500L, true, false, true));
        assertEquals("GP/hr: —",
            HudMetricPairFormatter.formatRateLine(0L, false, false, true));
        assertTrue(HudMetricPairFormatter.formatTotalLine(-1_800L, null, false, true)
            .startsWith("Total: -"));
    }

    @Test
    public void oneTwentyPxLargeValuesPreferStackOrFitWithoutAmbiguousRate()
    {
        HudMetricPairFormatter.Pair pair = HudMetricPairFormatter.formatValues(
            metrics, 120, -999_900_000L, -999_900_000L, true, false);
        assertTrue(pair.netText.startsWith("-"));
        assertTrue(pair.rateText.startsWith("-") || pair.rateText.equals("—"));
        if (!pair.stacked)
        {
            assertTrue(fitsSingle(pair, 120));
            assertFalse(HudMetricPairFormatter.isAmbiguousRate(pair.rateText));
        }
    }

    @Test
    public void wideBudgetKeepsFullCompactRateAndNet()
    {
        HudMetricPairFormatter.Pair pair = HudMetricPairFormatter.formatValues(
            metrics, 220, 12_400L, 99_000L, true, false);
        assertEquals("+12.4k gp", pair.netText);
        assertEquals("99k gp/h", pair.rateText);
        assertFalse(pair.stacked);
        assertTrue(fitsSingle(pair, 220));
    }

    @Test
    public void preservesNegativeNetAndRateSigns()
    {
        assertTrue(HudMetricPairFormatter.signedNet(-1_500L, false).startsWith("-"));
        assertTrue(HudMetricPairFormatter.signedNet(-1_500L, true).startsWith("-"));
        assertTrue(HudMetricPairFormatter.rateString(-2_500L, false, true).startsWith("-"));
        assertTrue(HudMetricPairFormatter.rateString(-2_500L, true, true).startsWith("-"));
        assertEquals("0 gp", HudMetricPairFormatter.signedNet(0L, false));
        assertTrue(HudMetricPairFormatter.signedNet(50L, false).startsWith("+"));
    }

    @Test
    public void mixedSignsKeepIndependentPrefixes()
    {
        HudMetricPairFormatter.Pair pair = HudMetricPairFormatter.formatValues(
            metrics, 260, 12_400L, -99_000L, true, false);
        assertTrue(pair.netText.startsWith("+"));
        assertTrue(pair.rateText.startsWith("-"));
    }

    @Test
    public void longMinAndMaxRemainFiniteAndSigned()
    {
        String minNet = HudMetricPairFormatter.signedNet(Long.MIN_VALUE, false);
        String maxNet = HudMetricPairFormatter.signedNet(Long.MAX_VALUE, false);
        assertTrue(minNet.startsWith("-"));
        assertTrue(maxNet.startsWith("+") || Character.isDigit(maxNet.charAt(0)));
        assertFalse(minNet.contains("Infinity"));
        String minRate = HudMetricPairFormatter.rateString(Long.MIN_VALUE, false, false);
        assertTrue(minRate.startsWith("-"));
        assertTrue(minRate.endsWith("/h"));
    }

    @Test
    public void extremeNarrowPrefersStackOverAmbiguousRate()
    {
        HudMetricPairFormatter.Pair pair = HudMetricPairFormatter.formatValues(
            metrics, 104, 999_900_000L, 999_900_000L, true, false);
        assertEquals("+999.9m gp", pair.netText);
        if (pair.stacked)
        {
            assertFalse(HudMetricPairFormatter.isAmbiguousRate(pair.rateText)
                && pair.rateText.contains("…")
                && pair.rateText.replaceAll("[^0-9]", "").length() <= 1);
            assertTrue(pair.rateText.contains("/h") || pair.rateText.equals("—"));
        }
        else
        {
            assertTrue(fitsSingle(pair, 104));
            assertFalse(HudMetricPairFormatter.isAmbiguousRate(pair.rateText));
        }
    }

    @Test
    public void unavailableRateStaysEmDash()
    {
        HudMetricPairFormatter.Pair pair = HudMetricPairFormatter.formatValues(
            metrics, 104, 50L, 0L, false, false);
        assertEquals("—", pair.rateText);
        assertEquals("+50 gp", pair.netText);
    }

    @Test
    public void exactFormatFallsBackToCompactUnderPressure()
    {
        HudMetricPairFormatter.Pair pair = HudMetricPairFormatter.formatValues(
            metrics, 104, 12_345_678L, 12_345_678L, true, true);
        assertFalse(pair.rateText.contains(","));
        assertTrue(pair.netText.contains("+"));
        if (!pair.stacked)
        {
            assertTrue(fitsSingle(pair, 104));
        }
    }

    @Test
    public void hudPlusRateAndTotalLinesHonorPrefixAndTarget()
    {
        assertEquals("GP/hr: +2.3k",
            HudMetricPairFormatter.formatRateLine(2_300L, true, false, true));
        assertEquals("+2.3k",
            HudMetricPairFormatter.formatRateLine(2_300L, true, false, false));
        ProfitTargetPresentation target = ProfitTargetPresentation.of(200_000L, 1_800L, false);
        assertEquals("Total: +1.8k/200k",
            HudMetricPairFormatter.formatTotalLine(1_800L, target, false, true));
        assertEquals("+1.8k/200k",
            HudMetricPairFormatter.formatTotalLine(1_800L, target, false, false));
        assertEquals("Total: +1,800/200,000",
            HudMetricPairFormatter.formatTotalLine(1_800L, target, true, true));
    }

    @Test
    public void metricsFitSideBySideDetectsCrowding()
    {
        assertTrue(HudMetricPairFormatter.metricsFitSideBySide(
            metrics, "+2.3k", "+1.8k/200k", 400));
        assertFalse(HudMetricPairFormatter.metricsFitSideBySide(
            metrics, "+2.3k", "+1.8k/200k", 40));
    }

    @Test
    public void labelsTruncateOrStackRateFirst()
    {
        int netOnly = metrics.stringWidth("NET PROFIT");
        int crowded = netOnly + HudMetricPairFormatter.GAP_PX + metrics.stringWidth("RATE") - 1;
        HudMetricPairFormatter.Pair pair = HudMetricPairFormatter.formatLabels(metrics, crowded);
        assertEquals("NET PROFIT", pair.netText);
        if (!pair.stacked)
        {
            assertTrue(metrics.stringWidth(pair.rateText)
                + HudMetricPairFormatter.GAP_PX
                + metrics.stringWidth(pair.netText) <= crowded);
        }
    }

    private boolean fitsSingle(HudMetricPairFormatter.Pair pair, int contentW)
    {
        return metrics.stringWidth(pair.rateText)
            + HudMetricPairFormatter.GAP_PX
            + metrics.stringWidth(pair.netText) <= contentW;
    }

    private static FontMetrics metrics()
    {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        return image.getGraphics().getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    }
}
