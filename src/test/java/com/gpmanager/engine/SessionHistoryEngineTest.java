package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.HistoryDateRange;
import com.gpmanager.model.HistoryQuery;
import com.gpmanager.model.HistorySort;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionCategory;
import com.gpmanager.model.SessionEndReason;
import com.gpmanager.model.SessionComparisonMetrics;
import com.gpmanager.model.SessionIntelligenceSnapshot;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.SessionOwnerKind;
import com.gpmanager.model.SessionSummary;
import com.gpmanager.model.RecordsSnapshot;
import com.gpmanager.model.SessionComparison;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionCorrection;
import com.gpmanager.model.TransactionType;
import com.gpmanager.persistence.SavedState;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionHistoryEngineTest
{
    private static final GpManagerConfig CONFIG = new GpManagerConfig()
    {
        @Override
        public int maxHistorySessions()
        {
            return 100;
        }
    };

    @Test
    public void restoredActiveSessionIsMarkedRecovered()
    {
        ProfitSession interrupted = session("Interrupted", TrackingContext.LOOT, TransactionType.LOOT, 100L);
        GpManagerEngine engine = engine();
        engine.restore(new SavedState(interrupted, Collections.emptyList()));

        assertNotNull(engine.getActiveSession());
        assertTrue(engine.getActiveSession().isRecoveredFromCrash());
    }

    @Test
    public void filtersEditsDeletesAndExcludesHistory()
    {
        ProfitSession pvm = session("Bossing", TrackingContext.LOOT, TransactionType.LOOT, 1_000L);
        pvm.close(3_600_000L);
        ProfitSession skilling = session("Fletching", TrackingContext.PRODUCTION, TransactionType.PROCESSING, 500L);
        skilling.close(3_600_000L);
        ProfitSession trading = session("Flipping", TrackingContext.MARKET, TransactionType.TRADE, -200L);
        trading.close(3_600_000L);

        GpManagerEngine engine = engine();
        engine.restore(new SavedState(null, Arrays.asList(pvm, skilling, trading)), 3_600_000L);

        assertEquals(1, engine.getFilteredHistory(SessionCategory.PVM).size());
        assertEquals(1, engine.getFilteredHistory(SessionCategory.SKILLING).size());
        assertEquals(1, engine.getFilteredHistory(SessionCategory.TRADING).size());

        assertTrue(engine.renameHistorySession(pvm.getId(), "Vorkath"));
        assertTrue(engine.setHistorySessionTags(pvm.getId(), "boss, pet hunt, boss"));
        assertEquals("Vorkath", engine.getHistorySession(pvm.getId()).getName());
        assertEquals("boss, pet hunt", engine.getHistorySession(pvm.getId()).getTagsDisplay());

        assertTrue(engine.setHistorySessionExcluded(trading.getId(), true));
        SessionComparisonMetrics comparison = engine.getSessionComparison(3_600_000L, 10);
        assertEquals(2, comparison.getSessionCount());
        assertEquals(750L, comparison.getAverageNet());

        assertTrue(engine.deleteHistorySession(skilling.getId()));
        assertNull(engine.getHistorySession(skilling.getId()));
        assertEquals(2, engine.getHistory().size());
    }

    @Test
    public void historicalCorrectionRecalculatesSummaryImmediately()
    {
        ProfitSession session = session("Review", TrackingContext.GENERIC, TransactionType.CONSUMPTION, -300L);
        session.close(60_000L);
        ProfitTransaction transaction = session.getTransactions().get(0);

        GpManagerEngine engine = engine();
        engine.restore(new SavedState(null, Collections.singletonList(session)), 60_000L);
        SessionSummary before = engine.getHistorySummary(session.getId(), 60_000L);
        assertEquals(-300L, before.getMetrics().getNet());

        assertTrue(engine.correctHistoryTransaction(
            session.getId(),
            transaction.getId(),
            TransactionCorrection.REVENUE,
            70_000L));
        SessionSummary after = engine.getHistorySummary(session.getId(), 70_000L);
        assertEquals(300L, after.getMetrics().getNet());
    }

    @Test
    public void historySummaryCarriesStripMetadataWithoutLeakingSession()
    {
        ProfitSession session = session("Vorkath", TrackingContext.LOOT, TransactionType.LOOT, 1_000L);
        session.setCategoryOverride(SessionCategory.BOSSING);
        session.setTags("blue dragon");
        session.setFavorite(true);
        session.setExcludedFromAverages(true);
        session.setOwnerKind(SessionOwnerKind.NAMED_SESSION);
        session.setEndReason(SessionEndReason.BOUNDARY);
        session.close(60_000L);

        GpManagerEngine engine = engine();
        engine.restore(new SavedState(null, Collections.singletonList(session)), 60_000L);

        SessionSummary summary = engine.getHistorySummary(session.getId(), 60_000L);
        assertEquals(SessionCategory.BOSSING, summary.getCategory());
        assertEquals(SessionEndReason.BOUNDARY, summary.getEndReason());
        assertEquals(SessionOwnerKind.NAMED_SESSION, summary.getOwnerKind());
        assertTrue(summary.isFavorite());
        assertTrue(summary.isExcludedFromAverages());
        assertFalse(summary.isAuto());
        assertNotNull(summary.getHighlight());
    }

    @Test
    public void recordsUseRetainedSessionsAndExcludeFreePlayAndExcludedRows()
    {
        ProfitSession best = session("Best", TrackingContext.LOOT, TransactionType.LOOT, 5_000L);
        best.setOwnerKind(SessionOwnerKind.NAMED_SESSION);
        best.close(60_000L);
        ProfitSession excluded = session("Excluded", TrackingContext.LOOT, TransactionType.LOOT, 50_000L);
        excluded.setOwnerKind(SessionOwnerKind.NAMED_SESSION);
        excluded.setExcludedFromAverages(true);
        excluded.close(60_000L);
        GpManagerEngine engine = engine();
        engine.restore(new SavedState(null, Arrays.asList(best, excluded)), 60_000L);

        RecordsSnapshot records = engine.getRecords(60_000L);
        assertTrue(records.getBestSessionNet().isAvailable());
        assertEquals(best.getId(), records.getBestSessionNet().getSessionId());
        assertEquals(best.getId(), records.getLongestSession().getSessionId());
        assertFalse(records.getBestSessionRate().isAvailable());
    }

    @Test
    public void pairwiseSessionComparisonCarriesIndependentAvailability()
    {
        ProfitSession left = session("Left", TrackingContext.LOOT, TransactionType.LOOT, 500L);
        ProfitSession right = session("Right", TrackingContext.LOOT, TransactionType.LOOT, 900L);
        left.close(60_000L); right.close(60_000L);
        GpManagerEngine engine = engine();
        engine.restore(new SavedState(null, Arrays.asList(left, right)), 60_000L);
        SessionComparison comparison = engine.compareSessions(left.getId(), right.getId(), 60_000L);
        assertTrue(comparison.isAvailable());
        assertEquals(Long.valueOf(500L), comparison.getLeftNet());
        assertEquals(Long.valueOf(900L), comparison.getRightNet());
    }

    @Test
    public void searchesSortsFavoritesAndBuildsLifetimeIntelligence()
    {
        long day = 24L * 60L * 60L * 1000L;
        long now = 100L * day;
        ProfitSession pvm = sessionAt("Vorkath", now - day, TrackingContext.LOOT, TransactionType.LOOT, 1_000L);
        pvm.setTags("boss, blue dragon");
        pvm.setNotes("pet hunt");
        pvm.setFavorite(true);
        ProfitSession skilling = sessionAt("Fletching", now - 10L * day, TrackingContext.PRODUCTION, TransactionType.PROCESSING, 500L);
        skilling.setNotes("afk bows");
        ProfitSession trading = sessionAt("Flipping", now - 40L * day, TrackingContext.MARKET, TransactionType.TRADE, -200L);

        GpManagerEngine engine = engine();
        engine.restore(new SavedState(null, Arrays.asList(pvm, skilling, trading)), now);

        assertEquals(1, engine.getHistory(new HistoryQuery(
            SessionCategory.ALL, "pet", HistoryDateRange.ALL_TIME, HistorySort.NEWEST, false), now).size());
        assertEquals(1, engine.getHistory(new HistoryQuery(
            SessionCategory.ALL, "", HistoryDateRange.ALL_TIME, HistorySort.NEWEST, true), now).size());
        assertEquals(2, engine.getHistory(new HistoryQuery(
            SessionCategory.ALL, "", HistoryDateRange.LAST_30_DAYS, HistorySort.NEWEST, false), now).size());
        assertEquals("Vorkath", engine.getHistory(new HistoryQuery(
            SessionCategory.ALL, "", HistoryDateRange.ALL_TIME, HistorySort.PROFIT_HIGH, false), now).get(0).getName());

        SessionIntelligenceSnapshot intelligence = engine.getSessionIntelligence(now, 2);
        assertEquals(2, intelligence.getRecent().getSessionCount());
        assertEquals(3, intelligence.getLifetime().getSessionCount());
        assertEquals(750L, intelligence.getRecent().getAverageNet());
        assertEquals(433L, intelligence.getLifetime().getAverageNet());
        assertEquals(2, intelligence.getRecentNetTrend().size());
        assertFalse(intelligence.getActivityMetrics().isEmpty());
    }

    private static GpManagerEngine engine()
    {
        return new GpManagerEngine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            CONFIG);
    }

    private static ProfitSession sessionAt(
        String name, long start, TrackingContext context, TransactionType type, long value)
    {
        ProfitSession session = new ProfitSession(name, start, SessionMode.GENERAL);
        long quantity = value < 0L ? -1L : 1L;
        session.addTransaction(new ProfitTransaction(
            start + 1_000L, 1_000L, type, context, name, name, true,
            Collections.singletonList(new ItemFlow(1, "Item", quantity, (int) Math.abs(value), value))), 100);
        session.close(start + 3_600_000L);
        return session;
    }

    private static ProfitSession session(
        String name,
        TrackingContext context,
        TransactionType type,
        long value)
    {
        ProfitSession session = new ProfitSession(name, 0L, SessionMode.GENERAL);
        long quantity = value < 0L ? -1L : 1L;
        long absolute = Math.abs(value);
        session.addTransaction(
            new ProfitTransaction(
                1_000L,
                1_000L,
                type,
                context,
                name,
                name,
                true,
                Collections.singletonList(new ItemFlow(1, "Item", quantity, (int) absolute, value))),
            100);
        return session;
    }
}
