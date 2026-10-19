package com.gpmanager;

import com.gpmanager.ChargeCheck.ChargedItem;
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
        List<ChargedItem> checked = new ArrayList<>();
        row(checked, ChargedItem.TRIDENT_SEAS, "Trident of the Seas", true, "TRIDENT", ActionKind.CAST, 1,
            ints(SpotanimID.SLAYER_TOTS_CASTING), NONE, SEAS, null,
            List.of("trident of the seas (full)", "uncharged trident"),
            ints(ItemID.TOTS, ItemID.TOTS_CHARGED, ItemID.TOTS_UNCHARGED, ItemID.TOTS_CHARGED_ORN, ItemID.TOTS_ORN,
                ItemID.TOTS_UNCHARGED_ORN));
        row(checked, ChargedItem.TRIDENT_SWAMP, "Trident of the Swamp", true, "TRIDENT", ActionKind.CAST, 1,
            ints(SpotanimID.TOXIC_TOTS_CASTING), NONE, SWAMP, null, List.of("uncharged toxic trident"),
            ints(ItemID.TOXIC_TOTS_CHARGED, ItemID.TOXIC_TOTS_UNCHARGED, ItemID.TOXIC_TOTS_CHARGED_ORN,
                ItemID.TOXIC_TOTS_UNCHARGED_ORN));
        row(checked, ChargedItem.TRIDENT_SWAMP_ENHANCED, "Trident of the Swamp (e)", true, "TRIDENT", ActionKind.CAST, 1,
            ints(SpotanimID.TOXIC_TOTS_CASTING), NONE, SWAMP, null, List.of("uncharged toxic trident (e)"),
            ints(ItemID.TOXIC_TOTS_I_CHARGED, ItemID.TOXIC_TOTS_I_UNCHARGED, ItemID.TOXIC_TOTS_I_CHARGED_ORN,
                ItemID.TOXIC_TOTS_I_UNCHARGED_ORN));
        row(checked, ChargedItem.TRIDENT_SEAS_ENHANCED, "Trident of the Seas (e)", true, "TRIDENT", ActionKind.CAST, 1,
            ints(SpotanimID.SLAYER_TOTS_CASTING), NONE, SEAS, null, List.of("uncharged trident (e)"),
            ints(ItemID.TOTS_I_CHARGED, ItemID.TOTS_I_UNCHARGED, ItemID.TOTS_I_CHARGED_ORN,
                ItemID.TOTS_I_UNCHARGED_ORN));
        row(checked, ChargedItem.BLOWPIPE, "Toxic blowpipe", true, "BLOWPIPE", ActionKind.CAST, 1, NONE, NONE, new Object[0],
            new long[]{ItemID.SNAKEBOSS_SCALE, 2L, 3L}, List.of(),
            ints(ItemID.TOXIC_BLOWPIPE, ItemID.TOXIC_BLOWPIPE_LOADED, ItemID.TOXIC_BLOWPIPE_ORNAMENT,
                ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT));
        row(checked, ChargedItem.SHADOW, "Tumeken's shadow", true, "CHARGES", ActionKind.CAST, 1,
            ints(SpotanimID.TUMEKENS_SHADOW_CASTING), NONE, new Object[]{ItemID.SOULRUNE, 2L, ItemID.CHAOSRUNE, 5L},
            null, List.of(), ints(ItemID.TUMEKENS_SHADOW, ItemID.TUMEKENS_SHADOW_UNCHARGED));
        int[] sanguinesti = ints(SpotanimID.SANGUINESTI_STAFF_CASTING, SpotanimID.SANGUINESTI_STAFF_CASTING_JUSTICIAR);
        row(checked, ChargedItem.SANGUINESTI, "Sanguinesti staff", true, "CHARGES", ActionKind.CAST, 1, sanguinesti, NONE,
            new Object[]{ItemID.BLOODRUNE, 2L}, null, List.of(),
            ints(ItemID.SANGUINESTI_STAFF, ItemID.SANGUINESTI_STAFF_UNCHARGED));
        row(checked, ChargedItem.SANGUINESTI_HOLY, "Holy sanguinesti staff", true, "CHARGES", ActionKind.CAST, 1, sanguinesti, NONE,
            new Object[]{ItemID.BLOODRUNE, 2L}, null, List.of(),
            ints(ItemID.SANGUINESTI_STAFF_OR, ItemID.SANGUINESTI_STAFF_UNCHARGED_OR));
        row(checked, ChargedItem.WARPED, "Warped sceptre", true, "CHARGES", ActionKind.CAST, 1, ints(SpotanimID.VFX_WARPED_SCEPTRE_CAST),
            NONE, new Object[]{ItemID.CHAOSRUNE, 2L, ItemID.EARTHRUNE, 5L}, null, List.of(),
            ints(ItemID.WARPED_SCEPTRE, ItemID.WARPED_SCEPTRE_UNCHARGED));
        row(checked, ChargedItem.VENATOR, "Venator bow", true, "CHARGES", ActionKind.FIRE, 1, ints(SpotanimID.ARROW_VENATOR01_LAUNCH01),
            NONE, new Object[]{ItemID.ANCIENT_ESSENCE, 1L}, null, List.of(),
            ints(ItemID.VENATOR_BOW, ItemID.VENATOR_BOW_UNCHARGED));
        row(checked, ChargedItem.VENATOR_ECHO, "Echo venator bow", true, "CHARGES", ActionKind.FIRE, 1,
            ints(SpotanimID.ARROW_VENATOR01_LAUNCH01), NONE, new Object[]{ItemID.ANCIENT_ESSENCE, 1L}, null, List.of(),
            ints(ItemID.VENATOR_BOW_ORNAMENT, ItemID.VENATOR_BOW_ORNAMENT_UNCHARGED));
        row(checked, ChargedItem.EYE_OF_AYAK, "Eye of Ayak", true, "EYE", ActionKind.CAST, 1, NONE, NONE,
            new Object[]{ItemID.DEMON_TEAR, 1L}, null, List.of(), ints(ItemID.EYE_OF_AYAK, ItemID.EYE_OF_AYAK_UNCHARGED));
        row(checked, ChargedItem.SCYTHE, "Scythe of vitur", true, "NONE", ActionKind.SUPPLIES, 1, NONE, NONE,
            new Object[]{ItemID.BLOODRUNE, 2L}, new long[]{ItemID.VIAL_BLOOD, 1L, 100L}, List.of(),
            ints(ItemID.SCYTHE_OF_VITUR, ItemID.SCYTHE_OF_VITUR_UNCHARGED, ItemID.SCYTHE_OF_VITUR_OR,
                ItemID.SCYTHE_OF_VITUR_UNCHARGED_OR, ItemID.SCYTHE_OF_VITUR_BL, ItemID.SCYTHE_OF_VITUR_UNCHARGED_BL));
        row(checked, ChargedItem.ATES, "Pendant of Ates", true, "ATES", ActionKind.CAST, 1, NONE, NONE,
            new Object[]{ItemID.FROZEN_TEAR, 1L}, null, List.of(),
            ints(ItemID.PENDANT_OF_ATES, ItemID.PENDANT_OF_ATES_EMPTY));
        row(checked, ChargedItem.SAILORS_AMULET, "Sailors' amulet", true, "AMULET", ActionKind.CAST, 10, NONE, NONE,
            new Object[]{ItemID.LAWRUNE, 1L, ItemID.WATERRUNE, 10L}, null, List.of(),
            ints(ItemID.SAILORS_AMULET, ItemID.SAILORS_AMULET_EMPTY));
        row(checked, ChargedItem.TOME_OF_FIRE, "Tome of fire", true, "TOME", ActionKind.CAST, 20, NONE, NONE,
            new Object[]{ItemID.WINT_BURNT_PAGE, 1L}, null, List.of(),
            ints(ItemID.TOME_OF_FIRE, ItemID.TOME_OF_FIRE_UNCHARGED));
        Object[] shard = {ItemID.PRIF_CRYSTAL_SHARD, 1L};
        row(checked, ChargedItem.CRYSTAL_BOW, "Crystal bow", true, "CRYSTAL", ActionKind.FIRE, 100, NONE, ints(AnimationID.HUMAN_BOW),
            shard, null, List.of(), ints(ItemID.CRYSTAL_BOW, ItemID.CRYSTAL_BOW_INACTIVE, ItemID.CRYSTAL_BOW_2500));
        row(checked, ChargedItem.CRYSTAL_HALBERD, "Crystal halberd", true, "CRYSTAL", ActionKind.SUPPLIES, 100, NONE,
            ints(AnimationID.HUMAN_SPEAR_SPIKE, AnimationID.HUMAN_SCYTHE_SWEEP, AnimationID.DRAGON_HALBERD_SPECIAL_ATTACK),
            shard, null, List.of(),
            ints(ItemID.CRYSTAL_HALBERD, ItemID.CRYSTAL_HALBERD_INACTIVE, ItemID.CRYSTAL_HALBERD_2500));
        row(checked, ChargedItem.BOWFA, "Bow of Faerdhinen", true, "CRYSTAL", ActionKind.FIRE, 100,
            ints(SpotanimID.SP_ATTACK_ARROW_LAUNCH_FAERDHINEN), NONE, shard, null, List.of(),
            ints(ItemID.BOW_OF_FAERDHINEN, ItemID.BOW_OF_FAERDHINEN_INACTIVE));
        row(checked, ChargedItem.SAELDOR, "Blade of Saeldor", true, "CRYSTAL", ActionKind.SUPPLIES, 100, NONE,
            ints(AnimationID.HUMAN_SWORD_SLASH, AnimationID.HUMAN_SWORD_LUNGE), shard, null, List.of(),
            ints(ItemID.BLADE_OF_SAELDOR, ItemID.BLADE_OF_SAELDOR_INACTIVE));
        row(checked, ChargedItem.BLOOD_FURY, "Blood fury", true, "FURY", ActionKind.SUPPLIES, 10_000, NONE, NONE,
            new Object[]{ItemID.BLOOD_SHARD, 1L}, null, List.of(), ints(ItemID.BLOOD_AMULET));
        Object[] ether = {ItemID.WILD_CAVE_SHARD, 1L};
        int[] casts = ints(AnimationID.HUMAN_CASTSTRIKE_STAFF, AnimationID.HUMAN_CASTWAVE_STAFF,
            AnimationID.HUMAN_CASTWAVE_STAFF_WALKMERGE, AnimationID.HUMAN_CASTING, AnimationID.ZAROS_CASTING,
            AnimationID.ZAROS_VERTICAL_CASTING, AnimationID.HUMAN_CAST_SURGE);
        row(checked, ChargedItem.CRAW, "Craw's bow", true, "REVENANT", ActionKind.FIRE, 1, NONE, ints(AnimationID.HUMAN_BOW), ether, null,
            List.of("craw's bow (u)"), ints(ItemID.WILD_CAVE_BOW_CHARGED, ItemID.WILD_CAVE_BOW_UNCHARGED));
        row(checked, ChargedItem.WEBWEAVER, "Webweaver bow", true, "REVENANT", ActionKind.FIRE, 1, NONE,
            ints(AnimationID.HUMAN_BOW, AnimationID.HUMAN_SPECIAL01_WEBWEAVER), ether, null, List.of("webweaver bow (u)"),
            ints(ItemID.WILD_CAVE_WEBWEAVER_CHARGED, ItemID.WILD_CAVE_WEBWEAVER_UNCHARGED));
        row(checked, ChargedItem.VIGGORA, "Viggora's chainmace", true, "REVENANT", ActionKind.SUPPLIES, 1, NONE,
            ints(AnimationID.WILD_CAVE_CHAINMACE_CRUSH), ether, null, List.of("viggora's chainmace (u)"),
            ints(ItemID.WILD_CAVE_CHAINMACE_CHARGED, ItemID.WILD_CAVE_CHAINMACE_UNCHARGED));
        row(checked, ChargedItem.URSINE, "Ursine chainmace", true, "REVENANT", ActionKind.SUPPLIES, 1, NONE,
            ints(AnimationID.WILD_CAVE_CHAINMACE_CRUSH, AnimationID.HUMAN_SPECIAL02_URSINE), ether, null,
            List.of("ursine chainmace (u)"), ints(ItemID.WILD_CAVE_URSINE_CHARGED, ItemID.WILD_CAVE_URSINE_UNCHARGED));
        row(checked, ChargedItem.THAMMARON, "Thammaron's sceptre", true, "REVENANT", ActionKind.CAST, 1, NONE, casts, ether, null,
            List.of("thammaron's sceptre (u)", "thammaron's sceptre (a)", "thammaron's sceptre (au)"),
            ints(ItemID.WILD_CAVE_SCEPTRE_CHARGED, ItemID.WILD_CAVE_SCEPTRE_UNCHARGED,
                ItemID.WILD_CAVE_SCEPTRE_CHARGED_RECOL, ItemID.WILD_CAVE_SCEPTRE_UNCHARGED_RECOL));
        int[] accursed = java.util.Arrays.copyOf(casts, casts.length + 1);
        accursed[casts.length] = AnimationID.HUMAN_SPECIAL_ACCURSED;
        row(checked, ChargedItem.ACCURSED, "Accursed sceptre", true, "REVENANT", ActionKind.CAST, 1, NONE, accursed, ether, null,
            List.of("accursed sceptre (u)", "accursed sceptre (a)", "accursed sceptre (au)"),
            ints(ItemID.WILD_CAVE_ACCURSED_CHARGED, ItemID.WILD_CAVE_ACCURSED_UNCHARGED,
                ItemID.WILD_CAVE_ACCURSED_CHARGED_RECOL, ItemID.WILD_CAVE_ACCURSED_UNCHARGED_RECOL));
        row(checked, ChargedItem.MEMOIRS, "Kharedst's memoirs", true, "MEMOIRS", ActionKind.CAST, 1, NONE, NONE,
            new Object[]{ItemID.LAWRUNE, 1L, ItemID.BODYRUNE, 1L, ItemID.MINDRUNE, 1L, ItemID.SOULRUNE, 1L}, null,
            List.of("book of the dead"), ints(ItemID.VEOS_KHAREDSTS_MEMOIRS, ItemID.BOOK_OF_THE_DEAD));
        row(checked, ChargedItem.BRYOPHYTA, "Bryophyta's staff", true, "CHARGES", ActionKind.CAST, 1,
            ints(SpotanimID.LOWLVLALCHEMY_CASTING, SpotanimID.HIGHLVLALCHEMY_CASTING, SpotanimID.SUPERHEATITEM_CASTING,
                SpotanimID.SPOTANIM_CASTLOWLVLALCHEMY_FIRE, SpotanimID.SPOTANIM_CASTHIGHLVLALCHEMY_FIRE),
            NONE, new Object[]{ItemID.NATURERUNE, 1L}, null, List.of(),
            ints(ItemID.NATURE_STAFF_UNCHARGED, ItemID.NATURE_STAFF_CHARGED));
        row(checked, ChargedItem.TOXIC_STAFF, "Toxic staff of the dead", true, "SCALES", ActionKind.CAST, 1, NONE, NONE,
            new Object[]{ItemID.SNAKEBOSS_SCALE, 1L}, null, List.of(), ints(ItemID.TOXIC_SOTD_CHARGED, ItemID.TOXIC_SOTD));
        row(checked, ChargedItem.SERPENTINE_HELM, "Serpentine helm", true, "SCALES", ActionKind.SUPPLIES, 1, NONE, NONE,
            new Object[]{ItemID.SNAKEBOSS_SCALE, 1L}, null, List.of("tanzanite helm", "magma helm"),
            ints(ItemID.SERPENTINE_HELM_CHARGED, ItemID.SERPENTINE_HELM, ItemID.SERPENTINE_HELM_CHARGED_CYAN,
                ItemID.SERPENTINE_HELM_CYAN, ItemID.SERPENTINE_HELM_CHARGED_RED, ItemID.SERPENTINE_HELM_RED));
        row(checked, ChargedItem.CRYSTAL_HELM, "Crystal helm", true, "CRYSTAL", ActionKind.SUPPLIES, 100, NONE, NONE,
            shard, null, List.of(),
            ints(ItemID.CRYSTAL_HELMET, ItemID.CRYSTAL_HELMET_INACTIVE, ItemID.CRYSTAL_HELMET_HEFIN,
                ItemID.CRYSTAL_HELMET_INACTIVE_HEFIN, ItemID.CRYSTAL_HELMET_ITHELL, ItemID.CRYSTAL_HELMET_INACTIVE_ITHELL,
                ItemID.CRYSTAL_HELMET_IORWERTH, ItemID.CRYSTAL_HELMET_INACTIVE_IORWERTH, ItemID.CRYSTAL_HELMET_TRAHAEARN,
                ItemID.CRYSTAL_HELMET_INACTIVE_TRAHAEARN, ItemID.CRYSTAL_HELMET_CADARN, ItemID.CRYSTAL_HELMET_INACTIVE_CADARN,
                ItemID.CRYSTAL_HELMET_CRWYS, ItemID.CRYSTAL_HELMET_INACTIVE_CRWYS, ItemID.CRYSTAL_HELMET_AMLODD,
                ItemID.CRYSTAL_HELMET_INACTIVE_AMLODD));
        row(checked, ChargedItem.CRYSTAL_BODY, "Crystal body", true, "CRYSTAL", ActionKind.SUPPLIES, 100, NONE, NONE,
            shard, null, List.of(),
            ints(ItemID.CRYSTAL_CHESTPLATE, ItemID.CRYSTAL_CHESTPLATE_INACTIVE, ItemID.CRYSTAL_CHESTPLATE_HEFIN,
                ItemID.CRYSTAL_CHESTPLATE_INACTIVE_HEFIN, ItemID.CRYSTAL_CHESTPLATE_ITHELL,
                ItemID.CRYSTAL_CHESTPLATE_INACTIVE_ITHELL, ItemID.CRYSTAL_CHESTPLATE_IORWERTH,
                ItemID.CRYSTAL_CHESTPLATE_INACTIVE_IORWERTH, ItemID.CRYSTAL_CHESTPLATE_TRAHAEARN,
                ItemID.CRYSTAL_CHESTPLATE_INACTIVE_TRAHAEARN, ItemID.CRYSTAL_CHESTPLATE_CADARN,
                ItemID.CRYSTAL_CHESTPLATE_INACTIVE_CADARN, ItemID.CRYSTAL_CHESTPLATE_CRWYS,
                ItemID.CRYSTAL_CHESTPLATE_INACTIVE_CRWYS, ItemID.CRYSTAL_CHESTPLATE_AMLODD,
                ItemID.CRYSTAL_CHESTPLATE_INACTIVE_AMLODD));
        row(checked, ChargedItem.CRYSTAL_LEGS, "Crystal legs", true, "CRYSTAL", ActionKind.SUPPLIES, 100, NONE, NONE,
            shard, null, List.of(),
            ints(ItemID.CRYSTAL_PLATELEGS, ItemID.CRYSTAL_PLATELEGS_INACTIVE, ItemID.CRYSTAL_PLATELEGS_HEFIN,
                ItemID.CRYSTAL_PLATELEGS_INACTIVE_HEFIN, ItemID.CRYSTAL_PLATELEGS_ITHELL,
                ItemID.CRYSTAL_PLATELEGS_INACTIVE_ITHELL, ItemID.CRYSTAL_PLATELEGS_IORWERTH,
                ItemID.CRYSTAL_PLATELEGS_INACTIVE_IORWERTH, ItemID.CRYSTAL_PLATELEGS_TRAHAEARN,
                ItemID.CRYSTAL_PLATELEGS_INACTIVE_TRAHAEARN, ItemID.CRYSTAL_PLATELEGS_CADARN,
                ItemID.CRYSTAL_PLATELEGS_INACTIVE_CADARN, ItemID.CRYSTAL_PLATELEGS_CRWYS,
                ItemID.CRYSTAL_PLATELEGS_INACTIVE_CRWYS, ItemID.CRYSTAL_PLATELEGS_AMLODD,
                ItemID.CRYSTAL_PLATELEGS_INACTIVE_AMLODD));
        row(checked, ChargedItem.CRYSTAL_SHIELD, "Crystal shield", true, "CRYSTAL", ActionKind.SUPPLIES, 100, NONE, NONE,
            shard, null, List.of(),
            ints(ItemID.CRYSTAL_SHIELD, ItemID.CRYSTAL_SHIELD_INACTIVE, ItemID.CRYSTAL_SHIELD_2500));
        row(checked, ChargedItem.SUFFERING, "Ring of suffering", true, "SUFFERING", ActionKind.SUPPLIES, 40, NONE, NONE,
            new Object[]{ItemID.RING_OF_RECOIL, 1L}, null, List.of(),
            ints(ItemID.ZENYTE_RING_ENCHANTED, ItemID.ZENYTE_RING_ENCHANTED_RECOIL,
                ItemID.NZONE_ZENYTE_RING_ENCHANTED, ItemID.NZONE_ZENYTE_RING_ENCHANTED_RECOIL,
                ItemID.SW_ZENYTE_RING_ENCHANTED, ItemID.SW_ZENYTE_RING_ENCHANTED_RECOIL,
                ItemID.PVPA_ZENYTE_RING_ENCHANTED, ItemID.PVPA_ZENYTE_RING_ENCHANTED_RECOIL));
        row(checked, ChargedItem.TONALZTICS, "Tonalztics of Ralos", true, "CHARGES", ActionKind.FIRE, 1, NONE, NONE,
            new Object[]{ItemID.SUNFIRESPLINTER, 1L}, null, List.of(),
            ints(ItemID.TONALZTICS_OF_RALOS_UNCHARGED, ItemID.TONALZTICS_OF_RALOS_CHARGED));
        row(checked, ChargedItem.TOME_OF_WATER, "Tome of Water", true, "TOME", ActionKind.CAST, 20, NONE, NONE,
            new Object[]{ItemID.SOAKED_PAGE, 1L}, null, List.of(),
            ints(ItemID.TOME_OF_WATER, ItemID.TOME_OF_WATER_UNCHARGED));
        row(checked, ChargedItem.TOME_OF_EARTH, "Tome of Earth", true, "TOME", ActionKind.CAST, 20, NONE, NONE,
            new Object[]{ItemID.SOILED_PAGE, 1L}, null, List.of(),
            ints(ItemID.TOME_OF_EARTH, ItemID.TOME_OF_EARTH_UNCHARGED));
        row(checked, ChargedItem.ETHEREUM, "Bracelet of ethereum", true, "ETHEREUM", ActionKind.SUPPLIES, 1, NONE, NONE,
            new Object[]{ItemID.WILD_CAVE_SHARD, 1L}, null, List.of(),
            ints(ItemID.WILD_CAVE_BRACELET_CHARGED, ItemID.WILD_CAVE_BRACELET_UNCHARGED));
        row(checked, ChargedItem.BONE_STAFF, "Bone staff", true, "CHARGES", ActionKind.CAST, 1, NONE, casts,
            new Object[]{ItemID.CHAOSRUNE, 1L}, null, List.of(), ints(ItemID.RAT_BONE_STAFF));
        row(checked, ChargedItem.CRYSTAL_AXE, "Crystal axe", true, "CRYSTAL", ActionKind.SUPPLIES, 100, NONE, NONE,
            shard, null, List.of(),
            ints(ItemID.CRYSTAL_AXE, ItemID.CRYSTAL_AXE_INACTIVE, ItemID.CRYSTAL_AXE_2H,
                ItemID.CRYSTAL_AXE_2H_INACTIVE));
        row(checked, ChargedItem.CRYSTAL_PICKAXE, "Crystal pickaxe", true, "CRYSTAL", ActionKind.SUPPLIES, 100, NONE, NONE,
            shard, null, List.of(), ints(ItemID.CRYSTAL_PICKAXE, ItemID.CRYSTAL_PICKAXE_INACTIVE));
        row(checked, ChargedItem.CRYSTAL_HARPOON, "Crystal harpoon", true, "CRYSTAL", ActionKind.SUPPLIES, 100, NONE, NONE,
            shard, null, List.of(), ints(ItemID.CRYSTAL_HARPOON, ItemID.CRYSTAL_HARPOON_INACTIVE));
        row(checked, ChargedItem.ECHO_BOOTS, "Echo boots", true, "ECHO", ActionKind.SUPPLIES, 6000, NONE, NONE,
            new Object[]{ItemID.ECHO_CRYSTAL, 1L}, null, List.of(),
            ints(ItemID.ECHO_BOOTS, ItemID.GUARDIAN_BOOTS));
        row(checked, ChargedItem.SLAYER_STAFF_E, "Slayer's staff (e)", true, "SLAYER", ActionKind.SUPPLIES, 2500, NONE, NONE,
            new Object[]{ItemID.SLAYER_ENCHANTMENT, 1L}, null, List.of(),
            ints(ItemID.SLAYER_STAFF_ENCHANTED, ItemID.SLAYER_STAFF));
        row(checked, ChargedItem.INFERNAL_AXE, "Infernal axe", true, "INFERNAL", ActionKind.SUPPLIES, 5000, NONE, NONE,
            new Object[]{ItemID.SMOULDERING_STONE, 1L, ItemID.DRAGON_AXE, 1L}, null, List.of(),
            ints(ItemID.INFERNAL_AXE, ItemID.INFERNAL_AXE_EMPTY));
        row(checked, ChargedItem.INFERNAL_PICKAXE, "Infernal pickaxe", true, "INFERNAL", ActionKind.SUPPLIES, 5000, NONE, NONE,
            new Object[]{ItemID.SMOULDERING_STONE, 1L, ItemID.DRAGON_PICKAXE, 1L}, null, List.of(),
            ints(ItemID.INFERNAL_PICKAXE, ItemID.INFERNAL_PICKAXE_EMPTY));
        row(checked, ChargedItem.INFERNAL_HARPOON, "Infernal harpoon", true, "INFERNAL", ActionKind.SUPPLIES, 5000, NONE, NONE,
            new Object[]{ItemID.SMOULDERING_STONE, 1L, ItemID.DRAGON_HARPOON, 1L}, null, List.of(),
            ints(ItemID.INFERNAL_HARPOON, ItemID.INFERNAL_HARPOON_EMPTY));
        row(checked, ChargedItem.DIZANAS, "Dizana's quiver", true, "CHARGES", ActionKind.FIRE, 1, NONE, NONE,
            new Object[]{ItemID.SUNFIRESPLINTER, 1L}, null,
            List.of("dizana's quiver (l)", "dizana's quiver (uncharged)", "dizana's quiver (l) (uncharged)"),
            ints(ItemID.DIZANAS_QUIVER_UNCHARGED, ItemID.DIZANAS_QUIVER_UNCHARGED_TROUVER,
                ItemID.DIZANAS_QUIVER_CHARGED, ItemID.DIZANAS_QUIVER_CHARGED_TROUVER));
        assertEquals("every family has exactly one checked row", Arrays.asList(ChargedItem.values()), checked);
    }

    @Test
    public void checkShapesFollowTheFamily()
    {
        for (ChargedItem variant : ChargedItem.values())
        {
            boolean special = variant == ChargedItem.BLOWPIPE
                || variant == ChargedItem.EYE_OF_AYAK
                || variant.isCommittedLoad();
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

    /** Only the refundless loads book at load (committed_loads.tsv): the crystal pieces' shards and the ring of suffering's recoils. */
    @Test
    public void committedLoadsAreExactlyTheRefundlessLoads()
    {
        for (ChargedItem variant : ChargedItem.values())
        {
            boolean expected = variant == ChargedItem.CRYSTAL_HELM
                || variant == ChargedItem.CRYSTAL_BODY
                || variant == ChargedItem.CRYSTAL_LEGS
                || variant == ChargedItem.CRYSTAL_SHIELD
                || variant == ChargedItem.SUFFERING
                || variant == ChargedItem.ETHEREUM
                || variant == ChargedItem.CRYSTAL_AXE
                || variant == ChargedItem.CRYSTAL_PICKAXE
                || variant == ChargedItem.CRYSTAL_HARPOON
                || variant == ChargedItem.ECHO_BOOTS
                || variant == ChargedItem.SLAYER_STAFF_E
                || variant == ChargedItem.INFERNAL_AXE
                || variant == ChargedItem.INFERNAL_PICKAXE
                || variant == ChargedItem.INFERNAL_HARPOON;
            assertEquals(variant.name(), expected, variant.isCommittedLoad());
        }
    }

    private static void row(List<ChargedItem> checked, ChargedItem variant, String name, boolean implemented, String family, ActionKind action,
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
