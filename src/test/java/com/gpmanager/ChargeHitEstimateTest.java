package com.gpmanager;

import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.*;

/** Owner 2026-09-29: the eye books its tears; blood fury books per melee hit, not per Check. */
public class ChargeHitEstimateTest
{
    @Test
    public void eyeChargeLineIsAParsedTearRead()
    {
        ChargeRead eye = ChargeRead.parseCheckMessage("The Eye of Ayak has been charged with demon tears. It currently has 1,996 charges.");
        assertNotNull("the owner's live line must parse", eye);
        assertEquals(ChargeRead.Variant.EYE_OF_AYAK, eye.variant);
        assertTrue(eye.bookable);
        assertEquals(Long.valueOf(1_996L), eye.componentCounts.get(ItemID.DEMON_TEAR));
    }

    @Test
    public void eyeLoadsValidateForBothChargingModes()
    {
        assertTrue(ChargeRead.isSupportedLoadComponent(ChargeRead.Variant.EYE_OF_AYAK, ItemID.DEMON_TEAR, "Demon tear"));
        assertTrue(ChargeRead.isSupportedLoadComponent(ChargeRead.Variant.EYE_OF_AYAK, ItemID.DEATHRUNE, "Death rune"));
        assertTrue(ChargeRead.isSupportedLoadComponent(ChargeRead.Variant.EYE_OF_AYAK, ItemID.CHAOSRUNE, "Chaos rune"));
        assertFalse("an unrelated rune never validates as an eye load",
            ChargeRead.isSupportedLoadComponent(ChargeRead.Variant.EYE_OF_AYAK, ItemID.BLOODRUNE, "Blood rune"));
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
        ChargeRead runes = ChargeRead.parseCheckMessage("The Eye of Ayak has been charged with runes. It currently has 98 charges.");
        assertNotNull(runes);
        assertEquals(ChargeRead.Variant.EYE_OF_AYAK, runes.variant);
        assertEquals(Long.valueOf(196L), runes.componentCounts.get(ItemID.DEATHRUNE));
        assertEquals(Long.valueOf(98L), runes.componentCounts.get(ItemID.CHAOSRUNE));
        assertNull("a rune-charged eye never books demon tears",
            runes.componentCounts.get(ItemID.DEMON_TEAR));
    }

    @Test
    public void eyeIsAChargeMeasuredFamilyAtOneTearPerCharge()
    {
        assertTrue(ChargeRead.Variant.EYE_OF_AYAK.implemented);
        assertEquals(1, ChargeRead.unitsPerPricedItem(ChargeRead.Variant.EYE_OF_AYAK, ItemID.DEMON_TEAR));
    }

    @Test
    public void eyeCastsBookOneDemonTearLikeASpell()
    {
        assertEquals(ActionKind.CAST, ChargeIntake.kindFor(ChargeRead.Variant.EYE_OF_AYAK));
        assertEquals(Long.valueOf(1L), ChargeIntake.recipeOf(ChargeRead.Variant.EYE_OF_AYAK).get(ItemID.DEMON_TEAR));
        assertEquals(1, ChargeRead.unitsPerPricedItem(ChargeRead.Variant.EYE_OF_AYAK, ItemID.DEMON_TEAR));
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
        assertEquals(ActionKind.SUPPLIES, ChargeIntake.kindFor(ChargeRead.Variant.BLOOD_FURY));
        assertEquals(Long.valueOf(1L), ChargeIntake.recipeOf(ChargeRead.Variant.BLOOD_FURY).get(ItemID.BLOOD_SHARD));
        assertEquals(10_000, ChargeRead.unitsPerPricedItem(ChargeRead.Variant.BLOOD_FURY, ItemID.BLOOD_SHARD));
    }

    @Test
    public void seasTridentEstimatesBookTenCoinsPerCast()
    {
        assertEquals(Long.valueOf(10L), ChargeIntake.recipeOf(ChargeRead.Variant.TRIDENT_SEAS).get(ItemID.COINS));
        assertEquals(Long.valueOf(10L),
            ChargeIntake.recipeOf(ChargeRead.Variant.TRIDENT_SEAS_ENHANCED).get(ItemID.COINS));
    }

    @Test
    public void fractionalPricingCarriesTheWholeItemValueExactly()
    {
        ChargeIntake intake = new ChargeIntake(null, null, null, null, null);
        // 100 units of a 101-gp item: the first 50 book 50, the next 50 book 51 — 101 in total.
        assertEquals(50L, intake.carriedValue(ChargeRead.Variant.BLOOD_FURY, 1, 50L, 101, 100));
        assertEquals(51L, intake.carriedValue(ChargeRead.Variant.BLOOD_FURY, 1, 50L, 101, 100));
        ChargeIntake other = new ChargeIntake(null, null, null, null, null);
        assertEquals("a fresh window books the whole item at once", 101L,
            other.carriedValue(ChargeRead.Variant.BLOOD_FURY, 1, 100L, 101, 100));
    }

    @Test
    public void eyeModeIsTrackedPerIdentity()
    {
        ChargeIntake intake = new ChargeIntake(null, null, null, null, null);
        intake.eyeRunes.put("a", true);
        assertTrue("the rune-charged staff reads runes", intake.eyeRune("a"));
        assertFalse("another staff keeps the tear default", intake.eyeRune("b"));
    }
}
