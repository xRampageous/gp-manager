package com.gpmanager.ui;

import com.gpmanager.HudPlusTextSize;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.FontManager;

/**
 * Shared HUD+ typography and icon slot sizes so Trip Beacon tray rows and any
 * legacy dedicated-hud paths stay visually aligned at Small / Normal / Large.
 * Uses discrete RuneScape bitmap faces (no fractional {@code deriveFont}) so
 * glyphs stay sharp on the client overlay.
 */
public final class HudPlusLayout
{
    /** Baseline icon slot when text size is Normal — fills near-native OSRS sprites. */
    public static final int ICON_NORMAL = 24;

    private HudPlusLayout()
    {
    }

    public static int iconSlot(HudPlusTextSize textSize)
    {
        if (textSize == HudPlusTextSize.LARGE)
        {
            return 28;
        }
        if (textSize == HudPlusTextSize.SMALL)
        {
            return 20;
        }
        return ICON_NORMAL;
    }

    /**
     * Discrete RS faces — stretching SmallFont with deriveFont is what made
     * Normal/Large look soft/blurry in live HUD+.
     */
    public static Font resolveFont(HudPlusTextSize textSize)
    {
        if (textSize == HudPlusTextSize.LARGE)
        {
            return FontManager.getRunescapeBoldFont();
        }
        if (textSize == HudPlusTextSize.SMALL)
        {
            return FontManager.getRunescapeSmallFont();
        }
        return FontManager.getRunescapeFont();
    }

    /** Bitmap-font + nearest-neighbor sprite hints for overlay paint. */
    public static void applyCrispHints(Graphics2D g)
    {
        if (g == null)
        {
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
    }

    /** Item icon into a square slot (no plate). */
    public static void drawIcon(Graphics2D g, BufferedImage sprite, int x, int y, int slot)
    {
        if (g == null || sprite == null || slot <= 0)
        {
            return;
        }
        drawSprite(g, sprite, x, y, slot);
    }

    /**
     * Draws an item sprite into {@code slot} by fitting opaque content
     * (contain + center, nearest-neighbor). Always scales so the longer content
     * edge equals the slot — small-padded and full canvases look the same size.
     */
    public static void drawSprite(Graphics2D g, BufferedImage sprite, int x, int y, int slot)
    {
        if (g == null || sprite == null || slot <= 0)
        {
            return;
        }
        int sw = sprite.getWidth();
        int sh = sprite.getHeight();
        if (sw <= 0 || sh <= 0)
        {
            return;
        }
        Rectangle src = opaqueBounds(sprite);
        if (src == null || src.width <= 0 || src.height <= 0)
        {
            return;
        }
        Dimension dest = fittedSize(src.width, src.height, slot);
        int dw = dest.width;
        int dh = dest.height;
        int dx = x + (slot - dw) / 2;
        int dy = y + (slot - dh) / 2;
        Object oldInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(
            sprite,
            dx, dy, dx + dw, dy + dh,
            src.x, src.y, src.x + src.width, src.y + src.height,
            null);
        if (oldInterp != null)
        {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
        }
    }

    /**
     * Destination size after contain-fit into {@code slot}. Longer edge equals
     * {@code slot}; aspect ratio preserved. Package-visible for tests.
     */
    static Dimension fittedSize(int contentW, int contentH, int slot)
    {
        if (slot <= 0 || contentW <= 0 || contentH <= 0)
        {
            return new Dimension(0, 0);
        }
        if (contentW >= contentH)
        {
            int dw = slot;
            int dh = Math.max(1, Math.round(contentH * (slot / (float) contentW)));
            return new Dimension(dw, dh);
        }
        int dh = slot;
        int dw = Math.max(1, Math.round(contentW * (slot / (float) contentH)));
        return new Dimension(dw, dh);
    }

    /**
     * Opaque (alpha &gt; 0) content bounds, or full image if every pixel is clear.
     * Package-visible for tests.
     */
    static Rectangle opaqueBounds(BufferedImage sprite)
    {
        if (sprite == null)
        {
            return null;
        }
        int w = sprite.getWidth();
        int h = sprite.getHeight();
        if (w <= 0 || h <= 0)
        {
            return new Rectangle(0, 0, 0, 0);
        }
        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                if (((sprite.getRGB(x, y) >>> 24) & 0xFF) > 0)
                {
                    if (x < minX)
                    {
                        minX = x;
                    }
                    if (y < minY)
                    {
                        minY = y;
                    }
                    if (x > maxX)
                    {
                        maxX = x;
                    }
                    if (y > maxY)
                    {
                        maxY = y;
                    }
                }
            }
        }
        if (maxX < minX || maxY < minY)
        {
            return new Rectangle(0, 0, w, h);
        }
        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }
}
