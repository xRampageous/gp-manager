package com.gpmanager;

import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** 1.7.0-only load repair of pre-1.7 bond conversion Reviews; delete with the migration in the next release. */
public class BondMigrationTest {
    static final long T0=ProductionRecipeTest.T0, BOND=11_122_724;
    java.util.function.LongSupplier previous;
    @Before public void clock() { previous = SafeMath.clock; SafeMath.clock = () -> T0; }
    @After public void restore() { SafeMath.clock = previous; }

    static Flow newBond(long qty) {
        return new Flow(BondConversionTest.NEW, "Old school bond", qty, BOND, qty * BOND, PriceSource.GRAND_EXCHANGE);
    }
    static Flow oldBond(long qty) {
        return new Flow(BondConversionTest.OLD, "Old school bond (untradeable)", qty, 0, 0, PriceSource.NO_VALUE);
    }
    /** How a pre-1.7 build booked a conversion: a counted Review gain of the tradeable bond. */
    static Transaction review(Flow... flows) {
        return new Transaction(T0 + 100, TransactionType.UNCERTAIN, Context.GENERIC, "", "", true, List.of(flows),
                Confidence.UNCERTAIN, "Mixed gains and costs had no confirmed context; counted by configuration.", null);
    }

    /** How a pre-1.7 build recorded the untradeable bond's GE collect: an uncounted Review. */
    static Transaction collect(Flow... flows) {
        return new Transaction(T0 + 50, TransactionType.UNCERTAIN, Context.MARKET, "Grand Exchange collect", "", false, List.of(flows),
                Confidence.UNCERTAIN, "Grand Exchange movement not claimable by a custody lifecycle; recorded for review without booking profit, cost or basis.", null);
    }

    @Test public void anOldConversionReviewIsRepairedOnLoadOnceAndUndoRestoresIt() {
        var engine = ProductionRecipeTest.engine(); var row = review(newBond(1), oldBond(-1));
        engine.session().addTransaction(row, 2000, false);
        long before = engine.metrics(T0 + 1000).net;
        engine.recheck();
        assertEquals("the Ledger's money-only removal", Correction.IGNORE, row.correction());
        assertEquals("its physical history stays as booked", Correction.AUTO, row.ownershipCorrection);
        assertFalse(ReviewRules.needsReview(row));
        assertEquals("the fake conversion gain leaves Net", before - BOND, engine.metrics(T0 + 1000).net);
        assertEquals(1, engine.session().correctionHistory.size());
        engine.recheck();
        assertEquals("a second load changes nothing", 1, engine.session().correctionHistory.size());
        assertTrue(engine.undoCorrection());
        assertEquals(Correction.AUTO, row.correction());
        assertEquals(before, engine.metrics(T0 + 1000).net);
    }

    @Test public void otherReviewsAndInexactBondShapesStayForTheOwner() {
        var engine = ProductionRecipeTest.engine();
        var onlyNew = review(newBond(1));
        var mismatched = review(newBond(2), oldBond(-1));
        var mixed = review(newBond(1), oldBond(-1), new Flow(385, "Shark", 1, 900, 900, PriceSource.GRAND_EXCHANGE));
        var plain = review(new Flow(385, "Shark", 1, 900, 900, PriceSource.GRAND_EXCHANGE));
        var uncountedShark = collect(new Flow(385, "Shark", 1, 900, 900, PriceSource.GRAND_EXCHANGE));
        var uncountedNew = collect(newBond(1)); var uncountedOldLoss = collect(oldBond(-1));
        var uncountedWithShark = collect(oldBond(1), new Flow(385, "Shark", 1, 900, 900, PriceSource.GRAND_EXCHANGE));
        var stays = List.of(onlyNew, mismatched, mixed, plain, uncountedShark, uncountedNew, uncountedOldLoss, uncountedWithShark);
        for (var row : stays) engine.session().addTransaction(row, 2000, false);
        long before = engine.metrics(T0 + 1000).net;
        engine.recheck();
        for (var row : stays) {
            assertEquals(Correction.AUTO, row.correction());
            assertTrue(ReviewRules.needsReview(row));
        }
        assertEquals(before, engine.metrics(T0 + 1000).net);
        assertTrue(engine.session().correctionHistory.isEmpty());
    }

    /** The owner's 4 Nov chain: a later gift used the tradeable bond, so Repair bond itself is refused. */
    @Test public void aConversionWhoseBondWasLaterGivenAwayIsStillRemovedFromNet() {
        var engine = ProductionRecipeTest.engine(); var row = review(newBond(1), oldBond(-1));
        engine.session().addTransaction(row, 2000, false);
        engine.session().addTransaction(new Transaction(T0 + 200, TransactionType.CONSUMPTION, Context.GENERIC, "", "", true,
                List.of(newBond(-1)), Confidence.LIKELY, "Stable inventory/equipment value decreased without a stronger context.", null), 2000, false);
        assertFalse("the Review decision cannot undo the depletion", engine.session().canCorrect(List.of(row), Correction.BOND, T0 + 1000));
        long before = engine.metrics(T0 + 1000).net;
        engine.recheck();
        assertEquals(Correction.IGNORE, row.correction());
        assertEquals("only the fake conversion gain leaves; the gift's loss stays", before - BOND, engine.metrics(T0 + 1000).net);
    }

    /** Owner profile 2026-11-06: the untradeable bond's old GE collect leaves Review; Net does not move. */
    @Test public void anOldUncountedBondCollectLeavesReviewWithoutChangingNet() {
        var engine = ProductionRecipeTest.engine(); var row = collect(oldBond(1));
        engine.session().addTransaction(row, 2000, false);
        long before = engine.metrics(T0 + 1000).net;
        engine.recheck();
        assertEquals(Correction.IGNORE, row.correction());
        assertFalse(ReviewRules.needsReview(row));
        assertEquals(before, engine.metrics(T0 + 1000).net);
        assertTrue(engine.undoCorrection());
        assertTrue(ReviewRules.needsReview(row));
    }
}
