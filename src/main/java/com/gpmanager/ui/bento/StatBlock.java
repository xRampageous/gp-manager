package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * The Live stat block (SIDEBAR_BENTO.md §3): header + clock, hero net with a tick chip,
 * one or two micro-stat rows, optional sparkline behind the figure. Fully painted so it
 * never depends on the look-and-feel and always fits the owned width.
 */
public final class StatBlock extends Painted
{
    /** Clock / gem state mirrored from HUD+. */
    public enum ClockState
    {
        LIVE, IDLE, PAUSED, WAITING, PROVISIONAL, NONE
    }

    /** One micro stat: label above value; optional small suffix and colour. */
    public static final class Micro
    {
        public final String label;
        public final String value;
        @Nullable
        public final String suffix;
        @Nullable
        public final Color valueColor;
        @Nullable
        public final Color suffixColor;

        public Micro(String label, String value)
        {
            this(label, value, null, null, null);
        }

        public Micro(String label, String value, @Nullable Color valueColor)
        {
            this(label, value, null, valueColor, null);
        }

        public Micro(String label, String value, @Nullable String suffix, @Nullable Color valueColor,
            @Nullable Color suffixColor)
        {
            this.label = label == null ? "" : label;
            this.value = value == null ? "" : value;
            this.suffix = suffix;
            this.valueColor = valueColor;
            this.suffixColor = suffixColor;
        }
    }

    public enum Flavour
    {
        GENERAL, PVP, WEALTH, NEUTRAL
    }

    private static final int TICK_VISIBLE_MILLIS = 3_000;

    private String header = "";
    private String headerTag = "";
    private String clock = "";
    private String clockSuffix = "";
    private ClockState clockState = ClockState.LIVE;
    private long net;
    private boolean provisional;
    private long tickDelta;
    private long tickShownAt;
    private List<Micro> rowA = Collections.emptyList();
    private List<Micro> rowB = Collections.emptyList();
    private double[] sparkline = new double[0];
    private boolean graphEnabled;
    private Flavour flavour = Flavour.GENERAL;
    private final Timer tickTimer = new Timer(250, e -> onTickTimer());
    private String lastPaintedHero = "";
    private boolean netInitialised;

    public StatBlock()
    {
        super(AccessibleRole.PANEL);
        tickTimer.setRepeats(true);
    }

    public StatBlock header(String text, String tag)
    {
        header = text == null ? "" : text;
        headerTag = tag == null ? "" : tag;
        repaint();
        return this;
    }

    public StatBlock clock(String text, ClockState state, String suffix)
    {
        clock = text == null ? "" : text;
        clockState = state == null ? ClockState.LIVE : state;
        clockSuffix = suffix == null ? "" : suffix;
        repaint();
        return this;
    }

    /** Set the net; a change shows the tick chip for a few seconds. */
    public StatBlock net(long value, boolean provisionalPrices)
    {
        if (netInitialised && value != net && !provisionalPrices)
        {
            tickDelta = value - net;
            tickShownAt = System.currentTimeMillis();
            if (!tickTimer.isRunning())
            {
                tickTimer.start();
            }
        }
        net = value;
        provisional = provisionalPrices;
        netInitialised = true;
        repaint();
        return this;
    }

    /** A standing delta chip beside the hero (e.g. "▲ +12%") and a one-line sub under it (§13). */
    public StatBlock delta(@Nullable String text, boolean positive, @Nullable String subline)
    {
        deltaText = text == null ? "" : text;
        deltaPositive = positive;
        sub = subline == null ? "" : subline;
        revalidate();
        repaint();
        return this;
    }

    private String deltaText = "";
    private boolean deltaPositive = true;
    private String sub = "";

    public StatBlock micro(List<Micro> first, List<Micro> second)
    {
        rowA = first == null ? Collections.emptyList() : new ArrayList<>(first);
        rowB = second == null ? Collections.emptyList() : new ArrayList<>(second);
        revalidate();
        repaint();
        return this;
    }

    public StatBlock sparkline(double[] points, boolean enabled)
    {
        sparkline = points == null ? new double[0] : points.clone();
        graphEnabled = enabled;
        repaint();
        return this;
    }

    public StatBlock flavour(Flavour value)
    {
        flavour = value == null ? Flavour.GENERAL : value;
        repaint();
        return this;
    }

    public long getNet()
    {
        return net;
    }

    public boolean isTickVisible()
    {
        return !provisional && tickDelta != 0L && System.currentTimeMillis() - tickShownAt < TICK_VISIBLE_MILLIS;
    }

    /** Text as painted for the hero figure — exposed for fixture tests. */
    public String heroText()
    {
        return (provisional ? "≈ " : "") + Fmt.exactSigned(net);
    }

    private void onTickTimer()
    {
        if (!isTickVisible())
        {
            tickTimer.stop();
            tickDelta = 0L;
        }
        repaint();
    }

    private int pad()
    {
        return BentoTheme.density().pad + 1;
    }

    @Override
    public Dimension getPreferredSize()
    {
        FontMetrics micro = getFontMetrics(BentoTheme.micro());
        FontMetrics body = getFontMetrics(BentoTheme.secondary());
        FontMetrics hero = getFontMetrics(BentoTheme.hero());
        int h = pad() + micro.getHeight() + 2 + hero.getHeight();
        if (!sub.isEmpty())
        {
            h += body.getHeight() + 1;
        }
        if (!rowA.isEmpty())
        {
            h += 8 + micro.getHeight() + body.getHeight() + 2;
        }
        if (!rowB.isEmpty())
        {
            h += 4 + micro.getHeight() + body.getHeight();
        }
        h += pad() - 2;
        return new Dimension(BentoTheme.CONTENT_WIDTH, h);
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
            int w = getWidth();
            int h = getHeight();
            int radius = BentoTheme.density().radius;
            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, radius, radius);

            Color tint;
            Color border;
            switch (flavour)
            {
                case PVP:
                    tint = new Color(0x33101a);
                    border = new Color(0x6a2236);
                    break;
                case WEALTH:
                    tint = BentoTheme.WEALTH_SURFACE;
                    border = new Color(0x2a3a4a);
                    break;
                case NEUTRAL:
                    tint = null;
                    border = BentoTheme.BORDER;
                    break;
                default:
                    // No standing tint on the net card: the figure's colour says profit or loss.
                    // A change flashes the card green or red and fades with the tick chip.
                    tint = null;
                    border = BentoTheme.BORDER;
            }
            if (provisional)
            {
                border = BentoTheme.WARN_BORDER;
            }
            g2.setColor(BentoTheme.SURFACE);
            g2.fill(shape);
            if (tint != null)
            {
                g2.setPaint(new GradientPaint(0, 0, tint, w * 0.6f, h, BentoTheme.SURFACE));
                g2.fill(shape);
            }
            if (flavour == Flavour.GENERAL && isTickVisible())
            {
                float remaining = 1f - Math.min(1f, (System.currentTimeMillis() - tickShownAt) / (float) TICK_VISIBLE_MILLIS);
                Color flash = tickDelta > 0L ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE;
                g2.setColor(BentoTheme.withAlpha(flash, Math.round(48 * remaining)));
                g2.fill(shape);
                border = BentoTheme.withAlpha(flash, Math.round(200 * remaining));
            }
            g2.setClip(shape);

            int x = pad();
            int right = w - pad();
            int y = pad();

            // Header row: label · tag · clock (§13: sentence case, muted).
            g2.setFont(BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary));
            FontMetrics micro = g2.getFontMetrics();
            y += micro.getAscent();
            g2.setColor(BentoTheme.MUTED);
            String headerText = header;
            g2.drawString(headerText, x, y);
            int hx = x + micro.stringWidth(headerText) + 6;
            if (!headerTag.isEmpty())
            {
                paintTag(g2, hx, y, headerTag);
            }
            paintClock(g2, right, y, micro);
            y += micro.getDescent() + 2;

            // Hero.
            g2.setFont(BentoTheme.hero());
            FontMetrics hero = g2.getFontMetrics();
            int heroBase = y + hero.getAscent();
            String heroText = heroText();
            lastPaintedHero = heroText;
            g2.setColor(provisional ? BentoTheme.DIM : net > 0L ? BentoTheme.POSITIVE : net < 0L ? BentoTheme.NEGATIVE : BentoTheme.TEXT);
            g2.drawString(heroText, x, heroBase);
            if (!deltaText.isEmpty())
            {
                java.awt.Font chipFont = BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary);
                FontMetrics cf = getFontMetrics(chipFont);
                int cy = heroBase - hero.getAscent() / 2 - (cf.getHeight() + 4) / 2 + 2;
                Chip.paint(g2, chipFont, x + hero.stringWidth(heroText) + 8, cy, deltaText,
                    deltaPositive ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE);
            }
            else if (isTickVisible())
            {
                paintTickChip(g2, x + hero.stringWidth(heroText) + 6, heroBase, hero);
            }
            y = heroBase + hero.getDescent();
            if (!sub.isEmpty())
            {
                g2.setFont(BentoTheme.secondary());
                FontMetrics sm = g2.getFontMetrics();
                g2.setColor(BentoTheme.MUTED);
                g2.drawString(fit(sub, sm, right - x), x, y + 1 + sm.getAscent());
                y += sm.getHeight() + 1;
            }

            // Sparkline behind the lower part of the block.
            if (graphEnabled && sparkline.length > 1)
            {
                paintSparkline(g2, x, y - 2, right - x, h - y - pad() + 4);
            }

            // Micro rows.
            if (!rowA.isEmpty())
            {
                y += 8;
                g2.setColor(BentoTheme.withAlpha(border, 160));
                g2.drawLine(x, y, right, y);
                y = paintMicroRow(g2, rowA, x, right, y + 6);
            }
            if (!rowB.isEmpty())
            {
                y = paintMicroRow(g2, rowB, x, right, y + 4);
            }

            g2.setClip(null);
            g2.setColor(border);
            g2.draw(shape);
        }
        finally
        {
            g2.dispose();
        }
    }

    private void paintTag(Graphics2D g2, int x, int baseline, String text)
    {
        FontMetrics fm = g2.getFontMetrics();
        String upper = text.toUpperCase();
        int tw = fm.stringWidth(upper) + 8;
        int th = fm.getHeight();
        g2.setColor(BentoTheme.WARN_SURFACE);
        g2.fillRoundRect(x, baseline - fm.getAscent(), tw, th, 4, 4);
        g2.setColor(BentoTheme.WARN);
        g2.drawString(upper, x + 4, baseline);
    }

    private void paintClock(Graphics2D g2, int right, int baseline, FontMetrics micro)
    {
        g2.setFont(BentoTheme.secondary());
        FontMetrics fm = g2.getFontMetrics();
        String state = clockState == ClockState.LIVE ? "Live " : clockState == ClockState.PAUSED ? "Paused "
            : clockState == ClockState.IDLE ? "Idle " : clockState == ClockState.WAITING ? "Waiting " : "";
        String text = state + clock + (clockSuffix.isEmpty() ? "" : " · " + clockSuffix);
        int tw = fm.stringWidth(text);
        Color color = clockState == ClockState.IDLE || clockState == ClockState.WAITING ? BentoTheme.DIM : BentoTheme.MUTED;
        g2.setColor(color);
        g2.drawString(text, right - tw, baseline);
        if (clockState == ClockState.NONE)
        {
            g2.setFont(BentoTheme.micro());
            return;
        }
        // Gem / icon before the clock.
        int gx = right - tw - 12;
        int cy = baseline - fm.getAscent() / 2;
        switch (clockState)
        {
            case PAUSED:
                g2.setColor(BentoTheme.WARN);
                g2.fillRect(gx, cy - 4, 2, 8);
                g2.fillRect(gx + 4, cy - 4, 2, 8);
                break;
            case IDLE:
            case WAITING:
                g2.setColor(BentoTheme.DIM);
                g2.drawOval(gx - 1, cy - 4, 8, 8);
                break;
            case PROVISIONAL:
                g2.setColor(BentoTheme.WARN);
                g2.fillOval(gx - 1, cy - 4, 8, 8);
                break;
            default:
                g2.setColor(BentoTheme.accentColor());
                g2.fillOval(gx - 1, cy - 4, 8, 8);
        }
        g2.setFont(BentoTheme.micro());
    }

    private void paintTickChip(Graphics2D g2, int x, int heroBaseline, FontMetrics hero)
    {
        g2.setFont(BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary));
        FontMetrics fm = g2.getFontMetrics();
        String text = (tickDelta > 0L ? "▲ " : "▼ ") + Fmt.compact(tickDelta);
        int tw = fm.stringWidth(text) + 10;
        int th = fm.getHeight() + 2;
        int cy = heroBaseline - hero.getAscent() / 2;
        boolean up = tickDelta > 0L;
        g2.setColor(up ? BentoTheme.POSITIVE_SURFACE : BentoTheme.NEGATIVE_SURFACE);
        g2.fillRoundRect(x, cy - th / 2, tw, th, 5, 5);
        g2.setColor(up ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE);
        g2.drawString(text, x + 5, cy + fm.getAscent() / 2 - 1);
    }

    private int paintMicroRow(Graphics2D g2, List<Micro> row, int x, int right, int y)
    {
        int columns = row.size();
        int gap = 7;
        int colW = (right - x - gap * (columns - 1)) / columns;
        g2.setFont(BentoTheme.micro());
        FontMetrics micro = g2.getFontMetrics();
        int labelBase = y + micro.getAscent();
        g2.setFont(BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary + 2));
        FontMetrics value = g2.getFontMetrics();
        int valueBase = labelBase + micro.getDescent() + 1 + value.getAscent();
        for (int i = 0; i < columns; i++)
        {
            Micro m = row.get(i);
            int cx = x + i * (colW + gap);
            g2.setFont(BentoTheme.micro());
            g2.setColor(BentoTheme.DIM);
            g2.drawString(fit(m.label.toUpperCase(), micro, colW), cx, labelBase);
            g2.setFont(BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary + 2));
            g2.setColor(m.valueColor == null ? BentoTheme.TEXT : m.valueColor);
            String v = fit(m.value, value, colW);
            g2.drawString(v, cx, valueBase);
            if (m.suffix != null && !m.suffix.isEmpty())
            {
                int vw = value.stringWidth(v) + 4;
                g2.setFont(BentoTheme.secondary());
                FontMetrics sfm = g2.getFontMetrics();
                String suffix = fit(m.suffix, sfm, colW - vw);
                g2.setColor(m.suffixColor == null ? BentoTheme.MUTED : m.suffixColor);
                if (!suffix.isEmpty())
                {
                    g2.drawString(suffix, cx + vw, valueBase);
                }
            }
        }
        return valueBase + value.getDescent();
    }

    private void paintSparkline(Graphics2D g2, int x, int y, int w, int h)
    {
        if (h < 8 || w < 8)
        {
            return;
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double p : sparkline)
        {
            min = Math.min(min, p);
            max = Math.max(max, p);
        }
        if (max - min < 1e-9)
        {
            max = min + 1;
        }
        Color line = flavour == Flavour.PVP ? BentoTheme.PVP
            : flavour == Flavour.WEALTH ? BentoTheme.INFO : BentoTheme.accentColor();
        Path2D fill = new Path2D.Double();
        Path2D stroke = new Path2D.Double();
        for (int i = 0; i < sparkline.length; i++)
        {
            double px = x + (double) i / (sparkline.length - 1) * w;
            double py = y + h - (sparkline[i] - min) / (max - min) * (h - 2) - 1;
            if (i == 0)
            {
                fill.moveTo(px, y + h);
                fill.lineTo(px, py);
                stroke.moveTo(px, py);
            }
            else
            {
                fill.lineTo(px, py);
                stroke.lineTo(px, py);
            }
        }
        fill.lineTo(x + w, y + h);
        fill.closePath();
        g2.setPaint(new GradientPaint(0, y, BentoTheme.withAlpha(line, 80), 0, y + h, BentoTheme.withAlpha(line, 0)));
        g2.fill(fill);
        g2.setColor(BentoTheme.withAlpha(line, 220));
        g2.setStroke(new java.awt.BasicStroke(1.4f));
        g2.draw(stroke);
    }

    static String fit(String text, FontMetrics fm, int width)
    {
        if (text == null)
        {
            return "";
        }
        if (fm.stringWidth(text) <= width)
        {
            return text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end)) + fm.stringWidth(ellipsis) > width)
        {
            end--;
        }
        return end <= 0 ? "" : text.substring(0, end) + ellipsis;
    }

    /** For fixture tests: the hero text painted last. */
    String lastPaintedHero()
    {
        return lastPaintedHero;
    }
}
