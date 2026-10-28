package com.gpmanager;
import static com.gpmanager.ActionReader.*;
import static com.gpmanager.GeCustodyLedger.AttributionKind.*;

import static java.time.ZoneId.*;

import static com.gpmanager.Confidence.CONFIRMED;
import com.google.gson.annotations.SerializedName;
import static java.lang.Math.abs;
import static com.gpmanager.SafeMath.*;
import static com.gpmanager.Spells.*;
import static com.gpmanager.ReviewRules.*;
import static com.gpmanager.KeyChestCatalogue.*;
import static com.gpmanager.GameData.*;
import static com.gpmanager.ActionKind.*;
import static com.gpmanager.Context.*;
import static com.gpmanager.Context.TRANSFER;
import static com.gpmanager.Context.LOOT;
import static com.gpmanager.Context.PK_LOOT;
import static com.gpmanager.FlowFilters.*;
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
import static net.runelite.api.gameval.ItemID.*;
import java.util.function.*;
import static java.util.Locale.*;
import java.util.regex.*;
import static com.gpmanager.Correction.*;
import static com.gpmanager.PriceSource.*;
import java.math.BigInteger;
import static com.gpmanager.GeOffer.Side.*;
import static com.gpmanager.GeOffer.CustodyConfidence.*;
import static com.gpmanager.GeOffer.Stage.*;
import com.gpmanager.GeOffer.*;
import com.gpmanager.OfferSlots.Snapshot;
import net.runelite.api.*;

@Singleton
class Engine {
    final RaidHistory raids = new RaidHistory(this);
    static final int MINIMUM_BASELINE_WARMUP_TICKS = 3;

    /** After a resume the sidebar/HUD show CALIBRATING this long; bookings stay immediate. */
    static final long CALIBRATING_MILLIS = 2500;

    static final String UNCLASSIFIED_LOCAL_DEATH_NOTE = msg("unclassified-local");
    FlowValuator valuation;
    TransactionClassifier classifier;
    GpManagerConfig config;
    LootPresentationFilterService eligibility;
    List<Session> history = new ArrayList<>();

    /** One home for pending physical evidence; Persistence supplies its owner token. */
    Inbox events = new Inbox();

    SessionArchive archive = new SessionArchive(this);

    /**
     * Profile-local date basis; persisted so travelling or changing the host zone cannot shift
     * history.
     */
    String zone = ZoneId.systemDefault().getId();

    /** Domain revision: every canonical mutation advances it; derived previews bind to it. */
    long revision;

    /** Derived presentation cache; never part of SavedState or an accounting authority. */
    transient MoneySnapshot snapshot;
    /** Pending dose Reviews only; at most four background attempts and one row per tick. */
    final PriorityQueue<DoseRetry> doseRetries = new PriorityQueue<>(Comparator.comparingLong((DoseRetry value) -> value.at)
            .thenComparingLong(value -> value.row.timestamp));
    @RequiredArgsConstructor
    static class DoseRetry {
        final Session session;
        final Transaction row;
        long at;
        int attempts;
    }
    void queueDose(Session session, Transaction row) {
        if (delayedDose(row)) doseRetries.add(new DoseRetry(session, row));
    }
    void retryDose(long at) {
        var next = doseRetries.peek();
        if (next == null || at < next.at) return;
        doseRetries.remove();
        if (sessionById(next.session.id()) != next.session
                || next.session.findTransaction(next.row.id()) != next.row
                || !delayedDose(next.row) || recoverDose(next.session, next.row, at)) return;
        if (++next.attempts < 4) {
            next.at = sum(at, next.attempts == 1 ? 10_000 : next.attempts == 2 ? 60_000 : 1_860_000);
            doseRetries.add(next);
        }
    }

    synchronized MoneySnapshot summary() {
        return MoneySnapshot.of(this);
    }

    /** Active RuneScape profile key supplied by the persistence coordinator. */
    String profile = "";

    // The general tracker is durable. A custom session is the only temporary
    // owner and takes the active pointer while General is explicitly paused.
    Session general, custom;

    /** A factory reset arms a fresh start a few seconds later, if nobody starts tracking first. */
    long startAt;

    Session current;
    ContainerSnapshot baseline;
    boolean warming;

    /** Epoch millis the post-resume calibration cue clears at; 0 when none is armed. */
    long warmUntil;

    ContainerSnapshot sample;
    int warmTicks, stable;
    boolean dirty;
    ContainerSnapshot pending;
    Observation cast;
    int stableTicks;

    /*
     * --- Per-source bank-transfer evidence lifetimes ---
     *
     * Bone burial (or any other consumption) that settles after the bank UI has closed must
     * classify as CONSUMPTION, not TRANSFER. Two evidence sources live in the Inbox, each with
     * its own lifetime, because they mean different things:
     *
     *   - SOFT evidence (Inbox SOFT_TRANSFER / BANK_UI, read by bankOpen()) only means "the bank
     *     widget happened to be visible". It is a weak, one-sided signal and bankClosed() drops it
     *     at once, so it can never outlive the UI and misclassify an unrelated burial/eat/drink/
     *     cast that settles after close.
     *   - HARD evidence (Inbox HARD_TRANSFER, read by hasHardTransfer()) means a real
     *     bank-container/menu deposit or withdrawal was confirmed. It survives bank close for a
     *     short, bounded TTL, so a same-tick race between the bank-container event and the
     *     inventory dirty callback still books the transfer correctly; settle or expiry clears it.
     *
     * A stale soft latch can never poison a burial after close; only live hard evidence can.
     * {@link Inbox} applies the same per-source lifetimes to consumption/drop intent.
     * `ConsumptionBurialEngineTest`, `BankBoundaryBurialTest` and
     * `PresentationFilterAccountingIsolationEngineTest#burialCallbackBeforeGameTickBankCloseMustNotLatchOldTransfer`
     * lock this in.
     */
    DeathReclaimLifecycle death = new DeathReclaimLifecycle();

    /** Session-owned measured balances; never persisted or shared across owners. */
    MeasuredChargeReadTracker checks = new MeasuredChargeReadTracker();

    ChargeEstimateJournal estimate = new ChargeEstimateJournal();
    Map<Integer, Long> stored = new HashMap<>();
    boolean zoneTransfer, capture;
    ContainerSnapshot storedBaseline;
    int captureTicks, restoreTicks;

    /** Active clue path for dig/tele/key cost pairing. */
    ClueCostPairing clues = new ClueCostPairing();

    /** Key/chest claims (the one durable evidence exception, section H) and their live evidence. */
    KeyClaims claims = new KeyClaims();

    @Inject
    Engine(Valuation valuationService, TransactionClassifier classifier, GpManagerConfig config) {
        this((FlowValuator) valuationService, classifier, config);
    }

    Engine(FlowValuator valuationService, TransactionClassifier classifier, GpManagerConfig config) {
        valuation = valuationService;
        this.classifier = classifier;
        this.config = config;
        events.reset = this::clearEphemera;
        exchange.setBasisLedger(basis);
        basis.openReservationLookup = exchange::latestReservationTime;
    }

    @Inject
    void setContributionEligibility(LootPresentationFilterService eligibility) {
        this.eligibility = eligibility;
    }

    /**
     * Restore using a supplied clock so retention and recovery rules are deterministic in tests.
     */
    synchronized void restore(SavedState state, long now) {
        ground = null;
        doseRetries.clear();
        history.clear();
        archive.clear();
        plans.clear();
        saved.clear();
        zone = state != null && validZone(state.profileTimeZoneId()) ? state.profileTimeZoneId()
                        : ZoneId.systemDefault().getId();
        changed();
        claims.clear();
        // Known-basis coverage restores before any session can notify the observer; a pre-106
        // state starts with no known coverage (existing holdings are unknown, never zero).
        if (state != null) {
            // Saved GE records are clamped once here, so their accessors read fields directly.
            for (var record : state.geCustody()) if (record != null) record.sanitize();
        }
        basis.restore(state, now);
        general = null;
        custom = null;
        current = null;
        events.drop(DROP_CLICK);
        if (state != null) {
            if (state.savedSessions != null) for (var saved : state.savedSessions)
                if (saved != null && saved.id != null && saved.id.startsWith("saved:")
                        && saved.session != null && saved.session.isClosed() && saved.market != null) {
                    saved.session.sanitize();
                    this.saved.add(saved);
                }
            for (var plan : state.savedGrinds()) {
                if (plan != null && !plan.id().isEmpty() && !plan.name().isEmpty())
                    plans.add(plan);
            }
            for (var session : state.history()) {
                if (session != null) history.add(session);
            }
            general = state.generalSession;
            custom = state.customSession;
            if (general != null) general.grind = false;
            if (custom != null) custom.grind = true;
            for (var session : sessions()) {
                session.sanitize();
                attachListeners(session);
                for (var row : session.transactions()) queueDose(session, row);
            }
            current = custom != null && !custom.isClosed() ? custom : general;
            if (current != null && !current.isClosed()) {
                if (!current.paused) {
                    long savedAt = state.savedAtEpochMillis;
                    current.pause(savedAt > 0 ? min(now, savedAt) : now, RECOVERY);
                } else
                    current.pauseReason = RECOVERY;
            }
        }
        // Reclaim evidence is ephemeral and belongs to the identity being replaced;
        // never carry one account's lost-item whitelist into another account's rows.
        death.reset();
        // The looting bag's dispenser additions belong to the account that earned them.
        bag.clear();
        rememberBag(state == null ? null : state.lootingBag);
        reward.clear();
        // A restart of the same owner restores its persisted pending death/reclaim evidence.
        death.restore(state == null ? null : state.pendingDeathReclaim);
        // Custody lifecycles are profile-scoped financial continuity state.
        exchange.restore(state, now);
        resetTracking(false);
        raids.restore(state == null ? null : state.raidHistory);
        if (state != null) {
            // Claims settled by a retained transaction are stale duplicates and must never book
            // again.
            claims.restore(state, sessions());
            archive.compactAfterRestore(now);
        }
    }

    /**
     * Ongoing load-time repricing and GE audit-row cleanup. The bounded worker owns missing-price
     * Drink pairs; historical spell and GE compatibility repairs have been retired. Owner
     * corrections stay unchanged, and an unchanged second pass does not advance the revision.
     * Historical grave-looking rows are never rewritten: item overlap cannot prove a recovery, so
     * ambiguous old income waits for the owner's explicit correction.
     */
    synchronized void recheck(IntUnaryOperator alchPrice) {
        boolean changed = false;
        var drops = new ArrayList<Runnable>();
        for (var session : sessions()) {
            for (var row : session.transactions()) {
                if (row.correction() != AUTO || Contribution.isSplit(row)) continue;
                // Neutral GE change stays only as an audit row, like a live custody move.
                if (row.type() == TransactionType.TRANSFER && msg("ge-custody").equals(row.note())
                        && row.flows().size() == 1 && row.flows().get(0).item == COINS) {
                    if (!config.keepTransferAuditRows()) drops.add(() -> session.drop(row));
                    continue;
                }
                // The bounded worker owns dose repricing; the login scan cannot publish half a pair.
                if (delayedDose(row)) continue;
                boolean priced = false;
                for (Flow flow : row.flows()) {
                    if (flow.source() != UNPRICED) continue;
                    // An item that had no price now has one: its alch value, worth nothing, or a share
                    // of the whole (half a pie, owner 1.5.1).
                    for (Flow now : valuation.value(Map.of(flow.item, flow.quantity))) {
                        if (now.source() != UNPRICED) {
                            flow.unitPrice = now.unitPrice;
                            flow.value = now.value;
                            flow.priceSource = now.source();
                            priced = true;
                        }
                    }
                }
                if (priced) {
                    // A new list, so the row's derived views are rebuilt.
                    row.flows = new ArrayList<>(row.flows);
                    row.recalculate();
                    session.notifyChanged();
                    changed = true;
                }
            }
        }
        drops.forEach(Runnable::run);
        if (changed || !drops.isEmpty()) changed();
    }

    static boolean delayedDose(Transaction row) {
        return needsReview(row) && row.action() == DRINK && !Contribution.isSplit(row)
                && msg("consumed-dose").equals(row.explanation());
    }

    Set<String> decisions(Session session) {
        if (session.correctionHistory == null) return null;
        var decided = new HashSet<String>();
        for (var record : session.correctionHistory) {
            if (record == null || record.changes == null) return null;
            for (var change : record.changes()) {
                if (change == null) return null;
                decided.add(change.transactionId());
            }
        }
        return decided;
    }
    /** Publishes a verified dose pair atomically from the bounded pending queue. */
    boolean recoverDose(Session session, Transaction row, long at) {
        if (!delayedDose(row) || row.original() != UNCERTAIN || row.counted || row.context() != GENERIC
                || !row.note().isEmpty() || row.onGround() || !row.claimId().isEmpty()
                || !row.encounterId().isEmpty() || row.flows().size() != 2) return false;
        var decided = decisions(session);
        if (decided == null || decided.contains(row.id())) return false;
        var draft = SaveCopies.copy(row);
        boolean captured = Valuation.capturedDose(row.flows());
        boolean known = false;
        try {
            for (int i = 0; i < draft.flows.size(); i++) {
                var flow = draft.flows.get(i);
                if (!blank(flow.raid)) return false;
                if (flow.source() == UNPRICED) {
                    var quote = valuation.value(Map.of(flow.item, flow.quantity), at);
                    if (quote.size() != 1 || quote.get(0).item != flow.item
                            || quote.get(0).quantity != flow.quantity) return false;
                    draft.flows.set(i, quote.get(0));
                    flow = draft.flows.get(i);
                } else known = true;
                if (flow.unitPrice <= 0
                        || flow.value != multiplyExact(flow.quantity, flow.unitPrice)) return false;
            }
            if (!captured) {
                var normalized = valuation.normalizeConsumedFlows(draft.flows, DRINK, at);
                if (normalized == null || normalized == draft.flows || normalized.size() != 2) return false;
                long raw = draft.flows.stream().mapToLong(flow -> flow.value).sum();
                long cost = normalized.stream().mapToLong(flow -> flow.value).sum();
                // Known quotes are immutable. A later authority can verify the same monetary step.
                if (known && raw != cost || cost > 0) return false;
                draft.replaceFlows(known ? draft.flows : normalized);
            }
        } catch (ArithmeticException ex) { return false; }
        draft.type = CONSUMPTION;
        draft.counted = true;
        boolean physical = basis.canIntroduceSink(emptyMap(), basis.sinks(draft), row.timestamp)
                && basis.canApplyAll(basis.contribution(draft), 1, row.timestamp);
        if (!physical && !captured) return false;
        row.replaceFlows(draft.flows());
        row.type = CONSUMPTION;
        row.counted = true;
        row.confidence = confidenceFor(row.context(), CONSUMPTION);
        row.explanation = explain(row.context(), CONSUMPTION, true);
        if (physical) basis.onAdded(row, false);
        else row.note = msg("captured-dose");
        session.notifyChanged();
        return true;
    }

    synchronized void ensureSession(long now) {
        if (general == null || general.isClosed())
            // Overall is the durable default owner display name; it must still
            // accept automatic activity detection just as the former General default
            // session did.
            general = newSession(Session.DURABLE_OWNER_NAME, now, false);
        if (custom == null || custom.isClosed()) {
            custom = null;
            current = general;
        }
    }

    /** Rate and displayed clock only: retain all accounting, targets and pending action evidence. */
    synchronized void restartRate(long now, boolean savePrevious) {
        if (current == null) return;
        if (savePrevious) current.savedRateMillis = current.liveElapsed(now);
        current.restoredRateMillis = null;
        current.rateStartAt = now;
        current.rateSetMillis = current.elapsed(now);
        warmUntil = now + CALIBRATING_MILLIS;
        changed();
    }

    /** Resume a frozen clock, falling back to the original run; bookings stay unchanged. */
    synchronized void restoreRate(long now) {
        if (current == null) return;
        var saved = current.savedRateMillis;
        long elapsed = current.elapsed(now);
        if (saved != null && (saved < 0 || saved > elapsed)) return;
        current.rateStartAt = saved == null ? current.startedAt : now;
        current.rateSetMillis = saved == null ? 0 : elapsed;
        current.restoredRateMillis = saved;
        changed();
    }

    /**
     * New session (owner 1.5.2): Live's Net, Recent, GP/h and clock start at zero and a set target
     * counts again with it, while the run keeps every booking and its whole total.
     */
    synchronized void newLive(long now) {
        if (current == null) return;
        var run = current;
        long elapsed = run.elapsed(now);
        run.rebaseTarget(metrics(now).net, elapsed);
        run.rearmTimeTarget(now);
        run.sessionStartAt = now;
        run.sessionStartMillis = elapsed;
        run.rateStartAt = run.restoredRateMillis = null;
        changed();
    }

    /**
     * Back to the whole run on Live, from the run total under the Net (owner 1.5.2): the session
     * ends, nothing else changes. A running target is its own session, so it stays.
     */
    synchronized void showWholeRun() {
        if (current == null) return;
        current.rateStartAt = current.startedAt;
        current.rateSetMillis = 0;
        current.restoredRateMillis = null;
        if (current.targetGp() == null && current.timeTargetMillis() == null)
            current.sessionStartAt = null;
        changed();
    }

    /**
     * Reset free-play (owner 1.5.2): the run so far moves to Grinds history, nothing deleted,
     * and a new Free play starts at zero with the same milestone. Not during a Grind.
     */
    synchronized boolean startFreePlayOver(long now) {
        if (custom != null || general == null) return false;
        becomeFreePlay(newSession(Session.DURABLE_OWNER_NAME, now, false), now);
        return true;
    }

    /**
     * Restore (owner 1.5.2): a past Free play run becomes Free play again and the current one takes
     * its place in history, so the two can swap back; nothing is deleted. Not during a Grind.
     */
    synchronized boolean restoreFreePlay(String id, long now) {
        var old = historySession(id);
        if (custom != null || general == null || old == null || old.grind) return false;
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
        var done = general;
        clearCollect();
        done.rename("Free play");
        done.close(max(done.startedAt, now));
        if (!done.transactions().isEmpty()) history.add(0, done);
        general = current = next;
        next.milestoneGp = done.milestoneGp;
        if (done.pauseReason() == MANUAL) next.pause(now, MANUAL);
        resetTracking(true);
        changed();
    }

    /** Free play's Net milestone (owner 1.5.2): it counts Free play's whole Net; null clears it. */
    synchronized boolean setMilestone(Long gp) {
        if (general == null || customActive()) return false;
        general.milestoneGp = gp == null || gp <= 0 ? null : gp;
        targetUntil = 0;
        changed();
        return true;
    }

    /** Presentation countdown only; completion clears the saved milestone, never run targets. */
    long targetUntil;
    Session targetOwner;

    synchronized void tickMilestone(long now) {
        if (targetOwner != general) {
            targetOwner = general;
            targetUntil = 0;
        }
        if (general == null || customActive() || general.milestoneGp == null
                || general.milestoneGp <= 0) return;
        if (targetUntil > 0) {
            if (now >= targetUntil) setMilestone(null);
        } else if (general.metrics(now).net >= general.milestoneGp)
            targetUntil = sum(now, 8000);
    }

    synchronized void startCustom(String name, long now) {
        ensureSession(now);
        if (custom != null)
            // This transition needs an explicit Finish custom first. Starting
            // another custom session must never silently archive the current one.
            return;
        clearCollect();
        if (!general.paused) general.pause(now, CUSTOM_SESSION);
        custom = newSession(name, now, true);
        current = custom;
        resetTracking(true);
    }

    synchronized boolean finishCustom(long now) {
        if (custom == null) return false;
        long closeAt = max(custom.startedAt, now);
        clearCollect();
        custom.close(closeAt);
        history.add(0, custom);
        custom = null;
        current = general;
        if (general != null && general.pauseReason() == CUSTOM_SESSION)
            general.resume(closeAt);
        resetTracking(true);
        return true;
    }

    synchronized boolean customActive() {
        return custom != null && current == custom && !custom.isClosed();
    }

    synchronized void pauseForLifecycle(long now) {
        resetTracking(false);
        if (live()) current.pause(now, LIFECYCLE);
    }

    /**
     * True on a separate-economy world (Leagues, Deadman, beta, tournament, speedrunning, no-save):
     * tracking pauses there and neither starts nor resumes until it is left (owner 1.5.0).
     */
    boolean separate;

    /** Holds the world pause; any existing pause, Manual included, is left as it is. */
    synchronized void pauseForWorld(long now) {
        separate = true;
        if (live()) {
            resetTracking(false);
            current.pause(now, WORLD);
        }
    }

    /** Resumes an automatic pause of one of these reasons; Manual Pause always needs the owner. */
    synchronized boolean resume(long now, PauseReason... reasons) {
        if (current != null && current.paused && !separate
                && Arrays.asList(reasons).contains(current.pauseReason())) {
            current.resume(now);
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
        if (!config.autoStartSession() || separate) return false;
        if (custom != null && !custom.isClosed()) return false;
        if (general != null && !general.isClosed()) return false;
        ensureSession(now);
        arm(now);
        return true;
    }

    /** Fresh tracking: clear the evidence and calibrate the rate from now. */
    void arm(long now) {
        resetTracking(true);
        warmUntil = now + CALIBRATING_MILLIS;
    }

    /** AFK pause: only a running session pauses, from now, so the timeout itself still counts. */
    synchronized void pauseIdle(long now) {
        if (live()) {
            resetTracking(false);
            current.pause(now, IDLE);
        }
    }

    synchronized boolean isIdlePaused() {
        return current != null && current.pauseReason() == IDLE;
    }

    synchronized void setDetectedActivity(String activity, long now) {
        if (current != null && current.mode() == SessionMode.AUTO)
            current.setActivityHint(activity, now);
    }

    synchronized void togglePause(long now) {
        if (current == null) {
            // Start tracking: a new install, or after a factory reset, with Automatic tracking off.
            ensureSession(now);
            arm(now);
        } else if (current.paused) {
            current.resume(now);
            arm(now);
        } else {
            resetTracking(false);
            current.pause(now, MANUAL);
            startAt = 0;
        }
    }

    /** A factory reset resumes tracking by itself a few seconds later, unless stopped first. */
    synchronized void tickAutoStart(long now) {
        if (startAt <= 0 || now < startAt) return;
        startAt = 0;
        if (current == null) {
            ensureSession(now);
            resetTracking(true);
        }
    }

    /** Seconds until the pending factory-reset resume fires; 0 when none is armed. */
    synchronized long autoResumeSeconds(long now) {
        long left = startAt - now;
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** True while the post-resume calibration cue is up; bookings are immediate and never wait. */
    synchronized boolean calibrating(long now) {
        return now < warmUntil;
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

    List<Session> sessions() {
        var unique = new LinkedHashMap<String, Session>();
        for (var session : history) addUnique(unique, session);
        addUnique(unique, general);
        addUnique(unique, custom);
        addUnique(unique, current);
        return new ArrayList<>(unique.values());
    }

    static void addUnique(Map<String, Session> unique, Session session) {
        if (session != null) unique.putIfAbsent(session.id(), session);
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
        warming = false;
        clearPriming();
    }

    synchronized void markDirty() {
        if (warming) return;
        dirty = true;
        stableTicks = 0;
        // Animation lag: refresh consume/drop TTLs when the inventory finally moves so
        // bury/drink intents survive until stabilization rather than expiring idle.
        events.refresh(CONSUME, 4);
        events.refresh(SPENT_CONSUME, 4);
        events.refresh(DROP_CLICK, max(4, config.stabilizationTicks() + 2));
        captureContext();
        if (bankOpen() && (dirty || pending != null)) {
            pending().transfer = true;
            if (pending().context == GENERIC) {
                pending().context = TRANSFER;
                pending().note = "Bank transfer";
            }
        }
    }

    /** Owner-scoped live and stabilizing claims; HF and BO are independent sources. */
    Clue context() {
        return events.clue(LIVE_CLAIM);
    }

    Clue pending() {
        return events.clue(PENDING_CLAIM);
    }

    int clockLeft() {
        return events.left(CLOCK);
    }

    boolean hasHardTransfer() {
        return events.peek(HARD_TRANSFER) != null;
    }

    boolean bankOpen() {
        return events.peek(BANK_UI) != null;
    }

    /** The still-open consume command, from the sole pending-evidence store. */
    Observation intent() {
        return events.peek(CONSUME);
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
    /**
     * @param actionKind menu verb behind the click (Drink/Eat/Bury/…) or null when the option does
     *     not evidence a specific action. Presentation only.
     */
    /** Exact spell text is an optional presentation hint; it is not part of action matching. */
    synchronized void noteConsume(int id, int ticks, boolean destroy, ActionKind action,
            ActionLabel label) {
        // Eat/Bury/Drink/Cast/etc. — clear any pending Drop intent for the same click window.
        events.drop(DROP_CLICK);
        int safeTicks = max(1, ticks);
        var prior = intent();
        if (prior != null && id < 0 && prior.item >= 0) {
            // Chat/menu reinforcement without an id must not wipe a resolved item id,
            // flip a Destroy stamp to Used, or replace the resolved item's proven verb.
            // Exact spell text may refresh the label; a click that proves no name must not
            // erase the name already proven for this bounded interaction.
            events.refresh(CONSUME, safeTicks);
            if (label != null) prior.label = label;
            return;
        }
        if (prior != null && id >= 0 && prior.item >= 0 && prior.item != id && pending != null
                && baseline != null && pending.quantity(prior.item) < baseline.quantity(prior.item)) {
            // The earlier click's item already left the pack and its change has not settled: it
            // keeps its own evidence while the new click arms (owner live 2026-10-16: an Eat two
            // ticks after a Break left the tablet in Review).
            events.drop(SPENT_CONSUME);
            prior.kind = SPENT_CONSUME;
        }
        var e = events.put(CONSUME, id, 1, safeTicks - 1, null);
        // Prefer the pending (dirty) snapshot so a mid-window pickup is visible before the settle.
        var live = pending != null ? pending : baseline;
        long held = id < 0 || live == null ? -1 : live.quantity(id);
        // Keep the window's high-water: a later click re-arms at the current count, but a bite
        // that landed between the clicks must stay measurable against the stack's top (owner
        // 2026-10-12: withdraw-all then an eat while the clicks kept coming).
        if (prior != null && prior.item == id && prior.held > held) held = prior.held;
        e.held = held;
        e.flag = destroy;
        e.action = action;
        e.label = label;
    }

    /**
     * Clears only pending, unbooked action evidence at the profile/owner fence. Booked schema-105
     * action labels are durable metadata on canonical transactions and are never erased here;
     * lifecycle paths also reset pending consume evidence through {@link #resetTracking(boolean)}.
     */
    synchronized void clearActionEvidence() {
        events.drop(CONSUME, SPENT_CONSUME, CAST_SPELL, MANUAL_SPELL);
        cast = null;
    }

    /** Execution-time spell evidence survives unrelated clicks, but never supplies money. */
    synchronized void noteCast(ActionLabel autocast) {
        var manual = events.peek(MANUAL_SPELL);
        var spell = manual == null ? autocast : manual.label;
        events.drop(MANUAL_SPELL);
        events.put(CAST_SPELL, -1, 1, 2, null).label = spell;
    }

    /**
     * Chat confirmation ("You eat the chicken.") carries the verb the line proves: a chat that
     * arrives after the menu clicks lapse still names Ate/Drank instead of a bare "Used" (owner
     * 2026-10-12). A verb the menu already proved is never replaced.
     */
    synchronized void extendConsume(int ticks, ActionKind action) {
        int safeTicks = max(1, ticks);
        if (intent() != null) {
            events.refresh(CONSUME, safeTicks);
            if (action != null && intent().action == null)
                intent().action = action;
            return;
        }
        noteConsume(-1, safeTicks, false, action, null);
    }

    /** Player-initiated Drop action: the confirmed inventory removal books as Dropped. */
    synchronized void noteDrop(int id, int ticks) {
        if (id < 0) return;
        events.drop(CONSUME);
        events.put(DROP_CLICK, id, 1, max(1, ticks) - 1, null);
    }

    /**
     * Set-up or Pick-up of a dwarf multicannon: its parts are yours wherever they stand (Nulodion
     * replaces a lost one free), so moving them is neutral (owner 1.3.3). The base-to-furnace
     * assembly runs for up to ~12 ticks after the click and its auto-load follows right behind,
     * so the custody must outlive them both (owner live 2026-10-13: 15 ticks expired right at the
     * furnace's own settle and booked it as a loss).
     */
    synchronized void moveCannon() {
        events.put(CANNON, -1, 1, 40, null);
    }

    /** Fire or Load: the balls it takes (granite first) are remembered until it is picked up. */
    synchronized void loadCannon(int ticks) {
        if (baseline != null)
            noteDrop(baseline.quantity(GRANITE_CANNONBALL) > 0 ? GRANITE_CANNONBALL
                            : MCANNONBALL, ticks);
    }

    /**
     * Sailing's additional ball tiers: bronze, iron, mithril, adamant, rune and dragon. The plain
     * steel tier is the classic id-2 ball and granite also serves ship cannons, so both already
     * sit in {@link #cannonball}; the exact ids here add the chainshot and incendiary types, so a
     * ship-cannon volley reads the same as a dwarf load (owner 1.3.4 ship sweep).
     */
    private static final Set<Integer> SAIL_CANNONBALLS = Set.copyOf(byId("sail_cannonballs").keySet());

    static boolean cannonball(int id) {
        return id == MCANNONBALL || id == GRANITE_CANNONBALL || SAIL_CANNONBALLS.contains(id);
    }

    /** The four dwarf multicannon parts, unnoted ids as placed (TWPART1..4). */
    static boolean cannonPart(int id) {
        return id == TWPART1 || id == TWPART2 || id == TWPART3
                || id == TWPART4;
    }

    /** Balls already inside a deployed cannon before this window; their return is neutral. */
    int cannonSeed;

    /** Seeds deployed parts/ammo from the native counters on a cannon interaction. */
    synchronized void syncCannon(int parts, int balls, boolean decayed) {
        if (decayed) {
            cannonSeed = 0;
            events.drop(DROPPED, e -> !cannonball(e.item));
            return;
        }
        if (parts != 4) return;
        long loaded = events.all(DROPPED).stream().filter(e -> cannonball(e.item)).mapToLong(e -> e.qty).sum();
        cannonSeed = max(cannonSeed, balls - (int) loaded);
    }

    /** Money evidence is owner-checked and consumed under the booking monitor. */
    synchronized void bagged(Observation e, long qty) {
        if (e.owner != events.owner) return;
        long moved = events.take(e.kind, e.item, qty, v -> v == e);
        if (e.flag && moved > 0) {
            bag.merge(e.item, moved, SafeMath::sum);
            changed();
        }
        bagged(e.item, moved);
    }

    /** A measured Bank-all decrease is received directly; bank callbacks cannot hide it. */
    synchronized Transaction banked(Observation e, long qty, long now) {
        if (!live() || e.owner != events.owner || e.kind != CHEST || e.item < 0) return null;
        long received = events.take(e.kind, e.item, qty, v -> v == e);
        if (received <= 0) return null;
        var row = bookTransaction(now, TransactionType.LOOT, LOOT, msg("banked-reward"),
                        current.activityHint(), true, valuation.value(singletonMap(e.item, received)),
                        CONFIRMED, msg("banked-proof"), null);
        add(row, false);
        return row;
    }

    /**
     * Books what really left the clicked Bank-all chest, once, for the owner whose callback sampled
     * it, and replaces the chest's proof with the new counts. The inbox is checked, spent and
     * re-armed in one hold under this monitor, so no owner fence lands inside it.
     */
    synchronized List<Transaction> chest(int id, Map<Integer, Long> counts, int tick, long owner, long now) {
        counts = cash(counts);
        var rows = new ArrayList<Transaction>();
        synchronized (events) {
            var e = events.peek(CHEST, v -> v.item == -1 && v.x == id);
            if (e == null || owner != events.owner || tick - e.at > 10) return rows;
            for (var event : events.all(CHEST)) {
                long gone = event.qty - counts.getOrDefault(event.item, 0L);
                if (event.item >= 0 && gone > 0) rows.add(banked(event, gone, now));
            }
            events.open.removeIf(v -> v.kind == CHEST && v.item >= 0);
            counts.forEach((item, qty) -> events.add(CHEST, item, qty, e.until - events.now, null));
        }
        rows.removeIf(Objects::isNull);
        return rows;
    }

    synchronized void fee(Observation e, long now) {
        if (e.owner == events.owner)
            bookDeathFee(events.take(Inbox.FEE, -1, e.qty, v -> v == e), e.note, now);
    }

    /**
     * A pickup that went straight into an open bag reads as an inventory gain (owner 1.2): the
     * baseline forgets it, so the next settle books it like any pickup.
     */
    synchronized void bagged(int id, long quantity) {
        if (baseline == null || warming || quantity <= 0) return;
        if (id == PLATINUM) { id = COINS; quantity = multiply(quantity, 1000); }
        var held = new HashMap<>(baseline.items);
        held.merge(id, -quantity, Long::sum);
        baseline = new ContainerSnapshot(held);
        markDirty();
    }

    /** Clears the export identity when persistence invalidates the active account. */
    synchronized void clearIdentity() {
        profile = "";
    }

    /** Binds the identity and restores its state under the same engine lock. */
    synchronized void restoreProfile(String identity, SavedState state, long now) {
        String next = fallback(identity, "");
        Gson codec = JsonCodec.gson();
        var detached = state == null ? null : codec.fromJson(codec.toJson(state), SavedState.class);
        String stateOwner = detached == null ? null : detached.ownerKey;
        if (!next.isEmpty() && !blank(stateOwner) && !next.equals(stateOwner))
            throw new IllegalArgumentException(msg("savedstate-owner"));
        var draft = prepareProfile(next, detached, now);
        installProfile(draft, next);
    }

    /** The bound profile's state as saved, for a backup copy of the save file. */
    synchronized SavedState exportState() {
        var snapshot = savedState();
        snapshot.setOwnerKey(profile);
        return snapshot;
    }

    Engine prepareProfile(String identity, SavedState state, long now) {
        // An isolated engine sharing this one's valuation, classifier and config.
        var draft = new Engine(valuation, classifier, config);
        draft.setContributionEligibility(eligibility);
        draft.profile = identity;
        draft.restore(state, now);
        return draft;
    }

    void installProfile(Engine draft, String identity) {
        // Allocate copies and attach listeners before changing live fields. The
        // normal restore/migration path has already completed on the isolated engine.
        var nextHistory = new ArrayList<>(draft.history);
        var nextSessions = new ArrayList<>(draft.sessions());
        for (var session : nextSessions) attachListeners(session);
        // Clear identity-scoped transient evidence while durable profile fields
        // still belong to the previous owner. No reader can observe the swap
        // because agl/restoreProfile hold this engine's monitor.
        events.drop(DROP_CLICK);
        death.reset();
        // The looting bag's dispenser additions belong to the account that earned them.
        bag.clear();
        contents = new HashMap<>(draft.contents);
        reward.clear();
        clearCollect();
        resetTracking(false);
        coffer = -1;
        history = nextHistory;
        doseRetries.clear();
        doseRetries.addAll(draft.doseRetries);
        plans.clear();
        plans.addAll(draft.plans);
        saved.clear();
        saved.addAll(draft.saved);
        archive.adoptFrom(draft.archive);
        zone = draft.zone;
        changed();
        claims.adoptFrom(draft.claims);
        death.adoptFrom(draft.death);
        exchange.adoptFrom(draft.exchange);
        basis.adoptFrom(draft.basis);
        ground = null;
        general = draft.general;
        custom = draft.custom;
        current = draft.current;
        profile = string(identity);
        raids.restore(draft.raids.snapshot());
    }

    synchronized void markContext(Context next, int ticks, String note) {
        if (next != LOOT && next != PK_LOOT) claims.cancelChest();
        if (next == TRANSFER || next == MARKET) events.drop(LOAD_TARGET);
        replaceDeathMarker(next, note);
        if (next == TRANSFER) {
            // Stale Eat/Bury intents must not reclassify bank deposits as Used/lost.
            events.drop(CONSUME, SPENT_CONSUME, DROP_CLICK);
            noteHardTransfer(max(1, ticks));
        }
        setContext(next, ticks, note, emptyMap(), null);
    }

    /**
     * Bank interface is open on this tick. Soft UI-open refresh only — does not by itself prove a
     * deposit/withdraw movement.
     */
    synchronized void bankOpened(int ticks) {
        claims.cancelChest();
        events.drop(LOAD_TARGET);
        replaceDeathMarker(TRANSFER, "Bank transfer");
        // Edge-trigger only. Live GameTick refreshes this every tick while the bank
        // widget/container is open — wiping Eat/Drink/Bury intents each tick made
        // potato-field sessions book every inventory delta as soft TRANSFER (Used/lost
        // ×0, no harvest rows) whenever bank detection stuck or fluttered.
        boolean newlyOpened = !bankOpen();
        events.ttl(BANK_UI, Integer.MAX_VALUE);
        if (newlyOpened)
            events.drop(CONSUME, SPENT_CONSUME, DROP_CLICK);
        events.ttl(SOFT_TRANSFER, max(events.left(SOFT_TRANSFER), max(1, ticks)));
        setContext(TRANSFER, ticks, "Bank transfer", emptyMap(), null);
    }

    /**
     * Bank interface closed this tick. Clears sticky soft TRANSFER hints so burial after close is
     * not classified as TRANSFER when the inventory callback raced ahead of this tick. Genuine
     * deposits keep pending TRANSFER only when hard bank-container/menu evidence was attached to
     * this pending change — or when hard evidence is still live and the inventory callback arrives
     * after close.
     */
    synchronized void bankClosed() {
        events.drop(BANK_UI);
        // Soft UI-open refresh only. Hard deposit/withdraw TTL must survive close so a
        // GameTick between bank-container evidence and inventory dirty stays TRANSFER.
        if (!hasHardTransfer()) events.drop(SOFT_TRANSFER);
        if (dirty || pending != null) {
            if (!pending().hard && !hasHardTransfer()) {
                // Soft UI-open latch only — clear so burial after close is CONSUMPTION.
                // Genuine deposits attach hard evidence via bank-container/menu markContext.
                pending().transfer = false;
                if (pending().context == TRANSFER) pending().clear();
            } else if (hasHardTransfer() && !pending().hard)
                // Inventory already dirty from soft open; promote with surviving hard evidence.
                latchHardTransfer();
            return;
        }
        if (context().context == TRANSFER && !deathNote(context().note)) {
            // Drop the soft UI-open note/ticks; live hard evidence keeps TRANSFER for a late
            // inventory callback.
            context().clear();
            context().context = hasHardTransfer() ? TRANSFER : GENERIC;
            events.drop(CLOCK);
        }
    }

    void noteHardTransfer(int ticks) {
        events.drop(LOAD_TARGET);
        int safeTicks = max(1, ticks);
        events.ttl(SOFT_TRANSFER, max(events.left(SOFT_TRANSFER), safeTicks));
        events.ttl(HARD_TRANSFER, max(events.left(HARD_TRANSFER), safeTicks));
        if (dirty || pending != null) latchHardTransfer();
    }

    void latchHardTransfer() {
        pending().transfer = true;
        pending().hard = true;
        if (pending().context == GENERIC || TRANSFER.priority > pending().context.priority) {
            // Keep the producer's own note (minigame wipe, neutral zone, death) — a
            // generic "Bank transfer" would otherwise win the priority race and mislabel
            // every non-bank transfer row.
            String note = context().context == TRANSFER && !blank(context().note) ? context().note
                            : "Bank transfer";
            applyContext(TRANSFER, note, emptyMap(), null);
        } else if (pending().context == TRANSFER) {
            pending().transfer = true;
            if (empty(pending().note)) pending().note = "Bank transfer";
        }
    }

    /**
     * Soft evidence: inventory became dirty while the bank UI was open. Survives bank close for
     * genuine deposits. Cleared by {@link #bankClosed()} when the pending change was only a stale
     * latch after the UI was already gone (plugin closes before dirty when {@code !isBankOpen()}).
     */
    synchronized void markLoot(Map<Integer, Long> expectedLoot, int ticks, String note, String activity) {
        var expected = positive(expectedLoot);
        if (!expected.isEmpty())
            loot(expected, ticks, note, activity, LOOT, null);
        setContext(LOOT, ticks, note, expected, null);
    }

    /**
     * Each private drop proves its own remaining quantity; attribution has the old shorter window.
     */
    void loot(Map<Integer, Long> counts, int ticks, String note, String name, Context context, String id) {
        counts.forEach((item, qty) -> {
                    var e = events.add(PRIVATE_LOOT, item, qty, 99, string(note));
                    e.at = events.now + max(1, ticks) - 1;
                    e.name = fallback(name, "General");
                    e.context = context;
                    e.id = id;
                });
    }

    void setContext(Context next, int ticks, String note, Map<Integer, Long> loot, String encounter) {
        var context = next == null ? GENERIC : next;
        var expected = positive(loot);
        replaceDeathMarker(context, note);
        boolean replace = clockLeft() <= 0 || context.priority > context().context.priority;
        if (replace || context == context().context)
            events.ttl(CLOCK, max(replace ? 0 : clockLeft(), max(1, ticks)));
        context().offer(context, note, expected, encounter, replace);
        if (dirty || pending != null)
            applyContext(context, note, expected, encounter);
    }

    /**
     * A death marker is a short-lived reconciliation hint, not a durable activity context. An
     * explicit later activity must own the next measured change even though TRANSFER has a higher
     * general context priority.
     */
    void replaceDeathMarker(Context context, String note) {
        boolean newDeathMarker = context == TRANSFER && deathNote(note);
        boolean activeMarker = context().context == TRANSFER && deathNote(context().note);
        boolean pendingMarker = pending().context == TRANSFER && deathNote(pending().note);
        if (!newDeathMarker && context != GENERIC && (activeMarker || pendingMarker)) {
            if (activeMarker) {
                context().clear();
                events.drop(CLOCK);
            }
            if (pendingMarker) pending().clear();
            events.drop(SOFT_TRANSFER);
            clearTransfer();
            death.cancelWipe();
        }
    }

    synchronized void markPkLoot(Map<Integer, Long> expectedLoot, int ticks, long now) {
        if (current == null && !autoStart(now)) return;
        if (!live()) return;
        var expected = positive(expectedLoot);
        String fight = current.addEncounter(true, now);
        if (current.mode() != SessionMode.GENERAL)
            current.attachRecentCosts(fight, now, 90000);
        String note = "PK loot";
        if (!expected.isEmpty())
            loot(expected, ticks, note, "PKing", PK_LOOT, fight);
        setContext(PK_LOOT, ticks, note, expected, fight);
    }

    /**
     * Local unsafe PvM death with a separate canonical inventory/equipment ownership snapshot. The
     * snapshot is reconciled against measured negative flows; presentation evidence remains
     * explanation-only.
     */
    synchronized void markPvmDeath(int ticks, Map<Integer, Long> heldItems, Map<Integer, Long> graveBag, long owner) {
        if (owner != events.owner) return;
        raids.death();
        int kind = RaidHistory.kind(raids.state.death);
        if (kind == 0 || kind == 2) return;
        armDeathReclaim(ticks, heldItems, graveBag);
    }

    void armDeathReclaim(int ticks, Map<Integer, Long> heldItems, Map<Integer, Long> graveBag) {
        markContext(TRANSFER, max(1, ticks), msg("death-items"));
        // Start after the transfer marker so this new death cannot cancel its own
        // bounded whitelist when strong-context supersession is evaluated.
        death.onPvmDeath(heldItems);
        // A positively identified Wilderness PvM death sends known bag stock to the grave.
        // It has no INV delta; retaining ownership here prevents a false gain on recovery.
        cash(graveBag).forEach((id, quantity) -> death.items.merge(id, quantity, SafeMath::sum));
        if (!death.items.isEmpty()) death.awaitingReclaim = true;
    }

    /**
     * Local death with no confident PvM / PK classification. Drop stale transfer and action
     * evidence so its inventory wipe is booked from ordinary loss evidence.
     */
    synchronized void markUnknownDeath() {
        raids.state.death = null;
        claims.cancelChest();
        death.reset();
        events.drop(CONSUME, SPENT_CONSUME, DROP_CLICK);
        clearTransfer();
        events.drop(SOFT_TRANSFER);
        events.drop(BANK_UI);
        events.ttl(CLOCK, max(6, config.stabilizationTicks() + 4));
        context().clear();
        context().note = UNCLASSIFIED_LOCAL_DEATH_NOTE;
        if (dirty || pending != null) {
            pending().clear();
            pending().note = UNCLASSIFIED_LOCAL_DEATH_NOTE;
        }
    }

    /**
     * Player interacted with a retrieval service (gravestone, Death, boss NPC or chest).
     *
     * @return true when the reclaim window was armed; ambiguous targets (a bare "Chest", a
     *     gravestone) only arm while a local PvM death is awaiting reclaim
     */
    synchronized boolean noteReclaimIntent(Service service, int ticks) {
        return death.noteReclaimIntent(service, ticks);
    }

    /** Current presentation read model for the local death-reclaim indicator. */
    synchronized DeathReclaimStatus deathStatus() {
        return new DeathReclaimStatus(death.isAwaitingReclaim(), death.armed(),
                death.outstanding(), death.age);
    }

    static Transaction first(Transaction... options) {
        for (var candidate : options) {
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
    Transaction settleReclaim(List<Flow> flows, long now, Map<Integer, Long> drops,
            boolean allowObservedFee) {
        if (empty(flows) || !death.isAwaitingReclaim() && !death.armed())
            return null;
        var service = death.service;
        var sourceBacked = positive(drops);
        // Source-backed loot quantities are protected first.
        var returned = claim(flows, Flow::gain, (flow, quantity) -> death.matchReturn(flow.item,
                                        quantity - take(sourceBacked, flow.item, quantity)));
        long coinsPaid = losses(flows, id -> id == COINS).getOrDefault(COINS, 0L);
        boolean hasOtherLoss = hasLoss(flows, identifier -> identifier != COINS);
        Transaction recovered = null;
        if (!returned.isEmpty()) {
            String source = service == null ? msg("grave-retrieval") : service.interactable;
            recovered = bookNeutral(now, msg("death-reclaim-items"), "Death reclaim", returned,
                            msg("items-recovered") + source + msg("after-death"), null);
            audit(recovered);
        }
        boolean feeEvidence = service != null || !returned.isEmpty();
        if (allowObservedFee && coinsPaid > 0 && feeEvidence && (!hasOtherLoss || !returned.isEmpty())) {
            String why = service == null ? msg("reclaim-fee") : service.why(coinsPaid);
            flows.removeIf(flow -> flow.item == COINS && flow.cost());
            return bookDeathFee(coinsPaid, why, now);
        }
        return recovered;
    }

    /** Create a neutral audit receipt for the measured negative portion of the death wipe. */
    Transaction deathWipeTransfer(List<Flow> flows, Map<Integer, Long> measured, long now) {
        if (empty(flows) || empty(measured)) return null;
        var outstanding = new HashMap<>(measured);
        var transferred = claim(flows, Flow::cost,
                        (flow, quantity) -> take(outstanding, flow.item, quantity));
        if (transferred.isEmpty()) return null;
        var bags = claim(transferred, Flow::cost, (flow, quantity) -> destroyedBag(flow.item) ? quantity : 0);
        Transaction loss = null;
        if (!bags.isEmpty()) {
            loss = bookTransaction(now, CONSUMPTION, GENERIC, msg("pvm-loss"), "Death reclaim", true,
                            bags, CONFIRMED, msg("bag-destroyed"), null);
            add(loss, false);
        }
        if (transferred.isEmpty()) return loss;
        Transaction transfer = bookNeutral(now, msg("death-items"), "Death reclaim", transferred,
                        msg("ownership-neutral-transfer"), null);
        audit(transfer);
        return first(loss, transfer);
    }

    static boolean destroyedBag(int id) {
        return id == LOOTING_BAG || id == LOOTING_BAG_OPEN;
    }

    void recordZoneEntry(Map<Integer, Long> changes) {
        if (changes != null)
            changes.forEach((id, delta) -> {
                        if (id != null && delta != null && delta < 0)
                            stored.merge(id, SafeMath.abs(delta), SafeMath::sum);
                    });
    }

    /**
     * Excludes only positive quantities that match items actually removed on zone entry. Item ids
     * with a matched observed-loot event remain counted as loot even if they happen to overlap the
     * stored gear.
     */
    Transaction settleZoneRestore(List<Flow> flows, Map<Integer, Long> drops, long now) {
        if (restoreTicks <= 0 || stored.isEmpty() || empty(flows))
            return null;
        var sourceBacked = positive(drops);
        var restored = claim(flows, Flow::gain, (flow, quantity) -> take(stored, flow.item,
                                        quantity - take(sourceBacked, flow.item, quantity)));
        if (restored.isEmpty()) return null;
        if (stored.isEmpty()) restoreTicks = 0;
        return bookNeutral(now, msg("neutral-zone-exit"), "Gauntlet", restored, msg("items-restored"), null);
    }

    static final Set<Integer> PVP_GRAVE = byId("pvp_gravestone").keySet();

    synchronized Transaction markPkDeath(long now, Map<Integer, Long> bag, Map<Integer, Long> held, long owner) {
        if (owner != events.owner) return null;
        raids.state.death = null;
        claims.cancelChest();
        if (current == null && !autoStart(now)) return null;
        if (!live()) return null;
        var grave = positive(held);
        grave.keySet().retainAll(PVP_GRAVE);
        death.onPvmDeath(grave);
        String fight = current.addEncounter(false, now);
        setContext(PK_DEATH, max(30, config.stabilizationTicks() + 4), "PK death loss", emptyMap(), fight);
        var lost = positive(bag);
        if (lost.isEmpty()) return null;
        lost.replaceAll((id, quantity) -> -quantity);
        var row = bookTransaction(now, PK_DEATH_LOSS, PK_DEATH, msg("bag-loss"), "PKing", true,
                        valuation.value(lost, now), Confidence.LIKELY, msg("bag-loss-proof"), fight);
        add(row, false);
        return row;
    }

    /**
     * An ownership-neutral storage or minigame move, with the note its classifier already chose.
     */
    synchronized void markTransfer(String note, int ticks) {
        markContext(TRANSFER, max(1, ticks), note);
    }

    /**
     * Known equipment changing wear/repair state stays owned. Pair by exact raw-id family;
     * preserve source-backed loot and charge only the observed repair fee.
     */
    Transaction wear(List<Flow> flows, long now, Map<Integer, Long> loot) {
        var reserved = positive(loot);
        var keep = new HashMap<>(reserved);
        var pieceNet = new HashMap<String, Long>();
        for (Flow flow : flows) {
            String part = piece(flow);
            if (part == null) continue;
            if (flow.cost() && flow.item == events.item(DROP_CLICK)) return null;
            long quantity = flow.quantity;
            if (quantity > 0) quantity -= take(reserved, flow.item, quantity);
            pieceNet.merge(part, quantity, SafeMath::sum);
        }
        // Unrecognized families default to a nonzero net and cannot be paired.
        var stepped = claim(flows, flow -> pieceNet.getOrDefault(piece(flow), 1L) == 0L,
                        (flow, quantity) -> flow.gain() ? quantity - take(keep, flow.item, quantity) : quantity);
        return stepped.isEmpty() ? null : bookNeutral(
                        now, msg("barrows-wear"), "Equipment", stepped, msg("barrows-gear"), null);
    }

    /**
     * An abyssal tentacle that runs out of charges, or is dissolved, leaves its kraken tentacle and
     * the whip inside is gone (OSRS Wiki). RuneLite prices the tentacle as the whip plus the kraken
     * tentacle, so the row nets to the whip: a loss, never a Review question (owner 1.3.3).
     */
    Transaction wornOut(List<Flow> flows, long now) {
        if (!hasLoss(flows, id -> id == ABYSSAL_TENTACLE)
                || gains(flows, id -> id == KRAKEN_TENTACLE).isEmpty()) return null;
        var pair = claim(flows, flow -> flow.item == ABYSSAL_TENTACLE ? flow.cost()
                                        : flow.item == KRAKEN_TENTACLE && flow.gain(),
                        (flow, quantity) -> 1);
        return bookCharge(now, msg("tentacle-used"), CONFIRMED, msg("tentacle-whip"), pair, null, false);
    }

    /** A dwarf multicannon's parts set up or picked up: the same items, yours wherever they stand. */
    Transaction cannon(List<Flow> flows, long now) {
        if (events.peek(CANNON) == null) return null;
        var parts = claim(flows, flow -> pattern("cannon").matcher(flow.name).matches(),
                        (flow, quantity) -> quantity);
        var seeded = claim(flows, flow -> flow.gain() && cannonball(flow.item), (flow, quantity) -> {
            long take = min(quantity, cannonSeed);
            cannonSeed -= take;
            return take;
        });
        parts.addAll(seeded);
        return parts.isEmpty() ? null : bookNeutral(now, msg("cannon-parts"), "Cannon", parts, msg("cannon-why"), null);
    }

    /** Known wear/repair states, including Barrows, Moons and Perdu; never a display-name guess. */
    static final Map<Integer, String> WEAR_PIECES = byId("wear_gear");

    static String piece(Flow flow) {
        return WEAR_PIECES.get(flow.item);
    }

    /**
     * A servant fetches from the bank, takes items to it and un-notes them: those moves are
     * neutral. Coins (his wage, a sawmill fee) still count, as do the logs a sawmill trip takes and
     * the planks it brings back, made from them.
     */
    Transaction servant(List<Flow> flows, long now) {
        if (events.peek(SERVANT) == null) return null;
        boolean sawmill = events.peek(SAWMILL) != null;
        var moved = claim(flows, flow -> flow.item != COINS && !(sawmill && wood(flow)),
                        (flow, quantity) -> quantity);
        // The sawmill trip ends when its planks come back.
        if (sawmill && flows.stream().anyMatch(flow -> flow.gain() && wood(flow)))
            events.drop(SAWMILL);
        return moved.isEmpty() ? null : bookNeutral(now, msg("servant-bank"), "Construction", moved,
                        msg("house-servant"), null);
    }

    /** Logs a sawmill trip takes, or the planks it brings back. */
    static boolean wood(Flow flow) {
        return pattern("wood").matcher(flow.name).matches();
    }

    /**
     * A measured Check supersedes this weapon's pending estimates. A component it read unchanged
     * was not used, so its estimates are removed, as a Check that moved nothing removes them all.
     */
    synchronized void clearPendingEstimates(String target, Set<Integer> unchanged) {
        if (current == null) return;
        String id = current.id();
        for (int identifier : unchanged) {
            for (var receipt :
                    estimate.receipts(id, target, identifier))
                forfeit(receipt);
        }
        estimate.clearTarget(id, target);
    }

    /**
     * A compatible Check that moved nothing closes the window for this target: any automatic
     * estimates still pending were overestimates and are removed through the announced path.
     */
    synchronized void closeChargeWindow(String target) {
        if (current == null || blank(target))
            return;
        String id = current.id();
        for (var receipt : estimate.receiptsFor(id, target))
            forfeit(receipt);
        estimate.clearTarget(id, target);
    }

    synchronized boolean markChargeLoad(ChargedItem variant, int selectedId, String selectedItemName,
            String target, int ticks) {
        if (variant == null || ticks <= 0 || !ChargeCheck.isLoadComponent(variant, selectedId, selectedItemName))
            return false;
        var e = events.put(LOAD_TARGET, selectedId, 1, ticks - 1, null);
        e.variant = variant;
        e.id = fallback(target, null);
        e.at = now();
        return true;
    }

    /**
     * A load click links candidate measured losses to a physical target; it proves no load
     * quantity.
     */
    LoadTarget loadTarget(List<Flow> flows) {
        var e = events.peek(LOAD_TARGET);
        var options = new ArrayList<Flow>();
        var left = new ArrayList<Flow>();
        boolean selected = false;
        for (Flow flow : e == null || flows == null ? Collections.<Flow>emptyList() : flows) {
            if (flow.cost() && ChargeCheck.isLoadComponent(e.variant, flow.item, flow.name)) {
                options.add(flow);
                selected |= flow.item == e.item;
            } else left.add(flow);
        }
        return selected ? new LoadTarget(e.variant, e.id, left, options, e.at)
                : new LoadTarget(null, null, flows == null ? emptyList() : flows, emptyList(), 0);
    }

    /**
     * A refused automatic use (the game answered "already at that location"): the click spent
     * nothing, so the estimate it booked is removed through the announced automatic-evidence path
     * (owner 1.3.4). Other pending estimates for the target are untouched.
     */
    synchronized void forfeitEstimate(String identity, String id) {
        if (current == null || empty(id)) return;
        String target = fallback(identity, null);
        if (target == null) return;
        for (var receipt : estimate.receiptsFor(current.id(), target)) {
            if (id.equals(receipt.transaction)) forfeit(receipt);
        }
    }

    /** Clear a pending charge-load intent when another action supersedes its evidence. */
    synchronized void clearChargeLoad() {
        events.drop(LOAD_TARGET);
    }

    /** Observe one exact Check read against that weapon's own last Check. */
    synchronized ChargeDelta observeCheck(ChargeCheck read, String target, long now) {
        return checks.observe(read, target, now);
    }

    /** Forget one kind's Check reads (every kind when null): its next Check starts fresh. */
    synchronized void resetChecks(ChargedItem variant) {
        if (variant == null) checks.reset();
        else checks.resetVariant(variant);
    }

    /**
     * A supported charged weapon entering the inventory is a new physical instance: a measured
     * Check baseline for that variant can no longer be trusted to belong to the item now in the
     * slot. Fail closed and require a fresh baseline instead of booking a phantom decrease from a
     * replacement that retained the same slot identity.
     */
    void invalidateChargeBaselines(Map<Integer, Long> changes) {
        if (empty(changes)) return;
        for (var entry : changes.entrySet()) {
            Long quantity = entry.getValue();
            if (quantity == null || quantity <= 0) continue;
            var variant = ChargeCheck.supported(entry.getKey());
            if (variant != null) checks.resetVariant(variant);
        }
    }

    /** Start tracking the items removed by entering a region-based neutral zone. */
    synchronized void beginNeutralZone() {
        stored.clear();
        zoneTransfer = true;
        capture = true;
        storedBaseline = baseline;
        // Inventory and equipment callbacks can settle in separate batches. Keep
        // reconciling against the entry snapshot for a short, fixed window so the
        // later batch is included, but in-zone supply use cannot be captured forever.
        captureTicks = max(8, config.stabilizationTicks() + 6);
        restoreTicks = 0;
    }

    /**
     * End the region-based transfer context. Only a matching return of an item actually removed on
     * entry remains neutral; later lobby rewards keep their normal classification.
     */
    synchronized void endNeutralZone(int ticks) {
        boolean zoneWasActive = zoneTransfer;
        zoneTransfer = false;
        capture = false;
        storedBaseline = null;
        captureTicks = 0;
        restoreTicks = stored.isEmpty() ? 0 : max(1, ticks);
        boolean pendingZone = pending().context == TRANSFER && pending().note != null
                        && pending().note.startsWith(msg("neutral-zone-instance"));
        boolean activeZone = context().context == TRANSFER && context().note != null
                        && context().note.startsWith(msg("zone-expired"));
        if (pendingZone) pending().clear();
        if (activeZone) {
            context().clear();
            events.drop(CLOCK);
        }
        if (zoneWasActive || pendingZone || activeZone) {
            events.drop(SOFT_TRANSFER);
            clearTransfer();
        }
    }

    /** Begin clue/casket path so dig/tele/key spends pair as clue costs. */
    synchronized void beginCluePath(String encounter, String label) {
        clues.beginClue(encounter, label);
    }

    /** Ids of the unresolved key/chest claims; a Ledger row with one of these ids is still pc(). */
    synchronized Set<String> pendingClaimIds() {
        var ids = new LinkedHashSet<String>();
        for (var claim : claims.all()) ids.add(claim.claimId());
        return ids;
    }

    synchronized List<PendingClaim> pendingClaims() {
        return claims.all();
    }

    synchronized void beginKeyDeath(Map<Integer, Long> keyInventory) {
        claims.beginLocalDeath(keyInventory);
    }

    synchronized void tickKeyClaims() {
        claims.tick();
    }

    /** Items taken apart and what doing it loses (d23). */
    static final Map<Integer, String[]> DISMANTLE = new HashMap<>();

    static {
        for (String[] row : rows("dismantle"))
            DISMANTLE.put(Integer.parseInt(row[0]), row);
    }

    /** The item a Revert or Dismantle click takes apart, for the next few ticks. */
    synchronized void dismantle(int id) {
        events.put(DISMANTLE_CLICK, id, 1, 5, null);
    }

    /** The clicked item left and something else arrived: the item was taken apart. */
    boolean takenApart(List<Flow> flows) {
        boolean out = false, in = false;
        for (Flow flow : flows) {
            if (events.item(DISMANTLE_CLICK) <= 0) continue;
            if (flow.item == events.item(DISMANTLE_CLICK) && flow.quantity < 0) out = true;
            else if (flow.quantity > 0) in = true;
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
        return live() && maintainMarket(now);
    }

    /** Durable schema-104 GE custody lifecycle; flows are claimed at Flow quantity level. */
    GeCustodyLedger exchange = new GeCustodyLedger();

    /**
     * Schema-106 pooled known-basis coverage; supporting ownership state, never a second ledger.
     */
    TrackedBasisLedger basis = new TrackedBasisLedger();

    /**
     * Feeds an offer transition to the custody lifecycles. Review handoffs (slot reuse, identity
     * replacement, unobserved terminal) and settlements completed by a held collect book once.
     */
    synchronized void noteGeOffer(OfferSlots.Transition transition, String name, long now) {
        for (var result : exchange.observeTransition(transition, name, now,
                        current == null ? "" : current.id()))
            bookCustody(result);
    }

    /**
     * Login/relog slot baseline. Resumes persisted custody lifecycles whose offer identity is
     * unchanged, baselines offers first seen already open without acquisition basis, and retires
     * claims made elsewhere silently. Only an observed local collection can settle new money.
     */
    synchronized void seedGeSlots(Map<Integer, OfferSlots.Snapshot> snapshots, long now) {
        for (var handoff : exchange.seedSlots(
                        snapshots, now, current == null ? "" : current.id()))
            bookCustody(handoff);
    }

    /** Receipt id → its first holding session, only while Market rows resolve settlements. */
    Map<String, Session> holders;

    /** Derived, read-only Market settlement context over the durable custody lifecycles. */
    synchronized List<MarketRows.Row> marketRows() {
        var records = exchange.records();
        if (records.isEmpty()) return emptyList();
        holders = new HashMap<>();
        for (var session : sessions())
            for (var transaction : session.transactions())
                if (transaction != null) holders.putIfAbsent(transaction.id(), session);
        try {
            return MarketRows.rows(records, this::transactionById, this);
        } finally {
            holders = null;
        }
    }

    /**
     * The session holding this booked transaction, or null when none is retained: Market rows
     * follow the session that holds their settlement (cross-Grind sales).
     */
    Session sessionHolding(String id) {
        if (holders != null) return holders.get(id);
        for (var session : sessions()) {
            if (findTransaction(session, id) != null) return session;
        }
        return null;
    }

    Session sessionById(String id) {
        for (var session : sessions()) {
            if (session != null && session.id().equals(id)) return session;
        }
        return null;
    }

    /** Effective correction-aware canonical settlement lookup for the derived Market projection. */
    Transaction transactionById(String id) {
        if (holders != null) return findTransaction(holders.get(id), id);
        for (var session : empty(id) ? Collections.<Session>emptyList() : sessions()) {
            var found = findTransaction(session, id);
            if (found != null) return found;
        }
        return null;
    }

    static Transaction findTransaction(Session session, String id) {
        if (session == null) return null;
        for (var transaction : session.transactions()) {
            if (transaction != null && id.equals(transaction.id())) return transaction;
        }
        return null;
    }

    /** Native per-slot collection evidence, kept in the same owner as custody. */
    synchronized void noteGeBox(int slot, net.runelite.api.Item[] items, IntUnaryOperator canon, long now) {
        for (var row : exchange.collectionBox(slot, items, canon, now,
                current == null ? "" : current.id(), now <= events.at(GE_COLLECT))) bookCustody(row);
    }

    /**
     * A bounded player Collect interaction with the Grand Exchange collection box. It only enables
     * exact custody collection correlation for the window; it never books anything and never owns a
     * movement by itself.
     */
    synchronized void noteGeCollect(long now) {
        long until = max(events.at(GE_COLLECT), now + 20000);
        events.put(GE_COLLECT, -1, 1, Integer.MAX_VALUE, null).at = until;
    }

    /**
     * Release held cash and coffer drain to their current owner before dropping transient
     * evidence; every pause, session change and profile install passes here first.
     */
    synchronized void clearCollect() {
        settleCoffer();
        events.drop(GE_COLLECT);
        exchange.releaseHeldCollect().forEach(this::bookCustody);
    }

    /** Canonical custody outcomes (review handoffs and retried settlements) are booked once. */
    void bookCustody(Transaction result) {
        if (result != null && current != null)
            // Counted custody settlements already consumed their reservation; their item legs
            // must never deplete known coverage again.
            add(result, result.counts());
    }

    /** Authoritative profile/analytics timezone for presentation date scopes. */
    synchronized ZoneId timeZone() {
        try {
            return of(zone);
        } catch (RuntimeException ex) {
            return ZoneId.systemDefault();
        }
    }

    synchronized void setChestVisible(boolean visible) {
        claims.setLootChestVisible(visible);
    }

    synchronized void observeKeyContainer(int key, Map<Integer, Long> contents) {
        claims.observeKeyContents(key, contents);
    }

    /** The exact price of one pending loot key's observed chest contents; 0 when unknown. */
    synchronized long pendingKeyValue(int key, long now) {
        return pricedContents(claims.observedContents(key), now);
    }

    /**
     * The exact price across every pending key's observed chest, each key type once (one opening
     * takes a chest's contents). 0 when no chest was observed or a part has no quote.
     */
    synchronized long pendingKeysValue(long now) {
        long total = 0;
        var seen = new HashSet<Integer>();
        for (var claim : claims.all()) {
            if (claim == null || !claim.isValid() || !seen.add(claim.itemOrKeyId)) continue;
            total = sum(total, pricedContents(claims.observedContents(claim.itemOrKeyId), now));
        }
        return total;
    }

    /**
     * A chest manifest priced exactly from its observed contents: every part needs a supported
     * quote, or nothing shows (owner 2026-10-14). The game's own display rounds to a million.
     */
    private long pricedContents(Map<Integer, Long> contents, long now) {
        if (empty(contents)) return 0;
        var flows = valuation.value(contents, now);
        // A component the valuator silently drops (an empty vial) fails the manifest closed.
        if (flows == null || flows.size() != contents.size()) return 0;
        long total = 0;
        for (Flow flow : flows) {
            if (flow.unitPrice <= 0) return 0;
            total = sum(total, flow.value);
        }
        return total;
    }

    /** A non-counted, ownership-neutral audit row: transfers, holds, restores and reclaims. */
    Transaction bookNeutral(long now, String note, String activity, List<Flow> flows, String reason,
            String encounter) {
        return bookTransaction(now, TransactionType.TRANSFER, TRANSFER, note, activity, false, flows,
                CONFIRMED, reason, encounter);
    }

    /** A session is open and not paused. */
    boolean live() {
        return current != null && !current.paused;
    }

    /**
     * Books a row; custody-owned rows already consumed their reservation and never deplete again.
     */
    void add(Transaction transaction, boolean owned) {
        if (transaction.original() == TRADE || transaction.original() == PK_DEATH_LOSS)
            for (var f : transaction.flows()) if (f.cost())
            take(raids.state.supplies, net.runelite.client.game.ItemVariationMapping.map(f.item),
                    multiply(abs(f.quantity), max(1, dose(f.name))));
        current.addTransaction(transaction, config.maxTransactionsPerSession(), owned);
        queueDose(current, transaction);
    }

    /** Death storage stays visible; unrelated neutral moves follow the audit preference. */
    void audit(Transaction row) {
        if (raids.find(raids.state.death) != null
                && (deathNote(row.note()) || row.note().equals(msg("death-reclaim-items")))) return;
        if (config.keepTransferAuditRows() || deathNote(row.note())
                        || row.note().equals(msg("death-reclaim-items"))) add(row, false);
    }

    /** Books the custody settlements that came due; true when any did. */
    boolean maintainMarket(long now) {
        var rows = exchange.maintenance(now, current.id());
        rows.forEach(booking -> add(booking, true));
        return !rows.isEmpty();
    }

    Transaction bookTransaction(long now, TransactionType type, Context context, String note, String activity,
            boolean counted, List<Flow> flows, Confidence confidence, String reason, String encounter) {
        return new Transaction(now, type, context, note, activity, counted, flows, confidence, reason,
                encounter);
    }

    /** A booked charge row: counted consumption with the current activity, then its action kind. */
    private Transaction bookCharge(long now, String note, Confidence confidence, String reason, List<Flow> flows,
            ActionKind kind, boolean estimate) {
        Transaction transaction = bookTransaction(now, CONSUMPTION, GENERIC, note,
                        fallback(current.activityHint(), "General"), true, flows, confidence, reason,
                        null);
        transaction.setAction(kind);
        // An estimated blowpipe dart lands by the target like a fired bolt; its scales are used up.
        transaction.setOnGround(estimate && flows.stream().anyMatch(Engine::groundDart));
        add(transaction, false);
        return transaction;
    }

    /**
     * Charges the game counted out exactly (uncharging Bryophyta's staff): a measured spend, kept
     * out of the estimate journal so a later Check never reshapes it (1.3.1).
     */
    synchronized void spent(String item, List<Flow> charges, long now) {
        if (live())
            bookCharge(now, msg("measured-charge") + item, CONFIRMED, msg("game-counted") + item + ".",
                    charges, CAST, false);
    }

    /** One chug from the chugging barrel: its doses as one Drank row titled by the barrel. */
    synchronized void chug(List<Flow> doses, long now) {
        if (live())
            bookCharge(now, "Chugging barrel", CONFIRMED, msg("drunk-chugging"), doses, DRINK,
                            false).setActionLabel(ActionLabel.of("Chugging barrel"));
    }

    /**
     * Book estimated component losses for one automatic charge use. The receipt counts immediately
     * at the captured prices with LIKELY confidence; a later measured Check reconciles it. Priced
     * components enter the transient journal so a Check can shrink them without repricing.
     */
    synchronized Transaction bookEstimatedCharge(String identity, String family, ActionKind kind,
            List<Flow> removed, long now) {
        String target = fallback(identity, null), label = fallback(family, null);
        if (!live() || target == null || label == null || empty(removed))
            return null;
        Transaction transaction = bookCharge(now, msg("estimated-charge") + label, Confidence.LIKELY, "Estimated",
                        removed, kind == null ? CAST : kind, true);
        String id = current.id();
        for (Flow flow : transaction.flows()) {
            if (flow.cost() && flow.unitPrice > 0)
                estimate.record(id, target, transaction.id(), flow.item,
                        abs(flow.quantity));
        }
        if (transaction.onGround()) rememberDrop(transaction, Engine::groundDart);
        return transaction;
    }

    /**
     * Consume one measured component decrease against this target's pending estimates. An
     * uncorrected straddling receipt is shrunk to the measured quantity at its captured price and
     * confirmed; manually corrected receipts are only consumed, never edited. Returns the covered
     * quantity.
     */
    private long reconcileEstimate(String sessionId, String target, int id, long measured) {
        long left = measured;
        for (var receipt : estimate.receipts(sessionId, target, id)) {
            long remaining = receipt.remaining;
            if (remaining <= 0) continue;
            var estimate = current.findTransaction(receipt.transaction);
            boolean frozen = estimate == null || estimate.correction() != AUTO;
            if (left < remaining && !frozen) {
                // Shrink the overestimate through the session's announced automatic-evidence path.
                current.settleEstimate(estimate, id, remaining - left, "Confirmed");
                this.estimate.consume(receipt, remaining);
                left = 0;
                continue;
            }
            long take = min(left, remaining);
            this.estimate.consume(receipt, take);
            left -= take;
            if (!frozen && take >= remaining) {
                estimate.confidence = CONFIRMED;
                estimate.setExplanation("Confirmed");
            }
            if (left <= 0) break;
        }
        // The measured window is closed here: automatic estimates left over are overestimates
        // and are removed, while manually corrected rows are preserved for future credit.
        for (var receipt : estimate.receipts(sessionId, target, id))
            forfeit(receipt);
        return measured - left;
    }

    /** Removes one overestimated automatic receipt through the session's announced path. */
    private void forfeit(ChargeEstimateJournal.Receipt receipt) {
        if (receipt == null || receipt.remaining <= 0 || current == null) return;
        var estimate = current.findTransaction(receipt.transaction);
        if (estimate == null || estimate.correction() != AUTO) return;
        current.settleEstimate(estimate, receipt.item, receipt.remaining, "Check reconciled");
        this.estimate.consume(receipt, receipt.remaining);
    }

    /**
     * Book exact component losses from a compatible measured Check difference, first reconciling
     * any pending estimates for that target; returns the booked row, or null (zero cost) when the
     * measurement adds nothing beyond what estimates already booked. Generic inventory losses, menu
     * intent and animations cannot authorize this.
     */
    synchronized Transaction bookChargeSpend(ChargeDelta measuredDelta, String name, List<Flow> removed,
            long now, String identity) {
        if (!live() || measuredDelta == null || measuredDelta.exactCost(removed) <= 0)
            return null;
        var booked = removed;
        String target = fallback(identity, "");
        if (!target.isEmpty()) {
            var excess = new ArrayList<Flow>();
            String id = current.id();
            for (Flow flow : removed) {
                long measured = abs(flow.quantity);
                long extra = measured - reconcileEstimate(id, target, flow.item, measured)
                                - estimate.evictedOverlap(id, target, flow.item);
                if (extra <= 0) continue;
                excess.add(extra == measured ? flow : new Flow(flow.item, flow.name, -extra, flow.unitPrice,
                                        -multiply(extra, flow.unitPrice), flow.source(),
                                        flow.priceCapturedAtEpochMillis));
            }
            if (excess.isEmpty()) return null;
            booked = excess;
        }
        // The spend belongs to what the player is doing, not to the weapon: Top activities
        // must never grow a "Toxic blowpipe" row. The weapon stays in the note.
        return bookCharge(now, msg("measured-charge") + fallback(name, "Measured charges"),
                        CONFIRMED, msg("measured-check") + measuredDelta.variant.getDisplayName() + ".",
                        booked, measuredDelta.variant == ChargedItem.BLOWPIPE ? FIRE : CAST, false);
    }

    /** Book a death reclaim fee as a plain counted CONSUMPTION cost under "Death reclaim". */
    synchronized Transaction bookDeathFee(long fee, String why, long now) {
        String label = why == null ? msg("grave-fee") : why;
        var row = bookFee(fee, label, "Death reclaim", now);
        raids.fee(row);
        return row;
    }

    /** A dispenser award the chat named, waiting for its settle; empty when none. */
    Map<Integer, Long> reward = new HashMap<>();

    long bagRewardUntil;

    /** Confirmed additions to the looting bag since the client last showed its contents. */
    Map<Integer, Long> bag = new HashMap<>();

    Map<Integer, Long> contents = new HashMap<>();

    synchronized void rememberBag(Map<Integer, Long> contents) {
        var valid = positive(contents);
        valid.entrySet().removeIf(e -> e.getKey() <= 0 || e.getValue() > Integer.MAX_VALUE);
        if (valid.size() > 28) valid.clear();
        if (!this.contents.equals(valid)) {
            this.contents = valid;
            changed();
        }
    }

    synchronized Map<Integer, Long> bagStock() {
        var stock = new HashMap<>(contents);
        bag.forEach((id, quantity) -> stock.merge(id, quantity, SafeMath::sum));
        return stock;
    }

    /**
     * Wilderness Agility pays its dispenser into an open looting bag, which the client never shows
     * (owner live 2026-10-16). The chat names the whole award, so what the next settle does not
     * bring into the pack went into the bag and books as loot. The bag only ever empties into the
     * bank, so nothing books twice.
     */
    synchronized void noteBagReward(Map<Integer, Long> awarded, long now) {
        reward = new HashMap<>(awarded);
        bagRewardUntil = now + 6000;
    }

    Transaction settleBagReward(List<Flow> flows, long now) {
        if (reward.isEmpty()) return null;
        var bag = new HashMap<>(reward);
        reward.clear();
        if (now > bagRewardUntil || !live()) return null;
        for (Flow flow : flows)
            if (flow.gain()) bag.computeIfPresent(flow.item, (id, q) -> q - flow.quantity);
        bag.values().removeIf(quantity -> quantity <= 0);
        if (bag.isEmpty()) return null;
        bag.forEach((id, quantity) -> this.bag.merge(id, quantity, Long::sum));
        Transaction loot = bookTransaction(now, TransactionType.LOOT, LOOT, msg("bag-reward"),
                        current.activityHint(), true, valuation.value(bag), Confidence.LIKELY,
                        msg("bag-proof"), null);
        add(loot, false);
        return loot;
    }

    /** The Blast Furnace coffer's drain while you work it (owner 2026-10-16, after Coin Flow 1.9). */
    synchronized Transaction bookCofferDrain(long gp, long now) {
        return bookFee(gp, msg("bf-coffer"), "Smithing", now);
    }

    /** The Blast Furnace coffer's last balance (-1 until read) and its tick; unbooked drain, when and whose. */
    int coffer = -1, cofferTick;

    long drain, drainAt;
    String drainer;

    /**
     * The Blast Furnace coffer drains 72,000 coins an hour, 12 a tick, while you work it (OSRS
     * Wiki). Deposits and withdrawals stay neutral: a drop counts as drain only when it is no more
     * than 12 gp a tick since the last change; anything faster is a withdrawal. Only an active,
     * ingesting session accrues drain; other readings just move the balance. Drain books in 1,200
     * gp batches (owner 2026-10-16, after Coin Flow 1.9) and before its session pauses, ends or saves.
     */
    synchronized void coffer(int gp, int tick, boolean eligible, long now) {
        long gone = cofferDrain(coffer, gp, tick - cofferTick);
        coffer = gp;
        cofferTick = tick;
        if (!eligible || !live() || gone == 0 || drain > 0 && !current.id().equals(drainer)) return;
        drainer = current.id();
        drain += gone;
        drainAt = now;
        if (drain >= 1200) settleCoffer();
    }

    /**
     * The drain in one coffer change: a drop the coffer could drain in the ticks elapsed (plus one);
     * a bigger drop is a withdrawal, and its few coins of drain are left out.
     */
    static long cofferDrain(int before, int after, int ticks) {
        long drop = before < 0 ? 0 : before - after;
        return drop > 0 && drop <= 12 * (max(1, ticks) + 1) ? drop : 0;
    }

    /** Books unbooked drain to the session it came from; it stays pending until that succeeds. */
    void settleCoffer() {
        if (live() && current.id().equals(drainer) && bookCofferDrain(drain, drainAt) != null)
            drain = 0;
    }

    /** A bank-paid fee as a plain counted CONSUMPTION cost under its own row name. */
    private Transaction bookFee(long fee, String label, String activity, long now) {
        if (fee <= 0 || !live()) return null;
        var feeFlow = new Flow(COINS, "Coins", -fee, 1, -fee, FACE_VALUE, now);
        Transaction transaction = bookTransaction(now, CONSUMPTION, GENERIC, label, activity, true,
                        singletonList(feeFlow), CONFIRMED, label, null);
        add(transaction, false);
        return transaction;
    }

    synchronized boolean correctTransaction(String id, Correction decision, String reason) {
        return current != null && current.correctTransaction(id, decision, reason);
    }

    /** Unresolved owner decisions in the active session, newest first, as review rows. */
    synchronized List<ReviewRow> getReviewRows(long now) {
        var rows = new ArrayList<ReviewRow>();
        if (current == null) return rows;
        for (var transaction : current.transactions()) {
            if (needsReview(transaction))
                rows.add(reviewRow(current, transaction, now));
        }
        rows.sort(Comparator.comparingLong((ReviewRow itemDataValue) -> itemDataValue.at).reversed());
        return rows;
    }

    /**
     * Current world/economy permission for ordinary automatic market quotes (presentation reads
     * it).
     */
    boolean automaticMarketQuotesAvailable() {
        return valuation.automaticMarketQuotesAvailable();
    }

    /** Domain revision: every canonical mutation advances it; derived previews bind to it. */
    synchronized long revision() {
        return revision;
    }

    /**
     * Exact rows and prospective personal-Net delta a Decide all would apply, computed from the
     * same canonical transaction ids that {@link #applyDecision} mutates.
     */
    synchronized DecisionPreview previewDecision(Decision decision, Predicate<ReviewRow> scope, long now) {
        var selectedIds = new ArrayList<String>();
        long net = 0;
        if (current != null && decision != null && scope != null) {
            var correction = decision.correction;
            for (var transaction : current.transactions()) {
                if (!needsReview(transaction)
                        || !scope.test(reviewRow(current, transaction, now)))
                    continue;
                selectedIds.add(transaction.id());
                net = sum(net, transaction.netUnder(correction)
                                        - (transaction.counts() ? transaction.net() : 0));
            }
        }
        return new DecisionPreview(decision, selectedIds, net, revision());
    }

    /**
     * Applies a preview as one atomic batch with one undo. Returns {@code -1} without touching
     * anything when the domain revision moved since the preview (stale row ids never apply).
     */
    synchronized int applyDecision(DecisionPreview preview, long now) {
        if (preview == null || current == null || preview.transactionIds.isEmpty()) return 0;
        if (preview.revision != revision()) return -1;
        return current.correctAll(preview.transactionIds, preview.decision.correction,
                        "Review decision: " + preview.decision.name());
    }

    /**
     * Revision-bound Decide all impact: canonical ids, row count and exact prospective Net delta.
     */
    static class DecisionPreview {
        Decision decision;
        List<String> transactionIds;
        long netDelta, revision;

        DecisionPreview(Decision decision, List<String> ids, long net, long revision) {
            this.decision = decision;
            transactionIds = frozenList(ids);
            netDelta = net;
            this.revision = revision;
        }

        int rowCount() {
            return transactionIds.size();
        }
    }

    static ReviewRow reviewRow(Session session, Transaction transaction, long now) {
        var items = new ArrayList<Item>();
        for (Flow flow : transaction.flows())
            items.add(new Item(flow.item, flow.name, flow.quantity, flow.value));
        if (items.isEmpty())
            items.add(new Item(-1, transaction.note(), 0, transaction.autoNet()));
        String why = transaction.explanation();
        if (blank(why)) why = msg("uncertain-transaction");
        long timestamp = transaction.timestamp, age = now > timestamp ? now - timestamp : 0;
        var decisions = EnumSet.allOf(Decision.class);
        return new ReviewRow(transaction.id(), session.id(), timestamp, age, items, transaction.autoNet(), why,
                decisions);
    }

    synchronized boolean splitItem(String id, int identifier, long keep, String note) {
        return current != null && current.splitItem(id, identifier, keep, note);
    }

    synchronized boolean undoCorrection() {
        return current != null && current.undoCorrection();
    }

    synchronized Transaction undoTransaction() {
        return current == null ? null : current.undoTransaction();
    }

    synchronized Transaction restoreUndo() {
        return current == null ? null : current.restoreUndo();
    }

    synchronized void recordAction(String activity) {
        if (live()) current.recordAction(activity, now());
    }

    /** Additional source receipts are published after canonical booking has completed. */
    synchronized Transaction processIfDirty(ContainerSnapshot current, long now,
            Consumer<Transaction> additional) {
        events.hold = dirty || pending != null
                        || current != null && baseline != null && !current.equals(baseline);
        events.tick(events.now + 1);
        if (live()) retryDose(now);
        settleGround();
        // Inventory alone must not create a tracker when automatic startup is
        // disabled or when no session has been started yet.
        if (this.current == null) return null;
        // adj is fed by the client's per-game-tick snapshot path.
        // Keep active-time accrual on that event path instead of analytics reads.
        // This window ages once per inventory/game-tick sample, even when the
        // inventory remains dirty across several stabilization samples.
        if (captureTicks > 0 && --captureTicks <= 0) {
            captureTicks = 0;
            capture = false;
            storedBaseline = null;
        }
        // Death and reclaim windows are real game-tick lifecycles. Advance them before
        // any dirty/stabilization early return so inventory churn cannot pause expiry.
        int retrieval = RaidHistory.kind(raids.state.death);
        if (retrieval > 0 && retrieval < 3) death.age = 0;
        String expired = death.tick(events.hold);
        if (expired != null) {
            Transaction expiry = bookNeutral(now, msg("reclaim-expired"), "Death reclaim", emptyList(),
                            expired, null);
            add(expiry, false);
            return expiry;
        }
        if (this.current.paused) {
            // An inventory gain during an Idle pause is the action that ended it (a
            // pickpocket's loot can settle before the activity detector fires): resume and
            // book it. Other pauses stay aligned so Resume never inherits pause-window
            // churn as catch-up profit (owner 2026-09-29).
            var woke = current == null || baseline == null ? null : current.diff(baseline);
            boolean idleResume = this.current.pauseReason() == IDLE && woke != null && woke.values().stream()
                                    .anyMatch(value -> value != null && value > 0);
            if (!idleResume) {
                // A death wipe while paused still moves gear to the grave: remember it so its
                // reclaim stays neutral.
                if (death.wipePending() && woke != null) death.removed(valuation.value(woke, now));
                // Keep the baseline aligned while paused so Resume never treats
                // pause-window inventory churn as catch-up profit.
                baseline = current;
                if (capture) storedBaseline = current;
                warming = false;
                clearPending();
                events.drop(CONSUME, SPENT_CONSUME);
                events.drop(DROP_CLICK);
                clearTransfer();
                return advanceContext();
            }
            this.current.resume(now);
        }
        if (warming) {
            observePriming(current);
            return null;
        }
        if (baseline == null) {
            baseline = current;
            if (capture) storedBaseline = current;
            clearPending();
            return null;
        }
        if (!dirty && current.equals(baseline)) return advanceContext();
        // Container callbacks are expected, but comparing the actual snapshot
        // makes the engine resilient to a missed or reordered callback.
        if (!dirty) {
            dirty = true;
            captureContext();
        }
        var cast = events.peek(CAST_SPELL);
        var spell = cast == null ? null : cast.label;
        var previous = this.cast == null ? null : this.cast.label;
        if (pending != null && !pending.equals(current) && cast != null && cast != this.cast
                && !Objects.equals(previous == null ? null : previous.value,
                        spell == null ? null : spell.value)) {
            // The previous complete tick belongs to the earlier spell. Settle it before
            // accepting the new cast's snapshot, even inside the stabilization window.
            var prior = settle(pending, now, additional);
            clearContext();
            if (prior != null && additional != null) additional.accept(prior);
            dirty = true;
            captureContext();
        }
        if (pending == null || !pending.equals(current)) {
            pending = current;
            if (cast != null) this.cast = cast;
            stableTicks = 0;
            captureContext();
            return null;
        }
        stableTicks++;
        if (stableTicks < max(0, config.stabilizationTicks())) return null;
        var settled = settle(pending, now, additional);
        clearContext();
        return settled;
    }

    /**
     * One stabilized inventory change moving through the settle stages, with the claim it settles
     * under.
     */
    static class Settle extends Clue {
        long now;
        ContainerSnapshot snapshot;
        List<Flow> flows;

        /**
         * A local death with no confident PvM/PK classification: routine spends stay unlabelled.
         */
        boolean suppress;

        boolean death, wipe, ambiguousCoins;
        Map<Integer, Long> lost = emptyMap(), keys = emptyMap();
        String claim;
        Transaction keyAudit;
        LootMatch loot = LootMatch.NONE;

        /** Source-backed quantities, kept even if the loot label is later refused. */
        Map<Integer, Long> lootItems = emptyMap();

        boolean destroy, pricingReview, combine, disassemble, drop, used, asTransfer;
        ActionKind intent;
        ActionLabel label, spell;
        int usedItem = -1, dropItem = -1;
        String activity;

        Settle(Clue claim, ContainerSnapshot committed, long now) {
            offer(claim.context, claim.note, claim.expected, claim.fight, true);
            transfer = claim.transfer;
            hard = claim.hard;
            snapshot = committed;
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
            Consumer<Transaction> additional) {
        var batch = commit(committed, now);
        if (batch == null) return null;
        // Published like any extra booking, so HUD+'s tray shows the bag's rewards too.
        var bag = settleBagReward(batch.flows, now);
        if (bag != null && additional != null) additional.accept(bag);
        // Minigame-only items (a loaned loadout, table supplies) cannot legally exist outside
        // their minigame: the grant and the exit strip vanish before any settle stage sees them, so
        // they never book, reach Review or pollute the death-wipe whitelist (Coinflow
        // MinigameQuarantine evidence, owner 2026-10-12). commit()'s swap filter runs earlier and
        // cannot pair these ids; the drink normalization runs later, so a loaned dose sip can never
        // be rewritten into real ids.
        batch.flows.removeIf(flow -> MinigameItems.isMinigameOnly(flow.item));
        raids.supplies(batch.flows, false,
                batch.context == LOOT || batch.context == PK_LOOT ? batch.expected : emptyMap());
        // An essence pouch's Empty/Fill carries custody of the essence it moves, never the runes a
        // craft produces in the same stabilized window: the essence stays neutral and the residual
        // classifies normally (owner 2026-10-13).
        if ((batch.transfer || batch.hard) && batch.note != null && batch.note.contains("essence_pouch")) {
            batch.flows.removeIf(flow -> flow.name != null && lower(flow.name).contains("essence"));
            batch.clear();
        }
        settleDeathWipe(batch);
        settleKeyTokens(batch);
        if (batch.flows.isEmpty()) return batch.keyAudit;
        var held = settleHeldItems(batch);
        if (batch.flows.isEmpty()) return held;
        var returned = settleReturns(batch);
        if (batch.flows.stream().allMatch(flow -> flow.value == 0
                                                && flow.source() != UNPRICED
                                                // A worthless item still moved: it books at 0
                                                // with a dash, never vanishes (owner 2026-10-16).
                                                && flow.source() != NO_VALUE)
                // Owner 2026-10-09: a clue scroll, scroll box or casket picked up is worth nothing
                // yet but is a
                // drop, so it books and HUD+ shows it; opening, stepping or moving them still books
                // nothing.
                && !(batch.flows.stream().allMatch(Flow::gain) && all(batch.flows, 0,
                                name -> pattern("clue").matcher(name).matches())))
            // A change with no value and no unpriced item is nothing to account for; a settled
            // claim still closed the deferred key audit row above. No value threshold exists.
            return first(returned, batch.keyAudit);
        var restore = settleZoneRestore(batch.flows, batch.lootItems, now);
        if (restore != null) {
            audit(restore);
            if (batch.flows.isEmpty()) return restore;
            // Only the matched entry items are neutral. Classify residual flows using
            // their actual consumption/loot evidence rather than the old region latch.
            batch.clear();
            clearContext();
        }
        for (var neutral : new Transaction[] {
                    servant(batch.flows, now), wear(batch.flows, now, batch.lootItems), cannon(batch.flows, now)
                }) {
            if (neutral != null) {
                audit(neutral);
                if (batch.flows.isEmpty()) return neutral;
            }
        }
        var gear = wornOut(batch.flows, now);
        if (gear != null && batch.flows.isEmpty()) return gear;
        var sources = bookLootSources(batch);
        // Partial source matches classify the whole remaining settle as loot only
        // when the transfer partition consumed the unmatched quantities. Without
        // that partition, an oversized same-id stack (or unrelated extra gain)
        // must follow generic gain classification rather than inherit the loot tag.
        if (batch.loot.matched && !matchesExpectedLoot(batch.flows, batch.loot.items))
            batch.loot = LootMatch.NONE;
        var dropSlice = settleDropSlice(batch);
        var consumeSlice = settleConsumeSlice(batch);
        settleActions(batch);
        if (dropSlice != null && batch.flows.isEmpty() && sources.isEmpty()) return dropSlice;
        if (consumeSlice != null && batch.flows.isEmpty() && sources.isEmpty()) return consumeSlice;
        Transaction cast = settleSpend(batch, ActionReader::isRune, CAST);
        Transaction fired = settleSpend(batch, ActionReader::isAmmo, FIRE);
        if (batch.used && !batch.drop && !batch.destroy && !batch.hard && !batch.combine
                && !batch.suppress && resolve(
                                batch.intent, batch.flows, CONSUMPTION, batch.activity) == DRINK) {
            var normalized = valuation.normalizeConsumedFlows(batch.flows, DRINK, batch.now);
            batch.pricingReview = normalized == null;
            if (normalized != null) batch.flows = normalized;
        }
        if (cast == null) cast = settleSpell(batch);
        Transaction result;
        if (!sources.isEmpty() && batch.flows.isEmpty())
            result = sources.get(sources.size() - 1); else {
            var custody = settleCustody(batch);
            result = custody != null ? custody : book(batch);
        }
        if (additional != null) {
            for (var row : sources) {
                if (row != result) additional.accept(row);
            }
            if (cast != null) additional.accept(cast);
            if (fired != null) additional.accept(fired);
            if (dropSlice != null) additional.accept(dropSlice);
            if (consumeSlice != null) additional.accept(consumeSlice);
        }
        return result;
    }

    /**
     * Routine rune/ammo spends keep their own supply receipt beside a pickup, drink or each other.
     * Execution evidence names partial rune sets too. Utility spells retain their product and input legs.
     */
    Transaction settleSpend(Settle batch, Predicate<String> names, ActionKind kind) {
        if (batch.hard || batch.soft() || batch.death || batch.drop || batch.destroy || batch.asTransfer
                || events.peek(DROP_CLICK) != null
                || batch.context == MARKET || batch.context == PRODUCTION || batch.context == TRANSFER
                || kind == FIRE && batch.intent == CAST
                || kind == CAST && (batch.label != null
                                && !product(named(batch.label.value)).isEmpty()
                        || batch.intent == CAST && batch.flows.stream().anyMatch(flow -> flow.cost()
                                        && !isRune(flow.name) && !isAmmo(flow.name))))
            return null;
        var lost = new ArrayList<Flow>();
        for (Flow flow : batch.flows) {
            if (flow.cost() && names.test(flow.name)) lost.add(flow);
        }
        if (lost.isEmpty() || lost.size() == batch.flows.size() || kind == CAST && batch.spell == null
                        && batch.intent != CAST
                        && lost.stream().allMatch(flow -> elemental(flow.name))) return null;
        var slice = new Settle(new Clue(), batch.snapshot, batch.now);
        slice.flows = lost;
        slice.activity = batch.activity;
        slice.intent = kind;
        slice.label = batch.intent == kind ? batch.label : null;
        slice.spell = kind == CAST ? batch.spell : null;
        batch.flows = new ArrayList<>(batch.flows);
        batch.flows.removeAll(lost);
        if (batch.intent == kind) {
            batch.used = false;
            batch.intent = null;
            batch.label = null;
        }
        return book(slice);
    }

    /**
     * A spell with a product keeps only its own legs; a pickup that settled with it books on its
     * own (owner live 2026-10-16: 39 rune darts rode a Bones to Peaches tablet).
     */
    Transaction settleSpell(Settle batch) {
        Set<Integer> product = batch.used && batch.label != null
                        ? product(named(batch.label.value)) : emptySet();
        var legs = new ArrayList<Flow>();
        for (Flow flow : batch.flows)
            if (flow.cost() || product.contains(flow.item)) legs.add(flow);
        if (product.isEmpty() || legs.size() == batch.flows.size()) return null;
        var slice = new Settle(new Clue(), batch.snapshot, batch.now);
        slice.flows = legs;
        slice.activity = batch.activity;
        slice.intent = batch.intent;
        slice.label = batch.label;
        slice.used = true;
        batch.flows = new ArrayList<>(batch.flows);
        batch.flows.removeAll(legs);
        batch.used = false;
        batch.intent = null;
        batch.label = null;
        return book(slice);
    }

    /**
     * A confirmed drop owns its item in a mixed settle (owner 1.3.4): dropping plant pots while a
     * calquat harvest lands must book the pot loss as its own Dropped row instead of turning the
     * change into Review. The drop's losses move to their own row; every other flow must be a
     * gain, and a second loss, a pickup back of the same item, an armed consume intent, bank
     * evidence or a Market/PK context cancels the split, leaving the change to settle as before.
     */
    Transaction settleDropSlice(Settle batch) {
        if (batch.hard || batch.soft() || batch.transfer) return null;
        if (batch.context == TRANSFER || batch.context == MARKET || batch.context == PK_DEATH
                || batch.context == PK_LOOT)
            return null;
        if (events.peek(DROP_CLICK) == null || intent() != null) return null;
        int id = events.item(DROP_CLICK);
        if (id <= 0) return null;
        var lost = new ArrayList<Flow>();
        boolean pickedBack = false;
        for (Flow flow : batch.flows) {
            if (flow.item == id) {
                if (flow.quantity > 0) pickedBack = true;
                else if (flow.quantity < 0) lost.add(flow);
            } else if (flow.quantity <= 0)
                // Any second loss (or a zero flow) makes the change ambiguous again.
                return null;
        }
        if (lost.isEmpty() || pickedBack) return null;
        var slice = new Settle(new Clue(), batch.snapshot, batch.now);
        slice.context = batch.context;
        slice.fight = batch.fight;
        slice.activity = batch.activity;
        slice.flows = lost;
        batch.flows = new ArrayList<>(batch.flows);
        batch.flows.removeAll(lost);
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
    Transaction settleConsumeSlice(Settle batch) {
        var intent = intent();
        if (intent == null || intent.item < 0 || intent.flag) return null;
        if (batch.death || batch.suppress) return null;
        if (batch.context == PK_DEATH) return null;
        int id = intent.item;
        var bites = new ArrayList<Flow>();
        boolean toppedUp = false;
        for (Flow flow : batch.flows) {
            if (flow.item != id) continue;
            if (flow.quantity > 0) toppedUp = true;
            else if (flow.quantity < 0) bites.add(flow);
        }
        if (bites.isEmpty()) return null;
        // A pure bite (nothing else in the change) books on its own whenever the intent proved
        // it — including inside a live bank window, where the transfer context would otherwise
        // swallow it. A bite mixed with other items only splits when a same-item top-up is there
        // to keep; any other mixed window keeps its existing shape.
        boolean pure = bites.size() == batch.flows.size();
        if (!pure && !toppedUp) return null;
        // The bite is a player action of its own, never part of the transfer or loot batch it
        // landed with: a fresh generic slice books it as the counted Supplies row.
        var slice = new Settle(new Clue(), batch.snapshot, batch.now);
        slice.activity = batch.activity;
        slice.intent = intent.action;
        slice.label = intent.label;
        slice.used = true;
        slice.flows = bites;
        batch.flows = new ArrayList<>(batch.flows);
        batch.flows.removeAll(bites);
        // The bite owns the intent: take it so no later flow wears its verb or spell label.
        events.take(CONSUME, intent.item, 1, e -> true);
        return book(slice);
    }

    /** Different loot owners share a measured snapshot, never a source label or encounter. */
    List<Transaction> bookLootSources(Settle batch) {
        if (batch.loot.sources.size() <= 1) return emptyList();
        var rows = new ArrayList<Transaction>();
        for (var source : batch.loot.sources) {
            var remaining = new HashMap<>(source.items);
            List<Flow> parts = claim(batch.flows, flow -> flow.quantity > 0, (flow, quantity) -> {
                                long take = min(quantity, remaining.getOrDefault(flow.item, 0L));
                                remaining.computeIfPresent(flow.item, (id, wanted) -> wanted - take);
                                return take;
                            });
            if (parts.isEmpty()) continue;
            var slice = new Settle(new Clue(), batch.snapshot, batch.now);
            slice.offer(source.context, source.note, source.items, source.fight, true);
            slice.flows = parts;
            slice.loot = source;
            slice.lootItems = source.items;
            slice.activity = source.activityName;
            slice.claim = rows.isEmpty() ? batch.claim : null;
            rows.add(book(slice));
        }
        batch.loot = LootMatch.NONE;
        batch.lootItems = emptyMap();
        batch.claim = null;
        if (batch.context == LOOT || batch.context == PK_LOOT) batch.generic();
        return rows;
    }

    /** Commits the stabilized snapshot: its measured deltas, valued, under the pending claim. */
    Settle commit(ContainerSnapshot committed, long now) {
        var batch = new Settle(pending(), committed, now);
        batch.spell = cast == null ? null : cast.label;
        batch.suppress = UNCLASSIFIED_LOCAL_DEATH_NOTE.equals(batch.note);
        batch.death = batch.suppress || batch.context == PK_DEATH;
        if (batch.suppress)
            // Keep routine rune/ammo spends distinguishable from local death losses
            // without exposing an internal marker in the transaction note.
            batch.note = "";
        var changes = committed.diff(baseline);
        baseline = committed;
        invalidateChargeBaselines(changes);
        if (capture && storedBaseline != null) {
            if (batch.context == TRANSFER && batch.note != null
                    && batch.note.startsWith(msg("zone-restored")))
                recordZoneEntry(committed.diff(storedBaseline));
            storedBaseline = committed;
        }
        clearPending();
        if (changes.isEmpty())
            // Same-tick acquire+consume (pickup then bury) nets to zero vs the prior
            // baseline. Recover a named consume when invent qty fell since intent arm.
            changes = consumeDeltas(committed);
        batch.flows = changes.isEmpty() ? new ArrayList<>() : new ArrayList<>(valuation.value(changes, now));
        if (batch.context == GENERIC && !batch.transfer && !batch.hard)
            batch.flows = withoutSwaps(batch.flows);
        // The split rewrites settle.flows only; nothing after commit() re-reads deltas, so the two
        // structures may disagree by design (review 2026-10-12).
        splitToppedUpConsume(batch, changes, committed, now);
        return batch.flows.isEmpty() ? null : batch;
    }

    /**
     * A named consume whose stack was topped up in the same window (a bank withdrawal then an
     * immediate bite) nets positive and hides the bite. Split the stack's net gain into its gross
     * acquisition and the shortfall since the intent was armed, so the named consume keeps its own
     * cost flow and books Ate/Buried instead of vanishing into the transfer (owner 2026-10-12).
     */
    void splitToppedUpConsume(Settle batch, Map<Integer, Long> changes, ContainerSnapshot committed, long now) {
        if (intent() == null || intent().item < 0 || intent().held < 0) return;
        int id = intent().item;
        Long net = changes.get(id);
        if (net == null || net <= 0) return;
        long lost = intent().held - committed.quantity(id);
        if (lost <= 0 || lost > net) return;
        for (int index = 0; index < batch.flows.size(); index++) {
            Flow flow = batch.flows.get(index);
            if (flow.item != id || flow.quantity <= 0) continue;
            long gross = flow.quantity + lost;
            batch.flows.set(index, new Flow(id, flow.name, gross, flow.unitPrice,
                            multiply(gross, flow.unitPrice), flow.source(),
                            flow.priceCapturedAtEpochMillis));
            break;
        }
        batch.flows.addAll(valuation.value(singletonMap(id, -lost), now));
    }

    /** A measured death wipe: stacks held at death that left INV stay ownership-neutral. */
    void settleDeathWipe(Settle batch) {
        boolean candidate = death.wipePending() && hasLoss(batch.flows);
        boolean allowed = candidate && (batch.context == TRANSFER && deathNote(batch.note)
                                || batch.context == PK_DEATH && !batch.hard
                                || batch.context == GENERIC && !batch.transfer && !batch.hard);
        if (candidate && !allowed)
            // A stronger market, transfer, production or loot context owns this change.
            death.cancelWipe();
        Map<Integer, Long> losses =
                allowed ? actionLosses(batch.flows, batch.hard) : emptyMap();
        batch.lost = allowed ? death.removed(
                                without(batch.flows, losses::containsKey, false)) : emptyMap();
        if (!losses.isEmpty())
            // Consume action-attributed quantities from the potential wipe whitelist,
            // but leave unrelated held-at-death ids eligible in the same settle.
            death.excludeActionLosses(losses);
        batch.wipe = !batch.lost.isEmpty();
        batch.ambiguousCoins = batch.wipe && death.armed()
                        && hasLoss(batch.flows, id -> id == COINS)
                        && !batch.lost.containsKey(COINS);
        batch.death |= batch.wipe;
        if (batch.context == TRANSFER && deathNote(batch.note)
                && (!batch.wipe || batch.ambiguousCoins)) {
            // The short note is not ownership evidence. Without a measured whitelist
            // match, let the remaining change follow its actual action/classification.
            batch.clear();
            clearTransfer();
        }
    }

    /**
     * Key tokens are provenance, not loot value: a credible pickup becomes a separate non-counted
     * audit row, and only later contents withdrawals contribute ordinary valued flows.
     */
    void settleKeyTokens(Settle batch) {
        batch.keys = losses(batch.flows, id -> forKey(id) != null);
        if (batch.death) {
            losses(batch.flows, KeyChestCatalogue::isDeferredKey).forEach(claims::consume);
            claims.cancelChest();
        }
        var lootKeys = gains(batch.flows, KeyClaims::lootKey);
        Transaction keyAudit = null;
        if (!lootKeys.isEmpty()) {
            boolean pickup = credibleKeyPickup(batch, batch.flows, lootKeys);
            batch.flows = without(batch.flows, KeyClaims::lootKey, true);
            batch.expected = withoutItems(batch.expected, KeyClaims::lootKey);
            keyAudit = pickup ? recordKeyAudit(lootKeys, batch.fight, batch.now) : null;
        }
        var claimKeys = gains(batch.flows, KeyChestCatalogue::isDeferredKey);
        boolean claimPickup = !claimKeys.isEmpty() && credibleKeyPickup(batch, batch.flows, claimKeys);
        batch.flows = without(batch.flows, KeyChestCatalogue::isDeferredKey, false);
        batch.expected = withoutItems(batch.expected, KeyChestCatalogue::isDeferredKey);
        var claimAudit = claimPickup && !batch.death ? recordClaimAudits(claimKeys, batch.now) : null;
        batch.keyAudit = claimAudit != null ? claimAudit : keyAudit;
        if (incoming(batch.flows, ANY).isEmpty())
            // The click plus measured key removal may settle a tick before the
            // chest's contents enter INV. Keep only that narrow causal evidence.
            claims.matchChest(batch.keys, emptyMap(), null);
    }

    /**
     * Held items: keys lost on death, the measured death wipe, key-token losses and charge loads.
     */
    Transaction settleHeldItems(Settle batch) {
        if (batch.death || claims.settlingDeath())
            claims.recordDeathLosses(batch.flows, KeyClaims.keys(batch.snapshot.items));
        Transaction wipe = batch.lost.isEmpty() ? null
                        : deathWipeTransfer(batch.flows, batch.lost, batch.now);
        if (batch.wipe && batch.context != PK_DEATH && (wipe != null || deathNote(batch.note))) {
            // The measured death-owned quantities have their own neutral receipt. Any
            // residual item changes in this settle must follow their own evidence.
            batch.clear();
            clearTransfer();
        }
        // Key tokens are deferred provenance on both sides of the lifecycle: their
        // inventory loss is never a counted cost. Preserve the measured quantity for
        // death annotation, but exclude the token from transaction maths. A same-ID
        // loss in a mixed claim settle has no source identity, so it never reduces the
        // manifest key quantity on that evidence alone.
        batch.flows.removeIf(flow -> flow.cost() && KeyClaims.lootKey(flow.item));
        // Do not let this narrow menu intent compete with stronger bank/trade/
        // transfer evidence or the loot-key lifecycle. A stale load click expires
        // once a stronger ownership context is already active.
        if (batch.hard || batch.context == TRANSFER || batch.context == MARKET)
            events.drop(LOAD_TARGET);
        var load = loadTarget(batch.flows);
        Transaction loadReview = null;
        if (!load.unknown.isEmpty()) {
            batch.flows = new ArrayList<>(load.remaining);
            loadReview = load.variant != null && load.variant.isCommittedLoad() ? recordCommittedLoad(load, batch.now)
                            : recordAmbiguousLoad(load, batch.now);
        }
        return first(batch.keyAudit, wipe, loadReview);
    }

    /** Source-backed loot, key-chest claims and death reclaim returns. */
    Transaction settleReturns(Settle batch) {
        // Consume source-backed loot before the reclaim partitioner so a kill drop
        // sharing an item id with the death wipe remains counted as loot.
        batch.loot = batch.context != TRANSFER && batch.context != MARKET ? consumeQueuedLoot(batch.flows)
                        : LootMatch.NONE;
        batch.lootItems = batch.loot.items;
        Map<Integer, Long> contents = batch.loot.matched ? incoming(batch.flows, ANY)
                        : minus(incoming(batch.flows, ANY), batch.lootItems);
        boolean chestContext = !batch.death && !batch.hard && !batch.soft()
                        && batch.context != TRANSFER && batch.context != MARKET
                        && batch.context != PRODUCTION && (batch.loot.matched || batch.context == GENERIC)
                        && gainsAndKeys(batch.flows);
        KeyClaims.ChestOpen claim = chestContext && !contents.isEmpty() ? claims.matchChest(batch.keys,
                                contents, batch.loot.matched ? batch.loot.activityName : null) : null;
        if (claim != null && isDeferredKey(claim.key)) {
            // Settlement and claim removal happen in this same revision. The settling row
            // carries the claim id only when the claim reached zero: a partial consume keeps
            // its durable remainder and must not look settled to a reload.
            String closed = claims.consume(claim.key, claim.quantity);
            batch.claim = closed != null ? closed : batch.claim;
        }
        if (claim != null && !batch.loot.matched)
            batch.offer(LOOT, claim.chest, contents, null, true);
        boolean deathTransfer = batch.context == TRANSFER && deathNote(batch.note);
        boolean canSplitReturns = (!batch.hard || deathTransfer) && batch.context != MARKET
                        && (batch.context != TRANSFER || deathTransfer);
        return canSplitReturns ? settleReclaim(batch.flows, batch.now, batch.lootItems,
                        !batch.ambiguousCoins) : null;
    }

    /**
     * A displaced earlier spend whose item this change lost leads the settle; the newer click
     * waits for its own change (owner live 2026-10-16).
     */
    void settleActions(Settle batch) {
        var spent = events.peek(SPENT_CONSUME);
        var newer = intent();
        boolean lead = spent != null && hasLoss(batch.flows, id -> id == spent.item)
                        && (newer == null || !hasLoss(batch.flows, id -> id == newer.item));
        if (lead) {
            spent.kind = CONSUME;
            if (newer != null) newer.kind = SPENT_CONSUME;
        }
        settleOwnActions(batch);
        if (lead && newer != null) {
            var left = intent();
            if (left != null) left.kind = SPENT_CONSUME;
            newer.kind = CONSUME;
        }
    }

    /** A confirmed consume or drop owns its losses; transfer evidence and loot name the rest. */
    void settleOwnActions(Settle batch) {
        batch.destroy = intent() != null && intent().flag;
        // Match Drop before consume wipe — consumeMatched used to null dropIntent first.
        batch.disassemble = !batch.hard && !batch.soft() && takenApart(batch.flows);
        batch.combine = batch.disassemble || isDecant(batch.flows);
        if (batch.soft() && batch.combine)
            // A mixed shape while the bank is open has no trustworthy player-action
            // source; preserve the accounting classification without a Decanted stamp.
            batch.suppress = true;
        // A set-up's auto-load can settle together with the last part placed (the furnace), and
        // the login priming can clear the click intent between the click and the load: with the
        // parts custody active, ball losses are loads on their own. The clicked item must itself
        // be lost for the click path, or a lone part settle would eat the ball's intent (owner
        // live 2026-10-13).
        boolean cannonLoad = events.peek(CANNON) != null && intent() == null && onlyCosts(batch.flows)
                        && hasLoss(batch.flows, Engine::cannonball)
                        && !hasLoss(batch.flows, id -> !cannonball(id) && !cannonPart(id));
        batch.drop = cannonLoad || events.peek(DROP_CLICK) != null && intent() == null
                                && onlyCosts(batch.flows) && hasLoss(batch.flows, id -> id == events.item(DROP_CLICK))
                                && !hasLoss(batch.flows, id -> id != events.item(DROP_CLICK) && !cannonPart(id));
        batch.used = !batch.drop && !batch.combine && matchesConsume(batch.flows, batch.hard);
        var intent = intent();
        if (batch.used && intent != null) {
            // Only a verb whose intent actually matched these flows may name the action; a stale
            // or unmatched click never upgrades the wording, and a named click names only a change
            // that moved its own item (owner 2026-10-16 sweep: a key used with an Eat armed read
            // "Ate"). Flow-level custody must never steal a quantity a named consume/drop intent
            // owns, so its item is captured here.
            boolean own = intent.item < 0 || intent.action == DRINK && all(
                                            batch.flows, 0, n -> dose(n) > 0)
                            || batch.flows.stream().anyMatch(f -> f.item == intent.item);
            batch.intent = own ? intent.action : null;
            batch.label = own ? intent.label : null;
            batch.usedItem = intent.item;
        }
        batch.dropItem = batch.drop ? events.peek(DROP_CLICK) != null ? events.item(DROP_CLICK)
                                : batch.flows.stream().filter(flow -> flow.cost() && cannonball(flow.item))
                                        .mapToInt(flow -> flow.item).findFirst().orElse(-1) : -1;
        if (!batch.used && !batch.drop && !batch.hard && !batch.combine
                && doseStep(batch.flows))
            // Dose leftovers are high-confidence even when the menu/chat intent expired
            // before the inventory stabilized (common with Drink + Pick coalescing).
            // Soft bank-open latch must not block this — only hard bank-container/menu
            // evidence (real deposit/withdraw) may.
            batch.used = true;
        if (batch.used || batch.drop) {
            // A confirmed Eat/Bury/Drink/Drop wins over stale transfer evidence once its matching
            // inventory loss has stabilized. The Dropped/Destroyed stamps survive the loot note
            // below.
            batch.generic();
            batch.note = batch.drop ? dropNote(batch.dropItem) : batch.destroy ? "Destroyed" : "";
        }
        if (batch.used) {
            events.take(CONSUME, events.item(CONSUME), 1, e -> true);
            events.drop(DROP_CLICK);
        }
        // Soft bank-open alone is a weak signal. Real deposits/withdrawals are one-sided;
        // Drink+Pick / Bury+Pick mixed windows must not become ownership-neutral TRANSFER
        // (that yields Used/lost ×0 and drops harvest rows when countUncertain is off).
        batch.asTransfer = !batch.used && !batch.drop && (batch.hard || batch.soft()
                                        && (onlyCosts(batch.flows) || oneSided(batch.flows, true)));
        if (batch.asTransfer) {
            // Soft evidence while bank open (one-sided), or hard evidence that outlived UI
            // close, must settle as ownership-neutral TRANSFER (not Used/lost).
            batch.context = TRANSFER;
            batch.note = empty(batch.note) ? "Bank transfer" : batch.note;
            batch.fight = null;
        } else if (batch.context == TRANSFER)
            // Stale soft TRANSFER (UI was open) must not reclassify pure inventory
            // consumption. Hard bank-container/menu evidence is required.
            batch.generic();
        batch.activity = current.activityHint();
        if (batch.loot.matched) {
            batch.context = batch.loot.context;
            if (!batch.drop && !(batch.used && batch.destroy))
                batch.note = batch.loot.note;
            batch.activity = batch.loot.activityName;
            batch.fight = batch.loot.fight;
        } else if ((batch.context == LOOT || batch.context == PK_LOOT)
                && !matchesExpectedLoot(batch.flows, batch.expected))
            batch.generic(); else if (batch.context == LOOT)
            batch.activity = activityFromNote(batch.note); else if (batch.context == PK_LOOT || batch.context == PK_DEATH)
            batch.activity = "PKing"; else if (batch.context == PRODUCTION && ActionSignals.TRANSFORM.containsValue(batch.note))
            // A run names its own skill: the session hint waits for a second XP drop, so a first
            // item would carry the last activity (or none).
            batch.activity = "Smelting".equals(batch.note) ? "Smithing" : batch.note;
    }

    /**
     * Schema-104 flow-level custody: exact Flow quantities proven to be GE principal, returns or
     * collections are claimed before any whole-transaction classification. Returns the last custody
     * booking when nothing is left; residual quantities keep their own evidence.
     */
    Transaction settleCustody(Settle batch) {
        String id = current.id();
        maintainMarket(batch.now);
        // Transaction-level owners (transfer/death/loot) block everything; per-item owners (a
        // named consume/drop intent or matched loot quantity) block only their own items so the
        // remaining quantities can still be claimed exactly.
        if (batch.pricingReview || batch.context == TRANSFER || batch.hard || batch.asTransfer
                || batch.death || batch.context == LOOT || batch.context == PK_LOOT
                || batch.context == PK_DEATH || batch.used && batch.usedItem < 0
                || batch.drop && batch.dropItem <= 0)
            return null;
        var blocked = new HashSet<Integer>(batch.loot.matched ? batch.loot.items.keySet() : emptySet());
        if (batch.usedItem >= 0) blocked.add(batch.usedItem);
        if (batch.dropItem > 0) blocked.add(batch.dropItem);
        GeCustodyLedger.Partition custody = exchange.partition(batch.flows, batch.now, id,
                        identifier -> !blocked.contains(identifier), batch.now <= events.at(GE_COLLECT));
        if (custody.counted.isEmpty() && custody.transferBookings.isEmpty()) return null;
        // Custody claimed these exact quantities; the matching offer sides are spent so
        // stale placement/fill evidence can never own a later unrelated movement.
        exchange.spend(custody.claimedFlows, batch.now);
        var rows = new ArrayList<>(custody.counted);
        if (config.keepTransferAuditRows()) rows.addAll(custody.transferBookings);
        rows.forEach(booking -> add(booking, true));
        if (custody.residualFlows.isEmpty()) return custody.lastBooking();
        batch.flows = new ArrayList<>(custody.residualFlows);
        return null;
    }

    /** Classifies what is left of the change and books it as one receipt. */
    Transaction book(Settle batch) {
        if (batch.used && raids.supplies(batch.flows, true, emptyMap())) {
            if (batch.flows.isEmpty()) return null;
            batch.note = msg("raid-supply-credit");
        }
        // The offer ledger is item/direction-scoped evidence of a Grand Exchange movement. It may
        // only own a settle that no stronger source-specific evidence already owns — matched
        // loot/reward, a confirmed consume/drop action, or a loot/death context keeps its own
        // delta.
        boolean sourceProven = batch.loot.matched || batch.used || batch.drop
                        || batch.context == LOOT || batch.context == PK_LOOT || batch.context == PK_DEATH;
        // A recent offer may explain a movement no custody lifecycle claimed. Custody is
        // authoritative for GE economics, so this is ancillary evidence only: it fails closed to
        // an uncounted review row instead of a quote-valued cost or automatic proceeds.
        boolean explained = !sourceProven && batch.context != MARKET && batch.context != TRANSFER
                        && !batch.hard
                        // Proceeds reach the pack only through a Collect: without one a gain is a
                        // pickup or loot, never an uncollected sale (owner live 2026-10-16:
                        // ground coins went to Review as Grand Exchange).
                        && (hasLoss(batch.flows) || events.at(GE_COLLECT) >= batch.now)
                        && exchange.explains(batch.flows, batch.now);
        if (explained) batch.note = "Grand Exchange";
        if (explained || batch.context == MARKET)
            // The movement an offer side explains is the only one it may ever own, whether the
            // GE UI context or a recent offer supplied the evidence for this settle.
            exchange.spend(batch.flows, batch.now);
        // An explicit GE interaction is source evidence, not settlement authority. Any
        // residual flow that custody did not claim must await owner review, even when
        // the broad MARKET context would otherwise classify a coins-only TRADE.
        boolean unclaimed = batch.context == MARKET && batch.note != null
                        && batch.note.startsWith("Grand Exchange");
        boolean geReview = explained || unclaimed || batch.pricingReview;
        var type = classifier.classify(batch.context, batch.flows);
        boolean uncertain = !batch.asTransfer && !batch.hard && !batch.soft() && type == UNCERTAIN;
        boolean dismantle = batch.disassemble
                        && (events.item(DISMANTLE_CLICK) == WILD_CAVE_BRACELET_UNCHARGED
                                || events.item(DISMANTLE_CLICK) == WILD_CAVE_BRACELET_CHARGED);
        if (batch.disassemble && !dismantle) {
            // Taking an item apart (Revert, Dismantle) keeps its worth in the parts: neutral, never
            // Review (owner 1.2). What the parts do not give back is booked below. The bracelet of
            // ethereum is the exception: its 250-ether dismantle is worth more than the item, so
            // the parts rule would bury the gain; it settles like the same change without the
            // click (fail-closed Review, owner 2026-10-12).
            type = TransactionType.TRANSFER;
            for (Flow flow : batch.flows)
                if (flow.item == events.item(DISMANTLE_CLICK))
                    batch.note = "Dismantled " + flow.name;
        } else if (batch.used && type != TransactionType.TRANSFER && type != TRADE)
            // Dose leftovers / empty vials are mixed inventory deltas; confirmed
            // consume intent still books Used/lost rather than uncertain exclusion.
            type = CONSUMPTION; else if (!batch.asTransfer && type == UNCERTAIN && !batch.combine && (doseStep(batch.flows)
                        || batch.soft() && hasLoss(batch.flows))) {
            // The classifier still sees mixed value: a dose shape alone is enough once soft
            // transfer has been refused, and a refused soft bank latch in a mixed Drink/Pick/Bury
            // window books supplies rather than dropping costs and harvest as UNCERTAIN.
            type = CONSUMPTION;
            batch.used = true;
        } else if (uncertain && all(batch.flows, -1, name -> isRune(name)
                                        || isAmmo(name) || isClaim(name)))
            // A pickup settling with a routine spend (a dart thrown, a spell cast) is a gain net of
            // that
            // spend, as a loot receipt absorbs a cast; never an uncounted Review (owner
            // 2026-09-28).
            // An opened claim (a coin pouch) is income too: its neutral claim leg is not a cost
            // (owner 2026-09-29).
            type = GAIN; else if (uncertain && batch.combine && !dismantle) {
            // Combining or decanting keeps every dose, so it is neutral like a bank move, never a
            // Review row (owner 1.2); the doses keep their value until they are drunk. The bracelet
            // of ethereum's dismantle is not value-preserving, so it stays fail-closed Review
            // (owner 2026-10-12).
            type = TransactionType.TRANSFER;
            batch.note = "Decanted";
        } else if (uncertain && events.peek(DIALOGUE) != null && all(
                        batch.flows, -1, pattern("cash").asMatchPredicate())) {
            // Owner 2026-10-09: coins for items while talking to an NPC is a purchase from its
            // dialogue
            // (Elnock's jars, a ticket), booked like a shop's; without a conversation it stays
            // Review.
            type = TRADE;
            batch.context = MARKET;
            batch.note = "Bought";
        } else if (uncertain && events.peek(DIALOGUE) != null && all(
                        batch.flows, 1, pattern("cash").asMatchPredicate())) {
            // Owner 1.3.4: items for coins with a shop window open is a sale; the window raises
            // the same conversation signal as a dialogue. Without it the change stays Review.
            type = TRADE;
            batch.context = MARKET;
            batch.note = "Sold";
        }
        // A drop settle is always CONSUMPTION, so picking it back up can shrink it.
        if (batch.drop && type != TransactionType.TRANSFER && type != TRADE) type = CONSUMPTION;
        if (!batch.loot.matched && !geReview && (type == GAIN || type == TransactionType.LOOT || type == UNCERTAIN)
                && !batch.death && oneSided(batch.flows, true)) {
            var picked = pickUpDrops(batch);
            if (picked != null && batch.flows.isEmpty()) return picked;
        }
        if (type == CONSUMPTION) {
            // Clue dig/tele/key spends while a clue path is active.
            if (clues.isClueCost(batch.note)) {
                batch.note = clues.costNote();
                if (clues.activeEncounter() != null)
                    batch.fight = clues.activeEncounter();
            }
            // Final presentation stamp after all note mutations.
            batch.note = batch.drop ? dropNote(batch.dropItem) : batch.destroy ? "Destroyed" : batch.note;
        }
        // Ancillary offer evidence with no claimable custody lifecycle fails closed to an
        // uncounted review row: never quote-valued cost, never automatic proceeds, no basis.
        boolean included = !geReview && type != TransactionType.TRANSFER
                        && (type != UNCERTAIN || config.countUncertainMixedChanges());
        type = geReview ? UNCERTAIN : type;
        boolean retainTransaction = included || config.keepTransferAuditRows() || type == UNCERTAIN;
        // The committed inventory baseline already deduplicates receipts. One encounter may
        // legitimately have several pickups; an encounter-wide revenue gate would discard them.
        batch.activity = type == TransactionType.TRANSFER ? "Transfer" : type == TRADE ? "Market" : batch.activity;
        Confidence confidence = unclaimed || batch.pricingReview ? Confidence.UNCERTAIN
                        : confidenceFor(batch.context, type);
        // Non-bank transfers explain themselves (minigame wipe, neutral zone, death).
        String reason = batch.pricingReview ? msg("consumed-dose") : geReview ? msg("ge-movement")
                                : type == TransactionType.TRANSFER && !empty(batch.note)
                                                && !batch.note.matches(msg("i-bank"))
                                        ? msg("neutral-prefix") + batch.note + "."
                                        : explain(batch.context, type, included);
        Transaction transaction = bookTransaction(batch.now, type, batch.context, batch.note, batch.activity,
                        included, batch.flows, confidence, reason, batch.fight);
        if (!batch.drop && !batch.destroy && !batch.suppress) {
            // Presentation-only evidence; type/valuation/counted above are unchanged.
            transaction.setAction(resolve(batch.intent, batch.flows, type, batch.activity));
            if (transaction.action() == CAST && batch.label != null)
                transaction.setActionLabel(batch.label);
            if (transaction.action() == CAST && batch.spell != null)
                transaction.observedSpellName = named(batch.spell.value);
        }
        if (type == CONSUMPTION && !batch.destroy && transaction.action() == null && !batch.flows.isEmpty()
                && batch.flows.stream().allMatch(flow -> flow.cost() && cannonball(flow.item)))
            // The cannon's balls are supplies wherever they leave the pack: a Fire or Load, a
            // ship-cannon shot from the pack, a manual drop, or the loss leg of a part-pickup
            // (owner 1.3.4).
            transaction.setAction(SUPPLIES);
        if (retainTransaction) {
            if (type != TransactionType.TRANSFER && type != TRADE && !batch.hard && !batch.soft()
                    && !batch.death) {
                // The relation is written only for a claim that actually closed, so restore's
                // settled-id check can never drop a partly consumed remainder.
                String closed = claims.settleLootKey(minus(incoming(batch.flows, ANY), batch.lootItems),
                                losses(batch.flows, KeyClaims::lootKey));
                batch.claim = closed != null ? closed : batch.claim;
            }
            if (batch.claim != null) transaction.setSourceClaimId(batch.claim);
            // A drop or a shot lands on the ground: it stays off the paid-price record until its
            // pickup window closes.
            transaction.setOnGround(included && (batch.drop && type == CONSUMPTION
                                    || transaction.action() == FIRE));
            add(transaction, false);
            if (!empty(batch.fight))
                current.attachToEncounter(transaction.id(), batch.fight, false);
        }
        if (batch.drop) events.take(DROP_CLICK, batch.dropItem, 1, e -> true);
        // Fired arrows and bolts land beside the target; picking them up (into the pack or the
        // ammo slot) shrinks the shot the same way as a drop (owner 1.3.3).
        if (transaction.onGround()) rememberDrop(transaction, Flow::cost);
        String[] lost = batch.disassemble ? DISMANTLE.get(events.item(DISMANTLE_CLICK)) : null;
        if (lost != null)
            // Owner 1.2: a crystal tool's 120 shards are lost when it is reverted.
            bookCharge(batch.now, batch.note, CONFIRMED, "Lost when it was taken apart.",
                    valuation.value(Map.of(Integer.parseInt(lost[2]), -Long.parseLong(lost[1]))), null, false);
        if (batch.disassemble) events.drop(DISMANTLE_CLICK);
        return transaction;
    }

    /** A loot-key manifest claim settled by this change's residual (non-source-backed) gains. */
    void observePriming(ContainerSnapshot current) {
        warmTicks++;
        if (sample != null && sample.equals(current))
            stable++; else {
            sample = current;
            stable = 1;
        }
        int stableTicks = max(2, config.stabilizationTicks() + 1);
        int requiredObservedTicks = max(MINIMUM_BASELINE_WARMUP_TICKS, stableTicks);
        if (warmTicks >= requiredObservedTicks && stable >= stableTicks) {
            baseline = current;
            warming = false;
            clearPriming();
            consumeContext();
        }
    }

    void captureContext() {
        // Late inventory after bank close: hard bank-container/menu evidence still applies
        // even when soft UI context ticks were cleared on close.
        if (hasHardTransfer()) latchHardTransfer();
        if (clockLeft() <= 0) return;
        // Never attach TRANSFER to a new pending change from soft UI-open ticks alone.
        // Hard bank-container/menu evidence (or an already-latched hard pending) required.
        if (context().context == TRANSFER && !pending().hard && !hasHardTransfer())
            // Soft open context may exist while bankOpen(); do not latch it into
            // pending: soft evidence only counts while the UI remains open, and
            // bankClosed() clears soft-only pending context.
            return;
        // Capture the active context once when a pending change begins. Repeated
        // snapshot observations must not merge the same expected loot again.
        // New loot events still merge through aho/kw.
        if (pending().context == GENERIC || context().context.priority > pending().context.priority)
            applyContext(context().context, context().note, context().expected, context().fight);
    }

    void applyContext(Context next, String note, Map<Integer, Long> loot, String encounter) {
        pending().transfer |= next == TRANSFER && pending().hard;
        pending().offer(next, note, loot, encounter, false);
    }

    LootMatch consumeQueuedLoot(List<Flow> flows) {
        var available = incoming(flows, ANY);
        var matched = new HashMap<Integer, Long>();
        var groups = new LinkedHashMap<List<Object>, LootMatch>();
        for (var e : new ArrayList<>(events.open)) {
            if (e.kind != PRIVATE_LOOT && e.kind != LOOT_RESERVE || !events.live(e)
                    || events.now > e.source().at) continue;
            var source = e.source();
            long qty = events.take(e.kind, e.item, available.getOrDefault(e.item, 0L), v -> v == e);
            if (qty <= 0) continue;
            take(available, e.item, qty);
            matched.merge(e.item, qty, SafeMath::sum);
            LootMatch group = groups.computeIfAbsent(Arrays.asList(source.note, source.name, source.context, source.id),
                            key -> new LootMatch(true, source.note, source.name, source.context, source.id,
                                            new HashMap<>(), emptyList()));
            group.items.merge(e.item, qty, SafeMath::sum);
        }
        // A measured gain also spends private proof after its attribution window has ended.
        available.forEach((item, qty) -> events.take(PRIVATE_LOOT, item, qty, e -> true));
        if (groups.isEmpty()) return LootMatch.NONE;
        var sources = new ArrayList<>(groups.values());
        var first = sources.get(0);
        return new LootMatch(true, first.note, first.activityName, first.context, first.fight,
                unmodifiableMap(matched), unmodifiableList(sources));
    }

    String activityFromNote(String note) {
        if (blank(note)) return current == null ? "General" : current.activityHint();
        String text = note.trim(), prefix = "Loot from ";
        return text.startsWith(prefix) && text.length() > prefix.length()
                ? text.substring(prefix.length()).trim() : text;
    }

    boolean matchesExpectedLoot(List<Flow> flows, Map<Integer, Long> loot) {
        var gains = incoming(flows, ANY);
        return !gains.isEmpty() && (empty(loot)
                        || gains.equals(positive(loot)));
    }

    Transaction recordKeyAudit(List<Flow> keys, String encounter, long now) {
        Transaction audit = bookNeutral(now, KeyClaims.LOOT_KEY_NOTE, "PKing", keys, msg("settled-loot"),
                        encounter);
        add(audit, false);
        var receivedKeys = gains(keys, KeyClaims::lootKey);
        // Claim identity must follow the key item, not incidental flow order. The normal valuator
        // sorts by captured value and its equal-value tie order comes from a map; either can vary
        // across equivalent observations while the audit row still represents the same keys.
        receivedKeys.sort(Comparator.comparingInt(item -> item.item));
        for (int index = 0; index < receivedKeys.size(); index++) {
            Flow flow = receivedKeys.get(index);
            // One audit row can carry several key types; each independent claim needs its own
            // Deterministic identity for multi-key provenance.
            String id = receivedKeys.size() == 1 ? audit.id() : audit.id() + "#" + (index + 1);
            claims.open(id, flow.item, flow.quantity, now);
        }
        if (!empty(encounter))
            current.attachToEncounter(audit.id(), encounter, false);
        return audit;
    }

    Transaction recordClaimAudits(List<Flow> keys, long now) {
        Transaction last = null;
        for (Flow flow : keys) {
            if (flow.quantity <= 0 || !isDeferredKey(flow.item))
                continue;
            var entry = forKey(flow.item);
            if (entry == null || entry.tradeable) continue;
            Transaction audit = bookNeutral(now, "Key held · " + fallback(entry.chestName, "Chest"), entry.chestName,
                            singletonList(flow), msg("untradeable-chest"), null);
            audit.setAction(ActionKind.DEFERRED_CLAIM);
            add(audit, false);
            claims.open(audit.id(), flow.item, flow.quantity, now);
            last = audit;
        }
        return last;
    }

    /** Only catalogued key losses and non-key gains: the shape of a chest open. */
    static boolean gainsAndKeys(List<Flow> flows) {
        for (Flow flow : flows == null ? Collections.<Flow>emptyList() : flows) {
            if (flow.quantity != 0 && flow.cost() != (forKey(flow.item) != null))
                return false;
        }
        return !empty(flows);
    }

    /**
     * Credible settled pickup evidence for an untradeable key token. Loot keys and deferred chest
     * keys share one rule: the settle must be a source-backed loot/death context or a plain settled
     * gain — never transfer, market or drop evidence — and a loot context must account for the
     * observed key quantity.
     */
    boolean credibleKeyPickup(Clue applied, List<Flow> originalFlows, List<Flow> keys) {
        var context = applied.context;
        if (context == null || context == TRANSFER || context == MARKET || applied.transfer || applied.hard
                || events.peek(DROP_CLICK) != null)
            return false;
        var evidence = classifier.classify(context, originalFlows);
        if (evidence != GAIN && evidence != TransactionType.LOOT && evidence != TransactionType.PK_LOOT)
            return false;
        var keyQuantities = incoming(keys, ANY);
        if (context == PK_LOOT || context == LOOT)
            // The observed source callback must account for the actual key quantity;
            // a stale loot context or key-shaped bank flow cannot create provenance.
            return covers(applied.expected, keyQuantities);
        // A source-less but settled ordinary gain is still a pickup; transfer-like evidence and
        // production/uncertain/death mixes are rejected above or by this context check.
        return context == GENERIC && evidence == GAIN;
    }

    void resetTracking(boolean primeBaseline) {
        clearCollect();
        events.drop(LOAD_TARGET);
        baseline = null;
        warming = primeBaseline;
        clearPriming();
        clearContext();
        resetHelpers();
        events.drop(PRIVATE_LOOT, LOOT_RESERVE, DISMANTLE_CLICK, CONSUME, SPENT_CONSUME, DROP_CLICK,
                CAST_SPELL, MANUAL_SPELL);
        events.drop(DROPPED, e -> cannonball(e.item));
    }

    static boolean groundDart(Flow flow) {
        return flow != null && flow.cost() && ChargeCheck.DARTS.containsValue(flow.item);
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
            for (var session : sessions()) {
                for (var row : session.transactions()) remember(row);
                for (var undone : session.undoHistory)
                    if (!undone.restored) remember(undone.transaction);
            }
        }
        for (Iterator<Transaction> rows = ground.values().iterator(); rows.hasNext(); ) {
            var row = rows.next();
            if (!row.onGround()) {
                rows.remove();
                continue;
            }
            if (events.peek(DROPPED, e -> row.id().equals(e.id)) != null || undone(row)) continue;
            rows.remove();
            for (var session : sessions())
                if (session.transactions().contains(row)) session.usedUp(row);
        }
    }

    void remember(Transaction row) {
        if (row != null && row.onGround()) ground.put(row.id(), row);
    }

    boolean undone(Transaction row) {
        for (var session : sessions())
            for (var record : session.undoHistory)
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
        for (Flow flow : drop.flows()) {
            if (onGround.test(flow))
                events.add(DROPPED, flow.item, abs(flow.quantity), cannonball(flow.item) ? 2099 : 299, null)
                                .id = drop.id();
        }
    }

    /** A Drop's note; balls loaded into a cannon say so. */
    static String dropNote(int id) {
        return cannonball(id) ? "Loaded into the cannon" : "Dropped";
    }

    /**
     * Picking up an item you dropped minutes ago shrinks that Dropped row instead of booking a
     * second, matching gain: drop 3 bolts and pick 3 up, and neither row shows. Any pickup of the
     * item counts, since the Net is the same whichever bolts they are. The rest books as usual.
     */
    Transaction pickUpDrops(Settle batch) {
        var picked = new ArrayList<Flow>();
        var rest = new ArrayList<Flow>();
        for (Flow flow : batch.flows) {
            long left = flow.quantity - pickUp(flow.item, flow.quantity, batch.now);
            if (left < flow.quantity) picked.add(flow.part(flow.quantity - left));
            if (left > 0) rest.add(flow.rest(flow.quantity - left));
        }
        if (picked.isEmpty()) return null;
        batch.flows = rest;
        Transaction row = bookTransaction(batch.now, TransactionType.TRANSFER, TRANSFER, "Picked back up",
                        "Transfer", false, picked, CONFIRMED, msg("picked-back-up"), null);
        if (config.keepTransferAuditRows()) add(row, false);
        return row;
    }

    /**
     * Shrinks remembered drops and shots of the item by up to {@code quantity}; returns how many.
     */
    long pickUp(int id, long quantity, long now) {
        long left = quantity;
        for (var e : events.all(DROPPED)) {
            long take = min(left, e.qty);
            if (e.item != id || take <= 0) continue;
            long given = current.pickUp(e.id, e.item, take, now);
            // A row that gave back less holds no more of the item: forget the rest.
            events.take(DROPPED, e.item, given < take ? e.qty : given, v -> v == e);
            left -= given;
        }
        return quantity - left;
    }

    /**
     * Fired darts picked up straight into the blowpipe (the game's ammo-picking setting): the shot
     * shrinks as a pickup into the pack does, and the pipe's next Check counts them as loaded, so
     * they are never taken off twice.
     */
    synchronized void pickUpIntoPipe(int id, long quantity, long now) {
        if (!live()) return;
        long given = pickUp(id, quantity, now);
        if (given > 0)
            checks.noteLoad(ChargedItem.BLOWPIPE, null, now, id, given);
    }

    /** Owner fencing clears only ephemera; persisted grave/key/custody continuity is retained. */
    synchronized void clearEphemera() {
        clearCollect();
        resetHelpers();
        cannonSeed = 0;
        death.ticks = 0;
        death.service = null;
    }

    /** The same non-inbox helpers also reset at an ordinary tracking-window boundary. */
    void resetHelpers() {
        estimate.clear();
        checks.reset();
        stored.clear();
        zoneTransfer = false;
        capture = false;
        storedBaseline = null;
        captureTicks = 0;
        restoreTicks = 0;
        clues.clear();
        claims.clearEvidence();
    }

    void clearPending() {
        dirty = false;
        pending = null;
        cast = null;
        stableTicks = 0;
        events.drop(PENDING_CLAIM);
    }

    /** The priming window ends: its counters and any pending change reset together. */
    void clearPriming() {
        sample = null;
        warmTicks = 0;
        stable = 0;
        clearPending();
    }

    boolean matchesConsume(List<Flow> flows, boolean hardTransfer) {
        if (intent() == null || empty(flows)) return false;
        // Match the spent stack even when a dose leftover / empty vial appears as a gain.
        if (intent().item >= 0 && hasLoss(flows, id -> id == intent().item))
            return true;
        // Open intent (menu item id missing) or id mismatch after canonicalize: still
        // confirm Drink/Eat when the delta is a dose/partial leftover, or a pure cost
        // (Bury/Cast runes/ammo) so hard TRANSFER cannot swallow the spend.
        if (doseStep(flows)) return true;
        if (onlyCosts(flows)) {
            // Named intent for a different item + hard bank deposit must stay TRANSFER
            // (stale bury of bones must not book oak-log deposit as Used/lost).
            if (hardTransfer && intent().item >= 0) return false;
            return true;
        }
        // Live potato-field sequence: Drink + Pick + Bury often share one stabilization
        // window. Any armed consume intent (named or open) must win so Used/lost books
        // instead of UNCERTAIN exclusion — wrong/stale named ids previously skipped
        // this path because it required itemId < 0 only.
        // Hard bank-container/menu evidence: do not let mixed any-cost swallow a deposit.
        if (hardTransfer) return false;
        return hasLoss(flows);
    }

    /**
     * Pickup then bury (etc.) in one stabilize window nets to zero vs baseline. Named consume
     * intent stores invent qty at arm — synthesize the shortfall loss.
     */
    Map<Integer, Long> consumeDeltas(ContainerSnapshot current) {
        if (intent() == null || intent().item < 0 || intent().held < 0 || current == null)
            return emptyMap();
        long nowQty = current.quantity(intent().item), lost = intent().held - nowQty;
        if (lost <= 0) return emptyMap();
        var synthetic = new HashMap<Integer, Long>();
        synthetic.put(intent().item, -lost);
        return synthetic;
    }

    void consumeContext() {
        events.drop(LIVE_CLAIM, CLOCK, SOFT_TRANSFER);
        // HARD_TRANSFER survives until settle or its TTL expires, so bank-container
        // evidence is not lost on the idle GameTick before inventory dirty.
    }

    void clearContext() {
        consumeContext();
        clearTransfer();
    }

    void clearTransfer() {
        events.drop(HARD_TRANSFER);
    }

    Confidence confidenceFor(Context context, TransactionType type) {
        if (context == TRANSFER || context == MARKET || context == LOOT
                || context == PK_LOOT || context == PK_DEATH)
            return CONFIRMED;
        if (type == UNCERTAIN) return Confidence.UNCERTAIN;
        return Confidence.LIKELY;
    }

    /** Loss ids claimed by exact consume/drop intent are excluded from the death wipe. */
    Map<Integer, Long> actionLosses(List<Flow> flows, boolean hardTransfer) {
        var excluded = new HashMap<Integer, Long>();
        if (intent() == null)
            return events.peek(DROP_CLICK) == null ? excluded
                    : losses(flows, id -> id == events.item(DROP_CLICK));
        if (intent().item >= 0)
            return losses(flows, id -> id == intent().item);
        // An open-id intent with only costs cannot distinguish which item was consumed; keep
        // every loss in this ambiguous batch counted.
        return addDoseLosses(excluded, flows) || !matchesConsume(flows, hardTransfer) ? excluded
                : losses(flows, ANY);
    }

    String explain(Context context, TransactionType type, boolean counted) {
        switch (context) {
            case TRANSFER:
                return msg("excluded-because");
            case MARKET:
                return counted ? msg("trade-inventory") : msg("grand-exchange-shop");
            case LOOT:
                return msg("matched-item-ids");
            case PK_LOOT:
                return msg("matched-stock");
            case PK_DEATH:
                return msg("inventory-equipment");
            case PRODUCTION:
                return type == CONSUMPTION ? msg("failed-inputs") : msg("simultaneous-inputs");
            case GENERIC:
            default:
                if (type == UNCERTAIN)
                    return counted ? msg("mixed-flows") : msg("mixed-confirmed");
                if (type == GAIN) return msg("stable-value");
                if (type == CONSUMPTION) return msg("stable-stock");
                return msg("stable-change");
        }
    }

    /**
     * A load whose exact quantities are ambiguous still moves value into charges: neutral by law,
     * never a cost and never an incomplete Review (owner 2026-09-29).
     */
    Transaction recordAmbiguousLoad(LoadTarget partition, long now) {
        if (partition == null || partition.unknown.isEmpty() || !live()) return null;
        Transaction transfer = bookNeutral(now, msg("charge-load-transfer"), "Charge load",
                        partition.unknown, msg("load-confirmed"), null);
        transfer.setAction(CHARGE_LOAD_AMBIGUOUS);
        add(transfer, false);
        // The weapon's next Check adds these back, so a reload is never read as darts unused.
        for (Flow flow : partition.unknown)
            checks.noteLoad(partition.variant, partition.targetIdentity, partition.clickedAt,
                    flow.item, multiply(abs(flow.quantity),
                            partition.variant.units));
        return transfer;
    }

    /**
     * A load whose components can never be refunded (crystal armour and the crystal shield): the
     * shards are committed to the piece, so they book as a counted Supplies spend now instead of a
     * neutral transfer (owner 2026-10-12). A revert keeps the seeds and loses these charges; the
     * piece keeps no Check baseline to reconcile.
     */
    Transaction recordCommittedLoad(LoadTarget partition, long now) {
        if (partition == null || partition.unknown.isEmpty() || !live()) return null;
        return bookCharge(now, msg("committed-load") + fallback(partition.variant.getDisplayName(), "Crystal charges"),
                CONFIRMED, msg("committed-proof") + partition.variant.getDisplayName() + ".",
                partition.unknown, partition.variant.action, false);
    }

    Transaction advanceContext() {
        if (dirty || pending != null) return null;
        if (clockLeft() <= 0) consumeContext();
        if (restoreTicks > 0 && --restoreTicks <= 0) {
            restoreTicks = 0;
            stored.clear();
        }
        return null;
    }

    /**
     * A measured death wipe may own this change: a death-held transfer, or an unclaimed generic
     * change.
     */
    static boolean deathNote(String note) {
        return note != null && note.startsWith("Death: items held");
    }

    synchronized Metrics metrics(long now) {
        if (current == null) return Metrics.none();
        return current.metrics(now);
    }

    /** Correction-aware metrics of one owner; Hub v1 counts every booked row (charter D). */
    synchronized Session session() {
        return current;
    }

    synchronized Session freePlay() {
        return general;
    }

    synchronized List<Session> history() {
        return frozenList(history);
    }

    synchronized Session historySession(String id) {
        var saved = savedSession(id);
        return saved == null ? archive.historySession(id) : saved.session;
    }

    /** Metrics of a retained history session; null when the id is not in history. */
    synchronized Metrics historyMetrics(String id, long now) {
        return archive.historyMetrics(id, now);
    }

    synchronized boolean deleteHistory(String id) {
        if (saved.removeIf(saved -> saved.id.equals(id))) {
            changed();
            return true;
        }
        return archive.deleteHistory(id);
    }

    /** Clears only GP Manager tracking state; configuration is intentionally outside the engine. */
    synchronized void resetData(long now) {
        raids.restore(null);
        doseRetries.clear();
        history.clear();
        saved.clear();
        changed();
        claims.clear();
        // A reset is a new install: no session until gameplay (with Automatic tracking) or Start.
        general = null;
        custom = null;
        current = null;
        // Destructive reset clears the schema-104 custody lifecycles with the rest.
        exchange.reset();
        basis.reset();
        ground = null;
        clearCollect();
        // The cleared session's unbooked coffer drain would otherwise block the next session's.
        drain = 0;
        // Destructive reset clears the pending death/reclaim physical evidence with it.
        death.reset();
        // The looting bag's dispenser additions belong to the account that earned them.
        bag.clear();
        contents.clear();
        reward.clear();
        events.drop(DROP_CLICK);
        resetTracking(true);
        // A reset is a fresh start: resume by itself shortly, if the player does nothing first.
        startAt = now + 4000;
    }

    synchronized long bestKill() {
        return current == null ? 0 : current.bestKill();
    }

    synchronized SavedState savedState() {
        settleCoffer();
        var state = new SavedState(general, custom, history);
        if (!blank(profile))
            state.setOwnerKey(profile);
        state.setProfileTimeZoneId(zone);
        claims.writeTo(state);
        state.setPendingDeathReclaim(death.snapshot());
        state.setSavedGrinds(plans);
        state.savedSessions = saved.isEmpty() ? null : new ArrayList<>(saved);
        var bag = bagStock();
        state.lootingBag = bag.isEmpty() ? null : bag;
        // Schema-104 custody continuity travels in the same bounded generation as the canonical
        // settlements that depend on it.
        exchange.writeTo(state);
        basis.writeTo(state);
        state.raidHistory = raids.state.seen == 0 ? null : raids.snapshot();
        return state;
    }

    // ---- My Grinds: reusable setup metadata, never financial truth --------------------------
    /** Reusable My Grind definitions; setup and default targets only. */
    List<SavedGrind> plans = new ArrayList<>();

    List<SavedSession> saved = new ArrayList<>();

    synchronized SavedSession savedSession(String id) {
        if (id != null && id.startsWith("raid:") && (id.equals("raid:all") || raids.find(id.substring(5)) != null))
            return raids.read(id.substring(5));
        if (id == null || !id.startsWith("saved:")) return null;
        for (var saved : saved) if (saved.id.equals(id)) return saved;
        return null;
    }

    synchronized boolean sameLiveWindow(Session expected, Long start) {
        return expected!=null && current==expected && Objects.equals(start,expected.sessionStartAt);
    }

    /** Freeze the current Live window without creating another counted owner of its receipts. */
    synchronized SavedSession captureLiveSession(Session expected, Long start, long now) {
        if (!sameLiveWindow(expected,start))
            return null;
        long elapsed = expected.liveElapsed(now);
        var copy = SaveCopies.copy(expected);
        if (start != null) {
            copy.transactions = copy.rowsSince(start);
            copy.retainedRevenue = copy.retainedCosts = copy.retainedSuppliesCosts = copy.compactedTransactionCount = 0;
            copy.retainedCostSplitComplete = true;
            copy.startedAt = start;
        }
        copy.rename(custom == null ? "Free play session" : expected.name());
        copy.savedActiveMillis = elapsed;
        copy.endedAt = now;
        copy.paused = false;
        copy.totalPausedMillis = positive(now - copy.startedAt - elapsed);
        copy.sessionStartAt = copy.rateStartAt = copy.savedRateMillis = copy.restoredRateMillis = null;
        copy.profitTargetGp = copy.activeTimeTargetMillis = null;
        copy.undoHistory.clear();
        copy.correctionHistory.clear();
        var markets = MarketRows.window(copy, marketRows(), copy.transactions());
        return new SavedSession("saved:" + uuid(), copy, markets);
    }

    synchronized String saveLiveSession(Session expected, Long start, SavedSession copy) {
        if (!sameLiveWindow(expected,start)
                || copy == null || saved.contains(copy)) return null;
        saved.add(0, copy);
        while (saved.size() > max(1, config.maxHistorySessions())) saved.remove(saved.size() - 1);
        changed();
        return copy.id;
    }

    /** All My Grinds; archived ones only when asked for. */
    synchronized List<SavedGrind> plans(boolean includeArchived) {
        return matching(plans, plan -> !plan.archived || includeArchived);
    }

    /** Saved-Grind lookup by stable id; null when unknown. */
    synchronized SavedGrind plan(String id) {
        for (var plan : plans) {
            if (plan.id().equals(id)) return plan;
        }
        return null;
    }

    /** Creates a reusable My Grind from reusable setup only; runs link to it as they start. */
    synchronized SavedGrind saveGrind(String name, Long profit, Long active, boolean favorite) {
        String clean = fallback(name, "");
        if (clean.isEmpty()) return null;
        var plan = new SavedGrind(uuid(), clean, profit, active, favorite);
        plans.add(plan);
        return plan;
    }

    /**
     * Edits a My Grind. Finished runs keep the targets they ran with; a running run of this Grind
     * takes the new name and any changed target at once (owner 1.5.0: one Grind, one setup).
     */
    synchronized boolean updateSavedGrind(String id, String name, Long profit, Long active,
            boolean favorite) {
        var plan = plan(id);
        if (plan == null) return false;
        String clean = fallback(name, "");
        // Only a changed target reaches the run: a rename or a favourite never re-arms its aim.
        boolean retarget = !Objects.equals(profit, plan.netTargetGp)
                        || !Objects.equals(active, plan.activeTimeTargetMillis);
        if (!clean.isEmpty()) plan.setName(clean);
        plan.setNetTargetGp(profit);
        plan.setActiveTimeTargetMillis(active);
        plan.setFavorite(favorite);
        var run = custom;
        if (run != null && id.equals(run.grindId())) {
            if (!clean.isEmpty()) renameActive(clean);
            if (retarget) setTargets(profit, active, now());
        }
        return true;
    }

    /** The My Grind with this name, ignoring case; null when there is none. */
    synchronized SavedGrind grindNamed(String name) {
        for (var plan : plans) {
            if (plan.name().equalsIgnoreCase(fallback(name, "").trim())) return plan;
        }
        return null;
    }

    synchronized boolean archiveGrind(String id, boolean archived) {
        var plan = plan(id);
        if (plan == null) return false;
        plan.setArchived(archived);
        return true;
    }

    /** Removes the reusable definition only; canonical history and lineage links survive. */
    synchronized boolean deleteSavedGrind(String id) {
        var plan = plan(id);
        if (plan == null) return false;
        plans.remove(plan);
        return true;
    }

    /** Stable-id lineage members, newest first. Never display-name matching. */
    synchronized List<Session> linkedSessions(String id) {
        var result = new ArrayList<Session>();
        if (empty(id)) return result;
        var active = current;
        if (active != null && id.equals(active.grindId())) result.add(active);
        for (var session : history) {
            if (session != null && id.equals(session.grindId())) result.add(session);
        }
        return result;
    }

    /** Stable-id lineage members among completed instances only, newest first. */
    synchronized List<Session> completedLinked(String id) {
        return matching(linkedSessions(id), session -> session != null && session.isClosed());
    }

    /**
     * Starts one fresh canonical Grind. Every Grind is remembered (owner 1.5.0): the run links to
     * its My Grind, found by id, else by name, else created, and the targets it starts with become
     * that Grind's targets. No financial state, loot, active time or correction state is copied.
     */
    synchronized boolean startGrind(
            String name, String id, Long profit, Long activeTarget, long now) {
        String clean = fallback(name, "");
        if (clean.isEmpty() || custom != null) return false;
        var plan = plan(id);
        if (plan == null) plan = grindNamed(clean);
        if (plan == null) plan = saveGrind(clean, null, null, false);
        plan.setArchived(false);
        plan.setNetTargetGp(profit);
        plan.setActiveTimeTargetMillis(activeTarget);
        startCustom(plan.name(), now);
        var active = current;
        if (active == null) return false;
        active.setGrindId(plan.id());
        active.setNetTarget(profit, 0, 0);
        active.setTimeTarget(activeTarget, now);
        return true;
    }

    /**
     * Renames the running Grind; a change like any other, so the revision advances and it is saved.
     */
    synchronized void renameActive(String name) {
        if (current != null) {
            current.rename(name);
            changed();
        }
    }

    /** Mid-Grind aim edit: changes the current target snapshot only, never financial truth. */
    synchronized boolean setTargets(Long profit, Long active, long now) {
        if (current == null) return false;
        boolean net = current.setNetTarget(profit == null || profit <= 0 ? null : profit,
                        metrics(now).net, current.elapsed(now));
        boolean time = current.setTimeTarget(
                        active == null || active <= 0 ? null : active,
                        now);
        // A new or changed target starts a session; re-saving one keeps its count, and Done or
        // Clear leaves the session counting (owner 1.5.2).
        if ((net || time) && (current.targetGp() != null
                        || current.timeTargetMillis() != null)) newLive(now);
        return true;
    }

    /**
     * The Targets sheet's save: the run's targets change and, during a Grind, so do that Grind's,
     * so the next run starts with them (owner 1.5.0). Done clears the run's aim only.
     */
    synchronized boolean saveTargets(Long profit, Long active, long now) {
        if (!setTargets(profit, active, now)) return false;
        var plan = custom == null ? null : plan(custom.grindId());
        if (plan != null) {
            plan.setNetTargetGp(current.targetGp());
            plan.setActiveTimeTargetMillis(current.timeTargetMillis());
        }
        return true;
    }

    /** Again: a new session with the same target, in the same run (owner 1.5.2). */
    synchronized boolean restartTarget(long now) {
        if (current == null || current.targetGp() == null
                        && current.timeTargetMillis() == null) return false;
        newLive(now);
        return true;
    }

    /** Toggles the timer's own pause; the run keeps tracking (owner live 2026-10-14). */
    synchronized boolean toggleTimeTargetPause(long now) {
        if (current == null || current.timeTargetMillis() == null) return false;
        return current.pauseTimeTarget(now) || current.resumeTimeTarget(now);
    }

    /** Every session the engine opens follows the detected activity (AUTO). */
    Session newSession(String name, long now, boolean plan) {
        var session = new Session(name, now, SessionMode.AUTO);
        session.grind = plan;
        attachListeners(session);
        changed();
        return session;
    }

    void attachListeners(Session session) {
        if (session != null) {
            session.setChangeListener(this::changed);
            session.ownedValueObserver = basis;
        }
    }

    void changed() {
        revision = revision == Long.MAX_VALUE ? 1 : revision + 1;
    }

    static boolean validZone(String value) {
        if (blank(value)) return false;
        try {
            of(value);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * One context claim on the next inventory change: what it is, why, which loot, which encounter.
     */
    static class Clue {
        Context context = GENERIC;
        String note = "";
        Map<Integer, Long> expected = emptyMap();
        String fight;

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
            expected = emptyMap();
            fight = null;
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
                note = string(claimNote);
                expected = positive(loot);
                fight = encounter;
            } else if (claim == context) {
                note = empty(claimNote) ? note : claimNote;
                if (claim == LOOT || claim == PK_LOOT) {
                    var merged = positive(loot);
                    expected.forEach((id, quantity) -> merged.merge(id, quantity, SafeMath::sum));
                    expected = merged;
                }
                fight = empty(encounter) ? fight : encounter;
            }
        }
    }
}

/** Source-backed quantities and their distinct source/encounter owners, in observation order. */
@AllArgsConstructor
class LootMatch {
    static final LootMatch NONE = new LootMatch(false, "", "General", GENERIC, null,
                    emptyMap(), emptyList());
    boolean matched;
    String note, activityName;
    Context context;
    String fight;
    Map<Integer, Long> items;
    List<LootMatch> sources;
}

@Singleton
class TransactionClassifier {
    TransactionType classify(Context context, List<Flow> flows) {
        if (context == TRANSFER) return TransactionType.TRANSFER;
        boolean gain = false, hasCost = false;
        for (Flow flow : flows) {
            // Direction comes from quantity. An unavailable unit price produces a
            // zero value, but it is still a real gain or cost that belongs in Review.
            gain |= flow.gain();
            hasCost |= flow.cost();
        }
        if (context == MARKET) return TRADE;
        if (context == PK_LOOT && gain) return TransactionType.PK_LOOT;
        if (context == PK_DEATH && hasCost) return PK_DEATH_LOSS;
        if (context == LOOT && gain) return TransactionType.LOOT;
        if (gain && hasCost)
            return context == PRODUCTION ? PROCESSING : UNCERTAIN;
        if (gain) return GAIN;
        if (hasCost) return CONSUMPTION;
        return ADJUSTMENT;
    }
}

/**
 * Pending evidence inbox:
 * <ul>
 * <li>FE fee, CH chest, TK ground click, BG bag gain, KT kit snapshot.
 * <li>DI dismantle click, GE collect interaction, TA fired-ammo pickup intent.
 * <li>DO drop click, DROPPED an own drop still on the ground, FA recoverable fired-ammo loss.
 * <li>CN a consume command, including its named inventory baseline and presentation hints.
 * <li>SC an earlier consume whose item already left the pack, displaced by a newer click.
 * <li>LT private loot (at bounds attribution); LP/RD quantities reserved by an own-pile despawn.
 * <li>CL load target, CK exact Check link, RV uncharge return; command quantities never prove money.
 * <li>CX/CP live/stabilizing claims, CT attribution clock, SF/HF soft/hard transfer, BO bank UI.
 * </ul>
 */
class Inbox {
    static final int FEE = 1, CHEST = 2, GROUND_CLICK = 3, BAG_GAIN = 4, KIT_SNAPSHOT = 5, DISMANTLE_CLICK = 6,
            GE_COLLECT = 7, BAG_STORE = 8, DROP_CLICK = 9, DROPPED = 10, CONSUME = 12, PRIVATE_LOOT = 13, LOOT_RESERVE = 14,
            LOAD_TARGET = 16, CHECK_LINK = 17, UNCHARGE = 18, LIVE_CLAIM = 19, PENDING_CLAIM = 20, CLOCK = 21,
            SOFT_TRANSFER = 22, HARD_TRANSFER = 23, BANK_UI = 24, SERVANT = 25, SAWMILL = 26, DIALOGUE = 27,
            PIPE_PICKUP = 28, CANNON = 29, SPENT_CONSUME = 30, CAST_SPELL = 31, MANUAL_SPELL = 32, LOAN = 33, RAID_STORE = 34;
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
        e.until = (int) min(Integer.MAX_VALUE, (long) now + ticks);
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
        var e = peek(kind);
        return e == null ? -1 : e.item;
    }

    long at(int kind) {
        var e = peek(kind);
        return e == null ? 0 : e.at;
    }

    boolean has(int kind, int item) {
        return peek(kind, e -> e.item == item) != null;
    }

    Engine.Clue clue(int kind) {
        var e = peek(kind);
        if (e == null) {
            e = add(kind, -1, 1, Integer.MAX_VALUE, null);
            e.data = new Engine.Clue();
        }
        return (Engine.Clue) e.data;
    }

    int left(int kind) {
        var e = peek(kind);
        return e == null ? 0 : e.until - now + 1;
    }

    void ttl(int kind, int ticks) {
        if (ticks <= 0) drop(kind);
        else put(kind, -1, 1, ticks - 1, null);
    }

    synchronized Observation peek(int kind, Predicate<Observation> match) {
        for (var e : open) if (e.kind == kind && live(e) && match.test(e)) return e;
        return null;
    }

    Observation peek(int kind) {
        return peek(kind, e -> true);
    }

    /** Consume no more than the still-open proof, even when a caller retains a stale record. */
    synchronized long take(int kind, int item, long qty, Predicate<Observation> match) {
        long left = positive(qty);
        for (var e : open) {
            if (e.kind != kind || e.item != item || !live(e) || !match.test(e)) continue;
            long used = min(left, e.qty);
            e.qty -= used;
            left -= used;
            if (left == 0) break;
        }
        open.removeIf(e -> !live(e));
        return positive(qty) - left;
    }

    boolean live(Observation e) {
        return e.owner == owner && e.qty > 0 && now <= e.until;
    }

    /**
     * Reserve proven private quantity for the bag, retaining its original source for settlement.
     */
    synchronized long reserve(int kind, int item, long qty, int into) {
        long unit = item == PLATINUM ? 1000 : 1;
        if (unit > 1) {
            item = COINS;
            long available = 0;
            for (var e : all(kind)) if (e.item == item) available = sum(available, e.qty);
            qty = min(multiply(qty, unit), available) / unit * unit;
        }
        long left = positive(qty);
        for (var e : all(kind)) {
            if (e.item != item) continue;
            int index = open.indexOf(e);
            long used = take(kind, item, left, v -> v == e);
            if (used > 0) {
                var part = add(into, item, used, e.until - now, e.note);
                part.data = e;
                open.remove(part);
                open.add(min(index, open.size()), part);
            }
            left -= used;
            if (left == 0) break;
        }
        return (positive(qty) - left) / unit;
    }

    synchronized List<Observation> all(int kind) {
        return matching(open, e -> e.kind == kind && live(e));
    }

    void refresh(int kind, int ticks) {
        var e = peek(kind);
        if (e != null) e.until = max(e.until, now + max(1, ticks) - 1);
    }

    synchronized void drop(int... kinds) {
        for (int kind : kinds) open.removeIf(e -> e.kind == kind);
    }

    synchronized void drop(int kind, Predicate<Observation> keep) {
        open.removeIf(e -> e.kind == kind && !keep.test(e));
    }

    synchronized void tick(int tick) {
        if (hold)
            for (var e : open)
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
    ChargedItem variant;

    Context context;
    String name;

    Observation source() {
        return data instanceof Observation ? ((Observation) data).source() : this;
    }
}

/** Ordered mutable selections and null-tolerant predicates/projections over settled item flows. */
class FlowFilters {
    /** A fresh mutable selection, in source order; null sources are empty. */
    static <T> List<T> matching(Collection<T> source, Predicate<? super T> include) {
        var selected = new ArrayList<T>();
        if (source != null)
            for (var value : source)
                if (include.test(value)) selected.add(value);
        return selected;
    }

    static final IntPredicate ANY = id -> true;

    /** Positive flows whose item matches. */
    static List<Flow> gains(List<Flow> flows, IntPredicate item) {
        return matching(flows, flow -> flow.quantity > 0 && item.test(flow.item));
    }

    /** Flows minus those whose item matches (positive-only when {@code gainsOnly}). */
    static List<Flow> without(List<Flow> flows, IntPredicate item, boolean gainsOnly) {
        if (flows == null) return emptyList();
        return matching(flows, flow -> !item.test(flow.item) || gainsOnly && flow.quantity <= 0);
    }

    /** Positive entries of {@code quantities} whose item does not match. */
    static Map<Integer, Long> withoutItems(Map<Integer, Long> counts, IntPredicate item) {
        var out = quantities(counts, id -> !item.test(id));
        return out.isEmpty() ? emptyMap() : out;
    }

    /** Positive entries only, in a fresh mutable map. */
    static Map<Integer, Long> positive(Map<Integer, Long> counts) {
        return quantities(counts, ANY);
    }

    /** Accounting cash units; physical container stock remains in tokens for display. */
    static Map<Integer, Long> cash(Map<Integer, Long> items) {
        var cash = positive(items);
        Long tokens = cash.remove(PLATINUM);
        if (tokens != null) cash.merge(COINS, multiply(tokens, 1000), SafeMath::sum);
        return cash;
    }

    static Map<Integer, Long> quantities(Map<Integer, Long> counts, IntPredicate item) {
        var out = new HashMap<Integer, Long>();
        if (counts != null)
            for (var e : counts.entrySet())
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0 && item.test(e.getKey()))
                    out.put(e.getKey(), e.getValue());
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
            if (flow.quantity == Long.MIN_VALUE || !which.test(flow)) continue;
            long taken = claimer.applyAsLong(flow, SafeMath.abs(flow.quantity));
            if (taken > 0) parts.add(flow.part(taken));
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
            for (var e : base.entrySet()) {
                Long less = subtract == null ? null : subtract.get(e.getKey());
                long left = (e.getValue() == null ? 0 : e.getValue()) - (less == null ? 0 : SafeMath.positive(less));
                if (e.getKey() != null && left > 0) out.put(e.getKey(), left);
            }
        }
        return out;
    }

    /** True when every wanted quantity fits inside {@code available}. */
    static boolean covers(Map<Integer, Long> available, Map<Integer, Long> wanted) {
        if (empty(wanted) || empty(available)) return false;
        for (var e : wanted.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0
                    || available.getOrDefault(e.getKey(), 0L) < e.getValue())
                return false;
        }
        return true;
    }

    /** Removes up to {@code max} of one item and returns the quantity taken. */
    static long take(Map<Integer, Long> counts, int id, long max) {
        if (counts == null || max <= 0) return 0;
        long held = counts.getOrDefault(id, 0L), taken = min(SafeMath.positive(held), max);
        if (held - taken <= 0) counts.remove(id);
        else counts.put(id, held - taken);
        return taken;
    }

    /** Summed positive quantities per matching item. */
    static Map<Integer, Long> incoming(List<Flow> flows, IntPredicate item) {
        return quantities(flows, item, true);
    }

    /** Summed magnitudes of negative deltas per matching item. */
    static Map<Integer, Long> losses(List<Flow> flows, IntPredicate item) {
        return quantities(flows, item, false);
    }

    static Map<Integer, Long> quantities(List<Flow> flows, IntPredicate item, boolean income) {
        var out = new HashMap<Integer, Long>();
        if (flows != null) {
            for (Flow flow : flows) {
                if (!item.test(flow.item)) continue;
                long delta = flow.quantity;
                if (income ? delta > 0 : delta < 0) {
                    long size = SafeMath.abs(delta);
                    out.merge(flow.item, size, SafeMath::sum);
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
        boolean[] drop = new boolean[flows.size()];
        for (int i = 0; i < flows.size(); i++) {
            Flow cost = flows.get(i);
            if (cost == null || cost.quantity >= 0) continue;
            var variant = ChargeCheck.supported(cost.item);
            for (int j = 0; j < flows.size(); j++) {
                Flow gain = flows.get(j);
                if (drop[j] || gain == null || gain.quantity <= 0 || gain.item == cost.item
                        || abs(gain.quantity) != abs(cost.quantity))
                    continue;
                if (variant != null && ChargeCheck.supported(gain.item) == variant
                        || reducedBase(cost.item) == reducedBase(gain.item)) {
                    drop[i] = true;
                    drop[j] = true;
                    break;
                }
            }
        }
        var result = new ArrayList<Flow>();
        for (int i = 0; i < flows.size(); i++) {
            if (!drop[i]) result.add(flows.get(i));
        }
        return result;
    }

    /** The item's 1:1 proxy base (an imbued variant reduces to its plain item), else itself. */
    static int reducedBase(int id) {
        int[] proxy = CurrencyProxyCatalogue.proxyOf(id);
        return proxy != null && proxy[1] == 1 ? proxy[0] : id;
    }

    static boolean hasLoss(List<Flow> flows) {
        return hasLoss(flows, ANY);
    }

    static boolean hasLoss(List<Flow> flows, IntPredicate item) {
        if (flows != null) {
            for (Flow flow : flows) {
                if (flow.quantity < 0 && item.test(flow.item)) return true;
            }
        }
        return false;
    }

    /** True when every non-null flow is on one side and at least one is non-zero. */

    static boolean onlyCosts(List<Flow> flows) {
        return oneSided(flows, false);
    }

    static boolean oneSided(List<Flow> flows, boolean income) {
        if (empty(flows)) return false;
        boolean saw = false;
        for (Flow flow : flows) {
            long delta = flow.quantity;
            if (income ? delta < 0 : delta > 0) return false;
            saw |= delta != 0;
        }
        return saw;
    }
}

/**
 * PvM death → reclaim lifecycle. A separate held-stack snapshot is intersected with measured
 * negative inventory flows to establish which items actually left the player. Those exact
 * quantities remain ownership-neutral through gravestone expiry and Death’s Office retrieval.
 *
 * <p>Fees are never inferred here; the engine can book only an observed carried-coin loss. A timer
 * expiry records an informational audit event and never creates an item loss.
 *
 * <p>Pure tick-counted state; the engine owns the single instance.
 */
class DeathReclaimLifecycle {
    /** Ticks a gravestone retains items. */
    static final int AWAIT_TICKS = 1500; // 15 minutes

    /** Keep a grave interaction armed through the usual open/confirm/claim sequence. */
    static final int MIN_RECLAIM_ARM_TICKS = 200; // 2 minutes

    /** Bound delayed death-wipe reconciliation so later ordinary losses cannot match it. */
    static final int DEATH_WIPE_WINDOW_TICKS = 100; // 1 minute

    @Getter(AccessLevel.PACKAGE)
    boolean awaitingReclaim;

    int wipe, age, ticks;
    Service service;

    /** Stacks held at death not yet matched to a measured loss; null when no wipe is pending. */
    Map<Integer, Long> held;

    /** Measured grave contents still outstanding, with the names used by the expiry note. */
    Map<Integer, Long> items = new HashMap<>();

    Map<Integer, String> names = new HashMap<>();

    /** Local unsafe PvM death: the snapshot is ownership evidence, not presentation evidence. */
    void onPvmDeath(Map<Integer, Long> heldItems) {
        // Another classified death cannot destroy proven ownership by itself. Preserve
        // outstanding grave/Death’s Office quantities and restart the grave timer.
        awaitingReclaim = true;
        // Missing canonical evidence is not permission to neutralize arbitrary losses.
        var held = positive(heldItems);
        held.keySet().removeIf(MinigameItems::isMinigameOnly);
        this.held = held.isEmpty() ? null : held;
        wipe = this.held == null ? 0 : DEATH_WIPE_WINDOW_TICKS;
        age = 0;
        ticks = 0;
        service = null;
        completeIfEmpty();
    }

    /** True while waiting for the measured death inventory/equipment removal. */
    boolean wipePending() {
        return awaitingReclaim && held != null;
    }

    /** Stop the short death-wipe whitelist when stronger later evidence owns the loss. */
    void cancelWipe() {
        held = null;
        wipe = 0;
        completeIfEmpty();
    }

    /** Consume death-time candidates whose later loss is explicitly action-attributed. */
    void excludeActionLosses(Map<Integer, Long> losses) {
        if (held == null || empty(losses)) return;
        positive(losses).forEach((id, lost) -> take(held, id, lost));
        finishWipe();
    }

    /**
     * Intersect a settled negative-flow batch with the stacks held at death. The returned
     * quantities identify the exact negative flow portions that may be labelled as the
     * ownership-neutral death transfer.
     */
    Map<Integer, Long> removed(List<Flow> flows) {
        if (!wipePending()) return emptyMap();
        var removed = new HashMap<Integer, Long>();
        for (Flow flow : flows == null ? Collections.<Flow>emptyList() : flows) {
            if (flow.quantity >= 0 || flow.quantity == Long.MIN_VALUE
                    // A live retrieval interaction makes measured coin loss a possible
                    // fee; do not consume it as a delayed death wipe.
                    || flow.item == COINS && armed())
                continue;
            long matched = take(held, flow.item, -flow.quantity);
            if (matched <= 0) continue;
            removed.put(flow.item, matched);
            // Keep measured cash ownership too; the armed-service guard above protects fees.
            if (!Engine.destroyedBag(flow.item)) {
                items.merge(flow.item, matched, SafeMath::sum);
                if (!blank(flow.name))
                    names.put(flow.item, flow.name.trim());
            }
        }
        finishWipe();
        return removed.isEmpty() ? emptyMap() : unmodifiableMap(removed);
    }

    /**
     * Match a positive return against the still-outstanding measured death quantity. Returns zero
     * when the item was not part of the actual settled death wipe.
     */
    long matchReturn(int id, long quantity) {
        long matched = take(items, id, quantity);
        if (matched > 0 && items.isEmpty())
            // All measured grave contents have returned. Remaining stacks from the
            // death-time snapshot were kept and can no longer be a delayed wipe.
            cancelWipe();
        return matched;
    }

    long outstanding() {
        long count = 0;
        for (long quantity : items.values()) count = sum(count, quantity);
        return count;
    }

    /**
     * Player interacted with a retrieval service. A fresh Grave / Gravestone click can always
     * re-arm while this local death remains active.
     *
     * @return true only when a local PvM death is awaiting reclaim
     */
    boolean noteReclaimIntent(Service service, int ticks) {
        if (service == null || !awaitingReclaim && items.isEmpty()) return false;
        this.service = service;
        this.ticks = max(MIN_RECLAIM_ARM_TICKS, ticks);
        return true;
    }

    boolean armed() {
        return ticks > 0 && service != null;
    }

    /** One inventory/game tick; defers gravestone expiry while a snapshot is unsettled. */
    String tick(boolean deferExpiry) {
        if (awaitingReclaim && age < Integer.MAX_VALUE) age++;
        if (ticks > 0 && --ticks == 0) service = null;
        if (wipe > 0 && --wipe == 0) cancelWipe();
        if (deferExpiry || !awaitingReclaim || age < AWAIT_TICKS) return null;
        String expired = items.isEmpty() ? null : expiredItems();
        // Expired grave items wait at Death's Office for good: still yours, so they stay
        // outstanding
        // and match only while a retrieval is armed (review 2026-10-09).
        awaitingReclaim = false;
        cancelWipe();
        return expired;
    }

    /** The expiry audit explanation: the outstanding items, sorted by name. */
    String expiredItems() {
        var labels = new ArrayList<String>();
        items.forEach((id, quantity) -> {
                    String name = names.getOrDefault(id, "item #" + id);
                    labels.add(quantity == 1 ? name : name + " ×" + Fmt.exact(quantity));
                });
        labels.sort(String.CASE_INSENSITIVE_ORDER);
        return msg("gravestone-timer") + outstanding() + msg("item-s") + String.join(", ", labels)
                + msg("item-loss");
    }

    void reset() {
        awaitingReclaim = false;
        wipe = 0;
        age = 0;
        ticks = 0;
        service = null;
        held = null;
        items.clear();
        names.clear();
    }

    /**
     * Persistable physical continuity for restart, or null when no death awaits reclaim. Item
     * identity and quantity only; the live reclaim interaction and presentation labels are
     * deliberately not persisted.
     */
    PendingDeath snapshot() {
        var outstanding = items(items);
        var held = items(this.held);
        return outstanding.isEmpty() && held.isEmpty() ? null
                : new PendingDeath(outstanding, held, wipe, age);
    }

    static List<DeathItem> items(Map<Integer, Long> counts) {
        var items = new ArrayList<DeathItem>();
        positive(counts).forEach((id, quantity) -> items.add(new DeathItem(id, quantity)));
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
        for (var item : pending.outstandingItems()) {
            if (item.quantity() > 0 && !MinigameItems.isMinigameOnly(item.itemId))
                items.merge(item.itemId, item.quantity(), SafeMath::sum);
        }
        items = cash(items);
        var held = new HashMap<Integer, Long>();
        for (var item : pending.heldAtDeath())
            held.merge(item.itemId, item.quantity(), SafeMath::sum);
        held.keySet().removeIf(MinigameItems::isMinigameOnly);
        held = new HashMap<>(cash(held));
        wipe = min(DEATH_WIPE_WINDOW_TICKS, max(0, pending.wipeTicksRemaining));
        this.held = wipe > 0 && !held.isEmpty() ? held : null;
        wipe = this.held == null ? 0 : wipe;
        age = max(0, pending.gravestoneAgeTicks);
        awaitingReclaim = this.held != null || !items.isEmpty() && age < AWAIT_TICKS;
    }

    /** Adopt the physical continuity of a staged engine during an owner install. */
    void adoptFrom(DeathReclaimLifecycle other) {
        reset();
        if (other == null) return;
        awaitingReclaim = other.awaitingReclaim;
        wipe = other.wipe;
        age = other.age;
        ticks = other.ticks;
        service = other.service;
        held = other.held == null ? null : new HashMap<>(other.held);
        items.putAll(other.items);
        names.putAll(other.names);
    }

    void finishWipe() {
        if (held != null && held.isEmpty()) cancelWipe();
        completeIfEmpty();
    }

    void completeIfEmpty() {
        if (awaitingReclaim && held == null && items.isEmpty()) reset();
    }
}

/**
 * Read-only state for the local PvM death-reclaim indicator. The item count is the sum of measured
 * outstanding stack quantities, and age is measured in active engine game ticks.
 */
class DeathReclaimStatus {
    boolean pending, armed;
    long items, age;

    DeathReclaimStatus(boolean awaiting, boolean armed, long outstandingCount, long ageTicks) {
        pending = awaiting;
        this.armed = awaiting && armed;
        items = positive(outstandingCount);
        age = positive(ageTicks);
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
    static final long VARIABLE_FEE = -1;

    /** Marker for tiered / percentage fees (gravestone, Death's Office) — see DeathReclaimFees. */
    static final long TIERED_FEE = -2;

    @AllArgsConstructor
    static class Service {
        String key, activity, interactable;
        long fee;
        String note;

        /**
         * Target name shared with unrelated scenery (a bare "Chest", a gravestone): only evidence
         * when a local PvM death is still awaiting reclaim.
         */
        boolean ambiguous;

        /** Ledger explanation for an observed coin payment at this service. */
        String why(long coins) {
            var why = new StringBuilder(msg("item-retrieval")).append(activity).append(" (").append(interactable)
                            .append(")");
            if (fee > 0) {
                why.append(", published ").append(Fmt.exact(fee));
                if (coins > 0 && coins != fee)
                    why.append(", observed ").append(Fmt.exact(coins));
            } else if (fee == VARIABLE_FEE)
                why.append(", fee varies");
            if (!note.isEmpty()) why.append(". ").append(note);
            return why.toString();
        }
    }

    static final Map<String, Service> BY_INTERACTABLE = new LinkedHashMap<>();

    static {
        for (String[] row : rows("retrieval_services"))
            BY_INTERACTABLE.put(row[2].toLowerCase(ROOT), new Service(row[0], row[1], row[2], Long.parseLong(row[3]),
                            row[4], Boolean.parseBoolean(row[5])));
    }

    /**
     * Menu verbs that open or complete a reclaim: the existing reclaim vocabulary and grave prefix,
     * compiled once from d18.
     */
    static final Pattern RECLAIM = pattern("reclaim");

    static final Pattern GRAVE = pattern("grave");

    /**
     * Service for a menu target (NPC or object name) when the option is a retrieval verb (see
     * {@link #isRetrievalOption}). Ambiguous targets ("Chest", "Gravestone", "Death") are returned
     * too; the engine only acts on them while a local PvM death awaits reclaim.
     */
    static Service serviceFor(String option, String target) {
        if (target == null || option == null || !RECLAIM.matcher(lower(option)).matches())
            return null;
        String lower = lower(target);
        if (lower.isEmpty()) return null;
        var exact = BY_INTERACTABLE.get(lower);
        if (exact != null) return exact;
        for (var entry : BY_INTERACTABLE.entrySet()) {
            if (!entry.getValue().ambiguous && lower.contains(entry.getKey()))
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
    static final Set<String> HERBLORE_INGREDIENTS = new HashSet<>(names("empty_vessels"));
    static final Pattern HERBLORE_INGREDIENT_SUFFIX = pattern("herblore-ingredient");
    static final Pattern DOSE_SUFFIX = pattern("dose-suffix");

    /** Ammunition names; tips, heads and shafts are fletching supplies, not ammunition. */
    static final Pattern AMMUNITION = pattern("ammunition");

    static final Pattern FLETCHING_PART = pattern("fletching-part");

    /** Menu verb prefixes (a leading "~" means "contains") and the action each evidences. */
    static final Object[] VERBS = rows("action_rules").stream().filter(a -> !a[2].isEmpty())
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
        if (empty(flows)) return null;
        // A decant shape can also appear in a bank transfer or trade. Only the
        // otherwise-unclassified mixed inventory row is evidence of a player action.
        if (type == UNCERTAIN && isDecant(flows)) return DECANT;
        String activity = lower(activityName);
        if (type == PROCESSING && activity.equals("herblore") && all(flows, -1, ActionReader::isIngredient)
                && all(flows, 1, name -> dose(name) > 0))
            return MIX;
        if (type == PROCESSING && activity.equals("cooking") && isCooking(flows))
            // Use the actual outcome: every output burnt → Burnt, otherwise Cooked.
            return all(flows, 1, name -> name.startsWith("burnt")) ? BURN : COOK;
        // Rune/ammo spends are only recognised on cost-shaped rows: a bank deposit or
        // death loss of runes is a transfer/death, not casting.
        boolean spend = type == CONSUMPTION || type == PK_SUPPLY_COST, hasLoss = hasLoss(flows);
        boolean gain = !gains(flows, ANY).isEmpty(), lossOnly = hasLoss && !gain;
        if (spend && lossOnly && all(flows, 0, ActionReader::isRune)) return CAST;
        if (spend && lossOnly && all(flows, 0, ActionReader::isAmmo)) return FIRE;
        boolean doseStep = doseStep(flows);
        // Every loss must be one potion's doses (Drink) or one item (Eat/Bury/...): a coalesced
        // shark or bones under the same click is not "Drank", so it falls back to Supplies.
        boolean onePotion = oneLossFamily(flows, name -> dose(name) > 0 ? doseBase(name) : null);
        boolean oneItem = oneLossFamily(flows, name -> name.isEmpty() ? null : name)
                        && all(flows, -1, name -> dose(name) <= 0);
        if (matchedIntent != null && hasLoss) {
            switch (matchedIntent) {
                case DRINK:
                    return onePotion ? DRINK : SUPPLIES;
                case EAT:
                case BURY:
                case SCATTER:
                case OFFER:
                    return oneItem ? matchedIntent : SUPPLIES;
                case CAST:
                    return type != TransactionType.TRANSFER && type != TRADE && !all(flows, -1,
                                            name -> !isRune(name) && named(name) == null) ? CAST
                            : doseStep ? SUPPLIES : null;
                case SUPPLIES:
                    return lossOnly && all(flows, 0, name -> name.matches(msg("logs-kindling"))) ? SUPPLIES
                            : null;
                default:
                    return matchedIntent;
            }
        }
        // Consumption is known from the leftover shape; the verb is not.
        return type == CONSUMPTION && doseStep ? SUPPLIES : null;
    }

    /**
     * Doses of one potion redistributed with no dose lost: e.g. −(3) −(1) +(4), or −(4) +(2) +(2).
     * Requires every flow to be a dosed form of the same base name.
     */
    static boolean isDecant(List<Flow> flows) {
        if (flows == null || flows.size() < 2) return false;
        String base = null;
        long doseBalance = 0;
        boolean anyLoss = false, gain = false;
        for (Flow flow : flows) {
            if (flow.quantity == 0) continue;
            String name = lower(flow.name);
            int dose = dose(name);
            // Combining doses frees a vessel; an emptied vial/jug gain is part of the shape.
            if (dose <= 0 && flow.quantity > 0 && rule("vessel", name)) continue;
            String stem = doseBase(name);
            if (dose <= 0 || stem.isEmpty() || base != null && !base.equals(stem)) return false;
            base = stem;
            doseBalance += dose * flow.quantity;
            anyLoss |= flow.quantity < 0;
            gain |= flow.quantity > 0;
        }
        return anyLoss && gain && doseBalance == 0;
    }

    /** A dose/food step confirms a consume even when its menu intent has expired. */
    static boolean doseStep(List<Flow> flows) {
        return !partialUses(flows).isEmpty();
    }

    /** Exact matched loss/remainder pairs for a recognized dose or partial-food consume. */
    static List<PartialUse> partialUses(List<Flow> flows) {
        if (flows == null || flows.size() < 2) return emptyList();
        var pairs = new ArrayList<PartialUse>();
        for (int index = 0; index < flows.size(); index++) {
            // A loss pairs only with the one remainder it could have become, and that remainder
            // only with this loss.
            var gains = stepPartners(flows, flows.get(index), true);
            if (gains.size() == 1 && stepPartners(flows, flows.get(gains.get(0)), false).size() == 1)
                pairs.add(new PartialUse(index, gains.get(0)));
        }
        return unmodifiableList(pairs);
    }

    /**
     * Indexes of the flows a loss could step to ({@code toGains}), or the losses a gain could come
     * from.
     */
    static List<Integer> stepPartners(List<Flow> flows, Flow flow, boolean toIncome) {
        var partners = new ArrayList<Integer>();
        Flow loss = toIncome ? flow : null, gain = toIncome ? null : flow;
        if (flow == null || (toIncome ? flow.quantity >= 0 || flow.quantity == Long.MIN_VALUE
                        : flow.quantity <= 0))
            return partners;
        for (int index = 0; index < flows.size(); index++) {
            Flow other = flows.get(index), lost = toIncome ? loss : other, gained = toIncome ? other : gain;
            if (lost != null && gained != null && lost.quantity < 0 && lost.quantity != Long.MIN_VALUE
                    && gained.quantity > 0 && -lost.quantity == gained.quantity
                    && isConsumeStep(lost.name, gained.name))
                partners.add(index);
        }
        return partners;
    }

    @AllArgsConstructor
    static class PartialUse {
        int lossIndex, remainder;
    }

    static boolean addDoseLosses(Map<Integer, Long> target, List<Flow> flows) {
        boolean matched = false;
        for (Flow loss : flows) {
            if (!stepPartners(flows, loss, true).isEmpty()) {
                target.put(loss.item, -loss.quantity);
                matched = true;
            }
        }
        return matched;
    }

    /**
     * Potion base name without its dose suffix, for receipts: "Prayer potion(4)" → "Prayer potion".
     */
    static String doseBase(String name) {
        return name == null ? "" : DOSE_SUFFIX.matcher(name.trim()).replaceFirst("").trim();
    }

    /** Dose count parsed from a trailing "(N)" suffix, or −1 when absent. */
    static int dose(String name) {
        var matcher = DOSE_SUFFIX.matcher(name == null ? "" : name.trim());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    static boolean isAmmo(String name) {
        String lower = lower(name);
        return AMMUNITION.matcher(lower).matches() && !FLETCHING_PART.matcher(lower).matches();
    }

    /**
     * True when every loss has the same non-null family key (a matched click cannot name mixed
     * costs); false when there is no loss.
     */
    static boolean oneLossFamily(List<Flow> flows, Function<String, String> family) {
        String first = null;
        for (Flow flow : flows) {
            if (flow.quantity >= 0) continue;
            String key = family.apply(lower(flow.name));
            if (key == null || first != null && !first.equals(key)) return false;
            first = key;
        }
        return first != null;
    }

    /** A cooking row must pair raw food inputs with only their cooked/burnt products. */
    static boolean isCooking(List<Flow> flows) {
        var rawBases = new ArrayList<String>();
        for (Flow flow : flows) {
            if (flow.quantity < 0) {
                String name = lower(flow.name);
                rawBases.add(name.startsWith("raw ") && name.length() > 4 ? name.substring(4).trim()
                                : name.startsWith("uncooked ") && name.length() > 9 ? name.substring(9).trim() : null);
            }
        }
        return !rawBases.isEmpty() && !rawBases.contains(null) && all(flows, 1, output -> rawBases.stream().anyMatch(
                                                raw -> output.equals(raw) || output.equals("cooked " + raw)
                                                                || output.equals("burnt " + raw)));
    }

    /**
     * True when at least one flow on the chosen side (1 gains, -1 losses, 0 both) exists and every
     * such flow's lower-case name satisfies {@code predicate}.
     */
    static boolean all(List<Flow> flows, int side, Predicate<String> predicate) {
        boolean any = false;
        for (Flow flow : flows) {
            long delta = flow.quantity;
            if (delta == 0 || side != 0 && Long.signum(delta) != side) continue;
            if (!predicate.test(lower(flow.name))) return false;
            any = true;
        }
        return any;
    }

    /** Ordered intent and item-name rules, compiled beside the old implementation for parity. */
    static final Map<String, Clause[][]> RULES = rules("action_rules");

    static boolean rule(String group, String text) {
        return Phrases.has(text, RULES.get(group));
    }

    /** First matching verb wins, before the explicit fuel fallback. */
    static ActionKind verbOf(String raw) {
        String name = lower(raw);
        for (int index = 0; index < VERBS.length; index += 2) {
            if (rule((String) VERBS[index], name)) return (ActionKind) VERBS[index + 1];
        }
        return rule("supplies", name) ? SUPPLIES : null;
    }

    /** Empty vessels use the already-normalized name, exactly as before. */

    /** Rune and unopened-claim names use the shared trimmed normalization. */
    static boolean isRune(String name) {
        return rule("rune", lower(name));
    }

    static boolean isClaim(String name) {
        return rule("claim", lower(name));
    }

    /** Ingredient names retain their existing non-null precondition and named ingredient set. */
    static boolean isIngredient(String name) {
        return !name.isEmpty() && rule("ingredient", name) && (HERBLORE_INGREDIENTS.contains(name)
                        || HERBLORE_INGREDIENT_SUFFIX.matcher(name).matches());
    }

    /** Dose arithmetic is unchanged; only food/vessel vocabulary is table-driven. */
    static boolean isConsumeStep(String lostName, String gainedName) {
        String lost = lower(lostName), gain = lower(gainedName);
        if (lost.isEmpty() || gain.isEmpty()) return false;
        if (rule("vessel", gain)) return true;
        int lostDose = dose(lost), dose = dose(gain);
        if (lostDose > 0) {
            String lostBase = doseBase(lost);
            if (!lostBase.isEmpty() && lostBase.equals(doseBase(gain)))
                // A suffix-less gain of the same base is the uncharged item: the last glory
                // charge leaves the plain amulet, so the pair is still one dose step (owner
                // 1.3.4). Any other suffix-less gain keeps failing closed.
                return lostDose == (dose < 0 ? 0 : dose) + 1;
        }
        return rule("food", lost) && rule("partial", gain);
    }
}

/**
 * Menu / chat vocabulary that turns a click into an accounting intent: explicit spend actions (eat,
 * drink, bury, offer, light), transform/production options that arm PRODUCTION context, gather
 * verbs that only name the activity, and the chat lines that confirm a spend after the click.
 * Anything not listed here stays ambiguous and settles through Review, never a guess.
 */
class ActionSignals {
    static final Map<String, String> TRANSFORM = byName("transform_skills"), GATHER = byName("gather_skills");

    /** Compiled menu/chat predicates; d7/d10 already contain transform and gather data. */
    static final Map<String, Clause[][]> RULES = rules("menu_rules");

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
        return rule("spend.norm", lower(option));
    }

    /** Use tinderbox with fuel; target normalization preserves whitespace. */
    static boolean lightsFire(String option, String target) {
        return rule("use", lower(option)) && rule("fuel", lower(target, false))
                && rule("fire", lower(target, false));
    }

    /** Use bones/ashes on an altar. */
    static boolean offersBones(String option, String target) {
        return rule("use", lower(option)) && rule("altar", lower(target, false))
                && rule("bones", lower(target, false));
    }

    /** Positive Sinister Offering cast evidence. */
    static boolean isOffering(String option, String target) {
        return rule("cast", lower(option)) && rule("offering", lower(target, false));
    }

    /** Chaos Altar saved a bone; this cannot confirm spend. */
    static boolean isSpared(String message) {
        return rule("spared", lower(message, false));
    }

    /** Inventory-spend chat, excluding a saved bone. */
    static boolean isChatSpend(String message) {
        return !isSpared(message) && rule("spend.chat", lower(message, false));
    }

    /**
     * Transform/production options (station or inventory) mapped to the skill whose XP confirms
     * them.
     */
    static String transformSkill(String option) {
        return TRANSFORM.getOrDefault(lower(option), "");
    }

    /** Activity hint for object gather verbs; "General" when the verb is not a gather. */
    static String gatherActivity(String option) {
        return GATHER.getOrDefault(string(option), "General");
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
        long trackedQty, basisGp;
    }

    Map<Integer, BasisPool> stock = new LinkedHashMap<>();
    long epoch;
    IntFunction<Long> openReservationLookup = id -> 0L;

    // ---------------------------------------------------------------------------------------
    // Acquisition / removal / change
    // ---------------------------------------------------------------------------------------
    /**
     * Book a newly added canonical transaction: add its exact known coverage, and deplete known
     * coverage for proven permanent non-custody ownership sinks. Custody-owned settlements already
     * consumed their reservation, so their item legs never deplete again.
     */
    synchronized void onAdded(Transaction transaction, boolean owned) {
        if (transaction == null || transaction.timestamp < epoch)
            // Pre-epoch stock is UNKNOWN coverage by migration law; nothing is inferred here.
            return;
        if (epoch <= 0 && transaction.timestamp > 0)
            epoch = transaction.timestamp;
        applyContribution(contribution(transaction), transaction.timestamp);
        if (!owned) depleteSinks(transaction);
    }

    /** Validate restoring a previously removed transaction; false blocks the restore. */
    synchronized boolean canAdd(Transaction transaction) {
        if (transaction == null) return false;
        if (transaction.timestamp >= epoch && !sinks(transaction).isEmpty())
            return false;
        return canApplyAll(contribution(transaction), 1, transaction.timestamp);
    }

    /** Validate removing a retained transaction (undo); false blocks the canonical removal. */
    synchronized boolean canRemove(Transaction transaction) {
        if (transaction == null || transaction.timestamp < epoch) return true;
        if (!sinks(transaction).isEmpty())
            return false;
        return canApplyAll(contribution(transaction), -1, transaction.timestamp);
    }

    boolean canApplyAll(Map<Integer, long[]> changes, long sign, long timestamp) {
        for (var entry : changes.entrySet()) {
            if (!canApply(entry.getKey(), sign * entry.getValue()[0], sign * entry.getValue()[1], timestamp))
                return false;
        }
        return true;
    }

    synchronized void onUsedUp(Transaction transaction) {
        depleteSinks(transaction);
    }

    /** Reverse a previously removed transaction's exact known coverage. */
    synchronized void onRemoved(Transaction transaction) {
        if (transaction == null || transaction.timestamp < epoch) return;
        contribution(transaction).forEach((id, delta) -> applyDelta(id, -delta[0], -delta[1]));
    }

    /**
     * Validate a pending correction/split/undo before any canonical mutation. False means the
     * mutation must be rejected as-is: the item's known coverage is reserved or already realized.
     */
    synchronized boolean canChange(Transaction transaction, Correction previous, List<Flow> previousFlows,
            Correction nextCorrection, List<Flow> next) {
        if (transaction == null) return false;
        var before = contribution(transaction, previous, previousFlows);
        // A null next-flow set means the caller can only promise the positive contribution
        // shrinks; validating against the empty worst case is the strictest safe check.
        var after = contribution(transaction, nextCorrection, next);
        var beforeSinks = sinks(transaction, previous, previousFlows);
        var afterSinks = sinks(transaction, nextCorrection, next);
        if (transaction.timestamp >= epoch && !beforeSinks.equals(afterSinks)
                && !canIntroduceSink(beforeSinks, afterSinks, transaction.timestamp))
            return false;
        return canApplyAll(deltas(before, after), 1, transaction.timestamp);
    }

    /** Apply the exact effective delta after a permitted correction/split/undo. */
    synchronized void onChanged(Transaction transaction, Correction previous, List<Flow> previousFlows) {
        if (transaction == null) return;
        var before = sinks(transaction, previous, previousFlows);
        var after = sinks(transaction);
        applyContribution(deltas(contribution(transaction, previous, previousFlows),
                        contribution(transaction)), transaction.timestamp);
        if (!before.equals(after) && before.isEmpty()) depleteSinks(transaction);
    }

    // ---------------------------------------------------------------------------------------
    // Reservation / realization / depletion
    // ---------------------------------------------------------------------------------------
    /**
     * Reserve the tracked-first known-coverage share for a quantity entering proven SELL custody.
     */
    synchronized Reservation reserve(int id, long quantity, long now) {
        long requested = positive(quantity);
        var pool = stock.get(id);
        long available = pool == null ? 0 : pool.availableQty;
        long availableBasis = pool == null ? 0 : pool.availableBasisGp, take = min(requested, available);
        long basis = BasisMath.shareOf(available, availableBasis, take);
        if (take <= 0 && basis <= 0) return new Reservation(0, 0);
        pool = pool(id);
        pool.availableQty = positive(available - take);
        pool.availableBasisGp = positive(availableBasis - basis);
        return new Reservation(take, basis);
    }

    /** A sale realized units of this item: fence retroactive corrections before {@code now}. */
    synchronized void fenceRealized(int id, long now) {
        var pool = pool(id);
        pool.fencedAt = max(pool.fencedAt, now);
    }

    /** Return an unconsumed reservation share to the available pool (cancel/return). */
    synchronized void release(int id, long quantity, long basisGp) {
        long released = positive(quantity), releaseBasis = positive(basisGp);
        if (released <= 0 && releaseBasis <= 0) return;
        var pool = pool(id);
        pool.availableQty = positive(pool.availableQty + released);
        pool.availableBasisGp = positive(pool.availableBasisGp + releaseBasis);
    }

    /** Deplete known coverage for a proven permanent ownership sink; returns basis consumed. */
    synchronized long deplete(int id, long quantity, long now) {
        long requested = positive(quantity);
        var pool = stock.get(id);
        if (requested <= 0) return 0;
        if (pool == null) {
            // Even a sink with no known coverage leaves a realization fence: units left ownership,
            // so later retroactive coverage for them can never be re-added.
            pool(id).fencedAt = positive(now);
            return 0;
        }
        long take = min(requested, pool.availableQty);
        long basis = BasisMath.shareOf(pool.availableQty, pool.availableBasisGp, take);
        pool.availableQty = positive(pool.availableQty - take);
        pool.availableBasisGp = positive(pool.availableBasisGp - basis);
        pool.fencedAt = max(pool.fencedAt, now);
        return basis;
    }

    /** Currency is money, never tracked owned-item value. */

    // ---------------------------------------------------------------------------------------
    // Persistence / lifecycle
    // ---------------------------------------------------------------------------------------
    synchronized void writeTo(SavedState state) {
        if (state == null) return;
        var out = new TrackedBasisState();
        // Epoch 0 only happens for an engine that never restored or tracked anything; a positive
        // sentinel keeps a saved pool loadable as "tracked since the beginning".
        out.basisEpochMillis = positive(epoch > 0 ? epoch : 1);
        var list = new ArrayList<BasisPool>();
        for (var pool : stock.values()) {
            if (pool.availableQty <= 0 && pool.availableBasisGp <= 0 && pool.fencedAt <= 0
                    && pool.acquiredAt <= 0)
                continue;
            var copy = new BasisPool(pool.itemId);
            copy.availableQty = pool.availableQty;
            copy.availableBasisGp = pool.availableBasisGp;
            copy.fencedAt = pool.fencedAt;
            copy.acquiredAt = pool.acquiredAt;
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
        stock.clear();
        var saved = state == null ? null : state.trackedBasis();
        if (saved != null && saved.basisEpochMillis() > 0) {
            epoch = saved.basisEpochMillis();
            for (var pool : saved.pools()) {
                if (pool == null || pool.itemId <= 0) continue;
                // A save is clamped once here, so the pool's fields are read directly.
                pool.availableQty = positive(pool.availableQty);
                pool.availableBasisGp = positive(pool.availableBasisGp);
                pool.fencedAt = positive(pool.fencedAt);
                pool.acquiredAt = positive(pool.acquiredAt);
                stock.put(pool.itemId, pool);
            }
        } else
            epoch = now;
    }

    /** Destructive reset: tracked basis is continuity state and clears with the rest. */
    synchronized void reset() {
        stock.clear();
        epoch = 0;
    }

    /** Adopt another ledger's durable state during a staged profile swap. */
    synchronized void adoptFrom(TrackedBasisLedger other) {
        reset();
        if (other != null) {
            epoch = other.epoch;
            other.stock.forEach((id, pool) -> {
                        if (pool != null) stock.put(id, pool);
                    });
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------
    BasisPool pool(int id) {
        return stock.computeIfAbsent(id, BasisPool::new);
    }

    void applyContribution(Map<Integer, long[]> part, long timestamp) {
        for (var entry : part.entrySet()) {
            applyDelta(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
            if (entry.getValue()[0] > 0) {
                var pool = pool(entry.getKey());
                pool.acquiredAt = max(pool.acquiredAt, timestamp);
            }
        }
    }

    /** Deplete known coverage for each proven permanent non-custody ownership sink in the row. */
    void depleteSinks(Transaction transaction) {
        var counts = sinks(transaction);
        for (var entry : counts.entrySet())
            deplete(entry.getKey(), entry.getValue(), transaction.timestamp);
    }

    /** An owner may first count a Review removal while no later use has frozen its pool. */
    boolean canIntroduceSink(Map<Integer, Long> before, Map<Integer, Long> after, long timestamp) {
        if (!before.isEmpty() || after.isEmpty()) return false;
        for (var id : after.keySet()) {
            if (!canApply(id, 0, 0, timestamp)) return false;
            var pool = stock.get(id);
            if (pool != null && pool.availableQty > 0 && (pool.acquiredAt <= 0
                            || pool.acquiredAt > timestamp))
                return false;
        }
        return true;
    }

    /** Physical counted removals that would require exact reversal of pooled sink depletion. */
    Map<Integer, Long> sinks(TransactionType type, boolean counted, Correction decision, List<Flow> flows) {
        var counts = new LinkedHashMap<Integer, Long>();
        if (!counted || type == TransactionType.TRANSFER || type == TRADE || decision == Correction.TRANSFER
                || flows == null)
            // Custody-owned trade settlements consume their reservation separately. Their financial
            // Result corrections must remain available without touching physical basis.
            return counts;
        for (Flow flow : flows) {
            if (flow.quantity >= 0 || (flow.item == COINS || flow.item == PLATINUM)) continue;
            long quantity = flow.quantity == Long.MIN_VALUE ? Long.MAX_VALUE : -flow.quantity;
            int id = flow.item;
            counts.put(id, sum(counts.getOrDefault(id, 0L), quantity));
        }
        return counts;
    }

    void applyDelta(int id, long quantity, long basisDelta) {
        if (quantity == 0 && basisDelta == 0) return;
        var pool = pool(id);
        pool.availableQty = positive(pool.availableQty + quantity);
        pool.availableBasisGp = positive(pool.availableBasisGp + basisDelta);
    }

    boolean canApply(int id, long quantity, long basisDelta, long timestamp) {
        if (timestamp < epoch) return false;
        Long reservationTime = openReservationLookup.apply(id);
        if (reservationTime != null && reservationTime > 0 && reservationTime >= timestamp) return false;
        var pool = stock.get(id);
        long fence = pool == null ? 0 : pool.fencedAt;
        if (fence > 0 && fence >= timestamp) return false;
        long available = pool == null ? 0 : pool.availableQty;
        long availableBasis = pool == null ? 0 : pool.availableBasisGp;
        if (available + quantity < 0 || availableBasis + basisDelta < 0) return false;
        return true;
    }

    Map<Integer, long[]> deltas(Map<Integer, long[]> before, Map<Integer, long[]> after) {
        var out = new LinkedHashMap<Integer, long[]>();
        for (var entry : before.entrySet()) {
            long[] target = after.get(entry.getKey());
            long quantity = (target == null ? 0 : target[0]) - entry.getValue()[0];
            long deltaBasis = (target == null ? 0 : target[1]) - entry.getValue()[1];
            if (quantity != 0 || deltaBasis != 0)
                out.put(entry.getKey(), new long[] {quantity, deltaBasis});
        }
        for (var entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey()))
                out.put(entry.getKey(), new long[] {entry.getValue()[0], entry.getValue()[1]});
        }
        return out;
    }

    Map<Integer, long[]> contribution(Transaction transaction) {
        return contribution(transaction, transaction.correction(), transaction.flows());
    }

    /** The contribution the transaction would have under {@code correction} with {@code flows}. */
    Map<Integer, long[]> contribution(Transaction transaction, Correction decision, List<Flow> flows) {
        return flows == null || financialDose(transaction) ? new LinkedHashMap<>() : contribution(transaction.original(),
                        effectiveCounted(transaction.counted, decision), decision, flows);
    }

    /** The row's sinks as it is booked now. */
    Map<Integer, Long> sinks(Transaction transaction) {
        return sinks(transaction, transaction.correction(), transaction.flows());
    }

    Map<Integer, Long> sinks(Transaction transaction, Correction decision, List<Flow> flows) {
        // A drop or a shot still on the ground is not a use yet: picking it up shrinks the row. It
        // depletes the pool when its pickup window closes (Engine#settleGround).
        if (transaction.onGround() || financialDose(transaction)) return new LinkedHashMap<>();
        return sinks(transaction.original(), effectiveCounted(transaction.counted, decision),
                decision, flows);
    }

    /** Effective counted state for an explicit correction (REVENUE/COST always count). */
    static boolean effectiveCounted(boolean autoCounted, Correction decision) {
        var effective = decision == null ? AUTO : decision;
        return effective == REVENUE || effective == COST || effective == AUTO && autoCounted;
    }

    /**
     * Exact known coverage a counted transaction introduces, mirroring {@code Accounting.flow} for
     * positive quantities: AUTO counts the observed positive value, REVENUE counts the gross
     * magnitude, COST/IGNORE/TRANSFER count nothing. Quantity is tracked even when the booked value
     * is legitimately zero; unpriced/unknown/deferred sources create no coverage at all.
     */
    static Map<Integer, long[]> contribution(
            TransactionType type, boolean counted, Correction decision, List<Flow> flows) {
        var out = new LinkedHashMap<Integer, long[]>();
        var effective = decision == null ? AUTO : decision;
        if (type == TransactionType.TRANSFER || effective == Correction.TRANSFER || !counted || effective == IGNORE
                || flows == null)
            return out;
        for (Flow flow : flows) {
            if (flow.quantity <= 0 || (flow.item == COINS || flow.item == PLATINUM)) continue;
            var source = flow.source();
            if (source == null || source == UNPRICED || source == UNKNOWN || source == PriceSource.DEFERRED_CLAIM) continue;
            long income = effective == REVENUE ? SafeMath.abs(flow.value)
                            : effective == COST ? 0 : positive(flow.value);
            long[] total = out.computeIfAbsent(flow.item, id -> new long[2]);
            total[0] = sum(total[0], flow.quantity);
            total[1] = sum(total[1], income);
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
class TrackedBasisState extends SaveCopies {
    long basisEpochMillis;
    List<BasisPool> pools = new ArrayList<>();

    long basisEpochMillis() {
        return positive(basisEpochMillis);
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
class BasisPool extends SaveCopies {
    int itemId;
    long availableQty, availableBasisGp;
    @SerializedName("realizationFenceEpochMillis")
    long fencedAt;

    /** Latest proven known-coverage acquisition; fences retroactive counted removals. */
    @SerializedName("latestAcquisitionEpochMillis")
    long acquiredAt;

    BasisPool() {}

    BasisPool(int id) {
        itemId = id;
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
        return multiplyDivide(totalBasisGp, consumeQty, totalQty);
    }

    /** Long-safe floor(value × numerator / denominator) for non-negative inputs. */
    static long multiplyDivide(long value, long amount, long denominator) {
        if (denominator <= 0 || amount <= 0 || value <= 0) return 0;
        if (amount >= denominator) return value;
        var product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(amount));
        var share = product.divide(BigInteger.valueOf(denominator));
        // b < c, so the share is below a and fits a long.
        return share.longValue();
    }
}

/**
 * Durable schema-104 GE custody ledger.
 *
 * <p>Physical placement principal, cancellation returns and refunds are ownership-neutral
 * transfers. A safely realized settlement books one canonical counted Market result exactly once,
 * guarded by the persisted cumulative-execution counters. Unattributable or unbased lifecycles fail
 * closed through the existing Review domain instead of guessing.
 *
 * <p>An observed collect coin movement (a live Collect interaction with its coin gain) is
 * attributed to exactly the sold offers it proves: the offers whose slot cleared within the SLOT
 * WINDOW, or the one subset of observed after-tax nets that sums to it exactly. A
 * unique-but-unconfirmed match is held for that short window and settles only without contradicting
 * slot evidence; a tie, a contradiction or exhausted evidence fails closed, quarantining only
 * slot-evidenced candidates and leaving the rest pending. Collect cash that arrives before the
 * matching sell execution evidence keeps its longer transient hold. Both holds are transient: a
 * restart leaves the durable records to their own unobserved-settlement handoff, never a double
 * booking. A coin gain with no Collect interaction keeps the previous booking path unchanged.
 *
 * <p>The ledger never mutates booked transactions, never re-prices history, and never scans the
 * whole session history: claims are quantity-bounded against the bounded open-record set.
 */
class GeCustodyLedger {
    /** How long a placement still explains the coins or items it took from the inventory. */
    static final long PLACEMENT_WINDOW_MILLIS = 90000;

    /** How long a terminal lifecycle may await an unobserved settlement before Review handoff. */
    static final long UNOBSERVED_GRACE_MILLIS = 10 * 60 * 1000;

    /** A cleared slot whose cash or items never reached the inventory went to the bank by now. */
    static final long BANK_COLLECT_MILLIS = 30000;

    /**
     * Slot-clear evidence window: at most 3 game ticks either side of the coin gain. A slot that
     * cleared in this window proves its offer's coins were the ones collected.
     */
    static final long SLOT_WINDOW_MILLIS = 1800;

    /** One open record per GE slot; more candidates than this can never be enumerated. */
    static final int MAX_SETTLEMENT_CANDIDATES = 8;

    /** How long a fill or cancellation still explains a collected movement. */
    static final long COLLECTION_WINDOW_MILLIS = 2 * 60 * 60 * 1000;

    /** Bounded retention of reconciled lifecycles so recent Market context survives. */
    static final int MAX_CLOSED_RECORDS = 64;

    /**
     * Per-slot baseline of each observed offer; exact fill deltas come from its cumulative
     * counters.
     */
    Map<Integer, Snapshot> baseline = new HashMap<>();

    List<GeOffer> records = new ArrayList<>();

    /** Slots whose identity was replaced mid-life; the next placement is quarantined. */
    Set<Integer> replaced = new HashSet<>();

    /**
     * Aggregate GE cash observed with a live Collect interaction but not yet exactly attributable
     * to every sell lifecycle it may settle. Retained internally (never a visible receipt) until
     * the missing offer evidence arrives and the batch reconciles exactly, or the bounded lifecycle
     * expires and the cash fails closed to Review. Transient by design: a restart without the
     * evidence leaves the records to their own unobserved-settlement handoff, never a double
     * booking.
     */
    long cash;

    long cashAt;

    /** Earliest observation of the still-unattributed cash; the SLOT WINDOW anchor. */
    long heldAt;

    /**
     * True while the held cash is waiting for sell execution/terminal evidence (the existing long
     * hold). Its evidence completion books the exact attribution immediately, as before; a short
     * SLOT WINDOW hold instead waits for slot confirmation.
     */
    boolean unproven;

    /**
     * Transient slot evidence: a CLEARED offer or confirmed native cash depletion identifies a
     * nearby collection's lifecycle. Never persisted; cleared on restore/reset/adopt.
     */
    Map<String, Long> clearAt = new HashMap<>();

    /**
     * Transient: live lifecycles the login seed saw as an EMPTY slot. RuneLite reports every slot
     * EMPTY at login before the server restores the offers, so the same offer arriving shortly
     * after is a late replay of it, never a new offer reusing the slot.
     */
    Map<String, Long> loginAt = new HashMap<>();

    /** At most eight transient native boxes, tied to durable offer identity. */
    Map<Integer, CollectionBox> boxes = new HashMap<>();

    @AllArgsConstructor
    static class CollectionBox {
        String offer;
        long cash, quantity, execution, at;
    }

    /** Full native cash depletion supplements slot evidence; it never creates money. */
    synchronized List<Transaction> collectionBox(int slot, net.runelite.api.Item[] items, IntUnaryOperator canon,
            long at, String owner, boolean collect) {
        begin(at, owner);
        var old = boxes.remove(slot);
        var record = forSlot(slot);
        if (record == null || items == null) return rows;
        long coins = 0;
        for (var item : items) {
            if (item == null || item.getId() <= 0) continue;
            if (item.getQuantity() < 0) return rows;
            if (item.getQuantity() == 0) continue;
            if (item.getId() == COINS) coins = sum(coins, item.getQuantity());
            // Beyond max cash (30 September 2026): a sale can pay out in platinum tokens.
            else if (item.getId() == PLATINUM) coins = sum(coins, 1000L * item.getQuantity());
            else if (canon.applyAsInt(item.getId()) != record.itemId) return rows;
        }
        if (coins > 0) boxes.put(slot, new CollectionBox(record.offerId, coins,
                record.reserved(), record.pendingExecution(), now));
        long[] net = net(record);
        if (collect && coins == 0 && old != null && old.offer.equals(record.offerId)
                && record.side == SELL && old.quantity == record.reserved()
                && old.execution == record.pendingExecution() && now >= old.at
                && now - old.at <= COLLECTION_WINDOW_MILLIS && net != null
                && old.cash >= net[0] && old.cash <= net[1]) {
            clearAt.put(record.offerId, now);
            reconcilePending();
        }
        return rows;
    }

    static final long LOGIN_REPLAY_MILLIS = 2 * 60 * 1000;

    /**
     * Transient: when each offer last explained a movement (its offer id, with a "$" suffix for the
     * coin side). A placement explains one loss; a fill or cancellation explains nothing more until
     * the offer is seen again.
     */
    Map<String, Long> explained = new HashMap<>();

    /** Schema-106 pooled known-basis ledger; null keeps custody purely observational. */
    TrackedBasisLedger basis;

    // The operation in progress: each synchronized entry point sets these before it works.
    /** The operation's time. */
    long now;

    /** The session the operation books into. */
    String session = "";

    /** What the operation books: settlements and Review handoffs. */
    List<Transaction> rows = new ArrayList<>();

    /** One settle's flows not yet claimed, the parts claimed so far, and the items it may claim. */
    List<Flow> residual = new ArrayList<>();

    List<Flow> claimed = new ArrayList<>();
    IntPredicate allowed = identifier -> true;

    /** Starts an operation at {@code now}; it books into a fresh list. */
    void begin(long now, String id) {
        this.now = now;
        session = id;
        rows = new ArrayList<>();
    }

    /** Wire the pooled known-basis ledger that owns SELL reservations and BUY coverage. */
    synchronized void setBasisLedger(TrackedBasisLedger ledger) {
        basis = ledger;
    }

    /** Latest capture time of an open SELL reservation for the item; 0 when none is open. */
    synchronized long latestReservationTime(int id) {
        long latest = 0;
        for (var record : records) {
            if (record.side != SELL || record.itemId != id) continue;
            if (record.reservedQty() <= 0 && record.reservedBasis() <= 0) continue;
            long captured = record.basisCapturedAtEpochMillis > 0 ? record.basisCapturedAtEpochMillis
                            : record.placedAtEpochMillis;
            latest = max(latest, captured);
        }
        return latest;
    }

    /** Immutable result of one settle: residual flows still needing normal classification. */
    @AllArgsConstructor
    static class Partition {
        List<Flow> residualFlows;
        List<Transaction> counted, transferBookings;

        /** Physical flows consumed by custody claims; used to spend matching offer evidence. */
        List<Flow> claimedFlows;

        Transaction lastBooking() {
            List<Transaction> rows = counted.isEmpty() ? transferBookings : counted;
            return rows.isEmpty() ? null : rows.get(rows.size() - 1);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle observation
    // ---------------------------------------------------------------------------------------
    /** Apply one offer-ledger transition; returns any Review handoff or settled rows it forced. */
    synchronized List<Transaction> observeTransition(
            OfferSlots.Transition transition, String name, long now, String id) {
        begin(now, id);
        observe(transition.previous, transition.current, name);
        // New execution/lifecycle evidence may complete a settlement whose cash was observed
        // first; the held batch settles as soon as it is exactly attributable.
        reconcilePending();
        return rows;
    }

    /**
     * Applies one slot snapshot against the slot's baseline: exact incremental fills from the
     * cumulative counters, Modify Offer, slot reuse and identity replacement. Backwards counters,
     * value moved without quantity, or a missed placement are a desync, never a fill.
     */
    void observe(Snapshot previous, Snapshot current, String name) {
        int slot = current.slot;
        Side side = OfferSlots.sideOf(current.state);
        var base = baseline.remove(slot);
        if (side == null) {
            var record = touch(slot, base != null ? base : previous);
            if (record != null) {
                record.cleared = true;
                // Transient slot evidence: proves which offer a nearby collect belongs to.
                clearAt.put(record.offerId, now);
            }
            return;
        }
        var replay = base == null ? lateLoginReplay(slot, side, current) : null;
        if (replay != null) {
            loginAt.remove(replay.offerId);
            clearAt.remove(replay.offerId);
            replay.cleared = replay.collectedAway = false;
            resume(replay, current);
            baseline.put(slot, current);
            return;
        }
        boolean active = OfferSlots.isActive(current.state);
        if (base == null || OfferSlots.sideOf(base.state) != side || base.item != current.item) {
            if (base != null) {
                // Item/side changed mid-life without Modify evidence: retire the old lifecycle and
                // quarantine the next; never inherit prior basis or progress.
                var old = touch(slot, base);
                if (old != null) {
                    old.confidence = AMBIGUOUS;
                    handoff(old, msg("offer-identity"));
                }
                replaced.add(slot);
            }
            baseline.put(slot, current);
            if (base == null && previous != null && previous.state != GrandExchangeOfferState.EMPTY) {
                // A missed placement cannot be dated: quarantine the lifecycle, emit no fill.
                desync(slot, current, current, name);
                return;
            }
            place(slot, side, current, name);
            advance(slot, current, current, current.traded, current.spent);
            return;
        }
        if (active && (base.quantity != current.quantity || base.price != current.price)) {
            // A price-only Modify keeps the lifecycle and its basis; a quantity change has no exact
            // per-slice basis allocation, so realization fails closed to Review.
            boolean priceOnly = base.quantity == current.quantity;
            baseline.put(slot, new Snapshot(slot, base.state, base.item, current.quantity, base.traded,
                            priceOnly ? current.price : base.price, base.spent));
            var record = touch(slot, base);
            if (record != null && priceOnly)
                record.listedPrice = positive(current.price); else if (record != null) {
                record.offeredQty = positive(current.quantity);
                record.quantityModified = true;
            }
            return;
        }
        long quantity = current.traded - base.traded, spent = current.spent - base.spent;
        baseline.put(slot, new Snapshot(slot, base.state, base.item, base.quantity, current.traded,
                        base.price, current.spent));
        if (quantity < 0 || spent < 0 || quantity == 0 && spent > 0) {
            desync(slot, base, current, name);
            return;
        }
        advance(slot, base, current, quantity, spent);
    }

    /** A new offer in the slot: a duplicate replay of the live lifecycle changes nothing. */
    void place(int slot, Side side, Snapshot offer, String name) {
        var previous = forSlot(slot);
        if (previous != null && loginAt.containsKey(previous.offerId)) {
            // Its slot was empty at login: the old offer was collected while logged out.
            away(previous);
            previous = null;
        }
        if (previous != null && sameOffer(previous, side, offer.item, offer.quantity, offer.price)
                && !previous.cleared && !previous.isTerminal()) {
            previous.lastSeenAtEpochMillis = now;
            return;
        }
        if (previous != null && previous.stage == OPEN && previous.reconciled()) {
            // Settled but not yet sealed (a settlement completed by an offer transition):
            // seal it exactly as the next settle would, so it stays as history.
            previous.stage = CLOSED;
            previous = null;
        }
        if (previous != null && previous.cleared && previous.isTerminal() && trusted(previous, previous.side)) {
            // Owner 1.2: a finished offer collected moments before its slot is reused still waits
            // for
            // its items or coins to settle; it gives up the slot instead of going to Review.
            previous.slot = -1;
            previous = null;
        }
        if (previous != null)
            // Slot reuse: a new offer never inherits prior basis or progress.
            handoff(previous, msg("slot-reused"));
        // A settled (CLOSED) lifecycle in this slot stays as bounded market history for its
        // booked sale; only its unrealized reservation leaves with the slot.
        releaseRemaining(latestClosed(slot));
        var record = newRecord(offer, name, offer.state);
        if (replaced.remove(slot)) record.confidence = AMBIGUOUS;
        // Per-fill tax is exact only for a placement observed live from an empty slot.
        record.fillTaxExact = side == SELL && record.confidence == CustodyConfidence.CONFIRMED;
        records.add(record);
    }

    /** Exact fill progress, then the terminal state (a final fill lands before a cancellation). */
    void advance(int slot, Snapshot offer, Snapshot current, long quantity, long spent) {
        boolean fill = quantity > 0 && spent >= 0, terminal = OfferSlots.isTerminal(current.state);
        var record = fill || terminal ? touch(slot, offer) : null;
        if (record != null && fill) recordFill(record, quantity, spent);
        if (record != null && terminal) record.offerState = current.state.name();
    }

    /** Counters with no exact lineage: quarantine the lifecycle, or start an unbased one. */
    void desync(int slot, Snapshot offer, Snapshot current, String name) {
        var record = touch(slot, offer);
        if (record != null) {
            record.confidence = AMBIGUOUS;
            record.fillTaxExact = false;
        } else if (offer.item > 0) {
            record = newRecord(offer, name, current.state);
            record.confidence = LEGACY_UNBASED;
            records.add(record);
        }
    }

    /**
     * The live lifecycle the login seed cleared in this slot, when this snapshot is that same
     * offer.
     */
    GeOffer lateLoginReplay(int slot, Side side, Snapshot current) {
        var record = forSlot(slot);
        Long clear = record == null ? null : loginAt.get(record.offerId);
        return clear != null && now - clear <= LOGIN_REPLAY_MILLIS && sameOffer(
                                record, side, current.item, current.quantity, current.price)
                && !restarted(record, current) ? record : null;
    }

    /** A re-listed identical offer restarts its counters; it is never the saved lifecycle. */
    static boolean restarted(GeOffer record, Snapshot snapshot) {
        return snapshot.traded < record.filledQty || record.isTerminal() && OfferSlots.isActive(snapshot.state);
    }

    /**
     * Resume a persisted lifecycle; progress made while away is adopted once, never as a fill. Its
     * confidence stays as it was: a relog never makes a quarantined or unbased offer trusted.
     */
    void resume(GeOffer existing, Snapshot snapshot) {
        existing.lastSeenAtEpochMillis = now;
        existing.offerState = snapshot.state.name();
        if (snapshot.traded != existing.filledQty)
            // Fills while logged out were never observed at their own prices.
            existing.fillTaxExact = false;
        existing.filledQty = snapshot.traded;
        existing.spentGp = positive(snapshot.spent);
    }

    /** A new lifecycle for the offer in this snapshot's slot, last seen in {@code state}. */
    GeOffer newRecord(Snapshot offer, String name, GrandExchangeOfferState state) {
        var record = new GeOffer(uuid(), offer.slot, OfferSlots.sideOf(offer.state), offer.item,
                        name, offer.quantity, offer.price, now, string(session));
        record.lastSeenAtEpochMillis = now;
        record.offerState = state.name();
        return record;
    }

    /**
     * Record the login slot baseline: resume a persisted matching lifecycle, quarantine active
     * offers first seen now as legacy/unbased, and hand off records whose slot changed identity.
     */
    synchronized List<Transaction> seedSlots(Map<Integer, Snapshot> snapshots, long now, String id) {
        begin(now, id);
        baseline.clear();
        boxes.clear();
        loginAt.clear();
        for (var snapshot : snapshots.values()) {
            var existing = forSlot(snapshot.slot);
            if (snapshot.state == GrandExchangeOfferState.EMPTY) {
                // An offer cleared here before the logout or hop still settles here.
                if (existing == null) existing = latestClosed(snapshot.slot);
                else if (!existing.cleared || existing.collectedAway) {
                    existing.collectedAway = true;
                    loginAt.put(existing.offerId, now);
                }
                if (existing != null) {
                    existing.cleared = true;
                    existing.lastSeenAtEpochMillis = now;
                    clearAt.put(existing.offerId, now);
                }
                continue;
            }
            Side side = OfferSlots.sideOf(snapshot.state);
            if (side == null) continue;
            // Pre-existing progress is only a baseline, never replayed as a fill.
            baseline.put(snapshot.slot, snapshot);
            if (existing != null && sameOffer(existing, side, snapshot.item, snapshot.quantity,
                            snapshot.price) && !restarted(existing, snapshot)) {
                resume(existing, snapshot);
                continue;
            }
            if (existing != null) away(existing);
            var record = newRecord(snapshot, "", snapshot.state);
            record.confidence = LEGACY_UNBASED;
            record.filledQty = snapshot.traded;
            record.spentGp = positive(snapshot.spent);
            records.add(record);
        }
        return rows;
    }

    /**
     * True when every moved flow is explained by a recent offer: an item or coins lost shortly
     * after a sell or buy was placed, an item or coins gained after a buy or sell filled, or the
     * other side's cancellation. Custody is authoritative for GE economics, so a movement it could
     * not claim but an offer explains is Grand Exchange activity for Review, never counted.
     */
    synchronized boolean explains(List<Flow> flows, long now) {
        this.now = now;
        boolean moved = false;
        for (Flow flow : flows) {
            if (flow.quantity != 0) {
                moved = true;
                if (explaining(flow) == null) return false;
            }
        }
        return moved;
    }

    /** Spends the offer evidence behind these movements: one offer side explains one movement. */
    synchronized void spend(List<Flow> flows, long now) {
        this.now = now;
        for (Flow flow : flows) {
            String key = flow.quantity == 0 ? null : explaining(flow);
            if (key != null) explained.put(key, now);
        }
    }

    String explaining(Flow flow) {
        boolean coins = flow.item == COINS;
        for (var record : records) {
            boolean buy = record.side == BUY;
            String key = record.offerId + (coins ? "$" : "");
            Long spent = explained.get(key);
            long seen = record.lastSeenAtEpochMillis;
            // Only a live placement moves coins (buy) or items (sell) out of the inventory; a
            // finished
            // fill of the same side or a cancellation of the other brings them back. Passive
            // progress on an open offer explains nothing.
            boolean fits = flow.cost() ? buy == coins && spent == null && !record.unbased() && !record.isCancelled()
                                    && now - record.placedAtEpochMillis <= PLACEMENT_WINDOW_MILLIS
                            : (spent == null || spent < seen) && now - seen <= COLLECTION_WINDOW_MILLIS && (buy != coins
                                            ? record.filledQty > 0 && record.isTerminal() : record.isCancelled());
            if (fits && (coins || record.itemId == flow.item)) return key;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Flow-level custody claims and canonical realization
    // ---------------------------------------------------------------------------------------
    /**
     * Claim custody flows from one stabilized settle. {@code itemAllowed} excludes items already
     * owned by a stronger per-item evidence source (consume/drop/loot) without weakening the
     * whole-transaction safety gates. {@code collectionEvidence} is a bounded player Collect
     * interaction; without it, collection claims need an observed terminal offer state.
     */
    synchronized Partition partition(List<Flow> incoming, long now, String id, IntPredicate itemAllowed,
            boolean collection) {
        begin(now, id);
        var transfers = new ArrayList<Transaction>();
        residual = new ArrayList<>(incoming);
        claimed = new ArrayList<>();
        allowed = itemAllowed;
        // A settlement held from an earlier collect may now be exactly attributable.
        reconcilePending();
        claimSellPlacements();
        claimBuyReserves();
        if (collection) {
            var gains = incoming(residual, allowed);
            for (var record : records)
                if (gains.containsKey(record.itemId) && (record.side == BUY || record.isCancelled())
                        || gains.containsKey(COINS) && (record.side == SELL && record.reserved() > 0
                                || record.side == BUY && record.isCancelled())) collected(record);
        }
        claimBuyRefunds(collection);
        claimSellReturns();
        claimBuyCollections(collection);
        claimSellSettlements(collection);
        if (!claimed.isEmpty())
            transfers.add(new Transaction(now, TransactionType.TRANSFER, Context.TRANSFER,
                            msg("ge-custody"), "Market", false, new ArrayList<>(claimed),
                            CONFIRMED, msg("ge-neutral"), null));
        // A hold created by this settle retries immediately: a batch whose evidence is complete
        // fails closed at once instead of waiting invisibly for a later event.
        reconcilePending();
        for (var open : records) {
            if (open.stage == OPEN && open.reconciled()) open.stage = CLOSED;
        }
        return new Partition(residual, rows, transfers, claimed);
    }

    /** Prune reconciled lifecycles, expire terminal-unobserved ones and cap stale records. */
    synchronized List<Transaction> maintenance(long now, String id) {
        begin(now, id);
        prune();
        // A held settlement retries and, once its evidence is exhausted or its bounded
        // lifecycle expires, fails closed to Review with the observed cash preserved.
        reconcilePending();
        return rows;
    }

    void prune() {
        int closed = 0;
        for (var record : records) {
            if (record.stage == CLOSED) closed++;
        }
        // Settled history is bounded: the oldest closed lifecycles leave first.
        for (; closed > MAX_CLOSED_RECORDS; closed--) {
            var first = oldestClosed();
            releaseRemaining(first);
            records.remove(first);
        }
        for (int index = records.size() - 1; index >= 0; index--) {
            var record = records.get(index);
            if (record.stage == CLOSED) continue;
            if (record.reconciled()) {
                record.stage = CLOSED;
                continue;
            }
            // Expire by grace only a slot that is CLEARED and whose cash was never seen (a
            // collect-to-bank and similar). A SELL whose slot still shows the terminal state is
            // still in GE custody: its coins may be collected much later, so it must not expire by
            // time. BUY keeps its existing pattern (reported, not changed).
            boolean clear = record.cleared;
            // Owner 2026-10-09: Collect to bank (or a collect while GP Manager was away) settles
            // from
            // the offer itself instead of waiting for Review; held collect cash may still be its
            // own.
            if (clear && now - record.lastSeenAtEpochMillis > LOGIN_REPLAY_MILLIS
                    && loginAt.containsKey(record.offerId)) {
                away(record);
                continue;
            }
            if (clear && !loginAt.containsKey(record.offerId)
                    && now - record.lastSeenAtEpochMillis > BANK_COLLECT_MILLIS && cash <= 0
                    && banked(record))
                continue;
            boolean buyTerminal = record.side != SELL && record.isTerminal() && !record.unbased();
            if ((clear || buyTerminal) && now - record.lastSeenAtEpochMillis > UNOBSERVED_GRACE_MILLIS)
                handoff(record, msg("settlement-observed"));
        }
        clearAt.values().removeIf(at -> now - at > UNOBSERVED_GRACE_MILLIS);
        // Spent offer evidence goes with its offer.
        var live = new HashSet<String>();
        for (var record : records) live.add(record.offerId);
        explained.keySet().removeIf(key -> !live.contains(key.replace("$", "")));
    }

    // -- Custody claims: each settle offers its flows to the lifecycles, oldest placement first --
    /** One lifecycle's claim on a flow: the magnitude it takes, or 0 to pass. */
    interface ClaimStep {
        long claim(GeOffer record, Flow flow, long remaining);
    }

    /** Offers one side's trusted lifecycles a gain or loss of coins or items, oldest first. */
    void claimFlows(Side side, boolean gain, boolean coins, ClaimStep step) {
        claim(residual, flow -> (gain ? flow.gain() : flow.cost())
                                && (flow.item == COINS) == coins && (coins || allowed.test(flow.item)),
                (flow, size) -> {
                    long remaining = size;
                    for (var record : byPlacement()) {
                        if (remaining > 0 && trusted(record, side) && (coins || record.itemId == flow.item))
                            remaining -= positive(step.claim(record, flow, remaining));
                    }
                    return size - remaining;
                });
    }

    /** A lifecycle observed from its placement with no identity desync. */
    static boolean trusted(GeOffer record, Side side) {
        return record.side == side && !record.unbased() && record.confidence != AMBIGUOUS;
    }

    /** SELL placement principal (item loss). */
    void claimSellPlacements() {
        claimFlows(SELL, false, false, (record, flow, remaining) -> {
                    long claim = min(remaining, record.offeredQty);
                    if (record.isCancelled() || record.cleared || record.capturedQty > 0
                            || now - record.placedAtEpochMillis > PLACEMENT_WINDOW_MILLIS || claim <= 0)
                        return 0;
                    captureBasis(record, flow);
                    if (basis != null) {
                        var reservation = basis.reserve(record.itemId, claim, now);
                        record.reservedTrackedQty = positive(sum(record.reservedTrackedQty, reservation.trackedQty));
                        record.reservedTrackedBasisGp =
                                positive(sum(record.reservedTrackedBasisGp, reservation.basisGp));
                    }
                    record.capturedQty = claim;
                    record.lastSeenAtEpochMillis = now;
                    claimed.add(flow.part(claim));
                    return claim;
                });
    }

    /** BUY observed reserve (coin loss). */
    void claimBuyReserves() {
        var gainItems = incoming(residual, ANY).keySet();
        claimFlows(BUY, false, true, (record, flow, remaining) -> {
                    long reserve = positive(record.offeredQty * record.listedPrice);
                    // Owner 1.2: an offer placed, bought and collected inside one settle shows only
                    // its exact spend, the reserve with its change already back; the purchase books
                    // it, never Review.
                    boolean instant = gainItems.contains(record.itemId) && record.isTerminal()
                                    && remaining == record.spentGp;
                    long claim = instant ? remaining : min(remaining, reserve);
                    // A same-item acquisition alongside the coin loss is a purchase, not a reserve:
                    // leave the mixed movement to the normal classification path. An offer that
                    // filled at once still took these coins; only a cancelled one kept none.
                    if (record.isCancelled() || record.cleared && !instant || record.reserveGp > 0
                            || now - record.placedAtEpochMillis > PLACEMENT_WINDOW_MILLIS
                            || !instant && gainItems.contains(record.itemId) || claim <= 0)
                        return 0;
                    record.reserveGp = instant ? reserve : positive(claim);
                    if (instant) record.refundedGp = positive(reserve - claim);
                    record.lastSeenAtEpochMillis = now;
                    claimed.add(flow.part(claim, 1, FACE_VALUE));
                    return claim;
                });
    }

    /** BUY refund (coin gain). */
    void claimBuyRefunds(boolean collection) {
        var items = incoming(residual, ANY);
        claimFlows(BUY, true, true, (record, flow, remaining) -> {
                    boolean terminal = record.isTerminal() || record.cleared;
                    long refundRoom = positive((terminal ? record.offeredQty : record.filledQty)
                                    * record.listedPrice - record.spentGp) - record.refundedGp;
                    boolean collected = collection && !record.quantityModified
                                    && (items.getOrDefault(record.itemId, 0L) >= record.reserved()
                                            && record.reserved() > 0 || remaining == refundRoom
                                            && record.collectedQty == record.filledQty
                                            && abs(now - record.lastSeenAtEpochMillis) <= 2 * SLOT_WINDOW_MILLIS);
                    // An offer paid from the bank (the GE draws there once the pack runs out) never
                    // showed its reserve, yet its change still lands in the pack: as the slot
                    // clears, up to the exact change owed is the player's own coins coming back
                    // (owner live 2026-10-16: two bank-paid buys sent their change to Review).
                    Long clear = clearAt.get(record.offerId);
                    boolean bankPaid = record.reserveGp == 0 && (collected || clear != null
                                    && abs(now - clear) <= 2 * SLOT_WINDOW_MILLIS);
                    long claim = min(remaining, min(positive(refundRoom),
                                            bankPaid ? refundRoom : record.reserveOutstanding()));
                    // A live collection can return price improvement on filled units while the
                    // unfilled reserve stays in custody. Terminal offers may return all change.
                    if (!(terminal || collected) || claim <= 0) return 0;
                    record.refundedGp = positive(sum(record.refundedGp, claim));
                    claimed.add(flow.part(claim, 1, FACE_VALUE));
                    return claim;
                });
    }

    /** SELL cancellation return (item gain). */
    void claimSellReturns() {
        claimFlows(SELL, true, false, (record, flow, remaining) -> {
                    long claim = min(remaining, record.heldQty());
                    if (!(record.isCancelled() || record.cleared) || claim <= 0) return 0;
                    releaseShare(record, claim);
                    record.returnedQty = sum(record.returnedQty, claim);
                    // The returned principal is neutral at its frozen custody basis, never the
                    // current live quote; with no frozen basis it stays the observed movement.
                    claimed.add(record.basisUnitPrice > 0 ? flow.part(
                                            claim, record.basisUnitPrice, record.getBasisSource()) : flow.part(claim));
                    return claim;
                });
    }

    /** BUY collection (item gain). */
    void claimBuyCollections(boolean collection) {
        claimFlows(BUY, true, false, (record, flow, remaining) -> {
                    long claim = min(remaining, record.reserved());
                    // Passive progress alone never turns a same-item delta into a collection.
                    if (record.quantityModified || claim <= 0
                            || !(collection || record.isTerminal() || record.cleared))
                        return 0;
                    if (record.basisSource == null) captureBasis(record, flow);
                    record.collectedQty = positive(sum(record.collectedQty, claim));
                    record.collectedValueGp = sum(record.collectedValueGp,
                                    multiply(claim, flow.unitPrice));
                    record.lastSeenAtEpochMillis = now;
                    claimed.add(flow.part(claim));
                    if (record.collectedQty < record.filledQty)
                        // Partial collection: execution chunks are not attributable; fail closed.
                        record.collectionAmbiguous = true; else
                        bookBuySettlement(record);
                    return claim;
                });
    }

    void bookBuySettlement(GeOffer record) {
        long quantity = record.collectedQty - record.settledQty, execution = record.pendingExecution();
        if (quantity <= 0) return;
        boolean clean = cleanSettlement(record);
        var flows = new ArrayList<Flow>(2);
        // Exact NEW-model BUY: the asset value equals the actual settled spend, so acquisition is
        // Net-neutral and the acquired quantity becomes known coverage at that exact spend.
        flows.add(itemFlow(record, quantity, execution / quantity, execution));
        flows.add(coins(-execution));
        settled(record, buildMarket(flows, clean, clean, msg("ge-purchase")), quantity, execution,
                execution);
        record.collectedQty = positive(record.settledQty);
        record.collectedValueGp = 0;
    }

    Flow coins(long amount) {
        return new Flow(COINS, "Coins", amount, 1, amount, FACE_VALUE, now);
    }

    /** One flow of the offer's item, carrying its frozen basis source and capture time. */
    static Flow itemFlow(GeOffer record, long quantity, long price, long value) {
        return new Flow(record.itemId, record.itemName, quantity, price, value, record.getBasisSource(),
                record.basisCapturedAtEpochMillis);
    }

    /** An uncounted Market row left for Review: the observed movement, never a guess. */
    Transaction reviewRow(List<Flow> flows, String reason) {
        return new Transaction(now, UNCERTAIN, MARKET, "Grand Exchange", "Market", false, flows, Confidence.UNCERTAIN,
                reason, null);
    }

    /** Claims a whole coin gain as held or settled GE cash. */
    long claimWhole(Flow flow) {
        claimed.add(flow.part(flow.value, 1, FACE_VALUE));
        return flow.value;
    }

    // -- SELL collection (coin gain) ----------------------------------------------------------
    /**
     * Attribute one observed collect coin movement to exactly the sold offers it proves.
     *
     * <p>Exact integer equality only: a movement settles a subset when the subset's observed
     * after-tax nets sum to it exactly. The slot-clear set is the primary proof; otherwise the
     * unique matching subset is confirmed by slot evidence within the SLOT WINDOW or, with no
     * contradicting evidence, at the window end. Ties and unprovable evidence fail closed: only
     * slot-evidenced candidates may be quarantined, and every other candidate stays pending for a
     * later exact collect.
     */
    void claimSellSettlements(boolean collection) {
        if (!collection) {
            // A coin gain with no Collect interaction settles a waiting sale only when it is
            // that whole sale, less at most its tax.
            claimSales();
            return;
        }
        claim(residual, flow -> flow.gain() && flow.item == COINS, (flow, size) -> {
                    long cash = flow.value;
                    var options = settlementCandidates(true);
                    // Coins may arrive before the offer's execution/terminal evidence. A live
                    // Collect interaction with a captured sell lifecycle still pending is that
                    // settlement's cash; with no candidate yet it is held below (an empty set
                    // attributes to NONE) instead of letting an unresolved Coins row reach Review.
                    // The bounded hold retries as offer evidence arrives.
                    if (options.isEmpty() && (cash <= 0 || !pendingSell()))
                        return 0;
                    var owner = attribute(options, cash, now);
                    if (owner.kind == SETTLE) {
                        settleAttributed(owner.records, cash);
                        return claimWhole(flow);
                    }
                    if (!owner.contradicted) {
                        // Exact-but-unconfirmed, tied, or unmatched evidence: retain the observed
                        // cash
                        // transiently. The SLOT WINDOW gathers the slot evidence a fail-closed
                        // decision
                        // needs; while a sell can still receive execution evidence, the existing
                        // long hold
                        // keeps waiting for it.
                        holdCash(cash);
                        unproven |= owner.kind == AttributionKind.NONE
                                        && pendingSell();
                        return claimWhole(flow);
                    }
                    // No exact attribution: only candidates with slot evidence in this interaction
                    // may be
                    // quarantined. The unattributed movement follows the ordinary path to one
                    // uncounted
                    // Review Coins row; every other candidate stays pending for a later exact
                    // collect.
                    quarantineAmbiguous(owner.quarantine);
                    return 0;
                });
    }

    /**
     * Booking for a coin gain with no Collect interaction (the click fell outside its window): only
     * a gain between the whole waiting sale and that sale less its largest possible tax settles it.
     */
    void claimSales() {
        claim(residual, flow -> flow.gain() && flow.item == COINS, (flow, size) -> {
                    var options = settlementCandidates(false);
                    long totalExecution = 0;
                    for (var record : options)
                        totalExecution = sum(totalExecution, record.pendingExecution());
                    long original = flow.value;
                    // Without a Collect click only the whole waiting sale, less at most its GE
                    // tax (2%), can explain the coins: an alch or a shop sale never claims it.
                    if (original > totalExecution || original < totalExecution - totalExecution / 50) return 0;
                    long claimCash = min(original, totalExecution);
                    if (claimCash <= 0) return 0;
                    if (options.size() > 1) {
                        boolean clean = true, allKnownCoverage = true;
                        long tax = 0;
                        for (var record : options) {
                            clean &= cleanSettlement(record);
                            allKnownCoverage &= record.reservedQty() >= record.reserved();
                            // Candidate fee only: the aggregate observed cash must confirm the
                            // exact combined gap.
                            tax = sum(tax, positive(pendingSellTax(record)));
                        }
                        boolean taxAttributed = tax > 0 && totalExecution - claimCash == tax;
                        if (!taxAttributed && (tax > 0 || claimCash != totalExecution) && !allKnownCoverage) {
                            clean = false;
                            for (var record : options) record.collectionAmbiguous = true;
                        }
                        // An exact batch books each offer's after-tax share of the observed cash;
                        // any
                        // other batch books gross execution and an adjustment row for the
                        // difference.
                        long allocated = 0;
                        for (var record : options) {
                            long quantity = record.reserved(), execution = record.pendingExecution();
                            if (quantity > 0 && execution > 0) {
                                long cash = taxAttributed ? execution - positive(pendingSellTax(record)) : execution;
                                bookSellSettlement(record, quantity, cash, execution, clean);
                                allocated = sum(allocated, cash);
                            }
                        }
                        if (claimCash != allocated)
                            // Legacy (non-collect) batch adjustment row; collect interactions never
                            // produce one.
                            rows.add(new Transaction(now, clean ? TRADE : UNCERTAIN, MARKET,
                                            msg("ge-settlement"), "Market", clean,
                                            singletonList(coins(claimCash - allocated)),
                                            clean ? CONFIRMED : Confidence.UNCERTAIN,
                                            msg("ge-batch"), null));
                    } else {
                        var record = options.get(0);
                        long execution = record.pendingExecution();
                        boolean over = original > execution;
                        record.collectionAmbiguous = record.collectionAmbiguous || over;
                        bookSellSettlement(record, record.reserved(), over ? execution : claimCash, execution,
                                !over && cleanSettlement(record));
                    }
                    claimed.add(flow.part(claimCash, 1, FACE_VALUE));
                    return claimCash;
                });
    }

    /**
     * Book one exactly attributed subset at each offer's own observed after-tax cash. A sale that
     * filled at several prices in one observed chunk has a tax range, not one tax: the observed
     * cash proves the subset's total, so each such offer pays its lowest possible tax plus, in
     * order, the rest up to its highest (owner 2026-10-08: a big mixed-price sale is no Review).
     */
    void settleAttributed(List<GeOffer> records, long cash) {
        boolean clean = true;
        for (var record : records) {
            clean &= cleanSettlement(record);
            long[] net = net(record);
            if (net != null) cash -= net[1];
        }
        // The tax beyond every offer's lowest possible tax.
        cash = -cash;
        for (var record : records) {
            long[] net = net(record);
            // An offer with no execution is never part of a match; the attribution excluded it.
            if (net == null) continue;
            long execution = record.pendingExecution(), extra = positive(min(net[1] - net[0], cash));
            cash -= extra;
            long tax = execution - net[1] + extra;
            if (pendingSellTax(record) < 0) {
                // The cash proved it: later reads (the receipt, a restart) see this exact tax.
                record.fillTaxExact = true;
                record.fillTaxGp = sum(record.settledTaxGp, tax);
            }
            bookSellSettlement(record, record.reserved(), execution - tax, execution, clean);
        }
    }

    /**
     * The pending execution's possible after-tax cash {lowest, highest}, or null with no execution.
     * Exact when its tax is proven; otherwise each item paid price/50 rounded down, so the stack
     * paid at most gross/50 and at least (gross - 49 per item)/50 (below the 5M per-item cap).
     */
    static long[] net(GeOffer record) {
        long execution = record.pendingExecution(), quantity = record.reserved(), tax = pendingSellTax(record);
        if (execution <= 0 || quantity <= 0) return null;
        if (tax >= 0) return new long[] {execution - tax, execution - tax};
        long least = execution >= 250_000_000 ? 0 : positive((execution - 49 * quantity + 49) / 50);
        return new long[] {execution - execution / 50, execution - least};
    }

    /**
     * Fail closed on the slot-evidenced candidates only. The quarantine booking keeps today's shape
     * (uncounted, collectionAmbiguous, the booked allocation recorded) while the observed cash
     * itself is never assigned to a lifecycle it was not proven part of.
     */
    void quarantineAmbiguous(List<GeOffer> eligible) {
        for (var record : eligible) {
            long quantity = record.reserved(), execution = record.pendingExecution();
            if (quantity > 0 && execution > 0) {
                record.collectionAmbiguous = true;
                bookSellSettlement(record, quantity, execution, execution, false);
            }
        }
    }

    /** Candidate sell lifecycles for one coin gain; the retry needs durable terminal evidence. */
    List<GeOffer> settlementCandidates(boolean collection) {
        return matching(byPlacement(), record -> record.side == SELL && record.reserved() > 0
                && record.capturedQty > 0 && record.settleable() >= record.reserved() && !record.unbased()
                && (collection || record.isTerminal() || record.cleared));
    }

    /** Retain observed collect cash transiently; the SLOT WINDOW anchors on the first hold. */
    void holdCash(long cash) {
        if (cash <= 0) return;
        if (this.cash <= 0) heldAt = now;
        this.cash = sum(this.cash, cash);
        cashAt = max(cashAt, now);
    }

    /** Exact attribution of one collect coin movement over the current candidate set. */
    Attribution attribute(List<GeOffer> options, long cash, long coinAt) {
        int size = options.size();
        var eligible = new ArrayList<GeOffer>();
        if (size <= 0 || size > MAX_SETTLEMENT_CANDIDATES)
            return new Attribution(AttributionKind.NONE, eligible, eligible, false);
        // Each candidate's possible after-tax cash: one value, or a range for a mixed-price fill.
        long[][] nets = new long[size][];
        var clear = new ArrayList<Integer>();
        for (int index = 0; index < size; index++) {
            var record = options.get(index);
            nets[index] = net(record);
            Long clearedAt = clearAt.get(record.offerId);
            // The candidate's slot cleared within the SLOT WINDOW of the coin gain.
            if (clearedAt != null && abs(clearedAt - coinAt) <= SLOT_WINDOW_MILLIS) {
                clear.add(index);
                eligible.add(record);
            }
        }
        // Apply slot evidence only after enumerating every subset within the eight-offer bound.
        var matches = matchingSubsets(nets, cash, 1 << size);
        List<Integer> match = null;
        var kind = AttributionKind.NONE;
        boolean conflicts = false;
        if (matches.contains(clear)) {
            // §2.1: the slot-clear set is the primary proof; a single candidate has no competing
            // explanation either, so neither needs slot confirmation (an amount it cannot have
            // received, or an execution with no known price, still fails closed below).
            kind = SETTLE;
            match = clear;
        } else if (size == 1 && !matches.isEmpty()) {
            kind = SETTLE;
            match = matches.get(0);
        } else if (matches.size() > 1) {
            // §2.3: slot evidence may still select exactly one matching subset.
            matches.removeIf(subset -> !subset.containsAll(clear));
            conflicts = !clear.isEmpty() && matches.isEmpty();
            kind = conflicts ? AttributionKind.NONE
                            : matches.size() == 1 ? SETTLE : TIE;
            match = matches.size() == 1 ? matches.get(0) : null;
        } else if (matches.size() == 1) {
            // A unique match settles when the slot-clear set is exactly it, needs confirmation
            // while
            // no slot outside it cleared, and §2.2: a slot cleared outside it contradicts it.
            match = matches.get(0);
            conflicts = !match.containsAll(clear);
            kind = match.equals(clear) ? SETTLE
                            : conflicts ? AttributionKind.NONE : CONFIRM;
        }
        var records = new ArrayList<GeOffer>();
        for (int index : match == null || conflicts ? Collections.<Integer>emptyList() : match)
            records.add(options.get(index));
        return new Attribution(kind, records, eligible, conflicts);
    }

    /**
     * Subset enumeration over candidates with a known execution: a subset matches when the cash
     * lies within its summed {lowest, highest} after-tax cash. Saturating sums, deterministic index
     * order, at most {@code limit} matches. Returns empty when the candidate bound is exceeded, so
     * an over-full interaction can never be enumerated.
     */
    static List<List<Integer>> matchingSubsets(long[][] nets, long cash, int limit) {
        int size = nets == null ? 0 : nets.length;
        var matches = new ArrayList<List<Integer>>();
        if (size <= 0 || size > MAX_SETTLEMENT_CANDIDATES)
            return matches;
        for (int mask = 1; mask < 1 << size && matches.size() < limit; mask++) {
            long least = 0, most = 0;
            var subset = new ArrayList<Integer>();
            for (int index = 0; index < size && least >= 0; index++) {
                if ((mask & 1 << index) != 0) {
                    subset.add(index);
                    least = nets[index] == null ? -1 : sum(least, nets[index][0]);
                    most = nets[index] == null ? most : sum(most, nets[index][1]);
                }
            }
            if (least >= 0 && least <= cash && cash <= most) matches.add(subset);
        }
        return matches;
    }

    /** Attribution outcome kinds; names are conceptual, not persisted. */
    enum AttributionKind {
        /** An exact subset is proven and may be settled now. */
        SETTLE,
        /** The unique exact subset needs slot confirmation (or the window end). */
        CONFIRM,
        /** More than one exact subset matches; slot evidence must select one. */
        TIE,
        /** No exact attribution exists. */
        NONE
    }

    @AllArgsConstructor
    static class Attribution {
        AttributionKind kind;
        List<GeOffer> records, quarantine;
        boolean contradicted;
    }

    /** True while an observed sell lifecycle can still receive execution/terminal evidence. */
    boolean pendingSell() {
        for (var record : records) {
            if (record.side == SELL && record.capturedQty > 0 && !record.unbased() && !record.isTerminal()
                    && !record.cleared)
                return true;
        }
        return false;
    }

    /**
     * Retry a held collect under the one attribution law. An exact subset settles as soon as the
     * evidence (or the SLOT WINDOW) confirms it; a tie, a contradiction, or exhausted evidence
     * fails closed to Review, never distributed by guesswork.
     */
    void reconcilePending() {
        if (cash <= 0) return;
        // The held cash IS a collect interaction, so the retry sees the same candidate set that
        // interaction saw (still-active sells whose fills may still be collected included).
        Attribution owner = attribute(settlementCandidates(true), cash,
                        heldAt);
        boolean windowClosed = now - heldAt > SLOT_WINDOW_MILLIS;
        // §2.2b: a unique match settles at the window end with no contradicting slot evidence; the
        // existing long hold also books as soon as its missing execution evidence completes.
        if (owner.kind == SETTLE || owner.kind == CONFIRM
                        && (windowClosed || unproven)) {
            settleAttributed(owner.records, cash);
            clearPending();
            return;
        }
        // §2.2 contradiction, §2.3 an unresolved tie, and §2.4/§3 exhausted evidence fail closed:
        // only slot-evidenced candidates are quarantined and the held cash goes to Review.
        if (owner.contradicted || now - cashAt > UNOBSERVED_GRACE_MILLIS
                || owner.kind == TIE && windowClosed || owner.kind == AttributionKind.NONE
                        && (unproven ? !pendingSell() : windowClosed)) {
            quarantineAmbiguous(owner.quarantine);
            releasePendingSettlement();
        }
    }

    /** Releases a held collect to Review at the time it was held; returns that row. */
    synchronized List<Transaction> releaseHeldCollect() {
        begin(cashAt, session);
        releasePendingSettlement();
        return rows;
    }

    /** Fail closed: the observed cash stays visible as an uncounted Review row, never guessed. */
    void releasePendingSettlement() {
        long held = cash;
        clearPending();
        if (held <= 0) return;
        rows.add(reviewRow(singletonList(coins(held)), msg("ge-collection")));
    }

    void bookSellSettlement(GeOffer record, long quantity, long cash, long execution, boolean clean) {
        long remaining = record.reservedQty(), remainingBasis = record.reservedBasis();
        long known = min(quantity, remaining), basis = BasisMath.shareOf(remaining, remainingBasis, known);
        long proceeds = BasisMath.shareOf(quantity, cash, known);
        boolean fullyUnknown = known <= 0;
        record.consumedTrackedQty = sum(record.consumedTrackedQty, known);
        record.consumedTrackedBasisGp = sum(record.consumedTrackedBasisGp, basis);
        if (this.basis != null)
            // The realized quantity left ownership even when it carried no known coverage; the
            // per-item realization fence always advances.
            this.basis.fenceRealized(record.itemId, now);
        // The one shared accepted-tax predicate: this settlement's observed gross execution against
        // its actual Received cash. Only an exact per-item match counts; the tax is never inferred
        // from a reference, a guide price or a difference.
        long tax = pendingSellTax(record);
        long acceptedTax = fullyUnknown && clean && tax > 0 && execution - cash == tax ? tax
                        : 0;
        boolean costOnly = acceptedTax > 0;
        var flows = new ArrayList<Flow>(2);
        if (costOnly) {
            // Fully unknown prior value with a PROVEN tax: the departing item flow is valued at
            // the observed gross execution (not at the frozen reference) against the observed
            // Received cash, so the counted automatic Net is exactly -accepted tax — a market
            // trade cost. Result stays unknown: the accumulator below receives no result here, and
            // a later correction replaces this contribution instead of stacking on it.
            long unit = execution / quantity;
            flows.add(itemFlow(record, -quantity, unit, -execution));
            flows.add(coins(cash));
        } else if (fullyUnknown) {
            // Unknown coverage without a proven tax: the full observed movement stays as
            // liquidation evidence and is Net-neutral. It is never a fake cost and never
            // automatic profit.
            flows.add(itemFlow(record, -quantity, record.basisUnitPrice, -record.basisFor(quantity)));
            flows.add(coins(cash));
        } else {
            if (basis > 0) {
                long unit = known > 0 ? basis / known : 0;
                flows.add(itemFlow(record, -quantity, unit, -basis));
            }
            flows.add(coins(proceeds));
        }
        String reason = costOnly ? msg("sale-proven") : fullyUnknown
                                ? msg("sale-settled") : known < quantity
                                        ? msg("sale-quantity") : msg("grand-exchange-sale");
        // Counted when the lifecycle is exact AND either the realized quantity carries known
        // coverage
        // or the actual GE tax is proven. An unknown sale without a proven tax stays an exact,
        // uncounted evidence row (liquidation cash retained, Net-neutral, never a Review nag).
        if (tax >= 0) record.settledTaxGp = positive(sum(record.settledTaxGp, tax));
        record.realizedResultGp = sum(record.realizedResultGp, proceeds - basis);
        settled(record, buildMarket(flows, clean, clean && (!fullyUnknown || costOnly), reason), quantity,
                execution, cash);
    }

    /** Books one settlement row and advances the offer's settled counters by what it booked. */
    void settled(GeOffer record, Transaction row, long quantity, long execution, long cash) {
        rows.add(row);
        record.settledQty = sum(record.settledQty, quantity);
        record.settledExecutionGp = sum(record.settledExecutionGp, execution);
        record.settledCashGp = sum(record.settledCashGp, cash);
        record.setSettlementId(row.id());
    }

    /** Return an unconsumed reservation share when custody returns or retires a lifecycle. */
    void releaseShare(GeOffer record, long returnedQty) {
        if (returnedQty <= 0) return;
        long remaining = record.reservedQty(), remainingBasis = record.reservedBasis();
        if (remaining <= 0 && remainingBasis <= 0) return;
        long released = min(returnedQty, remaining);
        long releaseBasis = BasisMath.shareOf(remaining, remainingBasis, released);
        if (basis != null && (released > 0 || releaseBasis > 0))
            basis.release(record.itemId, released, releaseBasis);
        record.reservedTrackedQty = positive(max(record.consumedTrackedQty, record.reservedTrackedQty - released));
        record.reservedTrackedBasisGp = positive(max(record.consumedTrackedBasisGp,
                                record.reservedTrackedBasisGp - releaseBasis));
    }

    /** Release every still-unrealized reserved share when a lifecycle leaves custody state. */
    void releaseRemaining(GeOffer record) {
        if (record == null) return;
        releaseShare(record, max(record.reservedQty(), record.reservedBasis()));
    }

    // ---------------------------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------------------------
    /**
     * One Market row: exact lifecycles book as trades, anything else stays uncounted for review.
     */
    Transaction buildMarket(List<Flow> flows, boolean exact, boolean countsInNet, String reason) {
        return new Transaction(now, exact ? TRADE : UNCERTAIN, MARKET, "Grand Exchange", "Market", countsInNet, flows,
                exact ? CONFIRMED : Confidence.UNCERTAIN, exact ? reason : reason
                                + msg("lifecycle-evidence") + msg("stays-uncounted"), null);
    }

    boolean cleanSettlement(GeOffer record) {
        if (record.unbased() || record.confidence == AMBIGUOUS || record.quantityModified
                || record.collectionAmbiguous || !record.hasFrozenBasis())
            return false;
        if (record.originSessionId.isEmpty() && !empty(session))
            record.originSessionId = string(session);
        // Owner policy (2026-09-24, replaces PRE-R5B 11.19): a settlement books in the session
        // where it is collected; the listing session is kept for the "Listed during" label only
        // and is never rewritten.
        return true;
    }

    /**
     * Accumulate one observed fill. A SELL chunk's tax is exact only when its own per-item price is
     * proven (spent divisible by quantity, or an exempt item); otherwise per-fill tax is lost for
     * this lifecycle and the uniform rule over the total remains the only proof.
     */
    static void recordFill(GeOffer record, long quantity, long spent) {
        record.filledQty = sum(record.filledQty, quantity);
        record.spentGp = sum(record.spentGp, spent);
        if (record.side != SELL || !record.fillTaxExact || quantity <= 0) return;
        long chunkTax = GeTaxRule.provableTax(record.itemId, spent, quantity);
        if (chunkTax < 0) {
            record.fillTaxExact = false;
            return;
        }
        record.fillTaxGp = positive(sum(record.fillTaxGp, chunkTax));
    }

    /**
     * The exact GE tax of a SELL's pending (filled, unsettled) execution, or {@link
     * GeTaxRule#UNPROVABLE_TAX}. Per-fill tax wins when every fill was observed at a provable
     * price, since fills at different prices pay different per-item tax; otherwise the uniform rule
     * over the pending total applies.
     */
    static long pendingSellTax(GeOffer record) {
        long execution = record.pendingExecution(), quantity = record.reserved();
        if (record.fillTaxExact && execution > 0 && quantity > 0) {
            long tax = record.fillTaxGp - record.settledTaxGp;
            return tax >= 0 && tax <= execution ? tax : GeTaxRule.UNPROVABLE_TAX;
        }
        return GeTaxRule.provableTax(record.itemId, execution, quantity);
    }

    // ---------------------------------------------------------------------------------------
    // Persistence / lifecycle
    // ---------------------------------------------------------------------------------------
    synchronized void writeTo(SavedState state) {
        if (state != null) state.setGeCustody(new ArrayList<>(records));
    }

    synchronized void restore(SavedState state, long now) {
        reset();
        if (state == null) return;
        // One live lifecycle per slot (its newest record); settled CLOSED history is kept as well.
        // A record that gave up its slot (-1) still settling is a lifecycle of its own.
        var newest = new HashMap<Object, GeOffer>();
        var closedByOffer = new LinkedHashMap<String, GeOffer>();
        for (var record : state.geCustody()) {
            if (record == null || record.offerId.isEmpty()) continue;
            if (record.stage == CLOSED) closedByOffer.put(record.offerId, record);
            Object slot = record.slot < 0 ? record.offerId : record.slot;
            var existing = newest.get(slot);
            if (existing == null || record.placedAtEpochMillis >= existing.placedAtEpochMillis)
                newest.put(slot, record);
        }
        for (var record : newest.values()) closedByOffer.remove(record.offerId);
        records.addAll(newest.values());
        records.addAll(closedByOffer.values());
        records.sort((left, right) -> Long.compare(left.placedAtEpochMillis, right.placedAtEpochMillis));
    }

    /** Destructive reset: custody state is financial continuity state and clears with it. */
    synchronized void reset() {
        records.clear();
        boxes.clear();
        baseline.clear();
        replaced.clear();
        explained.clear();
        loginAt.clear();
        clearPending();
    }

    /** Adopt another ledger's durable records during a staged profile swap. */
    synchronized void adoptFrom(GeCustodyLedger other) {
        reset();
        if (other != null) records.addAll(other.records);
    }

    synchronized List<GeOffer> records() {
        return frozenList(records);
    }

    /**
     * Drop any transient held collect and slot-clear evidence; durable lifecycles are untouched.
     */
    synchronized void clearPending() {
        cash = 0;
        cashAt = 0;
        heldAt = 0;
        unproven = false;
        clearAt.clear();
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------
    List<GeOffer> byPlacement() {
        var sorted = new ArrayList<>(records);
        sorted.sort((left, right) -> {
                    int byTime = Long.compare(left.placedAtEpochMillis, right.placedAtEpochMillis);
                    return byTime != 0 ? byTime : Integer.compare(left.slot, right.slot);
                });
        return sorted;
    }

    /** The slot's live lifecycle; settled (CLOSED) history never owns a slot. */
    GeOffer forSlot(int slot) {
        for (var record : records) {
            if (record.slot == slot && record.stage != CLOSED) return record;
        }
        return null;
    }

    GeOffer latestClosed(int slot) {
        GeOffer latest = null;
        for (var record : records) {
            if (record.slot == slot && record.stage == CLOSED && (latest == null
                            || record.placedAtEpochMillis >= latest.placedAtEpochMillis))
                latest = record;
        }
        return latest;
    }

    GeOffer oldestClosed() {
        GeOffer first = null;
        for (var record : records) {
            if (record.stage == CLOSED && (first == null || record.placedAtEpochMillis < first.placedAtEpochMillis))
                first = record;
        }
        return first;
    }

    /**
     * The slot's live lifecycle for an offer (any side or item when unknown), falling back to its
     * latest settled history; marks it seen.
     */
    GeOffer touch(int slot, Snapshot offer) {
        Side side = offer == null ? null : OfferSlots.sideOf(offer.state);
        int id = offer == null ? 0 : offer.item;
        GeOffer match = null;
        for (var record : records) {
            if (record.slot != slot || side != null && side != record.side || id > 0 && record.itemId != id)
                continue;
            if (record.stage != CLOSED) {
                match = record;
                break;
            }
            if (match == null || record.placedAtEpochMillis >= match.placedAtEpochMillis)
                match = record;
        }
        if (match != null) match.lastSeenAtEpochMillis = now;
        return match;
    }

    boolean sameOffer(GeOffer record, Side side, int id, long totalQty, long price) {
        return record.side == side && record.itemId == id && record.offeredQty == totalQty
                && record.listedPrice == price;
    }

    /**
     * The slot cleared but its cash or items never reached the inventory: they went to the bank.
     * The offer itself proves what filled and what it cost, so it settles as if collected. A buy is
     * exact; a sale pays its proven tax or, filled at several prices, the highest tax it could have
     * paid, so its result is never overstated. What never sold, or was never spent, went too. False
     * leaves an untrusted or unobserved lifecycle to the Review handoff.
     */
    boolean banked(GeOffer record) {
        collected(record);
        boolean sell = record.side == SELL;
        if (sell && record.capturedQty <= 0 || !cleanSettlement(record)) return false;
        if (sell) {
            long[] net = net(record);
            if (net != null) {
                // The lowest after-tax cash it could have received, as an exact one-offer collect.
                boolean estimated = pendingSellTax(record) < 0;
                settleAttributed(singletonList(record), net[0]);
                if (estimated) rows.get(rows.size() - 1).explanation += msg("gbank");
            }
            long unsold = record.heldQty();
            releaseShare(record, unsold);
            record.returnedQty = sum(record.returnedQty, unsold);
        } else {
            record.collectedQty = positive(record.filledQty);
            bookBuySettlement(record);
            record.refundedGp = positive(sum(record.refundedGp, record.reserveOutstanding()));
        }
        if (record.reconciled()) record.stage = CLOSED;
        return true;
    }

    /** Local collection supplies settlement authority, never retroactive acquisition basis. */
    void collected(GeOffer record) {
        if (record.confidence == AMBIGUOUS) return;
        if (record.unbased()) record.confidence = CustodyConfidence.CONFIRMED;
        if (record.side == SELL && record.capturedQty == 0) {
            record.capturedQty = record.offeredQty;
            record.basisUnitPrice = max(1, record.listedPrice);
        } else if (record.side == BUY && record.basisSource == null && record.reserved() > 0) {
            // A confirmed purchase supplies its actual cost without requiring a guide quote.
            record.basisUnitPrice = record.pendingExecution() / record.reserved();
        } else return;
        record.basisSource = GRAND_EXCHANGE;
        record.basisCapturedAtEpochMillis = now;
    }

    /** Outside-client collection retires pending custody without a booking or Review. */
    void away(GeOffer record) {
        if (basis != null && record.reservedQty() > 0) basis.fenceRealized(record.itemId, now);
        // Reserved units no longer prove owned stock; never release them as a fresh acquisition.
        record.reservedTrackedQty = record.consumedTrackedQty;
        record.reservedTrackedBasisGp = record.consumedTrackedBasisGp;
        record.filledQty = record.settledQty;
        record.spentGp = record.settledExecutionGp;
        record.capturedQty = sum(record.settledQty, record.returnedQty);
        record.stage = CLOSED;
        if (record.settledQty == 0) records.remove(record);
        loginAt.remove(record.offerId);
        clearAt.remove(record.offerId);
    }

    void handoff(GeOffer record, String reason) {
        releaseRemaining(record);
        var review = reviewHandoff(record, reason);
        if (review != null) rows.add(review);
        records.remove(record);
    }

    Transaction reviewHandoff(GeOffer record, String reason) {
        if (record.unbased()) return null;
        boolean buy = record.side == BUY;
        if (!buy && record.capturedQty <= 0)
            // The principal was never observed entering custody; its movements follow the
            // ordinary classification path and must not be surfaced twice.
            return null;
        long pending = buy ? record.reserved() : record.settleable(), execution = record.pendingExecution();
        if (pending <= 0 && execution <= 0) return null;
        var flows = new ArrayList<Flow>(2);
        long unit = record.basisUnitPrice;
        if (pending > 0) {
            long value = buy && record.collectedValueGp != 0 ? SafeMath.abs(record.collectedValueGp)
                            : record.basisFor(pending);
            flows.add(itemFlow(record, buy ? pending : -pending, unit, buy ? value : -value));
        }
        if (execution > 0) flows.add(coins(buy ? -execution : execution));
        return reviewRow(flows, msg("ge-lifecycle") + reason + msg("known-facts"));
    }

    void captureBasis(GeOffer record, Flow flow) {
        var source = flow.source();
        record.basisUnitPrice = positive(flow.unitPrice);
        record.basisSource = source == null ? UNKNOWN : source;
        record.basisCapturedAtEpochMillis = now;
    }

    /** Presentation/audit source name; never used for matching. */
}

/**
 * Slot-keyed, observation-only record of the Grand Exchange offer state exposed by RuneLite. It
 * screens duplicate snapshots and hands each real change to custody as a previous/current pair; it
 * assigns no financial meaning to any field and never books anything.
 *
 * <p>Call {@link #beginSeed()} before processing RuneLite's login slot replay. Snapshots received
 * until {@link #finishSeed()} establish baselines and produce no transitions. On relog, starting
 * another seed clears old slot state so the replay cannot look like new activity.
 */
@Singleton
class OfferSlots {
    Map<Integer, Snapshot> bySlot = new LinkedHashMap<>();
    boolean loginSeed;

    /**
     * Begin a login/relogin baseline pass; all observations are suppressed until it is finished.
     */
    synchronized void beginSeed() {
        bySlot.clear();
        loginSeed = true;
    }

    /** Finish the baseline pass after the caller has received the initial offer-slot replay. */
    synchronized void finishSeed() {
        loginSeed = false;
    }

    /**
     * Observe one immutable slot snapshot. Repeated identical snapshots, a first empty slot and all
     * login-seed observations return an empty Optional.
     */
    synchronized Optional<Transition> observe(Snapshot current) {
        Objects.requireNonNull(current, "current");
        var previous = bySlot.put(current.slot, current);
        if (loginSeed || current.equals(previous) || current.state == GrandExchangeOfferState.EMPTY
                        && (previous == null || previous.state == GrandExchangeOfferState.EMPTY))
            return Optional.empty();
        return Optional.of(new Transition(previous, current));
    }

    /**
     * Take an immutable point-in-time copy of the latest per-slot observations. The returned map
     * cannot be used to mutate this ledger.
     */
    synchronized Map<Integer, Snapshot> snapshots() {
        return unmodifiableMap(new LinkedHashMap<>(bySlot));
    }

    /** The offer side a state belongs to; null for an empty slot. */
    static Side sideOf(GrandExchangeOfferState state) {
        return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
                        || state == GrandExchangeOfferState.CANCELLED_BUY ? BUY
                : state == GrandExchangeOfferState.SELLING || state == GrandExchangeOfferState.SOLD
                                || state == GrandExchangeOfferState.CANCELLED_SELL ? SELL : null;
    }

    static boolean isActive(GrandExchangeOfferState state) {
        return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING;
    }

    static boolean isTerminal(GrandExchangeOfferState state) {
        return sideOf(state) != null && !isActive(state);
    }

    /** Immutable copy of the API fields used by the offer ledger. */
    @EqualsAndHashCode
    @AllArgsConstructor
    static class Snapshot {
        int slot;
        GrandExchangeOfferState state;
        int item, quantity;

        /** RuneLite's quantity bought or sold so far, copied from getQuantitySold(). */
        int traded;

        long price;

        /** Raw getSpent() observation; the model does not assign sell-side or tax semantics. */
        long spent;

        static Snapshot fromOffer(int slot, GrandExchangeOffer offer) {
            return new Snapshot(slot, offer.getState(), offer.getItemId(), offer.getTotalQuantity(),
                    offer.getQuantitySold(), offer.getPrice(), offer.getSpent());
        }
    }

    /**
     * One observed slot change: the previous snapshot (null when first seen) and the current one.
     */
    @AllArgsConstructor
    static class Transition {
        Snapshot previous, current;
    }
}

/**
 * One bounded schema-104 Grand Exchange custody lifecycle: the durable minimum needed to keep exact
 * economics across restart for an offer whose placement was observed under schema 104.
 *
 * <p>Physical custody movements (placement principal, cancellation return/refund) are
 * ownership-neutral. A safely realized settlement books one counted canonical Market result,
 * exactly once, using the persisted cumulative-execution counters as the idempotency guard.
 *
 * <p>Fields are side-specific on purpose: SELL freezes item basis at placement; BUY captures item
 * basis only when the purchased quantity enters tracked ownership. No field is a second financial
 * journal.
 */
@EqualsAndHashCode(of = "offerId", callSuper = false)
@Getter
@Setter
class GeOffer extends SaveCopies {
    /** Durable offer side. */
    enum Side {
        BUY, SELL
    }

    /** Durable lifecycle stage. Behavioural projection states are derived from these facts. */
    enum Stage {
        /** Placement observed; principal may still be arriving; execution may advance. */
        OPEN,
        /** Terminal and reconciled; eligible for safe retirement. */
        CLOSED
    }

    /** Confidence in the placement/basis lineage. */
    enum CustodyConfidence {
        /** Placement and any basis were observed live under schema 104. */
        CONFIRMED,
        /**
         * Offer was already open when schema 104 first observed it; never receives invented basis.
         */
        LEGACY_UNBASED,
        /** Identity or quantity desync; no exact basis lineage. */
        AMBIGUOUS
    }

    String offerId = "";
    int slot;
    Side side = SELL;
    int itemId;
    String itemName = "";
    long offeredQty, listedPrice, placedAtEpochMillis, lastSeenAtEpochMillis;
    String originSessionId = "", offerState = "";
    Stage stage = OPEN;
    CustodyConfidence confidence = CustodyConfidence.CONFIRMED;

    /** Latest cumulative filled quantity observed from the offer counters. */
    long filledQty;

    /** Latest cumulative spent observed from the offer counters (exact execution evidence). */
    long spentGp;

    /** Filled quantity already booked into a canonical settlement. */
    long settledQty;

    /** Gross execution value already booked into a canonical settlement. */
    long settledExecutionGp;

    /** Observed settlement value already applied to this offer's settlements (0 when batch). */
    long settledCashGp;

    /**
     * Cumulative realized Market result booked for this lifecycle (derived, not a second truth).
     */
    long realizedResultGp;

    /** SELL: quantity whose basis is frozen. Never increased after the placement capture. */
    long capturedQty;

    /** SELL: quantity returned from custody on cancellation. */
    long returnedQty;

    /** BUY: observed inventory coin principal currently held by the exchange. */
    long reserveGp;

    /** BUY: observed coin principal already refunded to the inventory. */
    long refundedGp;

    /** SELL frozen basis unit price; 0 when unbased. */
    long basisUnitPrice;

    /** SELL frozen basis price source; null until a basis is captured. */
    PriceSource basisSource;

    /** SELL frozen basis capture time; 0 when unbased. */
    long basisCapturedAtEpochMillis;

    /** BUY: quantity collected into tracked ownership (basis captured at collection). */
    long collectedQty;

    /** BUY: exact value of the collected quantity (sum of observed collection valuations). */
    long collectedValueGp;

    /** Last canonical settlement transaction id linked to this offer lifecycle. */
    String settlementId = "";

    /**
     * The earlier settlement ids, space-separated: an offer collected in parts books one settlement
     * per collect, and Live and the Ledger fold all of them into its one market row (owner
     * 2026-10-09).
     */
    String earlierSettlements = "";

    /** Schema-106 pooled tracked-basis reservation captured with the SELL placement principal. */
    long reservedTrackedQty;

    long reservedTrackedBasisGp;

    /** Schema-106 realized share of the reservation consumed by collected settlements. */
    long consumedTrackedQty;

    long consumedTrackedBasisGp;

    /** True when quantity was modified without an exact per-slice basis allocation. */
    boolean quantityModified;

    /** True once the offer slot was observed EMPTY; the terminal/cancelled state is retained. */
    boolean cleared;

    /** Missing on older saves: never infer a local collection from unknown provenance. */
    boolean collectedAway = true;

    /** True when a collection could not be attributed exactly to the filled quantity. */
    boolean collectionAmbiguous;

    /**
     * Schema-107 SELL: exact GE tax of every observed fill, each at its own proven unit price.
     * Meaningful only while {@link #fillTaxExact}; absent (false) on pre-107 records.
     */
    long fillTaxGp;

    /** Schema-107 SELL: true while every fill was observed at a provable per-item price. */
    boolean fillTaxExact;

    /** Schema-107 SELL: the part of {@link #fillTaxGp} already booked into settlements. */
    long settledTaxGp;

    GeOffer() {
        // Gson
    }

    GeOffer(String offerId, int slot, Side side, int id, String name, long offeredQty, long listedPrice,
            long placedAt, String origin) {
        this.offerId = offerId;
        collectedAway = false;
        this.slot = slot;
        this.side = side;
        itemId = id;
        itemName = string(name);
        this.offeredQty = positive(offeredQty);
        this.listedPrice = positive(listedPrice);
        placedAtEpochMillis = positive(placedAt);
        originSessionId = string(origin);
    }

    /** The frozen basis price source; UNKNOWN until one is captured. */
    PriceSource getBasisSource() {
        return basisSource == null ? UNKNOWN : basisSource;
    }

    /** SELL: tracked reservation quantity still held by this lifecycle. */
    long reservedQty() {
        return positive(reservedTrackedQty - consumedTrackedQty);
    }

    /** SELL: tracked reservation basis still held by this lifecycle. */
    long reservedBasis() {
        return positive(reservedTrackedBasisGp - consumedTrackedBasisGp);
    }

    void setSettlementId(String value) {
        earlierSettlements = earlierSettlements + " " + settlementId;
        settlementId = string(value);
    }

    /**
     * Clamps a record read from a save once, so the accessors can read fields directly: blank text
     * and missing enums take their defaults, and no counter is negative.
     */
    void sanitize() {
        offerId = string(offerId);
        itemName = string(itemName);
        originSessionId = string(originSessionId);
        offerState = string(offerState);
        settlementId = string(settlementId);
        earlierSettlements = string(earlierSettlements);
        if (side == null) side = BUY;
        if (stage == null) stage = OPEN;
        if (confidence == null) confidence = CustodyConfidence.CONFIRMED;
        offeredQty = positive(offeredQty);
        listedPrice = positive(listedPrice);
        placedAtEpochMillis = positive(placedAtEpochMillis);
        lastSeenAtEpochMillis = positive(lastSeenAtEpochMillis);
        filledQty = positive(filledQty);
        spentGp = positive(spentGp);
        settledQty = positive(settledQty);
        settledExecutionGp = positive(settledExecutionGp);
        capturedQty = positive(capturedQty);
        returnedQty = positive(returnedQty);
        reserveGp = positive(reserveGp);
        refundedGp = positive(refundedGp);
        basisUnitPrice = positive(basisUnitPrice);
        basisCapturedAtEpochMillis = positive(basisCapturedAtEpochMillis);
        collectedQty = positive(collectedQty);
        reservedTrackedQty = positive(reservedTrackedQty);
        reservedTrackedBasisGp = positive(reservedTrackedBasisGp);
        consumedTrackedQty = positive(consumedTrackedQty);
        consumedTrackedBasisGp = positive(consumedTrackedBasisGp);
        fillTaxGp = positive(fillTaxGp);
        settledTaxGp = positive(settledTaxGp);
    }

    /** A fresh basis can still be frozen at the first matched owned movement. */
    boolean hasFrozenBasis() {
        return basisSource != null && basisSource != UNPRICED && basisSource != UNKNOWN
                && basisUnitPrice > 0;
    }

    /** Filled quantity not yet booked into a canonical settlement. */
    long reserved() {
        return positive(filledQty - settledQty);
    }

    /** Exact gross execution value of the quantity not yet booked. */
    long pendingExecution() {
        return positive(spentGp - settledExecutionGp);
    }

    /** SELL: quantity still held by the exchange (captured, not returned, not filled). */
    long heldQty() {
        return positive(capturedQty - returnedQty - filledQty);
    }

    /** SELL: basis value for a quantity at the frozen unit price; 0 when unbased. */
    long basisFor(long quantity) {
        return multiply(basisUnitPrice, positive(quantity));
    }

    /** SELL: quantity that can be settled with an exact frozen basis. */
    long settleable() {
        return min(reserved(), positive(capturedQty - settledQty));
    }

    /** BUY: reserve not yet spent on fills or returned as change; zero once the offer is done. */
    long reserveOutstanding() {
        return positive(reserveGp - spentGp - refundedGp);
    }

    /** True when the offer's last observed state is a terminal fill/cancellation. */
    boolean isTerminal() {
        return "SOLD".equals(offerState) || "BOUGHT".equals(offerState) || isCancelled();
    }

    /** True when the last observed state is an explicit cancellation. */
    boolean isCancelled() {
        return "CANCELLED_SELL".equals(offerState) || "CANCELLED_BUY".equals(offerState);
    }

    /** True when custody is fully reconciled and the record may be retired after a durable save. */
    boolean reconciled() {
        if (side == SELL)
            return reserved() == 0 && heldQty() == 0 && (isTerminal() || cleared);
        return reserved() == 0 && positive(filledQty - collectedQty) == 0 && reserveOutstanding() == 0
                && (isTerminal() || cleared);
    }

    /** True when no durable basis can ever be attached (legacy-open or desync). */
    boolean unbased() {
        return confidence == LEGACY_UNBASED;
    }
}

/**
 * The one authoritative GE sell-tax rule shared by custody settlement and the read model.
 *
 * <p>Ordinary taxed items pay 2% of the sale price rounded DOWN per item (so sub-50 gp items pay
 * nothing), capped at 5,000,000 gp per item. The wiki's Grand Exchange tax exemption list (checked
 * 2026-09-13, ids resolved against the pinned RuneLite gameval {@code ItemID} constants) pays
 * nothing regardless of price; a real client Collect All of the owner's seven-sale batch proved the
 * exempt low-level food case (cooked chicken sold at 69 gp paid no tax). Tax is never approximated
 * as a stack-wide 2%: it rounds per ITEM. Everything is integer-only and long-safe; there is no
 * floating point here and no second exemption list anywhere else in the codebase.
 */
class GeTaxRule {
    /**
     * Items exempt from the GE convenience fee, per the wiki's Grand Exchange tax exemption list.
     * Items under 50 gp pay nothing through rounding anyway, so most tools here matter only when
     * their price spikes.
     */
    static final Set<Integer> EXEMPT_IDS = GameData.byId("tax_exempt").keySet();

    /** Sentinel: no proven per-item price exists, so no tax can be asserted for an execution. */
    static final long UNPROVABLE_TAX = -1;

    /**
     * The uniform per-item tax of one execution: {@link #UNPROVABLE_TAX} when the per-item price
     * cannot be proven (a non-exempt gross that is not divisible by the quantity), otherwise the
     * exact tax, which may legitimately be 0 (an exempt item, or a unit price below 50 gp). Each
     * item pays its price / 50 rounded down, at most 5,000,000 gp. The tax never exceeds gross /
     * 50, so it always fits a long.
     */
    static long provableTax(int id, long gross, long quantity) {
        if (gross <= 0 || quantity <= 0) return UNPROVABLE_TAX;
        if (EXEMPT_IDS.contains(id)) return 0;
        if (gross % quantity != 0) return UNPROVABLE_TAX;
        return min(5_000_000, gross / quantity / 50) * quantity;
    }

    /**
     * The proven sell tax of one settled execution: the exact positive gap between the observed
     * gross execution and the observed Received cash, accepted only when it equals the uniform
     * per-item rule for that execution. Zero means nothing is proven — a genuinely tax-free stack,
     * an exempt item, a non-uniform execution, or a gap that does not match the rule.
     *
     * <p>Booking and the read model share this one predicate, so a booked tax can never disagree
     * with the receipt that explains it. It is a tax proof, not a result: it never implies the
     * prior tracked value of the sold units.
     */
    static long acceptedTax(int id, long gross, long quantity, long receivedGp) {
        long tax = provableTax(id, gross, quantity);
        return tax > 0 && gross - receivedGp == tax ? tax : 0;
    }
}
