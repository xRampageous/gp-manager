package com.gpmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NormalizedCostOverflowTest {
    private static final long UNIT = 1_000_000_000_000_000_000L;

    private static Transaction sips(boolean reverse, int count, long unit) {
        String[] names = {"Prayer potion", "Super defence", "Saradomin brew", "Super restore"};
        var flows = new ArrayList<Flow>();
        for (int index = 0; index < count; index++) {
            flows.add(new Flow(10_000 + index * 2, names[index] + "(4)", -1,
                    4 * unit, -4 * unit, PriceSource.MANUAL_OVERRIDE));
            flows.add(new Flow(10_001 + index * 2, names[index] + "(3)", 1,
                    3 * unit, 3 * unit, PriceSource.MANUAL_OVERRIDE));
        }
        if (reverse) Collections.reverse(flows);
        var row = Tx.of(1000, TransactionType.CONSUMPTION, Context.GENERIC, "", true, flows);
        row.setAction(ActionKind.DRINK);
        return row;
    }

    private static void agree(Transaction row, long income, long cost) {
        var total = Accounting.transaction(row);
        assertEquals(income, total.income);
        assertEquals(cost, total.cost);
        var detail = new ValueTotal();
        for (var flow : row.flows()) {
            var money = Accounting.flow(row, flow);
            detail.add(money.income, money.cost);
        }
        assertEquals(total.net(), detail.value());
        var projected = new ValueTotal();
        Contribution.project(row).forEach(part -> projected.add(part.value));
        assertEquals(total.net(), projected.value());
        assertEquals(cost, Accounting.costSplit(row).supplies);
    }

    @Test public void cancelRemaindersBeforeGrossBucketsSaturateInEitherOrder() {
        for (boolean reverse : new boolean[]{false, true}) agree(sips(reverse, 4, UNIT), 0, 4 * UNIT);
    }

    @Test public void aLargeUnrelatedGainStaysSeparateFromSpentDoses() {
        for (boolean reverse : new boolean[]{false, true}) {
            var row = sips(reverse, 4, UNIT);
            var flows = new ArrayList<>(row.flows());
            flows.add(new Flow(995, "Coins", 5 * UNIT, 1, 5 * UNIT, PriceSource.FACE_VALUE));
            row.replaceFlows(flows);
            agree(row, 5 * UNIT, 4 * UNIT);
        }
    }

    @Test public void normalizedCostsStillSaturateWhenTheActualDosesExceedTheRange() {
        agree(sips(false, 4, 2 * UNIT), 0, 8 * UNIT);
        var row = sips(false, 4, 2 * UNIT);
        var flows = new ArrayList<>(row.flows());
        flows.add(new Flow(554, "Fire rune", -1, 2 * UNIT, -2 * UNIT, PriceSource.MANUAL_OVERRIDE));
        row.replaceFlows(flows);
        agree(row, 0, Long.MAX_VALUE);
    }

    @Test public void ordinarySipsAndExplicitCorrectionsRetainTheirMeaning() {
        agree(sips(false, 4, 100), 0, 400);
        for (var correction : new Correction[]{Correction.COST, Correction.REVENUE}) {
            var row = sips(false, 4, UNIT);
            row.applyCorrection(correction, "Owner decision");
            assertTrue(Accounting.consumePairs(row).isEmpty());
            var money = Accounting.transaction(row);
            assertEquals(correction == Correction.REVENUE ? Long.MAX_VALUE : 0, money.income);
            assertEquals(correction == Correction.COST ? Long.MAX_VALUE : 0, money.cost);
        }
    }

    @Test public void transfersAndDifferentQuoteSourcesDoNotAcquireConsumeNormalization() {
        var transfer = sips(false, 4, UNIT);
        transfer.applyCorrection(Correction.TRANSFER, "Move");
        assertEquals(0, Accounting.transaction(transfer).net());
        var row = sips(false, 1, UNIT);
        row.flows().get(1).priceSource = PriceSource.GRAND_EXCHANGE;
        row.replaceFlows(row.flows());
        assertTrue(Accounting.consumePairs(row).isEmpty());
        assertEquals(3 * UNIT, Accounting.transaction(row).income);
        assertEquals(4 * UNIT, Accounting.transaction(row).cost);
    }
}
