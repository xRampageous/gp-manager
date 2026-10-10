package com.gpmanager;

import static com.gpmanager.TransactionType.*;

import static java.lang.Math.*;
import static java.util.Locale.*;

import lombok.RequiredArgsConstructor;

import java.util.*;

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
        RECEIVED(""),
        PENDING("pending"),
        CLAIMED("claimed"),
        LOST("lost"),
        RECOVERED("recovered"),
        TRADED("");
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
        if (!transaction.isCounted()
                || transaction.getType() == TransactionType.TRANSFER
                || transaction.getCorrection() == Correction.TRANSFER
                || transaction.getType() == UNCERTAIN
                // Owner 2026-09-28: eating and drinking stay off HUD+ (a sip's "(3)" is no drop).
                || transaction.getActionKind() == ActionKind.EAT
                || transaction.getActionKind() == ActionKind.DRINK
                || ActionReader.isDoseStep(transaction.getFlows())) {
            return;
        }
        boolean market =
                transaction.getContext() == Context.MARKET || transaction.getType() == TRADE;
        boolean claimed =
                transaction.getActionKind() == ActionKind.DEFERRED_CLAIM
                        || !transaction.sourceClaimId().isEmpty();
        if (!npcs.isEmpty()
                && transaction.getNote().startsWith("Loot from ")
                && !has(npcs, transaction.getActivityName())) {
            // Another NPC's leftover drop, picked up after this streak began: it counts in Net, not
            // here.
            return;
        }
        boolean reclaim =
                (transaction.getActivityName() + " " + transaction.getNote())
                        .toLowerCase(ROOT)
                        .contains("reclaim");
        // Only deaths, drops and destroys ride the tray as lost; other item use is routine.
        String dropped =
                Phrases.has(transaction.getNote(), "Dropped", "Destroyed")
                        ? transaction.getNote()
                        : "";
        boolean lost =
                transaction.autoType() == PK_DEATH_LOSS
                        || transaction.getContext() == Context.PK_DEATH
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
                if (flow == null) continue;
                net += flow.valueDelta;
                if (flow.isCost() && flow.itemId == container.itemId) count -= flow.quantityDelta;
                if (flow.isGain() && flow.valueDelta >= big) {
                    add(
                                            flow.itemId,
                                            flow.itemName,
                                            State.RECEIVED,
                                            flow.unitPrice,
                                            flow.quantityDelta,
                                            flow.valueDelta,
                                            now,
                                            0L)
                                    .gold =
                            true;
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
            if (flow != null && flow.itemId == 995) coins += flow.valueDelta;
            else if (flow != null) items++;
        }
        for (Flow flow : transaction.getFlows()) {
            if (flow == null || flow.quantityDelta == 0L || market && flow.itemId == 995) continue;
            boolean gain = flow.quantityDelta > 0L;
            // Routine rune/ammo/charge use never rides the tray, but a pickup is a drop
            // like any other (owner 2026-09-29).
            if (!gain && !market && routine(flow.itemName)) continue;
            if (!gain && !market && !lost) continue;
            if (!any) {
                // A drop or a GE or player trade joins the running trip; on an empty tray it reads
                // "Dropped"
                // or "Traded" (owners 2026-09-28, 2026-10-09).
                event(
                        now,
                        xy,
                        claimed
                                ? "Claimed"
                                : market || !dropped.isEmpty()
                                        ? null
                                        : lost ? "Lost" : verb(transaction, flow.itemName, now),
                        null);
                if ((market || !dropped.isEmpty()) && entries.isEmpty())
                    label = market ? "Traded" : dropped;
                any = true;
            }
            int id = flow.itemId;
            long quantity = abs(flow.quantityDelta);
            State state =
                    market
                            ? State.TRADED
                            : !gain
                                    ? State.LOST
                                    : reclaim
                                            ? State.RECOVERED
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
            Entry entry =
                    add(
                            id,
                            flow.itemName,
                            state,
                            flow.unitPrice,
                            quantity,
                            !market
                                    ? flow.valueDelta
                                    : items == 1 && coins != 0L ? abs(coins) : abs(flow.valueDelta),
                            now,
                            born);
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
    synchronized void observed(
            int itemId, String name, long quantity, long unitPrice, long now, boolean xy) {
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
        return kills == 0 || now - lastEventAt >= quietMillis()
                ? null
                : new AbstractMap.SimpleImmutableEntry<>(names(), kills);
    }

    /** The streak's NPCs: "Guard", "Guard & Man", "Guard, Man +1"; empty before a kill. */
    synchronized String names() {
        var list = new ArrayList<String>();
        for (String npc : npcs) list.add(Fmt.activity(npc));
        int n = list.size();
        return n == 0
                ? ""
                : n == 1
                        ? list.get(0)
                        : n == 2
                                ? list.get(0) + " & " + list.get(1)
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
        list.sort(
                (a, b) ->
                        a.touched != b.touched
                                ? Long.compare(b.touched, a.touched)
                                : Long.compare(b.born, a.born));
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
        if (lastEventAt > 0L && now - lastEventAt >= quietMillis()
                || kind != null && !kind.equals(label)
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
            add(
                                    row.itemId,
                                    row.name,
                                    row.state,
                                    row.unitPrice,
                                    row.quantity,
                                    row.value,
                                    0L,
                                    row.born)
                            .gold |=
                    row.gold;
        }
    }

    Entry add(
            int itemId,
            String name,
            State state,
            long unitPrice,
            long quantity,
            long value,
            long now,
            long born) {
        Entry entry =
                entries.computeIfAbsent(
                        key(state, itemId),
                        ignored ->
                                new Entry(
                                        itemId,
                                        name == null ? "Unknown item" : name,
                                        state,
                                        unitPrice));
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
        return ActionReader.all(
                                flows,
                                -1,
                                name ->
                                        GameData.pattern("clue").matcher(name).matches()
                                                || name.endsWith(" impling jar"))
                        && ActionReader.all(flows, 1, name -> true)
                ? flows.stream().filter(Flow::isCost).findFirst().get()
                : null;
    }

    /** Runes, ammunition and charges are routine use: they count in Net but never ride the tray. */
    static boolean routine(String name) {
        // Runes, arrows, bolts, darts, javelins, throwing weapons and Zulrah's scales (d18
        // "routine").
        return GameData.pattern("routine")
                .matcher(name == null ? "" : name.toLowerCase(ROOT))
                .matches();
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
        String kind =
                transaction.getContext() == Context.PRODUCTION
                        ? labelFor(transaction.getNote())
                        : "Looted";
        if ("Looted".equals(kind)
                && (transaction.getType() == GAIN || transaction.getType() == PROCESSING)
                && now - xpAt < 6_000L) {
            kind = xpVerb;
        }
        if ("Looted".equals(kind)) kind = labelFor(transaction.getActivityName());
        // Smithing XP makes bars at a furnace and items at an anvil.
        return "Smithed".equals(kind) && item != null && item.endsWith(" bar") ? "Smelted" : kind;
    }

    static String labelFor(String activity) {
        String lower = activity == null ? "" : activity.toLowerCase(ROOT);
        for (String[] row : VERBS) {
            if (lower.contains(row[0])) return row[1];
        }
        return "Looted";
    }
}
