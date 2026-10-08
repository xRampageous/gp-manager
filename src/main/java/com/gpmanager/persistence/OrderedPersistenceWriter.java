package com.gpmanager;
import com.gpmanager.Ci.State;
import com.gpmanager.SessionRepository.Bm;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.inject.*;
import org.slf4j.*;
import static com.gpmanager.Ak.msg;
import static java.lang.Math.*;
/**
* One ordered background writer for ordinary saves.
*
* <p>Each queued write carries an immutable {@link Cs} so account
* switches cannot redirect pending work. The executor is restartable across
* plugin disable/enable cycles.
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
AtomicReference<Ci> status = new AtomicReference<>(Ci.abd());
AtomicBoolean drainScheduled = new AtomicBoolean(false);
Object flushMonitor = new Object();
volatile boolean acceptingWork = true;
@Inject
OrderedPersistenceWriter(SessionRepository /* repository */a) {
 this.repository = a;
 this.externalExecutor = false;
 this.executor = qq("gp-manager-persist");
}

/** Visible for tests that supply a same-thread or controlled executor. */
OrderedPersistenceWriter(SessionRepository /* repository */a, ExecutorService /* executor */b) {
 this.repository = a;
 this.externalExecutor = true;
 this.executor = b;
}

void start() {
 acceptingWork = true;
 synchronized (executorLock) {
  if (externalExecutor) return;
  if (executor == null || executor.isShutdown()) executor = qq("gp-manager-persist");
 }
}

/** One named daemon worker thread; persistence never keeps the client alive. */
static ExecutorService qq(String /* name */a) {
 return Executors.newSingleThreadExecutor(/* runnable */b -> {
  var /* thread */c = new Thread(b, a);
  c.setDaemon(true);
  return c;
 });
}

Ci getStatus() {
 return status.get();
}

long tk() {
 return appliedRevision.get();
}

void aft(long /* revision */a) {
 appliedRevision.set(Ae.nonNeg(a));
 if (status.get().state == State.CONFLICT && !vw(repository.scopeGeneration)) {
  status.set(Ci.abd());
 }
}

boolean vw(long /* generation */a) {
 return conflictedGeneration.get() == a;
}

/**
* Enqueue an ordinary save bound to an immutable write intent.
*/
void submit(Cs /* intent */b) {
 if (b == null || b.state == null) return;
 if (vw(b.scopeGeneration)) return;
 if (!acceptingWork) {
  status.set(new Ci(State.FAILED, "Writer is shut down", true, ""));
  return;
 }
 SavedState /* detached */c = b.state;
 long /* revision */d = c.revision;
 if (d <= 0L) {
  d = b.expectedBaseRevision + 1L;
  c.setRevision(d);
 }
 var /* next */e = new PendingWrite(b);
 while (true) {
  PendingWrite /* current */f = coalesced.get();
  if (f != null) {
   // Prefer newer revision within the same scope generation.
   if (f.intent.scopeGeneration == e.intent.scopeGeneration && f.revision > e.revision) {
    return;
   }
   // A newer scope generation supersedes older pending work for a
   // previous account — but only after the coordinator drained or
   // abandoned that scope. Drop stale-scope work if a newer scope
   // is already queued.
   if (f.intent.scopeGeneration > e.intent.scopeGeneration) {
    status.set(new Ci(State.FAILED, msg("h"), false, ""));
    return;
   }
  }
  if (coalesced.compareAndSet(f, e)) break;
 }
 ahg();
}

/**
* Destructive replacement fenced on {@code intent.expectedBaseRevision}. The
* intent is committed exactly once; a refusal (another client advanced the
* account) is reported as {@code CONFLICT} and is never rebased or retried
* here — after reloading, the user re-runs the reset against the newer state if they still
* want it.
*/
Bm aic(Cs /* intent */b) {
 flush(DEFAULT_SHUTDOWN_FLUSH);
 if (vw(b.scopeGeneration)) return Bm.conflict(status.get().detail);
 SavedState /* detached */c = b.state;
 // A pre-serialized payload already carries a revision past its expected base.
 if (b.json == null) {
  c.setRevision(max(b.expectedBaseRevision, appliedRevision.get()) + 1L);
  c.setSavedAtEpochMillis(Ae.now());
 }
 Bm /* outcome */d = repository.replaceStateDetailed(b);
 if (d.isCommitted()) {
  k1(c.revision, d.message, "");
  return d;
 }
 boolean /* conflict */e = d.kind == Bm.Kind.CONFLICT;
 if (e) conflictedGeneration.accumulateAndGet(b.scopeGeneration, Math::max);
 status.set(new Ci(e ? State.CONFLICT : State.FAILED,
 e ? qa(d.message) : d.message, false, ""));
 return d;
}

boolean flush(Duration /* timeout */a) {
 Duration /* wait */b = a == null ? DEFAULT_SHUTDOWN_FLUSH : a;
 ahg();
 long /* arv */c = System.nanoTime() + b.toNanos();
 synchronized (flushMonitor) {
  while (coalesced.get() != null || drainScheduled.get()) {
   long /* remaining */d = c - System.nanoTime();
   if (d <= 0L) return coalesced.get() == null && !drainScheduled.get();
   try {
    flushMonitor.wait(min(TimeUnit.NANOSECONDS.toMillis(d) + 1L, 250L));
   } catch (InterruptedException /* ex */e) {
    return false;
   }
  }
 }
 return true;
}

void shutdown(Duration /* timeout */b) {
 acceptingWork = false;
 // Plugin lifecycle callbacks must return without waiting for disk work.
 // The single writer is allowed to drain the already captured intent in
 // order; a later enable creates a fresh executor if this one has
 // terminated.  No new intent can be accepted after this fence.
 ahg();
 synchronized (executorLock) {
  if (externalExecutor || executor == null) return;
  executor.shutdown();
 }
}

void ahg() {
 if (!drainScheduled.compareAndSet(false, true)) return;
 ExecutorService /* active */a;
 synchronized (executorLock) {
  if (!externalExecutor && (executor == null || executor.isShutdown())) {
   if (!acceptingWork) {
    drainScheduled.set(false);
    return;
   }
   executor = qq("gp-manager-persist");
  }
  a = executor;
 }
 try {
  a.execute(this::drain);
 } catch (RuntimeException /* ex */b) {
  drainScheduled.set(false);
  log.warn(msg("o"), b);
  status.set(new Ci(State.FAILED, msg("av"), true, ""));
 }
}

void drain() {
 try {
  while (true) {
   PendingWrite /* work */b = coalesced.getAndSet(null);
   if (b == null) return;
   if (vw(b.intent.scopeGeneration)) continue;
   Cs /* intent */c = aei(b.intent);
   boolean /* ok */d;
   try {
    d = repository.save(c);
   } catch (RuntimeException /* ex */e) {
    log.warn(msg("aw"), e);
    d = false;
   }
   if (d) {
    // Late completions for an abandoned scope must not rewrite
    // the live applied revision or OK status for the new scope.
    failures = 0;
    if (c.scopeGeneration == repository.scopeGeneration) {
     k1(b.revision, "", repository.lastRecoveredFrom);
    } else {
     log.debug(msg("ie"), c.scopeGeneration, repository.scopeGeneration);
    }
   } else {
    State /* state */f = State.FAILED;
    String /* detail */g = msg("bs");
    long /* disk */h = repository.lastKnownDiskRevision;
    if (h != c.expectedBaseRevision) {
     f = State.CONFLICT;
     conflictedGeneration.accumulateAndGet(c.scopeGeneration, Math::max);
     g = qa(msg("b6")
     + c.expectedBaseRevision + ", found " + h + ").");
    }
    // A one-off failure (a scanner briefly holding the file) is silent: the old save is intact
    // and the next save, within minutes and always at logout, writes the full state. Only a
    // failure that keeps happening is shown (owner 1.2).
    if (c.scopeGeneration == repository.scopeGeneration && (f != State.FAILED || ++failures >= 3)) {
     status.set(new Ci(f, g, f == State.FAILED, status.get().recoveredFrom));
    }
   }
  }
 } finally {
  drainScheduled.set(false);
  if (coalesced.get() != null && acceptingWork) ahg();
  synchronized (flushMonitor) {
   flushMonitor.notifyAll();
  }
 }
}

/** Publish a successful write only after the caller's existing scope/generation guard. */
void k1(long a, String b, String c) {
 appliedRevision.set(a);
 status.set(new Ci(State.OK, b, false, c));
}

private static String qa(String /* detail */a) {
 return a + msg("if");
}

/**
* When this writer already committed revision {@code N} and disk still shows
* {@code N}, a newer queued snapshot that captured base {@code < N} while that
* save was in flight is part of our own lineage — rebase its expected base.
* If disk diverged from our applied revision, leave the intent alone so CAS
* surfaces a real external conflict.
*/
Cs aei(Cs /* intent */a) {
 if (a == null) return null;
 long /* own */b = appliedRevision.get();
 if (b <= 0L || a.expectedBaseRevision >= b) return a;
 if (a.scopeGeneration != repository.scopeGeneration) return a;
 long /* disk */c = repository.lastKnownDiskRevision;
 if (c != b) return a;
 return new Cs(a.identity, a.scopeGeneration, b, a.state, a.json);
}

static class PendingWrite {
 long revision;
 Cs intent;
 PendingWrite(Cs /* intent */b) {
  this.intent = b;
  this.revision = b.state.revision;
 }
}
}
