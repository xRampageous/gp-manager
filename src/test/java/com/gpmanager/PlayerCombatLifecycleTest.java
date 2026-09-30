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
        plugin.engine = new Am(d -> Collections.emptyList(), new TransactionClassifier(), plugin.config);
        plugin.charges = new ChargeIntake(null, null, plugin.config, plugin.engine, null);
        plugin.geOfferLedger = new Bj();
        plugin.characterIdleTracker = new CharacterIdleTracker(null, new CharacterIdleModel());
        plugin.interactionContextTracker = new InteractionContextTracker(null, plugin.engine, plugin.config);
        plugin.persistence = new Ei(null, null, null, null, plugin.engine)
        {
            @Override boolean isTrackingReady() { return false; }
            @Override void onLogout(boolean persistHistory) { }
        };
        plugin.recentPlayerCombatTicks = 20;
        return plugin;
    }

    private void assertPvmReclaimAllowed(GpManagerPlugin plugin)
    {
        assertEquals(GpManagerPlugin.Cn.TRACK_PVM_RECLAIM,
            GpManagerPlugin.yr(true, plugin.recentPlayerCombatTicks, true, Dh.DANGEROUS_PVP));
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
        plugin.lx();
        assertPvmReclaimAllowed(plugin);
    }

    @Test
    public void ownerSwitchPreservesHeldCashBeforeThePreviousProfileIsSaved()
    {
        GpManagerPlugin plugin = plugin();
        plugin.engine.rm(1_000L);
        plugin.engine.abg(1_100L);
        plugin.engine.geCustody.agq(100L, 1_200L);
        Ad previous = plugin.engine.getActiveSession();
        plugin.lx();
        assertEquals(0L, plugin.engine.geCollectionIntentUntil);
        assertEquals(0L, plugin.engine.geCustody.pendingSettlementCashGp);
        assertEquals(1, previous.getTransactions().size());
        Ac held = previous.getTransactions().get(0);
        assertEquals(Ai.UNCERTAIN, held.getType());
        assertFalse(held.isCounted());
        assertEquals(100L, held.getFlows().get(0).valueDelta);
        assertEquals(1_200L, held.timestampEpochMillis);
        assertEquals(1, plugin.engine.qm().getActiveSession().getTransactions().size());
    }

    @Test
    public void aRegionLoadKeepsCurrentLoginCombatEvidence()
    {
        GpManagerPlugin plugin = plugin();
        plugin.onGameStateChanged(event(GameState.LOADING));
        assertEquals(20, plugin.recentPlayerCombatTicks);
    }
}
