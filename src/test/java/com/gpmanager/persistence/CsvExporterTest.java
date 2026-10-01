package com.gpmanager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.BiPredicate;
import net.runelite.client.util.Filepath;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CsvExporterTest
{
    @Test
    public void protectsFormulaTextAndKeepsRepeatedExportsSeparate() throws Exception
    {
        Path directory = Files.createTempDirectory("gp-export-text");
        Session session = new Session("=1+1", 1L);
        session.addTransaction(Tx.of(2L, TransactionType.GAIN, Context.GENERIC,
            "@SUM(A1:A2)", true, Collections.singletonList(new Flow(1, "+cmd", 1, 5, 5))), 10);
        CsvExporter exporter = new CsvExporter();
        Filepath first = exporter.exportSession(session, FilepathTestSupport.root(directory));
        Filepath second = exporter.exportSession(session, FilepathTestSupport.root(directory));
        assertTrue(!first.equals(second));
        String details = read(first);
        assertTrue(details.contains("'=1+1"));
        assertTrue(details.contains("'@SUM(A1:A2)"));
        assertTrue(details.contains("'+cmd"));
    }
    @Test
    public void exportsOneItemLevelCsvPerGrind() throws Exception
    {
        Path directory = Files.createTempDirectory("profit-manager-test");
        Session session = new Session("Test Session", 1L);
        Transaction transaction = Tx.of(
            2L,
            TransactionType.LOOT,
            Context.LOOT,
            "Boss, phase 2",
            true,
            Collections.singletonList(
                new Flow(1, "Item, special", 2, 50, 100)));
        session.addTransaction(transaction, 100);
        session.correctTransaction(transaction.getId(), Correction.REVENUE, 3L, "Verified loot split");
        Transaction undone = Tx.of(
            4L, TransactionType.CONSUMPTION, Context.GENERIC, "Undo me", true,
            Collections.singletonList(new Flow(2, "Supply", -1, 25, -25)));
        session.addTransaction(undone, 100);
        session.undoLastTransaction(5L);
        session.restoreLastUndo(6L);

        session.recordAction("Boss", System.currentTimeMillis());
        Filepath output = new CsvExporter().exportSession(session, FilepathTestSupport.root(directory));
        String details = read(output);
        assertEquals("one file per export (owner 2026-09-28)", 1L, Files.list(directory).count());

        assertTrue(details.contains("transaction_id"));
        assertTrue(details.contains("record_kind"));
        assertTrue(details.contains(transaction.getId()));
        assertTrue(details.contains("\"Boss, phase 2\""));
        assertTrue(details.contains("\"Item, special\""));
        assertTrue(details.contains("price_source"));
        assertTrue(details.contains("pricing_summary"));
        assertTrue(details.contains("correction_reason"));
        assertTrue(details.contains("Verified loot split"));
        assertTrue(details.contains("confidence"));
        assertTrue(details.contains("explanation"));
        assertTrue(details.contains("activity_name"));
    }

    private static Transaction uncertainReceipt(long at, String itemName)
    {
        int itemId = (int) at;
        return new Transaction(at, null, TransactionType.UNCERTAIN,
            Context.GENERIC, "Uncertain", "Test", false,
            Collections.singletonList(new Flow(itemId, itemName, 1L, 100, 100L)),
            ClassificationConfidence.UNCERTAIN, "Needs owner decision", null);
    }

    @Test
    public void createsMissingExportDirectoryAndWritesUtf8() throws Exception
    {
        Path missing = Files.createTempDirectory("gp-export-create").resolve("nested").resolve("exports");
        assertFalse(Files.exists(missing));
        Session session = new Session("Ünicode", 1L);
        session.addTransaction(Tx.of(2L, TransactionType.GAIN, Context.GENERIC,
            "café — naïve", true, Collections.singletonList(new Flow(1, "Item", 1, 5, 5))), 10);

        Filepath output = new CsvExporter()
            .exportSession(session, FilepathTestSupport.root(missing));

        assertTrue("exporter must create the destination directory", Files.isDirectory(missing));
        String summary = read(output);
        assertTrue(summary.contains("Ünicode"));
        assertTrue(summary.contains("café — naïve"));
    }

    private static String read(Filepath filepath) throws IOException
    {
        return Files.readString(FilepathTestSupport.path(filepath), StandardCharsets.UTF_8);
    }

}
