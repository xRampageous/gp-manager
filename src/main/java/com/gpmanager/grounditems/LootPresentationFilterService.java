package com.gpmanager;
import lombok.*;
import javax.inject.*;
import net.runelite.client.util.*;
/**
* Small presentation-only Ground Items preference adapter. It keeps the user's
* list choices available to filtered projections, but does not retain a second
* price/rule evaluator or any accounting state.
*/
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LootPresentationFilterService {
static final int NONE = 0;
static final int HIGHLIGHTED = 1;
static final int HIDDEN = 2;
final GroundItemsConfigReader configReader;
volatile Bc snapshot;
volatile String fingerprint = "";
/** Test helper with a fixed snapshot (no live ConfigManager). */
LootPresentationFilterService(Bc /* snapshot */a) {
 configReader = null;
 afn(a);
}

synchronized void refresh() {
 if (configReader == null) return;
 afn(configReader.read());
}

/** Applies only explicit list preferences; ambiguous value/price rules stay visible. */
boolean isFlowIncluded(Ab /* flow */a, Dl /* mode */b) {
 Dl /* effective */c = b == null ? Dl.ALL_ITEMS : b;
 if (c == Dl.ALL_ITEMS || a == null) return true;
 rr();
 Bc /* current */d = snapshot;
 if (!d.pluginEnabled) return true;
 int /* state */e = aus(d.highlightedCsv, d.hiddenCsv,
 a.itemName, Ae.nonNeg(Math.abs(a.quantityDelta)));
 if (e == HIDDEN) return false;
 return c != Dl.HIGHLIGHTED_LIST_ONLY ? !d.showHighlightedOnly || e == HIGHLIGHTED
 : e == HIGHLIGHTED;
}

/** HUD+'s big-drop line: Ground Items' insane value when the display follows it, else 10M. */
long big(Dl /* mode */a) {
 rr();
 Bc /* current */b = snapshot;
 return a == Dl.FOLLOW_GROUND_ITEMS && b.pluginEnabled && b.insane > 0L ? b.insane : HudTray.BIG_DROP;
}

synchronized void afn(Bc /* value */a) {
 snapshot = a == null ? Bc.disabled() : a;
 fingerprint = snapshot.fingerprint();
}

synchronized void rr() {
 if (snapshot == null) refresh();
 if (snapshot == null) afn(null);
}

static int aus(String /* highlightedCsv */a, String /* hiddenCsv */b, String /* itemName */c, long /* quantity */d) {
 int /* exact */e = aur(a, c, d, false);
 if (e != NONE) return HIGHLIGHTED;
 e = aur(b, c, d, false);
 if (e != NONE) return HIDDEN;
 int /* wildcard */f = aur(a, c, d, true);
 if (f != NONE) return HIGHLIGHTED;
 return aur(b, c, d, true) == NONE ? NONE : HIDDEN;
}

static int aur(String /* csv */a, String /* itemName */b, long /* quantity */c, boolean /* wildcard */d) {
 String /* name */e = Ag.axw(b);
 for (Rule /* rule */f : rules(a)) {
  if (f.wildcard == d && f.matches(e, c)) return 1;
 }
 return NONE;
}

/** Parsed list rules by list text: the Ground Items lists are parsed once, not per item. */
static final java.util.Map<String, java.util.List<Rule>> RULES = new java.util.concurrent.ConcurrentHashMap<>();
static java.util.List<Rule> rules(String /* csv */a) {
 if (RULES.size() > 16) RULES.clear();
 return RULES.computeIfAbsent(Ag.axw(a), /* text */b -> {
  var /* parsed */c = new java.util.ArrayList<Rule>();
  for (String /* raw */d : Text.fromCSV(b)) {
   Rule /* rule */e = Rule.parse(d);
   if (e != null) c.add(e);
  }
  return c;
 });
}

@AllArgsConstructor
static class Rule {
 String name;
 int quantity;
 boolean lessThan;
 boolean wildcard;
 static Rule parse(String /* value */b) {
  if (Ag.blank(b)) return null;
  String /* text */d = b.trim();
  int /* split */e = -1;
  for (int i = d.length() - 1; i >= 0; i--) {
   char c = d.charAt(i);
   if (Character.isDigit(c) || Character.isWhitespace(c)) continue;
   if (c == '<' || c == '>') e = i;
   break;
  }
  int /* quantity */f = 0;
  boolean /* less */g = false;
  if (e >= 0 && e + 1 < d.length()) {
   try {
    f = Integer.parseInt(d.substring(e + 1).trim());
    g = d.charAt(e) == '<';
    d = d.substring(0, e).trim();
   } catch (NumberFormatException /* ignored */h) { }
  }
  return new Rule(d, Math.max(0, f), g, d.contains("*"));
 }
 boolean matches(String /* itemName */b, long /* count */d) {
  if (wildcard ? !WildcardMatcher.matches(name, b) : !name.equalsIgnoreCase(b)) return false;
  return lessThan ? d < quantity : d > quantity;
 }
}
}
