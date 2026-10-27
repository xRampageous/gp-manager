package com.gpmanager;

import java.util.Collections;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import org.junit.Test;
import static org.junit.Assert.*;

/** Short-lived PvP evidence belongs to the current login and account. */
public class PlayerCombatLifecycleTest
{
    private GpManagerPlugin plugin()
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        plugin.config = new GpManagerConfig() {};
        plugin.engine = new Engine(d -> Collections.emptyList(), new TransactionClassifier(), plugin.config);
        plugin.charges = new ChargeIntake(null, null, plugin.config, plugin.engine, null);
        plugin.offers = new OfferSlots();
        plugin.idle = new CharacterIdleTracker(null, new CharacterIdleModel());
        plugin.targets = new InteractionContextTracker(null, plugin.engine, plugin.config);
        plugin.storage = new Persistence(null, null, null, null, plugin.engine)
        {
            @Override boolean isTrackingReady() { return false; }
            @Override void onLogout() { }
        };
        plugin.combat = 20;
        return plugin;
    }

    private void assertPvmReclaimAllowed(GpManagerPlugin plugin)
    {
        assertEquals(GpManagerPlugin.DeathOutcome.TRACK_PVM_RECLAIM,
            GpManagerPlugin.deathOutcome(true, plugin.combat, true, PvpContext.DANGEROUS_PVP));
    }

    private GameStateChanged event(GameState state)
    {
        GameStateChanged event = new GameStateChanged();
        event.setGameState(state);
        return event;
    }

    @Test
    public void logoutAndHopDropPlayerCombatEvidence()
    {
        for (GameState state : new GameState[] {GameState.LOGIN_SCREEN, GameState.HOPPING})
        {
            GpManagerPlugin plugin = plugin();
            plugin.onGameStateChanged(event(state));
            assertPvmReclaimAllowed(plugin);
        }
    }

    @Test
    public void anOwnerSwitchDropsPlayerCombatEvidence()
    {
        GpManagerPlugin plugin = plugin();
        plugin.beginOwnerSwitch();
        assertPvmReclaimAllowed(plugin);
    }

    @Test
    public void ownerSwitchPreservesHeldCashBeforeThePreviousProfileIsSaved()
    {
        GpManagerPlugin plugin = plugin();
        plugin.engine.ensureSession(1_000L);
        plugin.engine.noteGeCollect(1_100L);
        plugin.engine.exchange.now = 1_200L;
        plugin.engine.exchange.holdCash(100L);
        Session previous = plugin.engine.session();
        plugin.beginOwnerSwitch();
        assertNull(plugin.engine.events.peek(Inbox.GE_COLLECT));
        assertEquals(0L, plugin.engine.exchange.cash);
        assertEquals(1, previous.transactions().size());
        Transaction held = previous.transactions().get(0);
        assertEquals(TransactionType.UNCERTAIN, held.type());
        assertFalse(held.counts());
        assertEquals(100L, held.flows().get(0).value);
        assertEquals(1_200L, held.timestamp);
        assertEquals(1, plugin.engine.savedState().currentSession().transactions().size());
    }

    @Test
    public void aRegionLoadKeepsCurrentLoginCombatEvidence()
    {
        GpManagerPlugin plugin = plugin();
        plugin.onGameStateChanged(event(GameState.LOADING));
        assertEquals(20, plugin.combat);
    }
}
