package com.gpmanager;

import org.junit.Test;
import static org.junit.Assert.*;

/** The journal's receipts are superseded by a measured Check, one weapon at a time. */
public class ChargeEstimateJournalTest
{
    @Test
    public void aMeasuredCheckClearsItsWeaponsPendingEstimates()
    {
        ChargeEstimateJournal journal = new ChargeEstimateJournal();
        journal.record("s1", "target", "Crystal bow", "tx1", 23962, 200L);
        journal.record("s2", "target", "Tome of fire", "tx2", 20718, 20L);
        assertEquals(1, journal.receipts("s1", "target", 23962).size());

        journal.clearTarget("s1", "target");
        assertTrue("the checked weapon's estimate receipts are gone",
            journal.receipts("s1", "target", 23962).isEmpty());
        assertEquals("another session stays untouched", 1,
            journal.receipts("s2", "target", 20718).size());
    }
}
