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

        assertEquals(PriceSource.UNKNOWN, flow.getPriceSource());
        assertEquals(0L, flow.getPriceCapturedAtEpochMillis());
    }

    @Test
    public void timestampedFlowRetainsPriceCaptureTime()
    {
        Flow flow = new Flow(995, "Coins", 100, 1, 100,
            PriceSource.GRAND_EXCHANGE, 1_725_000_000_000L);

        assertEquals(1_725_000_000_000L, flow.getPriceCapturedAtEpochMillis());
    }

}
