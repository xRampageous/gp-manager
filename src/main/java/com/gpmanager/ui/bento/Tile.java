package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * A rounded content tile (vertical stack) plus the small text helpers every page uses:
 * micro headers, key/value lines, secondary text. Layout is vertical BoxLayout so tiles
 * hug their content; width always stretches to the column.
 */
public final class Tile extends Surface
{
    public Tile()
    {
        super(null);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(LEFT_ALIGNMENT);
    }

    /**
     * §13 section header: optional icon, sentence-case bold title, optional right text; when
     * {@code onClick} is set the right text reads as a link and the whole line navigates.
     */
    public Tile section(@Nullable Icon icon, String title, @Nullable String right, @Nullable Runnable onClick)
    {
        JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.setOpaque(false);
        line.setAlignmentX(LEFT_ALIGNMENT);
        if (icon != null && !icon.isEmpty())
        {
            line.add(new IconLabel(icon));
            line.add(Box.createHorizontalStrut(6));
        }
        line.add(label(title, BentoTheme.font(Font.BOLD, BentoTheme.density().body), BentoTheme.TEXT));
        line.add(Box.createHorizontalGlue());
        if (onClick != null)
        {
            Controls.Button button = new Controls.Button((right == null || right.isEmpty() ? "" : right + " ") + "›", Controls.Button.Kind.GHOST).onClick(onClick);
            button.setPreferredSize(new Dimension(button.getPreferredSize().width - 4, 18));
            line.add(button);
        }
        else if (right != null && !right.isEmpty())
        {
            line.add(label(right, BentoTheme.secondary(), BentoTheme.MUTED));
        }
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
        add(line);
        add(Box.createVerticalStrut(4));
        return this;
    }

    /** A small component that paints one {@link Icon}. */
    public static final class IconLabel extends javax.swing.JComponent
    {
        private final Icon icon;

        public IconLabel(Icon icon)
        {
            this.icon = icon;
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize()
        {
            return new Dimension(Icon.SIZE, Icon.SIZE);
        }

        @Override
        public Dimension getMaximumSize()
        {
            return getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize()
        {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(java.awt.Graphics g)
        {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try
            {
                BentoTheme.quality(g2);
                icon.paint(g2, 0, 0, Icon.SIZE);
            }
            finally
            {
                g2.dispose();
            }
        }
    }

    /** A rounded, tinted box with a bare glyph or sprite inside (§13 section and row icons). */
    public static final class IconBox extends javax.swing.JComponent
    {
        public static final int SIZE = 26;
        private final Icon icon;
        private final Color tint;

        public IconBox(Icon icon, Color tint)
        {
            this.icon = icon;
            this.tint = tint == null ? BentoTheme.MUTED : tint;
            setOpaque(false);
        }

        public boolean isEmpty()
        {
            return icon.isEmpty();
        }

        @Override
        public Dimension getPreferredSize()
        {
            return icon.isEmpty() ? new Dimension(0, 0) : new Dimension(SIZE, SIZE);
        }

        @Override
        public Dimension getMaximumSize()
        {
            return getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize()
        {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(java.awt.Graphics g)
        {
            if (icon.isEmpty())
            {
                return;
            }
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try
            {
                BentoTheme.quality(g2);
                g2.setColor(BentoTheme.withAlpha(tint, 34));
                g2.fillRoundRect(0, 0, SIZE, SIZE, 8, 8);
                icon.paint(g2, (SIZE - Icon.SIZE) / 2, (SIZE - Icon.SIZE) / 2, Icon.SIZE, false);
            }
            finally
            {
                g2.dispose();
            }
        }
    }

    /** Uppercase micro header line with an optional right-aligned text. */
    public Tile header(String text, @Nullable String right, @Nullable Color rightColor)
    {
        add(line(micro(text, BentoTheme.DIM), right == null ? null : label(right, BentoTheme.secondary(), rightColor == null ? BentoTheme.MUTED : rightColor)));
        return this;
    }

    public Tile kv(String key, String value)
    {
        return kv(key, value, BentoTheme.TEXT);
    }

    public Tile kv(String key, String value, Color valueColor)
    {
        // Key and value share the interior; the value keeps at least a third, the key the rest.
        java.awt.FontMetrics fm = getFontMetrics(BentoTheme.secondary());
        int width = interiorWidth() - 8;
        String v = value == null ? "" : value;
        String k = key == null ? "" : key;
        if (fm.stringWidth(k) + fm.stringWidth(v) > width)
        {
            int valueMax = Math.max(width / 2, width - fm.stringWidth(k));
            v = StatBlock.fit(v, fm, valueMax);
            k = StatBlock.fit(k, fm, width - fm.stringWidth(v));
        }
        JLabel keyLabel = label(k, BentoTheme.secondary(), BentoTheme.MUTED);
        JLabel valueLabel = label(v, BentoTheme.secondary(), valueColor);
        if (!k.equals(key))
        {
            keyLabel.setToolTipText(key);
        }
        if (!v.equals(value))
        {
            valueLabel.setToolTipText(value);
        }
        add(line(keyLabel, valueLabel));
        return this;
    }

    public Tile text(String value, Color color)
    {
        JLabel label = label(value, BentoTheme.secondary(), color);
        label.setAlignmentX(LEFT_ALIGNMENT);
        add(label);
        return this;
    }

    /** Body copy wrapped by measurement so it never clips; width is the usable tile interior. */
    public Tile paragraph(String value, Font font, Color color, int widthPx)
    {
        for (String line : wrap(value, new JLabel().getFontMetrics(font), widthPx))
        {
            JLabel label = label(line, font, color);
            label.setAlignmentX(LEFT_ALIGNMENT);
            add(label);
        }
        return this;
    }

    /** Greedy word wrap against real font metrics. */
    public static java.util.List<String> wrap(String text, java.awt.FontMetrics fm, int widthPx)
    {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : (text == null ? "" : text.trim()).split("\\s+"))
        {
            if (word.isEmpty())
            {
                continue;
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) <= widthPx || line.length() == 0)
            {
                line.setLength(0);
                line.append(candidate);
            }
            else
            {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0)
        {
            lines.add(line.toString());
        }
        return lines;
    }

    /** Interior width of a tile in the content column (column minus tile padding and border). */
    public static int interiorWidth()
    {
        return BentoTheme.MIN_CONTENT_WIDTH - 2 * (BentoTheme.density().pad + 1) - 8;
    }

    public Tile gap(int px)
    {
        add(Box.createVerticalStrut(px));
        return this;
    }

    public Tile row(Component component)
    {
        if (component instanceof javax.swing.JComponent)
        {
            ((javax.swing.JComponent) component).setAlignmentX(LEFT_ALIGNMENT);
        }
        add(component);
        return this;
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    // ----- helpers -----

    /** A JLabel painted with the kit's text hints (fractional metrics keep small text evenly spaced). */
    public static final class Label extends JLabel
    {
        Label(String text)
        {
            super(text);
        }

        /** Measured with the same fractional metrics the paint uses, so the text never outgrows the label. */
        @Override
        public Dimension getPreferredSize()
        {
            Dimension d = super.getPreferredSize();
            String text = getText();
            if (text == null || text.isEmpty() || getFont() == null)
            {
                return d;
            }
            java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(null, true, true);
            int textW = (int) Math.ceil(getFont().getStringBounds(text, frc).getWidth());
            java.awt.Insets in = getInsets();
            int iconW = getIcon() == null ? 0 : getIcon().getIconWidth() + getIconTextGap();
            return new Dimension(Math.max(d.width, textW + iconW + in.left + in.right + 1), d.height);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g)
        {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try
            {
                BentoTheme.quality(g2);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                super.paintComponent(g2);
            }
            finally
            {
                g2.dispose();
            }
        }
    }

    public static JLabel micro(String text, Color color)
    {
        JLabel label = new Label(text == null ? "" : text.toUpperCase());
        label.setFont(BentoTheme.fontFor(label.getText(), BentoTheme.micro()));
        label.setForeground(color);
        return label;
    }

    public static JLabel label(String text, Font font, Color color)
    {
        JLabel label = new Label(text == null ? "" : text);
        label.setFont(BentoTheme.fontFor(label.getText(), font));
        label.setForeground(color);
        return label;
    }

    /** Left component, right component pushed to the far edge. */
    public static JPanel line(Component left, @Nullable Component right)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(left);
        panel.add(Box.createHorizontalGlue());
        if (right != null)
        {
            panel.add(right);
        }
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    /** Wrapping row of small controls with a fixed gap. */
    public static JPanel buttons(Component... components)
    {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        for (Component c : components)
        {
            panel.add(c);
        }
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height + 4));
        return panel;
    }
}
