package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

/**
 * Session ribbon: runs as segments (current one lit), bank visits as ticks, deaths as red
 * marks, loot-key pickups as amber ticks. Positions are fractions of active elapsed time.
 * Click a segment to open it in Runs; hover shows the segment's tooltip.
 */
public final class Ribbon extends Painted
{
    public static final class Segment
    {
        public final String id;
        public final double start;
        public final double end;
        public final boolean current;
        public final String tooltip;

        public Segment(String id, double start, double end, boolean current, String tooltip)
        {
            this.id = id;
            this.start = clamp(start);
            this.end = clamp(end);
            this.current = current;
            this.tooltip = tooltip == null ? "" : tooltip;
        }
    }

    public enum MarkKind
    {
        BANK, DEATH, KEY, TASK, RAID
    }

    public static final class Mark
    {
        public final double at;
        public final MarkKind kind;
        public final String label;

        public Mark(double at, MarkKind kind)
        {
            this(at, kind, null);
        }

        public Mark(double at, MarkKind kind, @Nullable String label)
        {
            this.at = clamp(at);
            this.kind = kind;
            this.label = label == null ? "" : label;
        }
    }

    private static final int HEIGHT = 12;

    private List<Segment> segments = Collections.emptyList();
    private List<Mark> marks = Collections.emptyList();
    private boolean pvp;
    private Consumer<String> onSegment = id -> { };

    public Ribbon()
    {
        super(AccessibleRole.PANEL);
        MouseAdapter mouse = new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                Segment segment = segmentAt(e.getX());
                if (segment != null)
                {
                    onSegment.accept(segment.id);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e)
            {
                Segment segment = segmentAt(e.getX());
                setToolTipText(segment == null || segment.tooltip.isEmpty() ? null : segment.tooltip);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public Ribbon segments(List<Segment> value, List<Mark> markValue)
    {
        segments = value == null ? Collections.emptyList() : new ArrayList<>(value);
        marks = markValue == null ? Collections.emptyList() : new ArrayList<>(markValue);
        repaint();
        return this;
    }

    public Ribbon pvp(boolean value)
    {
        pvp = value;
        repaint();
        return this;
    }

    public Ribbon onSegmentClick(Consumer<String> consumer)
    {
        onSegment = consumer == null ? id -> { } : consumer;
        return this;
    }

    private Segment segmentAt(int x)
    {
        double f = getWidth() <= 0 ? 0 : (double) x / getWidth();
        for (Segment s : segments)
        {
            if (f >= s.start && f <= s.end)
            {
                return s;
            }
        }
        return null;
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

    @Override
    protected void paintComponent(Graphics g)
    {
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            BentoTheme.quality(g2);
            int w = getWidth();
            int barY = 2;
            int barH = 7;
            Color base = pvp ? new Color(0x5a2236) : new Color(0x3a2a24);
            Color lit = pvp ? BentoTheme.PVP : BentoTheme.accentColor();
            if (segments.isEmpty())
            {
                g2.setColor(BentoTheme.SOFT);
                g2.fillRoundRect(0, barY, w, barH, 3, 3);
            }
            for (Segment s : segments)
            {
                int x0 = (int) Math.round(s.start * w);
                int x1 = (int) Math.round(s.end * w);
                if (x1 - x0 < 2)
                {
                    x1 = x0 + 2;
                }
                g2.setColor(s.current ? lit : base);
                g2.fillRoundRect(x0 + 1, barY, Math.max(1, x1 - x0 - 2), barH, 3, 3);
            }
            for (Mark m : marks)
            {
                int x = (int) Math.round(m.at * w);
                switch (m.kind)
                {
                    case DEATH:
                        g2.setColor(BentoTheme.NEGATIVE);
                        break;
                    case KEY:
                        g2.setColor(BentoTheme.WARN);
                        break;
                    case TASK:
                        g2.setColor(BentoTheme.INFO);
                        break;
                    case RAID:
                        g2.setColor(BentoTheme.accentColor());
                        break;
                    default:
                        g2.setColor(BentoTheme.MUTED);
                }
                g2.fillRoundRect(x - 1, 0, 3, HEIGHT, 1, 1);
            }
        }
        finally
        {
            g2.dispose();
        }
    }

    private static double clamp(double v)
    {
        return Math.max(0d, Math.min(1d, v));
    }
}
