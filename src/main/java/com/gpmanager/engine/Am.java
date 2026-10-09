package com.gpmanager;
import com.gpmanager.BossRetrievalCatalogue.Service;
import com.gpmanager.Bi.SessionLookup;
import com.gpmanager.Cu.Item;
import com.gpmanager.SavedState.*;
import com.gpmanager.Ar.V;
import java.time.ZoneId;
import java.util.*;
import java.util.UUID;
import java.util.function.Predicate;
import javax.inject.*;
import lombok.*;
import com.google.gson.Gson;
import net.runelite.api.gameval.ItemID;
import static com.gpmanager.Aj.*;
import static com.gpmanager.Aj.LOOT;
import static com.gpmanager.Aj.PK_LOOT;
import static com.gpmanager.Ai.TRANSFER;
import static com.gpmanager.Ag.*;
import static com.gpmanager.Ed.*;
import static java.lang.Math.*;
import static java.util.Collections.*;
import static com.gpmanager.Dv.*;
import static com.gpmanager.Au.*;
import static com.gpmanager.Ai.*;
import static com.gpmanager.Ak.msg;
import static com.gpmanager.Eb.*;
@Singleton
class Am {
static final int MINIMUM_BASELINE_WARMUP_TICKS = 3;
/** After a resume the sidebar/HUD show CALIBRATING this long; bookings stay immediate. */
static final long CALIBRATING_MILLIS = 2_500L;
static final String UNCLASSIFIED_LOCAL_DEATH_NOTE = msg("dd");
FlowValuator valuationService;
TransactionClassifier classifier;
GpManagerConfig config;
LootPresentationFilterService contributionEligibility;
List<Ad> history = new ArrayList<>();
/** One home for pending physical evidence; Ei supplies its owner token. */
Eb eb = new Eb();
SessionArchive archive = new SessionArchive(this);
/** Profile-local date basis; persisted so travelling or changing the host zone cannot shift history. */
String profileTimeZoneId = ZoneId.systemDefault().getId();
/** Domain revision: every canonical mutation advances it; derived previews bind to it. */
long revision;
/** Derived presentation cache; never part of SavedState or an accounting authority. */
transient Sm sm;
synchronized Sm summary() { return Sm.of(this); }

/** Schema-102 profile-level PvP retention: bases, evidence, sequence watermarks. */
PkHistoryArchive pkHistory = new PkHistoryArchive(this);
/** Bounded profile-local point-in-time wealth reads; always excluded from Net. */
/** Active RuneScape profile key supplied by the persistence coordinator. */
String profileIdentityKey = "";
// The general tracker is durable. A custom session is the only temporary
// owner and takes the active pointer while General is explicitly paused.
Ad generalSession;
Ad customSession;
boolean generalSuspendedByCustom;
/** A factory reset arms a fresh start a few seconds later, if nobody starts tracking first. */
long autoStartAtEpochMillis;
Ad activeSession;
Cc baseline;
boolean baselinePriming;
/** Epoch millis the post-resume calibration cue clears at; 0 when none is armed. */
long calibratingUntil;
Cc primingSnapshot;
int primingObservedTicks;
int primingStableTicks;
boolean dirty;
Cc pendingSnapshot;
int pendingStableTicks;
/** The live context claim and, while an inventory change stabilizes, the one it will settle with. */
/** One-shot display-only snapshot captured at the local death event. */
/*
* --- Per-source bank-transfer evidence lifetimes ---
*
* Fixes the documented P1: bone burial (or any other consumption) that
* settles after the bank UI has closed must classify as CONSUMPTION, not
* TRANSFER. Two distinct evidence sources are tracked below, each with
* its own lifetime, precisely because they mean different things:
*
*   - SOFT evidence (`transferEvidenceTicks` / `bo()`) only
*     means "the bank widget happened to be visible this tick." It is a
*     weak, one-sided signal and is dropped immediately on bank close
*     (see `yu`) so it can never outlive the UI and
*     misclassify an unrelated burial/eat/drink/cast that settles after
*     close.
*   - HARD evidence (`hf()` / `hardTransferEvidenceTicks`)
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
* and idle pw so a GameTick between bank-container and inventory
* does not book the deposit as CONSUMPTION. Cleared on settle or TTL expiry.
*/
/** Remaining ticks for {@link #hf()}. */
/**
* Fresh bank-interface / deposit-menu evidence (not sticky loot correlation).
* Soft-only: cleared unconditionally on bank close unless hard evidence is
* also live (see {@link #yu()}), so it never survives
* past the UI closing to poison a later burial/consumption.
*/
DeathReclaimLifecycle deathReclaim = new DeathReclaimLifecycle();
/** Item-on-item charge-load intent; only matched settled component losses are neutral. */
/** Ad-owned measured balances; never persisted or shared across owners. */
MeasuredChargeReadTracker measuredChargeReadTracker = new MeasuredChargeReadTracker();
ChargeEstimateJournal chargeEstimateJournal = new ChargeEstimateJournal();
Map<Integer, Long> neutralZoneStoredItems = new HashMap<>();
boolean neutralZoneTransferActive;
boolean captureNeutralZoneEntry;
Cc neutralZoneEntryBaseline;
int neutralZoneEntryCaptureTicks;
int neutralZoneRestoreTicks;
/**
* Items your own NPC or PvP loot dropped, by ticks left: 100 ticks, the minute loot stays private
* to you, outliving the 30 s attribution window (owner 1.2).
*/
/** Ammo fired in the running session, newest last: picking it back up recovers it (owner 1.1). */
/** Item ids a ground Take was clicked for, with the ticks the click stays evidence. */
static final int MAX_OWN_DROPS = 32;
/** One counted LOOT/PK_LOOT revenue per encounter id. */
/** Active clue path for dig/tele/key cost pairing. */
ClueCostPairing clueCostPairing = new ClueCostPairing();
/** Key/chest claims (the one durable evidence exception, section H) and their live evidence. */
Da claims = new Da();
/** Chebyshev tiles — pickup must be near the recorded drop, not name-only. */
static final int OWN_DROP_MATCH_RADIUS = 8;
static final int OWN_DROP_TTL_TICKS = 200;
int playerWorldX;
int playerWorldY;
int playerWorldPlane;
boolean playerLocationKnown;
@Inject
Am(Bl /* valuationService */a, TransactionClassifier /* classifier */b, GpManagerConfig /* config */c) {
 this((FlowValuator) a, b, c);
}

Am(FlowValuator /* valuationService */a, TransactionClassifier /* classifier */b, GpManagerConfig /* config */c) {
 this.valuationService = a;
 this.classifier = b;
 this.config = c;
 eb.reset = this::eo;
 geCustody.setBasisLedger(trackedBasis);
 trackedBasis.openReservationLookup = geCustody::yh;
}

@Inject
void setContributionEligibility(LootPresentationFilterService /* contributionEligibility */a) {
 this.contributionEligibility = a;
}

synchronized void restore(SavedState /* state */a) {
 restore(a, Ae.now());
}

/** Restore using a supplied clock so retention and recovery rules are deterministic in tests. */
synchronized void restore(SavedState /* state */b, long /* now */c) {
 history.clear();
 archive.clear();
 savedGrinds.clear();
 profileTimeZoneId = b != null && aks(b.getProfileTimeZoneId()) ? b.getProfileTimeZoneId() : ZoneId.systemDefault().getId();
 nc();
 claims.clear();
 chargeLoadReviews.clear();
 // Known-basis coverage restores before any session can notify the observer; a pre-106
 // state starts with no known coverage (existing holdings are unknown, never zero).
 trackedBasis.restore(b, b == null ? null : b.getGeCustody(), c);
 generalSession = null;
 customSession = null;
 generalSuspendedByCustom = false;
 activeSession = null;
 os();
 if (b != null) {
  for (Ap /* grind */d : b.getSavedGrinds()) {
   if (d != null && !d.getGrindId().isEmpty() && !d.getName().isEmpty()) savedGrinds.add(d);
  }
  pkHistory.restore(b);
  archive.restore(b, c);
  for (Ad /* session */e : b.getHistory()) {
   if (e != null) history.add(e);
  }
  generalSession = b.generalSession;
  customSession = b.customSession;
  if (generalSession != null) generalSession.setOwnerKind(Bt.FREE_PLAY);
  if (customSession != null) customSession.setOwnerKind(Bt.NAMED_SESSION);
  generalSuspendedByCustom = b.generalSuspendedByCustom;
  for (Ad /* session */f : akf()) lk(f);
  activeSession = customSession != null && !customSession.isClosed() ? customSession : generalSession;
  if (activeSession != null && !activeSession.isClosed()) {
   activeSession.zg();
   if (!activeSession.paused) {
    long /* savedAt */g = b.savedAtEpochMillis;
    activeSession.pause(g > 0L ? min(c, g) : c, RECOVERY);
   } else {
    activeSession.pauseReason = RECOVERY;
   }
  }
 }
 // Reclaim evidence is ephemeral and belongs to the identity being replaced;
 // never carry one account's lost-item whitelist into another account's rows.
 deathReclaim.reset();
 // A restart of the same owner restores its persisted pending death/reclaim evidence.
 deathReclaim.restore(b == null ? null : b.pendingDeathReclaim);
 // Custody lifecycles are profile-scoped financial continuity state.
 geCustody.restore(b, c);
 agb(false);
 archive.akm();
 if (b != null) {
  // Claims settled by a retained transaction are stale duplicates and must never book again.
  claims.restore(b, akf());
  archive.pr(c);
  pkHistory.ri(akf(), receiptRetentionDays(), c);
 }
}

synchronized void rm(long /* now */a) {
 if (generalSession == null || generalSession.isClosed()) {
  // Overall is the durable default owner display name; it must still
  // accept automatic activity detection just as the former General default
  // session did.
  generalSession = aaq(Ad.DURABLE_OWNER_NAME, Cx.AUTO, a, Bt.FREE_PLAY);
 }
 if (customSession == null || customSession.isClosed()) {
  customSession = null;
  activeSession = generalSession;
 }
}

synchronized void ajl(String /* name */a, Cx /* mode */b, long /* now */c) {
 rm(c);
 if (customSession != null) {
  // This transition needs an explicit Finish custom first. Starting
  // another custom session must never silently archive the current one.
  return;
 }
 ol();
 if (!generalSession.paused) {
  generalSession.pause(c, CUSTOM_SESSION);
  generalSuspendedByCustom = true;
 } else {
  generalSuspendedByCustom = false;
 }
 customSession = aaq(a, b == null || b == Cx.GENERAL
 ? Cx.AUTO : b, c, Bt.NAMED_SESSION);
 activeSession = customSession;
 agb(true);
}

synchronized boolean sx(long /* now */a) {
 if (customSession == null) return false;
 SessionEndReason /* reason */b = customSession.endReason;
 return sy(a, a, b == null ? SessionEndReason.MANUAL : b, false);
}

boolean sy(long /* endAt */a, long /* resumeGeneralAt */b, SessionEndReason /* reason */c, boolean /* autoEnded */d) {
 if (customSession == null) return false;
 long /* art */e = max(customSession.startedAtEpochMillis, a);
 ol();
 customSession.setEndReason(c);
 customSession.close(e);
 history.add(0, customSession);
 archive.akm();
 customSession = null;
 activeSession = generalSession;
 if (generalSession != null && generalSession.getPauseReason() == CUSTOM_SESSION) {
  generalSession.resume(max(e, b));
 }
 generalSuspendedByCustom = false;
 agb(true);
 return true;
}

synchronized boolean wb() {
 return customSession != null && activeSession == customSession && !customSession.isClosed();
}

synchronized void acu(long /* now */a) {
 if (live()) activeSession.pause(a, LIFECYCLE);
 agb(false);
}

/** Resumes an automatic pause of one of these reasons; Manual Pause always needs the owner. */
synchronized boolean resume(long /* now */a, Ed... /* reasons */b) {
 if (activeSession != null && activeSession.paused && Arrays.asList(b).contains(activeSession.getPauseReason())) {
  activeSession.resume(a);
  arm(a);
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
synchronized boolean ait(long /* now */a) {
 if (!config.autoStartSession()) return false;
 if (customSession != null && !customSession.isClosed()) return false;
 if (generalSession != null && !generalSession.isClosed()) return false;
 rm(a);
 arm(a);
 return true;
}

/** Fresh tracking: clear the evidence and calibrate the rate from now. */
void arm(long /* now */a) {
 agb(true);
 calibratingUntil = a + CALIBRATING_MILLIS;
}

synchronized boolean yp() {
 return activeSession != null && activeSession.getPauseReason() == IDLE;
}

synchronized void setDetectedActivity(String /* activity */a, long /* now */b) {
 if (activeSession != null && activeSession.getMode() == Cx.AUTO) {
  activeSession.setActivityHint(a, b);
 }
}

synchronized void togglePause(long /* now */b) {
 if (activeSession == null) {
  // Start tracking: a new install, or after a factory reset, with Automatic tracking off.
  rm(b);
  arm(b);
 } else if (activeSession.paused) {
  activeSession.resume(b);
  arm(b);
 } else {
  activeSession.pause(b, MANUAL);
  autoStartAtEpochMillis = 0L;
  agb(false);
 }
}

/** A factory reset resumes tracking by itself a few seconds later, unless stopped first. */
synchronized void tickAutoStart(long /* now */a) {
 if (autoStartAtEpochMillis <= 0L || a < autoStartAtEpochMillis) return;
 autoStartAtEpochMillis = 0L;
 if (activeSession == null) {
  rm(a);
  agb(true);
 }
}

/** Seconds until the pending factory-reset resume fires; 0 when none is armed. */
synchronized long autoResumeSeconds(long /* now */a) {
 long /* left */b = autoStartAtEpochMillis - a;
 return b <= 0L ? 0L : (b + 999L) / 1000L;
}

/** True while the post-resume calibration cue is up; bookings are immediate and never wait. */
synchronized boolean calibrating(long /* now */a) {
 return a < calibratingUntil;
}

/**
* Compacts closed sessions strictly older than {@code days}; a zero window
* means forever and performs no compaction.
*
* @return receipt rows compacted
*/
synchronized int pg(int /* days */a, long /* now */b) {
 return archive.pg(a, b);
}

/** Configured UTC retention maintenance used by the game-tick lifecycle. */
synchronized int yt(long /* now */a) {
 return archive.yt(a);
}

List<Ad> akf() {
 var /* unique */a = new LinkedHashMap<String, Ad>();
 for (Ad /* session */b : history) kh(a, b);
 kh(a, generalSession);
 kh(a, customSession);
 kh(a, activeSession);
 return new ArrayList<>(a.values());
}

static void kh(Map<String, Ad> /* unique */a, Ad /* session */b) {
 if (b != null) a.putIfAbsent(b.getId(), b);
}

/**
* Starts a login/session warm-up. Snapshots observed during this period are
* baseline material only and can never become profit.
*/
synchronized void lp() {
 agb(true);
}

/**
* Sets an immediate trusted baseline. Primarily useful for deterministic
* tests and callers which already know all tracked containers are loaded.
*/
synchronized void setBaseline(Cc /* snapshot */a) {
 baseline = a;
 baselinePriming = false;
 primingSnapshot = null;
 primingObservedTicks = 0;
 primingStableTicks = 0;
 ow();
}

synchronized void yz() {
 if (baselinePriming) return;
 dirty = true;
 pendingStableTicks = 0;
 // Animation lag: refresh consume/drop TTLs when the inventory finally moves so
 // bury/drink intents survive until stabilization rather than expiring idle.
 eb.refresh(CN, 4);
 eb.refresh(DO, max(4, config.stabilizationTicks() + 2));
 nn();
 if (bo() && (dirty || pendingSnapshot != null)) {
  pc().transfer = true;
  if (pc().context == GENERIC) {
   pc().context = Aj.TRANSFER;
   pc().note = "Bank transfer";
  }
 }
}

/** Owner-scoped live and stabilizing claims; HF and BO are independent sources. */
Clue ac() { return eb.clue(CX); }
Clue pc() { return eb.clue(CP); }
int ct() { return eb.left(CT); }
boolean hf() { return eb.peek(HF) != null; }
boolean bo() { return eb.peek(BO) != null; }

/** The still-open consume command, from the sole pending-evidence store. */
Ev ci() { return eb.peek(CN); }

/**
* Records a short-lived consume hint; accounting still waits for a stable inventory delta.
* {@code itemId} may be {@code < 0} (open intent) when the menu did not expose an id —
* matching then accepts a dose/partial leftover, a pure cost, or any inventory cost
* while intent is armed so drink/bury coalesced with harvest gains still books Used/lost.
*/
synchronized void ci(int /* itemId */a, int /* ticks */b) {
 ci(a, b, false);
}

/**
* @param destroy when true, irreversible Destroy menu — presentation stamps Destroyed;
*                never registers recoverable own-drop.
*/
synchronized void ci(int /* itemId */a, int /* ticks */b, boolean /* destroy */c) {
 ci(a, b, c, null);
}

/**
* @param actionKind menu verb behind the click (Drink/Eat/Bury/…) or null when the
*                   option does not evidence a specific action. Presentation only.
*/
synchronized void ci(int /* itemId */a, int /* ticks */b, boolean /* destroy */c, Au /* actionKind */d) {
 ci(a, b, c, d, null);
}

/** Exact spell text is an optional presentation hint; it is not part of action matching. */
synchronized void ci(int /* itemId */b, int /* ticks */c, boolean /* destroy */d, Au /* actionKind */f,
Bb /* actionLabel */g) {
 // Eat/Bury/Drink/Cast/etc. — clear any pending Drop intent for the same click window.
 eb.drop(DO);
 int /* safeTicks */h = max(1, c);
 if (ci() != null && b < 0 && ci().item >= 0) {
  // Chat/menu reinforcement without an id must not wipe a resolved item id,
  // flip a Destroy stamp to Used, or replace the resolved item's proven verb.
  // Exact spell text may refresh the label; a click that proves no name must not
  // erase the name already proven for this bounded interaction.
  eb.refresh(CN, h);
  if (g != null) ci().label = g;
  return;
 }
 Ev e = eb.put(CN, b, 1L, h - 1, null);
 e.held = adq(b);
 e.flag = d;
 e.action = f;
 e.label = g;
}

/**
* Clears only pending, unbooked action evidence at the profile/owner fence. Booked schema-105
* action labels are durable metadata on canonical transactions and are never erased here;
* lifecycle paths also reset pending consume evidence through
* {@link #agb(boolean)}.
*/
synchronized void ov() {
 eb.drop(CN);
}

/**
* Invent qty for a named consume arm. Prefer the pending (dirty) snapshot so a
* mid-window pickup is visible before bury/drink settles.
*/
long adq(int /* itemId */a) {
 if (a < 0) return -1L;
 Cc /* live */b = pendingSnapshot != null ? pendingSnapshot : baseline;
 return b == null ? -1L : b.aea(a);
}

/**
* Extends an existing consume intent TTL, or arms an open intent when chat confirms
* Eat/Bury/Drink after the menu click failed to expose an item id.
* Preserves a pending Destroy stamp; never invents destroy from chat alone.
*/
synchronized void aew(int /* ticks */a) {
 int /* safeTicks */b = max(1, a);
 if (ci() != null) {
  eb.refresh(CN, b);
  return;
 }
 ci(-1, b, false);
}

/**
* Player-initiated Drop action. Confirmed inventory removal books Used/lost and
* registers a recoverable own-drop record. Matching pickups reverse that loss.
*/
synchronized void abz(int /* itemId */a, int /* ticks */b, int /* worldX */c, int /* worldY */d, int /* worldPlane */f, boolean /* hasLocation */g) {
 if (a < 0) return;
 eb.drop(CN);
 Ev e = eb.put(DO, a, 1L, max(1, b) - 1, null);
 e.x = c;
 e.y = d;
 e.plane = g ? f : -1;
}

/** A ground-item Take: for 50 ticks a gain of that item may be fired ammo coming back. */
/** Wealth line (owner 1.2): the bank's total when last seen, when, and what is carried now. */
long bankValue;
long bankAt;
long carriedValue;

synchronized void bank(long /* value */a, long /* at */b) {
 bankValue = a;
 bankAt = b;
}

synchronized void carried(long /* value */a) {
 carriedValue = a;
}

synchronized void noteTake(int /* itemId */a) {
 if (a > 0) eb.set(TA, a, 1L, 49, null);
}

/**
* A pickup that went straight into an open bag reads as an inventory gain (owner 1.2): the
* baseline forgets it, so the next settle books it like any pickup, own-drop recovery included.
*/
/** Money evidence is owner-checked and consumed under the booking monitor. */
synchronized void bagged(Ev e, long /* qty */a) {
 if (e.owner == eb.owner) bagged(e.item, eb.take(e.kind, e.item, a, v -> v == e));
}
synchronized void fee(Ev e, long /* now */a) {
 if (e.owner == eb.owner) ml(eb.take(Eb.FE, -1, e.qty, v -> v == e), e.note, a, false);
}
synchronized void bagged(int /* itemId */a, long /* quantity */b) {
 if (baseline == null || baselinePriming || b <= 0L) return;
 var /* held */c = new HashMap<>(baseline.quantities);
 c.merge(a, -b, Long::sum);
 baseline = new Cc(c);
 yz();
}

/** Latest local-player tile for conservative own-drop recovery matching; never a PvP place fact. */
synchronized void aki(int /* worldX */a, int /* worldY */b, int /* worldPlane */c) {
 playerWorldX = a;
 playerWorldY = b;
 playerWorldPlane = c;
 playerLocationKnown = true;
}

/**
* Clears the export identity when persistence invalidates the active account.
*/
synchronized void oz() {
 profileIdentityKey = "";
}

/** Binds the identity and restores its state under the same engine lock. */
synchronized void agl(String /* identityKey */a, SavedState /* state */b, long /* now */c) {
 String /* next */d = a == null ? "" : a.trim();
 SavedState /* detached */e = rc(b);
 String /* ana */f = e == null ? null : e.ownerKey;
 if (!d.isEmpty() && f != null && !f.trim().isEmpty() && !d.equals(f)) {
  throw new IllegalArgumentException(msg("ia"));
 }
 Am /* staged */g = adg(d, e, c);
 vg(g, d);
 // The previous account's carried wealth is not this one's; ve() samples it again (Codex review 1.2).
 carriedValue = 0L;
}

/** The bound profile's state as saved, for a backup copy of the save file. */
synchronized SavedState sl() {
 SavedState /* snapshot */a = qm();
 a.setOwnerKey(profileIdentityKey);
 return a;
}

Am adg(String /* identityKey */a, SavedState /* state */b, long /* now */c) {
 Am /* staged */d = abf();
 d.profileIdentityKey = a;
 d.restore(b, c);
 return d;
}

/** An isolated engine sharing this engine's valuation/classifier/config; used to stage restores and migrations. */
Am abf() {
 var /* staged */a = new Am(valuationService, classifier, config);
 a.setContributionEligibility(contributionEligibility);
 return a;
}

static SavedState rc(SavedState /* state */a) {
 if (a == null) return null;
 Gson /* gson */b = JsonCodec.gson();
 SavedState /* detached */c = b.fromJson(b.toJson(a), SavedState.class);
 if (c != null) c.aar();
 return c;
}

void vg(Am /* staged */a, String /* identityKey */b) {
 // Allocate copies and attach listeners before changing live fields. The
 // normal restore/migration path has already completed on the isolated engine.
 var /* ass */c = new ArrayList<Ad>(a.history);
 var /* ast */d = new ArrayList<Ad>(a.akf());
 for (Ad /* session */e : d) lk(e);
 // Clear identity-scoped transient evidence while durable profile fields
 // still belong to the previous owner. No reader can observe the swap
 // because agl/restoreProfile hold this engine's monitor.
 os();
 deathReclaim.reset();
 chargeLoadReviews.clear();
 ol();
 agb(false);
 history = c;
 savedGrinds.clear();
 savedGrinds.addAll(a.savedGrinds);
 archive.auc(a.archive);
 profileTimeZoneId = a.profileTimeZoneId;
 nc();
 claims.auc(a.claims);
 deathReclaim.auc(a.deathReclaim);
 pkHistory.auc(a.pkHistory);
 geCustody.auc(a.geCustody);
 trackedBasis.auc(a.trackedBasis);
 generalSession = a.generalSession;
 customSession = a.customSession;
 generalSuspendedByCustom = a.generalSuspendedByCustom;
 activeSession = a.activeSession;
 profileIdentityKey = axw(b);
}

/**
* Pauses for Idle. The AFK timeout window itself still counts as active time; only the time
* after the pause fires is excluded, so the active clock never shrinks retroactively
* (owner 2026-10-01). {@code idleStartedAt} stays in the signature for the caller and tests.
*/
synchronized void adh(long /* now */a, long /* idleStartedAt */b) {
 if (live()) {
  activeSession.pause(a, IDLE);
  agb(false);
 }
}

/**
* Ends a named Grind that AFK pause has held for {@code minutes} (0 = off; owner 1.2). It closes
* when the pause began, like pressing End then; Free play takes over and is never auto-ended.
*/
synchronized boolean idleEnd(long /* now */a, int /* minutes */b) {
 if (b <= 0 || customSession == null || activeSession != customSession
 || customSession.getPauseReason() != IDLE) {
  return false;
 }
 long /* pausedAt */c = customSession.pausedAtEpochMillis;
 return a - c >= b * 60_000L && sy(c, a, SessionEndReason.IDLE, true);
}

synchronized void oy() {
 playerLocationKnown = false;
}

synchronized void markContext(Aj /* newContext */a, int /* ticks */b, String /* note */c) {
 if (a != LOOT && a != PK_LOOT) claims.nk();
 if (a == Aj.TRANSFER || a == MARKET) eb.drop(CL);
 aiz(a, c);
 if (a == Aj.TRANSFER) {
  // Stale Eat/Bury intents must not reclassify bank deposits as Used/lost.
  eb.drop(CN, DO);
  abi(max(1, b));
 }
 aho(a, b, c, emptyMap(), null);
}

/**
* Bank interface is open on this tick. Soft UI-open refresh only — does not
* by itself prove a deposit/withdraw movement.
*/
synchronized void ze(int /* ticks */a) {
 claims.nk();
 eb.drop(CL);
 aiz(Aj.TRANSFER, "Bank transfer");
 // Edge-trigger only. Live GameTick refreshes this every tick while the bank
 // widget/container is open — wiping Eat/Drink/Bury intents each tick made
 // potato-field sessions book every inventory delta as soft TRANSFER (Used/lost
 // ×0, no harvest rows) whenever bank detection stuck or fluttered.
 boolean /* asr */b = !bo();
 eb.ttl(BO, Integer.MAX_VALUE);
 if (b) {
  eb.drop(CN, DO);
 }
 eb.ttl(SF, max(eb.left(SF), max(1, a)));
 aho(Aj.TRANSFER, a, "Bank transfer", emptyMap(), null);
}

/**
* Bank interface closed this tick. Clears sticky soft TRANSFER hints so burial
* after close is not classified as TRANSFER when the inventory callback raced
* ahead of this tick. Genuine deposits keep pending TRANSFER only when hard
* bank-container/menu evidence was attached to this pending change — or when
* hard evidence is still live and the inventory callback arrives after close.
*/
synchronized void yu() {
 eb.drop(BO);
 // Soft UI-open refresh only. Hard deposit/withdraw TTL must survive close so a
 // GameTick between bank-container evidence and inventory dirty stays TRANSFER.
 if (!hf()) eb.drop(SF);
 if (dirty || pendingSnapshot != null) {
  if (!pc().hard && !hf()) {
   // Soft UI-open latch only — clear so burial after close is CONSUMPTION.
   // Genuine deposits attach hard evidence via bank-container/menu markContext.
   pc().transfer = false;
   if (pc().context == Aj.TRANSFER) pc().clear();
  } else if (hf() && !pc().hard) {
   // Inventory already dirty from soft open; promote with surviving hard evidence.
   yd();
  }
  return;
 }
 if (ac().context == Aj.TRANSFER) {
  // Drop the soft UI-open note/ticks; live hard evidence keeps TRANSFER for a late
  // inventory callback.
  ac().clear();
  ac().context = hf() ? Aj.TRANSFER : GENERIC;
  eb.drop(CT);
 }
}

void abi(int /* ticks */a) {
 eb.drop(CL);
 int /* safeTicks */b = max(1, a);
 eb.ttl(SF, max(eb.left(SF), b));
 eb.ttl(HF, max(eb.left(HF), b));
 if (dirty || pendingSnapshot != null) yd();
}

void yd() {
 pc().transfer = true;
 pc().hard = true;
 if (pc().context == GENERIC || Aj.TRANSFER.priority > pc().context.priority) {
  // Keep the producer's own note (minigame wipe, neutral zone, death) — a
  // generic "Bank transfer" would otherwise win the priority race and mislabel
  // every non-bank transfer row.
  String /* note */b = ac().context == Aj.TRANSFER && ac().note != null && !ac().note.trim().isEmpty()
  ? ac().note : "Bank transfer";
  kw(Aj.TRANSFER, b, emptyMap(), null);
 } else if (pc().context == Aj.TRANSFER) {
  pc().transfer = true;
  if (empty(pc().note)) pc().note = "Bank transfer";
 }
}

/**
* Soft evidence: inventory became dirty while the bank UI was open. Survives
* bank close for genuine deposits. Cleared by {@link #yu()}
* when the pending change was only a stale latch after the UI was already gone
* (plugin closes before dirty when {@code !isBankOpen()}).
*/
synchronized void zk(Map<Integer, Long> /* expectedLoot */a, int /* ticks */b, String /* note */c, String /* activityName */d) {
 Map<Integer, Long> /* safeExpectedLoot */e = positiveEntries(a);
 if (!e.isEmpty()) {
  loot(e, b, c, d, LOOT, null);
 }
 aho(LOOT, b, c, e, null);
}

/** Each private drop proves its own remaining quantity; attribution has the old shorter window. */
void loot(Map<Integer, Long> /* quantities */a, int /* ticks */b, String /* note */c, String /* name */d, Aj /* context */f, String /* id */g) {
 a.forEach((/* item */h, /* qty */i) -> {
  Ev e = eb.add(LT, h, i, 99, axw(c));
  e.at = eb.now + max(1, b) - 1;
  e.name = awq(d, "General");
  e.context = f;
  e.id = g;
 });
}

void aho(Aj /* newContext */a, int /* ticks */b, String /* note */c, Map<Integer, Long> /* expectedLoot */d, String /* encounterId */e) {
 Aj /* safeContext */f = a == null ? GENERIC : a;
 Map<Integer, Long> /* safeExpectedLoot */g = positiveEntries(d);
 aiz(f, c);
 boolean /* replace */h = ct() <= 0 || f.priority > ac().context.priority;
 if (h || f == ac().context) eb.ttl(CT, max(h ? 0 : ct(), max(1, b)));
 ac().offer(f, c, g, e, h);
 if (dirty || pendingSnapshot != null) kw(f, c, g, e);
}

/**
* A death marker is a short-lived reconciliation hint, not a durable activity
* context. An explicit later activity must own the next measured change even though
* TRANSFER has a higher general context priority.
*/
void aiz(Aj /* safeContext */a, String /* note */b) {
 boolean /* asq */c = a == Aj.TRANSFER && wd(b);
 boolean /* anf */d = ac().context == Aj.TRANSFER && wd(ac().note);
 boolean /* apm */e = pc().context == Aj.TRANSFER && wd(pc().note);
 if (!c && a != GENERIC && (d || e)) {
  if (d) {
   ac().clear();
   eb.drop(CT);
  }
  if (e) pc().clear();
  eb.drop(SF);
  om();
  deathReclaim.nl();
 }
}

synchronized void zn(Map<Integer, Long> /* expectedLoot */a, int /* ticks */b, String /* label */c, long /* now */d) {
 if (activeSession == null && !ait(d)) return;
 if (!live()) return;
 Map<Integer, Long> /* safeExpectedLoot */e = positiveEntries(a);
 Bx /* encounter */f = activeSession.ke(Be.KILL, d, c, Bd.CONFIRMED,
 msg("ib"));
 pkHistory.aeu(f);
 if (activeSession.getMode() != Cx.GENERAL) {
  activeSession.lj(f.getId(), d, 90_000L);
 }
 String /* note */g = "PK loot";
 if (!e.isEmpty()) {
  loot(e, b, g, "PKing", PK_LOOT, f.getId());
 }
 aho(PK_LOOT, b, g, e, f.getId());
}

/**
* Local unsafe PvM death with a separate canonical inventory/equipment ownership
* snapshot. The snapshot is reconciled against measured negative flows; presentation
* evidence remains explanation-only.
*/
synchronized void zi(int /* ticks */a, Map<Integer, Long> /* heldItemsAtDeath */b) {
 markContext(Aj.TRANSFER, max(1, a), msg("fw"));
 // Start after the transfer marker so this new death cannot cancel its own
 // bounded whitelist when strong-context supersession is evaluated.
 deathReclaim.ace(b);
}

/**
* Local death with no confident PvM / PK classification. Drop stale transfer and
* action evidence so its inventory wipe is booked from ordinary loss evidence.
*/
synchronized void zj() {
 claims.nk();
 deathReclaim.reset();
 eb.drop(CN, DO);
 om();
 eb.drop(SF);
 eb.drop(BO);
 eb.ttl(CT, max(6, config.stabilizationTicks() + 4));
 ac().clear();
 ac().note = UNCLASSIFIED_LOCAL_DEATH_NOTE;
 if (dirty || pendingSnapshot != null) {
  pc().clear();
  pc().note = UNCLASSIFIED_LOCAL_DEATH_NOTE;
 }
}

/**
* Player interacted with a retrieval service (gravestone, Death, boss NPC or chest).
*
* @return true when the reclaim window was armed; ambiguous targets (a bare "Chest",
*         a gravestone) only arm while a local PvM death is awaiting reclaim
*/
synchronized boolean abe(Service /* service */a, int /* ticks */b) {
 return deathReclaim.aca(a, b);
}

/** Current presentation read model for the local death-reclaim indicator. */
synchronized DeathReclaimStatus tt() {
 return new DeathReclaimStatus(deathReclaim.isAwaitingReclaim(), deathReclaim.xk(),
 deathReclaim.outstandingItemCount(), deathReclaim.ageTicks());
}

static Ac tc(Ac... /* candidates */a) {
 for (Ac /* candidate */b : a) {
  if (b != null) return b;
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
Ac aik(List<Ab> /* flows */a, long /* now */b, Map<Integer, Long> /* observedLootQuantities */c,
boolean /* allowObservedFee */d) {
 if (empty(a) || !deathReclaim.isAwaitingReclaim() && !deathReclaim.xk()) return null;
 Service /* service */e = deathReclaim.reclaimService;
 Map<Integer, Long> /* sourceBacked */f = positiveEntries(c);
 // Source-backed loot quantities are protected first.
 List<Ab> /* returned */g = claim(a, Ab::isGain, (/* flow */h, /* quantity */i) -> deathReclaim.aam(h.itemId,
 i - take(f, h.itemId, i)));
 long /* alm */j = lossQuantities(a, /* itemId */k -> k == ItemID.COINS).getOrDefault(ItemID.COINS, 0L);
 boolean /* ash */l = axs(a, /* itemId */m -> m != ItemID.COINS);
 Ac /* recovered */n = null;
 if (!g.isEmpty()) {
  String /* source */o = e == null ? msg("az") : e.interactable;
  n = mp(b, msg("bj"), "Death reclaim", g,
  msg("a3") + o + msg("bm"), null);
  audit(n);
 }
 boolean /* ase */p = e != null || !g.isEmpty();
 if (d && j > 0L && p && (!l || !g.isEmpty())) {
  String /* why */q = e == null ? msg("fz") : e.why(j);
  a.removeIf(/* flow */r -> r != null && r.itemId == ItemID.COINS && r.isCost());
  return ml(j, q, b, false);
 }
 return n;
}

/** Create a neutral audit receipt for the measured negative portion of the death wipe. */
Ac sf(List<Ab> /* flows */a, Map<Integer, Long> /* measuredLosses */b, long /* now */c) {
 if (empty(a) || empty(b)) return null;
 var /* outstanding */d = new HashMap<Integer, Long>(b);
 List<Ab> /* anc */e = claim(a, Ab::isCost, (/* flow */f, /* quantity */g) -> take(d, f.itemId, g));
 if (e.isEmpty()) return null;
 Ac /* transfer */h = mp(c, msg("fw"), "Death reclaim", e, msg("fx"), null);
 audit(h);
 return h;
}

void aep(Map<Integer, Long> /* deltas */a) {
 if (a != null) {
  a.forEach((/* itemId */b, /* delta */c) -> {
   if (b != null && c != null && c < 0L) {
    neutralZoneStoredItems.merge(b, Ae.abs(c), Ae::safeAdd);
   }
  });
 }
}

/**
* Excludes only positive quantities that match items actually removed on zone entry.
* Item ids with a matched observed-loot event remain counted as loot even if they
* happen to overlap the stored gear.
*/
Ac aih(List<Ab> /* flows */a, Map<Integer, Long> /* observedLootQuantities */b, long /* now */c) {
 if (neutralZoneRestoreTicks <= 0 || neutralZoneStoredItems.isEmpty() || empty(a)) {
  return null;
 }
 Map<Integer, Long> /* sourceBacked */d = positiveEntries(b);
 List<Ab> /* restored */e = claim(a, Ab::isGain, (/* flow */f, /* quantity */g) -> take(neutralZoneStoredItems, f.itemId,
 g - take(d, f.itemId, g)));
 if (e.isEmpty()) return null;
 if (neutralZoneStoredItems.isEmpty()) neutralZoneRestoreTicks = 0;
 return mp(c, msg("cg"), "Gauntlet", e, msg("gw"), null);
}

synchronized void zo(String /* label */a, long /* now */b) {
 claims.nk();
 deathReclaim.reset();
 if (activeSession == null && !ait(b)) return;
 if (!live()) return;
 Bx /* encounter */c = activeSession.ke(Be.DEATH, b, a,
 Bd.CONFIRMED, msg("ic"));
 pkHistory.aeu(c);
 aho(PK_DEATH, max(30, config.stabilizationTicks() + 4), "PK death loss", emptyMap(), c.getId());
}

/** An ownership-neutral storage or minigame move, with the note its classifier already chose. */
synchronized void zh(String /* note */a, int /* ticks */b) {
 markContext(Aj.TRANSFER, max(1, b), a);
}

/**
* Barrows gear stepping 100, 75, 50, 25 to 0 as you fight, or repaired back, is the same item in
* another state (RuneLite prices every stage but 0 alike): neutral, while its repair fee counts.
*/
Ac wear(List<Ab> /* flows */a, long /* now */b) {
 var /* net by piece */c = new HashMap<String, Long>();
 for (Ab /* flow */d : a) if (piece(d) != null) c.merge(piece(d), d.quantityDelta, Long::sum);
 List<Ab> /* stepped */e = claim(a, /* flow */f -> piece(f) != null && c.get(piece(f)) == 0L, (/* flow */g, /* quantity */h) -> h);
 return e.isEmpty() ? null : mp(b, msg("bwr"), "Barrows", e, msg("bww"), null);
}

/** The Barrows piece a flow is, whatever its stage; null for anything else. */
static String piece(Ab /* flow */a) {
 java.util.regex.Matcher /* name */b = Ak.pattern("barrows").matcher(a.itemName);
 return b.matches() ? Ag.lower(b.group(1)) : null;
}

/**
* A servant fetches from the bank, takes items to it and un-notes them: those moves are neutral.
* Coins (his wage, a sawmill fee) still count, as do the logs a sawmill trip takes and the
* planks it brings back, made from them.
*/
Ac servant(List<Ab> /* flows */a, long /* now */b) {
 if (eb.peek(SV) == null) return null;
 boolean /* sawmill */c = eb.peek(SW) != null;
 List<Ab> /* moved */d = claim(a, /* flow */e -> e.itemId != ItemID.COINS && !(c && wood(e)), (/* flow */f, /* quantity */g) -> g);
 // The sawmill trip ends when its planks come back.
 if (c && a.stream().anyMatch(/* flow */h -> h.isGain() && wood(h))) eb.drop(SW);
 return d.isEmpty() ? null : mp(b, msg("srv"), "Construction", d, msg("srw"), null);
}

/** Logs a sawmill trip takes, or the planks it brings back. */
static boolean wood(Ab /* flow */a) {
 return Ak.pattern("wood").matcher(a.itemName).matches();
}

/** Arm the narrow load intent with the target identity later used by Check. */
/** A measured check for this family supersedes its pending estimates. */
synchronized void clearPendingEstimates(String /* targetIdentity */a) {
 if (activeSession != null) chargeEstimateJournal.clearTarget(activeSession.getId(), a);
}

/**
* A compatible Check that moved nothing closes the window for this target: any automatic
* estimates still pending were overestimates and are removed through the announced path.
*/
synchronized void closeChargeWindow(String /* targetIdentity */a) {
 if (activeSession == null || a == null || a.trim().isEmpty()) return;
 String /* sessionId */b = activeSession.getId();
 long /* now */c = Ae.now();
 for (ChargeEstimateJournal.Receipt /* receipt */d : chargeEstimateJournal.receiptsFor(b, a)) {
  forfeit(d, c);
 }
 chargeEstimateJournal.clearTarget(b, a);
}

synchronized boolean yx(V /* variant */a, int /* selectedItemId */b, String /* selectedItemName */c,
String /* targetIdentity */d, int /* ticks */f) {
 if (a == null || f <= 0 || !Ar.xq(a, b, c)) return false;
 Ev e = eb.put(CL, b, 1L, f - 1, null);
 e.v = a;
 e.id = awq(d, null);
 return true;
}

/** A load click links candidate measured losses to a physical target; it proves no load quantity. */
El cp(List<Ab> /* flows */a) {
 Ev e = eb.peek(CL);
 var /* candidates */b = new ArrayList<Ab>();
 var /* left */c = new ArrayList<Ab>();
 boolean /* selected */d = false;
 for (Ab f : e == null || a == null ? Collections.<Ab>emptyList() : a) {
  if (f != null && f.isCost() && Ar.xq(e.v, f.itemId, f.itemName)) {
   b.add(f);
   d |= f.itemId == e.item;
  } else if (f != null) c.add(f);
 }
 return d ? new El(e.v, e.id, c, b)
 : new El(null, null, a == null ? emptyList() : a, emptyList());
}

/** Clear a pending charge-load intent when another action supersedes its evidence. */
synchronized void oh() {
 eb.drop(CL);
}

/**
* Observe Check evidence; a measured increase reconciles that weapon's pending load against
* its own last Check.
*/
synchronized Cm abu(Ar /* read */a, String /* targetIdentity */b, long /* now */c) {
 MeasuredChargeReadTracker.Baseline /* last */d = measuredChargeReadTracker.baseline(b);
 Map<Integer, Long> /* exactLoadQuantities */e = a == null || d == null ? emptyMap()
 : Ar.exactLoadQuantities(d.read, a);
 Cm /* delta */f = measuredChargeReadTracker.observe(a, b, c);
 if (!e.isEmpty()) {
  chargeLoadReviews.aed(
  a.variant, b, e, d.atEpochMillis, c);
 }
 return f;
}

/** A Check without trustworthy target identity invalidates the previous baseline. */
synchronized void afu() {
 measuredChargeReadTracker.reset();
}

/**
* A supported charged weapon entering the inventory is a new physical instance: a measured
* Check baseline for that variant can no longer be trusted to belong to the item now in the
* slot. Fail closed and require a fresh baseline instead of booking a phantom decrease from
* a replacement that retained the same slot identity.
*/
void vk(Map<Integer, Long> /* deltas */a) {
 if (empty(a)) return;
 for (Map.Entry<Integer, Long> /* entry */b : a.entrySet()) {
  Long /* quantity */c = b.getValue();
  if (c == null || c <= 0L) continue;
  V /* variant */d = Ar.aja(b.getKey());
  if (d != null) measuredChargeReadTracker.agu(d);
 }
}

/** Start tracking the items removed by entering a region-based neutral zone. */
synchronized void lw() {
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
synchronized void rg(int /* ticks */a) {
 boolean /* aub */b = neutralZoneTransferActive;
 neutralZoneTransferActive = false;
 captureNeutralZoneEntry = false;
 neutralZoneEntryBaseline = null;
 neutralZoneEntryCaptureTicks = 0;
 neutralZoneRestoreTicks = neutralZoneStoredItems.isEmpty() ? 0 : max(1, a);
 boolean /* apn */c = pc().context == Aj.TRANSFER && pc().note != null
 && pc().note.startsWith(msg("de"));
 boolean /* ang */d = ac().context == Aj.TRANSFER && ac().note != null
 && ac().note.startsWith(msg("df"));
 if (c) pc().clear();
 if (d) {
  ac().clear();
  eb.drop(CT);
 }
 if (b || c || d) {
  eb.drop(SF);
  om();
 }
}

/** Begin clue/casket path so dig/tele/key spends pair as clue costs. */
synchronized void ls(String /* encounterId */a, String /* label */b) {
 clueCostPairing.aue(a, b);
}

/** Ids of the unresolved key/chest claims; a Ledger row with one of these ids is still pc(). */
synchronized Set<String> aul() {
 var /* ids */a = new LinkedHashSet<String>();
 for (By /* claim */b : claims.all()) a.add(b.getClaimId());
 return a;
}

synchronized List<By> getPendingClaims() {
 return claims.all();
}

synchronized void lv(Map<Integer, Long> /* keyInventory */a) {
 claims.ly(a);
}

synchronized void aka() {
 claims.tick();
}

/** Items taken apart and what doing it loses (d23). */
static final Map<Integer, String[]> DISMANTLE = new HashMap<>();
static {
 for (String[] /* row */b : Ak.rows("d23")) DISMANTLE.put(Integer.parseInt(b[0]), b);
}
/** The item a Revert or Dismantle click takes apart, for the next few ticks. */

synchronized void dismantle(int /* itemId */a) {
 eb.put(DI, a, 1L, 5, null);
}

/** The clicked item left and something else arrived: the item was taken apart. */
boolean dm(List<Ab> /* flows */a) {
 boolean /* out */b = false, /* in */c = false;
 for (Ab /* flow */d : a) {
  if (d == null || eb.item(DI) <= 0) continue;
  if (d.itemId == eb.item(DI) && d.quantityDelta < 0L) b = true;
  else if (d.quantityDelta > 0L) c = true;
 }
 return b && c;
}

/** Arm/cancel ordinary key-chest provenance from an actual chest object click. */
synchronized boolean abp(String /* option */a, String /* target */b) {
 return claims.qo(a, b);
}

/**
* Per-tick GE custody maintenance: a held collect resolves at its window end and expired
* lifecycles hand off even when no further inventory change settles. Returns true when a
* booking was added.
*/
synchronized boolean ajv(long /* now */a) {
 return live() && zc(a);
}

/** Durable schema-104 GE custody lifecycle; flows are claimed at Ab quantity level. */
GeCustodyLedger geCustody = new GeCustodyLedger();
/** Schema-106 pooled known-basis coverage; supporting ownership state, never a second ledger. */
TrackedBasisLedger trackedBasis = new TrackedBasisLedger();
/** Bounded player Collect interaction evidence; never books by itself. */
/**
* Feeds an offer transition to the custody lifecycles. Review handoffs (slot reuse, identity
* replacement, unobserved terminal) and settlements completed by a held collect book once.
*/
synchronized void abh(Bj.Transition /* transition */a, String /* itemName */b, long /* now */c) {
 for (Ac /* outcome */d : geCustody.ack(a, b, c,
 activeSession == null ? "" : activeSession.getId())) {
  mk(d);
 }
}

/**
* Login/relog slot baseline. Resumes persisted custody lifecycles whose offer identity is
* unchanged, quarantines offers first seen already open as legacy/unbased, and hands
* changed-slot lifecycles to Review. Never invents basis and never replays old progress.
*/
synchronized void ahy(Map<Integer, Bj.Snapshot> /* snapshots */a, long /* now */b) {
 for (Ac /* handoff */c : geCustody.avh(a, b, activeSession == null ? "" : activeSession.getId())) {
  mk(c);
 }
}

/** Derived, read-only Market settlement context over the durable custody lifecycles. */
synchronized List<Bi.Row> ub() {
 return Bi.rows(geCustody.aji(), this::ajs, marketSessions);
}

/** Market rows follow the session that holds their booked settlement (cross-Grind sales). */
SessionLookup marketSessions = new SessionLookup() {
 public Ad holding(String /* transactionId */c) {
  for (Ad /* session */d : adk()) {
   if (sw(d, c) != null) return d;
  }
  return null;
 }
 public Ad byId(String /* sessionId */c) {
  for (Ad /* session */d : adk()) {
   if (d != null && d.getId().equals(c)) return d;
  }
  return null;
 }
};

List<Ad> adk() {
 var /* sessions */a = new ArrayList<Ad>(akf());
 a.addAll(history);
 return a;
}

/**
* Canonical item identity for GE custody/execution correlation, using the same
* noted/variation policy as inventory valuation. Identity normalization only; it never
* re-values a movement.
*/
/** Effective correction-aware canonical settlement lookup for the derived Market projection. */
Ac ajs(String /* id */a) {
 for (Ad /* session */b : a == null || a.isEmpty() ? Collections.<Ad>emptyList() : adk()) {
  Ac /* found */c = sw(b, a);
  if (c != null) return c;
 }
 return null;
}

static Ac sw(Ad /* session */a, String /* id */b) {
 if (a == null) return null;
 for (Ac /* transaction */c : a.getTransactions()) {
  if (c != null && b.equals(c.getId())) return c;
 }
 return null;
}

/**
* A bounded player Collect interaction with the Grand Exchange collection box. It only
* enables exact custody collection correlation for the window; it never books anything and
* never owns a movement by itself.
*/
synchronized void abg(long /* now */a) {
 long /* until */b = max(eb.at(GE), a + 20_000L);
 eb.put(GE, -1, 1L, Integer.MAX_VALUE, null).at = b;
}

/** Release held cash to its current owner's Review before dropping transient evidence. */
synchronized void ol() {
 eb.drop(GE);
 var /* outcomes */a = new ArrayList<Ac>();
 geCustody.aex(geCustody.pendingSettlementAtEpochMillis, a);
 a.forEach(this::mk);
}

/** Canonical custody outcomes (review handoffs and retried settlements) are booked once. */
void mk(Ac /* outcome */a) {
 if (a != null && activeSession != null) {
  // Counted custody settlements already consumed their reservation; their item legs
  // must never deplete known coverage again.
  add(a, a.isCounted());
 }
}

/** Authoritative profile/analytics timezone for presentation date scopes. */
synchronized ZoneId uf() {
 try { return ZoneId.of(profileTimeZoneId); }
 catch (RuntimeException /* ex */a) { return ZoneId.systemDefault(); }
}

synchronized void ahs(boolean /* visible */a) {
 claims.setLootChestVisible(a);
}

synchronized void abr(int /* keyItemId */a, Map<Integer, Long> /* contents */b) {
 claims.abq(a, b);
}

/** A non-counted, ownership-neutral audit row: transfers, holds, restores and reclaims. */
Ac mp(long /* now */a, String /* note */b, String /* activityName */c, List<Ab> /* flows */d,
String /* explanation */e, String /* encounterId */f) {
 return mr(a, Ai.TRANSFER, Aj.TRANSFER, b, c, false,
 d, Bd.CONFIRMED, e, f);
}

/** A session is open and not paused. */
boolean live() {
 return activeSession != null && !activeSession.paused;
}

/** Books a row; custody-owned rows already consumed their reservation and never deplete again. */
void add(Ac /* transaction */a, boolean /* custodyOwned */b) {
 activeSession.kf(a, config.maxTransactionsPerSession(), b);
}

/** Keeps a neutral transfer row only when the player asked to see them. */
void audit(Ac /* row */a) {
 if (config.keepTransferAuditRows()) add(a, false);
}

/** Books the custody settlements that came due; true when any did. */
boolean zc(long /* now */a) {
 List<Ac> /* bookings */b = geCustody.maintenance(a, activeSession.getId()).countedBookings;
 b.forEach(/* booking */c -> add(c, true));
 return !b.isEmpty();
}

Ac mr(long /* now */a, Ai /* type */b, Aj /* context */c, String /* note */d, String /* activityName */e,
boolean /* counted */f, List<Ab> /* flows */g, Bd /* confidence */h, String /* explanation */i, String /* encounterId */j) {
 return new Ac(a, activeSession.getElapsedMillis(a), b, c, d, e, f, g,
 h, i, j);
}

/** A booked charge row: counted consumption with the current activity, then its action kind. */
private Ac mo(long /* now */a, String /* note */b, Bd /* confidence */c,
String /* explanation */d, List<Ab> /* flows */e, Au /* kind */f) {
 Ac /* transaction */g = mr(a, CONSUMPTION, GENERIC, b,
 awq(activeSession.getActivityHint(), "General"), true, e, c, d, null);
 g.setActionKind(f);
 add(g, false);
 return g;
}

/** One chug from the chugging barrel: its doses as one Drank row titled by the barrel. */
synchronized void chug(List<Ab> /* doses */a, long /* now */b) {
 if (live()) mo(b, "Chugging barrel", Bd.CONFIRMED, msg("chb"), a, Au.DRINK).ahu(Bb.of("Chugging barrel"));
}

/**
* Book estimated component losses for one automatic charge use. The receipt counts immediately
* at the captured prices with LIKELY confidence; a later measured Check reconciles it. Priced
* components enter the transient journal so a Check can shrink them without repricing.
*/
synchronized Ac mn(String /* targetIdentity */a, String /* family */b, Au /* kind */c,
List<Ab> /* componentLosses */d, long /* now */e) {
 String /* target */f = awq(a, null);
 String /* label */g = awq(b, null);
 if (!live() || f == null || g == null || d == null || d.isEmpty()) {
  return null;
 }
 Ac /* transaction */h = mo(e, msg("jx") + g,
 Bd.LIKELY, "Estimated", d, c == null ? CAST : c);
 String /* sessionId */i = activeSession.getId();
 for (Ab /* flow */j : h.getFlows()) {
  if (j != null && j.isCost() && j.unitPrice > 0) {
   chargeEstimateJournal.record(i, f, g, h.getId(), j.itemId, abs(j.quantityDelta));
  }
 }
 return h;
}

/**
* Consume one measured component decrease against this target's pending estimates. An
* uncorrected straddling receipt is shrunk to the measured quantity at its captured price and
* confirmed; manually corrected receipts are only consumed, never edited. Returns the covered
* quantity.
*/
private long aer(String /* sessionId */a, String /* target */b, int /* itemId */c, long /* measured */d, long /* now */e) {
 long /* left */f = d;
 for (ChargeEstimateJournal.Receipt /* receipt */g : chargeEstimateJournal.receipts(a, b, c)) {
  long /* remaining */h = g.remainingQuantity;
  if (h <= 0L) continue;
  Ac /* estimate */i = activeSession.sw(g.transactionId);
  boolean /* frozen */j = i == null || i.getCorrection() != Ah.AUTO;
  if (f < h && !j) {
   // Shrink the overestimate through the session's announced automatic-evidence path.
   activeSession.pzz(i, c, h - f, "Confirmed");
   chargeEstimateJournal.consume(g, h);
   f = 0L;
   continue;
  }
  long /* take */k = min(f, h);
  chargeEstimateJournal.consume(g, k);
  f -= k;
  if (!j && k >= h) {
   i.confidence = Bd.CONFIRMED;
   i.agt("Confirmed");
  }
  if (f <= 0L) break;
 }
 // The measured window is closed here: automatic estimates left over are overestimates
 // and are removed, while manually corrected rows are preserved for future credit.
 for (ChargeEstimateJournal.Receipt /* receipt */l : chargeEstimateJournal.receipts(a, b, c)) {
  forfeit(l, e);
 }
 return d - f;
}

/** Removes one overestimated automatic receipt through the session's announced path. */
private void forfeit(ChargeEstimateJournal.Receipt /* receipt */a, long /* now */b) {
 if (a == null || a.remainingQuantity <= 0L || activeSession == null) return;
 Ac /* estimate */c = activeSession.sw(a.transactionId);
 if (c == null || c.getCorrection() != Ah.AUTO) return;
 activeSession.pzz(c, a.itemId, a.remainingQuantity, "Check reconciled");
 chargeEstimateJournal.consume(a, a.remainingQuantity);
}

/**
* Book exact component losses from a compatible measured Check difference, first reconciling
* any pending estimates for that target; returns the booked row, or null (zero cost) when the
* measurement adds nothing beyond what estimates already booked. Generic inventory losses,
* menu intent and animations cannot authorize this.
*/
synchronized Ac mj(Cm /* measuredDelta */a, String /* itemName */b, List<Ab> /* componentLosses */c,
long /* now */d) {
 return mj(a, b, c, d, null);
}

synchronized Ac mj(Cm /* measuredDelta */b, String /* itemName */c, List<Ab> /* componentLosses */d,
long /* now */e, String /* targetIdentity */f) {
 if (!live() || b == null || b.aui(d,
 chargeLoadReviews.acy(b.variant)) <= 0L) {
  return null;
 }
 List<Ab> /* booked */g = d;
 String /* target */h = f == null ? "" : f.trim();
 if (!h.isEmpty()) {
  var /* excess */i = new ArrayList<Ab>();
  String /* sessionId */j = activeSession.getId();
  for (Ab /* flow */k : d) {
   long /* measured */l = abs(k.quantityDelta);
   long /* extra */m = l - aer(j, h, k.itemId, l, e)
   - chargeEstimateJournal.evictedOverlap(j, h, k.itemId);
   if (m <= 0L) continue;
   i.add(m == l ? k : new Ab(k.itemId, k.itemName, -m, k.unitPrice,
   -Ae.agz(m, k.unitPrice), k.getPriceSource(), k.priceCapturedAtEpochMillis));
  }
  if (i.isEmpty()) return null;
  g = i;
 }
 // The spend belongs to what the player is doing, not to the weapon: Top activities
 // must never grow a "Toxic blowpipe" row. The weapon stays in the note.
 Ac /* transaction */n = mo(e, msg("kb") + awq(c, "Measured charges"),
 Bd.CONFIRMED,
 msg("a2") + b.variant.getDisplayName() + ".",
 g, b.variant == V.V1b ? FIRE : CAST);
 return n;
}

/**
* Book a death reclaim fee as counted PK_FEE / Lost cost.
*/
synchronized Ac ml(long /* feeGp */a, String /* why */b, long /* now */c) {
 return ml(a, b, c, true);
}

/**
* @param pvp true books the PK_FEE / PK_DEATH row used for player-combat deaths; false
*            books a plain counted CONSUMPTION cost under the "Death reclaim" activity
*/
synchronized Ac ml(long /* feeGp */a, String /* why */b, long /* now */c, boolean /* pvp */d) {
 if (a <= 0L || !live()) return null;
 var /* asf */e = new Ab(ItemID.COINS, "Coins", -a, 1, -a, Av.FACE_VALUE, c);
 String /* why */g = b == null ? DeathReclaimFees.vc(false) : b;
 Ac /* transaction */f = mr(c, d ? PK_FEE : CONSUMPTION, d ? PK_DEATH : GENERIC,
 g, "Death reclaim", true, singletonList(e), Bd.CONFIRMED, g, null);
 add(f, false);
 return f;
}

synchronized boolean qi(String /* transactionId */a, Ah /* correction */b, long /* now */c, String /* reason */d) {
 boolean /* changed */e = activeSession != null && activeSession.qi(a, b, c, d);
 return e;
}

/** Returns the active custom/general session's still-unresolved decisions as detached rows. */
/** Unresolved owner decisions in the active session, newest first, as review rows. */
synchronized List<Cu> getReviewRows(long /* now */a) {
 var /* rows */b = new ArrayList<Cu>();
 if (activeSession == null) return b;
 for (Ac /* transaction */c : activeSession.getTransactions()) {
  if (Eh.aal(c)) b.add(ave(activeSession, c, a));
 }
 b.sort(Comparator.comparingLong((Cu /* itemDataValue */d) -> d.timestampEpochMillis).reversed());
 return b;
}

/** Current world/economy permission for ordinary automatic market quotes (presentation reads it). */
boolean automaticMarketQuotesAvailable() {
 return valuationService.automaticMarketQuotesAvailable();
}

/** Domain revision: every canonical mutation advances it; derived previews bind to it. */
synchronized long getRevision() {
 return revision;
}

/**
* Exact rows and prospective personal-Net delta a Decide all would apply, computed from
* the same canonical transaction ids that {@link #kp} mutates.
*/
synchronized DecideAllPreview adu(Cl /* decision */a, Predicate<Cu> /* scope */b, long /* now */c) {
 var /* aqi */d = new ArrayList<String>();
 long /* netDelta */e = 0L;
 if (activeSession != null && a != null && b != null) {
  Ah /* correction */f = a.ajo();
  for (Ac /* transaction */g : activeSession.getTransactions()) {
   if (!Eh.aal(g) || !b.test(ave(activeSession, g, c))) {
    continue;
   }
   d.add(g.getId());
   e = Ae.safeAdd(e,
   g.awp(f) - (g.isCounted() ? g.getNet() : 0L));
  }
 }
 return new DecideAllPreview(a, d, e, getRevision());
}

/**
* Applies a preview as one atomic batch with one undo. Returns {@code -1} without touching
* anything when the domain revision moved since the preview (stale row ids never apply).
*/
synchronized int kp(DecideAllPreview /* preview */a, long /* now */b) {
 if (a == null || activeSession == null || a.transactionIds.isEmpty()) return 0;
 if (a.revision != getRevision()) return -1;
 int /* changed */c = activeSession.qh(a.transactionIds,
 a.decision.ajo(), b, "Review decision: " + a.decision.name());
 return c;
}

/** Revision-bound Decide all impact: canonical ids, row count and exact prospective Net delta. */
static class DecideAllPreview {
 Cl decision;
 List<String> transactionIds;
 long netDelta;
 long revision;
 DecideAllPreview(Cl /* decision */c, List<String> /* transactionIds */d, long /* netDelta */h, long /* revision */j) {
  this.decision = c;
  this.transactionIds = unmodifiableList(new ArrayList<>(d));
  this.netDelta = h;
  this.revision = j;
 }
 int rowCount() {
  return transactionIds.size();
 }
}

static Cu ave(Ad /* session */a, Ac /* transaction */b, long /* now */c) {
 var /* items */d = new ArrayList<Item>();
 for (Ab /* flow */e : b.getFlows()) {
  if (e != null) {
   d.add(new Item(e.itemId, e.itemName, e.quantityDelta, e.valueDelta));
  }
 }
 if (d.isEmpty()) d.add(new Item(-1, b.getNote(), 0L, b.tl()));
 String /* why */f = b.getExplanation();
 if (blank(f)) f = msg("dg");
 long /* timestamp */g = b.timestampEpochMillis;
 long /* age */h = c > g ? c - g : 0L;
 EnumSet<Cl> /* decisions */i = EnumSet.allOf(Cl.class);
 return new Cu(b.getId(), a.getId(), g, h, d,
 b.tl(), f, i);
}

synchronized boolean kr(String /* transactionId */a, int /* itemId */b, long /* keepQuantity */c, long /* now */d,
String /* optionalNote */e) {
 boolean /* changed */f = activeSession != null
 && activeSession.kr(a, b, c, d, e);
 return f;
}

synchronized boolean akb(long /* now */a) {
 boolean /* changed */b = activeSession != null && activeSession.akb(a);
 return b;
}

synchronized Ac akc(long /* now */a) {
 Ac /* undone */b = activeSession == null ? null : activeSession.akc(a);
 return b;
}

synchronized Ac agn(long /* now */a) {
 Ac /* restored */b = activeSession == null ? null : activeSession.agn(a);
 return b;
}

synchronized void aeh(String /* activityHint */a) {
 if (live()) activeSession.aeh(a, Ae.now());
}

synchronized Ac adj(Cc /* current */a, long /* now */b) {
 return adj(a, b, null);
}

/** Additional source receipts are published after canonical booking has completed. */
synchronized Ac adj(Cc /* current */b, long /* now */c,
java.util.function.Consumer<Ac> /* additionalBooking */d) {
 eb.hold = dirty || pendingSnapshot != null || (b != null && baseline != null && !b.equals(baseline));
 eb.tick(eb.now + 1);
 // Inventory alone must not create a tracker when automatic startup is
 // disabled or when no session has been started yet.
 if (activeSession == null) return null;
 // adj is fed by the client's per-game-tick snapshot path.
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
 String /* aod */e = deathReclaim.tick(dirty || pendingSnapshot != null
 || (b != null && baseline != null && !b.equals(baseline)));
 if (e != null) {
  Ac /* expiry */f = mp(c, msg("dh"), "Death reclaim", emptyList(), e, null);
  add(f, false);
  return f;
 }
 if (activeSession.paused) {
  // An inventory gain during an Idle pause is the action that ended it (a
  // pickpocket's loot can settle before the activity detector fires): resume and
  // book it. Other pauses stay aligned so Resume never inherits pause-window
  // churn as catch-up profit (owner 2026-09-29).
  Map<Integer, Long> /* woke */g = b == null || baseline == null ? null : b.diff(baseline);
  boolean /* idleResume */h = activeSession.getPauseReason() == IDLE && g != null
  && g.values().stream().anyMatch(/* value */i -> i != null && i > 0L);
  if (!h) {
   // Keep the baseline aligned while paused so Resume never treats
   // pause-window inventory churn as catch-up profit.
   baseline = b;
   if (captureNeutralZoneEntry) neutralZoneEntryBaseline = b;
   baselinePriming = false;
   ow();
   eb.drop(CN);
   eb.drop(DO);
   om();
   return ki(c);
  }
  activeSession.resume(c);
 }
 if (baselinePriming) {
  abv(b);
  return null;
 }
 if (baseline == null) {
  baseline = b;
  if (captureNeutralZoneEntry) neutralZoneEntryBaseline = b;
  ow();
  return null;
 }
 if (!dirty && b.equals(baseline)) return ki(c);
 // Container callbacks are expected, but comparing the actual snapshot
 // makes the engine resilient to a missed or reordered callback.
 if (!dirty) {
  dirty = true;
  nn();
 }
 if (pendingSnapshot == null || !pendingSnapshot.equals(b)) {
  pendingSnapshot = b;
  pendingStableTicks = 0;
  nn();
  return null;
 }
 pendingStableTicks++;
 if (pendingStableTicks < max(0, config.stabilizationTicks())) return null;
 Ac /* settled */j = settle(pendingSnapshot, c, d);
 pa();
 return j;
}

/** One stabilized inventory change moving through the settle stages, with the claim it settles under. */
static class Settle extends Clue {
 long now;
 Cc committed;
 List<Ab> flows;
 /** A local death with no confident PvM/PK classification: routine spends stay unlabelled. */
 boolean suppressActionEvidence;
 boolean localDeath;
 boolean deathWipe;
 boolean ambiguousDeathCoinLoss;
 Map<Integer, Long> deathWipeLosses = emptyMap();
 Map<Integer, Long> keyLosses = emptyMap();
 String settledClaimId;
 Ac keyAudit;
 Cy loot = Cy.NONE;
 /** Source-backed quantities, kept even if the loot label is later refused. */
 Map<Integer, Long> lootQuantities = emptyMap();
 boolean destroy;
 boolean pricingReview;
 boolean decant;
 boolean dismantle;
 boolean dropped;
 boolean consumed;
 boolean asTransfer;
 Au actionIntent;
 Bb actionLabel;
 int consumedItemId = -1;
 int droppedItemId = -1;
 String activity;
 Settle(Clue /* claim */c, Cc /* committed */d, long /* now */h) {
  offer(c.context, c.note, c.expectedLoot, c.encounterId, true);
  transfer = c.transfer;
  hard = c.hard;
  this.committed = d;
  this.now = h;
 }
}

/**
* Settles one stabilized inventory change. The stages run in order; each may book its own
* receipt and removes the flows it owns: commit, death wipe, key tokens, held items (death
* keys, wipe, charge loads), returns (loot, chest claims, death reclaim), neutral-zone restore,
* actions and transfers, GE custody, then classification and booking of what is left.
*/
Ac settle(Cc /* committed */b, long /* now */c, java.util.function.Consumer<Ac> /* additionalBooking */d) {
 Settle s = commit(b, c);
 if (s == null) return null;
 ain(s);
 aip(s);
 if (s.flows.isEmpty()) return s.keyAudit;
 Ac /* held */e = aio(s);
 if (s.flows.isEmpty()) return e;
 Ac /* returned */f = ais(s);
 if (s.flows.stream().allMatch(/* flow */g -> g.valueDelta == 0L && g.getPriceSource() != Av.UNPRICED)
 // Owner 2026-10-09: a clue scroll, scroll box or casket picked up is worth nothing yet but is a
 // drop, so it books and HUD+ shows it; opening, stepping or moving them still books nothing.
 && !(s.flows.stream().allMatch(Ab::isGain) && Bn.all(s.flows, 0, /* name */g -> Ak.pattern("clue").matcher(g).matches()))) {
  // A change with no value and no unpriced item is nothing to account for; a settled
  // claim still closed the deferred key audit row above. No value threshold exists.
  return tc(f, s.keyAudit);
 }
 Ac /* restore */h = aih(s.flows, s.lootQuantities, c);
 if (h != null) {
  audit(h);
  if (s.flows.isEmpty()) return h;
  // Only the matched entry items are neutral. Classify residual flows using
  // their actual consumption/loot evidence rather than the old region latch.
  s.clear();
  pa();
 }
 for (Ac /* neutral */sv : new Ac[] {servant(s.flows, c), wear(s.flows, c)}) {
  if (sv != null) {
   audit(sv);
   if (s.flows.isEmpty()) return sv;
  }
 }
 List<Ac> /* sources */i = mu(s);
 // Partial source matches classify the whole remaining settle as loot only
 // when the transfer partition consumed the unmatched quantities. Without
 // that partition, an oversized same-id stack (or unrelated extra gain)
 // must follow generic gain classification rather than inherit the loot tag.
 if (s.loot.matched && !aae(s.flows, s.loot.matchedQuantities)) s.loot = Cy.NONE;
 aif(s);
 if (s.consumed && !s.dropped && !s.destroy && !s.hard && !s.decant && !s.suppressActionEvidence
 && Bn.resolve(s.actionIntent, s.flows, CONSUMPTION, s.activity) == DRINK) {
  List<Ab> /* normalized */j = valuationService.normalizeConsumedFlows(s.flows, DRINK, s.now);
  s.pricingReview = j == null;
  if (j != null) s.flows = j;
 }
 Ac /* cast */k = aiv(s);
 Ac /* result */l;
 if (!i.isEmpty() && s.flows.isEmpty()) {
  l = i.get(i.size() - 1);
 } else {
  Ac /* custody */m = aij(s);
  l = m != null ? m : book(s);
 }
 if (d != null) {
  for (Ac /* row */n : i) {
   if (n != l) d.accept(n);
  }
  if (k != null) d.accept(k);
 }
 return l;
}

/**
* A spell cast settling with a pickup (owner 2026-09-28) books its runes as their own Cast, not as
* loose losses on the loot receipt. Only a loss set that is exactly one spell's runes splits off.
*/
Ac aiv(Settle s) {
 var /* losses */a = new ArrayList<Ab>();
 for (Ab /* flow */b : s.flows) {
  if (b.quantityDelta < 0L) a.add(b);
 }
 if (a.isEmpty() || a.size() == s.flows.size() || s.hard || s.soft() || s.localDeath
 || s.dropped || s.destroy || s.asTransfer || s.context == MARKET || Spells.a1(a) == null) {
  return null;
 }
 var /* slice */c = new Settle(new Clue(), s.committed, s.now);
 c.flows = a;
 c.activity = s.activity;
 c.actionIntent = s.actionIntent;
 c.actionLabel = s.actionLabel;
 s.flows = new ArrayList<>(s.flows);
 s.flows.removeAll(a);
 return book(c);
}

/** Different loot owners share a measured snapshot, never a source label or encounter. */
List<Ac> mu(Settle s) {
 if (s.loot.sources.size() <= 1) return emptyList();
 var /* rows */a = new ArrayList<Ac>();
 for (Cy /* source */b : s.loot.sources) {
  var /* remaining */c = new HashMap<Integer, Long>(b.matchedQuantities);
  List<Ab> /* parts */d = claim(s.flows, /* flow */e -> e.quantityDelta > 0L, (/* flow */f, /* quantity */g) -> {
   long /* take */h = min(g, c.getOrDefault(f.itemId, 0L));
   c.computeIfPresent(f.itemId, (/* id */i, /* wanted */j) -> j - h);
   return h;
  });
  if (d.isEmpty()) continue;
  var /* slice */k = new Settle(new Clue(), s.committed, s.now);
  k.offer(b.context, b.note, b.matchedQuantities, b.encounterId, true);
  k.flows = d;
  k.loot = b;
  k.lootQuantities = b.matchedQuantities;
  k.activity = b.activityName;
  k.settledClaimId = a.isEmpty() ? s.settledClaimId : null;
  a.add(book(k));
 }
 s.loot = Cy.NONE;
 s.lootQuantities = emptyMap();
 s.settledClaimId = null;
 if (s.context == LOOT || s.context == PK_LOOT) s.generic();
 return a;
}

/** Commits the stabilized snapshot: its measured deltas, valued, under the pending claim. */
Settle commit(Cc /* committed */b, long /* now */c) {
 var s = new Settle(pc(), b, c);
 s.suppressActionEvidence = UNCLASSIFIED_LOCAL_DEATH_NOTE.equals(s.note);
 s.localDeath = s.suppressActionEvidence || s.context == PK_DEATH;
 if (s.suppressActionEvidence) {
  // Keep routine rune/ammo spends distinguishable from local death losses
  // without exposing an internal marker in the transaction note.
  s.note = "";
 }
 Map<Integer, Long> /* deltas */d = b.diff(baseline);
 baseline = b;
 vk(d);
 if (captureNeutralZoneEntry && neutralZoneEntryBaseline != null) {
  if (s.context == Aj.TRANSFER && s.note != null && s.note.startsWith(msg("di"))) {
   aep(b.diff(neutralZoneEntryBaseline));
  }
  neutralZoneEntryBaseline = b;
 }
 ow();
 if (d.isEmpty()) {
  // Same-tick acquire+consume (pickup then bury) nets to zero vs the prior
  // baseline. Recover a named consume when invent qty fell since intent arm.
  d = sameTickConsumeDeltas(b);
 }
 s.flows = d.isEmpty() ? new ArrayList<>() : new ArrayList<>(valuationService.value(d, c));
 if (s.context == GENERIC && !s.transfer && !s.hard) s.flows = akq(s.flows);
 return s.flows.isEmpty() ? null : s;
}

/** A measured death wipe: stacks held at death that left INV stay ownership-neutral. */
void ain(Settle s) {
 boolean /* candidate */b = deathReclaim.wh() && axs(s.flows);
 boolean /* allowed */c = b && (s.context == Aj.TRANSFER && wd(s.note)
 || s.context == GENERIC && !s.transfer && !s.hard);
 if (b && !c) {
  // A stronger market, transfer, production or loot context owns this change.
  deathReclaim.nl();
 }
 Map<Integer, Long> /* actionLosses */d = c ? confirmedActionLossQuantities(s.flows, s.hard) : emptyMap();
 s.deathWipeLosses = c ? deathReclaim.onDeathItemsRemoved(without(s.flows, d::containsKey, false))
 : emptyMap();
 if (!d.isEmpty()) {
  // Consume action-attributed quantities from the potential wipe whitelist,
  // but leave unrelated held-at-death ids eligible in the same settle.
  deathReclaim.ry(d);
 }
 s.deathWipe = !s.deathWipeLosses.isEmpty();
 s.ambiguousDeathCoinLoss = s.deathWipe && deathReclaim.xk() && axs(s.flows, /* id */e -> e == ItemID.COINS)
 && !s.deathWipeLosses.containsKey(ItemID.COINS);
 s.localDeath |= s.deathWipe;
 if (s.context == Aj.TRANSFER && wd(s.note) && (!s.deathWipe || s.ambiguousDeathCoinLoss)) {
  // The short note is not ownership evidence. Without a measured whitelist
  // match, let the remaining change follow its actual action/classification.
  s.clear();
  om();
 }
}

/**
* Key tokens are provenance, not loot value: a credible pickup becomes a separate non-counted
* audit row, and only later contents withdrawals contribute ordinary valued flows.
*/
void aip(Settle s) {
 s.keyLosses = lossQuantities(s.flows, KeyChestCatalogue::wc);
 if (s.localDeath) {
  lossQuantities(s.flows, KeyChestCatalogue::wg).forEach(claims::consume);
  claims.nk();
 }
 List<Ab> /* lootKeys */b = gains(s.flows, Da::xa);
 Ac /* aox */c = null;
 if (!b.isEmpty()) {
  boolean /* pickup */d = uv(s, s.flows, b);
  s.flows = without(s.flows, Da::xa, true);
  s.expectedLoot = withoutItems(s.expectedLoot, Da::xa);
  c = d ? afb(b, s.encounterId, s.now) : null;
 }
 List<Ab> /* ali */e = gains(s.flows, KeyChestCatalogue::wg);
 boolean /* arr */f = !e.isEmpty() && uv(s, s.flows, e);
 s.flows = without(s.flows, KeyChestCatalogue::wg, false);
 s.expectedLoot = withoutItems(s.expectedLoot, KeyChestCatalogue::wg);
 Ac /* anp */g = f && !s.localDeath ? aej(e, s.now) : null;
 s.keyAudit = g != null ? g : c;
 if (gainQuantities(s.flows, ANY).isEmpty()) {
  // The click plus measured key removal may settle a tick before the
  // chest's contents enter INV. Keep only that narrow causal evidence.
  claims.aaj(s.keyLosses, emptyMap(), null);
 }
}

/** Held items: keys lost on death, the measured death wipe, key-token losses and charge loads. */
Ac aio(Settle s) {
 if (s.localDeath || claims.vo()) {
  claims.aet(s.flows, Da.keyQuantities(s.committed.quantities));
 }
 Ac /* wipe */b = s.deathWipeLosses.isEmpty() ? null
 : sf(s.flows, s.deathWipeLosses, s.now);
 if (s.deathWipe && (b != null || wd(s.note))) {
  // The measured death-owned quantities have their own neutral receipt. Any
  // residual item changes in this settle must follow their own evidence.
  s.clear();
  om();
 }
 // Key tokens are deferred provenance on both sides of the lifecycle: their
 // inventory loss is never a counted cost. Preserve the measured quantity for
 // death annotation, but exclude the token from transaction maths. A same-ID
 // loss in a mixed claim settle has no source identity, so it never reduces the
 // manifest key quantity on that evidence alone.
 s.flows.removeIf(/* flow */c -> c != null && c.isCost() && Da.xa(c.itemId));
 // Do not let this narrow menu intent compete with stronger bank/trade/
 // transfer evidence or the loot-key lifecycle. A stale load click expires
 // once a stronger ownership context is already active.
 if (s.hard || s.context == Aj.TRANSFER || s.context == MARKET) eb.drop(CL);
 El /* load */d = cp(s.flows);
 Ac /* aov */e = null;
 if (!d.ambiguousCandidates.isEmpty()) {
  s.flows = new ArrayList<>(d.remaining);
  e = chargeLoadReviews.aeg(d, s.now);
 }
 return tc(s.keyAudit, b, e);
}

/** Source-backed loot, key-chest claims and death reclaim returns. */
Ac ais(Settle s) {
 // Consume source-backed loot before the reclaim partitioner so a kill drop
 // sharing an item id with the death wipe remains counted as loot.
 s.loot = s.context != Aj.TRANSFER && s.context != MARKET ? qc(s.flows) : Cy.NONE;
 s.lootQuantities = s.loot.matchedQuantities;
 Map<Integer, Long> /* contents */b = s.loot.matched ? gainQuantities(s.flows, ANY)
 : minus(gainQuantities(s.flows, ANY), s.lootQuantities);
 boolean /* arq */c = !s.localDeath && !s.hard && !s.soft() && s.context != Aj.TRANSFER
 && s.context != MARKET && s.context != PRODUCTION && (s.loot.matched || s.context == GENERIC)
 && uy(s.flows);
 Da.Eg /* alg */d = c && !b.isEmpty() ? claims.aaj(s.keyLosses, b,
 s.loot.matched ? s.loot.activityName : null) : null;
 if (d != null && KeyChestCatalogue.wg(d.keyItemId)) {
  // Settlement and claim removal happen in this same revision. The settling row
  // carries the claim id only when the claim reached zero: a partial consume keeps
  // its durable remainder and must not look settled to a reload.
  String /* closed */e = claims.consume(d.keyItemId, d.quantity);
  s.settledClaimId = e != null ? e : s.settledClaimId;
 }
 if (d != null && !s.loot.matched) s.offer(LOOT, d.chestName, b, null, true);
 boolean /* deathTransfer */f = s.context == Aj.TRANSFER && wd(s.note);
 boolean /* arp */g = (!s.hard || f) && s.context != MARKET
 && (s.context != Aj.TRANSFER || f);
 return g
 ? aik(s.flows, s.now, s.lootQuantities, !s.ambiguousDeathCoinLoss) : null;
}

/** A confirmed consume or drop owns its losses; transfer evidence and loot name the rest. */
void aif(Settle s) {
 s.destroy = ci() != null && ci().flag;
 // Match Drop before consume wipe — consumeMatched used to null dropIntent first.
 s.dismantle = !s.hard && !s.soft() && dm(s.flows);
 s.decant = s.dismantle || Bn.isDecant(s.flows);
 if (s.soft() && s.decant) {
  // A mixed shape while the bank is open has no trustworthy player-action
  // source; preserve the accounting classification without a Decanted stamp.
  s.suppressActionEvidence = true;
 }
 s.dropped = eb.peek(DO) != null && ci() == null && auw(s.flows)
 && !axs(s.flows, /* id */b -> b != eb.item(DO));
 s.consumed = !s.dropped && !s.decant && aac(s.flows, s.hard);
 if (s.consumed && ci() != null) {
  // Only a verb whose intent actually matched these flows may name the action; a stale
  // or unmatched click never upgrades the wording. Ab-level custody must never steal
  // a quantity a named consume/drop intent owns, so its item is captured here.
  s.actionIntent = ci().action;
  s.actionLabel = ci().label;
  s.consumedItemId = ci().item;
 }
 s.droppedItemId = s.dropped && eb.peek(DO) != null ? eb.item(DO) : -1;
 if (!s.consumed && !s.dropped && !s.hard && !s.decant && Bn.wi(s.flows)) {
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
  eb.take(CN, eb.item(CN), 1L, e -> true);
  eb.drop(DO);
 }
 // Soft bank-open alone is a weak signal. Real deposits/withdrawals are one-sided;
 // Drink+Pick / Bury+Pick mixed windows must not become ownership-neutral TRANSFER
 // (that yields Used/lost ×0 and drops harvest rows when countUncertain is off).
 s.asTransfer = !s.consumed && !s.dropped && (s.hard || s.soft() && (auw(s.flows) || aux(s.flows)));
 if (s.asTransfer) {
  // Soft evidence while bank open (one-sided), or hard evidence that outlived UI
  // close, must settle as ownership-neutral TRANSFER (not Used/lost).
  s.context = Aj.TRANSFER;
  s.note = empty(s.note) ? "Bank transfer" : s.note;
  s.encounterId = null;
 } else if (s.context == Aj.TRANSFER) {
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
 } else if ((s.context == LOOT || s.context == PK_LOOT) && !aae(s.flows, s.expectedLoot)) {
  s.generic();
 } else if (s.context == LOOT) {
  s.activity = jz(s.note);
 } else if (s.context == PK_LOOT || s.context == PK_DEATH) {
  s.activity = "PKing";
 } else if (s.context == Aj.PRODUCTION && Dw.TRANSFORM.containsValue(s.note)) {
  // A run names its own skill: the session hint waits for a second XP drop, so a first
  // item would carry the last activity (or none).
  s.activity = "Smelting".equals(s.note) ? "Smithing" : s.note;
 }
}

/**
* Schema-104 flow-level custody: exact Ab quantities proven to be GE principal, returns
* or collections are claimed before any whole-transaction classification. Returns the last
* custody booking when nothing is left; residual quantities keep their own evidence.
*/
Ac aij(Settle s) {
 String /* sessionId */b = activeSession.getId();
 zc(s.now);
 // Ac-level owners (transfer/death/loot) block everything; per-item owners (a
 // named consume/drop intent or matched loot quantity) block only their own items so the
 // remaining quantities can still be claimed exactly.
 if (s.pricingReview || s.context == Aj.TRANSFER || s.hard || s.asTransfer || s.localDeath
 || s.context == LOOT || s.context == PK_LOOT || s.context == PK_DEATH
 || s.consumed && s.consumedItemId < 0 || s.dropped && s.droppedItemId <= 0) {
  return null;
 }
 var /* blocked */c = new HashSet<Integer>(s.loot.matched ? s.loot.matchedQuantities.keySet() : emptySet());
 if (s.consumedItemId >= 0) c.add(s.consumedItemId);
 if (s.droppedItemId > 0) c.add(s.droppedItemId);
 GeCustodyLedger.Partition /* custody */d = geCustody.partition(s.flows, s.now, b,
 /* itemId */e -> !c.contains(e), s.now <= eb.at(GE));
 if (d.countedBookings.isEmpty() && d.transferBookings.isEmpty()) return null;
 // Custody claimed these exact quantities; the matching offer sides are spent so
 // stale placement/fill evidence can never own a later unrelated movement.
 geCustody.spend(d.claimedFlows, s.now);
 var /* bookings */f = new ArrayList<Ac>(d.countedBookings);
 if (config.keepTransferAuditRows()) f.addAll(d.transferBookings);
 f.forEach(/* booking */g -> add(g, true));
 if (d.residualFlows.isEmpty()) return d.lastBooking();
 s.flows = new ArrayList<>(d.residualFlows);
 return null;
}

/** Classifies what is left of the change and books it as one receipt. */
Ac book(Settle s) {
 // The offer ledger is item/direction-scoped evidence of a Grand Exchange movement. It may
 // only own a settle that no stronger source-specific evidence already owns — matched
 // loot/reward, a confirmed consume/drop action, or a loot/death context keeps its own delta.
 boolean /* ats */b = s.loot.matched || s.consumed || s.dropped || s.context == LOOT || s.context == PK_LOOT
 || s.context == PK_DEATH;
 // A recent offer may explain a movement no custody lifecycle claimed. Custody is
 // authoritative for GE economics, so this is ancillary evidence only: it fails closed to
 // an uncounted review row instead of a quote-valued cost or automatic proceeds.
 boolean /* alz */c = !b && s.context != MARKET
 && s.context != Aj.TRANSFER && !s.hard && geCustody.explains(s.flows, s.now);
 if (c) s.note = "Grand Exchange";
 if (c || s.context == MARKET) {
  // The movement an offer side explains is the only one it may ever own, whether the
  // GE UI context or a recent offer supplied the evidence for this settle.
  geCustody.spend(s.flows, s.now);
 }
 // An explicit GE interaction is source evidence, not settlement authority. Any
 // residual flow that custody did not claim must await owner review, even when
 // the broad MARKET context would otherwise classify a coins-only TRADE.
 boolean /* aoi */d = s.context == MARKET && s.note != null && s.note.startsWith("Grand Exchange");
 boolean /* aoh */f = c || d || s.pricingReview;
 Ai /* type */h = classifier.classify(s.context, s.flows);
 if (s.dismantle) {
  // Taking an item apart (Revert, Dismantle) keeps its worth in the parts: neutral, never
  // Review (owner 1.2). What the parts do not give back is booked below.
  h = Ai.TRANSFER;
  for (Ab /* flow */i : s.flows) if (i != null && i.itemId == eb.item(DI)) s.note = "Dismantled " + i.itemName;
 } else if (s.consumed && h != Ai.TRANSFER && h != TRADE) {
  // Dose leftovers / empty vials are mixed inventory deltas; confirmed
  // consume intent still books Used/lost rather than uncertain exclusion.
  h = CONSUMPTION;
 } else if (!s.asTransfer && h == UNCERTAIN && !s.decant
 && (Bn.wi(s.flows) || s.soft() && axs(s.flows))) {
  // The classifier still sees mixed value: a dose shape alone is enough once soft
  // transfer has been refused, and a refused soft bank latch in a mixed Drink/Pick/Bury
  // window books supplies rather than dropping costs and harvest as UNCERTAIN.
  h = CONSUMPTION;
  s.consumed = true;
 } else if (!s.asTransfer && !s.hard && !s.soft() && h == UNCERTAIN && Bn.all(s.flows, -1,
 /* name */j -> Bn.d1(j) || Bn.vm(j) || Bn.e1(j))) {
  // A pickup settling with a routine spend (a dart thrown, a spell cast) is a gain net of that
  // spend, as a loot receipt absorbs a cast; never an uncounted Review (owner 2026-09-28).
  // An opened claim (a coin pouch) is income too: its neutral claim leg is not a cost
  // (owner 2026-09-29).
  h = GAIN;
 } else if (!s.asTransfer && !s.hard && !s.soft() && h == UNCERTAIN && s.decant) {
  // Combining or decanting keeps every dose, so it is neutral like a bank move, never a
  // Review row (owner 1.2); the doses keep their value until they are drunk.
  h = Ai.TRANSFER;
  s.note = "Decanted";
 } else if (!s.asTransfer && !s.hard && !s.soft() && h == UNCERTAIN && eb.peek(DG) != null
 && Bn.all(s.flows, -1, Ak.pattern("cash").asMatchPredicate())) {
  // Owner 2026-10-09: coins for items while talking to an NPC is a purchase from its dialogue
  // (Elnock's jars, a ticket), booked like a shop's; without a conversation it stays Review.
  h = TRADE;
  s.context = MARKET;
  s.note = "Bought";
 }
 // Drop settle is always CONSUMPTION so afm + pickup recovery work.
 if (s.dropped && h != Ai.TRANSFER && h != TRADE) h = CONSUMPTION;
 if (!s.loot.matched && !f && h != Ai.TRANSFER && h != TRADE
 && h != CONSUMPTION && h != PK_DEATH_LOSS && h != PK_SUPPLY_COST && h != PK_FEE
 && !s.localDeath && aux(s.flows)) {
  Ac /* recovery */k = aff(s);
  if (k != null && s.flows.isEmpty()) return k;
 }
 if (h == CONSUMPTION) {
  // Clue dig/tele/key spends while a clue path is active.
  if (clueCostPairing.wt(s.note)) {
   s.note = clueCostPairing.awb();
   if (clueCostPairing.tj() != null) s.encounterId = clueCostPairing.tj();
  }
  // Final presentation stamp after all note mutations.
  s.note = s.dropped ? "Dropped" : s.destroy ? "Destroyed" : s.note;
 }
 // Ancillary offer evidence with no claimable custody lifecycle fails closed to an
 // uncounted review row: never quote-valued cost, never automatic proceeds, no basis.
 boolean /* counted */l = !f && h != Ai.TRANSFER
 && (h != UNCERTAIN || config.countUncertainMixedChanges());
 h = f ? UNCERTAIN : h;
 boolean /* atm */m = l || config.keepTransferAuditRows() || h == UNCERTAIN;
 // The committed inventory baseline already deduplicates receipts. One encounter may
 // legitimately have several pickups; an encounter-wide revenue gate would discard them.
 s.activity = h == Ai.TRANSFER ? "Transfer" : h == TRADE ? "Market" : s.activity;
 Bd /* confidence */n = d || s.pricingReview ? Bd.UNCERTAIN
 : pv(s.context, h);
 // Non-bank transfers explain themselves (minigame wipe, neutral zone, death).
 String /* explanation */o = s.pricingReview ? msg("fl") : f ? msg("gk")
 : h == Ai.TRANSFER && s.note != null && !s.note.isEmpty() && !s.note.matches(msg("g"))
 ? msg("a1") + s.note + "." : sc(s.context, h, l);
 Ac /* transaction */p = mr(s.now, h, s.context, s.note, s.activity, l,
 s.flows, n, o, s.encounterId);
 if (!s.dropped && !s.destroy && !s.suppressActionEvidence) {
  // Presentation-only evidence; type/valuation/counted above are unchanged.
  p.setActionKind(Bn.resolve(s.actionIntent, s.flows, h, s.activity));
  if (p.getActionKind() == CAST && s.actionLabel != null) {
   p.ahu(s.actionLabel);
  }
 }
 boolean /* aob */q = s.dropped && h == CONSUMPTION && l;
 if (m) {
  if (h != Ai.TRANSFER && h != TRADE && !s.hard && !s.soft() && !s.localDeath) {
   // The relation is written only for a claim that actually closed, so restore's
   // settled-id check can never drop a partly consumed remainder.
   String /* closed */r = claims.aiq(minus(gainQuantities(s.flows, ANY), s.lootQuantities),
   lossQuantities(s.flows, Da::xa));
   s.settledClaimId = r != null ? r : s.settledClaimId;
  }
  if (s.settledClaimId != null) p.aid(s.settledClaimId);
  // Fired ammo is recoverable like an own drop: a pickup may shrink it (owner 1.1).
  if (q || l && p.getActionKind() == FIRE) p.zd();
  add(p, false);
  if (s.encounterId != null && !s.encounterId.isEmpty()) {
   activeSession.ll(p.getId(), s.encounterId, false);
  }
 }
 if (q) afm(p, s.flows);
 if (m && l && p.getActionKind() == FIRE) {
  for (Ab /* flow */t : s.flows) {
   if (t != null && t.isCost()) {
    eb.add(FA, t.itemId, Ae.abs(t.quantityDelta), Integer.MAX_VALUE, null).id = p.getId();
   }
  }
  eb.cap(FA, 256);
 }
 if (s.dropped) eb.take(DO, s.droppedItemId, 1L, e -> true);
 String[] /* lost */u = s.dismantle ? DISMANTLE.get(eb.item(DI)) : null;
 if (u != null) {
  // Owner 1.2: a crystal tool's 120 shards are lost when it is reverted.
  mo(s.now, s.note, Bd.CONFIRMED, "Lost when it was taken apart.",
  valuationService.value(Map.of(Integer.parseInt(u[2]), -Long.parseLong(u[1]))), null);
 }
 if (s.dismantle) eb.drop(DI);
 return p;
}

/** A loot-key manifest claim settled by this change's residual (non-source-backed) gains. */
void abv(Cc /* current */a) {
 primingObservedTicks++;
 if (primingSnapshot != null && primingSnapshot.equals(a)) {
  primingStableTicks++;
 } else {
  primingSnapshot = a;
  primingStableTicks = 1;
 }
 int /* aqa */b = max(2, config.stabilizationTicks() + 1);
 int /* atk */c = max(MINIMUM_BASELINE_WARMUP_TICKS, b);
 if (primingObservedTicks >= c && primingStableTicks >= b) {
  baseline = a;
  baselinePriming = false;
  primingSnapshot = null;
  primingObservedTicks = 0;
  primingStableTicks = 0;
  ow();
  pw();
 }
}

void nn() {
 // Late inventory after bank close: hard bank-container/menu evidence still applies
 // even when soft UI context ticks were cleared on close.
 if (hf()) yd();
 if (ct() <= 0) return;
 // Never attach TRANSFER to a new pending change from soft UI-open ticks alone.
 // Hard bank-container/menu evidence (or an already-latched hard pending) required.
 if (ac().context == Aj.TRANSFER && !pc().hard && !hf()) {
  // Soft open context may exist while bo(); do not latch it
  // into pending - noteSoftOpenTransferForPending handles settle evidence
  // only while the UI remains open, and close clears soft-only pc().
  return;
 }
 // Capture the active context once when a pending change begins. Repeated
 // snapshot observations must not merge the same expected loot again.
 // New loot events still merge through aho/kw.
 if (pc().context == GENERIC || ac().context.priority > pc().context.priority) {
  kw(ac().context, ac().note, ac().expectedLoot, ac().encounterId);
 }
}

void kw(Aj /* newContext */a, String /* note */b, Map<Integer, Long> /* expectedLoot */c, String /* encounterId */d) {
 pc().transfer |= a == Aj.TRANSFER && pc().hard;
 pc().offer(a, b, c, d, false);
}

Cy qc(List<Ab> /* flows */a) {
 Map<Integer, Long> /* available */b = gainQuantities(a, ANY);
 var /* matched */c = new HashMap<Integer, Long>();
 var /* groups */d = new LinkedHashMap<List<Object>, Cy>();
 for (Ev e : new ArrayList<>(eb.open)) {
  if ((e.kind != LT && e.kind != LP) || !eb.live(e) || eb.now > e.src().at) continue;
  Ev /* source */f = e.src();
  long /* qty */g = eb.take(e.kind, e.item, b.getOrDefault(e.item, 0L), v -> v == e);
  if (g <= 0L) continue;
  take(b, e.item, g);
  c.merge(e.item, g, Ae::safeAdd);
  Cy /* group */h = d.computeIfAbsent(Arrays.asList(f.note, f.name, f.context, f.id),
  /* key */i -> new Cy(true, f.note, f.name, f.context, f.id, new HashMap<>(), emptyList()));
  h.matchedQuantities.merge(e.item, g, Ae::safeAdd);
 }
 // A measured gain also spends private proof after its attribution window has ended.
 b.forEach((/* item */j, /* qty */k) -> eb.take(LT, j, k, e -> true));
 if (d.isEmpty()) return Cy.NONE;
 var /* sources */l = new ArrayList<Cy>(d.values());
 Cy /* first */m = l.get(0);
 return new Cy(true, m.note, m.activityName, m.context, m.encounterId,
 unmodifiableMap(c), unmodifiableList(l));
}

String jz(String /* note */a) {
 if (blank(a)) return activeSession == null ? "General" : activeSession.getActivityHint();
 String /* trimmed */b = a.trim();
 String /* prefix */c = "Loot from ";
 return b.startsWith(c) && b.length() > c.length() ? b.substring(c.length()).trim()
 : b;
}

boolean aae(List<Ab> /* flows */a, Map<Integer, Long> /* expectedLoot */b) {
 Map<Integer, Long> /* gains */c = gainQuantities(a, ANY);
 return !c.isEmpty() && (b == null || b.isEmpty()
 || c.equals(positiveEntries(b)));
}

Ac afb(List<Ab> /* keyFlows */b, String /* encounterId */c, long /* now */d) {
 Ac /* audit */e = mp(d, Da.LOOT_KEY_NOTE, "PKing", b, msg("gr"), c);
 add(e, false);
 List<Ab> /* amt */f = gains(b, Da::xa);
 // Claim identity must follow the key item, not incidental flow order. The normal valuator
 // sorts by captured value and its equal-value tie order comes from a map; either can vary
 // across equivalent observations while the audit row still represents the same keys.
 f.sort(Comparator.comparingInt((/* itemData */g -> g.itemId)));
 for (int /* index */h = 0; h < f.size(); h++) {
  Ab /* flow */i = f.get(h);
  // One audit row can carry several key types; each independent claim needs its own
  // Deterministic identity for multi-key provenance.
  String /* claimId */j = f.size() == 1 ? e.getId() : e.getId() + "#" + (h + 1);
  claims.open(j, i.itemId, i.quantityDelta, d);
 }
 if (c != null && !c.isEmpty()) {
  activeSession.ll(e.getId(), c, false);
 }
 return e;
}

Ac aej(List<Ab> /* keyFlows */a, long /* now */b) {
 Ac /* last */c = null;
 for (Ab /* flow */d : a) {
  if (d == null || d.quantityDelta <= 0L || !KeyChestCatalogue.wg(d.itemId)) {
   continue;
  }
  KeyChestCatalogue.Entry /* entry */e = KeyChestCatalogue.rq(d.itemId);
  if (e == null || e.tradeable) continue;
  Ac /* audit */f = mp(b, "Key held · " + awq(e.chestName, "Chest"), e.chestName, singletonList(d),
  msg("fp"), null);
  f.setActionKind(DEFERRED_CLAIM);
  add(f, false);
  claims.open(f.getId(), d.itemId, d.quantityDelta, b);
  c = f;
 }
 return c;
}

/** Only catalogued key losses and non-key gains: the shape of a chest open. */
static boolean uy(List<Ab> /* flows */a) {
 for (Ab /* flow */b : a == null ? Collections.<Ab>emptyList() : a) {
  if (b != null && b.quantityDelta != 0L && b.isCost() != KeyChestCatalogue.wc(b.itemId)) {
   return false;
  }
 }
 return a != null && !a.isEmpty();
}

/**
* Credible settled pickup evidence for an untradeable key token. Loot keys and deferred chest
* keys share one rule: the settle must be a source-backed loot/death context or a plain settled
* gain — never transfer, market or drop evidence — and a loot context must account for the
* observed key quantity.
*/
boolean uv(Clue /* applied */b, List<Ab> /* originalFlows */c, List<Ab> /* keyFlows */d) {
 Aj /* context */e = b.context;
 if (e == null || e == Aj.TRANSFER || e == MARKET || b.transfer || b.hard
 || eb.peek(DO) != null) {
  return false;
 }
 Ai /* alt */f = classifier.classify(e, c);
 if (f != GAIN && f != Ai.LOOT && f != Ai.PK_LOOT) {
  return false;
 }
 Map<Integer, Long> /* keyQuantities */g = gainQuantities(d, ANY);
 if (e == PK_LOOT || e == LOOT) {
  // The observed source callback must account for the actual key quantity;
  // a stale loot context or key-shaped bank flow cannot create provenance.
  return covers(b.expectedLoot, g);
 }
 // A source-less but settled ordinary gain is still a pickup; transfer-like evidence and
 // production/uncertain/death mixes are rejected above or by this context check.
 return e == GENERIC && f == GAIN;
}

void agb(boolean /* primeBaseline */a) {
 ol();
 eb.drop(CL);
 baseline = null;
 baselinePriming = a;
 primingSnapshot = null;
 primingObservedTicks = 0;
 primingStableTicks = 0;
 ow();
 pa();
 ec();
 eb.drop(LT, LP, RD, DI, CN, DO, OD);
}

/** Owner fencing clears only ephemera; persisted grave/key/custody continuity is retained. */
synchronized void eo() {
 ol();
 ec();
 chargeLoadReviews.clear();
 deathReclaim.reclaimTicks = 0;
 deathReclaim.reclaimService = null;
}

/** The same non-inbox helpers also reset at an ordinary tracking-window boundary. */
void ec() {
 chargeEstimateJournal.clear();
 measuredChargeReadTracker.reset();
 neutralZoneStoredItems.clear();
 neutralZoneTransferActive = false;
 captureNeutralZoneEntry = false;
 neutralZoneEntryBaseline = null;
 neutralZoneEntryCaptureTicks = 0;
 neutralZoneRestoreTicks = 0;
 clueCostPairing.clear();
 claims.pd();
}

void ow() {
 dirty = false;
 pendingSnapshot = null;
 pendingStableTicks = 0;
 eb.drop(CP);
}

boolean aac(List<Ab> /* flows */b, boolean /* hardTransferEvidence */c) {
 if (ci() == null || empty(b)) return false;
 // Match the spent stack even when a dose leftover / empty vial appears as a gain.
 if (ci().item >= 0 && axs(b, /* id */d -> d == ci().item)) return true;
 // Open intent (menu item id missing) or id mismatch after canonicalize: still
 // confirm Drink/Eat when the delta is a dose/partial leftover, or a pure cost
 // (Bury/Cast runes/ammo) so hard TRANSFER cannot swallow the spend.
 if (Bn.wi(b)) return true;
 if (auw(b)) {
  // Named intent for a different item + hard bank deposit must stay TRANSFER
  // (stale bury of bones must not book oak-log deposit as Used/lost).
  if (c && ci().item >= 0) return false;
  return true;
 }
 // Live potato-field sequence: Drink + Pick + Bury often share one stabilization
 // window. Any armed consume intent (named or open) must win so Used/lost books
 // instead of UNCERTAIN exclusion — wrong/stale named ids previously skipped
 // this path because it required itemId < 0 only.
 // Hard bank-container/menu evidence: do not let mixed any-cost swallow a deposit.
 if (c) return false;
 return axs(b);
}

/**
* Pickup then bury (etc.) in one stabilize window nets to zero vs baseline.
* Named consume intent stores invent qty at arm — synthesize the shortfall loss.
*/
Map<Integer, Long> sameTickConsumeDeltas(Cc /* current */a) {
 if (ci() == null || ci().item < 0 || ci().held < 0L
 || a == null) {
  return emptyMap();
 }
 long /* avu */b = a.aea(ci().item);
 long /* lost */c = ci().held - b;
 if (c <= 0L) return emptyMap();
 var /* synthetic */d = new HashMap<Integer, Long>();
 d.put(ci().item, -c);
 return d;
}

void afm(Ac /* transaction */a, List<Ab> /* flows */b) {
 Ev /* click */c = eb.peek(DO);
 for (Ab /* flow */d : b) {
  if (d == null || !d.isCost()) continue;
  Ev e = eb.add(OD, d.itemId, Ae.abs(d.quantityDelta), OWN_DROP_TTL_TICKS - 1, null);
  e.id = a.getId();
  if (c != null) { e.x = c.x; e.y = c.y; e.plane = c.plane; }
 }
 eb.cap(OD, MAX_OWN_DROPS);
}

Em aak(List<Ab> /* flows */a) {
 if (a == null) return null;
 // Conservative: single-item pickups only. Mixed stacks are ambiguous.
 List<Ab> /* gains */b = gains(a, ANY);
 if (b.size() != 1) return null;
 Ab /* gain */c = b.get(0);
 Ev /* best */d = null;
 int /* ael */e = Integer.MAX_VALUE;
 boolean /* ani */f = false;
 for (Ev /* record */g : new ArrayList<>(eb.open)) {
  if ((g.kind != OD && g.kind != RD) || !eb.live(g) || g.item != c.itemId) continue;
  Ac /* drop */h = activeSession.sw(g.src().id);
  if (h == null || !h.xe() || !h.isCounted()
  || h.getCorrection() != Ah.AUTO) continue;
  if ((g.src().plane >= 0)) {
   if (!playerLocationKnown || playerWorldPlane != g.src().plane) continue;
   int /* distance */i = max(abs(playerWorldX - g.src().x), abs(playerWorldY - g.src().y));
   if (i > OWN_DROP_MATCH_RADIUS) continue;
   boolean /* exactQty */j = c.quantityDelta == g.qty;
   boolean /* bestExact */k = d != null && c.quantityDelta == d.qty;
   if (d == null || i < e || (i == e && j && !k)) {
    d = g;
    e = i;
   }
  } else {
   // Unlocated dumps (no tile at Drop click) must still recover once the
   // player location is known — previously only matched when location was
   // unknown, so live pickups never reversed those losses.
   f |= d != null && !(d.src().plane >= 0);
   if (d == null) {
    d = g;
    e = 0;
   }
  }
 }
 if (f || d == null) return null;
 long /* apt */l = min(c.quantityDelta, d.qty);
 if (l <= 0L) return null;
 return new Em(d, l);
}

boolean ku(Em /* match */a, long /* now */b) {
 if (a == null || a.e == null || activeSession == null) return false;
 Ev /* record */c = a.e;
 boolean /* ammo */d = c.kind == FA;
 if (!eb.live(c) || a.qty > c.qty) return false;
 boolean /* recovered */f = activeSession.afc(c.src().id, c.item, a.qty, b,
 d ? "Ammo recovery" : "Own-drop recovery");
 if (!f) return false;
 eb.take(c.kind, c.item, a.qty, e -> e == c);
 return true;
}

/**
* Fired ammo a Take picks back up, newest shot first: it shrinks that Supplies receipt instead of
* booking loot. More than was fired and not yet recovered stays loot.
*/
Em ammo(Ab /* gain */a) {
 if (a == null || !eb.has(TA, a.itemId)) return null;
 List<Ev> /* records */b = eb.all(FA);
 for (int i = b.size() - 1; i >= 0; i--) {
  Ev /* record */c = b.get(i);
  if (c.item != a.itemId) continue;
  Ac /* fired */d = activeSession.sw(c.src().id);
  if (d != null && d.isCounted() && d.getCorrection() == Ah.AUTO) {
   return new Em(c, min(a.quantityDelta, c.qty));
  }
  eb.open.remove(c);
 }
 return null;
}

/** Reverse only a successfully recovered quantity; other measured gains keep their authority. */
Ac aff(Settle s) {
 var /* recovered */a = new ArrayList<Ab>();
 var /* residual */b = new ArrayList<Ab>();
 for (Ab /* gain */c : s.flows) {
  Ab /* left */d = c;
  while (d != null) {
   Em /* match */e = aak(singletonList(d));
   if (e == null) e = ammo(d);
   if (e == null || !ku(e, s.now)) break;
   a.add(d.part(e.qty));
   d = d.rest(e.qty);
  }
  if (d != null) b.add(d);
 }
 if (a.isEmpty()) return null;
 s.flows = b;
 Ac /* row */f = mr(s.now, Ai.TRANSFER, Aj.TRANSFER,
 "Own-drop recovery", "Drop recovery", false, a, Bd.CONFIRMED, msg("gy"), null);
 if (config.keepTransferAuditRows()) add(f, false);
 return f;
}

void os() {
 eb.drop(DO, OD, RD, FA, TA);
 playerLocationKnown = false;
}

void pw() {
 eb.drop(CX, CT, SF);
 // hf() survives until settle or hardTransferEvidenceTicks expiry
 // so bank-container evidence is not lost on the idle GameTick before inventory dirty.
}

void pa() {
 pw();
 om();
}

void om() {
 eb.drop(HF);
}

Bd pv(Aj /* appliedContext */a, Ai /* type */b) {
 if (a == Aj.TRANSFER || a == MARKET || a == LOOT
 || a == PK_LOOT || a == PK_DEATH) {
  return Bd.CONFIRMED;
 }
 if (b == UNCERTAIN) return Bd.UNCERTAIN;
 return Bd.LIKELY;
}

/** Loss ids claimed by exact consume/drop intent are excluded from the death wipe. */
Map<Integer, Long> confirmedActionLossQuantities(List<Ab> /* flows */a, boolean /* hardTransferEvidence */b) {
 var /* excluded */c = new HashMap<Integer, Long>();
 if (ci() == null) {
  return eb.peek(Eb.DO) == null ? c : lossQuantities(a, /* id */d -> d == eb.item(DO));
 }
 if (ci().item >= 0) {
  return lossQuantities(a, /* id */e -> e == ci().item);
 }
 // An open-id intent with only costs cannot distinguish which item was consumed; keep
 // every loss in this ambiguous batch counted.
 return Bn.kd(c, a)
 || !aac(a, b) ? c : lossQuantities(a, ANY);
}

String sc(Aj /* appliedContext */a, Ai /* type */b, boolean /* counted */c) {
 switch (a) {
  case TRANSFER:
  return msg("id");
  case MARKET:
  return c ? msg("hz") : msg("hr");
  case LOOT:
  return msg("hu");
  case PK_LOOT:
  return msg("hx");
  case PK_DEATH:
  return msg("hv");
  case PRODUCTION:
  return b == CONSUMPTION ? msg("zy") : msg("hy");
  case GENERIC:
  default:
  if (b == UNCERTAIN) {
   return c ? msg("hs") : msg("ht");
  }
  if (b == GAIN) return msg("hq");
  if (b == CONSUMPTION) return msg("hp");
  return msg("m");
 }
}

ChargeLoadReviews chargeLoadReviews = new ChargeLoadReviews(this);
Ac ki(long /* now */a) {
 if (dirty || pendingSnapshot != null) return null;
 if (ct() <= 0) pw();
 if (neutralZoneRestoreTicks > 0 && --neutralZoneRestoreTicks <= 0) {
  neutralZoneRestoreTicks = 0;
  neutralZoneStoredItems.clear();
 }
 return null;
}

/** A measured death wipe may own this change: a death-held transfer, or an unclaimed generic change. */
static boolean wd(String /* note */a) {
 return a != null && a.startsWith("Death: items held");
}

synchronized Bu getMetrics(long /* now */a) {
 if (activeSession == null) return Bu.none();
 return activeSession.metrics(a);
}

/** Ah-aware metrics of one owner; Hub v1 counts every booked row (charter D). */
synchronized Ad getActiveSession() {
 return activeSession;
}

synchronized Ad getGeneralSession() {
 return generalSession;
}

synchronized List<Ad> getHistory() {
 return unmodifiableList(new ArrayList<>(history));
}

synchronized Ad ua(String /* sessionId */a) {
 return archive.ua(a);
}

/** Metrics of a retained history session; null when the id is not in history. */
synchronized Bu tz(String /* sessionId */a, long /* now */b) {
 return archive.tz(a, b);
}

synchronized boolean qu(String /* sessionId */a) {
 return archive.qu(a);
}

/** Clears only GP Manager tracking state; configuration is intentionally outside the engine. */
synchronized void agr(long /* now */b) {
 history.clear();
 nc();
 pkHistory.clear();
 claims.clear();
 chargeLoadReviews.clear();
 // A reset is a new install: no session until gameplay (with Automatic tracking) or Start.
 generalSession = null;
 customSession = null;
 generalSuspendedByCustom = false;
 activeSession = null;
 // Destructive reset clears the schema-104 custody lifecycles with the rest.
 geCustody.reset();
 trackedBasis.reset();
 ol();
 // Destructive reset clears the pending death/reclaim physical evidence with it.
 deathReclaim.reset();
 os();
 agb(true);
 // A reset is a fresh start: resume by itself shortly, if the player does nothing first.
 autoStartAtEpochMillis = b + 4_000L;
}

synchronized Dt vl() {
 return activeSession == null ? Dt.none() : activeSession.ava();
}

synchronized SavedState qm() {
 var /* state */a = new SavedState(generalSession, customSession, generalSuspendedByCustom, history);
 if (profileIdentityKey != null && !profileIdentityKey.trim().isEmpty())
 a.setOwnerKey(profileIdentityKey);
 archive.ayc(a);
 a.setProfileTimeZoneId(profileTimeZoneId);
 claims.ayc(a);
 a.setPendingDeathReclaim(deathReclaim.snapshot());
 pkHistory.ayc(a);
 a.setSavedGrinds(savedGrinds);
 // Schema-104 custody continuity travels in the same bounded generation as the canonical
 // settlements that depend on it.
 geCustody.ayc(a);
 trackedBasis.ayc(a);
 return a;
}

// ---- My Grinds: reusable setup metadata, never financial truth --------------------------
/** Reusable My Grind definitions; setup and default targets only. */
List<Ap> savedGrinds = new ArrayList<>();
/** All My Grinds; archived ones only when asked for. */
synchronized List<Ap> getSavedGrinds(boolean /* includeArchived */a) {
 var /* result */b = new ArrayList<Ap>();
 for (Ap /* grind */c : savedGrinds) {
  if (!c.archived || a) b.add(c);
 }
 return b;
}

/** Saved-Grind lookup by stable id; null when unknown. */
synchronized Ap um(String /* grindId */a) {
 for (Ap /* grind */b : savedGrinds) {
  if (b.getGrindId().equals(a)) return b;
 }
 return null;
}

/**
* Creates a reusable My Grind from reusable setup only. When {@code linkSessionId} names a
* canonical session (active or retained), that one instance is linked as the first member of
* the lineage; other same-name instances are never swept in.
*/
synchronized Ap avg(String /* name */a, Long /* netTargetGp */b, Long /* activeTimeMillis */c,
boolean /* favorite */d, String /* linkSessionId */e) {
 String /* cleanName */f = a == null ? "" : a.trim();
 if (f.isEmpty()) return null;
 var /* grind */g = new Ap(UUID.randomUUID().toString(), f, b, c, d);
 savedGrinds.add(g);
 if (e != null && !e.isEmpty()) {
  Ad /* linked */h = activeSession != null && e.equals(activeSession.getId())
  ? activeSession : archive.ua(e);
  if (h != null) h.setGrindId(g.getGrindId());
 }
 return g;
}

/** Updates future reusable defaults; historical instances keep the snapshots they ran with. */
synchronized boolean updateSavedGrind(String /* grindId */a, String /* name */b, Long /* netTargetGp */c,
Long /* activeTimeMillis */d, boolean /* favorite */e) {
 Ap /* grind */f = um(a);
 if (f == null) return false;
 String /* cleanName */g = b == null ? "" : b.trim();
 if (!g.isEmpty()) f.setName(g);
 f.setNetTargetGp(c);
 f.setActiveTimeTargetMillis(d);
 f.setFavorite(e);
 return true;
}

synchronized boolean aht(String /* grindId */a, boolean /* archived */b) {
 Ap /* grind */c = um(a);
 if (c == null) return false;
 c.setArchived(b);
 return true;
}

/** Removes the reusable definition only; canonical history and lineage links survive. */
synchronized boolean deleteSavedGrind(String /* grindId */a) {
 Ap /* grind */b = um(a);
 if (b == null) return false;
 savedGrinds.remove(b);
 return true;
}

/** Stable-id lineage members, newest first. Never display-name matching. */
synchronized List<Ad> yk(String /* grindId */a) {
 var /* result */b = new ArrayList<Ad>();
 if (empty(a)) return b;
 Ad /* active */c = activeSession;
 if (c != null && a.equals(c.getGrindId())) b.add(c);
 for (Ad /* session */d : history) {
  if (d != null && a.equals(d.getGrindId())) b.add(d);
 }
 return b;
}

/** Stable-id lineage members among completed instances only, newest first. */
synchronized List<Ad> pp(String /* grindId */a) {
 var /* result */b = new ArrayList<Ad>();
 for (Ad /* session */c : yk(a)) {
  if (c != null && c.isClosed()) b.add(c);
 }
 return b;
}

/**
* Starts one fresh canonical Grind. Reusable setup travels as metadata and target snapshots;
* no financial state, loot, active time or correction state is copied.
*/
synchronized boolean startGrind(String /* name */a, String /* grindId */b, Long /* netTargetGp */c, Long /* activeTimeTargetMillis */d, long /* now */e) {
 String /* cleanName */f = a == null ? "" : a.trim();
 if (f.isEmpty() || customSession != null) return false;
 ajl(f, Cx.GENERAL, e);
 Ad /* active */g = activeSession;
 if (g == null) return false;
 if (b != null && !b.isEmpty()) g.setGrindId(b);
 g.setProfitTargetGp(c);
 g.setActiveTimeTargetMillis(d);
 return true;
}

/** Renames the running Grind; a change like any other, so the revision advances and it is saved. */
synchronized void afy(String /* name */a) {
 if (activeSession != null) {
  activeSession.rename(a);
  nc();
 }
}

/** Mid-Grind aim edit: changes the current target snapshot only, never financial truth. */
synchronized boolean ahq(Long /* netTargetGp */a, Long /* activeTimeTargetMillis */b, long /* now */c) {
 if (activeSession == null) return false;
 activeSession.setProfitTargetGp(a == null || a <= 0L ? null : a);
 activeSession.setActiveTimeTargetMillis(b == null || b <= 0L ? null : b);
 return true;
}

/** Configured receipt-retention window in days; the canonical detail age horizon. */
int receiptRetentionDays() {
 Db /* period */a = config == null ? null : config.receiptRetentionDays();
 return a == null ? Db.DAYS_90.days : a.days;
}

Ad aaq(String /* name */a, Cx /* mode */b, long /* now */c, Bt /* ownerKind */d) {
 var /* session */e = new Ad(a, c, b);
 e.setOwnerKind(d);
 lk(e);
 nc();
 return e;
}

void lk(Ad /* session */a) {
 if (a != null) {
  a.setChangeListener(this::nc);
  a.ownedValueObserver = trackedBasis;
 }
}

void nc() {
 revision = revision == Long.MAX_VALUE ? 1L : revision + 1L;
}

static boolean aks(String /* value */a) {
 if (blank(a)) return false;
 try { ZoneId.of(a); return true; }
 catch (RuntimeException /* ex */b) { return false; }
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
 Aj context = GENERIC;
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
 void offer(Aj /* claim */c, String /* claimNote */d, Map<Integer, Long> /* loot */h, String /* encounter */j, boolean /* replace */k) {
  if (k || c.priority > context.priority) {
   context = c;
   note = axw(d);
   expectedLoot = positiveEntries(h);
   encounterId = j;
  } else if (c == context) {
   note = empty(d) ? note : d;
   if (c == LOOT || c == PK_LOOT) {
    Map<Integer, Long> /* merged */l = positiveEntries(h);
    expectedLoot.forEach((/* itemId */n, /* quantity */o) -> l.merge(n, o, Ae::safeAdd));
    expectedLoot = l;
   }
   encounterId = empty(j) ? encounterId : j;
  }
 }
}

/** Quantity selected from one open receipt-backed observation. */
@AllArgsConstructor
static class Em {
 Ev e;
 long qty;
}
}
