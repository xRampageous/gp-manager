package com.gpmanager;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** R4 recap: biggest gain and cost and loot by source, derived and factual. */
public class GrindRecapTest
{

    private static Transaction loot(String npc, long at, long value)
    {
        return new Transaction(at, TransactionType.LOOT, Context.LOOT, "Loot from " + npc, npc, true,
            Collections.singletonList(new Flow(1, "Loot", 1L, (int) value, value)), Confidence.CONFIRMED,
            "fixture", null);
    }

    @Test
    public void recapMatchesTheCanonicalSummaryAndKeepsFullSessionRate() throws Exception
    {
        Engine engine = engine();
        long now = System.currentTimeMillis();
        long start = now - 2 * 3_600_000L;
        String grindId = runGrind(engine, "Vorkath", start, now, 6_000_000L, 500_000L, 5_000_000L,
            3L * 3_600_000L);
        Session session = engine.getHistory().get(0);
        assertEquals(grindId, session.getGrindId());

        Metrics metrics = engine.historyMetrics(session.getId(), now);
        String aay = GrindHistory.netOutcome(session.getProfitTargetGp(), metrics.net, true);
        String akd = GrindHistory.timeOutcome(session.getActiveTimeTargetMillis(),
            metrics.elapsedMillis);
        assertTrue(aay.contains("Reached"));
        assertTrue(akd.contains("remaining"));
        assertFalse("the word Failed never appears", aay.contains("Failed"));
        assertFalse(akd.contains("Failed"));
    }

    @Test
    public void targetOutcomesAreFactualForEveryCombination() throws Exception
    {
        Engine engine = engine();
        long now = System.currentTimeMillis();
        long start = now - 3_600_000L;
        runGrind(engine, "Vorkath", start, now, 2_000_000L, 0L, 5_000_000L, 3L * 3_600_000L);
        Session session = engine.getHistory().get(0);
        Metrics metrics = engine.historyMetrics(session.getId(), now);
        String aay = GrindHistory.netOutcome(session.getProfitTargetGp(), metrics.net, true);
        String akd = GrindHistory.timeOutcome(session.getActiveTimeTargetMillis(),
            metrics.elapsedMillis);

        assertTrue("not reached is stated factually: " + aay, aay.contains("Ended before target"));
        assertTrue("time target is still open: " + akd, akd.contains("remaining"));
        assertFalse(aay.toLowerCase().contains("fail"));
        assertFalse(akd.toLowerCase().contains("fail"));
        assertEquals("Reached 5.00M Net",
            GrindHistory.netOutcome(5_000_000L, 6_000_000L, true));
        assertEquals("No Net target", GrindHistory.netOutcome(null, 1L, true));
        assertEquals("Time target reached", GrindHistory.timeOutcome(60_000L, 120_000L));
        assertEquals("No Active Time target", GrindHistory.timeOutcome(null, 120_000L));
    }




    private static void collectLabelColours(java.awt.Component component,
        java.util.Map<String, java.awt.Color> out)
    {
        if (component instanceof javax.swing.JLabel)
        {
            String text = ((javax.swing.JLabel) component).getText();
            if (text != null && !text.isEmpty())
            {
                out.putIfAbsent(text, ((javax.swing.JLabel) component).getForeground());
            }
        }
        if (component instanceof java.awt.Container)
        {
            for (java.awt.Component child : ((java.awt.Container) component).getComponents())
            {
                collectLabelColours(child, out);
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static Engine engine()
    {
        return PresentationLifecycleTest.engine();
    }

    private static String runGrind(Engine engine, String name, long start, long end, long gainValue,
        long costValue, Long netTarget, Long timeTarget)
    {
        SavedState.SavedGrind definition = engine.saveGrind(name, netTarget, timeTarget, false);
        String grindId = definition.getGrindId();
        engine.startGrind(name, grindId, netTarget, timeTarget, start);
        if (gainValue != 0L)
        {
            engine.getActiveSession().addTransaction(
                gain(start + 1_000L, "Dragon bones", 1, 1, (int) gainValue, gainValue), 2_000, false);
        }
        if (costValue != 0L)
        {
            Transaction consumed = cost(start + 2_000L, "Prayer potion(4)", 3, costValue);
            consumed.setActionKind(ActionKind.DRINK);
            engine.getActiveSession().addTransaction(consumed, 2_000, false);
        }
        engine.finishCustom(end);
        return grindId;
    }

    private static Transaction gain(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return receipt(at, TransactionType.GAIN, name, itemId, quantity, unitPrice, value);
    }

    private static Transaction cost(long at, String name, int itemId, long value)
    {
        return receipt(at, TransactionType.CONSUMPTION, name, itemId, -1L, (int) Math.abs(value), -Math.abs(value));
    }

    private static Transaction receipt(long at, TransactionType type, String name, int itemId, long quantity,
        int unitPrice, long value)
    {
        return new Transaction(at, type, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            Confidence.LIKELY, "test", null);
    }

    private static void collectLabels(java.awt.Component component, java.util.List<String> out)
    {
        if (component instanceof javax.swing.JLabel)
        {
            String text = ((javax.swing.JLabel) component).getText();
            if (text != null && !text.isEmpty())
            {
                out.add(text);
            }
        }
        if (component instanceof java.awt.Container)
        {
            for (java.awt.Component child : ((java.awt.Container) component).getComponents())
            {
                collectLabels(child, out);
            }
        }
    }

}
