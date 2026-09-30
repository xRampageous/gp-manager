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
    private static boolean folioHas(Cb s, String label)
    {
        return s.folio.stream().anyMatch(line -> line.label.startsWith(label));
    }

    /** Owner 2026-09-28: "Greater Nechryael" shortens to "G. Nechryael"; its count still shows, spawns aside. */
    @Test
    public void aShortenedNameKeepsItsKillCountWhileASpawnIsHit()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.update(grind(10_000L, null), f -> true, null, NOW - 1L);
        builder.interaction("Greater Nechryael");
        for (int i = 0; i < 3; i++)
        {
            builder.tray().engage("Greater Nechryael", false);
            builder.tray().kill("Greater Nechryael", NOW - 1L + i, false);
        }
        assertEquals("G. Nechryael ×3", builder.update(grind(100_000L, null), f -> true, null, NOW).title);
    }

    @Test
    public void aStaleTargetDropsTheKillCountTitle()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.update(grind(10_000L, null), f -> true, null, NOW - 1L);
        builder.interaction("Guard");
        for (int i = 0; i < 2; i++)
        {
            builder.tray().engage("Guard", false);
            builder.tray().kill("Guard", NOW - 1L + i, false);
        }
        builder.interaction("");
        String title = builder.update(grind(100_000L, null), f -> true, null, NOW).title;
        assertFalse("a stale streak must not title the header", title.startsWith("Guard"));
    }

    @Test
    public void killsShowNetPerKillAfterSupplies()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.update(grind(10_000L, null), f -> true, null, NOW - 1L);
        builder.interaction("Vorkath");
        for (int i = 0; i < 3; i++)
        {
            builder.tray().kill("Vorkath", NOW - 1L + i, false);
        }
        Cb s = builder.update(grind(100_000L, null), f -> true, null, NOW);
        assertEquals("the kill count joins the name", "Vorkath ×3", s.title);
        assertEquals("kills leave the context line to other news", "", s.context);
        assertTrue("the streak's own Net per kill, not the session's", s.folio.stream()
            .anyMatch(line -> line.label.startsWith("Per kill") && "+30.0k · 3 kills".equals(line.value)));
    }

    /** Owner 2026-10-01: an ended streak says so with its final count, and the folio keeps it. */
    @Test
    public void anEndedStreakShowsItsFinalCountAndTheFolioKeepsIt()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.update(grind(10_000L, null), f -> true, null, NOW - 1L);
        builder.interaction("Guard");
        for (int i = 0; i < 12; i++)
        {
            builder.tray().kill("Guard", NOW - 1L + i, false);
        }
        assertEquals("Guard ×12", builder.update(grind(100_000L, null), f -> true, null, NOW).title);

        long endedAt = NOW + 61_000L;
        Cb ended = builder.update(grind(100_000L, null), f -> true, null, endedAt);
        assertEquals("Streak ended · Guard ×12", ended.context);
        assertTrue("the folio keeps the final count", ended.folio.stream()
            .anyMatch(line -> "Last streak".equals(line.label) && "Guard ×12".equals(line.value)));

        Cb later = builder.update(grind(100_000L, null), f -> true, null, endedAt + 5_000L);
        assertEquals("the moment is transient", "", later.context);
        assertTrue("the folio still holds the last streak", later.folio.stream()
            .anyMatch(line -> "Last streak".equals(line.label)));

        builder.interaction("Hill Giant");
        builder.tray().kill("Hill Giant", endedAt + 6_000L, false);
        Cb next = builder.update(grind(100_000L, null), f -> true, null, endedAt + 6_000L);
        assertFalse("the next streak refreshes the tray", next.folio.stream()
            .anyMatch(line -> "Last streak".equals(line.label)));
    }

    @Test
    public void aProfitableRunLosingMoneyLatelySaysSo()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        engine.getActiveSession().kf(receipt(NOW + 1_000L, Ai.LOOT,
            flow(536, "Dragon bones", 1L, 200_000)), 2_000);
        Cp builder = new Cp(config(true, 4), null);
        builder.update(Ca.capture(engine, NOW + 60_000L, null), f -> true, null, NOW + 60_000L);

        engine.getActiveSession().kf(receipt(NOW + 120_000L, Ai.CONSUMPTION,
            flow(385, "Shark", -50L, 1_000)), 2_000);
        long later = NOW + 660_000L;
        Cb s = builder.update(Ca.capture(engine, later, null), f -> true, null, later);
        assertEquals("Last 10m: −50.0k", s.context);
    }

    @Test
    public void theDetailPanelComparesWithYourAverageAndProjectsTheTimeTarget()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        engine.getActiveSession().kf(receipt(NOW + 1_000L, Ai.LOOT,
            flow(536, "Dragon bones", 1L, 1_000_000)), 2_000);
        engine.getActiveSession().setActiveTimeTargetMillis(3_600_000L);
        long at = NOW + 20 * 60_000L;
        Cb s = new Cp(config(true, 4), null).update(Ca.capture(engine, at, null),
            f -> true, new long[] {Long.MIN_VALUE, Long.MIN_VALUE, 1_000_000L}, at);
        assertTrue("the rate is established by now", s.rate.endsWith("/h"));
        assertTrue(folioHas(s, "vs your average"));
        assertTrue(folioHas(s, "At 1h 00m"));
    }

    @Test
    public void endingAGrindShowsTheRecapCardWithTheBestDropWithoutHover()
    {
        GpManagerConfig config = config(true, 4);
        Cp builder = new Cp(config, null);
        builder.update(grind(150_000L, null), f -> true, null, NOW - 1L);
        builder.tray().booked(receipt(NOW, Ai.LOOT, flow(11286, "Draconic visage", 1L, 2_500_000)), NOW, false);
        Cb running = builder.update(grind(150_000L, null), f -> true, null, NOW + 10L);
        assertTrue(folioHas(running, "Best drop"));

        Cb ended = builder.update(Ca.capture(engine(), NOW + 20L, null), f -> true, null,
            NOW + 20L);
        assertTrue("the card keeps HUD+ visible", ended.visible);
        assertEquals("VORKATH · ENDED", ended.recap.get(0).label);
        assertTrue(ended.recap.stream().anyMatch(line -> "Best drop".equals(line.label)
            && line.value.startsWith("Draconic visage")));

        HudFolio folio = new HudFolio(builder, new De(builder, config), config, () -> null, () -> 800, () -> 600);
        Rectangle hud = new Rectangle(10, 20, 150, 60);
        assertNotNull("the card needs no hover", folio.place(ended, hud, new Point(500, 500), 800, 600, HudFolio.WIDTH, 100));
        Cb later = builder.update(Ca.capture(engine(), NOW + 20L, null), f -> true, null,
            NOW + 20L + Cp.RECAP_MILLIS);
        assertTrue(later.recap.isEmpty());
        assertNull(folio.place(later, hud, new Point(500, 500), 800, 600, HudFolio.WIDTH, 100));
    }

    @Test
    public void aFreshSessionWithNothingBookedShowsADashNotAZero()
    {
        Am fresh = engine();
        fresh.rm(NOW);
        Cb s = new Cp(config(false, 4), null)
            .update(Ca.capture(fresh, NOW + 1_000L, null), f -> true, null, NOW + 1_000L);
        assertEquals("—", s.net);
    }

    @Test
    public void aLargeCompletedGrindKeepsTheCorrectRateSignAndValue()
    {
        for (long net : new long[] {3_000_000_000_000L, -3_000_000_000_000L})
        {
            Cb.Line rate = Cp.recap("Vorkath", net, 3_600_000L, "", null).stream()
                .filter(line -> "GP/h".equals(line.label)).findFirst().get();
            assertEquals("a one-hour recap agrees with its canonical Net", Fmt.rate(net) + "/h", rate.value);
        }
    }

    @Test
    public void aLongTimeTargetDoesNotTurnAProfitableProjectionNegative()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        engine.getActiveSession().kf(receipt(NOW + 1_000L, Ai.LOOT,
            flow(20997, "Twisted bow", 1L, 1_000_000_000)), 2_000);
        engine.getActiveSession().setActiveTimeTargetMillis(7L * 24L * 3_600_000L);
        long at = NOW + 61_000L;
        Ca live = Ca.capture(engine, at, null);
        assertEquals(60_000_000_000L, live.gpPerHour);
        Cb hud = new Cp(config(true, 4), null).update(live, f -> true, null, at);
        Cb.Line projection = hud.folio.stream().filter(line -> line.label.startsWith("At "))
            .findFirst().get();
        assertEquals("one billion booked and 60b/hour through the remaining active time",
            "~" + Fmt.signed(10_079_983_333_333L), projection.value);
    }

    @Test
    public void startingAGrindFromFreePlayWipesTheTrayAndNamesTheList()
    {
        Am free = engine();
        free.rm(NOW);
        Cp builder = new Cp(config(false, 4), null);
        builder.update(Ca.capture(free, NOW, null), f -> true, null, NOW);
        builder.tray().booked(receipt(NOW + 1L, Ai.LOOT, flow(1511, "Logs", 11L, 23)), NOW + 1L, false);
        Cb freePlay = builder.update(Ca.capture(free, NOW + 2L, null), f -> true, null,
            NOW + 2L);
        assertTrue(folioHas(freePlay, "RECENT"));

        Cb grinding = builder.update(grind(0L, null), f -> true, null, NOW + 3L);
        assertTrue("a new Grind starts with a clean tray", grinding.rows.isEmpty());
        builder.tray().booked(receipt(NOW + 4L, Ai.LOOT, flow(1511, "Logs", 1L, 23)), NOW + 4L, false);
        assertTrue(folioHas(builder.update(grind(0L, null), f -> true, null, NOW + 5L), "THIS STREAK"));
    }

    @Test
    public void anotherRunWithTheSameNameStartsWithACleanTrayAndMoments()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        Cp builder = new Cp(config(true, 4), null);
        builder.update(Ca.capture(engine, NOW, null), f -> true, new long[] {1L, 1L}, NOW);
        engine.getActiveSession().kf(receipt(NOW + 1L, Ai.LOOT,
            flow(536, "Dragon bones", 1L, 1_000_000)), 2_000);
        builder.tray().booked(receipt(NOW + 1L, Ai.LOOT, flow(536, "Dragon bones", 1L, 1_000_000)),
            NOW + 1L, false);
        Cb first = builder.update(Ca.capture(engine, NOW + 2L, null), f -> true,
            new long[] {1L, 1L}, NOW + 2L);
        assertEquals(1, first.rows.size());
        assertTrue(first.context.startsWith("New best Net"));

        // The UI sees no intermediate Free play snapshot between the two same-name runs.
        engine.sx(NOW + 3L);
        engine.ajl("Vorkath", Cx.GENERAL, NOW + 4L);
        Cb next = builder.update(Ca.capture(engine, NOW + 5L, null), f -> true,
            new long[] {1L, 1L}, NOW + 5L);
        assertTrue("the old run's loot must not follow its name", next.rows.isEmpty());
        assertEquals("the old run's best-drop detail must not survive", "", builder.tray().bestDrop());
        assertEquals("the old run's moment is gone", "", next.context);
        engine.getActiveSession().kf(receipt(NOW + 6L, Ai.LOOT,
            flow(536, "Dragon bones", 1L, 2_000_000)), 2_000);
        Cb best = builder.update(Ca.capture(engine, NOW + 7L, null), f -> true,
            new long[] {1L, 1L}, NOW + 7L);
        assertTrue("the new run may set its own best", best.context.startsWith("New best Net"));
    }
}
