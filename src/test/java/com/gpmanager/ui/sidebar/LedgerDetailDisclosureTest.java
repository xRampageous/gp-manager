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

/** One click from a Ledger row to its receipts; the selected receipt opens under them. */
public class LedgerDetailDisclosureTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final int DEATH = 560;
    private static final int CHAOS = 562;
    private static final int WATER = 555;
    private static final int SHARK = 385;

    @Test
    public void oneClickListsOneRowPerCanonicalTransactionWithItsResources() throws Exception
    {
        Am engine = session();
        Ac first = iceBurst(T0 + 1_000L);
        engine.getActiveSession().kf(first, 2_000);
        engine.getActiveSession().kf(iceBurst(T0 + 2_000L), 2_000);
        engine.getActiveSession().kf(iceBurst(T0 + 3_000L), 2_000);
        // A Shark group whose transaction also carries an unrelated coin gain.
        Ac mixed = new Ac(T0 + 4_000L, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Ab(SHARK, "Shark", -1L, 950, -950L, Av.GRAND_EXCHANGE),
                new Ab(995, "Coins", 500L, 1, 500L, Av.FACE_VALUE)),
            Bd.CONFIRMED, "Loot and food", null);
        mixed.setActionKind(Au.EAT);
        engine.getActiveSession().kf(mixed, 2_000);

        Ao data = capture(engine, T0 + 10_000L,
            entry(Ao.Bs.SUPPLIES, first.getId()));
        assertNotNull(data.detail);
        assertEquals("the action total reconciles", -2_460L, data.detail.group.value);
        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(new NoopActions(), id -> null);
            created.apply(data);
            return created;
        });

        List<String> cards = onEdt(() -> LedgerPageProbe.receiptCardTransactionIds(page));
        assertEquals("one row per canonical transaction, not per contribution", 3, cards.size());
        assertTrue(cards.contains(first.getId()));
        List<String> cardTexts = onEdt(() -> TableRows.of(page.body(), "RECEIPTS"));
        assertEquals(3, cardTexts.size());
        assertTrue("each row keeps the selected action total: " + cardTexts,
            cardTexts.stream().allMatch(text -> text.contains(Fmt.ru(-820) + " gp")));
        assertTrue("each row keeps its compact resource summary: " + cardTexts,
            cardTexts.stream().allMatch(text -> text.contains("Chaos rune") && text.contains("Death rune")
                && text.contains("Water rune")));
        assertTrue("rows hide canonical UUIDs",
            cardTexts.stream().noneMatch(text -> text.matches(".*[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}.*")));

        assertEquals("the linked receipt opens under the list", "EXACT", onEdt(() -> LedgerPageProbe.drill(page)));
        List<String> exactLabels = onEdt(() -> labels(page.body()));
        assertEquals("ids never show", 0, uuidLabels(exactLabels));
        assertTrue("one Correct menu", exactLabels.stream().anyMatch(label -> label.startsWith("Correct ▾")));
        assertFalse("no technical or raw panels remain",
            exactLabels.stream().anyMatch(label -> label.startsWith("Technical details") || label.startsWith("Raw evidence")));
        assertEquals(Arrays.asList("Count as Revenue", "Count as Cost", "Mark as Transfer", "Exclude",
            "Undo latest correction"), onEdt(() -> LedgerPageProbe.correctMenu(page)));

        // Single-resource group: the row shows the group's contribution, never the transaction net.
        Ao sharkData = capture(engine, T0 + 10_000L,
            entry(Ao.Bs.SUPPLIES, mixed.getId()));
        Br.Group shark = groupNamed(sharkData.costs.groups, "Shark");
        assertNotNull(shark);
        List<Ao.Card> sharkCards = Ao.cards(shark);
        assertEquals(1, sharkCards.size());
        assertEquals("a Shark row shows the Shark contribution, not the transaction Net",
            -950L, sharkCards.get(0).value);
    }

    @Test
    public void anUnpricedChildMakesItsReceiptRowIncomplete()
    {
        Am engine = session();
        engine.getActiveSession().kf(unpricedDeathBurst(T0 + 1_000L), 2_000);
        engine.getActiveSession().kf(unpricedDeathBurst(T0 + 2_000L), 2_000);
        Ao data = capture(engine, T0 + 10_000L,
            entry(Ao.Bs.SUPPLIES, null));
        // Its runes are Ice Burst's exact cost, so the row reads as the spell (owner 2026-09-28).
        Br.Group group = groupNamed(data.costs.groups, "Ice Burst");
        assertNotNull(group);
        assertTrue(group.incomplete());
        for (Ao.Card card : Ao.cards(group))
        {
            assertTrue("an unpriced child makes the row incomplete", card.incomplete);
            assertEquals("the known subtotal counts only the priced children", -444L, card.knownSubtotal);
        }
    }

    @Test
    public void measuredContextsNeverShareAGroup()
    {
        Am engine = session();
        engine.getActiveSession().kf(measuredLead(T0 + 1_000L, "Measured charge spend · Toxic blowpipe"), 2_000);
        engine.getActiveSession().kf(measuredLead(T0 + 2_000L, "Measured charge spend · Trident of the swamp"), 2_000);
        Ao data = capture(engine, T0 + 10_000L,
            entry(Ao.Bs.CHARGES, null));
        // Owner 2026-09-28: measured charge use has its own Charges chip, apart from Supplies.
        assertEquals(2, data.costCounts[Ao.Bs.CHARGES.ordinal()]);
        assertEquals(2, data.costs.groups.size());
        assertTrue("Supplies leaves charge use to Charges", capture(engine, T0 + 10_000L,
            entry(Ao.Bs.SUPPLIES, null)).costs.groups.stream().allMatch(group -> group.usedBy.isEmpty()));

        Br.Group blowpipe = null;
        for (Br.Group group : data.costs.groups)
        {
            if ("Zulrah's scales".equals(group.primaryName) && "Toxic blowpipe".equals(group.usedBy))
            {
                blowpipe = group;
            }
        }
        assertNotNull(blowpipe);
        assertEquals("a different measured context never joins this group", 1, Ao.cards(blowpipe).size());
        for (Br.Receipt receipt : blowpipe.receipts)
        {
            assertEquals("Toxic blowpipe", receipt.usedBy);
        }
    }

    @Test
    public void ownerFenceAndLiveAnchoringClearOrPreserveTheRightState() throws Exception
    {
        Am engine = session();
        Ac first = iceBurst(T0 + 1_000L);
        engine.getActiveSession().kf(first, 2_000);
        Ao data = capture(engine, T0 + 2_000L,
            entry(Ao.Bs.SUPPLIES, first.getId()));
        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(new NoopActions(), id -> null);
            created.apply(data);
            return created;
        });
        assertEquals("EXACT", onEdt(() -> LedgerPageProbe.drill(page)));

        // A compatible new receipt keeps the open detail anchored and updates its totals.
        engine.getActiveSession().kf(iceBurst(T0 + 3_000L), 2_000);
        Ao updated = capture(engine, T0 + 4_000L,
            entry(Ao.Bs.SUPPLIES, first.getId()));
        assertNotNull("the open group detail survives new compatible activity", updated.detail);
        onEdt(() ->
        {
            page.apply(updated);
            return null;
        });
        assertEquals("the open receipt stays anchored across compatible arrivals", "EXACT",
            onEdt(() -> LedgerPageProbe.drill(page)));
        assertEquals("the open receipt list grows with the compatible arrival", 2,
            onEdt(() -> LedgerPageProbe.receiptCardTransactionIds(page)).size());
        Br.Group group = updated.detail.group;
        assertEquals(2, group.receiptCount);
        assertEquals(-1_640L, group.value);
        assertTrue(updated.detail.group.containsTransaction(first.getId()));

        // The owner fence clears every presentation transient.
        onEdt(() ->
        {
            page.agf();
            return null;
        });
        assertEquals("OVERVIEW", onEdt(() -> LedgerPageProbe.drill(page)));
        assertTrue(onEdt(() -> LedgerPageProbe.receiptCardTransactionIds(page)).isEmpty());
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private static Am session()
    {
        Am engine = PresentationLifecycleTest.engine();
        engine.ajl("Vorkath", Cx.GENERAL, T0);
        return engine;
    }

    private static Ao capture(Am engine, long now, Ao.Entry entry)
    {
        return Ao.capture(engine, now, entry);
    }

    private static Ao.Entry entry(Ao.Bs view, String highlight)
    {
        return new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null, view, "",
            highlight, null, null, null);
    }

    private static Br.Group groupNamed(
        List<Br.Group> groups, String name)
    {
        for (Br.Group group : groups)
        {
            if (name.equals(group.primaryName))
            {
                return group;
            }
        }
        return null;
    }

    private static Ac iceBurst(long at)
    {        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Ab(CHAOS, "Chaos rune", -4L, 106, -424L, Av.GRAND_EXCHANGE),
                new Ab(DEATH, "Death rune", -2L, 188, -376L, Av.GRAND_EXCHANGE),
                new Ab(WATER, "Water rune", -4L, 5, -20L, Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(Au.CAST);
        transaction.ahu(Bb.of("Ice Burst"));
        return transaction;
    }

    private static Ac unpricedDeathBurst(long at)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Ab(CHAOS, "Chaos rune", -4L, 106, -424L, Av.GRAND_EXCHANGE),
                new Ab(DEATH, "Death rune", -2L, 0, 0L, Av.UNPRICED),
                new Ab(WATER, "Water rune", -4L, 5, -20L, Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(Au.CAST);
        transaction.ahu(Bb.of("Cast"));
        return transaction;
    }

    private static Ac measuredLead(long at, String note)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, note, "Vorkath", true, Arrays.asList(
                new Ab(12934, "Zulrah's scales", -1L, 110, -110L, Av.GRAND_EXCHANGE)),
            Bd.CONFIRMED, "Measured Check difference.", null);
        transaction.setActionKind(Au.FIRE);
        return transaction;
    }

    @Test
    public void estimatedChargeCastsLandInTheChargesChip()
    {
        Am engine = session();
        engine.getActiveSession().kf(estimatedCharge(T0 + 1_000L), 2_000);
        Ao charges = capture(engine, T0 + 2_000L,
            entry(Ao.Bs.CHARGES, null));
        assertEquals(1, charges.costCounts[Ao.Bs.CHARGES.ordinal()]);
        assertEquals(1, charges.costs.groups.size());
        assertEquals("Trident of the Seas", charges.costs.groups.get(0).primaryName);
        assertTrue("Supplies keeps only non-charge costs", capture(engine, T0 + 2_000L,
            entry(Ao.Bs.SUPPLIES, null)).costs.groups.isEmpty());
    }

    /** Owner 2026-10-01 (F11): a Charges receipt names its confidence and its captured price. */
    @Test
    public void chargeReceiptsDiscloseTheirConfidenceAndPriceBasis() throws Exception
    {
        Am engine = session();
        Ac estimate = singleRuneEstimate(T0 + 1_000L);
        engine.getActiveSession().kf(estimate, 2_000);
        Ac measured = measuredLead(T0 + 2_000L, "Measured charge spend \u00b7 Toxic blowpipe");
        engine.getActiveSession().kf(measured, 2_000);

        LedgerPage estimatePage = receiptPage(engine, estimate.getId());
        List<String> estimateLabels = onEdt(() -> labels(estimatePage.body()));
        assertTrue("the estimate discloses its confidence: " + estimateLabels,
            estimateLabels.stream().anyMatch(label -> label.contains(
                "Estimated from the local cast or hit graphic; a measured Check reconciles it.")));
        assertTrue("the captured unit price and its source are disclosed: " + estimateLabels,
            estimateLabels.contains("100 gp each \u00b7 RuneLite market"));
        assertTrue("the price row is labelled", estimateLabels.contains("Price"));

        LedgerPage measuredPage = receiptPage(engine, measured.getId());
        List<String> measuredLabels = onEdt(() -> labels(measuredPage.body()));
        assertTrue("the measured spend says where it came from: " + measuredLabels,
            measuredLabels.stream().anyMatch(label -> label.contains(
                "Measured from the exact charge Check difference.")));
        assertTrue("its captured unit price and source are disclosed",
            measuredLabels.contains("110 gp each \u00b7 RuneLite market"));
    }

    private static Ac singleRuneEstimate(long at)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "Estimated charge use \u00b7 Trident of the Seas", "Vorkath", true,
            Arrays.asList(
                new Ab(DEATH, "Death rune", -1L, 100, -100L, Av.GRAND_EXCHANGE)),
            Bd.LIKELY, "Estimated", null);
        transaction.setActionKind(Au.CAST);
        return transaction;
    }

    private static LedgerPage receiptPage(Am engine, String transactionId) throws Exception
    {
        Ao data = capture(engine, T0 + 10_000L, entry(Ao.Bs.CHARGES, transactionId));
        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(new NoopActions(), id -> null);
            created.apply(data);
            return created;
        });
        assertEquals("the receipt opens under the list", "EXACT", onEdt(() -> LedgerPageProbe.drill(page)));
        return page;
    }

    private static Ac estimatedCharge(long at)
    {
        Ac transaction = new Ac(at, null, Ai.CONSUMPTION,
            Aj.GENERIC, "Estimated charge use \u00b7 Trident of the Seas", "Vorkath", true,
            Arrays.asList(
                new Ab(DEATH, "Death rune", -1L, 100, -100L, Av.GRAND_EXCHANGE),
                new Ab(CHAOS, "Chaos rune", -1L, 50, -50L, Av.GRAND_EXCHANGE)),
            Bd.LIKELY, "Estimated", null);
        transaction.setActionKind(Au.CAST);
        return transaction;
    }

    private static int uuidLabels(List<String> labels)
    {
        int count = 0;
        for (String label : labels)
        {
            if (label.matches(".*[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}.*"))
            {
                count++;
            }
        }
        return count;
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

    private static final class NoopActions implements LedgerPage.Actions
    {
        @Override public void openScopeMenu(javax.swing.JComponent anchor) { }
        @Override public void costViewChanged(Ao.Bs view) { }
        @Override public void searchChanged(String text) { }
        @Override public Ao.Ef preview(String id,
            Ah correction) { return null; }
        @Override public LedgerPage.Ea correct(String id,
            Ah correction, long previewRevision)
        { return LedgerPage.Ea.REFUSED; }
        @Override public void split(String id) { }
        @Override public void undoCorrection() { }
        @Override public void decideAll(Cl decision) { }
        @Override public void refresh() { }
    }
}
