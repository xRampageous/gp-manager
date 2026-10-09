package com.gpmanager;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The All time card's wealth line: bank (when last seen) plus what is carried (owner 1.2). */
public class WealthLineTest
{
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void carriedAloneUntilTheBankIsSeenThenBothWithTheBanksAge()
    {
        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig()
        {
        });
        engine.rm(NOW);
        engine.carried(2_500_000L);
        As.AllTime before = As.allTime(engine, NOW);
        assertEquals(2_500_000L, before.wealth);
        assertEquals("the bank has not been opened yet", -1L, before.bankAge);

        engine.bank(410_100_000L, NOW - 7_200_000L);
        As.AllTime after = As.allTime(engine, NOW);
        assertEquals(412_600_000L, after.wealth);
        assertEquals(7_200_000L, after.bankAge);
        assertEquals("the bank part, for the breakdown", 410_100_000L, after.bank);
        assertEquals("2h ago", Fmt.age(after.bankAge));
        assertEquals("the card's compact style, cut off like the game (412.6M)", "412M", Fmt.compact(after.wealth));
    }
}
