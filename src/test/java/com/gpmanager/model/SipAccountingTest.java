package com.gpmanager;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Owner report 2026-09-28: a potion is drunk a dose at a time, so only the dose is a cost. */
public class SipAccountingTest
{
    private static Transaction sips(long quantity, Correction correction)
    {
        Transaction sips = new Transaction(1_000L, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "", "Vorkath", true, Arrays.asList(
                new Flow(2434, "Prayer potion(4)", -quantity, 9_800, -quantity * 9_800L),
                new Flow(139, "Prayer potion(3)", quantity, 7_350, quantity * 7_350L)),
            ClassificationConfidence.LIKELY, "fixture", null);
        sips.setActionKind(ActionKind.DRINK);
        if (correction != null)
        {
            sips.applyCorrection(correction, 1_500L);
        }
        return sips;
    }

    @Test
    public void twelveSipsAreTwelveDosesNotTwelvePotionsAndTwelveGains()
    {
        Session session = new Session("Vorkath", 0L);
        session.addTransaction(sips(12L, null), 100);
        SessionMetrics metrics = session.metrics(2_000L);
        assertEquals("the (3) left over is no gain", 0L, metrics.revenue);
        assertEquals("only the doses drunk are a cost", 12L * 2_450L, metrics.costs);
        assertTrue(metrics.costSplitAvailable);
        assertEquals("a drink is a supply", 12L * 2_450L, metrics.suppliesCosts);
        assertEquals(-12L * 2_450L, metrics.net);
    }

    /** Release pass 2026-09-28: a Grind's highlight names a sip by its potion, not its "(4)". */
    @Test
    public void aSipHighlightReadsAsThePotion()
    {
        assertEquals("Prayer potion", GrindHistory.leadName(sips(2L, null)));
    }

    @Test
    public void aCorrectedRowKeepsItsGrossAmounts()
    {
        Session session = new Session("Vorkath", 0L);
        session.addTransaction(sips(1L, Correction.COST), 100);
        assertEquals("a Cost correction still costs the gross, as before", 9_800L + 7_350L,
            session.metrics(2_000L).costs);
    }
}
