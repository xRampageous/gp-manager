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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * PR1-PRE-R5C.2C.6A.1B presentation: a booked unknown-basis sale with a PROVEN GE tax shows the
 * tax as a Market answer row with its Net contribution, while exempt,
 * pre-change and corrected sales never show that row.
 */
public class UnknownBasisTaxReceiptTest
{
    private static final long T0 = System.currentTimeMillis() - 10 * 60_000L;
    private static final int COINS = ItemID.COINS;
    private static final int VIAL = ItemID.VIAL_BLOOD;
    private static final int CHICKEN = ItemID.COOKED_CHICKEN;
    private static final int RUNE = ItemID.NATURERUNE;
    private static final int CHAOS = ItemID.CHAOSRUNE;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void vialReceiptShowsTheAnswer() throws Exception
    {
        Market market = new Market();
        market.prices.put(VIAL, 8_536L);
        market.sellUnknown(0, VIAL, 4L, 8_536, 33_804, 33_128);
        MarketSettlementProjection.Row row = market.row(VIAL);

        List<String> lines = MarketText.marketHumanLines(row, false);
        assertTrue(lines.contains("Received|33,128 gp"));
        assertTrue(lines.contains("Received each|8,282 gp"));
        assertTrue(lines.contains("Previously counted|Unknown"));
        assertTrue(lines.contains("Result|\u2014"));
        assertTrue("the booked tax is the answer row",
            lines.contains("GE tax|" + Fmt.exactSigned(-676L) + " gp"));
        assertTrue(lines.contains("GE difference|" + Fmt.exactSigned(-336L) + " gp"));
        assertFalse("the main receipt never repeats the full proof",
            lines.stream().anyMatch(line -> line.startsWith("Gross sale"))
                || lines.stream().anyMatch(line -> line.startsWith("GE reference"))
                || lines.stream().anyMatch(line -> line.startsWith("Net reference")));
        assertFalse("no unexplained adjustment is invented for a proven tax",
            lines.stream().anyMatch(line -> line.startsWith("Adjustment")));
        assertEquals(Fmt.exactSigned(-676L) + " gp \u00b7 GE tax",
            LedgerPageProbe.netContributionText(row));

        LedgerData data = captureWithGroup(market, groupIdOf(market));
        SemanticFinancialProjection.Receipt receipt = receiptOfItem(data, VIAL);
        assertNotNull(receipt);
        String rowText = LedgerPageProbe.marketReceiptRowText(receipt, data.capturedAt);
        assertTrue(rowText.startsWith("Vial of blood  " + Fmt.times(4L) + "|Sold \u00b7 Result \u2014"));
        assertTrue(rowText.endsWith("|+33,128 gp"));

        LedgerPage page = openExactReceipt(market, VIAL);
        List<String> human = LedgerPageProbe.detailTexts(page);
        assertTrue(human.contains("Received") && human.contains("33,128 gp"));
        assertTrue(human.contains("GE tax")
            && human.contains(Fmt.exactSigned(-676L) + " gp"));
        assertFalse("the proof is not duplicated in the main receipt", human.contains("Gross sale"));
    }

    @Test
    public void ledgerMarketTotalCarriesTheProvenTaxAndReconciles()
    {
        Market market = new Market();
        market.prices.put(VIAL, 8_536L);
        market.sellUnknown(0, VIAL, 4L, 8_536, 33_804, 33_128);

        LedgerData data = LedgerData.capture(market.engine, market.now + 1_000L,
            LedgerData.Entry.current());
        assertEquals(-676L, data.net);
        assertEquals("the Ledger market total carries the tax", -676L, data.market.total);
        assertEquals(-676L, data.total.market);
        assertEquals("no Gains contribution is invented", 0L, data.total.gains);
        assertEquals("no Supplies/Losses contribution is invented", 0L, data.total.costs);
        assertTrue("gains + costs + market == net", data.reconciled);
        SemanticFinancialProjection.Group group = null;
        for (SemanticFinancialProjection.Group candidate : data.market.groups)
        {
            if (candidate.market)
            {
                group = candidate;
            }
        }
        assertNotNull(group);
        assertEquals(-676L, group.value);
        assertEquals(SemanticFinancialProjection.Coverage.COMPLETE, group.coverage);
        assertFalse("a proven tax is not a review row", group.reviewRequired);

        LiveSnapshot live = LiveSnapshot.capture(market.engine, market.now + 1_000L, null);
        assertEquals(-676L, live.marketResult);
        assertEquals(-676L, live.net);
        assertEquals(0L, live.gains);
        assertEquals(0L, live.loss);
        assertEquals(0L, live.supplies);
        assertEquals(0L, live.costs);
    }

    @Test
    public void exemptAndKnownBasisReceiptsNeverShowAMainTaxRow()
    {
        Market exempt = new Market();
        exempt.prices.put(CHICKEN, 66L);
        exempt.sellUnknown(0, CHICKEN, 5L, 69, 315, 315);
        List<String> exemptLines = MarketText.marketHumanLines(exempt.row(CHICKEN), false);
        assertFalse(exemptLines.stream().anyMatch(line -> line.startsWith("GE tax")));
        assertEquals("0 \u00b7 not counted",
            LedgerPageProbe.netContributionText(exempt.row(CHICKEN)));

        Market known = new Market();
        known.prices.put(RUNE, 420L);
        gain(known.engine, RUNE, 13L, 5_000L, known.now - 60_000L);
        known.sellUnknown(0, RUNE, 13L, 420, 5_460, 5_356);
        List<String> knownLines = MarketText.marketHumanLines(known.row(RUNE), false);
        assertTrue("a full result keeps its Result row", knownLines.contains("Result|+356 gp"));
        assertFalse("the tax is already inside Received for a full result",
            knownLines.stream().anyMatch(line -> line.startsWith("GE tax")));
        assertEquals("+356 gp \u00b7 Result", LedgerPageProbe.netContributionText(known.row(RUNE)));
    }

    @Test
    public void correctedSaleLosesTheTaxRowUntilUndo()
    {
        Market market = new Market();
        market.prices.put(VIAL, 8_536L);
        market.sellUnknown(0, VIAL, 4L, 8_536, 33_804, 33_128);
        String settlementId = market.row(VIAL).settlementId;

        assertTrue(market.engine.correctTransaction(settlementId, Correction.IGNORE,
            market.now + 5_000L, "test"));
        MarketSettlementProjection.Row corrected = market.row(VIAL);
        List<String> correctedLines = MarketText.marketHumanLines(corrected, true);
        assertFalse("the GE tax row disappears while corrected",
            correctedLines.stream().anyMatch(line -> line.startsWith("GE tax")));
        assertEquals(Fmt.exactSigned(0L) + " gp \u00b7 corrected",
            LedgerPageProbe.netContributionText(corrected));

        assertTrue(market.engine.undoLastCorrection(market.now + 6_000L));
        MarketSettlementProjection.Row undone = market.row(VIAL);
        assertTrue(MarketText.marketHumanLines(undone, false)
            .contains("GE tax|" + Fmt.exactSigned(-676L) + " gp"));
        assertEquals(Fmt.exactSigned(-676L) + " gp \u00b7 GE tax",
            LedgerPageProbe.netContributionText(undone));
    }

    @Test
    public void preChangeUncountedSaleKeepsTheReceiptHonest()
    {
        GeRecord record = new GeRecord("offer-old", 0, GeRecord.Side.SELL, CHAOS,
            "Chaos rune", 100L, 121L, T0, "session-a");
        record.setStage(GeRecord.Stage.CLOSED);
        record.setConfidence(GeRecord.Confidence.CONFIRMED);
        record.setCapturedQty(100L);
        record.setFilledQty(100L);
        record.setSpentGp(12_200L);
        record.setSettledQty(100L);
        record.setSettledExecutionGp(12_200L);
        record.setSettledCashGp(12_000L);
        record.setConsumedTrackedQty(0L);
        record.setConsumedTrackedBasisGp(0L);
        record.setBasisUnitPrice(121L);
        record.setBasisSource(PriceSource.GRAND_EXCHANGE.name());
        record.setBasisCapturedAtEpochMillis(T0);
        record.setSettlementId("old-settlement");
        // The pre-6A.1B booking shape: uncounted, item flow at the frozen reference.
        Transaction settlement = new Transaction(T0 + 1_000L, null, TransactionType.TRADE,
            Context.MARKET, "Grand Exchange", "Market", false,
            java.util.Arrays.asList(
                new Flow(CHAOS, "Chaos rune", -100L, 121, -12_100L,
                    PriceSource.GRAND_EXCHANGE),
                new Flow(COINS, "Coins", 12_000L, 1, 12_000L, PriceSource.FACE_VALUE)),
            ClassificationConfidence.CONFIRMED, "", null);

        MarketSettlementProjection.Row row = MarketSettlementProjection.rows(
            Collections.singletonList(record), id -> settlement, null).get(0);
        assertEquals(MarketSettlementProjection.Coverage.FULLY_UNKNOWN, row.coverage);
        assertEquals("technical evidence may still show the historical tax", 200L,
            row.inferredGeTaxGp);
        assertFalse("a pre-change sale is not a booked KNOWN_COST_ONLY state",
            row.knownCostOnly);
        assertEquals(0L, row.realizedResultGp);
        List<String> lines = MarketText.marketHumanLines(row, false);
        assertFalse("no main-receipt GE tax row as if that cost were booked",
            lines.stream().anyMatch(line -> line.startsWith("GE tax")));
        assertTrue(lines.contains("Result|\u2014"));
        assertEquals("0 \u00b7 not counted", LedgerPageProbe.netContributionText(row));

        SemanticFinancialProjection.Result projection = SemanticFinancialProjection.capture(
            Collections.singletonList(settlement), Collections.singletonList(row), "session-a", null);
        assertEquals("the historical tax evidence never enters the market total",
            0L, SemanticFinancialProjection.summarize(projection.groups).market);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static void gain(Engine engine, int item, long quantity, long value, long at)
    {
        engine.getActiveSession().addTransaction(new Transaction(at, null,
            TransactionType.GAIN, Context.GENERIC, "", "Loot", true,
            Collections.singletonList(new Flow(item, name(item), quantity,
                (int) (quantity > 0L ? value / quantity : 0L), value,
                PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "", null), 500);
    }

    private static String name(int itemId)
    {
        switch (itemId)
        {
            case VIAL: return "Vial of blood";
            case CHICKEN: return "Cooked chicken";
            case RUNE: return "Nature rune";
            case CHAOS: return "Chaos rune";
            default: return "Item " + itemId;
        }
    }

    private static String groupIdOf(Market market)
    {
        LedgerData data = LedgerData.capture(market.engine, market.now + 1_000L,
            LedgerData.Entry.current());
        for (SemanticFinancialProjection.Group group : data.market.groups)
        {
            if (group.market)
            {
                return group.semanticGroupId;
            }
        }
        throw new AssertionError("no market group");
    }

    private static LedgerData captureWithGroup(Market market, String groupId)
    {
        LedgerData data = LedgerData.capture(market.engine, market.now + 1_000L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.SUPPLIES, "", null, null, groupId, null));
        assertNotNull(data.detail);
        return data;
    }

    private static SemanticFinancialProjection.Receipt receiptOfItem(LedgerData data, int itemId)
    {
        for (SemanticFinancialProjection.Receipt receipt : data.detail.receipts)
        {
            if (receipt.itemId == itemId)
            {
                return receipt;
            }
        }
        return null;
    }

    private static LedgerPage openExactReceipt(Market market, int itemId) throws Exception
    {
        LedgerData grouped = captureWithGroup(market, groupIdOf(market));
        SemanticFinancialProjection.Receipt receipt = receiptOfItem(grouped, itemId);
        assertNotNull(receipt);
        LedgerData data = LedgerData.capture(market.engine, market.now + 1_000L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.SUPPLIES, "", receipt.transactionId,
                receipt.contributionId, grouped.detail.group.semanticGroupId, null));
        assertNotNull(data.detail);
        assertNotNull(data.detail.exact);
        return onEdt(() ->
        {
            LedgerPage page = new LedgerPage(new LedgerNoop(), id -> null);
            page.apply(data);
            return page;
        });
    }

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

    /** A real engine with per-item frozen references and offline GE offer observations. */
    private static final class Market
    {
        final Engine engine;
        final OfferLedger ledger = new OfferLedger();
        final Map<Integer, Long> prices = new HashMap<>();
        final Map<Integer, Long> inventory = new HashMap<>();
        long now = T0;

        Market()
        {
            engine = new Engine(deltas ->
            {
                List<Flow> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> delta : deltas.entrySet())
                {
                    int id = delta.getKey();
                    long unit = id == COINS ? 1L : prices.getOrDefault(id, 0L);
                    PriceSource source = id == COINS ? PriceSource.FACE_VALUE
                        : unit > 0L ? PriceSource.GRAND_EXCHANGE : PriceSource.UNPRICED;
                    flows.add(new Flow(id, id == COINS ? "Coins" : name(id), delta.getValue(),
                        (int) unit, delta.getValue() * unit, source));
                }
                return flows;
            }, new TransactionClassifier(), new GpManagerConfig()
            {
                @Override
                public ReceiptRetentionPeriod receiptRetentionDays()
                {
                    return ReceiptRetentionPeriod.DAYS_365;
                }
            });
            engine.startCustomSession("Trading", SessionMode.AUTO, now);
            inventory.put(COINS, 1_000_000L);
        }

        void sellUnknown(int slot, int item, long qty, long listedPrice, long gross, long received)
        {
            inventory.put(item, qty);
            engine.setBaseline(new ContainerSnapshot(inventory));
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
            OfferLedger.Transition transition = ledger.observe(
                new OfferLedger.Snapshot(slot, state, item, total, traded, price, spent))
                .orElse(null);
            if (transition != null)
            {
                engine.noteGeOfferObservation(transition, name(item), now);
            }
        }

        private void settle()
        {
            now += 5_000L;
            engine.markInventoryDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            for (int i = 0; i < 3; i++)
            {
                engine.processIfDirty(snapshot, now);
                now += 1_000L;
            }
        }

        MarketSettlementProjection.Row row(int itemId)
        {
            for (MarketSettlementProjection.Row row : engine.getMarketSettlements())
            {
                if (row.itemId == itemId)
                {
                    return row;
                }
            }
            throw new AssertionError("no market row for " + itemId);
        }
    }

    private static final class LedgerNoop implements LedgerPage.Actions
    {
        @Override public void openScopeMenu(javax.swing.JComponent anchor) { }
        @Override public void costViewChanged(LedgerData.CostView view) { }
        @Override public void searchChanged(String text) { }
        @Override public LedgerData.CorrectionPreview preview(String id,
            Correction correction)
        {
            return null;
        }
        @Override public LedgerPage.CorrectionOutcome correct(String id,
            Correction correction, long previewRevision)
        {
            return LedgerPage.CorrectionOutcome.REFUSED;
        }
        @Override public void split(String id) { }
        @Override public void undoCorrection() { }
        @Override public void decideAll(ReviewDecision decision) { }
        @Override public void refresh() { }
    }
}
