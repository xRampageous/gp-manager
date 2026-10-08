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
static final int ICON = 20;
int rowHeight = ICON;
int indent = ICON + 4;
static final Color TEAL = new Color(0, 200, 180);
static final Color CORAL = new Color(255, 127, 80);
static final Color GOLD_ROW = new Color(255, 200, 40, 50);
static final long FADE_IN_MILLIS = 350L;
Cp builder;
GpManagerConfig config;
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
int[] moneyX = new int[4];
int[] moneyY = new int[4];
int moneyHeight;
/** Wall clock minus the snapshot's clock, fixed when a snapshot is first painted. */
Cb clocked;
long clockOffset;
String title = "";
String context = "";
String[] names = {};
Rectangle[] crops = {};
De(Cp /* builder */a, GpManagerConfig /* config */b) {
 this.builder = a;
 this.config = b;
 setPosition(OverlayPosition.TOP_LEFT);
}

public Dimension render(Graphics2D g) {
 Cb s = builder.current();
 if (!s.visible) return null;
 fonts();
 FontMetrics /* fb */a = g.getFontMetrics(body);
 FontMetrics /* fh */b = g.getFontMetrics(bold);
 FontMetrics /* fg */c = g.getFontMetrics(big);
 int /* key */d = fontSize.ordinal() * 1_000 + config.hudMaxWidth();
 if (s != measured || d != measuredKey) {
  measured = s;
  measuredKey = d;
  measure(s, a, b, c);
 }
 long /* wall */e = Ae.now();
 if (s != clocked) {
  clocked = s;
  clockOffset = e - s.builtAt;
 }
 long /* now */f = e - clockOffset;
 float /* open */h = axf(s, f, !config.reducedMotion());
 int /* height */i = restHeight + round(trayHeight * h);
 if (size.height != i) size = new Dimension(size.width, i);
 // Bitmap faces and item sprites stay sharp without smoothing; restored afterwards.
 Object /* aqw */j = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
 Object /* aom */k = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
 Composite /* plain */l = g.getComposite();
 Shape /* ayf */m = g.getClip();
 g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
 g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
 try {
  paint(g, s, a, b, c, l, f, h);
 } finally {
  g.setComposite(l);
  g.setClip(m);
  if (j != null) g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, j);
  if (k != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, k);
 }
 return size;
}

/**
* How far the tray is open, 0..1: it eases open when it appears and shut once it folds. Its
* slide never waits on the chip beside Net.
*/
static float axf(Cb s, long /* now */a, boolean /* motion */b) {
 if (!axt(s)) return 0f;
 if (!b) return 1f;
 float /* opening */c = ease((a - s.openedAt) / (float) Cp.FADE_MILLIS);
 return s.foldAt == 0L ? c
 : min(c, ease((s.foldAt + Cp.FADE_MILLIS - a) / (float) Cp.FADE_MILLIS));
}

static float ease(float t) {
 float x = max(0f, min(1f, t));
 return x * x * (3f - 2f * x);
}

static boolean axt(Cb s) {
 return !s.rows.isEmpty() || !s.more.isEmpty();
}

void paint(Graphics2D g, Cb s, FontMetrics /* fb */b, FontMetrics /* fh */d, FontMetrics /* fg */e, Composite /* plain */f,
long /* now */j, float /* open */k) {
 int /* width */l = size.width;
 int /* right */m = l - PAD;
 boolean /* motion */n = !config.reducedMotion();
 g.setColor(background);
 g.fillRect(0, 0, l, size.height);
 if (s.gold) {
  g.setColor(Cp.GOLD);
  g.drawRect(0, 0, l - 1, size.height - 1);
 }
 int y = PAD;
 g.setColor(awf(s.gem, n, j));
 g.fillOval(PAD, y + d.getHeight() / 2 - 3, 7, 7);
 Color /* apq */o = s.rate.endsWith("/h") ? Color.WHITE : LABEL;
 if (rateUp) {
  text(g, body, s.timer, PAD + 11, y + d.getAscent(), LABEL);
  text(g, body, s.rate, m - width(b, s.rate), y + d.getAscent(), o);
 } else {
  text(g, bold, title, PAD + 11, y + d.getAscent(), Color.WHITE);
  text(g, body, s.timer, m - width(b, s.timer), y + d.getAscent(), LABEL);
 }
 if (compact) return;
 y += d.getHeight() + 1;
 // The chip keeps its own few seconds and fades on its own, whatever the tray is doing.
 float /* chip */p = !n ? 1f : ease((s.chipUntil - j) / (float) Cp.FADE_MILLIS);
 int /* baseline */q = y + e.getAscent();
 text(g, big, s.net, PAD, q, s.netColor);
 text(g, body, s.target, PAD + moneyX[1], q + moneyY[1], LABEL);
 fade(g, f, p);
 text(g, body, s.trip, PAD + moneyX[2], q + moneyY[2], s.tripColor);
 g.setComposite(f);
 if (!rateUp) text(g, body, s.rate, m - width(b, s.rate), q + moneyY[3], o);
 y += moneyHeight;
 if (s.progress >= 0d) {
  g.setColor(LINE);
  g.fillRect(PAD, y, l - PAD * 2, 3);
  g.setColor(s.progress >= 1d ? Tone.GAIN.color : ACCENT);
  g.fillRect(PAD, y, (int) round((l - PAD * 2) * s.progress), 3);
  y += 5;
 }
 if (!context.isEmpty()) {
  text(g, body, context, PAD, y + b.getAscent(), s.contextColor);
  y += b.getHeight();
 }
 if (trayHeight == 0) return;
 // The tray slides: clipped to the height it has opened to, and fading with it.
 g.clipRect(0, 0, l, size.height);
 fade(g, f, k);
 // The heading sits tight on its rows: no leading, no gap (owner 1.1).
 text(g, body, s.trayLabel, PAD, y + b.getAscent(), LABEL);
 y += b.getAscent() + b.getDescent();
 for (int i = 0; i < names.length; i++) {
  Row /* row */r = s.rows.get(i);
  fade(g, f, n ? min(k, (j - r.born) / (float) FADE_IN_MILLIS) : k);
  if (r.gold) {
   g.setColor(GOLD_ROW);
   g.fillRect(0, y, l, rowHeight);
  }
  if (r.icon != null && crops[i] != null) {
   Rectangle c = crops[i];
   // Sprites shrink smoothly to fit and are never blown up: a small sprite stays crisp.
   double /* scale */t = min(1d, ICON / (double) max(c.width, c.height));
   g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, t < 1d
   ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
   int w = (int) round(c.width * t);
   int h = (int) round(c.height * t);
   int /* dx */u = PAD + (ICON - w) / 2;
   int /* dy */v = y + (ICON - h) / 2;
   g.drawImage(r.icon, u, v, u + w, v + h, c.x, c.y, c.x + c.width, c.y + c.height, null);
  }
  int /* axl */z = y + (rowHeight + b.getAscent()) / 2 - 1;
  int /* avw */aa = m - width(b, r.value);
  text(g, body, names[i], PAD + indent, z, Color.WHITE);
  text(g, body, r.value, aa, z, r.color);
  text(g, body, r.tag, aa - 4 - width(b, r.tag), z, LABEL);
  y += rowHeight;
 }
 fade(g, f, k);
 text(g, body, s.more, PAD, y + b.getAscent(), LABEL);
}

static void fade(Graphics2D g, Composite /* plain */a, float /* alpha */b) {
 g.setComposite(b >= 1f ? a : AlphaComposite.SrcOver.derive(max(0f, b)));
}

/**
* Width, height, fitted text and sprite crops for one snapshot. While the tray is open the
* width only grows, so arriving rows never jolt it; once it folds HUD+ settles back.
*/
void measure(Cb s, FontMetrics /* fb */b, FontMetrics /* fh */c, FontMetrics /* fg */d) {
 int /* rows */e = s.rows.size();
 boolean /* tray */f = axt(s);
 compact = s.title.isEmpty() && "—".equals(s.net) && s.target.isEmpty() && s.trip.isEmpty()
 && s.context.isEmpty() && !f;
 rateUp = s.title.isEmpty();
 // Ticking figures are measured with their widest digits, so the width never wobbles per second.
 int /* header */g = 11 + 8 + steady(b, s.timer) + (rateUp ? steady(b, s.rate) : 0);
 int /* content */h = compact ? g : max(width(b, s.context), steady(d, s.net) + steady(b, s.target)
 + (s.trip.isEmpty() ? 0 : 6 + steady(b, s.trip)) + (rateUp ? 0 : 8 + steady(b, s.rate)));
 // Owner 1.1: with item icons off a row is text-high and starts at the edge.
 boolean /* icons */j = config.showItemIcons();
 rowHeight = j ? ICON : b.getAscent() + b.getDescent() + 2;
 indent = j ? ICON + 4 : 0;
 for (Row /* row */k : s.rows) h = max(h, indent + 8 + width(b, k.name) + right(b, k));
 h = max(h, width(b, s.more));
 int /* ceiling */l = max(MIN_WIDTH, min(MAX_WIDTH, config.hudMaxWidth()));
 int /* width */m = max(MIN_WIDTH, min(l, max(h, width(c, s.title) + g) + PAD * 2));
 if (!f || s.tripId != widthTrip) {
  widthTrip = s.tripId;
  grownWidth = 0;
 }
 grownWidth = !f ? 0 : min(l, max(grownWidth, m));
 m = max(m, grownWidth);
 int /* inner */n = m - PAD * 2;
 // A short status stays on one line only when both figures fit the selected ceiling.
 compact &= h <= n;
 moneyHeight = aba(s, b, d, n);
 title = fit(c, s.title, n - g);
 context = fit(b, s.context, n);
 names = new String[e];
 crops = new Rectangle[e];
 for (int i = 0; i < e; i++) {
  Row /* row */o = s.rows.get(i);
  // Owner 1.2: a long name shortens before its count, so the "×2" always shows.
  String /* count */p = o.name.substring(max(0, o.name.lastIndexOf(" ×")));
  if (!p.startsWith(" ×")) p = "";
  names[i] = fit(b, o.name.substring(0, o.name.length() - p.length()),
  n - indent - 8 - right(b, o) - width(b, p)) + p;
  crops[i] = o.icon == null ? null : Kit.visible(o.icon);
 }
 restHeight = PAD * 2 + c.getHeight() + (compact ? 0 : 1 + moneyHeight + (s.progress >= 0d ? 5 : 0)
 + (context.isEmpty() ? 0 : b.getHeight()));
 trayHeight = !f ? 0 : b.getAscent() + b.getDescent() + e * rowHeight + (s.more.isEmpty() ? 0 : b.getHeight());
 size = new Dimension(m, restHeight + trayHeight);
}

/** Net leads; a target, new change or rate that cannot share its line wraps below it. */
int aba(Cb s, FontMetrics /* fb */a, FontMetrics /* fg */b, int /* inner */c) {
 String[] /* values */d = {s.net, s.target, s.trip, rateUp ? "" : s.rate};
 int x = steady(b, s.net);
 int y = 0;
 for (int i = 1; i < d.length; i++) {
  if (d[i].isEmpty()) {
   moneyX[i] = x;
   moneyY[i] = y;
   continue;
  }
  int /* gap */e = d[i].isEmpty() || i == 1 ? 0 : i == 2 ? 6 : 8;
  int /* width */f = steady(a, d[i]);
  if (x + e + f > c) {
   x = 0;
   y += b.getHeight();
   e = 0;
  }
  moneyX[i] = x + e;
  moneyY[i] = y;
  x += e + f;
 }
 return y + b.getHeight();
}

/** Width of text with every digit counted as the font's widest digit. */
static int steady(FontMetrics /* metrics */a, String /* value */b) {
 char /* widest */c = '0';
 for (char /* digit */d = '1'; d <= '9'; d++) {
  c = a.charWidth(d) > a.charWidth(c) ? d : c;
 }
 return width(a, b.replaceAll("[0-9]", String.valueOf(c)));
}

/** A row's value and tag, the part that never shortens. */
static int right(FontMetrics /* fb */a, Row /* row */b) {
 return width(a, b.value) + (b.tag.isEmpty() ? 0 : width(a, b.tag) + 4);
}

/** Bounds of a sprite's visible pixels; the whole image while it is still loading (blank). */
/** The text, or its longest start that fits {@code room} pixels followed by "…". */
static String fit(FontMetrics /* metrics */a, String /* value */b, int /* room */c) {
 if (width(a, b) <= c) return b;
 int /* budget */d = c - a.stringWidth("…");
 int /* low */e = 0;
 int /* high */f = b.length();
 while (e < f) {
  int /* middle */g = (e + f + 1) >>> 1;
  if (width(a, b.substring(0, g)) <= d) e = g;
  else f = g - 1;
 }
 return b.substring(0, e).trim() + "…";
}

void fonts() {
 Cr /* wanted */a = config.hudTextSize();
 if (a != fontSize) {
  fontSize = a;
  Font /* small */b = FontManager.getRunescapeSmallFont();
  Font /* regular */c = FontManager.getRunescapeFont();
  Font /* heavy */d = FontManager.getRunescapeBoldFont();
  // RuneLite's pixel fonts only render cleanly at their own size, so sizes pick fonts
  // rather than scale them: Small, Normal, and Large (the bold face throughout).
  body = a == Cr.Small ? b : a == Cr.Large ? d : c;
  bold = a == Cr.Small ? c : d;
  big = a == Cr.Small ? c : body;
 }
 int /* alpha */e = config.hudOpacity();
 if (e != opacity) {
  opacity = e;
  background = new Color(PANEL.getRed(), PANEL.getGreen(), PANEL.getBlue(), max(0, min(255, e * 255 / 100)));
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


static Color awf(Cb.Gem /* gem */a, boolean /* motion */b, long /* now */c) {
 switch (a) {
  case LIVE: return TEAL;
  case PAUSED: return CORAL;
  case AWAY:
  return b ? new Color(255, 127, 80, 110 + (int) (100 * abs(sin(c / 400d)))) : CORAL.darker();
  case PVP: return Tone.LOSS.color;
  case OFF:
  default: return Kit.OFF;
 }
}

/** Width of text as {@link #text} draws it. */
static int width(FontMetrics /* metrics */a, String /* value */b) {
 return b == null ? 0 : a.stringWidth(b.replace('−', '-'));
}

/** Text with the one-pixel shadow every RuneLite overlay uses. */
static void text(Graphics2D g, Font /* font */b, String /* value */c, int x, int y, Color /* color */d) {
 if (Ag.empty(c)) return;
 // RuneLite's font has no true minus sign (U+2212); a hyphen reads the same.
 String /* shown */e = c.replace('−', '-');
 g.setFont(b);
 g.setColor(Color.BLACK);
 g.drawString(e, x + 1, y + 1);
 g.setColor(d);
 g.drawString(e, x, y);
}
}
