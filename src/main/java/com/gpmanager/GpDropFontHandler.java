package com.gpmanager;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import net.runelite.client.config.FontType;
import net.runelite.client.ui.FontManager;

/**
 * Applies floating-drop fonts the Customizable XP Drops way: use {@link FontType},
 * and disable text antialiasing for RuneLite built-in RuneScape bitmap fonts.
 */
public final class GpDropFontHandler
{
    private GpDropFontHandler()
    {
    }

    public static void apply(Graphics2D graphics, FontType fontType)
    {
        if (graphics == null)
        {
            return;
        }
        Font font = fontType == null ? null : fontType.getFont();
        if (font == null)
        {
            font = FontManager.getRunescapeSmallFont();
        }
        if (FontManager.getBuiltInFonts().contains(font.getFamily()))
        {
            graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        }
        graphics.setFont(font);
    }

    public static Font resolve(FontType fontType)
    {
        if (fontType != null && fontType.getFont() != null)
        {
            return fontType.getFont();
        }
        return FontManager.getRunescapeSmallFont();
    }

    /** Map legacy {@link GpDropFontStyle} values into a {@link FontType}. */
    public static FontType fromLegacyStyle(GpDropFontStyle style)
    {
        if (style == null)
        {
            style = GpDropFontStyle.EXPANDED_XP;
        }
        switch (style)
        {
            case RUNESCAPE:
                return fontTypeOf(FontManager.getRunescapeFont(), 16, false);
            case RUNESCAPE_BOLD:
                return fontTypeOf(FontManager.getRunescapeBoldFont(), 16, true);
            case RUNELITE:
                return FontType.BOLD;
            case EXPANDED_XP:
            default:
                return fontTypeOf(FontManager.getRunescapeSmallFont(), 16, false);
        }
    }

    private static FontType fontTypeOf(Font base, int size, boolean bold)
    {
        String family = base == null ? Font.DIALOG : base.getFamily();
        Font derived = base == null
            ? new Font(Font.DIALOG, bold ? Font.BOLD : Font.PLAIN, size)
            : base.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size);
        return new FontType(family, size, bold, false, derived);
    }
}
