package com.gpmanager.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CharacterIdleModelTest
{
    @Test
    public void delayRequiredBeforeIdle()
    {
        CharacterIdleModel model = new CharacterIdleModel();
        model.setDelayMillis(5_000L);
        assertFalse(model.tick(false, false, 1_000L));
        assertFalse(model.isCharacterIdle());
        assertFalse(model.tick(false, false, 5_999L));
        assertFalse(model.isCharacterIdle());
        assertTrue(model.tick(false, false, 6_000L));
        assertTrue(model.isCharacterIdle());
        assertFalse(model.tick(false, false, 7_000L));
        assertTrue(model.isCharacterIdle());
    }

    @Test
    public void animationOrInteractClearsIdle()
    {
        CharacterIdleModel model = new CharacterIdleModel();
        model.setDelayMillis(2_000L);
        assertFalse(model.tick(false, false, 1_000L));
        assertTrue(model.tick(false, false, 3_000L));
        assertTrue(model.isCharacterIdle());
        assertFalse(model.tick(true, false, 3_100L));
        assertFalse(model.isCharacterIdle());
        assertFalse(model.tick(false, true, 4_000L));
        assertFalse(model.isCharacterIdle());
    }

    @Test
    public void clearResetsState()
    {
        CharacterIdleModel model = new CharacterIdleModel();
        model.setDelayMillis(2_000L);
        assertFalse(model.tick(false, false, 1_000L));
        assertTrue(model.tick(false, false, 3_000L));
        model.clear();
        assertFalse(model.isCharacterIdle());
        assertFalse(model.tick(false, false, 3_500L));
    }

    @Test
    public void objectStickyAndXpPreventIdle()
    {
        CharacterIdleModel model = new CharacterIdleModel();
        model.setDelayMillis(2_000L);
        assertFalse(model.tick(false, false, true, 1_000L));
        assertFalse(model.isCharacterIdle());
        assertFalse(model.tick(false, false, false, 3_000L));
        assertFalse(model.isCharacterIdle());
        assertTrue(model.tick(false, false, false, 5_000L));
        assertTrue(model.isCharacterIdle());

        model.clear();
        model.noteSkillingXp(10_000L);
        assertFalse(model.tick(false, false, false, 11_000L));
        assertFalse(model.isCharacterIdle());
        // XP soft-busy lasts for the idle delay after the drop — Idle fires then,
        // without stacking a second full delay.
        assertFalse(model.tick(false, false, false, 11_999L));
        assertFalse(model.isCharacterIdle());
        assertTrue(model.tick(false, false, false, 12_000L));
        assertTrue(model.isCharacterIdle());
    }

    @Test
    public void hardBusyStillRequiresFullDelayAfterClear()
    {
        CharacterIdleModel model = new CharacterIdleModel();
        model.setDelayMillis(2_000L);
        model.noteSkillingXp(10_000L);
        assertFalse(model.tick(true, false, false, 10_500L));
        assertFalse(model.tick(false, false, false, 12_000L));
        assertFalse(model.isCharacterIdle());
        assertTrue(model.tick(false, false, false, 14_000L));
        assertTrue(model.isCharacterIdle());
    }

    @Test
    public void bankUiOpenPreventsIdleAndClearsWhenClosed()
    {
        CharacterIdleModel model = new CharacterIdleModel();
        model.setDelayMillis(2_000L);
        assertFalse(model.tick(false, false, false, 1_000L));
        assertTrue(model.tick(false, false, false, 3_000L));
        assertTrue(model.isCharacterIdle());
        model.setBankUiOpen(true);
        assertFalse(model.isCharacterIdle());
        assertTrue(model.isBankUiOpen());
        assertFalse(model.tick(false, false, false, 10_000L));
        assertFalse(model.isCharacterIdle());
        model.setBankUiOpen(false);
        assertFalse(model.tick(false, false, false, 10_500L));
        assertTrue(model.tick(false, false, false, 12_500L));
        assertTrue(model.isCharacterIdle());
    }

    @Test
    public void clampDelay()
    {
        assertEquals(2_000L, CharacterIdleModel.clampDelay(500L));
        assertEquals(30_000L, CharacterIdleModel.clampDelay(60_000L));
        assertEquals(5_000L, CharacterIdleModel.clampDelay(5_000L));
    }
}
