package com.gpmanager;

import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.party.PartyProfitTracker;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.ui.HudClock;
import com.gpmanager.ui.TrackingDisplayModel;
import com.gpmanager.ui.TrackingDisplaySnapshot;
import java.awt.Color;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * HUD+-owned colours (hudPlusProfitColor/hudPlusLossColor/hudPlusNeutralColor/
 * hudPlusHeaderColor/hudPlusLabelColor) must always drive the dedicated HUD+
 * renderer, independent of Theme (HUD+ and HUD) and of the legacy HUD's
 * infoBoxCustom* keys.
 */
public class GpManagerOverlayHudPlusColorTest
{
    @Test
    public void hudPlusColorsAreUsedRegardlessOfTheme()
    {
        Color profit = new Color(10, 200, 10);
        Color loss = new Color(200, 10, 10);
        Color neutral = new Color(9, 9, 9);
        Color header = new Color(50, 50, 50);
        Color label = new Color(60, 60, 60);

        for (InfoBoxTheme theme : InfoBoxTheme.values())
        {
            GpManagerOverlay overlay = overlayFor(theme, profit, loss, neutral, header, label);
            GpManagerOverlay.ThemeColors colors = overlay.resolveDedicatedColors(emptySnapshot());
            assertEquals("theme=" + theme, profit, colors.positive);
            assertEquals("theme=" + theme, loss, colors.negative);
            assertEquals("theme=" + theme, neutral, colors.value);
            assertEquals("theme=" + theme, header, colors.header);
            assertEquals("theme=" + theme, label, colors.label);
        }
    }

    @Test
    public void hudPlusHeaderDefaultsToNeutralValueColor()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public TrackingDisplay trackingDisplay() { return TrackingDisplay.HUD_PLUS; }
        };
        // Trip Beacon: header shares the bright neutral/value default; labels stay muted.
        assertEquals(config.hudPlusNeutralColor(), config.hudPlusHeaderColor());
        assertNotEquals(config.hudPlusLabelColor(), config.hudPlusHeaderColor());
    }

    @Test
    public void legacyCustomColorsRemainIndependentOfHudPlusColors()
    {
        Color hudPlusProfit = new Color(10, 200, 10);
        Color legacyCustomProfit = new Color(1, 1, 1);
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public TrackingDisplay trackingDisplay() { return TrackingDisplay.HUD_PLUS; }
            @Override public InfoBoxTheme infoBoxTheme() { return InfoBoxTheme.CUSTOM; }
            @Override public Color hudPlusProfitColor() { return hudPlusProfit; }
            @Override public Color infoBoxCustomProfitColor() { return legacyCustomProfit; }
        };
        GpManagerOverlay overlay = overlay(config);
        GpManagerOverlay.ThemeColors dedicated = overlay.resolveDedicatedColors(emptySnapshot());
        assertEquals(hudPlusProfit, dedicated.positive);
        assertNotEquals(legacyCustomProfit, dedicated.positive);
    }

    @Test
    public void detailedInfoBoxDoesNotRepeatActivityAfterTitleCompaction()
    {
        // The visible title may be "GE Clerk", but duplicate detection uses the full title.
        assertFalse(GpManagerOverlay.shouldShowActivityLine(
            "Grand Exchange Clerk", "Grand Exchange Clerk"));
        assertTrue(GpManagerOverlay.shouldShowActivityLine("Banking", "Woodcutting"));
        assertFalse(GpManagerOverlay.shouldShowActivityLine("AFK", " "));
    }

    private static GpManagerOverlay overlayFor(
        InfoBoxTheme theme, Color profit, Color loss, Color neutral, Color header, Color label)
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public TrackingDisplay trackingDisplay() { return TrackingDisplay.HUD_PLUS; }
            @Override public InfoBoxTheme infoBoxTheme() { return theme; }
            @Override public Color hudPlusProfitColor() { return profit; }
            @Override public Color hudPlusLossColor() { return loss; }
            @Override public Color hudPlusNeutralColor() { return neutral; }
            @Override public Color hudPlusHeaderColor() { return header; }
            @Override public Color hudPlusLabelColor() { return label; }
        };
        return overlay(config);
    }

    private static GpManagerOverlay overlay(GpManagerConfig config)
    {
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        HudClock clock = new HudClock();
        TrackingDisplayModel display = new TrackingDisplayModel(
            engine, config, new RewardPresentationModel(), null, clock, null);
        return new GpManagerOverlay(engine, config, new PartyProfitTracker(null, null), display, clock, null);
    }

    private static TrackingDisplaySnapshot emptySnapshot()
    {
        return new TrackingDisplaySnapshot(
            0L, 0L, 0L, false, false, "", "Live", null, null,
            com.gpmanager.reward.RewardPresentationPhase.NONE, false, 1f, 0L, null, null);
    }
}
