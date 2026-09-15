package com.gpmanager.ui;

import com.gpmanager.FeedbackStyle;
import com.gpmanager.GpManagerConfig;
import com.gpmanager.TrackingDisplay;
import com.gpmanager.engine.GpManagerEngine;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.SessionMode;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.persistence.SavedState;
import com.gpmanager.reward.RewardItem;
import com.gpmanager.reward.RewardPresentationModel;
import com.gpmanager.reward.RewardPresentationPhase;
import com.gpmanager.reward.RewardSourceKind;
import com.gpmanager.model.ItemPriceSource;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TrackingDisplayModelTest
{
    @Test
    public void onlyFirstUseKeepsWaitingInTheGenericHeader()
    {
        GpManagerConfig config = new GpManagerConfig() {};
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        TrackingDisplayModel model = new TrackingDisplayModel(
            engine, config, new RewardPresentationModel(), null);

        TrackingDisplaySnapshot firstUse = model.snapshot(2_000L);
        assertEquals("Waiting", HudPlusHeaderLabel.resolve(firstUse));
        assertEquals("Waiting", HudPlusHeaderLabel.resolve(firstUse.withCharacterIdle(true)));

        engine.getActiveSession().addTransaction(new ProfitTransaction(
            2_100L,
            TransactionType.GAIN,
            TrackingContext.GENERIC,
            "Test",
            true,
            Collections.singletonList(new ItemFlow(995, "Coins", 1L, 1, 1L))), 0);
        model.invalidate();

        TrackingDisplaySnapshot afterFirstTransaction = model.snapshot(3_000L);
        assertEquals("", HudPlusHeaderLabel.resolve(afterFirstTransaction));
    }

    @Test
    public void neutralZonePlaceStateFlowsIntoSnapshotAndInvalidatesCache()
    {
        GpManagerConfig config = new GpManagerConfig() {};
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        TrackingDisplayModel model = new TrackingDisplayModel(
            engine, config, new RewardPresentationModel(), null);

        TrackingDisplaySnapshot before = model.snapshot(2_000L);
        assertFalse(before.isNeutralZoneActive());
        model.setNeutralZoneActive(true);
        TrackingDisplaySnapshot inside = model.snapshot(2_000L);
        assertNotSame(before, inside);
        assertTrue(inside.isNeutralZoneActive());
        assertEquals("Neutral zone", HudPlusHeaderLabel.resolve(inside));

        model.setNeutralZoneActive(false);
        assertFalse(model.snapshot(2_000L).isNeutralZoneActive());
    }

    @Test
    public void xpConfirmedTitleSurvivesUnrelatedEngineActivityAndClearsSeparately()
    {
        GpManagerConfig config = new GpManagerConfig() {};
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        TrackingDisplayModel model = new TrackingDisplayModel(
            engine, config, new RewardPresentationModel(), null);
        model.setConfirmedProcessTitle("Prayer");
        engine.getActiveSession().addTransaction(new ProfitTransaction(
            1_400L, TransactionType.GAIN, TrackingContext.GENERIC, "Test", true,
            Collections.singletonList(new ItemFlow(995, "Coins", 1L, 1, 1L))), 0);
        engine.setDetectedActivity("Chicken", 1_500L);

        TrackingDisplaySnapshot whileUnrelatedActivity = model.snapshot(2_000L);
        assertEquals("Chicken", whileUnrelatedActivity.getActivity());
        assertEquals("Prayer", whileUnrelatedActivity.getConfirmedProcessTitle());
        assertEquals("Prayer", HudPlusHeaderLabel.resolve(whileUnrelatedActivity));

        model.setConfirmedProcessTitle("");
        TrackingDisplaySnapshot cleared = model.snapshot(2_000L);
        assertEquals("", cleared.getConfirmedProcessTitle());
        assertEquals("", HudPlusHeaderLabel.resolve(cleared));
    }

    @Test
    public void wildernessRiskIsPresentationOnlyAndDropsOutsidePvp()
    {
        GpManagerConfig config = new GpManagerConfig() {};
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        TrackingDisplayModel model = new TrackingDisplayModel(
            engine, config, new RewardPresentationModel(), null);
        TrackingDisplaySnapshot before = model.snapshot(2_000L);
        WildernessRiskCalculator.Result result = WildernessRiskCalculator.calculate(
            Collections.singletonList(new WildernessRiskCalculator.ItemStack(995, 100L, 1L)),
            Collections.emptyList(),
            0);

        model.setWildernessRisk(true, result);
        TrackingDisplaySnapshot inPvp = model.snapshot(2_000L);
        assertNotSame(before, inPvp);
        assertTrue(inPvp.isPvpPossible());
        assertTrue(inPvp.hasKnownWildernessRisk());
        assertEquals(100L, inPvp.getWildernessRisk().getRiskValue());

        model.setWildernessRisk(false, result);
        TrackingDisplaySnapshot outsidePvp = model.snapshot(2_000L);
        assertFalse(outsidePvp.isPvpPossible());
        assertFalse(outsidePvp.hasKnownWildernessRisk());
    }

    @Test
    public void snapshotCachesPerTimestampAndKeepsRewardRevealSeparateFromSettled()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public TrackingDisplay trackingDisplay()
            {
                return TrackingDisplay.HUD_PLUS;
            }

            @Override
            public FeedbackStyle feedbackStyle()
            {
                return FeedbackStyle.INTEGRATED;
            }

            @Override
            public int gpDropDurationMillis()
            {
                return 500;
            }
        };
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(),
            new TransactionClassifier(),
            config);
        engine.ensureSession(1_000L);
        RewardPresentationModel rewards = new RewardPresentationModel();
        rewards.setLootCoalesceTicks(1);
        rewards.offerObservation(
            RewardSourceKind.NPC_LOOT,
            "Boss",
            "npc:Boss:1",
            Collections.singletonList(
                new RewardItem(2, "Armadyl hilt", 1L, 8_000_000L, true, ItemPriceSource.GRAND_EXCHANGE)),
            1_000L,
            true);

        TrackingDisplayModel model = new TrackingDisplayModel(engine, config, rewards, null);

        TrackingDisplaySnapshot first = model.snapshot(1_100L);
        TrackingDisplaySnapshot again = model.snapshot(1_100L);
        assertSame(first, again);
        assertNotNull(first.getLatestDrop());
        assertEquals("Armadyl hilt", first.getLatestDrop().getItemName());
        assertEquals(RewardPresentationPhase.REVEALING, first.getRewardPhase());
        assertTrue(first.isDropAnimating());

        TrackingDisplaySnapshot afterReveal = model.snapshot(
            1_000L + 600L + RewardPresentationModel.REVEAL_DWELL_MILLIS + 50L);
        // Advance past coalesce lock then dwell (snapshot alone may not tick mid-window).
        rewards.tick(1_000L + 600L + 1L);
        rewards.tick(1_000L + 600L + RewardPresentationModel.REVEAL_DWELL_MILLIS + 50L);
        afterReveal = model.snapshot(
            1_000L + 600L + RewardPresentationModel.REVEAL_DWELL_MILLIS + 50L);
        assertEquals(RewardPresentationPhase.NONE, afterReveal.getRewardPhase());
        assertFalse(afterReveal.isDropAnimating());
    }

    @Test
    public void displayedRateIsThrottledButTrueRateStaysCurrentForThePanel()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public TrackingDisplay trackingDisplay()
            {
                return TrackingDisplay.HUD_PLUS;
            }
        };
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        ProfitSession session = new ProfitSession("General", 0L, SessionMode.AUTO);
        session.addTransaction(new ProfitTransaction(0L, TransactionType.GAIN, TrackingContext.GENERIC,
            "Test", true, Collections.singletonList(
                new ItemFlow(995, "Coins", 1_000_000L, 1, 1_000_000L))), 0);
        engine.restore(new SavedState(session, Collections.emptyList()));

        TrackingDisplayModel model = new TrackingDisplayModel(engine, config, new RewardPresentationModel(), null);

        long trueAt1000 = model.trueProfitPerHour(1_000L);
        TrackingDisplaySnapshot first = model.snapshot(1_000L);
        assertEquals(trueAt1000, first.getProfitPerHour());

        // Sanity: the true rate really does move as elapsed time grows with a fixed net.
        long trueAt1500 = model.trueProfitPerHour(1_500L);
        assertNotEquals(trueAt1000, trueAt1500);

        // Within the ~1s throttle window, the displayed rate must not flicker to the
        // new true value even though it has changed.
        TrackingDisplaySnapshot withinThrottle = model.snapshot(1_500L);
        assertEquals(first.getProfitPerHour(), withinThrottle.getProfitPerHour());

        // Once the throttle window elapses, the displayed rate resamples to current.
        long trueAt2050 = model.trueProfitPerHour(2_050L);
        TrackingDisplaySnapshot afterThrottle = model.snapshot(2_050L);
        assertEquals(trueAt2050, afterThrottle.getProfitPerHour());
        assertNotEquals(first.getProfitPerHour(), afterThrottle.getProfitPerHour());

        // Net, revenue, and elapsed time are never throttled — only the rate.
        assertEquals(1_000_000L, afterThrottle.getNet());
    }

    @Test
    public void invalidateForcesImmediateRateResample()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override
            public TrackingDisplay trackingDisplay()
            {
                return TrackingDisplay.HUD_PLUS;
            }
        };
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        ProfitSession session = new ProfitSession("General", 0L, SessionMode.AUTO);
        session.addTransaction(new ProfitTransaction(0L, TransactionType.GAIN, TrackingContext.GENERIC,
            "Test", true, Collections.singletonList(
                new ItemFlow(995, "Coins", 1_000_000L, 1, 1_000_000L))), 0);
        engine.restore(new SavedState(session, Collections.emptyList()));

        TrackingDisplayModel model = new TrackingDisplayModel(engine, config, new RewardPresentationModel(), null);
        model.snapshot(1_000L);

        model.invalidate();

        long trueAt1050 = model.trueProfitPerHour(1_050L);
        TrackingDisplaySnapshot afterInvalidate = model.snapshot(1_050L);
        assertEquals(trueAt1050, afterInvalidate.getProfitPerHour());
    }
}
