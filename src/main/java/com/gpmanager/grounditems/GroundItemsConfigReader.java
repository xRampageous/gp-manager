package com.gpmanager.grounditems;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.grounditems.GroundItemsConfig;
import net.runelite.client.plugins.grounditems.GroundItemsPlugin;
import net.runelite.client.plugins.grounditems.config.ValueCalculationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the active Ground Items configuration through supported RuneLite APIs
 * only: {@link ConfigManager#getConfig(Class)}, {@link ConfigManager#getConfiguration},
 * and {@link PluginManager#isPluginEnabled(Plugin)}. Never reflects into private
 * Ground Items fields or package-private matchers.
 */
@Singleton
public class GroundItemsConfigReader
{
    private static final Logger log = LoggerFactory.getLogger(GroundItemsConfigReader.class);

    private final ConfigManager configManager;
    @Nullable
    private final PluginManager pluginManager;

    @Inject
    public GroundItemsConfigReader(ConfigManager configManager, @Nullable PluginManager pluginManager)
    {
        this.configManager = configManager;
        this.pluginManager = pluginManager;
    }

    /** Test constructor without PluginManager. */
    public GroundItemsConfigReader(ConfigManager configManager)
    {
        this(configManager, null);
    }

    public GroundItemsConfigSnapshot read()
    {
        boolean enabled = isGroundItemsEnabled();
        GroundItemsConfig config;
        try
        {
            config = configManager.getConfig(GroundItemsConfig.class);
        }
        catch (RuntimeException ex)
        {
            log.debug("Ground Items config unavailable", ex);
            return GroundItemsConfigSnapshot.disabled();
        }
        if (config == null)
        {
            return GroundItemsConfigSnapshot.disabled();
        }

        List<GroundItemsConfigSnapshot.PriceTier> tiers = new ArrayList<>();
        addTier(tiers, config.insaneValuePrice(), config.insaneValueColor());
        addTier(tiers, config.highValuePrice(), config.highValueColor());
        addTier(tiers, config.mediumValuePrice(), config.mediumValueColor());
        addTier(tiers, config.lowValuePrice(), config.lowValueColor());

        return new GroundItemsConfigSnapshot(
            enabled,
            config.getHighlightItems(),
            config.getHiddenItems(),
            config.showHighlightedOnly(),
            config.dontHideUntradeables(),
            config.getHideUnderValue(),
            mapValueMode(config.valueCalculationMode()),
            config.highlightedColor(),
            config.defaultColor(),
            config.hiddenColor(),
            tiers);
    }

    /**
     * Per-item colour overrides stored by Ground Items under
     * {@code grounditems.highlight_<itemId>}.
     */
    @Nullable
    public Color readItemColor(int itemId)
    {
        if (itemId <= 0 || configManager == null)
        {
            return null;
        }
        try
        {
            return configManager.getConfiguration(
                GroundItemsConfigSnapshot.GROUP,
                GroundItemsConfigSnapshot.HIGHLIGHT_COLOR_PREFIX + itemId,
                Color.class);
        }
        catch (RuntimeException ex)
        {
            log.debug("Ground Items item color unavailable for {}", itemId, ex);
            return null;
        }
    }

    public boolean isGroundItemsEnabled()
    {
        if (pluginManager == null)
        {
            return false;
        }
        for (Plugin plugin : pluginManager.getPlugins())
        {
            if (plugin instanceof GroundItemsPlugin)
            {
                return pluginManager.isPluginEnabled(plugin);
            }
        }
        return false;
    }

    private static void addTier(List<GroundItemsConfigSnapshot.PriceTier> tiers, int price, Color color)
    {
        if (price > 0 && color != null)
        {
            tiers.add(new GroundItemsConfigSnapshot.PriceTier(price, color));
        }
    }

    private static GroundItemsConfigSnapshot.ValueMode mapValueMode(ValueCalculationMode mode)
    {
        if (mode == null)
        {
            return GroundItemsConfigSnapshot.ValueMode.HIGHEST;
        }
        switch (mode)
        {
            case GE:
                return GroundItemsConfigSnapshot.ValueMode.GE;
            case HA:
                return GroundItemsConfigSnapshot.ValueMode.HA;
            case HIGHEST:
            default:
                return GroundItemsConfigSnapshot.ValueMode.HIGHEST;
        }
    }
}
