package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Taking items out of a tackle box (or another kit with its own window) is neutral (owner 1.2). */
public class KitWithdrawTest
{
    static ItemContainer holding(Item... items)
    {
        return (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
            new Class<?>[]{ItemContainer.class}, (proxy, method, args) -> "getItems".equals(method.getName()) ? items : null);
    }

    static Am withdraw(int kitEvents)
    {
        Am engine = HudBuilderTest.engine();
        engine.rm(1_000_000L);
        engine.setBaseline(new Cc(new HashMap<>()));
        GpManagerPlugin plugin = new GpManagerPlugin();
        plugin.engine = engine;
        if (kitEvents > 0)
        {
            plugin.kit(InventoryID.TACKLE_BOX, holding(new Item(ItemID.DRAGON_HARPOON, 1)));
        }
        if (kitEvents > 1)
        {
            plugin.kit(InventoryID.TACKLE_BOX, holding());
        }
        engine.yz();
        for (int tick = 1; tick <= 5; tick++)
        {
            engine.adj(new Cc(new HashMap<>(Map.of(ItemID.DRAGON_HARPOON, 1L))), 1_000_000L + tick * 600L);
        }
        return engine;
    }

    @Test
    public void takingAHarpoonOutOfTheTackleBoxIsNotLoot()
    {
        Am engine = withdraw(2);
        assertEquals(0L, engine.getMetrics(1_010_000L).net);
        assertTrue(engine.getActiveSession().getTransactions().toString(),
            engine.getActiveSession().getTransactions().stream().noneMatch(Ac::isCounted));
    }

    @Test
    public void withoutTheKitChangingTheSameItemCountsAsAGain()
    {
        assertTrue(withdraw(0).getMetrics(1_010_000L).net > 0L);
    }

    @Test
    public void openingAKitForTheFirstTimeIsNotEvidence()
    {
        assertTrue("a kit loading is not a withdrawal", withdraw(1).getMetrics(1_010_000L).net > 0L);
    }
}
