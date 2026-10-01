package com.gpmanager;
import static com.gpmanager.GameData.msg;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.util.*;
import javax.inject.Inject;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.*;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.*;
import net.runelite.client.game.*;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;
import org.slf4j.*;
import static com.gpmanager.ModelText.*;
import static com.gpmanager.PauseReason.*;
import static net.runelite.api.GameState.*;
import static java.lang.Math.*;
import static com.gpmanager.Context.*;
@PluginDescriptor(
name = "GP Manager", internalName = "gp-manager", legacyDataDirectory = "profit-manager",
description = "Passive profit tracking: profit per Grind and per hour, a ledger you can correct, all-time totals, PvP results, a loot HUD and CSV export.",
tags = {"profit", "gp", "loot", "costs", "supplies", "sessions", "pking", "pk", "hud"})
public class GpManagerPlugin extends Plugin {
/** Idle ticks a bank, market or loot context stays available. */
static final int CORRELATION_TICKS = 6;
enum LocalDeathDisposition {
TRACK_PK, TRACK_PVM_RECLAIM, UNCLASSIFIED
}

static final Logger log = LoggerFactory.getLogger(GpManagerPlugin.class);
static final int SAVE_INTERVAL_TICKS = 50;
/** Unchanged profiles save every 500 ticks (5 minutes) instead of every 30 seconds. */
static final int IDLE_SAVE_TICKS = 500;
long savedRevision = -1L;
@Inject
Client client;
@Inject
Gson gson;
@Inject
GpManagerConfig config;
@Inject
ConfigManager configManager;
@Inject
ContainerSnapshotFactory snapshotFactory;
@Inject
ItemManager itemManager;
@Inject
net.runelite.client.game.SpriteManager spriteManager;
@Inject
Engine engine;
@Inject
CsvExporter csvExporter;
@Inject
SessionRepository repository;
@Inject
PersistenceCoordinator persistence;
@Inject
InteractionContextTracker interactionContextTracker;
@Inject
CharacterIdleTracker characterIdleTracker;
@Inject
ActivityDetector activity;
@Inject
ChargeIntake charges;
/** Single valuation authority: the plugin refreshes its world/economy snapshot on the client thread. */
@Inject
ItemValuationService valuation;
@Inject
LootKeyManifestReader lootKeys;
@Inject
OfferLedger geOfferLedger;
@Inject
PvpContextReader pvp;
@Inject
ClientThread clientThread;
@Inject
OverlayManager overlayManager;
/** HUD+ overlay, its folio and the builder the sidebar feeds each tick; null while not installed. */
volatile HudBuilder hud;
HudOverlay hudOverlay;
HudFolio hudFolio;
@Inject
ClientToolbar clientToolbar;
/** Existing Ground Items preference authority; the sidebar reads its decision, never re-derives it. */
@Inject
LootPresentationFilterService lootPresentationFilter;
/** The restored sidebar, bound as a presentation host; null while presentation is absent. */
volatile SidebarPanel activityPanel;
/**
* Client-thread PvP sample for the sidebar context row. Sampled on the tick (the client
* thread) so the EDT never calls into the client, and presentation only: nothing here
* reaches accounting.
*/
volatile PvpState currentPvp = PvpState.NONE;
/** Read by the sidebar on the EDT; written from startup and game-state events. */
volatile boolean loggedIn;
final PresentationLifecycle navigation = new PresentationLifecycle();
/**
* Installs the sidebar and HUD+. When the toolbar
* is unavailable the plugin keeps tracking without presentation, and R1 acceptance fails
* because the sidebar is a required product surface.
*/
void installPresentation() {
try {
var panel = new SidebarPanel(engine, config, itemManager, csvExporter, repository, persistence);
panel.spriteManager = spriteManager;
panel.mutationOwner = clientThread::invoke;
panel.bindPvp(() -> currentPvp);
panel.bindLoggedIn(() -> loggedIn);
panel.setHoppingSource(() -> lastGameState == HOPPING);
var builder = new HudBuilder(config, (id, quantity) -> itemManager.getImage(id, quantity, false));
panel.bindHud(builder);
hudOverlay = new HudOverlay(builder, config);
hudFolio = new HudFolio(builder, hudOverlay, config, client::getMouseCanvasPosition, client::getCanvasWidth, client::getCanvasHeight);
overlayManager.add(hudOverlay);
overlayManager.add(hudFolio);
hud = builder;
lootPresentationFilter.refresh();
panel.bindFilter(new RecentFilter() {
public boolean isFlowIncluded(Flow flow) {
return lootPresentationFilter.isFlowIncluded(flow, config.lootPresentationFilter());
}
public String version() {
return config.lootPresentationFilter() + "|" + lootPresentationFilter.fingerprint + "|" + config.hiddenRecentItems();
}
public boolean showRecent(String name, long quantity) {
return LootPresentationFilterService.listState("", config.hiddenRecentItems(), name, quantity)
!= LootPresentationFilterService.HIDDEN;
}
public void hideRecent(String name) {
var names = new ArrayList<>(Text.fromCSV(config.hiddenRecentItems()));
if (!names.contains(name)) names.add(name);
configManager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentItems", Text.toCSV(names));
}
});
activityPanel = panel;
if (!navigation.install(PresentationLifecycle.toolbarHost(clientToolbar), panel, PresentationLifecycle.loadIcon())) {
log.warn(msg("d"), clientToolbar != null, PresentationLifecycle.loadIcon() != null);
removePresentation();
return;
}
log.info(msg("al"));
} catch (RuntimeException | LinkageError ex) {
log.warn(msg("as"), ex);
removePresentation();
}
}

/** Removes the sidebar button and detaches presentation. */
void removePresentation() {
hud = null;
if (hudOverlay != null) {
overlayManager.remove(hudOverlay);
overlayManager.remove(hudFolio);
hudOverlay = null;
hudFolio = null;
}
activityPanel = null;
navigation.uninstall(PresentationLifecycle.toolbarHost(clientToolbar));
}

/**
* Samples the client-thread PvP facts for the sidebar context row. Called from the tick,
* where client access is legal; the EDT reads the cached sample through {@code bindPvp}.
*/
void samplePvpState() {
Player local = client.getLocalPlayer();
int skull = local == null ? net.runelite.api.SkullIcon.NONE : local.getSkullIcon();
currentPvp = new PvpState(pvp.context().isDangerous(), skull != net.runelite.api.SkullIcon.NONE,
client.getVarbitValue(VarbitID.PRAYER_PROTECTITEM) == 1);
}

void transactionBooked(Transaction transaction, long now) {
HudBuilder builder = hud;
if (builder != null) builder.tray().booked(transaction, now, builder.keepSession());
refreshPresentation();
}

void lootObserved(boolean pending, String source, Collection<ItemStack> items, int multiplicity, long now) {
HudBuilder builder = hud;
String batch = source + ":" + pending + ":" + multiplicity + ":" + lootKey(items);
// Only reward windows ride the tray (ground loot shows once it is picked up). One reward
// can arrive through several RuneLite events; show it once.
if (builder != null && pending && items != null && !(batch.equals(lastLootBatch) && now - lastLootAt < 1_200L)) {
lastLootBatch = batch;
lastLootAt = now;
for (ItemStack item : items) {
// Named and priced by the one valuation route; nothing is counted from this.
for (Flow flow : valuation.value(Collections.singletonMap(itemManager.canonicalize(item.getId()),
(long) item.getQuantity() * max(1, multiplicity)))) {
builder.tray().observed(flow.itemId, flow.itemName, flow.quantityDelta, flow.unitPrice, now, builder.keepSession());
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
if (scope == null || "held".equals(scope) || "switching".equals(scope)) panel.clearOwnerScope();
else panel.resumeOwnerScope();
}

void interactionChanged(String name, boolean combat) {
// Presentation policy: the target never retitles the run; HUD+ shows it as its header,
// and fighting another NPC starts that NPC's tray streak.
HudBuilder builder = hud;
if (builder != null && !HudTray.minion(name)) {
builder.interaction(name);
if (combat) builder.tray().engage(name, builder.keepSession());
}
}

/** Every-tick signals: live values only, never a full page rebuild. */
void pulse() {
SidebarPanel panel = activityPanel;
if (panel != null) panel.tick();
}

int ticksSinceSave;
int recentPlayerCombatTicks;
int idleTicks;
/** Wall clock of the first idle tick; the pause is dated here. */
long idleStartedAt;
final Map<Skill, Integer> lastSkillExperience = new EnumMap<>(Skill.class);
final NeutralZoneTracker neutralZoneTracker = new NeutralZoneTracker();
int geOfferSeedTicks;
/** Rising-edge: clear NPC soft-stick once when bank opens, not every bank tick. */
boolean bankClearedInteractionThisOpen;
@Override
protected void startUp() {
migrateGroup(configManager);
JsonCodec.bind(gson);
boolean loggedInAtStart = client.getGameState() == LOGGED_IN;
loggedIn = loggedInAtStart;
TrackingIdentity identityAtStart = persistence.resolveCurrentIdentity();
persistence.initializeAsync(() -> {
try {
repository.initialize(getPluginDirectory());
} catch (IOException ex) {
throw new IllegalStateException(msg("k"), ex);
}
persistence.start();
if (loggedInAtStart) persistence.trySwitchIdentityAsync(identityAtStart, config.persistHistory());
});
// Always sync identity for account isolation; persistHistory only
// controls durable load/save, not whether accounts may mix.
if (client.getGameState() == LOGGED_IN) beginGeOfferSeed();
installPresentation();
interactionContextTracker.bindPresentation(this::canIngestGameplay, this::interactionChanged);
// startUp runs on the EDT; world type and the inventory baseline are client-thread reads.
clientThread.invokeLater(() -> {
refreshEconomyState();
if (canIngestGameplay()) initializeLoggedInState();
});
pulse();
}

/** One-time bridge: settings saved under the original plugin name's group carry into the current one. */
static void migrateGroup(ConfigManager manager) {
if (manager.getConfiguration(GpManagerConfig.GROUP, "groupMigrated") != null) return;
String old = "profitmanager.";
for (String whole : manager.getConfigurationKeys(old)) {
String value = manager.getConfiguration("profitmanager", whole.substring(old.length()));
if (value != null) manager.setConfiguration(GpManagerConfig.GROUP, whole.substring(old.length()), value);
}
manager.setConfiguration(GpManagerConfig.GROUP, "groupMigrated", "true");
}

@Override
protected void shutDown() {
charges.clearCheckIntent();
// The durable General tracker and any custom session are saved in
// place. Shutdown must not turn either into history or merge owners.
engine.pauseForLifecycle(System.currentTimeMillis());
persistence.shutdown(config.persistHistory());
lastSkillExperience.clear();
interactionContextTracker.clear();
// Clear the sidebar while the engine is still valid, then remove the button.
ownerScopeChanged("held");
interactionContextTracker.bindPresentation(null, null);
removePresentation();
}

GameState lastGameState;
/**
* Client-thread world/economy snapshot for automatic market pricing. Ordinary main-game
* modifiers keep quotes; special economies and an unknown world fail them closed, while
* manual overrides and face value still work. Never called from persistence/render threads.
*/
void refreshEconomyState() {
if (valuation == null) return;
valuation.setEconomyState(client != null && client.getGameState() == LOGGED_IN
? ItemValuationService.economyStateFor(client.getWorldType()) : ItemValuationService.EconomyState.UNKNOWN);
}

@Subscribe
public void onGameStateChanged(GameStateChanged event) {
GameState previous = lastGameState;
lastGameState = event.getGameState();
if (event.getGameState() == LOGGED_IN) {
loggedIn = true;
// Login or world hop: re-read the economy identity from the client thread.
refreshEconomyState();
} else if (event.getGameState() == LOGIN_SCREEN || event.getGameState() == HOPPING) {
recentPlayerCombatTicks = 0;
loggedIn = event.getGameState() == HOPPING;
// No world identity: automatic market quotes fail closed until the next login.
if (valuation != null) valuation.setEconomyState(ItemValuationService.EconomyState.UNKNOWN);
}
// LOGGED_IN -> LOADING -> LOGGED_IN is a region change (teleport, instance, stairs), not a
// login. Re-priming the baseline there swallowed whatever changed on the way -- a teleport
// tablet breaking never booked. Only the location-bound trackers reset on a load.
if (event.getGameState() == LOADING) {
clearLocationBoundState();
return;
}
if (event.getGameState() == LOGGED_IN && previous == LOADING) {
activity.syncActivitySession();
pulse();
return;
}
if (event.getGameState() != LOGGED_IN) {
geOfferLedger.beginLoginSeed();
geOfferSeedTicks = 0;
charges.resetTransient();
engine.clearPlayerWorldLocation();
clearLocationBoundState();
characterIdleTracker.clear();
}
if (event.getGameState() == LOGGED_IN) {
beginGeOfferSeed();
persistence.syncIdentityFromClientAsync(config::persistHistory);
pulse();
} else if (event.getGameState() == LOGIN_SCREEN || event.getGameState() == HOPPING) {
lastSkillExperience.clear();
if (persistence.isTrackingReady()) {
engine.pauseForLifecycle(System.currentTimeMillis());
persist();
}
if (event.getGameState() == LOGIN_SCREEN) persistence.onLogout(config.persistHistory());
}
}

@Subscribe
public void onRuneScapeProfileChanged(RuneScapeProfileChanged event) {
beginOwnerSwitch();
refreshEconomyState();
persistence.syncIdentityFromClientAsync(config::persistHistory);
pulse();
}

@Subscribe
public void onAccountHashChanged(AccountHashChanged event) {
beginOwnerSwitch();
if (client.getAccountHash() == -1L) {
persistence.onAccountHashInvalidatedAsync(config::persistHistory);
syncOwnerScopeNow();
} else {
persistence.syncIdentityFromClientAsync(config::persistHistory);
}
pulse();
}

/** Profile/account identity is changing: transient charge/GE/interaction evidence never crosses owners. */
void beginOwnerSwitch() {
recentPlayerCombatTicks = 0;
engine.clearGeCollectionIntent();
beginGeOfferSeed();
charges.resetTransient();
interactionContextTracker.clear();
}

/** Region load / logout: location-bound trackers reset; the baseline does not. */
void clearLocationBoundState() {
interactionContextTracker.clear();
neutralZoneTracker.reset();
}

void initializeLoggedInState() {
if (!canIngestGameplay()) {
pulse();
return;
}
// Login restores identity and baseline. Resume LIFECYCLE only (hop/relog).
// Idle and Recovery stay until activity or explicit Resume. Manual / Stop stay held.
if (engine.getActiveSession() != null) engine.beginBaselinePriming();
long now = System.currentTimeMillis();
engine.resume(now, LIFECYCLE);
captureBaseline();
idleTicks = 0;
activity.syncActivitySession();
syncOwnerScopeNow();
pulse();
}

/**
* Login and gameplay resumes trust the loaded containers as the baseline immediately.
* Priming would otherwise absorb the player's first action (owner 2026-10-01).
*/
void captureBaseline() {
if (engine.live() && client.getItemContainer(InventoryID.INV) != null) {
engine.setBaseline(snapshotFactory.capture(config.includeEquipment(), config.includeRunePouch()));
}
}

/** Same-turn owner rebind so a custom start/finish or account switch never lingers one tick. */
void syncOwnerScopeNow() {
String scope = persistence != null && persistence.isTrackingReady()
? String.valueOf(System.identityHashCode(engine.getActiveSession())) : "held";
ownerScopeChanged(scope);
}

void beginGeOfferSeed() {
geOfferLedger.beginLoginSeed();
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
geOfferLedger.observe(OfferLedger.Snapshot.fromOffer(slot, offer));
}
}
if (complete) {
geOfferLedger.finishLoginSeed();
// Schema-104 custody baseline: resume exact persisted lifecycles, quarantine offers
// first seen already open, and never replay pre-existing progress as a fill.
// Progress made while the client was away is taken from the slot once, here.
engine.seedGeOfferSlots(geOfferLedger.snapshots(), System.currentTimeMillis());
} else {
// Do not treat a partial/null slot array as an empty-account baseline.
// Retry until all eight current slots are observed.
geOfferSeedTicks = 1;
}
}

@Subscribe
public void onItemContainerChanged(ItemContainerChanged event) {
if (!canIngestGameplay()) return;
int containerId = event.getContainerId();
boolean inventory = containerId == InventoryID.INV || containerId == InventoryID.WORN;
if (!inventory) markMeaningfulActivity();
if (containerId == InventoryID.BANK) {
engine.markContext(Context.TRANSFER, CORRELATION_TICKS, "Bank transfer");
}
if (inventory) {
// Inventory can fire after the bank widget is gone but before the next
// GameTick. Close soft transfer evidence first so burial is not latched.
closeBankIfHidden();
// GE widget visibility is only a UI hint. Offer transitions are recorded by the
// ledger and matched by item/direction-scoped evidence in the engine; arming MARKET
// for every inventory callback while the interface is open would steal unrelated
// loot, shop, and manual inventory changes.
// Player trade UI open while invent settles → TRADE (Traded), not GAIN.
if (LiveWidgets.visible(client, InterfaceID.Trademain.TITLE)) {
engine.markContext(MARKET, marketTicks(), "Player trade");
}
engine.markInventoryDirty();
// Inventory context stops the idle countdown but does not resume by itself: the
// settlement resumes Idle only when it sees a gain, and books that gain first.
idleTicks = 0;
}
}

void closeBankIfHidden() {
if (!LiveWidgets.isBankingUiOpen(client)) engine.markBankInterfaceClosed();
}

@Subscribe
public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
if (event == null || event.getOffer() == null || event.getSlot() < 0) return;
// The API replays every slot as EMPTY at login, followed by each offer
// that the server restores. Keep these as baselines; the ledger is
// observation-only until that seed is finished.
OfferLedger.Snapshot snapshot = OfferLedger.Snapshot.fromOffer(event.getSlot(), event.getOffer());
Optional<OfferLedger.Transition> observed = geOfferLedger.observe(snapshot);
if (observed.isPresent()) handleGeOfferTransition(observed.get());
}

void handleGeOfferTransition(OfferLedger.Transition transition) {
int observedItem = transition.current.itemId > 0 || transition.previous == null
? transition.current.itemId : transition.previous.itemId;
// Observation only: custody owns offer-based classification through its item- and
// side-scoped offer records. A passive progress or slot transition must never arm
// MARKET context for whatever inventory change settles next.
engine.noteGeOfferObservation(transition, observedItem > 0 ? itemName(observedItem) : "", System.currentTimeMillis());
// Fill progress is evidence only; money is booked from the settled inventory movement.
}

@Subscribe
public void onVarbitChanged(VarbitChanged event) {
// Rune pouch contents live in varbits, not an item container. A change there is
// an inventory change for accounting: casting from the pouch is a cost, filling
// it from the inventory nets to zero.
if (event == null || !config.includeRunePouch() || !ContainerSnapshotFactory.isRunePouchVarbit(event.getVarbitId())) {
return;
}
if (!canIngestGameplay()) return;
markMeaningfulActivity();
closeBankIfHidden();
engine.markInventoryDirty();
}

@Subscribe
public void onGraphicChanged(GraphicChanged event) {
if (!canIngestGameplay() || event == null || event.getActor() != client.getLocalPlayer()) return;
charges.graphicChanged(event.getActor().getGraphic(), System.currentTimeMillis());
}

@Subscribe
public void onHitsplatApplied(HitsplatApplied event) {
if (!canIngestGameplay() || event == null || !(event.getActor() instanceof NPC)) return;
Hitsplat hitsplat = event.getHitsplat();
// isMine sees every target of one swing (owner 2026-09-29): a scythe sweep's second
// and third hits land on NPCs the player never "interacts" with.
if (hitsplat == null || !hitsplat.isMine()) return;
charges.hitApplied(hitsplat.getAmount(), System.currentTimeMillis());
}

@Subscribe
public void onAnimationChanged(AnimationChanged event) {
if (!canIngestGameplay() || event == null || event.getActor() != client.getLocalPlayer()) return;
charges.observeSkillXp(event.getActor().getAnimation(), System.currentTimeMillis());
}

@Subscribe
public void onChatMessage(ChatMessage event) {
if (!canIngestGameplay() || event == null) return;
ChatMessageType type = event.getType();
if (type != ChatMessageType.SPAM && type != ChatMessageType.GAMEMESSAGE) return;
String message = normalize(event.getMessage());
if (charges.observeMeasuredChargeCheck(message, System.currentTimeMillis())) {
// Charge Check chat is an explicit measurement, never an activity or title signal.
return;
}
// Any unrelated chat between click and numeric response makes the target
// association uncertain; the next Check must establish fresh intent.
charges.clearCheckIntent();
if (message.contains("accepted trade")) {
// Trade completed chat — keep MARKET armed through inventory settle.
engine.markContext(MARKET, marketTicks(), "Player trade");
markMeaningfulActivity();
return;
}
if (message.contains("too impure")) {
// A failed iron smelt gives no XP but is still part of the smelting run.
activity.continueProductionRun("Smelting", System.currentTimeMillis());
return;
}
if (ActionSignals.isChaosAltarBoneSaveChat(message)) {
// Chaos altar bone-save is not a spend — inventory may keep the bone.
markMeaningfulActivity();
return;
}
if (!ActionSignals.isConsumptionChatMessage(message)) return;
// Live bury/drink often animate after the menu click; chat confirms the spend
// and keeps open intent alive until inventory stabilizes (incl. coalesced picks).
engine.reinforceConsumptionIntent(intentTicks());
markMeaningfulActivity();
}

@Subscribe
public void onMenuOptionClicked(MenuOptionClicked event) {
if (!canIngestGameplay() || event == null || event.isConsumed()) return;
String option = normalize(event.getMenuOption());
boolean consumption = ActionSignals.isInventoryConsumptionOption(option);
boolean gather = ActionSignals.isHarvestGatherOption(option);
if (gather || consumption) {
// A genuine action can produce an item without XP or an animation
// (field crops). Capture its pre-action inventory on automatic start.
markGameplayActivity(true);
}
interactionContextTracker.onMenuOptionClicked(event);
markMeaningfulActivity();
String target = normalize(event.getMenuTarget());
String joined = option + " " + target;
engine.observeKeyChestInteraction(option, target);
charges.onMenuOptionClicked(event, option, target);
// A loss-only "Offer" click with the Grand Exchange open is a sell placement: the
// inventory removal is GE evidence, not a prayer/altar spend. The proven GE UI context
// disambiguates the shared "Offer" verb; a generic Offer elsewhere keeps its spend intent.
if (consumption && "offer".equals(option) && LiveWidgets.isGrandExchangeOpen(client)) {
trading(CORRELATION_TICKS, msg("eh") + (target.isEmpty() ? "" : " " + target));
return;
}
// A Collect interaction with the open Grand Exchange is bounded collection evidence. It
// never owns a movement by itself; it lets exact custody settlements correlate.
if (option.startsWith("collect") && LiveWidgets.isGrandExchangeOpen(client)) {
engine.noteGeCollectionIntent(System.currentTimeMillis());
trading(CORRELATION_TICKS, msg("ei"));
return;
}
if (consumption) {
int itemId = resolveMenuItemId(event);
// Always note intent — even when item id is unresolved (open intent). Spend is
// confirmed on stable inventory removal / dose leftover; the verb lets the settled
// row say Drank/Ate/Buried honestly. Exact spell text is optional presentation
// evidence and never changes matching, type, counted state or value.
engine.noteConsumptionIntent(itemId, intentTicks(), false,
ActionEvidence.fromMenuOption(option), spellLabel(event, option));
} else if ("drop".equals(option)) {
int itemId = resolveMenuItemId(event);
// Prefer resolved id only — open drop (itemId=-1) needs safer engine matching.
if (itemId >= 0) {
// Match consume TTL so dump-then-settle does not miss power-train drops.
Player local = client.getLocalPlayer();
if (local != null && local.getWorldLocation() != null) {
WorldPoint tile = local.getWorldLocation();
engine.noteDropIntent(itemId, intentTicks(), tile.getX(), tile.getY(), tile.getPlane(), true);
syncPlayerWorldLocation(local);
} else {
engine.noteDropIntent(itemId, intentTicks(), 0, 0, 0, false);
}
}
} else if ("destroy".equals(option)) {
// Irreversible loss — consume-style intent, never recoverable own-drop.
engine.noteConsumptionIntent(resolveMenuItemId(event), intentTicks(), true);
}
// Use Tinderbox ↔ Logs (inventory Use): a spend intent for the fuel, same as Light.
if (ActionSignals.isFiremakingUsePair(option, target)) {
engine.noteConsumptionIntent(resolveMenuItemId(event), intentTicks(), false, ActionKind.SUPPLIES);
}
// Use bones/ashes ↔ altar (PoH / wildy): an offer spend intent; Prayer XP confirms it.
if (ActionSignals.isPrayerAltarUsePair(option, target) || ActionSignals.xp(option, target)) {
engine.noteConsumptionIntent(resolveMenuItemId(event), intentTicks(), false, ActionKind.OFFER);
}
// Agility dispensers (Wilderness course rewards, Brimhaven tickets): the claim keeps the
// course title; the rewards book as ordinary inventory gains.
if (AgilityCourses.isDispenser(target)) {
Player local = client.getLocalPlayer();
String course = local == null || local.getWorldLocation() == null ? null
: AgilityCourses.courseName(local.getWorldLocation().getRegionID());
activity.observeActivity(course == null ? "Agility" : course, true);
}
// Farming / gather object actions: activity label only (accounting is inventory GAIN).
if (gather) {
activity.observeActivity(ActionSignals.activityHintForGatherOption(option), true);
} else if (!consumption) {
// Transform/production options (station or inventory) arm PRODUCTION context so the
// paired receipt settles without double count; XP re-arms it inside the window.
String skill = ActionSignals.transformSkillForOption(option);
if (!skill.isEmpty()) activity.armProductionContext(skill);
}
BossRetrievalCatalogue.Service retrieval = BossRetrievalCatalogue.forMenu(option, target);
if (retrieval != null) {
// Long window: dialogue + confirm + coins + items can take several ticks.
boolean armed = engine.noteDeathReclaimIntent(retrieval, max(CORRELATION_TICKS * 4, config.stabilizationTicks() + 30));
if (armed) {
activity.observeActivity("Death reclaim", true);
return;
}
}
// Clicks inside the store name the tool ("Withdraw-1 Rake"), never the leprechaun, so the
// open store window is the storage evidence, as the bank window is below.
if (LiveWidgets.isToolStoreOpen(client)) {
engine.markTransfer(NeutralStorageClassifier.transferNote(NeutralStorageClassifier.Kind.TOOL_LEPRECHAUN),
CORRELATION_TICKS);
return;
}
// "Deposit" is also ordinary menu vocabulary on unrelated objects. The menu string
// alone is not ownership evidence; require the live bank/deposit-box interface before
// treating a bank action as a hard neutral transfer. Direct withdraw clicks latch too:
// the bank can close before its inventory change stabilizes or its BANK callback arrives.
// Withdraw-X only opens a quantity prompt; its BANK callback proves the actual movement.
if (LiveWidgets.isBankingUiOpen(client) && (ModelText.has(joined, "bank", "deposit")
|| option.startsWith("withdraw") && !"withdraw-x".equals(option))) {
if (joined.contains("deposit box")) {
engine.markTransfer(msg("ej"), CORRELATION_TICKS);
} else {
engine.markContext(Context.TRANSFER, CORRELATION_TICKS, msg("ek"));
}
return;
}
NeutralStorageClassifier.Kind neutral = NeutralStorageClassifier.classify(joined);
if (neutral == null) neutral = NeutralStorageClassifier.classify(target);
if (neutral != null) {
engine.markTransfer(NeutralStorageClassifier.transferNote(neutral), CORRELATION_TICKS);
return;
}
String containerFamily = UtilityContainerCatalogue.familyForItemName(target);
if (containerFamily != null && UtilityContainerCatalogue.isCustodyOption(option)) {
boolean depositBox = joined.contains("deposit box");
UtilityContainerCatalogue.Kind kind = UtilityContainerCatalogue.classifyOption(option, depositBox);
if (UtilityContainerCatalogue.isOwnershipNeutral(kind)) {
String note = "Container " + kind.name().toLowerCase(Locale.ROOT) + " (" + containerFamily + ")";
if (joined.contains("plank sack")) note = msg("at");
engine.markContext(Context.TRANSFER, CORRELATION_TICKS, note);
}
return;
}
// A menu click alone cannot establish that a key was destroyed or dropped.
// Its settled inventory loss is handled by the ordinary evidence path.
if (("destroy".equals(option) || "drop".equals(option)) && target.contains("loot key")) return;
MinigameTransferClassifier.Event minigame = MinigameTransferClassifier.classify(joined, currentRegionId());
if (minigame != null) {
engine.markTransfer(MinigameTransferClassifier.transferNote(minigame), CORRELATION_TICKS);
return;
}
if (option.startsWith("buy") || option.startsWith("sell") || option.startsWith("collect")) {
trading(CORRELATION_TICKS, (LiveWidgets.isGrandExchangeOpen(client) ? "Grand Exchange " : "")
+ option + (target.isEmpty() ? "" : " " + target));
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
activity.observeActivity("Trading", true);
engine.markContext(MARKET, ticks, note);
}

/** Consume/drop/altar intent lifetime: animations outlast correlation, plus a coalesced settle window. */
int intentTicks() {
return max(CORRELATION_TICKS * 2, config.stabilizationTicks() + 16);
}

/** MARKET context lifetime for GE / player-trade inventory settlement. */
int marketTicks() {
return max(CORRELATION_TICKS * 2, config.stabilizationTicks() + 8);
}

int lootMemoryTicks() {
return 50; // an NPC drop stays matchable for 30 s
}

@Subscribe
public void onNpcLootReceived(NpcLootReceived event) {
if (!canIngestGameplay()) return;
markGameplayActivity();
if (engine.getActiveSession() == null) return;
String npcName = event.getNpc() == null ? "NPC loot" : event.getNpc().getName();
if (blank(npcName)) npcName = "NPC loot";
HudBuilder builder = hud;
if (!HudTray.minion(npcName)) {
activity.observeActivity(npcName, true);
engine.recordAction(npcName);
if (builder != null) builder.tray().kill(npcName, System.currentTimeMillis(), builder.keepSession());
}
engine.markLootContext(expectedLoot(event.getItems()), lootMemoryTicks(), "Loot from " + npcName, npcName);
offerObservedReward(false, npcName, event.getItems(), 1);
}

@Subscribe
public void onServerNpcLoot(ServerNpcLoot event) {
if (!canIngestGameplay() || event == null) return;
String npcName = event.getComposition() == null ? "NPC loot" : event.getComposition().getName();
if (blank(npcName)) npcName = "NPC loot";
offerObservedReward(false, npcName, event.getItems(), 1);
}

@Subscribe
public void onLootReceived(LootReceived event) {
// Optional: only fires when Loot Tracker is enabled. Deduped against
// NpcLootReceived / ServerNpcLoot for the same kill.
if (!canIngestGameplay() || event == null || event.getItems() == null) return;
String name = nonBlank(event.getName(), "Reward");
boolean pending = event.getType() == LootRecordType.EVENT && RewardChestCatalogue.isPendingRewardName(name);
offerObservedReward(pending, name, event.getItems(), max(1, event.getAmount()));
}

@Subscribe
public void onPlayerLootReceived(PlayerLootReceived event) {
if (!canIngestGameplay()) return;
markGameplayActivity();
// A safe arena kill (LMS, Castle Wars, PvP Arena) is never a dangerous-PvP encounter.
if (pvp.sample(client.getLocalPlayer()).isSafe()) return;
activity.observeActivity("PKing", true);
if (!config.enablePkTracking()) return;
Player player = event.getPlayer();
// PvP keeps your own results only; opponent names are never stored (SIDEBAR_SPEC.md §6b).
String label = "Player kill";
long now = System.currentTimeMillis();
syncPlayerWorldLocation(client.getLocalPlayer());
engine.markPkLootContext(expectedLoot(event.getItems()), lootMemoryTicks(), label, now);
String sourceName = player != null && player.getName() != null && !player.getName().trim().isEmpty()
? player.getName().trim() : "Player kill";
offerObservedReward(false, sourceName, event.getItems(), 1);
persist();
recentPlayerCombatTicks = 0;
}

@Subscribe
public void onInteractingChanged(InteractingChanged event) {
if (event.getSource() == client.getLocalPlayer()) {
if (event.getTarget() instanceof NPC) {
// Use the existing authorized lifecycle for an actual interaction, never a menu hover.
markGameplayActivity();
}
interactionContextTracker.onInteractingChanged(event);
}
if (!canIngestGameplay() || client.getLocalPlayer() == null) return;
boolean localToPlayer = event.getSource() == client.getLocalPlayer() && event.getTarget() instanceof Player;
boolean playerToLocal = event.getTarget() == client.getLocalPlayer() && event.getSource() instanceof Player;
if (localToPlayer || playerToLocal) {
// Keep short-lived evidence for death-safety classification even
// when PK accounting is disabled. That evidence prevents a lingering
// NPC target from turning a PvP death into an ownership-neutral reclaim.
recentPlayerCombatTicks = 20;
if (config.enablePkTracking() && !pvp.context().isSafe()) {
markMeaningfulActivity();
activity.observeActivity("PKing", true);
}
}
}

@Subscribe
public void onActorDeath(ActorDeath event) {
Player localPlayer = client.getLocalPlayer();
if (!canIngestGameplay() || localPlayer == null || event.getActor() != localPlayer) {
return;
}
// Preserve the pre-death held-key evidence before any disposition branch.
// A later stabilized key loss can then close its audit row without treating
// the unowned manifest as a loss.
engine.beginLootKeyLocalDeathSettle(KeyClaims.keyQuantities(client.getItemContainer(InventoryID.INV)));
syncPlayerWorldLocation(localPlayer);
// DeathKeep, Protect Item, and skull state are a one-time explanation
// snapshot. They are never consumed by disposition or accounting logic.
LocalDeathEvidence deathEvidence = LocalDeathEvidence.capture(client.getWidget(InterfaceID.DEATHKEEP),
client.getVarbitValue(VarbitID.PRAYER_PROTECTITEM) == 1, localPlayer.getSkullIcon(),
itemManager == null ? null : itemManager::canonicalize, itemId -> {
if (itemManager == null) return null;
ItemComposition composition = itemManager.getItemComposition(itemId);
return composition == null ? null : composition.getName();
});
// Separate ownership input: this canonical INV/WORN read is intersected with
// settled negative item flows by the engine. DeathKeep/Protect Item/skull remain
// explanation-only and never determine a flow or its counted state.
ContainerSnapshot deathHeldItems = snapshotFactory == null ? null
: snapshotFactory.captureDeathHeldItems(config.includeEquipment());
// Only a confident dangerous context plus recent player combat is a PvP death; a
// safe arena never is; elsewhere require NPC context before the reclaim window.
boolean pvmContext = interactionContextTracker != null && interactionContextTracker.hasPvmContext();
LocalDeathDisposition disposition = localDeathDisposition(
config.enablePkTracking(), recentPlayerCombatTicks, pvmContext, pvp.sample(localPlayer));
// PvM or PK: drop the NPC/activity soft-stick so the killer cannot title the next owner.
interactionContextTracker.clear();
characterIdleTracker.clear();
activity.clearOnLocalDeath();
refreshPresentation();
if (disposition == LocalDeathDisposition.TRACK_PK) {
markMeaningfulActivity();
activity.observeActivity("PKing", true);
engine.markPkDeath("Player death", System.currentTimeMillis(), deathEvidence);
persist();
recentPlayerCombatTicks = 0;
return;
}
if (disposition == LocalDeathDisposition.UNCLASSIFIED) {
// Missing PvP evidence alone cannot prove this was a PvM death. Let the
// inventory delta use ordinary loss classification rather than hiding a loss.
engine.markUnclassifiedLocalDeath(deathEvidence);
markMeaningfulActivity();
recentPlayerCombatTicks = 0;
return;
}
// PvM death: gear moves to a gravestone / Item Retrieval Service the player
// still owns. The wipe is an ownership-neutral transfer; the reclaim window
// later returns the items and books observed coins as the fee.
markMeaningfulActivity();
engine.markLocalPvmDeath(max(CORRELATION_TICKS * 2, config.stabilizationTicks() + 16), deathEvidence,
deathHeldItems == null ? null : deathHeldItems.quantities);
// Persist the physical death whitelist immediately. A restart before the first
// inventory wipe must still be able to classify the later loss as a transfer.
persist();
}

static LocalDeathDisposition localDeathDisposition(boolean pkTrackingEnabled, int playerCombatTicks, boolean pvmContext,
PvpContext context) {
if (context.isSafe()) {
// Safe arenas restore gear; never a PK death, never a reclaim.
return LocalDeathDisposition.UNCLASSIFIED;
}
if (playerCombatTicks > 0) {
// Player-combat evidence always blocks an unsafe PvM reclaim classification. It
// books a PK death only with PK accounting on and a confident dangerous context;
// otherwise the inventory loss follows ordinary accounting.
return pkTrackingEnabled && context.isDangerous() ? LocalDeathDisposition.TRACK_PK : LocalDeathDisposition.UNCLASSIFIED;
}
if (!context.isDangerous()) return LocalDeathDisposition.TRACK_PVM_RECLAIM;
return pvmContext ? LocalDeathDisposition.TRACK_PVM_RECLAIM : LocalDeathDisposition.UNCLASSIFIED;
}

Map<Integer, Long> expectedLoot(Collection<ItemStack> items) {
var expected = new HashMap<Integer, Long>();
for (ItemStack item : items) {
if (item != null && item.getId() >= 0 && item.getQuantity() > 0) {
expected.merge(itemManager.canonicalize(item.getId()), (long) item.getQuantity(), Long::sum);
}
}
return expected;
}

@Subscribe
public void onStatChanged(StatChanged event) {
Skill skill = event.getSkill();
if (skill == null) return;
Integer previous = lastSkillExperience.put(skill, event.getXp());
if (previous == null || event.getXp() <= previous || !canIngestGameplay()) return;
markGameplayActivity();
characterIdleTracker.noteSkillingXp();
long now = System.currentTimeMillis();
// Agility is an activity in its own right: the course names it when the region is known.
Player local = client.getLocalPlayer();
String course = skill != Skill.AGILITY || local == null || local.getWorldLocation() == null ? null
: AgilityCourses.courseName(local.getWorldLocation().getRegionID());
activity.observeSkillXp(skill.getName(), course, now);
charges.learnEyeCast(skill, client.getTickCount(), now);
}

@Subscribe
public void onGameTick(GameTick event) {
if (!canIngestGameplay()) {
if (client.getGameState() == LOGGED_IN) {
if (persistence.consumeIdentityReadyTransition() && canIngestGameplay()) {
initializeLoggedInState();
}
pulse();
}
return;
}
if (persistence.consumeIdentityReadyTransition()) initializeLoggedInState();
engine.tickAutoStart(System.currentTimeMillis());
advanceGeOfferSeed();
activity.syncActivitySession();
boolean bankOpen = LiveWidgets.isBankingUiOpen(client);
Player localPlayer = client.getLocalPlayer();
pvp.onGameTick(localPlayer);
samplePvpState();
WorldPoint tile = syncPlayerWorldLocation(localPlayer);
if (tile != null) applyNeutralZone(tile.getRegionID());
boolean activeAnimation = localPlayer != null && localPlayer.getAnimation() != -1;
boolean activeInteraction = localPlayer != null && localPlayer.getInteracting() != null;
if (activeAnimation || activeInteraction) markGameplayActivity();
else advanceIdleDetection();
interactionContextTracker.onGameTick();
if (bankOpen) {
engine.markBankInterfaceOpen(max(CORRELATION_TICKS, config.stabilizationTicks() + 2));
// Rising edge only — Banking ends combat soft-stick so Dark wizard
// cannot reappear the moment the bank closes.
if (!bankClearedInteractionThisOpen) {
interactionContextTracker.clear();
bankClearedInteractionThisOpen = true;
}
} else {
bankClearedInteractionThisOpen = false;
engine.markBankInterfaceClosed();
}
// Character idle drops a leftover NPC hint so leaving idle does not revive the last kill.
if (characterIdleTracker.onGameTick() && config.autoActivityDetection()
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
long now = System.currentTimeMillis();
if (engine.maintainReceiptRetention(now) > 0) {
ticksSinceSave = 0;
persist();
}
engine.tickKeyClaims();
if (engine.tickGeCustody(now)) {
ticksSinceSave = 0;
persist();
}
lootKeys.poll();
charges.watchReturn();
Transaction transaction = engine.processIfDirty(
snapshotFactory.capture(config.includeEquipment(), config.includeRunePouch()), now, row -> {
activity.observeTransactionActivity(row);
transactionBooked(row, now);
});
if (transaction != null) {
markMeaningfulActivity();
activity.observeTransactionActivity(transaction);
transactionBooked(transaction, now);
// A burst of receipts saves once within 10 ticks, not per receipt: each save copies the whole
// profile under the engine lock (release pass 2026-09-28). Deaths and logout still save at once.
ticksSinceSave = max(ticksSinceSave, SAVE_INTERVAL_TICKS - 10);
}
if (saveDue(++ticksSinceSave, engine.getRevision(), savedRevision)) {
ticksSinceSave = 0;
persist();
}
pulse();
}

/**
* A save serializes the whole profile on the client thread. With nothing booked since the last
* one, only a slow heartbeat remains: crash recovery pauses a running session at it.
*/
static boolean saveDue(int ticksSinceSave, long revision, long savedRevision) {
return ticksSinceSave >= SAVE_INTERVAL_TICKS && (revision != savedRevision || ticksSinceSave >= IDLE_SAVE_TICKS);
}

WorldPoint syncPlayerWorldLocation(Player localPlayer) {
WorldPoint tile = localPlayer == null ? null : localPlayer.getWorldLocation();
if (tile == null) engine.clearPlayerWorldLocation();
else engine.updatePlayerWorldLocation(tile.getX(), tile.getY(), tile.getPlane());
return tile;
}

/**
* Gauntlet-style instances store gear on entry and restore it on exit with no exit
* click. Keep an ownership-neutral transfer window open every tick inside, and one
* closing window after leaving so the restore settles as a transfer too.
*/
void applyNeutralZone(int regionId) {
NeutralZoneTracker.Signal signal = neutralZoneTracker.onRegion(regionId);
if (signal == NeutralZoneTracker.Signal.ENTERED) {
engine.beginNeutralZoneTransfer();
engine.markTransfer(msg("el"), config.stabilizationTicks() + 4);
} else if (signal == NeutralZoneTracker.Signal.INSIDE) {
engine.markTransfer(msg("em"), config.stabilizationTicks() + 4);
} else if (signal == NeutralZoneTracker.Signal.LEFT) {
engine.endNeutralZoneTransfer(max(CORRELATION_TICKS * 2, config.stabilizationTicks() + 16));
}
}

int currentRegionId() {
int regionId = -1;
try {
if (client != null && client.getLocalPlayer() != null && client.getLocalPlayer().getWorldLocation() != null) {
regionId = client.getLocalPlayer().getWorldLocation().getRegionID();
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
for (ItemStack item : items) key.append(item.getId()).append('x').append(item.getQuantity()).append(',');
}
return key.toString();
}

/** Unconfirmed loot goes to presentation only; pickup settles through the engine. */
void offerObservedReward(boolean pending, String sourceName, Collection<ItemStack> items, int multiplicity) {
long now = System.currentTimeMillis();
boolean chest = sourceName != null && sourceName.toLowerCase(Locale.ROOT).contains("loot chest");
if ((pending || chest) && ClueCostPairing.looksLikeClueActivity(sourceName)) {
engine.beginCluePath("loot:" + sourceName, sourceName);
}
lootObserved(pending || chest, sourceName, items, multiplicity, now);
}

@Subscribe
public void onConfigChanged(ConfigChanged event) {
if (event == null || event.getGroup() == null) return;
if (GroundItemsConfigSnapshot.GROUP.equals(event.getGroup())) {
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
engine.beginBaselinePriming();
}
if (msg("en").equals(event.getKey()) || msg("ch").equals(event.getKey())) {
refreshPresentation();
}
}

/** Republishes the current facts to the sidebar; presentation only. */
void refreshPresentation() {
SidebarPanel panel = activityPanel;
if (panel != null) panel.refresh();
}

boolean canIngestGameplay() {
try {
if (client == null || client.getGameState() != LOGGED_IN) return false;
} catch (RuntimeException ex) {
return false;
}
return persistence.isTrackingReady();
}

void markMeaningfulActivity() {
if (!canIngestGameplay()) return;
long now = System.currentTimeMillis();
idleTicks = 0;
// Inventory/bank context alone resumes IDLE but never Manual/Stop and
// never auto-starts a tracker (startup needs gameplay activity).
if (engine.resume(now, IDLE)) captureBaseline();
}

void markGameplayActivity() {
markGameplayActivity(false);
}

void markGameplayActivity(boolean beforeItemChange) {
if (!canIngestGameplay()) return;
long now = System.currentTimeMillis();
idleTicks = 0;
if (engine.startGeneralFromActivityIfNeeded(now)) {
if (beforeItemChange && client.getItemContainer(InventoryID.INV) != null) {
engine.setBaseline(snapshotFactory.capture(config.includeEquipment(), config.includeRunePouch()));
} else {
engine.beginBaselinePriming();
}
}
// Lifecycle (logout/hop), Idle, and Recovery resume on gameplay; Manual/Stop do not.
if (engine.resume(now, LIFECYCLE, IDLE, RECOVERY)) captureBaseline();
}

void advanceIdleDetection() {
if (!config.idlePauseEnabled() || engine.getActiveSession() == null) {
idleTicks = 0;
return;
}
long now = System.currentTimeMillis();
if (idleTicks++ == 0) {
// The last gameplay action was the previous tick; the idle stretch starts here.
idleStartedAt = now;
}
int thresholdTicks = max(1, (max(1, config.idleTimeoutSeconds()) * 1000 + 599) / 600);
if (idleTicks >= thresholdTicks) {
// Pause at the measured idle start so active time ends when the player stopped.
engine.pauseForIdle(now, idleStartedAt);
}
}

/**
* Exact spell evidence for one consumption click. A spellbook selection click carries the
* spell widget directly; the following widget-target click (NPC/player/item/object) carries
* no widget of its own, so the client's still-selected spell widget is the positive evidence.
* The menu target is never consulted: a target named "Ice Burst" cannot name a spell.
*/
ActionLabel spellLabel(MenuOptionClicked event, String option) {
Widget clicked = event.getWidget();
if (clicked != null) {
ActionLabel direct = ActionLabel.fromSpellMenu(option, clicked.getId(),
InterfaceID.MAGIC_SPELLBOOK, clicked.getText(), clicked.getName());
if (direct != null) return direct;
}
if (!ActionSignals.isWidgetTargetAction(event.getMenuAction())) return null;
Widget selected = client.getSelectedWidget();
return selected == null ? null : ActionLabel.fromSpellMenu(option, selected.getId(),
InterfaceID.MAGIC_SPELLBOOK, selected.getText(), selected.getName());
}

/**
* Inventory Bury/Drop/Eat menus sometimes report {@code getItemId() == -1}. Fall back to
* item-op / widget / inventory-slot ids (Supplies Tracker pattern) and canonicalize so
* intent matches snapshot item ids.
*/
int resolveMenuItemId(MenuOptionClicked event) {
if (event == null) return -1;
int itemId = event.getItemId();
if (itemId < 0 && event.isItemOp()) itemId = event.getId();
if (itemId < 0) {
Widget widget = event.getWidget();
if (widget != null) itemId = widget.getItemId();
}
// CC_OP inventory Eat/Drink often leaves getItemId() == -1; read the clicked slot.
if (itemId < 0) itemId = itemIdFromInventorySlot(event.getParam0());
if (itemId < 0) return -1;
return itemManager == null ? itemId : itemManager.canonicalize(itemId);
}

int itemIdFromInventorySlot(int slot) {
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
savedRevision = engine.getRevision();
if (config.persistHistory()) persistence.scheduleSave();
}

String itemName(int itemId) {
try {
ItemComposition composition = itemManager == null ? null : itemManager.getItemComposition(itemId);
return composition == null ? "Item " + itemId : composition.getName();
} catch (RuntimeException ex) {
return "Item " + itemId;
}
}

String normalize(String text) {
return text == null ? "" : text.replaceAll("<[^>]+>", "").trim().toLowerCase(Locale.ROOT);
}

@Provides
GpManagerConfig provideConfig(ConfigManager configManager) {
return configManager.getConfig(GpManagerConfig.class);
}
}
