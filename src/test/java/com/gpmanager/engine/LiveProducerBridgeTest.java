package com.gpmanager.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.engine.TransactionClassifier;
import com.gpmanager.model.ProfitTransaction;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class LiveProducerBridgeTest
{
    @Test
    public void wealthObserveMapsStashMenu()
    {
        AtomicReference<String> slot = new AtomicReference<>();
        LiveProducerBridge.observeWealthFromMenu(
            (id, detail) -> slot.set(id), "Search", "STASH unit");
        assertEquals("stash", slot.get());
    }

    @Test
    public void deathFeeBooksObservedCoins()
    {
        GpManagerConfig config = new GpManagerConfig() {};
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), config);
        engine.ensureSession(1_000L);
        ProfitTransaction tx = LiveProducerBridge.tryBookDeathFee(
            engine, "You pay a fee of 10000 coins", 10_000L, false, 1_000L);
        assertNotNull(tx);
        assertTrue(tx.isCounted());
        assertEquals(-10_000L, tx.getNet());
    }

    @Test
    public void pendingRewardUiDetected()
    {
        assertTrue(LiveProducerBridge.isPendingRewardUiOpen("Barrows chest"));
        assertFalse(LiveProducerBridge.isPendingRewardUiOpen("Oak tree"));
    }
}
