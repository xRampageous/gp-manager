package com.gpmanager.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.ActivityAverageSnapshot;
import com.gpmanager.model.DailyRollup;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionCategory;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.persistence.SavedState;
import java.util.Arrays;
import java.util.Collections;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionCategoryEngineTest
{
    @Test
    public void restoreMigratesSidebarTagsAndIsIdempotent()
    {
        long now = 10_000L;
        ProfitSession active = new ProfitSession("Active", now - 1L, SessionMode.AUTO);
        active.setTags("pvp, favourite");
        ProfitSession archived = new ProfitSession("Archived", now - 2L, SessionMode.AUTO);
        archived.setTags("raids");
        SavedState old = new SavedState(active, Collections.singletonList(archived));
        old.setSchemaVersion(18);
        old.setProfileTimeZoneId("UTC");
        old.setSavedAtEpochMillis(now);

        GpManagerEngine engine = engine();
        engine.restore(old, now);
        assertEquals(SessionCategory.PKING, engine.getActiveSession().getCategory());
        assertEquals(SessionMode.PK, engine.getActiveSession().getMode());
        assertEquals(SessionCategory.RAIDS, engine.getHistory().get(0).getCategory());
        assertEquals("pvp, favourite", engine.getActiveSession().getTagsDisplay());

        engine.restore(old, now + 1L);
        assertEquals(SessionCategory.PKING, engine.getActiveSession().getCategory());
        assertEquals(SessionCategory.RAIDS, engine.getHistory().get(0).getCategory());
    }

    @Test
    public void oldSchemaWithoutCategoryTagLeavesOverrideNull()
    {
        long now = 10_000L;
        ProfitSession oldSession = new ProfitSession("Legacy", now - 1L, SessionMode.AUTO);
        JsonObject json = new Gson().toJsonTree(oldSession).getAsJsonObject();
        assertFalse("null legacy field is not serialized", json.has("categoryOverride"));
        ProfitSession restored = new Gson().fromJson(json, ProfitSession.class);
        SavedState state = new SavedState(restored, Collections.emptyList());
        state.setSchemaVersion(18);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);

        GpManagerEngine engine = engine();
        engine.restore(state, now);

        assertNull(engine.getActiveSession().getCategoryOverride());
        assertEquals(SessionCategory.GENERAL, engine.getActiveSession().getCategory());
    }

    @Test
    public void activeAndHistorySettersValidateAndPersistCategoryOverrides()
    {
        long now = 10_000L;
        ProfitSession active = new ProfitSession("Active", now - 2L, SessionMode.AUTO);
        ProfitSession archived = new ProfitSession("Archived", now - 1L, SessionMode.AUTO);
        SavedState state = new SavedState(active, Collections.singletonList(archived));
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(now);
        GpManagerEngine engine = engine();
        engine.restore(state, now);

        assertTrue(engine.setActiveSessionCategory(SessionCategory.BOSSING));
        assertEquals(SessionCategory.BOSSING, engine.getActiveSession().getCategory());
        assertTrue(engine.setHistorySessionCategory(archived.getId(), SessionCategory.SLAYER));
        assertEquals(SessionCategory.SLAYER, engine.getHistory().get(0).getCategory());
        assertFalse(engine.setHistorySessionCategory("missing", SessionCategory.OTHER));

        assertTrue(engine.setHistorySessionCategory(archived.getId(), SessionCategory.PKING));
        assertEquals(SessionMode.PK, engine.getHistory().get(0).getMode());
        assertFalse(engine.setHistorySessionCategory(archived.getId(), SessionCategory.SKILLING));
    }

    @Test
    public void categoryAverageIsTimeWeightedAndHonorsExclusions()
    {
        long start = 1_000_000L;
        long end = start + 3_600_000L;
        ProfitSession session = new ProfitSession("Boss trip", start, SessionMode.AUTO);
        session.setCategoryOverride(SessionCategory.BOSSING);
        session.configureAnalyticsTimeZone("UTC", false);
        session.setActivityHint("Bossing", end);
        session.addTransaction(new ProfitTransaction(2_000_000L, TransactionType.LOOT,
            TrackingContext.LOOT, "Loot", true,
            Collections.singletonList(new ItemFlow(1, "Loot", 1L, 300, 300L))), 100);
        session.addTransaction(new ProfitTransaction(2_100_000L, TransactionType.CONSUMPTION,
            TrackingContext.GENERIC, "Food", true,
            Collections.singletonList(new ItemFlow(2, "Food", -1L, 50, -50L))), 100);
        session.close(end);
        SavedState state = new SavedState(null, null, false, Collections.singletonList(session));
        state.setSchemaVersion(SavedState.CURRENT_SCHEMA_VERSION);
        state.setProfileTimeZoneId("UTC");
        state.setSavedAtEpochMillis(end);
        GpManagerEngine engine = engine();
        engine.restore(state, end);

        ActivityAverageSnapshot average = engine.getCategoryAverage(SessionCategory.BOSSING, null);
        assertEquals(SessionCategory.BOSSING, average.getCategory());
        assertEquals(1, average.getSessionsCounted());
        assertEquals(Long.valueOf(250L), average.getTotalNetGp());
        assertEquals(Double.valueOf(250.0d), average.getMedianSessionNetGp());
        assertEquals(3_600_000L, average.getKnownActiveMillis());
        assertEquals(Long.valueOf(250L), average.getTimeWeightedGpPerHour());
        assertTrue(average.isCoverageComplete());

        ActivityAverageSnapshot excluded = engine.getCategoryAverage(
            SessionCategory.BOSSING, session.getId());
        assertEquals(0, excluded.getSessionsCounted());
        assertNull(excluded.getTotalNetGp());
    }

    @Test
    public void unavailableLegacyCategoryRollupMakesAbsenceUnknown()
    {
        long now = 10_000L;
        SavedState state = new SavedState();
        state.setSchemaVersion(18);
        state.setProfileTimeZoneId("UTC");
        state.setDailyRollups(Collections.singletonList(DailyRollup.builder(
            LocalDate.of(1970, 1, 1), ZoneId.of("UTC"))
            .addAccounting(500L, 0L)
            .build()));
        GpManagerEngine engine = engine();
        engine.restore(state, now);

        ActivityAverageSnapshot average = engine.getCategoryAverage(SessionCategory.BOSSING, null);
        assertFalse(average.isCoverageComplete());
        assertNull(average.getTotalNetGp());
        assertEquals("PARTIAL_CATEGORY_HISTORY", average.getStatus());
    }

    private static GpManagerEngine engine()
    {
        return new GpManagerEngine(deltas -> Collections.emptyList(), new TransactionClassifier(),
            new GpManagerConfig() { });
    }
}
