package com.gpmanager;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Point;
import org.junit.Test;

import static com.gpmanager.HudTrayTest.flow;
import static com.gpmanager.HudTrayTest.receipt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HudBuilderTest
{
    static final long NOW = 1_700_000_000_000L;

    static GpManagerConfig config(boolean hideWhenIdle, int rows)
    {
        return new GpManagerConfig()
        {
            @Override
            public boolean hudHideWhenIdle()
            {
                return hideWhenIdle;
            }

            @Override
            public int hudTrayRows()
            {
                return rows;
            }

            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
        };
    }

    static Am engine()
    {
        return new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Ab(id, "Item " + id, quantity, 50, quantity * 50)));
            return flows;
        }, new TransactionClassifier(), config(true, 4));
    }

    static Ca grind(long net, Long target)
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        engine.getActiveSession().id = "hud-test-grind";
        engine.getActiveSession().kf(receipt(NOW + 1_000L, Ai.LOOT,
            flow(536, "Dragon bones", 1L, (int) net)), 2_000);
        if (target != null)
        {
            engine.getActiveSession().setProfitTargetGp(target);
        }
        return Ca.capture(engine, NOW + 2_000L, null);
    }

    @Test
    public void nothingTrackingHidesHudPlusUnlessTheOwnerTurnedThatOff()
    {
        Ca idle = Ca.capture(engine(), NOW, null);
        assertFalse(new Cp(config(true, 4), null).update(idle, f -> true, NOW).visible);

        Cb shown = new Cp(config(false, 4), null).update(idle, f -> true, NOW);
        assertTrue(shown.visible);
        assertEquals("unknown is a dash, never zero", "—", shown.net);
        assertEquals(Cb.Gem.OFF, shown.gem);
    }

    @Test
    public void hideWhenNotTrackingHidesAPausedGrindAndUnbookedFreePlay()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        engine.togglePause(NOW + 1_000L);
        assertTrue(engine.getActiveSession().paused);
        assertFalse("a paused Grind is not tracking", new Cp(config(true, 4), null)
            .update(Ca.capture(engine, NOW + 2_000L, null), f -> true, NOW + 2_000L).visible);
        assertTrue("the option off keeps it", new Cp(config(false, 4), null)
            .update(Ca.capture(engine, NOW + 2_000L, null), f -> true, NOW + 2_000L).visible);

        Am free = engine();
        free.rm(NOW);
        Cp builder = new Cp(config(true, 4), null);
        builder.tray().observed(11286, "Draconic visage", 1L, 2_500_000L, NOW, false);
        assertFalse("held reward-window loot is not booked yet", builder
            .update(Ca.capture(free, NOW + 10L, null), f -> true, NOW + 10L).visible);
        free.getActiveSession().kf(receipt(NOW + 20L, Ai.LOOT,
            flow(536, "Dragon bones", 1L, 2_000)), 2_000);
        assertTrue("Free play shows once something is booked", builder
            .update(Ca.capture(free, NOW + 30L, null), f -> true, NOW + 30L).visible);
    }

    @Test
    public void aGrindShowsNetFirstWithItsTargetFoldedIn()
    {
        Cb s = new Cp(config(true, 4), null).update(grind(6_400L, 100_000L), f -> true, NOW);
        assertTrue("a running Grind always shows", s.visible);
        assertEquals("Vorkath", s.title);
        assertEquals("+6.4k", s.net);
        assertEquals(" / 100k", s.target);
        assertEquals(0.064d, s.progress, 0.001d);
        assertEquals("a new run is calculating, never 0/h", "Calculating…", s.rate);
        assertEquals(Cb.Gem.LIVE, s.gem);
    }

    @Test
    public void aLosingNetIsRedWithAnEmptyBarNeverANegativePercent()
    {
        Am engine = engine();
        engine.ajl("Zulrah", Cx.GENERAL, NOW);
        engine.getActiveSession().kf(receipt(NOW + 1_000L, Ai.CONSUMPTION,
            flow(385, "Shark", -10L, 900)), 2_000);
        engine.getActiveSession().setProfitTargetGp(1_000_000L);
        Cb s = new Cp(config(true, 4), null)
            .update(Ca.capture(engine, NOW + 2_000L, null), f -> true, NOW);
        assertEquals(Kit.Tone.LOSS.color, s.netColor);
        assertEquals(0d, s.progress, 0d);
    }

    @Test
    public void theTrayShowsItsRowsThenMoreAndTheFolioCountsHiddenLoot()
    {
        Cp builder = new Cp(config(true, 2), null);
        for (int i = 0; i < 4; i++)
        {
            builder.tray().booked(receipt(NOW + i, Ai.LOOT, flow(100 + i, "Item " + i, 1L, 1_000)),
                NOW + i, false);
        }
        Cb s = builder.update(grind(1_000L, null), flow -> flow.itemId != 100, NOW + 10L);
        assertEquals(2, s.rows.size());
        assertEquals("Item 3 ×1", s.rows.get(0).name);
        assertEquals("+1 more", s.more);
        assertEquals("the chip is the newest item's change, not the total", "+1.0k", s.trip);

        Cb folded = builder.update(grind(1_000L, null), f -> true, NOW + 60_000L);
        assertTrue(folded.rows.isEmpty());
        assertEquals("", folded.trip);
    }

    @Test
    public void filteredMarketReceiptsNeverBecomeHiddenLoot()
    {
        for (long quantity : new long[] {23_600L, -24_000L})
        {
            Cp builder = new Cp(config(true, 4), null);
            builder.tray().booked(Tx.of(NOW, null, Ai.TRADE, Aj.MARKET,
                "GE trade", "Trading", true, List.of(flow(995, "Coins", quantity, 1))), NOW, false);
            Cb snapshot = builder.update(grind(-400L, null), f -> false, NOW + 10L);
            assertTrue(snapshot.rows.isEmpty());
            assertEquals("market cash cannot open a filtered tray", 0f,
                De.axf(snapshot, NOW + Cp.FADE_MILLIS, true), 0f);
            assertEquals("presentation leaves the canonical Net alone", "−400", snapshot.net);
        }
    }

    /** Owner 2026-10-01 (F18): a moment never hides unresolved Review; a compact count rides along. */
    @Test
    public void aMomentStillCarriesTheCompactReviewCount()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        engine.getActiveSession().setProfitTargetGp(400L);
        engine.getActiveSession().kf(receipt(NOW + 1_000L, Ai.LOOT,
            flow(536, "Dragon bones", 1L, 300)), 2_000);
        engine.getActiveSession().kf(new Ac(NOW + 2_000L, null, Ai.UNCERTAIN, Aj.GENERIC, "",
            "Vorkath", true, List.of(new Ab(1, "Bones", 1L, 0, 0L)),
            Bd.UNCERTAIN, "Test sample: awaiting a decision.", null), 2_000);
        Cp builder = new Cp(config(true, 4), null);
        builder.update(Ca.capture(engine, NOW + 3_000L, null), f -> true, NOW + 3_000L);
        engine.getActiveSession().kf(receipt(NOW + 4_000L, Ai.LOOT,
            flow(536, "Dragon bones", 1L, 500)), 2_000);
        Ca snapshot = Ca.capture(engine, NOW + 5_000L, null);
        Cb reached = builder.update(snapshot, f -> true, NOW + 5_000L);
        assertTrue("the moment still shows: " + reached.context,
            reached.context.startsWith("Target reached"));
        assertEquals(1, snapshot.reviewCount);
        assertTrue("the Review count rides along: " + reached.context,
            reached.context.endsWith("\u00b7 (!) " + snapshot.reviewCount));
    }

    @Test
    public void momentsBeatReviewAndTheGrindEndedMomentNamesTheRun()
    {
        Cp builder = new Cp(config(true, 4), null);
        Ca running = grind(150_000L, 100_000L);
        builder.update(grind(50_000L, 100_000L), f -> true, NOW);
        Cb reached = builder.update(running, f -> true, NOW + 1_000L);
        assertTrue(reached.context.startsWith("Target reached"));
        assertEquals("", builder.update(running, f -> true, NOW + 1_000L + HudMoments.SHOW_MILLIS).context);
        assertFalse("no Review count without Review", reached.context.contains("(!)"));

        Ca freePlay = Ca.capture(engine(), NOW + 30_000L, null);
        Cb ended = new Cp(config(false, 4), null).update(freePlay, f -> true, NOW);
        assertEquals("", ended.context);
        Cb after = builder.update(freePlay, f -> true, NOW + 30_000L);
        assertTrue(after.context, after.context.startsWith("Vorkath ended · +150k in"));
    }

    @Test
    public void paintingFitsTheContentAndNeverShrinksWithinATrip()
    {
        GpManagerConfig config = config(true, 4);
        Cp builder = new Cp(config, null);
        De overlay = new De(builder, config);
        BufferedImage canvas = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        assertNull("hidden paints nothing", overlay.render(g));

        builder.tray().booked(receipt(NOW, Ai.LOOT, flow(4151, "Abyssal whip with a long name", 1L, 1_500_000)),
            NOW, false);
        builder.update(grind(1_500_000L, 100_000_000L), f -> true, NOW + 10L);
        Dimension wide = overlay.render(g);
        assertNotNull(wide);
        assertTrue(wide.width >= 130);

        builder.tray().booked(receipt(NOW + 20L, Ai.LOOT, flow(1, "Ash", 1L, 10)), NOW + 20L, false);
        builder.update(grind(1_500_000L, null), f -> true, NOW + 30L);
        assertTrue(overlay.render(g).width >= wide.width);

        builder.update(grind(1_500_000L, null), f -> true, NOW + 60_000L);
        assertTrue("a folded tray settles back to the resting width", overlay.render(g).width < wide.width);
        g.dispose();
    }

    @Test
    public void theHeaderNamesTheGrindAndNeverTheTarget()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.interaction("Goblin", true);
        assertEquals("Vorkath", builder.update(grind(1_000L, null), f -> true, NOW).title);
        builder.interaction("", false);
        assertEquals("Vorkath", builder.update(grind(1_000L, null), f -> true, NOW).title);
    }

    /** Owner 1.1: HUD+ stays clean; activity names such as Combat are for the sidebar. */
    @Test
    public void freePlayHasNoTitleOutsideAKillStreak()
    {
        Cp builder = new Cp(config(false, 4), null);
        Am engine = engine();
        engine.rm(NOW);
        builder.interaction("Goblin", true);
        assertEquals("", builder.update(Ca.capture(engine, NOW, null), f -> true, NOW).title);
        builder.interaction("Fishing spot", false);
        assertEquals("", builder.update(Ca.capture(engine, NOW, null), f -> true, NOW).title);
    }

    @Test
    public void aRaidNameStaysOnTheSidebar()
    {
        Cp builder = new Cp(config(false, 4), null);
        builder.interaction("Goblin", true);
        Ca curated = Ca.capture(engine(), NOW,
            new Dz(false, null, 0L, Bo.NONE, "Theatre of Blood (Hard Mode)", false, ""));
        assertEquals("", builder.update(curated, f -> true, NOW).title);
    }

    @Test
    public void aTimeTargetGetsItsBarAndItsMoment()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        engine.getActiveSession().setActiveTimeTargetMillis(60_000L);
        Cp builder = new Cp(config(true, 4), null);
        Cb early = builder.update(Ca.capture(engine, NOW + 30_000L, null),
            f -> true, NOW + 30_000L);
        Cb done = builder.update(Ca.capture(engine, NOW + 61_000L, null),
            f -> true, NOW + 61_000L);
        assertTrue(done.context, done.context.startsWith("Time target reached"));
    }

    @Test
    public void theTrayFadesOutJustBeforeItFoldsAndABigDropWearsGold()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.tray().booked(receipt(NOW, Ai.LOOT, flow(20997, "Twisted bow", 1L, 1_200_000_000)), NOW, false);
        Cb fresh = builder.update(grind(1_000L, null), f -> true, NOW + 10L);
        assertTrue("a big drop wears a gold edge", fresh.gold);
        assertEquals(NOW + builder.aix(), fresh.foldAt);
        Cb fading = builder.update(grind(1_000L, null), f -> true, NOW + builder.aix() + Cp.FADE_MILLIS / 2);
        assertEquals("rows stay while they fade", 1, fading.rows.size());
        Cb folded = builder.update(grind(1_000L, null), f -> true, NOW + builder.aix() + Cp.FADE_MILLIS);
        assertTrue(folded.rows.isEmpty());
    }

    @Test
    public void theChipKeepsItsOwnSecondsWhateverTheTrayDoes()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.tray().booked(receipt(NOW, Ai.LOOT, flow(1, "Bones", 1L, 300)), NOW, false);
        Cb fresh = builder.update(grind(300L, null), f -> true, NOW + 10L);
        assertEquals("+300", fresh.trip);
        assertEquals(NOW + Cp.CHIP_MILLIS, fresh.chipUntil);
        Cb later = builder.update(grind(300L, null), f -> true, NOW + Cp.CHIP_MILLIS);
        assertEquals("the chip has gone", "", later.trip);
        assertEquals("while the tray is still open", 1, later.rows.size());
    }

    @Test
    public void theTraySlidesOpenAndShutOnTheSnapshotClock()
    {
        Cp builder = new Cp(config(true, 4), null);
        builder.tray().booked(receipt(NOW, Ai.LOOT, flow(1, "Bones", 1L, 300)), NOW, false);
        Cb s = builder.update(grind(300L, null), f -> true, NOW + 10L);
        assertEquals(NOW + 10L, s.openedAt);
        assertEquals(0f, De.axf(s, s.openedAt, true), 0f);
        assertEquals(1f, De.axf(s, s.openedAt + Cp.FADE_MILLIS, true), 0f);
        float half = De.axf(s, s.foldAt + Cp.FADE_MILLIS / 2, true);
        assertTrue(half > 0f && half < 1f);
        assertEquals(0f, De.axf(s, s.foldAt + Cp.FADE_MILLIS, true), 0f);
        assertEquals("reduced motion never slides", 1f, De.axf(s, s.openedAt, false), 0f);
    }

    @Test
    public void stackSpritesPickTheCoinPileBuckets()
    {
        assertEquals(1, Cp.stack(1L));
        assertEquals(4, Cp.stack(4L));
        assertEquals(5, Cp.stack(24L));
        assertEquals(250, Cp.stack(999L));
        assertEquals(10_000, Cp.stack(5_000_000L));
    }
}
