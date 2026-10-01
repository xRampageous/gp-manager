package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.Timer;

/**
 * The tiny DEV build footer, attached by the dev launcher and the panel preview only. It lives
 * under src/test so the plugin itself carries no build-identity code; release builds never show it.
 * Presentation only: never financial, never persisted and never owner-scoped.
 */
public final class DevBadge
{
    private DevBadge()
    {
    }

    /** Development builds show the DEV marker; release builds leave the footer collapsed. */
    public static void apply(SidebarPanel panel, @Nullable BuildInfo info)
    {
        BuildInfo resolved = info == null ? BuildInfo.fallback() : info;
        String badge = resolved.badgeText();
        JLabel label = null;
        if (!badge.isEmpty())
        {
            label = Kit.label(badge, Kit.small(), Kit.OFF);
            label.setToolTipText(resolved.tooltipText());
            label.getAccessibleContext().setAccessibleName("Build identity");
            label.getAccessibleContext().setAccessibleDescription(resolved.tooltipText());
        }
        ShellProbe.setBadge(panel.shell, label);
    }

    /**
     * Dev launcher: RuneLite adds the sidebar panel when it is first opened (and again after a
     * plugin restart), so look for unbadged GP Manager panels once a second.
     */
    public static void attachWhenShown(BuildInfo info)
    {
        if (info.badgeText().isEmpty())
        {
            return;
        }
        new Timer(1_000, e ->
        {
            for (Window window : Window.getWindows())
            {
                badgeAll(window, info);
            }
        }).start();
    }

    private static void badgeAll(Component component, BuildInfo info)
    {
        if (component instanceof SidebarPanel)
        {
            SidebarPanel panel = (SidebarPanel) component;
            if (ShellProbe.badge(panel.shell) == null)
            {
                apply(panel, info);
            }
        }
        else if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                badgeAll(child, info);
            }
        }
    }
}
