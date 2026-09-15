package com.gpmanager.model;

import com.google.gson.Gson;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PartySummaryTest
{
    @Test
    public void partySummaryPersistsOnSessionWithoutLivePresentationObjects()
    {
        ProfitSession session = new ProfitSession("Party run", 1_000L);
        PartySummary summary = new PartySummary(
            900L,
            60_000L,
            Collections.singletonList(new PartySummary.Member(
                "Alice", 500L, 30_000L, true, true, false, false)));
        session.setPartySummary(summary);

        PartySummary beforeSave = session.getPartySummary();
        assertEquals(900L, beforeSave.getCombinedNetGp());
        assertEquals(60_000L, beforeSave.getElapsedMillis());
        assertTrue(beforeSave.isCombinedNetComplete());
        assertEquals("Alice", beforeSave.getMembers().get(0).getDisplayName());
        assertEquals(500L, beforeSave.getMembers().get(0).getFinalNetGp());
        assertEquals(30_000L, beforeSave.getMembers().get(0).getFinalGpPerHour());
        assertTrue(beforeSave.getMembers().get(0).isNetShared());
        assertFalse(beforeSave.getMembers().get(0).isActivityShared());

        PartySummary.Member withheld = new PartySummary.Member(
            "Bob", 999L, 888L, false, false, true, false);
        assertEquals(0L, withheld.getFinalNetGp());
        assertEquals(0L, withheld.getFinalGpPerHour());

        Gson gson = new Gson();
        ProfitSession restored = gson.fromJson(gson.toJson(session), ProfitSession.class);
        assertEquals(900L, restored.getPartySummary().getCombinedNetGp());
        assertEquals(500L, restored.getPartySummary().getMembers().get(0).getFinalNetGp());
        assertNull(gson.fromJson("{\"name\":\"legacy\"}", ProfitSession.class).getPartySummary());
    }
}
