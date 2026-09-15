package com.gpmanager.model;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WealthChangeBreakdownTest
{
    @Test
    public void separatesCountedNetFromRepricingOnlyForMinimumHeldQuantity()
    {
        WealthLocationsSnapshot before = snapshot(1_000L,
            location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(100, 5L, 100, ItemPriceSource.GRAND_EXCHANGE, 1_000L)));
        WealthLocationsSnapshot after = snapshot(2_000L,
            location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(100, 3L, 125, ItemPriceSource.GRAND_EXCHANGE, 2_000L)));

        WealthChangeBreakdown result = WealthChangeBreakdown.between(before, after, 500L);

        assertTrue(result.isAvailable());
        assertEquals(500L, result.getEarnedGp());
        assertEquals(75L, result.getMarketGp());
        assertEquals(-700L, result.getUnexplainedGp());
        assertEquals(5L, before.getLocation("bank").getItems().get(0).getQuantity());
        assertEquals(3L, after.getLocation("bank").getItems().get(0).getQuantity());
    }

    @Test
    public void marketLossRemainsSignedAndSubtractedFromCountedNet()
    {
        WealthChangeBreakdown result = WealthChangeBreakdown.between(
            snapshot(10L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(101, 4L, 200, ItemPriceSource.GRAND_EXCHANGE, 10L))),
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(101, 2L, 150, ItemPriceSource.GRAND_EXCHANGE, 20L))),
            -25L);

        assertTrue(result.isAvailable());
        assertEquals(-25L, result.getEarnedGp());
        assertEquals(-100L, result.getMarketGp());
        assertEquals(-375L, result.getUnexplainedGp());
    }

    @Test
    public void unrelatedAcquiredOrDisposedStacksAreNotCalledMarketMovement()
    {
        WealthChangeBreakdown result = WealthChangeBreakdown.between(
            snapshot(10L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(102, 4L, 200, ItemPriceSource.GRAND_EXCHANGE, 10L))),
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(103, 9L, 150, ItemPriceSource.GRAND_EXCHANGE, 20L))),
            700L);

        assertTrue(result.isAvailable());
        assertEquals(0L, result.getMarketGp());
        assertEquals(-150L, result.getUnexplainedGp());
    }

    @Test
    public void holdingsAreAggregatedAcrossLocationsBeforeTakingMinimum()
    {
        WealthChangeBreakdown result = WealthChangeBreakdown.between(
            snapshot(10L,
                location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                    item(104, 2L, 100, ItemPriceSource.GRAND_EXCHANGE, 10L)),
                location("inventory", WealthLocationSnapshot.Status.AVAILABLE,
                    item(104, 3L, 100, ItemPriceSource.GRAND_EXCHANGE, 10L))),
            snapshot(20L,
                location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                    item(104, 4L, 120, ItemPriceSource.GRAND_EXCHANGE, 20L)),
                location("inventory", WealthLocationSnapshot.Status.AVAILABLE,
                    item(104, 1L, 120, ItemPriceSource.GRAND_EXCHANGE, 20L))),
            1_000L);

        assertTrue(result.isAvailable());
        assertEquals(100L, result.getMarketGp());
        assertEquals(-1_000L, result.getUnexplainedGp());
    }

    @Test
    public void unavailableOrNonComparableLocationSnapshotsFailClosed()
    {
        WealthLocationsSnapshot available = snapshot(10L,
            location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(100, 1L, 100, ItemPriceSource.GRAND_EXCHANGE, 10L)));
        WealthChangeBreakdown missing = WealthChangeBreakdown.between(null, available, 47L);
        assertUnavailable(missing, 47L);

        assertUnavailable(WealthChangeBreakdown.between(
            snapshot(0L, location("bank", WealthLocationSnapshot.Status.AVAILABLE)),
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.AVAILABLE)), 48L), 48L);
        assertUnavailable(WealthChangeBreakdown.between(
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.AVAILABLE)),
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.AVAILABLE)), 49L), 49L);
        assertUnavailable(WealthChangeBreakdown.between(
            snapshot(10L, location("bank", WealthLocationSnapshot.Status.AVAILABLE)),
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.CLOSED)), 50L), 50L);
        assertUnavailable(WealthChangeBreakdown.between(
            snapshot(10L, location("bank", WealthLocationSnapshot.Status.AVAILABLE)),
            snapshot(20L, location("ge", WealthLocationSnapshot.Status.AVAILABLE)), 51L), 51L);
        assertUnavailable(WealthChangeBreakdown.between(
            snapshot(10L), snapshot(20L), 52L), 52L);
    }

    @Test
    public void unknownPriceOrChangedPriceBasisOnSharedHoldingFailsClosed()
    {
        assertUnavailable(WealthChangeBreakdown.between(
            snapshot(10L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(100, 2L, 100, ItemPriceSource.GRAND_EXCHANGE, 10L))),
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(100, 2L, 0, ItemPriceSource.UNPRICED, 0L))), 53L), 53L);

        assertUnavailable(WealthChangeBreakdown.between(
            snapshot(10L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(100, 2L, 100, ItemPriceSource.GRAND_EXCHANGE, 10L))),
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(100, 2L, 110, ItemPriceSource.OFFER_PRICE, 20L))), 54L), 54L);
    }

    @Test
    public void deferredUntradeableKeyIsAKnownZeroValueHolding()
    {
        WealthLocationSnapshot.Item key = item(3001, 1L, 0,
            ItemPriceSource.DEFERRED_CLAIM, 0L);
        assertTrue(key.isValueAvailable());
        assertEquals(0L, key.getValueGp());

        WealthChangeBreakdown result = WealthChangeBreakdown.between(
            snapshot(10L, location("bank", WealthLocationSnapshot.Status.AVAILABLE, key)),
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(3001, 1L, 0, ItemPriceSource.DEFERRED_CLAIM, 0L))), 0L);
        assertTrue(result.isAvailable());
        assertEquals(0L, result.getMarketGp());
        assertEquals(0L, result.getUnexplainedGp());
    }

    @Test
    public void changingManualOrOfferQuotesAreNotMarketRepricing()
    {
        WealthChangeBreakdown offer = WealthChangeBreakdown.between(
            snapshot(10L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(4001, 2L, 100, ItemPriceSource.OFFER_PRICE, 10L))),
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(4001, 2L, 150, ItemPriceSource.OFFER_PRICE, 20L))), 0L);
        assertTrue(offer.isAvailable());
        assertEquals(0L, offer.getMarketGp());
        assertEquals(100L, offer.getUnexplainedGp());

        WealthChangeBreakdown override = WealthChangeBreakdown.between(
            snapshot(10L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(4002, 1L, 100, ItemPriceSource.MANUAL_OVERRIDE, 10L))),
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(4002, 1L, 125, ItemPriceSource.MANUAL_OVERRIDE, 20L))), 0L);
        assertTrue(override.isAvailable());
        assertEquals(0L, override.getMarketGp());
        assertEquals(25L, override.getUnexplainedGp());
    }

    @Test
    public void exactArithmeticOverflowFailsClosedWithoutChangingCountedNet()
    {
        long quantity = Long.MAX_VALUE;
        WealthChangeBreakdown marketOverflow = WealthChangeBreakdown.between(
            snapshot(10L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(100, quantity, 1, ItemPriceSource.GRAND_EXCHANGE, 10L))),
            snapshot(20L, location("bank", WealthLocationSnapshot.Status.AVAILABLE,
                item(100, quantity, 3, ItemPriceSource.GRAND_EXCHANGE, 20L))), 55L);
        assertUnavailable(marketOverflow, 55L);

        WealthChangeBreakdown remainderOverflow = WealthChangeBreakdown.between(
            snapshot(10L, locationWithValue("bank", WealthLocationSnapshot.Status.AVAILABLE, 0L)),
            snapshot(20L, locationWithValue("bank", WealthLocationSnapshot.Status.AVAILABLE,
                Long.MAX_VALUE)), Long.MIN_VALUE);
        assertUnavailable(remainderOverflow, Long.MIN_VALUE);
    }

    private static void assertUnavailable(WealthChangeBreakdown result, long countedNet)
    {
        assertFalse(result.isAvailable());
        assertEquals(countedNet, result.getEarnedGp());
        assertEquals(0L, result.getMarketGp());
        assertEquals(0L, result.getUnexplainedGp());
    }

    private static WealthLocationsSnapshot snapshot(long capturedAt,
        WealthLocationSnapshot... locations)
    {
        return new WealthLocationsSnapshot(capturedAt,
            locations == null ? Collections.emptyList() : Arrays.asList(locations));
    }

    private static WealthLocationSnapshot location(String id,
        WealthLocationSnapshot.Status status, WealthLocationSnapshot.Item... items)
    {
        long total = 0L;
        if (items != null)
        {
            for (WealthLocationSnapshot.Item item : items)
            {
                if (item != null && item.isValueAvailable())
                {
                    total = Math.addExact(total, item.getValueGp());
                }
            }
        }
        return new WealthLocationSnapshot(id, id, status, total, 1L,
            items == null ? Collections.emptyList() : Arrays.asList(items), "test");
    }

    private static WealthLocationSnapshot locationWithValue(String id,
        WealthLocationSnapshot.Status status, long value)
    {
        return new WealthLocationSnapshot(id, id, status, value, 1L,
            Collections.emptyList(), "test");
    }

    private static WealthLocationSnapshot.Item item(int id, long quantity, int unitPrice,
        ItemPriceSource source, long priceCapturedAt)
    {
        return new WealthLocationSnapshot.Item(0, id, "Item " + id, quantity,
            unitPrice, source, priceCapturedAt);
    }
}
