package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Owner 2026-10-01 (F28): a resume shows CALIBRATING for ~2.5s; bookings never wait. */
public class CalibratingCueTest
{
    private static final long NOW = 1_700_000_000_000L;

    private static Am engine()
    {
        return new Am(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
    }

    @Test
    public void aResumeArmsTheCueAndItExpires()
    {
        Am engine = engine();
        engine.rm(NOW);
        assertFalse("a plain start alone arms nothing", engine.calibrating(NOW + 100L));

        engine.adh(NOW + 1_000L, NOW + 1_000L);
        assertTrue(engine.resume(NOW + 2_000L, Ed.IDLE));
        assertTrue("the cue is up right after resume", engine.calibrating(NOW + 2_100L));
        assertFalse("the cue clears after 2.5s", engine.calibrating(NOW + 4_600L));
    }

    @Test
    public void autoStartAlsoArmsTheCue()
    {
        Am engine = engine();
        assertTrue(engine.ait(NOW));
        assertTrue(engine.calibrating(NOW + 100L));
        assertFalse(engine.calibrating(NOW + 2_600L));
    }

    @Test
    public void liveShowsTheWordAndTheHudItsContextLineUntilItExpires()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        engine.adh(NOW + 1_000L, NOW + 1_000L);
        engine.resume(NOW + 2_000L, Ed.IDLE);

        Ca during = Ca.capture(engine, NOW + 2_500L, null);
        assertTrue(during.calibrating);
        assertEquals("CALIBRATING", LivePage.wordOf(during));
        Cp builder = new Cp(new GpManagerConfig() {}, null);
        assertEquals("Calibrating\u2026", builder.update(during, f -> true, NOW + 2_500L).context);

        Ca after = Ca.capture(engine, NOW + 5_000L, null);
        assertFalse(after.calibrating);
        assertEquals("plain running needs no status word", "", LivePage.wordOf(after));
        assertEquals("", builder.update(after, f -> true, NOW + 5_000L).context);
    }
}
