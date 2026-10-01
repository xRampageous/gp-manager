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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2C.1: Market activity presents the observed signed settlement as the primary value and
 * the canonical result as the explicit secondary line, on Live and in the Ledger.
 */
public class MarketActivityValueSemanticsTest
{
    private static final long T0 = System.currentTimeMillis() - 60_000L;
    private static final int COINS = 995;
    private static final int DEATH = 560;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void sellShowsObservedProceedsPrimaryAndResultSecondary()
    {
        Market market = new Market(Collections.singletonMap(DEATH, 186));
        market.gain(DEATH, 200L, 37_200L, market.now - 60_000L);
        market.sell(0, DEATH, 200L, 180);

        Ca snapshot = Ca.capture(market.engine, market.now + 1_000L, null);
        Ca.Recent row = marketRow(snapshot);
        assertNotNull(row);
        assertTrue("the primary value is the observed settlement", row.marketSettlementValue);
        assertEquals("proceeds are positive", 36_000L, row.value);
        assertEquals(-1_200L, row.marketResult);
        assertEquals("Sold", row.marketSide);
        String meta = LivePage.metaOf(row);
        assertTrue("secondary line names the side and the Result: " + meta,
            meta.contains("Sold") && meta.contains("Result " + Fmt.signed(-1_200L)));
        assertTrue("the proven quantity leads the second line: " + meta,
            meta.startsWith(Fmt.times(200L)));
    }

    @Test
    public void sellNetChangesOnlyByTheCanonicalResult()
    {
        Market market = new Market(Collections.singletonMap(DEATH, 186));
        market.gain(DEATH, 200L, 37_200L, market.now - 60_000L);
        market.sell(0, DEATH, 200L, 180);

        Ca snapshot = Ca.capture(market.engine, market.now + 1_000L, null);
        assertEquals("Net moves by the result only, never by the gross proceeds",
            37_200L - 1_200L, snapshot.net);
        assertEquals(market.engine.getMetrics(market.now + 1_000L).net, snapshot.net);
    }

    @Test
    public void unknownBasisSaleShowsSoldCashAndUnavailableResult()
    {
        Market market = new Market(Collections.singletonMap(DEATH, 12));
        market.sell(0, DEATH, 15L, 12);

        Ca snapshot = Ca.capture(market.engine, market.now + 1_000L, null);
        Ca.Recent row = marketRow(snapshot);
        assertNotNull(row);
        assertTrue(row.marketSettlementValue);
        assertFalse("automatic Result is unavailable", row.valueAvailable);
        assertEquals(180L, row.value);
        assertEquals(Fmt.signed(180L), LivePage.avo(row));
        assertTrue(LivePage.metaOf(row).contains("Sold \u00b7 Result \u2014"));
        assertEquals("unknown liquidation does not inflate Net", 0L, snapshot.net);
    }

    @Test
    public void buyShowsNegativeSpendPrimaryAndPositiveResultSecondary()
    {
        Market market = new Market(Collections.singletonMap(DEATH, 186));
        market.buy(0, DEATH, 200L, 180, 200L);

        Ca snapshot = Ca.capture(market.engine, market.now + 1_000L, null);
        Ca.Recent row = marketRow(snapshot);
        assertNotNull(row);
        assertTrue("the primary value is the observed settlement", row.marketSettlementValue);
        assertEquals("spend is negative", -36_000L, row.value);
        assertEquals("a NEW BUY is Net-neutral, so its Result is known zero", 0L, row.marketResult);
        assertEquals("Bought", row.marketSide);
        String meta = LivePage.metaOf(row);
        assertTrue(meta.contains("Bought") && meta.contains("Result " + Fmt.signed(0L)));
    }

    @Test
    public void pendingOrderHasNoRealizedPrimarySettlement()
    {
        Market market = new Market(Collections.singletonMap(DEATH, 186));
        market.pendingBuy(0, DEATH, 200L, 180);

        Ca snapshot = Ca.capture(market.engine, market.now + 1_000L, null);
        Ca.Recent row = marketRow(snapshot);
        assertNotNull(row);
        assertFalse("pending never claims a realized settlement", row.valueAvailable);
        assertFalse(row.marketSettlementValue);
        assertEquals("Buying", LivePage.avo(row));
        assertTrue("the detail line is the quantity, not the state again", LivePage.metaOf(row).contains("×"));
        assertFalse("status is not repeated on both sides",
            LivePage.metaOf(row).contains("Pending"));
        assertEquals("pending never alters Net", 0L, snapshot.net);
    }

    @Test
    public void partiallyFilledSaleNamesProgressWithoutShowingUncollectedCash()
    {
        Market market = new Market(Collections.singletonMap(DEATH, 186));
        market.partiallySoldUncollected(0, DEATH, 11L, 5L, 210);

        Ca snapshot = Ca.capture(market.engine, market.now + 1_000L, null);
        Ca.Recent row = marketRow(snapshot);
        assertNotNull(row);
        assertEquals("Pending", LivePage.avo(row));
        assertTrue(LivePage.metaOf(row).contains("Part sold 5/11"));
        assertFalse(row.marketSettlementValue);
        assertEquals(0L, snapshot.net);
    }

    @Test
    public void canceledAndReturnedOrderNoLongerSaysPending()
    {
        Market market = new Market(Collections.singletonMap(DEATH, 186));
        market.cancelBuy(0, DEATH, 5L, 180);

        Ca snapshot = Ca.capture(market.engine, market.now + 1_000L, null);
        // Owner 2026-09-28: a canceled, returned offer stays in the Ledger but leaves Recent.
        assertNull(marketRow(snapshot));
        assertEquals(0, snapshot.marketPending);
        assertEquals(0L, snapshot.net);
    }

    /** Owner 2026-09-28: a sale's second fill (an uncounted audit copy) read "Bones ×2 · 0". */
    @Test
    public void anUncountedTradeCopyNeverBecomesItsOwnRow()
    {
        Ac copy = new Ac(1_000L, null, Ai.TRADE, Aj.MARKET,
            "Grand Exchange", "Market", false, java.util.Arrays.asList(new Ab(526, "Bones", -2L, 37, -74L),
                new Ab(995, "Coins", 74L, 1, 74L)), Bd.CONFIRMED, "fixture", null);
        assertTrue(Br.capture(Collections.singletonList(copy), Collections.emptyList(),
            "", null).groups.isEmpty());
    }

    @Test
    public void partialFillUsesOnlyTheProvenSettledAmount()
    {
        Market market = new Market(Collections.singletonMap(DEATH, 186));
        market.buy(0, DEATH, 200L, 180, 100L);

        Ca snapshot = Ca.capture(market.engine, market.now + 1_000L, null);
        Ca.Recent row = marketRow(snapshot);
        assertNotNull(row);
        assertEquals("only the proven settled 100 are presented", -18_000L, row.value);
        assertEquals("a NEW BUY is Net-neutral at acquisition", 0L, row.marketResult);
    }

    @Test
    public void summaryLineExplicitlyIdentifiesTheMarketResult() throws Exception
    {
        Market market = new Market(Collections.singletonMap(DEATH, 186));
        market.gain(DEATH, 200L, 37_200L, market.now - 60_000L);
        market.sell(0, DEATH, 200L, 180);
        Ca snapshot = Ca.capture(market.engine, market.now + 1_000L, null);

        LivePage page = onEdt(() ->
        {
            LivePage created = new LivePage(new LiveNoop(), id -> null);
            created.apply(snapshot);
            return created;
        });
        assertEquals("the Market cell shows the result versus basis", Fmt.signed(-1_200L),
            HeroProbe.cell(page.hero, "MARKET"));
    }

    @Test
    public void ledgerMarketRowUsesTheSameValueSemantics() throws Exception
    {
        Market market = new Market(Collections.singletonMap(DEATH, 186));
        market.gain(DEATH, 200L, 37_200L, market.now - 60_000L);
        market.sell(0, DEATH, 200L, 180);

        Ao data = Ao.capture(market.engine, market.now + 1_000L,
            Ao.Entry.current());
        Br.Group group = null;
        for (Br.Group candidate : data.market.groups)
        {
            if (candidate.market)
            {
                group = candidate;
            }
        }
        assertNotNull(group);
        assertNotNull("the raw settlement evidence resolves", data.zu(group.marketPresentationId));

        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(new LedgerNoop(), id -> null);
            created.apply(data);
            return created;
        });
        String semantics = LedgerPageProbe.marketRowSemantics(page, group.semanticGroupId);
        assertEquals(Fmt.signed(36_000L) + "|Sold \u00b7 Result " + Fmt.signed(-1_200L), semantics);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Ca.Recent marketRow(Ca snapshot)
    {
        for (Ca.Recent row : snapshot.recent)
        {
            if (row.market)
            {
                return row;
            }
        }
        return null;
    }

    private static Am engine(Map<Integer, Integer> quote)
    {
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                int unit = id == COINS ? 1 : quote.getOrDefault(id, 7);
                Av source = id == COINS ? Av.FACE_VALUE
                    : Av.GRAND_EXCHANGE;
                flows.add(new Ab(id, id == COINS ? "Coins" : "Death rune", delta.getValue(),
                    unit, delta.getValue() * unit, source));
            }
            return flows;
        }, new TransactionClassifier(), new GpManagerConfig()
        {
            @Override
            public Db receiptRetentionDays()
            {
                return Db.DAYS_365;
            }
        });
    }

    private static final class Market
    {
        final Am engine;
        final Bj ledger = new Bj();
        final Map<Integer, Long> inventory = new HashMap<>();
        long now = T0;

        Market(Map<Integer, Integer> quote)
        {
            engine = engine(quote);
            engine.ajl("Trading", Cx.AUTO, now);
            inventory.put(COINS, 1_000_000L);
            engine.setBaseline(new Cc(inventory));
        }

        void gain(int item, long qty, long value, long at)
        {
            engine.getActiveSession().kf(new Ac(at, null,
                Ai.GAIN, Aj.GENERIC, "", "Loot", true,
                Collections.singletonList(new Ab(item, "Death rune", qty,
                    (int) (qty > 0L ? value / qty : 0L), value, Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null), 500);
        }

        void sell(int slot, int item, long qty, int price)
        {
            inventory.put(item, qty);
            engine.setBaseline(new Cc(inventory));
            offer(slot, GrandExchangeOfferState.SELLING, item, (int) qty, 0, price, 0);
            inventory.remove(item);
            settle();
            offer(slot, GrandExchangeOfferState.SOLD, item, (int) qty, (int) qty, price, (int) (qty * price));
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + qty * price);
            settle();
        }

        void buy(int slot, int item, long total, int price, long filled)
        {
            offer(slot, GrandExchangeOfferState.BUYING, item, (int) total, 0, price, 0);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) - filled * price);
            settle();
            offer(slot, GrandExchangeOfferState.BOUGHT, item, (int) total, (int) filled, price,
                (int) (filled * price));
            inventory.put(item, inventory.getOrDefault(item, 0L) + filled);
            settle();
        }

        void pendingBuy(int slot, int item, long total, int price)
        {
            offer(slot, GrandExchangeOfferState.BUYING, item, (int) total, 0, price, 0);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) - total * price);
            settle();
        }

        void partiallySoldUncollected(int slot, int item, long total, long filled, int price)
        {
            inventory.put(item, total);
            engine.setBaseline(new Cc(inventory));
            offer(slot, GrandExchangeOfferState.SELLING, item, (int) total, 0, price, 0);
            inventory.remove(item);
            settle();
            offer(slot, GrandExchangeOfferState.SELLING, item, (int) total, (int) filled,
                price, (int) (filled * price));
        }

        void cancelBuy(int slot, int item, long total, int price)
        {
            pendingBuy(slot, item, total, price);
            offer(slot, GrandExchangeOfferState.CANCELLED_BUY, item, (int) total, 0, price, 0);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + total * price);
            settle();
        }

        private void settle()
        {
            now += 600L;
            engine.yz();
            Cc snapshot = new Cc(inventory);
            for (int i = 0; i < 3; i++)
            {
                engine.adj(snapshot, now);
                now += 600L;
            }
        }

        private void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent)
        {
            now += 600L;
            Bj.Transition transition = ledger.observe(
                new Bj.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.abh(transition, "Death rune", now);
            }
        }
    }

    /** Swing pages must be built and applied on the EDT to stay isolated from other tests. */
    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        if (javax.swing.SwingUtilities.isEventDispatchThread())
        {
            return callable.call();
        }
        java.util.concurrent.atomic.AtomicReference<T> value =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() ->
        {
            try { value.set(callable.call()); }
            catch (Throwable ex) { failure.set(ex); }
        });
        if (failure.get() != null)
        {
            throw new AssertionError(failure.get());
        }
        return value.get();
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

    private static final class LedgerNoop implements LedgerPage.Actions
    {
        @Override public void openScopeMenu(javax.swing.JComponent anchor) { }
        @Override public void costViewChanged(Ao.Bs view) { }
        @Override public void searchChanged(String text) { }
        @Override public Ao.Ef preview(String id,
            Ah correction) { return null; }
        @Override public LedgerPage.Ea correct(String id,
            Ah correction, long previewRevision)
        { return LedgerPage.Ea.REFUSED; }
        @Override public void split(String id) { }
        @Override public void undoCorrection() { }
        @Override public void decideAll(Cl decision) { }
        @Override public void refresh() { }
    }
}
