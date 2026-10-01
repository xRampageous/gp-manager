package com.gpmanager;

import java.util.Collections;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Owner 2026-10-01 (F13): the empty Live card names what starts tracking, per mode. */
public class FirstUseCopyTest
{
    @Test
    public void theEmptyCardNamesWhatStartsTracking() throws Exception
    {
        SidebarPanel automatic = panel(new GpManagerConfig() {});
        refresh(automatic);
        assertEquals("Tracking starts with your first gameplay", automatic.live.recent.emptyText);
        assertTrue("the Grind action is offered in free play", automatic.live.startGrind.isVisible());

        SidebarPanel manual = panel(new GpManagerConfig()
        {
            @Override public boolean autoStartSession() { return false; }
        });
        refresh(manual);
        assertEquals("Press Grind to start tracking", manual.live.recent.emptyText);

        GpManagerConfig namedConfig = new GpManagerConfig() {};
        Engine namedEngine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            namedConfig);
        namedEngine.startCustomSession("Vorkath", SessionMode.GENERAL, 1_700_000_000_000L);
        SidebarPanel named = onEdt(() -> new SidebarPanel(namedEngine, namedConfig, null));
        refresh(named);
        assertEquals("Nothing booked yet", named.live.recent.emptyText);

        SidebarPanel out = panel(new GpManagerConfig() {});
        out.bindLoggedIn(() -> false);
        refresh(out);
        assertEquals("Tracking waits until you log back in", out.live.recent.emptyText);
    }

    private static SidebarPanel panel(GpManagerConfig config) throws Exception
    {
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        return onEdt(() -> new SidebarPanel(engine, config, null));
    }

    private static void refresh(SidebarPanel panel) throws Exception
    {
        onEdt(() ->
        {
            SidebarPanelProbe.refresh(panel);
            return null;
        });
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
