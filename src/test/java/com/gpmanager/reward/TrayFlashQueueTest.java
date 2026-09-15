package com.gpmanager.reward;

import com.gpmanager.model.ItemFlow;
import com.gpmanager.model.ItemPriceSource;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import com.gpmanager.ui.HudTrayState;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TrayFlashQueueTest
{
    @Test
    public void secondPeerFlashQueuesBehindFirst()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(500L);
        ProfitTransaction deposit = transfer("Bank deposit",
            new ItemFlow(1511, "Oak logs", -1L, 39, -39L));
        model.offerSkillingOrConfirmed(deposit, 1_000L, true);
        assertEquals("Deposit", HudTrayState.tagLine(model.current()));
        assertEquals(0, model.getFlashQueueSize());

        // Withdrew while bank closed queues behind Deposit.
        ProfitTransaction withdraw = transfer("Bank withdraw",
            new ItemFlow(385, "Shark", 5L, 500, 2_500L));
        model.offerSkillingOrConfirmed(withdraw, 1_100L, true);
        assertEquals("Deposit", HudTrayState.tagLine(model.current()));
        assertEquals(1, model.getFlashQueueSize());

        model.tick(1_000L + 500L + 1L);
        assertNotNull(model.current());
        assertEquals("Withdrew", HudTrayState.tagLine(model.current()));
        assertEquals(0, model.getFlashQueueSize());
    }

    @Test
    public void sameTagPeersCoalesceInQueue()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(400L);
        model.offerSkillingOrConfirmed(transfer("Bank deposit",
            new ItemFlow(1511, "Oak logs", -1L, 39, -39L)), 1_000L, true);
        model.offerSkillingOrConfirmed(transfer("Bank deposit",
            new ItemFlow(1519, "Logs", -2L, 40, -80L)), 1_050L, true);
        model.offerSkillingOrConfirmed(transfer("Bank deposit",
            new ItemFlow(1513, "Magic logs", -1L, 100, -100L)), 1_060L, true);
        // Same-tag Deposit merges into the live tray (not a second queued flash).
        assertEquals(0, model.getFlashQueueSize());
        assertEquals("Deposit", HudTrayState.tagLine(model.current()));
        assertTrue(model.current().getItems().size() >= 3);
    }

    @Test
    public void multiClickWithdrawMergesIntoLiveWithdrewTray()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(800L);
        model.offerSkillingOrConfirmed(transfer("Bank withdraw",
            new ItemFlow(385, "Shark", 5L, 500, 2_500L)), 1_000L, true);
        assertEquals("Withdrew", HudTrayState.tagLine(model.current()));
        assertEquals(1, model.current().getItems().size());

        model.offerSkillingOrConfirmed(transfer("Bank withdraw",
            new ItemFlow(314, "Anchovy", 10L, 10, 100L)), 1_100L, true);
        model.offerSkillingOrConfirmed(transfer("Bank withdraw",
            new ItemFlow(1511, "Oak logs", 3L, 39, 117L)), 1_150L, true);

        assertEquals("Withdrew", HudTrayState.tagLine(model.current()));
        assertEquals(0, model.getFlashQueueSize());
        assertEquals(3, model.current().getItems().size());
    }

    @Test
    public void bankOpenBuffersWithdrawThenFlushesOneTray()
    {
        RewardPresentationModel model = new RewardPresentationModel();
        model.setRevealDwellMillis(500L);
        model.setBankUiOpen(true);
        model.offerSkillingOrConfirmed(transfer("Bank withdraw",
            new ItemFlow(385, "Shark", 5L, 500, 2_500L)), 1_000L, true);
        model.offerSkillingOrConfirmed(transfer("Bank withdraw",
            new ItemFlow(314, "Anchovy", 10L, 10, 100L)), 1_050L, true);
        assertTrue(model.current() == null || !"Withdrew".equals(HudTrayState.tagLine(model.current())));

        model.setBankUiOpen(false);
        assertEquals("Withdrew", HudTrayState.tagLine(model.current()));
        assertEquals(2, model.current().getItems().size());
        assertEquals(0, model.getFlashQueueSize());
    }

    private static ProfitTransaction transfer(String note, ItemFlow flow)
    {
        return new ProfitTransaction(
            System.currentTimeMillis(),
            null,
            TransactionType.TRANSFER,
            TrackingContext.TRANSFER,
            note,
            "Bank",
            false,
            Collections.singletonList(flow));
    }
}
