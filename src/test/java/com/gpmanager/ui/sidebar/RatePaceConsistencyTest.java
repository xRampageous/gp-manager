package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * One cross-surface rate law: an immature/unavailable rate never renders as 0/h, every surface
 * agrees for identical canonical facts, and Pace only answers a question that exists.
 */
public class RatePaceConsistencyTest
{
    @Test
    public void immatureRateIsUnavailableNotZeroOnEverySurface() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now - 19_000L);
        engine.getActiveSession().addTransaction(consumed(now - 10_000L, "Prayer potion(4)", 3, -1, 100, -100L), 2_000);

        LiveSnapshot live = LiveSnapshot.capture(engine, now, LiveContext.NONE);
        assertTrue("nineteen seconds is not a trustworthy rate window", live.elapsedMillis < RateReadiness.MIN_ACTIVE_MILLIS);
        assertFalse("Live must not claim an established rate", live.rateEstablished);
        assertEquals("Calculating…", LivePage.rateText(live.rateEstablished, live.gpPerHour));

        GrindsData grinds =
            GrindsData.capture(engine, now, false, null);
        assertFalse("Grinds must agree with Live", grinds.activeRateEstablished);
        assertEquals("—  GP/h", GrindsPage.rateLine(
            grinds.activeRateEstablished, grinds.activeGpPerHour));
        assertEquals("both surfaces agree exactly",
            live.rateEstablished, grinds.activeRateEstablished);
    }

    @Test
    public void matureRateMayShowAZeroBecauseItIsReal() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now - 5 * 60_000L);
        // Balanced receipts: a real booked net of zero, with a mature Active-Time window.
        engine.getActiveSession().addTransaction(gain(now - 4 * 60_000L, 100L), 2_000);
        engine.getActiveSession().addTransaction(consumed(now - 3 * 60_000L, "Prayer potion(4)", 3, -1, 100, -100L), 2_000);

        LiveSnapshot live = LiveSnapshot.capture(engine, now, LiveContext.NONE);
        assertTrue("five minutes is a mature window", live.elapsedMillis >= RateReadiness.MIN_ACTIVE_MILLIS);
        assertTrue("the canonical rolling rate is available", live.rateEstablished);
        assertEquals("0/h", LivePage.rateText(live.rateEstablished, live.gpPerHour));

        GrindsData grinds =
            GrindsData.capture(engine, now, false, null);
        assertTrue(grinds.activeRateEstablished);
        assertEquals("0/h", GrindsPage.rateLine(
            grinds.activeRateEstablished, grinds.activeGpPerHour));
    }

    @Test
    public void positiveAndNegativeRatesStillRenderTruthfully() throws Exception
    {
        assertEquals("2.03M/h", LivePage.rateText(true, 2_030_000L));
        assertEquals("\u2212412k/h", LivePage.rateText(true, -412_000L));
        assertEquals("Calculating…", LivePage.rateText(false, 0L));
    }

    @Test
    public void paceAnswersOnlyQuestionsThatExist() throws Exception
    {
        // No targets: no Pace row, no Calculating, no dead space.
        GrindsData.Pace none =
            GrindsData.pace(null, null, 0L, false, 0L, 0L);
        assertFalse(none.present);
        assertFalse(none.available);
        assertEquals("", none.line);

        // Target present but immature evidence: Calculating (not a fabricated projection).
        GrindsData.Pace netPending =
            GrindsData.pace(5_000_000L, null, 0L, false, 0L, 20_000L);
        assertTrue(netPending.present);
        assertFalse(netPending.available);
        assertEquals("Calculating\u2026", netPending.line);

        GrindsData.Pace timePending =
            GrindsData.pace(null, 3L * 3_600_000L, 0L, false, 0L, 20_000L);
        assertTrue(timePending.present);
        assertEquals("Calculating\u2026", timePending.line);

        GrindsData.Pace bothPending =
            GrindsData.pace(5_000_000L, 3L * 3_600_000L, 0L, false, 0L, 20_000L);
        assertTrue(bothPending.present);
        assertEquals("Calculating\u2026", bothPending.line);

        // Trustworthy evidence: the truthful approximate output returns.
        GrindsData.Pace ready =
            GrindsData.pace(5_000_000L, null, 2_840_000L, true, 2_030_000L, 3_600_000L);
        assertTrue(ready.present);
        assertTrue(ready.available);
        assertTrue(ready.line.contains("remaining"));

        // A mature zero rate is still an established answer: no fake ETA, never "Calculating".
        GrindsData.Pace zeroRate =
            GrindsData.pace(5_000_000L, null, 0L, true, 0L, 300_000L);
        assertTrue(zeroRate.present);
        assertTrue("an established rate always produces an answer", zeroRate.available);
        assertEquals("Not on pace", zeroRate.line);

        GrindsData.Pace negativeRate =
            GrindsData.pace(5_000_000L, null, 0L, true, -200_000L, 300_000L);
        assertTrue(negativeRate.available);
        assertEquals("Not on pace", negativeRate.line);
    }

    @Test
    public void establishedRatesProjectTruthfullyEvenWhenNonPositive() throws Exception
    {
        // Net-only positive: an ETA; zero/negative: an honest "not on pace", never Calculating.
        GrindsData.Pace positive =
            GrindsData.pace(5_000_000L, null, 2_840_000L, true, 2_030_000L, 3_600_000L);
        assertTrue(positive.available);
        assertTrue(positive.line.contains("remaining"));

        // Time-only: an established zero projects a flat Net; negative projects a decline.
        GrindsData.Pace flat =
            GrindsData.pace(null, 3L * 3_600_000L, 2_000_000L, true, 0L,
                1L * 3_600_000L);
        assertTrue(flat.available);
        assertTrue("flat projection: " + flat.line, flat.line.contains("projected Net"));
        assertTrue("flat Net keeps the current value: " + flat.line, flat.line.contains("+2.00M"));

        GrindsData.Pace declining =
            GrindsData.pace(null, 3L * 3_600_000L, 2_000_000L, true, -200_000L,
                1L * 3_600_000L);
        assertTrue(declining.available);
        assertTrue("declining projection stays honest: " + declining.line,
            declining.line.contains("+1.60M"));

        // Both targets with an established negative rate still derive projection and requirement.
        GrindsData.Pace both =
            GrindsData.pace(5_000_000L, 3L * 3_600_000L, 2_000_000L, true,
                -200_000L, 1L * 3_600_000L);
        assertTrue(both.available);
        assertTrue(both.line.contains("projected at"));
        assertNotNull(both.second);
        assertTrue(both.second.contains("Required average"));

        // Target already reached only when the established evidence says so.
        GrindsData.Pace reached =
            GrindsData.pace(1_000_000L, null, 2_000_000L, true, 0L, 300_000L);
        assertEquals("Target reached", reached.line);

        // Projection never writes accounting.
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now - 5 * 60_000L);
        engine.getActiveSession().addTransaction(gain(now - 4 * 60_000L, 1_000L), 2_000);
        long net = engine.getMetrics(now).net;
        long revision = engine.getRevision();
        GrindsData.capture(engine, now, false, null);
        GrindsData.pace(1_000_000L, 3L * 3_600_000L, net, true, -1L, 300_000L);
        assertEquals("estimates never book", net, engine.getMetrics(now).net);
        assertEquals("estimates never revise", revision, engine.getRevision());
    }

    @Test
    public void rateDerivationNeverTouchesAccounting() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now - 20_000L);
        long net = engine.getMetrics(now).net;
        long revision = engine.getRevision();
        for (int i = 0; i < 5; i++)
        {
            LiveSnapshot.capture(engine, now, LiveContext.NONE);
            GrindsData.capture(engine, now, false, null);
        }
        assertEquals("derivation never books", net, engine.getMetrics(now).net);
        assertEquals("derivation never revises", revision, engine.getRevision());
    }

    private static Transaction gain(long at, long value)
    {
        return new Transaction(at, null, TransactionType.GAIN, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(1, "Dragon bones", 1, (int) value, value)),
            ClassificationConfidence.LIKELY, "test", null);
    }

    private static Transaction consumed(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Transaction(at, null, TransactionType.CONSUMPTION, Context.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Flow(itemId, name, quantity, unitPrice, value)),
            ClassificationConfidence.LIKELY, "test", null);
    }
}
