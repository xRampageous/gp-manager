package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * Overlay that stacks at most two toasts at the bottom of the panel. Undo toasts count
 * down; informational toasts dismiss on tap. Lives in the shell's layered pane so it never
 * takes layout space from the page.
 */
public final class ToastHost extends JPanel
{
    public static final int MAX_VISIBLE = 2;

    public static final class Toast extends Painted
    {
        private final String glyph;
        @Nullable
        private java.awt.image.BufferedImage image;
        private final String title;
        private final String detail;
        @Nullable
        private final String action;
        private final Color border;
        private final long expiresAt;
        private final Runnable onAction;
        private final ToastHost host;

        Toast(ToastHost host, String glyph, String title, String detail, @Nullable String action, Color border,
            long lifetimeMillis, @Nullable Runnable onAction)
        {
            super(AccessibleRole.ALERT);
            this.host = host;
            this.glyph = glyph;
            this.title = title;
            this.detail = detail;
            this.action = action;
            this.border = border;
            this.expiresAt = lifetimeMillis <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + lifetimeMillis;
            this.onAction = onAction == null ? () -> { } : onAction;
            addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    if (action != null && e.getX() > getWidth() - 70)
                    {
                        Toast.this.onAction.run();
                    }
                    host.dismiss(Toast.this);
                }
            });
        }

        boolean expired(long now)
        {
            return now >= expiresAt;
        }

        String detailText(long now)
        {
            if (expiresAt == Long.MAX_VALUE)
            {
                return detail;
            }
            long left = Math.max(0L, (expiresAt - now + 999L) / 1000L);
            return detail.isEmpty() ? "undo in " + left + "s" : detail + " · " + left + "s";
        }

        @Override
        public Dimension getPreferredSize()
        {
            FontMetrics fm = host.getFontMetrics(BentoTheme.secondary());
            return new Dimension(BentoTheme.CONTENT_WIDTH - 4, fm.getHeight() * 2 + 12);
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try
            {
                BentoTheme.quality(g2);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(BentoTheme.withAlpha(Color.BLACK, 120));
                g2.fillRoundRect(2, 3, w - 3, h - 2, 10, 10);
                g2.setColor(BentoTheme.ALT);
                g2.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
                g2.setColor(border);
                g2.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);
                // A toast shows real art (an item sprite) or nothing on its left; glyphs are not icons here.
                if (image != null)
                {
                    int iw = Math.min(18, image.getWidth());
                    int ih = Math.min(18, image.getHeight());
                    g2.drawImage(image, 6 + (18 - iw) / 2, h / 2 - ih / 2, iw, ih, null);
                }
                int x = image != null ? 30 : 10;
                g2.setFont(BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary));
                FontMetrics bold = g2.getFontMetrics();
                g2.setColor(BentoTheme.TEXT);
                g2.drawString(title, x, 6 + bold.getAscent());
                g2.setFont(BentoTheme.font(java.awt.Font.PLAIN, BentoTheme.density().micro + 1));
                FontMetrics small = g2.getFontMetrics();
                g2.setColor(BentoTheme.MUTED);
                g2.drawString(detailText(System.currentTimeMillis()), x, 6 + bold.getHeight() + small.getAscent() - 1);
                if (action != null)
                {
                    g2.setFont(BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary));
                    FontMetrics af = g2.getFontMetrics();
                    g2.setColor(BentoTheme.accentColor());
                    g2.drawString(action, w - 10 - af.stringWidth(action), h / 2 + (af.getAscent() - af.getDescent()) / 2);
                }
            }
            finally
            {
                g2.dispose();
            }
        }
    }

    private final List<Toast> toasts = new ArrayList<>();
    private final Timer timer = new Timer(500, e -> tick());

    public ToastHost()
    {
        setLayout(null);
        setOpaque(false);
        timer.setRepeats(true);
    }

    public Toast undo(String title, String detail, Runnable onUndo, int seconds)
    {
        return show("↶", title, detail, "Undo", BentoTheme.accentColor(), seconds * 1000L, onUndo);
    }

    /** Toasts dismiss themselves: five seconds, eight when there is an action to click. */
    private static long lifetimeFor(@Nullable String action)
    {
        return action == null ? 5_000L : 8_000L;
    }

    public Toast info(String glyph, String title, String detail, @Nullable String action, Color border,
        @Nullable Runnable onAction)
    {
        return show(glyph, title, detail, action, border, lifetimeFor(action), onAction);
    }

    /** As {@link #info}, with a sprite instead of the glyph. */
    public Toast info(@Nullable java.awt.image.BufferedImage image, String glyph, String title, String detail, @Nullable String action,
        Color border, @Nullable Runnable onAction)
    {
        Toast toast = show(glyph, title, detail, action, border, lifetimeFor(action), onAction);
        toast.image = image;
        toast.repaint();
        return toast;
    }

    private Toast show(String glyph, String title, String detail, @Nullable String action, Color border,
        long lifetime, @Nullable Runnable onAction)
    {
        Toast toast = new Toast(this, glyph, title, detail, action, border, lifetime, onAction);
        toasts.add(0, toast);
        while (toasts.size() > MAX_VISIBLE)
        {
            Toast dropped = toasts.remove(toasts.size() - 1);
            remove(dropped);
        }
        add(toast);
        relayout();
        if (!timer.isRunning())
        {
            timer.start();
        }
        return toast;
    }

    public void dismiss(Toast toast)
    {
        toasts.remove(toast);
        remove(toast);
        relayout();
    }

    public int visibleCount()
    {
        return toasts.size();
    }

    private void tick()
    {
        long now = System.currentTimeMillis();
        List<Toast> gone = new ArrayList<>();
        for (Toast t : toasts)
        {
            if (t.expired(now))
            {
                gone.add(t);
            }
        }
        for (Toast t : gone)
        {
            dismiss(t);
        }
        if (toasts.isEmpty())
        {
            timer.stop();
        }
        repaint();
    }

    void relayout()
    {
        int w = getWidth();
        int y = getHeight() - 8;
        for (Toast t : toasts)
        {
            Dimension size = t.getPreferredSize();
            y -= size.height;
            t.setBounds(2, y, Math.max(40, w - 4 - BentoTheme.SCROLLBAR_WIDTH), size.height);
            y -= 4;
        }
        revalidate();
        repaint();
    }

    @Override
    public void doLayout()
    {
        relayout();
    }

    @Override
    public boolean contains(int x, int y)
    {
        // Only toasts catch the mouse; the rest of the overlay is transparent to clicks.
        for (Toast t : toasts)
        {
            if (t.getBounds().contains(x, y))
            {
                return true;
            }
        }
        return false;
    }
}
