package com.gpmanager;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

import static com.gpmanager.HudBuilderTest.NOW;
import static com.gpmanager.HudBuilderTest.config;
import static com.gpmanager.HudBuilderTest.grind;
import static com.gpmanager.HudTrayTest.flow;
import static com.gpmanager.HudTrayTest.receipt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Auto width hugs content, stops at a ceiling, and shortens what does not fit. */
public class HudSizingTest
{
    private static final Graphics2D G = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();

    @Test
    public void fitShortensWithOneEllipsisAndLeavesShortTextAlone()
    {
        FontMetrics metrics = G.getFontMetrics(FontManager.getRunescapeFont());
        assertEquals("Logs ×4", HudOverlay.fit(metrics, "Logs ×4", 200));
        String cut = HudOverlay.fit(metrics, "Superior dragon bones ×2", 60);
        assertTrue(cut, cut.endsWith("…"));
        assertTrue(metrics.stringWidth(cut) <= 60);
    }

    /** Owner 1.2: a long name shortens before its count, and "(uncharged)" reads "(u)". */
    @Test
    public void aLongNameShortensButKeepsItsCount()
    {
        GpManagerConfig config = config(true, 4);
        HudBuilder builder = new HudBuilder(config, null);
        HudOverlay overlay = new HudOverlay(builder, config);
        builder.tray.booked(receipt(NOW, TransactionType.LOOT, flow(21817, "Bracelet of ethereum (uncharged)", 2L, 90_000)), NOW, false, NOW);
        builder.tray.booked(receipt(NOW + 1L, TransactionType.LOOT, flow(1, "An extraordinarily long item name that "
            + "would never fit any sensible overlay", 3L, 10)), NOW + 1L, false, NOW + 1L);
        builder.update(grind(46L, null), f -> true, NOW + 10L);
        overlay.render(G);
        assertTrue(overlay.names[0], overlay.names[0].endsWith("… ×3"));
        assertTrue(overlay.names[1], overlay.names[1].startsWith("Bracelet of ethereum (u)"));
        assertTrue(overlay.names[1], overlay.names[1].endsWith(" ×2"));
    }

    @Test
    public void spritesCropToTheirVisiblePixels()
    {
        BufferedImage sprite = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
        assertEquals("a sprite still loading keeps its whole canvas", new Rectangle(36, 32), Kit.visible(sprite));
        Graphics2D g = sprite.createGraphics();
        g.fillRect(10, 12, 4, 6);
        g.dispose();
        assertEquals(new Rectangle(10, 12, 4, 6), Kit.visible(sprite));
    }

    @Test
    public void autoWidthHugsContentAndStopsAtTheCeiling()
    {
        GpManagerConfig config = config(true, 4);
        HudBuilder builder = new HudBuilder(config, null);
        HudOverlay overlay = new HudOverlay(builder, config);
        builder.update(grind(46L, null), f -> true, NOW);
        Dimension resting = overlay.render(G);
        assertTrue("short content leaves room below the ceiling", resting.width < config.hudMaxWidth());
        assertTrue(resting.width >= HudOverlay.MIN_WIDTH);

        builder.tray.booked(receipt(NOW, TransactionType.LOOT, flow(1, "An extraordinarily long item name that "
            + "would never fit any sensible overlay", 1L, 10)), NOW, false, NOW);
        builder.update(grind(46L, null), f -> true, NOW + 10L);
        assertEquals("long rows stop at Max width and shorten instead", config.hudMaxWidth(), overlay.render(G).width);
    }

    /** Owner 2026-09-28: GP/h sits beside "Net / target" whenever HUD+ has the room. */
    @Test
    public void theRateSharesTheNetLineWhenItFits()
    {
        GpManagerConfig config = config(true, 4);
        HudBuilder builder = new HudBuilder(config, null);
        HudOverlay overlay = new HudOverlay(builder, config);
        builder.update(grind(-3_800L, 5_000_000L), f -> true, NOW);
        assertTrue("room to spare", overlay.render(G).width < config.hudMaxWidth());
        assertEquals("no line of its own", 0, overlay.moneyY[3]);
    }

    @Test
    public void tickingDigitsNeverWobbleTheWidth()
    {
        GpManagerConfig config = config(true, 4);
        HudBuilder builder = new HudBuilder(config, null);
        HudOverlay overlay = new HudOverlay(builder, config);
        builder.interaction("Ancient Wyvern of the Long Tail", true);
        Engine engine = HudBuilderTest.engine();
        engine.startCustom("Vorkath", NOW);
        builder.update(LiveSnapshot.capture(engine, NOW + 11_000L, null),
            f -> true, NOW + 11_000L);
        int narrowDigits = overlay.render(G).width;
        assertTrue("the title needs more than the minimum", narrowDigits > HudOverlay.MIN_WIDTH);
        builder.update(LiveSnapshot.capture(engine, NOW + 18_000L, null),
            f -> true, NOW + 18_000L);
        assertEquals(narrowDigits, overlay.render(G).width);
    }

    @Test
    public void emptyFreePlayFitsTimerAndRateOnOneLineAtEveryTextSize()
    {
        for (GpManagerConfig.TextSize textSize : GpManagerConfig.TextSize.values())
        {
            GpManagerConfig config = new GpManagerConfig()
            {
                @Override
                public boolean hudHideWhenIdle() { return false; }
                @Override
                public TextSize hudTextSize() { return textSize; }
            };
            Engine engine = HudBuilderTest.engine();
            engine.ensureSession(NOW);
            HudBuilder builder = new HudBuilder(config, null);
            HudFrame snapshot = builder.update(LiveSnapshot.capture(engine, NOW + 533_000L, null),
                f -> true, NOW + 533_000L);
            assertEquals("8m53s", snapshot.timer);
            assertEquals("0/h", snapshot.rate);
            HudOverlay overlay = new HudOverlay(builder, config);
            Dimension rendered = overlay.render(G);
            assertTrue("empty " + textSize + " HUD fits below the old floor", rendered.width < 120);
            assertEquals("only one line remains", HudOverlay.PAD * 2 + G.getFontMetrics(overlay.bold()).getHeight(),
                rendered.height);
            FontMetrics metrics = G.getFontMetrics(overlay.body());
            assertTrue("timer and rate retain a gap", HudOverlay.PAD + 11 + HudOverlay.width(metrics, snapshot.timer) + 8
                <= rendered.width - HudOverlay.PAD - HudOverlay.width(metrics, snapshot.rate));
        }
    }

    /**
     * Owner live 2026-10-14: with the run's name off the HUD the rate rides the gem row, so the
     * old money-row wrap trigger is gone; the ceiling is the invariant that remains.
     */
    @Test
    public void aNarrowHudNeverDrawsWiderThanItsCeiling()
    {
        for (int ceiling : new int[] {320, 120})
        {
            GpManagerConfig config = new GpManagerConfig()
            {
                @Override
                public int hudMaxWidth() { return ceiling; }
                @Override
                public boolean reducedMotion() { return true; }
            };
            HudBuilder builder = new HudBuilder(config, null);
            builder.tray.booked(receipt(NOW, TransactionType.LOOT,
                flow(1, "Whip", 1L, 1_500_000)), NOW, false, NOW);
            builder.update(grind(123_400_000L, null), f -> false, NOW + 10L);
            HudOverlay overlay = new HudOverlay(builder, config);
            Dimension rendered = overlay.render(G);
            assertTrue("the HUD never draws wider than its ceiling", rendered.width <= ceiling);
        }
    }

    /** Owner live 2026-10-14: the kill count survives even the narrowest HUD. */
    @Test
    public void theKillCountSurvivesNarrowCeilings()
    {
        for (int ceiling : new int[] {320, 100})
        {
            GpManagerConfig config = new GpManagerConfig()
            {
                @Override
                public int hudMaxWidth() { return ceiling; }
                @Override
                public boolean reducedMotion() { return true; }
            };
            HudBuilder builder = new HudBuilder(config, null);
            builder.update(grind(1_000L, null), f -> true, NOW - 1L);
            builder.interaction("Goblin", true);
            builder.tray.kill("Goblin", NOW - 1L, false);
            builder.update(grind(1_000L, null), f -> true, NOW);
            HudOverlay overlay = new HudOverlay(builder, config);
            overlay.render(G);
            if (ceiling == 320)
            {
                assertEquals("KC: 1", overlay.title);
            }
            else
            {
                assertTrue("the count survives narrow widths",
                    overlay.title.equals("KC: 1") || overlay.title.equals("1"));
                assertFalse("never an ellipsized count", overlay.title.contains("\u2026"));
            }
        }
    }
}
