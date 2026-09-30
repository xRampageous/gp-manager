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
        Am engine = engine();
        engine.rm(T0);

        Ac receipt = engine.mn("slot-a", "seas",
            Au.CAST, flows(
                flow(ItemID.DEATHRUNE, "Death rune", -2L, 100),
                flow(ItemID.CHAOSRUNE, "Chaos rune", -2L, 50)), T0 + 1L);

        assertNotNull(receipt);
        assertEquals(Bd.LIKELY, receipt.getConfidence());
        assertEquals("Estimated", receipt.getExplanation());
        assertEquals(-300L, receipt.getNet());
        assertEquals(2L, engine.chargeEstimateJournal.adp(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.DEATHRUNE));
        assertEquals(2L, engine.chargeEstimateJournal.adp(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.CHAOSRUNE));
    }

    @Test
    public void checkShrinksEstimatesAtCapturedPricesAndBooksOnlyMeasuredRemainder()
    {
        Am engine = engine();
        engine.rm(T0);
        Ac estimate = engine.mn("slot-a", "seas",
            Au.CAST, flows(
                flow(ItemID.DEATHRUNE, "Death rune", -3L, 100),
                flow(ItemID.CHAOSRUNE, "Chaos rune", -1L, 50),
                flow(ItemID.FIRERUNE, "Fire rune", -10L, 5),
                new Ab(ItemID.COINS, "Coins", -20L, 1, -20L, Av.FACE_VALUE)), T0 + 1L);

        Cm delta = new Cm(Ar.V.TRIDENT_SEAS,
            Arrays.asList(new Cm.ComponentDelta(ItemID.DEATHRUNE, -2L),
                new Cm.ComponentDelta(ItemID.CHAOSRUNE, -3L),
                new Cm.ComponentDelta(ItemID.FIRERUNE, -10L),
                new Cm.ComponentDelta(ItemID.COINS, -20L)));
        Ac measured = engine.mj(delta, "Trident of the seas",
            flows(
                flow(ItemID.DEATHRUNE, "Death rune", -2L, 100),
                flow(ItemID.CHAOSRUNE, "Chaos rune", -3L, 50),
                flow(ItemID.FIRERUNE, "Fire rune", -10L, 5),
                new Ab(ItemID.COINS, "Coins", -20L, 1, -20L, Av.FACE_VALUE)),
            T0 + 2L, "slot-a");

        assertNotNull(measured);
        assertEquals(2L, estimate.quantity(ItemID.DEATHRUNE, false));
        assertEquals(100, estimate.getFlows().get(0).unitPrice);
        assertEquals(1L, estimate.quantity(ItemID.CHAOSRUNE, false));
        assertEquals(Bd.CONFIRMED, estimate.getConfidence());
        assertEquals("Confirmed", estimate.getExplanation());
        assertEquals(2L, measured.quantity(ItemID.CHAOSRUNE, false));
        assertEquals(0L, engine.chargeEstimateJournal.adp(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.DEATHRUNE));
        assertEquals(0L, engine.chargeEstimateJournal.adp(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.CHAOSRUNE));
    }

    @Test
    public void manualCorrectionSurvivesReconciliationWithoutRepricing()
    {
        Am engine = engine();
        engine.rm(T0);
        Ac estimate = engine.mn("slot-a", "seas",
            Au.CAST, Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -3L, 100)),
            T0 + 1L);
        assertTrue(engine.getActiveSession().qi(estimate.getId(),
            Ah.COST, T0 + 2L, "Owner decision"));

        Cm delta = new Cm(Ar.V.TRIDENT_SEAS,
            Collections.singletonList(new Cm.ComponentDelta(ItemID.DEATHRUNE, -2L)));
        Ac measured = engine.mj(delta, "Trident of the seas",
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -2L, 100)), T0 + 3L, "slot-a");

        assertNull(measured);
        assertEquals(Ah.COST, estimate.getCorrection());
        assertEquals(3L, estimate.quantity(ItemID.DEATHRUNE, false));
        assertEquals(100, estimate.getFlows().get(0).unitPrice);
    }

    @Test
    public void estimatesCannotReconcileAcrossSessions()
    {
        Am engine = engine();
        engine.rm(T0);
        Ac old = engine.mn("slot-a", "seas",
            Au.CAST, Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -2L, 100)),
            T0 + 1L);
        String oldSession = engine.getActiveSession().getId();
        engine.ajl("Custom", Cx.GENERAL, T0 + 2L);

        Cm delta = new Cm(Ar.V.TRIDENT_SEAS,
            Collections.singletonList(new Cm.ComponentDelta(ItemID.DEATHRUNE, -2L)));
        Ac measured = engine.mj(delta, "Trident of the seas",
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -2L, 100)), T0 + 3L, "slot-a");

        assertNotNull(measured);
        assertEquals(2L, old.quantity(ItemID.DEATHRUNE, false));
        assertEquals(2L, measured.quantity(ItemID.DEATHRUNE, false));
        assertEquals(0L, engine.chargeEstimateJournal.adp(oldSession,
            "slot-a", "seas", ItemID.DEATHRUNE));
    }

    @Test
    public void lifecycleResetClearsTransientEstimates()
    {
        Am engine = engine();
        engine.rm(T0);
        engine.mn("slot-a", "seas", Au.CAST,
            Collections.singletonList(flow(ItemID.DEATHRUNE, "Death rune", -1L, 100)), T0 + 1L);
        engine.acu(T0 + 2L);
        assertEquals(0L, engine.chargeEstimateJournal.adp(
            engine.getActiveSession().getId(), "slot-a", "seas", ItemID.DEATHRUNE));
    }

    private static Am engine()
    {
        return new Am(deltas -> Collections.emptyList(),
            new TransactionClassifier(), new GpManagerConfig() {});
    }

    private static Ab flow(int id, String name, long quantity, int unit)
    {
        return new Ab(id, name, quantity, unit, quantity * unit, Av.GRAND_EXCHANGE);
    }

    private static List<Ab> flows(Ab... values)
    {
        return Arrays.asList(values);
    }

    private static List<Ab> tridentLosses(long death)
    {
        return flows(flow(ItemID.DEATHRUNE, "Death rune", death, 100),
            flow(ItemID.CHAOSRUNE, "Chaos rune", death, 50),
            flow(ItemID.FIRERUNE, "Fire rune", death * 5L, 5),
            new Ab(ItemID.COINS, "Coins", death * 10L, 1, death * 10L,
                Av.FACE_VALUE));
    }
}
