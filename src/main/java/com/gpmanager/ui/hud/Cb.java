package com.gpmanager;
import lombok.RequiredArgsConstructor;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;
import static java.util.Collections.*;
/**
* Everything HUD+ and its folio paint, built once per game tick by {@link Cp}. Strings are
* already formatted and colours already chosen, so painting only reads fields.
*/
@RequiredArgsConstructor
class Cb {
static final Cb HIDDEN = new Cb(false, Gem.OFF, "", "", "", Color.WHITE, "", -1d,
"", Color.WHITE, 0L, "", "", Color.WHITE, false, "", emptyList(), "", 0, 0L, 0L,
emptyList(), emptyList(), 0L);
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
}
/** One folio line: a section header, a label/value pair, a progress bar or an item row. */
@RequiredArgsConstructor
static class Line {
enum Kind { HEADER, PAIR, BAR, ITEM }
final Kind kind;
final String label;
final String value;
final Color color;
/** Bar fill 0..1 for {@link Kind#BAR}. */
final double fill;
final BufferedImage icon;
}
final boolean visible;
final Gem gem;
final String title;
final String timer;
final String net;
final Color netColor;
/** " / 100M" beside Net when a Net target is set, else empty. */
final String target;
/** Target bar fill 0..1, or -1 with no target. Never negative for a losing Net. */
final double progress;
/** The newest booking's change beside Net ("+23") for a few seconds, whether or not the tray shows. */
final String trip;
final Color tripColor;
/** When the chip is gone; it fades out just before. */
final long chipUntil;
/** "1.93M/h", "Calculating…" or "—". */
final String rate;
final String context;
final Color contextColor;
/** A big drop's moment: HUD+ wears a gold edge while it shows. */
final boolean gold;
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
final List<Line> folio;
/** The End card, shown in the folio's place for a few seconds after a Grind ends; else empty. */
final List<Line> recap;
/** The builder's clock when this snapshot was made; HUD+ animates on it. */
final long builtAt;
}
