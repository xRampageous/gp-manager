package com.gpmanager.grounditems;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of Ground Items settings readable through supported
 * RuneLite APIs ({@code ConfigManager.getConfig(GroundItemsConfig.class)} and
 * related {@code getConfiguration} color keys). Does not touch private plugin
 * fields or use reflection.
 *
 * <p>Verified against RuneLite Ground Items (config group {@code grounditems},
 * public {@code GroundItemsConfig} surface).</p>
 */
public final class GroundItemsConfigSnapshot
{
    public static final String GROUP = "grounditems";
    public static final String HIGHLIGHT_COLOR_PREFIX = "highlight_";

    public enum ValueMode
    {
        GE,
        HA,
        HIGHEST
    }

    public static final class PriceTier
    {
        private final int price;
        private final Color color;

        public PriceTier(int price, Color color)
        {
            this.price = price;
            this.color = color;
        }

        public int getPrice()
        {
            return price;
        }

        public Color getColor()
        {
            return color;
        }
    }

    private final boolean pluginEnabled;
    private final String highlightedCsv;
    private final String hiddenCsv;
    private final boolean showHighlightedOnly;
    private final boolean dontHideUntradeables;
    private final int hideUnderValue;
    private final ValueMode valueMode;
    private final Color highlightedColor;
    private final Color defaultColor;
    private final Color hiddenColor;
    private final List<PriceTier> priceTiers;
    private final String fingerprint;

    public GroundItemsConfigSnapshot(
        boolean pluginEnabled,
        String highlightedCsv,
        String hiddenCsv,
        boolean showHighlightedOnly,
        boolean dontHideUntradeables,
        int hideUnderValue,
        ValueMode valueMode,
        Color highlightedColor,
        Color defaultColor,
        Color hiddenColor,
        List<PriceTier> priceTiers)
    {
        this.pluginEnabled = pluginEnabled;
        this.highlightedCsv = highlightedCsv == null ? "" : highlightedCsv;
        this.hiddenCsv = hiddenCsv == null ? "" : hiddenCsv;
        this.showHighlightedOnly = showHighlightedOnly;
        this.dontHideUntradeables = dontHideUntradeables;
        this.hideUnderValue = Math.max(0, hideUnderValue);
        this.valueMode = valueMode == null ? ValueMode.HIGHEST : valueMode;
        this.highlightedColor = highlightedColor == null ? Color.decode("#AA00FF") : highlightedColor;
        this.defaultColor = defaultColor == null ? Color.WHITE : defaultColor;
        this.hiddenColor = hiddenColor == null ? Color.GRAY : hiddenColor;
        this.priceTiers = priceTiers == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(priceTiers));
        this.fingerprint = buildFingerprint();
    }

    /** Empty / disabled fallback — callers treat filtering as pass-through. */
    public static GroundItemsConfigSnapshot disabled()
    {
        return new GroundItemsConfigSnapshot(
            false,
            "",
            "Vial, Ashes, Coins, Bones, Bucket, Jug, Seaweed",
            false,
            true,
            0,
            ValueMode.HIGHEST,
            Color.decode("#AA00FF"),
            Color.WHITE,
            Color.GRAY,
            Collections.emptyList());
    }

    public boolean isPluginEnabled()
    {
        return pluginEnabled;
    }

    public String getHighlightedCsv()
    {
        return highlightedCsv;
    }

    public String getHiddenCsv()
    {
        return hiddenCsv;
    }

    public boolean isShowHighlightedOnly()
    {
        return showHighlightedOnly;
    }

    public boolean isDontHideUntradeables()
    {
        return dontHideUntradeables;
    }

    public int getHideUnderValue()
    {
        return hideUnderValue;
    }

    public ValueMode getValueMode()
    {
        return valueMode;
    }

    public Color getHighlightedColor()
    {
        return highlightedColor;
    }

    public Color getDefaultColor()
    {
        return defaultColor;
    }

    public Color getHiddenColor()
    {
        return hiddenColor;
    }

    public List<PriceTier> getPriceTiers()
    {
        return priceTiers;
    }

    public String fingerprint()
    {
        return fingerprint;
    }

    private String buildFingerprint()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(pluginEnabled).append('|')
            .append(highlightedCsv).append('|')
            .append(hiddenCsv).append('|')
            .append(showHighlightedOnly).append('|')
            .append(dontHideUntradeables).append('|')
            .append(hideUnderValue).append('|')
            .append(valueMode).append('|')
            .append(colorKey(highlightedColor)).append('|')
            .append(colorKey(defaultColor)).append('|')
            .append(colorKey(hiddenColor));
        for (PriceTier tier : priceTiers)
        {
            sb.append('|').append(tier.price).append(':').append(colorKey(tier.color));
        }
        return sb.toString();
    }

    private static String colorKey(Color color)
    {
        return color == null ? "" : Integer.toHexString(color.getRGB());
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof GroundItemsConfigSnapshot))
        {
            return false;
        }
        GroundItemsConfigSnapshot that = (GroundItemsConfigSnapshot) o;
        return Objects.equals(fingerprint, that.fingerprint);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(fingerprint);
    }
}
