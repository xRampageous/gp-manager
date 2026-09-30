package com.gpmanager;

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
 * Charter Q offline matrix: offer transitions (no client) establish fill progress and MARKET
 * evidence only; money is booked exactly once from the settled inventory movement. Raw
 * {@code getSpent()} is never interpreted, so a fill whose coins never reach the inventory
 * stays unbooked rather than guessed. Live buy/sell captures remain owner-gated.
 *
 * <p>This harness deliberately arms MARKET around its inventory helpers to test canonical GE
 * accounting <em>given confirmed ownership</em>. It is not proof that production establishes
 * that ownership: the production evidence path (GE UI interaction plus the item/direction-scoped
 * offer ring, with no artificial context) is proven in {@link GeSellClassificationTest} and
 * {@code GpManagerPluginGeEvidenceTest}.</p>
 */
public class GeReconciliationEngineTest
{
    private static final int LOGS = 1519;
    private static final int SHARK = 385;
    private static final int COINS = 995;
    private static final long T0 = 1_000_000_000_000L;

    /** One offline accounting run: engine, ledger, and an explicit MARKET arm for confirmed ownership. */
    private static final class Harness
    {
        final Am engine = engine();
        final Bj ledger = new Bj();
        long now = T0;
        Map<Integer, Long> inventory = new HashMap<>();

        Harness()
        {
            engine.ajl("Trading", Cx.AUTO, now);
            inventory.put(COINS, 100_000L);
            engine.setBaseline(new Cc(inventory));
        }

        /** Observe, arm MARKET as confirmed-ownership scaffolding, remember any fill as evidence. */
        Bj.Transition offer(int slot, GrandExchangeOfferState state, int item, int total, int traded, int price, int spent)
        {
            now += 600L;
            Bj.Transition t = ledger.observe(new Bj.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            return handle(t);
        }

        Bj.Transition handle(Bj.Transition t)
        {
            if (t == null) return null;
            engine.abh(t, name(t.current.itemId), now);
            // Test scaffolding only: production arms MARKET from proven GE interactions, never
            // from passive offer transitions (see GpManagerPluginGeEvidenceTest).
            engine.markContext(Aj.MARKET, 10, "Grand Exchange offer");
            return t;
        }

        /** An inventory change with confirmed MARKET ownership injected by the harness. */
        Ac geInventory(Map<Integer, Long> next)
        {
            now += 600L;
            engine.markContext(Aj.MARKET, 10, "collect");
            inventory = new HashMap<>(next);
            engine.yz();
            Ac first = engine.adj(new Cc(inventory), now);
            Ac settled = engine.adj(new Cc(inventory), now + 600L);
            now += 600L;
            return settled == null ? first : settled;
        }

        List<Ac> counted()
        {
            List<Ac> out = new ArrayList<>();
            for (Ac t : engine.getActiveSession().getTransactions())
            {
                if (t != null && t.isCounted()) out.add(t);
            }
            return out;
        }

        long net()
        {
            return engine.getMetrics(now).net;
        }

        /** Fill progress custody holds as evidence; the engine never books it by itself. */
        long filled()
        {
            long total = 0L;
            for (Aa record : engine.geCustody.aji()) total += record.getFilledQty();
            return total;
        }

        Aa record(int itemId)
        {
            for (Aa record : engine.geCustody.aji())
            {
                if (record.itemId == itemId) return record;
            }
            return null;
        }

        /** The plugin's login replay: seed the offer ledger, then the custody baseline. */
        void relog(Bj.Snapshot... restored)
        {
            ledger.lu();
            for (int slot = 0; slot < 8; slot++)
            {
                assertFalse(ledger.observe(new Bj.Snapshot(slot, EMPTY, 0, 0, 0, 0, 0)).isPresent());
            }
            for (Bj.Snapshot snapshot : restored)
            {
                assertFalse("replay seeds the baseline only", ledger.observe(snapshot).isPresent());
            }
            ledger.tg();
            now += 600L;
            engine.ahy(ledger.snapshots(), now);
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

    private static long flow(Ac t, int itemId)
    {
        for (Ab f : t.getFlows()) if (f.itemId == itemId) return f.valueDelta;
        return 0L;
    }

    // ── placement, fills and collection: evidence from the slot, money from the inventory ──

    @Test
    public void placementAloneBooksNothing()
    {
        Harness h = new Harness();
        assertNotNull(h.offer(0, BUYING, LOGS, 10, 0, 48, 0));
        assertEquals(0L, h.filled());
        assertTrue(h.counted().isEmpty());
        assertEquals(0L, h.net());
    }

    @Test
    public void buyFullFillIsEvidenceOnlyAndTheCollectionBooksOnce()
    {
        Harness h = new Harness();
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        assertNotNull(h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480));
        assertEquals("the fill is remembered, never booked from getSpent()", 10L, h.filled());
        assertTrue(h.counted().isEmpty());

        Ac collect = h.geInventory(with(h.inventory, LOGS, 10L));
        assertNotNull(collect);
        assertEquals(Ai.TRADE, collect.getType());
        assertTrue(collect.isCounted());
        assertEquals(480L, flow(collect, LOGS));
        assertFalse(new com.google.gson.Gson().toJson(collect).contains("geOfferProvenance"));
        assertEquals(1, h.counted().size());
        assertNoSyntheticTaxFlows(h);
    }

    @Test
    public void buyPartialFillsAreIdempotentFromCumulativeDeltas()
    {
        Harness h = new Harness();
        h.offer(1, BUYING, LOGS, 10, 0, 48, 0);
        h.offer(1, BUYING, LOGS, 10, 4, 48, 192);
        assertEquals(4L, h.filled());
        assertNull("an unchanged replay of the same progress is not a second fill", h.offer(1, BUYING, LOGS, 10, 4, 48, 192));
        h.offer(1, BOUGHT, LOGS, 10, 10, 48, 480);
        assertEquals(10L, h.filled());
        assertTrue("fills alone never reach the books", h.counted().isEmpty());
    }

    @Test
    public void sellFillCoinsAreBookedOnlyWhenTheyReachTheInventory()
    {
        Harness h = new Harness();
        h.inventory = with(h.inventory, SHARK, 10L);
        h.engine.setBaseline(new Cc(h.inventory));
        Ac placed = h.geInventory(with(h.inventory, SHARK, 0L));
        assertEquals(Ai.TRADE, placed.getType());
        assertEquals(-8_000L, flow(placed, SHARK));
        h.offer(2, SELLING, SHARK, 10, 0, 800, 0);
        h.offer(2, SOLD, SHARK, 10, 10, 800, 7_840);
        assertEquals("the reported coins are an observation, not proceeds", 1, h.counted().size());
        assertEquals(-8_000L, h.net());

        // Collect: the coins that actually arrive are the executed sell value; the observed
        // shortfall against the counted item loss is the realized result, booked exactly once.
        Ac collect = h.geInventory(with(h.inventory, COINS, 107_840L));
        assertNotNull(collect);
        assertTrue(collect.isCounted());
        assertEquals(7_840L, flow(collect, COINS));
        assertEquals(-160L, h.net());
        assertEquals(2, h.counted().size());
        assertNoSyntheticTaxFlows(h);
    }

    /** Band 3 C2: settlement is authoritative, so no synthetic tax cost may be reconstructed. */
    private static void assertNoSyntheticTaxFlows(Harness h)
    {
        for (Ac transaction : h.counted())
        {
            for (Ab itemFlow : transaction.getFlows())
            {
                assertTrue("no synthetic GE tax flow may be booked",
                    itemFlow.itemId != CostKind.GE_TAX_ITEM_ID);
            }
        }
    }

    @Test
    public void sellPartialFillsBookOnceOnCollect()
    {
        Harness h = new Harness();
        h.inventory = with(h.inventory, LOGS, 10L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.geInventory(with(h.inventory, LOGS, 0L));
        h.offer(3, SELLING, LOGS, 10, 0, 48, 0);
        h.offer(3, SELLING, LOGS, 10, 3, 48, 144);
        h.offer(3, SOLD, LOGS, 10, 10, 48, 480);
        assertEquals(10L, h.filled());
        assertEquals(1, h.counted().size());
        Ac collect = h.geInventory(with(h.inventory, COINS, 100_480L));
        assertTrue(collect.isCounted());
        assertEquals(480L, flow(collect, COINS));
        assertEquals("below the tax floor nothing is guessed", 0L, h.net());
        assertEquals(2, h.counted().size());
    }

    @Test
    public void cancelWithCoinRefundIsNeutralEndToEnd()
    {
        Harness h = new Harness();
        Ac placed = h.geInventory(with(h.inventory, COINS, 99_520L));
        assertTrue("coins into a buy offer are a counted trade until refunded", placed.isCounted());
        h.offer(4, BUYING, LOGS, 10, 0, 48, 0);
        Bj.Transition cancel = h.offer(4, CANCELLED_BUY, LOGS, 10, 0, 48, 0);
        assertNotNull(cancel);
        assertEquals(0L, h.filled());
        Ac refund = h.geInventory(with(h.inventory, COINS, 100_000L));
        assertTrue(refund.isCounted());
        assertEquals("placement and refund cancel out; nothing is double booked", 0L, h.net());
    }

    @Test
    public void cancelWithItemReturnIsNeutralEndToEnd()
    {
        Harness h = new Harness();
        h.inventory = with(h.inventory, LOGS, 10L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.geInventory(with(h.inventory, LOGS, 0L));
        h.offer(5, SELLING, LOGS, 10, 0, 48, 0);
        h.offer(5, CANCELLED_SELL, LOGS, 10, 0, 48, 0);
        assertEquals(0L, h.filled());
        h.geInventory(with(h.inventory, LOGS, 10L));
        assertEquals(0L, h.net());
    }

    @Test
    public void collectToBankWithoutAnInventoryMovementStaysUnbooked()
    {
        Harness h = new Harness();
        h.offer(0, BUYING, SHARK, 5, 0, 800, 0);
        h.offer(0, BOUGHT, SHARK, 5, 5, 800, 4_000);
        assertEquals(5L, h.filled());
        // Quantity progress is known but the money never crossed the inventory: fail closed, no guess.
        assertTrue(h.counted().isEmpty());
        assertEquals(0L, h.net());
        h.now += 600L;
        h.engine.markContext(Aj.TRANSFER, 10, "Bank transfer");
        h.inventory = with(h.inventory, SHARK, 5L);
        h.engine.yz();
        h.engine.adj(new Cc(h.inventory), h.now);
        Ac withdrawal = h.engine.adj(new Cc(h.inventory), h.now + 600L);
        assertTrue("a later bank withdrawal is a transfer, not a gain", withdrawal == null || !withdrawal.isCounted());
        assertTrue(h.counted().isEmpty());
    }

    // ── login / relogin replay ──────────────────────────────────────────────────────────

    @Test
    public void relogWithTheOfferOpenReportsOnlyWhatFilledWhileAwayOnce()
    {
        Harness h = new Harness();
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        h.offer(0, BUYING, LOGS, 10, 4, 48, 192);
        assertEquals(4L, h.filled());
        h.relog(new Bj.Snapshot(0, BUYING, LOGS, 10, 7, 48, 336));
        assertEquals("the lifecycle resumes with what filled while away, once", 7L, h.filled());
        h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480);
        assertEquals("3 more after the seed, not 7", 10L, h.filled());
        assertTrue("no fill ever booked money without an inventory movement", h.counted().isEmpty());
    }

    @Test
    public void relogAfterCompletionReportsTheRemainderOnce()
    {
        Harness h = new Harness();
        h.offer(0, SELLING, LOGS, 10, 0, 48, 0);
        h.relog(new Bj.Snapshot(0, SOLD, LOGS, 10, 10, 48, 480));
        assertEquals(10L, h.filled());
        assertEquals("SOLD", h.record(LOGS).getOfferState());
        assertNull("nothing new happens on the slot after the seed", h.offer(0, SOLD, LOGS, 10, 10, 48, 480));
        assertEquals(10L, h.filled());
        assertTrue(h.counted().isEmpty());
    }

    @Test
    public void aSlotThisProcessNeverSawIsOnlyABaseline()
    {
        Harness h = new Harness();
        h.relog(new Bj.Snapshot(1, BOUGHT, LOGS, 10, 10, 48, 480));
        assertEquals("no pre-logout snapshot: the completed offer cannot be dated",
            Aa.Confidence.LEGACY_UNBASED, h.record(LOGS).getConfidence());
        assertTrue(h.counted().isEmpty());
    }

    @Test
    public void impossibleOrBackwardsTransitionsAreNotFills()
    {
        Harness h = new Harness();
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        h.offer(0, BUYING, LOGS, 10, 6, 48, 288);
        h.offer(0, BUYING, LOGS, 10, 2, 48, 96);
        assertEquals("backwards counters are not a fill", 6L, h.record(LOGS).getFilledQty());
        h.offer(0, BUYING, SHARK, 10, 8, 800, 6_400);
        assertNull("a different item in the same slot retires the old offer", h.record(LOGS));
        assertEquals("and starts a quarantined one, not progress",
            Aa.Confidence.AMBIGUOUS, h.record(SHARK).getConfidence());
        assertTrue(h.counted().isEmpty());
    }

    @Test
    public void twoOffersForTheSameItemSettleOnceFromTheInventory()
    {
        Harness h = new Harness();
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        h.offer(1, BUYING, LOGS, 10, 0, 48, 0);
        h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480);
        h.offer(1, BOUGHT, LOGS, 10, 10, 48, 480);
        assertEquals(20L, h.filled());
        h.geInventory(with(h.inventory, LOGS, 20L));
        List<Ac> counted = h.counted();
        assertEquals("each offer settles its own acquisition exactly once", 2, counted.size());
        long total = 0L;
        for (Ac transaction : counted)
        {
            total += flow(transaction, LOGS);
        }
        assertEquals(960L, total);
        assertEquals(0L, h.net());
    }

    /**
     * Property: with or without offer observations, the canonical Net of every script stays exact.
     * Option 2 changes classification (custody is neutral), never the economic total, because this
     * harness values every movement at one fixed quote and books execution at the same price.
     */
    @Test
    public void observationsNeverChangeTheCanonicalNet()
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
            long withObservations = runNet(script, true);
            long without = runNet(script, false);
            assertEquals("seed " + seed, without, withObservations);
        }
    }

    private static long runNet(List<Object[]> script, boolean observe)
    {
        Harness h = new Harness();
        int slot = 0;
        for (Object[] step : script)
        {
            int kind = (Integer) step[0];
            int item = (Integer) step[1];
            int qty = (Integer) step[2];
            int price = price(item);
            if (kind == 0)
            {
                if (observe) h.offer(slot, BUYING, item, qty, 0, price, 0);
                h.geInventory(with(h.inventory, COINS, h.inventory.get(COINS) - (long) qty * price));
                if (observe) h.offer(slot, BOUGHT, item, qty, qty, price, qty * price);
                h.geInventory(with(h.inventory, item, h.inventory.getOrDefault(item, 0L) + qty));
            }
            else if (kind == 1)
            {
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
        return h.net();
    }

    private static Am engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
            @Override public boolean keepTransferAuditRows() { return true; }
        };
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            for (Map.Entry<Integer, Long> delta : deltas.entrySet())
            {
                int id = delta.getKey();
                flows.add(new Ab(id, name(id), delta.getValue(), price(id), delta.getValue() * price(id),
                    id == COINS ? Av.FACE_VALUE : Av.GRAND_EXCHANGE));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
