package com.gpmanager;
import static com.gpmanager.GameData.msg;
import lombok.*;
/**
* Immutable snapshot of the Ground Items list preferences readable through the
* supported {@code ConfigManager.getConfig(GroundItemsConfig.class)} API. It
* does not retain a second price/rule evaluator or touch private plugin fields.
*
* <p>Verified against RuneLite Ground Items (config group {@code grounditems},
* public {@code GroundItemsConfig} surface).</p>
*/
@EqualsAndHashCode(of = "fingerprint")
class GroundItemsConfigSnapshot {
/** The Ground Items config group whose changes invalidate this snapshot. */
static final String GROUP = "grounditems";
final boolean pluginEnabled;
final String highlightedCsv;
final String hiddenCsv;
final boolean showHighlightedOnly;
final String fingerprint;
GroundItemsConfigSnapshot(boolean pluginEnabled, String highlightedCsv, String hiddenCsv, boolean showHighlightedOnly) {
this.pluginEnabled = pluginEnabled;
this.highlightedCsv = ModelText.orEmpty(highlightedCsv);
this.hiddenCsv = ModelText.orEmpty(hiddenCsv);
this.showHighlightedOnly = showHighlightedOnly;
this.fingerprint = buildFingerprint();
}

/** Empty / disabled fallback — callers treat filtering as pass-through. */
static GroundItemsConfigSnapshot disabled() {
return new GroundItemsConfigSnapshot(false, "", msg("n"), false);
}

String fingerprint() {
return fingerprint;
}

String buildFingerprint() {
var sb = new StringBuilder();
sb.append(pluginEnabled).append('|').append(highlightedCsv).append('|').append(hiddenCsv).append('|')
.append(showHighlightedOnly);
return sb.toString();
}
}
