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
        Cu coins = row(25_000L, EnumSet.of(Cl.GAIN, Cl.COST, Cl.IGNORE));
        assertEquals("If counted as a gain: Net " + Fmt.signed(-160_000L) + " · as a cost: Net "
            + Fmt.signed(-210_000L), LedgerPage.abx(coins, -185_000L));
    }

    @Test
    public void unpricedRowsPreviewNothing()
    {
        assertEquals("", LedgerPage.abx(row(0L, EnumSet.allOf(Cl.class)), 1_000L));
    }

    private static Cu row(long value, EnumSet<Cl> decisions)
    {
        return new Cu("t", "s", 0L, 0L, Collections.emptyList(), value, "why", decisions);
    }
}
