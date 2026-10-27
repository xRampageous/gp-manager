package com.gpmanager;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** PRE-R5C.2B: stacked financial tables, group-level search and reconciled totals. */
public class StackedLedgerTablesTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final int DEATH = 560;
    private static final int CHAOS = 562;
    private static final int WATER = 555;
    private static final int BLOOD = 565;
    private static final int SHARK = 385;

    @Test
    public void threeStackedTablesReplaceTheGlobalTabStrip() throws Exception
    {
        Engine engine = session();
        engine.session().addTransaction(gain(T0 + 1_000L, "Dragon bones", 536, 1L, 3_200, 3_200L), 2_000, false);
        engine.session().addTransaction(food(T0 + 2_000L, "Shark", SHARK, 950L), 2_000, false);
        LedgerData data = capture(engine, T0 + 3_000L, LedgerData.Entry.current());
        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(new NoopActions(), id -> null);
            created.apply(data);
            return created;
        });

        List<String> labels = onEdt(() -> labels(page.body()));
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("GAINS \u00b7")));
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("LOSSES \u00b7")));
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("TRADE \u00b7")));
        // The scope Net stays on Live/HUD; the page bar is the scope toolbar only (owner 2026-10-01).
        assertFalse("no multi-line Total block remains", labels.contains("TOTAL"));
        assertFalse("no global All/Gains/Costs/Market/Review/Corrected tab strip",
            onEdt(() -> containsNav(page.body())));
        // Owner 1.1: the Losses view is one dropdown beside its header.
        assertTrue("the Losses picker reads its view: " + labels, labels.contains("All ⌄"));
    }

    @Test
    public void searchKeepsWholeGroupsAndFindsDistinctSpellsWithoutMerging()
    {
        Engine engine = session();
        engine.session().addTransaction(exactCast(T0 + 1_000L, "Ice Burst", 1, 1, 1), 2_000, false);
        engine.session().addTransaction(exactCast(T0 + 2_000L, "Blood Barrage", 1, 1, 1), 2_000, false);

        LedgerData data = capture(engine, T0 + 3_000L, new LedgerData.Entry(
            LedgerData.Scope.CURRENT_GRIND, null, null, LedgerData.CostView.SUPPLIES,
            "Death rune", null, null, null, null));

        assertEquals("search reaches the underlying rune of both spells", 2, data.cost.entries);
        Semantics.Group burst = groupNamed(data.cost.groups, "Ice Burst");
        Semantics.Group blood = groupNamed(data.cost.groups, "Blood Barrage");
        assertNotNull(burst);
        assertNotNull(blood);
        assertEquals("a matched group keeps its full total, not the Death-rune portion",
            -180L, burst.value);
        assertEquals(-180L, blood.value);
        assertFalse("the two spells never merge", burst.id.equals(blood.id));
    }

    @Test
    public void tablesCarryEveryRowAndTakeNewArrivals()
    {
        Engine engine = session();
        for (int i = 0; i < 25; i++)
        {
            engine.session().addTransaction(gain(T0 + i * 1_000L, "Item " + i, 2_000 + i, 1L, 100, 100L), 2_000, false);
        }
        for (int i = 0; i < 25; i++)
        {
            engine.session().addTransaction(food(T0 + 50_000L + i * 1_000L, "Food " + i,
                3_000 + i, 10L), 2_000, false);
        }
        LedgerData data = capture(engine, T0 + 100_000L, new LedgerData.Entry(
            LedgerData.Scope.CURRENT_GRIND, null, null, LedgerData.CostView.SUPPLIES,
            "", null, null, null, null));
        assertEquals("gains carry every row; the page table pages them", 25, data.income.groups.size());
        assertEquals("costs carry every row", 25, data.cost.groups.size());

        engine.session().addTransaction(gain(T0 + 200_000L, "Newest", 9_999, 1L, 100, 100L), 2_000, false);
        LedgerData later = capture(engine, T0 + 300_000L, new LedgerData.Entry(
            LedgerData.Scope.CURRENT_GRIND, null, null, LedgerData.CostView.SUPPLIES,
            "", null, null, null, null));
        assertEquals(26, later.income.groups.size());
        assertTrue("the new receipt is present in the whole-filter set",
            later.income.groups.stream().anyMatch(group -> "Newest".equals(group.name)));
    }

    @Test
    public void selectedDetailAnchorsAcrossArrivalsAndRowCorrectionsStayExact()
    {
        Engine engine = session();
        Transaction oldest = gain(T0 + 1_000L, "Oldest gain", 1_111, 1L, 100, 100L);
        engine.session().addTransaction(oldest, 2_000, false);
        for (int i = 0; i < 25; i++)
        {
            engine.session().addTransaction(gain(T0 + 2_000L + i * 1_000L, "Item " + i,
                2_000 + i, 1L, 100, 100L), 2_000, false);
        }
        LedgerData anchored = capture(engine, T0 + 50_000L, new LedgerData.Entry(
            LedgerData.Scope.CURRENT_GRIND, null, null, LedgerData.CostView.SUPPLIES,
            "", oldest.id(), null, null, null));
        assertNotNull("the selected receipt resolves its detail", anchored.detail);
        assertTrue("the detail is the selected row's group",
            anchored.detail.group.containsTransaction(oldest.id()));
        for (int i = 0; i < 5; i++)
        {
            engine.session().addTransaction(gain(T0 + 100_000L + i * 1_000L, "Newer " + i,
                5_000 + i, 1L, 100, 100L), 2_000, false);
        }
        LedgerData stillAnchored = capture(engine, T0 + 200_000L, new LedgerData.Entry(
            LedgerData.Scope.CURRENT_GRIND, null, null, LedgerData.CostView.SUPPLIES,
            "", oldest.id(), null, null, null));
        assertNotNull("the open detail stays anchored, not silently reset", stillAnchored.detail);
        assertTrue(stillAnchored.detail.group.containsTransaction(oldest.id()));
        assertEquals("selection never smuggles a search string", "", stillAnchored.entry.search);
    }

    @Test
    public void totalsReconcileAndCountUniqueReceipts()
    {
        Engine engine = session();
        engine.session().addTransaction(gain(T0 + 1_000L, "Dragon bones", 536, 1L, 3_200, 3_200L), 2_000, false);
        engine.session().addTransaction(food(T0 + 2_000L, "Shark", SHARK, 950L), 2_000, false);
        // A mixed alchemy-shaped receipt contributes one Gain and one Cost entry from one receipt.
        Transaction alchemy = new Transaction(T0 + 3_000L, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(561, "Nature rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE),
                new Flow(995, "Coins", 120L, 1, 120L, PriceSource.FACE_VALUE)),
            Confidence.CONFIRMED, "High alchemy", null);
        alchemy.setAction(ActionKind.CAST);
        engine.session().addTransaction(alchemy, 2_000, false);

        LedgerData data = capture(engine, T0 + 4_000L, LedgerData.Entry.current());
        long net = engine.metrics(T0 + 4_000L).net;
        assertEquals("Total Net is canonical, not a page sum", net, data.net);
        assertEquals("complete detail reconciles the equation",
            net, data.total.income + data.total.cost + data.total.market);
        assertTrue(data.balanced);
        assertEquals("entries count the semantic financial groups", 4, data.total.entries);
        assertEquals("receipts are unique, not per-group", 3, data.total.receipts);
    }

    @Test
    public void unpricedSupplyStaysInCostsSuppliesAndNeverOpensReview()
    {
        Engine engine = session();
        Transaction unpriced = new Transaction(T0 + 1_000L, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true,
            java.util.Collections.singletonList(new Flow(SHARK, "Shark", -1L, 0, 0L,
                PriceSource.UNPRICED)),
            Confidence.CONFIRMED, "Unpriced food", null);
        unpriced.setAction(ActionKind.EAT);
        engine.session().addTransaction(unpriced, 2_000, false);

        LedgerData data = capture(engine, T0 + 2_000L, LedgerData.Entry.current());
        assertEquals("the unpriced supply belongs to Costs/Supplies", 1, data.cost.entries);
        assertTrue("the unpriced supply stays incomplete at the group level",
                data.cost.groups.get(0).incomplete());
        assertEquals(0L, data.total.cost);
        assertEquals("an unpriced supply is not a review decision", 0, data.review.total);
    }

    @Test
    public void reviewAndCorrectedAuditsStayFunctional()
    {
        Engine engine = session();
        Transaction uncertain = new Transaction(T0 + 1_000L, TransactionType.UNCERTAIN,
            Context.GENERIC, "", "Vorkath", true,
            java.util.Collections.singletonList(new Flow(777, "Unknown rune", -1L, 0, 0L)),
            Confidence.UNCERTAIN, "Awaiting a decision", null);
        engine.session().addTransaction(uncertain, 2_000, false);
        Transaction corrected = gain(T0 + 2_000L, "Willow logs", 1519, 1L, 100, 120L);
        corrected.applyCorrection(Correction.IGNORE, "excluded");
        engine.session().addTransaction(corrected, 2_000, false);

        LedgerData data = capture(engine, T0 + 4_000L, LedgerData.Entry.current());
        assertEquals("only genuine review decisions are listed", 1, data.review.total);
        assertEquals(uncertain.id(), data.review.rows.get(0).transaction);
        assertEquals("corrected receipts keep an exact audit page", 1, data.corrected.size());
        assertEquals(corrected.id(), data.corrected.get(0).transaction);
        assertTrue("review rows never inflate the financial tables", data.income.entries == 0);
    }

    @Test
    public void collapsedTableBuildsNoRowComponents() throws Exception
    {
        Engine engine = session();
        for (int i = 0; i < 6; i++)
        {
            engine.session().addTransaction(gain(T0 + 1_000L + i * 1_000L, "Item " + i,
                2_000 + i, 1L, 100, 100L), 2_000, false);
        }
        LedgerData data = capture(engine, T0 + 10_000L, LedgerData.Entry.current());
        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(new NoopActions(), id -> null);
            created.apply(data);
            return created;
        });
        int rowsBefore = onEdt(() -> TableRows.visibleRows(page.body()));
        onEdt(() ->
        {
            LedgerPageProbe.toggleTable(page, LedgerPageProbe.FinancialTable.GAINS);
            return null;
        });
        int rowsAfter = onEdt(() -> TableRows.visibleRows(page.body()));
        assertEquals("the overview builds one five-row page", 5, rowsBefore);
        assertEquals("collapsed table builds header only", 0, rowsAfter);
        onEdt(() ->
        {
            LedgerPageProbe.toggleTable(page, LedgerPageProbe.FinancialTable.GAINS);
            return null;
        });
        int rowsAgain = onEdt(() -> TableRows.visibleRows(page.body()));
        assertEquals(rowsBefore, rowsAgain);
    }

    @Test
    public void compactedHistoryShowsSummaryOnlyWithoutInventingRows()
    {
        Engine engine = PresentationLifecycleTest.engine();
        long old = T0 - 400L * 24L * 60L * 60L * 1_000L;
        engine.startCustom("Old Grind", old);
        engine.session().addTransaction(gain(old + 1_000L, "Old bones", 536, 1L, 3_200, 3_200L), 2_000, false);
        String sessionId = engine.session().id();
        engine.finishCustom(old + 2_000L);
        long compactedNet = engine.historySession(sessionId).compactedContribution().net();
        assertTrue(compactedNet == 0L);
        engine.compactOlderThan(1, T0);

        LedgerData data = capture(engine, T0, new LedgerData.Entry(
            LedgerData.Scope.HISTORY, sessionId, "Old Grind"));
        assertTrue("the compacted scope is honest", data.compacted);
        assertFalse("row detail is unavailable", data.detailed);
        assertEquals("canonical Net is preserved", 3_200L, data.net);
        assertEquals("no item rows are fabricated", 0, data.income.entries);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Engine session()
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.startCustom("Vorkath", T0);
        return engine;
    }

    private static LedgerData capture(Engine engine, long now, LedgerData.Entry entry)
    {
        return LedgerData.capture(engine, now, entry);
    }

    private static Semantics.Group groupNamed(
        List<Semantics.Group> groups, String name)
    {
        for (Semantics.Group group : groups)
        {
            if (name.equals(group.name))
            {
                return group;
            }
        }
        return null;
    }

    private static Transaction exactCast(long at, String name, int deathQty, int chaosQty, int waterQty)
    {
        Transaction transaction = new Transaction(at, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(DEATH, "Death rune", -deathQty, 100, -deathQty * 100L),
                new Flow(CHAOS, "Chaos rune", -chaosQty, 50, -chaosQty * 50L),
                new Flow(WATER, "Water rune", -waterQty, 30, -waterQty * 30L)),
            Confidence.CONFIRMED, "Exact fixture", null);
        transaction.setAction(ActionKind.CAST);
        transaction.setActionLabel(ActionLabel.of(name));
        return transaction;
    }

    private static Transaction gain(long at, String name, int itemId, long quantity,
        int unitPrice, long value)
    {
        return new Transaction(at, TransactionType.GAIN, Context.GENERIC, "", "Vorkath",
            true, java.util.Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value,
                PriceSource.GRAND_EXCHANGE)), Confidence.LIKELY, "Test gain", null);
    }

    private static Transaction food(long at, String name, int itemId, long value)
    {
        Transaction transaction = new Transaction(at, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true,
            java.util.Collections.singletonList(new Flow(itemId, name, -1L, (int) value, -value,
                PriceSource.GRAND_EXCHANGE)), Confidence.CONFIRMED, "Test food", null);
        transaction.setAction(ActionKind.EAT);
        return transaction;
    }

    private static List<String> labels(Component component)
    {
        List<String> out = new ArrayList<>();
        walkLabels(component, out);
        return out;
    }

    private static void walkLabels(Component component, List<String> out)
    {
        if (component instanceof JLabel)
        {
            String text = ((JLabel) component).getText();
            if (text != null && !text.isEmpty())
            {
                out.add(text);
            }
        }
        if (component instanceof javax.swing.AbstractButton)
        {
            String text = ((javax.swing.AbstractButton) component).getText();
            if (text != null && !text.isEmpty())
            {
                out.add(text);
            }
        }
        if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                walkLabels(child, out);
            }
        }
    }

    private static boolean containsNav(Component component)
    {
        if (component instanceof net.runelite.client.ui.components.materialtabs.MaterialTabGroup)
        {
            return true;
        }
        if (component instanceof Container)
        {
            for (Component child : ((Container) component).getComponents())
            {
                if (containsNav(child))
                {
                    return true;
                }
            }
        }
        return false;
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

    private static final class NoopActions implements LedgerPage.Actions
    {
        @Override public void openScopeMenu(javax.swing.JComponent anchor) { }
        @Override public void costViewChanged(LedgerData.CostView view) { }
        @Override public void searchChanged(String text) { }
        @Override public LedgerData.CorrectionPreview preview(String id,
            Correction correction) { return null; }
        @Override public LedgerPage.CorrectionOutcome correct(String id,
            Correction correction, long previewRevision)
        { return LedgerPage.CorrectionOutcome.REFUSED; }
        @Override public void split(String id, java.util.function.BiConsumer<long[], LedgerData.CorrectionPreview> previewed) { }
        @Override public void undoCorrection() { }
        @Override public void refresh() { }
    }
}
