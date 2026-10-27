package com.gpmanager;

import java.util.*;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.*;

/** Focused contracts for automatic charge estimates and Check reconciliation. */
public class ChargeEstimateReconciliationTest
{
    private static final long T0 = 10_000L;

    @Test
    public void estimatedUsageBooksLikelyCostAndKeepsComponentJournal()
    {
        Engine engine = engine();
        engine.ensureSession(T0);

        Transaction receipt = engine.bookEstimatedCharge("slot-a", "seas",
            ActionKind.CAST, flows(
                flow(ItemID.DEATHRUNE, "Death rune", -2L, 100),
                flow(ItemID.CHAOSRUNE, "Chaos rune", -2L, 50)), T0 + 1L);

        assertNotNull(receipt);
        assertEquals(Confidence.LIKELY, receipt.confidence());
        assertEquals("Estimated", receipt.explanation());
        assertEquals(-300L, receipt.net());
        assertEquals(2L, ChargeProbes.adp(engine.estimate,
            engine.session().id(), "slot-a", "seas", ItemID.DEATHRUNE));
        assertEquals(2L, ChargeProbes.adp(engine.estimate,
            engine.session().id(), "slot-a", "seas", ItemID.CHAOSRUNE));
    }

    @Test
    public void checkShrinksEstimatesAtCapturedPricesAndBooksOnlyMeasuredRemainder()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        Transaction estimate = engine.bookEstimatedCharge("slot-a", "seas",
            ActionKind.CAST, flows(
                flow(ItemID.DEATHRUNE, "Death rune", -3L, 100),
                flow(ItemID.CHAOSRUNE, "Chaos rune", -1L, 50),
                flow(ItemID.FIRERUNE, "Fire rune", -10L, 5),
                new Flow(ItemID.COINS, "Coins", -20L, 1, -20L, PriceSource.FACE_VALUE)), T0 + 1L);

        ChargeDelta delta = new ChargeDelta(ChargeCheck.ChargedItem.TRIDENT_SEAS,
            Arrays.asList(new ChargeDelta.ComponentDelta(ItemID.DEATHRUNE, -2L),
                new ChargeDelta.ComponentDelta(ItemID.CHAOSRUNE, -3L),
                new ChargeDelta.ComponentDelta(ItemID.FIRERUNE, -10L),
                new ChargeDelta.ComponentDelta(ItemID.COINS, -20L)), ChargeDelta.CheckResult.SPEND);
        Transaction measured = engine.bookChargeSpend(delta, "Trident of the seas",
            flows(
                flow(ItemID.DEATHRUNE, "Death rune", -2L, 100),
                flow(ItemID.CHAOSRUNE, "Chaos rune", -3L, 50),
                flow(ItemID.FIRERUNE, "Fire rune", -10L, 5),
                new Flow(ItemID.COINS, "Coins", -20L, 1, -20L, PriceSource.FACE_VALUE)),
            T0 + 2L, "slot-a");

        assertNotNull(measured);
        assertEquals(2L, estimate.quantity(ItemID.DEATHRUNE, false));
        assertEquals(100, estimate.flows().get(0).unitPrice);
        assertEquals(1L, estimate.quantity(ItemID.CHAOSRUNE, false));
        assertEquals(Confidence.CONFIRMED, estimate.confidence());
        assertEquals("Confirmed", estimate.explanation());
        assertEquals(2L, measured.quantity(ItemID.CHAOSRUNE, false));
        assertEquals(0L, ChargeProbes.adp(engine.estimate,
            engine.session().id(), "slot-a", "seas", ItemID.DEATHRUNE));
        assertEquals(0L, ChargeProbes.adp(engine.estimate,
            engine.session().id(), "slot-a", "seas", ItemID.CHAOSRUNE));
    }

    @Test
    public void manualCorrectionSurvivesReconciliationWithoutRepricing()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        Transaction estimate = engine.bookEstimatedCharge("slot-a", "seas",
            ActionKind.CAST, Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -3L, 100)),
            T0 + 1L);
        assertTrue(engine.session().correctTransaction(estimate.id(),
            Correction.COST, "Owner decision"));

        ChargeDelta delta = new ChargeDelta(ChargeCheck.ChargedItem.TRIDENT_SEAS,
            Collections.singletonList(new ChargeDelta.ComponentDelta(ItemID.DEATHRUNE, -2L)), ChargeDelta.CheckResult.SPEND);
        Transaction measured = engine.bookChargeSpend(delta, "Trident of the seas",
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -2L, 100)), T0 + 3L, "slot-a");

        assertNull(measured);
        assertEquals(Correction.COST, estimate.correction());
        assertEquals(3L, estimate.quantity(ItemID.DEATHRUNE, false));
        assertEquals(100, estimate.flows().get(0).unitPrice);
    }

    @Test
    public void estimatesCannotReconcileAcrossSessions()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        Transaction old = engine.bookEstimatedCharge("slot-a", "seas",
            ActionKind.CAST, Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -2L, 100)),
            T0 + 1L);
        String oldSession = engine.session().id();
        engine.startCustom("Custom", T0 + 2L);

        ChargeDelta delta = new ChargeDelta(ChargeCheck.ChargedItem.TRIDENT_SEAS,
            Collections.singletonList(new ChargeDelta.ComponentDelta(ItemID.DEATHRUNE, -2L)), ChargeDelta.CheckResult.SPEND);
        Transaction measured = engine.bookChargeSpend(delta, "Trident of the seas",
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -2L, 100)), T0 + 3L, "slot-a");

        assertNotNull(measured);
        assertEquals(2L, old.quantity(ItemID.DEATHRUNE, false));
        assertEquals(2L, measured.quantity(ItemID.DEATHRUNE, false));
        assertEquals(0L, ChargeProbes.adp(engine.estimate, oldSession,
            "slot-a", "seas", ItemID.DEATHRUNE));
    }

    @Test
    public void lifecycleResetClearsTransientEstimates()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        engine.bookEstimatedCharge("slot-a", "seas", ActionKind.CAST,
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -1L, 100)), T0 + 1L);
        engine.pauseForLifecycle(T0 + 2L);
        assertEquals(0L, ChargeProbes.adp(engine.estimate,
            engine.session().id(), "slot-a", "seas", ItemID.DEATHRUNE));
    }

    @Test
    public void aMeasuredCheckClearsItsWeaponsPendingEstimates()
    {
        ChargeEstimateJournal journal = new ChargeEstimateJournal();
        journal.record("s1", "target", "tx1", 23962, 200L);
        journal.record("s2", "target", "tx2", 20718, 20L);
        assertEquals(1, journal.receipts("s1", "target", 23962).size());

        journal.clearTarget("s1", "target");
        assertTrue("the checked weapon's estimate receipts are gone",
            journal.receipts("s1", "target", 23962).isEmpty());
        assertEquals("another session stays untouched", 1,
            journal.receipts("s2", "target", 20718).size());
    }

    private static Engine engine()
    {
        return new Engine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig() {});
    }

    private static Flow flow(int id, String name, long quantity, int unit)
    {
        return new Flow(id, name, quantity, unit, quantity * unit, PriceSource.GRAND_EXCHANGE);
    }

    private static List<Flow> flows(Flow... values)
    {
        return Arrays.asList(values);
    }

}
