package com.gpmanager.engine;

import com.gpmanager.*;
import com.gpmanager.grounditems.*;
import com.gpmanager.model.*;
import com.gpmanager.persistence.SavedState;
import java.awt.Color;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * One eligibility path: Insights, corrections and compaction must exclude exactly the
 * items the live filter hides, and a burial settling across a bank-close callback must
 * not latch stale transfer evidence. Review probes (September 2026) kept under the
 * subject they test.
 */
public class HiddenItemExclusionConsistencyTest
{
    private GpManagerEngine engine()
    {
        GpManagerConfig config = new GpManagerConfig() {
            public int stabilizationTicks() { return 2; }
            public boolean keepTransferAuditRows() { return true; }
            public LootPresentationFilter accountingItemFilter() { return LootPresentationFilter.HIGHLIGHTED_LIST_ONLY; }
        };
        GpManagerEngine engine = new GpManagerEngine(deltas -> {
            List<ItemFlow> flows = new ArrayList<>();
            deltas.forEach((id, quantity) -> flows.add(new ItemFlow(id, "Bones", quantity, 35, quantity * 35L)));
            return flows;
        }, new TransactionClassifier(), config);
        engine.ensureSession(1000L);
        return engine;
    }

    private void onlyFeathers(GpManagerEngine engine)
    {
        engine.setContributionEligibility(new LootPresentationFilterService(new GroundItemsConfigSnapshot(
            true, "Feather", "Bones", false, true, 0, GroundItemsConfigSnapshot.ValueMode.HIGHEST,
            Color.MAGENTA, Color.WHITE, Color.GRAY, Collections.emptyList())));
    }

    private ProfitTransaction gain(ItemFlow... flows)
    {
        return new ProfitTransaction(1600L, TransactionType.GAIN, TrackingContext.GENERIC,
            "Pickup", true, Arrays.asList(flows));
    }

    @Test public void burialCallbackBeforeGameTickBankCloseMustNotLatchOldTransfer()
    {
        GpManagerEngine engine = engine();
        ContainerSnapshot bones = new ContainerSnapshot(Collections.singletonMap(526, 1L));
        engine.setBaseline(bones);
        engine.markBankInterfaceOpen(6);
        engine.processIfDirty(bones, 1000L);
        engine.markInventoryDirty();
        engine.markBankInterfaceClosed();
        engine.processIfDirty(ContainerSnapshot.empty(), 1600L);
        engine.processIfDirty(ContainerSnapshot.empty(), 2200L);
        ProfitTransaction tx = engine.processIfDirty(ContainerSnapshot.empty(), 2800L);
        assertEquals(TransactionType.CONSUMPTION, tx.getType());
    }

    @Test public void insightsMustUseSameHiddenItemExclusionAsLive()
    {
        GpManagerEngine engine = engine();
        onlyFeathers(engine);
        engine.getActiveSession().addTransaction(gain(new ItemFlow(526, "Bones", 1L, 35, 35L)), 100);
        assertEquals(0L, engine.getMetrics(2800L).getNet());
        assertFalse(engine.getMetrics(2800L).isActionCountAvailable());
        assertEquals("UNAVAILABLE_DAILY_ITEM_ATTRIBUTION", engine.getTrackingInsightsProjectionStatus());
        assertFalse(engine.getTrackingInsights(0, 2800L).isProjectionAvailable());
        assertEquals("UNAVAILABLE_DAILY_ITEM_ATTRIBUTION",
            engine.getTrackingInsights(0, 2800L).getProjectionStatus());
        assertTrue(engine.getTrackingInsightsTrend(30, 2800L).isEmpty());
    }

    @Test public void durableInsightsMustNotExposeRawValuesUnderAccountingFilter()
    {
        GpManagerEngine engine = engine();
        onlyFeathers(engine);
        ProfitSession session = engine.getActiveSession();
        session.addTransaction(gain(new ItemFlow(526, "Bones", 1L, 35, 35L)), 100);
        session.addTransaction(gain(new ItemFlow(314, "Feather", 1L, 10, 10L)), 100);
        session.addTransaction(new ProfitTransaction(1800L, TransactionType.CONSUMPTION,
            TrackingContext.GENERIC, "Consume", true,
            Collections.singletonList(new ItemFlow(526, "Bones", -1L, 35, -35L))), 100);

        DailyRollup day = engine.getDailyRollups(LocalDate.of(1970, 1, 1),
            LocalDate.of(1970, 1, 2)).get(0);
        assertEquals(0L, day.getRevenueGp());
        assertEquals(0L, day.getCostsGp());
        assertEquals(0L, day.getNetGp());
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            day.getCoverage(DailyRollup.Dimension.ACCOUNTING));
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            day.getCoverage(DailyRollup.Dimension.COST_SPLIT));
        assertTrue(day.getGainedItemTotals().isEmpty());
        assertTrue(day.getCostItemTotals().isEmpty());
        day.getActivities().values().forEach(activity -> assertEquals(0L, activity.getNetGp()));
        for (long amount : day.getFourHourNetGp()) assertEquals(0L, amount);

        InsightsWindowSnapshot.Window current = engine.getInsightsWindow(1, 2800L).getCurrent();
        assertEquals(0L, current.getRevenueGp());
        assertEquals(0L, current.getCostsGp());
        assertTrue(current.getGainedItemTotals().isEmpty());
        assertEquals(DailyRollup.Coverage.UNAVAILABLE,
            current.getCoverage(DailyRollup.Dimension.ACCOUNTING));
        SavedState saved = engine.createSavedState();
        assertEquals(45L, saved.getDailyRollups().get(0).getRevenueGp());
        assertFalse(saved.getDailyRollups().get(0).getGainedItemTotals().isEmpty());
        SessionNetTrendEntry trend = engine.getSessionNetTrend(1, 2800L).get(0);
        assertEquals(10L, trend.getNetGp());
        assertTrue(trend.isNetAvailable());
    }

    @Test public void correctionMustNotRestoreHiddenPortionOfMixedTransaction()
    {
        GpManagerEngine engine = engine();
        onlyFeathers(engine);
        ProfitTransaction tx = gain(new ItemFlow(526, "Bones", 1L, 35, 35L),
            new ItemFlow(314, "Feather", 1L, 10, 10L));
        engine.getActiveSession().addTransaction(tx, 100);
        assertEquals(10L, engine.getMetrics(2800L).getRevenue());
        engine.getActiveSession().correctTransaction(tx.getId(), TransactionCorrection.REVENUE, 3000L);
        assertEquals(10L, engine.getMetrics(3100L).getRevenue());
    }

    @Test public void compactionMustNotReintroduceHiddenItemProfit()
    {
        GpManagerEngine engine = engine();
        onlyFeathers(engine);
        ProfitSession session = engine.getActiveSession();
        session.addTransaction(gain(new ItemFlow(526, "Bones", 1L, 35, 35L)), 1);
        session.addTransaction(gain(new ItemFlow(526, "Bones", 1L, 35, 35L)), 1);
        assertTrue(session.getCompactedTransactionCount() > 0L);
        assertEquals(0L, engine.getMetrics(2800L).getNet());
        SessionNetTrendEntry trend = engine.getSessionNetTrend(1, 2800L).get(0);
        assertEquals(0L, trend.getNetGp());
        assertTrue(trend.isNetAvailable());
        assertTrue(engine.getSessionIntelligence(2800L, 2).getProjectionStatus()
            .startsWith("UNAVAILABLE_ACTIVE_SESSION_DETAIL"));
        assertFalse(session.metrics(2800L, 60_000L).isRollingRateAvailable());
    }
}
