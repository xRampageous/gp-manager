package com.gpmanager.engine.evidence;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class MeasuredChargeCheckIntentTest
{
    @Test
    public void exactChatReadConsumesOnlyItsFreshMenuTargetOnce()
    {
        MeasuredChargeCheckIntent intent = new MeasuredChargeCheckIntent();
        assertTrue(intent.arm(MeasuredChargeRead.Variant.TRIDENT_SEAS, "inventory:3:11907", 100, 2));
        MeasuredChargeRead read = MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the seas has 1,234 charges.");

        assertEquals("inventory:3:11907", intent.consume(read, 101));
        assertNull(intent.consume(read, 101));
    }

    @Test
    public void wrongVariantMissingTargetAndExpiredTargetFailClosed()
    {
        MeasuredChargeCheckIntent intent = new MeasuredChargeCheckIntent();
        assertTrue(intent.arm(MeasuredChargeRead.Variant.TRIDENT_SEAS, "inventory:3", 100, 2));
        assertNull(intent.consume(MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the swamp has 1 charge."), 101));

        assertTrue(intent.arm(MeasuredChargeRead.Variant.TRIDENT_SEAS, "inventory:3", 100, 2));
        assertNull(intent.consume(MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the seas has 1 charge."), 103));

        assertFalse(intent.arm(MeasuredChargeRead.Variant.TRIDENT_SEAS, " ", 100, 2));
        assertNull(intent.consume(MeasuredChargeRead.parseCheckMessage(
            "Your Trident of the seas has 1 charge."), 101));
    }
}
