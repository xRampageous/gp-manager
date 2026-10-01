package com.gpmanager;
import com.gpmanager.HudSnapshot.Line;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.*;
import static java.lang.Math.*;
/**
* The HUD+ detail panel, shown while the mouse rests on HUD+ (Detail panel: Hover). It always
* opens to the right, never flips, and slides left only as far as the screen edge needs; HUD+
* itself never moves. It reads the current snapshot and nothing else.
*/
class HudFolio extends Overlay {
/** Minimum width; long names widen it rather than overlap their values. */
static final int WIDTH = 160;
static final int LINE = 16;
final HudBuilder builder;
final HudOverlay hud;
final GpManagerConfig config;
/** Mouse position on the game canvas, or null when unknown. */
final Supplier<Point> mouse;
final Supplier<Integer> canvasWidth;
final Supplier<Integer> canvasHeight;
HudFolio(HudBuilder builder, HudOverlay hud, GpManagerConfig config, Supplier<Point> mouse,
Supplier<Integer> canvasWidth, Supplier<Integer> canvasHeight) {
 this.builder = builder;
 this.hud = hud;
 this.config = config;
 this.mouse = mouse;
 this.canvasWidth = canvasWidth;
 this.canvasHeight = canvasHeight;
 setPosition(OverlayPosition.DYNAMIC);
 setLayer(OverlayLayer.ABOVE_WIDGETS);
}

public Dimension render(Graphics2D g) {
 HudSnapshot s = builder.current();
 Point pointer = mouse.get();
 Rectangle hudBounds = hud.getBounds();
 int canvasW = canvasWidth == null || canvasWidth.get() == null ? 0 : canvasWidth.get();
 int canvasH = canvasHeight == null || canvasHeight.get() == null ? 0 : canvasHeight.get();
 List<Line> lines = hovering(hudBounds, pointer) ? s.folio : s.recap;
 // Owner 2026-10-01 (F09): the folio is bounded to the visible canvas. Trailing tray
 // lines trim first, counted by one "+N more" line; the rest is clamped inside the canvas.
 if (canvasH > 0 && hudBounds != null) {
  lines = fitLines(lines, max(1, (canvasH - hudBounds.y - HudOverlay.PAD * 2) / LINE));
 }
 Font body = hud.body();
 Font bold = hud.bold();
 FontMetrics fm = g.getFontMetrics(body);
 int width = WIDTH;
 for (Line line : lines) {
  width = max(width, HudOverlay.PAD * 2 + 22 + HudOverlay.width(fm, line.label) + 8 + HudOverlay.width(fm, line.value));
 }
 int height = lines.isEmpty() ? 0 : HudOverlay.PAD * 2 + lines.size() * LINE;
 Rectangle at = place(s, hudBounds, pointer, canvasW > 0 ? canvasW : null, canvasH, width, height);
 if (at == null) return null;
 g.setColor(hud.background());
 g.fillRect(at.x, at.y, at.width, at.height);
 int y = at.y + HudOverlay.PAD;
 int left = at.x + HudOverlay.PAD;
 int right = at.x + at.width - HudOverlay.PAD;
 for (Line line : lines) {
  int baseline = y + fm.getAscent();
  boolean item = line.kind == Line.Kind.ITEM;
  if (item && line.icon != null) {
   g.drawImage(line.icon, left, y, line.icon.getWidth() * (LINE - 1) / 32, LINE - 1, null);
  }
  // The label region ellipsizes before it can overlap the right-aligned value.
  Font labelFont = line.kind == Line.Kind.HEADER ? bold : body;
  FontMetrics labelMetrics = line.kind == Line.Kind.HEADER ? g.getFontMetrics(bold) : fm;
  int valueWidth = line.kind == Line.Kind.HEADER ? 0 : HudOverlay.width(fm, line.value) + 8;
  int labelLeft = left + (item ? 22 : 0);
  HudOverlay.text(g, labelFont, HudOverlay.fit(labelMetrics, line.label, max(0, right - labelLeft - valueWidth)),
  labelLeft, baseline, item ? Color.WHITE : Kit.LABEL);
  if (line.kind != Line.Kind.HEADER) {
   HudOverlay.text(g, body, line.value, right - HudOverlay.width(fm, line.value), baseline, line.color);
   if (line.kind == Line.Kind.BAR) {
    g.setColor(Kit.LINE);
    g.fillRect(left, y + LINE - 3, right - left, 3);
    g.setColor(line.color);
    g.fillRect(left, y + LINE - 3, (int) round((right - left) * max(0d, min(1d, line.fill))), 3);
   }
  }
  y += LINE;
 }
 return null;
}

/** The prefix of lines that fits in {@code room} lines, with one "+N more" counting the tail. */
static List<Line> fitLines(List<Line> lines, int room) {
 if (room <= 0 || lines.size() <= room) return lines;
 int keep = max(0, room - 1);
 var out = new ArrayList<Line>(lines.subList(0, min(keep, lines.size())));
 out.add(new Line(Line.Kind.PAIR, "+" + (lines.size() - out.size()) + " more", "", Kit.Tone.DIM.color, 0d, null));
 return out;
}

boolean hovering(Rectangle hudBounds, Point mousePoint) {
 return config.hudDetail() == GpManagerConfig.HudDetail.Hover && hudBounds != null && mousePoint != null
 && hudBounds.contains(mousePoint.getX(), mousePoint.getY());
}

/**
* Where the folio sits, or null when it should not show: on hover (Detail panel: Hover), or
* while the End card shows. Opens right and slides left only as far as the edge needs; the
* width and height stay inside the canvas when it is known (owner 2026-10-01, F09).
*/
Rectangle place(HudSnapshot s, Rectangle hudBounds, Point mousePoint, Integer canvasWidth,
int canvasHeight, int width, int height) {
 List<Line> lines = hovering(hudBounds, mousePoint) ? s.folio : s.recap;
 if (!s.visible || lines.isEmpty() || height <= 0 || hudBounds == null || hudBounds.width <= 0) return null;
 int x = hudBounds.x + hudBounds.width + 4;
 if (canvasWidth != null && canvasWidth > 0) {
  width = min(width, max(LINE, canvasWidth - 8));
  x = max(0, min(x, canvasWidth - width));
 }
 int y = hudBounds.y;
 if (canvasHeight > 0) y = max(0, min(y, canvasHeight - height));
 return new Rectangle(x, y, width, height);
}
}
