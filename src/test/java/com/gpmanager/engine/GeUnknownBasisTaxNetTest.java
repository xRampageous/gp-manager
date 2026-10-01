package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static net.runelite.api.GrandExchangeOfferState.SELLING;
import static net.runelite.api.GrandExchangeOfferState.SOLD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * PR1-PRE-R5C.2C.6A.1B: an unknown-basis GE sale whose ACTUAL tax is proven contributes exactly
 * that tax to Net (a Market cost) while its Result stays unknown. Exempt, unproven, pre-change and
 * corrected sales keep their existing automatic contribution, and every correction replaces the
 * tax instead of stacking on it.
 */
public class GeUnknownBasisTaxNetTest
{
    private static final int COINS = ItemID.COINS;
    private static final int VIAL = ItemID.VIAL_BLOOD;
    private static final int AMETHYST_ARROW = ItemID.AMETHYST_ARROW;
    private static final int CHICKEN = ItemID.COOKED_CHICKEN;
    private static final int RUNE = ItemID.NATURERUNE;
    private static final int CHAOS = ItemID.CHAOSRUNE;
    private static final long T0 = 1_000_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    // ── the fixtures ───────────────────────────────────────────────────────────────────────────

    @Test
    public void unknownBasisProvenTaxBooksExactlyTheTaxAndStaysUnknown()
    {
        Market market = new Market();
        market.prices.put(VIAL, 8_536L);
        market.sellUnknown(0, VIAL, 4L, 8_536, 33_804, 33_128);

        Bi.Row row = market.row(VIAL);
        assertEquals(4L, row.settledQty);
        assertEquals("Received is the actual after-tax cash", 33_128L, row.observedSettlementGp);
        assertEquals(33_804L, MarketFacts.realizedGrossGp(MarketFacts.record(market.engine, row)));
        assertEquals(-676L, row.settlementAdjustmentGp);
        assertEquals(676L, row.inferredGeTaxGp);
        assertEquals(8_282L, row.receivedEachGp);
        assertEquals(Bi.Coverage.FULLY_UNKNOWN, row.coverage);
        assertEquals("coverage is genuinely unknown", 0L, MarketFacts.trackedQtyConsumed(MarketFacts.record(market.engine, row)));
        assertTrue("the booked state is KNOWN_COST_ONLY", row.knownCostOnly);
        assertTrue(row.realizedResultCorrectionAware);
        assertEquals("the booked Net contribution is exactly -tax", -676L, row.realizedResultGp);

        Ac settlement = market.transaction(row.settlementId);
        assertNotNull(settlement);
        assertTrue("one counted MARKET settlement", settlement.isCounted());
        assertEquals(Ai.TRADE, settlement.getType());
        assertEquals(Ah.AUTO, settlement.getCorrection());
        assertEquals("automatic Net is exactly -accepted tax", -676L, settlement.getNet());
        assertEquals(-33_804L, flow(settlement, VIAL));
        assertEquals(33_128L, flow(settlement, COINS));
        assertNoSyntheticTaxFlow(settlement);

        assertEquals("exactly one counted settlement exists", 1, market.counted());
        assertEquals("the tax lands in the Market result", -676L,
            Bp.zl(market.transactions()).getNet());
        assertEquals("Live/grind Net reads the same booked transaction", -676L, market.net());
        assertEquals(-336L, row.geDifferenceGp);
        assertEquals("GE difference compares Received with the tax-adjusted reference",
            row.observedSettlementGp - row.geReferenceGp, row.geDifferenceGp);
        assertNotEquals("GE difference is non-zero and never affects Net", 0L, row.geDifferenceGp);
    }

    @Test
    public void exemptUnknownSaleStaysNetZeroWithoutATax()
    {
        Market market = new Market();
        market.prices.put(CHICKEN, 66L);
        market.sellUnknown(0, CHICKEN, 5L, 69, 315, 315);

        Bi.Row row = market.row(CHICKEN);
        assertEquals(Bi.Coverage.FULLY_UNKNOWN, row.coverage);
        assertEquals("an exempt item never proves a tax", 0L, row.inferredGeTaxGp);
        assertFalse(row.knownCostOnly);
        assertEquals(0L, row.realizedResultGp);
        assertEquals(0L, market.net());
        assertEquals("an exempt sale stays uncounted", 0, market.counted());
    }

    @Test
    public void knownBasisSaleCountsTheResultWithoutASecondTax()
    {
        Market market = new Market();
        market.prices.put(RUNE, 420L);
        gain(market.engine, RUNE, 13L, 5_000L, market.now - 60_000L);
        market.sellUnknown(0, RUNE, 13L, 420, 5_460, 5_356);

        Bi.Row row = market.row(RUNE);
        assertEquals(Bi.Coverage.FULLY_KNOWN, row.coverage);
        assertEquals(104L, row.inferredGeTaxGp);
        assertEquals(5_000L, row.trackedBasisConsumedGp);
        assertEquals("Result = after-tax Received - proven basis", 356L, row.realizedResultGp);
        assertFalse("a full result is never KNOWN_COST_ONLY", row.knownCostOnly);
        assertEquals("Net = Result; no separate tax contribution", 356L,
            Bp.zl(market.transactions()).getNet());
        assertEquals("the earlier counted gain plus the sale Result", 5_356L, market.net());
        assertNoSyntheticTaxFlow(market.transaction(row.settlementId));
    }

    @Test
    public void exemptAndKnownBasisFlowsAreUnchangedSoTheirPreviewsStayIdentical()
    {
        Market exempt = new Market();
        exempt.prices.put(CHICKEN, 66L);
        exempt.sellUnknown(0, CHICKEN, 5L, 69, 315, 315);
        Ac exemptSettlement =
            exempt.transaction(exempt.row(CHICKEN).settlementId);
        assertEquals("the exempt booking keeps the frozen-reference flow exactly", -330L,
            flow(exemptSettlement, CHICKEN));
        assertEquals(315L, flow(exemptSettlement, COINS));
        assertFalse(exemptSettlement.isCounted());
        assertEquals(0L, exemptSettlement.awp(Ah.AUTO));

        Market known = new Market();
        known.prices.put(RUNE, 420L);
        gain(known.engine, RUNE, 13L, 5_000L, known.now - 60_000L);
        known.sellUnknown(0, RUNE, 13L, 420, 5_460, 5_356);
        Ac knownSettlement = known.transaction(known.row(RUNE).settlementId);
        assertEquals("the known-basis booking keeps its consumed-basis flow exactly", -5_000L,
            flow(knownSettlement, RUNE));
        assertEquals(5_356L, flow(knownSettlement, COINS));
        assertTrue(knownSettlement.isCounted());
        assertEquals(356L, knownSettlement.awp(Ah.AUTO));
    }

    @Test
    public void amethystArrowFixtureCountsItsTaxOnce()
    {
        Market market = new Market();
        market.prices.put(AMETHYST_ARROW, 137L);
        market.sellUnknown(0, AMETHYST_ARROW, 110L, 137, 15_070, 14_850);

        Bi.Row row = market.row(AMETHYST_ARROW);
        assertEquals(220L, row.inferredGeTaxGp);
        assertEquals(135L, row.receivedEachGp);
        assertTrue(row.knownCostOnly);
        assertEquals(-220L, row.realizedResultGp);
        assertEquals(-220L, market.net());
        assertEquals(1, market.counted());
    }

    // ── corrections and undo ───────────────────────────────────────────────────────────────────

    @Test
    public void correctPreviewsKeepTheFormulaWithOnlyTheAllowedFlowDifference()
    {
        Market market = new Market();
        market.prices.put(VIAL, 8_536L);
        market.sellUnknown(0, VIAL, 4L, 8_536, 33_804, 33_128);
        Ac settlement = market.transaction(market.row(VIAL).settlementId);

        // The 804fa72 shape: an uncounted unknown-basis sale with the item flow at the frozen
        // reference (4 x 8,536) and the same observed Received cash.
        Ac before = new Ac(market.now - 1_000L, null,
            Ai.TRADE, Aj.MARKET, "Grand Exchange", "Market", false,
            java.util.Arrays.asList(
                new Ab(VIAL, name(VIAL), -4L, 8_536, -34_144L, Av.GRAND_EXCHANGE),
                new Ab(COINS, "Coins", 33_128L, 1, 33_128L, Av.FACE_VALUE)),
            Bd.CONFIRMED, "", null);

        assertEquals("804fa72 REVENUE preview", 33_128L + 34_144L, before.awp(Ah.REVENUE));
        assertEquals("after REVENUE preview", 33_128L + 33_804L, settlement.awp(Ah.REVENUE));
        assertEquals("the difference is exactly the §4a flow valuation",
            -(34_144L - 33_804L),
            settlement.awp(Ah.REVENUE)
                - before.awp(Ah.REVENUE));
        assertEquals("COST", -(33_128L + 33_804L), settlement.awp(Ah.COST));
        assertEquals("TRANSFER is unchanged", 0L, settlement.awp(Ah.TRANSFER));
        assertEquals("IGNORE is unchanged", 0L, settlement.awp(Ah.IGNORE));
        assertEquals("the automatic contribution itself is -acknowledged tax",
            -676L, settlement.getNet());
    }

    @Test
    public void everyCorrectionReplacesTheTaxAndUndoRestoresItExactly()
    {
        for (Ah correction : new Ah[] {
            Ah.REVENUE, Ah.COST,
            Ah.TRANSFER, Ah.IGNORE})
        {
            Market market = new Market();
            market.prices.put(VIAL, 8_536L);
            market.sellUnknown(0, VIAL, 4L, 8_536, 33_804, 33_128);
            String settlementId = market.row(VIAL).settlementId;
            Ac settlement = market.transaction(settlementId);
            long expectedAfter = settlement.awp(correction);

            assertEquals(-676L, market.net());
            assertTrue(market.engine.qi(settlementId, correction, market.now + 5_000L,
                "test " + correction));
            assertEquals("the correction REPLACES the automatic tax (never stacks)",
                expectedAfter, market.net());
            Bi.Row corrected = market.row(VIAL);
            assertTrue(corrected.correctionApplied);
            assertFalse("a corrected sale is no longer KNOWN_COST_ONLY",
                corrected.knownCostOnly);
            assertEquals(expectedAfter, corrected.realizedResultGp);
            assertEquals("no second tax contribution appears",
                expectedAfter, Bp.zl(market.transactions()).getNet());

            assertTrue(market.engine.akb(market.now + 6_000L));
            assertEquals("Undo restores exactly -accepted tax", -676L, market.net());
            Bi.Row undone = market.row(VIAL);
            assertTrue("the automatic booked state returns", undone.knownCostOnly);
            assertEquals(-676L, undone.realizedResultGp);
            assertEquals(Ah.AUTO, market.transaction(settlementId).getCorrection());
        }
    }

    @Test
    public void transferAndIgnoreCorrectionsZeroTheContribution()
    {
        Market market = new Market();
        market.prices.put(VIAL, 8_536L);
        market.sellUnknown(0, VIAL, 4L, 8_536, 33_804, 33_128);
        String settlementId = market.row(VIAL).settlementId;

        assertTrue(market.engine.qi(settlementId, Ah.TRANSFER,
            market.now + 5_000L, "test"));
        assertEquals(0L, market.net());
        assertTrue(market.engine.akb(market.now + 6_000L));
        assertEquals(-676L, market.net());
    }

    // ── fail-closed tax proofs ─────────────────────────────────────────────────────────────────

    @Test
    public void expectedTaxWithNoActualGapStaysUncounted()
    {
        Market market = new Market();
        market.prices.put(RUNE, 210L);
        // The frozen reference implies 104 gp of expected tax, but the actual Received equals the
        // gross execution, so nothing is proven.
        market.sellUnknown(0, RUNE, 26L, 210, 5_460, 5_460);

        Bi.Row row = market.row(RUNE);
        assertEquals(104L, row.expectedReferenceTaxGp);
        assertEquals(0L, row.inferredGeTaxGp);
        assertFalse(row.knownCostOnly);
        assertEquals(0L, row.realizedResultGp);
        assertEquals("expected/reference tax never affects Net", 0L, market.net());
    }

    @Test
    public void wrongGapFailsClosedWithoutInventingATax()
    {
        Market market = new Market();
        market.prices.put(RUNE, 100L);
        // The per-item rule says 200, but the observed gap is 300.
        market.sellUnknown(0, RUNE, 100L, 100, 10_000, 9_700);

        Bi.Row row = market.row(RUNE);
        assertEquals("the rule mismatch is not accepted", 0L, row.inferredGeTaxGp);
        assertFalse(row.knownCostOnly);
        assertNotEquals(-300L, market.net());
        assertNotEquals(-200L, market.net());
        assertEquals("the existing fail-closed path keeps the unexplained gap out of Net",
            0L, market.net());
        assertEquals("nothing is counted for an unexplained gap", 0, market.counted());
    }

    @Test
    public void nonUniformExecutionHasNoProvenTax()
    {
        Market market = new Market();
        market.prices.put(RUNE, 333L);
        market.sellUnknown(0, RUNE, 3L, 333, 1_000, 990);

        Bi.Row row = market.row(RUNE);
        assertEquals("a gross not divisible by the quantity proves no unit price", 0L,
            row.inferredGeTaxGp);
        assertFalse(row.knownCostOnly);
        assertEquals(0L, market.net());
    }

    // ── protection of the other laws ───────────────────────────────────────────────────────────

    @Test
    public void legacySyntheticTaxFlowIsNeverDoubled()
    {
        Market market = new Market();
        market.prices.put(VIAL, 8_536L);
        // A pre-6A.1B legacy settlement kept its own -99502 tax flow; it stays exactly as booked.
        market.engine.getActiveSession().kf(new Ac(market.now - 10_000L,
            null, Ai.TRADE, Aj.MARKET, "Grand Exchange", "Market", true,
            java.util.Arrays.asList(
                new Ab(CHAOS, "Chaos rune", -1_000L, 100, -1_000L, Av.GRAND_EXCHANGE),
                new Ab(COINS, "Coins", 980L, 1, 980L, Av.FACE_VALUE),
                new Ab(CostKind.GE_TAX_ITEM_ID, "GE sell tax", -20L, 1, -20L,
                    Av.FACE_VALUE)),
            Bd.CONFIRMED, "legacy booking", null), 500);
        long legacyNet = -1_000L + 980L - 20L;

        market.sellUnknown(0, VIAL, 4L, 8_536, 33_804, 33_128);

        assertEquals("both contributions are counted exactly once", legacyNet - 676L, market.net());
        Ac settlement = market.transaction(market.row(VIAL).settlementId);
        assertNoSyntheticTaxFlow(settlement);
        for (Ac transaction : market.transactions())
        {
            for (Ab itemFlow : transaction.getFlows())
            {
                if (itemFlow.itemId == CostKind.GE_TAX_ITEM_ID)
                {
                    assertEquals("the legacy tax flow is only the legacy one", -20L,
                        itemFlow.valueDelta);
                }
            }
        }
    }

    @Test
    public void partialBasisNetIsPinnedAndUntouched()
    {
        Market market = new Market();
        market.prices.put(RUNE, 100L);
        gain(market.engine, RUNE, 4L, 380L, market.now - 60_000L);
        market.sellUnknown(0, RUNE, 10L, 100, 1_000, 1_000);

        Bi.Row row = market.row(RUNE);
        assertEquals(Bi.Coverage.PARTIALLY_KNOWN, row.coverage);
        assertEquals(4L, MarketFacts.trackedQtyConsumed(MarketFacts.record(market.engine, row)));
        assertEquals(6L, MarketFacts.unknownQtyRealized(MarketFacts.record(market.engine, row)));
        assertEquals(380L, row.trackedBasisConsumedGp);
        assertEquals(400L, MarketFacts.knownProceedsGp(MarketFacts.record(market.engine, row)));
        assertEquals(600L, MarketFacts.unknownLiquidationGp(MarketFacts.record(market.engine, row)));
        assertEquals(20L, row.realizedResultGp);
        assertFalse("partial basis is never KNOWN_COST_ONLY", row.knownCostOnly);
        assertEquals("the known gain plus the known partial Result", 380L + 20L, market.net());
    }

    @Test
    public void trackedBasisCreatesNothingAndConsumesNothingForTheTax()
    {
        Market market = new Market();
        market.prices.put(VIAL, 8_536L);
        market.sellUnknown(0, VIAL, 4L, 8_536, 33_804, 33_128);

        assertEquals("no fake known coverage is created", 0L, EngineProbe.knownCoverageQty(market.engine, VIAL));
        assertEquals(0L, EngineProbe.knownCoverageBasisGp(market.engine, VIAL));
        Ac settlement = market.transaction(market.row(VIAL).settlementId);
        assertTrue(settlement.va().isEmpty());
        assertFalse(settlement.yi());
    }

    // ── persistence ────────────────────────────────────────────────────────────────────────────

    @Test
    public void provenTaxSurvivesSaveReloadAndUndoAfterReload()
    {
        Market market = new Market();
        market.prices.put(VIAL, 8_536L);
        market.sellUnknown(0, VIAL, 4L, 8_536, 33_804, 33_128);
        String settlementId = market.row(VIAL).settlementId;
        int transactions = market.transactions().size();

        SavedState state = market.engine.qm();
        Market restored = new Market();
        restored.prices.put(VIAL, 8_536L);
        restored.engine.restore(state, market.now + 1_000L);

        assertEquals("no second booking after reload", transactions,
            restored.engine.getActiveSession().getTransactions().size());
        Bi.Row row = restored.row(VIAL);
        assertEquals(settlementId, row.settlementId);
        assertTrue(row.knownCostOnly);
        assertEquals("Result stays unknown", -676L, row.realizedResultGp);
        assertEquals(-676L, restored.engine.getMetrics(market.now + 2_000L).net);

        assertTrue(restored.engine.qi(settlementId, Ah.REVENUE,
            market.now + 3_000L, "after reload"));
        assertEquals(33_128L + 33_804L, restored.engine.getMetrics(market.now + 4_000L).net);
        assertTrue(restored.engine.akb(market.now + 5_000L));
        assertEquals(-676L, restored.engine.getMetrics(market.now + 6_000L).net);
        assertTrue(restored.row(VIAL).knownCostOnly);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

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

    private static void assertNoSyntheticTaxFlow(Ac transaction)
    {
        assertNotNull(transaction);
        for (Ab flow : transaction.getFlows())
        {
            assertTrue("no synthetic GE tax flow may be booked",
                flow.itemId != CostKind.GE_TAX_ITEM_ID);
        }
    }

    private static void gain(Am engine, int item, long quantity, long value, long at)
    {
        engine.getActiveSession().kf(new Ac(at, null,
            Ai.GAIN, Aj.GENERIC, "", "Loot", true,
            Collections.singletonList(new Ab(item, name(item), quantity,
                (int) (quantity > 0L ? value / quantity : 0L), value,
                Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "", null), 500);
    }

    private static String name(int itemId)
    {
        switch (itemId)
        {
            case VIAL: return "Vial of blood";
            case AMETHYST_ARROW: return "Amethyst arrow";
            case CHICKEN: return "Cooked chicken";
            case RUNE: return "Nature rune";
            default: return "Item " + itemId;
        }
    }

    /** A real engine with per-item frozen references and offline GE offer observations. */
    private static final class Market
    {
        final Am engine;
        final Bj ledger = new Bj();
        final Map<Integer, Long> prices = new HashMap<>();
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
                    long unit = id == COINS ? 1L : prices.getOrDefault(id, 0L);
                    Av source = id == COINS ? Av.FACE_VALUE
                        : unit > 0L ? Av.GRAND_EXCHANGE : Av.UNPRICED;
                    flows.add(new Ab(id, id == COINS ? "Coins" : name(id), delta.getValue(),
                        (int) unit, delta.getValue() * unit, source));
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
            engine.ajl("Trading", Cx.AUTO, now);
            inventory.put(COINS, 1_000_000L);
        }

        /** Place a SELL offer for pre-existing (unknown-coverage) stock and collect its cash. */
        void sellUnknown(int slot, int item, long qty, long listedPrice, long gross, long received)
        {
            inventory.put(item, qty);
            engine.setBaseline(new Cc(inventory));
            offer(slot, SELLING, item, (int) qty, 0, (int) listedPrice, 0);
            inventory.put(item, 0L);
            settle();
            offer(slot, SOLD, item, (int) qty, (int) qty, (int) listedPrice, (int) gross);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + received);
            settle();
        }

        private void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent)
        {
            now += 5_000L;
            Bj.Transition transition = ledger.observe(
                new Bj.Snapshot(slot, state, item, total, traded, price, spent))
                .orElse(null);
            if (transition != null)
            {
                engine.abh(transition, name(item), now);
            }
        }

        private void settle()
        {
            now += 5_000L;
            engine.yz();
            Cc snapshot = new Cc(inventory);
            for (int i = 0; i < 3; i++)
            {
                engine.adj(snapshot, now);
                now += 1_000L;
            }
        }

        Bi.Row row(int itemId)
        {
            for (Bi.Row row : engine.ub())
            {
                if (row.itemId == itemId)
                {
                    return row;
                }
            }
            throw new AssertionError("no market row for " + itemId);
        }

        Ac transaction(String id)
        {
            Ad session = engine.getActiveSession();
            return session == null ? null : session.sw(id);
        }

        List<Ac> transactions()
        {
            Ad session = engine.getActiveSession();
            return session == null ? Collections.emptyList() : session.getTransactions();
        }

        int counted()
        {
            int counted = 0;
            for (Ac transaction : transactions())
            {
                if (transaction != null && transaction.isCounted())
                {
                    counted++;
                }
            }
            return counted;
        }

        long net()
        {
            return engine.getMetrics(now + 1_000L).net;
        }
    }
}
