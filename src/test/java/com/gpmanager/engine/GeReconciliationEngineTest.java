package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.BOUGHT;
import static net.runelite.api.GrandExchangeOfferState.BUYING;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL;
import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pass 10 step 45: Grand Exchange settlements derived from observed offer transitions (no client),
 * in both booking modes, over the sequences B01 lists.
 */
public class GeReconciliationEngineTest
{
    private static final int LOGS = 1519;
    private static final int SHARK = 385;
    private static final int COINS = 995;
    private static final long T0 = 1_000_000_000_000L;

    /** Everything one offline run needs: an engine, a ledger and the same handling the plugin does. */
    private static final class Harness
    {
        final GpManagerEngine engine = engine();
        final GeOfferLedger ledger = new GeOfferLedger();
        final List<GeOfferProvenanceMatcher.Observation> provenance = new ArrayList<>();
        long now = T0;
        Map<Integer, Long> inventory = new HashMap<>();

        Harness(GeBookingMode mode)
        {
            engine.setGeBookingMode(mode);
            engine.startCustomSession("Trading", SessionMode.AUTO, now);
            inventory.put(COINS, 100_000L);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }

        /** The plugin's handler, minus the client: observe, then book or keep as provenance. */
        ProfitTransaction offer(int slot, GrandExchangeOfferState state, int item, int total, int traded, int price, int spent)
        {
            now += 600L;
            GeOfferLedger.Transition t = ledger.observe(new GeOfferLedger.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            return handle(t);
        }

        ProfitTransaction handle(GeOfferLedger.Transition t)
        {
            if (t == null || !t.hasComparableProgress() || t.getQuantityTradedDelta() <= 0) return null;
            ProfitTransaction booked = engine.bookObservedGeSettlement(t, name(t.getCurrent().getItemId()), price(t.getCurrent().getItemId()), now);
            if (booked == null) provenance.add(new GeOfferProvenanceMatcher.Observation(t, now));
            return booked;
        }

        /** An inventory change with the GE open (the plugin arms MARKET on buy/sell/collect). */
        ProfitTransaction geInventory(Map<Integer, Long> next)
        {
            now += 600L;
            engine.markContext(TrackingContext.MARKET, 10, "collect");
            inventory = new HashMap<>(next);
            engine.markInventoryDirty();
            ProfitTransaction first = engine.processIfDirty(new ContainerSnapshot(inventory), now);
            ProfitTransaction settled = engine.processIfDirty(new ContainerSnapshot(inventory), now + 600L);
            now += 600L;
            return settled == null ? first : settled;
        }

        List<ProfitTransaction> counted()
        {
            List<ProfitTransaction> out = new ArrayList<>();
            for (ProfitTransaction t : engine.getActiveSession().getTransactions())
            {
                if (t != null && t.isCounted()) out.add(t);
            }
            return out;
        }

        long net()
        {
            return engine.getMetrics(now).getNet();
        }
    }

    private static String name(int id)
    {
        return id == LOGS ? "Willow logs" : id == SHARK ? "Shark" : id == COINS ? "Coins" : "Item " + id;
    }

    private static int price(int id)
    {
        return id == LOGS ? 48 : id == SHARK ? 800 : id == COINS ? 1 : 0;
    }

    private static Map<Integer, Long> with(Map<Integer, Long> base, Object... pairs)
    {
        Map<Integer, Long> m = new HashMap<>(base);
        for (int i = 0; i < pairs.length; i += 2)
        {
            long q = (Long) pairs[i + 1];
            if (q <= 0L) m.remove((Integer) pairs[i]);
            else m.put((Integer) pairs[i], q);
        }
        return m;
    }

    private static long flow(ProfitTransaction t, int itemId)
    {
        for (ItemFlow f : t.getFlows()) if (f.getItemId() == itemId) return f.getValueDelta();
        return 0L;
    }

    // ── observed mode: each sequence settles exactly once, from the slot ─────────────────

    @Test
    public void buyFullFillBooksOnceAndTheCollectionIsNeutral()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        assertNull(h.offer(0, BUYING, LOGS, 10, 0, 48, 0));
        ProfitTransaction booked = h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480);
        assertNotNull(booked);
        assertTrue(booked.isCounted());
        assertEquals(TransactionType.TRADE, booked.getType());
        assertEquals(480L, flow(booked, LOGS));
        assertEquals(-480L, flow(booked, COINS));
        assertEquals(0, booked.getGeOfferProvenance().getSlot());
        // Collect to inventory: the physical movement is a transfer, never a second trade.
        ProfitTransaction collect = h.geInventory(with(h.inventory, LOGS, 10L));
        assertNotNull(collect);
        assertFalse(collect.isCounted());
        assertEquals(TransactionType.TRANSFER, collect.getType());
        assertEquals(1, h.counted().size());
        assertEquals(0L, h.net());
    }

    @Test
    public void buyPartialFillsBookEachFill()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        h.offer(1, BUYING, LOGS, 10, 0, 48, 0);
        ProfitTransaction first = h.offer(1, BUYING, LOGS, 10, 4, 48, 192);
        ProfitTransaction second = h.offer(1, BOUGHT, LOGS, 10, 10, 48, 480);
        assertEquals(192L, flow(first, LOGS));
        assertEquals(-192L, flow(first, COINS));
        assertEquals(288L, flow(second, LOGS));
        assertEquals(-288L, flow(second, COINS));
        assertEquals(2, h.counted().size());
    }

    @Test
    public void sellFullFillWithNetCoinsBooksNoTaxRow()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        h.engine.setGeSellSpentIsNet(true);
        h.offer(2, SELLING, SHARK, 10, 0, 800, 0);
        ProfitTransaction booked = h.offer(2, SOLD, SHARK, 10, 10, 800, 7_840);
        assertEquals(-8_000L, flow(booked, SHARK));
        assertEquals(7_840L, flow(booked, COINS));
        assertEquals(0L, flow(booked, GeSellTaxBooking.TAX_ITEM_ID));
        assertEquals(-160L, h.net());
    }

    @Test
    public void sellWithGrossCoinsBooksTheTaxAsItsOwnRow()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        h.engine.setGeSellSpentIsNet(false);
        h.offer(2, SELLING, SHARK, 10, 0, 800, 0);
        ProfitTransaction booked = h.offer(2, SOLD, SHARK, 10, 10, 800, 8_000);
        assertEquals(-8_000L, flow(booked, SHARK));
        assertEquals(8_000L, flow(booked, COINS));
        assertEquals(-160L, flow(booked, GeSellTaxBooking.TAX_ITEM_ID));
        assertEquals(-160L, h.net());
    }

    @Test
    public void sellPartialFillsBookEachFill()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        h.offer(3, SELLING, LOGS, 10, 0, 48, 0);
        ProfitTransaction first = h.offer(3, SELLING, LOGS, 10, 3, 48, 144);
        ProfitTransaction second = h.offer(3, SOLD, LOGS, 10, 10, 48, 480);
        assertEquals(-144L, flow(first, LOGS));
        assertEquals(144L, flow(first, COINS));
        assertEquals(-336L, flow(second, LOGS));
        assertEquals(336L, flow(second, COINS));
    }

    @Test
    public void cancelWithCoinRefundBooksNothingAndTheRefundIsNeutral()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        // Placing the buy moves the coins out of the inventory: neutral in this mode.
        ProfitTransaction placed = h.geInventory(with(h.inventory, COINS, 99_520L));
        assertFalse(placed.isCounted());
        h.offer(4, BUYING, LOGS, 10, 0, 48, 0);
        assertNull(h.offer(4, CANCELLED_BUY, LOGS, 10, 0, 48, 0));
        ProfitTransaction refund = h.geInventory(with(h.inventory, COINS, 100_000L));
        assertFalse(refund.isCounted());
        assertTrue(h.counted().isEmpty());
        assertEquals(0L, h.net());
    }

    @Test
    public void cancelWithItemReturnBooksNothing()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        h.inventory = with(h.inventory, LOGS, 10L);
        h.engine.setBaseline(new ContainerSnapshot(h.inventory));
        h.geInventory(with(h.inventory, LOGS, 0L)); // items into the offer
        h.offer(5, SELLING, LOGS, 10, 0, 48, 0);
        assertNull(h.offer(5, CANCELLED_SELL, LOGS, 10, 0, 48, 0));
        ProfitTransaction back = h.geInventory(with(h.inventory, LOGS, 10L));
        assertFalse(back.isCounted());
        assertTrue(h.counted().isEmpty());
    }

    @Test
    public void collectToBankStillSettlesFromTheOffer()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        h.offer(0, BUYING, SHARK, 5, 0, 800, 0);
        ProfitTransaction booked = h.offer(0, BOUGHT, SHARK, 5, 5, 800, 4_000);
        assertNotNull(booked);
        // No inventory change at all: the bank took the sharks; the trade is already on the books.
        assertEquals(1, h.counted().size());
        assertEquals(0L, h.net());
        // A later bank withdrawal is a bank transfer, not a gain.
        h.now += 600L;
        h.engine.markContext(TrackingContext.TRANSFER, 10, "Bank transfer");
        h.inventory = with(h.inventory, SHARK, 5L);
        h.engine.markInventoryDirty();
        h.engine.processIfDirty(new ContainerSnapshot(h.inventory), h.now);
        ProfitTransaction withdrawal = h.engine.processIfDirty(new ContainerSnapshot(h.inventory), h.now + 600L);
        assertTrue(withdrawal == null || !withdrawal.isCounted());
        assertEquals(1, h.counted().size());
    }

    @Test
    public void relogWithTheOfferOpenSettlesOnlyWhatFilledWhileAway()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        h.offer(0, BUYING, LOGS, 10, 4, 48, 192);
        assertEquals(1, h.counted().size());
        // Logout, login: the API replays every slot as EMPTY, then the restored offers.
        h.ledger.beginLoginSeed();
        for (int slot = 0; slot < 8; slot++)
        {
            assertFalse(h.ledger.observe(new GeOfferLedger.Snapshot(slot, EMPTY, 0, 0, 0, 0, 0)).isPresent());
        }
        assertFalse(h.ledger.observe(new GeOfferLedger.Snapshot(0, BUYING, LOGS, 10, 7, 48, 336)).isPresent());
        h.ledger.finishLoginSeed();
        List<GeOfferLedger.Transition> resumed = h.ledger.takeResumedProgress();
        assertEquals(1, resumed.size());
        ProfitTransaction away = h.handle(resumed.get(0));
        assertNotNull(away);
        assertEquals(144L, flow(away, LOGS)); // 3 more, not 7
        assertTrue(h.ledger.takeResumedProgress().isEmpty());
        // And the rest fills live.
        ProfitTransaction rest = h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480);
        assertEquals(144L, flow(rest, LOGS));
        assertEquals(3, h.counted().size());
        assertEquals(0L, h.net());
    }

    @Test
    public void relogAfterCompletionSettlesTheRemainderOnce()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        h.offer(0, SELLING, LOGS, 10, 0, 48, 0);
        h.ledger.beginLoginSeed();
        for (int slot = 0; slot < 8; slot++) h.ledger.observe(new GeOfferLedger.Snapshot(slot, EMPTY, 0, 0, 0, 0, 0));
        h.ledger.observe(new GeOfferLedger.Snapshot(0, SOLD, LOGS, 10, 10, 48, 480));
        h.ledger.finishLoginSeed();
        List<GeOfferLedger.Transition> resumed = h.ledger.takeResumedProgress();
        assertEquals(1, resumed.size());
        ProfitTransaction away = h.handle(resumed.get(0));
        assertEquals(-480L, flow(away, LOGS));
        assertEquals(480L, flow(away, COINS));
        // Nothing new happens on the slot after the seed: no second booking.
        assertNull(h.offer(0, SOLD, LOGS, 10, 10, 48, 480));
        assertEquals(1, h.counted().size());
    }

    @Test
    public void aSlotThisProcessNeverSawIsOnlyABaseline()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        h.ledger.beginLoginSeed();
        for (int slot = 0; slot < 8; slot++) h.ledger.observe(new GeOfferLedger.Snapshot(slot, EMPTY, 0, 0, 0, 0, 0));
        h.ledger.observe(new GeOfferLedger.Snapshot(1, BOUGHT, LOGS, 10, 10, 48, 480));
        h.ledger.finishLoginSeed();
        assertTrue("no pre-logout snapshot: the completed offer cannot be dated, so it is not booked",
            h.ledger.takeResumedProgress().isEmpty());
        assertTrue(h.counted().isEmpty());
    }

    @Test
    public void twoOffersForTheSameItemSettleBySlot()
    {
        Harness h = new Harness(GeBookingMode.OBSERVED);
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        h.offer(1, BUYING, LOGS, 20, 0, 47, 0);
        ProfitTransaction a = h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480);
        ProfitTransaction b = h.offer(1, BOUGHT, LOGS, 20, 20, 47, 940);
        assertEquals(0, a.getGeOfferProvenance().getSlot());
        assertEquals(1, b.getGeOfferProvenance().getSlot());
        assertEquals(-480L, flow(a, COINS));
        assertEquals(-940L, flow(b, COINS));
        assertEquals(2, h.counted().size());
    }

    // ── provenance-only mode: observations never book; inventory settles as before ────────

    @Test
    public void provenanceOnlyNeverBooksFromObservations()
    {
        Harness h = new Harness(GeBookingMode.PROVENANCE_ONLY);
        assertNull(h.offer(0, BUYING, LOGS, 10, 0, 48, 0));
        assertNull(h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480));
        assertEquals(1, h.provenance.size());
        assertTrue(h.counted().isEmpty());
        // The inventory settlement books the trade and picks up the unique observation as provenance.
        ProfitTransaction collect = h.geInventory(with(h.inventory, LOGS, 10L));
        assertNotNull(collect);
        assertEquals(TransactionType.TRADE, collect.getType());
        assertTrue(collect.isCounted());
        GeOfferProvenanceMatcher.Observation match = GeOfferProvenanceMatcher.uniqueMatch(collect, h.provenance, h.now, 60_000L).orElse(null);
        assertNotNull(match);
        assertEquals(10, match.getTransition().getQuantityTradedDelta());
    }

    @Test
    public void provenanceOnlyTwoMatchingObservationsAttachNothing()
    {
        Harness h = new Harness(GeBookingMode.PROVENANCE_ONLY);
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        h.offer(1, BUYING, LOGS, 10, 0, 48, 0);
        h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480);
        h.offer(1, BOUGHT, LOGS, 10, 10, 48, 480);
        ProfitTransaction collect = h.geInventory(with(h.inventory, LOGS, 20L));
        assertTrue(collect.isCounted());
        assertFalse(GeOfferProvenanceMatcher.uniqueMatch(collect, h.provenance, h.now, 60_000L).isPresent());
    }

    /** Property: in PROVENANCE_ONLY, feeding or withholding the observations leaves every settlement identical. */
    @Test
    public void provenanceOnlySettlementsAreIndependentOfObservations()
    {
        for (int seed = 0; seed < 40; seed++)
        {
            Random random = new Random(seed);
            List<Object[]> script = new ArrayList<>();
            int steps = 3 + random.nextInt(8);
            for (int i = 0; i < steps; i++)
            {
                int kind = random.nextInt(3);
                int item = random.nextBoolean() ? LOGS : SHARK;
                int qty = 1 + random.nextInt(10);
                script.add(new Object[]{kind, item, qty});
            }
            List<String> withObservations = run(script, true);
            List<String> without = run(script, false);
            assertEquals("seed " + seed, without, withObservations);
        }
    }

    private static List<String> run(List<Object[]> script, boolean observe)
    {
        Harness h = new Harness(GeBookingMode.PROVENANCE_ONLY);
        int slot = 0;
        for (Object[] step : script)
        {
            int kind = (Integer) step[0];
            int item = (Integer) step[1];
            int qty = (Integer) step[2];
            int price = price(item);
            if (kind == 0)
            {
                // Buy: coins out on placement, items in on collect.
                if (observe) h.offer(slot, BUYING, item, qty, 0, price, 0);
                h.geInventory(with(h.inventory, COINS, h.inventory.get(COINS) - (long) qty * price));
                if (observe) h.offer(slot, BOUGHT, item, qty, qty, price, qty * price);
                h.geInventory(with(h.inventory, item, h.inventory.getOrDefault(item, 0L) + qty));
            }
            else if (kind == 1)
            {
                // Sell what we have (or nothing).
                long have = h.inventory.getOrDefault(item, 0L);
                if (have <= 0L) continue;
                long selling = Math.min(have, qty);
                if (observe) h.offer(slot, SELLING, item, (int) selling, 0, price, 0);
                h.geInventory(with(h.inventory, item, have - selling));
                if (observe) h.offer(slot, SOLD, item, (int) selling, (int) selling, price, (int) (selling * price));
                h.geInventory(with(h.inventory, COINS, h.inventory.get(COINS) + selling * price));
            }
            else
            {
                if (observe) h.offer(slot, BUYING, item, qty, 0, price, 0);
                if (observe) h.offer(slot, CANCELLED_BUY, item, qty, 0, price, 0);
            }
            slot = (slot + 1) % 8;
        }
        List<String> out = new ArrayList<>();
        for (ProfitTransaction t : h.engine.getActiveSession().getTransactions())
        {
            if (t == null) continue;
            out.add(t.getType() + "|" + t.isCounted() + "|" + t.getNet());
        }
        out.add("net=" + h.net());
        return out;
    }

    private static GpManagerEngine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public int minimumTransactionValue() { return 1; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new GpManagerEngine(deltas ->
        {
            List<ItemFlow> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                flows.add(new ItemFlow(id, name(id), delta.getValue(), price(id), delta.getValue() * price(id),
                    id == COINS ? ItemPriceSource.FACE_VALUE : ItemPriceSource.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
