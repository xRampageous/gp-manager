package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.evidence.MeasuredChargeDelta;
import com.gpmanager.engine.evidence.MeasuredChargeRead;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
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
        com.gpmanager.persistence.JsonCodec.bind(new com.google.gson.Gson());
    }

    private static final String TARGET = "149:0:9764864:12934:TOXIC_BLOWPIPE";
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override public int stabilizationTicks() { return 2; }
        @Override public int chargeLoadReviewMinutes() { return 10; }
        @Override public boolean keepTransferAuditRows() { return true; }
    };

    @Test
    public void measuredCheckConvertsLoadAndLaterUseBooksOneCost()
    {
        GpManagerEngine engine = engine();
        long start = 10_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start));

        engine.markChargeLoadTransfer(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        ProfitTransaction review = settle(engine, snapshot(12934, 50L), start + 600L);
        assertEquals(TransactionType.UNCERTAIN, review.getAutomaticType());
        assertFalse(review.isCounted());
        assertEquals(0L, engine.getMetrics(start + 2_400L).getNet());
        assertEquals(0L, engine.getMetrics(start + 2_400L).getCosts());

        assertNull(engine.observeMeasuredChargeRead(blowpipe(50, 10), TARGET, start + 3_000L));
        assertEquals(TransactionType.TRANSFER, review.getAutomaticType());
        assertFalse(review.isCounted());
        assertEquals("Charge load transfer", review.getNote());
        assertEquals(0L, engine.getMetrics(start + 3_000L).getNet());

        MeasuredChargeDelta use = engine.observeMeasuredChargeRead(
            blowpipe(48, 10), TARGET, start + 4_000L);
        assertNotNull(use);
        com.gpmanager.engine.evidence.ChargeSpendBooking.Result spend = engine.bookChargeSpend(
            use,
            "Toxic blowpipe",
            Collections.singletonList(new ItemFlow(12934, "Zulrah's scales", -2L, 100,
                -200L, ItemPriceSource.GRAND_EXCHANGE)),
            start + 4_000L);
        assertTrue(spend.booked);
        assertEquals(-200L, spend.transaction.getNet());
        assertEquals(200L, engine.getMetrics(start + 4_000L).getCosts());
        assertEquals(-200L, engine.getMetrics(start + 4_000L).getNet());
    }

    @Test
    public void tridentRecipeCheckConvertsTheCompleteMeasuredLoad()
    {
        GpManagerEngine engine = engine();
        long start = 15_000L;
        String target = "149:0:9764864:11905:TRIDENT_SEAS";
        engine.ensureSession(start);
        engine.setBaseline(snapshot(mapOf(560, 10L, 562, 10L, 554, 50L, 995, 100L)));
        assertNull(engine.observeMeasuredChargeRead(
            MeasuredChargeRead.parseCheckMessage("Your Trident of the seas has 10 charges."),
            target,
            start));

        engine.markChargeLoadTransfer(MeasuredChargeRead.Variant.TRIDENT_SEAS,
            560, "Death rune", target, 8);
        engine.markInventoryDirty();
        ProfitTransaction review = settle(engine,
            snapshot(mapOf(560, 8L, 562, 8L, 554, 40L, 995, 80L)),
            start + 600L);
        assertEquals(TransactionType.UNCERTAIN, review.getAutomaticType());
        assertEquals(4, review.getFlows().size());
        assertEquals(0L, engine.getMetrics(start + 2_400L).getNet());

        assertNull(engine.observeMeasuredChargeRead(
            MeasuredChargeRead.parseCheckMessage("Your Trident of the seas has 12 charges."),
            target,
            start + 3_000L));
        assertEquals(TransactionType.TRANSFER, review.getAutomaticType());
        assertEquals(4, review.getFlows().size());
        assertEquals(0L, engine.getMetrics(start + 3_000L).getNet());
    }

    @Test
    public void noCheckAfterConfiguredWindowLeavesLossInReview()
    {
        GpManagerEngine engine = engine();
        long start = 25_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.markChargeLoadTransfer(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        ProfitTransaction review = settle(engine, snapshot(12934, 50L), start + 600L);

        assertEquals(TransactionType.UNCERTAIN, review.getAutomaticType());
        assertFalse(review.isCounted());
        assertEquals(0L, engine.getMetrics(start + 11 * 60_000L).getNet());
        assertEquals(TransactionType.UNCERTAIN, review.getAutomaticType());
    }

    @Test
    public void stalePreLoadCheckCannotConfirmFreshReviewRow()
    {
        GpManagerEngine engine = engine();
        long start = 50_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start);

        engine.markChargeLoadTransfer(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        long settleAt = start + 11 * 60_000L;
        ProfitTransaction review = settle(engine, snapshot(12934, 50L), settleAt);
        assertEquals(TransactionType.UNCERTAIN, review.getAutomaticType());

        engine.observeMeasuredChargeRead(blowpipe(50, 10), TARGET, settleAt + 3_000L);
        assertEquals(TransactionType.UNCERTAIN, review.getAutomaticType());
        assertFalse(review.isCounted());
        assertEquals(0L, engine.getMetrics(settleAt + 3_000L).getNet());
    }

    @Test
    public void blowpipeCheckConfirmsScalesButLeavesUnmeasuredDartsInReview()
    {
        GpManagerEngine engine = engine();
        long start = 20_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(mapOf(12934, 100L,  ItemID.RUNE_DART, 10L)));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start));

        engine.markChargeLoadTransfer(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        settle(engine, snapshot(mapOf(12934, 50L, ItemID.RUNE_DART, 9L)), start + 600L);

        MeasuredChargeDelta unconfirmedDartDelta = engine.observeMeasuredChargeRead(
            blowpipe(50, 9), TARGET, start + 3_000L);
        assertNotNull(unconfirmedDartDelta);
        assertFalse(engine.bookChargeSpend(unconfirmedDartDelta,
            "Toxic blowpipe",
            Collections.singletonList(new ItemFlow(ItemID.RUNE_DART, "Rune dart", -1L, 500,
                -500L, ItemPriceSource.GRAND_EXCHANGE)),
            start + 3_000L).booked);
        List<ProfitTransaction> rows = engine.getActiveSession().getTransactions();
        ProfitTransaction review = only(rows, TransactionType.UNCERTAIN);
        ProfitTransaction transfer = only(rows, TransactionType.TRANSFER);
        assertEquals(1, review.getFlows().size());
        assertEquals(ItemID.RUNE_DART, review.getFlows().get(0).getItemId());
        assertFalse(review.isCounted());
        assertEquals(1, transfer.getFlows().size());
        assertEquals(12934, transfer.getFlows().get(0).getItemId());
        assertEquals(0L, engine.getMetrics(start + 3_000L).getNet());
    }

    @Test
    public void partialCheckConfirmationTransfersOnlyMeasuredScaleQuantity()
    {
        GpManagerEngine engine = engine();
        long start = 22_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start);
        engine.markChargeLoadTransfer(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        ProfitTransaction review = settle(engine, snapshot(12934, 50L), start + 600L);

        engine.observeMeasuredChargeRead(blowpipe(40, 10), TARGET, start + 3_000L);
        assertEquals(TransactionType.UNCERTAIN, review.getAutomaticType());
        assertEquals(-1_000L, review.getAutomaticNet());
        assertEquals(1, review.getFlows().size());
        assertEquals(-10L, review.getFlows().get(0).getQuantityDelta());
        ProfitTransaction partialTransfer = null;
        for (ProfitTransaction row : engine.getActiveSession().getTransactions())
        {
            if (row.getAutomaticType() == TransactionType.TRANSFER)
            {
                partialTransfer = row;
            }
        }
        assertNotNull(partialTransfer);
        assertEquals(-40L, partialTransfer.getFlows().get(0).getQuantityDelta());
        assertFalse(partialTransfer.isCounted());
        assertEquals(0L, engine.getMetrics(start + 3_000L).getNet());
    }

    @Test
    public void multipleSameTargetLossesStayInReviewAndOwnerCorrectionIsAvailable()
    {
        GpManagerEngine engine = engine();
        long start = 30_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));

        engine.markChargeLoadTransfer(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        settle(engine, snapshot(12934, 90L), start + 600L);
        engine.markChargeLoadTransfer(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        settle(engine, snapshot(12934, 80L), start + 3_000L);

        assertNull(engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start + 5_000L));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(20, 10), TARGET, start + 6_000L));
        List<ProfitTransaction> reviews = new ArrayList<>();
        for (ProfitTransaction row : engine.getActiveSession().getTransactions())
        {
            if (row.getAutomaticType() == TransactionType.UNCERTAIN)
            {
                reviews.add(row);
            }
        }
        assertEquals(2, reviews.size());
        assertTrue(reviews.get(0).getChargeLoadReviewProvenance() != null);
        assertTrue(reviews.get(1).getChargeLoadReviewProvenance() != null);
        assertEquals(0L, engine.getMetrics(start + 6_000L).getNet());

        ProfitSession session = engine.getActiveSession();
        assertTrue(session.correctTransaction(reviews.get(0).getId(),
            TransactionCorrection.COST, start + 7_000L, "Owner chose to count this load"));
        assertTrue(reviews.get(0).isCounted());
        assertEquals(1_000L, engine.getMetrics(start + 7_000L).getCosts());
    }

    @Test
    public void duplicateSameIdFlowsInsideOneLossStayInReview()
    {
        GpManagerEngine engine = engineWithSplitScaleFlow();
        long start = 35_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(0, 10), TARGET, start));
        engine.markChargeLoadTransfer(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        ProfitTransaction review = settle(engine, snapshot(12934, 50L), start + 600L);
        assertEquals(2, review.getFlows().size());

        engine.observeMeasuredChargeRead(blowpipe(50, 10), TARGET, start + 3_000L);
        assertEquals(TransactionType.UNCERTAIN, review.getAutomaticType());
        assertEquals(2, review.getFlows().size());
        assertFalse(review.isCounted());
        assertEquals(0L, engine.getMetrics(start + 3_000L).getNet());
    }

    @Test
    public void spendBookingRejectsComponentsThatStillHavePendingReview()
    {
        GpManagerEngine engine = engine();
        long start = 40_000L;
        engine.ensureSession(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.markChargeLoadTransfer(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            12934, "Zulrah's scales", TARGET, 8);
        engine.markInventoryDirty();
        settle(engine, snapshot(12934, 50L), start + 600L);

        com.gpmanager.engine.evidence.MeasuredChargeReadTracker tracker =
            new com.gpmanager.engine.evidence.MeasuredChargeReadTracker();
        tracker.observe(blowpipe(50, 10), TARGET);
        MeasuredChargeDelta use = tracker.observe(blowpipe(49, 10), TARGET);
        assertNotNull(use);
        com.gpmanager.engine.evidence.ChargeSpendBooking.Result blocked = engine.bookChargeSpend(
            use,
            "Toxic blowpipe",
            Collections.singletonList(new ItemFlow(12934, "Zulrah's scales", -1L, 100,
                -100L, ItemPriceSource.GRAND_EXCHANGE)),
            start + 5_000L);
        assertFalse(blocked.booked);
        assertEquals(0L, engine.getMetrics(start + 5_000L).getCosts());
    }

    @Test
    public void repeatedMeasuredSpendAndRefillBooksEachDecreaseOnce()
    {
        GpManagerEngine engine = engine();
        long start = 45_000L;
        engine.ensureSession(start);

        assertNull(engine.observeMeasuredChargeRead(blowpipe(100, 10), TARGET, start));
        MeasuredChargeDelta first = engine.observeMeasuredChargeRead(
            blowpipe(98, 10), TARGET, start + 1_000L);
        assertNotNull(first);
        assertTrue(engine.bookChargeSpend(first, "Toxic blowpipe",
            Collections.singletonList(new ItemFlow(12934, "Zulrah's scales", -2L, 100,
                -200L, ItemPriceSource.GRAND_EXCHANGE)), start + 1_000L).booked);

        // A refill is an increase, not a cost and becomes the new baseline.
        assertNull(engine.observeMeasuredChargeRead(blowpipe(120, 10), TARGET, start + 2_000L));
        MeasuredChargeDelta second = engine.observeMeasuredChargeRead(
            blowpipe(119, 10), TARGET, start + 3_000L);
        assertNotNull(second);
        assertTrue(engine.bookChargeSpend(second, "Toxic blowpipe",
            Collections.singletonList(new ItemFlow(12934, "Zulrah's scales", -1L, 100,
                -100L, ItemPriceSource.GRAND_EXCHANGE)), start + 3_000L).booked);

        assertEquals(300L, engine.getMetrics(start + 3_000L).getCosts());
        assertEquals(-300L, engine.getMetrics(start + 3_000L).getNet());
        assertEquals(2, countTransactions(engine.getActiveSession(), TransactionType.CONSUMPTION));
    }

    @Test
    public void pauseAndProfileRestoreDiscardAnInFlightChargeBaseline()
    {
        GpManagerEngine engine = engine();
        long start = 55_000L;
        engine.ensureSession(start);
        assertNull(engine.observeMeasuredChargeRead(blowpipe(100, 10), TARGET, start));

        engine.pauseForLifecycle(start + 1_000L);
        engine.resumeAfterLifecycle(start + 2_000L);
        assertNull(engine.observeMeasuredChargeRead(blowpipe(90, 10), TARGET, start + 3_000L));

        engine.restoreForProfile("profile-b", engine.createSavedState(), start + 4_000L);
        assertNull(engine.observeMeasuredChargeRead(blowpipe(80, 10), TARGET, start + 5_000L));
        MeasuredChargeDelta afterRestart = engine.observeMeasuredChargeRead(
            blowpipe(79, 10), TARGET, start + 6_000L);
        assertNotNull(afterRestart);
        assertEquals(-1L, afterRestart.getComponentDeltas().get(0).getQuantityDelta());
    }

    @Test
    public void duplicateSameVariantWeaponsKeepTheirOwnBaselines()
    {
        GpManagerEngine engine = engine();
        long start = 65_000L;
        engine.ensureSession(start);
        String second = "149:0:9764864:12934:TOXIC_BLOWPIPE#2";
        assertNull(engine.observeMeasuredChargeRead(blowpipe(100, 10), TARGET, start));
        assertNull(engine.observeMeasuredChargeRead(blowpipe(40, 10), second, start + 500L));
        // Checking the second pipe again is not a decrease on the first.
        assertNull(engine.observeMeasuredChargeRead(blowpipe(40, 10), second, start + 1_000L));
        MeasuredChargeDelta firstUse = engine.observeMeasuredChargeRead(blowpipe(97, 10), TARGET, start + 2_000L);
        assertNotNull(firstUse);
        assertEquals(-3L, firstUse.getComponentDeltas().get(0).getQuantityDelta());
        MeasuredChargeDelta secondUse = engine.observeMeasuredChargeRead(blowpipe(39, 10), second, start + 3_000L);
        assertNotNull(secondUse);
        assertEquals(-1L, secondUse.getComponentDeltas().get(0).getQuantityDelta());
        assertTrue(engine.bookChargeSpend(firstUse, "Toxic blowpipe",
            Collections.singletonList(new ItemFlow(12934, "Zulrah's scales", -3L, 100, -300L, ItemPriceSource.GRAND_EXCHANGE)), start + 2_000L).booked);
        assertTrue(engine.bookChargeSpend(secondUse, "Toxic blowpipe",
            Collections.singletonList(new ItemFlow(12934, "Zulrah's scales", -1L, 100, -100L, ItemPriceSource.GRAND_EXCHANGE)), start + 3_000L).booked);
        assertEquals(400L, engine.getMetrics(start + 3_000L).getCosts());
        assertEquals(2, countTransactions(engine.getActiveSession(), TransactionType.CONSUMPTION));
    }

    @Test
    public void spendIsAttributedToTheActivityBeingPlayed()
    {
        GpManagerEngine engine = engine();
        long start = 75_000L;
        engine.ensureSession(start);
        engine.getActiveSession().setActivityHint("Zulrah", start);
        assertNull(engine.observeMeasuredChargeRead(blowpipe(100, 10), TARGET, start));
        MeasuredChargeDelta use = engine.observeMeasuredChargeRead(blowpipe(98, 10), TARGET, start + 1_000L);
        com.gpmanager.engine.evidence.ChargeSpendBooking.Result spend = engine.bookChargeSpend(use, "Toxic blowpipe",
            Collections.singletonList(new ItemFlow(12934, "Zulrah's scales", -2L, 100, -200L, ItemPriceSource.GRAND_EXCHANGE)), start + 1_000L);
        assertTrue(spend.booked);
        assertEquals("Zulrah", spend.transaction.getActivityName());
        engine.getActiveSession().setActivityHint("Vorkath", start + 2_000L);
        MeasuredChargeDelta later = engine.observeMeasuredChargeRead(blowpipe(97, 10), TARGET, start + 3_000L);
        com.gpmanager.engine.evidence.ChargeSpendBooking.Result laterSpend = engine.bookChargeSpend(later, "Toxic blowpipe",
            Collections.singletonList(new ItemFlow(12934, "Zulrah's scales", -1L, 100, -100L, ItemPriceSource.GRAND_EXCHANGE)), start + 3_000L);
        assertEquals("Vorkath", laterSpend.transaction.getActivityName());
    }

    @Test
    public void bottomlessCompostBucketUsesAreMeasuredAtHalfABucketEach()
    {
        MeasuredChargeRead full = MeasuredChargeRead.parseCheckMessage(
            "Your bottomless compost bucket has 42 uses of ultracompost left.");
        assertNotNull(full);
        assertEquals(MeasuredChargeRead.Variant.BOTTOMLESS_COMPOST_BUCKET, full.getVariant());
        assertTrue(full.isBookable());
        assertEquals(Long.valueOf(42L), full.getComponentCounts().get(net.runelite.api.gameval.ItemID.BUCKET_ULTRACOMPOST));
        assertEquals(2, MeasuredChargeRead.unitsPerPricedItem(full.getVariant(), net.runelite.api.gameval.ItemID.BUCKET_ULTRACOMPOST));
        assertEquals(1, MeasuredChargeRead.unitsPerPricedItem(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE, 12934));
        MeasuredChargeRead one = MeasuredChargeRead.parseCheckMessage("Your bottomless compost bucket has 1 use of compost left.");
        assertNotNull(one);
        assertEquals(Long.valueOf(1L), one.getComponentCounts().get(net.runelite.api.gameval.ItemID.BUCKET_COMPOST));
        MeasuredChargeRead empty = MeasuredChargeRead.parseCheckMessage("Your bottomless compost bucket is currently empty.");
        assertNotNull(empty);
        assertTrue(empty.isBookable());
        assertEquals(MeasuredChargeRead.Variant.BOTTOMLESS_COMPOST_BUCKET,
            MeasuredChargeRead.supportedVariantForItemId(net.runelite.api.gameval.ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED));
        assertEquals(MeasuredChargeRead.Variant.BOTTOMLESS_COMPOST_BUCKET,
            MeasuredChargeRead.supportedVariantForItemName("Bottomless compost bucket"));

        // Two Checks, three uses apart, book one decrease of three uses on that bucket.
        GpManagerEngine engine = engine();
        long start = 85_000L;
        engine.ensureSession(start);
        String bucket = "149:0:9764864:22997:BOTTOMLESS_COMPOST_BUCKET";
        assertNull(engine.observeMeasuredChargeRead(full, bucket, start));
        MeasuredChargeDelta used = engine.observeMeasuredChargeRead(
            MeasuredChargeRead.parseCheckMessage("Your bottomless compost bucket has 39 uses of ultracompost left."), bucket, start + 1_000L);
        assertNotNull(used);
        assertEquals(-3L, used.getComponentDeltas().get(0).getQuantityDelta());
    }

    private static int countTransactions(ProfitSession session, TransactionType type)
    {
        int count = 0;
        for (ProfitTransaction transaction : session.getTransactions())
        {
            if (transaction.getType() == type)
            {
                count++;
            }
        }
        return count;
    }

    private static GpManagerEngine engine()
    {
        return new GpManagerEngine(ChargeLoadReviewLifecycleTest::flowsForTest,
            new TransactionClassifier(), CONFIG);
    }

    private static GpManagerEngine engineWithSplitScaleFlow()
    {
        return new GpManagerEngine(deltas -> {
            Long scaleDelta = deltas.get(12934);
            if (scaleDelta == null || scaleDelta >= 0L || scaleDelta != -50L)
            {
                return flowsForTest(deltas);
            }
            return Arrays.asList(
                new ItemFlow(12934, "Zulrah's scales", -20L, 100, -2_000L,
                    ItemPriceSource.GRAND_EXCHANGE),
                new ItemFlow(12934, "Zulrah's scales", -30L, 100, -3_000L,
                    ItemPriceSource.GRAND_EXCHANGE));
        }, new TransactionClassifier(), CONFIG);
    }

    private static List<ItemFlow> flowsForTest(Map<Integer, Long> deltas)
    {
        List<ItemFlow> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int id = entry.getKey();
            String name = itemName(id);
            int price = id == 12934 ? 100 : id == 995 ? 1 : 500;
            flows.add(new ItemFlow(id, name, entry.getValue(), price, entry.getValue() * price,
                ItemPriceSource.GRAND_EXCHANGE));
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

    private static ProfitTransaction only(List<ProfitTransaction> rows, TransactionType type)
    {
        ProfitTransaction match = null;
        for (ProfitTransaction row : rows)
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

    private static MeasuredChargeRead blowpipe(long scales, long darts)
    {
        return MeasuredChargeRead.parseCheckMessage(
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

    private static ProfitTransaction settle(GpManagerEngine engine, ContainerSnapshot next, long firstTick)
    {
        ProfitTransaction result = engine.processIfDirty(next, firstTick);
        assertNull(result);
        result = engine.processIfDirty(next, firstTick + 600L);
        assertNull(result);
        return engine.processIfDirty(next, firstTick + 1_200L);
    }

}
