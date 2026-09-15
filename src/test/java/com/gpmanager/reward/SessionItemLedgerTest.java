package com.gpmanager.reward;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.LootPresentationFilter;
import com.gpmanager.grounditems.GroundItemsConfigSnapshot;
import com.gpmanager.grounditems.LootPresentationFilterService;
import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionItemLedgerTest
{
    @Test
    public void recordsGainsAndLossesSeparately()
    {
        SessionItemLedger ledger = new SessionItemLedger();
        ledger.record(new ProfitTransaction(
            1_000L,
            null,
            TransactionType.PROCESSING,
            TrackingContext.PRODUCTION,
            "",
            "Crafting",
            true,
            Arrays.asList(
                new ItemFlow(1745, "Green dragonhide", -1L, 1500, -1500L),
                new ItemFlow(1099, "Green d'hide chaps", 1L, 2000, 2000L))));
        SessionItemLedger.SessionLedgerSnapshot snap = ledger.snapshot(0L, 5, 5);
        assertFalse(snap.isEmpty());
        assertEquals(1, snap.getGains().size());
        assertEquals(1, snap.getLosses().size());
        assertEquals(500L, snap.getNet());
    }

    @Test
    public void skipsTransfers()
    {
        SessionItemLedger ledger = new SessionItemLedger();
        ledger.record(new ProfitTransaction(
            1_000L,
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            "Bank",
            "Bank",
            false,
            Collections.singletonList(new ItemFlow(1511, "Logs", -20L, 100, -2000L))));
        assertTrue(ledger.snapshot(0L, 5, 5).isEmpty());
    }

    @Test
    public void respectsGainMinimumAndCountsHidden()
    {
        SessionItemLedger ledger = new SessionItemLedger();
        ledger.record(new ProfitTransaction(
            1_000L,
            null,
            TransactionType.GAIN,
            TrackingContext.GENERIC,
            "",
            "Woodcutting",
            true,
            Arrays.asList(
                new ItemFlow(1511, "Oak logs", 10L, 100, 1000L),
                new ItemFlow(1519, "Logs", 1L, 40, 40L))));
        SessionItemLedger.SessionLedgerSnapshot snap = ledger.snapshot(100L, 5, 5);
        assertEquals(1, snap.getGains().size());
        assertEquals(1, snap.getHiddenCount());
    }

    @Test
    public void displayFilterHidesGainsButKeepsCostsUntilAccountingExcludesThem()
    {
        SessionItemLedger ledger = new SessionItemLedger();
        ledger.record(new ProfitTransaction(
            1_000L,
            TransactionType.PROCESSING,
            TrackingContext.GENERIC,
            "Gathering",
            true,
            Arrays.asList(
                new ItemFlow(526, "Bones", 1L, 35, 35L),
                new ItemFlow(1513, "Magic logs", 1L, 1_000, 1_000L),
                new ItemFlow(385, "Shark", -1L, 800, -800L))));
        LootPresentationFilterService filter = new LootPresentationFilterService(
            new GroundItemsConfigSnapshot(true, "Magic logs", "Bones, Shark", false,
                true, 0, GroundItemsConfigSnapshot.ValueMode.HIGHEST,
                Color.MAGENTA, Color.WHITE, Color.GRAY, Collections.emptyList()));

        SessionItemLedger.SessionLedgerSnapshot displayOnly = ledger.snapshot(
            0L, 5, 5, filter, LootPresentationFilter.FOLLOW_GROUND_ITEMS,
            LootPresentationFilter.ALL_ITEMS);
        assertEquals(1, displayOnly.getGains().size());
        assertEquals("Magic logs", displayOnly.getGains().get(0).getItemName());
        assertEquals(1, displayOnly.getLosses().size());
        assertEquals("Shark", displayOnly.getLosses().get(0).getItemName());
        assertEquals(1_035L, displayOnly.getGainTotal());
        assertEquals(800L, displayOnly.getLossTotal());

        SessionItemLedger.SessionLedgerSnapshot accountingExcluded = ledger.snapshot(
            0L, 5, 5, filter, LootPresentationFilter.FOLLOW_GROUND_ITEMS,
            LootPresentationFilter.HIGHLIGHTED_LIST_ONLY);
        assertEquals(1, accountingExcluded.getGains().size());
        assertTrue(accountingExcluded.getLosses().isEmpty());
        assertEquals(1_000L, accountingExcluded.getGainTotal());
        assertEquals(0L, accountingExcluded.getLossTotal());
    }

    @Test
    public void bindToSessionRebuildsAndTitlesCapsule()
    {
        ProfitSession general = new ProfitSession("General", 1_000L);
        general.addTransaction(new ProfitTransaction(
            1_100L,
            null,
            TransactionType.GAIN,
            TrackingContext.GENERIC,
            "",
            "Woodcutting",
            true,
            Collections.singletonList(new ItemFlow(1519, "Willow logs", 2L, 22, 44L))), 0);

        SessionItemLedger ledger = new SessionItemLedger();
        ledger.record(new ProfitTransaction(
            900L,
            null,
            TransactionType.GAIN,
            TrackingContext.GENERIC,
            "",
            "Mining",
            true,
            Collections.singletonList(new ItemFlow(440, "Iron ore", 1L, 50, 50L))));
        ledger.bindToSession(general);

        SessionItemLedger.SessionLedgerSnapshot snap = ledger.snapshot(0L, 5, 5);
        assertEquals("Overall", snap.getSessionName());
        assertEquals(1, snap.getGains().size());
        assertEquals("Willow logs", snap.getGains().get(0).getItemName());
        assertEquals(44L, snap.getGains().get(0).getRecordedValue());
    }

    @Test
    public void renameBoundSessionUpdatesTitleOnly()
    {
        ProfitSession custom = new ProfitSession("Vorkath", 1_000L);
        SessionItemLedger ledger = new SessionItemLedger();
        ledger.bindToSession(custom);
        ledger.renameBoundSession("Vorkath trip");
        assertEquals("Current · Vorkath trip", ledger.snapshot(0L, 5, 5).getSessionName());
        ledger.renameBoundSession("Overall");
        assertEquals("Overall", ledger.snapshot(0L, 5, 5).getSessionName());
    }
}
