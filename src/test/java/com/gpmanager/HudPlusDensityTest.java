package com.gpmanager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HudPlusDensityTest
{
    @Test
    public void glanceHidesTargetAndTray()
    {
        assertFalse(HudPlusDensity.GLANCE.showTarget());
        assertFalse(HudPlusDensity.GLANCE.showTray());
    }

    @Test
    public void standardShowsTargetOnly()
    {
        assertTrue(HudPlusDensity.STANDARD.showTarget());
        assertFalse(HudPlusDensity.STANDARD.showTray());
    }

    @Test
    public void tripShowsBoth()
    {
        assertTrue(HudPlusDensity.TRIP.showTarget());
        assertTrue(HudPlusDensity.TRIP.showTray());
    }
}
