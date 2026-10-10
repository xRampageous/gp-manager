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
    public void sumsEveryRetainedSessionAndRanksOnlyFinishedGrinds()
    {
        Session vorkath = session("Vorkath", 1_000_000L, true);
        vorkath.close(HOUR);
        Session flipping = session("Flipping", 5_000_000L, true);
        flipping.close(HOUR);
        Session vorkathAgain = session("Vorkath", 0L, true);
        vorkathAgain.close(HOUR);
        Session freePlay = session("Free play", 200_000L, false);

        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig()
            {
            });
        engine.restore(new SavedState(freePlay, null, Arrays.asList(vorkath, flipping, vorkathAgain)), HOUR);

        GrindsData.AllTime card = GrindsData.allTime(engine, HOUR);
        assertEquals("Free play counts toward all-time Net", 6_200_000L, card.net);
        assertEquals("two runs of Vorkath are one Grind", 2, card.grinds);
        assertEquals("open Free play never becomes a best", "Flipping", card.bestNetName);
        assertEquals(5_000_000L, card.bestNet);
    }

    @Test
    public void emptyProfileHasNoBests()
    {
        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig()
            {
            });
        GrindsData.AllTime card = GrindsData.allTime(engine, HOUR);
        assertEquals(0L, card.net);
        assertNull(card.bestNetName);
        assertNull(card.gpPerHour);
    }

    private static Session session(String name, long gain, boolean grind)
    {
        Session session = new Session(name, 1L, SessionMode.GENERAL);
        session.grind = grind;
        session.addTransaction(Tx.of(1_000L, TransactionType.LOOT, Context.LOOT, "Loot", true,
            Collections.singletonList(new Flow(1, "Loot", 1L, (int) gain, gain))), 100);
        return session;
    }
}
