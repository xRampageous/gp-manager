package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChangeFeedbackPolicyTest
{
    @Test
    public void hudPlusShowsTrayAndOptionalFloating()
    {
        assertTrue(ChangeFeedbackPolicy.showHudPlusTray(config(TrackingDisplay.HUD_PLUS, true)));
        assertTrue(ChangeFeedbackPolicy.showFloatingDrops(config(TrackingDisplay.HUD_PLUS, true)));
        assertFalse(ChangeFeedbackPolicy.showFloatingDrops(config(TrackingDisplay.HUD_PLUS, false)));
        assertTrue(ChangeFeedbackPolicy.animateHudPlusTray(config(TrackingDisplay.HUD_PLUS, true)));
    }

    @Test
    public void hudAndInfoboxAreFloatingOnly()
    {
        assertFalse(ChangeFeedbackPolicy.showHudPlusTray(config(TrackingDisplay.HUD, false)));
        assertTrue(ChangeFeedbackPolicy.showFloatingDrops(config(TrackingDisplay.HUD, false)));
        assertFalse(ChangeFeedbackPolicy.showHudPlusTray(config(TrackingDisplay.INFOBOX, false)));
        assertTrue(ChangeFeedbackPolicy.showFloatingDrops(config(TrackingDisplay.INFOBOX, false)));
        assertFalse(ChangeFeedbackPolicy.animateHudPlusTray(config(TrackingDisplay.HUD, true)));
    }

    @Test
    public void offHidesAllChangeFeedback()
    {
        assertFalse(ChangeFeedbackPolicy.showHudPlusTray(config(TrackingDisplay.OFF, true)));
        assertFalse(ChangeFeedbackPolicy.showFloatingDrops(config(TrackingDisplay.OFF, true)));
    }

    private static GpManagerConfig config(TrackingDisplay display, boolean hudPlusFloating)
    {
        return new GpManagerConfig()
        {
            @Override
            public TrackingDisplay trackingDisplay()
            {
                return display;
            }

            @Override
            public boolean hudPlusFloatingDrops()
            {
                return hudPlusFloating;
            }

            @Override
            public boolean reducedMotion()
            {
                return false;
            }
        };
    }
}
