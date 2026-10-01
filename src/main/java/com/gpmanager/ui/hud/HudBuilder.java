package com.gpmanager;
import static com.gpmanager.GameData.msg;
import com.gpmanager.HudTray.*;
import com.gpmanager.HudSnapshot.*;
import com.gpmanager.Kit.Tone;
import lombok.RequiredArgsConstructor;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Predicate;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.Session.hourly;
import static java.util.Locale.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.Kit.Tone.*;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Fmt.*;
/**
* Builds one {@link HudSnapshot} per game tick from the same Live snapshot the sidebar paints,
* plus the tray and the moments. It reads, formats and publishes; it never books anything.
*/
@RequiredArgsConstructor
class HudBuilder {
static final Color GOLD = new Color(255, 200, 40);
/** The tray slides open and shut over this long; the chip fades out over it. */
static final long FADE_MILLIS = 300L;
/** The chip beside Net shows each booking's change this long, independent of the tray. */
static final long CHIP_MILLIS = 4_000L;
/** The losing-money line compares Net with about ten minutes ago: 21 samples 30 s apart. */
static final long RECAP_MILLIS = 10_000L;
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
/** The running Grind's average GP/h over its completed runs, kept for its recap. */
Long grindAverage;
List<Line> recap = Collections.emptyList();
long recapUntil;
Color lineColor = DIM.color;
long trayOpenedAt;
/** The streak the per-kill line measures, and Net just before it began. */
String baseKey;
long streakBase;
long lastNet;
/** The streak that just ended: its final count holds the context line for a few seconds. */
boolean streakLive;
long streakEndedUntil;
volatile String interaction = "";
volatile HudSnapshot current = HudSnapshot.HIDDEN;
HudTray tray() {
 return tray;
}

HudSnapshot current() {
 return current;
}

/**
* A factory reset replaced the bound profile: every presentation trace of the old trip is
* dropped, so HUD+ comes back exactly as on a new install. EDT only.
*/
void resetPresentation() {
 tray.clear();
 moments.reset();
 recap = Collections.emptyList();
 recapUntil = 0L;
 grindAverage = null;
 sessionOwner = "";
 lineColor = DIM.color;
 trayOpenedAt = 0L;
 baseKey = null;
 streakLive = false;
 streakEndedUntil = 0L;
 current = HudSnapshot.HIDDEN;
}

long stayMillis() {
 return config.hudTraySeconds() * 1_000L;
}

boolean keepSession() {
 return config.hudTrayKeeps() == GpManagerConfig.HudTrayKeeps.SESSION;
}

/** What the player is interacting with ("Oak tree"); empty when released. */
void interaction(String name) {
 interaction = name == null ? "" : name.trim();
}

/** The freshest client-thread interaction target; the shared activity label reads it. */
String interactionTarget() {
 return interaction;
}

/**
* @param visible the display loot filter; hidden rows stay unnamed, and only their count shows
* @param history the running Grind's completed runs: best Net, best GP/h and average GP/h
*     ({@code Long.MIN_VALUE} when unknown), or null without history
*/
HudSnapshot update(LiveSnapshot s, Predicate<Flow> visible, long[] history, long now) {
 String owner = s.sessionId;
 // Preserve the ended run's detail before resetting its tray for the next owner.
 String endedDrop = tray.bestDrop();
 if (!owner.equals(sessionOwner)) {
  // Free play to a Grind (or back) starts a clean tray; the first sighting keeps what is there.
  if (sessionOwner.isEmpty()) tray.resetSession();
  else tray.clear();
  sessionOwner = owner;
   baseKey = null;
  trayOpenedAt = 0L;
 }
 Long average = known(history, 2);
 String moment = moments.update(s, known(history, 0), known(history, 1), tray.bigDrop(now, HudMoments.SHOW_MILLIS), now);
 String ended = moments.takeEnded();
 if (!ended.isEmpty()) {
  recap = recap(ended, moments.endedNet, moments.endedMillis, endedDrop, grindAverage);
  recapUntil = now + RECAP_MILLIS;
 }
 boolean grind = s.hasSession && !s.freePlay;
 // A new streak measures its Net from the last tick before its first event.
 String streakKey = tray.streakKey();
 if (!streakKey.equals(baseKey)) {
  streakBase = baseKey == null ? s.net : lastNet;
  baseKey = streakKey;
 }
 lastNet = s.net;
 if (grind) grindAverage = average;
 // Nothing booked yet reads as a dash, never a zero (the sidebar's first-run rule).
 boolean booked = s.hasSession && (s.gains != 0L || s.costs != 0L || s.marketResult != 0L || !s.recent.isEmpty());
 tray.keepMillis(config.streakEndSeconds() * 1_000L);
 // Owner 2026-10-01: a streak that just ended says so with its final count, and the folio
 // keeps it as "Last streak" until the next streak refreshes the tray.
 java.util.Map.Entry<String, Integer> liveStreak = tray.streak(now);
 if (liveStreak == null && streakLive && tray.kills > 0 && !tray.source.isEmpty()) {
  streakEndedUntil = now + HudMoments.SHOW_MILLIS;
 }
 streakLive = liveStreak != null;
 if (moment.isEmpty() && !streakLive && now < streakEndedUntil && tray.kills > 0) {
  moment = "Streak ended · " + Fmt.activity(tray.source) + " ×" + tray.kills;
 }
 List<Line> card = now < recapUntil ? recap : Collections.<Line>emptyList();
 // "Hide when not tracking": nothing to track, logged out or paused hides HUD+; Free play shows
 // once something is booked (observed, unbooked tray activity does not count). A Grind's
 // closing recap card still shows.
 boolean live = s.hasSession && !s.loggedOut && !s.paused && !s.idle;
 boolean shown = config.showHud() && (!config.hudHideWhenIdle() || !card.isEmpty()
 || live && (grind || booked || tray.hasBooked() || !moment.isEmpty()));
 if (!shown) {
  current = HudSnapshot.HIDDEN;
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
 boolean open = tray.open(now, stayMillis() + FADE_MILLIS, always);
 trayOpenedAt = !open ? 0L : trayOpenedAt == 0L ? now : trayOpenedAt;
 List<Entry> entries = tray.entries();
 var rows = new ArrayList<Row>();
 var visibleEntries = new ArrayList<Entry>();
 int shownRows = 0;
 int hiddenRows = 0;
 // The chip beside Net is the newest item's change; the tray row keeps the running total.
 long chipUntil = tray.lastDeltaAt() == 0L ? 0L : tray.lastDeltaAt() + CHIP_MILLIS;
 long trip = now < chipUntil ? tray.lastDelta() : 0L;
 for (Entry entry : entries) {
  if (!visible.test(flowOf(entry))) {
   if (entry.state == State.RECEIVED || entry.state == State.CLAIMED || entry.state == State.PENDING) hiddenRows++;
  } else {
   // Owner 2026-10-01 (F16): the folio shares the tray's filter, so hidden names never leak.
   visibleEntries.add(entry);
   if (open && shownRows++ < config.hudTrayRows()) rows.add(row(entry));
  }
 }
 int more = shownRows - rows.size();
 String context = moment.isEmpty() ? contextLine(s) : moment;
 // Owner 2026-10-01 (F18): a moment never hides unresolved Review; a compact count rides along.
 if (!moment.isEmpty() && s.reviewCount > 0) context = context + " \u00b7 (!) " + s.reviewCount;
 Color contextColor = moment.isEmpty() ? lineColor : moment.startsWith("Big drop") ? GOLD : GAIN.color;
 current = new HudSnapshot(true, gem, title(s, now), s.hasSession ? timer(s.elapsedMillis) : "",
 booked ? signed(s.net) : "—", booked ? tone(s.net) : DIM.color,
 target, progress, trip != 0L ? signed(trip) : "", tone(trip), chipUntil, rate, context,
 contextColor, moment.startsWith("Big drop"), tray.label(), rows, more > 0 ? "+" + more + " more" : "",
 tray.tripId(), always ? 0L : tray.foldAt(stayMillis()), trayOpenedAt,
 folio(s, visibleEntries, average, now, hiddenRows), card, now);
 return current;
}

/** The shared activity label: fresh NPC target, else the session activity, else the Grind name. */
String title(LiveSnapshot s, long now) {
 String name = ActivityLabel.resolve(interaction, s.activityLabel, s.ownerLabel, s.hasSession, s.freePlay);
 // A running kill streak names the title, spawns and side targets aside: "G. Nechryael ×12".
 // Only while the target is still fresh: a stale streak must not outlive the activity label.
 java.util.Map.Entry<String, Integer> streak = tray.streak(now);
 return streak != null && streak.getValue() >= 2 && s.hasSession && !interaction.isEmpty()
 ? Fmt.activity(streak.getKey()) + " ×" + streak.getValue() : name;
}

/**
* Needs review, else Reclaim waiting, else the PvP line. Pace
* lives only in the sidebar (owner 2026-09-28); kills show in the title and the folio. Sets {@link #lineColor}.
*/
String contextLine(LiveSnapshot s) {
 lineColor = DIM.color;
 if (s.reviewCount > 0) {
  lineColor = REVIEW.color;
  return "(!) " + s.reviewCount + (s.reviewCount == 1 ? " item needs review" : " items need review");
 }
 if (s.reclaimItems > 0L) {
  return "Reclaim waiting · " + s.reclaimItems + (s.reclaimItems == 1L ? " item" : " items");
 }
 if (s.pvpPossible) {
  lineColor = s.skulled ? LOSS.color : lineColor;
  return "Streak " + s.streak + " · " + (s.skulled ? "Skulled" : "No skull") + (s.protectItem ? " · Protect on" : "");
 }
 if (s.calibrating) return "Calibrating\u2026";
 return "";
}

static Long known(long[] history, int index) {
 return history == null || history.length <= index || history[index] == Long.MIN_VALUE ? null : history[index];
}

/** The End card: Net, time, GP/h, best drop and how the run compares with the Grind's average. */
static List<Line> recap(String name, long net, long millis, String bestDrop, Long average) {
 var lines = new ArrayList<Line>();
 header(lines, name.toUpperCase(ROOT) + " \u00b7 ENDED");
 pair(lines, "Net", signed(net), tone(net));
 pair(lines, "Active time", durationCompact(millis), PLAIN.color);
 if (millis >= 60_000L) {
  long rate = hourly(net, millis);
  pair(lines, "GP/h", Fmt.rate(rate) + "/h", tone(rate));
  if (average != null && average != 0L) {
   pair(lines, "vs your average", percent(rate, average), tone(rate - average));
  }
 }
 if (!bestDrop.isEmpty()) pair(lines, "Best drop", bestDrop, GOLD);
 return unmodifiableList(lines);
}

/** "+12%" / "−8%" of a value against a reference. */
static String percent(long value, long reference) {
 long pct = round((value - reference) * 100d / Math.abs(reference));
 return (pct >= 0L ? "+" : "\u2212") + Math.abs(pct) + "%";
}

static boolean counted(Entry entry) {
 return entry.state != State.PENDING && entry.state != State.TRADED;
}

static Flow flowOf(Entry entry) {
 // A hidden item stays off the tray whichever way it moved (owner 2026-09-28: a dropped one too).
 return new Flow(entry.itemId, entry.name, entry.quantity, (int) min(Integer.MAX_VALUE, entry.unitPrice), entry.value);
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

/** One composer for the Standard and PvP folio; unset targets collapse. */
List<Line> folio(LiveSnapshot s, List<Entry> trip, Long average, long now, int hiddenLoot) {
 var lines = new ArrayList<Line>();
 if (s.pvpSession) {
  header(lines, "PVP GRIND · " + (s.activityLabel.isEmpty() ? s.ownerLabel : s.activityLabel));
  pair(lines, "Net", signed(s.net), tone(s.net));
  pair(lines, msg("ex"), s.kills + " · " + s.deaths + " · "
  + String.format(ROOT, "%.2f", s.deaths == 0 ? s.kills : s.kills / (double) s.deaths), PLAIN.color);
  pair(lines, "Player loot", signed(s.killNet), GAIN.color);
  if (s.gains > s.killNet) pair(lines, "Other revenue", signed(s.gains - s.killNet), GAIN.color);
  if (s.costSplitAvailable) {
   pair(lines, "Supplies", signed(-Math.abs(s.supplies)), SUPPLY.color);
   pair(lines, "Deaths / losses", signed(-Math.abs(s.deathLoss)), LOSS.color);
  } else {
   // Owner 2026-10-01 (F19): an unknown split shows the complete Costs, never a zero Supplies.
   pair(lines, "Costs", signed(-Math.abs(s.costs)), SUPPLY.color);
  }
  pair(lines, "Best kill", signed(s.bestKill), GAIN.color);
  pair(lines, "Streak", s.streak + " (best " + s.bestStreak + ")", PLAIN.color);
  pair(lines, "Skull · Protect", (s.skulled ? "Skulled" : "No skull") + " · "
  + (s.protectItem ? "on" : "off"), s.skulled ? LOSS.color : DIM.color);
 } else {
  header(lines, s.freePlay ? "FREE PLAY" : "GRIND · " + s.ownerLabel);
  pair(lines, "Gains", signed(s.gains), GAIN.color);
  if (s.costSplitAvailable) {
   pair(lines, "Supplies", signed(-Math.abs(s.supplies)), SUPPLY.color);
   pair(lines, "Losses", signed(-Math.abs(s.loss)), LOSS.color);
  } else {
   pair(lines, "Costs", signed(-Math.abs(s.costs)), SUPPLY.color);
  }
  if (s.marketResult != 0L) pair(lines, "Market", signed(s.marketResult), MARKET.color);
 }
 if (s.rateEstablished) {
  pair(lines, "GP/h", Fmt.rate(s.gpPerHour) + "/h", tone(s.gpPerHour));
 }
 if (s.rateEstablished && average != null && average != 0L && !s.freePlay) {
  pair(lines, "vs your average", percent(s.gpPerHour, average) + " (" + Fmt.rate(average) + "/h)",
  tone(s.gpPerHour - average));
 }
 java.util.Map.Entry<String, Integer> streak = tray.streak(now);
 String kills = streak == null || streak.getValue() < 2 || !s.hasSession ? ""
 : signed((s.net - streakBase) / streak.getValue()) + " · " + streak.getValue() + " kills";
 if (!kills.isEmpty()) pair(lines, "Per kill", kills, PLAIN.color);
 if (streak == null && tray.kills > 0 && !tray.source.isEmpty()) {
  pair(lines, "Last streak", Fmt.activity(tray.source) + " ×" + tray.kills, PLAIN.color);
 }
 String best = tray.bestDrop();
 if (!best.isEmpty()) pair(lines, "Best drop", best, GOLD);
 if (s.elapsedMillis >= 60_000L && s.costSplitAvailable && s.supplies != 0L) {
  pair(lines, "Supplies/h", signed(hourly(-SafeMath.abs(s.supplies), s.elapsedMillis)) + "/h", SUPPLY.color);
 }
 targets(lines, s);
 if (!trip.isEmpty()) {
  // Owner 2026-10-01 (F16): the scope is the retention mode's own label; hidden names never leak.
  header(lines, s.freePlay ? "RECENT" : config.hudTrayKeeps().toString().toUpperCase(ROOT));
  if (hiddenLoot > 0) pair(lines, "Hidden loot:", Integer.toString(hiddenLoot), DIM.color);
  for (Entry entry : trip.subList(0, min(8, trip.size()))) {
   String tag = entry.state.tag.isEmpty() ? "" : entry.state.tag + " ";
   lines.add(new Line(Line.Kind.ITEM, entry.name + " " + times(entry.quantity),
   tag + value(entry), color(entry), 0d, icon(entry)));
  }
  // Owner 2026-10-01 (F16): a truncated list names what it left out.
  if (trip.size() > 8) pair(lines, "+" + (trip.size() - 8) + " more", "", DIM.color);
 } else if (hiddenLoot > 0) {
  // Disclosed even when every row is hidden: the count survives, the names do not.
  pair(lines, "Hidden loot:", Integer.toString(hiddenLoot), DIM.color);
 }
 return unmodifiableList(lines);
}

static void targets(List<Line> lines, LiveSnapshot s) {
 if (s.goal == null && s.timeTargetMillis <= 0L) return;
 header(lines, "TARGETS");
 if (s.goal != null) {
  lines.add(new Line(Line.Kind.BAR, "Net " + s.goal.label, s.goal.progress,
  s.goal.reached ? GAIN.color : ACCENT, s.net <= 0L ? 0d : s.goal.fraction, null));
 }
 if (s.timeTargetMillis > 0L) {
  double fill = s.elapsedMillis / (double) s.timeTargetMillis;
  lines.add(new Line(Line.Kind.BAR, "Time " + durationCompact(s.timeTargetMillis),
  fill >= 1d ? "reached" : durationCompact(s.elapsedMillis), fill >= 1d ? GAIN.color : ACCENT, fill, null));
 }
 if (s.timeTargetMillis > s.elapsedMillis && s.rateEstablished) {
  long projected = safeAdd(s.net, round(s.gpPerHour * ((s.timeTargetMillis - s.elapsedMillis) / 3_600_000d)));
  pair(lines, "At " + durationCompact(s.timeTargetMillis), "~" + signed(projected), tone(projected));
 }
}

static void header(List<Line> lines, String text) {
 lines.add(new Line(Line.Kind.HEADER, text, "", LABEL, 0d, null));
}

static void pair(List<Line> lines, String label, String value, Color color) {
 lines.add(new Line(Line.Kind.PAIR, label, value, color, 0d, null));
}

BufferedImage icon(Entry entry) {
 return icons == null || entry.itemId <= 0 ? null : icons.of(entry.itemId, stack(entry.quantity));
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
