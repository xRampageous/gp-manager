package com.gpmanager;
import static com.gpmanager.Ak.msg;
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
class Bc {
/** The Ground Items config group whose changes invalidate this snapshot. */
static final String GROUP = "grounditems";
boolean pluginEnabled;
String highlightedCsv;
String hiddenCsv;
boolean showHighlightedOnly;
/** Ground Items' insane value: HUD+'s big-drop line while the loot display follows Ground Items. */
long insane = HudTray.BIG_DROP;
String fingerprint;
Bc(boolean /* pluginEnabled */a, String /* highlightedCsv */b, String /* hiddenCsv */c, boolean /* showHighlightedOnly */d) {
 this.pluginEnabled = a;
 this.highlightedCsv = Ag.axw(b);
 this.hiddenCsv = Ag.axw(c);
 this.showHighlightedOnly = d;
 this.fingerprint = mx();
}

/** Empty / disabled fallback — callers treat filtering as pass-through. */
static Bc disabled() {
 return new Bc(false, "", msg("n"), false);
}

String fingerprint() {
 return fingerprint;
}

String mx() {
 return pluginEnabled + "|" + highlightedCsv + "|" + hiddenCsv + "|" + showHighlightedOnly;
}
}
