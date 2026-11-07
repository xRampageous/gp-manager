package com.gpmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Fixed quotes isolate the shop evidence/matcher from the separate valuation policy. */
@RunWith(Parameterized.class)
public class ShopCurrencyMatcherTest {
    @Parameterized.Parameters(name = "{1}")
    public static Object[][] currencies() {
        return new Object[][] {
            {6529, "Tokkul"}, {25527, "Stardust"}, {21341, "Unidentified minerals"},
            {12012, "Golden nugget"}, {24711, "Hallowed mark"}, {4067, "Castle wars ticket"},
            {21656, "Mermaid's tear"}, {11849, "Mark of Grace"}, {28134, "Anima-infused bark"},
            {6306, "Trading sticks"}, {4278, "Ecto-token"}, {29480, "Agility arena ticket"},
            {29482, "Brimhaven voucher"}, {8951, "Pieces of eight"}, {30038, "Termites"},
            {1464, "Archery ticket"}, {26792, "Abyssal pearls"}, {25588, "Spirit flakes"},
            {22820, "Molch pearl"}
        };
    }

    @Parameterized.Parameter public int currency;
    @Parameterized.Parameter(1) public String name;
    private static final long NOW = 1_000_000L;
    private static final int REWARD = ItemID.LOGS;

    private Engine engine(boolean known) {
        JsonCodec.bind(new com.google.gson.Gson());
        var engine = new Engine(deltas -> {
            var flows = new ArrayList<Flow>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, id == currency ? name : "Logs", quantity,
                    id == currency ? 30 : 100, quantity * (id == currency ? 30 : 100),
                    id == currency && !known ? PriceSource.UNPRICED : PriceSource.GRAND_EXCHANGE)));
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig() {
            @Override public int stabilizationTicks() { return 0; }
        });
        engine.startCustom("Shop currency", NOW);
        engine.setBaseline(new ContainerSnapshot(Map.of(currency, 100L, REWARD, 10L)));
        return engine;
    }

    private Transaction change(Engine engine, boolean buy) {
        var next = new ContainerSnapshot(Map.of(currency, buy ? 90L : 110L, REWARD, buy ? 11L : 9L));
        engine.processIfDirty(next, NOW + 3000, null);
        engine.processIfDirty(next, NOW + 3600, null);
        var rows = engine.session().transactions();
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    @Test public void dialoguePurchaseIsCountedWithTheCapturedCost() {
        var engine = engine(true);
        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        var row = change(engine, true);
        assertEquals(TransactionType.TRADE, row.type());
        assertEquals(Context.MARKET, row.context());
        assertTrue(row.counts());
        assertFalse(ReviewRules.needsReview(row));
        assertEquals(-200, engine.metrics(NOW + 4000).net);
        assertEquals(10, row.quantity(currency, false));
    }

    @Test public void dialogueSaleKeepsItsOppositeNet() {
        var engine = engine(true);
        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        var row = change(engine, false);
        assertEquals(TransactionType.TRADE, row.type());
        assertTrue(row.counts());
        assertFalse(ReviewRules.needsReview(row));
        assertEquals(200, engine.metrics(NOW + 4000).net);
    }

    @Test public void withoutShopOrDialogueTheSameChangeStaysReview() {
        var engine = engine(true);
        assertTrue(ReviewRules.needsReview(change(engine, true)));
        assertEquals(0, engine.metrics(NOW + 4000).net);
    }

    @Test public void anExecutedNonCoinShopClickReadsExchanged() {
        var engine = engine(true);
        engine.markContext(Context.MARKET, 4, "buy 1 Logs");
        var row = change(engine, true);
        assertTrue(row.counts());
        assertEquals("Exchanged", Semantics.verbOf(row));
        assertEquals(-200, engine.metrics(NOW + 4000).net);
    }

    @Test public void mixedCurrencyAndOtherGainsDoNotBecomeAShopSale() {
        var engine = engine(true);
        engine.events.put(Inbox.DIALOGUE, 0, 1L, 4, null);
        var next = new ContainerSnapshot(Map.of(currency, 110L, REWARD, 9L, ItemID.COINS, 50L));
        engine.processIfDirty(next, NOW + 3000, null);
        engine.processIfDirty(next, NOW + 3600, null);
        assertTrue(ReviewRules.needsReview(engine.session().transactions().get(0)));
        assertEquals(0, engine.metrics(NOW + 4000).net);
    }
}
