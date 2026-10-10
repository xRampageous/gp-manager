package com.gpmanager;

import java.util.*;
import java.util.function.Predicate;

/** Pending evidence inbox. FE fee, CH chest, TK ground click, BG bag gain, KT kit snapshot. */
/** DI dismantle click, GE collect interaction, TA fired-ammo pickup intent. */
/** DO drop click, DROPPED an own drop still on the ground, FA recoverable fired-ammo loss. */
/** CN a consume command, including its named inventory baseline and presentation hints. */
/** LT private loot (at bounds attribution); LP/RD quantities reserved by an own-pile despawn. */
/**
 * CL load target, CK exact Check link, RV uncharge return; command quantities never prove money.
 */
/** CX/CP live/stabilizing claims, CT attribution clock, SF/HF soft/hard transfer, BO bank UI. */
class Inbox {
    static final int FEE = 1,
            CHEST = 2,
            GROUND_CLICK = 3,
            BAG_GAIN = 4,
            KIT_SNAPSHOT = 5,
            DISMANTLE_CLICK = 6,
            GE_COLLECT = 7,
            DROP_CLICK = 9,
            DROPPED = 10,
            CONSUME = 12,
            PRIVATE_LOOT = 13,
            LOOT_RESERVE = 14,
            LOAD_TARGET = 16,
            CHECK_LINK = 17,
            UNCHARGE = 18,
            LIVE_CLAIM = 19,
            PENDING_CLAIM = 20,
            CLOCK = 21,
            SOFT_TRANSFER = 22,
            HARD_TRANSFER = 23,
            BANK_UI = 24,
            SERVANT = 25,
            SAWMILL = 26,
            DIALOGUE = 27,
            PIPE_PICKUP = 28,
            CANNON = 29;
    volatile long owner;

    /** Legacy lifecycle ephemera clear here; their durable state keeps its tested lifecycle. */
    Runnable reset = () -> {};

    int now;

    /** Proved context stays latched while its measured snapshot stabilizes, as in 1.2. */
    boolean hold;

    List<Observation> open = new ArrayList<>();

    synchronized Observation add(int kind, int item, long qty, int ticks, String note) {
        var e = new Observation();
        e.kind = kind;
        e.owner = owner;
        e.item = item;
        e.qty = qty;
        e.until = (int) Math.min(Integer.MAX_VALUE, (long) now + ticks);
        e.note = note;
        open.add(e);
        return e;
    }

    /** Replace a single observation, such as the latest click or chat confirmation. */
    synchronized Observation put(int kind, int item, long qty, int ticks, String note) {
        drop(kind);
        return add(kind, item, qty, ticks, note);
    }

    int item(int kind) {
        Observation e = peek(kind);
        return e == null ? -1 : e.item;
    }

    long at(int kind) {
        Observation e = peek(kind);
        return e == null ? 0L : e.at;
    }

    boolean has(int kind, int item) {
        return peek(kind, e -> e.item == item) != null;
    }

    Engine.Clue clue(int kind) {
        Observation e = peek(kind);
        if (e == null) {
            e = add(kind, -1, 1L, Integer.MAX_VALUE, null);
            e.data = new Engine.Clue();
        }
        return (Engine.Clue) e.data;
    }

    int left(int kind) {
        Observation e = peek(kind);
        return e == null ? 0 : e.until - now + 1;
    }

    void ttl(int kind, int ticks) {
        if (ticks <= 0) drop(kind);
        else put(kind, -1, 1L, ticks - 1, null);
    }

    synchronized Observation peek(int kind, Predicate<Observation> match) {
        for (Observation e : open) if (e.kind == kind && live(e) && match.test(e)) return e;
        return null;
    }

    Observation peek(int kind) {
        return peek(kind, e -> true);
    }

    /** Consume no more than the still-open proof, even when a caller retains a stale record. */
    synchronized long take(int kind, int item, long qty, Predicate<Observation> match) {
        long left = Math.max(0L, qty);
        for (Observation e : open) {
            if (e.kind != kind || e.item != item || !live(e) || !match.test(e)) continue;
            long used = Math.min(left, e.qty);
            e.qty -= used;
            left -= used;
            if (left == 0L) break;
        }
        open.removeIf(e -> !live(e));
        return Math.max(0L, qty) - left;
    }

    boolean live(Observation e) {
        return e.owner == owner && e.qty > 0L && now <= e.until;
    }

    /**
     * Reserve proven private quantity for the bag, retaining its original source for settlement.
     */
    synchronized long reserve(int kind, int item, long qty, int into) {
        long left = Math.max(0L, qty);
        for (Observation e : all(kind)) {
            if (e.item != item) continue;
            int index = open.indexOf(e);
            long used = take(kind, item, left, v -> v == e);
            if (used > 0L) {
                Observation part = add(into, item, used, e.until - now, e.note);
                part.data = e;
                open.remove(part);
                open.add(Math.min(index, open.size()), part);
            }
            left -= used;
            if (left == 0L) break;
        }
        return Math.max(0L, qty) - left;
    }

    synchronized List<Observation> all(int kind) {
        var found = new ArrayList<Observation>();
        for (Observation e : open) if (e.kind == kind && live(e)) found.add(e);
        return found;
    }

    void refresh(int kind, int ticks) {
        Observation e = peek(kind);
        if (e != null) e.until = Math.max(e.until, now + Math.max(1, ticks) - 1);
    }

    synchronized void drop(int... kinds) {
        for (int kind : kinds) open.removeIf(e -> e.kind == kind);
    }

    synchronized void tick(int tick) {
        if (hold)
            for (Observation e : open)
                if (e.kind >= CLOCK && e.kind <= HARD_TRANSFER && e.until < Integer.MAX_VALUE)
                    e.until += tick - now;
        now = tick;
        open.removeIf(e -> !live(e));
    }

    /**
     * Sole account boundary; called with Persistence's existing request generation, including A ->
     * B -> A.
     */
    void owner(long token) {
        synchronized (this) {
            owner = token;
            open.clear();
        }
        // Never take the engine monitor while holding the inbox monitor.
        reset.run();
    }
}
