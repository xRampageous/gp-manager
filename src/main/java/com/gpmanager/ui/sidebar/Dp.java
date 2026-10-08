package com.gpmanager;
import static com.gpmanager.Ak.msg;
import com.gpmanager.Ao.Entry;
import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import javax.swing.*;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import static com.gpmanager.Kit.*;
/**
* The GP Manager sidebar as a RuneLite PluginPanel: owns the GP Surface shell and the Live page,
* reads engine state only inside {@link #refresh()} on the EDT, and asks the plugin for any
* domain mutation through its client-thread owner.
*/
class Dp extends PluginPanel {
Am engine;
GpManagerConfig config;
ItemManager itemManager;
volatile net.runelite.client.game.SpriteManager spriteManager;
/** Domain-mutation owner (charter V); the plugin binds the client thread, tests run direct. */
volatile Consumer<Runnable> mutationOwner = Runnable::run;
Shell shell = new Shell();
LivePage live;
LedgerPage ledger;
GrindsPage grinds;
SessionRepository repository;
Ei persistence;
String grindsDetailId;
boolean grindsShowArchived;
int historyScopeMenuPage;
/** The Ledger's typed context; presentation state only. */
Entry ledgerEntry = Entry.current();
String ledgerSearch = "";
ConcurrentHashMap<Integer, BufferedImage> sprites = new ConcurrentHashMap<>();
volatile Supplier<Bo> pvpSource = () -> Bo.NONE;
/** HUD+ reads the same per-tick Live snapshot; null in previews and tests. */
volatile Cp hud;
volatile BooleanSupplier loggedInSource = () -> true;
/** True while a world hop is in flight; presentation only. */
volatile BooleanSupplier hoppingSource = () -> false;
/** Preview/test seam: deterministic item sprites; null keeps the real ItemManager lookup. */
volatile Function<Integer, BufferedImage> spriteOverride;
/** Presentation-only loot visibility; null keeps every booked row visible. */
volatile RecentFilter filter;
volatile boolean active;
boolean scheduled;
/** A real change asked for the visible page to be re-read on the next drain. */
boolean pageForced = true;
/** Page, engine revision and minute the visible non-Live page was last read at. */
/**
* The revision and minute each page was last filled at. Switching back to a page whose data
* has not changed shows its existing rows instead of re-reading and rebuilding them.
*/
java.util.Map<String, String> pageKeys = new java.util.HashMap<>();
boolean suspended;
Ca lastSnapshot;
Dp(Am /* engine */a, GpManagerConfig /* config */b, ItemManager /* itemManager */c) {
 this(a, b, c, null, null);
}

/** The plugin supplies the current export/backup/recovery services; tests and previews may not. */
Dp(Am /* engine */a, GpManagerConfig /* config */b, ItemManager /* itemManager */c,
SessionRepository /* repository */d, Ei /* persistence */e) {
 super(false);
 this.engine = a;
 this.config = b;
 this.itemManager = c;
 this.repository = d;
 this.persistence = e;
 setLayout(new BorderLayout());
 setBackground(PANEL);
 live = new LivePage(new LiveActions(), this::sprite);
 ledger = new LedgerPage(new LedgerController(this), this::sprite);
 grinds = new GrindsPage(new GrindsController(this));
 shell.axm(live);
 shell.axm(ledger);
 shell.axm(grinds);
 add(shell, BorderLayout.CENTER);
 shell.show(Shell.LIVE);
 shell.onShow = this::schedule;
 shell.onGear = /* anchor */f -> new GrindsController(this).mv().show(f, 0, f.getHeight() + 2);
 SwingUtilities.invokeLater(() -> active = true);
}

/** Client-thread PvP facts (dangerous context, skull, Protect Item); presentation only. */
void axo(Supplier<Bo> /* source */a) {
 pvpSource = a == null ? () -> Bo.NONE : a;
}

/** HUD+ builds from this panel's per-tick snapshot, even while the sidebar is closed. */
void axn(Cp /* builder */a) {
 hud = a;
}

/** A factory reset replaced the profile: the bound HUD+ trip starts clean, like a new profile. */
void afx() {
 Cp /* builder */b = hud;
 if (b != null) {
  b.agj();
  // Repaint on the next EDT pass instead of waiting up to a game tick.
  refresh();
 }
}

/** Whether the client is logged in; previews and tests stay logged in. */
void na(BooleanSupplier /* source */a) {
 loggedInSource = a == null ? () -> true : a;
}

/** Binds the transient hop signal; presentation only. */
void axq(BooleanSupplier /* source */a) {
 hoppingSource = a == null ? () -> false : a;
}

/**
* The current loot visibility decision (the existing filter authority, bound by the plugin).
* Presentation only: hidden rows stay booked, accounted and queryable; nothing here values.
*/
void mh(RecentFilter /* source */a) {
 filter = a;
 refresh();
}

Shell shell() {
 return shell;
}

/**
* Coalesced refresh after a real change (an action, a booking, a setting): the visible page
* is re-read. Safe from any thread and never blocks the caller.
*/
void refresh() {
 if (SwingUtilities.isEventDispatchThread()) {
  pageForced = true;
  schedule();
  return;
 }
 SwingUtilities.invokeLater(this::refresh);
}

/**
* The plugin's per-game-tick pulse. Live values update in place; another visible page is
* re-read only when the engine revision or the minute changed, so its rows (and any menu
* open on them) are not rebuilt every tick.
*/
void tick() {
 lastTickAt = Ae.now();
 if (SwingUtilities.isEventDispatchThread()) {
  schedule();
  return;
 }
 SwingUtilities.invokeLater(this::tick);
}

/**
* Game ticks stop while Windows resizes the client window, so a one-second fallback keeps the
* Live clock and rate moving; it stays quiet while ticks arrive.
*/
volatile long lastTickAt;
Timer clockPulse = new Timer(1_000, e -> {
 if (Ae.now() - lastTickAt > 1_200L) schedule();
});
public void addNotify() {
 super.addNotify();
 clockPulse.start();
}

public void removeNotify() {
 clockPulse.stop();
 super.removeNotify();
}

void schedule() {
 if (!scheduled) {
  scheduled = true;
  SwingUtilities.invokeLater(this::drain);
 }
}

void drain() {
 scheduled = false;
 avz();
}

void avz() {
 boolean /* showing */b = active || isShowing();
 if (!b && hud == null) return;
 if (suspended) return;
 ajf();
 long /* now */c = Ae.now();
 Bo /* pvp */d = pvpSource.get();
 if (d == null) d = Bo.NONE;
 String /* page */e = shell.qt();
 long /* minute */f = c / 60_000L;
 String /* key */g;
 Ca /* snapshot */h;
 Ao /* amc */i = null;
 As /* ama */j = null;
 // One consistent read: the client thread mutates sessions under the engine's monitor, so
 // no list is iterated here while it changes. Nothing inside this block touches Swing.
 Dz /* context */k;
 synchronized (engine) {
  // Wealth moves without a financial revision, so it keys the page too (Codex review 1.2).
  g = engine.getRevision() + ":" + f + ":" + engine.bankValue + ":" + engine.bankAt + ":" + engine.carriedValue;
  Cp /* ann */l = hud;
  k = new Dz(engine.yp(), filter,
  Fmt.axx(config.minimumDisplayedLootValue()), d, activityLabel(),
  !loggedInSource.getAsBoolean(), l == null ? "" : l.vx(), hoppingSource.getAsBoolean());
  h = Ca.capture(engine, c, k);
  if (pageForced) {
   // A setting, search or scope change can alter a page without a new revision.
   pageKeys.clear();
  }
  boolean /* atb */m = b && !g.equals(pageKeys.get(e));
  if (m && Shell.LEDGER.equals(e)) {
   i = Ao.capture(engine, c, ledgerEntry);
  } else if (m && Shell.GRINDS.equals(e)) {
   j = As.capture(engine, c, grindsShowArchived, grindsDetailId);
  }
 }
 if (hud != null) hud.update(h, k::td, c);
 if (!b) return;
 pageForced = false;
 lastSnapshot = h;
 live.apply(h);
 live.body().revalidate();
 if (i != null) {
  ledger.apply(i);
  ledger.body().revalidate();
 } else if (j != null) {
  grinds.apply(j);
  grinds.body().revalidate();
 }
 if (i != null || j != null) pageKeys.put(e, g);
}

/** Reports only backend-proven recovery or save failure states; successful saves stay quiet. */
void ajf() {
 if (persistence == null) return;
 Ci /* status */b = persistence.ux();
 if (b == null) return;
 if (b.auq()) {
  // Owner 2026-10-01 (F06): a save failure stays visible until the backend recovers.
  String /* detail */c = b.detail.isEmpty() ? b.state.name() : b.detail;
  shell.notifySticky("Data not saved", c, b.retryAvailable ? "Retry" : null,
  b.retryAvailable ? persistence::ahe : null);
  return;
 }
 shell.clearSticky();
 if (b.recoveredFrom != null && !b.recoveredFrom.isEmpty()) {
  shell.notify(msg("e4"), b.recoveredFrom, false, null, null);
 }
}

String activityLabel() {
 String /* hint */a = engine.getMetrics(Ae.now()).activityHint;
 return a == null || "General".equalsIgnoreCase(a) ? "" : a;
}

BufferedImage sprite(int /* itemId */a) {
 // Owner 1.1: item icons can be turned off everywhere.
 if (!config.showItemIcons()) return null;
 if (a < -1 && spriteManager != null && !sprites.containsKey(a)) {
  // A named spell's row shows its spellbook icon; the sprite id arrives negated.
  spriteManager.getSpriteAsync(-a, 0, /* image */b -> {
   if (b != null) axu(a, b);
  });
 }
 if (a <= 0) return sprites.get(a);
 if (spriteOverride != null) {
  BufferedImage /* override */c = spriteOverride.apply(a);
  if (c != null) return c;
 }
 if (itemManager == null) return null;
 BufferedImage /* cached */d = sprites.get(a);
 if (d != null) return d;
 try {
  AsyncBufferedImage /* image */e = itemManager.getImage(a, 20, false);
  if (e != null) {
   sprites.put(a, e);
   // Late sprites must rebuild the rows drawn without them, not just repaint: rows keep
   // the icon they were built with until the page re-applies (owner 2026-10-01).
   e.onLoaded(() -> axu(a, e));
  }
  return e;
 } catch (RuntimeException /* ex */f) {
  // The sprite cache is unavailable (headless preview): the row keeps its item name.
  return null;
 }
}

/** A sprite that arrived after its row was drawn: cache it and force the rows to rebuild. */
void axu(int /* itemId */a, BufferedImage /* image */b) {
 sprites.put(a, Kit.fitIcon(b, Kit.ICON_BOX));
 refresh();
}

void ot() {
 suspended = true;
 sprites.clear();
 engine.ov();
 ahk(() -> {
  ledgerEntry = Entry.current();
  ledgerSearch = "";
  grindsDetailId = null;
  historyScopeMenuPage = 0;
  lastSnapshot = null;
  pageKeys.clear();
  pageForced = true;
  shell.clearSticky();
  ledger.agf();
  live.ot();
  shell.show(Shell.LIVE);
 });
}

void ahd() {
 ahk(() -> {
  suspended = false;
  avz();
 });
}

static void ahk(Runnable /* task */a) {
 if (SwingUtilities.isEventDispatchThread()) {
  a.run();
  return;
 }
 try {
  SwingUtilities.invokeAndWait(a);
 } catch (InterruptedException /* ex */b) {
  SwingUtilities.invokeLater(a);
 } catch (Exception /* ex */c) {
  throw new IllegalStateException(msg("an"), c);
 }
}

class LiveActions implements LivePage.Actions {
 public void togglePause() {
  mutate(() -> engine.togglePause(Ae.now()));
 }
 public void openLedger(Entry /* entry */b) {
  acg(b);
 }
 public void editTarget() {
  new GrindsController(Dp.this).openTargets();
 }
 @Override
 public boolean autoStartArmed() {
  return config.autoStartSession();
 }
 @Override
 public void hideRecent(String /* name */b) {
  if (filter != null) filter.hideRecent(b);
  refresh();
 }
 @Override
 public void renameGrind() {
  if (engine.wb()) {
   shell.prompt("Rename Grind", "Grind name", engine.getActiveSession().getName(),
   /* name */b -> mutate(() -> engine.afy(b)));
  }
 }
 @Override
 public void endGrind() {
  new GrindsController(Dp.this).endGrind();
 }
 @Override
 public void startGrind() {
  new GrindsController(Dp.this).newGrind();
 }
}

/** Opens the Ledger at a typed context. */
void acg(Entry /* entry */a) {
 ledger.ads();
 ledgerEntry = a;
 ledgerSearch = a.search;
 shell.show(Shell.LEDGER);
 refresh();
}

void mutate(Runnable /* domain */a) {
 // The change runs on the mutation owner (the client thread in the plugin); the redraw is
 // queued only after it has applied, so the sidebar never repaints the pre-click state.
 mutationOwner.accept(() -> {
  a.run();
  // A sidebar edit (rename, targets, Start/End) saves now rather than at the next heartbeat.
  if (persistence != null && config.persistHistory()) persistence.ahe();
  refresh();
 });
}
}
