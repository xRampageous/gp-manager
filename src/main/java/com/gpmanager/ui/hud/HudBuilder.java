package com.gpmanager;

import static com.gpmanager.Fmt.*;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Kit.Tone.*;
import static com.gpmanager.SafeMath.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static java.util.Locale.*;

import com.gpmanager.HudFrame.*;
import com.gpmanager.HudTray.*;
import com.gpmanager.Kit.Tone;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;

/**
 * Builds one {@link HudFrame} per game tick from the same Live snapshot the sidebar paints, plus
 * the tray and the moments. It reads, formats and publishes; it never books anything.
 */
@RequiredArgsConstructor
class HudBuilder {
    static final Color GOLD = new Color(255, 200, 40);

    /** The tray slides open and shut over this long; the chip fades out over it. */
    static final long FADE_MILLIS = 300L;

    /** The chip beside Net shows each booking's change this long, independent of the tray. */
    static final long CHIP_MILLIS = 4_000L;

    /** Item sprites by id and stack size, so coins show their pile rather than one coin. */
    interface Icons {
        BufferedImage of(int itemId, int quantity);
    }

    /** Counts at which stack sprites (coins) change; bucketing keeps the sprite cache small. */
    static final int[] STACKS = {10_000, 1_000, 250, 100, 25, 5, 4, 3, 2};

    final GpManagerConfig config;
    final Icons icons;
    final HudTray tray = new HudTray();
    String sessionOwner = "";
    Color lineColor = DIM.color;
    long trayOpenedAt;

    /** The streak the per-kill line measures, and Net just before it began. */
    String baseKey;

    long streakBase;

    /** The streak before, so a merged mixed streak measures from where it first began. */
    String prevKey;

    long prevBase;
    long lastNet;
    volatile String interaction = "";
    volatile HudFrame current = HudFrame.HIDDEN;

    HudTray tray() {
        return tray;
    }

    HudFrame current() {
        return current;
    }

    /**
     * A factory reset replaced the bound profile: every presentation trace of the old trip is
     * dropped, so HUD+ comes back exactly as on a new install. EDT only.
     */
    void resetPresentation() {
        tray.clear();
        sessionOwner = "";
        lineColor = DIM.color;
        trayOpenedAt = 0L;
        baseKey = null;
        current = HudFrame.HIDDEN;
    }

    long stayMillis() {
        return config.hudTraySeconds() * 1_000L;
    }

    boolean keepSession() {
        return config.hudTrayKeeps() == GpManagerConfig.HudTrayKeeps.SESSION;
    }

    /** The NPC the player is fighting; empty when released or not a fight. */
    void interaction(String name, boolean combat) {
        interaction = name == null || !combat ? "" : name.trim();
    }

    /** The fight target; the shared activity label reads only whether there is one. */
    String target() {
        return interaction;
    }

    /**
     * @param visible the display loot filter; hidden rows stay unnamed, and only their count shows
     */
    /** The panel's display filter, for highlighted names; null highlights nothing. */
    RecentFilter filter;

    HudFrame update(LiveSnapshot snapshot, Predicate<Flow> visible, long now) {
        String owner = snapshot.sessionId;
        if (!owner.equals(sessionOwner)) {
            // Free play to a Grind (or back) starts a clean tray; the first sighting keeps what is
            // there.
            if (!sessionOwner.isEmpty()) tray.clear();
            sessionOwner = owner;
            baseKey = null;
            trayOpenedAt = 0L;
        }
        boolean grind = snapshot.hasSession && !snapshot.freePlay;
        // A new streak measures its Net from the last tick before its first event.
        String streakKey = tray.streakKey();
        if (!streakKey.equals(baseKey)) {
            long base =
                    streakKey.equals(prevKey) ? prevBase : baseKey == null ? snapshot.net : lastNet;
            prevKey = baseKey;
            prevBase = streakBase;
            streakBase = base;
            baseKey = streakKey;
        }
        lastNet = snapshot.net;
        // Nothing booked yet reads as a dash, never a zero (the sidebar's first-run rule).
        boolean booked =
                snapshot.hasSession
                        && (snapshot.gains != 0L
                                || snapshot.costs != 0L
                                || snapshot.marketResult != 0L
                                || !snapshot.recent.isEmpty());
        tray.keepMillis(config.streakEndSeconds() * 1_000L);
        // "Hide when not tracking": nothing to track, logged out or paused hides HUD+; Free play
        // shows
        // once something is booked (observed, unbooked tray activity does not count).
        boolean live =
                snapshot.hasSession && !snapshot.loggedOut && !snapshot.paused && !snapshot.idle;
        boolean shown =
                config.showHud()
                        && (!config.hudHideWhenIdle()
                                || live && (grind || booked || tray.hasBooked()));
        if (!shown) {
            current = HudFrame.HIDDEN;
            return current;
        }
        boolean tracking = snapshot.hasSession && !snapshot.loggedOut;
        Gem gem =
                !tracking
                        ? Gem.OFF
                        : snapshot.paused && !snapshot.idle
                                ? Gem.PAUSED
                                : snapshot.idle
                                        ? Gem.AWAY
                                        : snapshot.pvpPossible ? Gem.PVP : Gem.LIVE;
        String target = "";
        double progress = -1d;
        boolean targetWarning = false;
        boolean targetDone = false;
        if (snapshot.goal != null) {
            LiveSnapshot.Goal goal = snapshot.goal;
            // Owner live 2026-10-13: the target rides the progress bar's end, not the money row,
            // so "/ 200k" never stretches HUD+ wide. A time target shows its live countdown
            // there and warns in its final seconds. A Net aim's label counts down what is left
            // while the bar fills (owner live 2026-10-14); its own timer's countdown replaces the
            // label only in the final seconds it warns for, and a paused timer never warns.
            long left = max(0L, goal.target - goal.current);
            long remaining = goal.time ? left : goal.timeRemainingMillis;
            int warnSeconds = config.hudTargetWarningSeconds();
            targetWarning =
                    warnSeconds > 0
                            && !goal.timerPaused
                            && remaining > 0L
                            && remaining <= warnSeconds * 1000L;
            target = goal.time || targetWarning ? durationCount(remaining) : compact(left);
            progress = !goal.time && goal.current <= 0L ? 0d : max(0d, min(1d, goal.fraction));
            targetDone = goal.reached;
        }
        String rate =
                !snapshot.hasSession
                        ? "—"
                        : LivePage.rateText(snapshot.rateEstablished, snapshot.gpPerHour);
        boolean always = config.hudTray() == GpManagerConfig.HudTray.ALWAYS_OPEN;
        // Owner 2026-10-03: hidden loot never opens or holds the tray open; an open tray's footer
        // counts it.
        var listed = new ArrayList<Entry>();
        long shownAt = 0L;
        int hidden = 0;
        for (Entry entry : tray.entries()) {
            if (visible.test(flowOf(entry))) {
                listed.add(entry);
                shownAt = max(shownAt, entry.touched);
            } else if (entry.state != State.TRADED) {
                hidden++;
            }
        }
        boolean open = !listed.isEmpty() && (always || now - shownAt < stayMillis() + FADE_MILLIS);
        trayOpenedAt = !open ? 0L : trayOpenedAt == 0L ? now : trayOpenedAt;
        var rows = new ArrayList<Row>();
        // The chip beside Net is the newest item's change; the tray row keeps the running total.
        long chipUntil = tray.lastDeltaAt() == 0L ? 0L : tray.lastDeltaAt() + CHIP_MILLIS;
        long trip = now < chipUntil ? tray.lastDelta() : 0L;
        // A big drop keeps its row however much cheaper loot follows it; "+N more" takes the rest.
        int plain = config.hudTrayRows();
        for (Entry entry : listed) if (entry.gold) plain--;
        for (Entry entry : listed) {
            if (open && rows.size() < config.hudTrayRows() && (entry.gold || plain-- > 0))
                rows.add(row(entry));
        }
        int more = open ? listed.size() - rows.size() : 0;
        String footer = more > 0 ? "+" + more + " more" : "";
        if (open && hidden > 0)
            footer = (footer.isEmpty() ? "" : footer + " · ") + "+" + hidden + " hidden";
        String context = contextLine(snapshot);
        current =
                new HudFrame(
                        true,
                        gem,
                        title(snapshot, now),
                        snapshot.hasSession ? timer(snapshot.elapsedMillis) : "",
                        booked ? signed(snapshot.net) : "—",
                        booked ? tone(snapshot.net) : DIM.color,
                        target,
                        progress,
                        targetWarning,
                        targetDone,
                        trip != 0L ? signed(trip) : "",
                        tone(trip),
                        chipUntil,
                        rate,
                        context,
                        lineColor,
                        trayLabel(snapshot, now),
                        rows,
                        footer,
                        tray.tripId(),
                        always ? 0L : shownAt + stayMillis(),
                        trayOpenedAt,
                        now);
        return current;
    }

    /**
     * Owner 1.1: HUD+ stays clean. A running kill streak reads "KC: 12" (spawns and side targets
     * aside, and only while the fight is fresh); the run's name lives on the sidebar only (owner
     * live 2026-10-14).
     */
    String title(LiveSnapshot snapshot, long now) {
        Map.Entry<String, Integer> streak = tray.streak(now);
        if (streak != null && snapshot.hasSession && !interaction.isEmpty())
            return "KC: " + streak.getValue();
        return "";
    }

    /**
     * Needs review, else Reclaim waiting. Pace, PvP streak, skull and risk live in the sidebar
     * (owner 2026-09-28, 1.1); the PvP gem marks danger. Sets {@link #lineColor}.
     */
    String contextLine(LiveSnapshot snapshot) {
        lineColor = DIM.color;
        if (snapshot.reviewCount > 0) {
            lineColor = REVIEW.color;
            return "(!) "
                    + snapshot.reviewCount
                    + (snapshot.reviewCount == 1 ? " item needs review" : " items need review");
        }
        if (snapshot.reclaimItems > 0L) {
            return "Reclaim waiting · "
                    + snapshot.reclaimItems
                    + (snapshot.reclaimItems == 1L ? " item" : " items");
        }
        if (snapshot.calibrating) return "Calibrating\u2026";
        return "";
    }

    static boolean counted(Entry entry) {
        return entry.state != State.PENDING && entry.state != State.TRADED;
    }

    static Flow flowOf(Entry entry) {
        // A hidden item stays off the tray whichever way it moved (owner 2026-09-28: a dropped one
        // too).
        return new Flow(
                entry.itemId,
                entry.name,
                entry.quantity,
                (int) min(Integer.MAX_VALUE, entry.unitPrice),
                entry.value);
    }

    Row row(Entry entry) {
        // "(u)" is the game's own short form for uncharged, as in "Craw's bow (u)" (owner 1.2).
        return new Row(
                icon(entry),
                entry.name.replace(" (uncharged)", " (u)")
                        + (entry.itemId == 995 ? "" : " " + times(entry.quantity)),
                entry.state.tag,
                value(entry),
                color(entry),
                entry.gold,
                entry.born,
                filter == null ? null : filter.highlight(entry.name, entry.quantity));
    }

    static String value(Entry entry) {
        // A clue drop is worth nothing until its casket opens: no value, never "?" (owner
        // 2026-10-09).
        return entry.value == 0L && entry.unitPrice == 0L
                ? entry.worthless || GameData.pattern("clue").matcher(entry.name).matches() ? "" : "?"
                : counted(entry) || entry.state == State.TRADED
                        ? signed(entry.value)
                        : compact(entry.value);
    }

    static Color color(Entry entry) {
        return entry.gold
                ? GOLD
                : entry.state == State.LOST
                        ? LOSS.color
                        : entry.state == State.TRADED
                                ? MARKET.color
                                : counted(entry) ? GAIN.color : DIM.color;
    }

    /**
     * The tray heading; a kill streak adds its Net per kill (owner 1.1: the hover panel is gone).
     */
    String trayLabel(LiveSnapshot snapshot, long now) {
        Map.Entry<String, Integer> streak = tray.streak(now);
        return streak == null || streak.getValue() < 2 || !snapshot.hasSession
                ? tray.label()
                : tray.label()
                        + " \u00b7 "
                        + signed((snapshot.net - streakBase) / streak.getValue())
                        + "/kill";
    }

    BufferedImage icon(Entry entry) {
        return icons == null || entry.itemId <= 0 || !config.showItemIcons()
                ? null
                : icons.of(entry.itemId, stack(entry.quantity));
    }

    static int stack(long quantity) {
        for (int stack : STACKS) {
            if (quantity >= stack) return stack;
        }
        return 1;
    }

    String timer(long millis) {
        if (config.hudTimer() == GpManagerConfig.HudTimer.Full) return clock(millis);
        long total = nonNeg(millis) / 1000L;
        long hours = total / 3600L;
        long minutes = total % 3600L / 60L;
        return hours > 0L
                ? String.format(ROOT, "%dh%02dm", hours, minutes)
                : String.format(ROOT, "%dm%02ds", minutes, total % 60L);
    }

    static Color tone(long value) {
        return Tone.sign(value).color;
    }
}

/**
 * Everything HUD+ paints, built once per game tick by {@link HudBuilder}. Strings are already
 * formatted and colours already chosen, so painting only reads fields.
 */
@RequiredArgsConstructor
class HudFrame {
    static final HudFrame HIDDEN =
            new HudFrame(
                    false,
                    Gem.OFF,
                    "",
                    "",
                    "",
                    Color.WHITE,
                    "",
                    -1d,
                    false,
                    false,
                    "",
                    Color.WHITE,
                    0L,
                    "",
                    "",
                    Color.WHITE,
                    "",
                    emptyList(),
                    "",
                    0,
                    0L,
                    0L,
                    0L);

    /** Tracking state dot: live, paused, away, not tracking, PvP. */
    enum Gem {
        LIVE,
        PAUSED,
        AWAY,
        OFF,
        PVP
    }

    /** One tray row: sprite, "Copper ore ×1", value and its colour; gold for a big drop. */
    @RequiredArgsConstructor
    static class Row {
        final BufferedImage icon;
        final String name;
        final String tag;
        final String value;
        final Color color;
        final boolean gold;

        /** When the row first appeared, for its fade-in. */
        final long born;

        /** Ground Items' highlight colour for the name; null draws it white. */
        final Color highlight;
    }

    final boolean visible;
    final Gem gem;
    final String title;
    final String timer;
    final String net;
    final Color netColor;

    /**
     * What the aim has counted, what is left of it, or a time target's countdown, drawn at the
     * progress bar's end; empty with no target.
     */
    final String target;

    /** Target bar fill 0..1, or -1 with no target. Never negative for a losing Net. */
    final double progress;

    /** True in a time target's final warning seconds (owner live 2026-10-13). */
    final boolean targetWarning;

    /**
     * True once the aim (or the time window) is reached: the bar's fill turns green then, whichever
     * way the gauge counts (owner live 2026-10-14).
     */
    final boolean targetDone;

    /**
     * The newest booking's change beside Net ("+23") for a few seconds, whether or not the tray
     * shows.
     */
    final String trip;

    final Color tripColor;

    /** When the chip is gone; it fades out just before. */
    final long chipUntil;

    /** "1.93M/h", "Calculating…" or "—". */
    final String rate;

    final String context;
    final Color contextColor;

    final String trayLabel;
    final List<Row> rows;

    /** "+3 more" past the visible rows, else empty. */
    final String more;

    /** Changes when a new trip starts; the tray width only grows within one trip. */
    final int tripId;

    /** When the tray began to fold; it slides shut over the next moment. 0 when always open. */
    final long foldAt;

    /** When the tray opened; it slides open over the next moment. */
    final long openedAt;

    /** The builder's clock when this snapshot was made; HUD+ animates on it. */
    final long builtAt;
}
