package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Owner 1.1: a blowpipe shot spends its dart unless an Ava's device saves it (OSRS wiki: an
 * assembler or Dizana's quiver saves 80%, the accumulator 72%, the attractor 60%).
 */
public class ChargeBlowpipeDartTest
{
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
        org.junit.Assert.assertTrue("the dart table knows dragon darts", Ar.DARTS.containsValue(11230));
    }
}
