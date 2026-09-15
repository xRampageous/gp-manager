package com.gpmanager.engine;

import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.WealthLocationSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.GrandExchangeOfferState;

/** Builds Wealth-only values from complete, current client observations. */
public final class WealthLocationsFactory
{
    public static final int F2P_GE_SLOT_COUNT = 3;
    public static final int MEMBERS_GE_SLOT_COUNT = 8;

    private WealthLocationsFactory()
    {
    }

    /** Builds a priced, read-only location from currently visible item-container ownership. */
    public static WealthLocationSnapshot container(String id, String title,
        ItemContainer container, IntUnaryOperator canonicalize,
        IntFunction<ItemFlow> unitQuote, long capturedAtEpochMillis)
    {
        if (container == null)
        {
            return empty(id, title, WealthLocationSnapshot.Status.INITIALIZING,
                capturedAtEpochMillis, "Waiting for the client container");
        }
        Item[] slots = container.getItems();
        if (slots == null)
        {
            return empty(id, title, WealthLocationSnapshot.Status.INCOMPLETE,
                capturedAtEpochMillis, "The client container is incomplete");
        }
        Map<Integer, Long> quantities = new java.util.TreeMap<>();
        try
        {
            for (Item slot : slots)
            {
                if (slot == null || slot.getId() <= 0 || slot.getQuantity() <= 0) continue;
                int itemId = canonicalize == null ? slot.getId() : canonicalize.applyAsInt(slot.getId());
                if (itemId <= 0)
                {
                    return empty(id, title, WealthLocationSnapshot.Status.INCOMPLETE,
                        capturedAtEpochMillis, "An item identity could not be resolved");
                }
                quantities.merge(itemId, (long) slot.getQuantity(), Math::addExact);
            }
        }
        catch (ArithmeticException ex)
        {
            return empty(id, title, WealthLocationSnapshot.Status.INCOMPLETE,
                capturedAtEpochMillis, "Item quantity exceeds the supported range");
        }
        return quantities(id, title, quantities, unitQuote, capturedAtEpochMillis);
    }

    /** Builds a priced read-only location from already identified item quantities. */
    public static WealthLocationSnapshot quantities(String id, String title,
        Map<Integer, Long> quantities, IntFunction<ItemFlow> unitQuote, long capturedAtEpochMillis)
    {
        List<WealthLocationSnapshot.Item> items = new ArrayList<>();
        boolean allValuesKnown = true;
        int unpriced = 0;
        int priced = 0;
        long total = 0L;
        try
        {
            if (quantities != null)
            {
                int slot = 0;
                for (Map.Entry<Integer, Long> entry : quantities.entrySet())
                {
                    int itemId = entry.getKey() == null ? 0 : entry.getKey();
                    long quantity = entry.getValue() == null ? 0L : entry.getValue();
                    if (itemId <= 0 || quantity <= 0L) continue;
                    ItemFlow quote = unitQuote == null ? null : unitQuote.apply(itemId);
                    int unitPrice = quote == null ? 0 : quote.getUnitPrice();
                    ItemPriceSource source = quote == null ? ItemPriceSource.UNPRICED : quote.getPriceSource();
                    String name = quote == null ? "Item " + itemId : quote.getItemName();
                    long priceAt = quote == null ? 0L : quote.getPriceCapturedAtEpochMillis();
                    WealthLocationSnapshot.Item item = new WealthLocationSnapshot.Item(slot++, itemId,
                        name, quantity, unitPrice, source, priceAt);
                    items.add(item);
                    if (!item.isValueAvailable())
                    {
                        allValuesKnown = false;
                        unpriced++;
                    }
                    else
                    {
                        priced++;
                        total = Math.addExact(total, item.getValueGp());
                    }
                }
            }
        }
        catch (ArithmeticException ex)
        {
            return empty(id, title, WealthLocationSnapshot.Status.INCOMPLETE,
                capturedAtEpochMillis, "Location value exceeds the supported range");
        }
        // Untradeable and unquoted holdings (quest items, untradeable gear) carry no market value;
        // they are listed and counted, and the priced total is still the location's value. Only a
        // location where nothing at all could be priced is unpriced.
        boolean nothingPriced = priced == 0 && unpriced > 0;
        return new WealthLocationSnapshot(id, title,
            nothingPriced ? WealthLocationSnapshot.Status.UNPRICED : WealthLocationSnapshot.Status.AVAILABLE,
            nothingPriced ? 0L : total, capturedAtEpochMillis, items,
            allValuesKnown ? "Observed holdings · excluded from Net"
                : nothingPriced ? "No holding here has a usable price"
                : unpriced + (unpriced == 1 ? " holding without a price is not in the total" : " holdings without a price are not in the total"));
    }

    public static boolean isSupportedGeSlotCount(int slotCount)
    {
        return slotCount == F2P_GE_SLOT_COUNT || slotCount == MEMBERS_GE_SLOT_COUNT;
    }

    public static WealthLocationSnapshot grandExchangeOffers(
        Map<Integer, GeOfferLedger.Snapshot> observedSlots,
        boolean loginSeedComplete,
        IntFunction<String> itemNames,
        long capturedAtEpochMillis)
    {
        if (!loginSeedComplete)
        {
            return empty("ge_offers", "Grand Exchange offers",
                WealthLocationSnapshot.Status.INITIALIZING, capturedAtEpochMillis,
                "Waiting for all offer slots");
        }
        if (observedSlots == null || !isSupportedGeSlotCount(observedSlots.size()))
        {
            return empty("ge_offers", "Grand Exchange offers",
                WealthLocationSnapshot.Status.INCOMPLETE, capturedAtEpochMillis,
                "Offer slot snapshot is incomplete");
        }

        List<WealthLocationSnapshot.Item> items = new ArrayList<>();
        boolean allValuesKnown = true;
        long total = 0L;
        try
        {
            for (int slot = 0; slot < observedSlots.size(); slot++)
            {
                GeOfferLedger.Snapshot offer = observedSlots.get(slot);
                if (offer == null || offer.getSlot() != slot)
                {
                    return empty("ge_offers", "Grand Exchange offers",
                        WealthLocationSnapshot.Status.INCOMPLETE, capturedAtEpochMillis,
                        "Offer slot snapshot is incomplete");
                }
                if (offer.getState() != GrandExchangeOfferState.SELLING)
                {
                    continue;
                }
                long remaining = Math.max(0L,
                    (long) offer.getTotalQuantity() - offer.getQuantityTraded());
                if (remaining == 0L)
                {
                    continue;
                }
                int unitPrice = Math.max(0, offer.getPrice());
                String name = itemNames == null ? null : itemNames.apply(offer.getItemId());
                WealthLocationSnapshot.Item item = new WealthLocationSnapshot.Item(
                    slot, offer.getItemId(), name, remaining, unitPrice,
                    unitPrice > 0 ? ItemPriceSource.OFFER_PRICE : ItemPriceSource.UNPRICED,
                    unitPrice > 0 ? capturedAtEpochMillis : 0L);
                items.add(item);
                if (!item.isValueAvailable())
                {
                    allValuesKnown = false;
                }
                else
                {
                    total = Math.addExact(total, item.getValueGp());
                }
            }
        }
        catch (ArithmeticException ex)
        {
            return empty("ge_offers", "Grand Exchange offers",
                WealthLocationSnapshot.Status.INCOMPLETE, capturedAtEpochMillis,
                "Offer value exceeds the supported range");
        }
        return new WealthLocationSnapshot("ge_offers", "Grand Exchange offers",
            allValuesKnown ? WealthLocationSnapshot.Status.AVAILABLE : WealthLocationSnapshot.Status.UNPRICED,
            allValuesKnown ? total : 0L, capturedAtEpochMillis, items,
            allValuesKnown ? "Active sell offers · excluded from Net" : "One or more offers have no usable price");
    }

    public static WealthLocationSnapshot collectionBox(
        boolean interfaceVisible,
        boolean allSlotsPresent,
        List<WealthLocationSnapshot.Item> items,
        long capturedAtEpochMillis)
    {
        if (!interfaceVisible)
        {
            return empty("ge_collection", "Grand Exchange collection box",
                WealthLocationSnapshot.Status.CLOSED, capturedAtEpochMillis,
                "Open the collection box to inspect its contents");
        }
        if (!allSlotsPresent)
        {
            return empty("ge_collection", "Grand Exchange collection box",
                WealthLocationSnapshot.Status.INCOMPLETE, capturedAtEpochMillis,
                "Collection slots are incomplete");
        }

        long total = 0L;
        boolean allValuesKnown = true;
        try
        {
            if (items != null)
            {
                for (WealthLocationSnapshot.Item item : items)
                {
                    if (item == null) continue;
                    if (!item.isValueAvailable()) allValuesKnown = false;
                    else total = Math.addExact(total, item.getValueGp());
                }
            }
        }
        catch (ArithmeticException ex)
        {
            return empty("ge_collection", "Grand Exchange collection box",
                WealthLocationSnapshot.Status.INCOMPLETE, capturedAtEpochMillis,
                "Collection value exceeds the supported range");
        }
        return new WealthLocationSnapshot("ge_collection", "Grand Exchange collection box",
            allValuesKnown ? WealthLocationSnapshot.Status.AVAILABLE : WealthLocationSnapshot.Status.UNPRICED,
            allValuesKnown ? total : 0L, capturedAtEpochMillis, items,
            allValuesKnown ? "Observed while open · excluded from Net" : "One or more contents have no usable price");
    }

    private static WealthLocationSnapshot empty(String id, String title,
        WealthLocationSnapshot.Status status, long capturedAtEpochMillis, String detail)
    {
        return new WealthLocationSnapshot(id, title, status, 0L, capturedAtEpochMillis,
            new ArrayList<>(), detail);
    }
}
