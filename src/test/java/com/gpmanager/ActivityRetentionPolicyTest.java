package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ActivityRetentionPolicyTest
{
    @Test
    public void zeroSecondsKeepsTheLastDetectedActivity()
    {
        assertEquals(-1, ActivityRetentionPolicy.resetTicks(0));
    }

    @Test
    public void resetSecondsRoundUpToWholeGameTicks()
    {
        assertEquals(2, ActivityRetentionPolicy.resetTicks(1));
        assertEquals(50, ActivityRetentionPolicy.resetTicks(30));
        assertEquals(200, ActivityRetentionPolicy.resetTicks(120));
    }
}
