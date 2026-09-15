package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CurrencyProxyCatalogueTest
{
    @Test
    public void enhancedWeaponSeedProxyUses1500ShardsAndTenDustPerShard()
    {
        ItemValuationService service = new ItemValuationService(new GpManagerConfig() {},
            id -> null, id -> id == 25859 ? 150_000_000 : 0);
        ItemFlow shards = service.value(Collections.singletonMap(23866, 1_500L)).get(0);
        ItemFlow dust = service.value(Collections.singletonMap(23964, 15_000L)).get(0);
        assertEquals(150_000_000L, shards.getValueDelta());
        assertEquals(150_000_000L, dust.getValueDelta());
        assertEquals(ItemPriceSource.CURRENCY_PROXY, shards.getPriceSource());
        assertEquals(ItemPriceSource.CURRENCY_PROXY, dust.getPriceSource());
    }

    @Test
    public void coinAnchoredRequiresManualOverride()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public boolean useCurrencyProxies() { return true; }
            public boolean ignoreUnpricedItems() { return false; }
        };
        ItemValuationService service = new ItemValuationService(config, id -> null, id -> 1);
        ItemFlow tear = service.value(Collections.singletonMap(21649, 5L)).get(0);
        assertEquals(ItemPriceSource.UNPRICED, tear.getPriceSource());
        assertEquals(0L, tear.getValueDelta());
    }

    @Test
    public void whyCountedNoteDocumentsProxy()
    {
        assertTrue(CurrencyProxyCatalogue.whyCountedNote(6529).contains("onyx"));
        assertTrue(CurrencyProxyCatalogue.isMapped(12746)); // BH emblem
    }
}
