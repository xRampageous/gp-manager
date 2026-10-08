package com.gpmanager;
import com.gpmanager.SavedState.Ap;
import com.gpmanager.Ao.Entry;
import javax.swing.*;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.Ak.msg;
/** Grinds actions: start, save, edit, targets, history, export, backups and recovery. */
class GrindsController implements GrindsPage.Actions {
Dp panel;
Am engine;
Shell shell;
SessionRepository repository;
Ei persistence;
GrindsController(Dp /* panel */a) {
 this.panel = a;
 this.engine = a.engine;
 this.shell = a.shell();
 this.repository = a.repository;
 this.persistence = a.persistence;
}

void mutate(Runnable /* domain */a) {
 panel.mutate(a);
}

void acg(Entry /* entry */a) {
 panel.acg(a);
}

public void back() {
 panel.grindsDetailId = null;
 shell.show(Shell.LIVE);
 refresh();
}

public void openLedger(String /* sessionId */a) {
 Ad /* active */b = engine.getActiveSession();
 if (b != null && b.getId().equals(a)) {
  acg(Entry.current());
 } else {
  Ad /* historical */c = a == null ? null : engine.ua(a);
  acg(new Entry(Ao.Scope.HISTORY, a, c == null ? null : c.getName()));
 }
}

public void endGrind() {
 Ad /* ending */b = engine.getActiveSession();
 if (b == null || !engine.wb()) return;
 String /* endedId */c = b.getId();
 String /* endedName */d = b.getName();
 // Ending is a deliberate menu pick and keeps history, so it needs no second confirmation.
 mutate(() -> {
  if (!engine.sx(Ae.now())) return;
  Bu /* ended */e = engine.tz(c, Ae.now());
  // Owner 2026-10-01 (F14): the ended run stays one click from its recap.
  shell.notify(d + " ended", e == null ? "View recap"
  : signed(e.net) + msg("kh"), false, "View recap", () -> {
   panel.grindsDetailId = c;
   shell.show(Shell.GRINDS);
   refresh();
  });
 });
}

public void startGrind(String /* grindId */a, Long /* netOverride */b, Long /* timeOverride */c, String /* nameOverride */d) {
 Ap /* definition */e = a == null ? null : engine.um(a);
 String /* name */f = d != null && !d.trim().isEmpty()
 ? d.trim() : e == null ? "" : e.getName();
 Long /* net */g = b != null ? b : e == null ? null : e.getNetTargetGp();
 Long /* time */h = c != null ? c : e == null ? null : e.getActiveTimeTargetMillis();
 if (e != null) a = e.getGrindId();
 if (a != null && engine.wb() && engine.getActiveSession() != null
 && a.equals(engine.getActiveSession().getGrindId())) {
  shell.show(Shell.LIVE);
  refresh();
  return;
 }
 String /* atr */i = f;
 String /* startGrindId */j = a;
 ako(f, () -> mutate(() ->
 engine.startGrind(i, j, g, h, Ae.now())));
}

public void openTargets() {
 Ad /* active */a = engine.getActiveSession();
 if (a == null) return;
 ajp("Targets", msg("ff"), aum(null, a.getProfitTargetGp(),
 a.getActiveTimeTargetMillis()), "Save", this::openTargets, (/* name */b, /* net */c, /* time */d) -> {
  Long /* netTarget */e = As.ks(c, a.getProfitTargetGp());
  Long /* timeTarget */f = As.lb(d, a.getActiveTimeTargetMillis());
  mutate(() -> engine.ahq(e, f, Ae.now()));
 });
}

/** Owner 2026-10-01 (F20): the run an action was opened on; never whatever is active now. */
Ad acq(String /* sessionId */a) {
 if (a == null || a.isEmpty()) return engine.getActiveSession();
 Ad /* active */b = engine.getActiveSession();
 return b != null && a.equals(b.getId()) ? b : engine.ua(a);
}

public void saveThisGrind(String /* sessionId */a) {
 Ad /* target */b = acq(a);
 if (b == null || (b == engine.getActiveSession() && !engine.wb())) return;
 Long /* net */c = b.getProfitTargetGp();
 Long /* time */d = b.getActiveTimeTargetMillis();
 String /* identity */e = b.getId();
 shell.prompt("Save This Grind", "Grind name", b.getName(), /* cleanName */f -> mutate(() -> {
  // Re-validate after the prompt: the viewed run must still resolve to the same identity.
  Ad /* now */g = acq(a);
  if (g == null || !e.equals(g.getId())) return;
  Ap /* created */h = engine.avg(f, c, d, false, e);
  if (h != null) {
   shell.tell("Saved as My Grind", f + msg("ki"), false);
  }
 }));
}

/**
* The one Start Grind sheet, from Live and Grinds: a saved Grind starts in one click (favourites
* first, with its targets); a new name starts once, unsaved (Add Grind saves one for later).
* Owner 2026-10-01 (F22): the saved list is bounded to five rows with paging.
*/
public void newGrind() {
 JPanel /* content */b = stack(CARD, 2);
 java.util.ArrayList<Ap> /* saved */c = new java.util.ArrayList<>(engine.getSavedGrinds(false));
 c.sort(java.util.Comparator.comparing(/* grind */d -> !d.favorite));
 new Paged(c.size(), (/* box */e, /* page */f) -> {
  int /* from */g = Math.min(f * CHOICE_ROWS, c.size());
  for (Ap /* grind */h : c.subList(g, Math.min(g + CHOICE_ROWS, c.size()))) {
   JButton /* start */i = button((h.favorite ? "\u2605 " : "") + h.getName(), false, () -> {
    shell.pc();
    startGrind(h.getGrindId(), null, null, null);
   });
   i.setHorizontalAlignment(SwingConstants.LEFT);
   i.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 22));
   e.add(i);
  }
 }).build(b);
 avx(b, "nameField", c.isEmpty() ? "Grind name" : "Or a new Grind", "");
 shell.sheet("Start Grind", c.isEmpty() ? "" : msg("jo"), b, new Shell.Choice("Start", true, () -> {
  String /* name */j = field(b, "nameField");
  if (!j.isEmpty()) startGrind(null, null, null, j);
 }));
}

/** Choice rows a bounded sheet list shows at once (owner 2026-10-01, F22). */
static final int CHOICE_ROWS = 5;
/**
* One bounded choice list: at most five rows, with previous/next paging when there are more.
* Paging refills the list box in place, so the sheet - and any typed name - is never rebuilt.
*/
static final class Paged {
 interface RowFill {
  void fill(JPanel /* box */b, int /* page */c);
 }
 JPanel box = stack(CARD, 2);
 int total;
 RowFill fill;
 int[] page = {0};
 JButton prev = button("\u2039", false, () -> turn(-1));
 JButton next = button("\u203a", false, () -> turn(1));
 JLabel range = label("", small(), LABEL);
 JPanel bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
 Paged(int /* total */b, RowFill /* fill */c) {
  this.total = b;
  this.fill = c;
  bar.setOpaque(false);
  bar.add(prev);
  bar.add(next);
  bar.add(range);
 }
 /** Adds the list (and its pager) to the sheet body and renders the first page. */
 JPanel build(JPanel /* content */b) {
  b.add(box);
  if (total > CHOICE_ROWS) b.add(bar);
  redraw();
  return box;
 }
 void turn(int /* delta */b) {
  page[0] = Kit.p2(page[0] + b, pages());
  redraw();
 }
 int pages() {
  return Kit.p1(total, CHOICE_ROWS);
 }
 void redraw() {
  box.removeAll();
  fill.fill(box, page[0]);
  int /* from */b = page[0] * CHOICE_ROWS;
  Kit.p3(range, prev, next, page[0], pages(), total == 0 ? "" : (b + 1) + "\u2013" + Math.min(total, b + CHOICE_ROWS) + " of " + total);
  box.revalidate();
 }
}

public void addGrind() {
 shell.prompt("Add Grind", "Grind name", "", /* name */a -> mutate(() -> engine.avg(a, null, null, false, null)));
}

public void updateSavedGrind(String /* grindId */a, String /* sessionId */b) {
 Ap /* definition */c = engine.um(a);
 Ad /* target */d = acq(b);
 if (c == null || d == null) return;
 Long /* net */e = d.getProfitTargetGp();
 Long /* time */f = d.getActiveTimeTargetMillis();
 String /* identity */g = d.getId();
 shell.confirm("Update " + c.getName(), msg("go"), "Update", () -> mutate(() -> {
  // Re-validate after the confirmation: definition and viewed run must still be the same.
  Ap /* current */h = engine.um(a);
  Ad /* now */i = acq(b);
  if (h == null || i == null || !g.equals(i.getId())) return;
  engine.updateSavedGrind(a, h.getName(), e, f, h.favorite);
 }));
}

public void editSavedGrind(String /* grindId */a) {
 Ap /* definition */b = engine.um(a);
 if (b == null) return;
 JPanel /* form */c = aum(b.getName(), b.getNetTargetGp(),
 b.getActiveTimeTargetMillis(), b.favorite);
 ajp("Edit " + b.getName(), msg("gn"),
 c, "Save", () -> editSavedGrind(a), (/* name */d, /* net */e, /* time */f) -> {
  String /* cleanName */g = d.isEmpty() ? b.getName() : d;
  Long /* netTarget */h = As.ks(e, b.getNetTargetGp());
  Long /* timeTarget */i = As.lb(f, b.getActiveTimeTargetMillis());
  // Owner 2026-10-01 (F21): the editor shows and saves the existing Favorite flag.
  boolean /* favorite */j = flag(c, "favoriteField");
  mutate(() -> engine.updateSavedGrind(a, g, h, i, j));
 });
}

public void startWithChanges(String /* grindId */a) {
 Ap /* definition */b = engine.um(a);
 if (b == null) return;
 ajp("Start with changes", msg("jp"),
 nb(b), "Start", () -> startWithChanges(a), (/* typed */c, /* net */d, /* time */e) -> {
  String /* name */f = c.isEmpty() ? b.getName() : c;
  Long /* netTarget */g = As.ks(d, null);
  Long /* timeTarget */h = As.lb(e, null);
  String /* startGrindId */i = b.getGrindId();
  // One-off changes apply to this instance only; saved defaults stay untouched.
  ako(f, () -> mutate(() ->
  engine.startGrind(f, i, g, h, Ae.now())));
 });
}

public void archiveSavedGrind(String /* grindId */a, boolean /* archived */b) {
 if (!b) {
  mutate(() -> engine.aht(a, false));
  return;
 }
 shell.confirm("Archive Grind", msg("gl"), "Archive", () -> mutate(() -> engine.aht(a, true)));
}

public void deleteSavedGrind(String /* grindId */a) {
 Ap /* definition */b = engine.um(a);
 if (b == null) return;
 shell.confirm("Delete " + b.getName(), msg("gm"),
 "Delete", () -> mutate(() -> engine.deleteSavedGrind(a)));
}

public void startAgain(String /* sessionId */a) {
 Ad /* session */b = a == null ? null : engine.ua(a);
 if (b == null) {
  Ad /* active */c = engine.getActiveSession();
  b = c != null && c.getId().equals(a) ? c : null;
 }
 if (b == null) return;
 String /* name */d = b.getName();
 String /* linkedGrindId */e = b.getGrindId();
 Long /* net */f = b.getProfitTargetGp();
 Long /* time */g = b.getActiveTimeTargetMillis();
 ako(d, () -> mutate(() ->
 engine.startGrind(d, e, f, g, Ae.now())));
}

public void openDetail(String /* sessionId */a) {
 panel.grindsDetailId = a;
 refresh();
}

public void deleteHistorical(String /* sessionId */a) {
 Ad /* session */b = engine.ua(a);
 if (b == null) return;
 shell.confirm("Delete " + b.getName(), msg("hd"), "Delete for good", () -> {
  panel.grindsDetailId = null;
  mutate(() -> engine.qu(a));
 });
}

/** The Grinds page's own menu: only what changes that page. */
public void openDataMenu(JComponent /* anchor */a) {
 var /* menu */b = new JPopupMenu();
 JCheckBoxMenuItem /* archived */c = new JCheckBoxMenuItem("Show archived", panel.grindsShowArchived);
 c.addActionListener(e -> {
  panel.grindsShowArchived = c.isSelected();
  refresh();
 });
 b.add(c);
 b.show(a, 0, a.getHeight() + 2);
}

/** The gear menu beside the tabs: profile-wide data actions, from any page. */
JPopupMenu mv() {
 var /* menu */a = new JPopupMenu();
 a.add(item("Back up profile", persistence != null, () -> lq()));
 a.add(item("Restore backup\u2026", persistence != null, () -> agm()));
 a.add(item("Clear old backups", persistence != null, this::qw));
 a.add(item(msg("fg"), () -> qr()));
 a.addSeparator();
 a.add(item(msg("kj"), persistence != null, () -> ti()));
 return a;
}

public void refresh() {
 panel.refresh();
}

/** The Start-with-changes form: name and the two targets; the saved definition is never edited. */
JPanel nb(Ap /* definition */a) {
 return aum(a.getName(), a.getNetTargetGp(), a.getActiveTimeTargetMillis());
}

/** Name (when given), the two target fields and, when editing a saved Grind, its Favorite flag. */
static JPanel aum(String /* name */a, Long /* net */b, Long /* time */c) {
 return aum(a, b, c, null);
}

static JPanel aum(String /* name */a, Long /* net */b, Long /* time */c, Boolean /* favorite */d) {
 JPanel /* form */e = stack(CARD, 2);
 if (a != null) avx(e, "nameField", "Name", a);
 avx(e, "netField", msg("gv"), b == null ? "" : String.valueOf(b));
 avx(e, "timeField", msg("hg"), c == null ? "" : akk(c));
 if (d != null) avx(e, "favoriteField", "\u2605 Favorite", d.booleanValue());
 return e;
}

static void avx(JPanel /* form */a, String /* key */b, String /* label */c, String /* value */d) {
 JTextField /* field */e = new JTextField(d);
 a.add(label(c, small(), LABEL));
 a.add(e);
 a.putClientProperty(b, e);
}

static void avx(JPanel /* form */a, String /* key */b, String /* label */c, boolean /* selected */d) {
 JCheckBox /* box */e = new JCheckBox(c, d);
 e.setOpaque(false);
 e.setFont(small());
 a.add(e);
 a.putClientProperty(b, e);
}

static boolean flag(JPanel /* form */a, String /* key */b) {
 Object /* box */c = a.getClientProperty(b);
 return c instanceof JCheckBox && ((JCheckBox) c).isSelected();
}

static String field(JPanel /* form */a, String /* key */b) {
 Object /* field */c = a.getClientProperty(b);
 return c instanceof JTextField ? ((JTextField) c).getText().trim() : "";
}

interface TargetSave {
 void save(String /* name */b, String /* net */c, String /* time */d);
}

/** A name-and-targets sheet; malformed targets never become a silent clear: say so and reopen it. */
void ajp(String /* title */a, String /* detail */b, JPanel /* form */c, String /* action */d, Runnable /* reopen */e, TargetSave /* save */f) {
 shell.sheet(a, b, c, new Shell.Choice(d, true, () -> {
  String /* net */g = field(c, "netField");
  String /* time */h = field(c, "timeField");
  if (As.vv(g, h)) {
   f.save(field(c, "nameField"), g, h);
  } else {
   shell.tell("Targets not saved", msg("hf"), true);
   e.run();
  }
 }));
}

/** One Grind at a time: starting another asks to end the running one first (spec §4). */
void ako(String /* nextName */b, Runnable /* start */c) {
 if (engine.getActiveSession() == null || !engine.wb()) {
  c.run();
  return;
 }
 Ca /* now */d = panel.lastSnapshot;
 String /* running */e = engine.getActiveSession().getName()
 + (d == null ? "" : " (" + signed(d.net) + ", " + clock(d.elapsedMillis) + ")");
 // The engine never replaces a running Grind silently: Switch ends it first, then starts.
 shell.confirm("Switch Grind", "End " + e + " and start " + b + "?", "Switch", () -> {
  mutate(() -> engine.sx(Ae.now()));
  c.run();
 });
}

/** Puts a path on the system clipboard. */
static void copyPath(String /* path */a) {
 java.awt.datatransfer.StringSelection /* selection */b = new java.awt.datatransfer.StringSelection(a);
 java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(b, b);
}

/**
* Disk work runs on the persistence worker, never Swing's thread, so RuneLite stays responsive;
* its result comes back on Swing's thread (Codex review 1.2).
*/
<T> void off(java.util.concurrent.Callable<T> /* work */b, java.util.function.Consumer<T> /* done */c, String /* failed */d) {
 java.util.concurrent.ExecutorService /* worker */e = persistence.lifecycleExecutor();
 if (e == null) return;
 // Bound to the account it was confirmed on: holding the persistence lock, no account switch can
 // land mid-job, and a switch before it starts refuses it (Codex follow-up 1.2).
 TrackingIdentity /* owner */f = persistence.activeIdentity;
 e.execute(() -> {
  try {
   T /* result */g;
   synchronized (persistence) {
    if (!java.util.Objects.equals(f, persistence.activeIdentity)) {
     SwingUtilities.invokeLater(() -> shell.tell("Account changed", msg("e9"), true));
     return;
    }
    g = b.call();
   }
   SwingUtilities.invokeLater(() -> c.accept(g));
  } catch (Exception /* ex */h) {
   SwingUtilities.invokeLater(() -> shell.failed(d, h));
  }
 });
}

void lq() {
 off(() -> persistence.akt(Ae.now()),
 /* backup */a -> shell.tell("Profile backed up", String.valueOf(a.getFileName()), false), "Backup failed");
}

/** This account's backups, newest first; restoring backs up the current state first. */
void agm() {
 JPanel /* content */a = stack(CARD, 2);
 java.util.List<net.runelite.client.util.Filepath> /* files */b;
 java.util.List<Long> /* modified */c = new java.util.ArrayList<>();
 try {
  b = persistence.backups();
  for (net.runelite.client.util.Filepath /* file */d : b) c.add(d.getLastModifiedTime().toMillis());
 } catch (java.io.IOException /* ex */e) {
  shell.failed(msg("bz"), e);
  return;
 }
 long /* now */f = Ae.now();
 new Paged(b.size(), (/* box */g, /* page */h) -> {
  int /* from */j = Math.min(h * CHOICE_ROWS, b.size());
  for (int i = j; i < Math.min(j + CHOICE_ROWS, b.size()); i++) {
   net.runelite.client.util.Filepath /* file */k = b.get(i);
   long /* at */l = c.get(i);
   JButton /* row */m = button(whenSeconds(l, f), false, () -> shell.confirm("Restore backup",
   msg("e8") + whenSeconds(l, f)
   + msg("kk"), "Restore", () -> off(() -> persistence.agm(k, Ae.now()), /* outcome */n -> {
    if (n.isApplied()) panel.afx();
    shell.tell(n.isApplied() ? "Backup restored" : "Restore refused",
    n.isApplied() ? "" : n.getDetail(), !n.isApplied());
   }, "Restore failed")));
   // Owner 2026-10-01 (F22): when() is minute-precision; the filename disambiguates.
   m.setToolTipText(String.valueOf(k.getFileName()));
   g.add(m);
  }
 }).build(a);
 shell.sheet("Restore backup", b.isEmpty() ? "No backups yet." : "Newest first.", a);
}

void qr() {
 if (repository == null) return;
 String /* path */a = String.valueOf(repository.ty());
 copyPath(a);
 shell.tell(msg("fh"), a, false);
}

/** Owner 2026-10-01 (F26): the deletion is immediate and irreversible, so state it first. */
void qw() {
 try {
  java.util.List<net.runelite.client.util.Filepath> /* backups */a = persistence.backups();
  if (a.size() <= 1) {
   shell.tell("Nothing to clear", "0 older backups", false);
   return;
  }
  long /* newest */b = a.get(0).getLastModifiedTime().toMillis();
  shell.confirm("Clear old backups", "Remove " + (a.size() - 1) + msg("e7")
  + when(b, Ae.now()) + ") is kept?", "Remove", () -> off(() -> persistence.adz(1),
  /* removed */c -> shell.tell(msg("e6"), c + msg("e5"), false), "Clear failed"));
 } catch (java.io.IOException /* ex */d) {
  shell.failed(msg("bz"), d);
 }
}

void ti() {
 // Owner 2026-10-01 (F26): back up first, then reset; a failed backup refuses the reset.
 // One job, so the backup and the reset are the same account's (Codex follow-up 1.2).
 shell.sheet("Factory reset", msg("hc"), null, new Shell.Choice("Back up first", true,
 () -> off(() -> {
  persistence.akt(Ae.now());
  return persistence.sj(Ae.now());
 }, this::reset, "Backup failed")), new Shell.Choice("Reset", false, this::axr));
}

/** The existing direct reset: one confirmation already happened in the sheet. */
void axr() {
 off(() -> persistence.sj(Ae.now()), this::reset, "Reset failed");
}

void reset(Ei.Ds /* outcome */a) {
 // The profile was replaced: HUD+ must not keep the old trip's loot or moments.
 if (a.isApplied()) panel.afx();
 String /* detail */b = a.isApplied() ? (a.getPreResetBackup() == null ? msg("fi")
 : "backup " + String.valueOf(a.getPreResetBackup().getFileName())) : a.getDetail();
 shell.tell(a.isApplied() ? "Profile reset" : "Reset refused", b, !a.isApplied());
}
}
