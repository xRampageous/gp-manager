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
    private static final int COINS = 995;
    private static final int JAR = 11260;
    private static final int PLATEBODY = 1127;
    private static final int TOKKUL = 6529;
    private static final int DEATH_RUNE = 560;
    private static final Map<Integer, String> NAMES = Map.of(COINS, "Coins", JAR, "Impling jar",
        PLATEBODY, "Rune platebody", TOKKUL, "Tokkul", DEATH_RUNE, "Death rune");
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
                id == COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
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
    public void coinsForAnItemWithAShopOpenIsAPurchase()
    {
        start();
        engine.inbox.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        Transaction bought = change(COINS, -2_000L, JAR, 1L);
        assertSame(TransactionType.TRADE, bought.getType());
        assertEquals("Bought", bought.getNote());
        assertTrue(bought.isCounted());
        assertFalse("not Review", ReviewRules.needsOwnerDecision(bought));
    }

    @Test
    public void itemsForCoinsWithAShopOpenIsASale()
    {
        start();
        assertTrue("without the shop window it stays Review",
            ReviewRules.needsOwnerDecision(change(PLATEBODY, -1L, COINS, 38_000L)));

        engine.inbox.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        Transaction sold = change(PLATEBODY, -1L, COINS, 38_000L);
        assertSame(TransactionType.TRADE, sold.getType());
        assertSame(Context.MARKET, sold.getContext());
        assertEquals("Sold", sold.getNote());
        assertTrue(sold.isCounted());
        assertFalse("not Review", ReviewRules.needsOwnerDecision(sold));
    }

    @Test
    public void aShopTradeEntryNamesWhatItBoughtOrSold()
    {
        start();
        engine.inbox.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        engine.markContext(Context.MARKET, 4, "buy 1 Impling jar");
        Transaction bought = change(COINS, -290L, JAR, 1L);
        assertSame(TransactionType.TRADE, bought.getType());
        assertEquals("a coin shop keeps its own verb", "Traded", Semantics.verbOf(bought));
        assertEquals("Bought Impling jar",
            Semantics.tradedItems(bought, contributionOf(bought, COINS)));
        assertEquals("Paid Coins ×290",
            Semantics.tradedItems(bought, contributionOf(bought, JAR)));

        engine.markContext(Context.MARKET, 4, "buy 50 Death rune");
        Transaction exchanged = change(TOKKUL, -14_550L, DEATH_RUNE, 50L);
        assertSame(TransactionType.TRADE, exchanged.getType());
        assertEquals("a non-coin currency shop reads Exchanged", "Exchanged",
            Semantics.verbOf(exchanged));
        assertEquals("Bought Death rune ×50",
            Semantics.tradedItems(exchanged, contributionOf(exchanged, TOKKUL)));
        assertEquals("Paid Tokkul ×14,550",
            Semantics.tradedItems(exchanged, contributionOf(exchanged, DEATH_RUNE)));

        engine.markContext(Context.MARKET, 4, "sell 1 Rune platebody");
        Transaction sold = change(PLATEBODY, -1L, COINS, 380L);
        assertSame(TransactionType.TRADE, sold.getType());
        assertEquals("Traded", Semantics.verbOf(sold));
        assertEquals("Sold Rune platebody",
            Semantics.tradedItems(sold, contributionOf(sold, COINS)));
        assertEquals("Sold for Coins ×380",
            Semantics.tradedItems(sold, contributionOf(sold, PLATEBODY)));
    }

    @Test
    public void aTokkulSaleThroughItsSellClickIsAnExchangedTrade()
    {
        start();
        // The sell click arms the shop's MARKET context; outside it the same change fails closed.
        assertTrue("without the click context it stays Review",
            ReviewRules.needsOwnerDecision(change(PLATEBODY, -1L, TOKKUL, 380L)));

        engine.markContext(Context.MARKET, 4, "sell 1 Rune platebody");
        Transaction sold = change(PLATEBODY, -1L, TOKKUL, 380L);
        assertSame(TransactionType.TRADE, sold.getType());
        assertSame(Context.MARKET, sold.getContext());
        assertEquals("a non-coin currency shop reads Exchanged", "Exchanged", Semantics.verbOf(sold));
        assertTrue(sold.isCounted());
        assertFalse("not Review", ReviewRules.needsOwnerDecision(sold));
    }

    private static Contribution contributionOf(Transaction transaction, int itemId)
    {
        for (Contribution contribution : Contribution.project(transaction))
            if (contribution.itemId == itemId) return contribution;
        throw new AssertionError("no contribution for item " + itemId);
    }

    @Test
    public void aMixedCoinAndItemGainIsNotASale()
    {
        start();
        engine.inbox.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        Transaction mixed = change(PLATEBODY, -1L, COINS, 1_000L, JAR, 1L);
        assertTrue("coins plus another item is not a sale",
            ReviewRules.needsOwnerDecision(mixed));
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
        int before = engine.getActiveSession().getTransactions().size();
        now += 3_000L;
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now);
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now + 600L);
        List<Transaction> all = engine.getActiveSession().getTransactions();
        return all.size() > before ? all.get(all.size() - 1) : null;
    }
}
