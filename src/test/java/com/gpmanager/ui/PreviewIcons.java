package com.gpmanager;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Development-only preview sprites (owner live 2026-10-13): real item images fetched by
 * {@code tools/fetch_preview_icons.py} into build/preview-icons/ (never committed and never part
 * of the published plugin). A missing file keeps the fixture's own dummy sprite, so previews run
 * anywhere; on a machine with the icons fetched, the screenshots read like real gameplay for the
 * README.
 */
public final class PreviewIcons
{
    private static final Map<Integer, BufferedImage> CACHE = new HashMap<>();

    private PreviewIcons()
    {
    }

    /** RuneLite's item box is 36 x 32; wiki renders are fitted inside it, never upscaled. */
    public static BufferedImage sprite(int itemId)
    {
        if (CACHE.containsKey(itemId))
        {
            return CACHE.get(itemId);
        }
        BufferedImage loaded = load(itemId);
        CACHE.put(itemId, loaded);
        return loaded;
    }

    private static BufferedImage load(int itemId)
    {
        try
        {
            File file = new File("build/preview-icons/" + itemId + ".png");
            if (!file.isFile())
            {
                return null;
            }
            BufferedImage source = ImageIO.read(file);
            if (source == null)
            {
                return null;
            }
            double scale = Math.min(1d, Math.min(36d / source.getWidth(), 32d / source.getHeight()));
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage out = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, (36 - width) / 2, (32 - height) / 2, width, height, null);
            g.dispose();
            return out;
        }
        catch (Exception unavailable)
        {
            return null;
        }
    }
}
