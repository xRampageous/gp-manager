package com.gpmanager.model;

import java.util.OptionalLong;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class ProfitTargetParserTest
{
    @Test public void acceptsWholeGpCompactAndFormattedTargets()
    {
        assertAmount("1000000000", 1_000_000_000L);
        assertAmount("1,000,000,000", 1_000_000_000L);
        assertAmount("1B", 1_000_000_000L);
        assertAmount("500m", 500_000_000L);
        assertAmount("2.5m", 2_500_000L);
        assertAmount("1k", 1_000L);
    }

    @Test public void rejectsInvalidFractionalNegativeZeroAndOverflow()
    {
        for (String value : new String[] {"", "0", "-1", "1.5", "0.5", "999999999999999999999b", "m"})
        {
            assertFalse(value, ProfitTargetParser.parse(value).isPresent());
        }
    }

    @Test public void targetDoesNotAlterSessionAccounting()
    {
        ProfitSession session = new ProfitSession("General", 0L, SessionMode.AUTO);
        session.addTransaction(new ProfitTransaction(1L, TransactionType.LOOT, TrackingContext.LOOT, "loot", true,
            java.util.Collections.singletonList(new ItemFlow(995, "Coins", 100, 1, 100))), 10);
        long net = session.metrics(2L, 60_000L).getNet();
        assertTrue(session.setProfitTargetGp(1_000L));
        assertEquals(net, session.metrics(2L, 60_000L).getNet());
        session.setProfitTargetGp(null);
        assertEquals(net, session.metrics(2L, 60_000L).getNet());
    }

    private static void assertAmount(String value, long expected)
    {
        OptionalLong parsed = ProfitTargetParser.parse(value);
        assertTrue(parsed.isPresent());
        assertEquals(expected, parsed.getAsLong());
    }
}
