package com.gpmanager;

import java.awt.image.BufferedImage;
import java.util.Collections;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Owner 2026-10-01 (F27): a sprite that arrives after its row was drawn must rebuild the rows. */
public class LateSpriteRefreshTest
{
    @Test
    public void aLateSpriteLandsInTheCacheAndForcesARefresh() throws Exception
    {
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
        SidebarPanel[] holder = new SidebarPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new SidebarPanel(engine, new GpManagerConfig() {}, null));
        SidebarPanel panel = holder[0];
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);

        SwingUtilities.invokeAndWait(() ->
        {
            panel.pageForced = false;
            panel.spriteLoaded(-4242, image);
            assertTrue("a late sprite asks the pages to rebuild their rows", panel.pageForced);
        });

        assertSame("the arrived sprite serves the row lookup", image, panel.sprite(-4242));
    }
}
