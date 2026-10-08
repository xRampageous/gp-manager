package com.gpmanager;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Owner 2026-10-09 (1.2.1): Review entries 1.2.0 would have booked from their items alone are
 * booked that way when a save loads. Corrections and every other entry stay exactly as saved.
 */
public class ReviewRecheckTest
{
    private static final long NOW = 1_700_000_000_000L;

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void oldReviewEntriesTheItemsExplainAreBookedOnLoad()
    {
        Am saved = PresentationLifecycleTest.engine();
        saved.ajl("Old", Cx.GENERAL, NOW);
        Ac casket = review(NOW + 1_000L, List.of(unpriced(20544, "Reward casket (hard)", -1L),
            new Ab(1127, "Rune platebody", 1L, 38_000, 38_000L)));
        Ac step = review(NOW + 2_000L, List.of(unpriced(2722, "Clue scroll (hard)", -1L),
            unpriced(2723, "Clue scroll (hard)", 1L)));
        Ac potions = review(NOW + 3_000L, List.of(new Ab(139, "Prayer potion(3)", -1L, 9_000, -9_000L),
            new Ab(143, "Prayer potion(1)", -1L, 3_000, -3_000L), new Ab(2434, "Prayer potion(4)", 1L, 12_100, 12_100L),
            new Ab(229, "Vial", 1L, 0, 0L)));
        Ac jar = review(NOW + 4_000L, List.of(new Ab(11248, "Eclectic impling jar", -1L, 20_000, -20_000L),
            new Ab(1127, "Rune platebody", 1L, 38_000, 38_000L), new Ab(11260, "Impling jar", 1L, 290, 290L)));
        Ac corrected = review(NOW + 5_000L, List.of(unpriced(20544, "Reward casket (hard)", -1L),
            new Ab(1127, "Rune platebody", 1L, 38_000, 38_000L)));
        corrected.ko(Ah.IGNORE, NOW + 6_000L);
        Ac fletching = review(NOW + 7_000L, List.of(new Ab(66, "Yew longbow (u)", 27L, 158, 4_266L),
            new Ab(1515, "Yew logs", -27L, 92, -2_484L)));
        for (Ac entry : List.of(casket, step, potions, jar, corrected, fletching))
        {
            saved.getActiveSession().kf(entry, 2_000);
        }

        Am loaded = PresentationLifecycleTest.engine();
        loaded.restore(saved.qm(), NOW + 10_000L);
        assertEquals("a casket and an impling jar opened are gains", 38_000L + 18_290L,
            loaded.getMetrics(NOW + 20_000L).net);
        Ac openedCasket = find(loaded, casket.getId());
        assertSame(Ai.GAIN, openedCasket.getType());
        assertTrue(openedCasket.isCounted());
        assertTrue(openedCasket.getExplanation().contains("1.2.1"));
        assertSame(Ai.TRANSFER, find(loaded, step.getId()).getType());
        assertSame(Ai.TRANSFER, find(loaded, potions.getId()).getType());
        assertEquals("Decanted", find(loaded, potions.getId()).getNote());
        assertSame("a correction is the player's own", Ah.IGNORE, find(loaded, corrected.getId()).getCorrection());
        assertSame("and stays as saved", find(saved, corrected.getId()).getType(),
            find(loaded, corrected.getId()).getType());
        assertTrue("other Review entries still wait", Eh.aal(find(loaded, fletching.getId())));
        assertEquals(1, reviews(loaded));

        Am again = PresentationLifecycleTest.engine();
        again.restore(loaded.qm(), NOW + 30_000L);
        assertEquals("loading again changes nothing", loaded.getMetrics(NOW + 20_000L).net,
            again.getMetrics(NOW + 20_000L).net);
        assertFalse(find(again, casket.getId()).getExplanation().matches(".*1\\.2\\.1.*1\\.2\\.1.*"));
    }

    private static Ac review(long at, List<Ab> flows)
    {
        Ac entry = Tx.of(at, Ai.UNCERTAIN, Aj.GENERIC, "", false, flows);
        entry.confidence = Bd.UNCERTAIN;
        return entry;
    }

    private static Ab unpriced(int id, String name, long quantity)
    {
        return new Ab(id, name, quantity, 0, 0L, Av.UNPRICED);
    }

    private static Ac find(Am engine, String id)
    {
        for (Ad session : engine.akf())
        {
            for (Ac entry : session.getTransactions())
            {
                if (entry.getId().equals(id)) return entry;
            }
        }
        throw new AssertionError("missing " + id);
    }

    private static int reviews(Am engine)
    {
        int count = 0;
        for (Ad session : engine.akf())
        {
            for (Ac entry : session.getTransactions())
            {
                if (Eh.aal(entry)) count++;
            }
        }
        return count;
    }
}
