package com.gpmanager;

import static com.gpmanager.GameData.msg;
import static com.gpmanager.PriceSource.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static java.util.Locale.*;

import java.util.*;
import java.util.regex.*;
import lombok.*;
import net.runelite.api.gameval.ItemID;

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
        SAILORS_AMULET,
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
        SERPENTINE_HELM,
        CRYSTAL_HELM,
        CRYSTAL_BODY,
        CRYSTAL_LEGS,
        CRYSTAL_SHIELD,
        SUFFERING,
        TONALZTICS,
        TOME_OF_WATER,
        TOME_OF_EARTH,
        ETHEREUM,
        BONE_STAFF,
        CRYSTAL_AXE,
        CRYSTAL_PICKAXE,
        CRYSTAL_HARPOON,
        ECHO_BOOTS,
        SLAYER_STAFF_E,
        INFERNAL_AXE,
        INFERNAL_PICKAXE,
        INFERNAL_HARPOON;
        String displayName;
        boolean implemented;
        String family;
        ActionKind action;
        int units;

        /** A load's components can never be refunded: the load books as spend, not a transfer. */
        boolean committedLoad;

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
            for (String[] row : GameData.rows("committed_loads")) {
                valueOf(row[0]).committedLoad = true;
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
        if (lower.startsWith("the amulet has ") || lower.startsWith("your amulet has ")) {
            // The sailors' amulet's own Check wording ("...has 106 charges." or "...charges
            // left."); the blood fury's hit-count line names itself below (owner 2026-10-14).
            return ChargedItem.SAILORS_AMULET;
        }
        if (lower.startsWith("your tome has been charged with "))
        {
            // The page names the tome; a bare "your tome" line stays fire and the clicked row
            // redirects it (owner 2026-10-12).
            if (lower.contains("soaked")) return ChargedItem.TOME_OF_WATER;
            if (lower.contains("soiled")) return ChargedItem.TOME_OF_EARTH;
            return ChargedItem.TOME_OF_FIRE;
        }
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

    /** The same measured count expressed in another same-pattern variant's own recipe. */
    static Map<Integer, Long> restock(
            ChargedItem target, ChargedItem source, Map<Integer, Long> counts) {
        if (counts.isEmpty() || source.recipe.isEmpty()) return counts;
        long unit = source.recipe.values().iterator().next();
        long count = counts.values().iterator().next() / unit;
        var stock = new LinkedHashMap<Integer, Long>();
        for (var part : target.recipe.entrySet()) stock.put(part.getKey(), count * part.getValue());
        return stock;
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

/** A load target's candidate losses; only a measured Check can reconcile their quantities. */
@AllArgsConstructor
class LoadTarget {
    ChargeCheck.ChargedItem variant;
    String targetIdentity;
    List<Flow> remaining;
    List<Flow> ambiguousCandidates;

    /** When the load was clicked; 0 when there was no load. */
    long clickedAt;
}

/**
 * Transient ownership of automatic charge estimates. The canonical receipts remain in the owning
 * {@link Session}; this index links one weapon (session and target identity) to those receipt ids
 * until a compatible measured Check closes the window. Never persisted.
 */
class ChargeEstimateJournal {
    static final int MAX_RECEIPTS = 512;

    static final class Receipt {
        final String key;
        final String transactionId;
        final int itemId;
        long remainingQuantity;

        Receipt(String key, String transactionId, int itemId, long quantity) {
            this.key = key;
            this.transactionId = transactionId;
            this.itemId = itemId;
            remainingQuantity = quantity;
        }
    }

    /** Open receipts per weapon, oldest weapon first. */
    final LinkedHashMap<String, List<Receipt>> entries = new LinkedHashMap<>();

    /** Quantities evicted by the bound while their weapon's window is still open, per item. */
    final Map<String, Map<Integer, Long>> evicted = new HashMap<>();

    int receiptCount;

    static String key(String sessionId, String targetIdentity) {
        return Phrases.lower(sessionId) + "\n" + Phrases.lower(targetIdentity);
    }

    synchronized void clear() {
        entries.clear();
        evicted.clear();
        receiptCount = 0;
    }

    synchronized void record(
            String sessionId,
            String targetIdentity,
            String transactionId,
            int itemId,
            long quantity) {
        if (Phrases.blank(sessionId)
                || Phrases.blank(targetIdentity)
                || Phrases.blank(transactionId)
                || itemId <= 0
                || quantity <= 0L) {
            return;
        }
        String key = key(sessionId, targetIdentity);
        entries.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new Receipt(key, transactionId, itemId, quantity));
        receiptCount++;
        while (receiptCount > MAX_RECEIPTS) {
            Map.Entry<String, List<Receipt>> oldest = entries.entrySet().iterator().next();
            Receipt gone = oldest.getValue().remove(0);
            receiptCount--;
            // The window is still open: remember the evicted quantity so the next measured
            // delta is not booked on top of an estimate that is no longer tracked here.
            evicted.computeIfAbsent(gone.key, ignored -> new HashMap<>())
                    .merge(gone.itemId, gone.remainingQuantity, Long::sum);
            if (oldest.getValue().isEmpty()) entries.remove(oldest.getKey());
        }
    }

    /** Evicted quantity still inside this weapon's open window for one component. */
    synchronized long evictedOverlap(String sessionId, String targetIdentity, int itemId) {
        return evicted.getOrDefault(key(sessionId, targetIdentity), Map.of())
                .getOrDefault(itemId, 0L);
    }

    /** Pending receipts for one component of one weapon. */
    synchronized List<Receipt> receipts(String sessionId, String targetIdentity, int itemId) {
        List<Receipt> result = new ArrayList<>();
        for (Receipt receipt : receiptsFor(sessionId, targetIdentity))
            if (receipt.itemId == itemId) result.add(receipt);
        return result;
    }

    /** Every pending receipt for one weapon, whichever component it belongs to. */
    synchronized List<Receipt> receiptsFor(String sessionId, String targetIdentity) {
        return new ArrayList<>(entries.getOrDefault(key(sessionId, targetIdentity), List.of()));
    }

    synchronized void consume(Receipt receipt, long quantity) {
        List<Receipt> values = receipt == null ? null : entries.get(receipt.key);
        if (values == null || quantity <= 0L || !values.contains(receipt)) return;
        receipt.remainingQuantity = Math.max(0L, receipt.remainingQuantity - quantity);
        if (receipt.remainingQuantity == 0L) {
            values.remove(receipt);
            receiptCount--;
        }
        if (values.isEmpty()) entries.remove(receipt.key);
    }

    /** Forget every receipt, and all evicted overlap, for one weapon (its window closed). */
    synchronized void clearTarget(String sessionId, String targetIdentity) {
        String key = key(sessionId, targetIdentity);
        List<Receipt> removed = entries.remove(key);
        if (removed != null) receiptCount -= removed.size();
        evicted.remove(key);
    }
}

/** Measured negative component-count differences; valuation is applied by the caller. */
@Getter
class ChargeDelta {
    ChargeCheck.ChargedItem variant;
    List<ComponentDelta> componentDeltas;

    /** Components the Check read at the same count as before: their estimates were overcounts. */
    final Set<Integer> unchanged = new HashSet<>();

    /** Explicit Check outcome; this enum is transient accounting evidence, never save data. */
    enum CheckResult {
        SEED,
        SAME,
        SPEND,
        LOAD,
        MISMATCH
    }

    CheckResult result;

    ChargeDelta(ChargeCheck.ChargedItem variant, List<ComponentDelta> deltas, CheckResult result) {
        this.variant = variant;
        componentDeltas = deltas;
        this.result = result;
    }

    /** A caller supplying measured losses explicitly constructs SPEND, as before. */
    ChargeDelta(ChargeCheck.ChargedItem variant, List<ComponentDelta> deltas) {
        this(variant, deltas, CheckResult.SPEND);
    }

    /** An outcome without a measured decrease carries no bookable component quantity. */
    ChargeDelta(ChargeCheck.ChargedItem variant, CheckResult result) {
        this(variant, Collections.emptyList(), result);
    }

    /** One component's measured decrease (always negative). */
    @Getter
    @AllArgsConstructor
    static class ComponentDelta {
        int itemId;
        long quantityDelta;
    }

    /**
     * The GP cost of these priced component losses when they exactly match every measured decrease;
     * 0 otherwise. Missing, partial, extra, unsupported-priced or overflowing flows fail closed as
     * a whole. Generic inventory losses can never authorize charge spend.
     */
    long exactCost(List<Flow> pricedLosses) {
        if (result != CheckResult.SPEND
                || variant == null
                || !variant.implemented
                || componentDeltas.isEmpty()
                || pricedLosses == null
                || pricedLosses.size() != componentDeltas.size()) {
            return 0L;
        }
        var expected = new HashMap<Integer, Long>();
        var actual = new HashMap<Integer, Long>();
        long cost = 0L;
        try {
            for (ComponentDelta component : componentDeltas) {
                if (component.itemId <= 0) return 0L;
                expected.merge(component.itemId, component.quantityDelta, Math::addExact);
            }
            for (Flow flow : pricedLosses) {
                boolean validPriceSource =
                        (flow.itemId == ItemID.COINS
                                        ? flow.unitPrice == 1 && flow.getPriceSource() == FACE_VALUE
                                        : flow.getPriceSource() == GRAND_EXCHANGE
                                                || flow.getPriceSource() == MANUAL_OVERRIDE);
                if (!validPriceSource
                        || flow.quantityDelta >= 0L
                        || flow.itemId <= 0
                        || flow.unitPrice <= 0
                        || flow.valueDelta >= 0L
                        || multiplyExact(flow.quantityDelta, (long) flow.unitPrice)
                                != flow.valueDelta) {
                    return 0L;
                }
                actual.merge(flow.itemId, flow.quantityDelta, Math::addExact);
                cost = addExact(cost, negateExact(flow.valueDelta));
            }
        } catch (ArithmeticException ex) {
            return 0L;
        }
        return expected.equals(actual) ? cost : 0L;
    }
}

/**
 * Compares explicit charge reads. One read establishes a baseline; only measured component
 * decreases can produce a booking-ready delta.
 */
class MeasuredChargeReadTracker {
    /**
     * Bounded number of same-variant weapons tracked side by side (two blowpipes, a spare trident).
     */
    static final int MAX_TARGETS = 8;

    /**
     * One baseline per stable target identity, most recently read last: each weapon's last read,
     * which its next Check is measured against.
     */
    LinkedHashMap<String, Baseline> baselines = new LinkedHashMap<>();

    /** One weapon's last exact read and its local observation time. */
    @Getter
    @AllArgsConstructor
    static class Baseline {
        ChargeCheck read;
        long atEpochMillis;

        /**
         * Component units loaded into the weapon since this read, which its next Check adds back.
         */
        final Map<Integer, Long> loaded = new HashMap<>();
    }

    /**
     * A load into one weapon, clicked at {@code clickedAt}: its next Check counts it as loaded, not
     * as unused. A load clicked before the last read is already in that read. A load whose weapon
     * is unknown goes to the only weapon of that kind with a read, or else makes that kind's reads
     * untrustworthy.
     */
    synchronized void noteLoad(
            ChargeCheck.ChargedItem variant,
            String targetIdentity,
            long clickedAt,
            int itemId,
            long units) {
        Baseline last =
                targetIdentity == null ? only(variant) : baselines.get(targetIdentity.trim());
        if (targetIdentity == null && last == null) {
            resetVariant(variant);
            return;
        }
        if (last == null || clickedAt < last.atEpochMillis || units <= 0L) return;
        last.loaded.merge(itemId, units, SafeMath::safeAdd);
    }

    /** Observe one exact Check read and retain its local observation time. */
    synchronized ChargeDelta observe(ChargeCheck read, String targetIdentity, long now) {
        if (read == null) return new ChargeDelta(null, ChargeDelta.CheckResult.MISMATCH);
        if (!read.bookable || Phrases.blank(targetIdentity)) {
            reset();
            return new ChargeDelta(read.variant, ChargeDelta.CheckResult.MISMATCH);
        }
        String safeIdentity = targetIdentity.trim();
        // Each weapon keeps its own baseline: a Check of the other blowpipe is neither a
        // decrease on this one nor a reason to forget what this one last read.
        Baseline last = baselines.remove(safeIdentity);
        baselines.put(safeIdentity, new Baseline(read, SafeMath.nonNeg(now)));
        while (baselines.size() > MAX_TARGETS)
            baselines.remove(baselines.keySet().iterator().next());
        ChargeCheck previous = last == null ? null : last.read;
        if (previous == null) return new ChargeDelta(read.variant, ChargeDelta.CheckResult.SEED);
        if (previous.variant != read.variant)
            return new ChargeDelta(read.variant, ChargeDelta.CheckResult.MISMATCH);
        boolean blowpipe = read.variant == ChargeCheck.ChargedItem.BLOWPIPE;
        // Dart type is an independent component identity. A type switch can return old darts
        // and load new ones, so do not infer dart spend from the change; scale measurements
        // remain valid across that switch.
        boolean dartTypeSwitch =
                blowpipe
                        && read.hasDarts()
                        && (!previous.hasDarts() || previous.dartItemId != read.dartItemId);
        Map<Integer, Long> previousCounts = previous.componentCounts;
        Map<Integer, Long> loaded = last.loaded;
        var nextCounts = new LinkedHashMap<Integer, Long>(read.componentCounts);
        if (blowpipe && previous.hasDarts() && !read.hasDarts())
            nextCounts.put(previous.dartItemId, 0L);
        var itemIds = new LinkedHashSet<Integer>(previousCounts.keySet());
        itemIds.addAll(nextCounts.keySet());
        var decreases = new ArrayList<ChargeDelta.ComponentDelta>();
        var unchanged = new ArrayList<Integer>();
        boolean same = true;
        for (int itemId : itemIds) {
            long before = previousCounts.getOrDefault(itemId, 0L);
            // What was loaded since is not use: count it as if it had been there all along.
            long after = nextCounts.getOrDefault(itemId, 0L) - loaded.getOrDefault(itemId, 0L);
            same &= after == before;
            if (after == before) unchanged.add(itemId);
            if (after < before && (!dartTypeSwitch || itemId == ItemID.SNAKEBOSS_SCALE)) {
                decreases.add(new ChargeDelta.ComponentDelta(itemId, after - before));
            }
        }
        // Independent decreases still prove spend when a different component was loaded.
        var delta =
                new ChargeDelta(
                        read.variant,
                        decreases,
                        !decreases.isEmpty()
                                ? ChargeDelta.CheckResult.SPEND
                                : same
                                        ? ChargeDelta.CheckResult.SAME
                                        : ChargeDelta.CheckResult.LOAD);
        delta.unchanged.addAll(unchanged);
        return delta;
    }

    synchronized void reset() {
        baselines.clear();
    }

    /** The one baseline of this kind, or null when there are none or several. */
    Baseline only(ChargeCheck.ChargedItem variant) {
        Baseline found = null;
        for (Baseline last : baselines.values()) {
            if (last.read.variant != variant) continue;
            if (found != null) return null;
            found = last;
        }
        return found;
    }

    /**
     * Forget one variant's baselines after a custody change: a new physical instance of the weapon
     * entered the inventory, so a stored baseline can no longer be trusted to belong to the item
     * now occupying the slot. The next Check seeds a fresh baseline.
     */
    synchronized void resetVariant(ChargeCheck.ChargedItem variant) {
        if (variant == null) return;
        baselines.values().removeIf(last -> last.read.variant == variant);
    }
}
