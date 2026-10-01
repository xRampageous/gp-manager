package com.gpmanager;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;

import static org.junit.Assert.*;

/** The production gate requires both login and the current account's ready state. */
public class GameplayIngestionTest
{
    @Test
    public void loginAndIdentityMustBothBeReady() throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        boolean[] ready = {false};
        GameState[] state = {GameState.LOGIN_SCREEN};
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) -> "getGameState".equals(method.getName()) ? state[0] : null);
        set(plugin, "client", client);
        set(plugin, "persistence", new PersistenceCoordinator(null, null, null, null, null)
        {
            @Override public synchronized boolean isTrackingReady() { return ready[0]; }
        });
        assertFalse(plugin.canIngestGameplay());
        ready[0] = true;
        assertFalse(plugin.canIngestGameplay());
        state[0] = GameState.LOGGED_IN;
        assertTrue(plugin.canIngestGameplay());
        ready[0] = false;
        assertFalse("an account switch holds ingestion even while logged in", plugin.canIngestGameplay());
    }

    @Test
    public void missingOrUnavailableClientFailsClosed() throws Exception
    {
        GpManagerPlugin plugin = new GpManagerPlugin();
        assertFalse(plugin.canIngestGameplay());
        set(plugin, "client", Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
            (proxy, method, args) -> { throw new IllegalStateException("client unavailable"); }));
        assertFalse(plugin.canIngestGameplay());
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = GpManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
