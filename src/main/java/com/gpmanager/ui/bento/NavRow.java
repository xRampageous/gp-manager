package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.accessibility.AccessibleRole;
import javax.annotation.Nullable;

/**
 * A card row that leads somewhere (SIDEBAR_BENTO.md §13.1): icon · title over subtitle ·
 * right value · chevron. Used for notices ("Notable drop · Draconic visage"), the encounter
 * row ("Vorkath · 41 · 58.7k/kill") and Tools navigation. Tone tints the border and icon.
 */
public final class NavRow extends Painted
{
    public enum Tone
    {
        PLAIN, ACCENT, WARN, PVP, INFO, NEGATIVE
    }

    private static final int PAD = 10;
    /** The icon box: a rounded, tinted square the icon sits in (§13 reference). */
    static final int BOX = 28;

    private Icon icon = Icon.glyph("", BentoTheme.MUTED);
    private String title = "";
    private String subtitle = "";
    private String right = "";
    private Color rightColor = BentoTheme.TEXT;
    private Tone tone = Tone.PLAIN;
    private boolean chevron = true;
    private Runnable onClick = () -> { };
    private boolean hover;

    public NavRow()
    {
        super(AccessibleRole.PUSH_BUTTON);
        setOpaque(false);
        setFocusable(true);
        setAlignmentX(LEFT_ALIGNMENT);
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (isEnabled())
                {
                    onClick.run();
                    requestFocusInWindow();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e)
            {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                hover = false;
                repaint();
            }
        });
        addKeyListener(new java.awt.event.KeyAdapter()
        {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e)
            {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER || e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE)
                {
                    onClick.run();
                    e.consume();
                }
            }
        });
    }

    public NavRow icon(Icon value)
    {
        icon = value == null ? Icon.glyph("", BentoTheme.MUTED) : value;
        return this;
    }

    public NavRow text(String titleText, @Nullable String subtitleText)
    {
        title = titleText == null ? "" : titleText;
        subtitle = subtitleText == null ? "" : subtitleText;
        getAccessibleContext().setAccessibleName(title + (subtitle.isEmpty() ? "" : " · " + subtitle));
        revalidate();
        repaint();
        return this;
    }

    public NavRow right(@Nullable String value, @Nullable Color color)
    {
        right = value == null ? "" : value;
        rightColor = color == null ? BentoTheme.TEXT : color;
        repaint();
        return this;
    }

    public NavRow tone(Tone value)
    {
        tone = value == null ? Tone.PLAIN : value;
        repaint();
        return this;
    }

    public NavRow chevron(boolean show)
    {
        chevron = show;
        repaint();
        return this;
    }

    public NavRow onClick(Runnable runnable)
    {
        onClick = runnable == null ? () -> { } : runnable;
        setCursor(java.awt.Cursor.getPredefinedCursor(runnable == null ? java.awt.Cursor.DEFAULT_CURSOR : java.awt.Cursor.HAND_CURSOR));
        return this;
    }

    private Color toneColor()
    {
        switch (tone)
        {
            case ACCENT:
                return BentoTheme.accentColor();
            case WARN:
                return BentoTheme.WARN;
            case PVP:
                return BentoTheme.PVP;
            case INFO:
                return BentoTheme.INFO;
            case NEGATIVE:
                return BentoTheme.NEGATIVE;
            default:
                return BentoTheme.MUTED;
        }
    }

    @Override
    public Dimension getPreferredSize()
    {
        FontMetrics t = getFontMetrics(BentoTheme.bodyBold());
        FontMetrics s = getFontMetrics(BentoTheme.secondary());
        int text = subtitle.isEmpty() ? t.getHeight() : t.getHeight() + s.getHeight();
        return new Dimension(BentoTheme.MIN_CONTENT_WIDTH, Math.max(icon.isEmpty() ? 0 : BOX, text) + 2 * PAD);
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
            Color tint = toneColor();
            g2.setColor(hover ? BentoTheme.HOVER : BentoTheme.SURFACE);
            g2.fillRoundRect(0, 0, w - 1, h - 1, radius, radius);
            g2.setColor(tone == Tone.PLAIN ? BentoTheme.BORDER : BentoTheme.withAlpha(tint, 70));
            g2.drawRoundRect(0, 0, w - 1, h - 1, radius, radius);

            int x = PAD;
            if (!icon.isEmpty())
            {
                int by = (h - BOX) / 2;
                g2.setColor(BentoTheme.withAlpha(tint, tone == Tone.PLAIN ? 22 : 34));
                g2.fillRoundRect(x, by, BOX, BOX, 8, 8);
                icon.paint(g2, x + (BOX - Icon.SIZE) / 2, by + (BOX - Icon.SIZE) / 2, Icon.SIZE, false);
                x += BOX + 10;
            }

            int rightEdge = w - PAD;
            if (chevron)
            {
                g2.setFont(BentoTheme.font(Font.PLAIN, BentoTheme.density().body + 1));
                FontMetrics cm = g2.getFontMetrics();
                g2.setColor(BentoTheme.DIM);
                g2.drawString("›", rightEdge - cm.stringWidth("›"), (h - cm.getHeight()) / 2 + cm.getAscent());
                rightEdge -= cm.stringWidth("›") + 6;
            }
            g2.setFont(BentoTheme.fontFor(right, BentoTheme.bodyBold()));
            FontMetrics tm = g2.getFontMetrics();
            if (!right.isEmpty())
            {
                int rw = tm.stringWidth(right);
                g2.setColor(rightColor);
                g2.drawString(right, rightEdge - rw, (h - tm.getHeight()) / 2 + tm.getAscent()
                    - (subtitle.isEmpty() ? 0 : 0));
                rightEdge -= rw + 8;
            }
            int avail = Math.max(24, rightEdge - x);
            FontMetrics sm = getFontMetrics(BentoTheme.secondary());
            int block = subtitle.isEmpty() ? tm.getHeight() : tm.getHeight() + sm.getHeight();
            int ty = (h - block) / 2;
            g2.setFont(BentoTheme.fontFor(title, BentoTheme.bodyBold()));
            tm = g2.getFontMetrics();
            g2.setColor(isEnabled() ? BentoTheme.TEXT : BentoTheme.DIM);
            g2.drawString(StatBlock.fit(title, tm, avail), x, ty + tm.getAscent());
            if (!subtitle.isEmpty())
            {
                g2.setFont(BentoTheme.fontFor(subtitle, BentoTheme.secondary()));
                sm = g2.getFontMetrics();
                g2.setColor(BentoTheme.MUTED);
                g2.drawString(StatBlock.fit(subtitle, sm, avail), x, ty + tm.getHeight() + sm.getAscent());
            }
            if (hasFocus())
            {
                g2.setColor(BentoTheme.withAlpha(BentoTheme.INFO, 160));
                g2.drawRoundRect(1, 1, w - 3, h - 3, radius, radius);
            }
        }
        finally
        {
            g2.dispose();
        }
    }
}
