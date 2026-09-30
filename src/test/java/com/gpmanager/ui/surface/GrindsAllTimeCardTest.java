package com.gpmanager;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GrindsAllTimeCardTest
{
    private static final long HOUR = 3_600_000L;

    @Test
    public void sumsEveryRetainedSessionAndRanksOnlyFinishedIncludedGrinds()
    {
        Ad vorkath = session("Vorkath", 1_000_000L, Bt.NAMED_SESSION);
        vorkath.close(HOUR);
        Ad flipping = session("Flipping", 5_000_000L, Bt.NAMED_SESSION);
        flipping.close(HOUR);
        flipping.setExcludedFromAverages(true);
        Ad vorkathAgain = session("Vorkath", 0L, Bt.NAMED_SESSION);
        vorkathAgain.close(HOUR);
        Ad freePlay = session("Free play", 200_000L, Bt.FREE_PLAY);

        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig()
            {
            });
        engine.restore(new SavedState(freePlay, null, false, Arrays.asList(vorkath, flipping, vorkathAgain)), HOUR);

        As.AllTime card = As.allTime(engine, HOUR);
        assertEquals("Free play and excluded Grinds still count toward all-time Net", 6_200_000L, card.net);
        assertEquals("two runs of Vorkath are one Grind", 2, card.grinds);
        assertEquals("excluded Grinds never become a best", "Vorkath", card.bestNetName);
        assertEquals(1_000_000L, card.bestNet);
    }

    @Test
    public void emptyProfileHasNoBests()
    {
        Am engine = new Am(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig()
            {
            });
        As.AllTime card = As.allTime(engine, HOUR);
        assertEquals(0L, card.net);
        assertNull(card.bestNetName);
        assertNull(card.gpPerHour);
    }

    private static Ad session(String name, long gain, Bt owner)
    {
        Ad session = new Ad(name, 1L, Cx.GENERAL);
        session.setOwnerKind(owner);
        session.kf(Tx.of(1_000L, Ai.LOOT, Aj.LOOT, "Loot", true,
            Collections.singletonList(new Ab(1, "Loot", 1L, (int) gain, gain))), 100);
        return session;
    }
}
