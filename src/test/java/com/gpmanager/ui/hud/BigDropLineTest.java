package com.gpmanager;

import com.gpmanager.GpManagerConfig.LootFilter;

import org.junit.Test;

import static com.gpmanager.HudTrayTest.flow;
import static com.gpmanager.HudTrayTest.receipt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** HUD+ 1.2: the gold big-drop line follows Ground Items' insane value while the display follows it. */
public class BigDropLineTest
{
    static LootPresentationFilterService groundItems(boolean enabled, long insane)
    {
        GroundItemsPrefs read = new GroundItemsPrefs(enabled, "", "", false);
        read.insane = insane;
        return new LootPresentationFilterService(read);
    }

    @Test
    public void followingGroundItemsUsesItsInsaneValue()
    {
        assertEquals(1_000_000L, groundItems(true, 1_000_000L).big(LootFilter.FOLLOW_GROUND_ITEMS));
        assertEquals("other displays keep 10M", HudTray.BIG_DROP, groundItems(true, 1_000_000L).big(LootFilter.ALL_ITEMS));
        assertEquals("Ground Items off keeps 10M", HudTray.BIG_DROP, groundItems(false, 1_000_000L).big(LootFilter.FOLLOW_GROUND_ITEMS));
        assertEquals("Ground Items' own default is 10M", HudTray.BIG_DROP, new GroundItemsPrefs(true, "", "", false).insane);
    }

    @Test
    public void theTrayLightsGoldFromTheLineItWasGiven()
    {
        HudTray tray = new HudTray();
        tray.big = 1_000_000L;
        tray.booked(receipt(1_000L, TransactionType.LOOT, flow(4151, "Abyssal whip", 1L, 1_500_000)), 1_000L, false);
        assertTrue(tray.entries().get(0).gold);
        tray.big = HudTray.BIG_DROP;
        tray.booked(receipt(2_000L, TransactionType.LOOT, flow(11840, "Dragon boots", 1L, 1_500_000)), 2_000L, false);
        assertFalse(tray.entries().get(0).gold);
    }
}
