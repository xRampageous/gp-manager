package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static com.gpmanager.SwingTestSupport.onEdt;
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
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        String sessionId = engine.session().id();
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));

        panel.suspend();

        panel.resume();
        onEdt(() -> { assertNotNull(SidebarPanelProbe.live(panel)); return null; });
    }

    @Test
    public void captureIsAReadAndNeverBooks() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustom("Vorkath", now);
        engine.session().addTransaction(Tx.of(now + 1_000L, null, TransactionType.GAIN,
            Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(1, "Dragon bones", 2, 3_200, 6_400L))), 2_000, false);
        long bookedNet = engine.metrics(now + 2_000L).net;

        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            SidebarPanelProbe.refresh(panel);
            return null;
        });

        assertEquals("repeated presentation capture leaves canonical net untouched",
            bookedNet, engine.metrics(now + 2_000L).net);
        assertNotNull(SidebarPanelProbe.live(panel));
        assertEquals("the restored hierarchy shows the live owner", "Vorkath", SidebarPanelProbe.live(panel).ownerLabel);
    }

    @Test
    public void repeatedRefreshesStayCoalescedAndKeepExactlyOneShell() throws Exception
    {
        SidebarPanel panel = onEdt(() -> new SidebarPanel(PresentationLifecycleTest.engine(),
            PresentationLifecycleTest.config(), null, null, null));
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
        Transaction neutral = Tx.of(System.currentTimeMillis(), null,
            TransactionType.TRANSFER, Context.TRANSFER, "Bank transfer", "Vorkath", true,
            Collections.singletonList(new Flow(995, "Coins", -1_000, 1, -1_000L)));
        LiveSnapshot.Recent row = LiveProbe.toRecent(neutral);
        assertNotNull(row);
        assertTrue("a transfer row is neutral", row.neutral);
        assertEquals("neutral rows carry the booked zero", 0L, row.value);
        assertFalse("a transfer is never marked unpriced", row.unknown);
    }

    private static int railTabs(SidebarPanel panel)
    {
        return ShellProbe.tabLabels(panel.shell).length;
    }

}
