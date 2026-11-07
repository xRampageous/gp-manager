package com.gpmanager;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ReviewNetPreviewTest
{
    @Test
    public void previewShowsNetAfterGainOrCost()
    {
        ReviewRow coins = row(25_000L, EnumSet.of(Decision.GAIN, Decision.COST, Decision.IGNORE));
        assertEquals("If counted as a gain: Net " + Fmt.signed(-160_000L) + " · as a cost: Net "
            + Fmt.signed(-210_000L), LedgerPage.netPreview(coins, Map.of(Decision.GAIN, -160_000L, Decision.COST, -210_000L)));
    }

    @Test
    public void unpricedRowsPreviewNothing()
    {
        assertEquals("", LedgerPage.netPreview(row(0L, EnumSet.allOf(Decision.class)), Collections.emptyMap()));
    }

    private static ReviewRow row(long value, EnumSet<Decision> decisions)
    {
        return new ReviewRow("t", "s", 0L, 0L, Collections.emptyList(), value, "why", decisions);
    }
}
