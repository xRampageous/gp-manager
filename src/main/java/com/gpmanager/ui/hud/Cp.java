package com.gpmanager;
import static com.gpmanager.Ak.msg;
import com.gpmanager.HudTray.*;
import com.gpmanager.Cb.*;
import com.gpmanager.Kit.Tone;
import lombok.RequiredArgsConstructor;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Predicate;
import static com.gpmanager.Ae.*;
import static com.gpmanager.Ad.hourly;
import static java.util.Locale.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.Kit.Tone.*;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Fmt.*;
/**
* Builds one {@link Cb} per game tick from the same Live snapshot the sidebar paints,
* plus the tray and the moments. It reads, formats and publishes; it never books anything.
*/
@RequiredArgsConstructor
class Cp {
static final Color GOLD = new Color(255, 200, 40);
/** The tray slides open and shut over this long; the chip fades out over it. */
static final long FADE_MILLIS = 300L;
/** The chip beside Net shows each booking's change this long, independent of the tray. */
static final long CHIP_MILLIS = 4_000L;
/** Item sprites by id and stack size, so coins show their pile rather than one coin. */
interface Icons {
 BufferedImage of(int /* itemId */b, int /* quantity */c);
}

/** Counts at which stack sprites (coins) change; bucketing keeps the sprite cache small. */
static final int[] STACKS = {10_000, 1_000, 250, 100, 25, 5, 4, 3, 2};
final GpManagerConfig config;
final Icons icons;
final HudTray tray = new HudTray();
final HudMoments moments = new HudMoments();
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
/** The streak that just ended: its final count holds the context line for a few seconds. */
boolean streakLive;
long streakEndedUntil;
volatile String interaction = "";
volatile Cb current = Cb.HIDDEN;
HudTray tray() {
 return tray;
}

Cb current() {
 return current;
}

/**
* A factory reset replaced the bound profile: every presentation trace of the old trip is
* dropped, so HUD+ comes back exactly as on a new install. EDT only.
*/
void agj() {
 tray.clear();
 moments.reset();
 sessionOwner = "";
 lineColor = DIM.color;
 trayOpenedAt = 0L;
 baseKey = null;
 streakLive = false;
 streakEndedUntil = 0L;
 current = Cb.HIDDEN;
}

long aix() {
 return config.hudTraySeconds() * 1_000L;
}

boolean xy() {
 return config.hudTrayKeeps() == GpManagerConfig.HudTrayKeeps.SESSION;
}

/** The NPC the player is fighting; empty when released or not a fight. */
void interaction(String /* name */a, boolean /* combat */b) {
 interaction = a == null || !b ? "" : a.trim();
}

/** The fight target; the shared activity label reads only whether there is one. */
String vx() {
 return interaction;
}

/**
* @param visible the display loot filter; hidden rows stay unnamed, and only their count shows
*/
Cb update(Ca s, Predicate<Ab> /* visible */b, long /* now */c) {
 String /* owner */d = s.sessionId;
 if (!d.equals(sessionOwner)) {
  // Free play to a Grind (or back) starts a clean tray; the first sighting keeps what is there.
  if (!sessionOwner.isEmpty()) tray.clear();
  sessionOwner = d;
   baseKey = null;
  trayOpenedAt = 0L;
 }
 String /* moment */e = moments.update(s, tray.bigDrop(c, HudMoments.SHOW_MILLIS), c);
 boolean /* grind */f = s.hasSession && !s.freePlay;
 // A new streak measures its Net from the last tick before its first event.
 String /* avk */g = tray.avk();
 if (!g.equals(baseKey)) {
  long /* base */i = g.equals(prevKey) ? prevBase : baseKey == null ? s.net : lastNet;
  prevKey = baseKey;
  prevBase = streakBase;
  streakBase = i;
  baseKey = g;
 }
 lastNet = s.net;
 // Nothing booked yet reads as a dash, never a zero (the sidebar's first-run rule).
 boolean /* booked */j = s.hasSession && (s.gains != 0L || s.costs != 0L || s.marketResult != 0L || !s.recent.isEmpty());
 tray.keepMillis(config.streakEndSeconds() * 1_000L);
 // Owner 2026-10-01: a streak that just ended says so with its final count, and the folio
 // keeps it as "Last streak" until the next streak refreshes the tray.
 Map.Entry<String, Integer> /* liveStreak */k = tray.streak(c);
 if (k == null && streakLive && tray.kills > 0 && !tray.npcs.isEmpty()) {
  streakEndedUntil = c + HudMoments.SHOW_MILLIS;
 }
 streakLive = k != null;
 if (e.isEmpty() && !streakLive && c < streakEndedUntil && tray.kills > 0) {
  e = "Streak ended · " + tray.names() + " ×" + tray.kills;
 }
 // "Hide when not tracking": nothing to track, logged out or paused hides HUD+; Free play shows
 // once something is booked (observed, unbooked tray activity does not count). A moment, such as
 // "<Grind> ended", still shows for its few seconds.
 boolean /* live */l = s.hasSession && !s.loggedOut && !s.paused && !s.idle;
 boolean /* shown */m = config.showHud() && (!config.hudHideWhenIdle() || !e.isEmpty()
 || l && (f || j || tray.aup()));
 if (!m) {
  current = Cb.HIDDEN;
  return current;
 }
 boolean /* tracking */n = s.hasSession && !s.loggedOut;
 Gem /* gem */o = !n ? Gem.OFF : s.paused && !s.idle ? Gem.PAUSED : s.idle ? Gem.AWAY
 : s.pvpPossible ? Gem.PVP : Gem.LIVE;
 String /* target */p = "";
 double /* progress */q = -1d;
 if (s.goal != null) {
  p = " / " + s.goal.label;
  q = s.net <= 0L ? 0d : max(0d, min(1d, s.goal.fraction));
 }
 String /* rate */r = !s.hasSession ? "—" : s.rateEstablished ? Fmt.rate(s.gpPerHour) + "/h" : "Calculating…";
 boolean /* always */t = config.hudTray() == GpManagerConfig.HudTray.ALWAYS_OPEN;
 // Owner 2026-10-03: hidden loot never opens or holds the tray open; an open tray's footer counts it.
 var /* listed */u = new ArrayList<Entry>();
 long /* shownAt */v = 0L;
 int /* hidden */w = 0;
 for (Entry /* entry */x : tray.entries()) {
  if (b.test(flowOf(x))) {
   u.add(x);
   v = max(v, x.touched);
  } else if (x.state != State.TRADED) {
   w++;
  }
 }
 boolean /* open */y = !u.isEmpty() && (t || c - v < aix() + FADE_MILLIS);
 trayOpenedAt = !y ? 0L : trayOpenedAt == 0L ? c : trayOpenedAt;
 var /* rows */z = new ArrayList<Row>();
 // The chip beside Net is the newest item's change; the tray row keeps the running total.
 long /* chipUntil */aa = tray.lastDeltaAt() == 0L ? 0L : tray.lastDeltaAt() + CHIP_MILLIS;
 long /* trip */ab = c < aa ? tray.lastDelta() : 0L;
 // A big drop keeps its row however much cheaper loot follows it; "+N more" takes the rest.
 int /* plain */ac = config.hudTrayRows();
 for (Entry /* entry */ad : u) if (ad.gold) ac--;
 for (Entry /* entry */ae : u) {
  if (y && z.size() < config.hudTrayRows() && (ae.gold || ac-- > 0)) z.add(row(ae));
 }
 int /* more */af = y ? u.size() - z.size() : 0;
 String /* footer */ag = af > 0 ? "+" + af + " more" : "";
 if (y && w > 0) ag = (ag.isEmpty() ? "" : ag + " · ") + "+" + w + " hidden";
 String /* context */ah = e.isEmpty() ? qe(s) : e;
 // Owner 2026-10-01 (F18): a moment never hides unresolved Review; a compact count rides along.
 if (!e.isEmpty() && s.reviewCount > 0) ah = ah + " \u00b7 (!) " + s.reviewCount;
 Color /* contextColor */ai = e.isEmpty() ? lineColor : e.startsWith("Big drop") ? GOLD : GAIN.color;
 current = new Cb(true, o, title(s, c), s.hasSession ? timer(s.elapsedMillis) : "",
 j ? signed(s.net) : "—", j ? tone(s.net) : DIM.color,
 p, q, ab != 0L ? signed(ab) : "", tone(ab), aa, r, ah,
 ai, e.startsWith("Big drop"), trayLabel(s, c), z, ag,
 tray.tripId(), t ? 0L : v + aix(), trayOpenedAt,
 c);
 return current;
}

/**
* Owner 1.1: HUD+ stays clean. A running kill streak reads "KC: 12" (spawns and side targets
* aside, and only while the fight is fresh); otherwise a named Grind shows its name and Free play
* shows nothing. Activity names (Combat, Woodcutting) are for the sidebar.
*/
String title(Ca s, long /* now */a) {
 Map.Entry<String, Integer> /* streak */b = tray.streak(a);
 if (b != null && s.hasSession && !interaction.isEmpty()) return "KC: " + b.getValue();
 return s.hasSession && !s.freePlay ? Fmt.activity(s.ownerLabel) : "";
}

/**
* Needs review, else Reclaim waiting. Pace, PvP streak, skull and risk live in the sidebar
* (owner 2026-09-28, 1.1); the PvP gem marks danger. Sets {@link #lineColor}.
*/
String qe(Ca s) {
 lineColor = DIM.color;
 if (s.reviewCount > 0) {
  lineColor = REVIEW.color;
  return "(!) " + s.reviewCount + (s.reviewCount == 1 ? " item needs review" : " items need review");
 }
 if (s.reclaimItems > 0L) {
  return "Reclaim waiting · " + s.reclaimItems + (s.reclaimItems == 1L ? " item" : " items");
 }
 if (s.calibrating) return "Calibrating\u2026";
 return "";
}

static boolean counted(Entry /* entry */a) {
 return a.state != State.PENDING && a.state != State.TRADED;
}

static Ab flowOf(Entry /* entry */b) {
 // A hidden item stays off the tray whichever way it moved (owner 2026-09-28: a dropped one too).
 return new Ab(b.itemId, b.name, b.quantity, (int) min(Integer.MAX_VALUE, b.unitPrice), b.value);
}

Row row(Entry /* entry */a) {
 // "(u)" is the game's own short form for uncharged, as in "Craw's bow (u)" (owner 1.2).
 return new Row(icon(a), a.name.replace(" (uncharged)", " (u)") + " " + times(a.quantity),
 a.state.tag, value(a), color(a), a.gold, a.born);
}

static String value(Entry /* entry */a) {
 return a.value == 0L && a.unitPrice == 0L ? "?"
 : counted(a) || a.state == State.TRADED ? signed(a.value) : compact(a.value);
}

static Color color(Entry /* entry */a) {
 return a.gold ? GOLD : a.state == State.LOST ? LOSS.color : a.state == State.TRADED ? MARKET.color
 : counted(a) ? GAIN.color : DIM.color;
}

/** The tray heading; a kill streak adds its Net per kill (owner 1.1: the hover panel is gone). */
String trayLabel(Ca s, long /* now */a) {
 Map.Entry<String, Integer> /* streak */b = tray.streak(a);
 return b == null || b.getValue() < 2 || !s.hasSession ? tray.label()
 : tray.label() + " \u00b7 " + signed((s.net - streakBase) / b.getValue()) + "/kill";
}

BufferedImage icon(Entry /* entry */a) {
 return icons == null || a.itemId <= 0 || !config.showItemIcons() ? null
 : icons.of(a.itemId, stack(a.quantity));
}

static int stack(long /* quantity */a) {
 for (int /* stack */b : STACKS) {
  if (a >= b) return b;
 }
 return 1;
}

String timer(long /* millis */a) {
 if (config.hudTimer() == GpManagerConfig.HudTimer.Full) return clock(a);
 long /* total */b = nonNeg(a) / 1000L;
 long h = b / 3600L;
 long m = b % 3600L / 60L;
 return h > 0L ? String.format(ROOT, "%dh%02dm", h, m) : String.format(ROOT, "%dm%02ds", m, b % 60L);
}

static Color tone(long /* value */a) {
 return Tone.sign(a).color;
}
}
