package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PkStreakTest
{
    @Test
    public void bestStreakKeepsTheLongestRunAndCurrentResetsOnDeath()
    {
        Session session = new Session("PK", 0L, SessionMode.AUTO);
        boolean[] kills = {true, true, true, false, true};
        long at = 1_000L;
        for (boolean kill : kills)
        {
            session.addEncounter(kill, at += 1_000L);
        }
        assertEquals(1, session.pk().streak);
        assertEquals(3, session.pk().bestStreak);

        session.addEncounter(false, at + 1_000L);
        assertEquals(3, session.pk().bestStreak);
    }
}
