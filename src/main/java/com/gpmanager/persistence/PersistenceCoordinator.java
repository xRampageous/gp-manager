package com.gpmanager.persistence;

import com.gpmanager.diagnostics.DebugTrace;
import com.gpmanager.engine.GpManagerEngine;
import java.time.Duration;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates account binding, detached snapshots, and ordered persistence.
 */
@Singleton
public class PersistenceCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(PersistenceCoordinator.class);

    private final Client client;
    private final ConfigManager configManager;
    private final SessionRepository repository;
    private final OrderedPersistenceWriter writer;
    private final GpManagerEngine engine;
    private final DebugTrace debugTrace;

    private long nextRevision = 1L;
    private TrackingIdentity activeIdentity;

    @Inject
    public PersistenceCoordinator(
        Client client,
        ConfigManager configManager,
        SessionRepository repository,
        OrderedPersistenceWriter writer,
        GpManagerEngine engine,
        DebugTrace debugTrace)
    {
        this.client = client;
        this.configManager = configManager;
        this.repository = repository;
        this.writer = writer;
        this.engine = engine;
        this.debugTrace = debugTrace;
    }

    public void start()
    {
        writer.start();
    }

    public SaveStatus getSaveStatus()
    {
        return writer.getStatus();
    }

    public TrackingIdentity getActiveIdentity()
    {
        return activeIdentity;
    }

    public SessionRepository getRepository()
    {
        return repository;
    }

    public OrderedPersistenceWriter getWriter()
    {
        return writer;
    }

    @Nullable
    public TrackingIdentity resolveCurrentIdentity()
    {
        if (configManager == null)
        {
            return null;
        }
        String rsProfileKey = configManager.getRSProfileKey();
        long accountHash = TrackingIdentity.ACCOUNT_HASH_INVALID;
        if (client != null)
        {
            try
            {
                accountHash = client.getAccountHash();
            }
            catch (RuntimeException ex)
            {
                // Client may not expose hash before login.
            }
        }
        if (rsProfileKey != null && !rsProfileKey.trim().isEmpty())
        {
            return new TrackingIdentity(rsProfileKey.trim(), accountHash);
        }
        return null;
    }

    /**
     * Bind to the current RS profile when available. Flushes the previous owner
     * before switching. A failed flush aborts the switch so pending work is not
     * redirected to another account.
     *
     * @return true when the bound identity matches the client after the call
     */
    public synchronized boolean syncIdentityFromClient(boolean persistHistory)
    {
        return trySwitchIdentity(resolveCurrentIdentity(), persistHistory);
    }

    /**
     * Attempt to bind {@code next}. When {@code next} is null, returns whether a
     * prior identity remains bound. Failed flush/save refuses the switch and
     * preserves the previous account's in-memory state.
     */
    public synchronized boolean trySwitchIdentity(
        @Nullable TrackingIdentity next,
        boolean persistHistory)
    {
        if (next == null)
        {
            return activeIdentity != null;
        }
        if (next.equals(activeIdentity))
        {
            return true;
        }

        if (activeIdentity != null && persistHistory)
        {
            scheduleSave();
            if (!flushPending(Duration.ofSeconds(5)))
            {
                log.warn("Refusing account switch: previous scope flush did not complete");
                debugTrace.record("persistence", "account switch aborted: flush incomplete");
                return false;
            }
            SaveStatus status = writer.getStatus();
            if (status.isFailure() || status.getState() == SaveStatus.State.CONFLICT)
            {
                log.warn("Refusing account switch after failed save: {}", status.getDetail());
                debugTrace.record("persistence", "account switch aborted: save failure");
                return false;
            }
        }

        TrackingIdentity previous = activeIdentity;
        long previousAppliedRevision = writer.getAppliedRevision();
        long previousNextRevision = nextRevision;
        activeIdentity = next;
        try
        {
            repository.bindIdentity(next);
            if (persistHistory)
            {
                SavedState loaded = repository.load();
                writer.resetAppliedRevision(loaded.getRevision());
                nextRevision = Math.max(loaded.getRevision() + 1L, 1L);
                engine.restoreForProfile(next.getRsProfileKey(), loaded, System.currentTimeMillis());
                engine.setPersistenceRecoveryHealth(repository.getLastRecoveredFrom(),
                    repository.getRotatedBackupCount());
            }
            else
            {
                writer.resetAppliedRevision(0L);
                nextRevision = 1L;
                engine.restoreForProfile(next.getRsProfileKey(), new SavedState(), System.currentTimeMillis());
                engine.setPersistenceRecoveryHealth("", repository.getRotatedBackupCount());
            }
        }
        catch (RuntimeException ex)
        {
            if (previous == null) repository.unbindIdentity();
            else repository.bindIdentity(previous);
            activeIdentity = previous;
            writer.resetAppliedRevision(previousAppliedRevision);
            nextRevision = previousNextRevision;
            log.warn("Refusing account switch: profile restore could not be prepared", ex);
            debugTrace.record("persistence", "account switch aborted: profile restore failed");
            return false;
        }
        if (previous == null || !previous.equals(next))
        {
            engine.beginBaselinePriming();
        }
        debugTrace.record("persistence", "identity bound gen=" + repository.getScopeGeneration());
        log.debug("GP Manager bound tracking identity {}", next);
        return true;
    }

    public synchronized void onLogout(boolean persistHistory)
    {
        if (activeIdentity != null && persistHistory)
        {
            scheduleSave();
            flushPending(Duration.ofSeconds(5));
        }
    }

    public synchronized void onAccountHashInvalidated(boolean persistHistory)
    {
        if (activeIdentity != null && persistHistory)
        {
            scheduleSave();
            if (!flushPending(Duration.ofSeconds(5)))
            {
                log.warn("Account clear proceeded with undrained pending save");
            }
        }
        engine.clearProfileIdentityForSwitch();
        activeIdentity = null;
        repository.unbindIdentity();
        writer.resetAppliedRevision(0L);
        nextRevision = 1L;
        engine.restoreForProfile(null, new SavedState(), System.currentTimeMillis());
    }

    public synchronized void scheduleSave()
    {
        WriteIntent intent = currentIntent();
        if (intent == null)
        {
            debugTrace.record("persistence", "save skipped: unbound");
            return;
        }
        debugTrace.record("persistence",
            "enqueue rev=" + intent.getState().getRevision()
                + " base=" + intent.getExpectedBaseRevision()
                + " gen=" + intent.getScopeGeneration());
        writer.submit(intent);
    }

    public synchronized boolean saveNow()
    {
        WriteIntent intent = currentIntent();
        if (intent == null)
        {
            return false;
        }
        writer.submit(intent);
        if (!writer.flush(Duration.ofSeconds(5)))
        {
            return false;
        }
        SaveStatus status = writer.getStatus();
        return status != null && !status.isFailure() && status.getState() != SaveStatus.State.CONFLICT;
    }

    /**
     * Imports a validated backup only after pending writes for the current
     * profile have drained, then commits the installed state through the
     * ordinary revision-fenced save path.
     */
    public synchronized ProfileBackupReport restoreProfile(String json, long now)
    {
        ProfileBackupReport inspected = engine.inspectBackup(json);
        if (!inspected.isAccepted()) return inspected;
        if (activeIdentity == null || !repository.isBound() || !isIdentityMatched())
        {
            return inspected.withRefusal("The current RuneScape profile is not safely bound");
        }
        if (!saveNow())
        {
            return inspected.withRefusal("Current profile data could not be flushed; restore was cancelled");
        }

        ProfileBackupReport applied = engine.restoreProfile(json, now);
        if (!applied.isApplied()) return applied;
        if (saveNow()) return applied.asDurablyCommitted();

        SaveStatus status = writer.getStatus();
        String detail = status == null || status.getDetail() == null || status.getDetail().trim().isEmpty()
            ? "Imported in memory but the profile save did not complete"
            : "Imported in memory but the profile save did not complete: " + status.getDetail();
        return applied.withPersistenceFailure(detail);
    }

    public synchronized boolean isIdentityMatched()
    {
        TrackingIdentity current = resolveCurrentIdentity();
        return current != null && current.equals(activeIdentity);
    }

    /**
     * True when the bound identity matches the client and tracking may ingest
     * gameplay. Refused switches leave the previous identity bound, so the new
     * physical account must not feed that state.
     */
    public synchronized boolean isTrackingReady()
    {
        return isIdentityMatched();
    }

    /**
     * True when a different physical account is present but the previous bound
     * identity was retained after a refused switch.
     */
    public synchronized boolean isIdentitySwitchHeld()
    {
        TrackingIdentity current = resolveCurrentIdentity();
        return activeIdentity != null
            && current != null
            && !current.equals(activeIdentity);
    }

    @Nullable
    public synchronized String identityBlockReason()
    {
        TrackingIdentity current = resolveCurrentIdentity();
        if (current == null)
        {
            return activeIdentity == null
                ? "Waiting for RuneScape profile identity"
                : "Client profile unavailable; previous account state held";
        }
        if (activeIdentity == null)
        {
            return "Tracking identity not bound yet";
        }
        if (!current.equals(activeIdentity))
        {
            SaveStatus status = writer.getStatus();
            String detail = status == null || status.getDetail() == null || status.getDetail().isEmpty()
                ? "previous account save did not finish"
                : status.getDetail();
            return "Account switch held — " + detail + ". Retry after save succeeds.";
        }
        return null;
    }

    public synchronized SessionRepository.ReplaceOutcome replaceStateNow()
    {
        WriteIntent intent = currentIntent();
        if (intent == null)
        {
            return SessionRepository.ReplaceOutcome.failedUnchanged("No account identity bound");
        }
        return writer.replaceNow(intent);
    }

    public synchronized boolean flushPending(Duration timeout)
    {
        return writer.flush(timeout);
    }

    public synchronized void shutdown(boolean persistHistory)
    {
        if (persistHistory && repository.isBound())
        {
            scheduleSave();
        }
        writer.shutdown(Duration.ofSeconds(5));
    }

    public synchronized boolean claimUnassignedLegacyForActiveIdentity()
    {
        if (activeIdentity == null)
        {
            return false;
        }
        try
        {
            boolean claimed = repository.claimUnassignedLegacy(activeIdentity);
            if (claimed)
            {
                SavedState loaded = repository.load();
                writer.resetAppliedRevision(loaded.getRevision());
                nextRevision = Math.max(loaded.getRevision() + 1L, 1L);
                engine.restoreForProfile(activeIdentity.getRsProfileKey(), loaded,
                    System.currentTimeMillis());
                engine.setPersistenceRecoveryHealth(repository.getLastRecoveredFrom(),
                    repository.getRotatedBackupCount());
            }
            return claimed;
        }
        catch (Exception ex)
        {
            log.warn("Unable to claim unassigned GP Manager legacy data", ex);
            return false;
        }
    }

    @Nullable
    private WriteIntent currentIntent()
    {
        if (!repository.isBound())
        {
            return null;
        }
        // Prefer the writer's verified lineage when a prior save is still
        // updating disk lastKnown — avoids queuing another base-N snapshot.
        long baseRevision = Math.max(
            repository.getLastKnownDiskRevision(),
            writer.getAppliedRevision());
        SavedState detached = writer.detach(engine.createSavedState());
        detached.setRevision(Math.max(nextRevision, baseRevision + 1L));
        nextRevision = detached.getRevision() + 1L;
        detached.setSavedAtEpochMillis(System.currentTimeMillis());
        if (activeIdentity != null)
        {
            detached.setOwnerKey(activeIdentity.getRsProfileKey());
        }
        detached.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        return activeIdentity == null
            ? WriteIntent.unbound(repository.getScopeGeneration(), baseRevision, detached)
            : new WriteIntent(activeIdentity, repository.getScopeGeneration(), baseRevision, detached);
    }
}
