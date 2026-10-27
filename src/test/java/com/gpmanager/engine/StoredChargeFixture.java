package com.gpmanager;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InventoryID;

/** Shared offline charge/death fixtures; prices are synthetic, not live quotes. */
final class StoredChargeFixture {
    static final long NOW = ReviewFixesTest.T0;
    static Engine engine() {
        JsonCodec.bind(new Gson());
        Engine engine = new Engine(delta -> {
            var flows = new ArrayList<Flow>();
            delta.forEach((id, quantity) -> {
                long price = id == 995 ? 1 : id == 13204 ? 1000 : 100;
                flows.add(new Flow(id, "Item " + id, quantity, price, quantity * price,
                        id == 995 || id == 13204 ? PriceSource.FACE_VALUE : PriceSource.GRAND_EXCHANGE));
            });
            return flows;
        }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
        engine.ensureSession(NOW);
        return engine;
    }

    static ContainerSnapshotFactory factory(Map<Integer, Long> inventory, Map<Integer, Integer> bits) {
        Item[] items = inventory.entrySet().stream().map(entry -> new Item(entry.getKey(),
                Math.toIntExact(entry.getValue()))).toArray(Item[]::new);
        var container = (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
                new Class<?>[]{ItemContainer.class}, (proxy, method, args) ->
                        method.getName().equals("getItems") ? items : null);
        var client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getItemContainer"))
                        return (int) args[0] == InventoryID.INV ? container : null;
                    if (method.getName().equals("getVarbitValue")) return bits.getOrDefault((int) args[0], 0);
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        return new ContainerSnapshotFactory(client, null);
    }

    static ContainerSnapshot snapshot(int item, int varbit, int charges, Map<Integer, Long> loose) {
        var inventory = new HashMap<>(loose);
        if (item > 0) inventory.merge(item, 1L, Long::sum);
        return factory(inventory, Map.of(varbit, charges)).capture(true, false);
    }

    static void settle(Engine engine, ContainerSnapshot next, long at) {
        engine.markDirty();
        for (int tick = 0; tick < 8; tick++) engine.processIfDirty(next, at + tick * 600L, null);
    }

    static long reviews(Engine engine) {
        return engine.session().transactions().stream().filter(ReviewRules::needsReview).count();
    }
}
