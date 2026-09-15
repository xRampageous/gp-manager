package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.accessibility.AccessibleRole;

/**
 * When-you-earn grid (SIDEBAR_BENTO.md §6): rows are 4-hour slots, columns Monday → Sunday.
 * Cell tint scales with counted net; losses are painted in the negative colour. A tooltip
 * on hover names the slot and its figure.
 */
public final class Heatmap extends Painted
{
    private static final String[] DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String[] SLOTS = {"00–04", "04–08", "08–12", "12–16", "16–20", "20–24"};

    private long[][] cells = new long[7][6];
    private long peak;

    public Heatmap()
    {
        super(AccessibleRole.CANVAS);
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        getAccessibleContext().setAccessibleName("When you earn");
        setToolTipText("");
    }

    /** {@code values[day][slot]} with Monday first and six 4-hour slots. */
    public Heatmap values(long[][] values)
    {
        cells = values == null ? new long[7][6] : values;
        peak = 0L;
        for (long[] row : cells)
        {
            for (long v : row)
            {
                peak = Math.max(peak, Math.abs(v));
            }
        }
        repaint();
        return this;
    }

    @Override
    public String getToolTipText(java.awt.event.MouseEvent e)
    {
        int[] cell = cellAt(e.getX(), e.getY());
        if (cell == null)
        {
            return null;
        }
        long v = cells[cell[0]][cell[1]];
        return DAYS[cell[0]] + " " + SLOTS[cell[1]] + " · " + (v == 0L ? "nothing" : Fmt.signed(v));
    }

    private int[] cellAt(int x, int y)
    {
        int gap = 2;
        int cw = (getWidth() - 6 * gap) / 7;
        int ch = 8;
        int col = x / (cw + gap);
        int row = y / (ch + gap);
        if (col < 0 || col > 6 || row < 0 || row > 5)
        {
            return null;
        }
        return new int[] {col, row};
    }

    @Override
    public Dimension getPreferredSize()
    {
        return new Dimension(BentoTheme.MIN_CONTENT_WIDTH, 6 * 8 + 5 * 2);
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            BentoTheme.quality(g2);
            int gap = 2;
            int cw = (getWidth() - 6 * gap) / 7;
            int ch = 8;
            for (int day = 0; day < 7; day++)
            {
                for (int slot = 0; slot < 6; slot++)
                {
                    long v = cells[day][slot];
                    int x = day * (cw + gap);
                    int y = slot * (ch + gap);
                    Color base = v == 0L || peak == 0L ? BentoTheme.SOFT : v > 0L ? BentoTheme.accentColor() : BentoTheme.NEGATIVE;
                    int alpha = v == 0L || peak == 0L ? 255 : 60 + (int) Math.round(195d * Math.min(1d, Math.abs(v) / (double) peak));
                    g2.setColor(BentoTheme.withAlpha(base, alpha));
                    g2.fillRoundRect(x, y, cw, ch, 3, 3);
                }
            }
        }
        finally
        {
            g2.dispose();
        }
    }
}
