package com.gpmanager;

import static javax.swing.SwingUtilities.*;


import static com.gpmanager.GameData.msg;
import static com.gpmanager.Kit.*;

import com.gpmanager.LedgerData.Entry;
import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.*;
import org.slf4j.*;
import static com.gpmanager.SafeMath.now;

/**
 * The GP Manager sidebar as a RuneLite PluginPanel: owns the GP Surface shell and the Live page,
 * reads engine state only inside {@link #refresh()} on the EDT, and asks the plugin for any domain
 * mutation through its client-thread owner.
 */
class SidebarPanel extends PluginPanel {
    Engine engine;
    GpManagerConfig config;
    ItemManager itemManager;
    volatile net.runelite.client.game.SpriteManager spriteManager;

    /** Domain-mutation owner (charter V); the plugin binds the client thread, tests run direct. */
    volatile Consumer<Runnable> mutationOwner = Runnable::run;

    Shell shell = new Shell();
    LivePage live;
    LedgerPage ledger, liveLedger;
    Entry liveEntry;
    GrindsPage grinds;
    GrindsController grindActions;
    SessionRepository repository;
    Persistence persistence;
    String grindsDetailId;
    boolean grindsShowArchived;
    int historyScopeMenuPage;

    /** The Ledger's typed context; presentation state only. */
    Entry ledgerEntry = Entry.current();

    String ledgerSearch = "";
    ConcurrentHashMap<Integer, BufferedImage> sprites = new ConcurrentHashMap<>();
    volatile Supplier<PvpState> pvpSource = () -> PvpState.NONE;

    /** HUD+ reads the same per-tick Live snapshot; null in previews and tests. */
    volatile HudBuilder hud;

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
     * The revision and minute each page was last filled at. Switching back to a page whose data has
     * not changed shows its existing rows instead of re-reading and rebuilding them.
     */
    java.util.Map<String, String> pageKeys = new java.util.HashMap<>();

    volatile boolean suspended;
    volatile Object ownerToken = new Object();
    LiveSnapshot lastSnapshot;

    /**
     * The plugin supplies the current export/backup/recovery services; tests and previews may not.
     */
    SidebarPanel(Engine engine, GpManagerConfig config, ItemManager items, SessionRepository repository,
            Persistence persistence) {
        super(false);
        this.engine = engine;
        this.config = config;
        itemManager = items;
        this.repository = repository;
        this.persistence = persistence;
        grindActions = new GrindsController(this);
        setLayout(new BorderLayout());
        setBackground(PANEL);
        live = new LivePage(new LiveActions(), this::sprite);
        ledger = new LedgerPage(new LedgerController(this), this::sprite);
        grinds = new GrindsPage(grindActions);
        shell.addPage(live);
        shell.addPage(ledger);
        shell.addPage(grinds);
        add(shell, BorderLayout.CENTER);
        shell.show(Shell.LIVE);
        shell.onShow = this::schedule;
        shell.onGear = anchor -> grindActions.dataMenu().show(anchor, 0, anchor.getHeight() + 2);
        invokeLater(() -> active = true);
    }

    /** Client-thread PvP facts (dangerous context, skull, Protect Item); presentation only. */
    void bindPvp(Supplier<PvpState> source) {
        pvpSource = source == null ? () -> PvpState.NONE : source;
    }

    /** HUD+ builds from this panel's per-tick snapshot, even while the sidebar is closed. */
    void bindHud(HudBuilder builder) {
        hud = builder;
    }

    /**
     * A factory reset replaced the profile: the bound HUD+ trip starts clean, like a new profile.
     */
    void resetProfile() {
        var builder = hud;
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
    void bindHopping(BooleanSupplier source) {
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
     * Coalesced refresh after a real change (an action, a booking, a setting): the visible page is
     * re-read. Safe from any thread and never blocks the caller.
     */
    void refresh() {
        if (isEventDispatchThread()) {
            pageForced = true;
            schedule();
            return;
        }
        invokeLater(this::refresh);
    }

    /**
     * The plugin's per-game-tick pulse. Live values update in place; another visible page is
     * re-read only when the engine revision or the minute changed, so its rows (and any menu open
     * on them) are not rebuilt every tick.
     */
    void tick() {
        lastTickAt = now();
        if (isEventDispatchThread()) {
            schedule();
            return;
        }
        invokeLater(this::tick);
    }

    /**
     * Game ticks stop while Windows resizes the client window, so a one-second fallback keeps the
     * Live clock and rate moving; it stays quiet while ticks arrive.
     */
    volatile long lastTickAt;

    Timer clockPulse = new Timer(1_000, e -> {
                        if (now() - lastTickAt > 1_200) schedule();
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
            invokeLater(this::drain);
        }
    }

    void drain() {
        scheduled = false;
        refreshNow();
    }

    void refreshNow() {
        boolean showing = active || isShowing();
        if (!showing && hud == null) return;
        if (suspended) return;
        showPersistenceWarning();
        long now = now();
        var pvp = pvpSource.get();
        if (pvp == null) pvp = PvpState.NONE;
        String page = shell.currentPage();
        long minute = now / 60_000;
        String key;
        LiveSnapshot snapshot;
        LedgerData ledgerData = null;
        GrindsData data = null;
        // One consistent read: the client thread mutates sessions under the engine's monitor, so
        // no list is iterated here while it changes. Nothing inside this block touches Swing.
        LiveContext context;
        synchronized (engine) {
            key = engine.getRevision() + ":" + minute;
            var boundHud = hud;
            context = new LiveContext(engine.isIdlePaused(), filter, Fmt.parseGp(config.minimumDisplayedLootValue()),
                            pvp, activityLabel(), !loggedInSource.getAsBoolean(),
                            boundHud == null ? "" : boundHud.target(), hoppingSource.getAsBoolean());
            snapshot = LiveSnapshot.capture(engine, now, context);
            if (pageForced) {
                // A setting, search or scope change can alter a page without a new revision.
                pageKeys.clear();
            }
            boolean pageDue = showing && !key.equals(pageKeys.get(page));
            if (liveEntry != null && lastSnapshot != null
                    && !lastSnapshot.sessionId.equals(snapshot.sessionId)) liveEntry = null;
            if (pageDue && Shell.LIVE.equals(page) && liveEntry != null) {
                ledgerData = LedgerData.capture(engine, now, liveEntry);
                if (liveEntry.search.isEmpty()) {
                    ledgerData.gains.total = snapshot.gains;
                    ledgerData.costs.total = liveEntry.liveTable == Semantics.Table.COSTS_LOSS
                            ? -snapshot.loss : -snapshot.supplies;
                    ledgerData.market.total = snapshot.marketResult;
                }
            } else if (pageDue && Shell.LEDGER.equals(page)) {
                ledgerData = LedgerData.capture(engine, now, ledgerEntry);
            } else if (pageDue && Shell.GRINDS.equals(page)) {
                data = GrindsData.capture(engine, now, grindsShowArchived, grindsDetailId);
            }
        }
        if (hud != null) {
            hud.filter = context.filter;
            hud.update(snapshot, context::flowVisible, now);
        }
        if (!showing) return;
        pageForced = false;
        lastSnapshot = snapshot;
        live.apply(snapshot);
        live.category(liveEntry == null ? null : liveLedger);
        if (ledgerData != null) {
            (Shell.LIVE.equals(page) ? liveLedger : ledger).apply(ledgerData);
        } else if (data != null) {
            grinds.apply(data);
        }
        shell.pages.get(page).visible(true);
        if (ledgerData != null || data != null) pageKeys.put(page, key);
    }

    /** Reports only backend-proven recovery or save failure states; successful saves stay quiet. */
    void showPersistenceWarning() {
        if (persistence == null) return;
        var status = persistence.saveStatus();
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
            shell.notify(msg("recovered-backup"), status.recoveredFrom, false, null, null);
        }
    }

    String activityLabel() {
        var active = engine.getActiveSession();
        String hint = active == null ? "" : active.getActivityHint();
        return "General".equalsIgnoreCase(hint) || "Magic".equalsIgnoreCase(hint) ? "" : hint;
    }

    BufferedImage sprite(int id) {
        // Owner 1.1: item icons can be turned off everywhere.
        if (!config.showItemIcons()) return null;
        if (id < -1 && spriteManager != null && !sprites.containsKey(id)) {
            // A named spell's row shows its spellbook icon; the sprite id arrives negated.
            spriteManager.getSpriteAsync(-id, 0, image -> {
                        if (image != null) spriteLoaded(id, image);
                    });
        }
        if (id <= 0) return sprites.get(id);
        if (spriteOverride != null) {
            var override = spriteOverride.apply(id);
            if (override != null) return override;
        }
        if (itemManager == null) return null;
        var cached = sprites.get(id);
        if (cached != null) return cached;
        try {
            var image = itemManager.getImage(id, 20, false);
            if (image != null) {
                sprites.put(id, image);
                // Late sprites must rebuild the rows drawn without them, not just repaint: rows
                // keep
                // the icon they were built with until the page re-applies (owner 2026-10-01).
                image.onLoaded(() -> spriteLoaded(id, image));
            }
            return image;
        } catch (RuntimeException ex) {
            // The sprite cache is unavailable (headless preview): the row keeps its item name.
            return null;
        }
    }

    /** A sprite that arrived after its row was drawn: cache it and force the rows to rebuild. */
    void spriteLoaded(int id, BufferedImage image) {
        sprites.put(id, fitIcon(image, ICON_BOX));
        refresh();
    }

    void suspend() {
        suspended = true;
        ownerToken = new Object();
        sprites.clear();
        engine.clearActionEvidence();
        runOnEdt(() -> {
                    cancelKeys(shell);
                    shell.dismiss();
                    ledgerEntry = Entry.current();
                    liveEntry = null;
                    if (liveLedger != null) liveLedger.resetOwner();
                    ledgerSearch = "";
                    grindsDetailId = null;
                    historyScopeMenuPage = 0;
                    lastSnapshot = null;
                    pageKeys.clear();
                    pageForced = true;
                    shell.clearSticky();
                    ledger.resetOwner();
                    live.clearOwner();
                    shell.pages.values().forEach(page -> page.visible(false));
                    shell.show(Shell.LIVE);
                });
    }

    void resume() {
        runOnEdt(() -> {
                    suspended = false;
                    refreshNow();
                });
    }

    static void runOnEdt(Runnable task) {
        if (isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            invokeAndWait(task);
        } catch (InterruptedException ex) {
            invokeLater(task);
        } catch (Exception ex) {
            throw new IllegalStateException(msg("swing-owner"), ex);
        }
    }

    class LiveActions implements LivePage.Actions {
        public void togglePause() {
            mutate(() -> engine.togglePause(now()));
        }

        public void openLedger(Entry entry) {
            openEntry(entry);
        }

        public void openCategory(Semantics.Table table) {
            if (liveEntry != null && liveEntry.liveTable == table) {
                liveLedger.actions.recent();
                return;
            }
            liveEntry = Entry.current().live(table);
            if (liveLedger == null) liveLedger = new LedgerPage(new LedgerController(SidebarPanel.this, true), SidebarPanel.this::sprite);
            liveLedger.resetOwner();
            live.category(liveLedger);
            refresh();
        }

        public void editTarget() {
            grindActions.openTargets();
        }

        /** Again: a new session with the same target, in the same run (owner 1.5.2). */
        public void againTarget() {
            mutate(() -> engine.restartTarget(now()));
        }

        public void doneTarget() {
            mutate(() -> engine.setTargets(null, null, now()));
        }

        /** Owner live 2026-10-14: the timer's own pause; the session pause stays the hero dot. */
        public void toggleTimerPause() {
            mutate(() -> engine.toggleTimeTargetPause(now()));
        }

        @Override
        public boolean autoStartArmed() {
            return config.autoStartSession();
        }

        @Override
        public void hideRecent(String name, boolean charge) {
            if (filter != null) {
                filter.hideRecent(name, charge);
                shell.tell("Hidden from Recent", name + " · Booked totals unchanged.", false);
            }
            refresh();
        }

        @Override
        public void renameGrind() {
            if (engine.isCustomActive()) {
                shell.prompt("Rename Grind", "Grind name", engine.getActiveSession().getName(),
                        name -> mutate(() -> engine.renameActive(name)));
            }
        }

        @Override
        public void showWholeRun() {
            grindActions.saveSessionPrompt();
        }

        public void newSession() {
            grindActions.newSession();
        }

    }

    /** Opens the Ledger at a typed context. */
    void openEntry(Entry entry) {
        ledger.prepareEntry();
        ledgerEntry = entry;
        ledgerSearch = entry.search;
        shell.show(Shell.LEDGER);
        refresh();
    }

    void mutate(Runnable domain) {
        // The change runs on the mutation owner (the client thread in the plugin); the redraw is
        // queued only after it has applied, so the sidebar never repaints the pre-click state.
        Object owner = ownerToken;
        if (suspended) return;
        mutationOwner.accept(() -> {
                    synchronized (engine) {
                        if (suspended || ownerToken != owner) return;
                        domain.run();
                    }
                    // A sidebar edit (rename, targets, Start/End) saves now rather than at the next
                    // heartbeat.
                    if (persistence != null && config.persistHistory()) persistence.scheduleSave();
                    refresh();
                });
    }
}

/**
 * Owns the one GP Manager NavigationButton; install is idempotent, so disable/enable cannot
 * duplicate it, and uninstall always removes exactly what install added.
 *
 * <p>Sidebar navigation and overlay registration have separate lifecycles. The icon loader never
 * returns null, so a missing image cannot silently remove the navigation button.
 */
class NavButton {
    static final Logger log = LoggerFactory.getLogger(NavButton.class);

    interface NavigationHost {
        void add(NavigationButton button);

        void remove(NavigationButton button);
    }

    NavigationButton button;

    /** The runtime sidebar icon; never null so the required button cannot vanish silently. */
    static BufferedImage loadIcon() {
        try (InputStream stream = NavButton.class.getResourceAsStream(msg("profit-manager"))) {
            var image = stream == null ? null : ImageIO.read(stream);
            if (image != null) return image;
            log.warn(msg("gp-manager-icon"));
        } catch (IOException ex) {
            log.warn(msg("gp-manager-icon-resource"), ex);
        }
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    static NavigationHost toolbarHost(ClientToolbar toolbar) {
        if (toolbar == null) return null;
        return new NavigationHost() {
            public void add(NavigationButton navigationButton) {
                toolbar.addNavigation(navigationButton);
            }

            public void remove(NavigationButton navigationButton) {
                toolbar.removeNavigation(navigationButton);
            }
        };
    }

    /**
     * @return true when this call installed the button (host, panel and icon present).
     */
    synchronized boolean install(NavigationHost navigation, PluginPanel panel, BufferedImage icon) {
        if (button != null || navigation == null || panel == null || icon == null) return false;
        NavigationButton next = NavigationButton.builder().tooltip("GP Manager").icon(icon).priority(5).panel(panel)
                        .build();
        navigation.add(next);
        button = next;
        return true;
    }

    synchronized void uninstall(NavigationHost navigation) {
        if (button != null && navigation != null) navigation.remove(button);
        button = null;
    }

    synchronized NavigationButton button() {
        return button;
    }
}
