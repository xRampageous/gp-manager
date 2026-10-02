package com.gpmanager;
import com.gpmanager.SavedState.Ap;
import com.gpmanager.Ao.Entry;
import javax.swing.*;
import static com.gpmanager.Kit.*;
import static com.gpmanager.Fmt.*;
import static com.gpmanager.Ak.msg;
/** Grinds actions: start, save, edit, targets, history, export, backups and recovery. */
class GrindsController implements GrindsPage.Actions {
final Dp panel;
final Am engine;
final Shell shell;
final SessionRepository repository;
final Ei persistence;
GrindsController(Dp panel) {
 this.panel = panel;
 this.engine = panel.engine;
 this.shell = panel.shell();
 this.repository = panel.repository;
 this.persistence = panel.persistence;
}

void mutate(Runnable domain) {
 panel.mutate(domain);
}

void acg(Entry entry) {
 panel.acg(entry);
}

public void back() {
 panel.grindsDetailId = null;
 shell.show(Shell.LIVE);
 refresh();
}

public void openLedger(String sessionId) {
 Ad active = engine.getActiveSession();
 if (active != null && active.getId().equals(sessionId)) {
  acg(Entry.current());
 } else {
  Ad historical = sessionId == null ? null : engine.ua(sessionId);
  acg(new Entry(Ao.Scope.HISTORY, sessionId, historical == null ? null : historical.getName()));
 }
}

public void endGrind() {
 Ad ending = engine.getActiveSession();
 if (ending == null || !engine.wb()) return;
 String endedId = ending.getId();
 String endedName = ending.getName();
 // Ending is a deliberate menu pick and keeps history, so it needs no second confirmation.
 mutate(() -> {
  if (!engine.sx(System.currentTimeMillis())) return;
  Bu ended = engine.tz(endedId, System.currentTimeMillis());
  // Owner 2026-10-01 (F14): the ended run stays one click from its recap.
  shell.notify(endedName + " ended", ended == null ? "View recap"
  : signed(ended.net) + msg("kh"), false, "View recap", () -> {
   panel.grindsDetailId = endedId;
   shell.show(Shell.GRINDS);
   refresh();
  });
 });
}

public void startGrind(String grindId, Long netOverride, Long timeOverride, String nameOverride) {
 Ap definition = grindId == null ? null : engine.um(grindId);
 String name = nameOverride != null && !nameOverride.trim().isEmpty()
 ? nameOverride.trim() : definition == null ? "" : definition.getName();
 Long net = netOverride != null ? netOverride : definition == null ? null : definition.getNetTargetGp();
 Long time = timeOverride != null ? timeOverride : definition == null ? null : definition.getActiveTimeTargetMillis();
 if (definition != null) grindId = definition.getGrindId();
 if (grindId != null && engine.wb() && engine.getActiveSession() != null
 && grindId.equals(engine.getActiveSession().getGrindId())) {
  shell.show(Shell.LIVE);
  refresh();
  return;
 }
 String atr = name;
 String startGrindId = grindId;
 ako(name, () -> mutate(() ->
 engine.startGrind(atr, startGrindId, net, time, System.currentTimeMillis())));
}

public void openTargets() {
 Ad active = engine.getActiveSession();
 if (active == null) return;
 ajp("Targets", msg("ff"), aum(null, active.getProfitTargetGp(),
 active.getActiveTimeTargetMillis()), "Save", this::openTargets, (name, net, time) -> {
  Long netTarget = As.ks(net, active.getProfitTargetGp());
  Long timeTarget = As.lb(time, active.getActiveTimeTargetMillis());
  mutate(() -> engine.ahq(netTarget, timeTarget, System.currentTimeMillis()));
 });
}

/** Owner 2026-10-01 (F20): the run an action was opened on; never whatever is active now. */
Ad acq(String sessionId) {
 if (sessionId == null || sessionId.isEmpty()) return engine.getActiveSession();
 Ad active = engine.getActiveSession();
 return active != null && sessionId.equals(active.getId()) ? active : engine.ua(sessionId);
}

public void saveThisGrind(String sessionId) {
 Ad target = acq(sessionId);
 if (target == null || (target == engine.getActiveSession() && !engine.wb())) return;
 Long net = target.getProfitTargetGp();
 Long time = target.getActiveTimeTargetMillis();
 String identity = target.getId();
 shell.prompt("Save This Grind", "Grind name", target.getName(), cleanName -> mutate(() -> {
  // Re-validate after the prompt: the viewed run must still resolve to the same identity.
  Ad now = acq(sessionId);
  if (now == null || !identity.equals(now.getId())) return;
  Ap created = engine.avg(cleanName, net, time, false, identity);
  if (created != null) {
   shell.tell("Saved as My Grind", cleanName + msg("ki"), false);
  }
 }));
}

/**
* The one Start Grind sheet, from Live and Grinds: a saved Grind starts in one click (favourites
* first, with its targets); a new name starts once, unsaved (Add Grind saves one for later).
* Owner 2026-10-01 (F22): the saved list is bounded to five rows with paging.
*/
public void newGrind() {
 JPanel content = stack(CARD, 2);
 final java.util.ArrayList<Ap> saved = new java.util.ArrayList<>(engine.getSavedGrinds(false));
 saved.sort(java.util.Comparator.comparing(grind -> !grind.favorite));
 new Paged(saved.size(), (box, page) -> {
  int from = Math.min(page * CHOICE_ROWS, saved.size());
  for (Ap grind : saved.subList(from, Math.min(from + CHOICE_ROWS, saved.size()))) {
   JButton start = button((grind.favorite ? "\u2605 " : "") + grind.getName(), false, () -> {
    shell.pc();
    startGrind(grind.getGrindId(), null, null, null);
   });
   start.setHorizontalAlignment(SwingConstants.LEFT);
   start.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 22));
   box.add(start);
  }
 }).build(content);
 avx(content, "nameField", saved.isEmpty() ? "Grind name" : "Or a new Grind", "");
 shell.sheet("Start Grind", saved.isEmpty() ? "" : msg("jo"), content, new Shell.Choice("Start", true, () -> {
  String name = field(content, "nameField");
  if (!name.isEmpty()) startGrind(null, null, null, name);
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
  void fill(JPanel box, int page);
 }
 final JPanel box = stack(CARD, 2);
 final int total;
 final RowFill fill;
 final int[] page = {0};
 final JButton prev = button("\u2039", false, () -> turn(-1));
 final JButton next = button("\u203a", false, () -> turn(1));
 final JLabel range = label("", small(), LABEL);
 final JPanel bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
 Paged(int total, RowFill fill) {
  this.total = total;
  this.fill = fill;
  bar.setOpaque(false);
  bar.add(prev);
  bar.add(next);
  bar.add(range);
 }
 /** Adds the list (and its pager) to the sheet body and renders the first page. */
 JPanel build(JPanel content) {
  content.add(box);
  if (total > CHOICE_ROWS) content.add(bar);
  redraw();
  return box;
 }
 void turn(int delta) {
  page[0] = Math.max(0, Math.min(page[0] + delta, pages() - 1));
  redraw();
 }
 int pages() {
  return Math.max(1, (total + CHOICE_ROWS - 1) / CHOICE_ROWS);
 }
 void redraw() {
  box.removeAll();
  fill.fill(box, page[0]);
  int from = page[0] * CHOICE_ROWS;
  range.setText(total == 0 ? "" : (from + 1) + "\u2013" + Math.min(total, from + CHOICE_ROWS) + " of " + total);
  prev.setEnabled(page[0] > 0);
  next.setEnabled(page[0] < pages() - 1);
  box.revalidate();
 }
}

public void addGrind() {
 shell.prompt("Add Grind", "Grind name", "", name -> mutate(() -> engine.avg(name, null, null, false, null)));
}

public void updateSavedGrind(String grindId, String sessionId) {
 Ap definition = engine.um(grindId);
 Ad target = acq(sessionId);
 if (definition == null || target == null) return;
 Long net = target.getProfitTargetGp();
 Long time = target.getActiveTimeTargetMillis();
 String identity = target.getId();
 shell.confirm("Update " + definition.getName(), msg("go"), "Update", () -> mutate(() -> {
  // Re-validate after the confirmation: definition and viewed run must still be the same.
  Ap current = engine.um(grindId);
  Ad now = acq(sessionId);
  if (current == null || now == null || !identity.equals(now.getId())) return;
  engine.updateSavedGrind(grindId, current.getName(), net, time, current.favorite);
 }));
}

public void editSavedGrind(String grindId) {
 Ap definition = engine.um(grindId);
 if (definition == null) return;
 JPanel form = aum(definition.getName(), definition.getNetTargetGp(),
 definition.getActiveTimeTargetMillis(), definition.favorite);
 ajp("Edit " + definition.getName(), msg("gn"),
 form, "Save", () -> editSavedGrind(grindId), (name, net, time) -> {
  String cleanName = name.isEmpty() ? definition.getName() : name;
  Long netTarget = As.ks(net, definition.getNetTargetGp());
  Long timeTarget = As.lb(time, definition.getActiveTimeTargetMillis());
  // Owner 2026-10-01 (F21): the editor shows and saves the existing Favorite flag.
  boolean favorite = flag(form, "favoriteField");
  mutate(() -> engine.updateSavedGrind(grindId, cleanName, netTarget, timeTarget, favorite));
 });
}

public void startWithChanges(String grindId) {
 Ap definition = engine.um(grindId);
 if (definition == null) return;
 ajp("Start with changes", msg("jp"),
 nb(definition), "Start", () -> startWithChanges(grindId), (typed, net, time) -> {
  String name = typed.isEmpty() ? definition.getName() : typed;
  Long netTarget = As.ks(net, null);
  Long timeTarget = As.lb(time, null);
  String startGrindId = definition.getGrindId();
  // One-off changes apply to this instance only; saved defaults stay untouched.
  ako(name, () -> mutate(() ->
  engine.startGrind(name, startGrindId, netTarget, timeTarget, System.currentTimeMillis())));
 });
}

public void archiveSavedGrind(String grindId, boolean archived) {
 if (!archived) {
  mutate(() -> engine.aht(grindId, false));
  return;
 }
 shell.confirm("Archive Grind", msg("gl"), "Archive", () -> mutate(() -> engine.aht(grindId, true)));
}

public void deleteSavedGrind(String grindId) {
 Ap definition = engine.um(grindId);
 if (definition == null) return;
 shell.confirm("Delete " + definition.getName(), msg("gm"),
 "Delete", () -> mutate(() -> engine.deleteSavedGrind(grindId)));
}

public void startAgain(String sessionId) {
 Ad session = sessionId == null ? null : engine.ua(sessionId);
 if (session == null) {
  Ad active = engine.getActiveSession();
  session = active != null && active.getId().equals(sessionId) ? active : null;
 }
 if (session == null) return;
 String name = session.getName();
 String linkedGrindId = session.getGrindId();
 Long net = session.getProfitTargetGp();
 Long time = session.getActiveTimeTargetMillis();
 ako(name, () -> mutate(() ->
 engine.startGrind(name, linkedGrindId, net, time, System.currentTimeMillis())));
}

public void openDetail(String sessionId) {
 panel.grindsDetailId = sessionId;
 refresh();
}

public void deleteHistorical(String sessionId) {
 Ad session = engine.ua(sessionId);
 if (session == null) return;
 shell.confirm("Delete " + session.getName(), msg("hd"), "Delete for good", () -> {
  panel.grindsDetailId = null;
  mutate(() -> engine.qu(sessionId));
 });
}

/** The Grinds page's own menu: only what changes that page. */
public void openDataMenu(JComponent anchor) {
 var menu = new JPopupMenu();
 JCheckBoxMenuItem archived = new JCheckBoxMenuItem("Show archived", panel.grindsShowArchived);
 archived.addActionListener(e -> {
  panel.grindsShowArchived = archived.isSelected();
  refresh();
 });
 menu.add(archived);
 menu.show(anchor, 0, anchor.getHeight() + 2);
}

/** The gear menu beside the tabs: profile-wide data actions, from any page. */
JPopupMenu mv() {
 var menu = new JPopupMenu();
 menu.add(item("Back up profile", persistence != null, () -> lq()));
 menu.add(item("Restore backup\u2026", persistence != null, () -> agm()));
 menu.add(item("Clear old backups", persistence != null, this::qw));
 menu.add(item(msg("fg"), () -> qr()));
 menu.addSeparator();
 menu.add(item(msg("kj"), persistence != null, () -> ti()));
 return menu;
}

public void refresh() {
 panel.refresh();
}

/** The Start-with-changes form: name and the two targets; the saved definition is never edited. */
JPanel nb(Ap definition) {
 return aum(definition.getName(), definition.getNetTargetGp(), definition.getActiveTimeTargetMillis());
}

/** Name (when given), the two target fields and, when editing a saved Grind, its Favorite flag. */
static JPanel aum(String name, Long net, Long time) {
 return aum(name, net, time, null);
}

static JPanel aum(String name, Long net, Long time, Boolean favorite) {
 JPanel form = stack(CARD, 2);
 if (name != null) avx(form, "nameField", "Name", name);
 avx(form, "netField", msg("gv"), net == null ? "" : String.valueOf(net));
 avx(form, "timeField", msg("hg"), time == null ? "" : akk(time));
 if (favorite != null) avx(form, "favoriteField", "\u2605 Favorite", favorite.booleanValue());
 return form;
}

static void avx(JPanel form, String key, String label, String value) {
 JTextField field = new JTextField(value);
 form.add(label(label, small(), LABEL));
 form.add(field);
 form.putClientProperty(key, field);
}

static void avx(JPanel form, String key, String label, boolean selected) {
 JCheckBox box = new JCheckBox(label, selected);
 box.setOpaque(false);
 box.setFont(small());
 form.add(box);
 form.putClientProperty(key, box);
}

static boolean flag(JPanel form, String key) {
 Object box = form.getClientProperty(key);
 return box instanceof JCheckBox && ((JCheckBox) box).isSelected();
}

static String field(JPanel form, String key) {
 Object field = form.getClientProperty(key);
 return field instanceof JTextField ? ((JTextField) field).getText().trim() : "";
}

interface TargetSave {
 void save(String name, String net, String time);
}

/** A name-and-targets sheet; malformed targets never become a silent clear: say so and reopen it. */
void ajp(String title, String detail, JPanel form, String action, Runnable reopen, TargetSave save) {
 shell.sheet(title, detail, form, new Shell.Choice(action, true, () -> {
  String net = field(form, "netField");
  String time = field(form, "timeField");
  if (As.vv(net, time)) {
   save.save(field(form, "nameField"), net, time);
  } else {
   shell.tell("Targets not saved", msg("hf"), true);
   reopen.run();
  }
 }));
}

/** One Grind at a time: starting another asks to end the running one first (spec §4). */
void ako(String nextName, Runnable start) {
 if (engine.getActiveSession() == null || !engine.wb()) {
  start.run();
  return;
 }
 Ca now = panel.lastSnapshot;
 String running = engine.getActiveSession().getName()
 + (now == null ? "" : " (" + signed(now.net) + ", " + clock(now.elapsedMillis) + ")");
 // The engine never replaces a running Grind silently: Switch ends it first, then starts.
 shell.confirm("Switch Grind", "End " + running + " and start " + nextName + "?", "Switch", () -> {
  mutate(() -> engine.sx(System.currentTimeMillis()));
  start.run();
 });
}

/** Puts a path on the system clipboard. */
static void copyPath(String path) {
 java.awt.datatransfer.StringSelection selection = new java.awt.datatransfer.StringSelection(path);
 java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
}

void lq() {
 try {
  net.runelite.client.util.Filepath backup = persistence.akt(System.currentTimeMillis());
  shell.tell("Profile backed up", String.valueOf(backup.getFileName()), false);
 } catch (java.io.IOException | RuntimeException ex) {
  shell.failed("Backup failed", ex);
 }
}

/** This account's backups, newest first; restoring backs up the current state first. */
void agm() {
 JPanel content = stack(CARD, 2);
 final java.util.List<net.runelite.client.util.Filepath> files;
 final java.util.List<Long> modified = new java.util.ArrayList<>();
 try {
  files = persistence.backups();
  for (net.runelite.client.util.Filepath file : files) modified.add(file.getLastModifiedTime().toMillis());
 } catch (java.io.IOException ex) {
  shell.failed(msg("bz"), ex);
  return;
 }
 final long now = System.currentTimeMillis();
 new Paged(files.size(), (box, page) -> {
  int from = Math.min(page * CHOICE_ROWS, files.size());
  for (int i = from; i < Math.min(from + CHOICE_ROWS, files.size()); i++) {
   net.runelite.client.util.Filepath file = files.get(i);
   long at = modified.get(i);
   JButton row = button(whenSeconds(at, now), false, () -> shell.confirm("Restore backup",
   "Replace this profile with the " + whenSeconds(at, now)
   + msg("kk"), "Restore", () -> {
    Ei.Ds outcome = persistence.agm(file, System.currentTimeMillis());
    if (outcome.isApplied()) panel.afx();
    shell.tell(outcome.isApplied() ? "Backup restored" : "Restore refused",
    outcome.isApplied() ? "" : outcome.getDetail(), !outcome.isApplied());
   }));
   // Owner 2026-10-01 (F22): when() is minute-precision; the filename disambiguates.
   row.setToolTipText(String.valueOf(file.getFileName()));
   box.add(row);
  }
 }).build(content);
 shell.sheet("Restore backup", files.isEmpty() ? "No backups yet." : "Newest first.", content);
}

void qr() {
 if (repository == null) return;
 String path = String.valueOf(repository.ty());
 copyPath(path);
 shell.tell(msg("fh"), path, false);
}

/** Owner 2026-10-01 (F26): the deletion is immediate and irreversible, so state it first. */
void qw() {
 try {
  java.util.List<net.runelite.client.util.Filepath> backups = persistence.backups();
  if (backups.size() <= 1) {
   shell.tell("Nothing to clear", "0 older backups", false);
   return;
  }
  long newest = backups.get(0).getLastModifiedTime().toMillis();
  shell.confirm("Clear old backups", "Remove " + (backups.size() - 1) + " older backups; the newest ("
  + when(newest, System.currentTimeMillis()) + ") is kept?", "Remove", () -> {
   try {
    shell.tell("Old backups cleared", persistence.adz(1) + " removed; the newest is kept", false);
   } catch (java.io.IOException ex) {
    shell.failed("Clear failed", ex);
   }
  });
 } catch (java.io.IOException ex) {
  shell.failed(msg("bz"), ex);
 }
}

void ti() {
 shell.sheet("Factory reset", msg("hc"), null, new Shell.Choice("Back up first", true, () -> {
  // Owner 2026-10-01 (F26): back up first, then reset; a failed backup refuses the reset.
  try {
   net.runelite.client.util.Filepath backup = persistence.akt(System.currentTimeMillis());
   shell.tell("Profile backed up", String.valueOf(backup.getFileName()), false);
  } catch (java.io.IOException | RuntimeException ex) {
   shell.failed("Backup failed", ex);
   return;
  }
  axr();
 }), new Shell.Choice("Reset", false, this::axr));
}

/** The existing direct reset: one confirmation already happened in the sheet. */
void axr() {
 Ei.Ds outcome = persistence.sj(System.currentTimeMillis());
 if (outcome.isApplied()) {
  // The profile was replaced: HUD+ must not keep the old trip's loot or moments.
  panel.afx();
 }
 String detail = outcome.isApplied() ? (outcome.getPreResetBackup() == null ? msg("fi")
 : "backup " + String.valueOf(outcome.getPreResetBackup().getFileName())) : outcome.getDetail();
 shell.tell(outcome.isApplied() ? "Profile reset" : "Reset refused", detail, !outcome.isApplied());
}
}
