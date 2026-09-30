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
        Ac older = death(1_000L, -500L);
        Ac newer = death(5_000L, -2_000L);
        Ac food = Tx.of(3_000L, Ai.CONSUMPTION, Aj.GENERIC,
            "", true, Collections.singletonList(new Ab(1, "Food", -1, 100, -100)));

        List<Ao.Death> deaths = Ao.deaths(Arrays.asList(older, food, newer), "");

        assertEquals(2, deaths.size());
        assertEquals(newer.getId(), deaths.get(0).transactionId);
        assertEquals(-2_000L, deaths.get(0).value);
        assertEquals(older.getId(), deaths.get(1).transactionId);
        assertEquals(1, deaths.get(0).lost.size());
    }

    @Test
    public void pvmDeathIsTheWipeWithItsReclaimFee()
    {
        Ac wipe = Tx.of(1_000L, Ai.TRANSFER, Aj.TRANSFER,
            "Death: items held by gravestone / retrieval service", false,
            Collections.singletonList(new Ab(3, "Abyssal whip", -1, 1_500_000, -1_500_000)));
        Ac fee = Tx.of(2_000L, null, Ai.CONSUMPTION,
            Aj.GENERIC, "Death reclaim fee", "Death reclaim", true,
            Collections.singletonList(new Ab(995, "Coins", -1_000, 1, -1_000)));

        List<Ao.Death> deaths = Ao.deaths(Arrays.asList(wipe, fee), "");

        assertEquals(1, deaths.size());
        assertEquals(wipe.getId(), deaths.get(0).transactionId);
        assertEquals("Death · PvM", deaths.get(0).place);
        assertEquals(-1_000L, deaths.get(0).value);
        assertEquals(1_000L, deaths.get(0).fees);
        assertEquals("the grave holds the whip, not counted", 1, deaths.get(0).kept.size());
        assertEquals(0, deaths.get(0).lost.size());
    }

    private static Ac death(long at, long value)
    {
        return Tx.of(at, Ai.PK_DEATH_LOSS, Aj.PK_DEATH, "", true,
            Collections.singletonList(new Ab(2, "Rune scimitar", -1, (int) -value, value)));
    }
}
