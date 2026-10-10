package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-10-09: a sale collected while still in progress (1,300 of 1,927 revenant ether, then
 * 500, then the last 127) books one settlement per collect. Live shows them as the offer's one
 * market row with the running total and Result, never as a separate "Traded" item cost.
 */
public class PartialSaleRecentTest
{
    private static final int COINS = 995;
    private static final int ETHER = 21820;

    private final OfferSlots ledger = new OfferSlots();
    private final Map<Integer, Long> inventory = new HashMap<>(Map.of(COINS, 1_000_000L, ETHER, 1_927L));
    private final Engine engine = new Engine(deltas ->
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) -> flows.add(new Flow(id, id == COINS ? "Coins" : "Revenant ether", quantity,
            id == COINS ? 1 : 174, quantity * (id == COINS ? 1 : 174), id == COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE)));
        return flows;
    }, new TransactionClassifier(), new GpManagerConfig()
    {
        @Override
        public int stabilizationTicks()
        {
            return 0;
        }
    });
    private long now = System.currentTimeMillis() - 600_000L;

    @Test
    public void aSaleCollectedInPartsIsOneMarketRowWithTheRunningResult()
    {
        engine.startCustom("Trading", now);
        engine.setBaseline(new ContainerSnapshot(new HashMap<>(inventory)));
        offer(SELLING, 0, 0);
        move(ETHER, -1_927L);

        offer(SELLING, 1_300, 226_200);
        collect(222_300L);
        check("first collect", 1_300L, 222_300L, -3_900L);

        offer(SELLING, 1_800, 313_200);
        collect(85_500L);
        check("second collect", 1_800L, 307_800L, -5_400L);

        offer(SELLING, 1_927, 335_298);
        offer(SOLD, 1_927, 335_298);
        collect(21_717L);
        offer(EMPTY, 0, 0);
        check("last collect", 1_927L, 329_517L, -5_781L);
        assertEquals("three settlements, one per collect", 3, engine.getActiveSession().getTransactions().size());
        assertEquals("only the tax is lost", -5_781L, engine.getMetrics(now).net);
    }

    private void check(String step, long quantity, long received, long result)
    {
        Map<String, MarketRows.Row> rows = new HashMap<>();
        for (MarketRows.Row row : engine.marketRows())
        {
            rows.put(row.presentationId, row);
            assertEquals(step + ": the Result adds every settlement", result, row.realizedResultGp);
        }
        List<LiveSnapshot.Recent> recent = LiveSnapshot.recentRows(engine.summary().view(engine.summary().scope(
            Collections.singletonList(engine.getActiveSession())), LiveContext.NONE::flowVisible), rows, null);
        assertEquals(step + ": one row, no separate Traded item cost", 1, recent.size());
        LiveSnapshot.Recent row = recent.get(0);
        assertTrue(step, row.market);
        assertEquals(step, quantity, row.quantity);
        assertEquals(step + ": the received cash so far", received, row.value);
    }

    private void offer(GrandExchangeOfferState state, int traded, int spent)
    {
        now += 1_000L;
        boolean empty = state == EMPTY;
        OfferSlots.Transition transition = ledger.observe(new OfferSlots.Snapshot(0, state, empty ? 0 : ETHER,
            empty ? 0 : 1_927, traded, empty ? 0 : 174, spent)).orElse(null);
        if (transition != null)
        {
            engine.noteGeOffer(transition, "Revenant ether", now);
        }
    }

    private void collect(long coins)
    {
        now += 3_000L;
        engine.noteGeCollect(now);
        move(COINS, coins);
    }

    private void move(int item, long delta)
    {
        inventory.merge(item, delta, Long::sum);
        inventory.values().removeIf(quantity -> quantity == 0L);
        now += 600L;
        engine.markDirty();
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now);
        engine.processIfDirty(new ContainerSnapshot(new HashMap<>(inventory)), now + 1L);
    }
}
