package com.gpmanager;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A single Review decision must call the exact-row action, never the bulk action. */
public class SingleReviewDecisionTest
{
    @Test
    public void decidingReceiptALeavesReceiptBUntouchedAndSkipsBulkPath() throws Exception
    {
        RecordingActions actions = new RecordingActions();
        LedgerPage page = onEdt(() -> new LedgerPage(actions, id -> null));
        Cu receiptA = row("A");
        JPopupMenu menu = onEdt(() -> LedgerPageProbe.reviewMenu(page, receiptA));

        onEdt(() ->
        {
            ((JMenuItem) menu.getComponent(0)).doClick();
            return null;
        });

        assertEquals("A", actions.decidedTransactionId);
        assertEquals(1, actions.unresolved.size());
        assertTrue(actions.unresolved.contains("B"));
        assertFalse("single decision must not invoke Decide All", actions.bulkCalled);
    }

    private static Cu row(String id)
    {
        return new Cu(id, "session", 1L, 0L,
            Arrays.asList(new Cu.Item(1, "Unpriced item", -1L, -10L)), -10L,
            "Needs a decision", EnumSet.allOf(Cl.class));
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        if (SwingUtilities.isEventDispatchThread()) return callable.call();
        java.util.concurrent.atomic.AtomicReference<T> value = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
        {
            try { value.set(callable.call()); }
            catch (Throwable ex) { failure.set(ex); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return value.get();
    }

    private static final class RecordingActions implements LedgerPage.Actions
    {
        final Set<String> unresolved = new HashSet<>(Arrays.asList("A", "B"));
        String decidedTransactionId = "";
        boolean bulkCalled;

        @Override public void openScopeMenu(javax.swing.JComponent anchor) { }
        @Override public void costViewChanged(Ao.Bs view) { }
        @Override public void searchChanged(String text) { }
        @Override public Ao.Ef preview(String id, Ah correction) { return null; }
        @Override public LedgerPage.Ea correct(String id, Ah correction, long revision)
        { return LedgerPage.Ea.REFUSED; }
        @Override public void split(String id, java.util.function.BiConsumer<long[], Ao.Ef> previewed) { }
        @Override public void undoCorrection() { }
        @Override public void decideAll(Cl decision)
        {
            bulkCalled = true;
            unresolved.clear();
        }
        @Override public void decide(String id, Cl decision)
        {
            decidedTransactionId = id;
            unresolved.remove(id);
        }
        @Override public void refresh() { }
    }
}
