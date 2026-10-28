package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static com.gpmanager.SwingTestSupport.onEdt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Grinds: current, My Grinds, Recent, targets, pace and the no-copy workflow laws. */
public class GrindsPresentationTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void aLargeLossCannotWrapTheRequiredTargetRateToZero()
    {
        assertEquals(Long.MAX_VALUE, GrindsData.requiredAverage(Long.MAX_VALUE, Long.MIN_VALUE, 3_600_000L));
        assertEquals(0L, GrindsData.requiredAverage(100L, 101L, 3_600_000L));
        assertEquals(0L, GrindsData.requiredAverage(100L, 0L, 0L));
    }

    @Test
    public void oversizedTimeInputsAreRejectedWithoutChangingTheExistingTarget()
    {
        for (String text : new String[] {"153722867280913", "153722867280913m",
            "2562047788016h", "2500000000000h 10000000000000m", "9".repeat(400) + "h"})
        {
            assertEquals(text, GrindsData.INVALID, GrindsData.parseTime(text));
            assertEquals(text, Long.valueOf(3_600_000L), GrindsData.applyTime(text, 3_600_000L));
        }
        assertEquals(60_000L, GrindsData.parseTime("0.5"));
        assertEquals(30_000L, GrindsData.parseTime("0.5m"));
        assertEquals(1_800_000L, GrindsData.parseTime("0.5h"));
    }

    @Test
    public void friendlyTargetParsingNormalisesExactValues() throws Exception
    {
        assertEquals(5_000_000L, GrindsData.parseNet("5m"));
        assertEquals(5_000_000L, GrindsData.parseNet("5M"));
        assertEquals(5_000_000L, GrindsData.parseNet("5000k"));
        assertEquals(5_000_000L, GrindsData.parseNet("5,000,000"));
        assertEquals(250_000L, GrindsData.parseNet("250k"));
        assertTrue("blank means no target", GrindsData.parseNet("") == 0L);
        assertTrue("garbage is rejected, never treated as clear", GrindsData.parseNet("abc") == GrindsData.INVALID);
        assertTrue("zero is not a target", GrindsData.parseNet("0") == GrindsData.INVALID);

        assertEquals(90L * 60_000L, GrindsData.parseTime("90m"));
        assertEquals(3L * 3_600_000L, GrindsData.parseTime("3h"));
        assertEquals(3L * 3_600_000L + 30L * 60_000L,
            GrindsData.parseTime("3h 30m"));
        assertEquals(45L * 60_000L, GrindsData.parseTime("45"));
        assertTrue("blank means no target", GrindsData.parseTime("") == 0L);
    }

    @Test
    public void malformedNonblankInputIsRejectedAndNeverClearsAnExistingTarget() throws Exception
    {
        for (String bad : new String[] {"5x", "3hh", "potato", "--5m", "1..5m", "3h bananas"})
        {
            assertTrue("Net rejected: " + bad, GrindsData.parseNet(bad) == GrindsData.INVALID);
            assertTrue("Time rejected: " + bad, GrindsData.parseTime(bad) == GrindsData.INVALID);
            assertEquals("existing Net survives: " + bad,
                Long.valueOf(5_000_000L), GrindsData.applyNet(bad, 5_000_000L));
            assertEquals("existing time survives: " + bad,
                Long.valueOf(3L * 3_600_000L), GrindsData.applyTime(bad, 3L * 3_600_000L));
            assertFalse("a form with malformed input is not applyable",
                GrindsData.inputsValid(bad, null)
                    && GrindsData.parseNet(bad) > 0L);
        }
        assertFalse("both fields must be safe to apply", GrindsData.inputsValid("5m", "3h bananas"));
        assertTrue(GrindsData.inputsValid("5m", "3h"));
        assertTrue("deliberate blanks are applyable (explicit clear)", GrindsData.inputsValid("", ""));
    }

    @Test
    public void explicitClearStillWorksAndFinancialTruthIsUntouched() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000, false);
        engine.setTargets(5_000_000L, 3L * 3_600_000L, now + 2_000L);
        long net = engine.metrics(now + 2_000L).net;

        // An invalid edit keeps both existing targets untouched.
        Long keptNet = GrindsData.applyNet("3h bananas", 5_000_000L);
        Long keptTime = GrindsData.applyTime("potato", 3L * 3_600_000L);
        engine.setTargets(keptNet, keptTime, now + 3_000L);
        assertEquals(Long.valueOf(5_000_000L), engine.session().targetGp());
        assertEquals(Long.valueOf(3L * 3_600_000L), engine.session().timeTargetMillis());

        // Deliberate blanks clear, with no financial mutation.
        engine.setTargets(GrindsData.applyNet("", 5_000_000L),
            GrindsData.applyTime("", 3L * 3_600_000L), now + 4_000L);
        assertNull(engine.session().targetGp());
        assertNull(engine.session().timeTargetMillis());
        assertEquals(net, engine.metrics(now + 4_000L).net);
    }

    @Test
    public void paceDerivesAllThreeModesAndNeverFabricatesPrecision() throws Exception
    {
        // A: Net target only → time remaining.
        GrindsData.Pace a = GrindsData.pace(5_000_000L, null, 2_840_000L, true, 2_030_000L, 3_600_000L);
        assertTrue(a.available);
        assertTrue("~1h 4m remaining", a.line.contains("remaining"));
        // B: Active Time target only → the countdown (owner 1.5.2: no projected Net).
        GrindsData.Pace b = GrindsData.pace(null, 10L * 3_600_000L, 2_840_000L, true, 2_030_000L,
            1L * 3_600_000L);
        assertTrue(b.available);
        assertEquals("9h 0m left", b.line);
        // C: both → the countdown + required average.
        GrindsData.Pace c = GrindsData.pace(5_000_000L, 3L * 3_600_000L, 2_840_000L, true, 2_030_000L,
            1L * 3_600_000L);
        assertTrue(c.available);
        assertNotNull(c.second);
        assertTrue(c.second.contains("Required average"));
        // Unavailable evidence is honest.
        GrindsData.Pace pending = GrindsData.pace(5_000_000L, null, 100L, false, 0L, 10_000L);
        assertFalse(pending.available);
        assertEquals("Calculating\u2026", pending.line);
        GrindsData.Pace none = GrindsData.pace(null, null, 100L, true, 2_000L, 10_000L);
        assertFalse("no targets means no Pace question at all", none.present);
        assertEquals("no Pace line renders", "", none.line);
    }

    /** Owner 2026-10-01 (F10): a combined target's required-pace line reaches the detail. */
    @Test
    public void combinedTargetPaceShowsTheRequiredAverageInDetail() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 20L * 60_000L);
        engine.session().addTransaction(booked(now - 15L * 60_000L, "Dragon bones", 1, 2,
            100_000, 200_000L), 2_000, false);
        engine.session().setNetTarget(5_000_000L, 0, 0);
        engine.session().setTimeTarget(3L * 3_600_000L, now);
        String id = engine.session().id();
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() ->
        {
            panel.shell.show(Shell.GRINDS);
            new GrindsController(panel).openDetail(id);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        List<String> texts = labels(panel);
        assertTrue("the Time row counts down: " + texts, anyContains(texts, "of Active Time remaining"));
        assertFalse("with no projection: " + texts, anyContains(texts, "projected"));
        assertTrue("the required average is used", anyContains(texts, "Required average"));
    }

    /** Owner 2026-10-01 (F12): a completed detail names its whole-run rate basis. */
    @Test
    public void closedDetailNamesItsWholeRunRateBasis() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 20L * 60_000L);
        engine.session().addTransaction(booked(now - 15L * 60_000L, "Dragon bones", 1, 2,
            100_000, 200_000L), 2_000, false);
        String id = engine.session().id();
        engine.finishCustom(now);
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() ->
        {
            panel.shell.show(Shell.GRINDS);
            new GrindsController(panel).openDetail(id);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        List<String> tips = rateTooltips(panel);
        assertTrue("the closed detail names the whole-run basis: " + tips,
            tips.contains("GP/h over active time"));
    }

    private static List<String> rateTooltips(Component root)
    {
        List<String> out = new ArrayList<>();
        collectRateTooltips(root, out);
        return out;
    }

    private static void collectRateTooltips(Component component, List<String> out)
    {
        if (component instanceof JLabel)
        {
            String text = ((JLabel) component).getText();
            if (text != null && text.endsWith("/h"))
            {
                out.add(String.valueOf(((JLabel) component).getToolTipText()));
            }
        }
        if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                collectRateTooltips(child, out);
            }
        }
    }

    @Test
    public void myGrindsOrderFavouritesThenRecentThenRemaining() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        SavedState.SavedGrind alpha = engine.saveGrind("Alpha", 1_000_000L, null, false);
        SavedState.SavedGrind beta = engine.saveGrind("Beta", null, null, false);
        SavedState.SavedGrind gamma = engine.saveGrind("Gamma", null, null, true);
        assertNotNull(alpha);
        assertNotNull(beta);
        assertNotNull(gamma);

        GrindsData data = GrindsData.capture(engine, now, false, null);
        assertEquals("Gamma", data.plans.get(0).definition.name());
        assertEquals(3, data.plans.size());
    }

    @Test
    public void renameKeepsStableLineage() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Zulrah", now);
        String sessionId = engine.session().id();
        SavedState.SavedGrind saved = engine.saveGrind("Zulrah", null, null, false);
        engine.session().setGrindId(saved.id());
        assertNotNull(saved);
        engine.updateSavedGrind(saved.id(), "Zulrah Bowfa", null, null, false);
        assertEquals("the id never changes with the name", saved.id(),
            engine.plan(saved.id()).id());
        assertEquals(1, engine.linkedSessions(saved.id()).size());
    }

    @Test
    public void updateSavedGrindNeverRewritesHistoricalSnapshots() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().setNetTarget(5_000_000L, 0, 0);
        String sessionId = engine.session().id();
        SavedState.SavedGrind saved = engine.saveGrind("Vorkath", 5_000_000L, null, false);
        engine.session().setGrindId(saved.id());
        engine.finishCustom(now + 1_000L);

        engine.updateSavedGrind(saved.id(), "Vorkath", 10_000_000L, null, false);
        assertEquals(Long.valueOf(10_000_000L), engine.plan(saved.id()).targetGp());
        assertEquals("the finished instance keeps the target it ran with", Long.valueOf(5_000_000L),
            engine.historySession(sessionId).targetGp());
    }

    /** Owner 1.5.0: one Grind, one setup; the targets a run starts with become the Grind's. */
    @Test
    public void startingWithTargetsSetsTheGrindsTargets() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        SavedState.SavedGrind saved = engine.saveGrind("Vorkath", 5_000_000L, null, false);
        long now = System.currentTimeMillis();
        assertTrue(engine.startGrind("Vorkath", saved.id(), 9_000_000L, 2L * 3_600_000L, now));
        assertEquals("the run gets the aim", Long.valueOf(9_000_000L),
            engine.session().targetGp());
        assertEquals("and so does its Grind", Long.valueOf(9_000_000L),
            engine.plan(saved.id()).targetGp());
        assertEquals(Long.valueOf(2L * 3_600_000L),
            engine.plan(saved.id()).timeTargetMillis());
    }

    /** Owner 1.5.0: every Grind is remembered; a name already known is reused, not copied. */
    @Test
    public void everyStartedGrindIsRemembered() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        assertTrue(engine.startGrind("Muspah", null, null, null, now));
        assertEquals(1, engine.plans(true).size());
        SavedState.SavedGrind made = engine.plans(true).get(0);
        assertEquals("the run links to it", made.id(), engine.session().grindId());
        engine.finishCustom(now + 1_000L);

        engine.archiveGrind(made.id(), true);
        assertTrue(engine.startGrind("muspah", null, 1_000_000L, null, now + 2_000L));
        assertEquals("the same name, any case, reuses it", 1, engine.plans(true).size());
        assertEquals(made.id(), engine.session().grindId());
        assertEquals("the run keeps the Grind's own name", "Muspah", engine.session().name());
        assertFalse("starting it brings it back from the archive", made.archived);
        engine.finishCustom(now + 3_000L);

        engine.deleteSavedGrind(made.id());
        assertTrue("a run whose Grind was deleted starts a new one",
            engine.startGrind("Muspah", made.id(), null, null, now + 4_000L));
        assertEquals(1, engine.plans(true).size());
        assertNotEquals(made.id(), engine.session().grindId());
    }

    /** Owner 1.5.0: the Targets sheet changes the Grind too; Done clears the run's aim only. */
    @Test
    public void targetsFollowTheGrindButDoneStaysWithTheRun() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startGrind("Zulrah", null, null, null, now);
        String grindId = engine.session().grindId();
        assertTrue(engine.saveTargets(2_000_000L, 3_600_000L, now + 1_000L));
        assertEquals(Long.valueOf(2_000_000L), engine.plan(grindId).targetGp());
        assertEquals(Long.valueOf(3_600_000L), engine.plan(grindId).timeTargetMillis());
        engine.setTargets(null, null, now + 2_000L);
        assertNull("Done clears the run", engine.session().targetGp());
        assertEquals("but the next run starts with the target", Long.valueOf(2_000_000L),
            engine.plan(grindId).targetGp());

        engine.updateSavedGrind(grindId, "Zulrah", 2_000_000L, 3_600_000L, true);
        assertNull("a favourite or an unchanged target never re-arms the run",
            engine.session().targetGp());
        engine.updateSavedGrind(grindId, "Zulrah bowfa", 4_000_000L, 3_600_000L, true);
        assertEquals("an edited target reaches the running run", Long.valueOf(4_000_000L),
            engine.session().targetGp());
        assertEquals("and so does the name", "Zulrah bowfa", engine.session().name());

        engine.saveTargets(5_000_000L, null, now + 3_000L);
        engine.finishCustom(now + 4_000L);
        assertEquals("Free play targets never touch a Grind", Long.valueOf(5_000_000L),
            engine.plan(grindId).targetGp());
        engine.saveTargets(9_000_000L, null, now + 5_000L);
        assertEquals(Long.valueOf(5_000_000L), engine.plan(grindId).targetGp());
    }

    /** Owner 1.5.0: a Grind's row compares by its average GP/h over finished runs. */
    @Test
    public void myGrindsUseTheCanonicalRateWithoutOverflowingLargeSignedTotals()
    {
        for (long value : new long[] {3_000_000_000_000L, -3_000_000_000_000L})
        {
            Engine engine = PresentationLifecycleTest.engine();
            long now = 1_800_000_000_000L;
            engine.startGrind("Long history", null, null, null, now);
            engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 536,
                value > 0L ? 1500 : -1500, 2_000_000_000, value), 2_000, false);
            engine.finishCustom(now + 3_600_000L);
            GrindsData.MyGrind row = GrindsData.capture(engine, now + 3_600_000L, false, null).plans.get(0);
            assertEquals(value, row.rate);
            assertEquals(Session.hourly(value, 3_600_000L), row.rate);
        }
    }

    @Test
    public void myGrindsShowTheAverageRateOfFinishedRuns() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis() - 10L * 3_600_000L;
        engine.startGrind("Vorkath", null, null, null, now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 536, 1, 1_000_000, 1_000_000L), 2_000, false);
        engine.finishCustom(now + 3_600_000L);
        engine.startGrind("Vorkath", null, null, null, now + 4_000_000L);
        engine.session().addTransaction(booked(now + 4_001_000L, "Dragon bones", 536, 1, 2_000_000, 2_000_000L), 2_000, false);
        engine.finishCustom(now + 4_000_000L + 3_600_000L);

        GrindsData.MyGrind row = GrindsData.capture(engine, System.currentTimeMillis(), false, null).plans.get(0);
        assertTrue(row.hasRate);
        assertEquals("3m over two hours", 1_500_000L, row.rate);
        assertEquals(2, row.linkedCount);
    }

    @Test
    public void startAgainStartsFreshWithNoCopiedFinancialState() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 5, 3_200, 16_000), 2_000, false);
        engine.session().setNetTarget(5_000_000L, 0, 0);
        SavedState.SavedGrind saved = engine.saveGrind("Vorkath", 5_000_000L, null, false);
        engine.session().setGrindId(saved.id());
        engine.finishCustom(now + 2_000L);

        assertTrue(engine.startGrind("Vorkath", saved.id(), 5_000_000L, null, now + 3_000L));
        assertEquals("fresh financial instance", 0L, engine.metrics(now + 3_000L).net);
        assertEquals(0, engine.session().transactions().size());
        assertEquals("fresh Active Time", 0L, engine.metrics(now + 3_000L).elapsed);
        assertEquals("setup is repeated", saved.id(), engine.session().grindId());
        assertTrue("history is never cleared", engine.history().size() >= 1);
    }

    @Test
    public void archiveAndDeletePreserveCanonicalHistory() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        String sessionId = engine.session().id();
        SavedState.SavedGrind saved = engine.saveGrind("Vorkath", null, null, false);
        engine.session().setGrindId(saved.id());
        engine.finishCustom(now + 1_000L);

        assertTrue(engine.archiveGrind(saved.id(), true));
        assertTrue("archived definitions hide from the normal list",
            engine.plans(false).isEmpty());
        assertTrue(engine.deleteSavedGrind(saved.id()));
        assertEquals("the historical instance survives the template delete",
            sessionId, engine.history().get(0).id());
        assertEquals("its stable lineage link survives too",
            saved.id(), engine.history().get(0).grindId());
    }

    @Test
    public void reachingATargetNeverAutoEndsTheGrind() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000, false);
        engine.setTargets(1_000L, null, now + 2_000L);
        assertTrue(engine.metrics(now + 2_000L).net > 1_000L);
        assertFalse("the Grind stays open after the target", engine.session().isClosed());
    }

    @Test
    public void resetKeepsReusableSetupButClearsFinancialData() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000, false);
        engine.session().setGrindId(engine.saveGrind("Vorkath", 5_000_000L, null, false).id());
        engine.resetData(now + 2_000L);
        assertEquals("accounting reset clears history", 0, engine.history().size());
        assertEquals("reusable setup metadata survives a data reset", 1, engine.plans(false).size());
    }

    @Test
    public void grindsPageRendersCurrentMyAndRecentSections() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000, false);
        engine.session().setNetTarget(5_000_000L, 0, 0);
        engine.saveGrind("Zulrah", null, null, true);

        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() ->
        {
            panel.shell.show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        List<String> labels = labels(panel);
        assertTrue("the Running line shows the Grind", anyContains(labels, "Vorkath"));
        assertTrue(anyContains(labels, "MY GRINDS"));
        assertTrue(anyContains(labels, "RECENT GRINDS"));
        assertTrue("saved Grinds render", anyContains(labels, "Zulrah"));
        // Owner 1.5.0: one Start Grind (no Add Grind); a saved Grind starts from its row.
        List<String> buttons = onEdt(() -> buttonTexts(panel));
        assertTrue(buttons.toString(), buttons.contains("Start Grind") && !buttons.contains("+ Add Grind"));
        assertFalse("no per-Grind Start button", buttons.stream().anyMatch(text -> text.startsWith("Start Zulrah")));
        GrindsData.MyGrind zulrah = panel.plans.data.plans.stream()
            .filter(grind -> "Zulrah".equals(grind.definition.name())).findFirst().orElseThrow();
        assertEquals("Start", panel.plans.grindMenu(zulrah).get(0).label);
                assertTrue("user-facing copy avoids the backend noun",
            labels.stream().noneMatch(label -> label.contains("Session")));
    }

    /** Owner 2026-10-01 (F14): the selected saved Grind starts in place, beside its menu. */
    @Test
    public void theSelectedSavedGrindOffersStartBesideItsMenu() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        SavedState.SavedGrind saved = engine.saveGrind("Zulrah", null, null, true);
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() ->
        {
            panel.shell.show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            panel.plans.select(saved.id());
            return null;
        });
        List<String> buttons = onEdt(() -> buttonTexts(panel));
        assertTrue("the selected panel offers Start: " + buttons, buttons.contains("Start"));

        assertTrue("the visible Start button starts the saved Grind",
            onEdt(() -> clickText(panel, "Start")));
        assertEquals("the run carries the saved lineage",
            saved.id(), engine.session().grindId());
    }

    /** Owner 1.5.0: Favourite is one menu pick; Edit keeps it as it was. */
    @Test
    public void favouriteTogglesFromTheMenuAndSortsFirst() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        SavedState.SavedGrind plain = engine.saveGrind("Zulrah", null, null, false);
        assertNotNull(engine.saveGrind("Vorkath", null, null, false));
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        GrindsController controller = new GrindsController(panel);

        onEdt(() ->
        {
            controller.favoriteSavedGrind(plain.id(), true);
            return null;
        });
        assertTrue(engine.plan(plain.id()).favorite);
        assertEquals("a favorite sorts first", plain.id(),
            GrindsData.capture(engine, System.currentTimeMillis(), false, null)
                .plans.get(0).definition.id());

        onEdt(() ->
        {
            controller.editSavedGrind(plain.id());
            ShellProbe.typeSheet(panel.shell, "Zulrah bowfa");
            ShellProbe.pressSheet(panel.shell, "Save");
            return null;
        });
        assertEquals("Zulrah bowfa", engine.plan(plain.id()).name());
        assertTrue("Edit keeps the favourite", engine.plan(plain.id()).favorite);
    }

    /** Owner 2026-10-01 (F22): the Start sheet pager appears only when rows exceed five. */
    @Test
    public void startSheetPagerAppearsOnlyAboveFiveRows() throws Exception
    {
        Engine empty = PresentationLifecycleTest.engine();
        SidebarPanel emptyPanel = onEdt(() -> new SidebarPanel(empty, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() ->
        {
            new GrindsController(emptyPanel).newGrind();
            List<String> texts = ShellProbe.sheetButtonTexts(emptyPanel.shell);
            assertFalse("nothing to page",
                texts.contains("\u2039") || texts.contains("\u203a"));
            assertTrue("the name field and the primary action stay visible",
                texts.contains("Start") && texts.contains("Cancel"));
            return null;
        });

        Engine single = PresentationLifecycleTest.engine();
        assertNotNull(single.saveGrind("Only", null, null, false));
        SidebarPanel singlePanel = onEdt(() -> new SidebarPanel(single, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() ->
        {
            new GrindsController(singlePanel).newGrind();
            List<String> texts = ShellProbe.sheetButtonTexts(singlePanel.shell);
            assertTrue(texts.toString(), texts.contains("Only"));
            assertFalse("one row needs no pager",
                texts.contains("\u2039") || texts.contains("\u203a"));
            return null;
        });
    }

    /** Owner 2026-10-01 (F22): six saved Grinds show five rows; paging reaches the sixth. */
    @Test
    public void startSheetPagesEverySavedGrind() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        for (int i = 1; i <= 6; i++)
        {
            assertNotNull(engine.saveGrind("Plan " + i, null, null, false));
        }
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.newGrind();
            List<String> texts = ShellProbe.sheetButtonTexts(panel.shell);
            assertTrue(texts.toString(), texts.contains("Plan 1") && texts.contains("Plan 5"));
            assertFalse("the sixth row waits on the next page", texts.contains("Plan 6"));
            assertEquals("five rows on one page", 5,
                texts.stream().filter(text -> text.startsWith("Plan ")).count());
            assertTrue("the window is named", anyLabelContains(panel, "1\u20135 of 6"));
            assertTrue("paging reaches the sixth",
                ShellProbe.pressSheetText(panel.shell, "\u203a"));
            texts = ShellProbe.sheetButtonTexts(panel.shell);
            assertTrue(texts.toString(), texts.contains("Plan 6"));
            assertFalse(texts.contains("Plan 1"));
            assertTrue(anyLabelContains(panel, "6\u20136 of 6"));
            return null;
        });
    }

    /** Owner 2026-10-01 (F22): a hundred saved Grinds stay reachable through the pager. */
    @Test
    public void startSheetStaysBoundedWithAHundredGrinds() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        for (int i = 1; i <= 100; i++)
        {
            assertNotNull(engine.saveGrind("Plan " + i, null, null, false));
        }
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.newGrind();
            List<String> texts = ShellProbe.sheetButtonTexts(panel.shell);
            assertEquals("the sheet never grows past five rows", 5,
                texts.stream().filter(text -> text.startsWith("Plan ")).count());
            assertFalse("later pages are not rendered", texts.contains("Plan 100"));
            for (int i = 0; i < 19; i++)
            {
                assertTrue("paging forward", ShellProbe.pressSheetText(panel.shell, "\u203a"));
            }
            texts = ShellProbe.sheetButtonTexts(panel.shell);
            assertTrue(texts.toString(), texts.contains("Plan 100"));
            assertEquals(5, texts.stream().filter(text -> text.startsWith("Plan ")).count());
            assertTrue(anyLabelContains(panel, "96\u2013100 of 100"));
            assertFalse("the last page disables next",
                ShellProbe.pressSheetText(panel.shell, "\u203a"));
            assertTrue(ShellProbe.pressSheetText(panel.shell, "\u2039"));
            assertTrue(anyLabelContains(panel, "91\u201395 of 100"));
            return null;
        });
    }


    /** Owner 2026-10-01 (F14): ending offers a recap route to the ended run. */
    @Test
    public void endingOffersAViewRecapRouteToTheEndedRun() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        String id = engine.session().id();
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.endGrind();
            return null;
        });
        assertTrue("the run closed into history",
            engine.historySession(id) != null && engine.historySession(id).isClosed());
        assertTrue("the notice names the ended run: " + ShellProbe.noticeText(panel.shell),
            ShellProbe.noticeText(panel.shell).startsWith("Vorkath ended"));

        onEdt(() ->
        {
            for (java.awt.Component component : panel.shell.noticeAction.getComponents())
            {
                if (component instanceof javax.swing.JButton)
                {
                    ((javax.swing.JButton) component).doClick();
                }
            }
            return null;
        });
        assertEquals("the recap opens the ended run", id, panel.selection);
        assertEquals(Shell.GRINDS, panel.shell.current);
    }

    /** Returns the fabricated backups the test chooses; the real service is never involved. */
    private static final class FakeBackups extends Persistence
    {
        private final List<Filepath> files;

        FakeBackups(List<Filepath> files)
        {
            super(null, null, null, null, null);
            this.files = files;
        }

        @Override
        List<Filepath> backups()
        {
            return files;
        }

        boolean adzCalled;

        @Override
        int pruneBackups(int keep)
        {
            adzCalled = true;
            return Math.max(0, files.size() - keep);
        }
    }

    /** One reset-path fake: records the calls and refuses the reset when asked. */
    private static final class FakeReset extends Persistence
    {
        final java.util.List<String> calls = new ArrayList<>();
        boolean refuse;

        FakeReset()
        {
            super(null, null, null, null, null);
        }

        @Override
        Persistence.ResetOutcome factoryReset(long now, boolean backup)
        {
            calls.add("reset");
            return new Persistence.ResetOutcome(!refuse, refuse ? "disk full" : "");
        }
    }

    /** Reset writes its own safety copy, so the sheet offers one Reset and no Back up first. */
    @Test
    public void factoryResetOffersOneResetAndReportsARefusal() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        FakeReset fake = new FakeReset();
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null,
            null, fake));
        GrindsController controller = new GrindsController(panel);

        onEdt(() ->
        {
            controller.factoryReset();
            List<String> buttons = ShellProbe.sheetButtonTexts(panel.shell);
            assertTrue("both deletes are offered: " + buttons, buttons.contains("Back up & delete")
                && buttons.contains("Delete without backup"));
            fake.refuse = true;
            ShellProbe.pressSheet(panel.shell, "Back up & delete");
            return null;
        });
        PersistenceProbe.drain(fake, 2);
        assertEquals(java.util.Arrays.asList("reset"), fake.calls);
        assertTrue("a refused reset says why: " + ShellProbe.noticeText(panel.shell),
            ShellProbe.noticeText(panel.shell).contains("disk full"));
    }


    /** Owner 2026-10-01: Ledger and Grinds share one toolbar band, attached to the tab strip. */
    @Test
    public void ledgerAndGrindsShareTheSameAttachedToolbarBand() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() ->
        {
            panel.shell.show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertTrue("the Ledger header keeps the shared band shape",
            onEdt(() -> bandShape(panel.ledger.pageBar())));
        assertTrue("the Grinds header uses the same band shape",
            onEdt(() -> bandShape(panel.plans.pageBar())));
        List<String> headerButtons = onEdt(() -> buttonTexts(panel.plans.pageBar()));
        assertTrue("the Grinds header carries the controls: " + headerButtons,
            headerButtons.contains("Start Grind") && !headerButtons.contains("+ Add Grind")
                && headerButtons.contains("Archived"));
        assertFalse("the controls left the scrolling body",
            onEdt(() -> buttonTexts(panel.plans.body())).contains("Start Grind"));
        javax.swing.border.Border border = onEdt(() -> panel.shell.bar.getBorder());
        assertEquals("the toolbar attaches to the tab strip",
            0, border.getBorderInsets(onEdt(() -> panel.shell.bar)).top);
    }

    /** Controls left, actions right: the one page-toolbar band both headers use. */
    private static boolean bandShape(Component pageBar)
    {
        if (!(pageBar instanceof Container) || !(((Container) pageBar).getLayout() instanceof java.awt.BorderLayout)) return false;
        java.awt.BorderLayout layout = (java.awt.BorderLayout) ((Container) pageBar).getLayout();
        Component left = layout.getLayoutComponent(java.awt.BorderLayout.CENTER);
        Component right = layout.getLayoutComponent(java.awt.BorderLayout.EAST);
        return left != null && right instanceof Container && ((Container) right).getComponentCount() >= 1;
    }

    private static boolean clickText(java.awt.Component root, String text)
    {
        if (root instanceof javax.swing.JButton
            && text.equals(((javax.swing.JButton) root).getText()))
        {
            ((javax.swing.JButton) root).doClick();
            return true;
        }
        if (root instanceof java.awt.Container)
        {
            for (java.awt.Component child : ((java.awt.Container) root).getComponents())
            {
                if (clickText(child, text))
                {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void grindsPageKeepsR1CopyAndOffersTheDataMenuWithoutTools() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() ->
        {
            panel.shell.show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertTrue("the gear beside the tabs holds GP Manager actions",
            anyAccessibleContains(panel, "GP Manager actions"));
        java.util.List<String> gear = new java.util.ArrayList<>();
        for (java.awt.Component item : onEdt(() -> SidebarPanelProbe.dataMenu(panel)).getComponents())
        {
            if (item instanceof javax.swing.JMenuItem)
            {
                gear.add(((javax.swing.JMenuItem) item).getText());
            }
        }
        assertEquals(java.util.Arrays.asList("View recap", "Copy Live summary", "Getting started", "Back up profile", "Copy data folder path", "Report a bug",
            "Changelog", "Reset free-play…", "Delete Profile…"), gear);
        assertTrue("no standalone Search page", java.util.Arrays.stream(ShellProbe.tabLabels(panel.shell))
            .noneMatch(label -> label.equalsIgnoreCase("Search") || label.equalsIgnoreCase("Tools")));
        assertFalse("no open-folder affordance is described",
            anyLabelContains(panel, "Open export folder"));
    }

    @Test
    public void recentGrindsRowsKeepNameAndSignedValueReadable() throws Exception
    {
        long now = System.currentTimeMillis();
        long startOfToday = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        // Midnight-safe day boundaries: never assume a fixed hour offset is still "today".
        long todayTimestamp = Math.max(now - 37 * 60_000L, startOfToday + 60_000L);
        GrindsData.Recent today = new GrindsData.Recent("Vorkath", "Vorkath", todayTimestamp,
            2_840_000L, 37 * 60_000L, false);
        String context = GrindsPage.contextLine(today, now, java.time.ZoneId.systemDefault());
        assertTrue("day label leads the context: " + context, context.startsWith("Today"));
        assertTrue("duration is present: " + context, context.contains("37m"));
        assertFalse("no internal ids leak", context.contains("Vorkath"));

        assertEquals("understandable zero", "0 GP", GrindsPage.valueText(0L));
        assertEquals("+2.84M", GrindsPage.valueText(2_840_000L));
        assertEquals("\u2212412k", GrindsPage.valueText(-412_000L));

        GrindsData.Recent yesterday = new GrindsData.Recent("Zulrah", "Zulrah",
            startOfToday - 3_600_000L, -412_000L, 96 * 60_000L, false);
        assertTrue("yesterday reads naturally: " + GrindsPage.contextLine(yesterday, now, java.time.ZoneId.systemDefault()),
            GrindsPage.contextLine(yesterday, now, java.time.ZoneId.systemDefault()).contains("Yesterday"));
    }

    @Test
    public void recentGrindsSectionUsesTheKitTable() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now - 3_600_000L);
        engine.session().addTransaction(booked(now - 3_500_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000, false);
        engine.finishCustom(now - 3_000_000L);
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() ->
        {
            panel.shell.show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertTrue("history rows use the kit table",
            anyInstance(panel, Table.class));
        assertTrue("the finished Grind is listed", anyContains(labels(panel), "Vorkath"));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static List<String> buttonTexts(Component root)
    {
        var out = new java.util.ArrayList<String>();
        if (root instanceof javax.swing.AbstractButton && ((javax.swing.AbstractButton) root).getText() != null)
        {
            out.add(((javax.swing.AbstractButton) root).getText());
        }
        if (root instanceof java.awt.Container)
        {
            for (Component child : ((java.awt.Container) root).getComponents())
            {
                out.addAll(buttonTexts(child));
            }
        }
        return out;
    }

    private static boolean anyInstance(Component root, Class<?> type)
    {
        if (type.isInstance(root))
        {
            return true;
        }
        if (root instanceof Container)
        {
            for (Component child : ((Container) root).getComponents())
            {
                if (anyInstance(child, type))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> labels(Component root)
    {
        List<String> out = new ArrayList<>();
        collectLabels(root, out);
        return out;
    }

    private static void collectLabels(Component component, List<String> out)
    {
        if (component instanceof JLabel)
        {
            String text = ((JLabel) component).getText();
            if (text != null && !text.isEmpty())
            {
                out.add(text);
            }
        }
        if (component.getAccessibleContext() != null)
        {
            String name = component.getAccessibleContext().getAccessibleName();
            if (name != null && !name.isEmpty())
            {
                out.add(name);
            }
        }
        if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                collectLabels(child, out);
            }
        }
    }

    private static boolean anyContains(List<String> labels, String needle)
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

    private static boolean anyLabelContains(Component root, String needle)
    {
        if (root instanceof JLabel)
        {
            String text = ((JLabel) root).getText();
            if (text != null && text.contains(needle))
            {
                return true;
            }
        }
        if (root instanceof Container)
        {
            for (Component child : ((Container) root).getComponents())
            {
                if (anyLabelContains(child, needle))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyAccessibleContains(Component root, String needle)
    {
        if (root.getAccessibleContext() != null)
        {
            String name = root.getAccessibleContext().getAccessibleName();
            String description = root.getAccessibleContext().getAccessibleDescription();
            if ((name != null && name.contains(needle)) || (description != null && description.contains(needle)))
            {
                return true;
            }
        }
        if (root instanceof Container)
        {
            for (Component child : ((Container) root).getComponents())
            {
                if (anyAccessibleContains(child, needle))
                {
                    return true;
                }
            }
        }
        return false;
    }


    private static Transaction booked(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Transaction(at, TransactionType.GAIN, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            Confidence.LIKELY, "Test sample.", null);
    }
}
