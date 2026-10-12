package com.gpmanager;

import lombok.*;

import net.runelite.client.util.*;

import javax.inject.*;

/**
 * Small presentation-only Ground Items preference adapter. It keeps the user's list choices
 * available to filtered projections, but does not retain a second price/rule evaluator or any
 * accounting state.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LootPresentationFilterService {
    static final int NONE = 0;
    static final int HIGHLIGHTED = 1;
    static final int HIDDEN = 2;
    final GroundItemsConfigReader configReader;
    volatile GroundItemsPrefs snapshot;
    volatile String fingerprint = "";

    /** Test helper with a fixed snapshot (no live ConfigManager). */
    LootPresentationFilterService(GroundItemsPrefs snapshot) {
        configReader = null;
        replaceSnapshotForTests(snapshot);
    }

    synchronized void refresh() {
        if (configReader == null) return;
        replaceSnapshotForTests(configReader.read());
    }

    /** Applies only explicit list preferences; ambiguous value/price rules stay visible. */
    boolean isFlowIncluded(Flow flow, LootFilter mode) {
        LootFilter effective = mode == null ? LootFilter.ALL_ITEMS : mode;
        if (effective == LootFilter.ALL_ITEMS || flow == null) return true;
        ensureSnapshot();
        GroundItemsPrefs current = snapshot;
        if (!current.pluginEnabled) return true;
        int state =
                listState(
                        current.highlightedCsv,
                        current.hiddenCsv,
                        flow.itemName,
                        SafeMath.nonNeg(Math.abs(flow.quantityDelta)));
        if (state == HIDDEN) return false;
        return effective != LootFilter.HIGHLIGHTED_LIST_ONLY
                ? !current.showHighlightedOnly || state == HIGHLIGHTED
                : state == HIGHLIGHTED;
    }

    /** HUD+'s big-drop line: Ground Items' insane value when the display follows it, else 10M. */
    long big(LootFilter mode) {
        ensureSnapshot();
        GroundItemsPrefs current = snapshot;
        return mode == LootFilter.FOLLOW_GROUND_ITEMS
                        && current.pluginEnabled
                        && current.insane > 0L
                ? current.insane
                : HudTray.BIG_DROP;
    }

    synchronized void replaceSnapshotForTests(GroundItemsPrefs value) {
        snapshot = value == null ? GroundItemsPrefs.disabled() : value;
        fingerprint = snapshot.fingerprint();
    }

    synchronized void ensureSnapshot() {
        if (snapshot == null) refresh();
        if (snapshot == null) replaceSnapshotForTests(null);
    }

    static int listState(String highlightedCsv, String hiddenCsv, String itemName, long quantity) {
        int exact = listMatch(highlightedCsv, itemName, quantity, false);
        if (exact != NONE) return HIGHLIGHTED;
        exact = listMatch(hiddenCsv, itemName, quantity, false);
        if (exact != NONE) return HIDDEN;
        int wildcard = listMatch(highlightedCsv, itemName, quantity, true);
        if (wildcard != NONE) return HIGHLIGHTED;
        return listMatch(hiddenCsv, itemName, quantity, true) == NONE ? NONE : HIDDEN;
    }

    /** The Recent charge pattern for a row: its dose family, wildcarded (Burning amulet*). */
    static String chargePattern(String itemName) {
        return ActionReader.doseBaseName(itemName) + "*";
    }

    static int listMatch(String csv, String itemName, long quantity, boolean wildcard) {
        String name = Phrases.orEmpty(itemName);
        for (Rule rule : rules(csv)) {
            if (rule.wildcard == wildcard && rule.matches(name, quantity)) return 1;
        }
        return NONE;
    }

    /** Parsed list rules by list text: the Ground Items lists are parsed once, not per item. */
    static final java.util.Map<String, java.util.List<Rule>> RULES =
            new java.util.concurrent.ConcurrentHashMap<>();

    static java.util.List<Rule> rules(String csv) {
        if (RULES.size() > 16) RULES.clear();
        return RULES.computeIfAbsent(
                Phrases.orEmpty(csv),
                text -> {
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
        String name;
        int quantity;
        boolean lessThan;
        boolean wildcard;

        static Rule parse(String value) {
            if (Phrases.blank(value)) return null;
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
                } catch (NumberFormatException ignored) {
                }
            }
            return new Rule(text, Math.max(0, quantity), less, text.contains("*"));
        }

        boolean matches(String itemName, long count) {
            if (wildcard
                    ? !WildcardMatcher.matches(name, itemName)
                    : !name.equalsIgnoreCase(itemName)) return false;
            return lessThan ? count < quantity : count > quantity;
        }
    }
}
