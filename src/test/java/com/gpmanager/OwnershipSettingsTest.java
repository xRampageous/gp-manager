package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static com.gpmanager.GpManagerPluginProbe.set;
import static org.junit.Assert.*;

/** Legacy false preferences must never split the player's owned containers. */
public class OwnershipSettingsTest {
    @Test public void containerExclusionSettingsAreRetired() {
        for (var method : GpManagerConfig.class.getDeclaredMethods()) {
            assertNotEquals("includeEquipment", method.getName());
            assertNotEquals("includeRunePouch", method.getName());
        }
    }

    @Test public void legacyFalsePreferencesStillBaselineEquipmentAndPouch() throws Exception {
        GpManagerConfig defaults = new GpManagerConfig() {};
        GpManagerConfig config = proxy(GpManagerConfig.class, (ignored, method, args) -> {
            if (method.getName().equals("includeEquipment") || method.getName().equals("includeRunePouch"))
                return false;
            return method.invoke(defaults, args);
        });
        ItemContainer inventory = proxy(ItemContainer.class, (ignored, method, args) ->
                method.getName().equals("getItems") ? new Item[] {new Item(554, 20)} : null);
        ItemContainer worn = proxy(ItemContainer.class, (ignored, method, args) ->
                method.getName().equals("getItems") ? new Item[] {new Item(4151, 1)} : null);
        EnumComposition runes = proxy(EnumComposition.class, (ignored, method, args) -> 554);
        Client client = proxy(Client.class, (ignored, method, args) -> {
            switch (method.getName()) {
                case "getItemContainer": return (int) args[0] == InventoryID.INV ? inventory : worn;
                case "getEnum": return runes;
                case "getVarbitValue": return (int) args[0] == VarbitID.RUNE_POUCH_TYPE_1 ? 4
                        : (int) args[0] == VarbitID.RUNE_POUCH_QUANTITY_1 ? 100 : 0;
                case "getVarpValue": return 0;
                default: return null;
            }
        });
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(1_000_000L);
        GpManagerPlugin plugin = new GpManagerPluginProbe();
        set(plugin, "config", config);
        set(plugin, "client", client);
        set(plugin, "engine", engine);
        set(plugin, "containers", new ContainerSnapshotFactory(client, null));
        plugin.rebaseline();
        assertEquals(1, engine.baseline.quantity(4151));
        assertEquals(120, engine.baseline.quantity(554));
        assertTrue(engine.session().transactions().isEmpty());
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}

