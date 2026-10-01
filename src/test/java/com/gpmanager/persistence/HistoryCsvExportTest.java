package com.gpmanager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Account history CSV: canonical correction-aware summaries, honest compaction, no peer data. */
public class HistoryCsvExportTest
{
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    static
    {
        JsonCodec.bind(new com.google.gson.Gson());
    }

    @Test
    public void exportsOneTruthfulRowPerRetainedGrind() throws Exception
    {
        Engine engine = engine();
        long now = 1_700_000_000_000L;
        archived(engine, "Vorkath", now, TransactionType.GAIN, 6_400L);
        archived(engine, "Zulrah", now + 3_600_000L, TransactionType.CONSUMPTION, -1_200L);

        Filepath directory = FilepathTestSupport.root(folder.getRoot().toPath());
        Filepath exported = new CsvExporter().exportHistory(engine.getHistory(), directory, now + 7_200_000L);
        List<String> lines = Files.readAllLines(FilepathTestSupport.path(exported), StandardCharsets.UTF_8);

        assertEquals("header + one row per retained Grind", 3, lines.size());
        assertTrue(lines.get(0).startsWith("grind_name,grind_id,session_id"));
        assertTrue("lineage id is exported for stable identity", lines.get(0).contains("grind_id"));
        assertTrue("no Party peer columns", !lines.get(0).contains("party") && !lines.get(0).contains("peer"));
        String vorkathRow = lines.stream().filter(line -> line.contains("Vorkath")).findFirst().orElse("");
        String zulrahRow = lines.stream().filter(line -> line.contains("Zulrah")).findFirst().orElse("");
        assertTrue("positive booked truth: " + vorkathRow, vorkathRow.contains("6400"));
        assertTrue("negative booked truth: " + zulrahRow, zulrahRow.contains("-1200"));
    }

    @Test
    public void compactedHistoryIsLabelledNotFabricated() throws Exception
    {
        Engine engine = engine();
        long now = 1_700_000_000_000L;
        engine.startCustomSession("Old grind", SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(receipt(now + 1_000L, TransactionType.GAIN, 400L), 2_000);
        engine.getActiveSession().addTransaction(receipt(now + 2_000L, TransactionType.GAIN, 400L), 2_000);
        engine.finishCustomSession(now + 3_000L);
        Session closed = engine.getHistory().get(0);
        closed.compactTransactionsBefore(now + 2_000L, transaction -> false);

        Filepath directory = FilepathTestSupport.root(folder.getRoot().toPath());
        Filepath exported = new CsvExporter().exportHistory(engine.getHistory(), directory, now + 3_600_000L);
        String csv = Files.readString(FilepathTestSupport.path(exported), StandardCharsets.UTF_8);

        assertTrue("compacted detail is labelled", csv.contains("SUMMARY_ONLY_COMPACTED"));
        assertTrue("retained totals still reconcile", csv.contains("800"));
        assertFalse("no receipt-level detail is fabricated", csv.contains("-details"));
    }

    @Test
    public void correctionAwareTotalsAndCostSplitHonesty() throws Exception
    {
        Engine engine = engine();
        long now = 1_700_000_000_000L;
        engine.startCustomSession("Vorkath", SessionMode.GENERAL, now);
        Transaction change = receipt(now + 1_000L, TransactionType.GAIN, 200L);
        engine.getActiveSession().addTransaction(change, 2_000);
        // Corrections apply while the Grind is current; the archived row then carries the result.
        assertTrue(engine.correctTransaction(change.getId(), Correction.COST, now + 1_500L, "history csv"));
        engine.finishCustomSession(now + 2_000L);

        Filepath directory = FilepathTestSupport.root(folder.getRoot().toPath());
        Filepath exported = new CsvExporter().exportHistory(engine.getHistory(), directory, now + 3_600_000L);
        String csv = Files.readString(FilepathTestSupport.path(exported), StandardCharsets.UTF_8);

        assertTrue("the correction is reflected in the exported net", csv.contains("-200"));
        assertTrue("split availability is stated, never guessed",
            csv.contains("UNAVAILABLE") || csv.contains("AVAILABLE"));
        assertTrue("historical prices stay booked (no repricing columns)", !csv.contains("current_price"));
    }

    @Test
    public void emptyHistoryStillProducesAHeaderOnlyReport() throws Exception
    {
        Filepath directory = FilepathTestSupport.root(folder.getRoot().toPath());
        Filepath exported = new CsvExporter().exportHistory(Collections.emptyList(), directory,
            1_700_000_000_000L);
        List<String> lines = Files.readAllLines(FilepathTestSupport.path(exported), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
    }

    private static void archived(Engine engine, String name, long now, TransactionType type, long value)
    {
        engine.startCustomSession(name, SessionMode.GENERAL, now);
        engine.getActiveSession().addTransaction(receipt(now + 1_000L, type, value), 2_000);
        engine.finishCustomSession(now + 2_000L);
    }

    private static Transaction receipt(long at, TransactionType type, long value)
    {
        return new Transaction(at, null, type, Context.GENERIC, "", "Vorkath", true,
            Collections.singletonList(new Flow(1519, "Willow logs", value >= 0L ? 5L : -5L,
                (int) Math.abs(value / 5L), value)),
            ClassificationConfidence.LIKELY, "test", null);
    }

    private static Engine engine()
    {
        GpManagerConfig config = new GpManagerConfig()
        {
            @Override public int stabilizationTicks() { return 0; }
        };
        return new Engine(deltas ->
        {
            java.util.List<Flow> flows = new java.util.ArrayList<>();
            deltas.forEach((id, quantity) ->
                flows.add(new Flow(id, "Willow logs", quantity, 40, quantity * 40)));
            return flows;
        }, new TransactionClassifier(), config);
    }
}
