package com.gpmanager;

import static com.gpmanager.GameData.msg;

import static java.lang.Math.*;

import com.gpmanager.SaveStatus.State;
import com.gpmanager.SessionRepository.ReplaceOutcome;

import org.slf4j.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.inject.*;

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
        this.externalExecutor = false;
        this.executor = daemonExecutor("gp-manager-persist");
    }

    /** Visible for tests that supply a same-thread or controlled executor. */
    OrderedPersistenceWriter(SessionRepository repository, ExecutorService executor) {
        this.repository = repository;
        this.externalExecutor = true;
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
