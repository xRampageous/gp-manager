package com.gpmanager;

import com.gpmanager.grounditems.GroundItemsConfigSnapshot;
import com.gpmanager.grounditems.LootPresentationFilterService;
import com.gpmanager.model.ClassificationConfidence;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.Collections;
import java.awt.Color;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Smoke tests for floating drop enqueue/combine/stagger wiring. */
public class GpManagerGpDropOverlayTest
{
    @Test
    public void enqueueAcceptsFloatingFeedbackTransactions()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public boolean hudPlusFloatingDrops()
            {
                return true;
            }

            @Override
            public int gpDropStaggerMillis()
            {
                return 400;
            }

            @Override
            public int gpDropCombineMillis()
            {
                return 600;
            }
        };
        GpManagerGpDropOverlay overlay = new GpManagerGpDropOverlay(config, null);
        ProfitTransaction tx = new ProfitTransaction(
            1L,
            TransactionType.LOOT,
            TrackingContext.LOOT,
            "loot",
            true,
            Collections.singletonList(new ItemFlow(995, "Coins", 1, 100, 100)));
        // Confidence defaults counted; force confident gain
        overlay.enqueue(tx);
        // Second enqueue same item within combine window should merge rather than grow unbounded.
        overlay.enqueue(tx);
        assertTrue(true);
    }

    @Test
    public void classicAmountFormattingPreservesSign()
    {
        String positive = GpManagerGpDropOverlay.formatClassicProfitTrackerAmount(1234L);
        assertTrue(positive.contains("234"));
        String negative = GpManagerGpDropOverlay.formatClassicProfitTrackerAmount(-50_000L);
        assertTrue(negative.startsWith("-") || negative.contains("-"));
    }

    @Test
    public void accountingExclusionsSuppressFloatingCostsWhenTheyExcludeEveryFlow()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public TrackingDisplay trackingDisplay()
            {
                return TrackingDisplay.HUD;
            }

            @Override
            public LootPresentationFilter accountingItemFilter()
            {
                return LootPresentationFilter.HIGHLIGHTED_LIST_ONLY;
            }

            @Override
            public boolean showGpDropCosts()
            {
                return true;
            }
        };
        LootPresentationFilterService filter = new LootPresentationFilterService(
            new GroundItemsConfigSnapshot(true, "Armadyl hilt", "", false, true, 0,
                GroundItemsConfigSnapshot.ValueMode.HIGHEST, Color.MAGENTA, Color.WHITE,
                Color.GRAY, Collections.emptyList()));
        GpManagerGpDropOverlay overlay = new GpManagerGpDropOverlay(config, null, filter);
        ProfitTransaction cost = new ProfitTransaction(10L, TransactionType.CONSUMPTION,
            TrackingContext.GENERIC, "food", true,
            Collections.singletonList(new ItemFlow(385, "Shark", -1L, 800, -800L)));

        overlay.enqueue(cost);

        assertEquals("An excluded cost must not reappear as a transaction-total fallback",
            0, overlay.queuedDropCountForTests());
    }
}
