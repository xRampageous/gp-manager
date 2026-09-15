package com.gpmanager.ui;

import com.gpmanager.HudPlusTextSize;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HudPlusTrayMotionTest
{
    @Test
    public void entryCompletesWithinEntryWindow()
    {
        long start = 1_000L;
        assertEquals(0f, HudPlusTrayMotion.entryProgress(start, start), 0.001f);
        assertTrue(HudPlusTrayMotion.entryProgress(start + 140L, start) > 0.4f);
        assertEquals(1f, HudPlusTrayMotion.entryProgress(start + HudPlusTrayMotion.ENTRY_MILLIS, start), 0.001f);
        assertEquals(1f, HudPlusTrayMotion.cassetteAlpha(
            start + HudPlusTrayMotion.ENTRY_MILLIS, start, start + 5_000L, false, false), 0.001f);
    }

    @Test
    public void activityHoldSkipsExitFade()
    {
        long start = 1_000L;
        long frozen = Long.MAX_VALUE / 4;
        assertEquals(0f, HudPlusTrayMotion.exitProgress(start + 10_000L, frozen, true), 0.001f);
        assertEquals(1f, HudPlusTrayMotion.cassetteAlpha(
            start + 10_000L, start, frozen, true, false), 0.001f);
    }

    @Test
    public void exitFadesInLastSliceOfFiniteDwell()
    {
        long expires = 5_000L;
        assertEquals(0f, HudPlusTrayMotion.exitProgress(expires - 1_000L, expires, false), 0.001f);
        assertTrue(HudPlusTrayMotion.exitProgress(expires - 160L, expires, false) > 0.4f);
        assertEquals(1f, HudPlusTrayMotion.exitProgress(expires, expires, false), 0.001f);
    }

    @Test
    public void reducedMotionSnapsOpaque()
    {
        assertEquals(1f, HudPlusTrayMotion.cassetteAlpha(1_000L, 1_000L, 5_000L, false, true), 0.001f);
        assertEquals(0, HudPlusTrayMotion.cassetteSlidePx(1_000L, 1_000L, true));
    }

    @Test
    public void iconSlotsScaleWithTextSize()
    {
        assertEquals(20, HudPlusLayout.iconSlot(HudPlusTextSize.SMALL));
        assertEquals(24, HudPlusLayout.iconSlot(HudPlusTextSize.NORMAL));
        assertEquals(28, HudPlusLayout.iconSlot(HudPlusTextSize.LARGE));
        // Discrete faces — do not stretch SmallFont with deriveFont.
        assertTrue(HudPlusLayout.resolveFont(HudPlusTextSize.SMALL)
            == FontManager.getRunescapeSmallFont()
            || HudPlusLayout.resolveFont(HudPlusTextSize.SMALL).equals(FontManager.getRunescapeSmallFont()));
        assertTrue(HudPlusLayout.resolveFont(HudPlusTextSize.NORMAL)
            == FontManager.getRunescapeFont()
            || HudPlusLayout.resolveFont(HudPlusTextSize.NORMAL).equals(FontManager.getRunescapeFont()));
        assertTrue(HudPlusLayout.resolveFont(HudPlusTextSize.LARGE)
            == FontManager.getRunescapeBoldFont()
            || HudPlusLayout.resolveFont(HudPlusTextSize.LARGE).equals(FontManager.getRunescapeBoldFont()));
        assertTrue(HudPlusLayout.iconSlot(HudPlusTextSize.LARGE)
            > HudPlusLayout.iconSlot(HudPlusTextSize.SMALL));
    }
}
