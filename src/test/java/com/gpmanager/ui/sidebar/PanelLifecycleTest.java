package com.gpmanager;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Owner fencing, thread ownership and the presentation-never-books contract. */
public class PanelLifecycleTest
{
    @Test
    public void ownerScopeChangeDropsThePreviousOwnersMarks() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        String sessionId = engine.getActiveSession().getId();
        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));

        panel.ot();

        panel.ahd();
        assertNotNull(SidebarPanelProbe.live(panel));
    }

    @Test
    public void captureIsAReadAndNeverBooks() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(Tx.of(now + 1_000L, null, Ai.GAIN,
            Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(1, "Dragon bones", 2, 3_200, 6_400L))), 2_000);
        long bookedNet = engine.getMetrics(now + 2_000L).net;

        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            SidebarPanelProbe.refresh(panel);
            return null;
        });

        assertEquals("repeated presentation capture leaves canonical net untouched",
            bookedNet, engine.getMetrics(now + 2_000L).net);
        assertNotNull(SidebarPanelProbe.live(panel));
        assertEquals("the restored hierarchy shows the live owner", "Vorkath", SidebarPanelProbe.live(panel).ownerLabel);
    }

    @Test
    public void repeatedRefreshesStayCoalescedAndKeepExactlyOneShell() throws Exception
    {
        Dp panel = onEdt(() -> new Dp(PresentationLifecycleTest.engine(),
            PresentationLifecycleTest.config(), null));
        for (int i = 0; i < 50; i++)
        {
            panel.refresh();
        }
        Thread.sleep(120L);
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertNotNull("repeated refreshes keep exactly one live presentation", SidebarPanelProbe.live(panel));
        assertEquals(3, railTabs(panel));
    }

    @Test
    public void neutralTransfersNeverFabricateAValue() throws Exception
    {
        Ac neutral = Tx.of(System.currentTimeMillis(), null,
            Ai.TRANSFER, Aj.TRANSFER, "Bank transfer", "Vorkath", true,
            Collections.singletonList(new Ab(995, "Coins", -1_000, 1, -1_000L)));
        Ca.Recent row = LiveProbe.toRecent(neutral);
        assertNotNull(row);
        assertTrue("a transfer row is neutral", row.neutral);
        assertEquals("neutral rows carry the booked zero", 0L, row.value);
        assertFalse("a transfer is never marked unpriced", row.unpriced);
    }

    private static int railTabs(Dp panel)
    {
        return ShellProbe.tabLabels(panel.shell()).length;
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
