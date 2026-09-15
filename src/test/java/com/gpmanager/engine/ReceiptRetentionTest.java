package com.gpmanager.engine;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.ReceiptRetentionPeriod;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.PartySummary;
import com.gpmanager.model.PkEncounter;
import com.gpmanager.model.PkEncounterType;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.ProfileSizeEstimate;
import com.gpmanager.model.ReceiptRetentionStatus;
import com.gpmanager.model.Run;
import com.gpmanager.model.RunComparisonSnapshot;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.persistence.SavedState;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ReceiptRetentionTest
{
    private static final long DAY = 86_400_000L;

    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int maxHistorySessions()
        {
            return 2_000;
        }

        @Override
        public ReceiptRetentionPeriod receiptRetentionDays()
        {
            return ReceiptRetentionPeriod.DAYS_90;
        }
    };

    @Test
    public void compactsStrictlyOlderClosedSessionsAndLeavesActiveAndBoundaryRows()
    {
        long now = 100L * DAY + 12L * 60L * 60L * 1_000L;
        long cutoff = now - 30L * DAY;
        ProfitSession boundary = closedWithReceipt("Boundary", cutoff, 100L);
        ProfitSession old = closedWithReceipt("Old", cutoff - 1L, 200L);
        ProfitSession active = new ProfitSession("Active", cutoff - 2L * DAY);
        active.addTransaction(receipt(cutoff - DAY, 300L), 10);

        GpManagerEngine engine = engine();
        engine.restore(new SavedState(null, Arrays.asList(boundary, old, old, active)), now);
        ReceiptRetentionStatus status = engine.getRetentionStatus(30, now);

        assertEquals(1, status.getPendingCount());
        assertEquals(Integer.valueOf(30), Integer.valueOf(status.getWindowDays()));
        assertNotNull(status.getNextCompactionAtEpochMillis());
        assertEquals(1, engine.compactOlderThan(30, now));
        assertEquals(1, boundary.getTransactions().size());
        assertTrue(old.getTransactions().isEmpty());
        assertEquals(1L, old.getCompactedTransactionCount());
        assertEquals(200L, old.metrics(now, 60_000L).getRevenue());
        assertEquals(1, active.getTransactions().size());
        assertEquals(0, engine.compactOlderThan(30, now));
        assertEquals(0, engine.compactOlderThan(0, now));
    }

    @Test
    public void compactionKeepsFavoriteMetadataEncountersAndRunComparisons()
    {
        long now = 400L * DAY;
        ProfitSession session = new ProfitSession("Favourite trip", now - 200L * DAY, SessionMode.PK);
        session.setTags("boss, pet");
        session.setNotes("keep this note");
        session.setFavorite(true);
        session.setExcludedFromAverages(true);
        session.setPartySummary(new PartySummary(500L, 60_000L,
            Collections.singletonList(new PartySummary.Member("Teammate", 200L, 12L,
                true, true, false, false))));

        session.addTransaction(receipt(now - 200L * DAY + 1_000L, 100L), 10);
        String firstRunId = session.getCurrentRunId(now - 200L * DAY + 2_000L);
        Run secondRun = session.startRun("Run 2", now - 200L * DAY + 60_000L);
        session.addTransaction(receipt(now - 200L * DAY + 61_000L, 250L), 10);
        PkEncounter encounter = session.addPkEncounter(PkEncounterType.KILL, now - 200L * DAY + 62_000L,
            "Player kill", null, "observed encounter");
        session.attachTransactionToEncounter(session.getTransactions().get(1).getId(), encounter.getId(), false);
        session.close(now - 200L * DAY + 120_000L);

        RunComparisonSnapshot before = session.compareRuns(firstRunId, secondRun.getId(), now);
        assertTrue(before.isAvailable());
        GpManagerEngine engine = engine();
        engine.restore(new SavedState(null, Collections.singletonList(session)), now);

        assertEquals("current-schema restore already compacted the overdue rows", 0,
            engine.compactOlderThan(90, now));

        assertTrue(session.getTransactions().isEmpty());
        assertEquals("boss, pet", session.getTagsDisplay());
        assertEquals("keep this note", session.getNotes());
        assertTrue(session.isFavorite());
        assertTrue(session.isExcludedFromAverages());
        assertEquals(500L, session.getPartySummary().getCombinedNetGp());
        assertEquals(1, session.getPkEncounters().size());
        assertEquals("Player kill", session.getPkEncounters().get(0).getLabel());
        RunComparisonSnapshot after = session.compareRuns(firstRunId, secondRun.getId(), now);
        assertTrue(after.isAvailable());
        assertEquals(before.getNetDeltaGp(), after.getNetDeltaGp());
        assertEquals(before.getLootDeltaGp(), after.getLootDeltaGp());
        assertEquals(before.getGpPerHourDelta(), after.getGpPerHourDelta());
    }

    @Test
    public void oldSchemaWaitsForUtcDayChangeAndPersistsTheDeferralMarker()
    {
        long savedAt = 70L * DAY + 23L * 60L * 60L * 1_000L;
        long sameUtcDay = 80L * DAY + 23L * 30L * 60L * 1_000L;
        long nextUtcDay = 81L * DAY + 1L;
        ProfitSession old = closedWithReceipt("Old profile", 1L, 125L);
        SavedState legacy = new SavedState(null, Collections.singletonList(old));
        legacy.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION - 1);
        legacy.setSavedAtEpochMillis(savedAt);

        GpManagerEngine firstLoad = engine();
        firstLoad.restore(legacy, sameUtcDay);
        assertEquals("legacy details are retained during the restore deferral", 1, old.getTransactions().size());
        assertEquals(0, firstLoad.maintainReceiptRetention(30, sameUtcDay));
        assertEquals(1, old.getTransactions().size());

        SavedState autosaved = new Gson().fromJson(new Gson().toJson(firstLoad.createSavedState()), SavedState.class);
        assertTrue(autosaved.isReceiptRetentionDeferredUntilDayChange());
        assertEquals(LocalDate.ofEpochDay(80L).toString(), autosaved.getLastReceiptRetentionDayUtc());

        GpManagerEngine afterRestart = engine();
        afterRestart.restore(autosaved, sameUtcDay);
        assertEquals("the persisted legacy deferral survives another same-day restore", 1,
            afterRestart.getHistory().get(0).getTransactions().size());
        assertEquals(1, afterRestart.maintainReceiptRetention(30, nextUtcDay));
        assertTrue(afterRestart.getHistory().get(0).getTransactions().isEmpty());
        assertFalse(afterRestart.createSavedState().isReceiptRetentionDeferredUntilDayChange());
    }

    @Test
    public void currentSchemaRestoreCompactsOverdueRowsWithoutWaitingForUtcRollover()
    {
        long now = 120L * DAY + 12L * 60L * 60L * 1_000L;
        ProfitSession overdue = closedWithReceipt("Overdue", now - 120L * DAY - 1L, 750L);
        SavedState current = new SavedState(null, Collections.singletonList(overdue));
        current.setLastReceiptRetentionDayUtc(LocalDate.ofEpochDay(120L).toString());

        GpManagerEngine engine = engine();
        engine.restore(current, now);

        assertTrue(overdue.getTransactions().isEmpty());
        assertEquals(1L, engine.getProfileSizeEstimate().getCompactedReceiptCount());
        assertEquals(0L, engine.getProfileSizeEstimate().getReceiptCount());
        assertEquals(LocalDate.ofEpochDay(120L).toString(),
            engine.createSavedState().getLastReceiptRetentionDayUtc());
    }

    @Test
    public void profileSizeEstimateGrowsWithSessionsAndReceipts()
    {
        GpManagerEngine engine = engine();
        engine.restore(new SavedState());
        ProfileSizeEstimate empty = engine.getProfileSizeEstimate();

        ProfitSession session = new ProfitSession("Growing", 1L);
        SavedState withSession = new SavedState(session, Collections.emptyList());
        engine.restore(withSession, 3L);
        ProfileSizeEstimate oneReceipt = engine.getProfileSizeEstimate();
        session.addTransaction(receipt(2L, 99L), 10);
        ProfileSizeEstimate twoReceipts = engine.getProfileSizeEstimate();

        assertEquals(0L, empty.getSessionCount());
        assertEquals(1L, oneReceipt.getSessionCount());
        assertEquals(0L, oneReceipt.getReceiptCount());
        assertEquals(1L, twoReceipts.getReceiptCount());
        assertTrue(oneReceipt.getApproximateJsonBytes() > empty.getApproximateJsonBytes());
        assertTrue(twoReceipts.getApproximateJsonBytes() > oneReceipt.getApproximateJsonBytes());
        assertEquals(0L, twoReceipts.getCompactedReceiptCount());
    }

    private static ProfitSession closedWithReceipt(String name, long endedAt, long value)
    {
        long start = Math.max(0L, endedAt - 10_000L);
        ProfitSession session = new ProfitSession(name, start);
        session.addTransaction(receipt(start + 1_000L, value), 10);
        session.close(endedAt);
        return session;
    }

    private static ProfitTransaction receipt(long timestamp, long value)
    {
        return new ProfitTransaction(timestamp, TransactionType.LOOT, TrackingContext.LOOT,
            "receipt", true, Collections.singletonList(new ItemFlow(995, "Coins",
                value < 0L ? -1L : 1L, (int) Math.min(Integer.MAX_VALUE, Math.abs(value)), value)));
    }

    private static GpManagerEngine engine()
    {
        return new GpManagerEngine(deltas -> Collections.emptyList(), new TransactionClassifier(), CONFIG);
    }
}
