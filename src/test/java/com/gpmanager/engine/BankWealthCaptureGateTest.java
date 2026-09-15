package com.gpmanager.engine;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BankWealthCaptureGateTest
{
    @Test
    public void requiresFreshBankEventThenStableContentAndCapturesAtMostOncePerVisit()
    {
        BankWealthCaptureGate gate = new BankWealthCaptureGate();
        assertFalse(gate.observe("same"));
        gate.beginVisit();
        assertFalse(gate.observe("stale"));
        assertFalse(gate.observe("stale"));
        assertFalse(gate.isCaptured());

        gate.markBankContentsChanged();
        assertFalse(gate.observe("partial"));
        gate.markBankContentsChanged();
        assertFalse(gate.observe("loaded"));
        assertTrue(gate.observe("loaded"));
        assertTrue(gate.isCaptured());
        assertFalse(gate.observe("loaded"));
        assertFalse(gate.observe("changed"));

        gate.endVisit();
        gate.beginVisit();
        assertFalse(gate.observe("loaded"));
        gate.markBankContentsChanged();
        assertFalse(gate.observe("loaded"));
        assertTrue(gate.observe("loaded"));
    }
}
