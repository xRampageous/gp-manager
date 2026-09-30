package com.gpmanager;

import javax.annotation.Nullable;
import net.runelite.client.util.Filepath;

/** Test-side reads of persistence state; moved out of production to keep the plugin small. */
public final class PersistenceProbe
{
    private PersistenceProbe()
    {
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
    public static SessionRepository.Bm replaceStateDetailed(SessionRepository repository, SavedState state)
    {
        if (state == null)
        {
            return SessionRepository.Bm.failedUnchanged("state was null");
        }
        synchronized (repository)
        {
            return repository.replaceStateDetailed(intentFromDisk(repository, state));
        }
    }

    private static Cs intentFromDisk(SessionRepository repository, SavedState state)
    {
        SessionRepository.ScopeFiles files = repository.bound;
        if (files.state.exists())
        {
            try
            {
                repository.avc(files.state);
            }
            catch (Exception ex)
            {
                repository.adf(files);
                repository.lastKnownDiskRevision = repository.adm(files.backup).orElse(0L);
            }
        }
        if (repository.lastKnownDiskRevision <= 0L)
        {
            repository.lastKnownDiskRevision = repository.ado(files.state)
                .orElse(repository.adm(files.backup).orElse(0L));
        }
        long base = repository.lastKnownDiskRevision;
        return repository.boundIdentity == null
            ? new Cs(null, repository.scopeGeneration, base, state)
            : new Cs(repository.boundIdentity, repository.scopeGeneration, base, state);
    }

    /** Number of existing rotated good-save copies (.1 through .3) in this scope. */
    public static int rotatedBackupCount(SessionRepository repository)
    {
        synchronized (repository)
        {
            repository.afp();
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
    public static String identityBlockReason(Ei coordinator)
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
            if (coordinator.readOnlyReason != null)
            {
                return coordinator.readOnlyReason;
            }
            if (!current.equals(coordinator.activeIdentity))
            {
                Ci status = coordinator.writer.getStatus();
                String detail = status == null || status.detail == null || status.detail.isEmpty()
                    ? "previous account save did not finish"
                    : status.detail;
                return "Account switch held — " + detail + ". Retry after save succeeds.";
            }
            return null;
        }
    }
}
