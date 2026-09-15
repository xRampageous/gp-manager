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
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Small painted controls: pill button, icon button, segmented switch. All keyboard-
 * operable (Space/Enter) and expose accessible names.
 */
public final class Controls
{
    private Controls()
    {
    }

    /** Rounded text button; {@link Kind} picks fill/border. */
    public static final class Button extends Painted
    {
        public enum Kind
        {
            DEFAULT, PRIMARY, STOP, RESUME, GHOST
        }

        private String text;
        private Kind kind;
        private boolean hover;
        private Runnable action = () -> { };

        public Button(String text, Kind kind)
        {
            super(AccessibleRole.PUSH_BUTTON);
            this.text = text == null ? "" : text;
            this.kind = kind == null ? Kind.DEFAULT : kind;
            setFocusable(true);
            getAccessibleContext().setAccessibleName(this.text);
            MouseAdapter mouse = new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    if (SwingUtilities.isLeftMouseButton(e) && isEnabled())
                    {
                        action.run();
                    }
                }

                @Override
                public void mouseEntered(MouseEvent e)
                {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e)
                {
                    hover = false;
                    repaint();
                }
            };
            addMouseListener(mouse);
            addKeyListener(new java.awt.event.KeyAdapter()
            {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e)
                {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE
                        || e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER)
                    {
                        action.run();
                        e.consume();
                    }
                }
            });
        }

        public Button onClick(Runnable runnable)
        {
            action = runnable == null ? () -> { } : runnable;
            return this;
        }

        public void setText(String value)
        {
            text = value == null ? "" : value;
            getAccessibleContext().setAccessibleName(text);
            revalidate();
            repaint();
        }

        public void setKind(Kind value)
        {
            kind = value == null ? Kind.DEFAULT : value;
            repaint();
        }

        public String getText()
        {
            return text;
        }

        @Override
        public Dimension getPreferredSize()
        {
            FontMetrics fm = getFontMetrics(BentoTheme.fontFor(text, BentoTheme.secondary()));
            return new Dimension(fm.stringWidth(text) + 18, fm.getHeight() + 10);
        }

        @Override
        public Dimension getMinimumSize()
        {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize()
        {
            Dimension max = super.getMaximumSize();
            return new Dimension(max.width, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try
            {
                BentoTheme.quality(g2);
                Color fill;
                Color border;
                Color fg = BentoTheme.TEXT;
                switch (kind)
                {
                    case PRIMARY:
                        fill = BentoTheme.accentColor();
                        border = BentoTheme.accentColor();
                        fg = new Color(0x1a0d08);
                        break;
                    case STOP:
                        fill = BentoTheme.NEGATIVE_SURFACE;
                        border = new Color(0x6a2a2a);
                        break;
                    case RESUME:
                        fill = BentoTheme.POSITIVE_SURFACE;
                        border = new Color(0x2f6a3f);
                        break;
                    case GHOST:
                        fill = null;
                        border = null;
                        fg = BentoTheme.MUTED;
                        break;
                    default:
                        fill = BentoTheme.ALT;
                        border = BentoTheme.BORDER;
                }
                if (hover && fill != null)
                {
                    fill = fill.brighter();
                }
                if (fill != null)
                {
                    g2.setColor(fill);
                    g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
                if (border != null)
                {
                    g2.setColor(hasFocus() ? BentoTheme.INFO : border);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
                else if (hasFocus())
                {
                    g2.setColor(BentoTheme.INFO);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
                g2.setFont(BentoTheme.fontFor(text, kind == Kind.PRIMARY ? BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary) : BentoTheme.secondary()));
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(isEnabled() ? fg : BentoTheme.DIM);
                g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            }
            finally
            {
                g2.dispose();
            }
        }
    }

    /** Segmented switch (General | PvP | Wealth; 7d | 30d | All). */
    public static final class Segmented extends Painted
    {
        private final List<String> options = new ArrayList<>();
        private int selected;
        @Nullable
        private Color onFill;
        private Consumer<Integer> listener = i -> { };

        /** Full-width tab strip: equal segments, body-weight labels, a raised pill on the selection. */
        private boolean fill;

        public Segmented fill(boolean value)
        {
            fill = value;
            revalidate();
            repaint();
            return this;
        }

        public Segmented(List<String> options)
        {
            super(AccessibleRole.RADIO_BUTTON);
            this.options.addAll(options);
            setFocusable(true);
            getAccessibleContext().setAccessibleName(String.join(" / ", options));
            addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    int index = indexAt(e.getX());
                    if (index >= 0)
                    {
                        select(index, true);
                    }
                    requestFocusInWindow();
                }
            });
            addKeyListener(new java.awt.event.KeyAdapter()
            {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e)
                {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_LEFT)
                    {
                        select(Math.max(0, selected - 1), true);
                        e.consume();
                    }
                    else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_RIGHT)
                    {
                        select(Math.min(options.size() - 1, selected + 1), true);
                        e.consume();
                    }
                }
            });
        }

        public Segmented onSelect(Consumer<Integer> consumer)
        {
            listener = consumer == null ? i -> { } : consumer;
            return this;
        }

        public Segmented selectedFill(@Nullable Color color)
        {
            onFill = color;
            repaint();
            return this;
        }

        public int selectedIndex()
        {
            return selected;
        }

        public void select(int index, boolean notify)
        {
            if (index < 0 || index >= options.size())
            {
                return;
            }
            boolean changed = index != selected;
            selected = index;
            repaint();
            if (changed && notify)
            {
                listener.accept(index);
            }
        }

        private int[] widths()
        {
            int[] w = new int[options.size()];
            if (fill && getWidth() > 0)
            {
                int inner = getWidth() - 2;
                for (int i = 0; i < w.length; i++)
                {
                    w[i] = inner / w.length + (i < inner % w.length ? 1 : 0);
                }
                return w;
            }
            FontMetrics fm = getFontMetrics(labelFont());
            for (int i = 0; i < w.length; i++)
            {
                w[i] = fm.stringWidth(options.get(i)) + (fill ? 24 : 16);
            }
            return w;
        }

        private java.awt.Font labelFont()
        {
            return fill ? BentoTheme.bodyBold() : BentoTheme.secondary();
        }

        private int indexAt(int x)
        {
            int[] w = widths();
            int cursor = 0;
            for (int i = 0; i < w.length; i++)
            {
                cursor += w[i];
                if (x < cursor)
                {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public Dimension getPreferredSize()
        {
            FontMetrics fm = getFontMetrics(labelFont());
            if (fill)
            {
                return new Dimension(BentoTheme.MIN_CONTENT_WIDTH, fm.getHeight() + 14);
            }
            int total = 0;
            for (int w : widths())
            {
                total += w;
            }
            return new Dimension(total + 2, fm.getHeight() + 8);
        }

        @Override
        public Dimension getMaximumSize()
        {
            return fill ? new Dimension(Integer.MAX_VALUE, getPreferredSize().height) : getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try
            {
                BentoTheme.quality(g2);
                int h = getHeight();
                int radius = fill ? 10 : 8;
                if (fill)
                {
                    // A sunken track with a raised pill: reads as the page's tabs, not a small toggle.
                    g2.setColor(BentoTheme.SURFACE);
                    g2.fillRoundRect(0, 0, getWidth() - 1, h - 1, radius, radius);
                }
                g2.setColor(hasFocus() ? BentoTheme.INFO : BentoTheme.BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, h - 1, radius, radius);
                g2.setFont(labelFont());
                FontMetrics fm = g2.getFontMetrics();
                int[] w = widths();
                int x = 1;
                for (int i = 0; i < options.size(); i++)
                {
                    boolean on = i == selected;
                    if (on)
                    {
                        g2.setColor(onFill == null ? BentoTheme.accentSoft() : onFill);
                        g2.fillRoundRect(x + (fill ? 2 : 0), fill ? 3 : 1, w[i] - (fill ? 4 : 0), h - (fill ? 7 : 3), radius - 1, radius - 1);
                    }
                    g2.setColor(on ? (fill && onFill == null ? BentoTheme.accentColor() : BentoTheme.TEXT) : BentoTheme.MUTED);
                    int tx = fill ? x + (w[i] - fm.stringWidth(options.get(i))) / 2 : x + 8;
                    g2.drawString(options.get(i), tx, (h + fm.getAscent() - fm.getDescent()) / 2);
                    x += w[i];
                }
            }
            finally
            {
                g2.dispose();
            }
        }
    }

    /** 20×20 glyph-only button (search, pop-out, view toggles). */
    public static final class IconButton extends Painted
    {
        private final String glyph;
        private boolean on;
        private boolean bordered;
        private Runnable action = () -> { };

        public IconButton(String glyph, String accessibleName, boolean bordered)
        {
            super(AccessibleRole.PUSH_BUTTON);
            this.glyph = glyph;
            this.bordered = bordered;
            setFocusable(true);
            getAccessibleContext().setAccessibleName(accessibleName);
            setToolTipText(accessibleName);
            addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    if (isEnabled())
                    {
                        action.run();
                    }
                }
            });
            addKeyListener(new java.awt.event.KeyAdapter()
            {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e)
                {
                    if (isEnabled() && (e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE || e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER))
                    {
                        action.run();
                        e.consume();
                    }
                }
            });
        }

        public IconButton onClick(Runnable runnable)
        {
            action = runnable == null ? () -> { } : runnable;
            return this;
        }

        public void setOn(boolean value)
        {
            on = value;
            repaint();
        }

        @Override
        public Dimension getPreferredSize()
        {
            return new Dimension(bordered ? 26 : 20, bordered ? 24 : 20);
        }

        @Override
        public Dimension getMaximumSize()
        {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try
            {
                BentoTheme.quality(g2);
                if (on)
                {
                    g2.setColor(BentoTheme.accentSoft());
                    g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 7, 7);
                }
                if (bordered || hasFocus())
                {
                    g2.setColor(hasFocus() ? BentoTheme.INFO : on ? BentoTheme.accentColor() : BentoTheme.BORDER);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 7, 7);
                }
                g2.setFont(BentoTheme.fontFor(glyph, BentoTheme.font(java.awt.Font.PLAIN, 12f)));
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(!isEnabled() ? BentoTheme.DIM : on ? BentoTheme.accentColor() : BentoTheme.MUTED);
                g2.drawString(glyph, (getWidth() - fm.stringWidth(glyph)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            }
            finally
            {
                g2.dispose();
            }
        }
    }
}
