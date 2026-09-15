package com.gpmanager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GpDropAnimationMathTest
{
    @Test
    public void progressIsClamped()
    {
        assertEquals(0.0d, GpDropAnimationMath.progress(-100L, 400L), 0.0001d);
        assertEquals(0.5d, GpDropAnimationMath.progress(200L, 400L), 0.0001d);
        assertEquals(1.0d, GpDropAnimationMath.progress(900L, 400L), 0.0001d);
    }

    @Test
    public void easeOutMovesQuicklyWithoutOvershoot()
    {
        double eased = GpDropAnimationMath.ease(0.5d, GpDropEasing.EASE_OUT);
        assertTrue(eased > 0.5d);
        assertTrue(eased <= 1.0d);
    }

    @Test
    public void popReturnsToRestScale()
    {
        assertTrue(GpDropAnimationMath.popScale(0.0d) < 1.0d);
        assertTrue(GpDropAnimationMath.popScale(0.65d) > 1.0d);
        assertEquals(1.0d, GpDropAnimationMath.popScale(1.0d), 0.0001d);
    }
}
