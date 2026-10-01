package com.gpmanager;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ItemFlowPriceSourceTest
{
    @Test
    public void legacyFlowDefaultsToUnknownSource()
    {
        Ab flow = new Ab(995, "Coins", 100, 1, 100);

        assertEquals(Av.UNKNOWN, flow.getPriceSource());
        assertEquals(0L, flow.getPriceCapturedAtEpochMillis());
    }

    @Test
    public void timestampedFlowRetainsPriceCaptureTime()
    {
        Ab flow = new Ab(995, "Coins", 100, 1, 100,
            Av.GRAND_EXCHANGE, 1_725_000_000_000L);

        assertEquals(1_725_000_000_000L, flow.getPriceCapturedAtEpochMillis());
    }

    @Test
    public void transactionSummarizesMixedPricingSources()
    {
        Ac transaction = Tx.of(
            100L,
            null,
            Ai.LOOT,
            Aj.LOOT,
            "Loot",
            "PvM",
            true,
            Arrays.asList(
                new Ab(995, "Coins", 100, 1, 100, Av.GRAND_EXCHANGE),
                new Ab(555, "Air rune", -2, 5, -10, Av.MANUAL_OVERRIDE),
                new Ab(995, "Coins", 1, 60, 60, Av.HIGH_ALCHEMY)));

        assertEquals("Pricing: manual (1), RuneLite market (1), high alchemy (1)", transaction.uu());
    }
}
