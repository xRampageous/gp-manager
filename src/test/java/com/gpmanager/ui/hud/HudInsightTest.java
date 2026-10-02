package com.gpmanager;

import java.awt.Rectangle;
import net.runelite.api.Point;
import org.junit.Test;

import static com.gpmanager.HudBuilderTest.NOW;
import static com.gpmanager.HudBuilderTest.config;
import static com.gpmanager.HudBuilderTest.engine;
import static com.gpmanager.HudBuilderTest.grind;
import static com.gpmanager.HudTrayTest.flow;
import static com.gpmanager.HudTrayTest.receipt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Per kill, losing money lately, your own average, projected finish, best drop and the End card. */
public class HudInsightTest
{
    /** Owner 1.1: a running streak titles HUD+ with its kill count alone, never the NPC or Grind. */
    @Test
    public void aStreakTitlesTheHudWithItsKillCount()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.update(grind(10_000L, null), f -> true, NOW - 1L);
        builder.interaction("Greater Nechryael", true);
        for (int i = 0; i < 3; i++)
        {
            builder.tray().kill("Greater Nechryael", NOW - 1L + i, false);
        }
        assertEquals("KC: 3", builder.update(grind(100_000L, null), f -> true, NOW).title);
    }

    @Test
    public void aStaleTargetDropsTheKillCountTitle()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.update(grind(10_000L, null), f -> true, NOW - 1L);
        builder.interaction("Guard", true);
        for (int i = 0; i < 2; i++)
        {
            builder.tray().kill("Guard", NOW - 1L + i, false);
        }
        builder.interaction("", false);
        String title = builder.update(grind(100_000L, null), f -> true, NOW).title;
        assertEquals("a stale streak must not add its count", "Vorkath", title);
    }

    @Test
    public void killsShowNetPerKillAfterSupplies()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.update(grind(10_000L, null), f -> true, NOW - 1L);
        builder.interaction("Vorkath", true);
        for (int i = 0; i < 3; i++)
        {
            builder.tray().kill("Vorkath", NOW - 1L + i, false);
        }
        Cb s = builder.update(grind(100_000L, null), f -> true, NOW);
        assertEquals("KC: 3", s.title);
        assertEquals("kills leave the context line to other news", "", s.context);
        assertEquals("the tray heading carries the streak's own Net per kill", "Vorkath · +30.0k/kill", s.trayLabel);
    }

    /** Owner 1.1: a merged mixed streak measures its Net per kill from where it first began. */
    @Test
    public void aMergedStreakMeasuresPerKillFromItsFirstStart()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.update(grind(10_000L, null), f -> true, NOW - 4L);
        builder.tray().kill("Guard", NOW - 4L, false);
        builder.update(grind(20_000L, null), f -> true, NOW - 3L);
        builder.tray().kill("Man", NOW - 3L, false);
        builder.update(grind(30_000L, null), f -> true, NOW - 2L);
        builder.tray().kill("Guard", NOW - 2L, false);
        Cb s = builder.update(grind(40_000L, null), f -> true, NOW);
        assertEquals("Guard & Man · +10.0k/kill", s.trayLabel);
    }

    /** Owner 2026-10-01: an ended streak says so with its final count. */
    @Test
    public void anEndedStreakShowsItsFinalCountAndTheFolioKeepsIt()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.update(grind(10_000L, null), f -> true, NOW - 1L);
        builder.interaction("Guard", true);
        for (int i = 0; i < 12; i++)
        {
            builder.tray().kill("Guard", NOW - 1L + i, false);
        }
        assertEquals("KC: 12", builder.update(grind(100_000L, null), f -> true, NOW).title);

        long endedAt = NOW + 61_000L;
        Cb ended = builder.update(grind(100_000L, null), f -> true, endedAt);
        assertEquals("Streak ended · Guard ×12", ended.context);

        Cb later = builder.update(grind(100_000L, null), f -> true, endedAt + 5_000L);
        assertEquals("the moment is transient", "", later.context);

        builder.interaction("Hill Giant", true);
        builder.tray().kill("Hill Giant", endedAt + 6_000L, false);
        Cb next = builder.update(grind(100_000L, null), f -> true, endedAt + 6_000L);
    }


    @Test
    public void aFreshSessionWithNothingBookedShowsADashNotAZero()
    {
        Am fresh = engine();
        fresh.rm(NOW);
        Cb s = new Cp(config(false, 4), null)
            .update(Ca.capture(fresh, NOW + 1_000L, null), f -> true, NOW + 1_000L);
        assertEquals("—", s.net);
    }

    @Test
    public void startingAGrindFromFreePlayWipesTheTrayAndNamesTheList()
    {
        Am free = engine();
        free.rm(NOW);
        Cp builder = new Cp(config(false, 4), null);
        builder.update(Ca.capture(free, NOW, null), f -> true, NOW);
        builder.tray().booked(receipt(NOW + 1L, Ai.LOOT, flow(1511, "Logs", 11L, 23)), NOW + 1L, false);
        Cb freePlay = builder.update(Ca.capture(free, NOW + 2L, null), f -> true, NOW + 2L);

        Cb grinding = builder.update(grind(0L, null), f -> true, NOW + 3L);
        assertTrue("a new Grind starts with a clean tray", grinding.rows.isEmpty());
        builder.tray().booked(receipt(NOW + 4L, Ai.LOOT, flow(1511, "Logs", 1L, 23)), NOW + 4L, false);
    }

    @Test
    public void anotherRunWithTheSameNameStartsWithACleanTrayAndMoments()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        Cp builder = new Cp(config(true, 4), null);
        builder.update(Ca.capture(engine, NOW, null), f -> true, NOW);
        engine.getActiveSession().kf(receipt(NOW + 1L, Ai.LOOT,
            flow(536, "Dragon bones", 1L, 1_000_000)), 2_000);
        builder.tray().booked(receipt(NOW + 1L, Ai.LOOT, flow(536, "Dragon bones", 1L, 1_000_000)),
            NOW + 1L, false);
        Cb first = builder.update(Ca.capture(engine, NOW + 2L, null), f -> true,
            NOW + 2L);
        assertEquals(1, first.rows.size());

        // The UI sees no intermediate Free play snapshot between the two same-name runs.
        engine.sx(NOW + 3L);
        engine.ajl("Vorkath", Cx.GENERAL, NOW + 4L);
        Cb next = builder.update(Ca.capture(engine, NOW + 5L, null), f -> true,
            NOW + 5L);
        assertTrue("the old run's loot must not follow its name", next.rows.isEmpty());
        assertEquals("the old run's moment is gone", "", next.context);
    }
}
