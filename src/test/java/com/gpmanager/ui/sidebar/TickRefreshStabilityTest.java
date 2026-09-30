package com.gpmanager;

import java.awt.Component;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

/**
 * The per-game-tick pulse must not rebuild the sidebar. Rebuilding every 0.6 s replaced the rows
 * a Swing menu was anchored to (the owner's disappearing dropdowns) and the labels a reader was
 * walking. Values still update in place, and a real data change still rebuilds.
 */
public class TickRefreshStabilityTest
{
    @Test
    public void liveRowsSurviveTicksAndRebuildOnlyWhenTheDataChanges() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(gain(now + 1_000L, "Dragon bones", 536, 3_200L), 2_000);
        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            return created;
        });
        List<Component> before = onEdt(() -> children(SidebarPanelProbe.livePage(panel).body()));

        for (int tick = 0; tick < 3; tick++)
        {
            panel.tick();
            flushEdt();
        }
        List<Component> afterTicks = onEdt(() -> children(SidebarPanelProbe.livePage(panel).body()));
        assertEquals(before.size(), afterTicks.size());
        for (int index = 0; index < before.size(); index++)
        {
            assertSame("tick " + index + " kept the same component", before.get(index), afterTicks.get(index));
        }

        engine.getActiveSession().kf(gain(now + 2_000L, "Rune platebody", 1127, 38_000L), 2_000);
        panel.tick();
        flushEdt();
        List<Component> afterBooking = onEdt(() -> children(SidebarPanelProbe.livePage(panel).body()));
        assertNotEquals("a new booking rebuilds the recent rows", before, afterBooking);
    }

    @Test
    public void openLedgerIsNotRebuiltByTicksButIsByANewBooking() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(gain(now + 1_000L, "Dragon bones", 536, 3_200L), 2_000);
        Dp panel = onEdt(() ->
        {
            Dp created = new Dp(engine, PresentationLifecycleTest.config(), null);
            SidebarPanelProbe.refresh(created);
            SidebarPanelProbe.openLedger(created, Ao.Entry.current());
            return created;
        });
        flushEdt();
        List<Component> before = onEdt(() -> children(SidebarPanelProbe.ledgerPage(panel).body()));

        panel.tick();
        flushEdt();
        panel.tick();
        flushEdt();
        List<Component> afterTicks = onEdt(() -> children(SidebarPanelProbe.ledgerPage(panel).body()));
        assertEquals("ticks never rebuild the open Ledger", before, afterTicks);

        engine.getActiveSession().kf(gain(now + 2_000L, "Rune platebody", 1127, 38_000L), 2_000);
        panel.tick();
        flushEdt();
        List<Component> afterBooking = onEdt(() -> children(SidebarPanelProbe.ledgerPage(panel).body()));
        assertNotEquals("a new booking re-reads the Ledger", before, afterBooking);
    }

    private static Ac gain(long at, String name, int itemId, long value)
    {
        return new Ac(at, null, Ai.GAIN, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(itemId, name, 1L, (int) value, value)),
            Bd.LIKELY, "Test sample.", null);
    }

    /** Every component under the body, depth first; a rebuilt row shows up as a new instance. */
    private static List<Component> children(JComponent body)
    {
        List<Component> out = new java.util.ArrayList<>();
        java.util.Deque<Component> stack = new java.util.ArrayDeque<>(Arrays.asList(body.getComponents()));
        while (!stack.isEmpty())
        {
            Component component = stack.pop();
            out.add(component);
            if (component instanceof java.awt.Container)
            {
                stack.addAll(Arrays.asList(((java.awt.Container) component).getComponents()));
            }
        }
        return out;
    }

    /** Run everything already queued on the EDT, including a drain the last call scheduled. */
    private static void flushEdt() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
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
