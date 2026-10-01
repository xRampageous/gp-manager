package com.gpmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Release pass 2026-09-28: the dose pairing (a sip books one dose, never a gain plus a whole potion)
 * must agree everywhere money is read. For generated sips, bites, decants, mixed loot and corrected
 * rows: the per-flow amounts add up to the transaction amounts, Net stays the raw flow sum, the cost
 * split covers the costs, and the Ledger contributions add up to the same Net.
 */
public class DosePairingInvariantTest
{
    private static final String[] POTIONS = {"Prayer potion", "Super defence", "Saradomin brew"};
    private static final ActionKind[] ACTIONS = {ActionKind.DRINK, ActionKind.EAT, ActionKind.SUPPLIES, null};
    private static final Correction[] CORRECTIONS = {Correction.AUTO, Correction.AUTO,
        Correction.AUTO, Correction.COST, Correction.REVENUE, Correction.IGNORE};

    @Test
    public void everyReaderAgreesOnGeneratedConsumption()
    {
        Random random = new Random(20260928L);
        int paired = 0;
        for (int i = 0; i < 2_000; i++)
        {
            Transaction transaction = generated(random, i);
            AccountingProjection.TransactionAmounts total = AccountingProjection.transaction(transaction);
            if (!total.included)
            {
                continue;
            }
            long revenue = 0L;
            long costs = 0L;
            long raw = 0L;
            for (Flow flow : transaction.getFlows())
            {
                AccountingProjection.TransactionAmounts amounts = AccountingProjection.flow(transaction, flow);
                revenue += amounts.revenue;
                costs += amounts.costs;
                raw += flow.valueDelta;
            }
            String where = "case " + i + " " + transaction.getFlows();
            assertEquals(where, total.revenue, revenue);
            assertEquals(where, total.costs, costs);
            if (transaction.getCorrection() == Correction.AUTO)
            {
                assertEquals("Net stays the raw flow sum: " + where, raw, total.getNet());
            }
            AccountingProjection.CostSplit split = AccountingProjection.costSplit(transaction);
            if (split.available)
            {
                assertEquals("the split covers the costs: " + where, total.costs, split.supplies + split.other);
            }
            long ledger = 0L;
            for (Contribution contribution : Contribution.project(transaction))
            {
                ledger += contribution.effectiveValue;
            }
            assertEquals("the Ledger adds up to Net: " + where, total.getNet(), ledger);
            if (!AccountingProjection.consumePairs(transaction).isEmpty())
            {
                paired++;
            }
        }
        assertTrue("the generator exercises dose pairs (" + paired + ")", paired > 200);
    }

    private static Transaction generated(Random random, int i)
    {
        List<Flow> flows = new ArrayList<>();
        String potion = POTIONS[random.nextInt(POTIONS.length)];
        int dose = 1 + random.nextInt(4);
        int unit = 500 + random.nextInt(3_000);
        long sips = 1 + random.nextInt(3);
        switch (random.nextInt(5))
        {
            case 0:
                // A sip: (n) -> (n-1), or (1) -> vial or nothing.
                flows.add(new Flow(1_000 + dose, potion + "(" + dose + ")", -sips, unit * dose, -sips * unit * dose));
                if (dose > 1)
                {
                    flows.add(new Flow(1_000 + dose - 1, potion + "(" + (dose - 1) + ")", sips, unit * (dose - 1),
                        sips * unit * (dose - 1)));
                }
                else if (random.nextBoolean())
                {
                    flows.add(new Flow(229, "Vial", sips, 3, sips * 3));
                }
                break;
            case 1:
                // A bite of a pie.
                flows.add(new Flow(2327, "Meat pie", -1L, 40, -40L));
                flows.add(new Flow(2331, "Half a meat pie", 1L, 18, 18L));
                break;
            case 2:
                // A decant: two (2) become one (4); doses are conserved.
                flows.add(new Flow(1_002, potion + "(2)", -2L, unit * 2, -4L * unit));
                flows.add(new Flow(1_004, potion + "(4)", 1L, unit * 4, 4L * unit));
                flows.add(new Flow(229, "Vial", 1L, 3, 3L));
                break;
            case 3:
                // A sip settled together with a pickup.
                flows.add(new Flow(1_000 + dose, potion + "(" + dose + ")", -1L, unit * dose, -(long) unit * dose));
                if (dose > 1)
                {
                    flows.add(new Flow(1_000 + dose - 1, potion + "(" + (dose - 1) + ")", 1L, unit * (dose - 1),
                        (long) unit * (dose - 1)));
                }
                flows.add(new Flow(536, "Dragon bones", 1L + random.nextInt(3), 2_000, 2_000L * (1 + random.nextInt(3))));
                break;
            default:
                // A price that fell between the sip's two legs (the remainder worth more than the loss).
                flows.add(new Flow(1_002, potion + "(2)", -1L, unit, -unit));
                flows.add(new Flow(1_001, potion + "(1)", 1L, unit * 2, unit * 2L));
                break;
        }
        TransactionType type = random.nextInt(4) == 0 ? TransactionType.LOOT : TransactionType.CONSUMPTION;
        Transaction transaction = new Transaction(1_000L + i, null, type, Context.GENERIC, "",
            "Vorkath", true, flows, ClassificationConfidence.LIKELY, "fixture", null);
        transaction.setActionKind(ACTIONS[random.nextInt(ACTIONS.length)]);
        Correction correction = CORRECTIONS[random.nextInt(CORRECTIONS.length)];
        if (correction != Correction.AUTO)
        {
            transaction.applyCorrection(correction, 2_000L + i);
        }
        return transaction;
    }
}
