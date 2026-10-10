package com.gpmanager;

import static com.gpmanager.Kit.*;

import static java.lang.Math.*;

import com.gpmanager.GpManagerConfig.TextSize;
import com.gpmanager.HudFrame.Row;
import com.gpmanager.Kit.Tone;

import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;

import java.awt.*;

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
    Rectangle[] crops = {};

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
        g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
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
        return frame.foldAt == 0L
                ? opening
                : min(
                        opening,
                        ease(
                                (frame.foldAt + HudBuilder.FADE_MILLIS - now)
                                        / (float) HudBuilder.FADE_MILLIS));
    }

    static float ease(float t) {
        float x = max(0f, min(1f, t));
        return x * x * (3f - 2f * x);
    }

    static boolean hasTray(HudFrame frame) {
        return !frame.rows.isEmpty() || !frame.more.isEmpty();
    }

    void paint(
            Graphics2D g,
            HudFrame frame,
            FontMetrics fb,
            FontMetrics fh,
            FontMetrics fg,
            Composite plain,
            long now,
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
        if (rateUp) {
            text(g, body, frame.timer, PAD + 11, y + fh.getAscent(), LABEL);
            text(g, body, frame.rate, right - width(fb, frame.rate), y + fh.getAscent(), rateColor);
        } else {
            text(g, bold, title, PAD + 11, y + fh.getAscent(), Color.WHITE);
            text(g, body, frame.timer, right - width(fb, frame.timer), y + fh.getAscent(), LABEL);
        }
        if (compact) return;
        y += fh.getHeight() + 1;
        // The chip keeps its own few seconds and fades on its own, whatever the tray is doing.
        float chip = !motion ? 1f : ease((frame.chipUntil - now) / (float) HudBuilder.FADE_MILLIS);
        int baseline = y + fg.getAscent();
        text(g, big, frame.net, PAD, baseline, frame.netColor);
        text(g, body, frame.target, PAD + moneyX[1], baseline + moneyY[1], LABEL);
        fade(g, plain, chip);
        text(g, body, frame.trip, PAD + moneyX[2], baseline + moneyY[2], frame.tripColor);
        g.setComposite(plain);
        if (!rateUp)
            text(
                    g,
                    body,
                    frame.rate,
                    right - width(fb, frame.rate),
                    baseline + moneyY[3],
                    rateColor);
        y += moneyHeight;
        if (frame.progress >= 0d) {
            g.setColor(LINE);
            g.fillRect(PAD, y, width - PAD * 2, 3);
            g.setColor(frame.progress >= 1d ? Tone.GAIN.color : ACCENT);
            g.fillRect(PAD, y, (int) round((width - PAD * 2) * frame.progress), 3);
            y += 5;
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
            if (row.icon != null && crops[i] != null) {
                Rectangle crop = crops[i];
                // Sprites shrink smoothly to fit and are never blown up: a small sprite stays
                // crisp.
                double scale = min(1d, ICON / (double) max(crop.width, crop.height));
                g.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        scale < 1d
                                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                                : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                int w = (int) round(crop.width * scale);
                int h = (int) round(crop.height * scale);
                int dx = PAD + (ICON - w) / 2;
                int dy = y + (ICON - h) / 2;
                g.drawImage(
                        row.icon,
                        dx,
                        dy,
                        dx + w,
                        dy + h,
                        crop.x,
                        crop.y,
                        crop.x + crop.width,
                        crop.y + crop.height,
                        null);
            }
            int textY = y + (rowHeight + fb.getAscent()) / 2 - 1;
            int valueX = right - width(fb, row.value);
            text(g, body, names[i], PAD + indent, textY, Color.WHITE);
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
        compact =
                frame.title.isEmpty()
                        && "—".equals(frame.net)
                        && frame.target.isEmpty()
                        && frame.trip.isEmpty()
                        && frame.context.isEmpty()
                        && !tray;
        rateUp = frame.title.isEmpty();
        // Ticking figures are measured with their widest digits, so the width never wobbles per
        // second.
        int header = 11 + 8 + steady(fb, frame.timer) + (rateUp ? steady(fb, frame.rate) : 0);
        int content =
                compact
                        ? header
                        : max(
                                width(fb, frame.context),
                                steady(fg, frame.net)
                                        + steady(fb, frame.target)
                                        + (frame.trip.isEmpty() ? 0 : 6 + steady(fb, frame.trip))
                                        + (rateUp ? 0 : 8 + steady(fb, frame.rate)));
        // Owner 1.1: with item icons off a row is text-high and starts at the edge.
        boolean icons = config.showItemIcons();
        rowHeight = icons ? ICON : fb.getAscent() + fb.getDescent() + 2;
        indent = icons ? ICON + 4 : 0;
        for (Row row : frame.rows)
            content = max(content, indent + 8 + width(fb, row.name) + right(fb, row));
        content = max(content, width(fb, frame.more));
        int ceiling = max(MIN_WIDTH, min(MAX_WIDTH, config.hudMaxWidth()));
        int width =
                max(
                        MIN_WIDTH,
                        min(ceiling, max(content, width(fh, frame.title) + header) + PAD * 2));
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
        title = fit(fh, frame.title, inner - header);
        context = fit(fb, frame.context, inner);
        names = new String[rows];
        crops = new Rectangle[rows];
        for (int i = 0; i < rows; i++) {
            Row row = frame.rows.get(i);
            // Owner 1.2: a long name shortens before its count, so the "×2" always shows.
            String count = row.name.substring(max(0, row.name.lastIndexOf(" ×")));
            if (!count.startsWith(" ×")) count = "";
            names[i] =
                    fit(
                                    fb,
                                    row.name.substring(0, row.name.length() - count.length()),
                                    inner - indent - 8 - right(fb, row) - width(fb, count))
                            + count;
            crops[i] = row.icon == null ? null : Kit.visible(row.icon);
        }
        restHeight =
                PAD * 2
                        + fh.getHeight()
                        + (compact
                                ? 0
                                : 1
                                        + moneyHeight
                                        + (frame.progress >= 0d ? 5 : 0)
                                        + (context.isEmpty() ? 0 : fb.getHeight()));
        trayHeight =
                !tray
                        ? 0
                        : fb.getAscent()
                                + fb.getDescent()
                                + rows * rowHeight
                                + (frame.more.isEmpty() ? 0 : fb.getHeight());
        size = new Dimension(width, restHeight + trayHeight);
    }

    /** Net leads; a target, new change or rate that cannot share its line wraps below it. */
    int moneyLayout(HudFrame frame, FontMetrics fb, FontMetrics fg, int inner) {
        String[] values = {frame.net, frame.target, frame.trip, rateUp ? "" : frame.rate};
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
            background =
                    new Color(
                            PANEL.getRed(),
                            PANEL.getGreen(),
                            PANEL.getBlue(),
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
                return motion
                        ? new Color(255, 127, 80, 110 + (int) (100 * abs(sin(now / 400d))))
                        : CORAL.darker();
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
