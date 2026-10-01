package com.gpmanager;
import com.gpmanager.SaveStatus.State;
import com.gpmanager.SessionRepository.ReplaceOutcome;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.inject.*;
import org.slf4j.*;
import static com.gpmanager.GameData.msg;
import static java.lang.Math.*;
/**
* One ordered background writer for ordinary saves.
*
* <p>Each queued write carries an immutable {@link WriteIntent} so account
* switches cannot redirect pending work. The executor is restartable across
* plugin disable/enable cycles.
*/
@Singleton
class OrderedPersistenceWriter {
static final Logger log = LoggerFactory.getLogger(OrderedPersistenceWriter.class);
static final Duration DEFAULT_SHUTDOWN_FLUSH = Duration.ofSeconds(5);
final SessionRepository repository;
final Object executorLock = new Object();
ExecutorService executor;
final boolean externalExecutor;
final AtomicLong appliedRevision = new AtomicLong(0L);
/** Disk truth diverged from this scope's in-memory state; retries must never rebase it. */
final AtomicLong conflictedGeneration = new AtomicLong(-1L);
final AtomicReference<PendingWrite> coalesced = new AtomicReference<>();
final AtomicReference<SaveStatus> status = new AtomicReference<>(SaveStatus.neverSaved());
final AtomicBoolean drainScheduled = new AtomicBoolean(false);
final Object flushMonitor = new Object();
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
if (executor == null || executor.isShutdown()) executor = daemonExecutor("gp-manager-persist");
}
}

/** One named daemon worker thread; persistence never keeps the client alive. */
static ExecutorService daemonExecutor(String name) {
return Executors.newSingleThreadExecutor(runnable -> {
var thread = new Thread(runnable, name);
thread.setDaemon(true);
return thread;
});
}

SaveStatus getStatus() {
return status.get();
}

long getAppliedRevision() {
return appliedRevision.get();
}

void resetAppliedRevision(long revision) {
appliedRevision.set(SafeMath.nonNeg(revision));
if (status.get().state == State.CONFLICT && !isConflicted(repository.scopeGeneration)) {
status.set(SaveStatus.neverSaved());
}
}

boolean isConflicted(long generation) {
return conflictedGeneration.get() == generation;
}

/**
* Enqueue an ordinary save bound to an immutable write intent.
*/
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
if (current.intent.scopeGeneration == next.intent.scopeGeneration && current.revision > next.revision) {
return;
}
// A newer scope generation supersedes older pending work for a
// previous account — but only after the coordinator drained or
// abandoned that scope. Drop stale-scope work if a newer scope
// is already queued.
if (current.intent.scopeGeneration > next.intent.scopeGeneration) {
status.set(new SaveStatus(State.FAILED, msg("h"), false, ""));
return;
}
}
if (coalesced.compareAndSet(current, next)) break;
}
scheduleDrain();
}

/**
* Destructive replacement fenced on {@code intent.expectedBaseRevision}. The
* intent is committed exactly once; a refusal (another client advanced the
* account) is reported as {@code CONFLICT} and is never rebased or retried
* here — after reloading, the user re-runs the reset against the newer state if they still
* want it.
*/
ReplaceOutcome replaceNow(WriteIntent intent) {
flush(DEFAULT_SHUTDOWN_FLUSH);
if (isConflicted(intent.scopeGeneration)) return ReplaceOutcome.conflict(status.get().detail);
SavedState detached = intent.state;
// A pre-serialized payload already carries a revision past its expected base.
if (intent.json == null) {
detached.setRevision(max(intent.expectedBaseRevision, appliedRevision.get()) + 1L);
detached.setSavedAtEpochMillis(System.currentTimeMillis());
}
ReplaceOutcome outcome = repository.replaceStateDetailed(intent);
if (outcome.isCommitted()) {
appliedRevision.set(detached.revision);
status.set(new SaveStatus(State.OK, outcome.message, false, ""));
return outcome;
}
boolean conflict = outcome.kind == ReplaceOutcome.Kind.CONFLICT;
if (conflict) conflictedGeneration.accumulateAndGet(intent.scopeGeneration, Math::max);
status.set(new SaveStatus(conflict ? State.CONFLICT : State.FAILED,
conflict ? conflictDetail(outcome.message) : outcome.message, false, ""));
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
Thread.currentThread().interrupt();
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
log.warn(msg("o"), ex);
status.set(new SaveStatus(State.FAILED, msg("av"), true, ""));
}
}

void drain() {
try {
while (true) {
PendingWrite work = coalesced.getAndSet(null);
if (work == null) return;
if (isConflicted(work.intent.scopeGeneration)) continue;
WriteIntent intent = rebaseOntoOwnLineage(work.intent);
boolean ok;
try {
ok = repository.save(intent);
} catch (RuntimeException ex) {
log.warn(msg("aw"), ex);
ok = false;
}
if (ok) {
// Late completions for an abandoned scope must not rewrite
// the live applied revision or OK status for the new scope.
if (intent.scopeGeneration == repository.scopeGeneration) {
appliedRevision.set(work.revision);
status.set(new SaveStatus(State.OK, "", false, repository.lastRecoveredFrom));
} else {
log.debug(msg("ie"), intent.scopeGeneration, repository.scopeGeneration);
}
} else {
State state = State.FAILED;
String detail = msg("bs");
long disk = repository.lastKnownDiskRevision;
if (disk != intent.expectedBaseRevision) {
state = State.CONFLICT;
conflictedGeneration.accumulateAndGet(intent.scopeGeneration, Math::max);
detail = conflictDetail("Disk revision changed (expected base "
+ intent.expectedBaseRevision + ", found " + disk + ").");
}
if (intent.scopeGeneration == repository.scopeGeneration) {
status.set(new SaveStatus(state, detail, state == State.FAILED, status.get().recoveredFrom));
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

private static String conflictDetail(String detail) {
return detail + msg("if");
}

/**
* When this writer already committed revision {@code N} and disk still shows
* {@code N}, a newer queued snapshot that captured base {@code < N} while that
* save was in flight is part of our own lineage — rebase its expected base.
* If disk diverged from our applied revision, leave the intent alone so CAS
* surfaces a real external conflict.
*/
WriteIntent rebaseOntoOwnLineage(WriteIntent intent) {
if (intent == null) return null;
long own = appliedRevision.get();
if (own <= 0L || intent.expectedBaseRevision >= own) return intent;
if (intent.scopeGeneration != repository.scopeGeneration) return intent;
long disk = repository.lastKnownDiskRevision;
if (disk != own) return intent;
return new WriteIntent(intent.identity, intent.scopeGeneration, own, intent.state, intent.json);
}

static class PendingWrite {
final long revision;
final WriteIntent intent;
PendingWrite(WriteIntent intent) {
this.intent = intent;
this.revision = intent.state.revision;
}
}
}
