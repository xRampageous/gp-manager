package com.gpmanager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MeasuredChargeLoadEvidenceTest
{
    private static final int DEATH_RUNE = 560;
    private static final int CHAOS_RUNE = 562;
    private static final int FIRE_RUNE = 554;
    private static final int COINS = 995;
    private static final int ZULRAH_SCALES = 12934;

    @Test
    public void tridentSeasChargeIncreaseUsesVariantSpecificComponentRatios()
    {
        ChargeRead before = trident("seas", "", 10L);
        ChargeRead after = trident("seas", "", 12L);

        Map<Integer, Long> evidence = ChargeRead.exactLoadQuantities(before, after);

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
        Map<Integer, Long> regular = ChargeRead.exactLoadQuantities(
            trident("swamp", "", 4L), trident("swamp", "", 7L));
        Map<Integer, Long> enhanced = ChargeRead.exactLoadQuantities(
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
        Map<Integer, Long> scalesAndDarts = ChargeRead.exactLoadQuantities(
            blowpipe("Adamant dart", 100L, 50L),
            blowpipe("Adamant dart", 450L, 80L));
        Map<Integer, Long> dartsOnly = ChargeRead.exactLoadQuantities(
            blowpipe("Adamant dart", 100L, 50L),
            blowpipe("Adamant dart", 100L, 80L));

        assertEquals(Collections.singletonMap(ZULRAH_SCALES, 350L), scalesAndDarts);
        assertTrue(dartsOnly.isEmpty());
    }

    @Test
    public void noEvidenceForDecreasesSameReadsDifferentVariantsOrUnsupportedVariants()
    {
        assertTrue(ChargeRead.exactLoadQuantities(
            trident("seas", "", 12L), trident("seas", "", 10L)).isEmpty());
        assertTrue(ChargeRead.exactLoadQuantities(
            trident("seas", "", 10L), trident("seas", "", 10L)).isEmpty());
        assertTrue(ChargeRead.exactLoadQuantities(
            trident("seas", "", 10L), trident("swamp", "", 11L)).isEmpty());
        assertTrue(ChargeRead.exactLoadQuantities(
            blowpipe("Adamant dart", 10L, 10L),
            ChargeRead.parseCheckMessage("Darts: Unknown dart x 12. Scales: 12 (1.0%).")).isEmpty());
        assertTrue(ChargeRead.exactLoadQuantities(
            trident("seas", "", 10L),
            trident("seas", " (e)", 12L)).isEmpty());
        assertTrue(ChargeRead.exactLoadQuantities(null, trident("seas", "", 12L)).isEmpty());
    }

    @Test
    public void arithmeticOverflowAndUnsupportedReadsFailClosed()
    {
        ChargeRead baseline = trident("swamp", "", 0L);
        ChargeRead overflowing = ChargeRead.parseCheckMessage(
            "Your Trident of the swamp has 9223372036854775807 charges.");

        assertFalse("The overflowed parsed read must not be bookable", overflowing.bookable);
        assertTrue(ChargeRead.exactLoadQuantities(baseline, overflowing).isEmpty());
        assertTrue(ChargeRead.exactLoadQuantities(
            ChargeRead.parseCheckMessage("Your Trident of the seas has 999999999999999999999999999 charges."),
            trident("seas", "", 1L)).isEmpty());
    }

    private static ChargeRead trident(String type, String enhanced, long charges)
    {
        return ChargeRead.parseCheckMessage(
            "Your Trident of the " + type + enhanced + " has " + charges
                + (charges == 1L ? " charge." : " charges."));
    }

    private static ChargeRead blowpipe(String dart, long scales, long darts)
    {
        return ChargeRead.parseCheckMessage(
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
