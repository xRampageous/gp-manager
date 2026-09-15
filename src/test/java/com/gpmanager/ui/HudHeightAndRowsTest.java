package com.gpmanager.ui;

import com.gpmanager.*;
import com.gpmanager.engine.*;
import com.gpmanager.party.PartyProfitTracker;
import com.gpmanager.reward.*;
import com.gpmanager.model.ItemPriceSource;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HudHeightAndRowsTest
{
    @Test public void maxHeightConfigIgnoredHeightAlwaysHugs() throws Exception
    {
        DedicatedHudRenderer.PaintResult auto = paint(0, 5, 5);
        DedicatedHudRenderer.PaintResult forced = paint(80, 5, 5);
        assertEquals("Retired Max height must not change hug height",
            auto.size.height, forced.size.height);
        assertTrue(forced.itemRowCount <= 5);
        assertTrue(forced.size.height >= 70);
    }

    @Test public void tallConfigStillHugsWhenContentIsShorter() throws Exception
    {
        DedicatedHudRenderer.PaintResult auto = paint(0, 5, 2);
        DedicatedHudRenderer.PaintResult tallCap = paint(320, 5, 2);
        assertEquals(auto.size.height, tallCap.size.height);
        assertTrue(tallCap.size.height < 320);
        assertEquals(auto.itemRowCount, tallCap.itemRowCount);
    }

    @Test public void autoHeightSizesToContent() throws Exception
    {
        DedicatedHudRenderer.PaintResult auto = paint(0, 5, 2);
        assertTrue(auto.size.height > 80);
        assertTrue(auto.size.height < 320);
    }

    @Test public void onlyConfiguredRowLimitCausesOverflow() throws Exception
    {
        DedicatedHudRenderer.PaintResult capped = paint(0, 3, 4);
        DedicatedHudRenderer.PaintResult uncapped = paint(0, 5, 4);
        assertEquals(3, capped.itemRowCount);
        assertEquals(4, uncapped.itemRowCount);
        assertTrue("Fewer visible rows yields a shorter or equal shell",
            capped.size.height <= uncapped.size.height);
    }

    @Test public void integratedFeedbackStillRespectsVisibleItemRows() throws Exception
    {
        DedicatedHudRenderer.PaintResult paint = paint(80, 3, 5, FeedbackStyle.INTEGRATED);
        assertEquals(3, paint.itemRowCount);
        DedicatedHudRenderer.PaintResult auto = paint(0, 3, 5, FeedbackStyle.INTEGRATED);
        assertTrue(paint.size.height <= auto.size.height);
        assertEquals(3, auto.itemRowCount);
    }

    private DedicatedHudRenderer.PaintResult paint(int height, int rows, int count) throws Exception
    {
        return paint(height, rows, count, FeedbackStyle.FLOATING);
    }

    private DedicatedHudRenderer.PaintResult paint(
        int height, int rows, int count, FeedbackStyle feedback) throws Exception
    {
        long now = 1900000000000L;
        GpManagerConfig config = new GpManagerConfig() {
            @Override public TrackingDisplay trackingDisplay() { return TrackingDisplay.HUD_PLUS; }
            @Override public FeedbackStyle feedbackStyle() { return feedback; }
            @Override public int hudPlusMaxHeight() { return height; }
            @Override public int hudPlusMaxRewardRows() { return rows; }
            @Override public HudPlusRewardPresentation hudPlusRewardPresentation()
            {
                return HudPlusRewardPresentation.KEEP_EXPANDED;
            }
        };
        GpManagerEngine engine = new GpManagerEngine(d -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(now);
        HudClock clock = new HudClock(); clock.fixAt(now);
        RewardPresentationModel rewards = new RewardPresentationModel();
        List<RewardItem> items = new ArrayList<>();
        for (int i=0; i<count; i++) items.add(new RewardItem(1511+i, i==0 ? "Oak logs" : "Logs " + i, 4, 148-i, true, ItemPriceSource.GRAND_EXCHANGE));
        rewards.offerObservation(RewardSourceKind.NPC_LOOT, "Test source", "test:1", items, now, false);
        TrackingDisplayModel display = new TrackingDisplayModel(engine, config, rewards, null, clock, null);
        GpManagerOverlay overlay = new GpManagerOverlay(engine, config, new PartyProfitTracker(null, null), display, clock, null);
        BufferedImage image = new BufferedImage(280, 520, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics(); overlay.render(g); g.dispose();
        java.nio.file.Path dir=java.nio.file.Paths.get("build/ui-preview"); java.nio.file.Files.createDirectories(dir);
        javax.imageio.ImageIO.write(image, "png", dir.resolve("hud-height-"+height+"-rows-"+rows+"-items-"+count+".png").toFile());
        DedicatedHudRenderer.PaintResult paint = overlay.lastDedicatedPaint();
        assertNotNull("HUD+ paint expected for Tracking display = HUD+", paint);
        return paint;
    }
}
