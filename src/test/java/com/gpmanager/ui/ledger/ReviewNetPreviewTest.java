package com.gpmanager;

import java.util.Collections;
import java.util.EnumSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ReviewNetPreviewTest
{
    @Test
    public void previewShowsNetAfterGainOrCost()
    {
        ReviewRow coins = row(25_000L, EnumSet.of(ReviewDecision.GAIN, ReviewDecision.COST, ReviewDecision.IGNORE));
        assertEquals("If counted as a gain: Net " + Fmt.signed(-160_000L) + " · as a cost: Net "
            + Fmt.signed(-210_000L), LedgerPage.netPreview(coins, -185_000L));
    }

    @Test
    public void unpricedRowsPreviewNothing()
    {
        assertEquals("", LedgerPage.netPreview(row(0L, EnumSet.allOf(ReviewDecision.class)), 1_000L));
    }

    private static ReviewRow row(long value, EnumSet<ReviewDecision> decisions)
    {
        return new ReviewRow("t", "s", 0L, 0L, Collections.emptyList(), value, "why", decisions);
    }
}
