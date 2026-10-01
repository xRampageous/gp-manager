package com.gpmanager;

import java.util.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecentVisibilityTest
{
    @Test
    public void itemMenuHidesRecentOnlyAndSavedChoiceCanBeRemoved()
    {
        ConfigManager manager = IsolatedConfigManager.create(null);
        GpManagerConfig config = manager.getConfig(GpManagerConfig.class);
        Engine engine = PresentationLifecycleTest.engine();
        long now = 1_700_000_000_000L;
        engine.startCustomSession("Fishing", SessionMode.GENERAL, now);
        Transaction gain = Tx.of(now, TransactionType.GAIN, Context.GENERIC, "", true,
            List.of(new Flow(385, "Shark", 2L, 900, 1_800L)));
        engine.getActiveSession().addTransaction(gain, 2_000);
        RecentFilter filter = new RecentFilter()
        {
            public boolean isFlowIncluded(Flow flow) { return true; }
            public boolean showRecent(String name, long quantity)
            {
                return LootPresentationFilterService.listState("", config.hiddenRecentItems(), name, quantity)
                    != LootPresentationFilterService.HIDDEN;
            }
            public String version() { return config.hiddenRecentItems(); }
        };
        LiveContext context = new LiveContext(false, filter, 0L, PvpState.NONE, "", false, "");
        LiveSnapshot before = LiveSnapshot.capture(engine, now + 600L, context);
        LivePage page = new LivePage(new LivePage.Actions()
        {
            public void togglePause() { }
            public void editTarget() { }
            public void openLedger(LedgerData.Entry entry) { }
            public void hideRecent(String name)
            {
                manager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentItems", Text.toCSV(List.of(name)));
            }
        }, id -> null);
        Table.Row row = page.rowOf(before.recent.get(0));
        row.menu.stream().filter(menu -> "Hide from recent".equals(menu.label))
            .findFirst().orElseThrow(AssertionError::new).action.run();
        LiveSnapshot hidden = LiveSnapshot.capture(engine, now + 1_200L, context);
        assertTrue(hidden.recent.isEmpty());
        assertEquals(before.net, hidden.net);
        assertEquals(before.gains, hidden.gains);
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertNotNull(LedgerData.capture(engine, now + 1_200L,
            LivePage.ledgerEntryFor(before.recent.get(0))).detail);
        assertEquals("Shark", manager.getConfig(GpManagerConfig.class).hiddenRecentItems());
        manager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentItems", "");
        assertEquals(1, LiveSnapshot.capture(engine, now + 1_800L, context).recent.size());
    }

    @Test
    public void coinPouchesMergeInRecentAndTheOpenCarriesItsNet()
    {
        Flow pouchPickup = new Flow(ItemID.PICKPOCKET_COIN_POUCH_ELF, "Coin pouch", 1L, 0, 0L,
            PriceSource.DEFERRED_CLAIM);
        Flow pouchSpend = new Flow(ItemID.PICKPOCKET_COIN_POUCH_ELF, "Coin pouch", -1L, 0, 0L,
            PriceSource.DEFERRED_CLAIM);
        List<Transaction> receipts = List.of(
            Tx.of(1_000L, TransactionType.GAIN, Context.GENERIC, "", true, List.of(pouchPickup)),
            Tx.of(2_000L, TransactionType.GAIN, Context.GENERIC, "", true, List.of(pouchPickup)),
            Tx.of(3_000L, TransactionType.GAIN, Context.GENERIC, "", true, List.of(pouchSpend,
                new Flow(995, "Coins", 300L, 1, 300L, PriceSource.FACE_VALUE))));
        LiveContext context = new LiveContext(false, flow -> true, 0L, PvpState.NONE, "", false, "");
        List<LiveSnapshot.Recent> visible = LiveProbe.recent(receipts, context);
        assertEquals("one merged pickup row and one merged open row", 2, visible.size());
        LiveSnapshot.Recent open = visible.stream().filter(row -> "Coin pouch".equals(row.name)
            && "\u22121".equals(row.qty)).findFirst().orElseThrow(AssertionError::new);
        assertEquals("the open's one row carries both the count and the net", 300L, open.value);
        assertFalse(open.neutral);
        LiveSnapshot.Recent pickups = visible.stream().filter(row -> "Coin pouch".equals(row.name)
            && "\u00d72".equals(row.qty)).findFirst().orElseThrow(AssertionError::new);
        assertEquals(2L, pickups.quantity);
        assertTrue(pickups.neutral);
        assertEquals(0L, pickups.value);
        assertEquals("\u2014", LivePage.valueText(pickups));
    }

    @Test
    public void openingCoinPouchesIsOneNetRowBeneathTheLootMinimum()
    {
        // The minimum loot value would hide a small coin gain; the open's net is the action's
        // income, so the merged row stays visible with both the count and the net.
        LiveContext context = new LiveContext(false, null, 100L, PvpState.NONE, "", false, "");
        List<Transaction> receipts = List.of(Tx.of(3_000L, TransactionType.GAIN, Context.GENERIC, "", true, List.of(
            new Flow(ItemID.PICKPOCKET_COIN_POUCH_ELF, "Coin pouch", -3L, 0, 0L, PriceSource.DEFERRED_CLAIM),
            new Flow(995, "Coins", 9L, 1, 9L, PriceSource.FACE_VALUE))));
        List<LiveSnapshot.Recent> visible = LiveProbe.recent(receipts, context);
        assertEquals(1, visible.size());
        LiveSnapshot.Recent row = visible.get(0);
        assertEquals("Coin pouch", row.name);
        assertEquals("\u22123", row.qty);
        assertEquals("the net is the action's gain", 9L, row.value);
        assertFalse(row.neutral);
    }

    @Test
    public void groundItemsNameWildcardAndQuantityRulesStillFilterOnlyGains()
    {
        LootPresentationFilterService ground = new LootPresentationFilterService(
            new GroundItemsConfigSnapshot(true, "", "Dragon*, Shark<3", false));
        List<Transaction> receipts = List.of(
            Tx.of(1L, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(536, "Dragon bones", 1L, 2_000, 2_000L))),
            Tx.of(2L, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(385, "Shark", 2L, 900, 1_800L))),
            Tx.of(3L, TransactionType.CONSUMPTION, Context.GENERIC, "Dropped", true,
                List.of(new Flow(385, "Shark", -1L, 900, -900L))));
        LiveContext context = new LiveContext(false,
            flow -> ground.isFlowIncluded(flow, LootPresentationFilter.FOLLOW_GROUND_ITEMS),
            0L, PvpState.NONE, "", false, "");
        List<LiveSnapshot.Recent> visible = LiveProbe.recent(receipts, context);
        assertEquals("hidden gains stay off Recent; costs remain discoverable", 1, visible.size());
        assertEquals(-900L, visible.get(0).value);
        assertEquals("Shark", visible.get(0).name);
        assertEquals("Dropped", visible.get(0).actionLabel);
    }
}
