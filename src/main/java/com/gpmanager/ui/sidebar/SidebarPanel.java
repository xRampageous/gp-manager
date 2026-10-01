package com.gpmanager;
import static com.gpmanager.GameData.msg;
import com.gpmanager.LedgerData.Entry;
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
class SidebarPanel extends PluginPanel {
final Engine engine;
final GpManagerConfig config;
final ItemManager itemManager;
volatile net.runelite.client.game.SpriteManager spriteManager;
/** Domain-mutation owner (charter Variant); the plugin binds the client thread, tests run direct. */
volatile Consumer<Runnable> mutationOwner = Runnable::run;
final Shell shell = new Shell();
final LivePage live;
final LedgerPage ledger;
final GrindsPage grinds;
final CsvExporter csvExporter;
final SessionRepository repository;
final PersistenceCoordinator persistence;
String grindsDetailId;
boolean grindsShowArchived;
int historyScopeMenuPage;
/** The Ledger's typed context; presentation state only. */
Entry ledgerEntry = Entry.current();
String ledgerSearch = "";
final ConcurrentHashMap<Integer, BufferedImage> sprites = new ConcurrentHashMap<>();
volatile Supplier<PvpState> pvpSource = () -> PvpState.NONE;
/** HUD+ reads the same per-tick Live snapshot; null in previews and tests. */
volatile HudBuilder hud;
String bestsFor;
long[] bests;
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
final java.util.Map<String, String> pageKeys = new java.util.HashMap<>();
boolean suspended;
LiveSnapshot lastSnapshot;
SidebarPanel(Engine engine, GpManagerConfig config, ItemManager itemManager) {
 this(engine, config, itemManager, null, null, null);
}

/** The plugin supplies the current export/backup/recovery services; tests and previews may not. */
SidebarPanel(Engine engine, GpManagerConfig config, ItemManager itemManager,
CsvExporter csvExporter, SessionRepository repository, PersistenceCoordinator persistence) {
 super(false);
 this.engine = engine;
 this.config = config;
 this.itemManager = itemManager;
 this.csvExporter = csvExporter;
 this.repository = repository;
 this.persistence = persistence;
 setLayout(new BorderLayout());
 setBackground(PANEL);
 live = new LivePage(new LiveActions(), this::sprite);
 ledger = new LedgerPage(new LedgerController(this), this::sprite);
 grinds = new GrindsPage(new GrindsController(this));
 shell.addPage(live);
 shell.addPage(ledger);
 shell.addPage(grinds);
 add(shell, BorderLayout.CENTER);
 shell.show(Shell.LIVE);
 shell.onShow = this::schedule;
 shell.onGear = anchor -> new GrindsController(this).buildDataMenu().show(anchor, 0, anchor.getHeight() + 2);
 SwingUtilities.invokeLater(() -> active = true);
}

/** Client-thread PvP facts (dangerous context, skull, Protect Item); presentation only. */
void bindPvp(Supplier<PvpState> source) {
 pvpSource = source == null ? () -> PvpState.NONE : source;
}

/** HUD+ builds from this panel's per-tick snapshot, even while the sidebar is closed. */
void bindHud(HudBuilder builder) {
 hud = builder;
}

/** A factory reset replaced the profile: the bound HUD+ trip starts clean, like a new profile. */
void resetProfilePresentation() {
 HudBuilder builder = hud;
 if (builder != null) {
  builder.resetPresentation();
  // Repaint on the next EDT pass instead of waiting up to a game tick.
  refresh();
 }
}

/** Whether the client is logged in; previews and tests stay logged in. */
void bindLoggedIn(BooleanSupplier source) {
 loggedInSource = source == null ? () -> true : source;
}

/** Binds the transient hop signal; presentation only. */
void setHoppingSource(BooleanSupplier source) {
 hoppingSource = source == null ? () -> false : source;
}

/**
* The current loot visibility decision (the existing filter authority, bound by the plugin).
* Presentation only: hidden rows stay booked, accounted and queryable; nothing here values.
*/
void bindFilter(RecentFilter source) {
 filter = source;
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
 lastTickAt = System.currentTimeMillis();
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
final Timer clockPulse = new Timer(1_000, e -> {
 if (System.currentTimeMillis() - lastTickAt > 1_200L) schedule();
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
 applyNow();
}

void applyNow() {
 boolean showing = active || isShowing();
 if (!showing && hud == null) return;
 if (suspended) return;
 surfacePersistenceWarning();
 long now = System.currentTimeMillis();
 PvpState pvp = pvpSource.get();
 if (pvp == null) pvp = PvpState.NONE;
 String page = shell.currentPage();
 long minute = now / 60_000L;
 long revision;
 LiveSnapshot snapshot;
 LedgerData ledgerData = null;
 GrindsData grindData = null;
 // One consistent read: the client thread mutates sessions under the engine's monitor, so
 // no list is iterated here while it changes. Nothing inside this block touches Swing.
 LiveContext context;
 synchronized (engine) {
  revision = engine.getRevision();
  HudBuilder boundHud = hud;
  context = new LiveContext(engine.isIdlePaused(), filter,
  Fmt.parseGp(config.minimumDisplayedLootValue()), pvp, activityLabel(),
  !loggedInSource.getAsBoolean(), boundHud == null ? "" : boundHud.interactionTarget(), hoppingSource.getAsBoolean());
  snapshot = LiveSnapshot.capture(engine, now, context);
  Session running = engine.getActiveSession();
  String grindId = running == null ? "" : running.getGrindId();
  // Owner 2026-10-01 (F04): restore, delete and mutation all recompute the cached bests.
  String bestsKey = grindId + ":" + engine.getRevision() + ":" + engine.getHistory().size();
  if (hud != null && !bestsKey.equals(bestsFor)) {
   bestsFor = bestsKey;
   bests = GrindHistory.bests(engine, grindId, now);
  }
  if (pageForced) {
   // A setting, search or scope change can alter a page without a new revision.
   pageKeys.clear();
  }
  boolean pageDue = showing && !(revision + ":" + minute).equals(pageKeys.get(page));
  if (pageDue && Shell.LEDGER.equals(page)) {
   ledgerData = LedgerData.capture(engine, now, ledgerEntry);
  } else if (pageDue && Shell.GRINDS.equals(page)) {
   grindData = GrindsData.capture(engine, now, grindsShowArchived, grindsDetailId);
  }
 }
 if (hud != null) hud.update(snapshot, context::flowVisible, bests, now);
 if (!showing) return;
 pageForced = false;
 lastSnapshot = snapshot;
 live.apply(snapshot);
 live.body().revalidate();
 if (ledgerData != null) {
  ledger.apply(ledgerData);
  ledger.body().revalidate();
 } else if (grindData != null) {
  grinds.apply(grindData);
  grinds.body().revalidate();
 }
 if (ledgerData != null || grindData != null) pageKeys.put(page, revision + ":" + minute);
}

/** Reports only backend-proven recovery or save failure states; successful saves stay quiet. */
void surfacePersistenceWarning() {
 if (persistence == null) return;
 SaveStatus status = persistence.getSaveStatus();
 if (status == null) return;
 if (status.isFailure()) {
  // Owner 2026-10-01 (F06): a save failure stays visible until the backend recovers.
  String detail = status.detail.isEmpty() ? status.state.name() : status.detail;
  shell.notifySticky("Data not saved", detail, status.retryAvailable ? "Retry" : null,
  status.retryAvailable ? persistence::scheduleSave : null);
  return;
 }
 shell.clearSticky();
 if (status.recoveredFrom != null && !status.recoveredFrom.isEmpty()) {
  shell.notify("Recovered from backup", status.recoveredFrom, false, null, null);
 }
}

String activityLabel() {
 String hint = engine.getMetrics(System.currentTimeMillis()).activityHint;
 return hint == null || "General".equalsIgnoreCase(hint) ? "" : hint;
}

BufferedImage sprite(int itemId) {
 if (itemId < -1 && spriteManager != null && !sprites.containsKey(itemId)) {
  // A named spell's row shows its spellbook icon; the sprite id arrives negated.
  spriteManager.getSpriteAsync(-itemId, 0, image -> {
   if (image != null) spriteLoaded(itemId, image);
  });
 }
 if (itemId <= 0) return sprites.get(itemId);
 if (spriteOverride != null) {
  BufferedImage override = spriteOverride.apply(itemId);
  if (override != null) return override;
 }
 if (itemManager == null) return null;
 BufferedImage cached = sprites.get(itemId);
 if (cached != null) return cached;
 try {
  AsyncBufferedImage image = itemManager.getImage(itemId, 20, false);
  if (image != null) {
   sprites.put(itemId, image);
   // Late sprites must rebuild the rows drawn without them, not just repaint: rows keep
   // the icon they were built with until the page re-applies (owner 2026-10-01).
   image.onLoaded(() -> spriteLoaded(itemId, image));
  }
  return image;
 } catch (RuntimeException ex) {
  // The sprite cache is unavailable (headless preview): the row keeps its item name.
  return null;
 }
}

/** A sprite that arrived after its row was drawn: cache it and force the rows to rebuild. */
void spriteLoaded(int itemId, BufferedImage image) {
 sprites.put(itemId, image);
 refresh();
}

void clearOwnerScope() {
 suspended = true;
 sprites.clear();
 engine.clearPendingActionEvidence();
 runOnEdtAndWait(() -> {
  ledgerEntry = Entry.current();
  ledgerSearch = "";
  grindsDetailId = null;
  historyScopeMenuPage = 0;
  lastSnapshot = null;
  pageKeys.clear();
  pageForced = true;
  shell.clearSticky();
  ledger.resetOwnerScope();
  live.clearOwnerScope();
  shell.show(Shell.LIVE);
 });
}

void resumeOwnerScope() {
 runOnEdtAndWait(() -> {
  suspended = false;
  applyNow();
 });
}

static void runOnEdtAndWait(Runnable task) {
 if (SwingUtilities.isEventDispatchThread()) {
  task.run();
  return;
 }
 try {
  SwingUtilities.invokeAndWait(task);
 } catch (InterruptedException ex) {
  Thread.currentThread().interrupt();
  SwingUtilities.invokeLater(task);
 } catch (Exception ex) {
  throw new IllegalStateException(msg("an"), ex);
 }
}

class LiveActions implements LivePage.Actions {
 public void togglePause() {
  mutate(() -> engine.togglePause(System.currentTimeMillis()));
 }
 public void openLedger(Entry entry) {
  openLedgerEntry(entry);
 }
 public void editTarget() {
  new GrindsController(SidebarPanel.this).openTargets();
 }
 @Override
 public boolean autoStartArmed() {
  return config.autoStartSession();
 }
 @Override
 public void hideRecent(String name) {
  if (filter != null) filter.hideRecent(name);
  refresh();
 }
 @Override
 public void renameGrind() {
  if (engine.isCustomSessionActive()) {
   shell.prompt("Rename Grind", "Grind name", engine.getActiveSession().getName(),
   name -> mutate(() -> engine.renameActive(name)));
  }
 }
 @Override
 public void endGrind() {
  new GrindsController(SidebarPanel.this).endGrind();
 }
 @Override
 public void startGrind() {
  new GrindsController(SidebarPanel.this).newGrind();
 }
}

/** Opens the Ledger at a typed context. */
void openLedgerEntry(Entry entry) {
 ledger.prepareForEntry();
 ledgerEntry = entry;
 ledgerSearch = entry.search;
 shell.show(Shell.LEDGER);
 refresh();
}

/** Exports one Grind as canonical CSV and says where it went. */
void exportGrind(Session session) {
 if (session == null || csvExporter == null || repository == null) return;
 try {
  net.runelite.client.util.Filepath file = csvExporter.exportSession(session, engine.tx(), repository.exportDirectory);
  // Owner 2026-10-01 (F15): filename in the notice; the exact path behind Copy.
  shell.notify("Exported " + session.getName(), String.valueOf(file.getFileName()), false, "Copy file path",
  () -> GrindsController.copyPath(String.valueOf(file)));
 } catch (java.io.IOException | RuntimeException ex) {
  shell.failed("Export failed", ex);
 }
}

void mutate(Runnable domain) {
 // The change runs on the mutation owner (the client thread in the plugin); the redraw is
 // queued only after it has applied, so the sidebar never repaints the pre-click state.
 mutationOwner.accept(() -> {
  domain.run();
  // A sidebar edit (rename, targets, Start/End) saves now rather than at the next heartbeat.
  if (persistence != null && config.persistHistory()) persistence.scheduleSave();
  refresh();
 });
}
}
