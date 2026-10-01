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
    private static boolean folioHas(HudSnapshot s, String label)
    {
        return s.folio.stream().anyMatch(line -> line.label.startsWith(label));
    }

    /** Owner 2026-09-28: "Greater Nechryael" shortens to "G. Nechryael"; its count still shows, spawns aside. */
    @Test
    public void aShortenedNameKeepsItsKillCountWhileASpawnIsHit()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
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
        HudBuilder builder = new HudBuilder(config(true, 4), null);
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
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.update(grind(10_000L, null), f -> true, null, NOW - 1L);
        builder.interaction("Vorkath");
        for (int i = 0; i < 3; i++)
        {
            builder.tray().kill("Vorkath", NOW - 1L + i, false);
        }
        HudSnapshot s = builder.update(grind(100_000L, null), f -> true, null, NOW);
        assertEquals("the kill count joins the name", "Vorkath ×3", s.title);
        assertEquals("kills leave the context line to other news", "", s.context);
        assertTrue("the streak's own Net per kill, not the session's", s.folio.stream()
            .anyMatch(line -> line.label.startsWith("Per kill") && "+30.0k · 3 kills".equals(line.value)));
    }

    /** Owner 2026-10-01: an ended streak says so with its final count, and the folio keeps it. */
    @Test
    public void anEndedStreakShowsItsFinalCountAndTheFolioKeepsIt()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.update(grind(10_000L, null), f -> true, null, NOW - 1L);
        builder.interaction("Guard");
        for (int i = 0; i < 12; i++)
        {
            builder.tray().kill("Guard", NOW - 1L + i, false);
        }
        assertEquals("Guard ×12", builder.update(grind(100_000L, null), f -> true, null, NOW).title);

        long endedAt = NOW + 61_000L;
        HudSnapshot ended = builder.update(grind(100_000L, null), f -> true, null, endedAt);
        assertEquals("Streak ended · Guard ×12", ended.context);
        assertTrue("the folio keeps the final count", ended.folio.stream()
            .anyMatch(line -> "Last streak".equals(line.label) && "Guard ×12".equals(line.value)));

        HudSnapshot later = builder.update(grind(100_000L, null), f -> true, null, endedAt + 5_000L);
        assertEquals("the moment is transient", "", later.context);
        assertTrue("the folio still holds the last streak", later.folio.stream()
            .anyMatch(line -> "Last streak".equals(line.label)));

        builder.interaction("Hill Giant");
        builder.tray().kill("Hill Giant", endedAt + 6_000L, false);
        HudSnapshot next = builder.update(grind(100_000L, null), f -> true, null, endedAt + 6_000L);
        assertFalse("the next streak refreshes the tray", next.folio.stream()
            .anyMatch(line -> "Last streak".equals(line.label)));
    }


    @Test
    public void theDetailPanelComparesWithYourAverageAndProjectsTheTimeTarget()
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW);
        engine.getActiveSession().addTransaction(receipt(NOW + 1_000L, TransactionType.LOOT,
            flow(536, "Dragon bones", 1L, 1_000_000)), 2_000);
        engine.getActiveSession().setActiveTimeTargetMillis(3_600_000L);
        long at = NOW + 20 * 60_000L;
        HudSnapshot s = new HudBuilder(config(true, 4), null).update(LiveSnapshot.capture(engine, at, null),
            f -> true, new long[] {Long.MIN_VALUE, Long.MIN_VALUE, 1_000_000L}, at);
        assertTrue("the rate is established by now", s.rate.endsWith("/h"));
        assertTrue(folioHas(s, "vs your average"));
        assertTrue(folioHas(s, "At 1h 00m"));
    }

    @Test
    public void endingAGrindShowsTheRecapCardWithTheBestDropWithoutHover()
    {
        GpManagerConfig config = config(true, 4);
        HudBuilder builder = new HudBuilder(config, null);
        builder.update(grind(150_000L, null), f -> true, null, NOW - 1L);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(11286, "Draconic visage", 1L, 2_500_000)), NOW, false);
        HudSnapshot running = builder.update(grind(150_000L, null), f -> true, null, NOW + 10L);
        assertTrue(folioHas(running, "Best drop"));

        HudSnapshot ended = builder.update(LiveSnapshot.capture(engine(), NOW + 20L, null), f -> true, null,
            NOW + 20L);
        assertTrue("the card keeps HUD+ visible", ended.visible);
        assertEquals("VORKATH · ENDED", ended.recap.get(0).label);
        assertTrue(ended.recap.stream().anyMatch(line -> "Best drop".equals(line.label)
            && line.value.startsWith("Draconic visage")));

        HudFolio folio = new HudFolio(builder, new HudOverlay(builder, config), config, () -> null, () -> 800, () -> 600);
        Rectangle hud = new Rectangle(10, 20, 150, 60);
        assertNotNull("the card needs no hover", folio.place(ended, hud, new Point(500, 500), 800, 600, HudFolio.WIDTH, 100));
        HudSnapshot later = builder.update(LiveSnapshot.capture(engine(), NOW + 20L, null), f -> true, null,
            NOW + 20L + HudBuilder.RECAP_MILLIS);
        assertTrue(later.recap.isEmpty());
        assertNull(folio.place(later, hud, new Point(500, 500), 800, 600, HudFolio.WIDTH, 100));
    }

    @Test
    public void aFreshSessionWithNothingBookedShowsADashNotAZero()
    {
        Engine fresh = engine();
        fresh.ensureSession(NOW);
        HudSnapshot s = new HudBuilder(config(false, 4), null)
            .update(LiveSnapshot.capture(fresh, NOW + 1_000L, null), f -> true, null, NOW + 1_000L);
        assertEquals("—", s.net);
    }

    @Test
    public void aLargeCompletedGrindKeepsTheCorrectRateSignAndValue()
    {
        for (long net : new long[] {3_000_000_000_000L, -3_000_000_000_000L})
        {
            HudSnapshot.Line rate = HudBuilder.recap("Vorkath", net, 3_600_000L, "", null).stream()
                .filter(line -> "GP/h".equals(line.label)).findFirst().get();
            assertEquals("a one-hour recap agrees with its canonical Net", Fmt.rate(net) + "/h", rate.value);
        }
    }

    @Test
    public void aLongTimeTargetDoesNotTurnAProfitableProjectionNegative()
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW);
        engine.getActiveSession().addTransaction(receipt(NOW + 1_000L, TransactionType.LOOT,
            flow(20997, "Twisted bow", 1L, 1_000_000_000)), 2_000);
        engine.getActiveSession().setActiveTimeTargetMillis(7L * 24L * 3_600_000L);
        long at = NOW + 61_000L;
        LiveSnapshot live = LiveSnapshot.capture(engine, at, null);
        assertTrue("a billion in 61 seconds is a huge positive rate", live.gpPerHour > 50_000_000_000L);
        HudSnapshot hud = new HudBuilder(config(true, 4), null).update(live, f -> true, null, at);
        HudSnapshot.Line projection = hud.folio.stream().filter(line -> line.label.startsWith("At "))
            .findFirst().get();
        assertTrue("the projection stays positive: " + projection.value, projection.value.startsWith("~+"));
    }

    @Test
    public void startingAGrindFromFreePlayWipesTheTrayAndNamesTheList()
    {
        Engine free = engine();
        free.ensureSession(NOW);
        HudBuilder builder = new HudBuilder(config(false, 4), null);
        builder.update(LiveSnapshot.capture(free, NOW, null), f -> true, null, NOW);
        builder.tray().booked(receipt(NOW + 1L, TransactionType.LOOT, flow(1511, "Logs", 11L, 23)), NOW + 1L, false);
        HudSnapshot freePlay = builder.update(LiveSnapshot.capture(free, NOW + 2L, null), f -> true, null,
            NOW + 2L);
        assertTrue(folioHas(freePlay, "RECENT"));

        HudSnapshot grinding = builder.update(grind(0L, null), f -> true, null, NOW + 3L);
        assertTrue("a new Grind starts with a clean tray", grinding.rows.isEmpty());
        builder.tray().booked(receipt(NOW + 4L, TransactionType.LOOT, flow(1511, "Logs", 1L, 23)), NOW + 4L, false);
        assertTrue(folioHas(builder.update(grind(0L, null), f -> true, null, NOW + 5L), "THIS STREAK"));
    }

    @Test
    public void anotherRunWithTheSameNameStartsWithACleanTrayAndMoments()
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW);
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.update(LiveSnapshot.capture(engine, NOW, null), f -> true, new long[] {1L, 1L}, NOW);
        engine.getActiveSession().addTransaction(receipt(NOW + 1L, TransactionType.LOOT,
            flow(536, "Dragon bones", 1L, 1_000_000)), 2_000);
        builder.tray().booked(receipt(NOW + 1L, TransactionType.LOOT, flow(536, "Dragon bones", 1L, 1_000_000)),
            NOW + 1L, false);
        HudSnapshot first = builder.update(LiveSnapshot.capture(engine, NOW + 2L, null), f -> true,
            new long[] {1L, 1L}, NOW + 2L);
        assertEquals(1, first.rows.size());
        assertTrue(first.context.startsWith("New best Net"));

        // The UI sees no intermediate Free play snapshot between the two same-name runs.
        engine.finishCustomSession(NOW + 3L);
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW + 4L);
        HudSnapshot next = builder.update(LiveSnapshot.capture(engine, NOW + 5L, null), f -> true,
            new long[] {1L, 1L}, NOW + 5L);
        assertTrue("the old run's loot must not follow its name", next.rows.isEmpty());
        assertEquals("the old run's best-drop detail must not survive", "", builder.tray().bestDrop());
        assertEquals("the old run's moment is gone", "", next.context);
        engine.getActiveSession().addTransaction(receipt(NOW + 6L, TransactionType.LOOT,
            flow(536, "Dragon bones", 1L, 2_000_000)), 2_000);
        HudSnapshot best = builder.update(LiveSnapshot.capture(engine, NOW + 7L, null), f -> true,
            new long[] {1L, 1L}, NOW + 7L);
        assertTrue("the new run may set its own best", best.context.startsWith("New best Net"));
    }
}
