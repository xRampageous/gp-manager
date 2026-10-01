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

        Transaction receipt = engine.bookEstimatedChargeUsage("slot-a", "seas",
            ActionKind.CAST, flows(
                flow(ItemID.DEATHRUNE, "Death rune", -2L, 100),
                flow(ItemID.CHAOSRUNE, "Chaos rune", -2L, 50)), T0 + 1L);

        assertNotNull(receipt);
        assertEquals(ClassificationConfidence.LIKELY, receipt.getConfidence());
        assertEquals("Estimated", receipt.getExplanation());
        assertEquals(-300L, receipt.getNet());
        assertEquals(2L, engine.chargeEstimateJournal.pendingQuantity(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.DEATHRUNE));
        assertEquals(2L, engine.chargeEstimateJournal.pendingQuantity(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.CHAOSRUNE));
    }

    @Test
    public void checkShrinksEstimatesAtCapturedPricesAndBooksOnlyMeasuredRemainder()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        Transaction estimate = engine.bookEstimatedChargeUsage("slot-a", "seas",
            ActionKind.CAST, flows(
                flow(ItemID.DEATHRUNE, "Death rune", -3L, 100),
                flow(ItemID.CHAOSRUNE, "Chaos rune", -1L, 50),
                flow(ItemID.FIRERUNE, "Fire rune", -10L, 5),
                new Flow(ItemID.COINS, "Coins", -20L, 1, -20L, PriceSource.FACE_VALUE)), T0 + 1L);

        ChargeDelta delta = new ChargeDelta(ChargeRead.Variant.TRIDENT_SEAS,
            Arrays.asList(new ChargeDelta.ComponentDelta(ItemID.DEATHRUNE, -2L),
                new ChargeDelta.ComponentDelta(ItemID.CHAOSRUNE, -3L),
                new ChargeDelta.ComponentDelta(ItemID.FIRERUNE, -10L),
                new ChargeDelta.ComponentDelta(ItemID.COINS, -20L)));
        Transaction measured = engine.bookChargeSpend(delta, "Trident of the seas",
            flows(
                flow(ItemID.DEATHRUNE, "Death rune", -2L, 100),
                flow(ItemID.CHAOSRUNE, "Chaos rune", -3L, 50),
                flow(ItemID.FIRERUNE, "Fire rune", -10L, 5),
                new Flow(ItemID.COINS, "Coins", -20L, 1, -20L, PriceSource.FACE_VALUE)),
            T0 + 2L, "slot-a");

        assertNotNull(measured);
        assertEquals(2L, estimate.quantity(ItemID.DEATHRUNE, false));
        assertEquals(100, estimate.getFlows().get(0).unitPrice);
        assertEquals(1L, estimate.quantity(ItemID.CHAOSRUNE, false));
        assertEquals(ClassificationConfidence.CONFIRMED, estimate.getConfidence());
        assertEquals("Confirmed", estimate.getExplanation());
        assertEquals(2L, measured.quantity(ItemID.CHAOSRUNE, false));
        assertEquals(0L, engine.chargeEstimateJournal.pendingQuantity(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.DEATHRUNE));
        assertEquals(0L, engine.chargeEstimateJournal.pendingQuantity(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.CHAOSRUNE));
    }

    @Test
    public void manualCorrectionSurvivesReconciliationWithoutRepricing()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        Transaction estimate = engine.bookEstimatedChargeUsage("slot-a", "seas",
            ActionKind.CAST, Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -3L, 100)),
            T0 + 1L);
        assertTrue(engine.getActiveSession().correctTransaction(estimate.getId(),
            Correction.COST, T0 + 2L, "Owner decision"));

        ChargeDelta delta = new ChargeDelta(ChargeRead.Variant.TRIDENT_SEAS,
            Collections.singletonList(new ChargeDelta.ComponentDelta(ItemID.DEATHRUNE, -2L)));
        Transaction measured = engine.bookChargeSpend(delta, "Trident of the seas",
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -2L, 100)), T0 + 3L, "slot-a");

        assertNull(measured);
        assertEquals(Correction.COST, estimate.getCorrection());
        assertEquals(3L, estimate.quantity(ItemID.DEATHRUNE, false));
        assertEquals(100, estimate.getFlows().get(0).unitPrice);
    }

    @Test
    public void estimatesCannotReconcileAcrossSessions()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        Transaction old = engine.bookEstimatedChargeUsage("slot-a", "seas",
            ActionKind.CAST, Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -2L, 100)),
            T0 + 1L);
        String oldSession = engine.getActiveSession().getId();
        engine.startCustomSession("Custom", SessionMode.GENERAL, T0 + 2L);

        ChargeDelta delta = new ChargeDelta(ChargeRead.Variant.TRIDENT_SEAS,
            Collections.singletonList(new ChargeDelta.ComponentDelta(ItemID.DEATHRUNE, -2L)));
        Transaction measured = engine.bookChargeSpend(delta, "Trident of the seas",
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -2L, 100)), T0 + 3L, "slot-a");

        assertNotNull(measured);
        assertEquals(2L, old.quantity(ItemID.DEATHRUNE, false));
        assertEquals(2L, measured.quantity(ItemID.DEATHRUNE, false));
        assertEquals(0L, engine.chargeEstimateJournal.pendingQuantity(oldSession,
            "slot-a", "seas", ItemID.DEATHRUNE));
    }

    @Test
    public void lifecycleResetClearsTransientEstimates()
    {
        Engine engine = engine();
        engine.ensureSession(T0);
        engine.bookEstimatedChargeUsage("slot-a", "seas", ActionKind.CAST,
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -1L, 100)), T0 + 1L);
        engine.pauseForLifecycle(T0 + 2L);
        assertEquals(0L, engine.chargeEstimateJournal.pendingQuantity(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.DEATHRUNE));
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

    private static List<Flow> tridentLosses(long death)
    {
        return flows(flow(ItemID.DEATHRUNE, "Death rune", death, 100),
            flow(ItemID.CHAOSRUNE, "Chaos rune", death, 50),
            flow(ItemID.FIRERUNE, "Fire rune", death * 5L, 5),
            new Flow(ItemID.COINS, "Coins", death * 10L, 1, death * 10L,
                PriceSource.FACE_VALUE));
    }
}
