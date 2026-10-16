package com.gpmanager;

import java.util.*;
import lombok.*;

/**
 * Bounded client-observed action text: durable presentation metadata since schema 105, never
 * financial.
 */
@AllArgsConstructor
class ActionLabel {
    static final int MAX_LENGTH = 64;
    String value;

    /**
     * Accept a name only from a Cast interaction whose associated widget belongs to the spellbook.
     * The menu target is deliberately absent from this API: NPC/player/item names cannot become
     * spell labels.
     *
     * <p>Used for both the direct spellbook click and the selected spell widget that RuneLite still
     * exposes during the following widget-target interaction.
     */
    static ActionLabel fromSpellMenu(
            String menuOption,
            int widgetId,
            int spellbookGroupId,
            String widgetText,
            String widgetName) {
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
        String withoutMarkup =
                text.replaceAll("<[^>]*>", " ")
                        .replaceAll("@[a-zA-Z0-9]{3,6}@", " ")
                        .replace("&nbsp;", " ")
                        .replace("&#160;", " ");
        return withoutMarkup.replaceAll("\\s+", " ").trim();
    }

    static String useful(String text) {
        if (text.isEmpty() || text.length() > MAX_LENGTH) return null;
        if ("cast".equalsIgnoreCase(text)
                || "autocast".equalsIgnoreCase(text)
                || "cancel".equalsIgnoreCase(text)) return null;
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

/**
 * Observed player action behind a settled inventory change. Presentation-only evidence: it names
 * what the player did (drank, ate, decanted, cast) so HUD/receipts/Live/Ledger can use honest
 * wording. It never changes {@link TransactionType}, valuation, counted state or session ownership.
 *
 * <p>Persisted on {@link Transaction} by wire name; absent on rows saved before B10 and on rows
 * whose evidence never supported a specific verb. Unsupported evidence must not acquire a more
 * confident verb — callers fall back to {@link #SUPPLIES} or a generic label rather than guessing.
 */
enum ActionKind {
    /** Drink intent plus a settled dose/vial decrease. */
    DRINK,
    /** Eat intent plus settled food loss (partial foods use the observed portion). */
    EAT,
    /** Herblore production: ingredients in, potion out. Not every paired transform. */
    MIX,
    /** Dose-conserving redistribution (3+1 → 4). Never implies doses were drunk. */
    DECANT,
    BURY,
    OFFER,
    SCATTER,
    /** Supported rune consumption. Spell name only when evidenced. Ledger detail only. */
    CAST,
    /** Supported ammunition consumption. Ledger detail only. */
    FIRE,
    /** Untradeable key audit rows are inspectable in Ledger only. */
    DEFERRED_CLAIM,
    /** Ambiguous charge-load settlements are explanatory Ledger notes only. */
    CHARGE_LOAD_AMBIGUOUS,
    COOK,
    BURN,
    /** Settled supply loss whose specific action is not evidenced. */
    SUPPLIES,
    /** A bank-paid fee row (the retired boat recovery): one action row named by its own note. */
    FEE;

    /** The constant's name, lowercased with hyphens: the wire name saved on every row. */
    String wireName = name().toLowerCase(Locale.ROOT).replace('_', '-');

    /** Stable persisted identifier. */
    String wireName() {
        return wireName;
    }

    static ActionKind fromWireName(String value) {
        return Phrases.blank(value) ? null : BY_WIRE.get(Phrases.lower(value));
    }

    /** Wire and constant names, lowercased, read on every getActionKind() (a hot path). */
    static final Map<String, ActionKind> BY_WIRE = new HashMap<>();

    static {
        for (ActionKind kind : values()) {
            BY_WIRE.put(kind.wireName, kind);
            BY_WIRE.put(kind.name().toLowerCase(Locale.ROOT), kind);
        }
    }
}

/**
 * Combat spells by exact rune cost (spells.tsv). Autocasting never clicks a spell, so a Cast with
 * no captured name reads as the one spell its runes pay for (owner 2026-09-28). A staff, tome or
 * combination rune may cover elementals; when two spells could fit, it stays a plain Cast.
 */
class Spells {
    /** Icons and recipes retain their original joined row shape for existing readers. */
    static final Map<String, String> ICONS = GameData.byName("spells");

    static final List<String[]> TABLE =
            GameData.cachedRows("spell_recipes").stream()
                    .map(a -> new String[] {a[0], ICONS.get(a[0]), a[1]})
                    .collect(java.util.stream.Collectors.toList());
    static final Map<String, String> COMBINATIONS = new HashMap<>();
    static final String ELEMENTS = " air water earth fire ";

    static {
        for (String[] row : GameData.rows("combination_runes")) COMBINATIONS.put(row[0], row[1]);
    }

    /**
     * The spell these rune costs pay for (a whole number of casts), or null. When staff-covered
     * elementals let several fit, the one paid in full wins; if none or several are, it stays a
     * Cast.
     */

    /**
     * The spell's spellbook icon as a negated sprite id (the sidebar's sprite lookup reads it),
     * else -1.
     */
    static final Map<String, String> BY_LOWER = new HashMap<>();

    static {
        for (String name : ICONS.keySet()) BY_LOWER.put(name.toLowerCase(Locale.ROOT), name);
    }

    /** The item ids a spell makes (an alch's coins, Bones to Peaches' fruit); empty for others. */
    static Set<Integer> product(String name) {
        for (String[] row : GameData.cachedRows("spells")) {
            if (row[0].equals(name) && row.length > 2) {
                var ids = new HashSet<Integer>();
                for (String id : row[2].split(",")) ids.add(Integer.parseInt(id));
                return ids;
            }
        }
        return Collections.emptySet();
    }

    /** The spell a name spells out, ignoring case (a "Bones to peaches" tablet), else null. */
    static String named(String name) {
        return name == null ? null : BY_LOWER.get(name.toLowerCase(Locale.ROOT).trim());
    }

    static int icon(String name) {
        // d19 holds a spell's sprite, or minus an item id for a row named by its item (the barrel).
        String icon = ICONS.get(name);
        return icon == null ? -1 : -Integer.parseInt(icon);
    }

    /** 0 when these costs cannot be this spell, 1 when they fit it, 2 when they pay it in full. */

    /** Rune recipes are parsed once; fitting arithmetic and HashMap iteration stay unchanged. */
    static final Map<String, Map<String, Long>> RECIPES = new LinkedHashMap<>();

    static {
        for (String[] row : GameData.cachedRows("spell_recipes")) {
            var recipe = new HashMap<String, Long>();
            for (String part : row[1].split(" ")) {
                int colon = part.indexOf(':');
                recipe.put(part.substring(colon + 1), Long.parseLong(part.substring(0, colon)));
            }
            RECIPES.put(row[0], Collections.unmodifiableMap(recipe));
        }
    }

    static String spellFor(List<Flow> flows) {
        String fitting = null;
        String full = null;
        int fits = 0;
        int fulls = 0;
        for (String[] spell : TABLE) {
            int fit = fit(RECIPES.get(spell[0]), flows);
            if (fit > 0) {
                fits++;
                fitting = spell[0];
            }
            if (fit > 1) {
                fulls++;
                full = spell[0];
            }
        }
        return fits == 1 ? fitting : fulls == 1 ? full : null;
    }

    static int fit(Map<String, Long> recipe, List<Flow> flows) {
        var paid = new HashMap<String, Long>();
        for (Flow flow : flows) {
            String name = flow.itemName.toLowerCase(Locale.ROOT);
            if (!name.endsWith(" rune") || flow.quantityDelta >= 0L) return 0;
            String rune = name.substring(0, name.length() - 5);
            boolean used = false;
            for (String element : COMBINATIONS.getOrDefault(rune, rune).split(" ")) {
                // A combination rune pays only the elements this spell uses (steam is water for Ice
                // Barrage).
                if (recipe.containsKey(element) || !COMBINATIONS.containsKey(rune)) {
                    paid.merge(element, -flow.quantityDelta, Long::sum);
                    used = true;
                }
            }
            if (!used) return 0;
        }
        long casts = 0L;
        for (Map.Entry<String, Long> rune : recipe.entrySet()) {
            if (!ELEMENTS.contains(" " + rune.getKey() + " ")) {
                long got = paid.getOrDefault(rune.getKey(), 0L);
                if (got == 0L
                        || got % rune.getValue() != 0L
                        || casts != 0L && got / rune.getValue() != casts) return 0;
                casts = got / rune.getValue();
            }
        }
        boolean full = true;
        for (Map.Entry<String, Long> rune : recipe.entrySet()) {
            long got = paid.getOrDefault(rune.getKey(), 0L);
            // A rune the spell never uses, or more of an elemental than its casts need, is another
            // spell.
            if (got > casts * rune.getValue()) return 0;
            full &= got == casts * rune.getValue();
        }
        return paid.keySet().stream().allMatch(recipe::containsKey) ? full ? 2 : 1 : 0;
    }
}
