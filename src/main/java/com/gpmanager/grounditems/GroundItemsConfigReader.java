package com.gpmanager;

import static com.gpmanager.GameData.msg;

import lombok.RequiredArgsConstructor;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.grounditems.*;

import org.slf4j.*;

import javax.inject.*;

/**
 * Reads only the active Ground Items list preferences through supported RuneLite APIs. Never
 * reflects into private Ground Items fields or package-private matchers.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GroundItemsConfigReader {
    static final Logger log = LoggerFactory.getLogger(GroundItemsConfigReader.class);
    final ConfigManager configManager;
    final PluginManager pluginManager;

    GroundItemsPrefs read() {
        boolean enabled = isGroundItemsEnabled();
        GroundItemsConfig config;
        try {
            config = configManager.getConfig(GroundItemsConfig.class);
        } catch (RuntimeException ex) {
            log.debug(msg("ground-items"), ex);
            return GroundItemsPrefs.disabled();
        }
        if (config == null) return GroundItemsPrefs.disabled();
        var read =
                new GroundItemsPrefs(
                        enabled,
                        config.getHighlightItems(),
                        config.getHiddenItems(),
                        config.showHighlightedOnly());
        read.insane = config.insaneValuePrice();
        return read;
    }

    boolean isGroundItemsEnabled() {
        if (pluginManager == null) return false;
        for (Plugin plugin : pluginManager.getPlugins()) {
            if (plugin instanceof GroundItemsPlugin) return pluginManager.isPluginEnabled(plugin);
        }
        return false;
    }
}
