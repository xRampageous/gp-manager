package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Owner 2026-10-01 (F04): PB claims compare whole-run rates; the rolling pace never claims one. */
public class HudBestRateBasisTest
{
    private static final long NOW = 1_700_000_000_000L;
    private static final long RUN_START = NOW - 3_600_000L;
    private static final long PB = 2_000_000L;


    @Test
    public void aGenuineWholeRunRecordClaimsIt()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, RUN_START);
        book(engine.getActiveSession(), RUN_START + 60_000L, 3_000_000L);
        Ca snapshot = Ca.capture(engine, NOW, null);

        assertEquals("the claim uses the whole-run rate",
            "New best GP/h · " + Fmt.rate(3_000_000L) + "/h",
            new HudMoments().update(snapshot, null, PB, "", NOW));
    }

    @Test
    public void theSixtySecondFloorStillApplies()
    {
        Am under = engine();
        under.ajl("Vorkath", Cx.GENERAL, NOW - 59_000L);
        book(under.getActiveSession(), NOW - 58_000L, 3_000_000L);
        assertEquals("under a minute is not a rate", "",
            new HudMoments().update(Ca.capture(under, NOW, null), null, 1L, "", NOW));

        Am exact = engine();
        exact.ajl("Vorkath", Cx.GENERAL, NOW - 60_000L);
        book(exact.getActiveSession(), NOW - 59_000L, 3_000_000L);
        assertTrue("exactly a minute qualifies",
            new HudMoments().update(Ca.capture(exact, NOW, null), null, 1L, "", NOW)
                .startsWith("New best GP/h"));
    }

    @Test
    public void aZeroRateClaimsNothing()
    {
        Am engine = engine();
        engine.ajl("Vorkath", Cx.GENERAL, RUN_START);
        assertEquals("", new HudMoments().update(Ca.capture(engine, NOW, null), null, 1L, "", NOW));
    }

    private static void book(Ad session, long at, long value)
    {
        session.kf(new Ac(at, null, Ai.LOOT, Aj.LOOT, "", "Vorkath", true,
            Collections.singletonList(new Ab(536, "Dragon bones", 1L, (int) value, value)),
            Bd.CONFIRMED, "fixture", null), 2_000);
    }

    private static Am engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
        };
        return new Am(deltas -> Collections.emptyList(), new TransactionClassifier(), config);
    }
}
