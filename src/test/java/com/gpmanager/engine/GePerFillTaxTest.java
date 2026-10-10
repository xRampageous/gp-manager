package com.gpmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.EMPTY;
import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Owner smoke (Nature rune, 5,488 for 40): a sell that fills at several prices pays GE tax per
 * item at each fill's own price, so the tax is exact only when summed per observed fill. The
 * uniform rule over the total either cannot prove it (non-divisible) or proves the wrong amount.
 */
public class GePerFillTaxTest
{
    private static final int COINS = ItemID.COINS;
    private static final int RUNE = ItemID.NATURERUNE;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void mixedPriceSaleSettlesAtItsExactPerFillTax()
    {
        // 30 @ 140 + 10 @ 129 = 5,490 for 40 (137.25 each: the total proves nothing).
        // Tax: 30 x 2 + 10 x 2 = 80.
        Fixture fixture = new Fixture(RUNE, 40L);
        fixture.sellInFills(0, new long[] {30L, 10L}, new long[] {4_200L, 1_290L}, T0 + 1_000L);
        fixture.collectAlone(0, 5_490L - 80L, T0 + 20_000L);

        assertEquals("exactly the per-fill tax is counted", -80L, fixture.net());
        assertEquals(0, fixture.reviewRows());
        MarketRows.Row row = fixture.row();
        assertEquals(80L, row.inferredGeTaxGp);
        assertTrue("the receipt shows the proven tax", row.knownCostOnly);
    }

    @Test
    public void mixedPriceSaleWhoseAverageLooksUniformUsesThePerFillTax()
    {
        // 20 @ 149 + 20 @ 151 = 6,000 for 40: the uniform rule would claim 40 x 3 = 120, but the
        // real tax is 20 x 2 + 20 x 3 = 100.
        Fixture fixture = new Fixture(RUNE, 40L);
        fixture.sellInFills(0, new long[] {20L, 20L}, new long[] {2_980L, 3_020L}, T0 + 1_000L);
        fixture.collectAlone(0, 6_000L - 100L, T0 + 20_000L);

        assertEquals(-100L, fixture.net());
        assertEquals(0, fixture.reviewRows());
        assertEquals(100L, fixture.row().inferredGeTaxGp);
    }

    @Test
    public void coalescedMixedPriceFillSettlesAtTheTaxItsCashProves()
    {
        // One observed chunk of 25 for 3,513 (140.52 each): no single price, so the tax lies
        // between (3,513 - 49 x 25) / 50 = 46 and 3,513 / 50 = 70. The collected cash proves 50.
        Fixture fixture = new Fixture(RUNE, 25L);
        fixture.sellInFills(0, new long[] {25L}, new long[] {3_513L}, T0 + 1_000L);
        fixture.collectAlone(0, 3_513L - 50L, T0 + 20_000L);
        fixture.engine.tickGe(T0 + 23_000L);

        assertEquals("exactly the proven tax is counted", -50L, fixture.net());
        assertEquals(0, fixture.reviewRows());
        assertEquals("the receipt shows the proven tax", 50L, fixture.row().inferredGeTaxGp);
    }

    @Test
    public void cashNoMixedPriceSaleCouldPayStillFailsClosed()
    {
        // A tax of 10 on 25 items worth 3,513 is impossible (at least 46): fail closed.
        Fixture fixture = new Fixture(RUNE, 25L);
        fixture.sellInFills(0, new long[] {25L}, new long[] {3_513L}, T0 + 1_000L);
        fixture.collectAlone(0, 3_513L - 10L, T0 + 20_000L);
        fixture.engine.tickGe(T0 + 23_000L);

        assertEquals("nothing is counted from an impossible tax", 0L, fixture.net());
        assertTrue("it fails closed visibly", fixture.reviewRows() > 0);
    }

    @Test
    public void collectAllOfAnExactAndAMixedPriceSaleSettlesBoth()
    {
        // Owner 2026-10-07 (yew logs at exactly 90 each and 10,471 longbows at 1,650,785):
        // the two slots cleared together and one Collect paid both after tax.
        Fixture fixture = new Fixture(RUNE, 45L);
        fixture.sellInFills(0, new long[] {20L}, new long[] {2_800L}, T0 + 1_000L);
        fixture.sellInFills(1, new long[] {25L}, new long[] {3_513L}, T0 + 5_000L);
        fixture.clear(0, T0 + 19_400L);
        fixture.clear(1, T0 + 19_450L);
        fixture.collect((2_800L - 40L) + (3_513L - 50L), T0 + 20_000L);
        fixture.engine.tickGe(T0 + 23_000L);

        assertEquals(-90L, fixture.net());
        assertEquals(0, fixture.reviewRows());
    }

    @Test
    public void collectAllOfTwoMixedPriceSalesSettlesTheirProvenTotal()
    {
        Fixture fixture = new Fixture(RUNE, 50L);
        fixture.sellInFills(0, new long[] {25L}, new long[] {3_513L}, T0 + 1_000L);
        fixture.sellInFills(1, new long[] {25L}, new long[] {3_513L}, T0 + 5_000L);
        fixture.clear(0, T0 + 19_400L);
        fixture.clear(1, T0 + 19_450L);
        fixture.collect(2L * (3_513L - 50L), T0 + 20_000L);
        fixture.engine.tickGe(T0 + 23_000L);

        assertEquals("the combined tax is exact", -100L, fixture.net());
        assertEquals(0, fixture.reviewRows());
    }

    @Test
    public void collectAllOfTwoMixedPriceSalesSettlesEachExactly()
    {
        Fixture fixture = new Fixture(RUNE, 80L);
        fixture.sellInFills(0, new long[] {30L, 10L}, new long[] {4_200L, 1_290L}, T0 + 1_000L);
        fixture.sellInFills(1, new long[] {20L, 20L}, new long[] {2_980L, 3_020L}, T0 + 5_000L);
        fixture.clear(0, T0 + 19_400L);
        fixture.clear(1, T0 + 19_450L);
        fixture.collect((5_490L - 80L) + (6_000L - 100L), T0 + 20_000L);

        assertEquals(-180L, fixture.net());
        assertEquals(0, fixture.reviewRows());
    }

    @Test
    public void perFillTaxSurvivesRestartAndPreSchemaRecordsKeepTheUniformRule()
    {
        Fixture fixture = new Fixture(RUNE, 40L);
        fixture.sellInFills(0, new long[] {30L, 10L}, new long[] {4_200L, 1_290L}, T0 + 1_000L);
        SavedState state = fixture.engine.savedState();
        assertEquals(109, state.schemaVersion);
        GeOffer saved = state.getGeCustody().get(0);
        assertTrue(saved.fillTaxExact);
        assertEquals(80L, saved.getFillTaxGp());

        String json = JsonCodec.gson().toJson(state);
        SavedState reloaded = JsonCodec.gson().fromJson(json, SavedState.class);
        assertTrue(reloaded.getGeCustody().get(0).fillTaxExact);
        assertEquals(80L, reloaded.getGeCustody().get(0).getFillTaxGp());

        // A pre-107 record has no per-fill fields: it keeps the conservative uniform rule.
        String legacy = json.replace("\"fillTaxExact\":true", "\"fillTaxExact\":false")
            .replace("\"fillTaxGp\":80", "\"fillTaxGp\":0");
        GeOffer old = JsonCodec.gson().fromJson(legacy, SavedState.class)
            .getGeCustody().get(0);
        assertFalse(old.fillTaxExact);
    }

    // ── fixture ────────────────────────────────────────────────────────────────────────────────

    private static final class Fixture
    {
        final Engine engine;
        final OfferSlots ledger = new OfferSlots();
        final Map<Integer, Long> inventory = new HashMap<>();
        final int item;

        Fixture(int item, long stock)
        {
            this.item = item;
            engine = new Engine(deltas ->
            {
                List<Flow> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> delta : deltas.entrySet())
                {
                    int id = delta.getKey();
                    long unit = id == COINS ? 1L : 137L;
                    flows.add(new Flow(id, id == COINS ? "Coins" : "Item " + id, delta.getValue(),
                        (int) unit, delta.getValue() * unit,
                        id == COINS ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
                }
                return flows;
            }, new TransactionClassifier(), new GpManagerConfig()
            {
                @Override
                public Retention receiptRetentionDays()
                {
                    return Retention.DAYS_365;
                }

                @Override
                public int stabilizationTicks()
                {
                    return 0;
                }
            });
            engine.startCustom("Trading", T0);
            inventory.put(COINS, 1_000_000L);
            inventory.put(item, stock);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }

        /** List the whole stack in one slot, then observe each fill as its own offer update. */
        void sellInFills(int slot, long[] quantities, long[] spent, long at)
        {
            long total = 0L;
            for (long quantity : quantities)
            {
                total += quantity;
            }
            offer(slot, SELLING, total, 0L, 0L, at);
            inventory.put(item, inventory.get(item) - total);
            settle(at + 100L);
            long traded = 0L;
            long value = 0L;
            for (int index = 0; index < quantities.length; index++)
            {
                traded += quantities[index];
                value += spent[index];
                offer(slot, traded == total ? SOLD : SELLING, total, traded, value, at + 1_000L * (index + 1));
            }
        }

        void clear(int slot, long at)
        {
            offer(slot, EMPTY, 0L, 0L, 0L, at);
        }

        void collect(long received, long at)
        {
            engine.noteGeCollect(at);
            inventory.put(COINS, inventory.get(COINS) + received);
            settle(at);
        }

        void collectAlone(int slot, long received, long at)
        {
            clear(slot, at - 600L);
            collect(received, at);
        }

        long net()
        {
            return engine.getMetrics(T0 + 60_000L).net;
        }

        int reviewRows()
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

        MarketRows.Row row()
        {
            return engine.marketRows().get(0);
        }

        private void offer(int slot, GrandExchangeOfferState state, long total, long traded, long spent,
            long at)
        {
            ledger.observe(new OfferSlots.Snapshot(slot, state, item, (int) total, (int) traded, 137,
                (int) spent)).ifPresent(transition -> engine.noteGeOffer(transition, "Item", at));
        }

        private void settle(long at)
        {
            engine.markDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            engine.processIfDirty(snapshot, at);
            engine.processIfDirty(snapshot, at + 1L);
        }
    }
}
