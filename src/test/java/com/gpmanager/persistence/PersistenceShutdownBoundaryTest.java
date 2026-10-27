package com.gpmanager;

import com.google.gson.Gson;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Deterministic post-finalisation-check boundary, without a production race hook. */
public class PersistenceShutdownBoundaryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void completionThatAlreadyDeclinedFinalisationCannotLoseTheLastSnapshot() throws Exception {
        exercise(false);
    }
    @Test public void completionAndQueuedFinaliserStillTakeExactlyOneFinalSnapshot() throws Exception {
        exercise(true);
    }
    void exercise(boolean completionAlsoFinalises) throws Exception {
        var repository = new SessionRepository(new Gson(), FilepathTestSupport.root(temporary.newFolder().toPath()), true);
        var writer = new OrderedPersistenceWriter(repository);
        var engine = StoredChargeFixture.engine();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var snapshots = new AtomicInteger();
        var persistence = new Persistence(null, null, repository, writer, engine) {
            @Override SavedState snapshotState(SavedState header) {
                if (closing) snapshots.incrementAndGet();
                return super.snapshotState(header);
            }
            @Override void finishRequest(long generation, boolean ready) {
                // Model the real completion after it has read closing=false, before its
                // generation check. A shutdown fences that request while it is paused here.
                assertFalse(closing);
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                assertNotEquals(generation, request.get());
                if (completionAlsoFinalises) finalizeShutdown();
            }
        };
        try {
            var alice = new TrackingIdentity("rsprofile.shutdown-boundary", TrackingIdentity.ACCOUNT_HASH_INVALID);
            assertTrue(persistence.switchIdentity(alice, true));
            engine.ensureSession(StoredChargeFixture.NOW);
            engine.freePlay().rename("Before shutdown"); assertTrue(persistence.saveNow());
            persistence.switchIdentityAsync(alice, false);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            engine.freePlay().rename("Final paused snapshot");
            engine.pauseForLifecycle(StoredChargeFixture.NOW + 1000);
            persistence.shutdown(); release.countDown();
            assertTrue(persistence.worker.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(writer.flush(Duration.ofSeconds(5)));
            assertEquals("exactly one final snapshot", 1, snapshots.get());
            assertTrue(persistence.closed.get()); assertFalse(persistence.needsSnapshot);
            var saved = repository.load().currentSession();
            assertEquals("Final paused snapshot", saved.name()); assertTrue(saved.paused);
        } finally {
            release.countDown();
            if (persistence.worker != null) {
                persistence.worker.shutdown(); persistence.worker.awaitTermination(10, TimeUnit.SECONDS);
            }
            writer.shutdown(Duration.ofSeconds(5));
        }
    }
}
