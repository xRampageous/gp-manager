package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A Grind's GP/h, live or closed, is the full-session rate; the
 * rate is Net over active time, so compaction never hides it.
 */
public class ClosedRateReadinessTest
{
    private static final long NOW = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;

    @Test
    public void aCompactedClosedRunStillShowsItsFullRunRate()
    {
        Fixture fixture = new Fixture();
        fixture.engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW - HOUR);
        Session session = fixture.engine.getActiveSession();
        String id = session.getId();
        book(session, NOW - HOUR + 60_000L, 1_000_000L);
        book(session, NOW - 30_000L, 1_000_000L);
        fixture.engine.finishCustomSession(NOW);

        Session closed = fixture.engine.getHistorySession(id);
        closed.compactTransactionsBefore(NOW - 30_000L, null);
        GrindsData data = GrindsData.capture(fixture.engine, NOW, false, id);
        assertTrue("the closed detail rate is established", data.detail.rateEstablished);
        assertEquals("on the full-session basis", 2_000_000L, data.detail.gpPerHour);
        assertEquals("the money stays exact", 2_000_000L, data.detail.net);
    }

    @Test
    public void theActiveRateSurvivesCompaction()
    {
        Fixture fixture = new Fixture();
        fixture.engine.startCustomSession("Vorkath", SessionMode.GENERAL, NOW - HOUR);
        Session session = fixture.engine.getActiveSession();
        book(session, NOW - HOUR + 60_000L, 1_000_000L);
        book(session, NOW - 30_000L, 1_000_000L);
        session.compactTransactionsBefore(NOW - 30_000L, null);
        GrindsData data = GrindsData.capture(fixture.engine, NOW, false, session.getId());
        assertTrue("compaction never hides the live rate: it is Net over active time",
            data.detail.rateEstablished);
        assertEquals(2_000_000L, data.detail.gpPerHour);
    }

    @Test
    public void theClosedFloorIsSixtySecondsAndZeroIsHonest()
    {
        Engine under = engine();
        under.startCustomSession("Vorkath", SessionMode.GENERAL, NOW - 59_000L);
        book(under.getActiveSession(), NOW - 58_000L, 60_000L);
        String underId = under.getActiveSession().getId();
        under.finishCustomSession(NOW);
        assertFalse("under a minute has no rate",
            GrindsData.capture(under, NOW, false, underId).detail.rateEstablished);

        Engine exact = engine();
        exact.startCustomSession("Vorkath", SessionMode.GENERAL, NOW - 60_000L);
        book(exact.getActiveSession(), NOW - 59_000L, 60_000L);
        String exactId = exact.getActiveSession().getId();
        exact.finishCustomSession(NOW);
        GrindsData closed = GrindsData.capture(exact, NOW, false, exactId);
        assertTrue("exactly a minute qualifies", closed.detail.rateEstablished);
        assertEquals(3_600_000L, closed.detail.gpPerHour);

        Engine zero = engine();
        zero.startCustomSession("Vorkath", SessionMode.GENERAL, NOW - 120_000L);
        String zeroId = zero.getActiveSession().getId();
        zero.finishCustomSession(NOW);
        GrindsData empty = GrindsData.capture(zero, NOW, false, zeroId);
        assertTrue("a real zero is not unavailable", empty.detail.rateEstablished);
        assertEquals(0L, empty.detail.gpPerHour);
    }

    private static void book(Session session, long at, long value)
    {
        session.addTransaction(new Transaction(at, null, TransactionType.LOOT, Context.LOOT, "", "Vorkath", true,
            Collections.singletonList(new Flow(536, "Dragon bones", 1L, (int) value, value)),
            ClassificationConfidence.CONFIRMED, "fixture", null), 2_000);
    }

    private static Engine engine()
    {
        return new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig()
            {
            });
    }

    private static final class Fixture
    {
        final Engine engine = engine();
    }
}
