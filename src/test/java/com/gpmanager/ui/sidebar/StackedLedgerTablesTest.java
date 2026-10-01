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
        engine.getActiveSession().addTransaction(gain(T0 + 1_000L, "Dragon bones", 536, 1L, 3_200, 3_200L), 2_000);
        engine.getActiveSession().addTransaction(food(T0 + 2_000L, "Shark", SHARK, 950L), 2_000);
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
        assertTrue(labels.stream().anyMatch(label -> label.startsWith("MARKET \u00b7")));
        // The scope Net stays on Live/HUD; the page bar is the scope toolbar only (owner 2026-10-01).
        assertFalse("no multi-line Total block remains", labels.contains("TOTAL"));
        assertFalse("no global All/Gains/Costs/Market/Review/Corrected tab strip",
            onEdt(() -> containsNav(page.body())));
        assertTrue("Losses selector is All / Supplies n / Items n / Charges n", labels.contains("All")
            && labels.stream().anyMatch(label -> label.startsWith("Supplies "))
            && labels.stream().anyMatch(label -> label.startsWith("Items "))
            && labels.stream().anyMatch(label -> label.startsWith("Charges ")));
    }

    @Test
    public void searchKeepsWholeGroupsAndFindsDistinctSpellsWithoutMerging()
    {
        Engine engine = session();
        engine.getActiveSession().addTransaction(exactCast(T0 + 1_000L, "Ice Burst", 1, 1, 1), 2_000);
        engine.getActiveSession().addTransaction(exactCast(T0 + 2_000L, "Blood Barrage", 1, 1, 1), 2_000);

        LedgerData data = capture(engine, T0 + 3_000L, new LedgerData.Entry(
            LedgerData.Scope.CURRENT_GRIND, null, null, LedgerData.CostView.SUPPLIES,
            "Death rune", null, null, null, null));

        assertEquals("search reaches the underlying rune of both spells", 2, data.costs.entries);
        SemanticFinancialProjection.Group burst = groupNamed(data.costs.groups, "Ice Burst");
        SemanticFinancialProjection.Group blood = groupNamed(data.costs.groups, "Blood Barrage");
        assertNotNull(burst);
        assertNotNull(blood);
        assertEquals("a matched group keeps its full total, not the Death-rune portion",
            -180L, burst.value);
        assertEquals(-180L, blood.value);
        assertFalse("the two spells never merge", burst.semanticGroupId.equals(blood.semanticGroupId));
    }

    @Test
    public void tablesCarryEveryRowAndTakeNewArrivals()
    {
        Engine engine = session();
        for (int i = 0; i < 25; i++)
        {
            engine.getActiveSession().addTransaction(gain(T0 + i * 1_000L, "Item " + i, 2_000 + i, 1L, 100, 100L), 2_000);
        }
        for (int i = 0; i < 25; i++)
        {
            engine.getActiveSession().addTransaction(food(T0 + 50_000L + i * 1_000L, "Food " + i,
                3_000 + i, 10L), 2_000);
        }
        LedgerData data = capture(engine, T0 + 100_000L, new LedgerData.Entry(
            LedgerData.Scope.CURRENT_GRIND, null, null, LedgerData.CostView.SUPPLIES,
            "", null, null, null, null));
        assertEquals("gains carry every row; the page table pages them", 25, data.gains.groups.size());
        assertEquals("costs carry every row", 25, data.costs.groups.size());

        engine.getActiveSession().addTransaction(gain(T0 + 200_000L, "Newest", 9_999, 1L, 100, 100L), 2_000);
        LedgerData later = capture(engine, T0 + 300_000L, new LedgerData.Entry(
            LedgerData.Scope.CURRENT_GRIND, null, null, LedgerData.CostView.SUPPLIES,
            "", null, null, null, null));
        assertEquals(26, later.gains.groups.size());
        assertTrue("the new receipt is present in the whole-filter set",
            later.gains.groups.stream().anyMatch(group -> "Newest".equals(group.primaryName)));
    }

    @Test
    public void selectedDetailAnchorsAcrossArrivalsAndRowCorrectionsStayExact()
    {
        Engine engine = session();
        Transaction oldest = gain(T0 + 1_000L, "Oldest gain", 1_111, 1L, 100, 100L);
        engine.getActiveSession().addTransaction(oldest, 2_000);
        for (int i = 0; i < 25; i++)
        {
            engine.getActiveSession().addTransaction(gain(T0 + 2_000L + i * 1_000L, "Item " + i,
                2_000 + i, 1L, 100, 100L), 2_000);
        }
        LedgerData anchored = capture(engine, T0 + 50_000L, new LedgerData.Entry(
            LedgerData.Scope.CURRENT_GRIND, null, null, LedgerData.CostView.SUPPLIES,
            "", oldest.getId(), null, null, null));
        assertNotNull("the selected receipt resolves its detail", anchored.detail);
        assertTrue("the detail is the selected row's group",
            anchored.detail.group.containsTransaction(oldest.getId()));
        for (int i = 0; i < 5; i++)
        {
            engine.getActiveSession().addTransaction(gain(T0 + 100_000L + i * 1_000L, "Newer " + i,
                5_000 + i, 1L, 100, 100L), 2_000);
        }
        LedgerData stillAnchored = capture(engine, T0 + 200_000L, new LedgerData.Entry(
            LedgerData.Scope.CURRENT_GRIND, null, null, LedgerData.CostView.SUPPLIES,
            "", oldest.getId(), null, null, null));
        assertNotNull("the open detail stays anchored, not silently reset", stillAnchored.detail);
        assertTrue(stillAnchored.detail.group.containsTransaction(oldest.getId()));
        assertEquals("selection never smuggles a search string", "", stillAnchored.entry.search);
    }

    @Test
    public void totalsReconcileAndCountUniqueReceipts()
    {
        Engine engine = session();
        engine.getActiveSession().addTransaction(gain(T0 + 1_000L, "Dragon bones", 536, 1L, 3_200, 3_200L), 2_000);
        engine.getActiveSession().addTransaction(food(T0 + 2_000L, "Shark", SHARK, 950L), 2_000);
        // A mixed alchemy-shaped receipt contributes one Gain and one Cost entry from one receipt.
        Transaction alchemy = new Transaction(T0 + 3_000L, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(561, "Nature rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE),
                new Flow(995, "Coins", 120L, 1, 120L, PriceSource.FACE_VALUE)),
            ClassificationConfidence.CONFIRMED, "High alchemy", null);
        alchemy.setActionKind(ActionKind.CAST);
        engine.getActiveSession().addTransaction(alchemy, 2_000);

        LedgerData data = capture(engine, T0 + 4_000L, LedgerData.Entry.current());
        long net = engine.getMetrics(T0 + 4_000L).net;
        assertEquals("Total Net is canonical, not a page sum", net, data.net);
        assertEquals("complete detail reconciles the equation",
            net, data.total.gains + data.total.costs + data.total.market);
        assertTrue(data.reconciled);
        assertEquals("entries count the semantic financial groups", 4, data.total.entries);
        assertEquals("receipts are unique, not per-group", 3, data.total.receipts);
    }

    @Test
    public void unpricedSupplyStaysInCostsSuppliesAndNeverOpensReview()
    {
        Engine engine = session();
        Transaction unpriced = new Transaction(T0 + 1_000L, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true,
            java.util.Collections.singletonList(new Flow(SHARK, "Shark", -1L, 0, 0L,
                PriceSource.UNPRICED)),
            ClassificationConfidence.CONFIRMED, "Unpriced food", null);
        unpriced.setActionKind(ActionKind.EAT);
        engine.getActiveSession().addTransaction(unpriced, 2_000);

        LedgerData data = capture(engine, T0 + 2_000L, LedgerData.Entry.current());
        assertEquals("the unpriced supply belongs to Costs/Supplies", 1, data.costs.entries);
        assertTrue(data.costs.incomplete);
        assertEquals(0L, data.total.costs);
        assertEquals("an unpriced supply is not a review decision", 0, data.review.scopeCount);
    }

    @Test
    public void reviewAndCorrectedAuditsStayFunctional()
    {
        Engine engine = session();
        Transaction uncertain = new Transaction(T0 + 1_000L, null, TransactionType.UNCERTAIN,
            Context.GENERIC, "", "Vorkath", true,
            java.util.Collections.singletonList(new Flow(777, "Unknown rune", -1L, 0, 0L)),
            ClassificationConfidence.UNCERTAIN, "Awaiting a decision", null);
        engine.getActiveSession().addTransaction(uncertain, 2_000);
        Transaction corrected = gain(T0 + 2_000L, "Willow logs", 1519, 1L, 100, 120L);
        corrected.applyCorrection(Correction.IGNORE, T0 + 3_000L, "excluded");
        engine.getActiveSession().addTransaction(corrected, 2_000);

        LedgerData data = capture(engine, T0 + 4_000L, LedgerData.Entry.current());
        assertEquals("only genuine review decisions are listed", 1, data.review.scopeCount);
        assertEquals(uncertain.getId(), data.review.rows.get(0).transactionId);
        assertEquals("corrected receipts keep an exact audit page", 1, data.corrected.size());
        assertEquals(corrected.getId(), data.corrected.get(0).transactionId);
        assertTrue("review rows never inflate the financial tables", data.gains.entries == 0);
    }

    @Test
    public void collapsedTableBuildsNoRowComponents() throws Exception
    {
        Engine engine = session();
        for (int i = 0; i < 6; i++)
        {
            engine.getActiveSession().addTransaction(gain(T0 + 1_000L + i * 1_000L, "Item " + i,
                2_000 + i, 1L, 100, 100L), 2_000);
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
        engine.startCustomSession("Old Grind", SessionMode.GENERAL, old);
        engine.getActiveSession().addTransaction(gain(old + 1_000L, "Old bones", 536, 1L, 3_200, 3_200L), 2_000);
        String sessionId = engine.getActiveSession().getId();
        engine.finishCustomSession(old + 2_000L);
        long compactedNet = engine.getHistorySession(sessionId).compactedContribution().getNet();
        assertTrue(compactedNet == 0L);
        engine.compactOlderThan(1, T0);

        LedgerData data = capture(engine, T0, new LedgerData.Entry(
            LedgerData.Scope.HISTORY, sessionId, "Old Grind"));
        assertTrue("the compacted scope is honest", data.compacted);
        assertFalse("row detail is unavailable", data.detailedHistoryAvailable);
        assertEquals("canonical Net is preserved", 3_200L, data.net);
        assertEquals("no item rows are fabricated", 0, data.gains.entries);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Engine session()
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, T0);
        return engine;
    }

    private static LedgerData capture(Engine engine, long now, LedgerData.Entry entry)
    {
        return LedgerData.capture(engine, now, entry);
    }

    private static SemanticFinancialProjection.Group groupNamed(
        List<SemanticFinancialProjection.Group> groups, String name)
    {
        for (SemanticFinancialProjection.Group group : groups)
        {
            if (name.equals(group.primaryName))
            {
                return group;
            }
        }
        return null;
    }

    private static Transaction exactCast(long at, String name, int deathQty, int chaosQty, int waterQty)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(DEATH, "Death rune", -deathQty, 100, -deathQty * 100L),
                new Flow(CHAOS, "Chaos rune", -chaosQty, 50, -chaosQty * 50L),
                new Flow(WATER, "Water rune", -waterQty, 30, -waterQty * 30L)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(ActionKind.CAST);
        transaction.setObservedActionLabel(ActionLabel.of(name));
        return transaction;
    }

    private static Transaction gain(long at, String name, int itemId, long quantity,
        int unitPrice, long value)
    {
        return new Transaction(at, null, TransactionType.GAIN, Context.GENERIC, "", "Vorkath",
            true, java.util.Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value,
                PriceSource.GRAND_EXCHANGE)), ClassificationConfidence.LIKELY, "Test gain", null);
    }

    private static Transaction food(long at, String name, int itemId, long value)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true,
            java.util.Collections.singletonList(new Flow(itemId, name, -1L, (int) value, -value,
                PriceSource.GRAND_EXCHANGE)), ClassificationConfidence.CONFIRMED, "Test food", null);
        transaction.setActionKind(ActionKind.EAT);
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
        @Override public void split(String id) { }
        @Override public void undoCorrection() { }
        @Override public void decideAll(ReviewDecision decision) { }
        @Override public void refresh() { }
    }
}
