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
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW);
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
        assertFalse(new HudBuilder(config(true, 4), null).update(idle, f -> true, null, NOW).visible);

        HudSnapshot shown = new HudBuilder(config(false, 4), null).update(idle, f -> true, null, NOW);
        assertTrue(shown.visible);
        assertEquals("unknown is a dash, never zero", "—", shown.net);
        assertEquals(HudSnapshot.Gem.OFF, shown.gem);
    }

    @Test
    public void hideWhenNotTrackingHidesAPausedGrindAndUnbookedFreePlay()
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW);
        engine.togglePause(NOW + 1_000L);
        assertTrue(engine.getActiveSession().paused);
        assertFalse("a paused Grind is not tracking", new HudBuilder(config(true, 4), null)
            .update(LiveSnapshot.capture(engine, NOW + 2_000L, null), f -> true, null, NOW + 2_000L).visible);
        assertTrue("the option off keeps it", new HudBuilder(config(false, 4), null)
            .update(LiveSnapshot.capture(engine, NOW + 2_000L, null), f -> true, null, NOW + 2_000L).visible);

        Engine free = engine();
        free.ensureSession(NOW);
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().observed(11286, "Draconic visage", 1L, 2_500_000L, NOW, false);
        assertFalse("held reward-window loot is not booked yet", builder
            .update(LiveSnapshot.capture(free, NOW + 10L, null), f -> true, null, NOW + 10L).visible);
        free.getActiveSession().addTransaction(receipt(NOW + 20L, TransactionType.LOOT,
            flow(536, "Dragon bones", 1L, 2_000)), 2_000);
        assertTrue("Free play shows once something is booked", builder
            .update(LiveSnapshot.capture(free, NOW + 30L, null), f -> true, null, NOW + 30L).visible);
    }

    @Test
    public void aGrindShowsNetFirstWithItsTargetFoldedIn()
    {
        HudSnapshot s = new HudBuilder(config(true, 4), null).update(grind(6_400L, 100_000L), f -> true, null, NOW);
        assertTrue("a running Grind always shows", s.visible);
        assertEquals("Vorkath", s.title);
        assertEquals("+6.4k", s.net);
        assertEquals(" / 100k", s.target);
        assertEquals(0.064d, s.progress, 0.001d);
        assertEquals("a new run is calculating, never 0/h", "Calculating…", s.rate);
        assertEquals(HudSnapshot.Gem.LIVE, s.gem);
    }

    @Test
    public void aLosingNetIsRedWithAnEmptyBarNeverANegativePercent()
    {
        Engine engine = engine();
        engine.startCustomSession("Zulrah", SessionMode.GENERAL, NOW);
        engine.getActiveSession().addTransaction(receipt(NOW + 1_000L, TransactionType.CONSUMPTION,
            flow(385, "Shark", -10L, 900)), 2_000);
        engine.getActiveSession().setProfitTargetGp(1_000_000L);
        HudSnapshot s = new HudBuilder(config(true, 4), null)
            .update(LiveSnapshot.capture(engine, NOW + 2_000L, null), f -> true, null, NOW);
        assertEquals(Kit.Tone.LOSS.color, s.netColor);
        assertEquals(0d, s.progress, 0d);
    }

    @Test
    public void theTrayShowsItsRowsThenMoreAndTheFolioCountsHiddenLoot()
    {
        HudBuilder builder = new HudBuilder(config(true, 2), null);
        for (int i = 0; i < 4; i++)
        {
            builder.tray().booked(receipt(NOW + i, TransactionType.LOOT, flow(100 + i, "Item " + i, 1L, 1_000)),
                NOW + i, false);
        }
        HudSnapshot s = builder.update(grind(1_000L, null), flow -> flow.itemId != 100, null, NOW + 10L);
        assertEquals(2, s.rows.size());
        assertEquals("Item 3 ×1", s.rows.get(0).name);
        assertEquals("+1 more", s.more);
        assertTrue(s.folio.stream().anyMatch(line -> "Hidden loot:".equals(line.label)
            && "1".equals(line.value)));
        assertEquals("the chip is the newest item's change, not the total", "+1.0k", s.trip);

        HudSnapshot folded = builder.update(grind(1_000L, null), f -> true, null, NOW + 60_000L);
        assertTrue(folded.rows.isEmpty());
        assertEquals("", folded.trip);
        assertFalse(folded.folio.stream().anyMatch(line -> "Hidden loot:".equals(line.label)));
    }

    /** Owner 2026-10-01 (F16): the folio names its retention scope, overflow count and filtering. */
    @Test
    public void folioNamesItsRetentionScopeOverflowAndFiltering()
    {
        // Default streak retention: a restarted streak clears, and the list says so.
        HudBuilder streak = new HudBuilder(config(true, 4), null);
        streak.tray().booked(receipt(NOW, TransactionType.LOOT, flow(100, "Old item", 1L, 1_000)), NOW, false);
        streak.tray().restart(false);
        streak.tray().booked(receipt(NOW + 1, TransactionType.LOOT, flow(101, "New item", 1L, 1_000)), NOW + 1, false);
        HudSnapshot streakSnapshot = streak.update(grind(1_000L, null), f -> true, null, NOW + 10L);
        List<String> streakLabels = folioLabels(streakSnapshot.folio);
        assertTrue("the scope names the streak: " + streakLabels, streakLabels.contains("THIS STREAK"));
        assertFalse("never the whole-Grind claim", streakLabels.contains("THIS GRIND"));
        assertTrue(streakLabels.contains("New item ×1"));
        assertFalse("the old streak is gone", streakLabels.contains("Old item ×1"));

        // Session retention: rows keep for the whole run, and the scope says so.
        HudBuilder session = new HudBuilder(new GpManagerConfig()
        {
            @Override public boolean hudHideWhenIdle() { return true; }
            @Override public int hudTrayRows() { return 4; }
            @Override public int stabilizationTicks() { return 1; }
            @Override public GpManagerConfig.HudTrayKeeps hudTrayKeeps()
            {
                return GpManagerConfig.HudTrayKeeps.SESSION;
            }
        }, null);
        session.tray().booked(receipt(NOW, TransactionType.LOOT, flow(100, "Old item", 1L, 1_000)), NOW, true);
        session.tray().restart(true);
        session.tray().booked(receipt(NOW + 1, TransactionType.LOOT, flow(101, "New item", 1L, 1_000)), NOW + 1, true);
        HudSnapshot sessionSnapshot = session.update(grind(1_000L, null), f -> true, null, NOW + 10L);
        List<String> sessionLabels = folioLabels(sessionSnapshot.folio);
        assertTrue("the scope names the retention: " + sessionLabels,
            sessionLabels.contains("WHOLE SESSION"));
        assertTrue(sessionLabels.contains("Old item ×1"));
        assertTrue(sessionLabels.contains("New item ×1"));

        // Nine visible entries truncate to eight with an explicit overflow count.
        HudBuilder many = new HudBuilder(config(true, 4), null);
        for (int i = 0; i < 9; i++)
        {
            many.tray().booked(receipt(NOW + i, TransactionType.LOOT, flow(200 + i, "Row " + i, 1L, 1_000)),
                NOW + i, false);
        }
        HudSnapshot manySnapshot = many.update(grind(1_000L, null), f -> true, null, NOW + 20L);
        List<String> manyLabels = folioLabels(manySnapshot.folio);
        assertEquals("eight items listed", 8,
            manyLabels.stream().filter(label -> label.startsWith("Row ")).count());
        assertTrue("the overflow is counted: " + manyLabels, manyLabels.contains("+1 more"));

        // A hidden item is never named in the folio; the count still discloses it.
        HudSnapshot filtered = many.update(grind(1_000L, null), f -> f.itemId != 208, null, NOW + 30L);
        List<String> filteredLabels = folioLabels(filtered.folio);
        assertFalse("hidden names stay hidden", filteredLabels.contains("Row 8 ×1"));
        assertTrue(filteredLabels.stream().anyMatch(label -> label.startsWith("Hidden loot:")));
    }

    private static List<String> folioLabels(List<HudSnapshot.Line> lines)
    {
        List<String> out = new ArrayList<>();
        for (HudSnapshot.Line line : lines)
        {
            if (line.label != null && !line.label.isEmpty())
            {
                out.add(line.label);
            }
        }
        return out;
    }

    @Test
    public void filteredMarketReceiptsNeverBecomeHiddenLoot()
    {
        for (long quantity : new long[] {23_600L, -24_000L})
        {
            HudBuilder builder = new HudBuilder(config(true, 4), null);
            builder.tray().booked(Tx.of(NOW, null, TransactionType.TRADE, Context.MARKET,
                "GE trade", "Trading", true, List.of(flow(995, "Coins", quantity, 1))), NOW, false);
            HudSnapshot snapshot = builder.update(grind(-400L, null), f -> false, null, NOW + 10L);
            assertTrue(snapshot.rows.isEmpty());
            assertFalse("market cash is not hidden loot", snapshot.folio.stream()
                .anyMatch(line -> "Hidden loot:".equals(line.label)));
            assertEquals("market cash cannot open a filtered tray", 0f,
                HudOverlay.trayOpen(snapshot, NOW + HudBuilder.FADE_MILLIS, true), 0f);
            assertEquals("presentation leaves the canonical Net alone", "−400", snapshot.net);
        }
    }

    /** Owner 2026-10-01 (F18): a moment never hides unresolved Review; a compact count rides along. */
    @Test
    public void aMomentStillCarriesTheCompactReviewCount()
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW);
        engine.getActiveSession().setProfitTargetGp(400L);
        engine.getActiveSession().addTransaction(receipt(NOW + 1_000L, TransactionType.LOOT,
            flow(536, "Dragon bones", 1L, 300)), 2_000);
        engine.getActiveSession().addTransaction(new Transaction(NOW + 2_000L, null, TransactionType.UNCERTAIN, Context.GENERIC, "",
            "Vorkath", true, List.of(new Flow(1, "Bones", 1L, 0, 0L)),
            ClassificationConfidence.UNCERTAIN, "Test sample: awaiting a decision.", null), 2_000);
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.update(LiveSnapshot.capture(engine, NOW + 3_000L, null), f -> true, null, NOW + 3_000L);
        engine.getActiveSession().addTransaction(receipt(NOW + 4_000L, TransactionType.LOOT,
            flow(536, "Dragon bones", 1L, 500)), 2_000);
        LiveSnapshot snapshot = LiveSnapshot.capture(engine, NOW + 5_000L, null);
        HudSnapshot reached = builder.update(snapshot, f -> true, null, NOW + 5_000L);
        assertTrue("the moment still shows: " + reached.context,
            reached.context.startsWith("Target reached"));
        assertEquals(1, snapshot.reviewCount);
        assertTrue("the Review count rides along: " + reached.context,
            reached.context.endsWith("\u00b7 (!) " + snapshot.reviewCount));
    }

    @Test
    public void momentsBeatReviewAndTheGrindEndedMomentNamesTheRun()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        LiveSnapshot running = grind(150_000L, 100_000L);
        builder.update(grind(50_000L, 100_000L), f -> true, null, NOW);
        HudSnapshot reached = builder.update(running, f -> true, null, NOW + 1_000L);
        assertTrue(reached.context.startsWith("Target reached"));
        assertEquals("", builder.update(running, f -> true, null, NOW + 1_000L + HudMoments.SHOW_MILLIS).context);
        assertFalse("no Review count without Review", reached.context.contains("(!)"));

        HudSnapshot best = builder.update(running, f -> true, new long[] {100_000L, Long.MIN_VALUE, Long.MIN_VALUE}, NOW + 20_000L);
        assertEquals("New best Net for Vorkath", best.context);

        LiveSnapshot freePlay = LiveSnapshot.capture(engine(), NOW + 30_000L, null);
        HudSnapshot ended = new HudBuilder(config(false, 4), null).update(freePlay, f -> true, null, NOW);
        assertEquals("", ended.context);
        HudSnapshot after = builder.update(freePlay, f -> true, null, NOW + 30_000L);
        assertTrue(after.context, after.context.startsWith("Vorkath ended · +150k in"));
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
        builder.update(grind(1_500_000L, 100_000_000L), f -> true, null, NOW + 10L);
        Dimension wide = overlay.render(g);
        assertNotNull(wide);
        assertTrue(wide.width >= 130);

        builder.tray().booked(receipt(NOW + 20L, TransactionType.LOOT, flow(1, "Ash", 1L, 10)), NOW + 20L, false);
        builder.update(grind(1_500_000L, null), f -> true, null, NOW + 30L);
        assertTrue(overlay.render(g).width >= wide.width);

        builder.update(grind(1_500_000L, null), f -> true, null, NOW + 60_000L);
        assertTrue("a folded tray settles back to the resting width", overlay.render(g).width < wide.width);
        g.dispose();
    }

    @Test
    public void theFolioOpensRightAndSlidesLeftOnlyAtTheScreenEdge()
    {
        GpManagerConfig config = config(true, 4);
        HudBuilder builder = new HudBuilder(config, null);
        HudOverlay overlay = new HudOverlay(builder, config);
        HudFolio folio = new HudFolio(builder, overlay, config, () -> null, () -> 800, () -> 600);
        HudSnapshot s = builder.update(grind(6_400L, null), f -> true, null, NOW);
        Rectangle hud = new Rectangle(10, 20, 150, 60);

        Rectangle right = folio.place(s, hud, new Point(50, 40), 800, 600, HudFolio.WIDTH, 100);
        assertEquals(164, right.x);
        assertEquals(20, right.y);
        assertNull("no hover, no folio", folio.place(s, hud, new Point(500, 40), 800, 600, HudFolio.WIDTH, 100));

        Rectangle edge = folio.place(s, new Rectangle(700, 20, 90, 60), new Point(720, 40), 800, 600, HudFolio.WIDTH, 100);
        assertEquals(800 - HudFolio.WIDTH, edge.x);
        assertTrue(s.folio.get(0).label.startsWith("GRIND"));
    }

    /** Owner 2026-10-01 (F09): the folio trims to a short canvas and never outgrows it. */
    @Test
    public void theFolioTrimsAndClampsToTheCanvas()
    {
        GpManagerConfig config = config(true, 4);
        HudBuilder builder = new HudBuilder(config, null);
        HudOverlay overlay = new HudOverlay(builder, config);
        HudFolio folio = new HudFolio(builder, overlay, config, () -> null, () -> 800, () -> 120);
        for (int i = 0; i < 12; i++)
        {
            builder.tray().booked(receipt(NOW + i, TransactionType.LOOT, flow(300 + i, "Row " + i, 1L, 1_000)),
                NOW + i, false);
        }
        HudSnapshot s = builder.update(grind(6_400L, null), f -> true, null, NOW + 20L);
        Rectangle hud = new Rectangle(10, 100, 150, 60);

        int room = Math.max(1, (120 - hud.y - 2 * HudOverlay.PAD) / HudFolio.LINE);
        List<HudSnapshot.Line> fitted = HudFolio.fitLines(s.folio, room);
        assertTrue("bounded to the room: " + fitted, fitted.size() <= room);
        assertTrue("the tail is counted, not silently dropped: " + fitted,
            fitted.get(fitted.size() - 1).label.matches("\\+\\d+ more"));

        int height = 2 * HudOverlay.PAD + fitted.size() * HudFolio.LINE;
        Rectangle at = folio.place(s, hud, new Point(50, 110), 800, 120, HudFolio.WIDTH, height);
        assertEquals("the folio clamps up to keep its bottom on the canvas",
            120 - height, at.y);
        assertTrue(at.y >= 0 && at.y + at.height <= 120);

        // A canvas narrower than the desired width bounds the folio horizontally.
        Rectangle narrow = folio.place(s, new Rectangle(700, 20, 90, 60), new Point(720, 40),
            200, 600, 400, 100);
        assertTrue("width stays on the canvas: " + narrow,
            narrow.x >= 0 && narrow.x + narrow.width <= 200);

        // A long Unicode label ellipsizes rather than overlapping its value.
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        java.awt.FontMetrics fm = graphics.getFontMetrics(new java.awt.Font(java.awt.Font.SANS_SERIF,
            java.awt.Font.PLAIN, 12));
        assertTrue("the fit helper shortens long labels",
            HudOverlay.fit(fm, "Antique lamp of the long-forgotten archaeologist", 60).endsWith("\u2026"));
        graphics.dispose();
    }

    @Test
    public void theHeaderPrefersTheFreshTargetThenTheActivityThenTheGrindName()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.interaction("Goblin");
        assertEquals("Goblin", builder.update(grind(1_000L, null), f -> true, null, NOW).title);
        builder.interaction("");
        assertEquals("Vorkath", builder.update(grind(1_000L, null), f -> true, null, NOW).title);
    }

    @Test
    public void aCuratedRaidKeepsItsNameOverAFreshTarget()
    {
        HudBuilder builder = new HudBuilder(config(false, 4), null);
        builder.interaction("Goblin");
        LiveSnapshot curated = LiveSnapshot.capture(engine(), NOW,
            new LiveContext(false, null, 0L, PvpState.NONE, "Theatre of Blood (Hard Mode)", false, ""));
        assertEquals("ToB (HM)", builder.update(curated, f -> true, null, NOW).title);
    }

    @Test
    public void aTimeTargetGetsItsBarAndItsMoment()
    {
        Engine engine = engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW);
        engine.getActiveSession().setActiveTimeTargetMillis(60_000L);
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        HudSnapshot early = builder.update(LiveSnapshot.capture(engine, NOW + 30_000L, null),
            f -> true, null, NOW + 30_000L);
        assertTrue(early.folio.stream().anyMatch(line -> line.kind == HudSnapshot.Line.Kind.BAR
            && line.label.startsWith("Time")));
        HudSnapshot done = builder.update(LiveSnapshot.capture(engine, NOW + 61_000L, null),
            f -> true, null, NOW + 61_000L);
        assertTrue(done.context, done.context.startsWith("Time target reached"));
    }

    /** Owner 2026-10-01 (F12): one vocabulary - Gains on the folio, Active time in the recap. */
    @Test
    public void folioAndRecapUseTheSharedVocabulary()
    {
        HudSnapshot s = new HudBuilder(config(true, 4), null).update(grind(6_400L, 100_000L), f -> true, null, NOW);
        assertTrue("the folio names Gains: " + s.folio,
            s.folio.stream().anyMatch(line -> "Gains".equals(line.label)));
        assertFalse("never Revenue",
            s.folio.stream().anyMatch(line -> "Revenue".equals(line.label)));

        List<HudSnapshot.Line> lines = HudBuilder.recap("Vorkath", 6_400L, 90_000L, "", null);
        assertTrue("the ended card names Active time: " + lines,
            lines.stream().anyMatch(line -> "Active time".equals(line.label)));
        assertFalse("never a bare Time",
            lines.stream().anyMatch(line -> "Time".equals(line.label)));
    }

    @Test
    public void theTrayFadesOutJustBeforeItFoldsAndABigDropWearsGold()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(20997, "Twisted bow", 1L, 1_200_000_000)), NOW, false);
        HudSnapshot fresh = builder.update(grind(1_000L, null), f -> true, null, NOW + 10L);
        assertTrue("a big drop wears a gold edge", fresh.gold);
        assertEquals(NOW + builder.stayMillis(), fresh.foldAt);
        HudSnapshot fading = builder.update(grind(1_000L, null), f -> true, null,
            NOW + builder.stayMillis() + HudBuilder.FADE_MILLIS / 2);
        assertEquals("rows stay while they fade", 1, fading.rows.size());
        assertTrue("the detail panel lists the trip", fading.folio.stream().anyMatch(line -> "THIS STREAK".equals(line.label)));
        HudSnapshot folded = builder.update(grind(1_000L, null), f -> true, null,
            NOW + builder.stayMillis() + HudBuilder.FADE_MILLIS);
        assertTrue(folded.rows.isEmpty());
    }

    @Test
    public void theChipKeepsItsOwnSecondsWhateverTheTrayDoes()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(1, "Bones", 1L, 300)), NOW, false);
        HudSnapshot fresh = builder.update(grind(300L, null), f -> true, null, NOW + 10L);
        assertEquals("+300", fresh.trip);
        assertEquals(NOW + HudBuilder.CHIP_MILLIS, fresh.chipUntil);
        HudSnapshot later = builder.update(grind(300L, null), f -> true, null, NOW + HudBuilder.CHIP_MILLIS);
        assertEquals("the chip has gone", "", later.trip);
        assertEquals("while the tray is still open", 1, later.rows.size());
    }

    @Test
    public void theTraySlidesOpenAndShutOnTheSnapshotClock()
    {
        HudBuilder builder = new HudBuilder(config(true, 4), null);
        builder.tray().booked(receipt(NOW, TransactionType.LOOT, flow(1, "Bones", 1L, 300)), NOW, false);
        HudSnapshot s = builder.update(grind(300L, null), f -> true, null, NOW + 10L);
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
