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
import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Owner 1.2 (revenant ether and bracelet buys after the Craw's bow): fast buys never leave a coin
 * row in Review. One instantly filled and collected offer settles as a single net spend, and a
 * finished offer collected just before its slot is reused still settles as a purchase.
 */
public class GeFastBuyTest
{
    private static final int COINS = ItemID.COINS;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void anOfferPlacedBoughtAndCollectedInOneSettleIsOnePurchase()
    {
        Map<Integer, Long> inventory = new HashMap<>(Map.of(COINS, 1_000_000L));
        Engine engine = engine(inventory);
        OfferSlots ledger = new OfferSlots();
        long t = T0 + 10_000L;
        offer(engine, ledger, 3, BUYING, ItemID.WILD_CAVE_SHARD, 1200, 344, 0, 0, t);
        offer(engine, ledger, 3, BOUGHT, ItemID.WILD_CAVE_SHARD, 1200, 344, 1200, 211_200, t + 50L);
        offer(engine, ledger, 3, EMPTY, 0, 0, 0, 0, 0, t + 100L);
        engine.noteGeCollect(t + 120L);
        inventory.put(COINS, 1_000_000L - 211_200L);
        inventory.put(ItemID.WILD_CAVE_SHARD, 1200L);
        settle(engine, inventory, t + 200L);

        assertEquals("no coin row in Review", 0, reviewRows(engine));
        assertTrue(engine.getActiveSession().getTransactions().stream().anyMatch(row -> row.autoType() == TransactionType.TRADE));
        assertEquals("buying is Net-neutral", 0L, engine.getMetrics(t + 1_000L).net);
    }

    @Test
    public void aFinishedOfferCollectedJustBeforeItsSlotIsReusedStillSettles()
    {
        Map<Integer, Long> inventory = new HashMap<>(Map.of(COINS, 1_000_000L));
        Engine engine = engine(inventory);
        OfferSlots ledger = new OfferSlots();
        long t = T0 + 10_000L;
        offer(engine, ledger, 3, BUYING, ItemID.WILD_CAVE_BRACELET_UNCHARGED, 2, 45_000, 0, 0, t);
        inventory.put(COINS, 1_000_000L - 90_000L);
        settle(engine, inventory, t + 20L);
        offer(engine, ledger, 3, BOUGHT, ItemID.WILD_CAVE_BRACELET_UNCHARGED, 2, 45_000, 2, 85_058, t + 50L);
        offer(engine, ledger, 3, EMPTY, 0, 0, 0, 0, 0, t + 100L);
        offer(engine, ledger, 3, BUYING, ItemID.WILD_CAVE_SHARD, 300, 181, 0, 0, t + 150L);
        engine.noteGeCollect(t + 160L);
        inventory.put(COINS, 1_000_000L - 85_058L);
        inventory.put(ItemID.WILD_CAVE_BRACELET_UNCHARGED, 2L);
        settle(engine, inventory, t + 200L);

        assertEquals("no Review for the reused slot", 0, reviewRows(engine));
        assertTrue(engine.getActiveSession().getTransactions().stream().anyMatch(row -> row.autoType() == TransactionType.TRADE));
    }

    private static int reviewRows(Engine engine)
    {
        int count = 0;
        for (Transaction transaction : engine.getActiveSession().getTransactions())
        {
            if (ReviewRules.needsOwnerDecision(transaction))
            {
                count++;
            }
        }
        return count;
    }

    private static void offer(Engine engine, OfferSlots ledger, int slot, GrandExchangeOfferState state, int itemId,
        int total, int price, int traded, int spent, long at)
    {
        ledger.observe(new OfferSlots.Snapshot(slot, state, itemId, total, traded, price, spent))
            .ifPresent(transition -> engine.noteGeOffer(transition, "Item " + itemId, at));
    }

    private static void settle(Engine engine, Map<Integer, Long> inventory, long at)
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
                long unit = id == COINS ? 1L : 176L;
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
