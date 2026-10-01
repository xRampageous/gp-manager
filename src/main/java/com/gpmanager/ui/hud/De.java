package com.gpmanager;
import com.gpmanager.GpManagerConfig.Cr;
import com.gpmanager.Cb.Row;
import com.gpmanager.Kit.Tone;
import java.awt.*;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;
import static java.lang.Math.*;
import static com.gpmanager.Kit.*;
/**
* HUD+ (Pulse role, Trip layout): gem, title and timer; Net first with the target folded in, the
* newest change and GP/h; one contextual line; the item tray. Width, "…" cuts and sprite crops are
* worked out once per snapshot; only the tray's slide changes the height between snapshots. It has
* no mouse listener and no menu entries, so stray clicks do nothing.
*/
class De extends Overlay {
static final int PAD = 5;
/** HUD+ fits its content between these; the Max width setting lowers the top. */
static final int MIN_WIDTH = 72;
static final int MAX_WIDTH = 320;
static final int ICON = 24;
static final Color TEAL = new Color(0, 200, 180);
static final Color CORAL = new Color(255, 127, 80);
static final Color GOLD_ROW = new Color(255, 200, 40, 50);
static final long FADE_IN_MILLIS = 350L;
final Cp builder;
final GpManagerConfig config;
Cr fontSize;
Font body;
Font bold;
Font big;
int opacity = -1;
Color background;
int widthTrip = Integer.MIN_VALUE;
int grownWidth;
/** The snapshot the layout below was measured for, and that layout. */
Cb measured;
int measuredKey;
Dimension size;
int restHeight;
int trayHeight;
boolean compact;
/** No title: the timer and rate share the gem row, so no row is left half empty (owner 2026-09-28). */
boolean rateUp;
final int[] moneyX = new int[4];
final int[] moneyY = new int[4];
int moneyHeight;
/** Wall clock minus the snapshot's clock, fixed when a snapshot is first painted. */
Cb clocked;
long clockOffset;
String title = "";
String context = "";
String[] names = {};
Rectangle[] crops = {};
De(Cp builder, GpManagerConfig config) {
 this.builder = builder;
 this.config = config;
 setPosition(OverlayPosition.TOP_LEFT);
}

public Dimension render(Graphics2D g) {
 Cb s = builder.current();
 if (!s.visible) return null;
 fonts();
 FontMetrics fb = g.getFontMetrics(body);
 FontMetrics fh = g.getFontMetrics(bold);
 FontMetrics fg = g.getFontMetrics(big);
 int key = fontSize.ordinal() * 1_000 + config.hudMaxWidth();
 if (s != measured || key != measuredKey) {
  measured = s;
  measuredKey = key;
  measure(s, fb, fh, fg);
 }
 long wall = System.currentTimeMillis();
 if (s != clocked) {
  clocked = s;
  clockOffset = wall - s.builtAt;
 }
 long now = wall - clockOffset;
 float open = axf(s, now, !config.reducedMotion());
 int height = restHeight + round(trayHeight * open);
 if (size.height != height) size = new Dimension(size.width, height);
 // Bitmap faces and item sprites stay sharp without smoothing; restored afterwards.
 Object aqw = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
 Object aom = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
 Composite plain = g.getComposite();
 Shape ayf = g.getClip();
 g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
 g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
 try {
  paint(g, s, fb, fh, fg, plain, now, open);
 } finally {
  g.setComposite(plain);
  g.setClip(ayf);
  if (aqw != null) g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aqw);
  if (aom != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, aom);
 }
 return size;
}

/**
* How far the tray is open, 0..1: it eases open when it appears and shut once it folds. Its
* slide never waits on the chip beside Net.
*/
static float axf(Cb s, long now, boolean motion) {
 if (!axt(s)) return 0f;
 if (!motion) return 1f;
 float opening = ease((now - s.openedAt) / (float) Cp.FADE_MILLIS);
 return s.foldAt == 0L ? opening
 : min(opening, ease((s.foldAt + Cp.FADE_MILLIS - now) / (float) Cp.FADE_MILLIS));
}

static float ease(float t) {
 float x = max(0f, min(1f, t));
 return x * x * (3f - 2f * x);
}

static boolean axt(Cb s) {
 return !s.rows.isEmpty() || !s.more.isEmpty();
}

void paint(Graphics2D g, Cb s, FontMetrics fb, FontMetrics fh, FontMetrics fg, Composite plain,
long now, float open) {
 int width = size.width;
 int right = width - PAD;
 boolean motion = !config.reducedMotion();
 g.setColor(background);
 g.fillRect(0, 0, width, size.height);
 if (s.gold) {
  g.setColor(Cp.GOLD);
  g.drawRect(0, 0, width - 1, size.height - 1);
 }
 int y = PAD;
 g.setColor(awf(s.gem, motion, now));
 g.fillOval(PAD, y + fh.getHeight() / 2 - 3, 7, 7);
 Color apq = s.rate.endsWith("/h") ? Color.WHITE : LABEL;
 if (rateUp) {
  text(g, body, s.timer, PAD + 11, y + fh.getAscent(), LABEL);
  text(g, body, s.rate, right - width(fb, s.rate), y + fh.getAscent(), apq);
 } else {
  text(g, bold, title, PAD + 11, y + fh.getAscent(), Color.WHITE);
  text(g, body, s.timer, right - width(fb, s.timer), y + fh.getAscent(), LABEL);
 }
 if (compact) return;
 y += fh.getHeight() + 1;
 // The chip keeps its own few seconds and fades on its own, whatever the tray is doing.
 float chip = !motion ? 1f : ease((s.chipUntil - now) / (float) Cp.FADE_MILLIS);
 int baseline = y + fg.getAscent();
 text(g, big, s.net, PAD, baseline, s.netColor);
 text(g, body, s.target, PAD + moneyX[1], baseline + moneyY[1], LABEL);
 fade(g, plain, chip);
 text(g, body, s.trip, PAD + moneyX[2], baseline + moneyY[2], s.tripColor);
 g.setComposite(plain);
 if (!rateUp) text(g, body, s.rate, right - width(fb, s.rate), baseline + moneyY[3], apq);
 y += moneyHeight;
 if (s.progress >= 0d) {
  g.setColor(LINE);
  g.fillRect(PAD, y, width - PAD * 2, 3);
  g.setColor(s.progress >= 1d ? Tone.GAIN.color : ACCENT);
  g.fillRect(PAD, y, (int) round((width - PAD * 2) * s.progress), 3);
  y += 5;
 }
 if (!context.isEmpty()) {
  text(g, body, context, PAD, y + fb.getAscent(), s.contextColor);
  y += fb.getHeight();
 }
 if (trayHeight == 0) return;
 // The tray slides: clipped to the height it has opened to, and fading with it.
 g.clipRect(0, 0, width, size.height);
 fade(g, plain, open);
 text(g, body, s.trayLabel, PAD, y + 2 + fb.getAscent(), LABEL);
 y += 2 + fb.getHeight();
 for (int i = 0; i < names.length; i++) {
  Row row = s.rows.get(i);
  fade(g, plain, motion ? min(open, (now - row.born) / (float) FADE_IN_MILLIS) : open);
  if (row.gold) {
   g.setColor(GOLD_ROW);
   g.fillRect(0, y, width, ICON);
  }
  if (row.icon != null && crops[i] != null) {
   Rectangle c = crops[i];
   // Sprites shrink smoothly to fit and are never blown up: a small sprite stays crisp.
   double scale = min(1d, ICON / (double) max(c.width, c.height));
   g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale < 1d
   ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
   int w = (int) round(c.width * scale);
   int h = (int) round(c.height * scale);
   int dx = PAD + (ICON - w) / 2;
   int dy = y + (ICON - h) / 2;
   g.drawImage(row.icon, dx, dy, dx + w, dy + h, c.x, c.y, c.x + c.width, c.y + c.height, null);
  }
  int axl = y + (ICON + fb.getAscent()) / 2 - 1;
  int avw = right - width(fb, row.value);
  text(g, body, names[i], PAD + ICON + 4, axl, Color.WHITE);
  text(g, body, row.value, avw, axl, row.color);
  text(g, body, row.tag, avw - 4 - width(fb, row.tag), axl, LABEL);
  y += ICON;
 }
 fade(g, plain, open);
 text(g, body, s.more, PAD, y + fb.getAscent(), LABEL);
}

static void fade(Graphics2D g, Composite plain, float alpha) {
 g.setComposite(alpha >= 1f ? plain : AlphaComposite.SrcOver.derive(max(0f, alpha)));
}

/**
* Width, height, fitted text and sprite crops for one snapshot. While the tray is open the
* width only grows, so arriving rows never jolt it; once it folds HUD+ settles back.
*/
void measure(Cb s, FontMetrics fb, FontMetrics fh, FontMetrics fg) {
 int rows = s.rows.size();
 boolean tray = axt(s);
 compact = s.title.isEmpty() && "—".equals(s.net) && s.target.isEmpty() && s.trip.isEmpty()
 && s.context.isEmpty() && !tray;
 rateUp = s.title.isEmpty();
 // Ticking figures are measured with their widest digits, so the width never wobbles per second.
 int header = 11 + 8 + steady(fb, s.timer) + (rateUp ? steady(fb, s.rate) : 0);
 int content = compact ? header : max(width(fb, s.context), steady(fg, s.net) + steady(fb, s.target)
 + (s.trip.isEmpty() ? 0 : 6 + steady(fb, s.trip)) + (rateUp ? 0 : 8 + steady(fb, s.rate)));
 for (Row row : s.rows) content = max(content, ICON + 12 + width(fb, row.name) + right(fb, row));
 content = max(content, width(fb, s.more));
 int ceiling = max(MIN_WIDTH, min(MAX_WIDTH, config.hudMaxWidth()));
 int width = max(MIN_WIDTH, min(ceiling, max(content, width(fh, s.title) + header) + PAD * 2));
 if (!tray || s.tripId != widthTrip) {
  widthTrip = s.tripId;
  grownWidth = 0;
 }
 grownWidth = !tray ? 0 : min(ceiling, max(grownWidth, width));
 width = max(width, grownWidth);
 int inner = width - PAD * 2;
 // A short status stays on one line only when both figures fit the selected ceiling.
 compact &= content <= inner;
 moneyHeight = aba(s, fb, fg, inner);
 title = fit(fh, s.title, inner - header);
 context = fit(fb, s.context, inner);
 names = new String[rows];
 crops = new Rectangle[rows];
 for (int i = 0; i < rows; i++) {
  Row row = s.rows.get(i);
  names[i] = fit(fb, row.name, inner - ICON - 12 - right(fb, row));
  crops[i] = row.icon == null ? null : visible(row.icon);
 }
 restHeight = PAD * 2 + fh.getHeight() + (compact ? 0 : 1 + moneyHeight + (s.progress >= 0d ? 5 : 0)
 + (context.isEmpty() ? 0 : fb.getHeight()));
 trayHeight = !tray ? 0 : 2 + fb.getHeight() + rows * ICON + (s.more.isEmpty() ? 0 : fb.getHeight());
 size = new Dimension(width, restHeight + trayHeight);
}

/** Net leads; a target, new change or rate that cannot share its line wraps below it. */
int aba(Cb s, FontMetrics fb, FontMetrics fg, int inner) {
 String[] values = {s.net, s.target, s.trip, rateUp ? "" : s.rate};
 int x = steady(fg, s.net);
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
static Rectangle visible(BufferedImage image) {
 int w = image.getWidth();
 int h = image.getHeight();
 int[] ayd = image.getRGB(0, 0, w, h, null, 0, w);
 int ayi = w;
 int ayj = h;
 int ayg = -1;
 int ayh = -1;
 for (int i = 0; i < ayd.length; i++) {
  if (ayd[i] >>> 24 != 0) {
   ayi = min(ayi, i % w);
   ayg = max(ayg, i % w);
   ayj = min(ayj, i / w);
   ayh = i / w;
  }
 }
 return ayg < 0 ? new Rectangle(w, h) : new Rectangle(ayi, ayj, ayg - ayi + 1, ayh - ayj + 1);
}

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
 Cr wanted = config.hudTextSize();
 if (wanted != fontSize) {
  fontSize = wanted;
  Font small = FontManager.getRunescapeSmallFont();
  Font regular = FontManager.getRunescapeFont();
  Font heavy = FontManager.getRunescapeBoldFont();
  // RuneLite's pixel fonts only render cleanly at their own size, so sizes pick fonts
  // rather than scale them: Small, Normal, and Large (the bold face throughout).
  body = wanted == Cr.Small ? small : wanted == Cr.Large ? heavy : regular;
  bold = wanted == Cr.Small ? regular : heavy;
  big = wanted == Cr.Small ? regular : body;
 }
 int alpha = config.hudOpacity();
 if (alpha != opacity) {
  opacity = alpha;
  background = new Color(PANEL.getRed(), PANEL.getGreen(), PANEL.getBlue(), max(0, min(255, alpha * 255 / 100)));
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

Color background() {
 fonts();
 return background;
}

static Color awf(Cb.Gem gem, boolean motion, long now) {
 switch (gem) {
  case LIVE: return TEAL;
  case PAUSED: return CORAL;
  case AWAY:
  return motion ? new Color(255, 127, 80, 110 + (int) (100 * abs(sin(now / 400d)))) : CORAL.darker();
  case PVP: return Tone.LOSS.color;
  case OFF:
  default: return Kit.OFF;
 }
}

/** Width of text as {@link #text} draws it. */
static int width(FontMetrics metrics, String value) {
 return value == null ? 0 : metrics.stringWidth(value.replace('−', '-'));
}

/** Text with the one-pixel shadow every RuneLite overlay uses. */
static void text(Graphics2D g, Font font, String value, int x, int y, Color color) {
 if (Ag.empty(value)) return;
 // RuneLite's font has no true minus sign (U+2212); a hyphen reads the same.
 String shown = value.replace('−', '-');
 g.setFont(font);
 g.setColor(Color.BLACK);
 g.drawString(shown, x + 1, y + 1);
 g.setColor(color);
 g.drawString(shown, x, y);
}
}
