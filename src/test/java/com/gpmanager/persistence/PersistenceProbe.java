package com.gpmanager;

import javax.annotation.Nullable;
import net.runelite.client.util.Filepath;

/** Test-side reads of persistence state; moved out of production to keep the plugin small. */
public final class PersistenceProbe
{
    /** Test-only serializer; both writer routes use the canonical snapshot builder. */
    public static String snapshotJson(Persistence persistence, SavedState header, boolean quick) {
        return persistence.repository.gson.toJson(persistence.snapshotState(header));
    }

    private PersistenceProbe()
    {
    }

    /**
     * An account clear that skips its save, as the retired history-off mode did: lets a test unbind
     * while an earlier write is still in flight and check how that stale write completes.
     */
    public static void unbindWithoutSave(Persistence persistence)
    {
        synchronized (persistence)
        {
            persistence.engine.clearIdentity();
            persistence.activeIdentity = null;
            persistence.repository.unbind();
            persistence.writer.resetRevision(0);
            persistence.next = 1;
            persistence.engine.restoreProfile(null, new SavedState(), SafeMath.now());
            persistence.switchBlocked = false;
        }
    }

    /** Saves {@code state} on the disk revision observed now, healing an unreadable primary first. */
    public static boolean save(SessionRepository repository, SavedState state)
    {
        synchronized (repository)
        {
            return repository.save(intentFromDisk(repository, state));
        }
    }

    /** A destructive replacement based on the disk revision observed now. */
    public static SessionRepository.ReplaceOutcome replaceStateDetailed(SessionRepository repository, SavedState state)
    {
        if (state == null)
        {
            return SessionRepository.ReplaceOutcome.unchanged("state was null");
        }
        synchronized (repository)
        {
            return repository.replaceStateDetailed(intentFromDisk(repository, state));
        }
    }

    private static WriteIntent intentFromDisk(SessionRepository repository, SavedState state)
    {
        SessionRepository.ScopeFiles files = repository.bound;
        if (files.state.exists())
        {
            try
            {
                repository.readState(files.state);
            }
            catch (Exception ex)
            {
                repository.setAside(files);
                repository.revision = repository.backupRevision(files.backup).orElse(0L);
            }
        }
        if (repository.revision <= 0L)
        {
            repository.revision = repository.peekRevision(files.state)
                .orElse(repository.backupRevision(files.backup).orElse(0L));
        }
        long base = repository.revision;
        return repository.owner == null
            ? new WriteIntent(null, repository.generation, base, state, null)
            : new WriteIntent(repository.owner, repository.generation, base, state, null);
    }

    /**
     * Waits for maintenance the Grinds controller ran off Swing's thread (Codex review 1.2), then for
     * its result back on Swing's thread; {@code steps} covers a chain such as backup-then-reset.
     */
    public static void drain(Persistence persistence, int steps) throws Exception
    {
        for (int step = 0; step < steps; step++)
        {
            persistence.executor().submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        }
    }

    /** Number of existing rotated good-save copies (.1 through .3) in this scope. */
    public static int rotatedBackupCount(SessionRepository repository)
    {
        synchronized (repository)
        {
            repository.requireInit();
            int count = 0;
            for (int index = 1; index <= 3; index++)
            {
                Filepath candidate = repository.bound.dir.joinSegment(
                    repository.bound.backup.getFileName() + "." + index);
                if (candidate.exists())
                {
                    count++;
                }
            }
            return count;
        }
    }

    /** Why writes are held for the current identity, or null when the bound account may write. */
    @Nullable
    public static String identityBlockReason(Persistence coordinator)
    {
        synchronized (coordinator)
        {
            TrackingIdentity current = coordinator.resolveCurrentIdentity();
            if (current == null)
            {
                return coordinator.activeIdentity == null
                    ? "Waiting for RuneScape profile identity"
                    : "Client profile unavailable; previous account state held";
            }
            if (coordinator.activeIdentity == null)
            {
                return "Tracking identity not bound yet";
            }
            if (coordinator.reason != null)
            {
                return coordinator.reason;
            }
            if (!current.equals(coordinator.activeIdentity))
            {
                SaveStatus status = coordinator.writer.status();
                String detail = status == null || status.detail == null || status.detail.isEmpty()
                    ? "previous account save did not finish"
                    : status.detail;
                return "Account switch held — " + detail + ". Retry after save succeeds.";
            }
            return null;
        }
    }
}
