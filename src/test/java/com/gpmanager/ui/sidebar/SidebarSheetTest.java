package com.gpmanager;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Forms and confirmations open inside the sidebar (SIDEBAR_SPEC.md §4), never as popups. */
public class SidebarSheetTest
{
    /** Owner 1.5.0: one Start sheet; a new name becomes a Grind, a known one starts in a click. */
    @Test
    public void startGrindSavesNewNamesAndStartsKnownOnes() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        Shell shell = panel.shell();
        GrindsController grinds = new GrindsController(panel);
        onEdt(() ->
        {
            grinds.newGrind();
            ShellProbe.typeSheet(shell, "   ");
            ShellProbe.pressSheet(shell, "Start");
            return null;
        });
        assertTrue("a blank name starts nothing", engine.getSavedGrinds(true).isEmpty());
        assertFalse(engine.isCustomActive());

        onEdt(() ->
        {
            grinds.newGrind();
            ShellProbe.typeSheet(shell, "  Vorkath ");
            ShellProbe.pressSheet(shell, "Start");
            return null;
        });
        assertTrue(engine.isCustomActive());
        assertEquals(1, engine.getSavedGrinds(true).size());
        SavedState.SavedGrind saved = engine.getSavedGrinds(true).get(0);
        assertEquals("the name is trimmed", "Vorkath", saved.getName());
        assertEquals("the run is that Grind", saved.getGrindId(), engine.getActiveSession().getGrindId());
        assertEquals("starting shows it on Live", Shell.LIVE, onEdt(shell::currentPage));
        engine.finishCustom(System.currentTimeMillis());

        // What Free play is doing now is offered first.
        engine.setDetectedActivity("Vorkath", System.currentTimeMillis());
        onEdt(() ->
        {
            grinds.newGrind();
            return null;
        });
        assertEquals("Start Grind", ShellProbe.sheetTitle(shell));
        onEdt(() ->
        {
            ShellProbe.pressSheet(shell, "Vorkath \u00b7 now");
            return null;
        });
        assertTrue(engine.isCustomActive());
        assertEquals(saved.getGrindId(), engine.getActiveSession().getGrindId());

        onEdt(() ->
        {
            grinds.newGrind();
            ShellProbe.typeSheet(shell, "Zulrah");
            ShellProbe.pressSheet(shell, "Start");
            return null;
        });
        assertEquals("a running Grind asks before switching", "Switch Grind", ShellProbe.sheetTitle(shell));
        onEdt(() ->
        {
            ShellProbe.pressSheet(shell, "Switch");
            return null;
        });
        assertEquals("Zulrah", engine.getActiveSession().getName());
        assertEquals("Zulrah is remembered too", 2, engine.getSavedGrinds(true).size());
    }

    @Test
    public void confirmRunsOnlyOnItsButton() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.startCustom("Vorkath", System.currentTimeMillis());
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        Shell shell = panel.shell();
        boolean[] ran = {false};
        onEdt(() ->
        {
            shell.confirm("Delete run", "This cannot be undone.", "Delete", () -> ran[0] = true);
            ShellProbe.pressSheet(shell, "Cancel");
            return null;
        });
        assertFalse("Cancel changes nothing", ran[0]);
        onEdt(() ->
        {
            shell.confirm("Delete run", "This cannot be undone.", "Delete", () -> ran[0] = true);
            ShellProbe.pressSheet(shell, "Delete");
            return null;
        });
        assertTrue("the named button runs it", ran[0]);
        assertTrue(engine.isCustomActive());
    }

    /** A rename from the Live hero name is a change like any other: the revision advances, so it is saved. */
    @Test
    public void liveRenameIsSaved() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.startCustom("Vorkath", System.currentTimeMillis());
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        Shell shell = panel.shell();
        long before = engine.getRevision();
        onEdt(() ->
        {
            panel.new LiveActions().renameGrind();
            ShellProbe.typeSheet(shell, "Zulrah");
            ShellProbe.pressSheet(shell, "OK");
            return null;
        });
        assertEquals("Zulrah", engine.getActiveSession().getName());
        assertTrue("the rename is saved", engine.getRevision() > before);
    }

    /** Owner 2026-09-28: End on the Live hero ends at once; there is no second confirmation. */
    @Test
    public void liveEndGrindEndsAtOnce() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.startCustom("Vorkath", System.currentTimeMillis());
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        Shell shell = panel.shell();
        onEdt(() ->
        {
            panel.new LiveActions().endGrind();
            return null;
        });
        assertFalse("End ends it", engine.isCustomActive());
        assertEquals("no confirm sheet opens", "", ShellProbe.sheetTitle(shell));
    }

    /** Release pass 2026-09-28: a long value never pushes its key out; it shortens and hovers in full. */
    @Test
    public void aLongKeyValueKeepsItsKey() throws Exception
    {
        String value = "Ended before target \u00b7 +4.98M remaining";
        KeyValue kv = onEdt(() ->
        {
            KeyValue created = new KeyValue(null).put("Target", value, null);
            created.setSize(200, 40);
            created.doLayout();
            created.body.doLayout();
            javax.swing.JPanel line = (javax.swing.JPanel) created.body.getComponent(0);
            line.setSize(200, 20);
            line.doLayout();
            return created;
        });
        javax.swing.JPanel line = (javax.swing.JPanel) kv.body.getComponent(0);
        assertTrue("the key keeps its width", line.getComponent(0).getWidth() > 20);
        assertEquals(value, ((javax.swing.JLabel) line.getComponent(1)).getToolTipText());
    }

    /** Release pass 2026-09-28: Undo last change and Restore last undo moved to the Ledger's menu. */
    @Test
    public void ledgerMenuUndoesAndRestoresTheLastChange() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.getActiveSession().addTransaction(new Transaction(now + 1_000L, TransactionType.LOOT,
            Context.LOOT, "", "Vorkath", true, java.util.Collections.singletonList(
                new Flow(536, "Dragon bones", 1L, 2_000, 2_000L)), Confidence.CONFIRMED, "fixture", null), 2_000, false);
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        LedgerPage.Actions ledger = SidebarPanelProbe.ledgerPage(panel).actions;
        onEdt(() ->
        {
            // F01: the menu pins its target when it opens; pin here to exercise the action path.
            ledger.pinMenuTarget();
            ledger.undoLast();
            return null;
        });
        assertEquals("undone", 0L, engine.getActiveSession().metrics(now + 5_000L).net);
        onEdt(() ->
        {
            ledger.pinMenuTarget();
            ledger.restoreUndo();
            return null;
        });
        assertEquals("restored", 2_000L, engine.getActiveSession().metrics(now + 5_000L).net);
    }

    @Test
    public void malformedTargetsReopenTheFormAndChangeNothing() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.startCustom("Vorkath", System.currentTimeMillis());
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        Shell shell = panel.shell();
        GrindsController grinds = new GrindsController(panel);
        onEdt(() ->
        {
            grinds.openTargets();
            ShellProbe.typeSheet(shell, "lots");
            ShellProbe.pressSheet(shell, "Save");
            return null;
        });
        assertEquals("the form stays open to fix", "Targets", ShellProbe.sheetTitle(shell));
        assertTrue(ShellProbe.noticeText(shell).startsWith("Targets not saved"));
        assertNull("nothing is cleared or guessed", engine.getActiveSession().getProfitTargetGp());
        onEdt(() ->
        {
            ShellProbe.typeSheet(shell, "5m");
            ShellProbe.pressSheet(shell, "Save");
            return null;
        });
        assertEquals(Long.valueOf(5_000_000L), engine.getActiveSession().getProfitTargetGp());
    }

    /** The time field is pre-filled in a form its own parser reads back unchanged. */
    @Test
    public void unchangedTargetsSaveAsShown() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.startCustom("Vorkath", System.currentTimeMillis());
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        Shell shell = panel.shell();
        GrindsController grinds = new GrindsController(panel);
        for (long target : new long[]{30L * 60_000L, 90L * 60_000L, 26L * 3_600_000L + 30L * 60_000L})
        {
            engine.getActiveSession().setActiveTimeTargetMillis(target, System.currentTimeMillis());
            onEdt(() ->
            {
                grinds.openTargets();
                ShellProbe.pressSheet(shell, "Save");
                return null;
            });
            assertEquals("Save without changes closes the form", "", ShellProbe.sheetTitle(shell));
            assertEquals(Long.valueOf(target), engine.getActiveSession().getActiveTimeTargetMillis());
        }
    }

    @Test
    public void switchingPagesClosesTheSheet() throws Exception
    {
        SidebarPanel panel = onEdt(() -> new SidebarPanel(PresentationLifecycleTest.engine(),
            PresentationLifecycleTest.config(), null, null, null));
        Shell shell = panel.shell();
        onEdt(() ->
        {
            new GrindsController(panel).newGrind();
            shell.show(Shell.LEDGER);
            return null;
        });
        assertEquals("", ShellProbe.sheetTitle(shell));
    }

    private static void collect(java.awt.Component component, StringBuilder out)
    {
        if (component instanceof javax.swing.JLabel)
        {
            out.append(((javax.swing.JLabel) component).getText()).append(' ');
        }
        if (component instanceof java.awt.Container)
        {
            for (java.awt.Component child : ((java.awt.Container) component).getComponents())
            {
                collect(child, out);
            }
        }
    }

    private static <T> T onEdt(Callable<T> callable) throws Exception
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
