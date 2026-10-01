package com.gpmanager;

import java.util.Collections;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Owner 2026-10-01 (F05): the Review pager stays reachable and Decide all names its count. */
public class ReviewBulkPagerTest
{
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void thePagerStaysReachableAndTheBulkLineNamesItsCount() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.apply(LedgerData.Entry.current());

        assertNull("the header pager is not replaced", fixture.page.review.action);
        assertEquals("1/2", fixture.page.review.pageLabel.getText());
        assertTrue(fixture.page.review.next.isEnabled());
        assertEquals("7 of 7", fixture.bulkLabel().getText());
        assertEquals("the bulk target count matches the preview", 7,
            fixture.engine.previewDecideAll(ReviewDecision.GAIN, row -> true, NOW + 20_000L).rowCount());

        fixture.page.review.next.doClick();
        assertEquals("the sixth receipt is reachable", "2/2",
            fixture.page.review.pageLabel.getText());
        assertTrue(fixture.page.review.previous.isEnabled());

        Engine.DecideAllPreview one = fixture.engine.previewDecideAll(ReviewDecision.GAIN,
            row -> row.transactionId.equals(fixture.page.data.review.rows.get(0).transactionId),
            NOW + 30_000L);
        assertEquals(1, one.rowCount());
        assertTrue(fixture.engine.applyDecideAll(one, NOW + 30_000L) >= 0);
        fixture.apply(LedgerData.Entry.current());
        assertEquals("the count follows a decision", "6 of 6", fixture.bulkLabel().getText());
    }

    @Test
    public void aFilteredBulkLineNamesTheFilteredCount() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.apply(LedgerData.Entry.current().withSearch("Alpha"));
        assertEquals("2 of 7", fixture.bulkLabel().getText());
    }

    @Test
    public void aHistoricalScopeHasNeitherBulkLineNorPager() throws Exception
    {
        Fixture fixture = new Fixture();
        String id = fixture.engine.getActiveSession().getId();
        fixture.engine.finishCustomSession(NOW);
        fixture.apply(LedgerData.Entry.current().withScope(LedgerData.Scope.HISTORY, id, "Vorkath"));

        assertNull("read-only keeps its audit hidden", fixture.page.review.extra);
        assertNull(fixture.page.review.action);
    }

    private static final class Fixture
    {
        final Engine engine;
        final LedgerPage page;

        Fixture() throws Exception
        {
            engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
                new GpManagerConfig() {});
            engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW - 3_600_000L);
            for (int i = 0; i < 7; i++)
            {
                String name = i < 2 ? "Alpha" : "Bones " + (char) ('A' + i);
                engine.getActiveSession().addTransaction(new Transaction(NOW - 3_000_000L + i, null, TransactionType.GAIN,
                    Context.GENERIC, "", "Loot", true,
                    Collections.singletonList(new Flow(536, name, 1L, 100, 100L)),
                    ClassificationConfidence.UNCERTAIN, "", null), 500);
            }
            page = onEdt(() -> new LedgerPage(new NoopActions(), id -> null));
        }

        void apply(LedgerData.Entry entry) throws Exception
        {
            onEdt(() ->
            {
                page.apply(LedgerData.capture(engine, NOW, entry));
                return null;
            });
        }

        JLabel bulkLabel()
        {
            if (page.review.extra == null)
            {
                return null;
            }
            for (java.awt.Component component : page.review.extra.getComponents())
            {
                if (component instanceof JLabel)
                {
                    return (JLabel) component;
                }
            }
            throw new AssertionError("the bulk line has no count label");
        }
    }

    private static final class NoopActions implements LedgerPage.Actions
    {
        @Override public void openScopeMenu(javax.swing.JComponent anchor) { }
        @Override public void costViewChanged(LedgerData.CostView view) { }
        @Override public void searchChanged(String text) { }
        @Override public LedgerData.CorrectionPreview preview(String id, Correction correction) { return null; }
        @Override public LedgerPage.CorrectionOutcome correct(String id, Correction correction, long revision)
        { return LedgerPage.CorrectionOutcome.REFUSED; }
        @Override public void split(String id) { }
        @Override public void undoCorrection() { }
        @Override public void decideAll(ReviewDecision decision) { }
        @Override public void refresh() { }
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        if (SwingUtilities.isEventDispatchThread())
        {
            return callable.call();
        }
        java.util.concurrent.atomic.AtomicReference<T> value = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                value.set(callable.call());
            }
            catch (Throwable ex)
            {
                failure.set(ex);
            }
        });
        if (failure.get() != null)
        {
            throw new AssertionError(failure.get());
        }
        return value.get();
    }
}
