package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Review 2026-10-09: each compact band ends where rounding would carry into the next. */
public class FmtCompactTest
{
    @Test
    public void roundingNeverShowsAThousandOfTheSmallerUnit()
    {
        assertEquals("99.9k", Fmt.compact(99_949L));
        assertEquals("100k", Fmt.compact(99_960L));
        assertEquals("999k", Fmt.compact(999_499L));
        assertEquals("1.00M", Fmt.compact(999_600L));
        assertEquals("9.99M", Fmt.compact(9_994_999L));
        assertEquals("10.0M", Fmt.compact(9_996_000L));
        assertEquals("100M", Fmt.compact(99_960_000L));
        assertEquals("1.00B", Fmt.compact(999_600_000L));
        assertEquals("10.0B", Fmt.compact(9_996_000_000L));
        assertEquals("−1.00M", Fmt.signed(-999_600L));
    }
}
