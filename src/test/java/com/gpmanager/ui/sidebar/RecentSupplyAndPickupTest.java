package com.gpmanager;

import java.util.*;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;
import static org.junit.Assert.*;

/** Owner 1.5.3: supply hide means spent resources; ordinary pickups share one item group. */
public class RecentSupplyAndPickupTest {
    static final long T0 = 1_800_000_000_000L;

    static Transaction item(TransactionType type, ActionKind action, int id, String name, long quantity, int price,
            long at) {
        Transaction row = new Transaction(at, type, type == TransactionType.LOOT ? Context.LOOT : Context.GENERIC,
            type == TransactionType.LOOT ? "Loot from Greater Nechryael" : "", "Greater Nechryael", true,
            List.of(new Flow(id, name, quantity, price, quantity * price, PriceSource.GRAND_EXCHANGE)),
            type == TransactionType.LOOT ? Confidence.CONFIRMED : Confidence.LIKELY, "Recorded pickup fixture", null);
        row.setAction(action);
        return row;
    }

    static List<Transaction> supplies() {
        Transaction potion = new Transaction(T0, TransactionType.CONSUMPTION, Context.GENERIC, "", "Nechryael", true,
            List.of(new Flow(2434, "Prayer potion(4)", -1L, 1000, -1000L, PriceSource.GRAND_EXCHANGE),
                new Flow(139, "Prayer potion(3)", 1L, 750, 750L, PriceSource.GRAND_EXCHANGE)),
            Confidence.CONFIRMED, "One dose", null);
        potion.setAction(ActionKind.DRINK);
        return List.of(potion,
            item(TransactionType.CONSUMPTION, ActionKind.EAT, 385, "Shark", -1L, 900, T0 + 1L),
            item(TransactionType.CONSUMPTION, ActionKind.FIRE, 808, "Steel dart", -2L, 10, T0 + 2L),
            item(TransactionType.CONSUMPTION, ActionKind.BURY, 526, "Bones", -1L, 100, T0 + 3L),
            item(TransactionType.CONSUMPTION, ActionKind.SUPPLIES, 1511, "Logs", -1L, 50, T0 + 4L));
    }

    static boolean menu(LivePage page, LiveSnapshot.Recent row, String label) {
        return page.rowOf(row).menu.stream().anyMatch(entry -> entry.label.equals(label));
    }

    @Test public void everyItemSupplyOffersItemAndSupplyHide() {
        LivePage page = MilestoneTest.page();
        List<LiveSnapshot.Recent> rows = LiveProbe.recent(supplies(), null);
        assertEquals(5, rows.size());
        for (LiveSnapshot.Recent row : rows) {
            assertTrue(row.name, menu(page, row, "Hide item from recent"));
            assertTrue(row.name, menu(page, row, "Hide supply from recent"));
            assertFalse(row.name, menu(page, row, "Hide charge from recent"));
        }
    }

    @Test public void suppliesUseTheExistingSavedListAndLeaveGainsLedgerAndMoneyVisible() {
        ConfigManager manager = IsolatedConfigManager.create(null);
        GpManagerConfig config = manager.getConfig(GpManagerConfig.class);
        Engine engine = PresentationLifecycleTest.engine(); engine.startCustom("Supplies", T0);
        for (Transaction row : supplies()) engine.session().addTransaction(row, 2000, false);
        engine.session().addTransaction(item(TransactionType.GAIN, null, 385, "Shark", 2L, 900, T0 + 10L), 2000, false);
        engine.session().addTransaction(item(TransactionType.GAIN, null, 2434, "Prayer potion(4)", 1L, 1000,
            T0 + 11L), 2000, false);
        RecentFilter filter = new RecentFilter() {
            public boolean isFlowIncluded(Flow flow) { return true; }
            public boolean showRecent(String name, long quantity, boolean supply) {
                return LootPresentationFilterService.listState("", config.hiddenRecentItems()
                    + (supply ? "," + config.hiddenRecentCharges() : ""), name, quantity)
                    != LootPresentationFilterService.HIDDEN;
            }
            public String version() { return config.hiddenRecentItems() + config.hiddenRecentCharges(); }
        };
        LiveContext context = new LiveContext(false, filter, 0L, PvpState.NONE, "", false, "", false);
        LiveSnapshot before = LiveSnapshot.capture(engine, T0 + 100L, context);
        int ledgerCosts = LedgerData.capture(engine, T0 + 100L, LedgerData.Entry.current()).cost.entries;
        manager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentCharges", "Prayer potion*,Shark*,Steel dart*,Bones*,Logs*");
        LiveSnapshot after = LiveSnapshot.capture(engine, T0 + 200L, context);
        assertEquals("only acquisitions remain", 2, after.recent.size());
        assertTrue(after.recent.stream().allMatch(row -> row.value > 0L));
        assertEquals(before.net, after.net);
        assertEquals(ledgerCosts, LedgerData.capture(engine, T0 + 200L, LedgerData.Entry.current()).cost.entries);
        manager.setConfiguration(GpManagerConfig.GROUP, "hiddenRecentCharges", "");
        assertEquals(before.recent.size(), LiveSnapshot.capture(engine, T0 + 300L, context).recent.size());
    }

    @Test public void gainsLossesAndOpenOffersNeverOfferSupplyHide() {
        LivePage page = MilestoneTest.page();
        for (Transaction row : List.of(item(TransactionType.GAIN, null, 385, "Shark", 1L, 900, T0),
            item(TransactionType.PK_DEATH_LOSS, null, 385, "Shark", -1L, 900, T0))) {
            LiveSnapshot.Recent recent = LiveProbe.recent(List.of(row), null).get(0);
            assertFalse(menu(page, recent, "Hide supply from recent"));
        }
        assertFalse(menu(page, RecentVisibilityTest.recent(385, "Shark", true, true), "Hide supply from recent"));
    }

    @Test public void settingsExplainSuppliesWithoutRenamingTheSavedKey() throws Exception {
        ConfigItem item = GpManagerConfig.class.getMethod("hiddenRecentCharges").getAnnotation(ConfigItem.class);
        assertEquals("hiddenRecentCharges", item.keyName());
        assertEquals("Hidden Recent supplies", item.name());
    }

    @Test public void bloodRunePickupsShareOneRowAndRetainEveryReceiptAndCapturedPrice() {
        Engine engine = PresentationLifecycleTest.engine(); engine.startCustom("Nechryael", T0);
        Transaction inventory = item(TransactionType.GAIN, null, 565, "Blood rune", 200L, 344, T0 + 1L);
        Transaction axes = item(TransactionType.LOOT, null, 1359, "Rune axe", 10L, 7060, T0 + 2L);
        Transaction loot = item(TransactionType.LOOT, null, 565, "Blood rune", 120L, 344, T0 + 3L);
        for (Transaction row : List.of(inventory, axes, loot)) engine.session().addTransaction(row, 2000, false);
        LiveSnapshot live = LiveSnapshot.capture(engine, T0 + 100L, null);
        List<LiveSnapshot.Recent> runes = new ArrayList<>();
        for (LiveSnapshot.Recent row : live.recent) if (row.item == 565) runes.add(row);
        assertEquals("the screenshot's 200 and 120 become one row", 1, runes.size());
        assertEquals(320L, runes.get(0).quantity); assertEquals(110080L, runes.get(0).value);
        LedgerData.Detail detail = LedgerData.capture(engine, T0 + 100L, LivePage.entryFor(runes.get(0))).detail;
        assertNotNull(detail); assertEquals(2, detail.entries.size());
        assertTrue(detail.group.containsTransaction(inventory.id()));
        assertTrue(detail.group.containsTransaction(loot.id()));
        assertEquals(180680L, live.net);
        assertEquals("grouping only: all original rows stay booked", 3, engine.session().transactions().size());
        assertEquals("the Ledger keeps the same item grouping", 2,
            LedgerData.capture(engine, T0 + 100L, LedgerData.Entry.current()).income.groups.size());
    }

    @Test public void aLaterQuoteDoesNotRepriceEarlierPickups() {
        Transaction first = item(TransactionType.GAIN, null, 565, "Blood rune", 200L, 344, T0);
        Transaction second = item(TransactionType.LOOT, null, 565, "Blood rune", 120L, 345, T0 + 1L);
        List<LiveSnapshot.Recent> rows = LiveProbe.recent(List.of(first, second), null);
        assertEquals(1, rows.size()); assertEquals(110200L, rows.get(0).value);
    }

    @Test public void pickupGroupingRetainsCoveragePriceSourceCorrectionAndPkBoundaries() {
        Transaction first = item(TransactionType.GAIN, null, 565, "Blood rune", 200L, 344, T0);
        for (int fence = 0; fence < 4; fence++) {
            Transaction second = item(fence == 3 ? TransactionType.PK_LOOT : TransactionType.LOOT,
                null, 565, "Blood rune", 120L, fence == 0 ? 0 : 344, T0 + 1L);
            if (fence == 0) second.flows().get(0).priceSource = PriceSource.UNPRICED;
            if (fence == 1) second.flows().get(0).priceSource = PriceSource.MANUAL_OVERRIDE;
            if (fence == 2) second.correction = Correction.REVENUE;
            assertEquals("fence " + fence, 2, LiveProbe.recent(List.of(first, second), null).size());
        }
    }
}
