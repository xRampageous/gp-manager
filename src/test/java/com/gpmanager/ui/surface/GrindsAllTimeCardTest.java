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
        Session vorkath = session("Vorkath", 1_000_000L, SessionOwnerKind.NAMED_SESSION);
        vorkath.close(HOUR);
        Session flipping = session("Flipping", 5_000_000L, SessionOwnerKind.NAMED_SESSION);
        flipping.close(HOUR);
        flipping.setExcludedFromAverages(true);
        Session vorkathAgain = session("Vorkath", 0L, SessionOwnerKind.NAMED_SESSION);
        vorkathAgain.close(HOUR);
        Session freePlay = session("Free play", 200_000L, SessionOwnerKind.FREE_PLAY);

        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig()
            {
            });
        engine.restore(new SavedState(freePlay, null, false, Arrays.asList(vorkath, flipping, vorkathAgain)), HOUR);

        GrindsData.AllTime card = GrindsData.allTime(engine, HOUR);
        assertEquals("Free play and excluded Grinds still count toward all-time Net", 6_200_000L, card.net);
        assertEquals("two runs of Vorkath are one Grind", 2, card.grinds);
        assertEquals("excluded Grinds never become a best", "Vorkath", card.bestNetName);
        assertEquals(1_000_000L, card.bestNet);
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

    private static Session session(String name, long gain, SessionOwnerKind owner)
    {
        Session session = new Session(name, 1L, SessionMode.GENERAL);
        session.setOwnerKind(owner);
        session.addTransaction(Tx.of(1_000L, TransactionType.LOOT, Context.LOOT, "Loot", true,
            Collections.singletonList(new Flow(1, "Loot", 1L, (int) gain, gain))), 100);
        return session;
    }
}
