package com.gpmanager;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** The actual Windows failure from the soak: two immediate atomic renames can both be denied. */
public class AtomicSaveRetryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    static class Denials extends SessionRepository {
        int remaining, attempts;
        Denials(Path directory) { super(new Gson(), FilepathTestSupport.root(directory), false); }
        @Override void atomicMove(Filepath source, Filepath target) throws IOException {
            if (target.getFileName().toString().equals("sessions.json")) {
                attempts++;
                if (remaining > 0) {
                    remaining--;
                    throw new AccessDeniedException(source.toString(), target.toString(), "Injected Windows denial");
                }
            }
            super.atomicMove(source, target);
        }
    }

    static class Writer extends OrderedPersistenceWriter {
        final List<Long> waits = new CopyOnWriteArrayList<>();
        Writer(SessionRepository repository) { super(repository); resetRevision(repository.revision); }
        @Override boolean retryLater(long millis) {
            waits.add(millis);
            return super.retryLater(millis);
        }
        void close() throws InterruptedException {
            shutdown(TIMEOUT);
            if (executor != null) assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void deniedImmediateMovesRetryThroughTheProductionSchedulerAndCommitOnce() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new Denials(directory);
        PrimaryReadFailureTest.seed(repository);
        long base = repository.revision;
        repository.attempts = 0;
        repository.remaining = 2;
        var writer = new Writer(repository);
        try {
            assertTrue(writer.submit(new WriteIntent(null, repository.generation, base,
                    PrimaryReadFailureTest.money(4_650), null)));
            assertTrue("only a committed snapshot acknowledges durability", writer.flush(TIMEOUT));
            assertEquals(List.of(50L), writer.waits);
            assertEquals(3, repository.attempts);
            assertEquals(base + 1, writer.appliedRevision());
            var restored = new SessionRepository(new Gson(), FilepathTestSupport.root(directory), false).load();
            assertEquals(base + 1, restored.revision);
            assertEquals(4_650, restored.currentSession().metrics(20_000L).net);
            assertEquals(4_550, restored.currentSession().compactedTransactionCount);
        } finally { writer.close(); }
    }

    @Test public void persistentDenialExhaustsTheRealBoundWithoutAcknowledgingOrReplacingTheSave() throws Exception {
        Path directory = temporary.newFolder().toPath();
        var repository = new Denials(directory);
        PrimaryReadFailureTest.seed(repository);
        byte[] primary = Files.readAllBytes(directory.resolve("sessions.json"));
        long base = repository.revision;
        repository.attempts = 0;
        repository.remaining = 6;
        var writer = new Writer(repository);
        try {
            assertTrue(writer.submit(new WriteIntent(null, repository.generation, base,
                    PrimaryReadFailureTest.money(4_650), null)));
            assertFalse(writer.flush(TIMEOUT));
            assertEquals(List.of(50L, 200L), writer.waits);
            assertEquals("two rename calls per each of the three attempts", 6, repository.attempts);
            assertEquals(base, repository.revision);
            assertEquals(base, writer.appliedRevision());
            assertArrayEquals(primary, Files.readAllBytes(directory.resolve("sessions.json")));
            assertEquals(4_600, repository.load().currentSession().metrics(20_000L).net);
        } finally { writer.close(); }
    }
}
