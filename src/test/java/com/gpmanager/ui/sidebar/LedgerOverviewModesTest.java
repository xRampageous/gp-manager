package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Ledger overview/detail modes, five-row pages, Review/Corrected and the bar Net. */
public class LedgerOverviewModesTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final int COINS = 995;
    private static final int[] MARKET_ITEMS = {561, 563, 565, 566};

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void openingDetailReplacesTheOverviewAndBackRestoresIt() throws Exception
    {
        Am engine = session();
        for (int i = 0; i < 5; i++)
        {
            engine.getActiveSession().kf(gain(T0 + 1_000L + i * 1_000L, "Item " + i,
                2_000 + i, 1L, 100, 100L), 2_000);
        }
        LedgerHarness harness = new LedgerHarness(engine, T0 + 50_000L, entry());
        LedgerPage page = onEdt(() -> harness.attach(new LedgerPage(harness, id -> null)));

        List<String> overview = onEdt(() -> labels(page.body()));
        assertTrue(overview.stream().anyMatch(label -> label.startsWith("GAINS \u00b7")));
        assertTrue(overview.stream().anyMatch(label -> label.startsWith("LOSSES \u00b7")));
        assertTrue(overview.stream().anyMatch(label -> label.startsWith("MARKET \u00b7")));
        assertFalse(onEdt(() -> detailOpen(page)));

        String groupId = harness.data().gains.groups.get(0).semanticGroupId;
        String groupName = harness.data().gains.groups.get(0).primaryName;
        onEdt(() ->
        {
            harness.auz(groupId);
            return null;
        });
        assertTrue(onEdt(() -> detailOpen(page)));
        List<String> detail = onEdt(() -> labels(page.body()));
        assertFalse("overview tables are gone while a detail is open", detail.stream()
            .anyMatch(label -> label.startsWith("GAINS \u00b7") || label.startsWith("LOSSES \u00b7")
                || label.startsWith("MARKET \u00b7")));
        assertTrue("the detail summary is rendered",
            detail.stream().anyMatch(label -> label.contains(groupName)));

        assertTrue("the detail offers a clear Back affordance",
            onEdt(() -> clickButton(page.body(), "\u2039 Ledger")));
        assertFalse("Back returns to the overview", onEdt(() -> detailOpen(page)));
        List<String> restored = onEdt(() -> labels(page.body()));
        assertTrue(restored.stream().anyMatch(label -> label.startsWith("GAINS \u00b7")));
        assertTrue(restored.stream().anyMatch(label -> label.startsWith("LOSSES \u00b7")));
    }

    @Test
    public void backRestoresThePriorPageAndCostsFilter() throws Exception
    {
        Am engine = session();
        for (int i = 0; i < 15; i++)
        {
            engine.getActiveSession().kf(food(T0 + 1_000L + i * 1_000L, "Food " + i,
                3_000 + i, 50L), 2_000);
        }
        engine.getActiveSession().kf(gain(T0 + 90_000L, "Bones", 536, 1L, 3_200, 3_200L), 2_000);
        LedgerHarness harness = new LedgerHarness(engine, T0 + 100_000L, entry());
        LedgerPage page = onEdt(() -> harness.attach(new LedgerPage(harness, id -> null)));

        assertTrue(onEdt(() -> clickButton(TableRows.tables(page.body(), "LOSSES").get(0), "\u203a")));
        assertEquals("2/3", onEdt(() -> LedgerPageProbe.overviewPageLabel(page, LedgerPageProbe.FinancialTable.COSTS)));
        List<String> paged = onEdt(() -> LedgerPageProbe.overviewRows(page, LedgerPageProbe.FinancialTable.COSTS));
        assertEquals("the page holds five rows", 5, paged.size());
        String firstRowOfPage = harness.data().costs.groups.get(5).primaryName;
        assertEquals("the page starts where the pager says", firstRowOfPage, paged.get(0));

        String groupId = harness.data().gains.groups.get(0).semanticGroupId;
        onEdt(() ->
        {
            harness.auz(groupId);
            return null;
        });
        assertTrue(onEdt(() -> detailOpen(page)));
        assertTrue(onEdt(() -> clickButton(page.body(), "\u2039 Ledger")));
        assertEquals("Back restores the exact prior table page", "2/3",
            onEdt(() -> LedgerPageProbe.overviewPageLabel(page, LedgerPageProbe.FinancialTable.COSTS)));
        assertEquals("Back restores the exact prior page rows", firstRowOfPage,
            onEdt(() -> LedgerPageProbe.overviewRows(page, LedgerPageProbe.FinancialTable.COSTS)).get(0));

        onEdt(() ->
        {
            harness.costViewChanged(Ao.Bs.LOSS);
            return null;
        });
        assertEquals(Ao.Bs.LOSS, onEdt(() -> LedgerPageProbe.data(page).entry.costView));
        onEdt(() ->
        {
            harness.auz(groupId);
            return null;
        });
        assertTrue(onEdt(() -> clickButton(page.body(), "\u2039 Ledger")));
        assertEquals("Back restores the exact prior subfilter", Ao.Bs.LOSS,
            onEdt(() -> LedgerPageProbe.data(page).entry.costView));
    }

    @Test
    public void overviewAndDrillTablesPageFiveRows() throws Exception
    {
        Am engine = session();
        for (int i = 0; i < 6; i++)
        {
            engine.getActiveSession().kf(gain(T0 + 1_000L + i * 1_000L, "Item " + i,
                2_000 + i, 1L, 100, 100L), 2_000);
            engine.getActiveSession().kf(food(T0 + 10_000L + i * 1_000L, "Food " + i,
                3_000 + i, 50L), 2_000);
        }
        for (int i = 0; i < 12; i++)
        {
            engine.getActiveSession().kf(iceBurst(T0 + 30_000L + i * 1_000L), 2_000);
        }
        LedgerHarness harness = new LedgerHarness(engine, T0 + 90_000L, entry());
        LedgerPage page = onEdt(() -> harness.attach(new LedgerPage(harness, id -> null)));

        assertEquals("overview Gains pages at five rows", 5,
            onEdt(() -> LedgerPageProbe.overviewRows(page, LedgerPageProbe.FinancialTable.GAINS)).size());
        assertEquals("1/2", onEdt(() -> LedgerPageProbe.overviewPageLabel(page, LedgerPageProbe.FinancialTable.GAINS)));
        assertEquals("overview Costs pages at five rows", 5,
            onEdt(() -> LedgerPageProbe.overviewRows(page, LedgerPageProbe.FinancialTable.COSTS)).size());

        String groupId = null;
        for (Br.Group group : harness.data().costs.groups)
        {
            if ("Ice Burst".equals(group.primaryName))
            {
                groupId = group.semanticGroupId;
            }
        }
        assertNotNull(groupId);
        String open = groupId;
        onEdt(() ->
        {
            harness.auz(open);
            return null;
        });
        assertEquals("every receipt stays reachable", 12,
            onEdt(() -> LedgerPageProbe.receiptCardTransactionIds(page)).size());
        assertEquals("the receipt list pages at five rows", 5,
            (int) onEdt(() -> TableRows.names(TableRows.tables(page.body(), "RECEIPTS").get(0)).size()));
    }

    @Test
    public void uncollectedSalesWaitInPendingNotMarket() throws Exception
    {
        MarketFixture market = new MarketFixture();
        for (int i = 0; i < MARKET_ITEMS.length; i++)
        {
            market.sell(i, MARKET_ITEMS[i], 10);
        }
        assertEquals("four market groups exist", 4, market.engine.ub().size());
        LedgerHarness harness = new LedgerHarness(market.engine, market.now + 1_000L, entry());
        LedgerPage page = onEdt(() -> harness.attach(new LedgerPage(harness, id -> null)));
        assertTrue("nothing is settled until the coins are collected",
            onEdt(() -> LedgerPageProbe.overviewRows(page, LedgerPageProbe.FinancialTable.MARKET)).isEmpty());
        assertEquals("every open sale waits in Pending", 4,
            (int) onEdt(() -> TableRows.of(page.body(), "PENDING").size()));
    }

    @Test
    public void anOfferCanceledBeforeAnyFillLeavesPending() throws Exception
    {
        MarketFixture market = new MarketFixture();
        market.sell(0, MARKET_ITEMS[0], 10);
        market.listAndCancel(1, MARKET_ITEMS[1], 10);
        LedgerHarness harness = new LedgerHarness(market.engine, market.now + 1_000L, entry());
        LedgerPage page = onEdt(() -> harness.attach(new LedgerPage(harness, id -> null)));
        List<String> pending = onEdt(() -> TableRows.of(page.body(), "PENDING"));
        assertEquals("only the uncollected sale waits; the canceled offer moved no money: " + pending,
            1, pending.size());
    }

    @Test
    public void emptyMarketKeepsItsHeader() throws Exception
    {
        Am engine = session();
        engine.getActiveSession().kf(gain(T0 + 1_000L, "Bones", 536, 1L, 3_200, 3_200L), 2_000);
        LedgerHarness harness = new LedgerHarness(engine, T0 + 50_000L, entry());
        LedgerPage page = onEdt(() -> harness.attach(new LedgerPage(harness, id -> null)));

        List<String> labels = onEdt(() -> labels(page.body()));
        assertTrue("an empty table keeps its header",
            labels.stream().anyMatch(label -> label.startsWith("MARKET \u00b7 0")));
        assertTrue(onEdt(() -> LedgerPageProbe.overviewRows(page, LedgerPageProbe.FinancialTable.MARKET)).isEmpty());
    }

    @Test
    public void reviewAndCorrectedFollowCounts() throws Exception
    {
        Am quiet = session();
        quiet.getActiveSession().kf(gain(T0 + 1_000L, "Bones", 536, 1L, 3_200, 3_200L), 2_000);
        LedgerHarness quietHarness = new LedgerHarness(quiet, T0 + 50_000L, entry());
        LedgerPage quietPage = onEdt(() ->
            quietHarness.attach(new LedgerPage(quietHarness, id -> null)));

        List<String> quietLabels = onEdt(() -> labels(quietPage.body()));
        assertFalse("zero Review hides its table", quietLabels.stream().anyMatch(label -> label.contains("REVIEW")));
        assertFalse("Corrected is closed by default",
            quietLabels.stream().anyMatch(label -> label.startsWith("CORRECTED")));
        onEdt(() ->
        {
            LedgerPageProbe.toggleCorrected(quietPage);
            return null;
        });
        assertTrue("Corrected history stays reachable through the menu",
            onEdt(() -> labels(quietPage.body())).contains("Nothing corrected in this scope"));

        Am engine = session();
        Ac uncertain = new Ac(T0 + 1_000L, null, Ai.UNCERTAIN,
            Aj.GENERIC, "", "Vorkath", true,
            java.util.Collections.singletonList(new Ab(777, "Unknown rune", -1L, 0, 0L)),
            Bd.UNCERTAIN, "Awaiting a decision", null);
        engine.getActiveSession().kf(uncertain, 2_000);
        Ac corrected = gain(T0 + 2_000L, "Willow logs", 1519, 1L, 100, 120L);
        corrected.ko(Ah.IGNORE, T0 + 3_000L, "excluded");
        engine.getActiveSession().kf(corrected, 2_000);
        LedgerHarness harness = new LedgerHarness(engine, T0 + 50_000L, entry());
        LedgerPage page = onEdt(() -> harness.attach(new LedgerPage(harness, id -> null)));
        assertTrue("a nonzero Review count shows its table",
            onEdt(() -> labels(page.body())).stream().anyMatch(label -> label.startsWith("(!) REVIEW \u00b7 1")));
        onEdt(() ->
        {
            LedgerPageProbe.toggleCorrected(page);
            return null;
        });
        assertTrue("a nonzero Corrected count is shown",
            onEdt(() -> labels(page.body())).stream().anyMatch(label -> label.startsWith("CORRECTED \u00b7 1")));
    }

    @Test
    public void scopeNetStaysCanonicalNotAVisiblePageSum() throws Exception
    {
        Am engine = session();
        for (int i = 0; i < 7; i++)
        {
            engine.getActiveSession().kf(gain(T0 + 1_000L + i * 1_000L, "Item " + i,
                2_000 + i, 1L, 100, 100L), 2_000);
        }
        LedgerHarness harness = new LedgerHarness(engine, T0 + 50_000L, entry());
        LedgerPage page = onEdt(() -> harness.attach(new LedgerPage(harness, id -> null)));
        Ao data = harness.data();
        assertEquals(5, onEdt(() -> LedgerPageProbe.overviewRows(page, LedgerPageProbe.FinancialTable.GAINS)).size());
        // The canonical scope Net survives the removed bar line: the tables page, the Net does not.
        assertEquals("the scope Net is canonical, not the visible page sum", 700L, data.net);
    }

    @Test
    public void profileFenceClearsOverviewAndDetailState() throws Exception
    {
        Am engine = session();
        engine.getActiveSession().kf(gain(T0 + 1_000L, "Bones", 536, 1L, 3_200, 3_200L), 2_000);
        LedgerHarness harness = new LedgerHarness(engine, T0 + 50_000L, entry());
        LedgerPage page = onEdt(() -> harness.attach(new LedgerPage(harness, id -> null)));
        onEdt(() ->
        {
            LedgerPageProbe.toggleCorrected(page);
            harness.auz(harness.data().gains.groups.get(0).semanticGroupId);
            return null;
        });
        assertTrue(onEdt(() -> detailOpen(page)));
        assertEquals("RECEIPTS", onEdt(() -> LedgerPageProbe.drill(page)));

        onEdt(() ->
        {
            page.agf();
            return null;
        });
        assertEquals("the fence clears the open detail", "OVERVIEW", onEdt(() -> LedgerPageProbe.drill(page)));
        onEdt(() ->
        {
            harness.selectionChanged(null, null, null);
            return null;
        });
        assertFalse("the next owner starts on the overview", onEdt(() -> detailOpen(page)));
        List<String> labels = onEdt(() -> labels(page.body()));
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("GAINS \u00b7")));
        assertFalse("the fence closes Corrected", labels.stream().anyMatch(label -> label.startsWith("CORRECTED")));
    }

    private static boolean detailOpen(LedgerPage page)
    {
        return LedgerPageProbe.data(page) != null && LedgerPageProbe.data(page).detail != null;
    }

    private static Am session()
    {
        Am engine = PresentationLifecycleTest.engine();
        engine.ajl("Vorkath", Cx.GENERAL, T0);
        return engine;
    }

    private static Ao.Entry entry()
    {
        return new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
            Ao.Bs.SUPPLIES, "", null, null, null, null);
    }

    private static Ac gain(long at, String name, int itemId, long quantity,
        int unitPrice, long value)
    {
        return new Ac(at, null, Ai.GAIN, Aj.GENERIC, "", "Vorkath",
            true, java.util.Collections.singletonList(new Ab(itemId, name, quantity, unitPrice, value,
                Av.GRAND_EXCHANGE)), Bd.LIKELY, "Test gain", null);
    }

    private static Ac food(long at, String name, int itemId, long value)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true,
            java.util.Collections.singletonList(new Ab(itemId, name, -1L, (int) value, -value,
                Av.GRAND_EXCHANGE)), Bd.CONFIRMED, "Test food", null);
        transaction.setActionKind(Au.EAT);
        return transaction;
    }

    private static Ac iceBurst(long at)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Ab(562, "Chaos rune", -4L, 106, -424L, Av.GRAND_EXCHANGE),
                new Ab(560, "Death rune", -2L, 188, -376L, Av.GRAND_EXCHANGE),
                new Ab(555, "Water rune", -4L, 5, -20L, Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(Au.CAST);
        transaction.ahu(Bb.of("Ice Burst"));
        return transaction;
    }

    /** Am-level GE custody fixture that produces one realized market settlement per item. */
    private static final class MarketFixture
    {
        final Am engine;
        final Bj ledger = new Bj();
        final Map<Integer, Long> inventory = new HashMap<>();
        long now = T0;

        MarketFixture()
        {
            GpManagerConfig config = new GpManagerConfig()
            {
                @Override public int stabilizationTicks() { return 0; }
                @Override public boolean keepTransferAuditRows() { return true; }
            };
            engine = new Am(deltas ->
            {
                List<Ab> flows = new ArrayList<>();
                for (Map.Entry<Integer, Long> delta : deltas.entrySet())
                {
                    int id = delta.getKey();
                    int unit = id == COINS ? 1 : 7;
                    Av source = id == COINS ? Av.FACE_VALUE
                        : Av.GRAND_EXCHANGE;
                    flows.add(new Ab(id, marketName(id), delta.getValue(), unit,
                        delta.getValue() * unit, source));
                }
                return flows;
            }, new TransactionClassifier(), PresentationLifecycleTest.config());
            engine.ajl("Trading", Cx.AUTO, now);
            inventory.put(COINS, 100_000L);
            for (int item : MARKET_ITEMS)
            {
                inventory.put(item, 10L);
            }
            engine.setBaseline(new Cc(inventory));
        }

        void sell(int slot, int item, int price)
        {
            now += 600L;
            observe(slot, GrandExchangeOfferState.SELLING, item, 10, 0, price, 0);
            now += 600L;
            inventory.remove(item);
            engine.yz();
            Cc snapshot = new Cc(inventory);
            for (int i = 0; i < 3; i++)
            {
                engine.adj(snapshot, now);
                now += 600L;
            }
            observe(slot, GrandExchangeOfferState.SOLD, item, 10, 10, price, 10 * price);
        }

        /** Lists the stack, cancels before anything sells, and collects the items back. */
        void listAndCancel(int slot, int item, int price)
        {
            now += 600L;
            observe(slot, GrandExchangeOfferState.SELLING, item, 10, 0, price, 0);
            now += 600L;
            inventory.remove(item);
            settle();
            observe(slot, GrandExchangeOfferState.CANCELLED_SELL, item, 10, 0, price, 0);
            now += 600L;
            inventory.put(item, 10L);
            settle();
        }

        private void settle()
        {
            engine.yz();
            Cc snapshot = new Cc(inventory);
            for (int i = 0; i < 3; i++)
            {
                engine.adj(snapshot, now);
                now += 600L;
            }
        }

        private void observe(int slot, GrandExchangeOfferState state, int item, int total, int traded,
            int price, int spent)
        {
            Bj.Transition transition = ledger.observe(
                new Bj.Snapshot(slot, state, item, total, traded, price, spent)).orElse(null);
            if (transition != null)
            {
                engine.abh(transition, marketName(transition.current.itemId),
                    now);
            }
        }

        private static String marketName(int id)
        {
            return id == COINS ? "Coins" : "Rune " + id;
        }
    }

    /** Host-faithful actions: selection/pages/filters re-capture and re-apply the page. */
    private static final class LedgerHarness implements LedgerPage.Actions
    {
        private final Am engine;
        private final long now;
        private Ao.Entry entry;
        private LedgerPage page;

        LedgerHarness(Am engine, long now, Ao.Entry entry)
        {
            this.engine = engine;
            this.now = now;
            this.entry = entry;
        }

        LedgerPage attach(LedgerPage created)
        {
            page = created;
            created.apply(data());
            return created;
        }

        Ao data()
        {
            return Ao.capture(engine, now, entry);
        }

        void auz(String groupId)
        {
            selectionChanged(null, null, groupId);
        }

        @Override
        public void selectionChanged(String transactionId, String contributionId, String groupId)
        {
            entry = entry.withSelection(transactionId, contributionId, groupId);
            page.apply(data());
        }

        @Override
        public void costViewChanged(Ao.Bs view)
        {
            entry = entry.withCostView(view);
            page.apply(data());
        }

        @Override
        public void searchChanged(String text)
        {
            entry = entry.withSearch(text);
            page.apply(data());
        }


        @Override public void openScopeMenu(JComponent anchor) { }

        @Override
        public Ao.Ef preview(String id, Ah correction)
        {
            return null;
        }

        @Override
        public LedgerPage.Ea correct(String id, Ah correction,
            long previewRevision)
        {
            return LedgerPage.Ea.REFUSED;
        }

        @Override public void split(String id, java.util.function.BiConsumer<long[], Ao.Ef> previewed) { }

        @Override public void undoCorrection() { }

        @Override public void decideAll(Cl decision) { }

        @Override public void refresh() { }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private static boolean clickButton(Component root, String textPrefix)
    {
        if (root instanceof AbstractButton)
        {
            String text = ((AbstractButton) root).getText();
            if (text != null && text.startsWith(textPrefix))
            {
                ((AbstractButton) root).doClick();
                return true;
            }
        }
        if (root instanceof Container)
        {
            for (Component child : ((Container) root).getComponents())
            {
                if (clickButton(child, textPrefix))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> labels(Component component)
    {
        List<String> out = new ArrayList<>();
        walk(component, out);
        return out;
    }

    private static void walk(Component component, List<String> out)
    {
        if (component instanceof JLabel)
        {
            String text = ((JLabel) component).getText();
            if (text != null && !text.isEmpty())
            {
                out.add(text);
            }
        }
        if (component instanceof AbstractButton)
        {
            String text = ((AbstractButton) component).getText();
            if (text != null && !text.isEmpty())
            {
                out.add(text);
            }
        }
        if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                walk(child, out);
            }
        }
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            return callable.call();
        }
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
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
}
