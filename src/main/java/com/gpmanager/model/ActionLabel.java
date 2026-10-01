package com.gpmanager;
import lombok.*;
/** Bounded client-observed action text: durable presentation metadata since schema 105, never financial. */
@AllArgsConstructor
class ActionLabel {
static final int MAX_LENGTH = 64;
final String value;
/**
* Accept a name only from a Cast interaction whose associated widget belongs to the
* spellbook. The menu target is deliberately absent from this API: NPC/player/item names
* cannot become spell labels.
*
* <p>Used for both the direct spellbook click and the selected spell widget that RuneLite
* still exposes during the following widget-target interaction.</p>
*/
static ActionLabel fromSpellMenu(String menuOption, int widgetId,
int spellbookGroupId, String widgetText, String widgetName) {
String option = clean(menuOption);
if (!option.toLowerCase(java.util.Locale.ROOT).startsWith("cast")
|| ((widgetId >>> 16) & 0xffff) != (spellbookGroupId & 0xffff)) {
return null;
}
String text = useful(clean(widgetText));
if (text != null) return new ActionLabel(text);
String name = useful(clean(widgetName));
if (name != null) return new ActionLabel(name);
// Some menu entries expose the exact action text directly, e.g. "Cast Ice Burst".
// Do not inspect menuTarget: it names the affected actor or item.
if (option.length() > 4 && option.regionMatches(true, 0, "Cast ", 0, 5)) {
String direct = useful(clean(option.substring(5)));
if (direct != null) return new ActionLabel(direct);
}
return null;
}

/** True when the widget id's interface group is the spellbook group. */
static ActionLabel of(String text) {
String normalized = useful(clean(text));
return normalized == null ? null : new ActionLabel(normalized);
}

String value() {
return value;
}

static String clean(String text) {
if (text == null) return "";
String withoutMarkup = text.replaceAll("<[^>]*>", " ").replaceAll("@[a-zA-Z0-9]{3,6}@", " ")
.replace("&nbsp;", " ").replace("&#160;", " ");
return withoutMarkup.replaceAll("\\s+", " ").trim();
}

static String useful(String text) {
if (text.isEmpty() || text.length() > MAX_LENGTH) return null;
if ("cast".equalsIgnoreCase(text) || "autocast".equalsIgnoreCase(text) || "cancel".equalsIgnoreCase(text)) return null;
for (int i = 0; i < text.length(); i++) {
char c = text.charAt(i);
if (!Character.isLetterOrDigit(c) && c != ' ' && c != '\'' && c != '-' && c != ',')
return null;
}
return text;
}

public String toString() {
return value;
}
}
