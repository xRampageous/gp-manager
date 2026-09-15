package com.gpmanager.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrackingDisplaySnapshotSessionChromeTest
{
    @Test
    public void sessionChromeCarriesCustomAndOverallPeek()
    {
        TrackingDisplaySnapshot snap = TrackingDisplaySnapshot.from(
            null, "Tracking", "Live", null, 1_000L, 5_000L, 0L, null, null)
            .withSessionChrome(true, "Vorkath", true, 50_000L, 12_000L, true);
        assertTrue(snap.isCustomSessionActive());
        assertEquals("Vorkath", snap.getSessionName());
        assertTrue(snap.hasOverallPeek());
        assertFalse(snap.isOverallPeekPresent()); // the HUD no longer paints it
        assertEquals(50_000L, snap.getOverallNet());
        assertEquals(12_000L, snap.getOverallProfitPerHour());
        assertTrue(snap.isOverallRateAvailable());

        TrackingDisplaySnapshot overall = snap.withSessionChrome(false, "Overall", false, 0L, 0L, false);
        assertFalse(overall.isCustomSessionActive());
        assertFalse(overall.isOverallPeekPresent());
        assertEquals("Overall", overall.getSessionName());
    }
}
