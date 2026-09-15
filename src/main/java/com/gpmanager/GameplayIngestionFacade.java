package com.gpmanager;

import com.gpmanager.persistence.PersistenceCoordinator;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;

/**
 * Single gate for login initialization and gameplay event ingestion. Used by
 * {@link GpManagerPlugin} so failed identity switches cannot feed a new account
 * into the previous bound state.
 */
@Singleton
public class GameplayIngestionFacade
{
    private final BooleanSupplier loggedIn;
    private final BooleanSupplier identityReady;

    @Inject
    public GameplayIngestionFacade(Client client, PersistenceCoordinator persistence)
    {
        this(
            () ->
            {
                try
                {
                    return client != null && client.getGameState() == GameState.LOGGED_IN;
                }
                catch (RuntimeException ex)
                {
                    return false;
                }
            },
            persistence::isTrackingReady);
    }

    /** Deterministic harness for plugin-level ingestion tests. */
    public GameplayIngestionFacade(BooleanSupplier loggedIn, BooleanSupplier identityReady)
    {
        this.loggedIn = loggedIn;
        this.identityReady = identityReady;
    }

    public boolean canIngestGameplay()
    {
        return loggedIn.getAsBoolean() && identityReady.getAsBoolean();
    }

    public boolean canInitializeAfterLogin()
    {
        return canIngestGameplay();
    }

    public boolean canProcessItemContainer()
    {
        return canIngestGameplay();
    }

    public boolean canProcessLoot()
    {
        return canIngestGameplay();
    }

    public boolean canProcessGameTick()
    {
        return canIngestGameplay();
    }
}
