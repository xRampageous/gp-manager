package com.gpmanager;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Measures caller-thread snapshot cost for a long synthetic session: the one serialization a
 * save runs under the engine lock before enqueue. Background disk writing alone is not enough.
 */
public class SnapshotDetachCostTest
{
    @Test
    public void detachLongSessionStaysUnderBudget()
    {
        Gson gson = new Gson();
        Ad session = new Ad("Cost probe", 1_000L);
        List<Ac> txs = new ArrayList<>();
        for (int i = 0; i < 2_000; i++)
        {
            txs.add(Tx.of(
                1_000L + i,
                (long) i * 600L,
                Ai.LOOT,
                Aj.LOOT,
                "",
                "Probe",
                true,
                Collections.emptyList()));
        }
        for (Ac tx : txs)
        {
            session.kf(tx, 100_000);
        }
        SavedState live = new SavedState(session, null, false, Collections.emptyList());
        live.setRevision(42L);

        long bestNanos = Long.MAX_VALUE;
        for (int i = 0; i < 8; i++)
        {
            long started = System.nanoTime();
            String json = gson.toJson(live);
            long elapsed = System.nanoTime() - started;
            assertTrue(json.contains("\"revision\":42"));
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
