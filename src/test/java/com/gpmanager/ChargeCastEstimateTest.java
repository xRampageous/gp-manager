package com.gpmanager;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.WidgetInfo;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Cast-graphic estimate evidence and charge-state swap neutrality. */
public class ChargeCastEstimateTest
{
    @Test
    public void castGraphicsArePinnedPerFamilyAndIgnoreUnsupportedVariants()
    {
        assertEquals(1251, ChargeIntake.nx(Ar.V.TRIDENT_SEAS));
        assertEquals(1251, ChargeIntake.nx(Ar.V.TRIDENT_SEAS_ENHANCED));
        assertEquals(665, ChargeIntake.nx(Ar.V.TRIDENT_SWAMP));
        assertEquals(665, ChargeIntake.nx(Ar.V.TRIDENT_SWAMP_ENHANCED));
        assertEquals(2125, ChargeIntake.nx(Ar.V.SHADOW));
        assertEquals(2567, ChargeIntake.nx(Ar.V.WARPED));
        assertEquals(1540, ChargeIntake.nx(Ar.V.SANGUINESTI));
        assertEquals(1540, ChargeIntake.nx(Ar.V.SANGUINESTI_HOLY));
        assertTrue("the Justiciar casting graphic is still the same family",
            ChargeIntake.wa(Ar.V.SANGUINESTI, 1900));
        assertFalse(ChargeIntake.wa(Ar.V.SHADOW, 1900));
        assertEquals(2289, ChargeIntake.nx(Ar.V.VENATOR));
        assertEquals(0, ChargeIntake.nx(Ar.V.SCYTHE));
        assertEquals(5061, ChargeIntake.lm(Ar.V.V1b));
        assertEquals(0, ChargeIntake.lm(Ar.V.VENATOR));
        assertEquals(Au.FIRE, ChargeIntake.rw(Ar.V.VENATOR));
        assertEquals(Au.SUPPLIES, ChargeIntake.rw(Ar.V.SCYTHE));
        assertEquals(Au.CAST, ChargeIntake.rw(Ar.V.SHADOW));
        assertEquals(0, ChargeIntake.nx(Ar.V.V1b));
        assertEquals(0, ChargeIntake.nx(Ar.V.V2));
    }

    @Test
    public void castRecipesArePinnedPerFamily()
    {
        Map<Integer, Long> shadow = ChargeIntake.recipeOf(Ar.V.SHADOW);
        assertEquals(Long.valueOf(2L), shadow.get(ItemID.SOULRUNE));
        assertEquals(Long.valueOf(5L), shadow.get(ItemID.CHAOSRUNE));
        assertEquals(2, shadow.size());

        Map<Integer, Long> warped = ChargeIntake.recipeOf(Ar.V.WARPED);
        assertEquals(Long.valueOf(2L), warped.get(ItemID.CHAOSRUNE));
        assertEquals(Long.valueOf(5L), warped.get(ItemID.EARTHRUNE));

        Map<Integer, Long> sang = ChargeIntake.recipeOf(Ar.V.SANGUINESTI);
        assertEquals(Long.valueOf(2L), sang.get(ItemID.BLOODRUNE));
        assertEquals(1, sang.size());
        assertEquals(sang, ChargeIntake.recipeOf(Ar.V.SANGUINESTI_HOLY));

        Map<Integer, Long> venator = ChargeIntake.recipeOf(Ar.V.VENATOR);
        assertEquals(Long.valueOf(1L), venator.get(ItemID.ANCIENT_ESSENCE));
        assertEquals(1, venator.size());

        Map<Integer, Long> scythe = ChargeIntake.recipeOf(Ar.V.SCYTHE);
        assertEquals(Long.valueOf(2L), scythe.get(ItemID.BLOODRUNE));
        Map<Integer, Long> ates = ChargeIntake.recipeOf(Ar.V.ATES);
        assertEquals(Long.valueOf(1L), ates.get(ItemID.FROZEN_TEAR));
        assertEquals(1, ates.size());
        assertTrue("a pendant location click spends a tear", ChargeIntake.lh("darkfrost"));
        assertTrue(ChargeIntake.lh("the darkfrost"));
        assertTrue(ChargeIntake.lh("twilight temple"));
        assertTrue(ChargeIntake.lh("ralos' rise"));
        assertTrue(ChargeIntake.lh("north aldarin"));
        assertTrue(ChargeIntake.lh("kastori"));
        assertTrue(ChargeIntake.lh("nemus retreat"));
        assertFalse(ChargeIntake.lh("check"));
        assertFalse(ChargeIntake.lh("uncharge"));
        assertFalse("rubbing opens the interface; it never spends a charge by itself",
            ChargeIntake.lh("rub"));
        assertFalse("a bank withdrawal is not a teleport", ChargeIntake.lh("withdraw-1"));
        assertFalse(ChargeIntake.lh("withdraw-all"));
        assertFalse(ChargeIntake.lh("deposit-1"));
        assertFalse(ChargeIntake.lh("examine"));
        assertFalse(ChargeIntake.lh("take"));
        assertFalse(ChargeIntake.lh(""));
        assertTrue("blowpipe scales accrue fractionally, nothing whole per shot",
            ChargeIntake.recipeOf(Ar.V.V1b).isEmpty());
    }

    @Test
    public void crystalEstimatesFollowTheirPinnedTriggersAndBillOneChargePerAttack()
    {
        // Crystal bow and Bowfa shoot; the halberd swings. One shard carries 100 charges,
        // and each attack books one charge priced at a hundredth of a shard.
        assertEquals(Au.FIRE, ChargeIntake.rw(Ar.V.BOWFA));
        assertEquals(Au.FIRE, ChargeIntake.rw(Ar.V.CRYSTAL_BOW));
        assertEquals(Au.SUPPLIES, ChargeIntake.rw(Ar.V.CRYSTAL_HALBERD));
        assertEquals(1888, ChargeIntake.nx(Ar.V.BOWFA));
        assertEquals(0, ChargeIntake.nx(Ar.V.CRYSTAL_BOW));
        assertEquals(0, ChargeIntake.nx(Ar.V.CRYSTAL_HALBERD));
        assertTrue(ChargeIntake.lg(Ar.V.CRYSTAL_BOW, AnimationID.HUMAN_BOW));
        assertTrue(ChargeIntake.lg(Ar.V.CRYSTAL_HALBERD, AnimationID.HUMAN_SPEAR_SPIKE));
        assertTrue(ChargeIntake.lg(Ar.V.CRYSTAL_HALBERD, AnimationID.HUMAN_SCYTHE_SWEEP));
        assertTrue(ChargeIntake.lg(Ar.V.CRYSTAL_HALBERD, AnimationID.DRAGON_HALBERD_SPECIAL_ATTACK));
        assertFalse(ChargeIntake.lg(Ar.V.CRYSTAL_BOW, AnimationID.HUMAN_SCYTHE_SWEEP));
        assertFalse(ChargeIntake.lg(Ar.V.BOWFA, AnimationID.HUMAN_BOW));
        assertNull(ChargeIntake.tw(Ar.V.CRYSTAL_BOW));
        assertNull(ChargeIntake.tw(Ar.V.CRYSTAL_HALBERD));
        assertNull(ChargeIntake.tw(Ar.V.BOWFA));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(Ar.V.CRYSTAL_BOW).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(Ar.V.CRYSTAL_HALBERD).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(Ar.V.BOWFA).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(100, Ar.akr(Ar.V.CRYSTAL_BOW, ItemID.PRIF_CRYSTAL_SHARD));
        assertArrayEquals("a shard derives its price from the seed exchange",
            new int[]{ItemID.PRIF_TELEPORT_SEED, 150},
            CurrencyProxyCatalogue.axy(ItemID.PRIF_CRYSTAL_SHARD));
    }

    @Test
    public void saeldorIsAShardFedBladeThatBillsOneChargePerAttack()
    {
        // One charge per attack, hit or miss; a shard feeds 100 charges and prices at a hundredth.
        // The Check text is not pinned yet, so the blade is estimate-only for now.
        assertEquals(Au.SUPPLIES, ChargeIntake.rw(Ar.V.SAELDOR));
        assertTrue(ChargeIntake.lg(Ar.V.SAELDOR, AnimationID.HUMAN_SWORD_SLASH));
        assertTrue(ChargeIntake.lg(Ar.V.SAELDOR, AnimationID.HUMAN_SWORD_LUNGE));
        assertFalse(ChargeIntake.lg(Ar.V.SAELDOR, AnimationID.HUMAN_BOW));
        assertFalse(ChargeIntake.lg(Ar.V.CRYSTAL_BOW, AnimationID.HUMAN_SWORD_SLASH));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(Ar.V.SAELDOR).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(100, Ar.akr(Ar.V.SAELDOR, ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(Ar.V.SAELDOR, Ar.aja(ItemID.BLADE_OF_SAELDOR));
        assertEquals(Ar.V.SAELDOR, Ar.aja(ItemID.BLADE_OF_SAELDOR_INACTIVE));
        assertEquals("the restart icon fallback resolves the blade",
            Ar.V.SAELDOR, Ar.ajt("Blade of Saeldor"));
    }

    @Test
    public void corruptedCrystalWeaponsStayUnmatchedAndTheEchoBowSharesItsFamily()
    {
        assertEquals(2289, ChargeIntake.nx(Ar.V.VENATOR_ECHO));
        assertEquals(Au.FIRE, ChargeIntake.rw(Ar.V.VENATOR_ECHO));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(Ar.V.VENATOR_ECHO).get(ItemID.ANCIENT_ESSENCE));
        assertNull("corrupted (permanently charged) crystal weapons cost nothing",
            Ar.aja(ItemID.BOW_OF_FAERDHINEN_INFINITE));
        assertNull(Ar.aja(ItemID.BOW_OF_FAERDHINEN_INFINITE_ITHELL));
        assertNull(Ar.aja(ItemID.BLADE_OF_SAELDOR_INFINITE));
    }

    @Test
    public void fractionalComponentsAccrueWholeUnits()
    {
        assertArrayEquals(new long[]{Ar.SCALES, 2L, 3L},
            ChargeIntake.tw(Ar.V.V1b));
        assertArrayEquals(new long[]{ItemID.VIAL_BLOOD, 1L, 100L},
            ChargeIntake.tw(Ar.V.SCYTHE));
        assertNull(ChargeIntake.tw(Ar.V.SHADOW));

        assertArrayEquals(new long[]{0L, 2L}, ChargeIntake.accrue(0L, 2L, 3L));
        assertArrayEquals(new long[]{1L, 1L}, ChargeIntake.accrue(2L, 2L, 3L));
        assertArrayEquals(new long[]{1L, 0L}, ChargeIntake.accrue(1L, 2L, 3L));
        assertArrayEquals(new long[]{0L, 99L}, ChargeIntake.accrue(98L, 1L, 100L));
        assertArrayEquals(new long[]{1L, 0L}, ChargeIntake.accrue(99L, 1L, 100L));
    }

    @Test
    public void wornEstimateIdentityMatchesTheWornCheckIdentity()
    {
        int equipmentWidget = WidgetInfo.EQUIPMENT.getId();
        assertEquals((equipmentWidget >>> 16) + ":3:" + ItemID.TOTS_CHARGED + ":TRIDENT_SEAS",
            ChargeIntake.akz(Ar.V.TRIDENT_SEAS,
                ItemID.TOTS_CHARGED));
    }

    @Test
    public void sameVariantChargeStateSwapsAreNeutralButRealMovementsStay()
    {
        Ab full = flow(ItemID.TOTS, "Trident of the Seas (full)", -1L, 845_298L);
        Ab partial = flow(ItemID.TOTS_CHARGED, "Trident of the Seas", 1L, 39_867L);
        Ab rune = flow(ItemID.DEATHRUNE, "Death rune", -1L, 100L);

        assertTrue(Dv.akq(Arrays.asList(full, partial)).isEmpty());
        assertEquals(1, Dv.akq(Arrays.asList(full, partial, rune)).size());
        assertEquals(1, Dv.akq(Collections.singletonList(full)).size());

        Ab swamp = flow(ItemID.TOXIC_TOTS_CHARGED, "Trident of the Swamp", -1L, 3_000L);
        assertEquals(2, Dv.akq(Arrays.asList(swamp, partial)).size());
    }

    private static Ab flow(int itemId, String name, long quantity, long unitPrice)
    {
        return new Ab(itemId, name, quantity, (int) unitPrice, quantity * unitPrice,
            Av.GRAND_EXCHANGE);
    }
}
