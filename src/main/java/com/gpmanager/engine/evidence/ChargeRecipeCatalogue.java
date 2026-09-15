package com.gpmanager.engine.evidence;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Documented charge-component recipes and explicit offline implementation
 * status. Unimplemented families remain catalogue-only.
 */
public final class ChargeRecipeCatalogue
{
    public static final class Recipe
    {
        public final String familyId;
        public final String displayName;
        /** Known component item ids when fixed; empty when Check-only / override. */
        public final int[] componentItemIds;
        public final String calibrationHint;
        public final boolean implemented;

        public Recipe(
            String familyId,
            String displayName,
            int[] componentItemIds,
            String calibrationHint,
            boolean implemented)
        {
            this.familyId = familyId;
            this.displayName = displayName;
            this.componentItemIds = componentItemIds == null ? new int[0] : componentItemIds.clone();
            this.calibrationHint = calibrationHint == null ? "" : calibrationHint;
            this.implemented = implemented;
        }
    }

    /**
     * Load evidence policy for one exact measured-read variant. Trident components
     * are derived from charge increases; the blowpipe exposes only its independently
     * measured Zulrah scale count because dart-per-charge ratios are not established.
     */
    private static final class LoadRecipe
    {
        private final int directlyMeasuredComponentId;
        private final Map<Integer, Long> componentsPerCharge;

        private LoadRecipe(int directlyMeasuredComponentId, Map<Integer, Long> componentsPerCharge)
        {
            this.directlyMeasuredComponentId = directlyMeasuredComponentId;
            this.componentsPerCharge = Collections.unmodifiableMap(
                new LinkedHashMap<>(componentsPerCharge));
        }
    }

    // Common charge components (canonical ids).
    private static final int ZULRAH_SCALES = 12934;
    private static final int COINS = 995;
    private static final int DEATH_RUNE = 560;
    private static final int CHAOS_RUNE = 562;
    private static final int FIRE_RUNE = 554;
    private static final int WATER_RUNE = 555;
    private static final int BLOOD_RUNE = 565;
    private static final int SOUL_RUNE = 566;
    private static final int WRATH_RUNE = 21880;
    private static final int CRYSTAL_SHARD = 23866;
    private static final int CRYSTAL_DUST = 23964;
    private static final int ANCIENT_SHARD = 19681;
    private static final int DEMON_TEAR = 29555;

    private static final Map<String, Recipe> BY_FAMILY;
    private static final Map<MeasuredChargeRead.Variant, LoadRecipe> LOAD_RECIPES;

    static
    {
        Map<String, Recipe> map = new LinkedHashMap<>();
        put(map, ChargeFamilyIds.BLOWPIPE, "Toxic blowpipe",
            new int[]{ZULRAH_SCALES}, "Check blowpipe after loading scales/darts.", true);
        put(map, ChargeFamilyIds.TRIDENT, "Trident of the seas/swamp",
            new int[]{DEATH_RUNE, CHAOS_RUNE, FIRE_RUNE, ZULRAH_SCALES, COINS},
            "Check trident to measure rune/scale charge cost.", true);
        put(map, ChargeFamilyIds.SCYTHE, "Scythe of vitur",
            new int[]{BLOOD_RUNE}, "Check scythe after charging with blood runes / vials.");
        put(map, ChargeFamilyIds.SANGUINESTI, "Sanguinesti staff",
            new int[]{BLOOD_RUNE}, "Check sanguinesti after charging.");
        put(map, ChargeFamilyIds.TUMEKEN_SHADOW, "Tumeken's shadow",
            new int[]{SOUL_RUNE, CHAOS_RUNE, WRATH_RUNE},
            "Check Tumeken's shadow after charging.");
        put(map, ChargeFamilyIds.IBAN_STAFF, "Iban's staff",
            new int[]{},
            "Check Iban's staff: assumed cast component must be confirmed (or set manual price).");
        put(map, ChargeFamilyIds.SAELDOR, "Blade of saeldor",
            new int[]{CRYSTAL_SHARD, CRYSTAL_DUST},
            "Check Blade of saeldor — crystal shard/dust degrade after calibrate.");
        put(map, ChargeFamilyIds.EYE_OF_AYAK, "Eye of Ayak",
            new int[]{DEMON_TEAR},
            "Check Eye of Ayak — calibrate rune vs demon-tear charge mode.");
        put(map, "warped_sceptre", "Warped sceptre", new int[]{}, "Check Warped sceptre to calibrate.");
        put(map, "abyssal_tentacle", "Abyssal tentacle", new int[]{}, "Check tentacle whip charges.");
        put(map, "crystal_weapon", "Crystal weapon/armour",
            new int[]{CRYSTAL_SHARD}, "Check crystal gear after charging with shards.");
        put(map, "tome_of_fire", "Tome of fire", new int[]{FIRE_RUNE}, "Check Tome of fire pages/charges.");
        put(map, "tome_of_water", "Tome of water", new int[]{WATER_RUNE}, "Check Tome of water.");
        put(map, "arclight", "Arclight", new int[]{ANCIENT_SHARD}, "Check Arclight after charging.");
        put(map, "craws_bow", "Craw's bow / Webweaver", new int[]{}, "Check Craw's/Webweaver charges.");
        put(map, "viggoras_chainmace", "Viggora's / Ursine", new int[]{}, "Check Viggora's/Ursine charges.");
        put(map, "thammarons_sceptre", "Thammaron's / Accursed", new int[]{}, "Check Thammaron's/Accursed.");
        put(map, "bow_of_faerdhinen", "Bow of faerdhinen",
            new int[]{CRYSTAL_SHARD}, "Check Bow of faerdhinen crystal charges.");
        put(map, "serpentine_helm", "Serpentine helm",
            new int[]{ZULRAH_SCALES}, "Check serpentine helm scales.");
        put(map, "amulet_of_blood_fury", "Amulet of blood fury",
            new int[]{BLOOD_RUNE}, "Check blood fury charges.");
        put(map, ChargeFamilyIds.RING_OF_SUFFERING, "Ring of suffering",
            new int[]{}, "Check Ring of suffering (i) to calibrate.");
        put(map, "kharedsts_memoirs", "Kharedst's memoirs", new int[]{}, "Check memoirs charges.");
        put(map, "ash_sanctifier", "Ash sanctifier", new int[]{}, "Check ash sanctifier.");
        put(map, "blood_essence", "Blood essence", new int[]{BLOOD_RUNE}, "Check blood essence.");
        put(map, "bottomless_compost", "Bottomless compost bucket",
            new int[]{}, "Check bottomless compost bucket fill state.");
        put(map, "cannonballs", "Dwarf multicannon", new int[]{}, "Cannonballs as ammo Used when fired.");
        BY_FAMILY = Collections.unmodifiableMap(map);

        Map<MeasuredChargeRead.Variant, LoadRecipe> loadRecipes =
            new EnumMap<>(MeasuredChargeRead.Variant.class);
        loadRecipes.put(MeasuredChargeRead.Variant.TRIDENT_SEAS,
            perCharge(
                new int[]{DEATH_RUNE, CHAOS_RUNE, FIRE_RUNE, COINS},
                new long[]{1L, 1L, 5L, 10L}));
        loadRecipes.put(MeasuredChargeRead.Variant.TRIDENT_SWAMP,
            perCharge(
                new int[]{DEATH_RUNE, CHAOS_RUNE, FIRE_RUNE, ZULRAH_SCALES},
                new long[]{1L, 1L, 5L, 1L}));
        loadRecipes.put(MeasuredChargeRead.Variant.TRIDENT_SWAMP_ENHANCED,
            perCharge(
                new int[]{DEATH_RUNE, CHAOS_RUNE, FIRE_RUNE, ZULRAH_SCALES},
                new long[]{1L, 1L, 5L, 1L}));
        loadRecipes.put(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            new LoadRecipe(ZULRAH_SCALES, Collections.emptyMap()));
        LOAD_RECIPES = Collections.unmodifiableMap(loadRecipes);
    }

    private ChargeRecipeCatalogue()
    {
    }

    /**
     * Returns exact positive component quantities evidenced by two consecutive
     * Check snapshots for the same supported variant. An empty map means that
     * these reads do not establish a load quantity. Blowpipe dart changes are
     * deliberately excluded; only the Check's Zulrah scale-count increase is
     * accepted. Trident quantities use the variant-specific components-per-charge
     * recipe. The method never returns a partial recipe on overflow.
     */
    public static Map<Integer, Long> exactLoadQuantities(
        @Nullable MeasuredChargeRead previous,
        @Nullable MeasuredChargeRead current)
    {
        if (previous == null || current == null
            || !previous.isBookable() || !current.isBookable()
            || previous.getVariant() != current.getVariant())
        {
            return Collections.emptyMap();
        }

        LoadRecipe recipe = LOAD_RECIPES.get(current.getVariant());
        if (recipe == null)
        {
            return Collections.emptyMap();
        }

        try
        {
            if (recipe.directlyMeasuredComponentId > 0)
            {
                Long before = previous.getComponentCounts().get(recipe.directlyMeasuredComponentId);
                Long after = current.getComponentCounts().get(recipe.directlyMeasuredComponentId);
                if (before == null || after == null)
                {
                    return Collections.emptyMap();
                }
                long gained = Math.subtractExact(after, before);
                if (gained <= 0L)
                {
                    return Collections.emptyMap();
                }
                return Collections.singletonMap(recipe.directlyMeasuredComponentId, gained);
            }

            long chargesGained = Math.subtractExact(current.getChargeCount(), previous.getChargeCount());
            if (chargesGained <= 0L || recipe.componentsPerCharge.isEmpty())
            {
                return Collections.emptyMap();
            }

            Map<Integer, Long> quantities = new LinkedHashMap<>();
            for (Map.Entry<Integer, Long> component : recipe.componentsPerCharge.entrySet())
            {
                long quantity = Math.multiplyExact(chargesGained, component.getValue());
                if (component.getKey() <= 0 || quantity <= 0L)
                {
                    return Collections.emptyMap();
                }
                quantities.put(component.getKey(), quantity);
            }
            return Collections.unmodifiableMap(quantities);
        }
        catch (ArithmeticException ex)
        {
            return Collections.emptyMap();
        }
    }

    private static LoadRecipe perCharge(int[] itemIds, long[] quantitiesPerCharge)
    {
        Map<Integer, Long> quantities = new LinkedHashMap<>();
        if (itemIds == null || quantitiesPerCharge == null
            || itemIds.length != quantitiesPerCharge.length)
        {
            throw new IllegalArgumentException("Charge recipe component arrays must have matching lengths");
        }
        for (int index = 0; index < itemIds.length; index++)
        {
            if (itemIds[index] <= 0 || quantitiesPerCharge[index] <= 0L)
            {
                throw new IllegalArgumentException("Charge recipe quantities must be positive");
            }
            quantities.put(itemIds[index], quantitiesPerCharge[index]);
        }
        return new LoadRecipe(-1, quantities);
    }

    private static void put(Map<String, Recipe> map, String familyId, String display, int[] comps, String hint)
    {
        put(map, familyId, display, comps, hint, false);
    }

    private static void put(
        Map<String, Recipe> map,
        String familyId,
        String display,
        int[] comps,
        String hint,
        boolean implemented)
    {
        map.put(normalize(familyId), new Recipe(normalize(familyId), display, comps, hint, implemented));
    }

    @Nullable
    public static Recipe recipeFor(String familyId)
    {
        String key = normalize(familyId);
        return key == null ? null : BY_FAMILY.get(key);
    }

    public static Map<String, Recipe> all()
    {
        return BY_FAMILY;
    }

    public static boolean isKnownFamily(String familyId)
    {
        String key = normalize(familyId);
        return key != null && BY_FAMILY.containsKey(key);
    }

    @Nullable
    private static String normalize(String familyId)
    {
        if (familyId == null)
        {
            return null;
        }
        String key = familyId.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }
}
