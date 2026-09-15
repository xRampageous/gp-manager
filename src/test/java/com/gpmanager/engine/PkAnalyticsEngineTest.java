package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.LootPresentationFilter;
import com.gpmanager.grounditems.GroundItemsConfigSnapshot;
import com.gpmanager.grounditems.LootPresentationFilterService;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.DailyRollup;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.PkEncounter;
import com.gpmanager.model.PkEncounterType;
import com.gpmanager.model.PkMetrics;
import com.gpmanager.model.PkPlaceSummaries;
import com.gpmanager.model.PkWindow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.SessionSummary;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.persistence.SavedState;
import com.google.gson.Gson;
import java.awt.Color;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PkAnalyticsEngineTest
{
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override public int stabilizationTicks() { return 2; }
        @Override public boolean keepTransferAuditRows() { return true; }
    };

    @Test
    public void placeSummarySeparatesUnknownTimeAndAttributesOnlyEncounterNet()
    {
        GpManagerEngine engine = engine(CONFIG);
        long start = Instant.parse("2026-09-14T11:59:00Z").toEpochMilli();
        long now = start + 60_000L;
        engine.ensureSession(start);
        ProfitSession session = engine.getActiveSession();
        session.setMode(SessionMode.PK);
        engine.updatePlayerWorldLocation(100, 200, 0, "Wilderness", start + 10_000L);
        engine.markPkLootContext(Collections.emptyMap(), 10, "Kill: Rival", start + 20_000L);
        addEncounterValue(session, session.getPkEncounters().get(0), 1_000L);
        engine.updatePlayerWorldLocation(100, 201, 0, null, start + 40_000L);

        PkPlaceSummaries summary = engine.getPkPlaceSummaries(1, now);

        assertEquals(1, summary.getPlaces().size());
        assertEquals("Wilderness", summary.getPlaces().get(0).getLocationLabel());
        assertEquals(1, summary.getPlaces().get(0).getKills());
        assertEquals(1_000L, summary.getPlaces().get(0).getNetGp());
        assertEquals(30_000L, summary.getPlaces().get(0).getActiveMillis());
        assertEquals(30_000L, summary.getUnlabelledMillis());
        assertTrue(summary.isActiveTimeAvailable());
        assertTrue(summary.isEncounterCountsAvailable());
        assertTrue(summary.isFinanceAvailable());
    }

    @Test
    public void filteredPlaceFinanceIsRedactedRatherThanReturningRawEncounterValues()
    {
        GpManagerConfig filteredConfig = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 2; }
            @Override public boolean keepTransferAuditRows() { return true; }
            @Override public LootPresentationFilter accountingItemFilter()
            {
                return LootPresentationFilter.HIGHLIGHTED_LIST_ONLY;
            }
        };
        GpManagerEngine engine = engine(filteredConfig);
        engine.setContributionEligibility(new LootPresentationFilterService(
            new GroundItemsConfigSnapshot(true, "Feather", "Bones", false, true, 0,
                GroundItemsConfigSnapshot.ValueMode.HIGHEST, Color.MAGENTA, Color.WHITE,
                Color.GRAY, Collections.emptyList())));
        long start = Instant.parse("2026-09-14T11:59:00Z").toEpochMilli();
        engine.ensureSession(start);
        ProfitSession session = engine.getActiveSession();
        session.setMode(SessionMode.PK);
        engine.updatePlayerWorldLocation(10, 10, 0, "Wilderness", start + 1L);
        engine.markPkLootContext(Collections.emptyMap(), 10, "Kill: Rival", start + 2L);
        PkEncounter encounter = session.getPkEncounters().get(0);
        ProfitTransaction hidden = new ProfitTransaction(start + 3L, TransactionType.PK_LOOT,
            TrackingContext.PK_LOOT, "", true,
            Collections.singletonList(new ItemFlow(526, "Bones", 1L, 10_000, 10_000L)));
        session.addTransaction(hidden, 100);
        session.attachTransactionToEncounter(hidden.getId(), encounter.getId(), false);

        PkPlaceSummaries summary = engine.getPkPlaceSummaries(1, start + 10_000L);

        assertEquals(1, summary.getPlaces().size());
        assertEquals(0L, summary.getPlaces().get(0).getNetGp());
        assertEquals(0L, summary.getPlaces().get(0).getAttachedSuppliesGp());
        assertEquals(1, summary.getPlaces().get(0).getKills());
        assertEquals(0, summary.getPlaces().get(0).getDeaths());
        assertEquals(start + 2L, summary.getPlaces().get(0).getFirstAtEpochMillis());
        assertEquals(start + 2L, summary.getPlaces().get(0).getLastAtEpochMillis());
        assertTrue(summary.getPlaces().get(0).isEncounterCountsAvailable());
        assertTrue(summary.isEncounterCountsAvailable());
        assertFalse(summary.getPlaces().get(0).isFinanceAvailable());
        assertFalse(summary.isFinanceAvailable());
    }

    @Test
    public void unavailableEncounterFinanceStillRetainsExactPlaceCounts()
    {
        long start = Instant.parse("2026-09-14T11:59:00Z").toEpochMilli();
        long at = start + 2_000L;
        long now = start + 10_000L;
        ProfitSession session = new ProfitSession("Legacy PK trip", start, SessionMode.PK);
        PkEncounter encounter = session.addPkEncounter(PkEncounterType.DEATH, at,
            "Death: Rival", ClassificationConfidence.CONFIRMED, "legacy finance");
        encounter.setLocationLabel("Wilderness");
        com.google.gson.JsonObject sessionJson = new Gson().toJsonTree(session).getAsJsonObject();
        sessionJson.getAsJsonArray("pkEncounters").get(0).getAsJsonObject()
            .addProperty("financialSummaryVersion", 0);
        ProfitSession restored = new Gson().fromJson(sessionJson, ProfitSession.class);
        SavedState state = new SavedState(restored, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        GpManagerEngine engine = engine(CONFIG);
        engine.restore(state, now);

        PkPlaceSummaries summary = engine.getPkPlaceSummaries(1, now);

        assertEquals(1, summary.getPlaces().size());
        assertEquals(0, summary.getPlaces().get(0).getKills());
        assertEquals(1, summary.getPlaces().get(0).getDeaths());
        assertEquals(at, summary.getPlaces().get(0).getFirstAtEpochMillis());
        assertEquals(at, summary.getPlaces().get(0).getLastAtEpochMillis());
        assertTrue(summary.getPlaces().get(0).isEncounterCountsAvailable());
        assertTrue(summary.isEncounterCountsAvailable());
        assertFalse(summary.getPlaces().get(0).isFinanceAvailable());
    }

    @Test
    public void filteredLocationOnlyPlaceRowDoesNotClaimFinanceCoverage()
    {
        GpManagerConfig filteredConfig = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 2; }
            @Override public boolean keepTransferAuditRows() { return true; }
            @Override public LootPresentationFilter accountingItemFilter()
            {
                return LootPresentationFilter.HIGHLIGHTED_LIST_ONLY;
            }
        };
        GpManagerEngine engine = engine(filteredConfig);
        engine.setContributionEligibility(new LootPresentationFilterService(
            new GroundItemsConfigSnapshot(true, "Feather", "Bones", false, true, 0,
                GroundItemsConfigSnapshot.ValueMode.HIGHEST, Color.MAGENTA, Color.WHITE,
                Color.GRAY, Collections.emptyList())));
        long start = Instant.parse("2026-09-14T11:59:00Z").toEpochMilli();
        engine.ensureSession(start);
        engine.getActiveSession().setMode(SessionMode.PK);
        engine.updatePlayerWorldLocation(10, 10, 0, "Wilderness", start + 1L);

        PkPlaceSummaries summary = engine.getPkPlaceSummaries(1, start + 10_000L);

        assertEquals(1, summary.getPlaces().size());
        assertEquals("Wilderness", summary.getPlaces().get(0).getLocationLabel());
        assertFalse(summary.getPlaces().get(0).isFinanceAvailable());
        assertFalse(summary.isFinanceAvailable());
    }

    @Test
    public void unavailableEncounterOutsideWindowDoesNotHideExactInWindowDetails()
    {
        long priorAt = Instant.parse("2026-09-13T12:00:00Z").toEpochMilli();
        long currentAt = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        long now = currentAt + 60 * 60_000L;
        ProfitSession session = new ProfitSession("Two-day PK trip", priorAt - 1_000L,
            SessionMode.PK);
        PkEncounter legacy = session.addPkEncounter(PkEncounterType.KILL, priorAt,
            "Kill: Prior rival", ClassificationConfidence.CONFIRMED, "legacy evidence");
        legacy.setLocationLabel("Wilderness");
        addEncounterValue(session, legacy, 100L);
        PkEncounter current = session.addPkEncounter(PkEncounterType.KILL, currentAt,
            "Kill: Current rival", ClassificationConfidence.CONFIRMED, "current evidence");
        current.setLocationLabel("Wilderness");
        addEncounterValue(session, current, 1_000L);

        Gson gson = new Gson();
        com.google.gson.JsonObject legacyJson = gson.toJsonTree(session).getAsJsonObject();
        legacyJson.getAsJsonArray("pkEncounters").get(0).getAsJsonObject()
            .addProperty("financialSummaryVersion", 0);
        ProfitSession restored = gson.fromJson(legacyJson, ProfitSession.class);
        SavedState state = new SavedState(restored, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        GpManagerEngine engine = engine(CONFIG);
        engine.restore(state, now);

        PkWindow window = engine.getPkWindow(1, now).getCurrent().getPkWindow();

        assertTrue(window.isDetailsAvailable());
        assertEquals(1, window.getKills());
        assertEquals(1, window.getBestKills().size());
        assertEquals("Current rival", window.getBestKills().get(0).getOpponentName());
        assertEquals(1_000L, window.getBestKills().get(0).getValueGp());
    }

    @Test
    public void persistedPkRollupWithTrimmedSourceMakesPlaceCoverageUnavailable()
    {
        long now = Instant.parse("2026-09-14T23:00:00Z").toEpochMilli();
        DailyRollup trimmedPkDay = DailyRollup.builder(
            LocalDate.of(2026, 9, 14), ZoneId.of("UTC"))
            .addSourceSessionId("trimmed-pk-owner")
            .addPvp(1, 0, 1_000L, 0L, 1_000L, 0L)
            .build();
        DailyRollup trimmedPkTimeDay = DailyRollup.builder(
            LocalDate.of(2026, 9, 13), ZoneId.of("UTC"))
            .addSourceSessionId("trimmed-pk-time-owner")
            .addCategory(com.gpmanager.model.SessionCategory.PKING, 0L, 120_000L)
            .build();
        SavedState state = new SavedState(null, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        state.setDailyRollups(java.util.Arrays.asList(trimmedPkDay, trimmedPkTimeDay));
        GpManagerEngine engine = engine(CONFIG);
        engine.restore(state, now);

        PkPlaceSummaries summary = engine.getPkPlaceSummaries(2, now);

        assertFalse(summary.isFinanceAvailable());
        assertFalse(summary.isActiveTimeAvailable());
        assertFalse(summary.isEncounterCountsAvailable());
    }

    @Test
    public void mixedTrimmedPkAndRetainedNonPkSameDayFailsPlaceCoverage()
    {
        long now = Instant.parse("2026-09-14T23:00:00Z").toEpochMilli();
        ProfitSession retainedNonPk = new ProfitSession("Retained skilling",
            now - 60_000L, SessionMode.AUTO);
        DailyRollup trimmedPk = DailyRollup.builder(
            LocalDate.of(2026, 9, 14), ZoneId.of("UTC"))
            .addSourceSessionId("trimmed-pk-owner")
            .addCategory(com.gpmanager.model.SessionCategory.PKING, 0L, 120_000L)
            .addPvp(1, 0, 1_000L, 0L, 1_000L, 0L)
            .build();
        SavedState state = new SavedState(retainedNonPk, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        state.setDailyRollups(Collections.singletonList(trimmedPk));
        GpManagerEngine engine = engine(CONFIG);
        engine.restore(state, now);

        PkPlaceSummaries summary = engine.getPkPlaceSummaries(1, now);
        PkWindow pkWindow = engine.getPkWindow(1, now).getCurrent().getPkWindow();

        assertFalse(summary.isFinanceAvailable());
        assertFalse(summary.isActiveTimeAvailable());
        assertFalse(summary.isEncounterCountsAvailable());
        assertFalse(pkWindow.isAggregateComplete());
        assertFalse(pkWindow.isDetailsAvailable());
    }

    @Test
    public void retainedPartialPkDayDoesNotHideTrimmedPkSourceOnLaterDay()
    {
        long firstAt = Instant.parse("2026-09-13T12:00:00Z").toEpochMilli();
        long laterAt = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        long now = Instant.parse("2026-09-14T23:00:00Z").toEpochMilli();
        ProfitSession retained = new ProfitSession("Retained partial PK", firstAt - 1_000L,
            SessionMode.PK);
        PkEncounter encounter = retained.addPkEncounter(PkEncounterType.DEATH, firstAt,
            "Death: Rival", ClassificationConfidence.CONFIRMED, "retained event count");
        encounter.setLocationLabel("Wilderness");
        retained.close(firstAt + 1_000L);
        com.google.gson.JsonObject retainedJson = new Gson().toJsonTree(retained).getAsJsonObject();
        retainedJson.getAsJsonArray("pkEncounters").get(0).getAsJsonObject()
            .addProperty("financialSummaryVersion", 0);
        retained = new Gson().fromJson(retainedJson, ProfitSession.class);

        DailyRollup laterTrimmedPk = DailyRollup.builder(
            LocalDate.of(2026, 9, 14), ZoneId.of("UTC"))
            .addSourceSessionId("trimmed-later-pk-owner")
            .addPvp(1, 0, 500L, 0L, 500L, 0L)
            .build();
        SavedState state = new SavedState(retained, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        state.setDailyRollups(Collections.singletonList(laterTrimmedPk));
        GpManagerEngine engine = engine(CONFIG);
        engine.restore(state, now);

        PkPlaceSummaries summary = engine.getPkPlaceSummaries(2, now);

        assertFalse(summary.isEncounterCountsAvailable());
        assertFalse(summary.isFinanceAvailable());
        assertFalse(summary.isActiveTimeAvailable());
    }

    @Test
    public void overlappingLegacyZoneRollupMakesPlaceCoverageUnavailable()
    {
        long now = Instant.parse("2026-09-14T23:00:00Z").toEpochMilli();
        DailyRollup legacyZonePk = DailyRollup.builder(
            LocalDate.of(2026, 9, 14), ZoneId.of("America/New_York"))
            .addPvp(1, 0, 1_000L, 0L, 1_000L, 0L)
            .build();
        SavedState state = new SavedState(null, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        state.setDailyRollups(Collections.singletonList(legacyZonePk));
        GpManagerEngine engine = engine(CONFIG);
        engine.restore(state, now);

        PkPlaceSummaries summary = engine.getPkPlaceSummaries(1, now);

        assertFalse(summary.isFinanceAvailable());
        assertFalse(summary.isActiveTimeAvailable());
        assertFalse(summary.isEncounterCountsAvailable());
    }

    @Test
    public void pkCategoryOnlyRowWithoutSourceIdsDoesNotEnableWindowDetails()
    {
        long now = Instant.parse("2026-09-14T23:00:00Z").toEpochMilli();
        DailyRollup pkCategoryOnly = DailyRollup.builder(
            LocalDate.of(2026, 9, 14), ZoneId.of("UTC"))
            .addCategory(com.gpmanager.model.SessionCategory.PKING, 0L, 120_000L)
            .addPvp(0, 0, 0L, 0L, 0L, 0L)
            .build();
        SavedState state = new SavedState(null, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        state.setDailyRollups(Collections.singletonList(pkCategoryOnly));
        GpManagerEngine engine = engine(CONFIG);
        engine.restore(state, now);

        PkWindow window = engine.getPkWindow(1, now).getCurrent().getPkWindow();

        assertTrue(window.isAggregateComplete());
        assertFalse(window.isDetailsAvailable());
        assertTrue(window.getBestKills().isEmpty());
    }

    @Test
    public void legacyZonePvpCoverageDoesNotExposeDetailsFromOnlyTheLocalZoneSubset()
    {
        long currentAt = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        long now = currentAt + 60 * 60_000L;
        ProfitSession currentSession = pkSession("Current trip", currentAt - 1_000L,
            currentAt, "Current rival", 1_000L);
        DailyRollup legacyZonePvp = DailyRollup.builder(
            LocalDate.of(2026, 9, 14), ZoneId.of("America/New_York"))
            .addSourceSessionId("legacy-zone-pk-owner")
            .addPvp(1, 0, 500L, 0L, 500L, 0L)
            .build();
        SavedState state = new SavedState(currentSession, Collections.emptyList());
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        state.setDailyRollups(Collections.singletonList(legacyZonePvp));
        GpManagerEngine engine = engine(CONFIG);
        engine.restore(state, now);

        PkWindow window = engine.getPkWindow(1, now).getCurrent().getPkWindow();

        assertFalse(window.isAggregateComplete());
        assertFalse(window.isDetailsAvailable());
        assertTrue(window.getBestKills().isEmpty());
    }

    @Test
    public void currentAndPreviousPkWindowsDoNotMixEventsAndPreviousSessionIsExcludable()
    {
        long previousAt = Instant.parse("2026-09-13T12:00:00Z").toEpochMilli();
        long currentAt = Instant.parse("2026-09-14T12:00:00Z").toEpochMilli();
        long now = Instant.parse("2026-09-14T23:00:00Z").toEpochMilli();
        ProfitSession previous = pkSession("Previous trip", previousAt - 1_000L,
            previousAt, "Old rival", 100L);
        ProfitSession current = pkSession("Current trip", currentAt - 1_000L,
            currentAt, "New rival", 1_000L);
        SavedState state = new SavedState(current, Collections.singletonList(previous));
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        GpManagerEngine engine = engine(CONFIG);
        engine.restore(state, now);

        PkWindow currentWindow = engine.getPkWindow(1, now).getCurrent().getPkWindow();
        PkWindow previousWindow = engine.getPkWindow(1, now).getPrevious().getPkWindow();

        assertEquals(1, currentWindow.getKills());
        assertEquals(1_000L, currentWindow.getKillNetGp());
        assertEquals(1, currentWindow.getBestKills().size());
        assertEquals("New rival", currentWindow.getBestKills().get(0).getOpponentName());
        assertEquals(1, previousWindow.getKills());
        assertEquals(100L, previousWindow.getKillNetGp());
        assertEquals("Old rival", previousWindow.getBestKills().get(0).getOpponentName());

        SessionSummary prior = engine.getPreviousPkSessionSummary(current.getId());
        assertNotNull(prior);
        assertEquals(previous.getId(), prior.getSessionId());
        assertEquals(100L, prior.getPkMetrics().getNet());
    }

    private static ProfitSession pkSession(String name, long start, long at,
        String opponent, long value)
    {
        ProfitSession session = new ProfitSession(name, start, SessionMode.PK);
        PkEncounter encounter = session.addPkEncounter(PkEncounterType.KILL, at,
            "Kill: " + opponent, ClassificationConfidence.CONFIRMED, "test");
        encounter.setLocationLabel("Wilderness");
        addEncounterValue(session, encounter, value);
        session.close(at + 1_000L);
        return session;
    }

    private static void addEncounterValue(ProfitSession session, PkEncounter encounter, long gp)
    {
        ProfitTransaction transaction = new ProfitTransaction(
            encounter.getTimestampEpochMillis(), TransactionType.PK_LOOT,
            TrackingContext.PK_LOOT, "", true,
            Collections.singletonList(new ItemFlow(995, "Coins", 1L, (int) gp, gp)));
        session.addTransaction(transaction, 100);
        session.attachTransactionToEncounter(transaction.getId(), encounter.getId(), false);
    }

    private static GpManagerEngine engine(GpManagerConfig config)
    {
        return new GpManagerEngine(deltas -> Collections.emptyList(),
            new TransactionClassifier(), config);
    }
}
