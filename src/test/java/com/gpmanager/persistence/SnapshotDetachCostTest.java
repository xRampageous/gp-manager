package com.gpmanager;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Measures actual caller-thread save capture for a long synthetic session. Encoding belongs to the writer.
 */
public class SnapshotDetachCostTest
{
    @Test
    public void detachLongSessionStaysUnderBudget() throws Exception
    {
        Gson gson = new Gson();
        Session session = new Session("Cost probe", 1_000L, SessionMode.GENERAL);
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 2_000; i++)
        {
            txs.add(Tx.of(
                1_000L + i,
                (long) i * 600L,
                TransactionType.LOOT,
                Context.LOOT,
                "",
                "Probe",
                true,
                Collections.emptyList()));
        }
        for (Transaction tx : txs)
        {
            session.addTransaction(tx, 100_000, false);
        }

        Engine engine = new Engine(deltas -> Collections.emptyList(), new TransactionClassifier(), new GpManagerConfig() {});
        engine.general = session;
        engine.current = session;
        SessionRepository repository = new SessionRepository(gson,
            FilepathTestSupport.root(java.nio.file.Files.createTempDirectory("gp-capture-cost")), false);
        Persistence persistence = new Persistence(null, null, repository, new OrderedPersistenceWriter(repository, null), engine);
        long bestNanos = Long.MAX_VALUE;
        for (int i = 0; i < 8; i++)
        {
            long started = System.nanoTime();
            WriteIntent captured = persistence.currentIntent();
            long elapsed = System.nanoTime() - started;
            org.junit.Assert.assertNull("capture leaves JSON encoding to the writer", captured.json);
            org.junit.Assert.assertEquals(txs.size(), captured.state.generalSession.transactions.size());
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
