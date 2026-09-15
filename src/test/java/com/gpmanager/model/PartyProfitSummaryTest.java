package com.gpmanager.model;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PartyProfitSummaryTest
{
    @Test
    public void keepsPartyTotalsAndMemberRowsImmutable()
    {
        PartyProfitSummary.Member member = new PartyProfitSummary.Member(
            "Alice", 1_000L, 200L, 800L, 800L, 600L, 3, true);
        PartyProfitSummary summary = new PartyProfitSummary(
            true, 2, 1_000L, 200L, 800L, 800L, 600L, Arrays.asList(member));

        assertTrue(summary.isInParty());
        assertEquals(2, summary.getMemberCount());
        assertEquals(800L, summary.getNet());
        assertEquals(1, summary.getMembers().size());
        assertTrue(summary.getMembers().get(0).isLocal());
    }

    @Test
    public void distinguishesFreshReportsFromStaleAndMissingRates()
    {
        PartyProfitSummary.Member fresh = new PartyProfitSummary.Member(
            "Alice", 100L, 0L, 100L, 500L, 0L, 1, false, true, true, true, 10L);
        PartyProfitSummary.Member stale = new PartyProfitSummary.Member(
            "Bob", 200L, 0L, 200L, 600L, 0L, 1, false, true, false, true, 1L);
        PartyProfitSummary.Member noRate = new PartyProfitSummary.Member(
            "Cara", 0L, 0L, 0L, 0L, 0L, 0, false, true, true, false, 11L);
        PartyProfitSummary summary = new PartyProfitSummary(
            true, 3, 100L, 0L, 100L, 500L, 0L, Arrays.asList(fresh, stale, noRate));

        assertEquals(2, summary.getReportingCount());
        assertTrue(summary.hasPartialRate());
    }
}
