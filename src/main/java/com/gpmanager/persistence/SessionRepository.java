package com.gpmanager.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.client.RuneLite;

/**
 * Durable session store with account-scoped files, recoverable commits, and
 * multi-client write protection.
 *
 * <p>Legacy root {@code sessions.json} remains <b>unassigned</b> until an
 * explicit claim. Account data lives under {@code accounts/<profileKey>/}.
 */
@Singleton
public class SessionRepository
{
    private static final Logger log = LoggerFactory.getLogger(SessionRepository.class);
    private static final String DATA_DIRECTORY = "gp-manager";
    /** Older folder names, newest first; the first one that exists is copied into the current folder once. */
    private static final String[] LEGACY_DATA_DIRECTORIES = {"profit-manager", "smart-profit-tracker"};
    private static final String LEGACY_DATA_DIRECTORY = LEGACY_DATA_DIRECTORIES[0];
    private static final String ACCOUNTS_DIRECTORY = "accounts";
    private static final String UNASSIGNED_MARKER = "unassigned";

    private final Gson gson;
    private final Path rootDirectory;
    private final Path exportDirectory;
    private final boolean accountAware;

    private Path scopeDirectory;
    private Path stateFile;
    private Path backupFile;
    private Path nextFile;
    private Path stageFile;
    private Path lockFile;
    private TrackingIdentity boundIdentity;
    private long lastKnownDiskRevision;
    private long scopeGeneration;
    private boolean exclusiveWriter;

    @Inject
    public SessionRepository(Gson gson)
    {
        this(
            gson,
            RuneLite.RUNELITE_DIR.toPath().resolve(DATA_DIRECTORY),
            firstExistingLegacyDirectory(RuneLite.RUNELITE_DIR.toPath()),
            true);
    }

    public SessionRepository(Gson gson, Path dataDirectory)
    {
        this(gson, dataDirectory, null, false);
    }

    SessionRepository(Gson gson, Path dataDirectory, Path legacyDataDirectory)
    {
        this(gson, dataDirectory, legacyDataDirectory, false);
    }

    SessionRepository(Gson gson, Path dataDirectory, Path legacyDataDirectory, boolean accountAware)
    {
        this.gson = UnknownFieldPreservation.wrap(gson);
        this.accountAware = accountAware;
        this.rootDirectory = migrateLegacyDirectory(dataDirectory, legacyDataDirectory);
        this.exportDirectory = this.rootDirectory.resolve("exports");
        if (accountAware)
        {
            // Production starts unbound: do not write shared root history until
            // an RS profile identity is available and explicitly bound.
            bindScopeDirectory(this.rootDirectory.resolve(UNASSIGNED_MARKER), null);
        }
        else
        {
            // Single-scope / test mode: the provided directory is the store.
            bindScopeDirectory(this.rootDirectory, null);
        }
    }

    private static Path firstExistingLegacyDirectory(Path runeliteDir)
    {
        for (String name : LEGACY_DATA_DIRECTORIES)
        {
            Path candidate = runeliteDir.resolve(name);
            if (Files.exists(candidate)) return candidate;
        }
        return runeliteDir.resolve(LEGACY_DATA_DIRECTORY);
    }

    private static Path migrateLegacyDirectory(Path target, Path legacy)
    {
        if (target == null)
        {
            throw new IllegalArgumentException("dataDirectory");
        }
        if (Files.exists(target) || legacy == null || !Files.exists(legacy))
        {
            return target;
        }

        try
        {
            copyDirectory(legacy, target);
            log.info("Migrated GP Manager data from the legacy directory");
            return target;
        }
        catch (IOException ex)
        {
            log.warn("Unable to copy legacy GP Manager data; continuing from the legacy directory", ex);
            return legacy;
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException
    {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException
            {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException
            {
                Files.copy(
                    file,
                    target.resolve(source.relativize(file)),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void bindScopeDirectory(Path directory, TrackingIdentity identity)
    {
        this.scopeDirectory = directory;
        this.stateFile = directory.resolve("sessions.json");
        this.backupFile = directory.resolve("sessions.backup.json");
        this.nextFile = directory.resolve("sessions.next.json");
        this.stageFile = directory.resolve("sessions.commit.stage");
        this.lockFile = directory.resolve("sessions.write.lock");
        this.boundIdentity = identity;
        this.lastKnownDiskRevision = 0L;
        this.scopeGeneration++;
        this.exclusiveWriter = false;
    }

    public long getScopeGeneration()
    {
        return scopeGeneration;
    }

    /** Directory for an account identity without changing the live bind. */
    public Path scopeDirectoryFor(TrackingIdentity identity)
    {
        if (identity == null)
        {
            return accountAware ? rootDirectory.resolve(UNASSIGNED_MARKER) : rootDirectory;
        }
        return rootDirectory.resolve(ACCOUNTS_DIRECTORY).resolve(identity.storageFolderName());
    }

    public Path getRootDirectory()
    {
        return rootDirectory;
    }

    /** The backup file the last {@link #load()} had to fall back to; empty when the primary loaded. */
    private volatile String lastRecoveredFrom = "";

    public String getLastRecoveredFrom()
    {
        return lastRecoveredFrom;
    }

    /** Number of existing rotated good-save copies (.1 through .3) in this scope. */
    public synchronized int getRotatedBackupCount()
    {
        int count = 0;
        for (int index = 1; index <= 3; index++)
        {
            Path candidate = backupFile.resolveSibling(backupFile.getFileName() + "." + index);
            if (Files.exists(candidate)) count++;
        }
        return count;
    }

    /** Size of the currently bound primary save, or zero when no primary exists. */
    public synchronized long getCurrentStateBytes()
    {
        try { return Files.exists(stateFile) ? Files.size(stateFile) : 0L; }
        catch (IOException ex) { return 0L; }
    }

    public Path getDataDirectory()
    {
        return scopeDirectory;
    }

    public Path getExportDirectory()
    {
        return exportDirectory;
    }

    public TrackingIdentity getBoundIdentity()
    {
        return boundIdentity;
    }

    public long getLastKnownDiskRevision()
    {
        return lastKnownDiskRevision;
    }

    public boolean isAccountAware()
    {
        return accountAware;
    }

    public boolean isBound()
    {
        return !accountAware || boundIdentity != null;
    }

    public boolean hasUnassignedLegacyData()
    {
        if (!accountAware)
        {
            return false;
        }
        Path legacy = rootDirectory.resolve("sessions.json");
        return Files.exists(legacy);
    }

    public Path unassignedStateFile()
    {
        return rootDirectory.resolve("sessions.json");
    }

    /**
     * Bind writes/loads to an account scope. Does not migrate unassigned data.
     */
    public synchronized void bindIdentity(TrackingIdentity identity)
    {
        if (identity == null)
        {
            throw new IllegalArgumentException("identity");
        }
        Path accountDir = rootDirectory.resolve(ACCOUNTS_DIRECTORY).resolve(identity.storageFolderName());
        bindScopeDirectory(accountDir, identity);
    }

    /** Leave any account scope; subsequent saves refuse until rebound. */
    public synchronized void unbindIdentity()
    {
        releaseWriterLock();
        bindScopeDirectory(rootDirectory.resolve(UNASSIGNED_MARKER), null);
    }

    /**
     * Explicitly claim root legacy data into the bound account. Never called
     * implicitly on login — silent assignment is forbidden.
     */
    public synchronized boolean claimUnassignedLegacy(TrackingIdentity identity) throws IOException
    {
        if (identity == null)
        {
            throw new IllegalArgumentException("identity");
        }
        Path legacyPrimary = rootDirectory.resolve("sessions.json");
        Path legacyBackup = rootDirectory.resolve("sessions.backup.json");
        if (!Files.exists(legacyPrimary) && !Files.exists(legacyBackup))
        {
            return false;
        }
        bindIdentity(identity);
        Files.createDirectories(scopeDirectory);
        if (Files.exists(legacyPrimary))
        {
            Files.move(legacyPrimary, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(legacyBackup))
        {
            Files.move(legacyBackup, backupFile, StandardCopyOption.REPLACE_EXISTING);
        }
        // Mark claim so operators can see the move was intentional.
        Files.writeString(
            rootDirectory.resolve("unassigned-claimed.txt"),
            "claimed-to=" + identity.storageFolderName() + "\nclaimed-at=" + System.currentTimeMillis() + "\n",
            StandardCharsets.UTF_8);
        return true;
    }

    public synchronized SavedState load()
    {
        lastRecoveredFrom = "";
        completeInterruptedCommitQuietly();
        if (Files.exists(stateFile))
        {
            try
            {
                SavedState state = readState(stateFile);
                // Load establishes disk truth; do not retain a stale in-memory
                // high-water mark from a primary that was later discarded.
                lastKnownDiskRevision = Math.max(0L, state.getRevision());
                return state;
            }
            catch (Exception ex)
            {
                log.warn("Unable to load GP Manager state; preserving corrupt file", ex);
                preserveCorruptState();
            }
        }
        if (backupCandidates(backupFile).stream().anyMatch(Files::exists))
        {
            // After a committed reset, backup must not resurrect cleared data.
            // If a stage file says PRIMARY_COMMITTED, prefer repairing from
            // primary/next rather than falling back to an older backup.
            CommitStage stage = readStage();
            if (stage == CommitStage.PRIMARY_COMMITTED && Files.exists(nextFile))
            {
                try
                {
                    SavedState fromNext = readState(nextFile);
                    replaceFile(nextFile, stateFile);
                    lastKnownDiskRevision = Math.max(0L, fromNext.getRevision());
                    try
                    {
                        Files.copy(stateFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                        writeStage(CommitStage.COMPLETE);
                        clearStage();
                    }
                    catch (IOException backupEx)
                    {
                        invalidateBackup("Committed primary recovered; obsolete backup invalidated");
                    }
                    return fromNext;
                }
                catch (Exception ex)
                {
                    log.warn("Unable to finish interrupted primary commit", ex);
                }
            }
            for (Path candidate : backupCandidates(backupFile))
            {
                try
                {
                    SavedState recovered = readState(candidate);
                    log.warn("Recovered GP Manager state from {}", candidate.getFileName());
                    lastKnownDiskRevision = Math.max(0L, recovered.getRevision());
                    lastRecoveredFrom = candidate.getFileName().toString();
                    return recovered;
                }
                catch (Exception ex) { log.warn("Unable to read GP Manager backup {}", candidate, ex); }
            }
        }
        lastKnownDiskRevision = 0L;
        return new SavedState();
    }

    public synchronized Optional<SavedState> loadUnassignedLegacy()
    {
        Path legacy = rootDirectory.resolve("sessions.json");
        if (!Files.exists(legacy))
        {
            return Optional.empty();
        }
        try
        {
            return Optional.of(readState(legacy));
        }
        catch (Exception ex)
        {
            log.warn("Unable to read unassigned legacy GP Manager state", ex);
            return Optional.empty();
        }
    }

    private SavedState readState(Path path) throws IOException
    {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
        {
            SavedState state = gson.fromJson(reader, SavedState.class);
            if (state == null)
            {
                throw new IOException("Empty session state");
            }
            return state;
        }
    }

    /**
     * Ordinary save with revision fencing and exclusive write lock.
     *
     * @return true when the new revision is committed to primary
     */
    public synchronized boolean save(SavedState state)
    {
        healCorruptPrimaryBeforeSave();
        syncLastKnownFromDiskIfNeeded();
        long base = lastKnownDiskRevision;
        WriteIntent intent = boundIdentity == null
            ? WriteIntent.unbound(scopeGeneration, base, state)
            : new WriteIntent(boundIdentity, scopeGeneration, base, state);
        return save(intent);
    }

    /**
     * Save using an immutable write intent. Destination and owner key come from
     * the intent, not from a later account bind.
     */
    public synchronized boolean save(WriteIntent intent)
    {
        return commitIntent(intent, false);
    }

    /**
     * Intentional destructive reset. Committed once primary is replaced; backup
     * is then forced to match or is invalidated so cleared data cannot return.
     */
    public synchronized boolean replaceState(SavedState state)
    {
        healCorruptPrimaryBeforeSave();
        syncLastKnownFromDiskIfNeeded();
        long base = lastKnownDiskRevision;
        WriteIntent intent = boundIdentity == null
            ? WriteIntent.unbound(scopeGeneration, base, state)
            : new WriteIntent(boundIdentity, scopeGeneration, base, state);
        return replaceState(intent);
    }

    public synchronized boolean replaceState(WriteIntent intent)
    {
        return commitIntent(intent, true);
    }

    private void healCorruptPrimaryBeforeSave()
    {
        if (!Files.exists(stateFile))
        {
            return;
        }
        try
        {
            readState(stateFile);
        }
        catch (Exception ex)
        {
            log.warn("Primary state unreadable before save; preserving evidence", ex);
            preserveCorruptState();
            lastKnownDiskRevision = peekBackupRevision(backupFile).orElse(0L);
        }
    }

    private void syncLastKnownFromDiskIfNeeded()
    {
        if (lastKnownDiskRevision > 0L)
        {
            return;
        }
        lastKnownDiskRevision = peekRevision(stateFile)
            .orElse(peekBackupRevision(backupFile).orElse(0L));
    }

    private boolean commitIntent(WriteIntent intent, boolean destructiveReset)
    {
        if (intent == null || intent.getState() == null)
        {
            throw new IllegalArgumentException("intent");
        }
        SavedState state = intent.getState();
        TrackingIdentity targetIdentity = intent.getIdentity();
        if (accountAware && targetIdentity == null)
        {
            log.warn("Refusing GP Manager save while no account identity is on the write intent");
            return false;
        }

        Path targetDir = scopeDirectoryFor(targetIdentity);
        Path targetState = targetDir.resolve("sessions.json");
        Path targetBackup = targetDir.resolve("sessions.backup.json");
        Path targetNext = targetDir.resolve("sessions.next.json");
        Path targetStage = targetDir.resolve("sessions.commit.stage");
        Path targetLock = targetDir.resolve("sessions.write.lock");

        // Owner key is taken from the intent, never from a later live bind.
        if (targetIdentity != null)
        {
            state.setOwnerKey(targetIdentity.getRsProfileKey());
        }
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        if (state.getSavedAtEpochMillis() <= 0L)
        {
            state.setSavedAtEpochMillis(System.currentTimeMillis());
        }

        FileLock lock = null;
        try
        {
            Files.createDirectories(targetDir);
            lock = acquireWriteLock(targetLock);
            if (lock == null)
            {
                log.warn("Another GP Manager client holds the write lock; refusing save");
                return false;
            }

            long diskRevision = peekRevision(targetState).orElse(peekBackupRevision(targetBackup).orElse(0L));
            if (!destructiveReset)
            {
                // Compare-and-swap against the base revision observed when the
                // snapshot was created. Equal competing writers both claiming
                // base N must not both succeed.
                if (diskRevision != intent.getExpectedBaseRevision())
                {
                    log.warn(
                        "Refusing concurrent/stale save: disk revision {} != expected base {}",
                        diskRevision,
                        intent.getExpectedBaseRevision());
                    if (sameScopeAsBound(targetIdentity))
                    {
                        lastKnownDiskRevision = diskRevision;
                    }
                    return false;
                }
            }
            if (state.getRevision() <= 0L)
            {
                state.setRevision(diskRevision + 1L);
            }
            else if (!destructiveReset && state.getRevision() <= diskRevision)
            {
                log.warn(
                    "Refusing non-advancing revision {} against disk {}",
                    state.getRevision(),
                    diskRevision);
                return false;
            }

            try (Writer writer = Files.newBufferedWriter(targetNext, StandardCharsets.UTF_8))
            {
                gson.toJson(state, writer);
            }
            SavedState validated = readState(targetNext);
            writeStage(targetStage, CommitStage.NEXT_READY);

            if (!destructiveReset && Files.exists(targetState))
            {
                boolean valid = false;
                try
                {
                    readState(targetState);
                    valid = true;
                }
                catch (Exception ex)
                {
                    preserveCorruptState(targetState, targetDir);
                    if (Files.exists(targetState))
                    {
                        throw new IOException("Cannot preserve damaged state; refusing to overwrite it", ex);
                    }
                }
                if (valid)
                {
                    Path temporaryBackup = targetDir.resolve("sessions.backup.json.tmp");
                    rotateBackups(targetDir, targetBackup);
                    Files.copy(targetState, temporaryBackup, StandardCopyOption.REPLACE_EXISTING);
                    replaceFile(temporaryBackup, targetBackup);
                }
            }

            Path primaryTmp = targetDir.resolve(
                destructiveReset ? "sessions.reset.json.tmp" : "sessions.json.tmp");
            Files.copy(targetNext, primaryTmp, StandardCopyOption.REPLACE_EXISTING);
            replaceFile(primaryTmp, targetState);
            writeStage(targetStage, CommitStage.PRIMARY_COMMITTED);
            if (sameScopeAsBound(targetIdentity))
            {
                lastKnownDiskRevision = validated.getRevision();
            }

            if (destructiveReset)
            {
                try
                {
                    Path backupTmp = targetDir.resolve("sessions.backup.reset.json.tmp");
                    Files.copy(targetState, backupTmp, StandardCopyOption.REPLACE_EXISTING);
                    replaceFile(backupTmp, targetBackup);
                    deleteRotatedBackups(targetBackup);
                    writeStage(targetStage, CommitStage.COMPLETE);
                    clearStage(targetStage);
                    Files.deleteIfExists(targetNext);
                }
                catch (IOException backupEx)
                {
                    invalidateBackup(targetBackup, targetDir,
                        "Reset primary committed; obsolete backup invalidated after align failure");
                    writeStage(targetStage, CommitStage.PRIMARY_COMMITTED);
                    log.warn(
                        "Reset primary committed but backup could not be aligned; obsolete backup removed",
                        backupEx);
                }
            }
            else
            {
                writeStage(targetStage, CommitStage.COMPLETE);
                clearStage(targetStage);
                Files.deleteIfExists(targetNext);
            }
            return true;
        }
        catch (IOException ex)
        {
            log.warn(
                destructiveReset
                    ? "Unable to replace GP Manager reset state"
                    : "Unable to save GP Manager state",
                ex);
            return false;
        }
        finally
        {
            releaseLock(lock);
        }
    }

    private static java.util.List<Path> backupCandidates(Path primaryBackup)
    {
        java.util.List<Path> result = new java.util.ArrayList<>();
        result.add(primaryBackup);
        result.add(primaryBackup.resolveSibling(primaryBackup.getFileName() + ".1"));
        result.add(primaryBackup.resolveSibling(primaryBackup.getFileName() + ".2"));
        result.add(primaryBackup.resolveSibling(primaryBackup.getFileName() + ".3"));
        return result;
    }

    private static void rotateBackups(Path directory, Path primaryBackup) throws IOException
    {
        Path third = primaryBackup.resolveSibling(primaryBackup.getFileName() + ".3");
        Path second = primaryBackup.resolveSibling(primaryBackup.getFileName() + ".2");
        Path first = primaryBackup.resolveSibling(primaryBackup.getFileName() + ".1");
        Files.deleteIfExists(third);
        if (Files.exists(second)) Files.move(second, third, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(first)) Files.move(first, second, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(primaryBackup)) Files.move(primaryBackup, first, StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean sameScopeAsBound(TrackingIdentity targetIdentity)
    {
        if (boundIdentity == null && targetIdentity == null)
        {
            return true;
        }
        return boundIdentity != null && boundIdentity.equals(targetIdentity);
    }

    /**
     * Result describing whether a failed replace left disk unchanged or already
     * committed the primary.
     */
    public synchronized ReplaceOutcome replaceStateDetailed(SavedState state)
    {
        if (state == null)
        {
            return ReplaceOutcome.failedUnchanged("state was null");
        }
        boolean ok = replaceState(state);
        if (ok)
        {
            CommitStage stage = readStage();
            if (stage == CommitStage.PRIMARY_COMMITTED)
            {
                return ReplaceOutcome.committedPrimaryOnly("Primary reset committed; backup align incomplete");
            }
            return ReplaceOutcome.committed("Reset committed to primary and backup");
        }
        // If primary already matches the intended revision, treat as committed
        // even when the method returned false due to a later-stage fault injected
        // after return-path confusion — inspect disk.
        try
        {
            if (Files.exists(stateFile))
            {
                SavedState onDisk = readState(stateFile);
                if (state.getRevision() > 0L && onDisk.getRevision() == state.getRevision())
                {
                    return ReplaceOutcome.committedPrimaryOnly(
                        "Primary already holds the reset revision after a partial failure");
                }
            }
        }
        catch (Exception ignored)
        {
            // Fall through to unchanged failure.
        }
        return ReplaceOutcome.failedUnchanged("Reset did not commit; previous disk state retained");
    }

    public static final class ReplaceOutcome
    {
        public enum Kind
        {
            COMMITTED,
            COMMITTED_PRIMARY_ONLY,
            FAILED_UNCHANGED
        }

        private final Kind kind;
        private final String message;

        private ReplaceOutcome(Kind kind, String message)
        {
            this.kind = kind;
            this.message = message;
        }

        public static ReplaceOutcome committed(String message)
        {
            return new ReplaceOutcome(Kind.COMMITTED, message);
        }

        public static ReplaceOutcome committedPrimaryOnly(String message)
        {
            return new ReplaceOutcome(Kind.COMMITTED_PRIMARY_ONLY, message);
        }

        public static ReplaceOutcome failedUnchanged(String message)
        {
            return new ReplaceOutcome(Kind.FAILED_UNCHANGED, message);
        }

        public Kind getKind()
        {
            return kind;
        }

        public String getMessage()
        {
            return message;
        }

        public boolean isCommitted()
        {
            return kind == Kind.COMMITTED || kind == Kind.COMMITTED_PRIMARY_ONLY;
        }
    }

    private void completeInterruptedCommitQuietly()
    {
        CommitStage stage = readStage();
        if (stage == CommitStage.NONE || stage == CommitStage.COMPLETE)
        {
            return;
        }
        try
        {
            if (stage == CommitStage.NEXT_READY && Files.exists(nextFile))
            {
                // Primary not replaced yet — next is aspirational; leave disk.
                return;
            }
            if (stage == CommitStage.PRIMARY_COMMITTED)
            {
                if (Files.exists(stateFile))
                {
                    try
                    {
                        Files.copy(stateFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                        clearStage();
                        Files.deleteIfExists(nextFile);
                    }
                    catch (IOException ex)
                    {
                        invalidateBackup("Interrupted commit: obsolete backup invalidated");
                    }
                }
                else if (Files.exists(nextFile))
                {
                    Files.copy(nextFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
                    try
                    {
                        Files.copy(stateFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                        clearStage();
                        Files.deleteIfExists(nextFile);
                    }
                    catch (IOException ex)
                    {
                        invalidateBackup("Interrupted commit: obsolete backup invalidated");
                    }
                }
            }
        }
        catch (IOException ex)
        {
            log.warn("Unable to finish interrupted GP Manager commit", ex);
        }
    }

    private void invalidateBackup(String reason) throws IOException
    {
        invalidateBackup(backupFile, scopeDirectory, reason);
    }

    private void invalidateBackup(Path backup, Path directory, String reason) throws IOException
    {
        if (Files.exists(backup))
        {
            Path tombstone = directory.resolve("sessions.backup.invalidated-" + System.currentTimeMillis() + ".json");
            Files.move(backup, tombstone, StandardCopyOption.REPLACE_EXISTING);
            log.warn("{} ({})", reason, tombstone.getFileName());
        }
        // An invalidated lineage takes its rotated copies with it; they hold the same obsolete data.
        deleteRotatedBackups(backup);
    }

    private static void deleteRotatedBackups(Path primaryBackup) throws IOException
    {
        for (Path candidate : backupCandidates(primaryBackup))
        {
            if (!candidate.equals(primaryBackup))
            {
                Files.deleteIfExists(candidate);
            }
        }
    }

    /** The newest readable revision across the backup and its rotated copies (pass 10 step 43). */
    private Optional<Long> peekBackupRevision(Path primaryBackup)
    {
        for (Path candidate : backupCandidates(primaryBackup))
        {
            Optional<Long> revision = peekRevision(candidate);
            if (revision.isPresent())
            {
                return revision;
            }
        }
        return Optional.empty();
    }

    private Optional<Long> peekRevision(Path path)
    {
        if (!Files.exists(path))
        {
            return Optional.empty();
        }
        try
        {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json == null || json.trim().isEmpty())
            {
                return Optional.empty();
            }
            JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
            if (obj.has("revision") && obj.get("revision").isJsonPrimitive())
            {
                return Optional.of(obj.get("revision").getAsLong());
            }
            return Optional.of(0L);
        }
        catch (Exception ex)
        {
            return Optional.empty();
        }
    }

    private void writeStage(CommitStage stage) throws IOException
    {
        writeStage(stageFile, stage);
    }

    private void writeStage(Path stagePath, CommitStage stage) throws IOException
    {
        Files.writeString(stagePath, stage.name(), StandardCharsets.UTF_8);
    }

    private void clearStage() throws IOException
    {
        clearStage(stageFile);
    }

    private void clearStage(Path stagePath) throws IOException
    {
        Files.deleteIfExists(stagePath);
    }

    private CommitStage readStage()
    {
        if (!Files.exists(stageFile))
        {
            return CommitStage.NONE;
        }
        try
        {
            String raw = Files.readString(stageFile, StandardCharsets.UTF_8).trim();
            return CommitStage.valueOf(raw);
        }
        catch (Exception ex)
        {
            return CommitStage.NONE;
        }
    }

    // Package visibility permits deterministic disk-failure tests without
    // introducing production fault switches.
    void replaceFile(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (java.nio.file.AtomicMoveNotSupportedException ex)
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private FileLock acquireWriteLock() throws IOException
    {
        return acquireWriteLock(lockFile);
    }

    private FileLock acquireWriteLock(Path lockPath) throws IOException
    {
        Files.createDirectories(lockPath.getParent());
        FileChannel channel = FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock();
        if (lock == null)
        {
            channel.close();
            return null;
        }
        exclusiveWriter = true;
        return lock;
    }

    private void releaseLock(FileLock lock)
    {
        if (lock == null)
        {
            return;
        }
        try
        {
            FileChannel channel = lock.channel();
            lock.release();
            channel.close();
        }
        catch (IOException ex)
        {
            log.debug("Unable to release GP Manager write lock", ex);
        }
        finally
        {
            exclusiveWriter = false;
        }
    }

    private void releaseWriterLock()
    {
        exclusiveWriter = false;
    }

    private void preserveCorruptState()
    {
        preserveCorruptState(stateFile, scopeDirectory);
    }

    private void preserveCorruptState(Path primary, Path directory)
    {
        try
        {
            if (Files.exists(primary))
            {
                Path backup = directory.resolve("sessions.corrupt-" + System.currentTimeMillis() + ".json");
                Files.move(primary, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException backupError)
        {
            log.warn("Unable to preserve corrupt GP Manager state", backupError);
        }
    }

    public synchronized SavedState detach(SavedState state)
    {
        if (state == null)
        {
            return new SavedState();
        }
        return gson.fromJson(gson.toJson(state), SavedState.class);
    }

    public boolean isExclusiveWriter()
    {
        return exclusiveWriter;
    }
}
