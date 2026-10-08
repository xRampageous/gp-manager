package com.gpmanager;

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
        ChargeIntake intent = new ChargeIntake(null, null, ReviewFixesTest.CONFIG, ReviewFixesTest.engine(), null);
        assertTrue(intent.ck(Ar.V.TRIDENT_SEAS, "inventory:3:11907", 100, 2));
        Ar read = Ar.acs(
            "Your Trident of the seas has 1,234 charges.");

        assertEquals("inventory:3:11907", intent.ck(read, 101));
        assertNull(intent.ck(read, 101));
    }

    @Test
    public void wrongVariantMissingTargetAndExpiredTargetFailClosed()
    {
        ChargeIntake intent = new ChargeIntake(null, null, ReviewFixesTest.CONFIG, ReviewFixesTest.engine(), null);
        assertTrue(intent.ck(Ar.V.TRIDENT_SEAS, "inventory:3", 100, 2));
        assertNull(intent.ck(Ar.acs(
            "Your Trident of the swamp has 1 charge."), 101));

        assertTrue(intent.ck(Ar.V.TRIDENT_SEAS, "inventory:3", 100, 2));
        assertNull(intent.ck(Ar.acs(
            "Your Trident of the seas has 1 charge."), 103));

        assertFalse(intent.ck(Ar.V.TRIDENT_SEAS, " ", 100, 2));
        assertNull(intent.ck(Ar.acs(
            "Your Trident of the seas has 1 charge."), 101));
    }
}
