package com.gpmanager.engine;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Rune pouch runes are account state in live varbits, not an item container. Merging
 * them into the ownership baseline makes filling the pouch neutral and casting from it
 * a visible cost. Looting bag / seed box are not merged: the client only syncs those
 * containers when the bag is opened.
 */
public class ContainerSnapshotFactoryRunePouchTest
{
    private static final int FIRE_RUNE = 554;
    private static final int CHAOS_RUNE = 562;

    @Test
    public void pouchRunesJoinInventoryQuantitiesWhenEnabled()
    {
        Map<Integer, Integer> varbits = new HashMap<>();
        varbits.put(VarbitID.RUNE_POUCH_TYPE_1, 4); // enum index -> Fire rune
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 1_500);
        varbits.put(VarbitID.RUNE_POUCH_TYPE_2, 10); // -> Chaos rune
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_2, 40);
        varbits.put(VarbitID.RUNE_POUCH_TYPE_3, 0); // empty slot
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_3, 99);
        Map<Integer, Integer> runeEnum = new HashMap<>();
        runeEnum.put(4, FIRE_RUNE);
        runeEnum.put(10, CHAOS_RUNE);
        Client client = client(varbits, runeEnum, new Item(FIRE_RUNE, 20), new Item(995, 100));
        ContainerSnapshotFactory factory = new ContainerSnapshotFactory(client, null);

        ContainerSnapshot with = factory.capture(false, true);
        ContainerSnapshot without = factory.capture(false, false);

        assertTrue(factory.hasRunePouchContents());
        assertEquals(1_520L, with.quantityOf(FIRE_RUNE));
        assertEquals(40L, with.quantityOf(CHAOS_RUNE));
        assertEquals(100L, with.quantityOf(995));
        assertEquals(20L, without.quantityOf(FIRE_RUNE));
        assertEquals(0L, without.quantityOf(CHAOS_RUNE));
        // Empty slot with a stale quantity contributes nothing.
        assertEquals(3, with.diff(new ContainerSnapshot(new HashMap<>())).size());
    }

    @Test
    public void emptyRunePouchDoesNotReportHiddenContents()
    {
        Map<Integer, Integer> varbits = new HashMap<>();
        varbits.put(VarbitID.RUNE_POUCH_TYPE_1, 4);
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 0);
        ContainerSnapshotFactory factory = new ContainerSnapshotFactory(
            client(varbits, new HashMap<>(), new Item(995, 7)), null);

        assertFalse(factory.hasRunePouchContents());
    }

    @Test
    public void castingFromThePouchIsAVisibleCostAndFillingIsNeutral()
    {
        Map<Integer, Integer> varbits = new HashMap<>();
        varbits.put(VarbitID.RUNE_POUCH_TYPE_1, 4);
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 100);
        Map<Integer, Integer> runeEnum = new HashMap<>();
        runeEnum.put(4, FIRE_RUNE);
        ContainerSnapshot before = new ContainerSnapshotFactory(
            client(varbits, runeEnum, new Item(FIRE_RUNE, 50)), null).capture(false, true);

        // Fill: 50 runes move from the inventory into the pouch.
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 150);
        ContainerSnapshot filled = new ContainerSnapshotFactory(client(varbits, runeEnum), null)
            .capture(false, true);
        assertTrue("fill nets to zero", filled.diff(before).isEmpty());

        // Cast: five runes leave the pouch with no inventory change at all.
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 145);
        ContainerSnapshot cast = new ContainerSnapshotFactory(client(varbits, runeEnum), null)
            .capture(false, true);
        assertEquals(Long.valueOf(-5L), cast.diff(filled).get(FIRE_RUNE));
    }

    @Test
    public void onlyPouchVarbitsAreRecognised()
    {
        assertTrue(ContainerSnapshotFactory.isRunePouchVarbit(VarbitID.RUNE_POUCH_TYPE_1));
        assertTrue(ContainerSnapshotFactory.isRunePouchVarbit(VarbitID.RUNE_POUCH_QUANTITY_6));
        assertFalse(ContainerSnapshotFactory.isRunePouchVarbit(VarbitID.LOOTINGBAG_USEALLITEMS));
        assertFalse(ContainerSnapshotFactory.isRunePouchVarbit(-1));
    }

    @Test
    public void missingEnumNeverBreaksTheSnapshot()
    {
        Map<Integer, Integer> varbits = new HashMap<>();
        varbits.put(VarbitID.RUNE_POUCH_TYPE_1, 4);
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 100);
        ContainerSnapshot snapshot = new ContainerSnapshotFactory(
            client(varbits, null, new Item(995, 7)), null).capture(false, true);
        assertEquals(7L, snapshot.quantityOf(995));
        assertNull(snapshot.diff(new ContainerSnapshot(new HashMap<>())).get(FIRE_RUNE));
    }

    @Test
    public void deathHeldSnapshotReadsInventoryAndOptionalEquipmentButNeverRunePouchVarbits()
    {
        Map<Integer, Integer> varbits = new HashMap<>();
        varbits.put(VarbitID.RUNE_POUCH_TYPE_1, 4);
        varbits.put(VarbitID.RUNE_POUCH_QUANTITY_1, 900);
        ItemContainer inventory = proxy(ItemContainer.class, (name, args) ->
            "getItems".equals(name) ? new Item[] {new Item(FIRE_RUNE, 20), new Item(995, 50)} : null);
        ItemContainer worn = proxy(ItemContainer.class, (name, args) ->
            "getItems".equals(name) ? new Item[] {new Item(4151, 1)} : null);
        Client client = proxy(Client.class, (name, args) ->
        {
            if ("getItemContainer".equals(name) && args != null && args.length == 1)
            {
                int id = (Integer) args[0];
                return id == InventoryID.INV ? inventory : id == InventoryID.WORN ? worn : null;
            }
            if ("getVarbitValue".equals(name))
            {
                return varbits.getOrDefault((Integer) args[0], 0);
            }
            return null;
        });
        ContainerSnapshotFactory factory = new ContainerSnapshotFactory(client, null);

        ContainerSnapshot withEquipment = factory.captureDeathHeldItems(true);
        ContainerSnapshot withoutEquipment = factory.captureDeathHeldItems(false);

        assertEquals(20L, withEquipment.quantityOf(FIRE_RUNE));
        assertEquals(50L, withEquipment.quantityOf(995));
        assertEquals(1L, withEquipment.quantityOf(4151));
        assertEquals(0L, withEquipment.quantityOf(CHAOS_RUNE));
        assertEquals(0L, withoutEquipment.quantityOf(4151));
    }

    private static Client client(Map<Integer, Integer> varbits, Map<Integer, Integer> runeEnum, Item... inventory)
    {
        ItemContainer inv = proxy(ItemContainer.class, (name, args) ->
            "getItems".equals(name) ? inventory : null);
        EnumComposition composition = runeEnum == null ? null : proxy(EnumComposition.class, (name, args) ->
            "getIntValue".equals(name) ? runeEnum.getOrDefault((Integer) args[0], -1) : null);
        return proxy(Client.class, (name, args) ->
        {
            if ("getItemContainer".equals(name) && args != null && args.length == 1 && args[0] instanceof Integer)
            {
                return (Integer) args[0] == InventoryID.INV ? inv : null;
            }
            if ("getVarbitValue".equals(name))
            {
                return varbits.getOrDefault((Integer) args[0], 0);
            }
            if ("getEnum".equals(name))
            {
                return (Integer) args[0] == EnumID.RUNEPOUCH_RUNE ? composition : null;
            }
            return null;
        });
    }

    private interface Answer
    {
        Object answer(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Answer answer)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (obj, method, args) ->
        {
            Object value = answer.answer(method.getName(), args);
            if (value != null || !method.getReturnType().isPrimitive())
            {
                return value;
            }
            if (method.getReturnType() == boolean.class)
            {
                return false;
            }
            if (method.getReturnType() == long.class)
            {
                return 0L;
            }
            return 0;
        }));
    }
}
