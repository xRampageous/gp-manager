package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Skill;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What books a charge estimate and at what price: the pinned cast graphics, attack animations and
 * spot anims, the eye's and blood fury's per-hit rules, dart loss by cape slot, recipes, kinds,
 * fractional accrual and charge-state swap neutrality (owners 2026-09-29 and 1.1).
 */
public class ChargeEstimateTriggerTest
{
    private final List<Integer> casts = new ArrayList<>();
    private final ChargeIntake intake = new ChargeIntake(null, null, null, null, null)
    {
        @Override
        void onGraphic(int graphicId, long now)
        {
            casts.add(graphicId);
        }
    };

    @Test
    public void castGraphicsArePinnedPerFamilyAndIgnoreUnsupportedVariants()
    {
        assertEquals(1251, ChargeProbes.nx(ChargeCheck.ChargedItem.TRIDENT_SEAS));
        assertEquals(1251, ChargeProbes.nx(ChargeCheck.ChargedItem.TRIDENT_SEAS_ENHANCED));
        assertEquals(665, ChargeProbes.nx(ChargeCheck.ChargedItem.TRIDENT_SWAMP));
        assertEquals(665, ChargeProbes.nx(ChargeCheck.ChargedItem.TRIDENT_SWAMP_ENHANCED));
        assertEquals(2125, ChargeProbes.nx(ChargeCheck.ChargedItem.SHADOW));
        assertEquals(2567, ChargeProbes.nx(ChargeCheck.ChargedItem.WARPED));
        assertEquals(1540, ChargeProbes.nx(ChargeCheck.ChargedItem.SANGUINESTI));
        assertEquals(1540, ChargeProbes.nx(ChargeCheck.ChargedItem.SANGUINESTI_HOLY));
        assertTrue("the Justiciar casting graphic is still the same family",
            ChargeIntake.isCastGraphic(ChargeCheck.ChargedItem.SANGUINESTI, 1900));
        assertFalse(ChargeIntake.isCastGraphic(ChargeCheck.ChargedItem.SHADOW, 1900));
        assertEquals(2289, ChargeProbes.nx(ChargeCheck.ChargedItem.VENATOR));
        assertEquals(0, ChargeProbes.nx(ChargeCheck.ChargedItem.SCYTHE));
        assertEquals(5061, ChargeIntake.attackAnimation(ChargeCheck.ChargedItem.BLOWPIPE));
        assertEquals(0, ChargeIntake.attackAnimation(ChargeCheck.ChargedItem.VENATOR));
        assertEquals(ActionKind.FIRE, ChargeIntake.kindFor(ChargeCheck.ChargedItem.VENATOR));
        assertEquals(ActionKind.SUPPLIES, ChargeIntake.kindFor(ChargeCheck.ChargedItem.SCYTHE));
        assertEquals(ActionKind.CAST, ChargeIntake.kindFor(ChargeCheck.ChargedItem.SHADOW));
        assertEquals(0, ChargeProbes.nx(ChargeCheck.ChargedItem.BLOWPIPE));
    }

    @Test
    public void castRecipesArePinnedPerFamily()
    {
        Map<Integer, Long> shadow = ChargeIntake.recipeOf(ChargeCheck.ChargedItem.SHADOW);
        assertEquals(Long.valueOf(2L), shadow.get(ItemID.SOULRUNE));
        assertEquals(Long.valueOf(5L), shadow.get(ItemID.CHAOSRUNE));
        assertEquals(2, shadow.size());

        Map<Integer, Long> warped = ChargeIntake.recipeOf(ChargeCheck.ChargedItem.WARPED);
        assertEquals(Long.valueOf(2L), warped.get(ItemID.CHAOSRUNE));
        assertEquals(Long.valueOf(5L), warped.get(ItemID.EARTHRUNE));

        Map<Integer, Long> sang = ChargeIntake.recipeOf(ChargeCheck.ChargedItem.SANGUINESTI);
        assertEquals(Long.valueOf(2L), sang.get(ItemID.BLOODRUNE));
        assertEquals(1, sang.size());
        assertEquals(sang, ChargeIntake.recipeOf(ChargeCheck.ChargedItem.SANGUINESTI_HOLY));

        Map<Integer, Long> venator = ChargeIntake.recipeOf(ChargeCheck.ChargedItem.VENATOR);
        assertEquals(Long.valueOf(1L), venator.get(ItemID.ANCIENT_ESSENCE));
        assertEquals(1, venator.size());

        Map<Integer, Long> scythe = ChargeIntake.recipeOf(ChargeCheck.ChargedItem.SCYTHE);
        assertEquals(Long.valueOf(2L), scythe.get(ItemID.BLOODRUNE));
        Map<Integer, Long> ates = ChargeIntake.recipeOf(ChargeCheck.ChargedItem.ATES);
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
            ChargeIntake.recipeOf(ChargeCheck.ChargedItem.BLOWPIPE).isEmpty());
    }

    @Test
    public void crystalEstimatesFollowTheirPinnedTriggersAndBillOneChargePerAttack()
    {
        // Crystal bow and Bowfa shoot; the halberd swings. One shard carries 100 charges,
        // and each attack books one charge priced at a hundredth of a shard.
        assertEquals(ActionKind.FIRE, ChargeIntake.kindFor(ChargeCheck.ChargedItem.BOWFA));
        assertEquals(ActionKind.FIRE, ChargeIntake.kindFor(ChargeCheck.ChargedItem.CRYSTAL_BOW));
        assertEquals(ActionKind.SUPPLIES, ChargeIntake.kindFor(ChargeCheck.ChargedItem.CRYSTAL_HALBERD));
        assertEquals(1888, ChargeProbes.nx(ChargeCheck.ChargedItem.BOWFA));
        assertEquals(0, ChargeProbes.nx(ChargeCheck.ChargedItem.CRYSTAL_BOW));
        assertEquals(0, ChargeProbes.nx(ChargeCheck.ChargedItem.CRYSTAL_HALBERD));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeCheck.ChargedItem.CRYSTAL_BOW, AnimationID.HUMAN_BOW));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeCheck.ChargedItem.CRYSTAL_HALBERD, AnimationID.HUMAN_SPEAR_SPIKE));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeCheck.ChargedItem.CRYSTAL_HALBERD, AnimationID.HUMAN_SCYTHE_SWEEP));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeCheck.ChargedItem.CRYSTAL_HALBERD, AnimationID.DRAGON_HALBERD_SPECIAL_ATTACK));
        assertFalse(ChargeIntake.isPinnedAttack(ChargeCheck.ChargedItem.CRYSTAL_BOW, AnimationID.HUMAN_SCYTHE_SWEEP));
        assertFalse(ChargeIntake.isPinnedAttack(ChargeCheck.ChargedItem.BOWFA, AnimationID.HUMAN_BOW));
        assertNull(ChargeIntake.fractionOf(ChargeCheck.ChargedItem.CRYSTAL_BOW));
        assertNull(ChargeIntake.fractionOf(ChargeCheck.ChargedItem.CRYSTAL_HALBERD));
        assertNull(ChargeIntake.fractionOf(ChargeCheck.ChargedItem.BOWFA));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(ChargeCheck.ChargedItem.CRYSTAL_BOW).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(ChargeCheck.ChargedItem.CRYSTAL_HALBERD).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(ChargeCheck.ChargedItem.BOWFA).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(100, ChargeCheck.unitsPerItem(ChargeCheck.ChargedItem.CRYSTAL_BOW, ItemID.PRIF_CRYSTAL_SHARD));
        assertArrayEquals("a shard derives its price from the seed exchange",
            new int[]{ItemID.PRIF_TELEPORT_SEED, 150},
            CurrencyProxyCatalogue.proxyOf(ItemID.PRIF_CRYSTAL_SHARD));
    }

    @Test
    public void saeldorIsAShardFedBladeThatBillsOneChargePerAttack()
    {
        // One charge per attack, hit or miss; a shard feeds 100 charges and prices at a hundredth.
        // Its crystal-family Check now books measured shards (ChargeCheckToCheckContractTest).
        assertEquals(ActionKind.SUPPLIES, ChargeIntake.kindFor(ChargeCheck.ChargedItem.SAELDOR));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeCheck.ChargedItem.SAELDOR, AnimationID.HUMAN_SWORD_SLASH));
        assertTrue(ChargeIntake.isPinnedAttack(ChargeCheck.ChargedItem.SAELDOR, AnimationID.HUMAN_SWORD_LUNGE));
        assertFalse(ChargeIntake.isPinnedAttack(ChargeCheck.ChargedItem.SAELDOR, AnimationID.HUMAN_BOW));
        assertFalse(ChargeIntake.isPinnedAttack(ChargeCheck.ChargedItem.CRYSTAL_BOW, AnimationID.HUMAN_SWORD_SLASH));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(ChargeCheck.ChargedItem.SAELDOR).get(ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(100, ChargeCheck.unitsPerItem(ChargeCheck.ChargedItem.SAELDOR, ItemID.PRIF_CRYSTAL_SHARD));
        assertEquals(ChargeCheck.ChargedItem.SAELDOR, ChargeCheck.supportedForId(ItemID.BLADE_OF_SAELDOR));
        assertEquals(ChargeCheck.ChargedItem.SAELDOR, ChargeCheck.supportedForId(ItemID.BLADE_OF_SAELDOR_INACTIVE));
        assertEquals("the restart icon fallback resolves the blade",
            ChargeCheck.ChargedItem.SAELDOR, ChargeCheck.variantForName("Blade of Saeldor"));
    }

    @Test
    public void corruptedCrystalWeaponsStayUnmatchedAndTheEchoBowSharesItsFamily()
    {
        assertEquals(2289, ChargeProbes.nx(ChargeCheck.ChargedItem.VENATOR_ECHO));
        assertEquals(ActionKind.FIRE, ChargeIntake.kindFor(ChargeCheck.ChargedItem.VENATOR_ECHO));
        assertEquals(Long.valueOf(1L),
            ChargeIntake.recipeOf(ChargeCheck.ChargedItem.VENATOR_ECHO).get(ItemID.ANCIENT_ESSENCE));
        assertNull("corrupted (permanently charged) crystal weapons cost nothing",
            ChargeCheck.supportedForId(ItemID.BOW_OF_FAERDHINEN_INFINITE));
        assertNull(ChargeCheck.supportedForId(ItemID.BOW_OF_FAERDHINEN_INFINITE_ITHELL));
        assertNull(ChargeCheck.supportedForId(ItemID.BLADE_OF_SAELDOR_INFINITE));
    }

    @Test
    public void fractionalComponentsAccrueWholeUnits()
    {
        assertArrayEquals(new long[]{ChargeCheck.SCALES, 2L, 3L},
            ChargeIntake.fractionOf(ChargeCheck.ChargedItem.BLOWPIPE));
        assertArrayEquals(new long[]{ItemID.VIAL_BLOOD, 1L, 100L},
            ChargeIntake.fractionOf(ChargeCheck.ChargedItem.SCYTHE));
        assertNull(ChargeIntake.fractionOf(ChargeCheck.ChargedItem.SHADOW));

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
            ChargeIntake.wornIdentity(ChargeCheck.ChargedItem.TRIDENT_SEAS,
                ItemID.TOTS_CHARGED));
    }

    @Test
    public void sameVariantChargeStateSwapsAreNeutralButRealMovementsStay()
    {
        Flow full = flow(ItemID.TOTS, "Trident of the Seas (full)", -1L, 845_298L);
        Flow partial = flow(ItemID.TOTS_CHARGED, "Trident of the Seas", 1L, 39_867L);
        Flow rune = flow(ItemID.DEATHRUNE, "Death rune", -1L, 100L);

        assertTrue(FlowFilters.withoutSwaps(Arrays.asList(full, partial)).isEmpty());
        assertEquals(1, FlowFilters.withoutSwaps(Arrays.asList(full, partial, rune)).size());
        assertEquals(1, FlowFilters.withoutSwaps(Collections.singletonList(full)).size());

        Flow swamp = flow(ItemID.TOXIC_TOTS_CHARGED, "Trident of the Swamp", -1L, 3_000L);
        assertEquals(2, FlowFilters.withoutSwaps(Arrays.asList(swamp, partial)).size());
    }

    @Test
    public void eyeChargeLineIsAParsedTearRead()
    {
        ChargeCheck eye = ChargeCheck.parse("The Eye of Ayak has been charged with demon tears. It currently has 1,996 charges.");
        assertNotNull("the owner's live line must parse", eye);
        assertEquals(ChargeCheck.ChargedItem.EYE_OF_AYAK, eye.variant);
        assertTrue(eye.bookable);
        assertEquals(Long.valueOf(1_996L), eye.componentCounts.get(ItemID.DEMON_TEAR));
    }

    @Test
    public void eyeLoadsValidateForBothChargingModes()
    {
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.EYE_OF_AYAK, ItemID.DEMON_TEAR, "Demon tear"));
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.EYE_OF_AYAK, ItemID.DEATHRUNE, "Death rune"));
        assertTrue(ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.EYE_OF_AYAK, ItemID.CHAOSRUNE, "Chaos rune"));
        assertFalse("an unrelated rune never validates as an eye load",
            ChargeCheck.isLoadComponent(ChargeCheck.ChargedItem.EYE_OF_AYAK, ItemID.BLOODRUNE, "Blood rune"));
    }

    @Test
    public void eyeRecipeFollowsItsOwnChargingMode()
    {
        assertEquals(Long.valueOf(1L), ChargeIntake.eyeRecipe(false).get(ItemID.DEMON_TEAR));
        assertNull(ChargeIntake.eyeRecipe(false).get(ItemID.DEATHRUNE));
        assertEquals(Long.valueOf(2L), ChargeIntake.eyeRecipe(true).get(ItemID.DEATHRUNE));
        assertEquals(Long.valueOf(1L), ChargeIntake.eyeRecipe(true).get(ItemID.CHAOSRUNE));
        assertNull("a rune-charged eye never estimates tears",
            ChargeIntake.eyeRecipe(true).get(ItemID.DEMON_TEAR));
    }

    @Test
    public void eyeRuneModeParsesAsRunesNotTears()
    {
        ChargeCheck runes = ChargeCheck.parse("The Eye of Ayak has been charged with runes. It currently has 98 charges.");
        assertNotNull(runes);
        assertEquals(ChargeCheck.ChargedItem.EYE_OF_AYAK, runes.variant);
        assertEquals(Long.valueOf(196L), runes.componentCounts.get(ItemID.DEATHRUNE));
        assertEquals(Long.valueOf(98L), runes.componentCounts.get(ItemID.CHAOSRUNE));
        assertNull("a rune-charged eye never books demon tears",
            runes.componentCounts.get(ItemID.DEMON_TEAR));
    }

    @Test
    public void eyeIsAChargeMeasuredFamilyAtOneTearPerCharge()
    {
        assertTrue(ChargeCheck.ChargedItem.EYE_OF_AYAK.implemented);
        assertEquals(1, ChargeCheck.unitsPerItem(ChargeCheck.ChargedItem.EYE_OF_AYAK, ItemID.DEMON_TEAR));
    }

    @Test
    public void eyeCastsBookOneDemonTearLikeASpell()
    {
        assertEquals(ActionKind.CAST, ChargeIntake.kindFor(ChargeCheck.ChargedItem.EYE_OF_AYAK));
        assertEquals(Long.valueOf(1L), ChargeIntake.recipeOf(ChargeCheck.ChargedItem.EYE_OF_AYAK).get(ItemID.DEMON_TEAR));
        assertEquals(1, ChargeCheck.unitsPerItem(ChargeCheck.ChargedItem.EYE_OF_AYAK, ItemID.DEMON_TEAR));
    }

    @Test
    public void magicXpPinsTheEyesOwnCastTrigger()
    {
        ChargeIntake intake = new ChargeIntake(null, null, null, null, null);
        intake.eyeCandidateAnimation = 9276;
        intake.eyeCandidateTick = 10;
        intake.learnEyeCast(Skill.MAGIC, 11, 11_000L);
        assertTrue(intake.eyeAnimations.contains(9276));
        assertTrue("the proven candidate never leaks into a later cast", intake.eyeCandidateTick < 0);
        intake.eyeCandidateGraphic = 7000;
        intake.eyeCandidateTick = 20;
        intake.learnEyeCast(Skill.MAGIC, 21, 21_000L);
        assertTrue("the cast graphic is learned too, so a once-only animation cannot starve it",
            intake.eyeGraphics.contains(7000));
    }

    @Test
    public void combatXpStyleDecidesMeleeForEveryWeapon()
    {
        ChargeIntake intake = new ChargeIntake(null, null, null, null, null);
        assertFalse(intake.furyMelee(10));
        intake.learnEyeCast(Skill.ATTACK, 10, 10_000L);
        assertTrue("melee XP confirms the stance", intake.furyMelee(12));
        intake.learnEyeCast(Skill.RANGED, 20, 20_000L);
        assertFalse("ranged XP denies melee", intake.furyMelee(21));
        intake.learnEyeCast(Skill.DEFENCE, 30, 30_000L);
        assertTrue("defensive melee counts too", intake.furyMelee(32));
        intake.learnEyeCast(Skill.MAGIC, 40, 40_000L);
        assertFalse("magic XP denies melee", intake.furyMelee(41));
        intake.learnEyeCast(Skill.ATTACK, 100, 100_000L);
        assertFalse("a stale stance expires", intake.furyMelee(200));
    }

    @Test
    public void furyEstimatesOneTenThousandthOfAShardPerCountedHit()
    {
        // The hit estimate must price in hit units, so the Check delta reconciles on the same scale.
        assertEquals(ActionKind.SUPPLIES, ChargeIntake.kindFor(ChargeCheck.ChargedItem.BLOOD_FURY));
        assertEquals(Long.valueOf(1L), ChargeIntake.recipeOf(ChargeCheck.ChargedItem.BLOOD_FURY).get(ItemID.BLOOD_SHARD));
        assertEquals(10_000, ChargeCheck.unitsPerItem(ChargeCheck.ChargedItem.BLOOD_FURY, ItemID.BLOOD_SHARD));
    }

    @Test
    public void seasTridentEstimatesBookTenCoinsPerCast()
    {
        assertEquals(Long.valueOf(10L), ChargeIntake.recipeOf(ChargeCheck.ChargedItem.TRIDENT_SEAS).get(ItemID.COINS));
        assertEquals(Long.valueOf(10L),
            ChargeIntake.recipeOf(ChargeCheck.ChargedItem.TRIDENT_SEAS_ENHANCED).get(ItemID.COINS));
    }

    @Test
    public void fractionalPricingCarriesTheWholeItemValueExactly()
    {
        ChargeIntake intake = new ChargeIntake(null, null, null, null, null);
        // 100 units of a 101-gp item: the first 50 book 50, the next 50 book 51 — 101 in total.
        assertEquals(50L, intake.carriedValue(ChargeCheck.ChargedItem.BLOOD_FURY, 1, 50L, 101, 100));
        assertEquals(51L, intake.carriedValue(ChargeCheck.ChargedItem.BLOOD_FURY, 1, 50L, 101, 100));
        ChargeIntake other = new ChargeIntake(null, null, null, null, null);
        assertEquals("a fresh window books the whole item at once", 101L,
            other.carriedValue(ChargeCheck.ChargedItem.BLOOD_FURY, 1, 100L, 101, 100));
    }

    @Test
    public void eyeModeIsTrackedPerIdentity()
    {
        ChargeIntake intake = new ChargeIntake(null, null, null, null, null);
        intake.eyeRunes.put("a", true);
        assertTrue("the rune-charged staff reads runes", intake.eyeRune("a"));
        assertFalse("another staff keeps the tear default", intake.eyeRune("b"));
    }

    @Test
    public void aCastStillPlayingIsNotBookedAgainWhenAnotherAnimJoins()
    {
        intake.spotAnims(Arrays.asList(anim(1251, 100)), 0L);
        intake.spotAnims(Arrays.asList(anim(1251, 100), anim(85, 130)), 0L);
        assertEquals(Arrays.asList(1251, 85), casts);
    }

    @Test
    public void theSameGraphicCastAgainIsANewAnim()
    {
        intake.spotAnims(Arrays.asList(anim(1251, 100)), 0L);
        intake.spotAnims(Arrays.asList(anim(1251, 160)), 0L);
        intake.spotAnims(Arrays.asList(), 0L);
        intake.spotAnims(Arrays.asList(anim(1251, 220)), 0L);
        assertEquals(Arrays.asList(1251, 1251, 1251), casts);
    }

    @Test
    public void twoNewAnimsOnOneChangeAreEachOffered()
    {
        // Each keeps one booking per tick and graphic, and ignores graphics no weapon uses.
        intake.spotAnims(Arrays.asList(anim(1251, 100), anim(1252, 100)), 0L);
        assertEquals(Arrays.asList(1251, 1252), casts);
    }

    @Test
    public void theCapeSlotSetsTheDartsEachShotSpends()
    {
        assertArrayEquals(new long[] {1L, 1L}, ChargeIntake.dartLoss(""));
        assertArrayEquals(new long[] {1L, 1L}, ChargeIntake.dartLoss(null));
        assertArrayEquals(new long[] {2L, 5L}, ChargeIntake.dartLoss("Ava's attractor"));
        assertArrayEquals(new long[] {7L, 25L}, ChargeIntake.dartLoss("Ava's accumulator"));
        assertArrayEquals(new long[] {1L, 5L}, ChargeIntake.dartLoss("Ava's assembler"));
        assertArrayEquals(new long[] {1L, 5L}, ChargeIntake.dartLoss("Masori assembler max cape"));
        assertArrayEquals(new long[] {1L, 5L}, ChargeIntake.dartLoss("Blessed dizana's quiver"));
    }

    @Test
    public void anAssemblerBooksOneDartEveryFiveShots()
    {
        long carry = 0L;
        long booked = 0L;
        for (int shot = 0; shot < 25; shot++)
        {
            long[] step = ChargeIntake.accrue(carry, 1L, 5L);
            booked += step[0];
            carry = step[1];
        }
        assertEquals(5L, booked);
        assertEquals(0L, carry);
    }

    @Test
    public void aCheckOrLoadNamesTheDart()
    {
        ChargeIntake intake = new ChargeIntake(null, null, null, null, null);
        intake.blowpipeDart = -1;
        assertTrue("the dart table knows dragon darts", ChargeCheck.DARTS.containsValue(11230));
    }

    private static ActorSpotAnim anim(int id, int startCycle)
    {
        return (ActorSpotAnim) Proxy.newProxyInstance(ActorSpotAnim.class.getClassLoader(),
            new Class<?>[] {ActorSpotAnim.class}, (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getId": return id;
                    case "getStartCycle": return startCycle;
                    default:
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                }
            });
    }

    private static Flow flow(int itemId, String name, long quantity, long unitPrice)
    {
        return new Flow(itemId, name, quantity, (int) unitPrice, quantity * unitPrice,
            PriceSource.GRAND_EXCHANGE);
    }
}
