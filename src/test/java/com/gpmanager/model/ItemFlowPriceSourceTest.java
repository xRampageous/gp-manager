package com.gpmanager;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ItemFlowPriceSourceTest
{
    @Test
    public void legacyFlowDefaultsToUnknownSource()
    {
        Flow flow = new Flow(995, "Coins", 100, 1, 100);

        assertEquals(PriceSource.UNKNOWN, flow.source());
        assertEquals(0L, flow.pricedAt());
    }

    @Test
    public void timestampedFlowRetainsPriceCaptureTime()
    {
        Flow flow = new Flow(995, "Coins", 100, 1, 100,
            PriceSource.GRAND_EXCHANGE, 1_725_000_000_000L);

        assertEquals(1_725_000_000_000L, flow.pricedAt());
    }

    @Test public void legacyNullAndExplicitUnknownHaveTheSameEqualityAndHash() {
        var legacy=new Flow(995,"Coins",100,1,100);
        var explicit=new Flow(995,"Coins",100,1,100,PriceSource.UNKNOWN,0);
        legacy.priceSource=null;
        assertEquals(explicit,legacy);assertEquals(explicit.hashCode(),legacy.hashCode());
    }

}
