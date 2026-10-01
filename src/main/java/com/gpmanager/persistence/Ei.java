package com.gpmanager;
import com.gpmanager.SessionRepository.Bm;
import lombok.*;
import com.google.gson.JsonParser;
import java.time.*;
import javax.inject.*;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Filepath;
import org.slf4j.*;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static java.lang.Math.*;
import static com.gpmanager.Ak.msg;
/**
* Coordinates account binding, detached snapshots, and ordered persistence.
*/
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class Ei {
static final Logger log = LoggerFactory.getLogger(Ei.class);
final Client client;
final ConfigManager configManager;
@Getter
final SessionRepository repository;
@Getter
final OrderedPersistenceWriter writer;
final Am engine;
long nextRevision = 1L;
@Getter
TrackingIdentity activeIdentity;
/** Set when the bound scope holds data this plugin must not rewrite (newer schema or refused migration). */
String readOnlyReason;
/** A restore or reset is replacing the bound account; ingestion is paused until it re-primes. */
volatile boolean maintenance;
/** Lifecycle identity work is serialized off the RuneLite lifecycle/client callbacks. */
final Object lifecycleExecutorLock = new Object();
ExecutorService lifecycleExecutor;
volatile boolean lifecycleShutdown;
volatile boolean lifecyclePending;
volatile boolean lifecycleInitializationFailed;
volatile boolean shutdownSnapshotPending;
volatile boolean shutdownPersistHistory;
/** Serializes backup file IO without holding the coordinator monitor the client thread reads. */
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
   lifecycleExecutor = OrderedPersistenceWriter.qq(msg("et"));
  }
 }
}

/**
* Runs repository-directory/configuration initialization away from the
* RuneLite plugin lifecycle callback. Identity work queued immediately
* afterwards remains ordered behind this operation on the same executor.
*/
void vr(Runnable initialization) {
 synchronized (lifecycleExecutorLock) {
  // A plugin re-enable may arrive after the prior lifecycle fenced
  // this coordinator. Reopen only the lifecycle executor; writer
  // start remains ordered inside the initialization task.
  if (lifecycleShutdown) {
   lifecycleShutdown = false;
   lifecycleEpoch.incrementAndGet();
  }
 }
 final long epoch = lifecycleEpoch.get();
 ExecutorService executor = lifecycleExecutor();
 if (executor == null) {
  lifecycleInitializationFailed = true;
  return;
 }
 try {
  executor.execute(() -> {
   try {
    if (!lifecycleShutdown && epoch == lifecycleEpoch.get()) initialization.run();
   } catch (RuntimeException ex) {
    if (epoch == lifecycleEpoch.get()) lifecycleInitializationFailed = true;
    log.warn(msg("t"), ex);
   }
  });
 } catch (RuntimeException ex) {
  lifecycleInitializationFailed = true;
  log.warn(msg("f"), ex);
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
* Captures the current identity without touching disk, then performs the
* account bind/load/flush on the lifecycle executor. The next game tick
* consumes the ready transition after the worker has installed the scope.
*/
void aje(BooleanSupplier persistHistory) {
 ajz(resolveCurrentIdentity(), persistHistory, false);
}

/** Seam for deterministic lifecycle tests and explicit profile events. */
void ajz(TrackingIdentity next, boolean persistHistory) {
 ajz(next, () -> persistHistory, true);
}

void ajz(TrackingIdentity next, BooleanSupplier persistHistory, boolean capturePreviousOnCaller) {
 agw(persistHistory, capturePreviousOnCaller, "identity switch",
 persist -> ajy(next, persist, false));
}

/**
* One lifecycle job on the executor: capture the old owner's latest state (on the caller when
* asked, else on the worker), run the job unless shut down, then publish whether it is ready.
*/
void agw(BooleanSupplier persistHistory, boolean capturePreviousOnCaller, String what,
Predicate<Boolean> job) {
 final long request = lifecycleRequest.incrementAndGet();
 synchronized (this) {
  if (lifecycleShutdown) return;
  // Capture the old owner's latest state before the worker can enter
  // its disk wait. A pending earlier lifecycle operation has already
  // made the same capture at its boundary.
  if (capturePreviousOnCaller && !lifecyclePending && activeIdentity != null && persistHistory.getAsBoolean()) {
   ahe();
  }
  lifecyclePending = true;
  identityReadyTransition.set(false);
 }
 ExecutorService executor = lifecycleExecutor();
 if (executor == null) {
  sz(request, false);
  return;
 }
 try {
  executor.execute(() -> {
   boolean ready = false;
   try {
    boolean persist = persistHistory.getAsBoolean();
    if (!capturePreviousOnCaller) {
     synchronized (this) {
      if (!lifecycleShutdown && activeIdentity != null && persist) ahf();
     }
    }
    if (!lifecycleShutdown) ready = job.test(persist);
   } catch (RuntimeException ex) {
    log.warn("Unable to complete GP Manager " + what, ex);
   }
   sz(request, ready);
  });
 } catch (RuntimeException ex) {
  log.warn("Unable to schedule GP Manager " + what, ex);
  sz(request, false);
 }
}

ExecutorService lifecycleExecutor() {
 synchronized (lifecycleExecutorLock) {
  if (lifecycleShutdown) return null;
  if (lifecycleExecutor == null || lifecycleExecutor.isShutdown()) {
   lifecycleExecutor = OrderedPersistenceWriter.qq(msg("eu"));
  }
  return lifecycleExecutor;
 }
}

void sz(long request, boolean ready) {
 if (lifecycleShutdown && shutdownSnapshotPending) sq();
 if (request != lifecycleRequest.get()) return;
 lifecyclePending = false;
 if (ready && !lifecycleShutdown) identityReadyTransition.set(true);
}

/** True once for the client tick that may resume ingestion after an async bind. */
boolean py() {
 return identityReadyTransition.compareAndSet(true, false);
}

/**
* Attempt to bind {@code next}. When {@code next} is null, returns whether a
* prior identity remains bound. Failed flush/save refuses the switch and
* preserves the previous account's in-memory state.
*/
synchronized boolean ajy(TrackingIdentity next, boolean persistHistory) {
 return ajy(next, persistHistory, true);
}

synchronized boolean ajy(TrackingIdentity next, boolean persistHistory, boolean capturePrevious) {
 if (next == null) return activeIdentity != null;
 if (next.equals(activeIdentity)) return true;
 if (lifecycleShutdown && !capturePrevious) return false;
 if (activeIdentity != null && persistHistory) {
  if (capturePrevious) ahe();
  if (!th(Duration.ofSeconds(5))) {
   log.warn(msg("ig"));
   return false;
  }
  Ci status = writer.getStatus();
  if (status.auq() || status.state == Ci.State.CONFLICT) {
   log.warn(msg("ih"), status.detail);
   return false;
  }
 }
 TrackingIdentity previous = activeIdentity;
 long apo = writer.tk();
 long previousNextRevision = nextRevision;
 activeIdentity = next;
 try {
  repository.mc(next);
  if (persistHistory) {
   SavedState loaded = yq(next);
   if (lifecycleShutdown && !capturePrevious) {
    awz(previous, apo, previousNextRevision);
    return false;
   }
   writer.aft(loaded.revision);
   nextRevision = max(loaded.revision + 1L, 1L);
   engine.agl(next.rsProfileKey, loaded, System.currentTimeMillis());
  } else {
   writer.aft(0L);
   nextRevision = 1L;
   engine.agl(next.rsProfileKey, new SavedState(), System.currentTimeMillis());
  }
 } catch (RuntimeException ex) {
  awz(previous, apo, previousNextRevision);
  log.warn(msg("ii"), ex);
  return false;
 }
 if (previous == null || !previous.equals(next)) engine.lp();
 return true;
}

/** A refused switch leaves the previous account bound exactly as it was. */
void awz(TrackingIdentity previous, long appliedRevision, long previousNextRevision) {
 if (previous == null) repository.akn();
 else repository.mc(previous);
 activeIdentity = previous;
 writer.aft(appliedRevision);
 nextRevision = previousNextRevision;
}

void onLogout(boolean persistHistory) {
 if (lifecyclePending) return;
 synchronized (this) {
  if (!lifecyclePending && activeIdentity != null && persistHistory) ahf();
 }
}

/**
* Account-hash invalidation is a lifecycle event; its save/load work stays off the callback, and
* the setting is read after queued startup configuration migration completes.
*/
void acc(BooleanSupplier persistHistory) {
 agw(persistHistory, false, "account clear", persist -> {
  acd(persist);
  return false;
 });
}

synchronized void acd(boolean persistHistory) {
 if (activeIdentity != null && persistHistory) {
  ahf();
  if (!th(Duration.ofSeconds(5))) log.warn(msg("ij"));
  if (lifecycleShutdown) return;
 }
 engine.oz();
 activeIdentity = null;
 repository.akn();
 writer.aft(0L);
 nextRevision = 1L;
 engine.agl(null, new SavedState(), System.currentTimeMillis());
}

void ahe() {
 // A lifecycle worker may hold this monitor while it waits for a disk
 // flush or loads the next account scope. Gameplay ingestion is already
 // fenced at this point, so a callback request must return without
 // waiting behind that disk work; the lifecycle boundary has its own
 // explicit snapshot path.
 if (lifecyclePending) return;
 synchronized (this) {
  if (!lifecyclePending) ahf();
 }
}

/** Caller holds this coordinator monitor; used by lifecycle boundary snapshots. */
void ahf() {
 Cs intent = qn();
 if (intent == null) return;
 writer.submit(intent);
}

synchronized boolean aya() {
 Cs intent = qn();
 if (intent == null) return false;
 writer.submit(intent);
 if (!writer.flush(Duration.ofSeconds(5))) return false;
 Ci status = writer.getStatus();
 return status != null && !status.auq() && status.state != Ci.State.CONFLICT;
}

/** In-memory persistence health for a restrained panel warning; performs no file I/O. */
Ci ux() {
 return writer.getStatus();
}

String ahx() {
 if (activeIdentity == null || !repository.isBound() || !wv()) return msg("ik");
 return writer.vw(repository.scopeGeneration) ? writer.getStatus().detail : readOnlyReason;
}

/** Outcome of a reset; the pre-reset backup path is set only when the reset was applied. */
static class Ds {
 @Getter
 final boolean applied;
 @Getter
 final String detail;
 final Filepath preResetBackup;
 Ds(boolean applied, String detail, Filepath preResetBackup) {
  this.applied = applied;
  this.detail = Ag.axw(detail);
  this.preResetBackup = preResetBackup;
 }
 public Filepath getPreResetBackup() { return preResetBackup; }
}

/**
* Charter R factory reset for the bound account only: flush, write one validated pre-reset
* backup export, commit a fresh General owner through the destructive replace, re-prime. Other
* account folders, unassigned root legacy and every configuration key (including the frozen
* V1 snapshot) are untouched. The replace is fenced on the revision flushed here: if another
* client advances the account first, the reset is refused (never rebased) and the in-memory
* profile is restored, so the user can re-run it against the newer state if they still want to.
*/
synchronized Ds sj(long now) {
 String scope = ahx();
 if (scope != null) return new Ds(false, scope, null);
 maintenance = true;
 try {
  return afz(now, null);
 } finally {
  maintenance = false;
 }
}

/** Restores one of this account's backups; the current state is backed up first. */
synchronized Ds agm(Filepath file, long now) {
 String scope = ahx();
 if (scope != null) return new Ds(false, scope, null);
 maintenance = true;
 try {
  return afz(now, SessionRepository.adc(JsonCodec.gson(),
  new JsonParser().parse(SessionRepository.awy(file))));
 } catch (IOException | RuntimeException ex) {
  return new Ds(false, "Backup could not be read: " + ex.getMessage(), null);
 } finally {
  maintenance = false;
 }
}

/** Commits a fresh owner ({@code from} null: factory reset) or a restored backup, or rolls back. */
Ds afz(long now, SavedState from) {
 if (!aya()) return new Ds(false, msg("y"), null);
 if (from != null && !from.ye()) {
  // The same schema gate as startup: an incompatible backup is never interpreted
  // and rewritten as the current schema (owner 2026-10-01).
  return new Ds(false, msg("im") + from.schemaVersion + msg("ax"), null);
 }
 Filepath backup = null;
 if (from != null) {
  try {
   backup = akt(now);
  } catch (IOException | RuntimeException ex) {
   return new Ds(false, "Pre-restore backup failed: " + ex.getMessage(), null);
  }
 }
 SavedState before = snapshot();
 if (from == null) engine.agr(now);
 else engine.agl(activeIdentity.rsProfileKey, from, now);
 Bm outcome = agc();
 if (!outcome.isCommitted()) {
  engine.agl(activeIdentity.rsProfileKey, before, now);
  String why = outcome.kind == Bm.Kind.CONFLICT ? msg("hb") + " " : "";
  // Owner 2026-10-01 (F25): the shared outcome names the operation that was refused.
  return new Ds(false, (from == null ? "Factory reset" : "Restore")
  + " was not applied: " + why + outcome.message + (from == null ? "" : " \u00b7 the old profile is unchanged"), backup);
 }
 engine.lp();
 return new Ds(true, msg("bp"), backup);
}

/**
* A copy of the bound account's save in backups/, named account-time and re-read to prove it is
* intact; only this account's newest 10 are kept.
*/
Filepath akt(long now) throws IOException {
 synchronized (backupLock) {
  return aktLocked(now);
 }
}

private Filepath aktLocked(long now) throws IOException {
 repository.backupDirectory.createDirectories();
 String name = account() + "-" + CsvExporter.FILE_TIME.format(Instant.ofEpochMilli(now));
 Filepath file = repository.backupDirectory.joinSegment(name + ".json");
 for (int n = 2; file.exists() && n < 1_000; n++) {
  file = repository.backupDirectory.joinSegment(name + "-" + n + ".json");
 }
 String json;
 synchronized (engine) {
  json = JsonCodec.gson().toJson(engine.sl());
 }
 file.write(json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
 SessionRepository.adc(JsonCodec.gson(), new JsonParser().parse(SessionRepository.awy(file)));
 adz(10);
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
  .sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
 }
}

/** Deletes all but this account's newest {@code keep} backups; returns how many went. */
int adz(int keep) throws IOException {
 java.util.List<Filepath> all = backups();
 for (Filepath old : all.subList(min(keep, all.size()), all.size())) old.deleteIfExists();
 return max(0, all.size() - keep);
}

synchronized boolean wv() {
 TrackingIdentity current = resolveCurrentIdentity();
 return current != null && current.equals(activeIdentity);
}

/**
* True when the bound identity matches the client and tracking may ingest
* gameplay. Refused switches leave the previous identity bound, so the new
* physical account must not feed that state.
*/
boolean isTrackingReady() {
 // Read without the monitor so a restore/reset holding it pauses ingestion instead of
 // blocking the client thread behind the disk work.
 if (maintenance || lifecyclePending || lifecycleInitializationFailed) return false;
 synchronized (this) {
  return !maintenance && !lifecyclePending && !lifecycleInitializationFailed
  && wv() && readOnlyReason == null && !writer.vw(repository.scopeGeneration);
 }
}

synchronized Bm agc() {
 Cs intent = qn();
 if (intent == null) return Bm.failedUnchanged(msg("ci"));
 return writer.aic(intent);
}

synchronized boolean th(Duration timeout) {
 return writer.flush(timeout);
}

void shutdown(boolean persistHistory) {
 boolean asj = lifecyclePending;
 lifecycleShutdown = true;
 lifecycleEpoch.incrementAndGet();
 lifecycleRequest.incrementAndGet();
 lifecyclePending = false;
 identityReadyTransition.set(false);
 shutdownPersistHistory = persistHistory;
 // If a lifecycle worker is already fencing an owner, it captured that
 // owner's state before entering its disk wait. Keep the worker alive so
 // it can take one final post-shutdown snapshot after that wait returns;
 // the lifecycle callback itself still never waits for the worker.
 if (asj) {
  shutdownSnapshotPending = true;
  synchronized (lifecycleExecutorLock) {
   if (lifecycleExecutor == null || lifecycleExecutor.isShutdown()) sq();
   else lifecycleExecutor.shutdown();
  }
  return;
 }
 if (persistHistory && repository.isBound()) ahe();
 writer.shutdown(Duration.ofSeconds(5));
 synchronized (lifecycleExecutorLock) {
  if (lifecycleExecutor != null) lifecycleExecutor.shutdownNow();
 }
}

/** Completes a deferred shutdown snapshot from the lifecycle worker without waiting on it. */
void sq() {
 if (!shutdownSnapshotPending || !shutdownFinalized.compareAndSet(false, true)) return;
 shutdownSnapshotPending = false;
 try {
  if (shutdownPersistHistory && repository.isBound()) ahe();
 } catch (RuntimeException ex) {
  log.warn(msg("il"), ex);
 } finally {
  writer.shutdown(Duration.ofSeconds(5));
  synchronized (lifecycleExecutorLock) {
   if (lifecycleExecutor != null && !lifecycleExecutor.isShutdown()) lifecycleExecutor.shutdownNow();
  }
 }
}

/**
* Loads the bound scope. Any schema other than 1.0's is left exactly as it is and makes the
* scope read-only; it is never rewritten.
*/
SavedState yq(TrackingIdentity identity) {
 readOnlyReason = null;
 SavedState loaded = repository.load();
 if (!loaded.ye()) {
  readOnlyReason = msg("im") + loaded.schemaVersion + msg("ax");
  log.warn(readOnlyReason);
  return new SavedState();
 }
 return loaded;
}

/**
* The engine's state serialized while holding the engine's lock: qm hands out live
* sessions and GE records, which the client thread keeps changing once the lock is released.
* A save {@code header}'s revision and savedAt, and the bound owner, are stamped on the fresh
* container first, so this one pass is the payload; the schema is the new container's current one.
*/
String aiw(SavedState header) {
 synchronized (engine) {
  SavedState state = engine.qm();
  if (header != null) {
   state.setRevision(header.revision);
   state.setSavedAtEpochMillis(header.savedAtEpochMillis);
   if (activeIdentity != null) state.setOwnerKey(activeIdentity.rsProfileKey);
  }
  return repository.gson.toJson(state);
 }
}

/** A detached copy of the engine's state; agl normalizes its labels as a load does. */
SavedState snapshot() {
 return repository.gson.fromJson(aiw(null), SavedState.class);
}

Cs qn() {
 if (!repository.isBound() || readOnlyReason != null || writer.vw(repository.scopeGeneration)) {
  return null;
 }
 // Prefer the writer's verified lineage when a prior save is still
 // updating disk lastKnown — avoids queuing another base-N snapshot.
 long anl = max(repository.lastKnownDiskRevision, writer.tk());
 var header = new SavedState();
 header.setRevision(max(nextRevision, anl + 1L));
 nextRevision = header.revision + 1L;
 header.setSavedAtEpochMillis(System.currentTimeMillis());
 return new Cs(activeIdentity, repository.scopeGeneration, anl, header, aiw(header));
}
}
