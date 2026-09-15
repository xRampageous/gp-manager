package com.gpmanager.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RunReadModelSnapshotTest
{
    @Test
    public void historyDefensivelyCopiesAndProtectsItsStatements()
    {
        RunStatementSnapshot statement = statement("r1", "Run 1", 1_000L,
            100L, 20L, 80L, 288_000L, true);
        List<RunStatementSnapshot> source = new ArrayList<>(Arrays.asList(statement, null));
        RunHistorySnapshot history = new RunHistorySnapshot("s1", "Session", source,
            2, 30L, "PENDING");
        source.clear();

        assertEquals(1, history.getStatements().size());
        assertSame(statement, history.getStatements().get(0));
        assertEquals(2, history.getUnassignedReceiptCount());
        assertEquals(30L, history.getUnassignedReceiptValueGp());
        assertEquals("PENDING", history.getUnassignedReceiptStatus());
        try
        {
            history.getStatements().clear();
            fail("history statements must be immutable");
        }
        catch (UnsupportedOperationException expected)
        {
            // Expected immutable view.
        }
    }

    @Test
    public void statementAlwaysLeavesGroundLootUnavailable()
    {
        RunStatementSnapshot statement = statement("r1", "Run 1", 0L,
            0L, 0L, 0L, 0L, true);

        assertFalse(statement.isLeftOnGroundAvailable());
        assertNull(statement.getLeftOnGroundGp());
        assertFalse(statement.isRateAvailable());
    }

    @Test
    public void comparisonProvidesSignedRightMinusLeftDeltas()
    {
        RunStatementSnapshot left = statement("r1", "Earlier", 10_000L,
            1_000L, 400L, 600L, 216_000L, true);
        RunStatementSnapshot right = statement("r2", "Later", 20_000L,
            1_500L, 550L, 950L, 171_000L, true);
        RunComparisonSnapshot comparison = new RunComparisonSnapshot(left, right);

        assertTrue(comparison.isAvailable());
        assertEquals("AVAILABLE", comparison.getStatus());
        assertEquals(Long.valueOf(500L), comparison.getLootDeltaGp());
        assertEquals(Long.valueOf(150L), comparison.getSuppliesDeltaGp());
        assertEquals(Long.valueOf(350L), comparison.getNetDeltaGp());
        assertEquals(Long.valueOf(-45_000L), comparison.getGpPerHourDelta());
    }

    @Test
    public void costSplitComparisonIsIndependentAndLegacyConstructorsStayUnknown()
    {
        RunStatementSnapshot left = new RunStatementSnapshot("r1", "Earlier", false, "CLOSED",
            10_000L, 200L, 60L, 140L, 50_400L, true, "AVAILABLE", 2, true,
            20L, 40L, true);
        RunStatementSnapshot right = new RunStatementSnapshot("r2", "Later", false, "CLOSED",
            10_000L, 300L, 90L, 210L, 75_600L, true, "AVAILABLE", 2, true,
            50L, 40L, true);
        RunComparisonSnapshot comparison = new RunComparisonSnapshot(left, right);
        RunStatementSnapshot legacy = statement("legacy", "Legacy", 10_000L,
            0L, 60L, -60L, -21_600L, true);

        assertTrue(comparison.isAvailable());
        assertTrue(comparison.isCostSplitAvailable());
        assertEquals(Long.valueOf(30L), comparison.getConsumableDeltaGp());
        assertEquals(Long.valueOf(0L), comparison.getLossDeltaGp());
        assertEquals(60L, legacy.getSuppliesGp());
        assertFalse(legacy.isCostSplitAvailable());
    }

    @Test
    public void comparisonIsUnavailableWithoutAccountingOrAUsableRate()
    {
        RunStatementSnapshot available = statement("r1", "Earlier", 10_000L,
            100L, 50L, 50L, 18_000L, true);
        RunComparisonSnapshot noAccounting = new RunComparisonSnapshot(
            available, RunStatementSnapshot.unavailable("r2", "Unknown"));
        RunComparisonSnapshot noRate = new RunComparisonSnapshot(available,
            statement("r3", "Zero time", 0L, 100L, 50L, 50L, 0L, true));

        assertFalse(noAccounting.isAvailable());
        assertEquals("ACCOUNTING_UNAVAILABLE", noAccounting.getStatus());
        assertNull(noAccounting.getLootDeltaGp());
        assertNull(noAccounting.getGpPerHourDelta());
        assertFalse(noRate.isAvailable());
        assertEquals("RATE_UNAVAILABLE", noRate.getStatus());
        assertNull(noRate.getNetDeltaGp());
    }

    @Test
    public void comparisonFailsClosedOnSubtractionOverflow()
    {
        RunStatementSnapshot left = statement("r1", "Earlier", 1L,
            Long.MIN_VALUE, 0L, 0L, 0L, true);
        RunStatementSnapshot right = statement("r2", "Later", 1L,
            Long.MAX_VALUE, 0L, 0L, 0L, true);
        RunComparisonSnapshot comparison = new RunComparisonSnapshot(left, right);

        assertFalse(comparison.isAvailable());
        assertEquals("DELTA_OVERFLOW", comparison.getStatus());
        assertNull(comparison.getLootDeltaGp());
        assertNull(comparison.getSuppliesDeltaGp());
        assertNull(comparison.getNetDeltaGp());
        assertNull(comparison.getGpPerHourDelta());
    }

    @Test
    public void constructorsAreNullSafeAndNormalizeUnavailableMetadata()
    {
        RunStatementSnapshot statement = new RunStatementSnapshot(null, null, false,
            null, -1L, 1L, 2L, 3L, 4L, false, null, -2, false);
        RunHistorySnapshot history = new RunHistorySnapshot(null, null, null,
            -1, 4L, null);
        RunComparisonSnapshot comparison = new RunComparisonSnapshot(null, null);

        assertEquals("", statement.getRunId());
        assertEquals("", statement.getName());
        assertEquals("UNKNOWN", statement.getStatus());
        assertEquals("UNAVAILABLE", statement.getAccountingStatus());
        assertEquals(0L, statement.getActiveDurationMillis());
        assertEquals(0, statement.getReceiptCount());
        assertEquals("", history.getSessionId());
        assertEquals("", history.getSessionName());
        assertEquals(Collections.emptyList(), history.getStatements());
        assertEquals("UNAVAILABLE", history.getUnassignedReceiptStatus());
        assertFalse(comparison.isAvailable());
    }

    private static RunStatementSnapshot statement(
        String id, String name, long duration, long loot, long supplies,
        long net, long gpPerHour, boolean accountingAvailable)
    {
        return new RunStatementSnapshot(id, name, false, "CLOSED", duration,
            loot, supplies, net, gpPerHour, accountingAvailable,
            accountingAvailable ? "AVAILABLE" : "UNAVAILABLE", 3, true);
    }
}
