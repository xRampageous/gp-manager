package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * The minimal receipt row: sprite · name (qty dim inside the name span) · verb · change.
 * Name always wins the width fight; qty and verb ellipsise first. Click toggles the
 * page-supplied expand action; right-click opens the page-supplied menu.
 */
public final class ItemRow extends Painted
{
    private static final int SPRITE = 18;

    @Nullable
    private BufferedImage sprite;
    private String name = "";
    @Nullable
    private String qty;
    @Nullable
    private String verb;
    private String value = "";
    private Color valueColor = BentoTheme.TEXT;
    @Nullable
    private String tag;
    private Color tagColor = BentoTheme.QUIET;
    private boolean open;
    private boolean first;
    private boolean card;
    private boolean hover;
    private Runnable onClick = () -> { };
    private Runnable onContext = () -> { };

    public ItemRow()
    {
        super(AccessibleRole.LIST_ITEM);
        setFocusable(true);
        MouseAdapter mouse = new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (SwingUtilities.isRightMouseButton(e))
                {
                    onContext.run();
                }
                else
                {
                    onClick.run();
                }
                requestFocusInWindow();
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
        };
        addMouseListener(mouse);
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
                else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_CONTEXT_MENU)
                {
                    onContext.run();
                    e.consume();
                }
            }
        });
    }

    public ItemRow sprite(@Nullable BufferedImage image)
    {
        sprite = image;
        repaint();
        return this;
    }

    public ItemRow name(String text, @Nullable String quantity)
    {
        name = text == null ? "" : text;
        qty = quantity;
        getAccessibleContext().setAccessibleName(name + (quantity == null ? "" : " " + quantity) + " " + value);
        repaint();
        return this;
    }

    public ItemRow verb(@Nullable String text)
    {
        verb = text;
        repaint();
        return this;
    }

    public ItemRow value(String text, Color color)
    {
        value = text == null ? "" : text;
        valueColor = color == null ? BentoTheme.TEXT : color;
        repaint();
        return this;
    }

    public ItemRow value(long amount)
    {
        return value(Fmt.signed(amount), BentoTheme.signColor(amount));
    }

    public ItemRow neutral()
    {
        return value("0", BentoTheme.DIM);
    }

    public ItemRow tag(@Nullable String text, Color color)
    {
        tag = text;
        tagColor = color == null ? BentoTheme.QUIET : color;
        repaint();
        return this;
    }

    public ItemRow open(boolean value)
    {
        open = value;
        repaint();
        return this;
    }

    /** First row in a list has no top divider. */
    public ItemRow first(boolean value)
    {
        first = value;
        repaint();
        return this;
    }

    public ItemRow onClick(Runnable runnable)
    {
        onClick = runnable == null ? () -> { } : runnable;
        return this;
    }

    public ItemRow onContext(Runnable runnable)
    {
        onContext = runnable == null ? () -> { } : runnable;
        return this;
    }

    public String getName()
    {
        return name;
    }

    @Override
    public Dimension getPreferredSize()
    {
        FontMetrics fm = getFontMetrics(BentoTheme.body());
        return new Dimension(BentoTheme.CONTENT_WIDTH, Math.max(SPRITE, fm.getHeight()) + (card ? 18 : 10));
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** Its own rounded card (the Ledger's item rows) instead of a divided list line. */
    public ItemRow card(boolean value)
    {
        card = value;
        revalidate();
        repaint();
        return this;
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
            if (card)
            {
                g2.setColor(open || hover ? BentoTheme.HOVER : BentoTheme.ALT);
                g2.fillRoundRect(0, 0, w - 1, h - 1, 9, 9);
                g2.setColor(BentoTheme.BORDER);
                g2.drawRoundRect(0, 0, w - 1, h - 1, 9, 9);
            }
            else if (open || hover)
            {
                g2.setColor(open ? BentoTheme.ALT : BentoTheme.HOVER);
                g2.fillRect(0, 0, w, h);
            }
            if (!first && !card)
            {
                g2.setColor(BentoTheme.SOFT);
                g2.drawLine(9, 0, w - 9, 0);
            }
            int x = 9;
            int cy = h / 2;
            if (sprite != null)
            {
                int sw = Math.min(SPRITE, sprite.getWidth());
                int sh = Math.min(SPRITE, sprite.getHeight());
                g2.drawImage(sprite, x + (SPRITE - sw) / 2, cy - sh / 2, sw, sh, null);
            }
            else
            {
                g2.setColor(BentoTheme.ALT);
                g2.fillRoundRect(x, cy - SPRITE / 2, SPRITE, SPRITE, 4, 4);
                g2.setColor(BentoTheme.BORDER);
                g2.drawRoundRect(x, cy - SPRITE / 2, SPRITE, SPRITE, 4, 4);
            }
            x += SPRITE + 8;

            g2.setFont(BentoTheme.font(java.awt.Font.BOLD, BentoTheme.density().body));
            FontMetrics bold = g2.getFontMetrics();
            int base = cy + (bold.getAscent() - bold.getDescent()) / 2;

            int vw = bold.stringWidth(value);
            int right = w - 9;
            g2.setColor(valueColor);
            g2.drawString(value, right - vw, base);
            right -= vw + 8;

            g2.setFont(BentoTheme.font(java.awt.Font.PLAIN, BentoTheme.density().secondary - 1));
            FontMetrics small = g2.getFontMetrics();
            int reserved = 0;
            String verbText = verb;
            if (verbText != null && !verbText.isEmpty())
            {
                reserved = small.stringWidth(verbText) + 8;
                // Name and quantity outrank the verb: when all three cannot fit, the verb yields.
                int needed = bold.stringWidth(name) + (qty == null || qty.isEmpty() ? 0 : small.stringWidth(" " + qty) + 4);
                if (right - x - reserved < needed)
                {
                    reserved = 0;
                    verbText = null;
                }
            }
            int nameAvail = right - x - reserved;
            String nameText = StatBlock.fit(name, bold, Math.max(24, nameAvail));
            g2.setColor(BentoTheme.TEXT);
            g2.drawString(nameText, x, base);
            int nx = x + bold.stringWidth(nameText);
            int spare = nameAvail - bold.stringWidth(nameText);
            if (tag != null && spare > 24)
            {
                g2.setFont(BentoTheme.font(java.awt.Font.BOLD, 8.5f));
                FontMetrics tf = g2.getFontMetrics();
                String upper = tag.toUpperCase();
                int tw = tf.stringWidth(upper) + 8;
                g2.setColor(BentoTheme.withAlpha(tagColor, 50));
                g2.fillRoundRect(nx + 6, cy - tf.getHeight() / 2, tw, tf.getHeight(), 4, 4);
                g2.setColor(tagColor);
                g2.drawString(upper, nx + 10, cy + (tf.getAscent() - tf.getDescent()) / 2);
                nx += tw + 6;
                spare -= tw + 6;
                g2.setFont(BentoTheme.font(java.awt.Font.PLAIN, BentoTheme.density().secondary - 1));
            }
            if (qty != null && !qty.isEmpty() && spare > 16)
            {
                g2.setColor(BentoTheme.DIM);
                g2.drawString(StatBlock.fit(" " + qty, small, spare), nx, base);
            }
            if (verbText != null && !verbText.isEmpty())
            {
                g2.setColor(BentoTheme.DIM);
                g2.drawString(verbText, right - small.stringWidth(verbText), base);
            }
            if (hasFocus())
            {
                g2.setColor(BentoTheme.INFO);
                g2.drawRoundRect(1, 1, w - 3, h - 3, 6, 6);
            }
        }
        finally
        {
            g2.dispose();
        }
    }
}
