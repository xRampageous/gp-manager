package com.gpmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** A readable older crash copy must never masquerade as the current temporarily unreadable save. */
public class PrimaryReadFailureTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    static SavedState money(long value) {
        var session = new Session("Read fence", 1_000L, SessionMode.GENERAL);
        for (int index = 0; index < value; index++)
            session.addTransaction(Tx.of(2_000L + index, TransactionType.GAIN, Context.GENERIC,
                    "Gain", true, Collections.singletonList(new Flow(995, "Coins", 1, 1, 1))), 100, false);
        return new SavedState(session, null, Collections.emptyList());
    }

    static void seed(SessionRepository repository) {
        assertTrue(PersistenceProbe.save(repository, money(4_550)));
        assertTrue(PersistenceProbe.save(repository, money(4_600)));
    }

    static class ReadFailures extends SessionRepository {
        int remaining, primaryReads, backupReads;
        boolean generic;
        Filepath failedPrimary;
        ReadFailures(Path directory, boolean accounts) {
            super(new Gson(), FilepathTestSupport.root(directory), accounts);
        }
        @Override SavedState readState(Filepath path) throws IOException {
            String name = path.getFileName().toString();
            if (name.equals("sessions.json")) {
                primaryReads++;
                if (remaining > 0 && (failedPrimary == null || failedPrimary.equals(path))) {
                    remaining--;
                    if (generic) throw new IOException("Injected read I/O failure");
                    throw new AccessDeniedException(path.toString());
                }
            }
            if (name.equals("sessions.backup.json")) backupReads++;
            return super.readState(path);
        }
    }

    /** Injects unavailability at the authoritative save-header read, per file, plus failed preservation. */
    static class HeaderFailures extends SessionRepository {
        boolean fail, denyBackup, failPreservation;
        int remaining, denyBackupReads, primaryHeaders, deniedBackupHeaders;
        HeaderFailures(Path directory) {
            super(new Gson(), FilepathTestSupport.root(directory), false);
        }
        @Override JsonObject header(Filepath path) throws IOException {
            String name = path.getFileName().toString();
            if (name.equals("sessions.backup.json") && (denyBackup || denyBackupReads > 0)) {
                if (denyBackupReads > 0) denyBackupReads--;
                deniedBackupHeaders++;
                throw new AccessDeniedException(path.toString());
            }
            if (name.equals("sessions.json")) {
                primaryHeaders++;
                if (fail || remaining > 0) {
                    if (remaining > 0) remaining--;
                    throw new AccessDeniedException(path.toString());
                }
            }
            return super.header(path);
        }
        @Override void setAside(ScopeFiles files) {
            if (failPreservation) return;
            super.setAside(files);
        }
    }

    static int corruptCopies(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return (int) files.filter(path -> path.getFileName().toString().startsWith("sessions.corrupt-")).count();
        }
    }

    @Test public void anUnavailableHeaderCannotLetAStaleWriterOverwriteANewerCommit() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var current = new HeaderFailures(directory);
        assertTrue(PersistenceProbe.save(current, money(4_550)));
        var stale = new HeaderFailures(directory);
        long observed = stale.load().revision;
        assertEquals(1, observed);
        assertTrue(PersistenceProbe.save(current, money(4_600)));
        byte[] newer = Files.readAllBytes(directory.resolve("sessions.json"));
        stale.fail = true;
        var refused = stale.commit(new WriteIntent(null, stale.generation, observed, money(4_575), null), false);
        assertEquals("an unavailable header is a retryable refusal", SessionRepository.ReplaceOutcome.Kind.FAILED_UNCHANGED, refused.kind);
        assertTrue("the header fault was exercised", stale.primaryHeaders > 0);
        assertArrayEquals("the newer primary stays byte-identical", newer,
                Files.readAllBytes(directory.resolve("sessions.json")));
        assertEquals(4_600, FixtureReads.currentSession(new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                .load()).metrics(20_000L).net);
        stale.fail = false;
        var conflict = stale.commit(new WriteIntent(null, stale.generation, observed, money(4_575), null), false);
        assertEquals("an actual competing commit still conflicts", SessionRepository.ReplaceOutcome.Kind.CONFLICT, conflict.kind);
        assertEquals(4_600, FixtureReads.currentSession(new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                .load()).metrics(20_000L).net);
    }

    @Test public void anUnavailableHeaderCannotReplaceANewerSchemaPrimary() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new HeaderFailures(directory);
        seed(repository);
        var payload = new com.google.gson.JsonParser().parse(Files.readString(directory.resolve("sessions.json")))
                .getAsJsonObject();
        payload.addProperty("schemaVersion", 111);
        Files.writeString(directory.resolve("sessions.json"), new Gson().toJson(payload));
        byte[] foreign = Files.readAllBytes(directory.resolve("sessions.json"));
        repository.fail = true;
        var refused = repository.commit(new WriteIntent(null, repository.generation, repository.revision,
                money(4_650), null), false);
        assertEquals(SessionRepository.ReplaceOutcome.Kind.FAILED_UNCHANGED, refused.kind);
        var reset = repository.replaceStateDetailed(new WriteIntent(null, repository.generation,
                repository.revision, money(1), null));
        assertEquals("a destructive reset cannot bypass the guard either",
                SessionRepository.ReplaceOutcome.Kind.FAILED_UNCHANGED, reset.kind);
        assertTrue("the header fault was exercised", repository.primaryHeaders > 0);
        assertArrayEquals("the newer-schema primary is untouched", foreign,
                Files.readAllBytes(directory.resolve("sessions.json")));
        repository.fail = false;
        var readable = repository.commit(new WriteIntent(null, repository.generation, repository.revision,
                money(4_650), null), false);
        assertEquals("a readable newer schema stays read-only", SessionRepository.ReplaceOutcome.Kind.FAILED_UNCHANGED, readable.kind);
        assertArrayEquals(foreign, Files.readAllBytes(directory.resolve("sessions.json")));
        assertEquals(111, new com.google.gson.JsonParser().parse(Files.readString(directory.resolve("sessions.json")))
                .getAsJsonObject().get("schemaVersion").getAsInt());
    }

    @Test public void aTransientlyUnavailableHeaderIsRetriedNotConflicted() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new HeaderFailures(directory);
        seed(repository);
        long base = repository.revision;
        var writer = new OrderedPersistenceWriter(repository);
        writer.resetRevision(base);
        repository.remaining = 2;
        try {
            assertTrue(writer.submit(new WriteIntent(null, repository.generation, base, money(4_650), null)));
            assertTrue("the bounded retry clears a transient header refusal", writer.flush(Duration.ofSeconds(5)));
            assertEquals(base + 1, writer.appliedRevision());
            assertTrue("the header fault was exercised", repository.primaryHeaders >= 2);
            assertEquals(4_650, FixtureReads.currentSession(new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                    .load()).metrics(20_000L).net);
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            if (writer.executor != null) assertTrue(writer.executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test public void aPersistentlyUnavailableHeaderFailsWithoutPermanentConflict() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new HeaderFailures(directory);
        seed(repository);
        long base = repository.revision;
        byte[] primary = Files.readAllBytes(directory.resolve("sessions.json"));
        var writer = new OrderedPersistenceWriter(repository);
        writer.resetRevision(base);
        repository.fail = true;
        try {
            assertTrue(writer.submit(new WriteIntent(null, repository.generation, base, money(4_650), null)));
            assertFalse(writer.flush(Duration.ofSeconds(5)));
            assertFalse("one exhausted retry batch stays silent, as designed", writer.status().isFailure());
            assertFalse("an unavailable header is not a competing commit", writer.conflicted(repository.generation));
            assertArrayEquals(primary, Files.readAllBytes(directory.resolve("sessions.json")));
            assertEquals(base, new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                    .load().revision);
            repository.fail = false;
            assertTrue("a later snapshot is accepted once the header is readable",
                    writer.submit(new WriteIntent(null, repository.generation, base, money(4_650), null)));
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals(4_650, FixtureReads.currentSession(new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                    .load()).metrics(20_000L).net);
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            if (writer.executor != null) assertTrue(writer.executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    /** Damages an optional body field while the readable schema/revision header stays intact. */
    static byte[] damageBody(Path directory) throws Exception {
        var payload = new com.google.gson.JsonParser().parse(Files.readString(directory.resolve("sessions.json")))
                .getAsJsonObject();
        payload.addProperty("savedSessions", "damaged optional list");
        String json = new Gson().toJson(payload);
        Files.writeString(directory.resolve("sessions.json"), json);
        return Files.readAllBytes(directory.resolve("sessions.json"));
    }

    @Test public void aDamagedBodyCannotDiscardTheReadableNewerRevision() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var current = new HeaderFailures(directory);
        assertTrue(PersistenceProbe.save(current, money(4_550)));
        var stale = new HeaderFailures(directory);
        long base = stale.load().revision;
        assertEquals(1, base);
        assertTrue(PersistenceProbe.save(current, money(4_600)));
        byte[] damaged = damageBody(directory);
        byte[] backup = Files.readAllBytes(directory.resolve("sessions.backup.json"));
        var refused = stale.commit(new WriteIntent(null, stale.generation, base, money(4_575), null), false);
        assertEquals("the readable revision 2 header is authoritative CAS evidence",
                SessionRepository.ReplaceOutcome.Kind.CONFLICT, refused.kind);
        assertTrue("the fault was exercised", stale.primaryHeaders > 0);
        assertEquals(2, stale.revision);
        assertArrayEquals("nothing was moved or replaced", damaged,
                Files.readAllBytes(directory.resolve("sessions.json")));
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve("sessions.backup.json")));
        assertEquals("no recovery ran for a foreign lineage", 0, corruptCopies(directory));
        var recovered = stale.commit(new WriteIntent(null, stale.generation, 2, money(4_650), null), false);
        assertTrue("the matching lineage still recovers", recovered.isCommitted());
        assertEquals("the revision advanced exactly once", 3, stale.revision);
        assertEquals(1, corruptCopies(directory));
        assertArrayEquals("the damaged primary is preserved as evidence", damaged,
                Files.readAllBytes(Files.list(directory).filter(path ->
                        path.getFileName().toString().startsWith("sessions.corrupt-")).findFirst().orElseThrow()));
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve("sessions.backup.json")));
        var restored = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false).load();
        assertEquals(3, restored.revision);
        assertEquals(4_650, FixtureReads.currentSession(restored).metrics(20_000L).net);
    }

    @Test public void aResetCannotBypassADamagedBodyNewerRevision() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var current = new HeaderFailures(directory);
        assertTrue(PersistenceProbe.save(current, money(4_550)));
        var stale = new HeaderFailures(directory);
        assertEquals(1, stale.load().revision);
        assertTrue(PersistenceProbe.save(current, money(4_600)));
        byte[] damaged = damageBody(directory);
        byte[] backup = Files.readAllBytes(directory.resolve("sessions.backup.json"));
        var refused = stale.replaceStateDetailed(new WriteIntent(null, stale.generation, 1, money(1), null));
        assertEquals("a destructive reset obeys the readable newer revision",
                SessionRepository.ReplaceOutcome.Kind.CONFLICT, refused.kind);
        assertArrayEquals(damaged, Files.readAllBytes(directory.resolve("sessions.json")));
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve("sessions.backup.json")));
        assertEquals(0, corruptCopies(directory));
        var recovered = stale.replaceStateDetailed(new WriteIntent(null, stale.generation, 2, money(1), null));
        assertTrue("a matching-base reset recovers and preserves evidence", recovered.isCommitted());
        assertEquals(1, corruptCopies(directory));
        assertEquals(3, new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                .load().revision);
    }

    @Test public void aForeignSchemaStaysProtectedEvenWhenItsBodyCannotBeRead() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new HeaderFailures(directory);
        seed(repository);
        var payload = new com.google.gson.JsonParser().parse(Files.readString(directory.resolve("sessions.json")))
                .getAsJsonObject();
        payload.addProperty("schemaVersion", 111);
        payload.addProperty("savedSessions", "undecodable body");
        Files.writeString(directory.resolve("sessions.json"), new Gson().toJson(payload));
        byte[] foreign = Files.readAllBytes(directory.resolve("sessions.json"));
        var refused = repository.commit(new WriteIntent(null, repository.generation, repository.revision,
                money(4_650), null), false);
        assertEquals(SessionRepository.ReplaceOutcome.Kind.FAILED_UNCHANGED, refused.kind);
        assertArrayEquals("a foreign schema is never deserialized or moved", foreign,
                Files.readAllBytes(directory.resolve("sessions.json")));
        assertEquals(0, corruptCopies(directory));
    }

    @Test public void aTransientlyUnavailableBackupHeaderIsRetriedNotConflicted() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new HeaderFailures(directory);
        seed(repository);
        Files.delete(directory.resolve("sessions.json"));
        long base = repository.load().revision;
        assertEquals(1, base);
        var writer = new OrderedPersistenceWriter(repository);
        writer.resetRevision(base);
        repository.denyBackupReads = 2;
        try {
            assertTrue(writer.submit(new WriteIntent(null, repository.generation, base, money(4_650), null)));
            assertTrue("the bounded retry clears a transient backup-header refusal",
                    writer.flush(Duration.ofSeconds(5)));
            assertTrue("the backup fault was exercised", repository.deniedBackupHeaders > 0);
            assertEquals(base + 1, writer.appliedRevision());
            assertEquals(4_650, FixtureReads.currentSession(new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                    .load()).metrics(20_000L).net);
            assertEquals("a missing primary creates no corrupt copy", 0, corruptCopies(directory));
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            if (writer.executor != null) assertTrue(writer.executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test public void aPersistentlyUnavailableBackupHeaderNeverConflicts() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new HeaderFailures(directory);
        seed(repository);
        Files.delete(directory.resolve("sessions.json"));
        long base = repository.load().revision;
        byte[] backup = Files.readAllBytes(directory.resolve("sessions.backup.json"));
        var writer = new OrderedPersistenceWriter(repository);
        writer.resetRevision(base);
        repository.denyBackup = true;
        try {
            assertTrue(writer.submit(new WriteIntent(null, repository.generation, base, money(4_650), null)));
            assertFalse(writer.flush(Duration.ofSeconds(5)));
            assertTrue("the backup fault was exercised", repository.deniedBackupHeaders > 0);
            assertFalse("one exhausted retry batch stays silent", writer.status().isFailure());
            assertFalse("an unavailable backup is not a competing commit", writer.conflicted(repository.generation));
            assertEquals("the known revision is preserved, never substituted with zero", base, repository.revision);
            assertArrayEquals(backup, Files.readAllBytes(directory.resolve("sessions.backup.json")));
            assertFalse(Files.exists(directory.resolve("sessions.json")));
            assertEquals(base, new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                    .load().revision);
            repository.denyBackup = false;
            assertTrue("a later snapshot is accepted once the backup is readable",
                    writer.submit(new WriteIntent(null, repository.generation, base, money(4_650), null)));
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals(4_650, FixtureReads.currentSession(new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                    .load()).metrics(20_000L).net);
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            if (writer.executor != null) assertTrue(writer.executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test public void corruptPrimaryRecoverySurvivesBackupUnavailability() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new HeaderFailures(directory);
        seed(repository);
        Files.writeString(directory.resolve("sessions.json"), "{broken primary");
        byte[] damaged = Files.readAllBytes(directory.resolve("sessions.json"));
        long base = repository.load().revision;
        assertEquals("load recovered the backup and preserved the corrupt primary", 1, base);
        assertEquals(1, corruptCopies(directory));
        var writer = new OrderedPersistenceWriter(repository);
        writer.resetRevision(base);
        repository.denyBackupReads = 2;
        try {
            assertTrue(writer.submit(new WriteIntent(null, repository.generation, base, money(4_650), null)));
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertTrue("the backup fault was exercised", repository.deniedBackupHeaders > 0);
            assertEquals("the crash copy from load survived", 1, corruptCopies(directory));
            assertEquals(4_650, FixtureReads.currentSession(new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                    .load()).metrics(20_000L).net);
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            if (writer.executor != null) assertTrue(writer.executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test public void theStressHelperRefusesWhenTheBackupHeaderIsUnavailable() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new HeaderFailures(directory);
        seed(repository);
        Files.delete(directory.resolve("sessions.json"));
        long base = repository.load().revision;
        byte[] backup = Files.readAllBytes(directory.resolve("sessions.backup.json"));
        repository.denyBackup = true;
        assertThrows(UncheckedIOException.class,
                () -> PersistenceProbe.saveThroughWriter(repository, money(4_650)));
        assertTrue("the backup fault was exercised", repository.deniedBackupHeaders > 0);
        assertEquals("no stale or default revision was reused", base, repository.revision);
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve("sessions.backup.json")));
        assertFalse(Files.exists(directory.resolve("sessions.json")));
        repository.denyBackup = false;
        assertTrue(PersistenceProbe.saveThroughWriter(repository, money(4_650)));
        var restored = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false).load();
        assertEquals("a successful helper save advances exactly once", base + 1, restored.revision);
        assertEquals(4_650, FixtureReads.currentSession(restored).metrics(20_000L).net);
    }

    @Test public void aPresentUnreadablePrimaryIsAlwaysPreservedBeforeReplacement() throws Exception {
        for (String body : new String[] {"", "null", "[1,2]", "{broken primary"}) {
            Path directory = temporary.newFolder().toPath();
            var repository = new HeaderFailures(directory);
            seed(repository);
            Files.writeString(directory.resolve("sessions.json"), body);
            byte[] damaged = Files.readAllBytes(directory.resolve("sessions.json"));
            byte[] backup = Files.readAllBytes(directory.resolve("sessions.backup.json"));
            var outcome = repository.commit(new WriteIntent(null, repository.generation, 1, money(4_650), null), false);
            assertTrue("recovery commits for the matching backup lineage: " + body, outcome.isCommitted());
            assertArrayEquals("the original bytes are preserved as evidence: " + body, damaged,
                    Files.readAllBytes(Files.list(directory).filter(path ->
                            path.getFileName().toString().startsWith("sessions.corrupt-")).findFirst().orElseThrow()));
            assertArrayEquals("the backup is untouched: " + body, backup,
                    Files.readAllBytes(directory.resolve("sessions.backup.json")));
            var restored = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false).load();
            assertEquals(2, restored.revision);
            assertEquals(4_650, FixtureReads.currentSession(restored).metrics(20_000L).net);
        }
    }

    @Test public void aFailedPreservationRefusesWithoutReplacing() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new HeaderFailures(directory);
        seed(repository);
        Files.writeString(directory.resolve("sessions.json"), "{broken primary");
        byte[] damaged = Files.readAllBytes(directory.resolve("sessions.json"));
        byte[] backup = Files.readAllBytes(directory.resolve("sessions.backup.json"));
        repository.failPreservation = true;
        var refused = repository.commit(new WriteIntent(null, repository.generation, 1, money(4_650), null), false);
        assertEquals("an unverified preservation refuses the whole save",
                SessionRepository.ReplaceOutcome.Kind.FAILED_UNCHANGED, refused.kind);
        assertArrayEquals(damaged, Files.readAllBytes(directory.resolve("sessions.json")));
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve("sessions.backup.json")));
        assertEquals("no evidence copy claimed a preservation that did not happen", 0, corruptCopies(directory));
        assertEquals("disk truth is still the recoverable backup", 1,
                new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false).load().revision);
        repository.failPreservation = false;
        assertTrue(repository.commit(new WriteIntent(null, repository.generation, 1, money(4_650), null), false)
                .isCommitted());
        assertEquals(1, corruptCopies(directory));
        assertEquals(4_650, FixtureReads.currentSession(new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                .load()).metrics(20_000L).net);
    }

    @Test public void theStressHelperPreservesAGoodPrimaryOnReadIo() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new ReadFailures(directory, false);
        seed(repository);
        long base = repository.revision;
        byte[] primary = Files.readAllBytes(directory.resolve("sessions.json"));
        repository.generic = true;
        repository.remaining = 1;
        assertThrows(UncheckedIOException.class,
                () -> PersistenceProbe.saveThroughWriter(repository, money(4_650)));
        assertArrayEquals("the valid primary is preserved", primary,
                Files.readAllBytes(directory.resolve("sessions.json")));
        try (var files = Files.list(directory)) {
            assertFalse("read I/O is never healed as corrupt JSON",
                    files.anyMatch(path -> path.getFileName().toString().startsWith("sessions.corrupt-")));
        }
        assertEquals(base, new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false)
                .load().revision);
        repository.generic = false;
        assertTrue(PersistenceProbe.saveThroughWriter(repository, money(4_650)));
        var restored = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false).load();
        assertEquals("a successful helper save advances exactly once", base + 1, restored.revision);
        assertEquals(4_650, FixtureReads.currentSession(restored).metrics(20_000L).net);
    }

    @Test public void twoDeniedReadsCannotRollBackTheLastFiftyCompactedGains() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new ReadFailures(directory, false);
        seed(repository);
        byte[] primary = Files.readAllBytes(directory.resolve("sessions.json"));
        byte[] backup = Files.readAllBytes(directory.resolve("sessions.backup.json"));
        repository.primaryReads = 0;
        repository.remaining = 2;
        assertThrows(UncheckedIOException.class, repository::load);
        assertEquals("bounded immediate retry", 2, repository.primaryReads);
        assertEquals("the older backup was not selected", 0, repository.backupReads);
        assertArrayEquals(primary, Files.readAllBytes(directory.resolve("sessions.json")));
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve("sessions.backup.json")));
        assertEquals("no recovery notice for a good primary", "", repository.recovery);
        assertEquals(4_600, FixtureReads.currentSession(repository.load()).metrics(20_000L).net);
        assertEquals(4_500, FixtureReads.currentSession(repository.load()).compactedTransactionCount);
    }

    @Test public void oneDeniedReadStillRetriesAndLoadsTheCurrentSave() throws Exception {
        var repository = new ReadFailures(temporary.newFolder().toPath(), false);
        seed(repository);
        repository.remaining = 1;
        assertEquals(4_600, FixtureReads.currentSession(repository.load()).metrics(20_000L).net);
        assertEquals(0, repository.backupReads);
        assertEquals("", repository.recovery);
    }

    @Test public void ordinaryReadIoCannotBeClassifiedAsCorruptJson() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new ReadFailures(directory, false);
        seed(repository);
        byte[] primary = Files.readAllBytes(directory.resolve("sessions.json"));
        repository.generic = true;
        repository.remaining = 1;
        assertThrows(UncheckedIOException.class, repository::load);
        assertArrayEquals(primary, Files.readAllBytes(directory.resolve("sessions.json")));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("sessions.corrupt-")));
        }
        assertEquals(4_600, FixtureReads.currentSession(repository.load()).metrics(20_000L).net);
    }

    @Test public void jsonReadersIoCauseIsPreservedInsteadOfRecoveringAnOlderBackup() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var root = FilepathTestSupport.root(directory);
        seed(new SessionRepository(new Gson(), root, false));
        Gson codec = new GsonBuilder().registerTypeAdapter(SavedState.class,
                (JsonDeserializer<SavedState>) (json, type, context) -> {
                    throw new JsonIOException(new IOException("Injected streaming read failure"));
                }).create();
        var repository = new SessionRepository(codec, root, false);
        var failure = assertThrows(UncheckedIOException.class, repository::load);
        assertEquals("Injected streaming read failure", failure.getCause().getMessage());
        assertEquals(4_600, FixtureReads.currentSession(new SessionRepository(new Gson(), root, false).load()).metrics(20_000L).net);
    }

    @Test public void aReadFailureWhileSavingCannotMoveTheGoodPrimaryOrOverwriteIt() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new ReadFailures(directory, false);
        seed(repository);
        byte[] primary = Files.readAllBytes(directory.resolve("sessions.json"));
        byte[] backup = Files.readAllBytes(directory.resolve("sessions.backup.json"));
        long base = repository.revision;
        repository.generic = true;
        repository.remaining = 1;
        assertFalse(repository.save(new WriteIntent(null, repository.generation, base, money(4_650), null)));
        assertEquals(base, repository.revision);
        assertArrayEquals(primary, Files.readAllBytes(directory.resolve("sessions.json")));
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve("sessions.backup.json")));
        assertTrue(repository.save(new WriteIntent(null, repository.generation, base, money(4_650), null)));
        assertEquals(4_650, FixtureReads.currentSession(repository.load()).metrics(20_000L).net);
    }

    @Test public void anUnreadableNewAccountStaysFencedUntilItsCurrentSaveCanLoad() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var alice = new TrackingIdentity("rsprofile.read-a", 11);
        var bob = new TrackingIdentity("rsprofile.read-b", 22);
        var repository = new ReadFailures(directory, true);
        repository.bindIdentity(bob);
        seed(repository);
        var engine = TargetFreshStartTest.engine();
        var writer = new OrderedPersistenceWriter(repository);
        var disk = new Persistence(null, null, repository, writer, engine);
        JsonCodec.bind(repository.gson);
        try {
            assertTrue(disk.switchIdentity(alice, true));
            engine.ensureSession(20_000L);
            TargetFreshStartTest.coins(engine, 20_000L, 123);
            assertTrue(disk.saveNow());
            repository.failedPrimary = repository.scopeDir(bob).joinSegment("sessions.json");
            repository.remaining = 2;
            assertFalse(disk.switchIdentity(bob, true));
            assertEquals(alice, disk.activeIdentity);
            assertEquals(123, engine.metrics(20_000L).net);
            assertTrue("a failed load is visible", disk.saveStatus().isFailure());
            assertTrue(disk.switchIdentity(bob, true));
            assertEquals(bob, disk.activeIdentity);
            assertEquals(4_600, engine.metrics(20_000L).net);
            assertFalse(disk.saveStatus().isFailure());
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            if (disk.worker != null) disk.worker.shutdown();
        }
    }

    @Test public void anUnavailableBackupCannotStartAnEmptyAccount() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var root = FilepathTestSupport.root(directory);
        seed(new SessionRepository(new Gson(), root, false));
        Files.delete(directory.resolve("sessions.json"));
        byte[] backup = Files.readAllBytes(directory.resolve("sessions.backup.json"));
        var repository = new SessionRepository(new Gson(), root, false) {
            @Override SavedState readState(Filepath path) throws IOException {
                if (path.getFileName().toString().equals("sessions.backup.json"))
                    throw new AccessDeniedException(path.toString());
                return super.readState(path);
            }
        };
        assertThrows(UncheckedIOException.class, repository::load);
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve("sessions.backup.json")));
        assertEquals(4_550, FixtureReads.currentSession(new SessionRepository(new Gson(), root, false).load()).metrics(20_000L).net);
    }
}
