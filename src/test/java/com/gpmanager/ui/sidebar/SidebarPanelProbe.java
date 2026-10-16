package com.gpmanager;

import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

/** Test-side access to SidebarPanel state; moved out of production to keep the plugin small. */
public final class SidebarPanelProbe
{
    private SidebarPanelProbe()
    {
    }

    public static void bindSprites(SidebarPanel p, @Nullable java.util.function.Function<Integer, BufferedImage> source)
    {
        p.spriteOverride = source;
    }

    public static JLabel buildBadge(SidebarPanel p)
    {
        return ShellProbe.badge(p.shell) instanceof JLabel ? (JLabel) ShellProbe.badge(p.shell) : null;
    }

    public static void refresh(SidebarPanel p)
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            p.active = true;
            p.refreshNow();
        }
        else
        {
            p.refresh();
        }
    }

    private static final java.util.Map<SidebarPanel, javax.swing.JFrame> FRAMES = new java.util.WeakHashMap<>();

    public static void frame(SidebarPanel p, javax.swing.JFrame frame)
    {
        FRAMES.put(p, frame);
    }

    public static void disposeFrame(SidebarPanel p)
    {
        javax.swing.JFrame frame = FRAMES.remove(p);
        if (frame != null)
        {
            frame.dispose();
        }
    }

    public static LiveSnapshot live(SidebarPanel p)
    {
        return p.lastSnapshot;
    }

    public static LivePage livePage(SidebarPanel p)
    {
        return p.live;
    }

    public static void openGrindsDetail(SidebarPanel p, @javax.annotation.Nullable String sessionId)
    {
        p.grindsDetailId = sessionId;
        p.shell.show(Shell.GRINDS);
        p.refresh();
    }

    public static JPopupMenu dataMenu(SidebarPanel p)
    {
        return new GrindsController(p).dataMenu();
    }

    public static LedgerPage ledgerPage(SidebarPanel p)
    {
        return p.ledger;
    }

    public static void openLedger(SidebarPanel p, LedgerData.Entry entry)
    {
        p.openEntry(entry);
    }
}
