package com.gpmanager;

import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Older bond display uses captured receipts, without reclassifying their money. */
public class LegacyBondDisplayTest {
    java.util.function.LongSupplier previous;
    @Before public void clock() { previous = SafeMath.clock; SafeMath.clock = () -> ProductionRecipeTest.T0; }
    @After public void restore() { SafeMath.clock = previous; }

    static Transaction pair(TransactionType type) {
        return new Transaction(ProductionRecipeTest.T0 + 100, type, Context.GENERIC, "", "", false,
                List.of(ProductionRecipeTest.leg(BondConversionTest.OLD, "Old school bond (untradeable)", -1),
                        ProductionRecipeTest.leg(BondConversionTest.NEW, "Old school bond", 1)),
                Confidence.UNCERTAIN, "Old conversion", null);
    }

    @Test public void aProvenConversionReviewUsesOneItemRowWithoutChangingItsBooking() {
        var engine = ProductionRecipeTest.engine();
        var receipt = pair(TransactionType.UNCERTAIN);
        engine.session().addTransaction(receipt, 2000, false);
        var before = JsonCodec.gson().toJson(engine.savedState());
        long revision = engine.revision();
        var live = LiveSnapshot.capture(engine, ProductionRecipeTest.T0 + 1000, LiveContext.NONE);
        assertEquals(1, live.recent.size());
        var row = live.recent.get(0);
        assertEquals("Old school bond", row.name);
        assertEquals(BondConversionTest.NEW, row.item);
        assertTrue(row.ledgerReview);
        assertEquals(0, live.net);
        assertEquals(1, row.receipts.size());
        var detail = LedgerData.capture(engine, ProductionRecipeTest.T0 + 1000, LivePage.entryFor(row));
        assertEquals(2, detail.detail.group.entries.size());
        var review = engine.getReviewRows(ProductionRecipeTest.T0 + 1000).get(0);
        assertTrue(review.decisions.contains(Decision.BOND));
        assertEquals(BondConversionTest.NEW, review.items.get(0).item);
        assertEquals(revision, engine.revision());
        assertEquals(before, JsonCodec.gson().toJson(engine.savedState()));
        assertEquals(Correction.AUTO, receipt.correction());
    }

    @Test public void oldNeutralConversionRowsStayInTheLedgerAndLeaveRecent() {
        var engine = ProductionRecipeTest.engine();
        var receipt = pair(TransactionType.TRANSFER);
        engine.session().addTransaction(receipt, 2000, false);
        var live = LiveSnapshot.capture(engine, ProductionRecipeTest.T0 + 1000, LiveContext.NONE);
        assertTrue(live.recent.isEmpty());
        assertEquals(0, live.net);
        assertEquals(2, Semantics.capture(List.of(receipt), List.of(), Set.of(engine.session().id()), null)
                .groups.stream().mapToInt(group -> group.entries.size()).sum());
        assertEquals(2, receipt.flows().size());
    }

    @Test public void independentBondLegsAreNotGuessedIntoOneConversion() {
        var engine = ProductionRecipeTest.engine();
        for (Flow flow : pair(TransactionType.UNCERTAIN).flows()) {
            engine.session().addTransaction(new Transaction(ProductionRecipeTest.T0 + 100,
                    TransactionType.UNCERTAIN, Context.GENERIC, "", "", false, List.of(flow),
                    Confidence.UNCERTAIN, "", null), 2000, false);
        }
        var live = LiveSnapshot.capture(engine, ProductionRecipeTest.T0 + 1000, LiveContext.NONE);
        assertEquals(2, live.recent.size());
        assertEquals(2, engine.getReviewRows(ProductionRecipeTest.T0 + 1000).size());
        assertEquals(0, live.net);
    }
    @Test public void anOverflowedOldQuantityCannotProveAConversionRepair() {
        var row = new Transaction(ProductionRecipeTest.T0 + 100, TransactionType.UNCERTAIN,
                Context.GENERIC, "", "", false,
                List.of(new Flow(BondConversionTest.OLD, "Old school bond (untradeable)", Long.MIN_VALUE,
                        1, Long.MIN_VALUE, PriceSource.GRAND_EXCHANGE),
                        ProductionRecipeTest.leg(BondConversionTest.NEW, "Old school bond", 1)),
                Confidence.UNCERTAIN, "", null);
        assertNull(BondRepair.plan(row));
    }
    @Test public void aCountedReviewMustKeepItsSeparateFinancialCategories() {
        var receipt = pair(TransactionType.UNCERTAIN); receipt.counted = true;
        var result = Semantics.capture(List.of(receipt), List.of(), Set.of(), null);
        assertEquals("a gain and a cost must never share a financial group", 2, result.groups.size());
        assertTrue(result.groups.stream().anyMatch(group -> group.table == Semantics.Table.GAINS));
        assertTrue(result.groups.stream().anyMatch(group -> group.table == Semantics.Table.COSTS_LOSS));
        assertEquals(7, Accounting.transaction(receipt).income);
        assertEquals(7, Accounting.transaction(receipt).cost);
    }

    @Test public void aPairWithACapturedFeeRetainsEveryPriceThroughRepairReloadAndUndo() {
        var engine = ProductionRecipeTest.engine();
        var flows = new ArrayList<>(pair(TransactionType.UNCERTAIN).flows());
        flows.add(new Flow(995, "Coins", -357123, 1, -357123, PriceSource.FACE_VALUE));
        var receipt = new Transaction(ProductionRecipeTest.T0 + 100, TransactionType.UNCERTAIN,
                Context.GENERIC, "", "", false, flows, Confidence.UNCERTAIN, "", null);
        engine.session().addTransaction(receipt, 2000, false);
        String captured = JsonCodec.gson().toJson(receipt.flows());
        var live = LiveSnapshot.capture(engine, ProductionRecipeTest.T0 + 1000, LiveContext.NONE);
        assertEquals(1, live.recent.size());
        var detail = LedgerData.capture(engine, ProductionRecipeTest.T0 + 1000, LivePage.entryFor(live.recent.get(0)));
        assertEquals(3, detail.detail.group.entries.size());
        assertEquals(0, live.net);
        assertEquals(1, BondSettlementTest.repair(engine, List.of(receipt.id()), ProductionRecipeTest.T0 + 1000));
        assertEquals(-357123, engine.metrics(ProductionRecipeTest.T0 + 1100).net);
        var restored = ProductionRecipeTest.engine();
        restored.restore(SaveCopies.copy(engine.savedState()), ProductionRecipeTest.T0 + 1200);
        assertTrue(restored.undoCorrection());
        var original = restored.session().findTransaction(receipt.id());
        assertEquals(captured, JsonCodec.gson().toJson(original.flows()));
        assertEquals(Correction.AUTO, original.correction());
        assertEquals(0, restored.metrics(ProductionRecipeTest.T0 + 1300).net);
        assertEquals(1, LiveSnapshot.capture(restored, ProductionRecipeTest.T0 + 1300, LiveContext.NONE).recent.size());
    }
    @Test public void removingAndUndoingTheMergedReviewRestoresTheSameReceipt() {
        var engine = ProductionRecipeTest.engine(); var receipt = pair(TransactionType.UNCERTAIN);
        engine.session().addTransaction(receipt, 2000, false);
        var recent = LiveSnapshot.capture(engine, ProductionRecipeTest.T0 + 1000, LiveContext.NONE).recent.get(0);
        String captured = JsonCodec.gson().toJson(receipt.flows());
        assertTrue(engine.correctEntries(recent.receipts, Correction.IGNORE));
        assertTrue(LiveSnapshot.capture(engine, ProductionRecipeTest.T0 + 1100, LiveContext.NONE).recent.isEmpty());
        var restored = ProductionRecipeTest.engine();
        restored.restore(SaveCopies.copy(engine.savedState()), ProductionRecipeTest.T0 + 1200);
        assertTrue(restored.undoCorrection());
        assertEquals(captured, JsonCodec.gson().toJson(restored.session().findTransaction(receipt.id()).flows()));
        assertEquals(1, LiveSnapshot.capture(restored, ProductionRecipeTest.T0 + 1300, LiveContext.NONE).recent.size());
    }

    @Test public void aGenuineKnownZeroBondGainIsStillVisible() {
        var engine = ProductionRecipeTest.engine();
        engine.session().addTransaction(new Transaction(ProductionRecipeTest.T0 + 100, TransactionType.GAIN,
                Context.GENERIC, "", "", true, List.of(new Flow(BondConversionTest.NEW, "Old school bond",
                        1, 0, 0, PriceSource.NO_VALUE)), Confidence.CONFIRMED, "", null), 2000, false);
        assertEquals(1, LiveSnapshot.capture(engine, ProductionRecipeTest.T0 + 1000, LiveContext.NONE).recent.size());
    }

    @Test public void theLegacyPairKeepsTheUsualSingleLineAtSupportedWidths() throws Exception {
        SwingTestSupport.onEdt(() -> {
            var engine = ProductionRecipeTest.engine();
            engine.session().addTransaction(pair(TransactionType.UNCERTAIN), 2000, false);
            var page = new LivePage(ReviewFixesTest.proxy(LivePage.Actions.class, Map.of()),
                    id -> new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB));
            page.apply(LiveSnapshot.capture(engine, ProductionRecipeTest.T0 + 1000, LiveContext.NONE));
            assertEquals(1, page.recent.rows.size());
            assertEquals("Old school bond", page.recent.rows.get(0).name);
            assertNotNull(page.recent.rows.get(0).icon);
            for (int width : new int[] {213, 225, 300}) {
                page.recent.setSize(width - 12, page.recent.getPreferredSize().height);
                ProductionRowLayoutTest.layout(page.recent);
                for (var row : page.recent.body.getComponents()) {
                    assertEquals(page.recent.rowHeight(), row.getHeight());
                    if (row instanceof java.awt.Container)
                        for (var child : ((java.awt.Container) row).getComponents()) {
                            assertTrue(child.getX() >= 0);
                            assertTrue(child.getX() + child.getWidth() <= row.getWidth());
                        }
                }
            }
            return null;
        });
    }
}
