package com.gpmanager.model;

import com.google.gson.Gson;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class EncounterTotalsTest
{
    @Test
    public void sourceNamesMergeCaseInsensitivelyAndSnapshotsAreImmutable()
    {
        EncounterTotals totals = new EncounterTotals();
        assertTrue(totals.record(" Goblin ", 2L, 120L, 1L, 10L));
        assertTrue(totals.record("gObLiN", 3L, 80L, 4L, 7L));

        assertEquals(1, totals.getNamedSourceCount());
        EncounterTotals.SourceSnapshot goblin = totals.getNamedSources().get(0);
        assertEquals("Goblin", goblin.getSourceName());
        assertEquals(5L, goblin.getEncounterCount());
        assertEquals(200L, goblin.getLootValue());
        assertEquals(4L, goblin.getBestStreak());
        assertEquals(10L, goblin.getLastSeenAt());
        assertEquals(5L, totals.getTotalEncounterCount());

        List<EncounterTotals.SourceSnapshot> snapshot = totals.getNamedSources();
        try
        {
            snapshot.clear();
            fail("named source snapshot should be immutable");
        }
        catch (UnsupportedOperationException expected)
        {
            // Expected: callers cannot mutate the aggregate through its snapshot.
        }
    }

    @Test
    public void capsNamedSourcesByEncounterCountAndKeepsOverflowTotals()
    {
        EncounterTotals totals = new EncounterTotals();
        for (int i = 0; i < EncounterTotals.MAX_NAMED_SOURCES; i++)
        {
            assertTrue(totals.record("Source " + i, 1L, 10L, 0L, i));
        }
        assertTrue(totals.record("Most common", 2L, 25L, 2L, 100L));

        assertEquals(EncounterTotals.MAX_NAMED_SOURCES, totals.getNamedSourceCount());
        assertEquals(1L, totals.getRemainder().getEncounterCount());
        assertEquals(10L, totals.getRemainder().getLootValue());
        assertEquals(32L, totals.getTotalEncounterCount());
        assertEquals(325L, totals.getTotalLootValue());
        assertEquals("Most common", totals.getNamedSources().get(0).getSourceName());
    }

    @Test
    public void invalidObservationsAreIgnoredAndValuesSaturateAtLongMax()
    {
        EncounterTotals totals = new EncounterTotals();
        assertFalse(totals.record(null, 1L, 1L, 1L, 1L));
        assertFalse(totals.record("  ", 1L, 1L, 1L, 1L));
        assertFalse(totals.record("Rat", 0L, 1L, 1L, 1L));
        assertTrue(totals.getNamedSources().isEmpty());

        assertTrue(totals.record("Rat", Long.MAX_VALUE, Long.MAX_VALUE, -5L, -1L));
        assertTrue(totals.record("RAT", 1L, Long.MAX_VALUE, 2L, 4L));
        EncounterTotals.SourceSnapshot rat = totals.getNamedSources().get(0);
        assertEquals(Long.MAX_VALUE, rat.getEncounterCount());
        assertEquals(Long.MAX_VALUE, rat.getLootValue());
        assertEquals(2L, rat.getBestStreak());
        assertEquals(4L, rat.getLastSeenAt());
        assertEquals(Long.MAX_VALUE, totals.getTotalEncounterCount());
        assertEquals(Long.MAX_VALUE, totals.getTotalLootValue());
    }

    @Test
    public void copyAndGsonRoundTripKeepIndependentPersistedState()
    {
        EncounterTotals original = new EncounterTotals();
        original.record("Goblin", 2L, 300L, 2L, 20L);
        original.record("Dragon", 1L, 100L, 0L, 15L);

        EncounterTotals copied = original.copy();
        copied.record("Goblin", 1L, 10L, 3L, 30L);
        assertEquals(2L, original.getNamedSources().get(0).getEncounterCount());
        assertEquals(3L, copied.getNamedSources().get(0).getEncounterCount());

        Gson gson = new Gson();
        EncounterTotals restored = gson.fromJson(gson.toJson(original), EncounterTotals.class);
        assertEquals(original.getTotalEncounterCount(), restored.getTotalEncounterCount());
        assertEquals(original.getTotalLootValue(), restored.getTotalLootValue());
        assertEquals(original.getNamedSources().get(0).getSourceName(),
            restored.getNamedSources().get(0).getSourceName());

        EncounterTotals legacyEmpty = gson.fromJson("{}", EncounterTotals.class);
        assertEquals(0L, legacyEmpty.getTotalEncounterCount());
        assertEquals(0L, legacyEmpty.getRemainder().getLootValue());
    }

    @Test
    public void incompleteLootValueIsExplicitForNamedAndRemainderAggregates()
    {
        EncounterTotals named = new EncounterTotals();
        named.record("Unknown-price source", 2L, 0L, false, 0L, 1L);
        named.record("Known source", 1L, 100L, true, 0L, 2L);

        assertFalse(named.getNamedSources().get(0).isLootValueKnown());
        assertFalse(named.isTotalLootValueKnown());
        assertEquals(100L, named.getTotalLootValue());

        EncounterTotals remainder = new EncounterTotals();
        for (int i = 0; i < EncounterTotals.MAX_NAMED_SOURCES; i++)
        {
            remainder.record("Source " + i, 1L, 1L, 0L, i);
        }
        remainder.record("Unknown overflow", 1L, 0L, false, 0L, 100L);

        assertEquals(1L, remainder.getRemainder().getEncounterCount());
        assertFalse(remainder.getRemainder().isLootValueKnown());
        assertFalse(remainder.isTotalLootValueKnown());
    }
}
