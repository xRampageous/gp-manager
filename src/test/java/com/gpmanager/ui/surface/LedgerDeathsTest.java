package com.gpmanager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LedgerDeathsTest
{
    @Test
    public void deathsListOnlyDeathLossesNewestFirst()
    {
        Transaction older = death(1_000L, -500L);
        Transaction newer = death(5_000L, -2_000L);
        Transaction food = Tx.of(3_000L, TransactionType.CONSUMPTION, Context.GENERIC,
            "", true, Collections.singletonList(new Flow(1, "Food", -1, 100, -100)));

        List<LedgerData.Death> deaths = LedgerData.deaths(Arrays.asList(older, food, newer), "");

        assertEquals(2, deaths.size());
        assertEquals(newer.getId(), deaths.get(0).transactionId);
        assertEquals(-2_000L, deaths.get(0).value);
        assertEquals(older.getId(), deaths.get(1).transactionId);
        assertEquals(1, deaths.get(0).lost.size());
    }

    @Test
    public void pvmDeathIsTheWipeWithItsReclaimFee()
    {
        Transaction wipe = Tx.of(1_000L, TransactionType.TRANSFER, Context.TRANSFER,
            "Death: items held by gravestone / retrieval service", false,
            Collections.singletonList(new Flow(3, "Abyssal whip", -1, 1_500_000, -1_500_000)));
        Transaction fee = Tx.of(2_000L, null, TransactionType.CONSUMPTION,
            Context.GENERIC, "Death reclaim fee", "Death reclaim", true,
            Collections.singletonList(new Flow(995, "Coins", -1_000, 1, -1_000)));

        List<LedgerData.Death> deaths = LedgerData.deaths(Arrays.asList(wipe, fee), "");

        assertEquals(1, deaths.size());
        assertEquals(wipe.getId(), deaths.get(0).transactionId);
        assertEquals("Death · PvM", deaths.get(0).place);
        assertEquals(-1_000L, deaths.get(0).value);
        assertEquals(1_000L, deaths.get(0).fees);
        assertEquals("the grave holds the whip, not counted", 1, deaths.get(0).kept.size());
        assertEquals(0, deaths.get(0).lost.size());
    }

    private static Transaction death(long at, long value)
    {
        return Tx.of(at, TransactionType.PK_DEATH_LOSS, Context.PK_DEATH, "", true,
            Collections.singletonList(new Flow(2, "Rune scimitar", -1, (int) -value, value)));
    }
}
