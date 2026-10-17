package com.gpmanager;

import com.google.gson.Gson;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The best kill reads live receipts, survives compaction once, and never counts a receipt twice. */
public class PkTallyTest
{
    private static Transaction loot(long at, long gp)
    {
        return Tx.of(at, TransactionType.PK_LOOT, Context.PK_LOOT, "PK loot", true,
            Collections.singletonList(new Flow(2, "Loot", 1L, (int) gp, gp)));
    }

    private static Transaction filler(long at)
    {
        return Tx.of(at, TransactionType.ADJUSTMENT, Context.GENERIC, "", false,
            Collections.emptyList());
    }

    private static Transaction kill(Session session, long at, long gp, int limit)
    {
        String id = session.addEncounter(true, at);
        Transaction loot = loot(at, gp);
        session.addTransaction(loot, limit, false);
        session.attachToEncounter(loot.getId(), id, false);
        return loot;
    }

    @Test
    public void bestKillIsTheLargestKillNetAndFollowsUndoAndRedo()
    {
        Session session = new Session("PK", 0L, SessionMode.AUTO);
        kill(session, 1_000L, 300L, 10);
        kill(session, 2_000L, 700L, 10);
        assertEquals(700L, session.bestKill());

        session.undoTransaction();
        assertEquals("undo reveals the next best", 300L, session.bestKill());
        session.restoreUndo();
        assertEquals(700L, session.bestKill());
        assertEquals(2, session.pk().kills);
    }

    @Test
    public void aKillSplitAcrossCompactionCountsItsWholeNetOnce()
    {
        Session session = new Session("PK", 0L, SessionMode.AUTO);
        String id = session.addEncounter(true, 1_000L);
        Transaction first = loot(1_000L, 400L);
        Transaction second = loot(1_100L, 500L);
        session.addTransaction(first, 3, false);
        session.attachToEncounter(first.getId(), id, false);
        session.addTransaction(second, 3, false);
        session.attachToEncounter(second.getId(), id, false);
        assertEquals(900L, session.bestKill());

        session.addTransaction(filler(2_000L), 3, false);
        session.addTransaction(filler(3_000L), 3, false);
        assertEquals("one receipt compacted, one live", 900L, session.bestKill());
        assertEquals(1, session.pk().compactedKillNet.size());

        session.addTransaction(filler(4_000L), 3, false);
        assertEquals(900L, session.bestKill());
        assertTrue("the kill folds once its last receipt goes", session.pk().compactedKillNet.isEmpty());
    }

    @Test
    public void deathsAndOtherEncounterIdsNeverReachTheBestKill()
    {
        Session session = new Session("PK", 0L, SessionMode.AUTO);
        String death = session.addEncounter(false, 1_000L);
        Transaction lost = Tx.of(1_000L, TransactionType.PK_DEATH_LOSS, Context.PK_DEATH, "", true,
            Collections.singletonList(new Flow(2, "Loot", -1L, 900, -900L)));
        session.addTransaction(lost, 10, false);
        session.attachToEncounter(lost.getId(), death, false);
        Transaction clue = loot(2_000L, 5_000L);
        session.addTransaction(clue, 10, false);
        session.attachToEncounter(clue.getId(), "loot:Reward casket", false);

        assertEquals(0L, session.bestKill());
        assertEquals("", clue.getEncounterId());
        assertEquals(1, session.pk().deaths);
        assertEquals(-1, session.pk().streak);
    }

    @Test
    public void theTallySurvivesASaveRoundTrip()
    {
        Session session = new Session("PK", 0L, SessionMode.AUTO);
        kill(session, 1_000L, 300L, 10);
        session.addEncounter(false, 2_000L);

        Gson gson = new Gson();
        Session restored = gson.fromJson(gson.toJson(session), Session.class);
        assertEquals(1, restored.pk().kills);
        assertEquals(1, restored.pk().deaths);
        assertEquals(1, restored.pk().bestStreak);
        assertEquals(300L, restored.bestKill());
    }
}
