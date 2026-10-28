package com.gpmanager;

import com.gpmanager.GpManagerConfig.Retention;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractButton;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * PRE-R5C.2C.4: every semantic group exposes its retained exact receipts, and the Ledger group
 * detail reaches the same human receipt view Live already deep-links to.
 */
public class LedgerGroupReceiptNavigationTest
{
    private static final long T0 = System.currentTimeMillis() - 10 * 60_000L;
    private static final int COINS = 995;
    private static final int LAW = 563;
    private static final int CHAOS = 562;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    // ── market group → its receipts → one receipt opened under them ────────────────────────────────────────────────

    @Test
    public void marketGroupListsItsRetainedReceiptAndSelectsItByIdentity() throws Exception
    {
        Market market = new Market();
        market.sell(0, LAW, 5L, 122, 610L, 610L);
        LedgerData grouped = captureWithGroup(market, groupIdOf(market));
        assertTrue(grouped.detail.group.market);

        RecordingActions actions = new RecordingActions();
        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(actions, id -> null);
            created.apply(grouped);
            return created;
        });

        assertEquals("one click shows the receipts", "RECEIPTS", LedgerPageProbe.drill(page));
        List<String> rows = LedgerPageProbe.detailReceiptRowTexts(page);
        assertEquals(1, rows.size());
        String[] parts = rows.get(0).split("\\|", 3);
        assertEquals("Law rune  " + Fmt.times(5L), parts[0]);
        assertTrue("secondary carries the side and the Market Result: " + parts[1],
            parts[1].startsWith("Sold \u00b7 Result " + Fmt.exactSigned(5L) + " gp \u00b7 "));
        assertEquals("primary is the observed settlement, never the Market Result",
            Fmt.exactSigned(610L) + " gp", parts[2]);
        assertFalse("the Market Result is never the primary row value",
            parts[2].equals(Fmt.exactSigned(5L) + " gp"));

        Semantics.Receipt receipt = grouped.detail.entries.get(0);
        onEdt(() ->
        {
            LedgerPageProbe.clickReceipt(page, receipt.transaction);
            return null;
        });
        assertEquals(1, actions.selections.size());
        assertEquals(receipt.transaction, actions.selections.get(0)[0]);
        assertEquals(receipt.id, actions.selections.get(0)[1]);
        assertEquals(null, actions.selections.get(0)[2]);
    }

    @Test
    public void clickingTheOpenReceiptClosesItAndOneBackLeavesTheGroup() throws Exception
    {
        Market market = new Market();
        market.sell(0, LAW, 5L, 122, 610L, 610L);
        LedgerData grouped = captureWithGroup(market, groupIdOf(market));
        Semantics.Receipt receipt = grouped.detail.entries.get(0);
        LedgerData selected = LedgerData.capture(market.engine, market.now + 1_000L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.SUPPLIES, "", receipt.transaction, receipt.id, null, null));
        RecordingActions actions = new RecordingActions();
        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(actions, id -> null);
            created.apply(selected);
            return created;
        });
        assertEquals("EXACT", LedgerPageProbe.drill(page));
        onEdt(() ->
        {
            LedgerPageProbe.clickReceipt(page, receipt.transaction);
            return null;
        });
        assertEquals("clicking the open receipt closes it, keeping its group",
            Arrays.asList(null, null, grouped.detail.group.id), Arrays.asList(actions.selections.get(0)));
        onEdt(() ->
        {
            LedgerPageProbe.back(page);
            return null;
        });
        assertEquals("one Back leaves for the Ledger", Arrays.asList(null, null, null),
            Arrays.asList(actions.selections.get(1)));
    }

    @Test
    public void groupBackAffordanceReturnsToTheLedgerOverview() throws Exception
    {
        Market market = new Market();
        market.sell(0, LAW, 5L, 122, 610L, 610L);
        LedgerData grouped = captureWithGroup(market, groupIdOf(market));
        RecordingActions actions = new RecordingActions();
        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(actions, id -> null);
            created.apply(grouped);
            return created;
        });

        assertTrue("the group detail keeps its Ledger back affordance",
            clickButton(page.body(), "Back to Ledger"));
        assertEquals(1, actions.selections.size());
        assertEquals(null, actions.selections.get(0)[0]);
        assertEquals(null, actions.selections.get(0)[1]);
        assertEquals(null, actions.selections.get(0)[2]);
    }

    @Test
    public void liveDeepLinkStillOpensTheExactReceiptDirectly() throws Exception
    {
        Market market = new Market();
        market.sell(0, LAW, 5L, 122, 610L, 610L);
        LedgerData grouped = captureWithGroup(market, groupIdOf(market));
        Semantics.Receipt receipt = grouped.detail.entries.get(0);

        LedgerData deepLinked = LedgerData.capture(market.engine, market.now + 1_000L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.SUPPLIES, "", receipt.transaction, receipt.id,
                grouped.detail.group.id, null));
        assertNotNull("the group detail resolves", deepLinked.detail);
        assertNotNull("the deep link resolves the exact receipt", deepLinked.detail.exact);
        LedgerPage page = page(deepLinked);
        assertEquals("EXACT", LedgerPageProbe.drill(page));
        assertTrue("the exact human receipt renders", LedgerPageProbe.detailTexts(page).contains("Received"));
    }

    @Test
    public void searchDeepLinkStillOpensTheGroup() throws Exception
    {
        Market market = new Market();
        market.sell(0, LAW, 5L, 122, 610L, 610L);
        String groupId = groupIdOf(market);
        LedgerData searched = LedgerData.capture(market.engine, market.now + 1_000L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.SUPPLIES, "Law rune", null, null, groupId, null));
        assertNotNull(searched.detail);
        assertEquals(groupId, searched.detail.group.id);
    }

    // ── metadata and honesty ──────────────────────────────────────────────────────────────────

    @Test
    public void claimedLootKeyKeepsItsEntryAndOriginalFlowsButOnlyShowsReceivedItems() throws Exception
    {
        java.util.function.LongSupplier clock = SafeMath.clock;
        SafeMath.clock = () -> T0 + 2000;
        try {
        for (int lootKinds : new int[]{1, 2}) {
            Engine engine = new Engine(delta -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
            engine.ensureSession(T0);
            List<Flow> flows = new ArrayList<>();
            flows.add(new Flow(26651, "Loot key", -1, 0, 0, PriceSource.DEFERRED_CLAIM));
            flows.add(new Flow(26219, "Osmumten's fang", 1, 20_000, 20_000));
            if (lootKinds == 2) flows.add(new Flow(6737, "Berserker ring", 1, 5_000, 5_000));
            Transaction receipt = Tx.of(T0 + 1000, TransactionType.PK_LOOT, Context.PK_LOOT, "Looted key", true, flows);
            engine.session().addTransaction(receipt, 2000, false);
            String before = new com.google.gson.Gson().toJson(engine.exportState());
            LedgerData overview = LedgerData.capture(engine, T0 + 2000, LedgerData.Entry.current());
            assertEquals(1, overview.income.groups.size());
            Semantics.Group group = overview.income.groups.get(0);
            assertEquals("Loot key", group.name);
            assertEquals(lootKinds == 1 ? 20_000 : 25_000, group.value);
            LedgerData detail = LedgerData.capture(engine, T0 + 2000,
                LedgerData.Entry.current().withSelection(null, null, group.id));
            LedgerPage page = page(detail);
            assertEquals(lootKinds, page.detailItems.rows.size());
            assertTrue("a single received item still has its item list", page.detailItems.getParent() == page.detail);
            assertTrue(page.detailItems.rows.stream().noneMatch(row -> row.name.equals("Loot key")));
            assertTrue(receipt.flows().stream().anyMatch(flow -> flow.item == 26651 && flow.quantity == -1));
            assertEquals(before, new com.google.gson.Gson().toJson(engine.exportState()));
        }
        } finally { SafeMath.clock = clock; }
    }

    @Test
    public void anUnclaimedLootKeyStillOpensItsNeutralItem() throws Exception
    {
        Engine engine = KillViewTest.nativeEngine();
        String encounter = engine.session().addEncounter(true, T0);
        Transaction key = KillViewTest.key(engine, encounter, 26651);
        LedgerData.Entry query = LedgerData.Entry.current().live(Semantics.Table.KILL);
        LedgerData overview = LedgerData.capture(engine, T0 + 2000, query);
        Semantics.Group group = overview.income.groups.get(0);
        assertTrue(group.incomplete());
        LedgerPage page = page(LedgerData.capture(engine, T0 + 2000, query.withSelection(null, null, group.id)));
        assertEquals(1, page.detailItems.rows.size());
        assertTrue(page.detailItems.rows.get(0).name.contains("Loot key"));
        assertFalse(key.counts());
    }

    @Test
    public void marketGroupMetadataHasNoResourceTypeNoise() throws Exception
    {
        Market market = new Market();
        market.sell(0, LAW, 5L, 122, 610L, 610L);
        LedgerPage page = page(captureWithGroup(market, groupIdOf(market)));
        List<String> texts = LedgerPageProbe.detailTexts(page);

        assertTrue("the trade count replaces resource-type noise: " + texts,
            texts.stream().anyMatch(text -> text.startsWith("1 trade \u00b7 Latest ")));
        assertTrue(texts.stream().noneMatch(text -> text.contains("0 resource types")));
        assertTrue(texts.stream().anyMatch(text -> text.startsWith("ENTRIES")));
    }

    @Test
    public void actionGroupCountsItsReceipts() throws Exception
    {
        Market market = new Market();
        Transaction eat = new Transaction(market.now - 5_000L,
            TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(CHAOS, "Chaos rune", -2L, 100, -200L,
                PriceSource.GRAND_EXCHANGE)),
            Confidence.CONFIRMED, "", null);
        eat.setAction(ActionKind.EAT);
        market.engine.session().addTransaction(eat, 100, false);
        LedgerData base = LedgerData.capture(market.engine, market.now + 1_000L,
            LedgerData.Entry.current());
        Semantics.Group group = null;
        List<String> names = new ArrayList<>();
        for (Semantics.Group candidate : base.cost.groups)
        {
            names.add(candidate.name + "/" + candidate.item + "/market=" + candidate.market
                + "/receipts=" + candidate.count);
            if (candidate.item == CHAOS && !candidate.market)
            {
                group = candidate;
            }
        }
        assertNotNull("cost groups: " + names, group);
        LedgerPage page = page(captureWithGroup(market, group.id));
        List<String> texts = LedgerPageProbe.detailTexts(page);
        List<String> rows = LedgerPageProbe.detailReceiptRowTexts(page);
        assertTrue("action groups count their receipts: " + texts,
            texts.stream().anyMatch(text -> text.startsWith("1 entry · Latest ")));
        // Owner 1.5.2: one entry opens straight on its detail instead of a list of one.
        assertFalse("no list of one (" + rows + "): " + texts, texts.stream().anyMatch(text -> text.startsWith("ENTRIES")));
        assertTrue("its detail is open: " + texts, texts.stream().anyMatch(text -> text.contains("Ate \u00b7 ")));
    }

    @Test
    public void groupWithoutRetainedReceiptsDoesNotFabricateARow() throws Exception
    {
        Market market = new Market();
        market.pendingSell(0, LAW, 200L, 122);
        LedgerData base = LedgerData.capture(market.engine, market.now + 1_000L,
            LedgerData.Entry.current());
        Semantics.Group group = null;
        for (Semantics.Group candidate : base.market.groups)
        {
            if (candidate.market)
            {
                group = candidate;
            }
        }
        assertNotNull(group);
        LedgerPage page = page(captureWithGroup(market, group.id));
        assertTrue(LedgerPageProbe.detailReceiptRowTexts(page).isEmpty());
        assertTrue(LedgerPageProbe.detailTexts(page).stream().anyMatch(text -> text.contains("No collected entries yet")));
        List<String> texts = LedgerPageProbe.detailTexts(page);
        assertTrue("no trade count is invented before collection: " + texts,
            texts.stream().noneMatch(text -> text.contains("1 trade")));
    }

    // ── multiple receipts, identity, accounting purity ────────────────────────────────────────

    @Test
    public void multipleReceiptsAreIndividuallySelectableWithExactIdentity() throws Exception
    {
        Market market = new Market();
        Transaction first = gain(market, LAW, 1L, 121, market.now - 8_000L);
        Transaction second = gain(market, LAW, 2L, 121, market.now - 4_000L);
        LedgerData base = LedgerData.capture(market.engine, market.now + 1_000L,
            LedgerData.Entry.current());
        Semantics.Group group = null;
        for (Semantics.Group candidate : base.income.groups)
        {
            if (candidate.name.equals("Law rune"))
            {
                group = candidate;
            }
        }
        assertNotNull(group);
        LedgerData grouped = captureWithGroup(market, group.id);
        RecordingActions actions = new RecordingActions();
        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(actions, id -> null);
            created.apply(grouped);
            return created;
        });
        assertEquals("both retained receipts are listed", 2, LedgerPageProbe.detailReceiptRowTexts(page).size());

        onEdt(() ->
        {
            LedgerPageProbe.clickReceipt(page, second.id());
            return null;
        });
        assertEquals(second.id(), actions.selections.get(0)[0]);
        assertTrue(actions.selections.get(0)[1] != null && !actions.selections.get(0)[1].isEmpty());
        assertFalse("the first receipt keeps its own identity",
            first.id().equals(actions.selections.get(0)[0]));
    }

    @Test
    public void navigationNeverMutatesCanonicalAccounting() throws Exception
    {
        Market market = new Market();
        market.sell(0, LAW, 5L, 122, 610L, 610L);
        long netBefore = market.engine.metrics(market.now).net;
        long revisionBefore = market.engine.revision();
        LedgerData grouped = captureWithGroup(market, groupIdOf(market));
        LedgerPage page = page(grouped);
        onEdt(() ->
        {
            LedgerPageProbe.clickReceipt(page, grouped.detail.entries.get(0).transaction);
            LedgerPageProbe.back(page);
            return null;
        });

        assertEquals(netBefore, market.engine.metrics(market.now).net);
        assertEquals(revisionBefore, market.engine.revision());
        assertEquals(110, SavedState.CURRENT_SCHEMA_VERSION);
        assertEquals("RuneLite market", PriceSource.GRAND_EXCHANGE.toString());
    }

    /** Owner 2026-10-01 (F07): the group-detail pager survives refreshes and clamps on shrink. */
    @Test
    public void theDetailPagerSurvivesRefreshesAndClampsWhenRowsShrink() throws Exception
    {
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
        engine.startCustom("Vorkath", T0);
        for (int i = 0; i < 8; i++)
        {
            engine.session().addTransaction(new Transaction(T0 + 10_000L + i * 1_000L, TransactionType.LOOT, Context.LOOT,
                "", "Vorkath", true,
                Collections.singletonList(new Flow(536, "Dragon bones", 1L, 2_000, 2_000L)),
                Confidence.CONFIRMED, "", null), 500, false);
        }
        String groupId = lootGroupId(engine);
        LedgerPage page = page(lootCapture(engine, groupId));

        assertEquals("two pages of receipts", "1/2", page.receipts.counter.getText());
        onEdt(() ->
        {
            page.receipts.next.doClick();
            return null;
        });
        assertEquals(1, page.receipts.page);

        page.apply(lootCapture(engine, groupId));
        assertEquals("a compatible refresh keeps the page", 1, page.receipts.page);

        onEdt(() ->
        {
            LedgerPageProbe.clickReceipt(page, page.data.detail.entries.get(5).transaction);
            return null;
        });
        page.apply(lootCapture(engine, groupId));
        assertEquals("selecting a receipt keeps the list's page", 1, page.receipts.page);

        Engine other = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
        other.startCustom("Zulrah", T0);
        other.session().addTransaction(new Transaction(T0 + 10_000L, TransactionType.LOOT, Context.LOOT, "", "Zulrah",
            true, Collections.singletonList(new Flow(1753, "Blue dragonhide", 1L, 2_000, 2_000L)),
            Confidence.CONFIRMED, "", null), 500, false);
        page.apply(lootCapture(other, lootGroupId(other)));
        assertEquals("a different group starts at the first page", 0, page.receipts.page);

        page.apply(lootCapture(engine, groupId));
        onEdt(() ->
        {
            page.receipts.next.doClick();
            return null;
        });
        assertEquals(1, page.receipts.page);
        onEdt(() ->
        {
            page.resetOwner();
            return null;
        });
        assertEquals("an account reset starts clean", 0, page.receipts.page);

        page.apply(lootCapture(engine, groupId));
        onEdt(() ->
        {
            page.receipts.next.doClick();
            return null;
        });
        assertEquals(1, page.receipts.page);
        engine.session().compactBefore(T0 + 13_000L, null);
        page.apply(lootCapture(engine, groupId));
        assertEquals("shrunken rows clamp the page", 0, page.receipts.page);
    }

    private static String lootGroupId(Engine engine)
    {
        LedgerData data = LedgerData.capture(engine, T0 + 30_000L, LedgerData.Entry.current());
        assertFalse("the loot group exists", data.income.groups.isEmpty());
        return data.income.groups.get(0).id;
    }

    private static LedgerData lootCapture(Engine engine, String groupId)
    {
        LedgerData data = LedgerData.capture(engine, T0 + 30_000L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null, LedgerData.CostView.ALL, "", null, null,
                groupId, null));
        assertNotNull("the group detail exists", data.detail);
        return data;
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Transaction gain(Market market, int itemId, long quantity, int unitPrice, long at)
    {
        Transaction transaction = new Transaction(at, TransactionType.GAIN,
            Context.GENERIC, "", itemId == LAW ? "Law rune" : "Item", true,
            Collections.singletonList(new Flow(itemId, "Law rune", quantity, unitPrice,
                quantity * unitPrice, PriceSource.GRAND_EXCHANGE)),
            Confidence.CONFIRMED, "", null);
        market.engine.session().addTransaction(transaction, 200, false);
        return transaction;
    }

    private static String groupIdOf(Market market)
    {
        LedgerData data = LedgerData.capture(market.engine, market.now + 1_000L,
            LedgerData.Entry.current());
        Semantics.Group group = null;
        for (Semantics.Group candidate : data.market.groups)
        {
            if (candidate.market)
            {
                group = candidate;
            }
        }
        assertNotNull(group);
        return group.id;
    }

    private static LedgerData captureWithGroup(Market market, String groupId)
    {
        LedgerData data = LedgerData.capture(market.engine, market.now + 1_000L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.SUPPLIES, "", null, null, groupId, null));
        assertNotNull(data.detail);
        return data;
    }

    private static LedgerPage page(LedgerData data) throws Exception
    {
        return onEdt(() ->
        {
            LedgerPage created = new LedgerPage(new RecordingActions(), id -> null);
            created.apply(data);
            return created;
        });
    }

    private static boolean clickButton(Component component, String accessibleName)
    {
        if (component instanceof AbstractButton
            && accessibleName.equals(component.getAccessibleContext().getAccessibleName()))
        {
            ((AbstractButton) component).doClick();
            return true;
        }
        if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                if (clickButton(child, accessibleName))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        if (javax.swing.SwingUtilities.isEventDispatchThread())
        {
            return callable.call();
        }
        java.util.concurrent.atomic.AtomicReference<T> value =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() ->
        {
            try { value.set(callable.call()); }
            catch (Throwable ex) { failure.set(ex); }
        });
        if (failure.get() != null)
        {
            throw new AssertionError(failure.get());
        }
        return value.get();
    }

    private static final class RecordingActions implements LedgerPage.Actions
    {
        final List<String[]> selections = new ArrayList<>();

        @Override public void openScopeMenu(javax.swing.JComponent anchor) { }
        @Override public void costViewChanged(LedgerData.CostView view) { }
        @Override public void searchChanged(String text) { }
        @Override public LedgerData.CorrectionPreview preview(String id,
            Correction correction)
        {
            return null;
        }
        @Override public LedgerPage.CorrectionOutcome correct(String id,
            Correction correction, long previewRevision)
        {
            return LedgerPage.CorrectionOutcome.REFUSED;
        }
        @Override public void split(String id, java.util.function.BiConsumer<long[], LedgerData.CorrectionPreview> previewed) { }
        @Override public void undoCorrection() { }
        @Override public void refresh() { }
        @Override public void selectionChanged(String transactionId, String contributionId,
            String groupId)
        {
            selections.add(new String[] {transactionId, contributionId, groupId});
        }
    }

    private static final class Market
    {
        final int[] quote = {121};
        final Engine engine;
        final OfferSlots ledger = new OfferSlots();
        final Map<Integer, Long> inventory = new HashMap<>();
        long now = T0;

        Market()
        {
            engine = new Engine(deltas ->
            {
                List<Flow> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> delta : deltas.entrySet())
                {
                    int id = delta.getKey();
                    int unit = id == COINS ? 1 : quote[0];
                    PriceSource source = id == COINS ? PriceSource.FACE_VALUE
                        : PriceSource.GRAND_EXCHANGE;
                    flows.add(new Flow(id, id == COINS ? "Coins" : "Law rune", delta.getValue(),
                        unit, delta.getValue() * unit, source));
                }
                return flows;
            }, new TransactionClassifier(), new GpManagerConfig()
            {
                @Override public Retention receiptRetentionDays()
                {
                    return Retention.DAYS_365;
                }
                @Override public boolean keepTransferAuditRows()
                {
                    return true;
                }
            });
            engine.startCustom("Trading", now);
            inventory.put(COINS, 1_000_000L);
            engine.setBaseline(new ContainerSnapshot(inventory));
        }

        void sell(int slot, int item, long qty, int limit, long spent, long cash)
        {
            // Known coverage at the observed quote, so the receipt carries a proven Result.
            engine.session().addTransaction(new Transaction(now - 1_000L,
                TransactionType.GAIN, Context.GENERIC, "", "Law rune", true,
                Collections.singletonList(new Flow(item, "Law rune", qty, quote[0],
                    qty * quote[0], PriceSource.GRAND_EXCHANGE)),
                Confidence.CONFIRMED, "", null), 500, false);
            inventory.put(item, qty);
            engine.setBaseline(new ContainerSnapshot(inventory));
            offer(slot, GrandExchangeOfferState.SELLING, item, (int) qty, 0, limit, 0);
            inventory.remove(item);
            settle();
            offer(slot, GrandExchangeOfferState.SOLD, item, (int) qty, (int) qty, limit, (int) spent);
            inventory.put(COINS, inventory.getOrDefault(COINS, 0L) + cash);
            settle();
        }

        void pendingSell(int slot, int item, long offered, int limit)
        {
            inventory.put(item, offered);
            engine.setBaseline(new ContainerSnapshot(inventory));
            offer(slot, GrandExchangeOfferState.SELLING, item, (int) offered, 0, limit, 0);
            inventory.remove(item);
            settle();
        }

        private void settle()
        {
            now += 5_000L;
            engine.markDirty();
            ContainerSnapshot snapshot = new ContainerSnapshot(inventory);
            for (int i = 0; i < 3; i++)
            {
                engine.processIfDirty(snapshot, now, null);
                now += 5_000L;
            }
        }

        private void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent)
        {
            now += 5_000L;
            OfferSlots.Transition transition = ledger.observe(
                new OfferSlots.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.noteGeOffer(transition, "Law rune", now);
            }
        }
    }
}
