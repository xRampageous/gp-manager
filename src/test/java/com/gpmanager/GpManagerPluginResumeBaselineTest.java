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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-10-01: login and gameplay resumes trust the loaded containers as the baseline,
 * so the first action counts instead of being absorbed by the priming window.
 */
public class GpManagerPluginResumeBaselineTest
{
    @Test
    public void theFirstActionAfterLoginCounts() throws Exception
    {
        Harness harness = new Harness();
        harness.engine.ensureSession(1_000L);

        harness.plugin.initializeLoggedInState();

        harness.firstActionBooks();
    }

    @Test
    public void theActionThatResumesAnIdlePauseCounts() throws Exception
    {
        Harness harness = new Harness();
        harness.engine.ensureSession(1_000L);
        harness.engine.setBaseline(ContainerSnapshot.empty());
        harness.engine.pauseForIdle(2_000L, 2_000L);
        assertTrue("the session is idle-paused", harness.engine.isIdlePaused());

        Method activity = GpManagerPlugin.class.getDeclaredMethod(
            "markGameplayActivity", boolean.class);
        activity.setAccessible(true);
        activity.invoke(harness.plugin, false);

        harness.firstActionBooks();
    }

    /** One post-resume inventory gain bookends: it stabilizes, then it books. */
    private static final class Harness
    {
        final GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
        };
        final Engine engine = new Engine(GpManagerPluginResumeBaselineTest::value,
            new TransactionClassifier(), config);
        final GpManagerPlugin plugin = new GpManagerPluginProbe();

        Harness() throws Exception
        {
            ItemContainer emptyInventory = proxy(ItemContainer.class, method ->
                "getItems".equals(method) ? new Item[0] : null);
            Client client = proxy(Client.class, method ->
            {
                if ("getGameState".equals(method)) return GameState.LOGGED_IN;
                if ("getItemContainer".equals(method)) return emptyInventory;
                return null;
            });
            set(plugin, "client", client);
            set(plugin, "config", config);
            set(plugin, "engine", engine);
            set(plugin, "snapshotFactory", new ContainerSnapshotFactory(client, null));
            set(plugin, "activity", new ActivityDetector(config, engine));
        }

        void firstActionBooks()
        {
            long now = System.currentTimeMillis();
            engine.markInventoryDirty();
            ContainerSnapshot potato = new ContainerSnapshot(Collections.singletonMap(1942, 1L));
            assertNull("the gain still stabilizes", engine.processIfDirty(potato, now + 600L));
            Transaction gain = engine.processIfDirty(potato, now + 1_200L);

            assertNotNull("the first action books, not the baseline", gain);
            assertEquals(TransactionType.GAIN, gain.getType());
            assertEquals(50L, engine.getMetrics(now + 1_200L).revenue);
        }
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
