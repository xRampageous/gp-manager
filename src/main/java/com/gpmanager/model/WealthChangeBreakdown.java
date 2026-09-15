package com.gpmanager.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure comparison of counted Net with a measured change in wealth-location snapshots.
 *
 * <p>{@code earnedGp} is always the supplied counted Net and is never adjusted by this
 * model. Unexplained is total observed wealth change minus Earned and Market. Market
 * repricing is calculated only when both snapshots have known, increasing
 * capture times, contain the same uniquely identified locations, and every location is
 * {@link WealthLocationSnapshot.Status#AVAILABLE}. Quantities are aggregated across
 * those locations by item id; only the minimum quantity held at both times is repriced.
 * Each item must have one known price
 * basis throughout each read and the same source at both reads. Only RuneLite
 * market and currency-proxy prices are classified as Market; manual, offer,
 * face-value and high-alchemy quote changes remain in Unexplained. This deliberately
 * excludes acquisitions, disposals, and uncertain reads from market movement, while
 * allowing an item moved between observed locations to remain in the held quantity.
 *
 * <p>If the snapshots are not comparable, or checked exact arithmetic overflows, the
 * breakdown is unavailable. In that case market and unexplained getters return zero
 * and callers must check {@link #isAvailable()} before displaying either value. Net
 * itself remains available through {@link #getEarnedGp()} and is never mutated.
 */
public final class WealthChangeBreakdown
{
    private final long earnedGp;
    private final long marketGp;
    private final long unexplainedGp;
    private final boolean available;

    private WealthChangeBreakdown(long earnedGp, long marketGp, long unexplainedGp,
        boolean available)
    {
        this.earnedGp = earnedGp;
        this.marketGp = marketGp;
        this.unexplainedGp = unexplainedGp;
        this.available = available;
    }

    /**
     * Compares two point-in-time snapshots against the counted Net between them.
     *
     * @param before earlier wealth read
     * @param after later wealth read
     * @param countedNetGp accounting Net for the interval; this value is retained as-is
     */
    public static WealthChangeBreakdown between(WealthLocationsSnapshot before,
        WealthLocationsSnapshot after, long countedNetGp)
    {
        if (!hasComparableLocations(before, after))
        {
            return unavailable(countedNetGp);
        }

        try
        {
            long market = 0L;
            long beforeWealth = totalValue(before);
            long afterWealth = totalValue(after);
            Map<Integer, HeldItem> beforeItems = aggregate(before.getLocations());
            Map<Integer, HeldItem> afterItems = aggregate(after.getLocations());
            for (Map.Entry<Integer, HeldItem> entry : beforeItems.entrySet())
            {
                HeldItem later = afterItems.get(entry.getKey());
                if (later == null)
                {
                    continue;
                }
                HeldItem earlier = entry.getValue();
                if (!earlier.hasComparablePrice() || !later.hasComparablePrice()
                    || earlier.priceSource != later.priceSource)
                {
                    return unavailable(countedNetGp);
                }

                if (!isMarketPriceSource(earlier.priceSource))
                {
                    continue;
                }

                long heldAtBothReads = Math.min(earlier.quantity, later.quantity);
                long priceChange = (long) later.unitPrice - earlier.unitPrice;
                market = Math.addExact(market, Math.multiplyExact(heldAtBothReads, priceChange));
            }
            long observedWealthChange = Math.subtractExact(afterWealth, beforeWealth);
            long unexplained = Math.subtractExact(
                Math.subtractExact(observedWealthChange, countedNetGp), market);
            return new WealthChangeBreakdown(countedNetGp, market, unexplained, true);
        }
        catch (ArithmeticException ex)
        {
            return unavailable(countedNetGp);
        }
    }

    /** Explicit unavailable value for callers that cannot establish counted Net coverage. */
    public static WealthChangeBreakdown unavailable()
    {
        return unavailable(0L);
    }

    /** True only for captured market feeds eligible to explain Market movement. */
    public static boolean isMarketPriceSource(ItemPriceSource source)
    {
        return source == ItemPriceSource.GRAND_EXCHANGE
            || source == ItemPriceSource.CURRENCY_PROXY;
    }

    /** The counted Net supplied to {@link #between}; this model never changes it. */
    public long getEarnedGp()
    {
        return earnedGp;
    }

    /** Returns market repricing when {@link #isAvailable()} is true, otherwise zero. */
    public long getMarketGp()
    {
        return marketGp;
    }

    /** Returns the remainder when {@link #isAvailable()} is true, otherwise zero. */
    public long getUnexplainedGp()
    {
        return unexplainedGp;
    }

    /** Whether both derived values are complete and safe to present. */
    public boolean isAvailable()
    {
        return available;
    }

    private static boolean hasComparableLocations(WealthLocationsSnapshot before,
        WealthLocationsSnapshot after)
    {
        if (before == null || after == null
            || before.getCapturedAtEpochMillis() <= 0L
            || after.getCapturedAtEpochMillis() <= before.getCapturedAtEpochMillis()
            || before.getLocations().isEmpty()
            || before.getLocations().size() != after.getLocations().size())
        {
            return false;
        }

        Set<String> seen = new HashSet<>();
        for (WealthLocationSnapshot beforeLocation : before.getLocations())
        {
            String id = beforeLocation.getId();
            if (id.isEmpty() || !seen.add(id))
            {
                return false;
            }
            WealthLocationSnapshot afterLocation = after.getLocation(id);
            if (afterLocation == null
                || afterLocation.getStatus() != WealthLocationSnapshot.Status.AVAILABLE
                || beforeLocation.getStatus() != WealthLocationSnapshot.Status.AVAILABLE)
            {
                return false;
            }
        }

        Set<String> afterIds = new HashSet<>();
        for (WealthLocationSnapshot location : after.getLocations())
        {
            if (location.getId().isEmpty() || !afterIds.add(location.getId()))
            {
                return false;
            }
        }
        return seen.equals(afterIds);
    }

    private static Map<Integer, HeldItem> aggregate(List<WealthLocationSnapshot> locations)
    {
        Map<Integer, HeldItem> result = new HashMap<>();
        for (WealthLocationSnapshot location : locations)
        {
            for (WealthLocationSnapshot.Item item : location.getItems())
            {
                if (item.getQuantity() <= 0L)
                {
                    continue;
                }
                if (item.getItemId() <= 0)
                {
                    throw new ArithmeticException("Unknown held item id");
                }

                HeldItem existing = result.get(item.getItemId());
                if (existing == null)
                {
                    result.put(item.getItemId(), new HeldItem(item));
                }
                else
                {
                    existing.add(item);
                }
            }
        }
        return result;
    }

    private static long totalValue(WealthLocationsSnapshot snapshot)
    {
        long total = 0L;
        for (WealthLocationSnapshot location : snapshot.getLocations())
        {
            total = Math.addExact(total, location.getValueGp());
        }
        return total;
    }

    private static WealthChangeBreakdown unavailable(long countedNetGp)
    {
        return new WealthChangeBreakdown(countedNetGp, 0L, 0L, false);
    }

    private static final class HeldItem
    {
        private long quantity;
        private final int unitPrice;
        private final ItemPriceSource priceSource;
        private boolean priceComparable;

        private HeldItem(WealthLocationSnapshot.Item item)
        {
            quantity = item.getQuantity();
            unitPrice = item.getUnitPrice();
            priceSource = item.getPriceSource();
            priceComparable = hasKnownPrice(item);
        }

        private void add(WealthLocationSnapshot.Item item)
        {
            quantity = Math.addExact(quantity, item.getQuantity());
            priceComparable &= hasKnownPrice(item)
                && unitPrice == item.getUnitPrice()
                && priceSource == item.getPriceSource();
        }

        private boolean hasComparablePrice()
        {
            return priceComparable;
        }

        private static boolean hasKnownPrice(WealthLocationSnapshot.Item item)
        {
            return item.isValueAvailable()
                && (item.getPriceSource() == ItemPriceSource.DEFERRED_CLAIM
                    || item.getPriceCapturedAtEpochMillis() > 0L)
                && item.getPriceSource() != ItemPriceSource.UNKNOWN
                && item.getPriceSource() != ItemPriceSource.UNPRICED;
        }
    }
}
