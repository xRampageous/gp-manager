package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.*;

/** Account ABA, quantity conservation, and inclusive expiry for every inbox kind. */
public class InboxTest
{
    private static final int BONES = 526;

    @Test
    public void ownerCycleRejectsEveryOldObservationEvenWhenReinserted()
    {
        Inbox inbox = new Inbox();
        for (int kind = Inbox.FEE; kind <= Inbox.BANK_UI; kind++)
        {
            inbox.owner(1001L);
            Observation old = inbox.add(kind, BONES, 3L, 5, "A");
            inbox.owner(2002L);
            assertTrue(inbox.open.isEmpty());
            inbox.owner(1003L);
            inbox.open.add(old);
            assertNull(inbox.peek(kind));
            assertEquals(0L, inbox.take(kind, BONES, 3L, observation -> true));
        }
    }

    @Test
    public void oversizedUseIsBoundedAndCannotRepeat()
    {
        for (int kind = Inbox.FEE; kind <= Inbox.BANK_UI; kind++)
        {
            Inbox inbox = new Inbox();
            inbox.add(kind, BONES, 3L, 5, "bag");
            assertEquals(2L, inbox.take(kind, BONES, 2L, observation -> true));
            assertEquals(1L, inbox.take(kind, BONES, 100L, observation -> true));
            assertEquals(0L, inbox.take(kind, BONES, 1L, observation -> true));
        }
    }

    @Test
    public void expiryIncludesTheBoundaryTick()
    {
        for (int kind = Inbox.FEE; kind <= Inbox.BANK_UI; kind++)
        {
            Inbox inbox = new Inbox();
            inbox.tick(10);
            inbox.add(kind, BONES, 3L, 5, null);
            inbox.tick(15);
            assertEquals(1L, inbox.take(kind, BONES, 1L, observation -> true));
            inbox.tick(16);
            assertEquals(0L, inbox.take(kind, BONES, 2L, observation -> true));
        }
    }

    @Test
    public void sourcePredicateAndItemMustBothMatch()
    {
        Inbox inbox = new Inbox();
        inbox.add(Inbox.CHEST, BONES, 3L, 5, "left");
        inbox.add(Inbox.CHEST, BONES, 4L, 5, "right");
        assertEquals(0L, inbox.take(Inbox.CHEST, 995, 100L, observation -> true));
        assertEquals(4L, inbox.take(Inbox.CHEST, BONES, 100L, observation -> "right".equals(observation.note)));
        assertEquals(3L, inbox.peek(Inbox.CHEST).qty);
    }
}
