package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PkStreakTest
{
    @Test
    public void bestStreakKeepsTheLongestRunAndCurrentResetsOnDeath()
    {
        Ad session = new Ad("PK", 0L, Cx.PK);
        Be[] order = {Be.KILL, Be.KILL, Be.KILL,
            Be.DEATH, Be.KILL};
        long at = 1_000L;
        for (Be type : order)
        {
            session.ke(type, at += 1_000L, "fight", Bd.CONFIRMED, "");
        }
        assertEquals(1, session.ava().currentStreak);
        assertEquals(3, session.mb());

        session.ke(Be.DEATH, at + 1_000L, "fight", Bd.CONFIRMED, "");
        assertEquals(3, session.mb());
    }
}
