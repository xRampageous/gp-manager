package com.gpmanager;

import static com.gpmanager.GameData.msg;

import lombok.RequiredArgsConstructor;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.grounditems.*;

import org.slf4j.*;

import javax.inject.*;
import lombok.*;

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

/**
 * Immutable snapshot of the Ground Items list preferences readable through the supported {@code
 * ConfigManager.getConfig(GroundItemsConfig.class)} API. It does not retain a second price/rule
 * evaluator or touch private plugin fields.
 *
 * <p>Verified against RuneLite Ground Items (config group {@code grounditems}, public {@code
 * GroundItemsConfig} surface).
 */
@EqualsAndHashCode(of = "fingerprint")
class GroundItemsPrefs {
    /** The Ground Items config group whose changes invalidate this snapshot. */
    static final String GROUP = "grounditems";

    boolean pluginEnabled;
    String highlightedCsv;
    String hiddenCsv;
    boolean showHighlightedOnly;

    /**
     * Ground Items' insane value: HUD+'s big-drop line while the loot display follows Ground Items.
     */
    long insane = HudTray.BIG_DROP;

    String fingerprint;

    GroundItemsPrefs(
            boolean pluginEnabled,
            String highlightedCsv,
            String hiddenCsv,
            boolean showHighlightedOnly) {
        this.pluginEnabled = pluginEnabled;
        this.highlightedCsv = Phrases.orEmpty(highlightedCsv);
        this.hiddenCsv = Phrases.orEmpty(hiddenCsv);
        this.showHighlightedOnly = showHighlightedOnly;
        this.fingerprint = buildFingerprint();
    }

    /** Empty / disabled fallback — callers treat filtering as pass-through. */
    static GroundItemsPrefs disabled() {
        return new GroundItemsPrefs(false, "", msg("vial-ashes"), false);
    }

    String fingerprint() {
        return fingerprint;
    }

    String buildFingerprint() {
        return pluginEnabled + "|" + highlightedCsv + "|" + hiddenCsv + "|" + showHighlightedOnly;
    }
}
