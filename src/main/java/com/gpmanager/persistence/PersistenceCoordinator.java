package com.gpmanager;
import com.gpmanager.SessionRepository.ReplaceOutcome;
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
import static com.gpmanager.GameData.msg;
/**
* Coordinates account binding, detached snapshots, and ordered persistence.
*/
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class PersistenceCoordinator {
static final Logger log = LoggerFactory.getLogger(PersistenceCoordinator.class);
final Client client;
final ConfigManager configManager;
@Getter
final SessionRepository repository;
@Getter
final OrderedPersistenceWriter writer;
final Engine engine;
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
   lifecycleExecutor = OrderedPersistenceWriter.daemonExecutor(msg("et"));
  }
 }
}

/**
* Runs repository-directory/configuration initialization away from the
* RuneLite plugin lifecycle callback. Identity work queued immediately
* afterwards remains ordered behind this operation on the same executor.
*/
void initializeAsync(Runnable initialization) {
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
void syncIdentityFromClientAsync(BooleanSupplier persistHistory) {
 trySwitchIdentityAsync(resolveCurrentIdentity(), persistHistory, false);
}

/** Seam for deterministic lifecycle tests and explicit profile events. */
void trySwitchIdentityAsync(TrackingIdentity next, boolean persistHistory) {
 trySwitchIdentityAsync(next, () -> persistHistory, true);
}

void trySwitchIdentityAsync(TrackingIdentity next, BooleanSupplier persistHistory, boolean capturePreviousOnCaller) {
 runLifecycleAsync(persistHistory, capturePreviousOnCaller, "identity switch",
 persist -> trySwitchIdentity(next, persist, false));
}

/**
* One lifecycle job on the executor: capture the old owner's latest state (on the caller when
* asked, else on the worker), run the job unless shut down, then publish whether it is ready.
*/
void runLifecycleAsync(BooleanSupplier persistHistory, boolean capturePreviousOnCaller, String what,
Predicate<Boolean> job) {
 final long request = lifecycleRequest.incrementAndGet();
 synchronized (this) {
  if (lifecycleShutdown) return;
  // Capture the old owner's latest state before the worker can enter
  // its disk wait. A pending earlier lifecycle operation has already
  // made the same capture at its boundary.
  if (capturePreviousOnCaller && !lifecyclePending && activeIdentity != null && persistHistory.getAsBoolean()) {
   scheduleSave();
  }
  lifecyclePending = true;
  identityReadyTransition.set(false);
 }
 ExecutorService executor = lifecycleExecutor();
 if (executor == null) {
  finishLifecycleRequest(request, false);
  return;
 }
 try {
  executor.execute(() -> {
   boolean ready = false;
   try {
    boolean persist = persistHistory.getAsBoolean();
    if (!capturePreviousOnCaller) {
     synchronized (this) {
      if (!lifecycleShutdown && activeIdentity != null && persist) scheduleSaveLocked();
     }
    }
    if (!lifecycleShutdown) ready = job.test(persist);
   } catch (RuntimeException ex) {
    log.warn("Unable to complete GP Manager " + what, ex);
   }
   finishLifecycleRequest(request, ready);
  });
 } catch (RuntimeException ex) {
  log.warn("Unable to schedule GP Manager " + what, ex);
  finishLifecycleRequest(request, false);
 }
}

ExecutorService lifecycleExecutor() {
 synchronized (lifecycleExecutorLock) {
  if (lifecycleShutdown) return null;
  if (lifecycleExecutor == null || lifecycleExecutor.isShutdown()) {
   lifecycleExecutor = OrderedPersistenceWriter.daemonExecutor(msg("eu"));
  }
  return lifecycleExecutor;
 }
}

void finishLifecycleRequest(long request, boolean ready) {
 if (lifecycleShutdown && shutdownSnapshotPending) finalizeShutdownSnapshot();
 if (request != lifecycleRequest.get()) return;
 lifecyclePending = false;
 if (ready && !lifecycleShutdown) identityReadyTransition.set(true);
}

/** True once for the client tick that may resume ingestion after an async bind. */
boolean consumeIdentityReadyTransition() {
 return identityReadyTransition.compareAndSet(true, false);
}

/**
* Attempt to bind {@code next}. When {@code next} is null, returns whether a
* prior identity remains bound. Failed flush/save refuses the switch and
* preserves the previous account's in-memory state.
*/
synchronized boolean trySwitchIdentity(TrackingIdentity next, boolean persistHistory) {
 return trySwitchIdentity(next, persistHistory, true);
}

synchronized boolean trySwitchIdentity(TrackingIdentity next, boolean persistHistory, boolean capturePrevious) {
 if (next == null) return activeIdentity != null;
 if (next.equals(activeIdentity)) return true;
 if (lifecycleShutdown && !capturePrevious) return false;
 if (activeIdentity != null && persistHistory) {
  if (capturePrevious) scheduleSave();
  if (!flushPending(Duration.ofSeconds(5))) {
   log.warn(msg("ig"));
   return false;
  }
  SaveStatus status = writer.getStatus();
  if (status.isFailure() || status.state == SaveStatus.State.CONFLICT) {
   log.warn(msg("ih"), status.detail);
   return false;
  }
 }
 TrackingIdentity previous = activeIdentity;
 long previousAppliedRevision = writer.getAppliedRevision();
 long previousNextRevision = nextRevision;
 activeIdentity = next;
 try {
  repository.bindIdentity(next);
  if (persistHistory) {
   SavedState loaded = loadBoundScope(next);
   if (lifecycleShutdown && !capturePrevious) {
    rollBack(previous, previousAppliedRevision, previousNextRevision);
    return false;
   }
   writer.resetAppliedRevision(loaded.revision);
   nextRevision = max(loaded.revision + 1L, 1L);
   engine.restoreForProfile(next.rsProfileKey, loaded, System.currentTimeMillis());
  } else {
   writer.resetAppliedRevision(0L);
   nextRevision = 1L;
   engine.restoreForProfile(next.rsProfileKey, new SavedState(), System.currentTimeMillis());
  }
 } catch (RuntimeException ex) {
  rollBack(previous, previousAppliedRevision, previousNextRevision);
  log.warn(msg("ii"), ex);
  return false;
 }
 if (previous == null || !previous.equals(next)) engine.beginBaselinePriming();
 return true;
}

/** A refused switch leaves the previous account bound exactly as it was. */
void rollBack(TrackingIdentity previous, long appliedRevision, long previousNextRevision) {
 if (previous == null) repository.unbindIdentity();
 else repository.bindIdentity(previous);
 activeIdentity = previous;
 writer.resetAppliedRevision(appliedRevision);
 nextRevision = previousNextRevision;
}

void onLogout(boolean persistHistory) {
 if (lifecyclePending) return;
 synchronized (this) {
  if (!lifecyclePending && activeIdentity != null && persistHistory) scheduleSaveLocked();
 }
}

/**
* Account-hash invalidation is a lifecycle event; its save/load work stays off the callback, and
* the setting is read after queued startup configuration migration completes.
*/
void onAccountHashInvalidatedAsync(BooleanSupplier persistHistory) {
 runLifecycleAsync(persistHistory, false, "account clear", persist -> {
  onAccountHashInvalidated(persist);
  return false;
 });
}

synchronized void onAccountHashInvalidated(boolean persistHistory) {
 if (activeIdentity != null && persistHistory) {
  scheduleSaveLocked();
  if (!flushPending(Duration.ofSeconds(5))) log.warn(msg("ij"));
  if (lifecycleShutdown) return;
 }
 engine.clearProfileIdentityForSwitch();
 activeIdentity = null;
 repository.unbindIdentity();
 writer.resetAppliedRevision(0L);
 nextRevision = 1L;
 engine.restoreForProfile(null, new SavedState(), System.currentTimeMillis());
}

void scheduleSave() {
 // A lifecycle worker may hold this monitor while it waits for a disk
 // flush or loads the next account scope. Gameplay ingestion is already
 // fenced at this point, so a callback request must return without
 // waiting behind that disk work; the lifecycle boundary has its own
 // explicit snapshot path.
 if (lifecyclePending) return;
 synchronized (this) {
  if (!lifecyclePending) scheduleSaveLocked();
 }
}

/** Caller holds this coordinator monitor; used by lifecycle boundary snapshots. */
void scheduleSaveLocked() {
 WriteIntent intent = currentIntent();
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
SaveStatus getSaveStatus() {
 return writer.getStatus();
}

String scopeRefusal() {
 if (activeIdentity == null || !repository.isBound() || !isIdentityMatched()) return msg("ik");
 return writer.isConflicted(repository.scopeGeneration) ? writer.getStatus().detail : readOnlyReason;
}

/** Outcome of a reset; the pre-reset backup path is set only when the reset was applied. */
static class ResetOutcome {
 @Getter
 final boolean applied;
 @Getter
 final String detail;
 final Filepath preResetBackup;
 ResetOutcome(boolean applied, String detail, Filepath preResetBackup) {
  this.applied = applied;
  this.detail = ModelText.orEmpty(detail);
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
synchronized ResetOutcome factoryResetCurrentAccount(long now) {
 String scope = scopeRefusal();
 if (scope != null) return new ResetOutcome(false, scope, null);
 maintenance = true;
 try {
  return replaceLocked(now, null);
 } finally {
  maintenance = false;
 }
}

/** Restores one of this account's backups; the current state is backed up first. */
synchronized ResetOutcome restoreBackup(Filepath file, long now) {
 String scope = scopeRefusal();
 if (scope != null) return new ResetOutcome(false, scope, null);
 maintenance = true;
 try {
  return replaceLocked(now, SessionRepository.parseState(JsonCodec.gson(),
  new JsonParser().parse(SessionRepository.readText(file))));
 } catch (IOException | RuntimeException ex) {
  return new ResetOutcome(false, "Backup could not be read: " + ex.getMessage(), null);
 } finally {
  maintenance = false;
 }
}

/** Commits a fresh owner ({@code from} null: factory reset) or a restored backup, or rolls back. */
ResetOutcome replaceLocked(long now, SavedState from) {
 if (!saveNow()) return new ResetOutcome(false, msg("y"), null);
 if (from != null && !from.isSupportedSchema()) {
  // The same schema gate as startup: an incompatible backup is never interpreted
  // and rewritten as the current schema (owner 2026-10-01).
  return new ResetOutcome(false, msg("im") + from.schemaVersion + msg("ax"), null);
 }
 Filepath backup = null;
 if (from != null) {
  try {
   backup = writePreOperationBackup(now);
  } catch (IOException | RuntimeException ex) {
   return new ResetOutcome(false, "Pre-restore backup failed: " + ex.getMessage(), null);
  }
 }
 SavedState before = snapshot();
 if (from == null) engine.resetTrackingData(now);
 else engine.restoreForProfile(activeIdentity.rsProfileKey, from, now);
 ReplaceOutcome outcome = replaceStateNow();
 if (!outcome.isCommitted()) {
  engine.restoreForProfile(activeIdentity.rsProfileKey, before, now);
  String why = outcome.kind == ReplaceOutcome.Kind.CONFLICT ? msg("hb") + " " : "";
  // Owner 2026-10-01 (F25): the shared outcome names the operation that was refused.
  return new ResetOutcome(false, (from == null ? "Factory reset" : "Restore")
  + " was not applied: " + why + outcome.message + (from == null ? "" : " \u00b7 the old profile is unchanged"), backup);
 }
 engine.beginBaselinePriming();
 return new ResetOutcome(true, msg("bp"), backup);
}

/**
* A copy of the bound account's save in backups/, named account-time and re-read to prove it is
* intact; only this account's newest 10 are kept.
*/
Filepath writePreOperationBackup(long now) throws IOException {
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
  json = JsonCodec.gson().toJson(engine.exportState());
 }
 file.write(json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
 SessionRepository.parseState(JsonCodec.gson(), new JsonParser().parse(SessionRepository.readText(file)));
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
  .sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
 }
}

/** Deletes all but this account's newest {@code keep} backups; returns how many went. */
int pruneBackups(int keep) throws IOException {
 java.util.List<Filepath> all = backups();
 for (Filepath old : all.subList(min(keep, all.size()), all.size())) old.deleteIfExists();
 return max(0, all.size() - keep);
}

synchronized boolean isIdentityMatched() {
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
  && isIdentityMatched() && readOnlyReason == null && !writer.isConflicted(repository.scopeGeneration);
 }
}

synchronized ReplaceOutcome replaceStateNow() {
 WriteIntent intent = currentIntent();
 if (intent == null) return ReplaceOutcome.failedUnchanged(msg("ci"));
 return writer.replaceNow(intent);
}

synchronized boolean flushPending(Duration timeout) {
 return writer.flush(timeout);
}

void shutdown(boolean persistHistory) {
 boolean lifecycleWasPending = lifecyclePending;
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
 if (lifecycleWasPending) {
  shutdownSnapshotPending = true;
  synchronized (lifecycleExecutorLock) {
   if (lifecycleExecutor == null || lifecycleExecutor.isShutdown()) finalizeShutdownSnapshot();
   else lifecycleExecutor.shutdown();
  }
  return;
 }
 if (persistHistory && repository.isBound()) scheduleSave();
 writer.shutdown(Duration.ofSeconds(5));
 synchronized (lifecycleExecutorLock) {
  if (lifecycleExecutor != null) lifecycleExecutor.shutdownNow();
 }
}

/** Completes a deferred shutdown snapshot from the lifecycle worker without waiting on it. */
void finalizeShutdownSnapshot() {
 if (!shutdownSnapshotPending || !shutdownFinalized.compareAndSet(false, true)) return;
 shutdownSnapshotPending = false;
 try {
  if (shutdownPersistHistory && repository.isBound()) scheduleSave();
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
SavedState loadBoundScope(TrackingIdentity identity) {
 readOnlyReason = null;
 SavedState loaded = repository.load();
 if (!loaded.isSupportedSchema()) {
  readOnlyReason = msg("im") + loaded.schemaVersion + msg("ax");
  log.warn(readOnlyReason);
  return new SavedState();
 }
 return loaded;
}

/**
* The engine's state serialized while holding the engine's lock: createSavedState hands out live
* sessions and GE records, which the client thread keeps changing once the lock is released.
* A save {@code header}'s revision and savedAt, and the bound owner, are stamped on the fresh
* container first, so this one pass is the payload; the schema is the new container's current one.
*/
String snapshotJson(SavedState header) {
 synchronized (engine) {
  SavedState state = engine.createSavedState();
  if (header != null) {
   state.setRevision(header.revision);
   state.setSavedAtEpochMillis(header.savedAtEpochMillis);
   if (activeIdentity != null) state.setOwnerKey(activeIdentity.rsProfileKey);
  }
  return repository.gson.toJson(state);
 }
}

/** A detached copy of the engine's state; restoreForProfile normalizes its labels as a load does. */
SavedState snapshot() {
 return repository.gson.fromJson(snapshotJson(null), SavedState.class);
}

WriteIntent currentIntent() {
 if (!repository.isBound() || readOnlyReason != null || writer.isConflicted(repository.scopeGeneration)) {
  return null;
 }
 // Prefer the writer's verified lineage when a prior save is still
 // updating disk lastKnown — avoids queuing another base-N snapshot.
 long baseRevision = max(repository.lastKnownDiskRevision, writer.getAppliedRevision());
 var header = new SavedState();
 header.setRevision(max(nextRevision, baseRevision + 1L));
 nextRevision = header.revision + 1L;
 header.setSavedAtEpochMillis(System.currentTimeMillis());
 return new WriteIntent(activeIdentity, repository.scopeGeneration, baseRevision, header, snapshotJson(header));
}
}
