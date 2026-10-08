package com.gpmanager;

import com.gpmanager.Ar.V;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpotanimID;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The charged-item catalogue (d20.tsv), written out here with RuneLite's named constants, must
 * match what the plugin loads: every id, trigger, recipe, unit and name, row by row.
 */
public class ChargeCatalogueTest
{
    private static final Object[] SEAS = {ItemID.DEATHRUNE, 1L, ItemID.CHAOSRUNE, 1L, ItemID.FIRERUNE, 5L,
        ItemID.COINS, 10L};
    private static final Object[] SWAMP = {ItemID.DEATHRUNE, 1L, ItemID.CHAOSRUNE, 1L, ItemID.FIRERUNE, 5L,
        ItemID.SNAKEBOSS_SCALE, 1L};
    private static final int[] NONE = {};

    @Test
    public void everyRowMatchesRuneLiteConstants()
    {
        List<V> checked = new ArrayList<>();
        row(checked, V.TRIDENT_SEAS, "Trident of the Seas", true, "TRIDENT", Au.CAST, 1,
            ints(SpotanimID.SLAYER_TOTS_CASTING), NONE, SEAS, null,
            List.of("trident of the seas (full)", "uncharged trident"),
            ints(ItemID.TOTS, ItemID.TOTS_CHARGED, ItemID.TOTS_UNCHARGED, ItemID.TOTS_CHARGED_ORN, ItemID.TOTS_ORN,
                ItemID.TOTS_UNCHARGED_ORN));
        row(checked, V.TRIDENT_SWAMP, "Trident of the Swamp", true, "TRIDENT", Au.CAST, 1,
            ints(SpotanimID.TOXIC_TOTS_CASTING), NONE, SWAMP, null, List.of("uncharged toxic trident"),
            ints(ItemID.TOXIC_TOTS_CHARGED, ItemID.TOXIC_TOTS_UNCHARGED, ItemID.TOXIC_TOTS_CHARGED_ORN,
                ItemID.TOXIC_TOTS_UNCHARGED_ORN));
        row(checked, V.TRIDENT_SWAMP_ENHANCED, "Trident of the Swamp (e)", true, "TRIDENT", Au.CAST, 1,
            ints(SpotanimID.TOXIC_TOTS_CASTING), NONE, SWAMP, null, List.of("uncharged toxic trident (e)"),
            ints(ItemID.TOXIC_TOTS_I_CHARGED, ItemID.TOXIC_TOTS_I_UNCHARGED, ItemID.TOXIC_TOTS_I_CHARGED_ORN,
                ItemID.TOXIC_TOTS_I_UNCHARGED_ORN));
        row(checked, V.TRIDENT_SEAS_ENHANCED, "Trident of the Seas (e)", true, "TRIDENT", Au.CAST, 1,
            ints(SpotanimID.SLAYER_TOTS_CASTING), NONE, SEAS, null, List.of("uncharged trident (e)"),
            ints(ItemID.TOTS_I_CHARGED, ItemID.TOTS_I_UNCHARGED, ItemID.TOTS_I_CHARGED_ORN,
                ItemID.TOTS_I_UNCHARGED_ORN));
        row(checked, V.V1b, "Toxic blowpipe", true, "BLOWPIPE", Au.CAST, 1, NONE, NONE, new Object[0],
            new long[]{ItemID.SNAKEBOSS_SCALE, 2L, 3L}, List.of(),
            ints(ItemID.TOXIC_BLOWPIPE, ItemID.TOXIC_BLOWPIPE_LOADED, ItemID.TOXIC_BLOWPIPE_ORNAMENT,
                ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT));
        row(checked, V.SHADOW, "Tumeken's shadow", true, "CHARGES", Au.CAST, 1,
            ints(SpotanimID.TUMEKENS_SHADOW_CASTING), NONE, new Object[]{ItemID.SOULRUNE, 2L, ItemID.CHAOSRUNE, 5L},
            null, List.of(), ints(ItemID.TUMEKENS_SHADOW, ItemID.TUMEKENS_SHADOW_UNCHARGED));
        int[] sanguinesti = ints(SpotanimID.SANGUINESTI_STAFF_CASTING, SpotanimID.SANGUINESTI_STAFF_CASTING_JUSTICIAR);
        row(checked, V.SANGUINESTI, "Sanguinesti staff", true, "CHARGES", Au.CAST, 1, sanguinesti, NONE,
            new Object[]{ItemID.BLOODRUNE, 2L}, null, List.of(),
            ints(ItemID.SANGUINESTI_STAFF, ItemID.SANGUINESTI_STAFF_UNCHARGED));
        row(checked, V.SANGUINESTI_HOLY, "Holy sanguinesti staff", true, "CHARGES", Au.CAST, 1, sanguinesti, NONE,
            new Object[]{ItemID.BLOODRUNE, 2L}, null, List.of(),
            ints(ItemID.SANGUINESTI_STAFF_OR, ItemID.SANGUINESTI_STAFF_UNCHARGED_OR));
        row(checked, V.WARPED, "Warped sceptre", true, "CHARGES", Au.CAST, 1, ints(SpotanimID.VFX_WARPED_SCEPTRE_CAST),
            NONE, new Object[]{ItemID.CHAOSRUNE, 2L, ItemID.EARTHRUNE, 5L}, null, List.of(),
            ints(ItemID.WARPED_SCEPTRE, ItemID.WARPED_SCEPTRE_UNCHARGED));
        row(checked, V.VENATOR, "Venator bow", true, "CHARGES", Au.FIRE, 1, ints(SpotanimID.ARROW_VENATOR01_LAUNCH01),
            NONE, new Object[]{ItemID.ANCIENT_ESSENCE, 1L}, null, List.of(),
            ints(ItemID.VENATOR_BOW, ItemID.VENATOR_BOW_UNCHARGED));
        row(checked, V.VENATOR_ECHO, "Echo venator bow", true, "CHARGES", Au.FIRE, 1,
            ints(SpotanimID.ARROW_VENATOR01_LAUNCH01), NONE, new Object[]{ItemID.ANCIENT_ESSENCE, 1L}, null, List.of(),
            ints(ItemID.VENATOR_BOW_ORNAMENT, ItemID.VENATOR_BOW_ORNAMENT_UNCHARGED));
        row(checked, V.EYE_OF_AYAK, "Eye of Ayak", true, "EYE", Au.CAST, 1, NONE, NONE,
            new Object[]{ItemID.DEMON_TEAR, 1L}, null, List.of(), ints(ItemID.EYE_OF_AYAK, ItemID.EYE_OF_AYAK_UNCHARGED));
        row(checked, V.SCYTHE, "Scythe of vitur", false, "NONE", Au.SUPPLIES, 1, NONE, NONE,
            new Object[]{ItemID.BLOODRUNE, 2L}, new long[]{ItemID.VIAL_BLOOD, 1L, 100L}, List.of(),
            ints(ItemID.SCYTHE_OF_VITUR, ItemID.SCYTHE_OF_VITUR_UNCHARGED, ItemID.SCYTHE_OF_VITUR_OR,
                ItemID.SCYTHE_OF_VITUR_UNCHARGED_OR, ItemID.SCYTHE_OF_VITUR_BL, ItemID.SCYTHE_OF_VITUR_UNCHARGED_BL));
        row(checked, V.ATES, "Pendant of Ates", true, "ATES", Au.CAST, 1, NONE, NONE,
            new Object[]{ItemID.FROZEN_TEAR, 1L}, null, List.of(),
            ints(ItemID.PENDANT_OF_ATES, ItemID.PENDANT_OF_ATES_EMPTY));
        row(checked, V.V3, "Tome of fire", true, "TOME", Au.CAST, 20, NONE, NONE,
            new Object[]{ItemID.WINT_BURNT_PAGE, 1L}, null, List.of(),
            ints(ItemID.TOME_OF_FIRE, ItemID.TOME_OF_FIRE_UNCHARGED));
        Object[] shard = {ItemID.PRIF_CRYSTAL_SHARD, 1L};
        row(checked, V.CRYSTAL_BOW, "Crystal bow", true, "CRYSTAL", Au.FIRE, 100, NONE, ints(AnimationID.HUMAN_BOW),
            shard, null, List.of(), ints(ItemID.CRYSTAL_BOW, ItemID.CRYSTAL_BOW_INACTIVE, ItemID.CRYSTAL_BOW_2500));
        row(checked, V.CRYSTAL_HALBERD, "Crystal halberd", true, "CRYSTAL", Au.SUPPLIES, 100, NONE,
            ints(AnimationID.HUMAN_SPEAR_SPIKE, AnimationID.HUMAN_SCYTHE_SWEEP, AnimationID.DRAGON_HALBERD_SPECIAL_ATTACK),
            shard, null, List.of(),
            ints(ItemID.CRYSTAL_HALBERD, ItemID.CRYSTAL_HALBERD_INACTIVE, ItemID.CRYSTAL_HALBERD_2500));
        row(checked, V.BOWFA, "Bow of Faerdhinen", true, "CRYSTAL", Au.FIRE, 100,
            ints(SpotanimID.SP_ATTACK_ARROW_LAUNCH_FAERDHINEN), NONE, shard, null, List.of(),
            ints(ItemID.BOW_OF_FAERDHINEN, ItemID.BOW_OF_FAERDHINEN_INACTIVE));
        row(checked, V.SAELDOR, "Blade of Saeldor", false, "CRYSTAL", Au.SUPPLIES, 100, NONE,
            ints(AnimationID.HUMAN_SWORD_SLASH, AnimationID.HUMAN_SWORD_LUNGE), shard, null, List.of(),
            ints(ItemID.BLADE_OF_SAELDOR, ItemID.BLADE_OF_SAELDOR_INACTIVE));
        row(checked, V.BLOOD_FURY, "Blood fury", true, "FURY", Au.SUPPLIES, 10_000, NONE, NONE,
            new Object[]{ItemID.BLOOD_SHARD, 1L}, null, List.of(), ints(ItemID.BLOOD_AMULET));
        Object[] ether = {ItemID.WILD_CAVE_SHARD, 1L};
        int[] casts = ints(AnimationID.HUMAN_CASTSTRIKE_STAFF, AnimationID.HUMAN_CASTWAVE_STAFF,
            AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE, AnimationID.HUMAN_CASTING, AnimationID.ZAROS_CASTING,
            AnimationID.ZAROS_VERTICAL_CASTING, AnimationID.HUMAN_CAST_SURGE);
        row(checked, V.CRAW, "Craw's bow", true, "REVENANT", Au.FIRE, 1, NONE, ints(AnimationID.HUMAN_BOW), ether, null,
            List.of("craw's bow (u)"), ints(ItemID.WILD_CAVE_BOW_CHARGED, ItemID.WILD_CAVE_BOW_UNCHARGED));
        row(checked, V.WEBWEAVER, "Webweaver bow", true, "REVENANT", Au.FIRE, 1, NONE,
            ints(AnimationID.HUMAN_BOW, AnimationID.HUMAN_SPECIAL01_WEBWEAVER), ether, null, List.of("webweaver bow (u)"),
            ints(ItemID.WILD_CAVE_WEBWEAVER_CHARGED, ItemID.WILD_CAVE_WEBWEAVER_UNCHARGED));
        row(checked, V.VIGGORA, "Viggora's chainmace", true, "REVENANT", Au.SUPPLIES, 1, NONE,
            ints(AnimationID.WILD_CAVE_CHAINMACE_CRUSH), ether, null, List.of("viggora's chainmace (u)"),
            ints(ItemID.WILD_CAVE_CHAINMACE_CHARGED, ItemID.WILD_CAVE_CHAINMACE_UNCHARGED));
        row(checked, V.URSINE, "Ursine chainmace", true, "REVENANT", Au.SUPPLIES, 1, NONE,
            ints(AnimationID.WILD_CAVE_CHAINMACE_CRUSH, AnimationID.HUMAN_SPECIAL02_URSINE), ether, null,
            List.of("ursine chainmace (u)"), ints(ItemID.WILD_CAVE_URSINE_CHARGED, ItemID.WILD_CAVE_URSINE_UNCHARGED));
        row(checked, V.THAMMARON, "Thammaron's sceptre", true, "REVENANT", Au.CAST, 1, NONE, casts, ether, null,
            List.of("thammaron's sceptre (u)", "thammaron's sceptre (a)", "thammaron's sceptre (au)"),
            ints(ItemID.WILD_CAVE_SCEPTRE_CHARGED, ItemID.WILD_CAVE_SCEPTRE_UNCHARGED,
                ItemID.WILD_CAVE_SCEPTRE_CHARGED_RECOL, ItemID.WILD_CAVE_SCEPTRE_UNCHARGED_RECOL));
        int[] accursed = java.util.Arrays.copyOf(casts, casts.length + 1);
        accursed[casts.length] = AnimationID.HUMAN_SPECIAL_ACCURSED;
        row(checked, V.ACCURSED, "Accursed sceptre", true, "REVENANT", Au.CAST, 1, NONE, accursed, ether, null,
            List.of("accursed sceptre (u)", "accursed sceptre (a)", "accursed sceptre (au)"),
            ints(ItemID.WILD_CAVE_ACCURSED_CHARGED, ItemID.WILD_CAVE_ACCURSED_UNCHARGED,
                ItemID.WILD_CAVE_ACCURSED_CHARGED_RECOL, ItemID.WILD_CAVE_ACCURSED_UNCHARGED_RECOL));
        row(checked, V.MEMOIRS, "Kharedst's memoirs", true, "MEMOIRS", Au.CAST, 1, NONE, NONE,
            new Object[]{ItemID.LAWRUNE, 1L, ItemID.BODYRUNE, 1L, ItemID.MINDRUNE, 1L, ItemID.SOULRUNE, 1L}, null,
            List.of("book of the dead"), ints(ItemID.VEOS_KHAREDSTS_MEMOIRS, ItemID.BOOK_OF_THE_DEAD));
        assertEquals("every family has exactly one checked row", Arrays.asList(V.values()), checked);
    }

    @Test
    public void checkShapesFollowTheFamily()
    {
        for (V variant : V.values())
        {
            boolean special = variant == V.V1b || variant == V.EYE_OF_AYAK || variant == V.SCYTHE;
            if (special)
            {
                assertNull(variant.name(), variant.getCheck());
            }
            else
            {
                assertNotNull(variant.name(), variant.getCheck());
            }
        }
    }

    private static void row(List<V> checked, V variant, String name, boolean implemented, String family, Au action,
        int units, int[] graphics, int[] animations, Object[] recipe, long[] fraction, List<String> aliases,
        int[] items)
    {
        String at = variant.name();
        assertEquals(at, name, variant.getDisplayName());
        assertEquals(at, implemented, variant.isImplemented());
        assertEquals(at, family, variant.getFamily());
        assertEquals(at, action, variant.getAction());
        assertEquals(at, units, variant.getUnits());
        assertArrayEquals(at, graphics, variant.getGraphics());
        assertArrayEquals(at, animations, variant.getAnimations());
        Map<Integer, Long> expected = new LinkedHashMap<>();
        for (int i = 0; i < recipe.length; i += 2)
        {
            expected.put((Integer) recipe[i], (Long) recipe[i + 1]);
        }
        assertEquals(at, new ArrayList<>(expected.entrySet()), new ArrayList<>(variant.getRecipe().entrySet()));
        assertArrayEquals(at, fraction, variant.getFraction());
        assertEquals(at, aliases, variant.getAliases());
        assertArrayEquals(at, items, variant.getItemIds());
        checked.add(variant);
    }

    private static int[] ints(int... values)
    {
        return values;
    }
}
