package com.gpmanager;

import static com.gpmanager.GameData.msg;

import lombok.*;

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
