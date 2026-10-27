package com.gpmanager;

import java.lang.reflect.Proxy;
import java.util.function.LongSupplier;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 1.7.0 negative controls for The Fractured Archive's Alliance Supplies (the Quartermaster's
 * shop, OSRS Wiki revision 15378420), apart from the reward chest. RuneLite 1.13.3 names its
 * containers TFA_MIDRAID_SUPPLIES_SHOP (1030) and TFA_SUPPLIES_SNAPSHOT_INV (1031) and the Supply
 * Credits balance TFA_SUPPLY_POINTS. Opening, filtering or revisiting the window and watching its
 * stock or credits book nothing: no gain, cost, death fee, raid receipt or Review. Credits are not
 * GP. How a credit exchange is accounted is unchanged and still awaits a native capture.
 */
public class AllianceSuppliesTest
{
    LongSupplier previous;

    @Before
    public void clock()
    {
        previous = SafeMath.clock;
        SafeMath.clock = () -> FracturedArchiveReadinessTest.T0;
    }

    @After
    public void restoreClock()
    {
        SafeMath.clock = previous;
    }

    @Test
    public void theSupplyIdentifiersAreTheRuneLiteOnes()
    {
        assertEquals(1030, InventoryID.TFA_MIDRAID_SUPPLIES_SHOP);
        assertEquals(1031, InventoryID.TFA_SUPPLIES_SNAPSHOT_INV);
        assertEquals(16381, VarbitID.TFA_SUPPLY_POINTS);
        assertTrue("the supplies are not a Bank-all reward chest",
            !GpManagerPlugin.CHESTS.containsValue(InventoryID.TFA_MIDRAID_SUPPLIES_SHOP)
                && !GpManagerPlugin.CHESTS.containsValue(InventoryID.TFA_SUPPLIES_SNAPSHOT_INV));
    }

    @Test
    public void openingAndWatchingAllianceSuppliesBooksNothing()
    {
        FracturedArchiveReadinessTest.Fixture f = new FracturedArchiveReadinessTest.Fixture();
        Item[][] stock = {{new Item(ItemID._4DOSEPOTIONOFSARADOMIN, 6), new Item(ItemID._4DOSE2RESTORE, 4),
            new Item(ItemID.MANTARAY, 10)}};
        ItemContainer shop = (ItemContainer) Proxy.newProxyInstance(ItemContainer.class.getClassLoader(),
            new Class<?>[]{ItemContainer.class}, (p, m, a) -> "getItems".equals(m.getName()) ? stock[0] : null);
        for (int visit = 0; visit < 3; visit++)
        {
            f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.TFA_MIDRAID_SUPPLIES_SHOP, shop));
            f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.TFA_SUPPLIES_SNAPSHOT_INV, shop));
            VarbitChanged credits = new VarbitChanged();
            credits.setVarbitId(VarbitID.TFA_SUPPLY_POINTS);
            credits.setValue(15 - visit);
            f.plugin.onVarbitChanged(credits);
            stock[0] = new Item[]{new Item(ItemID._4DOSEPOTIONOFSARADOMIN, 5 - visit)};
            f.tick++;
        }
        f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.TFA_MIDRAID_SUPPLIES_SHOP, null));

        assertTrue("no receipt of any kind", f.engine.session().transactions().isEmpty());
        assertEquals(0L, f.engine.metrics(FracturedArchiveReadinessTest.T0).net);
        assertTrue("no Review", f.engine.getReviewRows(FracturedArchiveReadinessTest.T0).isEmpty());
        f.engine.raids.read("all");
        assertEquals("no raid loot or death fee", 0L, f.engine.raids.money("3")[0] + f.engine.raids.money("3")[1]);
        assertTrue("nothing rides the tray", f.plugin.hud.tray.entries().isEmpty());
    }
}
