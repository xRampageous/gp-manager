package com.gpmanager;
import com.gpmanager.LedgerPage.Ea;
import java.util.*;
import javax.swing.*;
import static com.gpmanager.Ag.*;
import static com.gpmanager.Ao.Scope.*;
import static java.lang.Math.*;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Ak.msg;
/** Ledger actions: scope menu, search, selection, corrections, splits and review decisions. */
class LedgerController implements LedgerPage.Actions {
Dp panel;
Am engine;
LedgerController(Dp /* panel */a) {
 this.panel = a;
 this.engine = a.engine;
}

void mutate(Runnable /* domain */a) {
 panel.mutate(a);
}

public void openScopeMenu(JComponent /* anchor */a) {
 panel.historyScopeMenuPage = 0;
 ail(a, panel.historyScopeMenuPage);
}

void ail(JComponent /* anchor */a, int /* pageIndex */b) {
 var /* menu */c = new JPopupMenu();
 c.add(item("Current Grind", () -> aia(CURRENT_GRIND, null, null)));
 c.add(item("Runs today", () -> aia(TODAY, null, null)));
 var /* history */d = new ArrayList<Ad>(engine.getHistory());
 d.removeIf(Objects::isNull);
 d.sort(Comparator.comparingLong((Ad /* itemData */e) -> e.startedAtEpochMillis).reversed()
 .thenComparing(Ad::getId));
 if (!d.isEmpty()) {
  c.addSeparator();
  int /* size */f = 10;
  int /* pages */g = max(1, (d.size() + f - 1) / f);
  panel.historyScopeMenuPage = max(0, min(b, g - 1));
  int /* from */h = panel.historyScopeMenuPage * f;
  int /* to */i = min(d.size(), h + f);
  var /* status */j = new JMenuItem("History " + (h + 1) + "–" + i + " of " + d.size());
  j.setEnabled(false);
  c.add(j);
  if (g > 1) {
   c.add(item("Previous page", panel.historyScopeMenuPage > 0, () -> SwingUtilities.invokeLater(
   () -> ail(a, panel.historyScopeMenuPage - 1))));
   c.add(item("Next page", panel.historyScopeMenuPage + 1 < g, () -> SwingUtilities.invokeLater(
   () -> ail(a, panel.historyScopeMenuPage + 1))));
   c.addSeparator();
  }
  for (Ad /* session */k : d.subList(h, i)) {
   c.add(item(msg("kl") + k.getName(), () -> aia(HISTORY,
   k.getId(), k.getName())));
  }
 }
 c.show(a, 0, a.getHeight() + 2);
}

void aia(Ao.Scope /* scope */a, String /* historyId */b, String /* historyName */c) {
 panel.ledgerEntry = panel.ledgerEntry.withScope(a, b, c);
 refresh();
}

public void costViewChanged(Ao.Bs /* view */a) {
 panel.ledgerEntry = panel.ledgerEntry.withCostView(a);
 refresh();
}

public void searchChanged(String /* text */a) {
 panel.ledgerSearch = axw(a);
 panel.ledgerEntry = panel.ledgerEntry.withSearch(panel.ledgerSearch);
 refresh();
}

@Override
public void selectionChanged(String /* transactionId */a, String /* contributionId */b, String /* groupId */c) {
 String /* transaction */d = empty(a) ? null : a;
 String /* contribution */e = empty(b) ? null : b;
 String /* group */f = empty(c) ? null : c;
 panel.ledgerEntry = panel.ledgerEntry.withSelection(d, e, f);
 refresh();
}

public Ao.Ef preview(String /* transactionId */a, Ah /* correction */b) {
 return Ao.preview(engine, a, b, Ae.now());
}

public Ea correct(String /* transactionId */a, Ah /* correction */b, long /* previewRevision */c) {
 if (engine.getRevision() != c) return Ea.STALE;
 boolean /* applied */d = engine.qi(a, b, Ae.now(), "Ledger correction");
 return d ? Ea.APPLIED : Ea.REFUSED;
}

public void split(String /* transactionId */a, java.util.function.BiConsumer<long[], Ao.Ef> /* previewed */b) {
 Ad /* session */c = engine.getActiveSession();
 Ac /* transaction */d = c == null ? null : c.sw(a);
 int /* itemId */e = -1;
 long /* total */f = 0L;
 String /* itemName */g = "";
 if (d != null) {
  for (Ab /* flow */h : d.getFlows()) {
   if (h == null || h.quantityDelta <= 0L) continue;
   if (e == -1) {
    e = h.itemId;
    g = h.itemName;
   } else if (e != h.itemId) {
    e = -1;
    break;
   }
   f += h.quantityDelta;
  }
 }
 if (e <= 0 || f < 2L) {
  // Owner 2026-10-01 (F02): only one kind of item in two or more units can be split.
  panel.shell().tell("Not split", d == null ? msg("f1")
  : msg("km"), true);
  return;
 }
 int /* splitItem */i = e;
 long /* splitTotal */j = f;
 String /* splitName */k = g == null || g.isEmpty() ? "this item" : g;
 String /* owner */l = c.getId();
 panel.shell().prompt("Split entry",
 "Keep how many of " + k + " \u00d7" + f + " for your share?", "", /* value */m -> {
  long /* keep */n;
  try {
   n = Long.parseLong(m.replace(",", ""));
  } catch (NumberFormatException /* ex */o) {
   n = -1L;
  }
  if (n <= 0L || n >= j) {
   panel.shell().tell("Not split", msg("kn") + (j - 1), true);
   return;
  }
  Ad /* now */p = engine.getActiveSession();
  if (p == null || !l.equals(p.getId()) || !splittableSplit(p.sw(a), i, j)) {
   panel.shell().tell("Not split", msg("ko"), true);
   return;
  }
  // Owner 1.1: the split shows its Net preview first, like every other correction.
  b.accept(new long[] {i, n, j},
  Ao.splitPreview(engine, a, i, n, Ae.now()));
 });
}

public void applySplit(String /* transactionId */a, long[] /* split */b, long /* previewRevision */c) {
 String /* owner */d = engine.getActiveSession() == null ? "" : engine.getActiveSession().getId();
 mutate(() -> {
  // The sheet may be stale: re-check the session and the exact receipt before mutating.
  Ad /* now */e = engine.getActiveSession();
  Ac /* current */f = e == null || !d.equals(e.getId()) ? null : e.sw(a);
  if (engine.getRevision() != c) {
   panel.shell().tell("Not split", msg("gz"), true);
   return;
  }
  if (!splittableSplit(f, (int) b[0], b[2])) {
   panel.shell().tell("Not split", msg("ko"), true);
   return;
  }
  if (!engine.kr(a, (int) b[0], b[1], Ae.now(), "Ledger split")) {
   panel.shell().tell("Not split", msg("f0"), true);
  }
 });
}

/** One item kind only, and the quantity captured at the prompt is still exact. */
static boolean splittableSplit(Ac /* transaction */a, int /* itemId */b, long /* total */c) {
 if (a == null) return false;
 int /* found */d = -1;
 long /* sum */e = 0L;
 for (Ab /* flow */f : a.getFlows()) {
  if (f == null || f.quantityDelta <= 0L) continue;
  if (d == -1) d = f.itemId;
  else if (d != f.itemId) return false;
  e += f.quantityDelta;
 }
 return d == b && e == c;
}

String menuTarget = "";
/** Pins an open mutation menu; the callback re-checks it against the live session. */
@Override
public String pinMenuTarget() {
 Ad /* active */a = engine.getActiveSession();
 menuTarget = a == null ? "" : a.getId();
 return menuTarget;
}

/** Owner 2026-10-01 (F01): a stale menu never mutates; scope and session are re-checked here. */
boolean undoAllowed() {
 Ad /* active */a = engine.getActiveSession();
 return !Ao.readOnly(panel.ledgerEntry)
 && a != null && !menuTarget.isEmpty() && menuTarget.equals(a.getId());
}

public void undoCorrection() {
 if (!undoAllowed()) return;
 mutate(() -> engine.akb(Ae.now()));
}

@Override
public void undoLast() {
 if (!undoAllowed()) return;
 mutate(() -> engine.akc(Ae.now()));
}

@Override
public void restoreUndo() {
 if (!undoAllowed()) return;
 mutate(() -> engine.agn(Ae.now()));
}

public void decideAll(Cl /* decision */a) {
 decide(a, /* row */b -> b != null && Ao.aaf(b, panel.ledgerSearch), false);
}

@Override
public void decide(String /* transactionId */a, Cl /* decision */b) {
 decide(b, /* row */c -> c != null && a.equals(c.transactionId), true);
}

/** Previews one decision over the chosen Review rows, confirms, then applies it as one undoable batch. */
void decide(Cl /* decision */a, java.util.function.Predicate<Cu> /* rows */b, boolean /* single */c) {
 Am.DecideAllPreview /* preview */d = engine.adu(a, b, Ae.now());
 int /* count */e = d.rowCount();
 if (e == 0 || c && e != 1) return;
 String /* message */f = qj(a.ajo())
 + (c ? " for this entry" : " for " + e + (e == 1 ? " entry" : " entries"))
 + ". Net changes by " + Fmt.signed(d.netDelta) + " gp." + (c ? "" : msg("kp"));
 panel.shell().confirm(c ? "Review decision" : "Decide all", f, "Apply", () -> mutate(() -> {
  if (engine.kp(d, Ae.now()) < 0) {
   SwingUtilities.invokeLater(() -> panel.ledger.aiu(msg("gz")));
  }
 }));
}

public void refresh() {
 panel.refresh();
}

static String qj(Ah /* correction */a) {
 return LedgerPage.qj(a);
}
}
