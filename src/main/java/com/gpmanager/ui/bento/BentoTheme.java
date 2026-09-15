package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Design tokens for the Bento sidebar (docs/design/SIDEBAR_BENTO.md §2). Everything the
 * kit paints comes from here so density and accent can change in one place.
 */
public final class BentoTheme
{
    /** Owned panel width once PluginPanel wrapping is off and the slim scrollbar is used. */
    public static final int OWNED_WIDTH = 242;
    public static final int SCROLLBAR_WIDTH = 6;
    public static final int CONTENT_WIDTH = OWNED_WIDTH - SCROLLBAR_WIDTH;
    /** Minimum content width every component must survive (the old PluginPanel width). */
    public static final int MIN_CONTENT_WIDTH = 225;

    // §13.1 card language: page, card, raised, border; neutral text ramp; semantic colours.
    public static final Color BG = new Color(0x0a0b0c);
    public static final Color SURFACE = new Color(0x0f1113);
    public static final Color ALT = new Color(0x14171a);
    public static final Color HOVER = new Color(0x1a1e22);
    public static final Color BORDER = new Color(0x1e2226);
    public static final Color SOFT = new Color(0x171a1d);
    public static final Color TEXT = new Color(0xe8ecef);
    public static final Color MUTED = new Color(0x9aa3ab);
    public static final Color DIM = new Color(0x5c646b);
    public static final Color POSITIVE = new Color(0x35d6a3);
    public static final Color NEGATIVE = new Color(0xff6b6b);
    public static final Color WARN = new Color(0xf2b134);
    public static final Color INFO = new Color(0x5aa9ff);
    public static final Color QUIET = new Color(0x8f8cff);
    public static final Color PVP = new Color(0xff5c7a);
    public static final Color PVP_SURFACE = new Color(0x2a1219);
    public static final Color WEALTH_SURFACE = new Color(0x0f1d2a);
    public static final Color POSITIVE_SURFACE = new Color(0x0f2a22);
    public static final Color NEGATIVE_SURFACE = new Color(0x2a1414);
    public static final Color WARN_SURFACE = new Color(0x2a2010);
    public static final Color WARN_BORDER = new Color(0x4d3a14);
    /** Icon ring behind glyphs in section headers, notices and stat cells. */
    public static final Color RING = new Color(0x1c2024);

    public enum Accent
    {
        MINT(new Color(0x35d6a3), new Color(0x0f2a22), new Color(0x0e2019), new Color(0x1f5a48)),
        CORAL(new Color(0xff7a59), new Color(0x3a1d15), new Color(0x2a1610), new Color(0x5a2c1f)),
        AMBER(new Color(0xf2b134), new Color(0x3a2d12), new Color(0x2a2112), new Color(0x5a4620)),
        BLUE(new Color(0x7cb4ff), new Color(0x14202c), new Color(0x122030), new Color(0x2a3a4a));

        public final Color color;
        /** Selected-state fill behind accent text. */
        public final Color soft;
        /** Top-left tint of the stat block gradient. */
        public final Color tint;
        public final Color border;

        Accent(Color color, Color soft, Color tint, Color border)
        {
            this.color = color;
            this.soft = soft;
            this.tint = tint;
            this.border = border;
        }

        @Override
        public String toString()
        {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    public enum Density
    {
        // Compact spacing with readable type: body 13, secondary 12 (item rows, wealth rows), micro 10.5.
        COMPACT(13f, 12f, 10.5f, 28f, 8, 6, 12),
        COMFORTABLE(13f, 12f, 10.5f, 30f, 10, 8, 12);

        public final float body;
        public final float secondary;
        public final float micro;
        public final float hero;
        public final int pad;
        public final int gap;
        public final int radius;

        Density(float body, float secondary, float micro, float hero, int pad, int gap, int radius)
        {
            this.body = body;
            this.secondary = secondary;
            this.micro = micro;
            this.hero = hero;
            this.pad = pad;
            this.gap = gap;
            this.radius = radius;
        }

        @Override
        public String toString()
        {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    private static volatile Accent accent = Accent.MINT;
    private static volatile Density density = Density.COMFORTABLE;

    private BentoTheme()
    {
    }

    private static volatile boolean exactFigures;

    /** Tools › Appearance › Exact figures: hero figures as full numbers. */
    public static boolean exactFigures()
    {
        return exactFigures;
    }

    public static void setExactFigures(boolean value)
    {
        exactFigures = value;
    }

    public static Accent accent()
    {
        return accent;
    }

    public static void setAccent(Accent value)
    {
        accent = value == null ? Accent.MINT : value;
    }

    public static Density density()
    {
        return density;
    }

    public static void setDensity(Density value)
    {
        density = value == null ? Density.COMFORTABLE : value;
    }

    public static Color accentColor()
    {
        return accent.color;
    }

    public static Color accentSoft()
    {
        return accent.soft;
    }

    public static Font body()
    {
        return font(Font.PLAIN, density.body);
    }

    public static Font bodyBold()
    {
        return font(Font.BOLD, density.body);
    }

    public static Font secondary()
    {
        return font(Font.PLAIN, density.secondary);
    }

    public static Font micro()
    {
        return font(Font.BOLD, density.micro);
    }

    public static Font hero()
    {
        return font(Font.BOLD, density.hero);
    }

    /**
     * UI typeface: the first installed face from a short list of highly legible sans-serifs
     * (Segoe UI on Windows, then Inter / Roboto / Noto / DejaVu), otherwise the logical
     * SansSerif. Resolved once; RuneLite's own sidebar uses the same family on Windows.
     */
    public static String family()
    {
        String f = family;
        if (f == null)
        {
            f = resolveFamily();
            family = f;
        }
        return f;
    }

    private static volatile String family;

    private static String resolveFamily()
    {
        try
        {
            String[] preferred = {"Segoe UI", "Inter", "Roboto", "Noto Sans", "DejaVu Sans", "Helvetica Neue", "Arial"};
            java.util.Set<String> installed = new java.util.HashSet<>(java.util.Arrays.asList(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String p : preferred)
            {
                if (installed.contains(p))
                {
                    return p;
                }
            }
        }
        catch (RuntimeException | LinkageError ignored)
        {
            // headless or no font subsystem: the logical family still renders
        }
        return Font.SANS_SERIF;
    }

    public static Font font(int style, float size)
    {
        return new Font(family(), style, 12).deriveFont(Math.max(1f, size));
    }

    /**
     * Glyph icons (arrows, rings, gems) use the logical Dialog family: physical faces such as
     * Segoe UI carry no fallback for symbols, the logical family does.
     */
    public static Font symbol(float size)
    {
        return new Font(Font.DIALOG, Font.PLAIN, 12).deriveFont(Math.max(1f, size));
    }

    /**
     * {@code base} when it can draw every character of {@code text}; otherwise the logical face
     * at the same size and style, so a ★ or ⚖ inside a label never paints as a box.
     */
    public static Font fontFor(String text, Font base)
    {
        if (text == null || text.isEmpty() || base == null || base.canDisplayUpTo(text) == -1)
        {
            return base;
        }
        return symbol(base.getSize2D()).deriveFont(base.getStyle());
    }

    /** Figures (hero, values) — same family, bold, with a touch of tracking removed by the renderer. */
    public static Font figure(float size)
    {
        return font(Font.BOLD, size);
    }

    /** Text quality every painter should use. */
    public static void quality(Graphics2D g)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    public static Color withAlpha(Color color, int alpha)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    public static Color signColor(long value)
    {
        return value > 0L ? POSITIVE : value < 0L ? NEGATIVE : DIM;
    }
}
