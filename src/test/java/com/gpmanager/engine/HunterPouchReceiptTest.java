package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.http.api.item.ItemPrice;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Open Hunter pouches, a looting bag and the pack together (independent review of 353f8b31, H1–H3):
 * each catch or pickup books once, measured from the pack as the last tick left it, whichever of
 * the chat line and the inventory callback arrives first; only a looting-bag pickup becomes bag
 * stock, Risk or a PK loss. Every item is worth 100 GP; the clock and ticks are fixed.
 */
public class HunterPouchReceiptTest
{
    private static final long T0 = ReviewFixesTest.T0;
    private static final int MEAT = ItemID.HUNTING_FENNECFOX_MEAT, FUR = ItemID.HUNTING_FENNECFOX_FUR,
        MEAT_POUCH = ItemID.HG_MEATPOUCH_LARGE_OPEN, FUR_POUCH = ItemID.HG_FURPOUCH_LARGE_OPEN,
        LOOTING = ItemID.LOOTING_BAG_OPEN;
    private static final String FOX = "You've caught a pyre fox.";
    private static final Map<Integer, String> NAMES = Map.of(MEAT, "Raw pyre fox", FUR, "Fox fur");

    private LongSupplier previousClock;

    @Before public void fixedClock()
    {
        previousClock = SafeMath.clock;
        SafeMath.clock = () -> T0;
    }

    @After public void restoreClock()
    {
        SafeMath.clock = previousClock;
    }

    /** The pack holds the carried containers and real items; equipment and other containers are empty. */
    static final class Fixture
    {
        final List<Integer> carried = new ArrayList<>();
        final Map<Integer, Long> pack = new HashMap<>();
        int tick = 100;
        final Engine engine = engine();
        final ReviewFixesTest.Plugin plugin = ReviewFixesTest.plugin(engine, new int[] {100});
        final ItemContainer inventory = LootingBagMovementTest.proxy(ItemContainer.class, (method, args) -> {
            if (method.equals("contains")) return carried.contains((int) args[0]) || pack.containsKey((int) args[0]);
            if (method.equals("getItems")) {
                List<Item> items = new ArrayList<>();
                carried.forEach(id -> items.add(new Item(id, 1)));
                pack.forEach((id, quantity) -> items.add(new Item(id, quantity.intValue())));
                return items.toArray(new Item[0]);
            }
            return null;
        });

        Fixture(Integer... containers)
        {
            Collections.addAll(carried, containers);
            Player local = LootingBagMovementTest.proxy(Player.class,
                (method, args) -> method.equals("getWorldLocation") ? new WorldPoint(100, 100, 0) : null);
            plugin.client = LootingBagMovementTest.proxy(Client.class, (method, args) -> {
                switch (method) {
                    case "getItemContainer": return (int) args[0] == InventoryID.INV ? inventory : null;
                    case "getTickCount": return tick;
                    case "getGameState": return GameState.LOGGED_IN;
                    case "getLocalPlayer": return local;
                    default: return null;
                }
            });
            plugin.containers = new ContainerSnapshotFactory(plugin.client, null);
            plugin.lookup = new ItemLookup(null) {
                @Override ItemComposition composition(int id)
                {
                    return ReviewFixesTest.proxy(ItemComposition.class, Map.of("getName", NAMES.getOrDefault(id, "Item " + id)));
                }

                @Override List<ItemPrice> find(String query)
                {
                    List<ItemPrice> found = new ArrayList<>();
                    NAMES.forEach((id, name) -> {
                        if (!name.toLowerCase().contains(query.toLowerCase())) return;
                        ItemPrice price = new ItemPrice();
                        price.setId(id);
                        price.setName(name);
                        found.add(price);
                    });
                    return found;
                }
            };
            engine.setBaseline(capture());
            plugin.lastPack = plugin.counts(inventory);
        }

        /** A Grand Exchange quote of 100 GP each, so the tracked basis follows every booked receipt. */
        static Engine engine()
        {
            Engine engine = new Engine(deltas -> {
                List<Flow> flows = new ArrayList<>();
                deltas.forEach((id, q) -> flows.add(new Flow(id, NAMES.getOrDefault(id, "Item " + id), q, 100, q * 100,
                    PriceSource.GRAND_EXCHANGE)));
                return flows;
            }, new TransactionClassifier(), ReviewFixesTest.CONFIG);
            engine.ensureSession(T0);
            engine.setBaseline(FixtureReads.empty());
            return engine;
        }

        ContainerSnapshot capture()
        {
            return plugin.containers.capture(false, false);
        }

        void chat(String line)
        {
            ChatMessage message = new ChatMessage();
            message.setType(ChatMessageType.SPAM);
            message.setMessage(line);
            plugin.onChatMessage(message);
        }

        /** The player's own loot on the clicked tile vanishes as their Take succeeds. */
        void pickUp(int item)
        {
            engine.markLoot(Map.of(item, 1L), 100, "Loot from Pyre fox", "Pyre fox");
            Observation click = ReviewFixesTest.take(plugin);
            click.item = item;
            click.at = tick;
            click.x = 10;
            click.y = 10;
            Map<String, Object> pile = new HashMap<>();
            pile.put("getId", item);
            pile.put("getQuantity", 1);
            pile.put("getOwnership", TileItem.OWNERSHIP_SELF);
            plugin.onItemDespawned(new ItemDespawned(ReviewFollowupTest.tile(10, 10), ReviewFixesTest.proxy(TileItem.class, pile)));
        }

        /** Production GameTick order: the pack is read first, then a tick-old bag receipt settles. */
        void gameTick()
        {
            plugin.lastPack = plugin.counts(inventory);
            plugin.bagSettle();
            engine.markDirty();
            engine.processIfDirty(capture(), T0 + 600L * tick, null);
            engine.processIfDirty(capture(), T0 + 600L * tick + 1, null);
            tick++;
        }

        void ticks(int count)
        {
            for (int i = 0; i < count; i++) gameTick();
        }

        long income()
        {
            return engine.metrics(T0 + 600L * (tick + 10)).income;
        }

        long net()
        {
            return engine.metrics(T0 + 600L * (tick + 10)).net;
        }
    }

    @Test public void aFullPouchCatchCountsOnceInEitherCallbackOrder()
    {
        for (boolean inventoryFirst : new boolean[] {false, true}) {
            Fixture f = new Fixture(MEAT_POUCH);
            if (inventoryFirst) f.pack.put(MEAT, 1L);
            f.chat(FOX);
            if (!inventoryFirst) f.pack.put(MEAT, 1L);
            f.ticks(3);
            assertEquals("inventory first: " + inventoryFirst, 100, f.income());
            assertTrue(f.engine.bagStock().isEmpty());
        }
    }

    @Test public void aPouchWithRoomCountsTheCatchOnceAndAClosedPouchLeavesItToThePack()
    {
        Fixture open = new Fixture(MEAT_POUCH);
        open.chat(FOX);
        open.ticks(3);
        assertEquals(100, open.income());
        assertEquals(1L, EngineProbe.availableQty(open.engine, MEAT));
        assertEquals(100L, EngineProbe.availableBasisGp(open.engine, MEAT));

        Fixture closed = new Fixture(ItemID.HG_MEATPOUCH_LARGE);
        closed.chat(FOX);
        closed.pack.put(MEAT, 1L);
        closed.ticks(3);
        assertEquals("only the meat in the pack counts", 100, closed.income());
    }

    @Test public void heldStockAndSeveralCatchesKeepTheirOwnQuantities()
    {
        for (boolean inventoryFirst : new boolean[] {false, true}) {
            Fixture f = new Fixture(MEAT_POUCH);
            f.pack.put(MEAT, 3L);
            f.engine.setBaseline(f.capture());
            f.plugin.lastPack = f.plugin.counts(f.inventory);
            // Two catches in one tick: the pouch takes one, the second overflows into the pack.
            if (inventoryFirst) f.pack.put(MEAT, 4L);
            f.chat(FOX);
            f.chat(FOX);
            if (!inventoryFirst) f.pack.put(MEAT, 4L);
            f.ticks(3);
            assertEquals("inventory first: " + inventoryFirst, 200, f.income());
            assertEquals("the two catches are tracked stock; the three held before are not", 2L,
                EngineProbe.availableQty(f.engine, MEAT));
            assertEquals(200L, EngineProbe.availableBasisGp(f.engine, MEAT));
        }
    }

    @Test public void oneCatchFillsBothPouchesWithItsMeatAndFur()
    {
        Fixture f = new Fixture(MEAT_POUCH, FUR_POUCH);
        f.chat(FOX);
        f.ticks(3);
        assertEquals(200, f.income());
        assertEquals(100L, EngineProbe.availableBasisGp(f.engine, MEAT));
        assertEquals(100L, EngineProbe.availableBasisGp(f.engine, FUR));
        assertTrue(f.engine.bagStock().isEmpty());
    }

    @Test public void aCatchBesideAnOpenLootingBagNeverBecomesBagStockRiskOrAPkLoss()
    {
        Fixture f = new Fixture(MEAT_POUCH, FUR_POUCH, LOOTING);
        f.chat(FOX);
        f.ticks(3);
        assertEquals(200, f.income());
        assertTrue("the pouches took the catch", f.engine.bagStock().isEmpty());
        assertTrue(f.plugin.lootingBag().isEmpty());
        Transaction loss = f.engine.markPkDeath(T0 + 100_000, f.plugin.lootingBag(), null, f.engine.events.owner);
        assertTrue("no bag contents to lose", loss == null || loss.net() == 0);
    }

    @Test public void aMeatPickupIntoTheLootingBagIsMeasuredBesidePouches()
    {
        Fixture f = new Fixture(MEAT_POUCH, LOOTING);
        f.pickUp(MEAT);
        f.ticks(3);
        assertEquals("a ground pickup has no catch line; the despawn books it", 100, f.income());
        assertEquals(Map.of(MEAT, 1L), f.engine.bagStock());
    }

    @Test public void aCatchAndAPickupOfTheSameItemKeepTheirOwnReceivers()
    {
        // Both went into bags: the pouch's meat is never looting-bag stock, the picked-up one is.
        Fixture f = new Fixture(MEAT_POUCH, LOOTING);
        f.chat(FOX);
        f.pickUp(MEAT);
        f.ticks(3);
        assertEquals(200, f.income());
        assertEquals(Map.of(MEAT, 1L), f.engine.bagStock());

        // One of the two reached the pack, so which bag took the other is unknown: the money counts
        // once and the looting bag never inherits the catch's quantity.
        Fixture overflow = new Fixture(MEAT_POUCH, LOOTING);
        overflow.chat(FOX);
        overflow.pickUp(MEAT);
        overflow.pack.put(MEAT, 1L);
        overflow.ticks(3);
        assertEquals(200, overflow.income());
        assertTrue(overflow.engine.bagStock().getOrDefault(MEAT, 0L) <= 1);
    }

    @Test public void aBagSnapshotBeforeOrAfterPendingReceiptsIsNotDoubled()
    {
        for (boolean before : new boolean[] {false, true}) {
            Fixture f = new Fixture(MEAT_POUCH, LOOTING);
            f.chat(FOX);
            f.pickUp(MEAT);
            if (before) f.plugin.onItemContainerChanged(new net.runelite.api.events.ItemContainerChanged(
                InventoryID.LOOTING_BAG, bag(Map.of(MEAT, 1L))));
            f.ticks(1);
            if (!before) f.plugin.onItemContainerChanged(new net.runelite.api.events.ItemContainerChanged(
                InventoryID.LOOTING_BAG, bag(Map.of(MEAT, 1L))));
            f.ticks(2);
            assertEquals("snapshot before: " + before, Map.of(MEAT, 1L), f.engine.bagStock());
            assertEquals(200, f.income());
        }
    }

    @Test public void aRecoveredOwnDropIsNotNewLootButAFreshPickupIs()
    {
        Fixture own = new Fixture(LOOTING);
        own.pack.put(MEAT, 1L);
        own.engine.setBaseline(own.capture());
        own.engine.session().addTransaction(Tx.of(T0, TransactionType.GAIN, Context.GENERIC, "Initial loot", true,
            List.of(new Flow(MEAT, "Raw pyre fox", 1, 100, 100, PriceSource.GRAND_EXCHANGE))), 2_000, false);
        own.engine.noteDrop(MEAT, 6);
        own.pack.remove(MEAT);
        own.ticks(3);
        assertEquals("the drop books its loss", 0, own.net());
        own.plugin.lastPack = own.plugin.counts(own.inventory);
        own.plugin.bagGain(MEAT, 1, true);
        own.ticks(3);
        assertEquals("picking our own drop back up restores it, never new income", 100, own.net());
        assertEquals(Map.of(MEAT, 1L), own.engine.bagStock());

        Fixture fresh = new Fixture(LOOTING);
        fresh.pickUp(MEAT);
        fresh.ticks(3);
        assertEquals(100, fresh.income());
    }

    @Test public void aFencedTickReadsTheNewPackAndDropsPendingReceipts()
    {
        Fixture f = new Fixture(MEAT_POUCH);
        f.pack.put(MEAT, 5L);
        f.chat(FOX);
        f.plugin.ready = false;
        f.plugin.onGameTick(new GameTick());
        assertEquals(0L, ReviewFixesTest.qty(f.plugin, Inbox.BAG_GAIN));
        assertEquals("the next account is measured from its own pack", Long.valueOf(5), f.plugin.lastPack.get(MEAT));
    }

    @Test public void aPausedRunBooksNoPouchReceipt()
    {
        Fixture f = new Fixture(MEAT_POUCH);
        f.engine.session().pause(T0, PauseReason.MANUAL);
        f.chat(FOX);
        f.ticks(3);
        assertNotNull(f.engine.session());
        assertEquals(0, f.income());
    }

    static ItemContainer bag(Map<Integer, Long> contents)
    {
        return LootingBagMovementTest.proxy(ItemContainer.class, (method, args) -> method.equals("getItems")
            ? contents.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue().intValue())).toArray(Item[]::new) : null);
    }
}
