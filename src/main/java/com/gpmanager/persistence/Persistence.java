package com.gpmanager;
import static java.nio.file.StandardCopyOption.*;
import static java.nio.file.StandardOpenOption.*;
import static com.gpmanager.SaveStatus.State.*;

import static com.gpmanager.SafeMath.*;

import static com.gpmanager.GameData.msg;
import static java.lang.Math.*;

import com.gpmanager.SessionRepository.ReplaceOutcome;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Filepath;
import org.slf4j.*;
import static com.gpmanager.Phrases.*;
import static java.util.Collections.*;
import java.util.*;
import com.google.gson.*;
import com.gpmanager.SaveStatus.State;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/** Coordinates account binding, detached snapshots, and ordered persistence. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class Persistence {
    static final Logger log = LoggerFactory.getLogger(Persistence.class);
    final Client client;
    final ConfigManager config;
    final SessionRepository repository;
    final OrderedPersistenceWriter writer;
    final Engine engine;
    long next = 1;
    @Getter TrackingIdentity activeIdentity;

    /**
     * Set when the bound scope holds data this plugin must not rewrite (newer schema or refused
     * migration).
     */
    String reason;

    /**
     * A restore or reset is replacing the bound account; ingestion is paused until it re-primes.
     */
    volatile boolean maintenance;

    /**
     * A sidebar job (backup) holds the lock: the game thread reuses its last readiness instead of
     * waiting.
     */
    volatile boolean busy, okay;

    /** Lifecycle identity work is serialized off the RuneLite lifecycle/client callbacks. */
    final Object lock = new Object();

    ExecutorService worker;
    volatile boolean closing, loading, failed, needsSnapshot, switchBlocked;

    /**
     * Serializes backup file IO without holding the coordinator monitor the client thread reads.
     */
    final Object backup = new Object();

    final AtomicLong request = new AtomicLong(), epoch = new AtomicLong();
    final AtomicBoolean readySignal = new AtomicBoolean(), closed = new AtomicBoolean();

    void start() {
        synchronized (lock) {
            if (closing) return;
            writer.start();
            epoch.incrementAndGet();
            closing = false;
            loading = false;
            failed = false;
            switchBlocked = false;
            needsSnapshot = false;
            readySignal.set(false);
            closed.set(false);
            if (worker == null || worker.isShutdown())
                worker = OrderedPersistenceWriter.daemonExecutor(msg("gp-manager-lifecycle"));
        }
    }

    /**
     * Runs repository-directory/configuration initialization away from the RuneLite plugin
     * lifecycle callback. Identity work queued immediately afterwards remains ordered behind this
     * operation on the same executor.
     */
    void initAsync(Runnable initialization) {
        synchronized (lock) {
            // A plugin re-enable may arrive after the prior lifecycle fenced
            // this coordinator. Reopen only the lifecycle executor; writer
            // start remains ordered inside the initialization task.
            if (closing) {
                closing = false;
                epoch.incrementAndGet();
            }
        }
        long epoch = this.epoch.get();
        var executor = executor();
        if (executor == null) {
            failed = true;
            return;
        }
        try {
            executor.execute(() -> {
                        try {
                            if (!closing && epoch == this.epoch.get())
                                initialization.run();
                        } catch (RuntimeException ex) {
                            if (epoch == this.epoch.get()) failed = true;
                            log.warn(msg("startup-error"), ex);
                        }
                    });
        } catch (RuntimeException ex) {
            failed = true;
            log.warn(msg("schedule-failed"), ex);
        }
    }

    TrackingIdentity resolveCurrentIdentity() {
        if (config == null) return null;
        String profile = config.getRSProfileKey();
        long accountHash = TrackingIdentity.ACCOUNT_HASH_INVALID;
        if (client != null) {
            try {
                accountHash = client.getAccountHash();
            } catch (RuntimeException ex) {
                // Client may not expose hash before login.
            }
        }
        if (!blank(profile))
            return new TrackingIdentity(profile.trim(), accountHash);
        return null;
    }

    /**
     * Captures the current identity without touching disk, then performs the account
     * bind/load/flush on the lifecycle executor. The next game tick consumes the ready transition
     * after the worker has installed the scope.
     */
    void syncIdentityAsync() {
        switchIdentityAsync(resolveCurrentIdentity(), false);
    }

    /** Seam for deterministic lifecycle tests and explicit profile events. */
    void switchIdentityAsync(TrackingIdentity next) {
        switchIdentityAsync(next, true);
    }

    void switchIdentityAsync(TrackingIdentity next, boolean captureOnCaller) {
        runLifecycle(captureOnCaller, "identity switch", () -> switchIdentity(next, false));
    }

    /**
     * One lifecycle job on the executor: capture the old owner's latest state (on the caller when
     * asked, else on the worker), run the job unless shut down, then publish whether it is ready.
     */
    void runLifecycle(boolean captureOnCaller, String what, BooleanSupplier job) {
        long request = this.request.incrementAndGet();
        fence(nextHash(), request);
        synchronized (this) {
            if (closing) return;
            // Capture the old owner's latest state before the worker can enter
            // its disk wait. A pending earlier lifecycle operation has already
            // made the same capture at its boundary.
            if (captureOnCaller && !loading && activeIdentity != null)
                scheduleSave();
            loading = true;
            readySignal.set(false);
        }
        var executor = executor();
        if (executor == null) {
            finishRequest(request, false);
            return;
        }
        try {
            executor.execute(() -> {
                        boolean ready = false;
                        try {
                            if (!captureOnCaller) {
                                synchronized (this) {
                                    if (!closing && activeIdentity != null)
                                        scheduleSaveLocked(false);
                                }
                            }
                            if (!closing) ready = job.getAsBoolean();
                        } catch (RuntimeException ex) {
                            log.warn(msg("unable-complete") + what, ex);
                        }
                        finishRequest(request, ready);
                    });
        } catch (RuntimeException ex) {
            log.warn(msg("save-schedule-error") + what, ex);
            finishRequest(request, false);
        }
    }

    ExecutorService executor() {
        synchronized (lock) {
            if (closing) return null;
            if (worker == null || worker.isShutdown())
                worker = OrderedPersistenceWriter.daemonExecutor(msg("lifecycle-thread"));
            return worker;
        }
    }

    void finishRequest(long request, boolean ready) {
        if (closing && needsSnapshot) finalizeShutdown();
        if (request != this.request.get()) return;
        loading = false;
        if (ready && !closing) readySignal.set(true);
    }

    /** True once for the client tick that may resume ingestion after an async bind. */
    boolean consumeReady() {
        return readySignal.compareAndSet(true, false);
    }

    synchronized boolean switchIdentity(TrackingIdentity next, boolean capturePrevious) {
        if (next == null) return activeIdentity != null;
        if (next.equals(activeIdentity)) {
            switchBlocked = false;
            return true;
        }
        if (capturePrevious) fence(next.accountHash, request.incrementAndGet());
        if (closing && !capturePrevious) return false;
        if (activeIdentity != null) {
            if (capturePrevious) scheduleSave();
            // The previous owner stays bound until its latest snapshot is durable.
            if (!flush(Duration.ofSeconds(5))) {
                log.warn(msg("refusing-account-switch"));
                switchBlocked = true;
                return false;
            }
        }
        var previous = activeIdentity;
        long previousAppliedRevision = writer.appliedRevision(), previousNextRevision = this.next;
        activeIdentity = next;
        try {
            repository.bindIdentity(next);
            var loaded = loadScope();
            if (closing && !capturePrevious) {
                rollBack(previous, previousAppliedRevision, previousNextRevision);
                return false;
            }
            writer.resetRevision(loaded.revision);
            this.next = max(loaded.revision + 1, 1);
            engine.restoreProfile(next.rsProfileKey, loaded, now());
        } catch (RuntimeException ex) {
            rollBack(previous, previousAppliedRevision, previousNextRevision);
            log.warn(msg("switch-profile-conflict"), ex);
            return false;
        }
        fence(next.accountHash, request.get());
        switchBlocked = false;
        if (previous == null || !previous.equals(next)) engine.beginPriming();
        return true;
    }

    /** A refused switch leaves the previous account bound exactly as it was. */
    void rollBack(TrackingIdentity previous, long appliedRevision, long previousNextRevision) {
        if (previous == null) repository.unbind();
        else repository.bindIdentity(previous);
        activeIdentity = previous;
        writer.resetRevision(appliedRevision);
        next = previousNextRevision;
    }

    /**
     * Fence evidence with the same generation already used to reject stale lifecycle completions.
     */
    void fence() {
        fence(nextHash(), request.incrementAndGet());
    }

    long nextHash() {
        return client == null ? activeIdentity == null ? -1 : activeIdentity.accountHash : client.getAccountHash();
    }

    /** Engine then inbox, like every chest consumption, so no owner change lands inside one. */
    void fence(long hash, long generation) {
        if (engine != null) synchronized (engine) {
            engine.events.owner(hash * 1000 + generation);
        }
    }

    void onLogout() {
        if (loading) {
            // A logout advances the existing request generation too. Publish its completion after
            // the earlier bind, so discarding that stale completion cannot leave ingestion fenced.
            runLifecycle(false, "logout", () -> false);
            return;
        }
        fence();
        synchronized (this) {
            if (!loading && activeIdentity != null) scheduleSaveLocked(false);
        }
    }

    /** Account-hash invalidation is a lifecycle event; its save/load work stays off the callback. */
    void hashInvalidatedAsync() {
        runLifecycle(false, "account clear", () -> {
                    onHashInvalidated();
                    return false;
                });
    }

    synchronized void onHashInvalidated() {
        if (activeIdentity != null) {
            scheduleSaveLocked(false);
            // An unsaved owner is kept, fenced, rather than cleared; the next bind retries its save.
            if (!flush(Duration.ofSeconds(5))) {
                log.warn(msg("account-clear"));
                return;
            }
            if (closing) return;
        }
        engine.clearIdentity();
        activeIdentity = null;
        repository.unbind();
        writer.resetRevision(0);
        next = 1;
        engine.restoreProfile(null, new SavedState(), now());
        switchBlocked = false;
    }

    boolean scheduleSave() {
        return scheduleSave(false);
    }

    /** {@code quick}: a gameplay save, which may reuse unchanged closed sessions' JSON. */
    boolean scheduleSave(boolean quick) {
        // A lifecycle worker may hold this monitor while it waits for a disk
        // flush or loads the next account scope. Gameplay ingestion is already
        // fenced at this point, so a callback request must return without
        // waiting behind that disk work; the lifecycle boundary has its own
        // explicit snapshot path.
        if (loading) return false;
        synchronized (this) {
            return !loading && scheduleSaveLocked(quick);
        }
    }

    /** Caller holds this coordinator monitor; used by lifecycle boundary snapshots. */
    boolean scheduleSaveLocked(boolean quick) {
        var intent = currentIntent(quick);
        return intent != null && writer.submit(intent);
    }

    synchronized boolean saveNow() {
        var intent = currentIntent(false);
        if (intent == null) return false;
        writer.submit(intent);
        return writer.flush(Duration.ofSeconds(5));
    }

    /** In-memory persistence health for a restrained panel warning; performs no file I/O. */
    SaveStatus saveStatus() {
        return switchBlocked ? new SaveStatus(FAILED, msg("switch-save-paused"), false, "") : writer.status();
    }

    String scopeRefusal() {
        if (activeIdentity == null || !repository.isBound() || !identityMatches())
            return msg("current-runescape");
        return writer.conflicted(repository.generation) ? writer.status().detail : reason;
    }

    /** Outcome of a reset: whether it was applied, and why not when refused. */
    static class ResetOutcome {
        @Getter boolean applied;
        @Getter String detail;

        ResetOutcome(boolean applied, String detail) {
            this.applied = applied;
            this.detail = string(detail);
        }
    }

    /**
     * Charter R factory reset for the bound account only: flush, write one validated pre-reset
     * backup export, commit a fresh General owner through the destructive replace, re-prime. Other
     * account folders, unassigned root legacy and every configuration key (including the frozen V1
     * snapshot) are untouched. The replace is fenced on the revision flushed here: if another
     * client advances the account first, the reset is refused (never rebased) and the in-memory
     * profile is restored, so the user can re-run it against the newer state if they still want to.
     */
    /** Delete Profile: with a validated safety copy first, or without one (owner 1.5.2). */
    synchronized ResetOutcome factoryReset(long now, boolean backup) {
        String scope = scopeRefusal();
        if (scope != null) return new ResetOutcome(false, scope);
        maintenance = true;
        try {
            return replaceLocked(now, backup);
        } finally {
            maintenance = false;
        }
    }

    /** Commits a fresh owner (the factory reset) after a validated safety copy, or rolls back. */
    ResetOutcome replaceLocked(long now, boolean backup) {
        if (!saveNow()) return new ResetOutcome(false, msg("current-profile"));
        SavedState protectedState = null;
        if (backup) {
            try {
                protectedState = SessionRepository.parseBackup(JsonCodec.gson(), writeBackup(now));
            } catch (IOException | RuntimeException ex) {
                return new ResetOutcome(false, msg("pre-restore") + ex.getMessage());
            }
        }
        SavedState before;
        synchronized (engine) {
            // Sidebar edits use the engine lock, but are not ingestion. Refuse a stale safety copy
            // before replacing data, with check/snapshot/mutation atomic and all file IO outside.
            if (protectedState != null && !backupContent(engine.exportState()).equals(backupContent(protectedState)))
                return new ResetOutcome(false, msg("reset-refused") + msg("reset-data-changed"));
            before = snapshot();
            fence();
            engine.resetData(now);
        }
        var result = replaceNow();
        if (!result.isCommitted()) {
            engine.restoreProfile(activeIdentity.rsProfileKey, before, now);
            String why = result.kind == ReplaceOutcome.Kind.CONFLICT ? msg("another-client-changed") + " " : "";
            // Owner 2026-10-01 (F25): the shared outcome names the operation that was refused.
            return new ResetOutcome(false, msg("reset-refused") + why + result.message);
        }
        engine.beginPriming();
        return new ResetOutcome(true, msg("fresh-general"));
    }

    /**
     * A copy of the bound account's save in backups/, named account-time and re-read to prove it is
     * intact; only this account's newest 10 are kept.
     */
    Filepath writeBackup(long now) throws IOException {
        synchronized (backup) {
            return writeBackupLocked(now);
        }
    }

    /** Complete backup content with only root bookkeeping headers excluded from equality. */
    static JsonObject backupContent(SavedState state) {
        var json = JsonCodec.gson().toJsonTree(state).getAsJsonObject();
        json.remove("savedAtEpochMillis");
        json.remove("revision");
        return json;
    }

    private Filepath writeBackupLocked(long now) throws IOException {
        repository.backup.createDirectories();
        String name = account() + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(now));
        var file = repository.backup.joinSegment(name + ".json");
        for (int n = 2; file.exists() && n < 1000; n++)
            // "_" sorts after ".", so a same-second copy lists as the newer one and is kept
            // (1.3.1).
            file = repository.backup.joinSegment(name + "_" + n + ".json");
        SavedState state;
        synchronized (engine) {
            state = SaveCopies.copy(engine.exportState());
        }
        String json = JsonCodec.gson().toJson(state);
        file.write(json, CREATE, TRUNCATE_EXISTING, WRITE);
        SessionRepository.parseBackup(JsonCodec.gson(), file);
        pruneBackups(10);
        return file;
    }

    String account() {
        return activeIdentity == null ? "profile" : activeIdentity.rsProfileKey;
    }

    /** This account's backups, newest first. */
    List<Filepath> backups() throws IOException {
        if (!repository.backup.exists()) return new ArrayList<>();
        try (var files = repository.backup.walk(1)) {
            return files.filter(f -> f.isFile() && f.getFileName().startsWith(account() + "-"))
                    .sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
        }
    }

    /** Deletes all but this account's newest {@code keep} backups; returns how many went. */
    int pruneBackups(int keep) throws IOException {
        var all = backups();
        for (var old : all.subList(min(keep, all.size()), all.size())) old.deleteIfExists();
        return max(0, all.size() - keep);
    }

    synchronized boolean identityMatches() {
        var current = resolveCurrentIdentity();
        return current != null && current.equals(activeIdentity);
    }

    /**
     * True when the bound identity matches the client and tracking may ingest gameplay. Refused
     * switches leave the previous identity bound, so the new physical account must not feed that
     * state.
     */
    boolean isTrackingReady() {
        // Read without the monitor so a restore/reset holding it pauses ingestion instead of
        // blocking the client thread behind the disk work.
        if (maintenance || loading || failed) return false;
        if (busy) return okay;
        synchronized (this) {
            return okay = !maintenance && !loading && !failed && identityMatches()
                            && reason == null && !writer.conflicted(repository.generation);
        }
    }

    synchronized ReplaceOutcome replaceNow() {
        var intent = currentIntent(false);
        if (intent == null) return ReplaceOutcome.unchanged(msg("account-identity-bound"));
        return writer.replaceNow(intent);
    }

    synchronized boolean flush(Duration timeout) {
        return writer.flush(timeout);
    }

    void shutdown() {
        boolean lifecycleWasPending = loading;
        closing = true;
        epoch.incrementAndGet();
        fence();
        loading = false;
        readySignal.set(false);
        // If a lifecycle worker is already fencing an owner, it captured that
        // owner's state before entering its disk wait. Keep the worker alive so
        // it can take one final post-shutdown snapshot after that wait returns;
        // the lifecycle callback itself still never waits for the worker.
        if (lifecycleWasPending) {
            needsSnapshot = true;
            synchronized (lock) {
                if (worker == null || worker.isShutdown()) finalizeShutdown();
                else {
                    worker.execute(this::finalizeShutdown);
                    worker.shutdown();
                }
            }
            return;
        }
        if (repository.isBound()) scheduleSave();
        writer.shutdown(Duration.ofSeconds(5));
        synchronized (lock) {
            if (worker != null) worker.shutdown();
        }
    }

    /** Completes a deferred shutdown snapshot from the lifecycle worker without waiting on it. */
    void finalizeShutdown() {
        if (!needsSnapshot || !closed.compareAndSet(false, true)) return;
        needsSnapshot = false;
        try {
            if (repository.isBound()) scheduleSave();
        } catch (RuntimeException ex) {
            log.warn(msg("unable-capture"), ex);
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            synchronized (lock) {
                if (worker != null && !worker.isShutdown())
                    worker.shutdown();
            }
        }
    }

    /**
     * Loads the bound scope. Any schema other than the current one is left exactly as it is and makes the
     * scope read-only; it is never rewritten.
     */
    SavedState loadScope() {
        reason = null;
        var loaded = repository.load();
        if (!loaded.isSupportedSchema()) {
            reason = msg("local-data") + loaded.schemaVersion + msg("version-modify");
            log.warn(reason);
            return new SavedState();
        }
        return loaded;
    }

    /** Freeze the persisted graph under the engine monitor; encoding runs on the writer. */
    SavedState snapshotState(SavedState header) {
        synchronized (engine) {
            var state = SaveCopies.copy(engine.savedState());
            if (header != null) {
                state.setRevision(header.revision);
                state.setSavedAtEpochMillis(header.savedAtEpochMillis);
                if (activeIdentity != null) state.setOwnerKey(activeIdentity.rsProfileKey);
            }
            return state;
        }
    }

    /** A detached copy of the engine's persisted state, without a save header. */
    SavedState snapshot() {
        return snapshotState(null);
    }

    WriteIntent currentIntent(boolean quick) {
        if (!repository.isBound() || reason != null || writer.conflicted(repository.generation))
            return null;
        // Prefer the writer's verified lineage when a prior save is still
        // updating disk lastKnown — avoids queuing another base-N snapshot.
        long baseRevision = max(repository.revision, writer.appliedRevision());
        var header = new SavedState();
        header.setRevision(max(next, baseRevision + 1));
        next = header.revision + 1;
        header.setSavedAtEpochMillis(now());
        return new WriteIntent(activeIdentity, repository.generation, baseRevision, snapshotState(header), null);
    }
}

/**
 * Outcome of ordinary and reset persistence attempts.
 *
 * <p>Successful saves stay quiet. Persistent failures expose a compact warning with details and a
 * safe retry action in the panel.
 */
class SaveStatus {
    enum State {
        NEVER_SAVED, OK, FAILED, CONFLICT
    }

    State state;
    String detail;
    boolean retry;
    String origin;

    SaveStatus(State state, String detail, boolean retryAvailable, String recoveredFrom) {
        this.state = state;
        this.detail = string(detail);
        retry = retryAvailable;
        origin = string(recoveredFrom);
    }

    static SaveStatus neverSaved() {
        return new SaveStatus(NEVER_SAVED, "", false, "");
    }

    boolean isFailure() {
        return state == FAILED || state == State.CONFLICT;
    }
}

/**
 * The one JSON codec the plugin uses outside the injected {@link SessionRepository}: RuneLite's
 * client Gson, bound once at plugin start-up. Plugin Hub rule: never construct a fresh Gson, so
 * nothing in production code can fall back to one — an unbound codec is a programming error.
 */
class JsonCodec {
    static volatile Gson gson;

    static void bind(Gson clientGson) {
        gson = clientGson;
    }

    static boolean isBound() {
        return gson != null;
    }

    static Gson gson() {
        Gson bound = gson;
        if (bound == null) throw new IllegalStateException(msg("jsoncodec-bind"));
        return bound;
    }
}

/**
 * Stable tracking ownership identity.
 *
 * <p>Primary key is the RuneLite RS profile key when available (account hash plus world-type
 * profile). Account hash alone is retained for diagnostics and for matching profiles. Display names
 * are never used as durable identity.
 *
 * <p><b>Account isolation</b> — each RS profile key owns its own General tracker, history, targets,
 * and analytics files. Switching accounts or world-type profiles must never attach one history to
 * another.
 *
 * <p><b>Settings-profile sharing</b> — RuneLite configuration profiles (plugin enablement/settings
 * presets) share the same account data when the same RS profile is logged in. They do not split or
 * duplicate accounting history.
 */
@EqualsAndHashCode(of = "rsProfileKey")
class TrackingIdentity {
    static final long ACCOUNT_HASH_INVALID = -1;
    String rsProfileKey;
    long accountHash;

    TrackingIdentity(String profile, long accountHash) {
        if (blank(profile)) throw new IllegalArgumentException("rsProfileKey");
        rsProfileKey = profile.trim();
        this.accountHash = accountHash;
    }

    /** Filesystem-safe directory name derived from the RS profile key. */
    String folderName() {
        var out = new StringBuilder(rsProfileKey.length());
        for (int i = 0; i < rsProfileKey.length(); i++) {
            char c = rsProfileKey.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_'
                    || c == '.')
                out.append(c); else
                out.append('_');
        }
        String name = out.toString();
        return name.isEmpty() ? "profile" : name;
    }

    public String toString() {
        return msg("profile-key") + (accountHash != ACCOUNT_HASH_INVALID) + "}";
    }
}

/**
 * Immutable destination and fencing for one persistence attempt.
 *
 * <p>Queued and retry writes must carry this intent so a later account switch cannot redirect the
 * payload. {@code expectedBaseRevision} is the on-disk revision observed when the snapshot was
 * taken; under the write lock the repository refuses the save unless disk still matches.
 *
 * <p>A non-null {@code json} is an already encoded payload and {@code state} supplies its header.
 * Otherwise {@code state} owns a detached persisted graph, encoded later by the writer.
 */
class WriteIntent {
    TrackingIdentity identity;
    long generation, base;
    SavedState state;
    String json;

    WriteIntent(TrackingIdentity identity, long scopeGeneration, long expectedBaseRevision, SavedState state,
            String json) {
        if (state == null) throw new IllegalArgumentException("state");
        this.identity = identity;
        generation = scopeGeneration;
        base = positive(expectedBaseRevision);
        this.state = state;
        this.json = json;
    }
}

/**
 * The durable account state. Contains only facts needed after restart: owners and sessions and
 * minimal unresolved claims.
 *
 * <p>Schema {@value #CURRENT_SCHEMA_VERSION} is the 1.3.4 data schema. There is no upgrade path: an
 * older save is set aside and the profile starts fresh; anything newer is preserved read-only.
 */
class SavedState extends SaveCopies {
    /** The 1.3.4 data schema. Nothing older is read: it starts fresh with no upgrade path. */
    static final int CURRENT_SCHEMA_VERSION = 110;

    @Setter int schemaVersion = CURRENT_SCHEMA_VERSION;

    @Setter long savedAtEpochMillis;
    @Setter long revision;

    /** RS profile key that owns this file; null/empty means unbound. */
    @Setter String ownerKey;

    Session generalSession, customSession;
    List<Session> history = new ArrayList<>();

    /**
     * IANA timezone captured for this profile's local-date analytics. Empty means uninitialized.
     */
    String profileTimeZoneId = "";

    /** Reusable My Grinds definitions: setup metadata only, never financial history. */
    List<SavedGrind> savedGrinds = new ArrayList<>();

    /** Read-only copies of owner-selected Live windows; excluded from canonical accounting. */
    List<SavedSession> savedSessions;

    @RequiredArgsConstructor
    static class SavedSession {
        final String id;
        final Session session;
        final List<MarketRows.Row> market;
        transient MoneySnapshot view;

        MoneySnapshot view() {
            if (view == null) view = new MoneySnapshot(this);
            return view;
        }
    }

    /**
     * Bounded GE custody continuity: the minimum durable state that keeps exact economics across
     * restart for offers whose placement was observed. Physical custody is ownership-neutral; a
     * realized Market settlement is canonical once.
     */
    List<GeOffer> geCustody = new ArrayList<>();

    /**
     * Pooled tracked-basis continuity: available per-item previously-counted value, reserved totals
     * held by open SELL custody, the per-item realization fence and the basis epoch.
     */
    @Setter TrackedBasisState trackedBasis;

    /** Last observed looting-bag stock, including confirmed dispenser additions; risk and PK loss. */
    Map<Integer, Long> lootingBag;

    /**
     * Schema-105 compact evolution: optional bounded proven action-label presentation metadata on
     * canonical retained transactions (for example an exact "Ice Burst" name). It is revalidated on
     * load, never a financial input, and disappears with its retained receipt.
     */
    List<PendingClaim> pendingClaims;

    /**
     * Schema-101 physical continuity for an unresolved local PvM death; null on schema-100 files
     * and whenever no death awaits reclaim. Evidence only — never valuation, fees or labels.
     */
    PendingDeath pendingDeathReclaim;
    RaidHistory.State raidHistory;

    SavedState() {}

    /**
     * Revalidates optional schema-105 action-label presentation metadata after a load. Malformed,
     * oversized or non-compatible values degrade to null; a malformed label never fails the load,
     * never backfills and never infers a spell name from rune recipes.
     */
    List<SavedGrind> savedGrinds() {
        if (savedGrinds == null) savedGrinds = new ArrayList<>();
        return savedGrinds;
    }

    void setSavedGrinds(List<SavedGrind> values) {
        savedGrinds = values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    List<GeOffer> geCustody() {
        if (geCustody == null) geCustody = new ArrayList<>();
        return geCustody;
    }

    void setGeCustody(List<GeOffer> values) {
        geCustody = new ArrayList<>();
        if (values != null) {
            for (var value : values) {
                if (value != null && !value.getOfferId().isEmpty()) geCustody.add(value);
            }
        }
    }

    TrackedBasisState trackedBasis() {
        if (trackedBasis == null) trackedBasis = new TrackedBasisState();
        return trackedBasis;
    }

    /**
     * One reusable My Grind definition: setup metadata and default targets only. It never owns
     * transactions, Net, Recent Loot, Active-Time progress or correction state; those stay in the
     * canonical Session history linked through the stable {@code grindId}.
     */
    @Setter
    static class SavedGrind extends SaveCopies {
        String grindId, name;
        Long netTargetGp, activeTimeTargetMillis;
        boolean favorite, archived;

        SavedGrind() {}

        SavedGrind(String id, String name, Long profit, Long active, boolean favorite) {
            grindId = id;
            this.name = name;
            netTargetGp = profit;
            activeTimeTargetMillis = active;
            this.favorite = favorite;
        }

        String id() {
            return string(grindId);
        }

        String name() {
            return string(name);
        }

        Long targetGp() {
            return netTargetGp != null && netTargetGp > 0 ? netTargetGp : null;
        }

        Long timeTargetMillis() {
            return activeTimeTargetMillis != null && activeTimeTargetMillis > 0 ? activeTimeTargetMillis : null;
        }
    }

    SavedState(Session generalSession, Session customSession, List<Session> history) {
        savedAtEpochMillis = now();
        this.generalSession = generalSession;
        this.customSession = customSession;
        this.history = new ArrayList<>(history == null ? new ArrayList<>() : history);
    }

    /** True only for the current schema; a newer one is preserved read-only and never rewritten. */
    boolean isSupportedSchema() {
        return schemaVersion == CURRENT_SCHEMA_VERSION;
    }

    /** The owner that was tracking when this state was saved. */
    Session currentSession() {
        return customSession != null ? customSession : generalSession;
    }

    List<Session> history() {
        if (history == null) history = new ArrayList<>();
        return history;
    }

    String profileTimeZoneId() {
        return string(profileTimeZoneId);
    }

    void setProfileTimeZoneId(String value) {
        profileTimeZoneId = fallback(value, "");
    }

    List<PendingClaim> pendingClaims() {
        return pendingClaims == null ? emptyList() : unmodifiableList(pendingClaims);
    }

    void setPendingClaims(List<PendingClaim> values) {
        pendingClaims = empty(values) ? null : new ArrayList<>(values);
    }

    void setPendingDeathReclaim(PendingDeath value) {
        pendingDeathReclaim = value == null || value.isEmpty() ? null : value;
    }

    /**
     * The durable physical continuity of an unresolved local PvM death: the exact quantities the
     * gravestone/retrieval service holds, the still-unsettled held-at-death whitelist, and the
     * bounded lifecycle counters. Identity and quantity only — no GP valuation, quote, fee, coffer
     * or presentation data. Canonical transactions remain the sole financial authority.
     */
    static class PendingDeath extends SaveCopies {
        List<DeathItem> outstandingItems, heldAtDeath;
        int wipeTicksRemaining, gravestoneAgeTicks;

        PendingDeath() {}

        PendingDeath(List<DeathItem> outstandingItems, List<DeathItem> heldAtDeath, int wipeTicksRemaining,
                int gravestoneAgeTicks) {
            this.outstandingItems = copyItems(outstandingItems);
            this.heldAtDeath = copyItems(heldAtDeath);
            this.wipeTicksRemaining = max(0, wipeTicksRemaining);
            this.gravestoneAgeTicks = max(0, gravestoneAgeTicks);
        }

        List<DeathItem> outstandingItems() {
            return outstandingItems == null ? emptyList() : unmodifiableList(outstandingItems);
        }

        List<DeathItem> heldAtDeath() {
            return heldAtDeath == null ? emptyList() : unmodifiableList(heldAtDeath);
        }

        int wipeTicksRemaining() {
            return max(0, wipeTicksRemaining);
        }

        int gravestoneAgeTicks() {
            return max(0, gravestoneAgeTicks);
        }

        boolean isEmpty() {
            return outstandingItems().isEmpty() && heldAtDeath().isEmpty();
        }

        static List<DeathItem> copyItems(List<DeathItem> values) {
            if (empty(values)) return null;
            var copy = new ArrayList<DeathItem>();
            for (var value : values) {
                if (value != null && value.isValid()) copy.add(value);
            }
            return copy.isEmpty() ? null : copy;
        }
    }

    /** One persisted item identity and quantity for {@link PendingDeath}. */
    static class DeathItem extends SaveCopies {
        int itemId;
        long quantity;

        DeathItem() {}

        DeathItem(int id, long quantity) {
            itemId = id;
            this.quantity = positive(quantity);
        }

        long quantity() {
            return positive(quantity);
        }

        boolean isValid() {
            return itemId > 0 && quantity() > 0;
        }
    }

    /**
     * The one durable evidence exception: an unresolved key/chest claim that must survive restart.
     * Idempotent by {@code claimId}; a claim-closing transaction carries the same id as {@code
     * sourceClaimId}, while a partly settled claim keeps its reduced {@code quantity}. Claims
     * intentionally carry no cross-session ownership edge.
     */
    static class PendingClaim extends SaveCopies {
        String claimId;
        int itemOrKeyId;
        long quantity, createdAtEpochMillis;

        PendingClaim() {}

        PendingClaim(String id, int itemOrKeyId, long quantity, long createdAtEpochMillis) {
            claimId = id;
            this.itemOrKeyId = itemOrKeyId;
            this.quantity = positive(quantity);
            this.createdAtEpochMillis = positive(createdAtEpochMillis);
        }

        String claimId() {
            return string(claimId);
        }

        long quantity() {
            return positive(quantity);
        }

        long createdAtEpochMillis() {
            return positive(createdAtEpochMillis);
        }

        boolean isValid() {
            return !claimId().isEmpty() && itemOrKeyId > 0 && quantity() > 0;
        }
    }
}

/**
 * Durable session store with account-scoped files, recoverable commits, and multi-client write
 * protection.
 *
 * <p>Account data lives under {@code accounts/<profileKey>/}; before an account is known, saves go
 * to the {@code unbound} folder.
 */
@Singleton
class SessionRepository {
    static final Logger log = LoggerFactory.getLogger(SessionRepository.class);

    /** 1.0 folders: saves written before 1.0 live elsewhere and are never read or rewritten. */
    static final String ACCOUNTS_DIRECTORY = "profiles";

    static final String UNASSIGNED_MARKER = "unbound";
    Gson gson;
    Filepath root;

    /** Profile backups, one folder, newest 10 per account (owner 2026-09-28). */
    Filepath backup;

    boolean accounts;

    /** The bound scope's save files. */
    ScopeFiles bound;

    boolean initialized;
    TrackingIdentity owner;
    long revision, generation;

    @Inject
    SessionRepository(Gson codec) {
        gson = codec;
        accounts = true;
    }

    SessionRepository(Gson codec, Filepath dataDirectory, boolean accountAware) {
        gson = codec;
        accounts = accountAware;
        initialize(dataDirectory);
    }

    synchronized void initialize(Filepath pluginDirectory) {
        if (pluginDirectory == null) throw new IllegalArgumentException("pluginDirectory");
        Filepath rooted = pluginDirectory.rooted();
        if (initialized) {
            if (!root.equals(rooted))
                throw new IllegalStateException(msg("gp-manager-repository"));
            return;
        }
        root = rooted;
        backup = rooted.joinSegment("backups");
        initialized = true;
        if (accounts)
            // Production starts unbound: do not write shared root history until
            // an RS profile identity is available and explicitly bound.
            bindScope(root.joinSegment(UNASSIGNED_MARKER), null); else
            // Single-scope / test mode: the provided directory is the store.
            bindScope(rooted, null);
    }

    void requireInit() {
        if (!initialized) throw new IllegalStateException(msg("repository-ready"));
    }

    /** One scope directory's primary, backup, next and write-lock files. */
    static class ScopeFiles {
        Filepath dir, state, backup, next, lock;

        ScopeFiles(Filepath dir) {
            this.dir = dir;
            state = dir.joinSegment("sessions.json");
            backup = dir.joinSegment(msg("backup-suffix"));
            next = dir.joinSegment("sessions.next.json");
            lock = dir.joinSegment("sessions.write.lock");
        }
    }

    void bindScope(Filepath directory, TrackingIdentity identity) {
        bound = new ScopeFiles(directory);
        owner = identity;
        revision = 0;
        generation++;
    }

    /** Directory for an account identity without changing the live bind. */
    Filepath scopeDir(TrackingIdentity identity) {
        requireInit();
        if (identity == null)
            return accounts ? root.joinSegment(UNASSIGNED_MARKER) : root;
        return root.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.folderName());
    }

    /**
     * The backup file the last {@link #load()} had to fall back to; empty when the primary loaded.
     */
    volatile String recovery = "";

    /** The folder holding the bound profile's save files. */
    Filepath dataDir() {
        requireInit();
        return bound.dir;
    }

    boolean isBound() {
        return !accounts || owner != null;
    }

    /** Bind writes/loads to an account scope. Does not migrate unassigned data. */
    synchronized void bindIdentity(TrackingIdentity identity) {
        requireInit();
        if (identity == null) throw new IllegalArgumentException("identity");
        var accountDir = root.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.folderName());
        bindScope(accountDir, identity);
    }

    /** Leave any account scope; subsequent saves refuse until rebound. */
    synchronized void unbind() {
        requireInit();
        bindScope(root.joinSegment(UNASSIGNED_MARKER), null);
    }

    synchronized SavedState load() {
        requireInit();
        recovery = "";
        // A save from an older schema starts fresh: it is kept beside the profile, never read.
        retireOlder(bound);
        if (bound.state.exists()) {
            try {
                SavedState state;
                try {
                    state = readState(bound.state);
                } catch (FileSystemException locked) {
                    // Antivirus or Windows Search holding the file is no corruption: read it once
                    // more.
                    state = readState(bound.state);
                }
                // Load establishes disk truth; do not retain a stale in-memory
                // high-water mark from a primary that was later discarded.
                revision = positive(state.revision);
                return state;
            } catch (Exception ex) {
                log.warn(msg("unable-load"), ex);
                // Only a file that read but failed to parse is set aside as corrupt; a still-locked
                // one stays put,
                // so the newer save is never lost behind its backup (review 2026-10-09).
                if (!(ex instanceof FileSystemException)) setAside(bound);
            }
        }
        if (bound.backup.exists()) {
            try {
                var i = readState(bound.backup);
                log.warn(msg("recovered-gp"), bound.backup.getFileName());
                revision = positive(i.revision);
                recovery = bound.backup.getFileName().toString();
                return i;
            } catch (Exception j) {
                log.warn(msg("unable-read"), bound.backup, j);
            }
        }
        revision = 0;
        return new SavedState();
    }

    SavedState readState(Filepath path) throws IOException {
        try (Reader reader = path.openReader(READ)) {
            return parseState(gson, new JsonParser().parse(reader));
        } catch (RuntimeException ex) {
            throw new IOException(msg("unreadable-session"), ex);
        }
    }

    /** Parses one payload. */
    static SavedState parseState(Gson codec, JsonElement json) throws IOException {
        if (json == null || !json.isJsonObject()) throw new IOException(msg("empty-session"));
        var state = codec.fromJson(json, SavedState.class);
        if (state == null) throw new IOException(msg("empty-session"));
        return state;
    }

    /** Backup parse/validation with the caller's existing Gson policy and error behavior. */
    static SavedState parseBackup(Gson codec, Filepath file) throws IOException {
        return parseState(codec, new JsonParser().parse(readText(file)));
    }

    /**
     * Save using an immutable write intent. Destination and owner key come from the intent, not
     * from a later account bind.
     */
    synchronized boolean save(WriteIntent intent) {
        return commit(intent, false).isCommitted();
    }

    /**
     * Destructive replacement fenced exactly like an ordinary save: the intent's expected base
     * revision must still be the disk revision under the account lock, otherwise the newer disk
     * state wins and the reset is refused. The original intent is attempted once; it is never
     * rebased onto a newer revision, because that would be the stale overwrite the fence exists to
     * prevent.
     */
    synchronized ReplaceOutcome replaceStateDetailed(WriteIntent intent) {
        if (intent == null || intent.state == null)
            return ReplaceOutcome.unchanged("state was null");
        return commit(intent, true);
    }

    /**
     * One fenced commit of {@code intent}. Returns {@code COMMITTED} once primary (and, for resets,
     * backup alignment) finished, {@code COMMITTED_PRIMARY_ONLY} when the primary was replaced but
     * a later step faulted, {@code CONFLICT} when disk no longer matches the expected base, else
     * {@code FAILED_UNCHANGED}.
     */
    ReplaceOutcome commit(WriteIntent intent, boolean reset) {
        if (intent == null || intent.state == null) throw new IllegalArgumentException("intent");
        var state = intent.state;
        var identity = intent.identity;
        if (accounts && identity == null) {
            log.warn(msg("refusing-gp"));
            return ReplaceOutcome.unchanged(msg("account-identity-write"));
        }
        var target = new ScopeFiles(scopeDir(identity));
        // Owner key is taken from the intent, never from a later live bind.
        if (identity != null) state.setOwnerKey(identity.rsProfileKey);
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        if (state.savedAtEpochMillis <= 0) state.setSavedAtEpochMillis(now());
        FileLock lock = null;
        // Set once the primary replacement has been attempted; a fault after that
        // point is classified by inspecting the primary under the still-held lock.
        boolean primaryAttempted = false;
        try {
            target.dir.createDirectories();
            lock = lockForWrite(target.lock);
            if (lock == null) {
                log.warn(msg("another-gp"));
                return ReplaceOutcome.unchanged(msg("another-client-holds"));
            }
            retireOlder(target);
            var diskSchema = peek(target.state).map(SessionRepository::schemaOf);
            if (diskSchema.isPresent() && diskSchema.get() != SavedState.CURRENT_SCHEMA_VERSION) {
                // Any other schema (a newer version) is preserved read-only.
                log.warn(msg("refusing-overwrite"), diskSchema.get());
                return ReplaceOutcome.unchanged(msg("disk-state") + diskSchema.get());
            }
            long revision = peekRevision(target.state).orElse(backupRevision(target.backup).orElse(0L));
            // Compare-and-swap against the base revision observed when the
            // snapshot was created. Equal competing writers both claiming base N
            // must not both succeed — and a destructive reset is no exception:
            // a newer revision written by another client wins and the reset aborts.
            if (revision != intent.base) {
                log.warn(msg("refusing-concurrent"), reset ? "reset" : "save", revision,
                        intent.base);
                if (sameScope(identity)) this.revision = revision;
                return ReplaceOutcome.conflict(msg("disk-revision"));
            }
            if (state.revision <= 0)
                state.setRevision(revision + 1); else if (state.revision <= revision) {
                log.warn(msg("refusing-non"), state.revision, revision);
                return ReplaceOutcome.unchanged(
                        msg("advance-disk"));
            }
            try (Writer writer = target.next.openWriter(CREATE, TRUNCATE_EXISTING,
                            WRITE)) {
                if (intent.json == null) gson.toJson(state, writer);
                else writer.write(intent.json);
            }
            var validated = readState(target.next);
            if (!reset && target.state.exists()) {
                boolean valid = false;
                try {
                    readState(target.state);
                    valid = true;
                } catch (Exception ex) {
                    setAside(target);
                    if (target.state.exists()) throw new IOException(msg("cannot-preserve"), ex);
                }
                if (valid)
                    replaceBackup(target, msg("backup-prefix"));
            }
            primaryAttempted = true;
            // One atomic move: a crash leaves either the old save or the new one, never neither.
            replaceFile(target.next, target.state);
            if (sameScope(identity)) this.revision = validated.revision;
            if (reset) {
                // After a reset the crash copy must not resurrect the cleared data.
                try {
                    replaceBackup(target, msg("backup-reset"));
                } catch (IOException backupEx) {
                    invalidateBackup(target, msg("reset-flush-error"));
                    log.warn(msg("reset-backup-error"), backupEx);
                    return ReplaceOutcome.primaryOnly(msg("primary-reset"));
                }
                return ReplaceOutcome.committed(msg("reset-committed"));
            }
            return ReplaceOutcome.committed("Save committed");
        } catch (IOException ex) {
            log.warn(reset ? msg("unable-replace") : msg("unable-save"), ex);
            // Still under the account lock, so a primary holding our revision can
            // only be ours: classify, never re-issue the write.
            if (primaryAttempted && peekRevision(target.state).orElse(-1L) == state.revision) {
                if (sameScope(identity)) revision = state.revision;
                return ReplaceOutcome.primaryOnly(msg("primary-already"));
            }
            return ReplaceOutcome.unchanged(reset ? msg("reset-did") : msg("save-did"));
        } finally {
            releaseLock(lock);
        }
    }

    boolean sameScope(TrackingIdentity target) {
        if (owner == null && target == null) return true;
        return owner != null && owner.equals(target);
    }

    @AllArgsConstructor
    static class ReplaceOutcome {
        enum Kind {
            COMMITTED, COMMITTED_PRIMARY_ONLY,
            /** Disk advanced past the expected base; nothing was written. */
            CONFLICT, FAILED_UNCHANGED
        }

        Kind kind;
        String message;

        static ReplaceOutcome committed(String message) {
            return new ReplaceOutcome(Kind.COMMITTED, message);
        }

        static ReplaceOutcome primaryOnly(String message) {
            return new ReplaceOutcome(Kind.COMMITTED_PRIMARY_ONLY, message);
        }

        static ReplaceOutcome unchanged(String message) {
            return new ReplaceOutcome(Kind.FAILED_UNCHANGED, message);
        }

        static ReplaceOutcome conflict(String message) {
            return new ReplaceOutcome(Kind.CONFLICT, message);
        }

        boolean isCommitted() {
            return kind == Kind.COMMITTED || kind == Kind.COMMITTED_PRIMARY_ONLY;
        }
    }

    void invalidateBackup(ScopeFiles files, String reason) throws IOException {
        if (files.backup.exists()) {
            var tombstone = files.dir.joinSegment(msg("backup-invalidated") + now() + ".json");
            files.backup.moveTo(tombstone, REPLACE_EXISTING);
            log.warn("{} ({})", reason, tombstone.getFileName());
        }
    }

    /** The existing single crash copy's readable revision. */
    Optional<Long> backupRevision(Filepath file) {
        return peekRevision(file);
    }

    /** A stored payload's top-level object; empty when the file is missing, empty or unreadable. */
    Optional<JsonObject> peek(Filepath path) {
        try {
            return path.exists() ? Optional.of(new JsonParser().parse(readText(path)).getAsJsonObject())
                    : Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /** A payload's schema; 0 when it names none. */
    static int schemaOf(JsonObject object) {
        return object.has("schemaVersion") && object.get("schemaVersion").isJsonPrimitive()
                ? object.get("schemaVersion").getAsInt() : 0;
    }

    /** Sets aside a primary or backup from an older schema, so the profile starts fresh. */
    void retireOlder(ScopeFiles files) {
        for (var path : new Filepath[] {files.state, files.backup}) {
            int schema = peek(path).map(SessionRepository::schemaOf).orElse(SavedState.CURRENT_SCHEMA_VERSION);
            if (schema >= SavedState.CURRENT_SCHEMA_VERSION) continue;
            try {
                String name = path.getFileName().toString().replace(".json", "");
                path.moveTo(files.dir.joinSegment(name + ".schema" + schema + "-" + now() + ".json"),
                        REPLACE_EXISTING);
                log.warn(msg("older-save"), schema);
            } catch (IOException ex) {
                log.warn(msg("unable-preserve"), ex);
            }
        }
    }

    Optional<Long> peekRevision(Filepath path) {
        return peek(path).map(object -> object.has("revision") && object.get("revision").isJsonPrimitive()
                                        ? object.get("revision").getAsLong() : 0);
    }

    // Package visibility permits deterministic disk-failure tests without
    // introducing production fault switches.
    void replaceFile(Filepath source, Filepath target) throws IOException {
        try {
            atomicMove(source, target);
        } catch (AtomicMoveNotSupportedException ex) {
            // Windows/network volumes without atomic rename: plain replace of a validated file.
            source.moveTo(target, REPLACE_EXISTING);
        } catch (AccessDeniedException ex) {
            // Antivirus or Windows Search can hold a just-written file for a moment; one more try
            // often clears it, and the writer retries later without alarming anyone (owner 1.2).
            atomicMove(source, target);
        }
    }

    static String readText(Filepath path) throws IOException {
        try (Reader reader = path.openReader(READ)) {
            var text = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) text.append(buffer, 0, count);
            return text.toString();
        }
    }

    void atomicMove(Filepath source, Filepath target) throws IOException {
        source.moveTo(target, REPLACE_EXISTING, ATOMIC_MOVE);
    }

    FileLock lockForWrite(Filepath lockPath) throws IOException {
        lockPath.getParent().createDirectories();
        var channel = lockPath.openFileChannel(CREATE, WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldInThisProcess) {
            // Another repository instance in this JVM (or a test) holds the account lock.
            lock = null;
        }
        if (lock == null) {
            channel.close();
            return null;
        }
        return lock;
    }

    void releaseLock(FileLock lock) {
        if (lock == null) return;
        try {
            var channel = lock.channel();
            lock.release();
            channel.close();
        } catch (IOException ex) {
            log.debug(msg("unable-release"), ex);
        }
    }

    void setAside(ScopeFiles files) {
        try {
            if (files.state.exists()) {
                var backup = files.dir.joinSegment("sessions.corrupt-" + now() + ".json");
                files.state.moveTo(backup, REPLACE_EXISTING);
            }
        } catch (IOException backupError) {
            log.warn(msg("unable-preserve"), backupError);
        }
    }

    /** Atomic backup replacement, using each operation's original temporary file name. */
    void replaceBackup(ScopeFiles files, String tempName) throws IOException {
        var temp = files.dir.joinSegment(tempName);
        files.state.copyTo(temp, REPLACE_EXISTING);
        replaceFile(temp, files.backup);
    }
}

/**
 * One ordered background writer for ordinary saves.
 *
 * <p>Each queued write carries an immutable {@link WriteIntent} so account switches cannot redirect
 * pending work. The executor is restartable across plugin disable/enable cycles.
 */
@Singleton
class OrderedPersistenceWriter {
    static final Logger log = LoggerFactory.getLogger(OrderedPersistenceWriter.class);
    static final Duration DEFAULT_SHUTDOWN_FLUSH = Duration.ofSeconds(5);
    SessionRepository repository;
    Object lock = new Object();
    ExecutorService executor;

    /** Ordinary saves that failed in a row; the warning waits for the third. */
    int failed;

    boolean external;
    AtomicLong written = new AtomicLong(0);

    /** Disk truth diverged from this scope's in-memory state; retries must never rebase it. */
    AtomicLong conflict = new AtomicLong(-1);

    AtomicReference<PendingWrite> merged = new AtomicReference<>();
    AtomicReference<SaveStatus> status = new AtomicReference<>(SaveStatus.neverSaved());
    AtomicBoolean scheduled = new AtomicBoolean(false);
    Object monitor = new Object();
    volatile boolean accept = true;

    /**
     * The scope generation and revision of the newest write asked for and of the newest committed
     * one; neither keeps a snapshot alive.
     */
    volatile long[] asked, done;

    /** A queued write whose attempts ran out: it waits for a newer snapshot, a retry or a restart. */
    volatile PendingWrite stuck;

    /** When a shutdown stops starting retries. */
    volatile long until;

    @Inject
    OrderedPersistenceWriter(SessionRepository repository) {
        this.repository = repository;
        external = false;
        executor = daemonExecutor("gp-manager-persist");
    }

    /** Visible for tests that supply a same-thread or controlled executor. */
    OrderedPersistenceWriter(SessionRepository repository, ExecutorService executor) {
        this.repository = repository;
        external = true;
        this.executor = executor;
    }

    /** Reopens the writer; a write kept after its attempts gets another bounded cycle. */
    void start() {
        accept = true;
        stuck = null;
        scheduleDrain();
    }

    /** One named daemon worker thread; persistence never keeps the client alive. */
    static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
                    var thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    SaveStatus status() {
        return status.get();
    }

    long appliedRevision() {
        return written.get();
    }

    void resetRevision(long revision) {
        written.set(positive(revision));
        if (status.get().state == State.CONFLICT && !conflicted(repository.generation))
            status.set(SaveStatus.neverSaved());
    }

    boolean conflicted(long generation) {
        return conflict.get() == generation;
    }

    /** Enqueue an ordinary save bound to an immutable write intent. */
    boolean submit(WriteIntent intent) {
        if (intent == null || intent.state == null) return false;
        var detached = intent.state;
        if (detached.revision <= 0) detached.setRevision(intent.base + 1);
        // Asked for even when refused below, so a flush can never report it saved.
        var next = new PendingWrite(intent);
        asked = new long[] {intent.generation, next.revision};
        if (conflicted(intent.generation)) return false;
        if (!accept) {
            status.set(new SaveStatus(FAILED, "Saving has stopped. Restart RuneLite to try again.", true, ""));
            return false;
        }
        while (true) {
            var current = merged.get();
            if (current != null) {
                // Prefer newer revision within the same scope generation.
                if (current.intent.generation == next.intent.generation && current.revision > next.revision)
                    return true;
                // A newer scope generation supersedes older pending work for a
                // previous account — but only after the coordinator drained or
                // abandoned that scope. Drop stale-scope work if a newer scope
                // is already queued.
                if (current.intent.generation > next.intent.generation) {
                    status.set(new SaveStatus(FAILED, msg("pending-save"), false, ""));
                    return false;
                }
            }
            if (merged.compareAndSet(current, next)) break;
        }
        // The newer snapshot supersedes a write whose attempts ran out; nothing else keeps it.
        stuck = null;
        return scheduleDrain();
    }

    /**
     * Destructive replacement fenced on {@code intent.expectedBaseRevision}. The intent is
     * committed exactly once; a refusal (another client advanced the account) is reported as {@code
     * CONFLICT} and is never rebased or retried here — after reloading, the user re-runs the reset
     * against the newer state if they still want it.
     */
    ReplaceOutcome replaceNow(WriteIntent intent) {
        flush(DEFAULT_SHUTDOWN_FLUSH);
        if (conflicted(intent.generation))
            return ReplaceOutcome.conflict(status.get().detail);
        var detached = intent.state;
        // A pre-serialized payload already carries a revision past its expected base.
        if (intent.json == null) {
            detached.setRevision(max(intent.base, written.get()) + 1);
            detached.setSavedAtEpochMillis(now());
        }
        var result = repository.replaceStateDetailed(intent);
        if (result.isCommitted()) {
            publish(detached.revision, result.message, "");
            return result;
        }
        boolean conflict = result.kind == ReplaceOutcome.Kind.CONFLICT;
        if (conflict) this.conflict.accumulateAndGet(intent.generation, Math::max);
        status.set(new SaveStatus(conflict ? State.CONFLICT : FAILED,
                        conflict ? result.message + msg("tracking-paused-back") : result.message, false, ""));
        return result;
    }

    /**
     * Waits for the newest write asked for so far, and is true only when it, or a newer snapshot
     * of the same scope generation, committed. An empty queue alone proves nothing.
     */
    boolean flush(Duration timeout) {
        var wait = timeout == null ? DEFAULT_SHUTDOWN_FLUSH : timeout;
        var want = asked;
        scheduleDrain();
        long deadline = System.nanoTime() + wait.toNanos();
        synchronized (monitor) {
            while (pending()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    monitor.wait(min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 250));
                } catch (InterruptedException ex) {
                    return false;
                }
            }
        }
        var ok = done;
        return want == null || ok != null && ok[0] == want[0] && ok[1] >= want[1];
    }

    /** Work is queued or running; a write whose attempts ran out waits for newer work. */
    boolean pending() {
        var work = merged.get();
        return scheduled.get() || work != null && work != stuck;
    }

    void shutdown(Duration timeout) {
        until = System.nanoTime() + timeout.toNanos();
        accept = false;
        // Plugin lifecycle callbacks must return without waiting for disk work.
        // The single writer is allowed to drain the already captured intent in
        // order; a later enable creates a fresh executor if this one has
        // terminated.  No new intent can be accepted after this fence.
        scheduleDrain();
        synchronized (lock) {
            if (external || executor == null) return;
            executor.shutdown();
        }
    }

    boolean scheduleDrain() {
        if (!scheduled.compareAndSet(false, true)) return true;
        ExecutorService active;
        synchronized (lock) {
            if (!external && (executor == null || executor.isShutdown())) {
                if (!accept) {
                    scheduled.set(false);
                    return false;
                }
                executor = daemonExecutor("gp-manager-persist");
            }
            active = executor;
        }
        try {
            active.execute(this::drain);
            return true;
        } catch (RuntimeException ex) {
            scheduled.set(false);
            log.warn(msg("lifecycle-schedule-error"), ex);
            status.set(new SaveStatus(FAILED, msg("persistence-executor"), true, ""));
            return false;
        }
    }

    void drain() {
        try {
            while (true) {
                // The write stays queued until it commits, is superseded or meets a conflict.
                var work = merged.get();
                if (work == null || work == stuck) return;
                if (conflicted(work.intent.generation)) {
                    merged.compareAndSet(work, null);
                    continue;
                }
                WriteIntent intent;
                boolean ok = false;
                long disk;
                // Three attempts, 50 and 200 ms apart on this worker, unless newer work, a
                // conflict or the end of the shutdown window comes first.
                for (int attempt = 1; ; attempt++) {
                    intent = rebase(work.intent);
                    try {
                        ok = repository.save(intent);
                    } catch (RuntimeException ex) {
                        log.warn(msg("gp-manager-background"), ex);
                    }
                    // Only the bound scope's disk revision can prove a competing writer.
                    disk = intent.generation == repository.generation ? repository.revision : intent.base;
                    if (ok || disk != intent.base || attempt == 3 || merged.get() != work
                            || !accept && System.nanoTime() - until >= 0 || !pause(50 * attempt * attempt)
                            // The window can expire, or shutdown can arrive, during backoff.
                            || !accept && System.nanoTime() - until >= 0)
                        break;
                }
                if (ok) {
                    done = new long[] {work.intent.generation, work.revision};
                    merged.compareAndSet(work, null);
                    // Late completions for an abandoned scope must not rewrite
                    // the live applied revision or OK status for the new scope.
                    failed = 0;
                    if (intent.generation == repository.generation)
                        publish(work.revision, "", repository.recovery); else
                        log.debug(msg("ignoring-late"), intent.generation, repository.generation);
                    continue;
                }
                State state = FAILED;
                String detail = msg("save-failed");
                if (disk != intent.base) {
                    state = State.CONFLICT;
                    conflict.accumulateAndGet(intent.generation, Math::max);
                    detail = msg("disk-revision") + msg("tracking-paused-back");
                    merged.compareAndSet(work, null);
                } else if (merged.get() == work)
                    stuck = work;
                // A failure the retries clear (a scanner briefly holding the file) is silent: the
                // old save is intact. Only a save request that keeps failing is shown (owner 1.2).
                if (intent.generation == repository.generation
                        && (state != FAILED || ++failed >= 3))
                    status.set(new SaveStatus(state, detail, state == FAILED, status.get().origin));
            }
        } finally {
            scheduled.set(false);
            if (pending() && accept) scheduleDrain();
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
    }

    /** The wait before a retry, holding no monitor; false when interrupted. */
    boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ex) {
            // Nothing in the plugin interrupts this daemon worker; an interrupt just ends the retries.
            return false;
        }
    }

    /** Publish a successful write only after the caller's existing scope/generation guard. */
    void publish(long revision, String message, String detail) {
        written.set(revision);
        status.set(new SaveStatus(OK, message, false, detail));
    }

    /**
     * When this writer already committed revision {@code N} and disk still shows {@code N}, a newer
     * queued snapshot that captured base {@code < N} while that save was in flight is part of our
     * own lineage — rebase its expected base. If disk diverged from our applied revision, leave the
     * intent alone so CAS surfaces a real external conflict.
     */
    WriteIntent rebase(WriteIntent intent) {
        if (intent == null) return null;
        long own = written.get();
        if (own <= 0 || intent.base >= own) return intent;
        if (intent.generation != repository.generation) return intent;
        long disk = repository.revision;
        if (disk != own) return intent;
        return new WriteIntent(intent.identity, intent.generation, own, intent.state, intent.json);
    }

    static class PendingWrite {
        long revision;
        WriteIntent intent;

        PendingWrite(WriteIntent intent) {
            this.intent = intent;
            revision = intent.state.revision;
        }
    }
}

/** Detached persisted graphs; inherited Object.clone copies scalars without reflection. */
class SaveCopies implements Cloneable {
    @SuppressWarnings("unchecked")
    static <T extends SaveCopies> T shallow(T source) {
        if (source == null) return null;
        try {
            return (T) source.clone();
        } catch (CloneNotSupportedException ex) {
            throw new AssertionError(ex);
        }
    }

    static <T extends SaveCopies> T copy(T source) {
        return shallow(source);
    }

    static <T> List<T> list(List<T> source, Function<T, T> copy) {
        if (source == null) return null;
        var result = new ArrayList<T>(source.size());
        for (T item : source) result.add(copy.apply(item));
        return result;
    }

    static SavedState copy(SavedState source) {
        var result = shallow(source);
        if (result == null) return null;
        result.generalSession = copy(source.generalSession);
        result.customSession = copy(source.customSession);
        result.history = list(source.history, SaveCopies::copy);
        result.savedGrinds = list(source.savedGrinds, SaveCopies::copy);
        result.savedSessions = list(source.savedSessions, SaveCopies::copy);
        result.geCustody = list(source.geCustody, SaveCopies::copy);
        result.trackedBasis = copy(source.trackedBasis);
        result.lootingBag = source.lootingBag == null ? null : new LinkedHashMap<>(source.lootingBag);
        result.pendingClaims = list(source.pendingClaims, SaveCopies::copy);
        result.pendingDeathReclaim = copy(source.pendingDeathReclaim);
        result.raidHistory = source.raidHistory == null ? null : RaidHistory.copy(source.raidHistory);
        return result;
    }

    static Session copy(Session source) {
        var result = shallow(source);
        if (result == null) return null;
        result.changeListener = null;
        result.ownedValueObserver = null;
        result.saveStamp = 0;
        result.transactions = list(source.transactions, SaveCopies::copy);
        result.pk = copy(source.pk);
        result.compactedRaids = RaidHistory.copyMoney(source.compactedRaids);
        result.correctionHistory = list(source.correctionHistory, SaveCopies::copy);
        result.undoHistory = list(source.undoHistory, SaveCopies::copy);
        return result;
    }

    static Transaction copy(Transaction source) {
        var result = shallow(source);
        if (result == null) return null;
        result.memo = null;
        result.castItemId = 0;
        result.flows = list(source.flows, SaveCopies::copy);
        return result;
    }

    static PkTally copy(PkTally source) {
        var result = shallow(source);
        if (result == null) return null;
        result.compactedKillNet = source.compactedKillNet == null ? null : new LinkedHashMap<>(source.compactedKillNet);
        return result;
    }

    static CorrectionRecord copy(CorrectionRecord source) {
        var result = shallow(source);
        if (result == null) return null;
        result.changes = list(source.changes, SaveCopies::copy);
        return result;
    }

    static CorrectionRecord.Change copy(CorrectionRecord.Change source) {
        var result = shallow(source);
        if (result == null) return null;
        result.flowSnapshot = list(source.flowSnapshot, SaveCopies::copy);
        return result;
    }

    static UndoRecord copy(UndoRecord source) {
        var result = shallow(source);
        if (result == null) return null;
        result.transaction = copy(source.transaction);
        return result;
    }

    static SavedState.SavedSession copy(SavedState.SavedSession source) {
        return source == null ? null : new SavedState.SavedSession(source.id,
                copy(source.session), list(source.market, SaveCopies::copy));
    }

    static TrackedBasisState copy(TrackedBasisState source) {
        var result = shallow(source);
        if (result == null) return null;
        result.pools = list(source.pools, SaveCopies::copy);
        return result;
    }

    static SavedState.PendingDeath copy(SavedState.PendingDeath source) {
        var result = shallow(source);
        if (result == null) return null;
        result.outstandingItems = list(source.outstandingItems, SaveCopies::copy);
        result.heldAtDeath = list(source.heldAtDeath, SaveCopies::copy);
        return result;
    }
}
