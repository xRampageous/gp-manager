package com.gpmanager;

import org.junit.Test;
import static com.gpmanager.TargetFreshStartTest.coins;
import static com.gpmanager.TargetFreshStartTest.live;
import static com.gpmanager.MilestoneTest.onEdt;
import static org.junit.Assert.*;

/** Milestones reuse the HUD context; MilestoneExpiryTest covers the owner-thread countdown. */
public class MilestoneHudTest {
    @Test public void aClosedSidebarStillPublishesTheReachedMilestone() throws Exception {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ensureSession(now - 60_000L);
        engine.setMilestone(100_000L);
        coins(engine, now - 10_000L, 90_000L);
        HudBuilder hud = new HudBuilder(HudBuilderTest.config(false, 4), null);
        SidebarPanel panel = onEdt(() -> new SidebarPanel(engine, PresentationLifecycleTest.config(), null, null, null));
        onEdt(() -> { panel.active = false; panel.bindHud(hud); panel.refreshNow(); return null; });
        assertEquals("", hud.current().context);
        coins(engine, now - 5_000L, 10_000L);
        onEdt(() -> { panel.refreshNow(); return null; });
        assertEquals("Milestone reached · 100k", hud.current().context);
        assertEquals(Kit.Tone.GAIN.color, hud.current().contextColor);
        assertFalse("a milestone adds no progress bar", hud.current().progress >= 0d);
    }

    @Test public void changingOwnersTargetsAndNetCannotLeaveAStaleReachedLine() {
        Engine engine = TargetFreshStartTest.engine();
        coins(engine, 5L, 120_000L);
        engine.setMilestone(100_000L);
        HudBuilder hud = new HudBuilder(HudBuilderTest.config(false, 4), null);
        assertEquals("Milestone reached · 100k", hud.update(live(engine, 10L), f -> true, 10L).context);
        engine.setMilestone(200_000L);
        assertEquals("", hud.update(live(engine, 20L), f -> true, 20L).context);
        engine.setMilestone(100_000L);
        coins(engine, 30L, -30_000L);
        assertEquals("", hud.update(live(engine, 40L), f -> true, 40L).context);
        coins(engine, 50L, 30_000L);
        assertEquals("Milestone reached · 100k", hud.update(live(engine, 60L), f -> true, 60L).context);
        engine.startCustom("Vorkath", 70L);
        assertEquals("", hud.update(live(engine, 80L), f -> true, 80L).context);
        hud.resetPresentation();
        engine.resetData(90L); engine.ensureSession(90L);
        assertEquals("", hud.update(live(engine, 100L), f -> true, 100L).context);
    }

    @Test public void reviewReclaimAndHudVisibilityKeepTheirPriority() {
        Engine engine = TargetFreshStartTest.engine();
        coins(engine, 5L, 100_000L); engine.setMilestone(100_000L);
        LiveSnapshot snapshot = live(engine, 10L);
        HudBuilder hud = new HudBuilder(HudBuilderTest.config(false, 4), null);
        snapshot.reviewCount = 1; snapshot.reclaimItems = 2;
        assertEquals("(!) 1 item needs review", hud.contextLine(snapshot));
        snapshot.reviewCount = 0;
        assertEquals("Reclaim waiting · 2 items", hud.contextLine(snapshot));
        snapshot.reclaimItems = 0;
        assertEquals("Milestone reached · 100k", hud.contextLine(snapshot));
        HudBuilder hidden = new HudBuilder(new GpManagerConfig() {
            public boolean showHud() { return false; }
        }, null);
        assertFalse(hidden.update(snapshot, f -> true, 10L).visible);
    }

    @Test public void absentOrInvalidMilestonesNeverReadAsReached() {
        LiveSnapshot snapshot = live(TargetFreshStartTest.engine(), 10L);
        for (Long target : new Long[] {null, 0L, -1L}) {
            snapshot.milestone = target;
            assertFalse("invalid milestone " + target, snapshot.milestoneReached());
        }
    }
}
