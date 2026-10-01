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
        Ad session = new Ad("=1+1", 1L);
        session.kf(Tx.of(2L, Ai.GAIN, Aj.GENERIC,
            "@SUM(A1:A2)", true, Collections.singletonList(new Ab(1, "+cmd", 1, 5, 5))), 10);
        CsvExporter exporter = new CsvExporter();
        Filepath first = exporter.si(session, FilepathTestSupport.root(directory));
        Filepath second = exporter.si(session, FilepathTestSupport.root(directory));
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
        Ad session = new Ad("Test Session", 1L);
        Ac transaction = Tx.of(
            2L,
            Ai.LOOT,
            Aj.LOOT,
            "Boss, phase 2",
            true,
            Collections.singletonList(
                new Ab(1, "Item, special", 2, 50, 100)));
        session.kf(transaction, 100);
        session.qi(transaction.getId(), Ah.REVENUE, 3L, "Verified loot split");
        Ac undone = Tx.of(
            4L, Ai.CONSUMPTION, Aj.GENERIC, "Undo me", true,
            Collections.singletonList(new Ab(2, "Supply", -1, 25, -25)));
        session.kf(undone, 100);
        session.akc(5L);
        session.agn(6L);

        session.aeh("Boss", System.currentTimeMillis());
        Filepath output = new CsvExporter().si(session, FilepathTestSupport.root(directory));
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

    private static Ac uncertainReceipt(long at, String itemName)
    {
        int itemId = (int) at;
        return new Ac(at, null, Ai.UNCERTAIN,
            Aj.GENERIC, "Uncertain", "Test", false,
            Collections.singletonList(new Ab(itemId, itemName, 1L, 100, 100L)),
            Bd.UNCERTAIN, "Needs owner decision", null);
    }

    @Test
    public void createsMissingExportDirectoryAndWritesUtf8() throws Exception
    {
        Path missing = Files.createTempDirectory("gp-export-create").resolve("nested").resolve("exports");
        assertFalse(Files.exists(missing));
        Ad session = new Ad("Ünicode", 1L);
        session.kf(Tx.of(2L, Ai.GAIN, Aj.GENERIC,
            "café — naïve", true, Collections.singletonList(new Ab(1, "Item", 1, 5, 5))), 10);

        Filepath output = new CsvExporter()
            .si(session, FilepathTestSupport.root(missing));

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
