package com.gpmanager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The selected receipt's one Correct ▾ menu: its preview stays on that receipt, then applies. */
public class LedgerCorrectMenuTest
{
    private static final long T0 = 1_700_000_000_000L;

    @Test
    public void aCorrectionPreviewsOnItsReceiptThenApplies() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, T0);
        Transaction bones = loot(T0 + 1_000L, 536, "Dragon bones", 2L, 3_000);
        engine.getActiveSession().addTransaction(bones, 2_000);
        Harness harness = new Harness(engine, bones.getId());
        LedgerPage page = harness.page;

        List<String> opened = onEdt(() -> LedgerPageProbe.detailTexts(page));
        assertEquals("the group title and its row name it; the receipt panel never repeats it: " + opened, 2L,
            opened.stream().filter("Dragon bones"::equals).count());

        List<String> menu = onEdt(() -> LedgerPageProbe.correctMenu(page));
        assertEquals("a gain can be split; Undo is always the latest correction", Arrays.asList("Count as Revenue",
            "Count as Cost", "Mark as Transfer", "Exclude", "Split…", "Undo latest correction"), menu);

        onEdt(() ->
        {
            LedgerPageProbe.chooseCorrection(page, "Exclude");
            return null;
        });
        List<String> preview = onEdt(() -> LedgerPageProbe.detailTexts(page));
        assertTrue("the preview opens on the chosen receipt: " + preview, preview.contains("PREVIEW")
            && preview.contains(Fmt.exactSigned(-6_000L) + " gp"));
        assertEquals("nothing changes before Confirm", 6_000L, engine.getMetrics(T0 + 5_000L).net);

        assertTrue(onEdt(() -> click(page.body(), "Confirm")));
        assertEquals(0L, engine.getMetrics(T0 + 5_000L).net);
        assertEquals(Correction.IGNORE, engine.getActiveSession().findTransaction(bones.getId())
            .getCorrection());
    }

    @Test
    public void historyReceiptsAreReadOnly() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, T0);
        Transaction bones = loot(T0 + 1_000L, 536, "Dragon bones", 1L, 3_000);
        engine.getActiveSession().addTransaction(bones, 2_000);
        String id = engine.getActiveSession().getId();
        engine.finishCustomSession(T0 + 2_000L);
        Harness harness = new Harness(engine, bones.getId());
        harness.entry = new LedgerData.Entry(LedgerData.Scope.HISTORY, id, "Vorkath",
            LedgerData.CostView.ALL, "", bones.getId(), null, null, null);
        onEdt(() ->
        {
            harness.refresh();
            return null;
        });
        List<String> texts = onEdt(() -> LedgerPageProbe.detailTexts(harness.page));
        assertFalse(texts.contains("Correct ▾"));
        assertTrue(texts.stream().anyMatch(text -> text.contains("Read-only")));
    }

    private static Transaction loot(long at, int itemId, String name, long quantity, int price)
    {
        return Tx.of(at, TransactionType.LOOT, Context.LOOT, "Vorkath", true,
            Collections.singletonList(new Flow(itemId, name, quantity, price, quantity * price)));
    }

    private static boolean click(java.awt.Component root, String text)
    {
        if (root instanceof javax.swing.AbstractButton && text.equals(((javax.swing.AbstractButton) root).getText()))
        {
            ((javax.swing.AbstractButton) root).doClick();
            return true;
        }
        if (root instanceof java.awt.Container)
        {
            for (java.awt.Component child : ((java.awt.Container) root).getComponents())
            {
                if (click(child, text))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        Object[] out = new Object[1];
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                out[0] = callable.call();
            }
            catch (Exception ex)
            {
                failure[0] = ex;
            }
        });
        if (failure[0] != null)
        {
            throw failure[0];
        }
        @SuppressWarnings("unchecked")
        T value = (T) out[0];
        return value;
    }

    /** Recaptures on every selection, as the sidebar does, and books through the engine. */
    private static final class Harness implements LedgerPage.Actions
    {
        final Engine engine;
        final LedgerPage page;
        LedgerData.Entry entry;

        Harness(Engine engine, String transactionId) throws Exception
        {
            this.engine = engine;
            this.entry = new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.ALL, "", transactionId, null, null, null);
            this.page = onEdt(() -> new LedgerPage(this, id -> null));
            onEdt(() ->
            {
                refresh();
                return null;
            });
        }

        @Override public void openScopeMenu(javax.swing.JComponent anchor) { }
        @Override public void costViewChanged(LedgerData.CostView view) { }
        @Override public void searchChanged(String text) { }
        @Override public void split(String id) { }
        @Override public void undoCorrection() { }
        @Override public void decideAll(ReviewDecision decision) { }

        @Override
        public void selectionChanged(@Nullable String transactionId, @Nullable String contributionId,
            @Nullable String groupId)
        {
            entry = entry.withSelection(transactionId, contributionId, groupId);
            refresh();
        }

        @Override
        public LedgerData.CorrectionPreview preview(String id, Correction correction)
        {
            return LedgerData.preview(engine, id, correction, T0 + 5_000L);
        }

        @Override
        public LedgerPage.CorrectionOutcome correct(String id, Correction correction, long revision)
        {
            return engine.getRevision() != revision ? LedgerPage.CorrectionOutcome.STALE
                : engine.correctTransaction(id, correction, T0 + 5_000L, "test")
                    ? LedgerPage.CorrectionOutcome.APPLIED : LedgerPage.CorrectionOutcome.REFUSED;
        }

        @Override
        public void refresh()
        {
            page.apply(LedgerData.capture(engine, T0 + 5_000L, entry));
        }
    }
}
