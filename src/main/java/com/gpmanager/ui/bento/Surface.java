package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.geom.RoundRectangle2D;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JPanel;

/**
 * Rounded, bordered panel — the base of every tile, notice and block. Optional
 * left-to-right or diagonal tint for state surfaces (accent, PvP, wealth, warn).
 */
public class Surface extends JPanel
{
    private Color fill = BentoTheme.SURFACE;
    private Color border = BentoTheme.BORDER;
    @Nullable
    private Color tint;
    private boolean diagonalTint;
    private int radius = BentoTheme.density().radius;

    public Surface(LayoutManager layout)
    {
        super(layout);
        setOpaque(false);
        int pad = BentoTheme.density().pad;
        setBorder(BorderFactory.createEmptyBorder(pad - 2, pad + 1, pad - 2, pad + 1));
    }

    public Surface fill(Color color)
    {
        this.fill = color;
        repaint();
        return this;
    }

    public Surface borderColor(Color color)
    {
        this.border = color;
        repaint();
        return this;
    }

    /** Horizontal tint fading into the surface — used by state notices and owner tiles. */
    public Surface tint(@Nullable Color color)
    {
        this.tint = color;
        this.diagonalTint = false;
        repaint();
        return this;
    }

    /** Diagonal tint from the top-left — used by the stat block. */
    public Surface diagonalTint(@Nullable Color color)
    {
        this.tint = color;
        this.diagonalTint = true;
        repaint();
        return this;
    }

    public Surface radius(int value)
    {
        this.radius = Math.max(0, value);
        repaint();
        return this;
    }

    public Surface padding(int top, int left, int bottom, int right)
    {
        setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
        return this;
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
            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, radius, radius);
            g2.setColor(fill);
            g2.fill(shape);
            if (tint != null)
            {
                g2.setPaint(diagonalTint
                    ? new GradientPaint(0, 0, tint, w * 0.7f, h, fill)
                    : new GradientPaint(0, 0, tint, w * 0.6f, 0, fill));
                g2.fill(shape);
            }
            g2.setColor(border);
            g2.draw(shape);
        }
        finally
        {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
