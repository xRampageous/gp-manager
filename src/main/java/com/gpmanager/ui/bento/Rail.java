package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

/**
 * Row 1: five equal tabs, icon glyph over a 9 pt label, accent-soft fill when selected,
 * optional amber dot (Tools while a decision is pending). Keyboard: Left/Right/Home/End.
 */
public final class Rail extends Painted
{
    public static final class Tab
    {
        public final String id;
        public final String glyph;
        public final String label;

        public Tab(String id, String glyph, String label)
        {
            this.id = id;
            this.glyph = glyph;
            this.label = label;
        }
    }

    private static final int HEIGHT = 36;

    private final List<Tab> tabs = new ArrayList<>();
    private final List<String> dotted = new ArrayList<>();
    private int selected;
    private int hover = -1;
    private Consumer<String> listener = id -> { };

    public Rail(List<Tab> tabs)
    {
        super(AccessibleRole.PAGE_TAB_LIST);
        this.tabs.addAll(tabs);
        setOpaque(false);
        setFocusable(true);
        MouseAdapter mouse = new MouseAdapter()
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

            @Override
            public void mouseMoved(MouseEvent e)
            {
                int index = indexAt(e.getX());
                if (index != hover)
                {
                    hover = index;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                hover = -1;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                switch (e.getKeyCode())
                {
                    case KeyEvent.VK_LEFT:
                        select(Math.max(0, selected - 1), true);
                        break;
                    case KeyEvent.VK_RIGHT:
                        select(Math.min(tabs.size() - 1, selected + 1), true);
                        break;
                    case KeyEvent.VK_HOME:
                        select(0, true);
                        break;
                    case KeyEvent.VK_END:
                        select(tabs.size() - 1, true);
                        break;
                    default:
                        return;
                }
                e.consume();
            }
        });
        getAccessibleContext().setAccessibleName("Navigation");
    }

    public void onSelect(Consumer<String> consumer)
    {
        listener = consumer == null ? id -> { } : consumer;
    }

    public String selectedId()
    {
        return tabs.get(selected).id;
    }

    public void select(String id)
    {
        for (int i = 0; i < tabs.size(); i++)
        {
            if (tabs.get(i).id.equals(id))
            {
                select(i, false);
                return;
            }
        }
    }

    private void select(int index, boolean notify)
    {
        if (index < 0 || index >= tabs.size())
        {
            return;
        }
        boolean changed = index != selected;
        selected = index;
        repaint();
        if (changed && notify)
        {
            listener.accept(tabs.get(index).id);
        }
    }

    /** Show or hide the attention dot on a tab. */
    public void setDot(String id, boolean on)
    {
        dotted.remove(id);
        if (on)
        {
            dotted.add(id);
        }
        repaint();
    }

    private int indexAt(int x)
    {
        int count = tabs.size();
        if (count == 0 || getWidth() <= 0)
        {
            return -1;
        }
        int slot = getWidth() / count;
        int index = x / Math.max(1, slot);
        return index >= 0 && index < count ? index : -1;
    }

    @Override
    public Dimension getPreferredSize()
    {
        return new Dimension(BentoTheme.CONTENT_WIDTH, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    /** The rail never yields height to a tall page below it. */
    @Override
    public Dimension getMinimumSize()
    {
        return new Dimension(0, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            BentoTheme.quality(g2);
            int count = tabs.size();
            int slot = getWidth() / Math.max(1, count);
            int gap = 3;
            for (int i = 0; i < count; i++)
            {
                Tab tab = tabs.get(i);
                int x = i * slot + gap / 2;
                int w = slot - gap;
                boolean on = i == selected;
                if (on || i == hover)
                {
                    g2.setColor(on ? BentoTheme.accentSoft() : BentoTheme.ALT);
                    g2.fillRoundRect(x, 1, w, HEIGHT - 3, 9, 9);
                }
                Color fg = on ? BentoTheme.accentColor() : BentoTheme.DIM;
                g2.setColor(fg);
                g2.setFont(BentoTheme.symbol(15f));
                FontMetrics gm = g2.getFontMetrics();
                int gx = x + (w - gm.stringWidth(tab.glyph)) / 2;
                g2.drawString(tab.glyph, gx, 16);
                g2.setFont(BentoTheme.font(java.awt.Font.PLAIN, 10f));
                FontMetrics lm = g2.getFontMetrics();
                int lx = x + (w - lm.stringWidth(tab.label)) / 2;
                g2.drawString(tab.label, lx, HEIGHT - 6);
                if (dotted.contains(tab.id))
                {
                    g2.setColor(BentoTheme.WARN);
                    g2.fillOval(x + w - 11, 4, 6, 6);
                    g2.setColor(BentoTheme.BG);
                    g2.drawOval(x + w - 11, 4, 6, 6);
                }
                if (on && hasFocus())
                {
                    g2.setColor(BentoTheme.withAlpha(BentoTheme.INFO, 180));
                    g2.drawRoundRect(x, 1, w - 1, HEIGHT - 4, 9, 9);
                }
            }
        }
        finally
        {
            g2.dispose();
        }
    }
}
