package com.gpmanager;

import org.junit.Test;
import static org.junit.Assert.*;

/** A save copies the whole profile: changed profiles save every 30 s, unchanged ones every 5 minutes. */
public class SaveCadenceTest
{
    @Test
    public void aChangedProfileSavesOnTheUsualInterval()
    {
        assertFalse(GpManagerPlugin.saveDue(GpManagerPlugin.SAVE_INTERVAL_TICKS - 1, 8L, 7L));
        assertTrue(GpManagerPlugin.saveDue(GpManagerPlugin.SAVE_INTERVAL_TICKS, 8L, 7L));
    }

    @Test
    public void anUnchangedProfileOnlyKeepsTheSlowHeartbeat()
    {
        assertFalse("nothing booked: no save at 30 s", GpManagerPlugin.saveDue(GpManagerPlugin.SAVE_INTERVAL_TICKS, 7L, 7L));
        assertFalse(GpManagerPlugin.saveDue(GpManagerPlugin.IDLE_SAVE_TICKS - 1, 7L, 7L));
        assertTrue("the crash-recovery heartbeat still saves", GpManagerPlugin.saveDue(GpManagerPlugin.IDLE_SAVE_TICKS, 7L, 7L));
    }
}
