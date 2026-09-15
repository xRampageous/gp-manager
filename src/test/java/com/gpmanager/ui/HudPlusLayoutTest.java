package com.gpmanager.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HudPlusLayoutTest
{
    @Test
    public void fittedSizeLongerEdgeEqualsSlot()
    {
        Dimension wide = HudPlusLayout.fittedSize(36, 20, 24);
        assertEquals(24, wide.width);
        assertTrue(wide.height <= 24);
        assertTrue(wide.height >= 1);

        Dimension tall = HudPlusLayout.fittedSize(12, 36, 24);
        assertEquals(24, tall.height);
        assertTrue(tall.width <= 24);

        Dimension square = HudPlusLayout.fittedSize(8, 8, 24);
        assertEquals(24, square.width);
        assertEquals(24, square.height);
    }

    @Test
    public void opaqueBoundsCropsTransparentPadding()
    {
        BufferedImage canvas = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, 36, 32);
        g.setColor(Color.WHITE);
        g.fillRect(14, 12, 8, 8);
        g.dispose();

        Rectangle bounds = HudPlusLayout.opaqueBounds(canvas);
        assertNotNull(bounds);
        assertEquals(14, bounds.x);
        assertEquals(12, bounds.y);
        assertEquals(8, bounds.width);
        assertEquals(8, bounds.height);
    }

    @Test
    public void paddedAndFullArtBothFillSlot()
    {
        int slot = 24;
        BufferedImage padded = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gp = padded.createGraphics();
        gp.setColor(new Color(255, 0, 0, 255));
        gp.fillRect(14, 12, 8, 8);
        gp.dispose();

        BufferedImage full = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gf = full.createGraphics();
        gf.setColor(new Color(0, 255, 0, 255));
        gf.fillRect(0, 0, 36, 32);
        gf.dispose();

        Rectangle paddedBounds = HudPlusLayout.opaqueBounds(padded);
        Rectangle fullBounds = HudPlusLayout.opaqueBounds(full);
        Dimension paddedFit = HudPlusLayout.fittedSize(paddedBounds.width, paddedBounds.height, slot);
        Dimension fullFit = HudPlusLayout.fittedSize(fullBounds.width, fullBounds.height, slot);

        assertEquals(slot, Math.max(paddedFit.width, paddedFit.height));
        assertEquals(slot, Math.max(fullFit.width, fullFit.height));
    }

    @Test
    public void fullyTransparentFallsBackToFullCanvasAndDrawsSafely()
    {
        BufferedImage clear = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Rectangle bounds = HudPlusLayout.opaqueBounds(clear);
        assertEquals(0, bounds.x);
        assertEquals(0, bounds.y);
        assertEquals(16, bounds.width);
        assertEquals(16, bounds.height);

        BufferedImage target = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        HudPlusLayout.drawSprite(g, clear, 0, 0, 24);
        g.dispose();
    }

    @Test
    public void containFitPreservesAspectForFloatingSlot()
    {
        // Floating default slot 18 — same contain math as tray.
        Dimension fit = HudPlusLayout.fittedSize(36, 32, 18);
        assertEquals(18, Math.max(fit.width, fit.height));
        assertTrue(fit.width == 18 || fit.height == 18);
        assertTrue(Math.abs(fit.width / (float) fit.height - 36 / 32f) < 0.08f
            || Math.abs(fit.height / (float) fit.width - 32 / 36f) < 0.08f);
    }
}
