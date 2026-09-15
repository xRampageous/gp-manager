package com.gpmanager.party;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PartyProfitMessageTest
{
    @Test
    public void carriesAReplaceableSessionSnapshot()
    {
        PartyProfitMessage message = new PartyProfitMessage(
            "generation", 4L, 123L, "session", 10L, 3L, 7L, 70L, 50L, 2);

        assertEquals("generation", message.getGeneration());
        assertEquals(4L, message.getRevision());
        assertEquals("session", message.getSessionId());
        assertEquals(10L, message.getRevenue());
        assertEquals(3L, message.getCosts());
        assertEquals(7L, message.getNet());
        assertEquals(50L, message.getRollingProfitPerHour());
        assertEquals(2, message.getTransactionCount());
        assertEquals("", message.getActivityName());
        assertNull(message.getNotableDrop());
    }

    @Test
    public void carriesOptionalActivityAndNotableDropWithLegacyDefaults()
    {
        PartyProfitMessage message = new PartyProfitMessage(
            "generation", 4L, 123L, "session", 10L, 3L, 7L, 70L, 50L, 2,
            "  Zulrah  ", "  Tanzanite fang · 4.2m  ");
        assertEquals("Zulrah", message.getActivityName());
        assertEquals("Tanzanite fang · 4.2m", message.getNotableDrop());

        PartyProfitMessage legacy = new Gson().fromJson(
            "{\"generation\":\"old\",\"transactionCount\":2}", PartyProfitMessage.class);
        assertEquals("old", legacy.getGeneration());
        assertEquals("", legacy.getActivityName());
        assertNull(legacy.getNotableDrop());
    }
}
