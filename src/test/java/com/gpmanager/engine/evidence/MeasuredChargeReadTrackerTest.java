package com.gpmanager.engine.evidence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.runelite.api.gameval.ItemID;
import org.junit.Test;

public final class MeasuredChargeReadTrackerTest
{
    private static final int DEATH_RUNE = 560;
    private static final int CHAOS_RUNE = 562;
    private static final int FIRE_RUNE = 554;
    private static final int ZULRAH_SCALES = 12934;
    private static final int COINS = 995;

    @Test
    public void parsesTridentCountsAndExactRecipes()
    {
        MeasuredChargeRead seas = MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the seas has 2,000 charges.");
        assertNotNull(seas);
        assertTrue(seas.isBookable());
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SEAS, seas.getVariant());
        assertEquals(2_000L, seas.getChargeCount());
        assertEquals(2_000L, seas.getComponentCounts().get(DEATH_RUNE).longValue());
        assertEquals(2_000L, seas.getComponentCounts().get(CHAOS_RUNE).longValue());
        assertEquals(10_000L, seas.getComponentCounts().get(FIRE_RUNE).longValue());
        assertEquals(20_000L, seas.getComponentCounts().get(COINS).longValue());

        MeasuredChargeRead swamp = MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the swamp has one charge.");
        assertNotNull(swamp);
        assertEquals(1L, swamp.getChargeCount());
        assertEquals(1L, swamp.getComponentCounts().get(DEATH_RUNE).longValue());
        assertEquals(1L, swamp.getComponentCounts().get(CHAOS_RUNE).longValue());
        assertEquals(5L, swamp.getComponentCounts().get(FIRE_RUNE).longValue());
        assertEquals(1L, swamp.getComponentCounts().get(ZULRAH_SCALES).longValue());

        MeasuredChargeRead empty = MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the seas has no charges.");
        assertNotNull(empty);
        assertEquals(0L, empty.getChargeCount());
    }

    @Test
    public void enhancedSwampIsSupportedButEnhancedSeasIsNot()
    {
        MeasuredChargeRead swamp = MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the swamp (e) has 20,000 charges.");
        assertNotNull(swamp);
        assertTrue(swamp.isBookable());
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SWAMP_ENHANCED, swamp.getVariant());
        assertEquals(100_000L, swamp.getComponentCounts().get(FIRE_RUNE).longValue());

        MeasuredChargeRead seas = MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the seas (e) has 20,000 charges.");
        assertNotNull(seas);
        assertFalse(seas.isBookable());
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SEAS_ENHANCED, seas.getVariant());
        assertTrue(seas.getComponentCounts().isEmpty());
    }

    @Test
    public void parsesBlowpipeDartsAndScalesAsIndependentComponents()
    {
        MeasuredChargeRead read = MeasuredChargeRead.parseCheckMessage(
            "Darts: <col=007f00>Adamant dart x 16,383</col>. "
                + "Scales: <col=007f00>1,234 (7.5%)</col>.");
        assertNotNull(read);
        assertTrue(read.isBookable());
        assertEquals(ItemID.ADAMANT_DART, read.getDartItemId());
        assertEquals(16_383L, read.getDartCount());
        assertEquals(1_234L, read.getComponentCounts().get(ZULRAH_SCALES).longValue());
        assertEquals(16_383L, read.getComponentCounts().get(ItemID.ADAMANT_DART).longValue());

        MeasuredChargeRead noDarts = MeasuredChargeRead.parseCheckMessage(
            "Darts: None. Scales: 99 (0.6%).");
        assertNotNull(noDarts);
        assertTrue(noDarts.isBookable());
        assertFalse(noDarts.hasDarts());
        assertEquals(99L, noDarts.getComponentCounts().get(ZULRAH_SCALES).longValue());
        assertFalse(noDarts.getComponentCounts().containsKey(ItemID.ADAMANT_DART));
    }

    @Test
    public void unknownDartTypeReturnsUnsupportedReadToResetBaseline()
    {
        MeasuredChargeRead unknown = MeasuredChargeRead.parseCheckMessage(
            "Darts: Exotic dart x 100. Scales: 90 (1.0%).");
        assertNotNull(unknown);
        assertFalse(unknown.isBookable());
        assertTrue(unknown.getComponentCounts().isEmpty());

        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(tracker.observe(blowpipe("Adamant", 100, 100)));
        assertNull(tracker.observe(unknown));
        assertNull(tracker.getBaseline());
        assertNull(tracker.observe(blowpipe("Adamant", 95, 98)));
    }

    @Test
    public void tridentTrackerReturnsOnlyMeasuredNegativeIngredientCounts()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(tracker.observe(trident("seas", "", 12)));

        MeasuredChargeDelta spend = tracker.observe(trident("seas", "", 9));
        assertNotNull(spend);
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SEAS, spend.getVariant());
        assertEquals(ChargeFamilyIds.TRIDENT, spend.getFamilyId());
        assertEquals(-3L, quantityDelta(spend, DEATH_RUNE));
        assertEquals(-3L, quantityDelta(spend, CHAOS_RUNE));
        assertEquals(-15L, quantityDelta(spend, FIRE_RUNE));
        assertEquals(-30L, quantityDelta(spend, COINS));

        assertNull(tracker.observe(trident("seas", "", 9)));
        assertNull(tracker.observe(trident("seas", "", 11)));
        assertNull(tracker.observe(trident("swamp", "", 5)));
        MeasuredChargeDelta swampSpend = tracker.observe(trident("swamp", "", 3));
        assertNotNull(swampSpend);
        assertEquals(-2L, quantityDelta(swampSpend, DEATH_RUNE));
        assertEquals(-10L, quantityDelta(swampSpend, FIRE_RUNE));
        assertEquals(-2L, quantityDelta(swampSpend, ZULRAH_SCALES));
        assertEquals(4, swampSpend.getComponentDeltas().size());
    }

    @Test
    public void blowpipeTrackerMeasuresComponentsAndNeverUsesRatios()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(tracker.observe(blowpipe("Adamant", 1_000, 1_000)));
        MeasuredChargeDelta spend = tracker.observe(blowpipe("Adamant", 987, 996));
        assertNotNull(spend);
        assertEquals(2, spend.getComponentDeltas().size());
        assertEquals(-13L, quantityDelta(spend, ZULRAH_SCALES));
        assertEquals(-4L, quantityDelta(spend, ItemID.ADAMANT_DART));

        MeasuredChargeDelta independent = tracker.observe(blowpipe("Adamant", 980, 1_002));
        assertNotNull(independent);
        assertEquals(1, independent.getComponentDeltas().size());
        assertEquals(-7L, quantityDelta(independent, ZULRAH_SCALES));
    }

    @Test
    public void blowpipeDartTypeSwitchKeepsIndependentScaleMeasurement()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(tracker.observe(blowpipe("Adamant", 100, 100)));
        MeasuredChargeDelta switchDelta = tracker.observe(blowpipe("Rune", 90, 99));
        assertNotNull(switchDelta);
        assertEquals(1, switchDelta.getComponentDeltas().size());
        assertEquals(-10L, quantityDelta(switchDelta, ZULRAH_SCALES));
        assertEquals(ItemID.RUNE_DART, tracker.getBaseline().getDartItemId());
        MeasuredChargeDelta afterSwitch = tracker.observe(blowpipe("Rune", 80, 97));
        assertNotNull(afterSwitch);
        assertEquals(-10L, quantityDelta(afterSwitch, ZULRAH_SCALES));
        assertEquals(-2L, quantityDelta(afterSwitch, ItemID.RUNE_DART));
    }

    @Test
    public void sameVariantWeaponTargetChangeSeedsInsteadOfComparingDifferentWeapons()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        MeasuredChargeRead seas = MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the seas has 5,000 charges.");
        assertNull(tracker.observe(seas, "inventory:slot-3:item-11907"));
        assertNull(tracker.observe(MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the seas has 1,000 charges."), "inventory:slot-9:item-11907"));

        MeasuredChargeDelta spend = tracker.observe(MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the seas has 999 charges."), "inventory:slot-9:item-11907");
        assertNotNull(spend);
        assertEquals(-1L, quantityDelta(spend, 560));
    }

    @Test
    public void checkTargetIdentityRecognizesChargedAndUnchargedItems()
    {
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SEAS,
            MeasuredChargeRead.supportedVariantForItemId(11907));
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SEAS,
            MeasuredChargeRead.supportedVariantForItemId(11908));
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SWAMP,
            MeasuredChargeRead.supportedVariantForItemId(12899));
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SWAMP_ENHANCED,
            MeasuredChargeRead.supportedVariantForItemId(22294));
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SEAS_ENHANCED,
            MeasuredChargeRead.supportedVariantForItemId(22288));
        assertEquals(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            MeasuredChargeRead.supportedVariantForItemId(12926));
    }

    @Test
    public void explicitNoDartsClosesPriorKnownDartCount()
    {
        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(tracker.observe(blowpipe("Dragon", 500, 3)));
        MeasuredChargeDelta spend = tracker.observe(noDarts(497));
        assertNotNull(spend);
        assertEquals(-3L, quantityDelta(spend, ZULRAH_SCALES));
        assertEquals(-3L, quantityDelta(spend, ItemID.DRAGON_DART));
    }

    @Test
    public void supportedIdentityAndLoadComponentChecksAreExact()
    {
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SEAS,
            MeasuredChargeRead.supportedVariantForItemName("<col=ff9040>Trident of the seas</col>"));
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SWAMP,
            MeasuredChargeRead.supportedVariantForItemName("Trident of the swamp"));
        assertEquals(MeasuredChargeRead.Variant.TRIDENT_SWAMP_ENHANCED,
            MeasuredChargeRead.supportedVariantForItemName("Trident of the swamp (e)"));
        assertEquals(MeasuredChargeRead.Variant.TOXIC_BLOWPIPE,
            MeasuredChargeRead.supportedVariantForItemName("Toxic blowpipe"));
        assertNull(MeasuredChargeRead.supportedVariantForItemName("Trident of the seas (e)"));
        assertNull(MeasuredChargeRead.supportedVariantForItemName("Blazing blowpipe"));

        assertTrue(MeasuredChargeRead.isSupportedLoadComponent(
            MeasuredChargeRead.Variant.TRIDENT_SEAS, DEATH_RUNE, "Death runes"));
        assertTrue(MeasuredChargeRead.isSupportedLoadComponent(
            MeasuredChargeRead.Variant.TRIDENT_SEAS, COINS, "Coins"));
        assertFalse(MeasuredChargeRead.isSupportedLoadComponent(
            MeasuredChargeRead.Variant.TRIDENT_SEAS, ZULRAH_SCALES, "Zulrah's scales"));
        assertTrue(MeasuredChargeRead.isSupportedLoadComponent(
            MeasuredChargeRead.Variant.TRIDENT_SWAMP_ENHANCED, ZULRAH_SCALES, "Zulrah's scales"));
        assertTrue(MeasuredChargeRead.isSupportedLoadComponent(
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE, ItemID.ADAMANT_DART, "Adamant dart"));
        assertFalse(MeasuredChargeRead.isSupportedLoadComponent(
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE, ItemID.ADAMANT_DART, "Rune dart"));
        assertFalse(MeasuredChargeRead.isSupportedLoadComponent(
            MeasuredChargeRead.Variant.TOXIC_BLOWPIPE, ItemID.DRAGON_DART_P, "Dragon dart"));
    }

    @Test
    public void unrelatedAndMalformedMessagesCannotSpend()
    {
        assertNull(MeasuredChargeRead.parseCheckMessage("You cast a spell."));
        MeasuredChargeRead malformed = MeasuredChargeRead.parseCheckMessage(
            "Darts: Adamant dart x many. Scales: 100 (1.0%).");
        assertNotNull(malformed);
        assertFalse(malformed.isBookable());

        MeasuredChargeReadTracker tracker = new MeasuredChargeReadTracker();
        assertNull(tracker.observe(trident("seas", "", 10)));
        assertNull(tracker.observe(malformed));
        assertNull(tracker.getBaseline());
    }

    private static MeasuredChargeRead trident(String type, String enhanced, long charges)
    {
        String count = charges == 1L ? "one charge" : charges == 0L ? "no charges" : charges + " charges";
        return MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the " + type + enhanced + " has " + count + ".");
    }

    private static MeasuredChargeRead blowpipe(String dart, long scales, long darts)
    {
        return MeasuredChargeRead.parseCheckMessage(
            "Darts: " + dart + " dart x " + darts + ". Scales: " + scales + " (1.0%).");
    }

    private static MeasuredChargeRead noDarts(long scales)
    {
        return MeasuredChargeRead.parseCheckMessage(
            "Darts: None. Scales: " + scales + " (1.0%).");
    }

    private static long quantityDelta(MeasuredChargeDelta delta, int itemId)
    {
        assertNotNull(delta);
        for (MeasuredChargeDelta.ComponentDelta component : delta.getComponentDeltas())
        {
            if (component.getItemId() == itemId)
            {
                return component.getQuantityDelta();
            }
        }
        throw new AssertionError("Missing component delta for item " + itemId);
    }
}
