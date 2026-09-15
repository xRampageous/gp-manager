package com.gpmanager.engine.evidence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Special bags/sacks/barrels/kits/pouches. Store/retrieve is ownership-neutral
 * ({@link ContainerEvidence}); contents valuation follows snapshots — never invent gain/loss.
 */
public final class UtilityContainerCatalogue
{
    public enum Ambiguity
    {
        NONE,
        /** Plank sack same-tick invent mixes — Review, never silent invent. */
        UNCERTAIN_REVIEW
    }

    public static final class Entry
    {
        public final String familyId;
        public final String displayName;
        public final boolean requiresWidgetCheck;
        public final Ambiguity defaultAmbiguity;

        public Entry(String familyId, String displayName, boolean requiresWidgetCheck, Ambiguity ambiguity)
        {
            this.familyId = familyId;
            this.displayName = displayName;
            this.requiresWidgetCheck = requiresWidgetCheck;
            this.defaultAmbiguity = ambiguity == null ? Ambiguity.NONE : ambiguity;
        }
    }

    private static final Map<String, Entry> BY_FAMILY;
    private static final Map<String, String> NAME_HINTS;

    static
    {
        Map<String, Entry> map = new LinkedHashMap<>();
        map.put(ChargeFamilyIds.HERB_SACK, new Entry(ChargeFamilyIds.HERB_SACK, "Herb sack", false, Ambiguity.NONE));
        map.put("silk_lined_herb_sack", new Entry("silk_lined_herb_sack", "Silk-lined herb sack", false, Ambiguity.NONE));
        map.put(ChargeFamilyIds.SEED_BOX, new Entry(ChargeFamilyIds.SEED_BOX, "Seed box", false, Ambiguity.NONE));
        map.put(ChargeFamilyIds.COAL_BAG, new Entry(ChargeFamilyIds.COAL_BAG, "Coal bag", false, Ambiguity.NONE));
        map.put(ChargeFamilyIds.FISH_BARREL, new Entry(ChargeFamilyIds.FISH_BARREL, "Fish barrel", false, Ambiguity.NONE));
        map.put(ChargeFamilyIds.LOG_BASKET, new Entry(ChargeFamilyIds.LOG_BASKET, "Log basket", false, Ambiguity.NONE));
        map.put(ChargeFamilyIds.GEM_BAG, new Entry(ChargeFamilyIds.GEM_BAG, "Gem bag", false, Ambiguity.NONE));
        map.put("looting_bag", new Entry("looting_bag", "Looting bag", false, Ambiguity.NONE));
        map.put("pre_pot_device", new Entry("pre_pot_device", "Pre-pot device", false, Ambiguity.NONE));
        map.put(ChargeFamilyIds.FORESTRY_KIT, new Entry(
            ChargeFamilyIds.FORESTRY_KIT, "Forestry kit", true, Ambiguity.NONE));
        map.put(ChargeFamilyIds.PLANK_SACK, new Entry(
            ChargeFamilyIds.PLANK_SACK, "Plank sack", false, Ambiguity.UNCERTAIN_REVIEW));
        map.put(ChargeFamilyIds.ESSENCE_POUCH, new Entry(
            ChargeFamilyIds.ESSENCE_POUCH, "Essence pouch", false, Ambiguity.NONE));
        // Storage items the wiki lists that GP Per Hour's #58 shows users hit (bolt pouch);
        // all are Fill/Empty ownership moves like the sacks above.
        map.put("bolt_pouch", new Entry("bolt_pouch", "Bolt pouch", false, Ambiguity.NONE));
        map.put("tackle_box", new Entry("tackle_box", "Tackle box", false, Ambiguity.NONE));
        map.put("reagent_pouch", new Entry("reagent_pouch", "Reagent pouch", false, Ambiguity.NONE));
        map.put("huntsmans_kit", new Entry("huntsmans_kit", "Huntsman's kit", false, Ambiguity.NONE));
        map.put("meat_pouch", new Entry("meat_pouch", "Meat pouch", false, Ambiguity.NONE));
        map.put("fur_pouch", new Entry("fur_pouch", "Fur pouch", false, Ambiguity.NONE));
        BY_FAMILY = Collections.unmodifiableMap(map);

        Map<String, String> hints = new LinkedHashMap<>();
        hints.put("herb sack", ChargeFamilyIds.HERB_SACK);
        hints.put("silk-lined herb sack", "silk_lined_herb_sack");
        hints.put("seed box", ChargeFamilyIds.SEED_BOX);
        hints.put("coal bag", ChargeFamilyIds.COAL_BAG);
        hints.put("fish barrel", ChargeFamilyIds.FISH_BARREL);
        hints.put("log basket", ChargeFamilyIds.LOG_BASKET);
        hints.put("gem bag", ChargeFamilyIds.GEM_BAG);
        hints.put("looting bag", "looting_bag");
        hints.put("pre-pot device", "pre_pot_device");
        hints.put("forestry kit", ChargeFamilyIds.FORESTRY_KIT);
        hints.put("plank sack", ChargeFamilyIds.PLANK_SACK);
        hints.put("small pouch", ChargeFamilyIds.ESSENCE_POUCH);
        hints.put("medium pouch", ChargeFamilyIds.ESSENCE_POUCH);
        hints.put("large pouch", ChargeFamilyIds.ESSENCE_POUCH);
        hints.put("giant pouch", ChargeFamilyIds.ESSENCE_POUCH);
        hints.put("colossal pouch", ChargeFamilyIds.ESSENCE_POUCH);
        hints.put("bolt pouch", "bolt_pouch");
        hints.put("tackle box", "tackle_box");
        hints.put("reagent pouch", "reagent_pouch");
        hints.put("huntsman's kit", "huntsmans_kit");
        hints.put("meat pouch", "meat_pouch");
        hints.put("fur pouch", "fur_pouch");
        NAME_HINTS = Collections.unmodifiableMap(hints);
    }

    private UtilityContainerCatalogue()
    {
    }

    @Nullable
    public static Entry entryFor(String familyId)
    {
        String key = norm(familyId);
        return key == null ? null : BY_FAMILY.get(key);
    }

    @Nullable
    public static String familyForItemName(String itemName)
    {
        String lower = norm(itemName);
        if (lower == null)
        {
            return null;
        }
        String exact = NAME_HINTS.get(lower);
        if (exact != null)
        {
            return exact;
        }
        for (Map.Entry<String, String> e : NAME_HINTS.entrySet())
        {
            if (lower.contains(e.getKey()))
            {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Classify a menu option on a known container. Deposit-box Empty → bank is
     * {@link ContainerEvidence.Kind#EMPTY_TO_BANK} (ownership-neutral).
     */
    public static ContainerEvidence.Kind classifyOption(String option, boolean depositBoxOpen)
    {
        String op = norm(option);
        if (op == null)
        {
            return ContainerEvidence.Kind.STORE;
        }
        if ("check".equals(op))
        {
            return ContainerEvidence.Kind.CALIBRATE_CHECK;
        }
        if ("empty".equals(op) || op.startsWith("empty"))
        {
            return depositBoxOpen
                ? ContainerEvidence.Kind.EMPTY_TO_BANK
                : ContainerEvidence.Kind.EMPTY_TO_INVENTORY;
        }
        if ("fill".equals(op) || "open".equals(op) || "use".equals(op))
        {
            return ContainerEvidence.Kind.STORE;
        }
        if ("withdraw".equals(op) || op.startsWith("remove"))
        {
            return ContainerEvidence.Kind.WITHDRAW;
        }
        return ContainerEvidence.Kind.STORE;
    }

    /** True when evidence must stay ownership-neutral (never invent Net). */
    public static boolean isOwnershipNeutral(ContainerEvidence.Kind kind)
    {
        return kind == ContainerEvidence.Kind.STORE
            || kind == ContainerEvidence.Kind.WITHDRAW
            || kind == ContainerEvidence.Kind.EMPTY_TO_BANK
            || kind == ContainerEvidence.Kind.EMPTY_TO_INVENTORY;
    }

    public static Map<String, Entry> all()
    {
        return BY_FAMILY;
    }

    @Nullable
    private static String norm(String value)
    {
        if (value == null)
        {
            return null;
        }
        String key = value.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }
}
