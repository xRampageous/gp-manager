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
        Am engine = PresentationLifecycleTest.engine();
        engine.ajl("Vorkath", Cx.GENERAL, T0);
        Ac bones = loot(T0 + 1_000L, 536, "Dragon bones", 2L, 3_000);
        engine.getActiveSession().kf(bones, 2_000);
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
            && preview.contains(Fmt.ru(-6_000L) + " gp"));
        assertEquals("nothing changes before Confirm", 6_000L, engine.getMetrics(T0 + 5_000L).net);

        assertTrue(onEdt(() -> click(page.body(), "Confirm")));
        assertEquals(0L, engine.getMetrics(T0 + 5_000L).net);
        assertEquals(Ah.IGNORE, engine.getActiveSession().sw(bones.getId())
            .getCorrection());
    }

    /** Owner 1.1: Split shows the same preview card, with Keep and Others, before it applies. */
    @Test
    public void aSplitPreviewsOnItsReceiptThenApplies() throws Exception
    {
        JsonCodec.bind(new com.google.gson.Gson());
        Am engine = PresentationLifecycleTest.engine();
        engine.ajl("Vorkath", Cx.GENERAL, T0);
        Ac bones = loot(T0 + 1_000L, 536, "Dragon bones", 2L, 3_000);
        engine.getActiveSession().kf(bones, 2_000);
        Harness harness = new Harness(engine, bones.getId());
        LedgerPage page = harness.page;

        onEdt(() ->
        {
            LedgerPageProbe.chooseCorrection(page, "Split…");
            return null;
        });
        List<String> preview = onEdt(() -> LedgerPageProbe.detailTexts(page));
        assertTrue("the split preview: " + preview, preview.contains("PREVIEW") && preview.contains("Keep")
            && preview.contains("Others") && preview.contains("After split")
            && preview.contains(Fmt.ru(-3_000L) + " gp"));
        assertEquals("nothing changes before Confirm", 2L, bones.quantity(536, true));

        assertTrue(onEdt(() -> click(page.body(), "Confirm")));
        assertEquals(1L, bones.quantity(536, true));
        assertEquals(3_000L, engine.getMetrics(T0 + 5_000L).net);
    }

    @Test
    public void historyReceiptsAreReadOnly() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        engine.ajl("Vorkath", Cx.GENERAL, T0);
        Ac bones = loot(T0 + 1_000L, 536, "Dragon bones", 1L, 3_000);
        engine.getActiveSession().kf(bones, 2_000);
        String id = engine.getActiveSession().getId();
        engine.sx(T0 + 2_000L);
        Harness harness = new Harness(engine, bones.getId());
        harness.entry = new Ao.Entry(Ao.Scope.HISTORY, id, "Vorkath",
            Ao.Bs.ALL, "", bones.getId(), null, null, null);
        onEdt(() ->
        {
            harness.refresh();
            return null;
        });
        List<String> texts = onEdt(() -> LedgerPageProbe.detailTexts(harness.page));
        assertFalse(texts.contains("Correct ▾"));
        assertTrue(texts.stream().anyMatch(text -> text.contains("Read-only")));
    }

    private static Ac loot(long at, int itemId, String name, long quantity, int price)
    {
        return Tx.of(at, Ai.LOOT, Aj.LOOT, "Vorkath", true,
            Collections.singletonList(new Ab(itemId, name, quantity, price, quantity * price)));
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
        final Am engine;
        final LedgerPage page;
        Ao.Entry entry;

        Harness(Am engine, String transactionId) throws Exception
        {
            this.engine = engine;
            this.entry = new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.ALL, "", transactionId, null, null, null);
            this.page = onEdt(() -> new LedgerPage(this, id -> null));
            onEdt(() ->
            {
                refresh();
                return null;
            });
        }

        @Override public void openScopeMenu(javax.swing.JComponent anchor) { }
        @Override public void costViewChanged(Ao.Bs view) { }
        @Override public void searchChanged(String text) { }
        /** Keeps one: the sidebar's prompt is the controller's; this answers it. */
        @Override
        public void split(String id, java.util.function.BiConsumer<long[], Ao.Ef> previewed)
        {
            previewed.accept(new long[] {536, 1, 2}, Ao.splitPreview(engine, id, 536, 1L, T0 + 5_000L));
        }

        @Override
        public void applySplit(String id, long[] split, long revision)
        {
            engine.kr(id, (int) split[0], split[1], T0 + 5_000L, "test");
        }

        @Override public void undoCorrection() { }
        @Override public void decideAll(Cl decision) { }

        @Override
        public void selectionChanged(@Nullable String transactionId, @Nullable String contributionId,
            @Nullable String groupId)
        {
            entry = entry.withSelection(transactionId, contributionId, groupId);
            refresh();
        }

        @Override
        public Ao.Ef preview(String id, Ah correction)
        {
            return Ao.preview(engine, id, correction, T0 + 5_000L);
        }

        @Override
        public LedgerPage.Ea correct(String id, Ah correction, long revision)
        {
            return engine.getRevision() != revision ? LedgerPage.Ea.STALE
                : engine.qi(id, correction, T0 + 5_000L, "test")
                    ? LedgerPage.Ea.APPLIED : LedgerPage.Ea.REFUSED;
        }

        @Override
        public void refresh()
        {
            page.apply(Ao.capture(engine, T0 + 5_000L, entry));
        }
    }
}
