package com.gpmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountingFoldContractTest {
    private static final long NOW = 1_700_000_000_000L;

    private static Transaction row(long at, long value) {
        return Tx.of(at, TransactionType.ADJUSTMENT, Context.GENERIC, "", true,
                List.of(new Flow(995, "Coins", value, 1, value, PriceSource.FACE_VALUE)));
    }

    @Test public void scalarHistoryAndAllNullFlowCoverageKeepTheirDistinctMeaning() {
        var scalar = row(NOW, 1);
        scalar.flows = List.of();
        scalar.revenue = 77;
        scalar.costs = 30;
        assertEquals(47, Accounting.transaction(scalar).net());
        scalar.applyCorrection(Correction.COST, "Owner decision");
        assertEquals(107, Accounting.transaction(scalar).cost);
        var absent = row(NOW, 1);
        absent.flows = new ArrayList<>(Arrays.asList(null, null));
        assertFalse(Accounting.transaction(absent).included);
    }

    @Test public void zeroMixedAndExtremeCorrectionsRetainTheirOriginalQuotedFacts() {
        var row = Tx.of(NOW, TransactionType.ADJUSTMENT, Context.GENERIC, "", true, List.of(
                new Flow(995, "Coins", 40, 1, 40, PriceSource.FACE_VALUE),
                new Flow(526, "Bones", -1, 10, -10, PriceSource.MANUAL_OVERRIDE),
                new Flow(1203, "Iron dagger", 1, 0, 0, PriceSource.NO_VALUE)));
        assertEquals(30, Accounting.transaction(row).net());
        row.applyCorrection(Correction.REVENUE, "Gain");
        assertEquals(50, Accounting.transaction(row).income);
        assertEquals(0, Accounting.transaction(row).cost);
        row.applyCorrection(Correction.COST, "Cost");
        assertEquals(50, Accounting.transaction(row).cost);
        assertEquals(0, Accounting.transaction(row).income);
        assertEquals(-10, row.flows().get(1).value);
        assertEquals(PriceSource.NO_VALUE, row.flows().get(2).source());
        row.applyCorrection(Correction.IGNORE, "Exclude");
        assertFalse(Accounting.transaction(row).included);
        var extreme = Tx.of(NOW, TransactionType.ADJUSTMENT, Context.GENERIC, "", true, List.of(
                new Flow(526, "Bones", -1, Long.MAX_VALUE, Long.MIN_VALUE, PriceSource.MANUAL_OVERRIDE),
                new Flow(995, "Coins", 1, 1, 1, PriceSource.FACE_VALUE)));
        extreme.applyCorrection(Correction.REVENUE, "Gain");
        assertEquals(Long.MAX_VALUE, Accounting.transaction(extreme).income);
    }

    @Test public void retentionKeepsItsBoundaryPredicateOrderAndProtectedReceipt() {
        var engine = ReviewFixesTest.engine();
        engine.startCustom("Retention contract", NOW);
        var first = row(NOW + 1, 10);
        var protectedRow = row(NOW + 2, 20);
        var boundary = row(NOW + 3, 30);
        var future = row(NOW + 4, 40);
        var session = engine.session();
        for (var transaction : List.of(first, protectedRow, boundary, future))
            session.addTransaction(transaction, 2000, false);
        session.transactions.add(0, null);
        var visited = new ArrayList<String>();
        int folded = session.compactBefore(NOW + 3, transaction -> {
            visited.add(transaction.id());
            return transaction == protectedRow;
        });
        assertEquals(1, folded);
        assertEquals(List.of(first.id(), protectedRow.id()), visited);
        assertEquals(10, session.retainedRevenue);
        assertEquals(1, session.compactedTransactionCount);
        assertTrue(session.transactions.contains(protectedRow));
        assertTrue(session.transactions.contains(boundary));
        assertTrue(session.transactions.contains(future));
    }
}
