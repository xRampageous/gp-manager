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

/** R4 recap / previous-Grind comparison / same-Grind PBs: derived, factual, stable-id only. */
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

        Ba facts = Ba.of(engine, session, now);
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
    public void aFirstRunWithoutARateHasNoRateBest() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        runGrind(engine, "Vorkath", now - 10_000L, now, 100_000L, 0L, 5_000_000L, 3L * 3_600_000L);
        Ba facts = Ba.of(engine, engine.getHistory().get(0), now);
        assertNull("ten seconds is not a trustworthy rate", facts.pbs.bestGpPerHour);
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

        Ba facts = Ba.of(engine, session, now);
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

        Ba facts = Ba.of(engine, engine.getHistory().get(0), now);
        assertNotNull(facts.recap.biggestGain);
        assertEquals("Dragon bones", facts.recap.biggestGain.name);
        assertNotNull(facts.recap.lz);
        assertEquals("Tanzanite fang", facts.recap.lz.name);
        assertTrue(facts.recap.lz.net < 0L);
    }

    @Test
    public void comparisonUsesTheImmediatePreviousLinkedGrindOnly() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        String grindId = engine.avg("Vorkath", null, null, false, null).getGrindId();
        String a = runLinked(engine, "Vorkath", grindId, now - 9 * 3_600_000L, now - 7 * 3_600_000L, 2_000_000L);
        // A same-named Grind from a different lineage must never participate.
        String otherGrind = engine.avg("Vorkath", null, null, false, null).getGrindId();
        runLinked(engine, "Vorkath", otherGrind, now - 6 * 3_600_000L, now - 5 * 3_600_000L, 9_000_000L);
        // An unlinked legacy row must never auto-link either.
        runUnlinked(engine, "Vorkath", now - 4 * 3_600_000L, now - 3 * 3_600_000L, 8_000_000L);
        String b = runLinked(engine, "Vorkath", grindId, now - 3 * 3_600_000L, now - 1 * 3_600_000L, 3_000_000L);

        Ad sessionB = engine.ua(b);
        Ba facts = Ba.of(engine, sessionB, now);
        assertTrue(facts.comparison.available);
        assertEquals("only the stable grindId lineage is compared (3M against 2M)", 1_000_000L,
            facts.comparison.netDelta);
        assertNotNull(facts.comparison.rateDelta);
        assertEquals(2L * 3_600_000L - 2L * 3_600_000L, facts.comparison.activeDelta);

        // A rename never breaks the comparison.
        engine.ua(b).rename("Totally renamed");
        Ba renamed = Ba.of(engine, engine.ua(b), now);
        assertEquals(1_000_000L, renamed.comparison.netDelta);
    }

    @Test
    public void comparisonSkipsExcludedRunsAndNoPreviousIsHonest() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        String grindId = engine.avg("Vorkath", null, null, false, null).getGrindId();
        String a = runLinked(engine, "Vorkath", grindId, now - 9 * 3_600_000L, now - 7 * 3_600_000L, 2_000_000L);
        String b = runLinked(engine, "Vorkath", grindId, now - 3 * 3_600_000L, now - 1 * 3_600_000L, 3_000_000L);

        engine.ua(a).setExcludedFromAverages(true);
        Ba facts = Ba.of(engine, engine.ua(b), now);
        assertFalse("an excluded previous run is skipped", facts.comparison.available);
        assertEquals(0L, facts.comparison.netDelta);

        engine.ua(b).setExcludedFromAverages(true);
        Ba excluded = Ba.of(engine, engine.ua(b), now);
        assertTrue(excluded.comparison.excluded);
        assertTrue(excluded.pbs.excluded);
        assertFalse(excluded.comparison.available);
    }

    @Test
    public void sameGrindPBsAreStrictAndTiesNeverClaimANewRecord() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        String grindId = engine.avg("Vorkath", null, null, false, null).getGrindId();
        runLinked(engine, "Vorkath", grindId, now - 13 * 3_600_000L, now - 11 * 3_600_000L, 2_000_000L);
        runLinked(engine, "Vorkath", grindId, now - 9 * 3_600_000L, now - 7 * 3_600_000L, 3_000_000L);
        String c = runLinked(engine, "Vorkath", grindId, now - 5 * 3_600_000L, now - 3 * 3_600_000L, 4_000_000L);

        Ba auu = Ba.of(engine, engine.ua(c), now);
        assertTrue(auu.pbs.newBestNet);
        assertFalse(auu.pbs.matchesBestNet);
        assertEquals(4_000_000L, (long) auu.pbs.bestNet);
        assertNotNull(auu.pbs.bestGpPerHour);

        String d = runLinked(engine, "Vorkath", grindId, now - 2 * 3_600_000L, now - 1 * 3_600_000L, 4_000_000L);
        Ba tie = Ba.of(engine, engine.ua(d), now);
        assertFalse("a tie must not falsely become a new PB", tie.pbs.newBestNet);
        assertTrue(tie.pbs.matchesBestNet);
        assertEquals(4_000_000L, (long) tie.pbs.bestNet);
    }

    @Test
    public void gpPerHourPBsRequireTheSharedFloorAndExactProjection() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        String grindId = engine.avg("Vorkath", null, null, false, null).getGrindId();
        runLinked(engine, "Vorkath", grindId, now - 3 * 3_600_000L, now - 1 * 3_600_000L, 5_000_000L);
        // A forty-second burst with a huge naive rate is not eligible for a GP/h PB.
        String shortRun = runLinked(engine, "Vorkath", grindId, now - 50_000L, now - 10_000L, 4_000_000L);

        Ba facts = Ba.of(engine, engine.ua(shortRun), now);
        assertFalse("below the shared 60s floor the rate sets no best", facts.pbs.newBestGpPerHour);
        assertNotNull("the eligible earlier run still holds the best rate", facts.pbs.bestGpPerHour);
        assertEquals(Ba.hourly(5_000_000L, 2 * 3_600_000L), (long) facts.pbs.bestGpPerHour);
        assertFalse("the burst never claims the GP/h record", facts.pbs.newBestGpPerHour);
    }

    @Test
    public void grindsDetailRendersTheRecapComparisonAndPbs() throws Exception
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
        assertTrue(anyContains(labels, "VS PREVIOUS RUN"));
        assertTrue(anyContains(labels, "PERSONAL BESTS"));
        assertTrue(anyContains(labels, "NEW PB"));
        assertTrue(anyContains(labels, "Best Net"));
        assertTrue(anyContains(labels, "Dragon bones"));
        assertTrue("the Gain category reads Gains", anyContains(labels, "GAINS"));
        assertFalse("visible Revenue is gone", labels.stream().anyMatch(label -> label.contains("Revenue")));
    }

    @Test
    public void grindsDetailWithoutAPreviousRunNeverFabricatesOne() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        String grindId = engine.avg("Vorkath", null, null, false, null).getGrindId();
        String only = runLinked(engine, "Vorkath", grindId, now - 3 * 3_600_000L, now - 1 * 3_600_000L, 2_000_000L);

        As.Detail detail = As.capture(engine, now, false, only).detail;
        assertNotNull(detail);
        assertNotNull(detail.history);
        assertFalse(detail.history.comparison.available);

        java.util.List<String> labels = onEdt(() ->
        {
            Dp panel = new Dp(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.openGrindsDetail(panel, only);
            SidebarPanelProbe.refresh(panel);
            java.util.List<String> found = new java.util.ArrayList<>();
            collectLabels(panel, found);
            return found;
        });
        assertTrue(anyContains(labels, "No previous run yet"));
        assertFalse("no new-PB badge without a prior record", anyContains(labels, "NEW PB"));
    }

    @Test
    public void activeTimeDeltaUsesNeutralColourSemantics() throws Exception
    {
        Am engine = engine();
        long now = System.currentTimeMillis();
        String grindId = engine.avg("Vorkath", null, null, false, null).getGrindId();
        runLinked(engine, "Vorkath", grindId, now - 5 * 3_600_000L, now - 4 * 3_600_000L, 2_000_000L);
        String b = runLinked(engine, "Vorkath", grindId, now - 3 * 3_600_000L, now - 1 * 3_600_000L, 3_000_000L);
        Ba facts = Ba.of(engine, engine.ua(b), now);
        assertTrue(facts.comparison.available);
        assertTrue("comparison arithmetic is unchanged", facts.comparison.activeDelta != 0L);
        assertEquals(1_000_000L, facts.comparison.netDelta);
        String timeText = GrindsPage.auh(facts.comparison.activeDelta);
        String netText = Fmt.signed(facts.comparison.netDelta);

        java.util.Map<String, java.awt.Color> colours = onEdt(() ->
        {
            Dp panel = new Dp(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.openGrindsDetail(panel, b);
            SidebarPanelProbe.refresh(panel);
            java.util.Map<String, java.awt.Color> found = new java.util.HashMap<>();
            collectLabelColours(panel, found);
            return found;
        });
        assertNotNull("the Active Time delta renders", colours.get(timeText));
        assertEquals("time differences stay neutral", Kit.Tone.PLAIN.color,
            colours.get(timeText));
        assertEquals("financial deltas keep their sign colour",
            Kit.Tone.GAIN.color, colours.get(netText));
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
