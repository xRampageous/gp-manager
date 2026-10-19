package com.gpmanager;

import static net.runelite.api.gameval.InventoryID.*;
import static net.runelite.api.gameval.VarbitID.*;
import static com.gpmanager.ActionSignals.*;
import static com.gpmanager.ChargeCheck.*;
import static net.runelite.api.gameval.VarPlayerID.*;

import static java.util.Collections.*;

import static com.gpmanager.GameData.*;

import static com.gpmanager.Context.*;
import static com.gpmanager.Inbox.*;
import static com.gpmanager.PauseReason.*;
import static com.gpmanager.Phrases.*;

import static net.runelite.api.GameState.*;

import static java.lang.Math.*;

import com.google.gson.Gson;
import com.google.inject.Provides;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.*;
import net.runelite.client.game.*;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

import org.slf4j.*;

import java.io.IOException;
import java.awt.Color;
import java.util.*;

import static com.gpmanager.SafeMath.*;
import java.util.function.*;
import javax.inject.*;
import java.util.regex.Pattern;
import lombok.*;
import static java.util.Locale.ROOT;

@PluginDescriptor(
        name = "GP Manager", internalName = "gp-manager", description =
                "Passive profit tracking: profit per Grind and per hour, a ledger you can correct, all-time totals, PvP results and a loot HUD.",
        tags = {"profit", "gp", "loot", "costs", "supplies", "sessions", "pking", "pk", "hud"})
public class GpManagerPlugin extends Plugin {
    /** Idle ticks a bank, market or loot context stays available. */
    static final int CORRELATION_TICKS = 6;

    enum DeathOutcome {
        TRACK_PK, TRACK_PVM_RECLAIM, UNCLASSIFIED
    }

    static final Logger log = LoggerFactory.getLogger(GpManagerPlugin.class);
    static final int SAVE_INTERVAL_TICKS = 50;

    /** Unchanged profiles save every 500 ticks (5 minutes) instead of every 30 seconds. */
    static final int IDLE_SAVE_TICKS = 500;

    long savedRevision = -1;
    @Inject Client client;
    @Inject Gson gson;
    @Inject GpManagerConfig config;
    @Inject ConfigManager configManager;
    @Inject ContainerSnapshotFactory snapshotFactory;
    @Inject ItemManager itemManager;

    /** Accounting's item lookups; ItemManager itself stays for sprites. */
    @Inject ItemLookup lookup;

    @Inject SpriteManager spriteManager;
    @Inject Engine engine;
    @Inject SessionRepository repository;
    @Inject Persistence persistence;
    @Inject InteractionContextTracker interactionContextTracker;
    @Inject CharacterIdleTracker characterIdleTracker;
    @Inject ActivityDetector activity;
    @Inject ChargeIntake charges;

    /**
     * Single valuation authority: the plugin refreshes its world/economy snapshot on the client
     * thread.
     */
    @Inject Valuation valuation;

    /** The profile whose old rows were last rechecked; null before the first ready tick. */
    String rechecked;

    @Inject LootKeyManifestReader lootKeys;
    @Inject OfferSlots geOfferLedger;
    @Inject PvpContextReader pvp;
    @Inject ClientThread clientThread;
    @Inject OverlayManager overlayManager;

    /** HUD+ overlay and the builder the sidebar feeds each tick; null while not installed. */
    volatile HudBuilder hud;

    HudOverlay hudOverlay;
    @Inject ClientToolbar clientToolbar;

    /**
     * Existing Ground Items preference authority; the sidebar reads its decision, never re-derives
     * it.
     */
    @Inject LootPresentationFilterService lootPresentationFilter;

    /** The restored sidebar, bound as a presentation host; null while presentation is absent. */
    volatile SidebarPanel activityPanel;

    /**
     * Client-thread PvP sample for the sidebar context row. Sampled on the tick (the client thread)
     * so the EDT never calls into the client, and presentation only: nothing here reaches
     * accounting.
     */
    volatile PvpState currentPvp = PvpState.NONE;

    /**
     * Inventory and worn items priced every other tick while a death can be lost; risk reads them.
     */
    List<Flow> held = emptyList();

    /** Read by the sidebar on the EDT; written from startup and game-state events. */
    volatile boolean loggedIn;

    NavButton navigation = new NavButton();

    /**
     * Installs the sidebar and HUD+. When the toolbar is unavailable the plugin keeps tracking
     * without presentation, and R1 acceptance fails because the sidebar is a required product
     * surface.
     */
    void installPresentation() {
        try {
            var panel = new SidebarPanel(engine, config, itemManager, repository, persistence);
            panel.spriteManager = spriteManager;
            GrindsController.bondPrice = () -> bondGp;
            panel.mutationOwner = clientThread::invoke;
            panel.bindPvp(() -> currentPvp);
            panel.bindLoggedIn(() -> loggedIn);
            panel.bindHopping(() -> lastGameState == HOPPING);
            var builder = new HudBuilder(config, (id, quantity) -> itemManager.getImage(id, quantity, false));
            panel.bindHud(builder);
            hudOverlay = new HudOverlay(builder, config);
            overlayManager.add(hudOverlay);
            hud = builder;
            lootPresentationFilter.refresh();
            panel.bindFilter(new RecentFilter() {
                        public boolean isFlowIncluded(Flow flow) {
                            return lootPresentationFilter.isFlowIncluded(flow, config.lootPresentationFilter());
                        }

                        public boolean alwaysShows(Flow flow) {
                            return LootPresentationFilterService.listState(config.alwaysShowItems(), "", flow.itemName,
                                            Math.abs(flow.quantityDelta)) == LootPresentationFilterService.HIGHLIGHTED;
                        }

                        public Color highlight(String name, long quantity) {
                            return lootPresentationFilter.highlight(name, quantity, config.lootPresentationFilter());
                        }

                        public int recentRows() {
                            return config.recentRows();
                        }

                        public String version() {
                            return config.lootPresentationFilter() + "|" + lootPresentationFilter.fingerprint + "|"
                                    + config.alwaysShowItems() + "|" + config.hiddenRecentItems() + "|"
                                    + config.hiddenRecentCharges();
                        }

                        public boolean showRecent(String name, long quantity, boolean charge) {
                            // A charge row answers to both lists; a match in either hides it.
                            String hidden = config.hiddenRecentItems()
                                            + (charge ? "," + config.hiddenRecentCharges() : "");
                            return LootPresentationFilterService.listState("", hidden, name, quantity)
                                    != LootPresentationFilterService.HIDDEN;
                        }

                        public void hideRecent(String name, boolean charge) {
                            String list = charge ? config.hiddenRecentCharges() : config.hiddenRecentItems();
                            // Hiding one dose hides the whole potion (owner 2026-10-16, after
                            // Coin Flow 1.9): "Prayer potion*" covers (1) to (4).
                            String entry = charge || ActionReader.trailingDose(name) > 0
                                            ? LootPresentationFilterService.chargePattern(name) : name;
                            var names = new ArrayList<>(Text.fromCSV(list));
                            if (!names.contains(entry)) names.add(entry);
                            configManager.setConfiguration(GpManagerConfig.GROUP,
                                    charge ? "hiddenRecentCharges" : "hiddenRecentItems", Text.toCSV(names));
                        }
                    });
            activityPanel = panel;
            if (!navigation.install(NavButton.toolbarHost(clientToolbar), panel, NavButton.loadIcon())) {
                log.warn(msg("gp-manager-sidebar"), clientToolbar != null, NavButton.loadIcon() != null);
                removePresentation();
                return;
            }
            log.info(msg("gp-manager-sidebar-button"));
        } catch (RuntimeException | LinkageError ex) {
            log.warn(msg("gp-manager-presentation"), ex);
            removePresentation();
        }
    }

    /** Removes the sidebar button and detaches presentation. */
    void removePresentation() {
        hud = null;
        if (hudOverlay != null) {
            overlayManager.remove(hudOverlay);
            hudOverlay = null;
        }
        activityPanel = null;
        navigation.uninstall(NavButton.toolbarHost(clientToolbar));
    }

    /**
     * Samples the client-thread PvP facts for the sidebar context row. Called from the tick, where
     * client access is legal; the EDT reads the cached sample through {@code axo}.
     */
    void samplePvpState() {
        var local = client.getLocalPlayer();
        int skull = local == null ? SkullIcon.NONE : local.getSkullIcon();
        boolean possible = pvp.context().isDangerous(), skulled = skull != SkullIcon.NONE;
        boolean protect = client.getVarbitValue(PRAYER_PROTECTITEM) == 1;
        if (!possible) held = bagHeld = emptyList();
        else if (client.getTickCount() % 2 == 0 && valuation != null && snapshotFactory != null) {
            held = valuation.value(snapshotFactory.captureDeathHeld(true).quantities);
            bagHeld = valuation.value(lootingBag());
        }
        long bag = 0;
        // A death loses the looting bag whole: nothing in it is ever among the kept items.
        for (Flow stack : bagHeld) bag += PvpState.deathValue(stack) * stack.quantityDelta;
        currentPvp = new PvpState(possible, skulled, protect,
                        possible ? PvpState.risk(held, skulled, protect) + bag : 0);
    }

    List<Flow> bagHeld = emptyList();

    /**
     * What a carried looting bag holds: its contents when the client last showed them, plus the
     * dispenser rewards booked into it since (owner 1.5.1).
     */
    Map<Integer, Long> lootingBag() {
        var pack = client.getItemContainer(INV);
        if (pack == null || !pack.contains(ItemID.LOOTING_BAG) && !pack.contains(ItemID.LOOTING_BAG_OPEN))
            return Map.of();
        // Only fresh container events replace saved stock; a cached client container can be stale.
        return engine.bagStock();
    }

    void rememberBag(ItemContainer bag) {
        var contents = new HashMap<Integer, Long>();
        for (Item item : bag.getItems())
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                contents.merge(item.getId(), (long) item.getQuantity(), Long::sum);
        engine.rememberBag(contents);
    }

    void transactionBooked(Transaction transaction, long now) {
        var builder = hud;
        if (builder != null) {
            builder.tray().big = lootPresentationFilter.big(config.lootPresentationFilter());
            builder.tray().booked(transaction, now, builder.keepSession(), client == null ? now : client.getTickCount());
        }
        refreshPresentation();
    }

    void lootObserved(boolean pending, String source, Collection<ItemStack> items, int multiplicity, long now) {
        var builder = hud;
        String batch = source + ":" + pending + ":" + multiplicity + ":" + lootKey(items);
        // Only reward windows ride the tray (ground loot shows once it is picked up). One reward
        // can arrive through several RuneLite events; show it once.
        // A clue casket's rewards reach the inventory at once and ride the tray as one casket row.
        if (builder != null && pending && items != null && !lower(source).contains("clue")
                && !(batch.equals(lastLootBatch) && now - lastLootAt < 1_200)) {
            lastLootBatch = batch;
            lastLootAt = now;
            for (var item : items) {
                // Named and priced by the one valuation route; nothing is counted from this.
                for (Flow flow : valuation.value(singletonMap(lookup.canon(item.getId()),
                                        (long) item.getQuantity() * max(1, multiplicity)))) {
                    builder.tray().observed(flow.itemId, flow.itemName, flow.quantityDelta, flow.unitPrice, now,
                                    builder.keepSession());
                }
            }
        }
        refreshPresentation();
    }

    void ownerScopeChanged(String scope) {
        var builder = hud;
        if (builder != null) builder.tray().clear();
        var panel = activityPanel;
        if (panel == null) return;
        if (scope == null || "held".equals(scope) || "switching".equals(scope)) panel.suspend();
        else panel.resume();
    }

    void interactionChanged(String name, boolean combat) {
        // Presentation policy: the target never retitles the run; HUD+ shows it as its header,
        // and fighting another NPC starts that NPC's tray streak.
        var builder = hud;
        if (builder != null && !HudTray.minion(name)) {
            builder.interaction(name, combat);
        }
    }

    /** Every-tick signals: live values only, never a full page rebuild. */
    void pulse() {
        var panel = activityPanel;
        if (panel != null) panel.tick();
    }

    int ticksSinceSave, recentPlayerCombatTicks;

    Map<Skill, Integer> lastSkillExperience = new EnumMap<>(Skill.class);
    NeutralZoneTracker neutralZoneTracker = new NeutralZoneTracker();
    int geOfferSeedTicks;

    /** The Blast Furnace coffer's last balance (-1 until read), the tick it changed and unbooked drain. */
    int cofferGp = -1;

    int cofferTick;
    long cofferDrain;

    /**
     * The Blast Furnace coffer drains 72,000 coins an hour, 12 a tick, while you work it (OSRS
     * Wiki). Deposits and withdrawals stay neutral: a drop counts as drain only when it is no more
     * than 12 gp a tick since the last change; anything faster is a withdrawal. Drain books in 1,200 gp batches and
     * on logout (owner 2026-10-16, after Coin Flow 1.9).
     */
    void coffer(int gp) {
        int tick = client.getTickCount();
        cofferDrain += cofferDrain(cofferGp, gp, tick - cofferTick);
        cofferGp = gp;
        cofferTick = tick;
        if (cofferDrain >= 1_200) flushCoffer();
    }

    /**
     * The drain in one coffer change: a drop the coffer could drain in the ticks elapsed (plus one);
     * a bigger drop is a withdrawal, and its few coins of drain are left out.
     */
    static long cofferDrain(int before, int after, int ticks) {
        long drop = before < 0 ? 0L : before - after;
        return drop > 0 && drop <= 12L * (max(1, ticks) + 1) ? drop : 0;
    }

    void flushCoffer() {
        if (cofferDrain > 0) engine.bookCofferDrain(cofferDrain, now());
        cofferDrain = 0;
    }

    /** The tick a private instance was paid from the coffer; its bank share comes that tick. */
    int instancePaidTick = -1;

    /** Rising-edge: clear NPC soft-stick once when bank opens, not every bank tick. */
    boolean bankClearedInteractionThisOpen;

    /** One client-thread initialization when this plugin enable becomes ready. */
    boolean boot;

    /** Owner live 2026-10-13: the login-start option is offered once per client run. */
    boolean firstLoginAutoStart;

    @Override
    protected void startUp() {
        boot = true;
        firstLoginAutoStart = true;
        JsonCodec.bind(gson);
        boolean loggedInAtStart = client.getGameState() == LOGGED_IN;
        loggedIn = loggedInAtStart;
        var identityAtStart = persistence.resolveCurrentIdentity();
        persistence.initAsync(() -> {
                    try {
                        repository.initialize(getPluginDirectory());
                    } catch (IOException ex) {
                        throw new IllegalStateException(msg("unable-initialize-gp"), ex);
                    }
                    persistence.start();
                    if (loggedInAtStart)
                        persistence.switchIdentityAsync(identityAtStart, config.persistHistory());
                });
        // Always sync identity for account isolation; persistHistory only
        // controls durable load/save, not whether accounts may mix.
        if (client.getGameState() == LOGGED_IN) beginGeOfferSeed();
        installPresentation();
        interactionContextTracker.bindPresentation(this::canIngest, this::interactionChanged);
        // startUp runs on the EDT; world type and the inventory baseline are client-thread reads.
        clientThread.invokeLater(() -> {
                    refreshEconomyState();
                    refreshKaramjaGloves();
                    if (canIngest()) initLoggedIn();
                });
        pulse();
    }

    @Override
    protected void shutDown() {
        charges.clearCheck();
        // The durable General tracker and any custom session are saved in
        // place. Shutdown must not turn either into history or merge owners.
        engine.pauseForLifecycle(now());
        persistence.shutdown(config.persistHistory());
        lastSkillExperience.clear();
        interactionContextTracker.clear();
        LiveSnapshot.recentKey = emptyList();
        // Clear the sidebar while the engine is still valid, then remove the button.
        ownerScopeChanged("held");
        interactionContextTracker.bindPresentation(null, null);
        removePresentation();
    }

    GameState lastGameState;

    /**
     * Client-thread world/economy snapshot for automatic market pricing. Ordinary main-game
     * modifiers keep quotes; special economies and an unknown world fail them closed, while manual
     * overrides and face value still work. Never called from persistence/render threads.
     */
    void refreshEconomyState() {
        if (valuation == null) return;
        valuation.setEconomyState(client != null && client.getGameState() == LOGGED_IN
                        ? Valuation.economyFor(client.getWorldType()) : Valuation.Economy.UNKNOWN);
    }

    /** One bond's price, read on the client thread for the target form's preset (owner 1.5.0). */
    volatile long bondGp;

    /** True on separate-economy worlds, where tracking pauses entirely (owner 1.5.0). */
    boolean onUnsupportedWorld() {
        return client != null && Valuation.economyFor(client.getWorldType()) == Valuation.Economy.UNSUPPORTED_SPECIAL;
    }

    /** Reading the exchange or a trade window is not idle (owner 1.5.0). */
    boolean windowHoldsActivity() {
        return Widgets.isGeOpen(client) || Widgets.visible(client, InterfaceID.Trademain.TITLE);
    }

    /** Karamja gloves 1-4 on the hands slot pick the improved tokkul rate. */
    private static final Set<Integer> KARAMJA_GLOVES = Set.of(ItemID.ATJUN_GLOVES_EASY, ItemID.ATJUN_GLOVES_MED,
                    ItemID.ATJUN_GLOVES_HARD, ItemID.ATJUN_GLOVES_ELITE);

    /** Client-thread gloves snapshot; called at login/start and on worn-equipment changes. */
    void refreshKaramjaGloves() {
        if (valuation == null) return;
        var gear = client == null ? null : client.getItemContainer(WORN);
        Item gloves = gear == null ? null : gear.getItem(EquipmentInventorySlot.GLOVES.getSlotIdx());
        valuation.setKaramjaGlovesWorn(gloves != null && KARAMJA_GLOVES.contains(gloves.getId()));
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        var previous = lastGameState;
        lastGameState = event.getGameState();
        if (event.getGameState() == LOGGED_IN) {
            loggedIn = true;
            // Login or world hop: re-read the economy identity from the client thread.
            refreshEconomyState();
            refreshKaramjaGloves();
        } else if (event.getGameState() == LOGIN_SCREEN || event.getGameState() == HOPPING) {
            flushCoffer();
            cofferGp = -1;
            recentPlayerCombatTicks = 0;
            loggedIn = event.getGameState() == HOPPING;
            // No world identity: automatic market quotes fail closed until the next login.
            if (valuation != null) valuation.setEconomyState(Valuation.Economy.UNKNOWN);
        }
        // LOGGED_IN -> LOADING -> LOGGED_IN is a region change (teleport, instance, stairs), not a
        // login. Re-priming the baseline there swallowed whatever changed on the way -- a teleport
        // tablet breaking never booked. Only the location-bound trackers reset on a load.
        if (event.getGameState() == LOADING) {
            clearLocationState();
            return;
        }
        if (event.getGameState() == LOGGED_IN && previous == LOADING) {
            activity.syncSession();
            pulse();
            return;
        }
        if (event.getGameState() != LOGGED_IN) {
            geOfferLedger.beginSeed();
            geOfferSeedTicks = 0;
            charges.resetTransient();
            clearLocationState();
            characterIdleTracker.clear();
        }
        if (event.getGameState() == LOGGED_IN) {
            beginGeOfferSeed();
            persistence.syncIdentityAsync(config::persistHistory);
            pulse();
        } else if (event.getGameState() == LOGIN_SCREEN || event.getGameState() == HOPPING) {
            lastSkillExperience.clear();
            if (persistence.isTrackingReady()) {
                engine.pauseForLifecycle(now());
                persist();
            }
            if (event.getGameState() == LOGIN_SCREEN) persistence.onLogout(config.persistHistory());
        }
    }

    /**
     * A catch or cut's item: "Raw trout" beats cooked "Trout" in any search order (Codex review
     * 1.2).
     */
    static int caughtItem(String name, List<net.runelite.http.api.item.ItemPrice> found) {
        int plain = -1;
        for (var item : found) {
            if (item.getName().equalsIgnoreCase("raw " + name)) return item.getId();
            if (item.getName().equalsIgnoreCase(name)) plain = item.getId();
        }
        return plain;
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event) {
        beginOwnerSwitch();
        refreshEconomyState();
        refreshKaramjaGloves();
        persistence.syncIdentityAsync(config::persistHistory);
        pulse();
    }

    @Subscribe
    public void onAccountHashChanged(AccountHashChanged event) {
        beginOwnerSwitch();
        if (client.getAccountHash() == -1L) {
            persistence.onHashInvalidatedAsync(config::persistHistory);
            syncOwnerScope();
        } else {
            persistence.syncIdentityAsync(config::persistHistory);
        }
        pulse();
    }

    /**
     * Profile/account identity is changing: transient charge/GE/interaction evidence never crosses
     * owners.
     */
    void beginOwnerSwitch() {
        persistence.fence();
        recentPlayerCombatTicks = 0;
        beginGeOfferSeed();
        charges.resetOwner();
        interactionContextTracker.clear();
    }

    /** Region load / logout: location-bound trackers reset; the baseline does not. */
    void clearLocationState() {
        interactionContextTracker.clear();
        neutralZoneTracker.reset();
    }

    void initLoggedIn() {
        if (!canIngest()) {
            pulse();
            return;
        }
        long now = now();
        // Owner live 2026-10-13: an option pulls the automatic start (or the Idle/Recovery
        // resume that gameplay would do) to the first login of this client run; Manual pause
        // and Stop always hold.
        boolean loginAutoStart = firstLoginAutoStart && config.autoStartSession() && config.autoStartOnLogin();
        firstLoginAutoStart = false;
        if (loginAutoStart) engine.autoStart(now);
        // Login restores identity and baseline. Resume LIFECYCLE only (hop/relog) without the
        // option; with it Idle and Recovery come back at the first login too.
        if (engine.getActiveSession() != null) engine.beginPriming();
        if (boot) {
            boot = false;
            // Seed current XP rather than swallowing the first real XP drop after enabling.
            lastSkillExperience.clear();
            for (var skill : Skill.values())
                if (skill != Skill.OVERALL)
                    lastSkillExperience.put(skill, client.getSkillExperience(skill));
            activity.clearProduction(now);
        }
        if (loginAutoStart) {
            engine.resume(now, LIFECYCLE, IDLE, RECOVERY);
        } else {
            engine.resume(now, LIFECYCLE);
        }
        rebaseline();
        activity.syncSession();
        syncOwnerScope();
        pulse();
    }

    /**
     * Login and gameplay resumes trust the loaded containers as the baseline immediately. Priming
     * would otherwise absorb the player's first action (owner 2026-10-01).
     */
    void rebaseline() {
        if (engine.live() && client.getItemContainer(INV) != null) {
            engine.setBaseline(snapshotFactory.capture(config.includeEquipment(), config.includeRunePouch()));
        }
    }

    /** Same-turn owner rebind so a custom start/finish or account switch never lingers one tick. */
    void syncOwnerScope() {
        String scope = persistence != null && persistence.isTrackingReady()
                        ? String.valueOf(System.identityHashCode(engine.getActiveSession())) : "held";
        ownerScopeChanged(scope);
    }

    void beginGeOfferSeed() {
        geOfferLedger.beginSeed();
        // Hide the prior profile's observations until a fresh complete seed arrives.
        // Give RuneLite time to deliver the documented EMPTY-slot replay and
        // the server-backed active offers before transitions become observable.
        geOfferSeedTicks = 3;
    }

    void advanceGeOfferSeed() {
        if (geOfferSeedTicks <= 0 || --geOfferSeedTicks > 0) return;
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        boolean complete = offers != null && (offers.length == 3 || offers.length == 8);
        if (complete) {
            for (int slot = 0; slot < offers.length; slot++) {
                var offer = offers[slot];
                if (offer == null) {
                    complete = false;
                    break;
                }
                geOfferLedger.observe(OfferSlots.Snapshot.fromOffer(slot, offer));
            }
        }
        if (complete) {
            geOfferLedger.finishSeed();
            // Schema-104 custody baseline: resume exact persisted lifecycles, quarantine offers
            // first seen already open, and never replay pre-existing progress as a fill.
            // Progress made while the client was away is taken from the slot once, here.
            engine.seedGeSlots(geOfferLedger.snapshots(), now());
        } else {
            // Do not treat a partial/null slot array as an empty-account baseline.
            // Retry until all eight current slots are observed.
            geOfferSeedTicks = 1;
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (!canIngest()) return;
        int container = event.getContainerId();
        // The bag's own contents now include what the dispenser added.
        if (container == LOOTING_BAG && event.getItemContainer() != null) {
            engine.bagAdded.clear();
            rememberBag(event.getItemContainer());
        }
        boolean inventory = container == INV || container == WORN;
        if (container == WORN) refreshKaramjaGloves();
        if (!inventory) markActivity();
        if (container == BANK) {
            engine.markContext(Context.TRANSFER, CORRELATION_TICKS, "Bank transfer");
        }
        kit(container, event.getItemContainer());
        chestChanged(container, event.getItemContainer());
        if (inventory) {
            // Inventory can fire after the bank widget is gone but before the next
            // GameTick. Close soft transfer evidence first so burial is not latched.
            closeBankIfHidden();
            // GE widget visibility is only a UI hint. Offer transitions are recorded by the
            // ledger and matched by item/direction-scoped evidence in the engine; arming MARKET
            // for every inventory callback while the interface is open would steal unrelated
            // loot, shop, and manual inventory changes.
            // Player trade UI open while invent settles → TRADE (Traded), not GAIN.
            if (Widgets.visible(client, InterfaceID.Trademain.TITLE)) {
                engine.markContext(MARKET, marketTicks(), "Player trade");
            }
            engine.markDirty();
        }
    }

    /** Kits with their own window, by container; RuneLite loads one when its window first opens. */
    static final Map<Integer, String> KITS = byId("kits");

    /**
     * A click inside a kit's window names the item, never the kit, so the kit's contents changing
     * is the storage evidence: taking items out or putting them in is neutral (owner 1.2). The
     * first sighting only loads the kit.
     */
    void kit(int id, ItemContainer container) {
        String kit = KITS.get(id);
        if (kit == null || container == null) return;
        Item[] items = container.getItems();
        Observation old = engine.inbox.peek(KIT_SNAPSHOT, e -> e.item == id);
        Item[] before = old == null ? null : (Item[]) old.data;
        if (old != null) engine.inbox.take(KIT_SNAPSHOT, id, 1, e -> e == old);
        engine.inbox.add(KIT_SNAPSHOT, id, 1, Integer.MAX_VALUE, kit).data = items;
        if (before != null && !Arrays.equals(before, items)) zone(kit);
    }

    /**
     * A tick after a bag pickup, what is still not in the inventory went into the open bag (owner
     * 1.2).
     */
    void bagSettle() {
        for (var e : engine.inbox.all(BAG_GAIN)) {
            if (client.getTickCount() <= e.at) continue;
            long missing = e.held + e.qty - snapshotFactory.capture(false, false).quantityOf(e.item);
            if (missing > 0) engine.bagged(e, min(missing, e.qty));
            engine.inbox.take(BAG_GAIN, e.item, e.qty, v -> v == e);
        }
    }

    /** A one-tick bag observation; the source's inventory baseline bounds a split pickup. */
    void bagGain(int item, long qty, long held) {
        Observation e = engine.inbox.peek(BAG_GAIN, row -> row.item == item);
        if (e != null) { e.qty = safeAdd(e.qty, qty); return; }
        e = engine.inbox.add(BAG_GAIN, item, qty, 5, null);
        e.at = client.getTickCount();
        e.held = held;
    }

    void closeBankIfHidden() {
        if (!Widgets.isBankOpen(client)) engine.bankClosed();
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
        if (event == null || event.getOffer() == null || event.getSlot() < 0) return;
        // The API replays every slot as EMPTY at login, followed by each offer
        // that the server restores. Keep these as baselines; the ledger is
        // observation-only until that seed is finished.
        var snapshot = OfferSlots.Snapshot.fromOffer(event.getSlot(), event.getOffer());
        var observed = geOfferLedger.observe(snapshot);
        if (observed.isPresent()) handleGeOfferTransition(observed.get());
    }

    void handleGeOfferTransition(OfferSlots.Transition transition) {
        int observedItem = transition.current.itemId > 0 || transition.previous == null ? transition.current.itemId
                        : transition.previous.itemId;
        // Observation only: custody owns offer-based classification through its item- and
        // side-scoped offer records. A passive progress or slot transition must never arm
        // MARKET context for whatever inventory change settles next.
        engine.noteGeOffer(transition, observedItem > 0 ? itemName(observedItem) : "", now());
        // Fill progress is evidence only; money is booked from the settled inventory movement.
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        if (event != null && event.getVarbitId() == CHARGES_DIZANAS_QUIVER_BLESSING) {
            if (canIngest()) charges.quiverProgress(event.getValue(), now());
            return;
        }
        if (event != null && event.getVarbitId() == BLAST_FURNACE_COFFER) {
            coffer(event.getValue());
            return;
        }
        // Rune pouch contents live in varbits, not an item container. A change there is
        // an inventory change for accounting: casting from the pouch is a cost, filling
        // it from the inventory nets to zero.
        boolean quiver = event != null && config.includeEquipment()
                && (event.getVarpId() == DIZANAS_QUIVER_TEMP_AMMO
                        || event.getVarpId() == DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
        if (event == null || !quiver && (!config.includeRunePouch()
                                || !ContainerSnapshotFactory.isPouchVarbit(event.getVarbitId()))
                        && event.getVarbitId() != CHARGES_ASH_SANCTIFIER_QUANTITY) {
            return;
        }
        if (!canIngest()) return;
        markActivity();
        closeBankIfHidden();
        engine.markDirty();
    }

    @Subscribe
    public void onGraphicChanged(GraphicChanged event) {
        if (skip(event) || event.getActor() != client.getLocalPlayer()) return;
        charges.spotAnims(event.getActor().getSpotAnims(), now());
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        if (skip(event) || !(event.getActor() instanceof NPC)) return;
        var hitsplat = event.getHitsplat();
        // isMine sees every target of one swing (owner 2026-09-29): a scythe sweep's second
        // and third hits land on NPCs the player never "interacts" with.
        if (hitsplat == null || !hitsplat.isMine()) return;
        charges.onHit(hitsplat.getAmount(), now());
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (skip(event) || event.getActor() != client.getLocalPlayer()) return;
        int animation = event.getActor().getAnimation();
        charges.onAnimation(animation, now());
        if (Spells.CAST_ANIMATIONS.containsKey(animation)) {
            int spell = client.getVarpValue(AUTOCAST_SPELL_OBJ);
            ItemComposition definition = spell > 0 && client.getVarbitValue(AUTOCAST_SET) != 0
                    ? client.getItemDefinition(spell) : null;
            engine.noteCast(ActionLabel.of(definition == null ? null : Spells.named(definition.getName())));
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (skip(event)) return;
        String message = normalize(event.getMessage());
        long now = now();
        // Uncharging Bryophyta's staff destroys its runes; its dialog says how many (recorded
        // 2026-10-09).
        var emptied = pattern("bru").matcher(message);
        if (emptied.matches())
            charges.emptied(parseCount(emptied.group(1)), now);
        // A refused pendant teleport (already at that location) books no charge; its line arrives
        // as a "click here to continue" box (MESBOX), so it is matched before the chat-type gate
        // and the estimate its click made is removed through the announced path (owner live
        // 2026-10-13).
        if (pattern("pendant-refused").matcher(message).matches())
            charges.refusedTeleport(now);
        // The Vyre well is refundable storage for scythe charges (owner live 2026-10-13): filling
        // it with vials and blood runes moves the player's own items, and the scythe's charge
        // estimates book the use. Its line arrives as a message box too.
        if (pattern("vyre-well-fill").matcher(message).matches())
            zone(msg("vyre-well"));
        var type = event.getType();
        if (type != ChatMessageType.SPAM && type != ChatMessageType.GAMEMESSAGE) return;
        // An Agility dispenser award while a looting bag is open: the bag takes what the pack
        // does not show, so the engine books the difference (owner live 2026-10-16).
        var award = pattern("bag-reward").matcher(message);
        var pack = award.matches() ? client.getItemContainer(INV) : null;
        if (pack != null && pack.contains(ItemID.LOOTING_BAG_OPEN)) {
            var items = new HashMap<Integer, Long>();
            bagAward(award.group(1)).forEach((name, quantity) -> {
                                for (var price : lookup.find(name)) {
                                    if (price.getName().equalsIgnoreCase(name)) {
                                        items.merge(price.getId(), quantity, Long::sum);
                                        break;
                                    }
                                }
                            });
            engine.noteBagReward(items, now);
        }
        // Each potion a chug drinks prints its own barrel line (Dude Where's My Stuff, GIM Hub).
        var chug = pattern("chug").matcher(message);
        if (chug.matches()) charges.chug(chug.group(1), now);
        if (charges.observeCheck(message, now)) {
            // Charge Check chat is an explicit measurement, never an activity or title signal.
            return;
        }
        // Any unrelated chat between click and numeric response makes the target
        // association uncertain; the next Check must establish fresh intent.
        charges.clearCheck();
        // The Guardians of the Rift pool's left-click deposit resolves a few ticks after the
        // click, outside the menu's correlation window; its own line marks the custody
        // (owner 2026-10-13).
        if (pattern("deposit-pool").matcher(message).matches()) {
            zone(msg("deposit-pool"));
            return;
        }
        // Fees paid from Death's Coffer or the bank never leave the inventory; the game's own lines
        // name them (research 1.2). "Death charges you" is the whole reclaim fee, so a bank share
        // inside it waits two ticks and is dropped once that line arrives. A private instance is
        // paid coffer first, then the bank for the rest in the same tick (Death Cost Tracker).
        // Every instance line arrives as SPAM, while reclaim bank lines arrive as GAMEMESSAGE,
        // so a lone SPAM bank line with an empty coffer is the bank-paid instance fee. A share
        // settles the same tick as its coffer line, and the pinned later-tick bank line is never
        // a fee, so this branch also requires no coffer line on this tick or the last one.
        var fee = pattern("death-fee").matcher(message);
        var bank = pattern("bank-payment").matcher(message);
        var coffer = pattern("coffer-payment").matcher(message);
        if (fee.matches()) {
            engine.inbox.drop(FEE);
            engine.bookDeathFee(parseCount(fee.group(1)), msg("death-s"), now);
        } else if (bank.matches() && client.getTickCount() == instancePaidTick) {
            engine.bookDeathFee(parseCount(bank.group(1)), msg("instance-fee"), now);
        } else if (bank.matches() && engine.deathReclaim.isAwaitingReclaim()) {
            engine.inbox.put(FEE, -1, parseCount(bank.group(1)), 10, "Reclaim fee (bank)").at =
                    client.getTickCount();
        } else if (bank.matches() && type == ChatMessageType.SPAM && client.getTickCount() - instancePaidTick > 1) {
            engine.bookDeathFee(parseCount(bank.group(1)), msg("instance-fee-bank"), now);
        } else if (coffer.matches()) {
            instancePaidTick = client.getTickCount();
            engine.bookDeathFee(parseCount(coffer.group(1)), msg("instance-fee"), now);
        }
        var caught = pattern("bag").matcher(message);
        if (caught.matches() && lookup != null && engine.baseline != null && snapshotFactory.bagOpen()) {
            // A catch or a cut an open barrel or basket took never reaches the inventory (owner
            // 1.2).
            int found = caughtItem(caught.group(2), lookup.find(caught.group(2)));
            if (found >= 0) {
                bagGain(found, caught.group(1) == null ? 1 : Long.parseLong(caught.group(1)),
                        engine.baseline.quantityOf(found));
            }
        }
        if (lookup != null && engine.baseline != null) {
            for (String[] rule : cachedRows("gem_messages")) {
                var gem = pattern(rule[0]).matcher(message);
                if (!gem.matches()) continue;
                String name = "Uncut " + gem.group(Integer.parseInt(rule[1]));
                int id = caughtItem(name, lookup.find(name));
                boolean direct = Boolean.parseBoolean(rule[3]);
                if (id < 0 || !snapshotFactory.gemBag(id, !direct)) break;
                try {
                    long qty = "0".equals(rule[2]) ? 1
                            : parseCount(gem.group(Integer.parseInt(rule[2])));
                    if (direct) engine.bagged(id, qty);
                    else bagGain(id, qty, snapshotFactory.capture(false, false).quantityOf(id));
                } catch (NumberFormatException malformed) { }
                break;
            }
        }
        if (message.contains("accepted trade")) {
            // Trade completed chat — keep MARKET armed through inventory settle.
            engine.markContext(MARKET, marketTicks(), "Player trade");
            markActivity();
            return;
        }
        if (message.contains("too impure")) {
            // A failed iron smelt gives no XP but is still part of the smelting run.
            activity.continueProduction("Smelting", now);
            return;
        }
        if (isSpared(message)) {
            // Chaos altar bone-save is not a spend — inventory may keep the bone.
            markActivity();
            return;
        }
        if (!isChatSpend(message)) return;
        // Live bury/drink often animate after the menu click; chat confirms the spend
        // and keeps open intent alive until inventory stabilizes (incl. coalesced picks).
        engine.extendConsume(consumeTicks(), ActionReader.verbOf(message));
        markActivity();
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (skip(event) || event.isConsumed()) return;
        String option = normalize(event.getMenuOption());
        if ("bank-all".equals(option)) bankAll(event.getParam1() >>> 16);
        boolean consumption = isSpend(option), gather = namesActivityOnly(option);
        if (gather || consumption) {
            // A genuine action can produce an item without XP or an animation
            // (field crops). Capture its pre-action inventory on automatic start.
            markGameplayActivity(true);
        }
        interactionContextTracker.onMenuOptionClicked(event);
        if (worldAction(event.getMenuAction(), event.isItemOp())) markActivity();
        String target = normalize(event.getMenuTarget()), joined = option + " " + target;
        int identifier = menuItemId(event), ttl = consumeTicks();
        boolean ge = Widgets.isGeOpen(client);
        engine.observeChest(option, target);
        charges.onMenuOptionClicked(event, option, target);
        // A loss-only "Offer" click with the Grand Exchange open is a sell placement: the
        // inventory removal is GE evidence, not a prayer/altar spend. The proven GE UI context
        // disambiguates the shared "Offer" verb; a generic Offer elsewhere keeps its spend intent.
        if (consumption && "offer".equals(option) && ge) {
            trading(CORRELATION_TICKS, msg("grand-exchange-offer") + (target.isEmpty() ? "" : " " + target));
            return;
        }
        // A Collect interaction with the open Grand Exchange is bounded collection evidence. It
        // never owns a movement by itself; it lets exact custody settlements correlate.
        if (option.startsWith("collect") && ge) {
            engine.noteGeCollect(now());
            trading(CORRELATION_TICKS, msg("grand-exchange-collect"));
            return;
        }
        if ("revert".equals(option) || "dismantle".equals(option))
            engine.dismantle(identifier);
        if (target.contains("multicannon") || target.startsWith("cannon ")) {
            // A dwarf multicannon (OSRS Wiki): Fire or Load takes balls from the pack, Set-up
            // places its parts and auto-loads the balls it finds with them, and using a ball on
            // it loads too. Every one of those ball moves is a load (owner 1.3.4 live: the
            // set-up and use loads were unarmed, so their Empty returns booked as gains).
            if ("fire".equals(option) || option.startsWith("load")) engine.loadCannon(ttl);
            else if ("set-up".equals(option)) {
                engine.moveCannon();
                // The auto-load follows the ~12-tick assembly, so arm it for the same span as
                // the parts custody (owner live 2026-10-13).
                engine.loadCannon(40);
            } else if ("pick-up".equals(option)) engine.moveCannon();
            else if ("use".equals(option)) engine.loadCannon(ttl);
        }
        if (target.contains("vyre well") && "empty".equals(option)) {
            // The filled Vyre well refunds its vials of blood and blood runes through its Empty
            // option; that return is the player's own storage coming back, never income
            // (owner live 2026-10-13).
            zone(msg("vyre-well"));
        }
        if (consumption) {
            // Always note intent — even when item id is unresolved (open intent). Spend is
            // confirmed on stable inventory removal / dose leftover; the verb lets the settled
            // row say Drank/Ate/Buried honestly. Exact spell text is optional presentation
            // evidence and never changes matching, type, counted state or value.
            engine.noteConsume(identifier, ttl, false, ActionReader.verbOf(option), "break".equals(option)
                            ? ActionLabel.of(Spells.named(target)) : spellLabel(event, option));
            if (event.getMenuAction() != null && event.getMenuAction().name().startsWith("WIDGET_TARGET_ON")) {
                var spell = spellLabel(event, option);
                if (spell != null) engine.inbox.put(MANUAL_SPELL, -1, 1, 2, null).label = spell;
            }
        } else if ("drop".equals(option)) {
            // Match consume TTL so dump-then-settle does not miss power-train drops.
            engine.noteDrop(identifier, ttl);
        } else if ("take".equals(option) && event.getMenuAction() != null
                && event.getMenuAction().name().startsWith("GROUND_ITEM_")) {
            // A ground item's id is the entry's identifier; a pickup of fired ammo is recovery.
            int id = snapshotFactory.canonical(event.getId());
            var e = engine.inbox.put(GROUND_CLICK, id, 1, 5, null);
            e.at = client.getTickCount();
            e.x = event.getParam0();
            e.y = event.getParam1();
            e.held = snapshotFactory.capture(false, false).quantityOf(id);
        } else if ("destroy".equals(option)) {
            // Irreversible loss — consume-style intent, never recoverable own-drop.
            engine.noteConsume(identifier, ttl, true);
        }
        // Use Tinderbox ↔ Logs (inventory Use): a spend intent for the fuel, same as Light.
        if (lightsFire(option, target)) {
            engine.noteConsume(identifier, ttl, false, ActionKind.SUPPLIES);
        }
        // Use bones/ashes ↔ altar (PoH / wildy): an offer spend intent; Prayer XP confirms it.
        if (offersBones(option, target) || isOffering(option, target)) {
            engine.noteConsume(identifier, ttl, false, ActionKind.OFFER);
        }
        // Agility dispensers (Wilderness course rewards, Brimhaven tickets): the claim keeps the
        // course title; the rewards book as ordinary inventory gains.
        if (AgilityCourses.isDispenser(target)) {
            var local = client.getLocalPlayer();
            String course = local == null || local.getWorldLocation() == null ? null
                            : AgilityCourses.courseName(local.getWorldLocation().getRegionID());
            activity.observe(course == null ? "Agility" : course, true);
        }
        // Farming / gather object actions: their skill's first XP names the activity (accounting is
        // inventory GAIN).
        if (gather) {
            activity.gatherSkill = gatherActivity(option);
        } else if (!consumption) {
            // Transform/production options (station or inventory) arm PRODUCTION context so the
            // paired receipt settles without double count; XP re-arms it inside the window.
            String skill = transformSkill(option);
            if (!skill.isEmpty()) activity.armProduction(skill);
            // Item on item (a knife on logs, a herb into a vial) starts a Make-X the space bar
            // confirms
            // with no click, and its Make button may come a minute later: the run's own skill XP
            // arms it
            // (owner 2026-10-08, fletching went to Review).
            else if (event.getMenuAction() == MenuAction.WIDGET_TARGET_ON_WIDGET
                    || option.startsWith("make")) activity.clearProduction(now());
        }
        var retrieval = BossRetrievalCatalogue.serviceFor(option, target);
        if (retrieval != null) {
            // Long window: dialogue + confirm + coins + items can take several ticks.
            boolean armed = engine.noteReclaimIntent(retrieval, max(CORRELATION_TICKS * 4, stableTicks(30)));
            if (armed) {
                activity.observe("Death reclaim", true);
                return;
            }
        }
        // Clicks inside the store name the tool ("Withdraw-1 Rake"), never the leprechaun, so the
        // open store window is the storage evidence, as the bank window is below.
        if (Widgets.isToolStoreOpen(client)) {
            zone(msg("tool-leprechaun"));
            return;
        }
        // Other stores the same way (Elnock, the seed vault, a cargo hold, Death's coffer, item
        // sets...):
        // the open window, or Quick-withdraw on Elnock (owner 2026-10-08).
        String store = Widgets.store(client);
        if (store != null || "quick-withdraw".equals(option) && target.contains("elnock")) {
            zone(store == null ? "Elnock's storage" : store);
            return;
        }
        // "Deposit" is also ordinary menu vocabulary on unrelated objects. The menu string
        // alone is not ownership evidence; require the live bank/deposit-box interface before
        // treating a bank action as a hard neutral transfer. Direct withdraw clicks latch too:
        // the bank can close before its inventory change stabilizes or its BANK callback arrives.
        // Withdraw-X only opens a quantity prompt; its BANK callback proves the actual movement.
        if (Widgets.isBankOpen(client) && (has(joined, "bank", "deposit")
                        || option.startsWith("withdraw") && !"withdraw-x".equals(option))) {
            if (joined.contains("deposit box")) {
                zone(msg("deposit-box-transfer-2"));
            } else {
                engine.markContext(Context.TRANSFER, CORRELATION_TICKS, msg("bank-deposit"));
            }
            return;
        }
        String neutral = NeutralTransfers.storage(joined);
        if (neutral == null) neutral = NeutralTransfers.storage(target);
        if (neutral != null) {
            zone(neutral);
            return;
        }
        String containerFamily = UtilityContainerCatalogue.familyFor(target);
        if (containerFamily != null && UtilityContainerCatalogue.isCustodyOption(option)) {
            boolean depositBox = joined.contains("deposit box");
            var kind = UtilityContainerCatalogue.classify(option, depositBox);
            if (UtilityContainerCatalogue.isNeutral(kind)) {
                String note = "Container " + kind.name().toLowerCase(ROOT) + " (" + containerFamily + ")";
                if (joined.contains("plank sack")) note = msg("plank-sack");
                engine.markContext(Context.TRANSFER, CORRELATION_TICKS, note);
            }
            return;
        }
        // A menu click alone cannot establish that a key was destroyed or dropped.
        // Its settled inventory loss is handled by the ordinary evidence path.
        if (("destroy".equals(option) || "drop".equals(option)) && target.contains("loot key"))
            return;
        String minigame = NeutralTransfers.minigame(joined, regionId());
        if (minigame != null) {
            zone(minigame);
            return;
        }
        if (option.startsWith("buy") || option.startsWith("sell") || option.startsWith("collect")) {
            trading(CORRELATION_TICKS, (ge ? "Grand Exchange " : "") + option + (target.isEmpty() ? "" : " " + target));
            return;
        }
        // Player–player trade: arm MARKET so settle classifies as TRADE → Traded flash.
        // Ownership-neutral peer exchange — no GE tax (note must include "player trade").
        if (option.contains("accept trade") || option.startsWith("trade with")) {
            trading(marketTicks(), "Player trade");
        }
    }

    /** A GE or trade click: Trading activity and the MARKET context its inventory settle needs. */
    void trading(int ticks, String note) {
        activity.observe("Trading", true);
        engine.markContext(MARKET, ticks, note);
    }

    /**
     * Consume/drop/altar intent lifetime: animations outlast correlation, plus a coalesced settle
     * window.
     */
    /** The configured stabilization ticks plus {@code a}. */
    int stableTicks(int extra) {
        return config.stabilizationTicks() + extra;
    }

    /** Marks a neutral storage or minigame zone for the usual correlation window. */
    void zone(String name) {
        engine.markTransfer(name, CORRELATION_TICKS);
    }

    int consumeTicks() {
        return max(CORRELATION_TICKS * 2, stableTicks(16));
    }

    /** MARKET context lifetime for GE / player-trade inventory settlement. */
    int marketTicks() {
        return max(CORRELATION_TICKS * 2, stableTicks(8));
    }

    int lootMemoryTicks() {
        return 50; // an NPC drop stays matchable for 30 s
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        if (!canIngest()) return;
        markGameplayActivity();
        if (engine.getActiveSession() == null) return;
        String npc = event.getNpc() == null ? "NPC loot" : event.getNpc().getName();
        if (blank(npc)) npc = "NPC loot";
        var builder = hud;
        if (!HudTray.minion(npc)) {
            activity.observe(npc, true);
            engine.recordAction(npc);
            if (builder != null)
                builder.tray().kill(npc, now(), builder.keepSession());
        }
        engine.markLoot(expectedLoot(event.getItems()), lootMemoryTicks(), "Loot from " + npc, npc);
        offerReward(false, npc, event.getItems(), 1);
    }

    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event) {
        if (skip(event)) return;
        String npc = event.getComposition() == null ? "NPC loot" : event.getComposition().getName();
        if (blank(npc)) npc = "NPC loot";
        offerReward(false, npc, event.getItems(), 1);
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        // Optional: only fires when Loot Tracker is enabled. Deduped against
        // NpcLootReceived / ServerNpcLoot for the same kill.
        if (skip(event) || event.getItems() == null) return;
        String name = nonBlank(event.getName(), "Reward");
        boolean pending = event.getType() == LootRecordType.EVENT && RewardChestCatalogue.isRewardChest(name);
        offerReward(pending, name, event.getItems(), max(1, event.getAmount()));
    }

    @Subscribe
    public void onPlayerLootReceived(PlayerLootReceived event) {
        if (!canIngest()) return;
        markGameplayActivity();
        // A safe arena kill (LMS, Castle Wars, PvP Arena) is never a dangerous-PvP encounter.
        if (pvp.sample(client.getLocalPlayer()).isSafe()) return;
        activity.observe("PKing", true);
        if (!config.enablePkTracking()) return;
        // PvP keeps your own results only; opponent names are never stored (SIDEBAR_SPEC.md §6b).
        engine.markPkLoot(expectedLoot(event.getItems()), lootMemoryTicks(), now());
        persist();
        recentPlayerCombatTicks = 0;
    }

    @Subscribe
    public void onInteractingChanged(InteractingChanged event) {
        if (event.getSource() == client.getLocalPlayer()) {
            if (event.getTarget() instanceof NPC) {
                // Use the existing authorized lifecycle for an actual interaction, never a menu
                // hover.
                markGameplayActivity();
            }
            interactionContextTracker.onInteractingChanged(event);
        }
        if (!canIngest() || client.getLocalPlayer() == null) return;
        boolean localToPlayer = event.getSource() == client.getLocalPlayer() && event.getTarget() instanceof Player;
        boolean playerToLocal = event.getTarget() == client.getLocalPlayer() && event.getSource() instanceof Player;
        if (localToPlayer || playerToLocal) {
            // Keep short-lived evidence for death-safety classification even
            // when PK accounting is disabled. That evidence prevents a lingering
            // NPC target from turning a PvP death into an ownership-neutral reclaim.
            recentPlayerCombatTicks = 20;
            if (config.enablePkTracking() && !pvp.context().isSafe()) {
                markActivity();
                activity.observe("PKing", true);
            }
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        var player = client.getLocalPlayer();
        // A staged death in the Gauntlet or the PvP tutorial loses nothing (owner 2026-10-09).
        if (!canIngest() || player == null || event.getActor() != player || neutralZoneTracker.isInside()
                || MinigameRegionHints.isPvpTutorial(regionId())) {
            return;
        }
        engine.bagAdded.clear();
        engine.rememberBag(null);
        // Preserve the pre-death held-key evidence before any disposition branch.
        // A later stabilized key loss can then close its audit row without treating
        // the unowned manifest as a loss.
        engine.beginKeyDeathSettle(KeyClaims.keyQuantities(client.getItemContainer(INV)));
        // Ownership input: this canonical INV/WORN read is intersected with settled negative
        // item flows by the engine.
        ContainerSnapshot deathHeldItems = snapshotFactory == null ? null
                        : snapshotFactory.captureDeathHeld(config.includeEquipment());
        // Only a confident dangerous context plus recent player combat is a PvP death; a
        // safe arena never is; elsewhere require NPC context before the reclaim window.
        boolean pvmContext = interactionContextTracker != null && interactionContextTracker.hasPvmContext();
        DeathOutcome disposition = deathOutcome(config.enablePkTracking(), recentPlayerCombatTicks, pvmContext,
                        pvp.sample(player));
        // PvM or PK: drop the NPC/activity soft-stick so the killer cannot title the next owner.
        interactionContextTracker.clear();
        characterIdleTracker.clear();
        activity.clearOnDeath();
        refreshPresentation();
        if (disposition == DeathOutcome.TRACK_PK) {
            markActivity();
            activity.observe("PKing", true);
            engine.markPkDeath(now());
            persist();
            recentPlayerCombatTicks = 0;
            return;
        }
        if (disposition == DeathOutcome.UNCLASSIFIED) {
            // Missing PvP evidence alone cannot prove this was a PvM death. Let the
            // inventory delta use ordinary loss classification rather than hiding a loss.
            engine.markUnknownDeath();
            markActivity();
            recentPlayerCombatTicks = 0;
            return;
        }
        // PvM death: gear moves to a gravestone / Item Retrieval Service the player
        // still owns. The wipe is an ownership-neutral transfer; the reclaim window
        // later returns the items and books observed coins as the fee.
        markActivity();
        engine.markPvmDeath(consumeTicks(), deathHeldItems == null ? null : deathHeldItems.quantities);
        // Persist the physical death whitelist immediately. A restart before the first
        // inventory wipe must still be able to classify the later loss as a transfer.
        persist();
    }

    static DeathOutcome deathOutcome(boolean pkTrackingEnabled, int playerCombatTicks, boolean pvmContext,
            PvpContext context) {
        if (context.isSafe()) {
            // Safe arenas restore gear; never a PK death, never a reclaim.
            return DeathOutcome.UNCLASSIFIED;
        }
        if (playerCombatTicks > 0) {
            // Player-combat evidence always blocks an unsafe PvM reclaim classification. It
            // books a PK death only with PK accounting on and a confident dangerous context;
            // otherwise the inventory loss follows ordinary accounting.
            return pkTrackingEnabled && context.isDangerous() ? DeathOutcome.TRACK_PK : DeathOutcome.UNCLASSIFIED;
        }
        if (!context.isDangerous()) return DeathOutcome.TRACK_PVM_RECLAIM;
        return pvmContext ? DeathOutcome.TRACK_PVM_RECLAIM : DeathOutcome.UNCLASSIFIED;
    }

    Map<Integer, Long> expectedLoot(Collection<ItemStack> items) {
        var expected = new HashMap<Integer, Long>();
        for (var item : items) {
            if (item != null && item.getId() >= 0 && item.getQuantity() > 0) {
                expected.merge(lookup.canon(item.getId()), (long) item.getQuantity(), Long::sum);
            }
        }
        return expected;
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        Skill skill = event.getSkill();
        if (skill == null) return;
        Integer previous = lastSkillExperience.put(skill, event.getXp());
        if (previous == null || event.getXp() <= previous || !canIngest()) return;
        markGameplayActivity();
        characterIdleTracker.noteSkillingXp();
        long now = now();
        // Agility is an activity in its own right: the course names it when the region is known.
        var local = client.getLocalPlayer();
        String course = skill != Skill.AGILITY || local == null || local.getWorldLocation() == null ? null
                        : AgilityCourses.courseName(local.getWorldLocation().getRegionID());
        activity.observeXp(skill.getName(), course, interactionContextTracker.hasPvmContext(), now);
        var builder = hud;
        if (builder != null) builder.tray().xp(skill.getName(), now);
        charges.learnEyeCast(skill, client.getTickCount(), now);
    }

    /**
     * The last Take: item, tick and how many the inventory held then; a bag pickup waits a tick.
     */
    /** Reward chest interface → its item container (d22), and the last Bank-all tick. */
    static final Map<Integer, Integer> CHESTS = new HashMap<>();

    static {
        for (String[] row : rows("bank_all_chests"))
            CHESTS.put(Integer.parseInt(row[0]), Integer.parseInt(row[1]));
    }

    /**
     * "Bank-all" on a reward chest banks loot that never reaches the inventory; it is booked like a
     * pickup, once per click (research 1.2).
     */
    void bankAll(int chestInterface) {
        Integer chest = CHESTS.get(chestInterface);
        var loot = chest == null ? null : client.getItemContainer(chest);
        if (loot == null) return;
        engine.inbox.drop(CHEST);
        var e = engine.inbox.add(CHEST, -1, 1, 10, null);
        e.x = chest;
        e.at = client.getTickCount();
        chestSnapshot(counts(loot), e.until);
    }

    /**
     * After a Bank-all, only what really leaves the chest is booked, once: a refused or repeated
     * click moves nothing (Codex research 1.2).
     */
    void chestChanged(int id, ItemContainer loot) {
        Observation e = engine.inbox.peek(CHEST, v -> v.item == -1 && v.x == id);
        if (e == null || e.owner != engine.inbox.owner || loot == null || client.getTickCount() - e.at > 10) return;
        var now = counts(loot);
        for (var event : new ArrayList<>(engine.inbox.open)) {
            if (event.kind != CHEST || event.item < 0 || event.owner != engine.inbox.owner) continue;
            long gone = event.qty - now.getOrDefault(event.item, 0L);
            if (gone > 0) engine.bagged(event, gone);
        }
        int until = e.until;
        engine.inbox.open.removeIf(v -> v.kind == CHEST && v.item >= 0);
        chestSnapshot(now, until);
    }

    /** Each measured chest stack is quantity proof within the clicked chest's window. */
    void chestSnapshot(Map<Integer, Long> counts, int until) {
        counts.forEach((item, qty) -> engine.inbox.add(CHEST, item, qty, until - engine.inbox.now, null));
    }

    Map<Integer, Long> counts(ItemContainer container) {
        var counts = new HashMap<Integer, Long>();
        for (Item item : container.getItems()) {
            if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
                counts.merge(snapshotFactory.canonical(item.getId()), (long) item.getQuantity(), Long::sum);
        }
        return counts;
    }

    /** The taken item vanished beside you while an open bag was carried: it may be in the bag. */
    @Subscribe
    public void onItemDespawned(ItemDespawned event) {
        var local = client.getLocalPlayer();
        var item = event.getItem();
        Point at = event.getTile() == null ? null : event.getTile().getSceneLocation();
        var e = engine.inbox.peek(GROUND_CLICK);
        if (!canIngest() || e == null || e.owner != engine.inbox.owner || local == null || item == null || at == null
                || at.getX() != e.x || at.getY() != e.y || snapshotFactory.canonical(item.getId()) != e.item
                || client.getTickCount() - e.at > 5
                || local.getWorldLocation().distanceTo(event.getTile().getWorldLocation()) > 1
                || item.getOwnership() != TileItem.OWNERSHIP_SELF) return;
        if (!snapshotFactory.bagOpen()) {
            // The game's ammo-picking setting can put picked-up darts straight into the blowpipe
            // (OSRS Wiki). Decide two ticks later, once the pack has had its chance to gain them.
            if (e.item == charges.blowpipeDart && engine.inbox.take(GROUND_CLICK, e.item, 1, v -> v == e) > 0) {
                var pick = engine.inbox.add(PIPE_PICKUP, e.item, item.getQuantity(), 5, null);
                pick.at = client.getTickCount();
                pick.held = e.held;
            }
            return;
        }
        long qty = engine.inbox.reserve(PRIVATE_LOOT, e.item, item.getQuantity(), LOOT_RESERVE);
        if (qty > 0 && engine.inbox.take(GROUND_CLICK, e.item, 1, v -> v == e) > 0)
            bagGain(e.item, qty, e.held);
    }

    /** A dart pickup the pack never gained went into the blowpipe: it recovers the shot. */
    void pipePickups() {
        for (var pick : engine.inbox.all(PIPE_PICKUP)) {
            if (pick.owner != engine.inbox.owner || client.getTickCount() - pick.at < 2) continue;
            long quantity = engine.inbox.take(PIPE_PICKUP, pick.item, pick.qty, v -> v == pick);
            if (snapshotFactory.capture(false, false).quantityOf(pick.item) <= pick.held)
                engine.pickUpIntoPipe(pick.item, quantity, now());
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (!canIngest()) {
            // A fenced account (login, switch, restore) drops pending bag evidence untouched (Codex
            // review 1.2).
            engine.inbox.drop(BAG_GAIN, FEE);
            // The identity-ready flag waits for a ready tick below; consuming it here lost ve()
            // (1.3.1).
            if (client.getGameState() == LOGGED_IN) pulse();
            return;
        }
        if (persistence.consumeReady()) initLoggedIn();
        if (!engine.profileIdentityKey.equals(rechecked)
                && engine.valuationService.value(Map.of(ItemID.NATURERUNE, 1L)).stream()
                        .anyMatch(flow -> flow.valueDelta > 0)) {
            // Each loaded profile's old rows take today's rules once, after prices load, so an item
            // with no quote is told apart from one not loaded yet; item definitions are read here,
            // on the client thread (owner live 2026-10-16).
            rechecked = engine.profileIdentityKey;
            engine.recheck(id -> {
                        var item = client.getItemDefinition(id);
                        return item == null ? 0 : item.getHaPrice();
                    });
        }
        bondGp = engine.valuationService.value(Map.of(ItemID.OSRS_BOND, 1L)).stream().mapToLong(f -> f.valueDelta).sum();
        // Separate-economy worlds pause tracking entirely so their time and loot never dilute the
        // main session; leaving resumes like a relog and re-baselines (owner 1.5.0).
        if (onUnsupportedWorld()) {
            boolean entering = !engine.separateWorld;
            engine.pauseForWorld(now());
            if (entering) pulse();
            return;
        }
        if (engine.separateWorld) {
            engine.separateWorld = false;
            if (engine.resume(now(), WORLD)) rebaseline();
        }
        // Any ironman account type (iron, ultimate, hardcore, group) reads 1-6 (OSRS Wiki, RuneLite
        // gameval).
        valuation.alch = config.ironmanAlch() && client.getVarbitValue(IRONMAN) > 0;
        var fee = engine.inbox.peek(FEE);
        if (fee != null && fee.owner == engine.inbox.owner && client.getTickCount() - fee.at > 2) {
            engine.fee(fee, now());
        }
        bagSettle();
        pipePickups();
        engine.tickAutoStart(now());
        engine.tickMilestone(now());
        advanceGeOfferSeed();
        activity.syncSession();
        boolean bankOpen = Widgets.isBankOpen(client);
        var player = client.getLocalPlayer();
        pvp.onGameTick(player);
        samplePvpState();
        var tile = player == null ? null : player.getWorldLocation();
        // An instance's real map region, as the Gauntlet and arena ids are (owner 2026-10-09).
        if (tile != null) applyNeutralZone(regionId());
        servantTalk();
        charges.drank(now());
        // An NPC conversation or its options on screen, or a shop window: a coins-for-items
        // change is a purchase, an items-for-coins change a sale (owner 1.3.4).
        if (Widgets.visible(client, InterfaceID.ChatLeft.UNIVERSE)
                || Widgets.visible(client, InterfaceID.Chatmenu.UNIVERSE) || Widgets.isShopOpen(client)) {
            engine.inbox.put(DIALOGUE, 0, 1, stableTicks(4), null);
        }
        boolean activeAnimation = player != null && player.getAnimation() != -1;
        boolean activeInteraction = player != null && player.getInteracting() != null;
        // Moving is not AFK: it holds off and lifts AFK pause, but never starts tracking (owner 1.5.0).
        boolean moved = tile != null && lastTile != null && !tile.equals(lastTile);
        lastTile = tile;
        int afk = config.afkPauseMinutes() * 100;
        if (activeAnimation || activeInteraction) markGameplayActivity();
        else if (moved) markActivity();
        else idleTick(afk);
        interactionContextTracker.onGameTick();
        if (bankOpen) {
            engine.bankOpened(max(CORRELATION_TICKS, stableTicks(2)));
            // Rising edge only — Banking ends combat soft-stick so Dark wizard
            // cannot reappear the moment the bank closes.
            if (!bankClearedInteractionThisOpen) {
                interactionContextTracker.clear();
                bankClearedInteractionThisOpen = true;
            }
        } else {
            bankClearedInteractionThisOpen = false;
            engine.bankClosed();
        }
        // Character idle drops a leftover NPC hint so leaving idle does not revive the last kill;
        // reading the exchange or a trade window is not idle (owner 1.5.0). It waits the Activity
        // label timeout, so the NPC's name outlasts the gap between kills (owner live 1.5.2).
        characterIdleTracker.model.delayMillis = idleDelay(config);
        if (characterIdleTracker.onGameTick() && config.autoActivityDetection() && !windowHoldsActivity()
                && !"General".equalsIgnoreCase(activity.detectedActivity)) {
            activity.resetToGeneral(true);
        }
        pulse();
        if (client.getLocalPlayer() != null && client.getLocalPlayer().getInteracting() instanceof Player) {
            // Internal death-safety evidence only. PK transaction and activity
            // accounting still obeys enablePkTracking.
            recentPlayerCombatTicks = 20;
        } else if (recentPlayerCombatTicks > 0) {
            recentPlayerCombatTicks--;
        }
        long now = now();
        if (engine.maintainRetention(now) > 0) {
            ticksSinceSave = 0;
            persist();
        }
        engine.tickKeyClaims();
        if (engine.tickGe(now)) {
            ticksSinceSave = 0;
            persist();
        }
        lootKeys.poll();
        charges.watchReturn();
        Transaction transaction = engine.processIfDirty(snapshotFactory.capture(
                                config.includeEquipment(), config.includeRunePouch()), now, row -> {
                            activity.observeTransaction(row);
                            transactionBooked(row, now);
                        });
        if (transaction != null) {
            markActivity();
            activity.observeTransaction(transaction);
            transactionBooked(transaction, now);
            // A burst of receipts saves once within 10 ticks, not per receipt: each save copies the
            // whole
            // profile under the engine lock (release pass 2026-09-28). Deaths and logout still save
            // at once.
            ticksSinceSave = max(ticksSinceSave, SAVE_INTERVAL_TICKS - 10);
        }
        if (saveDue(++ticksSinceSave, engine.getRevision(), savedRevision)) {
            ticksSinceSave = 0;
            persist(true);
        }
        pulse();
    }

    /**
     * A save serializes the whole profile on the client thread. With nothing booked since the last
     * one, only a slow heartbeat remains: crash recovery pauses a running session at it.
     */
    static boolean saveDue(int ticksSinceSave, long revision, long savedRevision) {
        return ticksSinceSave >= SAVE_INTERVAL_TICKS
                && (revision != savedRevision || ticksSinceSave >= IDLE_SAVE_TICKS);
    }

    /**
     * Gauntlet-style instances store gear on entry and restore it on exit with no exit click. Keep
     * an ownership-neutral transfer window open every tick inside, and one closing window after
     * leaving so the restore settles as a transfer too.
     */
    void applyNeutralZone(int region) {
        // Pete Kayer's PvP tutorial lends gear and supplies inside and takes them back as you
        // leave:
        // nothing there, or just after, is profit or loss (owner 2026-10-09, coinflow 1.6.0).
        if (MinigameRegionHints.isPvpTutorial(region))
            engine.markTransfer("PvP tutorial", stableTicks(8));
        var signal = neutralZoneTracker.onRegion(region);
        if (signal == NeutralZoneTracker.Signal.ENTERED) {
            engine.beginNeutralZone();
            engine.markTransfer(msg("neutral-zone-instance-4"), stableTicks(4));
        } else if (signal == NeutralZoneTracker.Signal.INSIDE) {
            engine.markTransfer(msg("neutral-zone-instance-5"), stableTicks(4));
        } else if (signal == NeutralZoneTracker.Signal.LEFT) {
            engine.endNeutralZone(consumeTicks());
        }
    }

    /**
     * Owner 2026-10-09: while a house servant talks (taking items, or back from the bank or the
     * sawmill) his item moves are neutral. Every house is an instance, so a castle's cook is never
     * him. His own line names a sawmill trip ("the sawmill's fee", not "the bank or the sawmill").
     */
    void servantTalk() {
        if (!pattern("servant").matcher(normalize(Widgets.text(client, InterfaceID.ChatLeft.NAME))).matches()
                || !client.getTopLevelWorldView().isInstance()) return;
        String line = normalize(Widgets.text(client, InterfaceID.ChatLeft.TEXT));
        engine.inbox.put(SERVANT, 0, 1, stableTicks(4), null);
        if (line.contains("sawmill") && !line.contains("bank"))
            engine.inbox.put(SAWMILL, 0, 1, 200, null);
    }

    int regionId() {
        int region = -1;
        try {
            if (client != null && client.getLocalPlayer() != null
                    && client.getLocalPlayer().getWorldLocation() != null) {
                region = WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation())
                                .getRegionID();
            }
        } catch (Exception ignored) {
            // Live signals are best-effort.
        }
        return region;
    }

    /** Presentation-only duplicate guard for one loot batch reported by several RuneLite events. */
    String lastLootBatch = "";

    long lastLootAt;

    static String lootKey(Collection<ItemStack> items) {
        var key = new StringBuilder();
        if (items != null) {
            for (var item : items)
                key.append(item.getId()).append('x').append(item.getQuantity()).append(',');
        }
        return key.toString();
    }

    /** Unconfirmed loot goes to presentation only; pickup settles through the engine. */
    void offerReward(boolean pending, String source, Collection<ItemStack> items, int multiplicity) {
        long now = now();
        boolean chest = source != null && source.toLowerCase(ROOT).contains("loot chest");
        if ((pending || chest) && ClueCostPairing.looksLikeClueActivity(source)) {
            engine.beginCluePath("loot:" + source, source);
        }
        lootObserved(pending || chest, source, items, multiplicity, now);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event == null || event.getGroup() == null) return;
        if (GroundItemsPrefs.GROUP.equals(event.getGroup()) || "grounditemsplugin".equals(event.getKey())) {
            // Ground Items turned on or off (RuneLite saves that as runelite.grounditemsplugin,
            // 1.3.1).
            // The Ground Items list preferences changed: re-read the filter authority and
            // repaint the sidebar. Presentation only; stored receipts never change.
            lootPresentationFilter.refresh();
            refreshPresentation();
            return;
        }
        if (!GpManagerConfig.GROUP.equals(event.getGroup())) return;
        if ("includeEquipment".equals(event.getKey()) || "includeRunePouch".equals(event.getKey())) {
            // The tracked snapshot shape changed. Warm a new baseline so enabling or
            // disabling a container cannot appear as a synthetic gain or cost.
            engine.beginPriming();
        }
        if (msg("lootpresentationfilter").equals(event.getKey())
                || msg("minimumdisplayedlootvalue").equals(event.getKey())) {
            refreshPresentation();
        }
    }

    /** Republishes the current facts to the sidebar; presentation only. */
    void refreshPresentation() {
        var panel = activityPanel;
        if (panel != null) panel.refresh();
    }

    boolean canIngest() {
        try {
            if (client == null || client.getGameState() != LOGGED_IN) return false;
        } catch (RuntimeException ex) {
            return false;
        }
        return persistence.isTrackingReady();
    }

    /** The shared subscriber gate: live tracking and a real event (owner 1.4.0 size pass). */
    boolean skip(Object event) {
        return !canIngest() || event == null;
    }

    /** Ticks without gameplay or movement, for AFK pause (100 ticks a minute). */
    int idleTicks;

    WorldPoint lastTile;

    /**
     * The items one dispenser award names, by name: "3 x blighted anglerfish and 1 x rune med helm,
     * and an extra blighted manta ray".
     */
    static Map<String, Long> bagAward(String items) {
        var named = new LinkedHashMap<String, Long>();
        for (String part : items.split(",? and (?:an extra )?|, ")) {
            String[] count = part.trim().split(" x ", 2);
            boolean included = count.length == 2 && count[0].matches("\\d+");
            String name = (included ? count[1] : part.trim()).replaceFirst("^an? (?:extra )?", "");
            if (!name.isEmpty())
                named.merge(name, included ? Long.parseLong(count[0]) : 1, Long::sum);
        }
        return named;
    }

    /** How long a still character keeps its activity name: the Activity label timeout, 5 s at least. */
    static long idleDelay(GpManagerConfig config) {
        return max(5_000, config.activityLabelSeconds() * 1_000L);
    }

    /**
     * One still tick toward AFK pause. Past the limit, a run that is no longer AFK-paused was
     * resumed from the status dot, so the AFK clock starts over instead of pausing again at once.
     */
    void idleTick(int afk) {
        if (afk <= 0 || ++idleTicks < afk) return;
        if (idleTicks > afk && !engine.isIdlePaused()) idleTicks = 0;
        else engine.pauseIdle(now());
    }

    void markActivity() {
        idleTicks = 0;
        if (!canIngest()) return;
        long now = now();
        // Inventory/bank context alone resumes a saved Idle pause but never Manual/Stop and
        // never auto-starts a tracker (startup needs gameplay activity).
        if (engine.resume(now, IDLE)) rebaseline();
    }

    /**
     * A click in the game world (walk, NPC, object, ground item, player, use or cast on one) or on
     * an item lifts AFK pause; side tabs and panels such as Friends or Skills do not (owner 1.5.0).
     */
    static boolean worldAction(MenuAction action, boolean itemOp) {
        String name = action == null ? "" : action.name();
        return itemOp || action == MenuAction.WALK || name.startsWith("NPC_") || name.startsWith("GAME_OBJECT_")
                || name.startsWith("GROUND_ITEM_") || name.startsWith("PLAYER_")
                || name.startsWith("WIDGET_TARGET_ON_");
    }

    void markGameplayActivity() {
        markGameplayActivity(false);
    }

    void markGameplayActivity(boolean beforeItemChange) {
        idleTicks = 0;
        if (!canIngest()) return;
        long now = now();
        if (engine.autoStart(now)) {
            if (beforeItemChange && client.getItemContainer(INV) != null) {
                engine.setBaseline(snapshotFactory.capture(config.includeEquipment(), config.includeRunePouch()));
            } else {
                engine.beginPriming();
            }
        }
        // Lifecycle (logout/hop), Idle, and Recovery resume on gameplay; Manual/Stop do not.
        if (engine.resume(now, LIFECYCLE, IDLE, RECOVERY)) rebaseline();
    }

    /**
     * Exact spell evidence for one consumption click. A spellbook selection click carries the spell
     * widget directly; the following widget-target click (NPC/player/item/object) carries no widget
     * of its own, so the client's still-selected spell widget is the positive evidence. The menu
     * target is never consulted: a target named "Ice Burst" cannot name a spell.
     */
    ActionLabel spellLabel(MenuOptionClicked event, String option) {
        var clicked = event.getWidget();
        if (clicked != null) {
            ActionLabel direct = ActionLabel.fromSpellMenu(option, clicked.getId(), InterfaceID.MAGIC_SPELLBOOK,
                            clicked.getText(), clicked.getName());
            if (direct != null) return direct;
        }
        if (!isWidgetAction(event.getMenuAction())) return null;
        var selected = client.getSelectedWidget();
        return selected == null ? null : ActionLabel.fromSpellMenu(option, selected.getId(),
                        InterfaceID.MAGIC_SPELLBOOK, selected.getText(), selected.getName());
    }

    /**
     * Inventory Bury/Drop/Eat menus sometimes report {@code getItemId() == -1}. Fall back to
     * item-op / widget / inventory-slot ids (Supplies Tracker pattern) and canonicalize so intent
     * matches snapshot item ids.
     */
    int menuItemId(MenuOptionClicked event) {
        if (event == null) return -1;
        int id = event.getItemId();
        if (id < 0 && event.isItemOp()) id = event.getId();
        if (id < 0) {
            var widget = event.getWidget();
            if (widget != null) id = widget.getItemId();
        }
        // CC_OP inventory Eat/Drink often leaves getItemId() == -1; read the clicked slot.
        if (id < 0) id = slotItemId(event.getParam0());
        if (id < 0) return -1;
        return lookup == null ? id : lookup.canon(id);
    }

    int slotItemId(int slot) {
        if (slot < 0) return -1;
        try {
            var inventory = client.getItemContainer(INV);
            if (inventory == null) return -1;
            Item[] items = inventory.getItems();
            if (items == null || slot >= items.length) return -1;
            Item item = items[slot];
            return item == null ? -1 : item.getId();
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    void persist() {
        persist(false);
    }

    /** {@code quick}: the periodic gameplay save, which reuses unchanged closed sessions' JSON. */
    void persist(boolean quick) {
        savedRevision = engine.getRevision();
        if (config.persistHistory()) persistence.scheduleSave(quick);
    }

    String itemName(int id) {
        try {
            var composition = lookup == null ? null : lookup.composition(id);
            return composition == null ? "Item " + id : composition.getName();
        } catch (RuntimeException ex) {
            return "Item " + id;
        }
    }

    String normalize(String text) {
        // RuneLite's precompiled tag pattern: this runs on every chat line, click and tick (1.3.1).
        return text == null ? "" : Text.removeTags(text).trim().toLowerCase(ROOT);
    }

    @Provides
    GpManagerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GpManagerConfig.class);
    }
}

/**
 * Client-thread activity naming: a two-hit candidate that holds until something else is seen,
 * reseeded from the active session on owner change. Accounting never reads the title; the engine
 * only receives {@link Engine#setDetectedActivity} (session activity hint / auto-start) and the
 * PRODUCTION context window that lets a transform skill's XP confirm a processing receipt.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ActivityDetector {
    static final long MILLIS_PER_GAME_TICK = 600;
    final GpManagerConfig config;
    final Engine engine;

    /** The activity with nothing more specific detected. */
    static final String GENERAL = "General";

    String activityCandidate = GENERAL;
    int activityEvidence;
    String detectedActivity = GENERAL, activitySessionId;
    long productionWindowExpiresAtEpochMillis;

    /**
     * The skill a gather click asked for ("Chop down": Woodcutting). A click proves nothing (no
     * axe, still walking), so the skill's first XP names it at once instead (owner 1.1).
     */
    String gatherSkill = "";

    /** How long a Make-X run stays open for its skill's XP after the last production evidence. */
    long productionRunUntilEpochMillis;

    void observe(String activity, boolean immediate) {
        if (!config.autoActivityDetection() || blank(activity)) return;
        String next = activity.trim();
        if ("NPC loot".equalsIgnoreCase(next)) {
            // A generic fallback is not an activity; the previous real label stands.
            return;
        }
        if (next.equalsIgnoreCase(activityCandidate)) {
            activityEvidence++;
        } else {
            activityCandidate = next;
            activityEvidence = 1;
        }
        if (immediate || activityEvidence >= 2) {
            detectedActivity = next;
            engine.setDetectedActivity(next, now());
        }
    }

    /** Settled transaction types that name the activity (PK, named loot, trade). */
    void observeTransaction(Transaction transaction) {
        if (transaction == null) return;
        switch (transaction.getType()) {
            case PK_LOOT:
            case PK_SUPPLY_COST:
            case PK_DEATH_LOSS:
                observe("PKing", true);
                break;
            case LOOT:
                String lootActivity = transaction.getActivityName();
                if (!blank(lootActivity) && !GENERAL.equalsIgnoreCase(lootActivity.trim())) {
                    observe(lootActivity.trim(), true);
                }
                break;
            case TRADE:
                observe("Trading", true);
                break;
            default:
                break;
        }
    }

    /** Active-owner change reseeds the candidate from the session hint. */
    void syncSession() {
        var active = engine.getActiveSession();
        String nextId = active == null ? null : active.getId();
        if (nextId == null ? activitySessionId == null : nextId.equals(activitySessionId)) return;
        activitySessionId = nextId;
        String initial = active == null ? GENERAL : active.getActivityHint();
        detectedActivity = nonBlank(initial, GENERAL);
        activityCandidate = detectedActivity;
        activityEvidence = 0;
    }

    /** Local death drops the detected activity so the killer cannot title the next session. */
    void clearOnDeath() {
        resetToGeneral(true);
    }

    void resetToGeneral(boolean resetHold) {
        if (!GENERAL.equalsIgnoreCase(detectedActivity) || resetHold) {
            detectedActivity = GENERAL;
            activityCandidate = GENERAL;
            activityEvidence = 0;
            if (config.autoActivityDetection()) engine.setDetectedActivity(GENERAL, now());
        }
    }

    /**
     * Positive XP for {@code skill}: names the activity (Agility prefers the course) and, inside an
     * open PRODUCTION window, re-arms the context so the processing receipt can settle.
     */
    void observeXp(String skill, String agilityCourse, boolean fighting, long now) {
        if ("Agility".equalsIgnoreCase(skill) && agilityCourse != null)
            observe(agilityCourse, true);
        // Combat XP, and Magic in a fight, names nothing: the NPC's loot does. Alching is Magic.
        else if (!"Prayer".equalsIgnoreCase(skill) && !has(skill, rules("activity_rules").get("combat"))
                && !(fighting && "Magic".equals(skill)))
            observe(skill.trim(), skill.equalsIgnoreCase(gatherSkill));
        // A Make-X run has actions with no XP (a failed iron smelt), so a production skill's XP
        // re-arms the context for 30 s after the last one, not only inside the six-tick window.
        if (now <= productionWindowExpiresAtEpochMillis || TRANSFORM.containsValue(skill)
                || "Smithing".equals(skill)) {
            continueProduction(skill, now);
        }
    }

    /**
     * Evidence the Make-X run goes on (its XP, a failed smelt): re-arms it within 30 s of the last.
     */
    void continueProduction(String note, long now) {
        if (now <= productionRunUntilEpochMillis) armProduction(note, now);
    }

    /** Enabling during Make-X needs fresh production XP, without a click made while disabled. */
    void clearProduction(long now) {
        productionWindowExpiresAtEpochMillis = 0;
        productionRunUntilEpochMillis = now + 30_000;
        gatherSkill = "";
    }

    /** Station/menu hint: arm PRODUCTION context for the correlation window. */
    void armProduction(String skillNote) {
        armProduction(skillNote, now());
    }

    void armProduction(String skillNote, long now) {
        int ticks = 6;
        String note = nonBlank(skillNote, "Production");
        engine.markContext(PRODUCTION, ticks, note);
        productionWindowExpiresAtEpochMillis = now + ticks * MILLIS_PER_GAME_TICK;
        productionRunUntilEpochMillis = now + 30_000;
    }
}

/**
 * Agility courses by map region, so Agility XP earns a named activity ("Wilderness Agility Course")
 * instead of the bare skill. Presentation / activity naming only — accounting never reads this.
 * Region ids follow the wiki course maps (and RuneLite's agility plugin).
 */
class AgilityCourses {
    static final Map<Integer, String> BY_REGION = byId("agility_courses");

    /** Course name for a region, or null when the region is not a known course. */
    static String courseName(int region) {
        return BY_REGION.get(region);
    }

    /** The Wilderness course's reward dispenser and the Brimhaven ticket dispenser. */

    /** Name-only dispenser evidence, independent of the region, as before. */
    static final Pattern DISPENSER = pattern("dispenser");

    static boolean isDispenser(String name) {
        return name != null && DISPENSER.matcher(lower(name)).matches();
    }
}

/**
 * Client-thread interaction target for the active owner: the NPC being fought or interacted with,
 * with a short release grace so a swing gap does not blank it. Scenery and opponent names never
 * publish — the activity header titles NPCs only. The backend uses it for one fact — whether a
 * death happened in positive PvM context — and publishes the name to presentation through a
 * presentation callback.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class InteractionContextTracker {
    static final long RELEASE_GRACE_MILLIS = 4_000;
    final Client client;
    final Engine engine;
    final GpManagerConfig config;
    String owner = "", name = "";
    boolean combatNpc;
    long releasedAt, lastTargetAt;
    BooleanSupplier ingestion = () -> false;
    BiConsumer<String, Boolean> presentation = (name, combat) -> {};

    void bindPresentation(BooleanSupplier ready, BiConsumer<String, Boolean> changed) {
        ingestion = ready == null ? () -> false : ready;
        presentation = changed == null ? (name, combat) -> {} : changed;
    }

    void clear() {
        owner = "";
        lastTargetAt = 0;
        set("", false);
    }

    /** True while the active owner's target positively identifies PvM (a combat NPC). */
    boolean hasPvmContext() {
        return ready() && combatNpc && (releasedAt == 0 || now() - releasedAt < RELEASE_GRACE_MILLIS);
    }

    boolean ready() {
        var session = engine.getActiveSession();
        String nextOwner = session == null ? "" : session.getId();
        if (!nextOwner.equals(owner)) {
            clear();
            owner = nextOwner;
        }
        if (nextOwner.isEmpty() || !config.autoActivityDetection() || !ingestion.getAsBoolean()) {
            set("", false);
            return false;
        }
        return true;
    }

    void onInteractingChanged(InteractingChanged event) {
        if (!ready() || event == null || event.getSource() != client.getLocalPlayer()) return;
        var local = client.getLocalPlayer();
        if (local == null || local.isDead()) {
            set("", false);
            return;
        }
        Actor target = event.getTarget();
        if (target instanceof NPC) {
            NPC npc = (NPC) target;
            set(npc.getName(), npc.getCombatLevel() > 0);
        } else if (target instanceof Player) {
            // Opponent names are never display evidence; PvP reads as its activity.
            set("", false);
        } else {
            releasedAt = now();
        }
    }

    /** Scenery is never an activity; any other intentional action cancels a non-combat target. */
    void onMenuOptionClicked(MenuOptionClicked event) {
        if (!ready() || event == null || event.isConsumed() || event.getMenuAction() == null)
            return;
        String type = event.getMenuAction().name();
        var menuEntry = event.getMenuEntry();
        String clicked = menuEntry == null || menuEntry.getTarget() == null ? "" : menuEntry.getTarget().trim();
        if (combatNpc && !clicked.isEmpty() && clicked.equalsIgnoreCase(name)) {
            // Still attacking the same target: keep the activity label alive.
            lastTargetAt = now();
        }
        if (!(type.startsWith("GAME_OBJECT_") && type.endsWith("_OPTION")) && !combatNpc)
            set("", false);
    }

    /** Release grace expiry for non-combat targets; a combat target lives out the label timeout. */
    void onGameTick() {
        long now = now();
        if (releasedAt != 0 && now - releasedAt >= RELEASE_GRACE_MILLIS && !combatNpc) {
            set("", false);
            return;
        }
        if (combatNpc && !empty(name) && lastTargetAt != 0
                && now - lastTargetAt >= max(1_000, config.activityLabelSeconds() * 1_000L)) {
            set("", false);
        }
    }

    void set(String next, boolean combat) {
        String n = next == null ? "" : next.trim();
        releasedAt = 0;
        if (!n.isEmpty()) lastTargetAt = now();
        if (n.equals(name) && combat == combatNpc) return;
        name = n;
        combatNpc = combat;
        presentation.accept(n, combat);
    }
}

/**
 * Presentation-only character idle (Idle Notifier–shaped). Never pauses accounting or changes GP/hr
 * — session AFK pause stays separate.
 *
 * <p>Busy when animating, interacting, or recent skilling XP (Make-X / RC gaps). Idle badge after
 * hard-busy clears for {@link #delayMillis} (fixed at 5 s, SIDEBAR_SPEC.md §6b), or {@link
 * #delayMillis} after the last skilling XP when XP was the only soft-busy (no double-wait).
 */
@Singleton
class CharacterIdleModel {
    long delayMillis = 5_000, idleCandidateSinceEpochMillis, lastSkillingXpEpochMillis;
    boolean characterIdle;

    /** Soft-busy for Make-X / process gaps — presentation only. */
    synchronized void noteSkillingXp(long now) {
        lastSkillingXpEpochMillis = nonNeg(now);
        characterIdle = false;
        idleCandidateSinceEpochMillis = 0;
    }

    /**
     * @param animating true when local animation != -1
     * @param interacting true when local player has an interact target
     * @return true once when character idle newly becomes true
     */
    synchronized boolean tick(boolean animating, boolean interacting, long now) {
        boolean recentXp = lastSkillingXpEpochMillis > 0 && now - lastSkillingXpEpochMillis < delayMillis;
        boolean hardBusy = animating || interacting;
        if (hardBusy || recentXp) {
            if (hardBusy) {
                // Animation / interact: full Idle delay after clear.
                idleCandidateSinceEpochMillis = 0;
            } else {
                // XP soft-busy alone: Idle fires delayMillis after the XP drop —
                // do not stack a second full delay when the soft window ends.
                idleCandidateSinceEpochMillis = lastSkillingXpEpochMillis;
            }
            characterIdle = false;
            return false;
        }
        if (idleCandidateSinceEpochMillis <= 0) idleCandidateSinceEpochMillis = now;
        if (now - idleCandidateSinceEpochMillis < delayMillis) {
            characterIdle = false;
            return false;
        }
        if (!characterIdle) {
            characterIdle = true;
            return true;
        }
        return false;
    }

    /** Logout / hop — reset like Idle Notifier timers. */
    synchronized void clear() {
        idleCandidateSinceEpochMillis = 0;
        lastSkillingXpEpochMillis = 0;
        characterIdle = false;
    }
}

/**
 * Client-thread adapter for character idle (no animation, no target for the idle delay).
 * Presentation-only: it never pauses the engine or touches GP/hr; session idle pause is the
 * engine's own idle timeout.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class CharacterIdleTracker {
    final Client client;
    final CharacterIdleModel model;

    void clear() {
        model.clear();
    }

    /** Soft-busy across Make-X / process XP gaps. */
    void noteSkillingXp() {
        model.noteSkillingXp(now());
    }

    /**
     * @return true once when the character newly becomes idle
     */
    boolean onGameTick() {
        var local = client.getLocalPlayer();
        boolean animating = local != null && local.getAnimation() != -1;
        boolean interacting = local != null && local.getInteracting() != null;
        return model.tick(animating, interacting, now());
    }
}

/**
 * Samples the local player's PvP context (NONE / SAFE / DANGEROUS) on the game tick and on demand
 * for death/loot disposition. Context only; it never books item flows.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class PvpContextReader {
    final Client client;
    PvpContext context = PvpContext.NONE;

    /**
     * The context sampled on the latest tick (or by {@link #sample}); presentation and disposition
     * input only.
     */
    PvpContext context() {
        return context;
    }

    /** Re-reads the client signals now (death/loot events may precede the next tick sample). */
    PvpContext sample(Player player) {
        int region = player == null || player.getWorldLocation() == null ? 0
                        : player.getWorldLocation().getRegionID();
        context = PvpContext.classify(client.getVarbitValue(INSIDE_WILDERNESS) == 1,
                        client.getVarbitValue(PVP_AREA_CLIENT) == 1,
                        client.getVarbitValue(THIS_IS_A_PVP_OR_BH_WORLD) == 1,
                        client.getVarbitValue(THIS_IS_A_PVP_ARENA_WORLD) == 1, region);
        return context;
    }

    /** Game tick: re-classify the PvP context; disposition input only. */
    void onGameTick(Player player) {
        sample(player);
    }
}

/**
 * Bookkeeping-only PvP context. Detection never creates, renames or splits a session and never
 * becomes location analytics.
 *
 * <p>Precedence is fail-closed: a positively identified safe/minigame arena wins; otherwise only
 * confident item-risk evidence (inside the Wilderness, or attackable on a PvP/BH world) is
 * dangerous; anything conflicting or insufficient is {@link #NONE}. A manual session name such as
 * "PK Trip" is never an input.
 */
enum PvpContext {
    NONE, SAFE_PVP_OR_MINIGAME, DANGEROUS_PVP;

    /** Safe PvP arenas beyond LMS: Castle Wars, Soul Wars, TzHaar Fight Pit. */
    static final int[] SAFE_ARENA_REGIONS = {9520, 8237, 8493, 8749, 9552};

    /**
     * @param insideWilderness {@code INSIDE_WILDERNESS} varbit
     * @param pvpSpecOrb {@code PVP_SPEC_ORB} varbit — shown only where the player is attackable
     * @param pvpOrBhWorld {@code THIS_IS_A_PVP_OR_BH_WORLD} varbit
     * @param pvpArenaWorld {@code THIS_IS_A_PVP_ARENA_WORLD} varbit (safe PvP Arena)
     * @param regionId current region, or {@code <= 0} when unknown
     */
    static PvpContext classify(boolean insideWilderness, boolean pvpSpecOrb, boolean pvpWorld,
            boolean pvpArenaWorld, int region) {
        if (pvpArenaWorld || isSafeArenaRegion(region)) return SAFE_PVP_OR_MINIGAME;
        if (insideWilderness || (pvpWorld && pvpSpecOrb)) return DANGEROUS_PVP;
        return NONE;
    }

    static boolean isSafeArenaRegion(int regionId) {
        if (MinigameRegionHints.isLmsRegion(regionId)) return true;
        if (regionId > 0) {
            for (int region : SAFE_ARENA_REGIONS) {
                if (region == regionId) return true;
            }
        }
        return false;
    }

    boolean isDangerous() {
        return this == DANGEROUS_PVP;
    }

    boolean isSafe() {
        return this == SAFE_PVP_OR_MINIGAME;
    }
}

/**
 * One Grind's PvP record: kills, deaths, streaks and the best kill. A kill's money is the net of
 * the receipts carrying its id (loot less the supplies attached to it). While those receipts are
 * live the best kill is read from them, so a correction or undo shows at once.
 */
class PkTally extends SaveCopies {
    static final String KILL = "kill:", DEATH = "death:";

    int kills, deaths;

    /** Kills since the last death, or minus the deaths since the last kill. */
    int streak;

    int bestStreak;

    /** Best net of a kill whose receipts have all been compacted. */
    long bestKill;

    /** Net already compacted for a kill that still has live receipts. */
    Map<String, Long> compactedKillNet = new HashMap<>();

    /** Counts one kill or death and returns the id its receipts carry. */
    String record(boolean kill) {
        if (kill) {
            kills++;
            streak = max(streak, 0) + 1;
            bestStreak = max(bestStreak, streak);
        } else {
            deaths++;
            streak = min(streak, 0) - 1;
        }
        return (kill ? KILL : DEATH) + uuid();
    }

    int encounters() {
        return kills + deaths;
    }

    static boolean isEncounter(String encounter) {
        return encounter.startsWith(KILL) || encounter.startsWith(DEATH);
    }

    /**
     * A kill receipt left the ledger: keep its net, and fold the kill into the best once none of
     * its receipts remain live.
     */
    void compact(Transaction receipt, List<Transaction> live) {
        String id = receipt.getEncounterId();
        if (!id.startsWith(KILL)) return;
        long net = compactedKillNet.merge(id, net(receipt), SafeMath::safeAdd);
        for (var transaction : live) {
            if (id.equals(transaction.getEncounterId())) return;
        }
        compactedKillNet.remove(id);
        bestKill = max(bestKill, net);
    }

    long bestKill(List<Transaction> live) {
        var nets = new HashMap<>(compactedKillNet);
        for (var transaction : live) {
            String id = transaction.getEncounterId();
            if (id.startsWith(KILL)) nets.merge(id, net(transaction), SafeMath::safeAdd);
        }
        long best = bestKill;
        for (long net : nets.values()) best = max(best, net);
        return nonNeg(best);
    }

    static long net(Transaction transaction) {
        var money = Accounting.transaction(transaction);
        return money.available && money.included ? money.getNet() : 0;
    }
}

/**
 * Interface-visibility reads on the client thread. The client retains bank contents after the
 * interface closes, so only widget visibility is evidence of an open UI; cached ownership data must
 * never suppress later gathering or supply consumption.
 */
class Widgets {
    /** Bank main or deposit-box UI visible — drives Banking header and Withdrew buffer. */
    static boolean isBankOpen(Client client) {
        return visible(client, InterfaceID.Bankmain.ITEMS_CONTAINER)
                || visible(client, InterfaceID.BankDepositbox.INVENTORY)
                || visible(client, InterfaceID.BankDepositbox.CONTENTS);
    }

    /** Tool Leprechaun store (either panel) visible; its withdrawals and deposits are storage. */
    static boolean isToolStoreOpen(Client client) {
        return visible(client, InterfaceID.FarmingTools.FRAME)
                || visible(client, InterfaceID.FarmingToolsSide.UNIVERSE);
    }

    /**
     * Stores and exchanges whose own open window proves a move neutral (d29): its note, else null.
     */
    static final List<String[]> STORES = rows("store_windows");

    /** Shop windows whose own open UI turns a coin movement into a trade (owner 1.3.4). */
    static final List<String[]> SHOPS = rows("shop_windows");

    static String store(Client client) {
        for (String[] row : STORES) if (visible(client, Integer.parseInt(row[0]))) return row[2];
        return null;
    }

    /** A shop window (classic or omni) visible: purchases and sales are traded, not Review. */
    static boolean isShopOpen(Client client) {
        for (String[] row : SHOPS) if (visible(client, Integer.parseInt(row[0]))) return true;
        return false;
    }

    static boolean isGeOpen(Client client) {
        // A banker's or clerk's collection box collects offers like the exchange itself (1.2).
        return visible(client, InterfaceID.GeOffers.UNIVERSE) || visible(client, InterfaceID.GeOffersSide.ITEMS)
                || visible(client, InterfaceID.GeCollect.UNIVERSE);
    }

    /** A visible widget's text, "" when it is absent or hidden. */
    static String text(Client client, int componentId) {
        var widget = client.getWidget(componentId);
        return widget == null || widget.isHidden() ? "" : String.valueOf(widget.getText());
    }

    static boolean visible(Client client, int componentId) {
        try {
            var widget = client == null ? null : client.getWidget(componentId);
            return widget != null && !widget.isHidden();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ContainerSnapshotFactory {
    final Client client;
    final ItemLookup lookup;

    /** Rune pouch slot varbits (type, quantity) — live account state, unlike bag containers. */
    static final int[][] RUNE_POUCH_SLOTS = cachedRows("rune_pouch_slots").stream()
            .map(row -> new int[] {Integer.parseInt(row[0]), Integer.parseInt(row[1])}).toArray(int[][]::new);

    /**
     * @param includeRunePouch merge runes held in the rune pouch. The pouch is account state
     *     exposed through live varbits, so casting from it books a cost and filling it nets to
     *     zero. Looting bag / seed box containers are deliberately excluded: the client only syncs
     *     them when the bag is opened, so they would drift.
     */
    ContainerSnapshot capture(boolean includeEquipment, boolean includeRunePouch) {
        var counts = new HashMap<Integer, Long>();
        mergeContainer(counts, client.getItemContainer(INV));
        if (includeEquipment) {
            mergeContainer(counts, client.getItemContainer(WORN));
            // These varps stay live without opening the quiver's temporary UI container.
            if (counts.keySet().stream().anyMatch(id -> {
                var item = client.getItemDefinition(id);
                return item != null && item.getIntValue(ParamID.QUIVER_AMMO_AVAILABLE) == 1;
            })) {
                int id = client.getVarpValue(DIZANAS_QUIVER_TEMP_AMMO);
                int qty = client.getVarpValue(DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
                if (id > 0 && qty > 0) counts.merge(canonical(id), (long) qty, Long::sum);
            }
        }
        if (includeRunePouch) mergeRunePouch(counts);
        // A carried ash sanctifier holds death runes as charges, ten per rune (research 1.2): each
        // ten ashes it scatters spend a rune, and charging it nets to zero.
        if (counts.containsKey(ItemID.ASH_SANCTIFIER)) {
            counts.merge(ItemID.DEATHRUNE, client.getVarbitValue(CHARGES_ASH_SANCTIFIER_QUANTITY) / 10L,
                    Long::sum);
        }
        return new ContainerSnapshot(counts);
    }

    /**
     * Snapshot only the player's inventory and (when enabled for accounting) worn equipment for
     * local-death ownership reconciliation. Rune-pouch varbits and banked containers are
     * deliberately excluded because neither is an inventory/equipment stack read from the death
     * event.
     */
    ContainerSnapshot captureDeathHeld(boolean includeEquipment) {
        var counts = new HashMap<Integer, Long>();
        mergeContainer(counts, client.getItemContainer(INV));
        if (includeEquipment) mergeContainer(counts, client.getItemContainer(WORN));
        return new ContainerSnapshot(counts);
    }

    /** True when {@code varbitId} is one of the rune pouch slot varbits. */
    static boolean isPouchVarbit(int varbitId) {
        for (int[] slot : RUNE_POUCH_SLOTS) {
            if (slot[0] == varbitId || slot[1] == varbitId) return true;
        }
        return false;
    }

    void mergeRunePouch(Map<Integer, Long> counts) {
        EnumComposition runes;
        try {
            runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
        } catch (RuntimeException ignored) {
            return;
        }
        if (runes == null) return;
        for (int[] slot : RUNE_POUCH_SLOTS) {
            int type = client.getVarbitValue(slot[0]), quantity = client.getVarbitValue(slot[1]);
            if (type <= 0 || quantity <= 0) continue;
            int id = runes.getIntValue(type);
            if (id <= 0) continue;
            counts.merge(canonical(id), (long) quantity, Long::sum);
        }
    }

    void mergeContainer(Map<Integer, Long> counts, ItemContainer container) {
        if (container == null) return;
        for (Item item : container.getItems()) {
            if (item == null || item.getId() < 0 || item.getQuantity() <= 0) continue;
            int canonicalId = canonical(item.getId());
            counts.merge(canonicalId, (long) item.getQuantity(), Long::sum);
        }
    }

    /** An open bag or lit lantern is the same item as the closed or unlit one (d21; owner 1.2). */
    static final Map<Integer, Integer> ONE_ITEM = new HashMap<>();

    /** The open bags among them: a pickup may land in one instead of the inventory. */
    static final Set<Integer> BAGS = new HashSet<>();

    static {
        for (String[] row : rows("item_states")) {
            ONE_ITEM.put(Integer.parseInt(row[0]), Integer.parseInt(row[1]));
            if (row[2].contains("_OPEN=")) BAGS.add(Integer.parseInt(row[0]));
        }
    }

    /** True while the inventory or equipment holds an open bag. */
    boolean bagOpen() {
        for (int container : new int[] {INV, WORN}) {
            var items = client.getItemContainer(container);
            if (items == null) continue;
            for (Item item : items.getItems())
                if (item != null && BAGS.contains(item.getId())) return true;
        }
        return false;
    }

    /** The carried gem container must accept this gem; mining also requires it to be open. */
    boolean gemBag(int gem, boolean open) {
        var inventory = client.getItemContainer(INV);
        if (inventory == null) return false;
        for (Item item : inventory.getItems()) {
            if (item == null || open && !BAGS.contains(item.getId())) continue;
            for (String[] row : cachedRows("gem_containers"))
                if (canonical(item.getId()) == Integer.parseInt(row[0])
                        && Arrays.asList(row[1].split(",")).contains(String.valueOf(gem))) return true;
        }
        return false;
    }

    /** Un-noted / un-placeholdered, closed and unlit id; no ItemManager in offline tests. */
    int canonical(int identifier) {
        int id = lookup == null ? identifier : lookup.canon(identifier);
        return ONE_ITEM.getOrDefault(id, id);
    }
}

@EqualsAndHashCode
class ContainerSnapshot {
    Map<Integer, Long> quantities;

    ContainerSnapshot(Map<Integer, Long> counts) {
        quantities = unmodifiableMap(new HashMap<>(counts));
    }

    static ContainerSnapshot empty() {
        return new ContainerSnapshot(emptyMap());
    }

    Map<Integer, Long> diff(ContainerSnapshot previous) {
        var result = new HashMap<Integer, Long>();
        var itemIds = new HashSet<>(quantities.keySet());
        itemIds.addAll(previous.quantities.keySet());
        for (var id : itemIds) {
            long current = quantities.getOrDefault(id, 0L);
            long previousQuantity = previous.quantities.getOrDefault(id, 0L), delta = current - previousQuantity;
            if (delta != 0) result.put(id, delta);
        }
        return result;
    }

    /** Quantity of one item id in this snapshot (0 when absent). */
    long quantityOf(int id) {
        return quantities.getOrDefault(id, 0L);
    }
}

/**
 * Special bags/sacks/barrels/kits/pouches. Store/retrieve is ownership-neutral ({@link Kind});
 * contents valuation follows snapshots — never invent gain/loss.
 */
class UtilityContainerCatalogue {
    enum Kind {
        STORE, WITHDRAW, EMPTY_TO_BANK, EMPTY_TO_INVENTORY, CALIBRATE_CHECK
    }

    /** Special container names, most specific first: the first one a name contains wins. */
    static final List<String> NAMES = names("utility_containers");

    static final Pattern ESSENCE_POUCH = pattern("essence-pouch");

    /**
     * The container family used in the custody note ({@code herb_sack}), or null for other items.
     */
    static String familyFor(String title) {
        String lower = norm(title);
        if (lower != null && ESSENCE_POUCH.matcher(lower).find()) return "essence_pouch";
        for (String name : NAMES) {
            if (lower != null && lower.contains(name))
                return name.replace("'", "").replace(' ', '_').replace('-', '_');
        }
        return null;
    }

    /**
     * Classify a menu option on a known container. Deposit-box Empty → bank is {@link
     * Kind#EMPTY_TO_BANK} (ownership-neutral).
     */
    static Kind classify(String option, boolean depositBoxOpen) {
        String normalized = lower(option);
        return "check".equals(normalized) ? Kind.CALIBRATE_CHECK : normalized.startsWith("empty")
                        ? depositBoxOpen ? Kind.EMPTY_TO_BANK : Kind.EMPTY_TO_INVENTORY
                        : normalized.startsWith("withdraw") || normalized.startsWith("remove") ? Kind.WITHDRAW
                                : Kind.STORE;
    }

    /** True when evidence must stay ownership-neutral (never invent Net). */
    static boolean isNeutral(Kind kind) {
        return kind != null && kind != Kind.CALIBRATE_CHECK;
    }

    /**
     * True when the menu option is a container custody action this catalogue can classify.
     * Store/withdraw/empty/fill are reversible custody movement, never revenue or cost.
     */
    static boolean isCustodyOption(String option) {
        String normalized = norm(option);
        return normalized != null && (normalized.matches(msg("fill-check")) || normalized.startsWith("empty")
                        || normalized.startsWith("withdraw") || normalized.startsWith("remove"));
    }
}

/**
 * Region-based ownership-neutral zones. Some instances store the whole inventory and equipment on
 * entry and restore it on exit without any menu click at the exit — The Gauntlet is the canonical
 * case (GP Per Hour issues #7/#8: all gear booked as a loss on entry and a gain on exit). While the
 * player is inside such a region every settled delta is an ownership-neutral transfer; leaving
 * opens one more window so the restore settles as a transfer too. Reward chests are claimed in the
 * lobby, outside the zone, so real loot still counts.
 *
 * <p>Pure state machine over region ids; the plugin feeds it one region per tick.
 */
@Getter
class NeutralZoneTracker {
    enum Signal {
        /** Not in a neutral zone and nothing to close. */
        NONE,
        /** First tick inside a neutral zone. */
        ENTERED,
        /** Inside a neutral zone: keep the transfer window open this tick. */
        INSIDE,
        /** Just left a neutral zone: open one closing window for the gear restore. */
        LEFT
    }

    boolean inside;

    /** Feed the current region id (0 or negative when unknown). */
    Signal onRegion(int region) {
        boolean now = MinigameRegionHints.isNeutralZoneRegion(region);
        if (now) {
            boolean entered = !inside;
            inside = true;
            return entered ? Signal.ENTERED : Signal.INSIDE;
        }
        if (inside && region > 0) {
            inside = false;
            return Signal.LEFT;
        }
        // Unknown region (loading screens) keeps the previous state without signalling.
        return Signal.NONE;
    }

    void reset() {
        inside = false;
    }
}

/**
 * Minigame-only items: ids that cannot legally exist outside their minigame (a loaned loadout,
 * table supplies), so their grant and exit strip are both non-economic. Persistent rewards stay
 * tracked. Coinflow MinigameQuarantine evidence, Muse review, owner 2026-10-12.
 */
class MinigameItems {
    static final Set<Integer> IDS = byId("minigame_items").keySet();

    /** True for an item id that only exists inside its minigame. */
    static boolean isMinigameOnly(int id) {
        return IDS.contains(id);
    }
}

/**
 * Conservative region hints for LMS and Gauntlet instances when menu text is sparse. Live producers
 * pass {@link net.runelite.api.coords.WorldPoint#getRegionID()}.
 */
class MinigameRegionHints {
    /**
     * Region id to kind: "lms" for Last Man Standing instances (not exhaustive; menu and chat stay
     * primary) and "neutral" for instances that store the player's gear on entry and restore it on
     * exit with no exit click (The Gauntlet and the Corrupted Gauntlet; see minigame-regions.tsv).
     */
    static final Map<Integer, String> KINDS = byId("minigame_regions");

    /** True inside an instance whose inventory changes are never profit or loss. */
    static boolean isNeutralZoneRegion(int region) {
        return "neutral".equals(KINDS.get(region));
    }

    /** The PvP tutorial arena: lent gear and supplies, taken back on the way out. */
    static boolean isPvpTutorial(int region) {
        return "arena".equals(KINDS.get(region));
    }

    static boolean isLmsRegion(int region) {
        return "lms".equals(KINDS.get(region));
    }
}

/**
 * Ownership-neutral moves, treated like a bank TRANSFER: world and coin storages (seed vault, tool
 * leprechaun, coffers, STASH units...) and minigame moves (LMS loadouts, raid bags, GIM shared
 * storage). Never invent profit or loss on deposit, withdraw, enter or exit, and never book a full
 * inventory as Lost or loot on an LMS enter or exit.
 *
 * <p>The rows live in neutral_transfers.tsv and are checked in file order, so the first match wins:
 * minigame rows have "m" keys, and the ".lms" rows apply only inside an LMS region.
 */
class NeutralTransfers {
    static final Map<String, Clause[][]> RULES = rules("neutral_transfers");

    /** The transfer note of the first storage row matching this menu text, or null. */
    static String storage(String raw) {
        return match(norm(raw), false, -1);
    }

    /** The transfer note of the first minigame row matching this menu text, or null. */
    static String minigame(String raw, int region) {
        return match(lower(raw), true, region);
    }

    private static String match(String name, boolean minigame, int region) {
        boolean lms = region >= 0 && MinigameRegionHints.isLmsRegion(region);
        for (String[] row : cachedRows("neutral_transfers")) {
            if (row[0].startsWith("m") != minigame || row[0].endsWith(".lms") && !lms) continue;
            if (has(name, RULES.get(row[0]))) return GameData.text(row[3]);
        }
        return null;
    }
}
