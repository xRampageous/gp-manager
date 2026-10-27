package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Carried items that hold their charges as items: the Fractured Archive rewards and the ash sanctifier. */
public class StoredChargesTest
{
    @Test
    public void everyTableIdIsTheNamedRuneLiteConstant() throws Exception
    {
        int rows = 0;
        for (String[] row : GameData.rows("stored_charges"))
        {
            rows++;
            for (int column = 0; column < row.length; column++)
            {
                if (!row[column].contains("="))
                {
                    continue;
                }
                String[] cell = row[column].split("=");
                Class<?> constants = column == 1 ? VarbitID.class : ItemID.class;
                assertEquals(row[column], Integer.parseInt(cell[0]), constants.getField(cell[1]).getInt(null));
            }
        }
        assertEquals("ash sanctifier, Obligator, folio, tome and cowbell amulet", 5, rows);
    }

    @Test
    public void carriedItemsCountTheirChargesAsComponents()
    {
        ContainerSnapshot obligator = capture(Map.of(InventoryID.WORN, items(ItemID.OBLIGATOR, 1),
            InventoryID.INV, items(ItemID.NATURERUNE, 5)), Map.of(VarbitID.CHARGES_OBLIGATOR_QUANTITY, 1_234));
        assertEquals("1,234 charges hold 123 whole shards", 123L, obligator.quantity(ItemID.ASCENSION_SHARDS));
        assertEquals("two nature runes a charge, plus 5 loose", 2_473L, obligator.quantity(ItemID.NATURERUNE));
        assertEquals(2_468L, obligator.quantity(ItemID.DEATHRUNE));

        ContainerSnapshot folio = capture(Map.of(InventoryID.INV, items(ItemID.ARCHIVISTS_FOLIO, 1)),
            Map.of(VarbitID.CHARGES_ARCHIVISTS_FOLIO_QUANTITY, 12));
        assertEquals("five teleports a shard", 2L, folio.quantity(ItemID.ASCENSION_SHARDS));

        ContainerSnapshot tome = capture(Map.of(InventoryID.WORN, items(ItemID.ZORYAS_TOME, 1)),
            Map.of(VarbitID.CHARGES_ZORYAS_TOME_QUANTITY, 100));
        assertEquals(300L, tome.quantity(ItemID.ASTRALRUNE));
        assertEquals(200L, tome.quantity(ItemID.NATURERUNE));
    }

    @Test
    public void bankedOrUnchargedItemsHoldNothing()
    {
        Map<Integer, Integer> charges = Map.of(VarbitID.CHARGES_OBLIGATOR_QUANTITY, 500,
            VarbitID.CHARGES_ZORYAS_TOME_QUANTITY, 500, VarbitID.CHARGES_ARCHIVISTS_FOLIO_QUANTITY, 500);
        ContainerSnapshot banked = capture(Map.of(InventoryID.INV, items(ItemID.DEATHRUNE, 40)), charges);
        assertEquals(40L, banked.quantity(ItemID.DEATHRUNE));
        assertEquals(0L, banked.quantity(ItemID.ASCENSION_SHARDS));
        ContainerSnapshot uncharged = capture(Map.of(InventoryID.WORN, items(ItemID.OBLIGATOR_UNCHARGED, 1,
            ItemID.ZORYAS_TOME_UNCHARGED, 1), InventoryID.INV, items(ItemID.ARCHIVISTS_FOLIO_EMPTY, 1)), charges);
        assertEquals(0L, uncharged.quantity(ItemID.NATURERUNE));
        assertEquals(0L, uncharged.quantity(ItemID.ASCENSION_SHARDS));
    }

    @Test
    public void chargesTheGameCountsAreTheVarbitsThePluginWatches()
    {
        for (int varbit : new int[] {VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY, VarbitID.CHARGES_OBLIGATOR_QUANTITY,
            VarbitID.CHARGES_ARCHIVISTS_FOLIO_QUANTITY, VarbitID.CHARGES_ZORYAS_TOME_QUANTITY,
            VarbitID.CHARGES_COWBELL_AMULET_QUANTITY})
        {
            assertTrue(String.valueOf(varbit), ContainerSnapshotFactory.storedCharges(varbit));
        }
    }

    @Test
    public void spendingChargesIsOneSupplyCostAndChargingBooksNothing()
    {
        Engine engine = ReviewFixesTest.engine();
        long now = ReviewFixesTest.T0 + 1_000;
        Map<Integer, Item[]> held = Map.of(InventoryID.WORN, items(ItemID.OBLIGATOR, 1),
            InventoryID.INV, items(ItemID.ASCENSION_SHARDS, 2, ItemID.NATURERUNE, 40, ItemID.DEATHRUNE, 40));
        engine.setBaseline(capture(held, Map.of(VarbitID.CHARGES_OBLIGATOR_QUANTITY, 100)));

        // Charging with 2 shards, 40 nature and 40 death runes adds 20 charges: the items only move.
        Map<Integer, Item[]> charged = Map.of(InventoryID.WORN, items(ItemID.OBLIGATOR, 1));
        engine.markDirty();
        assertNull("charging is not a cost", settle(engine,
            capture(charged, Map.of(VarbitID.CHARGES_OBLIGATOR_QUANTITY, 120)), now));
        assertEquals(0L, engine.metrics(now + 10_000).net);

        // Ten attacks spend ten charges: one shard, twenty nature and twenty death runes.
        engine.markDirty();
        Transaction spend = settle(engine, capture(charged, Map.of(VarbitID.CHARGES_OBLIGATOR_QUANTITY, 110)),
            now + 20_000);
        assertNotNull(spend);
        assertEquals(TransactionType.CONSUMPTION, spend.type());
        assertEquals("spent, counted as a cost", 1L, spend.quantity(ItemID.ASCENSION_SHARDS, false));
        assertEquals(20L, spend.quantity(ItemID.NATURERUNE, false));
        assertEquals(20L, spend.quantity(ItemID.DEATHRUNE, false));
        assertEquals("41 items at the fixture's 100 gp", -4_100L, engine.metrics(now + 40_000).net);
    }

    private static Transaction settle(Engine engine, ContainerSnapshot next, long at)
    {
        Transaction booked = null;
        for (int tick = 0; tick < 8 && booked == null; tick++)
        {
            booked = engine.processIfDirty(next, at + tick * 600L, null);
        }
        return booked;
    }

    private static Item[] items(int... idQuantity)
    {
        Item[] items = new Item[idQuantity.length / 2];
        for (int i = 0; i < items.length; i++)
        {
            items[i] = new Item(idQuantity[2 * i], idQuantity[2 * i + 1]);
        }
        return items;
    }

    private static ContainerSnapshot capture(Map<Integer, Item[]> containers, Map<Integer, Integer> varbits)
    {
        Map<Integer, ItemContainer> byId = new HashMap<>();
        containers.forEach((id, items) -> byId.put(id, (ItemContainer) Proxy.newProxyInstance(
            ItemContainer.class.getClassLoader(), new Class<?>[]{ItemContainer.class},
            (proxy, method, args) -> "getItems".equals(method.getName()) ? items : null)));
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "getItemContainer":
                        return byId.get((int) args[0]);
                    case "getVarbitValue":
                        return varbits.getOrDefault((int) args[0], 0);
                    default:
                        return null;
                }
            });
        return new ContainerSnapshotFactory(client, null).capture(true, false);
    }
}
