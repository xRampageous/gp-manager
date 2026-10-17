package com.gpmanager;

import java.util.List;
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
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import com.gpmanager.GpManagerConfig.TextSize;
import java.awt.*;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;
import static com.gpmanager.TransactionType.*;
import static com.gpmanager.Kit.Tone.GAIN;
import static java.lang.Math.abs;

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
        // A new streak measures its Net from the last tick before its first event; the whole run's
        // Net, so a target starting Live fresh mid-streak never moves it (owner 1.5.2).
        long runNet = snapshot.net + snapshot.netBase;
        String streakKey = tray.streakKey();
        if (!streakKey.equals(baseKey)) {
            long base = streakKey.equals(prevKey) ? prevBase : baseKey == null ? runNet : lastNet;
            prevKey = baseKey;
            prevBase = streakBase;
            streakBase = base;
            baseKey = streakKey;
        }
        lastNet = runNet;
        // Nothing booked yet reads as a dash, never a zero (the sidebar's first-run rule).
        boolean booked = snapshot.hasSession && (snapshot.fresh || snapshot.gains != 0L || snapshot.costs != 0L
                                || snapshot.marketResult != 0L || !snapshot.recent.isEmpty());
        tray.keepMillis(config.streakEndSeconds() * 1_000L);
        // "Hide when not tracking": nothing to track, logged out or paused hides HUD+; Free play
        // shows
        // once something is booked (observed, unbooked tray activity does not count).
        boolean live = snapshot.hasSession && !snapshot.loggedOut && !snapshot.paused && !snapshot.idle;
        boolean shown = config.showHud() && (!config.hudHideWhenIdle()
                                || live && (grind || booked || tray.hasBooked()));
        if (!shown) {
            current = HudFrame.HIDDEN;
            return current;
        }
        boolean tracking = snapshot.hasSession && !snapshot.loggedOut;
        Gem gem = !tracking ? Gem.OFF : snapshot.paused && !snapshot.idle ? Gem.PAUSED : snapshot.idle ? Gem.AWAY
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
            targetWarning = warnSeconds > 0 && !goal.timerPaused && remaining > 0L && remaining <= warnSeconds * 1000L;
            target = goal.time || targetWarning ? durationCount(remaining) : compact(left);
            progress = !goal.time && goal.current <= 0L ? 0d : max(0d, min(1d, goal.fraction));
            targetDone = goal.reached;
        }
        String rate = !snapshot.hasSession ? "—" : LivePage.rateText(snapshot.rateEstablished, snapshot.gpPerHour);
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
        current = new HudFrame(true, gem, title(snapshot, now),
                        snapshot.hasSession ? timer(snapshot.elapsedMillis) : "", booked ? signed(snapshot.net) : "—",
                        booked ? tone(snapshot.net) : DIM.color, target, progress, targetWarning, targetDone,
                        trip != 0L ? signed(trip) : "", tone(trip), chipUntil, rate, context, lineColor,
                        trayLabel(snapshot, now), rows, footer, tray.tripId(), always ? 0L : shownAt + stayMillis(),
                        trayOpenedAt, now);
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
            return "(!) " + snapshot.reviewCount
                    + (snapshot.reviewCount == 1 ? " item needs review" : " items need review");
        }
        if (snapshot.reclaimItems > 0L) {
            return "Reclaim waiting · " + snapshot.reclaimItems + (snapshot.reclaimItems == 1L ? " item" : " items");
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
        return new Flow(entry.itemId, entry.name, entry.quantity, (int) min(Integer.MAX_VALUE, entry.unitPrice),
                entry.value);
    }

    Row row(Entry entry) {
        // "(u)" is the game's own short form for uncharged, as in "Craw's bow (u)" (owner 1.2).
        return new Row(icon(entry), entry.name.replace(" (uncharged)", " (u)")
                        + (entry.itemId == 995 ? "" : " " + times(entry.quantity)), entry.state.tag, value(entry),
                color(entry), entry.gold, entry.born,
                filter == null ? null : filter.highlight(entry.name, entry.quantity));
    }

    static String value(Entry entry) {
        // A clue drop is worth nothing until its casket opens: no value, never "?" (owner
        // 2026-10-09).
        return entry.value == 0L && entry.unitPrice == 0L
                ? entry.worthless || GameData.pattern("clue").matcher(entry.name).matches() ? "" : "?"
                : counted(entry) || entry.state == State.TRADED ? signed(entry.value) : compact(entry.value);
    }

    static Color color(Entry entry) {
        return entry.gold ? GOLD : entry.state == State.LOST ? LOSS.color : entry.state == State.TRADED ? MARKET.color
                                : counted(entry) ? GAIN.color : DIM.color;
    }

    /**
     * The tray heading; a kill streak adds its Net per kill (owner 1.1: the hover panel is gone).
     */
    String trayLabel(LiveSnapshot snapshot, long now) {
        Map.Entry<String, Integer> streak = tray.streak(now);
        return streak == null || streak.getValue() < 2 || !snapshot.hasSession ? tray.label() : tray.label()
                        + " \u00b7 " + signed((snapshot.net + snapshot.netBase - streakBase) / streak.getValue())
                        + "/kill";
    }

    BufferedImage icon(Entry entry) {
        return icons == null || entry.itemId <= 0 || !config.showItemIcons() ? null
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
        return hours > 0L ? String.format(ROOT, "%dh%02dm", hours, minutes)
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
    static final HudFrame HIDDEN = new HudFrame(false, Gem.OFF, "", "", "", Color.WHITE, "", -1d, false, false, "",
                    Color.WHITE, 0L, "", "", Color.WHITE, "", emptyList(), "", 0, 0L, 0L, 0L);

    /** Tracking state dot: live, paused, away, not tracking, PvP. */
    enum Gem {
        LIVE, PAUSED, AWAY, OFF, PVP
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

/**
 * HUD+ (Pulse role, Trip layout): gem, title and timer; Net first with the target folded in, the
 * newest change and GP/h; one contextual line; the item tray. Width, "…" cuts and sprite crops are
 * worked out once per snapshot; only the tray's slide changes the height between snapshots. It has
 * no mouse listener and no menu entries, so stray clicks do nothing.
 */
class HudOverlay extends Overlay {
    static final int PAD = 5;

    /** HUD+ fits its content between these; the Max width setting lowers the top. */
    static final int MIN_WIDTH = 72;

    static final int MAX_WIDTH = 320;
    static final int ICON = 20;
    int rowHeight = ICON;
    int indent = ICON + 4;
    static final Color TEAL = new Color(0, 200, 180);
    static final Color CORAL = new Color(255, 127, 80);
    static final Color GOLD_ROW = new Color(255, 200, 40, 50);
    static final long FADE_IN_MILLIS = 350L;

    /** Cadence of the final-seconds countdown blink (owner live 2026-10-13). */
    static final long WARNING_BLINK_MILLIS = 500L;
    HudBuilder builder;
    GpManagerConfig config;
    TextSize fontSize;
    Font body;
    Font bold;
    Font big;
    int opacity = -1;
    Color background;
    int widthTrip = Integer.MIN_VALUE;
    int grownWidth;

    /** The snapshot the layout below was measured for, and that layout. */
    HudFrame measured;

    int measuredKey;
    Dimension size;
    int restHeight;
    int trayHeight;
    boolean compact;

    /**
     * No title: the timer and rate share the gem row, so no row is left half empty (owner
     * 2026-09-28).
     */
    boolean rateUp;

    int[] moneyX = new int[4];
    int[] moneyY = new int[4];
    int moneyHeight;

    /** Wall clock minus the snapshot's clock, fixed when a snapshot is first painted. */
    HudFrame clocked;

    long clockOffset;
    String title = "";
    String context = "";
    String[] names = {};
    java.awt.image.BufferedImage[] sprites = {};

    HudOverlay(HudBuilder builder, GpManagerConfig config) {
        this.builder = builder;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    public Dimension render(Graphics2D g) {
        HudFrame frame = builder.current();
        if (!frame.visible) return null;
        fonts();
        FontMetrics fb = g.getFontMetrics(body);
        FontMetrics fh = g.getFontMetrics(bold);
        FontMetrics fg = g.getFontMetrics(big);
        int key = fontSize.ordinal() * 1_000 + config.hudMaxWidth();
        if (frame != measured || key != measuredKey) {
            measure(frame, fb, fh, fg);
            measured = frame;
            measuredKey = key;
        }
        long wall = SafeMath.now();
        if (frame != clocked) {
            clocked = frame;
            clockOffset = wall - frame.builtAt;
        }
        long now = wall - clockOffset;
        float open = trayOpen(frame, now, !config.reducedMotion());
        int height = restHeight + round(trayHeight * open);
        if (size.height != height) size = new Dimension(size.width, height);
        // Bitmap faces and item sprites stay sharp without smoothing; restored afterwards.
        Object textHint = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
        Object imageHint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        Composite plain = g.getComposite();
        Shape clip = g.getClip();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        try {
            paint(g, frame, fb, fh, fg, plain, now, open);
        } finally {
            g.setComposite(plain);
            g.setClip(clip);
            if (textHint != null)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, textHint);
            if (imageHint != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, imageHint);
        }
        return size;
    }

    /**
     * How far the tray is open, 0..1: it eases open when it appears and shut once it folds. Its
     * slide never waits on the chip beside Net.
     */
    static float trayOpen(HudFrame frame, long now, boolean motion) {
        if (!hasTray(frame)) return 0f;
        if (!motion) return 1f;
        float opening = ease((now - frame.openedAt) / (float) HudBuilder.FADE_MILLIS);
        return frame.foldAt == 0L ? opening : min(opening, ease((frame.foldAt + HudBuilder.FADE_MILLIS - now)
                                        / (float) HudBuilder.FADE_MILLIS));
    }

    static float ease(float t) {
        float x = max(0f, min(1f, t));
        return x * x * (3f - 2f * x);
    }

    static boolean hasTray(HudFrame frame) {
        return !frame.rows.isEmpty() || !frame.more.isEmpty();
    }

    void paint(Graphics2D g, HudFrame frame, FontMetrics fb, FontMetrics fh, FontMetrics fg, Composite plain, long now,
            float open) {
        int width = size.width;
        int right = width - PAD;
        boolean motion = !config.reducedMotion();
        g.setColor(background);
        g.fillRect(0, 0, width, size.height);
        int y = PAD;
        g.setColor(gemColor(frame.gem, motion, now));
        g.fillOval(PAD, y + fh.getHeight() / 2 - 3, 7, 7);
        Color rateColor = frame.rate.endsWith("/h") ? Color.WHITE : LABEL;
        // The timer keeps the left; the kill count owns the right, with the rate under it on the
        // money row (owner live 2026-10-14) — the timer never jumps sides.
        String head = rateUp ? frame.rate : title;
        text(g, body, frame.timer, PAD + 11, y + fh.getAscent(), LABEL);
        text(g, body, head, right - width(fb, head), y + fh.getAscent(), rateUp ? rateColor : Color.WHITE);
        if (compact) return;
        y += fh.getHeight() + 1;
        // The chip keeps its own few seconds and fades on its own, whatever the tray is doing.
        float chip = !motion ? 1f : ease((frame.chipUntil - now) / (float) HudBuilder.FADE_MILLIS);
        int baseline = y + fg.getAscent();
        text(g, big, frame.net, PAD, baseline, frame.netColor);
        fade(g, plain, chip);
        text(g, body, frame.trip, PAD + moneyX[2], baseline + moneyY[2], frame.tripColor);
        g.setComposite(plain);
        if (!rateUp)
            text(g, body, frame.rate, right - width(fb, frame.rate), baseline + moneyY[3], rateColor);
        y += moneyHeight;
        if (frame.progress >= 0d) {
            // The target label rides the bar's end; the bar shortens to make room (owner live
            // 2026-10-13: "/ 200k" on the money row stretched HUD+ wide).
            int labelRoom = frame.target.isEmpty() ? 0 : 6 + width(fb, frame.target);
            int barY = y + (labelRoom == 0 ? 0 : (fb.getHeight() - 3) / 2);
            g.setColor(LINE);
            g.fillRect(PAD, barY, width - PAD * 2 - labelRoom, 3);
            g.setColor(frame.targetDone ? Tone.GAIN.color : frame.targetWarning ? Tone.LOSS.color : ACCENT);
            g.fillRect(PAD, barY, (int) round((width - PAD * 2 - labelRoom) * frame.progress), 3);
            if (labelRoom > 0) {
                // The final-seconds warning blinks unless reduced motion, then stays solid
                // (owner live 2026-10-13).
                Color targetColor = LABEL;
                if (frame.targetWarning) {
                    boolean dim = !config.reducedMotion() && (now / WARNING_BLINK_MILLIS) % 2L == 0L;
                    targetColor = dim ? LABEL : Tone.LOSS.color;
                }
                text(g, body, frame.target, right - width(fb, frame.target), y + fb.getAscent(), targetColor);
            }
            y += labelRoom == 0 ? 5 : fb.getHeight();
        }
        if (!context.isEmpty()) {
            text(g, body, context, PAD, y + fb.getAscent(), frame.contextColor);
            y += fb.getHeight();
        }
        if (trayHeight == 0) return;
        // The tray slides: clipped to the height it has opened to, and fading with it.
        g.clipRect(0, 0, width, size.height);
        fade(g, plain, open);
        // The heading sits tight on its rows: no leading, no gap (owner 1.1).
        text(g, body, frame.trayLabel, PAD, y + fb.getAscent(), LABEL);
        y += fb.getAscent() + fb.getDescent();
        for (int i = 0; i < names.length; i++) {
            Row row = frame.rows.get(i);
            fade(g, plain, motion ? min(open, (now - row.born) / (float) FADE_IN_MILLIS) : open);
            if (row.gold) {
                g.setColor(GOLD_ROW);
                g.fillRect(0, y, width, rowHeight);
            }
            // Sprites are fitted once per snapshot: shrunk smoothly, never blown up, centred.
            if (sprites[i] != null) g.drawImage(sprites[i], PAD, y, null);
            int textY = y + (rowHeight + fb.getAscent()) / 2 - 1;
            int valueX = right - width(fb, row.value);
            text(g, body, names[i], PAD + indent, textY, row.highlight == null ? Color.WHITE : row.highlight);
            text(g, body, row.value, valueX, textY, row.color);
            text(g, body, row.tag, valueX - 4 - width(fb, row.tag), textY, LABEL);
            y += rowHeight;
        }
        fade(g, plain, open);
        text(g, body, frame.more, PAD, y + fb.getAscent(), LABEL);
    }

    static void fade(Graphics2D g, Composite plain, float alpha) {
        g.setComposite(alpha >= 1f ? plain : AlphaComposite.SrcOver.derive(max(0f, alpha)));
    }

    /**
     * Width, height, fitted text and sprite crops for one snapshot. While the tray is open the
     * width only grows, so arriving rows never jolt it; once it folds HUD+ settles back.
     */
    void measure(HudFrame frame, FontMetrics fb, FontMetrics fh, FontMetrics fg) {
        int rows = frame.rows.size();
        boolean tray = hasTray(frame);
        compact = frame.title.isEmpty() && "—".equals(frame.net) && frame.target.isEmpty() && frame.trip.isEmpty()
                        && frame.context.isEmpty() && !tray;
        rateUp = frame.title.isEmpty();
        // Ticking figures are measured with their widest digits, so the width never wobbles per
        // second.
        int header = 11 + 8 + steady(fb, frame.timer) + (rateUp ? steady(fb, frame.rate) : 0);
        int content = compact ? header : max(width(fb, frame.context), steady(fg, frame.net)
                                        + (frame.trip.isEmpty() ? 0 : 6 + steady(fb, frame.trip))
                                        + (rateUp ? 0 : 8 + steady(fb, frame.rate)));
        // The bar row needs room for its end label even when the money row is tiny.
        content = max(content, frame.target.isEmpty() ? 0 : 6 + steady(fb, frame.target));
        // Owner 1.1: with item icons off a row is text-high and starts at the edge.
        boolean icons = config.showItemIcons();
        rowHeight = icons ? ICON : fb.getAscent() + fb.getDescent() + 2;
        indent = icons ? ICON + 4 : 0;
        for (Row row : frame.rows)
            content = max(content, indent + 8 + width(fb, row.name) + right(fb, row));
        content = max(content, width(fb, frame.more));
        int ceiling = max(MIN_WIDTH, min(MAX_WIDTH, config.hudMaxWidth()));
        int width = max(MIN_WIDTH, min(ceiling, max(content, width(fb, frame.title) + header) + PAD * 2));
        if (!tray || frame.tripId != widthTrip) {
            widthTrip = frame.tripId;
            grownWidth = 0;
        }
        grownWidth = !tray ? 0 : min(ceiling, max(grownWidth, width));
        width = max(width, grownWidth);
        int inner = width - PAD * 2;
        // A short status stays on one line only when both figures fit the selected ceiling.
        compact &= content <= inner;
        moneyHeight = moneyLayout(frame, fb, fg, inner);
        title = fit(fb, frame.title, inner - header);
        if (!title.equals(frame.title) && frame.title.startsWith("KC: ")) {
            // The count survives tight widths: the prefix goes before the ellipsis does
            // (owner live 2026-10-14).
            title = fit(fb, frame.title.substring(4), inner - header);
        }
        context = fit(fb, frame.context, inner);
        names = new String[rows];
        sprites = new java.awt.image.BufferedImage[rows];
        for (int i = 0; i < rows; i++) {
            Row row = frame.rows.get(i);
            // Owner 1.2: a long name shortens before its count, so the "×2" always shows.
            String count = row.name.substring(max(0, row.name.lastIndexOf(" ×")));
            if (!count.startsWith(" ×")) count = "";
            names[i] = fit(fb, row.name.substring(0, row.name.length() - count.length()),
                                    inner - indent - 8 - right(fb, row) - width(fb, count)) + count;
            sprites[i] = row.icon == null ? null : fitIcon(row.icon, ICON);
        }
        restHeight = PAD * 2 + fh.getHeight() + (compact ? 0 : 1 + moneyHeight + (frame.progress >= 0d
                                                ? (frame.target.isEmpty() ? 5 : fb.getHeight()) : 0)
                                        + (context.isEmpty() ? 0 : fb.getHeight()));
        trayHeight = !tray ? 0 : fb.getAscent() + fb.getDescent() + rows * rowHeight
                                + (frame.more.isEmpty() ? 0 : fb.getHeight());
        size = new Dimension(width, restHeight + trayHeight);
    }

    /** Net leads; a new change or rate that cannot share its line wraps below it. */
    int moneyLayout(HudFrame frame, FontMetrics fb, FontMetrics fg, int inner) {
        String[] values = {frame.net, "", frame.trip, rateUp ? "" : frame.rate};
        int x = steady(fg, frame.net);
        int y = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i].isEmpty()) {
                moneyX[i] = x;
                moneyY[i] = y;
                continue;
            }
            int gap = values[i].isEmpty() || i == 1 ? 0 : i == 2 ? 6 : 8;
            int width = steady(fb, values[i]);
            if (x + gap + width > inner) {
                x = 0;
                y += fg.getHeight();
                gap = 0;
            }
            moneyX[i] = x + gap;
            moneyY[i] = y;
            x += gap + width;
        }
        return y + fg.getHeight();
    }

    /** Width of text with every digit counted as the font's widest digit. */
    static int steady(FontMetrics metrics, String value) {
        char widest = '0';
        for (char digit = '1'; digit <= '9'; digit++) {
            widest = metrics.charWidth(digit) > metrics.charWidth(widest) ? digit : widest;
        }
        return width(metrics, value.replaceAll("[0-9]", String.valueOf(widest)));
    }

    /** A row's value and tag, the part that never shortens. */
    static int right(FontMetrics fb, Row row) {
        return width(fb, row.value) + (row.tag.isEmpty() ? 0 : width(fb, row.tag) + 4);
    }

    /** Bounds of a sprite's visible pixels; the whole image while it is still loading (blank). */
    /** The text, or its longest start that fits {@code room} pixels followed by "…". */
    static String fit(FontMetrics metrics, String value, int room) {
        if (width(metrics, value) <= room) return value;
        int budget = room - metrics.stringWidth("…");
        int low = 0;
        int high = value.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (width(metrics, value.substring(0, middle)) <= budget) low = middle;
            else high = middle - 1;
        }
        return value.substring(0, low).trim() + "…";
    }

    void fonts() {
        TextSize wanted = config.hudTextSize();
        if (wanted != fontSize) {
            fontSize = wanted;
            Font small = FontManager.getRunescapeSmallFont();
            Font regular = FontManager.getRunescapeFont();
            Font heavy = FontManager.getRunescapeBoldFont();
            // RuneLite's pixel fonts only render cleanly at their own size, so sizes pick fonts
            // rather than scale them: Small, Normal, and Large (the bold face throughout).
            body = wanted == TextSize.Small ? small : wanted == TextSize.Large ? heavy : regular;
            bold = wanted == TextSize.Small ? regular : heavy;
            big = wanted == TextSize.Small ? regular : body;
        }
        int alpha = config.hudOpacity();
        if (alpha != opacity) {
            opacity = alpha;
            background = new Color(PANEL.getRed(), PANEL.getGreen(), PANEL.getBlue(),
                            max(0, min(255, alpha * 255 / 100)));
        }
    }

    Font body() {
        fonts();
        return body;
    }

    Font bold() {
        fonts();
        return bold;
    }

    static Color gemColor(HudFrame.Gem gem, boolean motion, long now) {
        switch (gem) {
            case LIVE:
                return TEAL;
            case PAUSED:
                return CORAL;
            case AWAY:
                return motion ? new Color(255, 127, 80, 110 + (int) (100 * abs(sin(now / 400d)))) : CORAL.darker();
            case PVP:
                return Tone.LOSS.color;
            case OFF:
            default:
                return Kit.OFF;
        }
    }

    /** Width of text as {@link #text} draws it. */
    static int width(FontMetrics metrics, String value) {
        return value == null ? 0 : metrics.stringWidth(value.replace('−', '-'));
    }

    /** Text with the one-pixel shadow every RuneLite overlay uses. */
    static void text(Graphics2D g, Font font, String value, int x, int y, Color color) {
        if (Phrases.empty(value)) return;
        // RuneLite's font has no true minus sign (U+2212); a hyphen reads the same.
        String shown = value.replace('−', '-');
        g.setFont(font);
        g.setColor(Color.BLACK);
        g.drawString(shown, x + 1, y + 1);
        g.setColor(color);
        g.drawString(shown, x, y);
    }
}

/**
 * The HUD+ item tray: what the current streak received, claimed, lost or traded. A streak is one
 * run of the same work: fighting one NPC, or one skill. Attacking another NPC, another kind of
 * work, or a quiet gap starts the next streak, so one NPC's loot never lands on another's tray. Fed
 * on the client thread by kills, bookings and reward sightings, read on the EDT by {@link
 * HudBuilder}. Presentation only; nothing here books or values money, it only mirrors the bookings.
 */
class HudTray {
    /**
     * A single drop worth this much lights its row gold and raises a moment, unless Ground Items
     * sets it.
     */
    static final long BIG_DROP = 10_000_000L;

    long big = BIG_DROP;

    @RequiredArgsConstructor
    enum State {
        // A tray row always reads icon, name ×count and value; the state stays internal
        // (owner 2026-10-13: no "claimed"/"pending"/"recovered" words on loot rows).
        RECEIVED(""), PENDING(""), CLAIMED(""), LOST(""), RECOVERED(""), TRADED("");
        final String tag;
    }

    /** One tray entry; quantities of the same item and state add up. */
    @RequiredArgsConstructor
    static class Entry {
        final int itemId;
        final String name;
        final State state;
        final long unitPrice;
        long quantity;
        long value;
        boolean gold;
        long born;

        /**
         * The last time this entry grew; the tray opens and folds by its visible entries' times.
         */
        long touched;

        /** Untradeable with no alch value: the tray shows no value, never "?". */
        boolean worthless;
    }

    Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * The NPC this streak first killed ("" before its first kill), every NPC it killed, and its
     * kills.
     */
    String source = "";

    Set<String> npcs = new LinkedHashSet<>();
    int kills;

    /**
     * The streak a switch of target ended, kept through its quiet gap: switching back merges it.
     */
    Map<String, Entry> prevEntries = new LinkedHashMap<>();

    Set<String> prevNpcs = new LinkedHashSet<>();
    String prevSource = "";
    int prevKills;
    int prevTrip;
    long prevEventAt;
    long lastEventAt;

    /** When an item last arrived: the tray opens on items only, never on a kill or an attack. */
    /**
     * Changes with every new streak (and on clear); HUD+ rebases the streak's Net and width on it.
     */
    int tripId;

    String label = "Looted";

    /** What the newest booking added (counted rows only): the chip beside Net. */
    long lastDelta;

    long lastDeltaAt;

    /** A booking settled; mirror its visible item flows onto the tray. */
    synchronized void booked(Transaction transaction, long now, boolean xy) {
        if (transaction == null) return;
        if (!transaction.isCounted() || transaction.getType() == TransactionType.TRANSFER
                || transaction.getCorrection() == Correction.TRANSFER || transaction.getType() == UNCERTAIN
                // Owner 2026-09-28: eating and drinking stay off HUD+ (a sip's "(3)" is no drop).
                || transaction.getActionKind() == ActionKind.EAT || transaction.getActionKind() == ActionKind.DRINK
                || ActionReader.isDoseStep(transaction.getFlows())) {
            return;
        }
        boolean market = transaction.getContext() == Context.MARKET || transaction.getType() == TRADE;
        boolean claimed = transaction.getActionKind() == ActionKind.DEFERRED_CLAIM
                        || !transaction.sourceClaimId().isEmpty();
        if (!npcs.isEmpty() && transaction.getNote().startsWith("Loot from ")
                && !has(npcs, transaction.getActivityName())) {
            // Another NPC's leftover drop, picked up after this streak began: it counts in Net, not
            // here.
            return;
        }
        boolean reclaim = (transaction.getActivityName() + " " + transaction.getNote()).toLowerCase(ROOT)
                        .contains("reclaim");
        // Only deaths, drops and destroys ride the tray as lost; other item use is routine.
        String dropped = Phrases.has(transaction.getNote(), "Dropped", "Destroyed") ? transaction.getNote() : "";
        boolean lost = transaction.autoType() == PK_DEATH_LOSS || transaction.getContext() == Context.PK_DEATH
                        || !dropped.isEmpty();
        // Owner 2026-10-09: opening caskets or impling jars is one row per container with its
        // total, so
        // a stack opened never floods the tray; a big drop still gets its own gold row.
        Flow container = opened(transaction.getFlows());
        if (container != null && !market && !lost) {
            event(now, xy, "Opened", null);
            long net = 0L;
            long count = 0L;
            for (Flow flow : transaction.getFlows()) {
                net += flow.valueDelta;
                if (flow.isCost() && flow.itemId == container.itemId) count -= flow.quantityDelta;
                if (flow.isGain() && flow.valueDelta >= big) {
                    add(flow.itemId, flow.itemName, State.RECEIVED, flow.unitPrice, flow.quantityDelta, flow.valueDelta,
                                            now, 0L).gold = true;
                }
            }
            add(container.itemId, container.itemName, State.RECEIVED, 0L, count, net, now, 0L);
            lastDelta = net;
            lastDeltaAt = now;
            return;
        }
        boolean any = false;
        long delta = 0L;
        // Owner 2026-10-09: a trade rides the tray as its item, bought or sold, with the coins it
        // took
        // or brought when it is the one item; its coins never get a row of their own.
        long coins = 0L;
        int items = 0;
        for (Flow flow : transaction.getFlows()) {
            if (flow.itemId == 995) coins += flow.valueDelta;
            else items++;
        }
        for (Flow flow : transaction.getFlows()) {
            if (flow.quantityDelta == 0L || market && flow.itemId == 995) continue;
            boolean gain = flow.quantityDelta > 0L;
            // Routine rune/ammo/charge use never rides the tray, but a pickup is a drop
            // like any other (owner 2026-09-29).
            if (!gain && !market && routine(flow.itemName)) continue;
            if (!gain && !market && !lost) continue;
            if (!any) {
                // A drop or a GE or player trade joins the running trip; on an empty tray it reads
                // "Dropped", "Traded" (owners 2026-09-28, 2026-10-09) or "Exchanged" for a shop.
                event(now, xy, claimed ? "Claimed" : market || !dropped.isEmpty() ? null
                                        : lost ? "Lost" : verb(transaction, flow.itemName, now), null);
                if ((market || !dropped.isEmpty()) && entries.isEmpty())
                    label = market ? (Semantics.shopExchange(transaction) ? "Exchanged" : "Traded") : dropped;
                any = true;
            }
            int id = flow.itemId;
            long quantity = abs(flow.quantityDelta);
            State state = market ? State.TRADED : !gain ? State.LOST : reclaim ? State.RECOVERED
                                            : claimed ? State.CLAIMED : State.RECEIVED;
            long born = 0L;
            if (state == State.RECEIVED || state == State.CLAIMED) {
                // A claim turns its pending row into Claimed in place, keeping its first-seen
                // identity.
                Entry pending = entries.get(key(State.PENDING, id));
                if (pending != null && take(State.PENDING, id, quantity) > 0L) {
                    state = State.CLAIMED;
                    born = pending.born;
                }
            }
            Entry entry = add(id, flow.itemName, state, flow.unitPrice, quantity, !market ? flow.valueDelta
                                    : items == 1 && coins != 0L ? abs(coins) : abs(flow.valueDelta), now, born);
            entry.worthless |= flow.getPriceSource() == PriceSource.NO_VALUE;
            delta += state == State.TRADED ? 0L : flow.valueDelta;
            if (gain && state != State.TRADED && flow.valueDelta >= big) {
                entry.gold = true;
            }
        }
        if (any && delta != 0L) {
            lastDelta = delta;
            lastDeltaAt = now;
        }
    }

    /**
     * A reward window reported loot it is holding; nothing is counted until it settles. Ground loot
     * never comes here: the tray lists what was received, so a pickup appears once, in place.
     */
    synchronized void observed(int itemId, String name, long quantity, long unitPrice, long now, boolean xy) {
        if (quantity <= 0L) return;
        event(now, xy, null, null);
        add(itemId, name, State.PENDING, unitPrice, quantity, unitPrice * quantity, now, 0L);
    }

    /**
     * One NPC loot event is one kill; a kill of another NPC starts that NPC's streak, unless it was
     * killed in the streak before, still inside that streak's quiet gap: then both are one mixed
     * streak again (owner 1.1).
     */
    synchronized void kill(String npc, long now, boolean xy) {
        String name = npc == null ? "" : npc.trim();
        if (name.isEmpty() || "NPC loot".equals(name)) return;
        if (!has(npcs, name)) {
            if (kills > 0) {
                // The new target's first kill refreshes the tray to its own streak; loot already
                // booked before any kill stays (owner 2026-10-01).
                boolean back = has(prevNpcs, name) && now - prevEventAt < quietMillis();
                var rows = new LinkedHashMap<String, Entry>(entries);
                var previous = new LinkedHashSet<String>(npcs);
                int had = kills;
                int trip = tripId;
                long last = lastEventAt;
                String from = source;
                restart(xy);
                if (back) {
                    if (!xy) {
                        merge(prevEntries.values());
                        merge(rows.values());
                    }
                    npcs.addAll(prevNpcs);
                    npcs.addAll(previous);
                    kills = prevKills + had;
                    tripId = prevTrip;
                    source = prevSource;
                    prevNpcs = new LinkedHashSet<>();
                } else {
                    prevEntries = rows;
                    prevNpcs = previous;
                    prevKills = had;
                    prevTrip = trip;
                    prevEventAt = last;
                    prevSource = from;
                }
            }
            label = "Looted";
        }
        event(now, xy, "Looted", name);
        kills++;
    }

    /**
     * The live kill streak ("Guard & Man", 5), or null before a kill or once the streak went quiet.
     */
    synchronized Map.Entry<String, Integer> streak(long now) {
        return kills == 0 || now - lastEventAt >= quietMillis() ? null
                : new AbstractMap.SimpleImmutableEntry<>(names(), kills);
    }

    /** The streak's NPCs: "Guard", "Guard & Man", "Guard, Man +1"; empty before a kill. */
    synchronized String names() {
        var list = new ArrayList<String>();
        for (String npc : npcs) list.add(Fmt.activity(npc));
        int n = list.size();
        return n == 0 ? "" : n == 1 ? list.get(0) : n == 2 ? list.get(0) + " & " + list.get(1)
                                : list.get(0) + ", " + list.get(1) + " +" + (n - 2);
    }

    static boolean has(Set<String> names, String name) {
        for (String each : names) {
            if (each.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Owner or Grind changed: start clean. */
    synchronized void clear() {
        entries.clear();
        tripId++;
        source = "";
        npcs.clear();
        prevNpcs = new LinkedHashSet<>();
        kills = 0;
        lastEventAt = 0L;
        lastDelta = 0L;
        lastDeltaAt = 0L;
        label = "Looted";
    }

    /** Newest arrivals first; a row keeps its place while its quantity grows. */
    synchronized List<Entry> entries() {
        var list = new ArrayList<Entry>(entries.values());
        // Owner 1.2: the row that last grew leads, so the newest loot is always on top.
        list.sort((a, b) -> a.touched != b.touched ? Long.compare(b.touched, a.touched) : Long.compare(b.born, a.born));
        return list;
    }

    /** The newest booking's counted change, e.g. +23 for one more item; 0 before any. */
    synchronized long lastDelta() {
        return lastDelta;
    }

    /** When the newest booking's chip was set; 0 before any. */
    synchronized long lastDeltaAt() {
        return lastDeltaAt;
    }

    /** Something booked rides the tray; a reward window's held (pending) loot is not booked yet. */
    synchronized boolean hasBooked() {
        return entries.values().stream().anyMatch(entry -> entry.state != State.PENDING);
    }

    synchronized int tripId() {
        return tripId;
    }

    /**
     * The tray heading: a kill streak's NPCs, else what the streak did ("Stole", "Claimed", "Mined"
     * ...).
     */
    synchronized String label() {
        return "Looted".equals(label) && !npcs.isEmpty() ? names() : label;
    }

    /**
     * Every tray event belongs to a streak. A quiet gap, another kind of work ("Mined" after
     * "Looted") or a kill of another NPC ends it; the next streak starts with an empty tray unless
     * rows keep for the whole session. Folding only hides the tray; it never ends a streak.
     */
    void event(long now, boolean xy, String kind, String npc) {
        if (lastEventAt > 0L && now - lastEventAt >= quietMillis() || kind != null && !kind.equals(label)
                || npc != null && !npcs.isEmpty() && !has(npcs, npc)) {
            restart(xy);
        }
        if (kind != null) label = kind;
        if (npc != null) {
            if (source.isEmpty()) source = npc;
            npcs.add(npc);
        }
        lastEventAt = now;
    }

    void restart(boolean xy) {
        if (!xy) {
            entries.clear();
        }
        tripId++;
        source = "";
        npcs.clear();
        kills = 0;
        lastEventAt = 0L;
    }

    /** Identity of the streak the per-kill line measures: a new streak, or its first kill. */
    synchronized String streakKey() {
        return tripId + ":" + source;
    }

    /**
     * A streak goes quiet after three of its own kill intervals: at least a minute, at most five.
     */
    volatile long keepMillis = 60_000L;

    /** Owner-set streak window: the tray ends a streak this long after its last kill. */
    synchronized void keepMillis(long millis) {
        keepMillis = max(10_000L, min(3_600_000L, millis));
    }

    long quietMillis() {
        return keepMillis;
    }

    long take(State from, int itemId, long quantity) {
        Entry source = entries.get(key(from, itemId));
        if (source == null) return 0L;
        long moved = min(quantity, source.quantity);
        source.quantity -= moved;
        source.value = source.unitPrice * source.quantity;
        if (source.quantity <= 0L) entries.remove(key(from, itemId));
        return moved;
    }

    void merge(Collection<Entry> rows) {
        for (Entry row : rows) {
            Entry entry = add(row.itemId, row.name, row.state, row.unitPrice, row.quantity, row.value, 0L, row.born);
            entry.gold |= row.gold;
            entry.worthless |= row.worthless;
        }
    }

    Entry add(int itemId, String name, State state, long unitPrice, long quantity, long value, long now, long born) {
        Entry entry = entries.computeIfAbsent(key(state, itemId), ignored -> new Entry(itemId,
                                        name == null ? "Unknown item" : name, state, unitPrice));
        if (entry.born == 0L) entry.born = born != 0L ? born : now;
        entry.touched = now;
        entry.quantity += quantity;
        entry.value += value;
        return entry;
    }

    static String key(State state, int itemId) {
        return state.name() + ":" + itemId;
    }

    /**
     * The casket or impling jar a booking opened: every cost is one, and something came out of it.
     * A caught impling spends an empty "Impling jar", which is no opening.
     */
    static Flow opened(List<Flow> flows) {
        return ActionReader.all(flows, -1, name -> GameData.pattern("clue").matcher(name).matches()
                                                || name.endsWith(" impling jar"))
                        && ActionReader.all(flows, 1, name -> true)
                ? flows.stream().filter(Flow::isCost).findFirst().get() : null;
    }

    /** Runes, ammunition and charges are routine use: they count in Net but never ride the tray. */
    static boolean routine(String name) {
        // Runes, arrows, bolts, darts, javelins, throwing weapons and Zulrah's scales (d18
        // "routine").
        return GameData.pattern("routine").matcher(Phrases.lower(name)).matches();
    }

    static final List<String[]> VERBS = GameData.rows("tray_verbs");
    static final Set<String> MINIONS = new HashSet<>();

    static {
        GameData.rows("boss_spawns").forEach(row -> MINIONS.add(row[0]));
    }

    /**
     * A boss's spawn (Greater Nechryael's death spawns): no header, streak, kill or activity of its
     * own.
     */
    static boolean minion(String npc) {
        return npc != null && MINIONS.contains(Phrases.lower(npc));
    }

    /** The newest skilling XP's tray verb ("Mined") and when it arrived. */
    String xpVerb = "Looted";

    long xpAt;

    /** Skill XP arrived: an item booked moments later is that skill's work. */
    synchronized void xp(String skill, long now) {
        String kind = labelFor(skill);
        if ("Looted".equals(kind)) return;
        xpVerb = kind;
        xpAt = now;
    }

    /**
     * The tray verb for a receipt: a production run's own skill, else the skill whose XP came with
     * the item, else the activity. The activity names a skill only after its second XP drop, so it
     * can still be General, or the last skill, on a run's first item: every run stays one streak.
     */
    String verb(Transaction transaction, String item, long now) {
        String kind = transaction.getContext() == Context.PRODUCTION ? labelFor(transaction.getNote()) : "Looted";
        if ("Looted".equals(kind) && (transaction.getType() == TransactionType.GAIN || transaction.getType() == PROCESSING)
                && now - xpAt < 6_000L) {
            kind = xpVerb;
        }
        if ("Looted".equals(kind)) kind = labelFor(transaction.getActivityName());
        // Smithing XP makes bars at a furnace and items at an anvil.
        return "Smithed".equals(kind) && item != null && item.endsWith(" bar") ? "Smelted" : kind;
    }

    static String labelFor(String activity) {
        String lower = Phrases.lower(activity);
        for (String[] row : VERBS) {
            if (lower.contains(row[0])) return row[1];
        }
        return "Looted";
    }
}
