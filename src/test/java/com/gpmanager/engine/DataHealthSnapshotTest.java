package com.gpmanager.engine;

import com.gpmanager.GpManagerConfig;
import com.gpmanager.model.AlertEvent;
import com.gpmanager.model.AlertKind;
import com.gpmanager.model.CoinStore;
import com.gpmanager.model.DataHealthSnapshot;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DataHealthSnapshotTest
{
    @Test
    public void healthReportsRetentionProfileUnknownsAndCoinFreshnessFailClosed()
    {
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        long now = 100_000L;
        engine.ensureSession(now);
        engine.observeCoinStore(CoinStore.NMZ_COFFER, 123L, now);

        DataHealthSnapshot fresh = engine.getDataHealth(now);
        assertEquals(DataHealthSnapshot.CoinStoreFreshness.FRESH,
            fresh.getCoinStoreFreshness(CoinStore.NMZ_COFFER));
        assertEquals(DataHealthSnapshot.CoinStoreFreshness.UNOBSERVED,
            fresh.getCoinStoreFreshness(CoinStore.BLAST_FURNACE_COFFER));
        assertTrue(fresh.getProfileSize().getSessionCount() > 0L);

        DataHealthSnapshot stale = engine.getDataHealth(now + 8L * 24L * 60L * 60L * 1_000L);
        assertEquals(DataHealthSnapshot.CoinStoreFreshness.STALE,
            stale.getCoinStoreFreshness(CoinStore.NMZ_COFFER));
        assertEquals(0, stale.getRebuiltDays());
    }

    @Test
    public void alertListenersRunAfterEngineLockIsReleased()
    {
        GpManagerEngine engine = new GpManagerEngine(
            deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        AtomicBoolean lockHeld = new AtomicBoolean(true);
        engine.addAlertListener(event -> lockHeld.set(Thread.holdsLock(engine)));

        assertTrue(engine.recordAlert(new AlertEvent(AlertKind.SUPPLIES_LOW, 1L,
            null, "Supplies", "Measured", 0L, -1)));
        assertFalse(lockHeld.get());
    }
}
