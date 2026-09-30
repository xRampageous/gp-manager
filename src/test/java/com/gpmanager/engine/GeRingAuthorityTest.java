package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.BOUGHT;
import static net.runelite.api.GrandExchangeOfferState.BUYING;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2C.6A GE authority: custody is authoritative for GE economics. Ring-explained movements
 * are ancillary evidence; when no claimable custody lifecycle exists they fail closed to an
 * uncounted review row instead of booking a quote-valued cost or automatic proceeds.
 */
public class GeRingAuthorityTest
{
    private static final int LOGS = ItemID.LOGS;
    private static final int RUNE = ItemID.NATURERUNE;
    private static final int COINS = ItemID.COINS;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void fastFillBuyReserveStaysCustodyNotReview()
    {
        Harness h = new Harness(140);
        // The offer fills before the reserve settle is processed. Owner report 2026-09-28: that coin
        // loss is still this offer's reserve, a neutral custody move, never a Review row.
        h.offer(0, BUYING, LOGS, 5, 0, 140, 0);
        h.offer(0, BOUGHT, LOGS, 5, 5, 140, 700);
        h.settle(with(h.inventory, COINS, 1_000_000L - 700L));

        assertEquals("the reserve never changes Net", 0L, h.net());
        for (Ac transaction : h.transactions())
        {
            assertFalse("no Review row", Eh.aal(transaction));
            assertFalse("the reserve is uncounted", transaction.isCounted());
        }

        h.settle(with(h.inventory, COINS, 1_000_000L - 700L, LOGS, 5L));

        assertEquals("authoritative NEW BUY is Net-neutral", 0L, h.net());
        assertEquals("exact spend becomes known coverage", 5L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(700L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        List<Ac> counted = h.counted();
        assertEquals("only the authoritative settlement is counted", 1, counted.size());
        assertEquals(Ai.TRADE, counted.get(0).getType());
        assertEquals("no duplicate spend", 0L, counted.get(0).getNet());
    }

    @Test
    public void unclaimedSellCashFailsClosedToReview()
    {
        Harness h = new Harness(7);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        // The player modifies the offer quantity: no exact realization is provable.
        h.offer(0, SELLING, RUNE, 20, 0, 7, 0);
        h.offer(0, SOLD, RUNE, 20, 10, 7, 70);
        h.settle(with(h.inventory, COINS, 1_000_070L));

        assertEquals("unclaimed cash is never automatic profit", 0L, h.net());
        assertFalse("no basis is mutated by the unclaimed movement", h.hasCountedCoinsOnlyRow());
        assertTrue("the movement is recorded for review", h.reviews() >= 1);
        assertEquals(0L, EngineProbe.knownCoverageQty(h.engine, RUNE));
    }

    @Test
    public void explicitGeBuyClickCannotCountUnclaimedCoinLoss()
    {
        Harness h = new Harness(7);
        h.engine.markContext(Aj.MARKET, 10, "Grand Exchange buy");
        h.settle(with(h.inventory, COINS, 999_990L));

        assertEquals(0L, h.net());
        assertFalse(h.hasCountedCoinsOnlyRow());
        assertEquals(1, h.reviews());
        assertEquals(Ai.UNCERTAIN, h.transactions().get(0).getType());
    }

    @Test
    public void explicitGeCollectCannotCountUnclaimedCashOrItem()
    {
        Harness h = new Harness(7);
        h.engine.markContext(Aj.MARKET, 10, "Grand Exchange collect");
        h.settle(with(h.inventory, COINS, 1_000_010L, RUNE, 5L));

        assertEquals(0L, h.net());
        assertEquals(1, h.reviews());
        assertEquals(0L, EngineProbe.knownCoverageQty(h.engine, RUNE));
        assertTrue(h.counted().isEmpty());
    }

    @Test
    public void shopMarketContextStillCountsItsObservedSpend()
    {
        Harness h = new Harness(7);
        h.engine.markContext(Aj.MARKET, 10, "buy shop item");
        h.settle(with(h.inventory, COINS, 999_990L));

        assertEquals(-10L, h.net());
        assertEquals(Ai.TRADE, h.counted().get(0).getType());
    }

    @Test
    public void custodyBackedSellIsTheSoleRealization()
    {
        Harness h = new Harness(159);
        h.gain(LOGS, 5L, 775L, h.now - 60_000L);

        h.inventory = with(h.inventory, LOGS, 5L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.offer(0, SELLING, LOGS, 5, 0, 159, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        h.offer(0, SOLD, LOGS, 5, 5, 159, 790);
        h.settle(with(h.inventory, COINS, 1_000_790L));

        assertEquals("the earlier counted value plus the sale result", 790L, h.net());
        List<Ac> counted = h.counted();
        assertEquals("the gain plus exactly one custody settlement", 2, counted.size());
        Ac settlement = counted.get(1);
        assertEquals(Ai.TRADE, settlement.getType());
        assertEquals("custody books the sale exactly once", 15L, settlement.getNet());
        assertEquals("known coverage is consumed by the reservation", 0L,
            EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertFalse(h.hasCountedCoinsOnlyRow());
    }

    // ---- harness ----------------------------------------------------------------------------

    private static final class Harness
    {
        final int[] quote;
        final Am engine;
        final Bj ledger = new Bj();
        long now = T0;
        Map<Integer, Long> inventory = new HashMap<>();

        Harness(int initialQuote)
        {
            quote = new int[] { initialQuote };
            engine = engine(quote);
            engine.ajl("Trading", Cx.AUTO, now);
            inventory.put(COINS, 1_000_000L);
            engine.setBaseline(new Cc(inventory));
        }

        void gain(int item, long qty, long value, long at)
        {
            engine.getActiveSession().kf(new Ac(at, null,
                Ai.GAIN, Aj.GENERIC, "", "Loot", true,
                Collections.singletonList(new Ab(item, name(item), qty,
                    (int) (qty > 0L ? value / qty : 0L), value, Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null), 500);
        }

        void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent)
        {
            now += 600L;
            Bj.Transition transition = ledger.observe(
                new Bj.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.abh(transition, name(transition.current.itemId), now);
            }
        }

        Ac settle(Map<Integer, Long> next)
        {
            now += 600L;
            inventory = new HashMap<>(next);
            engine.yz();
            Cc snapshot = new Cc(inventory);
            Ac result = null;
            for (int i = 0; i < 3; i++)
            {
                Ac settled = engine.adj(snapshot, now);
                if (settled != null)
                {
                    result = settled;
                }
                now += 600L;
            }
            return result;
        }

        long net()
        {
            return engine.getMetrics(now).net;
        }

        List<Ac> transactions()
        {
            List<Ac> out = new ArrayList<>();
            for (Ac transaction : engine.getActiveSession().getTransactions())
            {
                if (transaction != null)
                {
                    out.add(transaction);
                }
            }
            return out;
        }

        List<Ac> counted()
        {
            List<Ac> out = new ArrayList<>();
            for (Ac transaction : transactions())
            {
                if (transaction.isCounted())
                {
                    out.add(transaction);
                }
            }
            return out;
        }

        int reviews()
        {
            int reviews = 0;
            for (Ac transaction : transactions())
            {
                if (Eh.aal(transaction))
                {
                    reviews++;
                }
            }
            return reviews;
        }

        boolean hasCountedCoinsOnlyRow()
        {
            for (Ac transaction : counted())
            {
                boolean coinsOnly = !transaction.getFlows().isEmpty();
                for (Ab flow : transaction.getFlows())
                {
                    coinsOnly &= flow.itemId == COINS;
                }
                if (coinsOnly)
                {
                    return true;
                }
            }
            return false;
        }
    }

    private static String name(int id)
    {
        return id == COINS ? "Coins" : id == LOGS ? "Logs" : id == RUNE ? "Nature rune" : "Item " + id;
    }

    private static Map<Integer, Long> with(Map<Integer, Long> base, Object... pairs)
    {
        Map<Integer, Long> map = new HashMap<>(base);
        for (int i = 0; i < pairs.length; i += 2)
        {
            long quantity = (Long) pairs[i + 1];
            if (quantity <= 0L)
            {
                map.remove((Integer) pairs[i]);
            }
            else
            {
                map.put((Integer) pairs[i], quantity);
            }
        }
        return map;
    }

    private static Am engine(int[] quote)
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
                int unit = id == COINS ? 1 : quote[0];
                Av source = id == COINS ? Av.FACE_VALUE
                    : unit > 0 ? Av.GRAND_EXCHANGE : Av.UNPRICED;
                flows.add(new Ab(id, name(id), delta.getValue(), unit, delta.getValue() * unit,
                    source));
            }
            return flows;
        }, new TransactionClassifier(), config);
    }
}
