package com.gpmanager;
import static com.gpmanager.Ak.msg;
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
import static com.gpmanager.Ag.*;
import static com.gpmanager.Ed.*;
import static net.runelite.api.GameState.*;
import static java.lang.Math.*;
import static com.gpmanager.Aj.*;
import static com.gpmanager.Eb.*;
@PluginDescriptor(
name = "GP Manager", internalName = "gp-manager",
description = "Passive profit tracking: profit per Grind and per hour, a ledger you can correct, all-time totals, PvP results and a loot HUD.",
tags = {"profit", "gp", "loot", "costs", "supplies", "sessions", "pking", "pk", "hud"})
public class GpManagerPlugin extends Plugin {
/** Idle ticks a bank, market or loot context stays available. */
static final int CORRELATION_TICKS = 6;
enum Cn {
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
/** Accounting's item lookups; ItemManager itself stays for sprites. */
@Inject
Ix ix;
@Inject
SpriteManager spriteManager;
@Inject
Am engine;
@Inject
SessionRepository repository;
@Inject
Ei persistence;
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
Bl valuation;
@Inject
LootKeyManifestReader lootKeys;
@Inject
Bj geOfferLedger;
@Inject
PvpContextReader pvp;
@Inject
ClientThread clientThread;
@Inject
OverlayManager overlayManager;
/** HUD+ overlay and the builder the sidebar feeds each tick; null while not installed. */
volatile Cp hud;
De hudOverlay;
@Inject
ClientToolbar clientToolbar;
/** Existing Ground Items preference authority; the sidebar reads its decision, never re-derives it. */
@Inject
LootPresentationFilterService lootPresentationFilter;
/** The restored sidebar, bound as a presentation host; null while presentation is absent. */
volatile Dp activityPanel;
/**
* Client-thread PvP sample for the sidebar context row. Sampled on the tick (the client
* thread) so the EDT never calls into the client, and presentation only: nothing here
* reaches accounting.
*/
volatile Bo currentPvp = Bo.NONE;
/** Inventory and worn items priced every other tick while a death can be lost; risk reads them. */
List<Ab> held = Collections.emptyList();
/** Read by the sidebar on the EDT; written from startup and game-state events. */
volatile boolean loggedIn;
Ec navigation = new Ec();
/**
* Installs the sidebar and HUD+. When the toolbar
* is unavailable the plugin keeps tracking without presentation, and R1 acceptance fails
* because the sidebar is a required product surface.
*/
void installPresentation() {
 try {
  var /* panel */a = new Dp(engine, config, itemManager, repository, persistence);
  a.spriteManager = spriteManager;
  a.mutationOwner = clientThread::invoke;
  a.axo(() -> currentPvp);
  a.na(() -> loggedIn);
  a.axq(() -> lastGameState == HOPPING);
  var /* builder */b = new Cp(config, (/* id */c, /* quantity */e) -> itemManager.getImage(c, e, false));
  a.axn(b);
  hudOverlay = new De(b, config);
  overlayManager.add(hudOverlay);
  hud = b;
  lootPresentationFilter.refresh();
  a.mh(new RecentFilter() {
   public boolean isFlowIncluded(Ab /* flow */f) {
    return lootPresentationFilter.isFlowIncluded(f, config.lootPresentationFilter());
   }
   public String version() {
    return config.lootPresentationFilter() + "|" + lootPresentationFilter.fingerprint + "|" + config.hiddenRecentItems();
   }
   public boolean showRecent(String /* name */f, long /* quantity */g) {
    return LootPresentationFilterService.aus("", config.hiddenRecentItems(), f, g)
    != LootPresentationFilterService.HIDDEN;
   }
   public void hideRecent(String /* name */f) {
    var /* names */g = new ArrayList<>(Text.fromCSV(config.hiddenRecentItems()));
    if (!g.contains(f)) g.add(f);
    configManager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentItems", Text.toCSV(g));
   }
  });
  activityPanel = a;
  if (!navigation.install(Ec.akh(clientToolbar), a, Ec.awn())) {
   log.warn(msg("d"), clientToolbar != null, Ec.awn() != null);
   afe();
   return;
  }
  log.info(msg("al"));
 } catch (RuntimeException | LinkageError /* ex */f) {
  log.warn(msg("as"), f);
  afe();
 }
}

/** Removes the sidebar button and detaches presentation. */
void afe() {
 hud = null;
 if (hudOverlay != null) {
  overlayManager.remove(hudOverlay);
  hudOverlay = null;
 }
 activityPanel = null;
 navigation.uninstall(Ec.akh(clientToolbar));
}

/**
* Samples the client-thread PvP facts for the sidebar context row. Called from the tick,
* where client access is legal; the EDT reads the cached sample through {@code axo}.
*/
void ahp() {
 Player /* local */a = client.getLocalPlayer();
 int /* skull */b = a == null ? SkullIcon.NONE : a.getSkullIcon();
 boolean /* possible */c = pvp.context().ws();
 boolean /* skulled */d = b != SkullIcon.NONE;
 boolean /* protect */e = client.getVarbitValue(VarbitID.PRAYER_PROTECTITEM) == 1;
 if (!c) held = Collections.emptyList();
 else if (client.getTickCount() % 2 == 0 && valuation != null && snapshotFactory != null) {
  held = valuation.value(snapshotFactory.nr(true).quantities);
 }
 currentPvp = new Bo(c, d, e, c ? Bo.risk(held, d, e) : 0L);
}

void transactionBooked(Ac /* transaction */a, long /* now */b) {
 Cp /* builder */c = hud;
 if (c != null) {
  c.tray().big = lootPresentationFilter.big(config.lootPresentationFilter());
  c.tray().booked(a, b, c.xy());
 }
 refreshPresentation();
}

void lootObserved(boolean /* pending */a, String /* source */b, Collection<ItemStack> /* items */c, int /* multiplicity */d, long /* now */e) {
 Cp /* builder */f = hud;
 String /* batch */g = b + ":" + a + ":" + d + ":" + axv(c);
 // Only reward windows ride the tray (ground loot shows once it is picked up). One reward
 // can arrive through several RuneLite events; show it once.
 // A clue casket's rewards reach the inventory at once and ride the tray as one casket row.
 if (f != null && a && c != null && !Ag.lower(b).contains("clue") && !(g.equals(lastLootBatch) && e - lastLootAt < 1_200L)) {
  lastLootBatch = g;
  lastLootAt = e;
  for (ItemStack /* item */h : c) {
   // Named and priced by the one valuation route; nothing is counted from this.
   for (Ab /* flow */i : valuation.value(Collections.singletonMap(ix.canon(h.getId()),
   (long) h.getQuantity() * max(1, d)))) {
    f.tray().observed(i.itemId, i.itemName, i.quantityDelta, i.unitPrice, e, f.xy());
   }
  }
 }
 refreshPresentation();
}

void ownerScopeChanged(String /* scope */a) {
 Cp /* builder */b = hud;
 if (b != null) b.tray().clear();
 Dp /* panel */c = activityPanel;
 if (c == null) return;
 if (a == null || "held".equals(a) || "switching".equals(a)) c.ot();
 else c.ahd();
}

void interactionChanged(String /* name */a, boolean /* combat */b) {
 // Presentation policy: the target never retitles the run; HUD+ shows it as its header,
 // and fighting another NPC starts that NPC's tray streak.
 Cp /* builder */c = hud;
 if (c != null && !HudTray.minion(a)) {
  c.interaction(a, b);
 }
}

/** Every-tick signals: live values only, never a full page rebuild. */
void pulse() {
 Dp /* panel */a = activityPanel;
 if (a != null) a.tick();
}

int ticksSinceSave;
int recentPlayerCombatTicks;
int idleTicks;
/** Wall clock of the first idle tick; the pause is dated here. */
long idleStartedAt;
Map<Skill, Integer> lastSkillExperience = new EnumMap<>(Skill.class);
NeutralZoneTracker neutralZoneTracker = new NeutralZoneTracker();
int geOfferSeedTicks;
/** Rising-edge: clear NPC soft-stick once when bank opens, not every bank tick. */
boolean bankClearedInteractionThisOpen;
/** One client-thread initialization when this plugin enable becomes ready. */
boolean boot;
@Override
protected void startUp() {
 boot = true;
 JsonCodec.bind(gson);
 boolean /* aow */a = client.getGameState() == LOGGED_IN;
 loggedIn = a;
 TrackingIdentity /* asi */b = persistence.resolveCurrentIdentity();
 persistence.vr(() -> {
  try {
   repository.initialize(getPluginDirectory());
  } catch (IOException /* ex */c) {
   throw new IllegalStateException(msg("k"), c);
  }
  persistence.start();
  if (a) persistence.ajz(b, config.persistHistory());
 });
 // Always sync identity for account isolation; persistHistory only
 // controls durable load/save, not whether accounts may mix.
 if (client.getGameState() == LOGGED_IN) lr();
 installPresentation();
 interactionContextTracker.mf(this::nh, this::interactionChanged);
 // startUp runs on the EDT; world type and the inventory baseline are client-thread reads.
 clientThread.invokeLater(() -> {
  refreshEconomyState();
  loadBank();
  if (nh()) ve();
 });
 pulse();
}

@Override
protected void shutDown() {
 charges.oi();
 // The durable General tracker and any custom session are saved in
 // place. Shutdown must not turn either into history or merge owners.
 engine.acu(Ae.now());
 persistence.shutdown(config.persistHistory());
 lastSkillExperience.clear();
 interactionContextTracker.clear();
 // Clear the sidebar while the engine is still valid, then remove the button.
 ownerScopeChanged("held");
 interactionContextTracker.mf(null, null);
 afe();
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
 ? Bl.rh(client.getWorldType()) : Bl.Bk.UNKNOWN);
}

@Subscribe
public void onGameStateChanged(GameStateChanged /* event */b) {
 GameState /* previous */c = lastGameState;
 lastGameState = b.getGameState();
 if (b.getGameState() == LOGGED_IN) {
  loggedIn = true;
  // Login or world hop: re-read the economy identity from the client thread.
  refreshEconomyState();
 } else if (b.getGameState() == LOGIN_SCREEN || b.getGameState() == HOPPING) {
  recentPlayerCombatTicks = 0;
  loggedIn = b.getGameState() == HOPPING;
  // No world identity: automatic market quotes fail closed until the next login.
  if (valuation != null) valuation.setEconomyState(Bl.Bk.UNKNOWN);
 }
 // LOGGED_IN -> LOADING -> LOGGED_IN is a region change (teleport, instance, stairs), not a
 // login. Re-priming the baseline there swallowed whatever changed on the way -- a teleport
 // tablet breaking never booked. Only the location-bound trackers reset on a load.
 if (b.getGameState() == LOADING) {
  oo();
  return;
 }
 if (b.getGameState() == LOGGED_IN && c == LOADING) {
  activity.ajd();
  pulse();
  return;
 }
 if (b.getGameState() != LOGGED_IN) {
  geOfferLedger.lu();
  geOfferSeedTicks = 0;
  charges.ags();
  engine.oy();
  oo();
  characterIdleTracker.clear();
 }
 if (b.getGameState() == LOGGED_IN) {
  lr();
  persistence.aje(config::persistHistory);
  pulse();
 } else if (b.getGameState() == LOGIN_SCREEN || b.getGameState() == HOPPING) {
  lastSkillExperience.clear();
  if (persistence.isTrackingReady()) {
   engine.acu(Ae.now());
   persist();
  }
  if (b.getGameState() == LOGIN_SCREEN) persistence.onLogout(config.persistHistory());
 }
}

/** Wealth line (owner 1.2): GE price, or high alch for untradeables; placeholders count nothing. */
long worth(ItemContainer /* container */a) {
 long /* total */b = 0L;
 if (a == null || ix == null) return 0L;
 for (Item /* item */c : a.getItems()) {
  if (c == null || c.getId() < 0 || c.getQuantity() <= 0) continue;
  // A bank placeholder keeps the slot of an item you took out; it is not the item (owner 1.2).
  if (ix.comp(c.getId()).getPlaceholderTemplateId() != -1) continue;
  int /* id */d = ix.canon(c.getId());
  // Face value for coins and platinum; the active market route the accounting uses, never the
  // user-preference getter; high alch for untradeables.
  long /* price */e = Bl.sm(d) > 0 ? Bl.sm(d) : valuation.alch ? 0L : ix.price(d);
  if (e <= 0L) e = ix.comp(d).getHaPrice();
  b += e * c.getQuantity();
 }
 return b;
}

/** A catch or cut's item: "Raw trout" beats cooked "Trout" in any search order (Codex review 1.2). */
static int caughtItem(String /* name */a, List<net.runelite.http.api.item.ItemPrice> /* found */b) {
 int /* plain */c = -1;
 for (var /* item */d : b) {
  if (d.getName().equalsIgnoreCase("raw " + a)) return d.getId();
  if (d.getName().equalsIgnoreCase(a)) c = d.getId();
 }
 return c;
}

/** What you carry now: inventory and worn equipment. */
void carry() {
 engine.carried(worth(client.getItemContainer(InventoryID.INV)) + worth(client.getItemContainer(InventoryID.WORN)));
}

/** This account's last bank total, kept in its RuneLite profile so a restart remembers it. */
void loadBank() {
 String /* saved */a = configManager == null ? null : configManager.getRSProfileConfiguration(GpManagerConfig.GROUP, "bankWealth");
 String[] /* parts */b = a == null || !a.matches("\\d+@\\d+") ? new String[0] : a.split("@");
 engine.bank(b.length == 2 ? Long.parseLong(b[0]) : 0L, b.length == 2 ? Long.parseLong(b[1]) : 0L);
}

@Subscribe
public void onRuneScapeProfileChanged(RuneScapeProfileChanged /* event */a) {
 lx();
 loadBank();
 refreshEconomyState();
 persistence.aje(config::persistHistory);
 pulse();
}

@Subscribe
public void onAccountHashChanged(AccountHashChanged /* event */a) {
 lx();
 if (client.getAccountHash() == -1L) {
  persistence.acc(config::persistHistory);
  ajh();
 } else {
  persistence.aje(config::persistHistory);
 }
 pulse();
}

/** Profile/account identity is changing: transient charge/GE/interaction evidence never crosses owners. */
void lx() {
 persistence.eo();
 recentPlayerCombatTicks = 0;
 lr();
 charges.ags();
 interactionContextTracker.clear();
}

/** Region load / logout: location-bound trackers reset; the baseline does not. */
void oo() {
 interactionContextTracker.clear();
 neutralZoneTracker.reset();
}

void ve() {
 if (!nh()) {
  pulse();
  return;
 }
 // Login restores identity and baseline. Resume LIFECYCLE only (hop/relog).
 // Idle and Recovery stay until activity or explicit Resume. Manual / Stop stay held.
 if (engine.getActiveSession() != null) engine.lp();
 carry();
 long /* now */a = Ae.now();
 if (boot) {
  boot = false;
  // Seed current XP rather than swallowing the first real XP drop after enabling.
  lastSkillExperience.clear();
  for (Skill b : Skill.values()) if (b != Skill.OVERALL) lastSkillExperience.put(b, client.getSkillExperience(b));
  activity.rb(a);
 }
 engine.resume(a, LIFECYCLE);
 avz();
 idleTicks = 0;
 activity.ajd();
 ajh();
 pulse();
}

/**
* Login and gameplay resumes trust the loaded containers as the baseline immediately.
* Priming would otherwise absorb the player's first action (owner 2026-10-01).
*/
void avz() {
 if (engine.live() && client.getItemContainer(InventoryID.INV) != null) {
  engine.setBaseline(snapshotFactory.capture(config.includeEquipment(), config.includeRunePouch()));
 }
}

/** Same-turn owner rebind so a custom start/finish or account switch never lingers one tick. */
void ajh() {
 String /* scope */a = persistence != null && persistence.isTrackingReady()
 ? String.valueOf(System.identityHashCode(engine.getActiveSession())) : "held";
 ownerScopeChanged(a);
}

void lr() {
 geOfferLedger.lu();
 // Hide the prior profile's observations until a fresh complete seed arrives.
 // Give RuneLite time to deliver the documented EMPTY-slot replay and
 // the server-backed active offers before transitions become observable.
 geOfferSeedTicks = 3;
}

void kj() {
 if (geOfferSeedTicks <= 0 || --geOfferSeedTicks > 0) return;
 GrandExchangeOffer[] /* offers */b = client.getGrandExchangeOffers();
 boolean /* complete */c = b != null && (b.length == 3 || b.length == 8);
 if (c) {
  for (int /* slot */d = 0; d < b.length; d++) {
   GrandExchangeOffer /* offer */e = b[d];
   if (e == null) {
    c = false;
    break;
   }
   geOfferLedger.observe(Bj.Snapshot.fromOffer(d, e));
  }
 }
 if (c) {
  geOfferLedger.tg();
  // Schema-104 custody baseline: resume exact persisted lifecycles, quarantine offers
  // first seen already open, and never replay pre-existing progress as a fill.
  // Progress made while the client was away is taken from the slot once, here.
  engine.ahy(geOfferLedger.snapshots(), Ae.now());
 } else {
  // Do not treat a partial/null slot array as an empty-account baseline.
  // Retry until all eight current slots are observed.
  geOfferSeedTicks = 1;
 }
}

@Subscribe
public void onItemContainerChanged(ItemContainerChanged /* event */b) {
 if (!nh()) return;
 int /* aln */c = b.getContainerId();
 boolean /* inventory */d = c == InventoryID.INV || c == InventoryID.WORN;
 if (!d) zb();
 if (c == InventoryID.BANK) {
  engine.markContext(Aj.TRANSFER, CORRELATION_TICKS, "Bank transfer");
  long /* bank */e = worth(b.getItemContainer());
  long /* at */f = Ae.now();
  engine.bank(e, f);
  // The replay has no settings store; accounting never reads this copy back mid-session.
  if (configManager != null) configManager.setRSProfileConfiguration(GpManagerConfig.GROUP, "bankWealth", e + "@" + f);
 }
 kit(c, b.getItemContainer());
 chestChanged(c, b.getItemContainer());
 if (d) carry();
 if (d) {
  // Inventory can fire after the bank widget is gone but before the next
  // GameTick. Close soft transfer evidence first so burial is not latched.
  pb();
  // GE widget visibility is only a UI hint. Offer transitions are recorded by the
  // ledger and matched by item/direction-scoped evidence in the engine; arming MARKET
  // for every inventory callback while the interface is open would steal unrelated
  // loot, shop, and manual inventory changes.
  // Player trade UI open while invent settles → TRADE (Traded), not GAIN.
  if (Ee.visible(client, InterfaceID.Trademain.TITLE)) {
   engine.markContext(MARKET, aad(), "Player trade");
  }
  engine.yz();
  // Inventory context stops the idle countdown but does not resume by itself: the
  // settlement resumes Idle only when it sees a gain, and books that gain first.
  idleTicks = 0;
 }
}

/** Kits with their own window, by container; RuneLite loads one when its window first opens. */
static final Map<Integer, String> KITS = Map.of(InventoryID.TACKLE_BOX, "Tackle box", InventoryID.SEED_BOX, "Seed box",
InventoryID.FORESTRY_KIT, "Forestry kit", InventoryID.HUNTSMANS_KIT, "Huntsman's kit");

/**
* A click inside a kit's window names the item, never the kit, so the kit's contents changing is
* the storage evidence: taking items out or putting them in is neutral (owner 1.2). The first
* sighting only loads the kit.
*/
void kit(int /* id */a, ItemContainer /* container */b) {
 String /* kit */c = KITS.get(a);
 if (c == null || b == null) return;
 Item[] /* items */d = b.getItems();
 Ev /* old */f = engine.eb.peek(KT, e -> e.item == a);
 Item[] /* before */g = f == null ? null : (Item[]) f.data;
 if (f != null) engine.eb.take(KT, a, 1L, e -> e == f);
 engine.eb.add(KT, a, 1L, Integer.MAX_VALUE, c).data = d;
 if (g != null && !Arrays.equals(g, d)) zone(c);
}

/** A tick after a bag pickup, what is still not in the inventory went into the open bag (owner 1.2). */
void bagSettle() {
 Ev e = engine.eb.peek(BG);
 if (e == null || e.owner != engine.eb.owner || client.getTickCount() <= e.at) return;
 long /* missing */a = e.held + e.qty - snapshotFactory.capture(false, false).aea(e.item);
 if (a > 0L) engine.bagged(e, Math.min(a, e.qty));
 engine.eb.take(BG, e.item, e.qty, v -> v == e);
}

/** A one-tick bag observation; the source's inventory baseline bounds a split pickup. */
void bag(int /* item */a, long /* qty */b, long /* held */c) {
 Ev e = engine.eb.put(BG, a, b, 5, null);
 e.at = client.getTickCount();
 e.held = c;
}

void pb() {
 if (!Ee.vp(client)) engine.yu();
}

@Subscribe
public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged /* event */a) {
 if (a == null || a.getOffer() == null || a.getSlot() < 0) return;
 // The API replays every slot as EMPTY at login, followed by each offer
 // that the server restores. Keep these as baselines; the ledger is
 // observation-only until that seed is finished.
 Bj.Snapshot /* snapshot */b = Bj.Snapshot.fromOffer(a.getSlot(), a.getOffer());
 Optional<Bj.Transition> /* observed */c = geOfferLedger.observe(b);
 if (c.isPresent()) handleGeOfferTransition(c.get());
}

void handleGeOfferTransition(Bj.Transition /* transition */a) {
 int /* apj */b = a.current.itemId > 0 || a.previous == null
 ? a.current.itemId : a.previous.itemId;
 // Observation only: custody owns offer-based classification through its item- and
 // side-scoped offer records. A passive progress or slot transition must never arm
 // MARKET context for whatever inventory change settles next.
 engine.abh(a, b > 0 ? itemName(b) : "", Ae.now());
 // Fill progress is evidence only; money is booked from the settled inventory movement.
}

@Subscribe
public void onVarbitChanged(VarbitChanged /* event */b) {
 // Rune pouch contents live in varbits, not an item container. A change there is
 // an inventory change for accounting: casting from the pouch is a cost, filling
 // it from the inventory nets to zero.
 if (b == null || (!config.includeRunePouch() || !ContainerSnapshotFactory.xv(b.getVarbitId()))
 && b.getVarbitId() != VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY) {
  return;
 }
 if (!nh()) return;
 zb();
 pb();
 engine.yz();
}

@Subscribe
public void onGraphicChanged(GraphicChanged /* event */a) {
 if (!nh() || a == null || a.getActor() != client.getLocalPlayer()) return;
 charges.spotAnims(a.getActor().getSpotAnims(), Ae.now());
}

@Subscribe
public void onHitsplatApplied(HitsplatApplied /* event */b) {
 if (!nh() || b == null || !(b.getActor() instanceof NPC)) return;
 Hitsplat /* aol */c = b.getHitsplat();
 // isMine sees every target of one swing (owner 2026-09-29): a scythe sweep's second
 // and third hits land on NPCs the player never "interacts" with.
 if (c == null || !c.isMine()) return;
 charges.aci(c.getAmount(), Ae.now());
}

@Subscribe
public void onAnimationChanged(AnimationChanged /* event */a) {
 if (!nh() || a == null || a.getActor() != client.getLocalPlayer()) return;
 charges.acj(a.getActor().getAnimation(), Ae.now());
}

@Subscribe
public void onChatMessage(ChatMessage /* event */b) {
 if (!nh() || b == null) return;
 // Uncharging Bryophyta's staff destroys its runes; its dialog says how many (recorded 2026-10-09).
 var /* emptied */e1 = Ak.pattern("bru").matcher(normalize(b.getMessage()));
 if (e1.matches()) charges.emptied(Ar.acm(e1.group(1)), Ae.now());
 ChatMessageType /* type */c = b.getType();
 if (c != ChatMessageType.SPAM && c != ChatMessageType.GAMEMESSAGE) return;
 String /* message */d = normalize(b.getMessage());
 // Each potion a chug drinks prints its own barrel line (Dude Where's My Stuff, GIM Hub).
 var /* chug */a = Ak.pattern("chug").matcher(d);
 if (a.matches()) charges.chug(a.group(1), Ae.now());
 if (charges.abt(d, Ae.now())) {
  // Charge Check chat is an explicit measurement, never an activity or title signal.
  return;
 }
 // Any unrelated chat between click and numeric response makes the target
 // association uncertain; the next Check must establish fresh intent.
 charges.oi();
 // Fees paid from Death's Coffer or the bank never leave the inventory; the game's own lines name
 // them (research 1.2). "Death charges you" is the whole reclaim fee, so a bank share inside it
 // waits two ticks and is dropped once that line arrives.
 var /* fee */e = Ak.pattern("dfe").matcher(d);
 var /* bank */f = Ak.pattern("dbk").matcher(d);
 var /* coffer */g = Ak.pattern("dcf").matcher(d);
 if (e.matches()) {
  engine.eb.drop(FE);
  engine.ml(Ar.acm(e.group(1)), msg("b1"), Ae.now(), false);
 } else if (f.matches() && engine.deathReclaim.isAwaitingReclaim()) {
  engine.eb.put(FE, -1, Ar.acm(f.group(1)), 10, "Reclaim fee (bank)").at = client.getTickCount();
 } else if (g.matches()) {
  engine.ml(Ar.acm(g.group(1)), msg("b0"), Ae.now(), false);
 }
 var /* caught */h = Ak.pattern("bag").matcher(d);
 if (h.matches() && ix != null && engine.baseline != null && snapshotFactory.bagOpen()) {
  // A catch or a cut an open barrel or basket took never reaches the inventory (owner 1.2).
  int /* found */i = caughtItem(h.group(2), ix.find(h.group(2)));
  if (i >= 0) {
   bag(i, h.group(1) == null ? 1L : Long.parseLong(h.group(1)), engine.baseline.aea(i));
  }
 }
 if (d.contains("accepted trade")) {
  // Trade completed chat — keep MARKET armed through inventory settle.
  engine.markContext(MARKET, aad(), "Player trade");
  zb();
  return;
 }
 if (d.contains("too impure")) {
  // A failed iron smelt gives no XP but is still part of the smelting run.
  activity.qf("Smelting", Ae.now());
  return;
 }
 if (Dw.g1(d)) {
  // Chaos altar bone-save is not a spend — inventory may keep the bone.
  zb();
  return;
 }
 if (!Dw.h1(d)) return;
 // Live bury/drink often animate after the menu click; chat confirms the spend
 // and keeps open intent alive until inventory stabilizes (incl. coalesced picks).
 engine.aew(vh());
 zb();
}

@Subscribe
public void onMenuOptionClicked(MenuOptionClicked /* event */b) {
 if (!nh() || b == null || b.isConsumed()) return;
 String /* option */c = normalize(b.getMenuOption());
 if ("bank-all".equals(c)) bankAll(b.getParam1() >>> 16);
 boolean /* consumption */d = Dw.b1(c);
 boolean /* gather */f = Dw.i1(c);
 if (f || d) {
  // A genuine action can produce an item without XP or an animation
  // (field crops). Capture its pre-action inventory on automatic start.
  markGameplayActivity(true);
 }
 interactionContextTracker.onMenuOptionClicked(b);
 zb();
 String /* target */g = normalize(b.getMenuTarget());
 String /* avt */h = c + " " + g;
 engine.abp(c, g);
 charges.onMenuOptionClicked(b, c, g);
 // A loss-only "Offer" click with the Grand Exchange open is a sell placement: the
 // inventory removal is GE evidence, not a prayer/altar spend. The proven GE UI context
 // disambiguates the shared "Offer" verb; a generic Offer elsewhere keeps its spend intent.
 if (d && "offer".equals(c) && Ee.wq(client)) {
  trading(CORRELATION_TICKS, msg("eh") + (g.isEmpty() ? "" : " " + g));
  return;
 }
 // A Collect interaction with the open Grand Exchange is bounded collection evidence. It
 // never owns a movement by itself; it lets exact custody settlements correlate.
 if (c.startsWith("collect") && Ee.wq(client)) {
  engine.abg(Ae.now());
  trading(CORRELATION_TICKS, msg("ei"));
  return;
 }
 if ("revert".equals(c) || "dismantle".equals(c)) engine.dismantle(agh(b));
 if (d) {
  int /* itemId */i = agh(b);
  // Always note intent — even when item id is unresolved (open intent). Spend is
  // confirmed on stable inventory removal / dose leftover; the verb lets the settled
  // row say Drank/Ate/Buried honestly. Exact spell text is optional presentation
  // evidence and never changes matching, type, counted state or value.
  engine.ci(i, vh(), false,
  Bn.a1(c), ajm(b, c));
 } else if ("drop".equals(c)) {
  int /* itemId */j = agh(b);
  // Prefer resolved id only — open drop (itemId=-1) needs safer engine matching.
  if (j >= 0) {
   // Match consume TTL so dump-then-settle does not miss power-train drops.
   Player /* local */k = client.getLocalPlayer();
   if (k != null && k.getWorldLocation() != null) {
    WorldPoint /* tile */l = k.getWorldLocation();
    engine.abz(j, vh(), l.getX(), l.getY(), l.getPlane(), true);
    ajg(k);
   } else {
    engine.abz(j, vh(), 0, 0, 0, false);
   }
  }
 } else if ("take".equals(c) && b.getMenuAction() != null
 && b.getMenuAction().name().startsWith("GROUND_ITEM_")) {
  // A ground item's id is the entry's identifier; a pickup of fired ammo is recovery.
  int /* id */m = snapshotFactory.canonical(b.getId());
  Ev e = engine.eb.put(TK, m, 1L, 5, null);
  e.at = client.getTickCount();
  e.x = b.getParam0();
  e.y = b.getParam1();
  e.held = snapshotFactory.capture(false, false).aea(m);
  engine.noteTake(m);
 } else if ("destroy".equals(c)) {
  // Irreversible loss — consume-style intent, never recoverable own-drop.
  engine.ci(agh(b), vh(), true);
 }
 // Use Tinderbox ↔ Logs (inventory Use): a spend intent for the fuel, same as Light.
 if (Dw.d1(c, g)) {
  engine.ci(agh(b), vh(), false, Au.SUPPLIES);
 }
 // Use bones/ashes ↔ altar (PoH / wildy): an offer spend intent; Prayer XP confirms it.
 if (Dw.e1(c, g) || Dw.f1(c, g)) {
  engine.ci(agh(b), vh(), false, Au.OFFER);
 }
 // Agility dispensers (Wilderness course rewards, Brimhaven tickets): the claim keeps the
 // course title; the rewards book as ordinary inventory gains.
 if (AgilityCourses.a1(g)) {
  Player /* local */n = client.getLocalPlayer();
  String /* course */o = n == null || n.getWorldLocation() == null ? null
  : AgilityCourses.qp(n.getWorldLocation().getRegionID());
  activity.abm(o == null ? "Agility" : o, true);
 }
 // Farming / gather object actions: their skill's first XP names the activity (accounting is inventory GAIN).
 if (f) {
  activity.gatherSkill = Dw.ka(c);
 } else if (!d) {
  // Transform/production options (station or inventory) arm PRODUCTION context so the
  // paired receipt settles without double count; XP re-arms it inside the window.
  String /* skill */p = Dw.ajx(c);
  if (!p.isEmpty()) activity.le(p);
  // Item on item (a knife on logs, a herb into a vial) starts a Make-X the space bar confirms
  // with no click, and its Make button may come a minute later: the run's own skill XP arms it
  // (owner 2026-10-08, fletching went to Review).
  else if (b.getMenuAction() == MenuAction.WIDGET_TARGET_ON_WIDGET || c.startsWith("make")) activity.rb(Ae.now());
 }
 BossRetrievalCatalogue.Service /* retrieval */q = BossRetrievalCatalogue.a1(c, g);
 if (q != null) {
  // Long window: dialogue + confirm + coins + items can take several ticks.
  boolean /* armed */r = engine.abe(q, max(CORRELATION_TICKS * 4, stab(30)));
  if (r) {
   activity.abm("Death reclaim", true);
   return;
  }
 }
 // Clicks inside the store name the tool ("Withdraw-1 Rake"), never the leprechaun, so the
 // open store window is the storage evidence, as the bank window is below.
 if (Ee.yf(client)) {
  zone(NeutralStorageClassifier.ajw(NeutralStorageClassifier.Kind.TOOL_LEPRECHAUN));
  return;
 }
 // Other stores the same way (Elnock, the seed vault, a cargo hold, Death's coffer, item sets...):
 // the open window, or Quick-withdraw on Elnock (owner 2026-10-08).
 String /* store */s = Ee.store(client);
 if (s != null || "quick-withdraw".equals(c) && g.contains("elnock")) {
  zone(s == null ? "Elnock's storage" : s);
  return;
 }
 // "Deposit" is also ordinary menu vocabulary on unrelated objects. The menu string
 // alone is not ownership evidence; require the live bank/deposit-box interface before
 // treating a bank action as a hard neutral transfer. Direct withdraw clicks latch too:
 // the bank can close before its inventory change stabilizes or its BANK callback arrives.
 // Withdraw-X only opens a quantity prompt; its BANK callback proves the actual movement.
 if (Ee.vp(client) && (Ag.has(h, "bank", "deposit")
 || c.startsWith("withdraw") && !"withdraw-x".equals(c))) {
  if (h.contains("deposit box")) {
   zone(msg("ej"));
  } else {
   engine.markContext(Aj.TRANSFER, CORRELATION_TICKS, msg("ek"));
  }
  return;
 }
 NeutralStorageClassifier.Kind /* neutral */t = NeutralStorageClassifier.a1(h);
 if (t == null) t = NeutralStorageClassifier.a1(g);
 if (t != null) {
  zone(NeutralStorageClassifier.ajw(t));
  return;
 }
 String /* ans */u = UtilityContainerCatalogue.sv(g);
 if (u != null && UtilityContainerCatalogue.wp(c)) {
  boolean /* arw */v = h.contains("deposit box");
  UtilityContainerCatalogue.Kind /* kind */w = UtilityContainerCatalogue.oq(c, v);
  if (UtilityContainerCatalogue.xg(w)) {
   String /* note */z = "Container " + w.name().toLowerCase(Locale.ROOT) + " (" + u + ")";
   if (h.contains("plank sack")) z = msg("at");
   engine.markContext(Aj.TRANSFER, CORRELATION_TICKS, z);
  }
  return;
 }
 // A menu click alone cannot establish that a key was destroyed or dropped.
 // Its settled inventory loss is handled by the ordinary evidence path.
 if (("destroy".equals(c) || "drop".equals(c)) && g.contains("loot key")) return;
 MinigameTransferClassifier.Event /* minigame */aa = MinigameTransferClassifier.a1(h, qs());
 if (aa != null) {
  zone(MinigameTransferClassifier.ajw(aa));
  return;
 }
 if (c.startsWith("buy") || c.startsWith("sell") || c.startsWith("collect")) {
  trading(CORRELATION_TICKS, (Ee.wq(client) ? "Grand Exchange " : "")
  + c + (g.isEmpty() ? "" : " " + g));
  return;
 }
 // Player–player trade: arm MARKET so settle classifies as TRADE → Traded flash.
 // Ownership-neutral peer exchange — no GE tax (note must include "player trade").
 if (c.contains("accept trade") || c.startsWith("trade with")) {
  trading(aad(), "Player trade");
 }
}

/** A GE or trade click: Trading activity and the MARKET context its inventory settle needs. */
void trading(int /* ticks */a, String /* note */b) {
 activity.abm("Trading", true);
 engine.markContext(MARKET, a, b);
}

/** Consume/drop/altar intent lifetime: animations outlast correlation, plus a coalesced settle window. */
/** The configured stabilization ticks plus {@code a}. */
int stab(int /* extra */a) {
 return config.stabilizationTicks() + a;
}

/** Marks a neutral storage or minigame zone for the usual correlation window. */
void zone(String /* name */a) {
 engine.zh(a, CORRELATION_TICKS);
}

int vh() {
 return max(CORRELATION_TICKS * 2, stab(16));
}

/** MARKET context lifetime for GE / player-trade inventory settlement. */
int aad() {
 return max(CORRELATION_TICKS * 2, stab(8));
}

int yy() {
 return 50; // an NPC drop stays matchable for 30 s
}

@Subscribe
public void onNpcLootReceived(NpcLootReceived /* event */a) {
 if (!nh()) return;
 markGameplayActivity();
 if (engine.getActiveSession() == null) return;
 String /* npcName */b = a.getNpc() == null ? "NPC loot" : a.getNpc().getName();
 if (blank(b)) b = "NPC loot";
 Cp /* builder */c = hud;
 if (!HudTray.minion(b)) {
  activity.abm(b, true);
  engine.aeh(b);
  if (c != null) c.tray().kill(b, Ae.now(), c.xy());
 }
 engine.zk(expectedLoot(a.getItems()), yy(), "Loot from " + b, b);
 aby(false, b, a.getItems(), 1);
}

@Subscribe
public void onServerNpcLoot(ServerNpcLoot /* event */a) {
 if (!nh() || a == null) return;
 String /* npcName */b = a.getComposition() == null ? "NPC loot" : a.getComposition().getName();
 if (blank(b)) b = "NPC loot";
 aby(false, b, a.getItems(), 1);
}

@Subscribe
public void onLootReceived(LootReceived /* event */a) {
 // Optional: only fires when Loot Tracker is enabled. Deduped against
 // NpcLootReceived / ServerNpcLoot for the same kill.
 if (!nh() || a == null || a.getItems() == null) return;
 String /* name */b = awq(a.getName(), "Reward");
 boolean /* pending */c = a.getType() == LootRecordType.EVENT && RewardChestCatalogue.a1(b);
 aby(c, b, a.getItems(), max(1, a.getAmount()));
}

@Subscribe
public void onPlayerLootReceived(PlayerLootReceived /* event */b) {
 if (!nh()) return;
 markGameplayActivity();
 // A safe arena kill (LMS, Castle Wars, PvP Arena) is never a dangerous-PvP encounter.
 if (pvp.sample(client.getLocalPlayer()).isSafe()) return;
 activity.abm("PKing", true);
 if (!config.enablePkTracking()) return;
 Player /* player */c = b.getPlayer();
 // PvP keeps your own results only; opponent names are never stored (SIDEBAR_SPEC.md §6b).
 String /* label */d = "Player kill";
 long /* now */e = Ae.now();
 ajg(client.getLocalPlayer());
 engine.zn(expectedLoot(b.getItems()), yy(), d, e);
 String /* sourceName */f = c != null && c.getName() != null && !c.getName().trim().isEmpty()
 ? c.getName().trim() : "Player kill";
 aby(false, f, b.getItems(), 1);
 persist();
 recentPlayerCombatTicks = 0;
}

@Subscribe
public void onInteractingChanged(InteractingChanged /* event */b) {
 if (b.getSource() == client.getLocalPlayer()) {
  if (b.getTarget() instanceof NPC) {
   // Use the existing authorized lifecycle for an actual interaction, never a menu hover.
   markGameplayActivity();
  }
  interactionContextTracker.onInteractingChanged(b);
 }
 if (!nh() || client.getLocalPlayer() == null) return;
 boolean /* asl */c = b.getSource() == client.getLocalPlayer() && b.getTarget() instanceof Player;
 boolean /* atd */d = b.getTarget() == client.getLocalPlayer() && b.getSource() instanceof Player;
 if (c || d) {
  // Keep short-lived evidence for death-safety classification even
  // when PK accounting is disabled. That evidence prevents a lingering
  // NPC target from turning a PvP death into an ownership-neutral reclaim.
  recentPlayerCombatTicks = 20;
  if (config.enablePkTracking() && !pvp.context().isSafe()) {
   zb();
   activity.abm("PKing", true);
  }
 }
}

@Subscribe
public void onActorDeath(ActorDeath /* event */b) {
 Player /* localPlayer */c = client.getLocalPlayer();
 // A staged death in the Gauntlet or the PvP tutorial loses nothing (owner 2026-10-09).
 if (!nh() || c == null || b.getActor() != c || neutralZoneTracker.isInside() || MinigameRegionHints.xa(qs())) {
  return;
 }
 // Preserve the pre-death held-key evidence before any disposition branch.
 // A later stabilized key loss can then close its audit row without treating
 // the unowned manifest as a loss.
 engine.lv(Da.keyQuantities(client.getItemContainer(InventoryID.INV)));
 ajg(c);
 // Ownership input: this canonical INV/WORN read is intersected with settled negative
 // item flows by the engine.
 Cc /* anv */d = snapshotFactory == null ? null
 : snapshotFactory.nr(config.includeEquipment());
 // Only a confident dangerous context plus recent player combat is a PvP death; a
 // safe arena never is; elsewhere require NPC context before the reclaim window.
 boolean /* ams */e = interactionContextTracker != null && interactionContextTracker.vi();
 Cn /* disposition */f = yr(
 config.enablePkTracking(), recentPlayerCombatTicks, e, pvp.sample(c));
 // PvM or PK: drop the NPC/activity soft-stick so the killer cannot title the next owner.
 interactionContextTracker.clear();
 characterIdleTracker.clear();
 activity.pe();
 refreshPresentation();
 if (f == Cn.TRACK_PK) {
  zb();
  activity.abm("PKing", true);
  engine.zo("Player death", Ae.now());
  persist();
  recentPlayerCombatTicks = 0;
  return;
 }
 if (f == Cn.UNCLASSIFIED) {
  // Missing PvP evidence alone cannot prove this was a PvM death. Let the
  // inventory delta use ordinary loss classification rather than hiding a loss.
  engine.zj();
  zb();
  recentPlayerCombatTicks = 0;
  return;
 }
 // PvM death: gear moves to a gravestone / Item Retrieval Service the player
 // still owns. The wipe is an ownership-neutral transfer; the reclaim window
 // later returns the items and books observed coins as the fee.
 zb();
 engine.zi(vh(),
 d == null ? null : d.quantities);
 // Persist the physical death whitelist immediately. A restart before the first
 // inventory wipe must still be able to classify the later loss as a transfer.
 persist();
}

static Cn yr(boolean /* pkTrackingEnabled */b, int /* playerCombatTicks */c, boolean /* ams */d,
Dh /* context */e) {
 if (e.isSafe()) {
  // Safe arenas restore gear; never a PK death, never a reclaim.
  return Cn.UNCLASSIFIED;
 }
 if (c > 0) {
  // Player-combat evidence always blocks an unsafe PvM reclaim classification. It
  // books a PK death only with PK accounting on and a confident dangerous context;
  // otherwise the inventory loss follows ordinary accounting.
  return b && e.ws() ? Cn.TRACK_PK : Cn.UNCLASSIFIED;
 }
 if (!e.ws()) return Cn.TRACK_PVM_RECLAIM;
 return d ? Cn.TRACK_PVM_RECLAIM : Cn.UNCLASSIFIED;
}

Map<Integer, Long> expectedLoot(Collection<ItemStack> /* items */a) {
 var /* expected */b = new HashMap<Integer, Long>();
 for (ItemStack /* item */c : a) {
  if (c != null && c.getId() >= 0 && c.getQuantity() > 0) {
   b.merge(ix.canon(c.getId()), (long) c.getQuantity(), Long::sum);
  }
 }
 return b;
}

@Subscribe
public void onStatChanged(StatChanged /* event */a) {
 Skill /* skill */b = a.getSkill();
 if (b == null) return;
 Integer /* previous */c = lastSkillExperience.put(b, a.getXp());
 if (c == null || a.getXp() <= c || !nh()) return;
 markGameplayActivity();
 characterIdleTracker.abn();
 long /* now */d = Ae.now();
 // Agility is an activity in its own right: the course names it when the region is known.
 Player /* local */e = client.getLocalPlayer();
 String /* course */f = b != Skill.AGILITY || e == null || e.getWorldLocation() == null ? null
 : AgilityCourses.qp(e.getWorldLocation().getRegionID());
 activity.acj(b.getName(), f, interactionContextTracker.vi(), d);
 Cp /* builder */g = hud;
 if (g != null) g.tray().xp(b.getName(), d);
 charges.aks(b, client.getTickCount(), d);
}

/** The last Take: item, tick and how many the inventory held then; a bag pickup waits a tick. */
/** Reward chest interface → its item container (d22), and the last Bank-all tick. */
static final Map<Integer, Integer> CHESTS = new HashMap<>();
static {
 for (String[] /* row */b : Ak.rows("d22")) CHESTS.put(Integer.parseInt(b[0]), Integer.parseInt(b[1]));
}
/**
* "Bank-all" on a reward chest banks loot that never reaches the inventory; it is booked like a
* pickup, once per click (research 1.2).
*/
void bankAll(int /* chestInterface */a) {
 Integer /* chest */b = CHESTS.get(a);
 ItemContainer /* loot */c = b == null ? null : client.getItemContainer(b);
 if (c == null) return;
 engine.eb.drop(CH);
 Ev e = engine.eb.add(CH, -1, 1L, 10, null);
 e.x = b;
 e.at = client.getTickCount();
 chestSnapshot(counts(c), e.until);
}

/**
* After a Bank-all, only what really leaves the chest is booked, once: a refused or repeated
* click moves nothing (Codex research 1.2).
*/
void chestChanged(int /* id */a, ItemContainer /* loot */b) {
 Ev e = engine.eb.peek(CH, v -> v.item == -1 && v.x == a);
 if (e == null || e.owner != engine.eb.owner || b == null || client.getTickCount() - e.at > 10) return;
 Map<Integer, Long> /* now */c = counts(b);
 for (Ev v : new ArrayList<>(engine.eb.open)) {
  if (v.kind != CH || v.item < 0 || v.owner != engine.eb.owner) continue;
  long /* gone */d = v.qty - c.getOrDefault(v.item, 0L);
  if (d > 0L) engine.bagged(v, d);
 }
 int /* until */f = e.until;
 engine.eb.open.removeIf(v -> v.kind == CH && v.item >= 0);
 chestSnapshot(c, f);
}

/** Each measured chest stack is quantity proof within the clicked chest's window. */
void chestSnapshot(Map<Integer, Long> /* counts */a, int /* until */b) {
 a.forEach((/* item */c, /* qty */d) -> engine.eb.add(CH, c, d, b - engine.eb.now, null));
}

Map<Integer, Long> counts(ItemContainer /* container */a) {
 var /* counts */b = new HashMap<Integer, Long>();
 for (Item /* item */c : a.getItems()) {
  if (c != null && c.getId() >= 0 && c.getQuantity() > 0) b.merge(snapshotFactory.canonical(c.getId()), (long) c.getQuantity(), Long::sum);
 }
 return b;
}

/** The local player's tile last tick: a change is movement, which is not AFK (owner 1.2). */
WorldPoint lastTile;

boolean moved(WorldPoint /* tile */a) {
 boolean /* moved */b = a != null && lastTile != null && !a.equals(lastTile);
 lastTile = a;
 return b;
}
/** The taken item vanished beside you while an open bag was carried: it may be in the bag. */
@Subscribe
public void onItemDespawned(ItemDespawned /* event */a) {
 Player /* local */b = client.getLocalPlayer();
 TileItem /* item */c = a.getItem();
 Point /* at */d = a.getTile() == null ? null : a.getTile().getSceneLocation();
 Ev e = engine.eb.peek(TK);
 if (!nh() || e == null || e.owner != engine.eb.owner || b == null || c == null || d == null
 || d.getX() != e.x || d.getY() != e.y || snapshotFactory.canonical(c.getId()) != e.item
 || client.getTickCount() - e.at > 5 || !snapshotFactory.bagOpen()
 || b.getWorldLocation().distanceTo(a.getTile().getWorldLocation()) > 1
 || c.getOwnership() != TileItem.OWNERSHIP_SELF) return;
 long /* qty */f = engine.eb.reserve(LT, e.item, c.getQuantity(), LP);
 f += engine.eb.reserve(OD, e.item, c.getQuantity() - f, RD);
 if (f > 0L && engine.eb.take(TK, e.item, 1L, v -> v == e) > 0L) bag(e.item, f, e.held);
}

@Subscribe
public void onGameTick(GameTick /* event */b) {
 if (!nh()) {
  // A fenced account (login, switch, restore) drops pending bag evidence untouched (Codex review 1.2).
  engine.eb.drop(BG, FE);
  if (client.getGameState() == LOGGED_IN) {
   if (persistence.py() && nh()) {
    ve();
   }
   pulse();
  }
  return;
 }
 if (persistence.py()) ve();
 // Any ironman account type (iron, ultimate, hardcore, group) reads 1-6 (OSRS Wiki, RuneLite gameval).
 valuation.alch = config.ironmanAlch() && client.getVarbitValue(net.runelite.api.gameval.VarbitID.IRONMAN) > 0;
 Ev /* fee */c = engine.eb.peek(FE);
 if (c != null && c.owner == engine.eb.owner && client.getTickCount() - c.at > 2) {
  engine.fee(c, Ae.now());
 }
 bagSettle();
 engine.tickAutoStart(Ae.now());
 kj();
 activity.ajd();
 boolean /* bankOpen */d = Ee.vp(client);
 Player /* localPlayer */e = client.getLocalPlayer();
 pvp.onGameTick(e);
 ahp();
 WorldPoint /* tile */f = ajg(e);
 // An instance's real map region, as the Gauntlet and arena ids are (owner 2026-10-09).
 if (f != null) kt(qs());
 servantTalk();
 charges.drank(Ae.now());
 // An NPC conversation or its options on screen: a coins-for-items change is a purchase.
 if (Ee.visible(client, InterfaceID.ChatLeft.UNIVERSE) || Ee.visible(client, InterfaceID.Chatmenu.UNIVERSE)) {
  engine.eb.put(DG, 0, 1L, stab(4), null);
 }
 boolean /* arh */g = e != null && e.getAnimation() != -1;
 boolean /* ari */h = e != null && e.getInteracting() != null;
 // Owner 1.2: moving is not AFK. It holds off AFK pause and wakes an AFK-paused Grind, but
 // never starts tracking by itself.
 if (g || h) markGameplayActivity();
 else if (moved(f)) {
  idleTicks = 0;
  if (engine.resume(Ae.now(), IDLE)) avz();
 } else kk();
 interactionContextTracker.onGameTick();
 if (d) {
  engine.ze(max(CORRELATION_TICKS, stab(2)));
  // Rising edge only — Banking ends combat soft-stick so Dark wizard
  // cannot reappear the moment the bank closes.
  if (!bankClearedInteractionThisOpen) {
   interactionContextTracker.clear();
   bankClearedInteractionThisOpen = true;
  }
 } else {
  bankClearedInteractionThisOpen = false;
  engine.yu();
 }
 // Character idle drops a leftover NPC hint so leaving idle does not revive the last kill.
 if (characterIdleTracker.onGameTick() && config.autoActivityDetection()
 && !"General".equalsIgnoreCase(activity.detectedActivity)) {
  activity.agp(true);
 }
 pulse();
 if (client.getLocalPlayer() != null && client.getLocalPlayer().getInteracting() instanceof Player) {
  // Internal death-safety evidence only. PK transaction and activity
  // accounting still obeys enablePkTracking.
  recentPlayerCombatTicks = 20;
 } else if (recentPlayerCombatTicks > 0) {
  recentPlayerCombatTicks--;
 }
 long /* now */i = Ae.now();
 if (engine.yt(i) > 0) {
  ticksSinceSave = 0;
  persist();
 }
 engine.aka();
 if (engine.ajv(i)) {
  ticksSinceSave = 0;
  persist();
 }
 lootKeys.poll();
 charges.watchReturn();
 Ac /* transaction */j = engine.adj(
 snapshotFactory.capture(config.includeEquipment(), config.includeRunePouch()), i, /* row */k -> {
  activity.abw(k);
  transactionBooked(k, i);
 });
 if (j != null) {
  zb();
  activity.abw(j);
  transactionBooked(j, i);
  // A burst of receipts saves once within 10 ticks, not per receipt: each save copies the whole
  // profile under the engine lock (release pass 2026-09-28). Deaths and logout still save at once.
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
static boolean saveDue(int /* ticksSinceSave */a, long /* revision */b, long /* savedRevision */c) {
 return a >= SAVE_INTERVAL_TICKS && (b != c || a >= IDLE_SAVE_TICKS);
}

WorldPoint ajg(Player /* localPlayer */a) {
 WorldPoint /* tile */b = a == null ? null : a.getWorldLocation();
 if (b == null) engine.oy();
 else engine.aki(b.getX(), b.getY(), b.getPlane());
 return b;
}

/**
* Gauntlet-style instances store gear on entry and restore it on exit with no exit
* click. Keep an ownership-neutral transfer window open every tick inside, and one
* closing window after leaving so the restore settles as a transfer too.
*/
void kt(int /* regionId */a) {
 // Pete Kayer's PvP tutorial lends gear and supplies inside and takes them back as you leave:
 // nothing there, or just after, is profit or loss (owner 2026-10-09, coinflow 1.6.0).
 if (MinigameRegionHints.xa(a)) engine.zh("PvP tutorial", stab(8));
 NeutralZoneTracker.Signal /* signal */b = neutralZoneTracker.awr(a);
 if (b == NeutralZoneTracker.Signal.ENTERED) {
  engine.lw();
  engine.zh(msg("el"), stab(4));
 } else if (b == NeutralZoneTracker.Signal.INSIDE) {
  engine.zh(msg("em"), stab(4));
 } else if (b == NeutralZoneTracker.Signal.LEFT) {
  engine.rg(vh());
 }
}

/**
* Owner 2026-10-09: while a house servant talks (taking items, or back from the bank or the
* sawmill) his item moves are neutral. Every house is an instance, so a castle's cook is never
* him. His own line names a sawmill trip ("the sawmill's fee", not "the bank or the sawmill").
*/
void servantTalk() {
 if (!Ak.pattern("servant").matcher(normalize(Ee.text(client, InterfaceID.ChatLeft.NAME))).matches()
 || !client.getTopLevelWorldView().isInstance()) return;
 String /* line */a = normalize(Ee.text(client, InterfaceID.ChatLeft.TEXT));
 engine.eb.put(SV, 0, 1L, stab(4), null);
 if (a.contains("sawmill") && !a.contains("bank")) engine.eb.put(SW, 0, 1L, 200, null);
}

int qs() {
 int /* regionId */a = -1;
 try {
  if (client != null && client.getLocalPlayer() != null && client.getLocalPlayer().getWorldLocation() != null) {
   a = WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation()).getRegionID();
  }
 } catch (Exception /* ignored */b) {
  // Live signals are best-effort.
 }
 return a;
}

/** Presentation-only duplicate guard for one loot batch reported by several RuneLite events. */
String lastLootBatch = "";
long lastLootAt;
static String axv(Collection<ItemStack> /* items */a) {
 var /* key */b = new StringBuilder();
 if (a != null) {
  for (ItemStack /* item */c : a) b.append(c.getId()).append('x').append(c.getQuantity()).append(',');
 }
 return b.toString();
}

/** Unconfirmed loot goes to presentation only; pickup settles through the engine. */
void aby(boolean /* pending */a, String /* sourceName */b, Collection<ItemStack> /* items */c, int /* multiplicity */d) {
 long /* now */e = Ae.now();
 boolean /* chest */f = b != null && b.toLowerCase(Locale.ROOT).contains("loot chest");
 if ((a || f) && ClueCostPairing.ys(b)) {
  engine.ls("loot:" + b, b);
 }
 lootObserved(a || f, b, c, d, e);
}

@Subscribe
public void onConfigChanged(ConfigChanged /* event */b) {
 if (b == null || b.getGroup() == null) return;
 if (Bc.GROUP.equals(b.getGroup())) {
  // The Ground Items list preferences changed: re-read the filter authority and
  // repaint the sidebar. Presentation only; stored receipts never change.
  lootPresentationFilter.refresh();
  refreshPresentation();
  return;
 }
 if (!GpManagerConfig.GROUP.equals(b.getGroup())) return;
 if ("includeEquipment".equals(b.getKey()) || "includeRunePouch".equals(b.getKey())) {
  // The tracked snapshot shape changed. Warm a new baseline so enabling or
  // disabling a container cannot appear as a synthetic gain or cost.
  engine.lp();
 }
 if (msg("en").equals(b.getKey()) || msg("ch").equals(b.getKey())) {
  refreshPresentation();
 }
}

/** Republishes the current facts to the sidebar; presentation only. */
void refreshPresentation() {
 Dp /* panel */a = activityPanel;
 if (a != null) a.refresh();
}

boolean nh() {
 try {
  if (client == null || client.getGameState() != LOGGED_IN) return false;
 } catch (RuntimeException /* ex */a) {
  return false;
 }
 return persistence.isTrackingReady();
}

void zb() {
 if (!nh()) return;
 long /* now */b = Ae.now();
 idleTicks = 0;
 // Inventory/bank context alone resumes IDLE but never Manual/Stop and
 // never auto-starts a tracker (startup needs gameplay activity).
 if (engine.resume(b, IDLE)) avz();
}

void markGameplayActivity() {
 markGameplayActivity(false);
}

void markGameplayActivity(boolean /* beforeItemChange */a) {
 if (!nh()) return;
 long /* now */b = Ae.now();
 idleTicks = 0;
 if (engine.ait(b)) {
  if (a && client.getItemContainer(InventoryID.INV) != null) {
   engine.setBaseline(snapshotFactory.capture(config.includeEquipment(), config.includeRunePouch()));
  } else {
   engine.lp();
  }
 }
 // Lifecycle (logout/hop), Idle, and Recovery resume on gameplay; Manual/Stop do not.
 if (engine.resume(b, LIFECYCLE, IDLE, RECOVERY)) avz();
}

void kk() {
 if (!config.idlePauseEnabled() || engine.getActiveSession() == null) {
  idleTicks = 0;
  return;
 }
 long /* now */a = Ae.now();
 if (idleTicks++ == 0) {
  // The last gameplay action was the previous tick; the idle stretch starts here.
  idleStartedAt = a;
 }
 int /* att */b = max(1, (max(1, config.idleTimeoutSeconds()) * 1000 + 599) / 600);
 if (idleTicks >= b) {
  // Pause at the measured idle start so active time ends when the player stopped.
  engine.adh(a, idleStartedAt);
  if (engine.idleEnd(a, config.sessionIdleAutoEndMinutes())) avz();
 }
}

/**
* Exact spell evidence for one consumption click. A spellbook selection click carries the
* spell widget directly; the following widget-target click (NPC/player/item/object) carries
* no widget of its own, so the client's still-selected spell widget is the positive evidence.
* The menu target is never consulted: a target named "Ice Burst" cannot name a spell.
*/
Bb ajm(MenuOptionClicked /* event */a, String /* option */b) {
 Widget /* clicked */c = a.getWidget();
 if (c != null) {
  Bb /* direct */d = Bb.tv(b, c.getId(),
  InterfaceID.MAGIC_SPELLBOOK, c.getText(), c.getName());
  if (d != null) return d;
 }
 if (!Dw.a1(a.getMenuAction())) return null;
 Widget /* selected */e = client.getSelectedWidget();
 return e == null ? null : Bb.tv(b, e.getId(),
 InterfaceID.MAGIC_SPELLBOOK, e.getText(), e.getName());
}

/**
* Inventory Bury/Drop/Eat menus sometimes report {@code getItemId() == -1}. Fall back to
* item-op / widget / inventory-slot ids (Supplies Tracker pattern) and canonicalize so
* intent matches snapshot item ids.
*/
int agh(MenuOptionClicked /* event */a) {
 if (a == null) return -1;
 int /* itemId */b = a.getItemId();
 if (b < 0 && a.isItemOp()) b = a.getId();
 if (b < 0) {
  Widget /* widget */c = a.getWidget();
  if (c != null) b = c.getItemId();
 }
 // CC_OP inventory Eat/Drink often leaves getItemId() == -1; read the clicked slot.
 if (b < 0) b = xx(a.getParam0());
 if (b < 0) return -1;
 return ix == null ? b : ix.canon(b);
}

int xx(int /* slot */a) {
 if (a < 0) return -1;
 try {
  ItemContainer /* inventory */b = client.getItemContainer(InventoryID.INV);
  if (b == null) return -1;
  Item[] /* items */c = b.getItems();
  if (c == null || a >= c.length) return -1;
  Item /* item */d = c[a];
  return d == null ? -1 : d.getId();
 } catch (RuntimeException /* ex */e) {
  return -1;
 }
}

void persist() {
 persist(false);
}

/** {@code quick}: the periodic gameplay save, which reuses unchanged closed sessions' JSON. */
void persist(boolean /* quick */a) {
 savedRevision = engine.getRevision();
 if (config.persistHistory()) persistence.ahe(a);
}

String itemName(int /* itemId */a) {
 try {
  ItemComposition /* composition */b = ix == null ? null : ix.comp(a);
  return b == null ? "Item " + a : b.getName();
 } catch (RuntimeException /* ex */c) {
  return "Item " + a;
 }
}

String normalize(String /* text */a) {
 return a == null ? "" : a.replaceAll("<[^>]+>", "").trim().toLowerCase(Locale.ROOT);
}

@Provides
GpManagerConfig aeq(ConfigManager /* configManager */a) {
 return a.getConfig(GpManagerConfig.class);
}
}
