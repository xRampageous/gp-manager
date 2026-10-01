package com.gpmanager;

import java.util.Collections;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The Live list is reused between ticks and rebuilt on a booking, a Market change or a filter change. */
public class LiveRecentCacheTest
{
    @Test
    public void theLiveListIsReusedUntilSomethingItShowsChanges()
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = 1_700_000_000_000L;
        engine.ajl("Vorkath", Cx.GENERAL, now);
        engine.getActiveSession().kf(Tx.of(now, Ai.LOOT, Aj.GENERIC, "", true,
            Collections.singletonList(new Ab(536, "Dragon bones", 1, 2_000, 2_000L))), 2_000);

        boolean[] hideBones = {false};
        String[] version = {"a"};
        RecentFilter filter = new RecentFilter()
        {
            public boolean isFlowIncluded(Ab flow)
            {
                return !(hideBones[0] && flow.itemId == 536);
            }

            public String version()
            {
                return version[0];
            }
        };
        Dz context = new Dz(false, filter, 0L, Bo.NONE, "", false, "");
        Ca first = Ca.capture(engine, now + 1_000L, context);
        Ca tick = Ca.capture(engine, now + 1_600L, context);
        assertSame("an unchanged tick reuses the list", first.recent, tick.recent);
        assertEquals(1, tick.recent.size());

        engine.getActiveSession().kf(Tx.of(now + 2_000L, Ai.LOOT, Aj.GENERIC,
            "", true, Collections.singletonList(new Ab(385, "Shark", 1, 900, 900L))), 2_000);
        Ca booked = Ca.capture(engine, now + 2_200L, context);
        assertNotSame("a booking rebuilds it", tick.recent, booked.recent);
        assertEquals(2, booked.recent.size());

        hideBones[0] = true;
        version[0] = "b";
        Ca filtered = Ca.capture(engine, now + 2_800L, context);
        assertEquals("a filter change applies its new decisions", 1, filtered.recent.size());

        // A GE offer placed and progressing changes the Market rows without a new revision.
        Bj ledger = new Bj();
        ledger.observe(new Bj.Snapshot(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0));
        engine.abh(ledger.observe(new Bj.Snapshot(0,
            GrandExchangeOfferState.BUYING, 385, 10, 0, 900, 0)).get(), "Shark", now + 3_000L);
        Ca placed = Ca.capture(engine, now + 3_100L, context);
        long revision = engine.getRevision();
        engine.abh(ledger.observe(new Bj.Snapshot(0,
            GrandExchangeOfferState.BUYING, 385, 10, 4, 900, 3_600)).get(), "Shark", now + 3_500L);
        assertEquals("GE progress alone books nothing", revision, engine.getRevision());
        Ca progressed = Ca.capture(engine, now + 3_600L, context);
        assertTrue("the Market row change is seen", placed.recent != progressed.recent);
    }
}
