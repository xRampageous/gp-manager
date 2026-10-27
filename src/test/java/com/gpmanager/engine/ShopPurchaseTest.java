package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Owner 1.3.4: a shop window turns coins-for-items into a counted "Bought" and items-for-coins
 * into a counted "Sold" instead of a mixed Review row; the trade branches still fail closed when
 * the shapes are not exact.
 */
public class ShopPurchaseTest
{
    static String tradedItems(Transaction row, Contribution part) {
        var parts = Semantics.tradeParts(row, part);
        return parts == null ? "" : parts[0]+" "+parts[1];
    }

    private static final int COINS = 995;
    private static final int JAR = 11260;
    private static final int PLATEBODY = 1127;
    private static final int TOKKUL = 6529;
    private static final int DEATH_RUNE = 560;
    private static final int BOOK = 9003; // Security book: untradeable, no market price
    private static final Map<Integer, String> NAMES = Map.of(COINS, "Coins", JAR, "Impling jar",
        PLATEBODY, "Rune platebody", TOKKUL, "Tokkul", DEATH_RUNE, "Death rune", BOOK, "Security book");
    private static final Map<Integer, Integer> PRICES = Map.of(COINS, 1, JAR, 290, PLATEBODY, 38_000, TOKKUL, 9);

    private final Map<Integer, Long> inventory = new HashMap<>(Map.of(COINS, 100_000L, PLATEBODY, 5L, TOKKUL, 100_000L));
    private final Engine engine = new Engine(deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) ->
        {
            int unit = PRICES.getOrDefault(id, 0);
            flows.add(new Flow(id, NAMES.getOrDefault(id, "Item " + id), quantity, unit,
                quantity * unit,
                id == COINS ? PriceSource.FACE_VALUE
                    : id == BOOK ? PriceSource.UNPRICED : PriceSource.GRAND_EXCHANGE));
        });
        return flows;
    }, new TransactionClassifier(), new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }
    });
    private long now = 1_000_000L;

    @Test
    public void buyingAnUntradeableIsAPurchaseNotReview()
    {
        // A shop/dialogue purchase of an item with no market (untradeable) spends coins that
        // can never be recovered: the purchase books the cost (Coinflow 1.7.1 calls this the
        // unrealizable case; owner probe 2026-10-13).
        start();
        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        Transaction bought = change(COINS, -2L, BOOK, 1L);
        assertSame(TransactionType.TRADE, bought.type());
        assertEquals("Bought", bought.note());
        assertTrue(bought.counts());
        assertFalse("not Review", ReviewRules.needsReview(bought));
        assertEquals(-2L, engine.metrics(now + 1_000L).net);
    }

    @Test
    public void coinsForAnItemWithAShopOpenIsAPurchase()
    {
        start();
        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        Transaction bought = change(COINS, -2_000L, JAR, 1L);
        assertSame(TransactionType.TRADE, bought.type());
        assertEquals("Bought", bought.note());
        assertTrue(bought.counts());
        assertFalse("not Review", ReviewRules.needsReview(bought));
    }

    @Test
    public void itemsForCoinsWithAShopOpenIsASale()
    {
        start();
        assertTrue("without the shop window it stays Review",
            ReviewRules.needsReview(change(PLATEBODY, -1L, COINS, 38_000L)));

        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        Transaction sold = change(PLATEBODY, -1L, COINS, 38_000L);
        assertSame(TransactionType.TRADE, sold.type());
        assertSame(Context.MARKET, sold.context());
        assertEquals("Sold", sold.note());
        assertTrue(sold.counts());
        assertFalse("not Review", ReviewRules.needsReview(sold));
    }

    @Test
    public void aShopTradeEntryNamesWhatItBoughtOrSold()
    {
        start();
        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        engine.markContext(Context.MARKET, 4, "buy 1 Impling jar");
        Transaction bought = change(COINS, -290L, JAR, 1L);
        assertSame(TransactionType.TRADE, bought.type());
        assertEquals("a coin shop keeps its own verb", "Traded", Semantics.verbOf(bought));
        assertEquals("Bought Impling jar",
            tradedItems(bought, contributionOf(bought, COINS)));
        assertEquals("Paid Coins ×290",
            tradedItems(bought, contributionOf(bought, JAR)));

        engine.markContext(Context.MARKET, 4, "buy 50 Death rune");
        Transaction exchanged = change(TOKKUL, -14_550L, DEATH_RUNE, 50L);
        assertSame(TransactionType.TRADE, exchanged.type());
        assertEquals("a non-coin currency shop reads Exchanged", "Exchanged",
            Semantics.verbOf(exchanged));
        assertEquals("Bought Death rune ×50",
            tradedItems(exchanged, contributionOf(exchanged, TOKKUL)));
        assertEquals("Paid Tokkul ×14,550",
            tradedItems(exchanged, contributionOf(exchanged, DEATH_RUNE)));

        engine.markContext(Context.MARKET, 4, "sell 1 Rune platebody");
        Transaction sold = change(PLATEBODY, -1L, COINS, 380L);
        assertSame(TransactionType.TRADE, sold.type());
        assertEquals("Traded", Semantics.verbOf(sold));
        assertEquals("Sold Rune platebody",
            tradedItems(sold, contributionOf(sold, COINS)));
        assertEquals("Sold for Coins ×380",
            tradedItems(sold, contributionOf(sold, PLATEBODY)));
    }

    @Test
    public void aTokkulSaleThroughItsSellClickIsAnExchangedTrade()
    {
        start();
        // The sell click arms the shop's MARKET context; outside it the same change fails closed.
        assertTrue("without the click context it stays Review",
            ReviewRules.needsReview(change(PLATEBODY, -1L, TOKKUL, 380L)));

        engine.markContext(Context.MARKET, 4, "sell 1 Rune platebody");
        Transaction sold = change(PLATEBODY, -1L, TOKKUL, 380L);
        assertSame(TransactionType.TRADE, sold.type());
        assertSame(Context.MARKET, sold.context());
        assertEquals("a non-coin currency shop reads Exchanged", "Exchanged", Semantics.verbOf(sold));
        assertTrue(sold.counts());
        assertFalse("not Review", ReviewRules.needsReview(sold));
    }

    private static Contribution contributionOf(Transaction transaction, int itemId)
    {
        for (Contribution contribution : Contribution.project(transaction))
            if (contribution.item == itemId) return contribution;
        throw new AssertionError("no contribution for item " + itemId);
    }

    @Test
    public void aMixedCoinAndItemGainIsNotASale()
    {
        start();
        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        Transaction mixed = change(PLATEBODY, -1L, COINS, 1_000L, JAR, 1L);
        assertTrue("coins plus another item is not a sale",
            ReviewRules.needsReview(mixed));
    }

    @Test
    public void aVisibleShopWindowIsShopEvidence()
    {
        assertTrue("InterfaceID.Shopmain.FRAME",
            Widgets.isShopOpen(clientWith(0x012c_0001)));
        assertTrue("InterfaceID.OmnishopMain.FRAME",
            Widgets.isShopOpen(clientWith(0x0333_0007)));
        assertFalse("a quantity button is not the shop window",
            Widgets.isShopOpen(clientWith(0x012c_0008)));
        assertFalse(Widgets.isShopOpen(clientWith(0)));
    }

    private static Client clientWith(int componentId)
    {
        return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[] {Client.class}, (proxy, method, args) ->
            {
                if ("getWidget".equals(method.getName()))
                    return componentId > 0 && (int) args[0] == componentId ? visibleWidget() : null;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                return null;
            });
    }

    private static Widget visibleWidget()
    {
        return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
            new Class<?>[] {Widget.class}, (proxy, method, args) ->
            {
                if ("isHidden".equals(method.getName())) return false;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                return null;
            });
    }

    private void start()
    {
        engine.startCustom("Shops", now);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(inventory)));
    }

    private Transaction change(Object... idsAndDeltas)
    {
        for (int index = 0; index < idsAndDeltas.length; index += 2)
        {
            inventory.merge((Integer) idsAndDeltas[index], (Long) idsAndDeltas[index + 1],
                Long::sum);
        }
        inventory.values().removeIf(quantity -> quantity == 0L);
        int before = engine.session().transactions().size();
        now += 3_000L;
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now, null);
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now + 600L, null);
        List<Transaction> all = engine.session().transactions();
        return all.size() > before ? all.get(all.size() - 1) : null;
    }
}
