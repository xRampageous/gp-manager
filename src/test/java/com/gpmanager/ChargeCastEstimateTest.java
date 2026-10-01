package com.gpmanager;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.InterfaceID;
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
        assertEquals(1251, ChargeIntake.castGraphic(ChargeRead.Variant.TRIDENT_SEAS));
        assertEquals(1251, ChargeIntake.castGraphic(ChargeRead.Variant.TRIDENT_SEAS_ENHANCED));
        assertEquals(665, ChargeIntake.castGraphic(ChargeRead.Variant.TRIDENT_SWAMP));
        assertEquals(665, ChargeIntake.castGraphic(ChargeRead.Variant.TRIDENT_SWAMP_ENHANCED));
        assertEquals(2125, ChargeIntake.castGraphic(ChargeRead.Variant.SHADOW));
        assertEquals(2567, ChargeIntake.castGraphic(ChargeRead.Variant.WARPED));
        assertEquals(1540, ChargeIntake.castGraphic(ChargeRead.Variant.SANGUINESTI));
        assertEquals(1540, ChargeIntake.castGraphic(ChargeRead.Variant.SANGUINESTI_HOLY));
        assertTrue("the Justiciar casting graphic is still the same family",
            ChargeIntake.isCastGraphic(ChargeRead.Variant.SANGUINESTI, 1900));
        assertFalse(ChargeIntake.isCastGraphic(ChargeRead.Variant.SHADOW, 1900));
        assertEquals(2289, ChargeIntake.castGraphic(ChargeRead.Variant.VENATOR));
        assertEquals(0, ChargeIntake.castGraphic(ChargeRead.Variant.SCYTHE));
        assertEquals(5061, ChargeIntake.attackAnimation(ChargeRead.Variant.V1b));
        assertEquals(0, ChargeIntake.attackAnimation(ChargeRead.Variant.VENATOR));
        assertEquals(ActionKind.FIRE, ChargeIntake.kindFor(ChargeRead.Variant.VENATOR));
        assertEquals(ActionKind.SUPPLIES, ChargeIntake.kindFor(ChargeRead.Variant.SCYTHE));
        assertEquals(ActionKind.CAST, ChargeIntake.kindFor(ChargeRead.Variant.SHADOW));
        assertEquals(0, ChargeIntake.castGraphic(ChargeRead.Variant.V1b));
    }

    @Test
    public void castRecipesArePinnedPerFamily()
    {
        Map<Integer, Long> shadow = ChargeIntake.recipeOf(ChargeRead.Variant.SHADOW);
        assertEquals(Long.valueOf(2L), shadow.get(ItemID.SOULRUNE));
        assertEquals(Long.valueOf(5L), shadow.get(ItemID.CHAOSRUNE));
        assertEquals(2, shadow.size());

        Map<Integer, Long> warped = ChargeIntake.recipeOf(ChargeRead.Variant.WARPED);
        assertEquals(Long.valueOf(2L), warped.get(ItemID.CHAOSRUNE));
        assertEquals(Long.valueOf(5L), warped.get(ItemID.EARTHRUNE));

        Map<Integer, Long> sang = ChargeIntake.recipeOf(ChargeRead.Variant.SANGUINESTI);
        assertEquals(Long.valueOf(2L), sang.get(ItemID.BLOODRUNE));
        assertEquals(1, sang.size());
        assertEquals(sang, ChargeIntake.recipeOf(ChargeRead.Variant.SANGUINESTI_HOLY));

        Map<Integer, Long> venator = ChargeIntake.recipeOf(ChargeRead.Variant.VENATOR);
        assertEquals(Long.valueOf(1L), venator.get(ItemID.ANCIENT_ESSENCE));
        assertEquals(1, venator.size());

        Map<Integer, Long> scythe = ChargeIntake.recipeOf(ChargeRead.Variant.SCYTHE);
        assertEquals(Long.valueOf(2L), scythe.get(ItemID.BLOODRUNE));
        Map<Integer, Long> ates = ChargeIntake.recipeOf(ChargeRead.Variant.ATES);
        assertEquals(Long.valueOf(1L), ates.get(ItemID.FROZEN_TEAR));
        assertEquals(1, ates.size());
        assertTrue("a pendant location click spends a tear", ChargeIntake.isAtesLocation("darkfrost"));
        assertTrue(ChargeIntake.isAtesLocation("the darkfrost"));
        assertTrue(ChargeIntake.isAtesLocation("twilight temple"));
        assertTrue(ChargeIntake.isAtesLocation("ralos' rise"));
        assertTrue(ChargeIntake.isAtesLocation("north aldarin"));
        assertTrue(ChargeIntake.isAtesLocation("kastori"));
        assertTrue(ChargeIntake.isAtesLocation("nemus retreat"));
        assertFalse(ChargeIntake.isAtesLocation("check"));
        assertFalse(ChargeIntake.isAtesLocation("uncharge"));
        assertFalse("rubbing opens the interface; it never spends a charge by itself",
            ChargeIntake.isAtesLocation("rub"));
        assertFalse("a bank withdrawal is not a teleport", ChargeIntake.isAtesLocation("withdraw-1"));
        assertFalse(ChargeIntake.isAtesLocation("withdraw-all"));
        assertFalse(ChargeIntake.isAtesLocation("deposit-1"));
        assertFalse(ChargeIntake.isAtesLocation("examine"));
        assertFalse(ChargeIntake.isAtesLocation("take"));
        assertFalse(ChargeIntake.isAtesLocation(""));
        assertTrue("blowpipe scales accrue fractionally, nothing whole per shot",
            ChargeIntake.recipeOf(ChargeRead.Variant.V1b).isEmpty());
    }

    @Test
    public void crystalEstimatesFollowTheirPinnedTriggersAndBillOneChargePerAttack()
    {
        // Crystal bow and Bowfa shoot; the halberd swings. One shard carries 100 charges,
        // and each attack books one charge priced at a hundredth of a shard.
        assertEquals(ActionKind.FIRE, ChargeIntake.kindFor(ChargeRead.Variant.BOWFA));
        assertEquals(ActionKind.FIRE, ChargeIntake.kindFor(ChargeRead.Variant.CRYSTAL_BOW));
        assertEquals(ActionKind.SUPPLIES, ChargeIntake.kindFor(ChargeRead.Variant.CRYSTAL_HALBERD));
        assertEquals(1888, ChargeIntake.castGraphic(ChargeRead.Variant.BOWFA));
        assertEquals(0, ChargeIntake.castGraphic(ChargeRead.Variant.CRYSTAL_BOW));
        assertEquals(0, ChargeIntake.castGraphic(ChargeRead.Variant.CRYSTAL_HALBERD));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeRead.Variant.CRYSTAL_BOW, AnimationID.HUMAN_BOW));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeRead.Variant.CRYSTAL_HALBERD, AnimationID.HUMAN_SPEAR_SPIKE));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeRead.Variant.CRYSTAL_HALBERD, AnimationID.HUMAN_SCYTHE_SWEEP));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeRead.Variant.CRYSTAL_HALBERD, AnimationID.DRAGON_HALBERD_SPECIAL_ATTACK));
        assertFalse(ChargeIntake.isPinnedAttack(ChargeRead.Variant.CRYSTAL_BOW, AnimationID.HUMAN_SCYTHE_SWEEP));
        assertFalse(ChargeIntake.isPinnedAttack(ChargeRead.Variant.BOWFA, AnimationID.HUMAN_BOW));
        assertNull(ChargeIntake.fractionOf(ChargeRead.Variant.CRYSTAL_BOW));
        assertNull(ChargeIntake.fractionOf(ChargeRead.Variant.CRYSTAL_HALBERD));
        assertNull(ChargeIntake.fractionOf(ChargeRead.Variant.BOWFA));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(ChargeRead.Variant.CRYSTAL_BOW).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(ChargeRead.Variant.CRYSTAL_HALBERD).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(ChargeRead.Variant.BOWFA).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(100, ChargeRead.unitsPerPricedItem(ChargeRead.Variant.CRYSTAL_BOW, ItemID.PRIF_CRYSTAL_SHARD));
        assertArrayEquals("a shard derives its price from the seed exchange",
            new int[]{ItemID.PRIF_TELEPORT_SEED, 150},
            CurrencyProxyCatalogue.proxyOf(ItemID.PRIF_CRYSTAL_SHARD));
    }

    @Test
    public void saeldorIsAShardFedBladeThatBillsOneChargePerAttack()
    {
        // One charge per attack, hit or miss; a shard feeds 100 charges and prices at a hundredth.
        // The Check text is not pinned yet, so the blade is estimate-only for now.
        assertEquals(ActionKind.SUPPLIES, ChargeIntake.kindFor(ChargeRead.Variant.SAELDOR));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeRead.Variant.SAELDOR, AnimationID.HUMAN_SWORD_SLASH));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeRead.Variant.SAELDOR, AnimationID.HUMAN_SWORD_LUNGE));
        assertFalse(ChargeIntake.isPinnedAttack(ChargeRead.Variant.SAELDOR, AnimationID.HUMAN_BOW));
        assertFalse(ChargeIntake.isPinnedAttack(ChargeRead.Variant.CRYSTAL_BOW, AnimationID.HUMAN_SWORD_SLASH));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(ChargeRead.Variant.SAELDOR).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(100, ChargeRead.unitsPerPricedItem(ChargeRead.Variant.SAELDOR, ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(ChargeRead.Variant.SAELDOR, ChargeRead.supportedVariantForItemId(ItemID.BLADE_OF_SAELDOR));
        assertEquals(ChargeRead.Variant.SAELDOR, ChargeRead.supportedVariantForItemId(ItemID.BLADE_OF_SAELDOR_INACTIVE));
        assertEquals("the restart icon fallback resolves the blade",
            ChargeRead.Variant.SAELDOR, ChargeRead.variantNamed("Blade of Saeldor"));
    }

    @Test
    public void corruptedCrystalWeaponsStayUnmatchedAndTheEchoBowSharesItsFamily()
    {
        assertEquals(2289, ChargeIntake.castGraphic(ChargeRead.Variant.VENATOR_ECHO));
        assertEquals(ActionKind.FIRE, ChargeIntake.kindFor(ChargeRead.Variant.VENATOR_ECHO));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(ChargeRead.Variant.VENATOR_ECHO).get(ItemID.ANCIENT_ESSENCE));
        assertNull("corrupted (permanently charged) crystal weapons cost nothing",
            ChargeRead.supportedVariantForItemId(ItemID.BOW_OF_FAERDHINEN_INFINITE));
        assertNull(ChargeRead.supportedVariantForItemId(ItemID.BOW_OF_FAERDHINEN_INFINITE_ITHELL));
        assertNull(ChargeRead.supportedVariantForItemId(ItemID.BLADE_OF_SAELDOR_INFINITE));
    }

    @Test
    public void fractionalComponentsAccrueWholeUnits()
    {
        assertArrayEquals(new long[]{ChargeRead.SCALES, 2L, 3L},
            ChargeIntake.fractionOf(ChargeRead.Variant.V1b));
        assertArrayEquals(new long[]{ItemID.VIAL_BLOOD, 1L, 100L},
            ChargeIntake.fractionOf(ChargeRead.Variant.SCYTHE));
        assertNull(ChargeIntake.fractionOf(ChargeRead.Variant.SHADOW));

        assertArrayEquals(new long[]{0L, 2L}, ChargeIntake.accrue(0L, 2L, 3L));
        assertArrayEquals(new long[]{1L, 1L}, ChargeIntake.accrue(2L, 2L, 3L));
        assertArrayEquals(new long[]{1L, 0L}, ChargeIntake.accrue(1L, 2L, 3L));
        assertArrayEquals(new long[]{0L, 99L}, ChargeIntake.accrue(98L, 1L, 100L));
        assertArrayEquals(new long[]{1L, 0L}, ChargeIntake.accrue(99L, 1L, 100L));
    }

    @Test
    public void wornEstimateIdentityMatchesTheWornCheckIdentity()
    {
        int equipmentWidget = InterfaceID.Wornitems.UNIVERSE;
        assertEquals((equipmentWidget >>> 16) + ":3:" + ItemID.TOTS_CHARGED + ":TRIDENT_SEAS",
            ChargeIntake.wornTargetIdentity(ChargeRead.Variant.TRIDENT_SEAS,
                ItemID.TOTS_CHARGED));
    }

    @Test
    public void sameVariantChargeStateSwapsAreNeutralButRealMovementsStay()
    {
        Flow full = flow(ItemID.TOTS, "Trident of the Seas (full)", -1L, 845_298L);
        Flow partial = flow(ItemID.TOTS_CHARGED, "Trident of the Seas", 1L, 39_867L);
        Flow rune = flow(ItemID.DEATHRUNE, "Death rune", -1L, 100L);

        assertTrue(FlowFilters.withoutChargeStateSwaps(Arrays.asList(full, partial)).isEmpty());
        assertEquals(1, FlowFilters.withoutChargeStateSwaps(Arrays.asList(full, partial, rune)).size());
        assertEquals(1, FlowFilters.withoutChargeStateSwaps(Collections.singletonList(full)).size());

        Flow swamp = flow(ItemID.TOXIC_TOTS_CHARGED, "Trident of the Swamp", -1L, 3_000L);
        assertEquals(2, FlowFilters.withoutChargeStateSwaps(Arrays.asList(swamp, partial)).size());
    }

    private static Flow flow(int itemId, String name, long quantity, long unitPrice)
    {
        return new Flow(itemId, name, quantity, (int) unitPrice, quantity * unitPrice,
            PriceSource.GRAND_EXCHANGE);
    }
}
