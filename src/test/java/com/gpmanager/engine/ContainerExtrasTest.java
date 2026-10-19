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

/** Items held outside the inventory and worn slots still count (research 1.2). */
public class ContainerExtrasTest
{
    @Test
    public void dizanasQuiverAmmoCountsAsCarried() throws Exception
    {
        QuiverSupportTest.Harness harness = new QuiverSupportTest.Harness();
        harness.equipQuiver();
        harness.ammo = 250;
        assertEquals(250L, harness.capture().quantityOf(ItemID.DRAGON_ARROW));
    }

    @Test
    public void aCarriedAshSanctifierHoldsDeathRunesTenChargesEach()
    {
        ContainerSnapshot held = capture(Map.of(InventoryID.INV, items(ItemID.ASH_SANCTIFIER, 1, ItemID.DEATHRUNE, 40)), 125);
        assertEquals("40 loose runes and 125 charges = 12 more runes", 52L, held.quantityOf(ItemID.DEATHRUNE));
        ContainerSnapshot banked = capture(Map.of(InventoryID.INV, items(ItemID.DEATHRUNE, 40)), 125);
        assertEquals("a banked sanctifier's charges are not carried", 40L, banked.quantityOf(ItemID.DEATHRUNE));
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

    private static ContainerSnapshot capture(Map<Integer, Item[]> containers, int sanctifierCharges)
    {
        Map<Integer, ItemContainer> byId = new HashMap<>();
        containers.forEach((id, items) -> byId.put(id, (ItemContainer) Proxy.newProxyInstance(
            ItemContainer.class.getClassLoader(), new Class<?>[]{ItemContainer.class},
            (proxy, method, args) -> "getItems".equals(method.getName()) ? items : null)));
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> {
                if ("getItemContainer".equals(method.getName()))
                {
                    return byId.get((int) args[0]);
                }
                if ("getVarbitValue".equals(method.getName()))
                {
                    return (int) args[0] == VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY ? sanctifierCharges : 0;
                }
                return null;
            });
        return new ContainerSnapshotFactory(client, null).capture(true, false);
    }
}
