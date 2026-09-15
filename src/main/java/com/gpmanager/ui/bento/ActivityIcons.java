package com.gpmanager.ui.bento;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Activities and session categories map to an item sprite (RuneLite exposes no NPC sprites)
 * with a glyph fallback. Presentation only.
 */
public final class ActivityIcons
{
    private ActivityIcons()
    {
    }

    /** Item id whose sprite stands for the activity, or -1 for a glyph. */
    public static int itemFor(@Nullable String activity, @Nullable String category)
    {
        String a = activity == null ? "" : activity.toLowerCase(Locale.ROOT);
        if (a.contains("vorkath")) return 21907;         // Vorkath's head
        if (a.contains("zulrah")) return 12934;          // Zulrah's scales
        if (a.contains("hydra")) return 22973;           // Hydra's eye
        if (a.contains("cerberus")) return 13247;        // Hellpuppy
        if (a.contains("kraken")) return 12004;          // Kraken tentacle
        if (a.contains("chambers") || a.contains("cox") || a.contains("xeric")) return 20851; // Olmlet
        if (a.contains("theatre") || a.contains("tob") || a.contains("blood")) return 22473;  // Lil' Zik
        if (a.contains("tombs") || a.contains("toa") || a.contains("amascut")) return 27352;  // Tumeken's guardian
        if (a.contains("nightmare") || a.contains("phosani")) return 24491;
        if (a.contains("gauntlet")) return 23757;
        if (a.contains("wintertodt")) return 20693;      // Phoenix
        if (a.contains("tempoross")) return 25602;
        if (a.contains("zalcano")) return 23760;
        if (a.contains("agility")) return 11849;         // Mark of grace
        if (a.contains("woodcut") || a.contains("logs")) return 1511;
        if (a.contains("mining") || a.contains("ore")) return 440;
        if (a.contains("fishing")) return 317;
        if (a.contains("runecraft") || a.contains("rune")) return 554;
        if (a.contains("herblore")) return 249;
        if (a.contains("crafting")) return 2357;
        if (a.contains("smithing")) return 2351;
        if (a.contains("fletching")) return 52;
        if (a.contains("cooking")) return 385;
        if (a.contains("farming")) return 5318;
        if (a.contains("hunter")) return 10012;
        if (a.contains("thieving")) return 1523;
        if (a.contains("firemaking")) return 590;
        if (a.contains("construction")) return 8778;
        if (a.contains("prayer")) return 526;
        if (a.contains("slayer")) return 11864;          // Slayer helmet
        if (a.contains("clue")) return 405;              // Casket
        if (a.contains("pking") || a.contains("pvp") || a.contains("edge")) return -1;
        String c = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (c.contains("raid")) return 20851;
        if (c.contains("slayer")) return 11864;
        if (c.contains("skill")) return 1511;
        if (c.contains("boss") || c.contains("pvm")) return 4151;
        if (c.contains("trad")) return 995;
        return -1;
    }

    public static String glyphFor(@Nullable String activity, @Nullable String category)
    {
        String a = ((activity == null ? "" : activity) + " " + (category == null ? "" : category)).toLowerCase(Locale.ROOT);
        if (a.contains("pk") || a.contains("pvp") || a.contains("wild")) return "☠";
        if (a.contains("raid")) return "⛫";
        if (a.contains("slayer")) return "⚔";
        if (a.contains("skill")) return "⚒";
        if (a.contains("trad") || a.contains("market")) return "⇄";
        if (a.contains("free play")) return "◎";
        return "◈";
    }

    public static Color tintFor(@Nullable String activity, @Nullable String category)
    {
        String a = ((activity == null ? "" : activity) + " " + (category == null ? "" : category)).toLowerCase(Locale.ROOT);
        if (a.contains("pk") || a.contains("pvp") || a.contains("wild")) return BentoTheme.PVP;
        if (a.contains("raid")) return BentoTheme.QUIET;
        if (a.contains("skill")) return BentoTheme.INFO;
        if (a.contains("free play")) return BentoTheme.MUTED;
        return BentoTheme.accentColor();
    }

    /** An icon for a row: sprite when the map knows the activity and the sprite loaded, glyph otherwise. */
    public static Icon icon(@Nullable String activity, @Nullable String category, Function<Integer, BufferedImage> sprites)
    {
        int id = itemFor(activity, category);
        BufferedImage img = id > 0 && sprites != null ? sprites.apply(id) : null;
        return Icon.sprite(img, glyphFor(activity, category), tintFor(activity, category));
    }
}
