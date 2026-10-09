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
/** A sidebar job (backup) holds the lock: the game thread reuses its last readiness instead of waiting. */
volatile boolean busy, okay;
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
void vr(Runnable /* initialization */a) {
 synchronized (lifecycleExecutorLock) {
  // A plugin re-enable may arrive after the prior lifecycle fenced
  // this coordinator. Reopen only the lifecycle executor; writer
  // start remains ordered inside the initialization task.
  if (lifecycleShutdown) {
   lifecycleShutdown = false;
   lifecycleEpoch.incrementAndGet();
  }
 }
 long /* epoch */b = lifecycleEpoch.get();
 ExecutorService /* executor */c = lifecycleExecutor();
 if (c == null) {
  lifecycleInitializationFailed = true;
  return;
 }
 try {
  c.execute(() -> {
   try {
    if (!lifecycleShutdown && b == lifecycleEpoch.get()) a.run();
   } catch (RuntimeException /* ex */d) {
    if (b == lifecycleEpoch.get()) lifecycleInitializationFailed = true;
    log.warn(msg("t"), d);
   }
  });
 } catch (RuntimeException /* ex */e) {
  lifecycleInitializationFailed = true;
  log.warn(msg("f"), e);
 }
}

TrackingIdentity resolveCurrentIdentity() {
 if (configManager == null) return null;
 String /* rsProfileKey */a = configManager.getRSProfileKey();
 long /* accountHash */b = TrackingIdentity.ACCOUNT_HASH_INVALID;
 if (client != null) {
  try {
   b = client.getAccountHash();
  } catch (RuntimeException /* ex */c) {
   // Client may not expose hash before login.
  }
 }
 if (a != null && !a.trim().isEmpty()) {
  return new TrackingIdentity(a.trim(), b);
 }
 return null;
}

/**
* Captures the current identity without touching disk, then performs the
* account bind/load/flush on the lifecycle executor. The next game tick
* consumes the ready transition after the worker has installed the scope.
*/
void aje(BooleanSupplier /* persistHistory */a) {
 ajz(resolveCurrentIdentity(), a, false);
}

/** Seam for deterministic lifecycle tests and explicit profile events. */
void ajz(TrackingIdentity /* next */a, boolean /* persistHistory */b) {
 ajz(a, () -> b, true);
}

void ajz(TrackingIdentity /* next */a, BooleanSupplier /* persistHistory */b, boolean /* capturePreviousOnCaller */c) {
 agw(b, c, "identity switch",
 /* persist */d -> ajy(a, d, false));
}

/**
* One lifecycle job on the executor: capture the old owner's latest state (on the caller when
* asked, else on the worker), run the job unless shut down, then publish whether it is ready.
*/
void agw(BooleanSupplier /* persistHistory */a, boolean /* capturePreviousOnCaller */b, String /* what */c,
Predicate<Boolean> /* job */d) {
 long /* request */e = lifecycleRequest.incrementAndGet();
 eo(nextHash(), e);
 synchronized (this) {
  if (lifecycleShutdown) return;
  // Capture the old owner's latest state before the worker can enter
  // its disk wait. A pending earlier lifecycle operation has already
  // made the same capture at its boundary.
  if (b && !lifecyclePending && activeIdentity != null && a.getAsBoolean()) {
   ahe();
  }
  lifecyclePending = true;
  identityReadyTransition.set(false);
 }
 ExecutorService /* executor */f = lifecycleExecutor();
 if (f == null) {
  sz(e, false);
  return;
 }
 try {
  f.execute(() -> {
   boolean /* ready */g = false;
   try {
    boolean /* persist */h = a.getAsBoolean();
    if (!b) {
     synchronized (this) {
      if (!lifecycleShutdown && activeIdentity != null && h) ahf();
     }
    }
    if (!lifecycleShutdown) g = d.test(h);
   } catch (RuntimeException /* ex */i) {
    log.warn(msg("b5") + c, i);
   }
   sz(e, g);
  });
 } catch (RuntimeException /* ex */j) {
  log.warn(msg("b4") + c, j);
  sz(e, false);
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

void sz(long /* request */a, boolean /* ready */b) {
 if (lifecycleShutdown && shutdownSnapshotPending) sq();
 if (a != lifecycleRequest.get()) return;
 lifecyclePending = false;
 if (b && !lifecycleShutdown) identityReadyTransition.set(true);
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
synchronized boolean ajy(TrackingIdentity /* next */a, boolean /* persistHistory */b) {
 return ajy(a, b, true);
}

synchronized boolean ajy(TrackingIdentity /* next */a, boolean /* persistHistory */b, boolean /* capturePrevious */c) {
 if (a == null) return activeIdentity != null;
 if (a.equals(activeIdentity)) return true;
 if (c) eo(a.accountHash, lifecycleRequest.incrementAndGet());
 if (lifecycleShutdown && !c) return false;
 if (activeIdentity != null && b) {
  if (c) ahe();
  if (!th(Duration.ofSeconds(5))) {
   log.warn(msg("ig"));
   return false;
  }
  Ci /* status */d = writer.getStatus();
  if (d.auq() || d.state == Ci.State.CONFLICT) {
   log.warn(msg("ih"), d.detail);
   return false;
  }
 }
 TrackingIdentity /* previous */e = activeIdentity;
 long /* apo */f = writer.tk();
 long /* previousNextRevision */g = nextRevision;
 activeIdentity = a;
 try {
  repository.mc(a);
  SavedState h = b ? yq(a) : new SavedState();
  if (b && lifecycleShutdown && !c) {
   awz(e, f, g);
   return false;
  }
  writer.aft(h.revision);
  nextRevision = max(h.revision + 1L, 1L);
  engine.agl(a.rsProfileKey, h, Ae.now());
 } catch (RuntimeException /* ex */i) {
  awz(e, f, g);
  log.warn(msg("ii"), i);
  return false;
 }
 eo(a.accountHash, lifecycleRequest.get());
 if (e == null || !e.equals(a)) engine.lp();
 return true;
}

/** A refused switch leaves the previous account bound exactly as it was. */
void awz(TrackingIdentity /* previous */a, long /* appliedRevision */b, long /* previousNextRevision */c) {
 if (a == null) repository.akn();
 else repository.mc(a);
 activeIdentity = a;
 writer.aft(b);
 nextRevision = c;
}

/** Fence evidence with the same generation already used to reject stale lifecycle completions. */
void eo() { eo(nextHash(), lifecycleRequest.incrementAndGet()); }
long nextHash() { return client == null ? (activeIdentity == null ? -1L : activeIdentity.accountHash) : client.getAccountHash(); }
void eo(long /* hash */a, long /* generation */b) { if (engine != null) engine.eb.owner(a * 1_000L + b); }

void onLogout(boolean /* persistHistory */a) {
 if (lifecyclePending) {
  // A logout advances the existing request generation too. Publish its completion after
  // the earlier bind, so discarding that stale completion cannot leave ingestion fenced.
  agw(() -> a, false, "logout", /* persist */b -> false);
  return;
 }
 eo();
 synchronized (this) {
  if (!lifecyclePending && activeIdentity != null && a) ahf();
 }
}

/**
* Account-hash invalidation is a lifecycle event; its save/load work stays off the callback, and
* the setting is read after queued startup configuration migration completes.
*/
void acc(BooleanSupplier /* persistHistory */a) {
 agw(a, false, "account clear", /* persist */b -> {
  acd(b);
  return false;
 });
}

synchronized void acd(boolean /* persistHistory */a) {
 if (activeIdentity != null && a) {
  ahf();
  if (!th(Duration.ofSeconds(5))) log.warn(msg("ij"));
  if (lifecycleShutdown) return;
 }
 engine.oz();
 activeIdentity = null;
 repository.akn();
 writer.aft(0L);
 nextRevision = 1L;
 engine.agl(null, new SavedState(), Ae.now());
}

void ahe() {
 ahe(false);
}

/** {@code quick}: a gameplay save, which may reuse unchanged closed sessions' JSON. */
void ahe(boolean /* quick */b) {
 // A lifecycle worker may hold this monitor while it waits for a disk
 // flush or loads the next account scope. Gameplay ingestion is already
 // fenced at this point, so a callback request must return without
 // waiting behind that disk work; the lifecycle boundary has its own
 // explicit snapshot path.
 if (lifecyclePending) return;
 synchronized (this) {
  if (!lifecyclePending) ahf(b);
 }
}

/** Caller holds this coordinator monitor; used by lifecycle boundary snapshots. */
void ahf() {
 ahf(false);
}

void ahf(boolean /* quick */a) {
 Cs /* intent */b = qn(a);
 if (b == null) return;
 writer.submit(b);
}

synchronized boolean aya() {
 Cs /* intent */a = qn();
 if (a == null) return false;
 writer.submit(a);
 if (!writer.flush(Duration.ofSeconds(5))) return false;
 Ci /* status */b = writer.getStatus();
 return b != null && !b.auq() && b.state != Ci.State.CONFLICT;
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
 boolean applied;
 @Getter
 String detail;
 Filepath preResetBackup;
 Ds(boolean /* applied */b, String /* detail */c, Filepath /* preResetBackup */d) {
  this.applied = b;
  this.detail = Ag.axw(c);
  this.preResetBackup = d;
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
synchronized Ds sj(long /* now */a) {
 String /* scope */b = ahx();
 if (b != null) return new Ds(false, b, null);
 maintenance = true;
 try {
  return afz(a, null);
 } finally {
  maintenance = false;
 }
}

/** Restores one of this account's backups; the current state is backed up first. */
synchronized Ds agm(Filepath /* file */a, long /* now */b) {
 String /* scope */c = ahx();
 if (c != null) return new Ds(false, c, null);
 maintenance = true;
 try {
  return afz(b, SessionRepository.r1(JsonCodec.gson(), a));
 } catch (IOException | RuntimeException /* ex */d) {
  return new Ds(false, msg("b3") + d.getMessage(), null);
 } finally {
  maintenance = false;
 }
}

/** Commits a fresh owner ({@code from} null: factory reset) or a restored backup, or rolls back. */
Ds afz(long /* now */a, SavedState /* from */b) {
 if (!aya()) return new Ds(false, msg("y"), null);
 if (b != null && !b.ye()) {
  // The same schema gate as startup: an incompatible backup is never interpreted
  // and rewritten as the current schema (owner 2026-10-01).
  return new Ds(false, msg("im") + b.schemaVersion + msg("ax"), null);
 }
 Filepath /* backup */c = null;
 SavedState /* protected state */h;
 try {
  Filepath i = b == null ? b1(a) : (c = akt(a));
  h = SessionRepository.r1(JsonCodec.gson(), i);
 } catch (IOException | RuntimeException /* ex */d) {
  return new Ds(false, msg("b2") + d.getMessage(), null);
 }
 SavedState /* before */e;
 synchronized (engine) {
  // Sidebar edits use the engine lock, but are not ingestion. Refuse a stale safety copy
  // before replacing data, with check/snapshot/mutation atomic and all file IO outside.
  if (!b2(engine.sl()).equals(b2(h))) return new Ds(false,
  (b == null ? "Factory reset" : "Restore") + " was not applied: data changed while the safety copy was prepared; retry.", c);
  e = snapshot();
  eo();
  if (b == null) engine.agr(a);
  else engine.agl(activeIdentity.rsProfileKey, b, a);
 }
 Bm /* outcome */f = agc();
 if (!f.isCommitted()) {
  engine.agl(activeIdentity.rsProfileKey, e, a);
  String /* why */g = f.kind == Bm.Kind.CONFLICT ? msg("hb") + " " : "";
  // Owner 2026-10-01 (F25): the shared outcome names the operation that was refused.
  return new Ds(false, (b == null ? "Factory reset" : "Restore")
  + " was not applied: " + g + f.message + (b == null ? "" : msg("cq")), c);
 }
 engine.lp();
 return new Ds(true, msg("bp"), c);
}

/**
* A copy of the bound account's save in backups/, named account-time and re-read to prove it is
* intact; only this account's newest 10 are kept.
*/
Filepath akt(long /* now */a) throws IOException {
 synchronized (backupLock) {
  return aktLocked(a);
 }
}

/**
* Validated reset safety copy. Reuse this account's matching export, including an explicit
* Back up first copy; compare every durable fact, ignoring only the root save-time/revision.
* Corrupt, incompatible or different-owner copies never qualify. Failure refuses the reset.
* The existing backup lock and coordinator maintenance fence keep capture/reuse ordered.
*/
Filepath b1(long a) throws IOException {
 synchronized (backupLock) {
  com.google.gson.JsonObject b;
  synchronized (engine) { b = b2(engine.sl()); }
  for (Filepath c : backups()) {
   try {
    SavedState d = SessionRepository.r1(JsonCodec.gson(), c);
    if (d.ye() && b.equals(b2(d))) return c;
   } catch (IOException | RuntimeException e) {
    // Preserve unreadable exports; secure a new copy before changing the account.
   }
  }
  return aktLocked(a);
 }
}

/** Complete backup content with only root bookkeeping headers excluded from equality. */
static com.google.gson.JsonObject b2(SavedState a) {
 com.google.gson.JsonObject b = JsonCodec.gson().toJsonTree(a).getAsJsonObject();
 b.remove("savedAtEpochMillis");
 b.remove("revision");
 return b;
}

private Filepath aktLocked(long /* now */a) throws IOException {
 repository.backupDirectory.createDirectories();
 String /* name */b = account() + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
 .format(Instant.ofEpochMilli(a));
 Filepath /* file */c = repository.backupDirectory.joinSegment(b + ".json");
 for (int n = 2; c.exists() && n < 1_000; n++) {
  // "_" sorts after ".", so a same-second copy lists as the newer one and is kept (1.3.1).
  c = repository.backupDirectory.joinSegment(b + "_" + n + ".json");
 }
 String /* json */d;
 synchronized (engine) {
  d = JsonCodec.gson().toJson(engine.sl());
 }
 c.write(d, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
 SessionRepository.r1(JsonCodec.gson(), c);
 adz(10);
 return c;
}

String account() {
 return activeIdentity == null ? "profile" : activeIdentity.rsProfileKey;
}

/** This account's backups, newest first. */
java.util.List<Filepath> backups() throws IOException {
 if (!repository.backupDirectory.exists()) return new java.util.ArrayList<>();
 try (var /* files */a = repository.backupDirectory.walk(1)) {
  return a.filter(f -> f.isFile() && f.getFileName().startsWith(account() + "-"))
  .sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
 }
}

/** Deletes all but this account's newest {@code keep} backups; returns how many went. */
int adz(int /* keep */a) throws IOException {
 java.util.List<Filepath> /* all */b = backups();
 for (Filepath /* old */c : b.subList(min(a, b.size()), b.size())) c.deleteIfExists();
 return max(0, b.size() - a);
}

synchronized boolean wv() {
 TrackingIdentity /* current */a = resolveCurrentIdentity();
 return a != null && a.equals(activeIdentity);
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
 if (busy) return okay;
 synchronized (this) {
  return okay = !maintenance && !lifecyclePending && !lifecycleInitializationFailed
  && wv() && readOnlyReason == null && !writer.vw(repository.scopeGeneration);
 }
}

synchronized Bm agc() {
 Cs /* intent */a = qn();
 if (a == null) return Bm.failedUnchanged(msg("ci"));
 return writer.aic(a);
}

synchronized boolean th(Duration /* timeout */a) {
 return writer.flush(a);
}

void shutdown(boolean /* persistHistory */b) {
 boolean /* asj */c = lifecyclePending;
 lifecycleShutdown = true;
 lifecycleEpoch.incrementAndGet();
 eo();
 lifecyclePending = false;
 identityReadyTransition.set(false);
 shutdownPersistHistory = b;
 // If a lifecycle worker is already fencing an owner, it captured that
 // owner's state before entering its disk wait. Keep the worker alive so
 // it can take one final post-shutdown snapshot after that wait returns;
 // the lifecycle callback itself still never waits for the worker.
 if (c) {
  shutdownSnapshotPending = true;
  synchronized (lifecycleExecutorLock) {
   if (lifecycleExecutor == null || lifecycleExecutor.isShutdown()) sq();
   else lifecycleExecutor.shutdown();
  }
  return;
 }
 if (b && repository.isBound()) ahe();
 writer.shutdown(Duration.ofSeconds(5));
 synchronized (lifecycleExecutorLock) {
  if (lifecycleExecutor != null) lifecycleExecutor.shutdown();
 }
}

/** Completes a deferred shutdown snapshot from the lifecycle worker without waiting on it. */
void sq() {
 if (!shutdownSnapshotPending || !shutdownFinalized.compareAndSet(false, true)) return;
 shutdownSnapshotPending = false;
 try {
  if (shutdownPersistHistory && repository.isBound()) ahe();
 } catch (RuntimeException /* ex */a) {
  log.warn(msg("il"), a);
 } finally {
  writer.shutdown(Duration.ofSeconds(5));
  synchronized (lifecycleExecutorLock) {
   if (lifecycleExecutor != null && !lifecycleExecutor.isShutdown()) lifecycleExecutor.shutdown();
  }
 }
}

/**
* Loads the bound scope. Any schema other than 1.0's is left exactly as it is and makes the
* scope read-only; it is never rewritten.
*/
SavedState yq(TrackingIdentity /* identity */a) {
 readOnlyReason = null;
 SavedState /* loaded */b = repository.load();
 if (!b.ye()) {
  readOnlyReason = msg("im") + b.schemaVersion + msg("ax");
  log.warn(readOnlyReason);
  return new SavedState();
 }
 return b;
}

/**
* The engine's state serialized while holding the engine's lock: qm hands out live
* sessions and GE records, which the client thread keeps changing once the lock is released.
* A save {@code header}'s revision and savedAt, and the bound owner, are stamped on the fresh
* container first, so this one pass is the payload; the schema is the new container's current one.
*/
String aiw(SavedState /* header */b, boolean /* quick */c) {
 synchronized (engine) {
  SavedState /* state */d = engine.qm();
  if (b != null) {
   d.setRevision(b.revision);
   d.setSavedAtEpochMillis(b.savedAtEpochMillis);
   if (activeIdentity != null) d.setOwnerKey(activeIdentity.rsProfileKey);
  }
  // History is most of the save and closed sessions rarely change: each session is written on
  // its own and spliced into the same bytes one toJson would give; a quick save reuses the
  // JSON of a closed session whose saveStamp has not moved since.
  var /* gson */e = repository.gson;
  var /* history */f = d.history;
  d.history = new java.util.ArrayList<>();
  String /* rest */g = e.toJson(d);
  String /* hole */h = "\"generalSuspendedByCustom\":" + d.generalSuspendedByCustom + ",\"history\":[";
  int /* at */i = g.indexOf(h) + h.length();
  if (i < h.length() || g.charAt(i) != ']') {
   d.history = f;
   return e.toJson(d);
  }
  var /* json */j = new StringBuilder(g.length() + closedJsonChars + 4_096).append(g, 0, i);
  var /* next */k = new java.util.IdentityHashMap<Ad, Object[]>();
  long /* chars */l = 0;
  for (Ad /* session */m : f) {
   if (j.length() > i) j.append(',');
   Object[] /* cached */n = m == null ? null : closedJson.get(m);
   String /* one */o = c && n != null && (long) n[0] == m.saveStamp ? (String) n[1]
   : e.toJson(m);
   if (m != null && m.isClosed()) {
    k.put(m, new Object[] {m.saveStamp, o});
    l += o.length();
   }
   j.append(o);
  }
  closedJson = k;
  closedJsonChars = (int) min(l, 1 << 28);
  return j.append(g, i, g.length()).toString();
 }
}

/** Closed history sessions' last JSON by identity: {saveStamp, json}. */
java.util.Map<Ad, Object[]> closedJson = new java.util.IdentityHashMap<>();
int closedJsonChars;
/** Every 10th quick save writes everything afresh. */
int quickSaves;

/** A detached copy of the engine's state; agl normalizes its labels as a load does. */
SavedState snapshot() {
 return repository.gson.fromJson(aiw(null, false), SavedState.class);
}

Cs qn() {
 return qn(false);
}

Cs qn(boolean /* quick */b) {
 if (!repository.isBound() || readOnlyReason != null || writer.vw(repository.scopeGeneration)) {
  return null;
 }
 // Prefer the writer's verified lineage when a prior save is still
 // updating disk lastKnown — avoids queuing another base-N snapshot.
 long /* anl */c = max(repository.lastKnownDiskRevision, writer.tk());
 var /* header */d = new SavedState();
 d.setRevision(max(nextRevision, c + 1L));
 nextRevision = d.revision + 1L;
 d.setSavedAtEpochMillis(Ae.now());
 return new Cs(activeIdentity, repository.scopeGeneration, c, d, aiw(d, b && ++quickSaves % 10 != 0));
}
}
