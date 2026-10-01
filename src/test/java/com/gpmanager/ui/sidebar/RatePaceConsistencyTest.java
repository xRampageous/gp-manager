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
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now - 19_000L);
        engine.getActiveSession().kf(consumed(now - 10_000L, "Prayer potion(4)", 3, -1, 100, -100L), 2_000);

        Ca live = Ca.capture(engine, now, Dz.NONE);
        assertTrue("nineteen seconds is not a trustworthy rate window", live.elapsedMillis < RateReadiness.MIN_ACTIVE_MILLIS);
        assertFalse("Live must not claim an established rate", live.rateEstablished);
        assertEquals("Calculating…", LivePage.awx(live.rateEstablished, live.gpPerHour));

        As grinds =
            As.capture(engine, now, false, null);
        assertFalse("Grinds must agree with Live", grinds.activeRateEstablished);
        assertEquals("—  GP/h", GrindsPage.aww(
            grinds.activeRateEstablished, grinds.activeGpPerHour));
        assertEquals("both surfaces agree exactly",
            live.rateEstablished, grinds.activeRateEstablished);
    }

    @Test
    public void matureRateMayShowAZeroBecauseItIsReal() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now - 5 * 60_000L);
        // Balanced receipts: a real booked net of zero, with a mature Active-Time window.
        engine.getActiveSession().kf(gain(now - 4 * 60_000L, 100L), 2_000);
        engine.getActiveSession().kf(consumed(now - 3 * 60_000L, "Prayer potion(4)", 3, -1, 100, -100L), 2_000);

        Ca live = Ca.capture(engine, now, Dz.NONE);
        assertTrue("five minutes is a mature window", live.elapsedMillis >= RateReadiness.MIN_ACTIVE_MILLIS);
        assertTrue("the canonical rolling rate is available", live.rateEstablished);
        assertEquals("0/h", LivePage.awx(live.rateEstablished, live.gpPerHour));

        As grinds =
            As.capture(engine, now, false, null);
        assertTrue(grinds.activeRateEstablished);
        assertEquals("0/h", GrindsPage.aww(
            grinds.activeRateEstablished, grinds.activeGpPerHour));
    }

    @Test
    public void positiveAndNegativeRatesStillRenderTruthfully() throws Exception
    {
        assertEquals("2.03M/h", LivePage.awx(true, 2_030_000L));
        assertEquals("\u2212412k/h", LivePage.awx(true, -412_000L));
        assertEquals("Calculating…", LivePage.awx(false, 0L));
    }

    @Test
    public void paceAnswersOnlyQuestionsThatExist() throws Exception
    {
        // No targets: no Pace row, no Calculating, no dead space.
        As.Pace none =
            As.pace(null, null, 0L, false, 0L, 0L);
        assertFalse(none.present);
        assertFalse(none.available);
        assertEquals("", none.line);

        // Target present but immature evidence: Calculating (not a fabricated projection).
        As.Pace netPending =
            As.pace(5_000_000L, null, 0L, false, 0L, 20_000L);
        assertTrue(netPending.present);
        assertFalse(netPending.available);
        assertEquals("Calculating\u2026", netPending.line);

        As.Pace timePending =
            As.pace(null, 3L * 3_600_000L, 0L, false, 0L, 20_000L);
        assertTrue(timePending.present);
        assertEquals("Calculating\u2026", timePending.line);

        As.Pace bothPending =
            As.pace(5_000_000L, 3L * 3_600_000L, 0L, false, 0L, 20_000L);
        assertTrue(bothPending.present);
        assertEquals("Calculating\u2026", bothPending.line);

        // Trustworthy evidence: the truthful approximate output returns.
        As.Pace ready =
            As.pace(5_000_000L, null, 2_840_000L, true, 2_030_000L, 3_600_000L);
        assertTrue(ready.present);
        assertTrue(ready.available);
        assertTrue(ready.line.contains("remaining"));

        // A mature zero rate is still an established answer: no fake ETA, never "Calculating".
        As.Pace zeroRate =
            As.pace(5_000_000L, null, 0L, true, 0L, 300_000L);
        assertTrue(zeroRate.present);
        assertTrue("an established rate always produces an answer", zeroRate.available);
        assertEquals("Not on pace", zeroRate.line);

        As.Pace negativeRate =
            As.pace(5_000_000L, null, 0L, true, -200_000L, 300_000L);
        assertTrue(negativeRate.available);
        assertEquals("Not on pace", negativeRate.line);
    }

    @Test
    public void establishedRatesProjectTruthfullyEvenWhenNonPositive() throws Exception
    {
        // Net-only positive: an ETA; zero/negative: an honest "not on pace", never Calculating.
        As.Pace positive =
            As.pace(5_000_000L, null, 2_840_000L, true, 2_030_000L, 3_600_000L);
        assertTrue(positive.available);
        assertTrue(positive.line.contains("remaining"));

        // Time-only: an established zero projects a flat Net; negative projects a decline.
        As.Pace flat =
            As.pace(null, 3L * 3_600_000L, 2_000_000L, true, 0L,
                1L * 3_600_000L);
        assertTrue(flat.available);
        assertTrue("flat projection: " + flat.line, flat.line.contains("projected Net"));
        assertTrue("flat Net keeps the current value: " + flat.line, flat.line.contains("+2.00M"));

        As.Pace declining =
            As.pace(null, 3L * 3_600_000L, 2_000_000L, true, -200_000L,
                1L * 3_600_000L);
        assertTrue(declining.available);
        assertTrue("declining projection stays honest: " + declining.line,
            declining.line.contains("+1.60M"));

        // Both targets with an established negative rate still derive projection and requirement.
        As.Pace both =
            As.pace(5_000_000L, 3L * 3_600_000L, 2_000_000L, true,
                -200_000L, 1L * 3_600_000L);
        assertTrue(both.available);
        assertTrue(both.line.contains("projected at"));
        assertNotNull(both.second);
        assertTrue(both.second.contains("Required average"));

        // Target already reached only when the established evidence says so.
        As.Pace reached =
            As.pace(1_000_000L, null, 2_000_000L, true, 0L, 300_000L);
        assertEquals("Target reached", reached.line);

        // Projection never writes accounting.
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now - 5 * 60_000L);
        engine.getActiveSession().kf(gain(now - 4 * 60_000L, 1_000L), 2_000);
        long net = engine.getMetrics(now).net;
        long revision = engine.getRevision();
        As.capture(engine, now, false, null);
        As.pace(1_000_000L, 3L * 3_600_000L, net, true, -1L, 300_000L);
        assertEquals("estimates never book", net, engine.getMetrics(now).net);
        assertEquals("estimates never revise", revision, engine.getRevision());
    }

    @Test
    public void rateDerivationNeverTouchesAccounting() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now - 20_000L);
        long net = engine.getMetrics(now).net;
        long revision = engine.getRevision();
        for (int i = 0; i < 5; i++)
        {
            Ca.capture(engine, now, Dz.NONE);
            As.capture(engine, now, false, null);
        }
        assertEquals("derivation never books", net, engine.getMetrics(now).net);
        assertEquals("derivation never revises", revision, engine.getRevision());
    }

    private static Ac gain(long at, long value)
    {
        return new Ac(at, null, Ai.GAIN, Aj.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Ab(1, "Dragon bones", 1, (int) value, value)),
            Bd.LIKELY, "test", null);
    }

    private static Ac consumed(long at, String name, int itemId, long quantity, int unitPrice, long value)
    {
        return new Ac(at, null, Ai.CONSUMPTION, Aj.GENERIC, "", "Vorkath",
            true, Collections.singletonList(new Ab(itemId, name, quantity, unitPrice, value)),
            Bd.LIKELY, "test", null);
    }
}
