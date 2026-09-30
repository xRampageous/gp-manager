package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2C.5: the Live hero removes every counted Market-context transaction from ordinary
 * Gains/Supplies/Losses and shows its correction-aware net once; display filters never touch
 * hero accounting or the canonical pending count.
 */
public class LiveMarketExclusionTest
{
    private static final long T0 = System.currentTimeMillis() - 10 * 60_000L;
    private static final int COINS = 995;
    private static final int CHAOS = 562;
    private static final int CHEAP = 314;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    // ── real canonical paths ──────────────────────────────────────────────────────────────────

    @Test
    public void realSellRemovesGrossMarketLegsFromOrdinaryCategories()
    {
        Market market = new Market();
        market.gain(10_000L, CHEAP, 100L, 100, market.now - 60_000L);
        market.gain(3_150L, CHAOS, 30L, 105, market.now - 55_000L);
        market.loss(2_000L, market.now - 50_000L);
        market.sell(0, CHAOS, 30L, 104, 3_120L, 3_060L);

        Ca snapshot = snapshot(market);
        Bu metrics = market.engine.getMetrics(market.now + 1_000L);

        assertEquals("the canonical session still books the coverage gain and the sale cash",
            10_000L + 3_150L + 3_060L, metrics.revenue);
        assertEquals("the canonical session still books the consumed known basis",
            2_000L + 3_150L, metrics.costs);
        assertEquals("ordinary Gains exclude the whole Market transaction",
            10_000L + 3_150L, snapshot.gains);
        assertEquals("ordinary Losses exclude the whole Market transaction", 2_000L, snapshot.loss);
        assertEquals(0L, snapshot.supplies);
        assertEquals("combined ordinary Costs exclude the Market gross cost", 2_000L, snapshot.costs);
        assertEquals("the correction-aware Market net is shown once", -90L, snapshot.marketResult);
        assertEquals("Net stays canonical", 13_150L - 2_000L - 90L, snapshot.net);
        assertEquals(metrics.net, snapshot.net);
    }

    @Test
    public void realBuyRemovesGrossLegsAndShowsOnlyTheMarketResult()
    {
        Market market = new Market();
        market.buy(0, CHAOS, 30L, 102, 3_060L);

        Ca snapshot = snapshot(market);
        Bu metrics = market.engine.getMetrics(market.now + 1_000L);

        assertEquals("the canonical session books the exact spend as the asset value",
            3_060L, metrics.revenue);
        assertEquals("the canonical session books the cash spend as costs", 3_060L, metrics.costs);
        assertEquals("acquired value never appears as an ordinary Gain", 0L, snapshot.gains);
        assertEquals("purchase cash never appears as an ordinary Loss", 0L, snapshot.loss);
        assertEquals(0L, snapshot.costs);
        assertEquals("a NEW BUY is Net-neutral", 0L, snapshot.marketResult);
        assertEquals(0L, snapshot.net);
    }

    @Test
    public void oldStockSettlementNeverInflatesOrdinaryCategories()
    {
        Market market = new Market();
        market.sell(0, CHAOS, 30L, 104, 3_120L, 3_060L);

        Ca snapshot = snapshot(market);

        assertEquals("gross settlement is never an ordinary Gain", 0L, snapshot.gains);
        assertEquals("the frozen listed value is never an ordinary Loss", 0L, snapshot.loss);
        assertEquals("pre-existing stock has UNKNOWN basis: only the proven GE tax is counted",
            -60L, snapshot.marketResult);
        assertEquals("only the proven GE tax reduces Net", -60L, snapshot.net);
        assertEquals("no zero acquisition basis was inferred",
            MarketFacts.basisValueGp(MarketFacts.record(market.engine, market.engine.ub().get(0))), 3_150L);
        assertEquals(Bi.Coverage.FULLY_UNKNOWN,
            market.engine.ub().get(0).coverage);
    }

    @Test
    public void heldPriceMovementIsNotBooked()
    {
        Market market = new Market();
        market.gain(3_000L, CHAOS, 30L, 100, market.now - 60_000L);
        market.sell(0, CHAOS, 30L, 104, 3_120L, 3_060L);

        Ca snapshot = snapshot(market);
        assertEquals("loot gain plus the known-basis Market result is the booked contribution",
            3_000L + 60L, snapshot.net);
        assertEquals("the +150 holding appreciation is never booked",
            0L, snapshot.gains - 3_000L);
        var row = market.engine.ub().get(0);
        assertEquals("counted basis + result = settlement",
            row.observedSettlementGp,
            row.trackedBasisConsumedGp + row.realizedResultGp);
        assertEquals(3_150L, MarketFacts.basisValueGp(MarketFacts.record(market.engine, row)));
        assertEquals(3_000L, row.trackedBasisConsumedGp);
        assertEquals(3_060L, row.observedSettlementGp);
    }

    @Test
    public void mixedMarketFoldsTransactionLevelIncludingTheSettlementAdjustment()
    {
        Market market = new Market();
        market.gain(5_000L, CHEAP, 50L, 100, market.now - 60_000L);
        market.gain(2_100L, CHAOS, 20L, 105, market.now - 55_000L);
        market.loss(1_000L, market.now - 50_000L);
        market.sell(0, CHAOS, 10L, 107, 1_070L, 1_060L);
        market.sell(1, CHAOS, 10L, 104, 1_040L, 1_020L);
        market.adjustment(25L, market.now - 1_000L);

        Bp.Dy fold = Bp.zl(
            market.engine.getActiveSession().getTransactions());
        assertEquals("all three Market-context transactions are folded", 3, fold.transactions);
        assertEquals(5L, fold.getNet());

        Ca snapshot = snapshot(market);
        assertEquals("ordinary Gains contain no gross Market legs", 7_100L, snapshot.gains);
        assertEquals("ordinary Losses contain no gross Market legs", 1_000L, snapshot.loss);
        assertEquals("Market is the exact transaction-level sum, including the adjustment",
            5L, snapshot.marketResult);
        assertEquals(7_100L - 1_000L + 5L, snapshot.net);
    }

    @Test
    public void correctionKeepsMarketMembershipAndUndoRestores()
    {
        Market market = new Market();
        market.gain(3_150L, CHAOS, 30L, 105, market.now - 55_000L);
        market.sell(0, CHAOS, 30L, 104, 3_120L, 3_060L);
        String settlementId = market.engine.ub().get(0).settlementId;
        assertTrue(market.engine.qi(settlementId, Ah.COST,
            market.now, "test"));

        Bp.Dy corrected = Bp.zl(
            market.engine.getActiveSession().getTransactions());
        assertEquals("a correction never moves the transaction out of Market",
            1, corrected.transactions);
        Ca after = snapshot(market);
        assertEquals("effective corrected costs drive the Market result",
            -(3_150L + 3_060L), after.marketResult);
        assertEquals("ordinary Gains keep only the loot gain", 3_150L, after.gains);
        assertEquals("no gross leg leaks back into ordinary Losses", 0L, after.loss);

        assertTrue(market.engine.akb(market.now));
        Ca restored = snapshot(market);
        assertEquals("Undo restores the derived presentation", -90L, restored.marketResult);
        assertEquals("the ordinary loot gain is untouched by the undo", 3_150L, restored.gains);
    }

    @Test
    public void groupedMarketReceiptsUseTheTransactionLevelSum()
    {
        Market market = new Market();
        market.gain(2_100L, CHAOS, 20L, 105, market.now - 55_000L);
        market.sell(0, CHAOS, 10L, 107, 1_070L, 1_060L);
        market.sell(1, CHAOS, 10L, 104, 1_040L, 1_020L);

        Ca snapshot = snapshot(market);
        Bp.Dy fold = Bp.zl(
            market.engine.getActiveSession().getTransactions());
        assertEquals("one winner and one loser fold transaction-level", -20L, fold.getNet());
        assertEquals("the Market line is the transaction-level sum", -20L, snapshot.marketResult);
        assertEquals(2, market.engine.ub().size());
    }

    // ── display filters ───────────────────────────────────────────────────────────────────────

    @Test
    public void displayFiltersChangeRecentRowsOnlyNeverHeroOrPending()
    {
        Market market = new Market();
        market.gain(3_150L, CHAOS, 30L, 105, market.now - 55_000L);
        market.gain(10_000L, 385, 10L, 1_000, market.now - 50_000L);
        market.gain(50L, CHEAP, 5L, 10, market.now - 1_000L);
        market.sell(0, CHAOS, 30L, 104, 3_120L, 3_060L);
        market.pendingSell(1, CHAOS, 100L, 104);

        Ca plain = Ca.capture(market.engine, market.now + 1_000L, null);
        Ca threshold = Ca.capture(market.engine, market.now + 1_000L,
            new Dz(false, null, 1_000L, Bo.NONE, "", false, ""));
        Ca itemFiltered = Ca.capture(market.engine, market.now + 1_000L,
            new Dz(false, flow -> flow.itemId != CHEAP, 0L, Bo.NONE, "", false, ""));

        assertEquals("cheap hidden loot still counts in canonical Gains", 13_200L, threshold.gains);
        assertEquals(plain.gains, threshold.gains);
        assertEquals(plain.gains, itemFiltered.gains);
        assertEquals("the SELL coin leg still drives the Market math", -90L, threshold.marketResult);
        assertEquals("the unfiltered capture agrees", -90L, plain.marketResult);
        assertEquals(plain.marketResult, itemFiltered.marketResult);
        assertEquals(plain.net, threshold.net);
        assertEquals(plain.net, itemFiltered.net);
        assertEquals("pending Market is counted canonically", 1, plain.marketPending);
        assertEquals("display filters never change the pending count", 1, threshold.marketPending);
        assertEquals(1, itemFiltered.marketPending);

        assertTrue("the cheap row is visible without a filter",
            hasRecentItem(plain, CHEAP));
        assertFalse("the minimum-value filter hides the cheap row",
            hasRecentItem(threshold, CHEAP));
        assertFalse("the item filter hides the cheap row",
            hasRecentItem(itemFiltered, CHEAP));
    }

    @Test
    public void combinedCostFallbackExcludesMarketCosts()
    {
        Ac trade = new Ac(T0 + 1_000L, null, Ai.TRADE,
            Aj.MARKET, "Grand Exchange", "Market", true,
            java.util.Arrays.asList(
                new Ab(CHAOS, "Chaos rune", -30L, 105, -3_150L, Av.GRAND_EXCHANGE),
                new Ab(COINS, "Coins", 3_060L, 1, 3_060L, Av.FACE_VALUE)),
            Bd.CONFIRMED, "", null);
        Bp.Dy fold = Bp.zl(
            Collections.singletonList(trade));
        Bu metrics = new Bu("", false, 0L, 10_000L + 3_060L, 2_000L + 3_150L, 7_910L, 0L, 0L, true, 0L, 0L, false);

        assertFalse(metrics.costSplitAvailable);
        assertEquals(-1L, Ca.aky(metrics, fold));
        assertEquals(-1L, Ca.akx(metrics, fold));
        assertEquals("combined ordinary Costs exclude the Market gross cost",
            2_000L, Ca.aku(metrics, fold));
        assertEquals(10_000L, Ca.akw(metrics, fold));
        assertEquals("no fake historical split is invented", -1L,
            Ca.aky(metrics, fold));
    }

    // ── cross-surface and purity ──────────────────────────────────────────────────────────────

    @Test
    public void liveOrdinaryCategoriesMatchTheLedgerForTheSameUnfilteredScope()
    {
        Market market = new Market();
        market.gain(10_000L, CHEAP, 100L, 100, market.now - 60_000L);
        market.loss(2_000L, market.now - 50_000L);
        market.sell(0, CHAOS, 30L, 104, 3_120L, 3_060L);

        Ca snapshot = snapshot(market);
        Ao allView = Ao.capture(market.engine, market.now + 1_000L,
            Ao.Entry.current());
        Ao suppliesView = Ao.capture(market.engine, market.now + 1_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "", null, null, null, null));
        Ao lossView = Ao.capture(market.engine, market.now + 1_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.LOSS, "", null, null, null, null));

        assertEquals("Live Gains match the Ledger Gains total",
            snapshot.gains, suppliesView.gains.total);
        assertEquals("Live Supplies match the Ledger Supplies total",
            -snapshot.supplies, suppliesView.costs.total);
        assertEquals("Live Losses match the Ledger Loss total",
            -snapshot.loss, lossView.costs.total);
        assertEquals("Costs defaults to All: Supply and Loss together",
            -snapshot.supplies - snapshot.loss, allView.costs.total);
        assertEquals("Live Market matches the Ledger Market net",
            snapshot.marketResult, suppliesView.market.total);
        assertEquals("Live Net matches the canonical scope Net", snapshot.net, suppliesView.net);
    }

    @Test
    public void heroMathNeverMutatesCanonicalTruth()
    {
        Market market = new Market();
        market.gain(10_000L, CHEAP, 100L, 100, market.now - 60_000L);
        market.sell(0, CHAOS, 30L, 104, 3_120L, 3_060L);
        Ac settlement = market.engine.getActiveSession().getTransactions().get(1);
        long revisionBefore = market.engine.getRevision();
        long netBefore = settlement.getNet();

        Ca snapshot = snapshot(market);
        new LivePage(new LiveNoop(), id -> null).apply(snapshot);

        assertEquals(revisionBefore, market.engine.getRevision());
        assertEquals(netBefore, settlement.getNet());
        assertEquals(108, SavedState.CURRENT_SCHEMA_VERSION);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Ca snapshot(Market market)
    {
        return Ca.capture(market.engine, market.now + 1_000L, null);
    }

    private static boolean hasRecentItem(Ca snapshot, int itemId)
    {
        for (Ca.Recent row : snapshot.recent)
        {
            if (row.itemId == itemId)
            {
                return true;
            }
        }
        return false;
    }

    private static final class LiveNoop implements LivePage.Actions
    {
        @Override
        public void editTarget()
        {
        }

        @Override public void togglePause() { }

        @Override public void openLedger(Ao.Entry entry) { }
    }

    private static final class Market
    {
        final int[] quote = {105};
        final Am engine;
        final Bj ledger = new Bj();
        final Map<Integer, Long> inventory = new HashMap<>();
        long now = T0;

        Market()
        {
            engine = new Am(deltas ->
            {
                List<Ab> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> delta : deltas.entrySet())
                {
                    int id = delta.getKey();
                    int unit = id == COINS ? 1 : quote[0];
                    Av source = id == COINS ? Av.FACE_VALUE
                        : Av.GRAND_EXCHANGE;
                    flows.add(new Ab(id, id == COINS ? "Coins" : "Chaos rune", delta.getValue(),
                        unit, delta.getValue() * unit, source));
                }
                return flows;
            }, new TransactionClassifier(), new GpManagerConfig()
            {
                @Override public Db receiptRetentionDays()
                {
                    return Db.DAYS_365;
                }
                @Override public boolean keepTransferAuditRows()
                {
                    return true;
                }
            });
            engine.ajl("Trading", Cx.AUTO, now);
            inventory.put(COINS, 1_000_000L);
            engine.setBaseline(new Cc(inventory));
        }

        void gain(long value, int itemId, long quantity, int unit, long at)
        {
            engine.getActiveSession().kf(new Ac(at, null,
                Ai.GAIN, Aj.GENERIC, "", "Loot", true,
                Collections.singletonList(new Ab(itemId, "Item " + itemId, quantity, unit, value,
                    Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null), 500);
        }

        /** An unrelated ordinary loss (a distinct item, so no tracked coverage is depleted). */
        void loss(long value, long at)
        {
            engine.getActiveSession().kf(new Ac(at, null,
                Ai.CONSUMPTION, Aj.GENERIC, "", "Loss", true,
                Collections.singletonList(new Ab(385, "Shark", -value / 100L, 100, -value,
                    Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null), 500);
        }

        void adjustment(long coins, long at)
        {
            engine.getActiveSession().kf(new Ac(at, null,
                Ai.TRADE, Aj.MARKET, "Grand Exchange settlement adjustment",
                "Market", true,
                Collections.singletonList(new Ab(COINS, "Coins", coins, 1, coins,
                    Av.FACE_VALUE)),
                Bd.CONFIRMED,
                "Grand Exchange batch settlement adjustment: exact observed cash differs from "
                    + "attributed gross execution; per-offer allocation is not provable.", null), 500);
        }

        void sell(int slot, int item, long qty, int limit, long spent, long cash)
        {
            inventory.put(item, qty);
            engine.setBaseline(new Cc(inventory));
            offer(slot, GrandExchangeOfferState.SELLING, item, (int) qty, 0, limit, 0);
            inventory.remove(item);
            settle();
            offer(slot, GrandExchangeOfferState.SOLD, item, (int) qty, (int) qty, limit, (int) spent);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + cash);
            settle();
        }

        void buy(int slot, int item, long qty, int limit, long spent)
        {
            offer(slot, GrandExchangeOfferState.BUYING, item, (int) qty, 0, limit, 0);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) - spent);
            settle();
            offer(slot, GrandExchangeOfferState.BOUGHT, item, (int) qty, (int) qty, limit, (int) spent);
            inventory.put(item, inventory.getOrDefault(item, 0L) + qty);
            settle();
        }

        void pendingSell(int slot, int item, long offered, int limit)
        {
            inventory.put(item, offered);
            engine.setBaseline(new Cc(inventory));
            offer(slot, GrandExchangeOfferState.SELLING, item, (int) offered, 0, limit, 0);
            inventory.remove(item);
            settle();
        }

        private void settle()
        {
            now += 5_000L;
            engine.yz();
            Cc snapshot = new Cc(inventory);
            for (int i = 0; i < 3; i++)
            {
                engine.adj(snapshot, now);
                now += 5_000L;
            }
        }

        private void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent)
        {
            now += 5_000L;
            Bj.Transition transition = ledger.observe(
                new Bj.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.abh(transition, "Chaos rune", now);
            }
        }
    }
}
