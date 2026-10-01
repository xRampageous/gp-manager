package com.gpmanager;

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
        Ao grouped = captureWithGroup(market, groupIdOf(market));
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
            parts[1].startsWith("Sold \u00b7 Result " + Fmt.ru(5L) + " gp \u00b7 "));
        assertEquals("primary is the observed settlement, never the Market Result",
            Fmt.ru(610L) + " gp", parts[2]);
        assertFalse("the Market Result is never the primary row value",
            parts[2].equals(Fmt.ru(5L) + " gp"));

        Br.Receipt receipt = grouped.detail.receipts.get(0);
        onEdt(() ->
        {
            LedgerPageProbe.clickReceipt(page, receipt.transactionId);
            return null;
        });
        assertEquals(1, actions.selections.size());
        assertEquals(receipt.transactionId, actions.selections.get(0)[0]);
        assertEquals(receipt.contributionId, actions.selections.get(0)[1]);
        assertEquals(null, actions.selections.get(0)[2]);
    }

    @Test
    public void clickingTheOpenReceiptClosesItAndOneBackLeavesTheGroup() throws Exception
    {
        Market market = new Market();
        market.sell(0, LAW, 5L, 122, 610L, 610L);
        Ao grouped = captureWithGroup(market, groupIdOf(market));
        Br.Receipt receipt = grouped.detail.receipts.get(0);
        Ao selected = Ao.capture(market.engine, market.now + 1_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "", receipt.transactionId, receipt.contributionId, null, null));
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
            LedgerPageProbe.clickReceipt(page, receipt.transactionId);
            return null;
        });
        assertEquals("clicking the open receipt closes it, keeping its group",
            Arrays.asList(null, null, grouped.detail.group.semanticGroupId), Arrays.asList(actions.selections.get(0)));
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
        Ao grouped = captureWithGroup(market, groupIdOf(market));
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
        Ao grouped = captureWithGroup(market, groupIdOf(market));
        Br.Receipt receipt = grouped.detail.receipts.get(0);

        Ao deepLinked = Ao.capture(market.engine, market.now + 1_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "", receipt.transactionId, receipt.contributionId,
                grouped.detail.group.semanticGroupId, null));
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
        Ao searched = Ao.capture(market.engine, market.now + 1_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "Law rune", null, null, groupId, null));
        assertNotNull(searched.detail);
        assertEquals(groupId, searched.detail.group.semanticGroupId);
    }

    // ── metadata and honesty ──────────────────────────────────────────────────────────────────

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
        assertTrue(texts.stream().anyMatch(text -> text.startsWith("RECEIPTS")));
    }

    @Test
    public void actionGroupCountsItsReceipts() throws Exception
    {
        Market market = new Market();
        Ac eat = new Ac(market.now - 5_000L, null,
            Ai.CONSUMPTION, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(CHAOS, "Chaos rune", -2L, 100, -200L,
                Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "", null);
        eat.setActionKind(Au.EAT);
        market.engine.getActiveSession().kf(eat, 100);
        Ao base = Ao.capture(market.engine, market.now + 1_000L,
            Ao.Entry.current());
        Br.Group group = null;
        List<String> names = new ArrayList<>();
        for (Br.Group candidate : base.costs.groups)
        {
            names.add(candidate.primaryName + "/" + candidate.itemId + "/market=" + candidate.market
                + "/receipts=" + candidate.receiptCount);
            if (candidate.itemId == CHAOS && !candidate.market)
            {
                group = candidate;
            }
        }
        assertNotNull("cost groups: " + names, group);
        LedgerPage page = page(captureWithGroup(market, group.semanticGroupId));
        List<String> texts = LedgerPageProbe.detailTexts(page);
        List<String> rows = LedgerPageProbe.detailReceiptRowTexts(page);
        assertTrue("action groups count their receipts: " + texts,
            texts.stream().anyMatch(text -> text.startsWith("1 receipt · Latest ")));
        assertTrue("the receipt section renders (" + group.primaryName + " market=" + group.market
            + " rows=" + rows + "): " + texts, texts.stream().anyMatch(text -> text.startsWith("RECEIPTS")));
    }

    @Test
    public void groupWithoutRetainedReceiptsDoesNotFabricateARow() throws Exception
    {
        Market market = new Market();
        market.pendingSell(0, LAW, 200L, 122);
        Ao base = Ao.capture(market.engine, market.now + 1_000L,
            Ao.Entry.current());
        Br.Group group = null;
        for (Br.Group candidate : base.market.groups)
        {
            if (candidate.market)
            {
                group = candidate;
            }
        }
        assertNotNull(group);
        LedgerPage page = page(captureWithGroup(market, group.semanticGroupId));
        assertTrue(LedgerPageProbe.detailReceiptRowTexts(page).isEmpty());
        assertTrue(LedgerPageProbe.detailTexts(page).stream().anyMatch(text -> text.contains("No collected receipts yet")));
        List<String> texts = LedgerPageProbe.detailTexts(page);
        assertTrue("no trade count is invented before collection: " + texts,
            texts.stream().noneMatch(text -> text.contains("1 trade")));
    }

    // ── multiple receipts, identity, accounting purity ────────────────────────────────────────

    @Test
    public void multipleReceiptsAreIndividuallySelectableWithExactIdentity() throws Exception
    {
        Market market = new Market();
        Ac first = gain(market, LAW, 1L, 121, market.now - 8_000L);
        Ac second = gain(market, LAW, 2L, 121, market.now - 4_000L);
        Ao base = Ao.capture(market.engine, market.now + 1_000L,
            Ao.Entry.current());
        Br.Group group = null;
        for (Br.Group candidate : base.gains.groups)
        {
            if (candidate.primaryName.equals("Law rune"))
            {
                group = candidate;
            }
        }
        assertNotNull(group);
        Ao grouped = captureWithGroup(market, group.semanticGroupId);
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
            LedgerPageProbe.clickReceipt(page, second.getId());
            return null;
        });
        assertEquals(second.getId(), actions.selections.get(0)[0]);
        assertTrue(actions.selections.get(0)[1] != null && !actions.selections.get(0)[1].isEmpty());
        assertFalse("the first receipt keeps its own identity",
            first.getId().equals(actions.selections.get(0)[0]));
    }

    @Test
    public void navigationNeverMutatesCanonicalAccounting() throws Exception
    {
        Market market = new Market();
        market.sell(0, LAW, 5L, 122, 610L, 610L);
        long netBefore = market.engine.getMetrics(market.now).net;
        long revisionBefore = market.engine.getRevision();
        Ao grouped = captureWithGroup(market, groupIdOf(market));
        LedgerPage page = page(grouped);
        onEdt(() ->
        {
            LedgerPageProbe.clickReceipt(page, grouped.detail.receipts.get(0).transactionId);
            LedgerPageProbe.back(page);
            return null;
        });

        assertEquals(netBefore, market.engine.getMetrics(market.now).net);
        assertEquals(revisionBefore, market.engine.getRevision());
        assertEquals(108, SavedState.CURRENT_SCHEMA_VERSION);
        assertEquals("RuneLite market", Av.GRAND_EXCHANGE.toString());
    }

    /** Owner 2026-10-01 (F07): the group-detail pager survives refreshes and clamps on shrink. */
    @Test
    public void theDetailPagerSurvivesRefreshesAndClampsWhenRowsShrink() throws Exception
    {
        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
        engine.ajl("Vorkath", Cx.GENERAL, T0);
        for (int i = 0; i < 8; i++)
        {
            engine.getActiveSession().kf(new Ac(T0 + 10_000L + i * 1_000L, null, Ai.LOOT, Aj.LOOT,
                "", "Vorkath", true,
                Collections.singletonList(new Ab(536, "Dragon bones", 1L, 2_000, 2_000L)),
                Bd.CONFIRMED, "", null), 500);
        }
        String groupId = lootGroupId(engine);
        LedgerPage page = page(lootCapture(engine, groupId));

        assertEquals("two pages of receipts", "1/2", page.detailReceipts.pageLabel.getText());
        onEdt(() ->
        {
            page.detailReceipts.next.doClick();
            return null;
        });
        assertEquals(1, page.detailReceipts.page);

        page.apply(lootCapture(engine, groupId));
        assertEquals("a compatible refresh keeps the page", 1, page.detailReceipts.page);

        onEdt(() ->
        {
            LedgerPageProbe.clickReceipt(page, page.data.detail.receipts.get(5).transactionId);
            return null;
        });
        page.apply(lootCapture(engine, groupId));
        assertEquals("selecting a receipt keeps the list's page", 1, page.detailReceipts.page);

        Am other = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() {});
        other.ajl("Zulrah", Cx.GENERAL, T0);
        other.getActiveSession().kf(new Ac(T0 + 10_000L, null, Ai.LOOT, Aj.LOOT, "", "Zulrah",
            true, Collections.singletonList(new Ab(1753, "Blue dragonhide", 1L, 2_000, 2_000L)),
            Bd.CONFIRMED, "", null), 500);
        page.apply(lootCapture(other, lootGroupId(other)));
        assertEquals("a different group starts at the first page", 0, page.detailReceipts.page);

        page.apply(lootCapture(engine, groupId));
        onEdt(() ->
        {
            page.detailReceipts.next.doClick();
            return null;
        });
        assertEquals(1, page.detailReceipts.page);
        onEdt(() ->
        {
            page.agf();
            return null;
        });
        assertEquals("an account reset starts clean", 0, page.detailReceipts.page);

        page.apply(lootCapture(engine, groupId));
        onEdt(() ->
        {
            page.detailReceipts.next.doClick();
            return null;
        });
        assertEquals(1, page.detailReceipts.page);
        engine.getActiveSession().pj(T0 + 13_000L, null);
        page.apply(lootCapture(engine, groupId));
        assertEquals("shrunken rows clamp the page", 0, page.detailReceipts.page);
    }

    private static String lootGroupId(Am engine)
    {
        Ao data = Ao.capture(engine, T0 + 30_000L, Ao.Entry.current());
        assertFalse("the loot group exists", data.gains.groups.isEmpty());
        return data.gains.groups.get(0).semanticGroupId;
    }

    private static Ao lootCapture(Am engine, String groupId)
    {
        Ao data = Ao.capture(engine, T0 + 30_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null, Ao.Bs.ALL, "", null, null,
                groupId, null));
        assertNotNull("the group detail exists", data.detail);
        return data;
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Ac gain(Market market, int itemId, long quantity, int unitPrice, long at)
    {
        Ac transaction = new Ac(at, null, Ai.GAIN,
            Aj.GENERIC, "", itemId == LAW ? "Law rune" : "Item", true,
            Collections.singletonList(new Ab(itemId, "Law rune", quantity, unitPrice,
                quantity * unitPrice, Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "", null);
        market.engine.getActiveSession().kf(transaction, 200);
        return transaction;
    }

    private static String groupIdOf(Market market)
    {
        Ao data = Ao.capture(market.engine, market.now + 1_000L,
            Ao.Entry.current());
        Br.Group group = null;
        for (Br.Group candidate : data.market.groups)
        {
            if (candidate.market)
            {
                group = candidate;
            }
        }
        assertNotNull(group);
        return group.semanticGroupId;
    }

    private static Ao captureWithGroup(Market market, String groupId)
    {
        Ao data = Ao.capture(market.engine, market.now + 1_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "", null, null, groupId, null));
        assertNotNull(data.detail);
        return data;
    }

    private static LedgerPage page(Ao data) throws Exception
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
        @Override public void costViewChanged(Ao.Bs view) { }
        @Override public void searchChanged(String text) { }
        @Override public Ao.Ef preview(String id,
            Ah correction)
        {
            return null;
        }
        @Override public LedgerPage.Ea correct(String id,
            Ah correction, long previewRevision)
        {
            return LedgerPage.Ea.REFUSED;
        }
        @Override public void split(String id) { }
        @Override public void undoCorrection() { }
        @Override public void decideAll(Cl decision) { }
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
        final Am engine;
        final Bj ledger = new Bj();
        final Map<Integer, Long> inventory = new HashMap<>();
        long now = T0;

        Market()
        {
            engine = new Am(deltas ->
            {
                List<Ab> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> delta : deltas.entrySet())
                {
                    int id = delta.getKey();
                    int unit = id == COINS ? 1 : quote[0];
                    Av source = id == COINS ? Av.FACE_VALUE
                        : Av.GRAND_EXCHANGE;
                    flows.add(new Ab(id, id == COINS ? "Coins" : "Law rune", delta.getValue(),
                        unit, delta.getValue() * unit, source));
                }
                return flows;
            }, new TransactionClassifier(), new GpManagerConfig()
            {
                @Override public Db receiptRetentionDays()
                {
                    return Db.DAYS_365;
                }
                @Override public boolean keepTransferAuditRows()
                {
                    return true;
                }
            });
            engine.ajl("Trading", Cx.AUTO, now);
            inventory.put(COINS, 1_000_000L);
            engine.setBaseline(new Cc(inventory));
        }

        void sell(int slot, int item, long qty, int limit, long spent, long cash)
        {
            // Known coverage at the observed quote, so the receipt carries a proven Result.
            engine.getActiveSession().kf(new Ac(now - 1_000L, null,
                Ai.GAIN, Aj.GENERIC, "", "Law rune", true,
                Collections.singletonList(new Ab(item, "Law rune", qty, quote[0],
                    qty * quote[0], Av.GRAND_EXCHANGE)),
                Bd.CONFIRMED, "", null), 500);
            inventory.put(item, qty);
            engine.setBaseline(new Cc(inventory));
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
            engine.setBaseline(new Cc(inventory));
            offer(slot, GrandExchangeOfferState.SELLING, item, (int) offered, 0, limit, 0);
            inventory.remove(item);
            settle();
        }

        private void settle()
        {
            now += 5_000L;
            engine.yz();
            Cc snapshot = new Cc(inventory);
            for (int i = 0; i < 3; i++)
            {
                engine.adj(snapshot, now);
                now += 5_000L;
            }
        }

        private void offer(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent)
        {
            now += 5_000L;
            Bj.Transition transition = ledger.observe(
                new Bj.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.abh(transition, "Law rune", now);
            }
        }
    }
}
