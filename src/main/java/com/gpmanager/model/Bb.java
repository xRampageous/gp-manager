package com.gpmanager;
import lombok.*;
/** Bounded client-observed action text: durable presentation metadata since schema 105, never financial. */
@AllArgsConstructor
class Bb {
static final int MAX_LENGTH = 64;
String value;
/**
* Accept a name only from a Cast interaction whose associated widget belongs to the
* spellbook. The menu target is deliberately absent from this API: NPC/player/item names
* cannot become spell labels.
*
* <p>Used for both the direct spellbook click and the selected spell widget that RuneLite
* still exposes during the following widget-target interaction.</p>
*/
static Bb tv(String /* menuOption */a, int /* widgetId */b,
int /* spellbookGroupId */c, String /* widgetText */d, String /* widgetName */f) {
 String /* option */h = clean(a);
 if (!h.toLowerCase(java.util.Locale.ROOT).startsWith("cast")
 || ((b >>> 16) & 0xffff) != (c & 0xffff)) {
  return null;
 }
 String /* text */i = useful(clean(d));
 if (i != null) return new Bb(i);
 String /* name */j = useful(clean(f));
 if (j != null) return new Bb(j);
 // Some menu entries expose the exact action text directly, e.g. "Cast Ice Burst".
 // Do not inspect menuTarget: it names the affected actor or item.
 if (h.length() > 4 && h.regionMatches(true, 0, "Cast ", 0, 5)) {
  String /* direct */k = useful(clean(h.substring(5)));
  if (k != null) return new Bb(k);
 }
 return null;
}

/** True when the widget id's interface group is the spellbook group. */
static Bb of(String /* text */a) {
 String /* normalized */b = useful(clean(a));
 return b == null ? null : new Bb(b);
}

String value() {
 return value;
}

static String clean(String /* text */b) {
 if (b == null) return "";
 String /* aua */c = b.replaceAll("<[^>]*>", " ").replaceAll("@[a-zA-Z0-9]{3,6}@", " ")
 .replace("&nbsp;", " ").replace("&#160;", " ");
 return c.replaceAll("\\s+", " ").trim();
}

static String useful(String /* text */a) {
 if (a.isEmpty() || a.length() > MAX_LENGTH) return null;
 if ("cast".equalsIgnoreCase(a) || "autocast".equalsIgnoreCase(a) || "cancel".equalsIgnoreCase(a)) return null;
 for (int i = 0; i < a.length(); i++) {
  char c = a.charAt(i);
  if (!Character.isLetterOrDigit(c) && c != ' ' && c != '\'' && c != '-' && c != ',')
  return null;
 }
 return a;
}

public String toString() {
 return value;
}
}
