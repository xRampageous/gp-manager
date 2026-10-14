package com.gpmanager;

import static com.gpmanager.Context.*;
import static com.gpmanager.GameData.msg;
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
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.*;
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
import java.util.*;

import javax.inject.Inject;

@PluginDescriptor(
        name = "GP Manager",
        internalName = "gp-manager",
        description =
                "Passive profit tracking: profit per Grind and per hour, a ledger you can correct, all-time totals, PvP results and a loot HUD.",
        tags = {"profit", "gp", "loot", "costs", "supplies", "sessions", "pking", "pk", "hud"})
public class GpManagerPlugin extends Plugin {
    /** Idle ticks a bank, market or loot context stays available. */
    static final int CORRELATION_TICKS = 6;

    enum DeathOutcome {
        TRACK_PK,
        TRACK_PVM_RECLAIM,
        UNCLASSIFIED
    }

    static final Logger log = LoggerFactory.getLogger(GpManagerPlugin.class);
    static final int SAVE_INTERVAL_TICKS = 50;

    /** Unchanged profiles save every 500 ticks (5 minutes) instead of every 30 seconds. */
    static final int IDLE_SAVE_TICKS = 500;

    long savedRevision = -1L;
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
    List<Flow> held = Collections.emptyList();

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
            panel.mutationOwner = clientThread::invoke;
            panel.bindPvp(() -> currentPvp);
            panel.bindLoggedIn(() -> loggedIn);
            panel.bindHopping(() -> lastGameState == HOPPING);
            var builder =
                    new HudBuilder(
                            config, (id, quantity) -> itemManager.getImage(id, quantity, false));
            panel.bindHud(builder);
            hudOverlay = new HudOverlay(builder, config);
            overlayManager.add(hudOverlay);
            hud = builder;
            lootPresentationFilter.refresh();
            panel.bindFilter(
                    new RecentFilter() {
                        public boolean isFlowIncluded(Flow flow) {
                            return lootPresentationFilter.isFlowIncluded(
                                    flow, config.lootPresentationFilter());
                        }

                        public String version() {
                            return config.lootPresentationFilter()
                                    + "|"
                                    + lootPresentationFilter.fingerprint
                                    + "|"
                                    + config.hiddenRecentItems()
                                    + "|"
                                    + config.hiddenRecentCharges();
                        }

                        public boolean showRecent(String name, long quantity, boolean charge) {
                            // A charge row answers to both lists; a match in either hides it.
                            String hidden =
                                    config.hiddenRecentItems()
                                            + (charge ? "," + config.hiddenRecentCharges() : "");
                            return LootPresentationFilterService.listState(
                                            "", hidden, name, quantity)
                                    != LootPresentationFilterService.HIDDEN;
                        }

                        public void hideRecent(String name, boolean charge) {
                            String list =
                                    charge
                                            ? config.hiddenRecentCharges()
                                            : config.hiddenRecentItems();
                            String entry =
                                    charge
                                            ? LootPresentationFilterService.chargePattern(name)
                                            : name;
                            var names = new ArrayList<>(Text.fromCSV(list));
                            if (!names.contains(entry)) names.add(entry);
                            configManager.setConfiguration(
                                    GpManagerConfig.GROUP,
                                    charge ? "hiddenRecentCharges" : "hiddenRecentItems",
                                    Text.toCSV(names));
                        }
                    });
            activityPanel = panel;
            if (!navigation.install(
                    NavButton.toolbarHost(clientToolbar), panel, NavButton.loadIcon())) {
                log.warn(
                        msg("gp-manager-sidebar"),
                        clientToolbar != null,
                        NavButton.loadIcon() != null);
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
        Player local = client.getLocalPlayer();
        int skull = local == null ? SkullIcon.NONE : local.getSkullIcon();
        boolean possible = pvp.context().isDangerous();
        boolean skulled = skull != SkullIcon.NONE;
        boolean protect = client.getVarbitValue(VarbitID.PRAYER_PROTECTITEM) == 1;
        if (!possible) held = Collections.emptyList();
        else if (client.getTickCount() % 2 == 0 && valuation != null && snapshotFactory != null) {
            held = valuation.value(snapshotFactory.captureDeathHeld(true).quantities);
        }
        currentPvp =
                new PvpState(
                        possible,
                        skulled,
                        protect,
                        possible ? PvpState.risk(held, skulled, protect) : 0L);
    }

    void transactionBooked(Transaction transaction, long now) {
        HudBuilder builder = hud;
        if (builder != null) {
            builder.tray().big = lootPresentationFilter.big(config.lootPresentationFilter());
            builder.tray().booked(transaction, now, builder.keepSession());
        }
        refreshPresentation();
    }

    void lootObserved(
            boolean pending,
            String source,
            Collection<ItemStack> items,
            int multiplicity,
            long now) {
        HudBuilder builder = hud;
        String batch = source + ":" + pending + ":" + multiplicity + ":" + lootKey(items);
        // Only reward windows ride the tray (ground loot shows once it is picked up). One reward
        // can arrive through several RuneLite events; show it once.
        // A clue casket's rewards reach the inventory at once and ride the tray as one casket row.
        if (builder != null
                && pending
                && items != null
                && !Phrases.lower(source).contains("clue")
                && !(batch.equals(lastLootBatch) && now - lastLootAt < 1_200L)) {
            lastLootBatch = batch;
            lastLootAt = now;
            for (ItemStack item : items) {
                // Named and priced by the one valuation route; nothing is counted from this.
                for (Flow flow :
                        valuation.value(
                                Collections.singletonMap(
                                        lookup.canon(item.getId()),
                                        (long) item.getQuantity() * max(1, multiplicity)))) {
                    builder.tray()
                            .observed(
                                    flow.itemId,
                                    flow.itemName,
                                    flow.quantityDelta,
                                    flow.unitPrice,
                                    now,
                                    builder.keepSession());
                }
            }
        }
        refreshPresentation();
    }

    void ownerScopeChanged(String scope) {
        HudBuilder builder = hud;
        if (builder != null) builder.tray().clear();
        SidebarPanel panel = activityPanel;
        if (panel == null) return;
        if (scope == null || "held".equals(scope) || "switching".equals(scope)) panel.suspend();
        else panel.resume();
    }

    void interactionChanged(String name, boolean combat) {
        // Presentation policy: the target never retitles the run; HUD+ shows it as its header,
        // and fighting another NPC starts that NPC's tray streak.
        HudBuilder builder = hud;
        if (builder != null && !HudTray.minion(name)) {
            builder.interaction(name, combat);
        }
    }

    /** Every-tick signals: live values only, never a full page rebuild. */
    void pulse() {
        SidebarPanel panel = activityPanel;
        if (panel != null) panel.tick();
    }

    int ticksSinceSave;
    int recentPlayerCombatTicks;

    Map<Skill, Integer> lastSkillExperience = new EnumMap<>(Skill.class);
    NeutralZoneTracker neutralZoneTracker = new NeutralZoneTracker();
    int geOfferSeedTicks;

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
        TrackingIdentity identityAtStart = persistence.resolveCurrentIdentity();
        persistence.initAsync(
                () -> {
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
        clientThread.invokeLater(
                () -> {
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
        engine.pauseForLifecycle(SafeMath.now());
        persistence.shutdown(config.persistHistory());
        lastSkillExperience.clear();
        interactionContextTracker.clear();
        LiveSnapshot.recentKey = Collections.emptyList();
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
        valuation.setEconomyState(
                client != null && client.getGameState() == LOGGED_IN
                        ? Valuation.economyFor(client.getWorldType())
                        : Valuation.Economy.UNKNOWN);
    }

    /** Karamja gloves 1-4 on the hands slot pick the improved tokkul rate. */
    private static final Set<Integer> KARAMJA_GLOVES =
            Set.of(ItemID.ATJUN_GLOVES_EASY, ItemID.ATJUN_GLOVES_MED,
                    ItemID.ATJUN_GLOVES_HARD, ItemID.ATJUN_GLOVES_ELITE);

    /** Client-thread gloves snapshot; called at login/start and on worn-equipment changes. */
    void refreshKaramjaGloves() {
        if (valuation == null) return;
        ItemContainer worn = client == null ? null : client.getItemContainer(InventoryID.WORN);
        Item gloves = worn == null ? null : worn.getItem(EquipmentInventorySlot.GLOVES.getSlotIdx());
        valuation.setKaramjaGlovesWorn(gloves != null && KARAMJA_GLOVES.contains(gloves.getId()));
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState previous = lastGameState;
        lastGameState = event.getGameState();
        if (event.getGameState() == LOGGED_IN) {
            loggedIn = true;
            // Login or world hop: re-read the economy identity from the client thread.
            refreshEconomyState();
            refreshKaramjaGloves();
        } else if (event.getGameState() == LOGIN_SCREEN || event.getGameState() == HOPPING) {
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
                engine.pauseForLifecycle(SafeMath.now());
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
        long now = SafeMath.now();
        // Owner live 2026-10-13: an option pulls the automatic start (or the Idle/Recovery
        // resume that gameplay would do) to the first login of this client run; Manual pause
        // and Stop always hold.
        boolean loginAutoStart =
                firstLoginAutoStart && config.autoStartSession() && config.autoStartOnLogin();
        firstLoginAutoStart = false;
        if (loginAutoStart) engine.autoStart(now);
        // Login restores identity and baseline. Resume LIFECYCLE only (hop/relog) without the
        // option; with it Idle and Recovery come back at the first login too.
        if (engine.getActiveSession() != null) engine.beginPriming();
        if (boot) {
            boot = false;
            // Seed current XP rather than swallowing the first real XP drop after enabling.
            lastSkillExperience.clear();
            for (Skill skill : Skill.values())
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
        if (engine.live() && client.getItemContainer(InventoryID.INV) != null) {
            engine.setBaseline(
                    snapshotFactory.capture(config.includeEquipment(), config.includeRunePouch()));
        }
    }

    /** Same-turn owner rebind so a custom start/finish or account switch never lingers one tick. */
    void syncOwnerScope() {
        String scope =
                persistence != null && persistence.isTrackingReady()
                        ? String.valueOf(System.identityHashCode(engine.getActiveSession()))
                        : "held";
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
                GrandExchangeOffer offer = offers[slot];
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
            engine.seedGeSlots(geOfferLedger.snapshots(), SafeMath.now());
        } else {
            // Do not treat a partial/null slot array as an empty-account baseline.
            // Retry until all eight current slots are observed.
            geOfferSeedTicks = 1;
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (!canIngest()) return;
        int containerId = event.getContainerId();
        boolean inventory = containerId == InventoryID.INV || containerId == InventoryID.WORN;
        if (containerId == InventoryID.WORN) refreshKaramjaGloves();
        if (!inventory) markActivity();
        if (containerId == InventoryID.BANK) {
            engine.markContext(Context.TRANSFER, CORRELATION_TICKS, "Bank transfer");
        }
        kit(containerId, event.getItemContainer());
        chestChanged(containerId, event.getItemContainer());
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
    static final Map<Integer, String> KITS = GameData.byId("kits");

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
        if (old != null) engine.inbox.take(KIT_SNAPSHOT, id, 1L, e -> e == old);
        engine.inbox.add(KIT_SNAPSHOT, id, 1L, Integer.MAX_VALUE, kit).data = items;
        if (before != null && !Arrays.equals(before, items)) zone(kit);
    }

    /**
     * A tick after a bag pickup, what is still not in the inventory went into the open bag (owner
     * 1.2).
     */
    void bagSettle() {
        Observation e = engine.inbox.peek(BAG_GAIN);
        if (e == null || e.owner != engine.inbox.owner || client.getTickCount() <= e.at) return;
        long missing = e.held + e.qty - snapshotFactory.capture(false, false).quantityOf(e.item);
        if (missing > 0L) engine.bagged(e, Math.min(missing, e.qty));
        engine.inbox.take(BAG_GAIN, e.item, e.qty, v -> v == e);
    }

    /** A one-tick bag observation; the source's inventory baseline bounds a split pickup. */
    void bagGain(int item, long qty, long held) {
        Observation e = engine.inbox.put(BAG_GAIN, item, qty, 5, null);
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
        OfferSlots.Snapshot snapshot =
                OfferSlots.Snapshot.fromOffer(event.getSlot(), event.getOffer());
        Optional<OfferSlots.Transition> observed = geOfferLedger.observe(snapshot);
        if (observed.isPresent()) handleGeOfferTransition(observed.get());
    }

    void handleGeOfferTransition(OfferSlots.Transition transition) {
        int observedItem =
                transition.current.itemId > 0 || transition.previous == null
                        ? transition.current.itemId
                        : transition.previous.itemId;
        // Observation only: custody owns offer-based classification through its item- and
        // side-scoped offer records. A passive progress or slot transition must never arm
        // MARKET context for whatever inventory change settles next.
        engine.noteGeOffer(
                transition, observedItem > 0 ? itemName(observedItem) : "", SafeMath.now());
        // Fill progress is evidence only; money is booked from the settled inventory movement.
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        // Rune pouch contents live in varbits, not an item container. A change there is
        // an inventory change for accounting: casting from the pouch is a cost, filling
        // it from the inventory nets to zero.
        if (event == null
                || (!config.includeRunePouch()
                                || !ContainerSnapshotFactory.isPouchVarbit(event.getVarbitId()))
                        && event.getVarbitId() != VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY) {
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
        charges.spotAnims(event.getActor().getSpotAnims(), SafeMath.now());
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        if (skip(event) || !(event.getActor() instanceof NPC)) return;
        Hitsplat hitsplat = event.getHitsplat();
        // isMine sees every target of one swing (owner 2026-09-29): a scythe sweep's second
        // and third hits land on NPCs the player never "interacts" with.
        if (hitsplat == null || !hitsplat.isMine()) return;
        charges.onHit(hitsplat.getAmount(), SafeMath.now());
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (skip(event) || event.getActor() != client.getLocalPlayer()) return;
        charges.onAnimation(event.getActor().getAnimation(), SafeMath.now());
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (skip(event)) return;
        String message = normalize(event.getMessage());
        long now = SafeMath.now();
        // Uncharging Bryophyta's staff destroys its runes; its dialog says how many (recorded
        // 2026-10-09).
        var emptied = GameData.pattern("bru").matcher(message);
        if (emptied.matches())
            charges.emptied(ChargeCheck.parseCount(emptied.group(1)), now);
        // A refused pendant teleport (already at that location) books no charge; its line arrives
        // as a "click here to continue" box (MESBOX), so it is matched before the chat-type gate
        // and the estimate its click made is removed through the announced path (owner live
        // 2026-10-13).
        if (GameData.pattern("pendant-refused").matcher(message).matches())
            charges.refusedTeleport(now);
        // The Vyre well is refundable storage for scythe charges (owner live 2026-10-13): filling
        // it with vials and blood runes moves the player's own items, and the scythe's charge
        // estimates book the use. Its line arrives as a message box too.
        if (GameData.pattern("vyre-well-fill").matcher(message).matches())
            zone(msg("vyre-well"));
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.SPAM && type != ChatMessageType.GAMEMESSAGE) return;
        // Each potion a chug drinks prints its own barrel line (Dude Where's My Stuff, GIM Hub).
        var chug = GameData.pattern("chug").matcher(message);
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
        if (GameData.pattern("deposit-pool").matcher(message).matches()) {
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
        var fee = GameData.pattern("death-fee").matcher(message);
        var bank = GameData.pattern("bank-payment").matcher(message);
        var coffer = GameData.pattern("coffer-payment").matcher(message);
        if (fee.matches()) {
            engine.inbox.drop(FEE);
            engine.bookDeathFee(
                    ChargeCheck.parseCount(fee.group(1)), msg("death-s"), now);
        } else if (bank.matches() && client.getTickCount() == instancePaidTick) {
            engine.bookDeathFee(
                    ChargeCheck.parseCount(bank.group(1)), msg("instance-fee"), now);
        } else if (bank.matches() && engine.deathReclaim.isAwaitingReclaim()) {
            engine.inbox.put(
                                    FEE,
                                    -1,
                                    ChargeCheck.parseCount(bank.group(1)),
                                    10,
                                    "Reclaim fee (bank)")
                            .at =
                    client.getTickCount();
        } else if (bank.matches() && type == ChatMessageType.SPAM
                && client.getTickCount() - instancePaidTick > 1) {
            engine.bookDeathFee(
                    ChargeCheck.parseCount(bank.group(1)), msg("instance-fee-bank"), now);
        } else if (coffer.matches()) {
            instancePaidTick = client.getTickCount();
            engine.bookDeathFee(
                    ChargeCheck.parseCount(coffer.group(1)), msg("instance-fee"), now);
        }
        var caught = GameData.pattern("bag").matcher(message);
        if (caught.matches()
                && lookup != null
                && engine.baseline != null
                && snapshotFactory.bagOpen()) {
            // A catch or a cut an open barrel or basket took never reaches the inventory (owner
            // 1.2).
            int found = caughtItem(caught.group(2), lookup.find(caught.group(2)));
            if (found >= 0) {
                bagGain(
                        found,
                        caught.group(1) == null ? 1L : Long.parseLong(caught.group(1)),
                        engine.baseline.quantityOf(found));
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
        if (ActionSignals.isSpared(message)) {
            // Chaos altar bone-save is not a spend — inventory may keep the bone.
            markActivity();
            return;
        }
        if (!ActionSignals.isChatSpend(message)) return;
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
        boolean consumption = ActionSignals.isSpend(option);
        boolean gather = ActionSignals.namesActivityOnly(option);
        if (gather || consumption) {
            // A genuine action can produce an item without XP or an animation
            // (field crops). Capture its pre-action inventory on automatic start.
            markGameplayActivity(true);
        }
        interactionContextTracker.onMenuOptionClicked(event);
        markActivity();
        String target = normalize(event.getMenuTarget());
        String joined = option + " " + target;
        int itemId = menuItemId(event);
        int ttl = consumeTicks();
        boolean ge = Widgets.isGeOpen(client);
        engine.observeChest(option, target);
        charges.onMenuOptionClicked(event, option, target);
        // A loss-only "Offer" click with the Grand Exchange open is a sell placement: the
        // inventory removal is GE evidence, not a prayer/altar spend. The proven GE UI context
        // disambiguates the shared "Offer" verb; a generic Offer elsewhere keeps its spend intent.
        if (consumption && "offer".equals(option) && ge) {
            trading(
                    CORRELATION_TICKS,
                    msg("grand-exchange-offer") + (target.isEmpty() ? "" : " " + target));
            return;
        }
        // A Collect interaction with the open Grand Exchange is bounded collection evidence. It
        // never owns a movement by itself; it lets exact custody settlements correlate.
        if (option.startsWith("collect") && ge) {
            engine.noteGeCollect(SafeMath.now());
            trading(CORRELATION_TICKS, msg("grand-exchange-collect"));
            return;
        }
        if ("revert".equals(option) || "dismantle".equals(option))
            engine.dismantle(itemId);
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
            engine.noteConsume(
                    itemId,
                    ttl,
                    false,
                    ActionReader.verbOf(option),
                    spellLabel(event, option));
        } else if ("drop".equals(option)) {
            // Match consume TTL so dump-then-settle does not miss power-train drops.
            engine.noteDrop(itemId, ttl);
        } else if ("take".equals(option)
                && event.getMenuAction() != null
                && event.getMenuAction().name().startsWith("GROUND_ITEM_")) {
            // A ground item's id is the entry's identifier; a pickup of fired ammo is recovery.
            int id = snapshotFactory.canonical(event.getId());
            Observation e = engine.inbox.put(GROUND_CLICK, id, 1L, 5, null);
            e.at = client.getTickCount();
            e.x = event.getParam0();
            e.y = event.getParam1();
            e.held = snapshotFactory.capture(false, false).quantityOf(id);
        } else if ("destroy".equals(option)) {
            // Irreversible loss — consume-style intent, never recoverable own-drop.
            engine.noteConsume(itemId, ttl, true);
        }
        // Use Tinderbox ↔ Logs (inventory Use): a spend intent for the fuel, same as Light.
        if (ActionSignals.lightsFire(option, target)) {
            engine.noteConsume(itemId, ttl, false, ActionKind.SUPPLIES);
        }
        // Use bones/ashes ↔ altar (PoH / wildy): an offer spend intent; Prayer XP confirms it.
        if (ActionSignals.offersBones(option, target) || ActionSignals.isOffering(option, target)) {
            engine.noteConsume(itemId, ttl, false, ActionKind.OFFER);
        }
        // Agility dispensers (Wilderness course rewards, Brimhaven tickets): the claim keeps the
        // course title; the rewards book as ordinary inventory gains.
        if (AgilityCourses.isDispenser(target)) {
            Player local = client.getLocalPlayer();
            String course =
                    local == null || local.getWorldLocation() == null
                            ? null
                            : AgilityCourses.courseName(local.getWorldLocation().getRegionID());
            activity.observe(course == null ? "Agility" : course, true);
        }
        // Farming / gather object actions: their skill's first XP names the activity (accounting is
        // inventory GAIN).
        if (gather) {
            activity.gatherSkill = ActionSignals.gatherActivity(option);
        } else if (!consumption) {
            // Transform/production options (station or inventory) arm PRODUCTION context so the
            // paired receipt settles without double count; XP re-arms it inside the window.
            String skill = ActionSignals.transformSkill(option);
            if (!skill.isEmpty()) activity.armProduction(skill);
            // Item on item (a knife on logs, a herb into a vial) starts a Make-X the space bar
            // confirms
            // with no click, and its Make button may come a minute later: the run's own skill XP
            // arms it
            // (owner 2026-10-08, fletching went to Review).
            else if (event.getMenuAction() == MenuAction.WIDGET_TARGET_ON_WIDGET
                    || option.startsWith("make")) activity.clearProduction(SafeMath.now());
        }
        BossRetrievalCatalogue.Service retrieval =
                BossRetrievalCatalogue.serviceFor(option, target);
        if (retrieval != null) {
            // Long window: dialogue + confirm + coins + items can take several ticks.
            boolean armed =
                    engine.noteReclaimIntent(
                            retrieval, max(CORRELATION_TICKS * 4, stableTicks(30)));
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
        if (Widgets.isBankOpen(client)
                && (Phrases.has(joined, "bank", "deposit")
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
            UtilityContainerCatalogue.Kind kind =
                    UtilityContainerCatalogue.classify(option, depositBox);
            if (UtilityContainerCatalogue.isNeutral(kind)) {
                String note =
                        "Container "
                                + kind.name().toLowerCase(Locale.ROOT)
                                + " ("
                                + containerFamily
                                + ")";
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
            trading(
                    CORRELATION_TICKS,
                    (ge ? "Grand Exchange " : "")
                            + option
                            + (target.isEmpty() ? "" : " " + target));
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
        String npcName = event.getNpc() == null ? "NPC loot" : event.getNpc().getName();
        if (blank(npcName)) npcName = "NPC loot";
        HudBuilder builder = hud;
        if (!HudTray.minion(npcName)) {
            activity.observe(npcName, true);
            engine.recordAction(npcName);
            if (builder != null)
                builder.tray().kill(npcName, SafeMath.now(), builder.keepSession());
        }
        engine.markLoot(
                expectedLoot(event.getItems()), lootMemoryTicks(), "Loot from " + npcName, npcName);
        offerReward(false, npcName, event.getItems(), 1);
    }

    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event) {
        if (skip(event)) return;
        String npcName =
                event.getComposition() == null ? "NPC loot" : event.getComposition().getName();
        if (blank(npcName)) npcName = "NPC loot";
        offerReward(false, npcName, event.getItems(), 1);
    }

    @Subscribe
    public void onLootReceived(LootReceived event) {
        // Optional: only fires when Loot Tracker is enabled. Deduped against
        // NpcLootReceived / ServerNpcLoot for the same kill.
        if (skip(event) || event.getItems() == null) return;
        String name = nonBlank(event.getName(), "Reward");
        boolean pending =
                event.getType() == LootRecordType.EVENT && RewardChestCatalogue.isRewardChest(name);
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
        engine.markPkLoot(expectedLoot(event.getItems()), lootMemoryTicks(), SafeMath.now());
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
        boolean localToPlayer =
                event.getSource() == client.getLocalPlayer() && event.getTarget() instanceof Player;
        boolean playerToLocal =
                event.getTarget() == client.getLocalPlayer() && event.getSource() instanceof Player;
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
        Player localPlayer = client.getLocalPlayer();
        // A staged death in the Gauntlet or the PvP tutorial loses nothing (owner 2026-10-09).
        if (!canIngest()
                || localPlayer == null
                || event.getActor() != localPlayer
                || neutralZoneTracker.isInside()
                || MinigameRegionHints.isPvpTutorial(regionId())) {
            return;
        }
        // Preserve the pre-death held-key evidence before any disposition branch.
        // A later stabilized key loss can then close its audit row without treating
        // the unowned manifest as a loss.
        engine.beginKeyDeathSettle(
                KeyClaims.keyQuantities(client.getItemContainer(InventoryID.INV)));
        // Ownership input: this canonical INV/WORN read is intersected with settled negative
        // item flows by the engine.
        ContainerSnapshot deathHeldItems =
                snapshotFactory == null
                        ? null
                        : snapshotFactory.captureDeathHeld(config.includeEquipment());
        // Only a confident dangerous context plus recent player combat is a PvP death; a
        // safe arena never is; elsewhere require NPC context before the reclaim window.
        boolean pvmContext =
                interactionContextTracker != null && interactionContextTracker.hasPvmContext();
        DeathOutcome disposition =
                deathOutcome(
                        config.enablePkTracking(),
                        recentPlayerCombatTicks,
                        pvmContext,
                        pvp.sample(localPlayer));
        // PvM or PK: drop the NPC/activity soft-stick so the killer cannot title the next owner.
        interactionContextTracker.clear();
        characterIdleTracker.clear();
        activity.clearOnDeath();
        refreshPresentation();
        if (disposition == DeathOutcome.TRACK_PK) {
            markActivity();
            activity.observe("PKing", true);
            engine.markPkDeath(SafeMath.now());
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
        engine.markPvmDeath(
                consumeTicks(), deathHeldItems == null ? null : deathHeldItems.quantities);
        // Persist the physical death whitelist immediately. A restart before the first
        // inventory wipe must still be able to classify the later loss as a transfer.
        persist();
    }

    static DeathOutcome deathOutcome(
            boolean pkTrackingEnabled,
            int playerCombatTicks,
            boolean pvmContext,
            PvpContext context) {
        if (context.isSafe()) {
            // Safe arenas restore gear; never a PK death, never a reclaim.
            return DeathOutcome.UNCLASSIFIED;
        }
        if (playerCombatTicks > 0) {
            // Player-combat evidence always blocks an unsafe PvM reclaim classification. It
            // books a PK death only with PK accounting on and a confident dangerous context;
            // otherwise the inventory loss follows ordinary accounting.
            return pkTrackingEnabled && context.isDangerous()
                    ? DeathOutcome.TRACK_PK
                    : DeathOutcome.UNCLASSIFIED;
        }
        if (!context.isDangerous()) return DeathOutcome.TRACK_PVM_RECLAIM;
        return pvmContext ? DeathOutcome.TRACK_PVM_RECLAIM : DeathOutcome.UNCLASSIFIED;
    }

    Map<Integer, Long> expectedLoot(Collection<ItemStack> items) {
        var expected = new HashMap<Integer, Long>();
        for (ItemStack item : items) {
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
        long now = SafeMath.now();
        // Agility is an activity in its own right: the course names it when the region is known.
        Player local = client.getLocalPlayer();
        String course =
                skill != Skill.AGILITY || local == null || local.getWorldLocation() == null
                        ? null
                        : AgilityCourses.courseName(local.getWorldLocation().getRegionID());
        activity.observeXp(skill.getName(), course, interactionContextTracker.hasPvmContext(), now);
        HudBuilder builder = hud;
        if (builder != null) builder.tray().xp(skill.getName(), now);
        charges.learnEyeCast(skill, client.getTickCount(), now);
    }

    /**
     * The last Take: item, tick and how many the inventory held then; a bag pickup waits a tick.
     */
    /** Reward chest interface → its item container (d22), and the last Bank-all tick. */
    static final Map<Integer, Integer> CHESTS = new HashMap<>();

    static {
        for (String[] row : GameData.rows("bank_all_chests"))
            CHESTS.put(Integer.parseInt(row[0]), Integer.parseInt(row[1]));
    }

    /**
     * "Bank-all" on a reward chest banks loot that never reaches the inventory; it is booked like a
     * pickup, once per click (research 1.2).
     */
    void bankAll(int chestInterface) {
        Integer chest = CHESTS.get(chestInterface);
        ItemContainer loot = chest == null ? null : client.getItemContainer(chest);
        if (loot == null) return;
        engine.inbox.drop(CHEST);
        Observation e = engine.inbox.add(CHEST, -1, 1L, 10, null);
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
        if (e == null
                || e.owner != engine.inbox.owner
                || loot == null
                || client.getTickCount() - e.at > 10) return;
        Map<Integer, Long> now = counts(loot);
        for (Observation observation : new ArrayList<>(engine.inbox.open)) {
            if (observation.kind != CHEST
                    || observation.item < 0
                    || observation.owner != engine.inbox.owner) continue;
            long gone = observation.qty - now.getOrDefault(observation.item, 0L);
            if (gone > 0L) engine.bagged(observation, gone);
        }
        int until = e.until;
        engine.inbox.open.removeIf(v -> v.kind == CHEST && v.item >= 0);
        chestSnapshot(now, until);
    }

    /** Each measured chest stack is quantity proof within the clicked chest's window. */
    void chestSnapshot(Map<Integer, Long> counts, int until) {
        counts.forEach(
                (item, qty) -> engine.inbox.add(CHEST, item, qty, until - engine.inbox.now, null));
    }

    Map<Integer, Long> counts(ItemContainer container) {
        var counts = new HashMap<Integer, Long>();
        for (Item item : container.getItems()) {
            if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
                counts.merge(
                        snapshotFactory.canonical(item.getId()),
                        (long) item.getQuantity(),
                        Long::sum);
        }
        return counts;
    }

    /** The taken item vanished beside you while an open bag was carried: it may be in the bag. */
    @Subscribe
    public void onItemDespawned(ItemDespawned event) {
        Player local = client.getLocalPlayer();
        TileItem item = event.getItem();
        Point at = event.getTile() == null ? null : event.getTile().getSceneLocation();
        Observation e = engine.inbox.peek(GROUND_CLICK);
        if (!canIngest()
                || e == null
                || e.owner != engine.inbox.owner
                || local == null
                || item == null
                || at == null
                || at.getX() != e.x
                || at.getY() != e.y
                || snapshotFactory.canonical(item.getId()) != e.item
                || client.getTickCount() - e.at > 5
                || local.getWorldLocation().distanceTo(event.getTile().getWorldLocation()) > 1
                || item.getOwnership() != TileItem.OWNERSHIP_SELF) return;
        if (!snapshotFactory.bagOpen()) {
            // The game's ammo-picking setting can put picked-up darts straight into the blowpipe
            // (OSRS Wiki). Decide two ticks later, once the pack has had its chance to gain them.
            if (e.item == charges.blowpipeDart
                    && engine.inbox.take(GROUND_CLICK, e.item, 1L, v -> v == e) > 0L) {
                Observation pick =
                        engine.inbox.add(PIPE_PICKUP, e.item, item.getQuantity(), 5, null);
                pick.at = client.getTickCount();
                pick.held = e.held;
            }
            return;
        }
        long qty = engine.inbox.reserve(PRIVATE_LOOT, e.item, item.getQuantity(), LOOT_RESERVE);
        if (qty > 0L && engine.inbox.take(GROUND_CLICK, e.item, 1L, v -> v == e) > 0L)
            bagGain(e.item, qty, e.held);
    }

    /** A dart pickup the pack never gained went into the blowpipe: it recovers the shot. */
    void pipePickups() {
        for (Observation pick : engine.inbox.all(PIPE_PICKUP)) {
            if (pick.owner != engine.inbox.owner || client.getTickCount() - pick.at < 2) continue;
            long quantity = engine.inbox.take(PIPE_PICKUP, pick.item, pick.qty, v -> v == pick);
            if (snapshotFactory.capture(false, false).quantityOf(pick.item) <= pick.held)
                engine.pickUpIntoPipe(pick.item, quantity, SafeMath.now());
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
        // Any ironman account type (iron, ultimate, hardcore, group) reads 1-6 (OSRS Wiki, RuneLite
        // gameval).
        valuation.alch =
                config.ironmanAlch()
                        && client.getVarbitValue(net.runelite.api.gameval.VarbitID.IRONMAN) > 0;
        Observation fee = engine.inbox.peek(FEE);
        if (fee != null && fee.owner == engine.inbox.owner && client.getTickCount() - fee.at > 2) {
            engine.fee(fee, SafeMath.now());
        }
        bagSettle();
        pipePickups();
        engine.tickAutoStart(SafeMath.now());
        advanceGeOfferSeed();
        activity.syncSession();
        boolean bankOpen = Widgets.isBankOpen(client);
        Player localPlayer = client.getLocalPlayer();
        pvp.onGameTick(localPlayer);
        samplePvpState();
        WorldPoint tile = localPlayer == null ? null : localPlayer.getWorldLocation();
        // An instance's real map region, as the Gauntlet and arena ids are (owner 2026-10-09).
        if (tile != null) applyNeutralZone(regionId());
        servantTalk();
        charges.drank(SafeMath.now());
        // An NPC conversation or its options on screen, or a shop window: a coins-for-items
        // change is a purchase, an items-for-coins change a sale (owner 1.3.4).
        if (Widgets.visible(client, InterfaceID.ChatLeft.UNIVERSE)
                || Widgets.visible(client, InterfaceID.Chatmenu.UNIVERSE)
                || Widgets.isShopOpen(client)) {
            engine.inbox.put(DIALOGUE, 0, 1L, stableTicks(4), null);
        }
        boolean activeAnimation = localPlayer != null && localPlayer.getAnimation() != -1;
        boolean activeInteraction = localPlayer != null && localPlayer.getInteracting() != null;
        if (activeAnimation || activeInteraction) markGameplayActivity();
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
        // Character idle drops a leftover NPC hint so leaving idle does not revive the last kill.
        if (characterIdleTracker.onGameTick()
                && config.autoActivityDetection()
                && !"General".equalsIgnoreCase(activity.detectedActivity)) {
            activity.resetToGeneral(true);
        }
        pulse();
        if (client.getLocalPlayer() != null
                && client.getLocalPlayer().getInteracting() instanceof Player) {
            // Internal death-safety evidence only. PK transaction and activity
            // accounting still obeys enablePkTracking.
            recentPlayerCombatTicks = 20;
        } else if (recentPlayerCombatTicks > 0) {
            recentPlayerCombatTicks--;
        }
        long now = SafeMath.now();
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
        Transaction transaction =
                engine.processIfDirty(
                        snapshotFactory.capture(
                                config.includeEquipment(), config.includeRunePouch()),
                        now,
                        row -> {
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
    void applyNeutralZone(int regionId) {
        // Pete Kayer's PvP tutorial lends gear and supplies inside and takes them back as you
        // leave:
        // nothing there, or just after, is profit or loss (owner 2026-10-09, coinflow 1.6.0).
        if (MinigameRegionHints.isPvpTutorial(regionId))
            engine.markTransfer("PvP tutorial", stableTicks(8));
        NeutralZoneTracker.Signal signal = neutralZoneTracker.onRegion(regionId);
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
        if (!GameData.pattern("servant")
                        .matcher(normalize(Widgets.text(client, InterfaceID.ChatLeft.NAME)))
                        .matches()
                || !client.getTopLevelWorldView().isInstance()) return;
        String line = normalize(Widgets.text(client, InterfaceID.ChatLeft.TEXT));
        engine.inbox.put(SERVANT, 0, 1L, stableTicks(4), null);
        if (line.contains("sawmill") && !line.contains("bank"))
            engine.inbox.put(SAWMILL, 0, 1L, 200, null);
    }

    int regionId() {
        int regionId = -1;
        try {
            if (client != null
                    && client.getLocalPlayer() != null
                    && client.getLocalPlayer().getWorldLocation() != null) {
                regionId =
                        WorldPoint.fromLocalInstance(
                                        client, client.getLocalPlayer().getLocalLocation())
                                .getRegionID();
            }
        } catch (Exception ignored) {
            // Live signals are best-effort.
        }
        return regionId;
    }

    /** Presentation-only duplicate guard for one loot batch reported by several RuneLite events. */
    String lastLootBatch = "";

    long lastLootAt;

    static String lootKey(Collection<ItemStack> items) {
        var key = new StringBuilder();
        if (items != null) {
            for (ItemStack item : items)
                key.append(item.getId()).append('x').append(item.getQuantity()).append(',');
        }
        return key.toString();
    }

    /** Unconfirmed loot goes to presentation only; pickup settles through the engine. */
    void offerReward(
            boolean pending, String sourceName, Collection<ItemStack> items, int multiplicity) {
        long now = SafeMath.now();
        boolean chest =
                sourceName != null && sourceName.toLowerCase(Locale.ROOT).contains("loot chest");
        if ((pending || chest) && ClueCostPairing.looksLikeClueActivity(sourceName)) {
            engine.beginCluePath("loot:" + sourceName, sourceName);
        }
        lootObserved(pending || chest, sourceName, items, multiplicity, now);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event == null || event.getGroup() == null) return;
        if (GroundItemsPrefs.GROUP.equals(event.getGroup())
                || "grounditemsplugin".equals(event.getKey())) {
            // Ground Items turned on or off (RuneLite saves that as runelite.grounditemsplugin,
            // 1.3.1).
            // The Ground Items list preferences changed: re-read the filter authority and
            // repaint the sidebar. Presentation only; stored receipts never change.
            lootPresentationFilter.refresh();
            refreshPresentation();
            return;
        }
        if (!GpManagerConfig.GROUP.equals(event.getGroup())) return;
        if ("includeEquipment".equals(event.getKey())
                || "includeRunePouch".equals(event.getKey())) {
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
        SidebarPanel panel = activityPanel;
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

    void markActivity() {
        if (!canIngest()) return;
        long now = SafeMath.now();
        // Inventory/bank context alone resumes a saved Idle pause but never Manual/Stop and
        // never auto-starts a tracker (startup needs gameplay activity).
        if (engine.resume(now, IDLE)) rebaseline();
    }

    void markGameplayActivity() {
        markGameplayActivity(false);
    }

    void markGameplayActivity(boolean beforeItemChange) {
        if (!canIngest()) return;
        long now = SafeMath.now();
        if (engine.autoStart(now)) {
            if (beforeItemChange && client.getItemContainer(InventoryID.INV) != null) {
                engine.setBaseline(
                        snapshotFactory.capture(
                                config.includeEquipment(), config.includeRunePouch()));
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
        Widget clicked = event.getWidget();
        if (clicked != null) {
            ActionLabel direct =
                    ActionLabel.fromSpellMenu(
                            option,
                            clicked.getId(),
                            InterfaceID.MAGIC_SPELLBOOK,
                            clicked.getText(),
                            clicked.getName());
            if (direct != null) return direct;
        }
        if (!ActionSignals.isWidgetAction(event.getMenuAction())) return null;
        Widget selected = client.getSelectedWidget();
        return selected == null
                ? null
                : ActionLabel.fromSpellMenu(
                        option,
                        selected.getId(),
                        InterfaceID.MAGIC_SPELLBOOK,
                        selected.getText(),
                        selected.getName());
    }

    /**
     * Inventory Bury/Drop/Eat menus sometimes report {@code getItemId() == -1}. Fall back to
     * item-op / widget / inventory-slot ids (Supplies Tracker pattern) and canonicalize so intent
     * matches snapshot item ids.
     */
    int menuItemId(MenuOptionClicked event) {
        if (event == null) return -1;
        int itemId = event.getItemId();
        if (itemId < 0 && event.isItemOp()) itemId = event.getId();
        if (itemId < 0) {
            Widget widget = event.getWidget();
            if (widget != null) itemId = widget.getItemId();
        }
        // CC_OP inventory Eat/Drink often leaves getItemId() == -1; read the clicked slot.
        if (itemId < 0) itemId = slotItemId(event.getParam0());
        if (itemId < 0) return -1;
        return lookup == null ? itemId : lookup.canon(itemId);
    }

    int slotItemId(int slot) {
        if (slot < 0) return -1;
        try {
            ItemContainer inventory = client.getItemContainer(InventoryID.INV);
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

    String itemName(int itemId) {
        try {
            ItemComposition composition = lookup == null ? null : lookup.composition(itemId);
            return composition == null ? "Item " + itemId : composition.getName();
        } catch (RuntimeException ex) {
            return "Item " + itemId;
        }
    }

    String normalize(String text) {
        // RuneLite's precompiled tag pattern: this runs on every chat line, click and tick (1.3.1).
        return text == null ? "" : Text.removeTags(text).trim().toLowerCase(Locale.ROOT);
    }

    @Provides
    GpManagerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GpManagerConfig.class);
    }
}
