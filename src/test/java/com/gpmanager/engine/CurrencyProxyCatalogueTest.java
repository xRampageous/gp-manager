package com.gpmanager;

import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Non-GP currencies are recognised so platform item mappings never price them. */
public class CurrencyProxyCatalogueTest
{
    @Test
    public void recognisesNonGpCurrencies()
    {
        assertTrue(CurrencyProxyCatalogue.CURRENCIES.contains(6529)); // Tokkul
        assertTrue(CurrencyProxyCatalogue.CURRENCIES.contains(12746)); // BH emblem
    }

    @Test
    public void tokkulReadsAtTheOnyxExchangeAndGlovesImproveIt()
    {
        assertArrayEquals(new int[]{ItemID.UNCUT_ONYX, 300_000}, CurrencyProxyCatalogue.PROXIES.get(6529));
        assertEquals(260_000, (int) CurrencyProxyCatalogue.GLOVES_UNITS.getOrDefault(6529, 0));
        assertEquals(0, (int) CurrencyProxyCatalogue.GLOVES_UNITS.getOrDefault(12746, 0));
    }
}
