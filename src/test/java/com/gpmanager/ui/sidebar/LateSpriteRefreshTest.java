package com.gpmanager;

import java.awt.image.BufferedImage;
import java.util.Collections;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Owner 2026-10-01 (F27): a sprite that arrives after its row was drawn must rebuild the rows. */
public class LateSpriteRefreshTest
{
    @Test
    public void aLateSpriteLandsInTheCacheAndForcesARefresh() throws Exception
    {
        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
        Dp[] holder = new Dp[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new Dp(engine, new GpManagerConfig() {}, null));
        Dp panel = holder[0];
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);

        SwingUtilities.invokeAndWait(() ->
        {
            panel.pageForced = false;
            panel.axu(-4242, image);
            assertTrue("a late sprite asks the pages to rebuild their rows", panel.pageForced);
        });

        BufferedImage served = panel.sprite(-4242);
        assertNotNull("the arrived sprite serves the row lookup", served);
        assertEquals("owner 1.1: every sidebar icon is fitted to one box", Kit.ICON_BOX, served.getWidth());
        assertEquals(Kit.ICON_BOX, served.getHeight());
    }
}
