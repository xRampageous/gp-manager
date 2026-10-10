package com.gpmanager;

import java.util.*;

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
            String name = flow == null ? "" : flow.itemName.toLowerCase(Locale.ROOT);
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
