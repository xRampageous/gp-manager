package com.gpmanager.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;

public final class ModernTheme
{
    /** Explicit sidebar family keeps live RuneLite and offline Swing fixtures comparable. */
    public static final String SIDEBAR_FONT_FAMILY = Font.DIALOG;
    // Match the neutral grays and orange emphasis used by RuneLite's stock panels.
    public static final Color BACKGROUND = new Color(35, 35, 35);
    public static final Color SURFACE = new Color(43, 43, 43);
    public static final Color SURFACE_ALT = new Color(50, 50, 50);
    public static final Color SURFACE_HOVER = new Color(62, 62, 62);
    public static final Color BORDER = new Color(78, 78, 78);
    public static final Color BORDER_SOFT = new Color(61, 61, 61);
    public static final Color TEXT = new Color(245, 245, 245);
    public static final Color MUTED = new Color(201, 201, 201);
    public static final Color ACCENT = new Color(255, 152, 31);
    public static final Color ACCENT_DARK = new Color(82, 53, 22);
    public static final Color POSITIVE = new Color(85, 230, 133);
    public static final Color NEGATIVE = new Color(255, 102, 112);
    public static final Color INFO = new Color(111, 167, 255);
    public static final Color WARNING = new Color(236, 184, 88);
    /** Live Revenue / soft mint amounts (distinct from POSITIVE gain green). */
    public static final Color REVENUE = new Color(214, 196, 92);
    public static final Color POSITIVE_SURFACE = new Color(29, 49, 38);
    public static final Color NEGATIVE_SURFACE = new Color(54, 31, 35);
    public static final Color NEUTRAL_SURFACE = new Color(35, 35, 39);
    public static final Color STOP_FILL = new Color(78, 40, 44);
    public static final Color COIN_GOLD = new Color(232, 188, 74);

    public static final Border CARD_BORDER = BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(BORDER),
        BorderFactory.createEmptyBorder(6, 6, 6, 6));

    private ModernTheme()
    {
    }

    public static Font derive(Font base, int style, float size)
    {
        return new Font(SIDEBAR_FONT_FAMILY, style, 12).deriveFont(Math.max(1f, size));
    }

    public static void stylePrimaryButton(AbstractButton button)
    {
        styleButton(button, ACCENT, new Color(28, 24, 18));
    }

    public static void styleSecondaryButton(AbstractButton button)
    {
        styleButton(button, SURFACE_ALT, TEXT);
        button.setBorderPainted(true);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)));
    }

    public static void styleDangerButton(AbstractButton button)
    {
        styleButton(button, STOP_FILL, TEXT);
    }

    public static void styleTabButton(AbstractButton button, boolean selected)
    {
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setBorderPainted(true);
        button.setBackground(BACKGROUND);
        button.setForeground(selected ? ACCENT : MUTED);
        button.setFont(derive(button.getFont(), selected ? Font.BOLD : Font.PLAIN, 12f));
        button.setMargin(new Insets(4, 4, 4, 4));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(selected
            ? BorderFactory.createMatteBorder(0, 0, 2, 0, ACCENT)
            : BorderFactory.createEmptyBorder(0, 0, 2, 0));
    }

    public static void styleIconButton(AbstractButton button)
    {
        styleButton(button, SURFACE_ALT, TEXT);
        button.setMargin(new Insets(2, 5, 2, 5));
        button.setFont(derive(button.getFont(), Font.BOLD, 12f));
    }

    private static void styleButton(AbstractButton button, Color background, Color foreground)
    {
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(true);
        button.setBorderPainted(false);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setMargin(new Insets(2, 6, 2, 6));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(derive(button.getFont(), Font.BOLD, 12f));
    }

    public static void styleField(JTextField field)
    {
        field.setBackground(SURFACE_ALT);
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setSelectionColor(INFO.darker());
        field.setFont(derive(field.getFont(), Font.PLAIN, 12f));
        field.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(5, 6, 5, 6)));
        field.setOpaque(true);
    }

    public static void styleCombo(JComboBox<?> combo)
    {
        combo.setBackground(SURFACE_ALT);
        combo.setForeground(TEXT);
        combo.setOpaque(true);
        combo.setFont(derive(combo.getFont(), Font.PLAIN, 11.3f));
        combo.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(3, 6, 3, 4)));
        combo.setMaximumRowCount(8);
        combo.setRenderer(darkListRenderer());
        if (Boolean.TRUE.equals(combo.getClientProperty("gpmanager.styledCombo")))
        {
            return;
        }
        combo.putClientProperty("gpmanager.styledCombo", Boolean.TRUE);
        combo.setUI(new BasicComboBoxUI()
        {
            @Override
            protected JButton createArrowButton()
            {
                JButton arrow = new JButton()
                {
                    @Override
                    public void paintComponent(Graphics g)
                    {
                        g.setColor(SURFACE_ALT);
                        g.fillRect(0, 0, getWidth(), getHeight());
                        g.setColor(MUTED);
                        int cx = getWidth() / 2;
                        int cy = getHeight() / 2;
                        int[] x = {cx - 4, cx + 4, cx};
                        int[] y = {cy - 2, cy - 2, cy + 3};
                        g.fillPolygon(x, y, 3);
                    }
                };
                arrow.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                arrow.setContentAreaFilled(false);
                arrow.setFocusPainted(false);
                arrow.setOpaque(true);
                arrow.setBackground(SURFACE_ALT);
                arrow.setPreferredSize(new Dimension(18, 18));
                return arrow;
            }

            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus)
            {
                g.setColor(SURFACE_ALT);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }

            @Override
            protected ComboPopup createPopup()
            {
                return new BasicComboPopup(comboBox)
                {
                    @Override
                    protected JScrollPane createScroller()
                    {
                        JScrollPane scroller = super.createScroller();
                        styleScrollBar(scroller.getVerticalScrollBar());
                        scroller.getViewport().setBackground(SURFACE_ALT);
                        list.setBackground(SURFACE_ALT);
                        list.setForeground(TEXT);
                        list.setSelectionBackground(SURFACE_HOVER);
                        list.setSelectionForeground(TEXT);
                        list.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
                        return scroller;
                    }
                };
            }
        });
    }

    private static ListCellRenderer<Object> darkListRenderer()
    {
        return new DefaultListCellRenderer()
        {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus)
            {
                Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setOpaque(true);
                setFont(derive(getFont(), Font.PLAIN, 11.3f));
                setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
                if (isSelected)
                {
                    setBackground(SURFACE_HOVER);
                    setForeground(TEXT);
                }
                else
                {
                    setBackground(SURFACE_ALT);
                    setForeground(TEXT);
                }
                return component;
            }
        };
    }

    public static void styleScrollPane(JScrollPane pane)
    {
        pane.setBorder(BorderFactory.createLineBorder(BORDER_SOFT));
        pane.getViewport().setBackground(SURFACE);
        pane.setBackground(SURFACE);
        pane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        styleScrollBar(pane.getVerticalScrollBar());
    }

    public static void styleScrollBar(JScrollBar scrollBar)
    {
        scrollBar.setPreferredSize(new Dimension(7, 0));
        scrollBar.setMinimumSize(new Dimension(7, 0));
        scrollBar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI()
        {
            @Override protected void configureScrollBarColors()
            {
                thumbColor = BORDER;
                trackColor = BACKGROUND;
            }

            @Override protected JButton createDecreaseButton(int orientation) { return emptyButton(); }
            @Override protected JButton createIncreaseButton(int orientation) { return emptyButton(); }
            private JButton emptyButton()
            {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                button.setMinimumSize(new Dimension(0, 0));
                button.setMaximumSize(new Dimension(0, 0));
                return button;
            }
        });
        scrollBar.setUnitIncrement(16);
        scrollBar.setBorder(BorderFactory.createEmptyBorder());
    }

    public static void styleMutedLabel(JLabel label)
    {
        label.setForeground(MUTED);
        label.setFont(derive(label.getFont(), Font.PLAIN, 12.0f));
    }

    public static void styleTitleLabel(JLabel label)
    {
        label.setForeground(TEXT);
        label.setFont(derive(label.getFont(), Font.BOLD, 13.2f));
    }

    public static void styleSectionLabel(JLabel label)
    {
        label.setForeground(TEXT);
        label.setFont(derive(label.getFont(), Font.BOLD, 12.5f));
    }

    public static void transparent(JComponent component)
    {
        component.setOpaque(false);
    }

    public static Color amountSurface(long amount)
    {
        if (amount > 0L)
        {
            return POSITIVE_SURFACE;
        }
        if (amount < 0L)
        {
            return NEGATIVE_SURFACE;
        }
        return NEUTRAL_SURFACE;
    }

    public static Color amountColor(long amount)
    {
        if (amount > 0L)
        {
            return POSITIVE;
        }
        if (amount < 0L)
        {
            return NEGATIVE;
        }
        return TEXT;
    }
}
