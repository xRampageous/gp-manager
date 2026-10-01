package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChargeLoadReviewLifecycleTest
{
    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    private static final String TARGET = "149:0:9764864:12934:V1b";
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override public int stabilizationTicks() { return 2; }
        @Override public boolean keepTransferAuditRows() { return true; }
    };

    @Test
    public void measuredCheckConvertsLoadAndLaterUseBooksOneCost()
    {
        Engine engine = engine();
        long start = 10_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start));

        engine.markChargeLoadTransfer(ChargeRead.Variant.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        Transaction load = settle(engine, snapshot(12934, 50L), start + 600L);
        assertEquals("a validated recharge is neutral on arrival", TransactionType.TRANSFER, load.getAutomaticType());
        assertFalse(load.isCounted());
        assertEquals("Charge load transfer", load.getNote());
        assertEquals(0L, engine.getMetrics(start + 2_400L).net);
        assertEquals(0L, engine.getMetrics(start + 2_400L).costs);

        assertNull(engine.observeMeasuredChargeRead(blowpipe(50, 10), TARGET, start + 3_000L));
        assertEquals(TransactionType.TRANSFER, load.getAutomaticType());
        assertEquals(0L, engine.getMetrics(start + 3_000L).net);

        ChargeDelta use = engine.observeMeasuredChargeRead(
            blowpipe(48, 10), TARGET, start + 4_000L);
        assertNotNull(use);
        Transaction spend = engine.bookChargeSpend(
            use,
            "Toxic blowpipe",
            Collections.singletonList(new Flow(12934, "Zulrah's scales", -2L, 100,
                -200L, PriceSource.GRAND_EXCHANGE)),
            start + 4_000L);
        assertTrue(spend != null);
        assertEquals(-200L, spend.getNet());
        assertEquals(200L, engine.getMetrics(start + 4_000L).costs);
        assertEquals(-200L, engine.getMetrics(start + 4_000L).net);
    }

    /** Converting or trimming the Review row is a change like any other: the revision advances. */
    @Test
    public void measuredCheckConversionAdvancesTheRevision()
    {
        Engine engine = engine();
        long start = 10_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start);
        engine.markChargeLoadTransfer(ChargeRead.Variant.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        long before = engine.getRevision();
        Transaction load = settle(engine, snapshot(12934, 50L), start + 600L);
        assertEquals(TransactionType.TRANSFER, load.getAutomaticType());
        assertTrue("the load transfer is saved", engine.getRevision() > before);
    }

    @Test
    public void tridentRecipeCheckConvertsTheCompleteMeasuredLoad()
    {
        Engine engine = engine();
        long start = 15_000L;
        String target = "149:0:9764864:11905:TRIDENT_SEAS";
        engine.ensureSession(start);
        engine.setBaseline(snapshot(mapOf(560, 10L, 562, 10L, 554, 50L, 995, 100L)));
        assertNull(engine.observeMeasuredChargeRead(
            ChargeRead.parseCheckMessage("Your Trident of the seas has 10 charges."),
            target,
            start));

        engine.markChargeLoadTransfer(ChargeRead.Variant.TRIDENT_SEAS,
            560, "Death rune", target, 8);
        engine.markInventoryDirty();
        Transaction review = settle(engine,
            snapshot(mapOf(560, 8L, 562, 8L, 554, 40L, 995, 80L)),
            start + 600L);
        assertEquals(TransactionType.TRANSFER, review.getAutomaticType());
        assertEquals(4, review.getFlows().size());
        assertEquals(0L, engine.getMetrics(start + 2_400L).net);

        assertNull(engine.observeMeasuredChargeRead(
            ChargeRead.parseCheckMessage("Your Trident of the seas has 12 charges."),
            target,
            start + 3_000L));
        assertEquals(TransactionType.TRANSFER, review.getAutomaticType());
        assertEquals(4, review.getFlows().size());
        assertEquals(0L, engine.getMetrics(start + 3_000L).net);
    }

    @Test
    public void noCheckAfterConfiguredWindowLeavesLossInReview()
    {
        Engine engine = engine();
        long start = 25_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.markChargeLoadTransfer(ChargeRead.Variant.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        Transaction load = settle(engine, snapshot(12934, 50L), start + 600L);

        assertEquals("a validated recharge never waits in Review", TransactionType.TRANSFER, load.getAutomaticType());
        assertFalse(load.isCounted());
        assertEquals("Charge load transfer", load.getNote());
        assertEquals(0L, engine.getMetrics(start + 11 * 60_000L).net);
    }

    @Test
    public void stalePreLoadCheckCannotConfirmFreshReviewRow()
    {
        Engine engine = engine();
        long start = 50_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start);

        engine.markChargeLoadTransfer(ChargeRead.Variant.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        long settleAt = start + 11 * 60_000L;
        Transaction review = settle(engine, snapshot(12934, 50L), settleAt);
        assertEquals(TransactionType.TRANSFER, review.getAutomaticType());

        engine.observeMeasuredChargeRead(blowpipe(50, 10), TARGET, settleAt + 3_000L);
        assertEquals(TransactionType.TRANSFER, review.getAutomaticType());
        assertFalse(review.isCounted());
        assertEquals(0L, engine.getMetrics(settleAt + 3_000L).net);
    }

    @Test
    public void blowpipeCheckConfirmsScalesButLeavesUnmeasuredDartsInReview()
    {
        Engine engine = engine();
        long start = 20_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(mapOf(12934, 100L,  ItemID.RUNE_DART, 10L)));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start));

        engine.markChargeLoadTransfer(ChargeRead.Variant.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        settle(engine, snapshot(mapOf(12934, 50L, ItemID.RUNE_DART, 9L)), start + 600L);

        ChargeDelta dartUse = engine.observeMeasuredChargeRead(
            blowpipe(50, 9), TARGET, start + 3_000L);
        assertNotNull(dartUse);
        Transaction spent = engine.bookChargeSpend(dartUse,
            "Toxic blowpipe",
            Collections.singletonList(new Flow(ItemID.RUNE_DART, "Rune dart", -1L, 500,
                -500L, PriceSource.GRAND_EXCHANGE)),
            start + 3_000L);
        List<Transaction> rows = engine.getActiveSession().getTransactions();
        Transaction transfer = only(rows, TransactionType.TRANSFER);
        assertEquals("scales and darts load as one neutral transfer", 2,
            transfer.getFlows().size());
        assertFalse(transfer.isCounted());
        assertTrue("the measured dart use is the cost", spent != null);
        assertEquals(500L, engine.getMetrics(start + 3_000L).costs);
    }

    @Test
    public void partialCheckConfirmationTransfersOnlyMeasuredScaleQuantity()
    {
        Engine engine = engine();
        long start = 22_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start);
        engine.markChargeLoadTransfer(ChargeRead.Variant.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        Transaction load = settle(engine, snapshot(12934, 50L), start + 600L);

        assertEquals(TransactionType.TRANSFER, load.getAutomaticType());
        assertEquals(-50L, load.getFlows().get(0).quantityDelta);
        engine.observeMeasuredChargeRead(blowpipe(40, 10), TARGET, start + 3_000L);
        assertEquals("a later lower read never repriced the neutral transfer",
            TransactionType.TRANSFER, load.getAutomaticType());
        assertFalse(load.isCounted());
        assertEquals(0L, engine.getMetrics(start + 3_000L).net);
    }

    @Test
    public void multipleSameTargetLossesStayInReviewAndOwnerCorrectionIsAvailable()
    {
        Engine engine = engine();
        long start = 30_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));

        engine.markChargeLoadTransfer(ChargeRead.Variant.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        settle(engine, snapshot(12934, 90L), start + 600L);
        engine.markChargeLoadTransfer(ChargeRead.Variant.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        settle(engine, snapshot(12934, 80L), start + 3_000L);

        assertNull(engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start + 5_000L));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(20, 10), TARGET, start + 6_000L));
        List<Transaction> transfers = new ArrayList<>();
        for (Transaction row : engine.getActiveSession().getTransactions())
        {
            if (row.getAutomaticType() == TransactionType.TRANSFER)
            {
                transfers.add(row);
            }
        }
        assertEquals("each validated load transfers on arrival", 2, transfers.size());
        assertFalse(transfers.get(0).isCounted());
        assertFalse(transfers.get(1).isCounted());
        assertEquals(ActionKind.CHARGE_LOAD_AMBIGUOUS, transfers.get(0).getActionKind());
        assertEquals(ActionKind.CHARGE_LOAD_AMBIGUOUS, transfers.get(1).getActionKind());
        assertEquals(0L, engine.getMetrics(start + 6_000L).net);
        assertEquals("a load is value moved, never a cost", 0L,
            engine.getMetrics(start + 7_000L).costs);
    }

    @Test
    public void duplicateSameIdFlowsInsideOneLossStayInReview()
    {
        Engine engine = engineWithSplitScaleFlow();
        long start = 35_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start));
        engine.markChargeLoadTransfer(ChargeRead.Variant.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        Transaction review = settle(engine, snapshot(12934, 50L), start + 600L);
        assertEquals(2, review.getFlows().size());

        engine.observeMeasuredChargeRead(blowpipe(50, 10), TARGET, start + 3_000L);
        assertEquals(TransactionType.TRANSFER, review.getAutomaticType());
        assertEquals(2, review.getFlows().size());
        assertFalse(review.isCounted());
        assertEquals(0L, engine.getMetrics(start + 3_000L).net);
    }

    @Test
    public void aMeasuredSpendBooksOnceTheLoadTransferred()
    {
        Engine engine = engine();
        long start = 40_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.markChargeLoadTransfer(ChargeRead.Variant.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        settle(engine, snapshot(12934, 50L), start + 600L);

        MeasuredChargeReadTracker tracker =
            new MeasuredChargeReadTracker();
        tracker.observe(blowpipe(50, 10), TARGET, System.currentTimeMillis());
        ChargeDelta use = tracker.observe(blowpipe(49, 10), TARGET, System.currentTimeMillis());
        assertNotNull(use);
        Transaction booked = engine.bookChargeSpend(
            use,
            "Toxic blowpipe",
            Collections.singletonList(new Flow(12934, "Zulrah's scales", -1L, 100,
                -100L, PriceSource.GRAND_EXCHANGE)),
            start + 5_000L);
        assertTrue("a validated load blocks nothing: the use is the cost", booked != null);
        assertEquals(100L, engine.getMetrics(start + 5_000L).costs);
    }

    @Test
    public void repeatedMeasuredSpendAndRefillBooksEachDecreaseOnce()
    {
        Engine engine = engine();
        long start = 45_000L;
        engine.ensureSession(start);

        assertNull(engine.observeMeasuredChargeRead(blowpipe(100, 10), TARGET, start));
        ChargeDelta first = engine.observeMeasuredChargeRead(
            blowpipe(98, 10), TARGET, start + 1_000L);
        assertNotNull(first);
        assertTrue(engine.bookChargeSpend(first, "Toxic blowpipe",
            Collections.singletonList(new Flow(12934, "Zulrah's scales", -2L, 100,
                -200L, PriceSource.GRAND_EXCHANGE)), start + 1_000L) != null);

        // A refill is an increase, not a cost and becomes the new baseline.
        assertNull(engine.observeMeasuredChargeRead(blowpipe(120, 10), TARGET, start + 2_000L));
        ChargeDelta second = engine.observeMeasuredChargeRead(
            blowpipe(119, 10), TARGET, start + 3_000L);
        assertNotNull(second);
        assertTrue(engine.bookChargeSpend(second, "Toxic blowpipe",
            Collections.singletonList(new Flow(12934, "Zulrah's scales", -1L, 100,
                -100L, PriceSource.GRAND_EXCHANGE)), start + 3_000L) != null);

        assertEquals(300L, engine.getMetrics(start + 3_000L).costs);
        assertEquals(-300L, engine.getMetrics(start + 3_000L).net);
        assertEquals(2, countTransactions(engine.getActiveSession(), TransactionType.CONSUMPTION));
    }

    @Test
    public void pauseAndProfileRestoreDiscardAnInFlightChargeBaseline()
    {
        Engine engine = engine();
        long start = 55_000L;
        engine.ensureSession(start);
        assertNull(engine.observeMeasuredChargeRead(blowpipe(100, 10), TARGET, start));

        engine.pauseForLifecycle(start + 1_000L);
        engine.resume(start + 2_000L, PauseReason.LIFECYCLE);
        assertNull(engine.observeMeasuredChargeRead(blowpipe(90, 10), TARGET, start + 3_000L));

        engine.restoreForProfile("profile-b", engine.createSavedState(), start + 4_000L);
        assertNull(engine.observeMeasuredChargeRead(blowpipe(80, 10), TARGET, start + 5_000L));
        ChargeDelta afterRestart = engine.observeMeasuredChargeRead(
            blowpipe(79, 10), TARGET, start + 6_000L);
        assertNotNull(afterRestart);
        assertEquals(-1L, afterRestart.getComponentDeltas().get(0).getQuantityDelta());
    }

    @Test
    public void duplicateSameVariantWeaponsKeepTheirOwnBaselines()
    {
        Engine engine = engine();
        long start = 65_000L;
        engine.ensureSession(start);
        String second = "149:0:9764864:12934:V1b#2";
        assertNull(engine.observeMeasuredChargeRead(blowpipe(100, 10), TARGET, start));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(40, 10), second, start + 500L));
        // Checking the second pipe again is not a decrease on the first.
        assertNull(engine.observeMeasuredChargeRead(blowpipe(40, 10), second, start + 1_000L));
        ChargeDelta firstUse = engine.observeMeasuredChargeRead(blowpipe(97, 10), TARGET, start + 2_000L);
        assertNotNull(firstUse);
        assertEquals(-3L, firstUse.getComponentDeltas().get(0).getQuantityDelta());
        ChargeDelta secondUse = engine.observeMeasuredChargeRead(blowpipe(39, 10), second, start + 3_000L);
        assertNotNull(secondUse);
        assertEquals(-1L, secondUse.getComponentDeltas().get(0).getQuantityDelta());
        assertTrue(engine.bookChargeSpend(firstUse, "Toxic blowpipe",
            Collections.singletonList(new Flow(12934, "Zulrah's scales", -3L, 100, -300L, PriceSource.GRAND_EXCHANGE)), start + 2_000L) != null);
        assertTrue(engine.bookChargeSpend(secondUse, "Toxic blowpipe",
            Collections.singletonList(new Flow(12934, "Zulrah's scales", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE)), start + 3_000L) != null);
        assertEquals(400L, engine.getMetrics(start + 3_000L).costs);
        assertEquals(2, countTransactions(engine.getActiveSession(), TransactionType.CONSUMPTION));
    }

    /** Owner-approved: a top-up is measured against that weapon's own last Check. */
    @Test
    public void topUpIsMeasuredAgainstTheSameWeaponsLastCheckAcrossAnotherWeaponsCheck()
    {
        Engine engine = engine();
        long start = 70_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        String second = "149:0:9764864:12934:V1b#2";
        assertNull(engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start));

        engine.markChargeLoadTransfer(ChargeRead.Variant.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        Transaction review = settle(engine, snapshot(12934, 50L), start + 600L);
        assertEquals(TransactionType.TRANSFER, review.getAutomaticType());

        // The other blowpipe is Checked between the load and this pipe's next Check.
        assertNull(engine.observeMeasuredChargeRead(blowpipe(40, 10), second, start + 2_000L));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(50, 10), TARGET, start + 3_000L));
        assertEquals("the load is confirmed by this pipe's own Check", TransactionType.TRANSFER,
            review.getAutomaticType());
        assertEquals(0L, engine.getMetrics(start + 3_000L).net);
    }

    @Test
    public void aNewSameVariantInstanceInvalidatesTheStaleMeasuredBaseline()
    {
        Engine engine = engine();
        long start = 95_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(ItemID.TOXIC_BLOWPIPE_LOADED, 1L));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(1000, 100), TARGET, start));

        // A second blowpipe is withdrawn into the same slot: that slot identity now belongs to
        // a different physical weapon, so the stored baseline must not be compared against it.
        settle(engine, snapshot(ItemID.TOXIC_BLOWPIPE_LOADED, 2L), start + 1_000L);

        assertNull("a replacement that retained the slot identity must not book a phantom decrease",
            engine.observeMeasuredChargeRead(blowpipe(100, 100), TARGET, start + 5_000L));
        // The fresh read seeds the new baseline; a later decrease still books normally.
        ChargeDelta use = engine.observeMeasuredChargeRead(blowpipe(98, 100), TARGET, start + 6_000L);
        assertNotNull(use);
        assertEquals(-2L, use.getComponentDeltas().get(0).getQuantityDelta());
    }

    @Test
    public void spendIsAttributedToTheActivityBeingPlayed()
    {
        Engine engine = engine();
        long start = 75_000L;
        engine.ensureSession(start);
        engine.getActiveSession().setActivityHint("Zulrah", start);
        assertNull(engine.observeMeasuredChargeRead(blowpipe(100, 10), TARGET, start));
        ChargeDelta use = engine.observeMeasuredChargeRead(blowpipe(98, 10), TARGET, start + 1_000L);
        Transaction spend = engine.bookChargeSpend(use, "Toxic blowpipe",
            Collections.singletonList(new Flow(12934, "Zulrah's scales", -2L, 100, -200L, PriceSource.GRAND_EXCHANGE)), start + 1_000L);
        assertTrue(spend != null);
        assertEquals("Zulrah", spend.getActivityName());
        engine.getActiveSession().setActivityHint("Vorkath", start + 2_000L);
        ChargeDelta later = engine.observeMeasuredChargeRead(blowpipe(97, 10), TARGET, start + 3_000L);
        Transaction laterSpend = engine.bookChargeSpend(later, "Toxic blowpipe",
            Collections.singletonList(new Flow(12934, "Zulrah's scales", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE)), start + 3_000L);
        assertEquals("Vorkath", laterSpend.getActivityName());
    }


    private static int countTransactions(Session session, TransactionType type)
    {
        int count = 0;
        for (Transaction transaction : session.getTransactions())
        {
            if (transaction.getType() == type)
            {
                count++;
            }
        }
        return count;
    }

    private static Engine engine()
    {
        return new Engine(ChargeLoadReviewLifecycleTest::flowsForTest,
            new TransactionClassifier(), CONFIG);
    }

    private static Engine engineWithSplitScaleFlow()
    {
        return new Engine(deltas -> {
            Long scaleDelta = deltas.get(12934);
            if (scaleDelta == null || scaleDelta >= 0L || scaleDelta != -50L)
            {
                return flowsForTest(deltas);
            }
            return Arrays.asList(
                new Flow(12934, "Zulrah's scales", -20L, 100, -2_000L,
                    PriceSource.GRAND_EXCHANGE),
                new Flow(12934, "Zulrah's scales", -30L, 100, -3_000L,
                    PriceSource.GRAND_EXCHANGE));
        }, new TransactionClassifier(), CONFIG);
    }

    private static List<Flow> flowsForTest(Map<Integer, Long> deltas)
    {
        List<Flow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int id = entry.getKey();
            String name = itemName(id);
            int price = id == 12934 ? 100 : id == 995 ? 1 : 500;
            flows.add(new Flow(id, name, entry.getValue(), price, entry.getValue() * price,
                PriceSource.GRAND_EXCHANGE));
        }
        return flows;
    }

    private static String itemName(int id)
    {
        if (id == 12934) return "Zulrah's scales";
        if (id == 560) return "Death rune";
        if (id == 562) return "Chaos rune";
        if (id == 554) return "Fire rune";
        if (id == 995) return "Coins";
        return "Rune dart";
    }

    private static Transaction only(List<Transaction> rows, TransactionType type)
    {
        Transaction match = null;
        for (Transaction row : rows)
        {
            if (row.getAutomaticType() == type)
            {
                assertTrue("expected a single row of type " + type, match == null);
                match = row;
            }
        }
        assertNotNull("missing row of type " + type, match);
        return match;
    }

    private static ChargeRead blowpipe(long scales, long darts)
    {
        return ChargeRead.parseCheckMessage(
            "Darts: Rune dart x " + darts + ". Scales: " + scales + " (1.0%).");
    }

    private static ContainerSnapshot snapshot(int id, long quantity)
    {
        return snapshot(mapOf(id, quantity));
    }

    private static ContainerSnapshot snapshot(Map<Integer, Long> quantities)
    {
        return new ContainerSnapshot(quantities);
    }

    private static Map<Integer, Long> mapOf(Object... values)
    {
        Map<Integer, Long> result = new HashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2)
        {
            result.put((Integer) values[index], (Long) values[index + 1]);
        }
        return result;
    }

    private static Transaction settle(Engine engine, ContainerSnapshot next, long firstTick)
    {
        Transaction result = engine.processIfDirty(next, firstTick);
        assertNull(result);
        result = engine.processIfDirty(next, firstTick + 600L);
        assertNull(result);
        return engine.processIfDirty(next, firstTick + 1_200L);
    }

}
