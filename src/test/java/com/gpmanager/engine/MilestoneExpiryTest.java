package com.gpmanager;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.gpmanager.TargetFreshStartTest.*;

public class MilestoneExpiryTest {
    @Test public void reachedMilestoneFadesThenClearsOnlyItself() {
        Engine engine = engine();
        engine.setTargets(10_000L, 60_000L, 10L);
        coins(engine, 20L, 400_000L);
        engine.setMilestone(400_000L);
        engine.tickMilestone(100L);
        HudBuilder hud = new HudBuilder(HudBuilderTest.config(false, 4), null);
        assertEquals("Milestone reached · 400k", hud.update(live(engine, 100L), f -> true, 100L).context);
        assertEquals(255, hud.current.contextColor.getAlpha());
        engine.tickMilestone(7_600L);
        int alpha = hud.update(live(engine, 7_600L), f -> true, 7_600L).contextColor.getAlpha();
        assertTrue("last second fades", alpha > 0 && alpha < 255);
        engine.tickMilestone(8_100L);
        assertNull(engine.freePlay().milestoneGp);
        assertEquals("", hud.update(live(engine, 8_100L), f -> true, 8_100L).context);
        assertEquals(Long.valueOf(10_000L), engine.session().targetGp());
        assertEquals(Long.valueOf(60_000L), engine.session().timeTargetMillis());
        long revision = engine.revision();
        engine.tickMilestone(9_000L);
        assertEquals(revision, engine.revision());
    }

    @Test public void replacingTargetOrFreePlayCancelsTheOldCountdown() {
        Engine engine = engine();
        coins(engine, 10L, 400_000L); engine.setMilestone(400_000L);
        engine.tickMilestone(100L);
        engine.setMilestone(500_000L);
        engine.tickMilestone(8_100L);
        assertEquals(Long.valueOf(500_000L), engine.freePlay().milestoneGp);
        engine.setMilestone(400_000L); engine.tickMilestone(8_200L);
        engine.startFreePlayOver(8_300L); engine.tickMilestone(20_000L);
        assertEquals(Long.valueOf(400_000L), engine.freePlay().milestoneGp);
    }

    @Test public void completionSurvivesANetDipAndDoesNotRequireAVisibleHud() {
        Engine engine = engine();
        coins(engine, 10L, 400_000L); engine.setMilestone(400_000L);
        engine.tickMilestone(100L);
        coins(engine, 1_000L, -10_000L);
        engine.tickMilestone(8_100L);
        assertNull(engine.freePlay().milestoneGp);
    }

    @Test public void aGrindCannotConsumeFreePlaysMilestone() {
        Engine engine = engine();
        coins(engine, 10L, 400_000L); engine.setMilestone(400_000L);
        engine.startCustom("Vorkath", 20L); engine.tickMilestone(30L);
        engine.tickMilestone(9_000L);
        assertEquals(Long.valueOf(400_000L), engine.freePlay().milestoneGp);
    }

    @Test public void aRestoredProfilesFutureTargetCannotInheritThePreviousCountdown() {
        JsonCodec.bind(new com.google.gson.Gson());
        Engine engine = engine();
        coins(engine, 10L, 400_000L); engine.setMilestone(400_000L); engine.tickMilestone(100L);
        Engine other = engine(); other.setMilestone(500_000L);
        engine.restoreProfile("other", other.savedState(), 1_000L);
        assertEquals(0L, live(engine, 1_000L).targetUntil);
        HudBuilder hud = new HudBuilder(HudBuilderTest.config(false, 4), null);
        assertEquals("", hud.update(live(engine, 1_000L), f -> true, 1_000L).context);
        engine.tickMilestone(10_000L);
        assertEquals(Long.valueOf(500_000L), engine.freePlay().milestoneGp);
    }
}
