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
        Am engine = engine();
        long start = 10_000L;
        engine.rm(start);
        engine.setBaseline(snapshot(12934, 100L));
        assertNull(engine.abu(blowpipe(0, 10), TARGET, start));

        engine.yx(Ar.V.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.yz();
        Ac load = settle(engine, snapshot(12934, 50L), start + 600L);
        assertEquals("a validated recharge is neutral on arrival", Ai.TRANSFER, load.tm());
        assertFalse(load.isCounted());
        assertEquals("Charge load transfer", load.getNote());
        assertEquals(0L, engine.getMetrics(start + 2_400L).net);
        assertEquals(0L, engine.getMetrics(start + 2_400L).costs);

        assertNull(engine.abu(blowpipe(50, 10), TARGET, start + 3_000L));
        assertEquals(Ai.TRANSFER, load.tm());
        assertEquals(0L, engine.getMetrics(start + 3_000L).net);

        Cm use = engine.abu(
            blowpipe(48, 10), TARGET, start + 4_000L);
        assertNotNull(use);
        Ac spend = engine.mj(
            use,
            "Toxic blowpipe",
            Collections.singletonList(new Ab(12934, "Zulrah's scales", -2L, 100,
                -200L, Av.GRAND_EXCHANGE)),
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
        Am engine = engine();
        long start = 10_000L;
        engine.rm(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.abu(blowpipe(0, 10), TARGET, start);
        engine.yx(Ar.V.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.yz();
        long before = engine.getRevision();
        Ac load = settle(engine, snapshot(12934, 50L), start + 600L);
        assertEquals(Ai.TRANSFER, load.tm());
        assertTrue("the load transfer is saved", engine.getRevision() > before);
    }

    @Test
    public void tridentRecipeCheckConvertsTheCompleteMeasuredLoad()
    {
        Am engine = engine();
        long start = 15_000L;
        String target = "149:0:9764864:11905:TRIDENT_SEAS";
        engine.rm(start);
        engine.setBaseline(snapshot(mapOf(560, 10L, 562, 10L, 554, 50L, 995, 100L)));
        assertNull(engine.abu(
            Ar.acs("Your Trident of the seas has 10 charges."),
            target,
            start));

        engine.yx(Ar.V.TRIDENT_SEAS,
            560, "Death rune", target, 8);
        engine.yz();
        Ac review = settle(engine,
            snapshot(mapOf(560, 8L, 562, 8L, 554, 40L, 995, 80L)),
            start + 600L);
        assertEquals(Ai.TRANSFER, review.tm());
        assertEquals(4, review.getFlows().size());
        assertEquals(0L, engine.getMetrics(start + 2_400L).net);

        assertNull(engine.abu(
            Ar.acs("Your Trident of the seas has 12 charges."),
            target,
            start + 3_000L));
        assertEquals(Ai.TRANSFER, review.tm());
        assertEquals(4, review.getFlows().size());
        assertEquals(0L, engine.getMetrics(start + 3_000L).net);
    }

    @Test
    public void noCheckAfterConfiguredWindowLeavesLossInReview()
    {
        Am engine = engine();
        long start = 25_000L;
        engine.rm(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.yx(Ar.V.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.yz();
        Ac load = settle(engine, snapshot(12934, 50L), start + 600L);

        assertEquals("a validated recharge never waits in Review", Ai.TRANSFER, load.tm());
        assertFalse(load.isCounted());
        assertEquals("Charge load transfer", load.getNote());
        assertEquals(0L, engine.getMetrics(start + 11 * 60_000L).net);
    }

    @Test
    public void stalePreLoadCheckCannotConfirmFreshReviewRow()
    {
        Am engine = engine();
        long start = 50_000L;
        engine.rm(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.abu(blowpipe(0, 10), TARGET, start);

        engine.yx(Ar.V.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.yz();
        long settleAt = start + 11 * 60_000L;
        Ac review = settle(engine, snapshot(12934, 50L), settleAt);
        assertEquals(Ai.TRANSFER, review.tm());

        engine.abu(blowpipe(50, 10), TARGET, settleAt + 3_000L);
        assertEquals(Ai.TRANSFER, review.tm());
        assertFalse(review.isCounted());
        assertEquals(0L, engine.getMetrics(settleAt + 3_000L).net);
    }

    @Test
    public void blowpipeCheckConfirmsScalesButLeavesUnmeasuredDartsInReview()
    {
        Am engine = engine();
        long start = 20_000L;
        engine.rm(start);
        engine.setBaseline(snapshot(mapOf(12934, 100L,  ItemID.RUNE_DART, 10L)));
        assertNull(engine.abu(blowpipe(0, 10), TARGET, start));

        engine.yx(Ar.V.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.yz();
        settle(engine, snapshot(mapOf(12934, 50L, ItemID.RUNE_DART, 9L)), start + 600L);

        Cm dartUse = engine.abu(
            blowpipe(50, 9), TARGET, start + 3_000L);
        assertNotNull(dartUse);
        Ac spent = engine.mj(dartUse,
            "Toxic blowpipe",
            Collections.singletonList(new Ab(ItemID.RUNE_DART, "Rune dart", -1L, 500,
                -500L, Av.GRAND_EXCHANGE)),
            start + 3_000L);
        List<Ac> rows = engine.getActiveSession().getTransactions();
        Ac transfer = only(rows, Ai.TRANSFER);
        assertEquals("scales and darts load as one neutral transfer", 2,
            transfer.getFlows().size());
        assertFalse(transfer.isCounted());
        assertTrue("the measured dart use is the cost", spent != null);
        assertEquals(500L, engine.getMetrics(start + 3_000L).costs);
    }

    @Test
    public void partialCheckConfirmationTransfersOnlyMeasuredScaleQuantity()
    {
        Am engine = engine();
        long start = 22_000L;
        engine.rm(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.abu(blowpipe(0, 10), TARGET, start);
        engine.yx(Ar.V.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.yz();
        Ac load = settle(engine, snapshot(12934, 50L), start + 600L);

        assertEquals(Ai.TRANSFER, load.tm());
        assertEquals(-50L, load.getFlows().get(0).quantityDelta);
        engine.abu(blowpipe(40, 10), TARGET, start + 3_000L);
        assertEquals("a later lower read never repriced the neutral transfer",
            Ai.TRANSFER, load.tm());
        assertFalse(load.isCounted());
        assertEquals(0L, engine.getMetrics(start + 3_000L).net);
    }

    @Test
    public void multipleSameTargetLossesStayInReviewAndOwnerCorrectionIsAvailable()
    {
        Am engine = engine();
        long start = 30_000L;
        engine.rm(start);
        engine.setBaseline(snapshot(12934, 100L));

        engine.yx(Ar.V.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.yz();
        settle(engine, snapshot(12934, 90L), start + 600L);
        engine.yx(Ar.V.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.yz();
        settle(engine, snapshot(12934, 80L), start + 3_000L);

        assertNull(engine.abu(blowpipe(0, 10), TARGET, start + 5_000L));
        assertNull(engine.abu(blowpipe(20, 10), TARGET, start + 6_000L));
        List<Ac> transfers = new ArrayList<>();
        for (Ac row : engine.getActiveSession().getTransactions())
        {
            if (row.tm() == Ai.TRANSFER)
            {
                transfers.add(row);
            }
        }
        assertEquals("each validated load transfers on arrival", 2, transfers.size());
        assertFalse(transfers.get(0).isCounted());
        assertFalse(transfers.get(1).isCounted());
        assertEquals(Au.CHARGE_LOAD_AMBIGUOUS, transfers.get(0).getActionKind());
        assertEquals(Au.CHARGE_LOAD_AMBIGUOUS, transfers.get(1).getActionKind());
        assertEquals(0L, engine.getMetrics(start + 6_000L).net);
        assertEquals("a load is value moved, never a cost", 0L,
            engine.getMetrics(start + 7_000L).costs);
    }

    @Test
    public void duplicateSameIdFlowsInsideOneLossStayInReview()
    {
        Am engine = engineWithSplitScaleFlow();
        long start = 35_000L;
        engine.rm(start);
        engine.setBaseline(snapshot(12934, 100L));
        assertNull(engine.abu(blowpipe(0, 10), TARGET, start));
        engine.yx(Ar.V.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.yz();
        Ac review = settle(engine, snapshot(12934, 50L), start + 600L);
        assertEquals(2, review.getFlows().size());

        engine.abu(blowpipe(50, 10), TARGET, start + 3_000L);
        assertEquals(Ai.TRANSFER, review.tm());
        assertEquals(2, review.getFlows().size());
        assertFalse(review.isCounted());
        assertEquals(0L, engine.getMetrics(start + 3_000L).net);
    }

    @Test
    public void aMeasuredSpendBooksOnceTheLoadTransferred()
    {
        Am engine = engine();
        long start = 40_000L;
        engine.rm(start);
        engine.setBaseline(snapshot(12934, 100L));
        engine.yx(Ar.V.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.yz();
        settle(engine, snapshot(12934, 50L), start + 600L);

        MeasuredChargeReadTracker tracker =
            new MeasuredChargeReadTracker();
        tracker.observe(blowpipe(50, 10), TARGET, System.currentTimeMillis());
        Cm use = tracker.observe(blowpipe(49, 10), TARGET, System.currentTimeMillis());
        assertNotNull(use);
        Ac booked = engine.mj(
            use,
            "Toxic blowpipe",
            Collections.singletonList(new Ab(12934, "Zulrah's scales", -1L, 100,
                -100L, Av.GRAND_EXCHANGE)),
            start + 5_000L);
        assertTrue("a validated load blocks nothing: the use is the cost", booked != null);
        assertEquals(100L, engine.getMetrics(start + 5_000L).costs);
    }

    @Test
    public void repeatedMeasuredSpendAndRefillBooksEachDecreaseOnce()
    {
        Am engine = engine();
        long start = 45_000L;
        engine.rm(start);

        assertNull(engine.abu(blowpipe(100, 10), TARGET, start));
        Cm first = engine.abu(
            blowpipe(98, 10), TARGET, start + 1_000L);
        assertNotNull(first);
        assertTrue(engine.mj(first, "Toxic blowpipe",
            Collections.singletonList(new Ab(12934, "Zulrah's scales", -2L, 100,
                -200L, Av.GRAND_EXCHANGE)), start + 1_000L) != null);

        // A refill is an increase, not a cost and becomes the new baseline.
        assertNull(engine.abu(blowpipe(120, 10), TARGET, start + 2_000L));
        Cm second = engine.abu(
            blowpipe(119, 10), TARGET, start + 3_000L);
        assertNotNull(second);
        assertTrue(engine.mj(second, "Toxic blowpipe",
            Collections.singletonList(new Ab(12934, "Zulrah's scales", -1L, 100,
                -100L, Av.GRAND_EXCHANGE)), start + 3_000L) != null);

        assertEquals(300L, engine.getMetrics(start + 3_000L).costs);
        assertEquals(-300L, engine.getMetrics(start + 3_000L).net);
        assertEquals(2, countTransactions(engine.getActiveSession(), Ai.CONSUMPTION));
    }

    @Test
    public void pauseAndProfileRestoreDiscardAnInFlightChargeBaseline()
    {
        Am engine = engine();
        long start = 55_000L;
        engine.rm(start);
        assertNull(engine.abu(blowpipe(100, 10), TARGET, start));

        engine.acu(start + 1_000L);
        engine.resume(start + 2_000L, Ed.LIFECYCLE);
        assertNull(engine.abu(blowpipe(90, 10), TARGET, start + 3_000L));

        engine.agl("profile-b", engine.qm(), start + 4_000L);
        assertNull(engine.abu(blowpipe(80, 10), TARGET, start + 5_000L));
        Cm afterRestart = engine.abu(
            blowpipe(79, 10), TARGET, start + 6_000L);
        assertNotNull(afterRestart);
        assertEquals(-1L, afterRestart.getComponentDeltas().get(0).getQuantityDelta());
    }

    @Test
    public void duplicateSameVariantWeaponsKeepTheirOwnBaselines()
    {
        Am engine = engine();
        long start = 65_000L;
        engine.rm(start);
        String second = "149:0:9764864:12934:V1b#2";
        assertNull(engine.abu(blowpipe(100, 10), TARGET, start));
        assertNull(engine.abu(blowpipe(40, 10), second, start + 500L));
        // Checking the second pipe again is not a decrease on the first.
        assertNull(engine.abu(blowpipe(40, 10), second, start + 1_000L));
        Cm firstUse = engine.abu(blowpipe(97, 10), TARGET, start + 2_000L);
        assertNotNull(firstUse);
        assertEquals(-3L, firstUse.getComponentDeltas().get(0).getQuantityDelta());
        Cm secondUse = engine.abu(blowpipe(39, 10), second, start + 3_000L);
        assertNotNull(secondUse);
        assertEquals(-1L, secondUse.getComponentDeltas().get(0).getQuantityDelta());
        assertTrue(engine.mj(firstUse, "Toxic blowpipe",
            Collections.singletonList(new Ab(12934, "Zulrah's scales", -3L, 100, -300L, Av.GRAND_EXCHANGE)), start + 2_000L) != null);
        assertTrue(engine.mj(secondUse, "Toxic blowpipe",
            Collections.singletonList(new Ab(12934, "Zulrah's scales", -1L, 100, -100L, Av.GRAND_EXCHANGE)), start + 3_000L) != null);
        assertEquals(400L, engine.getMetrics(start + 3_000L).costs);
        assertEquals(2, countTransactions(engine.getActiveSession(), Ai.CONSUMPTION));
    }

    /** Owner-approved: a top-up is measured against that weapon's own last Check. */
    @Test
    public void topUpIsMeasuredAgainstTheSameWeaponsLastCheckAcrossAnotherWeaponsCheck()
    {
        Am engine = engine();
        long start = 70_000L;
        engine.rm(start);
        engine.setBaseline(snapshot(12934, 100L));
        String second = "149:0:9764864:12934:V1b#2";
        assertNull(engine.abu(blowpipe(0, 10), TARGET, start));

        engine.yx(Ar.V.V1b,
            12934, "Zulrah's scales", TARGET, 8);
        engine.yz();
        Ac review = settle(engine, snapshot(12934, 50L), start + 600L);
        assertEquals(Ai.TRANSFER, review.tm());

        // The other blowpipe is Checked between the load and this pipe's next Check.
        assertNull(engine.abu(blowpipe(40, 10), second, start + 2_000L));
        assertNull(engine.abu(blowpipe(50, 10), TARGET, start + 3_000L));
        assertEquals("the load is confirmed by this pipe's own Check", Ai.TRANSFER,
            review.tm());
        assertEquals(0L, engine.getMetrics(start + 3_000L).net);
    }

    @Test
    public void aNewSameVariantInstanceInvalidatesTheStaleMeasuredBaseline()
    {
        Am engine = engine();
        long start = 95_000L;
        engine.rm(start);
        engine.setBaseline(snapshot(ItemID.TOXIC_BLOWPIPE_LOADED, 1L));
        assertNull(engine.abu(blowpipe(1000, 100), TARGET, start));

        // A second blowpipe is withdrawn into the same slot: that slot identity now belongs to
        // a different physical weapon, so the stored baseline must not be compared against it.
        settle(engine, snapshot(ItemID.TOXIC_BLOWPIPE_LOADED, 2L), start + 1_000L);

        assertNull("a replacement that retained the slot identity must not book a phantom decrease",
            engine.abu(blowpipe(100, 100), TARGET, start + 5_000L));
        // The fresh read seeds the new baseline; a later decrease still books normally.
        Cm use = engine.abu(blowpipe(98, 100), TARGET, start + 6_000L);
        assertNotNull(use);
        assertEquals(-2L, use.getComponentDeltas().get(0).getQuantityDelta());
    }

    @Test
    public void spendIsAttributedToTheActivityBeingPlayed()
    {
        Am engine = engine();
        long start = 75_000L;
        engine.rm(start);
        engine.getActiveSession().setActivityHint("Zulrah", start);
        assertNull(engine.abu(blowpipe(100, 10), TARGET, start));
        Cm use = engine.abu(blowpipe(98, 10), TARGET, start + 1_000L);
        Ac spend = engine.mj(use, "Toxic blowpipe",
            Collections.singletonList(new Ab(12934, "Zulrah's scales", -2L, 100, -200L, Av.GRAND_EXCHANGE)), start + 1_000L);
        assertTrue(spend != null);
        assertEquals("Zulrah", spend.getActivityName());
        engine.getActiveSession().setActivityHint("Vorkath", start + 2_000L);
        Cm later = engine.abu(blowpipe(97, 10), TARGET, start + 3_000L);
        Ac laterSpend = engine.mj(later, "Toxic blowpipe",
            Collections.singletonList(new Ab(12934, "Zulrah's scales", -1L, 100, -100L, Av.GRAND_EXCHANGE)), start + 3_000L);
        assertEquals("Vorkath", laterSpend.getActivityName());
    }

    @Test
    public void bottomlessCompostBucketUsesAreMeasuredAtHalfABucketEach()
    {
        Ar full = Ar.acs(
            "Your bottomless compost bucket has 42 uses of ultracompost left.");
        assertNotNull(full);
        assertEquals(Ar.V.V2, full.variant);
        assertTrue(full.bookable);
        assertEquals(Long.valueOf(42L), full.componentCounts.get(net.runelite.api.gameval.ItemID.BUCKET_ULTRACOMPOST));
        assertEquals(2, Ar.akr(full.variant, net.runelite.api.gameval.ItemID.BUCKET_ULTRACOMPOST));
        assertEquals(1, Ar.akr(Ar.V.V1b, 12934));
        Ar one = Ar.acs("Your bottomless compost bucket has 1 use of compost left.");
        assertNotNull(one);
        assertEquals(Long.valueOf(1L), one.componentCounts.get(net.runelite.api.gameval.ItemID.BUCKET_COMPOST));
        Ar empty = Ar.acs("Your bottomless compost bucket is currently empty.");
        assertNotNull(empty);
        assertTrue(empty.bookable);
        assertEquals(Ar.V.V2,
            Ar.aja(net.runelite.api.gameval.ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED));
        assertEquals(Ar.V.V2,
            Ar.ajb("Bottomless compost bucket"));

        // Two Checks, three uses apart, book one decrease of three uses on that bucket.
        Am engine = engine();
        long start = 85_000L;
        engine.rm(start);
        String bucket = "149:0:9764864:22997:V2";
        assertNull(engine.abu(full, bucket, start));
        Cm used = engine.abu(
            Ar.acs("Your bottomless compost bucket has 39 uses of ultracompost left."), bucket, start + 1_000L);
        assertNotNull(used);
        assertEquals(-3L, used.getComponentDeltas().get(0).getQuantityDelta());
    }

    private static int countTransactions(Ad session, Ai type)
    {
        int count = 0;
        for (Ac transaction : session.getTransactions())
        {
            if (transaction.getType() == type)
            {
                count++;
            }
        }
        return count;
    }

    private static Am engine()
    {
        return new Am(ChargeLoadReviewLifecycleTest::flowsForTest,
            new TransactionClassifier(), CONFIG);
    }

    private static Am engineWithSplitScaleFlow()
    {
        return new Am(deltas -> {
            Long scaleDelta = deltas.get(12934);
            if (scaleDelta == null || scaleDelta >= 0L || scaleDelta != -50L)
            {
                return flowsForTest(deltas);
            }
            return Arrays.asList(
                new Ab(12934, "Zulrah's scales", -20L, 100, -2_000L,
                    Av.GRAND_EXCHANGE),
                new Ab(12934, "Zulrah's scales", -30L, 100, -3_000L,
                    Av.GRAND_EXCHANGE));
        }, new TransactionClassifier(), CONFIG);
    }

    private static List<Ab> flowsForTest(Map<Integer, Long> deltas)
    {
        List<Ab> flows = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : deltas.entrySet())
        {
            int id = entry.getKey();
            String name = itemName(id);
            int price = id == 12934 ? 100 : id == 995 ? 1 : 500;
            flows.add(new Ab(id, name, entry.getValue(), price, entry.getValue() * price,
                Av.GRAND_EXCHANGE));
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

    private static Ac only(List<Ac> rows, Ai type)
    {
        Ac match = null;
        for (Ac row : rows)
        {
            if (row.tm() == type)
            {
                assertTrue("expected a single row of type " + type, match == null);
                match = row;
            }
        }
        assertNotNull("missing row of type " + type, match);
        return match;
    }

    private static Ar blowpipe(long scales, long darts)
    {
        return Ar.acs(
            "Darts: Rune dart x " + darts + ". Scales: " + scales + " (1.0%).");
    }

    private static Cc snapshot(int id, long quantity)
    {
        return snapshot(mapOf(id, quantity));
    }

    private static Cc snapshot(Map<Integer, Long> quantities)
    {
        return new Cc(quantities);
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

    private static Ac settle(Am engine, Cc next, long firstTick)
    {
        Ac result = engine.adj(next, firstTick);
        assertNull(result);
        result = engine.adj(next, firstTick + 600L);
        assertNull(result);
        return engine.adj(next, firstTick + 1_200L);
    }

}
