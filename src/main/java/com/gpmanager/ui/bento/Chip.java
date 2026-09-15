package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.accessibility.AccessibleRole;

/**
 * A small pill: delta ("▲ +12%"), state ("● Live"), or count. Static {@link #paint} lets
 * other painters draw the same pill inline; the component form sits in Swing rows.
 */
public final class Chip extends Painted
{
    private String text = "";
    private Color color = BentoTheme.MUTED;

    public Chip()
    {
        super(AccessibleRole.LABEL);
        setOpaque(false);
    }

    public Chip set(String value, Color tint)
    {
        text = value == null ? "" : value;
        color = tint == null ? BentoTheme.MUTED : tint;
        getAccessibleContext().setAccessibleName(text);
        setVisible(!text.isEmpty());
        revalidate();
        repaint();
        return this;
    }

    private static Font font()
    {
        return BentoTheme.font(Font.BOLD, BentoTheme.density().micro);
    }

    @Override
    public Dimension getPreferredSize()
    {
        FontMetrics fm = getFontMetrics(BentoTheme.fontFor(text, font()));
        return new Dimension(width(fm, text), fm.getHeight() + 4);
    }

    @Override
    public Dimension getMaximumSize()
    {
        return getPreferredSize();
    }

    static int width(FontMetrics fm, String text)
    {
        return fm.stringWidth(text) + 12;
    }

    /** Paints the pill with its top-left at (x, y); returns its width. */
    static int paint(Graphics2D g2, Font font, int x, int y, String text, Color tint)
    {
        g2.setFont(BentoTheme.fontFor(text, font));
        FontMetrics fm = g2.getFontMetrics();
        int w = width(fm, text);
        int h = fm.getHeight() + 4;
        g2.setColor(BentoTheme.withAlpha(tint, 38));
        g2.fillRoundRect(x, y, w, h, h, h);
        g2.setColor(tint);
        g2.drawString(text, x + 6, y + 2 + fm.getAscent());
        return w;
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        if (text.isEmpty())
        {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            BentoTheme.quality(g2);
            paint(g2, font(), 0, 0, text, color);
        }
        finally
        {
            g2.dispose();
        }
    }
}
