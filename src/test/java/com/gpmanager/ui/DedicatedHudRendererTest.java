package com.gpmanager.ui;

import com.gpmanager.FeedbackStyle;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.HudPlusRewardPresentation;
import com.gpmanager.InfoBoxTheme;
import com.gpmanager.InfoBoxView;
import com.gpmanager.TrackingDisplay;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMetrics;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.party.PartyProfitTracker;
import com.gpmanager.persistence.SavedState;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardObservation;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.reward.RewardPresentationPhase;
import com.gpmanager.reward.RewardSourceKind;
import com.gpmanager.GpManagerOverlay;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DedicatedHudRendererTest
{
    @Test
    public void customLegacyHudConsumesEveryCustomContentAndSpacingSetting()
    {
        long now = 1_900_000_000_000L;
        boolean[] called = new boolean[9];
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public TrackingDisplay trackingDisplay() { return TrackingDisplay.HUD; }
            @Override public InfoBoxView infoBoxView() { return InfoBoxView.CUSTOM; }
            @Override public int infoBoxPadding() { called[0] = true; return 5; }
            @Override public int infoBoxRowGap() { called[1] = true; return 3; }
            @Override public boolean showInfoBoxActivity() { called[2] = true; return true; }
            @Override public boolean showInfoBoxRate() { called[3] = true; return true; }
            @Override public boolean showInfoBoxRevenue() { called[4] = true; return true; }
            @Override public boolean showInfoBoxCosts() { called[5] = true; return true; }
            @Override public boolean showInfoBoxTime() { called[6] = true; return true; }
            @Override public boolean showInfoBoxActions() { called[7] = true; return true; }
            @Override public boolean showInfoBoxChanges() { called[8] = true; return true; }
        };
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(now);
        HudClock clock = new HudClock();
        clock.fixAt(now);
        TrackingDisplayModel display = new TrackingDisplayModel(
            engine, config, new RewardPresentationModel(), null, clock, null);
        GpManagerOverlay overlay = new GpManagerOverlay(
            engine, config, new PartyProfitTracker(null, null), display, clock, null);
        BufferedImage image = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        overlay.render(graphics);
        graphics.dispose();

        for (boolean settingRead : called)
        {
            assertTrue(settingRead);
        }
    }

    @Test
    public void revealThenSettledUsesClockAndDedicatedPaint()
    {
        long t0 = 2_000_000_000_000L;
        HudClock clock = new HudClock();
        clock.fixAt(t0);
        GpManagerConfig config = dedicatedConfig(false, false);
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        ProfitSession session = new ProfitSession("General", t0 - 60_000L, SessionMode.AUTO);
        session.setActivityHint("PvM", t0);
        session.addTransaction(new ProfitTransaction(t0, TransactionType.GAIN, TrackingContext.GENERIC,
            "PvM", true, Collections.singletonList(new ItemFlow(995, "Coins", 100L, 1, 100L))), 0);
        engine.restore(new SavedState(session, Collections.emptyList()));

        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Kree'arra",
            "npc:1",
            Arrays.asList(
                new RewardItem(11818, "Armadyl hilt", 1L, 8_000_000L, true, ItemPriceSource.GRAND_EXCHANGE),
                new RewardItem(995, "Coins", 20_000L, 20_000L, true, ItemPriceSource.GRAND_EXCHANGE)),
            t0,
            true);
        TrackingDisplayModel display = new TrackingDisplayModel(engine, config, rewards, null, clock, null);
        GpManagerOverlay overlay = new GpManagerOverlay(
            engine, config, new PartyProfitTracker(null, null), display, clock, null);

        clock.fixAt(t0 + 250L);
        BufferedImage img = new BufferedImage(280, 220, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        overlay.render(g);
        g.dispose();
        assertEquals(RewardPresentationPhase.REVEALING, display.snapshot(clock.now()).getRewardPhase());
        assertNotNull(overlay.lastDedicatedPaint());
        assertTrue(overlay.lastDedicatedPaint().drewRevealTray);
        assertFalse(overlay.lastDedicatedPaint().drewSettledRow);
        int revealH = overlay.lastDedicatedPaint().size.height;

        clock.fixAt(t0 + 2 * RewardPresentationModel.MILLIS_PER_GAME_TICK
            + RewardPresentationModel.REVEAL_DWELL_MILLIS + 20L);
        img = new BufferedImage(280, 160, BufferedImage.TYPE_INT_ARGB);
        g = img.createGraphics();
        overlay.render(g);
        g.dispose();
        assertEquals(RewardPresentationPhase.NONE, display.snapshot(clock.now()).getRewardPhase());
        assertFalse(overlay.lastDedicatedPaint().drewRevealTray);
        assertFalse(overlay.lastDedicatedPaint().drewSettledRow);
        assertTrue(overlay.lastDedicatedPaint().size.height < revealH);
        assertTrue(overlay.lastDedicatedPaint().size.width <= 220);
        assertTrue(overlay.lastDedicatedPaint().size.width >= DedicatedHudRenderer.MIN_WIDTH);
    }

    @Test
    public void reducedMotionStillShowsStaticTray()
    {
        long t0 = 2_100_000_000_000L;
        HudClock clock = new HudClock();
        clock.fixAt(t0);
        GpManagerConfig config = dedicatedConfig(true, false, FeedbackStyle.INTEGRATED);
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(t0);
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "npc:2",
            Collections.singletonList(
                new RewardItem(557, "Earth rune", 4L, 20L, true, ItemPriceSource.GRAND_EXCHANGE)),
            t0,
            false);
        TrackingDisplayModel display = new TrackingDisplayModel(engine, config, rewards, null, clock, null);
        GpManagerOverlay overlay = new GpManagerOverlay(
            engine, config, new PartyProfitTracker(null, null), display, clock, null);
        clock.fixAt(t0 + 100L);
        BufferedImage img = new BufferedImage(240, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        overlay.render(g);
        g.dispose();
        assertTrue(overlay.lastDedicatedPaint().drewRevealTray);
    }

    @Test
    public void floatingFeedbackStillPaintsHudPlusUnconfirmedTray()
    {
        long t0 = 2_200_000_000_000L;
        assertTrue(paintChickenObservation(FeedbackStyle.FLOATING, t0).drewRevealTray);
        assertNull(DedicatedHudRenderer.collectionFooter(chickenObservation(t0)));
    }

    @Test
    public void integratedFeedbackStillPaintsHudPlusUnconfirmedTray()
    {
        long t0 = 2_300_000_000_000L;
        assertTrue(paintChickenObservation(FeedbackStyle.INTEGRATED, t0).drewRevealTray);
    }

    @Test
    public void amountColorPicksBySignIndependentlyPerMetric()
    {
        java.awt.Color positive = new java.awt.Color(1, 2, 3);
        java.awt.Color negative = new java.awt.Color(4, 5, 6);
        java.awt.Color neutral = new java.awt.Color(7, 8, 9);

        assertEquals(positive, DedicatedHudRenderer.amountColor(1L, positive, negative, neutral));
        assertEquals(negative, DedicatedHudRenderer.amountColor(-1L, positive, negative, neutral));
        assertEquals(neutral, DedicatedHudRenderer.amountColor(0L, positive, negative, neutral));

        // Net and rate are independent inputs to the same helper — a losing net with a
        // currently-positive rate (or vice versa) must not "mute" the rate to neutral.
        assertEquals(positive, DedicatedHudRenderer.amountColor(50L, positive, negative, neutral));
        assertEquals(negative, DedicatedHudRenderer.amountColor(-50L, positive, negative, neutral));
    }

    @Test
    public void legacyFeedbackOffStillPaintsHudPlusRewardTray()
    {
        // Tray is owned by Tracking display = HUD+, not the retired Change feedback picker.
        long t0 = 2_400_000_000_000L;
        DedicatedHudRenderer.PaintResult paint = paintChickenObservation(FeedbackStyle.OFF, t0);
        assertTrue(paint.drewRevealTray);
    }

    private static DedicatedHudRenderer.PaintResult paintChickenObservation(
        FeedbackStyle feedback,
        long t0)
    {
        HudClock clock = new HudClock();
        clock.fixAt(t0);
        GpManagerConfig config = dedicatedConfig(false, false, feedback);
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(t0);
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:1",
            Arrays.asList(
                new RewardItem(526, "Bones", 1L, 35L, true, ItemPriceSource.GRAND_EXCHANGE),
                new RewardItem(2138, "Raw chicken", 1L, 40L, true, ItemPriceSource.GRAND_EXCHANGE),
                new RewardItem(314, "Feather", 5L, 15L, true, ItemPriceSource.GRAND_EXCHANGE)),
            t0,
            true);
        TrackingDisplayModel display = new TrackingDisplayModel(engine, config, rewards, null, clock, null);
        GpManagerOverlay overlay = new GpManagerOverlay(
            engine, config, new PartyProfitTracker(null, null), display, clock, null);
        clock.fixAt(t0 + 100L);
        BufferedImage img = new BufferedImage(280, 220, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        overlay.render(g);
        g.dispose();
        assertNotNull(overlay.lastDedicatedPaint());
        return overlay.lastDedicatedPaint();
    }

    private static RewardObservation chickenObservation(long t0)
    {
        return new RewardObservation(
            "r", "d", RewardSourceKind.NPC_LOOT, "Chicken", "npc:Chicken:1",
            t0,
            Arrays.asList(
                new RewardItem(526, "Bones", 1L, 35L, true, ItemPriceSource.GRAND_EXCHANGE),
                new RewardItem(2138, "Raw chicken", 1L, 40L, true, ItemPriceSource.GRAND_EXCHANGE),
                new RewardItem(314, "Feather", 5L, 15L, true, ItemPriceSource.GRAND_EXCHANGE)),
            false, 1, "");
    }

    private static GpManagerConfig dedicatedConfig(boolean reduced, boolean pinned)
    {
        return dedicatedConfig(reduced, pinned, FeedbackStyle.INTEGRATED);
    }

    private static GpManagerConfig dedicatedConfig(
        boolean reduced,
        boolean pinned,
        FeedbackStyle feedback)
    {
        return new GpManagerConfig()
        {
            @Override public TrackingDisplay trackingDisplay() { return TrackingDisplay.HUD_PLUS; }
            @Override public FeedbackStyle feedbackStyle() { return feedback; }
            @Override public InfoBoxView infoBoxView() { return InfoBoxView.COMPACT; }
            @Override public int infoBoxWidth() { return 220; }
            @Override public int hudPlusWidth() { return 220; }
            @Override public InfoBoxTheme infoBoxTheme() { return InfoBoxTheme.DARK; }
            @Override public boolean reducedMotion() { return reduced; }
            @Override public boolean keepRewardTrayExpanded() { return pinned; }
            @Override public HudPlusRewardPresentation hudPlusRewardPresentation()
            {
                return pinned
                    ? HudPlusRewardPresentation.KEEP_EXPANDED
                    : HudPlusRewardPresentation.AUTO_COLLAPSE;
            }
        };
    }

    @Test
    public void tripBeaconPaintsWithOptionalFolioWiderThanTripAlone()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Oak tree", "Live", null, 1_000L, 3_000L, 0L, null, null);
        BufferedImage img = new BufferedImage(500, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DedicatedHudRenderer.PaintResult tripOnly = renderer.renderTripBeacon(
            g, snap, null, 220, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            true, false, false, false, null, false, 1_000L);
        com.gpmanager.reward.SessionItemLedger ledger = new com.gpmanager.reward.SessionItemLedger();
        ledger.record(new ProfitTransaction(
            1_000L, null, TransactionType.GAIN, TrackingContext.GENERIC, "", "Woodcutting", true,
            Collections.singletonList(new ItemFlow(1511, "Oak logs", 10L, 100, 1000L))));
        DedicatedHudRenderer.PaintResult withFolio = renderer.renderTripBeacon(
            g, snap, null, 220, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            true, false, true, false, ledger.snapshot(0L, 5, 5), false, 1_000L);
        g.dispose();
        assertTrue(withFolio.size.width > tripOnly.size.width);
        assertTrue(withFolio.size.height >= tripOnly.size.height);
        assertTrue(withFolio.folioWidth >= DedicatedHudRenderer.FOLIO_MIN);
        assertTrue(withFolio.folioWidth <= DedicatedHudRenderer.FOLIO_MAX);
        assertTrue("short folio should hug under old fixed 200",
            withFolio.folioWidth < 200 || withFolio.folioWidth == DedicatedHudRenderer.FOLIO_MIN);
    }

    @Test
    public void wildernessRiskRowIsCompactPvPOnlyAndHonorsToggle()
    {
        TrackingDisplaySnapshot safe = TrackingDisplaySnapshot.from(
            null, "", "Live", null, 1_000L, 3_000L, 0L, null, null);
        WildernessRiskCalculator.Result measured = WildernessRiskCalculator.calculate(
            Collections.singletonList(new WildernessRiskCalculator.ItemStack(995, 100L, 1L)),
            Collections.emptyList(), 0);
        TrackingDisplaySnapshot pvpKnown = safe.withWildernessRisk(true, measured);
        TrackingDisplaySnapshot pvpUnknown = safe.withWildernessRisk(true, null);

        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        DedicatedHudRenderer.PaintResult shown = paintRisk(renderer, pvpKnown, true);
        DedicatedHudRenderer.PaintResult unknown = paintRisk(renderer, pvpUnknown, true);
        DedicatedHudRenderer.PaintResult outsidePvp = paintRisk(renderer, pvpKnown.withWildernessRisk(false, measured), true);
        DedicatedHudRenderer.PaintResult disabled = paintRisk(renderer, pvpKnown, false);

        assertTrue(shown.size.height > outsidePvp.size.height);
        assertTrue("incomplete value still gets an explicit unknown row",
            unknown.size.height > outsidePvp.size.height);
        assertEquals(outsidePvp.size.height, disabled.size.height);
    }

    private static DedicatedHudRenderer.PaintResult paintRisk(
        DedicatedHudRenderer renderer,
        TrackingDisplaySnapshot snapshot,
        boolean showRisk)
    {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        DedicatedHudRenderer.PaintResult result = renderer.renderTripBeacon(
            graphics,
            snapshot,
            null,
            220,
            true,
            false,
            false,
            com.gpmanager.HudPlusTextSize.NORMAL,
            90,
            5,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            DedicatedHudRenderer.ACCENT,
            true,
            false,
            false,
            false,
            null,
            false,
            1_000L,
            true,
            true,
            true,
            true,
            true,
            true,
            false,
            showRisk);
        graphics.dispose();
        return result;
    }

    @Test
    public void folioWidthHugsShortLedgerUnderAutoResize()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Oak tree", "Live", null, 1_000L, 3_000L, 0L, null, null);
        BufferedImage img = new BufferedImage(500, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        com.gpmanager.reward.SessionItemLedger ledger = new com.gpmanager.reward.SessionItemLedger();
        ledger.record(new ProfitTransaction(
            1_000L, null, TransactionType.GAIN, TrackingContext.GENERIC, "", "Woodcutting", true,
            Collections.singletonList(new ItemFlow(1511, "Oak logs", 2L, 40, 80L))));
        DedicatedHudRenderer.PaintResult hugged = renderer.renderTripBeacon(
            g, snap, null, 260, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            true, false, true, false, ledger.snapshot(0L, 5, 5), false, 1_000L,
            true, true, true, true, false);
        DedicatedHudRenderer.PaintResult fixed = renderer.renderTripBeacon(
            g, snap, null, 260, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            true, false, true, false, ledger.snapshot(0L, 5, 5), false, 1_000L,
            true, true, true, false, false);
        g.dispose();
        assertTrue(hugged.folioWidth > 0);
        assertTrue(hugged.folioWidth <= fixed.folioWidth);
        assertEquals(260, fixed.folioWidth);
    }

    @Test
    public void tripBeaconWidthHugsShortIdleUnderCeiling()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        TrackingDisplaySnapshot idle = TrackingDisplaySnapshot.from(
            null, "", "Live", null, 1_000L, 3_000L, 0L, null, null)
            .withCharacterIdle(true);
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setPinnedExpanded(true, 1_000L);
        rewards.offerObservation(
            RewardSourceKind.SKILLING,
            "Woodcutting",
            "skill|1519",
            Arrays.asList(
                new RewardItem(1519, "Willow logs", 2L, 44L, true, ItemPriceSource.GRAND_EXCHANGE),
                new RewardItem(1511, "Oak logs", 1L, 39L, true, ItemPriceSource.GRAND_EXCHANGE)),
            1_000L,
            false);
        TrackingDisplaySnapshot longTray = TrackingDisplaySnapshot.from(
            null, "Woodcutting", "Live", rewards, 1_000L, 5_000L, 0L, null, null);
        BufferedImage img = new BufferedImage(500, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DedicatedHudRenderer.PaintResult shortPaint = renderer.renderTripBeacon(
            g, idle, null, 260, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, false, 1_000L);
        DedicatedHudRenderer.PaintResult longPaint = renderer.renderTripBeacon(
            g, longTray, null, 260, true, false, true,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, true, 1_000L);
        g.dispose();
        assertTrue(shortPaint.size.width >= DedicatedHudRenderer.MIN_WIDTH);
        assertTrue(shortPaint.size.width <= 260);
        assertTrue(longPaint.size.width <= 260);
        assertTrue("Longer cassette/header should be at least as wide as Idle",
            longPaint.size.width >= shortPaint.size.width);
    }

    @Test
    public void tripBeaconChromeOffOmitsIconsAndTrayTagsHeight()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setPinnedExpanded(true, 1_000L);
        rewards.offerObservation(
            RewardSourceKind.SKILLING,
            "Woodcutting",
            "skill|1511",
            Collections.singletonList(
                new RewardItem(1511, "Oak logs", 5L, 40L, true, ItemPriceSource.GRAND_EXCHANGE)),
            1_000L,
            false);
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Woodcutting", "Live", rewards, 1_000L, 5_000L, 0L, null, null)
            .withLootCoalescing(true);
        BufferedImage img = new BufferedImage(500, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DedicatedHudRenderer.PaintResult full = renderer.renderTripBeacon(
            g, snap, null, 260, true, false, true,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, true, 1_000L,
            true, true, true, true, true, true, false);
        DedicatedHudRenderer.PaintResult chromeOff = renderer.renderTripBeacon(
            g, snap, null, 260, true, false, true,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, true, 1_000L,
            true, true, true, false, false, true, false);
        g.dispose();
        assertTrue("icons+tags off should be shorter or equal while coalesce hides right label",
            chromeOff.size.height <= full.size.height);
        assertTrue("icons off should be narrower or equal under Auto Resize",
            chromeOff.size.width <= full.size.width);
    }

    @Test
    public void tripBeaconPaintsMixedProcessBothSignedRows()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setActivityHold(true);
        ProfitTransaction shafts = new ProfitTransaction(
            1_000L, null, TransactionType.PROCESSING, TrackingContext.PRODUCTION,
            "Fletched", "Fletching", true,
            Arrays.asList(
                new ItemFlow(1511, "Logs", -1L, 100, -100L),
                new ItemFlow(52, "Arrow shaft", 15L, 5, 75L)));
        rewards.offerSkillingOrConfirmed(shafts, 1_000L, true);
        assertEquals("Fletched", HudTrayState.tagLine(rewards.current()));
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Fletching", "Live", rewards, 1_000L, 5_000L, 0L, null, null);
        BufferedImage img = new BufferedImage(500, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DedicatedHudRenderer.PaintResult paint = renderer.renderTripBeacon(
            g, snap, null, 260, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, true, 1_000L);
        g.dispose();
        assertTrue(paint.drewRevealTray);
        assertEquals(2, paint.itemRowCount);
    }

    private static TrackingDisplaySnapshot withObjectTitle(TrackingDisplaySnapshot base, String name)
    {
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.selectObject("u1", "obj:" + name, name, 1_000L);
        return base.withInteraction(ctx.snapshot("u1", 1_000L));
    }

    @Test
    public void tripBeaconCompactsHudNameButKeepsFullHoverTitle()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        InteractionContextModel ctx = new InteractionContextModel();
        ctx.npc("u1", 1, 8615, "Alchemical Hydra");
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "PvM", "Live", null, 1_000L, 3_000L, 0L, null, null)
            .withInteraction(ctx.snapshot("u1", 1_000L));
        BufferedImage img = new BufferedImage(500, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        DedicatedHudRenderer.PaintResult paint = renderer.renderTripBeacon(
            g, snap, null, 260, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, false, 1_000L, true);
        g.dispose();

        assertEquals("Alch. Hydra", HudPlusHeaderLabel.resolve(snap));
        assertEquals("Alchemical Hydra", paint.truncatedHeaderTitle);
    }

    @Test
    public void autoResizeHugsCompactHudNameNotFullSemanticName()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        InteractionContextModel fullCtx = new InteractionContextModel();
        fullCtx.npc("u1", 1, 2148, "Grand Exchange Clerk");
        InteractionContextModel shortCtx = new InteractionContextModel();
        shortCtx.npc("u2", 2, 2149, "GE Clerk");
        TrackingDisplaySnapshot full = TrackingDisplaySnapshot.from(
            null, "Banking", "Live", null, 1_000L, 3_000L, 0L, null, null)
            .withInteraction(fullCtx.snapshot("u1", 1_000L));
        TrackingDisplaySnapshot literal = TrackingDisplaySnapshot.from(
            null, "Banking", "Live", null, 1_000L, 3_000L, 0L, null, null)
            .withInteraction(shortCtx.snapshot("u2", 1_000L));
        assertEquals("GE Clerk", HudPlusHeaderLabel.resolve(full));
        BufferedImage img = new BufferedImage(500, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        DedicatedHudRenderer.PaintResult fullPaint = renderer.renderTripBeacon(
            g, full, null, 260, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, true, 1_000L, true);
        DedicatedHudRenderer.PaintResult literalPaint = renderer.renderTripBeacon(
            g, literal, null, 260, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, true, 1_000L, true);
        g.dispose();

        assertEquals("Auto-size must measure the painted compact title",
            literalPaint.size.width, fullPaint.size.width);
        assertEquals("Grand Exchange Clerk", fullPaint.truncatedHeaderTitle);
    }

    @Test
    public void willowTreeTitleUntruncatedAtDefaultCeilingWithCompactTimer()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        TrackingDisplaySnapshot snap = withObjectTitle(
            TrackingDisplaySnapshot.from(
                null, "Woodcutting", "Live", null, 1_000L, 3_000L, 0L, null, null),
            "Willow tree");
        BufferedImage img = new BufferedImage(500, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DedicatedHudRenderer.PaintResult paint = renderer.renderTripBeacon(
            g, snap, null, 260, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, false, 1_000L, true);
        g.dispose();
        assertNull(paint.truncatedHeaderTitle);
        assertEquals("Willow tree", HudPlusHeaderLabel.resolve(snap));
        assertTrue(paint.tripWidth <= DedicatedHudRenderer.MAX_WIDTH);
        assertTrue(paint.tripWidth >= DedicatedHudRenderer.MIN_WIDTH);
    }

    @Test
    public void willowTreeTitleSurvivesNarrowPreferredWidth()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        TrackingDisplaySnapshot snap = withObjectTitle(
            TrackingDisplaySnapshot.from(
                null, "Woodcutting", "Live", null, 1_000L, 3_000L, 0L, null, null),
            "Willow tree");
        BufferedImage img = new BufferedImage(500, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DedicatedHudRenderer.PaintResult paint = renderer.renderTripBeacon(
            g, snap, null, DedicatedHudRenderer.MIN_WIDTH, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, false, 1_000L, true);
        g.dispose();
        assertNull("truncated=" + paint.truncatedHeaderTitle + " w=" + paint.tripWidth,
            paint.truncatedHeaderTitle);
    }

    @Test
    public void sessionPrefixedWillowTreeExpandsPastPreferredCeiling()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        com.gpmanager.model.SessionMetrics metrics = new com.gpmanager.model.SessionMetrics(
            "Oaks", "Woodcutting", false, 4 * 3_600_000L + 10 * 60_000L,
            0L, 0L, 0L, 0L, 0L, 0, 0, 0);
        TrackingDisplaySnapshot snap = withObjectTitle(
            TrackingDisplaySnapshot.from(
                metrics, "Woodcutting", "Live", null, 1_000L, 3_000L, 0L, null, null)
                .withSessionChrome(true, "Oaks", false, 0L, 0L, false),
            "Willow tree");
        BufferedImage img = new BufferedImage(500, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DedicatedHudRenderer.PaintResult paint = renderer.renderTripBeacon(
            g, snap, null, 160, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, false, 1_000L, false);
        g.dispose();
        assertEquals("Session · Willow tree", HudPlusHeaderLabel.resolve(snap));
        assertNull("truncated=" + paint.truncatedHeaderTitle + " w=" + paint.tripWidth,
            paint.truncatedHeaderTitle);
        assertTrue(paint.tripWidth > 160);
    }

    @Test
    public void compactTimerFormatsMatchQuantityFormatter()
    {
        assertEquals("45s", TripBeaconPainter.formatElapsed(45_000L, true));
        assertEquals("10m05s", TripBeaconPainter.formatElapsed(10 * 60_000L + 5_000L, true));
        assertEquals("4h10m", TripBeaconPainter.formatElapsed(4 * 3_600_000L + 10 * 60_000L, true));
        assertEquals(com.gpmanager.util.QuantityFormatter.duration(45_000L),
            TripBeaconPainter.formatElapsed(45_000L, false));
    }

    @Test
    public void netChangeChipShowsSignedDeltaAndStaysBlankAtZero()
    {
        assertEquals("+11", TripBeaconPainter.netChangeChipText(11L));
        assertEquals("-11", TripBeaconPainter.netChangeChipText(-11L));
        assertEquals("+1.2k", TripBeaconPainter.netChangeChipText(1_200L));
        assertEquals("", TripBeaconPainter.netChangeChipText(0L));
    }

    @Test
    public void netChangeChipPaintsExpiresAndResetsWithSessionAtWideAndNarrowWidths()
    {
        long initialTime = 1_000L;
        for (int width : new int[] {DedicatedHudRenderer.MIN_WIDTH, 260})
        {
            DedicatedHudRenderer renderer = new DedicatedHudRenderer();
            TrackingDisplaySnapshot initial = netChipSnapshot("General", 100L);
            renderNetChipFrame(renderer, initial, width, initialTime);

            TrackingDisplaySnapshot changed = netChipSnapshot("General", 111L);
            BufferedImage activeChip = renderNetChipFrame(renderer, changed, width, initialTime + 100L);
            BufferedImage expiredChip = renderNetChipFrame(
                renderer, changed, width, initialTime + 3_000L);
            assertTrue("net-change chip should paint at width " + width,
                differingPixels(activeChip, expiredChip) > 0);

            DedicatedHudRenderer steadyRenderer = new DedicatedHudRenderer();
            BufferedImage steady = renderNetChipFrame(
                steadyRenderer, changed, width, initialTime + 3_000L);
            assertEquals("expired chip should match a settled render at width " + width,
                0, differingPixels(expiredChip, steady));

            TrackingDisplaySnapshot switchedSession = netChipSnapshot("Custom", 111L)
                .withSessionChrome(true, "Custom", false, 0L, 0L, false);
            BufferedImage afterSessionSwitch = renderNetChipFrame(
                renderer, switchedSession, width, initialTime + 3_100L);
            DedicatedHudRenderer newSessionRenderer = new DedicatedHudRenderer();
            BufferedImage newSessionBaseline = renderNetChipFrame(
                newSessionRenderer, switchedSession, width, initialTime + 3_100L);
            assertEquals("session switch should clear the old chip at width " + width,
                0, differingPixels(afterSessionSwitch, newSessionBaseline));
        }
    }

    private static TrackingDisplaySnapshot netChipSnapshot(String sessionName, long net)
    {
        SessionMetrics metrics = new SessionMetrics(
            sessionName, "Goblin", false, 60_000L, Math.max(0L, net), 0L, net,
            1L, 1L, 1, 0, 1);
        return TrackingDisplaySnapshot.from(metrics, "Goblin", "Live", null,
            1_000L, 3_000L, 0L, null, null)
            .withSessionChrome(false, sessionName, false, 0L, 0L, false);
    }

    private static BufferedImage renderNetChipFrame(
        DedicatedHudRenderer renderer,
        TrackingDisplaySnapshot snapshot,
        int width,
        long now)
    {
        BufferedImage image = new BufferedImage(400, 180, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        renderer.renderTripBeacon(
            graphics, snapshot, null, width, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, false, now,
            false, true, true, false, false, false, true);
        graphics.dispose();
        return image;
    }

    private static int differingPixels(BufferedImage left, BufferedImage right)
    {
        int differing = 0;
        for (int y = 0; y < left.getHeight(); y++)
        {
            for (int x = 0; x < left.getWidth(); x++)
            {
                if (left.getRGB(x, y) != right.getRGB(x, y))
                {
                    differing++;
                }
            }
        }
        return differing;
    }

    @Test
    public void traySumDeltaReadsLootValueWithoutDrivingTotalSlot()
    {
        TrackingDisplaySnapshot idle = TrackingDisplaySnapshot.from(
            null, "", "Live", null, 1_000L, 3_000L, 44L, null, null)
            .withCharacterIdle(true);
        assertEquals(0L, TripBeaconPainter.traySumDelta(idle));

        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Chicken",
            "npc:Chicken:chip",
            Collections.singletonList(
                new RewardItem(526, "Bones", 1L, 35L, true, ItemPriceSource.GRAND_EXCHANGE)),
            1_000L,
            false);
        TrackingDisplaySnapshot withTray = TrackingDisplaySnapshot.from(
            null, "Chicken", "Live", rewards, 1_000L, 5_000L, 0L, null, null);
        assertEquals(35L, TripBeaconPainter.traySumDelta(withTray));
        assertEquals("", TripBeaconPainter.trayRightLabel(withTray, withTray.getReward(), false, 1_050L));
        assertTrue(withTray.isLootCoalescing());
    }

    @Test
    public void trayRightLabelBlankOnActivityHoldNeverLiveText()
    {
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.offerSkillingOrConfirmed(
            new ProfitTransaction(
                1_000L,
                null,
                TransactionType.GAIN,
                TrackingContext.GENERIC,
                "Woodcutting",
                "tree",
                false,
                Collections.singletonList(
                    new ItemFlow(1519, "Willow logs", 2L, 22, 44L))),
            1_000L,
            false);
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Willow tree", "Live", rewards, 1_000L, 5_000L, 0L, null, null);
        assertEquals("", TripBeaconPainter.trayRightLabel(snap, snap.getReward(), true, 1_050L));
    }

    @Test
    public void longTitleExpandsToHardMaxThenEllipsizes()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        String longName = "Very Long Interaction Context Name That Must Ellipsize Past The Hard Maximum Width";
        TrackingDisplaySnapshot snap = withObjectTitle(
            TrackingDisplaySnapshot.from(
                null, "Woodcutting", "Live", null, 1_000L, 3_000L, 0L, null, null),
            longName);
        BufferedImage img = new BufferedImage(500, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DedicatedHudRenderer.PaintResult paint = renderer.renderTripBeacon(
            g, snap, null, DedicatedHudRenderer.MIN_WIDTH, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, false, 1_000L, true);
        g.dispose();
        assertEquals(DedicatedHudRenderer.MAX_WIDTH, paint.tripWidth);
        assertEquals(longName, paint.truncatedHeaderTitle);
    }

    @Test
    public void folioEmptyCopyUsesSessionCapsuleTitle()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Oak tree", "Live", null, 1_000L, 3_000L, 0L, null, null);
        com.gpmanager.reward.SessionItemLedger empty = new com.gpmanager.reward.SessionItemLedger();
        BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DedicatedHudRenderer.PaintResult paint = renderer.renderTripBeacon(
            g, snap, null, 260, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, true, false, empty.snapshot(0L, 5, 5), false, 1_000L, true);
        g.dispose();
        assertTrue(paint.size.width > paint.tripWidth);
        assertTrue(paint.size.height > 0);
    }

    @Test
    public void cassetteItemRowStaysInsideShellHeight()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setActivityHold(true);
        ProfitTransaction chop = new ProfitTransaction(
            1_000L, null, TransactionType.GAIN, TrackingContext.GENERIC,
            "Chop", "Woodcutting", true,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", 2L, 22, 44L)));
        rewards.offerSkillingOrConfirmed(chop, 1_000L, true);
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Willow tree", "Live", rewards, 1_000L, 5_000L, 44L, null, null);
        BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DedicatedHudRenderer.PaintResult paint = renderer.renderTripBeacon(
            g, snap, null, 260, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, true, 1_000L, true);
        g.dispose();
        assertTrue(paint.drewRevealTray);
        assertEquals(1, paint.itemRowCount);
        assertTrue("trip height too short for cassette row: " + paint.size.height,
            paint.size.height >= 70);
    }

    @Test
    public void cassetteHeightHugsContentWithVisibleRow()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setActivityHold(true);
        ProfitTransaction chop = new ProfitTransaction(
            1_000L, null, TransactionType.GAIN, TrackingContext.GENERIC,
            "Chop", "Woodcutting", true,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", 2L, 22, 44L)));
        rewards.offerSkillingOrConfirmed(chop, 1_000L, true);
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Willow tree", "Live", rewards, 1_000L, 5_000L, 44L, null, null);
        BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DedicatedHudRenderer.PaintResult paint = renderer.renderTripBeacon(
            g, snap, null, 260, true, false, false,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, true, 1_000L, true);
        g.dispose();
        assertTrue(paint.drewRevealTray);
        assertEquals(1, paint.itemRowCount);
        assertTrue(paint.size.height >= 70);
    }

    @Test
    public void activityHoldMergesMultipleSkillingItems()
    {
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setActivityHold(true);
        rewards.offerSkillingOrConfirmed(new ProfitTransaction(
            1_000L, null, TransactionType.GAIN, TrackingContext.GENERIC,
            "Chop", "Woodcutting", true,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", 1L, 22, 22L))), 1_000L, true);
        rewards.offerSkillingOrConfirmed(new ProfitTransaction(
            1_200L, null, TransactionType.GAIN, TrackingContext.GENERIC,
            "Nest", "Woodcutting", true,
            Collections.singletonList(new ItemFlow(5075, "Bird nest", 1L, 200, 200L))), 1_200L, true);
        assertEquals(2, rewards.current().getItems().size());
    }

    @Test
    public void peerFlashAndNpcLootShareCassettePadPath()
    {
        DedicatedHudRenderer renderer = new DedicatedHudRenderer();
        BufferedImage img = new BufferedImage(500, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        RewardPresentationModel banked = new RewardPresentationModel();
        banked.offerObservation(
            RewardSourceKind.BANKED,
            "Bank",
            "bank",
            Collections.singletonList(
                new RewardItem(995, "Coins", 100L, 100L, true, ItemPriceSource.GRAND_EXCHANGE)),
            1_000L,
            false);
        TrackingDisplaySnapshot peer = TrackingDisplaySnapshot.from(
            null, "Bank", "Live", banked, 1_000L, 5_000L, 0L, null, null);
        DedicatedHudRenderer.PaintResult peerPaint = renderer.renderTripBeacon(
            g, peer, null, 260, true, false, true,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, false, 1_000L, true);

        RewardPresentationModel npc = new RewardPresentationModel();
        npc.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Goblin",
            "npc:1",
            Arrays.asList(
                new RewardItem(526, "Bones", 1L, 35L, true, ItemPriceSource.GRAND_EXCHANGE),
                new RewardItem(995, "Coins", 5L, 5L, true, ItemPriceSource.GRAND_EXCHANGE)),
            1_000L,
            true);
        TrackingDisplaySnapshot loot = TrackingDisplaySnapshot.from(
            null, "Goblin", "Live", npc, 1_000L, 5_000L, 0L, null, null);
        DedicatedHudRenderer.PaintResult npcPaint = renderer.renderTripBeacon(
            g, loot, null, 260, true, false, true,
            com.gpmanager.HudPlusTextSize.NORMAL, 90, 5, 0,
            null, null, null, null, null, null, null, DedicatedHudRenderer.ACCENT,
            false, false, false, false, null, false, 1_000L, true);
        g.dispose();

        assertTrue(peerPaint.drewRevealTray);
        assertTrue(npcPaint.drewRevealTray);
        assertTrue(peerPaint.size.height > 40);
        assertTrue(npcPaint.size.height > peerPaint.size.height);
        assertTrue(npcPaint.itemRowCount >= 2);
    }
}
