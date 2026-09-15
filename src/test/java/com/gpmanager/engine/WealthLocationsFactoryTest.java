package com.gpmanager.engine;

import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.WealthLocationSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WealthLocationsFactoryTest
{
    @Test
    public void heldContainerUsesCanonicalIdsAndReceiptQuoteWithoutAffectingNet()
    {
        ItemContainer container = proxy(ItemContainer.class, method ->
            "getItems".equals(method) ? new Item[] { new Item(2001, 2), new Item(2001, 3) } : null);
        WealthLocationSnapshot snapshot = WealthLocationsFactory.container("bank", "Bank", container,
            id -> id == 2001 ? 1001 : id,
            id -> new ItemFlow(id, "Rune", 1L, 50, 50L, ItemPriceSource.GRAND_EXCHANGE, 99L),
            100L);

        assertEquals(WealthLocationSnapshot.Status.AVAILABLE, snapshot.getStatus());
        assertEquals(250L, snapshot.getValueGp());
        assertEquals(1, snapshot.getItems().size());
        assertEquals(1001, snapshot.getItems().get(0).getItemId());
        assertEquals(5L, snapshot.getItems().get(0).getQuantity());
        assertEquals(99L, snapshot.getItems().get(0).getPriceCapturedAtEpochMillis());
        assertTrue(snapshot.isExcludedFromNet());
    }

    @Test
    public void activeSellOfferUsesRemainingQuantityAndItsOwnOfferPrice()
    {
        Map<Integer, GeOfferLedger.Snapshot> offers = completeOffers();
        offers.put(3, offer(3, GrandExchangeOfferState.SELLING, 1001, 10, 4, 250));

        WealthLocationSnapshot snapshot = WealthLocationsFactory.grandExchangeOffers(
            offers, true, id -> "Item " + id, 5_000L);

        assertEquals(WealthLocationSnapshot.Status.AVAILABLE, snapshot.getStatus());
        assertEquals(1_500L, snapshot.getValueGp());
        assertEquals(1, snapshot.getItems().size());
        WealthLocationSnapshot.Item item = snapshot.getItems().get(0);
        assertEquals(6L, item.getQuantity());
        assertEquals(250, item.getUnitPrice());
        assertEquals(ItemPriceSource.OFFER_PRICE, item.getPriceSource());
        assertEquals(5_000L, item.getPriceCapturedAtEpochMillis());
        assertTrue(snapshot.isExcludedFromNet());
    }

    @Test
    public void completeFreeToPlayThreeSlotArrayIsSupported()
    {
        Map<Integer, GeOfferLedger.Snapshot> offers = new LinkedHashMap<>();
        for (int slot = 0; slot < WealthLocationsFactory.F2P_GE_SLOT_COUNT; slot++)
        {
            offers.put(slot, offer(slot, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0));
        }

        WealthLocationSnapshot snapshot = WealthLocationsFactory.grandExchangeOffers(
            offers, true, id -> "Item " + id, 5_000L);

        assertEquals(WealthLocationSnapshot.Status.AVAILABLE, snapshot.getStatus());
        assertEquals(0L, snapshot.getValueGp());
    }

    @Test
    public void offerValueStaysUnavailableUntilAllSlotsAreSeededAndPriced()
    {
        Map<Integer, GeOfferLedger.Snapshot> offers = completeOffers();
        offers.put(0, offer(0, GrandExchangeOfferState.SELLING, 1002, 2, 0, 0));

        WealthLocationSnapshot initializing = WealthLocationsFactory.grandExchangeOffers(
            offers, false, id -> "Item " + id, 5_000L);
        assertEquals(WealthLocationSnapshot.Status.INITIALIZING, initializing.getStatus());
        assertFalse(initializing.isValueAvailable());

        WealthLocationSnapshot unpriced = WealthLocationsFactory.grandExchangeOffers(
            offers, true, id -> "Item " + id, 5_000L);
        assertEquals(WealthLocationSnapshot.Status.UNPRICED, unpriced.getStatus());
        assertEquals(0L, unpriced.getValueGp());
        assertFalse(unpriced.isValueAvailable());
        assertEquals(ItemPriceSource.UNPRICED, unpriced.getItems().get(0).getPriceSource());

        offers.remove(7);
        assertEquals(WealthLocationSnapshot.Status.INCOMPLETE,
            WealthLocationsFactory.grandExchangeOffers(offers, true, id -> "Item " + id, 5_000L)
                .getStatus());
    }

    @Test
    public void collectionBoxRequiresVisibleCompleteWidgetReadAndNeverAffectsNet()
    {
        WealthLocationSnapshot closed = WealthLocationsFactory.collectionBox(
            false, true, java.util.Collections.emptyList(), 6_000L);
        assertEquals(WealthLocationSnapshot.Status.CLOSED, closed.getStatus());

        java.util.List<WealthLocationSnapshot.Item> contents = java.util.Arrays.asList(
            new WealthLocationSnapshot.Item(0, 995, "Coins", 100L, 1,
                ItemPriceSource.FACE_VALUE, 6_000L),
            new WealthLocationSnapshot.Item(1, 1003, "Rune", 3L, 50,
                ItemPriceSource.GRAND_EXCHANGE, 6_000L));
        WealthLocationSnapshot snapshot = WealthLocationsFactory.collectionBox(
            true, true, contents, 6_000L);
        assertEquals(WealthLocationSnapshot.Status.AVAILABLE, snapshot.getStatus());
        assertEquals(250L, snapshot.getValueGp());
        assertEquals(2, snapshot.getItems().size());
        assertTrue(snapshot.isExcludedFromNet());
        assertEquals(WealthLocationSnapshot.Status.INCOMPLETE,
            WealthLocationsFactory.collectionBox(true, false, contents, 6_000L).getStatus());
    }

    @Test
    public void collectionBoxDoesNotPresentPartialTotalWhenAnyContentIsUnpriced()
    {
        java.util.List<WealthLocationSnapshot.Item> contents = java.util.Arrays.asList(
            new WealthLocationSnapshot.Item(0, 1004, "Priced", 2L, 40,
                ItemPriceSource.GRAND_EXCHANGE, 6_000L),
            new WealthLocationSnapshot.Item(1, 1005, "Unknown", 1L, 0,
                ItemPriceSource.UNPRICED, 0L));

        WealthLocationSnapshot snapshot = WealthLocationsFactory.collectionBox(
            true, true, contents, 6_000L);

        assertEquals(WealthLocationSnapshot.Status.UNPRICED, snapshot.getStatus());
        assertFalse(snapshot.isValueAvailable());
        assertEquals(0L, snapshot.getValueGp());
        assertNotNull(snapshot.getItems().get(1));
    }

    private static Map<Integer, GeOfferLedger.Snapshot> completeOffers()
    {
        Map<Integer, GeOfferLedger.Snapshot> offers = new LinkedHashMap<>();
        for (int slot = 0; slot < WealthLocationsFactory.MEMBERS_GE_SLOT_COUNT; slot++)
        {
            offers.put(slot, offer(slot, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0));
        }
        return offers;
    }

    private static GeOfferLedger.Snapshot offer(int slot, GrandExchangeOfferState state,
        int itemId, int total, int traded, int price)
    {
        return new GeOfferLedger.Snapshot(slot, state, itemId, total, traded, price, 0);
    }

    private interface MethodCall
    {
        Object apply(String method);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, MethodCall call)
    {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
            (proxy, method, args) -> call.apply(method.getName()));
    }
}
