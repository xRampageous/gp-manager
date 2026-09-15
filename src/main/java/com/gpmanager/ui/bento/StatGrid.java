package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.accessibility.AccessibleRole;
import javax.annotation.Nullable;

/**
 * Icon-led stat cells in a grid of cards (SIDEBAR_BENTO.md §13.1): icon · label · value ·
 * delta chip. Painted so the column widths are exact at 242 px; values are fitted, never
 * clipped. Tooltips name what a delta compares against.
 */
public final class StatGrid extends Painted
{
    public static final class Cell
    {
        final Icon icon;
        final String label;
        final String value;
        final Color valueColor;
        @Nullable
        final String delta;
        final boolean deltaPositive;
        @Nullable
        final String tooltip;

        public Cell(Icon icon, String label, String value, Color valueColor)
        {
            this(icon, label, value, valueColor, null, true, null);
        }

        public Cell(Icon icon, String label, String value, Color valueColor, @Nullable String delta, boolean deltaPositive,
            @Nullable String tooltip)
        {
            this.icon = icon;
            this.label = label == null ? "" : label;
            this.value = value == null ? "" : value;
            this.valueColor = valueColor == null ? BentoTheme.TEXT : valueColor;
            this.delta = delta;
            this.deltaPositive = deltaPositive;
            this.tooltip = tooltip;
        }
    }

    /** Where the icon sits and which of label / value comes first. */
    public enum Layout
    {
        /** Icon above, label, value (Combat summary, Live › PvP). */
        ICON_TOP,
        /** Icon at the left; label over value; a delta chip beside the value (Live › General strip, Performance). */
        ICON_LEFT,
        /** Icon at the left; value over label (the Sessions current card). */
        ICON_LEFT_VALUE_FIRST
    }

    private static final int GAP = 6;
    private static final int PAD = 5;

    private List<Cell> cells = Collections.emptyList();
    private int columns = 3;
    private boolean joined;
    private Layout layout = Layout.ICON_TOP;

    public StatGrid()
    {
        super(AccessibleRole.PANEL);
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        setToolTipText("");
    }

    /** Cells and how many per row; extra cells wrap to the next row. */
    public StatGrid cells(List<Cell> value, int columnsPerRow)
    {
        cells = new ArrayList<>(value);
        columns = Math.max(1, columnsPerRow);
        StringBuilder sb = new StringBuilder();
        for (Cell c : cells)
        {
            sb.append(c.label).append(' ').append(c.value).append("; ");
        }
        getAccessibleContext().setAccessibleName(sb.toString().trim());
        revalidate();
        repaint();
        return this;
    }

    public StatGrid layout(Layout value)
    {
        layout = value == null ? Layout.ICON_TOP : value;
        revalidate();
        repaint();
        return this;
    }

    /** One card with dividers instead of separate cards (the hero strip). */
    public StatGrid joined(boolean value)
    {
        joined = value;
        repaint();
        return this;
    }

    private int rows()
    {
        return cells.isEmpty() ? 0 : (cells.size() + columns - 1) / columns;
    }

    private int cellHeight()
    {
        FontMetrics label = getFontMetrics(BentoTheme.micro());
        FontMetrics value = getFontMetrics(BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + 1.5f));
        FontMetrics chip = getFontMetrics(BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().micro));
        boolean anyDelta = false;
        for (Cell c : cells)
        {
            anyDelta |= c.delta != null && !c.delta.isEmpty();
        }
        // Stat cells are figures, not pictures: no icon ever, whatever the cell was given.
        boolean anyIcon = false;
        if (layout != Layout.ICON_TOP)
        {
            FontMetrics v = getFontMetrics(sideValueFont());
            int text = label.getHeight() + 1 + v.getHeight();
            return PAD + 3 + Math.max(anyIcon ? SIDE_ICON : 0, text) + 3 + PAD;
        }
        return PAD + (anyIcon ? Icon.SIZE + 4 : 0) + label.getHeight() + 1 + value.getHeight() + (anyDelta ? chip.getHeight() + 2 : 0) + PAD;
    }

    @Override
    public Dimension getPreferredSize()
    {
        int rows = rows();
        return new Dimension(BentoTheme.MIN_CONTENT_WIDTH, rows == 0 ? 0 : rows * cellHeight() + (rows - 1) * (joined ? 0 : GAP));
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public String getToolTipText(java.awt.event.MouseEvent e)
    {
        int index = cellAt(e.getX(), e.getY());
        if (index < 0)
        {
            return null;
        }
        Cell c = cells.get(index);
        return c.tooltip != null ? c.tooltip : c.label + " · " + c.value;
    }

    private int cellAt(int x, int y)
    {
        if (cells.isEmpty())
        {
            return -1;
        }
        int ch = cellHeight();
        int cw = (getWidth() - (joined ? 0 : (columns - 1) * GAP)) / columns;
        int col = Math.min(columns - 1, Math.max(0, x / (cw + (joined ? 0 : GAP))));
        int row = Math.min(rows() - 1, Math.max(0, y / (ch + (joined ? 0 : GAP))));
        int index = row * columns + col;
        return index < cells.size() ? index : -1;
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        if (cells.isEmpty())
        {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            BentoTheme.quality(g2);
            int w = getWidth();
            int ch = cellHeight();
            int rows = rows();
            int radius = BentoTheme.density().radius;
            java.awt.Font labelFont = BentoTheme.micro();
            java.awt.Font valueFont = BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + 1.5f);
            java.awt.Font chipFont = BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().micro);
            for (int r = 0; r < rows; r++)
            {
                int y0 = r * (ch + (joined ? 0 : GAP));
                int inRow = Math.min(columns, cells.size() - r * columns);
                if (joined && r == 0)
                {
                    // One card around every row; rows are split by hairlines, not by borders.
                    int total = rows * ch;
                    g2.setColor(BentoTheme.SURFACE);
                    g2.fillRoundRect(0, 0, w - 1, total - 1, radius, radius);
                    g2.setColor(BentoTheme.BORDER);
                    g2.drawRoundRect(0, 0, w - 1, total - 1, radius, radius);
                }
                if (joined && r > 0)
                {
                    g2.setColor(BentoTheme.BORDER);
                    g2.drawLine(8, y0, w - 9, y0);
                }
                int cw = joined ? w / inRow : (w - (inRow - 1) * GAP) / inRow;
                for (int c = 0; c < inRow; c++)
                {
                    Cell cell = cells.get(r * columns + c);
                    int x0 = joined ? c * cw : c * (cw + GAP);
                    if (!joined)
                    {
                        g2.setColor(BentoTheme.SURFACE);
                        g2.fillRoundRect(x0, y0, cw - 1, ch - 1, radius, radius);
                        g2.setColor(BentoTheme.BORDER);
                        g2.drawRoundRect(x0, y0, cw - 1, ch - 1, radius, radius);
                    }
                    else if (c > 0)
                    {
                        g2.setColor(BentoTheme.BORDER);
                        g2.drawLine(x0, y0 + 8, x0, y0 + ch - 9);
                    }
                    if (layout != Layout.ICON_TOP)
                    {
                        paintIconLeft(g2, cell, x0, y0, cw, ch, labelFont, valueFont, chipFont);
                        continue;
                    }
                    int inner = cw - 2 * PAD;
                    int x = x0 + PAD;
                    int y = y0 + PAD;

                    g2.setFont(BentoTheme.fontFor(cell.label, labelFont));
                    FontMetrics lm = g2.getFontMetrics();
                    g2.setColor(BentoTheme.MUTED);
                    g2.drawString(StatBlock.fit(cell.label, lm, inner), x, y + lm.getAscent());
                    y += lm.getHeight() + 1;
                    g2.setFont(BentoTheme.fontFor(cell.value, valueFont));
                    FontMetrics vm = g2.getFontMetrics();
                    g2.setColor(cell.valueColor);
                    g2.drawString(StatBlock.fit(cell.value, vm, inner), x, y + vm.getAscent());
                    y += vm.getHeight() + 2;
                    if (cell.delta != null && !cell.delta.isEmpty())
                    {
                        Chip.paint(g2, chipFont, x, y, cell.delta, cell.deltaPositive ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE);
                    }
                }
            }
        }
        finally
        {
            g2.dispose();
        }
    }

    private static final int SIDE_ICON = 16;
    private static final int SIDE_PAD = 4;

    /** Values in the icon-left layouts: one step smaller in a three-column strip so 6 figures fit a 72 px cell. */
    private java.awt.Font sideValueFont()
    {
        return BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body + (columns >= 3 ? 0f : 1.5f));
    }

    /** Icon left of a two-line text column; the delta chip sits beside the value when it fits. */
    private void paintIconLeft(Graphics2D g2, Cell cell, int x0, int y0, int cw, int ch, java.awt.Font labelFont,
        java.awt.Font valueFont, java.awt.Font chipFont)
    {
        int x = x0 + SIDE_PAD + 1;
        valueFont = sideValueFont();
        FontMetrics lm = g2.getFontMetrics(BentoTheme.fontFor(cell.label, labelFont));
        FontMetrics vm = g2.getFontMetrics(BentoTheme.fontFor(cell.value, valueFont));
        int text = lm.getHeight() + 1 + vm.getHeight();
        int top = y0 + (ch - text) / 2;
        int tx = x;
        int inner = Math.max(20, x0 + cw - SIDE_PAD - tx);
        boolean valueFirst = layout == Layout.ICON_LEFT_VALUE_FIRST;
        int labelY = valueFirst ? top + vm.getHeight() + 1 : top;
        int valueY = valueFirst ? top : top + lm.getHeight() + 1;
        g2.setFont(BentoTheme.fontFor(cell.label, labelFont));
        g2.setColor(BentoTheme.MUTED);
        g2.drawString(StatBlock.fit(cell.label, lm, inner), tx, labelY + lm.getAscent());
        g2.setFont(BentoTheme.fontFor(cell.value, valueFont));
        g2.setColor(cell.valueColor);
        String v = StatBlock.fit(cell.value, vm, inner);
        g2.drawString(v, tx, valueY + vm.getAscent());
        if (cell.delta != null && !cell.delta.isEmpty())
        {
            FontMetrics cm = g2.getFontMetrics(BentoTheme.fontFor(cell.delta, chipFont));
            int chipW = Chip.width(cm, cell.delta);
            int vx = tx + vm.stringWidth(v) + 5;
            if (vx + chipW <= x0 + cw - SIDE_PAD)
            {
                Chip.paint(g2, chipFont, vx, valueY + (vm.getHeight() - cm.getHeight() - 4) / 2, cell.delta,
                    cell.deltaPositive ? BentoTheme.POSITIVE : BentoTheme.NEGATIVE);
            }
        }
    }
}
