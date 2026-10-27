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

        fixture.split(transaction.id());

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

        fixture.split(transaction.id());

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

        fixture.split(transaction.id());
        assertEquals("the prompt names the entry", "Split entry", ShellProbe.sheetTitle(fixture.shell()));
        fixture.type("1");
        fixture.press("OK");

        assertEquals("the kept share stays on the receipt", 1L, transaction.quantity(536, true));
    }

    @Test
    public void keepMustLeaveAtLeastOneForTheOtherSide() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction transaction = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));

        fixture.split(transaction.id());
        fixture.type("2");
        fixture.press("OK");
        assertTrue(ShellProbe.noticeText(fixture.shell()).startsWith("Not split"));
        assertEquals(2L, transaction.quantity(536, true));

        fixture.split(transaction.id());
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
        String owner = fixture.engine.session().id();

        fixture.split(transaction.id());
        fixture.closeAndStartAnotherRun();
        fixture.type("1");
        fixture.press("OK");

        assertTrue(ShellProbe.noticeText(fixture.shell()).startsWith("Not split"));
        assertEquals("the viewed receipt is untouched", 2L,
            fixture.engine.historySession(owner).findTransaction(transaction.id()).quantity(536, true));
    }

    @Test
    public void aRefusedEngineSplitSaysSo() throws Exception
    {
        Fixture fixture = new Fixture(new RefusingEngine());
        Transaction transaction = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));

        fixture.split(transaction.id());
        fixture.type("1");
        fixture.press("OK");

        assertTrue(ShellProbe.noticeText(fixture.shell()).startsWith("Not split"));
        assertEquals(2L, transaction.quantity(536, true));
    }

    /** Owner 1.1: Split shows Keep, Others and the Net it leaves before anything changes. */
    @Test
    public void theSplitPreviewsItsNetFirstAndTheResultMatches() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction transaction = fixture.book(new Flow(536, "Dragon bones", 4L, 2_000, 8_000L));
        long before = fixture.engine.metrics(NOW + 2_000L).net;
        fixture.confirm = false;

        fixture.split(transaction.id());
        fixture.type("1");
        fixture.press("OK");

        assertEquals("keep one of four", Arrays.asList(536L, 1L, 4L),
            Arrays.asList(fixture.pending[0], fixture.pending[1], fixture.pending[2]));
        assertEquals("the preview changes nothing", 4L, transaction.quantity(536, true));
        assertEquals(before - 6_000L, fixture.previewed.afterNet);

        onEdt(() ->
        {
            fixture.controller.applySplit(transaction.id(), fixture.pending, fixture.previewed.revision);
            return null;
        });
        assertEquals(1L, transaction.quantity(536, true));
        assertEquals("the result is what the preview said", fixture.previewed.afterNet,
            fixture.engine.metrics(NOW + 2_000L).net);
    }

    @Test
    public void aBookingAfterThePreviewSplitsNothing() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction transaction = fixture.book(new Flow(536, "Dragon bones", 4L, 2_000, 8_000L));
        fixture.confirm = false;
        fixture.split(transaction.id());
        fixture.type("1");
        fixture.press("OK");
        fixture.book(new Flow(995, "Coins", 10L, 1, 10L));

        onEdt(() ->
        {
            fixture.controller.applySplit(transaction.id(), fixture.pending, fixture.previewed.revision);
            return null;
        });
        assertTrue(ShellProbe.noticeText(fixture.shell()).startsWith("Not split"));
        assertEquals(4L, transaction.quantity(536, true));
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
            JsonCodec.bind(new com.google.gson.Gson());
            panel = onEdt(() -> new SidebarPanel(engine, new GpManagerConfig() {}, null, null, null));
            controller = new LedgerController(panel);
            engine.startCustom("Vorkath", NOW);
        }

        Shell shell()
        {
            return panel.shell;
        }

        Transaction book(Flow... flows)
        {
            Transaction transaction = new Transaction(NOW + 1_000L, TransactionType.LOOT, Context.LOOT, "", "Vorkath", true,
                Arrays.asList(flows), Confidence.CONFIRMED, "fixture", null);
            engine.session().addTransaction(transaction, 2_000, false);
            return transaction;
        }

        LedgerData.CorrectionPreview previewed;
        long[] pending;
        boolean confirm = true;

        void split(String transactionId) throws Exception
        {
            onEdt(() ->
            {
                // Owner 1.1: the split previews first; this harness confirms at once.
                controller.split(transactionId, (split, preview) ->
                {
                    previewed = preview;
                    pending = split;
                    if (confirm) controller.applySplit(transactionId, split, preview.revision);
                });
                return null;
            });
        }

        void type(String text) throws Exception
        {
            onEdt(() ->
            {
                ShellProbe.typeSheet(panel.shell, text);
                return null;
            });
        }

        void press(String label) throws Exception
        {
            onEdt(() ->
            {
                ShellProbe.pressSheet(panel.shell, label);
                return null;
            });
        }

        void closeAndStartAnotherRun()
        {
            engine.finishCustom(NOW + 2_000L);
            engine.startCustom("Other", NOW + 3_000L);
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
        synchronized boolean splitItem(String transactionId, int itemId, long keepQuantity,
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
