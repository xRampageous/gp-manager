package com.gpmanager;

import com.google.gson.Gson;
import java.util.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * GP Per Hour 1.22 parity (2026-11-01): a seedling is untradeable and worth nothing, so sowing a
 * seed went to Review and a sapling grown in the bank never came back. A seedling now reads as the
 * sapling it grows into (item_states.tsv), and the sow click arms Farming because sowing gives no
 * XP (Wiki "Seedling", "Rosewood seedling").
 */
public class SeedlingSaplingTest {
    static final int SEED = ItemID.ROSEWOOD_SEED, POT = ItemID.PLANTPOT_COMPOST, TROWEL = ItemID.GARDENING_TROWEL,
        SEEDLING = ItemID.PLANTPOT_ROSEWOOD_SEED, WATERED = ItemID.PLANTPOT_ROSEWOOD_SEED_WATERED,
        SAPLING = ItemID.PLANTPOT_ROSEWOOD_SAPLING, CAN_8 = ItemID.WATERING_CAN_8, CAN_7 = ItemID.WATERING_CAN_7;
    static final long T0 = ReviewFixesTest.T0;
    static final Map<Integer, String> NAMES = Map.of(SEED, "Rosewood seed", POT, "Filled plant pot",
        TROWEL, "Gardening trowel", SAPLING, "Rosewood sapling", CAN_8, "Watering can(8)", CAN_7, "Watering can(7)");
    static final Map<Integer, Long> PRICES = Map.of(SEED, 135_000L, POT, 40L, SAPLING, 140_000L, CAN_8, 8L, CAN_7, 7L);

    static class Fixture {
        final Map<Integer, Long> pack = new HashMap<>();
        int tick = 100;
        Widget selected;
        final Engine engine;
        final GpManagerPluginProbe plugin = new GpManagerPluginProbe();
        final ItemContainer inventory = LootingBagMovementTest.proxy(ItemContainer.class, (method, args) -> {
            if (method.equals("contains")) return pack.containsKey((int) args[0]);
            if (method.equals("getItems")) {
                List<Item> items = new ArrayList<>();
                pack.forEach((id, q) -> items.add(new Item(id, q.intValue())));
                return items.toArray(new Item[0]);
            }
            return null;
        });

        Fixture(Map<Integer, Long> held) {
            JsonCodec.bind(new Gson());
            engine = new Engine(deltas -> {
                List<Flow> result = new ArrayList<>();
                deltas.forEach((id, q) -> {
                    long unit = PRICES.getOrDefault(id, 0L);
                    result.add(new Flow(id, NAMES.getOrDefault(id, "Item " + id), q, unit, q * unit));
                });
                return result;
            }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
            engine.ensureSession(T0);
            pack.putAll(held);
            plugin.client = LootingBagMovementTest.proxy(Client.class, (method, args) -> {
                if (method.equals("getItemContainer")) return (int) args[0] == InventoryID.INV ? inventory : null;
                if (method.equals("getTickCount")) return tick;
                if (method.equals("getSelectedWidget")) return selected;
                if (method.equals("getGameState")) return GameState.LOGGED_IN;
                return null;
            });
            plugin.engine = engine;
            plugin.config = ReviewFixesTest.CONFIG;
            plugin.containers = new ContainerSnapshotFactory(plugin.client, null);
            plugin.targets = GpManagerPluginProbe.tracker(plugin.client, engine, plugin.config);
            plugin.charges = new ChargeIntake(plugin.client, null, plugin.config, engine, null);
            plugin.activity = new ActivityDetector(plugin.config, engine);
            plugin.idle = new CharacterIdleTracker(null, new CharacterIdleModel());
            engine.setBaseline(capture());
        }

        ContainerSnapshot capture() {
            return plugin.containers.capture(false, false);
        }

        /** Use one inventory item on another, as the game sends it. */
        void use(int item, String itemName, int on, String onName) {
            selected = LootingBagMovementTest.widget(item, 149 << 16);
            plugin.onMenuOptionClicked(LootingBagMovementTest.menu("Use", MenuAction.WIDGET_TARGET_ON_WIDGET,
                LootingBagMovementTest.widget(on, 149 << 16),
                "<col=ff9040>" + itemName + "<col=ffffff> -> <col=ff9040>" + onName));
        }

        void become(Map<Integer, Long> held) {
            pack.clear();
            pack.putAll(held);
            plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inventory));
            engine.markDirty();
            engine.processIfDirty(capture(), T0 + 600L * (++tick), null);
            engine.processIfDirty(capture(), T0 + 600L * (++tick), null);
        }

        List<Transaction> rows() {
            return engine.session().transactions();
        }
    }

    @Test
    public void sowingInEitherUseOrderIsMadeNotReview() {
        for (boolean potFirst : new boolean[] {false, true}) {
            Fixture f = new Fixture(Map.of(SEED, 2L, POT, 2L, TROWEL, 1L));
            if (potFirst) f.use(POT, "Filled plant pot", SEED, "Rosewood seed");
            else f.use(SEED, "Rosewood seed", POT, "Filled plant pot");
            f.become(Map.of(SEED, 1L, POT, 1L, TROWEL, 1L, SEEDLING, 1L));

            assertEquals(f.rows().toString(), 1, f.rows().size());
            Transaction sown = f.rows().get(0);
            assertEquals(TransactionType.PROCESSING, sown.type());
            assertTrue(sown.counts());
            assertEquals("Sowing", sown.note());
            assertEquals("the HUD+ tray never says Harvested", "Sowed", HudTray.labelFor(sown.note()));
            assertEquals("the seedling is its sapling", 1L, sown.quantity(SAPLING, true));
            assertEquals(1L, sown.quantity(SEED, false));
            assertEquals(1L, sown.quantity(POT, false));
            assertEquals("no Review", 0, f.engine.getReviewRows(T0 + 100_000L).size());
            assertEquals(140_000L - 135_000L - 40L, f.engine.metrics(T0 + 100_000L).net);
        }
    }

    @Test
    public void wateringAndGrowingBookOnlyTheCan() {
        Fixture f = new Fixture(Map.of(SEEDLING, 1L, CAN_8, 1L));
        f.use(CAN_8, "Watering can(8)", SEEDLING, "Rosewood seedling");
        f.become(Map.of(WATERED, 1L, CAN_7, 1L));
        assertEquals(f.rows().toString(), 1, f.rows().size());
        Transaction watered = f.rows().get(0);
        assertEquals(0L, watered.quantity(SAPLING, true));
        assertEquals(0L, watered.quantity(SAPLING, false));
        assertEquals(1L, watered.quantity(CAN_8, false));

        f.become(Map.of(SAPLING, 1L, CAN_7, 1L));
        assertEquals("growing into the sapling books nothing", 1, f.rows().size());
        assertEquals(0, f.engine.getReviewRows(T0 + 100_000L).size());
    }

    @Test
    public void withoutTheSowClickTheSwapStaysReview() {
        Fixture f = new Fixture(Map.of(SEED, 2L, POT, 2L, TROWEL, 1L));
        f.become(Map.of(SEED, 1L, POT, 1L, TROWEL, 1L, SEEDLING, 1L));
        assertEquals(f.rows().toString(), 1, f.rows().size());
        assertEquals(TransactionType.UNCERTAIN, f.rows().get(0).type());
    }
}
