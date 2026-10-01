package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CharacterIdleModelTest
{
    @Test
    public void delayRequiredBeforeIdle()
    {
        CharacterIdleModel model = new CharacterIdleModel();
        model.delayMillis = 5_000L;
        assertFalse(model.tick(false, false, 1_000L));
        assertFalse(model.characterIdle);
        assertFalse(model.tick(false, false, 5_999L));
        assertFalse(model.characterIdle);
        assertTrue(model.tick(false, false, 6_000L));
        assertTrue(model.characterIdle);
        assertFalse(model.tick(false, false, 7_000L));
        assertTrue(model.characterIdle);
    }

    @Test
    public void animationOrInteractClearsIdle()
    {
        CharacterIdleModel model = new CharacterIdleModel();
        model.delayMillis = 2_000L;
        assertFalse(model.tick(false, false, 1_000L));
        assertTrue(model.tick(false, false, 3_000L));
        assertTrue(model.characterIdle);
        assertFalse(model.tick(true, false, 3_100L));
        assertFalse(model.characterIdle);
        assertFalse(model.tick(false, true, 4_000L));
        assertFalse(model.characterIdle);
    }

    @Test
    public void clearResetsState()
    {
        CharacterIdleModel model = new CharacterIdleModel();
        model.delayMillis = 2_000L;
        assertFalse(model.tick(false, false, 1_000L));
        assertTrue(model.tick(false, false, 3_000L));
        model.clear();
        assertFalse(model.characterIdle);
        assertFalse(model.tick(false, false, 3_500L));
    }

    @Test
    public void busyAndXpPreventIdle()
    {
        CharacterIdleModel model = new CharacterIdleModel();
        model.delayMillis = 2_000L;
        assertFalse(model.tick(true, false, 1_000L));
        assertFalse(model.characterIdle);
        assertFalse(model.tick(false, false, 3_000L));
        assertFalse(model.characterIdle);
        assertTrue(model.tick(false, false, 5_000L));
        assertTrue(model.characterIdle);

        model.clear();
        model.abn(10_000L);
        assertFalse(model.tick(false, false, 11_000L));
        assertFalse(model.characterIdle);
        // XP soft-busy lasts for the idle delay after the drop — Idle fires then,
        // without stacking a second full delay.
        assertFalse(model.tick(false, false, 11_999L));
        assertFalse(model.characterIdle);
        assertTrue(model.tick(false, false, 12_000L));
        assertTrue(model.characterIdle);
    }

    @Test
    public void hardBusyStillRequiresFullDelayAfterClear()
    {
        CharacterIdleModel model = new CharacterIdleModel();
        model.delayMillis = 2_000L;
        model.abn(10_000L);
        assertFalse(model.tick(true, false, 10_500L));
        assertFalse(model.tick(false, false, 12_000L));
        assertFalse(model.characterIdle);
        assertTrue(model.tick(false, false, 14_000L));
        assertTrue(model.characterIdle);
    }

}
