package com.gpmanager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Every verb, reason and correction/review label, pinned against a golden written from the switch tables. */
public class LabelTablesTest
{
    private static final Path GOLDEN = Paths.get("src/test/resources/com/gpmanager/label-tables.txt");

    static String snapshot()
    {
        StringBuilder out = new StringBuilder();
        for (ActionKind kind : ActionKind.values())
        {
            out.append("action ").append(kind).append(" = ").append(SemanticFinancialProjection.actionVerb(kind)).append('\n');
        }
        out.append("correction null = ").append(SemanticFinancialProjection.correctionLabel(null)).append('\n');
        for (Correction correction : Correction.values())
        {
            out.append("correction ").append(correction).append(" = ")
                .append(SemanticFinancialProjection.correctionLabel(correction)).append(" | ")
                .append(LedgerPage.correctionLabel(correction)).append('\n');
        }
        for (ReviewDecision decision : ReviewDecision.values())
        {
            out.append("review ").append(decision).append(" = ").append(LedgerPage.reviewLabel(decision)).append('\n');
        }
        for (TransactionType type : TransactionType.values())
        {
            for (boolean counted : new boolean[]{true, false})
            {
                for (long value : new long[]{250L, -250L})
                {
                    Transaction tx = Tx.of(1_000L, type, Context.GENERIC, "", counted,
                        Collections.singletonList(new Flow(526, "Bones", value > 0 ? 1 : -1, 250, value)));
                    out.append("type ").append(type).append(' ').append(counted).append(' ').append(value).append(" = ")
                        .append(SemanticFinancialProjection.verbOf(tx)).append(" | ")
                        .append(SemanticFinancialProjection.why(tx, false, false)).append('\n');
                }
            }
            for (Correction correction : Correction.values())
            {
                Transaction tx = Tx.of(1_000L, type, Context.GENERIC, "", true,
                    Collections.singletonList(new Flow(526, "Bones", 1, 250, 250L)));
                tx.correction = correction;
                out.append("why ").append(type).append(' ').append(correction).append(" = ")
                    .append(SemanticFinancialProjection.why(tx, false, false)).append(" | ")
                    .append(SemanticFinancialProjection.why(tx, true, false)).append(" | ")
                    .append(SemanticFinancialProjection.why(tx, false, true)).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    public void labelsMatchTheGolden() throws java.io.IOException
    {
        String now = snapshot();
        if (Boolean.getBoolean("gp.golden.write"))
        {
            Files.createDirectories(GOLDEN.getParent());
            Files.write(GOLDEN, now.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(new String(Files.readAllBytes(GOLDEN), StandardCharsets.UTF_8).replace("\r\n", "\n"), now);
    }
}
