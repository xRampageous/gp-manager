package com.gpmanager;

import java.util.Collections;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Owner 2026-10-01 (F23): a hop says Reconnecting; a logout says Logged out. */
public class LifecycleWordTest
{
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void aHopSaysReconnectingWhileALogoutSaysLoggedOut() throws Exception
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        engine.acu(NOW + 1_000L);

        Ca hop = Ca.capture(engine, NOW + 2_000L, context(false, true));
        assertTrue("the lifecycle pause still holds", hop.loggedOut);
        assertEquals("RECONNECTING", LivePage.wordOf(hop));
        assertTrue(LivePage.ajn(hop).startsWith("Reconnecting"));

        Ca out = Ca.capture(engine, NOW + 2_000L, context(true, false));
        assertEquals("LOGGED OUT", LivePage.wordOf(out));

        engine.resume(NOW + 3_000L, Ed.LIFECYCLE);
        assertEquals("the resume cue takes over", "CALIBRATING",
            LivePage.wordOf(Ca.capture(engine, NOW + 3_100L, context(false, false))));
        assertEquals("then tracking is plainly live", "",
            LivePage.wordOf(Ca.capture(engine, NOW + 6_000L, context(false, false))));
    }

    @Test
    public void aManualPauseSurvivesLifecycleResumeAndKeepsItsWord() throws Exception
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        engine.togglePause(NOW + 1_000L);
        engine.resume(NOW + 2_000L, Ed.LIFECYCLE);
        assertTrue("a manual pause never becomes an automatic resume",
            engine.getActiveSession().paused);
        assertEquals("PAUSED",
            LivePage.wordOf(Ca.capture(engine, NOW + 2_100L, context(false, false))));
    }

    @Test
    public void theSidebarReadsTheHopSupplier() throws Exception
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        engine.acu(NOW + 1_000L);
        Dp panel = onEdt(() -> new Dp(engine, new GpManagerConfig() {}, null));
        onEdt(() ->
        {
            panel.axq(() -> true);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertEquals("RECONNECTING", LivePage.wordOf(SidebarPanelProbe.live(panel)));
    }

    private static Dz context(boolean offline, boolean hopping)
    {
        return new Dz(false, null, 0L, Bo.NONE, "", offline, "", hopping);
    }

    private static Am engine()
    {
        return new Am(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
    }

    @SuppressWarnings("unchecked")
    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                result[0] = callable.call();
            }
            catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
        });
        return (T) result[0];
    }
}
