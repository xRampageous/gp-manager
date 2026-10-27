package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.BOUGHT;
import static net.runelite.api.GrandExchangeOfferState.BUYING;
import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Beyond max cash (30 September 2026): the G.E. trades above 2,147,483,647 coins, paying and giving
 * change in coins and platinum tokens (up to 2,149,631,130,647 in all). A token is a thousand coins,
 * prices are whole longs, and the Obligator (about 3.27B) books at its value with no Review.
 */
public class BeyondMaxCashTest
{
    private static final int COINS = ItemID.COINS;
    private static final int PLATINUM = ItemID.PLATINUM;
    private static final int OBLIGATOR = ItemID.OBLIGATOR;
    private static final long PRICE = 3_274_989_430L;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void exchangingCoinsAndPlatinumTokensBooksNothing()
    {
        ContainerSnapshot coins = capture(COINS, 2_500);
        ContainerSnapshot tokens = capture(COINS, 500, PLATINUM, 2);
        assertEquals("2 tokens and 500 coins are the same 2,500 coins", coins, tokens);
        assertEquals(0L, tokens.quantity(PLATINUM));
        assertEquals("the largest holding fits", 2_149_631_130_647L,
            capture(COINS, Integer.MAX_VALUE, PLATINUM, Integer.MAX_VALUE).quantity(COINS));

        Engine engine = ReviewFixesTest.engine();
        engine.setBaseline(coins);
        engine.markDirty();
        assertNull("coins to tokens", settle(engine, tokens, T0));
        engine.markDirty();
        assertNull("tokens back to coins", settle(engine, capture(COINS, 2_500), T0 + 10_000));
        assertTrue(engine.session().transactions().isEmpty());
    }

    @Test
    public void anObligatorBoughtWithPlatinumAndCoinChangeIsOnePurchase()
    {
        Market market = new Market();
        market.inventory.put(COINS, 1_000_000L + 3_300_000L * 1_000);
        market.engine.setBaseline(new ContainerSnapshot(market.inventory));
        market.offer(0, BUYING, OBLIGATOR, 1, 0, PRICE, 0);
        market.offer(0, BOUGHT, OBLIGATOR, 1, 1, PRICE, PRICE);
        market.offer(0, EMPTY, 0, 0, 0, 0, 0);
        market.engine.noteGeCollect(market.now);
        // The G.E. took tokens and coins and gave change in coins; together they are 26,010,570.
        market.inventory.put(COINS, 1_000_000L + 3_300_000L * 1_000 - PRICE);
        market.inventory.put(OBLIGATOR, 1L);
        market.settle();

        assertEquals("no Review", 0, market.reviews());
        assertTrue(market.transactions().stream().anyMatch(row -> row.original() == TransactionType.TRADE));
        assertEquals("a purchase at the market price is Net-neutral", 0L, market.net());
    }

    @Test
    public void anObligatorSoldIntoPlatinumPaysTheCappedTaxOnce()
    {
        Market market = new Market();
        long received = PRICE - 5_000_000L;
        market.inventory.put(OBLIGATOR, 1L);
        market.engine.setBaseline(new ContainerSnapshot(market.inventory));
        market.offer(0, SELLING, OBLIGATOR, 1, 0, PRICE, 0);
        market.inventory.put(OBLIGATOR, 0L);
        market.settle();
        market.offer(0, SOLD, OBLIGATOR, 1, 1, PRICE, PRICE);
        // The collection box holds the proceeds as platinum tokens plus the odd coins.
        market.engine.noteGeBox(0, new Item[] {new Item(PLATINUM, (int) (received / 1_000)),
            new Item(COINS, (int) (received % 1_000))}, id -> id, market.now);
        assertEquals("the box's tokens count as cash", received, market.engine.exchange.boxes.get(0).cash);
        market.engine.noteGeCollect(market.now);
        market.inventory.put(COINS, market.inventory.get(COINS) + received);
        market.settle();

        MarketRows.Row row = market.row(OBLIGATOR);
        assertEquals("2% capped at 5M per item", 5_000_000L, row.inferredGeTaxGp);
        assertEquals(received, row.observedSettlementGp);
        assertEquals("no Review", 0, market.reviews());
        assertFalse(market.transactions().isEmpty());
    }

    private static Transaction settle(Engine engine, ContainerSnapshot next, long at)
    {
        Transaction booked = null;
        for (int tick = 0; tick < 6 && booked == null; tick++)
        {
            booked = engine.processIfDirty(next, at + tick * 600L, null);
        }
        return booked;
    }

    private static ContainerSnapshot capture(int... idQuantity)
    {
        Item[] items = new Item[idQuantity.length / 2];
        for (int i = 0; i < items.length; i++)
        {
            items[i] = new Item(idQuantity[2 * i], idQuantity[2 * i + 1]);
        }
        ItemContainer inventory = (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
            new Class<?>[] {ItemContainer.class}, (proxy, method, args) -> "getItems".equals(method.getName()) ? items : null);
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getItemContainer":
                        return (int) args[0] == InventoryID.INV ? inventory : null;
                    case "getVarbitValue":
                        return 0;
                    default:
                        return null;
                }
            });
        return new ContainerSnapshotFactory(client, null).capture(true, false);
    }

    private static final class Market
    {
        final Engine engine;
        final OfferSlots ledger = new OfferSlots();
        final Map<Integer, Long> inventory = new HashMap<>();
        long now = T0;

        Market()
        {
            engine = new Engine(deltas ->
            {
                List<Flow> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> delta : deltas.entrySet())
                {
                    int id = delta.getKey();
                    long unit = id == COINS ? 1L : PRICE;
                    flows.add(new Flow(id, id == COINS ? "Coins" : "Obligator", delta.getValue(), unit,
                        delta.getValue() * unit, id == COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
                }
                return flows;
            }, new TransactionClassifier(), new GpManagerConfig()
            {
                @Override
                public int stabilizationTicks()
                {
                    return 0;
                }
            });
            engine.startCustom("Trading", now);
            inventory.put(COINS, 1_000_000L);
        }

        void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded, long price, long spent)
        {
            now += 5_000L;
            ledger.observe(new OfferSlots.Snapshot(slot, state, item, total, traded, price, spent))
                .ifPresent(transition -> engine.noteGeOffer(transition, "Obligator", now));
        }

        void settle()
        {
            now += 5_000L;
            engine.markDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(new HashMap<>(inventory));
            for (int i = 0; i < 3; i++)
            {
                engine.processIfDirty(snapshot, now, null);
                now += 1_000L;
            }
        }

        MarketRows.Row row(int itemId)
        {
            for (MarketRows.Row row : engine.marketRows())
            {
                if (row.itemId == itemId)
                {
                    return row;
                }
            }
            throw new AssertionError("no market row for " + itemId);
        }

        List<Transaction> transactions()
        {
            return engine.session().transactions();
        }

        int reviews()
        {
            return (int) transactions().stream().filter(ReviewRules::needsReview).count();
        }

        long net()
        {
            return engine.metrics(now + 1_000L).net;
        }
    }
}
