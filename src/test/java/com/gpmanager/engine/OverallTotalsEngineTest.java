package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.DailyRollup;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.OverallTotalsSnapshot;
import com.gpmanager.model.PauseReason;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.SessionOwnerKind;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.persistence.SavedState;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class OverallTotalsEngineTest
{
    @Test
    public void rollupWinsOverOverlappingDaySummariesAndPausedFreePlayStillCounts()
    {
        long started = Instant.parse("2026-09-14T10:00:00Z").toEpochMilli();
        long freePlayPause = started + 2L * 60L * 60L * 1_000L;
        long customStart = freePlayPause;
        long now = customStart + 60L * 60L * 1_000L;
        ProfitSession freePlay = session("Overall", started, SessionOwnerKind.FREE_PLAY, 100L);
        freePlay.pause(freePlayPause, PauseReason.CUSTOM_SESSION);
        ProfitSession custom = session("Vorkath", customStart, SessionOwnerKind.NAMED_SESSION, 25L);
        custom.pause(now, PauseReason.MANUAL);
        DailyRollup persisted = DailyRollup.builder(LocalDate.parse("2026-09-14"), ZoneId.of("UTC"))
            .addSourceSessionId(freePlay.getId())
            .addSourceSessionId(custom.getId())
            .addAccounting(125L, 0L)
            .addActiveMillis(3L * 60L * 60L * 1_000L)
            .addEventCounts(0, 0, 2, 0)
            .addNamedSessionStarts(1)
            .coverage(DailyRollup.Dimension.NAMED_SESSION_STARTS, DailyRollup.Coverage.COMPLETE)
            .build();
        SavedState saved = new SavedState(freePlay, custom, true, Collections.emptyList());
        saved.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        saved.setSavedAtEpochMillis(now);
        saved.setProfileTimeZoneId("UTC");
        saved.setDailyRollups(Collections.singletonList(persisted));

        GpManagerEngine engine = engine();
        engine.restore(saved, now);
        OverallTotalsSnapshot totals = engine.getOverallTotals(now);

        assertEquals(125L, totals.getNetGp());
        assertEquals(3L * 60L * 60L * 1_000L, totals.getActiveMillis());
        assertEquals(1, totals.getNamedSessionsStarted());
        assertEquals(1, totals.getDaysTracked());
        assertEquals(LocalDate.parse("2026-09-14"), totals.getFirstTrackedDate());
        assertEquals("UTC", totals.getFirstTrackedZoneId());
        assertEquals(DailyRollup.Coverage.COMPLETE, totals.getNamedSessionStartsCoverage());
        assertTrue(engine.getGeneralSession().isPaused());
        assertFalse(engine.getActiveSession().getOwnerKind() == SessionOwnerKind.FREE_PLAY);
    }

    @Test
    public void todayUsesProfileLocalDateAndRefreshesAcrossMidnight()
    {
        long start = Instant.parse("2026-09-14T13:59:00Z").toEpochMilli();
        long beforeMidnight = Instant.parse("2026-09-14T13:59:30Z").toEpochMilli();
        long afterMidnight = Instant.parse("2026-09-14T14:00:30Z").toEpochMilli();
        SavedState profile = new SavedState();
        profile.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        profile.setProfileTimeZoneId("Australia/Sydney");
        profile.setSavedAtEpochMillis(start);
        GpManagerEngine engine = engine();
        engine.restore(profile, start);
        engine.ensureSession(start);

        engine.processIfDirty(ContainerSnapshot.empty(), beforeMidnight);
        OverallTotalsSnapshot yesterday = engine.getOverallToday(beforeMidnight);
        engine.processIfDirty(ContainerSnapshot.empty(), afterMidnight);
        OverallTotalsSnapshot today = engine.getOverallToday(afterMidnight);
        OverallTotalsSnapshot all = engine.getOverallTotals(afterMidnight);

        assertEquals(LocalDate.parse("2026-09-14"), yesterday.getRequestedDate());
        assertEquals(30_000L, yesterday.getActiveMillis());
        assertEquals(LocalDate.parse("2026-09-15"), today.getRequestedDate());
        assertEquals(30_000L, today.getActiveMillis());
        assertEquals(90_000L, all.getActiveMillis());
        assertEquals(LocalDate.parse("2026-09-15"), today.getFirstTrackedDate());
    }

    @Test
    public void allTimeReadCanMoveBackBeforePreviouslyPromotedFutureDay()
    {
        long before = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        long after = Instant.parse("2026-09-15T12:00:00Z").toEpochMilli();
        DailyRollup future = DailyRollup.builder(LocalDate.parse("2026-09-15"), ZoneId.of("UTC"))
            .addAccounting(250L, 0L)
            .addActiveMillis(5_000L)
            .addNamedSessionStarts(1)
            .coverage(DailyRollup.Dimension.NAMED_SESSION_STARTS, DailyRollup.Coverage.COMPLETE)
            .build();
        SavedState state = profile("UTC", before);
        state.setDailyRollups(Collections.singletonList(future));
        GpManagerEngine engine = engine();
        engine.restore(state, before);

        OverallTotalsSnapshot first = engine.getOverallTotals(before);
        OverallTotalsSnapshot promoted = engine.getOverallTotals(after);
        OverallTotalsSnapshot movedBack = engine.getOverallTotals(before);

        assertEquals(0L, first.getNetGp());
        assertEquals(250L, promoted.getNetGp());
        assertEquals(1, promoted.getDaysTracked());
        assertEquals(0L, movedBack.getNetGp());
        assertEquals(0, movedBack.getDaysTracked());
    }

    @Test
    public void liveDayEvictionInvalidatesCachedAccountingRows() throws Exception
    {
        long start = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
        long dayMillis = 86_400_000L;
        GpManagerEngine engine = engine();
        engine.restore(profile("UTC", start), start);
        engine.ensureSession(start);
        ProfitSession session = engine.getGeneralSession();
        session.addTransaction(receipt(start + 1_000L, 100L), 100);

        long beforeAt = start + 399L * dayMillis + 1_000L;
        engine.processIfDirty(ContainerSnapshot.empty(), beforeAt);
        OverallTotalsSnapshot beforeEviction = engine.getOverallTotals(beforeAt);
        assertEquals(100L, beforeEviction.getNetGp());
        long generation = ((Number) privateField(engine, "overallCacheGeneration")).longValue();

        long afterAt = start + 400L * dayMillis + 1_000L;
        engine.processIfDirty(ContainerSnapshot.empty(), afterAt);
        OverallTotalsSnapshot afterEviction = engine.getOverallTotals(afterAt);
        long afterGeneration = ((Number) privateField(engine, "overallCacheGeneration")).longValue();

        assertTrue(afterGeneration > generation);
        assertEquals(0L, afterEviction.getNetGp());
    }

    @Test
    public void liveClockUpdatesOnlyTouchedDayWithoutRebuildingOtherRollups() throws Exception
    {
        long start = Instant.parse("2026-09-15T10:00:00Z").toEpochMilli();
        DailyRollup priorDay = DailyRollup.builder(LocalDate.parse("2026-09-14"), ZoneId.of("UTC"))
            .addAccounting(900L, 0L)
            .addActiveMillis(5_000L)
            .addNamedSessionStarts(0)
            .coverage(DailyRollup.Dimension.NAMED_SESSION_STARTS, DailyRollup.Coverage.COMPLETE)
            .build();
        SavedState state = profile("UTC", start);
        state.setDailyRollups(Collections.singletonList(priorDay));
        GpManagerEngine engine = engine();
        engine.restore(state, start);
        engine.ensureSession(start);

        engine.processIfDirty(ContainerSnapshot.empty(), start + 1_000L);
        OverallTotalsSnapshot first = engine.getOverallTotals(start + 1_000L);
        @SuppressWarnings("unchecked")
        java.util.List<DailyRollup> rollupsBefore =
            (java.util.List<DailyRollup>) privateField(engine, "dailyRollups");
        long generationBefore = ((Number) privateField(engine, "overallCacheGeneration")).longValue();
        engine.processIfDirty(ContainerSnapshot.empty(), start + 11_000L);
        OverallTotalsSnapshot next = engine.getOverallTotals(start + 11_000L);
        @SuppressWarnings("unchecked")
        java.util.List<DailyRollup> rollupsAfter =
            (java.util.List<DailyRollup>) privateField(engine, "dailyRollups");
        long generationAfter = ((Number) privateField(engine, "overallCacheGeneration")).longValue();

        assertEquals(6_000L, first.getActiveMillis());
        assertEquals(16_000L, next.getActiveMillis());
        assertEquals(900L, next.getNetGp());
        assertSame(rollupsBefore, rollupsAfter);
        assertEquals(generationBefore, generationAfter);
    }

    @Test
    public void legacyHistoryOwnerRemainsUnknownAndNamedStartCountUnavailable()
    {
        long start = Instant.parse("2026-09-14T10:00:00Z").toEpochMilli();
        ProfitSession legacy = session("Vorkath", start, SessionOwnerKind.UNKNOWN, 100L);
        legacy.rename("Overall");
        legacy.close(start + 60L * 60L * 1_000L);
        SavedState oldState = new SavedState(null, null, false,
            Collections.singletonList(legacy));
        oldState.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION - 1);
        oldState.setSavedAtEpochMillis(start + 60L * 60L * 1_000L);
        oldState.setProfileTimeZoneId("UTC");

        GpManagerEngine engine = engine();
        engine.restore(oldState, oldState.getSavedAtEpochMillis());
        OverallTotalsSnapshot totals = engine.getOverallTotals(oldState.getSavedAtEpochMillis());

        assertEquals(100L, totals.getNetGp());
        assertEquals(SessionOwnerKind.UNKNOWN, engine.getHistory().get(0).getOwnerKind());
        assertEquals(DailyRollup.Coverage.UNAVAILABLE, totals.getNamedSessionStartsCoverage());
        assertFalse(totals.areNamedSessionStartsAvailable());
    }

    @Test
    public void derivedDayCacheTracksCorrectionUndoAndHistoryDeletionWithoutReceipts()
    {
        long start = Instant.parse("2026-09-14T10:00:00Z").toEpochMilli();
        GpManagerEngine engine = engine();
        engine.restore(profile("UTC", start), start);
        engine.ensureSession(start);
        ProfitSession freePlay = engine.getGeneralSession();
        ProfitTransaction first = receipt(start + 1L, 100L);
        freePlay.addTransaction(first, 1);
        // Force receipt compaction; daily totals remain summary-backed.
        freePlay.addTransaction(receipt(start + 2L, 10L), 1);
        OverallTotalsSnapshot settled = engine.getOverallTotals(start + 10L);
        assertEquals(110L, settled.getNetGp());
        assertTrue(freePlay.getCompactedTransactionCount() > 0L);

        ProfitTransaction retained = freePlay.getTransactions().get(0);
        assertTrue(engine.correctTransaction(retained.getId(), TransactionCorrection.IGNORE,
            start + 20L));
        assertEquals(100L, engine.getOverallTotals(start + 30L).getNetGp());
        assertTrue(engine.undoLastCorrection(start + 40L));
        assertEquals(110L, engine.getOverallTotals(start + 50L).getNetGp());
        assertNotNull(engine.undoLastTransaction(start + 60L));
        assertEquals(100L, engine.getOverallTotals(start + 70L).getNetGp());
        assertNotNull(engine.restoreLastUndo(start + 80L));
        assertEquals(110L, engine.getOverallTotals(start + 90L).getNetGp());

        engine.startCustomSession("Raid", SessionMode.AUTO, start + 100L);
        ProfitSession custom = engine.getActiveSession();
        custom.addTransaction(receipt(start + 101L, 50L), 100);
        assertTrue(engine.finishCustomSession(start + 200L));
        assertEquals(160L, engine.getOverallTotals(start + 300L).getNetGp());
        assertTrue(engine.deleteHistorySession(custom.getId()));
        assertEquals(110L, engine.getOverallTotals(start + 400L).getNetGp());
        assertNull(engine.getHistory().stream().filter(s -> custom.getId().equals(s.getId()))
            .findFirst().orElse(null));
    }

    private static ProfitSession session(String name, long started, SessionOwnerKind owner, long net)
    {
        ProfitSession session = new ProfitSession(name, started, SessionMode.AUTO);
        session.setOwnerKind(owner);
        session.configureAnalyticsTimeZone("UTC", false);
        session.addTransaction(receipt(started + 1L, net), 100);
        return session;
    }

    private static ProfitTransaction receipt(long timestamp, long value)
    {
        return new ProfitTransaction(timestamp, TransactionType.LOOT, TrackingContext.LOOT,
            "Loot", true, Collections.singletonList(new ItemFlow(995, "Coins",
                value < 0L ? value : 1L, (int) Math.abs(value), value)));
    }

    private static SavedState profile(String zone, long now)
    {
        SavedState state = new SavedState();
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setSavedAtEpochMillis(now);
        state.setProfileTimeZoneId(zone);
        return state;
    }

    private static GpManagerEngine engine()
    {
        return new GpManagerEngine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() { });
    }

    private static Object privateField(Object target, String name) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
