package com.gpmanager.ui;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HudDropRowComponentTest
{
    @Test
    public void truncatePreservesReadableSuffixWithinPixelBudget()
    {
        BufferedImage image = new BufferedImage(200, 40, BufferedImage.TYPE_INT_ARGB);
        FontMetrics metrics = image.createGraphics().getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        String truncated = HudDropRowComponent.truncateToWidth(
            "Super long dragon hunter crossbow name", metrics, 80);
        assertTrue(truncated.endsWith("…"));
        assertTrue(metrics.stringWidth(truncated) <= 80);
        assertEquals("Oak logs ×2",
            HudDropRowComponent.truncateToWidth("Oak logs ×2", metrics, 200));
    }

    @Test
    public void iconSlotConstantMatchesReservedHudBudget()
    {
        assertEquals(18, HudDropRowComponent.ICON_SLOT);
    }
}
