package com.gpmanager.ui.bento;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import javax.accessibility.AccessibleRole;

/**
 * Net-per-session line against a dashed average (SIDEBAR_BENTO.md §6). Sessions excluded
 * from averages are drawn as hollow points and skipped by the line; the average is the
 * time-weighted figure the read model already computed, so the chart never re-derives it.
 */
public final class TrendChart extends Painted
{
    public static final class Point
    {
        final double value;
        final boolean excluded;

        public Point(double value, boolean excluded)
        {
            this.value = value;
            this.excluded = excluded;
        }
    }

    private final List<Point> points = new ArrayList<>();
    private double average;
    private Color line = BentoTheme.accentColor();
    private int height = 56;

    public TrendChart()
    {
        super(AccessibleRole.CANVAS);
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        getAccessibleContext().setAccessibleName("Net per session trend");
    }

    public TrendChart points(List<Point> values, double averageValue)
    {
        points.clear();
        points.addAll(values);
        average = averageValue;
        repaint();
        return this;
    }

    public TrendChart lineColor(Color color)
    {
        line = color;
        repaint();
        return this;
    }

    public TrendChart height(int px)
    {
        height = px;
        return this;
    }

    @Override
    public Dimension getPreferredSize()
    {
        return new Dimension(BentoTheme.MIN_CONTENT_WIDTH, height);
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, height);
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
            int pad = 4;
            List<Point> drawn = new ArrayList<>();
            double min = average;
            double max = average;
            for (Point p : points)
            {
                min = Math.min(min, p.value);
                max = Math.max(max, p.value);
            }
            if (max - min < 1d)
            {
                max = min + 1d;
            }
            double span = max - min;
            int n = points.size();
            double stepX = n <= 1 ? 0 : (w - 2d * pad) / (n - 1);

            // Dashed average line.
            int avgY = (int) Math.round(h - pad - (average - min) / span * (h - 2d * pad));
            g2.setColor(BentoTheme.withAlpha(BentoTheme.MUTED, 140));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] {3f, 3f}, 0f));
            g2.drawLine(pad, avgY, w - pad, avgY);

            if (n == 0)
            {
                return;
            }
            Path2D.Double path = new Path2D.Double();
            boolean started = false;
            int[] xs = new int[n];
            int[] ys = new int[n];
            for (int i = 0; i < n; i++)
            {
                Point p = points.get(i);
                xs[i] = (int) Math.round(pad + i * stepX);
                ys[i] = (int) Math.round(h - pad - (p.value - min) / span * (h - 2d * pad));
                if (p.excluded)
                {
                    continue;
                }
                if (!started)
                {
                    path.moveTo(xs[i], ys[i]);
                    started = true;
                }
                else
                {
                    path.lineTo(xs[i], ys[i]);
                }
                drawn.add(p);
            }
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(line);
            if (drawn.size() > 1)
            {
                g2.draw(path);
            }
            for (int i = 0; i < n; i++)
            {
                Point p = points.get(i);
                if (p.excluded)
                {
                    g2.setColor(BentoTheme.DIM);
                    g2.drawOval(xs[i] - 2, ys[i] - 2, 4, 4);
                }
                else if (drawn.size() <= 1 || i == n - 1)
                {
                    g2.setColor(line);
                    g2.fillOval(xs[i] - 2, ys[i] - 2, 5, 5);
                }
            }
        }
        finally
        {
            g2.dispose();
        }
    }
}
