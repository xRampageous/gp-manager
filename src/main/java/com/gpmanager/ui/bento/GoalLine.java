package com.gpmanager.ui.bento;

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
 * One-line goal: {@code ◎ 200k ▬▬▬▬▭ 62% · ~18m} with a milestone tick. Click opens the
 * goal editor supplied by the page. Hidden (zero height) when no goal is set.
 */
public final class GoalLine extends Painted
{
    private String label = "";
    private double fraction;
    private double milestone = -1;
    private String progressText = "";
    @Nullable
    private String etaText;
    private boolean present;
    private Runnable onClick = () -> { };

    public GoalLine()
    {
        super(AccessibleRole.PUSH_BUTTON);
        setFocusable(true);
        getAccessibleContext().setAccessibleName("Goal");
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                onClick.run();
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

    public GoalLine onClick(Runnable runnable)
    {
        onClick = runnable == null ? () -> { } : runnable;
        return this;
    }

    /**
     * @param goalLabel short goal text ({@code 200k}, {@code 15 kills})
     * @param progressFraction 0..1
     * @param milestoneFraction 0..1 or negative for none
     * @param progress e.g. {@code 62%} or {@code 12/15}
     * @param eta e.g. {@code ~18m}, null when unknown
     */
    public GoalLine set(String goalLabel, double progressFraction, double milestoneFraction, String progress,
        @Nullable String eta)
    {
        label = goalLabel == null ? "" : goalLabel;
        fraction = Math.max(0d, Math.min(1d, progressFraction));
        milestone = milestoneFraction;
        progressText = progress == null ? "" : progress;
        etaText = eta;
        present = true;
        setToolTipText(label + " · " + progressText + (eta == null ? "" : " · " + eta));
        revalidate();
        repaint();
        return this;
    }

    public GoalLine clear()
    {
        present = false;
        setToolTipText(null);
        revalidate();
        repaint();
        return this;
    }

    public boolean isPresent()
    {
        return present;
    }

    private static int pad()
    {
        return BentoTheme.density().pad;
    }

    @Override
    public Dimension getPreferredSize()
    {
        if (!present)
        {
            return new Dimension(0, 0);
        }
        FontMetrics fm = getFontMetrics(BentoTheme.bodyBold());
        return new Dimension(BentoTheme.CONTENT_WIDTH, pad() + fm.getHeight() + 8 + 6 + pad());
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        if (!present)
        {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try
        {
            BentoTheme.quality(g2);
            int w = getWidth();
            int h = getHeight();
            int radius = BentoTheme.density().radius;
            g2.setColor(BentoTheme.SURFACE);
            g2.fillRoundRect(0, 0, w - 1, h - 1, radius, radius);
            g2.setColor(hasFocus() ? BentoTheme.INFO : BentoTheme.BORDER);
            g2.drawRoundRect(0, 0, w - 1, h - 1, radius, radius);

            int x = pad();
            int right = w - pad();
            FontMetrics bold = g2.getFontMetrics(BentoTheme.bodyBold());
            int base = pad() + bold.getAscent();
            // Goal · 200k — a small accent caption then the figure; no glyph (panels carry no decorative icons).
            g2.setFont(BentoTheme.micro());
            g2.setColor(BentoTheme.accentColor());
            g2.drawString("GOAL", x, base);
            x += g2.getFontMetrics().stringWidth("GOAL") + 6;
            g2.setFont(BentoTheme.bodyBold());
            g2.setColor(BentoTheme.TEXT);
            g2.drawString(label, x, base);

            // 62% · ETA 2h 18m on the right.
            String rightText = progressText + (etaText == null ? "" : " · " + ("reached".equals(etaText) ? etaText : "ETA " + etaText.replace("~", "")));
            g2.setFont(BentoTheme.secondary());
            FontMetrics sm = g2.getFontMetrics();
            g2.setColor(BentoTheme.MUTED);
            g2.drawString(rightText, right - sm.stringWidth(rightText), base);

            // Bar.
            int barY = pad() + bold.getHeight() + 8;
            int barX = pad();
            int barW = right - barX;
            g2.setColor(BentoTheme.SOFT);
            g2.fillRoundRect(barX, barY, barW, 6, 6, 6);
            int fillW = (int) Math.round(barW * fraction);
            if (fillW > 0)
            {
                g2.setColor(BentoTheme.accentColor());
                g2.fillRoundRect(barX, barY, Math.max(6, fillW), 6, 6, 6);
            }
            if (milestone >= 0d && milestone <= 1d)
            {
                g2.setColor(BentoTheme.DIM);
                g2.fillRect(barX + (int) Math.round(barW * milestone) - 1, barY - 1, 2, 8);
            }
        }
        finally
        {
            g2.dispose();
        }
    }
}
