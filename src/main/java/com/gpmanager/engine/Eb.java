package com.gpmanager;
import java.util.*;
import java.util.function.Predicate;
/** Pending evidence inbox. FE fee, CH chest, TK ground click, BG bag gain, KT kit snapshot. */
/** DI dismantle click, GE collect interaction, TA fired-ammo pickup intent. */
/** DO drop click, OD recoverable dropped loss, FA recoverable fired-ammo loss. */
/** CN a consume command, including its named inventory baseline and presentation hints. */
/** LT private loot (at bounds attribution); LP/RD quantities reserved by an own-pile despawn. */
/** CL load target, CK exact Check link, RV uncharge return; command quantities never prove money. */
/** CX/CP live/stabilizing claims, CT attribution clock, SF/HF soft/hard transfer, BO bank UI. */
class Eb {
 static final int FE = 1, CH = 2, TK = 3, BG = 4, KT = 5, DI = 6, GE = 7, TA = 8, DO = 9, OD = 10, FA = 11, CN = 12, LT = 13, LP = 14, RD = 15, CL = 16, CK = 17, RV = 18, CX = 19, CP = 20, CT = 21, SF = 22, HF = 23, BO = 24, SV = 25, SW = 26, DG = 27;
 volatile long owner;
 /** Legacy lifecycle ephemera clear here; their durable state keeps its tested lifecycle. */
 Runnable reset = () -> {};
 int now;
 /** Proved context stays latched while its measured snapshot stabilizes, as in 1.2. */
 boolean hold;
 List<Ev> open = new ArrayList<>();
 synchronized Ev add(int /* kind */a, int /* item */b, long /* qty */c, int /* ticks */d, String /* note */f) {
  var e = new Ev();
  e.kind = a;
  e.owner = owner;
  e.item = b;
  e.qty = c;
  e.until = (int) Math.min(Integer.MAX_VALUE, (long) now + d);
  e.note = f;
  open.add(e);
  return e;
 }
 /** Replace a single observation, such as the latest click or chat confirmation. */
 synchronized Ev put(int /* kind */a, int /* item */b, long /* qty */c, int /* ticks */d, String /* note */e) {
  drop(a);
  return add(a, b, c, d, e);
 }
 synchronized Ev set(int /* kind */a, int /* item */b, long /* qty */c, int /* ticks */d, String /* note */f) {
  open.removeIf(e -> e.kind == a && e.item == b);
  return add(a, b, c, d, f);
 }
 int item(int /* kind */a) { Ev e = peek(a); return e == null ? -1 : e.item; }
 long at(int /* kind */a) { Ev e = peek(a); return e == null ? 0L : e.at; }
 boolean has(int /* kind */a, int /* item */b) { return peek(a, e -> e.item == b) != null; }
 Am.Clue clue(int /* kind */a) {
  Ev e = peek(a);
  if (e == null) {
   e = add(a, -1, 1L, Integer.MAX_VALUE, null);
   e.data = new Am.Clue();
  }
  return (Am.Clue) e.data;
 }
 int left(int /* kind */a) { Ev e = peek(a); return e == null ? 0 : e.until - now + 1; }
 void ttl(int /* kind */a, int /* ticks */b) {
  if (b <= 0) drop(a);
  else put(a, -1, 1L, b - 1, null);
 }
 synchronized Ev peek(int /* kind */a, Predicate<Ev> /* match */b) {
  for (Ev e : open) if (e.kind == a && live(e) && b.test(e)) return e;
  return null;
 }
 Ev peek(int /* kind */a) { return peek(a, e -> true); }
 /** Consume no more than the still-open proof, even when a caller retains a stale record. */
 synchronized long take(int /* kind */a, int /* item */b, long /* qty */c, Predicate<Ev> /* match */d) {
  long /* left */f = Math.max(0L, c);
  for (Ev e : open) {
   if (e.kind != a || e.item != b || !live(e) || !d.test(e)) continue;
   long /* used */g = Math.min(f, e.qty);
   e.qty -= g;
   f -= g;
   if (f == 0L) break;
  }
  open.removeIf(e -> !live(e));
  return Math.max(0L, c) - f;
 }
 boolean live(Ev e) { return e.owner == owner && e.qty > 0L && now <= e.until; }
 /** Reserve proven private quantity for the bag, retaining its original source for settlement. */
 synchronized long reserve(int /* kind */a, int /* item */b, long /* qty */c, int /* into */d) {
  long /* left */f = Math.max(0L, c);
  for (Ev e : all(a)) {
   if (e.item != b) continue;
   int /* index */g = open.indexOf(e);
   long /* used */h = take(a, b, f, v -> v == e);
   if (h > 0L) {
    Ev /* part */i = add(d, b, h, e.until - now, e.note);
    i.data = e;
    open.remove(i);
    open.add(Math.min(g, open.size()), i);
   }
   f -= h;
   if (f == 0L) break;
  }
  return Math.max(0L, c) - f;
 }
 synchronized List<Ev> all(int /* kind */a) {
  var /* found */b = new ArrayList<Ev>();
  for (Ev e : open) if (e.kind == a && live(e)) b.add(e);
  return b;
 }
 void cap(int /* kind */a, int /* count */b) {
  var /* all */c = all(a);
  for (int i = 0; i < c.size() - b; i++) open.remove(c.get(i));
 }
 void refresh(int /* kind */a, int /* ticks */b) {
  Ev e = peek(a);
  if (e != null) e.until = Math.max(e.until, now + Math.max(1, b) - 1);
 }
 synchronized void drop(int... /* kinds */a) {
  for (int /* kind */b : a) open.removeIf(e -> e.kind == b);
 }
 synchronized void tick(int /* tick */a) {
  if (hold) for (Ev e : open) if (e.kind >= CT && e.kind <= HF && e.until < Integer.MAX_VALUE) e.until += a - now;
  now = a;
  open.removeIf(e -> !live(e));
 }
 /** Sole account boundary; called with Ei's existing request generation, including A -> B -> A. */
 void owner(long /* token */a) {
  synchronized (this) {
   owner = a;
   open.clear();
  }
  // Never take the engine monitor while holding the inbox monitor.
  reset.run();
 }
}
