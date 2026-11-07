package com.gpmanager;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Owner 1.3.1: compact amounts are cut off, never rounded up, like the game's stack numbers. */
public class FmtCompactTest
{
    @Test
    public void exactSignedAmountsKeepOneSignAndEveryDigitAtLongBounds()
    {
        assertEquals("−9,223,372,036,854,775,808", Fmt.exactSigned(Long.MIN_VALUE));
        assertEquals("+9,223,372,036,854,775,807", Fmt.exactSigned(Long.MAX_VALUE));
        assertEquals("0", Fmt.exactSigned(0L));
        assertEquals("−1,234", Fmt.exactSigned(-1_234L));
        assertEquals("+1,234", Fmt.exactSigned(1_234L));
    }

    @Test
    public void quantityMarkersUseTheSharedExactFormatter()
    {
        assertEquals("×0", Fmt.times(0L));
        assertEquals("×1,234", Fmt.times(1_234L));
        assertEquals("×1,234", Fmt.times(-1_234L));
        assertEquals("×9,223,372,036,854,775,807", Fmt.times(Long.MAX_VALUE));
        assertEquals("×-9,223,372,036,854,775,808", Fmt.times(Long.MIN_VALUE));
    }

    @Test
    public void fourThreadsKeepTheExactCorpusStable() throws Exception
    {
        long[] values = {0L, 1L, -1L, 1_234L, -1_234L, 1_234_567L, -1_234_567L,
                Long.MAX_VALUE, Long.MIN_VALUE};
        String[] exact = new String[values.length];
        String[] times = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            exact[i] = Fmt.exact(values[i]);
            times[i] = "×" + Fmt.exact(Math.abs(values[i]));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++)
                tasks.add(pool.submit(() -> {
                    for (int round = 0; round < 100; round++)
                        for (int i = 0; i < values.length; i++) {
                            assertEquals("exact " + values[i], exact[i], Fmt.exact(values[i]));
                            assertEquals("times " + values[i], times[i], Fmt.times(values[i]));
                        }
                    return null;
                }));
            for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

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

    @Test public void everyCompactBoundaryAndBothLongBoundsKeepTheirPrecision()
    {
        long[] values={0,999,1000,99_999,100_000,999_999,1_000_000,9_999_999,10_000_000,
            99_999_999,100_000_000,999_999_999,1_000_000_000,9_999_999_999L,10_000_000_000L,
            Long.MAX_VALUE,Long.MIN_VALUE};
        String[] expected={"0","999","1.0k","99.9k","100k","999k","1.00M","9.99M","10.0M",
            "99.9M","100M","999M","1.00B","9.99B","10.0B","9223372036.8B","9223372036.8B"};
        for(int i=0;i<values.length;i++) assertEquals("value "+values[i],expected[i],Fmt.compact(values[i]));
    }

    @Test public void actionLabelsRetainTheirUtf16CharacterContract()
    {
        for(String value:List.of("Fire Strike","Café ١","漢字, Alpha-Beta's"))
            assertEquals(value,ActionLabel.useful(value));
        for(String value:List.of("","Cast","Autocast","Cancel","A_B","A!","A\nB","\ud835\udc00"))
            assertEquals(value,null,ActionLabel.useful(value));
    }

    @Test
    public void fullAndCompactClocksKeepTheirBoundaryFormats()
    {
        long[] times = {Long.MIN_VALUE, -1, 0, 999, 1000, 59999, 60000, 3599999, 3600000, 3661000, Long.MAX_VALUE};
        String[] full = {"0:00", "0:00", "0:00", "0:00", "0:01", "0:59", "1:00", "59:59", "1:00:00", "1:01:01",
            "2562047788015:12:55"};
        String[] compact = {"0m00s", "0m00s", "0m00s", "0m00s", "0m01s", "0m59s", "1m00s", "59m59s", "1h00m", "1h01m",
            "2562047788015h12m"};
        for (var mode : GpManagerConfig.HudTimer.values())
        {
            var builder = new HudBuilder(new GpManagerConfig() {
                @Override public HudTimer hudTimer() { return mode; }
            }, null);
            for (int i = 0; i < times.length; i++)
            {
                assertEquals("full " + times[i], full[i], Fmt.clock(times[i]));
                assertEquals(mode + " " + times[i], mode == GpManagerConfig.HudTimer.Full ? full[i] : compact[i],
                    builder.timer(times[i]));
            }
        }
    }
}
