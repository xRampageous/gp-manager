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
        Engine engine = session();
        Transaction first = iceBurst(T0 + 1_000L);
        engine.getActiveSession().addTransaction(first, 2_000);
        engine.getActiveSession().addTransaction(iceBurst(T0 + 2_000L), 2_000);
        engine.getActiveSession().addTransaction(iceBurst(T0 + 3_000L), 2_000);
        // A Shark group whose transaction also carries an unrelated coin gain.
        Transaction mixed = new Transaction(T0 + 4_000L, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(SHARK, "Shark", -1L, 950, -950L, PriceSource.GRAND_EXCHANGE),
                new Flow(995, "Coins", 500L, 1, 500L, PriceSource.FACE_VALUE)),
            ClassificationConfidence.CONFIRMED, "Loot and food", null);
        mixed.setActionKind(ActionKind.EAT);
        engine.getActiveSession().addTransaction(mixed, 2_000);

        LedgerData data = capture(engine, T0 + 10_000L,
            entry(LedgerData.CostView.SUPPLIES, first.getId()));
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
            cardTexts.stream().allMatch(text -> text.contains(Fmt.exactSigned(-820) + " gp")));
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
        LedgerData sharkData = capture(engine, T0 + 10_000L,
            entry(LedgerData.CostView.SUPPLIES, mixed.getId()));
        SemanticFinancialProjection.Group shark = groupNamed(sharkData.costs.groups, "Shark");
        assertNotNull(shark);
        List<LedgerData.Card> sharkCards = LedgerData.cards(shark);
        assertEquals(1, sharkCards.size());
        assertEquals("a Shark row shows the Shark contribution, not the transaction Net",
            -950L, sharkCards.get(0).value);
    }

    @Test
    public void anUnpricedChildMakesItsReceiptRowIncomplete()
    {
        Engine engine = session();
        engine.getActiveSession().addTransaction(unpricedDeathBurst(T0 + 1_000L), 2_000);
        engine.getActiveSession().addTransaction(unpricedDeathBurst(T0 + 2_000L), 2_000);
        LedgerData data = capture(engine, T0 + 10_000L,
            entry(LedgerData.CostView.SUPPLIES, null));
        // Its runes are Ice Burst's exact cost, so the row reads as the spell (owner 2026-09-28).
        SemanticFinancialProjection.Group group = groupNamed(data.costs.groups, "Ice Burst");
        assertNotNull(group);
        assertTrue(group.incomplete());
        for (LedgerData.Card card : LedgerData.cards(group))
        {
            assertTrue("an unpriced child makes the row incomplete", card.incomplete);
            assertEquals("the known subtotal counts only the priced children", -444L, card.knownSubtotal);
        }
    }

    @Test
    public void measuredContextsNeverShareAGroup()
    {
        Engine engine = session();
        engine.getActiveSession().addTransaction(measuredLead(T0 + 1_000L, "Measured charge spend · Toxic blowpipe"), 2_000);
        engine.getActiveSession().addTransaction(measuredLead(T0 + 2_000L, "Measured charge spend · Trident of the swamp"), 2_000);
        LedgerData data = capture(engine, T0 + 10_000L,
            entry(LedgerData.CostView.CHARGES, null));
        // Owner 2026-09-28: measured charge use has its own Charges chip, apart from Supplies.
        assertEquals(2, data.costCounts[LedgerData.CostView.CHARGES.ordinal()]);
        assertEquals(2, data.costs.groups.size());
        assertTrue("Supplies leaves charge use to Charges", capture(engine, T0 + 10_000L,
            entry(LedgerData.CostView.SUPPLIES, null)).costs.groups.stream().allMatch(group -> group.usedBy.isEmpty()));

        SemanticFinancialProjection.Group blowpipe = null;
        for (SemanticFinancialProjection.Group group : data.costs.groups)
        {
            if ("Zulrah's scales".equals(group.primaryName) && "Toxic blowpipe".equals(group.usedBy))
            {
                blowpipe = group;
            }
        }
        assertNotNull(blowpipe);
        assertEquals("a different measured context never joins this group", 1, LedgerData.cards(blowpipe).size());
        for (SemanticFinancialProjection.Receipt receipt : blowpipe.receipts)
        {
            assertEquals("Toxic blowpipe", receipt.usedBy);
        }
    }

    @Test
    public void ownerFenceAndLiveAnchoringClearOrPreserveTheRightState() throws Exception
    {
        Engine engine = session();
        Transaction first = iceBurst(T0 + 1_000L);
        engine.getActiveSession().addTransaction(first, 2_000);
        LedgerData data = capture(engine, T0 + 2_000L,
            entry(LedgerData.CostView.SUPPLIES, first.getId()));
        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(new NoopActions(), id -> null);
            created.apply(data);
            return created;
        });
        assertEquals("EXACT", onEdt(() -> LedgerPageProbe.drill(page)));

        // A compatible new receipt keeps the open detail anchored and updates its totals.
        engine.getActiveSession().addTransaction(iceBurst(T0 + 3_000L), 2_000);
        LedgerData updated = capture(engine, T0 + 4_000L,
            entry(LedgerData.CostView.SUPPLIES, first.getId()));
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
        SemanticFinancialProjection.Group group = updated.detail.group;
        assertEquals(2, group.receiptCount);
        assertEquals(-1_640L, group.value);
        assertTrue(updated.detail.group.containsTransaction(first.getId()));

        // The owner fence clears every presentation transient.
        onEdt(() ->
        {
            page.resetOwnerScope();
            return null;
        });
        assertEquals("OVERVIEW", onEdt(() -> LedgerPageProbe.drill(page)));
        assertTrue(onEdt(() -> LedgerPageProbe.receiptCardTransactionIds(page)).isEmpty());
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

    private static LedgerData.Entry entry(LedgerData.CostView view, String highlight)
    {
        return new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null, view, "",
            highlight, null, null, null);
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

    private static Transaction iceBurst(long at)
    {        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(CHAOS, "Chaos rune", -4L, 106, -424L, PriceSource.GRAND_EXCHANGE),
                new Flow(DEATH, "Death rune", -2L, 188, -376L, PriceSource.GRAND_EXCHANGE),
                new Flow(WATER, "Water rune", -4L, 5, -20L, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(ActionKind.CAST);
        transaction.setObservedActionLabel(ActionLabel.of("Ice Burst"));
        return transaction;
    }

    private static Transaction unpricedDeathBurst(long at)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(CHAOS, "Chaos rune", -4L, 106, -424L, PriceSource.GRAND_EXCHANGE),
                new Flow(DEATH, "Death rune", -2L, 0, 0L, PriceSource.UNPRICED),
                new Flow(WATER, "Water rune", -4L, 5, -20L, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Exact fixture", null);
        transaction.setActionKind(ActionKind.CAST);
        transaction.setObservedActionLabel(ActionLabel.of("Cast"));
        return transaction;
    }

    private static Transaction measuredLead(long at, String note)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, note, "Vorkath", true, Arrays.asList(
                new Flow(12934, "Zulrah's scales", -1L, 110, -110L, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.CONFIRMED, "Measured Check difference.", null);
        transaction.setActionKind(ActionKind.FIRE);
        return transaction;
    }

    @Test
    public void estimatedChargeCastsLandInTheChargesChip()
    {
        Engine engine = session();
        engine.getActiveSession().addTransaction(estimatedCharge(T0 + 1_000L), 2_000);
        LedgerData charges = capture(engine, T0 + 2_000L,
            entry(LedgerData.CostView.CHARGES, null));
        assertEquals(1, charges.costCounts[LedgerData.CostView.CHARGES.ordinal()]);
        assertEquals(1, charges.costs.groups.size());
        assertEquals("Trident of the Seas", charges.costs.groups.get(0).primaryName);
        assertTrue("Supplies keeps only non-charge costs", capture(engine, T0 + 2_000L,
            entry(LedgerData.CostView.SUPPLIES, null)).costs.groups.isEmpty());
    }

    /** Owner 2026-10-01 (F11): a Charges receipt names its confidence and its captured price. */
    @Test
    public void chargeReceiptsDiscloseTheirConfidenceAndPriceBasis() throws Exception
    {
        Engine engine = session();
        Transaction estimate = singleRuneEstimate(T0 + 1_000L);
        engine.getActiveSession().addTransaction(estimate, 2_000);
        Transaction measured = measuredLead(T0 + 2_000L, "Measured charge spend \u00b7 Toxic blowpipe");
        engine.getActiveSession().addTransaction(measured, 2_000);

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

    private static Transaction singleRuneEstimate(long at)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "Estimated charge use \u00b7 Trident of the Seas", "Vorkath", true,
            Arrays.asList(
                new Flow(DEATH, "Death rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.LIKELY, "Estimated", null);
        transaction.setActionKind(ActionKind.CAST);
        return transaction;
    }

    private static LedgerPage receiptPage(Engine engine, String transactionId) throws Exception
    {
        LedgerData data = capture(engine, T0 + 10_000L, entry(LedgerData.CostView.CHARGES, transactionId));
        LedgerPage page = onEdt(() ->
        {
            LedgerPage created = new LedgerPage(new NoopActions(), id -> null);
            created.apply(data);
            return created;
        });
        assertEquals("the receipt opens under the list", "EXACT", onEdt(() -> LedgerPageProbe.drill(page)));
        return page;
    }

    private static Transaction estimatedCharge(long at)
    {
        Transaction transaction = new Transaction(at, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "Estimated charge use \u00b7 Trident of the Seas", "Vorkath", true,
            Arrays.asList(
                new Flow(DEATH, "Death rune", -1L, 100, -100L, PriceSource.GRAND_EXCHANGE),
                new Flow(CHAOS, "Chaos rune", -1L, 50, -50L, PriceSource.GRAND_EXCHANGE)),
            ClassificationConfidence.LIKELY, "Estimated", null);
        transaction.setActionKind(ActionKind.CAST);
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
