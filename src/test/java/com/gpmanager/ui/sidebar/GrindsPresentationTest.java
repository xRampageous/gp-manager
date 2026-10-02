package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Grinds: current, My Grinds, Recent, targets, pace and the no-copy workflow laws. */
public class GrindsPresentationTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void friendlyTargetParsingNormalisesExactValues() throws Exception
    {
        assertEquals(Long.valueOf(5_000_000L), As.acv("5m").value);
        assertEquals(Long.valueOf(5_000_000L), As.acv("5M").value);
        assertEquals(Long.valueOf(5_000_000L), As.acv("5000k").value);
        assertEquals(Long.valueOf(5_000_000L), As.acv("5,000,000").value);
        assertEquals(Long.valueOf(250_000L), As.acv("250k").value);
        assertTrue("blank means no target", As.acv("").empty());
        assertTrue("garbage is rejected, never treated as clear", As.acv("abc").invalid());
        assertTrue("zero is not a target", As.acv("0").invalid());

        assertEquals(Long.valueOf(90L * 60_000L), As.ade("90m").value);
        assertEquals(Long.valueOf(3L * 3_600_000L), As.ade("3h").value);
        assertEquals(Long.valueOf(3L * 3_600_000L + 30L * 60_000L),
            As.ade("3h 30m").value);
        assertEquals(Long.valueOf(45L * 60_000L), As.ade("45").value);
        assertTrue("blank means no target", As.ade("").empty());
    }

    @Test
    public void malformedNonblankInputIsRejectedAndNeverClearsAnExistingTarget() throws Exception
    {
        for (String bad : new String[] {"5x", "3hh", "potato", "--5m", "1..5m", "3h bananas"})
        {
            assertTrue("Net rejected: " + bad, As.acv(bad).invalid());
            assertTrue("Time rejected: " + bad, As.ade(bad).invalid());
            assertEquals("existing Net survives: " + bad,
                Long.valueOf(5_000_000L), As.ks(bad, 5_000_000L));
            assertEquals("existing time survives: " + bad,
                Long.valueOf(3L * 3_600_000L), As.lb(bad, 3L * 3_600_000L));
            assertFalse("a form with malformed input is not applyable",
                As.vv(bad, null)
                    && As.acv(bad).parse == As.Dr.VALID);
        }
        assertFalse("both fields must be safe to apply", As.vv("5m", "3h bananas"));
        assertTrue(As.vv("5m", "3h"));
        assertTrue("deliberate blanks are applyable (explicit clear)", As.vv("", ""));
    }

    @Test
    public void explicitClearStillWorksAndFinancialTruthIsUntouched() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.ahq(5_000_000L, 3L * 3_600_000L, now + 2_000L);
        long net = engine.getMetrics(now + 2_000L).net;

        // An invalid edit keeps both existing targets untouched.
        Long keptNet = As.ks("3h bananas", 5_000_000L);
        Long keptTime = As.lb("potato", 3L * 3_600_000L);
        engine.ahq(keptNet, keptTime, now + 3_000L);
        assertEquals(Long.valueOf(5_000_000L), engine.getActiveSession().getProfitTargetGp());
        assertEquals(Long.valueOf(3L * 3_600_000L), engine.getActiveSession().getActiveTimeTargetMillis());

        // Deliberate blanks clear, with no financial mutation.
        engine.ahq(As.ks("", 5_000_000L),
            As.lb("", 3L * 3_600_000L), now + 4_000L);
        assertNull(engine.getActiveSession().getProfitTargetGp());
        assertNull(engine.getActiveSession().getActiveTimeTargetMillis());
        assertEquals(net, engine.getMetrics(now + 4_000L).net);
    }

    @Test
    public void paceDerivesAllThreeModesAndNeverFabricatesPrecision() throws Exception
    {
        // A: Net target only → time remaining.
        As.Pace a = As.pace(5_000_000L, null, 2_840_000L, true, 2_030_000L, 3_600_000L);
        assertTrue(a.available);
        assertTrue("~1h 4m remaining", a.line.contains("remaining"));
        // B: Active Time target only → projected Net.
        As.Pace b = As.pace(null, 10L * 3_600_000L, 2_840_000L, true, 2_030_000L,
            1L * 3_600_000L);
        assertTrue(b.available);
        assertTrue("projected estimate wording", b.line.contains("projected Net"));
        // C: both → projection + required average.
        As.Pace c = As.pace(5_000_000L, 3L * 3_600_000L, 2_840_000L, true, 2_030_000L,
            1L * 3_600_000L);
        assertTrue(c.available);
        assertNotNull(c.second);
        assertTrue(c.second.contains("Required average"));
        // Unavailable evidence is honest.
        As.Pace pending = As.pace(5_000_000L, null, 100L, false, 0L, 10_000L);
        assertFalse(pending.available);
        assertEquals("Calculating\u2026", pending.line);
        As.Pace none = As.pace(null, null, 100L, true, 2_000L, 10_000L);
        assertFalse("no targets means no Pace question at all", none.present);
        assertEquals("no Pace line renders", "", none.line);
    }

    /** Owner 2026-10-01 (F10): a combined target's required-pace line reaches the detail. */
    @Test
    public void combinedTargetPaceShowsTheRequiredAverageInDetail() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now - 20L * 60_000L);
        engine.getActiveSession().kf(booked(now - 15L * 60_000L, "Dragon bones", 1, 2,
            100_000, 200_000L), 2_000);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        engine.getActiveSession().setActiveTimeTargetMillis(3L * 3_600_000L);
        String id = engine.getActiveSession().getId();
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            panel.shell().show(Shell.GRINDS);
            new GrindsController(panel).openDetail(id);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        List<String> texts = labels(panel);
        assertTrue("the projected line renders: " + texts, anyContains(texts, "projected"));
        assertTrue("the required average is used", anyContains(texts, "Required average"));
    }

    /** Owner 2026-10-01 (F12): a completed detail names its whole-run rate basis. */
    @Test
    public void closedDetailNamesItsWholeRunRateBasis() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now - 20L * 60_000L);
        engine.getActiveSession().kf(booked(now - 15L * 60_000L, "Dragon bones", 1, 2,
            100_000, 200_000L), 2_000);
        String id = engine.getActiveSession().getId();
        engine.sx(now);
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            panel.shell().show(Shell.GRINDS);
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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        SavedState.Ap alpha = engine.avg("Alpha", 1_000_000L, null, false, null);
        SavedState.Ap beta = engine.avg("Beta", null, null, false, null);
        SavedState.Ap gamma = engine.avg("Gamma", null, null, true, null);
        assertNotNull(alpha);
        assertNotNull(beta);
        assertNotNull(gamma);

        As data = As.capture(engine, now, false, null);
        assertEquals("Gamma", data.myGrinds.get(0).definition.getName());
        assertEquals(3, data.myGrinds.size());
    }

    @Test
    public void saveThisGrindLinksOnlyTheNamedInstance() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        String firstId = engine.getActiveSession().getId();
        engine.sx(now + 2_000L);
        engine.ajl("Vorkath", Cx.GENERAL, now + 3_000L);
        engine.getActiveSession().kf(booked(now + 4_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        String secondId = engine.getActiveSession().getId();

        SavedState.Ap saved = engine.avg("Vorkath", 5_000_000L, 3L * 3_600_000L, false, secondId);
        assertNotNull(saved);
        assertEquals("only the named instance joins the lineage", 1,
            engine.yk(saved.getGrindId()).size());
        assertEquals(secondId, engine.yk(saved.getGrindId()).get(0).getId());
        assertFalse("the other same-name instance stays unlinked",
            engine.ua(firstId).xf());
    }

    @Test
    public void renameKeepsStableLineage() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Zulrah", Cx.GENERAL, now);
        String sessionId = engine.getActiveSession().getId();
        SavedState.Ap saved = engine.avg("Zulrah", null, null, false, sessionId);
        assertNotNull(saved);
        engine.updateSavedGrind(saved.getGrindId(), "Zulrah Bowfa", null, null, false);
        assertEquals("the id never changes with the name", saved.getGrindId(),
            engine.um(saved.getGrindId()).getGrindId());
        assertEquals(1, engine.yk(saved.getGrindId()).size());
    }

    @Test
    public void updateSavedGrindNeverRewritesHistoricalSnapshots() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        String sessionId = engine.getActiveSession().getId();
        SavedState.Ap saved = engine.avg("Vorkath", 5_000_000L, null, false, sessionId);
        engine.sx(now + 1_000L);

        engine.updateSavedGrind(saved.getGrindId(), "Vorkath", 10_000_000L, null, false);
        assertEquals(Long.valueOf(10_000_000L), engine.um(saved.getGrindId()).getNetTargetGp());
        assertEquals("the finished instance keeps the target it ran with", Long.valueOf(5_000_000L),
            engine.ua(sessionId).getProfitTargetGp());
    }

    /** Owner 2026-10-01 (F20): save and update read the viewed run, never the active one. */
    @Test
    public void historicalSaveUsesTheViewedRunNotTheActiveOne() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Historical", Cx.GENERAL, now);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        String historicalId = engine.getActiveSession().getId();
        engine.sx(now + 1_000L);

        engine.ajl("Active", Cx.GENERAL, now + 2_000L);
        engine.getActiveSession().setProfitTargetGp(9_000_000L);

        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.saveThisGrind(historicalId);
            ShellProbe.typeSheet(panel.shell(), "Saved historical");
            ShellProbe.pressSheet(panel.shell(), "OK");
            return null;
        });

        SavedState.Ap saved = engine.getSavedGrinds(false).stream()
            .filter(g -> "Saved historical".equals(g.getName())).findFirst().orElse(null);
        assertNotNull("the viewed run saves", saved);
        assertEquals("targets come from the viewed run, not the active one", Long.valueOf(5_000_000L),
            saved.getNetTargetGp());
        assertEquals("the viewed run joins the lineage", historicalId,
            engine.yk(saved.getGrindId()).get(0).getId());
    }

    @Test
    public void historicalSaveWorksWhenOnlyFreePlayIsActive() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Gone", Cx.GENERAL, now);
        engine.getActiveSession().setProfitTargetGp(4_000_000L);
        String historicalId = engine.getActiveSession().getId();
        engine.sx(now + 1_000L);

        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.saveThisGrind(historicalId);
            ShellProbe.typeSheet(panel.shell(), "Gone saved");
            ShellProbe.pressSheet(panel.shell(), "OK");
            return null;
        });

        SavedState.Ap saved = engine.getSavedGrinds(false).stream()
            .filter(g -> "Gone saved".equals(g.getName())).findFirst().orElse(null);
        assertNotNull("a viewed run saves even with no named run active", saved);
        assertEquals(Long.valueOf(4_000_000L), saved.getNetTargetGp());
    }

    @Test
    public void historicalUpdateTakesTheViewedRunTargets() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Historical", Cx.GENERAL, now);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        String historicalId = engine.getActiveSession().getId();
        SavedState.Ap saved = engine.avg("Historical", 5_000_000L, null, false, historicalId);
        engine.sx(now + 1_000L);

        engine.ajl("Active", Cx.GENERAL, now + 2_000L);
        engine.getActiveSession().setProfitTargetGp(9_000_000L);

        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.updateSavedGrind(saved.getGrindId(), historicalId);
            ShellProbe.pressSheet(panel.shell(), "Update");
            return null;
        });

        assertEquals("defaults come from the viewed run", Long.valueOf(5_000_000L),
            engine.um(saved.getGrindId()).getNetTargetGp());
        assertEquals("the active run's aim is untouched", Long.valueOf(9_000_000L),
            engine.getActiveSession().getProfitTargetGp());
    }

    @Test
    public void aRunDeletedWhileThePromptIsOpenSavesNothing() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Doomed", Cx.GENERAL, now);
        String doomedId = engine.getActiveSession().getId();
        engine.sx(now + 1_000L);

        engine.ajl("Active", Cx.GENERAL, now + 2_000L);

        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.saveThisGrind(doomedId);
            return null;
        });
        assertTrue(engine.qu(doomedId));
        int before = engine.getSavedGrinds(false).size();
        onEdt(() ->
        {
            ShellProbe.typeSheet(panel.shell(), "Too late");
            ShellProbe.pressSheet(panel.shell(), "OK");
            return null;
        });
        assertEquals("nothing saves once the viewed run is gone", before,
            engine.getSavedGrinds(false).size());
    }

    @Test
    public void startWithChangesDoesNotRewriteSavedDefaults() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        SavedState.Ap saved = engine.avg("Vorkath", 5_000_000L, null, false, null);
        long now = System.currentTimeMillis();
        assertTrue(engine.startGrind("Vorkath", saved.getGrindId(), 9_000_000L, 2L * 3_600_000L, now));
        assertEquals("the instance gets the one-off aim", Long.valueOf(9_000_000L),
            engine.getActiveSession().getProfitTargetGp());
        assertEquals("saved defaults are untouched", Long.valueOf(5_000_000L),
            engine.um(saved.getGrindId()).getNetTargetGp());
    }

    @Test
    public void startAgainStartsFreshWithNoCopiedFinancialState() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 5, 3_200, 16_000), 2_000);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        SavedState.Ap saved = engine.avg("Vorkath", 5_000_000L, null, false,
            engine.getActiveSession().getId());
        engine.sx(now + 2_000L);

        assertTrue(engine.startGrind("Vorkath", saved.getGrindId(), 5_000_000L, null, now + 3_000L));
        assertEquals("fresh financial instance", 0L, engine.getMetrics(now + 3_000L).net);
        assertEquals(0, engine.getActiveSession().getTransactions().size());
        assertEquals("fresh Active Time", 0L, engine.getMetrics(now + 3_000L).elapsedMillis);
        assertEquals("setup is repeated", saved.getGrindId(), engine.getActiveSession().getGrindId());
        assertTrue("history is never cleared", engine.getHistory().size() >= 1);
    }

    @Test
    public void archiveAndDeletePreserveCanonicalHistory() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        String sessionId = engine.getActiveSession().getId();
        SavedState.Ap saved = engine.avg("Vorkath", null, null, false, sessionId);
        engine.sx(now + 1_000L);

        assertTrue(engine.aht(saved.getGrindId(), true));
        assertTrue("archived definitions hide from the normal list",
            engine.getSavedGrinds(false).isEmpty());
        assertTrue(engine.deleteSavedGrind(saved.getGrindId()));
        assertEquals("the historical instance survives the template delete",
            sessionId, engine.getHistory().get(0).getId());
        assertEquals("its stable lineage link survives too",
            saved.getGrindId(), engine.getHistory().get(0).getGrindId());
    }

    @Test
    public void reachingATargetNeverAutoEndsTheGrind() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.ahq(1_000L, null, now + 2_000L);
        assertTrue(engine.getMetrics(now + 2_000L).net > 1_000L);
        assertFalse("the Grind stays open after the target", engine.getActiveSession().isClosed());
        assertEquals("no auto end reason was written", null, engine.getActiveSession().endReason);
    }

    @Test
    public void resetKeepsReusableSetupButClearsFinancialData() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.avg("Vorkath", 5_000_000L, null, false, engine.getActiveSession().getId());
        engine.agr(now + 2_000L);
        assertEquals("accounting reset clears history", 0, engine.getHistory().size());
        assertEquals("reusable setup metadata survives a data reset", 1, engine.getSavedGrinds(false).size());
    }

    @Test
    public void grindsPageRendersCurrentMyAndRecentSections() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(booked(now + 1_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);
        engine.avg("Zulrah", null, null, true, null);

        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            panel.shell().show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        List<String> labels = labels(panel);
        assertTrue("the Running line shows the Grind", anyContains(labels, "Vorkath"));
        assertTrue(anyContains(labels, "MY GRINDS"));
        assertTrue(anyContains(labels, "RECENT GRINDS"));
        assertTrue("saved Grinds render", anyContains(labels, "Zulrah"));
        // Release pass 2026-09-28: one Start Grind, one Add Grind, and a saved Grind starts from its menu.
        List<String> buttons = onEdt(() -> buttonTexts(panel));
        assertTrue(buttons.toString(), buttons.contains("Start Grind") && buttons.contains("+ Add Grind"));
        assertFalse("no per-Grind Start button", buttons.stream().anyMatch(text -> text.startsWith("Start Zulrah")));
        As.MyGrind zulrah = panel.grinds.data.myGrinds.stream()
            .filter(grind -> "Zulrah".equals(grind.definition.getName())).findFirst().orElseThrow();
        assertEquals("Start", panel.grinds.aun(zulrah).get(0).label);
                assertTrue("user-facing copy avoids the backend noun",
            labels.stream().noneMatch(label -> label.contains("Session")));
    }

    /** Owner 2026-10-01 (F14): the selected saved Grind starts in place, beside its menu. */
    @Test
    public void theSelectedSavedGrindOffersStartBesideItsMenu() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        SavedState.Ap saved = engine.avg("Zulrah", null, null, true, null);
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            panel.shell().show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            panel.grinds.select(saved.getGrindId());
            return null;
        });
        List<String> buttons = onEdt(() -> buttonTexts(panel));
        assertTrue("the selected panel offers Start: " + buttons, buttons.contains("Start"));

        assertTrue("the visible Start button starts the saved Grind",
            onEdt(() -> clickText(panel, "Start")));
        assertEquals("the run carries the saved lineage",
            saved.getGrindId(), engine.getActiveSession().getGrindId());
    }

    /** Owner 2026-10-01 (F21): the saved-Grind editor exposes the existing Favorite flag. */
    @Test
    public void savedGrindEditorTogglesFavoriteAndSortsItFirst() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        SavedState.Ap plain = engine.avg("Zulrah", null, null, false, null);
        assertNotNull(engine.avg("Vorkath", null, null, false, null));
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        GrindsController controller = new GrindsController(panel);

        onEdt(() ->
        {
            controller.editSavedGrind(plain.getGrindId());
            javax.swing.JCheckBox box = ShellProbe.sheetCheckbox(panel.shell(), "Favorite");
            assertNotNull("the editor offers a Favorite control", box);
            assertFalse("it starts from the saved definition", box.isSelected());
            box.doClick();
            ShellProbe.pressSheet(panel.shell(), "Save");
            return null;
        });
        assertTrue("the toggle writes the existing field",
            engine.um(plain.getGrindId()).favorite);
        assertEquals("a favorite sorts first", plain.getGrindId(),
            As.capture(engine, System.currentTimeMillis(), false, null)
                .myGrinds.get(0).definition.getGrindId());

        onEdt(() ->
        {
            controller.editSavedGrind(plain.getGrindId());
            javax.swing.JCheckBox box = ShellProbe.sheetCheckbox(panel.shell(), "Favorite");
            assertTrue("reopening shows the saved value", box.isSelected());
            box.doClick();
            ShellProbe.pressSheet(panel.shell(), "Save");
            return null;
        });
        assertFalse("toggling off round-trips too", engine.um(plain.getGrindId()).favorite);
    }

    /** Owner 2026-10-01 (F21): a one-off "Start with changes" never edits the saved Favorite. */
    @Test
    public void startWithChangesNeverTouchesTheSavedFavorite() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        SavedState.Ap saved = engine.avg("Zulrah", null, null, true, null);
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.startWithChanges(saved.getGrindId());
            assertNull("the one-off form has no Favorite control",
                ShellProbe.sheetCheckbox(panel.shell(), "Favorite"));
            ShellProbe.pressSheet(panel.shell(), "Start");
            return null;
        });
        assertTrue("the saved definition keeps its flag",
            engine.um(saved.getGrindId()).favorite);
    }

    /** Owner 2026-10-01 (F22): the Start sheet pager appears only when rows exceed five. */
    @Test
    public void startSheetPagerAppearsOnlyAboveFiveRows() throws Exception
    {
        Am empty = PresentationLifecycleTest.engine();
        Dp emptyPanel = onEdt(() -> new Dp(empty, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            new GrindsController(emptyPanel).newGrind();
            List<String> texts = ShellProbe.sheetButtonTexts(emptyPanel.shell());
            assertFalse("nothing to page",
                texts.contains("\u2039") || texts.contains("\u203a"));
            assertTrue("the name field and the primary action stay visible",
                texts.contains("Start") && texts.contains("Cancel"));
            return null;
        });

        Am single = PresentationLifecycleTest.engine();
        assertNotNull(single.avg("Only", null, null, false, null));
        Dp singlePanel = onEdt(() -> new Dp(single, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            new GrindsController(singlePanel).newGrind();
            List<String> texts = ShellProbe.sheetButtonTexts(singlePanel.shell());
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
        Am engine = PresentationLifecycleTest.engine();
        for (int i = 1; i <= 6; i++)
        {
            assertNotNull(engine.avg("Plan " + i, null, null, false, null));
        }
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.newGrind();
            List<String> texts = ShellProbe.sheetButtonTexts(panel.shell());
            assertTrue(texts.toString(), texts.contains("Plan 1") && texts.contains("Plan 5"));
            assertFalse("the sixth row waits on the next page", texts.contains("Plan 6"));
            assertEquals("five rows on one page", 5,
                texts.stream().filter(text -> text.startsWith("Plan ")).count());
            assertTrue("the window is named", anyLabelContains(panel, "1\u20135 of 6"));
            assertTrue("paging reaches the sixth",
                ShellProbe.pressSheetText(panel.shell(), "\u203a"));
            texts = ShellProbe.sheetButtonTexts(panel.shell());
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
        Am engine = PresentationLifecycleTest.engine();
        for (int i = 1; i <= 100; i++)
        {
            assertNotNull(engine.avg("Plan " + i, null, null, false, null));
        }
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.newGrind();
            List<String> texts = ShellProbe.sheetButtonTexts(panel.shell());
            assertEquals("the sheet never grows past five rows", 5,
                texts.stream().filter(text -> text.startsWith("Plan ")).count());
            assertFalse("later pages are not rendered", texts.contains("Plan 100"));
            for (int i = 0; i < 19; i++)
            {
                assertTrue("paging forward", ShellProbe.pressSheetText(panel.shell(), "\u203a"));
            }
            texts = ShellProbe.sheetButtonTexts(panel.shell());
            assertTrue(texts.toString(), texts.contains("Plan 100"));
            assertEquals(5, texts.stream().filter(text -> text.startsWith("Plan ")).count());
            assertTrue(anyLabelContains(panel, "96\u2013100 of 100"));
            assertFalse("the last page disables next",
                ShellProbe.pressSheetText(panel.shell(), "\u203a"));
            assertTrue(ShellProbe.pressSheetText(panel.shell(), "\u2039"));
            assertTrue(anyLabelContains(panel, "91\u201395 of 100"));
            return null;
        });
    }

    /** Owner 2026-10-01 (F22): the Restore sheet bounds backups; seconds and the filename separate them. */
    @Test
    public void restoreSheetBoundsBackupsAndLabelsTheirSeconds() throws Exception
    {
        Path dir = temporary.newFolder().toPath();
        long now = System.currentTimeMillis();
        Filepath root = FilepathTestSupport.root(dir);
        long[] times = {now, now - 1_000L, now - 2_000L, now - 3_000L,
            now - 4_000L, now - 4_000L};
        String[] names = {"rsprofile.alice-20261001-120000.json",
            "rsprofile.alice-20261001-120001.json",
            "rsprofile.alice-20261001-120002.json",
            "rsprofile.alice-20261001-120003.json",
            "rsprofile.alice-20261001-120004.json",
            "rsprofile.alice-20261001-120004-2.json"};
        List<Filepath> files = new ArrayList<>();
        for (int i = 0; i < names.length; i++)
        {
            Filepath file = root.joinSegment(names[i]);
            file.write("{}", java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);
            Files.setLastModifiedTime(dir.resolve(names[i]), FileTime.fromMillis(times[i]));
            files.add(file);
        }
        Am engine = PresentationLifecycleTest.engine();
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null,
            null, new FakeBackups(files)));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.agm();
            List<JButton> rows = new ArrayList<>();
            for (JButton button : ShellProbe.sheetButtons(panel.shell()))
            {
                if (button.getText().contains(":"))
                {
                    rows.add(button);
                }
            }
            assertEquals("five rows on one page", 5, rows.size());
            for (JButton row : rows)
            {
                assertTrue("seconds disambiguate: " + row.getText(),
                    row.getText().matches(".*:\\d\\d"));
            }
            assertTrue(anyLabelContains(panel, "1\u20135 of 6"));
            JButton sameSecond = rows.stream().filter(row -> row.getToolTipText() != null
                && row.getToolTipText().endsWith("120004.json")).findFirst().orElse(null);
            assertNotNull("the same-second backup is on page one", sameSecond);
            assertTrue(ShellProbe.pressSheetText(panel.shell(), "\u203a"));
            assertTrue(anyLabelContains(panel, "6\u20136 of 6"));
            JButton suffixed = ShellProbe.sheetButtons(panel.shell()).stream()
                .filter(button -> button.getToolTipText() != null
                    && button.getToolTipText().endsWith("120004-2.json"))
                .findFirst().orElse(null);
            assertNotNull(suffixed);
            assertEquals("the same backup second keeps the same label",
                sameSecond.getText(), suffixed.getText());
            assertFalse("the filename is the distinguishing suffix",
                sameSecond.getToolTipText().equals(suffixed.getToolTipText()));
            return null;
        });
    }

    /** Owner 2026-10-01 (F14): ending offers a recap route to the ended run. */
    @Test
    public void endingOffersAViewRecapRouteToTheEndedRun() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        String id = engine.getActiveSession().getId();
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.endGrind();
            return null;
        });
        assertTrue("the run closed into history",
            engine.ua(id) != null && engine.ua(id).isClosed());
        assertTrue("the notice names the ended run: " + ShellProbe.noticeText(panel.shell()),
            ShellProbe.noticeText(panel.shell()).startsWith("Vorkath ended"));

        onEdt(() ->
        {
            for (java.awt.Component component : panel.shell().noticeAction.getComponents())
            {
                if (component instanceof javax.swing.JButton)
                {
                    ((javax.swing.JButton) component).doClick();
                }
            }
            return null;
        });
        assertEquals("the recap opens the ended run", id, panel.grindsDetailId);
        assertEquals(Shell.GRINDS, panel.shell().qt());
    }

    /** Returns the fabricated backups the test chooses; the real service is never involved. */
    private static final class FakeBackups extends Ei
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
        int adz(int keep)
        {
            adzCalled = true;
            return Math.max(0, files.size() - keep);
        }
    }

    /** One reset-path fake: records the order and can fail the backup (owner 2026-10-01, F26). */
    private static final class FakeReset extends Ei
    {
        final java.util.List<String> calls = new ArrayList<>();
        final Filepath backup;
        boolean backupFails;

        FakeReset(Filepath backup)
        {
            super(null, null, null, null, null);
            this.backup = backup;
        }

        @Override
        Filepath akt(long now) throws java.io.IOException
        {
            calls.add("backup");
            if (backupFails)
            {
                throw new java.io.IOException("disk full");
            }
            return backup;
        }

        @Override
        Ei.Ds sj(long now)
        {
            calls.add("reset");
            return new Ei.Ds(true, "", null);
        }
    }

    /** Owner 2026-10-01 (F26): reset offers Back up first; a failed backup refuses the reset. */
    @Test
    public void factoryResetBacksUpFirstAndFailsClosed() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        Path dir = temporary.newFolder().toPath();
        Filepath backup = FilepathTestSupport.root(dir).joinSegment("profile-backup.json");
        FakeReset fake = new FakeReset(backup);
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null,
            null, fake));
        GrindsController controller = new GrindsController(panel);

        onEdt(() ->
        {
            controller.ti();
            List<String> buttons = ShellProbe.sheetButtonTexts(panel.shell());
            assertTrue("both choices stay offered: " + buttons,
                buttons.contains("Back up first") && buttons.contains("Reset"));
            fake.backupFails = true;
            ShellProbe.pressSheet(panel.shell(), "Back up first");
            return null;
        });
        assertEquals("the backup ran first", java.util.Arrays.asList("backup"), fake.calls);
        assertTrue("a failed backup refuses the reset: " + ShellProbe.noticeText(panel.shell()),
            ShellProbe.noticeText(panel.shell()).startsWith("Backup failed"));

        onEdt(() ->
        {
            fake.backupFails = false;
            controller.ti();
            ShellProbe.pressSheet(panel.shell(), "Back up first");
            return null;
        });
        assertEquals("the backup precedes the reset",
            java.util.Arrays.asList("backup", "backup", "reset"), fake.calls);
    }

    /** Owner 2026-10-01 (F26): clearing old backups states the count and the kept newest first. */
    @Test
    public void clearOldBackupsAsksBeforeDeleting() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        Path dir = temporary.newFolder().toPath();
        long now = System.currentTimeMillis();
        List<Filepath> files = new ArrayList<>();
        for (int i = 0; i < 3; i++)
        {
            String name = "rsprofile.alice-20261001-12000" + i + ".json";
            Filepath file = FilepathTestSupport.root(dir).joinSegment(name);
            file.write("{}", java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);
            Files.setLastModifiedTime(dir.resolve(name), FileTime.fromMillis(now - i * 1_000L));
            files.add(file);
        }
        FakeBackups fake = new FakeBackups(files);
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null,
            null, fake));
        GrindsController controller = new GrindsController(panel);
        onEdt(() ->
        {
            controller.qw();
            return null;
        });
        assertEquals("Clear old backups", ShellProbe.sheetTitle(panel.shell()));
        assertTrue("the count and the kept newest are stated: " + labels(panel),
            labels(panel).stream().anyMatch(label -> label.contains("Remove 2 older backups")
                && label.contains(Fmt.when(now, System.currentTimeMillis()))
                && label.contains("is kept?")));
        assertFalse("nothing is deleted before the confirm", fake.adzCalled);
        onEdt(() ->
        {
            ShellProbe.pressSheet(panel.shell(), "Remove");
            return null;
        });
        assertTrue("the confirm runs the existing deletion", fake.adzCalled);
    }

    /** Owner 2026-10-01: Ledger and Grinds share one toolbar band, attached to the tab strip. */
    @Test
    public void ledgerAndGrindsShareTheSameAttachedToolbarBand() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            panel.shell().show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertTrue("the Ledger header keeps the shared band shape",
            onEdt(() -> bandShape(panel.ledger.pageBar())));
        assertTrue("the Grinds header uses the same band shape",
            onEdt(() -> bandShape(panel.grinds.pageBar())));
        List<String> headerButtons = onEdt(() -> buttonTexts(panel.grinds.pageBar()));
        assertTrue("the Grinds header carries the controls: " + headerButtons,
            headerButtons.contains("Start Grind") && headerButtons.contains("+ Add Grind")
                && headerButtons.contains("\u00b7\u00b7\u00b7"));
        assertFalse("the controls left the scrolling body",
            onEdt(() -> buttonTexts(panel.grinds.body())).contains("Start Grind"));
        javax.swing.border.Border border = onEdt(() -> panel.shell().bar.getBorder());
        assertEquals("the toolbar attaches to the tab strip",
            0, border.getBorderInsets(onEdt(() -> panel.shell().bar)).top);
    }

    /** Controls left, actions right: the one page-toolbar band both headers use. */
    private static boolean bandShape(Component pageBar)
    {
        if (!(pageBar instanceof Container))
        {
            return false;
        }
        for (Component child : ((Container) pageBar).getComponents())
        {
            if (!(child instanceof Container))
            {
                continue;
            }
            Container row = (Container) child;
            if (!(row.getLayout() instanceof java.awt.BorderLayout))
            {
                continue;
            }
            java.awt.BorderLayout layout = (java.awt.BorderLayout) row.getLayout();
            Component left = layout.getLayoutComponent(java.awt.BorderLayout.CENTER);
            Component right = layout.getLayoutComponent(java.awt.BorderLayout.EAST);
            if (left instanceof Container && right instanceof Container
                && ((Container) left).getLayout() instanceof java.awt.FlowLayout
                && ((Container) right).getLayout() instanceof java.awt.FlowLayout
                && ((Container) left).getComponentCount() >= 1
                && ((Container) right).getComponentCount() >= 1)
            {
                return true;
            }
        }
        return false;
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
        Am engine = PresentationLifecycleTest.engine();
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            panel.shell().show(Shell.GRINDS);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertTrue("the gear beside the tabs holds the profile data actions",
            anyAccessibleContains(panel, "Profile data"));
        java.util.List<String> gear = new java.util.ArrayList<>();
        for (java.awt.Component item : onEdt(() -> SidebarPanelProbe.dataMenu(panel)).getComponents())
        {
            if (item instanceof javax.swing.JMenuItem)
            {
                gear.add(((javax.swing.JMenuItem) item).getText());
            }
        }
        assertEquals(java.util.Arrays.asList("Back up profile", "Restore backup\u2026", "Clear old backups", "Copy data folder path",
            "Factory reset current profile…"), gear);
        assertTrue("no standalone Search page", java.util.Arrays.stream(ShellProbe.tabLabels(panel.shell()))
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
        As.Recent today = new As.Recent("Vorkath", "Vorkath", todayTimestamp,
            2_840_000L, 37 * 60_000L, "");
        String context = GrindsPage.qe(today, now);
        assertTrue("day label leads the context: " + context, context.startsWith("Today"));
        assertTrue("duration is present: " + context, context.contains("37m"));
        assertFalse("no internal ids leak", context.contains("Vorkath"));

        assertEquals("understandable zero", "0 GP", GrindsPage.avo(0L));
        assertEquals("+2.84M", GrindsPage.avo(2_840_000L));
        assertEquals("\u2212412k", GrindsPage.avo(-412_000L));

        As.Recent yesterday = new As.Recent("Zulrah", "Zulrah",
            startOfToday - 3_600_000L, -412_000L, 96 * 60_000L, "");
        assertTrue("yesterday reads naturally: " + GrindsPage.qe(yesterday, now),
            GrindsPage.qe(yesterday, now).contains("Yesterday"));
    }

    @Test
    public void recentGrindsSectionUsesTheKitTable() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now - 3_600_000L);
        engine.getActiveSession().kf(booked(now - 3_500_000L, "Dragon bones", 1, 1, 3_200, 3_200), 2_000);
        engine.sx(now - 3_000_000L);
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            panel.shell().show(Shell.GRINDS);
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

    private static Ac booked(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Ac(at, null, Ai.GAIN, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(itemId, name, quantity, unitPrice, value)),
            Bd.LIKELY, "Test sample.", null);
    }
}
