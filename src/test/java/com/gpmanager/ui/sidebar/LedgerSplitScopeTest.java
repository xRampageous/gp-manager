package com.gpmanager;

import java.util.Arrays;
import java.util.Collections;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-10-01 (F02): Split names one item, refuses mixed or indivisible receipts,
 * and refuses a stale sheet after the prompt.
 */
public class LedgerSplitScopeTest
{
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void mixedItemReceiptsRefuseWithAnExplanation() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction transaction = fixture.book(
            new Flow(536, "Dragon bones", 2L, 2_000, 4_000L),
            new Flow(1753, "Blue dragonhide", 1L, 2_000, 2_000L));

        fixture.split(transaction.getId());

        assertEquals("no prompt opens for a mixed receipt", "", ShellProbe.sheetTitle(fixture.shell()));
        assertTrue(ShellProbe.noticeText(fixture.shell()).startsWith("Not split"));
        assertEquals("nothing was split", 2L, transaction.quantity(536, true));
        assertEquals(1L, transaction.quantity(1753, true));
    }

    @Test
    public void aSingleUnitReceiptRefuses() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction transaction = fixture.book(new Flow(536, "Dragon bones", 1L, 2_000, 2_000L));

        fixture.split(transaction.getId());

        assertEquals("", ShellProbe.sheetTitle(fixture.shell()));
        assertTrue(ShellProbe.noticeText(fixture.shell()).startsWith("Not split"));
        assertEquals(1L, transaction.quantity(536, true));
    }

    @Test
    public void duplicateFlowsOfOneItemStillSplit() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction transaction = fixture.book(
            new Flow(536, "Dragon bones", 1L, 2_000, 2_000L),
            new Flow(536, "Dragon bones", 1L, 2_000, 2_000L));

        fixture.split(transaction.getId());
        assertEquals("the prompt names the receipt", "Split receipt", ShellProbe.sheetTitle(fixture.shell()));
        fixture.type("1");
        fixture.press("OK");

        assertEquals("the kept share stays on the receipt", 1L, transaction.quantity(536, true));
    }

    @Test
    public void keepMustLeaveAtLeastOneForTheOtherSide() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction transaction = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));

        fixture.split(transaction.getId());
        fixture.type("2");
        fixture.press("OK");
        assertTrue(ShellProbe.noticeText(fixture.shell()).startsWith("Not split"));
        assertEquals(2L, transaction.quantity(536, true));

        fixture.split(transaction.getId());
        fixture.type("0");
        fixture.press("OK");
        assertTrue(ShellProbe.noticeText(fixture.shell()).startsWith("Not split"));
        assertEquals(2L, transaction.quantity(536, true));
    }

    @Test
    public void aReceiptThatChangesWhileThePromptIsOpenSplitsNothing() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction transaction = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));
        String owner = fixture.engine.getActiveSession().getId();

        fixture.split(transaction.getId());
        fixture.closeAndStartAnotherRun();
        fixture.type("1");
        fixture.press("OK");

        assertTrue(ShellProbe.noticeText(fixture.shell()).startsWith("Not split"));
        assertEquals("the viewed receipt is untouched", 2L,
            fixture.engine.getHistorySession(owner).findTransaction(transaction.getId()).quantity(536, true));
    }

    @Test
    public void aRefusedEngineSplitSaysSo() throws Exception
    {
        Fixture fixture = new Fixture(new RefusingEngine());
        Transaction transaction = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));

        fixture.split(transaction.getId());
        fixture.type("1");
        fixture.press("OK");

        assertTrue(ShellProbe.noticeText(fixture.shell()).startsWith("Not split"));
        assertEquals(2L, transaction.quantity(536, true));
    }

    private static final class Fixture
    {
        final Engine engine;
        final SidebarPanel panel;
        final LedgerController controller;

        Fixture() throws Exception
        {
            this(new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
                new GpManagerConfig() {}));
        }

        Fixture(Engine engine) throws Exception
        {
            this.engine = engine;
            panel = onEdt(() -> new SidebarPanel(engine, new GpManagerConfig() {}, null));
            controller = new LedgerController(panel);
            engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW);
        }

        Shell shell()
        {
            return panel.shell();
        }

        Transaction book(Flow... flows)
        {
            Transaction transaction = new Transaction(NOW + 1_000L, null, TransactionType.LOOT, Context.LOOT, "", "Vorkath", true,
                Arrays.asList(flows), ClassificationConfidence.CONFIRMED, "fixture", null);
            engine.getActiveSession().addTransaction(transaction, 2_000);
            return transaction;
        }

        void split(String transactionId) throws Exception
        {
            onEdt(() ->
            {
                controller.split(transactionId);
                return null;
            });
        }

        void type(String text) throws Exception
        {
            onEdt(() ->
            {
                ShellProbe.typeSheet(panel.shell(), text);
                return null;
            });
        }

        void press(String label) throws Exception
        {
            onEdt(() ->
            {
                ShellProbe.pressSheet(panel.shell(), label);
                return null;
            });
        }

        void closeAndStartAnotherRun()
        {
            engine.finishCustomSession(NOW + 2_000L);
            engine.startCustomSession("Other", SessionMode.GENERAL, NOW + 3_000L);
        }
    }

    /** Refuses every split, so the controller's refusal feedback can be pinned. */
    private static final class RefusingEngine extends Engine
    {
        RefusingEngine()
        {
            super(deltas -> Collections.emptyList(), new TransactionClassifier(),
                new GpManagerConfig() {});
        }

        @Override
        synchronized boolean applyItemSplit(String transactionId, int itemId, long keepQuantity, long now,
            String optionalNote)
        {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                result[0] = callable.call();
            }
            catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
        });
        return (T) result[0];
    }
}
