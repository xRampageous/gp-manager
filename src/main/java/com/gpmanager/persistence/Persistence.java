package com.gpmanager;

import static com.gpmanager.GameData.msg;
import static java.lang.Math.*;

import com.google.gson.Gson;
import com.gpmanager.SessionRepository.ReplaceOutcome;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
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

/** Coordinates account binding, detached snapshots, and ordered persistence. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class Persistence {
    static final Logger log = LoggerFactory.getLogger(Persistence.class);
    final Client client;
    final ConfigManager configManager;
    @Getter final SessionRepository repository;
    @Getter final OrderedPersistenceWriter writer;
    final Engine engine;
    long nextRevision = 1L;
    @Getter TrackingIdentity activeIdentity;

    /**
     * Set when the bound scope holds data this plugin must not rewrite (newer schema or refused
     * migration).
     */
    String readOnlyReason;

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
    final Object lifecycleExecutorLock = new Object();

    ExecutorService lifecycleExecutor;
    volatile boolean lifecycleShutdown;
    volatile boolean lifecyclePending;
    volatile boolean lifecycleInitializationFailed;
    volatile boolean shutdownSnapshotPending;
    volatile boolean shutdownPersistHistory;

    /**
     * Serializes backup file IO without holding the coordinator monitor the client thread reads.
     */
    final Object backupLock = new Object();

    final AtomicLong lifecycleRequest = new AtomicLong();
    final AtomicLong lifecycleEpoch = new AtomicLong();
    final AtomicBoolean identityReadyTransition = new AtomicBoolean();
    final AtomicBoolean shutdownFinalized = new AtomicBoolean();

    void start() {
        synchronized (lifecycleExecutorLock) {
            if (lifecycleShutdown) return;
            writer.start();
            lifecycleEpoch.incrementAndGet();
            lifecycleShutdown = false;
            lifecyclePending = false;
            lifecycleInitializationFailed = false;
            shutdownSnapshotPending = false;
            shutdownPersistHistory = false;
            identityReadyTransition.set(false);
            shutdownFinalized.set(false);
            if (lifecycleExecutor == null || lifecycleExecutor.isShutdown()) {
                lifecycleExecutor =
                        OrderedPersistenceWriter.daemonExecutor(msg("gp-manager-lifecycle"));
            }
        }
    }

    /**
     * Runs repository-directory/configuration initialization away from the RuneLite plugin
     * lifecycle callback. Identity work queued immediately afterwards remains ordered behind this
     * operation on the same executor.
     */
    void initAsync(Runnable initialization) {
        synchronized (lifecycleExecutorLock) {
            // A plugin re-enable may arrive after the prior lifecycle fenced
            // this coordinator. Reopen only the lifecycle executor; writer
            // start remains ordered inside the initialization task.
            if (lifecycleShutdown) {
                lifecycleShutdown = false;
                lifecycleEpoch.incrementAndGet();
            }
        }
        long epoch = lifecycleEpoch.get();
        ExecutorService executor = lifecycleExecutor();
        if (executor == null) {
            lifecycleInitializationFailed = true;
            return;
        }
        try {
            executor.execute(
                    () -> {
                        try {
                            if (!lifecycleShutdown && epoch == lifecycleEpoch.get())
                                initialization.run();
                        } catch (RuntimeException ex) {
                            if (epoch == lifecycleEpoch.get()) lifecycleInitializationFailed = true;
                            log.warn(msg("unable-initialize-gp-manager"), ex);
                        }
                    });
        } catch (RuntimeException ex) {
            lifecycleInitializationFailed = true;
            log.warn(msg("unable-schedule-gp"), ex);
        }
    }

    TrackingIdentity resolveCurrentIdentity() {
        if (configManager == null) return null;
        String rsProfileKey = configManager.getRSProfileKey();
        long accountHash = TrackingIdentity.ACCOUNT_HASH_INVALID;
        if (client != null) {
            try {
                accountHash = client.getAccountHash();
            } catch (RuntimeException ex) {
                // Client may not expose hash before login.
            }
        }
        if (rsProfileKey != null && !rsProfileKey.trim().isEmpty()) {
            return new TrackingIdentity(rsProfileKey.trim(), accountHash);
        }
        return null;
    }

    /**
     * Captures the current identity without touching disk, then performs the account
     * bind/load/flush on the lifecycle executor. The next game tick consumes the ready transition
     * after the worker has installed the scope.
     */
    void syncIdentityAsync(BooleanSupplier persistHistory) {
        switchIdentityAsync(resolveCurrentIdentity(), persistHistory, false);
    }

    /** Seam for deterministic lifecycle tests and explicit profile events. */
    void switchIdentityAsync(TrackingIdentity next, boolean persistHistory) {
        switchIdentityAsync(next, () -> persistHistory, true);
    }

    void switchIdentityAsync(
            TrackingIdentity next,
            BooleanSupplier persistHistory,
            boolean capturePreviousOnCaller) {
        runLifecycle(
                persistHistory,
                capturePreviousOnCaller,
                "identity switch",
                persist -> switchIdentity(next, persist, false));
    }

    /**
     * One lifecycle job on the executor: capture the old owner's latest state (on the caller when
     * asked, else on the worker), run the job unless shut down, then publish whether it is ready.
     */
    void runLifecycle(
            BooleanSupplier persistHistory,
            boolean capturePreviousOnCaller,
            String what,
            Predicate<Boolean> job) {
        long request = lifecycleRequest.incrementAndGet();
        fence(nextHash(), request);
        synchronized (this) {
            if (lifecycleShutdown) return;
            // Capture the old owner's latest state before the worker can enter
            // its disk wait. A pending earlier lifecycle operation has already
            // made the same capture at its boundary.
            if (capturePreviousOnCaller
                    && !lifecyclePending
                    && activeIdentity != null
                    && persistHistory.getAsBoolean()) {
                scheduleSave();
            }
            lifecyclePending = true;
            identityReadyTransition.set(false);
        }
        ExecutorService executor = lifecycleExecutor();
        if (executor == null) {
            finishRequest(request, false);
            return;
        }
        try {
            executor.execute(
                    () -> {
                        boolean ready = false;
                        try {
                            boolean persist = persistHistory.getAsBoolean();
                            if (!capturePreviousOnCaller) {
                                synchronized (this) {
                                    if (!lifecycleShutdown && activeIdentity != null && persist)
                                        scheduleSaveLocked();
                                }
                            }
                            if (!lifecycleShutdown) ready = job.test(persist);
                        } catch (RuntimeException ex) {
                            log.warn(msg("unable-complete") + what, ex);
                        }
                        finishRequest(request, ready);
                    });
        } catch (RuntimeException ex) {
            log.warn(msg("unable-schedule-gp-manager-2") + what, ex);
            finishRequest(request, false);
        }
    }

    ExecutorService lifecycleExecutor() {
        synchronized (lifecycleExecutorLock) {
            if (lifecycleShutdown) return null;
            if (lifecycleExecutor == null || lifecycleExecutor.isShutdown()) {
                lifecycleExecutor =
                        OrderedPersistenceWriter.daemonExecutor(msg("gp-manager-lifecycle-2"));
            }
            return lifecycleExecutor;
        }
    }

    void finishRequest(long request, boolean ready) {
        if (lifecycleShutdown && shutdownSnapshotPending) finalizeShutdown();
        if (request != lifecycleRequest.get()) return;
        lifecyclePending = false;
        if (ready && !lifecycleShutdown) identityReadyTransition.set(true);
    }

    /** True once for the client tick that may resume ingestion after an async bind. */
    boolean consumeReady() {
        return identityReadyTransition.compareAndSet(true, false);
    }

    /**
     * Attempt to bind {@code next}. When {@code next} is null, returns whether a prior identity
     * remains bound. Failed flush/save refuses the switch and preserves the previous account's
     * in-memory state.
     */
    synchronized boolean switchIdentity(TrackingIdentity next, boolean persistHistory) {
        return switchIdentity(next, persistHistory, true);
    }

    synchronized boolean switchIdentity(
            TrackingIdentity next, boolean persistHistory, boolean capturePrevious) {
        if (next == null) return activeIdentity != null;
        if (next.equals(activeIdentity)) return true;
        if (capturePrevious) fence(next.accountHash, lifecycleRequest.incrementAndGet());
        if (lifecycleShutdown && !capturePrevious) return false;
        if (activeIdentity != null && persistHistory) {
            if (capturePrevious) scheduleSave();
            if (!flush(Duration.ofSeconds(5))) {
                log.warn(msg("refusing-account-switch"));
                return false;
            }
            SaveStatus status = writer.getStatus();
            if (status.isFailure() || status.state == SaveStatus.State.CONFLICT) {
                log.warn(msg("refusing-account-switch-after"), status.detail);
                return false;
            }
        }
        TrackingIdentity previous = activeIdentity;
        long previousAppliedRevision = writer.appliedRevision();
        long previousNextRevision = nextRevision;
        activeIdentity = next;
        try {
            repository.bindIdentity(next);
            SavedState loaded = persistHistory ? loadScope(next) : new SavedState();
            if (persistHistory && lifecycleShutdown && !capturePrevious) {
                rollBack(previous, previousAppliedRevision, previousNextRevision);
                return false;
            }
            writer.resetRevision(loaded.revision);
            nextRevision = max(loaded.revision + 1L, 1L);
            engine.restoreProfile(next.rsProfileKey, loaded, SafeMath.now());
        } catch (RuntimeException ex) {
            rollBack(previous, previousAppliedRevision, previousNextRevision);
            log.warn(msg("refusing-account-switch-profile"), ex);
            return false;
        }
        fence(next.accountHash, lifecycleRequest.get());
        if (previous == null || !previous.equals(next)) engine.beginPriming();
        return true;
    }

    /** A refused switch leaves the previous account bound exactly as it was. */
    void rollBack(TrackingIdentity previous, long appliedRevision, long previousNextRevision) {
        if (previous == null) repository.unbind();
        else repository.bindIdentity(previous);
        activeIdentity = previous;
        writer.resetRevision(appliedRevision);
        nextRevision = previousNextRevision;
    }

    /**
     * Fence evidence with the same generation already used to reject stale lifecycle completions.
     */
    void fence() {
        fence(nextHash(), lifecycleRequest.incrementAndGet());
    }

    long nextHash() {
        return client == null
                ? (activeIdentity == null ? -1L : activeIdentity.accountHash)
                : client.getAccountHash();
    }

    void fence(long hash, long generation) {
        if (engine != null) engine.inbox.owner(hash * 1_000L + generation);
    }

    void onLogout(boolean persistHistory) {
        if (lifecyclePending) {
            // A logout advances the existing request generation too. Publish its completion after
            // the earlier bind, so discarding that stale completion cannot leave ingestion fenced.
            runLifecycle(() -> persistHistory, false, "logout", persist -> false);
            return;
        }
        fence();
        synchronized (this) {
            if (!lifecyclePending && activeIdentity != null && persistHistory) scheduleSaveLocked();
        }
    }

    /**
     * Account-hash invalidation is a lifecycle event; its save/load work stays off the callback,
     * and the setting is read after queued startup configuration migration completes.
     */
    void onHashInvalidatedAsync(BooleanSupplier persistHistory) {
        runLifecycle(
                persistHistory,
                false,
                "account clear",
                persist -> {
                    onHashInvalidated(persist);
                    return false;
                });
    }

    synchronized void onHashInvalidated(boolean persistHistory) {
        if (activeIdentity != null && persistHistory) {
            scheduleSaveLocked();
            if (!flush(Duration.ofSeconds(5))) log.warn(msg("account-clear"));
            if (lifecycleShutdown) return;
        }
        engine.clearIdentity();
        activeIdentity = null;
        repository.unbind();
        writer.resetRevision(0L);
        nextRevision = 1L;
        engine.restoreProfile(null, new SavedState(), SafeMath.now());
    }

    void scheduleSave() {
        scheduleSave(false);
    }

    /** {@code quick}: a gameplay save, which may reuse unchanged closed sessions' JSON. */
    void scheduleSave(boolean quick) {
        // A lifecycle worker may hold this monitor while it waits for a disk
        // flush or loads the next account scope. Gameplay ingestion is already
        // fenced at this point, so a callback request must return without
        // waiting behind that disk work; the lifecycle boundary has its own
        // explicit snapshot path.
        if (lifecyclePending) return;
        synchronized (this) {
            if (!lifecyclePending) scheduleSaveLocked(quick);
        }
    }

    /** Caller holds this coordinator monitor; used by lifecycle boundary snapshots. */
    void scheduleSaveLocked() {
        scheduleSaveLocked(false);
    }

    void scheduleSaveLocked(boolean quick) {
        WriteIntent intent = currentIntent(quick);
        if (intent == null) return;
        writer.submit(intent);
    }

    synchronized boolean saveNow() {
        WriteIntent intent = currentIntent();
        if (intent == null) return false;
        writer.submit(intent);
        if (!writer.flush(Duration.ofSeconds(5))) return false;
        SaveStatus status = writer.getStatus();
        return status != null && !status.isFailure() && status.state != SaveStatus.State.CONFLICT;
    }

    /** In-memory persistence health for a restrained panel warning; performs no file I/O. */
    SaveStatus saveStatus() {
        return writer.getStatus();
    }

    String scopeRefusal() {
        if (activeIdentity == null || !repository.isBound() || !identityMatches())
            return msg("current-runescape");
        return writer.isConflicted(repository.scopeGeneration)
                ? writer.getStatus().detail
                : readOnlyReason;
    }

    /** Outcome of a reset: whether it was applied, and why not when refused. */
    static class ResetOutcome {
        @Getter boolean applied;
        @Getter String detail;

        ResetOutcome(boolean applied, String detail) {
            this.applied = applied;
            this.detail = Phrases.orEmpty(detail);
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
    synchronized ResetOutcome factoryReset(long now) {
        String scope = scopeRefusal();
        if (scope != null) return new ResetOutcome(false, scope);
        maintenance = true;
        try {
            return replaceLocked(now);
        } finally {
            maintenance = false;
        }
    }

    /** Commits a fresh owner (the factory reset) after a validated safety copy, or rolls back. */
    ResetOutcome replaceLocked(long now) {
        if (!saveNow()) return new ResetOutcome(false, msg("current-profile"));
        SavedState protectedState;
        try {
            protectedState = SessionRepository.parseBackup(JsonCodec.gson(), writeBackup(now));
        } catch (IOException | RuntimeException ex) {
            return new ResetOutcome(false, msg("pre-restore") + ex.getMessage());
        }
        SavedState before;
        synchronized (engine) {
            // Sidebar edits use the engine lock, but are not ingestion. Refuse a stale safety copy
            // before replacing data, with check/snapshot/mutation atomic and all file IO outside.
            if (!backupContent(engine.exportState()).equals(backupContent(protectedState)))
                return new ResetOutcome(
                        false, msg("reset-refused") + msg("reset-data-changed"));
            before = snapshot();
            fence();
            engine.resetData(now);
        }
        ReplaceOutcome outcome = replaceNow();
        if (!outcome.isCommitted()) {
            engine.restoreProfile(activeIdentity.rsProfileKey, before, now);
            String why =
                    outcome.kind == ReplaceOutcome.Kind.CONFLICT
                            ? msg("another-client-changed") + " "
                            : "";
            // Owner 2026-10-01 (F25): the shared outcome names the operation that was refused.
            return new ResetOutcome(
                    false, msg("reset-refused") + why + outcome.message);
        }
        engine.beginPriming();
        return new ResetOutcome(true, msg("fresh-general"));
    }

    /**
     * A copy of the bound account's save in backups/, named account-time and re-read to prove it is
     * intact; only this account's newest 10 are kept.
     */
    Filepath writeBackup(long now) throws IOException {
        synchronized (backupLock) {
            return writeBackupLocked(now);
        }
    }

    /** Complete backup content with only root bookkeeping headers excluded from equality. */
    static com.google.gson.JsonObject backupContent(SavedState state) {
        com.google.gson.JsonObject json = JsonCodec.gson().toJsonTree(state).getAsJsonObject();
        json.remove("savedAtEpochMillis");
        json.remove("revision");
        return json;
    }

    private Filepath writeBackupLocked(long now) throws IOException {
        repository.backupDirectory.createDirectories();
        String name =
                account()
                        + "-"
                        + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                .withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(now));
        Filepath file = repository.backupDirectory.joinSegment(name + ".json");
        for (int n = 2; file.exists() && n < 1_000; n++) {
            // "_" sorts after ".", so a same-second copy lists as the newer one and is kept
            // (1.3.1).
            file = repository.backupDirectory.joinSegment(name + "_" + n + ".json");
        }
        String json;
        synchronized (engine) {
            json = JsonCodec.gson().toJson(engine.exportState());
        }
        file.write(
                json,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        SessionRepository.parseBackup(JsonCodec.gson(), file);
        pruneBackups(10);
        return file;
    }

    String account() {
        return activeIdentity == null ? "profile" : activeIdentity.rsProfileKey;
    }

    /** This account's backups, newest first. */
    java.util.List<Filepath> backups() throws IOException {
        if (!repository.backupDirectory.exists()) return new java.util.ArrayList<>();
        try (var files = repository.backupDirectory.walk(1)) {
            return files.filter(f -> f.isFile() && f.getFileName().startsWith(account() + "-"))
                    .sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    /** Deletes all but this account's newest {@code keep} backups; returns how many went. */
    int pruneBackups(int keep) throws IOException {
        java.util.List<Filepath> all = backups();
        for (Filepath old : all.subList(min(keep, all.size()), all.size())) old.deleteIfExists();
        return max(0, all.size() - keep);
    }

    synchronized boolean identityMatches() {
        TrackingIdentity current = resolveCurrentIdentity();
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
        if (maintenance || lifecyclePending || lifecycleInitializationFailed) return false;
        if (busy) return okay;
        synchronized (this) {
            return okay =
                    !maintenance
                            && !lifecyclePending
                            && !lifecycleInitializationFailed
                            && identityMatches()
                            && readOnlyReason == null
                            && !writer.isConflicted(repository.scopeGeneration);
        }
    }

    synchronized ReplaceOutcome replaceNow() {
        WriteIntent intent = currentIntent();
        if (intent == null) return ReplaceOutcome.failedUnchanged(msg("account-identity-bound"));
        return writer.replaceNow(intent);
    }

    synchronized boolean flush(Duration timeout) {
        return writer.flush(timeout);
    }

    void shutdown(boolean persistHistory) {
        boolean lifecycleWasPending = lifecyclePending;
        lifecycleShutdown = true;
        lifecycleEpoch.incrementAndGet();
        fence();
        lifecyclePending = false;
        identityReadyTransition.set(false);
        shutdownPersistHistory = persistHistory;
        // If a lifecycle worker is already fencing an owner, it captured that
        // owner's state before entering its disk wait. Keep the worker alive so
        // it can take one final post-shutdown snapshot after that wait returns;
        // the lifecycle callback itself still never waits for the worker.
        if (lifecycleWasPending) {
            shutdownSnapshotPending = true;
            synchronized (lifecycleExecutorLock) {
                if (lifecycleExecutor == null || lifecycleExecutor.isShutdown()) finalizeShutdown();
                else lifecycleExecutor.shutdown();
            }
            return;
        }
        if (persistHistory && repository.isBound()) scheduleSave();
        writer.shutdown(Duration.ofSeconds(5));
        synchronized (lifecycleExecutorLock) {
            if (lifecycleExecutor != null) lifecycleExecutor.shutdown();
        }
    }

    /** Completes a deferred shutdown snapshot from the lifecycle worker without waiting on it. */
    void finalizeShutdown() {
        if (!shutdownSnapshotPending || !shutdownFinalized.compareAndSet(false, true)) return;
        shutdownSnapshotPending = false;
        try {
            if (shutdownPersistHistory && repository.isBound()) scheduleSave();
        } catch (RuntimeException ex) {
            log.warn(msg("unable-capture"), ex);
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            synchronized (lifecycleExecutorLock) {
                if (lifecycleExecutor != null && !lifecycleExecutor.isShutdown())
                    lifecycleExecutor.shutdown();
            }
        }
    }

    /**
     * Loads the bound scope. Any schema other than the current one is left exactly as it is and makes the
     * scope read-only; it is never rewritten.
     */
    SavedState loadScope(TrackingIdentity identity) {
        readOnlyReason = null;
        SavedState loaded = repository.load();
        if (!loaded.isSupportedSchema()) {
            readOnlyReason = msg("local-data") + loaded.schemaVersion + msg("version-modify");
            log.warn(readOnlyReason);
            return new SavedState();
        }
        return loaded;
    }

    /**
     * The engine's state serialized while holding the engine's lock: qm hands out live sessions and
     * GE records, which the client thread keeps changing once the lock is released. A save {@code
     * header}'s revision and savedAt, and the bound owner, are stamped on the fresh container
     * first, so this one pass is the payload; the schema is the new container's current one.
     */
    String snapshotJson(SavedState header, boolean quick) {
        synchronized (engine) {
            SavedState state = engine.savedState();
            if (header != null) {
                state.setRevision(header.revision);
                state.setSavedAtEpochMillis(header.savedAtEpochMillis);
                if (activeIdentity != null) state.setOwnerKey(activeIdentity.rsProfileKey);
            }
            // History is most of the save and closed sessions rarely change: each session is
            // written on
            // its own and spliced into the same bytes one toJson would give; a quick save reuses
            // the
            // JSON of a closed session whose saveStamp has not moved since.
            var gson = repository.gson;
            var history = state.history;
            state.history = new java.util.ArrayList<>();
            String rest = gson.toJson(state);
            String hole = "\"history\":[";
            int at = rest.indexOf(hole) + hole.length();
            if (at < hole.length() || rest.charAt(at) != ']') {
                state.history = history;
                return gson.toJson(state);
            }
            var json =
                    new StringBuilder(rest.length() + closedJsonChars + 4_096).append(rest, 0, at);
            var next = new java.util.IdentityHashMap<Session, Object[]>();
            long chars = 0;
            for (Session session : history) {
                if (json.length() > at) json.append(',');
                Object[] cached = session == null ? null : closedJson.get(session);
                String one =
                        quick && cached != null && (long) cached[0] == session.saveStamp
                                ? (String) cached[1]
                                : gson.toJson(session);
                if (session != null && session.isClosed()) {
                    next.put(session, new Object[] {session.saveStamp, one});
                    chars += one.length();
                }
                json.append(one);
            }
            closedJson = next;
            closedJsonChars = (int) min(chars, 1 << 28);
            return json.append(rest, at, rest.length()).toString();
        }
    }

    /** Closed history sessions' last JSON by identity: {saveStamp, json}. */
    java.util.Map<Session, Object[]> closedJson = new java.util.IdentityHashMap<>();

    int closedJsonChars;

    /** Every 10th quick save writes everything afresh. */
    int quickSaves;

    /** A detached copy of the engine's state; agl normalizes its labels as a load does. */
    SavedState snapshot() {
        return repository.gson.fromJson(snapshotJson(null, false), SavedState.class);
    }

    WriteIntent currentIntent() {
        return currentIntent(false);
    }

    WriteIntent currentIntent(boolean quick) {
        if (!repository.isBound()
                || readOnlyReason != null
                || writer.isConflicted(repository.scopeGeneration)) {
            return null;
        }
        // Prefer the writer's verified lineage when a prior save is still
        // updating disk lastKnown — avoids queuing another base-N snapshot.
        long baseRevision = max(repository.lastKnownDiskRevision, writer.appliedRevision());
        var header = new SavedState();
        header.setRevision(max(nextRevision, baseRevision + 1L));
        nextRevision = header.revision + 1L;
        header.setSavedAtEpochMillis(SafeMath.now());
        return new WriteIntent(
                activeIdentity,
                repository.scopeGeneration,
                baseRevision,
                header,
                snapshotJson(header, quick && ++quickSaves % 10 != 0));
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
        NEVER_SAVED,
        OK,
        FAILED,
        CONFLICT
    }

    State state;
    String detail;
    boolean retryAvailable;
    String recoveredFrom;

    SaveStatus(State state, String detail, boolean retryAvailable, String recoveredFrom) {
        this.state = state;
        this.detail = Phrases.orEmpty(detail);
        this.retryAvailable = retryAvailable;
        this.recoveredFrom = Phrases.orEmpty(recoveredFrom);
    }

    static SaveStatus neverSaved() {
        return new SaveStatus(State.NEVER_SAVED, "", false, "");
    }

    boolean isFailure() {
        return state == State.FAILED || state == State.CONFLICT;
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
    static final long ACCOUNT_HASH_INVALID = -1L;
    String rsProfileKey;
    long accountHash;

    TrackingIdentity(String rsProfileKey, long accountHash) {
        if (Phrases.blank(rsProfileKey)) throw new IllegalArgumentException("rsProfileKey");
        this.rsProfileKey = rsProfileKey.trim();
        this.accountHash = accountHash;
    }

    /** Filesystem-safe directory name derived from the RS profile key. */
    String folderName() {
        var out = new StringBuilder(rsProfileKey.length());
        for (int i = 0; i < rsProfileKey.length(); i++) {
            char c = rsProfileKey.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        String name = out.toString();
        return name.isEmpty() ? "profile" : name;
    }

    public String toString() {
        return msg("trackingidentity-rsprofilekey") + (accountHash != ACCOUNT_HASH_INVALID) + "}";
    }
}

/**
 * Immutable destination and fencing for one persistence attempt.
 *
 * <p>Queued and retry writes must carry this intent so a later account switch cannot redirect the
 * payload. {@code expectedBaseRevision} is the on-disk revision observed when the snapshot was
 * taken; under the write lock the repository refuses the save unless disk still matches.
 *
 * <p>A non-null {@code json} is the payload, serialized once under the engine lock; {@code state}
 * is then only its header (revision, savedAt), already final and never serialized.
 */
class WriteIntent {
    TrackingIdentity identity;
    long scopeGeneration;
    long expectedBaseRevision;
    SavedState state;
    String json;

    WriteIntent(
            TrackingIdentity identity,
            long scopeGeneration,
            long expectedBaseRevision,
            SavedState state) {
        this(identity, scopeGeneration, expectedBaseRevision, state, null);
    }

    WriteIntent(
            TrackingIdentity identity,
            long scopeGeneration,
            long expectedBaseRevision,
            SavedState state,
            String json) {
        if (state == null) throw new IllegalArgumentException("state");
        this.identity = identity;
        this.scopeGeneration = scopeGeneration;
        this.expectedBaseRevision = SafeMath.nonNeg(expectedBaseRevision);
        this.state = state;
        this.json = json;
    }
}
