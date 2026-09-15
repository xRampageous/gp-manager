package com.gpmanager.engine.evidence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Test;

public class ChargeAccountingGateTest
{
    @After
    public void tearDown()
    {
        ChargeAccountingGate.clearAllCalibrated();
    }

    @Test
    public void chargeAccountingStaysDisabledUntilCalibrated()
    {
        assertFalse(ChargeAccountingGate.isEnabled());
        assertFalse(ChargeAccountingGate.isCalibrated(ChargeFamilyIds.FORESTRY_KIT));
    }

    @Test
    public void perFamilyCalibrationEnablesGate()
    {
        ChargeAccountingGate.markCalibrated(ChargeFamilyIds.FORESTRY_KIT);
        assertTrue(ChargeAccountingGate.isCalibrated(ChargeFamilyIds.FORESTRY_KIT));
        assertTrue(ChargeAccountingGate.isEnabled());
        assertFalse(ChargeAccountingGate.isCalibrated(ChargeFamilyIds.TRIDENT));
        String encoded = ChargeAccountingGate.encode(ChargeAccountingGate.snapshot());
        assertTrue(encoded.contains(ChargeFamilyIds.FORESTRY_KIT));
        ChargeAccountingGate.clearAllCalibrated();
        ChargeAccountingGate.restore(ChargeAccountingGate.decode(encoded));
        assertTrue(ChargeAccountingGate.isCalibrated(ChargeFamilyIds.FORESTRY_KIT));
    }

    @Test
    public void calibrationWarningNamesTheItem()
    {
        assertEquals(
            "Check forestry kit to calibrate before inferred container costs are counted.",
            ChargeAccountingGate.calibrationWarning("forestry kit"));
        assertTrue(ChargeAccountingGate.calibrationWarning(null).contains("calibrate"));
    }
}
