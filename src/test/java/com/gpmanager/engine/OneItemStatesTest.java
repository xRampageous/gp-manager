package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Opening a bag or lighting a lantern swaps the item id; GP Manager reads both states as one item
 * (owner 1.2, after a "Light" click booked a lantern's whole price as a cost).
 */
public class OneItemStatesTest
{
    private static Map<Integer, Integer> expected()
    {
        int[][] pairs = {
            {ItemID.LIT_BLACK_CANDLE, ItemID.UNLIT_BLACK_CANDLE}, {ItemID.LIT_CANDLE, ItemID.UNLIT_CANDLE},
            {ItemID.TORCH_LIT, ItemID.TORCH_UNLIT}, {ItemID.OIL_LAMP_LIT, ItemID.OIL_LAMP_UNLIT},
            {ItemID.CANDLE_LANTERN_LIT, ItemID.CANDLE_LANTERN_UNLIT},
            {ItemID.CANDLE_LANTERN_BLACK_LIT, ItemID.CANDLE_LANTERN_BLACK_UNLIT},
            {ItemID.OIL_LANTERN_LIT, ItemID.OIL_LANTERN_UNLIT}, {ItemID.BULLSEYE_LANTERN_LIT, ItemID.BULLSEYE_LANTERN_UNLIT},
            {ItemID.TOG_SAPPHIRE_LANTERN_LIT, ItemID.TOG_SAPPHIRE_LANTERN_UNLIT},
            {ItemID.CAVE_GOBLIN_MINING_HELMET_LIT, ItemID.CAVE_GOBLIN_MINING_HELMET_UNLIT},
            {ItemID.BULLSEYE_LANTERN_LIT_LUNAR_QUEST, ItemID.BULLSEYE_LANTERN_UNLIT_LUNAR_QUEST},
            {ItemID.TRAIL_LIGHT_LIT, ItemID.TRAIL_LIGHT_UNLIT},
            {ItemID.LOOTING_BAG_OPEN, ItemID.LOOTING_BAG}, {ItemID.SLAYER_HERB_SACK_OPEN, ItemID.SLAYER_HERB_SACK},
            {ItemID.COAL_BAG_OPEN, ItemID.COAL_BAG}, {ItemID.GEM_BAG_OPEN, ItemID.GEM_BAG},
            {ItemID.SEED_BOX_OPEN, ItemID.SEED_BOX},
            {ItemID.SHADES_COFFIN_BRONZE_OPEN, ItemID.SHADES_COFFIN_BRONZE},
            {ItemID.SHADES_COFFIN_STEEL_OPEN, ItemID.SHADES_COFFIN_STEEL},
            {ItemID.SHADES_COFFIN_BLACK_OPEN, ItemID.SHADES_COFFIN_BLACK},
            {ItemID.SHADES_COFFIN_SILVER_OPEN, ItemID.SHADES_COFFIN_SILVER},
            {ItemID.SHADES_COFFIN_GOLD_OPEN, ItemID.SHADES_COFFIN_GOLD},
            {ItemID.FISH_BARREL_OPEN, ItemID.FISH_BARREL_CLOSED}, {ItemID.FISH_SACK_BARREL_OPEN, ItemID.FISH_SACK_BARREL_CLOSED},
            {ItemID.LOG_BASKET_OPEN, ItemID.LOG_BASKET_CLOSED}, {ItemID.FORESTRY_BASKET_OPEN, ItemID.FORESTRY_BASKET_CLOSED},
            {ItemID.HG_MEATPOUCH_SMALL_OPEN, ItemID.HG_MEATPOUCH_SMALL},
            {ItemID.HG_MEATPOUCH_LARGE_OPEN, ItemID.HG_MEATPOUCH_LARGE},
            {ItemID.HG_FURPOUCH_SMALL_OPEN, ItemID.HG_FURPOUCH_SMALL}, {ItemID.HG_FURPOUCH_MED_OPEN, ItemID.HG_FURPOUCH_MED},
            {ItemID.HG_FURPOUCH_LARGE_OPEN, ItemID.HG_FURPOUCH_LARGE},
            {ItemID.MM_SECONDARY_POUCH_OPEN, ItemID.MM_SECONDARY_POUCH},
            {ItemID.SLAYER_HERB_SACK_SILK_OPEN, ItemID.SLAYER_HERB_SACK_SILK},
            {ItemID.GEM_POUCH_OPEN, ItemID.GEM_POUCH}, {ItemID.GEM_SATCHEL_OPEN, ItemID.GEM_SATCHEL},
            {ItemID.GEM_TOTE_OPEN, ItemID.GEM_TOTE}, {ItemID.GEM_SACK_OPEN, ItemID.GEM_SACK},
            {ItemID.NZONE_HERBBOX_OPENED, ItemID.NZONE_HERBBOX},
            {ItemID.BOTTOMLESS_MILK_BUCKET_FILLED, ItemID.BOTTOMLESS_MILK_BUCKET}};
        Map<Integer, Integer> map = new HashMap<>();
        for (int[] pair : pairs)
        {
            map.put(pair[0], pair[1]);
        }
        return map;
    }

    @Test
    public void theTableMatchesRuneLiteConstants()
    {
        assertEquals(expected(), ContainerSnapshotFactory.ONE_ITEM);
    }

    @Test
    public void lightingALanternOrOpeningABagChangesNoSnapshot()
    {
        ContainerSnapshot before = snapshot(ItemID.BULLSEYE_LANTERN_UNLIT, ItemID.SLAYER_HERB_SACK, ItemID.SHARK);
        ContainerSnapshot after = snapshot(ItemID.BULLSEYE_LANTERN_LIT, ItemID.SLAYER_HERB_SACK_OPEN, ItemID.SHARK);
        assertEquals(before, after);
        assertEquals("the engine sees no change to book", Map.of(), after.diff(before));
        assertEquals("ordinary items keep their id", ItemID.SHARK,
            new ContainerSnapshotFactory(null, null).canonical(ItemID.SHARK));
    }

    private static ContainerSnapshot snapshot(int... ids)
    {
        Item[] items = new Item[ids.length];
        for (int i = 0; i < ids.length; i++)
        {
            items[i] = new Item(ids[i], 1);
        }
        ItemContainer inventory = (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
            new Class<?>[]{ItemContainer.class}, (proxy, method, args) -> "getItems".equals(method.getName()) ? items : null);
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> "getItemContainer".equals(method.getName()) && (int) args[0] == InventoryID.INV
                ? inventory : null);
        return new ContainerSnapshotFactory(client, null).capture(false, false);
    }
}
