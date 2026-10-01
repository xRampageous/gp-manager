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
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        Transaction oldest = null;
        for (int i = 0; i < 225; i++)
        {
            Transaction transaction = booked(now + i + 1L, "Item " + i, 1_000 + i, 100L);
            if (oldest == null) oldest = transaction;
            engine.getActiveSession().addTransaction(transaction, 2_000);
        }
        assertNotNull(oldest);
        final String oldId = oldest.getId();

        LedgerData first = LedgerData.capture(engine, now + 1_000L,
            LedgerData.Entry.current());
        assertEquals(225, first.gains.entries);
        assertEquals("every retained row reaches the page table, which pages it", 225, first.gains.groups.size());
        assertEquals(22_500L, first.gains.total);

        LedgerData searched = LedgerData.capture(engine, now + 1_000L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.SUPPLIES, "Item 222", null, null, null, null));
        assertEquals("search sees the full retained set before page slicing", 1, searched.gains.entries);
        assertEquals("Item 222", searched.gains.groups.get(0).primaryName);

        LedgerData deepLink = LedgerData.capture(engine, now + 1_000L,
            new LedgerData.Entry(LedgerData.Scope.CURRENT_GRIND, null, null,
                LedgerData.CostView.SUPPLIES, "", oldId, null, null, null));
        assertNotNull("the deep link opens the owning group detail", deepLink.detail);
        assertTrue(deepLink.detail.group.containsTransaction(oldId));
    }

    @Test
    public void grindSectionsCarryFullHistoryAndSavedSetAndUseThePassedProfileZone() throws Exception
    {
        Engine engine = PresentationLifecycleTest.engine();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 31; i++)
        {
            engine.startCustomSession("History " + i, SessionMode.GENERAL, now + i * 10L);
            engine.finishCustomSession(now + i * 10L + 1L);
        }
        for (int i = 0; i < 31; i++)
        {
            engine.saveGrind("Saved " + i, null, null, false, null);
        }

        GrindsData data = GrindsData.capture(engine, now + 2_000L, false, null);
        assertEquals("every saved Grind reaches the table", 31, data.myGrinds.size());
        assertEquals("no Recent runs cap", 31, data.recent.size());

        long recentAt = Instant.parse("2024-01-01T11:00:00Z").toEpochMilli();
        long profileNow = Instant.parse("2024-01-01T13:00:00Z").toEpochMilli();
        GrindsData.Recent sample = new GrindsData.Recent("s", "Grind", recentAt, 0L, 0L, "");
        String label = GrindsPage.contextLine(sample, profileNow, ZoneId.of("Pacific/Kiritimati"));
        assertTrue("Recent Grind day labels follow the supplied analytics zone", label.startsWith("Today"));
    }

    private static Transaction booked(long at, String name, int itemId, long value)
    {
        return new Transaction(at, null, TransactionType.GAIN, Context.GENERIC,
            "", "Vorkath", true, Collections.singletonList(new Flow(itemId, name, 1L,
                Math.toIntExact(value), value)), ClassificationConfidence.LIKELY, "test", null);
    }
}
