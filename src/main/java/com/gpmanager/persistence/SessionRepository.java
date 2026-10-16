package com.gpmanager;

import static com.gpmanager.GameData.msg;
import static com.gpmanager.SafeMath.nonNeg;
import static java.lang.Math.*;

import com.google.gson.*;
import com.gpmanager.SaveStatus.State;
import com.gpmanager.SessionRepository.ReplaceOutcome;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.inject.*;
import lombok.*;
import net.runelite.client.util.Filepath;
import org.slf4j.*;

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
    Filepath rootDirectory;

    /** Profile backups, one folder, newest 10 per account (owner 2026-09-28). */
    Filepath backupDirectory;

    boolean accountAware;

    /** The bound scope's save files. */
    ScopeFiles bound;

    boolean initialized;
    TrackingIdentity boundIdentity;
    long lastKnownDiskRevision;
    long scopeGeneration;

    @Inject
    SessionRepository(Gson gson) {
        this.gson = gson;
        accountAware = true;
    }

    SessionRepository(Gson gson, Filepath dataDirectory) {
        this(gson, dataDirectory, false);
    }

    SessionRepository(Gson gson, Filepath dataDirectory, boolean accountAware) {
        this.gson = gson;
        this.accountAware = accountAware;
        initialize(dataDirectory);
    }

    synchronized void initialize(Filepath pluginDirectory) {
        if (pluginDirectory == null) throw new IllegalArgumentException("pluginDirectory");
        Filepath rooted = pluginDirectory.rooted();
        if (initialized) {
            if (!rootDirectory.equals(rooted))
                throw new IllegalStateException(msg("gp-manager-repository"));
            return;
        }
        this.rootDirectory = rooted;
        backupDirectory = rooted.joinSegment("backups");
        this.initialized = true;
        if (accountAware) {
            // Production starts unbound: do not write shared root history until
            // an RS profile identity is available and explicitly bound.
            bindScope(this.rootDirectory.joinSegment(UNASSIGNED_MARKER), null);
        } else {
            // Single-scope / test mode: the provided directory is the store.
            bindScope(rooted, null);
        }
    }

    void requireInit() {
        if (!initialized) throw new IllegalStateException(msg("gp-manager-repository-initialized"));
    }

    /** One scope directory's primary, backup, next and write-lock files. */
    static class ScopeFiles {
        Filepath dir;
        Filepath state;
        Filepath backup;
        Filepath next;
        Filepath lock;

        ScopeFiles(Filepath dir) {
            this.dir = dir;
            state = dir.joinSegment("sessions.json");
            backup = dir.joinSegment(msg("sessions-backup-json-2"));
            next = dir.joinSegment("sessions.next.json");
            lock = dir.joinSegment("sessions.write.lock");
        }
    }

    void bindScope(Filepath directory, TrackingIdentity identity) {
        bound = new ScopeFiles(directory);
        boundIdentity = identity;
        lastKnownDiskRevision = 0L;
        scopeGeneration++;
    }

    /** Directory for an account identity without changing the live bind. */
    Filepath scopeDir(TrackingIdentity identity) {
        requireInit();
        if (identity == null)
            return accountAware ? rootDirectory.joinSegment(UNASSIGNED_MARKER) : rootDirectory;
        return rootDirectory.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.folderName());
    }

    /**
     * The backup file the last {@link #load()} had to fall back to; empty when the primary loaded.
     */
    volatile String lastRecoveredFrom = "";

    /** The folder holding the bound profile's save files. */
    Filepath dataDir() {
        requireInit();
        return bound.dir;
    }

    boolean isBound() {
        return !accountAware || boundIdentity != null;
    }

    /** Bind writes/loads to an account scope. Does not migrate unassigned data. */
    synchronized void bindIdentity(TrackingIdentity identity) {
        requireInit();
        if (identity == null) throw new IllegalArgumentException("identity");
        Filepath accountDir =
                rootDirectory.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.folderName());
        bindScope(accountDir, identity);
    }

    /** Leave any account scope; subsequent saves refuse until rebound. */
    synchronized void unbind() {
        requireInit();
        bindScope(rootDirectory.joinSegment(UNASSIGNED_MARKER), null);
    }

    synchronized SavedState load() {
        requireInit();
        lastRecoveredFrom = "";
        // A save from an older schema starts fresh: it is kept beside the profile, never read.
        retireIfOlder(bound);
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
                lastKnownDiskRevision = nonNeg(state.revision);
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
                SavedState i = readState(bound.backup);
                log.warn(msg("recovered-gp"), bound.backup.getFileName());
                lastKnownDiskRevision = nonNeg(i.revision);
                lastRecoveredFrom = bound.backup.getFileName().toString();
                return i;
            } catch (Exception j) {
                log.warn(msg("unable-read"), bound.backup, j);
            }
        }
        lastKnownDiskRevision = 0L;
        return new SavedState();
    }

    SavedState readState(Filepath path) throws IOException {
        try (Reader reader = path.openReader(StandardOpenOption.READ)) {
            return parseState(gson, new JsonParser().parse(reader));
        } catch (RuntimeException ex) {
            throw new IOException(msg("unreadable-session"), ex);
        }
    }

    /** Parses one payload. */
    static SavedState parseState(Gson gson, JsonElement json) throws IOException {
        if (json == null || !json.isJsonObject()) throw new IOException(msg("empty-session"));
        SavedState state = gson.fromJson(json, SavedState.class);
        if (state == null) throw new IOException(msg("empty-session"));
        return state;
    }

    /** Backup parse/validation with the caller's existing Gson policy and error behavior. */
    static SavedState parseBackup(Gson gson, Filepath file) throws IOException {
        return parseState(gson, new JsonParser().parse(readText(file)));
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
            return ReplaceOutcome.failedUnchanged("state was null");
        return commit(intent, true);
    }

    /**
     * One fenced commit of {@code intent}. Returns {@code COMMITTED} once primary (and, for resets,
     * backup alignment) finished, {@code COMMITTED_PRIMARY_ONLY} when the primary was replaced but
     * a later step faulted, {@code CONFLICT} when disk no longer matches the expected base, else
     * {@code FAILED_UNCHANGED}.
     */
    ReplaceOutcome commit(WriteIntent intent, boolean destructiveReset) {
        if (intent == null || intent.state == null) throw new IllegalArgumentException("intent");
        SavedState state = intent.state;
        TrackingIdentity targetIdentity = intent.identity;
        if (accountAware && targetIdentity == null) {
            log.warn(msg("refusing-gp"));
            return ReplaceOutcome.failedUnchanged(msg("account-identity-write"));
        }
        var target = new ScopeFiles(scopeDir(targetIdentity));
        // Owner key is taken from the intent, never from a later live bind.
        if (targetIdentity != null) state.setOwnerKey(targetIdentity.rsProfileKey);
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        if (state.savedAtEpochMillis <= 0L) state.setSavedAtEpochMillis(SafeMath.now());
        FileLock lock = null;
        // Set once the primary replacement has been attempted; a fault after that
        // point is classified by inspecting the primary under the still-held lock.
        boolean primaryAttempted = false;
        try {
            target.dir.createDirectories();
            lock = lockForWrite(target.lock);
            if (lock == null) {
                log.warn(msg("another-gp"));
                return ReplaceOutcome.failedUnchanged(msg("another-client-holds"));
            }
            retireIfOlder(target);
            Optional<Integer> diskSchema = peek(target.state).map(SessionRepository::schemaOf);
            if (diskSchema.isPresent() && diskSchema.get() != SavedState.CURRENT_SCHEMA_VERSION) {
                // Any other schema (a newer version) is preserved read-only.
                log.warn(msg("refusing-overwrite"), diskSchema.get());
                return ReplaceOutcome.failedUnchanged(msg("disk-state") + diskSchema.get());
            }
            long diskRevision =
                    peekRevision(target.state).orElse(backupRevision(target.backup).orElse(0L));
            // Compare-and-swap against the base revision observed when the
            // snapshot was created. Equal competing writers both claiming base N
            // must not both succeed — and a destructive reset is no exception:
            // a newer revision written by another client wins and the reset aborts.
            if (diskRevision != intent.expectedBaseRevision) {
                log.warn(
                        msg("refusing-concurrent"),
                        destructiveReset ? "reset" : "save",
                        diskRevision,
                        intent.expectedBaseRevision);
                if (sameScope(targetIdentity)) lastKnownDiskRevision = diskRevision;
                return ReplaceOutcome.conflict(
                        msg("disk-revision")
                                + intent.expectedBaseRevision
                                + ", found "
                                + diskRevision
                                + ")");
            }
            if (state.revision <= 0L) {
                state.setRevision(diskRevision + 1L);
            } else if (state.revision <= diskRevision) {
                log.warn(msg("refusing-non"), state.revision, diskRevision);
                return ReplaceOutcome.failedUnchanged(
                        "Revision " + state.revision + msg("advance-disk") + diskRevision);
            }
            try (Writer writer =
                    target.next.openWriter(
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                if (intent.json == null) gson.toJson(state, writer);
                else writer.write(intent.json);
            }
            SavedState validated = readState(target.next);
            if (!destructiveReset && target.state.exists()) {
                boolean valid = false;
                try {
                    readState(target.state);
                    valid = true;
                } catch (Exception ex) {
                    setAside(target);
                    if (target.state.exists()) throw new IOException(msg("cannot-preserve"), ex);
                }
                if (valid) {
                    replaceBackup(target, msg("sessions-backup-json"));
                }
            }
            primaryAttempted = true;
            // One atomic move: a crash leaves either the old save or the new one, never neither.
            replaceFile(target.next, target.state);
            if (sameScope(targetIdentity)) lastKnownDiskRevision = validated.revision;
            if (destructiveReset) {
                // After a reset the crash copy must not resurrect the cleared data.
                try {
                    replaceBackup(target, msg("sessions-backup-reset"));
                } catch (IOException backupEx) {
                    invalidateBackup(target, msg("reset-primary-committed"));
                    log.warn(msg("reset-primary-committed-but"), backupEx);
                    return ReplaceOutcome.committedPrimaryOnly(msg("primary-reset"));
                }
                return ReplaceOutcome.committed(msg("reset-committed"));
            }
            return ReplaceOutcome.committed("Save committed");
        } catch (IOException ex) {
            log.warn(destructiveReset ? msg("unable-replace") : msg("unable-save"), ex);
            // Still under the account lock, so a primary holding our revision can
            // only be ours: classify, never re-issue the write.
            if (primaryAttempted && peekRevision(target.state).orElse(-1L) == state.revision) {
                if (sameScope(targetIdentity)) lastKnownDiskRevision = state.revision;
                return ReplaceOutcome.committedPrimaryOnly(msg("primary-already"));
            }
            return ReplaceOutcome.failedUnchanged(
                    destructiveReset ? msg("reset-did") : msg("save-did"));
        } finally {
            releaseLock(lock);
        }
    }

    boolean sameScope(TrackingIdentity targetIdentity) {
        if (boundIdentity == null && targetIdentity == null) return true;
        return boundIdentity != null && boundIdentity.equals(targetIdentity);
    }

    @AllArgsConstructor
    static class ReplaceOutcome {
        enum Kind {
            COMMITTED,
            COMMITTED_PRIMARY_ONLY,
            /** Disk advanced past the expected base; nothing was written. */
            CONFLICT,
            FAILED_UNCHANGED
        }

        Kind kind;
        String message;

        static ReplaceOutcome committed(String message) {
            return new ReplaceOutcome(Kind.COMMITTED, message);
        }

        static ReplaceOutcome committedPrimaryOnly(String message) {
            return new ReplaceOutcome(Kind.COMMITTED_PRIMARY_ONLY, message);
        }

        static ReplaceOutcome failedUnchanged(String message) {
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
            Filepath tombstone =
                    files.dir.joinSegment(
                            msg("sessions-backup-invalidated") + SafeMath.now() + ".json");
            files.backup.moveTo(tombstone, StandardCopyOption.REPLACE_EXISTING);
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
            return path.exists()
                    ? Optional.of(new JsonParser().parse(readText(path)).getAsJsonObject())
                    : Optional.empty();
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /** A payload's schema; 0 when it names none. */
    static int schemaOf(JsonObject object) {
        return object.has("schemaVersion") && object.get("schemaVersion").isJsonPrimitive()
                ? object.get("schemaVersion").getAsInt()
                : 0;
    }

    /** Sets aside a primary or backup from an older schema, so the profile starts fresh. */
    void retireIfOlder(ScopeFiles files) {
        for (Filepath path : new Filepath[] {files.state, files.backup}) {
            int schema =
                    peek(path)
                            .map(SessionRepository::schemaOf)
                            .orElse(SavedState.CURRENT_SCHEMA_VERSION);
            if (schema >= SavedState.CURRENT_SCHEMA_VERSION) continue;
            try {
                String name = path.getFileName().toString().replace(".json", "");
                path.moveTo(
                        files.dir.joinSegment(
                                name + ".schema" + schema + "-" + SafeMath.now() + ".json"),
                        StandardCopyOption.REPLACE_EXISTING);
                log.warn(msg("older-save"), schema);
            } catch (IOException ex) {
                log.warn(msg("unable-preserve"), ex);
            }
        }
    }

    Optional<Long> peekRevision(Filepath path) {
        return peek(path)
                .map(
                        object ->
                                object.has("revision") && object.get("revision").isJsonPrimitive()
                                        ? object.get("revision").getAsLong()
                                        : 0L);
    }

    // Package visibility permits deterministic disk-failure tests without
    // introducing production fault switches.
    void replaceFile(Filepath source, Filepath target) throws IOException {
        try {
            atomicMove(source, target);
        } catch (AtomicMoveNotSupportedException ex) {
            // Windows/network volumes without atomic rename: plain replace of a validated file.
            source.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
        } catch (AccessDeniedException ex) {
            // Antivirus or Windows Search can hold a just-written file for a moment; one more try
            // often clears it, and the writer retries later without alarming anyone (owner 1.2).
            atomicMove(source, target);
        }
    }

    static String readText(Filepath path) throws IOException {
        try (Reader reader = path.openReader(StandardOpenOption.READ)) {
            var text = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) text.append(buffer, 0, count);
            return text.toString();
        }
    }

    void atomicMove(Filepath source, Filepath target) throws IOException {
        source.moveTo(target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    FileLock lockForWrite(Filepath lockPath) throws IOException {
        lockPath.getParent().createDirectories();
        FileChannel channel =
                lockPath.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
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
            FileChannel channel = lock.channel();
            lock.release();
            channel.close();
        } catch (IOException ex) {
            log.debug(msg("unable-release"), ex);
        }
    }

    void setAside(ScopeFiles files) {
        try {
            if (files.state.exists()) {
                Filepath backup =
                        files.dir.joinSegment("sessions.corrupt-" + SafeMath.now() + ".json");
                files.state.moveTo(backup, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException backupError) {
            log.warn(msg("unable-preserve"), backupError);
        }
    }

    /** Atomic backup replacement, using each operation's original temporary file name. */
    void replaceBackup(ScopeFiles files, String tempName) throws IOException {
        Filepath temp = files.dir.joinSegment(tempName);
        files.state.copyTo(temp, StandardCopyOption.REPLACE_EXISTING);
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
    Object executorLock = new Object();
    ExecutorService executor;

    /** Ordinary saves that failed in a row; the warning waits for the third. */
    int failures;

    boolean externalExecutor;
    AtomicLong appliedRevision = new AtomicLong(0L);

    /** Disk truth diverged from this scope's in-memory state; retries must never rebase it. */
    AtomicLong conflictedGeneration = new AtomicLong(-1L);

    AtomicReference<PendingWrite> coalesced = new AtomicReference<>();
    AtomicReference<SaveStatus> status = new AtomicReference<>(SaveStatus.neverSaved());
    AtomicBoolean drainScheduled = new AtomicBoolean(false);
    Object flushMonitor = new Object();
    volatile boolean acceptingWork = true;

    @Inject
    OrderedPersistenceWriter(SessionRepository repository) {
        this.repository = repository;
        externalExecutor = false;
        executor = daemonExecutor("gp-manager-persist");
    }

    /** Visible for tests that supply a same-thread or controlled executor. */
    OrderedPersistenceWriter(SessionRepository repository, ExecutorService executor) {
        this.repository = repository;
        externalExecutor = true;
        this.executor = executor;
    }

    void start() {
        acceptingWork = true;
        synchronized (executorLock) {
            if (externalExecutor) return;
            if (executor == null || executor.isShutdown())
                executor = daemonExecutor("gp-manager-persist");
        }
    }

    /** One named daemon worker thread; persistence never keeps the client alive. */
    static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(
                runnable -> {
                    var thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    SaveStatus getStatus() {
        return status.get();
    }

    long appliedRevision() {
        return appliedRevision.get();
    }

    void resetRevision(long revision) {
        appliedRevision.set(SafeMath.nonNeg(revision));
        if (status.get().state == State.CONFLICT && !isConflicted(repository.scopeGeneration)) {
            status.set(SaveStatus.neverSaved());
        }
    }

    boolean isConflicted(long generation) {
        return conflictedGeneration.get() == generation;
    }

    /** Enqueue an ordinary save bound to an immutable write intent. */
    void submit(WriteIntent intent) {
        if (intent == null || intent.state == null) return;
        if (isConflicted(intent.scopeGeneration)) return;
        if (!acceptingWork) {
            status.set(new SaveStatus(State.FAILED, "Writer is shut down", true, ""));
            return;
        }
        SavedState detached = intent.state;
        long revision = detached.revision;
        if (revision <= 0L) {
            revision = intent.expectedBaseRevision + 1L;
            detached.setRevision(revision);
        }
        var next = new PendingWrite(intent);
        while (true) {
            PendingWrite current = coalesced.get();
            if (current != null) {
                // Prefer newer revision within the same scope generation.
                if (current.intent.scopeGeneration == next.intent.scopeGeneration
                        && current.revision > next.revision) {
                    return;
                }
                // A newer scope generation supersedes older pending work for a
                // previous account — but only after the coordinator drained or
                // abandoned that scope. Drop stale-scope work if a newer scope
                // is already queued.
                if (current.intent.scopeGeneration > next.intent.scopeGeneration) {
                    status.set(new SaveStatus(State.FAILED, msg("pending-save"), false, ""));
                    return;
                }
            }
            if (coalesced.compareAndSet(current, next)) break;
        }
        scheduleDrain();
    }

    /**
     * Destructive replacement fenced on {@code intent.expectedBaseRevision}. The intent is
     * committed exactly once; a refusal (another client advanced the account) is reported as {@code
     * CONFLICT} and is never rebased or retried here — after reloading, the user re-runs the reset
     * against the newer state if they still want it.
     */
    ReplaceOutcome replaceNow(WriteIntent intent) {
        flush(DEFAULT_SHUTDOWN_FLUSH);
        if (isConflicted(intent.scopeGeneration))
            return ReplaceOutcome.conflict(status.get().detail);
        SavedState detached = intent.state;
        // A pre-serialized payload already carries a revision past its expected base.
        if (intent.json == null) {
            detached.setRevision(max(intent.expectedBaseRevision, appliedRevision.get()) + 1L);
            detached.setSavedAtEpochMillis(SafeMath.now());
        }
        ReplaceOutcome outcome = repository.replaceStateDetailed(intent);
        if (outcome.isCommitted()) {
            publish(detached.revision, outcome.message, "");
            return outcome;
        }
        boolean conflict = outcome.kind == ReplaceOutcome.Kind.CONFLICT;
        if (conflict) conflictedGeneration.accumulateAndGet(intent.scopeGeneration, Math::max);
        status.set(
                new SaveStatus(
                        conflict ? State.CONFLICT : State.FAILED,
                        conflict ? conflictDetail(outcome.message) : outcome.message,
                        false,
                        ""));
        return outcome;
    }

    boolean flush(Duration timeout) {
        Duration wait = timeout == null ? DEFAULT_SHUTDOWN_FLUSH : timeout;
        scheduleDrain();
        long deadline = System.nanoTime() + wait.toNanos();
        synchronized (flushMonitor) {
            while (coalesced.get() != null || drainScheduled.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return coalesced.get() == null && !drainScheduled.get();
                try {
                    flushMonitor.wait(min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1L, 250L));
                } catch (InterruptedException ex) {
                    return false;
                }
            }
        }
        return true;
    }

    void shutdown(Duration timeout) {
        acceptingWork = false;
        // Plugin lifecycle callbacks must return without waiting for disk work.
        // The single writer is allowed to drain the already captured intent in
        // order; a later enable creates a fresh executor if this one has
        // terminated.  No new intent can be accepted after this fence.
        scheduleDrain();
        synchronized (executorLock) {
            if (externalExecutor || executor == null) return;
            executor.shutdown();
        }
    }

    void scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return;
        ExecutorService active;
        synchronized (executorLock) {
            if (!externalExecutor && (executor == null || executor.isShutdown())) {
                if (!acceptingWork) {
                    drainScheduled.set(false);
                    return;
                }
                executor = daemonExecutor("gp-manager-persist");
            }
            active = executor;
        }
        try {
            active.execute(this::drain);
        } catch (RuntimeException ex) {
            drainScheduled.set(false);
            log.warn(msg("unable-schedule-gp-manager"), ex);
            status.set(new SaveStatus(State.FAILED, msg("persistence-executor"), true, ""));
        }
    }

    void drain() {
        try {
            while (true) {
                PendingWrite work = coalesced.getAndSet(null);
                if (work == null) return;
                if (isConflicted(work.intent.scopeGeneration)) continue;
                WriteIntent intent = rebase(work.intent);
                boolean ok;
                try {
                    ok = repository.save(intent);
                } catch (RuntimeException ex) {
                    log.warn(msg("gp-manager-background"), ex);
                    ok = false;
                }
                if (ok) {
                    // Late completions for an abandoned scope must not rewrite
                    // the live applied revision or OK status for the new scope.
                    failures = 0;
                    if (intent.scopeGeneration == repository.scopeGeneration) {
                        publish(work.revision, "", repository.lastRecoveredFrom);
                    } else {
                        log.debug(
                                msg("ignoring-late"),
                                intent.scopeGeneration,
                                repository.scopeGeneration);
                    }
                } else {
                    State state = State.FAILED;
                    String detail = msg("save-failed");
                    long disk = repository.lastKnownDiskRevision;
                    if (disk != intent.expectedBaseRevision) {
                        state = State.CONFLICT;
                        conflictedGeneration.accumulateAndGet(intent.scopeGeneration, Math::max);
                        detail =
                                conflictDetail(
                                        msg("disk-revision")
                                                + intent.expectedBaseRevision
                                                + ", found "
                                                + disk
                                                + ").");
                    }
                    // A one-off failure (a scanner briefly holding the file) is silent: the old
                    // save is intact
                    // and the next save, within minutes and always at logout, writes the full
                    // state. Only a
                    // failure that keeps happening is shown (owner 1.2).
                    if (intent.scopeGeneration == repository.scopeGeneration
                            && (state != State.FAILED || ++failures >= 3)) {
                        status.set(
                                new SaveStatus(
                                        state,
                                        detail,
                                        state == State.FAILED,
                                        status.get().recoveredFrom));
                    }
                }
            }
        } finally {
            drainScheduled.set(false);
            if (coalesced.get() != null && acceptingWork) scheduleDrain();
            synchronized (flushMonitor) {
                flushMonitor.notifyAll();
            }
        }
    }

    /** Publish a successful write only after the caller's existing scope/generation guard. */
    void publish(long revision, String message, String detail) {
        appliedRevision.set(revision);
        status.set(new SaveStatus(State.OK, message, false, detail));
    }

    private static String conflictDetail(String detail) {
        return detail + msg("tracking-paused-back");
    }

    /**
     * When this writer already committed revision {@code N} and disk still shows {@code N}, a newer
     * queued snapshot that captured base {@code < N} while that save was in flight is part of our
     * own lineage — rebase its expected base. If disk diverged from our applied revision, leave the
     * intent alone so CAS surfaces a real external conflict.
     */
    WriteIntent rebase(WriteIntent intent) {
        if (intent == null) return null;
        long own = appliedRevision.get();
        if (own <= 0L || intent.expectedBaseRevision >= own) return intent;
        if (intent.scopeGeneration != repository.scopeGeneration) return intent;
        long disk = repository.lastKnownDiskRevision;
        if (disk != own) return intent;
        return new WriteIntent(
                intent.identity, intent.scopeGeneration, own, intent.state, intent.json);
    }

    static class PendingWrite {
        long revision;
        WriteIntent intent;

        PendingWrite(WriteIntent intent) {
            this.intent = intent;
            this.revision = intent.state.revision;
        }
    }
}
