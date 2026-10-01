package com.gpmanager;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/**
 * Synthetic long-session save harness. Measures wall time and file growth under a
 * TemporaryFolder — <strong>not</strong> a live RuneLite client soak.
 */
public class SyntheticLongSessionPerfTest
{
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void syntheticSaveWorkloadRecordsDurationAndGrowth() throws Exception
    {
        Path root = folder.newFolder("profit-manager").toPath();
        SessionRepository repository = new SessionRepository(new Gson(), FilepathTestSupport.root(root));
        long t0 = 1_700_000_000_000L;
        final int transactions = 2_000;

        long beforeBytes = dirSize(root);
        long startNs = System.nanoTime();
        int committed = 0;
        for (int round = 0; round < 8; round++)
        {
            Session session = new Session("General-" + round, t0, SessionMode.AUTO);
            for (int i = 0; i < transactions; i++)
            {
                session.addTransaction(Tx.of(
                    t0 + i * 1_000L,
                    TransactionType.GAIN,
                    Context.GENERIC,
                    "Skilling",
                    true,
                    Collections.singletonList(new Flow(1511, "Logs", 1L, 39, 39L))), transactions);
            }
            assertEquals("Measure retained records, not only compacted totals", transactions, session.getTransactions().size());
            SavedState state = new SavedState(session, null, false, Collections.emptyList());
            state.setRevision(round + 1L);
            if (PersistenceProbe.save(repository, state) || PersistenceProbe.replaceStateDetailed(repository, state).isCommitted())
            {
                committed++;
            }
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        long afterBytes = dirSize(root);
        long growth = Math.max(0L, afterBytes - beforeBytes);

        String report = String.format(Locale.ROOT,
            "synthetic_long_session_perf%n"
                + "label=SYNTHETIC_NOT_LIVE_CLIENT%n"
                + "transactions=%d%n"
                + "save_rounds=8%n"
                + "committed_saves=%d%n"
                + "elapsed_ms=%d%n"
                + "dir_bytes_before=%d%n"
                + "dir_bytes_after=%d%n"
                + "dir_growth_bytes=%d%n"
                + "note=CPU/UI-stall/live-log volume not measured here%n",
            transactions, committed, elapsedMs, beforeBytes, afterBytes, growth);

        Path outDir = Path.of("build", "reports", "perf");
        Files.createDirectories(outDir);
        Path out = outDir.resolve("synthetic-long-session.txt");
        Files.write(out, report.getBytes(StandardCharsets.UTF_8));

        assertEquals("Every measured save must commit: " + report, 8, committed);
        assertTrue("repository should write something", afterBytes > 0L);
        assertTrue(Files.exists(out));
    }

    private static long dirSize(Path root) throws IOException
    {
        if (!Files.exists(root))
        {
            return 0L;
        }
        final long[] total = {0L};
        try (java.util.stream.Stream<Path> paths = Files.walk(root))
        {
            paths.filter(Files::isRegularFile).forEach(path ->
            {
                try { total[0] += Files.size(path); }
                catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
            });
        }
        return total[0];
    }
}
