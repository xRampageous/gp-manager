package com.gpmanager;
import static java.lang.Integer.parseInt;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.InventoryID;

import static com.gpmanager.ChargeCheck.ChargedItem.*;
import static com.gpmanager.PriceSource.*;
import static java.util.Locale.*;
import java.util.regex.*;
import static com.gpmanager.FlowFilters.*;
import com.gpmanager.SavedState.PendingClaim;
import com.gpmanager.ChargeDelta.CheckResult;
import net.runelite.api.gameval.*;

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
import static net.runelite.api.gameval.InterfaceID.*;
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
import java.util.Collection;
import static com.gpmanager.SafeMath.*;
import java.util.function.*;
import javax.inject.*;
import lombok.*;

@PluginDescriptor(
        name = "GP Manager", internalName = "gp-manager", description =
                "Passive profit tracking with raids, PvP and a correctable ledger.",
        tags = {"profit", "gp", "loot", "costs", "supplies", "sessions", "pking", "pk", "hud", "tracker", "money", "raids"})
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
    @Inject ContainerSnapshotFactory containers;
    @Inject ItemManager itemManager;

    /** Accounting's item lookups; ItemManager itself stays for sprites. */
    @Inject ItemLookup lookup;

    @Inject SpriteManager spriteManager;
    @Inject Engine engine;
    @Inject SessionRepository repository;
    @Inject Persistence storage;
    @Inject InteractionContextTracker targets;
    @Inject CharacterIdleTracker idle;
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
    @Inject OfferSlots offers;
    @Inject PvpContextReader pvp;
    @Inject ClientThread clientThread;
    @Inject OverlayManager overlayManager;

    /** HUD+ overlay and the builder the sidebar feeds each tick; null while not installed. */
    volatile HudBuilder hud;
    RaidHistory.Native raidNative;
    RaidHistory.Native raidNative() {
        if (raidNative == null) raidNative = new RaidHistory.Native(this);
        return raidNative;
    }

    HudOverlay hudOverlay;
    @Inject ClientToolbar clientToolbar;

    /**
     * Existing Ground Items preference authority; the sidebar reads its decision, never re-derives
     * it.
     */
    @Inject LootPresentationFilterService filter;

    /** The restored sidebar, bound as a presentation host; null while presentation is absent. */
    volatile SidebarPanel sidebar;

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
            var panel = new SidebarPanel(engine, config, itemManager, repository, storage);
            panel.spriteManager = spriteManager;
            GrindsController.bondPrice = () -> bondGp;
            panel.mutationOwner = clientThread::invoke;
            panel.bindPvp(() -> currentPvp);
            panel.bindLoggedIn(() -> loggedIn);
            panel.bindHopping(() -> previousState == HOPPING);
            var builder = new HudBuilder(config, (id, quantity) -> itemManager.getImage(id, quantity, false));
            panel.bindHud(builder);
            hudOverlay = new HudOverlay(builder, config);
            overlayManager.add(hudOverlay);
            hud = builder;
            filter.refresh();
            panel.bindFilter(new RecentFilter() {
                        public boolean isFlowIncluded(Flow flow) {
                            return filter.isFlowIncluded(flow, config.lootPresentationFilter());
                        }

                        public boolean alwaysShows(Flow flow) {
                            return LootPresentationFilterService.listState(config.alwaysShowItems(), "", flow.name,
                                            Math.abs(flow.quantity)) == LootPresentationFilterService.HIGHLIGHTED;
                        }

                        public Color highlight(String name, long quantity) {
                            return filter.highlight(name, quantity, config.lootPresentationFilter());
                        }

                        public int recentRows() {
                            return config.recentRows();
                        }

                        public String version() {
                            return config.lootPresentationFilter() + "|" + filter.fingerprint + "|"
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
                            String entry = charge || ActionReader.dose(name) > 0
                                            ? ActionReader.doseBase(name) + "*" : name;
                            var names = new ArrayList<>(Text.fromCSV(list));
                            if (!names.contains(entry)) names.add(entry);
                            configManager.setConfiguration(GpManagerConfig.GROUP,
                                    charge ? "hiddenRecentCharges" : "hiddenRecentItems", Text.toCSV(names));
                        }
                    });
            sidebar = panel;
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
        sidebar = null;
        navigation.uninstall(NavButton.toolbarHost(clientToolbar));
    }

    /**
     * Samples the client-thread PvP facts for the sidebar context row. Called from the tick, where
     * client access is legal; the EDT reads the cached sample through {@code currentPvp}.
     */
    void samplePvpState() {
        var local = client.getLocalPlayer();
        int skull = local == null ? SkullIcon.NONE : local.getSkullIcon();
        boolean possible = pvp.context.isDangerous(), skulled = skull != SkullIcon.NONE;
        boolean protect = client.getVarbitValue(PRAYER_PROTECTITEM) == 1;
        if (!possible) held = bagHeld = emptyList();
        else if (client.getTickCount() % 2 == 0 && valuation != null && containers != null) {
            held = valuation.value(containers.captureDeathHeld(true).items);
            bagHeld = valuation.value(lootingBag());
        }
        long bag = 0;
        // A death loses the looting bag whole: nothing in it is ever among the kept items.
        for (Flow stack : bagHeld) bag = sum(bag, multiply(PvpState.deathValue(stack), stack.quantity));
        currentPvp = new PvpState(possible, skulled, protect,
                        possible ? sum(PvpState.risk(held, skulled, protect), bag) : 0);
    }

    List<Flow> bagHeld = emptyList();

    /**
     * What a carried looting bag holds: its contents when the client last showed them, plus the
     * confirmed pickups, deposits and dispenser rewards since.
     */
    Map<Integer, Long> lootingBag() {
        if (!carries(ItemID.LOOTING_BAG) && !carries(ItemID.LOOTING_BAG_OPEN)) return Map.of();
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
        if (raidNative != null) raidNative.booked(transaction,false,0);
        var builder = hud;
        if (builder != null) {
            builder.tray.big = filter.big(config.lootPresentationFilter());
            builder.tray.booked(transaction, now, builder.keepSession(), client == null ? now : client.getTickCount());
        }
        refreshPresentation();
    }

    void lootObserved(boolean pending, String source, Collection<ItemStack> items, int multiplicity, long now) {
        var builder = hud;
        var key = new StringBuilder();
        if (items != null)
            for (var item : items)
                key.append(item.getId()).append('x').append(item.getQuantity()).append(',');
        String batch = source + ":" + pending + ":" + multiplicity + ":" + key;
        // Only reward windows ride the tray (ground loot shows once it is picked up). One reward
        // can arrive through several RuneLite events; show it once.
        // A clue casket's rewards reach the inventory at once and ride the tray as one casket row.
        if (builder != null && pending && items != null && !lower(source).contains("clue")
                && !(batch.equals(lastLootBatch) && now - lastLootAt < 1200)) {
            lastLootBatch = batch;
            lastLootAt = now;
            for (var item : items)
                // Named and priced by the one valuation route; nothing is counted from this.
                for (Flow flow : valuation.value(singletonMap(lookup.canon(item.getId()),
                                        (long) item.getQuantity() * max(1, multiplicity))))
                    builder.tray.observed(flow.item, flow.name, flow.quantity, flow.unitPrice, now,
                                    builder.keepSession());

        }
        refreshPresentation();
    }

    void ownerScopeChanged(String scope) {
        var builder = hud;
        if (builder != null) builder.tray.clear();
        var panel = sidebar;
        if (panel == null) return;
        if (scope == null || "held".equals(scope) || "switching".equals(scope)) panel.suspend();
        else panel.resume();
    }

    void interactionChanged(String name, boolean combat) {
        // Presentation policy: the target never retitles the run; HUD+ shows it as its header,
        // and fighting another NPC starts that NPC's tray streak.
        var builder = hud;
        if (builder != null && !HudTray.minion(name))
            builder.interaction(name, combat);
    }

    /** Every-tick signals: live values only, never a full page rebuild. */
    void pulse() {
        var panel = sidebar;
        if (panel != null) panel.tick();
    }

    int saveTicks, combat;

    Map<Skill, Integer> experience = new EnumMap<>(Skill.class);
    NeutralZoneTracker zones = new NeutralZoneTracker();
    int seedTicks;

    /** The tick a private instance was paid from the coffer; its bank share comes that tick. */
    int instanceTick = -1;

    /** Rising-edge: clear NPC soft-stick once when bank opens, not every bank tick. */
    boolean bankReset;

    /** One client-thread initialization when this plugin enable becomes ready. */
    boolean boot;

    /** Owner live 2026-10-13: the login-start option is offered once per client run. */
    boolean autoStart;

    protected void startUp() {
        boot = true;
        autoStart = true;
        JsonCodec.bind(gson);
        boolean online = client.getGameState() == LOGGED_IN;
        loggedIn = online;
        var identityAtStart = storage.resolveCurrentIdentity();
        storage.initAsync(() -> {
                    try {
                        repository.initialize(getPluginDirectory());
                    } catch (IOException ex) {
                        throw new IllegalStateException(msg("startup-failed"), ex);
                    }
                    storage.start();
                    if (online)
                        storage.switchIdentityAsync(identityAtStart);
                });
        // History always loads and saves locally, isolated by account.
        if (client.getGameState() == LOGGED_IN) beginOfferSeed();
        installPresentation();
        targets.bindPresentation(this::canIngest, this::interactionChanged);
        // startUp runs on the EDT; world type and the inventory baseline are client-thread reads.
        clientThread.invokeLater(() -> {
                    refreshEconomyState();
                    refreshGloves();
                    if (canIngest()) initLoggedIn();
                });
        pulse();
    }

    protected void shutDown() {
        charges.clearCheck();
        // The durable General tracker and any custom session are saved in
        // place. Shutdown must not turn either into history or merge owners.
        engine.pauseForLifecycle(now());
        storage.shutdown();
        experience.clear();
        targets.clear();
        LiveSnapshot.recentKey = emptyList();
        // Clear the sidebar while the engine is still valid, then remove the button.
        ownerScopeChanged("held");
        targets.bindPresentation(null, null);
        removePresentation();
    }

    GameState previousState;

    /**
     * Client-thread world/economy snapshot for automatic market pricing. Ordinary main-game
     * modifiers keep quotes; special economies and an unknown world fail them closed, while manual
     * overrides and face value still work. Never called from persistence/render threads.
     */
    void refreshEconomyState() {
        if (valuation == null) return;
        valuation.setEconomy(client != null && client.getGameState() == LOGGED_IN
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
        return Widgets.isGeOpen(client) || Widgets.visible(client, Trademain.TITLE);
    }

    /** Karamja gloves 1-4 on the hands slot pick the improved tokkul rate. */
    private static final Set<Integer> KARAMJA_GLOVES = Set.of(ItemID.ATJUN_GLOVES_EASY, ItemID.ATJUN_GLOVES_MED,
                    ItemID.ATJUN_GLOVES_HARD, ItemID.ATJUN_GLOVES_ELITE);

    /** Client-thread gloves snapshot; called at login/start and on worn-equipment changes. */
    void refreshGloves() {
        if (valuation == null) return;
        var gear = client == null ? null : client.getItemContainer(WORN);
        Item gloves = gear == null ? null : gear.getItem(EquipmentInventorySlot.GLOVES.getSlotIdx());
        valuation.setGloves(gloves != null && KARAMJA_GLOVES.contains(gloves.getId()));
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        var state = event.getGameState();
        var previous = previousState;
        previousState = state;
        if (state == LOGGED_IN) {
            loggedIn = true;
            // Login or world hop: re-read the economy identity from the client thread.
            refreshEconomyState();
            refreshGloves();
        } else if (state == LOGIN_SCREEN || state == HOPPING) {
            // Forget the coffer balance: the next reading only sets a baseline.
            engine.coffer(-1, 0, false, 0);
            combat = 0;
            loggedIn = state == HOPPING;
            // No world identity: automatic market quotes fail closed until the next login.
            if (valuation != null) valuation.setEconomy(Valuation.Economy.UNKNOWN);
        }
        // LOGGED_IN -> LOADING -> LOGGED_IN is a region change (teleport, instance, stairs), not a
        // login. Re-priming the baseline there swallowed whatever changed on the way -- a teleport
        // tablet breaking never booked. Only the location-bound trackers reset on a load.
        if (state == LOADING) {
            clearLocationState();
            return;
        }
        if (state == LOGGED_IN && previous == LOADING) {
            activity.syncSession();
            pulse();
            return;
        }
        if (state != LOGGED_IN) {
            offers.beginSeed();
            seedTicks = 0;
            charges.resetTransient();
            clearLocationState();
            idle.clear();
        }
        if (state == LOGGED_IN) {
            beginOfferSeed();
            storage.syncIdentityAsync();
            pulse();
        } else if (state == LOGIN_SCREEN || state == HOPPING) {
            experience.clear();
            if (storage.isTrackingReady()) {
                synchronized (engine) {
                    engine.raids.logout(now());
                }
                engine.pauseForLifecycle(now());
                persist();
            }
            if (state == LOGIN_SCREEN) storage.onLogout();
        }
    }

    /**
     * A catch or cut's item: "Raw trout" beats cooked "Trout" in any search order (Codex review
     * 1.2).
     */
    static int caughtItem(String name, List<net.runelite.http.api.item.ItemPrice> found) {
        int plain = -1;
        for (var item : found) {
            // A catch names its raw item and a gem line its uncut one (RuneLite's search matches by substring).
            if (item.getName().equalsIgnoreCase("raw " + name) || item.getName().equalsIgnoreCase("uncut " + name)) return item.getId();
            if (item.getName().equalsIgnoreCase(name)) plain = item.getId();
        }
        return plain;
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event) {
        beginOwnerSwitch();
        refreshEconomyState();
        refreshGloves();
        storage.syncIdentityAsync();
        pulse();
    }

    @Subscribe
    public void onAccountHashChanged(AccountHashChanged event) {
        beginOwnerSwitch();
        if (client.getAccountHash() == -1) {
            storage.hashInvalidatedAsync();
            syncOwnerScope();
        } else
            storage.syncIdentityAsync();
        pulse();
    }

    /**
     * Profile/account identity is changing: transient charge/GE/interaction evidence never crosses
     * owners.
     */
    void beginOwnerSwitch() {
        storage.fence();
        combat = 0;
        beginOfferSeed();
        charges.resetOwner();
        targets.clear();
    }

    /** Region load / logout: location-bound trackers reset; the baseline does not. */
    void clearLocationState() {
        targets.clear();
        zones.reset();
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
        boolean loginAutoStart = autoStart && config.autoStartSession() && config.autoStartOnLogin();
        autoStart = false;
        if (loginAutoStart) engine.autoStart(now);
        // Login restores identity and baseline. Resume LIFECYCLE only (hop/relog) without the
        // option; with it Idle and Recovery come back at the first login too.
        if (engine.session() != null) engine.beginPriming();
        if (boot) {
            boot = false;
            // Seed current XP rather than swallowing the first real XP drop after enabling.
            experience.clear();
            for (var skill : Skill.values())
                experience.put(skill, client.getSkillExperience(skill));
            activity.clearProduction(now);
        }
        if (loginAutoStart)
            engine.resume(now, LIFECYCLE, IDLE, RECOVERY);
        else
            engine.resume(now, LIFECYCLE);
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
        if (engine.live() && client.getItemContainer(INV) != null)
            engine.setBaseline(containers.capture(true, true));
    }

    /** Same-turn owner rebind so a custom start/finish or account switch never lingers one tick. */
    void syncOwnerScope() {
        String scope = storage != null && storage.isTrackingReady()
                        ? String.valueOf(System.identityHashCode(engine.session())) : "held";
        ownerScopeChanged(scope);
    }

    void beginOfferSeed() {
        offers.beginSeed();
        // Hide the prior profile's observations until a fresh complete seed arrives.
        // Give RuneLite time to deliver the documented EMPTY-slot replay and
        // the server-backed active offers before transitions become observable.
        seedTicks = 3;
    }

    void advanceGeOfferSeed() {
        if (seedTicks <= 0 || --seedTicks > 0) return;
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        boolean complete = offers != null && (offers.length == 3 || offers.length == 8);
        if (complete)
            for (int slot = 0; slot < offers.length; slot++) {
                var offer = offers[slot];
                if (offer == null) {
                    complete = false;
                    break;
                }
                this.offers.observe(OfferSlots.Snapshot.fromOffer(slot, offer));
            }

        if (complete) {
            this.offers.finishSeed();
            // Schema-104 custody baseline: resume exact persisted lifecycles, quarantine offers
            // first seen already open, and never replay pre-existing progress as a fill.
            // Progress made while the client was away is taken from the slot once, here.
            engine.seedGeSlots(this.offers.snapshots(), now());
            for (int slot = 0; slot < offers.length; slot++) {
                int id = slot < 6 ? TRADINGPOST_SELL_0 + slot : slot == 6 ? GE_COLLECT_6 : GE_COLLECT_7;
                geBox(id, client.getItemContainer(id));
            }
        } else
            // Do not treat a partial/null slot array as an empty-account baseline.
            // Retry until all eight current slots are observed.
            seedTicks = 1;
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        var items = event.getItemContainer();
        if (!canIngest()) return;
        int container = event.getContainerId();
        raidNative().container(container,items);
        geBox(container, items);
        // The bag's own contents now include what the dispenser added.
        if (container == LOOTING_BAG && items != null) {
            // One hold: a save or the Risk read never sees the stock half replaced.
            synchronized (engine) {
                engine.bag.clear();
                rememberBag(items);
            }
            for (int kind : new int[] {BAG_GAIN, BAG_STORE})
                for (var e : engine.events.all(kind)) e.flag = false;
        }
        if (container == INV && items != null)
            for (var e : engine.events.all(BAG_STORE)) {
                long held = counts(items).getOrDefault(e.item, 0L);
                engine.bagged(e, e.held - held);
                e.held = held;
                e.flag = true;
            }
        boolean inventory = container == INV || container == WORN;
        if (container == WORN) refreshGloves();
        if (!inventory) markActivity();
        if (container == BANK && items != null) {
            long now = now();
            engine.bankContents(counts(items), now, row -> inventoryBooked(row, now));
        }
        kit(container, items);
        chestChanged(container, items);
        if (inventory) {
            // Inventory can fire after the bank widget is gone but before the next
            // GameTick. Close soft transfer evidence first so burial is not latched.
            closeHiddenBank();
            // GE widget visibility is only a UI hint. Offer transitions are recorded by the
            // ledger and matched by item/direction-scoped evidence in the engine; arming MARKET
            // for every inventory callback while the interface is open would steal unrelated
            // loot, shop, and manual inventory changes.
            // Player trade UI open while invent settles → TRADE (Traded), not GAIN.
            if (Widgets.visible(client, Trademain.TITLE))
                engine.markContext(MARKET, marketTicks(), "Player trade");
            engine.markDirty();
        }
    }

    /** Only collection-box inventories enter native GE evidence. */
    void geBox(int id, ItemContainer box) {
        int slot = id >= TRADINGPOST_SELL_0 && id <= TRADINGPOST_SELL_5
                ? id - TRADINGPOST_SELL_0 : id == GE_COLLECT_6 ? 6 : id == GE_COLLECT_7 ? 7 : -1;
        if (slot < 0 || seedTicks > 0 || box == null) return;
        engine.noteGeBox(slot, box.getItems(), i -> lookup == null ? i : lookup.canon(i), now());
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
        Observation old = engine.events.peek(KIT_SNAPSHOT, e -> e.item == id);
        Item[] before = old == null ? null : (Item[]) old.data;
        if (old != null) engine.events.take(KIT_SNAPSHOT, id, 1, e -> e == old);
        engine.events.add(KIT_SNAPSHOT, id, 1, Integer.MAX_VALUE, kit).data = items;
        if (before != null && !Arrays.equals(before, items)) zone(kit);
    }

    /**
     * A tick after a bag receipt, what is still not in the inventory went into an open bag (owner
     * 1.2). One item's receipts share that shortfall once: catches and sack receipts take it first,
     * so a looting-bag pickup never inherits another source's quantity as bag stock.
     */
    void bagSettle() {
        var missing = new HashMap<Integer, Long>();
        var due = engine.events.all(BAG_GAIN);
        due.removeIf(e -> client.getTickCount() <= e.at);
        due.sort((a, b) -> Boolean.compare(a.flag, b.flag));
        for (var e : due) missing.put(e.item, sum(e.qty, missing.getOrDefault(e.item, e.held - inventoryQuantity(e.item))));
        for (var e : due) {
            missing.merge(e.item, -engine.bagged(e, positive(missing.get(e.item))), SafeMath::sum);
            e.qty = 0;
        }
    }

    /** The pack as the last tick left it: a receipt this tick is measured from it, whichever callback came first. */
    Map<Integer, Long> lastPack = Map.of();

    /** A one-tick bag observation per item and receiver; only a looting-bag pickup is bag stock. */
    void bagGain(int item, long qty, boolean looting) {
        Observation e = engine.events.peek(BAG_GAIN, row -> row.item == item && row.flag == looting);
        if (e != null) { e.qty = sum(e.qty, qty); return; }
        e = engine.events.add(BAG_GAIN, item, qty, 5, null);
        e.at = client.getTickCount();
        e.held = lastPack.getOrDefault(item, 0L);
        e.flag = looting;
    }

    boolean carries(int item) {
        var pack = client.getItemContainer(INV);
        return pack != null && pack.contains(item);
    }

    /** Explicit item-on-bag custody; only its later measured inventory removal proves a move. */
    boolean bagStore(MenuOptionClicked event) {
        var action = event.getMenuAction();
        if (action == MenuAction.WIDGET_CONTINUE) return false;
        engine.events.drop(BAG_STORE);
        if (action != MenuAction.WIDGET_TARGET_ON_WIDGET) return false;
        var selected = client.getSelectedWidget();
        var clicked = event.getWidget();
        if (selected == null || clicked == null
                || selected.getId() != Inventory.ITEMS || clicked.getId() != Inventory.ITEMS)
            return false;
        int source = containers.canonical(selected.getItemId()), target = containers.canonical(clicked.getItemId());
        if (source == ItemID.LOOTING_BAG) source = target;
        else if (target != ItemID.LOOTING_BAG) return false;
        long held = inventoryQuantity(source);
        if (source == ItemID.LOOTING_BAG || held <= 0) return false;
        var e = engine.events.add(BAG_STORE, source, held, consumeTicks(), null);
        e.held = held;
        e.flag = true;
        return true;
    }

    void closeHiddenBank() {
        if (!Widgets.isBankOpen(client)) engine.bankClosed();
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
        if (event == null || event.getOffer() == null || event.getSlot() < 0) return;
        // The API replays every slot as EMPTY at login, followed by each offer
        // that the server restores. Keep these as baselines; the ledger is
        // observation-only until that seed is finished.
        var snapshot = OfferSlots.Snapshot.fromOffer(event.getSlot(), event.getOffer());
        var observed = offers.observe(snapshot);
        if (observed.isPresent()) handleGeOfferTransition(observed.get());
    }

    void handleGeOfferTransition(OfferSlots.Transition transition) {
        int observedItem = transition.current.item > 0 || transition.previous == null ? transition.current.item
                        : transition.previous.item;
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
            engine.coffer(event.getValue(), client.getTickCount(), canIngest(), now());
            return;
        }
        // Rune pouch contents live in varbits, not an item container. A change there is
        // an inventory change for accounting: casting from the pouch is a cost, filling
        // it from the inventory nets to zero.
        boolean quiver = event != null
                && (event.getVarpId() == DIZANAS_QUIVER_TEMP_AMMO
                        || event.getVarpId() == DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
        if (event == null || !quiver && !ContainerSnapshotFactory.isPouchVarbit(event.getVarbitId())
                        && !ContainerSnapshotFactory.storedCharges(event.getVarbitId()) || !canIngest()) return;
        markActivity();
        closeHiddenBank();
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
        var type = event.getType();
        // Only game messages can prove an item use or a custody change.
        if (type != ChatMessageType.SPAM && type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.MESBOX) return;
        String message = normalize(event.getMessage());
        long now = now();
        // Uncharging Bryophyta's staff destroys its runes; its dialog says how many (recorded
        // 2026-10-09).
        var emptied = pattern("bru").matcher(message);
        if (emptied.matches())
            charges.emptied(digits(emptied.group(1)), now);
        // A refused pendant teleport (already at that location) books no charge; its line arrives
        // as a "click here to continue" box (MESBOX), so it is matched before the MESBOX return
        // and the estimate its click made is removed through the announced path (owner live
        // 2026-10-13).
        if (pattern("pendant-refused").matcher(message).matches())
            charges.refusedTeleport(now);
        // The Vyre well is refundable storage for scythe charges (owner live 2026-10-13): filling
        // it with vials and blood runes moves the player's own items, and the scythe's charge
        // estimates book the use. Its line arrives as a message box too.
        if (pattern("vyre-well-fill").matcher(message).matches())
            zone(msg("vyre-well"));
        if (type == ChatMessageType.MESBOX) return;
        raidNative().chat(message,now);
        // An Agility dispenser award while a looting bag is open: the bag takes what the pack
        // does not show, so the engine books the difference (owner live 2026-10-16).
        var award = pattern("bag-reward").matcher(message);
        if (award.matches() && carries(ItemID.LOOTING_BAG_OPEN)) {
            var items = new HashMap<Integer, Long>();
            bagAward(award.group(1)).forEach((name, quantity) -> {
                                for (var price : lookup.find(name))
                                    if (price.getName().equalsIgnoreCase(name)) {
                                        items.merge(price.getId(), quantity, Long::sum);
                                        break;
                                    }

                            });
            engine.noteBagReward(items, now);
        }
        // Each potion a chug drinks prints its own barrel line (Dude Where's My Stuff, GIM Hub).
        var chug = pattern("chug").matcher(message);
        if (chug.matches()) charges.chug(chug.group(1), now);
        // A banker charges a weapon straight from the bank (GP Per Hour 1.21), so no load is seen:
        // its next Check starts fresh rather than reading the added charges as unspent.
        var banker = pattern("banker-charge").matcher(message);
        if (banker.matches()) engine.resetChecks(ChargeCheck.namedVariant(banker.group(1)));
        if (pattern("ghommal-save").matcher(message).matches()) return;
        if (charges.observeCheck(message, now))
            // Charge Check chat is an explicit measurement, never an activity or title signal.
            return;
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
            engine.events.drop(FEE);
            engine.bookDeathFee(digits(fee.group(1)), msg("death-s"), now);
        } else if (bank.matches()) {
            int tick = client.getTickCount();
            if (tick == instanceTick)
                engine.bookDeathFee(digits(bank.group(1)), msg("instance-fee"), now);
            else if (engine.death.isAwaitingReclaim())
                engine.events.put(FEE, -1, digits(bank.group(1)), 10, "Reclaim fee (bank)").at = tick;
            else if (type == ChatMessageType.SPAM && tick - instanceTick > 1)
                engine.bookDeathFee(digits(bank.group(1)), msg("instance-fee-bank"), now);
        } else if (coffer.matches()) {
            instanceTick = client.getTickCount();
            engine.bookDeathFee(digits(coffer.group(1)), msg("instance-fee"), now);
        }
        var caught = pattern("bag").matcher(message);
        if (caught.matches() && lookup != null && containers.bagOpen()) {
            // A catch or a cut an open barrel or basket took never reaches the inventory (owner
            // 1.2).
            int found = caughtItem(caught.group(2), lookup.find(caught.group(2)));
            if (found >= 0) bagGain(found, caught.group(1) == null ? 1 : Long.parseLong(caught.group(1)), false);
        }
        if (lookup != null)
            for (String[] rule : cached("gem_messages")) {
                var gem = pattern(rule[0]).matcher(message);
                if (!gem.matches()) continue;
                String name = rule[1].length() > 1 ? rule[1] : gem.group(parseInt(rule[1]));
                int id = caughtItem(name, lookup.find(name));
                boolean direct = Boolean.parseBoolean(rule[3]);
                if (id < 0 || !containers.gemBag(id, direct ? 0 : 1)) continue;
                try {
                    long qty = "0".equals(rule[2]) ? 1 : digits(gem.group(parseInt(rule[2])));
                    if (direct) engine.bagged(id, qty);
                    else bagGain(id, qty, false);
                } catch (NumberFormatException malformed) { }
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
        if (skip(event)) return;
        var param = event.getParam1();
        var clicked = event.getMenuAction();
        raidNative().menu(event,now());
        if (event.isConsumed()) return;
        if (bagStore(event)) { markActivity(); return; }
        String option = normalize(event.getMenuOption());
        if (rule("chest-disposition", option)
                && engine.events.peek(CHEST, v -> v.item < 0
                        && v.x == CHESTS.getOrDefault(param >>> 16, -1)) != null)
            engine.events.drop(CHEST);
        if ("bank-all".equals(option)) bankAll(param >>> 16);
        boolean consumption = isSpend(option), gather = namesActivityOnly(option);
        if (gather || consumption)
            // A genuine action can produce an item without XP or an animation
            // (field crops). Capture its pre-action inventory on automatic start.
            markGameplayActivity(true);
        targets.onMenuOptionClicked(event);
        if (worldAction(clicked, event.isItemOp())
                || param >>> 16 == RUNE_POUCH
                        && UtilityContainerCatalogue.isCustodyOption(option)) markActivity();
        String target = normalize(event.getMenuTarget()), joined = option + " " + target;
        int identifier = menuItemId(event), ttl = consumeTicks();
        boolean ge = Widgets.isGeOpen(client);
        engine.observeChest(option, target);
        charges.onMenuOptionClicked(event, option, target);
        // A loss-only "Offer" click with the Grand Exchange open is a sell placement: the
        // inventory removal is GE evidence, not a prayer/altar spend. The proven GE UI context
        // disambiguates the shared "Offer" verb; a generic Offer elsewhere keeps its spend intent.
        if (consumption && "offer".equals(option) && ge) {
            trading(CORRELATION_TICKS, msg("ge-offer") + (target.isEmpty() ? "" : " " + target));
            return;
        }
        // A Collect interaction with the open Grand Exchange is bounded collection evidence. It
        // never owns a movement by itself; it lets exact custody settlements correlate.
        if (option.startsWith("collect") && ge) {
            engine.noteGeCollect(now());
            trading(CORRELATION_TICKS, msg("ge-collect"));
            return;
        }
        if ("revert".equals(option) || "dismantle".equals(option))
            engine.dismantle(identifier);
        if (target.contains("multicannon") || target.startsWith("cannon ")) {
            engine.syncCannon(client.getVarpValue(DROPCANNON), client.getVarpValue(ROCKTHROWER),
                            client.getVarbitValue(MCANNON_DECAYED) == 1);
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
        if (target.contains("vyre well") && "empty".equals(option))
            // The filled Vyre well refunds its vials of blood and blood runes through its Empty
            // option; that return is the player's own storage coming back, never income
            // (owner live 2026-10-13).
            zone(msg("vyre-well"));
        if (consumption) {
            // Always note intent — even when item id is unresolved (open intent). Spend is
            // confirmed on stable inventory removal / dose leftover; the verb lets the settled
            // row say Drank/Ate/Buried honestly. Exact spell text is optional presentation
            // evidence and never changes matching, type, counted state or value.
            engine.noteConsume(identifier, ttl, false, ActionReader.verbOf(option), "break".equals(option)
                            ? ActionLabel.of(Spells.named(target)) : spellLabel(event, option));
            if (clicked != null && clicked.name().startsWith("WIDGET_TARGET_ON")) {
                var spell = spellLabel(event, option);
                if (spell != null) engine.events.put(MANUAL_SPELL, -1, 1, 2, null).label = spell;
            }
        } else if ("drop".equals(option))
            // Match consume TTL so dump-then-settle does not miss power-train drops.
            engine.noteDrop(identifier, ttl);
        else if ("take".equals(option) && clicked != null
                && clicked.name().startsWith("GROUND_ITEM_")) {
            // A ground item's id is the entry's identifier; a pickup of fired ammo is recovery.
            int id = containers.canonical(event.getId());
            var e = engine.events.put(GROUND_CLICK, id, 1, 5, null);
            e.at = client.getTickCount();
            e.x = event.getParam0();
            e.y = param;
            e.held = inventoryQuantity(id);
        } else if ("destroy".equals(option))
            // Irreversible loss — consume-style intent, never recoverable own-drop.
            engine.noteConsume(identifier, ttl, true, null, null);
        // Use Tinderbox ↔ Logs (inventory Use): a spend intent for the fuel, same as Light.
        if (lightsFire(option, target))
            engine.noteConsume(identifier, ttl, false, ActionKind.SUPPLIES, null);
        // Use bones/ashes ↔ altar (PoH / wildy): an offer spend intent; Prayer XP confirms it.
        if (offersBones(option, target) || isOffering(option, target))
            engine.noteConsume(identifier, ttl, false, ActionKind.OFFER, null);
        // Agility dispensers (Wilderness course rewards, Brimhaven tickets): the claim keeps the
        // course title; the rewards book as ordinary inventory gains.
        if (target != null && AgilityCourses.DISPENSER.matcher(lower(target)).matches()) {
            var local = client.getLocalPlayer();
            String course = local == null || local.getWorldLocation() == null ? null
                            : AgilityCourses.courseName(local.getWorldLocation().getRegionID());
            activity.observe(course == null ? "Agility" : course, true);
        }
        // Farming / gather object actions: their skill's first XP names the activity (accounting is
        // inventory GAIN).
        if (gather)
            activity.skill = gatherActivity(option);
        else if (!consumption) {
            // Transform/production options (station or inventory) arm PRODUCTION context so the
            // paired receipt settles without double count; XP re-arms it inside the window.
            String skill = transformSkill(option);
            if (!skill.isEmpty()) activity.armProduction(skill, now());
            // Item on item (a knife on logs, a herb into a vial) starts a Make-X the space bar
            // confirms
            // with no click, and its Make button may come a minute later: the run's own skill XP
            // arms it
            // (owner 2026-10-08, fletching went to Review).
            else if (clicked == MenuAction.WIDGET_TARGET_ON_WIDGET
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
            if (joined.contains("deposit box"))
                zone(msg("deposit-box-transfer-2"));
            else
                engine.markContext(TRANSFER, CORRELATION_TICKS, msg("bank-deposit"));
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
                engine.markContext(TRANSFER, CORRELATION_TICKS, note);
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
        if (option.contains("accept trade") || option.startsWith("trade with"))
            trading(marketTicks(), "Player trade");
    }

    /** A GE or trade click: Trading activity and the MARKET context its inventory settle needs. */
    void trading(int ticks, String note) {
        activity.observe("Trading", true);
        engine.markContext(MARKET, ticks, note);
    }

    /** The configured stabilization ticks plus {@code a}. */
    int stableTicks(int extra) {
        return config.stabilizationTicks() + extra;
    }

    /** Marks a neutral storage or minigame zone for the usual correlation window. */
    void zone(String name) {
        engine.markTransfer(name, CORRELATION_TICKS);
    }

    /**
     * Consume/drop/altar intent lifetime: animations outlast correlation, plus a coalesced settle
     * window.
     */
    int consumeTicks() {
        return max(CORRELATION_TICKS * 2, stableTicks(16));
    }

    /** MARKET context lifetime for GE / player-trade inventory settlement. */
    int marketTicks() {
        return max(CORRELATION_TICKS * 2, stableTicks(8));
    }

    static final int LOOT_MEMORY_TICKS = 50; // a drop stays matchable for 30 s

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        if (!canIngest()) return;
        markGameplayActivity();
        if (engine.session() == null) return;
        String npc = event.getNpc() == null ? "NPC loot" : event.getNpc().getName();
        if (blank(npc)) npc = "NPC loot";
        var builder = hud;
        if (!HudTray.minion(npc)) {
            activity.observe(npc, true);
            engine.recordAction(npc);
            if (builder != null)
                builder.tray.kill(npc, now(), builder.keepSession());
        }
        engine.markLoot(expectedLoot(event.getItems()), LOOT_MEMORY_TICKS, "Loot from " + npc, npc);
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
        String name = fallback(event.getName(), "Reward");
        boolean pending = event.getType() == LootRecordType.EVENT && RewardChestCatalogue.isRewardChest(name);
        if (pending && msg("fractured-archive").equalsIgnoreCase(name)) activity.observe(name, true);
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
        engine.markPkLoot(expectedLoot(event.getItems()), LOOT_MEMORY_TICKS, now());
        persist();
        combat = 0;
    }

    @Subscribe
    public void onInteractingChanged(InteractingChanged event) {
        var self = client.getLocalPlayer();
        if (event.getSource() == self) {
            if (event.getTarget() instanceof NPC)
                // Use the existing authorized lifecycle for an actual interaction, never a menu
                // hover.
                markGameplayActivity();
            targets.onInteractingChanged(event);
        }
        if (!canIngest() || self == null) return;
        boolean localToPlayer = event.getSource() == self && event.getTarget() instanceof Player;
        boolean playerToLocal = event.getTarget() == self && event.getSource() instanceof Player;
        if (localToPlayer || playerToLocal) {
            // Keep short-lived evidence for death-safety classification even
            // when PK accounting is disabled. That evidence prevents a lingering
            // NPC target from turning a PvP death into an ownership-neutral reclaim.
            combat = 20;
            if (config.enablePkTracking() && !pvp.context.isSafe()) {
                markActivity();
                activity.observe("PKing", true);
            }
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        long owner = engine.events.owner;
        var player = client.getLocalPlayer();
        // A staged death in the Gauntlet or the PvP tutorial loses nothing (owner 2026-10-09).
        if (!canIngest() || player == null || event.getActor() != player || zones.isInside()
                || MinigameRegionHints.pvpTutorial(regionId()))
            return;
        if (engine.raids.active != null) raidNative().pulse(now());
        // Preserve the pre-death held-key evidence before any disposition branch.
        // A later stabilized key loss can then close its audit row without treating
        // the unowned manifest as a loss.
        engine.beginKeyDeath(KeyClaims.keys(client.getItemContainer(INV)));
        // Ownership input: this canonical INV/WORN read is intersected with settled negative
        // item flows by the engine.
        ContainerSnapshot deathHeldItems = containers == null ? null
                        : containers.captureDeathOwned();
        // Only a confident dangerous context plus recent player combat is a PvP death; a
        // safe arena never is; elsewhere require NPC context before the reclaim window.
        boolean pvmContext = targets != null && targets.pvmContext();
        DeathOutcome disposition = deathOutcome(config.enablePkTracking(), combat, pvmContext,
                        pvp.sample(player));
        Map<Integer, Long> bag;
        synchronized (engine) {
            if (owner != engine.events.owner) return;
            bag = lootingBag();
            engine.events.drop(BAG_GAIN, BAG_STORE);
            engine.bag.clear();
            engine.rememberBag(null);
        }
        // PvM or PK: drop the NPC/activity soft-stick so the killer cannot title the next owner.
        targets.clear();
        idle.clear();
        activity.resetToGeneral(true);
        refreshPresentation();
        if (disposition == DeathOutcome.TRACK_PK) {
            markActivity();
            activity.observe("PKing", true);
            var lost = engine.markPkDeath(now(), bag, deathHeldItems == null ? null : deathHeldItems.items, owner);
            if (lost != null) transactionBooked(lost, now());
            persist();
            combat = 0;
            return;
        }
        if (disposition == DeathOutcome.UNCLASSIFIED) {
            // Missing PvP evidence alone cannot prove this was a PvM death. Let the
            // inventory delta use ordinary loss classification rather than hiding a loss.
            engine.markUnknownDeath();
            markActivity();
            combat = 0;
            return;
        }
        // PvM death: gear moves to a gravestone / Item Retrieval Service the player
        // still owns. The wipe is an ownership-neutral transfer; the reclaim window
        // later returns the items and books observed coins as the fee.
        markActivity();
        engine.markPvmDeath(consumeTicks(), deathHeldItems == null ? null : deathHeldItems.items,
                        client.getVarbitValue(INSIDE_WILDERNESS) == 1 ? bag : emptyMap(), owner);
        // Persist the physical death whitelist immediately. A restart before the first
        // inventory wipe must still be able to classify the later loss as a transfer.
        persist();
    }

    static DeathOutcome deathOutcome(boolean pkTrackingEnabled, int playerCombatTicks, boolean pvmContext,
            PvpContext context) {
        if (context.isSafe())
            // Safe arenas restore gear; never a PK death, never a reclaim.
            return DeathOutcome.UNCLASSIFIED;
        if (playerCombatTicks > 0)
            // Player-combat evidence always blocks an unsafe PvM reclaim classification. It
            // books a PK death only with PK accounting on and a confident dangerous context;
            // otherwise the inventory loss follows ordinary accounting.
            return pkTrackingEnabled && context.isDangerous() ? DeathOutcome.TRACK_PK : DeathOutcome.UNCLASSIFIED;
        return !context.isDangerous() ? DeathOutcome.TRACK_PVM_RECLAIM : pvmContext ? DeathOutcome.TRACK_PVM_RECLAIM : DeathOutcome.UNCLASSIFIED;
    }

    Map<Integer, Long> expectedLoot(Collection<ItemStack> items) {
        var expected = new HashMap<Integer, Long>();
        for (var item : items)
            if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
                expected.merge(lookup == null ? item.getId() : lookup.canon(item.getId()),
                        (long) item.getQuantity(), Long::sum);

        return cash(expected);
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        Skill skill = event.getSkill();
        if (skill == null) return;
        Integer previous = experience.put(skill, event.getXp());
        if (previous == null || event.getXp() <= previous || !canIngest()) return;
        markGameplayActivity();
        idle.noteSkillingXp();
        long now = now();
        // Agility is an activity in its own right: the course names it when the region is known.
        var local = client.getLocalPlayer();
        String course = skill != Skill.AGILITY || local == null || local.getWorldLocation() == null ? null
                        : AgilityCourses.courseName(local.getWorldLocation().getRegionID());
        activity.observeXp(skill.getName(), course, targets.pvmContext(), now);
        var builder = hud;
        if (builder != null) builder.tray.xp(skill.getName(), now);
        charges.learnEyeCast(skill, client.getTickCount(), now);
    }

    /** Reward chest interface → its item container (d22), and the last Bank-all tick. */
    static final Map<Integer, Integer> CHESTS = new HashMap<>();

    static {
        for (String[] row : rows("bank_all_chests"))
            CHESTS.put(parseInt(row[0]), parseInt(row[1]));
    }

    /**
     * "Bank-all" on a reward chest banks loot that never reaches the inventory; it is booked like a
     * pickup, once per click (research 1.2).
     */
    void bankAll(int chestInterface) {
        Integer chest = CHESTS.get(chestInterface);
        var loot = chest == null ? null : client.getItemContainer(chest);
        if (loot == null) return;
        var counts = cash(counts(loot));
        // Each measured chest stack is quantity proof within the clicked chest's window.
        synchronized (engine) {
            engine.events.drop(CHEST);
            var e = engine.events.add(CHEST, -1, 1, 10, null);
            e.x = chest;
            e.at = client.getTickCount();
            counts.forEach((item, qty) -> engine.events.add(CHEST, item, qty, 10, null));
        }
    }

    /**
     * After a Bank-all, only what really leaves the chest is booked, once: a refused or repeated
     * click moves nothing (Codex research 1.2). Its raid attempt is attributed before an owner
     * change can detach it; the tray and sidebar hear of it after both monitors are released.
     */
    void chestChanged(int id, ItemContainer loot) {
        if (loot == null) return;
        var counts = counts(loot);
        long owner = engine.events.owner;
        List<Transaction> rows;
        synchronized (engine) {
            rows = engine.chest(id, counts, client.getTickCount(), owner, now());
            for (var row : rows) raidNative().booked(row, true, id);
        }
        for (var row : rows) transactionBooked(row, now());
    }

    Map<Integer, Long> counts(ItemContainer container) {
        var counts = new HashMap<Integer, Long>();
        containers.mergeContainer(counts, container);
        return counts;
    }

    long inventoryQuantity(int item) {
        return counts(client.getItemContainer(INV)).getOrDefault(item, 0L);
    }

    /** The taken item vanished beside you while an open bag was carried: it may be in the bag. */
    @Subscribe
    public void onItemDespawned(ItemDespawned event) {
        var local = client.getLocalPlayer();
        var item = event.getItem();
        Point at = event.getTile() == null ? null : event.getTile().getSceneLocation();
        var e = engine.events.peek(GROUND_CLICK);
        if (!canIngest() || e == null || e.owner != engine.events.owner || local == null || item == null || at == null
                || at.getX() != e.x || at.getY() != e.y || containers.canonical(item.getId()) != e.item
                || client.getTickCount() - e.at > 5
                || local.getWorldLocation().distanceTo(event.getTile().getWorldLocation()) > 1
                || item.getOwnership() != TileItem.OWNERSHIP_SELF) return;
        if (!containers.bagOpen()) {
            // The game's ammo-picking setting can put picked-up darts straight into the blowpipe
            // (OSRS Wiki). Decide two ticks later, once the pack has had its chance to gain them.
            if (e.item == charges.dart && engine.events.take(GROUND_CLICK, e.item, 1, v -> v == e) > 0) {
                var pick = engine.events.add(PIPE_PICKUP, e.item, item.getQuantity(), 5, null);
                pick.at = client.getTickCount();
                pick.held = e.held;
            }
            return;
        }
        // An open herb sack or seed box names each item it picks up in its own line, which books it.
        if (containers.gemBag(e.item, 2)) return;
        long qty = engine.events.reserve(PRIVATE_LOOT, e.item, item.getQuantity(), LOOT_RESERVE);
        if (qty > 0 && engine.events.take(GROUND_CLICK, e.item, 1, v -> v == e) > 0)
            bagGain(e.item, qty, carries(ItemID.LOOTING_BAG_OPEN));
    }

    /** A dart pickup the pack never gained went into the blowpipe: it recovers the shot. */
    void pipePickups() {
        for (var pick : engine.events.all(PIPE_PICKUP)) {
            if (pick.owner != engine.events.owner || client.getTickCount() - pick.at < 2) continue;
            long quantity = engine.events.take(PIPE_PICKUP, pick.item, pick.qty, v -> v == pick);
            if (containers.capture(false, false).quantity(pick.item) <= pick.held)
                engine.pickUpIntoPipe(pick.item, quantity, now());
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        lastPack = counts(client.getItemContainer(INV));
        if (!canIngest()) {
            // A fenced account (login, switch, restore) drops pending bag evidence untouched (Codex
            // review 1.2).
            engine.events.drop(BAG_GAIN, FEE);
            // The identity-ready flag waits for a ready tick below; consuming it here skipped
            // initLoggedIn() (1.3.1).
            if (client.getGameState() == LOGGED_IN) pulse();
            return;
        }
        if (storage.consumeReady()) initLoggedIn();
        if (!engine.profile.equals(rechecked)
                && engine.valuation.value(Map.of(ItemID.NATURERUNE, 1L)).stream()
                        .anyMatch(flow -> flow.value > 0)) {
            // Each loaded profile's old rows take today's rules once, after prices load, so an item
            // with no quote is told apart from one not loaded yet; item definitions are read here,
            // on the client thread (owner live 2026-10-16).
            rechecked = engine.profile;
            engine.recheck();
        }
        bondGp = engine.valuation.value(Map.of(ItemID.OSRS_BOND, 1L)).stream().mapToLong(f -> f.value).sum();
        // Separate-economy worlds pause tracking entirely so their time and loot never dilute the
        // main session; leaving resumes like a relog and re-baselines (owner 1.5.0).
        if (onUnsupportedWorld()) {
            boolean entering = !engine.separate;
            engine.pauseForWorld(now());
            if (entering) pulse();
            return;
        }
        if (engine.separate) {
            engine.separate = false;
            if (engine.resume(now(), WORLD)) rebaseline();
        }
        // Any ironman account type (iron, ultimate, hardcore, group) reads 1-6 (OSRS Wiki, RuneLite
        // gameval).
        valuation.alch = config.ironmanAlch() && client.getVarbitValue(IRONMAN) > 0;
        var fee = engine.events.peek(FEE);
        if (fee != null && fee.owner == engine.events.owner && client.getTickCount() - fee.at > 2)
            engine.fee(fee, now());
        bagSettle();
        pipePickups();
        engine.tickAutoStart(now());
        engine.tickMilestone(now());
        advanceGeOfferSeed();
        activity.syncSession();
        raidActivity();
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
        if (Widgets.visible(client, ChatLeft.UNIVERSE)
                || Widgets.visible(client, Chatmenu.UNIVERSE) || Widgets.isShopOpen(client))
            engine.events.put(DIALOGUE, 0, 1, stableTicks(4), null);
        boolean activeAnimation = player != null && player.getAnimation() != -1;
        boolean activeInteraction = player != null && player.getInteracting() != null;
        // Moving is not AFK: it holds off and lifts AFK pause, but never starts tracking (owner 1.5.0).
        boolean moved = tile != null && lastTile != null && !tile.equals(lastTile);
        lastTile = tile;
        int afk = config.afkPauseMinutes() * 100;
        if (activeAnimation || activeInteraction) markGameplayActivity();
        else if (moved) markActivity();
        else idleTick(afk);
        targets.onGameTick();
        if (bankOpen) {
            engine.bankOpened(max(CORRELATION_TICKS, stableTicks(2)));
            // Rising edge only — Banking ends combat soft-stick so Dark wizard
            // cannot reappear the moment the bank closes.
            if (!bankReset) {
                targets.clear();
                bankReset = true;
            }
        } else {
            bankReset = false;
            engine.bankClosed();
        }
        // Character idle drops a leftover NPC hint so leaving idle does not revive the last kill;
        // reading the exchange or a trade window is not idle (owner 1.5.0). It waits the Activity
        // label timeout, so the NPC's name outlasts the gap between kills (owner live 1.5.2).
        idle.model.delay = idleDelay(config);
        if (idle.onGameTick() && config.autoActivityDetection() && !windowHoldsActivity()
                && !GENERAL.equalsIgnoreCase(activity.detected))
            activity.resetToGeneral(true);
        pulse();
        if (player != null && player.getInteracting() instanceof Player)
            // Internal death-safety evidence only. PK transaction and activity
            // accounting still obeys enablePkTracking.
            combat = 20;
        else if (combat > 0)
            combat--;
        long now = now();
        if (engine.maintainRetention(now) > 0) {
            saveTicks = 0;
            persist();
        }
        engine.tickKeyClaims();
        if (engine.tickGe(now)) {
            saveTicks = 0;
            persist();
        }
        lootKeys.poll();
        charges.watchReturn();
        raidNative().pulse(now);
        inventoryBooked(engine.processIfDirty(containers.capture(true, true), now,
                row -> inventoryBooked(row, now)), now);
        if (saveDue(++saveTicks, engine.revision(), savedRevision)) {
            saveTicks = 0;
            persist();
        }
        pulse();
    }

    /** A boundary or tick receipt follows the same notification and coalesced-save path. */
    void inventoryBooked(Transaction transaction, long now) {
        if (transaction == null) return;
        markActivity();
        activity.observeTransaction(transaction);
        transactionBooked(transaction, now);
        saveTicks = max(saveTicks, SAVE_INTERVAL_TICKS - 10);
    }

    /**
     * A save serializes the whole profile on the client thread. With nothing booked since the last
     * one, only a slow heartbeat remains: crash recovery pauses a running session at it.
     */
    static boolean saveDue(int saveTicks, long revision, long savedRevision) {
        return saveTicks >= SAVE_INTERVAL_TICKS
                && (revision != savedRevision || saveTicks >= IDLE_SAVE_TICKS);
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
        if (MinigameRegionHints.pvpTutorial(region))
            engine.markTransfer("PvP tutorial", stableTicks(8));
        var signal = zones.onRegion(region, client.getVarbitValue(BR_INGAME) == 1);
        if (signal == NeutralZoneTracker.Signal.ENTERED) {
            engine.beginNeutralZone();
            engine.markTransfer(msg("zone-left"), stableTicks(4));
        } else if (signal == NeutralZoneTracker.Signal.INSIDE)
            engine.markTransfer(msg("zone-stored"), stableTicks(4));
        else if (signal == NeutralZoneTracker.Signal.LEFT)
            engine.endNeutralZone(consumeTicks());
    }

    /**
     * Owner 2026-10-09: while a house servant talks (taking items, or back from the bank or the
     * sawmill) his item moves are neutral. Every house is an instance, so a castle's cook is never
     * him. His own line names a sawmill trip ("the sawmill's fee", not "the bank or the sawmill").
     */
    void servantTalk() {
        if (!pattern("servant").matcher(normalize(Widgets.text(client, ChatLeft.NAME))).matches()
                || !client.getTopLevelWorldView().isInstance()) return;
        String line = normalize(Widgets.text(client, ChatLeft.TEXT));
        engine.events.put(SERVANT, 0, 1, stableTicks(4), null);
        if (line.contains("sawmill") && !line.contains("bank"))
            engine.events.put(SAWMILL, 0, 1, 200, null);
    }

    int regionId() {
        int region = -1;
        try {
            if (client != null && client.getLocalPlayer() != null
                    && client.getLocalPlayer().getWorldLocation() != null)
                region = WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation())
                                .getRegionID();
        } catch (Exception ignored) {
            // Live signals are best-effort.
        }
        return region;
    }

    /** Presentation-only duplicate guard for one loot batch reported by several RuneLite events. */
    String lastLootBatch = "";

    long lastLootAt;

    /** Unconfirmed loot goes to presentation only; pickup settles through the engine. */
    void offerReward(boolean pending, String source, Collection<ItemStack> items, int multiplicity) {
        long now = now();
        boolean chest = source != null && source.toLowerCase(ROOT).contains("loot chest");
        if ((pending || chest) && ClueCostPairing.clueActivity(source))
            engine.beginCluePath("loot:" + source, source);
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
            filter.refresh();
            refreshPresentation();
            return;
        }
        if (!GpManagerConfig.GROUP.equals(event.getGroup())) return;
        if (msg("lootpresentationfilter").equals(event.getKey())
                || msg("minimumdisplayedlootvalue").equals(event.getKey()))
            refreshPresentation();
    }

    /** Republishes the current facts to the sidebar; presentation only. */
    void refreshPresentation() {
        var panel = sidebar;
        if (panel != null) panel.refresh();
    }

    boolean canIngest() {
        try {
            if (client == null || client.getGameState() != LOGGED_IN) return false;
        } catch (RuntimeException ex) {
            return false;
        }
        return storage.isTrackingReady();
    }

    /** A visible raid HUD names the activity; it never creates a session or books money. */
    void raidActivity() {
        if (Widgets.visible(client, TfaHud.UNIVERSE))
            activity.observe(msg("fractured-archive"), true);
    }

    /** The shared subscriber gate: live tracking and a real event (owner 1.4.0 size pass). */
    boolean skip(Object event) {
        return !canIngest() || event == null;
    }

    /** Ticks without gameplay or movement, for AFK pause (100 ticks a minute). */
    int inactive;

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
        return max(5000, config.activityLabelSeconds() * 1000);
    }

    /**
     * One still tick toward AFK pause. Past the limit, a run that is no longer AFK-paused was
     * resumed from the status dot, so the AFK clock starts over instead of pausing again at once.
     */
    void idleTick(int afk) {
        if (afk <= 0 || ++inactive < afk) return;
        if (inactive > afk && !engine.isIdlePaused()) inactive = 0;
        else engine.pauseIdle(now());
    }

    void markActivity() {
        inactive = 0;
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
        inactive = 0;
        if (!canIngest()) return;
        long now = now();
        if (engine.autoStart(now))
            if (beforeItemChange && client.getItemContainer(INV) != null)
                engine.setBaseline(containers.capture(true, true));
            else
                engine.beginPriming();

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
            ActionLabel direct = ActionLabel.fromSpellMenu(option, clicked.getId(), MAGIC_SPELLBOOK,
                            clicked.getText(), clicked.getName());
            if (direct != null) return direct;
        }
        if (!isWidgetAction(event.getMenuAction())) return null;
        var selected = client.getSelectedWidget();
        return selected == null ? null : ActionLabel.fromSpellMenu(option, selected.getId(),
                        MAGIC_SPELLBOOK, selected.getText(), selected.getName());
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
        // CC_OP inventory Eat/Drink often leaves getItemId() == -1; read the clicked slot. Only a
        // widget click carries a slot: NPC, object and ground entries hold scene coordinates.
        if (id < 0 && event.getParam1() >>> 16 > 0) id = slotItemId(event.getParam0());
        return id < 0 ? -1 : lookup == null ? id : lookup.canon(id);
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

    /** Advance the heartbeat marker only after the snapshot is accepted by storage. */
    void persist() {
        long revision = engine.revision();
        if (storage.scheduleSave()) savedRevision = revision;
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
    String detected = GENERAL, session;
    long productionUntil;

    /**
     * The skill a gather click asked for ("Chop down": Woodcutting). A click proves nothing (no
     * axe, still walking), so the skill's first XP names it at once instead (owner 1.1).
     */
    String skill = "";

    /** How long a Make-X run stays open for its skill's XP after the last production evidence. */
    long runUntil;

    void observe(String activity, boolean immediate) {
        if (!config.autoActivityDetection() || blank(activity)) return;
        String next = activity.trim();
        if ("NPC loot".equalsIgnoreCase(next))
            // A generic fallback is not an activity; the previous real label stands.
            return;
        if (next.equalsIgnoreCase(activityCandidate))
            activityEvidence++;
        else {
            activityCandidate = next;
            activityEvidence = 1;
        }
        if (immediate || activityEvidence >= 2) {
            detected = next;
            engine.setDetectedActivity(next, now());
        }
    }

    /** Settled transaction types that name the activity (PK, named loot, trade). */
    void observeTransaction(Transaction transaction) {
        if (transaction == null) return;
        switch (transaction.type()) {
            case PK_LOOT:
            case PK_SUPPLY_COST:
            case PK_DEATH_LOSS:
                observe("PKing", true);
                break;
            case LOOT:
                String lootActivity = transaction.activityName();
                if (!blank(lootActivity) && !GENERAL.equalsIgnoreCase(lootActivity.trim()))
                    observe(lootActivity.trim(), true);
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
        var active = engine.session();
        String nextId = active == null ? null : active.id();
        if (nextId == null ? session == null : nextId.equals(session)) return;
        session = nextId;
        String initial = active == null ? GENERAL : active.activityHint();
        detected = fallback(initial, GENERAL);
        activityCandidate = detected;
        activityEvidence = 0;
    }

    /** Local death drops the detected activity so the killer cannot title the next session. */

    void resetToGeneral(boolean resetHold) {
        if (!GENERAL.equalsIgnoreCase(detected) || resetHold) {
            detected = GENERAL;
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
            observe(skill.trim(), skill.equalsIgnoreCase(this.skill));
        // A Make-X run has actions with no XP (a failed iron smelt), so a production skill's XP
        // re-arms the context for 30 s after the last one, not only inside the six-tick window.
        if (now <= productionUntil || TRANSFORM.containsValue(skill)
                || "Smithing".equals(skill))
            continueProduction(skill, now);
    }

    /**
     * Evidence the Make-X run goes on (its XP, a failed smelt): re-arms it within 30 s of the last.
     */
    void continueProduction(String note, long now) {
        if (now <= runUntil) armProduction(note, now);
    }

    /** Enabling during Make-X needs fresh production XP, without a click made while disabled. */
    void clearProduction(long now) {
        productionUntil = 0;
        runUntil = now + 30000;
        skill = "";
    }

    /** Station/menu hint: arm PRODUCTION context for the correlation window. */
    void armProduction(String skillNote, long now) {
        int ticks = 6;
        String note = fallback(skillNote, "Production");
        engine.markContext(PRODUCTION, ticks, note);
        productionUntil = now + ticks * MILLIS_PER_GAME_TICK;
        runUntil = now + 30000;
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

    /**
     * Name-only evidence for the Wilderness course's reward dispenser and the Brimhaven ticket
     * dispenser, independent of the region, as before.
     */
    static final Pattern DISPENSER = pattern("dispenser");

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
    static final long RELEASE_GRACE_MILLIS = 4000;
    final Client client;
    final Engine engine;
    final GpManagerConfig config;
    String owner = "", name = "";
    boolean npc;
    long releasedAt, targetAt;
    BooleanSupplier ingestion = () -> false;
    BiConsumer<String, Boolean> presentation = (name, combat) -> {};

    void bindPresentation(BooleanSupplier ready, BiConsumer<String, Boolean> changed) {
        ingestion = ready == null ? () -> false : ready;
        presentation = changed == null ? (name, combat) -> {} : changed;
    }

    void clear() {
        owner = "";
        targetAt = 0;
        set("", false);
    }

    /** True while the active owner's target positively identifies PvM (a combat NPC). */
    boolean pvmContext() {
        return ready() && npc && (releasedAt == 0 || now() - releasedAt < RELEASE_GRACE_MILLIS);
    }

    boolean ready() {
        var session = engine.session();
        String nextOwner = session == null ? "" : session.id();
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
        } else if (target instanceof Player)
            // Opponent names are never display evidence; PvP reads as its activity.
            set("", false);
        else
            releasedAt = now();
    }

    /** Scenery is never an activity; any other intentional action cancels a non-combat target. */
    void onMenuOptionClicked(MenuOptionClicked event) {
        if (!ready() || event == null || event.isConsumed() || event.getMenuAction() == null)
            return;
        String type = event.getMenuAction().name();
        var menuEntry = event.getMenuEntry();
        String clicked = menuEntry == null || menuEntry.getTarget() == null ? "" : menuEntry.getTarget().trim();
        if (npc && !clicked.isEmpty() && clicked.equalsIgnoreCase(name))
            // Still attacking the same target: keep the activity label alive.
            targetAt = now();
        if (!(type.startsWith("GAME_OBJECT_") && type.endsWith("_OPTION")) && !npc)
            set("", false);
    }

    /** Release grace expiry for non-combat targets; a combat target lives out the label timeout. */
    void onGameTick() {
        long now = now();
        if (releasedAt != 0 && now - releasedAt >= RELEASE_GRACE_MILLIS && !npc) {
            set("", false);
            return;
        }
        if (npc && !empty(name) && targetAt != 0
                && now - targetAt >= max(1000, config.activityLabelSeconds() * 1000))
            set("", false);
    }

    void set(String next, boolean combat) {
        String n = next == null ? "" : next.trim();
        releasedAt = 0;
        if (!n.isEmpty()) targetAt = now();
        if (n.equals(name) && combat == npc) return;
        name = n;
        npc = combat;
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
    long delay = 5000, idleAt, xpAt;
    boolean idle;

    /** Soft-busy for Make-X / process gaps — presentation only. */
    synchronized void noteSkillingXp(long now) {
        xpAt = positive(now);
        idle = false;
        idleAt = 0;
    }

    /**
     * @param animating true when local animation != -1
     * @param interacting true when local player has an interact target
     * @return true once when character idle newly becomes true
     */
    synchronized boolean tick(boolean animating, boolean interacting, long now) {
        boolean recentXp = xpAt > 0 && now - xpAt < delay;
        boolean hardBusy = animating || interacting;
        if (hardBusy || recentXp) {
            if (hardBusy)
                // Animation / interact: full Idle delay after clear.
                idleAt = 0;
            else
                // XP soft-busy alone: Idle fires delayMillis after the XP drop —
                // do not stack a second full delay when the soft window ends.
                idleAt = xpAt;
            idle = false;
            return false;
        }
        if (idleAt <= 0) idleAt = now;
        if (now - idleAt < delay) {
            idle = false;
            return false;
        }
        if (!idle) {
            idle = true;
            return true;
        }
        return false;
    }

    /** Logout / hop — reset like Idle Notifier timers. */
    synchronized void clear() {
        idleAt = 0;
        xpAt = 0;
        idle = false;
    }
}

/**
 * Client-thread adapter for character idle (no animation, no target for the idle delay).
 * Presentation-only: it never pauses the engine or touches GP/hr; session idle pause is the
 * engine's own idle timeout.
 */
@Singleton
@AllArgsConstructor(onConstructor_ = @Inject)
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

    /** Re-reads the client signals now (death/loot events may precede the next tick sample). */
    PvpContext sample(Player player) {
        int region = player == null || player.getWorldLocation() == null ? 0
                        : player.getWorldLocation().getRegionID();
        context = PvpContext.classify(client.getVarbitValue(INSIDE_WILDERNESS) == 1
                                        || client.getVarbitValue(BH_IN_BH) == 1,
                        client.getVarbitValue(PVP_AREA_CLIENT) == 1,
                        client.getVarbitValue(THIS_IS_A_PVP_OR_BH_WORLD) == 1,
                        client.getVarbitValue(THIS_IS_A_PVP_ARENA_WORLD) == 1
                                        || client.getVarbitValue(BR_INGAME) == 1
                                        || client.getVarbitValue(BH_IN_LOBBY) == 1, region);
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
    static PvpContext classify(boolean insideWilderness, boolean specOrb, boolean pvpWorld,
            boolean pvpArenaWorld, int region) {
        if (pvpArenaWorld || isSafeArenaRegion(region)) return SAFE_PVP_OR_MINIGAME;
        return insideWilderness || pvpWorld && specOrb ? DANGEROUS_PVP : NONE;
    }

    static boolean isSafeArenaRegion(int regionId) {
        if (MinigameRegionHints.lmsRegion(regionId)) return true;
        if (regionId > 0)
            for (int region : SAFE_ARENA_REGIONS)
                if (region == regionId) return true;


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

    /**
     * A kill receipt left the ledger: keep its net, and fold the kill into the best once none of
     * its receipts remain live.
     */
    void compact(Transaction receipt, List<Transaction> live) {
        String id = receipt.encounterId();
        if (!id.startsWith(KILL)) return;
        long net = compactedKillNet.merge(id, net(receipt), SafeMath::sum);
        for (var transaction : live)
            if (id.equals(transaction.encounterId())) return;

        compactedKillNet.remove(id);
        bestKill = max(bestKill, net);
    }

    long bestKill(List<Transaction> live) {
        var nets = new HashMap<>(compactedKillNet);
        for (var transaction : live) {
            String id = transaction.encounterId();
            if (id.startsWith(KILL)) nets.merge(id, net(transaction), SafeMath::sum);
        }
        long best = bestKill;
        for (long net : nets.values()) best = max(best, net);
        return positive(best);
    }

    static long net(Transaction transaction) {
        var money = Accounting.transaction(transaction);
        return money.available && money.included ? money.net() : 0;
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
        return visible(client, Bankmain.ITEMS_CONTAINER)
                || visible(client, BankDepositbox.INVENTORY)
                || visible(client, BankDepositbox.CONTENTS);
    }

    /** Tool Leprechaun store (either panel) visible; its withdrawals and deposits are storage. */
    static boolean isToolStoreOpen(Client client) {
        return visible(client, FarmingTools.FRAME)
                || visible(client, FarmingToolsSide.UNIVERSE);
    }

    /**
     * Stores and exchanges whose own open window proves a move neutral (d29): its note, else null.
     */
    static final List<String[]> STORES = rows("store_windows");

    /** Shop windows whose own open UI turns a coin movement into a trade (owner 1.3.4). */
    static final List<String[]> SHOPS = rows("shop_windows");

    static String store(Client client) {
        for (String[] row : STORES) if (visible(client, parseInt(row[0]))) return row[2];
        return null;
    }

    /** A shop window (classic or omni) visible: purchases and sales are traded, not Review. */
    static boolean isShopOpen(Client client) {
        for (String[] row : SHOPS) if (visible(client, parseInt(row[0]))) return true;
        return false;
    }

    static boolean isGeOpen(Client client) {
        // A banker's or clerk's collection box collects offers like the exchange itself (1.2).
        return visible(client, GeOffers.UNIVERSE) || visible(client, GeOffersSide.ITEMS)
                || visible(client, GeCollect.UNIVERSE);
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
@AllArgsConstructor(onConstructor_ = @Inject)
class ContainerSnapshotFactory {
    final Client client;
    final ItemLookup lookup;

    /** Rune pouch slot varbits (type, quantity) — live account state, unlike bag containers. */
    static final int[][] RUNE_POUCH_SLOTS = cached("rune_pouch_slots").stream()
            .map(row -> new int[] {parseInt(row[0]), parseInt(row[1])}).toArray(int[][]::new);

    /**
     * @param includeRunePouch merge runes held in the rune pouch. The pouch is account state
     *     exposed through live varbits, so casting from it books a cost and filling it nets to
     *     zero. Looting bag / seed box containers are deliberately excluded: the client only syncs
     *     them when the bag is opened, so they would drift.
     */
    ContainerSnapshot capture(boolean includeEquipment, boolean includePouch) {
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
        if (includePouch) mergeRunePouch(counts);
        // A carried item that holds its charges as items (stored_charges.tsv: the ash sanctifier,
        // Obligator, archivist's folio, Zorya's tome) counts them as those items, so charging nets
        // to zero and each spent charge is a cost.
        mergeStoredCharges(counts);
        return new ContainerSnapshot(cash(counts));
    }

    /** Item, charge varbit, charges per set, then quantity/item pairs (stored_charges.tsv). */
    static final int[][] STORED = cached("stored_charges").stream()
            .map(row -> Arrays.stream(row).mapToInt(cell -> parseInt(cell.split("=")[0])).toArray())
            .toArray(int[][]::new);

    static boolean storedCharges(int varbit) {
        return Arrays.stream(STORED).anyMatch(row -> row[1] == varbit);
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
        return new ContainerSnapshot(cash(counts));
    }

    /** Ownership at death includes the components held inside each carried charged item. */
    ContainerSnapshot captureDeathOwned() {
        var counts = new HashMap<>(captureDeathHeld(true).items);
        mergeStoredCharges(counts);
        return new ContainerSnapshot(counts);
    }

    void mergeStoredCharges(Map<Integer, Long> counts) {
        for (int[] row : STORED)
            if (counts.containsKey(row[0])) {
                long charges = client.getVarbitValue(row[1]);
                for (int i = 3; i + 1 < row.length; i += 2)
                    counts.merge(row[i + 1], charges * row[i] / row[2], Long::sum);
            }
    }

    /** True when {@code varbitId} is one of the rune pouch slot varbits. */
    static boolean isPouchVarbit(int varbitId) {
        for (int[] slot : RUNE_POUCH_SLOTS)
            if (slot[0] == varbitId || slot[1] == varbitId) return true;

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
            ONE_ITEM.put(parseInt(row[0]), parseInt(row[1]));
            if (row[2].contains("_OPEN=")) BAGS.add(parseInt(row[0]));
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

    /**
     * A carried container (gem_containers.tsv) accepts this item: need 1 also requires it open, 2
     * also requires the container to name each ground pickup in its own line (herb sack, seed box).
     */
    boolean gemBag(int gem, int need) {
        var inventory = client.getItemContainer(INV);
        if (inventory == null) return false;
        for (Item item : inventory.getItems()) {
            if (item == null || need > 0 && !BAGS.contains(item.getId())) continue;
            for (String[] row : cached("gem_containers"))
                if (canonical(item.getId()) == parseInt(row[0]) && row.length > need + 1
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
    Map<Integer, Long> items;

    ContainerSnapshot(Map<Integer, Long> counts) {
        items = unmodifiableMap(new HashMap<>(counts));
    }



    Map<Integer, Long> diff(ContainerSnapshot previous) {
        var result = new HashMap<Integer, Long>();
        var ids = new HashSet<>(items.keySet());
        ids.addAll(previous.items.keySet());
        for (var id : ids) {
            long current = amount(items, id);
            long previousQuantity = amount(previous.items, id), delta = current - previousQuantity;
            if (delta != 0) result.put(id, delta);
        }
        return result;
    }

    /** Quantity of one item id in this snapshot (0 when absent). */
    long quantity(int id) {
        return amount(items, id);
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
        for (String name : NAMES)
            if (lower != null && lower.contains(name))
                return name.replace("'", "").replace(' ', '_').replace('-', '_');

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
    Signal onRegion(int region, boolean lms) {
        boolean now = lms || MinigameRegionHints.isNeutralZoneRegion(region);
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
    static boolean pvpTutorial(int region) {
        return "arena".equals(KINDS.get(region));
    }

    static boolean lmsRegion(int region) {
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
        boolean lms = region >= 0 && MinigameRegionHints.lmsRegion(region);
        for (String[] row : cached("neutral_transfers")) {
            if (row[0].startsWith("m") != minigame || row[0].endsWith(".lms") && !lms) continue;
            if (has(name, RULES.get(row[0]))) return text(row[3]);
        }
        return null;
    }
}

/**
 * Client-thread intake for the exact Check-to-Check charge contract: a Check click binds a
 * short-lived identity, the numeric chat read is the only source of counts, and a Use-on-item click
 * arms one narrow load. Local graphics, hitsplats and location clicks book LIKELY estimates.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ChargeIntake {
    final Client client;
    final ItemLookup lookup;
    final GpManagerConfig config;
    final Engine engine;

    /** The single GP Manager valuation authority; null only in narrow synthetic tests. */
    final Valuation valuation;

    /** Fractional component carry per variant+item so whole units book when they accrue. */
    final Map<String, Long> chargeCarry = new HashMap<>();

    /** Exact cumulative value per fractional component: units consumed and value already booked. */
    final Map<String, long[]> valueCarry = new HashMap<>();

    int lastCastTick = -1;

    /** The blowpipe's loaded dart from its last Check or load; -1 until one says. */
    int dart = -1;

    int lastCastGraphic;

    /** The player's spot anims at the last change, by id and start cycle. */
    Set<Long> playingAnims = new HashSet<>();

    int attack = -1, blessing = -1;

    /** Learnable eye cast triggers: local graphics or animations proven by same-tick magic XP. */
    final Set<Integer> eyeGraphics = new HashSet<>();

    final Set<Integer> eyeAnimations = new HashSet<>();
    int eyeGraphic, eyeAnimation, eyeTick = -1, eyeWeapon;
    String eyeTarget;

    /** A melee hit awaiting its same-tick XP confirmation, so a login's first hit counts. */
    int furyTick = -1;

    /** Eye charging mode per target identity: true when that staff's own line said runes, so
     * each cast then spends 2 death and 1 chaos. */
    final Map<String, Boolean> runes = new HashMap<>();

    /** How long an Uncharge click waits for its returned components: 50 ticks, 30 seconds. */
    static final int RETURN_TICKS = 50;

    /** A Check command has one physical target and a short inclusive client-tick window. */
    Observation checkIntent() {
        return engine.events.peek(CHECK_LINK);
    }

    boolean startCheck(ChargedItem variant, String id, int tick, int ticks) {
        engine.events.drop(CHECK_LINK);
        if (variant == null || !variant.implemented || blank(id) || ticks <= 0)
            return false;
        var e = engine.events.add(CHECK_LINK, -1, 1, ticks, null);
        e.variant = variant;
        e.id = id;
        e.at = tick + ticks;
        return true;
    }

    String resolveCheck(ChargeCheck read, int tick) {
        var e = checkIntent();
        String id = e != null && tick <= e.at && read != null && read.supported && read.variant == e.variant ? e.id
                        : null;
        engine.events.drop(CHECK_LINK);
        return id;
    }

    /** Any unrelated chat/menu between click and numeric response drops the target link. */
    void clearCheck() {
        engine.events.drop(CHECK_LINK);
    }

    /**
     * Login/hop: transient baselines and load intents never survive; the fractional carries do,
     * so a vial of blood (or a tome page, or a blowpipe scale) still accrues toward its next
     * whole unit across a relog (owner live 2026-10-13: the wipe made short sessions never book
     * the vial).
     */
    void resetTransient() {
        blessing = -1;
        engine.events.drop(CHECK_LINK);
        eyeGraphics.clear();
        eyeAnimations.clear();
        eyeGraphic = 0;
        eyeAnimation = 0;
        eyeTick = -1;
        eyeWeapon = 0;
        eyeTarget = null;
        furyTick = -1;
        runes.clear();
        dart = -1;
        engine.events.drop(UNCHARGE);
        attack = -1;
        engine.resetChecks(null);
        engine.clearChargeLoad();
    }

    /** Profile change: nothing of the previous owner survives, the fractional carries included. */
    void resetOwner() {
        resetTransient();
        chargeCarry.clear();
        valueCarry.clear();
    }

    /**
     * Feed only exact numeric Check messages into the measured charge baseline. Chat is
     * deliberately not activity/title evidence. Booking rejects the complete delta on a missing
     * supported component valuation or any arithmetic overflow.
     *
     * @return true when the message was a charge Check read (consumed, even if unbooked)
     */
    boolean observeCheck(String message, long now) {
        var read = parse(message);
        if (read == null) return false;
        if (read.variant.isCommittedLoad()) {
            // A committed load books when its shards arrive; its Check has nothing to compare
            // (owner 2026-10-12).
            engine.resetChecks(read.variant);
            return true;
        }
        if (read.variant == BLOWPIPE) dart = read.dart;
        var clicked = checkIntent() == null ? null : checkIntent().variant;
        // A revenant Check says only "bow", "sceptre" or "chainmace": the clicked weapon names the
        // row.
        if (clicked != null && clicked.check != null && clicked != read.variant && clicked.check == read.variant.check)
        {
            // The line names only the family ("your tome"); the clicked item names the row, and
            // the read's stock is rebuilt from that row's own recipe (the tomes use different
            // pages; owner 2026-10-12).
            read.counts = restock(clicked, read.variant, read.counts);
            read.variant = clicked;
        }
        String target = resolveCheck(read, client.getTickCount());
        if (target == null && read.variant == BLOOD_FURY)
            // The amulet's count arrives on its own, without a Check click: bind it to the
            // worn amulet's neck slot (owner 2026-09-29).
            target = bloodFury();
        if (read.variant == EYE_OF_AYAK) {
            // No Check click: the line can only mean the worn staff or the pack copy.
            if (target == null) target = heldIdentity(EYE_OF_AYAK, EquipmentInventorySlot.WEAPON);
            // The line names the mode ("demon tears" or "runes"), stored for that staff (review
            // 2026-10-09).
            runes.put(target, read.counts.containsKey(ItemID.DEATHRUNE));
        }
        if (target == null) {
            // A value without its fresh Check target cannot distinguish another
            // same-variant weapon; discard any older comparison baseline of that kind.
            engine.resetChecks(read.variant);
            return true;
        }
        if (message.toLowerCase(ROOT).contains("charged with"))
            // Any charge wording is a load: the components moved into the weapon, never spend.
            // Armed even on the first read, where no delta exists yet (owner 2026-09-30).
            transfer("charge-load");
        var delta = engine.observeCheck(read, target, now);
        // Every exact read names its result; quantities still come only from measured decreases.
        if (delta.result == CheckResult.SAME) {
            engine.closeChargeWindow(target);
            return true;
        }
        if (delta.result == CheckResult.LOAD) transfer("charge-load");
        var lost = delta.result == CheckResult.SPEND ? measuredLosses(read, delta, now) : null;
        if (lost != null)
            engine.bookChargeSpend(delta, read.variant.displayName, lost, now, target);
        // The measured read reconciles only this weapon's estimates; another of the same kind keeps
        // its own until its own Check (Codex research 1.2). Any other read is a fresh start:
        // earlier estimates stay as booked, and the next Check, measured from here, never
        // reconciles them.
        engine.clearPendingEstimates(target, lost == null ? emptySet() : delta.unchanged);
        return true;
    }

    /** The measured decrease priced as losses; null when it cannot be booked as spend. */
    List<Flow> measuredLosses(ChargeCheck read, ChargeDelta delta, long now) {
        if (lookup == null) return null;
        if (read.variant == EYE_OF_AYAK) {
            boolean resource = read.counts.containsKey(ItemID.DEATHRUNE)
                            || read.counts.containsKey(ItemID.CHAOSRUNE);
            if (delta.componentDeltas.stream()
                    .anyMatch(component -> component.getItemId() == ItemID.DEMON_TEAR == resource))
                // A mode switch's vanished component is a load or unload, never spend.
                return null;
        }
        var lost = new ArrayList<Flow>();
        for (var component : delta.componentDeltas) {
            Flow loss = chargeFlow(read.variant, component.itemId, component.quantityDelta, now, false);
            if (loss == null) return null;
            lost.add(loss);
        }
        return lost;
    }

    /** The tick through which recent combat XP proves a melee stance; -1 when it does not. */
    int attackUntil = -1;

    int stanceXpTick = -1;
    boolean attackXp, otherXp;

    /**
     * Combat XP reveals the attack style with no per-weapon animation table: Attack/Strength/
     * Defence confirm melee, Ranged/Magic deny it, so every melee weapon qualifies (owner
     * 2026-09-29).
     */
    void learnEyeCast(Skill skill, int tick, long now) {
        if (skill == Skill.MAGIC && eyeTick >= 0 && tick - eyeTick <= 1) {
            if (eyeAnimation > 0) eyeAnimations.add(eyeAnimation);
            if (eyeGraphic > 0) eyeGraphics.add(eyeGraphic);
            if (eyeTarget != null && tick != attack) {
                // The cast that proved the trigger books once, through the same tick dedupe.
                attack = tick;
                bookAttackEstimate(EYE_OF_AYAK, eyeWeapon, eyeTarget, ActionKind.CAST,
                        now);
            }
            eyeAnimation = 0;
            eyeGraphic = 0;
            eyeTick = -1;
            eyeWeapon = 0;
            eyeTarget = null;
        }
        boolean melee = skill == Skill.ATTACK || skill == Skill.STRENGTH || skill == Skill.DEFENCE;
        boolean other = skill == Skill.RANGED || skill == Skill.MAGIC;
        if (!melee && !other) return;
        if (tick != stanceXpTick) {
            stanceXpTick = tick;
            attackXp = false;
            otherXp = false;
        }
        attackXp |= melee;
        otherXp |= other;
        attackUntil = otherXp ? -1 : attackXp ? tick + 8 : attackUntil;
        if (melee && furyTick == tick) {
            // The hit that opened this fight lands before its XP in the same tick.
            furyTick = -1;
            if (furyAmulet() != null) bookFury(now);
        }
    }

    /** Whether the recent XP pattern proves the player is meleeing right now. */
    boolean furyMelee(int tick) {
        return tick <= attackUntil;
    }

    /**
     * Uncharging Bryophyta's staff destroys the nature runes it holds (OSRS Wiki; "You remove 1000
     * charges from your staff, leaving it empty."): those charges are spent, one rune each.
     */
    void emptied(long charges, long now) {
        engine.spent(BRYOPHYTA.displayName,
                List.of(valuation.valueComponent(ItemID.NATURERUNE, -charges, now)), now);
    }

    /** The doses a chug drank this tick, booked as one row on the next game tick. */
    final List<Flow> drank = new ArrayList<>();

    /**
     * A chug drinks one dose of every potion in the chugging barrel, never touching the inventory.
     * The game names each one, as its one-dose potion: "You have 3 doses of Saradomin brew(1) left
     * in your barrel." or "You have finished all doses of ..." (OSRS Wiki; Dude Where's My Stuff).
     */
    void chug(String potion, long now) {
        for (var match : lookup.find(potion))
            if (match.getName().equalsIgnoreCase(potion)) {
                drank.add(valuation.valueComponent(match.getId(), -1, now));
                return;
            }

    }

    /** Each tick, the doses drunk from the barrel are one Drank row (owner 2026-10-09). */
    void drank(long now) {
        if (drank.isEmpty()) return;
        engine.chug(new ArrayList<>(drank), now);
        drank.clear();
    }

    /** A client item container, or null without a client. */
    ItemContainer container(int container) {
        return client == null ? null : client.getItemContainer(container);
    }

    /** Marks the next item move as a neutral charge transfer with this message. */
    void transfer(String messageKey) {
        engine.markContext(Context.TRANSFER, max(4, config.stabilizationTicks() + 2), msg(messageKey));
    }

    /** That staff's charging mode; unknown means tears (the default charge type). */

    /**
     * The eye's identity for lines that arrive with no Check click: the worn staff, else the pack.
     */

    /**
     * A teleport click that carries no item id (a Rub-interface destination button, or a worn
     * pendant's own minimenu destination) resolves the pendant itself: the worn amulet first,
     * then the pack (owner 1.3.4).
     */
    void bookAmulet(ChargedItem variant, long now) {
        String identity = heldIdentity(variant, EquipmentInventorySlot.AMULET);
        if (identity != null)
            bookAttackEstimate(variant, parseInt(identity.split(":")[2]), identity, ActionKind.SUPPLIES, now);
    }

    /**
     * The identity of a held item of this family: the one worn in that equipment slot first, then
     * the first one in the pack; null when neither holds one.
     */
    String heldIdentity(ChargedItem variant, EquipmentInventorySlot wornSlot) {
        Item item = wornItem(wornSlot);
        if (item != null && supported(item.getId()) == variant)
            return weaponIdentity(InterfaceID.Wornitems.UNIVERSE, wornSlot.getSlotIdx(), item.getId(), variant);
        var inventory = container(InventoryID.INV);
        if (inventory == null) return null;
        for (int slot = 0; slot < inventory.size(); slot++) {
            item = inventory.getItem(slot);
            if (item != null && supported(item.getId()) == variant)
                return weaponIdentity(InterfaceID.Inventory.ITEMS, slot, item.getId(), variant);
        }
        return null;
    }

    /** The worn amulet of blood fury, or null when another amulet (or none) is worn. */
    Item furyAmulet() {
        Item amulet = wornItem(EquipmentInventorySlot.AMULET);
        return amulet != null && amulet.getId() == ItemID.BLOOD_AMULET ? amulet : null;
    }

    /** The worn amulet's identity for its click-free count messages; null when not worn. */
    String bloodFury() {
        Item amulet = furyAmulet();
        return amulet == null ? null : weaponIdentity(InterfaceID.Wornitems.UNIVERSE,
                        EquipmentInventorySlot.AMULET.getSlotIdx(), amulet.getId(), BLOOD_FURY);
    }

    /** One blood fury charge for a melee hit. */
    void bookFury(long now) {
        bookAttackEstimate(BLOOD_FURY, ItemID.BLOOD_AMULET, bloodFury(),
                BLOOD_FURY.action, now);
    }

    /** One use of the worn weapon at its pinned recipe. */
    void bookWorn(ChargedItem variant, Item weapon, long now) {
        bookAttackEstimate(variant, weapon.getId(), wornIdentity(variant, weapon.getId()), variant.action, now);
    }

    /** Every menu click: supersede a pending Check link, then arm Check / unload / load intents. */
    void onMenuOptionClicked(MenuOptionClicked event, String option, String target) {
        // Answering the Uncharge prompt keeps the return watch; any other action ends it, so a
        // declined prompt never turns later loot of the same runes or scales into a transfer.
        if (!isDialogAnswer(event, option)) engine.events.drop(UNCHARGE);
        armCheck(event, option, target);
        armLoad(event, option);
        // The memoirs are charged at the Old Memorial: the runes move into the book (owner 1.2).
        if ("inspect".equals(option) && target.contains("old memorial"))
            engine.markChargeLoad(MEMOIRS, ItemID.LAWRUNE, "law rune", null, LOAD_TICKS);
        if (isAtesDestination(event)) {
            // A Rub-interface destination button carries no item id: the pendant itself, worn or
            // in the pack, names the teleport (owner 1.3.4).
            bookAmulet(ATES, now());
            return;
        }
        if (isAmuletLocation(option) && isAmuletTeleport(event, target, option)) {
            // A sailors' amulet teleport: the destination names the teleport whether the click
            // sits on the worn amulet's own menu, its teleport dialog or the teleport interface
            // (owner captures and live 2026-10-14). One charge leaves the amulet; the amulet
            // itself names the row.
            bookAmulet(SAILORS_AMULET, now());
            return;
        }
        if (!isAtesLocation(option)) return;
        int id = weaponId(event);
        var variant = supported(id);
        if (variant == null && target.contains("pendant of ates")) {
            // A worn pendant's own minimenu destination can carry no item id either (owner 1.3.4
            // live capture: "Nemus Retreat" arrived with param1 on the worn amulet and itemId
            // -1); resolve the pendant itself rather than drop the click.
            bookAmulet(ATES, now());
            return;
        }
        if (variant != ATES && variant != MEMOIRS) return;
        String identity = clickedIdentity(event, variant, id);
        if (identity == null) identity = checkIdentity(variant, id);
        if (identity != null)
            bookAttackEstimate(variant, id, identity, ActionKind.SUPPLIES, now());
    }

    /**
     * A pendant or memoirs teleport click spends one charge; bank, inventory and interface options
     * are plumbing.
     */
    static boolean isAtesLocation(String option) {
        return LOCATIONS.contains(place(option));
    }

    /** A menu option as a destination name: straight apostrophes, without a leading "the ". */
    static String place(String option) {
        String name = option == null ? "" : option.replace('’', '\'');
        return name.startsWith("the ") ? name.substring(4) : name;
    }

    /** The pendant's six teleport locations, normalized like menu options. */
    static final List<String> LOCATIONS = names("ates_locations");

    /**
     * The sailors' amulet's four destinations (a leading "the " strips like the pendant's own
     * options); a click there is a teleport (OSRS Wiki, owner recording 2026-10-14).
     */
    static boolean isAmuletLocation(String option) {
        String name = place(option);
        // The amulet's own teleport dialog lists each destination as a sentence.
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1).trim();
        return AMULET_LOCATIONS.contains(name);
    }

    static final List<String> AMULET_LOCATIONS = names("amulet_locations");

    /**
     * A teleport click that names the amulet itself: an interface or worn-item widget carrying
     * the amulet's item id, the worn amulet's own minimenu target, or the amulet's teleport
     * dialog ("Where would you like to teleport to?", each option a sentence). A sentence-shaped
     * option (a menu option never ends in a period) counts too. A destination-named option that
     * names no amulet (a port travel menu) never books (owner 2026-10-14).
     */
    static boolean isAmuletTeleport(MenuOptionClicked event, String target, String option) {
        if (supported(weaponId(event)) == SAILORS_AMULET || target != null && target.contains("sailors' amulet")) return true;
        int dialogGroup = InterfaceID.Chatmenu.UNIVERSE >>> 16;
        if (event != null && event.getParam1() >>> 16 == dialogGroup) return true;
        var widget = event == null ? null : event.getWidget();
        return widget != null && widget.getId() >>> 16 == dialogGroup || option != null && option.trim().endsWith(".");
    }

    /** The Rub interface's six destination buttons; a click there is a teleport (owner 1.3.4). */
    static final Set<Integer> ATES_DESTINATIONS = byId("ates_destinations").keySet();

    /** A Rub-interface destination click; the event carries the packed component id. */
    static boolean isAtesDestination(MenuOptionClicked event) {
        if (event == null) return false;
        if (ATES_DESTINATIONS.contains(event.getParam1())) return true;
        var widget = event.getWidget();
        return widget != null && ATES_DESTINATIONS.contains(widget.getId());
    }

    /**
     * Spot anims play on for several ticks and the player can hold several, so only one that was
     * not playing at the last change is a new cast.
     *
     * <p>A local cast or shot graphic books this use's pinned recipe as a LIKELY estimate; the next
     * measured Check reconciles it at the captured prices. Any unpriced component fails the whole
     * estimate closed. A repeated graphic in one tick is one use.
     */
    void spotAnims(Iterable<ActorSpotAnim> anims, long now) {
        var playing = new HashSet<Long>();
        for (var anim : anims) {
            if (anim == null) continue;
            long key = (long) anim.getId() << 32 | anim.getStartCycle() & 0xFFFFFFFFL;
            if (playing.add(key) && !playingAnims.contains(key)) onGraphic(anim.getId(), now);
        }
        playingAnims = playing;
    }

    void onGraphic(int graphic, long now) {
        Item weapon = wornWeapon();
        var variant = weapon == null ? null : supported(weapon.getId());
        if (variant == null || graphic <= 0) return;
        boolean eye = variant == EYE_OF_AYAK;
        if (!isCastGraphic(variant, graphic) && !(eye && eyeGraphics.contains(graphic))) {
            if (eye) {
                // The eye's cast trigger is pinned in no table: learn it from the magic XP every
                // cast drops (owner 2026-09-29), then each cast books one tear like a spell.
                eyeGraphic = graphic;
                eyeCandidate(weapon);
            }
            return;
        }
        int tick = client.getTickCount();
        if (tick == lastCastTick && graphic == lastCastGraphic || eye && tick == attack)
            return;
        lastCastTick = tick;
        lastCastGraphic = graphic;
        if (eye) attack = tick;
        bookWorn(variant, weapon, now);
    }

    /**
     * A landed hit on the target. The scythe books one charge per damaging hit; the blowpipe books
     * any shot while its attack animation is live, because a 2-tick attack restarts the same
     * animation and never fires a second AnimationChanged. At most once per tick.
     */
    void onHit(int damage, long now) {
        Item weapon = wornWeapon();
        var variant = weapon == null ? null : supported(weapon.getId());
        // Blood fury: one charge per melee hit. Combat XP proves melee for every weapon
        // (owner 2026-09-29); a hit whose XP has not landed yet this tick stays pending
        // until that same tick's XP confirms the stance (owner 2026-10-01).
        if (damage > 0) {
            int tick = client.getTickCount();
            if (furyAmulet() != null)
                if (furyMelee(tick)) bookFury(now);
                else furyTick = tick;

        }
        if (variant == null) return;
        // Each landed splat books: a multi-target swing is several hits, not one attack.
        boolean scythe = variant == SCYTHE && damage > 0;
        Actor local = client.getLocalPlayer();
        boolean shot = variant == BLOWPIPE && local != null
                        && local.getAnimation() == 5061;
        if (!scythe && !shot) return;
        if (shot) {
            int tick = client.getTickCount();
            if (tick == attack) return;
            attack = tick;
        }
        bookWorn(variant, weapon, now);
    }

    /** A local attack animation books the pinned per-attack charge for crystal weapons. */
    /** A candidate eye cast on this tick; the same tick's magic XP proves it. */
    void eyeCandidate(Item weapon) {
        eyeTick = client.getTickCount();
        eyeWeapon = weapon.getId();
        eyeTarget = wornIdentity(EYE_OF_AYAK, weapon.getId());
    }

    void onAnimation(int animation, long now) {
        Item weapon = wornWeapon();
        var variant = weapon == null ? null : supported(weapon.getId());
        if (variant == null) return;
        if (variant == EYE_OF_AYAK) {
            if (!eyeAnimations.contains(animation)) {
                // Candidate cast animation; the same tick's magic XP proves it, like the graphic.
                eyeAnimation = animation;
                eyeCandidate(weapon);
                return;
            }
        } else if (!isPinnedAttack(variant, animation))
            return;
        int tick = client.getTickCount();
        if (tick == attack) return;
        attack = tick;
        bookWorn(variant, weapon, now);
    }

    /** Progress counts actual splinters consumed, not an average cost for every ranged shot. */
    void quiverProgress(int progress, long now) {
        int before = blessing;
        blessing = progress;
        int used = progress - before;
        Item cape = wornItem(EquipmentInventorySlot.CAPE);
        var load = engine.events.peek(LOAD_TARGET);
        if (before < 0 || used <= 0 || used > 2 || cape == null
                || supported(cape.getId()) != DIZANAS
                || load != null && load.variant == DIZANAS) return;
        String identity = heldIdentity(DIZANAS, EquipmentInventorySlot.CAPE);
        for (int i = 0; i < used; i++)
            bookAttackEstimate(DIZANAS, cape.getId(), identity, ActionKind.FIRE, now);
    }

    Item wornWeapon() {
        return wornItem(EquipmentInventorySlot.WEAPON);
    }

    Item wornItem(EquipmentInventorySlot slot) {
        var gear = container(InventoryID.WORN);
        return gear == null ? null : gear.getItem(slot.getSlotIdx());
    }

    void bookAttackEstimate(ChargedItem variant, int weaponId, String identity, ActionKind kind, long now) {
        // A hit is gameplay: resume the pauses gameplay ends (logout/idle/recovery) and start
        // tracking when automatic startup is on, before booking — so the very first attack after
        // logging in counts instead of arriving while the session is still catching up.
        engine.resume(now, PauseReason.LIFECYCLE, PauseReason.IDLE, PauseReason.RECOVERY);
        if (!engine.live()) engine.autoStart(now);
        var recipe = estimateRecipe(variant, identity, now);
        if (empty(recipe)) return;
        var receipt = engine.bookEstimatedCharge(identity, variant.displayName, kind, recipe, now);
        if (receipt != null) {
            receipt.castItemId = weaponId;
            if (variant == ATES) {
                atesOwner = identity;
                atesReceipt = receipt.id();
                atesAt = now;
            }
        }
    }

    /** How long after a pendant click its refusal message still cancels the estimate. */
    static final long REFUSAL_WINDOW_MILLIS = 3000;

    /** The newest pendant click's estimate, so a refusal can remove exactly that row (1.3.4). */
    String atesOwner, atesReceipt;
    long atesAt;

    /**
     * The game refused a pendant teleport ("you appear to already be at that location"), so the
     * click consumed nothing: the estimate that click booked is removed through the announced
     * automatic-evidence path (owner 1.3.4). Earlier pendant estimates are untouched.
     */
    void refusedTeleport(long now) {
        String identity = atesOwner, id = atesReceipt;
        atesOwner = null;
        atesReceipt = null;
        if (identity == null || id == null || now - atesAt > REFUSAL_WINDOW_MILLIS)
            return;
        engine.forfeitEstimate(identity, id);
    }

    /** A family may cast with more than one local graphic (Sanguinesti's Justiciar variant). */
    static boolean isCastGraphic(ChargedItem variant, int graphic) {
        return graphic > 0 && Arrays.stream(variant.graphics).anyMatch(id -> id == graphic);
    }

    /** The attack animations pinned for per-attack estimates (d20); false means none. */
    static boolean isPinnedAttack(ChargedItem variant, int animation) {
        return Arrays.stream(variant.animations).anyMatch(id -> id == animation);
    }

    /**
     * The equipment-side identity a worn Check uses: container group, weapon slot, item, variant.
     */
    static String wornIdentity(ChargedItem variant, int weapon) {
        return weaponIdentity(InterfaceID.Wornitems.UNIVERSE, EquipmentInventorySlot.WEAPON.getSlotIdx(), weapon,
                variant);
    }

    /**
     * The worn-container identity of a clicked item, by the slot that actually holds it. A Check
     * from the Worn Equipment tab arrives with param0 -1 (owner capture 2026-10-12), so the
     * clicked location is unknown; the item's own worn slot is the identity the estimates use.
     */
    String checkIdentity(ChargedItem variant, int weapon) {
        if (client == null || variant == null) return null;
        var gear = client.getItemContainer(InventoryID.WORN);
        if (gear == null) return null;
        for (var slot : EquipmentInventorySlot.values()) {
            Item item = gear.getItem(slot.getSlotIdx());
            if (item == null || supported(item.getId()) != variant || weapon > 0 && item.getId() != weapon) continue;
            return weaponIdentity(InterfaceID.Wornitems.UNIVERSE, slot.getSlotIdx(), item.getId(), variant);
        }
        return null;
    }

    /**
     * The eye's mode-aware per-cast recipe, keyed off its own charge line's wording (owner
     * 2026-09-30).
     */
    static Map<Integer, Long> eyeRecipe(boolean runeCharged) {
        var recipe = new LinkedHashMap<Integer, Long>();
        if (runeCharged) {
            recipe.put(ItemID.DEATHRUNE, 2L);
            recipe.put(ItemID.CHAOSRUNE, 1L);
        } else
            recipe.put(ItemID.DEMON_TEAR, 1L);
        return recipe;
    }

    /** One cast's pinned component quantities per family (d20), as a copy the caller may extend. */

    /** Fractional per-use components: [itemId, numerator, denominator] accruing toward one item. */

    /**
     * Darts a blowpipe shot spends, as {numerator, denominator}, by the cape slot (OSRS wiki): an
     * assembler or Dizana's quiver saves 80%, the accumulator 72%, the attractor 60%; none saves
     * none.
     */
    static long[] dartLoss(String capeName) {
        String cape = lower(capeName);
        if (cape.contains("assembler") || cape.contains("dizana")) return new long[] {1, 5};
        if (cape.contains("accumulator")) return new long[] {7, 25};
        return cape.contains("attractor") ? new long[] {2, 5} : new long[] {1, 1};
    }

    /** Whole units to book now and the remainder to keep, for a fractional component. */
    static long[] accrue(long carry, long amount, long denominator) {
        long total = carry + amount;
        return new long[] {total / denominator, total % denominator};
    }

    /**
     * Exact cumulative value for a fractional priced item: charges are booked in units of {@code
     * 1/perItem} of the item, so each whole item's price is divided without losing its remainder to
     * truncation. Returns the value this booking adds.
     */
    long carriedValue(ChargedItem variant, int id, long units, long price, int item) {
        if (units <= 0 || price <= 0 || item <= 1)
            return positive(units * max(0, price));
        // Only the new units are priced, at today's quote; earlier use keeps the price it was
        // booked
        // at (Codex review 1.2). The sub-gp remainder carries, and so does any 1 gp floor overpaid.
        long[] carry = valueCarry.computeIfAbsent(variant.name() + ":" + id, ignored -> new long[1]);
        long total = carry[0] + units * price, booked = max(1, total / item);
        carry[0] = total - booked * item;
        return booked;
    }

    /** Accrues one fractional component into the recipe (owner 1.4.0 size pass). */
    void accrueInto(ChargedItem variant, int id, long amount, long denominator, Map<Integer, Long> recipe) {
        String key = variant.name() + ":" + id;
        long[] accrued = accrue(amount(chargeCarry, key), amount, denominator);
        chargeCarry.put(key, accrued[1]);
        if (accrued[0] > 0) recipe.put(id, accrued[0]);
    }

    /** One cast's priced recipe, or null when a component lacks a supported valuation. */
    List<Flow> estimateRecipe(ChargedItem variant, String identity, long now) {
        Map<Integer, Long> recipe = new LinkedHashMap<>(variant.recipe);
        if (variant == EYE_OF_AYAK) recipe = eyeRecipe(Boolean.TRUE.equals(runes.get(identity)));
        long[] fraction = variant.fraction;
        if (fraction != null)
            accrueInto(variant, (int) fraction[0], fraction[1], fraction[2], recipe);
        if (variant == BLOWPIPE && dart > 0) {
            // Owner 1.1: each shot spends its dart unless an Ava's device saves it; the expected
            // share
            // accrues like the scales, and a Check reconciles it.
            Item cape = wornItem(EquipmentInventorySlot.CAPE);
            long[] loss = dartLoss(cape == null || cape.getId() <= 0 ? "" : itemName(cape.getId()));
            accrueInto(variant, dart, loss[0], loss[1], recipe);
        }
        var flows = new ArrayList<Flow>();
        for (var entry : recipe.entrySet()) {
            Flow flow = chargeFlow(variant, entry.getKey(), -entry.getValue(), now, true);
            if (flow == null) return null;
            flows.add(flow);
        }
        return flows;
    }

    /** One price/carry path; measured rows preserve their component id and unpriced evidence. */
    Flow chargeFlow(ChargedItem variant, int identifier, long units, long now, boolean estimate) {
        int id = lookup == null ? -1 : lookup.canon(identifier);
        var item = identifier <= 0 || id <= 0 ? null : lookup.composition(id);
        if (item == null || blank(item.getName())) return null;
        Flow priced = valuation == null ? null : valuation.valueComponent(id, units, now);
        long price = priced == null ? 0 : priced.unitPrice;
        if (estimate && price <= 0) return null;
        int perItem = variant.units;
        long value = multiply(units, price);
        if (perItem > 1 && price > 0) {
            value = -carriedValue(variant, identifier, Math.abs(units), price, perItem);
            price = max(1, price / perItem);
        }
        return new Flow(estimate ? id : identifier, item.getName(), units, price, value,
                priced == null ? UNPRICED : priced.source(), now);
    }

    /**
     * Link one numeric Check response to the item slot that was actually checked. The click
     * contains identity only; any other menu action supersedes the pending link.
     */
    void armCheck(MenuOptionClicked event, String option, String target) {
        engine.events.drop(CHECK_LINK);
        int raw = weaponId(event);
        var variant = supported(raw);
        if (variant == null) variant = supportedForName(target);
        if ("configure-charges".equals(option)) {
            // The bank's Configure-Charges loads or removes charges with bank items the plugin
            // never sees, so no Check of that kind can be compared across it (OSRS Wiki, 5 March
            // 2025).
            engine.resetChecks(variant);
            return;
        }
        if ("charge".equals(option) && variant != null && !variant.recipe.isEmpty()) {
            // A right-click Charge prompt applies the weapon's own component from the pack
            // (Tonalztics of Ralos: "You apply N charges…", OSRS Wiki transcript 2026-10-12).
            int part = variant.recipe.keySet().iterator().next();
            engine.markChargeLoad(
                    variant, part, itemName(part), clickedIdentity(event, variant, raw), LOAD_TICKS);
            return;
        }
        if (("uncharge".equals(option) || "unload".equals(option) || "empty".equals(option)) && variant != null) {
            // These actions can return loaded components. A later lower Check value
            // cannot be treated as consumed charges across that unmeasured return.
            engine.resetChecks(variant);
            // The return is the player's own charge investment coming back: an
            // ownership-neutral transfer, never income (owner 2026-09-29).
            markReturnTransfer();
            // Most weapons ask to confirm first, so the components can land long after this short
            // context: keep watching for them (watchReturn) instead of booking them as a gain.
            if (client != null) {
                var e = engine.events.put(UNCHARGE, -1, 1, RETURN_TICKS, null);
                e.variant = variant;
                e.at = client.getTickCount() + RETURN_TICKS;
                e.data = inventoryCounts();
            }
            return;
        }
        if (!"check".equals(option) || variant == null || !variant.implemented
                || variant.isCommittedLoad()) return;
        String identity = clickedIdentity(event, variant, raw);
        if (identity == null)
            // A Check from the Worn Equipment tab carries no slot (param0 -1, owner capture
            // 2026-10-12): the item's own worn slot names the baseline the estimates use.
            identity = checkIdentity(variant, raw);
        if (identity != null)
            startCheck(variant, identity, client.getTickCount(), 2);
    }

    /**
     * Each tick before booking: an Uncharge's own components landing make that change a transfer.
     */
    void watchReturn() {
        var e = engine.events.peek(UNCHARGE);
        if (e == null || client == null) return;
        if (client.getTickCount() > e.at) {
            engine.events.drop(UNCHARGE);
            return;
        }
        if (!returnLanded(e.variant, (Map<Integer, Long>) e.data, inventoryCounts(), this::itemName)) return;
        markReturnTransfer();
        engine.events.drop(UNCHARGE);
    }

    /** The returned charge is the player's own investment coming back: ownership-neutral. */
    void markReturnTransfer() {
        transfer("charge-unload");
    }

    /** A click that answers a chat dialog (the "Really uncharge?" Yes/No), not a new action. */
    static boolean isDialogAnswer(MenuOptionClicked event, String option) {
        return event.getMenuAction() == MenuAction.WIDGET_CONTINUE || "yes".equals(option) || "continue".equals(option);
    }

    /** True when an item that loads this weapon (its scales, darts or runes) increased. */
    static boolean returnLanded(ChargedItem variant, Map<Integer, Long> before, Map<Integer, Long> after,
            IntFunction<String> names) {
        for (var item : after.entrySet())
            if (item.getValue() > before.getOrDefault(item.getKey(), 0L)
                    && isLoadComponent(variant, item.getKey(), names.apply(item.getKey())))
                return true;

        return false;
    }

    String itemName(int id) {
        var composition = lookup == null ? null : lookup.composition(id);
        return composition == null ? null : composition.getName();
    }

    /** Canonical inventory quantities, so noted or placeholder ids compare as the item itself. */
    Map<Integer, Long> inventoryCounts() {
        var inventory = container(InventoryID.INV);
        var counts = new HashMap<Integer, Long>();
        if (inventory == null) return counts;
        for (Item item : inventory.getItems()) {
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) continue;
            int id = lookup == null ? item.getId() : lookup.canon(item.getId());
            counts.merge(id, (long) item.getQuantity(), Long::sum);
        }
        return counts;
    }

    /** Room to type a charge amount; any other click still ends the load. */
    static final int LOAD_TICKS = 25;

    /**
     * Arm one narrowly-scoped, ownership-neutral load only for a real Use-on-item action against an
     * exact supported weapon and a source component whose canonical id and item name both match
     * that weapon's recipe.
     */
    void armLoad(MenuOptionClicked event, String option) {
        // Answering the "How many charges?" prompt is part of the same load (owner 1.2).
        if (isDialogAnswer(event, option)) return;
        // A right-click Charge prompt arms its load in armCheck (the Tonalztics of Ralos).
        if ("charge".equals(option)) return;
        // On a widget-target click the clicked widget is the target; the item being used is the
        // client's selected widget. Either order loads: scales on the blowpipe, or the blowpipe on
        // them.
        var clicked = event.getWidget();
        var selected = client == null ? null : client.getSelectedWidget();
        var used = selected == null ? null : supported(selected.getItemId());
        if (event.getMenuAction() == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT && used != null) {
            // A weapon used on an object (the scythe on the Vyre well) loads its recipe from the
            // pack.
            int part = used.recipe.isEmpty() ? (int) used.fraction[0] : used.recipe.keySet().iterator().next();
            engine.markChargeLoad(used, part, String.valueOf(part), weaponIdentity(
                            selected.getId(), selected.getIndex(), selected.getItemId(), used), LOAD_TICKS);
            return;
        }
        if (event.getMenuAction() != MenuAction.WIDGET_TARGET_ON_WIDGET || clicked == null || selected == null) {
            engine.clearChargeLoad();
            return;
        }
        boolean weaponClicked = supported(clicked.getItemId()) != null;
        var weapon = weaponClicked ? clicked : selected;
        var variant = supported(weapon.getItemId());
        int source = canonical((weaponClicked ? selected : clicked).getItemId());
        String name = source <= 0 ? null : itemName(source);
        if (variant == null || name == null || !isLoadComponent(variant, source, name)) {
            engine.clearChargeLoad();
            return;
        }
        if (variant == BLOWPIPE && DARTS.containsValue(source))
            dart = source;
        String target = weaponIdentity(weapon.getId(), weapon.getIndex(), weapon.getItemId(), variant);
        engine.markChargeLoad(variant, source, name, target, LOAD_TICKS);
    }

    int canonical(int id) {
        return lookup == null || id <= 0 ? id : lookup.canon(id);
    }

    /** Resolve the weapon targeted by the menu action, distinct from an item-on-item source. */
    static int weaponId(MenuOptionClicked event) {
        if (event == null) return -1;
        int id = event.getId();
        if (supported(id) != null) return id;
        int identifier = event.getItemId();
        if (supported(identifier) != null) return identifier;
        if (event.getWidget() != null) {
            int widgetItemId = event.getWidget().getItemId();
            if (supported(widgetItemId) != null) return widgetItemId;
        }
        return -1;
    }

    /**
     * Same item/container/slot identity for the clicked weapon across Use-on-item and Check.
     * Without all location fields, a charge load remains Review only.
     */
    static String clickedIdentity(MenuOptionClicked event, ChargedItem variant, int weapon) {
        if (event == null) return null;
        int widget = event.getParam1();
        if (widget <= 0 && event.getWidget() != null) widget = event.getWidget().getId();
        return weaponIdentity(widget, event.getParam0(), weapon, variant);
    }

    /**
     * Container group, slot, item and variant uniquely identify one weapon: the same item in
     * inventory and equipment (or two slots in either container) cannot share a baseline, and a
     * worn estimate builds the identical identity from the equipment slot.
     */
    static String weaponIdentity(int widget, int slot, int weapon, ChargedItem variant) {
        int widgetGroup = widget >>> 16;
        return variant == null || weapon <= 0 || slot < 0 || widget <= 0 || widgetGroup <= 0 ? null : widgetGroup + ":" + slot + ":" + weapon + ":" + variant.name();
    }

    /** The item id inside a {@link #weaponIdentity} ("group:slot:item:variant"). */
}

/**
 * One explicit weapon Check response, expressed as its measured component stock. It does not infer
 * depletion from combat events, menu intent, or probabilities.
 */
@AllArgsConstructor
class ChargeCheck {
    static final int SCALES = ItemID.SNAKEBOSS_SCALE;
    static final Pattern BLOWPIPE_CHECK = pattern("blowpipe-check"), TYPED_DARTS = pattern("dart-count");
    static final Pattern EYE_OF_AYAK_CHECK = pattern("eye-tears-check"), EYE_RUNE_CHECK = pattern("eye-runes-check");
    static final Map<String, Integer> DARTS = new LinkedHashMap<>();

    static {
        for (String[] row : rows("darts")) DARTS.put(row[0], parseInt(row[1]));
    }

    /**
     * Charged-item families; each one's data (names, ids, triggers, recipe, Check shape) is a d20
     * row.
     */
    @Getter
    enum ChargedItem {
        TRIDENT_SEAS, TRIDENT_SWAMP, TRIDENT_SWAMP_ENHANCED, TRIDENT_SEAS_ENHANCED, BLOWPIPE, SHADOW, SANGUINESTI,
        SANGUINESTI_HOLY, WARPED, VENATOR, VENATOR_ECHO, EYE_OF_AYAK, SCYTHE, ATES, SAILORS_AMULET, TOME_OF_FIRE,
        CRYSTAL_BOW, CRYSTAL_HALBERD, BOWFA, SAELDOR, BLOOD_FURY, CRAW, WEBWEAVER, VIGGORA, URSINE, THAMMARON, ACCURSED,
        MEMOIRS, BRYOPHYTA, TOXIC_STAFF, SERPENTINE_HELM, CRYSTAL_HELM, CRYSTAL_BODY, CRYSTAL_LEGS, CRYSTAL_SHIELD,
        SUFFERING, TONALZTICS, TOME_OF_WATER, TOME_OF_EARTH, ETHEREUM, BONE_STAFF, CRYSTAL_AXE, CRYSTAL_PICKAXE,
        CRYSTAL_HARPOON, ECHO_BOOTS, SLAYER_STAFF_E, INFERNAL_AXE, INFERNAL_PICKAXE, INFERNAL_HARPOON, DIZANAS;
        String displayName;
        boolean implemented;
        String family;
        ActionKind action;
        int units;

        /** A load's components can never be refunded: the load books as spend, not a transfer. */
        boolean committedLoad;

        /** The Check line's pattern with its count group and its single-charge group (0: none). */
        Pattern check;

        int countGroup, oneGroup;
        int[] graphics, animations, itemIds;

        /** Components one use spends; a Check's count of uses times this is its measured stock. */
        Map<Integer, Long> recipe;

        /** A fractional per-use part, [itemId, numerator, denominator], or null. */
        long[] fraction;

        List<String> aliases;

        static {
            for (String[] row : rows("charged_items")) {
                ChargedItem item = valueOf(row[0]);
                item.displayName = row[1];
                item.implemented = Boolean.parseBoolean(row[2]);
                item.family = row[3];
                item.action = ActionKind.valueOf(row[4]);
                item.units = parseInt(row[5]);
                if (!"-".equals(row[6])) {
                    String[] check = row[6].split(" ");
                    item.check = pattern(check[0]);
                    item.countGroup = parseInt(check[1]);
                    item.oneGroup = parseInt(check[2]);
                }
                item.graphics = ids(row[7]);
                item.animations = ids(row[8]);
                var recipe = new LinkedHashMap<Integer, Long>();
                for (String part : parts(row[9], " "))
                    recipe.put(id(part), Long.parseLong(part.substring(0, part.indexOf('*'))));
                item.recipe = unmodifiableMap(recipe);
                if (!"-".equals(row[10])) {
                    String[] fraction = row[10].split("[/*]");
                    item.fraction = new long[] {
                                id(row[10]), Long.parseLong(fraction[0]), Long.parseLong(fraction[1])
                            };
                }
                item.aliases = parts(row[11], ";");
                item.itemIds = ids(row[12]);
            }
            for (String[] row : rows("committed_loads"))
                valueOf(row[0]).committedLoad = true;
        }

        static List<String> parts(String cell, String separator) {
            return "-".equals(cell) ? emptyList() : List.of(cell.split(separator));
        }

        /** The id in "qty*id=NAME" or "id=NAME". */
        static int id(String part) {
            return parseInt(part.substring(part.indexOf('*') + 1, part.indexOf('=')));
        }

        static int[] ids(String cell) {
            return parts(cell, ",").stream().mapToInt(ChargedItem::id).toArray();
        }
    }

    ChargedItem variant;
    Map<Integer, Long> counts;

    /** Returns -1 when a Check explicitly reported no darts or had an unknown type. */
    int dart;

    boolean supported;

    /**
     * Parse a pinned Check-message shape. Returns {@code null} for unrelated chat. Recognized but
     * unsupported/unknown variants return a non-bookable read so a tracker can clear an older
     * baseline safely.
     */
    static ChargeCheck parse(String rawMessage) {
        String message = stripTags(rawMessage);
        var surface = message == null ? null : surface(message.toLowerCase(ROOT));
        if (surface == null) return null;
        // A recognizably malformed Check surface resets the caller's old baseline
        // rather than carrying stale evidence forward.
        var components = new LinkedHashMap<Integer, Long>();
        int dartId = -1;
        try {
            var blowpipe = BLOWPIPE_CHECK.matcher(message);
            if (blowpipe.matches()) {
                components.put(SCALES, digits(blowpipe.group(2)));
                String dartPart = blowpipe.group(1).trim();
                var darts = TYPED_DARTS.matcher(dartPart);
                if (!"none".equalsIgnoreCase(dartPart)) {
                    dartId = darts.matches() ? DARTS.getOrDefault(normalizeName(darts.group(1)), -1) : -1;
                    if (dartId < 0) return new ChargeCheck(surface, emptyMap(), -1, false);
                    components.put(dartId, digits(darts.group(2)));
                }
            } else if (surface == EYE_OF_AYAK) {
                var eye = EYE_OF_AYAK_CHECK.matcher(message);
                var runes = EYE_RUNE_CHECK.matcher(message);
                if (eye.matches())
                    components.put(ItemID.DEMON_TEAR, digits(eye.group(1)));
                else if (runes.matches()) {
                    long charges = digits(runes.group(1));
                    components.put(ItemID.DEATHRUNE, multiplyExact(charges, 2));
                    components.put(ItemID.CHAOSRUNE, charges);
                }
            } else if (surface.check != null) {
                // Every other family's Check counts uses, so its stock is that count times one
                // use's recipe.
                var read = surface.check.matcher(message);
                if (read.matches()) {
                    String included = read.group(surface.countGroup);
                    long count = included != null ? digits(included)
                                    : surface.oneGroup > 0 && read.group(surface.oneGroup) != null ? 1 : 0;
                    for (var part : surface.recipe.entrySet())
                        components.put(part.getKey(), multiplyExact(count, part.getValue()));
                }
            }
            if (!components.isEmpty())
                return new ChargeCheck(surface, unmodifiableMap(components), dartId, true);
        } catch (NumberFormatException | ArithmeticException malformed) {
            // The count is syntax-checked; this is a value outside the supported range.
        }
        return new ChargeCheck(surface, emptyMap(), -1, false);
    }

    /** The Check surface a lowercase chat line belongs to, whether or not it parses. */
    static ChargedItem surface(String lower) {
        if (lower.startsWith("darts:") && lower.contains("scales:")) return BLOWPIPE;
        if (lower.startsWith("the pendant has ")) return ATES;
        if (lower.startsWith("the amulet has ") || lower.startsWith("your amulet has "))
            // The sailors' amulet's own Check wording ("...has 106 charges." or "...charges
            // left."); the blood fury's hit-count line names itself below (owner 2026-10-14).
            return SAILORS_AMULET;
        if (lower.startsWith("your tome has been charged with "))
        {
            // The page names the tome; a bare "your tome" line stays fire and the clicked row
            // redirects it (owner 2026-10-12).
            if (lower.contains("soaked")) return TOME_OF_WATER;
            return lower.contains("soiled") ? TOME_OF_EARTH : TOME_OF_FIRE;
        }
        if (lower.startsWith(msg("amulet-blood"))) return BLOOD_FURY;
        if (lower.startsWith(msg("eye-capacity")) || lower.startsWith(msg("eye-loaded")))
            return EYE_OF_AYAK;
        for (var variant : ChargedItem.values()) {
            if (variant == BLOWPIPE) continue;
            String name = variant.displayName.toLowerCase(ROOT);
            if (lower.startsWith("your " + name + " has ") || lower.startsWith("your " + name + " only has ")
                    || lower.startsWith(name + " has ") || lower.startsWith(name + " only has "))
                return variant;
        }
        // A Check that names no weapon ("Your bow has…"): the first row sharing that wording.
        for (var variant : ChargedItem.values())
            if (variant.check != null && variant.check.matcher(lower).matches()) return variant;
        return null;
    }

    /** Resolve only exact supported menu-target names; menu evidence is identity only. */
    static ChargedItem supportedForName(String title) {
        String name = normalizeName(title);
        if (name == null) return null;
        for (var variant : ChargedItem.values())
            if (variant.implemented && (variant.displayName.toLowerCase(ROOT).equals(name)
                            || variant.aliases.contains(name))) return variant;
        return null;
    }

    /**
     * Any catalogue variant by exact display name for presentation; estimate-only variants
     * included.
     */
    static ChargedItem namedVariant(String title) {
        String name = normalizeName(title);
        for (var variant : ChargedItem.values())
            if (variant.displayName.toLowerCase(ROOT).equals(name)) return variant;
        return null;
    }

    /** Resolve charged and uncharged item ids so a Check receipt can be tied to its menu target. */
    static ChargedItem supported(int identifier) {
        for (var variant : ChargedItem.values())
            for (int id : variant.itemIds)
                if (id == identifier) return variant;


        return null;
    }

    /**
     * Validate a candidate item-on-weapon load component. Both the canonical id and displayed name
     * must agree, so unknown dart types fail closed.
     */
    static boolean isLoadComponent(ChargedItem variant, int id, String name) {
        String expectedName = variant == null || !variant.implemented ? null : expectedLoadName(variant, id);
        String actualName = normalizeName(name);
        // Owner 1.2: what an item's own charge is made of is loaded, never spent, for every item.
        return actualName != null && variant != null && (variant.recipe.containsKey(id)
                        || variant.fraction != null && variant.fraction[0] == id) || expectedName != null && actualName != null && (expectedName.equals(actualName)
                        || actualName.equals(expectedName + "s") && !expectedName.endsWith("s"));
    }

    boolean hasDarts() {
        return dart >= 0;
    }

    /** Component units per priced item: a page feeds 20 charges, a shard 100. */

    /** The same measured count expressed in another same-pattern variant's own recipe. */
    static Map<Integer, Long> restock(ChargedItem target, ChargedItem source, Map<Integer, Long> counts) {
        if (counts.isEmpty() || source.recipe.isEmpty()) return counts;
        long unit = source.recipe.values().iterator().next(), count = counts.values().iterator().next() / unit;
        var stock = new LinkedHashMap<Integer, Long>();
        for (var part : target.recipe.entrySet()) stock.put(part.getKey(), count * part.getValue());
        return stock;
    }

    /**
     * A load component not in the item's own recipe: the blowpipe's darts, and the Eye of Ayak's
     * runes in rune mode (owner 2026-09-30). Everything else loads through its recipe.
     */
    static String expectedLoadName(ChargedItem variant, int id) {
        if (variant == BLOWPIPE)
            for (var dart : DARTS.entrySet())
                if (dart.getValue() == id) return dart.getKey();


        return variant != EYE_OF_AYAK ? null : id == ItemID.DEATHRUNE ? "death rune" : id == ItemID.CHAOSRUNE ? "chaos rune" : null;
    }

    static long digits(String raw) {
        return Long.parseLong(raw.replace(",", ""));
    }

    static String stripTags(String value) {
        return value == null ? null : value.replaceAll("<[^>]*>", "").trim();
    }

    static String normalizeName(String value) {
        String cleaned = stripTags(value);
        return empty(cleaned) ? null : cleaned.toLowerCase(ROOT).replaceAll("\\s+", " ");
    }
}

/** A load target's candidate losses; only a measured Check can reconcile their quantities. */
@AllArgsConstructor
class LoadTarget {
    ChargedItem variant;
    String targetIdentity;
    List<Flow> remaining, unknown;

    /** When the load was clicked; 0 when there was no load. */
    long clickedAt;
}

/**
 * Transient ownership of automatic charge estimates. The canonical receipts remain in the owning
 * {@link Session}; this index links one weapon (session and target identity) to those receipt ids
 * until a compatible measured Check closes the window. Never persisted.
 */
class ChargeEstimateJournal {
    static final int MAX_RECEIPTS = 512;

    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Receipt {
        final String key, transaction;
        final int item;
        long remaining;

    }

    /** Open receipts per weapon, oldest weapon first. */
    final LinkedHashMap<String, List<Receipt>> entries = new LinkedHashMap<>();

    /** Quantities evicted by the bound while their weapon's window is still open, per item. */
    final Map<String, Map<Integer, Long>> evicted = new HashMap<>();

    int count;

    static String key(String id, String target) {
        return lower(id) + "\n" + lower(target);
    }

    synchronized void clear() {
        entries.clear();
        evicted.clear();
        count = 0;
    }

    synchronized void record(String id, String target, String identifier, int itemId, long quantity) {
        if (blank(id) || blank(target) || blank(identifier) || itemId <= 0
                || quantity <= 0)
            return;
        String key = key(id, target);
        entries.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new Receipt(key, identifier, itemId, quantity));
        count++;
        while (count > MAX_RECEIPTS) {
            var first = entries.entrySet().iterator().next();
            var gone = first.getValue().remove(0);
            count--;
            // The window is still open: remember the evicted quantity so the next measured
            // delta is not booked on top of an estimate that is no longer tracked here.
            evicted.computeIfAbsent(gone.key, ignored -> new HashMap<>())
                    .merge(gone.item, gone.remaining, Long::sum);
            if (first.getValue().isEmpty()) entries.remove(first.getKey());
        }
    }

    /** Evicted quantity still inside this weapon's open window for one component. */
    synchronized long evictedOverlap(String id, String target, int identifier) {
        return evicted.getOrDefault(key(id, target), Map.of()).getOrDefault(identifier, 0L);
    }

    /** Pending receipts for one component of one weapon. */
    synchronized List<Receipt> receipts(String id, String target, int identifier) {
        return matching(receiptsFor(id, target), receipt -> receipt.item == identifier);
    }

    /** Every pending receipt for one weapon, whichever component it belongs to. */
    synchronized List<Receipt> receiptsFor(String id, String target) {
        return new ArrayList<>(entries.getOrDefault(key(id, target), List.of()));
    }

    synchronized void consume(Receipt receipt, long quantity) {
        var values = receipt == null ? null : entries.get(receipt.key);
        if (values == null || quantity <= 0 || !values.contains(receipt)) return;
        receipt.remaining = positive(receipt.remaining - quantity);
        if (receipt.remaining == 0) {
            values.remove(receipt);
            count--;
        }
        if (values.isEmpty()) entries.remove(receipt.key);
    }

    /** Forget every receipt, and all evicted overlap, for one weapon (its window closed). */
    synchronized void clearTarget(String id, String target) {
        String key = key(id, target);
        var removed = entries.remove(key);
        if (removed != null) count -= removed.size();
        evicted.remove(key);
    }
}

/** Measured negative component-count differences; valuation is applied by the caller. */
@Getter
@AllArgsConstructor(access = AccessLevel.PACKAGE)
class ChargeDelta {
    ChargedItem variant;
    List<ComponentDelta> componentDeltas;

    /** Components the Check read at the same count as before: their estimates were overcounts. */
    final Set<Integer> unchanged = new HashSet<>();

    /** Explicit Check outcome; this enum is transient accounting evidence, never save data. */
    enum CheckResult {
        SEED, SAME, SPEND, LOAD, MISMATCH
    }

    CheckResult result;

    /** An outcome without a measured decrease carries no bookable component quantity. */
    ChargeDelta(ChargedItem variant, CheckResult result) {
        this(variant, emptyList(), result);
    }

    /** One component's measured decrease (always negative). */
    @Getter
    @AllArgsConstructor
    static class ComponentDelta {
        int itemId;
        long quantityDelta;
    }

    /**
     * The GP cost of these priced component losses when they exactly match every measured decrease;
     * 0 otherwise. Missing, partial, extra, unsupported-priced or overflowing flows fail closed as
     * a whole. Generic inventory losses can never authorize charge spend.
     */
    long exactCost(List<Flow> losses) {
        if (result != CheckResult.SPEND || variant == null || !variant.implemented || componentDeltas.isEmpty()
                || losses == null || losses.size() != componentDeltas.size())
            return 0;
        var expected = new HashMap<Integer, Long>();
        var actual = new HashMap<Integer, Long>();
        long cost = 0;
        try {
            for (var component : componentDeltas) {
                if (component.itemId <= 0) return 0;
                expected.merge(component.itemId, component.quantityDelta, Math::addExact);
            }
            for (Flow flow : losses) {
                boolean validPriceSource = flow.item == ItemID.COINS
                                        ? flow.unitPrice == 1 && flow.source() == FACE_VALUE
                                        : flow.source() == GRAND_EXCHANGE
                                                || flow.source() == MANUAL_OVERRIDE;
                if (!validPriceSource || flow.quantity >= 0 || flow.item <= 0 || flow.unitPrice <= 0
                        || flow.value >= 0 || multiplyExact(flow.quantity, flow.unitPrice)
                                != flow.value)
                    return 0;
                actual.merge(flow.item, flow.quantity, Math::addExact);
                cost = addExact(cost, negateExact(flow.value));
            }
        } catch (ArithmeticException ex) {
            return 0;
        }
        return expected.equals(actual) ? cost : 0;
    }
}

/**
 * Compares explicit charge reads. One read establishes a baseline; only measured component
 * decreases can produce a booking-ready delta.
 */
class MeasuredChargeReadTracker {
    /**
     * Bounded number of same-variant weapons tracked side by side (two blowpipes, a spare trident).
     */
    static final int MAX_TARGETS = 8;

    /**
     * One baseline per stable target identity, most recently read last: each weapon's last read,
     * which its next Check is measured against.
     */
    LinkedHashMap<String, Baseline> baseline = new LinkedHashMap<>();

    /** One weapon's last exact read and its local observation time. */
    @Getter
    @AllArgsConstructor
    static class Baseline {
        ChargeCheck read;
        long atEpochMillis;

        /**
         * Component units loaded into the weapon since this read, which its next Check adds back.
         */
        final Map<Integer, Long> loaded = new HashMap<>();
    }

    /**
     * A load into one weapon, clicked at {@code clickedAt}: its next Check counts it as loaded, not
     * as unused. A load clicked before the last read is already in that read. A load whose weapon
     * is unknown goes to the only weapon of that kind with a read, or else makes that kind's reads
     * untrustworthy.
     */
    synchronized void noteLoad(ChargedItem variant, String target, long clickedAt, int id,
            long units) {
        var last = target == null ? only(variant) : baseline.get(target.trim());
        if (target == null && last == null) {
            resetVariant(variant);
            return;
        }
        if (last == null || clickedAt < last.atEpochMillis || units <= 0) return;
        last.loaded.merge(id, units, SafeMath::sum);
    }

    /** Observe one exact Check read and retain its local observation time. */
    synchronized ChargeDelta observe(ChargeCheck read, String target, long now) {
        if (read == null) return new ChargeDelta(null, CheckResult.MISMATCH);
        if (!read.supported || blank(target)) {
            reset();
            return new ChargeDelta(read.variant, CheckResult.MISMATCH);
        }
        String safeIdentity = target.trim();
        // Each weapon keeps its own baseline: a Check of the other blowpipe is neither a
        // decrease on this one nor a reason to forget what this one last read.
        var last = baseline.remove(safeIdentity);
        baseline.put(safeIdentity, new Baseline(read, positive(now)));
        while (baseline.size() > MAX_TARGETS)
            baseline.remove(baseline.keySet().iterator().next());
        var previous = last == null ? null : last.read;
        if (previous == null) return new ChargeDelta(read.variant, CheckResult.SEED);
        if (previous.variant != read.variant)
            return new ChargeDelta(read.variant, CheckResult.MISMATCH);
        boolean blowpipe = read.variant == BLOWPIPE;
        // Dart type is an independent component identity. A type switch can return old darts
        // and load new ones, so do not infer dart spend from the change; scale measurements
        // remain valid across that switch.
        boolean dartTypeSwitch = blowpipe && read.hasDarts()
                        && (!previous.hasDarts() || previous.dart != read.dart);
        var previousCounts = previous.counts;
        var loaded = last.loaded;
        var nextCounts = new LinkedHashMap<>(read.counts);
        if (blowpipe && previous.hasDarts() && !read.hasDarts())
            nextCounts.put(previous.dart, 0L);
        var ids = new LinkedHashSet<>(previousCounts.keySet());
        ids.addAll(nextCounts.keySet());
        var decreases = new ArrayList<ChargeDelta.ComponentDelta>();
        var unchanged = new ArrayList<Integer>();
        boolean same = true;
        for (int id : ids) {
            long before = amount(previousCounts, id);
            // What was loaded since is not use: count it as if it had been there all along.
            long after = amount(nextCounts, id) - amount(loaded, id);
            same &= after == before;
            if (after == before) unchanged.add(id);
            if (after < before && (!dartTypeSwitch || id == ItemID.SNAKEBOSS_SCALE))
                decreases.add(new ChargeDelta.ComponentDelta(id, after - before));
        }
        // Independent decreases still prove spend when a different component was loaded.
        var delta = new ChargeDelta(read.variant, decreases, !decreases.isEmpty() ? CheckResult.SPEND : same
                                        ? CheckResult.SAME : CheckResult.LOAD);
        delta.unchanged.addAll(unchanged);
        return delta;
    }

    synchronized void reset() {
        baseline.clear();
    }

    /** The one baseline of this kind, or null when there are none or several. */
    Baseline only(ChargedItem variant) {
        Baseline found = null;
        for (var last : baseline.values()) {
            if (last.read.variant != variant) continue;
            if (found != null) return null;
            found = last;
        }
        return found;
    }

    /**
     * Forget one variant's baselines after a custody change: a new physical instance of the weapon
     * entered the inventory, so a stored baseline can no longer be trusted to belong to the item
     * now occupying the slot. The next Check seeds a fresh baseline.
     */
    synchronized void resetVariant(ChargedItem variant) {
        if (variant == null) return;
        baseline.values().removeIf(last -> last.read.variant == variant);
    }
}

/**
 * Key and chest claims. The account's unresolved claims are the only durable part: a claim opens
 * when a key audit row is booked (the first key type keeps the row's id, more types derive {@code
 * id#index}), shrinks as its chest contents settle or its keys are lost on death, and is removed at
 * zero. Only a claim that reached zero writes its id onto the settling transaction as {@code
 * sourceClaimId}, so a reload that sees both drops the stale claim without booking again, while a
 * partly settled claim keeps its persisted remainder. Claims never expire on their own.
 *
 * <p>Everything else is short-lived evidence: an ordinary chest's object click (and a key loss seen
 * while it is armed), the Wilderness loot-key chest container removals, and the local-death key
 * snapshot. Chest contents never become durable provenance. Invoked only under the engine monitor.
 */
class KeyClaims {
    static final int OPEN_WINDOW_TICKS = 4, DEATH_WINDOW_TICKS = 8, CLAIM_EVIDENCE_TICKS = 4;
    static final int[] KEY_ITEM_IDS = intColumn("loot_key_slots", 0);
    static final int[] KEY_CONTAINER_IDS = intColumn("loot_key_slots", 1);
    static final String LOOT_KEY_NOTE = msg("loot-key");
    List<PendingClaim> claims = new ArrayList<>();

    /** The chest object click, armed for a few ticks. */
    ChestOpen open;

    /** A key loss seen while armed whose contents have not settled yet. */
    ChestOpen used;

    Map<Integer, Long> keysAtDeath;
    int deathTicks;
    boolean visible;
    Map<Integer, Map<Integer, Long>> contents = new HashMap<>();

    /**
     * Contents removed from each loot key's chest container, kept for a few ticks as claim
     * evidence.
     */
    Map<Integer, Removed> removed = new HashMap<>();

    /** A chest opened with one key type: the key, how many were used and the chest's name. */
    @AllArgsConstructor
    static class ChestOpen {
        int key;
        long quantity;
        String chest;

        @Getter(AccessLevel.NONE)
        int ticks;
    }

    @AllArgsConstructor
    static class Removed {
        Map<Integer, Long> contents;
        int ticks;
    }

    // -- Durable claims --
    void clear() {
        claims.clear();
    }

    /** Restores the persisted claims and drops any already settled by a retained transaction. */
    void restore(SavedState state, List<Session> sessions) {
        claims.clear();
        var settled = new HashSet<String>();
        for (var session : sessions)
            for (var transaction : session == null ? Collections.<Transaction>emptyList()
                            : session.transactions())
                if (transaction != null && !transaction.claimId().isEmpty())
                    settled.add(transaction.claimId());


        var seen = new HashSet<String>();
        for (var claim : state.pendingClaims())
            if (claim != null && claim.isValid() && seen.add(claim.claimId())
                    && !settled.contains(claim.claimId()))
                claims.add(claim);

        sort();
    }

    void adoptFrom(KeyClaims draft) {
        claims.clear();
        claims.addAll(draft.claims);
    }

    void writeTo(SavedState state) {
        state.setPendingClaims(new ArrayList<>(claims));
    }

    List<PendingClaim> all() {
        return unmodifiableList(claims);
    }

    /** Opens a claim keyed by the audit row's id; a second open with the same id is ignored. */
    void open(String id, int key, long quantity, long now) {
        if (empty(id) || key <= 0 || quantity <= 0 || indexOf(id) >= 0) return;
        claims.add(new PendingClaim(id, key, quantity, now));
        sort();
    }

    /**
     * Applies a settlement or death loss of {@code quantity} keys to the oldest claims for the key.
     * Returns the id of the last claim it touched when that claim closed; null otherwise.
     */
    String consume(int key, long quantity) {
        String closed = null;
        long remaining = positive(quantity);
        for (int index = 0; index < claims.size() && remaining > 0; index++) {
            var claim = claims.get(index);
            if (claim.itemOrKeyId != key) continue;
            long applied = min(remaining, claim.quantity());
            remaining -= applied;
            closed = reduce(index, applied) ? claim.claimId() : null;
            index -= closed == null ? 0 : 1;
        }
        return closed;
    }

    /** Settles one specific claim by {@code quantity}; returns its id when it closed. */
    String settle(String id, long quantity) {
        int index = indexOf(id);
        boolean closed = index >= 0 && reduce(index, positive(quantity));
        sort();
        return closed ? id : null;
    }

    /** Shrinks the claim at {@code index}; true when it reached zero and was removed. */
    boolean reduce(int index, long quantity) {
        var claim = claims.get(index);
        long left = claim.quantity() - quantity;
        if (left > 0) {
            claims.set(index, new PendingClaim(claim.claimId(), claim.itemOrKeyId, left,
                            claim.createdAtEpochMillis()));
            return false;
        }
        claims.remove(index);
        return true;
    }

    int indexOf(String id) {
        for (int index = 0; index < claims.size(); index++)
            if (claims.get(index).claimId().equals(id)) return index;

        return -1;
    }

    /** Created-at then claim id: equal timestamps never reorder across save and reload. */
    void sort() {
        claims.sort(Comparator.comparingLong(PendingClaim::createdAtEpochMillis)
                        .thenComparing(PendingClaim::claimId));
    }

    // -- Short-lived evidence --
    /** All transient evidence goes; the durable claims stay. */
    void clearEvidence() {
        cancelChest();
        keysAtDeath = null;
        deathTicks = 0;
        visible = false;
        contents.clear();
        removed.clear();
    }

    void tick() {
        open = open == null || --open.ticks <= 0 ? null : open;
        used = used == null || --used.ticks <= 0 ? null : used;
        if (deathTicks > 0 && --deathTicks == 0) keysAtDeath = null;
        removed.values().removeIf(removed -> --removed.ticks <= 0);
    }

    /** Arms (or disarms) ordinary key-chest provenance from an actual chest object click. */
    boolean chestClick(String option, String target) {
        var entry = KeyChestCatalogue.entryForMenu(option, target);
        used = null;
        open = entry == null ? null : new ChestOpen(entry.keyItemId, 0, entry.chestName, OPEN_WINDOW_TICKS);
        return open != null;
    }

    void cancelChest() {
        open = null;
        used = null;
    }

    /**
     * Correlate a settled key loss with settled positive inventory contents. A click alone is never
     * a claim, and ambiguous keys with multiple chest types require the observed object name.
     */
    ChestOpen matchChest(Map<Integer, Long> keyLosses, Map<Integer, Long> positiveContents,
            String sourceActivity) {
        boolean sourced = !blank(sourceActivity);
        var lost = positive(keyLosses);
        if (empty(positiveContents)) {
            if (sourced)
                cancelChest();
            else if (open != null && lost.size() == 1 && lost.containsKey(open.key)) {
                // The key left before its contents: remember the use for a few ticks.
                used = new ChestOpen(open.key, lost.get(open.key), open.chest, OPEN_WINDOW_TICKS);
                open = null;
            }
            return null;
        }
        int key = -1;
        long quantity = 0;
        String name = null;
        if (!lost.isEmpty()) {
            var armed = open != null ? open : used;
            var loss = lost.entrySet().iterator().next();
            if (lost.size() != 1 || armed != null && armed.key != loss.getKey()) {
                cancelChest();
                return null;
            }
            key = loss.getKey();
            quantity = loss.getValue();
            var entries = KeyChestCatalogue.entriesForKey(key);
            name = open != null && open.key == key ? open.chest
                            : entries.size() == 1 ? entries.get(0).chestName : null;
            used = null;
        } else if (used != null) {
            key = used.key;
            quantity = used.quantity;
            name = used.chest;
            used = null;
        }
        if (key < 0 || quantity <= 0 || name == null) {
            // Do not let an uncorroborated gain leave an object click armed to
            // relabel a later, unrelated key loss and contents change.
            open = null;
            return null;
        }
        var sourceChest = sourced ? KeyChestCatalogue.forChest(sourceActivity) : null;
        if (sourced && (sourceChest == null || !name.equalsIgnoreCase(sourceChest.chestName))) {
            cancelChest();
            return null;
        }
        if (open != null && open.key == key && open.chest != null
                && !open.chest.equalsIgnoreCase(name))
            return null;
        cancelChest();
        return new ChestOpen(key, quantity, name, 0);
    }

    void beginLocalDeath(Map<Integer, Long> keyInventory) {
        var keys = keys(keyInventory);
        keysAtDeath = keys.isEmpty() ? null : keys;
        deathTicks = keysAtDeath == null ? 0 : DEATH_WINDOW_TICKS;
    }

    boolean settlingDeath() {
        return keysAtDeath != null && deathTicks > 0;
    }

    void setLootChestVisible(boolean visible) {
        if (this.visible && !visible)
            // Reopening a key UI starts a fresh container-delta baseline. A
            // short, already-observed claim delta may still settle afterward.
            contents.clear();
        this.visible = visible;
    }

    /**
     * Observe one loot key's server-owned contents while its interface is visible. Only removals
     * from this container can correlate settled INV gains to a claim.
     */
    void observeKeyContents(int keyItemId, Map<Integer, Long> contents) {
        int index = keyIndex(keyItemId);
        if (!visible || index < 0 || contents == null) return;
        var next = cash(contents);
        var removed = minus(this.contents.put(index, next), next);
        if (!removed.isEmpty()) {
            Removed evidence = this.removed.computeIfAbsent(index, key -> new Removed(new HashMap<>(), 0));
            removed.forEach((id, quantity) -> evidence.contents.merge(id, quantity, SafeMath::sum));
            evidence.ticks = CLAIM_EVIDENCE_TICKS;
        }
    }

    /** The last contents observed in one loot key's chest, or an empty map (read-only). */
    Map<Integer, Long> observedContents(int key) {
        int index = keyIndex(key);
        var contents = index < 0 ? null : this.contents.get(index);
        return contents == null ? emptyMap() : contents;
    }

    /**
     * Settles the one single-key loot-key claim whose chest removal (seen within the evidence
     * window) covers these settled gains. One matched manifest accounts for one physical key at
     * most, so a stacked claim shrinks one unit at a time; exhausting the evidence supplies that
     * unit even when the key-loss callback arrived separately. Returns the claim id when it closed.
     * Descriptive: never alters the settling transaction's type, valuation or flows.
     */
    String settleLootKey(Map<Integer, Long> positiveFlows, Map<Integer, Long> keyLosses) {
        if (empty(positiveFlows)) return null;
        PendingClaim match = null;
        int index = -1;
        for (var evidence : removed.entrySet()) {
            PendingClaim claim = covers(evidence.getValue().contents, positiveFlows)
                            ? uniquePending(keyItemId(evidence.getKey())) : null;
            if (claim != null && claim.quantity() > 0) {
                if (match != null) return null;
                match = claim;
                index = evidence.getKey();
            }
        }
        if (match == null) return null;
        long usedKeys = keyLosses == null ? 0L : keyLosses.getOrDefault(keyItemId(index), 0L);
        var evidence = removed.get(index);
        evidence.contents = minus(evidence.contents, positiveFlows);
        evidence.ticks = CLAIM_EVIDENCE_TICKS;
        if (evidence.contents.isEmpty()) removed.remove(index);
        return settle(match.claimId(), evidence.contents.isEmpty() && usedKeys <= 0 ? 1 : usedKeys);
    }

    /** The one unresolved claim for this key item when exactly one exists; null otherwise. */
    PendingClaim uniquePending(int key) {
        PendingClaim match = null;
        for (var claim : claims) {
            if (claim.itemOrKeyId != key) continue;
            if (match != null) return null;
            match = claim;
        }
        return match;
    }

    /**
     * Closes pending loot-key claims only for key units that disappeared from INV in a local-death
     * settlement, measured against the death snapshot.
     */
    void recordDeathLosses(List<Flow> settledFlows, Map<Integer, Long> currentKeyInventory) {
        if (!settlingDeath()) return;
        var flowLosses = losses(settledFlows, KeyClaims::lootKey);
        var gone = minus(keysAtDeath, keys(currentKeyInventory));
        for (int key : KEY_ITEM_IDS) {
            long pendingKeys = 0;
            for (var claim : claims)
                pendingKeys += claim.itemOrKeyId == key ? claim.quantity() : 0;
            long matchedLoss = min(pendingKeys, min(amount(gone, key),
                                    amount(flowLosses, key)));
            if (matchedLoss > 0) {
                consume(key, matchedLoss);
                // Inventory/equipment changes can stabilize in separate batches after a death.
                // Keep the bounded snapshot until each confirmed key loss is consumed or the
                // game-tick window expires; an unrelated first batch must not make a later
                // key-loss flow look like an ordinary cost.
                take(keysAtDeath, key, matchedLoss);
            }
        }
        if (keysAtDeath.isEmpty()) {
            keysAtDeath = null;
            deathTicks = 0;
        }
    }

    // -- Key identity --
    static boolean lootKey(int id) {
        return keyIndex(id) >= 0;
    }

    static int keyIndex(int id) {
        for (int index = 0; index < KEY_ITEM_IDS.length; index++)
            if (KEY_ITEM_IDS[index] == id) return index;

        return -1;
    }

    static int keyItemId(int index) {
        return index < 0 || index >= KEY_ITEM_IDS.length ? -1 : KEY_ITEM_IDS[index];
    }

    static int keyContainerId(int index) {
        return index < 0 || index >= KEY_CONTAINER_IDS.length ? -1 : KEY_CONTAINER_IDS[index];
    }

    /**
     * Positive item quantities in a container, skipping items {@code skip} rejects (nested crates).
     */
    static Map<Integer, Long> manifestFromContainer(ItemContainer container, IntPredicate skip) {
        // Refuse an incomplete evidence policy rather than accidentally valuing a
        // nested crate as if its unopened contents were already manifest items.
        var manifest = new LinkedHashMap<Integer, Long>();
        if (container == null || container.getItems() == null || skip == null) return emptyMap();
        for (Item item : container.getItems())
            if (item != null && item.getId() >= 0 && item.getQuantity() > 0 && !skip.test(item.getId()))
                manifest.merge(item.getId(), (long) item.getQuantity(), SafeMath::sum);

        return unmodifiableMap(manifest);
    }

    static Map<Integer, Long> keys(ItemContainer inventory) {
        return manifestFromContainer(inventory, id -> !lootKey(id));
    }

    static Map<Integer, Long> keys(Map<Integer, Long> counts) {
        var keys = positive(counts);
        keys.keySet().removeIf(id -> !lootKey(id));
        return keys;
    }
}

/**
 * While a clue/casket/key path is active, digs / teleports / key spends attach as costs against
 * that clue's Pending Rewards → Claimed batch. Unmatched supply spends stay normal Used.
 */
class ClueCostPairing {
    String clue, label;

    synchronized void beginClue(String encounter, String label) {
        clue = emptyToNull(encounter);
        this.label = emptyToNull(label);
    }

    synchronized void endClue() {
        clue = null;
        label = null;
    }

    synchronized String activeEncounter() {
        return clue;
    }

    /**
     * Classify a spend note while a clue path may be open. Dig / teleport / clue key → CLUE_COST;
     * food / potions → UNRELATED_USED.
     */
    synchronized boolean isClueCost(String noteOrOption) {
        String lower = norm(noteOrOption);
        return clue != null && lower != null && clueCost(lower);
    }

    static boolean clueCost(String note) {
        return !empty(note) && (note.contains("dig") || note.contains("teleport") || note.contains("tele ")
                || note.contains("clue") || note.contains("casket") || note.contains("spade")
                || note.contains("key") && has(note, "clue", "chest"));
    }

    static boolean clueActivity(String name) {
        String lower = norm(name);
        return lower != null && has(lower, "clue", "casket", "treasure trail");
    }

    synchronized String costNote() {
        String label = this.label == null ? "clue" : this.label;
        return "Clue cost paired to " + label;
    }

    synchronized void clear() {
        endClue();
    }

    static String emptyToNull(String value) {
        return blank(value) ? null : value.trim();
    }
}

/**
 * Wiki-sourced key/chest pairs used for deferred-claim valuation and provenance. Chest names and
 * locations follow the OSRS Wiki pages for the <a
 * href="https://oldschool.runescape.wiki/w/Crystal_chest">Crystal chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Elf_crystal_chest">Elven Crystal Chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Larran%27s_small_chest">Larran's small chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Larran%27s_big_chest">Larran's big chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Ogre_Coffin">Ogre coffin</a>, <a
 * href="https://oldschool.runescape.wiki/w/Muddy_chest">Muddy chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Sinister_chest">Sinister chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Grubby_chest">Grubby chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Brimstone_chest">Brimstone chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Moons_rewards">Moon chest</a>, and <a
 * href="https://oldschool.runescape.wiki/w/Category%3AShades_of_Mort%27ton_%28minigame%29">Shades
 * of Mort'ton chest/key variants</a> (for example, <a
 * href="https://oldschool.runescape.wiki/w/Steel_Chest_%28brown%29">Steel Chest (brown)</a>). Key
 * names/tradeability follow the item pages for <a
 * href="https://oldschool.runescape.wiki/w/Crystal_key">Crystal key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Enhanced_crystal_key">Enhanced crystal key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Larran%27s_key">Larran's key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Ogre_coffin_key">Ogre coffin key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Muddy_key">Muddy key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Sinister_key">Sinister key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Grubby_key">Grubby key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Brimstone_key">Brimstone key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Moon_key">Moon key</a>, and <a
 * href="https://oldschool.runescape.wiki/w/Shade_key">Shade key variants</a> and the <a
 * href="https://oldschool.runescape.wiki/w/Bronze_key_crimson">Bronze key crimson</a>, <a
 * href="https://oldschool.runescape.wiki/w/Steel_key_brown">Steel key brown</a>, <a
 * href="https://oldschool.runescape.wiki/w/Black_key_black">Black key black</a>, <a
 * href="https://oldschool.runescape.wiki/w/Silver_key_red">Silver key red</a>, and <a
 * href="https://oldschool.runescape.wiki/w/Gold_key_purple">Gold key purple</a> pages. Tradeable
 * keys keep their market opportunity cost. Untradeable keys represent an unclaimed reward and are
 * deliberately held at zero until contents enter inventory. The Wiki explicitly documents
 * consumption for Crystal, Muddy, Sinister and Grubby keys (<a
 * href="https://oldschool.runescape.wiki/w/Crystal_key">Crystal key</a>, <a
 * href="https://oldschool.runescape.wiki/w/Muddy_chest">Muddy chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Money_making_guide/Opening_sinister_chests">Sinister
 * chest</a>, <a
 * href="https://oldschool.runescape.wiki/w/Money_making_guide/Opening_grubby_chests">Grubby
 * chest</a>). The Enhanced crystal, Larran, Ogre coffin, Brimstone, Moon and Shade pages describe
 * using their keys but do not explicitly state that chest use consumes them. Every claim match
 * therefore still requires a measured inventory key loss; a click only disambiguates a chest, and
 * live acceptance must confirm consumption for those families.
 */
class KeyChestCatalogue {
    static final List<Entry> ENTRIES = new ArrayList<>();

    static {
        for (String[] row : rows("key_chests"))
            ENTRIES.add(new Entry(parseInt(row[0]), row[1], Boolean.parseBoolean(row[2])));
    }

    /** Catalogue entries for a key item; Larran's key has both chest variants. */
    static List<Entry> entriesForKey(int key) {
        return matching(ENTRIES, entry -> entry.keyItemId == key);
    }

    /** First known chest association for an item, or {@code null} when not catalogued. */
    static Entry forKey(int key) {
        var entries = entriesForKey(key);
        return entries.isEmpty() ? null : entries.get(0);
    }

    /** Match an observed object target that contains one exact catalogue chest label. */
    static Entry forChest(String target) {
        if (blank(target)) return null;
        String normalizedTarget = normalize(target);
        Entry best = null;
        for (Entry entry : ENTRIES) {
            String name = normalize(entry.chestName);
            if (normalizedTarget.contains(name)
                    && (best == null || name.length() > normalize(best.chestName).length()))
                best = entry;
        }
        return best;
    }

    /** Arm a short-lived chest label for measured key-loss disambiguation only. */
    static Entry entryForMenu(String option, String target) {
        String action = option == null ? "" : normalize(option);
        return !(action.equals("open") || action.startsWith("open ") || action.equals("unlock")
                || action.startsWith("unlock ") || action.equals("search") || action.startsWith("search ")) ? null : forChest(target);
    }

    /** Deferred claims have no held value and are excluded from high-alchemy fallback. */
    static boolean isDeferredKey(int key) {
        Entry entry = forKey(key);
        return entry != null && !entry.tradeable;
    }



    static String normalize(String value) {
        return value.trim().replace('’', '\'').toLowerCase(ROOT);
    }

    /** Immutable key-to-chest information for accounting and presentation. */
    @AllArgsConstructor
    static class Entry {
        @Getter int keyItemId;
        @Getter String chestName;
        @Getter boolean tradeable;
    }
}

/** Untradeable unopened claims held at zero: their contents become income only on receipt. */
class DeferredClaims {
    /** A key/chest claim or a pouch. */
    static boolean of(int id) {
        return KeyChestCatalogue.isDeferredKey(id) || pouch(id);
    }

    /** Coin pouches and the Guardians of the Rift intricate pouch: contents book when opened. */
    static boolean pouch(int id) {
        return id >= ItemID.PICKPOCKET_COIN_POUCH_CITIZEN && id <= ItemID.PICKPOCKET_COIN_POUCH_ELF
                || id == ItemID.PICKPOCKET_COIN_POUCH_VYRE
                || id == ItemID.PICKPOCKET_COIN_POUCH_VARLAMORE_WEALTHY
                || id == ItemID.PICKPOCKET_COIN_POUCH_PIRATE || id == ItemID.GOTR_INTRICATE_POUCH;
    }
}

/**
 * Reward-interface / chest adapters → Pending Rewards → Claimed once. Name matching for Loot
 * Tracker EVENT + live reward UIs.
 */
class RewardChestCatalogue {
    /** Reward fragments share the same once-compiled matcher as other evidence tables. */
    static final Map<String, Clause[][]> RULES = rules("reward_chests");

    /** True when this LT/event name is a reward chest / raid / clue pool — not floor loot. */
    static boolean isRewardChest(String raw) {
        if (blank(raw)) return false;
        if (KeyChestCatalogue.forChest(raw) != null) return true;
        String name = lower(raw);
        return RULES.values().stream().anyMatch(c -> has(name, c));
    }
}

/** Polls the real loot-key chest interface; menu and chat wording is never key source evidence. */
@Singleton
@AllArgsConstructor(onConstructor_ = @Inject)
class LootKeyManifestReader {
    final Client client;
    final ItemLookup lookup;
    final Engine engine;

    void poll() {
        if (client == null || engine == null) return;
        boolean visible = Widgets.visible(client, InterfaceID.WildyLootChest.UNIVERSE);
        engine.setChestVisible(visible);
        if (!visible) return;
        for (int index = 0; index < 5; index++) {
            int key = KeyClaims.keyItemId(index);
            var keyContents = client.getItemContainer(KeyClaims.keyContainerId(index));
            if (keyContents == null) continue;
            var rawManifest = KeyClaims.manifestFromContainer(keyContents, this::isNestedContainerItem);
            var manifest = new HashMap<Integer, Long>();
            for (var item : rawManifest.entrySet()) {
                int canonical = lookup == null ? item.getKey() : lookup.canon(item.getKey());
                long quantity = item.getValue();
                if (canonical <= 0 || quantity <= 0) continue;
                manifest.merge(canonical, quantity, SafeMath::sum);
            }
            // An open chest is evidence only; settled inventory gains carry the financial truth.
            engine.observeKeyContainer(key, manifest);
        }
    }

    boolean isNestedContainerItem(int id) {
        String name = itemName(id);
        return name == null || has(name, "crate", "casket");
    }

    String itemName(int id) {
        if (lookup == null) return null;
        try {
            var composition = lookup.composition(id);
            return composition == null || composition.getName() == null || composition.getName().trim().isEmpty() ? null
                    : composition.getName().toLowerCase(ROOT);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
