package com.gpmanager.model;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PkLocationLedgerTest
{
    @Test
    public void adjacentSameLabelSegmentsMergeAcrossPauseAndResume()
    {
        PkLocationLedger ledger = new PkLocationLedger(100L);
        ledger.observe("Wilderness", 100L);
        ledger.pause(120L);
        ledger.resume(120L);
        ledger.observe(" Wilderness ", 120L);
        ledger.pause(150L);

        assertEquals(1, ledger.getRetainedSegments().size());
        PkLocationLedger.Segment segment = ledger.getRetainedSegments().get(0);
        assertEquals("Wilderness", segment.getLocationLabel());
        assertEquals(100L, segment.getEnteredAtEpochMillis());
        assertEquals(150L, segment.getLeftAtEpochMillis());
        assertEquals(Long.valueOf(50L), ledger.getTotalMillisByLabel().get("Wilderness"));
    }

    @Test
    public void capEvictsOnlySegmentDetailAndPreservesLifetimeLabelTotals()
    {
        PkLocationLedger ledger = new PkLocationLedger(1L);
        ledger.observe("Place 0", 1L);
        for (int index = 1; index <= PkLocationLedger.MAX_SEGMENTS + 1; index++)
        {
            ledger.observe("Place " + index, index * 10L);
        }
        ledger.pause((PkLocationLedger.MAX_SEGMENTS + 2L) * 10L);

        assertEquals(PkLocationLedger.MAX_SEGMENTS, ledger.getRetainedSegments().size());
        assertEquals(Long.valueOf(9L), ledger.getTotalMillisByLabel().get("Place 0"));
        assertEquals(Long.valueOf(10L), ledger.getTotalMillisByLabel()
            .get("Place " + (PkLocationLedger.MAX_SEGMENTS + 1)));
        assertTrue(ledger.getTruncatedThroughEpochMillis() > 1L);
        assertFalse(ledger.hasCompleteCoverageFrom(1L, 1L));

        PkLocationLedger restored = new Gson().fromJson(new Gson().toJson(ledger),
            PkLocationLedger.class);
        assertEquals(ledger.getTotalMillisByLabel(), restored.getTotalMillisByLabel());
        assertEquals(ledger.getUnlabelledMillis(), restored.getUnlabelledMillis());
        assertEquals(PkLocationLedger.MAX_SEGMENTS, restored.getRetainedSegments().size());
    }

    @Test
    public void oldProfileHasNoInventedPlaceHistoryAndStartsCoverageAtFirstSample()
    {
        ProfitSession old = new Gson().fromJson(
            "{\"id\":\"old\",\"startedAtEpochMillis\":10,\"pkEncounters\":[]}",
            ProfitSession.class);
        assertNotNull(old);
        assertFalse(old.hasPkLocationLedger());
        assertNull(old.getPkLocationLedger());

        old.observePkLocation("Wilderness", 100L);
        PkLocationLedger migrated = old.getPkLocationLedger();
        assertNotNull(migrated);
        assertTrue(migrated.hasCompleteCoverageFrom(100L, 10L));
        assertFalse(migrated.hasCompleteCoverageFrom(50L, 10L));
    }

    @Test
    public void nullLabelsAccumulateOnlyInUnknownRemainder()
    {
        PkLocationLedger ledger = new PkLocationLedger(100L);
        ledger.observe("Wilderness", 120L);
        ledger.observe(null, 150L);
        ledger.pause(190L);

        assertEquals(30L, ledger.getTotalMillisByLabel().get("Wilderness").longValue());
        assertEquals(60L, ledger.getUnlabelledMillis());
        for (PkLocationLedger.Segment segment : ledger.getRetainedSegments())
        {
            assertTrue(segment.getLocationLabel() == null
                || !segment.getLocationLabel().isEmpty());
        }
    }
}
