package com.gpmanager;

import static com.gpmanager.ActionKind.*;
import static com.gpmanager.Context.*;
import static com.gpmanager.Context.LOOT;
import static com.gpmanager.Context.PK_LOOT;
import static com.gpmanager.FlowFilters.*;
import static com.gpmanager.GameData.msg;
import static com.gpmanager.Inbox.*;
import static com.gpmanager.PauseReason.*;
import static com.gpmanager.Phrases.*;
import static com.gpmanager.TransactionType.*;
import static java.lang.Math.*;
import static java.util.Collections.*;

import com.google.gson.Gson;
import com.gpmanager.BossRetrievalCatalogue.Service;
import com.gpmanager.ChargeCheck.ChargedItem;
import com.gpmanager.ReviewRow.Item;
import com.gpmanager.SavedState.*;
import java.time.ZoneId;
import java.util.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.gameval.ItemID;
import static com.gpmanager.SafeMath.nonNeg;
import java.util.Map.Entry;
import java.util.function.*;
import static java.util.Locale.*;
import java.util.regex.*;
import net.runelite.api.MenuAction;
import static com.gpmanager.Correction.*;
import static com.gpmanager.PriceSource.*;
import static com.gpmanager.SafeMath.safeAdd;
import java.math.BigInteger;

@Singleton
class Engine {
    static final int MINIMUM_BASELINE_WARMUP_TICKS = 3;

    /** After a resume the sidebar/HUD show CALIBRATING this long; bookings stay immediate. */
    static final long CALIBRATING_MILLIS = 2_500L;

    static final String UNCLASSIFIED_LOCAL_DEATH_NOTE = msg("unclassified-local");
    FlowValuator valuationService;
    TransactionClassifier classifier;
    GpManagerConfig config;
    LootPresentationFilterService contributionEligibility;
    List<Session> history = new ArrayList<>();

    /** One home for pending physical evidence; Persistence supplies its owner token. */
    Inbox inbox = new Inbox();

    SessionArchive archive = new SessionArchive(this);

    /**
     * Profile-local date basis; persisted so travelling or changing the host zone cannot shift
     * history.
     */
    String profileTimeZoneId = ZoneId.systemDefault().getId();

    /** Domain revision: every canonical mutation advances it; derived previews bind to it. */
    long revision;

    /** Derived presentation cache; never part of SavedState or an accounting authority. */
    transient MoneySnapshot snapshot;

    synchronized MoneySnapshot summary() {
        return MoneySnapshot.of(this);
    }

    /** Active RuneScape profile key supplied by the persistence coordinator. */
    String profileIdentityKey = "";

    // The general tracker is durable. A custom session is the only temporary
    // owner and takes the active pointer while General is explicitly paused.
    Session generalSession;
    Session customSession;

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

    /**
     * The live context claim and, while an inventory change stabilizes, the one it will settle
     * with.
     */
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
     * hard evidence can. See {@link Inbox}
     * for the same per-source-lifetime pattern applied to consumption/drop
     * intent, and `ConsumptionBurialEngineTest` /
     * `FollowupBoundaryReviewTest#burialCallbackBeforeGameTickBankCloseMustNotLatchOldTransfer`
     * for the regression coverage that locks this in.
     */
    /**
     * Hard TRANSFER from bank-container/menu markContext. Survives soft bank-close and idle pw so a
     * GameTick between bank-container and inventory does not book the deposit as CONSUMPTION.
     * Cleared on settle or TTL expiry.
     */
    /** Remaining ticks for {@link #hasHardTransfer()}. */
    /**
     * Fresh bank-interface / deposit-menu evidence (not sticky loot correlation). Soft-only:
     * cleared unconditionally on bank close unless hard evidence is also live (see {@link
     * #bankClosed()}), so it never survives past the UI closing to poison a later
     * burial/consumption.
     */
    DeathReclaimLifecycle deathReclaim = new DeathReclaimLifecycle();

    /** Item-on-item charge-load intent; only matched settled component losses are neutral. */
    /** Session-owned measured balances; never persisted or shared across owners. */
    MeasuredChargeReadTracker measuredChargeReadTracker = new MeasuredChargeReadTracker();

    ChargeEstimateJournal chargeEstimateJournal = new ChargeEstimateJournal();
    Map<Integer, Long> neutralZoneStoredItems = new HashMap<>();
    boolean neutralZoneTransferActive;
    boolean captureNeutralZoneEntry;
    ContainerSnapshot neutralZoneEntryBaseline;
    int neutralZoneEntryCaptureTicks;
    int neutralZoneRestoreTicks;

    /**
     * Items your own NPC or PvP loot dropped, by ticks left: 100 ticks, the minute loot stays
     * private to you, outliving the 30 s attribution window (owner 1.2).
     */
    /**
     * Ammo fired in the running session, newest last: picking it back up recovers it (owner 1.1).
     */
    /** Item ids a ground Take was clicked for, with the ticks the click stays evidence. */

    /** One counted LOOT/PK_LOOT revenue per encounter id. */
    /** Active clue path for dig/tele/key cost pairing. */
    ClueCostPairing clueCostPairing = new ClueCostPairing();

    /** Key/chest claims (the one durable evidence exception, section H) and their live evidence. */
    KeyClaims claims = new KeyClaims();

    /** Chebyshev tiles — pickup must be near the recorded drop, not name-only. */
    @Inject
    Engine(Valuation valuationService, TransactionClassifier classifier, GpManagerConfig config) {
        this((FlowValuator) valuationService, classifier, config);
    }

    Engine(FlowValuator valuationService, TransactionClassifier classifier, GpManagerConfig config) {
        this.valuationService = valuationService;
        this.classifier = classifier;
        this.config = config;
        inbox.reset = this::clearEphemera;
        geCustody.setBasisLedger(trackedBasis);
        trackedBasis.openReservationLookup = geCustody::latestReservationTime;
    }

    @Inject
    void setContributionEligibility(LootPresentationFilterService contributionEligibility) {
        this.contributionEligibility = contributionEligibility;
    }

    /**
     * Restore using a supplied clock so retention and recovery rules are deterministic in tests.
     */
    synchronized void restore(SavedState state, long now) {
        ground = null;
        history.clear();
        archive.clear();
        savedGrinds.clear();
        profileTimeZoneId = state != null && validZone(state.getProfileTimeZoneId()) ? state.getProfileTimeZoneId()
                        : ZoneId.systemDefault().getId();
        bumpRevision();
        claims.clear();
        // Known-basis coverage restores before any session can notify the observer; a pre-106
        // state starts with no known coverage (existing holdings are unknown, never zero).
        if (state != null) {
            // Saved GE records are clamped once here, so their accessors read fields directly.
            for (GeOffer record : state.getGeCustody()) if (record != null) record.sanitize();
        }
        trackedBasis.restore(state, now);
        generalSession = null;
        customSession = null;
        activeSession = null;
        inbox.drop(DROP_CLICK);
        if (state != null) {
            for (SavedGrind grind : state.getSavedGrinds()) {
                if (grind != null && !grind.getGrindId().isEmpty() && !grind.getName().isEmpty())
                    savedGrinds.add(grind);
            }
            for (Session session : state.getHistory()) {
                if (session != null) history.add(session);
            }
            generalSession = state.generalSession;
            customSession = state.customSession;
            if (generalSession != null) generalSession.grind = false;
            if (customSession != null) customSession.grind = true;
            for (Session session : uniqueSessions()) {
                session.sanitize();
                attachListeners(session);
            }
            activeSession = customSession != null && !customSession.isClosed() ? customSession : generalSession;
            if (activeSession != null && !activeSession.isClosed()) {
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
        // The looting bag's dispenser additions belong to the account that earned them.
        bagAdded.clear();
        bagReward.clear();
        // A restart of the same owner restores its persisted pending death/reclaim evidence.
        deathReclaim.restore(state == null ? null : state.pendingDeathReclaim);
        // Custody lifecycles are profile-scoped financial continuity state.
        geCustody.restore(state, now);
        resetTracking(false);
        archive.trimHistory();
        if (state != null) {
            // Claims settled by a retained transaction are stale duplicates and must never book
            // again.
            claims.restore(state, uniqueSessions());
            archive.compactAfterRestore(now);
        }
    }

    /**
     * Brings retained automatic rows booked before 1.5.0's rules up to them (owner live
     * 2026-10-16): an unpriced item takes its alch value or reads as worthless; tablet breaks, alchs and Bones to Peaches read
     * as their spell, and a tablet left in Review books like a fresh one; coins picked up near a GE
     * sale leave Review (owner 2026-10-16). Owner decisions and
     * corrections are never touched, and a second pass changes nothing.
     */
    synchronized void recheck(java.util.function.IntUnaryOperator alchPrice) {
        boolean changed = false;
        var drops = new ArrayList<Runnable>();
        for (Session session : uniqueSessions()) {
            for (Transaction row : session.getTransactions()) {
                if (row.getCorrection() != Correction.AUTO || Contribution.isSplit(row)) continue;
                if (row.getType() == UNCERTAIN && msg("grand-exchange-collect").equals(row.getNote())
                        && row.getFlows().size() == 1 && row.getFlows().get(0).itemId == ItemID.COINS
                        && geCustody.claimOldChange(row.getFlows().get(0).valueDelta, row.timestampEpochMillis)) {
                    // A bank-paid buy's change is the player's own coins coming back (owner 1.5.1).
                    row.type = TransactionType.TRANSFER;
                    row.counted = false;
                    row.note = msg("grand-exchange-custody");
                    row.confidence = Confidence.CONFIRMED;
                    row.explanation = msg("ownership-neutral-grand");
                    session.changed(row, Correction.AUTO, row.getFlows());
                    changed = true;
                }
                // Neutral GE change stays only as an audit row, like a live custody move.
                if (row.getType() == TransactionType.TRANSFER && msg("grand-exchange-custody").equals(row.getNote())
                        && row.getFlows().size() == 1 && row.getFlows().get(0).itemId == ItemID.COINS) {
                    if (!config.keepTransferAuditRows()) drops.add(() -> session.drop(row));
                    continue;
                }
                if (preCollectGate(row)) {
                    // Before the GE-collect gate (owner live 2026-10-16) a coin pickup within two
                    // hours of a sale went to Review as Grand Exchange; it books as one today.
                    row.type = classifier.classify(row.context, row.flows);
                    row.counted = row.type != TransactionType.TRANSFER;
                    row.note = "";
                    row.confidence = confidenceFor(row.context, row.type);
                    row.explanation = explanationFor(row.context, row.type, row.counted);
                    session.changed(row, Correction.AUTO, row.getFlows());
                    changed = true;
                    continue;
                }
                boolean priced = false;
                for (Flow flow : row.getFlows()) {
                    if (flow.getPriceSource() != PriceSource.UNPRICED) continue;
                    // An item that had no price now has one: its alch value, worth nothing, or a share
                    // of the whole (half a pie, owner 1.5.1).
                    for (Flow now : valuationService.value(Map.of(flow.itemId, flow.quantityDelta))) {
                        if (now.getPriceSource() != PriceSource.UNPRICED) {
                            flow.unitPrice = now.unitPrice;
                            flow.valueDelta = now.valueDelta;
                            flow.priceSource = now.getPriceSource();
                            priced = true;
                        }
                    }
                }
                if (priced) {
                    // A new list, so the row's derived views are rebuilt.
                    row.flows = new ArrayList<>(row.flows);
                    row.recalculate();
                    changed = true;
                }
                String spell = row.getActionKind() != null || row.getType() != CONSUMPTION && row.getType() != UNCERTAIN
                                        || has(row.getNote(), "Dropped", "Destroyed") ? null : spellOf(row, alchPrice);
                if (spell == null) continue;
                if (ReviewRules.needsOwnerDecision(row)) {
                    row.type = CONSUMPTION;
                    row.counted = true;
                    row.confidence = confidenceFor(row.context, CONSUMPTION);
                    row.explanation = explanationFor(row.context, CONSUMPTION, true);
                }
                row.setActionKind(CAST);
                row.setActionLabel(ActionLabel.of(spell));
                changed = true;
            }
        }
        drops.forEach(Runnable::run);
        if (changed || !drops.isEmpty()) bumpRevision();
    }

    /**
     * A Review row the old rule booked for a sale seen in the last two hours: coins gained with no
     * Collect click, which today's rule books as a pickup. A real collect is always MARKET.
     */
    static boolean preCollectGate(Transaction row) {
        if (row.getType() != UNCERTAIN || row.getContext() == MARKET || !"Grand Exchange".equals(row.getNote())
                || !msg("grand-exchange-movement").equals(row.getExplanation())
                || row.getFlows().isEmpty()) return false;
        for (Flow flow : row.getFlows()) if (flow.itemId != ItemID.COINS || !flow.isGain()) return false;
        return true;
    }

    /**
     * The spell an unlabelled row's shape proves: a broken tablet; Bones to Peaches or Bananas
     * (runes and bones for fruit); an alch (runes and one item for coins, High when the coins are
     * its alch price).
     */
    static String spellOf(Transaction row, java.util.function.IntUnaryOperator alchPrice) {
        String tablet = null;
        boolean nature = false;
        Flow item = null;
        int items = 0;
        long coins = 0L;
        String gain = "";
        var gains = new HashSet<Integer>();
        for (Flow flow : row.getFlows()) {
            if (flow.isGain()) gains.add(flow.itemId);
            String name = lower(flow.itemName);
            if (flow.isCost()) {
                if (Spells.named(name) != null) tablet = Spells.named(name);
                else if (ActionReader.isRune(name)) nature |= flow.itemId == ItemID.NATURERUNE;
                else {
                    item = flow;
                    items++;
                }
            } else if (flow.itemId == ItemID.COINS) coins += flow.quantityDelta;
            else gain = gain.isEmpty() || gain.equals(name) ? name : "?";
        }
        if (tablet != null)
            return Spells.product(tablet).containsAll(gains) ? tablet : null;
        if (!nature) return null;
        if (gain.equals("peach") || gain.equals("banana"))
            return gain.equals("peach") ? "Bones to Peaches" : "Bones to Bananas";
        return items != 1 || coins <= 0L || !gain.isEmpty() ? null
                : coins == -item.quantityDelta * alchPrice.applyAsInt(item.itemId) ? "High Level Alchemy"
                        : "Low Level Alchemy";
    }

    synchronized void ensureSession(long now) {
        if (generalSession == null || generalSession.isClosed()) {
            // Overall is the durable default owner display name; it must still
            // accept automatic activity detection just as the former General default
            // session did.
            generalSession = newSession(Session.DURABLE_OWNER_NAME, now, false);
        }
        if (customSession == null || customSession.isClosed()) {
            customSession = null;
            activeSession = generalSession;
        }
    }

    /**
     * New session (owner 1.5.2): Live's Net, Recent, GP/h and clock start at zero and a set target
     * counts again with it, while the run keeps every booking and its whole total.
     */
    synchronized void newLiveSession(long now) {
        if (activeSession == null) return;
        Session run = activeSession;
        long elapsed = run.getElapsedMillis(now);
        run.rebaseProfitTarget(getMetrics(now).net, elapsed);
        run.rearmTimeTarget(now);
        run.sessionStartAt = now;
        run.sessionStartMillis = elapsed;
        bumpRevision();
    }

    /**
     * Back to the whole run on Live, from the run total under the Net (owner 1.5.2): the session
     * ends, nothing else changes. A running target is its own session, so it stays.
     */
    synchronized void showWholeRun() {
        if (activeSession == null || activeSession.getProfitTargetGp() != null
                || activeSession.getActiveTimeTargetMillis() != null) return;
        activeSession.sessionStartAt = null;
        bumpRevision();
    }

    /**
     * Reset free-play (owner 1.5.2): the run so far moves to Grinds history, nothing deleted,
     * and a new Free play starts at zero with the same milestone. Not during a Grind.
     */
    synchronized boolean startFreePlayOver(long now) {
        if (customSession != null || generalSession == null) return false;
        becomeFreePlay(newSession(Session.DURABLE_OWNER_NAME, now, false), now);
        return true;
    }

    /**
     * Restore (owner 1.5.2): a past Free play run becomes Free play again and the current one takes
     * its place in history, so the two can swap back; nothing is deleted. Not during a Grind.
     */
    synchronized boolean restoreFreePlay(String sessionId, long now) {
        Session old = historySession(sessionId);
        if (customSession != null || generalSession == null || old == null || old.grind) return false;
        history.remove(old);
        old.reopen(now);
        old.rename(Session.DURABLE_OWNER_NAME);
        becomeFreePlay(old, now);
        return true;
    }

    /**
     * The current Free play moves to history as "Free play" (an empty one is dropped) and {@code
     * next} takes over, keeping the milestone and a manual pause.
     */
    void becomeFreePlay(Session next, long now) {
        Session done = generalSession;
        clearGeCollect();
        done.rename("Free play");
        done.close(max(done.startedAtEpochMillis, now));
        if (!done.getTransactions().isEmpty()) history.add(0, done);
        archive.trimHistory();
        generalSession = activeSession = next;
        next.milestoneGp = done.milestoneGp;
        if (done.getPauseReason() == MANUAL) next.pause(now, MANUAL);
        resetTracking(true);
        bumpRevision();
    }

    /** Free play's Net milestone (owner 1.5.2): it counts Free play's whole Net; null clears it. */
    synchronized boolean setMilestone(Long gp) {
        if (generalSession == null || isCustomActive()) return false;
        generalSession.milestoneGp = gp == null || gp <= 0L ? null : gp;
        bumpRevision();
        return true;
    }

    synchronized void startCustom(String name, long now) {
        ensureSession(now);
        if (customSession != null) {
            // This transition needs an explicit Finish custom first. Starting
            // another custom session must never silently archive the current one.
            return;
        }
        clearGeCollect();
        if (!generalSession.paused) generalSession.pause(now, CUSTOM_SESSION);
        customSession = newSession(name, now, true);
        activeSession = customSession;
        resetTracking(true);
    }

    synchronized boolean finishCustom(long now) {
        if (customSession == null) return false;
        long closeAt = max(customSession.startedAtEpochMillis, now);
        clearGeCollect();
        customSession.close(closeAt);
        history.add(0, customSession);
        archive.trimHistory();
        customSession = null;
        activeSession = generalSession;
        if (generalSession != null && generalSession.getPauseReason() == CUSTOM_SESSION) {
            generalSession.resume(closeAt);
        }
        resetTracking(true);
        return true;
    }

    synchronized boolean isCustomActive() {
        return customSession != null && activeSession == customSession && !customSession.isClosed();
    }

    synchronized void pauseForLifecycle(long now) {
        if (live()) activeSession.pause(now, LIFECYCLE);
        resetTracking(false);
    }

    /**
     * True on a separate-economy world (Leagues, Deadman, beta, tournament, speedrunning, no-save):
     * tracking pauses there and neither starts nor resumes until it is left (owner 1.5.0).
     */
    boolean separateWorld;

    /** Holds the world pause; any existing pause, Manual included, is left as it is. */
    synchronized void pauseForWorld(long now) {
        separateWorld = true;
        if (live()) {
            activeSession.pause(now, WORLD);
            resetTracking(false);
        }
    }

    /** Resumes an automatic pause of one of these reasons; Manual Pause always needs the owner. */
    synchronized boolean resume(long now, PauseReason... reasons) {
        if (activeSession != null && activeSession.paused && !separateWorld
                && Arrays.asList(reasons).contains(activeSession.getPauseReason())) {
            activeSession.resume(now);
            arm(now);
            return true;
        }
        return false;
    }

    /**
     * Create the durable General tracker from meaningful gameplay when automatic startup is
     * enabled, on a new install and after a factory reset alike. Never resumes Manual Pause; Idle
     * and Recovery resume via {@link #resume}. Not from login alone, unless the plugin's
     * Start-tracking-on-login option asks for it at the first login (owner live 2026-10-13).
     *
     * @return true when a new General session was created
     */
    synchronized boolean autoStart(long now) {
        if (!config.autoStartSession() || separateWorld) return false;
        if (customSession != null && !customSession.isClosed()) return false;
        if (generalSession != null && !generalSession.isClosed()) return false;
        ensureSession(now);
        arm(now);
        return true;
    }

    /** Fresh tracking: clear the evidence and calibrate the rate from now. */
    void arm(long now) {
        resetTracking(true);
        calibratingUntil = now + CALIBRATING_MILLIS;
    }

    /** AFK pause: only a running session pauses, from now, so the timeout itself still counts. */
    synchronized void pauseIdle(long now) {
        if (live()) {
            activeSession.pause(now, IDLE);
            resetTracking(false);
        }
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
            arm(now);
        } else if (activeSession.paused) {
            activeSession.resume(now);
            arm(now);
        } else {
            activeSession.pause(now, MANUAL);
            autoStartAtEpochMillis = 0L;
            resetTracking(false);
        }
    }

    /** A factory reset resumes tracking by itself a few seconds later, unless stopped first. */
    synchronized void tickAutoStart(long now) {
        if (autoStartAtEpochMillis <= 0L || now < autoStartAtEpochMillis) return;
        autoStartAtEpochMillis = 0L;
        if (activeSession == null) {
            ensureSession(now);
            resetTracking(true);
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
     * Compacts closed sessions strictly older than {@code days}; a zero window means forever and
     * performs no compaction.
     *
     * @return receipt rows compacted
     */
    synchronized int compactOlderThan(int days, long now) {
        return archive.compactOlderThan(days, now);
    }

    /** Configured UTC retention maintenance used by the game-tick lifecycle. */
    synchronized int maintainRetention(long now) {
        return archive.maintainRetention(now);
    }

    List<Session> uniqueSessions() {
        var unique = new LinkedHashMap<String, Session>();
        for (Session session : history) addUnique(unique, session);
        addUnique(unique, generalSession);
        addUnique(unique, customSession);
        addUnique(unique, activeSession);
        return new ArrayList<>(unique.values());
    }

    static void addUnique(Map<String, Session> unique, Session session) {
        if (session != null) unique.putIfAbsent(session.getId(), session);
    }

    /**
     * Starts a login/session warm-up. Snapshots observed during this period are baseline material
     * only and can never become profit.
     */
    synchronized void beginPriming() {
        resetTracking(true);
    }

    /**
     * Sets an immediate trusted baseline. Primarily useful for deterministic tests and callers
     * which already know all tracked containers are loaded.
     */
    synchronized void setBaseline(ContainerSnapshot snapshot) {
        baseline = snapshot;
        baselinePriming = false;
        clearPriming();
    }

    synchronized void markDirty() {
        if (baselinePriming) return;
        dirty = true;
        pendingStableTicks = 0;
        // Animation lag: refresh consume/drop TTLs when the inventory finally moves so
        // bury/drink intents survive until stabilization rather than expiring idle.
        inbox.refresh(CONSUME, 4);
        inbox.refresh(SPENT_CONSUME, 4);
        inbox.refresh(DROP_CLICK, max(4, config.stabilizationTicks() + 2));
        capturePendingContext();
        if (bankOpen() && (dirty || pendingSnapshot != null)) {
            pendingClue().transfer = true;
            if (pendingClue().context == GENERIC) {
                pendingClue().context = Context.TRANSFER;
                pendingClue().note = "Bank transfer";
            }
        }
    }

    /** Owner-scoped live and stabilizing claims; HF and BO are independent sources. */
    Clue liveClue() {
        return inbox.clue(LIVE_CLAIM);
    }

    Clue pendingClue() {
        return inbox.clue(PENDING_CLAIM);
    }

    int clockLeft() {
        return inbox.left(CLOCK);
    }

    boolean hasHardTransfer() {
        return inbox.peek(HARD_TRANSFER) != null;
    }

    boolean bankOpen() {
        return inbox.peek(BANK_UI) != null;
    }

    /** The still-open consume command, from the sole pending-evidence store. */
    Observation consumeIntent() {
        return inbox.peek(CONSUME);
    }

    /**
     * Records a short-lived consume hint; accounting still waits for a stable inventory delta.
     * {@code itemId} may be {@code < 0} (open intent) when the menu did not expose an id — matching
     * then accepts a dose/partial leftover, a pure cost, or any inventory cost while intent is
     * armed so drink/bury coalesced with harvest gains still books Used/lost.
     *
     * @param destroy when true, irreversible Destroy menu — presentation stamps Destroyed; never
     *     registers recoverable own-drop.
     */
    synchronized void noteConsume(int itemId, int ticks, boolean destroy) {
        noteConsume(itemId, ticks, destroy, null);
    }

    /**
     * @param actionKind menu verb behind the click (Drink/Eat/Bury/…) or null when the option does
     *     not evidence a specific action. Presentation only.
     */
    synchronized void noteConsume(int itemId, int ticks, boolean destroy, ActionKind actionKind) {
        noteConsume(itemId, ticks, destroy, actionKind, null);
    }

    /** Exact spell text is an optional presentation hint; it is not part of action matching. */
    synchronized void noteConsume(int itemId, int ticks, boolean destroy, ActionKind actionKind,
            ActionLabel actionLabel) {
        // Eat/Bury/Drink/Cast/etc. — clear any pending Drop intent for the same click window.
        inbox.drop(DROP_CLICK);
        int safeTicks = max(1, ticks);
        Observation prior = consumeIntent();
        if (prior != null && itemId < 0 && prior.item >= 0) {
            // Chat/menu reinforcement without an id must not wipe a resolved item id,
            // flip a Destroy stamp to Used, or replace the resolved item's proven verb.
            // Exact spell text may refresh the label; a click that proves no name must not
            // erase the name already proven for this bounded interaction.
            inbox.refresh(CONSUME, safeTicks);
            if (actionLabel != null) prior.label = actionLabel;
            return;
        }
        if (prior != null && itemId >= 0 && prior.item >= 0 && prior.item != itemId && pendingSnapshot != null
                && baseline != null && pendingSnapshot.quantityOf(prior.item) < baseline.quantityOf(prior.item)) {
            // The earlier click's item already left the pack and its change has not settled: it
            // keeps its own evidence while the new click arms (owner live 2026-10-16: an Eat two
            // ticks after a Break left the tablet in Review).
            inbox.drop(SPENT_CONSUME);
            prior.kind = SPENT_CONSUME;
        }
        Observation e = inbox.put(CONSUME, itemId, 1L, safeTicks - 1, null);
        // Prefer the pending (dirty) snapshot so a mid-window pickup is visible before the settle.
        ContainerSnapshot live = pendingSnapshot != null ? pendingSnapshot : baseline;
        long held = itemId < 0 || live == null ? -1L : live.quantityOf(itemId);
        // Keep the window's high-water: a later click re-arms at the current count, but a bite
        // that landed between the clicks must stay measurable against the stack's top (owner
        // 2026-10-12: withdraw-all then an eat while the clicks kept coming).
        if (prior != null && prior.item == itemId && prior.held > held) held = prior.held;
        e.held = held;
        e.flag = destroy;
        e.action = actionKind;
        e.label = actionLabel;
    }

    /**
     * Clears only pending, unbooked action evidence at the profile/owner fence. Booked schema-105
     * action labels are durable metadata on canonical transactions and are never erased here;
     * lifecycle paths also reset pending consume evidence through {@link #resetTracking(boolean)}.
     */
    synchronized void clearActionEvidence() {
        inbox.drop(CONSUME, SPENT_CONSUME);
    }

    /**
     * Chat confirmation ("You eat the chicken.") carries the verb the line proves: a chat that
     * arrives after the menu clicks lapse still names Ate/Drank instead of a bare "Used" (owner
     * 2026-10-12). A verb the menu already proved is never replaced.
     */
    synchronized void extendConsume(int ticks, ActionKind actionKind) {
        int safeTicks = max(1, ticks);
        if (consumeIntent() != null) {
            inbox.refresh(CONSUME, safeTicks);
            if (actionKind != null && consumeIntent().action == null)
                consumeIntent().action = actionKind;
            return;
        }
        noteConsume(-1, safeTicks, false, actionKind);
    }

    /** Player-initiated Drop action: the confirmed inventory removal books as Dropped. */
    synchronized void noteDrop(int itemId, int ticks) {
        if (itemId < 0) return;
        inbox.drop(CONSUME);
        inbox.put(DROP_CLICK, itemId, 1L, max(1, ticks) - 1, null);
    }

    /**
     * Set-up or Pick-up of a dwarf multicannon: its parts are yours wherever they stand (Nulodion
     * replaces a lost one free), so moving them is neutral (owner 1.3.3). The base-to-furnace
     * assembly runs for up to ~12 ticks after the click and its auto-load follows right behind,
     * so the custody must outlive them both (owner live 2026-10-13: 15 ticks expired right at the
     * furnace's own settle and booked it as a loss).
     */
    synchronized void moveCannon() {
        inbox.put(CANNON, -1, 1L, 40, null);
    }

    /** Fire or Load: the balls it takes (granite first) are remembered until it is picked up. */
    synchronized void loadCannon(int ticks) {
        if (baseline != null)
            noteDrop(baseline.quantityOf(ItemID.GRANITE_CANNONBALL) > 0 ? ItemID.GRANITE_CANNONBALL
                            : ItemID.MCANNONBALL, ticks);
    }

    /**
     * Sailing's additional ball tiers: bronze, iron, mithril, adamant, rune and dragon. The plain
     * steel tier is the classic id-2 ball and granite also serves ship cannons, so both already
     * sit in {@link #cannonball}; the exact ids here add the chainshot and incendiary types, so a
     * ship-cannon volley reads the same as a dwarf load (owner 1.3.4 ship sweep).
     */
    private static final Set<Integer> SAIL_CANNONBALLS = Set.of(ItemID.BRONZE_CANNONBALL, ItemID.IRON_CANNONBALL,
                    ItemID.MITHRIL_CANNONBALL, ItemID.ADAMANT_CANNONBALL, ItemID.RUNE_CANNONBALL,
                    ItemID.DRAGON_CANNONBALL, ItemID.BRONZE_CHAINSHOT_CANNONBALL, ItemID.IRON_CHAINSHOT_CANNONBALL,
                    ItemID.STEEL_CHAINSHOT_CANNONBALL, ItemID.MITHRIL_CHAINSHOT_CANNONBALL,
                    ItemID.ADAMANT_CHAINSHOT_CANNONBALL, ItemID.RUNE_CHAINSHOT_CANNONBALL,
                    ItemID.DRAGON_CHAINSHOT_CANNONBALL, ItemID.BRONZE_INCENDIARY_CANNONBALL,
                    ItemID.IRON_INCENDIARY_CANNONBALL, ItemID.STEEL_INCENDIARY_CANNONBALL,
                    ItemID.MITHRIL_INCENDIARY_CANNONBALL, ItemID.ADAMANT_INCENDIARY_CANNONBALL,
                    ItemID.RUNE_INCENDIARY_CANNONBALL, ItemID.DRAGON_INCENDIARY_CANNONBALL);

    static boolean cannonball(int itemId) {
        return itemId == ItemID.MCANNONBALL || itemId == ItemID.GRANITE_CANNONBALL || SAIL_CANNONBALLS.contains(itemId);
    }

    /** The four dwarf multicannon parts, unnoted ids as placed (TWPART1..4). */
    static boolean cannonPart(int itemId) {
        return itemId == ItemID.TWPART1 || itemId == ItemID.TWPART2 || itemId == ItemID.TWPART3
                || itemId == ItemID.TWPART4;
    }

    /**
     * A pickup that went straight into an open bag reads as an inventory gain (owner 1.2): the
     * baseline forgets it, so the next settle books it like any pickup.
     */
    /** Money evidence is owner-checked and consumed under the booking monitor. */
    synchronized void bagged(Observation e, long qty) {
        if (e.owner == inbox.owner) bagged(e.item, inbox.take(e.kind, e.item, qty, v -> v == e));
    }

    synchronized void fee(Observation e, long now) {
        if (e.owner == inbox.owner)
            bookDeathFee(inbox.take(Inbox.FEE, -1, e.qty, v -> v == e), e.note, now);
    }

    synchronized void bagged(int itemId, long quantity) {
        if (baseline == null || baselinePriming || quantity <= 0L) return;
        var held = new HashMap<>(baseline.quantities);
        held.merge(itemId, -quantity, Long::sum);
        baseline = new ContainerSnapshot(held);
        markDirty();
    }

    /** Clears the export identity when persistence invalidates the active account. */
    synchronized void clearIdentity() {
        profileIdentityKey = "";
    }

    /** Binds the identity and restores its state under the same engine lock. */
    synchronized void restoreProfile(String identityKey, SavedState state, long now) {
        String next = nonBlank(identityKey, "");
        Gson gson = JsonCodec.gson();
        SavedState detached = state == null ? null : gson.fromJson(gson.toJson(state), SavedState.class);
        String stateOwner = detached == null ? null : detached.ownerKey;
        if (!next.isEmpty() && !blank(stateOwner) && !next.equals(stateOwner)) {
            throw new IllegalArgumentException(msg("savedstate-owner"));
        }
        Engine staged = prepareProfile(next, detached, now);
        installProfile(staged, next);
    }

    /** The bound profile's state as saved, for a backup copy of the save file. */
    synchronized SavedState exportState() {
        SavedState snapshot = savedState();
        snapshot.setOwnerKey(profileIdentityKey);
        return snapshot;
    }

    Engine prepareProfile(String identityKey, SavedState state, long now) {
        // An isolated engine sharing this one's valuation, classifier and config.
        var staged = new Engine(valuationService, classifier, config);
        staged.setContributionEligibility(contributionEligibility);
        staged.profileIdentityKey = identityKey;
        staged.restore(state, now);
        return staged;
    }

    void installProfile(Engine staged, String identityKey) {
        // Allocate copies and attach listeners before changing live fields. The
        // normal restore/migration path has already completed on the isolated engine.
        var nextHistory = new ArrayList<Session>(staged.history);
        var nextSessions = new ArrayList<Session>(staged.uniqueSessions());
        for (Session session : nextSessions) attachListeners(session);
        // Clear identity-scoped transient evidence while durable profile fields
        // still belong to the previous owner. No reader can observe the swap
        // because agl/restoreProfile hold this engine's monitor.
        inbox.drop(DROP_CLICK);
        deathReclaim.reset();
        // The looting bag's dispenser additions belong to the account that earned them.
        bagAdded.clear();
        bagReward.clear();
        clearGeCollect();
        resetTracking(false);
        history = nextHistory;
        savedGrinds.clear();
        savedGrinds.addAll(staged.savedGrinds);
        archive.adoptFrom(staged.archive);
        profileTimeZoneId = staged.profileTimeZoneId;
        bumpRevision();
        claims.adoptFrom(staged.claims);
        deathReclaim.adoptFrom(staged.deathReclaim);
        geCustody.adoptFrom(staged.geCustody);
        trackedBasis.adoptFrom(staged.trackedBasis);
        ground = null;
        generalSession = staged.generalSession;
        customSession = staged.customSession;
        activeSession = staged.activeSession;
        profileIdentityKey = orEmpty(identityKey);
    }

    synchronized void markContext(Context newContext, int ticks, String note) {
        if (newContext != LOOT && newContext != PK_LOOT) claims.cancelChest();
        if (newContext == Context.TRANSFER || newContext == MARKET) inbox.drop(LOAD_TARGET);
        supersedeDeathMarker(newContext, note);
        if (newContext == Context.TRANSFER) {
            // Stale Eat/Bury intents must not reclassify bank deposits as Used/lost.
            inbox.drop(CONSUME, SPENT_CONSUME, DROP_CLICK);
            noteHardTransfer(max(1, ticks));
        }
        setContext(newContext, ticks, note, emptyMap(), null);
    }

    /**
     * Bank interface is open on this tick. Soft UI-open refresh only — does not by itself prove a
     * deposit/withdraw movement.
     */
    synchronized void bankOpened(int ticks) {
        claims.cancelChest();
        inbox.drop(LOAD_TARGET);
        supersedeDeathMarker(Context.TRANSFER, "Bank transfer");
        // Edge-trigger only. Live GameTick refreshes this every tick while the bank
        // widget/container is open — wiping Eat/Drink/Bury intents each tick made
        // potato-field sessions book every inventory delta as soft TRANSFER (Used/lost
        // ×0, no harvest rows) whenever bank detection stuck or fluttered.
        boolean newlyOpened = !bankOpen();
        inbox.ttl(BANK_UI, Integer.MAX_VALUE);
        if (newlyOpened) {
            inbox.drop(CONSUME, SPENT_CONSUME, DROP_CLICK);
        }
        inbox.ttl(SOFT_TRANSFER, max(inbox.left(SOFT_TRANSFER), max(1, ticks)));
        setContext(Context.TRANSFER, ticks, "Bank transfer", emptyMap(), null);
    }

    /**
     * Bank interface closed this tick. Clears sticky soft TRANSFER hints so burial after close is
     * not classified as TRANSFER when the inventory callback raced ahead of this tick. Genuine
     * deposits keep pending TRANSFER only when hard bank-container/menu evidence was attached to
     * this pending change — or when hard evidence is still live and the inventory callback arrives
     * after close.
     */
    synchronized void bankClosed() {
        inbox.drop(BANK_UI);
        // Soft UI-open refresh only. Hard deposit/withdraw TTL must survive close so a
        // GameTick between bank-container evidence and inventory dirty stays TRANSFER.
        if (!hasHardTransfer()) inbox.drop(SOFT_TRANSFER);
        if (dirty || pendingSnapshot != null) {
            if (!pendingClue().hard && !hasHardTransfer()) {
                // Soft UI-open latch only — clear so burial after close is CONSUMPTION.
                // Genuine deposits attach hard evidence via bank-container/menu markContext.
                pendingClue().transfer = false;
                if (pendingClue().context == Context.TRANSFER) pendingClue().clear();
            } else if (hasHardTransfer() && !pendingClue().hard) {
                // Inventory already dirty from soft open; promote with surviving hard evidence.
                latchHardTransfer();
            }
            return;
        }
        if (liveClue().context == Context.TRANSFER) {
            // Drop the soft UI-open note/ticks; live hard evidence keeps TRANSFER for a late
            // inventory callback.
            liveClue().clear();
            liveClue().context = hasHardTransfer() ? Context.TRANSFER : GENERIC;
            inbox.drop(CLOCK);
        }
    }

    void noteHardTransfer(int ticks) {
        inbox.drop(LOAD_TARGET);
        int safeTicks = max(1, ticks);
        inbox.ttl(SOFT_TRANSFER, max(inbox.left(SOFT_TRANSFER), safeTicks));
        inbox.ttl(HARD_TRANSFER, max(inbox.left(HARD_TRANSFER), safeTicks));
        if (dirty || pendingSnapshot != null) latchHardTransfer();
    }

    void latchHardTransfer() {
        pendingClue().transfer = true;
        pendingClue().hard = true;
        if (pendingClue().context == GENERIC || Context.TRANSFER.priority > pendingClue().context.priority) {
            // Keep the producer's own note (minigame wipe, neutral zone, death) — a
            // generic "Bank transfer" would otherwise win the priority race and mislabel
            // every non-bank transfer row.
            String note = liveClue().context == Context.TRANSFER && !blank(liveClue().note) ? liveClue().note
                            : "Bank transfer";
            applyPendingContext(Context.TRANSFER, note, emptyMap(), null);
        } else if (pendingClue().context == Context.TRANSFER) {
            pendingClue().transfer = true;
            if (empty(pendingClue().note)) pendingClue().note = "Bank transfer";
        }
    }

    /**
     * Soft evidence: inventory became dirty while the bank UI was open. Survives bank close for
     * genuine deposits. Cleared by {@link #bankClosed()} when the pending change was only a stale
     * latch after the UI was already gone (plugin closes before dirty when {@code !isBankOpen()}).
     */
    synchronized void markLoot(Map<Integer, Long> expectedLoot, int ticks, String note, String activityName) {
        Map<Integer, Long> safeExpectedLoot = positiveEntries(expectedLoot);
        if (!safeExpectedLoot.isEmpty()) {
            loot(safeExpectedLoot, ticks, note, activityName, LOOT, null);
        }
        setContext(LOOT, ticks, note, safeExpectedLoot, null);
    }

    /**
     * Each private drop proves its own remaining quantity; attribution has the old shorter window.
     */
    void loot(Map<Integer, Long> quantities, int ticks, String note, String name, Context context, String id) {
        quantities.forEach((item, qty) -> {
                    Observation e = inbox.add(PRIVATE_LOOT, item, qty, 99, orEmpty(note));
                    e.at = inbox.now + max(1, ticks) - 1;
                    e.name = nonBlank(name, "General");
                    e.context = context;
                    e.id = id;
                });
    }

    void setContext(Context newContext, int ticks, String note, Map<Integer, Long> expectedLoot, String encounterId) {
        Context safeContext = newContext == null ? GENERIC : newContext;
        Map<Integer, Long> safeExpectedLoot = positiveEntries(expectedLoot);
        supersedeDeathMarker(safeContext, note);
        boolean replace = clockLeft() <= 0 || safeContext.priority > liveClue().context.priority;
        if (replace || safeContext == liveClue().context)
            inbox.ttl(CLOCK, max(replace ? 0 : clockLeft(), max(1, ticks)));
        liveClue().offer(safeContext, note, safeExpectedLoot, encounterId, replace);
        if (dirty || pendingSnapshot != null)
            applyPendingContext(safeContext, note, safeExpectedLoot, encounterId);
    }

    /**
     * A death marker is a short-lived reconciliation hint, not a durable activity context. An
     * explicit later activity must own the next measured change even though TRANSFER has a higher
     * general context priority.
     */
    void supersedeDeathMarker(Context safeContext, String note) {
        boolean newDeathMarker = safeContext == Context.TRANSFER && isDeathHeldNote(note);
        boolean activeDeathMarker = liveClue().context == Context.TRANSFER && isDeathHeldNote(liveClue().note);
        boolean pendingDeathMarker = pendingClue().context == Context.TRANSFER && isDeathHeldNote(pendingClue().note);
        if (!newDeathMarker && safeContext != GENERIC && (activeDeathMarker || pendingDeathMarker)) {
            if (activeDeathMarker) {
                liveClue().clear();
                inbox.drop(CLOCK);
            }
            if (pendingDeathMarker) pendingClue().clear();
            inbox.drop(SOFT_TRANSFER);
            clearHardTransfer();
            deathReclaim.cancelDeathWipe();
        }
    }

    synchronized void markPkLoot(Map<Integer, Long> expectedLoot, int ticks, long now) {
        if (activeSession == null && !autoStart(now)) return;
        if (!live()) return;
        Map<Integer, Long> safeExpectedLoot = positiveEntries(expectedLoot);
        String encounter = activeSession.addEncounter(true, now);
        if (activeSession.getMode() != SessionMode.GENERAL) {
            activeSession.attachRecentCosts(encounter, now, 90_000L);
        }
        String note = "PK loot";
        if (!safeExpectedLoot.isEmpty()) {
            loot(safeExpectedLoot, ticks, note, "PKing", PK_LOOT, encounter);
        }
        setContext(PK_LOOT, ticks, note, safeExpectedLoot, encounter);
    }

    /**
     * Local unsafe PvM death with a separate canonical inventory/equipment ownership snapshot. The
     * snapshot is reconciled against measured negative flows; presentation evidence remains
     * explanation-only.
     */
    synchronized void markPvmDeath(int ticks, Map<Integer, Long> heldItemsAtDeath) {
        markContext(Context.TRANSFER, max(1, ticks), msg("death-items"));
        // Start after the transfer marker so this new death cannot cancel its own
        // bounded whitelist when strong-context supersession is evaluated.
        deathReclaim.onPvmDeath(heldItemsAtDeath);
    }

    /**
     * Local death with no confident PvM / PK classification. Drop stale transfer and action
     * evidence so its inventory wipe is booked from ordinary loss evidence.
     */
    synchronized void markUnknownDeath() {
        claims.cancelChest();
        deathReclaim.reset();
        inbox.drop(CONSUME, SPENT_CONSUME, DROP_CLICK);
        clearHardTransfer();
        inbox.drop(SOFT_TRANSFER);
        inbox.drop(BANK_UI);
        inbox.ttl(CLOCK, max(6, config.stabilizationTicks() + 4));
        liveClue().clear();
        liveClue().note = UNCLASSIFIED_LOCAL_DEATH_NOTE;
        if (dirty || pendingSnapshot != null) {
            pendingClue().clear();
            pendingClue().note = UNCLASSIFIED_LOCAL_DEATH_NOTE;
        }
    }

    /**
     * Player interacted with a retrieval service (gravestone, Death, boss NPC or chest).
     *
     * @return true when the reclaim window was armed; ambiguous targets (a bare "Chest", a
     *     gravestone) only arm while a local PvM death is awaiting reclaim
     */
    synchronized boolean noteReclaimIntent(Service service, int ticks) {
        return deathReclaim.noteReclaimIntent(service, ticks);
    }

    /** Current presentation read model for the local death-reclaim indicator. */
    synchronized DeathReclaimStatus deathStatus() {
        return new DeathReclaimStatus(deathReclaim.isAwaitingReclaim(), deathReclaim.isReclaimArmed(),
                deathReclaim.outstandingItemCount(), deathReclaim.ageTicks());
    }

    static Transaction first(Transaction... candidates) {
        for (Transaction candidate : candidates) {
            if (candidate != null) return candidate;
        }
        return null;
    }

    /**
     * Remove measured death-owned returns before minimum-value and ordinary gain classification.
     * Source-backed loot quantities are protected first; unmatched gains and all non-coin costs
     * stay in the ordinary settle. A fee is booked only from an observed coin loss with a live
     * service interaction or a matched return in the same settle.
     */
    Transaction settleReclaim(List<Flow> flows, long now, Map<Integer, Long> observedLootQuantities,
            boolean allowObservedFee) {
        if (empty(flows) || !deathReclaim.isAwaitingReclaim() && !deathReclaim.isReclaimArmed())
            return null;
        Service service = deathReclaim.reclaimService;
        Map<Integer, Long> sourceBacked = positiveEntries(observedLootQuantities);
        // Source-backed loot quantities are protected first.
        List<Flow> returned = claim(flows, Flow::isGain, (flow, quantity) -> deathReclaim.matchReturnedItem(flow.itemId,
                                        quantity - take(sourceBacked, flow.itemId, quantity)));
        long coinsPaid = lossQuantities(flows, itemId -> itemId == ItemID.COINS).getOrDefault(ItemID.COINS, 0L);
        boolean hasOtherLoss = hasLoss(flows, itemId -> itemId != ItemID.COINS);
        Transaction recovered = null;
        if (!returned.isEmpty()) {
            String source = service == null ? msg("gravestone-retrieval") : service.interactable;
            recovered = bookNeutral(now, msg("death-reclaim-items"), "Death reclaim", returned,
                            msg("items-recovered") + source + msg("after-death"), null);
            audit(recovered);
        }
        boolean feeEvidence = service != null || !returned.isEmpty();
        if (allowObservedFee && coinsPaid > 0L && feeEvidence && (!hasOtherLoss || !returned.isEmpty())) {
            String why = service == null ? msg("death-reclaim-fee") : service.why(coinsPaid);
            flows.removeIf(flow -> flow.itemId == ItemID.COINS && flow.isCost());
            return bookDeathFee(coinsPaid, why, now);
        }
        return recovered;
    }

    /** Create a neutral audit receipt for the measured negative portion of the death wipe. */
    Transaction deathWipeTransfer(List<Flow> flows, Map<Integer, Long> measuredLosses, long now) {
        if (empty(flows) || empty(measuredLosses)) return null;
        var outstanding = new HashMap<Integer, Long>(measuredLosses);
        List<Flow> transferred = claim(flows, Flow::isCost,
                        (flow, quantity) -> take(outstanding, flow.itemId, quantity));
        if (transferred.isEmpty()) return null;
        Transaction transfer = bookNeutral(now, msg("death-items"), "Death reclaim", transferred,
                        msg("ownership-neutral-transfer"), null);
        audit(transfer);
        return transfer;
    }

    void recordZoneEntry(Map<Integer, Long> deltas) {
        if (deltas != null) {
            deltas.forEach((itemId, delta) -> {
                        if (itemId != null && delta != null && delta < 0L) {
                            neutralZoneStoredItems.merge(itemId, SafeMath.abs(delta), SafeMath::safeAdd);
                        }
                    });
        }
    }

    /**
     * Excludes only positive quantities that match items actually removed on zone entry. Item ids
     * with a matched observed-loot event remain counted as loot even if they happen to overlap the
     * stored gear.
     */
    Transaction settleZoneRestore(List<Flow> flows, Map<Integer, Long> observedLootQuantities, long now) {
        if (neutralZoneRestoreTicks <= 0 || neutralZoneStoredItems.isEmpty() || empty(flows)) {
            return null;
        }
        Map<Integer, Long> sourceBacked = positiveEntries(observedLootQuantities);
        List<Flow> restored = claim(flows, Flow::isGain, (flow, quantity) -> take(neutralZoneStoredItems, flow.itemId,
                                        quantity - take(sourceBacked, flow.itemId, quantity)));
        if (restored.isEmpty()) return null;
        if (neutralZoneStoredItems.isEmpty()) neutralZoneRestoreTicks = 0;
        return bookNeutral(now, msg("neutral-zone-exit"), "Gauntlet", restored, msg("items-restored"), null);
    }

    synchronized void markPkDeath(long now) {
        claims.cancelChest();
        deathReclaim.reset();
        if (activeSession == null && !autoStart(now)) return;
        if (!live()) return;
        String encounter = activeSession.addEncounter(false, now);
        setContext(PK_DEATH, max(30, config.stabilizationTicks() + 4), "PK death loss", emptyMap(), encounter);
    }

    /**
     * An ownership-neutral storage or minigame move, with the note its classifier already chose.
     */
    synchronized void markTransfer(String note, int ticks) {
        markContext(Context.TRANSFER, max(1, ticks), note);
    }

    /**
     * Barrows gear stepping 100, 75, 50, 25 to 0 as you fight, or repaired back, is the same item
     * in another state (RuneLite prices every stage but 0 alike): neutral, while its repair fee
     * counts.
     */
    Transaction wear(List<Flow> flows, long now) {
        var netByPiece = new HashMap<String, Long>();
        for (Flow flow : flows)
            if (piece(flow) != null) netByPiece.merge(piece(flow), flow.quantityDelta, Long::sum);
        List<Flow> stepped = claim(flows, flow -> piece(flow) != null && netByPiece.get(piece(flow)) == 0L,
                        (flow, quantity) -> quantity);
        return stepped.isEmpty() ? null : bookNeutral(
                        now, msg("barrows-wear"), "Barrows", stepped, msg("barrows-gear"), null);
    }

    /**
     * An abyssal tentacle that runs out of charges, or is dissolved, leaves its kraken tentacle and
     * the whip inside is gone (OSRS Wiki). RuneLite prices the tentacle as the whip plus the kraken
     * tentacle, so the row nets to the whip: a loss, never a Review question (owner 1.3.3).
     */
    Transaction wornOut(List<Flow> flows, long now) {
        if (!hasLoss(flows, id -> id == ItemID.ABYSSAL_TENTACLE)
                || gains(flows, id -> id == ItemID.KRAKEN_TENTACLE).isEmpty()) return null;
        List<Flow> pair = claim(flows, flow -> flow.itemId == ItemID.ABYSSAL_TENTACLE ? flow.isCost()
                                        : flow.itemId == ItemID.KRAKEN_TENTACLE && flow.isGain(),
                        (flow, quantity) -> 1L);
        return bookCharge(now, msg("tentacle-used"), Confidence.CONFIRMED, msg("tentacle-whip"), pair, null, false);
    }

    /** A dwarf multicannon's parts set up or picked up: the same items, yours wherever they stand. */
    Transaction cannon(List<Flow> flows, long now) {
        if (inbox.peek(CANNON) == null) return null;
        List<Flow> parts = claim(flows, flow -> GameData.pattern("cannon").matcher(flow.itemName).matches(),
                        (flow, quantity) -> quantity);
        return parts.isEmpty() ? null : bookNeutral(now, msg("cannon-parts"), "Cannon", parts, msg("cannon-why"), null);
    }

    /** The Barrows piece a flow is, whatever its stage; null for anything else. */
    static String piece(Flow flow) {
        java.util.regex.Matcher name = GameData.pattern("barrows").matcher(flow.itemName);
        return name.matches() ? Phrases.lower(name.group(1)) : null;
    }

    /**
     * A servant fetches from the bank, takes items to it and un-notes them: those moves are
     * neutral. Coins (his wage, a sawmill fee) still count, as do the logs a sawmill trip takes and
     * the planks it brings back, made from them.
     */
    Transaction servant(List<Flow> flows, long now) {
        if (inbox.peek(SERVANT) == null) return null;
        boolean sawmill = inbox.peek(SAWMILL) != null;
        List<Flow> moved = claim(flows, flow -> flow.itemId != ItemID.COINS && !(sawmill && wood(flow)),
                        (flow, quantity) -> quantity);
        // The sawmill trip ends when its planks come back.
        if (sawmill && flows.stream().anyMatch(flow -> flow.isGain() && wood(flow)))
            inbox.drop(SAWMILL);
        return moved.isEmpty() ? null : bookNeutral(now, msg("servant-bank"), "Construction", moved,
                        msg("house-servant"), null);
    }

    /** Logs a sawmill trip takes, or the planks it brings back. */
    static boolean wood(Flow flow) {
        return GameData.pattern("wood").matcher(flow.itemName).matches();
    }

    /**
     * A measured Check supersedes this weapon's pending estimates. A component it read unchanged
     * was not used, so its estimates are removed, as a Check that moved nothing removes them all.
     */
    synchronized void clearPendingEstimates(String targetIdentity, Set<Integer> unchanged) {
        if (activeSession == null) return;
        String sessionId = activeSession.getId();
        for (int itemId : unchanged) {
            for (ChargeEstimateJournal.Receipt receipt :
                    chargeEstimateJournal.receipts(sessionId, targetIdentity, itemId)) {
                forfeit(receipt, SafeMath.now());
            }
        }
        chargeEstimateJournal.clearTarget(sessionId, targetIdentity);
    }

    /**
     * A compatible Check that moved nothing closes the window for this target: any automatic
     * estimates still pending were overestimates and are removed through the announced path.
     */
    synchronized void closeChargeWindow(String targetIdentity) {
        if (activeSession == null || blank(targetIdentity))
            return;
        String sessionId = activeSession.getId();
        long now = SafeMath.now();
        for (ChargeEstimateJournal.Receipt receipt : chargeEstimateJournal.receiptsFor(sessionId, targetIdentity)) {
            forfeit(receipt, now);
        }
        chargeEstimateJournal.clearTarget(sessionId, targetIdentity);
    }

    synchronized boolean markChargeLoad(ChargedItem variant, int selectedItemId, String selectedItemName,
            String targetIdentity, int ticks) {
        if (variant == null || ticks <= 0 || !ChargeCheck.isLoadComponent(variant, selectedItemId, selectedItemName))
            return false;
        Observation e = inbox.put(LOAD_TARGET, selectedItemId, 1L, ticks - 1, null);
        e.variant = variant;
        e.id = nonBlank(targetIdentity, null);
        e.at = SafeMath.now();
        return true;
    }

    /**
     * A load click links candidate measured losses to a physical target; it proves no load
     * quantity.
     */
    LoadTarget loadTarget(List<Flow> flows) {
        Observation e = inbox.peek(LOAD_TARGET);
        var candidates = new ArrayList<Flow>();
        var left = new ArrayList<Flow>();
        boolean selected = false;
        for (Flow flow : e == null || flows == null ? Collections.<Flow>emptyList() : flows) {
            if (flow.isCost() && ChargeCheck.isLoadComponent(e.variant, flow.itemId, flow.itemName)) {
                candidates.add(flow);
                selected |= flow.itemId == e.item;
            } else left.add(flow);
        }
        return selected ? new LoadTarget(e.variant, e.id, left, candidates, e.at)
                : new LoadTarget(null, null, flows == null ? emptyList() : flows, emptyList(), 0L);
    }

    /**
     * A refused automatic use (the game answered "already at that location"): the click spent
     * nothing, so the estimate it booked is removed through the announced automatic-evidence path
     * (owner 1.3.4). Other pending estimates for the target are untouched.
     */
    synchronized void forfeitEstimate(String targetIdentity, String transactionId) {
        if (activeSession == null || transactionId == null || transactionId.isEmpty()) return;
        String target = nonBlank(targetIdentity, null);
        if (target == null) return;
        long now = SafeMath.now();
        for (ChargeEstimateJournal.Receipt receipt : chargeEstimateJournal.receiptsFor(activeSession.getId(), target)) {
            if (transactionId.equals(receipt.transactionId)) forfeit(receipt, now);
        }
    }

    /** Clear a pending charge-load intent when another action supersedes its evidence. */
    synchronized void clearChargeLoad() {
        inbox.drop(LOAD_TARGET);
    }

    /** Observe one exact Check read against that weapon's own last Check. */
    synchronized ChargeDelta observeCheck(ChargeCheck read, String targetIdentity, long now) {
        return measuredChargeReadTracker.observe(read, targetIdentity, now);
    }

    /** Forget one kind's Check reads (every kind when null): its next Check starts fresh. */
    synchronized void resetChecks(ChargedItem variant) {
        if (variant == null) measuredChargeReadTracker.reset();
        else measuredChargeReadTracker.resetVariant(variant);
    }

    /**
     * A supported charged weapon entering the inventory is a new physical instance: a measured
     * Check baseline for that variant can no longer be trusted to belong to the item now in the
     * slot. Fail closed and require a fresh baseline instead of booking a phantom decrease from a
     * replacement that retained the same slot identity.
     */
    void invalidateChargeBaselines(Map<Integer, Long> deltas) {
        if (empty(deltas)) return;
        for (Map.Entry<Integer, Long> entry : deltas.entrySet()) {
            Long quantity = entry.getValue();
            if (quantity == null || quantity <= 0L) continue;
            ChargedItem variant = ChargeCheck.supportedForId(entry.getKey());
            if (variant != null) measuredChargeReadTracker.resetVariant(variant);
        }
    }

    /** Start tracking the items removed by entering a region-based neutral zone. */
    synchronized void beginNeutralZone() {
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
     * End the region-based transfer context. Only a matching return of an item actually removed on
     * entry remains neutral; later lobby rewards keep their normal classification.
     */
    synchronized void endNeutralZone(int ticks) {
        boolean zoneWasActive = neutralZoneTransferActive;
        neutralZoneTransferActive = false;
        captureNeutralZoneEntry = false;
        neutralZoneEntryBaseline = null;
        neutralZoneEntryCaptureTicks = 0;
        neutralZoneRestoreTicks = neutralZoneStoredItems.isEmpty() ? 0 : max(1, ticks);
        boolean pendingZoneContext = pendingClue().context == Context.TRANSFER && pendingClue().note != null
                        && pendingClue().note.startsWith(msg("neutral-zone-instance"));
        boolean activeZoneContext = liveClue().context == Context.TRANSFER && liveClue().note != null
                        && liveClue().note.startsWith(msg("neutral-zone-instance-2"));
        if (pendingZoneContext) pendingClue().clear();
        if (activeZoneContext) {
            liveClue().clear();
            inbox.drop(CLOCK);
        }
        if (zoneWasActive || pendingZoneContext || activeZoneContext) {
            inbox.drop(SOFT_TRANSFER);
            clearHardTransfer();
        }
    }

    /** Begin clue/casket path so dig/tele/key spends pair as clue costs. */
    synchronized void beginCluePath(String encounterId, String label) {
        clueCostPairing.beginClue(encounterId, label);
    }

    /** Ids of the unresolved key/chest claims; a Ledger row with one of these ids is still pc(). */
    synchronized Set<String> pendingClaimIds() {
        var ids = new LinkedHashSet<String>();
        for (PendingClaim claim : claims.all()) ids.add(claim.getClaimId());
        return ids;
    }

    synchronized List<PendingClaim> getPendingClaims() {
        return claims.all();
    }

    synchronized void beginKeyDeathSettle(Map<Integer, Long> keyInventory) {
        claims.beginLocalDeath(keyInventory);
    }

    synchronized void tickKeyClaims() {
        claims.tick();
    }

    /** Items taken apart and what doing it loses (d23). */
    static final Map<Integer, String[]> DISMANTLE = new HashMap<>();

    static {
        for (String[] row : GameData.rows("dismantle"))
            DISMANTLE.put(Integer.parseInt(row[0]), row);
    }

    /** The item a Revert or Dismantle click takes apart, for the next few ticks. */
    synchronized void dismantle(int itemId) {
        inbox.put(DISMANTLE_CLICK, itemId, 1L, 5, null);
    }

    /** The clicked item left and something else arrived: the item was taken apart. */
    boolean takenApart(List<Flow> flows) {
        boolean out = false, in = false;
        for (Flow flow : flows) {
            if (inbox.item(DISMANTLE_CLICK) <= 0) continue;
            if (flow.itemId == inbox.item(DISMANTLE_CLICK) && flow.quantityDelta < 0L) out = true;
            else if (flow.quantityDelta > 0L) in = true;
        }
        return out && in;
    }

    /** Arm/cancel ordinary key-chest provenance from an actual chest object click. */
    synchronized boolean observeChest(String option, String target) {
        return claims.chestClick(option, target);
    }

    /**
     * Per-tick GE custody maintenance: a held collect resolves at its window end and expired
     * lifecycles hand off even when no further inventory change settles. Returns true when a
     * booking was added.
     */
    synchronized boolean tickGe(long now) {
        return live() && maintainCustody(now);
    }

    /** Durable schema-104 GE custody lifecycle; flows are claimed at Flow quantity level. */
    GeCustodyLedger geCustody = new GeCustodyLedger();

    /**
     * Schema-106 pooled known-basis coverage; supporting ownership state, never a second ledger.
     */
    TrackedBasisLedger trackedBasis = new TrackedBasisLedger();

    /** Bounded player Collect interaction evidence; never books by itself. */
    /**
     * Feeds an offer transition to the custody lifecycles. Review handoffs (slot reuse, identity
     * replacement, unobserved terminal) and settlements completed by a held collect book once.
     */
    synchronized void noteGeOffer(OfferSlots.Transition transition, String itemName, long now) {
        for (Transaction outcome : geCustody.observeTransition(transition, itemName, now,
                        activeSession == null ? "" : activeSession.getId())) {
            bookCustody(outcome);
        }
    }

    /**
     * Login/relog slot baseline. Resumes persisted custody lifecycles whose offer identity is
     * unchanged, quarantines offers first seen already open as legacy/unbased, and hands
     * changed-slot lifecycles to Review. Never invents basis and never replays old progress.
     */
    synchronized void seedGeSlots(Map<Integer, OfferSlots.Snapshot> snapshots, long now) {
        for (Transaction handoff : geCustody.seedSlots(
                        snapshots, now, activeSession == null ? "" : activeSession.getId())) {
            bookCustody(handoff);
        }
    }

    /** Derived, read-only Market settlement context over the durable custody lifecycles. */
    synchronized List<MarketRows.Row> marketRows() {
        return MarketRows.rows(geCustody.records(), this::transactionById, this);
    }

    /**
     * The session holding this booked transaction, or null when none is retained: Market rows
     * follow the session that holds their settlement (cross-Grind sales).
     */
    Session sessionHolding(String transactionId) {
        for (Session session : uniqueSessions()) {
            if (findTransaction(session, transactionId) != null) return session;
        }
        return null;
    }

    Session sessionById(String sessionId) {
        for (Session session : uniqueSessions()) {
            if (session != null && session.getId().equals(sessionId)) return session;
        }
        return null;
    }

    /**
     * Canonical item identity for GE custody/execution correlation, using the same noted/variation
     * policy as inventory valuation. Identity normalization only; it never re-values a movement.
     */
    /** Effective correction-aware canonical settlement lookup for the derived Market projection. */
    Transaction transactionById(String id) {
        for (Session session : id == null || id.isEmpty() ? Collections.<Session>emptyList() : uniqueSessions()) {
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
     * A bounded player Collect interaction with the Grand Exchange collection box. It only enables
     * exact custody collection correlation for the window; it never books anything and never owns a
     * movement by itself.
     */
    synchronized void noteGeCollect(long now) {
        long until = max(inbox.at(GE_COLLECT), now + 20_000L);
        inbox.put(GE_COLLECT, -1, 1L, Integer.MAX_VALUE, null).at = until;
    }

    /** Release held cash to its current owner's Review before dropping transient evidence. */
    synchronized void clearGeCollect() {
        inbox.drop(GE_COLLECT);
        geCustody.releaseHeldCollect().forEach(this::bookCustody);
    }

    /** Canonical custody outcomes (review handoffs and retried settlements) are booked once. */
    void bookCustody(Transaction outcome) {
        if (outcome != null && activeSession != null) {
            // Counted custody settlements already consumed their reservation; their item legs
            // must never deplete known coverage again.
            add(outcome, outcome.isCounted());
        }
    }

    /** Authoritative profile/analytics timezone for presentation date scopes. */
    synchronized ZoneId timeZone() {
        try {
            return ZoneId.of(profileTimeZoneId);
        } catch (RuntimeException ex) {
            return ZoneId.systemDefault();
        }
    }

    synchronized void setChestVisible(boolean visible) {
        claims.setLootChestVisible(visible);
    }

    synchronized void observeKeyContainer(int keyItemId, Map<Integer, Long> contents) {
        claims.observeKeyContents(keyItemId, contents);
    }

    /** The exact price of one pending loot key's observed chest contents; 0 when unknown. */
    synchronized long pendingKeyValue(int keyItemId, long now) {
        return pricedContents(claims.observedContents(keyItemId), now);
    }

    /**
     * The exact price across every pending key's observed chest, each key type once (one opening
     * takes a chest's contents). 0 when no chest was observed or a part has no quote.
     */
    synchronized long pendingKeysValue(long now) {
        long total = 0L;
        var seen = new HashSet<Integer>();
        for (SavedState.PendingClaim claim : claims.all()) {
            if (claim == null || !claim.isValid() || !seen.add(claim.itemOrKeyId)) continue;
            total = SafeMath.safeAdd(total, pricedContents(claims.observedContents(claim.itemOrKeyId), now));
        }
        return total;
    }

    /**
     * A chest manifest priced exactly from its observed contents: every part needs a supported
     * quote, or nothing shows (owner 2026-10-14). The game's own display rounds to a million.
     */
    private long pricedContents(Map<Integer, Long> contents, long now) {
        if (contents == null || contents.isEmpty()) return 0L;
        List<Flow> flows = valuationService.value(contents, now);
        // A component the valuator silently drops (an empty vial) fails the manifest closed.
        if (flows == null || flows.size() != contents.size()) return 0L;
        long total = 0L;
        for (Flow flow : flows) {
            if (flow.unitPrice <= 0) return 0L;
            total = SafeMath.safeAdd(total, flow.valueDelta);
        }
        return total;
    }

    /** A non-counted, ownership-neutral audit row: transfers, holds, restores and reclaims. */
    Transaction bookNeutral(long now, String note, String activityName, List<Flow> flows, String explanation,
            String encounterId) {
        return bookTransaction(now, TransactionType.TRANSFER, Context.TRANSFER, note, activityName, false, flows,
                Confidence.CONFIRMED, explanation, encounterId);
    }

    /** A session is open and not paused. */
    boolean live() {
        return activeSession != null && !activeSession.paused;
    }

    /**
     * Books a row; custody-owned rows already consumed their reservation and never deplete again.
     */
    void add(Transaction transaction, boolean custodyOwned) {
        activeSession.addTransaction(transaction, config.maxTransactionsPerSession(), custodyOwned);
    }

    /** Keeps a neutral transfer row only when the player asked to see them. */
    void audit(Transaction row) {
        if (config.keepTransferAuditRows()) add(row, false);
    }

    /** Books the custody settlements that came due; true when any did. */
    boolean maintainCustody(long now) {
        List<Transaction> bookings = geCustody.maintenance(now, activeSession.getId());
        bookings.forEach(booking -> add(booking, true));
        return !bookings.isEmpty();
    }

    Transaction bookTransaction(long now, TransactionType type, Context context, String note, String activityName,
            boolean counted, List<Flow> flows, Confidence confidence, String explanation, String encounterId) {
        return new Transaction(now, type, context, note, activityName, counted, flows, confidence, explanation,
                encounterId);
    }

    /** A booked charge row: counted consumption with the current activity, then its action kind. */
    private Transaction bookCharge(long now, String note, Confidence confidence, String explanation, List<Flow> flows,
            ActionKind kind, boolean estimate) {
        Transaction transaction = bookTransaction(now, CONSUMPTION, GENERIC, note,
                        nonBlank(activeSession.getActivityHint(), "General"), true, flows, confidence, explanation,
                        null);
        transaction.setActionKind(kind);
        // An estimated blowpipe dart lands by the target like a fired bolt; its scales are used up.
        transaction.setOnGround(estimate && flows.stream().anyMatch(Engine::groundDart));
        add(transaction, false);
        return transaction;
    }

    /** One chug from the chugging barrel: its doses as one Drank row titled by the barrel. */
    /**
     * Charges the game counted out exactly (uncharging Bryophyta's staff): a measured spend, kept
     * out of the estimate journal so a later Check never reshapes it (1.3.1).
     */
    synchronized void spent(String item, List<Flow> charges, long now) {
        if (live())
            bookCharge(now, msg("measured-charge") + item, Confidence.CONFIRMED, msg("game-counted") + item + ".",
                    charges, CAST, false);
    }

    synchronized void chug(List<Flow> doses, long now) {
        if (live())
            bookCharge(now, "Chugging barrel", Confidence.CONFIRMED, msg("drunk-chugging"), doses, ActionKind.DRINK,
                            false).setActionLabel(ActionLabel.of("Chugging barrel"));
    }

    /**
     * Book estimated component losses for one automatic charge use. The receipt counts immediately
     * at the captured prices with LIKELY confidence; a later measured Check reconciles it. Priced
     * components enter the transient journal so a Check can shrink them without repricing.
     */
    synchronized Transaction bookEstimatedCharge(String targetIdentity, String family, ActionKind kind,
            List<Flow> componentLosses, long now) {
        String target = nonBlank(targetIdentity, null);
        String label = nonBlank(family, null);
        if (!live() || target == null || label == null || componentLosses == null || componentLosses.isEmpty()) {
            return null;
        }
        Transaction transaction = bookCharge(now, msg("estimated-charge") + label, Confidence.LIKELY, "Estimated",
                        componentLosses, kind == null ? CAST : kind, true);
        String sessionId = activeSession.getId();
        for (Flow flow : transaction.getFlows()) {
            if (flow.isCost() && flow.unitPrice > 0) {
                chargeEstimateJournal.record(sessionId, target, transaction.getId(), flow.itemId,
                        abs(flow.quantityDelta));
            }
        }
        if (transaction.isOnGround()) rememberDrop(transaction, Engine::groundDart);
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
                estimate.confidence = Confidence.CONFIRMED;
                estimate.setExplanation("Confirmed");
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
     * measurement adds nothing beyond what estimates already booked. Generic inventory losses, menu
     * intent and animations cannot authorize this.
     */
    synchronized Transaction bookChargeSpend(ChargeDelta measuredDelta, String itemName, List<Flow> componentLosses,
            long now, String targetIdentity) {
        if (!live() || measuredDelta == null || measuredDelta.exactCost(componentLosses) <= 0L) {
            return null;
        }
        List<Flow> booked = componentLosses;
        String target = nonBlank(targetIdentity, "");
        if (!target.isEmpty()) {
            var excess = new ArrayList<Flow>();
            String sessionId = activeSession.getId();
            for (Flow flow : componentLosses) {
                long measured = abs(flow.quantityDelta);
                long extra = measured - reconcileEstimate(sessionId, target, flow.itemId, measured, now)
                                - chargeEstimateJournal.evictedOverlap(sessionId, target, flow.itemId);
                if (extra <= 0L) continue;
                excess.add(extra == measured ? flow : new Flow(flow.itemId, flow.itemName, -extra, flow.unitPrice,
                                        -SafeMath.safeMultiply(extra, flow.unitPrice), flow.getPriceSource(),
                                        flow.priceCapturedAtEpochMillis));
            }
            if (excess.isEmpty()) return null;
            booked = excess;
        }
        // The spend belongs to what the player is doing, not to the weapon: Top activities
        // must never grow a "Toxic blowpipe" row. The weapon stays in the note.
        Transaction transaction = bookCharge(now, msg("measured-charge") + nonBlank(itemName, "Measured charges"),
                        Confidence.CONFIRMED, msg("measured-check") + measuredDelta.variant.getDisplayName() + ".",
                        booked, measuredDelta.variant == ChargedItem.BLOWPIPE ? FIRE : CAST, false);
        return transaction;
    }

    /** Book a death reclaim fee as a plain counted CONSUMPTION cost under "Death reclaim". */
    synchronized Transaction bookDeathFee(long feeGp, String why, long now) {
        String label = why == null ? msg("grave-reclaim-fee-tiered") : why;
        return bookFee(feeGp, label, "Death reclaim", now);
    }

    /** A dispenser award the chat named, waiting for its settle; empty when none. */
    Map<Integer, Long> bagReward = new HashMap<>();

    long bagRewardUntil;

    /** Dispenser rewards booked into the looting bag since the client last showed its contents. */
    Map<Integer, Long> bagAdded = new HashMap<>();

    /**
     * Wilderness Agility pays its dispenser into an open looting bag, which the client never shows
     * (owner live 2026-10-16). The chat names the whole award, so what the next settle does not
     * bring into the pack went into the bag and books as loot. The bag only ever empties into the
     * bank, so nothing books twice.
     */
    synchronized void noteBagReward(Map<Integer, Long> awarded, long now) {
        bagReward = new HashMap<>(awarded);
        bagRewardUntil = now + 6_000L;
    }

    Transaction settleBagReward(List<Flow> flows, long now) {
        if (bagReward.isEmpty()) return null;
        var bag = new HashMap<>(bagReward);
        bagReward.clear();
        if (now > bagRewardUntil || !live()) return null;
        for (Flow flow : flows)
            if (flow.isGain()) bag.computeIfPresent(flow.itemId, (id, q) -> q - flow.quantityDelta);
        bag.values().removeIf(quantity -> quantity <= 0L);
        if (bag.isEmpty()) return null;
        bag.forEach((id, quantity) -> bagAdded.merge(id, quantity, Long::sum));
        Transaction loot = bookTransaction(now, TransactionType.LOOT, LOOT, msg("bag-reward"),
                        activeSession.getActivityHint(), true, valuationService.value(bag), Confidence.LIKELY,
                        msg("bag-reward-why"), null);
        add(loot, false);
        return loot;
    }

    /** The Blast Furnace coffer's drain while you work it (owner 2026-10-16, after Coin Flow 1.9). */
    synchronized Transaction bookCofferDrain(long gp, long now) {
        return bookFee(gp, msg("bf-coffer"), "Smithing", now);
    }

    /** A bank-paid fee as a plain counted CONSUMPTION cost under its own row name. */
    private Transaction bookFee(long feeGp, String label, String activity, long now) {
        if (feeGp <= 0L || !live()) return null;
        var feeFlow = new Flow(ItemID.COINS, "Coins", -feeGp, 1, -feeGp, PriceSource.FACE_VALUE, now);
        Transaction transaction = bookTransaction(now, CONSUMPTION, GENERIC, label, activity, true,
                        singletonList(feeFlow), Confidence.CONFIRMED, label, null);
        add(transaction, false);
        return transaction;
    }

    synchronized boolean correctTransaction(String transactionId, Correction correction, long now, String reason) {
        boolean changed = activeSession != null
                        && activeSession.correctTransaction(transactionId, correction, now, reason);
        return changed;
    }

    /** Returns the active custom/general session's still-unresolved decisions as detached rows. */
    /** Unresolved owner decisions in the active session, newest first, as review rows. */
    synchronized List<ReviewRow> getReviewRows(long now) {
        var rows = new ArrayList<ReviewRow>();
        if (activeSession == null) return rows;
        for (Transaction transaction : activeSession.getTransactions()) {
            if (ReviewRules.needsOwnerDecision(transaction))
                rows.add(reviewRow(activeSession, transaction, now));
        }
        rows.sort(Comparator.comparingLong((ReviewRow itemDataValue) -> itemDataValue.timestampEpochMillis).reversed());
        return rows;
    }

    /**
     * Current world/economy permission for ordinary automatic market quotes (presentation reads
     * it).
     */
    boolean automaticMarketQuotesAvailable() {
        return valuationService.automaticMarketQuotesAvailable();
    }

    /** Domain revision: every canonical mutation advances it; derived previews bind to it. */
    synchronized long getRevision() {
        return revision;
    }

    /**
     * Exact rows and prospective personal-Net delta a Decide all would apply, computed from the
     * same canonical transaction ids that {@link #applyDecision} mutates.
     */
    synchronized DecisionPreview previewDecision(Decision decision, Predicate<ReviewRow> scope, long now) {
        var selectedIds = new ArrayList<String>();
        long netDelta = 0L;
        if (activeSession != null && decision != null && scope != null) {
            Correction correction = decision.toCorrection();
            for (Transaction transaction : activeSession.getTransactions()) {
                if (!ReviewRules.needsOwnerDecision(transaction)
                        || !scope.test(reviewRow(activeSession, transaction, now))) {
                    continue;
                }
                selectedIds.add(transaction.getId());
                netDelta = SafeMath.safeAdd(netDelta, transaction.netUnder(correction)
                                        - (transaction.isCounted() ? transaction.getNet() : 0L));
            }
        }
        return new DecisionPreview(decision, selectedIds, netDelta, getRevision());
    }

    /**
     * Applies a preview as one atomic batch with one undo. Returns {@code -1} without touching
     * anything when the domain revision moved since the preview (stale row ids never apply).
     */
    synchronized int applyDecision(DecisionPreview preview, long now) {
        if (preview == null || activeSession == null || preview.transactionIds.isEmpty()) return 0;
        if (preview.revision != getRevision()) return -1;
        int changed = activeSession.correctAll(preview.transactionIds, preview.decision.toCorrection(), now,
                        "Review decision: " + preview.decision.name());
        return changed;
    }

    /**
     * Revision-bound Decide all impact: canonical ids, row count and exact prospective Net delta.
     */
    static class DecisionPreview {
        Decision decision;
        List<String> transactionIds;
        long netDelta;
        long revision;

        DecisionPreview(Decision decision, List<String> transactionIds, long netDelta, long revision) {
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
        for (Flow flow : transaction.getFlows())
            items.add(new Item(flow.itemId, flow.itemName, flow.quantityDelta, flow.valueDelta));
        if (items.isEmpty())
            items.add(new Item(-1, transaction.getNote(), 0L, transaction.autoNet()));
        String why = transaction.getExplanation();
        if (blank(why)) why = msg("uncertain-transaction");
        long timestamp = transaction.timestampEpochMillis;
        long age = now > timestamp ? now - timestamp : 0L;
        EnumSet<Decision> decisions = EnumSet.allOf(Decision.class);
        return new ReviewRow(transaction.getId(), session.getId(), timestamp, age, items, transaction.autoNet(), why,
                decisions);
    }

    synchronized boolean splitItem(String transactionId, int itemId, long keepQuantity, long now, String optionalNote) {
        boolean changed = activeSession != null && activeSession.splitItem(
                                transactionId, itemId, keepQuantity, now, optionalNote);
        return changed;
    }

    synchronized boolean undoCorrection(long now) {
        boolean changed = activeSession != null && activeSession.undoCorrection(now);
        return changed;
    }

    synchronized Transaction undoTransaction() {
        return activeSession == null ? null : activeSession.undoTransaction();
    }

    synchronized Transaction restoreUndo() {
        return activeSession == null ? null : activeSession.restoreUndo();
    }

    synchronized void recordAction(String activityHint) {
        if (live()) activeSession.recordAction(activityHint, SafeMath.now());
    }

    /** Additional source receipts are published after canonical booking has completed. */
    synchronized Transaction processIfDirty(ContainerSnapshot current, long now,
            java.util.function.Consumer<Transaction> additionalBooking) {
        inbox.hold = dirty || pendingSnapshot != null
                        || (current != null && baseline != null && !current.equals(baseline));
        inbox.tick(inbox.now + 1);
        settleGround();
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
        String expiredDeathReclaim = deathReclaim.tick(inbox.hold);
        if (expiredDeathReclaim != null) {
            Transaction expiry = bookNeutral(now, msg("death-reclaim-expired"), "Death reclaim", emptyList(),
                            expiredDeathReclaim, null);
            add(expiry, false);
            return expiry;
        }
        if (activeSession.paused) {
            // An inventory gain during an Idle pause is the action that ended it (a
            // pickpocket's loot can settle before the activity detector fires): resume and
            // book it. Other pauses stay aligned so Resume never inherits pause-window
            // churn as catch-up profit (owner 2026-09-29).
            Map<Integer, Long> woke = current == null || baseline == null ? null : current.diff(baseline);
            boolean idleResume = activeSession.getPauseReason() == IDLE && woke != null && woke.values().stream()
                                    .anyMatch(value -> value != null && value > 0L);
            if (!idleResume) {
                // Keep the baseline aligned while paused so Resume never treats
                // pause-window inventory churn as catch-up profit.
                baseline = current;
                if (captureNeutralZoneEntry) neutralZoneEntryBaseline = current;
                baselinePriming = false;
                clearPendingChange();
                inbox.drop(CONSUME, SPENT_CONSUME);
                inbox.drop(DROP_CLICK);
                clearHardTransfer();
                return advanceContext(now);
            }
            activeSession.resume(now);
        }
        if (baselinePriming) {
            observePriming(current);
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
            capturePendingContext();
        }
        if (pendingSnapshot == null || !pendingSnapshot.equals(current)) {
            pendingSnapshot = current;
            pendingStableTicks = 0;
            capturePendingContext();
            return null;
        }
        pendingStableTicks++;
        if (pendingStableTicks < max(0, config.stabilizationTicks())) return null;
        Transaction settled = settle(pendingSnapshot, now, additionalBooking);
        clearSettledContext();
        return settled;
    }

    /**
     * One stabilized inventory change moving through the settle stages, with the claim it settles
     * under.
     */
    static class Settle extends Clue {
        long now;
        ContainerSnapshot committed;
        List<Flow> flows;

        /**
         * A local death with no confident PvM/PK classification: routine spends stay unlabelled.
         */
        boolean suppressActionEvidence;

        boolean localDeath;
        boolean deathWipe;
        boolean ambiguousDeathCoinLoss;
        Map<Integer, Long> deathWipeLosses = emptyMap();
        Map<Integer, Long> keyLosses = emptyMap();
        String settledClaimId;
        Transaction keyAudit;
        LootMatch loot = LootMatch.NONE;

        /** Source-backed quantities, kept even if the loot label is later refused. */
        Map<Integer, Long> lootQuantities = emptyMap();

        boolean destroy;
        boolean pricingReview;
        boolean decant;
        boolean dismantle;
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
     * a confirmed drop's own item, actions and transfers, GE custody, then classification and
     * booking of what is left.
     */
    Transaction settle(ContainerSnapshot committed, long now,
            java.util.function.Consumer<Transaction> additionalBooking) {
        Settle settle = commit(committed, now);
        if (settle == null) return null;
        // Published like any extra booking, so HUD+'s tray shows the bag's rewards too.
        Transaction bag = settleBagReward(settle.flows, now);
        if (bag != null && additionalBooking != null) additionalBooking.accept(bag);
        // Minigame-only items (a loaned loadout, table supplies) cannot legally exist outside
        // their minigame: the grant and the exit strip vanish before any settle stage sees them, so
        // they never book, reach Review or pollute the death-wipe whitelist (Coinflow
        // MinigameQuarantine evidence, owner 2026-10-12). commit()'s swap filter runs earlier and
        // cannot pair these ids; the drink normalization runs later, so a loaned dose sip can never
        // be rewritten into real ids.
        settle.flows.removeIf(flow -> MinigameItems.isMinigameOnly(flow.itemId));
        // An essence pouch's Empty/Fill carries custody of the essence it moves, never the runes a
        // craft produces in the same stabilized window: the essence stays neutral and the residual
        // classifies normally (owner 2026-10-13).
        if ((settle.transfer || settle.hard) && settle.note != null && settle.note.contains("essence_pouch")) {
            settle.flows.removeIf(flow -> flow.itemName != null && Phrases.lower(flow.itemName).contains("essence"));
            settle.clear();
        }
        settleDeathWipe(settle);
        settleKeyTokens(settle);
        if (settle.flows.isEmpty()) return settle.keyAudit;
        Transaction held = settleHeldItems(settle);
        if (settle.flows.isEmpty()) return held;
        Transaction returned = settleReturns(settle);
        if (settle.flows.stream().allMatch(flow -> flow.valueDelta == 0L
                                                && flow.getPriceSource() != PriceSource.UNPRICED
                                                // A worthless item still moved: it books at 0
                                                // with a dash, never vanishes (owner 2026-10-16).
                                                && flow.getPriceSource() != PriceSource.NO_VALUE)
                // Owner 2026-10-09: a clue scroll, scroll box or casket picked up is worth nothing
                // yet but is a
                // drop, so it books and HUD+ shows it; opening, stepping or moving them still books
                // nothing.
                && !(settle.flows.stream().allMatch(Flow::isGain) && ActionReader.all(settle.flows, 0,
                                name -> GameData.pattern("clue").matcher(name).matches()))) {
            // A change with no value and no unpriced item is nothing to account for; a settled
            // claim still closed the deferred key audit row above. No value threshold exists.
            return first(returned, settle.keyAudit);
        }
        Transaction restore = settleZoneRestore(settle.flows, settle.lootQuantities, now);
        if (restore != null) {
            audit(restore);
            if (settle.flows.isEmpty()) return restore;
            // Only the matched entry items are neutral. Classify residual flows using
            // their actual consumption/loot evidence rather than the old region latch.
            settle.clear();
            clearSettledContext();
        }
        for (Transaction neutral : new Transaction[] {
                    servant(settle.flows, now), wear(settle.flows, now), cannon(settle.flows, now)
                }) {
            if (neutral != null) {
                audit(neutral);
                if (settle.flows.isEmpty()) return neutral;
            }
        }
        Transaction worn = wornOut(settle.flows, now);
        if (worn != null && settle.flows.isEmpty()) return worn;
        List<Transaction> sources = bookLootSources(settle);
        // Partial source matches classify the whole remaining settle as loot only
        // when the transfer partition consumed the unmatched quantities. Without
        // that partition, an oversized same-id stack (or unrelated extra gain)
        // must follow generic gain classification rather than inherit the loot tag.
        if (settle.loot.matched && !matchesExpectedLoot(settle.flows, settle.loot.matchedQuantities))
            settle.loot = LootMatch.NONE;
        Transaction dropSlice = settleDropSlice(settle);
        Transaction consumeSlice = settleConsumeSlice(settle);
        settleActions(settle);
        if (dropSlice != null && settle.flows.isEmpty() && sources.isEmpty()) return dropSlice;
        if (consumeSlice != null && settle.flows.isEmpty() && sources.isEmpty()) return consumeSlice;
        if (settle.consumed && !settle.dropped && !settle.destroy && !settle.hard && !settle.decant
                && !settle.suppressActionEvidence && ActionReader.resolve(
                                settle.actionIntent, settle.flows, CONSUMPTION, settle.activity) == DRINK) {
            List<Flow> normalized = valuationService.normalizeConsumedFlows(settle.flows, DRINK, settle.now);
            settle.pricingReview = normalized == null;
            if (normalized != null) settle.flows = normalized;
        }
        Transaction cast = settleCast(settle);
        if (cast == null) cast = settleSpell(settle);
        Transaction result;
        if (!sources.isEmpty() && settle.flows.isEmpty()) {
            result = sources.get(sources.size() - 1);
        } else {
            Transaction custody = settleCustody(settle);
            result = custody != null ? custody : book(settle);
        }
        if (additionalBooking != null) {
            for (Transaction row : sources) {
                if (row != result) additionalBooking.accept(row);
            }
            if (cast != null) additionalBooking.accept(cast);
            if (dropSlice != null) additionalBooking.accept(dropSlice);
            if (consumeSlice != null) additionalBooking.accept(consumeSlice);
        }
        return result;
    }

    /**
     * A spell cast settling with a pickup (owner 2026-09-28) books its runes as their own Cast, not
     * as loose losses on the loot receipt. Only a loss set that is exactly one spell's runes splits
     * off.
     */
    Transaction settleCast(Settle settle) {
        var losses = new ArrayList<Flow>();
        for (Flow flow : settle.flows) {
            if (flow.quantityDelta < 0L) losses.add(flow);
        }
        if (losses.isEmpty() || losses.size() == settle.flows.size() || settle.hard || settle.soft()
                || settle.localDeath || settle.dropped || settle.destroy || settle.asTransfer
                || settle.context == MARKET || Spells.spellFor(losses) == null) {
            return null;
        }
        var slice = new Settle(new Clue(), settle.committed, settle.now);
        slice.flows = losses;
        slice.activity = settle.activity;
        slice.actionIntent = settle.actionIntent;
        slice.actionLabel = settle.actionLabel;
        settle.flows = new ArrayList<>(settle.flows);
        settle.flows.removeAll(losses);
        return book(slice);
    }

    /**
     * A spell with a product keeps only its own legs; a pickup that settled with it books on its
     * own (owner live 2026-10-16: 39 rune darts rode a Bones to Peaches tablet).
     */
    Transaction settleSpell(Settle settle) {
        Set<Integer> product = settle.consumed && settle.actionLabel != null
                        ? Spells.product(Spells.named(settle.actionLabel.value())) : emptySet();
        var legs = new ArrayList<Flow>();
        for (Flow flow : settle.flows)
            if (flow.isCost() || product.contains(flow.itemId)) legs.add(flow);
        if (product.isEmpty() || legs.size() == settle.flows.size()) return null;
        var slice = new Settle(new Clue(), settle.committed, settle.now);
        slice.flows = legs;
        slice.activity = settle.activity;
        slice.actionIntent = settle.actionIntent;
        slice.actionLabel = settle.actionLabel;
        slice.consumed = true;
        settle.flows = new ArrayList<>(settle.flows);
        settle.flows.removeAll(legs);
        settle.consumed = false;
        settle.actionIntent = null;
        settle.actionLabel = null;
        return book(slice);
    }

    /**
     * A confirmed drop owns its item in a mixed settle (owner 1.3.4): dropping plant pots while a
     * calquat harvest lands must book the pot loss as its own Dropped row instead of turning the
     * change into Review. The drop's losses move to their own row; every other flow must be a
     * gain, and a second loss, a pickup back of the same item, an armed consume intent, bank
     * evidence or a Market/PK context cancels the split, leaving the change to settle as before.
     */
    Transaction settleDropSlice(Settle settle) {
        if (settle.hard || settle.soft() || settle.transfer) return null;
        if (settle.context == Context.TRANSFER || settle.context == MARKET || settle.context == PK_DEATH
                || settle.context == PK_LOOT) {
            return null;
        }
        if (inbox.peek(DROP_CLICK) == null || consumeIntent() != null) return null;
        int itemId = inbox.item(DROP_CLICK);
        if (itemId <= 0) return null;
        var losses = new ArrayList<Flow>();
        boolean pickedBack = false;
        for (Flow flow : settle.flows) {
            if (flow.itemId == itemId) {
                if (flow.quantityDelta > 0L) pickedBack = true;
                else if (flow.quantityDelta < 0L) losses.add(flow);
            } else if (flow.quantityDelta <= 0L) {
                // Any second loss (or a zero flow) makes the change ambiguous again.
                return null;
            }
        }
        if (losses.isEmpty() || pickedBack) return null;
        var slice = new Settle(new Clue(), settle.committed, settle.now);
        slice.context = settle.context;
        slice.encounterId = settle.encounterId;
        slice.activity = settle.activity;
        slice.flows = losses;
        settle.flows = new ArrayList<>(settle.flows);
        settle.flows.removeAll(losses);
        settleActions(slice);
        return book(slice);
    }

    /**
     * A named consume whose bite lands with a same-item top-up (a bank withdrawal or a pickup in
     * one window): the bite moves to its own Supplies row and the top-up stays with the settle, so
     * the stack's acquisition is never counted as the bite's revenue (owner 2026-10-12, review).
     * Runs before settleActions so the remainder follows its own evidence — a hard bank batch
     * stays a neutral transfer, a loot batch keeps its row.
     */
    Transaction settleConsumeSlice(Settle settle) {
        Observation intent = consumeIntent();
        if (intent == null || intent.item < 0 || intent.flag) return null;
        if (settle.localDeath || settle.suppressActionEvidence) return null;
        if (settle.context == PK_DEATH) return null;
        int itemId = intent.item;
        var bites = new ArrayList<Flow>();
        boolean toppedUp = false;
        for (Flow flow : settle.flows) {
            if (flow.itemId != itemId) continue;
            if (flow.quantityDelta > 0L) toppedUp = true;
            else if (flow.quantityDelta < 0L) bites.add(flow);
        }
        if (bites.isEmpty()) return null;
        // A pure bite (nothing else in the change) books on its own whenever the intent proved
        // it — including inside a live bank window, where the transfer context would otherwise
        // swallow it. A bite mixed with other items only splits when a same-item top-up is there
        // to keep; any other mixed window keeps its existing shape.
        boolean pure = bites.size() == settle.flows.size();
        if (!pure && !toppedUp) return null;
        // The bite is a player action of its own, never part of the transfer or loot batch it
        // landed with: a fresh generic slice books it as the counted Supplies row.
        var slice = new Settle(new Clue(), settle.committed, settle.now);
        slice.activity = settle.activity;
        slice.actionIntent = intent.action;
        slice.actionLabel = intent.label;
        slice.consumed = true;
        slice.flows = bites;
        settle.flows = new ArrayList<>(settle.flows);
        settle.flows.removeAll(bites);
        // The bite owns the intent: take it so no later flow wears its verb or spell label.
        inbox.take(CONSUME, intent.item, 1L, e -> true);
        return book(slice);
    }

    /** Different loot owners share a measured snapshot, never a source label or encounter. */
    List<Transaction> bookLootSources(Settle settle) {
        if (settle.loot.sources.size() <= 1) return emptyList();
        var rows = new ArrayList<Transaction>();
        for (LootMatch source : settle.loot.sources) {
            var remaining = new HashMap<Integer, Long>(source.matchedQuantities);
            List<Flow> parts = claim(settle.flows, flow -> flow.quantityDelta > 0L, (flow, quantity) -> {
                                long take = min(quantity, remaining.getOrDefault(flow.itemId, 0L));
                                remaining.computeIfPresent(flow.itemId, (id, wanted) -> wanted - take);
                                return take;
                            });
            if (parts.isEmpty()) continue;
            var slice = new Settle(new Clue(), settle.committed, settle.now);
            slice.offer(source.context, source.note, source.matchedQuantities, source.encounterId, true);
            slice.flows = parts;
            slice.loot = source;
            slice.lootQuantities = source.matchedQuantities;
            slice.activity = source.activityName;
            slice.settledClaimId = rows.isEmpty() ? settle.settledClaimId : null;
            rows.add(book(slice));
        }
        settle.loot = LootMatch.NONE;
        settle.lootQuantities = emptyMap();
        settle.settledClaimId = null;
        if (settle.context == LOOT || settle.context == PK_LOOT) settle.generic();
        return rows;
    }

    /** Commits the stabilized snapshot: its measured deltas, valued, under the pending claim. */
    Settle commit(ContainerSnapshot committed, long now) {
        var settle = new Settle(pendingClue(), committed, now);
        settle.suppressActionEvidence = UNCLASSIFIED_LOCAL_DEATH_NOTE.equals(settle.note);
        settle.localDeath = settle.suppressActionEvidence || settle.context == PK_DEATH;
        if (settle.suppressActionEvidence) {
            // Keep routine rune/ammo spends distinguishable from local death losses
            // without exposing an internal marker in the transaction note.
            settle.note = "";
        }
        Map<Integer, Long> deltas = committed.diff(baseline);
        baseline = committed;
        invalidateChargeBaselines(deltas);
        if (captureNeutralZoneEntry && neutralZoneEntryBaseline != null) {
            if (settle.context == Context.TRANSFER && settle.note != null
                    && settle.note.startsWith(msg("neutral-zone-instance-3"))) {
                recordZoneEntry(committed.diff(neutralZoneEntryBaseline));
            }
            neutralZoneEntryBaseline = committed;
        }
        clearPendingChange();
        if (deltas.isEmpty()) {
            // Same-tick acquire+consume (pickup then bury) nets to zero vs the prior
            // baseline. Recover a named consume when invent qty fell since intent arm.
            deltas = sameTickConsumeDeltas(committed);
        }
        settle.flows = deltas.isEmpty() ? new ArrayList<>() : new ArrayList<>(valuationService.value(deltas, now));
        if (settle.context == GENERIC && !settle.transfer && !settle.hard)
            settle.flows = withoutSwaps(settle.flows);
        // The split rewrites settle.flows only; nothing after commit() re-reads deltas, so the two
        // structures may disagree by design (review 2026-10-12).
        splitToppedUpConsume(settle, deltas, committed, now);
        return settle.flows.isEmpty() ? null : settle;
    }

    /**
     * A named consume whose stack was topped up in the same window (a bank withdrawal then an
     * immediate bite) nets positive and hides the bite. Split the stack's net gain into its gross
     * acquisition and the shortfall since the intent was armed, so the named consume keeps its own
     * cost flow and books Ate/Buried instead of vanishing into the transfer (owner 2026-10-12).
     */
    void splitToppedUpConsume(Settle settle, Map<Integer, Long> deltas, ContainerSnapshot committed, long now) {
        if (consumeIntent() == null || consumeIntent().item < 0 || consumeIntent().held < 0L) return;
        int id = consumeIntent().item;
        Long net = deltas.get(id);
        if (net == null || net <= 0L) return;
        long lost = consumeIntent().held - committed.quantityOf(id);
        if (lost <= 0L || lost > net) return;
        for (int index = 0; index < settle.flows.size(); index++) {
            Flow flow = settle.flows.get(index);
            if (flow.itemId != id || flow.quantityDelta <= 0L) continue;
            long gross = flow.quantityDelta + lost;
            settle.flows.set(index, new Flow(id, flow.itemName, gross, flow.unitPrice,
                            SafeMath.safeMultiply(gross, flow.unitPrice), flow.getPriceSource(),
                            flow.priceCapturedAtEpochMillis));
            break;
        }
        settle.flows.addAll(valuationService.value(Collections.singletonMap(id, -lost), now));
    }

    /** A measured death wipe: stacks held at death that left INV stay ownership-neutral. */
    void settleDeathWipe(Settle settle) {
        boolean candidate = deathReclaim.wipePending() && hasLoss(settle.flows);
        boolean allowed = candidate && (settle.context == Context.TRANSFER && isDeathHeldNote(settle.note)
                                || settle.context == GENERIC && !settle.transfer && !settle.hard);
        if (candidate && !allowed) {
            // A stronger market, transfer, production or loot context owns this change.
            deathReclaim.cancelDeathWipe();
        }
        Map<Integer, Long> actionLosses =
                allowed ? confirmedActionLossQuantities(settle.flows, settle.hard) : emptyMap();
        settle.deathWipeLosses = allowed ? deathReclaim.onDeathItemsRemoved(
                                without(settle.flows, actionLosses::containsKey, false)) : emptyMap();
        if (!actionLosses.isEmpty()) {
            // Consume action-attributed quantities from the potential wipe whitelist,
            // but leave unrelated held-at-death ids eligible in the same settle.
            deathReclaim.excludeActionLosses(actionLosses);
        }
        settle.deathWipe = !settle.deathWipeLosses.isEmpty();
        settle.ambiguousDeathCoinLoss = settle.deathWipe && deathReclaim.isReclaimArmed()
                        && hasLoss(settle.flows, id -> id == ItemID.COINS)
                        && !settle.deathWipeLosses.containsKey(ItemID.COINS);
        settle.localDeath |= settle.deathWipe;
        if (settle.context == Context.TRANSFER && isDeathHeldNote(settle.note)
                && (!settle.deathWipe || settle.ambiguousDeathCoinLoss)) {
            // The short note is not ownership evidence. Without a measured whitelist
            // match, let the remaining change follow its actual action/classification.
            settle.clear();
            clearHardTransfer();
        }
    }

    /**
     * Key tokens are provenance, not loot value: a credible pickup becomes a separate non-counted
     * audit row, and only later contents withdrawals contribute ordinary valued flows.
     */
    void settleKeyTokens(Settle settle) {
        settle.keyLosses = lossQuantities(settle.flows, KeyChestCatalogue::isKey);
        if (settle.localDeath) {
            lossQuantities(settle.flows, KeyChestCatalogue::isDeferredKey).forEach(claims::consume);
            claims.cancelChest();
        }
        List<Flow> lootKeys = gains(settle.flows, KeyClaims::isLootKey);
        Transaction lootKeyAudit = null;
        if (!lootKeys.isEmpty()) {
            boolean pickup = credibleKeyPickup(settle, settle.flows, lootKeys);
            settle.flows = without(settle.flows, KeyClaims::isLootKey, true);
            settle.expectedLoot = withoutItems(settle.expectedLoot, KeyClaims::isLootKey);
            lootKeyAudit = pickup ? recordKeyAudit(lootKeys, settle.encounterId, settle.now) : null;
        }
        List<Flow> claimKeys = gains(settle.flows, KeyChestCatalogue::isDeferredKey);
        boolean claimPickup = !claimKeys.isEmpty() && credibleKeyPickup(settle, settle.flows, claimKeys);
        settle.flows = without(settle.flows, KeyChestCatalogue::isDeferredKey, false);
        settle.expectedLoot = withoutItems(settle.expectedLoot, KeyChestCatalogue::isDeferredKey);
        Transaction claimAudit = claimPickup && !settle.localDeath ? recordClaimAudits(claimKeys, settle.now) : null;
        settle.keyAudit = claimAudit != null ? claimAudit : lootKeyAudit;
        if (gainQuantities(settle.flows, ANY).isEmpty()) {
            // The click plus measured key removal may settle a tick before the
            // chest's contents enter INV. Keep only that narrow causal evidence.
            claims.matchChest(settle.keyLosses, emptyMap(), null);
        }
    }

    /**
     * Held items: keys lost on death, the measured death wipe, key-token losses and charge loads.
     */
    Transaction settleHeldItems(Settle settle) {
        if (settle.localDeath || claims.awaitingDeathSettle()) {
            claims.recordDeathLosses(settle.flows, KeyClaims.keyQuantities(settle.committed.quantities));
        }
        Transaction wipe = settle.deathWipeLosses.isEmpty() ? null
                        : deathWipeTransfer(settle.flows, settle.deathWipeLosses, settle.now);
        if (settle.deathWipe && (wipe != null || isDeathHeldNote(settle.note))) {
            // The measured death-owned quantities have their own neutral receipt. Any
            // residual item changes in this settle must follow their own evidence.
            settle.clear();
            clearHardTransfer();
        }
        // Key tokens are deferred provenance on both sides of the lifecycle: their
        // inventory loss is never a counted cost. Preserve the measured quantity for
        // death annotation, but exclude the token from transaction maths. A same-ID
        // loss in a mixed claim settle has no source identity, so it never reduces the
        // manifest key quantity on that evidence alone.
        settle.flows.removeIf(flow -> flow.isCost() && KeyClaims.isLootKey(flow.itemId));
        // Do not let this narrow menu intent compete with stronger bank/trade/
        // transfer evidence or the loot-key lifecycle. A stale load click expires
        // once a stronger ownership context is already active.
        if (settle.hard || settle.context == Context.TRANSFER || settle.context == MARKET)
            inbox.drop(LOAD_TARGET);
        LoadTarget load = loadTarget(settle.flows);
        Transaction loadReview = null;
        if (!load.ambiguousCandidates.isEmpty()) {
            settle.flows = new ArrayList<>(load.remaining);
            loadReview = load.variant != null && load.variant.isCommittedLoad() ? recordCommittedLoad(load, settle.now)
                            : recordAmbiguousLoad(load, settle.now);
        }
        return first(settle.keyAudit, wipe, loadReview);
    }

    /** Source-backed loot, key-chest claims and death reclaim returns. */
    Transaction settleReturns(Settle settle) {
        // Consume source-backed loot before the reclaim partitioner so a kill drop
        // sharing an item id with the death wipe remains counted as loot.
        settle.loot = settle.context != Context.TRANSFER && settle.context != MARKET ? consumeQueuedLoot(settle.flows)
                        : LootMatch.NONE;
        settle.lootQuantities = settle.loot.matchedQuantities;
        Map<Integer, Long> contents = settle.loot.matched ? gainQuantities(settle.flows, ANY)
                        : minus(gainQuantities(settle.flows, ANY), settle.lootQuantities);
        boolean chestClaimContext = !settle.localDeath && !settle.hard && !settle.soft()
                        && settle.context != Context.TRANSFER && settle.context != MARKET
                        && settle.context != PRODUCTION && (settle.loot.matched || settle.context == GENERIC)
                        && onlyGainsOrKeyCosts(settle.flows);
        KeyClaims.ChestOpen chestClaim = chestClaimContext && !contents.isEmpty() ? claims.matchChest(settle.keyLosses,
                                contents, settle.loot.matched ? settle.loot.activityName : null) : null;
        if (chestClaim != null && KeyChestCatalogue.isDeferredKey(chestClaim.keyItemId)) {
            // Settlement and claim removal happen in this same revision. The settling row
            // carries the claim id only when the claim reached zero: a partial consume keeps
            // its durable remainder and must not look settled to a reload.
            String closed = claims.consume(chestClaim.keyItemId, chestClaim.quantity);
            settle.settledClaimId = closed != null ? closed : settle.settledClaimId;
        }
        if (chestClaim != null && !settle.loot.matched)
            settle.offer(LOOT, chestClaim.chestName, contents, null, true);
        boolean deathTransfer = settle.context == Context.TRANSFER && isDeathHeldNote(settle.note);
        boolean canPartitionDeathReturns = (!settle.hard || deathTransfer) && settle.context != MARKET
                        && (settle.context != Context.TRANSFER || deathTransfer);
        return canPartitionDeathReturns ? settleReclaim(settle.flows, settle.now, settle.lootQuantities,
                        !settle.ambiguousDeathCoinLoss) : null;
    }

    /**
     * A displaced earlier spend whose item this change lost leads the settle; the newer click
     * waits for its own change (owner live 2026-10-16).
     */
    void settleActions(Settle settle) {
        Observation spent = inbox.peek(SPENT_CONSUME);
        Observation newer = consumeIntent();
        boolean lead = spent != null && hasLoss(settle.flows, id -> id == spent.item)
                        && (newer == null || !hasLoss(settle.flows, id -> id == newer.item));
        if (lead) {
            spent.kind = CONSUME;
            if (newer != null) newer.kind = SPENT_CONSUME;
        }
        settleOwnActions(settle);
        if (lead && newer != null) {
            Observation left = consumeIntent();
            if (left != null) left.kind = SPENT_CONSUME;
            newer.kind = CONSUME;
        }
    }

    /** A confirmed consume or drop owns its losses; transfer evidence and loot name the rest. */
    void settleOwnActions(Settle settle) {
        settle.destroy = consumeIntent() != null && consumeIntent().flag;
        // Match Drop before consume wipe — consumeMatched used to null dropIntent first.
        settle.dismantle = !settle.hard && !settle.soft() && takenApart(settle.flows);
        settle.decant = settle.dismantle || ActionReader.isDecant(settle.flows);
        if (settle.soft() && settle.decant) {
            // A mixed shape while the bank is open has no trustworthy player-action
            // source; preserve the accounting classification without a Decanted stamp.
            settle.suppressActionEvidence = true;
        }
        // A set-up's auto-load can settle together with the last part placed (the furnace), and
        // the login priming can clear the click intent between the click and the load: with the
        // parts custody active, ball losses are loads on their own. The clicked item must itself
        // be lost for the click path, or a lone part settle would eat the ball's intent (owner
        // live 2026-10-13).
        boolean cannonLoad = inbox.peek(CANNON) != null && consumeIntent() == null && onlyCosts(settle.flows)
                        && hasLoss(settle.flows, Engine::cannonball)
                        && !hasLoss(settle.flows, id -> !cannonball(id) && !cannonPart(id));
        settle.dropped = cannonLoad || (inbox.peek(DROP_CLICK) != null && consumeIntent() == null
                                && onlyCosts(settle.flows) && hasLoss(settle.flows, id -> id == inbox.item(DROP_CLICK))
                                && !hasLoss(settle.flows, id -> id != inbox.item(DROP_CLICK) && !cannonPart(id)));
        settle.consumed = !settle.dropped && !settle.decant && matchesConsume(settle.flows, settle.hard);
        Observation intent = consumeIntent();
        if (settle.consumed && intent != null) {
            // Only a verb whose intent actually matched these flows may name the action; a stale
            // or unmatched click never upgrades the wording, and a named click names only a change
            // that moved its own item (owner 2026-10-16 sweep: a key used with an Eat armed read
            // "Ate"). Flow-level custody must never steal a quantity a named consume/drop intent
            // owns, so its item is captured here.
            boolean own = intent.item < 0 || intent.action == DRINK && ActionReader.all(
                                            settle.flows, 0, n -> ActionReader.trailingDose(n) > 0)
                            || settle.flows.stream().anyMatch(f -> f.itemId == intent.item);
            settle.actionIntent = own ? intent.action : null;
            settle.actionLabel = own ? intent.label : null;
            settle.consumedItemId = intent.item;
        }
        settle.droppedItemId = settle.dropped ? (inbox.peek(DROP_CLICK) != null ? inbox.item(DROP_CLICK)
                                : settle.flows.stream().filter(flow -> flow.isCost() && cannonball(flow.itemId))
                                        .mapToInt(flow -> flow.itemId).findFirst().orElse(-1)) : -1;
        if (!settle.consumed && !settle.dropped && !settle.hard && !settle.decant
                && ActionReader.isDoseStep(settle.flows)) {
            // Dose leftovers are high-confidence even when the menu/chat intent expired
            // before the inventory stabilized (common with Drink + Pick coalescing).
            // Soft bank-open latch must not block this — only hard bank-container/menu
            // evidence (real deposit/withdraw) may.
            settle.consumed = true;
        }
        if (settle.consumed || settle.dropped) {
            // A confirmed Eat/Bury/Drink/Drop wins over stale transfer evidence once its matching
            // inventory loss has stabilized. The Dropped/Destroyed stamps survive the loot note
            // below.
            settle.generic();
            settle.note = settle.dropped ? dropNote(settle.droppedItemId) : settle.destroy ? "Destroyed" : "";
        }
        if (settle.consumed) {
            inbox.take(CONSUME, inbox.item(CONSUME), 1L, e -> true);
            inbox.drop(DROP_CLICK);
        }
        // Soft bank-open alone is a weak signal. Real deposits/withdrawals are one-sided;
        // Drink+Pick / Bury+Pick mixed windows must not become ownership-neutral TRANSFER
        // (that yields Used/lost ×0 and drops harvest rows when countUncertain is off).
        settle.asTransfer = !settle.consumed && !settle.dropped && (settle.hard || settle.soft()
                                        && (onlyCosts(settle.flows) || onlyGains(settle.flows)));
        if (settle.asTransfer) {
            // Soft evidence while bank open (one-sided), or hard evidence that outlived UI
            // close, must settle as ownership-neutral TRANSFER (not Used/lost).
            settle.context = Context.TRANSFER;
            settle.note = empty(settle.note) ? "Bank transfer" : settle.note;
            settle.encounterId = null;
        } else if (settle.context == Context.TRANSFER) {
            // Stale soft TRANSFER (UI was open) must not reclassify pure inventory
            // consumption. Hard bank-container/menu evidence is required.
            settle.generic();
        }
        settle.activity = activeSession.getActivityHint();
        if (settle.loot.matched) {
            settle.context = settle.loot.context;
            if (!settle.dropped && !(settle.consumed && settle.destroy))
                settle.note = settle.loot.note;
            settle.activity = settle.loot.activityName;
            settle.encounterId = settle.loot.encounterId;
        } else if ((settle.context == LOOT || settle.context == PK_LOOT)
                && !matchesExpectedLoot(settle.flows, settle.expectedLoot)) {
            settle.generic();
        } else if (settle.context == LOOT) {
            settle.activity = activityFromNote(settle.note);
        } else if (settle.context == PK_LOOT || settle.context == PK_DEATH) {
            settle.activity = "PKing";
        } else if (settle.context == Context.PRODUCTION && ActionSignals.TRANSFORM.containsValue(settle.note)) {
            // A run names its own skill: the session hint waits for a second XP drop, so a first
            // item would carry the last activity (or none).
            settle.activity = "Smelting".equals(settle.note) ? "Smithing" : settle.note;
        }
    }

    /**
     * Schema-104 flow-level custody: exact Flow quantities proven to be GE principal, returns or
     * collections are claimed before any whole-transaction classification. Returns the last custody
     * booking when nothing is left; residual quantities keep their own evidence.
     */
    Transaction settleCustody(Settle settle) {
        String sessionId = activeSession.getId();
        maintainCustody(settle.now);
        // Transaction-level owners (transfer/death/loot) block everything; per-item owners (a
        // named consume/drop intent or matched loot quantity) block only their own items so the
        // remaining quantities can still be claimed exactly.
        if (settle.pricingReview || settle.context == Context.TRANSFER || settle.hard || settle.asTransfer
                || settle.localDeath || settle.context == LOOT || settle.context == PK_LOOT
                || settle.context == PK_DEATH || settle.consumed && settle.consumedItemId < 0
                || settle.dropped && settle.droppedItemId <= 0) {
            return null;
        }
        var blocked = new HashSet<Integer>(settle.loot.matched ? settle.loot.matchedQuantities.keySet() : emptySet());
        if (settle.consumedItemId >= 0) blocked.add(settle.consumedItemId);
        if (settle.droppedItemId > 0) blocked.add(settle.droppedItemId);
        GeCustodyLedger.Partition custody = geCustody.partition(settle.flows, settle.now, sessionId,
                        itemId -> !blocked.contains(itemId), settle.now <= inbox.at(GE_COLLECT));
        if (custody.countedBookings.isEmpty() && custody.transferBookings.isEmpty()) return null;
        // Custody claimed these exact quantities; the matching offer sides are spent so
        // stale placement/fill evidence can never own a later unrelated movement.
        geCustody.spend(custody.claimedFlows, settle.now);
        var bookings = new ArrayList<Transaction>(custody.countedBookings);
        if (config.keepTransferAuditRows()) bookings.addAll(custody.transferBookings);
        bookings.forEach(booking -> add(booking, true));
        if (custody.residualFlows.isEmpty()) return custody.lastBooking();
        settle.flows = new ArrayList<>(custody.residualFlows);
        return null;
    }

    /** Classifies what is left of the change and books it as one receipt. */
    Transaction book(Settle settle) {
        // The offer ledger is item/direction-scoped evidence of a Grand Exchange movement. It may
        // only own a settle that no stronger source-specific evidence already owns — matched
        // loot/reward, a confirmed consume/drop action, or a loot/death context keeps its own
        // delta.
        boolean strongerSourceEvidence = settle.loot.matched || settle.consumed || settle.dropped
                        || settle.context == LOOT || settle.context == PK_LOOT || settle.context == PK_DEATH;
        // A recent offer may explain a movement no custody lifecycle claimed. Custody is
        // authoritative for GE economics, so this is ancillary evidence only: it fails closed to
        // an uncounted review row instead of a quote-valued cost or automatic proceeds.
        boolean geExplained = !strongerSourceEvidence && settle.context != MARKET && settle.context != Context.TRANSFER
                        && !settle.hard
                        // Proceeds reach the pack only through a Collect: without one a gain is a
                        // pickup or loot, never an uncollected sale (owner live 2026-10-16:
                        // ground coins went to Review as Grand Exchange).
                        && (hasLoss(settle.flows) || inbox.at(GE_COLLECT) >= settle.now)
                        && geCustody.explains(settle.flows, settle.now);
        if (geExplained) settle.note = "Grand Exchange";
        if (geExplained || settle.context == MARKET) {
            // The movement an offer side explains is the only one it may ever own, whether the
            // GE UI context or a recent offer supplied the evidence for this settle.
            geCustody.spend(settle.flows, settle.now);
        }
        // An explicit GE interaction is source evidence, not settlement authority. Any
        // residual flow that custody did not claim must await owner review, even when
        // the broad MARKET context would otherwise classify a coins-only TRADE.
        boolean geUiUnclaimed = settle.context == MARKET && settle.note != null
                        && settle.note.startsWith("Grand Exchange");
        boolean geReview = geExplained || geUiUnclaimed || settle.pricingReview;
        TransactionType type = classifier.classify(settle.context, settle.flows);
        boolean plainUncertain = !settle.asTransfer && !settle.hard && !settle.soft() && type == UNCERTAIN;
        boolean braceletDismantle = settle.dismantle
                        && (inbox.item(DISMANTLE_CLICK) == ItemID.WILD_CAVE_BRACELET_UNCHARGED
                                || inbox.item(DISMANTLE_CLICK) == ItemID.WILD_CAVE_BRACELET_CHARGED);
        if (settle.dismantle && !braceletDismantle) {
            // Taking an item apart (Revert, Dismantle) keeps its worth in the parts: neutral, never
            // Review (owner 1.2). What the parts do not give back is booked below. The bracelet of
            // ethereum is the exception: its 250-ether dismantle is worth more than the item, so
            // the parts rule would bury the gain; it settles like the same change without the
            // click (fail-closed Review, owner 2026-10-12).
            type = TransactionType.TRANSFER;
            for (Flow flow : settle.flows)
                if (flow.itemId == inbox.item(DISMANTLE_CLICK))
                    settle.note = "Dismantled " + flow.itemName;
        } else if (settle.consumed && type != TransactionType.TRANSFER && type != TRADE) {
            // Dose leftovers / empty vials are mixed inventory deltas; confirmed
            // consume intent still books Used/lost rather than uncertain exclusion.
            type = CONSUMPTION;
        } else if (!settle.asTransfer && type == UNCERTAIN && !settle.decant && (ActionReader.isDoseStep(settle.flows)
                        || settle.soft() && hasLoss(settle.flows))) {
            // The classifier still sees mixed value: a dose shape alone is enough once soft
            // transfer has been refused, and a refused soft bank latch in a mixed Drink/Pick/Bury
            // window books supplies rather than dropping costs and harvest as UNCERTAIN.
            type = CONSUMPTION;
            settle.consumed = true;
        } else if (plainUncertain && ActionReader.all(settle.flows, -1, name -> ActionReader.isRune(name)
                                        || ActionReader.isAmmo(name) || ActionReader.isClaim(name))) {
            // A pickup settling with a routine spend (a dart thrown, a spell cast) is a gain net of
            // that
            // spend, as a loot receipt absorbs a cast; never an uncounted Review (owner
            // 2026-09-28).
            // An opened claim (a coin pouch) is income too: its neutral claim leg is not a cost
            // (owner 2026-09-29).
            type = GAIN;
        } else if (plainUncertain && settle.decant && !braceletDismantle) {
            // Combining or decanting keeps every dose, so it is neutral like a bank move, never a
            // Review row (owner 1.2); the doses keep their value until they are drunk. The bracelet
            // of ethereum's dismantle is not value-preserving, so it stays fail-closed Review
            // (owner 2026-10-12).
            type = TransactionType.TRANSFER;
            settle.note = "Decanted";
        } else if (plainUncertain && inbox.peek(DIALOGUE) != null && ActionReader.all(
                        settle.flows, -1, GameData.pattern("cash").asMatchPredicate())) {
            // Owner 2026-10-09: coins for items while talking to an NPC is a purchase from its
            // dialogue
            // (Elnock's jars, a ticket), booked like a shop's; without a conversation it stays
            // Review.
            type = TRADE;
            settle.context = MARKET;
            settle.note = "Bought";
        } else if (plainUncertain && inbox.peek(DIALOGUE) != null && ActionReader.all(
                        settle.flows, 1, GameData.pattern("cash").asMatchPredicate())) {
            // Owner 1.3.4: items for coins with a shop window open is a sale; the window raises
            // the same conversation signal as a dialogue. Without it the change stays Review.
            type = TRADE;
            settle.context = MARKET;
            settle.note = "Sold";
        }
        // A drop settle is always CONSUMPTION, so picking it back up can shrink it.
        if (settle.dropped && type != TransactionType.TRANSFER && type != TRADE) type = CONSUMPTION;
        if (!settle.loot.matched && !geReview && (type == GAIN || type == TransactionType.LOOT || type == UNCERTAIN)
                && !settle.localDeath && onlyGains(settle.flows)) {
            Transaction picked = pickUpDrops(settle);
            if (picked != null && settle.flows.isEmpty()) return picked;
        }
        if (type == CONSUMPTION) {
            // Clue dig/tele/key spends while a clue path is active.
            if (clueCostPairing.isClueCost(settle.note)) {
                settle.note = clueCostPairing.costNote();
                if (clueCostPairing.activeEncounter() != null)
                    settle.encounterId = clueCostPairing.activeEncounter();
            }
            // Final presentation stamp after all note mutations.
            settle.note = settle.dropped ? dropNote(settle.droppedItemId) : settle.destroy ? "Destroyed" : settle.note;
        }
        // Ancillary offer evidence with no claimable custody lifecycle fails closed to an
        // uncounted review row: never quote-valued cost, never automatic proceeds, no basis.
        boolean counted = !geReview && type != TransactionType.TRANSFER
                        && (type != UNCERTAIN || config.countUncertainMixedChanges());
        type = geReview ? UNCERTAIN : type;
        boolean retainTransaction = counted || config.keepTransferAuditRows() || type == UNCERTAIN;
        // The committed inventory baseline already deduplicates receipts. One encounter may
        // legitimately have several pickups; an encounter-wide revenue gate would discard them.
        settle.activity = type == TransactionType.TRANSFER ? "Transfer" : type == TRADE ? "Market" : settle.activity;
        Confidence confidence = geUiUnclaimed || settle.pricingReview ? Confidence.UNCERTAIN
                        : confidenceFor(settle.context, type);
        // Non-bank transfers explain themselves (minigame wipe, neutral zone, death).
        String explanation = settle.pricingReview ? msg("consumed-dose") : geReview ? msg("grand-exchange-movement")
                                : type == TransactionType.TRANSFER && settle.note != null && !settle.note.isEmpty()
                                                && !settle.note.matches(msg("i-bank"))
                                        ? msg("ownership-neutral-transfer-2") + settle.note + "."
                                        : explanationFor(settle.context, type, counted);
        Transaction transaction = bookTransaction(settle.now, type, settle.context, settle.note, settle.activity,
                        counted, settle.flows, confidence, explanation, settle.encounterId);
        if (!settle.dropped && !settle.destroy && !settle.suppressActionEvidence) {
            // Presentation-only evidence; type/valuation/counted above are unchanged.
            transaction.setActionKind(ActionReader.resolve(settle.actionIntent, settle.flows, type, settle.activity));
            if (transaction.getActionKind() == CAST && settle.actionLabel != null) {
                transaction.setActionLabel(settle.actionLabel);
            }
        }
        if (type == CONSUMPTION && !settle.destroy && transaction.getActionKind() == null && !settle.flows.isEmpty()
                && settle.flows.stream().allMatch(flow -> flow.isCost() && cannonball(flow.itemId))) {
            // The cannon's balls are supplies wherever they leave the pack: a Fire or Load, a
            // ship-cannon shot from the pack, a manual drop, or the loss leg of a part-pickup
            // (owner 1.3.4).
            transaction.setActionKind(SUPPLIES);
        }
        if (retainTransaction) {
            if (type != TransactionType.TRANSFER && type != TRADE && !settle.hard && !settle.soft()
                    && !settle.localDeath) {
                // The relation is written only for a claim that actually closed, so restore's
                // settled-id check can never drop a partly consumed remainder.
                String closed = claims.settleLootKey(minus(gainQuantities(settle.flows, ANY), settle.lootQuantities),
                                lossQuantities(settle.flows, KeyClaims::isLootKey));
                settle.settledClaimId = closed != null ? closed : settle.settledClaimId;
            }
            if (settle.settledClaimId != null) transaction.setSourceClaimId(settle.settledClaimId);
            // A drop or a shot lands on the ground: it stays off the paid-price record until its
            // pickup window closes.
            transaction.setOnGround(counted && (settle.dropped && type == CONSUMPTION
                                    || transaction.getActionKind() == FIRE));
            add(transaction, false);
            if (settle.encounterId != null && !settle.encounterId.isEmpty()) {
                activeSession.attachToEncounter(transaction.getId(), settle.encounterId, false);
            }
        }
        if (settle.dropped) inbox.take(DROP_CLICK, settle.droppedItemId, 1L, e -> true);
        // Fired arrows and bolts land beside the target; picking them up (into the pack or the
        // ammo slot) shrinks the shot the same way as a drop (owner 1.3.3).
        if (transaction.isOnGround()) rememberDrop(transaction, Flow::isCost);
        String[] lost = settle.dismantle ? DISMANTLE.get(inbox.item(DISMANTLE_CLICK)) : null;
        if (lost != null) {
            // Owner 1.2: a crystal tool's 120 shards are lost when it is reverted.
            bookCharge(settle.now, settle.note, Confidence.CONFIRMED, "Lost when it was taken apart.",
                    valuationService.value(Map.of(Integer.parseInt(lost[2]), -Long.parseLong(lost[1]))), null, false);
        }
        if (settle.dismantle) inbox.drop(DISMANTLE_CLICK);
        return transaction;
    }

    /** A loot-key manifest claim settled by this change's residual (non-source-backed) gains. */
    void observePriming(ContainerSnapshot current) {
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
            clearPriming();
            consumeContext();
        }
    }

    void capturePendingContext() {
        // Late inventory after bank close: hard bank-container/menu evidence still applies
        // even when soft UI context ticks were cleared on close.
        if (hasHardTransfer()) latchHardTransfer();
        if (clockLeft() <= 0) return;
        // Never attach TRANSFER to a new pending change from soft UI-open ticks alone.
        // Hard bank-container/menu evidence (or an already-latched hard pending) required.
        if (liveClue().context == Context.TRANSFER && !pendingClue().hard && !hasHardTransfer()) {
            // Soft open context may exist while bo(); do not latch it
            // into pending - noteSoftOpenTransferForPending handles settle evidence
            // only while the UI remains open, and close clears soft-only pc().
            return;
        }
        // Capture the active context once when a pending change begins. Repeated
        // snapshot observations must not merge the same expected loot again.
        // New loot events still merge through aho/kw.
        if (pendingClue().context == GENERIC || liveClue().context.priority > pendingClue().context.priority) {
            applyPendingContext(liveClue().context, liveClue().note, liveClue().expectedLoot, liveClue().encounterId);
        }
    }

    void applyPendingContext(Context newContext, String note, Map<Integer, Long> expectedLoot, String encounterId) {
        pendingClue().transfer |= newContext == Context.TRANSFER && pendingClue().hard;
        pendingClue().offer(newContext, note, expectedLoot, encounterId, false);
    }

    LootMatch consumeQueuedLoot(List<Flow> flows) {
        Map<Integer, Long> available = gainQuantities(flows, ANY);
        var matched = new HashMap<Integer, Long>();
        var groups = new LinkedHashMap<List<Object>, LootMatch>();
        for (Observation e : new ArrayList<>(inbox.open)) {
            if ((e.kind != PRIVATE_LOOT && e.kind != LOOT_RESERVE) || !inbox.live(e)
                    || inbox.now > e.source().at) continue;
            Observation source = e.source();
            long qty = inbox.take(e.kind, e.item, available.getOrDefault(e.item, 0L), v -> v == e);
            if (qty <= 0L) continue;
            take(available, e.item, qty);
            matched.merge(e.item, qty, SafeMath::safeAdd);
            LootMatch group = groups.computeIfAbsent(Arrays.asList(source.note, source.name, source.context, source.id),
                            key -> new LootMatch(true, source.note, source.name, source.context, source.id,
                                            new HashMap<>(), emptyList()));
            group.matchedQuantities.merge(e.item, qty, SafeMath::safeAdd);
        }
        // A measured gain also spends private proof after its attribution window has ended.
        available.forEach((item, qty) -> inbox.take(PRIVATE_LOOT, item, qty, e -> true));
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
        return trimmed.startsWith(prefix) && trimmed.length() > prefix.length()
                ? trimmed.substring(prefix.length()).trim() : trimmed;
    }

    boolean matchesExpectedLoot(List<Flow> flows, Map<Integer, Long> expectedLoot) {
        Map<Integer, Long> gains = gainQuantities(flows, ANY);
        return !gains.isEmpty() && (expectedLoot == null || expectedLoot.isEmpty()
                        || gains.equals(positiveEntries(expectedLoot)));
    }

    Transaction recordKeyAudit(List<Flow> keyFlows, String encounterId, long now) {
        Transaction audit = bookNeutral(now, KeyClaims.LOOT_KEY_NOTE, "PKing", keyFlows, msg("settled-loot"),
                        encounterId);
        add(audit, false);
        List<Flow> receivedKeys = gains(keyFlows, KeyClaims::isLootKey);
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
            activeSession.attachToEncounter(audit.getId(), encounterId, false);
        }
        return audit;
    }

    Transaction recordClaimAudits(List<Flow> keyFlows, long now) {
        Transaction last = null;
        for (Flow flow : keyFlows) {
            if (flow.quantityDelta <= 0L || !KeyChestCatalogue.isDeferredKey(flow.itemId)) {
                continue;
            }
            KeyChestCatalogue.Entry entry = KeyChestCatalogue.entryForKey(flow.itemId);
            if (entry == null || entry.tradeable) continue;
            Transaction audit = bookNeutral(now, "Key held · " + nonBlank(entry.chestName, "Chest"), entry.chestName,
                            singletonList(flow), msg("untradeable-chest"), null);
            audit.setActionKind(ActionKind.DEFERRED_CLAIM);
            add(audit, false);
            claims.open(audit.getId(), flow.itemId, flow.quantityDelta, now);
            last = audit;
        }
        return last;
    }

    /** Only catalogued key losses and non-key gains: the shape of a chest open. */
    static boolean onlyGainsOrKeyCosts(List<Flow> flows) {
        for (Flow flow : flows == null ? Collections.<Flow>emptyList() : flows) {
            if (flow.quantityDelta != 0L && flow.isCost() != KeyChestCatalogue.isKey(flow.itemId)) {
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
    boolean credibleKeyPickup(Clue applied, List<Flow> originalFlows, List<Flow> keyFlows) {
        Context context = applied.context;
        if (context == null || context == Context.TRANSFER || context == MARKET || applied.transfer || applied.hard
                || inbox.peek(DROP_CLICK) != null) {
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

    void resetTracking(boolean primeBaseline) {
        clearGeCollect();
        inbox.drop(LOAD_TARGET);
        baseline = null;
        baselinePriming = primeBaseline;
        clearPriming();
        clearSettledContext();
        resetHelpers();
        inbox.drop(PRIVATE_LOOT, LOOT_RESERVE, DISMANTLE_CLICK, CONSUME, SPENT_CONSUME, DROP_CLICK, DROPPED);
    }

    static boolean groundDart(Flow flow) {
        return flow != null && flow.isCost() && ChargeCheck.DARTS.containsValue(flow.itemId);
    }

    /**
     * A row whose pickup window closed (its memory expired or was cleared, or the plugin restarted)
     * is used up: what is left on it leaves the paid-price record now. Compaction settles its own
     * rows; an undone row waits, since redo brings it back.
     */
    void settleGround() {
        if (ground == null) {
            // Rebuilt from the saved flags after a restore: their memories did not survive it.
            ground = new HashMap<>();
            for (Session session : uniqueSessions()) {
                for (Transaction row : session.getTransactions()) remember(row);
                for (UndoRecord undone : session.undoHistory)
                    if (!undone.restored) remember(undone.transaction);
            }
        }
        for (Iterator<Transaction> rows = ground.values().iterator(); rows.hasNext(); ) {
            Transaction row = rows.next();
            if (!row.isOnGround()) {
                rows.remove();
                continue;
            }
            if (inbox.peek(DROPPED, e -> row.getId().equals(e.id)) != null || undone(row)) continue;
            rows.remove();
            for (Session session : uniqueSessions())
                if (session.getTransactions().contains(row)) session.usedUp(row);
        }
    }

    void remember(Transaction row) {
        if (row != null && row.isOnGround()) ground.put(row.getId(), row);
    }

    boolean undone(Transaction row) {
        for (Session session : uniqueSessions())
            for (UndoRecord record : session.undoHistory)
                if (!record.restored && record.transaction == row) return true;
        return false;
    }

    /** Rows still on the ground by id; null until rebuilt after a restore. */
    transient Map<String, Transaction> ground;

    /**
     * An own drop or fired ammo stays on the ground, yours, for 3 minutes (300 ticks); balls that
     * went into any cannon for as long as it can stand, 35 minutes (dwarf part-pickups and ship
     * volleys alike).
     */
    void rememberDrop(Transaction drop, Predicate<Flow> onGround) {
        if (ground != null) remember(drop);
        for (Flow flow : drop.getFlows()) {
            if (onGround.test(flow))
                inbox.add(DROPPED, flow.itemId, abs(flow.quantityDelta), cannonball(flow.itemId) ? 2_099 : 299, null)
                                .id = drop.getId();
        }
    }

    /** A Drop's note; balls loaded into a cannon say so. */
    static String dropNote(int itemId) {
        return cannonball(itemId) ? "Loaded into the cannon" : "Dropped";
    }

    /**
     * Picking up an item you dropped minutes ago shrinks that Dropped row instead of booking a
     * second, matching gain: drop 3 bolts and pick 3 up, and neither row shows. Any pickup of the
     * item counts, since the Net is the same whichever bolts they are. The rest books as usual.
     */
    Transaction pickUpDrops(Settle settle) {
        var picked = new ArrayList<Flow>();
        var rest = new ArrayList<Flow>();
        for (Flow flow : settle.flows) {
            long left = flow.quantityDelta - pickUp(flow.itemId, flow.quantityDelta, settle.now);
            if (left < flow.quantityDelta) picked.add(flow.part(flow.quantityDelta - left));
            if (left > 0L) rest.add(flow.rest(flow.quantityDelta - left));
        }
        if (picked.isEmpty()) return null;
        settle.flows = rest;
        Transaction row = bookTransaction(settle.now, TransactionType.TRANSFER, Context.TRANSFER, "Picked back up",
                        "Transfer", false, picked, Confidence.CONFIRMED, msg("picked-back-up"), null);
        if (config.keepTransferAuditRows()) add(row, false);
        return row;
    }

    /**
     * Shrinks remembered drops and shots of the item by up to {@code quantity}; returns how many.
     */
    long pickUp(int itemId, long quantity, long now) {
        long left = quantity;
        for (Observation e : inbox.all(DROPPED)) {
            long take = min(left, e.qty);
            if (e.item != itemId || take <= 0L) continue;
            long given = activeSession.pickUp(e.id, e.item, take, now);
            // A row that gave back less holds no more of the item: forget the rest.
            inbox.take(DROPPED, e.item, given < take ? e.qty : given, v -> v == e);
            left -= given;
        }
        return quantity - left;
    }

    /**
     * Fired darts picked up straight into the blowpipe (the game's ammo-picking setting): the shot
     * shrinks as a pickup into the pack does, and the pipe's next Check counts them as loaded, so
     * they are never taken off twice.
     */
    synchronized void pickUpIntoPipe(int itemId, long quantity, long now) {
        if (!live()) return;
        long given = pickUp(itemId, quantity, now);
        if (given > 0L)
            measuredChargeReadTracker.noteLoad(ChargedItem.BLOWPIPE, null, now, itemId, given);
    }

    /** Owner fencing clears only ephemera; persisted grave/key/custody continuity is retained. */
    synchronized void clearEphemera() {
        clearGeCollect();
        resetHelpers();
        deathReclaim.reclaimTicks = 0;
        deathReclaim.reclaimService = null;
    }

    /** The same non-inbox helpers also reset at an ordinary tracking-window boundary. */
    void resetHelpers() {
        chargeEstimateJournal.clear();
        measuredChargeReadTracker.reset();
        neutralZoneStoredItems.clear();
        neutralZoneTransferActive = false;
        captureNeutralZoneEntry = false;
        neutralZoneEntryBaseline = null;
        neutralZoneEntryCaptureTicks = 0;
        neutralZoneRestoreTicks = 0;
        clueCostPairing.clear();
        claims.clearEvidence();
    }

    void clearPendingChange() {
        dirty = false;
        pendingSnapshot = null;
        pendingStableTicks = 0;
        inbox.drop(PENDING_CLAIM);
    }

    /** The priming window ends: its counters and any pending change reset together. */
    void clearPriming() {
        primingSnapshot = null;
        primingObservedTicks = 0;
        primingStableTicks = 0;
        clearPendingChange();
    }

    boolean matchesConsume(List<Flow> flows, boolean hardTransferEvidence) {
        if (consumeIntent() == null || empty(flows)) return false;
        // Match the spent stack even when a dose leftover / empty vial appears as a gain.
        if (consumeIntent().item >= 0 && hasLoss(flows, id -> id == consumeIntent().item))
            return true;
        // Open intent (menu item id missing) or id mismatch after canonicalize: still
        // confirm Drink/Eat when the delta is a dose/partial leftover, or a pure cost
        // (Bury/Cast runes/ammo) so hard TRANSFER cannot swallow the spend.
        if (ActionReader.isDoseStep(flows)) return true;
        if (onlyCosts(flows)) {
            // Named intent for a different item + hard bank deposit must stay TRANSFER
            // (stale bury of bones must not book oak-log deposit as Used/lost).
            if (hardTransferEvidence && consumeIntent().item >= 0) return false;
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
     * Pickup then bury (etc.) in one stabilize window nets to zero vs baseline. Named consume
     * intent stores invent qty at arm — synthesize the shortfall loss.
     */
    Map<Integer, Long> sameTickConsumeDeltas(ContainerSnapshot current) {
        if (consumeIntent() == null || consumeIntent().item < 0 || consumeIntent().held < 0L || current == null) {
            return emptyMap();
        }
        long nowQty = current.quantityOf(consumeIntent().item);
        long lost = consumeIntent().held - nowQty;
        if (lost <= 0L) return emptyMap();
        var synthetic = new HashMap<Integer, Long>();
        synthetic.put(consumeIntent().item, -lost);
        return synthetic;
    }

    void consumeContext() {
        inbox.drop(LIVE_CLAIM, CLOCK, SOFT_TRANSFER);
        // hf() survives until settle or hardTransferEvidenceTicks expiry
        // so bank-container evidence is not lost on the idle GameTick before inventory dirty.
    }

    void clearSettledContext() {
        consumeContext();
        clearHardTransfer();
    }

    void clearHardTransfer() {
        inbox.drop(HARD_TRANSFER);
    }

    Confidence confidenceFor(Context appliedContext, TransactionType type) {
        if (appliedContext == Context.TRANSFER || appliedContext == MARKET || appliedContext == LOOT
                || appliedContext == PK_LOOT || appliedContext == PK_DEATH) {
            return Confidence.CONFIRMED;
        }
        if (type == UNCERTAIN) return Confidence.UNCERTAIN;
        return Confidence.LIKELY;
    }

    /** Loss ids claimed by exact consume/drop intent are excluded from the death wipe. */
    Map<Integer, Long> confirmedActionLossQuantities(List<Flow> flows, boolean hardTransferEvidence) {
        var excluded = new HashMap<Integer, Long>();
        if (consumeIntent() == null) {
            return inbox.peek(Inbox.DROP_CLICK) == null ? excluded
                    : lossQuantities(flows, id -> id == inbox.item(DROP_CLICK));
        }
        if (consumeIntent().item >= 0) {
            return lossQuantities(flows, id -> id == consumeIntent().item);
        }
        // An open-id intent with only costs cannot distinguish which item was consumed; keep
        // every loss in this ambiguous batch counted.
        return ActionReader.addDoseLosses(excluded, flows) || !matchesConsume(flows, hardTransferEvidence) ? excluded
                : lossQuantities(flows, ANY);
    }

    String explanationFor(Context appliedContext, TransactionType type, boolean counted) {
        switch (appliedContext) {
            case TRANSFER:
                return msg("excluded-because");
            case MARKET:
                return counted ? msg("trade-inventory") : msg("grand-exchange-shop");
            case LOOT:
                return msg("matched-item-ids");
            case PK_LOOT:
                return msg("matched-item-ids-quantities");
            case PK_DEATH:
                return msg("inventory-equipment");
            case PRODUCTION:
                return type == CONSUMPTION ? msg("failed-inputs") : msg("simultaneous-inputs");
            case GENERIC:
            default:
                if (type == UNCERTAIN) {
                    return counted ? msg("mixed-gains-costs") : msg("mixed-gains-costs-confirmed");
                }
                if (type == GAIN) return msg("stable-inventory-equipment-value");
                if (type == CONSUMPTION) return msg("stable-inventory-equipment");
                return msg("stable-inventory-equipment-change");
        }
    }

    /**
     * A load whose exact quantities are ambiguous still moves value into charges: neutral by law,
     * never a cost and never an incomplete Review (owner 2026-09-29).
     */
    Transaction recordAmbiguousLoad(LoadTarget partition, long now) {
        if (partition == null || partition.ambiguousCandidates.isEmpty() || !live()) return null;
        Transaction transfer = bookNeutral(now, msg("charge-load-transfer"), "Charge load",
                        partition.ambiguousCandidates, msg("same-target-measured-check"), null);
        transfer.setActionKind(ActionKind.CHARGE_LOAD_AMBIGUOUS);
        add(transfer, false);
        // The weapon's next Check adds these back, so a reload is never read as darts unused.
        for (Flow flow : partition.ambiguousCandidates) {
            measuredChargeReadTracker.noteLoad(partition.variant, partition.targetIdentity, partition.clickedAt,
                    flow.itemId, SafeMath.safeMultiply(abs(flow.quantityDelta),
                            ChargeCheck.unitsPerItem(partition.variant, flow.itemId)));
        }
        return transfer;
    }

    /**
     * A load whose components can never be refunded (crystal armour and the crystal shield): the
     * shards are committed to the piece, so they book as a counted Supplies spend now instead of a
     * neutral transfer (owner 2026-10-12). A revert keeps the seeds and loses these charges; the
     * piece keeps no Check baseline to reconcile.
     */
    Transaction recordCommittedLoad(LoadTarget partition, long now) {
        if (partition == null || partition.ambiguousCandidates.isEmpty() || !live()) return null;
        return bookCharge(now, msg("committed-load") + nonBlank(partition.variant.getDisplayName(), "Crystal charges"),
                Confidence.CONFIRMED, msg("committed-load-why") + partition.variant.getDisplayName() + ".",
                partition.ambiguousCandidates, partition.variant.action, false);
    }

    Transaction advanceContext(long now) {
        if (dirty || pendingSnapshot != null) return null;
        if (clockLeft() <= 0) consumeContext();
        if (neutralZoneRestoreTicks > 0 && --neutralZoneRestoreTicks <= 0) {
            neutralZoneRestoreTicks = 0;
            neutralZoneStoredItems.clear();
        }
        return null;
    }

    /**
     * A measured death wipe may own this change: a death-held transfer, or an unclaimed generic
     * change.
     */
    static boolean isDeathHeldNote(String note) {
        return note != null && note.startsWith("Death: items held");
    }

    synchronized Metrics getMetrics(long now) {
        if (activeSession == null) return Metrics.none();
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

    synchronized Session historySession(String sessionId) {
        return archive.historySession(sessionId);
    }

    /** Metrics of a retained history session; null when the id is not in history. */
    synchronized Metrics historyMetrics(String sessionId, long now) {
        return archive.historyMetrics(sessionId, now);
    }

    synchronized boolean deleteHistory(String sessionId) {
        return archive.deleteHistory(sessionId);
    }

    /** Clears only GP Manager tracking state; configuration is intentionally outside the engine. */
    synchronized void resetData(long now) {
        history.clear();
        bumpRevision();
        claims.clear();
        // A reset is a new install: no session until gameplay (with Automatic tracking) or Start.
        generalSession = null;
        customSession = null;
        activeSession = null;
        // Destructive reset clears the schema-104 custody lifecycles with the rest.
        geCustody.reset();
        trackedBasis.reset();
        ground = null;
        clearGeCollect();
        // Destructive reset clears the pending death/reclaim physical evidence with it.
        deathReclaim.reset();
        // The looting bag's dispenser additions belong to the account that earned them.
        bagAdded.clear();
        bagReward.clear();
        inbox.drop(DROP_CLICK);
        resetTracking(true);
        // A reset is a fresh start: resume by itself shortly, if the player does nothing first.
        autoStartAtEpochMillis = now + 4_000L;
    }

    synchronized long bestKill() {
        return activeSession == null ? 0L : activeSession.bestKill();
    }

    synchronized SavedState savedState() {
        var state = new SavedState(generalSession, customSession, history);
        if (!blank(profileIdentityKey))
            state.setOwnerKey(profileIdentityKey);
        state.setProfileTimeZoneId(profileTimeZoneId);
        claims.writeTo(state);
        state.setPendingDeathReclaim(deathReclaim.snapshot());
        state.setSavedGrinds(savedGrinds);
        // Schema-104 custody continuity travels in the same bounded generation as the canonical
        // settlements that depend on it.
        geCustody.writeTo(state);
        trackedBasis.writeTo(state);
        return state;
    }

    // ---- My Grinds: reusable setup metadata, never financial truth --------------------------
    /** Reusable My Grind definitions; setup and default targets only. */
    List<SavedGrind> savedGrinds = new ArrayList<>();

    /** All My Grinds; archived ones only when asked for. */
    synchronized List<SavedGrind> getSavedGrinds(boolean includeArchived) {
        var result = new ArrayList<SavedGrind>();
        for (SavedGrind grind : savedGrinds) {
            if (!grind.archived || includeArchived) result.add(grind);
        }
        return result;
    }

    /** Saved-Grind lookup by stable id; null when unknown. */
    synchronized SavedGrind savedGrind(String grindId) {
        for (SavedGrind grind : savedGrinds) {
            if (grind.getGrindId().equals(grindId)) return grind;
        }
        return null;
    }

    /** Creates a reusable My Grind from reusable setup only; runs link to it as they start. */
    synchronized SavedGrind saveGrind(String name, Long netTargetGp, Long activeTimeMillis, boolean favorite) {
        String cleanName = nonBlank(name, "");
        if (cleanName.isEmpty()) return null;
        var grind = new SavedGrind(UUID.randomUUID().toString(), cleanName, netTargetGp, activeTimeMillis, favorite);
        savedGrinds.add(grind);
        return grind;
    }

    /**
     * Edits a My Grind. Finished runs keep the targets they ran with; a running run of this Grind
     * takes the new name and any changed target at once (owner 1.5.0: one Grind, one setup).
     */
    synchronized boolean updateSavedGrind(String grindId, String name, Long netTargetGp, Long activeTimeMillis,
            boolean favorite) {
        SavedGrind grind = savedGrind(grindId);
        if (grind == null) return false;
        String cleanName = nonBlank(name, "");
        // Only a changed target reaches the run: a rename or a favourite never re-arms its aim.
        boolean retarget = !Objects.equals(netTargetGp, grind.netTargetGp)
                        || !Objects.equals(activeTimeMillis, grind.activeTimeTargetMillis);
        if (!cleanName.isEmpty()) grind.setName(cleanName);
        grind.setNetTargetGp(netTargetGp);
        grind.setActiveTimeTargetMillis(activeTimeMillis);
        grind.setFavorite(favorite);
        Session run = customSession;
        if (run != null && grindId.equals(run.getGrindId())) {
            if (!cleanName.isEmpty()) renameActive(cleanName);
            if (retarget) setTargets(netTargetGp, activeTimeMillis, SafeMath.now());
        }
        return true;
    }

    /** The My Grind with this name, ignoring case; null when there is none. */
    synchronized SavedGrind grindNamed(String name) {
        for (SavedGrind grind : savedGrinds) {
            if (grind.getName().equalsIgnoreCase(nonBlank(name, "").trim())) return grind;
        }
        return null;
    }

    synchronized boolean archiveGrind(String grindId, boolean archived) {
        SavedGrind grind = savedGrind(grindId);
        if (grind == null) return false;
        grind.setArchived(archived);
        return true;
    }

    /** Removes the reusable definition only; canonical history and lineage links survive. */
    synchronized boolean deleteSavedGrind(String grindId) {
        SavedGrind grind = savedGrind(grindId);
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
    synchronized List<Session> completedLinked(String grindId) {
        var result = new ArrayList<Session>();
        for (Session session : linkedSessions(grindId)) {
            if (session != null && session.isClosed()) result.add(session);
        }
        return result;
    }

    /**
     * Starts one fresh canonical Grind. Every Grind is remembered (owner 1.5.0): the run links to
     * its My Grind, found by id, else by name, else created, and the targets it starts with become
     * that Grind's targets. No financial state, loot, active time or correction state is copied.
     */
    synchronized boolean startGrind(
            String name, String grindId, Long netTargetGp, Long activeTimeTargetMillis, long now) {
        String cleanName = nonBlank(name, "");
        if (cleanName.isEmpty() || customSession != null) return false;
        SavedGrind grind = savedGrind(grindId);
        if (grind == null) grind = grindNamed(cleanName);
        if (grind == null) grind = saveGrind(cleanName, null, null, false);
        grind.setArchived(false);
        grind.setNetTargetGp(netTargetGp);
        grind.setActiveTimeTargetMillis(activeTimeTargetMillis);
        startCustom(grind.getName(), now);
        Session active = activeSession;
        if (active == null) return false;
        active.setGrindId(grind.getGrindId());
        active.setProfitTargetGp(netTargetGp);
        active.setActiveTimeTargetMillis(activeTimeTargetMillis, now);
        return true;
    }

    /**
     * Renames the running Grind; a change like any other, so the revision advances and it is saved.
     */
    synchronized void renameActive(String name) {
        if (activeSession != null) {
            activeSession.rename(name);
            bumpRevision();
        }
    }

    /** Mid-Grind aim edit: changes the current target snapshot only, never financial truth. */
    synchronized boolean setTargets(Long netTargetGp, Long activeTimeTargetMillis, long now) {
        if (activeSession == null) return false;
        boolean net = activeSession.setProfitTargetGp(netTargetGp == null || netTargetGp <= 0L ? null : netTargetGp,
                        getMetrics(now).net, activeSession.getElapsedMillis(now));
        boolean time = activeSession.setActiveTimeTargetMillis(
                        activeTimeTargetMillis == null || activeTimeTargetMillis <= 0L ? null : activeTimeTargetMillis,
                        now);
        // A new or changed target starts a session; re-saving one keeps its count, and Done or
        // Clear leaves the session counting (owner 1.5.2).
        if ((net || time) && (activeSession.getProfitTargetGp() != null
                        || activeSession.getActiveTimeTargetMillis() != null)) newLiveSession(now);
        return true;
    }

    /**
     * The Targets sheet's save: the run's targets change and, during a Grind, so do that Grind's,
     * so the next run starts with them (owner 1.5.0). Done clears the run's aim only.
     */
    synchronized boolean saveTargets(Long netTargetGp, Long activeTimeTargetMillis, long now) {
        if (!setTargets(netTargetGp, activeTimeTargetMillis, now)) return false;
        SavedGrind grind = customSession == null ? null : savedGrind(customSession.getGrindId());
        if (grind != null) {
            grind.setNetTargetGp(activeSession.getProfitTargetGp());
            grind.setActiveTimeTargetMillis(activeSession.getActiveTimeTargetMillis());
        }
        return true;
    }

    /** Again: a new session with the same target, in the same run (owner 1.5.2). */
    synchronized boolean restartTarget(long now) {
        if (activeSession == null || activeSession.getProfitTargetGp() == null
                        && activeSession.getActiveTimeTargetMillis() == null) return false;
        newLiveSession(now);
        return true;
    }

    /** Toggles the timer's own pause; the run keeps tracking (owner live 2026-10-14). */
    synchronized boolean toggleTimeTargetPause(long now) {
        if (activeSession == null || activeSession.getActiveTimeTargetMillis() == null) return false;
        return activeSession.pauseTimeTarget(now) || activeSession.resumeTimeTarget(now);
    }

    /** Every session the engine opens follows the detected activity (AUTO). */
    Session newSession(String name, long now, boolean grind) {
        var session = new Session(name, now, SessionMode.AUTO);
        session.grind = grind;
        attachListeners(session);
        bumpRevision();
        return session;
    }

    void attachListeners(Session session) {
        if (session != null) {
            session.setChangeListener(this::bumpRevision);
            session.ownedValueObserver = trackedBasis;
        }
    }

    void bumpRevision() {
        revision = revision == Long.MAX_VALUE ? 1L : revision + 1L;
    }

    static boolean validZone(String value) {
        if (blank(value)) return false;
        try {
            ZoneId.of(value);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Per-source evidence: a confirmed Eat/Drink/Bury/Cast action. Decays on its own TTL via {@link
     * Inbox} — independent of bank-open/close evidence, so a stale or active bank signal can never
     * suppress a confirmed consume. See {@code com.gpmanager.engine.evidence} for why evidence
     * lifetimes are tracked per-source rather than with one shared flag.
     */
    /**
     * One context claim on the next inventory change: what it is, why, which loot, which encounter.
     */
    static class Clue {
        Context context = GENERIC;
        String note = "";
        Map<Integer, Long> expectedLoot = emptyMap();
        String encounterId;

        /**
         * Bank/deposit evidence during this change; {@code hard} is a real container/menu movement.
         */
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

        /**
         * A higher-priority (or forced) claim replaces this one; the same context merges its
         * details.
         */
        void offer(Context claim, String claimNote, Map<Integer, Long> loot, String encounter, boolean replace) {
            if (replace || claim.priority > context.priority) {
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
}

/** Source-backed quantities and their distinct source/encounter owners, in observation order. */
@AllArgsConstructor
class LootMatch {
    static final LootMatch NONE = new LootMatch(false, "", "General", Context.GENERIC, null,
                    Collections.<Integer, Long>emptyMap(), Collections.emptyList());
    boolean matched;
    String note;
    String activityName;
    Context context;
    String encounterId;
    Map<Integer, Long> matchedQuantities;
    List<LootMatch> sources;
}

@Singleton
class TransactionClassifier {
    TransactionType classify(Context context, List<Flow> flows) {
        if (context == Context.TRANSFER) return TransactionType.TRANSFER;
        boolean hasGain = false;
        boolean hasCost = false;
        for (Flow flow : flows) {
            // Direction comes from quantity. An unavailable unit price produces a
            // zero value, but it is still a real gain or cost that belongs in Review.
            hasGain |= flow.isGain();
            hasCost |= flow.isCost();
        }
        if (context == MARKET) return TRADE;
        if (context == Context.PK_LOOT && hasGain) return TransactionType.PK_LOOT;
        if (context == PK_DEATH && hasCost) return PK_DEATH_LOSS;
        if (context == Context.LOOT && hasGain) return TransactionType.LOOT;
        if (hasGain && hasCost) {
            return context == PRODUCTION ? PROCESSING : UNCERTAIN;
        }
        if (hasGain) return GAIN;
        if (hasCost) return CONSUMPTION;
        return ADJUSTMENT;
    }
}

/** Pending evidence inbox. FE fee, CH chest, TK ground click, BG bag gain, KT kit snapshot. */
/** DI dismantle click, GE collect interaction, TA fired-ammo pickup intent. */
/** DO drop click, DROPPED an own drop still on the ground, FA recoverable fired-ammo loss. */
/** CN a consume command, including its named inventory baseline and presentation hints. */
/** SC an earlier consume whose item already left the pack, displaced by a newer click. */
/** LT private loot (at bounds attribution); LP/RD quantities reserved by an own-pile despawn. */
/**
 * CL load target, CK exact Check link, RV uncharge return; command quantities never prove money.
 */
/** CX/CP live/stabilizing claims, CT attribution clock, SF/HF soft/hard transfer, BO bank UI. */
class Inbox {
    static final int FEE = 1, CHEST = 2, GROUND_CLICK = 3, BAG_GAIN = 4, KIT_SNAPSHOT = 5, DISMANTLE_CLICK = 6,
            GE_COLLECT = 7, DROP_CLICK = 9, DROPPED = 10, CONSUME = 12, PRIVATE_LOOT = 13, LOOT_RESERVE = 14,
            LOAD_TARGET = 16, CHECK_LINK = 17, UNCHARGE = 18, LIVE_CLAIM = 19, PENDING_CLAIM = 20, CLOCK = 21,
            SOFT_TRANSFER = 22, HARD_TRANSFER = 23, BANK_UI = 24, SERVANT = 25, SAWMILL = 26, DIALOGUE = 27,
            PIPE_PICKUP = 28, CANNON = 29, SPENT_CONSUME = 30;
    volatile long owner;

    /** Legacy lifecycle ephemera clear here; their durable state keeps its tested lifecycle. */
    Runnable reset = () -> {};

    int now;

    /** Proved context stays latched while its measured snapshot stabilizes, as in 1.2. */
    boolean hold;

    List<Observation> open = new ArrayList<>();

    synchronized Observation add(int kind, int item, long qty, int ticks, String note) {
        var e = new Observation();
        e.kind = kind;
        e.owner = owner;
        e.item = item;
        e.qty = qty;
        e.until = (int) Math.min(Integer.MAX_VALUE, (long) now + ticks);
        e.note = note;
        open.add(e);
        return e;
    }

    /** Replace a single observation, such as the latest click or chat confirmation. */
    synchronized Observation put(int kind, int item, long qty, int ticks, String note) {
        drop(kind);
        return add(kind, item, qty, ticks, note);
    }

    int item(int kind) {
        Observation e = peek(kind);
        return e == null ? -1 : e.item;
    }

    long at(int kind) {
        Observation e = peek(kind);
        return e == null ? 0L : e.at;
    }

    boolean has(int kind, int item) {
        return peek(kind, e -> e.item == item) != null;
    }

    Engine.Clue clue(int kind) {
        Observation e = peek(kind);
        if (e == null) {
            e = add(kind, -1, 1L, Integer.MAX_VALUE, null);
            e.data = new Engine.Clue();
        }
        return (Engine.Clue) e.data;
    }

    int left(int kind) {
        Observation e = peek(kind);
        return e == null ? 0 : e.until - now + 1;
    }

    void ttl(int kind, int ticks) {
        if (ticks <= 0) drop(kind);
        else put(kind, -1, 1L, ticks - 1, null);
    }

    synchronized Observation peek(int kind, Predicate<Observation> match) {
        for (Observation e : open) if (e.kind == kind && live(e) && match.test(e)) return e;
        return null;
    }

    Observation peek(int kind) {
        return peek(kind, e -> true);
    }

    /** Consume no more than the still-open proof, even when a caller retains a stale record. */
    synchronized long take(int kind, int item, long qty, Predicate<Observation> match) {
        long left = Math.max(0L, qty);
        for (Observation e : open) {
            if (e.kind != kind || e.item != item || !live(e) || !match.test(e)) continue;
            long used = Math.min(left, e.qty);
            e.qty -= used;
            left -= used;
            if (left == 0L) break;
        }
        open.removeIf(e -> !live(e));
        return Math.max(0L, qty) - left;
    }

    boolean live(Observation e) {
        return e.owner == owner && e.qty > 0L && now <= e.until;
    }

    /**
     * Reserve proven private quantity for the bag, retaining its original source for settlement.
     */
    synchronized long reserve(int kind, int item, long qty, int into) {
        long left = Math.max(0L, qty);
        for (Observation e : all(kind)) {
            if (e.item != item) continue;
            int index = open.indexOf(e);
            long used = take(kind, item, left, v -> v == e);
            if (used > 0L) {
                Observation part = add(into, item, used, e.until - now, e.note);
                part.data = e;
                open.remove(part);
                open.add(Math.min(index, open.size()), part);
            }
            left -= used;
            if (left == 0L) break;
        }
        return Math.max(0L, qty) - left;
    }

    synchronized List<Observation> all(int kind) {
        var found = new ArrayList<Observation>();
        for (Observation e : open) if (e.kind == kind && live(e)) found.add(e);
        return found;
    }

    void refresh(int kind, int ticks) {
        Observation e = peek(kind);
        if (e != null) e.until = Math.max(e.until, now + Math.max(1, ticks) - 1);
    }

    synchronized void drop(int... kinds) {
        for (int kind : kinds) open.removeIf(e -> e.kind == kind);
    }

    synchronized void tick(int tick) {
        if (hold)
            for (Observation e : open)
                if (e.kind >= CLOCK && e.kind <= HARD_TRANSFER && e.until < Integer.MAX_VALUE)
                    e.until += tick - now;
        now = tick;
        open.removeIf(e -> !live(e));
    }

    /**
     * Sole account boundary; called with Persistence's existing request generation, including A ->
     * B -> A.
     */
    void owner(long token) {
        synchronized (this) {
            owner = token;
            open.clear();
        }
        // Never take the engine monitor while holding the inbox monitor.
        reset.run();
    }
}

/** One owner-scoped observation. Quantity is spent once; until is an inclusive inbox tick. */
class Observation {
    int kind, item, x, y, until;
    long owner, qty;

    /** Source tick/time and inventory quantity at the observation. */
    long at, held;

    /** Receipt or physical target identity, independent of the human source label. */
    String id;

    String note;

    /** Typed source snapshot (kit contents); never persisted. */
    Object data;

    /** Consume command metadata: Destroy, verb, trusted spell label. */
    boolean flag;

    ActionKind action;
    ActionLabel label;

    /** Loot source context and activity; id is its encounter. */
    ChargeCheck.ChargedItem variant;

    Context context;
    String name;

    Observation source() {
        return data instanceof Observation ? ((Observation) data).source() : this;
    }
}

/** Null-tolerant, allocation-light predicates and projections over settled item flows. */
class FlowFilters {
    static final IntPredicate ANY = itemId -> true;

    /** Positive flows whose item matches. */
    static List<Flow> gains(List<Flow> flows, IntPredicate item) {
        var out = new ArrayList<Flow>();
        if (flows != null) {
            for (Flow flow : flows) {
                if (flow.quantityDelta > 0L && item.test(flow.itemId))
                    out.add(flow);
            }
        }
        return out;
    }

    /** Flows minus those whose item matches (positive-only when {@code gainsOnly}). */
    static List<Flow> without(List<Flow> flows, IntPredicate item, boolean gainsOnly) {
        if (flows == null) return Collections.emptyList();
        var out = new ArrayList<Flow>(flows.size());
        for (Flow flow : flows) {
            if (!item.test(flow.itemId) || (gainsOnly && flow.quantityDelta <= 0L))
                out.add(flow);
        }
        return out;
    }

    /** Positive entries of {@code quantities} whose item does not match. */
    static Map<Integer, Long> withoutItems(Map<Integer, Long> quantities, IntPredicate item) {
        var out = new HashMap<Integer, Long>();
        if (quantities != null) {
            for (Entry<Integer, Long> e : quantities.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0L && !item.test(e.getKey())) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out.isEmpty() ? Collections.emptyMap() : out;
    }

    /** Positive entries only (fresh mutable map; merges duplicates). */
    static Map<Integer, Long> positiveEntries(Map<Integer, Long> quantities) {
        var out = new HashMap<Integer, Long>();
        if (quantities != null) {
            for (Entry<Integer, Long> e : quantities.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0L) {
                    out.merge(e.getKey(), e.getValue(), SafeMath::safeAdd);
                }
            }
        }
        return out;
    }

    /**
     * The shared settle-later claim: offers each matching flow's magnitude to {@code claimer},
     * which returns how much its lifecycle takes, leaves only the unclaimed rest in {@code flows}
     * and returns the claimed parts.
     */
    static List<Flow> claim(List<Flow> flows, Predicate<Flow> which, ToLongBiFunction<Flow, Long> claimer) {
        var parts = new ArrayList<Flow>();
        for (int index = 0; index < flows.size(); index++) {
            Flow flow = flows.get(index);
            if (flow.quantityDelta == Long.MIN_VALUE || !which.test(flow)) continue;
            long taken = claimer.applyAsLong(flow, SafeMath.abs(flow.quantityDelta));
            if (taken > 0L) parts.add(flow.part(taken));
            Flow rest = flow.rest(taken);
            if (rest == null) flows.remove(index--);
            else flows.set(index, rest);
        }
        return parts;
    }

    /** Positive remainders of {@code a} minus {@code b}: what left between two observations. */
    static Map<Integer, Long> minus(Map<Integer, Long> base, Map<Integer, Long> subtract) {
        var out = new LinkedHashMap<Integer, Long>();
        if (base != null) {
            for (Entry<Integer, Long> e : base.entrySet()) {
                Long less = subtract == null ? null : subtract.get(e.getKey());
                long left = (e.getValue() == null ? 0L : e.getValue()) - (less == null ? 0L : nonNeg(less));
                if (e.getKey() != null && left > 0L) out.put(e.getKey(), left);
            }
        }
        return out;
    }

    /** True when every wanted quantity fits inside {@code available}. */
    static boolean covers(Map<Integer, Long> available, Map<Integer, Long> wanted) {
        if (empty(wanted) || empty(available)) return false;
        for (Entry<Integer, Long> e : wanted.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0L
                    || available.getOrDefault(e.getKey(), 0L) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** Removes up to {@code max} of one item and returns the quantity taken. */
    static long take(Map<Integer, Long> quantities, int itemId, long max) {
        if (quantities == null || max <= 0L) return 0L;
        long held = quantities.getOrDefault(itemId, 0L);
        long taken = min(nonNeg(held), max);
        if (held - taken <= 0L) quantities.remove(itemId);
        else quantities.put(itemId, held - taken);
        return taken;
    }

    /** Summed positive quantities per matching item. */
    static Map<Integer, Long> gainQuantities(List<Flow> flows, IntPredicate item) {
        return quantities(flows, item, true);
    }

    /** Summed magnitudes of negative deltas per matching item. */
    static Map<Integer, Long> lossQuantities(List<Flow> flows, IntPredicate item) {
        return quantities(flows, item, false);
    }

    static Map<Integer, Long> quantities(List<Flow> flows, IntPredicate item, boolean gains) {
        var out = new HashMap<Integer, Long>();
        if (flows != null) {
            for (Flow flow : flows) {
                if (!item.test(flow.itemId)) continue;
                long delta = flow.quantityDelta;
                if (gains ? delta > 0L : delta < 0L) {
                    long magnitude = SafeMath.abs(delta);
                    out.merge(flow.itemId, magnitude, SafeMath::safeAdd);
                }
            }
        }
        return out;
    }

    /**
     * Removes same-variant charge-state swaps (full -> partial -> uncharged): one physical weapon
     * changing item id is neither a gain nor a loss. A 1:1 proxy pair (X and X(i)/(ei)/(ri)) is the
     * same item changing state too, so an imbue never books a loss and a gain (owner 2026-10-12).
     * Leftover flows keep their own rows.
     */
    static List<Flow> withoutSwaps(List<Flow> flows) {
        boolean[] dropped = new boolean[flows.size()];
        for (int i = 0; i < flows.size(); i++) {
            Flow cost = flows.get(i);
            if (cost == null || cost.quantityDelta >= 0L) continue;
            ChargeCheck.ChargedItem variant = ChargeCheck.supportedForId(cost.itemId);
            for (int j = 0; j < flows.size(); j++) {
                Flow gain = flows.get(j);
                if (dropped[j] || gain == null || gain.quantityDelta <= 0L || gain.itemId == cost.itemId
                        || abs(gain.quantityDelta) != abs(cost.quantityDelta)) {
                    continue;
                }
                if (variant != null && ChargeCheck.supportedForId(gain.itemId) == variant
                        || reducedBase(cost.itemId) == reducedBase(gain.itemId)) {
                    dropped[i] = true;
                    dropped[j] = true;
                    break;
                }
            }
        }
        var result = new ArrayList<Flow>();
        for (int i = 0; i < flows.size(); i++) {
            if (!dropped[i]) result.add(flows.get(i));
        }
        return result;
    }

    /** The item's 1:1 proxy base (an imbued variant reduces to its plain item), else itself. */
    static int reducedBase(int itemId) {
        int[] proxy = CurrencyProxyCatalogue.proxyOf(itemId);
        return proxy != null && proxy[1] == 1 ? proxy[0] : itemId;
    }

    static boolean hasLoss(List<Flow> flows) {
        return hasLoss(flows, ANY);
    }

    static boolean hasLoss(List<Flow> flows, IntPredicate item) {
        if (flows != null) {
            for (Flow flow : flows) {
                if (flow.quantityDelta < 0L && item.test(flow.itemId)) return true;
            }
        }
        return false;
    }

    /** True when every non-null flow is on one side and at least one is non-zero. */
    static boolean onlyGains(List<Flow> flows) {
        return oneSided(flows, true);
    }

    static boolean onlyCosts(List<Flow> flows) {
        return oneSided(flows, false);
    }

    static boolean oneSided(List<Flow> flows, boolean gains) {
        if (empty(flows)) return false;
        boolean saw = false;
        for (Flow flow : flows) {
            long delta = flow.quantityDelta;
            if (gains ? delta < 0L : delta > 0L) return false;
            saw |= delta != 0L;
        }
        return saw;
    }
}

/**
 * PvM death → reclaim lifecycle. A separate held-stack snapshot is intersected with measured
 * negative inventory flows to establish which items actually left the player. Those exact
 * quantities remain ownership-neutral until reclaimed or the gravestone timer expires.
 *
 * <p>Fees are never inferred here; the engine can book only an observed carried-coin loss. A timer
 * expiry records an informational audit event and never creates an item loss.
 *
 * <p>Pure tick-counted state; the engine owns the single instance.
 */
class DeathReclaimLifecycle {
    /** Ticks a gravestone retains items. */
    static final int AWAIT_TICKS = 1_500; // 15 minutes

    /** Keep a grave interaction armed through the usual open/confirm/claim sequence. */
    static final int MIN_RECLAIM_ARM_TICKS = 200; // 2 minutes

    /** Bound delayed death-wipe reconciliation so later ordinary losses cannot match it. */
    static final int DEATH_WIPE_WINDOW_TICKS = 100; // 1 minute

    @Getter(AccessLevel.PACKAGE)
    boolean awaitingReclaim;

    int deathWipeTicks;
    int ageTicks;
    int reclaimTicks;
    BossRetrievalCatalogue.Service reclaimService;

    /** Stacks held at death not yet matched to a measured loss; null when no wipe is pending. */
    Map<Integer, Long> carriedAtDeath;

    /** Measured grave contents still outstanding, with the names used by the expiry note. */
    Map<Integer, Long> deathItems = new HashMap<>();

    Map<Integer, String> names = new HashMap<>();

    /** Local unsafe PvM death: the snapshot is ownership evidence, not presentation evidence. */
    void onPvmDeath(Map<Integer, Long> heldItems) {
        // A second death moves outstanding items to the new gravestone. Keep their
        // quantities and restart the single gravestone timer with the new grave.
        if (!awaitingReclaim) deathItems.clear();
        awaitingReclaim = true;
        // Missing canonical evidence is not permission to neutralize arbitrary losses.
        Map<Integer, Long> held = positiveEntries(heldItems);
        carriedAtDeath = held.isEmpty() ? null : held;
        deathWipeTicks = carriedAtDeath == null ? 0 : DEATH_WIPE_WINDOW_TICKS;
        ageTicks = 0;
        reclaimTicks = 0;
        reclaimService = null;
        completeIfEmpty();
    }

    /** True while waiting for the measured death inventory/equipment removal. */
    boolean wipePending() {
        return awaitingReclaim && carriedAtDeath != null;
    }

    /** Stop the short death-wipe whitelist when stronger later evidence owns the loss. */
    void cancelDeathWipe() {
        carriedAtDeath = null;
        deathWipeTicks = 0;
        completeIfEmpty();
    }

    /** Consume death-time candidates whose later loss is explicitly action-attributed. */
    void excludeActionLosses(Map<Integer, Long> actionLosses) {
        if (carriedAtDeath == null || Phrases.empty(actionLosses)) return;
        positiveEntries(actionLosses).forEach((itemId, lost) -> take(carriedAtDeath, itemId, lost));
        endWipeIfSpent();
    }

    /**
     * Intersect a settled negative-flow batch with the stacks held at death. The returned
     * quantities identify the exact negative flow portions that may be labelled as the
     * ownership-neutral death transfer.
     */
    Map<Integer, Long> onDeathItemsRemoved(List<Flow> flows) {
        if (!wipePending()) return emptyMap();
        var removed = new HashMap<Integer, Long>();
        for (Flow flow : flows == null ? Collections.<Flow>emptyList() : flows) {
            if (flow.quantityDelta >= 0L || flow.quantityDelta == Long.MIN_VALUE
                    // A live retrieval interaction makes measured coin loss a possible
                    // fee; do not consume it as a delayed death wipe.
                    || flow.itemId == ItemID.COINS && isReclaimArmed()) {
                continue;
            }
            long matched = take(carriedAtDeath, flow.itemId, -flow.quantityDelta);
            if (matched <= 0L) continue;
            removed.put(flow.itemId, matched);
            // Coins can be part of the measured death wipe transfer, but they are
            // never reclaim whitelist items: a later coin loss may be the observed fee.
            if (flow.itemId != ItemID.COINS) {
                deathItems.merge(flow.itemId, matched, SafeMath::safeAdd);
                if (flow.itemName != null && !flow.itemName.trim().isEmpty())
                    names.put(flow.itemId, flow.itemName.trim());
            }
        }
        endWipeIfSpent();
        return removed.isEmpty() ? emptyMap() : unmodifiableMap(removed);
    }

    /**
     * Match a positive return against the still-outstanding measured death quantity. Returns zero
     * when the item was not part of the actual settled death wipe.
     */
    long matchReturnedItem(int itemId, long quantity) {
        long matched = take(deathItems, itemId, quantity);
        if (matched > 0L && deathItems.isEmpty()) {
            // All measured grave contents have returned. Remaining stacks from the
            // death-time snapshot were kept and can no longer be a delayed wipe.
            cancelDeathWipe();
        }
        return matched;
    }

    long outstandingItemCount() {
        long count = 0L;
        for (long quantity : deathItems.values()) count = SafeMath.safeAdd(count, quantity);
        return count;
    }

    int ageTicks() {
        return ageTicks;
    }

    /**
     * Player interacted with a retrieval service. A fresh Grave / Gravestone click can always
     * re-arm while this local death remains active.
     *
     * @return true only when a local PvM death is awaiting reclaim
     */
    boolean noteReclaimIntent(BossRetrievalCatalogue.Service service, int ticks) {
        if (service == null || !awaitingReclaim && deathItems.isEmpty()) return false;
        reclaimService = service;
        reclaimTicks = Math.max(MIN_RECLAIM_ARM_TICKS, ticks);
        return true;
    }

    boolean isReclaimArmed() {
        return reclaimTicks > 0 && reclaimService != null;
    }

    /** One inventory/game tick; defers gravestone expiry while a snapshot is unsettled. */
    String tick(boolean deferGravestoneExpiry) {
        if (awaitingReclaim && ageTicks < Integer.MAX_VALUE) ageTicks++;
        if (reclaimTicks > 0 && --reclaimTicks == 0) reclaimService = null;
        if (deathWipeTicks > 0 && --deathWipeTicks == 0) cancelDeathWipe();
        if (deferGravestoneExpiry || !awaitingReclaim || ageTicks < AWAIT_TICKS) return null;
        String expired = deathItems.isEmpty() ? null : expiredItems();
        // Expired grave items wait at Death's Office for good: still yours, so they stay
        // outstanding
        // and match only while a retrieval is armed (review 2026-10-09).
        awaitingReclaim = false;
        cancelDeathWipe();
        return expired;
    }

    /** The expiry audit explanation: the outstanding items, sorted by name. */
    String expiredItems() {
        var labels = new ArrayList<String>();
        deathItems.forEach((itemId, quantity) -> {
                    String name = names.getOrDefault(itemId, "item #" + itemId);
                    labels.add(quantity == 1L ? name : name + " ×" + String.format(Locale.ROOT, "%,d", quantity));
                });
        labels.sort(String.CASE_INSENSITIVE_ORDER);
        return msg("gravestone-timer") + outstandingItemCount() + msg("item-s") + String.join(", ", labels)
                + msg("item-loss");
    }

    void reset() {
        awaitingReclaim = false;
        deathWipeTicks = 0;
        ageTicks = 0;
        reclaimTicks = 0;
        reclaimService = null;
        carriedAtDeath = null;
        deathItems.clear();
        names.clear();
    }

    /**
     * Persistable physical continuity for restart, or null when no death awaits reclaim. Item
     * identity and quantity only; the live reclaim interaction and presentation labels are
     * deliberately not persisted.
     */
    PendingDeath snapshot() {
        List<DeathItem> outstanding = items(deathItems);
        List<DeathItem> held = items(carriedAtDeath);
        return outstanding.isEmpty() && held.isEmpty() ? null
                : new PendingDeath(outstanding, held, deathWipeTicks, ageTicks);
    }

    static List<DeathItem> items(Map<Integer, Long> quantities) {
        var items = new ArrayList<DeathItem>();
        positiveEntries(quantities).forEach((itemId, quantity) -> items.add(new DeathItem(itemId, quantity)));
        return items;
    }

    /**
     * Restore persisted physical continuity after a restart. The reclaimed quantities are matched
     * exactly as before; the delayed-wipe window resumes its remaining bounded ticks and the
     * gravestone timer keeps its age. A live reclaim interaction is never restored.
     */
    void restore(PendingDeath pending) {
        reset();
        if (pending == null || pending.isEmpty()) return;
        for (DeathItem item : pending.getOutstandingItems()) {
            if (item.getQuantity() > 0L)
                deathItems.merge(item.itemId, item.getQuantity(), SafeMath::safeAdd);
        }
        var held = new HashMap<Integer, Long>();
        for (DeathItem item : pending.getHeldAtDeath()) {
            held.merge(item.itemId, item.getQuantity(), SafeMath::safeAdd);
        }
        deathWipeTicks = Math.min(DEATH_WIPE_WINDOW_TICKS, pending.getWipeTicksRemaining());
        carriedAtDeath = deathWipeTicks > 0 && !held.isEmpty() ? held : null;
        deathWipeTicks = carriedAtDeath == null ? 0 : deathWipeTicks;
        ageTicks = pending.getGravestoneAgeTicks();
        awaitingReclaim = carriedAtDeath != null || !deathItems.isEmpty() && ageTicks < AWAIT_TICKS;
    }

    /** Adopt the physical continuity of a staged engine during an owner install. */
    void adoptFrom(DeathReclaimLifecycle other) {
        reset();
        if (other == null) return;
        awaitingReclaim = other.awaitingReclaim;
        deathWipeTicks = other.deathWipeTicks;
        ageTicks = other.ageTicks;
        reclaimTicks = other.reclaimTicks;
        reclaimService = other.reclaimService;
        carriedAtDeath = other.carriedAtDeath == null ? null : new HashMap<>(other.carriedAtDeath);
        deathItems.putAll(other.deathItems);
        names.putAll(other.names);
    }

    void endWipeIfSpent() {
        if (carriedAtDeath != null && carriedAtDeath.isEmpty()) cancelDeathWipe();
        completeIfEmpty();
    }

    void completeIfEmpty() {
        if (awaitingReclaim && carriedAtDeath == null && deathItems.isEmpty()) reset();
    }
}

/**
 * Read-only state for the local PvM death-reclaim indicator. The item count is the sum of measured
 * outstanding stack quantities, and age is measured in active engine game ticks.
 */
class DeathReclaimStatus {
    boolean awaiting;
    boolean armed;
    long outstandingItemCount;
    long ageTicks;

    DeathReclaimStatus(boolean awaiting, boolean armed, long outstandingItemCount, long ageTicks) {
        this.awaiting = awaiting;
        this.armed = awaiting && armed;
        this.outstandingItemCount = nonNeg(outstandingItemCount);
        this.ageTicks = nonNeg(ageTicks);
    }
}

/**
 * Item Retrieval Services and their published flat fees. Source: OSRS Wiki "Item Retrieval Service"
 * (checked 2026-10-11). The fee here is an <em>expectation used for labelling</em>; the booked cost
 * is always the coins actually observed leaving the inventory in the reclaim window. Fees paid from
 * the bank or Death's Coffer are not observable and are never invented.
 */
class BossRetrievalCatalogue {
    /** Marker for services whose fee depends on the run (ToA scales with raid level). */
    static final long VARIABLE_FEE = -1L;

    /** Marker for tiered / percentage fees (gravestone, Death's Office) — see DeathReclaimFees. */
    static final long TIERED_FEE = -2L;

    @AllArgsConstructor
    static class Service {
        String key;
        String activity;
        String interactable;
        long expectedFee;
        String note;

        /**
         * Target name shared with unrelated scenery (a bare "Chest", a gravestone): only evidence
         * when a local PvM death is still awaiting reclaim.
         */
        boolean ambiguousTarget;

        /** Ledger explanation for an observed coin payment at this service. */
        String why(long observedCoins) {
            var why = new StringBuilder(msg("item-retrieval")).append(activity).append(" (").append(interactable)
                            .append(")");
            if (expectedFee > 0L) {
                why.append(", published ").append(String.format(ROOT, "%,d", expectedFee));
                if (observedCoins > 0L && observedCoins != expectedFee) {
                    why.append(", observed ").append(String.format(ROOT, "%,d", observedCoins));
                }
            } else if (expectedFee == VARIABLE_FEE) {
                why.append(", fee varies");
            }
            if (!note.isEmpty()) why.append(". ").append(note);
            return why.toString();
        }
    }

    static final Map<String, Service> BY_INTERACTABLE = new LinkedHashMap<>();

    static {
        for (String[] row : GameData.rows("retrieval_services")) {
            BY_INTERACTABLE.put(row[2].toLowerCase(ROOT), new Service(row[0], row[1], row[2], Long.parseLong(row[3]),
                            row[4], Boolean.parseBoolean(row[5])));
        }
    }

    /**
     * Service for a menu target (NPC or object name) when the option is a retrieval verb (see
     * {@link #isRetrievalOption}). Ambiguous targets ("Chest", "Gravestone", "Death") are returned
     * too; the engine only acts on them while a local PvM death awaits reclaim.
     */

    /** Menu verbs that open or complete a reclaim. */
    /** Existing reclaim vocabulary and grave prefix compiled once from d18. */
    static final java.util.regex.Pattern RECLAIM = GameData.pattern("reclaim");

    static final java.util.regex.Pattern GRAVE = GameData.pattern("grave");

    static Service serviceFor(String option, String target) {
        if (target == null || option == null || !RECLAIM.matcher(Phrases.lower(option)).matches()) {
            return null;
        }
        String lower = Phrases.lower(target);
        if (lower.isEmpty()) return null;
        Service exact = BY_INTERACTABLE.get(lower);
        if (exact != null) return exact;
        for (Map.Entry<String, Service> entry : BY_INTERACTABLE.entrySet()) {
            if (!entry.getValue().ambiguousTarget && lower.contains(entry.getKey()))
                return entry.getValue();
        }
        if (GRAVE.matcher(lower).matches()) return BY_INTERACTABLE.get("gravestone");
        return null;
    }
}

/**
 * Derives the observed {@link ActionKind} for a settled inventory change from positive evidence
 * only: a matched menu verb plus the settled flow shape. Never upgrades weak evidence into a
 * confident verb — a dose leftover without a matched Drink click is {@link ActionKind#SUPPLIES},
 * not {@link ActionKind#DRINK}.
 *
 * <p>Presentation evidence only. Accounting type, valuation and counted state are decided elsewhere
 * and never read this class.
 */
class ActionReader {
    static final Set<String> HERBLORE_INGREDIENTS = new HashSet<>(GameData.names("empty_vessels"));
    static final Pattern HERBLORE_INGREDIENT_SUFFIX = GameData.pattern("herblore-ingredient");
    static final Pattern DOSE_SUFFIX = GameData.pattern("dose-suffix");

    /** Ammunition names; tips, heads and shafts are fletching supplies, not ammunition. */
    static final Pattern AMMUNITION = GameData.pattern("ammunition");

    static final Pattern FLETCHING_PART = GameData.pattern("fletching-part");

    /** Menu verb prefixes (a leading "~" means "contains") and the action each evidences. */
    static final Object[] VERBS = GameData.rows("action_rules").stream().filter(a -> !a[2].isEmpty())
                    .flatMap(a -> java.util.stream.Stream.of(a[0], ActionKind.valueOf(a[2]))).toArray();

    /**
     * Final stamp for a booked change.
     *
     * @param matchedIntent the menu verb whose intent matched these flows, or null when the intent
     *     was absent, stale or matched nothing
     * @param flows settled flows of the transaction
     * @param type booked accounting type (unchanged by this call)
     * @param activityName booked activity name, used only to recognise Herblore production
     */
    static ActionKind resolve(ActionKind matchedIntent, List<Flow> flows, TransactionType type, String activityName) {
        if (Phrases.empty(flows)) return null;
        // A decant shape can also appear in a bank transfer or trade. Only the
        // otherwise-unclassified mixed inventory row is evidence of a player action.
        if (type == UNCERTAIN && isDecant(flows)) return DECANT;
        String activity = Phrases.lower(activityName);
        if (type == PROCESSING && activity.equals("herblore") && all(flows, -1, ActionReader::isIngredient)
                && all(flows, 1, name -> trailingDose(name) > 0)) {
            return MIX;
        }
        if (type == PROCESSING && activity.equals("cooking") && isCooking(flows)) {
            // Use the actual outcome: every output burnt → Burnt, otherwise Cooked.
            return all(flows, 1, name -> name.startsWith("burnt")) ? BURN : COOK;
        }
        // Rune/ammo spends are only recognised on cost-shaped rows: a bank deposit or
        // death loss of runes is a transfer/death, not casting.
        boolean spendShaped = type == CONSUMPTION || type == PK_SUPPLY_COST;
        boolean hasLoss = hasLoss(flows);
        boolean hasGain = !gains(flows, ANY).isEmpty();
        boolean lossOnly = hasLoss && !hasGain;
        if (spendShaped && lossOnly && all(flows, 0, ActionReader::isRune)) return ActionKind.CAST;
        if (spendShaped && lossOnly && all(flows, 0, ActionReader::isAmmo)) return FIRE;
        boolean doseStep = isDoseStep(flows);
        // Every loss must be one potion's doses (Drink) or one item (Eat/Bury/...): a coalesced
        // shark or bones under the same click is not "Drank", so it falls back to Supplies.
        boolean onePotion = oneLossFamily(flows, name -> trailingDose(name) > 0 ? doseBaseName(name) : null);
        boolean oneItem = oneLossFamily(flows, name -> name.isEmpty() ? null : name)
                        && all(flows, -1, name -> trailingDose(name) <= 0);
        if (matchedIntent != null && hasLoss) {
            switch (matchedIntent) {
                case DRINK:
                    return onePotion ? ActionKind.DRINK : ActionKind.SUPPLIES;
                case EAT:
                case BURY:
                case SCATTER:
                case OFFER:
                    return oneItem ? matchedIntent : ActionKind.SUPPLIES;
                case CAST:
                    // A Cast proves its spell by what it spent: runes, or a broken tablet (the
                    // spell in item form). An alch's coins or Bones to Peaches' fruit may come
                    // back with them (owner live 2026-10-16); a bank or trade move never casts.
                    return type != TransactionType.TRANSFER && type != TRADE && !all(flows, -1,
                                            name -> !isRune(name) && Spells.named(name) == null) ? ActionKind.CAST
                            : doseStep ? ActionKind.SUPPLIES : null;
                case SUPPLIES:
                    // This explicit intent currently comes from Light/Tend-to and the
                    // Tinderbox→fuel pair. Require the matching loss to be actual fuel.
                    return lossOnly && all(flows, 0, name -> name.matches(msg("logs-kindling"))) ? ActionKind.SUPPLIES
                            : null;
                default:
                    return matchedIntent;
            }
        }
        // Consumption is known from the leftover shape; the verb is not.
        return type == CONSUMPTION && doseStep ? ActionKind.SUPPLIES : null;
    }

    /**
     * Doses of one potion redistributed with no dose lost: e.g. −(3) −(1) +(4), or −(4) +(2) +(2).
     * Requires every flow to be a dosed form of the same base name.
     */
    static boolean isDecant(List<Flow> flows) {
        if (flows == null || flows.size() < 2) return false;
        String base = null;
        long doseBalance = 0L;
        boolean anyLoss = false;
        boolean anyGain = false;
        for (Flow flow : flows) {
            if (flow.quantityDelta == 0L) continue;
            String name = Phrases.lower(flow.itemName);
            int dose = trailingDose(name);
            // Combining doses frees a vessel; an emptied vial/jug gain is part of the shape.
            if (dose <= 0 && flow.quantityDelta > 0L && isVessel(name)) continue;
            String stem = doseBaseName(name);
            if (dose <= 0 || stem.isEmpty() || base != null && !base.equals(stem)) return false;
            base = stem;
            doseBalance += (long) dose * flow.quantityDelta;
            anyLoss |= flow.quantityDelta < 0L;
            anyGain |= flow.quantityDelta > 0L;
        }
        return anyLoss && anyGain && doseBalance == 0L;
    }

    /** A dose/food step confirms a consume even when its menu intent has expired. */
    static boolean isDoseStep(List<Flow> flows) {
        return !partialUses(flows).isEmpty();
    }

    /** Exact matched loss/remainder pairs for a recognized dose or partial-food consume. */
    static List<PartialUse> partialUses(List<Flow> flows) {
        if (flows == null || flows.size() < 2) return Collections.emptyList();
        var pairs = new ArrayList<PartialUse>();
        for (int lossIndex = 0; lossIndex < flows.size(); lossIndex++) {
            // A loss pairs only with the one remainder it could have become, and that remainder
            // only with this loss.
            List<Integer> gains = stepPartners(flows, flows.get(lossIndex), true);
            if (gains.size() == 1 && stepPartners(flows, flows.get(gains.get(0)), false).size() == 1) {
                pairs.add(new PartialUse(lossIndex, gains.get(0)));
            }
        }
        return Collections.unmodifiableList(pairs);
    }

    /**
     * Indexes of the flows a loss could step to ({@code toGains}), or the losses a gain could come
     * from.
     */
    static List<Integer> stepPartners(List<Flow> flows, Flow flow, boolean toGains) {
        var partners = new ArrayList<Integer>();
        Flow loss = toGains ? flow : null;
        Flow gain = toGains ? null : flow;
        if (flow == null || (toGains ? flow.quantityDelta >= 0L || flow.quantityDelta == Long.MIN_VALUE
                        : flow.quantityDelta <= 0L)) {
            return partners;
        }
        for (int index = 0; index < flows.size(); index++) {
            Flow other = flows.get(index);
            Flow lost = toGains ? loss : other;
            Flow gained = toGains ? other : gain;
            if (lost != null && gained != null && lost.quantityDelta < 0L && lost.quantityDelta != Long.MIN_VALUE
                    && gained.quantityDelta > 0L && -lost.quantityDelta == gained.quantityDelta
                    && isConsumeStep(lost.itemName, gained.itemName)) {
                partners.add(index);
            }
        }
        return partners;
    }

    @AllArgsConstructor
    static class PartialUse {
        int lossIndex;
        int remainderIndex;
    }

    static boolean addDoseLosses(Map<Integer, Long> target, List<Flow> flows) {
        boolean matched = false;
        for (Flow loss : flows) {
            if (!stepPartners(flows, loss, true).isEmpty()) {
                target.put(loss.itemId, -loss.quantityDelta);
                matched = true;
            }
        }
        return matched;
    }

    /**
     * Potion base name without its dose suffix, for receipts: "Prayer potion(4)" → "Prayer potion".
     */
    static String doseBaseName(String itemName) {
        return itemName == null ? "" : DOSE_SUFFIX.matcher(itemName.trim()).replaceFirst("").trim();
    }

    /** Dose count parsed from a trailing "(N)" suffix, or −1 when absent. */
    static int trailingDose(String itemName) {
        Matcher matcher = DOSE_SUFFIX.matcher(itemName == null ? "" : itemName.trim());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    static boolean isAmmo(String itemName) {
        String lower = Phrases.lower(itemName);
        return AMMUNITION.matcher(lower).matches() && !FLETCHING_PART.matcher(lower).matches();
    }

    /**
     * True when every loss has the same non-null family key (a matched click cannot name mixed
     * costs); false when there is no loss.
     */
    static boolean oneLossFamily(List<Flow> flows, Function<String, String> family) {
        String first = null;
        for (Flow flow : flows) {
            if (flow.quantityDelta >= 0L) continue;
            String key = family.apply(Phrases.lower(flow.itemName));
            if (key == null || first != null && !first.equals(key)) return false;
            first = key;
        }
        return first != null;
    }

    /** A cooking row must pair raw food inputs with only their cooked/burnt products. */
    static boolean isCooking(List<Flow> flows) {
        var rawBases = new ArrayList<String>();
        for (Flow flow : flows) {
            if (flow.quantityDelta < 0L) {
                String name = Phrases.lower(flow.itemName);
                rawBases.add(name.startsWith("raw ") && name.length() > 4 ? name.substring(4).trim()
                                : name.startsWith("uncooked ") && name.length() > 9 ? name.substring(9).trim() : null);
            }
        }
        return !rawBases.isEmpty() && !rawBases.contains(null) && all(flows, 1, output -> rawBases.stream().anyMatch(
                                                raw -> output.equals(raw) || output.equals("cooked " + raw)
                                                                || output.equals("burnt " + raw)));
    }

    /** True when every gained item is a burnt product ("Burnt shark", "Burnt pie"). */
    /**
     * True when at least one flow on the chosen side (1 gains, -1 losses, 0 both) exists and every
     * such flow's lower-case name satisfies {@code predicate}.
     */
    static boolean all(List<Flow> flows, int side, Predicate<String> predicate) {
        boolean any = false;
        for (Flow flow : flows) {
            long delta = flow.quantityDelta;
            if (delta == 0L || side != 0 && Long.signum(delta) != side) continue;
            if (!predicate.test(Phrases.lower(flow.itemName))) return false;
            any = true;
        }
        return any;
    }

    /** Ordered intent and item-name rules, compiled beside the old implementation for parity. */
    static final Map<String, Phrases.Clause[][]> RULES = GameData.rules("action_rules");

    static boolean rule(String group, String text) {
        return Phrases.has(text, RULES.get(group));
    }

    /** First matching verb wins, before the explicit fuel fallback. */
    static ActionKind verbOf(String raw) {
        String name = Phrases.lower(raw);
        for (int index = 0; index < VERBS.length; index += 2) {
            if (rule((String) VERBS[index], name)) return (ActionKind) VERBS[index + 1];
        }
        return rule("supplies", name) ? ActionKind.SUPPLIES : null;
    }

    /** Empty vessels use the already-normalized name, exactly as before. */
    static boolean isVessel(String name) {
        return rule("vessel", name);
    }

    /** Rune and unopened-claim names use the shared trimmed normalization. */
    static boolean isRune(String name) {
        return rule("rune", Phrases.lower(name));
    }

    static boolean isClaim(String name) {
        return rule("claim", Phrases.lower(name));
    }

    /** Ingredient names retain their existing non-null precondition and named ingredient set. */
    static boolean isIngredient(String name) {
        return !name.isEmpty() && rule("ingredient", name) && (HERBLORE_INGREDIENTS.contains(name)
                        || HERBLORE_INGREDIENT_SUFFIX.matcher(name).matches());
    }

    /** Dose arithmetic is unchanged; only food/vessel vocabulary is table-driven. */
    static boolean isConsumeStep(String lostName, String gainedName) {
        String lost = Phrases.lower(lostName);
        String gained = Phrases.lower(gainedName);
        if (lost.isEmpty() || gained.isEmpty()) return false;
        if (isVessel(gained)) return true;
        int lostDose = trailingDose(lost);
        int gainedDose = trailingDose(gained);
        if (lostDose > 0) {
            String lostBase = doseBaseName(lost);
            if (!lostBase.isEmpty() && lostBase.equals(doseBaseName(gained))) {
                // A suffix-less gain of the same base is the uncharged item: the last glory
                // charge leaves the plain amulet, so the pair is still one dose step (owner
                // 1.3.4). Any other suffix-less gain keeps failing closed.
                return lostDose == (gainedDose < 0 ? 0 : gainedDose) + 1;
            }
        }
        return rule("food", lost) && rule("partial", gained);
    }
}

/**
 * Menu / chat vocabulary that turns a click into an accounting intent: explicit spend actions (eat,
 * drink, bury, offer, light), transform/production options that arm PRODUCTION context, gather
 * verbs that only name the activity, and the chat lines that confirm a spend after the click.
 * Anything not listed here stays ambiguous and settles through Review, never a guess.
 */
class ActionSignals {
    static final Map<String, String> TRANSFORM = GameData.byName("transform_skills");
    static final Map<String, String> GATHER = GameData.byName("gather_skills");

    /** Compiled menu/chat predicates; d7/d10 already contain transform and gather data. */
    static final Map<String, Phrases.Clause[][]> RULES = GameData.rules("menu_rules");

    /** Apply one compiled predicate group. */
    static boolean rule(String group, String text) {
        return Phrases.has(text, RULES.get(group));
    }

    /** Widget actions eligible for selected spell evidence. */
    static boolean isWidgetAction(MenuAction action) {
        return rule("widget", action == null ? null : action.name());
    }

    /** Raw consume verbs plus normalized explicit spends. */
    static boolean isSpend(String option) {
        return rule("spend.raw", option) || isSpendName(option);
    }

    /** Normalized light/offer/pray and burial/scatter verbs. */
    static boolean isSpendName(String option) {
        return rule("spend.norm", Phrases.lower(option));
    }

    /** Use tinderbox with fuel; target normalization preserves whitespace. */
    static boolean lightsFire(String option, String target) {
        return rule("use", Phrases.lower(option)) && rule("fuel", Phrases.lower(target, false))
                && rule("fire", Phrases.lower(target, false));
    }

    /** Use bones/ashes on an altar. */
    static boolean offersBones(String option, String target) {
        return rule("use", Phrases.lower(option)) && rule("altar", Phrases.lower(target, false))
                && rule("bones", Phrases.lower(target, false));
    }

    /** Positive Sinister Offering cast evidence. */
    static boolean isOffering(String option, String target) {
        return rule("cast", Phrases.lower(option)) && rule("offering", Phrases.lower(target, false));
    }

    /** Chaos Altar saved a bone; this cannot confirm spend. */
    static boolean isSpared(String message) {
        return rule("spared", Phrases.lower(message, false));
    }

    /** Inventory-spend chat, excluding a saved bone. */
    static boolean isChatSpend(String message) {
        return !isSpared(message) && rule("spend.chat", Phrases.lower(message, false));
    }

    /**
     * Transform/production options (station or inventory) mapped to the skill whose XP confirms
     * them.
     */
    static String transformSkill(String option) {
        return TRANSFORM.getOrDefault(Phrases.lower(option), "");
    }

    /** Activity hint for object gather verbs; "General" when the verb is not a gather. */
    static String gatherActivity(String option) {
        return GATHER.getOrDefault(Phrases.orEmpty(option), "General");
    }

    /** Rake and plant still name the activity, but do not arm gather ownership evidence. */
    static boolean namesActivityOnly(String option) {
        return option != null && !"General".equals(gatherActivity(option)) && !rule("gather.exclude", option);
    }
}

/**
 * Schema-106 pooled known-basis coverage memory.
 *
 * <p>Tracked basis is a double-count-prevention memory, never a second financial ledger: canonical
 * counted transactions stay the only financial authority and this ledger never books, reprices or
 * exports GP. It records only <em>known coverage</em>: quantity whose prior GP Manager accounting
 * is proven, with the exact GP value GP Manager counted (which may legitimately be zero). Absence
 * of coverage means UNKNOWN basis, never zero basis; unknown quantity is derived per realization
 * and never persisted as a global unknown-owned ledger.
 *
 * <p>Allocation is tracked-first pooled weighted average (never FIFO/LIFO). Acquisitions add known
 * coverage. Proven SELL placements reserve their share; cancellations release the unused
 * reservation exactly; settlements consume the realized share; permanent ownership sinks deplete
 * with the same rule. A per-item realization fence blocks retroactive acquisition corrections once
 * known coverage has been realized or depleted, so canonical Net and tracked basis can never
 * disagree about what was previously counted.
 */
class TrackedBasisLedger {
    /** Exact known-coverage share a proven SELL placement captured from the available pool. */
    @AllArgsConstructor
    static class Reservation {
        long trackedQty;
        long basisGp;
    }

    Map<Integer, BasisPool> pools = new LinkedHashMap<>();
    long basisEpochMillis;
    java.util.function.IntFunction<Long> openReservationLookup = id -> 0L;

    // ---------------------------------------------------------------------------------------
    // Acquisition / removal / change
    // ---------------------------------------------------------------------------------------
    /**
     * Book a newly added canonical transaction: add its exact known coverage, and deplete known
     * coverage for proven permanent non-custody ownership sinks. Custody-owned settlements already
     * consumed their reservation, so their item legs never deplete again.
     */
    synchronized void onAdded(Transaction transaction, boolean custodyOwned) {
        if (transaction == null || transaction.timestampEpochMillis < basisEpochMillis) {
            // Pre-epoch stock is UNKNOWN coverage by migration law; nothing is inferred here.
            return;
        }
        if (basisEpochMillis <= 0L && transaction.timestampEpochMillis > 0L)
            basisEpochMillis = transaction.timestampEpochMillis;
        applyContribution(contribution(transaction), transaction.timestampEpochMillis);
        if (!custodyOwned) depleteSinks(transaction);
    }

    /** Validate restoring a previously removed transaction; false blocks the restore. */
    synchronized boolean canAdd(Transaction transaction) {
        if (transaction == null) return false;
        if (transaction.timestampEpochMillis >= basisEpochMillis && !sinkQuantities(transaction).isEmpty()) {
            return false;
        }
        return canApplyAll(contribution(transaction), 1L, transaction.timestampEpochMillis);
    }

    /** Validate removing a retained transaction (undo); false blocks the canonical removal. */
    synchronized boolean canRemove(Transaction transaction) {
        if (transaction == null || transaction.timestampEpochMillis < basisEpochMillis) return true;
        if (!sinkQuantities(transaction).isEmpty()) {
            return false;
        }
        return canApplyAll(contribution(transaction), -1L, transaction.timestampEpochMillis);
    }

    boolean canApplyAll(Map<Integer, long[]> deltas, long sign, long timestamp) {
        for (Entry<Integer, long[]> entry : deltas.entrySet()) {
            if (!canApply(entry.getKey(), sign * entry.getValue()[0], sign * entry.getValue()[1], timestamp)) {
                return false;
            }
        }
        return true;
    }

    synchronized void onUsedUp(Transaction transaction) {
        depleteSinks(transaction);
    }

    /** Reverse a previously removed transaction's exact known coverage. */
    synchronized void onRemoved(Transaction transaction) {
        if (transaction == null || transaction.timestampEpochMillis < basisEpochMillis) return;
        contribution(transaction).forEach((itemId, delta) -> applyDelta(itemId, -delta[0], -delta[1]));
    }

    /**
     * Validate a pending correction/split/undo before any canonical mutation. False means the
     * mutation must be rejected as-is: the item's known coverage is reserved or already realized.
     */
    synchronized boolean canChange(Transaction transaction, Correction previousCorrection, List<Flow> previousFlows,
            Correction nextCorrection, List<Flow> nextFlows) {
        if (transaction == null) return false;
        Map<Integer, long[]> before = contribution(transaction, previousCorrection, previousFlows);
        // A null next-flow set means the caller can only promise the positive contribution
        // shrinks; validating against the empty worst case is the strictest safe check.
        Map<Integer, long[]> after = contribution(transaction, nextCorrection, nextFlows);
        Map<Integer, Long> beforeSinks = sinkQuantities(transaction, previousCorrection, previousFlows);
        Map<Integer, Long> afterSinks = sinkQuantities(transaction, nextCorrection, nextFlows);
        if (transaction.timestampEpochMillis >= basisEpochMillis && !beforeSinks.equals(afterSinks)
                && !canIntroduceSink(beforeSinks, afterSinks, transaction.timestampEpochMillis)) {
            return false;
        }
        return canApplyAll(deltas(before, after), 1L, transaction.timestampEpochMillis);
    }

    /** Apply the exact effective delta after a permitted correction/split/undo. */
    synchronized void onChanged(Transaction transaction, Correction previousCorrection, List<Flow> previousFlows) {
        if (transaction == null) return;
        Map<Integer, Long> beforeSinks = sinkQuantities(transaction, previousCorrection, previousFlows);
        Map<Integer, Long> afterSinks = sinkQuantities(transaction);
        applyContribution(deltas(contribution(transaction, previousCorrection, previousFlows),
                        contribution(transaction)), transaction.timestampEpochMillis);
        if (!beforeSinks.equals(afterSinks) && beforeSinks.isEmpty()) depleteSinks(transaction);
    }

    // ---------------------------------------------------------------------------------------
    // Reservation / realization / depletion
    // ---------------------------------------------------------------------------------------
    /**
     * Reserve the tracked-first known-coverage share for a quantity entering proven SELL custody.
     */
    synchronized Reservation reserve(int itemId, long quantity, long now) {
        long requested = nonNeg(quantity);
        BasisPool pool = pools.get(itemId);
        long availableQty = pool == null ? 0L : pool.availableQty;
        long availableBasis = pool == null ? 0L : pool.availableBasisGp;
        long consumeQty = min(requested, availableQty);
        long basis = BasisMath.shareOf(availableQty, availableBasis, consumeQty);
        if (consumeQty <= 0L && basis <= 0L) return new Reservation(0L, 0L);
        pool = pool(itemId);
        pool.availableQty = nonNeg(availableQty - consumeQty);
        pool.availableBasisGp = nonNeg(availableBasis - basis);
        return new Reservation(consumeQty, basis);
    }

    /** A sale realized units of this item: fence retroactive corrections before {@code now}. */
    synchronized void fenceRealized(int itemId, long now) {
        BasisPool pool = pool(itemId);
        pool.realizationFenceEpochMillis = max(pool.realizationFenceEpochMillis, now);
    }

    /** Return an unconsumed reservation share to the available pool (cancel/return). */
    synchronized void release(int itemId, long quantity, long basisGp) {
        long releaseQty = nonNeg(quantity);
        long releaseBasis = nonNeg(basisGp);
        if (releaseQty <= 0L && releaseBasis <= 0L) return;
        BasisPool pool = pool(itemId);
        pool.availableQty = nonNeg(pool.availableQty + releaseQty);
        pool.availableBasisGp = nonNeg(pool.availableBasisGp + releaseBasis);
    }

    /** Deplete known coverage for a proven permanent ownership sink; returns basis consumed. */
    synchronized long deplete(int itemId, long quantity, long now) {
        long requested = nonNeg(quantity);
        BasisPool pool = pools.get(itemId);
        if (requested <= 0L) return 0L;
        if (pool == null) {
            // Even a sink with no known coverage leaves a realization fence: units left ownership,
            // so later retroactive coverage for them can never be re-added.
            pool(itemId).realizationFenceEpochMillis = nonNeg(now);
            return 0L;
        }
        long consumeQty = min(requested, pool.availableQty);
        long basis = BasisMath.shareOf(pool.availableQty, pool.availableBasisGp, consumeQty);
        pool.availableQty = nonNeg(pool.availableQty - consumeQty);
        pool.availableBasisGp = nonNeg(pool.availableBasisGp - basis);
        pool.realizationFenceEpochMillis = max(pool.realizationFenceEpochMillis, now);
        return basis;
    }

    /** Currency is money, never tracked owned-item value. */
    static boolean isCurrency(int itemId) {
        return itemId == ItemID.COINS || itemId == ItemID.PLATINUM;
    }

    // ---------------------------------------------------------------------------------------
    // Persistence / lifecycle
    // ---------------------------------------------------------------------------------------
    synchronized void writeTo(SavedState state) {
        if (state == null) return;
        var out = new TrackedBasisState();
        // Epoch 0 only happens for an engine that never restored or tracked anything; a positive
        // sentinel keeps a saved pool loadable as "tracked since the beginning".
        out.basisEpochMillis = nonNeg(basisEpochMillis > 0L ? basisEpochMillis : 1L);
        var list = new ArrayList<BasisPool>();
        for (BasisPool pool : pools.values()) {
            if (pool.availableQty <= 0L && pool.availableBasisGp <= 0L && pool.realizationFenceEpochMillis <= 0L
                    && pool.latestAcquisitionEpochMillis <= 0L) {
                continue;
            }
            var copy = new BasisPool(pool.itemId);
            copy.availableQty = pool.availableQty;
            copy.availableBasisGp = pool.availableBasisGp;
            copy.realizationFenceEpochMillis = pool.realizationFenceEpochMillis;
            copy.latestAcquisitionEpochMillis = pool.latestAcquisitionEpochMillis;
            list.add(copy);
        }
        list.removeIf(value -> value == null || value.itemId <= 0);
        out.pools = new ArrayList<>(list);
        state.setTrackedBasis(out);
    }

    /**
     * Restore persisted continuity. A pre-106 (or absent) state starts with no known coverage at
     * the migration boundary: existing holdings are UNKNOWN coverage, no bank value seeds basis and
     * no history is reconstructed. Reserved shares live on the durable custody records.
     */
    synchronized void restore(SavedState state, long now) {
        pools.clear();
        TrackedBasisState saved = state == null ? null : state.getTrackedBasis();
        if (saved != null && saved.getBasisEpochMillis() > 0L) {
            basisEpochMillis = saved.getBasisEpochMillis();
            for (BasisPool pool : saved.pools()) {
                if (pool == null || pool.itemId <= 0) continue;
                // A save is clamped once here, so the pool's fields are read directly.
                pool.availableQty = nonNeg(pool.availableQty);
                pool.availableBasisGp = nonNeg(pool.availableBasisGp);
                pool.realizationFenceEpochMillis = nonNeg(pool.realizationFenceEpochMillis);
                pool.latestAcquisitionEpochMillis = nonNeg(pool.latestAcquisitionEpochMillis);
                pools.put(pool.itemId, pool);
            }
        } else {
            basisEpochMillis = now;
        }
    }

    /** Destructive reset: tracked basis is continuity state and clears with the rest. */
    synchronized void reset() {
        pools.clear();
        basisEpochMillis = 0L;
    }

    /** Adopt another ledger's durable state during a staged profile swap. */
    synchronized void adoptFrom(TrackedBasisLedger other) {
        reset();
        if (other != null) {
            basisEpochMillis = other.basisEpochMillis;
            other.pools.forEach((itemId, pool) -> {
                        if (pool != null) pools.put(itemId, pool);
                    });
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------
    BasisPool pool(int itemId) {
        return pools.computeIfAbsent(itemId, BasisPool::new);
    }

    void applyContribution(Map<Integer, long[]> contribution, long timestamp) {
        for (Entry<Integer, long[]> entry : contribution.entrySet()) {
            applyDelta(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            if (entry.getValue()[0] > 0L) {
                BasisPool pool = pool(entry.getKey());
                pool.latestAcquisitionEpochMillis = max(pool.latestAcquisitionEpochMillis, timestamp);
            }
        }
    }

    /** Deplete known coverage for each proven permanent non-custody ownership sink in the row. */
    void depleteSinks(Transaction transaction) {
        Map<Integer, Long> quantities = sinkQuantities(transaction);
        for (Entry<Integer, Long> entry : quantities.entrySet())
            deplete(entry.getKey(), entry.getValue(), transaction.timestampEpochMillis);
    }

    /** An owner may first count a Review removal while no later use has frozen its pool. */
    boolean canIntroduceSink(Map<Integer, Long> before, Map<Integer, Long> after, long transactionTimestamp) {
        if (!before.isEmpty() || after.isEmpty()) return false;
        for (Integer itemId : after.keySet()) {
            if (!canApply(itemId, 0L, 0L, transactionTimestamp)) return false;
            BasisPool pool = pools.get(itemId);
            if (pool != null && pool.availableQty > 0L && (pool.latestAcquisitionEpochMillis <= 0L
                            || pool.latestAcquisitionEpochMillis > transactionTimestamp)) {
                return false;
            }
        }
        return true;
    }

    /** Physical counted removals that would require exact reversal of pooled sink depletion. */
    Map<Integer, Long> sinkQuantities(TransactionType type, boolean counted, Correction correction, List<Flow> flows) {
        var quantities = new LinkedHashMap<Integer, Long>();
        if (!counted || type == TransactionType.TRANSFER || type == TransactionType.TRADE || correction == Correction.TRANSFER
                || flows == null) {
            // Custody-owned trade settlements consume their reservation separately. Their financial
            // Result corrections must remain available without touching physical basis.
            return quantities;
        }
        for (Flow flow : flows) {
            if (flow.quantityDelta >= 0L || isCurrency(flow.itemId)) continue;
            long quantity = flow.quantityDelta == Long.MIN_VALUE ? Long.MAX_VALUE : -flow.quantityDelta;
            int itemId = flow.itemId;
            quantities.put(itemId, safeAdd(quantities.getOrDefault(itemId, 0L), quantity));
        }
        return quantities;
    }

    void applyDelta(int itemId, long deltaQty, long deltaBasisGp) {
        if (deltaQty == 0L && deltaBasisGp == 0L) return;
        BasisPool pool = pool(itemId);
        pool.availableQty = nonNeg(pool.availableQty + deltaQty);
        pool.availableBasisGp = nonNeg(pool.availableBasisGp + deltaBasisGp);
    }

    boolean canApply(int itemId, long deltaQty, long deltaBasisGp, long transactionTimestamp) {
        if (transactionTimestamp < basisEpochMillis) return false;
        Long reservationTime = openReservationLookup.apply(itemId);
        if (reservationTime != null && reservationTime > 0L && reservationTime >= transactionTimestamp) return false;
        BasisPool pool = pools.get(itemId);
        long fence = pool == null ? 0L : pool.realizationFenceEpochMillis;
        if (fence > 0L && fence >= transactionTimestamp) return false;
        long availableQty = pool == null ? 0L : pool.availableQty;
        long availableBasis = pool == null ? 0L : pool.availableBasisGp;
        if (availableQty + deltaQty < 0L || availableBasis + deltaBasisGp < 0L) return false;
        return true;
    }

    Map<Integer, long[]> deltas(Map<Integer, long[]> before, Map<Integer, long[]> after) {
        var out = new LinkedHashMap<Integer, long[]>();
        for (Entry<Integer, long[]> entry : before.entrySet()) {
            long[] target = after.get(entry.getKey());
            long deltaQty = (target == null ? 0L : target[0]) - entry.getValue()[0];
            long deltaBasis = (target == null ? 0L : target[1]) - entry.getValue()[1];
            if (deltaQty != 0L || deltaBasis != 0L) {
                out.put(entry.getKey(), new long[] {deltaQty, deltaBasis});
            }
        }
        for (Entry<Integer, long[]> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey())) {
                out.put(entry.getKey(), new long[] {entry.getValue()[0], entry.getValue()[1]});
            }
        }
        return out;
    }

    Map<Integer, long[]> contribution(Transaction transaction) {
        return contribution(transaction, transaction.getCorrection(), transaction.getFlows());
    }

    /** The contribution the transaction would have under {@code correction} with {@code flows}. */
    Map<Integer, long[]> contribution(Transaction transaction, Correction correction, List<Flow> flows) {
        return flows == null ? new LinkedHashMap<>() : contribution(transaction.autoType(),
                        effectiveCounted(transaction.autoCounted(), correction), correction, flows);
    }

    /** The row's sinks as it is booked now. */
    Map<Integer, Long> sinkQuantities(Transaction transaction) {
        return sinkQuantities(transaction, transaction.getCorrection(), transaction.getFlows());
    }

    Map<Integer, Long> sinkQuantities(Transaction transaction, Correction correction, List<Flow> flows) {
        // A drop or a shot still on the ground is not a use yet: picking it up shrinks the row. It
        // depletes the pool when its pickup window closes (Engine#settleGround).
        if (transaction.isOnGround()) return new LinkedHashMap<>();
        return sinkQuantities(transaction.autoType(), effectiveCounted(transaction.autoCounted(), correction),
                correction, flows);
    }

    /** Effective counted state for an explicit correction (REVENUE/COST always count). */
    static boolean effectiveCounted(boolean automaticallyCounted, Correction correction) {
        Correction effective = correction == null ? AUTO : correction;
        return effective == REVENUE || effective == COST || (effective == AUTO && automaticallyCounted);
    }

    /**
     * Exact known coverage a counted transaction introduces, mirroring {@code Accounting.flow} for
     * positive quantities: AUTO counts the observed positive value, REVENUE counts the gross
     * magnitude, COST/IGNORE/TRANSFER count nothing. Quantity is tracked even when the booked value
     * is legitimately zero; unpriced/unknown/deferred sources create no coverage at all.
     */
    static Map<Integer, long[]> contribution(
            TransactionType type, boolean counted, Correction correction, List<Flow> flows) {
        var out = new LinkedHashMap<Integer, long[]>();
        Correction effective = correction == null ? AUTO : correction;
        if (type == TransactionType.TRANSFER || effective == Correction.TRANSFER || !counted || effective == IGNORE
                || flows == null) {
            return out;
        }
        for (Flow flow : flows) {
            if (flow.quantityDelta <= 0L || isCurrency(flow.itemId)) continue;
            PriceSource source = flow.getPriceSource();
            if (source == null || source == UNPRICED || source == UNKNOWN || source == PriceSource.DEFERRED_CLAIM) continue;
            long revenue = effective == REVENUE ? SafeMath.abs(flow.valueDelta)
                            : effective == COST ? 0L : nonNeg(flow.valueDelta);
            long[] total = out.computeIfAbsent(flow.itemId, itemId -> new long[2]);
            total[0] = safeAdd(total[0], flow.quantityDelta);
            total[1] = safeAdd(total[1], revenue);
        }
        return out;
    }
}

/**
 * Persisted schema-106 tracked-basis continuity: the minimum state a pooled weighted-average
 * double-count-prevention memory needs across restart.
 *
 * <p>{@code basisEpochMillis} is the migration boundary: acquisitions booked before it are
 * untracked by law (no backfill, no bank-price inference), and basis-affecting corrections of those
 * rows are fenced. Open SELL reservation shares live on their durable custody records; the ledger
 * rebinds the pooled reserved totals from them on restore.
 */
class TrackedBasisState {
    long basisEpochMillis;
    List<BasisPool> pools = new ArrayList<>();

    long getBasisEpochMillis() {
        return nonNeg(basisEpochMillis);
    }

    /** Never null: a pre-106 file upgrades with an empty pool. */
    List<BasisPool> pools() {
        if (pools == null) pools = new ArrayList<>();
        return pools;
    }
}

/**
 * One canonical item's pooled tracked-basis state (schema 106).
 *
 * <p>It is supporting ownership state, never a financial authority: canonical counted transactions
 * stay the only financial truth. {@code available*} is the free pooled quantity/value that a new
 * SELL reservation or permanent sink may consume (a reserved share is held by its open SELL custody
 * record, not here); {@code realizationFenceEpochMillis} records the latest time pooled basis was
 * realized or permanently depleted, which fences retroactive acquisition corrections for this item.
 * No FIFO lots, no portfolio history, no mark-to-market.
 */
class BasisPool {
    int itemId;
    long availableQty;
    long availableBasisGp;
    long realizationFenceEpochMillis;

    /** Latest proven known-coverage acquisition; fences retroactive counted removals. */
    long latestAcquisitionEpochMillis;

    BasisPool() {}

    BasisPool(int itemId) {
        this.itemId = itemId;
    }
}

/**
 * Integer-only pooled basis math shared by the known-basis ledger and the read model.
 *
 * <p>Every allocation is floor-based and the remainder stays with the other side, so parts always
 * sum to the whole: no invented GP, no decimals, no drift. All multiplication is long-safe.
 */
class BasisMath {
    /**
     * Floor-share of a pooled quantity/basis pair; consuming everything takes the exact remainder.
     */
    static long shareOf(long totalQty, long totalBasisGp, long consumeQty) {
        long qty = nonNeg(totalQty);
        long basis = nonNeg(totalBasisGp);
        long consume = nonNeg(consumeQty);
        if (qty <= 0L || basis <= 0L || consume <= 0L) return 0L;
        if (consume >= qty) return basis;
        return multiplyDivide(basis, consume, qty);
    }

    /**
     * Known-coverage share of an observed settlement: the known portion takes the floor share and
     * the unknown remainder receives the integer difference, so both always sum to the settlement.
     */
    static long knownProceedsOf(long totalSettlementGp, long realizedQty, long knownQty) {
        long total = nonNeg(totalSettlementGp);
        long realized = nonNeg(realizedQty);
        long known = nonNeg(min(knownQty, realized));
        if (realized <= 0L || known <= 0L) return 0L;
        if (known >= realized) return total;
        return multiplyDivide(total, known, realized);
    }

    /** Long-safe floor(value × numerator / denominator) for non-negative inputs. */
    static long multiplyDivide(long value, long numerator, long denominator) {
        if (denominator <= 0L || numerator <= 0L || value <= 0L) return 0L;
        if (numerator >= denominator) return value;
        BigInteger product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(numerator));
        BigInteger share = product.divide(BigInteger.valueOf(denominator));
        // b < c, so the share is below a and fits a long.
        return share.longValue();
    }
}
