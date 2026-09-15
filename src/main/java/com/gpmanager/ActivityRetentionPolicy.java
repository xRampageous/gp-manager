package com.gpmanager;

public final class ActivityRetentionPolicy
{
    private static final int GAME_TICK_MILLIS = 600;

    private ActivityRetentionPolicy()
    {
    }

    public static int resetTicks(int seconds)
    {
        if (seconds <= 0)
        {
            return -1;
        }
        return Math.max(1, (seconds * 1000 + GAME_TICK_MILLIS - 1) / GAME_TICK_MILLIS);
    }
}
