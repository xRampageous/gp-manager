package com.gpmanager.ui;

import com.gpmanager.*;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.reward.*;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.*;

public class InteractionContextDisplayTest
{
    @Test public void targetRevisionInvalidatesSameTimestampSnapshotWithoutChangingSession()
    {
        Harness h = new Harness();
        h.context.npc(h.owner(), 1, 41, "Chicken");
        TrackingDisplaySnapshot first = h.display.snapshot(2000L);
        assertEquals("Chicken", HudPlusHeaderLabel.resolve(first));
        h.context.npc(h.owner(), 2, 42, "Cow");
        TrackingDisplaySnapshot second = h.display.snapshot(2000L);
        assertNotSame(first, second);
        assertEquals("Cow", HudPlusHeaderLabel.resolve(second));
        assertEquals(first.getNet(), second.getNet());
        assertEquals(first.getElapsedMillis(), second.getElapsedMillis());
        assertTrue(h.engine.getActiveSession().getTransactions().isEmpty());
        assertEquals(0, h.rewards.getRevealStartCount());
    }

    @Test public void newTargetCannotRenameOrReplayPreviousSourceLoot()
    {
        Harness h = new Harness();
        h.loot();
        int reveals = h.rewards.getRevealStartCount();
        h.context.npc(h.owner(), 2, 42, "Cow");
        TrackingDisplaySnapshot snapshot = h.display.snapshot(2000L);
        assertEquals("Cow", HudPlusHeaderLabel.resolve(snapshot));
        assertEquals("", snapshot.getRewardContextLabel());
        assertEquals("Chicken", snapshot.getCompleteReward().getSourceName());
        assertEquals(1L, snapshot.getCompleteReward().bestItem().getQuantity());
        assertEquals(reveals, h.rewards.getRevealStartCount());
        assertEquals(0L, snapshot.getNet());
        h.engine.togglePause(2100L);
        assertEquals("PAUSED · Cow", HudPlusHeaderLabel.resolve(h.display.snapshot(2200L)));
    }

    @Test public void selectedObjectIsHonestAndSameSourceRewardDoesNotNeedDuplicateCaption()
    {
        Harness h = new Harness();
        h.context.selectObject(h.owner(), "1751:1:2", "Oak tree", 1000L);
        assertEquals("Oak tree", HudPlusHeaderLabel.resolve(h.display.snapshot(2000L)));
        h.loot();
        h.context.npc(h.owner(), 1, 41, "Chicken");
        assertEquals("", h.display.snapshot(2000L).getRewardContextLabel());
    }

    @Test public void rendersImmediateTargetAndDifferentlySourcedLootAtProductionWidth() throws Exception
    {
        Harness h = new Harness();
        h.context.npc(h.owner(), 1, 41, "Chicken");
        GpManagerOverlay overlay = new GpManagerOverlay(h.engine, h.config, null, h.display, h.clock, null);
        Path folder = Paths.get("build/ui-preview");
        Files.createDirectories(folder);
        render(overlay, folder.resolve("hudplus-interaction-before-loot.png"));
        h.loot();
        h.context.npc(h.owner(), 2, 42, "Cow");
        render(overlay, folder.resolve("hudplus-interaction-source-switch.png"));
        assertEquals("", h.display.snapshot(2000L).getRewardContextLabel());
    }

    private static void render(GpManagerOverlay overlay, Path file) throws Exception
    {
        BufferedImage canvas = new BufferedImage(320, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        Dimension size;
        try { size = overlay.render(g); } finally { g.dispose(); }
        assertNotNull(size);
        assertTrue(size.width >= DedicatedHudRenderer.MIN_WIDTH);
        assertTrue(size.width <= 220);
        assertTrue(size.height < 360);
        ImageIO.write(canvas.getSubimage(0, 0, size.width, size.height), "png", file.toFile());
    }

    private static final class Harness
    {
        final GpManagerConfig config = new GpManagerConfig() {
            public TrackingDisplay trackingDisplay() { return TrackingDisplay.HUD_PLUS; }
            public FeedbackStyle feedbackStyle() { return FeedbackStyle.INTEGRATED; }
            public int hudPlusWidth() { return 220; }
        };
        final GpManagerEngine engine = new GpManagerEngine(d -> Collections.emptyList(), new TransactionClassifier(), config);
        final RewardPresentationModel rewards = new RewardPresentationModel();
        final HudClock clock = new HudClock();
        final InteractionContextModel context = new InteractionContextModel();
        final TrackingDisplayModel display = new TrackingDisplayModel(engine, config, rewards, null, clock, null);
        Harness()
        {
            engine.ensureSession(1000L);
            clock.fixAt(2000L);
            display.setInteractionContext(context);
        }
        String owner() { return engine.getActiveSession().getId(); }
        void loot()
        {
            rewards.offerObservation(RewardSourceKind.NPC_LOOT, "Chicken", "enc-1",
                Collections.singletonList(new RewardItem(526, "Bones", 1L, 35L, true,
                    ItemPriceSource.GRAND_EXCHANGE)), 1500L, true);
            display.invalidate();
        }
    }
}
