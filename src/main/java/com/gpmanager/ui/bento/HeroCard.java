package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.accessibility.AccessibleRole;
import javax.annotation.Nullable;

/**
 * The headline card of Live and Insights (SIDEBAR_BENTO.md §13.1): title · tag on the left,
 * a status on the right ("● Live 01:12:36", "just now ●", "Compared to previous 30 days ▲ +42%"),
 * a sign-coloured hero figure with a boxed chip beside it, one to three lines beneath, and an
 * optional area sparkline on the right. Never carries a standing tint: the figure and the chip
 * carry the colour.
 */
public final class HeroCard extends Painted
{
    /** One line under the hero: optional glyph, text, colour. */
    public static final class Line
    {
        final String glyph;
        final String text;
        final Color color;
        final boolean bold;

        public Line(@Nullable String glyph, String text, Color color, boolean bold)
        {
            this.glyph = glyph == null ? "" : glyph;
            this.text = text == null ? "" : text;
            this.color = color == null ? BentoTheme.MUTED : color;
            this.bold = bold;
        }

        public static Line muted(String text)
        {
            return new Line(null, text, BentoTheme.MUTED, false);
        }
    }

    private String title = "";
    private String tag = "";
    private String statusText = "";
    @Nullable
    private Color statusDot;
    private boolean statusDotLeading = true;
    private String statusSecond = "";
    private Color statusSecondColor = BentoTheme.POSITIVE;
    private String hero = "";
    private Color heroColor = BentoTheme.TEXT;
    private String chip = "";
    private String caption = "";
    private String captionNote = "";
    private Color chipColor = BentoTheme.POSITIVE;
    private List<Line> lines = Collections.emptyList();
    private double[] series = new double[0];
    private Color seriesColor = BentoTheme.POSITIVE;
    private boolean sparkline;
    private long lastValue;
    private boolean valueSeen;
    private List<StatGrid.Cell> strip = Collections.emptyList();
    private int stripColumns = 0;

    public HeroCard()
    {
        super(AccessibleRole.PANEL);
        setAlignmentX(LEFT_ALIGNMENT);
    }

    /** "Net" · "General" — the tag paints muted after a middle dot; empty for none. */
    public HeroCard title(String text, @Nullable String tagText)
    {
        title = text == null ? "" : text;
        tag = tagText == null ? "" : tagText;
        repaint();
        return this;
    }

    /**
     * Right-hand status. {@code dot} paints a small disc in that colour; {@code leading} puts it
     * before the text ("● Live 01:12:36"), otherwise after ("just now ●").
     */
    public HeroCard status(String text, @Nullable Color dot, boolean leading)
    {
        statusText = text == null ? "" : text;
        statusDot = dot;
        statusDotLeading = leading;
        repaint();
        return this;
    }

    /** A second status line under the first, coloured ("▲ +42%"). */
    public HeroCard statusSecond(@Nullable String text, @Nullable Color color)
    {
        statusSecond = text == null ? "" : text;
        statusSecondColor = color == null ? BentoTheme.POSITIVE : color;
        repaint();
        return this;
    }

    public HeroCard hero(String text, Color color)
    {
        hero = text == null ? "" : text;
        heroColor = color == null ? BentoTheme.TEXT : color;
        getAccessibleContext().setAccessibleName(title + " " + hero);
        repaint();
        return this;
    }

    /**
     * Sign-coloured net as the hero; a change since the last call shows as the chip ("▲ 1.2k"),
     * green when net went up, red when it went down, until the next change.
     */
    public HeroCard net(long value)
    {
        if (valueSeen && value != lastValue)
        {
            long d = value - lastValue;
            chip(String.valueOf(d > 0 ? "▲ " + Fmt.compact(d) : "▼ " + Fmt.compact(-d)), d > 0 ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE);
        }
        lastValue = value;
        valueSeen = true;
        return hero(BentoTheme.exactFigures() ? Fmt.exactSigned(value) : Fmt.signed(value), BentoTheme.signColor(value));
    }

    /** A small muted word right after the figure, on its baseline ("TOTAL"); empty hides it. */
    public HeroCard caption(@Nullable String text)
    {
        caption = text == null ? "" : text;
        revalidate();
        repaint();
        return this;
    }

    /** A muted note after the caption ("· +38k since bank"); empty hides it. */
    public HeroCard captionNote(@Nullable String text)
    {
        captionNote = text == null ? "" : text;
        repaint();
        return this;
    }

    /** Boxed chip beside the hero; empty hides it. */
    public HeroCard chip(@Nullable String text, @Nullable Color color)
    {
        chip = text == null ? "" : text;
        chipColor = color == null ? BentoTheme.POSITIVE : color;
        repaint();
        return this;
    }

    public HeroCard lines(List<Line> value)
    {
        lines = value == null ? Collections.emptyList() : new ArrayList<>(value);
        revalidate();
        repaint();
        return this;
    }

    public HeroCard lines(Line... value)
    {
        return lines(java.util.Arrays.asList(value));
    }

    /**
     * A stat strip inside the card, under the lines and a hairline: icon · label over value per
     * cell, equal columns (Gains · Loss · GP/h). Empty removes it.
     */
    public HeroCard strip(List<StatGrid.Cell> cells)
    {
        return strip(cells, 0);
    }

    /** As {@link #strip(List)} with {@code columns} cells per row (0 = all on one row). */
    public HeroCard strip(List<StatGrid.Cell> cells, int columns)
    {
        strip = cells == null ? Collections.emptyList() : new ArrayList<>(cells);
        stripColumns = columns;
        revalidate();
        repaint();
        return this;
    }

    private int stripRows()
    {
        if (strip.isEmpty())
        {
            return 0;
        }
        int per = stripColumns <= 0 ? strip.size() : stripColumns;
        return (strip.size() + per - 1) / per;
    }

    private int stripRowHeight()
    {
        FontMetrics label = getFontMetrics(BentoTheme.secondary());
        FontMetrics value = getFontMetrics(stripValueFont());
        FontMetrics chip = getFontMetrics(BentoTheme.font(Font.BOLD, BentoTheme.density().micro));
        return Math.max(STRIP_ICON, label.getHeight() + value.getHeight() + (stripHasDelta() ? chip.getHeight() + 6 : 0));
    }

    private static final int STRIP_ICON = 14;

    private int stripHeight()
    {
        if (strip.isEmpty())
        {
            return 0;
        }
        int rows = stripRows();
        return 8 + 1 + 8 + rows * stripRowHeight() + (rows - 1) * 10;
    }

    private boolean stripHasDelta()
    {
        for (StatGrid.Cell c : strip)
        {
            if (c.delta != null && !c.delta.isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    private static Font stripValueFont()
    {
        return BentoTheme.font(Font.BOLD, BentoTheme.density().body);
    }

    /** Area sparkline on the right; hidden when fewer than two points or disabled. */
    public HeroCard sparkline(double[] values, boolean enabled, @Nullable Color color)
    {
        series = values == null ? new double[0] : values.clone();
        sparkline = enabled && series.length > 1;
        seriesColor = color == null ? BentoTheme.POSITIVE : color;
        repaint();
        return this;
    }

    private int pad()
    {
        return BentoTheme.density().pad + 2;
    }

    private Font heroFont()
    {
        return BentoTheme.hero();
    }

    @Override
    public Dimension getPreferredSize()
    {
        FontMetrics head = getFontMetrics(BentoTheme.bodyBold());
        FontMetrics heroFm = getFontMetrics(heroFont());
        FontMetrics line = getFontMetrics(BentoTheme.secondary());
        int h = pad() + head.getHeight();
        if (!statusSecond.isEmpty())
        {
            h += line.getHeight() - 2;
        }
        h += 4 + heroFm.getHeight();
        for (int i = 0; i < lines.size(); i++)
        {
            h += line.getHeight() + (i == 0 ? 3 : 1);
        }
        h += stripHeight();
        h += pad();
        return new Dimension(BentoTheme.MIN_CONTENT_WIDTH, h);
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
            g2.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            int w = getWidth();
            int h = getHeight();
            int radius = BentoTheme.density().radius;
            g2.setColor(BentoTheme.SURFACE);
            g2.fillRoundRect(0, 0, w - 1, h - 1, radius, radius);

            int x = pad();
            int right = w - pad();
            int y = pad();

            // Sparkline first so the text paints over it.
            FontMetrics head = g2.getFontMetrics(BentoTheme.bodyBold());
            FontMetrics heroFm = g2.getFontMetrics(heroFont());
            int heroTop = y + head.getHeight() + (statusSecond.isEmpty() ? 0 : g2.getFontMetrics(BentoTheme.secondary()).getHeight() - 2) + 4;
            int textRight = sparkline ? x + (right - x) * 58 / 100 : right;
            if (sparkline)
            {
                int sx = textRight + 8;
                int sw = right - sx;
                int sTop = heroTop - 2;
                int sBottom = h - pad() + 2 - stripHeight();
                paintArea(g2, sx, sTop, sw, sBottom - sTop);
            }

            g2.setColor(BentoTheme.BORDER);
            g2.drawRoundRect(0, 0, w - 1, h - 1, radius, radius);

            // Header: title · tag on the left, status on the right.
            int base = y + head.getAscent();
            g2.setFont(BentoTheme.fontFor(title, BentoTheme.bodyBold()));
            g2.setColor(BentoTheme.TEXT);
            g2.drawString(title, x, base);
            int tx = x + g2.getFontMetrics().stringWidth(title);
            if (!tag.isEmpty())
            {
                g2.setFont(BentoTheme.font(Font.PLAIN, BentoTheme.density().body));
                g2.setColor(BentoTheme.MUTED);
                g2.drawString(" · " + tag, tx, base);
            }
            FontMetrics sm = g2.getFontMetrics(BentoTheme.secondary());
            if (!statusText.isEmpty() || statusDot != null)
            {
                g2.setFont(BentoTheme.fontFor(statusText, BentoTheme.secondary()));
                int textW = g2.getFontMetrics().stringWidth(statusText);
                int dotW = statusDot == null ? 0 : 7 + 5;
                int sx = right - textW - dotW;
                int sBase = y + head.getAscent();
                if (statusDot != null && statusDotLeading)
                {
                    g2.setColor(statusDot);
                    g2.fillOval(sx, sBase - 7, 7, 7);
                    sx += 12;
                }
                g2.setColor(BentoTheme.MUTED);
                g2.drawString(statusText, sx, sBase);
                if (statusDot != null && !statusDotLeading)
                {
                    g2.setColor(statusDot);
                    g2.fillOval(sx + textW + 5, sBase - 7, 7, 7);
                }
                if (!statusSecond.isEmpty())
                {
                    g2.setFont(BentoTheme.fontFor(statusSecond, BentoTheme.font(Font.BOLD, BentoTheme.density().secondary)));
                    int w2 = g2.getFontMetrics().stringWidth(statusSecond);
                    g2.setColor(statusSecondColor);
                    g2.drawString(statusSecond, right - w2, sBase + sm.getHeight() - 2);
                }
            }

            // Hero + chip.
            // TOTAL (small, dim) leads the figure on the same baseline; the figure shrinks (never
            // below 18 px) before it would ever be cut.
            int heroX = x;
            int capW = 0;
            if (!caption.isEmpty())
            {
                capW = g2.getFontMetrics(BentoTheme.micro()).stringWidth(caption.toUpperCase(java.util.Locale.ROOT)) + 6;
            }
            Font hf = BentoTheme.fontFor(hero, heroFont());
            g2.setFont(hf);
            int heroAvail = textRight - x - capW;
            int heroW = g2.getFontMetrics().stringWidth(hero);
            while (heroW > heroAvail && hf.getSize2D() > 18f)
            {
                hf = hf.deriveFont(hf.getSize2D() - 1f);
                g2.setFont(hf);
                heroW = g2.getFontMetrics().stringWidth(hero);
            }
            g2.setColor(heroColor);
            String heroText = hero;
            if (heroW > heroAvail)
            {
                heroText = StatBlock.fit(hero, g2.getFontMetrics(), heroAvail);
                heroW = g2.getFontMetrics().stringWidth(heroText);
            }
            int heroBase = heroTop + heroFm.getAscent() - (heroFm.getAscent() - g2.getFontMetrics().getAscent()) / 2;
            if (!caption.isEmpty())
            {
                g2.setFont(BentoTheme.micro());
                g2.setColor(BentoTheme.DIM);
                g2.drawString(caption.toUpperCase(java.util.Locale.ROOT), x, heroBase - 1);
                heroX = x + capW;
            }
            g2.setFont(hf);
            g2.setColor(heroColor);
            g2.drawString(heroText, heroX, heroBase);
            int afterHero = heroX + heroW;
            if (!chip.isEmpty())
            {
                Font chipFont = BentoTheme.fontFor(chip, BentoTheme.font(Font.BOLD, BentoTheme.density().secondary));
                g2.setFont(chipFont);
                FontMetrics cm = g2.getFontMetrics();
                int cw = cm.stringWidth(chip) + 16;
                int ch = cm.getHeight() + 8;
                int cx = afterHero + 10;
                int cy = heroTop + (heroFm.getHeight() - ch) / 2 + 1;
                if (cx + cw <= textRight + 4)
                {
                    g2.setColor(BentoTheme.withAlpha(chipColor, 30));
                    g2.fillRoundRect(cx, cy, cw, ch, 8, 8);
                    g2.setColor(BentoTheme.withAlpha(chipColor, 110));
                    g2.drawRoundRect(cx, cy, cw, ch, 8, 8);
                    g2.setColor(chipColor);
                    g2.drawString(chip, cx + 8, cy + 4 + cm.getAscent());
                    afterHero = cx + cw;
                }
            }
            if (!captionNote.isEmpty())
            {
                // "· +38k since bank" after the figure (and chip) when there is room; the tooltip keeps it otherwise.
                g2.setFont(BentoTheme.secondary());
                FontMetrics nm = g2.getFontMetrics();
                int room = textRight - afterHero - 8;
                if (nm.stringWidth(captionNote) <= room)
                {
                    g2.setColor(BentoTheme.MUTED);
                    g2.drawString(captionNote, afterHero + 8, heroBase - 1);
                }
            }

            int ly = heroTop + heroFm.getHeight() + 3;
            int lineAvail = textRight - x;
            for (Line l : lines)
            {
                Font f = l.bold ? BentoTheme.font(Font.BOLD, BentoTheme.density().secondary + 1) : BentoTheme.secondary();
                int lx = x;
                if (!l.glyph.isEmpty())
                {
                    g2.setFont(BentoTheme.symbol(BentoTheme.density().secondary));
                    g2.setColor(l.color);
                    g2.drawString(l.glyph, lx, ly + sm.getAscent());
                    lx += g2.getFontMetrics().stringWidth(l.glyph) + 5;
                }
                g2.setFont(BentoTheme.fontFor(l.text, f));
                g2.setColor(l.color);
                g2.drawString(StatBlock.fit(l.text, g2.getFontMetrics(), lineAvail - (lx - x)), lx, ly + sm.getAscent());
                ly += sm.getHeight() + 1;
            }

            // The strip: a hairline, then rows of cells — icon · label over value (chips on their own line).
            if (!strip.isEmpty())
            {
                int sy = ly + 7;
                g2.setColor(BentoTheme.BORDER);
                g2.drawLine(x, sy, right, sy);
                sy += 9;
                int per = stripColumns <= 0 ? strip.size() : stripColumns;
                int rowH = stripRowHeight();
                for (int start = 0; start < strip.size(); start += per)
                {
                    List<StatGrid.Cell> row = strip.subList(start, Math.min(strip.size(), start + per));
                    if (start > 0)
                    {
                        g2.setColor(BentoTheme.SOFT);
                        g2.drawLine(x, sy - 5, right, sy - 5);
                    }
                    paintStripRow(g2, row, x, right, sy, rowH);
                    sy += rowH + 10;
                }
            }
        }
        finally
        {
            g2.dispose();
        }
    }

    /** One strip row: cells sized to their text, icons dropped before anything clips. */
    private void paintStripRow(Graphics2D g2, List<StatGrid.Cell> cells, int x, int right, int sy, int cellH)
    {
        int n = cells.size();
        Font labelFont = BentoTheme.secondary();
        Font valueFont = stripValueFont();
        FontMetrics lm = g2.getFontMetrics(labelFont);
        FontMetrics vm = g2.getFontMetrics(valueFont);
        Font chipFont = BentoTheme.font(Font.BOLD, BentoTheme.density().micro);
        boolean chipLine = stripHasDelta();
        int textH = lm.getHeight() + vm.getHeight() + (chipLine ? g2.getFontMetrics(chipFont).getHeight() + 6 : 0);
        int[] widths = new int[n];
        int need = 0;
        int total = right - x;
        // A hero card carries figures only — never an icon in its strip.
        boolean icons = false;
        for (int pass = 0; pass < 2; pass++)
        {
            need = 0;
            for (int i = 0; i < n; i++)
            {
                StatGrid.Cell cell = cells.get(i);
                int text = Math.max(g2.getFontMetrics(BentoTheme.fontFor(cell.label, labelFont)).stringWidth(cell.label),
                    g2.getFontMetrics(BentoTheme.fontFor(cell.value, valueFont)).stringWidth(cell.value));
                widths[i] = (icons ? STRIP_ICON + 4 : 0) + text + (i == 0 ? 6 : 12);
                need += widths[i];
            }
            if (need <= total || !icons)
            {
                break;
            }
            icons = false;
        }
        if (need <= total)
        {
            int slack = (total - need) / n;
            for (int i = 0; i < n; i++)
            {
                widths[i] += slack;
            }
        }
        else
        {
            for (int i = 0; i < n; i++)
            {
                widths[i] = Math.max(28, widths[i] * total / need);
            }
        }
        int cx = x;
        for (int i = 0; i < n; i++)
        {
            StatGrid.Cell cell = cells.get(i);
            int cw = widths[i];
            if (i > 0)
            {
                g2.setColor(BentoTheme.BORDER);
                g2.drawLine(cx, sy + 1, cx, sy + cellH - 1);
                cx += 6;
                cw -= 6;
            }
            int sx2 = cx;
            if (icons)
            {
                cell.icon.paint(g2, cx, sy + (chipLine ? 2 : (cellH - STRIP_ICON) / 2), STRIP_ICON);
                sx2 = cx + STRIP_ICON + 4;
            }
            int avail = Math.max(20, cx + cw - 5 - sx2);
            int top = sy + (cellH - textH) / 2;
            g2.setFont(BentoTheme.fontFor(cell.label, labelFont));
            g2.setColor(BentoTheme.MUTED);
            g2.drawString(StatBlock.fit(cell.label, g2.getFontMetrics(), avail), sx2, top + lm.getAscent());
            g2.setFont(BentoTheme.fontFor(cell.value, valueFont));
            g2.setColor(cell.valueColor);
            String v = StatBlock.fit(cell.value, g2.getFontMetrics(), avail);
            g2.drawString(v, sx2, top + lm.getHeight() + vm.getAscent());
            if (cell.delta != null && !cell.delta.isEmpty())
            {
                Chip.paint(g2, chipFont, sx2, top + lm.getHeight() + vm.getHeight() + 2, cell.delta,
                    cell.deltaPositive ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE);
            }
            cx += cw;
        }
    }

    private void paintArea(Graphics2D g2, int x, int y, int w, int h)
    {
        if (series.length < 2 || w < 10 || h < 6)
        {
            return;
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : series)
        {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (max - min < 1e-9)
        {
            max = min + 1;
        }
        Path2D.Double line = new Path2D.Double();
        Path2D.Double area = new Path2D.Double();
        for (int i = 0; i < series.length; i++)
        {
            double px = x + (w - 1) * (i / (double) (series.length - 1));
            double py = y + (h - 4) * (1 - (series[i] - min) / (max - min)) + 2;
            if (i == 0)
            {
                line.moveTo(px, py);
                area.moveTo(px, y + h);
                area.lineTo(px, py);
            }
            else
            {
                line.lineTo(px, py);
                area.lineTo(px, py);
            }
        }
        area.lineTo(x + w - 1, y + h);
        area.closePath();
        g2.setPaint(new GradientPaint(0, y, BentoTheme.withAlpha(seriesColor, 70), 0, y + h, BentoTheme.withAlpha(seriesColor, 0)));
        g2.fill(area);
        g2.setColor(seriesColor);
        g2.setStroke(new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g2.draw(line);
    }
}
