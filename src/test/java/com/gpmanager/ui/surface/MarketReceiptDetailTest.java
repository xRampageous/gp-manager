package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2C.3: the human Level-3 Market receipt speaks in collected-quantity cash movement and
 * frozen comparison values, never in audit language; the exact proof stays one click deeper.
 */
public class MarketReceiptDetailTest
{
    private static final long T0 = System.currentTimeMillis() - 10 * 60_000L;
    private static final int COINS = 995;
    private static final int CHAOS = 562;
    private static final String SETTLED_THROUGH = "Settled through a trade.";

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    // ── realized SELL / BUY ────────────────────────────────────────────────────────────────────

    @Test
    public void realizedSellUsesCollectedScopeAndHumanLabels() throws Exception
    {
        Market market = new Market();
        market.sell(0, CHAOS, 30L, 104, 3_120L, 3_060L);
        LedgerPage page = openExactReceipt(market, receipt -> receipt.itemId == CHAOS);
        List<String> texts = LedgerPageProbe.detailTexts(page);

        assertTrue(texts.contains("Chaos rune  " + Fmt.times(30L)));
        assertTrue("the selected row carries the collected cash", texts.contains(Fmt.signed(3_060L)));
        assertTrue(texts.contains("Received"));
        assertTrue(texts.contains(Fmt.exact(3_060L) + " gp"));
        assertTrue("the actual after-tax settled unit value is shown when exact",
            texts.contains("Received each") && texts.contains("102 gp"));
        assertTrue(texts.contains("Previously counted"));
        assertTrue(texts.contains(Fmt.exact(3_150L) + " gp"));
        assertTrue(texts.contains("Result"));
        assertTrue(texts.contains(Fmt.ru(-90L) + " gp"));
        assertTrue(texts.contains("GE difference"));
        assertTrue(texts.contains(Fmt.ru(-30L) + " gp"));
        assertFalse("the full proof is not repeated in the main receipt",
            texts.contains("Gross sale"));
        assertFalse(texts.contains("GE tax (inferred)"));
        assertFalse(texts.contains("GE reference"));
        String sold = "Sold \u00b7 " + Fmt.age(Math.max(0L, market.now + 1_000L
            - market.engine.ub().get(0).settlementAtEpochMillis));
        assertTrue("the settlement-relative age is shown", texts.stream().anyMatch(text -> text.contains(sold)));
        assertFalse("a full fill needs no fill-state row", texts.contains("Filled 30 / 30"));
        assertFalse("the generic WHY is suppressed for a clean realized trade",
            texts.contains(SETTLED_THROUGH));
        assertFalse(texts.contains("Acquired at"));
        assertFalse(texts.contains("Seen value"));
        assertFalse(texts.contains("Executed GP"));
        assertFalse(texts.contains("Observed settlement"));
        assertFalse(texts.contains("Canonical realized result"));
        assertFalse(texts.contains("Underlying canonical receipts"));
        assertFalse(texts.contains("Side / state"));
        assertFalse(texts.contains("Market detail"));
        assertFalse(texts.contains("Selected-group total"));
    }

    @Test
    public void lawRuneReceiptShowsTheAnswer() throws Exception
    {
        Market market = new Market();
        market.quote[0] = 121;
        market.sellUnknownGross(0, CHAOS, 100L, 122, 12_200L, 12_000L);
        LedgerPage page = openExactReceipt(market, receipt -> receipt.itemId == CHAOS);
        List<String> texts = LedgerPageProbe.detailTexts(page);

        assertTrue(texts.contains("Received") && texts.contains("12,000 gp"));
        assertTrue("120 gp each is the actual after-tax settled unit value",
            texts.contains("Received each") && texts.contains("120 gp"));
        assertTrue(texts.contains("Previously counted") && texts.contains("Unknown"));
        assertTrue(texts.contains("Result") && texts.contains("\u2014"));
        assertTrue("the booked proven tax is the answer row, not the full proof",
            texts.contains("GE tax") && texts.contains(Fmt.ru(-200L) + " gp"));
        assertTrue(texts.contains("GE difference")
            && texts.contains(Fmt.ru(100L) + " gp"));
        assertFalse("the main receipt never repeats the full proof",
            texts.contains("Gross sale")
                || texts.contains("GE reference") || texts.contains("Net reference"));
    }

    @Test
    public void realizedBuyUsesBoughtAtAndSpentWithoutAnInventedAdjustment() throws Exception
    {
        Market market = new Market();
        market.buy(0, CHAOS, 30L, 102, 3_060L);
        LedgerPage page = openExactReceipt(market, receipt -> receipt.itemId == CHAOS);
        List<String> texts = LedgerPageProbe.detailTexts(page);

        assertTrue(texts.contains("Chaos rune  " + Fmt.times(30L)));
        assertTrue("the selected row carries the spend", texts.contains(Fmt.signed(-3_060L)));
        assertTrue(texts.contains("Spent"));
        assertTrue(texts.contains(Fmt.exact(3_060L) + " gp"));
        assertTrue(texts.contains("Tracked value"));
        assertTrue(texts.contains("GE reference"));
        assertTrue(texts.contains("105 gp ea"));
        assertTrue(texts.contains("GE difference"));
        assertTrue(texts.contains("Result"));
        assertFalse("current BUY semantics have no adjustment", texts.contains("Adjustment"));
        String bought = "Bought \u00b7 " + Fmt.age(Math.max(0L, market.now + 1_000L
            - market.engine.ub().get(0).settlementAtEpochMillis));
        assertTrue("the BUY age is settlement-relative", texts.stream().anyMatch(text -> text.contains(bought)));
    }

    @Test
    public void partialFillRendersCompactFillStateWithCollectedScope() throws Exception
    {
        Market market = new Market();
        market.sellPartial(0, CHAOS, 200L, 30L, 104, 3_120L, 3_060L);
        LedgerPage page = openExactReceipt(market, receipt -> receipt.itemId == CHAOS);
        List<String> texts = LedgerPageProbe.detailTexts(page);

        assertTrue(texts.contains("Filled 30 / 200"));
        assertTrue(texts.contains("Received"));
        assertTrue(texts.contains("Received each"));
        assertTrue(texts.contains("Result"));
        assertFalse(texts.contains("GE reference"));
        assertTrue(texts.contains("GE difference"));
    }

    // ── quantity scope, stored basis, rounding, reconciliation ────────────────────────────────

    @Test
    public void filledMoreThanCollectedUsesCollectedScopeForEveryFinancialLine()
    {
        Bi.Row row = syntheticRow(200L, 50L, 5_000L, 30L, 3_120L, 3_060L, 105L);
        List<String> lines = Dc.zs(row, false);

        assertEquals(30L, row.settledQty);
        assertEquals("realized gross is settlement minus adjustment", 3_120L,
            row.observedSettlementGp - row.settlementAdjustmentGp);
        assertEquals(105L, row.basisUnitPrice);
        assertEquals("the actual after-tax unit value uses the collected scope",
            102L, row.receivedEachGp);
        assertTrue(lines.contains("Filled 50 / 200 \u00b7 collected 30"));
        assertTrue(lines.contains("Received|" + Fmt.exact(3_060L) + " gp"));
        assertTrue(lines.contains("Received each|" + Fmt.exact(102L) + " gp"));
        assertTrue(lines.contains("Result|" + Fmt.ru(-90L) + " gp"));
        assertFalse("the aggregate 5,000 / 50 unit price must never appear",
            lines.contains("GE reference|100 gp ea"));
        assertFalse("the gross proof never shows",
            lines.stream().anyMatch(line -> line.startsWith("Gross sale")));
        assertFalse("a realized receipt never shows Acquired at", lines.contains("Acquired at"));
        assertEquals("the tax-adjusted reference uses the collected scope, never a gross aggregate",
            Fmt.exact(3_090L), Fmt.exact(row.geReferenceGp));
        assertEquals("the aggregate 5,000 / 50 reference must never be used",
            3_090L, row.geReferenceGp);
    }

    @Test
    public void exactDivisionRendersWithoutApproximationMarker()
    {
        Bi.Row row = syntheticRow(30L, 30L, 3_120L, 30L, 3_120L, 3_060L, 105L);
        List<String> lines = Dc.zs(row, false);
        assertTrue(lines.contains("Received each|" + Fmt.exact(102L) + " gp"));
        assertTrue(lines.contains("GE difference|" + Fmt.ru(-30L) + " gp"));
    }

    @Test
    public void sapphireTaxBreakdownUsesObservedCashOnlyOnce()
    {
        Bi.Row row = syntheticRow(26L, 26L, 5_460L,
            26L, 5_460L, 5_356L, 207L);
        List<String> lines = Dc.zs(row, false);

        assertEquals(104L, row.inferredGeTaxGp);
        assertEquals("5,356 / 26 is exactly 206", 206L, row.receivedEachGp);
        assertTrue(lines.contains("Received|5,356 gp"));
        assertTrue(lines.contains("Received each|206 gp"));
        assertTrue(lines.contains("GE difference|" + Fmt.ru(78L) + " gp"));
        assertFalse("tax is not added as another financial cost",
            lines.contains("Adjustment|" + Fmt.ru(-104L) + " gp"));
        assertFalse("the gross-to-net proof is not repeated in the main receipt",
            lines.stream().anyMatch(line -> line.startsWith("Gross sale")));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("GE tax")));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("GE reference")));
    }

    @Test
    public void unexplainedGrossToCashGapRemainsAdjustment()
    {
        Bi.Row row = syntheticRow(26L, 26L, 5_460L,
            26L, 5_460L, 5_355L, 207L);
        List<String> lines = Dc.zs(row, false);

        assertEquals(0L, row.inferredGeTaxGp);
        assertTrue(lines.contains("Adjustment|" + Fmt.ru(-105L) + " gp"));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("GE tax")));
    }

    @Test
    public void nonEvenDivisionKeepsTheExactCollectedScopeValues()
    {
        Bi.Row row = syntheticRow(30L, 30L, 3_121L, 30L, 3_121L, 3_061L, 105L);
        List<String> lines = Dc.zs(row, false);
        assertTrue(lines.contains("Received|" + Fmt.exact(3_061L) + " gp"));
        assertFalse("a non-integral actual unit value is never silently rounded",
            lines.stream().anyMatch(line -> line.startsWith("Received each")));
        assertEquals("the exact tax-adjusted reference aggregate stays available",
            Fmt.exact(105L * 30L - 60L) + " gp across " + Fmt.exact(row.settledQty) + " collected",
            Fmt.exact(row.geReferenceGp) + " gp across " + Fmt.exact(row.settledQty)
                + " collected");
        assertTrue(lines.contains("GE difference|" + Fmt.ru(3_061L - 3_090L) + " gp"));
    }

    @Test
    public void approximateUnitPriceKeepsTheExactAggregateInTheRenderedTooltip() throws Exception
    {
        Market market = new Market();
        market.sell(0, CHAOS, 30L, 104, 3_121L, 3_061L);
        LedgerPage page = openExactReceipt(market, receipt -> receipt.itemId == CHAOS);

        assertTrue("the exact tax-adjusted reference aggregate stays reachable in the tooltip",
            LedgerPageProbe.detailTooltips(page).stream().anyMatch(tip ->
                tip.contains("3,090 gp across 30 collected")));
        assertTrue(LedgerPageProbe.detailTexts(page).contains("GE difference"));
    }

    @Test
    public void basisUnavailableOmitsTheReferenceAndUnavailableResultNeverRendersFakeZero()
    {
        Aa record = syntheticRecord(30L, 30L, 3_120L, 30L, 3_120L, 3_060L, 0L, "");
        Bi.Row row = MarketFacts.row(record);
        List<String> lines = Dc.zs(row, false);

        assertTrue("no fabricated GE reference without a stored unit basis",
            lines.stream().noneMatch(line -> line.startsWith("GE reference|")));
        assertTrue("an unresolvable canonical result stays unavailable, never zero",
            lines.contains("Result|\u2014"));
        assertFalse(lines.contains("Result|0 gp"));
    }

    @Test
    public void collectedUnknownSaleKeepsCashVisibleAndResultUnavailable()
    {
        Market market = new Market();
        market.sellUnknown(0, CHAOS, 15L, 12, 180L);
        Ao data = captureWithGroup(market, groupIdOf(market));
        Bi.Row row = market.engine.ub().get(0);
        assertEquals(Bi.Coverage.FULLY_UNKNOWN, row.coverage);
        assertTrue(Dc.zs(row, false).contains("Received|180 gp"));
        assertTrue(Dc.zs(row, false).contains("Result|\u2014"));
        for (Br.Receipt receipt : data.detail.receipts)
        {
            if (receipt.marketSettlement != null)
            {
                String text = LedgerPageProbe.marketReceiptRowText(receipt, data.capturedAt);
                assertTrue(text.contains("Sold \u00b7 Result \u2014"));
                assertTrue(text.endsWith("|+180 gp"));
                return;
            }
        }
        throw new AssertionError("market receipt not found");
    }

    @Test
    public void explicitUnknownSaleCorrectionKeepsPriorBasisUnknown()
    {
        Market market = new Market();
        market.sellUnknown(0, CHAOS, 15L, 12, 180L);
        String settlementId = market.engine.ub().get(0).settlementId;
        assertTrue(market.engine.qi(settlementId, Ah.REVENUE,
            market.now, "owner decision"));

        Bi.Row row = market.engine.ub().get(0);
        List<String> lines = Dc.zs(row, true);
        assertTrue(row.manualFinancialResult);
        assertTrue(lines.contains("Previously counted|Unknown"));
        assertTrue(lines.contains("Result|" + Fmt.ru(row.realizedResultGp)
            + " gp \u00b7 Corrected"));
    }

    @Test
    public void fullSellReconcilesExactlyOverTheCollectedQuantity()
    {
        Bi.Row row = syntheticRow(30L, 30L, 3_120L, 30L, 3_120L, 3_060L, 105L);
        assertEquals("tracked basis consumed + Result == observed settlement",
            row.observedSettlementGp,
            row.trackedBasisConsumedGp + row.realizedResultGp);
    }

    // ── corrected result ───────────────────────────────────────────────────────────────────────

    @Test
    public void correctedResultUsesCanonicalValueAndCarriesMarkerWithoutMutatingEvidence()
        throws Exception
    {
        Market market = new Market();
        market.sell(0, CHAOS, 30L, 104, 3_120L, 3_060L);
        String settlementId = market.engine.ub().get(0).settlementId;
        market.engine.qi(settlementId, Ah.IGNORE, market.now, "test");

        LedgerPage page = openExactReceipt(market, receipt -> receipt.itemId == CHAOS);
        List<String> texts = LedgerPageProbe.detailTexts(page);

        assertTrue("the corrected canonical result is shown",
            texts.contains(Fmt.ru(0L) + " gp \u00b7 Corrected"));
        assertTrue("proven evidence is not rewritten to force reconciliation",
            texts.contains("Received") && texts.contains(Fmt.exact(3_060L) + " gp"));
        assertTrue(texts.contains("Received each") && texts.contains("102 gp"));
        assertTrue(texts.contains("GE difference"));
        assertFalse("the proof is not duplicated in the main receipt",
            texts.contains("Gross sale") || texts.contains("GE tax"));
        assertTrue(LedgerPageProbe.detailTooltips(page).contains("Result includes a manual correction."));
    }

    // ── timestamp ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void saleProceedsHelpExplainsReceivedVersusResult() throws Exception
    {
        Market market = new Market();
        market.sell(0, CHAOS, 30L, 104, 3_120L, 3_060L);
        LedgerPage page = openExactReceipt(market, receipt -> receipt.itemId == CHAOS);
        assertTrue(LedgerPageProbe.detailTooltips(page).contains(
            "Received is cash from selling owned items; the GE tax row and the Result affect Net."));
    }

    @Test
    public void buySpendHelpExplainsSpentVersusResult() throws Exception
    {
        Market market = new Market();
        market.buy(0, CHAOS, 30L, 102, 3_060L);
        LedgerPage page = openExactReceipt(market, receipt -> receipt.itemId == CHAOS);
        assertTrue(LedgerPageProbe.detailTooltips(page).contains(
            "Spent is cash used to acquire owned items; only Result affects Net."));
    }

    @Test
    public void realizedAgeUsesSettlementTimeNotPlacementTime() throws Exception
    {
        Market market = new Market();
        market.sellAfterDelay(0, CHAOS, 30L, 104, 3_120L, 3_060L, 60 * 60_000L);
        Ao data = captureWithGroup(market, groupIdOf(market));
        Bi.Row row = data.zu(data.detail.group.marketPresentationId);
        assertNotNull(row);
        assertTrue("settlement time is retained by the read model", row.settlementAtEpochMillis > 0L);
        assertTrue("placement and settlement are materially apart",
            row.settlementAtEpochMillis - row.timestampEpochMillis >= 60 * 60_000L);

        LedgerPage page = openExactReceipt(market, receipt -> receipt.itemId == CHAOS);
        String expected = "Sold \u00b7 " + Fmt.age(Math.max(0L,
            data.capturedAt - row.settlementAtEpochMillis));
        assertTrue(LedgerPageProbe.detailTexts(page).stream().anyMatch(text -> text.contains(expected)));
        String placed = "Sold \u00b7 " + Fmt.age(Math.max(0L, data.capturedAt - row.timestampEpochMillis));
        assertFalse("placement age must not be labelled as the Sold age",
            LedgerPageProbe.detailTexts(page).stream().anyMatch(text -> text.contains(placed)));
    }

    // ── pending ────────────────────────────────────────────────────────────────────────────────

    @Test
    public void pendingOrderFakesNoRealizedEconomicsAndHasNoSettlementReceipt()
    {
        Market market = new Market();
        market.pendingSell(0, CHAOS, 200L, 104);
        Ao data = Ao.capture(market.engine, market.now + 1_000L,
            Ao.Entry.current());
        Br.Group group = marketGroup(data);
        assertNotNull(group);
        assertFalse("pending stays incomplete coverage",
            group.coverage == Br.Coverage.COMPLETE);

        Ao grouped = captureWithGroup(market, group.semanticGroupId);
        assertTrue("no settlement receipt exists before collection", grouped.detail.receipts.isEmpty());
        Bi.Row row = grouped.zu(group.marketPresentationId);
        assertNotNull(row);
        assertEquals("the human Market block shows only the fill state",
            Collections.singletonList("Filled 0 / 200"), Dc.zs(row, false));
    }

    // ── disclosure / selected-group total ──────────────────────────────────────────────────────

    @Test
    public void sapphireAuditReceiptSeparatesCashReferenceAndBasis() throws Exception
    {
        Market market = new Market();
        market.quote[0] = 2_802;
        market.sell(0, CHAOS, 50L, 2_802, 133_450L, 130_800L);
        LedgerPage page = openExactReceipt(market, receipt -> receipt.itemId == CHAOS);

        List<String> lines = Dc.zs(
            market.engine.ub().get(0), false);
        assertTrue(lines.contains("Received|130,800 gp"));
        assertTrue("130,800 / 50 is exactly 2,616", lines.contains("Received each|2,616 gp"));
        assertTrue(lines.contains("Previously counted|140,100 gp"));
        assertTrue(lines.contains("GE difference|" + Fmt.ru(-6_500L) + " gp"));
        assertFalse("the main receipt never repeats the full cash/reference proof",
            lines.stream().anyMatch(line -> line.startsWith("Gross sale")));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("GE tax")));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("GE reference")));
    }

    @Test
    public void redundantSelectedGroupTotalIsHiddenForASingleReceiptGroup() throws Exception
    {
        Market market = new Market();
        market.engine.getActiveSession().kf(new Ac(market.now - 5_000L, null,
            Ai.GAIN, Aj.GENERIC, "", "Chaos rune", true,
            Collections.singletonList(new Ab(CHAOS, "Chaos rune", 1L, 105, 105L)),
            Bd.CONFIRMED, "", null), 100);
        Ao base = Ao.capture(market.engine, market.now + 1_000L,
            Ao.Entry.current());
        Br.Group gain = null;
        for (Br.Group group : base.gains.groups)
        {
            if (group.primaryName.equals("Chaos rune"))
            {
                gain = group;
            }
        }
        assertNotNull(gain);
        LedgerPage page = openExactReceiptForGroup(market, gain.semanticGroupId);
        assertFalse(LedgerPageProbe.detailTexts(page).contains("Selected-group total"));
    }

    @Test
    public void noAcquiredAtLabelExistsInTheMarketReceiptPath()
    {
        assertTrue(Dc.zs(
            syntheticRow(30L, 30L, 3_120L, 30L, 3_120L, 3_060L, 105L), false)
            .stream().noneMatch(line -> line.startsWith("Acquired at")));
        assertFalse(Dc.zs(
            syntheticRow(200L, 50L, 5_000L, 30L, 3_120L, 3_060L, 105L), false)
            .stream().anyMatch(line -> line.contains("Acquired")));
    }

    @Test
    public void orderStateLabelsMatchTheAcceptedWording()
    {
        Aa pending = syntheticRecord(11L, 0L, 0L, 0L, 0L, 0L, 0L, "");
        assertEquals("Offering",
            Dc.acp(MarketFacts.row(pending)));

        Aa partial = syntheticRecord(11L, 5L, 35L, 0L, 0L, 0L, 0L, "");
        assertEquals("Part sold 5/11 \u00b7 Pending",
            Dc.acp(MarketFacts.row(partial)));

        Aa canceled = syntheticRecord(11L, 0L, 0L, 0L, 0L, 0L, 0L, "");
        canceled.setOfferState("CANCELLED_SELL");
        canceled.setReturnedQty(11L);
        assertEquals("Returned \u00b7 Canceled",
            Dc.acp(MarketFacts.row(canceled)));
    }

    @Test
    public void unknownBankStockKeepsResultUnavailable()
    {
        Aa record = syntheticRecord(50L, 50L, 133_450L, 50L, 133_450L,
            130_800L, 2_802L, "GRAND_EXCHANGE");
        record.setConsumedTrackedQty(0L);
        record.setConsumedTrackedBasisGp(0L);
        Bi.Row row = MarketFacts.row(record);

        assertEquals(Bi.Coverage.FULLY_UNKNOWN, row.coverage);
        assertEquals("the frozen reference still supports a tax-adjusted comparison",
            137_300L, row.geReferenceGp);
        assertEquals(-6_500L, row.geDifferenceGp);
        List<String> lines = Dc.zs(row, false);
        assertTrue(lines.contains("Previously counted|Unknown"));
        assertTrue(lines.contains("Result|\u2014"));
        assertFalse(lines.contains("Result|0 gp"));
    }

    @Test
    public void readingTheHumanReceiptNeverMutatesCanonicalTruth() throws Exception
    {
        Market market = new Market();
        market.sell(0, CHAOS, 30L, 104, 3_120L, 3_060L);
        Ac settlement = market.engine.getActiveSession().getTransactions().get(0);
        long netBefore = market.engine.getMetrics(market.now).net;
        long transactionBefore = settlement.getNet();

        openExactReceipt(market, receipt -> receipt.itemId == CHAOS);

        assertEquals(netBefore, market.engine.getMetrics(market.now).net);
        assertEquals(transactionBefore, settlement.getNet());
        assertEquals("the frozen basis is untouched by reading the human receipt", 105L,
            market.engine.ub().get(0).basisUnitPrice);
    }

    @Test
    public void schemaAndValuationPolicyRemainUntouched()
    {
        assertEquals(108, SavedState.CURRENT_SCHEMA_VERSION);
        assertEquals("RuneLite market", Av.GRAND_EXCHANGE.toString());
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Bi.Row syntheticRow(long offered, long filled, long spent,
        long settled, long settledExecution, long settledCash, long basisUnitPrice)
    {
        return Bi.rows(Collections.singletonList(syntheticRecord(offered, filled,
            spent, settled, settledExecution, settledCash, basisUnitPrice, "GRAND_EXCHANGE")),
            id -> syntheticSettlement(settledCash, basisUnitPrice * settled), null).get(0);
    }

    private static Aa syntheticRecord(long offered, long filled, long spent, long settled,
        long settledExecution, long settledCash, long basisUnitPrice, String basisSource)
    {
        Aa record = new Aa("offer-1", 0, Aa.Side.SELL, CHAOS,
            "Chaos rune", offered, 104L, T0, "session");
        record.setStage(Aa.Stage.OPEN);
        record.setConfidence(Aa.Confidence.CONFIRMED);
        record.setCapturedQty(offered);
        record.setFilledQty(filled);
        record.setSpentGp(spent);
        record.setSettledQty(settled);
        record.setSettledExecutionGp(settledExecution);
        record.setSettledCashGp(settledCash);
        // Model a fully known sale: the settled quantity carried known tracked coverage.
        record.setConsumedTrackedQty(settled);
        record.setConsumedTrackedBasisGp(Math.max(0L, basisUnitPrice) * settled);
        if (basisUnitPrice > 0L && !basisSource.isEmpty())
        {
            record.setBasisUnitPrice(basisUnitPrice);
            record.setBasisSource(basisSource);
            record.setBasisCapturedAtEpochMillis(T0);
        }
        record.setSettlementId("settlement-1");
        return record;
    }

    /** A correction-aware canonical settlement with the exact realized result. */
    private static Ac syntheticSettlement(long cash, long basis)
    {
        return new Ac(T0 + 60_000L, null, Ai.TRADE,
            Aj.MARKET, "Grand Exchange", "Market", true,
            java.util.Arrays.asList(
                new Ab(CHAOS, "Chaos rune", -30L, (int) (basis / 30L), -basis,
                    Av.GRAND_EXCHANGE),
                new Ab(COINS, "Coins", cash, 1, cash, Av.FACE_VALUE)),
            Bd.CONFIRMED, "", null);
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

        int[] quote()
        {
            return quote;
        }

        void sell(int slot, int item, long qty, int limit, long spent, long cash)
        {
            sellInternal(slot, item, qty, qty, limit, spent, cash, 0L);
        }

        void sellUnknown(int slot, int item, long qty, int limit, long cash)
        {
            inventory.put(item, qty);
            engine.setBaseline(new Cc(inventory));
            offer(slot, GrandExchangeOfferState.SELLING, item, (int) qty, 0, limit, 0);
            inventory.remove(item);
            settle();
            offer(slot, GrandExchangeOfferState.SOLD, item, (int) qty, (int) qty, limit,
                (int) cash);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + cash);
            settle();
        }

        /** Unknown prior basis, but the offer's raw spent stays the real gross execution. */
        void sellUnknownGross(int slot, int item, long qty, int limit, long spent, long cash)
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

        void sellPartial(int slot, int item, long offered, long filled, int limit, long spent, long cash)
        {
            sellInternal(slot, item, offered, filled, limit, spent, cash, 0L);
        }

        void sellAfterDelay(int slot, int item, long qty, int limit, long spent, long cash, long delay)
        {
            sellInternal(slot, item, qty, qty, limit, spent, cash, delay);
        }

        private void sellInternal(int slot, int item, long offered, long filled, int limit, long spent,
            long cash, long delayBeforeSettlement)
        {
            // Known coverage at the listing reference, so the receipt's known-basis Result is the
            // exact reference-versus-cash comparison these receipt tests assert.
            engine.getActiveSession().kf(new Ac(now - 1_000L, null,
                Ai.GAIN, Aj.GENERIC, "", "Chaos rune", true,
                Collections.singletonList(new Ab(item, "Chaos rune", filled, quote[0],
                    filled * quote[0], Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null), 500);
            inventory.put(item, offered);
            engine.setBaseline(new Cc(inventory));
            offer(slot, GrandExchangeOfferState.SELLING, item, (int) offered, 0, limit, 0);
            inventory.remove(item);
            settle();
            now += delayBeforeSettlement;
            offer(slot, GrandExchangeOfferState.SOLD, item, (int) offered, (int) filled, limit, (int) spent);
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

    private static String groupIdOf(Market market)
    {
        Ao data = Ao.capture(market.engine, market.now + 1_000L,
            Ao.Entry.current());
        Br.Group group = marketGroup(data);
        assertNotNull(group);
        return group.semanticGroupId;
    }

    @Nullable
    private static Br.Group marketGroup(Ao data)
    {
        for (Br.Group group : data.market.groups)
        {
            if (group.market)
            {
                return group;
            }
        }
        return null;
    }

    private static Ao captureWithGroup(Market market, String groupId)
    {
        Ao data = Ao.capture(market.engine, market.now + 1_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "", null, null, groupId, null));
        assertNotNull(data.detail);
        return data;
    }

    private static LedgerPage openExactReceipt(Market market,
        java.util.function.Predicate<Br.Receipt> pick) throws Exception
    {
        Ao grouped = captureWithGroup(market, groupIdOf(market));
        Br.Receipt receipt = null;
        for (Br.Receipt candidate : grouped.detail.receipts)
        {
            if (pick.test(candidate))
            {
                receipt = candidate;
                break;
            }
        }
        if (receipt == null)
        {
            StringBuilder dump = new StringBuilder("no receipt matched: ");
            for (Br.Receipt candidate : grouped.detail.receipts)
            {
                dump.append('[').append(candidate.itemId).append(':').append(candidate.itemName)
                    .append(':').append(candidate.category).append("] ");
            }
            throw new AssertionError(dump.toString());
        }
        Ao data = Ao.capture(market.engine, market.now + 1_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "", receipt.transactionId, receipt.contributionId,
                grouped.detail.group.semanticGroupId, null));
        assertNotNull(data.detail);
        assertNotNull(data.detail.exact);
        return onEdt(() ->
        {
            LedgerPage page = new LedgerPage(new LedgerNoop(), id -> null);
            page.apply(data);
            return page;
        });
    }

    private static LedgerPage openExactReceiptForGroup(Market market, String groupId)
        throws Exception
    {
        Ao grouped = captureWithGroup(market, groupId);
        assertFalse(grouped.detail.receipts.isEmpty());
        Br.Receipt receipt = grouped.detail.receipts.get(0);
        Ao data = Ao.capture(market.engine, market.now + 1_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "", receipt.transactionId, receipt.contributionId,
                groupId, null));
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

    private static final class LedgerNoop implements LedgerPage.Actions
    {
        @Override public void openScopeMenu(javax.swing.JComponent anchor) { }
        @Override public void costViewChanged(Ao.Bs view) { }
        @Override public void searchChanged(String text) { }
        @Override public Ao.Ef preview(String id,
            Ah correction)
        {
            return null;
        }
        @Override public LedgerPage.Ea correct(String id,
            Ah correction, long previewRevision)
        {
            return LedgerPage.Ea.REFUSED;
        }
        @Override public void split(String id, java.util.function.BiConsumer<long[], Ao.Ef> previewed) { }
        @Override public void undoCorrection() { }
        @Override public void decideAll(Cl decision) { }
        @Override public void refresh() { }
    }
}
