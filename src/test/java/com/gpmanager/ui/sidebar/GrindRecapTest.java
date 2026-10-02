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
    /** Owner 2026-09-28: a finished Grind lists its loot by the NPC that dropped it, top five by value. */
    @Test
    public void lootBySourceGroupsDropsByNpc()
    {
        Ad session = new Ad("Slayer", 1_000L);
        session.kf(loot("Greater Nechryael", 2_000L, 7_202L), 100);
        session.kf(loot("Death spawn", 3_000L, 0L), 100);
        session.kf(loot("Greater Nechryael", 4_000L, 4_140L), 100);
        session.kf(loot("Abyssal demon", 5_000L, 30_000L), 100);
        Ac pickup = loot("Greater Nechryael", 6_000L, 999L);
        pickup.type = Ai.GAIN;
        session.kf(pickup, 100);
        java.util.List<Ba.Highlight> sources = Ba.sources(session);
        assertEquals("Abyssal demon", sources.get(0).name);
        assertEquals(30_000L, sources.get(0).net);
        assertEquals("Greater Nechryael", sources.get(1).name);
        assertEquals("only loot counts, not a stray pickup", 11_342L, sources.get(1).net);
    }

    private static Ac loot(String npc, long at, long value)
    {
        return new Ac(at, null, Ai.LOOT, Aj.LOOT, "Loot from " + npc, npc, true,
            Collections.singletonList(new Ab(1, "Loot", 1L, (int) value, value)), Bd.CONFIRMED,
            "fixture", null);
    }

    @Test
    public void recapMatchesTheCanonicalSummaryAndKeepsFullSessionRate() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        long start = now - 2 * 3_600_000L;
        String grindId = runGrind(engine, "Vorkath", start, now, 6_000_000L, 500_000L, 5_000_000L,
            3L * 3_600_000L);
        Ad session = engine.getHistory().get(0);
        assertEquals(grindId, session.getGrindId());

        Ba facts = Ba.of(session);
        Bu metrics = engine.tz(session.getId(), now);
        String aay = Ba.aay(session.getProfitTargetGp(), metrics.net, true);
        String akd = Ba.akd(session.getActiveTimeTargetMillis(),
            metrics.elapsedMillis);
        assertFalse("detail is retained, so no highlight fails closed", facts.recap.highlightsUnavailable);
        assertNotNull(facts.recap.biggestGain);
        assertEquals(6_000_000L, facts.recap.biggestGain.net);
        assertNotNull(facts.recap.lz);
        assertEquals("Prayer potion(4)", facts.recap.lz.name);
        assertEquals(-500_000L, facts.recap.lz.net);
        assertTrue(aay.contains("Reached"));
        assertTrue(akd.contains("remaining"));
        assertFalse("the word Failed never appears", aay.contains("Failed"));
        assertFalse(akd.contains("Failed"));
    }

    @Test
    public void targetOutcomesAreFactualForEveryCombination() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        long start = now - 3_600_000L;
        runGrind(engine, "Vorkath", start, now, 2_000_000L, 0L, 5_000_000L, 3L * 3_600_000L);
        Ad session = engine.getHistory().get(0);
        Bu metrics = engine.tz(session.getId(), now);
        String aay = Ba.aay(session.getProfitTargetGp(), metrics.net, true);
        String akd = Ba.akd(session.getActiveTimeTargetMillis(),
            metrics.elapsedMillis);

        assertTrue("not reached is stated factually: " + aay, aay.contains("Ended before target"));
        assertTrue("time target is still open: " + akd, akd.contains("remaining"));
        assertFalse(aay.toLowerCase().contains("fail"));
        assertFalse(akd.toLowerCase().contains("fail"));
        assertEquals("Reached 5.00M Net",
            Ba.aay(5_000_000L, 6_000_000L, true));
        assertEquals("No Net target", Ba.aay(null, 1L, true));
        assertEquals("Time target reached", Ba.akd(60_000L, 120_000L));
        assertEquals("No Active Time target", Ba.akd(null, 120_000L));
    }

    @Test
    public void compactedGrindKeepsAggregatesAndFailsHighlightsClosed() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        long start = now - 2 * 3_600_000L;
        runGrind(engine, "Vorkath", start, now, 6_000_000L, 500_000L, null, null);
        Ad session = engine.getHistory().get(0);
        session.pj(now - 3_600_000L, transaction -> false);

        Ba facts = Ba.of(session);
        assertTrue(facts.recap.highlightsUnavailable);
        assertNull("no fake historical winner is inferred", facts.recap.biggestGain);
        assertNull(facts.recap.lz);
    }

    @Test
    public void highlightsAreCorrectionAwareAndNeverCountTransfers() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        long start = now - 2 * 3_600_000L;
        engine.avg("Vorkath", null, null, false, null);
        String grindId = engine.getSavedGrinds(false).get(0).getGrindId();
        engine.startGrind("Vorkath", grindId, null, null, start);
        Ac drop = gain(start + 1_000L, "Tanzanite fang", 2, 1, 3_100_000, 3_100_000L);
        engine.getActiveSession().kf(drop, 2_000);
        engine.getActiveSession().kf(gain(start + 2_000L, "Dragon bones", 1, 1, 3_200, 3_200L), 2_000);
        engine.getActiveSession().kf(cost(start + 3_000L, "Prayer potion(4)", 3, 184_000L), 2_000);
        // A big transfer must never become the "biggest gain".
        engine.getActiveSession().kf(new Ac(start + 4_000L, null, Ai.TRANSFER,
            Aj.TRANSFER, "", "Bank transfer", true,
            Collections.singletonList(new Ab(995, "Coins", 50_000_000, 1, 50_000_000L)),
            Bd.LIKELY, "test", null), 2_000);
        // The received drop is corrected to a cost: the highlight must follow the correction.
        assertTrue(engine.qi(drop.getId(), Ah.COST, start + 5_000L, "test"));
        engine.sx(now);

        Ba facts = Ba.of(engine.getHistory().get(0));
        assertNotNull(facts.recap.biggestGain);
        assertEquals("Dragon bones", facts.recap.biggestGain.name);
        assertNotNull(facts.recap.lz);
        assertEquals("Tanzanite fang", facts.recap.lz.name);
        assertTrue(facts.recap.lz.net < 0L);
    }

    @Test
    public void grindsDetailRendersTheRecap() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        String grindId = engine.avg("Vorkath", 5_000_000L, null, false, null).getGrindId();
        runLinked(engine, "Vorkath", grindId, now - 9 * 3_600_000L, now - 7 * 3_600_000L, 2_000_000L);
        String b = runLinked(engine, "Vorkath", grindId, now - 3 * 3_600_000L, now - 1 * 3_600_000L, 4_000_000L);

        java.util.List<String> labels = onEdt(() ->
        {
            Dp panel = new Dp(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.openGrindsDetail(panel, b);
            SidebarPanelProbe.refresh(panel);
            java.util.List<String> found = new java.util.ArrayList<>();
            collectLabels(panel, found);
            return found;
        });
        assertTrue(anyContains(labels, "COMPLETE"));
        assertTrue(anyContains(labels, "HIGHLIGHTS"));
        assertFalse("1.0.4: no previous-run comparison", anyContains(labels, "VS PREVIOUS RUN"));
        assertFalse("1.0.4: no personal bests", anyContains(labels, "PERSONAL BESTS"));
        assertTrue(anyContains(labels, "Dragon bones"));
        assertTrue("the Gain category reads Gains", anyContains(labels, "GAINS"));
        assertFalse("visible Revenue is gone", labels.stream().anyMatch(label -> label.contains("Revenue")));
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

    private static Am engine()
    {
        return PresentationLifecycleTest.engine();
    }

    private static String runGrind(Am engine, String name, long start, long end, long gainValue,
        long costValue, Long netTarget, Long timeTarget)
    {
        SavedState.Ap definition = engine.avg(name, netTarget, timeTarget, false, null);
        String grindId = definition.getGrindId();
        engine.startGrind(name, grindId, netTarget, timeTarget, start);
        if (gainValue != 0L)
        {
            engine.getActiveSession().kf(
                gain(start + 1_000L, "Dragon bones", 1, 1, (int) gainValue, gainValue), 2_000);
        }
        if (costValue != 0L)
        {
            Ac consumed = cost(start + 2_000L, "Prayer potion(4)", 3, costValue);
            consumed.setActionKind(Au.DRINK);
            engine.getActiveSession().kf(consumed, 2_000);
        }
        engine.sx(end);
        return grindId;
    }

    private static String runLinked(Am engine, String name, String grindId, long start, long end,
        long net)
    {
        engine.startGrind(name, grindId, null, null, start);
        if (net >= 0L)
        {
            engine.getActiveSession().kf(gain(start + 1_000L, "Dragon bones", 1, 1, (int) net, net), 2_000);
        }
        else
        {
            engine.getActiveSession().kf(cost(start + 1_000L, "Prayer potion(4)", 3, -net), 2_000);
        }
        engine.sx(end);
        return engine.getHistory().get(0).getId();
    }

    private static String runUnlinked(Am engine, String name, long start, long end, long net)
    {
        engine.ajl(name, Cx.GENERAL, start);
        engine.getActiveSession().kf(gain(start + 1_000L, "Dragon bones", 1, 1, (int) net, net), 2_000);
        engine.sx(end);
        return engine.getHistory().get(0).getId();
    }

    private static Ac gain(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return receipt(at, Ai.GAIN, name, itemId, quantity, unitPrice, value);
    }

    private static Ac cost(long at, String name, int itemId, long value)
    {
        return receipt(at, Ai.CONSUMPTION, name, itemId, -1L, (int) Math.abs(value), -Math.abs(value));
    }

    private static Ac receipt(long at, Ai type, String name, int itemId, long quantity,
        int unitPrice, long value)
    {
        return new Ac(at, null, type, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(itemId, name, quantity, unitPrice, value)),
            Bd.LIKELY, "test", null);
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

    private static boolean anyContains(java.util.List<String> labels, String needle)
    {
        for (String label : labels)
        {
            if (label.contains(needle))
            {
                return true;
            }
        }
        return false;
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                result.set(callable.call());
            }
            catch (Exception ex)
            {
                failure.set(ex);
            }
        });
        if (failure.get() != null)
        {
            throw failure.get();
        }
        return result.get();
    }
}
