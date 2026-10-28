package com.gpmanager;
import com.google.gson.Gson;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Deterministic scheduler: no sleeps, real files, game client, or wall-clock timing tolerance. */
public class RetryDelayProbeTest {
    @Test public void daemonSchedulerDefaultsMatchTheRemovedRedundantSetters() {
        var executor=(ScheduledThreadPoolExecutor) OrderedPersistenceWriter.daemonExecutor("gp-manager-offline-probe");
        try {
            assertTrue(executor.getExecuteExistingDelayedTasksAfterShutdownPolicy());
            assertFalse(executor.getContinueExistingPeriodicTasksAfterShutdownPolicy());
            assertTrue(executor.getThreadFactory().newThread(() -> {}).isDaemon());
        } finally { executor.shutdown(); }
    }
    static class Scheduler extends ScheduledThreadPoolExecutor {
        final List<Runnable> delayed = new ArrayList<>();
        Scheduler() { super(1); }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public ScheduledFuture<?> schedule(Runnable task,long delay,TimeUnit unit) {
            assertTrue(delay > 0); delayed.add(task); return null;
        }
        void advance() { delayed.remove(0).run(); }
    }
    static class Repository extends SessionRepository {
        int calls;
        Repository() { super(new Gson()); }
        @Override synchronized boolean save(WriteIntent intent) {
            calls++;
            if(calls == 1) return false;
            revision = intent.state.revision;
            return true;
        }
    }
    static WriteIntent intent(Repository repo,long revision) {
        var state=new SavedState();state.setRevision(revision);
        return new WriteIntent(new TrackingIdentity("rsprofile.probe",-1),repo.generation,0,state,null);
    }
    @Test public void flushDoesNotRunThePendingRetryAheadOfItsScheduledDelay() {
        var repo=new Repository();var executor=new Scheduler();var writer=new OrderedPersistenceWriter(repo,executor);
        try {
            assertTrue(writer.submit(intent(repo,1)));assertEquals(1,repo.calls);
            assertFalse(writer.flush(Duration.ZERO));
            assertEquals("flush must not bypass the scheduled retry",1,repo.calls);
            executor.advance();assertEquals(2,repo.calls);assertTrue(writer.flush(Duration.ZERO));
        } finally { executor.shutdown(); }
    }
    @Test public void queuedRetryCannotStartAfterTheShutdownRetryWindowExpired() {
        var repo=new Repository();var executor=new Scheduler();var writer=new OrderedPersistenceWriter(repo,executor);
        try {
            assertTrue(writer.submit(intent(repo,1)));
            writer.shutdown(Duration.ZERO);
            assertEquals("shutdown must not force the delayed retry early",1,repo.calls);
            executor.advance();
            assertEquals("an expired window cannot start another attempt",1,repo.calls);
            assertFalse(writer.flush(Duration.ZERO));
            assertSame(writer.merged.get(),writer.stuck);
        } finally { executor.shutdown(); }
    }
    @Test public void aNewSnapshotCoalescesWhileTheExistingDelayRemainsInForce() {
        var repo=new Repository();var executor=new Scheduler();var writer=new OrderedPersistenceWriter(repo,executor);
        try {
            assertTrue(writer.submit(intent(repo,1)));assertTrue(writer.submit(intent(repo,2)));
            assertEquals("coalescing does not bypass the existing scheduled delay",1,repo.calls);
            executor.advance();assertEquals(2,repo.calls);assertEquals(2,repo.revision);
            assertTrue(writer.flush(Duration.ZERO));
        } finally { executor.shutdown(); }
    }
}
