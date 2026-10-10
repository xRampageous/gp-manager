package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.BOUGHT;
import static net.runelite.api.GrandExchangeOfferState.BUYING;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL;
import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-10-09: an offer collected to the bank never shows its cash or items in the
 * inventory, so it used to wait ten minutes and land in Review uncounted. The offer itself proves
 * what filled and what it cost, so it now settles: a buy exactly, a sale at its proven tax or, at
 * mixed prices, the highest tax it could have paid.
 */
public class GeCollectToBankTest
{
    private static final int COINS = ItemID.COINS;
    private static final int RUNE = ItemID.NATURERUNE;
    private static final long T0 = 1_000_000_000_000L;
    private static final long LATER = 31_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    private final Map<Integer, Long> inventory = new HashMap<>(Map.of(COINS, 1_000_000L, RUNE, 100L));
    private final OfferSlots ledger = new OfferSlots();
    private final Engine engine = engine(inventory);

    @Test
    public void aBuyCollectedToTheBankIsAPurchase()
    {
        long t = T0 + 10_000L;
        offer(0, BUYING, 1_000, 180, 0, 0, t);
        inventory.put(COINS, 1_000_000L - 180_000L);
        settle(t + 100L);
        offer(0, BOUGHT, 1_000, 180, 1_000, 176_000, t + 1_000L);
        offer(0, EMPTY, 0, 0, 0, 0, t + 2_000L);
        engine.tickGe(t + 2_000L + LATER);

        assertEquals("no Review", 0, reviewRows());
        assertTrue("one purchase", trades() == 1);
        assertEquals("buying is Net-neutral", 0L, net());
        engine.tickGe(t + 2_000L + 11L * 60_000L);
        assertEquals("still nothing in Review ten minutes later", 0, reviewRows());
    }

    @Test
    public void aSaleAtOnePriceCollectedToTheBankCountsItsTax()
    {
        long t = T0 + 10_000L;
        sell(40L, 5_600L, SOLD, t);
        offer(0, EMPTY, 0, 0, 0, 0, t + 2_000L);
        engine.tickGe(t + 2_000L + 10_000L);
        assertEquals("not before the bank wait", 0, trades());
        engine.tickGe(t + 2_000L + LATER);

        assertEquals("140 each pays 2 each", -80L, net());
        assertEquals(0, reviewRows());
        assertEquals(1, trades());
    }

    @Test
    public void aSaleAtSeveralPricesCountsTheHighestTaxItCouldHavePaid()
    {
        long t = T0 + 10_000L;
        sell(25L, 3_513L, SOLD, t);
        offer(0, EMPTY, 0, 0, 0, 0, t + 2_000L);
        engine.tickGe(t + 2_000L + LATER);

        assertEquals("3,513 / 50 = 70 at most, never less than the truth", -70L, net());
        assertEquals(0, reviewRows());
        assertTrue(lastTrade().getExplanation().contains("highest tax"));
    }

    @Test
    public void aCancelledSaleSendsWhatDidNotSellToTheBankToo()
    {
        long t = T0 + 10_000L;
        sell(40L, 0L, SELLING, t);
        offer(0, SELLING, 40, 140, 10, 1_400, t + 1_000L);
        offer(0, CANCELLED_SELL, 40, 140, 10, 1_400, t + 1_500L);
        offer(0, EMPTY, 0, 0, 0, 0, t + 2_000L);
        engine.tickGe(t + 2_000L + LATER);

        assertEquals("only the 10 sold pay tax", -20L, net());
        assertEquals("the 30 unsold went back neutrally", 0, reviewRows());
        engine.tickGe(t + 2_000L + 11L * 60_000L);
        assertEquals(0, reviewRows());
    }

    @Test
    public void coinsCollectedToTheInventoryStillSettleOnceFromThem()
    {
        long t = T0 + 10_000L;
        sell(40L, 5_600L, SOLD, t);
        offer(0, EMPTY, 0, 0, 0, 0, t + 2_000L);
        engine.noteGeCollect(t + 2_100L);
        inventory.put(COINS, inventory.get(COINS) + 5_600L - 80L);
        settle(t + 2_200L);
        engine.tickGe(t + 2_000L + LATER);

        assertEquals(-80L, net());
        assertEquals("settled once, from the coins", 1, trades());
        assertEquals(0, reviewRows());
    }

    private void sell(long quantity, long spent, GrandExchangeOfferState state, long t)
    {
        offer(0, SELLING, (int) quantity, 140, 0, 0, t);
        inventory.put(RUNE, inventory.get(RUNE) - quantity);
        settle(t + 100L);
        if (state == SOLD) offer(0, SOLD, (int) quantity, 140, (int) quantity, (int) spent, t + 1_000L);
    }

    private long net()
    {
        return engine.getMetrics(T0 + 60L * 60_000L).net;
    }

    private int reviewRows()
    {
        int count = 0;
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            if (ReviewRules.needsOwnerDecision(transaction)) count++;
        }
        return count;
    }

    private int trades()
    {
        int count = 0;
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            if (transaction.autoType() == TransactionType.TRADE) count++;
        }
        return count;
    }

    private Transaction lastTrade()
    {
        Transaction last = null;
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            if (transaction.autoType() == TransactionType.TRADE) last = transaction;
        }
        return last;
    }

    private void offer(int slot, GrandExchangeOfferState state, int total, int price, int traded, int spent, long at)
    {
        int item = state == EMPTY ? 0 : state == BUYING || state == BOUGHT ? ItemID.WILD_CAVE_SHARD : RUNE;
        ledger.observe(new OfferSlots.Snapshot(slot, state, item, total, traded, price, spent))
            .ifPresent(transition -> engine.noteGeOffer(transition, "Item " + item, at));
    }

    private void settle(long at)
    {
        engine.markDirty();
        ContainerSnapshot snapshot = new ContainerSnapshot(new HashMap<>(inventory));
        engine.processIfDirty(snapshot, at);
        engine.processIfDirty(snapshot, at + 1L);
    }

    private static Engine engine(Map<Integer, Long> inventory)
    {
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                long unit = id == COINS ? 1L : 137L;
                flows.add(new Flow(id, id == COINS ? "Coins" : "Item " + id, delta.getValue(), (int) unit,
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
        engine.startCustom("Trading", T0);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(inventory)));
        return engine;
    }
}
