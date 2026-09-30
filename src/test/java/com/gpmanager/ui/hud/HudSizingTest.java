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
import static org.junit.Assert.assertTrue;

/** Auto width hugs content, stops at a ceiling, and shortens what does not fit. */
public class HudSizingTest
{
    private static final Graphics2D G = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();

    @Test
    public void fitShortensWithOneEllipsisAndLeavesShortTextAlone()
    {
        FontMetrics metrics = G.getFontMetrics(FontManager.getRunescapeFont());
        assertEquals("Logs ×4", De.fit(metrics, "Logs ×4", 200));
        String cut = De.fit(metrics, "Superior dragon bones ×2", 60);
        assertTrue(cut, cut.endsWith("…"));
        assertTrue(metrics.stringWidth(cut) <= 60);
    }

    @Test
    public void spritesCropToTheirVisiblePixels()
    {
        BufferedImage sprite = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
        assertEquals("a sprite still loading keeps its whole canvas", new Rectangle(36, 32), De.visible(sprite));
        Graphics2D g = sprite.createGraphics();
        g.fillRect(10, 12, 4, 6);
        g.dispose();
        assertEquals(new Rectangle(10, 12, 4, 6), De.visible(sprite));
    }

    @Test
    public void autoWidthHugsContentAndStopsAtTheCeiling()
    {
        GpManagerConfig config = config(true, 4);
        Cp builder = new Cp(config, null);
        De overlay = new De(builder, config);
        builder.update(grind(46L, null), f -> true, null, NOW);
        Dimension resting = overlay.render(G);
        assertTrue("short content leaves room below the ceiling", resting.width < config.hudMaxWidth());
        assertTrue(resting.width >= De.MIN_WIDTH);

        builder.tray().booked(receipt(NOW, Ai.LOOT, flow(1, "An extraordinarily long item name that "
            + "would never fit any sensible overlay", 1L, 10)), NOW, false);
        builder.update(grind(46L, null), f -> true, null, NOW + 10L);
        assertEquals("long rows stop at Max width and shorten instead", config.hudMaxWidth(), overlay.render(G).width);
    }

    /** Owner 2026-09-28: GP/h sits beside "Net / target" whenever HUD+ has the room. */
    @Test
    public void theRateSharesTheNetLineWhenItFits()
    {
        GpManagerConfig config = config(true, 4);
        Cp builder = new Cp(config, null);
        De overlay = new De(builder, config);
        builder.update(grind(-3_800L, 5_000_000L), f -> true, null, NOW);
        assertTrue("room to spare", overlay.render(G).width < config.hudMaxWidth());
        assertEquals("no line of its own", 0, overlay.moneyY[3]);
    }

    @Test
    public void tickingDigitsNeverWobbleTheWidth()
    {
        GpManagerConfig config = config(true, 4);
        Cp builder = new Cp(config, null);
        De overlay = new De(builder, config);
        builder.interaction("Ancient Wyvern of the Long Tail");
        Am engine = HudBuilderTest.engine();
        engine.ajl("Vorkath", Cx.GENERAL, NOW);
        builder.update(Ca.capture(engine, NOW + 11_000L, null),
            f -> true, null, NOW + 11_000L);
        int narrowDigits = overlay.render(G).width;
        assertTrue("the title needs more than the minimum", narrowDigits > De.MIN_WIDTH);
        builder.update(Ca.capture(engine, NOW + 18_000L, null),
            f -> true, null, NOW + 18_000L);
        assertEquals(narrowDigits, overlay.render(G).width);
    }

    @Test
    public void hiddenLootOnlyAddsASmallCountToTheFolio()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public boolean reducedMotion() { return true; }
        };
        Cp builder = new Cp(config, null);
        builder.tray().booked(receipt(NOW, Ai.LOOT, flow(1, "Bones", 1L, 300)), NOW, false);
        Cb snapshot = builder.update(grind(300L, null), f -> false, null, NOW + 10L);
        assertTrue(snapshot.rows.isEmpty());
        assertTrue(snapshot.folio.stream().anyMatch(line -> "Hidden loot:".equals(line.label)
            && "1".equals(line.value)));
        assertEquals("a hidden count never opens an empty tray", 0f,
            De.axf(snapshot, snapshot.openedAt + Cp.FADE_MILLIS, true), 0f);
        De overlay = new De(builder, config);
        BufferedImage image = new BufferedImage(320, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        Dimension rendered = overlay.render(graphics);
        graphics.dispose();
        assertEquals("filtered loot leaves the HUD compact", overlay.restHeight, rendered.height);
        assertEquals("filtering never changes earned Net", "+300", snapshot.net);

        Cb folded = builder.update(grind(300L, null), f -> false, null, NOW + 60_000L);
        assertTrue("the folio retains the count after the tray folds", folded.folio.stream()
            .anyMatch(line -> "Hidden loot:".equals(line.label) && "1".equals(line.value)));
    }

    @Test
    public void emptyFreePlayFitsTimerAndRateOnOneLineAtEveryTextSize()
    {
        for (GpManagerConfig.Cr textSize : GpManagerConfig.Cr.values())
        {
            GpManagerConfig config = new GpManagerConfig()
            {
                @Override
                public boolean hudHideWhenIdle() { return false; }
                @Override
                public Cr hudTextSize() { return textSize; }
            };
            Am engine = HudBuilderTest.engine();
            engine.rm(NOW);
            Cp builder = new Cp(config, null);
            Cb snapshot = builder.update(Ca.capture(engine, NOW + 533_000L, null),
                f -> true, null, NOW + 533_000L);
            assertEquals("8m53s", snapshot.timer);
            assertEquals("0/h", snapshot.rate);
            De overlay = new De(builder, config);
            Dimension rendered = overlay.render(G);
            assertTrue("empty " + textSize + " HUD fits below the old floor", rendered.width < 120);
            assertEquals("only one line remains", De.PAD * 2 + G.getFontMetrics(overlay.bold()).getHeight(),
                rendered.height);
            FontMetrics metrics = G.getFontMetrics(overlay.body());
            assertTrue("timer and rate retain a gap", De.PAD + 11 + De.width(metrics, snapshot.timer) + 8
                <= rendered.width - De.PAD - De.width(metrics, snapshot.rate));
        }
    }

    @Test
    public void aNarrowHudWrapsMoneyInsteadOfDrawingItOverTheRate()
    {
        int wideHeight = 0;
        for (int ceiling : new int[] {320, 120})
        {
            GpManagerConfig config = new GpManagerConfig()
            {
                @Override
                public int hudMaxWidth() { return ceiling; }
                @Override
                public boolean reducedMotion() { return true; }
            };
            Cp builder = new Cp(config, null);
            builder.tray().booked(receipt(NOW, Ai.LOOT,
                flow(1, "Whip", 1L, 1_500_000)), NOW, false);
            builder.update(grind(1_500_000L, 100_000_000L), f -> false, null, NOW + 10L);
            De overlay = new De(builder, config);
            Dimension rendered = overlay.render(G);
            assertTrue(rendered.width <= ceiling);
            if (ceiling == 320) wideHeight = rendered.height;
            else assertTrue("Net, target, change and rate need separate lines at 120 pixels",
                rendered.height > wideHeight);
        }
    }
}
