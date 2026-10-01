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
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW);
        engine.pauseForLifecycle(NOW + 1_000L);

        LiveSnapshot hop = LiveSnapshot.capture(engine, NOW + 2_000L, context(false, true));
        assertTrue("the lifecycle pause still holds", hop.loggedOut);
        assertEquals("RECONNECTING", LivePage.wordOf(hop));
        assertTrue(LivePage.statusTooltip(hop).startsWith("Reconnecting"));

        LiveSnapshot out = LiveSnapshot.capture(engine, NOW + 2_000L, context(true, false));
        assertEquals("LOGGED OUT", LivePage.wordOf(out));

        engine.resume(NOW + 3_000L, PauseReason.LIFECYCLE);
        assertEquals("the resume cue takes over", "CALIBRATING",
            LivePage.wordOf(LiveSnapshot.capture(engine, NOW + 3_100L, context(false, false))));
        assertEquals("then tracking is plainly live", "",
            LivePage.wordOf(LiveSnapshot.capture(engine, NOW + 6_000L, context(false, false))));
    }

    @Test
    public void aManualPauseSurvivesLifecycleResumeAndKeepsItsWord() throws Exception
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW);
        engine.togglePause(NOW + 1_000L);
        engine.resume(NOW + 2_000L, PauseReason.LIFECYCLE);
        assertTrue("a manual pause never becomes an automatic resume",
            engine.getActiveSession().paused);
        assertEquals("PAUSED",
            LivePage.wordOf(LiveSnapshot.capture(engine, NOW + 2_100L, context(false, false))));
    }

    @Test
    public void theSidebarReadsTheHopSupplier() throws Exception
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW);
        engine.pauseForLifecycle(NOW + 1_000L);
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, new GpManagerConfig() {}, null));
        onEdt(() ->
        {
            panel.setHoppingSource(() -> true);
            SidebarPanelProbe.refresh(panel);
            return null;
        });
        assertEquals("RECONNECTING", LivePage.wordOf(SidebarPanelProbe.live(panel)));
    }

    private static LiveContext context(boolean offline, boolean hopping)
    {
        return new LiveContext(false, null, 0L, PvpState.NONE, "", offline, "", hopping);
    }

    private static Engine engine()
    {
        return new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
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
