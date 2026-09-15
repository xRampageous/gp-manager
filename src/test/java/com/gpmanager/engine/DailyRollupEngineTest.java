package com.gpmanager.engine;

import com.google.gson.Gson;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.DailyRollup;
import com.gpmanager.model.InsightsWindowSnapshot;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.SessionCategory;
import com.gpmanager.model.SessionNetTrendEntry;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.persistence.SavedState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DailyRollupEngineTest
{
    @Test
    public void pkEncounterTimestampsCannotAccrueWallClockTimeIntoRollups()
    {
        long now = Instant.parse("2026-09-15T12:00:00Z").toEpochMilli();
        long start = now - 3L * 24L * 60L * 60L * 1_000L;
        long finish = start + 31L * 60L * 1_000L;
        GpManagerEngine engine = engine();
        engine.startCustomSession("Edgeville PvP", SessionMode.PK, start);
        ProfitSession session = engine.getActiveSession();

        session.addPkEncounter(com.gpmanager.model.PkEncounterType.KILL,
            start + 6L * 60L * 1_000L, "Kill: Rival", ClassificationConfidence.CONFIRMED, "fixture");
        session.addTransaction(receipt(start + 7L * 60L * 1_000L, TransactionType.PK_LOOT,
            new ItemFlow(4151, "Abyssal whip", 1L, 1_000, 1_000L)), 100);
        session.addPkEncounter(com.gpmanager.model.PkEncounterType.DEATH,
            start + 20L * 60L * 1_000L, "Player death", ClassificationConfidence.CONFIRMED, "fixture");

        assertTrue(engine.finishCustomSession(finish));
        engine.togglePause(finish);
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneId.of("UTC")).toLocalDate();
        long rollupActive = engine.getDailyRollups(today.minusDays(80), today).stream()
            .mapToLong(DailyRollup::getActiveMillis).sum();

        assertEquals(finish - start, rollupActive);
        assertEquals(finish - start, engine.getOverallTotals(now).getActiveMillis());
    }

    @Test
    public void analyticsReadsDoNotAdvanceLiveOrArchivedSessionClocks()
    {
        long start = Instant.parse("2026-09-14T08:00:00Z").toEpochMilli();
        GpManagerEngine engine = engine();
        engine.startCustomSession("Completed", SessionMode.AUTO, start);
        ProfitSession archived = engine.getActiveSession();
        engine.processIfDirty(ContainerSnapshot.empty(), start + 60_000L);
        assertTrue(engine.finishCustomSession(start + 60_000L));

        engine.startCustomSession("Live", SessionMode.AUTO, start + 60_000L);
        ProfitSession live = engine.getActiveSession();
        engine.processIfDirty(ContainerSnapshot.empty(), start + 120_000L);
        long readAt = start + 24L * 60L * 60L * 1_000L;
        com.gpmanager.model.GoalDefinition todayNet = new com.gpmanager.model.GoalDefinition(
            com.gpmanager.model.GoalDefinition.Kind.NET,
            com.gpmanager.model.GoalDefinition.Scope.TODAY, 1L, Collections.emptyList());
        engine.setGoalDefinitions(Collections.singletonList(todayNet));
        long liveCursor = analyticsCursor(live);
        long liveActive = sessionActiveMillis(live);
        long archivedCursor = analyticsCursor(archived);
        long archivedActive = sessionActiveMillis(archived);

        live.metrics(readAt, 5L * 60L * 1_000L);
        live.getRuns(readAt);
        engine.getHistorySummary(archived.getId(), readAt);
        engine.getOverallTotals(readAt);
        engine.getOverallToday(readAt);
        engine.getInsightsWindow(7, readAt);
        engine.getGoalProgress(todayNet, readAt);

        assertEquals(liveCursor, analyticsCursor(live));
        assertEquals(liveActive, sessionActiveMillis(live));
        assertEquals(archivedCursor, analyticsCursor(archived));
        assertEquals(archivedActive, sessionActiveMillis(archived));
    }

    @Test
    public void profileZoneMigrationBuildsWindowsAndPersistsRollupsWithoutDroppingExcludedSessions()
    {
        long sessionStart = Instant.parse("2026-09-14T06:20:00Z").toEpochMilli();
        long firstLootAt = Instant.parse("2026-09-14T06:30:00Z").toEpochMilli();
        long costAt = Instant.parse("2026-09-14T06:40:00Z").toEpochMilli();
        long secondRunAt = Instant.parse("2026-09-14T07:30:00Z").toEpochMilli();
        long now = Instant.parse("2026-09-14T17:00:00Z").toEpochMilli();
        ProfitSession session = new ProfitSession("Woodcutting", sessionStart, SessionMode.GENERAL);
        session.setActivityHint("Woodcutting", sessionStart);
        session.addTransaction(receipt(firstLootAt, TransactionType.LOOT,
            new ItemFlow(1511, "Logs", 1L, 100, 100L)), 100);
        ProfitTransaction tinderbox = receipt(costAt, TransactionType.CONSUMPTION,
            new ItemFlow(590, "Tinderbox", -1L, 20, -20L));
        tinderbox.setActionKind(com.gpmanager.model.ActionKind.SUPPLIES); // an evidenced supply use, not an unexplained loss
        session.addTransaction(tinderbox, 100);
        session.startRun("Run 2", secondRunAt);
        session.addTransaction(receipt(secondRunAt + 1_000L, TransactionType.LOOT,
            new ItemFlow(1511, "Logs", 2L, 100, 200L)), 100);
        session.setExcludedFromAverages(true);

        SavedState legacyZone = new SavedState(session, Collections.emptyList());
        legacyZone.setSchemaVersion(16);
        legacyZone.setProfileTimeZoneId("America/Los_Angeles");
        legacyZone.setSavedAtEpochMillis(now);
        GpManagerEngine engine = engine();
        engine.restore(legacyZone, now);

        List<DailyRollup> days = engine.getDailyRollups(
            LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-14"));
        assertEquals("2026-09-13", day(days, "2026-09-13").getDay());
        assertEquals("2026-09-14", day(days, "2026-09-14").getDay());
        assertEquals("America/Los_Angeles", day(days, "2026-09-13").getZoneId());
        assertEquals(1, day(days, "2026-09-13").getSessionStarts());
        assertEquals(1, day(days, "2026-09-13").getRunStarts());
        assertEquals(1, day(days, "2026-09-14").getRunStarts());
        assertEquals(80L, day(days, "2026-09-13").getNetGp());
        assertEquals(20L, day(days, "2026-09-13").getSuppliesCostsGp());

        InsightsWindowSnapshot window = engine.getInsightsWindow(1, now);
        assertEquals(200L, window.getCurrent().getNetGp());
        assertEquals(80L, window.getPrevious().getNetGp());
        assertEquals(1, window.getCurrent().getRunStarts());
        assertEquals(1, window.getPrevious().getSessionStarts());

        List<SessionNetTrendEntry> trend = engine.getSessionNetTrend(7, now);
        assertEquals(1, trend.size());
        assertTrue(trend.get(0).isExcludedFromAverages());
        assertTrue(trend.get(0).isNetAvailable());
        assertEquals(280L, trend.get(0).getNetGp());

        SavedState saved = new Gson().fromJson(new Gson().toJson(engine.createSavedState()),
            SavedState.class);
        assertEquals("America/Los_Angeles", saved.getProfileTimeZoneId());
        assertEquals(days.size(), saved.getDailyRollups().size());
        GpManagerEngine restored = engine();
        restored.restore(saved, now);
        assertEquals(200L, restored.getInsightsWindow(1, now).getCurrent().getNetGp());
        assertEquals("America/Los_Angeles", restored.createSavedState().getProfileTimeZoneId());
    }

    @Test
    public void categoryRollupNetAndTimeReconcileToSessionDayTotals()
    {
        long dayStart = Instant.parse("2026-09-14T08:00:00Z").toEpochMilli();
        long bossEnd = dayStart + 3_600_000L;
        long skillStart = bossEnd + 1_000L;
        long now = skillStart + 3_600_000L;
        ProfitSession boss = categorySession("Bossing", SessionCategory.BOSSING,
            dayStart, bossEnd, 300L);
        ProfitSession skill = categorySession("Skilling", SessionCategory.SKILLING,
            skillStart, now, -50L);
        SavedState state = new SavedState(null, null, false, Arrays.asList(boss, skill));
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);

        GpManagerEngine engine = engine();
        engine.restore(state, now);
        DailyRollup rollup = engine.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0);

        long categoryNet = 0L;
        long categoryMillis = 0L;
        for (DailyRollup.CategoryTotal total : rollup.getCategoryTotals().values())
        {
            categoryNet += total.getNetGp();
            categoryMillis += total.getActiveMillis();
        }
        assertEquals(rollup.getNetGp(), categoryNet);
        assertEquals(rollup.getActiveMillis(), categoryMillis);
        assertEquals(300L,
            rollup.getCategoryTotals().get(SessionCategory.BOSSING).getNetGp());
        assertEquals(-50L,
            rollup.getCategoryTotals().get(SessionCategory.SKILLING).getNetGp());
        assertEquals(DailyRollup.Coverage.COMPLETE,
            rollup.getCoverage(DailyRollup.Dimension.CATEGORIES));
    }

    @Test
    public void legacyCategoryRollupUncertaintyDoesNotDowngradeAccounting()
    {
        long start = Instant.parse("2026-09-14T10:00:00Z").toEpochMilli();
        long end = start + 60_000L;
        long now = end;
        ProfitSession session = categorySession("Bossing", SessionCategory.BOSSING,
            start, end, 300L);
        SavedState sourceState = new SavedState(null, null, false,
            Collections.singletonList(session));
        sourceState.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        sourceState.setProfileTimeZoneId("UTC");
        sourceState.setSavedAtEpochMillis(now);
        GpManagerEngine source = engine();
        source.restore(sourceState, now);
        DailyRollup full = source.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0);
        com.google.gson.JsonObject legacyJson = new Gson().toJsonTree(full).getAsJsonObject();
        legacyJson.remove("categoryTotals");
        legacyJson.getAsJsonObject("coverage").remove("CATEGORIES");
        legacyJson.add("sourceSessionIds", new com.google.gson.JsonArray());
        DailyRollup legacy = new Gson().fromJson(legacyJson, DailyRollup.class);

        SavedState restoredState = new SavedState(null, null, false,
            Collections.singletonList(session));
        restoredState.setSchemaVersion(18);
        restoredState.setProfileTimeZoneId("UTC");
        restoredState.setSavedAtEpochMillis(now);
        restoredState.setDailyRollups(Collections.singletonList(legacy));
        GpManagerEngine restored = engine();
        restored.restore(restoredState, now);
        DailyRollup result = restored.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0);

        assertEquals(full.getNetGp(), result.getNetGp());
        assertEquals(DailyRollup.Coverage.COMPLETE,
            result.getCoverage(DailyRollup.Dimension.ACCOUNTING));
        assertEquals(DailyRollup.Coverage.PARTIAL,
            result.getCoverage(DailyRollup.Dimension.CATEGORIES));
    }

    @Test
    public void legacyUtcDayCannotMasqueradeAsACompleteLocalWindow()
    {
        long now = Instant.parse("2026-09-14T17:00:00Z").toEpochMilli();
        ProfitSession session = new ProfitSession("Legacy", now - 86_400_000L, SessionMode.GENERAL);
        session.addTransaction(receipt(now - 80_000_000L, TransactionType.LOOT,
            new ItemFlow(1511, "Logs", 1L, 100, 100L)), 100);
        session.close(now);
        session.compactAllTransactions();
        SavedState state = new SavedState(session, Collections.emptyList());
        state.setSchemaVersion(16);
        state.setProfileTimeZoneId("America/Los_Angeles");
        state.setSavedAtEpochMillis(now);

        GpManagerEngine engine = engine();
        engine.restore(state, now);
        InsightsWindowSnapshot snapshot = engine.getInsightsWindow(2, now);
        assertTrue(snapshot.getPrevious().getCoverage(DailyRollup.Dimension.ACCOUNTING)
            != DailyRollup.Coverage.COMPLETE);
        assertTrue(snapshot.getCurrent().getCoverage(DailyRollup.Dimension.ACCOUNTING)
            != DailyRollup.Coverage.COMPLETE);
    }

    @Test
    public void persistedRollupSurvivesWhenSessionSourcesAreNoLongerRetained()
    {
        LocalDate day = LocalDate.parse("2026-09-14");
        long now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        DailyRollup persisted = DailyRollup.builder(day, ZoneId.of("UTC"))
            .addAccounting(500L, 125L)
            .addEventCounts(3, 1, 1, 2)
            .build();
        SavedState state = new SavedState();
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setDailyRollups(Collections.singletonList(persisted));

        GpManagerEngine restored = engine();
        restored.restore(state, now);
        DailyRollup result = restored.getDailyRollups(day, day).get(0);
        assertEquals(375L, result.getNetGp());
        assertEquals(3, result.getKills());
        assertEquals(1, result.getSessionStarts());
    }

    @Test
    public void divergedPersistedAndSessionDayDoesNotDoubleCountOrClaimCompleteness()
    {
        LocalDate day = LocalDate.parse("2026-09-14");
        long now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        ProfitSession session = new ProfitSession("Woodcutting", now - 60_000L, SessionMode.GENERAL);
        session.configureAnalyticsTimeZone("UTC", false);
        session.addTransaction(receipt(now - 30_000L, TransactionType.LOOT,
            new ItemFlow(1511, "Logs", 2L, 100, 200L)), 100);
        SavedState state = new SavedState(session, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setDailyRollups(Collections.singletonList(DailyRollup.builder(day, ZoneId.of("UTC"))
            .addSourceSessionId(session.getId())
            .addSourceSessionId("trimmed-session-b")
            .addAccounting(300L, 0L).build()));

        GpManagerEngine restored = engine();
        restored.restore(state, now);
        DailyRollup result = restored.getDailyRollups(day, day).get(0);
        assertEquals(200L, result.getRevenueGp());
        assertEquals(DailyRollup.Coverage.PARTIAL,
            result.getCoverage(DailyRollup.Dimension.ACCOUNTING));
    }

    @Test
    public void changedAmountsFromEveryRecordedSessionReplaceSnapshotExactly()
    {
        LocalDate day = LocalDate.parse("2026-09-14");
        long now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        ProfitSession session = new ProfitSession("Woodcutting", now - 60_000L, SessionMode.GENERAL);
        session.configureAnalyticsTimeZone("UTC", false);
        session.addTransaction(receipt(now - 30_000L, TransactionType.LOOT,
            new ItemFlow(1511, "Logs", 2L, 100, 200L)), 100);
        SavedState state = new SavedState(session, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setDailyRollups(Collections.singletonList(DailyRollup.builder(day, ZoneId.of("UTC"))
            .addSourceSessionId(session.getId())
            .addAccounting(100L, 0L).build()));

        GpManagerEngine restored = engine();
        restored.restore(state, now);
        DailyRollup result = restored.getDailyRollups(day, day).get(0);
        assertEquals(200L, result.getRevenueGp());
        assertEquals(DailyRollup.Coverage.COMPLETE,
            result.getCoverage(DailyRollup.Dimension.ACCOUNTING));
    }

    @Test
    public void explicitHistoryClearDoesNotResurrectCachedDailyTotals()
    {
        long now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        ProfitSession archived = new ProfitSession("Archived", now - 86_400_000L, SessionMode.GENERAL);
        archived.addTransaction(receipt(now - 60_000L, TransactionType.LOOT,
            new ItemFlow(1511, "Logs", 1L, 100, 100L)), 100);
        SavedState state = new SavedState(null, null, false,
            Collections.singletonList(archived));
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setDailyRollups(Collections.singletonList(DailyRollup.builder(
            LocalDate.parse("2026-09-14"), ZoneId.of("UTC"))
            .addAccounting(100L, 0L).build()));

        GpManagerEngine restored = engine();
        restored.restore(state, now);
        assertEquals(100L, restored.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0).getRevenueGp());
        assertEquals(1, restored.clearCompletedHistory());
        assertTrue(restored.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).isEmpty());
        restored.restore(state, now);
        restored.resetTrackingData(now);
        assertEquals(0L, restored.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0).getRevenueGp());
    }

    @Test
    public void twoDayLegacyZoneLabelDifferenceMakesOverlappingWindowPartial()
    {
        ZoneId profileZone = ZoneId.of("Etc/GMT+12");
        long now = Instant.parse("2026-01-01T11:00:00Z").toEpochMilli();
        DailyRollup profileDay = DailyRollup.builder(LocalDate.parse("2025-12-31"), profileZone)
            .addAccounting(100L, 0L)
            .addActivity("Woodcutting", 100L, 3_600_000L)
            .addActivitySession("Woodcutting", "old-source")
            .addHourlyBuckets(hourBucket(14, 100L), hourBucket(14, 3_600_000L))
            .build();
        DailyRollup legacyZoneDay = DailyRollup.builder(LocalDate.parse("2026-01-02"),
            ZoneId.of("Pacific/Kiritimati"))
            .addAccounting(200L, 0L)
            .build();
        SavedState state = new SavedState();
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId(profileZone.getId());
        state.setDailyRollups(Arrays.asList(profileDay, legacyZoneDay));

        GpManagerEngine restored = engine();
        restored.restore(state, now);
        InsightsWindowSnapshot.Window current = restored.getInsightsWindow(1, now).getCurrent();
        assertEquals(DailyRollup.Coverage.PARTIAL,
            current.getCoverage(DailyRollup.Dimension.ACCOUNTING));
        assertEquals(100L, current.getRevenueGp());
        assertFalse(current.getBestHour().isAvailable());
        assertEquals(1, current.getTopActivities().size());
        assertFalse(current.getTopActivities().get(0).isPositiveNetShareAvailable());
        assertFalse(current.getTopActivities().get(0).isSessionCountAvailable());
    }

    @Test
    public void observedNpcEncountersPersistIntoDailyRollupsAndSessionTodayGoals()
    {
        long start = Instant.parse("2026-09-14T10:00:00Z").toEpochMilli();
        long first = start + 1_000L;
        long second = start + 2_000L;
        long now = start + 3_000L;
        ProfitSession session = new ProfitSession("Goblin task", start, SessionMode.AUTO);
        session.configureAnalyticsTimeZone("UTC", false);
        assertTrue(session.recordObservedEncounter("Goblin", 2L, 120L, true, 2L, first));
        assertTrue(session.recordObservedEncounter("Goblin", 1L, 70L, true, 3L, second));
        session.addTransaction(receipt(second + 1L, TransactionType.LOOT,
            new ItemFlow(1511, "Logs", 25L, 100, 2_500L)), 100);
        ProfitSession archived = new ProfitSession("Imp task", start, SessionMode.AUTO);
        archived.configureAnalyticsTimeZone("UTC", false);
        assertTrue(archived.recordObservedEncounter("Imp", 4L, 80L, true, 1L, second));
        archived.close(second + 500L);

        SavedState state = new SavedState(session, Collections.singletonList(archived));
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        GpManagerEngine engine = engine();
        engine.restore(state, now);

        DailyRollup rollup = engine.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0);
        assertEquals(7L, rollup.getPvmEncounterCount());
        assertEquals("Imp", rollup.getTopPvmSource());
        assertEquals(DailyRollup.Coverage.COMPLETE,
            rollup.getCoverage(DailyRollup.Dimension.PVM_ENCOUNTERS));

        com.gpmanager.model.GoalDefinition sessionKills = new com.gpmanager.model.GoalDefinition(
            com.gpmanager.model.GoalDefinition.Kind.KILLS,
            com.gpmanager.model.GoalDefinition.Scope.SESSION, 3L, Collections.emptyList());
        com.gpmanager.model.GoalProgress kills = engine.getGoalProgress(sessionKills, now);
        assertTrue(kills.isAvailable());
        assertEquals(3L, kills.getCurrentValue());
        com.gpmanager.model.GoalDefinition todayKills = new com.gpmanager.model.GoalDefinition(
            com.gpmanager.model.GoalDefinition.Kind.KILLS,
            com.gpmanager.model.GoalDefinition.Scope.TODAY, 4L, Collections.emptyList());
        assertEquals(7L, engine.getGoalProgress(todayKills, now).getCurrentValue());
        com.gpmanager.model.GoalDefinition itemGoal = new com.gpmanager.model.GoalDefinition(
            com.gpmanager.model.GoalDefinition.Kind.ITEM_COUNT,
            com.gpmanager.model.GoalDefinition.Scope.TODAY, 30L, 1511, Collections.emptyList());
        assertEquals(25L, engine.getGoalProgress(itemGoal, now).getCurrentValue());
    }

    @Test
    public void preEncounterSchemaKeepsSessionHighlightUnavailableAfterRestore()
    {
        long start = Instant.parse("2026-09-14T10:00:00Z").toEpochMilli();
        ProfitSession legacy = new ProfitSession("Legacy slayer", start, SessionMode.AUTO);
        legacy.addTransaction(receipt(start + 1_000L, TransactionType.LOOT,
            new ItemFlow(526, "Bones", 25L, 1, 25L)), 100);
        SavedState old = new SavedState(legacy, Collections.emptyList());
        old.setSchemaVersion(19);
        old.setProfileTimeZoneId("UTC");

        GpManagerEngine engine = engine();
        engine.restore(old, start + 2_000L);

        assertTrue(engine.getActiveSession() != null);
        assertTrue("legacy encounter coverage is explicitly unknown",
            !engine.getActiveSession().getSessionHighlight().isAvailable());
        assertEquals("ENCOUNTER_HISTORY_UNAVAILABLE",
            engine.getActiveSession().getSessionHighlight().getUnavailableReason());
    }

    @Test
    public void timezoneRebaseKeepsLegacyTodayKillCoverageUnavailable()
    {
        long start = Instant.parse("2026-09-14T10:00:00Z").toEpochMilli();
        ProfitSession legacy = new ProfitSession("Legacy hunt", start, SessionMode.AUTO);
        assertTrue(legacy.recordObservedEncounter("Goblin", 1L, 0L, true, 1L,
            start + 1_000L));
        SavedState old = new SavedState(legacy, Collections.emptyList());
        old.setSchemaVersion(19);
        old.setProfileTimeZoneId("America/Los_Angeles");

        GpManagerEngine engine = engine();
        engine.restore(old, start + 2_000L);
        com.gpmanager.model.GoalDefinition todayKills = new com.gpmanager.model.GoalDefinition(
            com.gpmanager.model.GoalDefinition.Kind.KILLS,
            com.gpmanager.model.GoalDefinition.Scope.TODAY, 1L, Collections.emptyList());
        com.gpmanager.model.GoalProgress progress = engine.getGoalProgress(todayKills, start + 2_000L);

        assertFalse("rebasing discarded the old encounter day split", progress.isAvailable());
        assertEquals("TODAY_KILL_COUNTS_UNAVAILABLE", progress.getUnavailableReason());
        DailyRollup rollup = engine.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0);
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            rollup.getCoverage(DailyRollup.Dimension.PVM_ENCOUNTERS));
    }

    @Test
    public void sameZoneLegacyMigrationKeepsNewSameDayEncounterPartial()
    {
        long start = Instant.parse("2026-09-14T10:00:00Z").toEpochMilli();
        SavedState old = new SavedState(new ProfitSession("Legacy hunt", start, SessionMode.AUTO),
            Collections.emptyList());
        old.setSchemaVersion(19);
        old.setProfileTimeZoneId("UTC");
        old.setSavedAtEpochMillis(start + 1_000L);

        GpManagerEngine engine = engine();
        engine.restore(old, start + 1_000L);
        assertTrue(engine.getActiveSession().recordObservedEncounter("Goblin", 1L,
            0L, true, 1L, start + 2_000L));

        com.gpmanager.model.GoalDefinition todayKills = new com.gpmanager.model.GoalDefinition(
            com.gpmanager.model.GoalDefinition.Kind.KILLS,
            com.gpmanager.model.GoalDefinition.Scope.TODAY, 1L, Collections.emptyList());
        assertFalse("new events cannot fill an unknown pre-upgrade day",
            engine.getGoalProgress(todayKills, start + 3_000L).isAvailable());
        DailyRollup sameDay = engine.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0);
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            sameDay.getCoverage(DailyRollup.Dimension.PVM_ENCOUNTERS));

        long nextDay = Instant.parse("2026-09-15T10:00:00Z").toEpochMilli();
        assertTrue(engine.getActiveSession().recordObservedEncounter("Goblin", 1L,
            0L, true, 2L, nextDay));
        DailyRollup nextDayRollup = engine.getDailyRollups(LocalDate.parse("2026-09-15"),
            LocalDate.parse("2026-09-15")).get(0);
        assertEquals(DailyRollup.Coverage.COMPLETE,
            nextDayRollup.getCoverage(DailyRollup.Dimension.PVM_ENCOUNTERS));
        assertEquals(1L, nextDayRollup.getPvmEncounterCount());
    }

    @Test
    public void overflowedSourceNamesKeepExactKillCountButWithholdTopSource()
    {
        long start = Instant.parse("2026-09-14T10:00:00Z").toEpochMilli();
        ProfitSession session = new ProfitSession("Long hunt", start, SessionMode.AUTO);
        session.configureAnalyticsTimeZone("UTC", false);
        for (int i = 0; i < 31; i++)
        {
            assertTrue(session.recordObservedEncounter("Creature " + i, 1L, 0L,
                true, 1L, start + i + 1L));
        }
        SavedState state = new SavedState(session, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(start + 100L);

        GpManagerEngine engine = engine();
        engine.restore(state, start + 100L);
        DailyRollup rollup = engine.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0);
        assertEquals(31L, rollup.getPvmEncounterCount());
        assertEquals(DailyRollup.Coverage.COMPLETE,
            rollup.getCoverage(DailyRollup.Dimension.PVM_ENCOUNTERS));
        assertEquals(DailyRollup.Coverage.PARTIAL,
            rollup.getCoverage(DailyRollup.Dimension.PVM_ENCOUNTER_SOURCES));
        assertEquals("", rollup.getTopPvmSource());

        com.gpmanager.model.GoalDefinition todayKills = new com.gpmanager.model.GoalDefinition(
            com.gpmanager.model.GoalDefinition.Kind.KILLS,
            com.gpmanager.model.GoalDefinition.Scope.TODAY, 31L, Collections.emptyList());
        com.gpmanager.model.GoalProgress progress = engine.getGoalProgress(todayKills, start + 100L);
        assertTrue(progress.isAvailable());
        assertEquals(31L, progress.getCurrentValue());
    }

    @Test
    public void analyticsDayCapMakesLifetimeItemGoalAndHighlightUnavailable()
    {
        long firstDay = Instant.parse("2025-01-01T12:00:00Z").toEpochMilli();
        ProfitSession session = new ProfitSession("Long skilling", firstDay, SessionMode.AUTO);
        session.configureAnalyticsTimeZone("UTC", false);
        for (int day = 0; day < 400; day++)
        {
            long timestamp = firstDay + day * 86_400_000L;
            session.addTransaction(receipt(timestamp, TransactionType.LOOT,
                new ItemFlow(1511, "Logs", 25L, 1, 25L)), 1_000);
        }
        assertTrue("exactly 400 retained days are still complete", session.isAnalyticsItemHistoryComplete());
        long evictingDay = firstDay + 400L * 86_400_000L;
        session.addTransaction(receipt(evictingDay, TransactionType.LOOT,
            new ItemFlow(1511, "Logs", 25L, 1, 25L)), 1_000);
        assertFalse(session.isAnalyticsItemHistoryComplete());

        SavedState state = new SavedState(session, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        long now = firstDay + 401L * 86_400_000L;
        state.setSavedAtEpochMillis(now);
        GpManagerEngine engine = engine();
        engine.restore(state, now);

        com.gpmanager.model.GoalDefinition itemGoal = new com.gpmanager.model.GoalDefinition(
            com.gpmanager.model.GoalDefinition.Kind.ITEM_COUNT,
            com.gpmanager.model.GoalDefinition.Scope.SESSION, 20L, 1511,
            Collections.emptyList());
        com.gpmanager.model.GoalProgress progress = engine.getGoalProgress(itemGoal, now);
        assertFalse("evicted lifetime item quantities must not become a complete total",
            progress.isAvailable());
        assertEquals("ITEM_HISTORY_TRUNCATED", progress.getUnavailableReason());
        assertEquals(com.gpmanager.model.SessionHighlight.Kind.UNAVAILABLE,
            engine.getActiveSession().getSessionHighlight().getKind());
    }

    @Test
    public void hourlyBucketsUseProfileLocalHoursAcrossSpringDstAndCollapseExactly()
    {
        ZoneId zone = ZoneId.of("America/New_York");
        long start = Instant.parse("2026-03-08T06:30:00Z").toEpochMilli();
        long firstGain = Instant.parse("2026-03-08T07:30:00Z").toEpochMilli();
        long secondGain = Instant.parse("2026-03-08T08:15:00Z").toEpochMilli();
        long end = Instant.parse("2026-03-08T08:30:00Z").toEpochMilli();
        ProfitSession session = new ProfitSession("DST run", start, SessionMode.GENERAL);
        session.configureAnalyticsTimeZone(zone.getId(), false);
        session.addTransaction(namedReceipt(firstGain, "Woodcutting",
            new ItemFlow(1511, "Logs", 1L, 300, 300L)), 100);
        session.addTransaction(namedReceipt(secondGain, "Woodcutting",
            new ItemFlow(1511, "Logs", 1L, 500, 500L)), 100);
        session.close(end);
        GpManagerEngine engine = restoredWithSessions(zone, end, Collections.singletonList(session));

        DailyRollup daily = engine.getDailyRollups(LocalDate.parse("2026-03-08"),
            LocalDate.parse("2026-03-08")).get(0);
        assertEquals(DailyRollup.Coverage.COMPLETE,
            daily.getCoverage(DailyRollup.Dimension.HOURLY_BUCKETS));
        long[] net = daily.getHourlyNetGp();
        long[] active = daily.getHourlyActiveMillis();
        assertEquals(0L, net[2]);
        assertEquals(300L, net[3]);
        assertEquals(500L, net[4]);
        assertEquals(30L * 60_000L, active[1]);
        assertEquals(60L * 60_000L, active[3]);
        assertEquals(30L * 60_000L, active[4]);
        assertArrayEquals(sumByFour(net), daily.getFourHourNetGp());
        assertArrayEquals(sumByFour(active), daily.getFourHourActiveMillis());

        InsightsWindowSnapshot.Window window = engine.getInsightsWindow(1, end).getCurrent();
        assertTrue(window.getBestHour().isAvailable());
        assertEquals(LocalDate.parse("2026-03-08"), window.getBestHour().getDate());
        assertEquals(4, window.getBestHour().getHour());
        assertEquals(500L, window.getBestHour().getNetGp());
        assertEquals(active[4], window.getBestHour().getActiveMillis());
    }

    @Test
    public void currentAndPreviousWindowsExposeWeightedRatesAndSeparateSessionExtrema()
    {
        ZoneId zone = ZoneId.of("UTC");
        long previousStart = Instant.parse("2026-09-13T08:00:00Z").toEpochMilli();
        long previousEnd = previousStart + 60L * 60_000L;
        long excludedStart = Instant.parse("2026-09-13T10:00:00Z").toEpochMilli();
        long excludedEnd = excludedStart + 3L * 60L * 60_000L;
        long currentStart = Instant.parse("2026-09-14T08:00:00Z").toEpochMilli();
        long currentEnd = currentStart + 2L * 60L * 60_000L;
        long now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        ProfitSession previous = closedGainSession("Previous best", zone, previousStart,
            previousEnd, "Oak logs", 100L, 100L, false);
        ProfitSession excluded = closedGainSession("Excluded", zone, excludedStart,
            excludedEnd, "Oak logs", 10_000L, 10_000L, true);
        ProfitSession current = closedGainSession("Current best", zone, currentStart,
            currentEnd, "Oak logs", 400L, 400L, false);
        GpManagerEngine engine = restoredWithSessions(zone, now, Arrays.asList(previous, excluded, current));

        InsightsWindowSnapshot snapshot = engine.getInsightsWindow(1, now);
        assertEquals(200L, snapshot.getCurrent().getAverageGpPerHour().longValue());
        assertEquals(100L, snapshot.getPrevious().getAverageGpPerHour().longValue());
        assertEquals(current.getId(), snapshot.getCurrent().getBestSession().getSessionId());
        assertTrue(snapshot.getCurrent().getBiggestDrop().isAvailable());
        assertTrue(snapshot.getCurrent().getBiggestDrop().isPresent());
        assertEquals(400L, snapshot.getCurrent().getBiggestDrop().getValueGp());
        assertEquals(400L, snapshot.getCurrent().getBiggestDrop().getQuantity());
        assertEquals(current.getId(), snapshot.getCurrent().getBiggestDrop().getSessionId());
        assertEquals(excluded.getId(), snapshot.getPrevious().getBestSession().getSessionId());
        assertEquals(excluded.getId(), snapshot.getPrevious().getLongestSession().getSessionId());
        assertFalse("previous-window winner must not leak into current window",
            current.getId().equals(snapshot.getPrevious().getBestSession().getSessionId()));
        assertFalse(snapshot.getCurrent().isAverageGpPerHourRollupBacked());
    }

    @Test
    public void highlightsFailClosedWhenOnlyRollupTotalsRemainAndMarkTheirSource()
    {
        long now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        DailyRollup row = DailyRollup.builder(LocalDate.parse("2026-09-14"), ZoneId.of("UTC"))
            .addAccounting(900L, 100L)
            .addActiveMillis(3_600_000L)
            .build();
        SavedState state = new SavedState();
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        state.setDailyRollups(Collections.singletonList(row));
        GpManagerEngine engine = engine();
        engine.restore(state, now);

        InsightsWindowSnapshot.Window window = engine.getInsightsWindow(1, now).getCurrent();
        assertFalse(window.getBestSession().isAvailable());
        assertTrue(window.getBestSession().isRollupBacked());
        assertFalse(window.getLongestSession().isAvailable());
        assertTrue(window.getLongestSession().isRollupBacked());
        assertFalse(window.getBiggestDrop().isAvailable());
        assertTrue(window.getBiggestDrop().isRollupBacked());
        assertFalse(window.isAverageGpPerHourAvailable());
        assertTrue(window.isAverageGpPerHourRollupBacked());
    }

    @Test
    public void sourceLessZeroNetRollupWithGrossAndItemDetailCannotProveNoDrop()
    {
        long now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        SavedState emptyState = new SavedState();
        emptyState.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        emptyState.setProfileTimeZoneId("UTC");
        GpManagerEngine emptyEngine = engine();
        emptyEngine.restore(emptyState, now);
        assertFalse(emptyEngine.getInsightsWindow(1, now).getCurrent()
            .getBiggestDrop().isAvailable());

        DailyRollup row = DailyRollup.builder(LocalDate.parse("2026-09-14"), ZoneId.of("UTC"))
            .addAccounting(100L, 100L)
            .addActivity("Woodcutting", 0L, 0L)
            .addGainedItem(1511, "Logs", 1L, 100L)
            .addCostItem(314, "Feathers", 1L, 100L)
            .build();
        SavedState state = new SavedState();
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        state.setDailyRollups(Collections.singletonList(row));
        GpManagerEngine engine = engine();
        engine.restore(state, now);

        InsightsWindowSnapshot.Window window = engine.getInsightsWindow(1, now).getCurrent();
        assertFalse(window.getBestSession().isAvailable());
        assertTrue(window.getBestSession().isRollupBacked());
        assertFalse(window.getBiggestDrop().isAvailable());
        assertTrue(window.getBiggestDrop().isRollupBacked());
        assertFalse(window.isAverageGpPerHourAvailable());
        assertTrue(window.isAverageGpPerHourRollupBacked());
    }

    @Test
    public void activitySessionsAndTopItemQuantitiesAreExposedWithCapState()
    {
        ZoneId zone = ZoneId.of("UTC");
        long start = Instant.parse("2026-09-14T08:00:00Z").toEpochMilli();
        long end = start + 90L * 60_000L;
        List<ProfitSession> sessions = new java.util.ArrayList<>();
        sessions.add(activitySession("Skilling A", zone, start, end, 1511, "Logs", 2L, 200L));
        sessions.add(activitySession("Skilling B", zone, start + 40L * 60_000L,
            start + 70L * 60_000L, 1511, "Logs", 3L, 300L));
        ProfitSession capped = new ProfitSession("Many items", start, SessionMode.GENERAL);
        capped.configureAnalyticsTimeZone(zone.getId(), false);
        for (int i = 0; i < 21; i++)
            capped.addTransaction(namedReceipt(start + 1_000L + i, "General",
                new ItemFlow(30_000 + i, "Item " + i, 1L, 10, 10L)), 100);
        capped.close(end);
        sessions.add(capped);

        GpManagerEngine engine = restoredWithSessions(zone, end, sessions);
        DailyRollup daily = engine.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0);
        DailyRollup.ActivityTotal activity = daily.getActivities().get("Woodcutting");
        assertEquals(2, activity.getSessionCount());
        assertEquals(DailyRollup.Coverage.COMPLETE,
            daily.getCoverage(DailyRollup.Dimension.ACTIVITY_SESSION_COUNTS));
        assertEquals(5L, daily.getGainedItemTotals().get("id:1511").getQuantity());
        assertTrue(daily.isGainedItemsTruncated());

        InsightsWindowSnapshot.Window window = engine.getInsightsWindow(1, end).getCurrent();
        assertEquals(DailyRollup.TOP_ITEM_LIMIT, window.getTopItemLimit());
        assertTrue(window.isGainedItemsTruncated());
        InsightsWindowSnapshot.ActivityInsight woodcutting = window.getTopActivities().stream()
            .filter(value -> "Woodcutting".equals(value.getName())).findFirst().get();
        assertEquals(2, woodcutting.getSessionCount());
        assertEquals(500.0d / 710.0d,
            woodcutting.getPositiveNetShare().doubleValue(), 0.0001d);
    }

    @Test
    public void mismatchedPersistedActivitySessionAttributionDowngradesOnlyThatDimension()
    {
        ZoneId zone = ZoneId.of("UTC");
        long start = Instant.parse("2026-09-14T08:00:00Z").toEpochMilli();
        long end = start + 60L * 60_000L;
        ProfitSession woodcutting = namedActivitySession("Wood session", "Woodcutting",
            start, end, 1511, "Logs", 100L);
        ProfitSession fishing = namedActivitySession("Fishing session", "Fishing",
            start + 2L * 60_000L, end, 317, "Shrimp", 100L);
        List<ProfitSession> sessions = Arrays.asList(woodcutting, fishing);
        GpManagerEngine source = restoredWithSessions(zone, end, sessions);
        DailyRollup original = source.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0);

        com.google.gson.JsonObject json = new Gson().toJsonTree(original).getAsJsonObject();
        com.google.gson.JsonObject activities = json.getAsJsonObject("activities");
        com.google.gson.JsonObject woodActivity = findActivity(activities, "Woodcutting");
        com.google.gson.JsonObject fishActivity = findActivity(activities, "Fishing");
        com.google.gson.JsonArray woodIds = woodActivity.getAsJsonArray("sourceSessionIds");
        com.google.gson.JsonArray fishIds = fishActivity.getAsJsonArray("sourceSessionIds");
        String woodId = woodIds.get(0).getAsString();
        String fishId = fishIds.get(0).getAsString();
        woodIds.set(0, new com.google.gson.JsonPrimitive(fishId));
        fishIds.set(0, new com.google.gson.JsonPrimitive(woodId));
        DailyRollup cachedWithWrongActivityMembers = new Gson().fromJson(json, DailyRollup.class);

        SavedState state = new SavedState(null, null, false, sessions);
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId(zone.getId());
        state.setSavedAtEpochMillis(end);
        state.setDailyRollups(Collections.singletonList(cachedWithWrongActivityMembers));
        GpManagerEngine restored = engine();
        restored.restore(state, end);
        DailyRollup result = restored.getDailyRollups(LocalDate.parse("2026-09-14"),
            LocalDate.parse("2026-09-14")).get(0);

        assertEquals(original.getNetGp(), result.getNetGp());
        assertEquals(DailyRollup.Coverage.COMPLETE,
            result.getCoverage(DailyRollup.Dimension.ACCOUNTING));
        assertEquals(DailyRollup.Coverage.PARTIAL,
            result.getCoverage(DailyRollup.Dimension.ACTIVITY_SESSION_COUNTS));
    }

    @Test
    public void schemaTwentyHourlyCoverageStaysUnavailableAndRetainsSixBucketValues()
    {
        long now = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        long[] net = { 10L, 20L, 30L, 40L, 50L, 60L };
        long[] active = { 1L, 2L, 3L, 4L, 5L, 6L };
        DailyRollup complete = DailyRollup.builder(LocalDate.parse("2026-09-14"), ZoneId.of("UTC"))
            .addFourHourBuckets(net, active).build();
        com.google.gson.JsonObject json = new Gson().toJsonTree(complete).getAsJsonObject();
        json.remove("hourlyNetGp");
        json.remove("hourlyActiveMillis");
        json.getAsJsonObject("coverage").remove("HOURLY_BUCKETS");
        json.getAsJsonObject("coverage").remove("ACTIVITY_SESSION_COUNTS");
        DailyRollup legacy = new Gson().fromJson(json, DailyRollup.class);
        SavedState state = new SavedState();
        state.setSchemaVersion(20);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        state.setDailyRollups(Collections.singletonList(legacy));
        GpManagerEngine engine = engine();
        engine.restore(state, now);

        InsightsWindowSnapshot.Window window = engine.getInsightsWindow(1, now).getCurrent();
        assertArrayEquals(net, window.getFourHourNetGp());
        assertArrayEquals(active, window.getFourHourActiveMillis());
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            window.getCoverage(DailyRollup.Dimension.HOURLY_BUCKETS));
        assertFalse(window.getBestHour().isAvailable());
    }

    @Test
    public void partialHourlyCoveragePreservesStoredFourHourCompatibilityValues()
    {
        long[] fourHourNet = { 13L, 0L, 0L, 0L, 0L, 0L };
        long[] fourHourActive = { 5L, 0L, 0L, 0L, 0L, 0L };
        long[] hourlyNet = new long[DailyRollup.HOURLY_BUCKET_COUNT];
        long[] hourlyActive = new long[DailyRollup.HOURLY_BUCKET_COUNT];
        hourlyNet[0] = 7L;
        hourlyActive[0] = 3L;
        DailyRollup partial = DailyRollup.builder(LocalDate.parse("2026-09-14"), ZoneId.of("UTC"))
            .addFourHourBuckets(fourHourNet, fourHourActive)
            .addHourlyBuckets(hourlyNet, hourlyActive)
            .coverage(DailyRollup.Dimension.HOURLY_BUCKETS, DailyRollup.Coverage.PARTIAL)
            .build();

        assertArrayEquals(fourHourNet, partial.getFourHourNetGp());
        assertArrayEquals(fourHourActive, partial.getFourHourActiveMillis());
    }

    private static DailyRollup day(List<DailyRollup> days, String date)
    {
        for (DailyRollup day : days)
        {
            if (date.equals(day.getDay()) && "America/Los_Angeles".equals(day.getZoneId())) return day;
        }
        throw new AssertionError("Missing daily rollup " + date + " in " + days.size() + " rows");
    }

    private static long analyticsCursor(ProfitSession session)
    {
        return new Gson().toJsonTree(session).getAsJsonObject()
            .get("analyticsLastActiveAtEpochMillis").getAsLong();
    }

    private static long sessionActiveMillis(ProfitSession session)
    {
        return session.getAnalyticsDays().stream()
            .mapToLong(com.gpmanager.model.TrackingDaySummary::getActiveMillis).sum();
    }

    private static com.google.gson.JsonObject findActivity(com.google.gson.JsonObject activities,
        String name)
    {
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : activities.entrySet())
        {
            com.google.gson.JsonObject activity = entry.getValue().getAsJsonObject();
            if (name.equals(activity.get("activityName").getAsString())) return activity;
        }
        throw new AssertionError("Missing activity " + name + " in " + activities);
    }

    private static ProfitTransaction receipt(long timestamp, TransactionType type, ItemFlow... flows)
    {
        return new ProfitTransaction(timestamp, type, TrackingContext.GENERIC, "", true,
            Arrays.asList(flows));
    }

    private static ProfitTransaction namedReceipt(long timestamp, String activity, ItemFlow... flows)
    {
        return new ProfitTransaction(timestamp, null, TransactionType.LOOT, TrackingContext.LOOT,
            "Loot", activity, true, Arrays.asList(flows));
    }

    private static ProfitSession closedGainSession(String name, ZoneId zone, long start, long end,
        String itemName, long quantity, long value, boolean excluded)
    {
        ProfitSession session = new ProfitSession(name, start, SessionMode.GENERAL);
        session.configureAnalyticsTimeZone(zone.getId(), false);
        session.addTransaction(namedReceipt(start + 1L, "Woodcutting",
            new ItemFlow(1511, itemName, quantity, (int) value, value)), 100);
        session.setExcludedFromAverages(excluded);
        session.close(end);
        return session;
    }

    private static ProfitSession activitySession(String name, ZoneId zone, long start, long end,
        int itemId, String itemName, long quantity, long value)
    {
        ProfitSession session = new ProfitSession(name, start, SessionMode.GENERAL);
        session.configureAnalyticsTimeZone(zone.getId(), false);
        session.addTransaction(namedReceipt(start + 1L, "Woodcutting",
            new ItemFlow(itemId, itemName, quantity, (int) (value / quantity), value)), 100);
        session.close(end);
        return session;
    }

    private static ProfitSession namedActivitySession(String name, String activity, long start,
        long end, int itemId, String itemName, long value)
    {
        ProfitSession session = new ProfitSession(name, start, SessionMode.GENERAL);
        session.configureAnalyticsTimeZone("UTC", false);
        session.addTransaction(namedReceipt(start + 1L, activity,
            new ItemFlow(itemId, itemName, 1L, (int) value, value)), 100);
        session.close(end);
        return session;
    }

    private static GpManagerEngine restoredWithSessions(ZoneId zone, long now,
        List<ProfitSession> sessions)
    {
        SavedState state = new SavedState(null, null, false, sessions);
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId(zone.getId());
        state.setSavedAtEpochMillis(now);
        GpManagerEngine engine = engine();
        engine.restore(state, now);
        return engine;
    }

    private static long[] sumByFour(long[] hourly)
    {
        long[] result = new long[DailyRollup.FOUR_HOUR_BUCKET_COUNT];
        for (int hour = 0; hour < hourly.length; hour++) result[hour / 4] += hourly[hour];
        return result;
    }

    private static long[] hourBucket(int hour, long value)
    {
        long[] result = new long[DailyRollup.HOURLY_BUCKET_COUNT];
        result[hour] = value;
        return result;
    }

    private static ProfitSession categorySession(String name, SessionCategory category,
        long start, long end, long net)
    {
        ProfitSession session = new ProfitSession(name, start, SessionMode.AUTO);
        session.setCategoryOverride(category);
        session.configureAnalyticsTimeZone("UTC", false);
        session.setActivityHint(name, end);
        TransactionType type = net < 0L ? TransactionType.CONSUMPTION : TransactionType.LOOT;
        TrackingContext context = net < 0L ? TrackingContext.GENERIC : TrackingContext.LOOT;
        session.addTransaction(new ProfitTransaction(start + 1L, type, context, name,
            true, Collections.singletonList(new ItemFlow(name.hashCode(), name,
                net < 0L ? -1L : 1L, (int) Math.abs(net), net))), 100);
        session.close(end);
        return session;
    }

    private static GpManagerEngine engine()
    {
        return new GpManagerEngine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() { });
    }
}
