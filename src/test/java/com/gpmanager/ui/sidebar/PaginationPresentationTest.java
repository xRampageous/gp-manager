package com.gpmanager;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PaginationPresentationTest
{
    @Test
    public void ledgerTablesRetainAndDeepLinkPastTheOldTwoHundredRowCap() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.ajl("Vorkath", Cx.GENERAL, now);
        Ac oldest = null;
        for (int i = 0; i < 225; i++)
        {
            Ac transaction = booked(now + i + 1L, "Item " + i, 1_000 + i, 100L);
            if (oldest == null) oldest = transaction;
            engine.getActiveSession().kf(transaction, 2_000);
        }
        assertNotNull(oldest);
        final String oldId = oldest.getId();

        Ao first = Ao.capture(engine, now + 1_000L,
            Ao.Entry.current());
        assertEquals(225, first.gains.entries);
        assertEquals("every retained row reaches the page table, which pages it", 225, first.gains.groups.size());
        assertEquals(22_500L, first.gains.total);

        Ao searched = Ao.capture(engine, now + 1_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "Item 222", null, null, null, null));
        assertEquals("search sees the full retained set before page slicing", 1, searched.gains.entries);
        assertEquals("Item 222", searched.gains.groups.get(0).primaryName);

        Ao deepLink = Ao.capture(engine, now + 1_000L,
            new Ao.Entry(Ao.Scope.CURRENT_GRIND, null, null,
                Ao.Bs.SUPPLIES, "", oldId, null, null, null));
        assertNotNull("the deep link opens the owning group detail", deepLink.detail);
        assertTrue(deepLink.detail.group.containsTransaction(oldId));
    }

    @Test
    public void grindSectionsCarryFullHistoryAndSavedSetAndUseThePassedProfileZone() throws Exception
    {
        Am engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 31; i++)
        {
            engine.ajl("History " + i, Cx.GENERAL, now + i * 10L);
            engine.sx(now + i * 10L + 1L);
        }
        for (int i = 0; i < 31; i++)
        {
            engine.avg("Saved " + i, null, null, false, null);
        }

        As data = As.capture(engine, now + 2_000L, false, null);
        assertEquals("every saved Grind reaches the table", 31, data.myGrinds.size());
        assertEquals("no Recent runs cap", 31, data.recent.size());

        long recentAt = Instant.parse("2024-01-01T11:00:00Z").toEpochMilli();
        long profileNow = Instant.parse("2024-01-01T13:00:00Z").toEpochMilli();
        As.Recent sample = new As.Recent("s", "Grind", recentAt, 0L, 0L, "");
        String label = GrindsPage.qe(sample, profileNow, ZoneId.of("Pacific/Kiritimati"));
        assertTrue("Recent Grind day labels follow the supplied analytics zone", label.startsWith("Today"));
    }

    private static Ac booked(long at, String name, int itemId, long value)
    {
        return new Ac(at, null, Ai.GAIN, Aj.GENERIC,
            "", "Vorkath", true, Collections.singletonList(new Ab(itemId, name, 1L,
                Math.toIntExact(value), value)), Bd.LIKELY, "test", null);
    }
}
