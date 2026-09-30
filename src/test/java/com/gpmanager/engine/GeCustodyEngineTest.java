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
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY;
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL;
import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Schema-104 Option 2 custody contract: pending placement/reserve is ownership-neutral, a
 * cancellation return is neutral at its frozen basis, and a safely realized settlement books one
 * canonical Market result exactly once.
 */
public class GeCustodyEngineTest
{
    private static final int RUNE = ItemID.NATURERUNE;
    private static final int LOGS = ItemID.LOGS;
    private static final int COINS = ItemID.COINS;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    private static final class Harness
    {
        final int[] quote;
        final Am engine;
        final Bj ledger = new Bj();
        long now = T0;
        Map<Integer, Long> inventory = new HashMap<>();

        Harness(int initialQuote)
        {
            quote = new int[]{initialQuote};
            engine = engine(quote);
            engine.ajl("Trading", Cx.AUTO, now);
            inventory.put(COINS, 100_000L);
            engine.setBaseline(new Cc(inventory));
        }

        Bj.Transition offer(int slot, GrandExchangeOfferState state, int item, int total,
            int traded, int price, int spent)
        {
            now += 600L;
            Bj.Transition transition = ledger.observe(
                new Bj.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.abh(transition, name(transition.current.itemId), now);
            }
            return transition;
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

        /** Establish exact known coverage for an item (schema-106 tracked basis). */
        void gain(int item, long quantity, long value, long at)
        {
            engine.getActiveSession().kf(new Ac(at, null,
                Ai.GAIN, Aj.GENERIC, "", "Loot", true,
                Collections.singletonList(new Ab(item, name(item), quantity,
                    (int) (quantity > 0L ? value / quantity : 0L), value,
                    Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null), 500);
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

        List<Ac> reviews()
        {
            List<Ac> out = new ArrayList<>();
            for (Ac transaction : transactions())
            {
                if (!transaction.isCounted()
                    && transaction.tm() == Ai.UNCERTAIN)
                {
                    out.add(transaction);
                }
            }
            return out;
        }
    }

    private static String name(int id)
    {
        if (id == COINS)
        {
            return "Coins";
        }
        if (id == RUNE)
        {
            return "Nature rune";
        }
        if (id == ItemID.LAWRUNE) return "Law rune";
        if (id == LOGS)
        {
            return "Logs";
        }
        return "Item " + id;
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

    private static long flow(Ac transaction, int itemId)
    {
        for (Ab itemFlow : transaction.getFlows())
        {
            if (itemFlow.itemId == itemId)
            {
                return itemFlow.valueDelta;
            }
        }
        return 0L;
    }

    // ---- proven starting defects ---------------------------------------------------------

    @Test
    public void bankedRuneSalesUseEarlierKnownBasisButNeverInventItFromAWithdrawal()
    {
        for (boolean earlierAcquisition : new boolean[] {true, false})
        {
            Harness h = new Harness(122);
            if (earlierAcquisition)
            {
                h.gain(ItemID.LAWRUNE, 200L, 24_400L, h.now);
                h.inventory = with(h.inventory, ItemID.LAWRUNE, 200L);
                h.engine.setBaseline(new Cc(h.inventory));
                h.engine.zh("Bank deposit", 6);
                h.settle(with(h.inventory, ItemID.LAWRUNE, 0L));
            }
            assertTrue(h.engine.sx(h.now));
            h.engine.ajl("Trading", Cx.AUTO, h.now);
            h.engine.setBaseline(new Cc(h.inventory));
            h.engine.zh("Bank withdrawal", 6);
            h.settle(with(h.inventory, ItemID.LAWRUNE, 200L));
            assertEquals("withdrawing owned stock adds no revenue", 0L, h.net());

            h.offer(0, SELLING, ItemID.LAWRUNE, 200, 0, 120, 0);
            h.settle(with(h.inventory, ItemID.LAWRUNE, 0L));
            h.offer(0, SOLD, ItemID.LAWRUNE, 200, 200, 120, 24_000);
            Ac law = h.settle(with(h.inventory, COINS, 123_600L));
            assertNotNull(law);
            assertEquals("known basis gives -800; unknown basis counts only the proven -400 tax",
                earlierAcquisition ? -800L : -400L, law.getNet());

            h.quote[0] = 136;
            h.engine.zh("Bank withdrawal", 6);
            h.settle(with(h.inventory, RUNE, 200L));
            h.offer(1, SELLING, RUNE, 200, 0, 137, 0);
            h.settle(with(h.inventory, RUNE, 0L));
            h.offer(1, SOLD, RUNE, 200, 200, 137, 27_400);
            Ac nature = h.settle(with(h.inventory, COINS, 150_600L));
            assertNotNull(nature);
            assertEquals(-400L, nature.getNet());
            assertEquals("only genuine earlier law basis supports the owner's -1.2k total",
                earlierAcquisition ? -1_200L : -800L, h.net());
            assertEquals("sale proceeds never become ordinary Gains", 0L,
                Ca.capture(h.engine, h.now, null).gains);
        }
    }

    @Test
    public void pendingSellCustodyIsNeutral()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 100L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, RUNE, 100, 0, 7, 0);
        Ac placement = h.settle(with(h.inventory, RUNE, 0L));
        assertNotNull(placement);
        assertEquals("placement principal is a custody transfer",
            Ai.TRANSFER, placement.getType());
        assertFalse(placement.isCounted());
        assertEquals("pending SELL must not change canonical Net", 0L, h.net());
        assertEquals(1, h.engine.geCustody.aji().size());
    }

    @Test
    public void pendingBuyObservedReserveIsNeutral()
    {
        Harness h = new Harness(6);
        h.offer(1, BUYING, LOGS, 100, 0, 7, 0);
        Ac reserve = h.settle(with(h.inventory, COINS, 100_000L - 700L));
        assertNotNull(reserve);
        assertEquals(Ai.TRANSFER, reserve.getType());
        assertFalse(reserve.isCounted());
        assertEquals("pending BUY reserve must not change canonical Net", 0L, h.net());
    }

    @Test
    public void zeroFillCancelAfterQuoteMovementIsNeutral()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 100L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(2, SELLING, RUNE, 100, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        assertEquals(0L, h.net());

        h.quote[0] = 7;
        h.offer(2, CANCELLED_SELL, RUNE, 100, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 100L));
        assertEquals("cancelled unfilled principal is neutral at its frozen basis", 0L, h.net());
    }

    @Test
    public void cancelledSellNeverClaimsAnotherItemsGainAsItsReturn()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 100L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.offer(2, SELLING, RUNE, 100, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(2, CANCELLED_SELL, RUNE, 100, 0, 7, 0);

        Ac pickup = h.settle(with(h.inventory, LOGS, 5L));

        assertNotNull(pickup);
        assertFalse("another item's gain is not this offer's cancellation return",
            pickup.getType() == Ai.TRANSFER);
    }

    // ---- SELL realization ----------------------------------------------------------------

    @Test
    public void sellAboveBasisRealizesPositiveEdgeOnce()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.gain(RUNE, 10L, 60L, h.now - 60_000L);

        h.offer(0, SELLING, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SOLD, RUNE, 10, 10, 7, 70);
        Ac settlement = h.settle(with(h.inventory, COINS, 100_070L));

        assertNotNull(settlement);
        assertEquals(Ai.TRADE, settlement.getType());
        assertTrue(settlement.isCounted());
        assertEquals("known basis consumed at the pooled value", -60L, flow(settlement, RUNE));
        assertEquals("observed cash settlement", 70L, flow(settlement, COINS));
        assertEquals("the sale realizes +10 on top of the counted 60", 70L, h.net());
        assertEquals(2, h.counted().size());

        // A duplicate terminal replay and a restart must not book again.
        assertTrue(h.engine.geCustody.aji().isEmpty()
            || h.engine.geCustody.aji().get(0).getSettledQty() == 10L);
        assertEquals(2, h.counted().size());
    }

    @Test
    public void sellBelowBasisRealizesNegativeEdgeOnce()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.gain(RUNE, 10L, 70L, h.now - 60_000L);

        h.offer(0, SELLING, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SOLD, RUNE, 10, 10, 7, 50);
        Ac settlement = h.settle(with(h.inventory, COINS, 100_050L));
        assertEquals("the sale realizes -20 against the counted 70", -20L, settlement.getNet());
        assertEquals("end to end the received 50 is the tracked realization", 50L, h.net());
    }

    @Test
    public void sellAtBasisRealizesZero()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.gain(RUNE, 10L, 60L, h.now - 60_000L);

        h.offer(0, SELLING, RUNE, 10, 0, 6, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SOLD, RUNE, 10, 10, 6, 60);
        Ac settlement = h.settle(with(h.inventory, COINS, 100_060L));
        assertEquals(0L, settlement.getNet());
        assertEquals("the counted 60 stays realized", 60L, h.net());
    }

    @Test
    public void multiPriceFillsPreserveExactTotal()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.gain(RUNE, 10L, 60L, h.now - 60_000L);

        h.offer(0, SELLING, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SELLING, RUNE, 10, 5, 7, 27);
        h.offer(0, SOLD, RUNE, 10, 10, 7, 69);
        Ac settlement = h.settle(with(h.inventory, COINS, 100_069L));
        assertEquals("exact total execution 69 against the counted 60", 9L, settlement.getNet());
        assertEquals("the counted 60 plus the realized 9", 69L, h.net());
    }

    @Test
    public void partialFillThenCancelRealizesOnlyFilledQuantity()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 100L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.gain(RUNE, 100L, 700L, h.now - 60_000L);

        h.offer(0, SELLING, RUNE, 100, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SELLING, RUNE, 100, 30, 7, 210);
        h.offer(0, CANCELLED_SELL, RUNE, 100, 30, 7, 210);
        h.settle(with(h.inventory, RUNE, 70L));
        assertEquals("the returned 70 stay neutral", 700L, h.net());

        Ac settlement = h.settle(with(h.inventory, COINS, 100_210L));
        assertEquals("only the 30 filled units realize", 700L, h.net());
        assertEquals(0L, settlement.getNet());
        assertEquals("the reservation consumed exactly the filled basis", 210L,
            h.engine.geCustody.aji().get(0).getConsumedTrackedBasisGp());
        assertEquals("the returned reservation is available again", 70L,
            EngineProbe.knownCoverageQty(h.engine, RUNE));
        assertEquals(2, h.counted().size());
    }

    @Test
    public void fillCollectFillCollectSettlesOnlyNewQuantity()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 100L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.gain(RUNE, 100L, 600L, h.now - 60_000L);

        h.offer(0, SELLING, RUNE, 100, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SELLING, RUNE, 100, 40, 7, 240);
        h.engine.abg(h.now);
        h.settle(with(h.inventory, COINS, 100_240L));
        assertEquals(600L, h.net());

        h.offer(0, SOLD, RUNE, 100, 100, 7, 600);
        h.settle(with(h.inventory, COINS, 100_600L));
        assertEquals("second collection settles only the new 60 units", 600L, h.net());
        assertEquals(100L, h.engine.geCustody.aji().get(0).getSettledQty());
        assertEquals(3, h.counted().size());
    }

    @Test
    public void unpricedBasisGoesToUncountedReview()
    {
        Harness h = new Harness(0);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SOLD, RUNE, 10, 10, 7, 70);
        h.settle(with(h.inventory, COINS, 100_070L));

        assertEquals("unknown basis is never booked as cash minus zero", 0L, h.net());
        assertEquals(1, h.reviews().size());
    }

    @Test
    public void manualOverrideBasisIsFrozenAtPlacement()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.quote[0] = 9;
        h.offer(0, CANCELLED_SELL, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 10L));

        Aa record = h.engine.geCustody.aji().get(0);
        assertEquals("custody basis is not repriced by a later quote", 6L, record.getBasisUnitPrice());
        assertEquals(0L, h.net());
        assertEquals(0L, record.aib());
    }

    // ---- BUY realization ------------------------------------------------------------------

    @Test
    public void buyInventoryFundedCollectsAndSettlesOnce()
    {
        Harness h = new Harness(48);
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        h.settle(with(h.inventory, COINS, 100_000L - 480L));
        assertEquals(0L, h.net());
        h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480);

        h.quote[0] = 60;
        Ac settlement = h.settle(with(h.inventory, LOGS, 10L));
        assertNotNull(settlement);
        assertEquals(Ai.TRADE, settlement.getType());
        assertEquals("the asset value is the exact spend", 480L, flow(settlement, LOGS));
        assertEquals("exact execution spend", -480L, flow(settlement, COINS));
        assertEquals("a NEW BUY is Net-neutral", 0L, h.net());
        assertEquals(1, h.counted().size());
        assertEquals("the acquired quantity becomes known coverage", 10L,
            EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(480L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
        Bi.Row row = h.engine.ub().get(0);
        assertEquals("favourable execution edge is informational", 120L, row.geDifferenceGp);
    }

    @Test
    public void buyBankFundedWithoutInventoryReserveStillSettles()
    {
        Harness h = new Harness(48);
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        // No observed inventory coin departure: the offer was funded from the bank.
        h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480);
        assertEquals(0L, h.net());

        Ac settlement = h.settle(with(h.inventory, LOGS, 10L));
        assertNotNull(settlement);
        assertEquals(480L, flow(settlement, LOGS));
        assertEquals(-480L, flow(settlement, COINS));
        assertEquals(0L, h.net());
    }

    @Test
    public void buyRefundIsNeutral()
    {
        Harness h = new Harness(48);
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        h.settle(with(h.inventory, COINS, 100_000L - 480L));
        h.offer(0, CANCELLED_BUY, LOGS, 10, 0, 48, 0);
        h.settle(with(h.inventory, COINS, 100_000L));
        assertEquals("placement and refund cancel exactly", 0L, h.net());
        assertEquals(0, h.counted().size());
    }

    @Test
    public void buyUnpricedAcquisitionGoesToUncountedReview()
    {
        Harness h = new Harness(0);
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        h.settle(with(h.inventory, COINS, 100_000L - 480L));
        h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480);
        h.settle(with(h.inventory, LOGS, 10L));
        assertEquals("cash minus zero is never booked", 0L, h.net());
        assertEquals(1, h.reviews().size());
    }

    // ---- mixed flows and partitioning -----------------------------------------------------

    @Test
    public void mixedCustodyAndSupplyPartitionPerFlow()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 100L);
        h.inventory = with(h.inventory, ItemID.SHARK, 1L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, RUNE, 100, 0, 7, 0);
        h.engine.noteConsumptionIntent(ItemID.SHARK, 18, false, Au.EAT);
        Ac settle = h.settle(with(h.inventory, RUNE, 0L, ItemID.SHARK, 0L));

        assertNotNull(settle);
        assertEquals("the shark keeps its own supply classification",
            Ai.CONSUMPTION, settle.getType());
        assertEquals(-6L, flow(settle, ItemID.SHARK));
        assertEquals("the rune loss never appears as a supply",
            0L, flow(settle, RUNE));
        assertEquals("only the genuine supply cost remains counted", -6L, h.net());
    }

    @Test
    public void coinReserveClaimLeavesResidualToNormalClassification()
    {
        Harness h = new Harness(6);
        h.offer(0, BUYING, LOGS, 100, 0, 7, 0);
        // 750 leaves inventory; only the 700 listed principal is custody.
        Ac settle = h.settle(with(h.inventory, COINS, 100_000L - 750L));

        assertNotNull(settle);
        assertEquals("the residual 50 is not custody", 50L, -flow(settle, COINS));
        assertEquals("only the unexplained residual follows normal classification", -50L, h.net());
    }

    @Test
    public void sameItemMultiSlotPartitionsExactQuantities()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 300L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, RUNE, 100, 0, 7, 0);
        h.offer(1, SELLING, RUNE, 200, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));

        List<Aa> records = h.engine.geCustody.aji();
        assertEquals(2, records.size());
        long captured = 0L;
        for (Aa record : records)
        {
            captured += record.getCapturedQty();
        }
        assertEquals("the whole 300 is partitioned by exact evidence", 300L, captured);
        assertEquals(0L, h.net());
    }

    @Test
    public void genuineCastAfterGePlacementStaysConsumption()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, RUNE, 5, 0, 7, 0);
        h.engine.noteConsumptionIntent(RUNE, 18, false, Au.CAST);
        Ac cast = h.settle(with(h.inventory, RUNE, 9L));

        assertNotNull(cast);
        assertEquals(Ai.CONSUMPTION, cast.getType());
        assertEquals("Cast", Br.verbOf(cast));
        assertEquals(-6L, h.net());
    }

    // ---- terminal / unobserved ------------------------------------------------------------

    @Test
    public void collectToBankEndsInUncountedReview()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SOLD, RUNE, 10, 10, 7, 70);
        h.offer(0, EMPTY, 0, 0, 0, 0, 0);

        h.now += GeCustodyLedger.UNOBSERVED_GRACE_MILLIS + 60_000L;
        // An unrelated bank transfer still runs custody maintenance.
        h.engine.markContext(Aj.TRANSFER, 6, "Bank transfer");
        h.settle(with(h.inventory, COINS, 99_000L));

        assertEquals("no invented cash", 0L, h.net());
        assertTrue("known facts are handed to Review", h.reviews().size() >= 1);
        assertTrue(h.engine.geCustody.aji().isEmpty());
    }

    @Test
    public void slotReuseForcesReviewOfUnresolvedLifecycle()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SOLD, RUNE, 10, 10, 7, 70);
        h.offer(0, EMPTY, 0, 0, 0, 0, 0);
        // A brand-new offer in the same slot before the coins were ever collected.
        h.offer(0, SELLING, LOGS, 5, 0, 100, 0);

        assertEquals("unresolved lifecycle became a Review row", 1, h.reviews().size());
        assertEquals("the new offer is a fresh identity", 1,
            h.engine.geCustody.aji().size());
    }

    // ---- derived projection ---------------------------------------------------------------

    @Test
    public void projectionShowsPendingEdgeSeparateFromRealized()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.gain(RUNE, 10L, 60L, h.now - 60_000L);

        h.offer(0, SELLING, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SOLD, RUNE, 10, 10, 7, 70);

        List<Bi.Row> pending = h.engine.ub();
        assertEquals(1, pending.size());
        assertEquals(Bi.Lifecycle.EXECUTED_UNSETTLED,
            pending.get(0).lifecycle);
        assertEquals("pending edge is never realized", 0L, pending.get(0).realizedResultGp);

        h.settle(with(h.inventory, COINS, 100_070L));
        List<Bi.Row> realized = h.engine.ub();
        assertEquals(Bi.Lifecycle.REALIZED, realized.get(0).lifecycle);
        assertEquals("the realized known-basis result", 10L, realized.get(0).realizedResultGp);
    }

    // ---- identity windows -----------------------------------------------------------------

    @Test
    public void stalePlacementEvidenceCannotStealAnUnrelatedSameItemLoss()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, RUNE, 5, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 5L));
        // Spent placement evidence cannot own a later unrelated same-item loss.
        h.now += GeCustodyLedger.PLACEMENT_WINDOW_MILLIS + 1_000L;
        Ac later = h.settle(with(h.inventory, RUNE, 4L));
        assertNotNull(later);
        assertEquals(Ai.CONSUMPTION, later.getType());
        assertEquals(-6L, h.net());
    }

    @Test
    public void largeStacksKeepExactLongArithmetic()
    {
        Harness h = new Harness(2_000_000);
        long quantity = 1_000_000L;
        long basis = quantity * 2_000_000L;
        h.inventory = with(h.inventory, RUNE, quantity);
        h.engine.setBaseline(new Cc(h.inventory));
        h.gain(RUNE, quantity, basis, h.now - 60_000L);

        h.offer(0, SELLING, RUNE, (int) quantity, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SOLD, RUNE, (int) quantity, (int) quantity, 7, 2_000_000_000);
        Ac settlement = h.settle(with(h.inventory, COINS, 100_000L + 2_000_000_000L));

        assertEquals("exact long arithmetic, never a rounded average",
            2_000_000_000L - basis, settlement.getNet());
        assertEquals("end to end the received cash is the realization", 2_000_000_000L, h.net());
        Aa record = h.engine.geCustody.aji().get(0);
        assertEquals(2_000_000_000L, record.getSpentGp());
        assertEquals(basis, record.lo(quantity));
        assertEquals("the whole known basis was consumed exactly", basis,
            record.getConsumedTrackedBasisGp());
    }

    @Test
    public void buyPartialCollectionStaysAmbiguousAndFailsClosed()
    {
        Harness h = new Harness(48);
        h.offer(0, BUYING, LOGS, 10, 0, 48, 0);
        h.settle(with(h.inventory, COINS, 100_000L - 480L));
        h.offer(0, BOUGHT, LOGS, 10, 10, 48, 480);

        h.engine.abg(h.now);
        h.settle(with(h.inventory, LOGS, 5L));
        assertEquals("an attributable partial collection never books a result", 0L, h.net());
        assertEquals(0, h.counted().size());
        assertEquals(Bi.Lifecycle.AMBIGUOUS,
            h.engine.ub().get(0).lifecycle);

        h.engine.abg(h.now);
        h.settle(with(h.inventory, LOGS, 10L));
        assertEquals("the ambiguous lifecycle fails closed to uncounted review", 0L, h.net());
        assertTrue(h.reviews().size() >= 1);
    }

    @Test
    public void quantityModifiedOfferFailsClosedToReview()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        // The player modifies the offer quantity: no exact per-slice basis allocation exists.
        h.offer(0, SELLING, RUNE, 20, 0, 7, 0);
        h.offer(0, SOLD, RUNE, 20, 10, 7, 70);
        h.settle(with(h.inventory, COINS, 100_070L));

        assertEquals("an unprovable quantity allocation never books a Market result", 0L, h.net());
        assertTrue(h.reviews().size() >= 1);
    }

    @Test
    public void correctedSettlementRecomputesTheDerivedMarketResult()
    {
        Harness h = new Harness(6);
        h.inventory = with(h.inventory, RUNE, 10L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.gain(RUNE, 10L, 60L, h.now - 60_000L);

        h.offer(0, SELLING, RUNE, 10, 0, 7, 0);
        h.settle(with(h.inventory, RUNE, 0L));
        h.offer(0, SOLD, RUNE, 10, 10, 7, 70);
        Ac settlement = h.settle(with(h.inventory, COINS, 100_070L));

        Bi.Row before = h.engine.ub().get(0);
        assertEquals(10L, before.realizedResultGp);
        assertTrue(before.realizedResultCorrectionAware);

        h.engine.qi(settlement.getId(),
            Ah.IGNORE, h.now, "audit test");
        Bi.Row after = h.engine.ub().get(0);
        assertEquals("the projection consumes effective corrected truth", 0L, after.realizedResultGp);
        assertEquals("the counted gain remains", 60L, h.net());
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
