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
SessionRepository(Gson /* gson */a) {
 this.gson = a;
 this.accountAware = true;
}

SessionRepository(Gson /* gson */a, Filepath /* dataDirectory */b) {
 this(a, b, false);
}

SessionRepository(Gson /* gson */a, Filepath /* dataDirectory */b, boolean /* accountAware */c) {
 this.gson = a;
 this.accountAware = c;
 initialize(b);
}

synchronized void initialize(Filepath pluginDirectory) {
 if (pluginDirectory == null) throw new IllegalArgumentException("pluginDirectory");
 Filepath rooted = pluginDirectory.rooted();
 if (initialized) {
  if (!rootDirectory.equals(rooted)) throw new IllegalStateException(msg("in"));
  return;
 }
 this.rootDirectory = rooted;
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
 Filepath dir;
 Filepath state;
 Filepath backup;
 Filepath next;
 Filepath stage;
 Filepath lock;
 ScopeFiles(Filepath /* dir */c) {
  this.dir = c;
  state = c.joinSegment("sessions.json");
  backup = c.joinSegment(msg("ev"));
  next = c.joinSegment("sessions.next.json");
  stage = c.joinSegment(msg("ew"));
  lock = c.joinSegment("sessions.write.lock");
 }
}

void mg(Filepath /* directory */a, TrackingIdentity /* identity */b) {
 this.bound = new ScopeFiles(a);
 this.boundIdentity = b;
 this.lastKnownDiskRevision = 0L;
 this.scopeGeneration++;
}

/** Directory for an account identity without changing the live bind. */
Filepath ahw(TrackingIdentity /* identity */a) {
 afp();
 if (a == null) return accountAware ? rootDirectory.joinSegment(UNASSIGNED_MARKER) : rootDirectory;
 return rootDirectory.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(a.ajc());
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
synchronized void mc(TrackingIdentity /* identity */a) {
 afp();
 if (a == null) throw new IllegalArgumentException("identity");
 Filepath /* ard */b = rootDirectory.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(a.ajc());
 mg(b, a);
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
   SavedState /* state */b;
   try {
    b = avc(bound.state);
   } catch (FileSystemException /* locked */e) {
    // Antivirus or Windows Search holding the file is no corruption: read it once more.
    b = avc(bound.state);
   }
   // Load establishes disk truth; do not retain a stale in-memory
   // high-water mark from a primary that was later discarded.
   lastKnownDiskRevision = nonNeg(b.revision);
   return b;
  } catch (Exception /* ex */c) {
   log.warn(msg("io"), c);
   // Only a file that read but failed to parse is set aside as corrupt; a still-locked one stays put,
   // so the newer save is never lost behind its backup (review 2026-10-09).
   if (!(c instanceof FileSystemException)) adf(bound);
  }
 }
 if (bound.backup.exists()) {
  // After a committed reset, backup must not resurrect cleared data.
  // If a stage file says PRIMARY_COMMITTED, prefer repairing from
  // primary/next rather than falling back to an older backup.
  Cv /* stage */d = avb();
  if (d == Cv.PRIMARY_COMMITTED && bound.next.exists()) {
   try {
    SavedState /* fromNext */e = avc(bound.next);
    afl(bound.next, bound.state);
    lastKnownDiskRevision = nonNeg(e.revision);
    try {
     bound.state.copyTo(bound.backup, StandardCopyOption.REPLACE_EXISTING);
     akv(bound.stage, Cv.COMPLETE);
     bound.stage.deleteIfExists();
    } catch (IOException /* backupEx */f) {
     vj(bound, msg("ip"));
    }
    return e;
   } catch (Exception /* ex */g) {
    log.warn(msg("u"), g);
   }
  }
  try {
   SavedState i = avc(bound.backup);
   log.warn(msg("ay"), bound.backup.getFileName());
   lastKnownDiskRevision = nonNeg(i.revision);
   lastRecoveredFrom = bound.backup.getFileName().toString();
   return i;
  } catch (Exception j) { log.warn(msg("au"), bound.backup, j); }
 }
 lastKnownDiskRevision = 0L;
 return new SavedState();
}

SavedState avc(Filepath /* path */a) throws IOException {
 try (Reader /* reader */b = a.openReader(StandardOpenOption.READ)) {
  return adc(gson, new JsonParser().parse(b));
 } catch (RuntimeException /* ex */c) {
  throw new IOException(msg("cm"), c);
 }
}

/** Parses one payload. */
static SavedState adc(Gson /* gson */a, JsonElement /* json */b) throws IOException {
 if (b == null || !b.isJsonObject()) throw new IOException(msg("b9"));
 SavedState /* state */c = a.fromJson(b, SavedState.class);
 if (c == null) throw new IOException(msg("b9"));
 // Schema-105 presentation labels are revalidated on every load; invalid text degrades to
 // generic presentation instead of failing the profile.
 c.aar();
 return c;
}

/** Backup parse/validation with the caller's existing Gson policy and error behavior. */
static SavedState r1(Gson a, Filepath b) throws IOException {
 return adc(a, new JsonParser().parse(awy(b)));
}

/**
* Save using an immutable write intent. Destination and owner key come from
* the intent, not from a later account bind.
*/
synchronized boolean save(Cs /* intent */a) {
 return pq(a, false).isCommitted();
}

/**
* Destructive replacement fenced exactly like an ordinary save: the intent's
* expected base revision must still be the disk revision under the account
* lock, otherwise the newer disk state wins and the reset is refused. The
* original intent is attempted once; it is never rebased onto a newer
* revision, because that would be the stale overwrite the fence exists to
* prevent.
*/
synchronized Bm replaceStateDetailed(Cs /* intent */a) {
 if (a == null || a.state == null) return Bm.failedUnchanged("state was null");
 return pq(a, true);
}

/**
* One fenced commit of {@code intent}. Returns {@code COMMITTED} once primary
* (and, for resets, backup alignment) finished, {@code COMMITTED_PRIMARY_ONLY}
* when the primary was replaced but a later step faulted, {@code CONFLICT}
* when disk no longer matches the expected base, else {@code FAILED_UNCHANGED}.
*/
Bm pq(Cs /* intent */b, boolean /* destructiveReset */c) {
 if (b == null || b.state == null) throw new IllegalArgumentException("intent");
 SavedState /* state */d = b.state;
 TrackingIdentity /* targetIdentity */e = b.identity;
 if (accountAware && e == null) {
  log.warn(msg("iq"));
  return Bm.failedUnchanged(msg("af"));
 }
 var /* target */f = new ScopeFiles(ahw(e));
 // Owner key is taken from the intent, never from a later live bind.
 if (e != null) d.setOwnerKey(e.rsProfileKey);
 d.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
 if (d.savedAtEpochMillis <= 0L) d.setSavedAtEpochMillis(Ae.now());
 FileLock /* lock */g = null;
 // Set once the primary replacement has been attempted; a fault after that
 // point is classified by inspecting the primary under the still-held lock.
 boolean /* app */h = false;
 try {
  f.dir.createDirectories();
  g = jw(f.lock);
  if (g == null) {
   log.warn(msg("ir"));
   return Bm.failedUnchanged(msg("v"));
  }
  Optional<Integer> /* alr */i = peek(f.state).map(/* object */j -> j.has("schemaVersion")
  && j.get("schemaVersion").isJsonPrimitive() ? j.get("schemaVersion").getAsInt() : 0);
  if (i.isPresent() && i.get() != SavedState.CURRENT_SCHEMA_VERSION) {
   // Any other schema (a newer version) is preserved read-only.
   log.warn(msg("is"), i.get());
   return Bm.failedUnchanged(msg("b8") + i.get());
  }
  long /* alq */k = ado(f.state).orElse(adm(f.backup).orElse(0L));
  // Compare-and-swap against the base revision observed when the
  // snapshot was created. Equal competing writers both claiming base N
  // must not both succeed — and a destructive reset is no exception:
  // a newer revision written by another client wins and the reset aborts.
  if (k != b.expectedBaseRevision) {
   log.warn(msg("it"), c ? "reset" : "save", k, b.expectedBaseRevision);
   if (ahb(e)) lastKnownDiskRevision = k;
   return Bm.conflict(msg("b6")
   + b.expectedBaseRevision + ", found " + k + ")");
  }
  if (d.revision <= 0L) {
   d.setRevision(k + 1L);
  } else if (d.revision <= k) {
   log.warn(msg("iu"), d.revision, k);
   return Bm.failedUnchanged("Revision " + d.revision + msg("b7") + k);
  }
  try (Writer /* writer */m = f.next.openWriter(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
  StandardOpenOption.WRITE)) {
   if (b.json == null) gson.toJson(d, m);
   else m.write(b.json);
  }
  SavedState /* validated */n = avc(f.next);
  akv(f.stage, Cv.NEXT_READY);
  if (!c && f.state.exists()) {
   boolean /* valid */o = false;
   try {
    avc(f.state);
    o = true;
   } catch (Exception /* ex */p) {
    adf(f);
    if (f.state.exists()) throw new IOException(msg("iv"), p);
   }
   if (o) {
    b1(f, msg("cn"));
   }
  }
  h = true;
  afl(f.next, f.state);
  akv(f.stage, Cv.PRIMARY_COMMITTED);
  if (ahb(e)) lastKnownDiskRevision = n.revision;
  if (c) {
   try {
    b1(f, msg("bk"));
    c1(f);
   } catch (IOException /* backupEx */s) {
    vj(f, msg("iw"));
    akv(f.stage, Cv.PRIMARY_COMMITTED);
    log.warn(msg("ix"), s);
    return Bm.committedPrimaryOnly(msg("l"));
   }
   return Bm.committed(msg("ai"));
  }
  c1(f);
  return Bm.committed("Save committed");
 } catch (IOException /* ex */t) {
  log.warn(c ? msg("ad") : msg("be"), t);
  // Still under the account lock, so a primary holding our revision can
  // only be ours: classify, never re-issue the write.
  if (h && ado(f.state).orElse(-1L) == d.revision) {
   if (ahb(e)) lastKnownDiskRevision = d.revision;
   return Bm.committedPrimaryOnly(msg("iy"));
  }
  return Bm.failedUnchanged(c ? msg("iz") : msg("ja"));
 } finally {
  afv(g);
 }
}



boolean ahb(TrackingIdentity /* targetIdentity */a) {
 if (boundIdentity == null && a == null) return true;
 return boundIdentity != null && boundIdentity.equals(a);
}

@AllArgsConstructor
static class Bm {
 enum Kind {
  COMMITTED, COMMITTED_PRIMARY_ONLY,
  /** Disk advanced past the expected base; nothing was written. */
  CONFLICT, FAILED_UNCHANGED
 }
 Kind kind;
 String message;
 static Bm committed(String /* message */c) {
  return new Bm(Kind.COMMITTED, c);
 }
 static Bm committedPrimaryOnly(String /* message */c) {
  return new Bm(Kind.COMMITTED_PRIMARY_ONLY, c);
 }
 static Bm failedUnchanged(String /* message */c) {
  return new Bm(Kind.FAILED_UNCHANGED, c);
 }
 static Bm conflict(String /* message */c) {
  return new Bm(Kind.CONFLICT, c);
 }
 boolean isCommitted() {
  return kind == Kind.COMMITTED || kind == Kind.COMMITTED_PRIMARY_ONLY;
 }
}

void po() {
 Cv /* stage */a = avb();
 if (a == Cv.NONE || a == Cv.COMPLETE) return;
 try {
  if (a == Cv.NEXT_READY && bound.next.exists()) {
   // Primary not replaced yet — next is aspirational; leave disk.
   return;
  }
  if (a == Cv.PRIMARY_COMMITTED && (bound.state.exists() || bound.next.exists())) {
   // A primary lost mid-commit is restored from next first; then align the backup.
   if (!bound.state.exists()) bound.next.copyTo(bound.state, StandardCopyOption.REPLACE_EXISTING);
   try {
    bound.state.copyTo(bound.backup, StandardCopyOption.REPLACE_EXISTING);
    bound.stage.deleteIfExists();
    bound.next.deleteIfExists();
   } catch (IOException /* ex */b) {
    vj(bound, msg("p"));
   }
  }
 } catch (IOException /* ex */c) {
  log.warn(msg("r"), c);
 }
}

void vj(ScopeFiles /* files */a, String /* reason */b) throws IOException {
 if (a.backup.exists()) {
  Filepath /* aqx */c = a.dir.joinSegment(msg("bt") + Ae.now() + ".json");
  a.backup.moveTo(c, StandardCopyOption.REPLACE_EXISTING);
  log.warn("{} ({})", b, c.getFileName());
 }
}

/** The existing single crash copy's readable revision. */
Optional<Long> adm(Filepath a) { return ado(a); }

/** A stored payload's top-level object; empty when the file is missing, empty or unreadable. */
Optional<JsonObject> peek(Filepath /* path */a) {
 try {
  return a.exists() ? Optional.of(new JsonParser().parse(awy(a)).getAsJsonObject()) : Optional.empty();
 } catch (Exception /* ex */b) {
  return Optional.empty();
 }
}

Optional<Long> ado(Filepath /* path */a) {
 return peek(a).map(/* object */b -> b.has("revision") && b.get("revision").isJsonPrimitive()
 ? b.get("revision").getAsLong() : 0L);
}

void akv(Filepath /* stagePath */a, Cv /* stage */b) throws IOException {
 a.write(b.name(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
 StandardOpenOption.WRITE);
}

Cv avb() {
 if (!bound.stage.exists()) return Cv.NONE;
 try {
  String /* raw */a = awy(bound.stage).trim();
  return Cv.valueOf(a);
 } catch (Exception /* ex */b) {
  return Cv.NONE;
 }
}

// Package visibility permits deterministic disk-failure tests without
// introducing production fault switches.
void afl(Filepath /* source */b, Filepath /* target */c) throws IOException {
 try {
  ma(b, c);
 } catch (AtomicMoveNotSupportedException /* ex */d) {
  // Windows/network volumes without atomic rename: plain replace of a validated file.
  b.moveTo(c, StandardCopyOption.REPLACE_EXISTING);
 } catch (AccessDeniedException /* ex */e) {
  // Antivirus or Windows Search can hold a just-written file for a moment; one more try
  // often clears it, and the writer retries later without alarming anyone (owner 1.2).
  ma(b, c);
 }
}

static String awy(Filepath /* path */a) throws IOException {
 try (Reader /* reader */b = a.openReader(StandardOpenOption.READ)) {
  var /* text */c = new StringBuilder();
  char[] /* buffer */d = new char[4096];
  int /* count */e;
  while ((e = b.read(d)) >= 0) c.append(d, 0, e);
  return c.toString();
 }
}

void ma(Filepath /* source */a, Filepath /* target */b) throws IOException {
 a.moveTo(b, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
}

FileLock jw(Filepath /* lockPath */b) throws IOException {
 b.getParent().createDirectories();
 FileChannel /* channel */c = b.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
 FileLock /* lock */d;
 try {
  d = c.tryLock();
 } catch (OverlappingFileLockException /* heldInThisProcess */e) {
  // Another repository instance in this JVM (or a test) holds the account lock.
  d = null;
 }
 if (d == null) {
  c.close();
  return null;
 }
 return d;
}

void afv(FileLock /* lock */a) {
 if (a == null) return;
 try {
  FileChannel /* channel */b = a.channel();
  a.release();
  b.close();
 } catch (IOException /* ex */c) {
  log.debug(msg("ag"), c);
 }
}

void adf(ScopeFiles /* files */a) {
 try {
  if (a.state.exists()) {
   Filepath /* backup */b = a.dir.joinSegment("sessions.corrupt-" + Ae.now() + ".json");
   a.state.moveTo(b, StandardCopyOption.REPLACE_EXISTING);
  }
 } catch (IOException /* backupError */c) {
  log.warn(msg("w"), c);
 }
}
/** Atomic backup replacement, using each operation's original temporary file name. */
void b1(ScopeFiles a, String b) throws IOException {
 Filepath c = a.dir.joinSegment(b);
 a.state.copyTo(c, StandardCopyOption.REPLACE_EXISTING);
 afl(c, a.backup);
}

/** Finish an already committed write in the original stage/delete order. */
void c1(ScopeFiles a) throws IOException {
 akv(a.stage, Cv.COMPLETE);
 a.stage.deleteIfExists();
 a.next.deleteIfExists();
}
}
