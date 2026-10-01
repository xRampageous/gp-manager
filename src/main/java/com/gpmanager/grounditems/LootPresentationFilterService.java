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
LootPresentationFilterService(Bc snapshot) {
 configReader = null;
 afn(snapshot);
}

synchronized void refresh() {
 if (configReader == null) return;
 afn(configReader.read());
}

/** Applies only explicit list preferences; ambiguous value/price rules stay visible. */
boolean isFlowIncluded(Ab flow, Dl mode) {
 Dl effective = mode == null ? Dl.ALL_ITEMS : mode;
 if (effective == Dl.ALL_ITEMS || flow == null) return true;
 rr();
 Bc current = snapshot;
 if (!current.pluginEnabled) return true;
 int state = aus(current.highlightedCsv, current.hiddenCsv,
 flow.itemName, Ae.nonNeg(Math.abs(flow.quantityDelta)));
 if (state == HIDDEN) return false;
 return effective != Dl.HIGHLIGHTED_LIST_ONLY ? !current.showHighlightedOnly || state == HIGHLIGHTED
 : state == HIGHLIGHTED;
}

synchronized void afn(Bc value) {
 snapshot = value == null ? Bc.disabled() : value;
 fingerprint = snapshot.fingerprint();
}

synchronized void rr() {
 if (snapshot == null) refresh();
 if (snapshot == null) afn(null);
}

static int aus(String highlightedCsv, String hiddenCsv, String itemName, long quantity) {
 int exact = aur(highlightedCsv, itemName, quantity, false);
 if (exact != NONE) return HIGHLIGHTED;
 exact = aur(hiddenCsv, itemName, quantity, false);
 if (exact != NONE) return HIDDEN;
 int wildcard = aur(highlightedCsv, itemName, quantity, true);
 if (wildcard != NONE) return HIGHLIGHTED;
 return aur(hiddenCsv, itemName, quantity, true) == NONE ? NONE : HIDDEN;
}

static int aur(String csv, String itemName, long quantity, boolean wildcard) {
 String name = Ag.axw(itemName);
 for (Rule rule : rules(csv)) {
  if (rule.wildcard == wildcard && rule.matches(name, quantity)) return 1;
 }
 return NONE;
}

/** Parsed list rules by list text: the Ground Items lists are parsed once, not per item. */
static final java.util.Map<String, java.util.List<Rule>> RULES = new java.util.concurrent.ConcurrentHashMap<>();
static java.util.List<Rule> rules(String csv) {
 if (RULES.size() > 16) RULES.clear();
 return RULES.computeIfAbsent(Ag.axw(csv), text -> {
  var parsed = new java.util.ArrayList<Rule>();
  for (String raw : Text.fromCSV(text)) {
   Rule rule = Rule.parse(raw);
   if (rule != null) parsed.add(rule);
  }
  return parsed;
 });
}

@AllArgsConstructor
static class Rule {
 final String name;
 final int quantity;
 final boolean lessThan;
 final boolean wildcard;
 static Rule parse(String value) {
  if (Ag.blank(value)) return null;
  String text = value.trim();
  int split = -1;
  for (int i = text.length() - 1; i >= 0; i--) {
   char c = text.charAt(i);
   if (Character.isDigit(c) || Character.isWhitespace(c)) continue;
   if (c == '<' || c == '>') split = i;
   break;
  }
  int quantity = 0;
  boolean less = false;
  if (split >= 0 && split + 1 < text.length()) {
   try {
    quantity = Integer.parseInt(text.substring(split + 1).trim());
    less = text.charAt(split) == '<';
    text = text.substring(0, split).trim();
   } catch (NumberFormatException ignored) { }
  }
  return new Rule(text, Math.max(0, quantity), less, text.contains("*"));
 }
 boolean matches(String itemName, long count) {
  if (wildcard ? !WildcardMatcher.matches(name, itemName) : !name.equalsIgnoreCase(itemName)) return false;
  return lessThan ? count < quantity : count > quantity;
 }
}
}
