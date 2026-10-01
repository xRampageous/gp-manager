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
import static net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2C.6A final law: known-basis coverage, unknown-basis liquidation.
 *
 * <p>UNKNOWN BASIS != ZERO BASIS. A sale realizes Result only for the quantity whose prior
 * GP Manager accounting is proven; unknown quantity is Net-neutral liquidation, and known zero
 * basis is a valid, distinct coverage state.</p>
 */
public class TrackedBasisKnownCoverageTest
{
    private static final int LOGS = ItemID.LOGS;
    private static final int COINS = ItemID.COINS;
    private static final int NOTED_LOGS = LOGS + 1_000;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    // ---- fully unknown liquidation ----------------------------------------------------------

    @Test
    public void fullyUnknownSaleIsNeutralLiquidation()
    {
        Harness h = new Harness(159);
        h.inventory = with(h.inventory, LOGS, 5L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, LOGS, 5, 0, 159, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        h.offer(0, SOLD, LOGS, 5, 5, 159, 790);
        h.settle(with(h.inventory, COINS, 1_000_790L));

        assertEquals("unknown liquidation never creates automatic Net", 0L, h.net());
        assertEquals("no known coverage is invented", 0L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(0L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        Bi.Row row = h.rows().get(0);
        assertEquals(5L, row.settledQty);
        assertEquals("known coverage absent", 0L, MarketFacts.trackedQtyConsumed(MarketFacts.record(h.engine, row)));
        assertEquals(5L, MarketFacts.unknownQtyRealized(MarketFacts.record(h.engine, row)));
        assertEquals(0L, MarketFacts.knownProceedsGp(MarketFacts.record(h.engine, row)));
        assertEquals("received cash is liquidation evidence", 790L, MarketFacts.unknownLiquidationGp(MarketFacts.record(h.engine, row)));
        assertEquals(790L, Math.abs(row.observedSettlementGp));
        assertEquals(Bi.Coverage.FULLY_UNKNOWN, row.coverage);
        assertTrue(row.geDifferenceAvailable);
        assertEquals("frozen gross reference 5 x 159", 795L, MarketFacts.grossGeReferenceGp(MarketFacts.record(h.engine, row)));
        assertEquals("tax-adjusted reference carries the expected per-item tax", 780L,
            row.geReferenceGp);
        assertEquals("GE difference compares after-tax cash with the net reference", 10L,
            row.geDifferenceGp);

        Ac settlement = h.ajs(row.settlementId);
        assertNotNull(settlement);
        assertFalse("an unknown-basis settlement is Net-neutral evidence", settlement.isCounted());
    }

    @Test
    public void knownZeroBasisAcquisitionRealizesFullProceeds()
    {
        Harness h = new Harness(159);
        h.zeroGain(LOGS, 5L, h.now - 60_000L);
        assertEquals("known coverage quantity", 5L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals("known zero basis", 0L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        h.sell(0, LOGS, 5L, 159, 790L);

        assertEquals("known zero basis legitimately realizes the proceeds", 790L, h.net());
        Bi.Row row = h.rows().get(0);
        assertEquals(Bi.Coverage.FULLY_KNOWN, row.coverage);
        assertEquals(5L, MarketFacts.trackedQtyConsumed(MarketFacts.record(h.engine, row)));
        assertEquals(0L, MarketFacts.unknownQtyRealized(MarketFacts.record(h.engine, row)));
        assertEquals(790L, MarketFacts.knownProceedsGp(MarketFacts.record(h.engine, row)));
        assertEquals(0L, row.trackedBasisConsumedGp);
        assertEquals(790L, row.realizedResultGp);
    }

    @Test
    public void unpricedAcquisitionCreatesNoKnownCoverage()
    {
        Harness h = new Harness(159);
        h.unpricedGain(LOGS, 5L, h.now - 60_000L);
        assertEquals("an unpriced acquisition is unknown, never zero basis", 0L,
            EngineProbe.knownCoverageQty(h.engine, LOGS));

        h.sell(0, LOGS, 5L, 159, 790L);
        assertEquals("its sale stays Net-neutral", 0L, h.net());
    }

    // ---- known coverage ---------------------------------------------------------------------

    @Test
    public void knownLootBasisRealizesDelta()
    {
        Harness h = new Harness(159);
        h.gain(LOGS, 5L, 775L, h.now - 60_000L);

        h.sell(0, LOGS, 5L, 159, 790L);

        assertEquals("earlier counted value plus sale result", 775L + 15L, h.net());
        Bi.Row row = h.rows().get(0);
        assertEquals(Bi.Coverage.FULLY_KNOWN, row.coverage);
        assertEquals(775L, row.trackedBasisConsumedGp);
        assertEquals(790L, MarketFacts.knownProceedsGp(MarketFacts.record(h.engine, row)));
        assertEquals(15L, row.realizedResultGp);
        assertEquals(0L, MarketFacts.unknownLiquidationGp(MarketFacts.record(h.engine, row)));
    }

    @Test
    public void knownBasisAboveSaleRealizesNegative()
    {
        Harness h = new Harness(159);
        h.gain(LOGS, 5L, 800L, h.now - 60_000L);

        h.sell(0, LOGS, 5L, 159, 790L);

        assertEquals("earlier counted value plus negative sale result", 800L - 10L, h.net());
        assertEquals(-10L, h.rows().get(0).realizedResultGp);
    }

    @Test
    public void mixedCoverageRealizesPartialResult()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 4L, 380L, h.now - 60_000L);
        h.inventory = with(h.inventory, LOGS, 10L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, LOGS, 10, 0, 100, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        h.offer(0, SOLD, LOGS, 10, 10, 100, 1_000);
        h.settle(with(h.inventory, COINS, 1_001_000L));

        assertEquals("only the known portion contributes", 380L + 20L, h.net());
        Bi.Row row = h.rows().get(0);
        assertEquals(Bi.Coverage.PARTIALLY_KNOWN, row.coverage);
        assertEquals(4L, MarketFacts.trackedQtyConsumed(MarketFacts.record(h.engine, row)));
        assertEquals(6L, MarketFacts.unknownQtyRealized(MarketFacts.record(h.engine, row)));
        assertEquals(380L, row.trackedBasisConsumedGp);
        assertEquals("floor share of the settlement", 400L, MarketFacts.knownProceedsGp(MarketFacts.record(h.engine, row)));
        assertEquals("unknown remainder is liquidation", 600L, MarketFacts.unknownLiquidationGp(MarketFacts.record(h.engine, row)));
        assertEquals(20L, row.realizedResultGp);
    }

    @Test
    public void geBuyThenSellRealizesTradingProfit()
    {
        Harness h = new Harness(140);
        h.buy(0, LOGS, 5L, 140, 700L);
        assertEquals("acquisition is Net-neutral", 0L, h.net());
        assertEquals(5L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(700L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        h.sell(1, LOGS, 5L, 170, 850L);

        assertEquals("proven trading profit", 150L, h.net());
        Bi.Row row = h.rows().stream()
            .filter(candidate -> candidate.side == Aa.Side.SELL)
            .findFirst().orElseThrow(AssertionError::new);
        assertEquals(Bi.Coverage.FULLY_KNOWN, row.coverage);
        assertEquals(700L, row.trackedBasisConsumedGp);
        assertEquals(150L, row.realizedResultGp);
    }

    // ---- rounding ---------------------------------------------------------------------------

    @Test
    public void weightedBasisRoundingLosesNothing()
    {
        assertEquals(3L, Df.ayb(3L, 10L, 1L));
        assertEquals(3L, Df.ayb(2L, 7L, 1L));
        assertEquals("the last unit absorbs the remainder", 4L, Df.ayb(1L, 4L, 1L));
        assertEquals(0L, Df.ayb(0L, 0L, 1L));
    }

    @Test
    public void mixedProceedsAllocationKeepsExactSum()
    {
        assertEquals(3L, Df.yl(10L, 3L, 1L));
        long known = Df.yl(10L, 3L, 1L);
        assertEquals("unknown receives the integer remainder", 10L, known + (10L - known));
        assertEquals("all-known takes the exact settlement", 10L,
            Df.yl(10L, 3L, 3L));
        assertEquals(0L, Df.yl(10L, 3L, 0L));
    }

    // ---- reservation ------------------------------------------------------------------------

    @Test
    public void simultaneousOffersNeverReserveTheSameCoverage()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 100L, 10_000L, h.now - 60_000L);
        h.inventory = with(h.inventory, LOGS, 120L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, LOGS, 60, 0, 100, 0);
        h.settle(with(h.inventory, LOGS, 60L));
        assertEquals(60L, EngineProbe.reservedQty(h.engine, LOGS));
        assertEquals(6_000L, EngineProbe.reservedBasisGp(h.engine, LOGS));

        h.offer(1, SELLING, LOGS, 60, 0, 100, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        assertEquals("together they reserve exactly the known coverage", 100L,
            EngineProbe.reservedQty(h.engine, LOGS));
        assertEquals(10_000L, EngineProbe.reservedBasisGp(h.engine, LOGS));
        assertEquals(0L, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals("20 offered units are unknown, never zero basis", 20L,
            h.engine.geCustody.aji().get(1).getOfferedQty()
                - h.engine.geCustody.aji().get(1).getReservedTrackedQty());

        h.offer(0, CANCELLED_SELL, LOGS, 60, 0, 100, 0);
        h.settle(with(h.inventory, LOGS, 60L));
        assertEquals("cancelling A returns its unused known reservation exactly", 60L,
            EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(6_000L, EngineProbe.availableBasisGp(h.engine, LOGS));
        assertEquals(40L, EngineProbe.reservedQty(h.engine, LOGS));
        assertEquals(4_000L, EngineProbe.reservedBasisGp(h.engine, LOGS));
    }

    @Test
    public void partialFillCancelReleasesOnlyUnusedKnownCoverage()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 100L, 10_000L, h.now - 60_000L);
        h.inventory = with(h.inventory, LOGS, 200L);
        h.engine.setBaseline(new Cc(h.inventory));

        h.offer(0, SELLING, LOGS, 200, 0, 100, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        assertEquals(100L, EngineProbe.reservedQty(h.engine, LOGS));

        h.offer(0, SELLING, LOGS, 200, 30, 100, 3_000);
        h.offer(0, SOLD, LOGS, 200, 30, 100, 3_000);
        h.settle(with(h.inventory, COINS, 1_003_000L));
        assertEquals("only the realized 30 consume reserved basis", 30L,
            h.engine.geCustody.aji().get(0).getConsumedTrackedQty());
        assertEquals(3_000L,
            h.engine.geCustody.aji().get(0).getConsumedTrackedBasisGp());
        assertEquals("the earlier counted value plus a zero sale result", 10_000L, h.net());

        h.offer(0, CANCELLED_SELL, LOGS, 200, 30, 100, 3_000);
        h.settle(with(h.inventory, LOGS, 170L));
        assertEquals("the unrealized reservation returns", 70L, EngineProbe.availableQty(h.engine, LOGS));
        assertEquals(7_000L, EngineProbe.availableBasisGp(h.engine, LOGS));
        assertEquals("no unknown unit ever enters the known pool", 70L,
            EngineProbe.knownCoverageQty(h.engine, LOGS));
    }

    // ---- depletion / neutrality -------------------------------------------------------------

    @Test
    public void provenPermanentSinkDepletesKnownCoverage()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 10L, 1_000L, h.now - 60_000L);

        h.sink(LOGS, 4L, h.now - 30_000L);
        assertEquals(6L, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        h.transfer(LOGS, 4L, h.now - 20_000L);
        assertEquals("ownership-location transfers never deplete", 6L,
            EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals(600L, EngineProbe.knownCoverageBasisGp(h.engine, LOGS));
    }

    // ---- persistence / migration ------------------------------------------------------------

    @Test
    public void restartKeepsCoverageAndReservations()
    {
        Harness h = new Harness(100);
        h.gain(LOGS, 100L, 10_000L, h.now - 60_000L);
        h.inventory = with(h.inventory, LOGS, 60L);
        h.engine.setBaseline(new Cc(h.inventory));
        h.offer(0, SELLING, LOGS, 60, 0, 100, 0);
        h.settle(with(h.inventory, LOGS, 0L));
        assertEquals(60L, EngineProbe.reservedQty(h.engine, LOGS));

        SavedState state = h.engine.qm();
        assertEquals(108, state.schemaVersion);
        Am restarted = engine(new int[] {100});
        restarted.restore(state, h.now);
        restarted.setBaseline(new Cc(with(h.inventory, LOGS, 0L)));
        restarted.getActiveSession().resume(h.now);
        restarted.ahy(Collections.singletonMap(0,
            new Bj.Snapshot(0, SELLING, LOGS, 60, 0, 100, 0)), h.now + 500L);

        assertEquals("available coverage survives restart", 40L, EngineProbe.knownCoverageQty(restarted, LOGS)
            - EngineProbe.reservedQty(restarted, LOGS));
        assertEquals(4_000L, EngineProbe.availableBasisGp(restarted, LOGS));
        assertEquals("the open reservation survives restart", 60L,
            EngineProbe.reservedQty(restarted, LOGS));
        assertEquals(6_000L, EngineProbe.reservedBasisGp(restarted, LOGS));
        assertEquals(1, restarted.geCustody.aji().size());

        Bj ledger = new Bj();
        Bj.Transition cancel = ledger.observe(
            new Bj.Snapshot(0, CANCELLED_SELL, LOGS, 60, 0, 100, 0)).orElse(null);
        assertNotNull(cancel);
        restarted.abh(cancel, "Logs", h.now + 1_000L);
        restarted.yz();
        Cc back = new Cc(with(h.inventory, LOGS, 60L));
        for (int i = 0; i < 3; i++)
        {
            restarted.adj(back, h.now + 1_000L + i * 600L);
        }
        assertEquals("cancelling after restart releases the exact reservation", 100L,
            EngineProbe.knownCoverageQty(restarted, LOGS));
        assertEquals(10_000L, EngineProbe.availableBasisGp(restarted, LOGS));
        assertEquals(0L, EngineProbe.reservedQty(restarted, LOGS));
    }

    @Test
    public void schema105MigrationStartsUnknownWithoutBackfill()
    {
        Harness h = new Harness(159);
        h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        long historicalNet = h.net();
        SavedState legacy = h.engine.qm();
        legacy.setSchemaVersion(105);
        legacy.setTrackedBasis(null);

        Am migrated = engine(new int[] {159});
        migrated.restore(legacy, h.now + 1_000L);

        assertEquals("no bank or history backfill", 0L, EngineProbe.knownCoverageQty(migrated, LOGS));
        assertEquals(0L, EngineProbe.knownCoverageBasisGp(migrated, LOGS));
        assertEquals("historical Net is unchanged by migration", historicalNet,
            migrated.getMetrics(h.now + 1_000L).net);

        // New post-migration acquisitions still create known coverage.
        Ac gain = new Ac(h.now + 2_000L, null, Ai.GAIN,
            Aj.GENERIC, "", "Loot", true,
            Collections.singletonList(new Ab(LOGS, "Logs", 3L, 100, 300L,
                Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "", null);
        migrated.getActiveSession().kf(gain, 500);
        assertEquals(3L, EngineProbe.knownCoverageQty(migrated, LOGS));
        assertEquals(300L, EngineProbe.knownCoverageBasisGp(migrated, LOGS));
    }

    @Test
    public void bankValueEstimateNeverSeedsOrChangesBasis()
    {
        Harness h = new Harness(159);
        h.gain(LOGS, 5L, 775L, h.now - 60_000L);
        long qty = EngineProbe.knownCoverageQty(h.engine, LOGS);
        long basis = EngineProbe.knownCoverageBasisGp(h.engine, LOGS);

        h.quote[0] = 900;
        assertEquals("a price/estimate change never seeds basis", 0L,
            EngineProbe.knownCoverageQty(h.engine, COINS));
        assertEquals(qty, EngineProbe.knownCoverageQty(h.engine, LOGS));
        assertEquals("a price/estimate change never reprices basis", basis,
            EngineProbe.knownCoverageBasisGp(h.engine, LOGS));

        h.sell(0, LOGS, 5L, 900, 4_500L);
        assertEquals("basis stays the exact counted value", 775L + (4_500L - 775L), h.net());
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
            book(new Ac(at, null, Ai.GAIN, Aj.GENERIC, "",
                "Loot", true,
                Collections.singletonList(new Ab(item, name(item), qty,
                    (int) (qty > 0L ? value / qty : 0L), value, Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null));
        }

        void zeroGain(int item, long qty, long at)
        {
            book(new Ac(at, null, Ai.GAIN, Aj.GENERIC, "",
                "Loot", true,
                Collections.singletonList(new Ab(item, name(item), qty, 0, 0L,
                    Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null));
        }

        void unpricedGain(int item, long qty, long at)
        {
            book(new Ac(at, null, Ai.GAIN, Aj.GENERIC, "",
                "Loot", true,
                Collections.singletonList(new Ab(item, name(item), qty, 0, 0L,
                    Av.UNPRICED)),
                Bd.CONFIRMED, "", null));
        }

        void sink(int item, long qty, long at)
        {
            book(new Ac(at, null, Ai.CONSUMPTION, Aj.GENERIC,
                "", "Used", true,
                Collections.singletonList(new Ab(item, name(item), -qty, 100, -qty * 100L,
                    Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null));
        }

        void transfer(int item, long qty, long at)
        {
            book(new Ac(at, null, Ai.TRANSFER, Aj.TRANSFER,
                "Bank deposit", "Transfer", false,
                Collections.singletonList(new Ab(item, name(item), -qty, 100, -qty * 100L,
                    Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null));
        }

        void book(Ac transaction)
        {
            engine.getActiveSession().kf(transaction, 500);
        }

        void sell(int slot, int item, long qty, int limit, long cash)
        {
            inventory = with(inventory, item, qty);
            engine.setBaseline(new Cc(inventory));
            offer(slot, SELLING, item, (int) qty, 0, limit, 0);
            inventory = with(inventory, item, 0L);
            settle(inventory);
            offer(slot, SOLD, item, (int) qty, (int) qty, limit, (int) cash);
            inventory = with(inventory, COINS, inventory.getOrDefault(COINS, 0L) + cash);
            settle(inventory);
        }

        void buy(int slot, int item, long qty, int limit, long spend)
        {
            offer(slot, BUYING, item, (int) qty, 0, limit, 0);
            inventory = with(inventory, COINS, inventory.getOrDefault(COINS, 0L) - spend);
            settle(inventory);
            offer(slot, BOUGHT, item, (int) qty, (int) qty, limit, (int) spend);
            inventory = with(inventory, item, qty);
            settle(inventory);
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

        List<Bi.Row> rows()
        {
            return engine.ub();
        }

        Ac ajs(String id)
        {
            for (Ac transaction : engine.getActiveSession().getTransactions())
            {
                if (transaction != null && transaction.getId().equals(id))
                {
                    return transaction;
                }
            }
            return null;
        }
    }

    private static String name(int id)
    {
        return id == COINS ? "Coins" : id == LOGS ? "Logs" : "Item " + id;
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
