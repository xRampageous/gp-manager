package com.gpmanager;

import java.awt.FontMetrics;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The development-build footer is tiny, honest, width-safe and absent in release builds. */
public class DevMarkerPresentationTest
{
    @Test
    public void developmentBadgeShowsVersionAndBuildIdentityAtRealWidth() throws Exception
    {
        Dp panel = onEdt(() -> new Dp(PresentationLifecycleTest.engine(),
            PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            DevBadge.apply(panel, BuildInfo.of("0.8.0", "0.8.0-dev", "105",
                "2026-09-23T01:24:37Z", "2929696", "1.12.39", true));
            return null;
        });

        JLabel badge = onEdt(() -> SidebarPanelProbe.buildBadge(panel));
        assertNotNull("development builds must identify themselves", badge);
        assertEquals("DEV \u00b7 0.8.0 \u00b7 01:24Z", badge.getText());
        assertNotNull(ShellProbe.badge(panel.shell()));
        assertTrue("full identity belongs in the tooltip",
            badge.getToolTipText().contains("Version: 0.8.0-dev"));
        assertTrue(badge.getToolTipText().contains("Schema: 105"));
        assertTrue(badge.getToolTipText().contains("Built: 2026-09-23 01:24:37 UTC"));
        assertNotNull(badge.getAccessibleContext().getAccessibleDescription());
        FontMetrics metrics = new JLabel().getFontMetrics(badge.getFont());
        assertTrue("the badge must fit the production sidebar width",
            metrics.stringWidth(badge.getText()) <= 225 - 24);
    }

    @Test
    public void releaseBuildLeavesTheFooterCollapsed() throws Exception
    {
        Dp panel = onEdt(() -> new Dp(PresentationLifecycleTest.engine(),
            PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            DevBadge.apply(panel, BuildInfo.of("1.0.0", "1.0.0", "104",
                "2026-09-23T01:24:37Z", "", "1.12.39", false));
            return null;
        });

        assertNull("release builds must not claim to be development", SidebarPanelProbe.buildBadge(panel));
        assertNull(ShellProbe.badge(panel.shell()));
    }

    @Test
    public void missingMetadataDegradesToTheBareDevelopmentMarker() throws Exception
    {
        Dp panel = onEdt(() -> new Dp(PresentationLifecycleTest.engine(),
            PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            DevBadge.apply(panel, null);
            return null;
        });

        JLabel badge = onEdt(() -> SidebarPanelProbe.buildBadge(panel));
        assertNotNull("a broken optional resource must never hide the build", badge);
        assertEquals("DEV BUILD", badge.getText());
        assertTrue(badge.getToolTipText().contains("GP Manager Development Build"));
    }

    @Test
    public void buildBadgeNeverTouchesSessionTruth() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(new Ac(
            now + 1L, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true,
            java.util.Arrays.asList(new Ab(385, "Shark", -1L, 950, -950L)),
            Bd.CONFIRMED, "Exact cost fixture", null), 2_000);
        long net = engine.getMetrics(now + 2L).net;
        int receipts = engine.getActiveSession().getTransactions().size();

        Dp panel = onEdt(() -> new Dp(engine, PresentationLifecycleTest.config(), null));
        onEdt(() ->
        {
            DevBadge.apply(panel, BuildInfo.of("0.8.0", "0.8.0-dev", "105",
                "2026-09-23T01:24:37Z", "2929696", "1.12.39", true));
            SidebarPanelProbe.refresh(panel);
            return null;
        });

        assertNotNull(SidebarPanelProbe.buildBadge(panel));
        assertEquals("the build marker is presentation only", net, engine.getMetrics(now + 2L).net);
        assertEquals(receipts, engine.getActiveSession().getTransactions().size());
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        if (SwingUtilities.isEventDispatchThread()) return callable.call();
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
        {
            try { value.set(callable.call()); }
            catch (Throwable ex) { failure.set(ex); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return value.get();
    }
}
