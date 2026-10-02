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
 BufferedImage of(int itemId, int quantity);
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
void interaction(String name, boolean combat) {
 interaction = name == null || !combat ? "" : name.trim();
}

/** The fight target; the shared activity label reads only whether there is one. */
String vx() {
 return interaction;
}

/**
* @param visible the display loot filter; hidden rows stay unnamed, and only their count shows
*/
Cb update(Ca s, Predicate<Ab> visible, long now) {
 String owner = s.sessionId;
 if (!owner.equals(sessionOwner)) {
  // Free play to a Grind (or back) starts a clean tray; the first sighting keeps what is there.
  if (!sessionOwner.isEmpty()) tray.clear();
  sessionOwner = owner;
   baseKey = null;
  trayOpenedAt = 0L;
 }
 String moment = moments.update(s, tray.bigDrop(now, HudMoments.SHOW_MILLIS), now);
 boolean grind = s.hasSession && !s.freePlay;
 // A new streak measures its Net from the last tick before its first event.
 String avk = tray.avk();
 if (!avk.equals(baseKey)) {
  long base = avk.equals(prevKey) ? prevBase : baseKey == null ? s.net : lastNet;
  prevKey = baseKey;
  prevBase = streakBase;
  streakBase = base;
  baseKey = avk;
 }
 lastNet = s.net;
 // Nothing booked yet reads as a dash, never a zero (the sidebar's first-run rule).
 boolean booked = s.hasSession && (s.gains != 0L || s.costs != 0L || s.marketResult != 0L || !s.recent.isEmpty());
 tray.keepMillis(config.streakEndSeconds() * 1_000L);
 // Owner 2026-10-01: a streak that just ended says so with its final count, and the folio
 // keeps it as "Last streak" until the next streak refreshes the tray.
 Map.Entry<String, Integer> liveStreak = tray.streak(now);
 if (liveStreak == null && streakLive && tray.kills > 0 && !tray.npcs.isEmpty()) {
  streakEndedUntil = now + HudMoments.SHOW_MILLIS;
 }
 streakLive = liveStreak != null;
 if (moment.isEmpty() && !streakLive && now < streakEndedUntil && tray.kills > 0) {
  moment = "Streak ended · " + tray.names() + " ×" + tray.kills;
 }
 // "Hide when not tracking": nothing to track, logged out or paused hides HUD+; Free play shows
 // once something is booked (observed, unbooked tray activity does not count). A moment, such as
 // "<Grind> ended", still shows for its few seconds.
 boolean live = s.hasSession && !s.loggedOut && !s.paused && !s.idle;
 boolean shown = config.showHud() && (!config.hudHideWhenIdle() || !moment.isEmpty()
 || live && (grind || booked || tray.aup()));
 if (!shown) {
  current = Cb.HIDDEN;
  return current;
 }
 boolean tracking = s.hasSession && !s.loggedOut;
 Gem gem = !tracking ? Gem.OFF : s.paused && !s.idle ? Gem.PAUSED : s.idle ? Gem.AWAY
 : s.pvpPossible ? Gem.PVP : Gem.LIVE;
 String target = "";
 double progress = -1d;
 if (s.goal != null) {
  target = " / " + s.goal.label;
  progress = s.net <= 0L ? 0d : max(0d, min(1d, s.goal.fraction));
 }
 String rate = !s.hasSession ? "—" : s.rateEstablished ? Fmt.rate(s.gpPerHour) + "/h" : "Calculating…";
 boolean always = config.hudTray() == GpManagerConfig.HudTray.ALWAYS_OPEN;
 boolean open = tray.open(now, aix() + FADE_MILLIS, always);
 trayOpenedAt = !open ? 0L : trayOpenedAt == 0L ? now : trayOpenedAt;
 List<Entry> entries = tray.entries();
 var rows = new ArrayList<Row>();
 int aqm = 0;
 // The chip beside Net is the newest item's change; the tray row keeps the running total.
 long chipUntil = tray.lastDeltaAt() == 0L ? 0L : tray.lastDeltaAt() + CHIP_MILLIS;
 long trip = now < chipUntil ? tray.lastDelta() : 0L;
 for (Entry entry : entries) {
  if (visible.test(flowOf(entry)) && open && aqm++ < config.hudTrayRows()) rows.add(row(entry));
 }
 int more = aqm - rows.size();
 String context = moment.isEmpty() ? qe(s) : moment;
 // Owner 2026-10-01 (F18): a moment never hides unresolved Review; a compact count rides along.
 if (!moment.isEmpty() && s.reviewCount > 0) context = context + " \u00b7 (!) " + s.reviewCount;
 Color contextColor = moment.isEmpty() ? lineColor : moment.startsWith("Big drop") ? GOLD : GAIN.color;
 current = new Cb(true, gem, title(s, now), s.hasSession ? timer(s.elapsedMillis) : "",
 booked ? signed(s.net) : "—", booked ? tone(s.net) : DIM.color,
 target, progress, trip != 0L ? signed(trip) : "", tone(trip), chipUntil, rate, context,
 contextColor, moment.startsWith("Big drop"), trayLabel(s, now), rows, more > 0 ? "+" + more + " more" : "",
 tray.tripId(), always ? 0L : tray.foldAt(aix()), trayOpenedAt,
 now);
 return current;
}

/**
* Owner 1.1: HUD+ stays clean. A running kill streak reads "KC: 12" (spawns and side targets
* aside, and only while the fight is fresh); otherwise a named Grind shows its name and Free play
* shows nothing. Activity names (Combat, Woodcutting) are for the sidebar.
*/
String title(Ca s, long now) {
 Map.Entry<String, Integer> streak = tray.streak(now);
 if (streak != null && s.hasSession && !interaction.isEmpty()) return "KC: " + streak.getValue();
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

static boolean counted(Entry entry) {
 return entry.state != State.PENDING && entry.state != State.TRADED;
}

static Ab flowOf(Entry entry) {
 // A hidden item stays off the tray whichever way it moved (owner 2026-09-28: a dropped one too).
 return new Ab(entry.itemId, entry.name, entry.quantity, (int) min(Integer.MAX_VALUE, entry.unitPrice), entry.value);
}

Row row(Entry entry) {
 return new Row(icon(entry), entry.name + " " + times(entry.quantity),
 entry.state.tag, value(entry), color(entry), entry.gold, entry.born);
}

static String value(Entry entry) {
 return entry.value == 0L && entry.unitPrice == 0L ? "?"
 : counted(entry) || entry.state == State.TRADED ? signed(entry.value) : compact(entry.value);
}

static Color color(Entry entry) {
 return entry.gold ? GOLD : entry.state == State.LOST ? LOSS.color : entry.state == State.TRADED ? MARKET.color
 : counted(entry) ? GAIN.color : DIM.color;
}

/** The tray heading; a kill streak adds its Net per kill (owner 1.1: the hover panel is gone). */
String trayLabel(Ca s, long now) {
 Map.Entry<String, Integer> streak = tray.streak(now);
 return streak == null || streak.getValue() < 2 || !s.hasSession ? tray.label()
 : tray.label() + " \u00b7 " + signed((s.net - streakBase) / streak.getValue()) + "/kill";
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
 long h = total / 3600L;
 long m = total % 3600L / 60L;
 return h > 0L ? String.format(ROOT, "%dh%02dm", h, m) : String.format(ROOT, "%dm%02ds", m, total % 60L);
}

static Color tone(long value) {
 return Tone.sign(value).color;
}
}
