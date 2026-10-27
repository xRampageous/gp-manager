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
        long now = SafeMath.now();
        compactRows(out, now);
        emptyAndFiltered(out, now);
        Engine free = HudBuilderTest.engine();
        free.ensureSession(now - 61_000L);
        free.session().addTransaction(receipt(now - 1_000L, TransactionType.LOOT,
            flow(995, "Coins", 100_000L, 1)), 2_000, false);
        free.setMilestone(100_000L);
        GpManagerConfig milestoneConfig = HudBuilderTest.config(false, 4);
        HudBuilder milestone = new HudBuilder(milestoneConfig, HudPreview::icon);
        milestone.update(LiveSnapshot.capture(free, now, null), f -> true, now);
        canvas(out, "hud-milestone.png", milestone, milestoneConfig, 520, 160);
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
        Engine engine = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Item " + id, quantity, 50, quantity * 50)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.startCustom("Vorkath", now - 2_400_000L);
        engine.session().setNetTarget(5_000_000L, 0, 0);

        HudBuilder builder = new HudBuilder(config, HudPreview::icon);
        HudTray tray = builder.tray;
        builder.update(LiveSnapshot.capture(engine, now - 3_000L, null), f -> true, now - 3_000L);
        add(engine, tray, now - 1_500L, flow(22124, "Superior dragon bones", 2L, 21_920));
        add(engine, tray, now - 1_000L, flow(11286, "Draconic visage", 1L, 3_425_000));
        add(engine, tray, now - 500L, flow(1319, "Rune 2h sword", 1L, 37_900));
        builder.update(LiveSnapshot.capture(engine, now, null), f -> true, now);
        canvas(out, "hud-grind.png", builder, config, 520, 260);

        // The same loot with no target: no bar, no label (owner live 2026-10-13).
        engine.session().setNetTarget(null, 0, 0);
        builder.update(LiveSnapshot.capture(engine, now + 1L, null), f -> true, now + 1L);
        canvas(out, "hud-loot.png", builder, config, 520, 260);

        // A looted Theatre of Blood: KC: 15, the scythe and rune loot (owner live 2026-10-13).
        Engine raid = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Item " + id, quantity, 50, quantity * 50)));
            return flows;
        }, new TransactionClassifier(), config);
        raid.startCustom("Theatre of Blood", now - 2_100_000L);
        HudBuilder raidBuilder = new HudBuilder(config, HudPreview::icon);
        HudTray raidTray = raidBuilder.tray;
        raidBuilder.update(LiveSnapshot.capture(raid, now - 1_200L, null), f -> true, now - 1_200L);
        for (int i = 0; i < 15; i++)
        {
            raidTray.kill("Verzik Vitur", now - 1_000L + i, false);
        }
        // An update inside the streak records its base at the pre-chest net, so the heading can
        // show the Net per kill.
        raidBuilder.update(LiveSnapshot.capture(raid, now - 900L, null), f -> true, now - 900L);
        raidBuilder.interaction("Verzik Vitur", true);
        add(raid, raidTray, now - 300L, flow(22486, "Scythe of vitur", 1L, 1_219_300_000));
        add(raid, raidTray, now - 200L, flow(565, "Blood rune", 56L, 339));
        raidBuilder.update(LiveSnapshot.capture(raid, now, null), f -> true, now);
        canvas(out, "hud-raid.png", raidBuilder, config, 520, 320);

        // Nothing new for a while: the tray folds and HUD+ rests at its narrow width.
        HudBuilder resting = new HudBuilder(config, HudPreview::icon);
        resting.update(LiveSnapshot.capture(engine, now + 60_000L, null), f -> true, now + 60_000L);
        BufferedImage rest = new BufferedImage(200, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D r = rest.createGraphics();
        r.setColor(new Color(62, 74, 44));
        r.fillRect(0, 0, rest.getWidth(), rest.getHeight());
        r.translate(8, 8);
        new HudOverlay(resting, config).render(r);
        r.dispose();
        ImageIO.write(rest, "png", new File(out, "hud-rest.png"));

        // A time target in its final seconds: the countdown warns (owner live 2026-10-13).
        Engine timed = new Engine(deltas ->
        {
            List<Flow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new Flow(id, "Item " + id, quantity, 50, quantity * 50)));
            return flows;
        }, new TransactionClassifier(), config);
        timed.startCustom("Vorkath", now - 61_000L);
        timed.session().addTransaction(receipt(now - 500L, TransactionType.LOOT,
            flow(536, "Dragon bones", 2L, 3_930)), 2_000, false);
        timed.session().setTimeTarget(60_000L, now);
        timed.session().activeTimeTargetSetMillis = 9_000L;
        HudBuilder timedBuilder = new HudBuilder(config, HudPreview::icon);
        timedBuilder.update(LiveSnapshot.capture(timed, now, null), f -> true, now);
        canvas(out, "hud-time-warning.png", timedBuilder, config, 520, 160);
    }

    private static void compactRows(File out, long now) throws IOException
    {
        for (int mode : new int[]{0, 1, 2})
            for (int width : new int[]{120, 213, 225, 300})
            {
                GpManagerConfig config = new GpManagerConfig()
                {
                    @Override public boolean hudCompactItems() { return mode > 0; }
                    @Override public boolean hudSemiCompactValues() { return mode == 2; }
                    @Override public boolean hudHideWhenIdle() { return false; }
                    @Override public boolean reducedMotion() { return true; }
                    @Override public int hudMaxWidth() { return width; }
                };
                HudBuilder builder = new HudBuilder(config, HudPreview::icon);
                builder.tray.booked(receipt(now, TransactionType.LOOT, flow(1201, "Rune kiteshield", 1, 31_799)), now, false, now);
                builder.tray.booked(receipt(now, TransactionType.LOOT, flow(560, "Death rune", 20_000, 10_000)), now, false, now);
                builder.update(HudBuilderTest.grind(200_031_799L, null), item -> true, now + 1000);
                canvas(out, "hud-" + (mode == 2 ? "semi-compact" : mode == 1 ? "compact" : "named") + "-" + width + ".png", builder, config, 400, 180);
            }
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
        Engine engine = HudBuilderTest.engine();
        engine.ensureSession(now - 533_000L);
        HudBuilder builder = new HudBuilder(config, HudPreview::icon);
        builder.update(LiveSnapshot.capture(engine, now, null), f -> true, now);
        shot(out, "hud-empty.png", builder, config);
        builder.tray.booked(receipt(now, TransactionType.LOOT, flow(526, "Bones", 1L, 41)), now, false, now);
        engine.session().addTransaction(receipt(now, TransactionType.LOOT, flow(526, "Bones", 1L, 41)), 2_000, false);
        builder.update(LiveSnapshot.capture(engine, now + 10L, null), f -> false, now + 10L);
        shot(out, "hud-filtered.png", builder, config);
        shot(out, "hud-narrow.png", builder, new GpManagerConfig()
        {
            @Override
            public int hudMaxWidth() { return 120; }
            @Override
            public boolean reducedMotion() { return true; }
        });
    }

    /** A full-size HUD+ canvas over the game-coloured backdrop. */
    private static void canvas(File out, String name, HudBuilder builder, GpManagerConfig config, int width, int height)
            throws IOException
    {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(new Color(62, 74, 44));
        g.fillRect(0, 0, width, height);
        g.translate(8, 8);
        new HudOverlay(builder, config).render(g);
        g.dispose();
        ImageIO.write(image, "png", new File(out, name));
    }

    private static void shot(File out, String name, HudBuilder builder, GpManagerConfig config) throws IOException
    {        BufferedImage image = new BufferedImage(350, 240, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(62, 74, 44));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.translate(8, 8);
        HudOverlay overlay = new HudOverlay(builder, config);
        Dimension size = overlay.render(g);
        g.dispose();
        ImageIO.write(image.getSubimage(0, 0, size.width + 16, size.height + 16), "png", new File(out, name));
    }

    private static void add(Engine engine, HudTray tray, long at, Flow item)
    {
        engine.session().addTransaction(receipt(at, TransactionType.LOOT, item), 2_000, false);
        tray.booked(receipt(at, TransactionType.LOOT, item), at, false, at);
    }

    /** A stand-in sprite: RuneLite's item images are 36 x 32. */
    private static BufferedImage icon(int itemId, int quantity)
    {
        BufferedImage real = PreviewIcons.sprite(itemId);
        if (real != null)
        {
            return real;
        }
        BufferedImage image = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.getHSBColor((itemId % 97) / 97f, 0.5f, 0.8f));
        g.fillOval(6, 4, 24, 24);
        g.dispose();
        return image;
    }

}
