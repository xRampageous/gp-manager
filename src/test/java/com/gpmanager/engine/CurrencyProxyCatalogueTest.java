package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Non-GP currencies are recognised so platform item mappings never price them. */
public class CurrencyProxyCatalogueTest
{
    @Test
    public void recognisesNonGpCurrencies()
    {
        assertTrue(CurrencyProxyCatalogue.isMapped(6529)); // Tokkul
        assertTrue(CurrencyProxyCatalogue.isMapped(12746)); // BH emblem
    }
}
