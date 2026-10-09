package com.gpmanager;
import lombok.RequiredArgsConstructor;
import java.util.*;
import static java.util.Locale.*;
import static java.lang.Math.*;
import static com.gpmanager.Ai.*;
/**
* The HUD+ item tray: what the current streak received, claimed, lost or traded. A streak is one
* run of the same work: fighting one NPC, or one skill. Attacking another NPC, another kind of
* work, or a quiet gap starts the next streak, so one NPC's loot never lands on another's tray. Fed on the
* client thread by kills, bookings and reward sightings, read on the EDT by {@link Cp}.
* Presentation only; nothing here books or values money, it only mirrors the bookings.
*/
class HudTray {
/** A single drop worth this much lights its row gold and raises a moment, unless Ground Items sets it. */
static final long BIG_DROP = 10_000_000L;
long big = BIG_DROP;
/** Bounded presentation mirror of canonical receipts eligible for own-drop recovery. */
static final int MAX_DROP_RECEIPTS = 64;
enum State {
 RECEIVED(""), PENDING("pending"), CLAIMED("claimed"), LOST("lost"), RECOVERED("recovered"), TRADED("");
 String tag;
 State(String /* tag */c) {
  this.tag = c;
 }
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
 /** The last time this entry grew; the tray opens and folds by its visible entries' times. */
 long touched;
}

static class DropReceipt {
 Ac transaction;
 int itemId;
 long lastQuantity;
 long lastValue;
 DropReceipt(Ac /* transaction */c, int /* itemId */d) {
  this.transaction = c;
  this.itemId = d;
 }
}

Map<String, Entry> entries = new LinkedHashMap<>();
/** Receipt references are presentation-only and live no longer than the tray streak/session. */
List<DropReceipt> dropReceipts = new ArrayList<>();
/** The NPC this streak first killed ("" before its first kill), every NPC it killed, and its kills. */
String source = "";
Set<String> npcs = new LinkedHashSet<>();
int kills;
/** The streak a switch of target ended, kept through its quiet gap: switching back merges it. */
Map<String, Entry> prevEntries = new LinkedHashMap<>();
List<DropReceipt> prevDrops = new ArrayList<>();
Set<String> prevNpcs = new LinkedHashSet<>();
String prevSource = "";
int prevKills;
int prevTrip;
long prevEventAt;
long lastEventAt;
/** When an item last arrived: the tray opens on items only, never on a kill or an attack. */
/** Changes with every new streak (and on clear); HUD+ rebases the streak's Net and width on it. */
int tripId;
String label = "Looted";
String bigDrop = "";
/** What the newest booking added (counted rows only): the chip beside Net. */
long lastDelta;
long lastDeltaAt;
long bigDropAt;
/** A booking settled; mirror its visible item flows onto the tray. */
synchronized void booked(Ac /* transaction */b, long /* now */c, boolean /* xy */d) {
 if (b == null) return;
 if (!b.isCounted() || b.getType() == Ai.TRANSFER
 || b.getCorrection() == Ah.TRANSFER || b.getType() == UNCERTAIN
 // Owner 2026-09-28: eating and drinking stay off HUD+ (a sip's "(3)" is no drop).
 || b.getActionKind() == Au.EAT || b.getActionKind() == Au.DRINK
 || Bn.wi(b.getFlows())) {
  return;
 }
 boolean /* market */e = b.getContext() == Aj.MARKET || b.getType() == TRADE;
 boolean /* claimed */f = b.getActionKind() == Au.DEFERRED_CLAIM || !b.uo().isEmpty();
 if (!npcs.isEmpty() && b.getNote().startsWith("Loot from ")
 && !has(npcs, b.getActivityName())) {
  // Another NPC's leftover drop, picked up after this streak began: it counts in Net, not here.
  return;
 }
 boolean /* reclaim */g = (b.getActivityName() + " " + b.getNote()).toLowerCase(ROOT).contains("reclaim");
 // Only deaths, drops and destroys ride the tray as lost; other item use is routine.
 String /* dropped */h = Ag.has(b.getNote(), "Dropped", "Destroyed") ? b.getNote() : "";
 boolean /* lost */i = b.tm() == PK_DEATH_LOSS
 || b.getContext() == Aj.PK_DEATH || !h.isEmpty();
 // Owner 2026-10-09: opening caskets or impling jars is one row per container with its total, so
 // a stack opened never floods the tray; a big drop still gets its own gold row.
 Ab /* container */aa = opened(b.getFlows());
 if (aa != null && !e && !i) {
  event(c, d, "Opened", null);
  long /* net */ab = 0L;
  long /* count */ac = 0L;
  for (Ab /* flow */ad : b.getFlows()) {
   if (ad == null) continue;
   ab += ad.valueDelta;
   if (ad.isCost() && ad.itemId == aa.itemId) ac -= ad.quantityDelta;
   if (ad.isGain() && ad.valueDelta >= big) {
    add(ad.itemId, ad.itemName, State.RECEIVED, ad.unitPrice, ad.quantityDelta, ad.valueDelta, c, 0L).gold = true;
    bigDrop = ad.itemName;
    bigDropAt = c;
   }
  }
  add(aa.itemId, aa.itemName, State.RECEIVED, 0L, ac, ab, c, 0L);
  lastDelta = ab;
  lastDeltaAt = c;
  return;
 }
 boolean /* any */j = false;
 long /* delta */k = 0L;
 // Owner 2026-10-09: a trade rides the tray as its item, bought or sold, with the coins it took
 // or brought when it is the one item; its coins never get a row of their own.
 long /* coins */ae = 0L;
 int /* items */af = 0;
 for (Ab /* flow */ag : b.getFlows()) {
  if (ag != null && ag.itemId == 995) ae += ag.valueDelta;
  else if (ag != null) af++;
 }
 for (Ab /* flow */l : b.getFlows()) {
  if (l == null || l.quantityDelta == 0L || e && l.itemId == 995) continue;
  boolean /* gain */m = l.quantityDelta > 0L;
  // Routine rune/ammo/charge use never rides the tray, but a pickup is a drop
  // like any other (owner 2026-09-29).
  if (!m && !e && routine(l.itemName)) continue;
  if (!m && !e && !i) continue;
  if (!j) {
   // A drop or a GE or player trade joins the running trip; on an empty tray it reads "Dropped"
   // or "Traded" (owners 2026-09-28, 2026-10-09).
   event(c, d, f ? "Claimed" : e || !h.isEmpty() ? null : i ? "Lost"
   : verb(b, l.itemName, c), null);
   if ((e || !h.isEmpty()) && entries.isEmpty()) label = e ? "Traded" : h;
   j = true;
  }
  int /* id */n = l.itemId;
  long /* quantity */o = abs(l.quantityDelta);
  State /* state */p = e ? State.TRADED : !m ? State.LOST : g ? State.RECOVERED
  : f ? State.CLAIMED : State.RECEIVED;
  long /* born */q = 0L;
  if (p == State.RECEIVED || p == State.CLAIMED) {
   // A claim turns its pending row into Claimed in place, keeping its first-seen identity.
   Entry /* pending */r = entries.get(key(State.PENDING, n));
   if (r != null && take(State.PENDING, n, o) > 0L) {
    p = State.CLAIMED;
    q = r.born;
   }
  }
  Entry /* entry */t = add(n, l.itemName, p, l.unitPrice, o, !e ? l.valueDelta : af == 1 && ae != 0L ? abs(ae) : abs(l.valueDelta), c, q);
  k += p == State.TRADED ? 0L : l.valueDelta;
  if (m && p != State.TRADED && l.valueDelta >= big) {
   t.gold = true;
   bigDrop = l.itemName;
   bigDropAt = c;
  }
 }
 if (j && k != 0L) {
  lastDelta = k;
  lastDeltaAt = c;
 }
 afq(b);
}

/**
* A reward window reported loot it is holding; nothing is counted until it settles. Ground
* loot never comes here: the tray lists what was received, so a pickup appears once, in place.
*/
synchronized void observed(int /* itemId */a, String /* name */b, long /* quantity */c, long /* unitPrice */d, long /* now */e, boolean /* xy */f) {
 if (c <= 0L) return;
 event(e, f, null, null);
 add(a, b, State.PENDING, d, c, d * c, e, 0L);
}


/**
* One NPC loot event is one kill; a kill of another NPC starts that NPC's streak, unless it was
* killed in the streak before, still inside that streak's quiet gap: then both are one mixed
* streak again (owner 1.1).
*/
synchronized void kill(String /* npc */a, long /* now */b, boolean /* xy */c) {
 String /* name */d = a == null ? "" : a.trim();
 if (d.isEmpty() || "NPC loot".equals(d)) return;
 if (!has(npcs, d)) {
  if (kills > 0) {
   // The new target's first kill refreshes the tray to its own streak; loot already
   // booked before any kill stays (owner 2026-10-01).
   boolean /* back */e = has(prevNpcs, d) && b - prevEventAt < aef();
   var /* rows */f = new LinkedHashMap<String, Entry>(entries);
   var /* drops */g = new ArrayList<DropReceipt>(dropReceipts);
   var /* was */h = new LinkedHashSet<String>(npcs);
   int /* had */i = kills;
   int /* trip */j = tripId;
   long /* last */k = lastEventAt;
   String /* from */l = source;
   restart(c);
   if (e) {
    if (!c) {
     merge(prevEntries.values());
     merge(f.values());
     dropReceipts.addAll(prevDrops);
     dropReceipts.addAll(g);
    }
    npcs.addAll(prevNpcs);
    npcs.addAll(h);
    kills = prevKills + i;
    tripId = prevTrip;
    source = prevSource;
    prevNpcs = new LinkedHashSet<>();
   } else {
    prevEntries = f;
    prevDrops = g;
    prevNpcs = h;
    prevKills = i;
    prevTrip = j;
    prevEventAt = k;
    prevSource = l;
   }
  }
  label = "Looted";
 }
 event(b, c, "Looted", d);
 kills++;
}

/** The live kill streak ("Guard & Man", 5), or null before a kill or once the streak went quiet. */
synchronized Map.Entry<String, Integer> streak(long /* now */a) {
 return kills == 0 || a - lastEventAt >= aef() ? null : new AbstractMap.SimpleImmutableEntry<>(names(), kills);
}

/** The streak's NPCs: "Guard", "Guard & Man", "Guard, Man +1"; empty before a kill. */
synchronized String names() {
 var /* list */a = new ArrayList<String>();
 for (String /* npc */b : npcs) a.add(Fmt.activity(b));
 int n = a.size();
 return n == 0 ? "" : n == 1 ? a.get(0) : n == 2 ? a.get(0) + " & " + a.get(1)
 : a.get(0) + ", " + a.get(1) + " +" + (n - 2);
}

static boolean has(Set<String> /* names */a, String /* name */b) {
 for (String /* each */c : a) {
  if (c.equalsIgnoreCase(b)) return true;
 }
 return false;
}

/** Owner or Grind changed: start clean. */
synchronized void clear() {
 entries.clear();
 dropReceipts.clear();
 tripId++;
 source = "";
 npcs.clear();
 prevNpcs = new LinkedHashSet<>();
 kills = 0;
 lastEventAt = 0L;
 lastDelta = 0L;
 lastDeltaAt = 0L;
 bigDrop = "";
 bigDropAt = 0L;
 label = "Looted";
}


/** Newest arrivals first; a row keeps its place while its quantity grows. */
synchronized List<Entry> entries() {
 aec();
 var /* list */c = new ArrayList<Entry>(entries.values());
 // Owner 1.2: the row that last grew leads, so the newest loot is always on top.
 c.sort((a, b) -> a.touched != b.touched ? Long.compare(b.touched, a.touched) : Long.compare(b.born, a.born));
 return c;
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
synchronized boolean aup() {
 aec();
 return entries.values().stream().anyMatch(/* entry */a -> a.state != State.PENDING);
}

synchronized int tripId() {
 return tripId;
}

/** The tray heading: a kill streak's NPCs, else what the streak did ("Stole", "Claimed", "Mined" ...). */
synchronized String label() {
 return "Looted".equals(label) && !npcs.isEmpty() ? names() : label;
}

/** The newest big drop's name while it is fresh (moments show it a few seconds). */
synchronized String bigDrop(long /* now */a, long /* forMillis */b) {
 return a - bigDropAt < b ? bigDrop : "";
}

/**
* Every tray event belongs to a streak. A quiet gap, another kind of work ("Mined" after
* "Looted") or a kill of another NPC ends it; the next streak starts with an empty tray unless
* rows keep for the whole session. Folding only hides the tray; it never ends a streak.
*/
void event(long /* now */a, boolean /* xy */b, String /* kind */c, String /* npc */d) {
 if (lastEventAt > 0L && a - lastEventAt >= aef() || c != null && !c.equals(label)
 || d != null && !npcs.isEmpty() && !has(npcs, d)) {
  restart(b);
 }
 if (c != null) label = c;
 if (d != null) {
  if (source.isEmpty()) source = d;
  npcs.add(d);
 }
 lastEventAt = a;
}

void restart(boolean /* xy */a) {
 if (!a) {
  entries.clear();
  dropReceipts.clear();
 }
 tripId++;
 source = "";
 npcs.clear();
 kills = 0;
 lastEventAt = 0L;
}

/** Identity of the streak the per-kill line measures: a new streak, or its first kill. */
synchronized String avk() {
 return tripId + ":" + source;
}

/** A streak goes quiet after three of its own kill intervals: at least a minute, at most five. */
volatile long keepMillis = 60_000L;
/** Owner-set streak window: the tray ends a streak this long after its last kill. */
synchronized void keepMillis(long /* millis */a) {
 keepMillis = max(10_000L, min(3_600_000L, a));
}

long aef() {
 return keepMillis;
}

long take(State /* from */a, int /* itemId */b, long /* quantity */c) {
 Entry /* source */d = entries.get(key(a, b));
 if (d == null) return 0L;
 long /* moved */e = min(c, d.quantity);
 d.quantity -= e;
 d.value = d.unitPrice * d.quantity;
 if (d.quantity <= 0L) entries.remove(key(a, b));
 return e;
}

void merge(Collection<Entry> /* rows */a) {
 for (Entry /* row */b : a) {
  add(b.itemId, b.name, b.state, b.unitPrice, b.quantity, b.value, 0L, b.born).gold |= b.gold;
 }
}

Entry add(int /* itemId */a, String /* name */b, State /* state */c, long /* unitPrice */d, long /* quantity */e, long /* value */f, long /* now */g, long /* born */h) {
 Entry /* entry */i = entries.computeIfAbsent(key(c, a),
 /* ignored */j -> new Entry(a, b == null ? "Unknown item" : b, c, d));
 if (i.born == 0L) i.born = h != 0L ? h : g;
 i.touched = g;
 i.quantity += e;
 i.value += f;
 return i;
}

void afq(Ac /* transaction */a) {
 if (!"Dropped".equals(a.getNote()) || !a.isCounted() || !a.xe()
 || a.getCorrection() != Ah.AUTO) {
  return;
 }
 for (Ab /* flow */b : a.getFlows()) {
  if (b == null || !b.isCost() || b.itemId <= 0) continue;
  boolean /* known */c = dropReceipts.stream().anyMatch(/* receipt */d -> d.transaction == a
  && d.itemId == b.itemId);
  if (c) continue;
  DropReceipt /* receipt */e = new DropReceipt(a, b.itemId);
  long[] /* costs */f = nq(e);
  e.lastQuantity = f[0];
  e.lastValue = f[1];
  dropReceipts.add(e);
 }
 while (dropReceipts.size() > MAX_DROP_RECEIPTS) dropReceipts.remove(0);
}

synchronized void aec() {
 for (DropReceipt /* receipt */a : dropReceipts) {
  long[] /* costs */b = nq(a);
  long /* apu */c = a.lastQuantity - b[0];
  long /* apv */d = a.lastValue - b[1];
  if (c > 0L && d >= 0L) {
   afo(a.itemId, c, d);
  }
  a.lastQuantity = b[0];
  a.lastValue = b[1];
 }
}

long[] nq(DropReceipt /* receipt */a) {
 long /* quantity */b = 0L;
 long /* value */c = 0L;
 for (Ab /* flow */d : a.transaction.getFlows()) {
  if (d == null || d.itemId != a.itemId || !d.isCost()) continue;
  Bp.Ax /* amounts */e = Bp.flow(a.transaction, d);
  if (e.costs <= 0L) continue;
  b = Ae.safeAdd(b, Ae.abs(d.quantityDelta));
  c = Ae.safeAdd(c, e.costs);
 }
 return new long[] {b, c};
}

boolean afo(int /* itemId */a, long /* quantity */b, long /* value */c) {
 Entry /* entry */d = entries.get(key(State.LOST, a));
 if (d == null || b <= 0L || d.quantity < b) return false;
 d.quantity -= b;
 d.value = min(0L, Ae.safeAdd(d.value, c));
 if (d.quantity <= 0L) entries.remove(key(State.LOST, a));
 return true;
}

static String key(State /* state */a, int /* itemId */b) {
 return a.name() + ":" + b;
}

/**
* The casket or impling jar a booking opened: every cost is one, and something came out of it. A
* caught impling spends an empty "Impling jar", which is no opening.
*/
static Ab opened(List<Ab> /* flows */a) {
 return Bn.all(a, -1, /* name */b -> Ak.pattern("clue").matcher(b).matches() || b.endsWith(" impling jar"))
 && Bn.all(a, 1, /* name */c -> true) ? a.stream().filter(Ab::isCost).findFirst().get() : null;
}

/** Runes, ammunition and charges are routine use: they count in Net but never ride the tray. */
static boolean routine(String /* name */a) {
 // Runes, arrows, bolts, darts, javelins, throwing weapons and Zulrah's scales (d18 "routine").
 return Ak.pattern("routine").matcher(a == null ? "" : a.toLowerCase(ROOT)).matches();
}

static final List<String[]> VERBS = Ak.rows("d17");
static final Set<String> MINIONS = new HashSet<>();
static {
 Ak.rows("d16").forEach(/* row */c -> MINIONS.add(c[0]));
}

/** A boss's spawn (Greater Nechryael's death spawns): no header, streak, kill or activity of its own. */
static boolean minion(String /* npc */a) {
 return a != null && MINIONS.contains(Ag.lower(a));
}

/** The newest skilling XP's tray verb ("Mined") and when it arrived. */
String xpVerb = "Looted";
long xpAt;

/** Skill XP arrived: an item booked moments later is that skill's work. */
synchronized void xp(String /* skill */a, long /* now */b) {
 String /* kind */c = awl(a);
 if ("Looted".equals(c)) return;
 xpVerb = c;
 xpAt = b;
}

/**
* The tray verb for a receipt: a production run's own skill, else the skill whose XP came with
* the item, else the activity. The activity names a skill only after its second XP drop, so it
* can still be General, or the last skill, on a run's first item: every run stays one streak.
*/
String verb(Ac /* transaction */b, String /* item */c, long /* now */d) {
 String /* kind */e = b.getContext() == Aj.PRODUCTION ? awl(b.getNote()) : "Looted";
 if ("Looted".equals(e) && (b.getType() == GAIN || b.getType() == PROCESSING)
 && d - xpAt < 6_000L) {
  e = xpVerb;
 }
 if ("Looted".equals(e)) e = awl(b.getActivityName());
 // Smithing XP makes bars at a furnace and items at an anvil.
 return "Smithed".equals(e) && c != null && c.endsWith(" bar") ? "Smelted" : e;
}

static String awl(String /* activity */a) {
 String /* lower */b = a == null ? "" : a.toLowerCase(ROOT);
 for (String[] /* row */c : VERBS) {
  if (b.contains(c[0])) return c[1];
 }
 return "Looted";
}
}
