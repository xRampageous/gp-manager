package com.gpmanager;

import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Non-GP currencies are recognised so platform item mappings never price them. */
public class CurrencyProxyCatalogueTest
{
    @Test
    public void recognisesNonGpCurrencies()
    {
        assertTrue(CurrencyProxyCatalogue.CURRENCIES.contains(6529)); // Tokkul
        assertTrue(CurrencyProxyCatalogue.CURRENCIES.contains(12746)); // BH emblem
        // 2026-11-03: the Fossil Island currency is the tear; the trident it buys is an item.
        assertTrue(CurrencyProxyCatalogue.CURRENCIES.contains(ItemID.FOSSIL_MERMAID_TEAR));
        assertTrue(!CurrencyProxyCatalogue.CURRENCIES.contains(ItemID.MERFOLK_TRIDENT));
    }

    @Test
    public void minigameCurrenciesKeepTheirIdentityAndTakeAlch()
    {
        // Owner 2026-11-03: these stay currencies (no market mapping), but are worth their alch
        // value rather than "?".
        for (int id : new int[]{ItemID.ABYSSAL_PEARL, ItemID.STAR_DUST, ItemID.MGUILD_MINERALS,
            ItemID.MOTHERLODE_NUGGET, ItemID.HALLOWED_MARK, ItemID.HALLOWED_MARK_2, ItemID.HALLOWED_MARK_3,
            ItemID.HALLOWED_MARK_4, ItemID.HALLOWED_MARK_5, ItemID.HALLOWED_MARK_25})
        {
            assertTrue("currency " + id, CurrencyProxyCatalogue.CURRENCIES.contains(id));
            assertTrue("alch currency " + id, CurrencyProxyCatalogue.ALCH_CURRENCIES.contains(id));
        }
        assertFalse("tokkul reads at its onyx proxy, not alch",
            CurrencyProxyCatalogue.ALCH_CURRENCIES.contains(ItemID.TZHAAR_TOKEN));
    }

    @Test
    public void tokkulReadsAtTheOnyxExchangeAndGlovesImproveIt()
    {
        assertArrayEquals(new int[]{ItemID.UNCUT_ONYX, 300_000}, CurrencyProxyCatalogue.PROXIES.get(6529));
        assertEquals(260_000, (int) CurrencyProxyCatalogue.GLOVES_UNITS.getOrDefault(6529, 0));
        assertEquals(0, (int) CurrencyProxyCatalogue.GLOVES_UNITS.getOrDefault(12746, 0));
    }
}
