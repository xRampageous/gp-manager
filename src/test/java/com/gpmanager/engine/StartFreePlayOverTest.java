package com.gpmanager;

import static com.gpmanager.TargetFreshStartTest.HOUR;
import static com.gpmanager.TargetFreshStartTest.coins;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Owner 1.5.2: the gear menu's Reset free-play moves the run so far to Grinds history, with
 * nothing deleted, and starts a new Free play at zero that keeps the milestone.
 */
public class StartFreePlayOverTest
{
    @Test
    public void theOldRunMovesToHistoryWithEveryRow()
    {
        Engine engine = TargetFreshStartTest.engine();
        Session before = engine.freePlay();
        coins(engine, 5L, 1_000L);
        coins(engine, 10L, 500L);
        engine.setMilestone(1_000_000_000L);
        before.pause(HOUR - 1L, PauseReason.MANUAL);

        assertTrue(engine.startFreePlayOver(HOUR));
        Session kept = engine.history().get(0);
        assertSame("the run is kept, not deleted", before, kept);
        assertEquals("Free play", kept.name());
        assertTrue(kept.isClosed());
        assertEquals(2, kept.transactions().size());
        assertEquals(1_500L, kept.metrics(HOUR).net);

        Session fresh = engine.freePlay();
        assertNotSame(before, fresh);
        assertSame(fresh, engine.session());
        assertTrue("the new Free play starts at zero", fresh.transactions().isEmpty());
        assertEquals("the milestone counts the new run", Long.valueOf(1_000_000_000L), fresh.milestoneGp);
        assertSame("a manual pause holds", PauseReason.MANUAL, fresh.pauseReason());
        assertEquals(0L, TargetFreshStartTest.live(engine, HOUR + 1L).run);
    }

    /** Owner 1.5.2: Restore swaps a past Free play back in; the current one moves to history. */
    @Test
    public void restoreSwapsAndSwapsBack()
    {
        Engine engine = TargetFreshStartTest.engine();
        Session first = engine.freePlay();
        coins(engine, 5L, 1_000L);
        engine.setMilestone(1_000_000_000L);
        long firstActive = first.elapsed(HOUR);
        assertTrue(engine.startFreePlayOver(HOUR));
        Session second = engine.freePlay();
        coins(engine, HOUR + 5L, 300L);

        assertTrue(engine.restoreFreePlay(first.id(), 2 * HOUR));
        assertSame("the old run is Free play again", first, engine.freePlay());
        assertSame(first, engine.session());
        assertFalse(first.isClosed());
        assertEquals("every row came back", 1, first.transactions().size());
        assertEquals("its active time is unchanged", firstActive, first.elapsed(2 * HOUR));
        assertEquals(1_000L, TargetFreshStartTest.live(engine, 2 * HOUR).run);
        assertEquals(Long.valueOf(1_000_000_000L), first.milestoneGp);
        assertSame("the current run waits in history", second, engine.history().get(0));
        assertTrue(second.isClosed());

        assertTrue("and swaps back", engine.restoreFreePlay(second.id(), 3 * HOUR));
        assertSame(second, engine.freePlay());
        assertEquals(300L, TargetFreshStartTest.live(engine, 3 * HOUR).run);
        assertSame(first, engine.history().get(0));
    }

    @Test
    public void anEmptyCurrentRunIsDroppedAndGrindsCannotBeRestored()
    {
        Engine engine = TargetFreshStartTest.engine();
        Session first = engine.freePlay();
        coins(engine, 5L, 1_000L);
        engine.startFreePlayOver(HOUR);
        assertTrue(engine.restoreFreePlay(first.id(), 2 * HOUR));
        assertTrue("an empty Free play is not kept", engine.history().isEmpty());

        engine.startCustom("Vorkath", 2 * HOUR);
        assertFalse("not during a Grind", engine.restoreFreePlay(first.id(), 3 * HOUR));
        engine.finishCustom(3 * HOUR);
        Session grind = engine.history().get(0);
        assertFalse("a Grind run is not Free play", engine.restoreFreePlay(grind.id(), 4 * HOUR));
    }

    @Test
    public void grindsListsFreePlayApart()
    {
        Engine engine = TargetFreshStartTest.engine();
        coins(engine, 5L, 1_000L);
        engine.startFreePlayOver(HOUR);
        engine.startCustom("Vorkath", HOUR);
        engine.finishCustom(2 * HOUR);
        GrindsData data = GrindsData.capture(engine, 2 * HOUR, false, null);
        long free = data.recent.stream().filter(run -> run.general).count();
        assertEquals("the reset run and the current Free play", 2, free);
        assertTrue(data.recent.stream().anyMatch(run -> !run.general && "Vorkath".equals(run.name)));
    }

    /** Owner live 1.5.2: a past Free play's detail page offers Restore, never Start again. */
    @Test
    public void aPastFreePlayDetailOffersRestore() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ensureSession(now - HOUR);
        Session old = engine.freePlay();
        coins(engine, now - 60_000L, 1_000L);
        engine.startFreePlayOver(now - 30_000L);
        SidebarPanel panel = MilestoneTest.onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        java.util.List<String> buttons = MilestoneTest.onEdt(() ->
        {
            panel.shell.show(Shell.GRINDS);
            new GrindsController(panel).openDetail(old.id());
            SidebarPanelProbe.refresh(panel);
            java.util.List<String> texts = new java.util.ArrayList<>();
            collect(panel, texts);
            return texts;
        });
        assertTrue("Restore is offered: " + buttons, buttons.contains("Restore"));
        assertFalse("not Start again: " + buttons, buttons.contains("Start again"));
    }

    static void collect(java.awt.Component component, java.util.List<String> out)
    {
        if (component instanceof javax.swing.AbstractButton && component.isVisible())
            out.add(((javax.swing.AbstractButton) component).getText());
        if (component instanceof java.awt.Container)
            for (java.awt.Component child : ((java.awt.Container) component).getComponents())
                collect(child, out);
    }

    @Test
    public void notDuringAGrind() throws Exception
    {
        Engine engine = TargetFreshStartTest.engine();
        engine.startCustom("Vorkath", HOUR);
        assertFalse(engine.startFreePlayOver(HOUR + 1L));

        Engine panelEngine = PresentationLifecycleTest.engine();
        panelEngine.startCustom("Vorkath", System.currentTimeMillis());
        SidebarPanel panel = MilestoneTest.onEdt(() -> new SidebarPanel(panelEngine, PresentationLifecycleTest.config(), null, null, null));
        javax.swing.JPopupMenu menu = MilestoneTest.onEdt(() -> new GrindsController(panel).dataMenu());
        boolean enabled = true;
        for (java.awt.Component item : menu.getComponents())
            if (item instanceof javax.swing.JMenuItem && ((javax.swing.JMenuItem) item).getText().startsWith("Reset free-play"))
                enabled = item.isEnabled();
        assertFalse("the gear item is off during a Grind", enabled);
    }
}
