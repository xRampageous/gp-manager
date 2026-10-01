package com.gpmanager;
import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import javax.inject.*;
import org.slf4j.*;
import net.runelite.client.util.Filepath;
import lombok.*;
import static com.gpmanager.Ak.msg;
import static com.gpmanager.Ae.nonNeg;
/**
* Durable session store with account-scoped files, recoverable commits, and
* multi-client write protection.
*
* <p>Legacy root {@code sessions.json} remains <b>unassigned</b> until an
* explicit claim. Account data lives under {@code accounts/<profileKey>/}.
*/
@Singleton
class SessionRepository {
static final Logger log = LoggerFactory.getLogger(SessionRepository.class);
/** 1.0 folders: saves written before 1.0 live elsewhere and are never read or rewritten. */
static final String ACCOUNTS_DIRECTORY = "profiles";
static final String UNASSIGNED_MARKER = "unbound";
final Gson gson;
Filepath rootDirectory;
Filepath exportDirectory;
/** Profile backups, one folder, newest 10 per account (owner 2026-09-28). */
Filepath backupDirectory;
final boolean accountAware;
/** The bound scope's save files. */
ScopeFiles bound;
boolean initialized;
TrackingIdentity boundIdentity;
long lastKnownDiskRevision;
long scopeGeneration;
@Inject
SessionRepository(Gson gson) {
 this.gson = gson;
 this.accountAware = true;
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
  if (!rootDirectory.equals(rooted)) throw new IllegalStateException(msg("in"));
  return;
 }
 this.rootDirectory = rooted;
 this.exportDirectory = rooted.joinSegment("exports");
 this.backupDirectory = rooted.joinSegment("backups");
 this.initialized = true;
 if (accountAware) {
  // Production starts unbound: do not write shared root history until
  // an RS profile identity is available and explicitly bound.
  mg(this.rootDirectory.joinSegment(UNASSIGNED_MARKER), null);
 } else {
  // Single-scope / test mode: the provided directory is the store.
  mg(rooted, null);
 }
}

void afp() {
 if (!initialized) throw new IllegalStateException(msg("ac"));
}

/** One scope directory's primary, backup, next, commit-stage and write-lock files. */
static class ScopeFiles {
 final Filepath dir;
 final Filepath state;
 final Filepath backup;
 final Filepath next;
 final Filepath stage;
 final Filepath lock;
 ScopeFiles(Filepath dir) {
  this.dir = dir;
  state = dir.joinSegment("sessions.json");
  backup = dir.joinSegment(msg("ev"));
  next = dir.joinSegment("sessions.next.json");
  stage = dir.joinSegment(msg("ew"));
  lock = dir.joinSegment("sessions.write.lock");
 }
}

void mg(Filepath directory, TrackingIdentity identity) {
 this.bound = new ScopeFiles(directory);
 this.boundIdentity = identity;
 this.lastKnownDiskRevision = 0L;
 this.scopeGeneration++;
}

/** Directory for an account identity without changing the live bind. */
Filepath ahw(TrackingIdentity identity) {
 afp();
 if (identity == null) return accountAware ? rootDirectory.joinSegment(UNASSIGNED_MARKER) : rootDirectory;
 return rootDirectory.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.ajc());
}

/** The backup file the last {@link #load()} had to fall back to; empty when the primary loaded. */
volatile String lastRecoveredFrom = "";
/** Size of the currently bound primary save, or zero when no primary exists. */
Filepath ty() {
 afp();
 return bound.dir;
}

boolean isBound() {
 return !accountAware || boundIdentity != null;
}

/**
* Bind writes/loads to an account scope. Does not migrate unassigned data.
*/
synchronized void mc(TrackingIdentity identity) {
 afp();
 if (identity == null) throw new IllegalArgumentException("identity");
 Filepath ard = rootDirectory.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.ajc());
 mg(ard, identity);
}

/** Leave any account scope; subsequent saves refuse until rebound. */
synchronized void akn() {
 afp();
 mg(rootDirectory.joinSegment(UNASSIGNED_MARKER), null);
}

synchronized SavedState load() {
 afp();
 lastRecoveredFrom = "";
 po();
 if (bound.state.exists()) {
  try {
   SavedState state = avc(bound.state);
   // Load establishes disk truth; do not retain a stale in-memory
   // high-water mark from a primary that was later discarded.
   lastKnownDiskRevision = nonNeg(state.revision);
   return state;
  } catch (Exception ex) {
   log.warn(msg("io"), ex);
   adf(bound);
  }
 }
 if (ln(bound.backup).stream().anyMatch(Filepath::exists)) {
  // After a committed reset, backup must not resurrect cleared data.
  // If a stage file says PRIMARY_COMMITTED, prefer repairing from
  // primary/next rather than falling back to an older backup.
  Cv stage = avb();
  if (stage == Cv.PRIMARY_COMMITTED && bound.next.exists()) {
   try {
    SavedState fromNext = avc(bound.next);
    afl(bound.next, bound.state);
    lastKnownDiskRevision = nonNeg(fromNext.revision);
    try {
     bound.state.copyTo(bound.backup, StandardCopyOption.REPLACE_EXISTING);
     akv(bound.stage, Cv.COMPLETE);
     bound.stage.deleteIfExists();
    } catch (IOException backupEx) {
     vj(bound, msg("ip"));
    }
    return fromNext;
   } catch (Exception ex) {
    log.warn(msg("u"), ex);
   }
  }
  for (Filepath candidate : ln(bound.backup)) {
   try {
    SavedState recovered = avc(candidate);
    log.warn(msg("ay"), candidate.getFileName());
    lastKnownDiskRevision = nonNeg(recovered.revision);
    lastRecoveredFrom = candidate.getFileName().toString();
    return recovered;
   } catch (Exception ex) { log.warn(msg("au"), candidate, ex); }
  }
 }
 lastKnownDiskRevision = 0L;
 return new SavedState();
}

SavedState avc(Filepath path) throws IOException {
 try (Reader reader = path.openReader(StandardOpenOption.READ)) {
  return adc(gson, new JsonParser().parse(reader));
 } catch (RuntimeException ex) {
  throw new IOException(msg("cm"), ex);
 }
}

/** Parses one payload. */
static SavedState adc(Gson gson, JsonElement json) throws IOException {
 if (json == null || !json.isJsonObject()) throw new IOException("Empty session state");
 SavedState state = gson.fromJson(json, SavedState.class);
 if (state == null) throw new IOException("Empty session state");
 // Schema-105 presentation labels are revalidated on every load; invalid text degrades to
 // generic presentation instead of failing the profile.
 state.aar();
 return state;
}

/**
* Save using an immutable write intent. Destination and owner key come from
* the intent, not from a later account bind.
*/
synchronized boolean save(Cs intent) {
 return pq(intent, false).isCommitted();
}

/**
* Destructive replacement fenced exactly like an ordinary save: the intent's
* expected base revision must still be the disk revision under the account
* lock, otherwise the newer disk state wins and the reset is refused. The
* original intent is attempted once; it is never rebased onto a newer
* revision, because that would be the stale overwrite the fence exists to
* prevent.
*/
synchronized Bm replaceStateDetailed(Cs intent) {
 if (intent == null || intent.state == null) return Bm.failedUnchanged("state was null");
 return pq(intent, true);
}

/**
* One fenced commit of {@code intent}. Returns {@code COMMITTED} once primary
* (and, for resets, backup alignment) finished, {@code COMMITTED_PRIMARY_ONLY}
* when the primary was replaced but a later step faulted, {@code CONFLICT}
* when disk no longer matches the expected base, else {@code FAILED_UNCHANGED}.
*/
Bm pq(Cs intent, boolean destructiveReset) {
 if (intent == null || intent.state == null) throw new IllegalArgumentException("intent");
 SavedState state = intent.state;
 TrackingIdentity targetIdentity = intent.identity;
 if (accountAware && targetIdentity == null) {
  log.warn(msg("iq"));
  return Bm.failedUnchanged(msg("af"));
 }
 var target = new ScopeFiles(ahw(targetIdentity));
 // Owner key is taken from the intent, never from a later live bind.
 if (targetIdentity != null) state.setOwnerKey(targetIdentity.rsProfileKey);
 state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
 if (state.savedAtEpochMillis <= 0L) state.setSavedAtEpochMillis(System.currentTimeMillis());
 FileLock lock = null;
 // Set once the primary replacement has been attempted; a fault after that
 // point is classified by inspecting the primary under the still-held lock.
 boolean app = false;
 try {
  target.dir.createDirectories();
  lock = jw(target.lock);
  if (lock == null) {
   log.warn(msg("ir"));
   return Bm.failedUnchanged(msg("v"));
  }
  Optional<Integer> alr = peek(target.state).map(object -> object.has("schemaVersion")
  && object.get("schemaVersion").isJsonPrimitive() ? object.get("schemaVersion").getAsInt() : 0);
  if (alr.isPresent() && alr.get() != SavedState.CURRENT_SCHEMA_VERSION) {
   // Any other schema (a newer version) is preserved read-only.
   log.warn(msg("is"), alr.get());
   return Bm.failedUnchanged("Disk state uses unsupported data schema " + alr.get());
  }
  long alq = ado(target.state).orElse(adm(target.backup).orElse(0L));
  // Compare-and-swap against the base revision observed when the
  // snapshot was created. Equal competing writers both claiming base N
  // must not both succeed — and a destructive reset is no exception:
  // a newer revision written by another client wins and the reset aborts.
  if (alq != intent.expectedBaseRevision) {
   log.warn(msg("it"), destructiveReset ? "reset" : "save", alq, intent.expectedBaseRevision);
   if (ahb(targetIdentity)) lastKnownDiskRevision = alq;
   return Bm.conflict("Disk revision changed (expected base "
   + intent.expectedBaseRevision + ", found " + alq + ")");
  }
  if (state.revision <= 0L) {
   state.setRevision(alq + 1L);
  } else if (state.revision <= alq) {
   log.warn(msg("iu"), state.revision, alq);
   return Bm.failedUnchanged("Revision " + state.revision + " does not advance disk revision " + alq);
  }
  try (Writer writer = target.next.openWriter(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
  StandardOpenOption.WRITE)) {
   if (intent.json == null) gson.toJson(state, writer);
   else writer.write(intent.json);
  }
  SavedState validated = avc(target.next);
  akv(target.stage, Cv.NEXT_READY);
  if (!destructiveReset && target.state.exists()) {
   boolean valid = false;
   try {
    avc(target.state);
    valid = true;
   } catch (Exception ex) {
    adf(target);
    if (target.state.exists()) throw new IOException(msg("iv"), ex);
   }
   if (valid) {
    Filepath aqv = target.dir.joinSegment(msg("cn"));
    target.state.copyTo(aqv, StandardCopyOption.REPLACE_EXISTING);
    afl(aqv, target.backup);
   }
  }
  app = true;
  afl(target.next, target.state);
  akv(target.stage, Cv.PRIMARY_COMMITTED);
  if (ahb(targetIdentity)) lastKnownDiskRevision = validated.revision;
  if (destructiveReset) {
   try {
    Filepath ank = target.dir.joinSegment(msg("bk"));
    target.state.copyTo(ank, StandardCopyOption.REPLACE_EXISTING);
    afl(ank, target.backup);
    akv(target.stage, Cv.COMPLETE);
    target.stage.deleteIfExists();
    target.next.deleteIfExists();
   } catch (IOException backupEx) {
    vj(target, msg("iw"));
    akv(target.stage, Cv.PRIMARY_COMMITTED);
    log.warn(msg("ix"), backupEx);
    return Bm.committedPrimaryOnly(msg("l"));
   }
   return Bm.committed(msg("ai"));
  }
  akv(target.stage, Cv.COMPLETE);
  target.stage.deleteIfExists();
  target.next.deleteIfExists();
  return Bm.committed("Save committed");
 } catch (IOException ex) {
  log.warn(destructiveReset ? msg("ad") : msg("be"), ex);
  // Still under the account lock, so a primary holding our revision can
  // only be ours: classify, never re-issue the write.
  if (app && ado(target.state).orElse(-1L) == state.revision) {
   if (ahb(targetIdentity)) lastKnownDiskRevision = state.revision;
   return Bm.committedPrimaryOnly(msg("iy"));
  }
  return Bm.failedUnchanged(destructiveReset ? msg("iz") : msg("ja"));
 } finally {
  afv(lock);
 }
}

/** One crash copy: the previous good save (owner 2026-09-28: not four near-identical ones). */
static List<Filepath> ln(Filepath primaryBackup) {
 return List.of(primaryBackup);
}

boolean ahb(TrackingIdentity targetIdentity) {
 if (boundIdentity == null && targetIdentity == null) return true;
 return boundIdentity != null && boundIdentity.equals(targetIdentity);
}

@AllArgsConstructor
static class Bm {
 enum Kind {
  COMMITTED, COMMITTED_PRIMARY_ONLY,
  /** Disk advanced past the expected base; nothing was written. */
  CONFLICT, FAILED_UNCHANGED
 }
 final Kind kind;
 final String message;
 static Bm committed(String message) {
  return new Bm(Kind.COMMITTED, message);
 }
 static Bm committedPrimaryOnly(String message) {
  return new Bm(Kind.COMMITTED_PRIMARY_ONLY, message);
 }
 static Bm failedUnchanged(String message) {
  return new Bm(Kind.FAILED_UNCHANGED, message);
 }
 static Bm conflict(String message) {
  return new Bm(Kind.CONFLICT, message);
 }
 boolean isCommitted() {
  return kind == Kind.COMMITTED || kind == Kind.COMMITTED_PRIMARY_ONLY;
 }
}

void po() {
 Cv stage = avb();
 if (stage == Cv.NONE || stage == Cv.COMPLETE) return;
 try {
  if (stage == Cv.NEXT_READY && bound.next.exists()) {
   // Primary not replaced yet — next is aspirational; leave disk.
   return;
  }
  if (stage == Cv.PRIMARY_COMMITTED && (bound.state.exists() || bound.next.exists())) {
   // A primary lost mid-commit is restored from next first; then align the backup.
   if (!bound.state.exists()) bound.next.copyTo(bound.state, StandardCopyOption.REPLACE_EXISTING);
   try {
    bound.state.copyTo(bound.backup, StandardCopyOption.REPLACE_EXISTING);
    bound.stage.deleteIfExists();
    bound.next.deleteIfExists();
   } catch (IOException ex) {
    vj(bound, msg("p"));
   }
  }
 } catch (IOException ex) {
  log.warn(msg("r"), ex);
 }
}

void vj(ScopeFiles files, String reason) throws IOException {
 if (files.backup.exists()) {
  Filepath aqx = files.dir.joinSegment(msg("bt") + System.currentTimeMillis() + ".json");
  files.backup.moveTo(aqx, StandardCopyOption.REPLACE_EXISTING);
  log.warn("{} ({})", reason, aqx.getFileName());
 }
}

/** The newest readable revision across the backup and its rotated copies. */
Optional<Long> adm(Filepath primaryBackup) {
 for (Filepath candidate : ln(primaryBackup)) {
  Optional<Long> revision = ado(candidate);
  if (revision.isPresent()) return revision;
 }
 return Optional.empty();
}

/** A stored payload's top-level object; empty when the file is missing, empty or unreadable. */
Optional<JsonObject> peek(Filepath path) {
 try {
  return path.exists() ? Optional.of(new JsonParser().parse(awy(path)).getAsJsonObject()) : Optional.empty();
 } catch (Exception ex) {
  return Optional.empty();
 }
}

Optional<Long> ado(Filepath path) {
 return peek(path).map(object -> object.has("revision") && object.get("revision").isJsonPrimitive()
 ? object.get("revision").getAsLong() : 0L);
}

void akv(Filepath stagePath, Cv stage) throws IOException {
 stagePath.write(stage.name(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
 StandardOpenOption.WRITE);
}

Cv avb() {
 if (!bound.stage.exists()) return Cv.NONE;
 try {
  String raw = awy(bound.stage).trim();
  return Cv.valueOf(raw);
 } catch (Exception ex) {
  return Cv.NONE;
 }
}

// Package visibility permits deterministic disk-failure tests without
// introducing production fault switches.
void afl(Filepath source, Filepath target) throws IOException {
 try {
  ma(source, target);
 } catch (AtomicMoveNotSupportedException ex) {
  // Windows/network volumes without atomic rename: plain replace of a validated file.
  source.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
 }
}

static String awy(Filepath path) throws IOException {
 try (Reader reader = path.openReader(StandardOpenOption.READ)) {
  var text = new StringBuilder();
  char[] buffer = new char[4096];
  int count;
  while ((count = reader.read(buffer)) >= 0) text.append(buffer, 0, count);
  return text.toString();
 }
}

void ma(Filepath source, Filepath target) throws IOException {
 source.moveTo(target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
}

FileLock jw(Filepath lockPath) throws IOException {
 lockPath.getParent().createDirectories();
 FileChannel channel = lockPath.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
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

void afv(FileLock lock) {
 if (lock == null) return;
 try {
  FileChannel channel = lock.channel();
  lock.release();
  channel.close();
 } catch (IOException ex) {
  log.debug(msg("ag"), ex);
 }
}

void adf(ScopeFiles files) {
 try {
  if (files.state.exists()) {
   Filepath backup = files.dir.joinSegment("sessions.corrupt-" + System.currentTimeMillis() + ".json");
   files.state.moveTo(backup, StandardCopyOption.REPLACE_EXISTING);
  }
 } catch (IOException backupError) {
  log.warn(msg("w"), backupError);
 }
}
}
