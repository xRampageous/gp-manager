package com.gpmanager.model;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ItemFlowPriceSourceTest
{
    @Test
    public void legacyFlowDefaultsToUnknownSource()
    {
        ItemFlow flow = new ItemFlow(995, "Coins", 100, 1, 100);

        assertEquals(ItemPriceSource.UNKNOWN, flow.getPriceSource());
        assertEquals(0L, flow.getPriceCapturedAtEpochMillis());
    }

    @Test
    public void timestampedFlowRetainsPriceCaptureTime()
    {
        ItemFlow flow = new ItemFlow(995, "Coins", 100, 1, 100,
            ItemPriceSource.GRAND_EXCHANGE, 1_725_000_000_000L);

        assertEquals(1_725_000_000_000L, flow.getPriceCapturedAtEpochMillis());
    }

    @Test
    public void transactionSummarizesMixedPricingSources()
    {
        ProfitTransaction transaction = new ProfitTransaction(
            100L,
            null,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "Loot",
            "PvM",
            true,
            Arrays.asList(
                new ItemFlow(995, "Coins", 100, 1, 100, ItemPriceSource.GRAND_EXCHANGE),
                new ItemFlow(555, "Air rune", -2, 5, -10, ItemPriceSource.MANUAL_OVERRIDE),
                new ItemFlow(995, "Coins", 1, 60, 60, ItemPriceSource.HIGH_ALCHEMY)));

        assertEquals("Pricing: manual (1), RuneLite market (1), high alchemy (1)", transaction.getPricingSummary());
    }
}
