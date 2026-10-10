package com.gpmanager;

import static com.gpmanager.GameData.msg;

import static java.util.Collections.*;
import static java.util.Locale.*;

import lombok.*;

import net.runelite.api.gameval.ItemID;

import java.util.*;
import java.util.regex.*;

/**
 * One explicit weapon Check response, expressed as its measured component stock. It does not infer
 * depletion from combat events, menu intent, or probabilities.
 */
@AllArgsConstructor
class ChargeCheck {
    static final int SCALES = ItemID.SNAKEBOSS_SCALE;
    static final Pattern BLOWPIPE_CHECK = GameData.pattern("blowpipe-check");
    static final Pattern TYPED_DARTS = GameData.pattern("dart-count");
    static final Pattern EYE_OF_AYAK_CHECK = GameData.pattern("eye-tears-check");
    static final Pattern EYE_RUNE_CHECK = GameData.pattern("eye-runes-check");
    static final Map<String, Integer> DARTS = new LinkedHashMap<>();

    static {
        for (String[] row : GameData.rows("darts")) DARTS.put(row[0], Integer.parseInt(row[1]));
    }

    /**
     * Charged-item families; each one's data (names, ids, triggers, recipe, Check shape) is a d20
     * row.
     */
    @Getter
    enum ChargedItem {
        TRIDENT_SEAS,
        TRIDENT_SWAMP,
        TRIDENT_SWAMP_ENHANCED,
        TRIDENT_SEAS_ENHANCED,
        BLOWPIPE,
        SHADOW,
        SANGUINESTI,
        SANGUINESTI_HOLY,
        WARPED,
        VENATOR,
        VENATOR_ECHO,
        EYE_OF_AYAK,
        SCYTHE,
        ATES,
        TOME_OF_FIRE,
        CRYSTAL_BOW,
        CRYSTAL_HALBERD,
        BOWFA,
        SAELDOR,
        BLOOD_FURY,
        CRAW,
        WEBWEAVER,
        VIGGORA,
        URSINE,
        THAMMARON,
        ACCURSED,
        MEMOIRS,
        BRYOPHYTA,
        TOXIC_STAFF,
        SERPENTINE_HELM;
        String displayName;
        boolean implemented;
        String family;
        ActionKind action;
        int units;

        /** The Check line's pattern with its count group and its single-charge group (0: none). */
        Pattern check;

        int countGroup, oneGroup;
        int[] graphics, animations, itemIds;

        /** Components one use spends; a Check's count of uses times this is its measured stock. */
        Map<Integer, Long> recipe;

        /** A fractional per-use part, [itemId, numerator, denominator], or null. */
        long[] fraction;

        List<String> aliases;

        static {
            for (String[] row : GameData.rows("charged_items")) {
                ChargedItem item = valueOf(row[0]);
                item.displayName = row[1];
                item.implemented = Boolean.parseBoolean(row[2]);
                item.family = row[3];
                item.action = ActionKind.valueOf(row[4]);
                item.units = Integer.parseInt(row[5]);
                if (!"-".equals(row[6])) {
                    String[] check = row[6].split(" ");
                    item.check = GameData.pattern(check[0]);
                    item.countGroup = Integer.parseInt(check[1]);
                    item.oneGroup = Integer.parseInt(check[2]);
                }
                item.graphics = ids(row[7]);
                item.animations = ids(row[8]);
                var recipe = new LinkedHashMap<Integer, Long>();
                for (String part : parts(row[9], " "))
                    recipe.put(id(part), Long.parseLong(part.substring(0, part.indexOf('*'))));
                item.recipe = unmodifiableMap(recipe);
                if (!"-".equals(row[10])) {
                    String[] fraction = row[10].split("[/*]");
                    item.fraction =
                            new long[] {
                                id(row[10]),
                                Long.parseLong(fraction[0]),
                                Long.parseLong(fraction[1])
                            };
                }
                item.aliases = parts(row[11], ";");
                item.itemIds = ids(row[12]);
            }
        }

        static List<String> parts(String cell, String separator) {
            return "-".equals(cell) ? emptyList() : List.of(cell.split(separator));
        }

        /** The id in "qty*id=NAME" or "id=NAME". */
        static int id(String part) {
            return Integer.parseInt(part.substring(part.indexOf('*') + 1, part.indexOf('=')));
        }

        static int[] ids(String cell) {
            return parts(cell, ",").stream().mapToInt(ChargedItem::id).toArray();
        }
    }

    ChargedItem variant;
    Map<Integer, Long> componentCounts;

    /** Returns -1 when a Check explicitly reported no darts or had an unknown type. */
    int dartItemId;

    boolean bookable;

    /**
     * Parse a pinned Check-message shape. Returns {@code null} for unrelated chat. Recognized but
     * unsupported/unknown variants return a non-bookable read so a tracker can clear an older
     * baseline safely.
     */
    static ChargeCheck parse(String rawMessage) {
        String message = stripTags(rawMessage);
        ChargedItem surface = message == null ? null : surface(message.toLowerCase(ROOT));
        if (surface == null) return null;
        // A recognizably malformed Check surface resets the caller's old baseline
        // rather than carrying stale evidence forward.
        var components = new LinkedHashMap<Integer, Long>();
        int dartId = -1;
        try {
            Matcher blowpipe = BLOWPIPE_CHECK.matcher(message);
            if (blowpipe.matches()) {
                components.put(SCALES, parseCount(blowpipe.group(2)));
                String dartPart = blowpipe.group(1).trim();
                Matcher darts = TYPED_DARTS.matcher(dartPart);
                if (!"none".equalsIgnoreCase(dartPart)) {
                    dartId =
                            darts.matches()
                                    ? DARTS.getOrDefault(normalizeItemName(darts.group(1)), -1)
                                    : -1;
                    if (dartId < 0) return unsupported(surface);
                    components.put(dartId, parseCount(darts.group(2)));
                }
            } else if (surface == ChargedItem.EYE_OF_AYAK) {
                Matcher eye = EYE_OF_AYAK_CHECK.matcher(message);
                Matcher runes = EYE_RUNE_CHECK.matcher(message);
                if (eye.matches()) {
                    components.put(ItemID.DEMON_TEAR, parseCount(eye.group(1)));
                } else if (runes.matches()) {
                    long charges = parseCount(runes.group(1));
                    components.put(ItemID.DEATHRUNE, Math.multiplyExact(charges, 2L));
                    components.put(ItemID.CHAOSRUNE, charges);
                }
            } else if (surface.check != null) {
                // Every other family's Check counts uses, so its stock is that count times one
                // use's recipe.
                Matcher read = surface.check.matcher(message);
                if (read.matches()) {
                    String counted = read.group(surface.countGroup);
                    long count =
                            counted != null
                                    ? parseCount(counted)
                                    : surface.oneGroup > 0 && read.group(surface.oneGroup) != null
                                            ? 1L
                                            : 0L;
                    for (var part : surface.recipe.entrySet())
                        components.put(part.getKey(), Math.multiplyExact(count, part.getValue()));
                }
            }
            if (!components.isEmpty())
                return new ChargeCheck(surface, unmodifiableMap(components), dartId, true);
        } catch (NumberFormatException | ArithmeticException malformed) {
            // The count is syntax-checked; this is a value outside the supported range.
        }
        return unsupported(surface);
    }

    /** The Check surface a lowercase chat line belongs to, whether or not it parses. */
    static ChargedItem surface(String lower) {
        if (lower.startsWith("darts:") && lower.contains("scales:")) return ChargedItem.BLOWPIPE;
        if (lower.startsWith("the pendant has ")) return ChargedItem.ATES;
        if (lower.startsWith("your tome has been charged with ")) return ChargedItem.TOME_OF_FIRE;
        if (lower.startsWith(msg("amulet-blood"))) return ChargedItem.BLOOD_FURY;
        if (lower.startsWith(msg("eye-ayak-been"))
                || lower.startsWith(msg("eye-ayak-been-charged"))) {
            return ChargedItem.EYE_OF_AYAK;
        }
        for (ChargedItem variant : ChargedItem.values()) {
            if (variant == ChargedItem.BLOWPIPE) continue;
            String name = variant.displayName.toLowerCase(ROOT);
            if (lower.startsWith("your " + name + " has ")
                    || lower.startsWith("your " + name + " only has ")
                    || lower.startsWith(name + " has ")
                    || lower.startsWith(name + " only has ")) {
                return variant;
            }
        }
        // A Check that names no weapon ("Your bow has…"): the first row sharing that wording.
        for (ChargedItem variant : ChargedItem.values()) {
            if (variant.check != null && variant.check.matcher(lower).matches()) return variant;
        }
        return null;
    }

    /** Resolve only exact supported menu-target names; menu evidence is identity only. */
    static ChargedItem supportedForName(String itemName) {
        String name = normalizeItemName(itemName);
        if (name == null) return null;
        for (ChargedItem variant : ChargedItem.values()) {
            if (variant.implemented
                    && (variant.displayName.toLowerCase(ROOT).equals(name)
                            || variant.aliases.contains(name))) {
                return variant;
            }
        }
        return null;
    }

    /**
     * Any catalogue variant by exact display name for presentation; estimate-only variants
     * included.
     */
    static ChargedItem variantForName(String itemName) {
        String name = normalizeItemName(itemName);
        for (ChargedItem variant : ChargedItem.values()) {
            if (variant.displayName.toLowerCase(ROOT).equals(name)) return variant;
        }
        return null;
    }

    /** Resolve charged and uncharged item ids so a Check receipt can be tied to its menu target. */
    static ChargedItem supportedForId(int itemId) {
        for (ChargedItem variant : ChargedItem.values()) {
            for (int id : variant.itemIds) {
                if (id == itemId) return variant;
            }
        }
        return null;
    }

    /**
     * Validate a candidate item-on-weapon load component. Both the canonical id and displayed name
     * must agree, so unknown dart types fail closed.
     */
    static boolean isLoadComponent(ChargedItem variant, int itemId, String itemName) {
        String expectedName =
                variant == null || !variant.implemented ? null : expectedLoadName(variant, itemId);
        String actualName = normalizeItemName(itemName);
        // Owner 1.2: what an item's own charge is made of is loaded, never spent, for every item.
        if (actualName != null
                && variant != null
                && (variant.recipe.containsKey(itemId)
                        || variant.fraction != null && variant.fraction[0] == itemId)) return true;
        return expectedName != null
                && actualName != null
                && (expectedName.equals(actualName)
                        || actualName.equals(expectedName + "s") && !expectedName.endsWith("s"));
    }

    boolean hasDarts() {
        return dartItemId >= 0;
    }

    /** Component units per priced item: a page feeds 20 charges, a shard 100. */
    static int unitsPerItem(ChargedItem variant, int componentItemId) {
        return variant.units;
    }

    static ChargeCheck unsupported(ChargedItem variant) {
        return new ChargeCheck(variant, emptyMap(), -1, false);
    }

    /**
     * A load component not in the item's own recipe: the blowpipe's darts, and the Eye of Ayak's
     * runes in rune mode (owner 2026-09-30). Everything else loads through its recipe.
     */
    static String expectedLoadName(ChargedItem variant, int itemId) {
        if (variant == ChargedItem.BLOWPIPE) {
            for (Map.Entry<String, Integer> dart : DARTS.entrySet()) {
                if (dart.getValue() == itemId) return dart.getKey();
            }
        }
        if (variant != ChargedItem.EYE_OF_AYAK) return null;
        return itemId == ItemID.DEATHRUNE
                ? "death rune"
                : itemId == ItemID.CHAOSRUNE ? "chaos rune" : null;
    }

    static long parseCount(String raw) {
        return Long.parseLong(raw.replace(",", ""));
    }

    static String stripTags(String value) {
        return value == null ? null : value.replaceAll("<[^>]*>", "").trim();
    }

    static String normalizeItemName(String value) {
        String cleaned = stripTags(value);
        return Phrases.empty(cleaned) ? null : cleaned.toLowerCase(ROOT).replaceAll("\\s+", " ");
    }
}
