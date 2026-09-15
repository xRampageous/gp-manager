package com.gpmanager;

import com.gpmanager.diagnostics.DebugTrace;
import com.gpmanager.engine.ContainerSnapshot;
import com.gpmanager.engine.ContainerSnapshotFactory;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TransactionType;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
        GpManagerEngine engine = new GpManagerEngine(deltas -> value(deltas),
            new TransactionClassifier(), config);
        ItemContainer emptyInventory = proxy(ItemContainer.class, method ->
            "getItems".equals(method) ? new Item[0] : null);
        Client client = proxy(Client.class, method ->
        {
            if ("getGameState".equals(method)) return GameState.LOGGED_IN;
            if ("getItemContainer".equals(method)) return emptyInventory;
            return null;
        });

        GpManagerPlugin plugin = new GpManagerPlugin();
        set(plugin, "client", client);
        set(plugin, "config", config);
        set(plugin, "engine", engine);
        set(plugin, "snapshotFactory", new ContainerSnapshotFactory(client, null));
        set(plugin, "ingestion", new GameplayIngestionFacade(() -> true, () -> true));
        set(plugin, "debugTrace", new DebugTrace());

        Method start = GpManagerPlugin.class.getDeclaredMethod("markGameplayActivity", boolean.class);
        start.setAccessible(true);
        start.invoke(plugin, true);
        assertNotNull(engine.getActiveSession());

        long now = System.currentTimeMillis();
        engine.markInventoryDirty();
        ContainerSnapshot potato = new ContainerSnapshot(Collections.singletonMap(1942, 1L));
        assertNull(engine.processIfDirty(potato, now + 600L));
        ProfitTransaction gain = engine.processIfDirty(potato, now + 1_200L);

        assertNotNull(gain);
        assertEquals(TransactionType.GAIN, gain.getType());
        assertEquals(50L, engine.getMetrics(now + 1_200L).getRevenue());
    }

    private static List<ItemFlow> value(Map<Integer, Long> deltas)
    {
        List<ItemFlow> flows = new ArrayList<>();
        deltas.forEach((id, quantity) ->
            flows.add(new ItemFlow(id, "Item " + id, quantity, 50, quantity * 50)));
        return flows;
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
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
