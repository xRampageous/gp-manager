package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Rounded list container: optional micro header with a right-aligned link, then rows.
 * Rows are added with {@link #row(ItemRow)}; the first row loses its divider. An
 * {@link #empty(String)} sentence replaces a blank list.
 */
public final class ListBlock extends Surface
{
    private int rows;

    public ListBlock()
    {
        super(null);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(LEFT_ALIGNMENT);
        padding(0, 0, 0, 0);
    }

    public ListBlock header(String text, @Nullable String link, @Nullable Runnable onLink)
    {
        return header(null, text, link, onLink);
    }

    public ListBlock header(@Nullable Icon icon, String text, @Nullable String link, @Nullable Runnable onLink)
    {
        JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setOpaque(false);
        line.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 9, 4, 9));
        if (icon != null && !icon.isEmpty())
        {
            line.add(new Tile.IconLabel(icon));
            line.add(Box.createHorizontalStrut(6));
        }
        line.add(Tile.label(text, BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body), BentoTheme.TEXT));
        line.add(Box.createHorizontalGlue());
        if (link != null)
        {
            Controls.Button button = new Controls.Button(link, Controls.Button.Kind.GHOST).onClick(onLink);
            button.setPreferredSize(new Dimension(button.getPreferredSize().width - 4, 18));
            line.add(button);
        }
        line.setAlignmentX(LEFT_ALIGNMENT);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        add(line);
        headerLine = line;
        return this;
    }

    @Nullable
    private JPanel headerLine;

    /** Clicking the header (not its link) runs {@code action} — used to fold a list. */
    public ListBlock onHeaderClick(Runnable action)
    {
        if (headerLine == null || action == null)
        {
            return this;
        }
        headerLine.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        headerLine.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                action.run();
            }
        });
        return this;
    }

    public ListBlock row(ItemRow row)
    {
        row.first(rows == 0);
        row.setAlignmentX(LEFT_ALIGNMENT);
        add(row);
        rows++;
        return this;
    }

    public ListBlock empty(String sentence)
    {
        JLabel label = Tile.label(sentence, BentoTheme.secondary(), BentoTheme.DIM);
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 9, 10, 9));
        label.setAlignmentX(LEFT_ALIGNMENT);
        add(label);
        return this;
    }

    public ListBlock footer(String text, Color color)
    {
        JLabel label = Tile.label(text, BentoTheme.font(java.awt.Font.PLAIN, BentoTheme.density().micro + 1), color);
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 9, 7, 9));
        label.setAlignmentX(LEFT_ALIGNMENT);
        add(label);
        return this;
    }

    public int rowCount()
    {
        return rows;
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
