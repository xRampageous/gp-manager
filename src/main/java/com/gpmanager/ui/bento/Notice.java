package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.annotation.Nullable;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;

/**
 * One-line state notice: glyph · bold lead · detail · optional right text/chevron.
 * Exists only while its condition is true; the page adds/removes it.
 */
public final class Notice extends Painted
{
    public enum Tone
    {
        PLAIN, ACCENT, WARN, PVP, INFO
    }

    private final String glyph;
    private final String lead;
    private final String detail;
    @Nullable
    private final String right;
    private final Tone tone;
    private Runnable onClick = () -> { };

    public Notice(String glyph, String lead, String detail, @Nullable String right, Tone tone)
    {
        super(AccessibleRole.LABEL);
        this.glyph = glyph == null ? "" : glyph;
        this.lead = lead == null ? "" : lead;
        this.detail = detail == null ? "" : detail;
        this.right = right;
        this.tone = tone == null ? Tone.PLAIN : tone;
        setFocusable(right != null);
        getAccessibleContext().setAccessibleName(this.lead + " " + this.detail);
        setToolTipText(this.lead + (this.detail.isEmpty() ? "" : " · " + this.detail));
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                onClick.run();
            }
        });
    }

    public Notice onClick(Runnable runnable)
    {
        onClick = runnable == null ? () -> { } : runnable;
        return this;
    }

    @Override
    public Dimension getPreferredSize()
    {
        FontMetrics fm = getFontMetrics(BentoTheme.secondary());
        return new Dimension(BentoTheme.CONTENT_WIDTH, fm.getHeight() + 11);
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
            Color border;
            Color tint;
            Color glyphColor;
            switch (tone)
            {
                case ACCENT:
                    border = BentoTheme.accentColor();
                    tint = BentoTheme.accentSoft();
                    glyphColor = BentoTheme.accentColor();
                    break;
                case WARN:
                    border = BentoTheme.WARN_BORDER;
                    tint = null;
                    glyphColor = BentoTheme.WARN;
                    break;
                case PVP:
                    border = new Color(0x6a2236);
                    tint = BentoTheme.PVP_SURFACE;
                    glyphColor = BentoTheme.PVP;
                    break;
                case INFO:
                    border = new Color(0x2a3a4a);
                    tint = BentoTheme.WEALTH_SURFACE;
                    glyphColor = BentoTheme.INFO;
                    break;
                default:
                    border = BentoTheme.BORDER;
                    tint = null;
                    glyphColor = BentoTheme.QUIET;
            }
            int w = getWidth();
            int h = getHeight();
            g2.setColor(BentoTheme.SURFACE);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 9, 9);
            if (tint != null)
            {
                g2.setPaint(new java.awt.GradientPaint(0, 0, tint, w * 0.6f, 0, BentoTheme.SURFACE));
                g2.fillRoundRect(0, 0, w - 1, h - 1, 9, 9);
            }
            g2.setColor(hasFocus() ? BentoTheme.INFO : border);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 9, 9);

            g2.setFont(BentoTheme.secondary());
            FontMetrics fm = g2.getFontMetrics();
            int base = (h + fm.getAscent() - fm.getDescent()) / 2;
            int x = 9;
            g2.setColor(glyphColor);
            g2.drawString(glyph, x, base);
            x += Math.max(14, fm.stringWidth(glyph)) + 6;

            int rightW = 0;
            if (right != null && !right.isEmpty())
            {
                rightW = fm.stringWidth(right);
                g2.setColor(BentoTheme.accentColor());
                g2.drawString(right, w - 9 - rightW, base);
                rightW += 8;
            }
            int avail = w - 9 - rightW - x;
            g2.setFont(BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().secondary));
            FontMetrics bold = g2.getFontMetrics();
            String leadText = StatBlock.fit(lead, bold, avail);
            g2.setColor(BentoTheme.TEXT);
            g2.drawString(leadText, x, base);
            int lx = x + bold.stringWidth(leadText);
            if (!detail.isEmpty() && avail - bold.stringWidth(leadText) > 20)
            {
                g2.setFont(BentoTheme.secondary());
                g2.setColor(BentoTheme.MUTED);
                g2.drawString(StatBlock.fit(" · " + detail, fm, avail - bold.stringWidth(leadText)), lx, base);
            }
        }
        finally
        {
            g2.dispose();
        }
    }
}
