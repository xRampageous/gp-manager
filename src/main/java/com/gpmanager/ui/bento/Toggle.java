package com.gpmanager.ui.bento;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.accessibility.AccessibleRole;

/**
 * A switch (SIDEBAR_BENTO.md §13.1). Click or Space flips it; the listener receives the new
 * state. Disabled switches paint dimmed and ignore input.
 */
public final class Toggle extends Painted
{
    private static final int W = 34;
    private static final int H = 18;

    private boolean on;
    private Consumer<Boolean> listener = v -> { };

    public Toggle(String accessibleName, boolean initial)
    {
        super(AccessibleRole.CHECK_BOX);
        on = initial;
        setOpaque(false);
        setFocusable(true);
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName(accessibleName);
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                flip();
                requestFocusInWindow();
            }
        });
        addKeyListener(new java.awt.event.KeyAdapter()
        {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e)
            {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE || e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER)
                {
                    flip();
                    e.consume();
                }
            }
        });
    }

    private void flip()
    {
        if (!isEnabled())
        {
            return;
        }
        on = !on;
        repaint();
        listener.accept(on);
    }

    public Toggle onChange(Consumer<Boolean> consumer)
    {
        listener = consumer == null ? v -> { } : consumer;
        return this;
    }

    public boolean isOn()
    {
        return on;
    }

    public void setOn(boolean value)
    {
        on = value;
        repaint();
    }

    @Override
    public Dimension getPreferredSize()
    {
        return new Dimension(W, H);
    }

    @Override
    public Dimension getMaximumSize()
    {
        return getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize()
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
            java.awt.Color track = on ? BentoTheme.accentColor() : BentoTheme.RING;
            if (!isEnabled())
            {
                track = BentoTheme.withAlpha(track, 90);
            }
            g2.setColor(track);
            g2.fillRoundRect(0, 0, W, H, H, H);
            if (!on)
            {
                g2.setColor(BentoTheme.BORDER);
                g2.drawRoundRect(0, 0, W - 1, H - 1, H, H);
            }
            int knob = H - 4;
            int kx = on ? W - knob - 2 : 2;
            g2.setColor(on ? BentoTheme.BG : BentoTheme.MUTED);
            g2.fillOval(kx, 2, knob, knob);
            if (hasFocus())
            {
                g2.setColor(BentoTheme.withAlpha(BentoTheme.INFO, 160));
                g2.drawRoundRect(-1, -1, W + 1, H + 1, H, H);
            }
        }
        finally
        {
            g2.dispose();
        }
    }
}
