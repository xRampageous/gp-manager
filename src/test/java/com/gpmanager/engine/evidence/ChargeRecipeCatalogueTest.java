package com.gpmanager.engine.evidence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChargeRecipeCatalogueTest
{
    private static final int DEATH_RUNE = 560;
    private static final int CHAOS_RUNE = 562;
    private static final int FIRE_RUNE = 554;
    private static final int COINS = 995;
    private static final int ZULRAH_SCALES = 12934;

    @Test
    public void tridentSeasChargeIncreaseUsesVariantSpecificComponentRatios()
    {
        MeasuredChargeRead before = trident("seas", "", 10L);
        MeasuredChargeRead after = trident("seas", "", 12L);

        Map<Integer, Long> evidence = ChargeRecipeCatalogue.exactLoadQuantities(before, after);

        Map<Integer, Long> expected = new LinkedHashMap<>();
        expected.put(DEATH_RUNE, 2L);
        expected.put(CHAOS_RUNE, 2L);
        expected.put(FIRE_RUNE, 10L);
        expected.put(COINS, 20L);
        assertEquals(expected, evidence);
        assertFalse("Recipe output must be immutable", isMutable(evidence));
    }

    @Test
    public void tridentSwampVariantsUseScalesRatherThanCoins()
    {
        Map<Integer, Long> regular = ChargeRecipeCatalogue.exactLoadQuantities(
            trident("swamp", "", 4L), trident("swamp", "", 7L));
        Map<Integer, Long> enhanced = ChargeRecipeCatalogue.exactLoadQuantities(
            trident("swamp", " (e)", 4L), trident("swamp", " (e)", 7L));

        Map<Integer, Long> expected = new LinkedHashMap<>();
        expected.put(DEATH_RUNE, 3L);
        expected.put(CHAOS_RUNE, 3L);
        expected.put(FIRE_RUNE, 15L);
        expected.put(ZULRAH_SCALES, 3L);
        assertEquals(expected, regular);
        assertEquals(expected, enhanced);
        assertFalse(regular.containsKey(COINS));
    }

    @Test
    public void blowpipeUsesOnlyMeasuredScaleIncreaseAndNeverDartChanges()
    {
        Map<Integer, Long> scalesAndDarts = ChargeRecipeCatalogue.exactLoadQuantities(
            blowpipe("Adamant dart", 100L, 50L),
            blowpipe("Adamant dart", 450L, 80L));
        Map<Integer, Long> dartsOnly = ChargeRecipeCatalogue.exactLoadQuantities(
            blowpipe("Adamant dart", 100L, 50L),
            blowpipe("Adamant dart", 100L, 80L));

        assertEquals(Collections.singletonMap(ZULRAH_SCALES, 350L), scalesAndDarts);
        assertTrue(dartsOnly.isEmpty());
    }

    @Test
    public void noEvidenceForDecreasesSameReadsDifferentVariantsOrUnsupportedVariants()
    {
        assertTrue(ChargeRecipeCatalogue.exactLoadQuantities(
            trident("seas", "", 12L), trident("seas", "", 10L)).isEmpty());
        assertTrue(ChargeRecipeCatalogue.exactLoadQuantities(
            trident("seas", "", 10L), trident("seas", "", 10L)).isEmpty());
        assertTrue(ChargeRecipeCatalogue.exactLoadQuantities(
            trident("seas", "", 10L), trident("swamp", "", 11L)).isEmpty());
        assertTrue(ChargeRecipeCatalogue.exactLoadQuantities(
            blowpipe("Adamant dart", 10L, 10L),
            MeasuredChargeRead.parseCheckMessage("Darts: Unknown dart x 12. Scales: 12 (1.0%).")).isEmpty());
        assertTrue(ChargeRecipeCatalogue.exactLoadQuantities(
            trident("seas", "", 10L),
            trident("seas", " (e)", 12L)).isEmpty());
        assertTrue(ChargeRecipeCatalogue.exactLoadQuantities(null, trident("seas", "", 12L)).isEmpty());
    }

    @Test
    public void arithmeticOverflowAndUnsupportedReadsFailClosed()
    {
        MeasuredChargeRead baseline = trident("swamp", "", 0L);
        MeasuredChargeRead overflowing = MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the swamp has 9223372036854775807 charges.");

        assertFalse("The overflowed parsed read must not be bookable", overflowing.isBookable());
        assertTrue(ChargeRecipeCatalogue.exactLoadQuantities(baseline, overflowing).isEmpty());
        assertTrue(ChargeRecipeCatalogue.exactLoadQuantities(
            MeasuredChargeRead.parseCheckMessage("Your Trident of the seas has 999999999999999999999999999 charges."),
            trident("seas", "", 1L)).isEmpty());
    }

    private static MeasuredChargeRead trident(String type, String enhanced, long charges)
    {
        return MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the " + type + enhanced + " has " + charges
                + (charges == 1L ? " charge." : " charges."));
    }

    private static MeasuredChargeRead blowpipe(String dart, long scales, long darts)
    {
        return MeasuredChargeRead.parseCheckMessage(
            "Darts: " + dart + " x " + darts + ". Scales: " + scales + " (1.0%).");
    }

    private static boolean isMutable(Map<Integer, Long> map)
    {
        try
        {
            map.put(-1, -1L);
            return true;
        }
        catch (UnsupportedOperationException expected)
        {
            return false;
        }
    }
}
