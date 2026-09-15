package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.accessibility.AccessibleRole;
import javax.annotation.Nullable;

/**
 * A half-width card (Insights › Performance, Top activities): icon · title, then rows of
 * icon · label over value with a delta chip beside the value, or a share bar beneath it.
 * Painted so two of them sit side by side at exactly half the column each.
 */
public final class MiniCard extends Painted
{
    public static final class Row
    {
        final Icon icon;
        final String label;
        final String value;
        final Color valueColor;
        @Nullable
        final String chip;
        final boolean chipPositive;
        final double bar;
        @Nullable
        final Color barColor;
        @Nullable
        String note;

        /** Muted text after the value when it fits ("(46%)"). */
        public Row note(@Nullable String value)
        {
            note = value;
            return this;
        }

        /** Label over value with an optional delta chip. */
        public Row(Icon icon, String label, String value, Color valueColor, @Nullable String chip, boolean chipPositive)
        {
            this(icon, label, value, valueColor, chip, chipPositive, -1d, null);
        }

        /** Label over value with a share bar beneath (0..1). */
        public Row(Icon icon, String label, String value, Color valueColor, double bar, Color barColor)
        {
            this(icon, label, value, valueColor, null, true, bar, barColor);
        }

        private Row(Icon icon, String label, String value, Color valueColor, @Nullable String chip, boolean chipPositive,
            double bar, @Nullable Color barColor)
        {
            this.icon = icon;
            this.label = label == null ? "" : label;
            this.value = value == null ? "" : value;
            this.valueColor = valueColor == null ? BentoTheme.TEXT : valueColor;
            this.chip = chip;
            this.chipPositive = chipPositive;
            this.bar = bar;
            this.barColor = barColor;
        }
    }

    private static final int PAD = 7;
    private static final int ROW_GAP = 8;

    private Icon icon = Icon.glyph("", BentoTheme.MUTED);
    private String title = "";
    private List<Row> rows = Collections.emptyList();
    private boolean full;

    /** The whole column instead of half of it. */
    public MiniCard full(boolean value)
    {
        full = value;
        revalidate();
        repaint();
        return this;
    }

    public MiniCard()
    {
        super(AccessibleRole.PANEL);
        setAlignmentX(LEFT_ALIGNMENT);
    }

    public MiniCard title(Icon value, String text)
    {
        icon = value == null ? Icon.glyph("", BentoTheme.MUTED) : value;
        title = text == null ? "" : text;
        getAccessibleContext().setAccessibleName(title);
        repaint();
        return this;
    }

    public MiniCard rows(List<Row> value)
    {
        rows = value == null ? Collections.emptyList() : new ArrayList<>(value);
        revalidate();
        repaint();
        return this;
    }

    private static Font titleFont()
    {
        return BentoTheme.font(Font.BOLD, BentoTheme.density().secondary + 0.5f);
    }

    private int rowHeight(Row r)
    {
        FontMetrics label = getFontMetrics(BentoTheme.secondary());
        FontMetrics value = getFontMetrics(BentoTheme.font(Font.BOLD, BentoTheme.density().body + 1));
        int text = label.getHeight() + value.getHeight() + (r.bar >= 0d ? 7 : 0);
        return Math.max(Tile.IconBox.SIZE, text);
    }

    @Override
    public Dimension getPreferredSize()
    {
        FontMetrics head = getFontMetrics(titleFont());
        int h = PAD + Math.max(Icon.SIZE, head.getHeight()) + 8;
        for (Row r : rows)
        {
            h += rowHeight(r) + ROW_GAP;
        }
        return new Dimension(full ? BentoTheme.MIN_CONTENT_WIDTH : (BentoTheme.MIN_CONTENT_WIDTH - BentoTheme.density().gap) / 2, h - ROW_GAP + PAD);
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
            g2.setColor(BentoTheme.BORDER);
            g2.drawRoundRect(0, 0, w - 1, h - 1, radius, radius);

            int x = PAD;
            int y = PAD;
            int right = w - PAD;
            FontMetrics head = g2.getFontMetrics(titleFont());
            int headH = Math.max(Icon.SIZE, head.getHeight());
            g2.setFont(BentoTheme.fontFor(title, titleFont()));
            // The icon only when the title still fits beside it — a half-width card is tight.
            if (!icon.isEmpty() && g2.getFontMetrics().stringWidth(title) + 19 <= right - x)
            {
                icon.paint(g2, x, y + (headH - 14) / 2, 14, false);
                x += 14 + 5;
            }
            g2.setColor(BentoTheme.TEXT);
            g2.drawString(StatBlock.fit(title, g2.getFontMetrics(), right - x), x, y + (headH - head.getHeight()) / 2 + head.getAscent());
            y += headH + 8;

            Font labelFont = BentoTheme.secondary();
            Font valueFont = BentoTheme.font(Font.BOLD, BentoTheme.density().body + 1);
            Font chipFont = BentoTheme.font(Font.BOLD, BentoTheme.density().micro);
            for (Row r : rows)
            {
                int rh = rowHeight(r);
                int rx = PAD;
                if (r.icon.hasSprite())
                {
                    r.icon.paint(g2, rx, y + (rh - Tile.IconBox.SIZE) / 2 + (Tile.IconBox.SIZE - Icon.SIZE) / 2, Icon.SIZE, false);
                    rx += Icon.SIZE + 7;
                }
                int avail = Math.max(20, right - rx);
                g2.setFont(BentoTheme.fontFor(r.label, labelFont));
                FontMetrics lm = g2.getFontMetrics();
                g2.setColor(BentoTheme.MUTED);
                g2.drawString(StatBlock.fit(r.label, lm, avail), rx, y + lm.getAscent());
                g2.setFont(BentoTheme.fontFor(r.value, valueFont));
                FontMetrics vm = g2.getFontMetrics();
                g2.setColor(r.valueColor);
                String value = StatBlock.fit(r.value, vm, avail);
                int vy = y + lm.getHeight();
                g2.drawString(value, rx, vy + vm.getAscent());
                if (r.note != null && !r.note.isEmpty())
                {
                    g2.setFont(BentoTheme.secondary());
                    FontMetrics nm = g2.getFontMetrics();
                    int nx = rx + vm.stringWidth(value) + 4;
                    if (nx + nm.stringWidth(r.note) <= right)
                    {
                        g2.setColor(BentoTheme.MUTED);
                        g2.drawString(r.note, nx, vy + vm.getAscent());
                    }
                }
                if (r.chip != null && !r.chip.isEmpty())
                {
                    FontMetrics cm = g2.getFontMetrics(BentoTheme.fontFor(r.chip, chipFont));
                    int cw = Chip.width(cm, r.chip);
                    int cx = rx + vm.stringWidth(value) + 5;
                    if (cx + cw <= right)
                    {
                        Chip.paint(g2, chipFont, cx, vy + (vm.getHeight() - cm.getHeight() - 4) / 2, r.chip,
                            r.chipPositive ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE);
                    }
                }
                if (r.bar >= 0d)
                {
                    int by = vy + vm.getHeight() + 2;
                    int bw = right - rx;
                    g2.setColor(BentoTheme.SOFT);
                    g2.fillRoundRect(rx, by, bw, 5, 5, 5);
                    int fw = (int) Math.round(bw * Math.max(0d, Math.min(1d, r.bar)));
                    if (fw > 0)
                    {
                        g2.setColor(r.barColor == null ? BentoTheme.accentColor() : r.barColor);
                        g2.fillRoundRect(rx, by, Math.max(5, fw), 5, 5, 5);
                    }
                }
                y += rh + ROW_GAP;
            }
        }
        finally
        {
            g2.dispose();
        }
    }
}
