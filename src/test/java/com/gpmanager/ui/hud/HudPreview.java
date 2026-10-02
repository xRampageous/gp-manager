package com.gpmanager;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.runelite.api.Point;

import static com.gpmanager.HudTrayTest.flow;
import static com.gpmanager.HudTrayTest.receipt;

/** Paints HUD+ states over a game-coloured backdrop for `panelPreview` screenshots. */
public final class HudPreview
{
    private HudPreview()
    {
    }

    public static void write(File out) throws IOException
    {
        long now = System.currentTimeMillis();
        emptyAndFiltered(out, now);
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public int stabilizationTicks()
            {
                return 1;
            }
            @Override
            public boolean reducedMotion() { return true; }
        };
        Am engine = new Am(deltas ->
        {
            List<Ab> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Ab(id, "Item " + id, quantity, 50, quantity * 50)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.ajl("Vorkath", Cx.GENERAL, now - 2_400_000L);
        engine.getActiveSession().setProfitTargetGp(5_000_000L);

        Cp builder = new Cp(config, HudPreview::icon);
        De overlay = new De(builder, config);
        HudTray tray = builder.tray();
        builder.update(Ca.capture(engine, now - 3_000L, null), f -> true, now - 3_000L);
        add(engine, tray, now - 1_500L, flow(536, "Superior dragon bones", 2L, 12_000));
        add(engine, tray, now - 1_000L, flow(11286, "Draconic visage", 1L, 10_400_000));
        add(engine, tray, now - 500L, flow(1319, "Rune 2h sword", 1L, 37_000));
        builder.update(Ca.capture(engine, now, null), f -> true, now);

        BufferedImage image = new BufferedImage(520, 260, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(new Color(62, 74, 44));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.translate(8, 8);
        overlay.render(g);
        g.dispose();
        ImageIO.write(image, "png", new File(out, "hud-grind.png"));

        // Nothing new for a while: the tray folds and HUD+ rests at its narrow width.
        Cp resting = new Cp(config, HudPreview::icon);
        resting.update(Ca.capture(engine, now + 60_000L, null), f -> true, now + 60_000L);
        BufferedImage rest = new BufferedImage(200, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D r = rest.createGraphics();
        r.setColor(new Color(62, 74, 44));
        r.fillRect(0, 0, rest.getWidth(), rest.getHeight());
        r.translate(8, 8);
        new De(resting, config).render(r);
        r.dispose();
        ImageIO.write(rest, "png", new File(out, "hud-rest.png"));
    }

    private static void emptyAndFiltered(File out, long now) throws IOException
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public boolean hudHideWhenIdle() { return false; }
            @Override
            public boolean reducedMotion() { return true; }
        };
        Am engine = HudBuilderTest.engine();
        engine.rm(now - 533_000L);
        Cp builder = new Cp(config, HudPreview::icon);
        builder.update(Ca.capture(engine, now, null), f -> true, now);
        shot(out, "hud-empty.png", builder, config);
        builder.tray().booked(receipt(now, Ai.LOOT, flow(1, "Bones", 1L, 300)), now, false);
        engine.getActiveSession().kf(receipt(now, Ai.LOOT, flow(1, "Bones", 1L, 300)), 2_000);
        builder.update(Ca.capture(engine, now + 10L, null), f -> false, now + 10L);
        shot(out, "hud-filtered.png", builder, config);
        shot(out, "hud-narrow.png", builder, new GpManagerConfig()
        {
            @Override
            public int hudMaxWidth() { return 120; }
            @Override
            public boolean reducedMotion() { return true; }
        });
    }

    private static void shot(File out, String name, Cp builder, GpManagerConfig config) throws IOException
    {
        BufferedImage image = new BufferedImage(350, 240, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(62, 74, 44));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.translate(8, 8);
        De overlay = new De(builder, config);
        Dimension size = overlay.render(g);
        g.dispose();
        ImageIO.write(image.getSubimage(0, 0, size.width + 16, size.height + 16), "png", new File(out, name));
    }

    private static void add(Am engine, HudTray tray, long at, Ab item)
    {
        engine.getActiveSession().kf(receipt(at, Ai.LOOT, item), 2_000);
        tray.booked(receipt(at, Ai.LOOT, item), at, false);
    }

    /** A stand-in sprite: RuneLite's item images are 36 x 32. */
    private static BufferedImage icon(int itemId, int quantity)
    {
        BufferedImage image = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.getHSBColor((itemId % 97) / 97f, 0.5f, 0.8f));
        g.fillOval(6, 4, 24, 24);
        g.dispose();
        return image;
    }

}
