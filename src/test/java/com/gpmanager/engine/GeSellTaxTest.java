package com.gpmanager.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import org.junit.Test;

public class GeSellTaxTest
{
    @Test
    public void twoPercentRoundsDownAndCaps()
    {
        assertEquals(0L, GeSellTax.taxForSale(49, 1L, false));
        assertEquals(1L, GeSellTax.taxForSale(50, 1L, false));
        assertEquals(20L, GeSellTax.taxForSale(1_000, 1L, false));
        assertEquals(5_000_000L, GeSellTax.taxForSale(250_000_000, 1L, false));
        assertEquals(5_000_000L, GeSellTax.taxForSale(500_000_000, 1L, false));
        assertEquals(0L, GeSellTax.taxForSale(1_000, 1L, true));
    }

    @Test
    public void reconcilePrefersMatchingShortfall()
    {
        assertEquals(20L, GeSellTax.reconcileTax(1_000L, 980L, 20L));
        assertEquals(20L, GeSellTax.reconcileTax(1_000L, 500L, 20L)); // mismatch → formula
    }

    @Test
    public void wikiExemptListPaysNoTaxAndLookalikesStillDo()
    {
        // Curated 2026-09-13 from the wiki Grand Exchange tax exemption list.
        int[] exempt = {13190, 13192, 882, 884, 886, 806, 807, 808, 558, 365, 2309, 1891, 2140, 2142,
            347, 379, 355, 2327, 351, 329, 315, 361, 3008, 3014, 8011, 8010, 28824, 8009, 28790, 8008,
            8013, 8007, 3853, 2552, 1755, 5325, 1785, 2347, 1733, 233, 5341, 8794, 5329, 5343, 1735, 952, 5331};
        for (int id : exempt)
        {
            assertTrue("exempt " + id, GeSellTaxBooking.isExempt(id));
            assertEquals(0L, GeSellTax.taxForSale(100_000, 1L, GeSellTaxBooking.isExempt(id)));
        }
        // Mithril arrows, chaos runes and sharks are not on the list.
        assertFalse(GeSellTaxBooking.isExempt(888));
        assertFalse(GeSellTaxBooking.isExempt(562));
        assertFalse(GeSellTaxBooking.isExempt(385));
        assertEquals(2_000L, GeSellTax.taxForSale(100_000, 1L, GeSellTaxBooking.isExempt(385)));
    }
}
