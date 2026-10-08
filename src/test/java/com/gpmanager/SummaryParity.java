package com.gpmanager;
import java.util.*;
import static org.junit.Assert.*;

/** Frozen old algorithms against the revision summary, also used by offline previews/replays. */
class SummaryParity {
 static void same(String a, Object b, Object c) {
  assertTrue(a + " output differs", Objects.equals(SummaryFields.of(b), SummaryFields.of(c)));
 }
 static void live(Am a, long b) {
  same("Live", Oc.capture(a, b, null), Nc.capture(a, b, null));
  same("Live production", Oc.capture(a, b, null), Ca.capture(a, b, null));
 }
 static void check(Am a, long b) {
  live(a, b);
  GpManagerConfig c = new GpManagerConfig() {};
  Cp d = new Cp(c, (id, qty) -> null), e = new Cp(c, (id, qty) -> null);
  for (long f : new long[]{b, b + 1L, b + 60_000L}) {
   for (Dz g : new Dz[]{Dz.NONE, new Dz(true, null, Long.MAX_VALUE, new Bo(true, true, true, 99L), "Fishing", true, "Guard", true)}) {
    Ca h = Oc.capture(a, f, g), i = Nc.capture(a, f, g);
    same("Live filtered", h, i);
    same("Live production filtered", h, Ca.capture(a, f, g));
    same("HUD", d.update(h, g::td, f), e.update(i, g::td, f));
   }
  }
  List<String> f = new ArrayList<>(); f.add(null);
  for (Ad g : a.adk()) if (g != null) f.add(g.getId());
  for (String g : f) {
   for (boolean h : new boolean[]{false, true}) {
    same("Grinds", Og.capture(a, b, h, g), Ng.capture(a, b, h, g));
    same("Grinds production", Og.capture(a, b, h, g), As.capture(a, b, h, g));
   }
   for (Ao.Scope h : Ao.Scope.values()) {
    for (Ao.Bs i : Ao.Bs.values()) {
     for (String j : new String[]{"", "rune", "potion", "no matching item"}) {
      Ao.Entry k = new Ao.Entry(h, g, null).withCostView(i).withSearch(j);
      same("Ledger", Ol.capture(a, b, k), Nl.capture(a, b, k));
      same("Ledger production", Ol.capture(a, b, k), Ao.capture(a, b, k));
     }
    }
   }
  }
  Sm h = a.summary();
  for (Ad i : a.adk()) {
   if (i == null) continue;
   same("Session metrics", i.metrics(b), h.metrics(i, b));
   if (i.isClosed()) same("Recap", Ob.of(i), h.fact(i).recap);
   Sm.S j = h.scope(Collections.singletonList(i));
   for (java.util.function.Predicate<Ab> k : Arrays.<java.util.function.Predicate<Ab>>asList(null, x -> false, x -> x.quantityDelta <= 0L, x -> x.itemId % 2 == 0)) {
    same("Semantic groups", Or.capture(i.getTransactions(), a.ub(), i.getId(), k), h.view(j, k));
   }
   for (Ac k : i.getTransactions()) {
    if (k == null) continue;
    Ao.Entry l = new Ao.Entry(i == a.getActiveSession() ? Ao.Scope.CURRENT_GRIND : Ao.Scope.HISTORY, i.getId(), i.getName())
    .withSelection(k.getId(), null, null);
    same("Ledger detail", Ol.capture(a, b, l), Nl.capture(a, b, l));
    same("Ledger production detail", Ol.capture(a, b, l), Ao.capture(a, b, l));
   }
  }
 }
}
