package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * Six-pixel scrollbar: no arrow buttons, rounded thumb, hairline when idle and a fuller
 * thumb while the pointer is over it or dragging. Frees ~11 px of content width compared
 * with RuneLite's default 17 px bar (SIDEBAR_BENTO.md §2).
 */
public final class SlimScrollBarUI extends BasicScrollBarUI
{
    private boolean hover;

    @Override
    protected void installListeners()
    {
        super.installListeners();
        scrollbar.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseEntered(MouseEvent e)
            {
                hover = true;
                scrollbar.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                hover = false;
                scrollbar.repaint();
            }
        });
    }

    @Override
    protected JButton createDecreaseButton(int orientation)
    {
        return zeroButton();
    }

    @Override
    protected JButton createIncreaseButton(int orientation)
    {
        return zeroButton();
    }

    private static JButton zeroButton()
    {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension(0, 0));
        button.setMinimumSize(new Dimension(0, 0));
        button.setMaximumSize(new Dimension(0, 0));
        button.setFocusable(false);
        button.setOpaque(false);
        return button;
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds)
    {
        g.setColor(BentoTheme.BG);
        g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds)
    {
        if (thumbBounds.isEmpty() || !scrollbar.isEnabled())
        {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            BentoTheme.quality(g2);
            boolean active = hover || isDragging;
            int width = active ? BentoTheme.SCROLLBAR_WIDTH : 2;
            int x = thumbBounds.x + thumbBounds.width - width - (active ? 0 : 1);
            Color color = active ? BentoTheme.DIM : BentoTheme.BORDER;
            g2.setColor(color);
            g2.fillRoundRect(x, thumbBounds.y + 1, width, Math.max(8, thumbBounds.height - 2), width, width);
        }
        finally
        {
            g2.dispose();
        }
    }

    @Override
    public Dimension getPreferredSize(JComponent c)
    {
        return new Dimension(BentoTheme.SCROLLBAR_WIDTH, 48);
    }

    /** Apply the slim bar to a scroll pane and the Bento scrolling defaults. */
    public static JScrollPane install(JScrollPane pane)
    {
        JScrollBar vertical = pane.getVerticalScrollBar();
        vertical.setUI(new SlimScrollBarUI());
        vertical.setPreferredSize(new Dimension(BentoTheme.SCROLLBAR_WIDTH, 0));
        vertical.setUnitIncrement(24);
        vertical.setBlockIncrement(120);
        vertical.setOpaque(false);
        pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        pane.setBorder(null);
        pane.setViewportBorder(null);
        pane.setOpaque(false);
        pane.getViewport().setOpaque(false);
        pane.getViewport().setBackground(BentoTheme.BG);
        pane.setBackground(BentoTheme.BG);
        pane.setWheelScrollingEnabled(true);
        return pane;
    }
}
