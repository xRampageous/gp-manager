package com.gpmanager.reward;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.ui.HudTrayState;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ActivityHoldPresentationTest
{
    @Test
    public void activityHoldKeepsSkillingCardPastDwell()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(500L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 4L, 39L), 1_000L, true);
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        model.setActivityHold(true);
        model.tick(2_000L);
        assertNotNull(model.current());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        assertTrue(model.isActivityHold());
    }

    @Test
    public void activityHoldAccumulatesDifferentSkillingItems()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setActivityHold(true);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 4L, 39L), 1_000L, true);
        model.offerSkillingOrConfirmed(woodcutting(1519, "Logs", 2L, 40L), 1_200L, true);
        assertEquals(2, model.current().getItems().size());
        assertEquals("Chopped", HudTrayState.tagLine(model.current()));
    }

    @Test
    public void activityHoldAccumulatesSameItemStackValue()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setActivityHold(true);
        model.offerSkillingOrConfirmed(woodcutting(1519, "Willow logs", 1L, 22L), 1_000L, true);
        model.offerSkillingOrConfirmed(woodcutting(1519, "Willow logs", 1L, 22L), 1_200L, true);
        assertEquals(1, model.current().getItems().size());
        RewardItem stack = model.current().getItems().get(0);
        assertEquals(2L, stack.getQuantity());
        assertEquals(44L, stack.getRecordedValue());
        assertTrue(stack.compactLabel().contains("×2"));
    }

    @Test
    public void releasingHoldSchedulesNormalDwell()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(400L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 1L, 39L), 1_000L, true);
        model.tick(1_000L);
        model.setActivityHold(true);
        assertEquals(1f, model.revealProgress(1_000L, 400L), 0.001f);
        model.setActivityHold(false);
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        long releaseAt = model.getRevealExpiresAtEpochMillis();
        model.tick(releaseAt + 1L);
        assertEquals(null, model.current());
    }

    @Test
    public void revealProgressFullWhileActivityHeld()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(500L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 1L, 39L), 1_000L, true);
        model.setActivityHold(true);
        assertEquals(1f, model.revealProgress(1_050L, 500L), 0.001f);
        assertEquals(1f, model.revealProgress(10_000L, 500L), 0.001f);
    }

    @Test
    public void idleReleaseDoesNotRestartEntryClock()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(800L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 1L, 39L), 1_000L, true);
        long started = model.getRevealStartedAtEpochMillis();
        model.setActivityHold(true);
        model.setActivityHold(false);
        assertEquals(started, model.getRevealStartedAtEpochMillis());
        assertTrue(model.getRevealExpiresAtEpochMillis() > 0L);
        assertTrue(model.getRevealExpiresAtEpochMillis() < Long.MAX_VALUE / 8);
    }

    @Test
    public void activityHoldKeepsProcessingMixedPastDwell()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(400L);
        ProfitTransaction shafts = new ProfitTransaction(
            1_000L,
            null,
            TransactionType.PROCESSING,
            TrackingContext.PRODUCTION,
            "Fletched",
            "Fletching",
            true,
            java.util.Arrays.asList(
                new ItemFlow(1511, "Logs", -1L, 100, -100L),
                new ItemFlow(52, "Arrow shaft", 15L, 5, 75L)));
        model.offerSkillingOrConfirmed(shafts, 1_000L, true);
        model.setActivityHold(true);
        model.tick(2_000L);
        assertNotNull(model.current());
        assertEquals("Fletched", HudTrayState.tagLine(model.current()));
        assertEquals(2, model.current().getItems().size());
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
        model.setActivityHold(false);
        model.tick(2_000L + 500L);
        assertEquals(null, model.current());
    }

    @Test
    public void openTrayUnderHoldKeepsFrozenExpiry()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(500L);
        model.setPinnedExpanded(true, 1_000L);
        model.offerSkillingOrConfirmed(woodcutting(1511, "Oak logs", 1L, 39L), 1_000L, true);
        model.setActivityHold(true);
        assertTrue(model.getRevealExpiresAtEpochMillis() >= Long.MAX_VALUE / 8);
        model.offerSkillingOrConfirmed(woodcutting(1519, "Logs", 1L, 40L), 1_200L, true);
        assertTrue(model.isActivityHold());
        assertTrue(model.getRevealExpiresAtEpochMillis() >= Long.MAX_VALUE / 8);
        assertEquals(RewardPresentationPhase.REVEALING, model.phase());
    }

    private static ProfitTransaction woodcutting(int id, String name, long qty, long unit)
    {
        return new ProfitTransaction(
            System.currentTimeMillis(),
            null,
            TransactionType.GAIN,
            TrackingContext.GENERIC,
            "Gathered",
            "Woodcutting",
            true,
            Collections.singletonList(new ItemFlow(id, name, qty, (int) unit, unit * qty)));
    }
}
