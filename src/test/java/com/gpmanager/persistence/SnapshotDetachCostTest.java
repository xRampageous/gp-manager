package com.gpmanager.persistence;

import com.google.gson.Gson;
import com.gpmanager.model.ProfitSession;
import com.gpmanager.model.ProfitTransaction;
import com.gpmanager.model.TrackingContext;
import com.gpmanager.model.TransactionType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Measures caller-thread detach/serialization cost for a long synthetic session.
 * Background disk writing alone is not enough — this covers the snapshot path
 * that still runs on the client thread before enqueue.
 */
public class SnapshotDetachCostTest
{
    @Test
    public void detachLongSessionStaysUnderBudget()
    {
        SessionRepository repository = new SessionRepository(new Gson());
        ProfitSession session = new ProfitSession("Cost probe", 1_000L);
        List<ProfitTransaction> txs = new ArrayList<>();
        for (int i = 0; i < 2_000; i++)
        {
            txs.add(new ProfitTransaction(
                1_000L + i,
                (long) i * 600L,
                TransactionType.LOOT,
                TrackingContext.LOOT,
                "",
                "Probe",
                true,
                Collections.emptyList()));
        }
        for (ProfitTransaction tx : txs)
        {
            session.addTransaction(tx, 100_000);
        }
        SavedState live = new SavedState(session, Collections.emptyList());
        live.setRevision(42L);

        long bestNanos = Long.MAX_VALUE;
        for (int i = 0; i < 8; i++)
        {
            long started = System.nanoTime();
            SavedState detached = repository.detach(live);
            long elapsed = System.nanoTime() - started;
            assertTrue(detached != null && detached.getActiveSession() != null);
            bestNanos = Math.min(bestNanos, elapsed);
        }
        long bestMillis = TimeUnit.NANOSECONDS.toMillis(bestNanos);
        // Soft budget for a 2k-transaction detach on a developer workstation.
        // Recorded for the progress checkpoint; fail only on extreme stalls.
        assertTrue(
            "Detach took " + bestMillis + " ms (budget 250 ms)",
            bestMillis < 250L);
        System.out.println("SnapshotDetachCostTest best detach ms=" + bestMillis
            + " transactions=" + txs.size());
    }
}
