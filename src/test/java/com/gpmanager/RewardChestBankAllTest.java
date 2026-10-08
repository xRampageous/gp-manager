package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** "Bank-all" on a reward chest books its loot, which never passes the inventory (research 1.2). */
public class RewardChestBankAllTest
{
    @Test
    public void theChestTableMatchesRuneLiteConstants()
    {
        Map<Integer, Integer> expected = new HashMap<>();
        expected.put(InterfaceID.RAIDS_REWARDS, InventoryID.RAIDS_REWARDS);
        expected.put(InterfaceID.TOB_CHESTS, InventoryID.TOB_CHESTS);
        expected.put(InterfaceID.TOA_CHESTS, InventoryID.TOA_CHESTS);
        expected.put(InterfaceID.COLOSSEUM_REWARD_CHEST_2, InventoryID.COLOSSEUM_REWARDS);
        expected.put(InterfaceID.PMOON_REWARD, InventoryID.PMOON_REWARDINV);
        expected.put(InterfaceID.WILDY_LOOT_CHEST, InventoryID.LOOT_INV_ACCESS);
        expected.put(InterfaceID.DOM_END_LEVEL_UI, InventoryID.DOM_LOOTPILE);
        expected.put(InterfaceID.TRAWLER_REWARD, InventoryID.TRAWLER_REWARDINV);
        expected.put(InterfaceID.FOSSIL_DRIFTNET, InventoryID.MACRO_CERTER);
        assertEquals(expected, GpManagerPlugin.CHESTS);
    }

    /** Codex research 1.2: only what really leaves the chest after a Bank-all is booked, once. */
    @Test
    public void bankAllBooksWhatLeavesTheChestOnceAndIgnoresOtherContainers()
    {
        Am engine = ReviewFixesTest.engine();
        int[] tick = {100};
        Item[][] chest = {{new Item(ItemID.TWISTED_BOW, 1), new Item(ItemID.DRAGON_ARROW, 50), null}};
        ItemContainer rewards = (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
            new Class<?>[]{ItemContainer.class}, (proxy, method, args) -> "getItems".equals(method.getName()) ? chest[0] : null);
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> {
                if ("getTickCount".equals(method.getName()))
                {
                    return tick[0];
                }
                if ("getItemContainer".equals(method.getName()))
                {
                    return (int) args[0] == InventoryID.RAIDS_REWARDS ? rewards : null;
                }
                return null;
            });
        GpManagerPlugin plugin = new GpManagerPlugin();
        plugin.engine = engine;
        plugin.client = client;
        plugin.snapshotFactory = new ContainerSnapshotFactory(client, null);

        plugin.bankAll(InterfaceID.RAIDS_REWARDS);
        assertEquals("the click alone books nothing", 0L, engine.baseline.aea(ItemID.TWISTED_BOW));
        plugin.chestChanged(InventoryID.RAIDS_REWARDS, rewards);
        assertEquals("a refused Bank-all moves nothing", 0L, engine.baseline.aea(ItemID.TWISTED_BOW));

        chest[0] = new Item[]{new Item(ItemID.DRAGON_ARROW, 20)};
        tick[0]++;
        plugin.chestChanged(InventoryID.RAIDS_REWARDS, rewards);
        assertEquals("the bow reads as picked up", -1L, engine.baseline.aea(ItemID.TWISTED_BOW));
        assertEquals("only the arrows that left", -30L, engine.baseline.aea(ItemID.DRAGON_ARROW));

        tick[0] += 6;
        plugin.bankAll(InterfaceID.RAIDS_REWARDS);
        plugin.chestChanged(InventoryID.RAIDS_REWARDS, rewards);
        assertEquals("a repeated click books nothing again", -1L, engine.baseline.aea(ItemID.TWISTED_BOW));
        assertEquals(-30L, engine.baseline.aea(ItemID.DRAGON_ARROW));

        chest[0] = new Item[0];
        plugin.chestChanged(InventoryID.BANK, rewards);
        assertEquals("another container is not the chest", -30L, engine.baseline.aea(ItemID.DRAGON_ARROW));
    }
}
