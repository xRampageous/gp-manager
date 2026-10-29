package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 1.7.0: Ledger previews and split prompts read one canonical state under one engine hold;
 * corrections are checked and applied together on the mutation owner, save at once, and never
 * reach an owner that changed meanwhile.
 */
public class LedgerAtomicActionTest
{
    private static final long NOW = 1_700_000_000_000L;

    /** Notes any engine read made without the caller holding the engine monitor. */
    private static final class Watched extends Engine
    {
        volatile boolean watching, unlocked;

        Watched()
        {
            super(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        }

        void check()
        {
            if (watching && !Thread.holdsLock(this)) unlocked = true;
        }

        @Override
        Session session()
        {
            check();
            return super.session();
        }

        @Override
        Metrics metrics(long now)
        {
            check();
            return super.metrics(now);
        }

        @Override
        long revision()
        {
            check();
            return super.revision();
        }
    }

    private static final class Fixture
    {
        final Watched engine = new Watched();
        final SidebarPanel panel;
        final LedgerController controller;
        final List<Runnable> clientThread = new ArrayList<>();
        final AtomicInteger saves = new AtomicInteger();

        Fixture() throws Exception
        {
            JsonCodec.bind(new com.google.gson.Gson());
            Persistence storage = new Persistence(null, null, null, null, engine)
            {
                @Override
                boolean scheduleSave()
                {
                    saves.incrementAndGet();
                    return true;
                }

                @Override
                SaveStatus saveStatus()
                {
                    return SaveStatus.neverSaved();
                }
            };
            panel = onEdt(() -> new SidebarPanel(engine, new GpManagerConfig() {}, null, null, storage));
            controller = new LedgerController(panel);
            engine.startCustom("Vorkath", NOW);
        }

        Transaction book(Flow... flows)
        {
            Transaction transaction = new Transaction(NOW + 1_000L, TransactionType.LOOT, Context.LOOT, "", "Vorkath",
                true, Arrays.asList(flows), Confidence.CONFIRMED, "fixture", null);
            engine.session().addTransaction(transaction, 2_000, false);
            return transaction;
        }

        void queueMutations()
        {
            panel.mutationOwner = clientThread::add;
        }

        void runClientThread()
        {
            List<Runnable> work = new ArrayList<>(clientThread);
            clientThread.clear();
            work.forEach(Runnable::run);
        }
    }

    @Test
    public void aCorrectionPreviewReadsOneCanonicalState() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction receipt = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));
        fixture.engine.watching = true;
        LedgerData.CorrectionPreview preview = LedgerData.preview(fixture.engine, receipt.id(), Correction.IGNORE,
            NOW + 2_000L);
        fixture.engine.watching = false;
        assertFalse("Net before/after and revision come from one engine hold", fixture.engine.unlocked);
        assertEquals(fixture.engine.revision(), preview.revision);
        assertEquals(-4_000L, preview.change);
    }

    @Test
    public void theSplitPromptReadsItsFactsInOneHold() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction receipt = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));
        onEdt(() ->
        {
            fixture.engine.watching = true;
            fixture.controller.split(receipt.id(), (split, preview) -> { });
            fixture.engine.watching = false;
            return null;
        });
        assertFalse(fixture.engine.unlocked);
        assertEquals("Split entry", ShellProbe.sheetTitle(fixture.panel.shell));
    }

    @Test
    public void aCorrectionRunsOnTheMutationOwnerAndSavesAtOnce() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction receipt = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));
        fixture.queueMutations();
        LedgerData.CorrectionPreview preview = LedgerData.preview(fixture.engine, receipt.id(), Correction.IGNORE,
            NOW + 2_000L);
        LedgerPage.CorrectionOutcome outcome = onEdt(() ->
            fixture.controller.correct(receipt.id(), Correction.IGNORE, preview.revision));

        assertNull("the outcome reports back from the mutation owner", outcome);
        assertTrue("nothing changed on the Swing thread", receipt.counts());
        fixture.runClientThread();
        assertFalse("applied on the client thread", receipt.counts());
        assertEquals("saved at once, like other sidebar edits", 1, fixture.saves.get());
        assertTrue("and undoable", fixture.engine.undoCorrection());
        assertTrue(receipt.counts());
    }

    @Test
    public void aBookingBetweenPreviewAndConfirmChangesNothing() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction receipt = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));
        fixture.queueMutations();
        LedgerData.CorrectionPreview preview = LedgerData.preview(fixture.engine, receipt.id(), Correction.IGNORE,
            NOW + 2_000L);
        onEdt(() -> fixture.controller.correct(receipt.id(), Correction.IGNORE, preview.revision));
        fixture.book(new Flow(995, "Coins", 10L, 1, 10L));
        fixture.runClientThread();
        assertTrue("stale: the receipt is untouched", receipt.counts());
        // The refusal is a toast, so the page rebuild after the edit cannot wipe it.
        onEdt(() -> { fixture.panel.refreshNow(); return null; });
        SwingUtilities.invokeAndWait(() -> { });
        String notice = onEdt(() -> ShellProbe.noticeText(fixture.panel.shell));
        assertTrue(notice, notice.contains("Entries changed while the preview was open"));
    }

    @Test
    public void theReceiptMenuUndoWorksWithoutOpeningTheMoreMenu() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction receipt = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));
        assertTrue(fixture.engine.correctTransaction(receipt.id(), Correction.IGNORE, "test"));
        fixture.panel.scope = LedgerData.Entry.current();
        onEdt(() ->
        {
            fixture.panel.ledger.apply(LedgerData.capture(fixture.engine, NOW + 3_000L, fixture.panel.scope));
            fixture.panel.ledger.corrections(receipt.id(), receipt.id(), false).get("Undo latest correction").run();
            return null;
        });
        assertTrue("the receipt's own Undo restores it", receipt.counts());
    }

    @Test
    public void aReceiptUndoCannotFollowASessionSwitchBeforeOrAfterDispatch() throws Exception
    {
        for (boolean queued : new boolean[] {false, true}) {
            Fixture fixture = new Fixture();
            Transaction old = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));
            fixture.engine.correctTransaction(old.id(), Correction.IGNORE, "old");
            fixture.panel.scope = LedgerData.Entry.current();
            Runnable undo = onEdt(() -> fixture.panel.ledger.corrections(old.id(), old.id(), false)
                .get("Undo latest correction"));
            if (queued) {
                fixture.queueMutations();
                onEdt(() -> { undo.run(); return null; });
            }
            assertTrue(fixture.engine.finishCustom(NOW + 3_000L));
            fixture.engine.startCustom("Next Grind", NOW + 3_001L);
            Transaction next = fixture.book(new Flow(536, "Dragon bones", 1L, 2_000, 2_000L));
            fixture.engine.correctTransaction(next.id(), Correction.IGNORE, "new");
            if (queued) fixture.runClientThread();
            else onEdt(() -> { undo.run(); return null; });
            assertFalse("stale receipt Undo must not alter the new Grind, queued=" + queued, next.counts());
        }
    }

    @Test
    public void aDelayedCorrectionNeverReachesAnOwnerThatChanged() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction receipt = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));
        fixture.queueMutations();
        LedgerData.CorrectionPreview preview = LedgerData.preview(fixture.engine, receipt.id(), Correction.IGNORE,
            NOW + 2_000L);
        onEdt(() -> fixture.controller.correct(receipt.id(), Correction.IGNORE, preview.revision));
        fixture.panel.suspend();
        fixture.panel.resume();
        fixture.runClientThread();
        assertTrue("A -> B -> A: the queued correction is dropped", receipt.counts());
    }

    @Test
    public void aSplitPromptAnsweredAfterAnOwnerRoundTripSplitsNothing() throws Exception
    {
        Fixture fixture = new Fixture();
        Transaction receipt = fixture.book(new Flow(536, "Dragon bones", 2L, 2_000, 4_000L));
        boolean[] previewed = {false};
        onEdt(() ->
        {
            fixture.controller.split(receipt.id(), (split, preview) ->
            {
                previewed[0] = true;
                fixture.controller.applySplit(receipt.id(), split, preview.revision);
            });
            return null;
        });
        // A full suspend already closes the sheet; the callback re-checks the owner by itself too.
        fixture.panel.ownerToken = new Object();
        onEdt(() ->
        {
            ShellProbe.typeSheet(fixture.panel.shell, "1");
            ShellProbe.pressSheet(fixture.panel.shell, "OK");
            return null;
        });
        assertFalse("the old prompt cannot preview for the new owner", previewed[0]);
        assertEquals(2L, receipt.quantity(536, true));
    }

    @Test
    public void correctionsAndUndoMoveEveryTotalTogether() throws Exception
    {
        Fixture fixture = new Fixture();
        Flow loot = new Flow(536, "Dragon bones", 2L, 2_000, 4_000L);
        loot.raid = "0:" + java.util.UUID.randomUUID();
        Transaction receipt = fixture.book(loot);
        Engine engine = fixture.engine;
        engine.raids.read("all");
        assertEquals(4_000L, FixtureReads.money(engine.raids, "0")[0]);
        long net = engine.metrics(NOW + 2_000L).net;

        assertTrue(engine.correctTransaction(receipt.id(), Correction.IGNORE, "test"));
        engine.raids.read("all");
        assertEquals("All raids follows the correction", 0L, FixtureReads.money(engine.raids, "0")[0]);
        assertEquals(net - 4_000L, engine.metrics(NOW + 2_000L).net);

        assertTrue(engine.undoCorrection());
        engine.raids.read("all");
        assertEquals("and the Undo, without a second booking", 4_000L, FixtureReads.money(engine.raids, "0")[0]);
        assertEquals(net, engine.metrics(NOW + 2_000L).net);
        assertEquals(1, engine.session().transactions().size());
    }

    @SuppressWarnings("unchecked")
    private static <T> T onEdt(java.util.concurrent.Callable<T> callable) throws Exception
    {
        Object[] result = new Object[1];
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                result[0] = callable.call();
            }
            catch (Exception ex)
            {
                failure[0] = ex;
            }
        });
        if (failure[0] != null) throw failure[0];
        return (T) result[0];
    }
}
