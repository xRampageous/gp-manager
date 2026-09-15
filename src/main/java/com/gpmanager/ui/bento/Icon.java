package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;

/**
 * A small icon for headers, rows and stat cells: an item sprite scaled into the box, or a
 * glyph on a tinted ring. Painted by the caller so every component sizes it the same way.
 */
public final class Icon
{
    public static final int SIZE = 18;

    @Nullable
    private final BufferedImage sprite;
    private final String glyph;
    private final Color tint;
    private final boolean control;

    private Icon(@Nullable BufferedImage sprite, String glyph, Color tint, boolean control)
    {
        this.sprite = sprite;
        this.glyph = glyph == null ? "" : glyph;
        this.tint = tint == null ? BentoTheme.MUTED : tint;
        this.control = control;
    }

    /**
     * Decorative glyphs are gone from the panels (owner's call after the first live session: only real
     * game art reads well next to it). This returns an empty icon so every section header, notice and
     * row simply omits its box; the rail and page-bar buttons draw their own glyphs and are unaffected.
     */
    public static Icon glyph(String glyph, Color tint)
    {
        return new Icon(null, "", tint, false);
    }

    /** Game interface art by sprite id, bound by the panel (null in previews and headless tests). */
    private static volatile java.util.function.IntFunction<BufferedImage> gameArt = id -> null;

    public static void bindGameArt(@Nullable java.util.function.IntFunction<BufferedImage> art)
    {
        gameArt = art == null ? id -> null : art;
    }

    /**
     * Real interface art (a side-stone icon, a GE or bank button) for a section header — the trial
     * that replaced glyphs: only game art sits well beside the panels. Empty until the sprite loads.
     */
    public static Icon art(int spriteId, Color tint)
    {
        // Trialled and turned down: interface art on headers did not earn its place either. The
        // panels carry sprites of things (items, activities) and nothing else.
        return new Icon(null, "", tint, false);
    }

    /** A glyph that is a control state (a pick mark). */
    public static Icon control(String glyph, Color tint)
    {
        return new Icon(null, glyph, tint, true);
    }

    /** Real game art (an item or activity sprite); with no sprite there is no icon, never a glyph stand-in. */
    public static Icon sprite(@Nullable BufferedImage sprite, String fallbackGlyph, Color tint)
    {
        return new Icon(sprite, "", tint, false);
    }

    public boolean isEmpty()
    {
        return sprite == null && glyph.isEmpty();
    }

    /** A control glyph (the Sessions pick mark) that must show even where decoration would not. */
    public boolean isControlForTest()
    {
        return control && !glyph.isEmpty();
    }

    /** True for real game art (an item or interface sprite), false for a glyph. */
    public boolean hasSprite()
    {
        return sprite != null;
    }

    /** Paints into a {@code size} box whose top-left is (x, y). */
    public void paint(Graphics2D g2, int x, int y, int size)
    {
        paint(g2, x, y, size, true);
    }

    /** As {@link #paint(Graphics2D, int, int, int)}; {@code ring} false paints a bare glyph (inside a box). */
    public void paint(Graphics2D g2, int x, int y, int size, boolean ring)
    {
        if (sprite != null)
        {
            int w = sprite.getWidth();
            int h = sprite.getHeight();
            if (w > 0 && h > 0)
            {
                double scale = Math.min((double) size / w, (double) size / h);
                int dw = (int) Math.round(w * scale);
                int dh = (int) Math.round(h * scale);
                g2.drawImage(sprite, x + (size - dw) / 2, y + (size - dh) / 2, dw, dh, null);
                return;
            }
        }
        if (glyph.isEmpty())
        {
            return;
        }
        if (ring)
        {
            g2.setColor(BentoTheme.RING);
            g2.fillOval(x, y, size, size);
            g2.setColor(BentoTheme.withAlpha(tint, 40));
            g2.fillOval(x, y, size, size);
        }
        g2.setFont(BentoTheme.symbol(Math.max(9f, size * (ring ? 0.7f : 0.85f))));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(tint);
        int tx = x + (size - fm.stringWidth(glyph)) / 2;
        int ty = y + (size - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(glyph, tx, ty);
    }
}
