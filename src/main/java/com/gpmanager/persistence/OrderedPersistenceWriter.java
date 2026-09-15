package com.gpmanager.persistence;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One ordered background writer for ordinary saves.
 *
 * <p>Each queued write carries an immutable {@link WriteIntent} so account
 * switches cannot redirect pending work. The executor is restartable across
 * plugin disable/enable cycles.
 */
@Singleton
public class OrderedPersistenceWriter
{
    private static final Logger log = LoggerFactory.getLogger(OrderedPersistenceWriter.class);
    private static final Duration DEFAULT_SHUTDOWN_FLUSH = Duration.ofSeconds(5);

    private final SessionRepository repository;
    private final Object executorLock = new Object();
    private ExecutorService executor;
    private final boolean externalExecutor;
    private final AtomicLong appliedRevision = new AtomicLong(0L);
    private final AtomicReference<PendingWrite> coalesced = new AtomicReference<>();
    private final AtomicReference<SaveStatus> status = new AtomicReference<>(SaveStatus.neverSaved());
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);
    private final Object flushMonitor = new Object();
    private final AtomicReference<WriteIntent> lastFailedIntent = new AtomicReference<>();
    private volatile boolean acceptingWork = true;

    @Inject
    public OrderedPersistenceWriter(SessionRepository repository)
    {
        this.repository = repository;
        this.externalExecutor = false;
        this.executor = newExecutor();
    }

    /** Visible for tests that supply a same-thread or controlled executor. */
    OrderedPersistenceWriter(SessionRepository repository, ExecutorService executor)
    {
        this.repository = repository;
        this.externalExecutor = true;
        this.executor = executor;
    }

    public void start()
    {
        acceptingWork = true;
        synchronized (executorLock)
        {
            if (externalExecutor)
            {
                return;
            }
            if (executor == null || executor.isShutdown())
            {
                executor = newExecutor();
            }
        }
    }

    private static ExecutorService newExecutor()
    {
        return Executors.newSingleThreadExecutor(new ThreadFactory()
        {
            @Override
            public Thread newThread(Runnable runnable)
            {
                Thread thread = new Thread(runnable, "gp-manager-persist");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public SaveStatus getStatus()
    {
        return status.get();
    }

    public long getAppliedRevision()
    {
        return appliedRevision.get();
    }

    public void noteLoadedRevision(long revision)
    {
        appliedRevision.updateAndGet(current -> Math.max(current, revision));
    }

    public void resetAppliedRevision(long revision)
    {
        appliedRevision.set(Math.max(0L, revision));
    }

    /**
     * Enqueue an ordinary save bound to an immutable write intent.
     */
    public void submit(WriteIntent intent)
    {
        if (intent == null || intent.getState() == null)
        {
            return;
        }
        if (!acceptingWork)
        {
            status.set(new SaveStatus(
                SaveStatus.State.FAILED,
                intent.getState().getRevision(),
                status.get().getLastSuccessEpochMillis(),
                0L,
                "Writer is shut down",
                true));
            lastFailedIntent.set(intent);
            return;
        }
        SavedState detached = intent.getState();
        long revision = detached.getRevision();
        if (revision <= 0L)
        {
            revision = intent.getExpectedBaseRevision() + 1L;
            detached.setRevision(revision);
        }
        PendingWrite next = new PendingWrite(intent);
        while (true)
        {
            PendingWrite current = coalesced.get();
            if (current != null)
            {
                // Prefer newer revision within the same scope generation.
                if (current.intent.getScopeGeneration() == next.intent.getScopeGeneration()
                    && current.revision > next.revision)
                {
                    return;
                }
                // A newer scope generation supersedes older pending work for a
                // previous account — but only after the coordinator drained or
                // abandoned that scope. Drop stale-scope work if a newer scope
                // is already queued.
                if (current.intent.getScopeGeneration() > next.intent.getScopeGeneration())
                {
                    status.set(new SaveStatus(
                        SaveStatus.State.FAILED,
                        next.revision,
                        status.get().getLastSuccessEpochMillis(),
                        0L,
                        "Pending save abandoned after account scope changed",
                        false));
                    return;
                }
            }
            if (coalesced.compareAndSet(current, next))
            {
                break;
            }
        }
        scheduleDrain();
    }

    /** @deprecated prefer {@link #submit(WriteIntent)} */
    @Deprecated
    public void submit(SavedState detached)
    {
        if (detached == null)
        {
            return;
        }
        TrackingIdentity identity = repository.getBoundIdentity();
        WriteIntent intent = identity == null
            ? WriteIntent.unbound(
                repository.getScopeGeneration(),
                repository.getLastKnownDiskRevision(),
                detached)
            : new WriteIntent(
                identity,
                repository.getScopeGeneration(),
                repository.getLastKnownDiskRevision(),
                detached);
        submit(intent);
    }

    public boolean retryLastFailure()
    {
        WriteIntent failed = lastFailedIntent.get();
        if (failed == null)
        {
            return false;
        }
        SavedState copy = repository.detach(failed.getState());
        copy.setSavedAtEpochMillis(System.currentTimeMillis());
        long retryBase = failed.getExpectedBaseRevision();
        // Safe retry onto our own verified lineage only.
        long own = appliedRevision.get();
        long disk = repository.getLastKnownDiskRevision();
        if (own > 0L
            && disk == own
            && failed.getScopeGeneration() == repository.getScopeGeneration()
            && retryBase < own)
        {
            retryBase = own;
        }
        WriteIntent retry = new WriteIntent(
            failed.getIdentity(),
            failed.getScopeGeneration(),
            retryBase,
            copy);
        submit(retry);
        return true;
    }

    public SessionRepository.ReplaceOutcome replaceNow(WriteIntent intent)
    {
        flush(DEFAULT_SHUTDOWN_FLUSH);
        SavedState detached = intent.getState();
        long revision = Math.max(intent.getExpectedBaseRevision(), appliedRevision.get()) + 1L;
        detached.setRevision(revision);
        detached.setSavedAtEpochMillis(System.currentTimeMillis());
        WriteIntent advancing = new WriteIntent(
            intent.getIdentity(),
            intent.getScopeGeneration(),
            intent.getExpectedBaseRevision(),
            detached);
        long started = System.nanoTime();
        boolean ok = repository.replaceState(advancing);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (ok)
        {
            appliedRevision.set(revision);
            lastFailedIntent.set(null);
            status.set(new SaveStatus(
                SaveStatus.State.OK,
                revision,
                System.currentTimeMillis(),
                durationMs,
                "Reset committed",
                false));
            return SessionRepository.ReplaceOutcome.committed("Reset committed");
        }
        lastFailedIntent.set(advancing);
        SessionRepository.ReplaceOutcome outcome = repository.replaceStateDetailed(advancing.getState());
        status.set(new SaveStatus(
            SaveStatus.State.FAILED,
            revision,
            status.get().getLastSuccessEpochMillis(),
            durationMs,
            outcome.getMessage(),
            true));
        return outcome;
    }

    public SessionRepository.ReplaceOutcome replaceNow(SavedState detached)
    {
        TrackingIdentity identity = repository.getBoundIdentity();
        WriteIntent intent = identity == null
            ? WriteIntent.unbound(
                repository.getScopeGeneration(),
                repository.getLastKnownDiskRevision(),
                detached)
            : new WriteIntent(
                identity,
                repository.getScopeGeneration(),
                repository.getLastKnownDiskRevision(),
                detached);
        return replaceNow(intent);
    }

    public boolean flush(Duration timeout)
    {
        Duration wait = timeout == null ? DEFAULT_SHUTDOWN_FLUSH : timeout;
        scheduleDrain();
        long deadline = System.nanoTime() + wait.toNanos();
        synchronized (flushMonitor)
        {
            while (coalesced.get() != null || drainScheduled.get())
            {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L)
                {
                    return coalesced.get() == null && !drainScheduled.get();
                }
                try
                {
                    flushMonitor.wait(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1L, 250L));
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    public void shutdown(Duration timeout)
    {
        acceptingWork = false;
        flush(timeout == null ? DEFAULT_SHUTDOWN_FLUSH : timeout);
        synchronized (executorLock)
        {
            if (externalExecutor || executor == null)
            {
                return;
            }
            executor.shutdown();
            try
            {
                if (!executor.awaitTermination(
                    (timeout == null ? DEFAULT_SHUTDOWN_FLUSH : timeout).toMillis(),
                    TimeUnit.MILLISECONDS))
                {
                    executor.shutdownNow();
                }
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private void scheduleDrain()
    {
        if (!drainScheduled.compareAndSet(false, true))
        {
            return;
        }
        ExecutorService active;
        synchronized (executorLock)
        {
            if (!externalExecutor && (executor == null || executor.isShutdown()))
            {
                if (!acceptingWork)
                {
                    drainScheduled.set(false);
                    return;
                }
                executor = newExecutor();
            }
            active = executor;
        }
        try
        {
            active.execute(this::drain);
        }
        catch (RuntimeException ex)
        {
            drainScheduled.set(false);
            log.warn("Unable to schedule GP Manager persistence drain", ex);
            status.set(new SaveStatus(
                SaveStatus.State.FAILED,
                appliedRevision.get(),
                status.get().getLastSuccessEpochMillis(),
                0L,
                "Persistence executor rejected work",
                true));
        }
    }

    private void drain()
    {
        try
        {
            while (true)
            {
                PendingWrite work = coalesced.getAndSet(null);
                if (work == null)
                {
                    return;
                }
                WriteIntent intent = rebaseOntoOwnLineage(work.intent);
                long started = System.nanoTime();
                boolean ok;
                try
                {
                    ok = repository.save(intent);
                }
                catch (RuntimeException ex)
                {
                    log.warn("GP Manager background save crashed", ex);
                    ok = false;
                }
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (ok)
                {
                    // Late completions for an abandoned scope must not rewrite
                    // the live applied revision or OK status for the new scope.
                    if (intent.getScopeGeneration() == repository.getScopeGeneration())
                    {
                        appliedRevision.set(work.revision);
                        lastFailedIntent.set(null);
                        status.set(new SaveStatus(
                            SaveStatus.State.OK,
                            work.revision,
                            System.currentTimeMillis(),
                            durationMs,
                            "",
                            false,
                            repository.getCurrentStateBytes(),
                            Math.max(status.get().getMaxDurationMillis(), durationMs),
                            coalesced.get() == null ? 0 : 1,
                            0L,
                            repository.getLastRecoveredFrom()));
                    }
                    else
                    {
                        log.debug(
                            "Ignoring late save success for abandoned scope gen {} (live gen {})",
                            intent.getScopeGeneration(),
                            repository.getScopeGeneration());
                    }
                }
                else
                {
                    SaveStatus.State state = SaveStatus.State.FAILED;
                    String detail = "Save failed; retry available";
                    long disk = repository.getLastKnownDiskRevision();
                    if (disk != intent.getExpectedBaseRevision())
                    {
                        state = SaveStatus.State.CONFLICT;
                        detail = "Disk revision changed (expected base "
                            + intent.getExpectedBaseRevision() + ", found " + disk + ")";
                    }
                    if (intent.getScopeGeneration() == repository.getScopeGeneration())
                    {
                        lastFailedIntent.set(intent);
                        status.set(new SaveStatus(
                            state,
                            work.revision,
                            status.get().getLastSuccessEpochMillis(),
                            durationMs,
                            detail,
                            state == SaveStatus.State.FAILED,
                            status.get().getLastBytes(),
                            status.get().getMaxDurationMillis(),
                            coalesced.get() == null ? 0 : 1,
                            System.currentTimeMillis(),
                            status.get().getRecoveredFrom()));
                    }
                }
            }
        }
        finally
        {
            drainScheduled.set(false);
            if (coalesced.get() != null && acceptingWork)
            {
                scheduleDrain();
            }
            synchronized (flushMonitor)
            {
                flushMonitor.notifyAll();
            }
        }
    }

    /**
     * When this writer already committed revision {@code N} and disk still shows
     * {@code N}, a newer queued snapshot that captured base {@code < N} while that
     * save was in flight is part of our own lineage — rebase its expected base.
     * If disk diverged from our applied revision, leave the intent alone so CAS
     * surfaces a real external conflict.
     */
    WriteIntent rebaseOntoOwnLineage(WriteIntent intent)
    {
        if (intent == null)
        {
            return null;
        }
        long own = appliedRevision.get();
        if (own <= 0L || intent.getExpectedBaseRevision() >= own)
        {
            return intent;
        }
        if (intent.getScopeGeneration() != repository.getScopeGeneration())
        {
            return intent;
        }
        long disk = repository.getLastKnownDiskRevision();
        if (disk != own)
        {
            return intent;
        }
        return intent.withExpectedBaseRevision(own);
    }

    public SavedState detach(SavedState live)
    {
        return repository.detach(live);
    }

    private static final class PendingWrite
    {
        private final long revision;
        private final WriteIntent intent;

        private PendingWrite(WriteIntent intent)
        {
            this.intent = intent;
            this.revision = intent.getState().getRevision();
        }
    }
}
