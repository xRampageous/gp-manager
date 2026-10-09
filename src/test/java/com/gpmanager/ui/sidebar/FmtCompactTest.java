package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Owner 1.3.1: compact amounts are cut off, never rounded up, like the game's stack numbers. */
public class FmtCompactTest
{
    @Test
    public void amountsAreCutOffNeverRoundedUp()
    {
        assertEquals("999", Fmt.compact(999L));
        assertEquals("1.2k", Fmt.compact(1_299L));
        assertEquals("99.9k", Fmt.compact(99_999L));
        assertEquals("999k", Fmt.compact(999_600L));
        assertEquals("1.99M", Fmt.compact(1_999_999L));
        assertEquals("9.99M", Fmt.compact(9_999_999L));
        assertEquals("99.9M", Fmt.compact(99_999_999L));
        assertEquals("999M", Fmt.compact(999_999_999L));
        assertEquals("1.99B", Fmt.compact(1_999_999_999L));
        assertEquals("12.4B", Fmt.compact(12_499_999_999L));
        assertEquals("a loss reads toward zero too", "\u2212999k", Fmt.signed(-999_600L));
    }
}
