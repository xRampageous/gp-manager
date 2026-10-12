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
        engine.startCustom("Fishing", now);
        Transaction gain = Tx.of(now, TransactionType.GAIN, Context.GENERIC, "", true,
            List.of(new Flow(385, "Shark", 2L, 900, 1_800L)));
        engine.getActiveSession().addTransaction(gain, 2_000);
        RecentFilter filter = new RecentFilter()
        {
            public boolean isFlowIncluded(Flow flow) { return true; }
            public boolean showRecent(String name, long quantity, boolean charge)
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
            public void hideRecent(String name, boolean charge)
            {
                manager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentItems", Text.toCSV(List.of(name)));
            }
        }, id -> null);
        Table.Row row = page.rowOf(before.recent.get(0));
        row.menu.stream().filter(menu -> "Hide item from recent".equals(menu.label))
            .findFirst().orElseThrow(AssertionError::new).action.run();
        LiveSnapshot hidden = LiveSnapshot.capture(engine, now + 1_200L, context);
        assertTrue(hidden.recent.isEmpty());
        assertEquals(before.net, hidden.net);
        assertEquals(before.gains, hidden.gains);
        assertEquals(1, engine.getActiveSession().getTransactions().size());
        assertNotNull(LedgerData.capture(engine, now + 1_200L,
            LivePage.entryFor(before.recent.get(0))).detail);
        assertEquals("Shark", manager.getConfig(GpManagerConfig.class).hiddenRecentItems());
        manager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentItems", "");
        assertEquals(1, LiveSnapshot.capture(engine, now + 1_800L, context).recent.size());
    }

    @Test
    public void aFinishedTradeCanBeHiddenButAnOpenOfferAndASpellCannot()
    {
        // Owner 2026-10-08: a sold Fire rune row had no "Hide from recent".
        LivePage page = new LivePage(new LivePage.Actions()
        {
            public void togglePause() { }
            public void editTarget() { }
            public void openLedger(LedgerData.Entry entry) { }
        }, id -> null);
        assertTrue(hides(page, recent(554, "Fire rune", true, false)));
        assertFalse("an offer still on the exchange stays listed",
            hides(page, recent(554, "Fire rune", true, true)));
        assertFalse("a spell's runes are no single item", hides(page, recent(554, "Fire Strike", false, false)));
        assertTrue(hides(page, new LiveSnapshot.Recent(385, "Shark", null, 900L, false, false, null, 1, "r", "c",
            "", 1L, false, false, true, null, false, 0L, false, "", false, false)));
    }

    @Test
    public void anOrdinarySupplyNeverOffersTheChargeHide()
    {
        // Owner live 2026-10-12: buried bones offered "Hide charge from recent"; it is a supply,
        // not a charge.
        Transaction bones = Tx.of(1_000L, TransactionType.CONSUMPTION, Context.GENERIC, "Buried", true,
            List.of(new Flow(526, "Bones", -1L, 90, -90L)));
        LiveContext context = new LiveContext(false, null, 0L, PvpState.NONE, "", false, "");
        List<LiveSnapshot.Recent> rows = LiveProbe.recent(List.of(bones), context);
        assertEquals(1, rows.size());
        LivePage page = new LivePage(new LivePage.Actions()
        {
            public void togglePause() { }
            public void editTarget() { }
            public void openLedger(LedgerData.Entry entry) { }
        }, id -> null);
        assertTrue("a buried supply is still an item row", has(page, rows.get(0), "Hide item from recent"));
        assertFalse("but it is not a charge",
            has(page, rows.get(0), "Hide charge from recent"));
    }

    static LiveSnapshot.Recent recent(int itemId, String name, boolean market, boolean open)
    {
        return new LiveSnapshot.Recent(itemId, name, null, 1_000L, false, false, market ? "market" : null, 1, "r", "c",
            "", 200L, true, market, true, null, false, 0L, market, market ? "Sold" : "", open, false);
    }

    static boolean hides(LivePage page, LiveSnapshot.Recent row)
    {
        return page.rowOf(row).menu.stream().anyMatch(menu -> "Hide item from recent".equals(menu.label));
    }

    @Test
    public void aChargeSpendHidesItsWholeFamilyAndLeavesTheItemsOtherRows()
    {
        ConfigManager manager = IsolatedConfigManager.create(null);
        GpManagerConfig config = manager.getConfig(GpManagerConfig.class);
        Engine engine = PresentationLifecycleTest.engine();
        long now = 1_700_000_000_000L;
        engine.startCustom("Teleports", now);
        engine.getActiveSession().addTransaction(Tx.of(now, TransactionType.CONSUMPTION, Context.GENERIC, "", true,
            List.of(new Flow(21173, "Burning amulet(2)", -1L, 828, -828L),
                new Flow(21175, "Burning amulet(1)", 1L, 414, 414L))), 2_000);
        engine.getActiveSession().addTransaction(Tx.of(now + 1_000L, TransactionType.CONSUMPTION, Context.GENERIC, "", true,
            List.of(new Flow(21171, "Burning amulet(3)", -1L, 1_242, -1_242L),
                new Flow(21173, "Burning amulet(2)", 1L, 828, 828L))), 2_000);
        engine.getActiveSession().addTransaction(Tx.of(now + 2_000L, TransactionType.GAIN, Context.GENERIC, "", true,
            List.of(new Flow(21166, "Burning amulet(5)", 1L, 2_070, 2_070L))), 2_000);
        RecentFilter filter = new RecentFilter()
        {
            public boolean isFlowIncluded(Flow flow) { return true; }
            public boolean showRecent(String name, long quantity, boolean charge)
            {
                return LootPresentationFilterService.listState("", config.hiddenRecentItems(), name, quantity)
                    != LootPresentationFilterService.HIDDEN
                    && (!charge || LootPresentationFilterService.listState("", config.hiddenRecentCharges(),
                        name, quantity) != LootPresentationFilterService.HIDDEN);
            }
            public String version() { return config.hiddenRecentItems() + "|" + config.hiddenRecentCharges(); }
        };
        LiveContext context = new LiveContext(false, filter, 0L, PvpState.NONE, "", false, "");
        LiveSnapshot before = LiveSnapshot.capture(engine, now + 3_000L, context);
        assertEquals("one merged charge row and one gain", 2, before.recent.size());
        LiveSnapshot.Recent spend = before.recent.stream().filter(row -> row.value < 0L)
            .findFirst().orElseThrow(AssertionError::new);
        LiveSnapshot.Recent gain = before.recent.stream().filter(row -> row.value > 0L)
            .findFirst().orElseThrow(AssertionError::new);
        LivePage page = new LivePage(new LivePage.Actions()
        {
            public void togglePause() { }
            public void editTarget() { }
            public void openLedger(LedgerData.Entry entry) { }
            public void hideRecent(String name, boolean charge)
            {
                manager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentCharges",
                    Text.toCSV(List.of(LootPresentationFilterService.chargePattern(name))));
            }
        }, id -> null);
        assertTrue("a charge row keeps both options",
            has(page, spend, "Hide item from recent") && has(page, spend, "Hide charge from recent"));
        assertFalse("a gain is not a charge", has(page, gain, "Hide charge from recent"));

        page.rowOf(spend).menu.stream().filter(menu -> "Hide charge from recent".equals(menu.label))
            .findFirst().orElseThrow(AssertionError::new).action.run();
        assertEquals("Burning amulet*", manager.getConfig(GpManagerConfig.class).hiddenRecentCharges());
        assertTrue("the item itself was not hidden",
            manager.getConfig(GpManagerConfig.class).hiddenRecentItems().isEmpty());

        LiveSnapshot after = LiveSnapshot.capture(engine, now + 3_600L, context);
        assertTrue("every spend of the family is hidden",
            after.recent.stream().noneMatch(row -> row.value < 0L));
        assertEquals("the gain stays listed", 1,
            after.recent.stream().filter(row -> row.value > 0L).count());
        assertEquals(before.net, after.net);

        manager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentCharges", "");
        assertEquals("removing the preference restores the spends", 1,
            LiveSnapshot.capture(engine, now + 4_200L, context).recent.stream()
                .filter(row -> row.value < 0L).count());
    }

    @Test
    public void aChargeRowIsListedNowAndHidesWithTheChargeList()
    {
        // Owner 1.3.4: charge rows are no longer hardcoded out of Recent; the Hidden Recent
        // charges list hides them like any supply spend.
        Transaction estimate = new Transaction(1_000L, TransactionType.CONSUMPTION, Context.GENERIC,
            GameData.msg("estimated-charge") + "Trident of the Seas", "Vorkath", true,
            List.of(new Flow(560, "Death rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE)),
            Confidence.LIKELY, "Estimated", null);
        estimate.setActionKind(ActionKind.CAST);
        LiveContext context = new LiveContext(false, null, 0L, PvpState.NONE, "", false, "");
        List<LiveSnapshot.Recent> visible = LiveProbe.recent(List.of(estimate), context);
        assertEquals("a charge cast is listed by default", 1, visible.size());
        assertEquals("Trident of the Seas", visible.get(0).name);
        assertEquals(LedgerData.CostView.CHARGES, visible.get(0).ledgerCostView);

        RecentFilter hiding = new RecentFilter()
        {
            public boolean isFlowIncluded(Flow flow) { return true; }
            public boolean showRecent(String name, long quantity, boolean charge) { return !charge; }
        };
        LiveContext hidden = new LiveContext(false, hiding, 0L, PvpState.NONE, "", false, "");
        assertTrue("the charge list hides it like any charge",
            LiveProbe.recent(List.of(estimate), hidden).isEmpty());

        LivePage page = new LivePage(new LivePage.Actions()
        {
            public void togglePause() { }
            public void editTarget() { }
            public void openLedger(LedgerData.Entry entry) { }
        }, id -> null);
        assertTrue("the charge menu is offered on it",
            has(page, visible.get(0), "Hide charge from recent"));
    }

    private static boolean has(LivePage page, LiveSnapshot.Recent row, String label)
    {
        return page.rowOf(row).menu.stream().anyMatch(menu -> label.equals(menu.label));
    }

    @Test
    public void aSmeltingRunsOreIsOneRowOfFailedOverAll()
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = 1_700_000_000_000L;
        engine.startCustom("Smithing", now);
        engine.getActiveSession().addTransaction(Tx.of(now, TransactionType.PROCESSING, Context.PRODUCTION, "Smithing", true,
            List.of(new Flow(2351, "Iron bar", 1L, 187, 187L), new Flow(440, "Iron ore", -1L, 72, -72L))), 2_000);
        engine.getActiveSession().addTransaction(Tx.of(now + 3_000L, TransactionType.CONSUMPTION, Context.PRODUCTION, "Smelting", true,
            List.of(new Flow(440, "Iron ore", -1L, 72, -72L))), 2_000);
        LiveSnapshot live = LiveSnapshot.capture(engine, now + 3_600L, new LiveContext(false, null, 0L, PvpState.NONE, "", false, ""));
        LivePage page = new LivePage(new LivePage.Actions()
        {
            public void togglePause() { }
            public void editTarget() { }
            public void openLedger(LedgerData.Entry entry) { }
        }, id -> null);
        var ore = new ArrayList<LiveSnapshot.Recent>();
        for (LiveSnapshot.Recent recent : live.recent) if (recent.itemId == 440) ore.add(recent);
        assertEquals("one ore row", 1, ore.size());
        assertEquals("Iron ore", page.rowOf(ore.get(0)).name);
        assertEquals("1 of the 2 ores failed", "1/2", ore.get(0).qty);
        assertEquals(-144L, ore.get(0).value);
        assertTrue(LivePage.tipOf(ore.get(0)), LivePage.tipOf(ore.get(0)).contains("1 of 2 failed"));

        // Before any bar, failed ore stands alone and says so.
        Engine alone = PresentationLifecycleTest.engine();
        alone.startCustom("Smithing", now);
        alone.getActiveSession().addTransaction(Tx.of(now, TransactionType.CONSUMPTION, Context.PRODUCTION, "Smelting", true,
            List.of(new Flow(440, "Iron ore", -1L, 72, -72L))), 2_000);
        LiveSnapshot first = LiveSnapshot.capture(alone, now + 600L, new LiveContext(false, null, 0L, PvpState.NONE, "", false, ""));
        assertEquals("Iron ore (failed)", page.rowOf(first.recent.get(0)).name);

        // The Ledger shows the same single row, and its detail keeps both kinds of receipt.
        Semantics.Result ledger = Semantics.capture(engine.getActiveSession().getTransactions(), List.of(), "", null);
        var oreGroups = new ArrayList<Semantics.Group>();
        for (Semantics.Group group : ledger.groups) if (group.itemId == 440) oreGroups.add(group);
        assertEquals("one Ledger ore row", 1, oreGroups.size());
        assertEquals("1/2", oreGroups.get(0).failedOf());
        assertEquals(2, oreGroups.get(0).receipts.size());
        SidebarPanel panel = new SidebarPanel(PresentationLifecycleTest.engine(), PresentationLifecycleTest.config(), null);
        Table.Row row = panel.ledger.groupRow(oreGroups.get(0), Kit.Tone.SUPPLY);
        assertEquals("Iron ore", row.name);
        assertEquals("1/2", row.qty);
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
            new GroundItemsPrefs(true, "", "Dragon*, Shark<3", false));
        List<Transaction> receipts = List.of(
            Tx.of(1L, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(536, "Dragon bones", 1L, 2_000, 2_000L))),
            Tx.of(2L, TransactionType.GAIN, Context.GENERIC, "", true,
                List.of(new Flow(385, "Shark", 2L, 900, 1_800L))),
            Tx.of(3L, TransactionType.CONSUMPTION, Context.GENERIC, "Dropped", true,
                List.of(new Flow(385, "Shark", -1L, 900, -900L))));
        LiveContext context = new LiveContext(false,
            flow -> ground.isFlowIncluded(flow, LootFilter.FOLLOW_GROUND_ITEMS),
            0L, PvpState.NONE, "", false, "");
        List<LiveSnapshot.Recent> visible = LiveProbe.recent(receipts, context);
        assertEquals("hidden gains stay off Recent; costs remain discoverable", 1, visible.size());
        assertEquals(-900L, visible.get(0).value);
        assertEquals("Shark", visible.get(0).name);
        assertEquals("Dropped", visible.get(0).actionLabel);
    }
}
