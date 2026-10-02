package com.gpmanager;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static com.gpmanager.HudBuilderTest.NOW;
import static com.gpmanager.HudTrayTest.flow;
import static com.gpmanager.HudTrayTest.receipt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Owner 1.1: every icon is cropped, fitted to one box and centred; icons can be turned off. */
public class IconFitTest
{
    private static BufferedImage sprite(int x, int y, int w, int h)
    {
        BufferedImage image = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(x, y, w, h);
        g.dispose();
        return image;
    }

    @Test
    public void aLargeSpriteIsCroppedScaledDownAndCentred()
    {
        BufferedImage fitted = Kit.fitIcon(sprite(2, 5, 30, 10), Kit.ICON_BOX);
        assertEquals(Kit.ICON_BOX, fitted.getWidth());
        assertEquals(Kit.ICON_BOX, fitted.getHeight());
        assertEquals("30 x 10 scales to 24 x 8, centred", new Rectangle(0, 8, 24, 8), Kit.visible(fitted));
    }

    @Test
    public void aSmallSpriteIsNeverBlownUp()
    {
        BufferedImage fitted = Kit.fitIcon(sprite(10, 12, 4, 6), Kit.ICON_BOX);
        assertEquals(new Rectangle(10, 9, 4, 6), Kit.visible(fitted));
    }

    @Test
    public void iconsOffLeavesTrayRowsWithoutSprites()
    {
        for (boolean show : new boolean[] {true, false})
        {
            GpManagerConfig config = new GpManagerConfig()
            {
                @Override public boolean hudHideWhenIdle() { return false; }
                @Override public boolean showItemIcons() { return show; }
            };
            Cp builder = new Cp(config, (id, quantity) -> sprite(2, 2, 20, 20));
            builder.update(HudBuilderTest.grind(1_000L, null), f -> true, NOW);
            builder.tray().booked(receipt(NOW + 1L, Ai.LOOT, flow(1420, "Iron mace", 1L, 42)), NOW + 1L, false);
            Cb s = builder.update(HudBuilderTest.grind(1_042L, null), f -> true, NOW + 2L);
            if (show) assertNotNull(s.rows.get(0).icon);
            else assertNull("item icons off", s.rows.get(0).icon);
        }
    }
}
