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
import static com.gpmanager.GameData.msg;
import static com.gpmanager.SafeMath.nonNeg;
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
bindScopeDirectory(this.rootDirectory.joinSegment(UNASSIGNED_MARKER), null);
} else {
// Single-scope / test mode: the provided directory is the store.
bindScopeDirectory(rooted, null);
}
}

void requireInitialized() {
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

void bindScopeDirectory(Filepath directory, TrackingIdentity identity) {
this.bound = new ScopeFiles(directory);
this.boundIdentity = identity;
this.lastKnownDiskRevision = 0L;
this.scopeGeneration++;
}

/** Directory for an account identity without changing the live bind. */
Filepath scopeDirectoryFor(TrackingIdentity identity) {
requireInitialized();
if (identity == null) return accountAware ? rootDirectory.joinSegment(UNASSIGNED_MARKER) : rootDirectory;
return rootDirectory.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.storageFolderName());
}

/** The backup file the last {@link #load()} had to fall back to; empty when the primary loaded. */
volatile String lastRecoveredFrom = "";
/** Size of the currently bound primary save, or zero when no primary exists. */
Filepath getDataDirectory() {
requireInitialized();
return bound.dir;
}

boolean isBound() {
return !accountAware || boundIdentity != null;
}

/**
* Bind writes/loads to an account scope. Does not migrate unassigned data.
*/
synchronized void bindIdentity(TrackingIdentity identity) {
requireInitialized();
if (identity == null) throw new IllegalArgumentException("identity");
Filepath accountDir = rootDirectory.joinSegment(ACCOUNTS_DIRECTORY).joinSegment(identity.storageFolderName());
bindScopeDirectory(accountDir, identity);
}

/** Leave any account scope; subsequent saves refuse until rebound. */
synchronized void unbindIdentity() {
requireInitialized();
bindScopeDirectory(rootDirectory.joinSegment(UNASSIGNED_MARKER), null);
}

synchronized SavedState load() {
requireInitialized();
lastRecoveredFrom = "";
completeInterruptedCommitQuietly();
if (bound.state.exists()) {
try {
SavedState state = readState(bound.state);
// Load establishes disk truth; do not retain a stale in-memory
// high-water mark from a primary that was later discarded.
lastKnownDiskRevision = nonNeg(state.revision);
return state;
} catch (Exception ex) {
log.warn(msg("io"), ex);
preserveCorruptState(bound);
}
}
if (backupCandidates(bound.backup).stream().anyMatch(Filepath::exists)) {
// After a committed reset, backup must not resurrect cleared data.
// If a stage file says PRIMARY_COMMITTED, prefer repairing from
// primary/next rather than falling back to an older backup.
CommitStage stage = readStage();
if (stage == CommitStage.PRIMARY_COMMITTED && bound.next.exists()) {
try {
SavedState fromNext = readState(bound.next);
replaceFile(bound.next, bound.state);
lastKnownDiskRevision = nonNeg(fromNext.revision);
try {
bound.state.copyTo(bound.backup, StandardCopyOption.REPLACE_EXISTING);
writeStage(bound.stage, CommitStage.COMPLETE);
bound.stage.deleteIfExists();
} catch (IOException backupEx) {
invalidateBackup(bound, msg("ip"));
}
return fromNext;
} catch (Exception ex) {
log.warn(msg("u"), ex);
}
}
for (Filepath candidate : backupCandidates(bound.backup)) {
try {
SavedState recovered = readState(candidate);
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

SavedState readState(Filepath path) throws IOException {
try (Reader reader = path.openReader(StandardOpenOption.READ)) {
return parseState(gson, new JsonParser().parse(reader));
} catch (RuntimeException ex) {
throw new IOException(msg("cm"), ex);
}
}

/** Parses one payload. */
static SavedState parseState(Gson gson, JsonElement json) throws IOException {
if (json == null || !json.isJsonObject()) throw new IOException("Empty session state");
SavedState state = gson.fromJson(json, SavedState.class);
if (state == null) throw new IOException("Empty session state");
// Schema-105 presentation labels are revalidated on every load; invalid text degrades to
// generic presentation instead of failing the profile.
state.normalizeActionLabels();
return state;
}

/**
* Save using an immutable write intent. Destination and owner key come from
* the intent, not from a later account bind.
*/
synchronized boolean save(WriteIntent intent) {
return commitIntent(intent, false).isCommitted();
}

/**
* Destructive replacement fenced exactly like an ordinary save: the intent's
* expected base revision must still be the disk revision under the account
* lock, otherwise the newer disk state wins and the reset is refused. The
* original intent is attempted once; it is never rebased onto a newer
* revision, because that would be the stale overwrite the fence exists to
* prevent.
*/
synchronized ReplaceOutcome replaceStateDetailed(WriteIntent intent) {
if (intent == null || intent.state == null) return ReplaceOutcome.failedUnchanged("state was null");
return commitIntent(intent, true);
}

/**
* One fenced commit of {@code intent}. Returns {@code COMMITTED} once primary
* (and, for resets, backup alignment) finished, {@code COMMITTED_PRIMARY_ONLY}
* when the primary was replaced but a later step faulted, {@code CONFLICT}
* when disk no longer matches the expected base, else {@code FAILED_UNCHANGED}.
*/
ReplaceOutcome commitIntent(WriteIntent intent, boolean destructiveReset) {
if (intent == null || intent.state == null) throw new IllegalArgumentException("intent");
SavedState state = intent.state;
TrackingIdentity targetIdentity = intent.identity;
if (accountAware && targetIdentity == null) {
log.warn(msg("iq"));
return ReplaceOutcome.failedUnchanged(msg("af"));
}
var target = new ScopeFiles(scopeDirectoryFor(targetIdentity));
// Owner key is taken from the intent, never from a later live bind.
if (targetIdentity != null) state.setOwnerKey(targetIdentity.rsProfileKey);
state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
if (state.savedAtEpochMillis <= 0L) state.setSavedAtEpochMillis(System.currentTimeMillis());
FileLock lock = null;
// Set once the primary replacement has been attempted; a fault after that
// point is classified by inspecting the primary under the still-held lock.
boolean primaryAttempted = false;
try {
target.dir.createDirectories();
lock = acquireWriteLock(target.lock);
if (lock == null) {
log.warn(msg("ir"));
return ReplaceOutcome.failedUnchanged(msg("v"));
}
Optional<Integer> diskSchema = peek(target.state).map(object -> object.has("schemaVersion")
&& object.get("schemaVersion").isJsonPrimitive() ? object.get("schemaVersion").getAsInt() : 0);
if (diskSchema.isPresent() && diskSchema.get() != SavedState.CURRENT_SCHEMA_VERSION) {
// Any other schema (a newer version) is preserved read-only.
log.warn(msg("is"), diskSchema.get());
return ReplaceOutcome.failedUnchanged("Disk state uses unsupported data schema " + diskSchema.get());
}
long diskRevision = peekRevision(target.state).orElse(peekBackupRevision(target.backup).orElse(0L));
// Compare-and-swap against the base revision observed when the
// snapshot was created. Equal competing writers both claiming base N
// must not both succeed — and a destructive reset is no exception:
// a newer revision written by another client wins and the reset aborts.
if (diskRevision != intent.expectedBaseRevision) {
log.warn(msg("it"), destructiveReset ? "reset" : "save", diskRevision, intent.expectedBaseRevision);
if (sameScopeAsBound(targetIdentity)) lastKnownDiskRevision = diskRevision;
return ReplaceOutcome.conflict("Disk revision changed (expected base "
+ intent.expectedBaseRevision + ", found " + diskRevision + ")");
}
if (state.revision <= 0L) {
state.setRevision(diskRevision + 1L);
} else if (state.revision <= diskRevision) {
log.warn(msg("iu"), state.revision, diskRevision);
return ReplaceOutcome.failedUnchanged("Revision " + state.revision + " does not advance disk revision " + diskRevision);
}
try (Writer writer = target.next.openWriter(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
StandardOpenOption.WRITE)) {
if (intent.json == null) gson.toJson(state, writer);
else writer.write(intent.json);
}
SavedState validated = readState(target.next);
writeStage(target.stage, CommitStage.NEXT_READY);
if (!destructiveReset && target.state.exists()) {
boolean valid = false;
try {
readState(target.state);
valid = true;
} catch (Exception ex) {
preserveCorruptState(target);
if (target.state.exists()) throw new IOException(msg("iv"), ex);
}
if (valid) {
Filepath temporaryBackup = target.dir.joinSegment(msg("cn"));
target.state.copyTo(temporaryBackup, StandardCopyOption.REPLACE_EXISTING);
replaceFile(temporaryBackup, target.backup);
}
}
primaryAttempted = true;
replaceFile(target.next, target.state);
writeStage(target.stage, CommitStage.PRIMARY_COMMITTED);
if (sameScopeAsBound(targetIdentity)) lastKnownDiskRevision = validated.revision;
if (destructiveReset) {
try {
Filepath backupTmp = target.dir.joinSegment(msg("bk"));
target.state.copyTo(backupTmp, StandardCopyOption.REPLACE_EXISTING);
replaceFile(backupTmp, target.backup);
writeStage(target.stage, CommitStage.COMPLETE);
target.stage.deleteIfExists();
target.next.deleteIfExists();
} catch (IOException backupEx) {
invalidateBackup(target, msg("iw"));
writeStage(target.stage, CommitStage.PRIMARY_COMMITTED);
log.warn(msg("ix"), backupEx);
return ReplaceOutcome.committedPrimaryOnly(msg("l"));
}
return ReplaceOutcome.committed(msg("ai"));
}
writeStage(target.stage, CommitStage.COMPLETE);
target.stage.deleteIfExists();
target.next.deleteIfExists();
return ReplaceOutcome.committed("Save committed");
} catch (IOException ex) {
log.warn(destructiveReset ? msg("ad") : msg("be"), ex);
// Still under the account lock, so a primary holding our revision can
// only be ours: classify, never re-issue the write.
if (primaryAttempted && peekRevision(target.state).orElse(-1L) == state.revision) {
if (sameScopeAsBound(targetIdentity)) lastKnownDiskRevision = state.revision;
return ReplaceOutcome.committedPrimaryOnly(msg("iy"));
}
return ReplaceOutcome.failedUnchanged(destructiveReset ? msg("iz") : msg("ja"));
} finally {
releaseLock(lock);
}
}

/** One crash copy: the previous good save (owner 2026-09-28: not four near-identical ones). */
static List<Filepath> backupCandidates(Filepath primaryBackup) {
return List.of(primaryBackup);
}

boolean sameScopeAsBound(TrackingIdentity targetIdentity) {
if (boundIdentity == null && targetIdentity == null) return true;
return boundIdentity != null && boundIdentity.equals(targetIdentity);
}

@AllArgsConstructor
static class ReplaceOutcome {
enum Kind {
COMMITTED, COMMITTED_PRIMARY_ONLY,
/** Disk advanced past the expected base; nothing was written. */
CONFLICT, FAILED_UNCHANGED
}
final Kind kind;
final String message;
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

void completeInterruptedCommitQuietly() {
CommitStage stage = readStage();
if (stage == CommitStage.NONE || stage == CommitStage.COMPLETE) return;
try {
if (stage == CommitStage.NEXT_READY && bound.next.exists()) {
// Primary not replaced yet — next is aspirational; leave disk.
return;
}
if (stage == CommitStage.PRIMARY_COMMITTED && (bound.state.exists() || bound.next.exists())) {
// A primary lost mid-commit is restored from next first; then align the backup.
if (!bound.state.exists()) bound.next.copyTo(bound.state, StandardCopyOption.REPLACE_EXISTING);
try {
bound.state.copyTo(bound.backup, StandardCopyOption.REPLACE_EXISTING);
bound.stage.deleteIfExists();
bound.next.deleteIfExists();
} catch (IOException ex) {
invalidateBackup(bound, msg("p"));
}
}
} catch (IOException ex) {
log.warn(msg("r"), ex);
}
}

void invalidateBackup(ScopeFiles files, String reason) throws IOException {
if (files.backup.exists()) {
Filepath tombstone = files.dir.joinSegment(msg("bt") + System.currentTimeMillis() + ".json");
files.backup.moveTo(tombstone, StandardCopyOption.REPLACE_EXISTING);
log.warn("{} ({})", reason, tombstone.getFileName());
}
}

/** The newest readable revision across the backup and its rotated copies. */
Optional<Long> peekBackupRevision(Filepath primaryBackup) {
for (Filepath candidate : backupCandidates(primaryBackup)) {
Optional<Long> revision = peekRevision(candidate);
if (revision.isPresent()) return revision;
}
return Optional.empty();
}

/** A stored payload's top-level object; empty when the file is missing, empty or unreadable. */
Optional<JsonObject> peek(Filepath path) {
try {
return path.exists() ? Optional.of(new JsonParser().parse(readText(path)).getAsJsonObject()) : Optional.empty();
} catch (Exception ex) {
return Optional.empty();
}
}

Optional<Long> peekRevision(Filepath path) {
return peek(path).map(object -> object.has("revision") && object.get("revision").isJsonPrimitive()
? object.get("revision").getAsLong() : 0L);
}

void writeStage(Filepath stagePath, CommitStage stage) throws IOException {
stagePath.write(stage.name(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
StandardOpenOption.WRITE);
}

CommitStage readStage() {
if (!bound.stage.exists()) return CommitStage.NONE;
try {
String raw = readText(bound.stage).trim();
return CommitStage.valueOf(raw);
} catch (Exception ex) {
return CommitStage.NONE;
}
}

// Package visibility permits deterministic disk-failure tests without
// introducing production fault switches.
void replaceFile(Filepath source, Filepath target) throws IOException {
try {
atomicMove(source, target);
} catch (AtomicMoveNotSupportedException ex) {
// Windows/network volumes without atomic rename: plain replace of a validated file.
source.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
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

FileLock acquireWriteLock(Filepath lockPath) throws IOException {
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

void releaseLock(FileLock lock) {
if (lock == null) return;
try {
FileChannel channel = lock.channel();
lock.release();
channel.close();
} catch (IOException ex) {
log.debug(msg("ag"), ex);
}
}

void preserveCorruptState(ScopeFiles files) {
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
