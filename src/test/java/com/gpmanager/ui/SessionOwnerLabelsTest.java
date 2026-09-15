package com.gpmanager.ui;

import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.SessionMode;
import com.gpmanager.reward.SessionItemLedger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionOwnerLabelsTest
{
    @Test
    public void durableNamesAcceptLegacyAndOverall()
    {
        assertTrue(SessionOwnerLabels.isDurableOwnerName("Overall"));
        assertTrue(SessionOwnerLabels.isDurableOwnerName("General"));
        assertTrue(SessionOwnerLabels.isDurableOwnerName(""));
        assertFalse(SessionOwnerLabels.isDurableOwnerName("Boss trip"));
    }

    @Test
    public void folioTitlesOverallVsCurrent()
    {
        assertEquals("Overall", SessionOwnerLabels.folioTitle("Overall", false));
        assertEquals("Overall", SessionOwnerLabels.folioTitle("General", false));
        assertEquals("Current · Boss trip", SessionOwnerLabels.folioTitle("Boss trip", true));
        assertEquals("No items in Overall yet", SessionOwnerLabels.emptyFolioTip("Overall"));
    }

    @Test
    public void sessionCapsuleUsesFolioVocabulary()
    {
        SessionItemLedger ledger = new SessionItemLedger();
        ProfitSession overall = new ProfitSession("Overall", 1_000L, SessionMode.AUTO);
        ledger.bindToSession(overall);
        assertEquals("Overall", ledger.snapshot(0L, 3, 3).getSessionName());

        ProfitSession custom = new ProfitSession("Boss trip", 2_000L, SessionMode.AUTO);
        ledger.bindToSession(custom);
        assertEquals("Current · Boss trip", ledger.snapshot(0L, 3, 3).getSessionName());
    }
}
