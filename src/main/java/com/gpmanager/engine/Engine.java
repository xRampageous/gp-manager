package com.gpmanager;
import com.gpmanager.BossRetrievalCatalogue.Service;
import com.gpmanager.MarketSettlementProjection.SessionLookup;
import com.gpmanager.ReviewRow.Item;
import com.gpmanager.SavedState.*;
import com.gpmanager.ChargeRead.Variant;
import java.time.ZoneId;
import java.util.*;
import java.util.UUID;
import java.util.function.Predicate;
import javax.inject.*;
import lombok.*;
import com.google.gson.Gson;
import net.runelite.api.gameval.ItemID;
import static com.gpmanager.Context.*;
import static com.gpmanager.Context.LOOT;
import static com.gpmanager.Context.PK_LOOT;
import static com.gpmanager.TransactionType.TRANSFER;
import static com.gpmanager.ModelText.*;
import static com.gpmanager.PauseReason.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.FlowFilters.*;
import static com.gpmanager.ActionKind.*;
import static com.gpmanager.TransactionType.*;
import static com.gpmanager.GameData.msg;
@Singleton
class Engine {
static final int MINIMUM_BASELINE_WARMUP_TICKS = 3;
/** After a resume the sidebar/HUD show CALIBRATING this long; bookings stay immediate. */
static final long CALIBRATING_MILLIS = 2_500L;
static final String UNCLASSIFIED_LOCAL_DEATH_NOTE = msg("dd");
final FlowValuator valuationService;
final TransactionClassifier classifier;
final GpManagerConfig config;
LootPresentationFilterService contributionEligibility;
List<Session> history = new ArrayList<>();
final List<LootExpectation> lootExpectations = new ArrayList<>();
final SessionArchive archive = new SessionArchive(this);
/** Profile-local date basis; persisted so travelling or changing the host zone cannot shift history. */
String profileTimeZoneId = ZoneId.systemDefault().getId();
/** Domain revision: every canonical mutation advances it; derived previews bind to it. */
long revision;
/** Schema-102 profile-level PvP retention: bases, evidence, sequence watermarks. */
final PkHistoryArchive pkHistory = new PkHistoryArchive(this);
/** Bounded profile-local point-in-time wealth reads; always excluded from Net. */
/** Active RuneScape profile key supplied by the persistence coordinator. */
String profileIdentityKey = "";
// The general tracker is durable. A custom session is the only temporary
// owner and takes the active pointer while General is explicitly paused.
Session generalSession;
Session customSession;
boolean generalSuspendedByCustom;
/** A factory reset arms a fresh start a few seconds later, if nobody starts tracking first. */
long autoStartAtEpochMillis;
Session activeSession;
ContainerSnapshot baseline;
boolean baselinePriming;
/** Epoch millis the post-resume calibration cue clears at; 0 when none is armed. */
long calibratingUntil;
ContainerSnapshot primingSnapshot;
int primingObservedTicks;
int primingStableTicks;
boolean dirty;
ContainerSnapshot pendingSnapshot;
int pendingStableTicks;
/** The live context claim and, while an inventory change stabilizes, the one it will settle with. */
final Clue active = new Clue();
int contextTicks;
final Clue pending = new Clue();
/** One-shot display-only snapshot captured at the local death event. */
LocalDeathEvidence pendingLocalDeathEvidence;
/*
* --- Per-source bank-transfer evidence lifetimes ---
*
* Fixes the documented P1: bone burial (or any other consumption) that
* settles after the bank UI has closed must classify as CONSUMPTION, not
* TRANSFER. Two distinct evidence sources are tracked below, each with
* its own lifetime, precisely because they mean different things:
*
*   - SOFT evidence (`transferEvidenceTicks` / `bankInterfaceOpen`) only
*     means "the bank widget happened to be visible this tick." It is a
*     weak, one-sided signal and is dropped immediately on bank close
*     (see `markBankInterfaceClosed`) so it can never outlive the UI and
*     misclassify an unrelated burial/eat/drink/cast that settles after
*     close.
*   - HARD evidence (`hardTransferContextActive` / `hardTransferEvidenceTicks`)
*     means a real bank-container/menu deposit or withdrawal was
*     confirmed. It survives bank close for a short, bounded TTL so a
*     same-tick race between the bank-container event and the inventory
*     dirty callback still books the transfer correctly.
*
* A stale soft latch can never poison a burial after close; only live
* hard evidence can. See `TimedEvidence`
* for the same per-source-lifetime pattern applied to consumption/drop
* intent, and `ConsumptionBurialEngineTest` /
* `FollowupBoundaryReviewTest#burialCallbackBeforeGameTickBankCloseMustNotLatchOldTransfer`
* for the regression coverage that locks this in.
*/
/**
* Hard TRANSFER from bank-container/menu markContext. Survives soft bank-close
* and idle consumeContext so a GameTick between bank-container and inventory
* does not book the deposit as CONSUMPTION. Cleared on settle or TTL expiry.
*/
boolean hardTransferContextActive;
/** Remaining ticks for {@link #hardTransferContextActive}. */
int hardTransferEvidenceTicks;
/**
* Fresh bank-interface / deposit-menu evidence (not sticky loot correlation).
* Soft-only: cleared unconditionally on bank close unless hard evidence is
* also live (see {@link #markBankInterfaceClosed()}), so it never survives
* past the UI closing to poison a later burial/consumption.
*/
int transferEvidenceTicks;
boolean bankInterfaceOpen;
ConsumptionIntent consumptionIntent;
final DeathReclaimLifecycle deathReclaim = new DeathReclaimLifecycle();
/** Item-on-item charge-load intent; only matched settled component losses are neutral. */
final ChargeLoadTransferEvidence chargeLoadTransferEvidence = new ChargeLoadTransferEvidence();
/** Session-owned measured balances; never persisted or shared across owners. */
final MeasuredChargeReadTracker measuredChargeReadTracker = new MeasuredChargeReadTracker();
final ChargeEstimateJournal chargeEstimateJournal = new ChargeEstimateJournal();
final Map<Integer, Long> neutralZoneStoredItems = new HashMap<>();
boolean neutralZoneTransferActive;
boolean captureNeutralZoneEntry;
ContainerSnapshot neutralZoneEntryBaseline;
int neutralZoneEntryCaptureTicks;
int neutralZoneRestoreTicks;
DropIntent dropIntent;
final Deque<OwnDropRecord> ownDrops = new ArrayDeque<>();
static final int MAX_OWN_DROPS = 32;
/** One counted LOOT/PK_LOOT revenue per encounter id. */
/** Active clue path for dig/tele/key cost pairing. */
final ClueCostPairing clueCostPairing = new ClueCostPairing();
/** Key/chest claims (the one durable evidence exception, section H) and their live evidence. */
final KeyClaims claims = new KeyClaims();
/** Chebyshev tiles — pickup must be near the recorded drop, not name-only. */
static final int OWN_DROP_MATCH_RADIUS = 8;
static final int OWN_DROP_TTL_TICKS = 200;
int playerWorldX;
int playerWorldY;
int playerWorldPlane;
boolean playerLocationKnown;
@Inject
Engine(ItemValuationService valuationService, TransactionClassifier classifier, GpManagerConfig config) {
this((FlowValuator) valuationService, classifier, config);
}

Engine(FlowValuator valuationService, TransactionClassifier classifier, GpManagerConfig config) {
this.valuationService = valuationService;
this.classifier = classifier;
this.config = config;
geCustody.setBasisLedger(trackedBasis);
trackedBasis.openReservationLookup = geCustody::latestOpenReservationTime;
}

@Inject
void setContributionEligibility(LootPresentationFilterService contributionEligibility) {
this.contributionEligibility = contributionEligibility;
}

synchronized void restore(SavedState state) {
restore(state, System.currentTimeMillis());
}

/** Restore using a supplied clock so retention and recovery rules are deterministic in tests. */
synchronized void restore(SavedState state, long now) {
history.clear();
archive.clear();
savedGrinds.clear();
profileTimeZoneId = ZoneId.systemDefault().getId();
bumpRevision();
claims.clear();
chargeLoadReviews.clear();
// Known-basis coverage restores before any session can notify the observer; a pre-106
// state starts with no known coverage (existing holdings are unknown, never zero).
trackedBasis.restore(state, state == null ? null : state.getGeCustody(), now);
generalSession = null;
customSession = null;
generalSuspendedByCustom = false;
activeSession = null;
clearOwnDropState();
if (state != null) {
profileTimeZoneId = validTimeZone(state.getProfileTimeZoneId())
? state.getProfileTimeZoneId() : ZoneId.systemDefault().getId();
savedGrinds.clear();
if (state == null) return;
for (SavedGrind grind : state.getSavedGrinds()) {
if (grind != null && !grind.getGrindId().isEmpty() && !grind.getName().isEmpty()) savedGrinds.add(grind);
}
pkHistory.restore(state);
archive.restore(state, now);
for (Session session : state.getHistory()) {
if (session != null) history.add(session);
}
generalSession = state.generalSession;
customSession = state.customSession;
if (generalSession != null) generalSession.setOwnerKind(SessionOwnerKind.FREE_PLAY);
if (customSession != null) customSession.setOwnerKind(SessionOwnerKind.NAMED_SESSION);
generalSuspendedByCustom = state.generalSuspendedByCustom;
for (Session session : uniqueProfileSessions()) attachSessionListeners(session);
activeSession = customSession != null && !customSession.isClosed() ? customSession : generalSession;
if (activeSession != null && !activeSession.isClosed()) {
activeSession.markRecoveredFromCrash();
if (!activeSession.paused) {
long savedAt = state.savedAtEpochMillis;
activeSession.pause(savedAt > 0L ? min(now, savedAt) : now, RECOVERY);
} else {
activeSession.pauseReason = RECOVERY;
}
}
}
// Reclaim evidence is ephemeral and belongs to the identity being replaced;
// never carry one account's lost-item whitelist into another account's rows.
deathReclaim.reset();
// A restart of the same owner restores its persisted pending death/reclaim evidence.
deathReclaim.restore(state == null ? null : state.pendingDeathReclaim);
// Custody lifecycles are profile-scoped financial continuity state.
geCustody.restore(state, now);
resetTrackingState(false);
archive.trimHistory();
if (state != null) {
// Claims settled by a retained transaction are stale duplicates and must never book again.
claims.restore(state, uniqueProfileSessions());
archive.compactAfterRestore(now);
pkHistory.enforceDetailRetention(uniqueProfileSessions(), receiptRetentionDays(), now);
}
}

synchronized void ensureSession(long now) {
if (generalSession == null || generalSession.isClosed()) {
// Overall is the durable default owner display name; it must still
// accept automatic activity detection just as the former General default
// session did.
generalSession = newProfileSession(Session.DURABLE_OWNER_NAME, SessionMode.AUTO, now, SessionOwnerKind.FREE_PLAY);
}
if (customSession == null || customSession.isClosed()) {
customSession = null;
activeSession = generalSession;
}
}

synchronized void startCustomSession(String name, SessionMode mode, long now) {
ensureSession(now);
if (customSession != null) {
// This transition needs an explicit Finish custom first. Starting
// another custom session must never silently archive the current one.
return;
}
clearGeCollectionIntent();
if (!generalSession.paused) {
generalSession.pause(now, CUSTOM_SESSION);
generalSuspendedByCustom = true;
} else {
generalSuspendedByCustom = false;
}
customSession = newProfileSession(name, mode == null || mode == SessionMode.GENERAL
? SessionMode.AUTO : mode, now, SessionOwnerKind.NAMED_SESSION);
activeSession = customSession;
resetTrackingState(true);
}

synchronized boolean finishCustomSession(long now) {
if (customSession == null) return false;
SessionEndReason reason = customSession.endReason;
return finishCustomSessionAt(now, now, reason == null ? SessionEndReason.MANUAL : reason, false);
}

/** Finish a named session with an explicit client-owned reason. */
synchronized boolean finishCustomSession(long now, SessionEndReason reason) {
if (customSession == null) return false;
return finishCustomSessionAt(now, now, reason == null ? SessionEndReason.MANUAL : reason, false);
}

boolean finishCustomSessionAt(long endAt, long resumeGeneralAt, SessionEndReason reason, boolean autoEnded) {
if (customSession == null) return false;
long closeAt = max(customSession.startedAtEpochMillis, endAt);
clearGeCollectionIntent();
customSession.setEndReason(reason);
customSession.close(closeAt);
history.add(0, customSession);
archive.trimHistory();
customSession = null;
activeSession = generalSession;
if (generalSession != null && generalSession.getPauseReason() == CUSTOM_SESSION) {
generalSession.resume(max(closeAt, resumeGeneralAt));
}
generalSuspendedByCustom = false;
resetTrackingState(true);
return true;
}

synchronized boolean isCustomSessionActive() {
return customSession != null && activeSession == customSession && !customSession.isClosed();
}

synchronized void pauseForLifecycle(long now) {
if (live()) activeSession.pause(now, LIFECYCLE);
resetTrackingState(false);
}

/** Resumes an automatic pause of one of these reasons; Manual Pause always needs the owner. */
synchronized boolean resume(long now, PauseReason... reasons) {
if (activeSession != null && activeSession.paused && Arrays.asList(reasons).contains(activeSession.getPauseReason())) {
activeSession.resume(now);
resetTrackingState(true);
calibratingUntil = now + CALIBRATING_MILLIS;
return true;
}
return false;
}

/**
* Create the durable General tracker from meaningful gameplay when automatic startup is
* enabled, on a new install and after a factory reset alike. Never resumes Manual Pause;
* Idle and Recovery resume via {@link #resume}. Never starts tracking
* from login alone.
*
* @return true when a new General session was created
*/
synchronized boolean startGeneralFromActivityIfNeeded(long now) {
if (!config.autoStartSession()) return false;
if (customSession != null && !customSession.isClosed()) return false;
if (generalSession != null && !generalSession.isClosed()) return false;
ensureSession(now);
resetTrackingState(true);
calibratingUntil = now + CALIBRATING_MILLIS;
return true;
}

synchronized boolean isIdlePaused() {
return activeSession != null && activeSession.getPauseReason() == IDLE;
}

synchronized void setDetectedActivity(String activity, long now) {
if (activeSession != null && activeSession.getMode() == SessionMode.AUTO) {
activeSession.setActivityHint(activity, now);
}
}

synchronized void togglePause(long now) {
if (activeSession == null) {
// Start tracking: a new install, or after a factory reset, with Automatic tracking off.
ensureSession(now);
resetTrackingState(true);
calibratingUntil = now + CALIBRATING_MILLIS;
} else if (activeSession.paused) {
activeSession.resume(now);
resetTrackingState(true);
calibratingUntil = now + CALIBRATING_MILLIS;
} else {
activeSession.pause(now, MANUAL);
autoStartAtEpochMillis = 0L;
resetTrackingState(false);
}
}

/** A factory reset resumes tracking by itself a few seconds later, unless stopped first. */
synchronized void tickAutoStart(long now) {
if (autoStartAtEpochMillis <= 0L || now < autoStartAtEpochMillis) return;
autoStartAtEpochMillis = 0L;
if (activeSession == null) {
ensureSession(now);
resetTrackingState(true);
}
}

/** Seconds until the pending factory-reset resume fires; 0 when none is armed. */
synchronized long autoResumeSeconds(long now) {
long left = autoStartAtEpochMillis - now;
return left <= 0L ? 0L : (left + 999L) / 1000L;
}

/** True while the post-resume calibration cue is up; bookings are immediate and never wait. */
synchronized boolean calibrating(long now) {
return now < calibratingUntil;
}

/**
* Compacts closed sessions strictly older than {@code days}; a zero window
* means forever and performs no compaction.
*
* @return receipt rows compacted
*/
synchronized int compactOlderThan(int days, long now) {
return archive.compactOlderThan(days, now);
}

/** Daily UTC maintenance hook. Supplying time keeps lifecycle tests deterministic. */
synchronized int maintainReceiptRetention(int days, long now) {
return archive.maintainReceiptRetention(days, now);
}

/** Configured UTC retention maintenance used by the game-tick lifecycle. */
synchronized int maintainReceiptRetention(long now) {
return archive.maintainReceiptRetention(now);
}

List<Session> uniqueProfileSessions() {
var unique = new LinkedHashMap<String, Session>();
for (Session session : history) addUniqueSession(unique, session);
addUniqueSession(unique, generalSession);
addUniqueSession(unique, customSession);
addUniqueSession(unique, activeSession);
return new ArrayList<>(unique.values());
}

static void addUniqueSession(Map<String, Session> unique, Session session) {
if (session != null) unique.putIfAbsent(session.getId(), session);
}

/**
* Starts a login/session warm-up. Snapshots observed during this period are
* baseline material only and can never become profit.
*/
synchronized void beginBaselinePriming() {
resetTrackingState(true);
}

/**
* Sets an immediate trusted baseline. Primarily useful for deterministic
* tests and callers which already know all tracked containers are loaded.
*/
synchronized void setBaseline(ContainerSnapshot snapshot) {
baseline = snapshot;
baselinePriming = false;
primingSnapshot = null;
primingObservedTicks = 0;
primingStableTicks = 0;
clearPendingChange();
}

synchronized void markInventoryDirty() {
if (baselinePriming) return;
dirty = true;
pendingStableTicks = 0;
// Animation lag: refresh consume/drop TTLs when the inventory finally moves so
// bury/drink intents survive until stabilization rather than expiring idle.
if (consumptionIntent != null) consumptionIntent.refresh(max(consumptionIntent.ticksRemaining(), 4));
if (dropIntent != null) {
dropIntent.refresh(max(dropIntent.ticksRemaining(), max(4, config.stabilizationTicks() + 2)));
}
captureActiveContextForPending();
if (bankInterfaceOpen && (dirty || pendingSnapshot != null)) {
pending.transfer = true;
if (pending.context == GENERIC) {
pending.context = Context.TRANSFER;
pending.note = "Bank transfer";
}
}
}

/**
* Records a short-lived consume hint; accounting still waits for a stable inventory delta.
* {@code itemId} may be {@code < 0} (open intent) when the menu did not expose an id —
* matching then accepts a dose/partial leftover, a pure cost, or any inventory cost
* while intent is armed so drink/bury coalesced with harvest gains still books Used/lost.
*/
synchronized void noteConsumptionIntent(int itemId, int ticks) {
noteConsumptionIntent(itemId, ticks, false);
}

/**
* @param destroy when true, irreversible Destroy menu — presentation stamps Destroyed;
*                never registers recoverable own-drop.
*/
synchronized void noteConsumptionIntent(int itemId, int ticks, boolean destroy) {
noteConsumptionIntent(itemId, ticks, destroy, null);
}

/**
* @param actionKind menu verb behind the click (Drink/Eat/Bury/…) or null when the
*                   option does not evidence a specific action. Presentation only.
*/
synchronized void noteConsumptionIntent(int itemId, int ticks, boolean destroy, ActionKind actionKind) {
noteConsumptionIntent(itemId, ticks, destroy, actionKind, null);
}

/** Exact spell text is an optional presentation hint; it is not part of action matching. */
synchronized void noteConsumptionIntent(int itemId, int ticks, boolean destroy, ActionKind actionKind,
ActionLabel actionLabel) {
// Eat/Bury/Drink/Cast/etc. — clear any pending Drop intent for the same click window.
dropIntent = null;
int safeTicks = max(1, ticks);
if (consumptionIntent != null && itemId < 0 && consumptionIntent.itemId >= 0) {
// Chat/menu reinforcement without an id must not wipe a resolved item id,
// flip a Destroy stamp to Used, or replace the resolved item's proven verb.
// Exact spell text may refresh the label; a click that proves no name must not
// erase the name already proven for this bounded interaction.
consumptionIntent.refresh(safeTicks);
if (actionLabel != null) consumptionIntent.actionLabel = actionLabel;
return;
}
consumptionIntent = new ConsumptionIntent(itemId, safeTicks, destroy, quantityAtArmForConsume(itemId), actionKind,
actionLabel);
}

/**
* Clears only pending, unbooked action evidence at the profile/owner fence. Booked schema-105
* action labels are durable metadata on canonical transactions and are never erased here;
* lifecycle paths also reset pending consume evidence through
* {@link #resetTrackingState(boolean)}.
*/
synchronized void clearPendingActionEvidence() {
consumptionIntent = null;
}

/**
* Invent qty for a named consume arm. Prefer the pending (dirty) snapshot so a
* mid-window pickup is visible before bury/drink settles.
*/
long quantityAtArmForConsume(int itemId) {
if (itemId < 0) return -1L;
ContainerSnapshot live = pendingSnapshot != null ? pendingSnapshot : baseline;
return live == null ? -1L : live.quantityOf(itemId);
}

/**
* Extends an existing consume intent TTL, or arms an open intent when chat confirms
* Eat/Bury/Drink after the menu click failed to expose an item id.
* Preserves a pending Destroy stamp; never invents destroy from chat alone.
*/
synchronized void reinforceConsumptionIntent(int ticks) {
int safeTicks = max(1, ticks);
if (consumptionIntent != null) {
consumptionIntent.refresh(safeTicks);
return;
}
noteConsumptionIntent(-1, safeTicks, false);
}

/**
* Player-initiated Drop action. Confirmed inventory removal books Used/lost and
* registers a recoverable own-drop record. Matching pickups reverse that loss.
*/
synchronized void noteDropIntent(int itemId, int ticks, int worldX, int worldY, int worldPlane, boolean hasLocation) {
if (itemId < 0) return;
consumptionIntent = null;
dropIntent = new DropIntent(itemId, max(1, ticks), worldX, worldY, worldPlane, hasLocation);
}

/** Latest local-player tile for conservative own-drop recovery matching; never a PvP place fact. */
synchronized void updatePlayerWorldLocation(int worldX, int worldY, int worldPlane) {
playerWorldX = worldX;
playerWorldY = worldY;
playerWorldPlane = worldPlane;
playerLocationKnown = true;
}

/**
* Clears the export identity when persistence invalidates the active account.
*/
synchronized void clearProfileIdentityForSwitch() {
profileIdentityKey = "";
}

/** Binds the identity and restores its state under the same engine lock. */
synchronized void restoreForProfile(String identityKey, SavedState state, long now) {
String next = identityKey == null ? "" : identityKey.trim();
SavedState detached = detachSavedState(state);
String stateOwner = detached == null ? null : detached.ownerKey;
if (!next.isEmpty() && stateOwner != null && !stateOwner.trim().isEmpty() && !next.equals(stateOwner)) {
throw new IllegalArgumentException(msg("ia"));
}
Engine staged = prepareRestoredProfile(next, detached, now);
installPreparedProfile(staged, next);
}

/** The bound profile's state as saved, for a backup copy of the save file. */
synchronized SavedState exportState() {
SavedState snapshot = createSavedState();
snapshot.setOwnerKey(profileIdentityKey);
return snapshot;
}

Engine prepareRestoredProfile(String identityKey, SavedState state, long now) {
Engine staged = newStagedEngine();
staged.profileIdentityKey = identityKey;
staged.restore(state, now);
return staged;
}

/** An isolated engine sharing this engine's valuation/classifier/config; used to stage restores and migrations. */
Engine newStagedEngine() {
var staged = new Engine(valuationService, classifier, config);
staged.setContributionEligibility(contributionEligibility);
return staged;
}

static SavedState detachSavedState(SavedState state) {
if (state == null) return null;
Gson gson = JsonCodec.gson();
SavedState detached = gson.fromJson(gson.toJson(state), SavedState.class);
if (detached != null) detached.normalizeActionLabels();
return detached;
}

void installPreparedProfile(Engine staged, String identityKey) {
// Allocate copies and attach listeners before changing live fields. The
// normal restore/migration path has already completed on the isolated engine.
var nextHistory = new ArrayList<Session>(staged.history);
var nextSessions = new ArrayList<Session>(staged.uniqueProfileSessions());
for (Session session : nextSessions) attachSessionListeners(session);
// Clear identity-scoped transient evidence while durable profile fields
// still belong to the previous owner. No reader can observe the swap
// because restoreForProfile/restoreProfile hold this engine's monitor.
clearOwnDropState();
deathReclaim.reset();
chargeLoadReviews.clear();
clearGeCollectionIntent();
resetTrackingState(false);
history = nextHistory;
savedGrinds.clear();
savedGrinds.addAll(staged.savedGrinds);
archive.adoptFrom(staged.archive);
profileTimeZoneId = staged.profileTimeZoneId;
bumpRevision();
claims.adoptFrom(staged.claims);
deathReclaim.adoptFrom(staged.deathReclaim);
pkHistory.adoptFrom(staged.pkHistory);
geCustody.adoptFrom(staged.geCustody);
trackedBasis.adoptFrom(staged.trackedBasis);
generalSession = staged.generalSession;
customSession = staged.customSession;
generalSuspendedByCustom = staged.generalSuspendedByCustom;
activeSession = staged.activeSession;
profileIdentityKey = orEmpty(identityKey);
}

/**
* Pauses for Idle. The AFK timeout window itself still counts as active time; only the time
* after the pause fires is excluded, so the active clock never shrinks retroactively
* (owner 2026-10-01). {@code idleStartedAt} stays in the signature for the caller and tests.
*/
synchronized void pauseForIdle(long now, long idleStartedAt) {
if (live()) {
activeSession.pause(now, IDLE);
resetTrackingState(false);
}
}

synchronized void clearPlayerWorldLocation() {
playerLocationKnown = false;
}

synchronized void markContext(Context newContext, int ticks, String note) {
if (newContext != LOOT && newContext != PK_LOOT) claims.cancelChest();
if (newContext == Context.TRANSFER || newContext == MARKET) chargeLoadTransferEvidence.clear();
supersedeDeathMarkerForExplicitContext(newContext, note);
if (newContext == Context.TRANSFER) {
// Stale Eat/Bury intents must not reclassify bank deposits as Used/lost.
consumptionIntent = null;
dropIntent = null;
noteHardTransferEvidence(max(1, ticks));
}
setActiveContext(newContext, ticks, note, emptyMap(), null);
}

/**
* Bank interface is open on this tick. Soft UI-open refresh only — does not
* by itself prove a deposit/withdraw movement.
*/
synchronized void markBankInterfaceOpen(int ticks) {
claims.cancelChest();
chargeLoadTransferEvidence.clear();
supersedeDeathMarkerForExplicitContext(Context.TRANSFER, "Bank transfer");
// Edge-trigger only. Live GameTick refreshes this every tick while the bank
// widget/container is open — wiping Eat/Drink/Bury intents each tick made
// potato-field sessions book every inventory delta as soft TRANSFER (Used/lost
// ×0, no harvest rows) whenever bank detection stuck or fluttered.
boolean newlyOpened = !bankInterfaceOpen;
bankInterfaceOpen = true;
if (newlyOpened) {
consumptionIntent = null;
dropIntent = null;
}
transferEvidenceTicks = max(transferEvidenceTicks, max(1, ticks));
setActiveContext(Context.TRANSFER, ticks, "Bank transfer", emptyMap(), null);
}

/**
* Bank interface closed this tick. Clears sticky soft TRANSFER hints so burial
* after close is not classified as TRANSFER when the inventory callback raced
* ahead of this tick. Genuine deposits keep pending TRANSFER only when hard
* bank-container/menu evidence was attached to this pending change — or when
* hard evidence is still live and the inventory callback arrives after close.
*/
synchronized void markBankInterfaceClosed() {
bankInterfaceOpen = false;
// Soft UI-open refresh only. Hard deposit/withdraw TTL must survive close so a
// GameTick between bank-container evidence and inventory dirty stays TRANSFER.
if (!hardTransferContextActive) transferEvidenceTicks = 0;
if (dirty || pendingSnapshot != null) {
if (!pending.hard && !hardTransferContextActive) {
// Soft UI-open latch only — clear so burial after close is CONSUMPTION.
// Genuine deposits attach hard evidence via bank-container/menu markContext.
pending.transfer = false;
if (pending.context == Context.TRANSFER) pending.clear();
} else if (hardTransferContextActive && !pending.hard) {
// Inventory already dirty from soft open; promote with surviving hard evidence.
latchHardTransferIntoPending();
}
return;
}
if (active.context == Context.TRANSFER) {
// Drop the soft UI-open note/ticks; live hard evidence keeps TRANSFER for a late
// inventory callback.
active.clear();
active.context = hardTransferContextActive ? Context.TRANSFER : GENERIC;
contextTicks = 0;
}
}

void noteHardTransferEvidence(int ticks) {
chargeLoadTransferEvidence.clear();
int safeTicks = max(1, ticks);
transferEvidenceTicks = max(transferEvidenceTicks, safeTicks);
hardTransferEvidenceTicks = max(hardTransferEvidenceTicks, safeTicks);
hardTransferContextActive = true;
if (dirty || pendingSnapshot != null) latchHardTransferIntoPending();
}

void latchHardTransferIntoPending() {
pending.transfer = true;
pending.hard = true;
if (pending.context == GENERIC || Context.TRANSFER.getPriority() > pending.context.getPriority()) {
// Keep the producer's own note (minigame wipe, neutral zone, death) — a
// generic "Bank transfer" would otherwise win the priority race and mislabel
// every non-bank transfer row.
String note = active.context == Context.TRANSFER && active.note != null && !active.note.trim().isEmpty()
? active.note : "Bank transfer";
applyPendingContext(Context.TRANSFER, note, emptyMap(), null);
} else if (pending.context == Context.TRANSFER) {
pending.transfer = true;
if (empty(pending.note)) pending.note = "Bank transfer";
}
}

/**
* Soft evidence: inventory became dirty while the bank UI was open. Survives
* bank close for genuine deposits. Cleared by {@link #markBankInterfaceClosed()}
* when the pending change was only a stale latch after the UI was already gone
* (plugin closes before dirty when {@code !isBankOpen()}).
*/
synchronized void markLootContext(Map<Integer, Long> expectedLoot, int ticks, String note, String activityName) {
Map<Integer, Long> safeExpectedLoot = positiveEntries(expectedLoot);
if (!safeExpectedLoot.isEmpty()) {
lootExpectations.add(new LootExpectation(safeExpectedLoot, max(1, ticks), note, activityName));
}
setActiveContext(LOOT, ticks, note, safeExpectedLoot, null);
}

void setActiveContext(Context newContext, int ticks, String note, Map<Integer, Long> expectedLoot, String encounterId) {
Context safeContext = newContext == null ? GENERIC : newContext;
Map<Integer, Long> safeExpectedLoot = positiveEntries(expectedLoot);
supersedeDeathMarkerForExplicitContext(safeContext, note);
boolean replace = contextTicks <= 0 || safeContext.getPriority() > active.context.getPriority();
if (replace || safeContext == active.context) contextTicks = max(replace ? 0 : contextTicks, max(1, ticks));
active.offer(safeContext, note, safeExpectedLoot, encounterId, replace);
if (dirty || pendingSnapshot != null) applyPendingContext(safeContext, note, safeExpectedLoot, encounterId);
}

/**
* A death marker is a short-lived reconciliation hint, not a durable activity
* context. An explicit later activity must own the next measured change even though
* TRANSFER has a higher general context priority.
*/
void supersedeDeathMarkerForExplicitContext(Context safeContext, String note) {
boolean newDeathMarker = safeContext == Context.TRANSFER && isDeathHeldNote(note);
boolean activeDeathMarker = active.context == Context.TRANSFER && isDeathHeldNote(active.note);
boolean pendingDeathMarker = pending.context == Context.TRANSFER && isDeathHeldNote(pending.note);
if (!newDeathMarker && safeContext != GENERIC && (activeDeathMarker || pendingDeathMarker)) {
if (activeDeathMarker) {
active.clear();
contextTicks = 0;
}
if (pendingDeathMarker) pending.clear();
transferEvidenceTicks = 0;
clearHardTransferEvidence();
deathReclaim.cancelDeathWipe();
}
}

synchronized void markPkLootContext(Map<Integer, Long> expectedLoot, int ticks, String label, long now) {
if (activeSession == null && !startGeneralFromActivityIfNeeded(now)) return;
if (!live()) return;
Map<Integer, Long> safeExpectedLoot = positiveEntries(expectedLoot);
PkEncounter encounter = activeSession.addPkEncounter(EncounterType.KILL, now, label, ClassificationConfidence.CONFIRMED,
msg("ib"));
pkHistory.registerEncounter(encounter);
if (activeSession.getMode() != SessionMode.GENERAL) {
activeSession.attachRecentCostsToEncounter(encounter.getId(), now, 90_000L);
}
String note = "PK loot";
if (!safeExpectedLoot.isEmpty()) {
lootExpectations.add(new LootExpectation(safeExpectedLoot, max(1, ticks), note, "PKing", PK_LOOT, encounter.getId()));
}
setActiveContext(PK_LOOT, ticks, note, safeExpectedLoot, encounter.getId());
}

/**
* Local unsafe PvM death with a separate canonical inventory/equipment ownership
* snapshot. The snapshot is reconciled against measured negative flows; presentation
* evidence remains explanation-only.
*/
synchronized void markLocalPvmDeath(int ticks, LocalDeathEvidence evidence, Map<Integer, Long> heldItemsAtDeath) {
pendingLocalDeathEvidence = evidence;
markContext(Context.TRANSFER, max(1, ticks), msg("fw"));
// Start after the transfer marker so this new death cannot cancel its own
// bounded whitelist when strong-context supersession is evaluated.
deathReclaim.onLocalPvmDeath(heldItemsAtDeath);
}

/**
* Local death with no confident PvM / PK classification. Drop stale transfer and
* action evidence so its inventory wipe is booked from ordinary loss evidence.
*/
synchronized void markUnclassifiedLocalDeath(LocalDeathEvidence evidence) {
claims.cancelChest();
pendingLocalDeathEvidence = evidence;
deathReclaim.reset();
consumptionIntent = null;
dropIntent = null;
clearHardTransferEvidence();
transferEvidenceTicks = 0;
bankInterfaceOpen = false;
contextTicks = max(6, config.stabilizationTicks() + 4);
active.clear();
active.note = UNCLASSIFIED_LOCAL_DEATH_NOTE;
if (dirty || pendingSnapshot != null) {
pending.clear();
pending.note = UNCLASSIFIED_LOCAL_DEATH_NOTE;
}
}

/**
* Player interacted with a retrieval service (gravestone, Death, boss NPC or chest).
*
* @return true when the reclaim window was armed; ambiguous targets (a bare "Chest",
*         a gravestone) only arm while a local PvM death is awaiting reclaim
*/
synchronized boolean noteDeathReclaimIntent(Service service, int ticks) {
return deathReclaim.noteReclaimIntent(service, ticks);
}

/** Current presentation read model for the local death-reclaim indicator. */
synchronized DeathReclaimStatus getDeathReclaimStatus() {
return new DeathReclaimStatus(deathReclaim.isAwaitingReclaim(), deathReclaim.isReclaimArmed(),
deathReclaim.outstandingItemCount(), deathReclaim.ageTicks());
}

static Transaction firstTransaction(Transaction... candidates) {
for (Transaction candidate : candidates) {
if (candidate != null) return candidate;
}
return null;
}

/**
* Remove measured death-owned returns before minimum-value and ordinary gain
* classification. Source-backed loot quantities are protected first; unmatched gains
* and all non-coin costs stay in the ordinary settle. A fee is booked only from an
* observed coin loss with a live service interaction or a matched return in the same
* settle.
*/
Transaction settleDeathReclaim(List<Flow> flows, long now, Map<Integer, Long> observedLootQuantities,
boolean allowObservedFee) {
if (!deathReclaim.isAwaitingReclaim() || empty(flows)) return null;
Service service = deathReclaim.reclaimService;
Map<Integer, Long> sourceBacked = positiveEntries(observedLootQuantities);
// Source-backed loot quantities are protected first.
List<Flow> returned = claim(flows, Flow::isGain, (flow, quantity) -> deathReclaim.matchReturnedItem(flow.itemId,
quantity - take(sourceBacked, flow.itemId, quantity)));
long coinsPaid = lossQuantities(flows, itemId -> itemId == ItemID.COINS).getOrDefault(ItemID.COINS, 0L);
boolean hasOtherLoss = hasLoss(flows, itemId -> itemId != ItemID.COINS);
Transaction recovered = null;
if (!returned.isEmpty()) {
String source = service == null ? msg("az") : service.interactable;
recovered = bookNeutral(now, msg("bj"), "Death reclaim", returned,
"Items recovered from " + source + " after a death; ownership never changed.", null);
audit(recovered);
}
boolean feeEvidence = service != null || !returned.isEmpty();
if (allowObservedFee && coinsPaid > 0L && feeEvidence && (!hasOtherLoss || !returned.isEmpty())) {
String why = service == null ? msg("fz") : service.why(coinsPaid);
flows.removeIf(flow -> flow != null && flow.itemId == ItemID.COINS && flow.isCost());
return bookDeathReclaimFee(coinsPaid, why, now, false);
}
return recovered;
}

/** Create a neutral audit receipt for the measured negative portion of the death wipe. */
Transaction extractDeathWipeTransfer(List<Flow> flows, Map<Integer, Long> measuredLosses, long now,
LocalDeathEvidence deathEvidence) {
if (empty(flows) || empty(measuredLosses)) return null;
var outstanding = new HashMap<Integer, Long>(measuredLosses);
List<Flow> transferred = claim(flows, Flow::isCost, (flow, quantity) -> take(outstanding, flow.itemId, quantity));
if (transferred.isEmpty()) return null;
String explanation = msg("fx");
if (deathEvidence != null) explanation = deathEvidence.appendTo(explanation, transferred);
Transaction transfer = bookNeutral(now, msg("fw"), "Death reclaim", transferred, explanation, null);
audit(transfer);
return transfer;
}

void recordNeutralZoneEntryItems(Map<Integer, Long> deltas) {
if (deltas != null) {
deltas.forEach((itemId, delta) -> {
if (itemId != null && delta != null && delta < 0L) {
neutralZoneStoredItems.merge(itemId, SafeMath.abs(delta), SafeMath::safeAdd);
}
});
}
}

/**
* Excludes only positive quantities that match items actually removed on zone entry.
* Item ids with a matched observed-loot event remain counted as loot even if they
* happen to overlap the stored gear.
*/
Transaction settleNeutralZoneRestore(List<Flow> flows, Map<Integer, Long> observedLootQuantities, long now) {
if (neutralZoneRestoreTicks <= 0 || neutralZoneStoredItems.isEmpty() || empty(flows)) {
return null;
}
Map<Integer, Long> sourceBacked = positiveEntries(observedLootQuantities);
List<Flow> restored = claim(flows, Flow::isGain, (flow, quantity) -> take(neutralZoneStoredItems, flow.itemId,
quantity - take(sourceBacked, flow.itemId, quantity)));
if (restored.isEmpty()) return null;
if (neutralZoneStoredItems.isEmpty()) neutralZoneRestoreTicks = 0;
return bookNeutral(now, msg("cg"), "Gauntlet", restored, msg("gw"), null);
}

synchronized void markPkDeath(String label, long now, LocalDeathEvidence evidence) {
claims.cancelChest();
deathReclaim.reset();
if (activeSession == null && !startGeneralFromActivityIfNeeded(now)) return;
if (!live()) return;
pendingLocalDeathEvidence = evidence;
PkEncounter encounter = activeSession.addPkEncounter(EncounterType.DEATH, now, label,
ClassificationConfidence.CONFIRMED, msg("ic"));
pkHistory.registerEncounter(encounter);
setActiveContext(PK_DEATH, max(30, config.stabilizationTicks() + 4), "PK death loss", emptyMap(), encounter.getId());
}

/** An ownership-neutral storage or minigame move, with the note its classifier already chose. */
synchronized void markTransfer(String note, int ticks) {
markContext(Context.TRANSFER, max(1, ticks), note);
}

/** Arm the narrow load intent with the target identity later used by Check. */
/** A measured check for this family supersedes its pending estimates. */
synchronized void clearPendingEstimates(String family) {
if (activeSession != null) chargeEstimateJournal.clearFamily(activeSession.getId(), family);
}

/**
* A compatible Check that moved nothing closes the window for this target: any automatic
* estimates still pending were overestimates and are removed through the announced path.
*/
synchronized void closeChargeWindow(String targetIdentity) {
if (activeSession == null || targetIdentity == null || targetIdentity.trim().isEmpty()) return;
String sessionId = activeSession.getId();
long now = System.currentTimeMillis();
for (ChargeEstimateJournal.Receipt receipt : chargeEstimateJournal.receiptsFor(sessionId, targetIdentity)) {
forfeit(receipt, now);
}
chargeEstimateJournal.clearTarget(sessionId, targetIdentity);
}

synchronized boolean markChargeLoadTransfer(Variant variant, int selectedItemId, String selectedItemName,
String targetIdentity, int ticks) {
return chargeLoadTransferEvidence.arm(variant, selectedItemId, selectedItemName, targetIdentity, ticks);
}

/** Clear a pending charge-load intent when another action supersedes its evidence. */
synchronized void clearChargeLoadTransfer() {
chargeLoadTransferEvidence.clear();
}

/**
* Observe Check evidence; a measured increase reconciles that weapon's pending load against
* its own last Check.
*/
synchronized ChargeDelta observeMeasuredChargeRead(ChargeRead read, String targetIdentity, long now) {
MeasuredChargeReadTracker.Baseline last = measuredChargeReadTracker.baseline(targetIdentity);
Map<Integer, Long> exactLoadQuantities = read == null || last == null ? emptyMap()
: ChargeRead.exactLoadQuantities(last.getRead(), read);
ChargeDelta delta = measuredChargeReadTracker.observe(read, targetIdentity, now);
if (!exactLoadQuantities.isEmpty()) {
chargeLoadReviews.reconcileChargeLoadReviews(
read.variant, targetIdentity, exactLoadQuantities, last.getAtEpochMillis(), now);
}
return delta;
}

/** A Check without trustworthy target identity invalidates the previous baseline. */
synchronized void resetMeasuredChargeReads() {
measuredChargeReadTracker.reset();
}

/**
* A supported charged weapon entering the inventory is a new physical instance: a measured
* Check baseline for that variant can no longer be trusted to belong to the item now in the
* slot. Fail closed and require a fresh baseline instead of booking a phantom decrease from
* a replacement that retained the same slot identity.
*/
void invalidateChargeBaselinesForCustodyChange(Map<Integer, Long> deltas) {
if (empty(deltas)) return;
for (Map.Entry<Integer, Long> entry : deltas.entrySet()) {
Long quantity = entry.getValue();
if (quantity == null || quantity <= 0L) continue;
Variant variant = ChargeRead.supportedVariantForItemId(entry.getKey());
if (variant != null) measuredChargeReadTracker.resetVariant(variant);
}
}

/** Start tracking the items removed by entering a region-based neutral zone. */
synchronized void beginNeutralZoneTransfer() {
neutralZoneStoredItems.clear();
neutralZoneTransferActive = true;
captureNeutralZoneEntry = true;
neutralZoneEntryBaseline = baseline;
// Inventory and equipment callbacks can settle in separate batches. Keep
// reconciling against the entry snapshot for a short, fixed window so the
// later batch is included, but in-zone supply use cannot be captured forever.
neutralZoneEntryCaptureTicks = max(8, config.stabilizationTicks() + 6);
neutralZoneRestoreTicks = 0;
}

/**
* End the region-based transfer context. Only a matching return of an item actually
* removed on entry remains neutral; later lobby rewards keep their normal classification.
*/
synchronized void endNeutralZoneTransfer(int ticks) {
boolean zoneWasActive = neutralZoneTransferActive;
neutralZoneTransferActive = false;
captureNeutralZoneEntry = false;
neutralZoneEntryBaseline = null;
neutralZoneEntryCaptureTicks = 0;
neutralZoneRestoreTicks = neutralZoneStoredItems.isEmpty() ? 0 : max(1, ticks);
boolean pendingZoneContext = pending.context == Context.TRANSFER && pending.note != null
&& pending.note.startsWith(msg("de"));
boolean activeZoneContext = active.context == Context.TRANSFER && active.note != null
&& active.note.startsWith(msg("df"));
if (pendingZoneContext) pending.clear();
if (activeZoneContext) {
active.clear();
contextTicks = 0;
}
if (zoneWasActive || pendingZoneContext || activeZoneContext) {
transferEvidenceTicks = 0;
clearHardTransferEvidence();
}
}

/** Begin clue/casket path so dig/tele/key spends pair as clue costs. */
synchronized void beginCluePath(String encounterId, String label) {
clueCostPairing.beginClue(encounterId, label);
}

/** Ids of the unresolved key/chest claims; a Ledger row with one of these ids is still pending. */
synchronized Set<String> getPendingClaimIds() {
var ids = new LinkedHashSet<String>();
for (PendingClaim claim : claims.all()) ids.add(claim.getClaimId());
return ids;
}

synchronized List<PendingClaim> getPendingClaims() {
return claims.all();
}

synchronized void beginLootKeyLocalDeathSettle(Map<Integer, Long> keyInventory) {
claims.beginLocalDeath(keyInventory);
}

synchronized void tickKeyClaims() {
claims.tick();
}

/** Arm/cancel ordinary key-chest provenance from an actual chest object click. */
synchronized boolean observeKeyChestInteraction(String option, String target) {
return claims.chestClick(option, target);
}

/**
* Per-tick GE custody maintenance: a held collect resolves at its window end and expired
* lifecycles hand off even when no further inventory change settles. Returns true when a
* booking was added.
*/
synchronized boolean tickGeCustody(long now) {
return live() && maintainCustody(now);
}

/** Durable schema-104 GE custody lifecycle; flows are claimed at Flow quantity level. */
final GeCustodyLedger geCustody = new GeCustodyLedger();
/** Schema-106 pooled known-basis coverage; supporting ownership state, never a second ledger. */
final TrackedBasisLedger trackedBasis = new TrackedBasisLedger();
/** Bounded player Collect interaction evidence; never books by itself. */
long geCollectionIntentUntil;
/**
* Feeds an offer transition to the custody lifecycles. Review handoffs (slot reuse, identity
* replacement, unobserved terminal) and settlements completed by a held collect book once.
*/
synchronized void noteGeOfferObservation(OfferLedger.Transition transition, String itemName, long now) {
for (Transaction outcome : geCustody.observeTransition(transition, itemName, now,
activeSession == null ? "" : activeSession.getId())) {
bookCustodyOutcome(outcome);
}
}

/**
* Login/relog slot baseline. Resumes persisted custody lifecycles whose offer identity is
* unchanged, quarantines offers first seen already open as legacy/unbased, and hands
* changed-slot lifecycles to Review. Never invents basis and never replays old progress.
*/
synchronized void seedGeOfferSlots(Map<Integer, OfferLedger.Snapshot> snapshots, long now) {
for (Transaction handoff : geCustody.seedSlots(snapshots, now, activeSession == null ? "" : activeSession.getId())) {
bookCustodyOutcome(handoff);
}
}

/** Derived, read-only Market settlement context over the durable custody lifecycles. */
synchronized List<MarketSettlementProjection.Row> getMarketSettlements() {
return MarketSettlementProjection.rows(geCustody.snapshotRecords(), this::transactionById, marketSessions);
}

/** Market rows follow the session that holds their booked settlement (cross-Grind sales). */
final SessionLookup marketSessions = new SessionLookup() {
public Session holding(String transactionId) {
for (Session session : profileAndHistorySessions()) {
if (findTransaction(session, transactionId) != null) return session;
}
return null;
}
public Session byId(String sessionId) {
for (Session session : profileAndHistorySessions()) {
if (session != null && session.getId().equals(sessionId)) return session;
}
return null;
}
};

List<Session> profileAndHistorySessions() {
var sessions = new ArrayList<Session>(uniqueProfileSessions());
sessions.addAll(history);
return sessions;
}

/**
* Canonical item identity for GE custody/execution correlation, using the same
* noted/variation policy as inventory valuation. Identity normalization only; it never
* re-values a movement.
*/
/** Effective correction-aware canonical settlement lookup for the derived Market projection. */
Transaction transactionById(String id) {
for (Session session : id == null || id.isEmpty() ? Collections.<Session>emptyList() : profileAndHistorySessions()) {
Transaction found = findTransaction(session, id);
if (found != null) return found;
}
return null;
}

static Transaction findTransaction(Session session, String id) {
if (session == null) return null;
for (Transaction transaction : session.getTransactions()) {
if (transaction != null && id.equals(transaction.getId())) return transaction;
}
return null;
}

/**
* A bounded player Collect interaction with the Grand Exchange collection box. It only
* enables exact custody collection correlation for the window; it never books anything and
* never owns a movement by itself.
*/
synchronized void noteGeCollectionIntent(long now) {
geCollectionIntentUntil = max(geCollectionIntentUntil, now) + 20_000L;
}

/** Release held cash to its current owner's Review before dropping transient evidence. */
synchronized void clearGeCollectionIntent() {
geCollectionIntentUntil = 0L;
var outcomes = new ArrayList<Transaction>();
geCustody.releasePendingSettlement(geCustody.pendingSettlementAtEpochMillis, outcomes);
outcomes.forEach(this::bookCustodyOutcome);
}

/** Canonical custody outcomes (review handoffs and retried settlements) are booked once. */
void bookCustodyOutcome(Transaction outcome) {
if (outcome != null && activeSession != null) {
// Counted custody settlements already consumed their reservation; their item legs
// must never deplete known coverage again.
add(outcome, outcome.isCounted());
}
}

/** Authoritative profile/analytics timezone for presentation date scopes. */
synchronized ZoneId getProfileTimeZone() {
try { return ZoneId.of(profileTimeZoneId); }
catch (RuntimeException ex) { return ZoneId.systemDefault(); }
}

synchronized void setLootKeyChestVisible(boolean visible) {
claims.setLootChestVisible(visible);
}

synchronized void observeLootKeyContainer(int keyItemId, Map<Integer, Long> contents) {
claims.observeKeyContainerContents(keyItemId, contents);
}

/** A non-counted, ownership-neutral audit row: transfers, holds, restores and reclaims. */
Transaction bookNeutral(long now, String note, String activityName, List<Flow> flows,
String explanation, String encounterId) {
return bookTransaction(now, TransactionType.TRANSFER, Context.TRANSFER, note, activityName, false,
flows, ClassificationConfidence.CONFIRMED, explanation, encounterId);
}

/** A session is open and not paused. */
boolean live() {
return activeSession != null && !activeSession.paused;
}

/** Books a row; custody-owned rows already consumed their reservation and never deplete again. */
void add(Transaction transaction, boolean custodyOwned) {
activeSession.addTransaction(transaction, config.maxTransactionsPerSession(), custodyOwned);
}

/** Keeps a neutral transfer row only when the player asked to see them. */
void audit(Transaction row) {
if (config.keepTransferAuditRows()) add(row, false);
}

/** Books the custody settlements that came due; true when any did. */
boolean maintainCustody(long now) {
List<Transaction> bookings = geCustody.maintenance(now, activeSession.getId()).countedBookings;
bookings.forEach(booking -> add(booking, true));
return !bookings.isEmpty();
}

Transaction bookTransaction(long now, TransactionType type, Context context, String note, String activityName,
boolean counted, List<Flow> flows, ClassificationConfidence confidence, String explanation, String encounterId) {
return new Transaction(now, activeSession.getElapsedMillis(now), type, context, note, activityName, counted, flows,
confidence, explanation, encounterId);
}

/** A booked charge row: counted consumption with the current activity, then its action kind. */
private Transaction bookChargeRow(long now, String note, ClassificationConfidence confidence,
String explanation, List<Flow> flows, ActionKind kind) {
Transaction transaction = bookTransaction(now, CONSUMPTION, GENERIC, note,
nonBlank(activeSession.getActivityHint(), "General"), true, flows, confidence, explanation, null);
transaction.setActionKind(kind);
add(transaction, false);
return transaction;
}

/**
* Book estimated component losses for one automatic charge use. The receipt counts immediately
* at the captured prices with LIKELY confidence; a later measured Check reconciles it. Priced
* components enter the transient journal so a Check can shrink them without repricing.
*/
synchronized Transaction bookEstimatedChargeUsage(String targetIdentity, String family, ActionKind kind,
List<Flow> componentLosses, long now) {
String target = nonBlank(targetIdentity, null);
String label = nonBlank(family, null);
if (!live() || target == null || label == null || componentLosses == null || componentLosses.isEmpty()) {
return null;
}
Transaction transaction = bookChargeRow(now, "Estimated charge use · " + label,
ClassificationConfidence.LIKELY, "Estimated", componentLosses, kind == null ? CAST : kind);
String sessionId = activeSession.getId();
for (Flow flow : transaction.getFlows()) {
if (flow != null && flow.isCost() && flow.unitPrice > 0) {
chargeEstimateJournal.record(sessionId, target, label, transaction.getId(), flow.itemId, abs(flow.quantityDelta));
}
}
return transaction;
}

/**
* Consume one measured component decrease against this target's pending estimates. An
* uncorrected straddling receipt is shrunk to the measured quantity at its captured price and
* confirmed; manually corrected receipts are only consumed, never edited. Returns the covered
* quantity.
*/
private long reconcileEstimate(String sessionId, String target, int itemId, long measured, long now) {
long left = measured;
for (ChargeEstimateJournal.Receipt receipt : chargeEstimateJournal.receipts(sessionId, target, itemId)) {
long remaining = receipt.remainingQuantity;
if (remaining <= 0L) continue;
Transaction estimate = activeSession.findTransaction(receipt.transactionId);
boolean frozen = estimate == null || estimate.getCorrection() != Correction.AUTO;
if (left < remaining && !frozen) {
// Shrink the overestimate through the session's announced automatic-evidence path.
activeSession.settleEstimate(estimate, itemId, remaining - left, "Confirmed");
chargeEstimateJournal.consume(receipt, remaining);
left = 0L;
continue;
}
long take = min(left, remaining);
chargeEstimateJournal.consume(receipt, take);
left -= take;
if (!frozen && take >= remaining) {
estimate.confidence = ClassificationConfidence.CONFIRMED;
estimate.rewriteExplanation("Confirmed");
}
if (left <= 0L) break;
}
// The measured window is closed here: automatic estimates left over are overestimates
// and are removed, while manually corrected rows are preserved for future credit.
for (ChargeEstimateJournal.Receipt receipt : chargeEstimateJournal.receipts(sessionId, target, itemId)) {
forfeit(receipt, now);
}
return measured - left;
}

/** Removes one overestimated automatic receipt through the session's announced path. */
private void forfeit(ChargeEstimateJournal.Receipt receipt, long now) {
if (receipt == null || receipt.remainingQuantity <= 0L || activeSession == null) return;
Transaction estimate = activeSession.findTransaction(receipt.transactionId);
if (estimate == null || estimate.getCorrection() != Correction.AUTO) return;
activeSession.settleEstimate(estimate, receipt.itemId, receipt.remainingQuantity, "Check reconciled");
chargeEstimateJournal.consume(receipt, receipt.remainingQuantity);
}

/**
* Book exact component losses from a compatible measured Check difference, first reconciling
* any pending estimates for that target; returns the booked row, or null (zero cost) when the
* measurement adds nothing beyond what estimates already booked. Generic inventory losses,
* menu intent and animations cannot authorize this.
*/
synchronized Transaction bookChargeSpend(ChargeDelta measuredDelta, String itemName, List<Flow> componentLosses,
long now) {
return bookChargeSpend(measuredDelta, itemName, componentLosses, now, null);
}

synchronized Transaction bookChargeSpend(ChargeDelta measuredDelta, String itemName, List<Flow> componentLosses,
long now, String targetIdentity) {
if (!live() || measuredDelta == null || measuredDelta.exactCost(componentLosses,
chargeLoadReviews.pendingChargeLoadComponents(measuredDelta.getVariant())) <= 0L) {
return null;
}
List<Flow> booked = componentLosses;
String target = targetIdentity == null ? "" : targetIdentity.trim();
if (!target.isEmpty()) {
var excess = new ArrayList<Flow>();
String sessionId = activeSession.getId();
for (Flow flow : componentLosses) {
long measured = abs(flow.quantityDelta);
long extra = measured - reconcileEstimate(sessionId, target, flow.itemId, measured, now)
- chargeEstimateJournal.evictedOverlap(sessionId, target, flow.itemId);
if (extra <= 0L) continue;
excess.add(extra == measured ? flow : new Flow(flow.itemId, flow.itemName, -extra, flow.unitPrice,
-SafeMath.safeMultiply(extra, flow.unitPrice), flow.getPriceSource(), flow.priceCapturedAtEpochMillis));
}
if (excess.isEmpty()) return null;
booked = excess;
}
// The spend belongs to what the player is doing, not to the weapon: Top activities
// must never grow a "Toxic blowpipe" row. The weapon stays in the note.
Transaction transaction = bookChargeRow(now, "Measured charge spend · " + nonBlank(itemName, "Measured charges"),
ClassificationConfidence.CONFIRMED,
"Measured Check difference for " + measuredDelta.getVariant().getDisplayName() + ".",
booked, measuredDelta.getVariant() == Variant.V1b ? FIRE : CAST);
return transaction;
}

/**
* Book a death reclaim fee as counted PK_FEE / Lost cost.
*/
synchronized Transaction bookDeathReclaimFee(long feeGp, String why, long now) {
return bookDeathReclaimFee(feeGp, why, now, true);
}

/**
* @param pvp true books the PK_FEE / PK_DEATH row used for player-combat deaths; false
*            books a plain counted CONSUMPTION cost under the "Death reclaim" activity
*/
synchronized Transaction bookDeathReclaimFee(long feeGp, String why, long now, boolean pvp) {
if (feeGp <= 0L || !live()) return null;
var feeFlow = new Flow(ItemID.COINS, "Coins", -feeGp, 1, -feeGp, PriceSource.FACE_VALUE, now);
Transaction transaction = bookTransaction(now, pvp ? PK_FEE : CONSUMPTION, pvp ? PK_DEATH : GENERIC,
why == null ? DeathReclaimFees.graveFeeWhy(false) : why, "Death reclaim", true, singletonList(feeFlow),
ClassificationConfidence.CONFIRMED, why == null ? DeathReclaimFees.graveFeeWhy(false) : why, null);
add(transaction, false);
return transaction;
}

synchronized boolean correctTransaction(String transactionId, Correction correction, long now, String reason) {
boolean changed = activeSession != null && activeSession.correctTransaction(transactionId, correction, now, reason);
return changed;
}

/** Returns the active custom/general session's still-unresolved decisions as detached rows. */
/** Unresolved owner decisions in the active session, newest first, as review rows. */
synchronized List<ReviewRow> getReviewRows(long now) {
var rows = new ArrayList<ReviewRow>();
if (activeSession == null) return rows;
for (Transaction transaction : activeSession.getTransactions()) {
if (ReviewEligibility.needsOwnerDecision(transaction)) rows.add(reviewRow(activeSession, transaction, now));
}
rows.sort(Comparator.comparingLong((ReviewRow itemDataValue) -> itemDataValue.timestampEpochMillis).reversed());
return rows;
}

/** Current world/economy permission for ordinary automatic market quotes (presentation reads it). */
boolean automaticMarketQuotesAvailable() {
return valuationService.automaticMarketQuotesAvailable();
}

/** A detached copy of the active session's transactions for exports that must not race bookings. */
synchronized List<Transaction> tx() {
return activeSession == null ? new ArrayList<>() : new ArrayList<>(activeSession.getTransactions());
}

/** Domain revision: every canonical mutation advances it; derived previews bind to it. */
synchronized long getRevision() {
return revision;
}

/**
* Exact rows and prospective personal-Net delta a Decide all would apply, computed from
* the same canonical transaction ids that {@link #applyDecideAll} mutates.
*/
synchronized DecideAllPreview previewDecideAll(ReviewDecision decision, Predicate<ReviewRow> scope, long now) {
var selectedIds = new ArrayList<String>();
long netDelta = 0L;
if (activeSession != null && decision != null && scope != null) {
Correction correction = decision.toCorrection();
for (Transaction transaction : activeSession.getTransactions()) {
if (!ReviewEligibility.needsOwnerDecision(transaction) || !scope.test(reviewRow(activeSession, transaction, now))) {
continue;
}
selectedIds.add(transaction.getId());
netDelta = SafeMath.safeAdd(netDelta,
transaction.netUnder(correction) - (transaction.isCounted() ? transaction.getNet() : 0L));
}
}
return new DecideAllPreview(decision, selectedIds, netDelta, getRevision());
}

/**
* Applies a preview as one atomic batch with one undo. Returns {@code -1} without touching
* anything when the domain revision moved since the preview (stale row ids never apply).
*/
synchronized int applyDecideAll(DecideAllPreview preview, long now) {
if (preview == null || activeSession == null || preview.transactionIds.isEmpty()) return 0;
if (preview.revision != getRevision()) return -1;
int changed = activeSession.correctPendingTransactions(preview.transactionIds,
preview.decision.toCorrection(), now, "Review decision: " + preview.decision.name());
return changed;
}

/** Revision-bound Decide all impact: canonical ids, row count and exact prospective Net delta. */
static class DecideAllPreview {
final ReviewDecision decision;
final List<String> transactionIds;
final long netDelta;
final long revision;
DecideAllPreview(ReviewDecision decision, List<String> transactionIds, long netDelta, long revision) {
this.decision = decision;
this.transactionIds = unmodifiableList(new ArrayList<>(transactionIds));
this.netDelta = netDelta;
this.revision = revision;
}
int rowCount() {
return transactionIds.size();
}
}

static ReviewRow reviewRow(Session session, Transaction transaction, long now) {
var items = new ArrayList<Item>();
for (Flow flow : transaction.getFlows()) {
if (flow != null) {
items.add(new Item(flow.itemId, flow.itemName, flow.quantityDelta, flow.valueDelta));
}
}
if (items.isEmpty()) items.add(new Item(-1, transaction.getNote(), 0L, transaction.getAutomaticNet()));
String why = transaction.getExplanation();
if (blank(why)) why = msg("dg");
long timestamp = transaction.timestampEpochMillis;
long age = now > timestamp ? now - timestamp : 0L;
EnumSet<ReviewDecision> decisions = EnumSet.allOf(ReviewDecision.class);
return new ReviewRow(transaction.getId(), session.getId(), timestamp, age, items,
transaction.getAutomaticNet(), why, decisions);
}

synchronized boolean applyItemSplit(String transactionId, int itemId, long keepQuantity, long now,
String optionalNote) {
boolean changed = activeSession != null
&& activeSession.applyItemSplit(transactionId, itemId, keepQuantity, now, optionalNote);
return changed;
}

synchronized boolean undoLastCorrection(long now) {
boolean changed = activeSession != null && activeSession.undoLastCorrection(now);
return changed;
}

synchronized Transaction undoLastTransaction(long now) {
Transaction undone = activeSession == null ? null : activeSession.undoLastTransaction(now);
return undone;
}

synchronized Transaction restoreLastUndo(long now) {
Transaction restored = activeSession == null ? null : activeSession.restoreLastUndo(now);
return restored;
}

synchronized void recordAction(String activityHint) {
if (live()) activeSession.recordAction(activityHint, System.currentTimeMillis());
}

synchronized Transaction processIfDirty(ContainerSnapshot current, long now) {
return processIfDirty(current, now, null);
}

/** Additional source receipts are published after canonical booking has completed. */
synchronized Transaction processIfDirty(ContainerSnapshot current, long now,
java.util.function.Consumer<Transaction> additionalBooking) {
// Inventory alone must not create a tracker when automatic startup is
// disabled or when no session has been started yet.
if (activeSession == null) return null;
// processIfDirty is fed by the client's per-game-tick snapshot path.
// Keep active-time accrual on that event path instead of analytics reads.
// This window ages once per inventory/game-tick sample, even when the
// inventory remains dirty across several stabilization samples.
if (neutralZoneEntryCaptureTicks > 0 && --neutralZoneEntryCaptureTicks <= 0) {
neutralZoneEntryCaptureTicks = 0;
captureNeutralZoneEntry = false;
neutralZoneEntryBaseline = null;
}
// Death and reclaim windows are real game-tick lifecycles. Advance them before
// any dirty/stabilization early return so inventory churn cannot pause expiry.
String expiredDeathReclaim = deathReclaim.tick(dirty || pendingSnapshot != null
|| (current != null && baseline != null && !current.equals(baseline)));
if (expiredDeathReclaim != null) {
Transaction expiry = bookNeutral(now, msg("dh"), "Death reclaim", emptyList(), expiredDeathReclaim, null);
add(expiry, false);
return expiry;
}
if (activeSession.paused) {
// An inventory gain during an Idle pause is the action that ended it (a
// pickpocket's loot can settle before the activity detector fires): resume and
// book it. Other pauses stay aligned so Resume never inherits pause-window
// churn as catch-up profit (owner 2026-09-29).
Map<Integer, Long> woke = current == null || baseline == null ? null : current.diff(baseline);
boolean idleResume = activeSession.getPauseReason() == IDLE && woke != null
&& woke.values().stream().anyMatch(value -> value != null && value > 0L);
if (!idleResume) {
// Keep the baseline aligned while paused so Resume never treats
// pause-window inventory churn as catch-up profit.
baseline = current;
if (captureNeutralZoneEntry) neutralZoneEntryBaseline = current;
baselinePriming = false;
clearPendingChange();
consumptionIntent = null;
dropIntent = null;
clearHardTransferEvidence();
return advanceContext(now);
}
activeSession.resume(now);
}
if (baselinePriming) {
observePrimingSnapshot(current);
return null;
}
if (baseline == null) {
baseline = current;
if (captureNeutralZoneEntry) neutralZoneEntryBaseline = current;
clearPendingChange();
return null;
}
if (!dirty && current.equals(baseline)) return advanceContext(now);
// Container callbacks are expected, but comparing the actual snapshot
// makes the engine resilient to a missed or reordered callback.
if (!dirty) {
dirty = true;
captureActiveContextForPending();
}
if (pendingSnapshot == null || !pendingSnapshot.equals(current)) {
pendingSnapshot = current;
pendingStableTicks = 0;
captureActiveContextForPending();
return null;
}
pendingStableTicks++;
if (pendingStableTicks < max(0, config.stabilizationTicks())) return null;
Transaction settled = settle(pendingSnapshot, now, additionalBooking);
clearSettledContext();
return settled;
}

/** One stabilized inventory change moving through the settle stages, with the claim it settles under. */
static class Settle extends Clue {
final long now;
final ContainerSnapshot committed;
List<Flow> flows;
LocalDeathEvidence deathEvidence;
/** A local death with no confident PvM/PK classification: routine spends stay unlabelled. */
boolean suppressActionEvidence;
boolean localDeath;
boolean deathWipe;
boolean ambiguousDeathCoinLoss;
Map<Integer, Long> deathWipeLosses = emptyMap();
Map<Integer, Long> keyLosses = emptyMap();
List<Flow> deathEvidenceFlows = emptyList();
String settledClaimId;
Transaction keyAudit;
LootMatch loot = LootMatch.NONE;
/** Source-backed quantities, kept even if the loot label is later refused. */
Map<Integer, Long> lootQuantities = emptyMap();
boolean destroy;
boolean pricingReview;
boolean decant;
boolean dropped;
boolean consumed;
boolean asTransfer;
ActionKind actionIntent;
ActionLabel actionLabel;
int consumedItemId = -1;
int droppedItemId = -1;
String activity;
Settle(Clue claim, ContainerSnapshot committed, long now) {
offer(claim.context, claim.note, claim.expectedLoot, claim.encounterId, true);
transfer = claim.transfer;
hard = claim.hard;
this.committed = committed;
this.now = now;
}
}

/**
* Settles one stabilized inventory change. The stages run in order; each may book its own
* receipt and removes the flows it owns: commit, death wipe, key tokens, held items (death
* keys, wipe, charge loads), returns (loot, chest claims, death reclaim), neutral-zone restore,
* actions and transfers, GE custody, then classification and booking of what is left.
*/
Transaction settle(ContainerSnapshot committed, long now, java.util.function.Consumer<Transaction> additionalBooking) {
Settle s = commit(committed, now);
if (s == null) return null;
settleDeathWipe(s);
settleKeyTokens(s);
if (s.flows.isEmpty()) return s.keyAudit;
Transaction held = settleHeldItems(s);
if (s.flows.isEmpty()) return held;
Transaction returned = settleReturns(s);
if (s.flows.stream().allMatch(flow -> flow.valueDelta == 0L && flow.getPriceSource() != PriceSource.UNPRICED)) {
// A change with no value and no unpriced item is nothing to account for; a settled
// claim still closed the deferred key audit row above. No value threshold exists.
return firstTransaction(returned, s.keyAudit);
}
Transaction restore = settleNeutralZoneRestore(s.flows, s.lootQuantities, now);
if (restore != null) {
audit(restore);
if (s.flows.isEmpty()) return restore;
// Only the matched entry items are neutral. Classify residual flows using
// their actual consumption/loot evidence rather than the old region latch.
s.clear();
clearSettledContext();
}
List<Transaction> sources = bookLootSources(s);
// Partial source matches classify the whole remaining settle as loot only
// when the transfer partition consumed the unmatched quantities. Without
// that partition, an oversized same-id stack (or unrelated extra gain)
// must follow generic gain classification rather than inherit the loot tag.
if (s.loot.matched && !matchesExpectedLoot(s.flows, s.loot.matchedQuantities)) s.loot = LootMatch.NONE;
settleActions(s);
if (s.consumed && !s.dropped && !s.destroy && !s.hard && !s.decant && !s.suppressActionEvidence
&& ActionEvidence.resolve(s.actionIntent, s.flows, CONSUMPTION, s.activity) == DRINK) {
List<Flow> normalized = valuationService.normalizeConsumedFlows(s.flows, DRINK, s.now);
s.pricingReview = normalized == null;
if (normalized != null) s.flows = normalized;
}
Transaction cast = settleCast(s);
Transaction result;
if (!sources.isEmpty() && s.flows.isEmpty()) {
result = sources.get(sources.size() - 1);
} else {
Transaction custody = settleCustody(s);
result = custody != null ? custody : book(s);
}
if (additionalBooking != null) {
for (Transaction row : sources) {
if (row != result) additionalBooking.accept(row);
}
if (cast != null) additionalBooking.accept(cast);
}
return result;
}

/**
* A spell cast settling with a pickup (owner 2026-09-28) books its runes as their own Cast, not as
* loose losses on the loot receipt. Only a loss set that is exactly one spell's runes splits off.
*/
Transaction settleCast(Settle s) {
var losses = new ArrayList<Flow>();
for (Flow flow : s.flows) {
if (flow.quantityDelta < 0L) losses.add(flow);
}
if (losses.isEmpty() || losses.size() == s.flows.size() || s.hard || s.soft() || s.localDeath
|| s.dropped || s.destroy || s.asTransfer || s.context == MARKET || Spells.named(losses) == null) {
return null;
}
var slice = new Settle(new Clue(), s.committed, s.now);
slice.flows = losses;
slice.activity = s.activity;
slice.actionIntent = s.actionIntent;
slice.actionLabel = s.actionLabel;
s.flows = new ArrayList<>(s.flows);
s.flows.removeAll(losses);
return book(slice);
}

/** Different loot owners share a measured snapshot, never a source label or encounter. */
List<Transaction> bookLootSources(Settle s) {
if (s.loot.sources.size() <= 1) return emptyList();
var rows = new ArrayList<Transaction>();
for (LootMatch source : s.loot.sources) {
var remaining = new HashMap<Integer, Long>(source.matchedQuantities);
List<Flow> parts = claim(s.flows, flow -> flow.quantityDelta > 0L, (flow, quantity) -> {
long take = min(quantity, remaining.getOrDefault(flow.itemId, 0L));
remaining.computeIfPresent(flow.itemId, (id, wanted) -> wanted - take);
return take;
});
if (parts.isEmpty()) continue;
var slice = new Settle(new Clue(), s.committed, s.now);
slice.offer(source.context, source.note, source.matchedQuantities, source.encounterId, true);
slice.flows = parts;
slice.loot = source;
slice.lootQuantities = source.matchedQuantities;
slice.activity = source.activityName;
slice.settledClaimId = rows.isEmpty() ? s.settledClaimId : null;
rows.add(book(slice));
}
s.loot = LootMatch.NONE;
s.lootQuantities = emptyMap();
s.settledClaimId = null;
if (s.context == LOOT || s.context == PK_LOOT) s.generic();
return rows;
}

/** Commits the stabilized snapshot: its measured deltas, valued, under the pending claim. */
Settle commit(ContainerSnapshot committed, long now) {
var s = new Settle(pending, committed, now);
s.deathEvidence = pendingLocalDeathEvidence;
s.suppressActionEvidence = UNCLASSIFIED_LOCAL_DEATH_NOTE.equals(s.note);
s.localDeath = s.suppressActionEvidence || s.context == PK_DEATH;
if (s.suppressActionEvidence) {
// Keep routine rune/ammo spends distinguishable from local death losses
// without exposing an internal marker in the transaction note.
s.note = "";
}
Map<Integer, Long> deltas = committed.diff(baseline);
baseline = committed;
invalidateChargeBaselinesForCustodyChange(deltas);
if (captureNeutralZoneEntry && neutralZoneEntryBaseline != null) {
if (s.context == Context.TRANSFER && s.note != null && s.note.startsWith(msg("di"))) {
recordNeutralZoneEntryItems(committed.diff(neutralZoneEntryBaseline));
}
neutralZoneEntryBaseline = committed;
}
clearPendingChange();
if (deltas.isEmpty()) {
// Same-tick acquire+consume (pickup then bury) nets to zero vs the prior
// baseline. Recover a named consume when invent qty fell since intent arm.
deltas = sameTickConsumeDeltas(committed);
}
s.flows = deltas.isEmpty() ? new ArrayList<>() : new ArrayList<>(valuationService.value(deltas, now));
if (s.context == GENERIC && !s.transfer && !s.hard) s.flows = withoutChargeStateSwaps(s.flows);
return s.flows.isEmpty() ? null : s;
}

/** A measured death wipe: stacks held at death that left INV stay ownership-neutral. */
void settleDeathWipe(Settle s) {
boolean candidate = deathReclaim.isDeathWipePending() && hasLoss(s.flows);
boolean allowed = candidate && (s.context == Context.TRANSFER && isDeathHeldNote(s.note)
|| s.context == GENERIC && !s.transfer && !s.hard);
if (candidate && !allowed) {
// A stronger market, transfer, production or loot context owns this change.
deathReclaim.cancelDeathWipe();
}
Map<Integer, Long> actionLosses = allowed ? confirmedActionLossQuantities(s.flows, s.hard) : emptyMap();
s.deathWipeLosses = allowed ? deathReclaim.onDeathItemsRemoved(without(s.flows, actionLosses::containsKey, false))
: emptyMap();
if (!actionLosses.isEmpty()) {
// Consume action-attributed quantities from the potential wipe whitelist,
// but leave unrelated held-at-death ids eligible in the same settle.
deathReclaim.excludeActionLosses(actionLosses);
}
s.deathWipe = !s.deathWipeLosses.isEmpty();
s.ambiguousDeathCoinLoss = s.deathWipe && deathReclaim.isReclaimArmed() && hasLoss(s.flows, id -> id == ItemID.COINS)
&& !s.deathWipeLosses.containsKey(ItemID.COINS);
s.localDeath |= s.deathWipe;
if (s.context == Context.TRANSFER && isDeathHeldNote(s.note) && (!s.deathWipe || s.ambiguousDeathCoinLoss)) {
// The short note is not ownership evidence. Without a measured whitelist
// match, let the remaining change follow its actual action/classification.
s.clear();
clearHardTransferEvidence();
}
}

/**
* Key tokens are provenance, not loot value: a credible pickup becomes a separate non-counted
* audit row, and only later contents withdrawals contribute ordinary valued flows.
*/
void settleKeyTokens(Settle s) {
s.keyLosses = lossQuantities(s.flows, KeyChestCatalogue::isCataloguedKey);
if (s.localDeath) {
lossQuantities(s.flows, KeyChestCatalogue::isDeferredClaimKey).forEach(claims::consume);
claims.cancelChest();
}
List<Flow> lootKeys = gains(s.flows, KeyClaims::isLootKeyItem);
Transaction lootKeyAudit = null;
if (!lootKeys.isEmpty()) {
boolean pickup = hasCredibleKeyPickupEvidence(s, s.flows, lootKeys);
s.flows = without(s.flows, KeyClaims::isLootKeyItem, true);
s.expectedLoot = withoutItems(s.expectedLoot, KeyClaims::isLootKeyItem);
lootKeyAudit = pickup ? recordLootKeyAuditRow(lootKeys, s.encounterId, s.now) : null;
}
List<Flow> claimKeys = gains(s.flows, KeyChestCatalogue::isDeferredClaimKey);
boolean claimPickup = !claimKeys.isEmpty() && hasCredibleKeyPickupEvidence(s, s.flows, claimKeys);
s.flows = without(s.flows, KeyChestCatalogue::isDeferredClaimKey, false);
s.expectedLoot = withoutItems(s.expectedLoot, KeyChestCatalogue::isDeferredClaimKey);
Transaction claimAudit = claimPickup && !s.localDeath ? recordDeferredClaimAuditRows(claimKeys, s.now) : null;
s.keyAudit = claimAudit != null ? claimAudit : lootKeyAudit;
if (gainQuantities(s.flows, ANY).isEmpty()) {
// The click plus measured key removal may settle a tick before the
// chest's contents enter INV. Keep only that narrow causal evidence.
claims.matchChest(s.keyLosses, emptyMap(), null);
}
}

/** Held items: keys lost on death, the measured death wipe, key-token losses and charge loads. */
Transaction settleHeldItems(Settle s) {
s.deathEvidenceFlows = s.localDeath ? new ArrayList<>(s.flows) : emptyList();
if (s.localDeath || claims.isAwaitingLocalDeathSettle()) {
claims.recordDeathLosses(s.flows, KeyClaims.keyQuantities(s.committed.quantities));
}
Transaction wipe = s.deathWipeLosses.isEmpty() ? null
: extractDeathWipeTransfer(s.flows, s.deathWipeLosses, s.now, s.deathEvidence);
if (s.deathWipe && (wipe != null || isDeathHeldNote(s.note))) {
// The measured death-owned quantities have their own neutral receipt. Any
// residual item changes in this settle must follow their own evidence.
s.clear();
clearHardTransferEvidence();
}
// Key tokens are deferred provenance on both sides of the lifecycle: their
// inventory loss is never a counted cost. Preserve the measured quantity for
// death annotation, but exclude the token from transaction maths. A same-ID
// loss in a mixed claim settle has no source identity, so it never reduces the
// manifest key quantity on that evidence alone.
s.flows.removeIf(flow -> flow != null && flow.isCost() && KeyClaims.isLootKeyItem(flow.itemId));
// Do not let this narrow menu intent compete with stronger bank/trade/
// transfer evidence or the loot-key lifecycle. A stale load click expires
// once a stronger ownership context is already active.
if (s.hard || s.context == Context.TRANSFER || s.context == MARKET) chargeLoadTransferEvidence.clear();
ChargeLoadTransferEvidence.Partition load = chargeLoadTransferEvidence.partition(s.flows);
Transaction loadReview = null;
if (!load.ambiguousCandidates.isEmpty()) {
s.flows = new ArrayList<>(load.remaining);
loadReview = chargeLoadReviews.recordAmbiguousChargeLoadReview(load, s.now);
}
return firstTransaction(s.keyAudit, wipe, loadReview);
}

/** Source-backed loot, key-chest claims and death reclaim returns. */
Transaction settleReturns(Settle s) {
// Consume source-backed loot before the reclaim partitioner so a kill drop
// sharing an item id with the death wipe remains counted as loot.
s.loot = s.context != Context.TRANSFER && s.context != MARKET ? consumeQueuedLoot(s.flows) : LootMatch.NONE;
s.lootQuantities = s.loot.matchedQuantities;
Map<Integer, Long> contents = s.loot.matched ? gainQuantities(s.flows, ANY)
: minus(gainQuantities(s.flows, ANY), s.lootQuantities);
boolean chestClaimContext = !s.localDeath && !s.hard && !s.soft() && s.context != Context.TRANSFER
&& s.context != MARKET && s.context != PRODUCTION && (s.loot.matched || s.context == GENERIC)
&& hasOnlyGainContentsOrCataloguedKeyCosts(s.flows);
KeyClaims.ChestOpen chestClaim = chestClaimContext && !contents.isEmpty() ? claims.matchChest(s.keyLosses, contents,
s.loot.matched ? s.loot.activityName : null) : null;
if (chestClaim != null && KeyChestCatalogue.isDeferredClaimKey(chestClaim.keyItemId)) {
// Settlement and claim removal happen in this same revision. The settling row
// carries the claim id only when the claim reached zero: a partial consume keeps
// its durable remainder and must not look settled to a reload.
String closed = claims.consume(chestClaim.keyItemId, chestClaim.quantity);
s.settledClaimId = closed != null ? closed : s.settledClaimId;
}
if (chestClaim != null && !s.loot.matched) s.offer(LOOT, chestClaim.chestName, contents, null, true);
boolean deathTransfer = s.context == Context.TRANSFER && isDeathHeldNote(s.note);
boolean canPartitionDeathReturns = (!s.hard || deathTransfer) && s.context != MARKET
&& (s.context != Context.TRANSFER || deathTransfer);
return canPartitionDeathReturns
? settleDeathReclaim(s.flows, s.now, s.lootQuantities, !s.ambiguousDeathCoinLoss) : null;
}

/** A confirmed consume or drop owns its losses; transfer evidence and loot name the rest. */
void settleActions(Settle s) {
s.destroy = consumptionIntent != null && consumptionIntent.destroy;
// Match Drop before consume wipe — consumeMatched used to null dropIntent first.
s.decant = ActionEvidence.isDecant(s.flows);
if (s.soft() && s.decant) {
// A mixed shape while the bank is open has no trustworthy player-action
// source; preserve the accounting classification without a Decanted stamp.
s.suppressActionEvidence = true;
}
s.dropped = dropIntent != null && consumptionIntent == null && onlyCosts(s.flows)
&& !hasLoss(s.flows, id -> id != dropIntent.itemId);
s.consumed = !s.dropped && !s.decant && matchesConsumptionIntent(s.flows, s.hard);
if (s.consumed && consumptionIntent != null) {
// Only a verb whose intent actually matched these flows may name the action; a stale
// or unmatched click never upgrades the wording. Flow-level custody must never steal
// a quantity a named consume/drop intent owns, so its item is captured here.
s.actionIntent = consumptionIntent.actionKind;
s.actionLabel = consumptionIntent.actionLabel;
s.consumedItemId = consumptionIntent.itemId;
}
s.droppedItemId = s.dropped && dropIntent != null ? dropIntent.itemId : -1;
if (!s.consumed && !s.dropped && !s.hard && !s.decant && ActionEvidence.isDoseOrPartialConsumeDelta(s.flows)) {
// Dose leftovers are high-confidence even when the menu/chat intent expired
// before the inventory stabilized (common with Drink + Pick coalescing).
// Soft bank-open latch must not block this — only hard bank-container/menu
// evidence (real deposit/withdraw) may.
s.consumed = true;
}
if (s.consumed || s.dropped) {
// A confirmed Eat/Bury/Drink/Drop wins over stale transfer evidence once its matching
// inventory loss has stabilized. The Dropped/Destroyed stamps survive the loot note below.
s.generic();
s.note = s.dropped ? "Dropped" : s.destroy ? "Destroyed" : "";
}
if (s.consumed) {
consumptionIntent = null;
dropIntent = null;
}
// Soft bank-open alone is a weak signal. Real deposits/withdrawals are one-sided;
// Drink+Pick / Bury+Pick mixed windows must not become ownership-neutral TRANSFER
// (that yields Used/lost ×0 and drops harvest rows when countUncertain is off).
s.asTransfer = !s.consumed && !s.dropped && (s.hard || s.soft() && (onlyCosts(s.flows) || onlyGains(s.flows)));
if (s.asTransfer) {
// Soft evidence while bank open (one-sided), or hard evidence that outlived UI
// close, must settle as ownership-neutral TRANSFER (not Used/lost).
s.context = Context.TRANSFER;
s.note = empty(s.note) ? "Bank transfer" : s.note;
s.encounterId = null;
} else if (s.context == Context.TRANSFER) {
// Stale soft TRANSFER (UI was open) must not reclassify pure inventory
// consumption. Hard bank-container/menu evidence is required.
s.generic();
}
s.activity = activeSession.getActivityHint();
if (s.loot.matched) {
s.context = s.loot.context;
if (!s.dropped && !(s.consumed && s.destroy)) s.note = s.loot.note;
s.activity = s.loot.activityName;
s.encounterId = s.loot.encounterId;
} else if ((s.context == LOOT || s.context == PK_LOOT) && !matchesExpectedLoot(s.flows, s.expectedLoot)) {
s.generic();
} else if (s.context == LOOT) {
s.activity = activityFromNote(s.note);
} else if (s.context == PK_LOOT || s.context == PK_DEATH) {
s.activity = "PKing";
}
}

/**
* Schema-104 flow-level custody: exact Flow quantities proven to be GE principal, returns
* or collections are claimed before any whole-transaction classification. Returns the last
* custody booking when nothing is left; residual quantities keep their own evidence.
*/
Transaction settleCustody(Settle s) {
String sessionId = activeSession.getId();
maintainCustody(s.now);
// Transaction-level owners (transfer/death/loot) block everything; per-item owners (a
// named consume/drop intent or matched loot quantity) block only their own items so the
// remaining quantities can still be claimed exactly.
if (s.pricingReview || s.context == Context.TRANSFER || s.hard || s.asTransfer || s.localDeath
|| s.context == LOOT || s.context == PK_LOOT || s.context == PK_DEATH
|| s.consumed && s.consumedItemId < 0 || s.dropped && s.droppedItemId <= 0) {
return null;
}
var blocked = new HashSet<Integer>(s.loot.matched ? s.loot.matchedQuantities.keySet() : emptySet());
if (s.consumedItemId >= 0) blocked.add(s.consumedItemId);
if (s.droppedItemId > 0) blocked.add(s.droppedItemId);
GeCustodyLedger.Partition custody = geCustody.partition(s.flows, s.now, sessionId,
itemId -> !blocked.contains(itemId), s.now <= geCollectionIntentUntil);
if (custody.countedBookings.isEmpty() && custody.transferBookings.isEmpty()) return null;
// Custody claimed these exact quantities; the matching offer sides are spent so
// stale placement/fill evidence can never own a later unrelated movement.
geCustody.spend(custody.claimedFlows, s.now);
var bookings = new ArrayList<Transaction>(custody.countedBookings);
if (config.keepTransferAuditRows()) bookings.addAll(custody.transferBookings);
bookings.forEach(booking -> add(booking, true));
if (custody.residualFlows.isEmpty()) return custody.lastBooking();
s.flows = new ArrayList<>(custody.residualFlows);
return null;
}

/** Classifies what is left of the change and books it as one receipt. */
Transaction book(Settle s) {
// The offer ledger is item/direction-scoped evidence of a Grand Exchange movement. It may
// only own a settle that no stronger source-specific evidence already owns — matched
// loot/reward, a confirmed consume/drop action, or a loot/death context keeps its own delta.
boolean strongerSourceEvidence = s.loot.matched || s.consumed || s.dropped || s.context == LOOT || s.context == PK_LOOT
|| s.context == PK_DEATH;
// A recent offer may explain a movement no custody lifecycle claimed. Custody is
// authoritative for GE economics, so this is ancillary evidence only: it fails closed to
// an uncounted review row instead of a quote-valued cost or automatic proceeds.
boolean geExplained = !strongerSourceEvidence && s.context != MARKET
&& s.context != Context.TRANSFER && !s.hard && geCustody.explains(s.flows, s.now);
if (geExplained) s.note = "Grand Exchange";
if (geExplained || s.context == MARKET) {
// The movement an offer side explains is the only one it may ever own, whether the
// GE UI context or a recent offer supplied the evidence for this settle.
geCustody.spend(s.flows, s.now);
}
// An explicit GE interaction is source evidence, not settlement authority. Any
// residual flow that custody did not claim must await owner review, even when
// the broad MARKET context would otherwise classify a coins-only TRADE.
boolean geUiUnclaimed = s.context == MARKET && s.note != null && s.note.startsWith("Grand Exchange");
boolean geReview = geExplained || geUiUnclaimed || s.pricingReview;
TransactionType type = classifier.classify(s.context, s.flows);
if (s.consumed && type != TransactionType.TRANSFER && type != TRADE) {
// Dose leftovers / empty vials are mixed inventory deltas; confirmed
// consume intent still books Used/lost rather than uncertain exclusion.
type = CONSUMPTION;
} else if (!s.asTransfer && type == UNCERTAIN && !s.decant
&& (ActionEvidence.isDoseOrPartialConsumeDelta(s.flows) || s.soft() && hasLoss(s.flows))) {
// The classifier still sees mixed value: a dose shape alone is enough once soft
// transfer has been refused, and a refused soft bank latch in a mixed Drink/Pick/Bury
// window books supplies rather than dropping costs and harvest as UNCERTAIN.
type = CONSUMPTION;
s.consumed = true;
} else if (!s.asTransfer && !s.hard && !s.soft() && type == UNCERTAIN && ActionEvidence.all(s.flows, -1,
name -> ActionEvidence.isRuneName(name) || ActionEvidence.isAmmunitionName(name) || ActionEvidence.claim(name))) {
// A pickup settling with a routine spend (a dart thrown, a spell cast) is a gain net of that
// spend, as a loot receipt absorbs a cast; never an uncounted Review (owner 2026-09-28).
// An opened claim (a coin pouch) is income too: its neutral claim leg is not a cost
// (owner 2026-09-29).
type = GAIN;
}
// Drop settle is always CONSUMPTION so registerOwnDrop + pickup recovery work.
if (s.dropped && type != TransactionType.TRANSFER && type != TRADE) type = CONSUMPTION;
if (!s.loot.matched && !geReview && type != TransactionType.TRANSFER && type != TRADE
&& type != CONSUMPTION && type != PK_DEATH_LOSS && type != PK_SUPPLY_COST && type != PK_FEE
&& !s.localDeath && onlyGains(s.flows)) {
Transaction recovery = recoverOwnDrops(s);
if (recovery != null && s.flows.isEmpty()) return recovery;
}
if (type == CONSUMPTION) {
// Clue dig/tele/key spends while a clue path is active.
if (clueCostPairing.isClueCost(s.note)) {
s.note = clueCostPairing.costNote();
if (clueCostPairing.getActiveEncounterId() != null) s.encounterId = clueCostPairing.getActiveEncounterId();
}
// Final presentation stamp after all note mutations.
s.note = s.dropped ? "Dropped" : s.destroy ? "Destroyed" : s.note;
}
// Ancillary offer evidence with no claimable custody lifecycle fails closed to an
// uncounted review row: never quote-valued cost, never automatic proceeds, no basis.
boolean counted = !geReview && type != TransactionType.TRANSFER
&& (type != UNCERTAIN || config.countUncertainMixedChanges());
type = geReview ? UNCERTAIN : type;
boolean retainTransaction = counted || config.keepTransferAuditRows() || type == UNCERTAIN;
// The committed inventory baseline already deduplicates receipts. One encounter may
// legitimately have several pickups; an encounter-wide revenue gate would discard them.
s.activity = type == TransactionType.TRANSFER ? "Transfer" : type == TRADE ? "Market" : s.activity;
ClassificationConfidence confidence = geUiUnclaimed || s.pricingReview ? ClassificationConfidence.UNCERTAIN
: confidenceFor(s.context, type);
// Non-bank transfers explain themselves (minigame wipe, neutral zone, death).
String explanation = s.pricingReview ? msg("fl") : geReview ? msg("gk")
: type == TransactionType.TRANSFER && s.note != null && !s.note.isEmpty() && !s.note.matches(msg("g"))
? "Ownership-neutral transfer: " + s.note + "." : explanationFor(s.context, type, counted);
Transaction transaction = bookTransaction(s.now, type, s.context, s.note, s.activity, counted,
s.flows, confidence, explanation, s.encounterId);
if (s.localDeath && s.deathEvidence != null && hasLoss(s.deathEvidenceFlows)) {
// Evidence enriches the explanation only; the already-final flows,
// classification, valuation and counted state remain untouched.
transaction.rewriteExplanation(s.deathEvidence.appendTo(transaction.getExplanation(), s.deathEvidenceFlows));
}
if (!s.dropped && !s.destroy && !s.suppressActionEvidence) {
// Presentation-only evidence; type/valuation/counted above are unchanged.
transaction.setActionKind(ActionEvidence.resolve(s.actionIntent, s.flows, type, s.activity));
if (transaction.getActionKind() == CAST && s.actionLabel != null) {
transaction.setObservedActionLabel(s.actionLabel);
}
}
boolean droppedCost = s.dropped && type == CONSUMPTION && counted;
if (retainTransaction) {
if (type != TransactionType.TRANSFER && type != TRADE && !s.hard && !s.soft() && !s.localDeath) {
// The relation is written only for a claim that actually closed, so restore's
// settled-id check can never drop a partly consumed remainder.
String closed = claims.settleLootKey(minus(gainQuantities(s.flows, ANY), s.lootQuantities),
lossQuantities(s.flows, KeyClaims::isLootKeyItem));
s.settledClaimId = closed != null ? closed : s.settledClaimId;
}
if (s.settledClaimId != null) transaction.setSourceClaimId(s.settledClaimId);
if (droppedCost) transaction.markOwnDropRecoveryEligible();
add(transaction, false);
if (s.encounterId != null && !s.encounterId.isEmpty()) {
activeSession.attachTransactionToEncounter(transaction.getId(), s.encounterId, false);
}
}
if (droppedCost) registerOwnDrop(transaction, s.flows);
if (s.dropped) dropIntent = null;
return transaction;
}

/** A loot-key manifest claim settled by this change's residual (non-source-backed) gains. */
void observePrimingSnapshot(ContainerSnapshot current) {
primingObservedTicks++;
if (primingSnapshot != null && primingSnapshot.equals(current)) {
primingStableTicks++;
} else {
primingSnapshot = current;
primingStableTicks = 1;
}
int requiredStableTicks = max(2, config.stabilizationTicks() + 1);
int requiredObservedTicks = max(MINIMUM_BASELINE_WARMUP_TICKS, requiredStableTicks);
if (primingObservedTicks >= requiredObservedTicks && primingStableTicks >= requiredStableTicks) {
baseline = current;
baselinePriming = false;
primingSnapshot = null;
primingObservedTicks = 0;
primingStableTicks = 0;
clearPendingChange();
consumeContext();
}
}

void captureActiveContextForPending() {
// Late inventory after bank close: hard bank-container/menu evidence still applies
// even when soft UI context ticks were cleared on close.
if (hardTransferContextActive) latchHardTransferIntoPending();
if (contextTicks <= 0) return;
// Never attach TRANSFER to a new pending change from soft UI-open ticks alone.
// Hard bank-container/menu evidence (or an already-latched hard pending) required.
if (active.context == Context.TRANSFER && !pending.hard && !hardTransferContextActive) {
// Soft open context may exist while bankInterfaceOpen; do not latch it
// into pending - noteSoftOpenTransferForPending handles settle evidence
// only while the UI remains open, and close clears soft-only pending.
return;
}
// Capture the active context once when a pending change begins. Repeated
// snapshot observations must not merge the same expected loot again.
// New loot events still merge through setActiveContext/applyPendingContext.
if (pending.context == GENERIC || active.context.getPriority() > pending.context.getPriority()) {
applyPendingContext(active.context, active.note, active.expectedLoot, active.encounterId);
}
}

void applyPendingContext(Context newContext, String note, Map<Integer, Long> expectedLoot, String encounterId) {
pending.transfer |= newContext == Context.TRANSFER && pending.hard;
pending.offer(newContext, note, expectedLoot, encounterId, false);
}

LootMatch consumeQueuedLoot(List<Flow> flows) {
Map<Integer, Long> available = gainQuantities(flows, ANY);
var matched = new HashMap<Integer, Long>();
var groups = new LinkedHashMap<List<Object>, LootMatch>();
for (Iterator<LootExpectation> iterator = lootExpectations.iterator(); iterator.hasNext();) {
LootExpectation expectation = iterator.next();
Map<Integer, Long> taken = expectation.consumeMatching(available);
taken.forEach((itemId, quantity) -> matched.merge(itemId, quantity, SafeMath::safeAdd));
if (!taken.isEmpty()) {
LootMatch source = groups.computeIfAbsent(Arrays.asList(expectation.getNote(), expectation.getActivityName(),
expectation.getContext(), expectation.getEncounterId()), key -> new LootMatch(true,
expectation.getNote(), expectation.getActivityName(), expectation.getContext(), expectation.getEncounterId(),
new HashMap<>(), emptyList()));
taken.forEach((itemId, quantity) -> source.matchedQuantities.merge(itemId, quantity, SafeMath::safeAdd));
}
if (expectation.isComplete() || expectation.isExpired()) iterator.remove();
}
if (groups.isEmpty()) return LootMatch.NONE;
var sources = new ArrayList<LootMatch>(groups.values());
LootMatch first = sources.get(0);
return new LootMatch(true, first.note, first.activityName, first.context, first.encounterId,
unmodifiableMap(matched), unmodifiableList(sources));
}

String activityFromNote(String note) {
if (blank(note)) return activeSession == null ? "General" : activeSession.getActivityHint();
String trimmed = note.trim();
String prefix = "Loot from ";
return trimmed.startsWith(prefix) && trimmed.length() > prefix.length() ? trimmed.substring(prefix.length()).trim()
: trimmed;
}

boolean matchesExpectedLoot(List<Flow> flows, Map<Integer, Long> expectedLoot) {
Map<Integer, Long> gains = gainQuantities(flows, ANY);
return !gains.isEmpty() && (expectedLoot == null || expectedLoot.isEmpty()
|| gains.equals(positiveEntries(expectedLoot)));
}

Transaction recordLootKeyAuditRow(List<Flow> keyFlows, String encounterId, long now) {
Transaction audit = bookNeutral(now, KeyClaims.LOOT_KEY_NOTE, "PKing", keyFlows, msg("gr"), encounterId);
add(audit, false);
List<Flow> receivedKeys = gains(keyFlows, KeyClaims::isLootKeyItem);
// Claim identity must follow the key item, not incidental flow order. The normal valuator
// sorts by captured value and its equal-value tie order comes from a map; either can vary
// across equivalent observations while the audit row still represents the same keys.
receivedKeys.sort(Comparator.comparingInt((itemData -> itemData.itemId)));
for (int index = 0; index < receivedKeys.size(); index++) {
Flow flow = receivedKeys.get(index);
// One audit row can carry several key types; each independent claim needs its own
// Deterministic identity for multi-key provenance.
String claimId = receivedKeys.size() == 1 ? audit.getId() : audit.getId() + "#" + (index + 1);
claims.open(claimId, flow.itemId, flow.quantityDelta, now);
}
if (encounterId != null && !encounterId.isEmpty()) {
activeSession.attachTransactionToEncounter(audit.getId(), encounterId, false);
}
return audit;
}

Transaction recordDeferredClaimAuditRows(List<Flow> keyFlows, long now) {
Transaction last = null;
for (Flow flow : keyFlows) {
if (flow == null || flow.quantityDelta <= 0L || !KeyChestCatalogue.isDeferredClaimKey(flow.itemId)) {
continue;
}
KeyChestCatalogue.Entry entry = KeyChestCatalogue.entryForKey(flow.itemId);
if (entry == null || entry.isTradeable()) continue;
Transaction audit = bookNeutral(now, "Key held · " + nonBlank(entry.getChestName(), "Chest"), entry.getChestName(), singletonList(flow),
msg("fp"), null);
audit.setActionKind(DEFERRED_CLAIM);
add(audit, false);
claims.open(audit.getId(), flow.itemId, flow.quantityDelta, now);
last = audit;
}
return last;
}

/** Only catalogued key losses and non-key gains: the shape of a chest open. */
static boolean hasOnlyGainContentsOrCataloguedKeyCosts(List<Flow> flows) {
for (Flow flow : flows == null ? Collections.<Flow>emptyList() : flows) {
if (flow != null && flow.quantityDelta != 0L && flow.isCost() != KeyChestCatalogue.isCataloguedKey(flow.itemId)) {
return false;
}
}
return flows != null && !flows.isEmpty();
}

/**
* Credible settled pickup evidence for an untradeable key token. Loot keys and deferred chest
* keys share one rule: the settle must be a source-backed loot/death context or a plain settled
* gain — never transfer, market or drop evidence — and a loot context must account for the
* observed key quantity.
*/
boolean hasCredibleKeyPickupEvidence(Clue applied, List<Flow> originalFlows, List<Flow> keyFlows) {
Context context = applied.context;
if (context == null || context == Context.TRANSFER || context == MARKET || applied.transfer || applied.hard
|| dropIntent != null) {
return false;
}
TransactionType evidenceType = classifier.classify(context, originalFlows);
if (evidenceType != GAIN && evidenceType != TransactionType.LOOT && evidenceType != TransactionType.PK_LOOT) {
return false;
}
Map<Integer, Long> keyQuantities = gainQuantities(keyFlows, ANY);
if (context == PK_LOOT || context == LOOT) {
// The observed source callback must account for the actual key quantity;
// a stale loot context or key-shaped bank flow cannot create provenance.
return covers(applied.expectedLoot, keyQuantities);
}
// A source-less but settled ordinary gain is still a pickup; transfer-like evidence and
// production/uncertain/death mixes are rejected above or by this context check.
return context == GENERIC && evidenceType == GAIN;
}

void resetTrackingState(boolean primeBaseline) {
clearGeCollectionIntent();
chargeLoadTransferEvidence.clear();
chargeEstimateJournal.clear();
measuredChargeReadTracker.reset();
baseline = null;
baselinePriming = primeBaseline;
primingSnapshot = null;
primingObservedTicks = 0;
primingStableTicks = 0;
clearPendingChange();
clearSettledContext();
neutralZoneStoredItems.clear();
neutralZoneTransferActive = false;
captureNeutralZoneEntry = false;
neutralZoneEntryBaseline = null;
neutralZoneEntryCaptureTicks = 0;
neutralZoneRestoreTicks = 0;
lootExpectations.clear();
consumptionIntent = null;
dropIntent = null;
ownDrops.clear();
clueCostPairing.clear();
claims.clearEvidence();
}

void clearPendingChange() {
dirty = false;
pendingSnapshot = null;
pendingStableTicks = 0;
pending.clear();
pendingLocalDeathEvidence = null;
pending.transfer = false;
pending.hard = false;
}

boolean matchesConsumptionIntent(List<Flow> flows, boolean hardTransferEvidence) {
if (consumptionIntent == null || empty(flows)) return false;
// Match the spent stack even when a dose leftover / empty vial appears as a gain.
if (consumptionIntent.itemId >= 0 && hasLoss(flows, id -> id == consumptionIntent.itemId)) return true;
// Open intent (menu item id missing) or id mismatch after canonicalize: still
// confirm Drink/Eat when the delta is a dose/partial leftover, or a pure cost
// (Bury/Cast runes/ammo) so hard TRANSFER cannot swallow the spend.
if (ActionEvidence.isDoseOrPartialConsumeDelta(flows)) return true;
if (onlyCosts(flows)) {
// Named intent for a different item + hard bank deposit must stay TRANSFER
// (stale bury of bones must not book oak-log deposit as Used/lost).
if (hardTransferEvidence && consumptionIntent.itemId >= 0) return false;
return true;
}
// Live potato-field sequence: Drink + Pick + Bury often share one stabilization
// window. Any armed consume intent (named or open) must win so Used/lost books
// instead of UNCERTAIN exclusion — wrong/stale named ids previously skipped
// this path because it required itemId < 0 only.
// Hard bank-container/menu evidence: do not let mixed any-cost swallow a deposit.
if (hardTransferEvidence) return false;
return hasLoss(flows);
}

/**
* Pickup then bury (etc.) in one stabilize window nets to zero vs baseline.
* Named consume intent stores invent qty at arm — synthesize the shortfall loss.
*/
Map<Integer, Long> sameTickConsumeDeltas(ContainerSnapshot current) {
if (consumptionIntent == null || consumptionIntent.itemId < 0 || consumptionIntent.quantityAtArm < 0L
|| current == null) {
return emptyMap();
}
long nowQty = current.quantityOf(consumptionIntent.itemId);
long lost = consumptionIntent.quantityAtArm - nowQty;
if (lost <= 0L) return emptyMap();
var synthetic = new HashMap<Integer, Long>();
synthetic.put(consumptionIntent.itemId, -lost);
return synthetic;
}

void registerOwnDrop(Transaction transaction, List<Flow> flows) {
boolean located = dropIntent != null && dropIntent.hasLocation;
for (Flow flow : flows) {
if (flow != null && flow.isCost()) {
ownDrops.addLast(new OwnDropRecord(transaction.getId(), flow.itemId,
SafeMath.abs(flow.quantityDelta), located, located ? dropIntent.worldX : 0,
located ? dropIntent.worldY : 0, located ? dropIntent.worldPlane : 0, OWN_DROP_TTL_TICKS));
}
}
while (ownDrops.size() > MAX_OWN_DROPS) ownDrops.removeFirst();
}

OwnDropMatch matchOwnDropRecovery(List<Flow> flows) {
if (ownDrops.isEmpty() || flows == null) return null;
// Conservative: single-item pickups only. Mixed stacks are ambiguous.
List<Flow> gains = gains(flows, ANY);
if (gains.size() != 1) return null;
Flow gain = gains.get(0);
OwnDropRecord best = null;
int bestDistance = Integer.MAX_VALUE;
boolean ambiguousUnlocated = false;
for (OwnDropRecord record : ownDrops) {
if (record == null || record.remainingQuantity <= 0L || record.itemId != gain.itemId) continue;
Transaction drop = activeSession.findTransaction(record.transactionId);
if (drop == null || !drop.isOwnDropRecoveryEligible() || !drop.isCounted()
|| drop.getCorrection() != Correction.AUTO) continue;
if (record.hasLocation) {
if (!playerLocationKnown || playerWorldPlane != record.worldPlane) continue;
int distance = max(abs(playerWorldX - record.worldX), abs(playerWorldY - record.worldY));
if (distance > OWN_DROP_MATCH_RADIUS) continue;
boolean exactQty = gain.quantityDelta == record.remainingQuantity;
boolean bestExact = best != null && gain.quantityDelta == best.remainingQuantity;
if (best == null || distance < bestDistance || (distance == bestDistance && exactQty && !bestExact)) {
best = record;
bestDistance = distance;
}
} else {
// Unlocated dumps (no tile at Drop click) must still recover once the
// player location is known — previously only matched when location was
// unknown, so live pickups never reversed those losses.
ambiguousUnlocated |= best != null && !best.hasLocation;
if (best == null) {
best = record;
bestDistance = 0;
}
}
}
if (ambiguousUnlocated || best == null) return null;
long recoverQty = min(gain.quantityDelta, best.remainingQuantity);
if (recoverQty <= 0L) return null;
return new OwnDropMatch(best, recoverQty);
}

boolean applyOwnDropRecovery(OwnDropMatch match, long now) {
if (match == null || match.record == null || activeSession == null) return false;
OwnDropRecord record = match.record;
boolean recovered = activeSession.recoverOwnDropCosts(record.transactionId, record.itemId, match.quantity, now,
"Own-drop recovery");
if (!recovered) return false;
record.remainingQuantity -= match.quantity;
if (record.remainingQuantity <= 0L) ownDrops.remove(record);
return true;
}

/** Reverse only a successfully recovered quantity; other measured gains keep their authority. */
Transaction recoverOwnDrops(Settle s) {
var recovered = new ArrayList<Flow>();
var residual = new ArrayList<Flow>();
for (Flow gain : s.flows) {
Flow left = gain;
while (left != null) {
OwnDropMatch match = matchOwnDropRecovery(singletonList(left));
if (match == null || !applyOwnDropRecovery(match, s.now)) break;
recovered.add(left.part(match.quantity));
left = left.rest(match.quantity);
}
if (left != null) residual.add(left);
}
if (recovered.isEmpty()) return null;
s.flows = residual;
Transaction row = bookTransaction(s.now, TransactionType.TRANSFER, Context.TRANSFER,
"Own-drop recovery", "Drop recovery", false, recovered, ClassificationConfidence.CONFIRMED, msg("gy"), null);
if (config.keepTransferAuditRows()) add(row, false);
return row;
}

void clearOwnDropState() {
dropIntent = null;
ownDrops.clear();
playerLocationKnown = false;
}

void consumeContext() {
active.clear();
contextTicks = 0;
transferEvidenceTicks = 0;
// hardTransferContextActive survives until settle or hardTransferEvidenceTicks expiry
// so bank-container evidence is not lost on the idle GameTick before inventory dirty.
}

void clearSettledContext() {
consumeContext();
clearHardTransferEvidence();
}

void clearHardTransferEvidence() {
hardTransferContextActive = false;
hardTransferEvidenceTicks = 0;
}

ClassificationConfidence confidenceFor(Context appliedContext, TransactionType type) {
if (appliedContext == Context.TRANSFER || appliedContext == MARKET || appliedContext == LOOT
|| appliedContext == PK_LOOT || appliedContext == PK_DEATH) {
return ClassificationConfidence.CONFIRMED;
}
if (type == UNCERTAIN) return ClassificationConfidence.UNCERTAIN;
return ClassificationConfidence.LIKELY;
}

/** Loss ids claimed by exact consume/drop intent are excluded from the death wipe. */
Map<Integer, Long> confirmedActionLossQuantities(List<Flow> flows, boolean hardTransferEvidence) {
var excluded = new HashMap<Integer, Long>();
if (consumptionIntent == null) {
return dropIntent == null ? excluded : lossQuantities(flows, id -> id == dropIntent.itemId);
}
if (consumptionIntent.itemId >= 0) {
return lossQuantities(flows, id -> id == consumptionIntent.itemId);
}
// An open-id intent with only costs cannot distinguish which item was consumed; keep
// every loss in this ambiguous batch counted.
return ActionEvidence.addDoseStepLossQuantities(excluded, flows)
|| !matchesConsumptionIntent(flows, hardTransferEvidence) ? excluded : lossQuantities(flows, ANY);
}

String explanationFor(Context appliedContext, TransactionType type, boolean counted) {
switch (appliedContext) {
case TRANSFER:
return msg("id");
case MARKET:
return counted ? msg("hz") : msg("hr");
case LOOT:
return msg("hu");
case PK_LOOT:
return msg("hx");
case PK_DEATH:
return msg("hv");
case PRODUCTION:
return type == CONSUMPTION ? msg("zy") : msg("hy");
case GENERIC:
default:
if (type == UNCERTAIN) {
return counted ? msg("hs") : msg("ht");
}
if (type == GAIN) return msg("hq");
if (type == CONSUMPTION) return msg("hp");
return msg("m");
}
}

final ChargeLoadReviews chargeLoadReviews = new ChargeLoadReviews(this);
Transaction advanceContext(long now) {
chargeLoadTransferEvidence.tick();
if (dirty || pendingSnapshot != null) return null;
if (contextTicks > 0) contextTicks--;
if (contextTicks <= 0) consumeContext();
if (transferEvidenceTicks > 0) transferEvidenceTicks--;
if (hardTransferEvidenceTicks > 0) {
hardTransferEvidenceTicks--;
if (hardTransferEvidenceTicks <= 0) hardTransferContextActive = false;
} else if (hardTransferContextActive) {
hardTransferContextActive = false;
}
if (neutralZoneRestoreTicks > 0 && --neutralZoneRestoreTicks <= 0) {
neutralZoneRestoreTicks = 0;
neutralZoneStoredItems.clear();
}
if (consumptionIntent != null && consumptionIntent.tick()) consumptionIntent = null;
if (dropIntent != null && dropIntent.tick()) dropIntent = null;
if (!ownDrops.isEmpty()) {
ownDrops.removeIf(record -> record == null || record.tick());
}
lootExpectations.removeIf(expectation -> {
expectation.tick();
return expectation.isExpired() || expectation.isComplete();
});
return null;
}

/** A measured death wipe may own this change: a death-held transfer, or an unclaimed generic change. */
static boolean isDeathHeldNote(String note) {
return note != null && note.startsWith("Death: items held");
}

synchronized SessionMetrics getMetrics(long now) {
if (activeSession == null) return SessionMetrics.none();
return activeSession.metrics(now);
}

/** Correction-aware metrics of one owner; Hub v1 counts every booked row (charter D). */
synchronized Session getActiveSession() {
return activeSession;
}

synchronized Session getGeneralSession() {
return generalSession;
}

synchronized List<Session> getHistory() {
return unmodifiableList(new ArrayList<>(history));
}

synchronized Session getHistorySession(String sessionId) {
return archive.getHistorySession(sessionId);
}

/** Metrics of a retained history session; null when the id is not in history. */
synchronized SessionMetrics getHistoryMetrics(String sessionId, long now) {
return archive.getHistoryMetrics(sessionId, now);
}

synchronized boolean deleteHistorySession(String sessionId) {
return archive.deleteHistorySession(sessionId);
}

/** Clears only GP Manager tracking state; configuration is intentionally outside the engine. */
synchronized void resetTrackingData(long now) {
history.clear();
bumpRevision();
pkHistory.clear();
claims.clear();
chargeLoadReviews.clear();
// A reset is a new install: no session until gameplay (with Automatic tracking) or Start.
generalSession = null;
customSession = null;
generalSuspendedByCustom = false;
activeSession = null;
lootExpectations.clear();
// Destructive reset clears the schema-104 custody lifecycles with the rest.
geCustody.reset();
trackedBasis.reset();
clearGeCollectionIntent();
// Destructive reset clears the pending death/reclaim physical evidence with it.
deathReclaim.reset();
clearOwnDropState();
resetTrackingState(true);
// A reset is a fresh start: resume by itself shortly, if the player does nothing first.
autoStartAtEpochMillis = now + 4_000L;
}

synchronized PkMetrics getPkMetrics() {
return activeSession == null ? PkMetrics.none() : activeSession.pkMetrics();
}

synchronized SavedState createSavedState() {
var state = new SavedState(generalSession, customSession, generalSuspendedByCustom, history);
if (profileIdentityKey != null && !profileIdentityKey.trim().isEmpty())
state.setOwnerKey(profileIdentityKey);
archive.writeTo(state);
state.setProfileTimeZoneId(profileTimeZoneId);
claims.writeTo(state);
state.setPendingDeathReclaim(deathReclaim.snapshot());
pkHistory.writeTo(state);
state.setSavedGrinds(savedGrinds);
// Schema-104 custody continuity travels in the same bounded generation as the canonical
// settlements that depend on it.
geCustody.writeTo(state);
trackedBasis.writeTo(state);
return state;
}

// ---- My Grinds: reusable setup metadata, never financial truth --------------------------
/** Reusable My Grind definitions; setup and default targets only. */
final List<SavedGrind> savedGrinds = new ArrayList<>();
/** All My Grinds; archived ones only when asked for. */
synchronized List<SavedGrind> getSavedGrinds(boolean includeArchived) {
var result = new ArrayList<SavedGrind>();
for (SavedGrind grind : savedGrinds) {
if (!grind.archived || includeArchived) result.add(grind);
}
return result;
}

/** Saved-Grind lookup by stable id; null when unknown. */
synchronized SavedGrind getSavedGrind(String grindId) {
for (SavedGrind grind : savedGrinds) {
if (grind.getGrindId().equals(grindId)) return grind;
}
return null;
}

/**
* Creates a reusable My Grind from reusable setup only. When {@code linkSessionId} names a
* canonical session (active or retained), that one instance is linked as the first member of
* the lineage; other same-name instances are never swept in.
*/
synchronized SavedGrind saveGrind(String name, Long netTargetGp, Long activeTimeMillis,
boolean favorite, String linkSessionId) {
String cleanName = name == null ? "" : name.trim();
if (cleanName.isEmpty()) return null;
var grind = new SavedGrind(UUID.randomUUID().toString(), cleanName, netTargetGp, activeTimeMillis, favorite);
savedGrinds.add(grind);
if (linkSessionId != null && !linkSessionId.isEmpty()) {
Session linked = activeSession != null && linkSessionId.equals(activeSession.getId())
? activeSession : archive.getHistorySession(linkSessionId);
if (linked != null) linked.setGrindId(grind.getGrindId());
}
return grind;
}

/** Updates future reusable defaults; historical instances keep the snapshots they ran with. */
synchronized boolean updateSavedGrind(String grindId, String name, Long netTargetGp,
Long activeTimeMillis, boolean favorite) {
SavedGrind grind = getSavedGrind(grindId);
if (grind == null) return false;
String cleanName = name == null ? "" : name.trim();
if (!cleanName.isEmpty()) grind.setName(cleanName);
grind.setNetTargetGp(netTargetGp);
grind.setActiveTimeTargetMillis(activeTimeMillis);
grind.setFavorite(favorite);
return true;
}

synchronized boolean setSavedGrindArchived(String grindId, boolean archived) {
SavedGrind grind = getSavedGrind(grindId);
if (grind == null) return false;
grind.setArchived(archived);
return true;
}

/** Removes the reusable definition only; canonical history and lineage links survive. */
synchronized boolean deleteSavedGrind(String grindId) {
SavedGrind grind = getSavedGrind(grindId);
if (grind == null) return false;
savedGrinds.remove(grind);
return true;
}

/** Stable-id lineage members, newest first. Never display-name matching. */
synchronized List<Session> linkedSessions(String grindId) {
var result = new ArrayList<Session>();
if (empty(grindId)) return result;
Session active = activeSession;
if (active != null && grindId.equals(active.getGrindId())) result.add(active);
for (Session session : history) {
if (session != null && grindId.equals(session.getGrindId())) result.add(session);
}
return result;
}

/** Stable-id lineage members among completed instances only, newest first. */
synchronized List<Session> completedLinkedSessions(String grindId) {
var result = new ArrayList<Session>();
for (Session session : linkedSessions(grindId)) {
if (session != null && session.isClosed()) result.add(session);
}
return result;
}

/**
* Starts one fresh canonical Grind. Reusable setup travels as metadata and target snapshots;
* no financial state, loot, active time or correction state is copied.
*/
synchronized boolean startGrind(String name, String grindId, Long netTargetGp, Long activeTimeTargetMillis, long now) {
String cleanName = name == null ? "" : name.trim();
if (cleanName.isEmpty()) return false;
startCustomSession(cleanName, SessionMode.GENERAL, now);
Session active = activeSession;
if (active == null) return false;
if (grindId != null && !grindId.isEmpty()) active.setGrindId(grindId);
active.setProfitTargetGp(netTargetGp);
active.setActiveTimeTargetMillis(activeTimeTargetMillis);
return true;
}

/** Renames the running Grind; a change like any other, so the revision advances and it is saved. */
synchronized void renameActive(String name) {
if (activeSession != null) {
activeSession.rename(name);
bumpRevision();
}
}

/** Mid-Grind aim edit: changes the current target snapshot only, never financial truth. */
synchronized boolean setActiveSessionTargets(Long netTargetGp, Long activeTimeTargetMillis, long now) {
if (activeSession == null) return false;
if (netTargetGp == null || netTargetGp <= 0L) activeSession.setProfitTargetGp(null);
else activeSession.setProfitTargetGp(netTargetGp);
if (activeTimeTargetMillis == null || activeTimeTargetMillis <= 0L) {
activeSession.setActiveTimeTargetMillis(null);
} else {
activeSession.setActiveTimeTargetMillis(activeTimeTargetMillis);
}
return true;
}

/** Configured receipt-retention window in days; the canonical detail age horizon. */
int receiptRetentionDays() {
ReceiptRetentionPeriod period = config == null ? null : config.receiptRetentionDays();
return period == null ? ReceiptRetentionPeriod.DAYS_90.getDays() : period.getDays();
}

Session newProfileSession(String name, SessionMode mode, long now, SessionOwnerKind ownerKind) {
var session = new Session(name, now, mode);
session.setOwnerKind(ownerKind);
attachSessionListeners(session);
bumpRevision();
return session;
}

void attachSessionListeners(Session session) {
if (session != null) {
session.setChangeListener(this::bumpRevision);
session.ownedValueObserver = trackedBasis;
}
}

void bumpRevision() {
revision = revision == Long.MAX_VALUE ? 1L : revision + 1L;
}

static boolean validTimeZone(String value) {
if (blank(value)) return false;
try { ZoneId.of(value); return true; }
catch (RuntimeException ex) { return false; }
}

/**
* Per-source evidence: a confirmed Eat/Drink/Bury/Cast action. Decays on its
* own TTL via {@link TimedEvidence} — independent of bank-open/close evidence,
* so a stale or active bank signal can never suppress a confirmed consume.
* See {@code com.gpmanager.engine.evidence} for why evidence lifetimes are
* tracked per-source rather than with one shared flag.
*/
/** One context claim on the next inventory change: what it is, why, which loot, which encounter. */
static class Clue {
Context context = GENERIC;
String note = "";
Map<Integer, Long> expectedLoot = emptyMap();
String encounterId;
/** Bank/deposit evidence during this change; {@code hard} is a real container/menu movement. */
boolean transfer;
boolean hard;
/** Soft evidence: the bank UI was open, but no deposit or withdrawal was confirmed. */
boolean soft() {
return transfer && !hard;
}
/** The claim gives way to ordinary classification; transfer evidence is kept. */
void generic() {
context = GENERIC;
note = "";
expectedLoot = emptyMap();
encounterId = null;
}
void clear() {
generic();
transfer = false;
hard = false;
}
/** A higher-priority (or forced) claim replaces this one; the same context merges its details. */
void offer(Context claim, String claimNote, Map<Integer, Long> loot, String encounter, boolean replace) {
if (replace || claim.getPriority() > context.getPriority()) {
context = claim;
note = orEmpty(claimNote);
expectedLoot = positiveEntries(loot);
encounterId = encounter;
} else if (claim == context) {
note = empty(claimNote) ? note : claimNote;
if (claim == LOOT || claim == PK_LOOT) {
Map<Integer, Long> merged = positiveEntries(loot);
expectedLoot.forEach((itemId, quantity) -> merged.merge(itemId, quantity, SafeMath::safeAdd));
expectedLoot = merged;
}
encounterId = empty(encounter) ? encounterId : encounter;
}
}
}

static class ConsumptionIntent extends TimedEvidence {
final int itemId;
/** Irreversible Destroy menu — presentation Destroyed, never own-drop recovery. */
final boolean destroy;
/**
* Invent qty of {@link #itemId} when intent armed (−1 unknown). Used to recover
* same-tick acquire+consume that nets to zero vs the session baseline.
*/
final long quantityAtArm;
/** Menu verb that armed this intent (B10 presentation evidence); null when unknown. */
ActionKind actionKind;
/** Exact trusted widget text; cleared with the matching short-lived intent. */
ActionLabel actionLabel;
ConsumptionIntent(int itemId, int ticksRemaining, boolean destroy, long quantityAtArm, ActionKind actionKind,
ActionLabel actionLabel) {
super(ticksRemaining);
this.itemId = itemId;
this.destroy = destroy;
this.quantityAtArm = quantityAtArm;
this.actionKind = actionKind;
this.actionLabel = actionLabel;
}
}

/**
* Per-source evidence: a confirmed player-initiated Drop action, held until a
* matching pickup reverses the loss or the TTL lapses. Independent lifetime
* from bank/consume evidence — see {@code com.gpmanager.engine.evidence}.
*/
static class DropIntent extends TimedEvidence {
final int itemId;
final int worldX;
final int worldY;
final int worldPlane;
final boolean hasLocation;
DropIntent(int itemId, int ticksRemaining, int worldX, int worldY, int worldPlane, boolean hasLocation) {
super(ticksRemaining);
this.itemId = itemId;
this.worldX = worldX;
this.worldY = worldY;
this.worldPlane = worldPlane;
this.hasLocation = hasLocation;
}
}

@AllArgsConstructor
static class OwnDropRecord {
final String transactionId;
final int itemId;
long remainingQuantity;
final boolean hasLocation;
final int worldX;
final int worldY;
final int worldPlane;
int ticksRemaining;
boolean tick() {
return --ticksRemaining <= 0;
}
}

@AllArgsConstructor
static class OwnDropMatch {
final OwnDropRecord record;
final long quantity;
}
}
