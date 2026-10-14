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

    static Engine engine()
    {
        return new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Item " + id, quantity, 50, quantity * 50)));
            return flows;
        }, new TransactionClassifier(), config(true, 4));
    }

    static LiveSnapshot grind(long net, Long target)
    {
        Engine engine = engine();
        engine.startCustom("Vorkath", NOW);
        engine.getActiveSession().id = "hud-test-grind";
        engine.getActiveSession().addTransaction(receipt(NOW + 1_000L, TransactionType.LOOT,
            flow(536, "Dragon bones", 1L, (int) net)), 2_000);
        if (target != null)
        {
            engine.getActiveSession().setProfitTargetGp(target);
        }
        return LiveSnapshot.capture(engine, NOW + 2_000L, null);
    }

    @Test
    public void nothingTrackingHidesHudPlusUnlessTheOwnerTurnedThatOff()
    {
        LiveSnapshot idle = LiveSnapshot.capture(engine(), NOW, null);
        assertFalse(new HudBuilder(config(true, 4), null).update(idle, f -> true, NOW).visible);

        HudFrame shown = new HudBuilder(config(false, 4), null).update(idle, f -> true, NOW);
        assertTrue(shown.visible);
        assertEquals("unknown is a dash, never zero", "—", shown.net);
        assertEquals(HudFrame.Gem.OFF, shown.gem);
    }

    @Test
    public void hideWhenNotTrackingHidesAPausedGrindAndUnbookedFreePlay()
    {
        Engine engine = engine();
        engine.startCustom("Vorkath", NOW);
        engine.togglePause(NOW + 1_000L);
        assertTrue(engine.getActiveSession().paused);
        assertFalse("a paused Grind is not tracking", new HudBuilder(config(true, 4), null)
            .update(LiveSnapshot.capture(engine, NOW + 2_000L, null), f -> true, NOW + 2_000L).visible);
        assertTrue("the option off keeps it", new HudBuilder(config(false, 4), null)
            .update(LiveSnapshot.capture(engine, NOW + 2_000L, null), f -> true, NOW + 2_000L).visible);

        Engine free = engine();
        free.ensureSession(NOW);
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().observed(11286, "Draconic visage", 1L, 2_500_000L, NOW, false);
        assertFalse("held reward-window loot is not booked yet", builder
            .update(LiveSnapshot.capture(free, NOW + 10L, null), f -> true, NOW + 10L).visible);
        free.getActiveSession().addTransaction(receipt(NOW + 20L, TransactionType.LOOT,
            flow(536, "Dragon bones", 1L, 2_000)), 2_000);
        assertTrue("Free play shows once something is booked", builder
            .update(LiveSnapshot.capture(free, NOW + 30L, null), f -> true, NOW + 30L).visible);
    }

    @Test
    public void aGrindShowsNetWithItsTargetOnTheBar()
    {
        HudFrame s = new HudBuilder(config(true, 4), null).update(grind(6_400L, 100_000L), f -> true, NOW);
        assertTrue("a running Grind always shows", s.visible);
        assertEquals("the run's name lives on the sidebar only", "", s.title);
        assertEquals("+6.4k", s.net);
        assertEquals("the gauge counts down: what is left", "93.6k", s.target);
        assertEquals("while the bar fills with the progress", 0.064d, s.progress, 0.001d);
        assertEquals("a new run is calculating, never 0/h", "Calculating…", s.rate);
        assertEquals(HudFrame.Gem.LIVE, s.gem);

        // A reached aim reads zero with a full bar and the done signal (owner live 2026-10-14).
        Engine done = engine();
        done.startCustom("Vorkath", NOW);
        done.getActiveSession().addTransaction(receipt(NOW + 1_000L, TransactionType.LOOT,
            flow(536, "Dragon bones", 1L, 300_000)), 2_000);
        done.getActiveSession().setProfitTargetGp(200_000L);
        HudFrame reached = new HudBuilder(config(true, 4), null)
            .update(LiveSnapshot.capture(done, NOW + 2_000L, null), f -> true, NOW + 2_000L);
        assertEquals("a reached aim reads zero left", "0", reached.target);
        assertEquals("with a full bar", 1d, reached.progress, 0d);
        assertTrue("and the bar's done signal", reached.targetDone);
        assertFalse("a fresh aim is not done", s.targetDone);
    }

    /** Owner live 2026-10-13: the HUD shows a time target's countdown and warns in its last seconds. */
    @Test
    public void aTimeTargetCountsDownAndWarnsInItsFinalSeconds()
    {
        Engine engine = engine();
        engine.startCustom("Vorkath", NOW - 60_000L);
        engine.getActiveSession().setActiveTimeTargetMillis(2L * 60_000L, NOW);
        engine.getActiveSession().activeTimeTargetSetMillis = 0L;
        HudFrame mid = new HudBuilder(config(true, 4), null)
            .update(LiveSnapshot.capture(engine, NOW + 1_000L, null), f -> true, NOW + 1_000L);
        assertEquals("the label is the live countdown", "59s", mid.target);
        assertFalse("no warning before the final seconds", mid.targetWarning);

        engine.getActiveSession().setActiveTimeTargetMillis(60_000L, NOW + 1_000L);
        engine.getActiveSession().activeTimeTargetSetMillis = 9_000L;
        HudFrame late = new HudBuilder(config(true, 4), null)
            .update(LiveSnapshot.capture(engine, NOW + 1_000L, null), f -> true, NOW + 1_000L);
        assertEquals("seconds remain in the countdown", "8s", late.target);
        assertTrue("the final seconds warn (default 10)", late.targetWarning);
        assertEquals("the bar tracks the window, not the Net", 52d / 60d, late.progress, 0.01d);

        GpManagerConfig noWarning = new GpManagerConfig()
        {
            @Override public boolean hudHideWhenIdle() { return true; }
            @Override public int hudTrayRows() { return 4; }
            @Override public int hudTargetWarningSeconds() { return 0; }
            @Override public int stabilizationTicks() { return 1; }
        };
        HudFrame off = new HudBuilder(noWarning, null)
            .update(LiveSnapshot.capture(engine, NOW + 1_000L, null), f -> true, NOW + 1_000L);
        assertEquals("the countdown stays", "8s", off.target);
        assertFalse("0 turns the warning off", off.targetWarning);

        // Both targets: the Net label stays until the final seconds, then the countdown replaces
        // it (owner live 2026-10-13).
        Engine both = engine();
        both.startCustom("Vorkath", NOW - 61_000L);
        both.getActiveSession().setProfitTargetGp(200_000L);
        both.getActiveSession().setActiveTimeTargetMillis(60_000L, NOW);
        both.getActiveSession().activeTimeTargetSetMillis = 20_000L;
        HudFrame running = new HudBuilder(config(true, 4), null)
            .update(LiveSnapshot.capture(both, NOW, null), f -> true, NOW);
        assertEquals("the both-run drains from its aim", "200k", running.target);
        assertFalse("no warning outside the final seconds", running.targetWarning);

        both.getActiveSession().activeTimeTargetSetMillis = 9_000L;
        HudFrame flip = new HudBuilder(config(true, 4), null)
            .update(LiveSnapshot.capture(both, NOW, null), f -> true, NOW);
        assertEquals("the label flips to the countdown", "8s", flip.target);
        assertTrue("the both-target run warns too", flip.targetWarning);

        // A frozen countdown never warns (owner live 2026-10-14): a both-run returns to its Net
        // label, a time-only run keeps its frozen seconds.
        both.getActiveSession().pauseTimeTarget(NOW + 1_000L);
        HudFrame frozen = new HudBuilder(config(true, 4), null)
            .update(LiveSnapshot.capture(both, NOW + 1_000L, null), f -> true, NOW + 1_000L);
        assertEquals("the frozen both-run keeps its drain value", "200k", frozen.target);
        assertFalse("a frozen countdown never warns", frozen.targetWarning);

        engine.getActiveSession().pauseTimeTarget(NOW + 1_000L);
        HudFrame frozenOnly = new HudBuilder(config(true, 4), null)
            .update(LiveSnapshot.capture(engine, NOW + 1_000L, null), f -> true, NOW + 1_000L);
        assertEquals("the frozen time-only countdown keeps its value", "8s", frozenOnly.target);
        assertFalse("and still never warns", frozenOnly.targetWarning);

        // Zero warning seconds keep the Net gauge throughout on a both-run (owner live
        // 2026-10-14: the countdown replacement follows the setting).
        both.getActiveSession().resumeTimeTarget(NOW + 1_000L);
        GpManagerConfig noWarn = new GpManagerConfig()
        {
            @Override public boolean hudHideWhenIdle() { return true; }
            @Override public int hudTrayRows() { return 4; }
            @Override public int hudTargetWarningSeconds() { return 0; }
            @Override public int stabilizationTicks() { return 1; }
        };
        HudFrame silent = new HudBuilder(noWarn, null)
            .update(LiveSnapshot.capture(both, NOW + 1_000L, null), f -> true, NOW + 1_000L);
        assertEquals("no countdown without a warning window", "200k", silent.target);
        assertFalse("and no warning", silent.targetWarning);

        // The window follows the setting: 30s warns at 25s left, not at 34s (owner live 2026-10-14).
        engine.getActiveSession().resumeTimeTarget(NOW + 1_000L);
        GpManagerConfig wide = new GpManagerConfig()
        {
            @Override public boolean hudHideWhenIdle() { return true; }
            @Override public int hudTrayRows() { return 4; }
            @Override public int hudTargetWarningSeconds() { return 30; }
            @Override public int stabilizationTicks() { return 1; }
        };
        engine.getActiveSession().activeTimeTargetSetMillis = 35_000L;
        HudFrame wideQuiet = new HudBuilder(wide, null)
            .update(LiveSnapshot.capture(engine, NOW + 1_000L, null), f -> true, NOW + 1_000L);
        assertFalse("30s window does not warn at 34s left", wideQuiet.targetWarning);
        engine.getActiveSession().activeTimeTargetSetMillis = 26_000L;
        HudFrame wideWarn = new HudBuilder(wide, null)
            .update(LiveSnapshot.capture(engine, NOW + 1_000L, null), f -> true, NOW + 1_000L);
        assertTrue("but warns at 25s left", wideWarn.targetWarning);
        assertEquals("25s", wideWarn.target);
    }

    @Test
    public void aLosingNetIsRedAndTheGaugeNeverGoesNegative()
    {
        Engine engine = engine();
        engine.startCustom("Zulrah", NOW);
        engine.getActiveSession().addTransaction(receipt(NOW + 1_000L, TransactionType.CONSUMPTION,
            flow(385, "Shark", -10L, 900)), 2_000);
        engine.getActiveSession().setProfitTargetGp(1_000_000L);
        HudFrame s = new HudBuilder(config(true, 4), null)
            .update(LiveSnapshot.capture(engine, NOW + 2_000L, null), f -> true, NOW);
        assertEquals(Kit.Tone.LOSS.color, s.netColor);
        assertEquals("the bar waits empty while nothing is earned", 0d, s.progress, 0d);
        assertEquals("and reads everything still to earn", "1.00M", s.target);
    }

    @Test
    public void theTrayShowsItsRowsThenMoreAndTheFooterCountsHiddenLoot()
    {
        HudBuilder builder = new HudBuilder(config(true, 2), null);
        for (int i = 0; i < 4; i++)
        {
            builder.tray().booked(receipt(NOW + i, TransactionType.LOOT, flow(100 + i, "Item " + i, 1L, 1_000)),
                NOW + i, false);
        }
        HudFrame s = builder.update(grind(1_000L, null), flow -> flow.itemId != 100, NOW + 10L);
        assertEquals(2, s.rows.size());
        assertEquals("Item 3 ×1", s.rows.get(0).name);
        assertEquals("the hidden item rides the footer, unnamed", "+1 more · +1 hidden", s.more);
        assertEquals("the chip is the newest item's change, not the total", "+1.0k", s.trip);

        HudFrame folded = builder.update(grind(1_000L, null), f -> true, NOW + 60_000L);
        assertTrue(folded.rows.isEmpty());
        assertEquals("", folded.trip);
    }

    /** Owner 2026-10-03: a hidden pickup never opens the tray or holds it open. */
    @Test
    public void hiddenLootNeverReopensAFoldedTray()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(536, "Dragon bones", 1L, 2_000)), NOW, false);
        long later = NOW + builder.stayMillis() + HudBuilder.FADE_MILLIS + 1_000L;
        builder.tray().booked(receipt(later, TransactionType.LOOT, flow(526, "Bones", 1L, 100)), later, false);
        HudFrame s = builder.update(grind(1_000L, null), flow -> flow.itemId != 526, later + 10L);
        assertTrue("the visible row stays folded", s.rows.isEmpty());
        assertEquals("a folded tray shows no footer", "", s.more);
        assertEquals("the money still flashes beside Net", "+100", s.trip);

        HudFrame all = builder.update(grind(1_000L, null), flow -> flow.itemId != 526, later + 20L);
        assertEquals("folds by its visible entries' time", NOW + builder.stayMillis(), all.foldAt);
    }

    @Test
    public void anAllHiddenTrayStaysShut()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(526, "Bones", 3L, 100)), NOW, false);
        HudFrame s = builder.update(grind(1_000L, null), f -> false, NOW + 10L);
        assertTrue(s.rows.isEmpty());
        assertEquals("", s.more);
    }

    @Test
    public void theHiddenFooterSkipsMarketCash()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(536, "Dragon bones", 1L, 2_000)), NOW, false);
        builder.tray().booked(receipt(NOW + 1L, TransactionType.LOOT, flow(526, "Bones", 2L, 100)), NOW + 1L, false);
        builder.tray().booked(Tx.of(NOW + 2L, null, TransactionType.TRADE, Context.MARKET, "GE trade", "Trading", true,
            List.of(flow(995, "Coins", 5_000L, 1))), NOW + 2L, false);
        HudFrame s = builder.update(grind(1_000L, null), flow -> flow.itemId == 536, NOW + 10L);
        assertEquals(1, s.rows.size());
        assertEquals("one hidden loot row; GE cash is not hidden loot", "+1 hidden", s.more);
    }

    @Test
    public void filteredMarketReceiptsNeverBecomeHiddenLoot()
    {
        for (long quantity : new long[] {23_600L, -24_000L})
        {
            HudBuilder builder = new HudBuilder(config(true, 4), null);
            builder.tray().booked(Tx.of(NOW, null, TransactionType.TRADE, Context.MARKET,
                "GE trade", "Trading", true, List.of(flow(995, "Coins", quantity, 1))), NOW, false);
            HudFrame snapshot = builder.update(grind(-400L, null), f -> false, NOW + 10L);
            assertTrue(snapshot.rows.isEmpty());
            assertEquals("market cash cannot open a filtered tray", 0f,
                HudOverlay.trayOpen(snapshot, NOW + HudBuilder.FADE_MILLIS, true), 0f);
            assertEquals("presentation leaves the canonical Net alone", "−400", snapshot.net);
        }
    }

    @Test
    public void paintingFitsTheContentAndNeverShrinksWithinATrip()
    {
        GpManagerConfig config = config(true, 4);
        HudBuilder builder = new HudBuilder(config, null);
        HudOverlay overlay = new HudOverlay(builder, config);
        BufferedImage canvas = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        assertNull("hidden paints nothing", overlay.render(g));

        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(4151, "Abyssal whip with a long name", 1L, 1_500_000)),
            NOW, false);
        builder.update(grind(1_500_000L, 100_000_000L), f -> true, NOW + 10L);
        Dimension wide = overlay.render(g);
        assertNotNull(wide);
        assertTrue(wide.width >= 130);

        builder.tray().booked(receipt(NOW + 20L, TransactionType.LOOT, flow(1, "Ash", 1L, 10)), NOW + 20L, false);
        builder.update(grind(1_500_000L, null), f -> true, NOW + 30L);
        assertTrue(overlay.render(g).width >= wide.width);

        builder.update(grind(1_500_000L, null), f -> true, NOW + 60_000L);
        assertTrue("a folded tray settles back to the resting width", overlay.render(g).width < wide.width);
        g.dispose();
    }

    @Test
    public void theHeaderNeverNamesTheGrindOrTheTarget()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.interaction("Goblin", true);
        assertEquals("the run's name stays on the sidebar", "",
            builder.update(grind(1_000L, null), f -> true, NOW).title);
        builder.interaction("", false);
        assertEquals("", builder.update(grind(1_000L, null), f -> true, NOW).title);
    }

    /** Owner 1.1: HUD+ stays clean; activity names such as Combat are for the sidebar. */
    @Test
    public void freePlayHasNoTitleOutsideAKillStreak()
    {
        HudBuilder builder = new HudBuilder(config(false, 4), null);
        Engine engine = engine();
        engine.ensureSession(NOW);
        builder.interaction("Goblin", true);
        assertEquals("", builder.update(LiveSnapshot.capture(engine, NOW, null), f -> true, NOW).title);
        builder.interaction("Fishing spot", false);
        assertEquals("", builder.update(LiveSnapshot.capture(engine, NOW, null), f -> true, NOW).title);
    }

    @Test
    public void aRaidNameStaysOnTheSidebar()
    {
        HudBuilder builder = new HudBuilder(config(false, 4), null);
        builder.interaction("Goblin", true);
        LiveSnapshot curated = LiveSnapshot.capture(engine(), NOW,
            new LiveContext(false, null, 0L, PvpState.NONE, "Theatre of Blood (Hard Mode)", false, ""));
        assertEquals("", builder.update(curated, f -> true, NOW).title);
    }


    @Test
    public void theTrayFadesOutJustBeforeItFoldsAndABigDropWearsGold()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(20997, "Twisted bow", 1L, 1_200_000_000)), NOW, false);
        HudFrame fresh = builder.update(grind(1_000L, null), f -> true, NOW + 10L);
        assertEquals(NOW + builder.stayMillis(), fresh.foldAt);
        HudFrame fading = builder.update(grind(1_000L, null), f -> true, NOW + builder.stayMillis() + HudBuilder.FADE_MILLIS / 2);
        assertEquals("rows stay while they fade", 1, fading.rows.size());
        HudFrame folded = builder.update(grind(1_000L, null), f -> true, NOW + builder.stayMillis() + HudBuilder.FADE_MILLIS);
        assertTrue(folded.rows.isEmpty());
    }

    /** Owner 1.2: more of an item already on the tray brings its row back to the top. */
    @Test
    public void theRowThatLastGrewLeadsTheTray()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(1, "Bones", 1L, 100)), NOW, false);
        builder.tray().booked(receipt(NOW + 1L, TransactionType.LOOT, flow(2, "Coins", 50L, 50)), NOW + 1L, false);
        builder.tray().booked(receipt(NOW + 2L, TransactionType.LOOT, flow(1, "Bones", 1L, 100)), NOW + 2L, false);
        HudFrame s = builder.update(grind(1_000L, null), f -> true, NOW + 10L);
        assertEquals("Bones ×2", s.rows.get(0).name);
        assertEquals("Coins ×50", s.rows.get(1).name);
    }

    /** HUD+ 1.2: cheaper loot after a big drop goes to "+N more", never the big drop itself. */
    @Test
    public void aBigDropKeepsItsRowWhenCheaperLootOverflowsTheTray()
    {
        HudBuilder builder = new HudBuilder(config(true, 2), null);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(20997, "Twisted bow", 1L, 1_200_000_000)), NOW, false);
        for (int i = 1; i <= 3; i++)
        {
            builder.tray().booked(receipt(NOW + i, TransactionType.LOOT, flow(i, "Junk " + i, 1L, 100)), NOW + i, false);
        }
        HudFrame s = builder.update(grind(1_000L, null), f -> true, NOW + 10L);
        assertEquals(2, s.rows.size());
        assertEquals("the newest cheap row still shows", "Junk 3 ×1", s.rows.get(0).name);
        assertTrue("the bow keeps its row", s.rows.get(1).gold);
        assertEquals("+2 more", s.more);
    }

    @Test
    public void theChipKeepsItsOwnSecondsWhateverTheTrayDoes()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(1, "Bones", 1L, 300)), NOW, false);
        HudFrame fresh = builder.update(grind(300L, null), f -> true, NOW + 10L);
        assertEquals("+300", fresh.trip);
        assertEquals(NOW + HudBuilder.CHIP_MILLIS, fresh.chipUntil);
        HudFrame later = builder.update(grind(300L, null), f -> true, NOW + HudBuilder.CHIP_MILLIS);
        assertEquals("the chip has gone", "", later.trip);
        assertEquals("while the tray is still open", 1, later.rows.size());
    }

    @Test
    public void theTraySlidesOpenAndShutOnTheSnapshotClock()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(1, "Bones", 1L, 300)), NOW, false);
        HudFrame s = builder.update(grind(300L, null), f -> true, NOW + 10L);
        assertEquals(NOW + 10L, s.openedAt);
        assertEquals(0f, HudOverlay.trayOpen(s, s.openedAt, true), 0f);
        assertEquals(1f, HudOverlay.trayOpen(s, s.openedAt + HudBuilder.FADE_MILLIS, true), 0f);
        float half = HudOverlay.trayOpen(s, s.foldAt + HudBuilder.FADE_MILLIS / 2, true);
        assertTrue(half > 0f && half < 1f);
        assertEquals(0f, HudOverlay.trayOpen(s, s.foldAt + HudBuilder.FADE_MILLIS, true), 0f);
        assertEquals("reduced motion never slides", 1f, HudOverlay.trayOpen(s, s.openedAt, false), 0f);
    }

    @Test
    public void stackSpritesPickTheCoinPileBuckets()
    {
        assertEquals(1, HudBuilder.stack(1L));
        assertEquals(4, HudBuilder.stack(4L));
        assertEquals(5, HudBuilder.stack(24L));
        assertEquals(250, HudBuilder.stack(999L));
        assertEquals(10_000, HudBuilder.stack(5_000_000L));
    }
}
