package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.*;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.*;

/** Typed outcomes retain exact component quantities and physical-target isolation. */
public class ChargeOutcomeTest
{
    /** A Check that books nothing; SAME and SPEND never pass. */
    static void none(ChargeDelta delta)
    {
        none("no booking", delta);
    }

    static void none(String message, ChargeDelta delta)
    {
        assertNotNull(message, delta);
        assertTrue(message, delta.result == ChargeDelta.CheckResult.SEED
            || delta.result == ChargeDelta.CheckResult.LOAD
            || delta.result == ChargeDelta.CheckResult.MISMATCH);
        assertTrue(message, delta.componentDeltas.isEmpty());
    }

    static ChargeDelta outcome(ChargeDelta.CheckResult expected, ChargeDelta delta)
    {
        assertNotNull(delta);
        assertEquals(expected, delta.result);
        return delta;
    }

    static ChargeCheck seasCheck(long charges)
    {
        return ChargeCheck.parse("Your Trident of the seas has " + charges + " charges.");
    }

    @Test
    public void firstAndUnchangedReadsNameDifferentOutcomes()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        outcome(ChargeDelta.CheckResult.SEED, tracker.observe(seasCheck(10), "a", 1));
        ChargeDelta same = outcome(ChargeDelta.CheckResult.SAME, tracker.observe(seasCheck(10), "a", 2));
        assertTrue(same.componentDeltas.isEmpty());
        assertEquals(2L, tracker.baseline.get("a").atEpochMillis);
    }

    @Test
    public void loadSeedsTheNextExactSpendWithoutAComponentLoss()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        outcome(ChargeDelta.CheckResult.SEED, tracker.observe(seasCheck(10), "a", 1));
        ChargeDelta load = outcome(ChargeDelta.CheckResult.LOAD, tracker.observe(seasCheck(20), "a", 2));
        assertTrue(load.componentDeltas.isEmpty());
        ChargeDelta spend = outcome(ChargeDelta.CheckResult.SPEND, tracker.observe(seasCheck(19), "a", 3));
        assertEquals(4, spend.componentDeltas.size());
        assertEquals(-1L, spend.componentDeltas.stream()
            .filter(component -> component.itemId == ItemID.DEATHRUNE).findFirst().get().quantityDelta);
    }

    @Test
    public void aMixedBlowpipeLoadKeepsIndependentMeasuredScaleSpend()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(ChargeCheck.parse("Darts: Adamant dart x 10. Scales: 100 (1.0%)."), "a", 1);
        ChargeDelta spend = outcome(ChargeDelta.CheckResult.SPEND,
            tracker.observe(ChargeCheck.parse("Darts: Adamant dart x 12. Scales: 98 (1.0%)."), "a", 2));
        assertEquals(1, spend.componentDeltas.size());
        assertEquals(ItemID.SNAKEBOSS_SCALE, spend.componentDeltas.get(0).itemId);
        assertEquals(-2L, spend.componentDeltas.get(0).quantityDelta);
    }

    @Test
    public void anUnsupportedReadMismatchesAndClearsThePriorBaseline()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(seasCheck(10), "a", 1);
        outcome(ChargeDelta.CheckResult.MISMATCH,
            tracker.observe(ChargeCheck.parse("Darts: Exotic dart x 100. Scales: 90 (1.0%)."), "a", 2));
        assertNull(tracker.baseline.get("a"));
        outcome(ChargeDelta.CheckResult.SEED, tracker.observe(seasCheck(9), "a", 3));
    }

    @Test
    public void aMissingReadKeepsTheLastExactBaselineAsBefore()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        tracker.observe(seasCheck(10), "a", 1);
        outcome(ChargeDelta.CheckResult.MISMATCH, tracker.observe(null, "a", 2));
        assertNotNull(tracker.baseline.get("a"));
        outcome(ChargeDelta.CheckResult.SPEND, tracker.observe(seasCheck(9), "a", 3));
    }

    @Test
    public void aVariantMismatchAndTwoPhysicalTargetsRemainIsolated()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        outcome(ChargeDelta.CheckResult.SEED, tracker.observe(seasCheck(10), "a", 1));
        outcome(ChargeDelta.CheckResult.SEED, tracker.observe(seasCheck(2), "b", 2));
        outcome(ChargeDelta.CheckResult.SPEND, tracker.observe(seasCheck(9), "a", 3));
        outcome(ChargeDelta.CheckResult.SAME, tracker.observe(seasCheck(2), "b", 4));
        outcome(ChargeDelta.CheckResult.MISMATCH,
            tracker.observe(ChargeCheck.parse("Your Trident of the swamp has 8 charges."), "a", 5));
        outcome(ChargeDelta.CheckResult.SPEND,
            tracker.observe(ChargeCheck.parse("Your Trident of the swamp has 7 charges."), "a", 6));
    }

    @Test
    public void aMeasuredLoadArmsTransferWithoutRequiringChargeWordingOrPrices()
    {
        GpManagerConfig config = new GpManagerConfig() {};
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[]{Client.class}, (proxy, method, args) ->
            {
                if (method.getName().equals("getTickCount")) return 10;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
        ChargeIntake intake = new ChargeIntake(client, null, config, engine, null);
        assertTrue(intake.startCheck(ChargeCheck.ChargedItem.TRIDENT_SEAS, "a", 10, 2));
        assertTrue(intake.observeCheck("Your Trident of the seas has 10 charges.", 1_001L));
        assertNull(engine.events.peek(Inbox.SOFT_TRANSFER));
        assertTrue(intake.startCheck(ChargeCheck.ChargedItem.TRIDENT_SEAS, "a", 10, 2));
        assertTrue(intake.observeCheck("Your Trident of the seas has 20 charges.", 1_002L));
        assertNotNull(engine.events.peek(Inbox.SOFT_TRANSFER));
        assertTrue(engine.session().transactions().isEmpty());
    }

    @Test
    public void aFreshStartLeavesEarlierEstimatesBookedButOutOfTheNextCheck()
    {
        GpManagerConfig config = new GpManagerConfig() {};
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
            new Class<?>[]{Client.class}, (proxy, method, args) ->
            {
                if (method.getName().equals("getTickCount")) return 10;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
        ChargeIntake intake = new ChargeIntake(client, null, config, engine, null);
        assertTrue(intake.startCheck(ChargeCheck.ChargedItem.TRIDENT_SEAS, "a", 10, 2));
        assertTrue(intake.observeCheck("Your Trident of the seas has 100 charges.", 1_001L));
        Transaction cast = engine.bookEstimatedCharge("a", "Trident of the seas", ActionKind.CAST,
            Collections.singletonList(new Flow(ItemID.DEATHRUNE, "Death rune", -1, 100, -100,
                PriceSource.GRAND_EXCHANGE)), 1_002L);
        String session = engine.session().id();
        assertEquals(1L, ChargeProbes.adp(engine.estimate, session, "a", "seas", ItemID.DEATHRUNE));

        // A recharge read ("charged with") arrives before its load books: a fresh start.
        assertTrue(intake.startCheck(ChargeCheck.ChargedItem.TRIDENT_SEAS, "a", 10, 2));
        assertTrue(intake.observeCheck("Your Trident of the seas has 150 charges.", 1_003L));
        assertEquals("the next Check never reconciles it", 0L,
            ChargeProbes.adp(engine.estimate, session, "a", "seas", ItemID.DEATHRUNE));
        assertTrue("the cast stays booked", cast.counts());
        assertEquals(100L, engine.metrics(2_000L).cost);
    }

    @Test
    public void nonSpendOutcomesCannotAuthorizeValuedLosses()
    {
        ChargeDelta load = new ChargeDelta(ChargeCheck.ChargedItem.TRIDENT_SEAS,
            Collections.singletonList(new ChargeDelta.ComponentDelta(ItemID.DEATHRUNE, -1)),
            ChargeDelta.CheckResult.LOAD);
        assertEquals(0L, load.exactCost(Collections.singletonList(
            new Flow(ItemID.DEATHRUNE, "Death rune", -1, 100, -100, PriceSource.GRAND_EXCHANGE))));
    }
}
