package com.gpmanager;

import org.junit.Test;

import static com.gpmanager.HudBuilderTest.NOW;
import static com.gpmanager.HudBuilderTest.config;
import static com.gpmanager.HudBuilderTest.engine;
import static com.gpmanager.HudBuilderTest.grind;
import static com.gpmanager.HudTrayTest.flow;
import static com.gpmanager.HudTrayTest.receipt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A factory reset leaves no HUD+ trace of the profile it replaced. */
public class HudResetPresentationTest
{
    @Test
    public void aFactoryResetStartsTheHudTripClean()
    {
        GpManagerConfig config = config(false, 4);
        HudBuilder builder = new HudBuilder(config, null);
        builder.update(grind(150_000L, null), f -> true, NOW);
        builder.tray.booked(receipt(NOW, TransactionType.LOOT, flow(11286, "Draconic visage", 1L, 2_500_000)), NOW, false, NOW);
        for (int k = 0; k < 3; k++)
        {
            builder.tray.kill("Vorkath", NOW + k, false);
        }
        builder.update(grind(50_000L, null), f -> true, NOW + 600_000L);
        assertTrue(!builder.tray.entries.isEmpty());

        builder.resetPresentation();

        Engine free = engine();
        free.ensureSession(NOW + 700_000L);
        HudFrame after = builder.update(LiveSnapshot.capture(free, NOW + 700_000L, null), f -> true,
            NOW + 700_000L);
        assertTrue("the reset tray is empty", after.rows.isEmpty());
        assertEquals("no trip chip survives", "", after.trip);
        assertEquals("no stale context line", "", after.context);
        assertFalse("no per-kill heading from before the reset", after.trayLabel.contains("/kill"));
        assertFalse(!builder.tray.entries.isEmpty());
    }

    @Test
    public void aFactoryResetHidesHudLikeANewInstallUntilPlay()
    {
        GpManagerConfig config = config(true, 4);
        HudBuilder builder = new HudBuilder(config, null);
        Engine engine = engine();
        engine.ensureSession(NOW);
        builder.tray.booked(receipt(NOW, TransactionType.LOOT, flow(11286, "Draconic visage", 1L, 2_500_000)), NOW, false, NOW);
        assertTrue("the running profile shows",
            builder.update(LiveSnapshot.capture(engine, NOW + 10L, null), f -> true, NOW + 10L).visible);

        builder.resetPresentation();
        engine.resetData(NOW + 20L);
        assertFalse("after a reset HUD+ waits, as on a new install",
            builder.update(LiveSnapshot.capture(engine, NOW + 20L, null), f -> true, NOW + 20L).visible);

        engine.ensureSession(NOW + 30L);
        builder.tray.booked(receipt(NOW + 30L, TransactionType.LOOT, flow(536, "Dragon bones", 1L, 2_000)), NOW + 30L, false, NOW + 30L);
        HudFrame back = builder.update(LiveSnapshot.capture(engine, NOW + 30L, null), f -> true, NOW + 30L);
        assertTrue("play brings it back", back.visible);
        assertEquals("only the new loot", 1, back.rows.size());
    }
}
