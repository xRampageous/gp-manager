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

/** Test-side access to Dp state; moved out of production to keep the plugin small. */
public final class SidebarPanelProbe
{
    private SidebarPanelProbe()
    {
    }

    public static void bindSprites(Dp p, @Nullable java.util.function.Function<Integer, BufferedImage> source)
    {
        p.spriteOverride = source;
    }

    public static JLabel buildBadge(Dp p)
    {
        return ShellProbe.badge(p.shell) instanceof JLabel ? (JLabel) ShellProbe.badge(p.shell) : null;
    }

    public static void refresh(Dp p)
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            p.active = true;
            p.avz();
        }
        else
        {
            p.refresh();
        }
    }

    private static final java.util.Map<Dp, javax.swing.JFrame> FRAMES = new java.util.WeakHashMap<>();

    public static void frame(Dp p, javax.swing.JFrame frame)
    {
        FRAMES.put(p, frame);
    }

    public static void disposeFrame(Dp p)
    {
        javax.swing.JFrame frame = FRAMES.remove(p);
        if (frame != null)
        {
            frame.dispose();
        }
    }

    public static Ca live(Dp p)
    {
        return p.lastSnapshot;
    }

    public static LivePage livePage(Dp p)
    {
        return p.live;
    }

    public static void openGrindsDetail(Dp p, @javax.annotation.Nullable String sessionId)
    {
        p.grindsDetailId = sessionId;
        p.shell.show(Shell.GRINDS);
        p.refresh();
    }

    public static JPanel startWithChangesForm(Dp p, String grindId)
    {
        SavedState.Ap definition = p.engine.um(grindId);
        return definition == null ? null : new GrindsController(p).nb(definition);
    }

    public static JPopupMenu dataMenu(Dp p)
    {
        return new GrindsController(p).mv();
    }

    public static LedgerPage ledgerPage(Dp p)
    {
        return p.ledger;
    }

    public static void openLedger(Dp p, Ao.Entry entry)
    {
        p.acg(entry);
    }
}
