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

    @Test
    public void transactionSummarizesMixedPricingSources()
    {
        Transaction transaction = Tx.of(
            100L,
            null,
            TransactionType.LOOT,
            Context.LOOT,
            "Loot",
            "PvM",
            true,
            Arrays.asList(
                new Flow(995, "Coins", 100, 1, 100, PriceSource.GRAND_EXCHANGE),
                new Flow(555, "Air rune", -2, 5, -10, PriceSource.MANUAL_OVERRIDE),
                new Flow(995, "Coins", 1, 60, 60, PriceSource.HIGH_ALCHEMY)));

        assertEquals("Pricing: manual (1), RuneLite market (1), high alchemy (1)", transaction.getPricingSummary());
    }
}
