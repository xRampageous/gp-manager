package com.gpmanager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plugin-to-engine proof that a first no-XP gather keeps its pre-click baseline. */
public class GpManagerPluginAutoStartTest
{
    @Test
    public void firstGatherAfterAutomaticStartIsCounted() throws Exception
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 1; }
        };
        Engine engine = new Engine(deltas -> value(deltas),
            new TransactionClassifier(), config);
        ItemContainer emptyInventory = proxy(ItemContainer.class, method ->
            "getItems".equals(method) ? new Item[0] : null);
        Client client = proxy(Client.class, method ->
        {
            if ("getGameState".equals(method)) return GameState.LOGGED_IN;
            if ("getItemContainer".equals(method)) return emptyInventory;
            return null;
        });

        GpManagerPlugin plugin = new GpManagerPluginProbe();
        set(plugin, "client", client);
        set(plugin, "config", config);
        set(plugin, "engine", engine);
        set(plugin, "snapshotFactory", new ContainerSnapshotFactory(client, null));

        Method start = GpManagerPlugin.class.getDeclaredMethod("markGameplayActivity", boolean.class);
        start.setAccessible(true);
        start.invoke(plugin, true);
        assertNotNull(engine.getActiveSession());

        long now = System.currentTimeMillis();
        engine.markDirty();
        ContainerSnapshot potato = new ContainerSnapshot(Collections.singletonMap(1942, 1L));
        assertNull(engine.processIfDirty(potato, now + 600L));
        Transaction gain = engine.processIfDirty(potato, now + 1_200L);

        assertNotNull(gain);
        assertEquals(TransactionType.GAIN, gain.getType());
        assertEquals(50L, engine.getMetrics(now + 1_200L).revenue);
    }

    /** Owner live 2026-10-13: an option starts tracking at the first login itself. */
    @Test
    public void loginStartsTrackingWhenTheOptionIsOnAndOnlyOnTheFirstLogin() throws Exception
    {
        GpManagerConfig on = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 1; }
        };
        GpManagerPlugin plugin = loginPlugin(on);
        set(plugin, "firstLoginAutoStart", true);
        init(plugin);
        Engine engine = (Engine) get(plugin, "engine");
        assertNotNull("the login option starts tracking at login", engine.getActiveSession());
        assertFalse("the login offer is spent", (Boolean) get(plugin, "firstLoginAutoStart"));

        // Option off: login stays passive (gameplay still starts tracking).
        GpManagerConfig off = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 1; }
            @Override public boolean autoStartOnLogin() { return false; }
        };
        GpManagerPlugin silent = loginPlugin(off);
        set(silent, "firstLoginAutoStart", true);
        init(silent);
        assertNull("option off keeps login passive",
            ((Engine) get(silent, "engine")).getActiveSession());

        // A later login (offer already spent) does not start on its own either.
        GpManagerPlugin later = loginPlugin(on);
        set(later, "firstLoginAutoStart", false);
        init(later);
        assertNull("only the first login may start",
            ((Engine) get(later, "engine")).getActiveSession());
    }

    /** The login option also resumes the pauses gameplay would (Idle/Recovery), never Manual. */
    @Test
    public void loginOptionResumesIdleButNeverManual() throws Exception
    {
        GpManagerConfig on = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 1; }
        };
        GpManagerPlugin idle = loginPlugin(on);
        Engine idleEngine = (Engine) get(idle, "engine");
        idleEngine.startCustom("Vorkath", System.currentTimeMillis());
        idleEngine.getActiveSession().pause(System.currentTimeMillis(), PauseReason.IDLE);
        set(idle, "firstLoginAutoStart", true);
        init(idle);
        assertFalse("an Idle pause comes back at login", idleEngine.getActiveSession().paused);

        GpManagerPlugin manual = loginPlugin(on);
        Engine manualEngine = (Engine) get(manual, "engine");
        manualEngine.startCustom("Vorkath", System.currentTimeMillis());
        manualEngine.getActiveSession().pause(System.currentTimeMillis(), PauseReason.MANUAL);
        set(manual, "firstLoginAutoStart", true);
        init(manual);
        assertTrue("a Manual pause always holds", manualEngine.getActiveSession().paused);
    }

    private static GpManagerPlugin loginPlugin(GpManagerConfig config) throws Exception
    {
        Engine engine = new Engine(deltas -> value(deltas), new TransactionClassifier(), config);
        ItemContainer emptyInventory = proxy(ItemContainer.class, method ->
            "getItems".equals(method) ? new Item[0] : null);
        Client client = proxy(Client.class, method ->
        {
            if ("getGameState".equals(method)) return GameState.LOGGED_IN;
            if ("getItemContainer".equals(method)) return emptyInventory;
            return null;
        });
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        set(plugin, "client", client);
        set(plugin, "config", config);
        set(plugin, "engine", engine);
        set(plugin, "snapshotFactory", new ContainerSnapshotFactory(client, null));
        set(plugin, "activity", new ActivityDetector(config, engine));
        return plugin;
    }

    private static void init(GpManagerPlugin plugin) throws Exception
    {
        Method init = GpManagerPlugin.class.getDeclaredMethod("initLoggedIn");
        init.setAccessible(true);
        init.invoke(plugin);
    }

    private static Object get(Object target, String name) throws Exception
    {
        Field field = GpManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static List<Flow> value(Map<Integer, Long> deltas)
    {
        List<Flow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) ->
            flows.add(new Flow(id, "Item " + id, quantity, 50, quantity * 50)));
        return flows;
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = GpManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> values)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (object, method, args) ->
            {
                Object value = values.apply(method.getName());
                if (value != null || !method.getReturnType().isPrimitive()) return value;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == long.class) return 0L;
                return 0;
            }));
    }
}
