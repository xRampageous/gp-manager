package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PkStreakTest
{
    @Test
    public void bestStreakKeepsTheLongestRunAndCurrentResetsOnDeath()
    {
        Session session = new Session("PK", 0L, SessionMode.PK);
        EncounterType[] order = {EncounterType.KILL, EncounterType.KILL, EncounterType.KILL,
            EncounterType.DEATH, EncounterType.KILL};
        long at = 1_000L;
        for (EncounterType type : order)
        {
            session.addPkEncounter(type, at += 1_000L, "fight", ClassificationConfidence.CONFIRMED, "");
        }
        assertEquals(1, session.pkMetrics().currentStreak);
        assertEquals(3, session.bestPkStreak());

        session.addPkEncounter(EncounterType.DEATH, at + 1_000L, "fight", ClassificationConfidence.CONFIRMED, "");
        assertEquals(3, session.bestPkStreak());
    }
}
